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
    /** A piece to place: its blueprint path, quarter turns, anchor, footprint box {x0, z0, x1, z1}, role, block count. */
    record Placed(String path, int turns, BlockPos anchor, int[] box, PieceSets.Role role, int blocks) {
    }

    private static final Map<String, Map<String, PieceSets.Family>> families = new HashMap<>();
    private static final Map<String, CompletableFuture<Blueprint>> loads = new HashMap<>();
    private static final Map<String, Blueprint> rotated = new HashMap<>();
    private static final Map<String, Integer> blockCounts = new HashMap<>();
    // Overrides: pack -> "family|ROLE" -> group base name ("-" = role disabled).
    private static final Map<String, Map<String, String>> overrides = new HashMap<>();

    private PieceCatalog() {
    }

    static void clear() {
        families.clear();
        loads.clear();
        rotated.clear();
        blockCounts.clear();
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
        if (bp == null) return null;
        RotationMirror rm = RotationMirror.NONE;
        for (int i = 0; i < turns; i++) rm = rm.rotate(Rotation.CLOCKWISE_90);
        bp.setRotationMirror(rm, mc.level);
        rotated.put(key, bp);
        return bp;
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
                    loading = true;
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
     * @return the solid cells of that face as {@code y * 65536 + lateral - ref + 30000}
     */
    static Set<Long> face(Blueprint bp, int[] box, int face, int ref) {
        Set<Long> out = new HashSet<>();
        int sx = bp.getSizeX(), sy = bp.getSizeY(), sz = bp.getSizeZ();
        for (int y = 0; y < sy; y++)
            if (face == 0 || face == 2)
                for (int z = 0; z < sz; z++) {
                    if (solid(bp.getBlockState(new BlockPos(face == 0 ? sx - 1 : 0, y, z)))) out.add((long) y * 65536 + box[1] + z - ref + 30000);
                }
            else
                for (int x = 0; x < sx; x++)
                    if (solid(bp.getBlockState(new BlockPos(x, y, face == 1 ? sz - 1 : 0)))) out.add((long) y * 65536 + box[0] + x - ref + 30000);
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

    /** The result of laying out every planned line: placed pieces per line, and whether all blueprints were ready. */
    record Layout(List<List<Placed>> lines, List<Integer> shortened, boolean complete) {
    }

    /**
     * Lays out every line: joints (where 2+ lines of the same family meet: corner / T / cross, or a tower when the
     * line asks for towers) are placed once each and turned / shifted by profile matching; end caps go at free ends,
     * a gate in the middle when asked, and straights fill the rest (longest that fit, then shorter; what can't be
     * covered is left over and reported).
     */
    static Layout layout(List<Plan.Line> lines) {
        List<List<Placed>> out = new ArrayList<>();
        List<Integer> shortened = new ArrayList<>();
        boolean complete = true;
        // Endpoints: which lines meet at each cell.
        Map<Long, List<int[]>> at = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            at.computeIfAbsent(key(l.x0(), l.z0()), k -> new ArrayList<>()).add(new int[]{i, 0});
            at.computeIfAbsent(key(l.x1(), l.z1()), k -> new ArrayList<>()).add(new int[]{i, 1});
        }
        Map<Long, Placed> joints = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            List<Placed> placed = new ArrayList<>();
            out.add(placed);
            Map<String, PieceSets.Family> fs = families(l.pack());
            PieceSets.Family f = fs == null ? null : fs.get(l.family());
            if (f == null) {
                complete &= fs != null;
                shortened.add(0);
                continue;
            }
            List<PieceSets.Group> straights = groups(l.pack(), f, PieceSets.Role.STRAIGHT);
            if (straights.isEmpty()) {
                shortened.add(0);
                continue;
            }
            int[] a = {l.x0(), l.z0()}, b = {l.x1(), l.z1()};
            int dir = PieceLayout.dir(a, b), len = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) + 1;
            PieceSets.Piece primary = straights.get(0).pick(straights.get(0).levels() ? l.level() : l.variant(), i * 1000);
            int width = primary.across();
            // Joints at both ends (placed by the first line that reaches them) and how far they reach into this line.
            int[] off = new int[2];
            int shift = 0;
            for (int end = 0; end < 2; end++) {
                int[] c = end == 0 ? a : b;
                int d = end == 0 ? dir : (dir + 2) % 4;
                List<int[]> meet = new ArrayList<>();
                for (int[] m : at.get(key(c[0], c[1])))
                    if (m[0] != i && lines.get(m[0]).family().equals(l.family()) && lines.get(m[0]).pack().equals(l.pack())) meet.add(m);
                Placed joint = joints.get(key(c[0], c[1]));
                if (joint == null && !meet.isEmpty()) {
                    joint = joint(l, f, c, d, meet, lines, i, primary, width);
                    if (joint == null && !collinear(l, d, meet, lines)) complete &= families(l.pack()) != null;
                    if (joint != null) joints.put(key(c[0], c[1]), joint);
                    if (joint != null && joints.size() > 0 && joint != null) placed.add(joint);
                } else if (meet.isEmpty()) {
                    // A free end: an end cap, if the family has one.
                    List<PieceSets.Group> caps = groups(l.pack(), f, PieceSets.Role.END);
                    if (!caps.isEmpty()) {
                        PieceSets.Piece cap = caps.get(0).pick(l.level(), i);
                        int run = cap.run(), turns = PieceLayout.turns(cap.sx(), cap.sz(), (d + 2) % 4);
                        List<int[]> bx = PieceLayout.boxes(c, d, 0, List.of(run), cap.across(), 0);
                        Placed p = place(l.pack(), cap.path(), turns, bx.get(0), PieceSets.Role.END);
                        if (p == null) complete = false;
                        else placed.add(p);
                        off[end] = run;
                    }
                }
                if (joint != null) {
                    int[] jb = joint.box();
                    off[end] = switch (d) {
                        case 0 -> jb[2] - c[0] + 1;
                        case 2 -> c[0] - jb[0] + 1;
                        case 1 -> jb[3] - c[1] + 1;
                        default -> c[1] - jb[1] + 1;
                    };
                    // Lateral alignment: shift the straights so their end face matches the joint's face profile.
                    Blueprint jbp = rotated(l.pack(), joint.path(), joint.turns()), sbp = rotated(l.pack(), primary.path(), PieceLayout.turns(primary.sx(), primary.sz(), dir));
                    if (end == 0 && jbp != null && sbp != null) {
                        int ref = dir % 2 == 0 ? c[1] : c[0];
                        int[] sbox = PieceLayout.boxes(a, dir, off[0], List.of(primary.run()), width, 0).get(0);
                        shift = PieceLayout.bestShift(face(jbp, jb, d, ref), face(sbp, sbox, (d + 2) % 4, ref), 3);
                    }
                }
            }
            // The run between the joints / caps: a gate in the middle when asked, straights around it.
            int n = len - off[0] - off[1];
            List<PieceSets.Group> gates = l.gate() ? groups(l.pack(), f, PieceSets.Role.GATE) : List.of();
            // Lengths of the straights as picked for this line (levels can change a piece's size), same width only.
            int[] lens = straights.stream().map(g -> g.pick(g.levels() ? l.level() : l.variant(), 0)).filter(p -> p.across() == width)
                    .mapToInt(PieceSets.Piece::run).distinct().toArray();
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
            shortened.add(Math.max(0, n - used));
            List<Integer> runs = new ArrayList<>();
            for (PieceSets.Piece p : tilePieces) runs.add(p.run());
            List<int[]> boxes = PieceLayout.boxes(a, dir, off[0], runs, width, shift);
            for (int k = 0; k < tilePieces.size(); k++) {
                PieceSets.Piece p = tilePieces.get(k);
                Placed pl = place(l.pack(), p.path(), PieceLayout.turns(p.sx(), p.sz(), dir), boxes.get(k), tiles.get(k) < 0 ? PieceSets.Role.GATE : PieceSets.Role.STRAIGHT);
                if (pl == null) complete = false;
                else placed.add(pl);
            }
        }
        return new Layout(out, shortened, complete);
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
            if (jbp == null || sbp0 == null) continue;
            int[] jb = PieceLayout.centred(c, jbp.getSizeX(), jbp.getSizeZ());
            double score = 0;
            for (int out : outs) {
                Blueprint sbp = rotated(l.pack(), straight.path(), PieceLayout.turns(straight.sx(), straight.sz(), out));
                if (sbp == null) continue;
                int ref = out % 2 == 0 ? c[1] : c[0];
                int[] sbox = PieceLayout.boxes(c, out, 1, List.of(straight.run()), width, 0).get(0);
                Set<Long> jf = face(jbp, jb, out, ref), sf = face(sbp, sbox, (out + 2) % 4, ref);
                score += PieceLayout.jaccard(jf, sf, PieceLayout.bestShift(jf, sf, 3));
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
        return new Placed(path, turns, new BlockPos(box[0] + o.getX(), y, box[1] + o.getZ()), box, role, blocks(pack, path));
    }

    private static long key(int x, int z) {
        return (long) x << 32 ^ (z & 0xFFFFFFFFL);
    }
}
