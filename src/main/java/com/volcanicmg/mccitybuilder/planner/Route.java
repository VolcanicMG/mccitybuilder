package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Auto-route for the road tool: the cheapest chain of axis-aligned segments from a start cell to an end cell over a
 * snapshot of the terrain. A route is a list of points (start, corners, end); every segment between two corners tiles
 * exactly with the family's straights between the corner pieces ({@link LineSnap#exact}), the first one between the
 * start's joint / cap and a corner, the last one is long enough to drop ({@link LineSnap#canDrop}), its uncovered cells
 * costing extra. Cost: length, a penalty per turn, slope (height steps along the run, steeper than one block much
 * more), water (blocked, or a bridge cell when bridges are allowed: straight across, never a corner on water), cells of
 * earlier routes (Try another). Obstacles and water are widened by the road's half width. A* over (cell, axis the last
 * segment ran along), moves = whole segments. Pure, so it runs off the client thread on a snapshot and SelfCheck drives it.
 */
public final class Route {
    /** {@link Grid} cell flags: nothing may be built there; water; an existing road of this tool (cleared near the ends). */
    public static final byte BLOCKED = 1, WATER = 2, ROAD = 4;

    /**
     * The terrain snapshot: a {@code w} x {@code h} box of cells from (x0, z0); per cell the ground height and flags,
     * index {@code (z - z0) * w + (x - x0)}.
     */
    public record Grid(int x0, int z0, int w, int h, int[] ground, byte[] flags) {
        public boolean in(int x, int z) {
            return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h;
        }

        public int at(int x, int z) {
            return (z - z0) * w + (x - x0);
        }
    }

    /**
     * What to route and how.
     *
     * @param half      the road's half width (cells beside the centreline it covers): obstacles and water are widened by it
     * @param lens      the family's straight lengths (what segments tile with)
     * @param offStart  cells the joint / cap at the start takes; @param offEnd the same at the end; @param corner a corner's
     * @param maxLen    longest route (sum of segment lengths, blocks)
     * @param bridges   water may be crossed straight, at most {@code bridgeMax} cells in a row
     * @param avoid     earlier routes (Try another): each of their cells costs {@code avoidCost} more per route through it
     * @param banStart  directions (0 east .. 3 north) the first segment may not leave in; @param banEnd directions the last may not arrive in
     * @param freeStart the start joins nothing (a free end): the first segment needn't tile exactly, the route's start moves
     *                  in by the cells its pieces leave over (as Done trims a chain's free end); @param freeEnd the same at the end
     */
    public record Params(int[] start, int[] end, int half, int[] lens, int offStart, int offEnd, int corner, int maxLen, double turnCost,
                         double slopeCost, double steepCost, boolean bridges, int bridgeMax, double bridgeCost, List<List<int[]>> avoid, double avoidCost,
                         Set<Integer> banStart, Set<Integer> banEnd, boolean freeStart, boolean freeEnd) {
        public Params(int[] start, int[] end, int half, int[] lens, int offStart, int offEnd, int corner, int maxLen, double turnCost,
                      double slopeCost, double steepCost, boolean bridges, int bridgeMax, double bridgeCost, List<List<int[]>> avoid, double avoidCost,
                      Set<Integer> banStart, Set<Integer> banEnd) {
            this(start, end, half, lens, offStart, offEnd, corner, maxLen, turnCost, slopeCost, steepCost, bridges, bridgeMax, bridgeCost, avoid, avoidCost,
                    banStart, banEnd, false, false);
        }
    }

    /** Default weights: a turn costs as much as this many blocks of road; a 1-block step; each block of a steeper one. */
    public static final double TURN = 12, SLOPE = 1.5, STEEP = 12, BRIDGE = 2, AVOID = 3, GAP = 4;

    /** A route: its points (start .. end), total length, and why there is none ({@code null} when found). */
    public record Result(List<int[]> points, int length, String fail) {
        public static Result failed(String why) {
            return new Result(List.of(), 0, why);
        }
    }

    private static final int[][] D = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private Route() {
    }

    /** @return the bounding box {x0, z0, w, h} a route between a and b may use: their box widened by {@code margin} */
    public static int[] box(int[] a, int[] b, int margin) {
        int x0 = Math.min(a[0], b[0]) - margin, z0 = Math.min(a[1], b[1]) - margin;
        return new int[]{x0, z0, Math.abs(a[0] - b[0]) + 2 * margin + 1, Math.abs(a[1] - b[1]) + 2 * margin + 1};
    }

    /** @return the margin around start / end a route of at most {@code maxLen} could use, at most {@code cap} */
    public static int margin(int[] a, int[] b, int maxLen, int cap) {
        int manhattan = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
        return Math.max(8, Math.min(cap, (maxLen - manhattan) / 2 + 4));
    }

    /**
     * @param cancelled polled now and then: true stops the search ("cancelled")
     * @return the cheapest route, or a failure: {@code too_far} (straight-line distance over the maximum), {@code blocked}
     * (an end on an obstacle), {@code no_route}, {@code cancelled}
     */
    public static Result find(Grid g, Params p, BooleanSupplier cancelled) {
        int[] s = p.start(), t = p.end();
        if (Math.abs(s[0] - t[0]) + Math.abs(s[1] - t[1]) > p.maxLen()) return Result.failed("too_far");
        if (!g.in(s[0], s[1]) || !g.in(t[0], t[1]) || s[0] == t[0] && s[1] == t[1]) return Result.failed("no_route");
        int w = g.w(), h = g.h(), n = w * h;
        boolean[] bad = new boolean[n], wet = new boolean[n];
        byte[] f = g.flags();
        int ex = p.half() + p.corner() + 1;
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                boolean road = (f[i] & ROAD) != 0 && !near(g.x0() + x, g.z0() + z, s, ex) && !near(g.x0() + x, g.z0() + z, t, ex);
                if ((f[i] & BLOCKED) != 0 || road) widen(bad, w, h, x, z, p.half());
                if ((f[i] & WATER) != 0) widen(wet, w, h, x, z, p.half());
            }
        if (bad[g.at(s[0], s[1])] || bad[g.at(t[0], t[1])]) return Result.failed("blocked");
        int[] avoid = new int[n];
        for (List<int[]> r : p.avoid()) for (int[] c : cells(r)) if (g.in(c[0], c[1])) avoid[g.at(c[0], c[1])]++;
        int maxM = p.maxLen();
        boolean[] interior = new boolean[maxM + 1], first = new boolean[maxM + 1];
        for (int m = 1; m <= maxM; m++) {
            interior[m] = LineSnap.exact(m + 1, p.corner(), p.corner(), p.lens());
            first[m] = p.freeStart() ? m + 1 >= PieceLayout.need(p.offStart(), p.corner(), p.lens()) : LineSnap.exact(m + 1, p.offStart(), p.corner(), p.lens());
        }
        int needLast = PieceLayout.need(p.corner(), p.offEnd(), p.lens()), needOne = PieceLayout.need(p.offStart(), p.offEnd(), p.lens());
        if (needOne == Integer.MAX_VALUE) return Result.failed("no_route");
        // States: cell * 2 + the axis the last segment ran along (0 = x, 1 = z); n * 2 is the start, n * 2 + 1 the end.
        int states = 2 * n + 2, startS = 2 * n, goal = 2 * n + 1;
        double[] cost = new double[states];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        int[] len = new int[states], from = new int[states];
        java.util.Arrays.fill(from, -1);
        boolean[] done = new boolean[states];
        Heap q = new Heap();
        cost[startS] = 0;
        q.push(0, startS);
        int polls = 0;
        while (!q.empty()) {
            int st = q.pop();
            if (done[st]) continue;
            done[st] = true;
            if (st == goal) break;
            if ((++polls & 1023) == 0 && cancelled.getAsBoolean()) return Result.failed("cancelled");
            int cx, cz;
            boolean isStart = st == startS;
            if (isStart) {
                cx = s[0];
                cz = s[1];
            } else {
                cx = g.x0() + (st >> 1) % w;
                cz = g.z0() + (st >> 1) / w;
            }
            for (int d = 0; d < 4; d++) {
                int axis = d % 2;
                if (!isStart && axis == (st & 1)) continue;
                if (isStart && p.banStart().contains(d)) continue;
                double c = cost[st] + (isStart ? 0 : p.turnCost());
                int run = 0, x = cx, z = cz, prevH = g.ground()[g.at(cx, cz)];
                for (int m = 1; len[st] + m <= p.maxLen(); m++) {
                    x += D[d][0];
                    z += D[d][1];
                    if (!g.in(x, z)) break;
                    int i = g.at(x, z);
                    if (bad[i]) break;
                    if (wet[i]) {
                        if (!p.bridges() || ++run > p.bridgeMax()) break;
                        c += p.bridgeCost();
                    } else run = 0;
                    int hh = g.ground()[i], dh = Math.abs(hh - prevH);
                    // Over water the bridge stays level: the banks' step doesn't count.
                    if (!wet[i]) {
                        c += p.slopeCost() * dh + p.steepCost() * Math.max(0, dh - 1);
                        prevH = hh;
                    }
                    c += 1 + p.avoidCost() * avoid[i];
                    if (x == t[0] && z == t[1]) {
                        if (!p.banEnd().contains(d) && m + 1 >= (isStart ? needOne : needLast)) {
                            // A free end is trimmed afterwards: its leftover cells cost nothing.
                            boolean trims = p.freeEnd() || isStart && p.freeStart();
                            int gap = trims ? 0 : uncovered(m + 1, isStart ? p.offStart() : p.corner(), p.offEnd(), p.lens());
                            relax(q, cost, len, from, done, goal, st, c + GAP * gap, len[st] + m, 0);
                        }
                        break;
                    }
                    // A corner here: on land, and the segment so far tiles exactly.
                    if (wet[i] || !(isStart ? first[m] : interior[m])) continue;
                    int ns = 2 * i + axis;
                    relax(q, cost, len, from, done, ns, st, c, len[st] + m, Math.abs(x - t[0]) + Math.abs(z - t[1]));
                }
            }
        }
        if (!done[goal]) return Result.failed("no_route");
        List<int[]> pts = new ArrayList<>();
        pts.add(t.clone());
        for (int st = from[goal]; st != -1 && st != startS; st = from[st]) pts.add(0, new int[]{g.x0() + (st >> 1) % w, g.z0() + (st >> 1) / w});
        pts.add(0, s.clone());
        int length = len[goal];
        // Free ends move in by what their pieces leave over: the first segment's at the start, the last's at the end.
        int last = pts.size() - 1;
        int o1 = last == 1 ? p.offEnd() : p.corner(), u0 = p.freeStart() && !(last == 1 && p.freeEnd()) ? uncovered(LineSnap.length(pts.get(0), pts.get(1)) + 1, p.offStart(), o1, p.lens()) : 0;
        length -= trim(pts, 0, 1, u0);
        int o0 = last == 1 ? p.offStart() : p.corner(), u1 = p.freeEnd() ? uncovered(LineSnap.length(pts.get(last - 1), pts.get(last)) + 1, o0, p.offEnd(), p.lens()) : 0;
        length -= trim(pts, last, last - 1, u1);
        return new Result(pts, length, null);
    }

    /** Moves point {@code k} toward point {@code toward} by {@code u} cells. @return u */
    private static int trim(List<int[]> pts, int k, int toward, int u) {
        int[] a = pts.get(k), b = pts.get(toward);
        pts.set(k, new int[]{a[0] + Integer.signum(b[0] - a[0]) * u, a[1] + Integer.signum(b[1] - a[1]) * u});
        return u;
    }

    /** A* relax: {@code c} is the cost so far, {@code h} the distance left (every block costs at least 1, so it never overestimates). */
    private static void relax(Heap q, double[] cost, int[] len, int[] from, boolean[] done, int ns, int st, double c, int l, int h) {
        if (done[ns] || c >= cost[ns]) return;
        cost[ns] = c;
        len[ns] = l;
        from[ns] = st;
        q.push(c + h, ns);
    }

    /** @return the cells a segment of {@code cells} leaves uncovered between its offsets (0 = exact) */
    static int uncovered(int cells, int off0, int off1, int[] lens) {
        int k = cells - off0 - off1, sum = 0;
        if (k <= 0) return 0;
        for (int l : PieceLayout.fill(k, lens)) sum += l;
        return k - sum;
    }

    private static boolean near(int x, int z, int[] c, int r) {
        return Math.abs(x - c[0]) <= r && Math.abs(z - c[1]) <= r;
    }

    private static void widen(boolean[] out, int w, int h, int x, int z, int r) {
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx, zz = z + dz;
                if (xx >= 0 && zz >= 0 && xx < w && zz < h) out[zz * w + xx] = true;
            }
    }

    /** @return every cell along a route's points (corners once) */
    public static List<int[]> cells(List<int[]> pts) {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k + 1 < pts.size(); k++) {
            int[] a = pts.get(k), b = pts.get(k + 1);
            int dx = Integer.signum(b[0] - a[0]), dz = Integer.signum(b[1] - a[1]), m = LineSnap.length(a, b);
            for (int j = k == 0 ? 0 : 1; j <= m; j++) out.add(new int[]{a[0] + dx * j, a[1] + dz * j});
        }
        return out;
    }

    /** @return true when the two routes' points are the same */
    public static boolean same(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /** A binary min-heap of (cost, state); stale entries are skipped by the caller. */
    private static final class Heap {
        private double[] k = new double[1024];
        private int[] v = new int[1024];
        private int size;

        boolean empty() {
            return size == 0;
        }

        void push(double key, int val) {
            if (size == k.length) {
                k = java.util.Arrays.copyOf(k, size * 2);
                v = java.util.Arrays.copyOf(v, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int up = (i - 1) / 2;
                if (k[up] <= key) break;
                k[i] = k[up];
                v[i] = v[up];
                i = up;
            }
            k[i] = key;
            v[i] = val;
        }

        int pop() {
            int top = v[0];
            double key = k[--size];
            int val = v[size], i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= size) break;
                if (c + 1 < size && k[c + 1] < k[c]) c++;
                if (k[c] >= key) break;
                k[i] = k[c];
                v[i] = v[c];
                i = c;
            }
            k[i] = key;
            v[i] = val;
            return top;
        }
    }
}
