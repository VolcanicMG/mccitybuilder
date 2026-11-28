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
     * The pieces a run may be tiled with: those of the run's number (a level-1 road never takes a level-3 piece that
     * happens to share its width; Ethan's wide cobble pieces in a gravel road), unnumbered ones, and every piece of a
     * group whose numbers are sizes; all of the run's width.
     *
     * @param number the run's straight number, or -1 for mixed variants (any number of the width)
     */
    public static List<Piece> runPieces(List<Group> groups, int number, int width) {
        List<Piece> out = new ArrayList<>();
        for (Group g : groups) {
            // Numbers that are sizes (the group's pieces differ in length: acacia's straight4 / straight8) all count.
            boolean sizes = g.pieces().stream().mapToInt(Piece::run).distinct().count() > 1;
            for (Piece p : g.pieces())
                if (p.across() == width && (number < 0 || sizes || p.number() == 0 || p.number() == number)) out.add(p);
        }
        return out;
    }

    /** A family: tool ("wall" / "road"), key (folder, or folder + prefix) and its groups per role. */
    public record Family(String tool, String key, Map<Role, List<Group>> roles) {
        public List<Group> of(Role r) {
            return roles.getOrDefault(r, List.of());
        }

        /** @return straights, named ones before those only their shape made straights (S&K's arches), then longest first */
        public List<Group> straights() {
            List<Group> s = new ArrayList<>(of(Role.STRAIGHT));
            s.sort(Comparator.comparing((Group g) -> unreadable(g.base())).thenComparingInt(g -> -g.first().run()).thenComparing(Group::base));
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

    /**
     * @return "wall" / "road" for MineColonies' own folders (walls/, infrastructure/roads/, crossroads/), else null:
     * the 0.2 rule, for what must not change for decorations already placed (their ground offset, how they're removed)
     */
    public static String mineColoniesTool(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (p.startsWith("walls/") || p.contains("/walls/")) return "wall";
        if (p.contains("infrastructure/roads/") || p.contains("/crossroads/") || p.startsWith("roads/")) return "road";
        return null;
    }

    // Folder names that make a road (under infrastructure/) or wall set in packs that don't use MineColonies' folders.
    private static final java.util.Set<String> ROAD_DIRS = java.util.Set.of("road", "roads", "street", "streets", "boulevard", "boulevards",
            "highway", "highways", "path", "paths", "道路");
    private static final java.util.Set<String> WALL_DIRS = java.util.Set.of("walls", "rampart", "ramparts", "fortification", "fortifications",
            "palisade", "palisades", "retainingwall", "retainingwalls", "城墙");
    // Not set pieces though they sit in a road / wall folder (bridge, streetlight...; also the Chinese words).
    private static final String[] NOT_PIECES = {"bridge", "tunnel", "aqueduct", "railing", "streetlight", "lamp", "sewer", "pier", "桥", "路灯",
            "栏杆", "水道"};

    /**
     * @return "wall", "road", or null for a blueprint path outside the road / wall folders: MineColonies' folders
     * (walls/, infrastructure/roads/), else a folder named like a road under infrastructure/ (road, street, avenue...)
     * or like a wall anywhere (wall, rampart, palisade...), unless the path names a non-piece (bridge, streetlight...)
     */
    public static String tool(String path) {
        String t = mineColoniesTool(path);
        if (t != null) return t;
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (has(p, NOT_PIECES)) return null;
        String[] seg = p.split("/");
        boolean infra = false;
        for (int i = 0; i < seg.length - 1; i++) {
            // A bare "wall" folder under infrastructure/ is a building's (Rebel Base hangar walls); retaining walls say so.
            if (WALL_DIRS.contains(seg[i]) || seg[i].equals("wall") && !infra) return "wall";
            if (infra && (ROAD_DIRS.contains(seg[i]) || seg[i].startsWith("avenue"))) return "road";
            infra |= seg[i].equals("infrastructure");
        }
        return null;
    }

    /** @return the role a blueprint's name suggests (keywords, most specific first; a bracketed tag first of all) */
    public static Role role(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Role tag = tagRole(n);
        if (tag != null) return tag;
        if (has(n, "gate", "drawbridge", "barbican")) return Role.GATE;
        if (has(n, "inner", "insidecorner", "incorner", "cornerin", "insideturn")) return Role.INNER;
        if (has(n, "crossthree", "threeway", "three_way", "three-way")) return Role.TEE;
        if (has(n, "tjunction", "tcorner", "t_junction", "junction", "end_t") || token(n, "tee")) return Role.TEE;
        if (has(n, "intersection", "cross", "fourway", "four_way", "four-way", "十字")) return Role.CROSS;
        if (notInSet(n)) return Role.OTHER;
        if (has(n, "corner", "curve", "turn", "outer", "bend", "弯")) return Role.CORNER;
        if (has(n, "tower", "pillar", "watch")) return Role.TOWER;
        if (has(n, "stair", "climb", "step", "incline", "slope", "hill", "sloped", "↑", "楼梯") || tokens(n).anyMatch(t -> t.equals("up") || UP_N.matcher(t).lookingAt()) || UP.matcher(n).find()
                || n.contains("ramp") && !n.contains("rampart")) return Role.SLOPE;
        if (n.startsWith("end") || n.contains("_end") || n.contains("endroad")) return Role.END;
        if (has(n, "straight", "segment", "wall", "long", "oong", "short", "path", "walk", "section", "medium", "road", "palisade", "长", "短",
                "道路")) return Role.STRAIGHT;
        return Role.OTHER;
    }

    // "oneup", "threeup": a piece that climbs that many blocks.
    private static final java.util.regex.Pattern UP = java.util.regex.Pattern.compile("(one|two|three|four|five|six|seven|eight)up"),
            UP_N = java.util.regex.Pattern.compile("up(one|two|three|four|five|six|seven|eight)");

    // A name's words: runs of letters / digits ("tee_base": tee, base; "street" is one word).
    private static java.util.stream.Stream<String> tokens(String n) {
        return java.util.Arrays.stream(n.split("[^\\p{L}\\p{N}]+"));
    }

    private static boolean token(String n, String word) {
        return tokens(n).anyMatch(word::equals);
    }
    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("[\\[【]([^\\]】]*)[\\]】]");

    /** @return the role a bracketed tag gives ([━] straight, 【curve】 corner, [gate]), OTHER for a glyph it can't read, null without one */
    private static Role tagRole(String n) {
        var m = TAG.matcher(n);
        if (!m.find()) return null;
        String t = m.group(1);
        if (has(t, "╲", "╱", "grade separation", "立交")) return Role.OTHER;
        if (has(t, "gate", "\u57ce\u95e8")) return Role.GATE;
        if (has(t, "curve", "弯")) return Role.CORNER;
        if (has(t, "↑")) return Role.SLOPE;
        if (has(t, "long", "short", "长", "短") || !t.isEmpty() && t.chars().allMatch(c -> c == '━')) return Role.STRAIGHT;
        // Words or a number (【Bottom】, 【1】) say nothing; bare glyphs ([■], [∩]) are a shape only the blocks can tell.
        return t.codePoints().anyMatch(Character::isLetterOrDigit) ? null : Role.OTHER;
    }

    /** @return true when the name says the piece plays no role in a set: diagonals, offsets, shims, overpasses */
    public static boolean notInSet(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("diag") || n.startsWith("offset") || token(n, "ess") || n.contains("shim") || has(n, "╲", "╱", "grade separation", "立交");
    }

    // ---- shape: which sides a piece's body leaves through (bits 1 east +x, 2 south +z, 4 west, 8 north) ----

    /**
     * @param road true: the body is the walkable surface (columns topped within a block of the most common top
     *             height: no lamp, wall or kerb rising above it); false: the mass (columns topped at least half as
     *             high as the highest: an arch counts, a parapet's footing doesn't)
     * @return the piece's body seen from above, {@code [z * sx + x]}
     */
    public static boolean[] body(PieceLayout.Grid g, boolean road) {
        int sx = g.sx(), sz = g.sz();
        int[] top = new int[sx * sz], tops = new int[g.sy()];
        int maxTop = 0;
        for (int z = 0; z < sz; z++)
            for (int x = 0; x < sx; x++) {
                int t = -1;
                for (int y = 0; y < g.sy(); y++) if (g.id(x, y, z) > 0) t = y;
                top[z * sx + x] = t;
                if (t >= 0) tops[t]++;
                maxTop = Math.max(maxTop, t);
            }
        int mode = 0;
        for (int y = 1; y < tops.length; y++) if (tops[y] > tops[mode]) mode = y;
        boolean[] out = new boolean[sx * sz];
        for (int i = 0; i < out.length; i++) out[i] = road ? top[i] >= 0 && Math.abs(top[i] - mode) <= 1 : top[i] >= 0 && top[i] * 2 >= maxTop;
        return out;
    }

    /**
     * Which sides the body leaves the box through. Edge layers much sparser than the one inside them (merlons, posts)
     * are trimmed first. A side is open when the body covers a fifth of its edge (2+ cells) and nearly all of those
     * cells (the lowest quarter too) run at least half the box's depth inward: an arm. A face band along the side
     * (a T's bar, a corner's outer sides) runs deep only where another arm crosses it. A nearly full block is a
     * straight along its long side (square: unclear).
     *
     * @param body {@code [z * sx + x]}, as {@link #body}
     * @return open sides (bits 1 east +x, 2 south +z, 4 west, 8 north), or -1 when unclear: no body, or a full square;
     * {@link #THROUGH} for an S-jog that reads as neither a straight nor a full block
     */
    public static int openSides(boolean[] body, int sx, int sz) {
        int m = sides(body, sx, sz);
        // A straight (or a full block) stays one: the jog test only tells corners and odd shapes apart.
        return m == 5 || m == 10 || m < 0 || !through(body, sx, sz) ? m : THROUGH;
    }

    private static int sides(boolean[] body, int sx, int sz) {
        int[] lo = {0, 0}, hi = {sx - 1, sz - 1};
        for (int d = 0; d < 4; d++)
            for (int k = 0; k < 2 && hi[0] > lo[0] && hi[1] > lo[1]; k++) {
                int c0 = layer(body, sx, lo, hi, d, 0), c1 = layer(body, sx, lo, hi, d, 1);
                if (c0 == 0 || c0 * 2 >= c1) break;
                if (d == 0) hi[0]--;
                else if (d == 1) hi[1]--;
                else if (d == 2) lo[0]++;
                else lo[1]++;
            }
        int w = hi[0] - lo[0] + 1, h = hi[1] - lo[1] + 1, cells = 0;
        for (int z = lo[1]; z <= hi[1]; z++) for (int x = lo[0]; x <= hi[0]; x++) if (body[z * sx + x]) cells++;
        if (cells == 0) return -1;
        if (cells * 10 >= w * h * 9) return w == h ? -1 : w > h ? 1 | 4 : 2 | 8;
        int mask = 0;
        for (int d = 0; d < 4; d++) {
            int len = d % 2 == 0 ? h : w, depth = d % 2 == 0 ? w : h;
            List<Integer> runs = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                // How far in the body goes from this edge cell, over gaps of up to 2 (a lamp, a well in a crossing).
                int k = 0;
                for (int j = 0; j < depth && j - k <= 2; j++) if (at(body, sx, lo, hi, d, i, j)) k = j + 1;
                if (at(body, sx, lo, hi, d, i, 0)) runs.add(k);
            }
            if (runs.size() < 2 || runs.size() * 5 < len) continue;
            runs.sort(null);
            if (runs.get(runs.size() / 4) * 2 >= depth) mask |= 1 << d;
        }
        return mask;
    }

    /** {@link #openSides} of a body that enters one side and leaves the opposite one, moved sideways or not: an S-jog. */
    public static final int THROUGH = 16;

    /**
     * An S-jog (Shogun's and Nile's bend, bend_short and curve: the far run meets it 3 off as a corner): the body
     * crosses two opposite sides of the box with bands about as wide as each other (within 1), each narrower than that
     * side, touches the other two sides with fewer cells than either band (an L's arm is a band there too), and the
     * bands are offset sideways or the body bulges out of both other sides.
     */
    static boolean through(boolean[] body, int sx, int sz) {
        for (int ax = 0; ax < 2; ax++) {
            // ax 0: in through west, out through east; 1: north to south.
            int a = edge(body, sx, sz, ax == 0 ? 2 : 3), b = edge(body, sx, sz, ax == 0 ? 0 : 1);
            int c = edge(body, sx, sz, ax == 0 ? 1 : 0), d = edge(body, sx, sz, ax == 0 ? 3 : 2), side = ax == 0 ? sz : sx;
            if (a < 2 || b < 2 || Math.abs(a - b) > 1 || a >= side || b >= side || c >= Math.min(a, b) || d >= Math.min(a, b)) continue;
            // Moved sideways, or bulging out of both other sides (a short jog): a plain straight does neither.
            if (first(body, sx, sz, ax == 0 ? 2 : 3) != first(body, sx, sz, ax == 0 ? 0 : 1) || c > 0 && d > 0) return true;
        }
        return false;
    }

    // The first body cell along side d's edge (-1: none).
    private static int first(boolean[] body, int sx, int sz, int d) {
        int len = d % 2 == 0 ? sz : sx;
        for (int i = 0; i < len; i++) {
            int x = d == 0 ? sx - 1 : d == 2 ? 0 : i, z = d == 1 ? sz - 1 : d == 3 ? 0 : i;
            if (body[z * sx + x]) return i;
        }
        return -1;
    }

    // Body cells on the outer edge of side d (0 east, 1 south, 2 west, 3 north).
    private static int edge(boolean[] body, int sx, int sz, int d) {
        int n = 0, len = d % 2 == 0 ? sz : sx;
        for (int i = 0; i < len; i++) {
            int x = d == 0 ? sx - 1 : d == 2 ? 0 : i, z = d == 1 ? sz - 1 : d == 3 ? 0 : i;
            if (body[z * sx + x]) n++;
        }
        return n;
    }

    // Body cell at lateral i, depth k in from side d of the box lo..hi.
    private static boolean at(boolean[] body, int sx, int[] lo, int[] hi, int d, int i, int k) {
        int x = d == 0 ? hi[0] - k : d == 2 ? lo[0] + k : lo[0] + i, z = d == 1 ? hi[1] - k : d == 3 ? lo[1] + k : lo[1] + i;
        return body[z * sx + x];
    }

    private static int layer(boolean[] body, int sx, int[] lo, int[] hi, int d, int k) {
        int n = 0, len = d % 2 == 0 ? hi[1] - lo[1] + 1 : hi[0] - lo[0] + 1;
        for (int i = 0; i < len; i++) if (at(body, sx, lo, hi, d, i, k)) n++;
        return n;
    }

    /** @return the role open sides make: 2 opposite = straight, 2 adjacent = corner, 3 = T, 4 = cross, 1 = end; null when unclear */
    public static Role shapeRole(int sides) {
        if (sides <= 0) return null;
        if (sides == THROUGH) return Role.OTHER;
        return switch (Integer.bitCount(sides)) {
            case 1 -> Role.END;
            case 2 -> sides == 5 || sides == 10 ? Role.STRAIGHT : Role.CORNER;
            case 3 -> Role.TEE;
            default -> Role.CROSS;
        };
    }

    /** @return open sides as text, "E S" or "?" (logs and the piece scan) */
    public static String sidesName(int sides) {
        if (sides < 0) return "?";
        if (sides == THROUGH) return "jog";
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < 4; d++) if ((sides & 1 << d) != 0) sb.append("ESWN".charAt(d));
        return sb.isEmpty() ? "-" : sb.toString();
    }

    /**
     * Shape only reads names that say nothing readable: a bare glyph tag ([■], [∩]) or no letters at all. Names with
     * words keep their keyword role even when the shape disagrees: over the 22 bundled packs the shape contradicts
     * ~50 good names (thin walls read as crosses, lamp posts as straights), so it is a hint ({@link #shapeHint}), not a
     * correction.
     *
     * @param sides the piece's {@link #openSides}, or null when not known
     * @return the role a group plays: its name's, else (an unreadable name) its shape's
     */
    public static Role decide(String base, Integer sides) {
        Role named = role(base);
        Role shaped = sides == null ? null : shapeRole(sides);
        // A "corner" that runs through and out the opposite side moves the run sideways: no corner (Other tab).
        if (sides != null && sides == THROUGH && (named == Role.CORNER || named == Role.INNER)) return Role.OTHER;
        return shaped != null && named == Role.OTHER && unreadable(base) ? shaped : named;
    }

    /** @return the shape's role when it differs from the name's (a hint for the set-up screen and the log), else null */
    public static Role shapeHint(String base, Integer sides) {
        // A jog reading only says something when the name doesn't (a straight stair or a gate can read as one).
        if (sides != null && sides == THROUGH && !unreadable(base)) return null;
        Role shaped = sides == null ? null : shapeRole(sides), used = decide(base, sides);
        return shaped != null && shaped != used && used != Role.GATE && used != Role.TOWER && used != Role.SLOPE && !notInSet(base) ? shaped : null;
    }

    /** @return true when a name gives no role and has nothing the keyword reader can use: a bare glyph tag, or no letters */
    static boolean unreadable(String base) {
        String n = base.toLowerCase(Locale.ROOT);
        if (notInSet(n) || role(n) != Role.OTHER) return false;
        var m = TAG.matcher(n);
        if (m.find() && !m.group(1).codePoints().anyMatch(Character::isLetterOrDigit)) return true;
        return n.codePoints().noneMatch(Character::isLetter);
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
        return classify(pieces, Map.of());
    }

    /** @return families keyed "tool:key" with shapes ({@link #openSides} per piece path; missing: unknown) and no overrides */
    public static Map<String, Family> classify(List<Piece> pieces, Map<String, Integer> shapes) {
        return classify(pieces, shapes, Map.of());
    }

    /**
     * @param shapes    {@link #openSides} per piece path (missing: unknown); a group's role is {@link #decide}d from its
     *                  first piece's
     * @param overrides the set-up keys of the pack's overrides ({@link #toolOf}, {@link #PIECE}): folders / blueprints
     *                  turned road, wall or none; blueprints given a role, made a variant of another group, or excluded
     * @return families keyed "tool:key", in path order
     */
    public static Map<String, Family> classify(List<Piece> pieces, Map<String, Integer> shapes, Map<String, String> overrides) {
        // A variant joins its base's group: it takes that base name (followed along chains: A of B, B of C: C).
        Map<String, String> variantOf = new java.util.HashMap<>();
        for (Piece p : pieces) {
            String v = overrides.get(PIECE + p.path());
            if (v != null && v.startsWith(VARIANT) && v.length() > VARIANT.length()) variantOf.putIfAbsent(p.base(), v.substring(VARIANT.length()));
        }
        Map<String, Role> roleOf = new java.util.HashMap<>();
        List<Piece> eff = new ArrayList<>();
        for (Piece p : pieces) {
            String v = overrides.get(PIECE + p.path());
            if (EXCLUDED.equals(v)) continue;
            if (v != null && v.startsWith(VARIANT) && v.length() > VARIANT.length()) p = new Piece(p.path(), variantRoot(v.substring(VARIANT.length()), variantOf), p.number(), p.sx(), p.sy(),
                    p.sz(), p.axis());
            else if (v != null) for (Role r : Role.values()) if (r.name().equals(v)) roleOf.put(p.path(), r);
            eff.add(p);
        }
        pieces = eff;
        // Folder per piece, and the prefixes each folder uses (a folder with several prefixes is split by them).
        Map<String, java.util.Set<String>> prefixes = new TreeMap<>();
        for (Piece p : pieces) prefixes.computeIfAbsent(folder(p.path()), k -> new java.util.TreeSet<>()).add(style(p.base()));
        Map<String, Map<String, List<Piece>>> byFamily = new LinkedHashMap<>();
        for (Piece p : pieces) {
            String tool = toolOf(p.path(), overrides);
            if (tool == null) continue;
            String f = folder(p.path()), pre = style(p.base());
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
                Role r = null;
                for (Piece p : ps) if (r == null) r = roleOf.get(p.path());
                if (r == null) r = decide(g.getKey(), shapes.get(ps.get(0).path()));
                roles.computeIfAbsent(r, k -> new ArrayList<>()).add(new Group(g.getKey(), r, ps, levels));
            }
            // A wall "crossing" only its shape found that is a square block wider than the wall is a tower / bastion ([■]).
            if (tool.equals("wall") && roles.containsKey(Role.CROSS) && roles.containsKey(Role.STRAIGHT)) {
                int wallW = 0;
                for (Group g : roles.get(Role.STRAIGHT)) for (Piece p : g.pieces()) wallW = Math.max(wallW, p.across());
                for (Group g : List.copyOf(roles.get(Role.CROSS))) {
                    Piece f = g.first();
                    if (!unreadable(g.base()) || g.pieces().stream().anyMatch(p -> roleOf.containsKey(p.path())) || f.sx() != f.sz() || f.sx() <= wallW) continue;
                    roles.get(Role.CROSS).remove(g);
                    roles.computeIfAbsent(Role.TOWER, k -> new ArrayList<>()).add(new Group(g.base(), Role.TOWER, g.pieces(), g.levels()));
                }
                if (roles.get(Role.CROSS).isEmpty()) roles.remove(Role.CROSS);
            }
            // A family's straights all run along its longest straight's axis: a short piece may be shorter than the
            // road is wide (colonial roads: 8x5 and 4x5), so its longer side isn't its run.
            // All square: each piece keeps its own sides. Gates and end pieces that are square or the road's width along
            // that axis run the same way (a 7x7 gate is turned like its 7-wide wall).
            List<Group> sts = roles.get(Role.STRAIGHT);
            Piece longest = null;
            if (sts != null) for (Group g : sts) for (Piece p : g.pieces()) if (p.sx() != p.sz() && (longest == null || Math.max(p.sx(), p.sz()) > Math.max(longest.sx(), longest.sz()))) longest = p;
            if (longest != null) {
                int w = sharedWidth(sts);
                // Only when a square corner / T / cross piece is at least that wide (the joint spans the road).
                if (w > 0 && !jointWide(roles, w)) w = 0;
                // The road's width is the one size every straight has (Byzantine avenues: 12x12 and a 6x12 short): the
                // run is the other side, even when it is the shorter one. Else the longest straight's longer side.
                int ax = w > 0 && longest.sx() != longest.sz() ? (longest.sz() == w ? 0 : 1) : longest.sx() >= longest.sz() ? 0 : 1;
                // Walls whose straights' bodies all run across their longer side run that way (palisade: a 6-long log
                // wall on a 7-deep berm, laid crosswise before).
                Integer sa = w > 0 || !tool.equals("wall") ? null : shapeAxis(sts, shapes);
                if (sa != null) ax = sa;
                int wide = ax == 0 ? longest.sz() : longest.sx();
                for (Role r : List.of(Role.STRAIGHT, Role.GATE, Role.END)) {
                    if (!roles.containsKey(r)) continue;
                    List<Group> fixed = new ArrayList<>();
                    for (Group g : roles.get(r)) {
                        List<Piece> ps = new ArrayList<>();
                        for (Piece p : g.pieces()) {
                            boolean along = r == Role.STRAIGHT || p.sx() == p.sz() || (ax == 0 ? p.sz() : p.sx()) == wide;
                            ps.add(along ? new Piece(p.path(), p.base(), p.number(), p.sx(), p.sy(), p.sz(), ax) : p);
                        }
                        fixed.add(new Group(g.base(), g.role(), ps, g.levels()));
                    }
                    roles.put(r, fixed);
                }
            }
            out.put(en.getKey(), new Family(tool, en.getKey().substring(tool.length() + 1), roles));
        }
        return out;
    }

    /**
     * A square straight is as long as the road is wide, so with one in the family the width is known: the side every
     * straight shares with it. Without a square one the shared side may just as well be the run (birch's 4x10 and
     * 5x10 paths, Byzantine's 12x7 / 12x5 small walls), so none is given.
     *
     * @return that width, or 0 (no square straight, or no single side all straights share)
     */
    static int sharedWidth(List<Group> straights) {
        java.util.Set<Integer> common = null;
        boolean square = false;
        for (Group g : straights)
            for (Piece p : g.pieces()) {
                square |= p.sx() == p.sz();
                java.util.Set<Integer> s = new java.util.HashSet<>(List.of(p.sx(), p.sz()));
                if (common == null) common = s;
                else common.retainAll(s);
            }
        return square && common.size() == 1 ? common.iterator().next() : 0;
    }

    /** @return the axis every straight's {@link #openSides} runs along (0 x, 1 z), or null when one isn't a clear straight or they differ */
    static Integer shapeAxis(List<Group> straights, Map<String, Integer> shapes) {
        Integer ax = null;
        for (Group g : straights)
            for (Piece p : g.pieces()) {
                Integer s = shapes.get(p.path());
                int a = s == null ? -1 : s == 5 ? 0 : s == 10 ? 1 : -1;
                if (a < 0 || ax != null && ax != a) return null;
                ax = a;
            }
        return ax;
    }

    /** @return true when a corner, T or cross piece of the family is a square at least w wide (original cobble walls: a 10x10 corner on 9-wide walls) */
    static boolean jointWide(Map<Role, List<Group>> roles, int w) {
        for (Role r : List.of(Role.CORNER, Role.TEE, Role.CROSS))
            for (Group g : roles.getOrDefault(r, List.of())) for (Piece p : g.pieces()) if (p.sx() == p.sz() && p.sx() >= w) return true;
        return false;
    }

    /** @return the group a variant ends up in: its target, followed while that is a variant itself (a loop stops) */
    static String variantRoot(String base, Map<String, String> variantOf) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (variantOf.containsKey(base) && seen.add(base)) base = variantOf.get(base);
        return base;
    }

    /**
     * A planned line keeps its family key ("tool:key"); a set-up save or new name rules may rename the family.
     *
     * @param keys the pack's family keys now
     * @return the key itself while it exists, else the one family of the same tool and folder (a folder split by
     * styles or merged back counts as the same folder; another tool never does: a road line doesn't turn into a
     * wall), or null when none or several fit
     */
    public static String resolveFamily(String key, java.util.Collection<String> keys) {
        if (keys.contains(key)) return key;
        int c = key.indexOf(':');
        String tool = c < 0 ? "" : key.substring(0, c), part = key.substring(c + 1);
        List<String> fit = new ArrayList<>();
        for (String k : keys) {
            int kc = k.indexOf(':');
            String kp = k.substring(kc + 1);
            if (k.substring(0, Math.max(0, kc)).equals(tool) && (kp.equals(part) || kp.startsWith(part + "/") || part.startsWith(kp + "/"))) fit.add(k);
        }
        if (fit.size() == 1) return fit.get(0);
        // Only enclosing folders' families (walls/walls/tee folded into walls/walls, and walls): the nearest.
        String best = null;
        for (String k : fit) {
            String kp = k.substring(k.indexOf(':') + 1);
            if (!part.startsWith(kp + "/")) return null;
            if (best == null || kp.length() > best.length() - best.indexOf(':') - 1) best = k;
        }
        return best;
    }

    /** Override key prefixes: "tool|folder or blueprint path" to road / wall / none; "piece|blueprint path" to a role, a variant or excluded. */
    public static final String TOOL = "tool|", PIECE = "piece|", VARIANT = "variant:", EXCLUDED = "excluded", NONE = "none";

    /**
     * @return a blueprint's tool with the set-up overrides: the blueprint's own "tool|" key, else its nearest folder's
     * (road / wall, or none = not a set), else {@link #tool}
     */
    public static String toolOf(String path, Map<String, String> overrides) {
        for (String p = path; !p.isEmpty(); p = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "") {
            String v = overrides.get(TOOL + p);
            if (NONE.equals(v)) return null;
            if ("road".equals(v) || "wall".equals(v)) return v;
        }
        return tool(path);
    }

    /** @return the roles a full set of the tool has, in the order the set-up screen shows them */
    public static List<Role> slots(String tool) {
        return "wall".equals(tool) ? List.of(Role.STRAIGHT, Role.CORNER, Role.TEE, Role.CROSS, Role.END, Role.GATE, Role.TOWER)
                : List.of(Role.STRAIGHT, Role.CORNER, Role.TEE, Role.CROSS, Role.END);
    }

    /**
     * @param has whether the family has a group for a role (with the player's role picks applied)
     * @return the tool's {@link #slots} it lacks; what the planner does without each is the lang key
     * {@code mccitybuilder.sets.missing.<role>}
     */
    public static List<Role> missing(String tool, java.util.function.Predicate<Role> has) {
        List<Role> out = new ArrayList<>();
        for (Role r : slots(tool)) if (!has.test(r)) out.add(r);
        return out;
    }

    /** The roles the line tools place (slopes and unused groups stay out). */
    public static final java.util.Set<Role> TOOL_ROLES = java.util.EnumSet.of(Role.STRAIGHT, Role.CORNER, Role.INNER, Role.TEE, Role.CROSS, Role.END,
            Role.GATE, Role.TOWER);

    /**
     * @param groups the groups a family uses for a role (the player's role picks applied; {@code Family::of} without)
     * @return the blueprint paths the road / wall tools can place: every piece of a tool role in a family that has a
     * straight. A road / wall folder's other blueprints (slopes, unused, excluded pieces, families without a
     * straight) are the palette's Other tab.
     */
    public static java.util.Set<String> toolPieces(Map<String, Family> families, java.util.function.BiFunction<Family, Role, List<Group>> groups) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Family f : families.values()) {
            if (groups.apply(f, Role.STRAIGHT).isEmpty()) continue;
            for (Role r : TOOL_ROLES) for (Group g : groups.apply(f, r)) for (Piece p : g.pieces()) out.add(p.path());
        }
        return out;
    }

    /** Sub-folders named after a role (walls/corner, walls/gate, roads/straight...) belong to their parent's family. */
    private static final java.util.Set<String> ROLE_FOLDERS = java.util.Set.of("corner", "corners", "gate", "gates", "tower", "towers", "segment",
            "segments", "wall", "straight", "straights", "section", "sections", "long", "short", "medium", "intersection", "intersections",
            "junction", "junctions", "end", "ends", "stairs", "curve", "curves");

    /** @return the family folder of a piece: its folder, with trailing role-named sub-folders folded into the parent */
    public static String folder(String path) {
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

    /** @return the style a base name carries: its {@link #prefix}, else a style suffix (Byzantine's crossfourdio, straightbevel), or "" */
    static String style(String base) {
        String p = prefix(base);
        if (!p.isEmpty()) return p;
        for (String s : new String[]{"dio", "bevel"}) if (base.length() > s.length() && base.endsWith(s)) return s;
        return "";
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

    /**
     * @return the file name stem for a pack's settings: the name with characters outside [A-Za-z0-9._-] as "_", plus "-" and the
     * original name's hash (hex) when it has a non-ASCII character (ASCII names keep the plain form, so existing files stay), so two non-Latin names of one length never share a file
     */
    public static String fileName(String pack) {
        String s = pack.replaceAll("[^A-Za-z0-9._-]", "_");
        return pack.chars().allMatch(c -> c < 128) ? s : s + "-" + Integer.toHexString(pack.hashCode());
    }

    /** @return the pre-hash file name stem (sanitised only), for reading player files saved before {@link #fileName} */
    public static String legacyFileName(String pack) {
        return pack.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static final Map<String, Map<String, String>> BUILT_IN = new java.util.HashMap<>();

    /**
     * @return the mod's own overrides for a pack (joint turns and alignments fixed by hand, read before the search but
     * after the player's), from {@code /mccitybuilder/alignments/<pack name>.txt} in the overrides file format
     */
    public static synchronized Map<String, String> builtIn(String pack) {
        Map<String, String> done = BUILT_IN.get(pack);
        if (done != null) return done;
        // Only files that exist are cached: the server is asked with any pack name a client sends.
        try (var in = PieceSets.class.getResourceAsStream("/mccitybuilder/alignments/" + fileName(pack) + ".txt")) {
            if (in == null) return Map.of();
            Map<String, String> m = java.util.Collections.unmodifiableMap(readOverrides(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            BUILT_IN.put(pack, m);
            return m;
        } catch (java.io.IOException e) {
            return Map.of();
        }
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
