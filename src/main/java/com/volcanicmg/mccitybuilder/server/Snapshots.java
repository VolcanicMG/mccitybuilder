package com.volcanicmg.mccitybuilder.server;

import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.PasteRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * What stood in a creative paste's footprint before it: block states and block entity data, one compressed NBT file
 * per placement under {@code <world>/data/mccitybuilder/snapshots/}, so a delete (or a move's old site) puts the world
 * back exactly. Server side only: files are named by a hash of the placement key, never by anything a client sends.
 * Cells the paste kept (storage, huts, entity supports) hold nothing: a restore leaves them alone, and item contents
 * are never stored, so nothing can be duplicated. Each snapshot carries when it was taken, so overlapping placements
 * hand their "before" over to the newer one when the older goes ({@link #handOver}).
 */
final class Snapshots {
    /** Footprints larger than this (cells) aren't snapshotted; the snapshot folder stops growing past MAX_DISK bytes. */
    static final int MAX_CELLS = 262_144;
    static final long MAX_DISK = 512L << 20;
    // Bytes in the snapshot folder per world (listed once, then kept as files come and go), and the last time stamp.
    private static final Map<Path, Long> total = new HashMap<>();
    private static long lastTime;

    /** A snapshot: its box origin and size, a palette, a palette index per cell (-1: untouched), block entity data by cell, when taken. */
    record Snap(BlockPos origin, int sx, int sy, int sz, BlockState[] palette, int[] cells, Map<Integer, CompoundTag> bes, long time) {
        int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }

        /** @return the cell index of world position w, or -1 outside the box */
        int at(BlockPos w) {
            int x = w.getX() - origin.getX(), y = w.getY() - origin.getY(), z = w.getZ() - origin.getZ();
            return x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz ? -1 : index(x, y, z);
        }
    }

    private Snapshots() {
    }

    /** @return the key of a decoration / piece placement: its dimension and record key */
    static String decoKey(ServerLevel level, int colony, String pack, String path, BlockPos pos, int rot) {
        return level.dimension().location() + "|" + PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot);
    }

    /** @return the snapshot key for a record key in this dimension */
    static String recordSnapKey(ServerLevel level, String recordKey) {
        return level.dimension().location() + "|" + recordKey;
    }

    /** @return the key of a hut placement: its dimension, colony and building position */
    static String hutKey(ServerLevel level, int colony, BlockPos pos) {
        return level.dimension().location() + "|hut|" + colony + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Path dir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(CityBuilder.MODID).resolve("snapshots");
    }

    private static Path file(ServerLevel level, String key) {
        return dir(level).resolve(PasteRules.snapshotName(key));
    }

    /**
     * Records the box origin..far as it stands now, except {@code untouched} cells (the paste keeps them). Skipped,
     * with a log line, when the box is too large or the folder too full.
     */
    static void take(ServerLevel level, String key, BlockPos origin, BlockPos far, Set<BlockPos> untouched) {
        int sx = far.getX() - origin.getX() + 1, sy = far.getY() - origin.getY() + 1, sz = far.getZ() - origin.getZ() + 1;
        if ((long) sx * sy * sz > MAX_CELLS) {
            CityBuilder.LOGGER.info("CityBuilder: no snapshot for {} ({} cells, over {}); a delete will match and refill", key, (long) sx * sy * sz, MAX_CELLS);
            return;
        }
        if (size(level) > MAX_DISK) {
            CityBuilder.LOGGER.warn("CityBuilder: snapshot folder over {} bytes; no snapshot for {}", MAX_DISK, key);
            return;
        }
        Map<BlockState, Integer> ids = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        Map<Integer, CompoundTag> bes = new HashMap<>();
        int[] cells = new int[sx * sy * sz];
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    int i = (y * sz + z) * sx + x;
                    BlockPos w = origin.offset(x, y, z);
                    if (untouched.contains(w)) {
                        cells[i] = -1;
                        continue;
                    }
                    BlockState s = level.getBlockState(w);
                    cells[i] = ids.computeIfAbsent(s, k -> {
                        palette.add(k);
                        return palette.size() - 1;
                    });
                    BlockEntity be = level.getBlockEntity(w);
                    if (be != null && !InstantBuild.isProtected(level, w, s)) bes.put(i, be.saveWithFullMetadata(level.registryAccess()));
                }
        lastTime = Math.max(lastTime + 1, System.currentTimeMillis());
        save(level, key, new Snap(origin, sx, sy, sz, palette.toArray(new BlockState[0]), cells, bes, lastTime));
    }

    private static void save(ServerLevel level, String key, Snap s) {
        CompoundTag root = new CompoundTag();
        root.putIntArray("origin", new int[]{s.origin().getX(), s.origin().getY(), s.origin().getZ()});
        root.putIntArray("size", new int[]{s.sx(), s.sy(), s.sz()});
        ListTag pl = new ListTag(), bl = new ListTag();
        for (BlockState b : s.palette()) pl.add(NbtUtils.writeBlockState(b));
        root.put("palette", pl);
        root.putIntArray("cells", s.cells());
        s.bes().forEach((i, nbt) -> {
            CompoundTag t = new CompoundTag();
            t.putInt("i", i);
            t.put("nbt", nbt);
            bl.add(t);
        });
        root.put("bes", bl);
        root.putLong("time", s.time());
        Path f = file(level, key);
        size(level);
        try {
            Files.createDirectories(dir(level));
            long before = Files.isRegularFile(f) ? Files.size(f) : 0;
            NbtIo.writeCompressed(root, f);
            total.merge(dir(level), Files.size(f) - before, Long::sum);
        } catch (IOException e) {
            CityBuilder.LOGGER.warn("CityBuilder: couldn't write the snapshot for {}", key, e);
        }
    }

    /** @return true when a placement has a snapshot */
    static boolean exists(ServerLevel level, String key) {
        return Files.isRegularFile(file(level, key));
    }

    /** @return the snapshot of a placement, or null when there is none (or it can't be read) */
    static Snap load(ServerLevel level, String key) {
        Path f = file(level, key);
        if (!Files.isRegularFile(f)) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.create(256L << 20));
            int[] o = root.getIntArray("origin"), s = root.getIntArray("size"), cells = root.getIntArray("cells");
            if (o.length != 3 || s.length != 3 || cells.length != s[0] * s[1] * s[2]) return null;
            ListTag pl = root.getList("palette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[pl.size()];
            for (int i = 0; i < palette.length; i++) palette[i] = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), pl.getCompound(i));
            for (int c : cells) if (c >= palette.length) return null;
            Map<Integer, CompoundTag> bes = new HashMap<>();
            for (Tag t : root.getList("bes", Tag.TAG_COMPOUND)) bes.put(((CompoundTag) t).getInt("i"), ((CompoundTag) t).getCompound("nbt"));
            return new Snap(new BlockPos(o[0], o[1], o[2]), s[0], s[1], s[2], palette, cells, bes, root.getLong("time"));
        } catch (IOException | RuntimeException e) {
            CityBuilder.LOGGER.warn("CityBuilder: couldn't read the snapshot for {}", key, e);
            return null;
        }
    }

    /**
     * A placement {@code from} goes while newer ones stand over parts of it: in each cell, what stood before it is what
     * the oldest newer placement that changed that cell must put back when it goes ({@link PasteRules#handOverTarget}),
     * so that cell of its snapshot takes {@code from}'s value; the others above it already hold their own "before".
     *
     * @param newer the newer placements' snapshot keys (any order)
     */
    static void handOver(ServerLevel level, Snap from, List<String> newer) {
        List<String> keys = new ArrayList<>();
        List<Snap> snaps = new ArrayList<>();
        for (String k : newer) {
            Snap s = load(level, k);
            if (s == null) continue;
            int at = 0;
            while (at < snaps.size() && snaps.get(at).time() <= s.time()) at++;
            snaps.add(at, s);
            keys.add(at, k);
        }
        int n = snaps.size();
        List<List<BlockState>> palettes = new ArrayList<>();
        List<int[]> cells = new ArrayList<>();
        List<Map<Integer, CompoundTag>> bes = new ArrayList<>();
        boolean[] changed = new boolean[n];
        for (Snap s : snaps) {
            palettes.add(new ArrayList<>(List.of(s.palette())));
            cells.add(s.cells().clone());
            bes.add(new HashMap<>(s.bes()));
        }
        for (int y = 0; y < from.sy(); y++)
            for (int z = 0; z < from.sz(); z++)
                for (int x = 0; x < from.sx(); x++) {
                    int fi = from.index(x, y, z);
                    if (from.cells()[fi] < 0) continue;
                    BlockPos w = from.origin().offset(x, y, z);
                    boolean[] took = new boolean[n];
                    int[] at = new int[n];
                    for (int q = 0; q < n; q++) {
                        at[q] = snaps.get(q).at(w);
                        took[q] = at[q] >= 0 && cells.get(q)[at[q]] >= 0;
                    }
                    int q = PasteRules.handOverTarget(took);
                    if (q < 0) continue;
                    BlockState st = from.palette()[from.cells()[fi]];
                    int pi = palettes.get(q).indexOf(st);
                    if (pi < 0) {
                        palettes.get(q).add(st);
                        pi = palettes.get(q).size() - 1;
                    }
                    cells.get(q)[at[q]] = pi;
                    CompoundTag nbt = from.bes().get(fi);
                    if (nbt != null) bes.get(q).put(at[q], nbt);
                    else bes.get(q).remove(at[q]);
                    changed[q] = true;
                }
        for (int q = 0; q < n; q++)
            if (changed[q]) {
                Snap t = snaps.get(q);
                save(level, keys.get(q), new Snap(t.origin(), t.sx(), t.sy(), t.sz(), palettes.get(q).toArray(new BlockState[0]), cells.get(q), bes.get(q), t.time()));
            }
    }

    /** Forgets a placement's snapshot (restored, or no longer valid). */
    static void delete(ServerLevel level, String key) {
        Path f = file(level, key);
        size(level);
        try {
            long was = Files.isRegularFile(f) ? Files.size(f) : 0;
            if (Files.deleteIfExists(f)) total.merge(dir(level), -was, Long::sum);
        } catch (IOException e) {
            CityBuilder.LOGGER.warn("CityBuilder: couldn't delete the snapshot for {}", key, e);
        }
    }

    /** @return the snapshot folder's size in bytes: listed the first time, then kept up to date as files come and go */
    private static long size(ServerLevel level) {
        Path d = dir(level);
        return total.computeIfAbsent(d, k -> {
            if (!Files.isDirectory(k)) return 0L;
            try (var files = Files.list(k)) {
                return files.mapToLong(f -> f.toFile().length()).sum();
            } catch (IOException e) {
                return 0L;
            }
        });
    }
}
