package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.network.messages.BuildToolPlacementMessage;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.server.InstantBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Plan mode: ghosts dropped into a plan instead of being built, shown as Structurize previews with their footprints
 * (green, or red with a conflict), edited one at a time (select by clicking its box; arrows, PgUp/PgDn, R, M, [ ],
 * Delete), saved per world and colony under {@code mccitybuilder/plans/}, and committed together: survival places
 * the huts it has items for (then build requests), creative pastes everything through the server part one at a time
 * (or Structurize's pretty paste without it).
 */
final class PlanView {
    static boolean on;
    static List<Plan.Entry> entries = new ArrayList<>();
    static List<Plan.Conflict> conflicts = List.of();
    static int selected = -1;
    private static Path file;
    private static int previews;
    private static boolean dirty = true;
    // Commit queue: creative requests go one at a time, each waiting for its building (or a short time).
    private static final Deque<Object> queue = new ArrayDeque<>();
    private static Object waitingFor;
    // Drawn road / wall segments, their computed blocks (client terrain) and whether each has a conflict.
    static List<Plan.Line> lines = new ArrayList<>();
    /** Joint type per joint cell ({@link Plan#cell}); missing = auto. */
    static Map<Long, Integer> jointTypes = new HashMap<>();
    /** Built segments' pieces as built ({@link Plan#lineKey}); built segments without them (older plans) are laid out once and frozen. */
    static Map<String, List<Plan.BuiltPiece>> builtPieces = new HashMap<>();
    static List<int[]> lineOffs = new ArrayList<>();
    static List<Integer> lineNeed = new ArrayList<>();
    /** Per joint cell from the last plan layout: {role, uncovered cells, the same with a tower} (edit-mode labels). */
    static Map<Long, int[]> jointNotes = new HashMap<>();
    static Map<Long, Double> jointMatch = new HashMap<>();
    static java.util.Set<Long> jointGapAir = new java.util.HashSet<>();
    static List<List<PieceCatalog.Placed>> linePieces = new ArrayList<>();
    static List<Boolean> lineBad = new ArrayList<>();
    static List<Integer> lineShort = new ArrayList<>();
    static List<int[]> lineGaps = new ArrayList<>();
    static List<Boolean> lineComplete = new ArrayList<>();
    static List<int[]> lineHeights = new ArrayList<>();
    static List<List<Boolean>> pieceBad = new ArrayList<>();
    /** Bumped whenever the planned lines are laid out again (the live segment re-lays out against them). */
    static int layoutVersion;
    private static boolean linesDirty = true;
    private static int linePreviews;
    static int selectedLine = -1;
    private static long waitUntil;
    private static final Map<String, CompletableFuture<Blueprint>> loads = new HashMap<>();
    private static final Map<String, Blueprint> rotated = new HashMap<>();

    private PlanView() {
    }

    /** Loads the plan for this world and colony (on planner entry). */
    static void load(IColonyView colony) {
        Minecraft mc = Minecraft.getInstance();
        String world = mc.getSingleplayerServer() != null ? "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName()
                : "mp_" + (mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown");
        String dim = colony.getDimension().location().toString();
        file = mc.gameDirectory.toPath().resolve("mccitybuilder").resolve("plans").resolve(safe(world)).resolve(safe(dim) + "_" + colony.getID() + ".txt");
        // Plans saved under the old name (ColonyPlanner) are read from there until this one is saved.
        Path old = mc.gameDirectory.toPath().resolve("colonyplanner").resolve(mc.gameDirectory.toPath().resolve("mccitybuilder").relativize(file));
        try {
            Path from = Files.exists(file) ? file : Files.exists(old) ? old : null;
            String text = from != null ? Files.readString(from) : null;
            entries = new ArrayList<>(Plan.read(text));
            lines = new ArrayList<>(Plan.readLines(text));
            jointTypes = new java.util.HashMap<>(Plan.readJoints(text));
            builtPieces = new HashMap<>(Plan.readBuilt(text));
        } catch (Exception e) {
            CityBuilder.LOGGER.warn("CityBuilder: can't read plan {}", file, e);
            entries = new ArrayList<>();
            lines = new ArrayList<>();
            jointTypes = new HashMap<>();
            builtPieces = new HashMap<>();
        }
        PieceCatalog.builtPieces = builtPieces;
        LineView.resetUndo();
        builtEntries.clear();
        removalLine.clear();
        selected = -1;
        selectedLine = -1;
        linesDirty = true;
        queue.clear();
        waitingFor = null;
        dirty = true;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void save() {
        dirty = true;
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            // Joint types of joints that no longer exist (removed, moved, replaced segments) go.
            Plan.pruneJoints(jointTypes, lines);
            Plan.pruneBuilt(builtPieces, lines);
            Files.writeString(file, Plan.write(entries, lines, jointTypes, builtPieces));
        } catch (Exception e) {
            CityBuilder.LOGGER.warn("CityBuilder: can't save plan {}", file, e);
        }
    }

    /** Adds the ghost being placed to the plan. @param hut the hut block, or null for a decoration */
    static void add(Block hut, Blueprints.Hut bp, int level, BlockPos at, RotationMirror rot) {
        int[] r = boundsOf(bp.blueprint());
        String id = hut == null ? null : BuiltInRegistries.BLOCK.getKey(hut).toString();
        entries.add(new Plan.Entry(id, bp.pack(), bp.path(), level, at.getX(), at.getY(), at.getZ(), rot.ordinal(),
                at.getX() + r[0], at.getY() + r[1], at.getZ() + r[2], at.getX() + r[3], at.getY() + r[4], at.getZ() + r[5]));
        selected = entries.size() - 1;
        save();
    }

    /** @return the blueprint's footprint offsets from its anchor, inclusive (the blueprint as currently rotated) */
    private static int[] boundsOf(Blueprint bp) {
        BlockPos o = bp.getPrimaryBlockOffset();
        return new int[]{-o.getX(), -o.getY(), -o.getZ(), bp.getSizeX() - 1 - o.getX(), bp.getSizeY() - 1 - o.getY(), bp.getSizeZ() - 1 - o.getZ()};
    }

    // ---- editing the selected entry ----

    static boolean hasSelection() {
        return on && selected >= 0 && selected < entries.size();
    }

    static void move(int dx, int dy, int dz) {
        if (!hasSelection()) return;
        entries.set(selected, entries.get(selected).moved(dx, dy, dz));
        save();
    }

    static void rotate(boolean mirror) {
        if (!hasSelection()) return;
        Plan.Entry e = entries.get(selected);
        RotationMirror rm = RotationMirror.values()[e.rot()];
        rm = mirror ? rm.mirrorate() : rm.rotate(Rotation.CLOCKWISE_90);
        // The box follows once the newly rotated bounds are known (refresh); until then the old box stays.
        entries.set(selected, e.with(e.path(), e.level(), rm.ordinal(), e.box()));
        save();
    }

    /** Huts: level up / down within the style pack's files. */
    static void level(int d) {
        if (!hasSelection() || !entries.get(selected).isHut()) return;
        Plan.Entry e = entries.get(selected);
        Block hut = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.hut()));
        Blueprints.Hut one = Blueprints.get(e.pack(), hut);
        int max = one == null ? e.level() : Blueprints.maxLevel(one), lv = Math.max(1, Math.min(max, e.level() + d));
        String path = HutCatalog.levelPath(e.path(), lv);
        if (path == null || lv == e.level()) return;
        entries.set(selected, e.with(path, lv, e.rot(), e.box()));
        save();
    }

    static void delete() {
        if (!hasSelection()) return;
        entries.remove(selected);
        selected = -1;
        save();
    }

    static void clear() {
        LineView.checkpoint(true);
        entries.clear();
        selected = -1;
        // Segments already on their way to the server stay until it answers.
        lines.removeIf(l -> !sending.containsKey(l));
        selectedLine = -1;
        LineView.endChain();
        linesDirty = true;
        save();
    }

    /** @return the index of the smallest planned box containing the block, or -1 */
    static int entryAt(BlockPos pos) {
        List<int[]> boxes = new ArrayList<>();
        for (Plan.Entry e : entries) boxes.add(e.box());
        return com.volcanicmg.mccitybuilder.planner.ColonyGeometry.smallestContaining(boxes, pos.getX(), pos.getY(), pos.getZ());
    }

    // ---- per frame: boxes, conflicts, previews ----

    /** @return the planned blueprint rotated for this entry, or null while it loads (starts the load) */
    private static Blueprint blueprint(Plan.Entry e) {
        Minecraft mc = Minecraft.getInstance();
        String key = e.pack() + "|" + e.path() + "|" + e.rot();
        Blueprint done = rotated.get(key);
        if (done != null || mc.level == null) return done;
        CompletableFuture<Blueprint> f = loads.computeIfAbsent(key, k -> StructurePacks.getBlueprintFuture(e.pack(), e.path(), true, mc.level.registryAccess()));
        if (!f.isDone()) return null;
        Blueprint bp = f.isCompletedExceptionally() ? null : f.getNow(null);
        if (bp == null) return null;
        bp.setRotationMirror(RotationMirror.values()[e.rot()], mc.level);
        rotated.put(key, bp);
        return bp;
    }

    /** Keeps boxes in step with rotations and levels, recomputes conflicts and the Structurize previews. */
    static void refresh() {
        IColonyView colony = Planner.colony();
        if (colony == null) return;
        for (int i = 0; i < entries.size(); i++) {
            Plan.Entry e = entries.get(i);
            Blueprint bp = blueprint(e);
            if (bp == null) continue;
            int[] r = boundsOf(bp), box = {e.x() + r[0], e.y() + r[1], e.z() + r[2], e.x() + r[3], e.y() + r[4], e.z() + r[5]};
            if (!java.util.Arrays.equals(box, e.box())) {
                entries.set(i, e.with(e.path(), e.level(), e.rot(), box));
                dirty = true;
            }
        }
        if (!dirty) return;
        List<int[]> buildings = new ArrayList<>();
        for (IBuildingView b : colony.getClientBuildingManager().getBuildings().values()) buildings.add(Planner.box(b));
        conflicts = Plan.conflicts(entries, buildings, Planner.claimed());
        // Structurize previews, one key per entry; stale keys removed.
        for (int i = 0; i < Math.max(previews, entries.size()); i++) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_plan_" + i);
        previews = 0;
        boolean allLoaded = true;
        if (on)
            for (int i = 0; i < entries.size(); i++) {
                Plan.Entry e = entries.get(i);
                Blueprint bp = blueprint(e);
                if (bp == null) {
                    allLoaded = false;
                    continue;
                }
                BlueprintPreviewData data = new BlueprintPreviewData(false);
                data.setRenderBlocksNice(true);
                data.setBlueprint(bp);
                data.setRotationMirror(RotationMirror.values()[e.rot()]);
                data.setPos(new BlockPos(e.x(), e.y(), e.z()));
                RenderingCache.queue(Planner.PREVIEW_KEY + "_plan_" + i, data);
                previews = i + 1;
            }
        dirty = !allLoaded;
    }

    /** Footprint outlines: white when selected, green when buildable, red with a conflict. */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        if (on || LineView.active())
            for (int i = 0; i < linePieces.size() && i < PlanView.lines.size(); i++) {
                List<Boolean> bs = i < pieceBad.size() ? pieceBad.get(i) : List.of();
                boolean hover = LineView.edit ? LineView.hoverPreview(net.minecraft.client.gui.screens.Screen.hasAltDown()).contains(i) : i == LineView.hoverLine;
                if (LineView.selected(i)) {
                    outline(pose, lines, linePieces.get(i), List.of(), false, 0.25f, 0.9f, 1f, 1f);
                    continue;
                }
                if (PlanView.lines.get(i).built() && !hover && i != selectedLine) {
                    // Built records: faint grey, no ghost (the real wall is there).
                    outline(pose, lines, linePieces.get(i), List.of(), false, 0.55f, 0.55f, 0.55f, 0.5f);
                    continue;
                }
                if (hover) outline(pose, lines, linePieces.get(i), List.of(), false, 1f, 0.9f, 0.5f, 0.8f);
                else outline(pose, lines, linePieces.get(i), bs, i == selectedLine);
                if (i < lineGaps.size() && lineGaps.get(i) != null && !linePieces.get(i).isEmpty()) gap(pose, lines, lineGaps.get(i), linePieces.get(i).get(0).anchor().getY());
                floating(pose, lines, linePieces.get(i));
            }
        if (!on) return;
        for (int i = 0; i < entries.size(); i++) {
            Plan.Entry e = entries.get(i);
            boolean bad = i < conflicts.size() && conflicts.get(i) != Plan.Conflict.NONE;
            AABB box = new AABB(e.minX(), e.minY(), e.minZ(), e.maxX() + 1, e.maxY() + 1, e.maxZ() + 1).inflate(0.04);
            if (i == selected) LevelRenderer.renderLineBox(pose, lines, box, 1f, 1f, 1f, 1f);
            else LevelRenderer.renderLineBox(pose, lines, box, bad ? 1f : 0.3f, bad ? 0.25f : 1f, bad ? 0.25f : 0.3f, 0.9f);
        }
    }

    static void hide() {
        PieceCatalog.clearDrafts();
        LineView.closeJointMenu(false);
        LineView.clearLive();
        for (int i = 0; i < linePreviews; i++) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_piece_" + i);
        linePreviews = 0;
        linesDirty = true;
        for (int i = 0; i < previews; i++) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_plan_" + i);
        previews = 0;
        dirty = true;
    }

    // ---- committing ----

    /**
     * Builds the plan (or just the selected entry). Creative: queued, one request at a time. Survival: huts with an
     * item in the inventory are placed (build requests follow); the rest stay in the plan and are reported.
     *
     * @return a message for the player
     */
    static Component commit(boolean selectedOnly) {
        LineView.closeJointMenu(false);
        Minecraft mc = Minecraft.getInstance();
        boolean creative = Planner.creative(), server = Planner.serverInstant();
        Map<Plan.Build, Integer> kept = new java.util.EnumMap<>(Plan.Build.class);
        int items = 0, segments = 0;
        Map<Block, Integer> left = new HashMap<>();
        List<Plan.Entry> keep = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Plan.Entry e = entries.get(i);
            if (selectedOnly && i != selected) {
                keep.add(e);
                continue;
            }
            if (entryQueue.pending(e)) {
                kept.merge(Plan.Build.IN_FLIGHT, 1, Integer::sum);
                keep.add(e);
                continue;
            }
            boolean outside = i < conflicts.size() && conflicts.get(i) == Plan.Conflict.OUTSIDE;
            Block hut = e.isHut() ? BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.hut())) : null;
            boolean have = false;
            if (hut != null && !creative) {
                int n = left.computeIfAbsent(hut, h -> mc.player.getInventory().countItem(h.asItem()));
                boolean locked = hut instanceof AbstractBlockHut<?> h && !h.areRequirementsMet(mc.level, mc.player.blockPosition(), mc.player);
                have = n > 0 && !locked;
                if (have) left.put(hut, n - 1);
            }
            Plan.Build d = Plan.decide(outside, creative, server, e.isHut(), have);
            if (d != Plan.Build.SEND) {
                kept.merge(d, 1, Integer::sum);
                keep.add(e);
                continue;
            }
            items++;
            if (server && (creative || !e.isHut())) {
                // Through the server part: stays in the plan until the server says it was built (or why not).
                if (entryQueue.idle() && !entryRun) entryQueue.reset();
                entryQueue.add(e);
                entryRun = true;
                keep.add(e);
                continue;
            }
            if (creative) {
                queue.add(e);
                continue;
            }
            BlockPos at = new BlockPos(e.x(), e.y(), e.z());
            // Survival places level 1 (the builder builds up); the planned level stays a plan.
            String path = HutCatalog.levelPath(e.path(), 1);
            new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Survival, "minecolonies", e.pack(), path == null ? e.path() : path, at,
                    RotationMirror.values()[e.rot()]).sendToServer();
            Planner.expectBuilding(at);
        }
        entries = keep;
        selected = -1;
        // Roads and walls, in drawing order: real pieces through the server part (creative: placed; survival: work orders).
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            if (selectedOnly && i != selectedLine || l.built()) continue;
            List<PieceCatalog.Placed> ps = i < linePieces.size() ? linePieces.get(i) : List.of();
            Plan.Build d = Plan.decideLine(server, i < lineBad.size() && lineBad.get(i), ps.isEmpty(), sending.containsKey(l),
                    i < lineComplete.size() && lineComplete.get(i));
            if (d != Plan.Build.SEND) {
                kept.merge(d, 1, Integer::sum);
                continue;
            }
            // The segment stays in the plan until the server has accepted all its batches (see onBatchResult).
            // Joint pieces go first (the layout's overlap rule counts on it in creative), then the runs.
            List<PieceCatalog.Placed> ordered = new ArrayList<>();
            for (PieceCatalog.Placed p : ps) if (p.joint()) ordered.add(p);
            for (PieceCatalog.Placed p : ps) if (!p.joint()) ordered.add(p);
            List<InstantBuild.Piece> batch = new ArrayList<>();
            List<PieceCatalog.Placed> sent = new ArrayList<>();
            int n = 0;
            for (PieceCatalog.Placed p : ordered) {
                batch.add(new InstantBuild.Piece(p.path(), p.anchor(), PieceCatalog.rotation(p.turns()).ordinal()));
                sent.add(p);
                if (batch.size() == InstantBuild.MAX_BATCH) {
                    queueBatch(l, batch, sent);
                    n++;
                    batch = new ArrayList<>();
                    sent = new ArrayList<>();
                }
            }
            if (!batch.isEmpty()) {
                queueBatch(l, batch, sent);
                n++;
            }
            sending.put(l, n);
            segments++;
        }
        selectedLine = -1;
        LineView.endChain();
        linesDirty = true;
        save();
        progressTotal = queue.size() + (waitingFor != null ? 1 : 0);
        progressDone = 0;
        MutableComponent msg = items + segments == 0 ? Component.translatable("mccitybuilder.plan.nothing")
                : Component.translatable("mccitybuilder.plan.building", items, segments);
        for (var en : kept.entrySet())
            msg.append(" · ").append(Component.translatable("mccitybuilder.plan.kept." + en.getKey().name().toLowerCase(java.util.Locale.ROOT), en.getValue()));
        // Chat too: the toast is short, the reasons must not be missed.
        if (mc.player != null) mc.player.displayClientMessage(msg, false);
        return msg;
    }

    // Build progress: requests sent so far out of those queued by the last Build.
    private static int progressTotal, progressDone;

    // Plan entries going through the server part, one at a time, kept until answered (see BuildQueue).
    private static final com.volcanicmg.mccitybuilder.planner.BuildQueue<Plan.Entry> entryQueue = new com.volcanicmg.mccitybuilder.planner.BuildQueue<>();
    private static boolean entryRun;

    /** @return "Building 3 / 7" while a build runs, else null */
    static Component progress() {
        if (!entryQueue.idle()) {
            int t = entryQueue.total();
            return Component.translatable("mccitybuilder.plan.progress", t - entryQueue.left() + 1, t);
        }
        int left = queued();
        if (left == 0 || progressTotal == 0) return null;
        return Component.translatable("mccitybuilder.plan.progress", Math.min(progressTotal, progressDone), progressTotal);
    }

    /** Sends the next plan entry once the previous one was answered; at the end, "Built n / m" and what stayed, why. */
    private static void tickEntries(long now) {
        int id = nextBatchId;
        Plan.Entry e = entryQueue.next(now, id);
        if (e != null) {
            nextBatchId++;
            BlockPos at = new BlockPos(e.x(), e.y(), e.z());
            int mode = e.isHut() ? InstantBuild.PLACE : Planner.creative() ? InstantBuild.DECO : InstantBuild.DECO_ORDER;
            PacketDistributor.sendToServer(new InstantBuild.Request(mode, at, e.isHut() ? e.level() : 0, e.pack(), e.path(), RotationMirror.values()[e.rot()], id));
            return;
        }
        if (!entryRun || !entryQueue.idle()) return;
        entryRun = false;
        MutableComponent msg = Component.translatable("mccitybuilder.plan.built", entryQueue.done().size(), entryQueue.total());
        for (var k : entryQueue.kept()) {
            Plan.Entry x = k.item();
            String name = x.path().substring(x.path().lastIndexOf('/') + 1).replace(".blueprint", "");
            msg.append(" · ").append(Component.translatable("mccitybuilder.plan.stayed." + k.reason(), name));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(msg, false);
    }

    /** Creative commit queue: one request at a time, the next once the building appears (huts) or after a pause. */
    static void tick() {
        IColonyView colony = Planner.colony();
        long now = System.currentTimeMillis();
        tickEntries(now);
        if (waitingFor != null) {
            boolean there = waitingFor instanceof Plan.Entry w && w.isHut() && colony != null
                    && colony.getClientBuildingManager().getBuilding(new BlockPos(w.x(), w.y(), w.z())) != null;
            if (!there && now < waitUntil) return;
            // No answer to a batch in time: its segment stays in the plan, the rest of it isn't sent.
            if (waitingFor instanceof InstantBuild.PieceBatch b && now >= waitUntil) onBatchResult(new InstantBuild.BatchResult(b.id(), false, "timeout"));
            waitingFor = null;
        }
        Object next = queue.poll();
        if (next == null) return;
        progressDone++;
        if (next instanceof InstantBuild.PieceBatch b) {
            // Road / wall pieces: real style-pack decorations, up to 16 per batch, in drawing order; the next batch
            // goes once the server answered this one (accepted or refused), at least 1.1 s apart (its rate limit).
            PacketDistributor.sendToServer(b);
            waitUntil = now + 10_000;
            waitingFor = b;
            return;
        }
        Plan.Entry e = (Plan.Entry) next;
        BlockPos at = new BlockPos(e.x(), e.y(), e.z());
        RotationMirror rot = RotationMirror.values()[e.rot()];
        if (Planner.serverInstant()) {
            int mode = e.isHut() ? InstantBuild.PLACE : Planner.creative() ? InstantBuild.DECO : InstantBuild.DECO_ORDER;
            PacketDistributor.sendToServer(new InstantBuild.Request(mode, at, e.isHut() ? e.level() : 0,
                    e.pack(), e.path(), rot));
            waitUntil = now + (e.isHut() ? 15_000 : 3_000);
        } else {
            new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Pretty, "", e.pack(), e.path(), at, rot).sendToServer();
            waitUntil = now + 1_500;
        }
        waitingFor = e;
    }

    // ---- drawn lines ----

    /** Lays out the lines into pieces (when they or the loaded pieces changed), their conflicts and ghosts. */
    static void refreshLines() {
        // An incomplete layout (pieces still loading) is redone when a piece finished loading, or twice a second for
        // what that doesn't count (style folder lists), not every frame.
        long now = System.currentTimeMillis();
        if (!linesDirty && !(waitingForPieces && (PieceCatalog.loadVersion != seenLoads || now - lastTry > 500))) return;
        seenLoads = PieceCatalog.loadVersion;
        lastTry = now;
        PieceCatalog.builtPieces = builtPieces;
        // Off: the paste order can't be guaranteed end to end (a dropped batch, Build selected, a joint next to a
        // built run), and a joint pasted after a run would clear the run's blocks where its ring is air. See DESIGN.
        com.volcanicmg.mccitybuilder.planner.PieceLayout.jointFirst = false;
        PieceCatalog.Layout lay = PieceCatalog.layout(lines, LineView.previewTypes());
        layoutVersion++;
        linePieces = lay.lines();
        // Older plans: a built segment without stored pieces is laid out this once and frozen.
        boolean froze = false;
        for (int i = 0; i < lines.size() && i < linePieces.size(); i++) {
            Plan.Line l = lines.get(i);
            String k = Plan.lineKey(l.x0(), l.z0(), l.x1(), l.z1());
            if (l.built() && !builtPieces.containsKey(k) && lay.lineComplete().get(i) && !linePieces.get(i).isEmpty()) {
                builtPieces.put(k, PieceCatalog.freeze(l, linePieces.get(i)));
                froze = true;
            }
        }
        if (froze) save();
        lineShort = lay.shortened();
        lineGaps = lay.gaps();
        lineComplete = lay.lineComplete();
        lineHeights = lay.heights();
        lineOffs = lay.offs();
        lineNeed = lay.need();
        jointNotes = new HashMap<>(PieceCatalog.jointNotes);
        jointMatch = new HashMap<>(PieceCatalog.jointMatch);
        jointGapAir = new java.util.HashSet<>(PieceCatalog.jointGapAir);
        lineBad = new ArrayList<>();
        pieceBad = new ArrayList<>();
        List<int[]> boxes = buildingBoxes();
        for (List<PieceCatalog.Placed> ps : linePieces) {
            List<Boolean> bs = new ArrayList<>();
            for (PieceCatalog.Placed p : ps) bs.add(bad(p, boxes));
            pieceBad.add(bs);
        }
        // Pieces that would clear or replace each other's blocks (crossing segments, a road through a wall): red too.
        // ponytail: all pairs, O(pieces^2); a grid index when plans grow past a few hundred pieces.
        for (int i = 0; i < linePieces.size() && i < lines.size(); i++)
            for (int k = 0; k < linePieces.get(i).size(); k++)
                for (int j = i; j < linePieces.size() && j < lines.size(); j++)
                    for (int m = j == i ? k + 1 : 0; m < linePieces.get(j).size(); m++)
                        if (PieceCatalog.clash(lines.get(i).pack(), linePieces.get(i).get(k), lines.get(j).pack(), linePieces.get(j).get(m))) {
                            pieceBad.get(i).set(k, true);
                            pieceBad.get(j).set(m, true);
                        }
        for (List<Boolean> bs : pieceBad) lineBad.add(bs.contains(true));
        // Structurize ghosts of every planned piece.
        for (int i = 0; i < linePreviews; i++) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_piece_" + i);
        linePreviews = 0;
        if (on || LineView.active())
            for (int i = 0; i < linePieces.size() && i < lines.size(); i++)
                for (PieceCatalog.Placed p : lines.get(i).built() || LineView.liveTouches(lines.get(i)) ? List.<PieceCatalog.Placed>of() : linePieces.get(i)) {
                    Blueprint bp = PieceCatalog.rotated(lines.get(i).pack(), p.path(), p.turns());
                    if (bp == null) continue;
                    BlueprintPreviewData data = new BlueprintPreviewData(false);
                    data.setRenderBlocksNice(true);
                    data.setBlueprint(bp);
                    data.setRotationMirror(PieceCatalog.rotation(p.turns()));
                    data.setPos(p.anchor());
                    RenderingCache.queue(Planner.PREVIEW_KEY + "_piece_" + linePreviews++, data);
                }
        linesDirty = false;
        waitingForPieces = !lay.complete();
    }

    /** @return the colony's building boxes {x0, y0, z0, x1, y1, z1} */
    static List<int[]> buildingBoxes() {
        IColonyView colony = Planner.colony();
        List<int[]> boxes = new ArrayList<>();
        if (colony != null) for (IBuildingView b : colony.getClientBuildingManager().getBuildings().values()) boxes.add(Planner.box(b));
        return boxes;
    }

    /** @return true when a piece is outside the colony's claims or runs into a building */
    static boolean bad(PieceCatalog.Placed p, List<int[]> buildings) {
        int[] bx = p.box();
        if (!com.volcanicmg.mccitybuilder.planner.ColonyGeometry.footprintClaimed(bx[0], bx[1], bx[2], bx[3], Planner.claimed())) return true;
        for (int[] x : buildings) if (bx[0] <= x[3] && x[0] <= bx[2] && bx[1] <= x[5] && x[2] <= bx[3] && p.anchor().getY() <= x[4] && x[1] <= p.anchor().getY() + 4) return true;
        return false;
    }

    private static boolean waitingForPieces;
    private static int seenLoads;
    private static long lastTry;

    static void linesChanged() {
        linesDirty = true;
    }

    static void addLine(Plan.Line l) {
        lines.add(l);
        save();
        linesDirty = true;
    }

    /** Removes a line (Delete in plan mode). Any chain being drawn ends, so its undo can't reach other lines. */
    static void removeLine(int i) {
        if (i < 0 || i >= lines.size()) return;
        lines.remove(i);
        if (selectedLine == i) selectedLine = -1;
        else if (selectedLine > i) selectedLine--;
        LineView.endChain();
        save();
        linesDirty = true;
    }

    /** Removes the line equal to {@code l} (the chain's undo). */
    static void removeLineValue(Plan.Line l) {
        int i = lines.lastIndexOf(l);
        if (i < 0) return;
        lines.remove(i);
        if (selectedLine == i) selectedLine = -1;
        else if (selectedLine > i) selectedLine--;
        save();
        linesDirty = true;
    }

    /** Branching: a chain started in the middle of a planned segment splits it there, so the start is a joint. */
    static void splitAt(int[] c) {
        int i = Plan.split(lines, c, PlanView::locked);
        if (i < 0) return;
        if (selectedLine > i) selectedLine++;
        save();
        linesDirty = true;
    }

    /**
     * Intersections: the dropped segment {@code l} and every unlocked segment it passes through are split at their
     * crossings ({@link Plan#splitThrough}); the cross joints form there.
     *
     * @return the crossing cells split
     */
    static List<int[]> splitThrough(Plan.Line l) {
        List<int[]> cs = new ArrayList<>();
        for (int[] c : Plan.crossings(lines, l, PlanView::locked)) if (c[2] == 0) cs.add(c);
        if (cs.isEmpty()) return cs;
        Plan.splitThrough(lines, l, PlanView::locked);
        // Draw mode keeps no selection; indices moved.
        selectedLine = -1;
        save();
        linesDirty = true;
        return cs;
    }

    /** Lays the lines out again next frame (a joint type previewed, nothing saved). */
    static void relayout() {
        linesDirty = true;
    }

    /** Sets (AUTO clears) the joint type at a cell and saves. */
    static void setJointType(int x, int z, int type) {
        if (type == Plan.J_AUTO) jointTypes.remove(Plan.cell(x, z));
        else jointTypes.put(Plan.cell(x, z), type);
        save();
        linesDirty = true;
    }

    /** Replaces the whole set of lines (an edit-mode move) and saves. */
    /** Replaces the hut / decoration entries (an undo of a clear); the caller saves. */
    static void setEntries(List<Plan.Entry> es) {
        entries = new ArrayList<>(es);
        selected = -1;
        dirty = true;
    }

    /** @return how many entries the server has built so far (a mark for {@link #builtSince}) */
    static int builtMark() {
        return builtEntries.size();
    }

    /** @return true for an entry the server built after the mark (never planned again by an undo) */
    static boolean builtSince(Plan.Entry e, int mark) {
        return builtEntries.subList(Math.min(mark, builtEntries.size()), builtEntries.size()).contains(e);
    }

    static void setLines(List<Plan.Line> ls) {
        lines = new ArrayList<>(ls);
        save();
        linesDirty = true;
    }

    /** Replaces a planned segment by value (a chain's shared level moving with it). */
    static void replaceLine(Plan.Line old, Plan.Line nu) {
        int i = lines.lastIndexOf(old);
        if (i < 0) return;
        lines.set(i, nu);
        save();
        linesDirty = true;
    }

    /**
     * Takes a built segment's pieces down through the server part: creative deletes the blocks that still match the
     * blueprints at once, survival orders a builder to remove them. @return false without the server part or pieces
     */
    static boolean sendRemoval(Plan.Line l) {
        List<Plan.BuiltPiece> ps = builtPieces.get(Plan.lineKey(l.x0(), l.z0(), l.x1(), l.z1()));
        if (!Planner.serverInstant() || ps == null || ps.isEmpty()) return false;
        List<InstantBuild.Piece> batch = new ArrayList<>();
        // Newest first (the reverse of the order they were built): a newer piece over an older one goes before it.
        for (Plan.BuiltPiece b : ps.reversed()) {
            batch.add(new InstantBuild.Piece(b.path(), new BlockPos(b.ax(), b.ay(), b.az()), PieceCatalog.rotation(b.turns()).ordinal()));
            if (batch.size() == InstantBuild.MAX_BATCH) {
                removalLine.put(nextBatchId, l);
                queue.add(new InstantBuild.PieceBatch(nextBatchId++, l.pack(), Planner.creative() ? InstantBuild.DECO_DELETE : InstantBuild.DECO_REMOVE_ORDER, batch));
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            removalLine.put(nextBatchId, l);
            queue.add(new InstantBuild.PieceBatch(nextBatchId++, l.pack(), Planner.creative() ? InstantBuild.DECO_DELETE : InstantBuild.DECO_REMOVE_ORDER, batch));
        }
        return true;
    }

    /** @return true for a segment that must not change (built, or its batches on their way to the server) */
    static boolean locked(Plan.Line l) {
        return l.built() || sending.containsKey(l);
    }

    static Plan.Line selectedLine() {
        return selectedLine >= 0 && selectedLine < lines.size() ? lines.get(selectedLine) : null;
    }

    static void setLine(Plan.Line l) {
        if (selectedLine() == null || locked(selectedLine())) return;
        lines.set(selectedLine, l);
        save();
        linesDirty = true;
    }

    /** @return the piece of line {@code i} covering (x, z), or null */
    static PieceCatalog.Placed pieceAt(int i, BlockPos pos) {
        if (i < 0 || i >= linePieces.size()) return null;
        for (PieceCatalog.Placed p : linePieces.get(i)) {
            int[] b = p.box();
            if (pos.getX() >= b[0] && pos.getX() <= b[2] && pos.getZ() >= b[1] && pos.getZ() <= b[3]) return p;
        }
        return null;
    }

    /** @return the index of a line with a piece covering (x, z), or -1 */
    static int lineAt(BlockPos pos) {
        for (int i = linePieces.size() - 1; i >= 0; i--)
            for (PieceCatalog.Placed p : linePieces.get(i)) {
                int[] b = p.box();
                if (pos.getX() >= b[0] && pos.getX() <= b[2] && pos.getZ() >= b[1] && pos.getZ() <= b[3]) return i;
            }
        return -1;
    }

    /**
     * One outline per piece, the height of its blueprint box (inset, so neighbours stay apart): white when its segment
     * is selected, red when the piece is outside the colony or runs into a building, else green.
     */
    static void outline(PoseStack pose, VertexConsumer out, List<PieceCatalog.Placed> ps, List<Boolean> bad, boolean selected) {
        for (int k = 0; k < ps.size(); k++) {
            PieceCatalog.Placed p = ps.get(k);
            int[] bx = p.box();
            boolean b = k < bad.size() && bad.get(k);
            float r = selected ? 1f : b ? 1f : 0.3f, g = selected ? 1f : b ? 0.25f : 1f, bl = selected ? 1f : b ? 0.25f : 0.3f;
            LevelRenderer.renderLineBox(pose, out, bx[0] + 0.12, p.y0() + 0.05, bx[1] + 0.12, bx[2] + 0.88, p.y0() + Math.max(1, p.h()) - 0.05, bx[3] + 0.88, r, g, bl, 1f);
        }
    }

    /** Outlines in one colour. */
    static void outline(PoseStack pose, VertexConsumer out, List<PieceCatalog.Placed> ps, List<Boolean> bad, boolean selected, float r, float g, float b, float a) {
        for (PieceCatalog.Placed p : ps) {
            int[] bx = p.box();
            LevelRenderer.renderLineBox(pose, out, bx[0] + 0.12, p.y0() + 0.05, bx[1] + 0.12, bx[2] + 0.88, p.y0() + Math.max(1, p.h()) - 0.05, bx[3] + 0.88, r, g, b, a);
        }
    }

    /**
     * Air under pieces raised above the ground (flat or smoothed heights, slopes): an amber box from the lowest ground
     * under the piece up to where it stands. A builder doesn't fill it; the piece's blueprint stays as it is.
     */
    static void floating(PoseStack pose, VertexConsumer out, List<PieceCatalog.Placed> ps) {
        for (PieceCatalog.Placed p : ps) {
            if (p.level() - p.low() < 2) continue;
            int[] bx = p.box();
            LevelRenderer.renderLineBox(pose, out, bx[0] + 0.2, p.low(), bx[1] + 0.2, bx[2] + 0.8, p.level(), bx[3] + 0.8, 1f, 0.6f, 0.1f, 0.8f);
        }
    }

    /** An amber box over cells a segment leaves uncovered (no piece fits there). */
    static void gap(PoseStack pose, VertexConsumer out, int[] box, int y) {
        LevelRenderer.renderLineBox(pose, out, box[0] + 0.12, y, box[1] + 0.12, box[2] + 0.88, y + 1.5, box[3] + 0.88, 1f, 0.7f, 0.1f, 1f);
    }

    // Road / wall batches: which segment each belongs to, and how many of a segment's batches still wait for a yes.
    private static final Map<Integer, Plan.Line> batchLine = new HashMap<>();
    private static final Map<Plan.Line, Integer> sending = new HashMap<>();
    // The pieces each batch sent, and the accepted ones per segment: what a built record freezes (never a later layout).
    private static final Map<Integer, List<PieceCatalog.Placed>> batchSent = new HashMap<>();
    private static final Map<Plan.Line, List<PieceCatalog.Placed>> acceptedPieces = new HashMap<>();
    private static int nextBatchId = 1;
    // Removal batches of built segments, and plan entries the server built (undo never plans them again).
    private static final Map<Integer, Plan.Line> removalLine = new HashMap<>();
    private static final List<Plan.Entry> builtEntries = new ArrayList<>();
    private static final java.util.Set<Integer> decoIds = new java.util.HashSet<>();

    /** @return a request id for a selected decoration's action (its answer is told only when refused) */
    static int decoRequest() {
        decoIds.add(nextBatchId);
        return nextBatchId++;
    }

    private static void queueBatch(Plan.Line l, List<InstantBuild.Piece> pieces, List<PieceCatalog.Placed> sent) {
        int id = nextBatchId++;
        batchLine.put(id, l);
        batchSent.put(id, new ArrayList<>(sent));
        queue.add(new InstantBuild.PieceBatch(id, l.pack(), Planner.creative() ? InstantBuild.DECO : InstantBuild.DECO_ORDER, pieces));
    }

    /**
     * The server's answer to a batch. Accepted: once all of a segment's batches are, the segment leaves the plan.
     * Refused: the segment stays, its remaining batches are dropped, and the player is told why.
     */
    static void onBatchResult(InstantBuild.BatchResult r) {
        // A selected decoration's delete / move / remove: only a refusal is told (success has the server's own message).
        if (decoIds.remove(r.id())) {
            Minecraft mc = Minecraft.getInstance();
            if (!r.accepted() && mc.player != null) mc.player.displayClientMessage(Component.translatable(r.reason().equals("busy") ? "mccitybuilder.deco.busy" : "mccitybuilder.deco.refused"), false);
            return;
        }
        // A plan entry's answer: built (leaves the plan), busy (sent again shortly) or refused (stays).
        Plan.Entry done = entryQueue.answer(r.id(), r.accepted(), r.reason(), System.currentTimeMillis());
        if (done != null) {
            builtEntries.add(done);
            int i = entries.indexOf(done);
            if (i >= 0) {
                entries.remove(i);
                if (selected == i) selected = -1;
                else if (selected > i) selected--;
                dirty = true;
                save();
            }
            return;
        }
        Plan.Line l = batchLine.remove(r.id());
        List<PieceCatalog.Placed> sentNow = batchSent.remove(r.id());
        if (waitingFor instanceof InstantBuild.PieceBatch b && b.id() == r.id()) {
            waitingFor = null;
            waitUntil = System.currentTimeMillis() + 1_100;
            waitingFor = new Object();
        }
        // A built segment's removal: taken down once the server accepted any of its batches.
        Plan.Line rm = removalLine.remove(r.id());
        if (rm != null && r.accepted()) LineView.tookDown(rm);
        if (l == null) return;
        if (r.accepted()) {
            if (sentNow != null) acceptedPieces.computeIfAbsent(l, k -> new ArrayList<>()).addAll(sentNow);
            int left = sending.getOrDefault(l, 1) - 1;
            if (left > 0) sending.put(l, left);
            else {
                sending.remove(l);
                // Kept as a built record: new segments snap and join onto it; it is never built again.
                int at = lines.indexOf(l);
                List<PieceCatalog.Placed> sentAll = acceptedPieces.remove(l);
                if (at >= 0) {
                    // Its pieces as sent and accepted: fixed from now on (the world has them), whatever a later layout says.
                    if (sentAll != null && !sentAll.isEmpty()) builtPieces.put(Plan.lineKey(l.x0(), l.z0(), l.x1(), l.z1()), PieceCatalog.freeze(l, sentAll));
                    lines.set(at, l.asBuilt());
                    LineView.builtAgain(l.asBuilt());
                }
                if (selectedLine >= lines.size()) selectedLine = -1;
                save();
                linesDirty = true;
            }
            return;
        }
        sending.remove(l);
        acceptedPieces.remove(l);
        queue.removeIf(o -> o instanceof InstantBuild.PieceBatch b && batchLine.get(b.id()) == l && batchLine.remove(b.id()) != null && batchSent.remove(b.id()) != null);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable("mccitybuilder.pieces.refused." + r.reason()), false);
    }

    /** @return creative requests still waiting to be sent */
    static int queued() {
        return queue.size() + (waitingFor != null ? 1 : 0);
    }
}
