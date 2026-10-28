package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sorts a style pack's road and wall blueprints into piece sets: families (a folder, split by an {@code xxx_} name
 * prefix when a folder mixes several, like original's cobble_ / fancy_), roles (straight, corner, T, cross, end,
 * gate, tower, slope) from name keywords, and numbered groups (segment1..5) that are levels when their footprints
 * differ and variants when they are the same. Pure: works on paths and footprints only.
 */
public final class PieceSets {
    public enum Role { STRAIGHT, CORNER, INNER, TEE, CROSS, END, GATE, TOWER, SLOPE, OTHER }

    /** One blueprint: pack sub-path, its base name (no digits), its number (0 = none) and its unrotated size. */
    /** @param axis the run's axis in the blueprint (0 along x, 1 along z), -1: the longer side */
    public record Piece(String path, String base, int number, int sx, int sy, int sz, int axis) {
        public Piece(String path, String base, int number, int sx, int sy, int sz) {
            this(path, base, number, sx, sy, sz, -1);
        }

        /** @return the length along the run */
        public int run() {
            return axis == 0 ? sx : axis == 1 ? sz : Math.max(sx, sz);
        }

        /** @return the width across the run */
        public int across() {
            return axis == 0 ? sz : axis == 1 ? sx : Math.min(sx, sz);
        }

        /** @return the direction the blueprint runs in unturned (0 east along x, 1 south along z) */
        public int nativeDir() {
            return axis >= 0 ? axis : sx >= sz ? 0 : 1;
        }
    }

    /** Pieces sharing a base name: levels (footprints differ) or variants (all the same footprint). */
    public record Group(String base, Role role, List<Piece> pieces, boolean levels) {
        public Piece first() {
            return pieces.get(0);
        }

        /**
         * @param like the line's straight as picked: a piece of this group with its number (the same level or variant)
         *             wins, so a variant wall's corner is that variant even when the corners' sizes make them levels
         * @return the piece with like's number, else {@link #pick}
         */
        public Piece pickAs(Piece like, int levelOrVariant, int seed) {
            if (like != null) for (Piece p : pieces) if (p.number() == like.number()) return p;
            return pick(levelOrVariant, seed);
        }

        /** @return the piece for a level (levels) or variant index (variants; -1 = picked by {@code seed}) */
        public Piece pick(int levelOrVariant, int seed) {
            if (levels) {
                Piece best = pieces.get(0);
                for (Piece p : pieces) if (p.number() <= Math.max(1, levelOrVariant)) best = p;
                return best;
            }
            int i = levelOrVariant < 0 ? Math.floorMod(seed * 31 + 7, pieces.size()) : Math.min(levelOrVariant, pieces.size() - 1);
            return pieces.get(i);
        }
    }

    /**
     * The pieces a run may be tiled with: those of the run's number only (a level-1 road never takes a level-3 piece
     * that happens to share its width; Ethan's wide cobble pieces in a gravel road), of its width.
     *
     * @param number the run's straight number, or -1 for mixed variants (any number of the width)
     */
    public static List<Piece> runPieces(List<Group> groups, int number, int width) {
        List<Piece> out = new ArrayList<>();
        for (Group g : groups) for (Piece p : g.pieces()) if (p.across() == width && (number < 0 || p.number() == number)) out.add(p);
        return out;
    }

    /** A family: tool ("wall" / "road"), key (folder, or folder + prefix) and its groups per role. */
    public record Family(String tool, String key, Map<Role, List<Group>> roles) {
        public List<Group> of(Role r) {
            return roles.getOrDefault(r, List.of());
        }

        /** @return straights, longest first */
        public List<Group> straights() {
            List<Group> s = new ArrayList<>(of(Role.STRAIGHT));
            s.sort(Comparator.comparingInt((Group g) -> -g.first().run()).thenComparing(Group::base));
            return s;
        }

        public boolean usable() {
            return !of(Role.STRAIGHT).isEmpty();
        }
    }

    private PieceSets() {
    }

    /** The order the piece preview browses a family's roles in. */
    public static final Role[] BROWSE = {Role.STRAIGHT, Role.CORNER, Role.INNER, Role.TEE, Role.CROSS, Role.END, Role.GATE, Role.TOWER};

    /**
     * @param pieces the pieces a line would use per role (several straights; one per other role)
     * @return them in {@link #BROWSE} order, each blueprint once
     */
    public static List<Piece> browse(Map<Role, List<Piece>> pieces) {
        List<Piece> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Role r : BROWSE) for (Piece p : pieces.getOrDefault(r, List.of())) if (seen.add(p.path())) out.add(p);
        return out;
    }

    /** @return "wall", "road", or null for a blueprint path outside the road / wall folders */
    public static String tool(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (p.startsWith("walls/") || p.contains("/walls/")) return "wall";
        if (p.contains("infrastructure/roads/") || p.contains("/crossroads/") || p.startsWith("roads/")) return "road";
        return null;
    }

    /** @return the role a blueprint's name suggests (keywords, most specific first) */
    public static Role role(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (has(n, "gate", "drawbridge", "barbican")) return Role.GATE;
        if (has(n, "inner", "insidecorner", "incorner")) return Role.INNER;
        if (has(n, "tjunction", "tcorner", "t_junction", "junction", "end_t")) return Role.TEE;
        if (has(n, "intersection", "cross")) return Role.CROSS;
        if (has(n, "corner", "curve", "turn", "outer")) return Role.CORNER;
        if (has(n, "tower", "pillar", "watch")) return Role.TOWER;
        if (has(n, "stair", "climb", "step", "incline", "slope", "hill", "sloped")) return Role.SLOPE;
        if (n.startsWith("end") || n.contains("_end") || n.contains("endroad")) return Role.END;
        if (has(n, "straight", "segment", "wall", "long", "oong", "short", "path", "walk", "section", "medium", "road", "palisade")) return Role.STRAIGHT;
        return Role.OTHER;
    }

    private static boolean has(String n, String... words) {
        for (String w : words) if (n.contains(w)) return true;
        return false;
    }

    /** @return {base name, number} of a file name: trailing digits split off (0 when there are none) */
    public static String[] split(String file) {
        String n = file.replace(".blueprint", "");
        int i = n.length();
        while (i > 0 && Character.isDigit(n.charAt(i - 1))) i--;
        // A long digit run (a timestamp, an id) is part of the name, not a level / variant number.
        if (n.length() - i > 6) i = n.length();
        return new String[]{n.substring(0, i), i < n.length() ? n.substring(i) : "0"};
    }

    /**
     * @param pieces every road / wall blueprint of one pack: {path, sx, sy, sz}
     * @return families keyed "tool:key", in path order
     */
    public static Map<String, Family> classify(List<Piece> pieces) {
        // Folder per piece, and the prefixes each folder uses (a folder with several prefixes is split by them).
        Map<String, java.util.Set<String>> prefixes = new TreeMap<>();
        for (Piece p : pieces) prefixes.computeIfAbsent(folder(p.path()), k -> new java.util.TreeSet<>()).add(prefix(p.base()));
        Map<String, Map<String, List<Piece>>> byFamily = new LinkedHashMap<>();
        for (Piece p : pieces) {
            String tool = tool(p.path());
            if (tool == null) continue;
            String f = folder(p.path()), pre = prefix(p.base());
            String key = prefixes.get(f).size() > 1 && !pre.isEmpty() ? f + "/" + pre : f;
            byFamily.computeIfAbsent(tool + ":" + key, k -> new TreeMap<>()).computeIfAbsent(p.base(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Family> out = new LinkedHashMap<>();
        for (var en : byFamily.entrySet()) {
            String tool = en.getKey().substring(0, en.getKey().indexOf(':'));
            Map<Role, List<Group>> roles = new LinkedHashMap<>();
            for (var g : en.getValue().entrySet()) {
                List<Piece> ps = new ArrayList<>(g.getValue());
                ps.sort(Comparator.comparingInt(Piece::number));
                boolean levels = false;
                for (Piece p : ps) levels |= p.sx() != ps.get(0).sx() || p.sy() != ps.get(0).sy() || p.sz() != ps.get(0).sz();
                Role r = role(g.getKey());
                roles.computeIfAbsent(r, k -> new ArrayList<>()).add(new Group(g.getKey(), r, ps, levels));
            }
            // A family's straights all run along its longest straight's axis: a short piece may be shorter than the
            // road is wide (colonial roads: 8x5 and 4x5), so its longer side isn't its run.
            List<Group> sts = roles.get(Role.STRAIGHT);
            if (sts != null) {
                Piece longest = null;
                for (Group g : sts) for (Piece p : g.pieces()) if (longest == null || Math.max(p.sx(), p.sz()) > Math.max(longest.sx(), longest.sz())) longest = p;
                int ax = longest.sx() >= longest.sz() ? 0 : 1;
                List<Group> fixed = new ArrayList<>();
                for (Group g : sts) {
                    List<Piece> ps = new ArrayList<>();
                    for (Piece p : g.pieces()) ps.add(new Piece(p.path(), p.base(), p.number(), p.sx(), p.sy(), p.sz(), ax));
                    fixed.add(new Group(g.base(), g.role(), ps, g.levels()));
                }
                roles.put(Role.STRAIGHT, fixed);
            }
            out.put(en.getKey(), new Family(tool, en.getKey().substring(tool.length() + 1), roles));
        }
        return out;
    }

    /** Sub-folders named after a role (walls/corner, walls/gate, roads/straight...) belong to their parent's family. */
    private static final java.util.Set<String> ROLE_FOLDERS = java.util.Set.of("corner", "corners", "gate", "gates", "tower", "towers", "segment",
            "segments", "wall", "straight", "straights", "section", "sections", "long", "short", "medium", "intersection", "intersections",
            "junction", "junctions", "end", "ends", "stairs", "curve", "curves");

    /** @return the family folder of a piece: its folder, with trailing role-named sub-folders folded into the parent */
    static String folder(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        String f = i < 0 ? "" : p.substring(0, i);
        while (f.contains("/") && ROLE_FOLDERS.contains(f.substring(f.lastIndexOf('/') + 1))) f = f.substring(0, f.lastIndexOf('/'));
        return f;
    }

    /** @return the {@code xxx_} prefix of a base name when it names a style (cobble_, fancy_), not a role (long_, corner_), or "" */
    public static String prefix(String base) {
        int i = base.indexOf('_');
        String p = i > 0 ? base.substring(0, i) : "";
        return p.isEmpty() || role(p) != Role.OTHER || ROLE_FOLDERS.contains(p) ? "" : p;
    }

    /** First line of an overrides file whose alignment shifts are relative to the joint box ({@link PieceLayout#worldShift}). */
    public static final String VERSION = "#v2";

    /** @return a pack's overrides file text: the version line, then one "key TAB value" line each (role groups, joint alignments) */
    public static String writeOverrides(Map<String, String> m) {
        StringBuilder sb = new StringBuilder(VERSION + "\n");
        m.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        return sb.toString();
    }

    /** @return the overrides in a file's text (damaged lines skipped), in file order */
    public static Map<String, String> readOverrides(String text) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (text != null)
            for (String line : text.split("\n")) {
                String[] kv = line.split("\t");
                if (kv.length == 2) m.put(kv[0], kv[1].trim());
            }
        return m;
    }

    /**
     * @return the overrides in a saved file's text: a file without the {@link #VERSION} line holds world shifts in its
     * alignments, so they are marked ",w" ({@link PieceLayout#parseAlign}) and used as they were until re-saved
     */
    public static Map<String, String> loadOverrides(String text) {
        Map<String, String> m = readOverrides(text);
        if (text != null && !text.startsWith(VERSION)) m.replaceAll((k, v) -> k.startsWith("align|") && v.split(",").length == 2 ? v + ",w" : v);
        return m;
    }

    /** @return an alignment's value: the unsaved draft if there is one, else the player's saved override, else the built-in one, else null */
    public static String alignLookup(Map<String, String> drafts, Map<String, String> saved, Map<String, String> builtIn, String key) {
        String v = drafts.get(key);
        if (v == null) v = saved.get(key);
        return v != null ? v : builtIn.get(key);
    }

    private static final Map<String, Map<String, String>> BUILT_IN = new java.util.HashMap<>();

    /**
     * @return the mod's own overrides for a pack (joint turns and alignments fixed by hand, read before the search but
     * after the player's), from {@code /mccitybuilder/alignments/<pack name>.txt} in the overrides file format
     */
    public static synchronized Map<String, String> builtIn(String pack) {
        return BUILT_IN.computeIfAbsent(pack, p -> {
            try (var in = PieceSets.class.getResourceAsStream("/mccitybuilder/alignments/" + p.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt")) {
                return in == null ? Map.of() : readOverrides(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                return Map.of();
            }
        });
    }

    /** @return the drafts that may be saved: all but those that would break blocks */
    public static Map<String, int[]> saveable(Map<String, int[]> drafts, java.util.Set<String> bad) {
        Map<String, int[]> out = new LinkedHashMap<>();
        drafts.forEach((k, v) -> {
            if (!bad.contains(k)) out.put(k, v);
        });
        return out;
    }
}
