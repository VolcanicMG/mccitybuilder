package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Real road / wall joints laid out the way the planner lays them out (joint role by topology, the turn by face score,
 * each run's start and sideways shift by {@link PieceLayout#fit}), for every bundled family, and a world-space
 * continuity check on the placed blocks: the run's cross-section against the joint's, next to each other.
 */
final class JointCases {
    /** One laid-out joint: its piece (turned) and box, and per run leaving it: direction, straight (turned), fit, box. */
    record Run(int out, PieceLayout.Grid straight, PieceLayout.Fit fit, int[] box) {
    }

    /** @param fixed laid out by a built-in setting ({@link PieceLayout#jointFix}), not the search */
    record Case(String family, String name, String jointPath, PieceLayout.Grid joint, int[] jointBox, List<Run> runs, boolean butt, boolean fixed) {
        Case(String family, String name, String jointPath, PieceLayout.Grid joint, int[] jointBox, List<Run> runs, boolean butt) {
            this(family, name, jointPath, joint, jointBox, runs, butt, false);
        }

        Case(String family, String name, String jointPath, PieceLayout.Grid joint, int[] jointBox, List<Run> runs) {
            this(family, name, jointPath, joint, jointBox, runs, false);
        }

        /** @return the least matched run ({@link PieceLayout.Fit#match}) */
        double match() {
            double f = 1;
            for (Run r : runs) f = Math.min(f, r.fit().match());
            return f;
        }
    }

    /**
     * The world-space verdict: the least run match measured on the placed blocks, the best any turn / shift / depth
     * could reach, and why the pieces don't continue each other when they don't.
     */
    record Report(double match, double best, String reason) {
    }

    /**
     * The shapes: L turning left and right (both flips; the plain corner, then the inner corner when chosen), a T, a
     * cross, an L with the tower, a free end with a cap, and a T made the way the game makes one: a branch drawn onto
     * a wall's body ({@link LineSnap#crossing}, {@link Plan#split}).
     */
    static final Map<String, int[]> SHAPES = new LinkedHashMap<>();

    static {
        // Directions out of the joint: 0 east, 1 south, 2 west, 3 north. The first line arrives heading east.
        SHAPES.put("L-right", new int[]{2, 1});
        SHAPES.put("L-left", new int[]{2, 3});
        SHAPES.put("T", new int[]{2, 0, 1});
        SHAPES.put("cross", new int[]{2, 0, 1, 3});
        SHAPES.put("end", new int[]{2});
    }

    private static final Map<String, List<PieceSets.Piece>> PACKS = new TreeMap<>();

    private JointCases() {
    }

    /** @return every bundled pack's pieces by pack, read once */
    static Map<String, List<PieceSets.Piece>> packs() throws Exception {
        if (PACKS.isEmpty())
            for (String full : PackPieces.pieces()) {
                String pack = full.substring(0, full.indexOf('/')), path = full.substring(pack.length() + 1);
                PieceLayout.Grid g = PackPieces.grid(full);
                String[] sn = PieceSets.split(path.substring(path.lastIndexOf('/') + 1));
                PACKS.computeIfAbsent(pack, k -> new ArrayList<>()).add(new PieceSets.Piece(path, sn[0], Integer.parseInt(sn[1]), g.sx(), g.sy(), g.sz()));
            }
        return PACKS;
    }

    /** @return every case of every bundled road / wall family that has a straight and a joint or cap piece (top level) */
    static List<Case> all() throws Exception {
        List<Case> out = new ArrayList<>();
        for (var en : packs().entrySet())
            for (String famKey : PieceSets.classify(en.getValue()).keySet()) out.addAll(cases(en.getKey(), famKey));
        return out;
    }

    // The level / variant number the cases pick from each group (0: the highest).
    static int level;
    // Quarter turns the shapes are drawn turned (0: the first line arrives heading east); the T split stays as drawn.
    static int rot;
    /** Per case laid out by the search (no built-in), its layout as a built-in line: "pack name TAB key TAB value". */
    static final Map<Case, String> PINS = new java.util.IdentityHashMap<>();

    /** @return {@link #all} at every level / variant number any group has, each family name suffixed " L<n>" */
    static List<Case> allLevels() throws Exception {
        java.util.TreeSet<Integer> numbers = new java.util.TreeSet<>();
        for (var ps : packs().values()) for (PieceSets.Piece p : ps) numbers.add(p.number());
        List<Case> out = new ArrayList<>();
        try {
            for (int n : numbers) {
                level = n;
                for (Case cs : all()) out.add(new Case(cs.family() + " L" + n, cs.name(), cs.jointPath(), cs.joint(), cs.jointBox(), cs.runs(), cs.butt(), cs.fixed()));
            }
        } finally {
            level = 0;
        }
        return out;
    }

    /**
     * A saved plan's joints laid out as the planner would (real directions, lengths and end cells): every cell where two
     * or more segments of one family end, with the plan's joint type there. Named "x,z".
     *
     * @param text a plan file ({@link Plan#readLines}, {@link Plan#readJoints})
     */
    static List<Case> fromPlan(String text) throws Exception {
        List<Plan.Line> lines = Plan.readLines(text);
        Map<Long, Integer> types = Plan.readJoints(text);
        Map<String, String> folders = new HashMap<>();
        for (String f : packs().keySet()) folders.put(PackPieces.packName(f), f);
        Map<String, List<Plan.Line>> at = new TreeMap<>();
        for (Plan.Line l : lines) for (int[] e : l.ends()) at.computeIfAbsent(l.pack() + "|" + l.family() + "|" + e[0] + "," + e[1], k -> new ArrayList<>()).add(l);
        List<Case> out = new ArrayList<>();
        try {
            for (var en : at.entrySet()) {
                List<Plan.Line> ls = en.getValue();
                if (ls.size() < 2) continue;
                Plan.Line l0 = ls.get(0);
                String folder = folders.get(l0.pack()), xz = en.getKey().substring(en.getKey().lastIndexOf('|') + 1);
                PieceSets.Family fam = folder == null ? null : PieceSets.classify(packs().get(folder)).get(l0.family());
                if (fam == null || fam.straights().isEmpty()) continue;
                int[] c = {Integer.parseInt(xz.split(",")[0]), Integer.parseInt(xz.split(",")[1])};
                int[] outs = new int[ls.size()], dirs = new int[ls.size()];
                for (int q = 0; q < ls.size(); q++) {
                    Plan.Line l = ls.get(q);
                    dirs[q] = PieceLayout.dir(new int[]{l.x0(), l.z0()}, new int[]{l.x1(), l.z1()});
                    outs[q] = l.x0() == c[0] && l.z0() == c[1] ? dirs[q] : (dirs[q] + 2) % 4;
                }
                level = l0.level();
                PieceSets.Piece st = pickTop(fam.straights().get(0));
                boolean collinear = ls.size() == 2 && outs[0] % 2 == outs[1] % 2;
                add(out, folder, l0.family(), xz, fam, PieceLayout.jointRoles(types.getOrDefault(Plan.cell(c[0], c[1]), Plan.J_AUTO), ls.size(), collinear, l0.towers()),
                        PackPieces.grid(folder + "/" + st.path()), st, outs, dirs, l0.flip());
            }
        } finally {
            level = 0;
        }
        return out;
    }

    /**
     * @param pack the style pack; @param famKey a family key ("wall:walls")
     * @return every case the family has pieces for
     */
    static List<Case> cases(String pack, String famKey) throws Exception {
        PieceSets.Family fam = PieceSets.classify(packs().get(pack)).get(famKey);
        List<Case> out = new ArrayList<>();
        if (fam == null || fam.straights().isEmpty()) return out;
        PieceSets.Piece st = pickTop(fam.straights().get(0));
        PieceLayout.Grid sg = PackPieces.grid(pack + "/" + st.path());
        for (boolean flip : new boolean[]{false, true}) {
            for (var en : SHAPES.entrySet()) {
                int[] outs = en.getValue().clone();
                for (int q = 0; q < outs.length; q++) outs[q] = (outs[q] + rot) % 4;
                String name = en.getKey() + (flip ? "-flipped" : "");
                if (outs.length == 1) {
                    add(out, pack, famKey, name, fam, PieceLayout.jointRoles(Plan.J_AUTO, 1, false, false), sg, st, outs, dirsOf(outs), flip);
                    add(out, pack, famKey, name + "-tower", fam, List.of(PieceSets.Role.TOWER), sg, st, outs, dirsOf(outs), flip);
                    continue;
                }
                add(out, pack, famKey, name, fam, PieceLayout.jointRoles(Plan.J_AUTO, outs.length, false, false), sg, st, outs, dirsOf(outs), flip);
                // A T may take the cross piece (chosen per joint): three arms meet the runs, the fourth stays open.
                if (outs.length == 3) add(out, pack, famKey, name + "-cross", fam, List.of(PieceSets.Role.CROSS), sg, st, outs, dirsOf(outs), flip);
                if (outs.length == 2) {
                    add(out, pack, famKey, name + "-inner", fam, List.of(PieceSets.Role.INNER), sg, st, outs, dirsOf(outs), flip);
                    // Both walls leaving the joint (two chains started there) or both arriving: the straights turn differently.
                    int[] leave = outs.clone(), arrive = {(outs[0] + 2) % 4, (outs[1] + 2) % 4};
                    add(out, pack, famKey, name + "-leave", fam, PieceLayout.jointRoles(Plan.J_AUTO, 2, false, false), sg, st, outs, leave, flip);
                    add(out, pack, famKey, name + "-leave-inner", fam, List.of(PieceSets.Role.INNER), sg, st, outs, leave, flip);
                    add(out, pack, famKey, name + "-arrive", fam, PieceLayout.jointRoles(Plan.J_AUTO, 2, false, false), sg, st, outs, arrive, flip);
                    add(out, pack, famKey, name + "-arrive-inner", fam, List.of(PieceSets.Role.INNER), sg, st, outs, arrive, flip);
                    if (!flip) add(out, pack, famKey, en.getKey() + "-tower", fam, PieceLayout.jointRoles(Plan.J_TOWER, 2, false, false), sg, st, outs, dirsOf(outs), false);
                }
            }
            split(out, pack, famKey, fam, sg, st, flip);
        }
        return out;
    }

    /**
     * A T the way the game makes one: a wall along X is planned, a branch is drawn from the south heading north; the
     * end snaps onto the wall's body ({@link LineSnap#crossing}) and the click splits it there ({@link Plan#split}).
     * The joint cell then has three line ends; the layout's outs, straights and role come from those lines. With no T
     * piece (nor tower) it is a butt joint: the first half runs through the square, the other half and the branch
     * stop at its face ({@link PieceLayout#square}, {@link PieceLayout#owner}, {@link PieceLayout#offset}).
     */
    private static void split(List<Case> out, String pack, String famKey, PieceSets.Family fam, PieceLayout.Grid sg, PieceSets.Piece st, boolean flip) throws Exception {
        List<Plan.Line> lines = new ArrayList<>();
        lines.add(new Plan.Line("wall", -30, 0, 30, 0, pack, famKey, st.number(), -1, false, false, flip, Plan.FOLLOW, 0, Plan.NO_BASE, false));
        int[] start = {0, 30};
        int cells = LineSnap.crossing(start, 3, 30, List.<int[][]>of(lines.get(0).ends()), 3);
        int[] c = {start[0], start[1] - cells};
        int i = Plan.split(lines, c, l -> false);
        if (cells != 30 || c[0] != 0 || c[1] != 0 || i != 0 || lines.size() != 2) throw new AssertionError("T snap / split: " + cells + " " + i);
        lines.add(new Plan.Line("wall", start[0], start[1], c[0], c[1], pack, famKey, st.number(), -1, false, false, flip, Plan.FOLLOW, 0, Plan.NO_BASE, false));
        // The layout: each line's direction out of the joint cell (end 0: its direction; end 1: back) and its own travel.
        int[] outs = new int[lines.size()], dirs = new int[lines.size()];
        for (int q = 0; q < lines.size(); q++) {
            Plan.Line l = lines.get(q);
            dirs[q] = PieceLayout.dir(new int[]{l.x0(), l.z0()}, new int[]{l.x1(), l.z1()});
            outs[q] = l.x0() == c[0] && l.z0() == c[1] ? dirs[q] : (dirs[q] + 2) % 4;
        }
        String name = "T-split" + (flip ? "-flipped" : "");
        List<PieceSets.Role> roles = PieceLayout.jointRoles(Plan.J_AUTO, lines.size(), false, false);
        for (PieceSets.Role r : roles)
            if (!fam.of(r).isEmpty()) {
                add(out, pack, famKey, name, fam, List.of(r), sg, st, outs, dirs, flip);
                return;
            }
        // Butt joint: the owner's straight stands in for the joint piece, the others are the runs.
        int width = st.across(), run = st.run();
        int[] widths = new int[outs.length];
        java.util.Arrays.fill(widths, width);
        int[] sq = PieceLayout.square(c, outs, widths);
        int owner = PieceLayout.owner(outs);
        PieceLayout.Grid og = straight(sg, dirs[owner], flip);
        int[] ob = PieceLayout.boxes(c, outs[owner], PieceLayout.offset(c, outs[owner], sq, true), List.of(run), width, 0).get(0);
        List<Run> runs = new ArrayList<>();
        for (int q = 0; q < outs.length; q++) {
            if (q == owner) continue;
            PieceLayout.Grid s = straight(sg, dirs[q], flip);
            // The other half butts on the square; the branch meets the owner's body like a joint piece.
            PieceLayout.Fit f = outs[q] % 2 == outs[owner] % 2 ? new PieceLayout.Fit(PieceLayout.offset(c, outs[q], sq, false), 0, 0, 0)
                    : PieceLayout.fit(c, outs[q], ob, og, s, width, run, Math.max(3, width));
            int[] bx = PieceLayout.boxes(c, outs[q], f.off(), List.of(run), width, f.shift()).get(0);
            Run r = new Run(outs[q], s, f, bx);
            runs.add(new Run(outs[q], s, new PieceLayout.Fit(f.off(), f.shift(), measure(new Case(pack, name, st.path(), og, ob, List.of(r), true), r), f.depth()), bx));
        }
        out.add(new Case(pack + " " + famKey.substring(famKey.indexOf(':') + 1), name + "-butt", st.path(), og, ob, runs, true));
    }

    /** The group's piece at {@link #level} (0: its highest; a group without that number: its highest). */
    private static PieceSets.Piece pickTop(PieceSets.Group g) {
        if (level > 0) for (PieceSets.Piece p : g.pieces()) if (p.number() == level) return p;
        return g.pieces().get(g.pieces().size() - 1);
    }

    /** @param dirs each run's travel direction as the game places its straight; @param roles the joint's roles, first present used */
    private static void add(List<Case> out, String pack, String famKey, String name, PieceSets.Family fam, List<PieceSets.Role> roles,
                            PieceLayout.Grid sg, PieceSets.Piece st, int[] outs, int[] dirs, boolean flip) throws Exception {
        PieceSets.Role role = null;
        for (PieceSets.Role r : roles) if (!fam.of(r).isEmpty()) {
            role = r;
            break;
        }
        if (role == null) return;
        PieceSets.Piece jp = pickTop(fam.of(role).get(0));
        PieceLayout.Grid jg = PackPieces.grid(pack + "/" + jp.path());
        int[] c = {0, 0};
        int width = st.across(), run = st.run();
        // The configuration's frame (joints only; a cap stays along its line): laid out there, turned back.
        int[] sts = new int[outs.length];
        String[] sps = new String[outs.length];
        for (int q = 0; q < outs.length; q++) {
            sts[q] = Math.floorMod(PieceLayout.turns(sg.sx(), sg.sz(), dirs[q]) + (flip ? 2 : 0), 4);
            sps[q] = st.path();
        }
        PieceLayout.Grid s2 = sg.turned(2);
        boolean[] sym = new boolean[outs.length];
        java.util.Arrays.fill(sym, s2.sx() == sg.sx() && java.util.Arrays.equals(s2.ids(), sg.ids()));
        int rot = role == PieceSets.Role.END ? 0 : PieceLayout.frameRot(outs, kinds(outs, sts), PieceLayout.jointKey(jp.path(), outs, sps, sts, sym).rot());
        int[] oc = new int[outs.length], dc = new int[outs.length];
        for (int q = 0; q < outs.length; q++) {
            oc[q] = Math.floorMod(outs[q] - rot, 4);
            dc[q] = Math.floorMod(dirs[q] - rot, 4);
        }
        // The turn whose faces best continue the straights, as the planner picks it: any for a joint, along the line for a cap.
        int best = 0, from = 0, step = 1;
        if (role == PieceSets.Role.END) {
            from = PieceLayout.turns(jp.sx(), jp.sz(), (outs[0] + 2) % 4);
            step = 2;
        }
        List<Integer> ks = new ArrayList<>(), outList = new ArrayList<>();
        for (int o : oc) outList.add(o);
        double[] contact = new double[4 / step], shape = new double[4 / step], bestMatch = new double[4 / step];
        for (int k = from; k < from + 4; k += step) {
            PieceLayout.Grid t = jg.turned(k);
            double sc = 0, bm = 1;
            for (int q = 0; q < outs.length; q++) {
                int[] tb = box(c, oc[0], t, role, jp);
                sc += PieceLayout.faceScore(c, oc[q], tb, t, straight(sg, dc[q], flip), width, run);
                bm = Math.min(bm, PieceLayout.fit(c, oc[q], tb, t, straight(sg, dc[q], flip), width, run, Math.max(3, width)).match());
            }
            // The floor judges wall corners only (towers, T's, crosses and roads keep their contact order).
            bestMatch[ks.size()] = outs.length == 2 && famKey.startsWith("wall:") && (role == PieceSets.Role.CORNER || role == PieceSets.Role.INNER) ? bm : 1;
            double[] ac = PieceLayout.armClosed(t, outList, sg, flip);
            contact[ks.size()] = sc;
            shape[ks.size()] = ac[0] - ac[1];
            ks.add(k);
        }
        best = ks.get(PieceLayout.pickTurn(contact, bestMatch, shape));
        PieceLayout.JointFix fix = null;
        if (role != PieceSets.Role.END) {
            // The mod's built-in setting for this configuration, as the planner reads it.
            fix = PieceLayout.jointFix(PieceSets.builtIn(PackPieces.packName(pack))::get, k -> false, jp.path(), outs, sps, sts, sym);
            if (fix != null) best = Math.floorMod(fix.turn() - rot, 4);
        }
        PieceLayout.Grid tc = jg.turned(best);
        int[] jbc = box(c, oc[0], tc, role, jp);
        PieceLayout.Grid t = jg.turned(best + rot);
        int[] jb = PieceLayout.rotBox(c, jbc, rot);
        List<Run> runs = new ArrayList<>();
        for (int q = 0; q < outs.length; q++) {
            int o = outs[q];
            PieceLayout.Grid s = straight(sg, dirs[q], flip);
            PieceLayout.Fit fc = PieceLayout.fit(c, oc[q], jbc, tc, straight(sg, dc[q], flip), width, run, Math.max(3, width), fix == null ? null : fix.runs()[q]);
            PieceLayout.Fit f = PieceLayout.fitBack(c, oc[q], fc, width, run, rot);
            runs.add(new Run(o, s, f, PieceLayout.boxes(c, o, f.off(), List.of(run), width, f.shift()).get(0)));
        }
        best = Math.floorMod(best + rot, 4);
        Case cs = new Case(pack + " " + famKey.substring(famKey.indexOf(':') + 1), name, jp.path(), t, jb, runs, false, fix != null);
        if (fix == null && role != PieceSets.Role.END) PINS.put(cs, pin(pack, jp.path(), t, best, jb, runs, sg, st, dirs, flip));
        out.add(cs);
    }

    /** @return a case's layout as a built-in line ({@link PieceLayout#jointFix}) */
    private static String pin(String pack, String jointPath, PieceLayout.Grid t, int turn, int[] jb, List<Run> runs, PieceLayout.Grid sg, PieceSets.Piece st, int[] dirs,
                              boolean flip) {
        int n = runs.size();
        int[] outs = new int[n], sts = new int[n];
        String[] sps = new String[n];
        boolean[] sym = new boolean[n];
        PieceLayout.Grid s2 = sg.turned(2);
        for (int q = 0; q < n; q++) {
            outs[q] = runs.get(q).out();
            sts[q] = Math.floorMod(PieceLayout.turns(sg.sx(), sg.sz(), dirs[q]) + (flip ? 2 : 0), 4);
            sps[q] = st.path();
            sym[q] = s2.sx() == sg.sx() && java.util.Arrays.equals(s2.ids(), sg.ids());
        }
        var k = PieceLayout.jointKey(jointPath, outs, sps, sts, sym);
        StringBuilder v = new StringBuilder("" + Math.floorMod(turn - k.rot(), 4));
        for (int q = 0; q < n; q++)
            v.append(' ').append(Math.floorMod(outs[q] - k.rot(), 4)).append(':')
                    .append(PieceLayout.alignShift(new int[]{0, 0}, outs[q], jb, st.across(), runs.get(q).fit().shift())).append(',').append(runs.get(q).fit().depth());
        return PackPieces.packName(pack) + "\t" + k.key() + "\t" + v;
    }

    /** @return per arm, its straight's turns less its direction out ({@link PieceLayout#frameRot}) */
    static int[] kinds(int[] outs, int[] sts) {
        int[] k = new int[outs.length];
        for (int q = 0; q < k.length; q++) k[q] = Math.floorMod(sts[q] - outs[q], 4);
        return k;
    }

    /** @return the joint's box: centred on the joint cell; a cap lies along the line from the free end (shift 0) */
    private static int[] box(int[] c, int d, PieceLayout.Grid t, PieceSets.Role role, PieceSets.Piece p) {
        return role == PieceSets.Role.END ? PieceLayout.boxes(c, d, 0, List.of(p.run()), p.across(), 0).get(0) : PieceLayout.centred(c, t.sx(), t.sz());
    }

    /** @return the direction each line runs in: the first arrives at the joint (heading against its out), the rest leave */
    static int[] dirsOf(int[] outs) {
        int[] d = outs.clone();
        d[0] = (outs[0] + 2) % 4;
        return d;
    }

    /** @return the straight as the planner places it on a line running in direction {@code dir} */
    static PieceLayout.Grid straight(PieceLayout.Grid sg, int dir, boolean flip) {
        return sg.turned(PieceLayout.turns(sg.sx(), sg.sz(), dir) + (flip ? 2 : 0));
    }

    // ---- world space: the placed blocks ----

    /** Solid cells of a placed piece as {@code h << 16 | lat} at one cell along a run direction (world coordinates). */
    static Set<Integer> section(PieceLayout.Grid g, int ox, int oz, int d, int along) {
        Set<Integer> out = new HashSet<>();
        for (int y = 0; y < g.sy(); y++) {
            int h = y - g.ground();
            if (h < g.hidden()) continue;
            for (int x = 0; x < g.sx(); x++)
                for (int z = 0; z < g.sz(); z++) {
                    int wx = ox + x, wz = oz + z;
                    if ((d % 2 == 0 ? wx : wz) != along || g.id(x, y, z) <= 0) continue;
                    out.add(h << 16 | ((d % 2 == 0 ? wz : wx) + 1000));
                }
        }
        return out;
    }

    /** @return the run's body face cell along its direction (its end toward the joint, trim skipped) */
    private static int face(Run r) {
        int si = r.straight().inset((r.out() + 2) % 4);
        return switch (r.out()) {
            case 0 -> r.box()[0] + si;
            case 2 -> r.box()[2] - si;
            case 1 -> r.box()[1] + si;
            default -> r.box()[3] - si;
        };
    }

    /** @return the cell one step from {@code along} toward the joint cell, for runs leaving in {@code d} */
    private static int inward(int d, int along) {
        return d == 0 || d == 1 ? along - 1 : along + 1;
    }

    /** @return the run's cross-section at its face against the joint's at the cell next to it, on the placed blocks */
    static double measure(Case cs, Run r) {
        return measure(cs, r, 0, Integer.MAX_VALUE);
    }

    /**
     * @return {@link #measure(Case, Run)} over the rows at heights hLo..hHi only; 0 unless the joint holds blocks in
     * front of at least half the run's cells (a few stray blocks touching aren't a joint)
     */
    static double measure(Case cs, Run r, int hLo, int hHi) {
        int d = r.out(), f = face(r);
        Set<Integer> a = section(r.straight(), r.box()[0], r.box()[1], d, f), b = section(cs.joint(), cs.jointBox()[0], cs.jointBox()[1], d, inward(d, f));
        int hit = 0;
        for (int v : a) if (b.contains(v)) hit++;
        if (hit * 2 < a.size()) return 0;
        a.removeIf(v -> (v >> 16) < hLo || (v >> 16) > hHi);
        b.removeIf(v -> (v >> 16) < hLo || (v >> 16) > hHi);
        // A butt joint's run meets the other wall's side: continuous when every face cell has a block in front.
        if (cs.butt()) {
            hit = 0;
            for (int v : a) if (b.contains(v)) hit++;
            return a.isEmpty() ? 1 : (double) hit / a.size();
        }
        return jaccard(a, b);
    }

    private static double jaccard(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int inter = 0;
        for (int v : a) if (b.contains(v)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    /**
     * The verdict on the placed blocks, with the best any shift (within ±max(3, width)) could reach for the turn the
     * layout chose, the run meeting the joint at the first layer that holds blocks in front of at least half its
     * visible end cells (unless that overlap is destructive in either build order: then it stops at the box), scored
     * the same way on the placed blocks. The turn itself is the layout's policy (every run's face score summed).
     */
    static Report report(Case cs) {
        double match = 1;
        StringBuilder why = new StringBuilder();
        for (Run r : cs.runs()) {
            double m = measure(cs, r);
            match = Math.min(match, m);
            if (m < 1 - 1e-9) why.append(why.length() == 0 ? "" : "; ").append("ESWN".charAt(r.out())).append(": ").append(reason(cs, r));
        }
        int[] c = {0, 0};
        int width = Math.min(cs.runs().get(0).straight().sx(), cs.runs().get(0).straight().sz()), run = Math.max(cs.runs().get(0).straight().sx(), cs.runs().get(0).straight().sz());
        PieceLayout.Grid t = cs.joint();
        int[] jb = cs.jointBox();
        double best = 1;
        // A butt joint's runs stay on their line (nothing to shift against); only joint pieces are brute-forced.
        if (cs.butt()) best = match;
        for (Run r : cs.butt() ? List.<Run>of() : cs.runs()) {
            int d = r.out(), si = r.straight().inset((d + 2) % 4), edge = PieceLayout.reach(c, d, jb), layers = d % 2 == 0 ? t.sx() : t.sz();
            double bm = 0;
            for (int s = -Math.max(3, width); s <= Math.max(3, width); s++) {
                int[] bx = PieceLayout.boxes(c, d, edge, List.of(run), width, s).get(0);
                Run q = new Run(d, r.straight(), null, bx);
                Set<Integer> a = section(r.straight(), bx[0], bx[1], d, face(q));
                for (int i = 0; i < layers && !a.isEmpty(); i++) {
                    int cell = d == 0 || d == 1 ? edge - 1 - i : -(edge - 1 - i), hit = 0;
                    for (int v : section(t, jb[0], jb[1], d, cell)) if (a.contains(v)) hit++;
                    if (hit * 2 < a.size()) continue;
                    int[] bi = PieceLayout.boxes(c, d, edge - i - si, List.of(run), width, s).get(0);
                    if (i + si == 0 || !PieceLayout.destructive(t, jb[0], -t.ground(), jb[1], r.straight(), bi[0], -r.straight().ground(), bi[1])) q = new Run(d, r.straight(), null, bi);
                    break;
                }
                bm = Math.max(bm, measure(new Case(cs.family(), cs.name(), cs.jointPath(), t, jb, List.of(q), cs.butt()), q));
            }
            best = Math.min(best, bm);
        }
        return new Report(match, best, why.toString());
    }

    /** @return the rows (heights) where the run's face and the joint's next layer differ, as lateral ranges */
    private static String reason(Case cs, Run r) {
        int d = r.out(), f = face(r);
        Set<Integer> a = section(r.straight(), r.box()[0], r.box()[1], d, f), b = section(cs.joint(), cs.jointBox()[0], cs.jointBox()[1], d, inward(d, f));
        int hit = 0;
        for (int v : a) if (b.contains(v)) hit++;
        if (hit * 2 < a.size()) return "gap (" + (b.isEmpty() ? "nothing" : "under half the run's cells meet a block") + " next to the run)";
        Map<Integer, List<Integer>> ra = new TreeMap<>(), rb = new TreeMap<>();
        for (int v : a) ra.computeIfAbsent(v >> 16, k -> new ArrayList<>()).add((v & 0xFFFF) - 1000);
        for (int v : b) rb.computeIfAbsent(v >> 16, k -> new ArrayList<>()).add((v & 0xFFFF) - 1000);
        Set<Integer> hs = new java.util.TreeSet<>(ra.keySet());
        hs.addAll(rb.keySet());
        List<String> rows = new ArrayList<>();
        for (int h : hs) {
            List<Integer> x = ra.getOrDefault(h, List.of()), y = rb.getOrDefault(h, List.of());
            if (!new HashSet<>(x).equals(new HashSet<>(y))) rows.add("h" + h + " run " + span(x) + " vs joint " + span(y));
        }
        return rows.size() > 3 ? rows.subList(0, 3) + " +" + (rows.size() - 3) + " rows" : rows.toString();
    }

    private static String span(List<Integer> l) {
        if (l.isEmpty()) return "-";
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int v : l) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return l.size() == hi - lo + 1 ? "[" + lo + ".." + hi + "]" : l.size() + "@[" + lo + ".." + hi + "]";
    }
}
