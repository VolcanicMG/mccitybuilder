package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tiling style-pack pieces along a drawn segment. Directions: 0 = +X (east), 1 = +Z (south), 2 = -X, 3 = -Z; a
 * Structurize clockwise quarter turn takes one direction to the next. Pure.
 */
public final class PieceLayout {
    /** A piece on the ground: which one (index into the caller's list), world box (inclusive, x / z) and quarter turns. */
    public record Place(int piece, int x0, int z0, int x1, int z1, int turns) {
        public int cx() {
            return (x0 + x1) / 2;
        }

        public int cz() {
            return (z0 + z1) / 2;
        }
    }

    private PieceLayout() {
    }

    /** @return the direction from a to b (axis-aligned segments; the dominant axis otherwise) */
    public static int dir(int[] a, int[] b) {
        int dx = b[0] - a[0], dz = b[1] - a[1];
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? 0 : 2;
        return dz >= 0 ? 1 : 3;
    }

    /** @return quarter turns that bring a piece whose run is along its X (or Z) side to point in {@code dir} */
    public static int turns(int sx, int sz, int dir) {
        int nativeDir = sx >= sz ? 0 : 1;
        return Math.floorMod(dir - nativeDir, 4);
    }

    /**
     * Lengths that cover {@code n} cells best: the least left over, then the fewest pieces (coin change).
     *
     * @param lens the available piece lengths (any order)
     * @return the lengths used, longest first; the cells left over are n minus their sum
     */
    public static List<Integer> fill(int n, int[] lens) {
        if (n <= 0 || lens.length == 0) return List.of();
        int[] count = new int[n + 1], from = new int[n + 1];
        java.util.Arrays.fill(count, Integer.MAX_VALUE);
        count[0] = 0;
        for (int i = 1; i <= n; i++)
            for (int l : lens)
                if (l > 0 && l <= i && count[i - l] != Integer.MAX_VALUE && count[i - l] + 1 < count[i]) {
                    count[i] = count[i - l] + 1;
                    from[i] = l;
                }
        int best = n;
        while (best > 0 && count[best] == Integer.MAX_VALUE) best--;
        List<Integer> out = new ArrayList<>();
        for (int i = best; i > 0; i -= from[i]) out.add(from[i]);
        out.sort((x, y) -> y - x);
        return out;
    }

    /**
     * The boxes of pieces of the given lengths laid end to end from cell {@code start} along {@code dir}, centred on
     * the line across it.
     *
     * @param a      the segment's start cell {x, z}
     * @param offset cells along the run before the first piece
     * @param width  piece width across the run
     * @param shift  lateral shift (along +Z for east / west runs, +X for north / south runs)
     */
    public static List<int[]> boxes(int[] a, int dir, int offset, List<Integer> lens, int width, int shift) {
        List<int[]> out = new ArrayList<>();
        int s = offset, lo = -(width - 1) / 2 + shift, hi = width / 2 + shift;
        for (int len : lens) {
            int r0 = s, r1 = s + len - 1;
            out.add(switch (dir) {
                case 0 -> new int[]{a[0] + r0, a[1] + lo, a[0] + r1, a[1] + hi};
                case 2 -> new int[]{a[0] - r1, a[1] + lo, a[0] - r0, a[1] + hi};
                case 1 -> new int[]{a[0] + lo, a[1] + r0, a[0] + hi, a[1] + r1};
                default -> new int[]{a[0] + lo, a[1] - r1, a[0] + hi, a[1] - r0};
            });
            s += len;
        }
        return out;
    }

    /** @return the box of a joint piece (sx by sz after turning) centred on cell {@code c} */
    public static int[] centred(int[] c, int sx, int sz) {
        return new int[]{c[0] - (sx - 1) / 2, c[1] - (sz - 1) / 2, c[0] + sx / 2, c[1] + sz / 2};
    }

    /** @return |A ∩ shifted B| / |A ∪ shifted B| (1 when both are empty) */
    public static double jaccard(Set<Long> a, Set<Long> b, int shift) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int inter = 0;
        for (long v : b) if (a.contains(v + shift)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    // ---- joints: who covers the joint square, where each run starts ----

    /** @return the cells of {@code box} from {@code c} going in direction {@code d}, c included (0 when c is past it) */
    public static int reach(int[] c, int d, int[] box) {
        return Math.max(0, switch (d) {
            case 0 -> box[2] - c[0] + 1;
            case 2 -> c[0] - box[0] + 1;
            case 1 -> box[3] - c[1] + 1;
            default -> c[1] - box[1] + 1;
        });
    }

    /**
     * The square a butt joint (no joint piece) must cover once: each line's band across its run, where the lines
     * meet. East / west runs give the Z extent, north / south runs the X extent; along an axis no line crosses, the
     * joint cell alone.
     *
     * @param dirs   each meeting line's direction out of {@code c}
     * @param widths each line's piece width
     * @return {x0, z0, x1, z1}
     */
    public static int[] square(int[] c, int[] dirs, int[] widths) {
        int x0 = c[0], x1 = c[0], z0 = c[1], z1 = c[1];
        for (int k = 0; k < dirs.length; k++) {
            int lo = -(widths[k] - 1) / 2, hi = widths[k] / 2;
            if (dirs[k] % 2 == 0) {
                z0 = Math.min(z0, c[1] + lo);
                z1 = Math.max(z1, c[1] + hi);
            } else {
                x0 = Math.min(x0, c[0] + lo);
                x1 = Math.max(x1, c[0] + hi);
            }
        }
        return new int[]{x0, z0, x1, z1};
    }

    /** @return the line (index into {@code dirs}) that runs through a butt joint: the first with a line straight opposite, else the first */
    public static int owner(int[] dirs) {
        for (int k = 0; k < dirs.length; k++)
            for (int d : dirs) if (d == (dirs[k] + 2) % 4) return k;
        return 0;
    }

    /**
     * Where a run starts at a joint end, in cells from the joint cell along {@code d} (into the line): past the joint
     * piece's (or butt square's) far face, or for the butt joint's owner, back across the square so it covers it once.
     */
    public static int offset(int[] c, int d, int[] box, boolean owner) {
        return owner ? 1 - reach(c, (d + 2) % 4, box) : reach(c, d, box);
    }

    /** @return the segment length (cells) a piece needs: the joint / cap offsets plus the shortest straight; MAX_VALUE without one */
    public static int need(int off0, int off1, int[] lens) {
        int min = Integer.MAX_VALUE;
        for (int l : lens) if (l > 0) min = Math.min(min, l);
        return min == Integer.MAX_VALUE ? min : Math.max(1, off0 + off1 + min);
    }

    /**
     * @param left cells no piece covers
     * @return where the first piece starts (cells from the segment's start): uncovered cells go to a free end, so a
     * run always meets its joint
     */
    public static int runStart(int off0, int left, boolean free0, boolean free1) {
        return off0 + (free0 && !free1 ? left : 0);
    }

    // ---- piece shapes: where a run meets a joint piece's real face ----

    /**
     * A piece's blocks, as Structurize stores them: {@code ids[(y * sz + z) * sx + x]}: a block id above 0,
     * {@link #AIR} (pasting clears the world there) or 0 = substitution (pasting leaves the world as it is).
     *
     * @param ground the local y level that sits at the ground (so pieces with different foundations compare by height
     *               above ground)
     */
    public record Grid(int sx, int sy, int sz, int[] ids, int ground) {
        public static final int AIR = -1;

        public int id(int x, int y, int z) {
            return ids[(y * sz + z) * sx + x];
        }

        /** @return this piece turned {@code k} quarter turns clockwise, like Structurize ((x, z) to (sz-1-z, x)) */
        public Grid turned(int k) {
            Grid g = this;
            for (int t = 0; t < Math.floorMod(k, 4); t++) {
                int nsx = g.sz, nsz = g.sx;
                int[] o = new int[g.ids.length];
                for (int y = 0; y < g.sy; y++)
                    for (int z = 0; z < g.sz; z++)
                        for (int x = 0; x < g.sx; x++) o[(y * nsz + x) * nsx + (g.sz - 1 - z)] = g.id(x, y, z);
                g = new Grid(nsx, g.sy, nsz, o, g.ground);
            }
            return g;
        }

        /** @return solid cells in slice {@code i} from the given face inward (0 = the face's own layer) */
        int slice(int face, int i) {
            int n = 0;
            for (int y = 0; y < sy; y++)
                if (face % 2 == 0) {
                    int x = face == 0 ? sx - 1 - i : i;
                    for (int z = 0; z < sz; z++) if (id(x, y, z) > 0) n++;
                } else {
                    int z = face == 1 ? sz - 1 - i : i;
                    for (int x = 0; x < sx; x++) if (id(x, y, z) > 0) n++;
                }
            return n;
        }

        /**
         * @return how many layers from {@code face} inward are only trim (fewer than half the blocks of the fullest
         * layer: an overhanging parapet, an empty margin); the body starts after them
         */
        public int inset(int face) {
            int n = face % 2 == 0 ? sx : sz, max = 0;
            for (int i = 0; i < n; i++) max = Math.max(max, slice(face, i));
            for (int i = 0; i < n; i++) if (slice(face, i) * 2 >= max && max > 0) return i;
            return 0;
        }

        /**
         * @param base the world lateral coordinate of local 0 minus the reference
         * @return the body's face (layer {@code inset} from {@code face}) as profile cells: height above ground and
         * lateral position, plus the block id when {@code withIds}
         */
        public Set<Long> profile(int face, int inset, int base, boolean withIds) {
            Set<Long> out = new java.util.HashSet<>();
            for (int y = 0; y < sy; y++)
                if (face % 2 == 0) {
                    int x = face == 0 ? sx - 1 - inset : inset;
                    for (int z = 0; z < sz; z++) add(out, id(x, y, z), y, base + z, withIds);
                } else {
                    int z = face == 1 ? sz - 1 - inset : inset;
                    for (int x = 0; x < sx; x++) add(out, id(x, y, z), y, base + x, withIds);
                }
            return out;
        }

        private void add(Set<Long> out, int id, int y, int lateral, boolean withIds) {
            if (id <= 0) return;
            long v = (long) (y - ground + 1000) * 65536 + lateral + 30000;
            out.add(withIds ? v + ((long) (id & 0x7FFF) << 32) : v);
        }
    }

    /**
     * How a run meets a joint piece's face: where it starts, its sideways shift, and how well it continues the piece.
     *
     * @param off   cells from the joint cell to the run's first cell; @param shift its lateral shift
     * @param match how well the run's visible end face continues the joint layer it meets (Jaccard over the cells
     *              above ground, 1 = the same cross-section: no air gap, no sideways step); @param depth that layer
     *              (from the joint's face inward; -1 when the run stops at the box with nothing to meet)
     */
    public record Fit(int off, int shift, double match, int depth) {
    }

    /** Cells of a profile from the ground layer up (height 0: a road's surface sits there): what shows once the piece stands. */
    public static Set<Long> visible(Set<Long> profile) {
        Set<Long> out = new java.util.HashSet<>();
        for (long v : profile) if (((v >> 16) & 0xFFFF) - 1000 >= 0) out.add(v);
        return out;
    }

    /**
     * Brute force over the sideways shift (within ±maxShift): the run's end face (the straight's solid face toward the
     * joint, trim skipped) meets the joint at the first layer from its face inward holding blocks in front of at least
     * half its visible cells ({@link #visible}: foundations are buried, so they never decide), scored by how well the
     * two visible cross-sections match (Jaccard: 1 = the same shape, no air gap, no sideways step). A contact whose
     * overlap would be destructive in either build order, or no contact at all (a tower whose ring is air), stops the
     * run at the joint's box with match 0 (a few stray blocks touching aren't a joint). Best match first, then the shallowest contact, then the run centred on the
     * joint's fullest visible layer, then the smallest shift.
     *
     * @param jb       the joint's world box; @param joint the joint piece as placed (turned)
     * @param straight the run's straight as placed (turned for the run's direction); its face toward the joint is (d+2)%4
     * @param d        the direction from the joint cell into the run
     */
    public static Fit fit(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        // The result depends only on the pieces and the joint box relative to the joint cell, never on where it is.
        FitKey k = new FitKey(joint, straight, d, width, run, maxShift, jb[0] - c[0], jb[1] - c[1], jb[2] - c[0], jb[3] - c[1]);
        Fit f = FITS.get(k);
        if (f == null) {
            if (FITS.size() > 50_000) FITS.clear();
            f = fitNow(c, d, jb, joint, straight, width, run, maxShift);
            FITS.put(k, f);
        }
        return f;
    }

    /** {@link #fit} without the cache (the self-check compares the two). */
    public static Fit fitNow(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        int si = straight.inset((d + 2) % 4), edge = reach(c, d, jb), ref = d % 2 == 0 ? c[1] : c[0];
        int[] sbox0 = boxes(c, d, edge, List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox0[1] : sbox0[0]) - ref;
        Set<Long> S = visible(straight.profile((d + 2) % 4, si, sa, false));
        int layers = d % 2 == 0 ? joint.sx() : joint.sz();
        List<Set<Long>> J = new ArrayList<>();
        Set<Long> fullest = Set.of();
        for (int i = 0; i < layers; i++) {
            J.add(visible(joint.profile(d, i, ja, false)));
            if (J.get(i).size() > fullest.size()) fullest = J.get(i);
        }
        double cj = centre(fullest), cs = S.isEmpty() ? cj : centre(S);
        Fit best = null;
        double bestOff = 0;
        for (int s = -maxShift; s <= maxShift; s++) {
            double off = Math.abs(cj - cs - s);
            Fit f = new Fit(edge, s, 0, -1);
            for (int i = 0; i < layers && !S.isEmpty(); i++) {
                int hit = 0;
                for (long v : S) if (J.get(i).contains(v + s)) hit++;
                if (hit * 2 < S.size()) continue;
                int[] bx = boxes(c, d, edge - i - si, List.of(run), width, s).get(0);
                if (i + si == 0 || !destructive(joint, jb[0], -joint.ground(), jb[1], straight, bx[0], -straight.ground(), bx[1]))
                    f = new Fit(edge - i - si, s, jaccard(J.get(i), S, s), i);
                break;
            }
            if (better(f, off, best, bestOff)) {
                best = f;
                bestOff = off;
            }
        }
        return best;
    }

    /** @return true when f beats best: more match, then shallower, then more centred ({@code off}), then a smaller shift */
    private static boolean better(Fit f, double off, Fit best, double bestOff) {
        if (best == null || f.match() > best.match() + 1e-9) return true;
        if (f.match() < best.match() - 1e-9 || f.depth() > best.depth()) return false;
        if (f.depth() < best.depth() || off < bestOff - 1e-9) return true;
        return off < bestOff + 1e-9 && Math.abs(f.shift()) < Math.abs(best.shift());
    }

    /** @return the mean lateral coordinate of a profile (its low 16 bits) */
    private static double centre(Set<Long> p) {
        if (p.isEmpty()) return 0;
        double sum = 0;
        for (long v : p) sum += v & 0xFFFF;
        return sum / p.size();
    }

    /** @return {cells from the joint cell to the run's first cell, lateral shift} ({@link #fit}) */
    public static int[] attach(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        Fit f = fit(c, d, jb, joint, straight, width, run, maxShift);
        return new int[]{f.off(), f.shift()};
    }

    /**
     * @param ax the world x of a's local 0 (and so on; y relative to a common ground)
     * @return true when pasting one piece after the other, in either order, would clear or replace a block the first
     * placed: the second has air or a different block in a cell where the first has a block (substitution in the
     * second leaves it)
     */
    public static boolean destructive(Grid a, int ax, int ay, int az, Grid b, int bx, int by, int bz) {
        int x0 = Math.max(ax, bx), x1 = Math.min(ax + a.sx(), bx + b.sx()), y0 = Math.max(ay, by), y1 = Math.min(ay + a.sy(), by + b.sy());
        int z0 = Math.max(az, bz), z1 = Math.min(az + a.sz(), bz + b.sz());
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++)
                for (int z = z0; z < z1; z++) {
                    int ia = a.id(x - ax, y - ay, z - az), ib = b.id(x - bx, y - by, z - bz);
                    if (hurts(ia, ib) || hurts(ib, ia)) return true;
                }
        return false;
    }

    // Memoised fits and face scores: keyed by the pieces (grids are cached per piece and turn, so the same objects
    // come back; a record compares their block arrays by identity) and the joint box relative to the joint cell.
    private record FitKey(Grid joint, Grid straight, int d, int width, int run, int maxShift, int x0, int z0, int x1, int z1) {
    }

    private static final java.util.Map<FitKey, Fit> FITS = new java.util.HashMap<>();
    private static final java.util.Map<FitKey, Double> SCORES = new java.util.HashMap<>();

    /** Forgets the memoised fits (pieces reloaded). */
    public static void clearCache() {
        FITS.clear();
        SCORES.clear();
    }

    private static boolean hurts(int first, int second) {
        return first > 0 && second != 0 && second != first;
    }

    /**
     * @return how well a turned joint piece's face toward {@code d} continues a straight leaving it there ({@link #fit}'s
     * match), plus how well the visible blocks match at the contact (a road corner's open and closed sides can share
     * a shape)
     */
    public static double faceScore(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run) {
        FitKey k = new FitKey(joint, straight, d, width, run, -1, jb[0] - c[0], jb[1] - c[1], jb[2] - c[0], jb[3] - c[1]);
        Double v = SCORES.get(k);
        if (v == null) {
            if (SCORES.size() > 50_000) SCORES.clear();
            v = faceScoreNow(c, d, jb, joint, straight, width, run);
            SCORES.put(k, v);
        }
        return v;
    }

    /** {@link #faceScore} without the cache. */
    public static double faceScoreNow(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run) {
        Fit f = fit(c, d, jb, joint, straight, width, run, Math.max(3, width));
        if (f.depth() < 0) return 0;
        int si = straight.inset((d + 2) % 4), ref = d % 2 == 0 ? c[1] : c[0];
        int[] sbox = boxes(c, d, f.off(), List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox[1] : sbox[0]) - ref;
        return f.match() + jaccard(visible(joint.profile(d, f.depth(), ja, true)), visible(straight.profile((d + 2) % 4, si, sa, true)), f.shift());
    }

    // ---- heights ----

    /**
     * Ground levels for a segment's pieces, in order along the run.
     *
     * @param mode  {@link Plan#FOLLOW} (each on its own ground), {@link Plan#SMOOTH} (raised so neighbours differ by
     *              at most 1), {@link Plan#FLAT} (all at the highest ground under them) or {@link Plan#CHAIN} (all at
     *              the chain's level, {@link #chainBase})
     * @param own   each piece's ground (at its centre); @param high the highest ground under each piece
     * @param dy    blocks added; @param base the chain's level, or {@link Plan#NO_BASE}
     */
    public static int[] levels(int mode, int[] own, int[] high, int dy, int base) {
        int[] out;
        int max = Integer.MIN_VALUE;
        for (int h : high) max = Math.max(max, h);
        switch (mode) {
            case Plan.SMOOTH -> out = Roads.smooth(own, 1);
            case Plan.FLAT -> {
                out = new int[own.length];
                java.util.Arrays.fill(out, max);
            }
            case Plan.CHAIN -> {
                out = new int[own.length];
                java.util.Arrays.fill(out, chainBase(base, max));
            }
            default -> out = own.clone();
        }
        for (int i = 0; i < out.length; i++) out[i] += dy;
        return out;
    }

    /** @return the chain's level after a segment with this highest ground joins it: never below any of its ground */
    public static int chainBase(int base, int segMax) {
        return base == Plan.NO_BASE ? segMax : Math.max(base, segMax);
    }

    /** @return a joint piece's level: its highest neighbour's, or its own with none */
    public static int jointLevel(int own, int[] neighbours) {
        int best = Integer.MIN_VALUE;
        for (int n : neighbours) best = Math.max(best, n);
        return neighbours.length == 0 ? own : best;
    }

    // ---- joint choice ----

    /** @return {whole straights, uncovered cells} for a run of {@code cells} tiled with {@code lens} */
    public static int[] runFit(int cells, int[] lens) {
        if (cells <= 0 || lens.length == 0) return new int[]{0, Math.max(0, cells)};
        List<Integer> f = fill(cells, lens);
        int sum = 0;
        for (int l : f) sum += l;
        return new int[]{f.size(), cells - sum};
    }

    /**
     * @param fits      per candidate joint piece: {whole straights the meeting runs keep, cells they leave uncovered}
     * @param primaries how many candidates (first in the list) the joint's type asks for; the rest are fallbacks
     *                  (a tower for an auto joint), taken only when they keep strictly more straights
     * @return the candidate to use: the primary leaving the fewest cells uncovered (then more straights, then order)
     */
    public static int pickJoint(List<int[]> fits, int primaries) {
        int best = 0;
        for (int q = 1; q < Math.max(1, primaries); q++) {
            int[] a = fits.get(q), b = fits.get(best);
            if (a[1] < b[1] || a[1] == b[1] && a[0] > b[0]) best = q;
        }
        int fb = -1;
        for (int q = primaries; q < fits.size(); q++)
            if (fits.get(q)[0] > fits.get(best)[0] && (fb < 0 || fits.get(q)[0] > fits.get(fb)[0] || fits.get(q)[0] == fits.get(fb)[0] && fits.get(q)[1] < fits.get(fb)[1])) fb = q;
        return fb >= 0 ? fb : best;
    }

    /**
     * @param outA the first line's direction out of the joint (travel arrives along it); @param outB the other's
     * @param flip the first line's flip (unflipped walls face out to the left of travel)
     * @return true when the turn bends toward the outside: an inner corner
     */
    public static boolean innerTurn(int outA, int outB, boolean flip) {
        int arrive = (outA + 2) % 4, turn = Math.floorMod(outB - arrive, 4);
        // Directions run clockwise seen from above (east, south, west, north): +1 is a right turn.
        boolean left = turn == 3;
        return left != flip;
    }

    /** Auto joint roles: none (a straight line), outer corner, inner corner, T, cross. */
    public static final int NONE = 0, CORNER = 1, INNER = 2, TEE = 3, CROSS = 4;

    /**
     * Auto's joint from the topology alone: how many lines meet, straight on or not. Every L gets the plain corner
     * (turned to fit either way round); the inner corner and the tower are only used when chosen per joint, so the
     * same gesture turning left or right looks the same ({@link #innerTurn} stays for the hints).
     */
    public static int autoRole(int degree, boolean collinear, int outA, int outB, boolean flip) {
        if (degree >= 4) return CROSS;
        if (degree == 3) return TEE;
        return collinear ? NONE : CORNER;
    }

    /**
     * The piece roles a joint may use, best first: the chosen type's role (or auto's, {@link #autoRole}), then the
     * stand-ins when the family lacks it (a corner for an inner corner and the other way round, a tower for a T or
     * cross and for a tower-less family's tower pick a corner). Empty = a butt joint (none, or straight on).
     *
     * @param type   {@link Plan#J_AUTO} .. {@link Plan#J_NONE}; @param towers the line was drawn with towers
     */
    public static List<PieceSets.Role> jointRoles(int type, int degree, boolean collinear, boolean towers) {
        if (type == Plan.J_NONE) return List.of();
        if (type == Plan.J_AUTO) {
            if (towers) return List.of(PieceSets.Role.TOWER, PieceSets.Role.CORNER, PieceSets.Role.INNER);
            int auto = autoRole(degree, collinear, 0, 0, false);
            type = auto == NONE ? Plan.J_NONE : auto == CROSS ? Plan.J_CROSS : auto == TEE ? Plan.J_TEE : Plan.J_CORNER;
        }
        return switch (type) {
            case Plan.J_TOWER -> List.of(PieceSets.Role.TOWER, PieceSets.Role.CORNER, PieceSets.Role.INNER);
            case Plan.J_CORNER -> List.of(PieceSets.Role.CORNER, PieceSets.Role.INNER);
            case Plan.J_INNER -> List.of(PieceSets.Role.INNER, PieceSets.Role.CORNER);
            case Plan.J_TEE -> List.of(PieceSets.Role.TEE, PieceSets.Role.TOWER);
            case Plan.J_CROSS -> List.of(PieceSets.Role.CROSS, PieceSets.Role.TOWER);
            default -> List.of();
        };
    }
}
