package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.Config;
import com.volcanicmg.mccitybuilder.planner.ColonyGeometry;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

/**
 * Colony buildings whose hut chunk this client doesn't draw (no chunk, or beyond render distance from the planner
 * camera; Distant Horizons shows only LOD terrain there): in the planner each gets its outline box, picking reaches it
 * through its box ({@link Planner#pickUnder}), and the nearest {@link #MAX_PREVIEWS} their blocks at
 * the current level as Structurize previews. A preview that stays keeps its slot (no rebuild); it goes when the chunk
 * loads, the building goes, the planner closes or on logout.
 */
final class FarBuildings {
    /** Far buildings previewed at once (nearest the camera first); each costs a Structurize render buffer. */
    static final int MAX_PREVIEWS = 16;
    // Buildings in unloaded chunks, found each tick.
    private static List<IBuildingView> far = List.of();
    // Queued previews by what they show (pos|pack|path|rot) → their slot (key PREVIEW_KEY_far_slot), and freed slots.
    private static final Map<String, Integer> previews = new HashMap<>();
    private static final java.util.ArrayDeque<Integer> freeSlots = new java.util.ArrayDeque<>();
    private static int nextSlot;

    private FarBuildings() {
    }

    /** Each client tick: the far buildings and their previews while the planner is open, else nothing kept. */
    static void tick() {
        IColonyView colony = Planner.colony();
        Minecraft mc = Minecraft.getInstance();
        if (!Planner.active() || colony == null || mc.level == null || !Config.SHOW_UNLOADED_BUILDINGS.get()) {
            clear();
            return;
        }
        List<IBuildingView> out = new ArrayList<>();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        java.util.Set<BlockPos> was = new java.util.HashSet<>();
        for (IBuildingView b : far) was.add(b.getPosition());
        for (IBuildingView b : colony.getClientBuildingManager().getBuildings().values()) {
            BlockPos p = b.getPosition();
            // The chunk itself: the client's hasChunk stays true for chunks the server told it to drop.
            if (mc.level.getChunkSource().getChunk(p.getX() >> 4, p.getZ() >> 4, false) == null || undrawn(p.getX(), p.getZ(), was.contains(p))) out.add(b);
        }
        out.sort(java.util.Comparator.comparingDouble(b -> b.getPosition().distToCenterSqr(cam)));
        far = out;
        previews(out);
    }

    /** Keeps the nearest far buildings' previews queued (one per building at its current level, blueprint loaded). */
    private static void previews(List<IBuildingView> near) {
        record Want(String pack, String path, int rot, BlockPos at) {
        }
        Map<String, Want> want = new LinkedHashMap<>();
        // Only the nearest few start loads (not every hut in the colony).
        for (IBuildingView b : near.subList(0, Math.min(near.size(), 2 * MAX_PREVIEWS))) {
            if (want.size() >= MAX_PREVIEWS) break;
            // Level 0: nothing built yet.
            if (b.getBuildingLevel() < 1 || b.getStructurePack() == null || b.getStructurePath() == null || b.getRotationMirror() == null) continue;
            String now = HutCatalog.levelPath(b.getStructurePath(), b.getBuildingLevel()), path = now != null ? now : b.getStructurePath();
            int rot = b.getRotationMirror().ordinal();
            if (PlanView.blueprint(b.getStructurePack(), path, rot) != null)
                want.put(b.getPosition().asLong() + "|" + b.getStructurePack() + "|" + path + "|" + rot, new Want(b.getStructurePack(), path, rot, b.getPosition()));
        }
        for (var it = previews.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (want.containsKey(en.getKey())) continue;
            RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_far_" + en.getValue());
            freeSlots.add(en.getValue());
            it.remove();
        }
        for (var en : want.entrySet()) {
            if (previews.containsKey(en.getKey())) continue;
            Want w = en.getValue();
            int slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.poll();
            BlueprintPreviewData data = new BlueprintPreviewData(false);
            data.setRenderBlocksNice(true);
            // Turn first: setBlueprint applies the turn to the shared blueprint once, not NONE then back.
            data.setRotationMirror(com.ldtteam.structurize.api.RotationMirror.values()[w.rot()]);
            data.setBlueprint(PlanView.blueprint(w.pack(), w.path(), w.rot()));
            data.setPos(w.at());
            RenderingCache.queue(Planner.PREVIEW_KEY + "_far_" + slot, data);
            previews.put(en.getKey(), slot);
        }
    }

    /** Planner closed, toggle off or logout: every far preview goes. */
    static void clear() {
        far = List.of();
        if (previews.isEmpty()) return;
        for (int slot : previews.values()) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_far_" + slot);
        previews.clear();
        freeSlots.clear();
        nextSlot = 0;
    }

    /**
     * @return true when block column x, z is beyond render distance from the planner camera (its chunk, not the
     * player's: the client may hold chunks out to the server's view distance, past what it draws)
     * @param wasFar hysteresis for something already treated as far ({@link ColonyGeometry#beyondRender})
     */
    static boolean undrawn(int x, int z, boolean wasFar) {
        Minecraft mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        return ColonyGeometry.beyondRender((x >> 4) - (net.minecraft.util.Mth.floor(cam.x) >> 4), (z >> 4) - (net.minecraft.util.Mth.floor(cam.z) >> 4),
                mc.options.getEffectiveRenderDistance(), wasFar);
    }

    /** @return the buildings shown as far this tick (nearest the camera first) */
    static List<IBuildingView> far() {
        return far;
    }

    /** Every far building's box, a soft blue outline. */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        for (IBuildingView b : far) LevelRenderer.renderLineBox(pose, lines, Planner.aabb(b), 0.55f, 0.75f, 1f, 0.7f);
    }

    /** Dev bridge: far buildings and their preview slots. */
    static String dump() {
        return "far=" + far.size() + " previews=" + new java.util.TreeMap<>(previews);
    }
}
