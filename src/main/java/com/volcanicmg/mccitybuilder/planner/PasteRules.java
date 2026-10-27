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
}
