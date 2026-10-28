package com.volcanicmg.mccitybuilder.planner;

/** What the creative server paste does at one blueprint position. Pure. */
public final class PasteRules {
    /** PLACE as the blueprint says; SKIP leaves the world block; KEEP leaves a protected block and reports it. */
    public enum Action { PLACE, SKIP, KEEP }

    private PasteRules() {
    }

    /**
     * @param hutBlock    the position is the building's hut block (always left alone: block entity, inventory, data)
     * @param sameState   the world already has exactly the blueprint's block state there
     * @param protectedBl the world block is storage (container / item handler, empty or not) or a hut: never replaced
     */
    public static Action decide(boolean hutBlock, boolean sameState, boolean protectedBl) {
        if (hutBlock || sameState) return Action.SKIP;
        return protectedBl ? Action.KEEP : Action.PLACE;
    }

    /**
     * Deleting a decoration or a retired building: only blocks that still are the blueprint's go, and never a hut,
     * storage that still holds items, or a block an item-holding entity needs.
     *
     * @param sameState   the world block is exactly the blueprint's; @param protectedBl storage or a hut
     * @param emptied     the storage holds nothing (a moved building's chest, just copied); @param hut a hut block
     * @param entityKept  an entity with items stands on or hangs from it
     */
    public static boolean remove(boolean sameState, boolean protectedBl, boolean emptied, boolean hut, boolean entityKept) {
        if (!sameState || hut || entityKept) return false;
        return !protectedBl || emptied;
    }

    /** @return the key a built decoration / piece is recorded under: colony, normalised pack / path, anchor, rotation ordinal */
    public static String recordKey(int colony, String pack, String path, int x, int y, int z, int rot) {
        return colony + "|" + pack + "|" + (path == null ? "" : path.replace('\\', '/')) + "|" + x + "," + y + "," + z + "|" + rot;
    }

    /** A parsed {@link #recordKey}. */
    public record Rec(int colony, String pack, String path, int x, int y, int z, int rot) {
    }

    /** @return the record a key stands for, or null when it doesn't parse */
    public static Rec parseKey(String key) {
        String[] f = key.split("\\|", -1);
        if (f.length != 5) return null;
        String[] xyz = f[3].split(",");
        if (xyz.length != 3) return null;
        try {
            return new Rec(Integer.parseInt(f[0]), f[1], f[2], Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]), Integer.parseInt(f[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return a blueprint path in the one form records and requests use: forward slashes, no leading slash */
    public static String cleanPath(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    /** @return true when two footprint boxes {x0, y0, z0, x1, y1, z1} (inclusive) share any block: a move's source and target may not */
    public static boolean overlap(int[] a, int[] b) {
        return a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5];
    }

    /**
     * @return null when a client-sent place path is safe (relative, inside the pack, not a scan, a blueprint, and for
     * {@code level > 0} that level's file), else why not
     */
    public static String badPath(String path, int level) {
        if (path != null) path = path.replace('\\', '/');
        if (path == null || path.contains("..") || path.startsWith("/") || path.startsWith("\\") || path.contains(":") || path.startsWith("scans/"))
            return "unsafe path";
        if (!path.endsWith(".blueprint")) return "not a blueprint";
        if (level > 0 && !path.equals(HutCatalog.levelPath(path, level))) return "path is not level " + level;
        return null;
    }

    /**
     * @param why the server's log reason for refusing a single request
     * @return the answer's reason: "busy" when it may work a moment later (another paste running, the rate limit),
     * else "refused"
     */
    public static String refusal(String why) {
        return why.startsWith("rate limit") || why.startsWith("a paste is still running") || why.startsWith("another paste is running")
                || why.startsWith("a piece batch is running") ? "busy" : "refused";
    }
}
