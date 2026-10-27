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

    /** Why an entry can't be built as planned. */
    public enum Conflict { NONE, OVERLAP_PLAN, OVERLAP_BUILDING, OUTSIDE }

    public static final int VERSION = 1;

    private Plan() {
    }

    /** @return the plan as text: a version line, then one line per entry, fields separated by tabs */
    public static String write(List<Entry> entries) {
        StringBuilder sb = new StringBuilder("mccitybuilder-plan\t" + VERSION + "\n");
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
