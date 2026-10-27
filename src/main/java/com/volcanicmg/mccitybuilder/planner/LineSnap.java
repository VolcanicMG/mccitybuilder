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
        int[] best = null;
        double bd = RADIUS * RADIUS;
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
}
