package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.storage.StructurePacks;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A style pack's road and wall pieces on the client: the blueprints of its road / wall folders (loaded in the
 * background), sorted into {@link PieceSets} families with the user's per-pack overrides (saved under
 * {@code mccitybuilder/piecesets/}), rotated copies for ghosts and profiles, and the layout of drawn lines into
 * placed pieces: straights tiled along each segment, corners / T / cross / tower pieces at joints (turned and shifted
 * by matching the solid profile of their faces to the neighbouring straights), end caps, gates.
 */
final class PieceCatalog {
    /**
     * A piece to place: its blueprint path, quarter turns, anchor, footprint box {x0, z0, x1, z1}, role, block count,
     * and the bottom and height of its blueprint box (for outlines).
     */
    record Placed(String path, int turns, BlockPos anchor, int[] box, PieceSets.Role role, int blocks, int y0, int h) {
    }

    private static final Map<String, Map<String, PieceSets.Family>> families = new HashMap<>();
    private static final Map<String, CompletableFuture<Blueprint>> loads = new HashMap<>();
    private static final Map<String, Blueprint> rotated = new HashMap<>();
    private static final Map<String, Integer> blockCounts = new HashMap<>();
    // Pieces whose blueprint failed to load ("pack|path"): skipped, so their pack doesn't wait for them forever.
    private static final Set<String> failed = new HashSet<>();
    // Overrides: pack -> "family|ROLE" -> group base name ("-" = role disabled).
    private static final Map<String, Map<String, String>> overrides = new HashMap<>();

    private PieceCatalog() {
    }

    static void clear() {
        families.clear();
        loads.clear();
        rotated.clear();
        blockCounts.clear();
        failed.clear();
        topViews.clear();
        overrides.clear();
    }

    /** @return a fresh copy of the blueprint turned {@code turns} quarter turns, or null while it loads */
    static Blueprint rotated(String pack, String path, int turns) {
        Minecraft mc = Minecraft.getInstance();
        String key = pack + "|" + path + "|" + turns;
        Blueprint done = rotated.get(key);
        if (done != null || mc.level == null) return done;
        CompletableFuture<Blueprint> f = loads.computeIfAbsent(key, k -> StructurePacks.getBlueprintFuture(pack, path, true, mc.level.registryAccess()));
        if (!f.isDone()) return null;
        Blueprint bp = f.isCompletedExceptionally() ? null : f.getNow(null);
        if (bp == null) {
            if (failed.add(pack + "|" + path)) CityBuilder.LOGGER.warn("City Builder: can't load piece {} of {}; skipping it", path, pack);
            return null;
        }
        RotationMirror rm = RotationMirror.NONE;
        for (int i = 0; i < turns; i++) rm = rm.rotate(Rotation.CLOCKWISE_90);
        bp.setRotationMirror(rm, mc.level);
        rotated.put(key, bp);
        return bp;
    }

    /** @return true when the piece's blueprint failed to load (it is skipped, not waited for) */
    static boolean failed(String pack, String path) {
        return failed.contains(pack + "|" + path);
    }

    static RotationMirror rotation(int turns) {
        RotationMirror rm = RotationMirror.NONE;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) rm = rm.rotate(Rotation.CLOCKWISE_90);
        return rm;
    }

    /** @return the pack's families (all tools), or null while its pieces load */
    static Map<String, PieceSets.Family> families(String pack) {
        Map<String, PieceSets.Family> done = families.get(pack);
        if (done != null) return done;
        List<String> folders = new ArrayList<>();
        for (String f : Blueprints.decoFolders(pack)) if (PieceSets.tool(f + "/x.blueprint") != null) folders.add(f);
        if (Blueprints.decoFolders(pack).isEmpty()) return null;
        List<PieceSets.Piece> pieces = new ArrayList<>();
        boolean loading = false;
        for (String f : folders) {
            List<Blueprints.Hut> ds = Blueprints.decorations(pack, f);
            if (ds == null) {
                loading = true;
                continue;
            }
            for (Blueprints.Hut d : ds) {
                Blueprint bp = rotated(pack, d.path(), 0);
                if (bp == null) {
                    loading |= !failed(pack, d.path());
                    continue;
                }
                String[] sn = PieceSets.split(d.path().substring(d.path().lastIndexOf('/') + 1));
                pieces.add(new PieceSets.Piece(d.path(), sn[0], Integer.parseInt(sn[1]), bp.getSizeX(), bp.getSizeY(), bp.getSizeZ()));
            }
        }
        if (loading) return null;
        Map<String, PieceSets.Family> out = PieceSets.classify(pieces);
        families.put(pack, out);
        return out;
    }

    /** @return the usable families of a tool in the pack (have a straight), in order; null while loading */
    static List<String> familyKeys(String pack, String tool) {
        Map<String, PieceSets.Family> fs = families(pack);
        if (fs == null) return null;
        List<String> out = new ArrayList<>();
        for (var en : fs.entrySet()) if (en.getValue().tool().equals(tool) && !groups(pack, en.getValue(), PieceSets.Role.STRAIGHT).isEmpty()) out.add(en.getKey());
        return out;
    }

    /** @return the groups used for a role: the user's override if any, else every group the classifier found */
    static List<PieceSets.Group> groups(String pack, PieceSets.Family f, PieceSets.Role r) {
        String o = overrides(pack).get(f.tool() + ":" + f.key() + "|" + r.name());
        List<PieceSets.Group> all = new ArrayList<>(r == PieceSets.Role.STRAIGHT ? f.straights() : f.of(r));
        if (o == null) return all;
        if (o.equals("-")) return List.of();
        for (PieceSets.Family any : families(pack).values())
            if (any.tool().equals(f.tool()) && any.key().equals(f.key()))
                for (List<PieceSets.Group> gs : any.roles().values())
                    for (PieceSets.Group g : gs) if (g.base().equals(o)) return List.of(new PieceSets.Group(g.base(), r, g.pieces(), g.levels()));
        return all;
    }

    /** @return every group of the family (for the editor's choices) */
    static List<PieceSets.Group> allGroups(PieceSets.Family f) {
        List<PieceSets.Group> out = new ArrayList<>();
        for (List<PieceSets.Group> gs : f.roles().values()) out.addAll(gs);
        out.sort(java.util.Comparator.comparing(PieceSets.Group::base));
        return out;
    }

    // ---- overrides (the piece-set editor) ----

    private static Path overridesFile(String pack) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CityBuilder.MODID).resolve("piecesets").resolve(pack.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
    }

    static Map<String, String> overrides(String pack) {
        return overrides.computeIfAbsent(pack, k -> {
            Map<String, String> m = new LinkedHashMap<>();
            try {
                Path f = overridesFile(pack);
                if (Files.exists(f))
                    for (String line : Files.readAllLines(f)) {
                        String[] kv = line.split("\t");
                        if (kv.length == 2) m.put(kv[0], kv[1]);
                    }
            } catch (Exception e) {
                CityBuilder.LOGGER.warn("City Builder: can't read piece set overrides for {}", pack, e);
            }
            return m;
        });
    }

    /** Sets (or with null, clears) the group used for a role in a family, and saves the pack's overrides. */
    static void override(String pack, String familyKey, PieceSets.Role role, String base) {
        Map<String, String> m = overrides(pack);
        if (base == null) m.remove(familyKey + "|" + role.name());
        else m.put(familyKey + "|" + role.name(), base);
        try {
            Path f = overridesFile(pack);
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            m.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
            Files.writeString(f, sb.toString());
        } catch (Exception e) {
            CityBuilder.LOGGER.warn("City Builder: can't save piece set overrides for {}", pack, e);
        }
    }

    // ---- profiles ----

    private static boolean solid(BlockState st) {
        return !st.isAir() && st.getBlock() != com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get();
    }

    /**
     * @param box  the piece's world box {x0, z0, x1, z1} (for the turned blueprint {@code bp})
     * @param face 0 = +X, 1 = +Z, 2 = -X, 3 = -Z
     * @param ref  the world coordinate lateral positions are measured from
     * @param ids  true: each cell also carries its block (so a face matches by material too, not only by shape)
     * @return the solid cells of that face as {@code y * 65536 + lateral - ref + 30000} (+ block id << 32)
     */
    static Set<Long> face(Blueprint bp, int[] box, int face, int ref, boolean ids) {
        Set<Long> out = new HashSet<>();
        int sx = bp.getSizeX(), sy = bp.getSizeY(), sz = bp.getSizeZ();
        for (int y = 0; y < sy; y++)
            if (face == 0 || face == 2)
                for (int z = 0; z < sz; z++) {
                    BlockState st = bp.getBlockState(new BlockPos(face == 0 ? sx - 1 : 0, y, z));
                    if (solid(st)) out.add(cell(st, y, box[1] + z - ref, ids));
                }
            else
                for (int x = 0; x < sx; x++) {
                    BlockState st = bp.getBlockState(new BlockPos(x, y, face == 1 ? sz - 1 : 0));
                    if (solid(st)) out.add(cell(st, y, box[0] + x - ref, ids));
                }
        return out;
    }

    private static long cell(BlockState st, int y, int lateral, boolean ids) {
        long v = (long) y * 65536 + lateral + 30000;
        return ids ? v + ((long) (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(st.getBlock()) & 0x7FFF) << 32) : v;
    }

    private static final Map<String, int[]> topViews = new HashMap<>();

    /**
     * A top-down picture of a piece (unturned): per column {@code z * sizeX + x}, the map colour of its highest solid
     * block, lighter the higher it is; 0 for empty columns.
     *
     * @return the colours, or null while the blueprint loads
     */
    static int[] topView(String pack, String path) {
        int[] done = topViews.get(pack + "|" + path);
        if (done != null) return done;
        Blueprint bp = rotated(pack, path, 0);
        Minecraft mc = Minecraft.getInstance();
        if (bp == null || mc.level == null) return null;
        int sx = bp.getSizeX(), sy = bp.getSizeY(), sz = bp.getSizeZ();
        int[] out = new int[sx * sz];
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++)
                for (int y = sy - 1; y >= 0; y--) {
                    BlockState st = bp.getBlockState(new BlockPos(x, y, z));
                    if (!solid(st)) continue;
                    int c = st.getMapColor(mc.level, BlockPos.ZERO).col;
                    float k = 0.6f + 0.4f * (y + 1) / sy;
                    out[z * sx + x] = 0xFF000000 | Math.min(255, (int) ((c >> 16 & 255) * k)) << 16 | Math.min(255, (int) ((c >> 8 & 255) * k)) << 8
                            | Math.min(255, (int) ((c & 255) * k));
                    break;
                }
        topViews.put(pack + "|" + path, out);
        return out;
    }

    static int blocks(String pack, String path) {
        return blockCounts.computeIfAbsent(pack + "|" + path, k -> {
            Blueprint bp = rotated(pack, path, 0);
            if (bp == null) return 0;
            int n = 0;
            for (var info : bp.getBlockInfoAsList()) if (solid(info.getState())) n++;
            return n;
        });
    }

    // ---- layout ----

    /**
     * The result of laying out every planned line: placed pieces per line, cells left uncovered ("short"), where that
     * gap is (box or null), the length each line needs to fit a piece, and whether all blueprints were ready (per line,
     * and overall): an incomplete line may miss pieces or have a butt joint where a joint piece belongs, so it must
     * not be built yet.
     */
    record Layout(List<List<Placed>> lines, List<Integer> shortened, List<int[]> gaps, List<Integer> need, List<Boolean> lineComplete, boolean complete) {
    }

    /**
     * Lays out every line. Joints (2+ lines of the same family at a cell) are reserved first, once each: a corner /
     * T / cross piece (or a tower when the line asks), turned and shifted by profile matching; with no such piece, a
     * butt joint where one line runs through the joint square and the others stop at its face. Every run then starts
     * exactly at the joint's face (no overlap, no gap); free ends get an end cap; a gate goes in the middle when
     * asked; straights fill the rest, and what can't be covered is left at a free end and reported.
     */
    static Layout layout(List<Plan.Line> lines) {
        List<List<Placed>> out = new ArrayList<>();
        List<Integer> shortened = new ArrayList<>(), need = new ArrayList<>();
        List<int[]> gaps = new ArrayList<>();
        List<Boolean> lineComplete = new ArrayList<>();
        boolean complete = true;
        // Endpoints: which lines meet at each cell.
        Map<Long, List<int[]>> at = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            at.computeIfAbsent(key(l.x0(), l.z0()), k -> new ArrayList<>()).add(new int[]{i, 0});
            at.computeIfAbsent(key(l.x1(), l.z1()), k -> new ArrayList<>()).add(new int[]{i, 1});
        }
        Map<Long, Placed> joints = new HashMap<>();
        // Butt joints: {x0, z0, x1, z1, owner line, 1 = only because the joint piece is still loading}.
        Map<Long, int[]> butts = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            List<Placed> placed = new ArrayList<>();
            out.add(placed);
            Map<String, PieceSets.Family> fs = families(l.pack());
            PieceSets.Family f = fs == null ? null : fs.get(l.family());
            List<PieceSets.Group> straights = f == null ? List.of() : groups(l.pack(), f, PieceSets.Role.STRAIGHT);
            if (straights.isEmpty()) {
                complete &= fs != null;
                lineComplete.add(fs != null);
                shortened.add(0);
                gaps.add(null);
                need.add(Integer.MAX_VALUE);
                continue;
            }
            int[] a = {l.x0(), l.z0()}, b = {l.x1(), l.z1()};
            int dir = PieceLayout.dir(a, b), len = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) + 1;
            PieceSets.Piece primary = primary(l, i);
            int width = primary.across();
            int[] off = new int[2];
            boolean[] free = new boolean[2];
            boolean aligned = false, ok = true;
            int shift = 0;
            for (int end = 0; end < 2; end++) {
                int[] c = end == 0 ? a : b;
                int d = end == 0 ? dir : (dir + 2) % 4;
                long k = key(c[0], c[1]);
                List<int[]> meet = new ArrayList<>();
                for (int[] m : at.get(k))
                    if (m[0] != i && lines.get(m[0]).family().equals(l.family()) && lines.get(m[0]).pack().equals(l.pack())) meet.add(m);
                if (meet.isEmpty()) {
                    free[end] = true;
                    // A free end: an end cap, if the family has one.
                    List<PieceSets.Group> caps = groups(l.pack(), f, PieceSets.Role.END);
                    if (!caps.isEmpty()) {
                        PieceSets.Piece cap = caps.get(0).pick(l.level(), i);
                        int run = cap.run(), turns = PieceLayout.turns(cap.sx(), cap.sz(), (d + 2) % 4);
                        List<int[]> bx = PieceLayout.boxes(c, d, 0, List.of(run), cap.across(), 0);
                        Placed p = place(l.pack(), cap.path(), turns, bx.get(0), PieceSets.Role.END);
                        if (p == null) ok &= failed(l.pack(), cap.path());
                        else placed.add(p);
                        off[end] = run;
                    }
                    continue;
                }
                Placed joint = joints.get(k);
                if (joint == null && !butts.containsKey(k)) {
                    jointLoading = false;
                    joint = joint(l, f, c, d, meet, lines, i, primary, width);
                    if (joint != null) {
                        joints.put(k, joint);
                        placed.add(joint);
                    } else {
                        // No joint piece: one line runs through the joint square, the others stop at its face.
                        int[] who = new int[meet.size() + 1], dirs = new int[who.length], widths = new int[who.length];
                        List<int[]> all = new ArrayList<>(meet);
                        all.add(new int[]{i, end});
                        all.sort(java.util.Comparator.comparingInt(m -> m[0]));
                        for (int n = 0; n < all.size(); n++) {
                            Plan.Line o = lines.get(all.get(n)[0]);
                            int od = PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()});
                            who[n] = all.get(n)[0];
                            dirs[n] = all.get(n)[1] == 0 ? od : (od + 2) % 4;
                            PieceSets.Piece op = primary(o, who[n]);
                            widths[n] = op == null ? width : op.across();
                        }
                        int[] sq = PieceLayout.square(c, dirs, widths);
                        butts.put(k, new int[]{sq[0], sq[1], sq[2], sq[3], who[PieceLayout.owner(dirs)], jointLoading ? 1 : 0});
                    }
                }
                if (joint != null) {
                    int[] jb = joint.box();
                    off[end] = PieceLayout.reach(c, d, jb);
                    // Lateral alignment: shift the straights so their end face matches the joint's face profile.
                    Blueprint jbp = rotated(l.pack(), joint.path(), joint.turns()), sbp = rotated(l.pack(), primary.path(), PieceLayout.turns(primary.sx(), primary.sz(), dir));
                    // From the start's joint, else the end's (lateral positions don't depend on where along the run).
                    if (sbp == null && !failed(l.pack(), primary.path())) ok = false;
                    if ((end == 0 || !aligned) && jbp != null && sbp != null) {
                        int ref = dir % 2 == 0 ? c[1] : c[0];
                        int[] sbox = PieceLayout.boxes(a, dir, 0, List.of(primary.run()), width, 0).get(0);
                        shift = PieceLayout.bestShift(face(jbp, jb, d, ref, false), face(sbp, sbox, (d + 2) % 4, ref, false), 3);
                        aligned = true;
                    }
                } else {
                    int[] bt = butts.get(k);
                    off[end] = PieceLayout.offset(c, d, bt, bt[4] == i);
                    if (bt[5] == 1) ok = false;
                }
            }
            // The run between the joints / caps: a gate in the middle when asked, straights around it.
            int n = len - off[0] - off[1];
            List<PieceSets.Group> gates = l.gate() ? groups(l.pack(), f, PieceSets.Role.GATE) : List.of();
            // Lengths of the straights as picked for this line (levels can change a piece's size), same width only.
            int[] lens = straights.stream().map(g -> g.pick(g.levels() ? l.level() : l.variant(), 0)).filter(p -> p.across() == width)
                    .mapToInt(PieceSets.Piece::run).distinct().toArray();
            need.add(PieceLayout.need(off[0], off[1], lens));
            List<Integer> tiles = new ArrayList<>();
            List<PieceSets.Piece> tilePieces = new ArrayList<>();
            int used;
            if (!gates.isEmpty() && n >= gates.get(0).first().run()) {
                PieceSets.Piece gate = gates.get(0).pick(l.level(), i);
                int left = (n - gate.run()) / 2;
                List<Integer> l1 = PieceLayout.fill(left, lens), l2 = PieceLayout.fill(n - gate.run() - left, lens);
                for (int x : l1) tiles.add(x);
                tiles.add(-1);
                for (int x : l2) tiles.add(x);
                used = gate.run() + l1.stream().mapToInt(Integer::intValue).sum() + l2.stream().mapToInt(Integer::intValue).sum();
                for (int t : tiles) tilePieces.add(t < 0 ? gate : straightOf(straights, width, t, l, i * 1000 + tilePieces.size()));
            } else {
                for (int x : PieceLayout.fill(n, lens)) tiles.add(x);
                used = tiles.stream().mapToInt(Integer::intValue).sum();
                for (int t : tiles) tilePieces.add(straightOf(straights, width, t, l, i * 1000 + tilePieces.size()));
            }
            int left = Math.max(0, n - used);
            shortened.add(left);
            // What can't be covered goes to a free end, so runs always meet their joints.
            int start = PieceLayout.runStart(off[0], left, free[0], free[1]);
            gaps.add(left == 0 ? null : PieceLayout.boxes(a, dir, start == off[0] ? off[0] + used : off[0], List.of(left), width, shift).get(0));
            List<Integer> runs = new ArrayList<>();
            for (PieceSets.Piece p : tilePieces) runs.add(p.run());
            List<int[]> boxes = PieceLayout.boxes(a, dir, start, runs, width, shift);
            for (int k = 0; k < tilePieces.size(); k++) {
                PieceSets.Piece p = tilePieces.get(k);
                Placed pl = place(l.pack(), p.path(), PieceLayout.turns(p.sx(), p.sz(), dir), boxes.get(k), tiles.get(k) < 0 ? PieceSets.Role.GATE : PieceSets.Role.STRAIGHT);
                if (pl == null) ok &= failed(l.pack(), p.path());
                else placed.add(pl);
            }
            lineComplete.add(ok);
            complete &= ok;
        }
        return new Layout(out, shortened, gaps, need, lineComplete, complete);
    }

    /** @return the line's main straight at its level / variant (sets its width), or null without one */
    static PieceSets.Piece primary(Plan.Line l, int seed) {
        Map<String, PieceSets.Family> fs = families(l.pack());
        PieceSets.Family f = fs == null ? null : fs.get(l.family());
        List<PieceSets.Group> st = f == null ? List.of() : groups(l.pack(), f, PieceSets.Role.STRAIGHT);
        return st.isEmpty() ? null : st.get(0).pick(st.get(0).levels() ? l.level() : l.variant(), seed * 1000);
    }

    /**
     * A straight piece of the line's family at the line's start (the preview under the cursor before a chain starts).
     *
     * @return the piece, or null while it loads
     */
    static Placed preview(Plan.Line l) {
        PieceSets.Piece p = primary(l, 0);
        if (p == null) return null;
        int[] a = {l.x0(), l.z0()};
        int dir = PieceLayout.dir(a, new int[]{l.x1(), l.z1()});
        return place(l.pack(), p.path(), PieceLayout.turns(p.sx(), p.sz(), dir), PieceLayout.boxes(a, dir, 0, List.of(p.run()), p.across(), 0).get(0), PieceSets.Role.STRAIGHT);
    }

    /** @return a straight of run length {@code run} and the line's width, at the line's level / variant */
    private static PieceSets.Piece straightOf(List<PieceSets.Group> straights, int width, int run, Plan.Line l, int seed) {
        for (PieceSets.Group g : straights) {
            PieceSets.Piece p = g.pick(g.levels() ? l.level() : l.variant(), seed);
            if (p.run() == run && p.across() == width) return p;
        }
        return straights.get(0).pick(straights.get(0).levels() ? l.level() : l.variant(), seed);
    }

    private static boolean collinear(Plan.Line l, int d, List<int[]> meet, List<Plan.Line> lines) {
        if (meet.size() != 1) return false;
        Plan.Line o = lines.get(meet.get(0)[0]);
        int od = meet.get(0)[1] == 0 ? PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()})
                : (PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()}) + 2) % 4;
        return od == (d + 2) % 4;
    }

    // Set by joint() when it returned null only because a blueprint is still loading.
    private static boolean jointLoading;

    /**
     * The joint piece at cell {@code c}: a tower when asked (and the family has one), else corner / T / cross by how
     * many lines meet; none for two lines in a straight line. Its quarter turn is the one whose faces toward the lines
     * best match a straight's end profile.
     */
    private static Placed joint(Plan.Line l, PieceSets.Family f, int[] c, int d, List<int[]> meet, List<Plan.Line> lines, int i, PieceSets.Piece straight,
                                int width) {
        if (!l.towers() && collinear(l, d, meet, lines)) return null;
        int degree = meet.size() + 1;
        PieceSets.Role role = l.towers() && !groups(l.pack(), f, PieceSets.Role.TOWER).isEmpty() ? PieceSets.Role.TOWER
                : degree >= 4 ? PieceSets.Role.CROSS : degree == 3 ? PieceSets.Role.TEE : PieceSets.Role.CORNER;
        List<PieceSets.Group> gs = groups(l.pack(), f, role);
        if (gs.isEmpty() && role != PieceSets.Role.CORNER) gs = groups(l.pack(), f, PieceSets.Role.CORNER);
        if (gs.isEmpty()) return null;
        // Prefer the group whose size suits the straights (e.g. even / odd road widths).
        PieceSets.Group g = gs.get(0);
        for (PieceSets.Group x : gs) if (Math.abs(x.first().across() - width) < Math.abs(g.first().across() - width)) g = x;
        PieceSets.Piece p = g.pick(l.level(), i);
        // Directions out of the joint along each line.
        List<Integer> outs = new ArrayList<>(List.of(d));
        for (int[] m : meet) {
            Plan.Line o = lines.get(m[0]);
            int od = PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()});
            outs.add(m[1] == 0 ? od : (od + 2) % 4);
        }
        Blueprint sbp0 = rotated(l.pack(), straight.path(), PieceLayout.turns(straight.sx(), straight.sz(), 0));
        int best = 0;
        double bestScore = -1;
        for (int k = 0; k < 4; k++) {
            Blueprint jbp = rotated(l.pack(), p.path(), k);
            // Every turn must be scored, or the pick could change once the rest loads.
            if (jbp == null || sbp0 == null) {
                jointLoading = !failed(l.pack(), p.path()) && !failed(l.pack(), straight.path());
                return null;
            }
            int[] jb = PieceLayout.centred(c, jbp.getSizeX(), jbp.getSizeZ());
            double score = 0;
            for (int out : outs) {
                Blueprint sbp = rotated(l.pack(), straight.path(), PieceLayout.turns(straight.sx(), straight.sz(), out));
                if (sbp == null) {
                    jointLoading = !failed(l.pack(), straight.path());
                    return null;
                }
                int ref = out % 2 == 0 ? c[1] : c[0];
                int[] sbox = PieceLayout.boxes(c, out, 1, List.of(straight.run()), width, 0).get(0);
                // By shape and material: a road corner's faces can share a shape, its open sides share the road's blocks.
                Set<Long> jf = face(jbp, jb, out, ref, false), sf = face(sbp, sbox, (out + 2) % 4, ref, false);
                Set<Long> jm = face(jbp, jb, out, ref, true), sm = face(sbp, sbox, (out + 2) % 4, ref, true);
                int sh = PieceLayout.bestShift(jf, sf, 3);
                score += PieceLayout.jaccard(jf, sf, sh) + PieceLayout.jaccard(jm, sm, sh);
            }
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = k;
            }
        }
        Blueprint jbp = rotated(l.pack(), p.path(), best);
        if (jbp == null) return null;
        return place(l.pack(), p.path(), best, PieceLayout.centred(c, jbp.getSizeX(), jbp.getSizeZ()), role);
    }

    /** @return the piece with its anchor on the ground at the box's centre (stepped per piece), or null while it loads */
    private static Placed place(String pack, String path, int turns, int[] box, PieceSets.Role role) {
        Blueprint bp = rotated(pack, path, turns);
        if (bp == null) return null;
        BlockPos o = bp.getPrimaryBlockOffset();
        Integer g = Planner.groundAt((box[0] + box[2]) / 2, (box[1] + box[3]) / 2);
        int ground = (g == null ? 64 : g) - 1;
        int y = ground + BlueprintTagUtils.getGroundAnchorOffset(bp, 1) - 1;
        return new Placed(path, turns, new BlockPos(box[0] + o.getX(), y, box[1] + o.getZ()), box, role, blocks(pack, path), y - o.getY(), bp.getSizeY());
    }

    private static long key(int x, int z) {
        return (long) x << 32 ^ (z & 0xFFFFFFFFL);
    }
}
