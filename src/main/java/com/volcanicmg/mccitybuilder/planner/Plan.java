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
    public record Line(String tool, int x0, int z0, int x1, int z1, String pack, String family, int level, int variant, boolean towers,
                       boolean gate) {
        public boolean isWall() {
            return "wall".equals(tool);
        }

        public int[][] ends() {
            return new int[][]{{x0, z0}, {x1, z1}};
        }

        public Line with(String family, int level, int variant, boolean towers, boolean gate) {
            return new Line(tool, x0, z0, x1, z1, pack, family, level, variant, towers, gate);
        }
    }

    /** Longest segment kept when reading a plan (longer ones are damaged or hostile files). */
    public static final int MAX_LINE = 256;

    /** Why an entry can't be built as planned. */
    public enum Conflict { NONE, OVERLAP_PLAN, OVERLAP_BUILDING, OUTSIDE }

    public static final int VERSION = 1;

    private Plan() {
    }

    /** @return the plan as text: a version line, then one line per entry, fields separated by tabs */
    public static String write(List<Entry> entries) {
        return write(entries, List.of());
    }

    /** @return entries, then lines (each prefixed {@code L}) */
    public static String write(List<Entry> entries, List<Line> lines) {
        StringBuilder sb = new StringBuilder("mccitybuilder-plan\t" + VERSION + "\n");
        for (Line l : lines)
            sb.append(String.join("\t", "L2", l.tool(), "" + l.x0(), "" + l.z0(), "" + l.x1(), "" + l.z1(), l.pack(), l.family(), "" + l.level(),
                    "" + l.variant(), "" + l.towers(), "" + l.gate())).append('\n');
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
     * @return the drawn lines in {@code text} (rows prefixed {@code L2}); damaged rows, unknown tools and segments
     * longer than {@link #MAX_LINE} are skipped (old {@code L} rows of the vanilla-block tools too)
     */
    public static List<Line> readLines(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null) return out;
        String[] rows = text.split("\n");
        if (rows.length == 0 || !rows[0].startsWith("mccitybuilder-plan\t") && !rows[0].startsWith("colonyplanner-plan\t")) return out;
        for (int i = 1; i < rows.length; i++) {
            String[] f = rows[i].split("\t");
            if (f.length != 12 || !f[0].equals("L2") || !f[1].equals("road") && !f[1].equals("wall")) continue;
            try {
                int x0 = Integer.parseInt(f[2]), z0 = Integer.parseInt(f[3]), x1 = Integer.parseInt(f[4]), z1 = Integer.parseInt(f[5]);
                if (Math.abs(x1 - x0) + Math.abs(z1 - z0) > MAX_LINE) continue;
                out.add(new Line(f[1], x0, z0, x1, z1, f[6], f[7], Integer.parseInt(f[8]), Integer.parseInt(f[9]), Boolean.parseBoolean(f[10]),
                        Boolean.parseBoolean(f[11].trim())));
            } catch (NumberFormatException ignored) {
                // A damaged row: skip it.
            }
        }
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
