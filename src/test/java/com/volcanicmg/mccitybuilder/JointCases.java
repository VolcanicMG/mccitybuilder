package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.PieceLayout;
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

    record Case(String family, String name, String jointPath, PieceLayout.Grid joint, int[] jointBox, List<Run> runs) {
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

    /** The shapes: L turning left and right (both flips), a T, a cross, an L with the tower, and a free end with a cap. */
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

    /** @return every case of every bundled road / wall family that has a straight and a joint or cap piece */
    static List<Case> all() throws Exception {
        List<Case> out = new ArrayList<>();
        for (var en : packs().entrySet())
            for (String famKey : PieceSets.classify(en.getValue()).keySet()) out.addAll(cases(en.getKey(), famKey));
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
        for (boolean flip : new boolean[]{false, true})
            for (var en : SHAPES.entrySet()) {
                int[] outs = en.getValue();
                String name = en.getKey() + (flip ? "-flipped" : "");
                if (outs.length == 1) {
                    add(out, pack, famKey, name, fam, PieceSets.Role.END, sg, st, outs, flip);
                    continue;
                }
                int auto = PieceLayout.autoRole(outs.length, false, outs[0], outs[1], flip);
                PieceSets.Role role = auto == PieceLayout.CROSS ? PieceSets.Role.CROSS : auto == PieceLayout.TEE ? PieceSets.Role.TEE
                        : auto == PieceLayout.INNER ? PieceSets.Role.INNER : PieceSets.Role.CORNER;
                // A missing role falls back to the corner, as the layout does.
                if (fam.of(role).isEmpty()) role = PieceSets.Role.CORNER;
                add(out, pack, famKey, name, fam, role, sg, st, outs, flip);
                if (outs.length == 2 && !flip) add(out, pack, famKey, en.getKey() + "-tower", fam, PieceSets.Role.TOWER, sg, st, outs, false);
            }
        return out;
    }

    /** The highest level of a group (the pieces in the screenshots are level 5). */
    private static PieceSets.Piece pickTop(PieceSets.Group g) {
        return g.pieces().get(g.pieces().size() - 1);
    }

    private static void add(List<Case> out, String pack, String famKey, String name, PieceSets.Family fam, PieceSets.Role role,
                            PieceLayout.Grid sg, PieceSets.Piece st, int[] outs, boolean flip) throws Exception {
        if (fam.of(role).isEmpty()) return;
        PieceSets.Piece jp = pickTop(fam.of(role).get(0));
        PieceLayout.Grid jg = PackPieces.grid(pack + "/" + jp.path());
        int[] c = {0, 0};
        int width = st.across(), run = st.run();
        // The turn whose faces best continue the straights, as the planner picks it: any for a joint, along the line for a cap.
        int best = 0, from = 0, step = 1;
        if (role == PieceSets.Role.END) {
            from = PieceLayout.turns(jp.sx(), jp.sz(), (outs[0] + 2) % 4);
            step = 2;
        }
        double bs = -1;
        for (int k = from; k < from + 4; k += step) {
            PieceLayout.Grid t = jg.turned(k);
            double sc = 0;
            for (int q = 0; q < outs.length; q++)
                sc += PieceLayout.faceScore(c, outs[q], box(c, outs[0], t, role, jp), t, straight(sg, dirOf(outs, q), flip), width, run);
            if (sc > bs + 1e-9) {
                bs = sc;
                best = k;
            }
        }
        PieceLayout.Grid t = jg.turned(best);
        int[] jb = box(c, outs[0], t, role, jp);
        List<Run> runs = new ArrayList<>();
        for (int q = 0; q < outs.length; q++) {
            int o = outs[q];
            PieceLayout.Grid s = straight(sg, dirOf(outs, q), flip);
            PieceLayout.Fit f = PieceLayout.fit(c, o, jb, t, s, width, run, Math.max(3, width));
            runs.add(new Run(o, s, f, PieceLayout.boxes(c, o, f.off(), List.of(run), width, f.shift()).get(0)));
        }
        out.add(new Case(pack + " " + famKey.substring(famKey.indexOf(':') + 1), name, jp.path(), t, jb, runs));
    }

    /** @return the joint's box: centred on the joint cell; a cap lies along the line from the free end (shift 0) */
    private static int[] box(int[] c, int d, PieceLayout.Grid t, PieceSets.Role role, PieceSets.Piece p) {
        return role == PieceSets.Role.END ? PieceLayout.boxes(c, d, 0, List.of(p.run()), p.across(), 0).get(0) : PieceLayout.centred(c, t.sx(), t.sz());
    }

    /** @return the direction line q runs in: the first arrives at the joint (heading against its out), the rest leave */
    static int dirOf(int[] outs, int q) {
        return q == 0 ? (outs[0] + 2) % 4 : outs[q];
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
            if (h < 0) continue;
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
        for (Run r : cs.runs()) {
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
                bm = Math.max(bm, measure(new Case(cs.family(), cs.name(), cs.jointPath(), t, jb, List.of(q)), q));
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
