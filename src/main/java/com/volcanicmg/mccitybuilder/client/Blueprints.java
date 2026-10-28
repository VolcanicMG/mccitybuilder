package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.minecolonies.api.util.LoadOnlyStructureHandler;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.Costs;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Hut blueprints from Structurize's style packs: for a pack and a hut block, the level-1 blueprint whose anchor is
 * that hut (what MineColonies' build tool offers first). Looked up asynchronously through
 * {@link StructurePacks#findBlueprintFuture} and cached per pack and hut; null results are cached too.
 */
final class Blueprints {
    /** A resolved hut blueprint and its path inside the pack, as BuildToolPlacementMessage wants it. */
    record Hut(Blueprint blueprint, String pack, String path) {
    }

    private static final Map<String, CompletableFuture<Hut>> cache = new HashMap<>();

    private Blueprints() {
    }

    /** @return enabled style packs, by name */
    static List<String> packs() {
        List<String> out = new ArrayList<>();
        for (StructurePackMeta m : StructurePacks.getPackMetas())
            if (!m.isDisabled()) out.add(m.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** @return the hut's level-1 blueprint in {@code pack} when loaded, else null (and starts loading it) */
    static Hut get(String pack, Block hut) {
        String key = pack + "|" + hut.builtInRegistryHolder().key().location();
        CompletableFuture<Hut> f = cache.computeIfAbsent(key, k -> load(pack, hut));
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    /**
     * @return the hut's blueprint at {@code level} (its level-1 path with the digit changed) when loaded, else null (and
     * starts loading it)
     */
    static Hut get(String pack, Block hut, int level) {
        Hut one = get(pack, hut);
        if (level <= 1 || one == null) return level <= 1 ? one : null;
        String path = HutCatalog.levelPath(one.path(), level);
        if (path == null || Minecraft.getInstance().level == null) return null;
        CompletableFuture<Hut> f = cache.computeIfAbsent(pack + "|" + path,
                k -> StructurePacks.getBlueprintFuture(pack, path, true, Minecraft.getInstance().level.registryAccess())
                        .thenApply(bp -> bp == null ? null : new Hut(bp, pack, path))
                        .exceptionally(t -> null));
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    /** @return true when the lookup finished and the pack has no blueprint for this hut */
    static boolean missing(String pack, Block hut) {
        CompletableFuture<Hut> f = cache.get(pack + "|" + hut.builtInRegistryHolder().key().location());
        return f != null && f.isDone() && !f.isCompletedExceptionally() && f.getNow(null) == null;
    }

    private static final Map<String, Integer> maxLevels = new HashMap<>();

    /** @return the highest level with a file next to the level-1 blueprint ({@code baker1} .. {@code bakerN}) */
    static int maxLevel(Hut one) {
        return maxLevels.computeIfAbsent(one.pack() + "|" + one.path(), k -> {
            String name = one.blueprint().getFileName();
            if (one.blueprint().getFilePath() == null || name.isEmpty()) return 1;
            String base = name.substring(0, name.length() - 1);
            int n = 1;
            while (n < 9 && Files.exists(one.blueprint().getFilePath().resolve(base + (n + 1) + ".blueprint"))) n++;
            return n;
        });
    }

    // Building boxes per pack|path|rotation: our own blueprint copy, rotated on the client thread.
    private static final Map<String, CompletableFuture<Blueprint>> boxLoads = new HashMap<>();
    private static final Map<String, int[]> boxes = new HashMap<>();

    /**
     * A colony building's box from its current blueprint, rotated like the building (what MineColonies' build goggles
     * outline): {minX, minY, minZ, maxX, maxY, maxZ} offsets from the hut block, inclusive.
     *
     * @return the offsets, or null while loading or when the blueprint is unknown
     */
    static int[] bounds(String pack, String path, RotationMirror rot) {
        Minecraft mc = Minecraft.getInstance();
        if (pack == null || path == null || pack.isEmpty() || path.isEmpty() || mc.level == null) return null;
        String key = pack + "|" + path + "|" + rot;
        int[] done = boxes.get(key);
        if (done != null) return done.length == 0 ? null : done;
        CompletableFuture<Blueprint> f = boxLoads.computeIfAbsent(key, k -> StructurePacks.getStructurePack(pack) == null
                ? CompletableFuture.completedFuture(null) : StructurePacks.getBlueprintFuture(pack, path, true, mc.level.registryAccess()));
        if (!f.isDone()) return null;
        boxLoads.remove(key);
        int[] box = new int[0];
        try {
            Blueprint bp = f.getNow(null);
            if (bp != null) {
                bp.setRotationMirror(rot, mc.level);
                BlockPos o = bp.getPrimaryBlockOffset();
                box = new int[]{-o.getX(), -o.getY(), -o.getZ(), bp.getSizeX() - 1 - o.getX(), bp.getSizeY() - 1 - o.getY(), bp.getSizeZ() - 1 - o.getZ()};
            }
        } catch (RuntimeException e) {
            // A broken blueprint: fall back to the hut block rather than leave the planner.
            CityBuilder.LOGGER.warn("CityBuilder: no bounds for {} in {}", path, pack, e);
        }
        boxes.put(key, box);
        return box.length == 0 ? null : box;
    }

    // Decorations per pack: the folders holding blueprints, then each folder's non-hut blueprints (loaded on demand).
    private static final Map<String, CompletableFuture<List<String>>> decoFolders = new HashMap<>();
    private static final Map<String, CompletableFuture<List<Hut>>> decoLists = new HashMap<>();

    /** @return the pack's folders (sub-paths) that hold blueprints, sorted; empty while the walk runs */
    static List<String> decoFolders(String pack) {
        CompletableFuture<List<String>> f = decoFolders.computeIfAbsent(pack, k -> CompletableFuture.supplyAsync(() -> {
            StructurePackMeta meta = StructurePacks.getStructurePack(pack);
            List<String> out = new ArrayList<>();
            if (meta == null) return out;
            try (var walk = Files.walk(meta.getPath())) {
                walk.filter(f2 -> f2.toString().endsWith(".blueprint") && !meta.getSubPath(f2).startsWith("scans"))
                        .map(f2 -> meta.getSubPath(f2.getParent()).replace('\\', '/'))
                        .distinct().forEach(out::add);
            } catch (Exception e) {
                CityBuilder.LOGGER.warn("CityBuilder: can't list {}", pack, e);
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        }));
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(List.of()) : List.of();
    }

    /** @return the folder's decorations (blueprints whose anchor is not a hut), or null while they load */
    static List<Hut> decorations(String pack, String folder) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        CompletableFuture<List<Hut>> f = decoLists.computeIfAbsent(pack + "|" + folder, k -> {
            StructurePackMeta meta = StructurePacks.getStructurePack(pack);
            if (meta == null) return CompletableFuture.completedFuture(List.of());
            return StructurePacks.getBlueprintsFuture(pack, folder, mc.level.registryAccess()).thenApply(list -> {
                List<Hut> out = new ArrayList<>();
                for (Blueprint bp : list == null ? List.<Blueprint>of() : list)
                    if (!(bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() instanceof com.minecolonies.api.blocks.AbstractBlockHut<?>))
                        out.add(new Hut(bp, pack, meta.getSubPath(bp.getFilePath().resolve(bp.getFileName() + ".blueprint")).replace('\\', '/')));
                out.sort(java.util.Comparator.comparing(h -> h.blueprint().getFileName()));
                return out;
            }).exceptionally(t -> List.of());
        });
        return f.isDone() ? f.getNow(List.of()) : null;
    }

    private static final Map<String, CompletableFuture<Hut>> decoLoads = new HashMap<>();

    /** @return one decoration's blueprint by pack and path when loaded, else null (and starts loading it) */
    static Hut deco(String pack, String path) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        CompletableFuture<Hut> f = decoLoads.computeIfAbsent(pack + "|" + path, k -> StructurePacks.getStructurePack(pack) == null ? CompletableFuture.completedFuture(null)
                : StructurePacks.getBlueprintFuture(pack, path, true, mc.level.registryAccess()).thenApply(bp -> bp == null ? null : new Hut(bp, pack, path))
                .exceptionally(t -> null));
        return f.isDone() ? f.getNow(null) : null;
    }

    /** @return true while a lookup is still running */
    static boolean loading(String pack, Block hut) {
        CompletableFuture<Hut> f = cache.get(pack + "|" + hut.builtInRegistryHolder().key().location());
        return f != null && !f.isDone();
    }

    private static CompletableFuture<Hut> load(String pack, Block hut) {
        StructurePackMeta meta = StructurePacks.getStructurePack(pack);
        if (meta == null || Minecraft.getInstance().level == null) return CompletableFuture.completedFuture(null);
        return StructurePacks.findBlueprintFuture(pack,
                        bp -> bp.getFileName().endsWith("1") && bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hut,
                        Minecraft.getInstance().level.registryAccess())
                .thenApply(bp -> bp == null ? null
                        : new Hut(bp, pack, meta.getSubPath(bp.getFilePath().resolve(bp.getFileName() + ".blueprint"))))
                .exceptionally(t -> {
                    CityBuilder.LOGGER.warn("CityBuilder: no blueprint for {} in {}", hut, pack, t);
                    return null;
                });
    }

    // Material lists per pack|path: a fresh blueprint copy loads first (the preview's copy gets rotated).
    private static final Map<String, CompletableFuture<Blueprint>> costLoads = new HashMap<>();
    private static final Map<String, Map<String, Integer>> costs = new HashMap<>();

    /**
     * The hut's level-1 materials, computed like MineColonies' build window does (Structurize's StructurePlacer with
     * GET_RES_REQUIREMENTS over a load-only structure handler), against empty air high above the player so existing
     * blocks don't count. Runs on the client thread once per blueprint.
     *
     * @return item id → count, or null while loading
     */
    static Map<String, Integer> cost(Hut hut) {
        String key = hut.pack() + "|" + hut.path();
        Map<String, Integer> done = costs.get(key);
        if (done != null) return done;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        CompletableFuture<Blueprint> f = costLoads.computeIfAbsent(key, k -> StructurePacks.getBlueprintFuture(hut.pack(), hut.path(), mc.level.registryAccess()));
        if (!f.isDone()) return null;
        Blueprint bp = f.isCompletedExceptionally() ? null : f.getNow(null);
        Map<String, Integer> need = Costs.newMap();
        if (bp != null) {
            BlockPos pos = new BlockPos(mc.player.getBlockX(), mc.level.getMaxBuildHeight() - bp.getSizeY() - 2, mc.player.getBlockZ());
            StructurePlacer placer = new StructurePlacer(new LoadOnlyStructureHandler(mc.level, pos, bp, RotationMirror.NONE));
            BlockPos progress = AbstractBlueprintIterator.NULL_POS;
            for (int guard = 0; guard < 100_000; guard++) {
                StructurePhasePlacementResult r = placer.executeStructureStep(mc.level, null, progress, StructurePlacer.Operation.GET_RES_REQUIREMENTS,
                        () -> placer.getIterator().increment((info, p, handler) -> false), true);
                if (r == null) break;
                progress = r.getIteratorPos();
                for (ItemStack st : r.getBlockResult().getRequiredItems())
                    if (!st.isEmpty()) Costs.add(need, BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount());
                if (r.getBlockResult().getResult() == BlockPlacementResult.Result.FINISHED) break;
            }
        }
        costs.put(key, need);
        return need;
    }

    static void clear() {
        cache.clear();
        decoFolders.clear();
        decoLists.clear();
        maxLevels.clear();
        boxLoads.clear();
        boxes.clear();
        costLoads.clear();
        costs.clear();
    }
}
