package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Roads: a polyline of clicked ground points turned into block columns. The centreline is walked one block at a
 * time (Bresenham per segment), its ground heights are smoothed so the road never climbs or drops more than one
 * block per step, and every column within half the width of the centreline takes the height of its nearest
 * centreline cell. Pure.
 */
public final class Roads {
    public static final int MIN_WIDTH = 1, MAX_WIDTH = 7, MAX_COLUMNS = 4096;

    /** One road column: where it is, the y of the road block (the surface block it replaces), its centreline cell index. */
    public record Column(int x, int z, int y, int cell) {
    }

    private Roads() {
    }

    /** @return the centreline cells {x, z} through the points, one block apart, without repeats */
    public static List<int[]> centreline(List<int[]> points) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            int[] a = points.get(i), b = i + 1 < points.size() ? points.get(i + 1) : a;
            int x = a[0], z = a[1], dx = Math.abs(b[0] - x), dz = -Math.abs(b[1] - z), sx = x < b[0] ? 1 : -1, sz = z < b[1] ? 1 : -1, err = dx + dz;
            while (true) {
                if (out.isEmpty() || out.get(out.size() - 1)[0] != x || out.get(out.size() - 1)[1] != z) out.add(new int[]{x, z});
                if (x == b[0] && z == b[1]) break;
                int e2 = 2 * err;
                if (e2 >= dz) {
                    err += dz;
                    x += sx;
                }
                if (e2 <= dx) {
                    err += dx;
                    z += sz;
                }
            }
        }
        return out;
    }

    /**
     * @return heights that change by at most {@code maxStep} between neighbours and are never below the ground: dips
     * are filled and the road ramps up ahead of a rise (it never cuts into the ground)
     */
    public static int[] smooth(int[] ground, int maxStep) {
        int[] h = ground.clone();
        // Raising only: after both passes each cell is at least its neighbours minus maxStep, both ways.
        for (int i = 1; i < h.length; i++) h[i] = Math.max(h[i], h[i - 1] - maxStep);
        for (int i = h.length - 2; i >= 0; i--) h[i] = Math.max(h[i], h[i + 1] - maxStep);
        return h;
    }

    /**
     * @param points clicked ground points {x, z}
     * @param width  road width in blocks (1..7)
     * @param ground surface height at a column: the y of the top ground block
     * @return the road columns (at most {@link #MAX_COLUMNS}), each at its smoothed height
     */
    public static List<Column> columns(List<int[]> points, int width, IntBinaryOperator ground) {
        List<int[]> line = centreline(points);
        if (line.isEmpty()) return List.of();
        int[] g = new int[line.size()];
        for (int i = 0; i < g.length; i++) g[i] = ground.applyAsInt(line.get(i)[0], line.get(i)[1]);
        int[] h = smooth(g, 1);
        double r = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)) / 2.0;
        int reach = (int) Math.ceil(r);
        // Each column takes the height of its nearest centreline cell.
        Map<Long, int[]> best = new LinkedHashMap<>();
        for (int i = 0; i < line.size(); i++) {
            int cx = line.get(i)[0], cz = line.get(i)[1];
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    double d2 = dx * dx + dz * dz;
                    if (Math.sqrt(d2) > r - 0.01 && !(dx == 0 && dz == 0)) continue;
                    long key = ((long) (cx + dx) << 32) ^ ((cz + dz) & 0xFFFFFFFFL);
                    int[] cur = best.get(key);
                    if (cur == null || d2 < cur[1]) best.put(key, new int[]{h[i], (int) d2, cx + dx, cz + dz, i});
                }
        }
        List<Column> out = new ArrayList<>();
        for (int[] v : best.values()) {
            if (out.size() >= MAX_COLUMNS) break;
            out.add(new Column(v[2], v[3], v[0], v[4]));
        }
        return out;
    }
}
