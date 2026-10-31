package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.planner.ColonyGeometry;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.server.PlanShare;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Colony mates' ghosts and plans ({@link PlanShare}), drawn read-only as outlines in a colour per player with their
 * name, plus the real blocks as Structurize previews (the nearest {@link #MAX_PREVIEWS}; segments stay strips); never
 * part of this player's plan (not selectable, cleared or built). Also sends this player's own: the ghost
 * when it changes (at most 5 a second), the plan a second after its last change.
 * <p>
 * ponytail: boxes written into the line batch each frame (at most 256 entries + 256 segments a player); a cached
 * buffer like PlanView's others' outlines if many mates plan at once.
 */
final class Others {
    private static final long GHOST_MS = 200, PLAN_DEBOUNCE_MS = 1000;
    // Others' live ghosts by owner, with the dimension they came in (colony ids repeat across dimensions).
    private static final Map<UUID, PlanShare.Ghost> ghosts = new HashMap<>();
    private static final Map<UUID, String> ghostDims = new HashMap<>();
    // Others' plans per "dimension|colony", by owner.
    private static final Map<String, Map<UUID, PlanShare.Shared>> plans = new HashMap<>();
    // A planned segment's ground height, once known.
    private static final Map<Plan.Line, Integer> lineY = new HashMap<>();
    // What was last sent and when; the colony of the loaded plan and when it is due to go (0: nothing to send).
    private static PlanShare.Ghost sentGhost;
    private static long sentGhostMs, planDue;
    private static int planColony = -1;
    /** Mates' blueprints previewed at once (nearest the camera first); each costs a Structurize render buffer. */
    static final int MAX_PREVIEWS = 24;
    // Queued previews by what they show (see previews()) → their slot (key PREVIEW_KEY_mate_slot), and freed slots.
    private static final Map<String, Integer> previews = new HashMap<>();
    private static final java.util.ArrayDeque<Integer> freeSlots = new java.util.ArrayDeque<>();
    private static final Map<String, BlockPos> previewAt = new HashMap<>();
    private static int nextSlot;

    private Others() {
    }

    private static String dim() {
        var l = Minecraft.getInstance().level;
        return l == null ? "" : l.dimension().location().toString();
    }

    static void onGhost(PlanShare.Ghost g) {
        if (g.kind() == PlanShare.NONE) {
            ghosts.remove(g.owner());
            ghostDims.remove(g.owner());
        } else {
            ghosts.put(g.owner(), g);
            ghostDims.put(g.owner(), dim());
        }
    }

    static void onShared(PlanShare.Shared s) {
        Map<UUID, PlanShare.Shared> m = plans.computeIfAbsent(dim() + "|" + s.colony(), k -> new HashMap<>());
        if (s.entries().isEmpty() && s.lines().isEmpty()) m.remove(s.owner());
        else m.put(s.owner(), s);
    }

    /** Logout: nothing kept (colony ids repeat across servers). */
    static void clear() {
        hidePreviews();
        ghosts.clear();
        ghostDims.clear();
        plans.clear();
        lineY.clear();
        sentGhost = null;
        planDue = 0;
        planColony = -1;
    }

    /** The planner asks for the colony's records: its plans and live ghosts here come again in full. */
    static void forget(int colony) {
        String d = dim();
        plans.remove(d + "|" + colony);
        lineY.clear();
        ghosts.values().removeIf(g -> g.colony() == colony && d.equals(ghostDims.get(g.owner())));
        ghostDims.keySet().retainAll(ghosts.keySet());
    }

    /** Logging out: a plan change still waiting goes now (best effort, before the connection closes). */
    static void flush() {
        if (planDue != 0) sendPlan();
    }

    /** Before another colony's plan replaces the loaded one: a change still pending for it goes now. */
    static void flushFor(int colony) {
        if (planDue != 0 && colony != planColony) sendPlan();
    }

    /** A plan was loaded (planner entry): shared, so mates see it even if it was made before sharing existed. */
    static void planLoaded(int colony) {
        planColony = colony;
        planChanged();
    }

    /** The plan changed: shared once it has been still for a second. */
    static void planChanged() {
        planDue = System.currentTimeMillis() + PLAN_DEBOUNCE_MS;
    }

    private static boolean channel() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && c.hasChannel(PlanShare.Ghost.TYPE);
    }

    private static void sendPlan() {
        planDue = 0;
        if (planColony < 0 || !channel()) return;
        List<Plan.Entry> es = PlanView.entries.subList(0, Math.min(PlanView.entries.size(), PlanShare.MAX_ENTRIES));
        List<Plan.Line> ls = new ArrayList<>();
        for (Plan.Line l : PlanView.lines) if (!l.built() && ls.size() < PlanShare.MAX_LINES) ls.add(l);
        PacketDistributor.sendToServer(new PlanShare.Shared(planColony, new UUID(0, 0), "", List.copyOf(es), ls));
    }

    /** Each client tick: this player's ghost when it changed (throttled), the plan when due. */
    static void tick() {
        if (Planner.active()) previews();
        else if (!previews.isEmpty()) hidePreviews();
        if (!channel()) return;
        long now = System.currentTimeMillis();
        PlanShare.Ghost g = Planner.active() ? ownGhost() : null;
        PlanShare.Ghost send = g != null ? g : sentGhost != null && sentGhost.kind() != PlanShare.NONE ? PlanShare.Ghost.none(sentGhost.colony()) : null;
        if (send != null && !send.equals(sentGhost) && now - sentGhostMs >= GHOST_MS) {
            PacketDistributor.sendToServer(send);
            sentGhost = send;
            sentGhostMs = now;
        }
        if (planDue != 0 && now >= planDue) sendPlan();
    }

    /** @return the ghost this player has out (a placement, a move, a segment being drawn), or null */
    private static PlanShare.Ghost ownGhost() {
        var c = Planner.colony();
        if (c == null) return null;
        BlockPos at = Planner.anchor();
        if (Planner.placingHut != null && at != null) {
            int kind = Planner.moving != null || Planner.movingDeco != null ? PlanShare.MOVE : Planner.deco != null ? PlanShare.DECO : PlanShare.HUT;
            return PlanShare.Ghost.of(c.getID(), kind, Planner.placingHut.pack(), Planner.placingHut.path(), Planner.deco != null ? 0 : Planner.placingLevel, at,
                    Planner.rotation.ordinal(), BlockPos.ZERO);
        }
        if (LineView.active() && LineView.start != null && LineView.end != null) {
            Integer gy = LineView.liveY != Integer.MIN_VALUE ? Integer.valueOf(LineView.liveY) : Planner.groundAt(LineView.start[0], LineView.start[1]);
            int y = gy == null ? 64 : gy;
            return PlanShare.Ghost.of(c.getID(), PlanShare.LINE, PlannerScreen.pack(), LineView.wall() ? "wall" : "road", 0,
                    new BlockPos(LineView.start[0], y, LineView.start[1]), 0, new BlockPos(LineView.end[0], y, LineView.end[1]));
        }
        return null;
    }

    /**
     * Keeps mates' Structurize previews in step: their ghosts always, their plan entries with plan mode or a line tool
     * on, the nearest {@link #MAX_PREVIEWS} with a loaded blueprint; one that stays keeps its slot (no rebuild).
     */
    private static void previews() {
        // A ghost's key is its owner and design (a move only shifts it); a plan entry's includes where it stands.
        record Want(String key, String pack, String path, int rot, BlockPos at) {
        }
        List<Want> all = new ArrayList<>();
        for (var g : ghostsHere())
            if (g.kind() != PlanShare.LINE) all.add(new Want("g|" + g.owner() + "|" + g.pack() + "|" + g.path() + "|" + g.rot(), g.pack(), g.path(), g.rot(), g.pos()));
        if (PlanView.on || LineView.active())
            for (var s : plansHere())
                for (Plan.Entry e : s.entries())
                    all.add(new Want("p|" + e.pack() + "|" + e.path() + "|" + e.rot() + "|" + e.x() + "|" + e.y() + "|" + e.z(), e.pack(), e.path(), e.rot(), new BlockPos(e.x(), e.y(), e.z())));
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        all.sort(java.util.Comparator.comparingDouble(w -> w.at().distToCenterSqr(cam)));
        Map<String, Want> want = new java.util.LinkedHashMap<>();
        // Only the nearest few start loads (not every design in every mate's plan).
        for (Want w : all.subList(0, Math.min(all.size(), 2 * MAX_PREVIEWS))) {
            if (want.size() >= MAX_PREVIEWS) break;
            if (PlanView.blueprint(w.pack(), w.path(), w.rot()) != null) want.putIfAbsent(w.key(), w);
        }
        for (var it = previews.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (want.containsKey(en.getKey())) continue;
            RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_mate_" + en.getValue());
            freeSlots.add(en.getValue());
            previewAt.remove(en.getKey());
            it.remove();
        }
        for (var en : want.entrySet()) {
            Want w = en.getValue();
            Integer had = previews.get(en.getKey());
            if (had != null) {
                // Moved: the queued preview follows (no rebuild).
                BlueprintPreviewData data = RenderingCache.getBlueprintPreviewData(Planner.PREVIEW_KEY + "_mate_" + had);
                if (data != null && !w.at().equals(previewAt.get(en.getKey()))) data.setPos(w.at());
                previewAt.put(en.getKey(), w.at());
                continue;
            }
            int slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.poll();
            BlueprintPreviewData data = new BlueprintPreviewData(false);
            data.setRenderBlocksNice(true);
            // Turn first: setBlueprint applies the turn to the shared blueprint once, not NONE then back.
            data.setRotationMirror(RotationMirror.values()[Math.floorMod(w.rot(), RotationMirror.values().length)]);
            data.setBlueprint(PlanView.blueprint(w.pack(), w.path(), w.rot()));
            data.setPos(w.at());
            RenderingCache.queue(Planner.PREVIEW_KEY + "_mate_" + slot, data);
            previews.put(en.getKey(), slot);
            previewAt.put(en.getKey(), w.at());
        }
    }

    /** Planner closed or logout: every mate preview goes. */
    private static void hidePreviews() {
        for (int slot : previews.values()) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_mate_" + slot);
        previews.clear();
        previewAt.clear();
        freeSlots.clear();
        nextSlot = 0;
    }

    /** @return the player's colour (ARGB, opaque), the same on every client */
    static int colour(UUID owner) {
        return 0xFF000000 | Mth.hsvToRgb((owner.hashCode() & 0xFFFF) / 65536f, 0.55f, 1f);
    }

    private static List<PlanShare.Ghost> ghostsHere() {
        var c = Planner.colony();
        List<PlanShare.Ghost> out = new ArrayList<>();
        if (c == null) return out;
        for (var g : ghosts.values()) if (g.colony() == c.getID() && dim().equals(ghostDims.get(g.owner()))) out.add(g);
        return out;
    }

    private static List<PlanShare.Shared> plansHere() {
        var c = Planner.colony();
        return c == null ? List.of() : new ArrayList<>(plans.getOrDefault(dim() + "|" + c.getID(), Map.of()).values());
    }

    /** @return a ghost's block box: its blueprint's footprint (the anchor block while it loads), or a segment's strip */
    private static int[] box(PlanShare.Ghost g) {
        BlockPos p = g.pos();
        if (g.kind() == PlanShare.LINE)
            return new int[]{Math.min(p.getX(), g.to().getX()), p.getY(), Math.min(p.getZ(), g.to().getZ()), Math.max(p.getX(), g.to().getX()), p.getY(), Math.max(p.getZ(), g.to().getZ())};
        int[] r = Blueprints.bounds(g.pack(), g.path(), RotationMirror.values()[Math.floorMod(g.rot(), RotationMirror.values().length)]);
        return r == null ? new int[]{p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()} : ColonyGeometry.placed(r, p.getX(), p.getY(), p.getZ());
    }

    /** @return a planned segment's strip at the ground under its middle (64 until loaded) */
    private static int[] box(Plan.Line l) {
        Integer y = lineY.get(l);
        if (y == null) {
            y = Planner.groundAt((l.x0() + l.x1()) / 2, (l.z0() + l.z1()) / 2);
            if (y != null) lineY.put(l, y);
        }
        int gy = y == null ? 64 : y;
        return new int[]{Math.min(l.x0(), l.x1()), gy, Math.min(l.z0(), l.z1()), Math.max(l.x0(), l.x1()), gy, Math.max(l.z0(), l.z1())};
    }

    private static void line(PoseStack pose, VertexConsumer out, int[] b, double grow, int argb, float a) {
        AABB box = new AABB(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1).inflate(grow);
        LevelRenderer.renderLineBox(pose, out, box, (argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, a);
    }

    /** Others' ghosts always; their plans with plan mode or a line tool on (like this player's own). */
    static void renderWorld(PoseStack pose, VertexConsumer out) {
        for (var g : ghostsHere()) line(pose, out, box(g), 0.05, colour(g.owner()), 1f);
        if (!PlanView.on && !LineView.active()) return;
        for (var s : plansHere()) {
            int c = colour(s.owner());
            for (Plan.Entry e : s.entries()) line(pose, out, e.box(), 0.06, c, 0.7f);
            for (Plan.Line l : s.lines()) line(pose, out, box(l), 0.06, c, 0.7f);
        }
    }

    /** Names over others' ghosts and plan entries (a label that would overlap one already drawn is skipped). */
    static void labels(GuiGraphics g, Font font, int width, int height) {
        List<int[]> drawn = new ArrayList<>();
        for (var gh : ghostsHere()) {
            int[] b = box(gh);
            String what = gh.kind() == PlanShare.LINE ? gh.path() : file(gh.path());
            label(g, font, width, height, drawn, b, Component.translatable(gh.kind() == PlanShare.MOVE ? "mccitybuilder.others.moving"
                    : gh.kind() == PlanShare.LINE ? "mccitybuilder.others.drawing" : "mccitybuilder.others.placing", gh.name(), what), colour(gh.owner()));
        }
        if (!PlanView.on && !LineView.active()) return;
        for (var s : plansHere()) {
            for (Plan.Entry e : s.entries())
                label(g, font, width, height, drawn, e.box(), Component.translatable("mccitybuilder.others.planned", s.name(), file(e.path())), colour(s.owner()));
            for (Plan.Line l : s.lines())
                label(g, font, width, height, drawn, box(l), Component.translatable("mccitybuilder.others.planned", s.name(), l.tool()), colour(s.owner()));
        }
    }

    private static String file(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replace(".blueprint", "");
    }

    private static void label(GuiGraphics g, Font font, int width, int height, List<int[]> drawn, int[] b, Component t, int colour) {
        double[] ndc = Planner.project((b[0] + b[3] + 1) / 2.0, b[4] + 1.5, (b[2] + b[5] + 1) / 2.0);
        if (ndc == null || Math.abs(ndc[0]) > 1 || Math.abs(ndc[1]) > 1) return;
        int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height), tw = font.width(t);
        int[] r = {sx - tw / 2 - 3, sy - 6, sx + tw / 2 + 3, sy + 6};
        for (int[] o : drawn) if (r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3]) return;
        drawn.add(r);
        g.fill(r[0], r[1], r[2], r[3], 0xC0202020);
        g.drawString(font, t, sx - tw / 2, sy - 4, colour, false);
    }

    /** Dev bridge: what this client knows of others. */
    static String dump() {
        StringBuilder sb = new StringBuilder();
        for (var gh : ghosts.values())
            sb.append("G ").append(gh.name()).append(' ').append(gh.colony()).append(' ').append(gh.kind()).append(' ').append(gh.path()).append(' ').append(gh.pos().toShortString()).append('\n');
        plans.forEach((k, m) -> m.values().forEach(s -> sb.append("P ").append(k).append(' ').append(s.name()).append(" entries=").append(s.entries().size())
                .append(" lines=").append(s.lines().size()).append('\n')));
        sb.append("sentGhost=").append(sentGhost == null ? "null" : sentGhost.kind() + " " + sentGhost.path()).append(" planDue=").append(planDue).append(" planColony=").append(planColony).append(" previews=").append(new java.util.TreeMap<>(previews));
        return sb.toString();
    }
}
