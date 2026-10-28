package com.volcanicmg.mccitybuilder.server;

import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.PasteRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * Cells the paste kept (storage, huts, entity supports) hold nothing: a restore leaves them alone, and container
 * contents are never stored, so nothing can be duplicated.
 */
final class Snapshots {
    /** Footprints larger than this (cells) aren't snapshotted; the snapshot folder stops growing past MAX_DISK bytes. */
    static final int MAX_CELLS = 262_144;
    static final long MAX_DISK = 512L << 20;

    /** A loaded snapshot: its box origin and size, a palette, a palette index per cell (-1: untouched) and block entity data by cell. */
    record Snap(BlockPos origin, int sx, int sy, int sz, BlockState[] palette, int[] cells, Map<Integer, CompoundTag> bes) {
        int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }
    }

    private Snapshots() {
    }

    /** @return the key of a decoration / piece placement: its dimension and record key */
    static String decoKey(ServerLevel level, int colony, String pack, String path, BlockPos pos, int rot) {
        return level.dimension().location() + "|" + PasteRules.recordKey(colony, pack, path, pos.getX(), pos.getY(), pos.getZ(), rot);
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
        // ponytail: the folder is summed on every paste; keep a running total if pastes get frequent and the folder large.
        if (size(level) > MAX_DISK) {
            CityBuilder.LOGGER.warn("CityBuilder: snapshot folder over {} bytes; no snapshot for {}", MAX_DISK, key);
            return;
        }
        Map<BlockState, Integer> ids = new HashMap<>();
        ListTag palette = new ListTag(), bes = new ListTag();
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
                        palette.add(NbtUtils.writeBlockState(k));
                        return palette.size() - 1;
                    });
                    BlockEntity be = level.getBlockEntity(w);
                    if (be != null && !InstantBuild.isProtected(level, w, s)) {
                        CompoundTag t = new CompoundTag();
                        t.putInt("i", i);
                        t.put("nbt", be.saveWithFullMetadata(level.registryAccess()));
                        bes.add(t);
                    }
                }
        CompoundTag root = new CompoundTag();
        root.putIntArray("origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
        root.putIntArray("size", new int[]{sx, sy, sz});
        root.put("palette", palette);
        root.putIntArray("cells", cells);
        root.put("bes", bes);
        try {
            Files.createDirectories(dir(level));
            NbtIo.writeCompressed(root, file(level, key));
        } catch (IOException e) {
            CityBuilder.LOGGER.warn("CityBuilder: couldn't write the snapshot for {}", key, e);
        }
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
            return new Snap(new BlockPos(o[0], o[1], o[2]), s[0], s[1], s[2], palette, cells, bes);
        } catch (IOException | RuntimeException e) {
            CityBuilder.LOGGER.warn("CityBuilder: couldn't read the snapshot for {}", key, e);
            return null;
        }
    }

    /** Forgets a placement's snapshot (restored, or no longer valid). */
    static void delete(ServerLevel level, String key) {
        try {
            Files.deleteIfExists(file(level, key));
        } catch (IOException e) {
            CityBuilder.LOGGER.warn("CityBuilder: couldn't delete the snapshot for {}", key, e);
        }
    }

    private static long size(ServerLevel level) {
        Path d = dir(level);
        if (!Files.isDirectory(d)) return 0;
        try (var files = Files.list(d)) {
            return files.mapToLong(f -> f.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
