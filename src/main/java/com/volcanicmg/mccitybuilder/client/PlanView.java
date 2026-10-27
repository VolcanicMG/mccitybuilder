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
    private static final Deque<Plan.Entry> queue = new ArrayDeque<>();
    private static Plan.Entry waitingFor;
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
            entries = new ArrayList<>(from != null ? Plan.read(Files.readString(from)) : List.of());
        } catch (Exception e) {
            CityBuilder.LOGGER.warn("CityBuilder: can't read plan {}", file, e);
            entries = new ArrayList<>();
        }
        selected = -1;
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
            Files.writeString(file, Plan.write(entries));
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
        entries.clear();
        selected = -1;
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
        Minecraft mc = Minecraft.getInstance();
        List<Integer> picks = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) if (!selectedOnly || i == selected) picks.add(i);
        int sent = 0, outside = 0, noItem = 0, deco = 0;
        Map<Block, Integer> left = new HashMap<>();
        List<Plan.Entry> keep = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Plan.Entry e = entries.get(i);
            if (!picks.contains(i)) {
                keep.add(e);
                continue;
            }
            if (i < conflicts.size() && conflicts.get(i) == Plan.Conflict.OUTSIDE) {
                outside++;
                keep.add(e);
                continue;
            }
            if (Planner.creative()) {
                queue.add(e);
                sent++;
                continue;
            }
            if (!e.isHut()) {
                // MineColonies opens its decoration window per placement: decorations go one by one.
                deco++;
                keep.add(e);
                continue;
            }
            Block hut = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.hut()));
            int have = left.computeIfAbsent(hut, h -> mc.player.getInventory().countItem(h.asItem()));
            boolean locked = hut instanceof AbstractBlockHut<?> h && !h.areRequirementsMet(mc.level, mc.player.blockPosition(), mc.player);
            if (have <= 0 || locked) {
                noItem++;
                keep.add(e);
                continue;
            }
            left.put(hut, have - 1);
            BlockPos at = new BlockPos(e.x(), e.y(), e.z());
            // Survival places level 1 (the builder builds up); the planned level stays a plan.
            String path = HutCatalog.levelPath(e.path(), 1);
            new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Survival, "minecolonies", e.pack(), path == null ? e.path() : path, at,
                    RotationMirror.values()[e.rot()]).sendToServer();
            Planner.expectBuilding(at);
            sent++;
        }
        entries = keep;
        selected = -1;
        save();
        return Component.translatable("mccitybuilder.plan.committed", sent, outside, noItem, deco);
    }

    /** Creative commit queue: one request at a time, the next once the building appears (huts) or after a pause. */
    static void tick() {
        IColonyView colony = Planner.colony();
        long now = System.currentTimeMillis();
        if (waitingFor != null) {
            boolean there = waitingFor.isHut() && colony != null
                    && colony.getClientBuildingManager().getBuilding(new BlockPos(waitingFor.x(), waitingFor.y(), waitingFor.z())) != null;
            if (!there && now < waitUntil) return;
            waitingFor = null;
        }
        Plan.Entry e = queue.poll();
        if (e == null) return;
        BlockPos at = new BlockPos(e.x(), e.y(), e.z());
        RotationMirror rot = RotationMirror.values()[e.rot()];
        if (Planner.serverInstant()) {
            PacketDistributor.sendToServer(new InstantBuild.Request(e.isHut() ? InstantBuild.PLACE : InstantBuild.DECO, at, e.isHut() ? e.level() : 0,
                    e.pack(), e.path(), rot));
            waitUntil = now + (e.isHut() ? 15_000 : 3_000);
        } else {
            new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Pretty, "", e.pack(), e.path(), at, rot).sendToServer();
            waitUntil = now + 1_500;
        }
        waitingFor = e;
    }

    /** @return creative requests still waiting to be sent */
    static int queued() {
        return queue.size() + (waitingFor != null ? 1 : 0);
    }
}
