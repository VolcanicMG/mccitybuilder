package com.volcanicmg.mccitybuilder.planner;

import java.util.List;

/**
 * Snapping for the road and wall tools, like city builders: a chain of segments where each new segment starts at
 * the previous end. Starts snap to existing segment endpoints, else onto an existing segment (a branch); ends snap to
 * endpoints, else to multiples of 45° relative to the previous segment (or the grid for the first), unless free. Pure.
 */
public final class LineSnap {
    /** How close (blocks) the cursor must be to an endpoint or a segment to snap to it. */
    public static final double RADIUS = 2.5;

    private LineSnap() {
    }

    /** @return the endpoint nearest the cursor within {@link #RADIUS}, or null */
    public static int[] nearEndpoint(double cx, double cz, List<int[][]> segments) {
        return nearEndpoint(cx, cz, segments, RADIUS);
    }

    /** @return the endpoint nearest the cursor within {@code r}, or null */
    public static int[] nearEndpoint(double cx, double cz, List<int[][]> segments, double r) {
        int[] best = null;
        double bd = r * r;
        for (int[][] s : segments)
            for (int[] p : s) {
                double d = (p[0] + 0.5 - cx) * (p[0] + 0.5 - cx) + (p[1] + 0.5 - cz) * (p[1] + 0.5 - cz);
                if (d <= bd) {
                    bd = d;
                    best = p;
                }
            }
        return best;
    }

    /** @return where a chain starts: an endpoint, else the nearest cell of a segment (a branch), else the cursor's block */
    public static int[] snapStart(double cx, double cz, List<int[][]> segments) {
        int[] e = nearEndpoint(cx, cz, segments);
        if (e != null) return e.clone();
        int[] best = null;
        double bd = RADIUS * RADIUS;
        for (int[][] s : segments)
            for (int[] c : Roads.centreline(List.of(s[0], s[1]))) {
                double d = (c[0] + 0.5 - cx) * (c[0] + 0.5 - cx) + (c[1] + 0.5 - cz) * (c[1] + 0.5 - cz);
                if (d <= bd) {
                    bd = d;
                    best = c;
                }
            }
        return best != null ? best : new int[]{(int) Math.floor(cx), (int) Math.floor(cz)};
    }

    /**
     * @param prev the previous segment's direction {dx, dz}, or null for the first (angles then snap to the grid)
     * @param free true (Shift) = no angle snap
     * @return the segment end for the cursor
     */
    public static int[] snapEnd(int[] start, double cx, double cz, int[] prev, boolean free, List<int[][]> segments) {
        return snapEnd(start, cx, cz, prev, free, segments, 45);
    }

    /** {@link #snapEnd} with the angle step in degrees (90 = straight runs only, for packs without diagonal pieces). */
    public static int[] snapEnd(int[] start, double cx, double cz, int[] prev, boolean free, List<int[][]> segments, int stepDeg) {
        int[] e = nearEndpoint(cx, cz, segments);
        if (e != null && !(e[0] == start[0] && e[1] == start[1]) && (stepDeg < 90 || e[0] == start[0] || e[1] == start[1])) return e.clone();
        double dx = cx - (start[0] + 0.5), dz = cz - (start[1] + 0.5), d = Math.hypot(dx, dz);
        if (free && stepDeg < 90 || d < 1e-6) return new int[]{(int) Math.floor(cx), (int) Math.floor(cz)};
        double base = prev == null || prev[0] == 0 && prev[1] == 0 ? 0 : Math.atan2(prev[1], prev[0]);
        double step = Math.toRadians(stepDeg), a = base + Math.round((Math.atan2(dz, dx) - base) / step) * step;
        double cos = Math.cos(a), sin = Math.sin(a);
        // Along a diagonal the length counts blocks per axis, so the end lands on the true 45° cell.
        double len = Math.abs(cos) > 0.1 && Math.abs(sin) > 0.1 ? Math.round(d / Math.sqrt(2)) * Math.sqrt(2) : Math.round(d);
        return new int[]{start[0] + (int) Math.round(cos * len), start[1] + (int) Math.round(sin * len)};
    }

    /** @return the segment's length in blocks (cells along it, end excluded) */
    public static int length(int[] a, int[] b) {
        return Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
    }

    /** @return true when the live segment must be laid out again: no layout yet, or its key (ends, plan, options) changed */
    public static boolean relayout(int[] prevKey, int[] key) {
        return prevKey == null || !java.util.Arrays.equals(prevKey, key);
    }

    /**
     * @param cells the segment's length in cells (both ends included)
     * @param need  the cells it needs to fit a piece ({@code PieceLayout.need}; MAX_VALUE when nothing fits)
     * @return true when a click may drop it (false = too short: a 0-piece segment would be planned)
     */
    public static boolean canDrop(int cells, int need) {
        return need != Integer.MAX_VALUE && cells >= need;
    }

    /** @return the snap radius in blocks for a camera this high above the ground: wider when zoomed out */
    public static double radius(double heightAboveGround) {
        return Math.max(RADIUS, Math.min(10, heightAboveGround / 10));
    }

    /** @return true when a segment of {@code cells} is covered exactly by the joint / cap offsets and whole straights */
    public static boolean exact(int cells, int off0, int off1, int[] lens) {
        int n = cells - off0 - off1;
        if (n <= 0) return false;
        int sum = 0;
        for (int l : PieceLayout.fill(n, lens)) sum += l;
        return sum == n;
    }

    /**
     * Length snap: the segment length nearest {@code cells} that tiles exactly (no "short" gap), the shorter on a tie.
     *
     * @return that length, or {@code cells} when none is within the longest straight plus the offsets
     */
    public static int snapLength(int cells, int off0, int off1, int[] lens) {
        int max = 0;
        for (int l : lens) max = Math.max(max, l);
        // Far enough that a short drag still reaches the first length that fits a piece between the joints.
        max += Math.max(0, off0) + Math.max(0, off1);
        for (int d = 0; d <= max; d++) {
            if (cells - d >= 1 && exact(cells - d, off0, off1, lens)) return cells - d;
            if (exact(cells + d, off0, off1, lens)) return cells + d;
        }
        return cells;
    }

    /** @return the candidate nearest {@code v} within {@code r}, or {@link Integer#MIN_VALUE} */
    public static int nearest(double v, List<Integer> candidates, double r) {
        int best = Integer.MIN_VALUE;
        double bd = r;
        for (int c : candidates) {
            double d = Math.abs(c + 0.5 - v);
            if (d <= bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    /**
     * Axis snap: centreline coordinates of the segments, per axis, so a start lines up with them (parallel walls on
     * one line, a perpendicular wall on another's axis) and so seams line up (start + whole pieces along the run).
     *
     * @param run the straight length, for the seam grid (0 = none)
     * @return {x candidates, z candidates}
     */
    public static List<List<Integer>> axes(List<int[][]> segments, int run, int span) {
        List<Integer> xs = new java.util.ArrayList<>(), zs = new java.util.ArrayList<>();
        for (int[][] s : segments) {
            boolean alongX = s[0][1] == s[1][1];
            if (alongX) {
                zs.add(s[0][1]);
                if (run > 0) for (int k = -span; k <= span; k++) xs.add(s[0][0] + k * run);
            } else {
                xs.add(s[0][0]);
                if (run > 0) for (int k = -span; k <= span; k++) zs.add(s[0][1] + k * run);
            }
        }
        return List.of(xs, zs);
    }

    /**
     * T snap: a cell of a perpendicular segment the run from {@code start} along {@code dir} crosses near {@code along}
     * (cells from the start), so the end lands on it and the segment is split there into a T.
     *
     * @return the cells from the start, or -1
     */
    public static int crossing(int[] start, int dir, double along, List<int[][]> segments, double r) {
        int best = -1;
        double bd = r;
        for (int[][] s : segments) {
            boolean alongX = s[0][1] == s[1][1];
            // Perpendicular only: an X-run crosses Z-segments and the other way round.
            if ((dir % 2 == 0) == alongX) continue;
            int at = dir % 2 == 0 ? s[0][0] : s[0][1], cross = dir % 2 == 0 ? start[1] : start[0];
            int lo = dir % 2 == 0 ? Math.min(s[0][1], s[1][1]) : Math.min(s[0][0], s[1][0]), hi = dir % 2 == 0 ? Math.max(s[0][1], s[1][1]) : Math.max(s[0][0], s[1][0]);
            if (cross <= lo || cross >= hi) continue;
            int cells = (dir == 0 || dir == 1 ? at - (dir == 0 ? start[0] : start[1]) : (dir == 2 ? start[0] : start[1]) - at);
            if (cells <= 0) continue;
            double d = Math.abs(cells - along);
            if (d <= bd) {
                bd = d;
                best = cells;
            }
        }
        return best;
    }
}
