package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A layout plan: ghosts of huts and decorations dropped without building them. Pure: entries, a line-based text
 * format for saving per world and colony, and conflict detection (overlaps with each other or existing buildings,
 * footprint outside the colony's claims).
 */
public final class Plan {
    /**
     * One planned structure. {@code hut} = a hut block id (null for a decoration); {@code path} = the blueprint path at
     * {@code level}; {@code rot} = Structurize RotationMirror ordinal; the box is the footprint in world blocks,
     * inclusive, for the current position and rotation.
     */
    public record Entry(String hut, String pack, String path, int level, int x, int y, int z, int rot,
                        int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean isHut() {
            return hut != null && !hut.isEmpty();
        }

        /** @return the entry moved by (dx, dy, dz), box included */
        public Entry moved(int dx, int dy, int dz) {
            return new Entry(hut, pack, path, level, x + dx, y + dy, z + dz, rot, minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
        }

        /** @return the same entry with a new blueprint path / level / rotation and footprint box */
        public Entry with(String path, int level, int rot, int[] box) {
            return new Entry(hut, pack, path, level, x, y, z, rot, box[0], box[1], box[2], box[3], box[4], box[5]);
        }

        public int[] box() {
            return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        }
    }

    /**
     * A drawn road or wall segment from (x0, z0) to (x1, z1), built from a style pack's pieces: the pack, the piece
     * family, the level (level groups) or variant (-1 = varied), towers at its joints, a gate in its middle.
     */
    /**
     * Joint types a joint can be set to: picked automatically, or a tower / outer corner / inner corner / T / cross, or
     * none (butt joint); a free end takes auto (its cap), a tower, an end cap or none.
     */
    public static final int J_AUTO = 0, J_TOWER = 1, J_CORNER = 2, J_INNER = 3, J_TEE = 4, J_CROSS = 5, J_NONE = 6, J_CAP = 7, J_TYPES = 8;

    /**
     * @return the types the joint menu offers for a cell where {@code degree} segment ends meet: a free end gets auto,
     * end cap, tower, none; an L the corners and a tower; a T or cross its piece and a tower; straight on a tower only
     */
    public static List<Integer> menuTypes(int degree, boolean collinear) {
        if (degree <= 1) return List.of(J_AUTO, J_CAP, J_TOWER, J_NONE);
        if (degree >= 4) return List.of(J_AUTO, J_CROSS, J_TOWER, J_NONE);
        if (degree == 3) return List.of(J_AUTO, J_TEE, J_TOWER, J_NONE);
        return collinear ? List.of(J_AUTO, J_TOWER, J_NONE) : List.of(J_AUTO, J_CORNER, J_INNER, J_TOWER, J_NONE);
    }

    /** @return the key of a joint cell */
    public static long cell(int x, int z) {
        return (long) x << 32 ^ (z & 0xFFFFFFFFL);
    }

    public static int cellX(long k) {
        return (int) (k >> 32);
    }

    public static int cellZ(long k) {
        return (int) k;
    }

    /** @return the joint types in {@code text} (rows {@code J x z type}); damaged rows and unknown types are skipped */
    public static java.util.Map<Long, Integer> readJoints(String text) {
        java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        if (text == null) return out;
        for (String row : text.split("\n")) {
            String[] f = row.split("\t");
            if (f.length != 4 || !f[0].equals("J")) continue;
            try {
                int t = Integer.parseInt(f[3].trim());
                if (t > J_AUTO && t < J_TYPES) out.put(cell(Integer.parseInt(f[1]), Integer.parseInt(f[2])), t);
            } catch (NumberFormatException ignored) {
                // A damaged row: skip it.
            }
        }
        return out;
    }

    /** Height modes of a line: each piece on its ground, flat per segment, flat per chain, smoothed (steps of 1). */
    public static final int FOLLOW = 0, FLAT = 1, CHAIN = 2, SMOOTH = 3;
    /** {@link Line#base} when no chain height is fixed yet. */
    public static final int NO_BASE = Integer.MIN_VALUE;

    /**
     * A drawn road / wall segment and its options. {@code flip}: straights and gates turned 180° (a wall's other side
     * out); {@code height}: one of {@link #FOLLOW} .. {@link #SMOOTH}; {@code dy}: blocks added to that height;
     * {@code base}: the chain's shared ground level ({@link #CHAIN}); {@code built}: already built (kept so new
     * segments can snap and join onto it; never built again).
     */
    public record Line(String tool, int x0, int z0, int x1, int z1, String pack, String family, int level, int variant, boolean towers,
                       boolean gate, boolean flip, int height, int dy, int base, boolean built) {
        public Line(String tool, int x0, int z0, int x1, int z1, String pack, String family, int level, int variant, boolean towers, boolean gate) {
            this(tool, x0, z0, x1, z1, pack, family, level, variant, towers, gate, false, FOLLOW, 0, NO_BASE, false);
        }

        public boolean isWall() {
            return "wall".equals(tool);
        }

        /** @return this segment with other ends (a split keeps every option) */
        public Line between(int ax, int az, int bx, int bz) {
            return new Line(tool, ax, az, bx, bz, pack, family, level, variant, towers, gate, flip, height, dy, base, built);
        }

        public Line withFlip(boolean f) {
            return new Line(tool, x0, z0, x1, z1, pack, family, level, variant, towers, gate, f, height, dy, base, built);
        }

        public Line withHeight(int mode, int d, int b) {
            return new Line(tool, x0, z0, x1, z1, pack, family, level, variant, towers, gate, flip, mode, d, b, built);
        }

        public Line asBuilt() {
            return new Line(tool, x0, z0, x1, z1, pack, family, level, variant, towers, gate, flip, height, dy, base, true);
        }

        public int[][] ends() {
            return new int[][]{{x0, z0}, {x1, z1}};
        }

        public Line with(String family, int level, int variant, boolean towers, boolean gate) {
            return new Line(tool, x0, z0, x1, z1, pack, family, level, variant, towers, gate, flip, height, dy, base, built);
        }
    }

    /** Longest segment kept when reading a plan (longer ones are damaged or hostile files). */
    public static final int MAX_LINE = 256;

    /** Why an entry can't be built as planned. */
    public enum Conflict { NONE, OVERLAP_PLAN, OVERLAP_BUILDING, OUTSIDE }

    /** What Build all does with one planned item: send it, or keep it in the plan for a reason the player is told. */
    public enum Build { SEND, OUTSIDE, NO_ITEM, DECO_WINDOW, NEEDS_SERVER, NO_PIECES, LOADING, IN_FLIGHT }

    /**
     * @param outside  the entry is outside the colony's claims
     * @param haveItem survival: the hut item is in the inventory and its research is done
     * @return Build all's decision for a hut or decoration entry
     */
    public static Build decide(boolean outside, boolean creative, boolean server, boolean hut, boolean haveItem) {
        if (outside) return Build.OUTSIDE;
        if (creative) return Build.SEND;
        if (!hut) return server ? Build.SEND : Build.DECO_WINDOW;
        return haveItem ? Build.SEND : Build.NO_ITEM;
    }

    /**
     * @param bad     a piece is outside the claims or runs into a building
     * @param empty   no pieces laid out (still loading, or too short)
     * @param sending  its batches are already on their way
     * @param complete every blueprint of its layout was loaded (else pieces could be missing or a joint wrong)
     * @return Build all's decision for a road / wall segment (pieces always go through the server part)
     */
    public static Build decideLine(boolean server, boolean bad, boolean empty, boolean sending, boolean complete) {
        if (sending) return Build.IN_FLIGHT;
        if (!server) return Build.NEEDS_SERVER;
        if (!complete) return Build.LOADING;
        if (empty) return Build.NO_PIECES;
        return bad ? Build.OUTSIDE : Build.SEND;
    }

    public static final int VERSION = 1;

    private Plan() {
    }

    /** @return the plan as text: a version line, then one line per entry, fields separated by tabs */
    public static String write(List<Entry> entries) {
        return write(entries, List.of());
    }

    /** @return entries, then lines (each prefixed {@code L}) */
    public static String write(List<Entry> entries, List<Line> lines) {
        return write(entries, lines, java.util.Map.of());
    }

    /** @return entries, lines and the joint types chosen per joint cell (rows {@code J x z type}) */
    public static String write(List<Entry> entries, List<Line> lines, java.util.Map<Long, Integer> joints) {
        StringBuilder sb = new StringBuilder("mccitybuilder-plan\t" + VERSION + "\n");
        for (var en : new java.util.TreeMap<>(joints).entrySet())
            if (en.getValue() != J_AUTO) sb.append("J\t").append(cellX(en.getKey())).append('\t').append(cellZ(en.getKey())).append('\t').append(en.getValue()).append('\n');
        for (Line l : lines)
            sb.append(String.join("\t", "L3", l.tool(), "" + l.x0(), "" + l.z0(), "" + l.x1(), "" + l.z1(), l.pack(), l.family(), "" + l.level(),
                    "" + l.variant(), "" + l.towers(), "" + l.gate(), "" + l.flip(), "" + l.height(), "" + l.dy(), "" + l.base(), "" + l.built())).append('\n');
        for (Entry e : entries)
            sb.append(String.join("\t", e.isHut() ? e.hut() : "-", e.pack(), e.path(), "" + e.level(), "" + e.x(), "" + e.y(), "" + e.z(), "" + e.rot(),
                    "" + e.minX(), "" + e.minY(), "" + e.minZ(), "" + e.maxX(), "" + e.maxY(), "" + e.maxZ())).append('\n');
        return sb.toString();
    }

    /** @return the entries in {@code text}; unreadable lines (or a wrong header) are skipped, never thrown */
    public static List<Entry> read(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\n");
        // Plans saved under the mod's old name (ColonyPlanner) read the same.
        if (lines.length == 0 || !lines[0].startsWith("mccitybuilder-plan\t") && !lines[0].startsWith("colonyplanner-plan\t")) return out;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t");
            if (f.length != 14) continue;
            try {
                int[] n = new int[11];
                for (int k = 0; k < 11; k++) n[k] = Integer.parseInt(f[3 + k].trim());
                out.add(new Entry(f[0].equals("-") ? null : f[0], f[1], f[2], n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9], n[10]));
            } catch (NumberFormatException ignored) {
                // A damaged line: skip it, keep the rest.
            }
        }
        return out;
    }

    /**
     * @return the drawn lines in {@code text} (rows prefixed {@code L3}, or the older {@code L2} without flip / height /
     * built); damaged rows, unknown tools, out-of-range heights and segments
     * longer than {@link #MAX_LINE} are skipped (old {@code L} rows of the vanilla-block tools too)
     */
    public static List<Line> readLines(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null) return out;
        String[] rows = text.split("\n");
        if (rows.length == 0 || !rows[0].startsWith("mccitybuilder-plan\t") && !rows[0].startsWith("colonyplanner-plan\t")) return out;
        for (int i = 1; i < rows.length; i++) {
            String[] f = rows[i].split("\t");
            boolean v3 = f.length == 17 && f[0].equals("L3");
            if (!v3 && (f.length != 12 || !f[0].equals("L2")) || !f[1].equals("road") && !f[1].equals("wall")) continue;
            try {
                int x0 = Integer.parseInt(f[2]), z0 = Integer.parseInt(f[3]), x1 = Integer.parseInt(f[4]), z1 = Integer.parseInt(f[5]);
                if (Math.abs(x1 - x0) + Math.abs(z1 - z0) > MAX_LINE) continue;
                Line l = new Line(f[1], x0, z0, x1, z1, f[6], f[7], Integer.parseInt(f[8]), Integer.parseInt(f[9]), Boolean.parseBoolean(f[10]),
                        Boolean.parseBoolean(f[11].trim()));
                if (v3) {
                    int mode = Integer.parseInt(f[13]), dy = Integer.parseInt(f[14]);
                    int base = Integer.parseInt(f[15]);
                    if (mode < FOLLOW || mode > SMOOTH || Math.abs(dy) > 64 || base != NO_BASE && Math.abs(base) > 4096) continue;
                    l = new Line(l.tool(), x0, z0, x1, z1, l.pack(), l.family(), l.level(), l.variant(), l.towers(), l.gate(), Boolean.parseBoolean(f[12]),
                            mode, dy, base, Boolean.parseBoolean(f[16].trim()));
                }
                out.add(l);
            } catch (NumberFormatException ignored) {
                // A damaged row: skip it.
            }
        }
        return out;
    }

    /** @return a copy of the joint types with one cell set ({@link #J_AUTO} clears it); the given map is untouched */
    public static java.util.Map<Long, Integer> withJoint(java.util.Map<Long, Integer> types, long cell, int type) {
        java.util.Map<Long, Integer> out = new java.util.HashMap<>(types);
        if (type == J_AUTO) out.remove(cell);
        else out.put(cell, type);
        return out;
    }

    /** @return true when segments i and j run along the same axis (straight on through their shared end) */
    public static boolean collinearAt(List<Line> lines, int i, int j) {
        Line a = lines.get(i), b = lines.get(j);
        return (a.z0() == a.z1()) == (b.z0() == b.z1());
    }

    /**
     * Splits the segment that passes through cell {@code c} (not at its ends) into two meeting there, so a branch or a
     * segment ending on it gets a joint. The second half drops a gate. Locked segments (built, or on their way to the
     * server) are never split: their record must stay as it is.
     *
     * @return the index of the split segment (its second half follows it), or -1 when none passes through c
     */
    public static int split(List<Line> lines, int[] c, java.util.function.Predicate<Line> locked) {
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (locked.test(l)) continue;
            boolean onX = l.z0() == l.z1() && c[1] == l.z0() && c[0] > Math.min(l.x0(), l.x1()) && c[0] < Math.max(l.x0(), l.x1());
            boolean onZ = l.x0() == l.x1() && c[0] == l.x0() && c[1] > Math.min(l.z0(), l.z1()) && c[1] < Math.max(l.z0(), l.z1());
            if (!onX && !onZ) continue;
            lines.set(i, l.between(l.x0(), l.z0(), c[0], c[1]));
            Line rest = l.between(c[0], c[1], l.x1(), l.z1());
            lines.add(i + 1, rest.with(rest.family(), rest.level(), rest.variant(), rest.towers(), false));
            return i;
        }
        return -1;
    }

    /**
     * Edit mode: moves the given points (segment ends, a joint) by (dx, dz). Every segment ending at a moved point
     * follows: along its own axis it just gets longer or shorter; across it, the whole segment shifts, which needs its
     * other end free (no other segment there), else that component of the move is dropped. Segments keep their
     * direction and a length of at least 1; locked ones (built, sending) never move.
     *
     * @return the moved segments (same order), or null when nothing can move
     */
    public static List<Line> move(List<Line> lines, List<int[]> points, int dx, int dz, java.util.function.Predicate<Line> locked) {
        java.util.Set<Long> m = new java.util.HashSet<>();
        for (int[] p : points) m.add(cell(p[0], p[1]));
        java.util.Map<Long, Integer> ends = new java.util.HashMap<>();
        for (Line l : lines) for (int[] e : l.ends()) ends.merge(cell(e[0], e[1]), 1, Integer::sum);
        boolean allowX = true, allowZ = true;
        for (Line l : lines) {
            boolean a = m.contains(cell(l.x0(), l.z0())), b = m.contains(cell(l.x1(), l.z1()));
            if (!a && !b) continue;
            if (locked.test(l)) return null;
            if (a && b) continue;
            int[] other = a ? new int[]{l.x1(), l.z1()} : new int[]{l.x0(), l.z0()};
            boolean otherFree = ends.getOrDefault(cell(other[0], other[1]), 0) == 1;
            if (l.z0() == l.z1() && !otherFree) allowZ = false;
            if (l.x0() == l.x1() && !otherFree) allowX = false;
        }
        int mx = allowX ? dx : 0, mz = allowZ ? dz : 0;
        if (mx == 0 && mz == 0) return null;
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            boolean a = m.contains(cell(l.x0(), l.z0())), b = m.contains(cell(l.x1(), l.z1()));
            if (!a && !b) {
                out.add(l);
                continue;
            }
            // The moved end goes all the way; the other end only across the segment's axis (it shifts with it).
            int ox = l.x0() == l.x1() ? mx : 0, oz = l.z0() == l.z1() ? mz : 0;
            int x0 = l.x0() + (a ? mx : ox), z0 = l.z0() + (a ? mz : oz), x1 = l.x1() + (b ? mx : ox), z1 = l.z1() + (b ? mz : oz);
            boolean aligned = x0 == x1 || z0 == z1, same = Integer.signum(x1 - x0) == Integer.signum(l.x1() - l.x0()) && Integer.signum(z1 - z0) == Integer.signum(l.z1() - l.z0());
            if (!aligned || !same || x0 == x1 && z0 == z1) return null;
            out.add(l.between(x0, z0, x1, z1));
        }
        return out;
    }

    /** @return where each of the given points went: before and after are the same segments in the same order */
    public static java.util.Map<Long, int[]> moved(List<Line> before, List<Line> after, List<int[]> points) {
        java.util.Map<Long, int[]> to = new java.util.HashMap<>();
        for (int i = 0; i < before.size() && i < after.size(); i++)
            for (int k = 0; k < 2; k++) {
                int[] a = before.get(i).ends()[k], b = after.get(i).ends()[k];
                for (int[] p : points) if (p[0] == a[0] && p[1] == a[1]) to.put(cell(a[0], a[1]), b);
            }
        return to;
    }

    /** Drops joint types whose cell is no longer any segment's end. */
    public static void pruneJoints(java.util.Map<Long, Integer> joints, List<Line> lines) {
        java.util.Set<Long> ends = new java.util.HashSet<>();
        for (Line l : lines) for (int[] e : l.ends()) ends.add(cell(e[0], e[1]));
        joints.keySet().retainAll(ends);
    }

    /** @return how many segment ends there are at each cell */
    public static java.util.Map<Long, Integer> endCounts(List<Line> lines) {
        java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        for (Line l : lines) for (int[] e : l.ends()) out.merge(cell(e[0], e[1]), 1, Integer::sum);
        return out;
    }

    /** @return the segments connected to segment {@code start} through shared ends (branches, T's and loops included), sorted */
    public static List<Integer> connected(List<Line> lines, int start) {
        List<Integer> out = new ArrayList<>();
        if (start < 0 || start >= lines.size()) return out;
        boolean[] seen = new boolean[lines.size()];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(List.of(start));
        seen[start] = true;
        while (!q.isEmpty()) {
            int i = q.poll();
            out.add(i);
            for (int[] e : lines.get(i).ends())
                for (int j = 0; j < lines.size(); j++)
                    if (!seen[j])
                        for (int[] f : lines.get(j).ends())
                            if (f[0] == e[0] && f[1] == e[1]) {
                                seen[j] = true;
                                q.add(j);
                                break;
                            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** @return a segment's length in cells, both ends included */
    public static int cells(Line l) {
        return Math.max(Math.abs(l.x1() - l.x0()), Math.abs(l.z1() - l.z0())) + 1;
    }

    /**
     * A move that keeps every run whole: the delta nearest (dx, dz), within {@code max} of it, for which each segment
     * whose length changes and that has joints at both ends is still filled exactly by its straights (a segment with
     * a free end is re-fitted afterwards, {@link #refitFree}).
     *
     * @param offs per segment, the cells its joints / caps take at {start, end}; @param lens per segment, its straights
     * @return {dx, dz} to use, or null when no position within reach fits
     */
    public static int[] fitMove(List<Line> lines, List<int[]> points, int dx, int dz, java.util.function.Predicate<Line> locked, List<int[]> offs,
                                List<int[]> lens, int max) {
        List<int[]> cand = new ArrayList<>();
        for (int ex = dx - max; ex <= dx + max; ex++) for (int ez = dz - max; ez <= dz + max; ez++) cand.add(new int[]{ex, ez});
        cand.sort(java.util.Comparator.comparingInt(c -> Math.abs(c[0] - dx) + Math.abs(c[1] - dz)));
        for (int[] c : cand) {
            List<Line> m = move(lines, points, c[0], c[1], locked);
            if (m != null && allFit(lines, m, offs, lens)) return c;
        }
        return null;
    }

    /** @return true when every segment of {@code after} whose length changed and whose both ends are joints fits exactly */
    public static boolean allFit(List<Line> before, List<Line> after, List<int[]> offs, List<int[]> lens) {
        java.util.Map<Long, Integer> ends = endCounts(after);
        for (int i = 0; i < after.size() && i < before.size(); i++) {
            Line a = after.get(i);
            if (cells(a) == cells(before.get(i)) || i >= offs.size() || i >= lens.size()) continue;
            boolean fixed = ends.getOrDefault(cell(a.x0(), a.z0()), 0) > 1 && ends.getOrDefault(cell(a.x1(), a.z1()), 0) > 1;
            if (fixed && !LineSnap.exact(cells(a), offs.get(i)[0], offs.get(i)[1], lens.get(i))) return false;
        }
        return true;
    }

    /**
     * Re-fits segments with a free end so their straights fill them exactly: that end moves to the nearest length that
     * fits, growing by preference (up to {@code max}), else shrinking (never below one straight).
     *
     * @param which the segments to re-fit (indices)
     */
    public static List<Line> refitFree(List<Line> lines, java.util.Collection<Integer> which, List<int[]> offs, List<int[]> lens,
                                       java.util.function.Predicate<Line> locked, int max) {
        List<Line> out = new ArrayList<>(lines);
        java.util.Map<Long, Integer> ends = endCounts(lines);
        for (int i : which) {
            if (i < 0 || i >= out.size() || i >= offs.size() || i >= lens.size() || locked.test(out.get(i))) continue;
            Line l = out.get(i);
            boolean free1 = ends.getOrDefault(cell(l.x1(), l.z1()), 0) == 1, free0 = ends.getOrDefault(cell(l.x0(), l.z0()), 0) == 1;
            if (!free0 && !free1) continue;
            int n = cells(l), o0 = offs.get(i)[0], o1 = offs.get(i)[1];
            if (LineSnap.exact(n, o0, o1, lens.get(i))) continue;
            int grow = LineSnap.extension(n, o0, o1, lens.get(i), max), d = grow;
            if (grow < 0) {
                d = 0;
                for (int s = 1; s < n && d == 0; s++) if (LineSnap.exact(n - s, o0, o1, lens.get(i))) d = -s;
            }
            if (d == 0) continue;
            int dx = Integer.signum(l.x1() - l.x0()), dz = Integer.signum(l.z1() - l.z0());
            out.set(i, free1 ? l.between(l.x0(), l.z0(), l.x1() + dx * d, l.z1() + dz * d) : l.between(l.x0() - dx * d, l.z0() - dz * d, l.x1(), l.z1()));
        }
        return out;
    }

    /** @return the plan without one tool's segments that may still change (built and in-flight ones stay) */
    public static List<Line> clearTool(List<Line> lines, String tool, java.util.function.Predicate<Line> locked) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (!l.tool().equals(tool) || locked.test(l)) out.add(l);
        return out;
    }

    /** @return true when two inclusive boxes share a block */
    public static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5];
    }

    /**
     * @param buildings existing buildings' boxes (inclusive)
     * @param claimed   the colony's claimed chunks ({@link ColonyGeometry#chunk})
     * @return one conflict per entry, in order: outside the claims first, then overlapping a building, then another entry
     */
    public static List<Conflict> conflicts(List<Entry> entries, List<int[]> buildings, Set<Long> claimed) {
        List<Conflict> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Conflict c = Conflict.NONE;
            if (!ColonyGeometry.footprintClaimed(e.minX(), e.minZ(), e.maxX(), e.maxZ(), claimed)) c = Conflict.OUTSIDE;
            else {
                for (int[] b : buildings) if (overlaps(e.box(), b)) c = Conflict.OVERLAP_BUILDING;
                if (c == Conflict.NONE)
                    for (int j = 0; j < entries.size(); j++) if (j != i && overlaps(e.box(), entries.get(j).box())) c = Conflict.OVERLAP_PLAN;
            }
            out.add(c);
        }
        return out;
    }
}
