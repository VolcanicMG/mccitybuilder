package com.volcanicmg.mccitybuilder.server;

import com.ldtteam.structurize.api.BlockPosUtil;
import com.ldtteam.structurize.blocks.interfaces.IAnchorBlock;
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
     * is left out (air); the anchor stays where it was, or is copied to the nearest cell of the window when it falls
     * outside, so the file read back has the same primary offset; null when the window doesn't fit
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
        CompoundTag pte = tes[p.getY()][p.getZ()][p.getX()];
        // A real anchor (Structurize finds it again on load); without one, Structurize's fallback for the crop's size.
        boolean real = src.getPalette()[st[p.getY()][p.getZ()][p.getX()] & 0xFFFF].getBlock() instanceof IAnchorBlock
                || pte != null && pte.contains("blueprintDataProvider");
        BlockPos np = real ? new BlockPos(Math.clamp(p.getX() - dx, 0, nsx - 1), p.getY(), Math.clamp(p.getZ() - dz, 0, nsz - 1))
                : new BlockPos(nsx / 2, 0, nsz / 2);
        if (real && !np.equals(p.offset(-dx, 0, -dz))) {
            // The anchor fell outside the window: the same anchor block (and its data) moves to the nearest cell inside.
            out[np.getY()][np.getZ()][np.getX()] = st[p.getY()][p.getZ()][p.getX()];
            outTes.removeIf(te -> te.getInt("x") == np.getX() && te.getInt("y") == np.getY() && te.getInt("z") == np.getZ());
            if (pte != null) {
                CompoundTag a = pte.copy();
                a.putInt("x", np.getX());
                a.putInt("y", np.getY());
                a.putInt("z", np.getZ());
                outTes.add(a);
            }
        }
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
     * @param srcHash a hash of the straight's file: an updated straight gets new crop files, and orders keep theirs
     * @return the crop's path in {@link #PACK}: the straight's own path (so it still reads as a road / wall piece) with
     * its source pack, source content and window appended; the same crop is always the same file
     */
    static String packPath(String pack, String path, int[] w, String srcHash) {
        String[] pp = packPathParts(pack, path, w);
        return pp[0] + srcHash + pp[1];
    }

    /** @return {@link #packPath} before and after the source hash: every version of this crop matches both */
    static String[] packPathParts(String pack, String path, int[] w) {
        String base = path.endsWith(".blueprint") ? path.substring(0, path.length() - ".blueprint".length()) : path;
        return new String[]{base + "_crop_" + hash(pack.getBytes(StandardCharsets.UTF_8)) + "_", "_" + (w[0] == 0 ? "x" : "z") + w[1] + "_" + w[2] + ".blueprint"};
    }

    // Per "pack|folder": the paths of the folder's straights as City Builder sorts them (PieceSets.classify).
    private static final java.util.Map<String, java.util.Set<String>> straights = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Off the server thread: the straights of the road / wall folder a piece is in, sorted into families the way the
     * planner does (built-in roles; a player's own role overrides aren't known here). Cached per server run.
     *
     * @return their paths (empty when the folder can't be read)
     */
    static java.util.Set<String> straightsOf(String pack, String path, net.minecraft.core.HolderLookup.Provider regs) {
        String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        return straights.computeIfAbsent(pack + "|" + folder, k -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            StructurePackMeta m = StructurePacks.getStructurePack(pack);
            if (m == null) return out;
            List<com.volcanicmg.mccitybuilder.planner.PieceSets.Piece> pieces = new ArrayList<>();
            try (var files = Files.list(m.getPath().resolve(folder))) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".blueprint")) continue;
                    String sub = folder.isEmpty() ? name : folder + "/" + name;
                    Blueprint bp = StructurePacks.getBlueprint(pack, sub, true, regs);
                    if (bp == null) continue;
                    String[] sn = com.volcanicmg.mccitybuilder.planner.PieceSets.split(name);
                    pieces.add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece(sub, sn[0], Integer.parseInt(sn[1]), bp.getSizeX(), bp.getSizeY(), bp.getSizeZ()));
                }
            } catch (IOException | RuntimeException e) {
                return out;
            }
            for (var fam : com.volcanicmg.mccitybuilder.planner.PieceSets.classify(pieces).values())
                for (var g : fam.roles().getOrDefault(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.STRAIGHT, List.of()))
                    for (var p : g.pieces()) out.add(p.path());
            return out;
        });
    }

    /** @return the first 8 hex digits of the bytes' SHA-256 */
    static String hash(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b), 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Most crop files / bytes the pack may hold (a client could otherwise ask for endless windows). */
    static final int MAX_FILES = 2000;
    static final long MAX_BYTES = 64L << 20;

    /**
     * Writes a crop (its unturned blueprint as NBT) into {@link #PACK} unless the same bytes are there, and makes sure
     * Structurize knows the pack. On the server thread, only once every check passed: the file must exist before a
     * work order loads it. Crops are never deleted (a few KB each; an open work order or a built record may name one).
     *
     * @throws IOException also when the pack is full ({@link #MAX_FILES}, {@link #MAX_BYTES})
     */
    static void store(CompoundTag crop, String subPath) throws IOException {
        Path dir = DIR.toAbsolutePath().normalize(), f = dir.resolve(subPath).normalize();
        if (!f.startsWith(dir)) throw new IOException("crop path leaves the pack: " + subPath);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        NbtIo.writeCompressed(crop, bo);
        byte[] bytes = bo.toByteArray();
        boolean fresh = !Files.exists(f);
        if (fresh) {
            // ponytail: walks the pack per new file; fine up to MAX_FILES, keep a running count if that grows.
            long[] n = {0, 0};
            if (Files.isDirectory(dir))
                try (var all = Files.walk(dir)) {
                    all.filter(Files::isRegularFile).forEach(x -> {
                        n[0]++;
                        n[1] += x.toFile().length();
                    });
                }
            if (n[0] >= MAX_FILES || n[1] + bytes.length > MAX_BYTES) throw new IOException("the crop pack is full (" + n[0] + " files)");
            Files.createDirectories(f.getParent());
            write(f, bytes);
        } else if (!java.util.Arrays.equals(Files.readAllBytes(f), bytes)) {
            // The same name always holds the same crop: a file that differs was written wrong (older builds turned its
            // block entities), so it is replaced.
            write(f, bytes);
            fresh = true;
        }
        if (fresh) register(true);
        else ensurePack();
    }

    /** @return where a crop's file goes (its folder), for the blueprint's own file path */
    static Path fileOf(String subPath) {
        return DIR.toAbsolutePath().normalize().resolve(subPath);
    }

    // Whether this server run has registered the pack (Structurize forgets it only when it rediscovers packs).
    private static Object registeredFor;

    /**
     * Makes sure Structurize knows {@link #PACK} (a paste of a crop's anchor looks its pack up), once per server run,
     * writing only its pack.json and icon when they're missing.
     *
     * @throws IOException when another pack of that name is registered from another folder (it is left alone)
     */
    static void ensurePack() throws IOException {
        Object server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (registeredFor == server && server != null) return;
        // A new server run: packs may have changed.
        straights.clear();
        register(false);
        registeredFor = server;
    }

    /** @param bump true when a crop was added: a new version, so players joining later get the pack again */
    private static void register(boolean bump) throws IOException {
        Path dir = DIR.toAbsolutePath().normalize();
        StructurePackMeta m = StructurePacks.getStructurePack(PACK);
        if (m != null && !m.getPath().toAbsolutePath().normalize().equals(dir)) {
            com.volcanicmg.mccitybuilder.CityBuilder.LOGGER.warn("City Builder: another structure pack named {} is at {}; Fill gaps crops are off", PACK, m.getPath());
            throw new IOException("another pack is named " + PACK);
        }
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("icon.png")))
            try (var in = Crops.class.getResourceAsStream("/assets/mccitybuilder/crop_pack_icon.png")) {
                if (in != null) write(dir.resolve("icon.png"), in.readAllBytes());
            }
        if (!bump && Files.exists(dir.resolve("pack.json"))) {
            if (m == null) StructurePacks.discoverPackAtPath(dir, false, List.of(), false, "Local");
            return;
        }
        String json = "{\"name\": \"" + PACK + "\", \"icon\": \"icon.png\", \"authors\": [\"City Builder\"], \"desc\": \"Fill gaps: straights cropped to close a wall or road gap\","
                + " \"mods\": [], \"version\": " + (System.currentTimeMillis() / 1000) + ".0, \"pack-format\": 1}";
        write(dir.resolve("pack.json"), json.getBytes(StandardCharsets.UTF_8));
        StructurePacks.discoverPackAtPath(dir, false, List.of(), false, "Local");
    }

    /** Writes a file whole or not at all (a temp file moved over it), so a reader never sees half of one. */
    private static void write(Path f, byte[] bytes) throws IOException {
        // Beside the pack, not in it: Structurize's client-sync zip walks the pack folder and trips on a half-made file.
        Path tmp = DIR.toAbsolutePath().resolveSibling(DIR.getFileName() + ".tmp").resolve(f.getFileName());
        Files.createDirectories(tmp.getParent());
        Files.write(tmp, bytes);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

}
