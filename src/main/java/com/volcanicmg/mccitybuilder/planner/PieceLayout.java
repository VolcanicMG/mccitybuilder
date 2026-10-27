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
     * a piece is solid at that face. The shift of {@code b} (within ±max) that overlaps {@code a} most, ties to 0.
     */
    public static int bestShift(Set<Long> a, Set<Long> b, int max) {
        int best = 0;
        double bestScore = jaccard(a, b, 0);
        for (int s = -max; s <= max; s++) {
            double sc = jaccard(a, b, s);
            if (sc > bestScore + 1e-9) {
                bestScore = sc;
                best = s;
            }
        }
        return best;
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
}
