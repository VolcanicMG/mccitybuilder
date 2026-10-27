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

    /**
     * Profile alignment: a face profile is a set of {@code y << 16 | lateral} cells (world lateral coordinate) where
     * a piece is solid at that face. The shift of {@code b} (within ±max) that overlaps {@code a} most; among equal
     * overlaps (a thin wall inside a wide tower face fits at several) the one that centres b on a, then 0.
     */
    public static int bestShift(Set<Long> a, Set<Long> b, int max) {
        int best = 0;
        double bestScore = jaccard(a, b, 0), ca = centre(a), cb = centre(b), bestOff = Math.abs(ca - cb);
        for (int s = -max; s <= max; s++) {
            double sc = jaccard(a, b, s), off = Math.abs(ca - cb - s);
            if (sc > bestScore + 1e-9 || sc > bestScore - 1e-9 && off < bestOff - 1e-9) {
                bestScore = sc;
                best = s;
                bestOff = off;
            }
        }
        return best;
    }

    /** @return the mean lateral coordinate of a profile (its low 16 bits) */
    private static double centre(Set<Long> p) {
        if (p.isEmpty()) return 0;
        double sum = 0;
        for (long v : p) sum += v & 0xFFFF;
        return sum / p.size();
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
     * Where a run leaving a joint piece starts, and how far sideways it shifts, so it meets the piece's body: its end
     * touches the body's face (trim and margins skipped on both pieces) and its profile lines up with that face.
     *
     * @param jb       the joint's world box; @param joint the joint piece as placed (turned)
     * @param straight the run's straight as placed (turned for the run's direction); its face toward the joint is (d+2)%4
     * @param d        the direction from the joint cell into the run
     * @return {cells from the joint cell to the run's first cell, lateral shift}
     */
    public static int[] attach(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        int ji = joint.inset(d), si = straight.inset((d + 2) % 4), edge = reach(c, d, jb);
        int ref = d % 2 == 0 ? c[1] : c[0];
        int[] sbox = boxes(c, d, Math.max(1, edge - ji - si), List.of(run), width, 0).get(0);
        Set<Long> a = joint.profile(d, ji, (d % 2 == 0 ? jb[1] : jb[0]) - ref, false);
        Set<Long> b = straight.profile((d + 2) % 4, si, (d % 2 == 0 ? sbox[1] : sbox[0]) - ref, false);
        int shift = bestShift(a, b, maxShift);
        // Into the joint's trim only as far as no block of either piece is cleared or replaced, in either build order
        // (survival builders work in any order); else the run stops at the joint's box (a cosmetic gap at most).
        for (int k = ji + si; k > 0; k--) {
            int[] bx = boxes(c, d, edge - k, List.of(run), width, shift).get(0);
            if (!destructive(joint, jb[0], -joint.ground(), jb[1], straight, bx[0], -straight.ground(), bx[1])) return new int[]{edge - k, shift};
        }
        return new int[]{edge, shift};
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

    private static boolean hurts(int first, int second) {
        return first > 0 && second != 0 && second != first;
    }

    /** @return how well a turned joint piece's face toward {@code d} matches a straight's end there: shape + blocks, 0..2 */
    public static double faceScore(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run) {
        int ji = joint.inset(d), si = straight.inset((d + 2) % 4), ref = d % 2 == 0 ? c[1] : c[0];
        int[] sbox = boxes(c, d, 1, List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox[1] : sbox[0]) - ref;
        Set<Long> jf = joint.profile(d, ji, ja, false), sf = straight.profile((d + 2) % 4, si, sa, false);
        int sh = bestShift(jf, sf, Math.max(3, width));
        return jaccard(jf, sf, sh) + jaccard(joint.profile(d, ji, ja, true), straight.profile((d + 2) % 4, si, sa, true), sh);
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
}
