package com.volcanicmg.mccitybuilder.server;

import com.ldtteam.structurize.api.BlockPosUtil;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Fill gaps' cropped straights: a straight's blueprint cut to a window of cells along one axis (full height and
 * width), the same on client (ghosts, layout) and server (creative paste). The server also writes each crop as a real
 * blueprint into its own structure pack, {@link #PACK} (under the game folder's {@code blueprints/}, where Structurize
 * finds it after a restart), because MineColonies builders load a work order's blueprint from a pack by path.
 */
public final class Crops {
    /** The structure pack cropped straights are written to. */
    public static final String PACK = "City Builder Crops";
    private static final Path DIR = Path.of("blueprints", "mccitybuilder_crops");

    private Crops() {
    }

    /**
     * @param src an unturned blueprint (a fresh copy: it is only read)
     * @param w   {axis (0 = X, 1 = Z), from, len}: a window shorter than the blueprint along that axis
     * @return a copy holding only the window's cells; a wall-hung decoration on its edge whose support was cut off
     * is left out (air); the primary offset stays where it was, moved into the window when it falls outside; null
     * when the window doesn't fit
     */
    public static Blueprint crop(Blueprint src, int[] w) {
        int sx = src.getSizeX(), sy = src.getSizeY(), sz = src.getSizeZ(), size = w[0] == 0 ? sx : sz;
        if (w[1] < 0 || w[2] < 1 || w[2] >= size || w[1] > size - w[2]) return null;
        int nsx = w[0] == 0 ? w[2] : sx, nsz = w[0] == 1 ? w[2] : sz, dx = w[0] == 0 ? w[1] : 0, dz = w[0] == 1 ? w[1] : 0;
        List<BlockState> palette = new ArrayList<>(Arrays.asList(src.getPalette()));
        short air = -1;
        for (int i = 0; i < palette.size() && air < 0; i++) if (palette.get(i).getBlock() == Blocks.AIR) air = (short) i;
        if (air < 0) {
            air = (short) palette.size();
            palette.add(Blocks.AIR.defaultBlockState());
        }
        short[][][] st = src.getStructure(), out = new short[sy][nsz][nsx];
        CompoundTag[][][] tes = src.getTileEntities();
        List<BlockPos> cut = cut(src, w);
        List<CompoundTag> outTes = new ArrayList<>();
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < nsz; z++)
                for (int x = 0; x < nsx; x++) {
                    boolean drop = cut.contains(new BlockPos(x + dx, y, z + dz));
                    out[y][z][x] = drop ? air : st[y][z + dz][x + dx];
                    CompoundTag te = tes[y][z + dz][x + dx];
                    if (te == null || drop) continue;
                    te = te.copy();
                    te.putInt("x", x);
                    te.putInt("y", y);
                    te.putInt("z", z);
                    outTes.add(te);
                }
        BlockPos p = src.getPrimaryBlockOffset();
        BlockPos np = new BlockPos(Math.clamp(p.getX() - dx, 0, nsx - 1), p.getY(), Math.clamp(p.getZ() - dz, 0, nsz - 1));
        // The anchor's own data names the blueprint's corners relative to it.
        for (CompoundTag te : outTes)
            if (te.contains("blueprintDataProvider") && te.getInt("x") == np.getX() && te.getInt("y") == np.getY() && te.getInt("z") == np.getZ()) {
                CompoundTag d = te.getCompound("blueprintDataProvider");
                BlockPosUtil.writeToNBT(d, "corner1", BlockPos.ZERO.subtract(np));
                BlockPosUtil.writeToNBT(d, "corner2", new BlockPos(nsx - 1, sy - 1, nsz - 1).subtract(np));
            }
        Blueprint bp = new Blueprint((short) nsx, (short) sy, (short) nsz, (short) palette.size(), palette, out, outTes.toArray(new CompoundTag[0]),
                new ArrayList<>(src.getRequiredMods()), src.getRegistryAccess());
        List<CompoundTag> ents = new ArrayList<>();
        for (CompoundTag e : src.getEntities()) {
            if (e == null || !e.contains("Pos", Tag.TAG_LIST)) continue;
            ListTag pos = e.getList("Pos", Tag.TAG_DOUBLE);
            double a = pos.getDouble(w[0] == 0 ? 0 : 2) - w[1];
            if (a < 0 || a >= w[2]) continue;
            CompoundTag c = e.copy();
            ListTag np2 = new ListTag();
            np2.add(DoubleTag.valueOf(pos.getDouble(0) - dx));
            np2.add(DoubleTag.valueOf(pos.getDouble(1)));
            np2.add(DoubleTag.valueOf(pos.getDouble(2) - dz));
            c.put("Pos", np2);
            if (c.contains("TileX")) {
                c.putInt("TileX", c.getInt("TileX") - dx);
                c.putInt("TileZ", c.getInt("TileZ") - dz);
            }
            ents.add(c);
        }
        bp.setEntities(ents.toArray(new CompoundTag[0]));
        bp.setName(src.getName());
        bp.setArchitects(src.getArchitects());
        if (src.getFileName() != null) bp.setFileName(src.getFileName());
        if (src.getFilePath() != null) bp.setFilePath(src.getFilePath());
        if (src.getPackName() != null) bp.setPackName(src.getPackName());
        bp.setCachePrimaryOffset(np);
        return bp;
    }

    /**
     * @return the cells (unturned blueprint) of wall-hung decorations (torches, signs, banners, ladders, buttons...) on
     * the window's edges whose support lies outside it: a crop leaves them out, the window choice avoids them
     */
    public static List<BlockPos> cut(Blueprint src, int[] w) {
        List<BlockPos> out = new ArrayList<>();
        BlockState[] palette = src.getPalette();
        short[][][] st = src.getStructure();
        int sy = src.getSizeY(), across = w[0] == 0 ? src.getSizeZ() : src.getSizeX();
        for (int edge : new int[]{w[1], w[1] + w[2] - 1})
            for (int y = 0; y < sy; y++)
                for (int c = 0; c < across; c++) {
                    int x = w[0] == 0 ? edge : c, z = w[0] == 0 ? c : edge;
                    Direction d = support(palette[st[y][z][x] & 0xFFFF]);
                    if (d == null) continue;
                    int at = (w[0] == 0 ? x + d.getStepX() : z + d.getStepZ());
                    if (at < w[1] || at >= w[1] + w[2]) out.add(new BlockPos(x, y, z));
                }
        return out;
    }

    /** @return the side a wall-hung block hangs from, or null when it stands or hangs vertically (or isn't hung) */
    private static Direction support(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof FaceAttachedHorizontalDirectionalBlock)
            return s.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.WALL ? s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : null;
        boolean hung = b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof LadderBlock
                || b instanceof WallSkullBlock || b instanceof TripWireHookBlock;
        return hung && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : null;
    }

    /**
     * @return the crop's path in {@link #PACK}: the straight's own path (so it still reads as a road / wall piece) with
     * its source pack and window appended; the same crop is always the same file
     */
    static String packPath(String pack, String path, int[] w) {
        String base = path.endsWith(".blueprint") ? path.substring(0, path.length() - ".blueprint".length()) : path;
        return base + "_crop_" + Integer.toHexString(pack.hashCode()) + "_" + (w[0] == 0 ? "x" : "z") + w[1] + "_" + w[2] + ".blueprint";
    }

    /**
     * Writes a crop into {@link #PACK} (unless the same bytes are there) and makes sure Structurize knows the pack. On
     * the server thread: the file must exist before a work order loads it. Crops are never deleted (a few KB each,
     * and an open work order or a built record may still name one).
     */
    static void store(Blueprint crop, String subPath) throws IOException {
        Path dir = DIR.toAbsolutePath().normalize(), f = dir.resolve(subPath).normalize();
        if (!f.startsWith(dir)) throw new IOException("crop path leaves the pack: " + subPath);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        NbtIo.writeCompressed(BlueprintUtil.writeBlueprintToNBT(crop), bo);
        byte[] bytes = bo.toByteArray();
        boolean fresh = !Files.exists(f) || !Arrays.equals(Files.readAllBytes(f), bytes);
        if (fresh) {
            Files.createDirectories(f.getParent());
            Files.write(f, bytes);
        }
        StructurePackMeta m = StructurePacks.getStructurePack(PACK);
        if (!fresh && m != null && m.getPath().toAbsolutePath().normalize().equals(dir)) return;
        // A new version each time a crop is added: players joining later get the pack again (Structurize syncs by version).
        String json = "{\"name\": \"" + PACK + "\", \"icon\": \"\", \"authors\": [\"City Builder\"], \"desc\": \"Fill gaps: straights cropped to close a wall or road gap\","
                + " \"mods\": [], \"version\": " + (System.currentTimeMillis() / 1000) + ".0, \"pack-format\": 1}";
        Files.writeString(dir.resolve("pack.json"), json, StandardCharsets.UTF_8);
        StructurePacks.discoverPackAtPath(dir, false, List.of(), false, "Local");
    }
}
