package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * The road / wall help's scenes, Create-Ponder style: per topic a short script of steps, each a small plan (segments in
 * scene cells, laid out by the planner itself with the player's pieces) plus what to show over it: outline colours, a
 * cursor and its click, key caps and callouts anchored to scene points. Captions are lang keys
 * ({@code mccitybuilder.ponder.<topic>.<n>}). Pure data, so SelfCheck can check every script.
 * <p>
 * Coordinates are cells; a coordinate may use {@code L}, the family's longest straight ({@code 2L-1}, {@code L+3}).
 * A segment spec is {@code "x0 z0 x1 z1"} plus options: {@code ~} its far end moves out until its pieces fit exactly,
 * {@code gap} its far end moves out until some cells stay uncovered (shown without Fill gaps), {@code fill} (Fill gaps), a height mode {@code follow|flat|chain|smooth}, {@code dy=N}.
 */
public final class Ponder {
    /** How long a step plays by default (ms). */
    public static final int STEP_MS = 4500;

    /** A callout: a scene point (cells; y in blocks above the ground there) and its text (lang key). */
    public record Callout(String x, String z, int y, String text) {
    }

    /** One step: what the scene holds and shows while its caption is read. */
    public static final class Step {
        public final String caption;
        public final List<String> lines = new ArrayList<>();
        /** Per segment index: its outline colour (green when not listed): red, cyan, white, grey (built). */
        public final java.util.Map<Integer, String> colours = new java.util.HashMap<>();
        /** Joint types by cell ("x z" → {@link Plan#J_AUTO}..). */
        public final java.util.Map<String, Integer> joints = new java.util.LinkedHashMap<>();
        public final List<Callout> callouts = new ArrayList<>();
        public final List<String> keys = new ArrayList<>();
        /** The cursor's cell (null: none) and whether it clicks there. */
        public String[] cursor;
        public boolean click, nodes;
        /** The plate: "flat" or "slope" (rising one block every three cells east). */
        public String ground = "flat";

        Step(String caption) {
            this.caption = caption;
        }

        Step line(String... specs) {
            lines.addAll(List.of(specs));
            return this;
        }

        Step colour(int line, String c) {
            colours.put(line, c);
            return this;
        }

        Step joint(String cell, int type) {
            joints.put(cell, type);
            return this;
        }

        Step call(String x, String z, int y, String text) {
            callouts.add(new Callout(x, z, y, text));
            return this;
        }

        Step keys(String... k) {
            keys.addAll(List.of(k));
            return this;
        }

        Step cursor(String x, String z, boolean click) {
            cursor = new String[]{x, z};
            this.click = click;
            return this;
        }

        Step nodes() {
            nodes = true;
            return this;
        }

        Step slope() {
            ground = "slope";
            return this;
        }
    }

    /** A topic: its id (lang {@code mccitybuilder.ponder.<id>.title}), the tool its scenes use (null: the player's) and its steps. */
    public record Topic(String id, String tool, List<Step> steps) {
    }

    public static final List<Topic> TOPICS = new ArrayList<>();

    static {
        String c = "2L", d = "L+2";
        topic("draw", null,
                s("draw", 1).cursor("0", "0", true),
                s("draw", 2).line("0 0 2L-1 0").cursor("2L-1", "0", true).call("2L-1", "0", 5, "mccitybuilder.ponder.call.snap"),
                s("draw", 3).line("0 0 2L-1 0", "0 " + d + " 2L+2 " + d).keys("#shift").cursor("2L+2", d, true),
                s("draw", 4).line("0 0 2L 0 ~", "2L 0 2L L ~").cursor("2L", "L", true).call("2L", "0", 6, "mccitybuilder.ponder.call.corner"));
        topic("joints", null,
                s("joints", 1).line("0 0 2L 0", "2L 0 2L L ~").call(c, "0", 6, "mccitybuilder.ponder.call.corner"),
                s("joints", 2).line("0 0 2L 0", "2L 0 2L L ~", "2L 0 3L 0 ~").call(c, "0", 6, "mccitybuilder.ponder.call.tee"),
                s("joints", 3).line("0 0 2L 0", "2L 0 2L L ~", "2L 0 3L 0 ~", "2L 0 2L -L ~").call(c, "0", 6, "mccitybuilder.ponder.call.cross"),
                s("joints", 4).line("0 0 2L 0", "2L 0 2L L ~").joint("2L 0", Plan.J_TOWER).keys("C").call(c, "0", 7, "mccitybuilder.ponder.call.type"));
        topic("connect", null,
                s("connect", 1).line("2L 0 0 0", "0 0 0 L+2").cursor("2L", "0", false).call("2L", "0", 5, "mccitybuilder.ponder.call.open_end"),
                s("connect", 2).line("2L 0 0 0", "0 0 0 L+2", "0 L+2 2L L+2", "2L L+2 2L 0").colour(2, "cyan").colour(3, "cyan").cursor("2L", "0", false)
                        .call("2L", d, 5, "mccitybuilder.ponder.call.connect"),
                s("connect", 3).line("2L 0 0 0", "0 0 0 L+2", "0 L+2 2L L+2", "2L L+2 2L 0").colour(2, "cyan").colour(3, "cyan").keys("R").cursor("2L", "0", false),
                s("connect", 4).line("2L 0 0 0", "0 0 0 L+2", "0 L+2 2L L+2", "2L L+2 2L 0").cursor("2L", "0", true));
        topic("height", null,
                s("height", 1).slope().line("0 0 3L 0 follow").keys("H"),
                s("height", 2).slope().line("0 0 3L 0 flat").keys("H").call("0", "0", 3, "mccitybuilder.ponder.call.air"),
                s("height", 3).slope().line("0 0 3L 0 smooth").keys("H"),
                s("height", 4).slope().line("0 0 3L 0 chain", "3L 0 3L L chain ~").keys("H"),
                s("height", 5).slope().line("0 0 3L 0 flat dy=2").keys("#pgup", "/", "#pgdn"));
        topic("gaps", null,
                s("gaps", 1).line("0 0 2L+4 0 gap", "0 0 0 L ~", "2L+4 0 2L+4 L ~").call("L", "0", 6, "mccitybuilder.ponder.call.gap"),
                s("gaps", 2).line("0 0 2L+4 0 gap fill", "0 0 0 L ~", "2L+4 0 2L+4 L ~").keys("F").call("L", "0", 6, "mccitybuilder.ponder.call.filled"),
                s("gaps", 3).line("0 0 2L+4 0 gap", "0 0 0 L ~", "2L+4 0 2L+4 L ~", "L -4 L 4").colour(3, "red"),
                s("gaps", 4).line("0 0 2L+4 0 gap fill", "0 0 0 L ~", "2L+4 0 2L+4 L ~").nodes().keys("X"));
        topic("edit", null,
                s("edit", 1).line("0 0 2L 0", "2L 0 2L L ~").nodes().keys("X"),
                s("edit", 2).line("0 0 2L 0", "2L 0 2L 2L ~").nodes().cursor("2L", "2L", true),
                s("edit", 3).line("0 0 2L 0", "2L 0 2L 2L ~").colour(0, "cyan").colour(1, "cyan").keys("#tab"));
        topic("building", null,
                s("building", 1).line("0 0 2L 0", "2L 0 2L L ~"),
                s("building", 2).line("0 0 2L 0", "2L 0 2L L ~").colour(0, "grey").colour(1, "grey"),
                s("building", 3).line("0 0 2L 0", "2L 0 2L L ~").colour(0, "grey").colour(1, "grey").keys("#rmb").call("L", "0", 6, "mccitybuilder.ponder.call.locked"),
                s("building", 4).line("2L 0 2L L ~").colour(0, "grey").keys("#delete"));
        topic("roads", "road",
                s("roads", 1).line("0 0 2L 0", "2L 0 2L L ~"),
                s("roads", 2).line("0 0 2L 0", "2L 0 2L L ~").keys("G"),
                s("roads", 3).line("0 0 2L 0", "2L 0 2L L ~").colour(0, "white").keys("R"));
    }

    /** Pixel icons: 16x16 per topic, 12x12 for the player's buttons; {@link #iconColor} palette, '.' clear. */
    public static final java.util.Map<String, String[]> ICONS = new java.util.HashMap<>();
    /** The stage's mouse pointer, 10x16 (its tip the top left pixel): arrow, pressed. */
    public static final java.util.Map<String, String[]> CURSORS = new java.util.HashMap<>();
    /** Badges, 9x9, drawn at the pointer's lower right: move (a drag). */
    public static final java.util.Map<String, String[]> BADGES = new java.util.HashMap<>();

    static {
        // Topic icons and stage cursors: generated pixel art (see the repo's tools/ponder_icons.py), light from the top left.
        ICONS.put("draw", new String[]{"................", "................", "oooo..oooo..oooo", "olso..olso..olso", "olsSooolsSooolsS", "olllllllllllllso", "olsssssssssssSSo", "olsSSssssSSssssS", "olssssssssssssSo", "olsssSSssssSSsSo", "olssssssssssssSo", "olSsssssSsssssss", "oSSSSSSSSSSSSSSo", "oooooooooooooooo", "GgggggGgggggGggg", "................"});
        ICONS.put("joints", new String[]{"................", "oooooooooooooooo", "ollllobbbbollllo", "olsssobyybossSSo", "olsssobybBossSSo", "oSSSSobbBBoSSSSo", "oooooobBBBoooooo", ".....olssSo.....", ".....olssSo.....", ".....olssSo.....", ".....olsSSo.....", ".....olssSo.....", ".....olssSo.....", ".....oSSSSo.....", ".....oooooo.....", "................"});
        ICONS.put("connect", new String[]{"................", "oooooooooooooo..", "ollllllllllllo..", "olsssssssssSSo..", "olsooooooooooo..", "olso........cc..", "olso............", "olso........cc..", "olso............", "olso........cc..", "olso............", "olso........cc..", "oSSo............", "oooo.cc.cc.cc...", "................", "................"});
        ICONS.put("height", new String[]{"......oo........", ".....owwo.......", "....owwwwo......", "...owwwwwwo.....", "...ooowwooo.....", ".....owwo.......", ".....owwo.......", ".....oooo.......", "...........ooooo", "...........oggGo", "......oooooodddo", "......oggggGdddo", ".oooooodddddddDo", ".oggggGddddddDDo", ".oddddddddDDDDDo", ".ooooooooooooooo"});
        ICONS.put("gaps", new String[]{"................", "................", "................", "oooooo....oooooo", "ollllo....ollllo", "olsSSo....osssSo", "olsSSooooooosSSo", "olssSoyyyAosssSo", "olsSSoyaaAossSSo", "olssSoyaaAosssSo", "olsSSoAAAAossSSo", "oSSSSooooooSSSSo", "oooooo....oooooo", "................", "................", "................"});
        ICONS.put("edit", new String[]{"....o...........", "...oyo..........", "..oyyao.........", ".oyyaaao........", "oyyaaaAAo.......", ".oaaawAo........", "..oaAwwo........", "...oAwwwo..o....", "....owwwwoowo...", "....owwooowwwo..", ".....oo.owowowo.", ".......owwwwwwwo", "........owowowo.", ".........owwwo..", "..........owo...", "...........o...."});
        ICONS.put("building", new String[]{"................", ".oooooooooooooo.", ".owwwwwwwwwwwlo.", ".owlllllllllllS.", ".olllllllllllSo.", ".oSSSSSSSSSSSSo.", ".ooooobbBBooooo.", "......obbBo.....", "......obbBo.....", "......obbBo.....", "......obbBo.....", "oooooooooooooooo", "olllllllllllllSo", "olssSssssssSsssS", "oSSSSSSSSSSSSSSo", "oooooooooooooooo"});
        ICONS.put("roads", new String[]{"................", "ooooooo..ooo.ooo", "olswsSo..olo.olo", "olswsSo..olooolo", "olsssSo..ollllSo", "olsssSo..olssSSo", "olswsSo..olsSsso", "olswsSo..olssSSo", "olsssSo..olSssso", "olsssSo..olssSSo", "olswsSo..olsSsso", "olswsSo..olssSSo", "olsssSo..olsssso", "oSsssSo..oSSSSSo", "ooooooo..ooooooo", "................"});
        CURSORS.put("arrow", new String[]{"o.........", "oo........", "owo.......", "owwo......", "owwwo.....", "owwwwo....", "owwwwwo...", "owwwwwwo..", "owwwwwwwo.", "owwwwwoooo", "owwowwo...", "owo.owwo..", "oo..owwo..", "o....owwo.", ".....owwo.", "......oo.."});
        CURSORS.put("pressed", new String[]{"..........", "o.........", "oo........", "olo.......", "ollo......", "olllo.....", "ollllo....", "olllllo...", "ollllllo..", "olllllllo.", "ollllloooo", "ollolllo..", "olo.ollo..", "oo..ollo..", "o....ollo.", ".....oo..."});
        BADGES.put("move", new String[]{"....o....", "...owo...", "..owwwo..", ".owowowo.", "owwwwwwwo", ".owowowo.", "..owwwo..", "...owo...", "....o...."});
        ICONS.put("play", new String[]{
                "............", "...oo.......", "...owo......", "...owwo.....", "...owwwo....", "...owwwwo...",
                "...owwwwo...", "...owwwo....", "...owwo.....", "...owo......", "...oo.......", "............"});
        ICONS.put("pause", new String[]{
                "............", "..ooo..ooo..", "..owo..owo..", "..owo..owo..", "..owo..owo..", "..owo..owo..",
                "..owo..owo..", "..owo..owo..", "..owo..owo..", "..owo..owo..", "..ooo..ooo..", "............"});
        ICONS.put("back", new String[]{
                "............", ".......oo...", "......owo...", ".....owwo...", "....owwwo...", "...owwwwo...",
                "...owwwwo...", "....owwwo...", ".....owwo...", "......owo...", ".......oo...", "............"});
        ICONS.put("next", new String[]{
                "............", "...oo.......", "...owo......", "...owwo.....", "...owwwo....", "...owwwwo...",
                "...owwwwo...", "...owwwo....", "...owwo.....", "...owo......", "...oo.......", "............"});
        ICONS.put("again", new String[]{
                "............", "....oooo....", "...owwwwo.o.", "..owoooowowo", "..owo...owwo", "..owo..owwwo",
                "..owo..ooooo", "..owo.......", "..owoooowo..", "...owwwwo...", "....oooo....", "............"});
    }

    /** @return an icon pixel's colour (0 = clear): o outline, w white, l light, s / S stone, b / B wood, y / a / A amber, c / C cyan, g / G grass, d / D dirt */
    public static int iconColor(char c) {
        return switch (c) {
            case 'o' -> 0xFF202020;
            case 'w' -> 0xFFFFFFFF;
            case 'l' -> 0xFFC8C8C8;
            case 's' -> 0xFF8E8E8E;
            case 'S' -> 0xFF5C5C5C;
            case 'b' -> 0xFFB58A55;
            case 'B' -> 0xFF7A5530;
            case 'y' -> 0xFFFFE680;
            case 'a' -> 0xFFFFB31A;
            case 'A' -> 0xFFB07010;
            case 'c' -> 0xFF40E6FF;
            case 'C' -> 0xFF1E8FA8;
            case 'g' -> 0xFF6CB44A;
            case 'G' -> 0xFF3F7F2E;
            case 'd' -> 0xFF8B5E3C;
            case 'D' -> 0xFF5E3F27;
            default -> 0;
        };
    }

    /** @return a callout's accent colour from what it points at: amber gaps / air, cyan connect, grey built, else green */
    public static int calloutColor(String text) {
        if (text.endsWith(".gap") || text.endsWith(".air") || text.endsWith(".filled")) return 0xFFFFB31A;
        if (text.endsWith(".connect") || text.endsWith(".open_end")) return 0xFF40E6FF;
        if (text.endsWith(".locked")) return 0xFF8C8C8C;
        return 0xFF4DFF4D;
    }

    private Ponder() {
    }

    private static Step s(String topic, int n) {
        return new Step("mccitybuilder.ponder." + topic + "." + n);
    }

    private static void topic(String id, String tool, Step... steps) {
        TOPICS.add(new Topic(id, tool, List.of(steps)));
    }

    /** @return a coordinate's cells: a number, or terms of {@code L} (the longest straight) and numbers ({@code 2L-1}) */
    public static int coord(String s, int longest) {
        int sum = 0;
        for (String t : s.replace("-", "+-").split("\\+")) {
            if (t.isEmpty()) continue;
            if (t.endsWith("L")) {
                String k = t.substring(0, t.length() - 1);
                sum += (k.isEmpty() ? 1 : k.equals("-") ? -1 : Integer.parseInt(k)) * longest;
            } else sum += Integer.parseInt(t);
        }
        return sum;
    }

    /** A segment spec read: ends in cells, its options. */
    public record Seg(int x0, int z0, int x1, int z1, boolean fit, boolean fill, int height, int dy, boolean gap) {
    }

    /** @return the spec ({@link Ponder} class doc) with {@code longest} for L */
    public static Seg seg(String spec, int longest) {
        String[] t = spec.trim().split(" +");
        boolean fit = false, fill = false, gap = false;
        int height = Plan.FOLLOW, dy = 0;
        for (int i = 4; i < t.length; i++)
            switch (t[i]) {
                case "~" -> fit = true;
                case "fill" -> fill = true;
                case "gap" -> gap = true;
                case "follow" -> height = Plan.FOLLOW;
                case "flat" -> height = Plan.FLAT;
                case "chain" -> height = Plan.CHAIN;
                case "smooth" -> height = Plan.SMOOTH;
                default -> {
                    if (!t[i].startsWith("dy=")) throw new IllegalArgumentException("ponder spec: " + spec);
                    dy = Integer.parseInt(t[i].substring(3));
                }
            }
        return new Seg(coord(t[0], longest), coord(t[1], longest), coord(t[2], longest), coord(t[3], longest), fit, fill, height, dy, gap);
    }

    /** @return the plate's ground level at a cell: 0, or one block up every three cells east on a slope */
    public static int ground(String kind, int x, int z) {
        return kind.equals("slope") ? Math.max(0, Math.floorDiv(x, 3)) : 0;
    }

    /** @return every lang key the scenes use: topic titles, captions, callouts, key caps */
    public static List<String> langKeys() {
        List<String> out = new ArrayList<>();
        for (Topic t : TOPICS) {
            out.add("mccitybuilder.ponder." + t.id() + ".title");
            out.add("mccitybuilder.ponder." + t.id() + ".summary");
            for (Step s : t.steps()) {
                out.add(s.caption);
                for (Callout c : s.callouts) out.add(c.text());
                for (String k : s.keys) if (k.startsWith("#")) out.add("mccitybuilder.hint.key." + k.substring(1));
            }
        }
        return out.stream().distinct().toList();
    }
}
