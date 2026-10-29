package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The planner's "Controls" guide: what each section says and how it is laid out in whole GUI pixels. Pure, so the
 * client and the offline preview share it and SelfCheck can verify it.
 * <p>
 * A row is key caps then one short action; essential rows make the compact guide, "All" shows every row of the state. Cap tokens: {@code #name} = a key cap labelled {@code mccitybuilder.hint.key.name},
 * {@code @toggle} = a cap with the planner key's current binding, {@code + / ,} = plain separators, anything else a
 * literal cap ({@code Z}). The action is the lang key {@code mccitybuilder.hint.<section>.<n>}. Only behaviour the planner
 * actually implements is listed.
 */
public final class Hints {
    /** Panel width (GUI px), padding, key-cap height, gap between rows, text line height, compact rows per section at most. */
    public static final int WIDTH = 178, PAD = 7, CAP_H = 12, ROW_GAP = 3, LINE_H = 10, MAX_ROWS = 8;
    /** Gap under a section heading, between sections, between the key and action columns; the key column's widest. */
    public static final int HEAD_GAP = 3, SECTION_GAP = 7, COL_GAP = 6, MAX_KEYS = 82;
    public static final List<String> SEPARATORS = List.of("+", "/", ",");

    /**
     * @param essential shown in the compact guide (the rest only with "All"); @param wallOnly only for the wall tool
     *                  (gate); @param gapsOnly only while a planned segment has uncovered cells (Fill gaps)
     */
    public record Row(List<String> caps, String action, boolean essential, boolean wallOnly, boolean gapsOnly) {
    }

    /** What the shown rows depend on besides the state: the line tool is walls, some segment has a gap. */
    public record Ctx(boolean wall, boolean gaps) {
        public static final Ctx NONE = new Ctx(false, false);
    }

    /**
     * Section id → rows, one per thing the player can be doing: "view" (camera, while browsing), "browse", "selected" /
     * "selected_deco", "placing" / "placing_deco", "adjusting", "planned" / "planned_deco", and the road / wall tool's
     * "line_idle", "line_draw", "line_connect", "line_edit", "line_edit_segment", "line_edit_built", "line_edit_joint".
     */
    public static final Map<String, List<Row>> SECTIONS = new LinkedHashMap<>();
    /** The road / wall tool's states: their headings name the tool ({@code mccitybuilder.hint.section.<id>.<road|wall>}). */
    public static final List<String> LINE_STATES = List.of("line_idle", "line_draw", "line_connect", "line_edit", "line_edit_segment", "line_edit_built", "line_edit_joint");

    static {
        // Flags first: "*" essential, "w" walls only, "f" only with gaps to fill. One action per row.
        section("view",
                row("*", "WASD"),
                row("*", "#wheel"),
                row("*", "Q", "/", "E"),
                row("#rdrag"),
                row("V"),
                row("#shift", "+", "WASD"),
                row("*", "@toggle", "/", "#esc"));
        section("browse",
                row("*", "#click"),
                row("*", "K"),
                row("*", "N"),
                row("*", "B"));
        section("selected",
                row("*", "U"),
                row("*", "P"),
                row("*", "O"),
                row("#click"),
                row("*", "#rmb", "/", "#esc"));
        section("selected_deco",
                row("*", "#click"),
                row("*", "#rmb", "/", "#esc"));
        String[][] placing = {{"*", "#click"}, {"*", "R"}, {"M"}, {"*", "#shift", "+", "#wheel"}, {"#pgup", "/", "#pgdn"}, {"G"}, {"*", "#rmb", "/", "#esc"}};
        section("placing", placing);
        section("placing_deco", placing);
        section("adjusting",
                row("*", "#arrows"),
                row("#shift", "+", "#arrows"),
                row("#drag"),
                row("*", "#shift", "+", "#wheel"),
                row("#pgup", "/", "#pgdn"),
                row("*", "R"),
                row("M"),
                row("G"),
                row("*", "#enter"),
                row("*", "#rmb", "/", "#esc"));
        section("planned",
                row("*", "#arrows"),
                row("*", "#pgup", "/", "#pgdn"),
                row("*", "R"),
                row("M"),
                row("[", "/", "]"),
                row("*", "#delete"),
                row("*", "#rmb", "/", "#esc"));
        section("planned_deco",
                row("*", "#arrows"),
                row("*", "#pgup", "/", "#pgdn"),
                row("*", "R"),
                row("M"),
                row("*", "#delete"),
                row("*", "#rmb", "/", "#esc"));
        section("line_idle",
                row("*", "#click"),
                row("*", "X"),
                row("*", "#backspace"),
                row("#delete"),
                row("C"),
                row("H"),
                row("#pgup", "/", "#pgdn"),
                row("[", "/", "]"),
                row("-", "/", "="),
                row("R"),
                row("w", "G"),
                row("*", "f", "F"),
                row("*", "#rmb", "/", "#esc"));
        section("line_draw",
                row("*", "#click"),
                row("*", "#shift"),
                row("*", "#backspace"),
                row("*", "C"),
                row("R"),
                row("H"),
                row("#pgup", "/", "#pgdn"),
                row("w", "G"),
                row("*", "f", "F"),
                row("*", "#rmb", "/", "#esc"));
        section("line_connect",
                row("*", "#click"),
                row("*", "R"),
                row("*", "#backspace"),
                row("*", "#rmb", "/", "#esc"));
        section("line_edit",
                row("*", "#click"),
                row("*", "#drag"),
                row("*", "#tab"),
                row("#alt", "+", "#click"),
                row("#delete"),
                row("*", "X"),
                row("*", "#esc"));
        section("line_edit_segment",
                row("*", "#drag"),
                row("*", "#delete"),
                row("*", "R"),
                row("H"),
                row("#pgup", "/", "#pgdn"),
                row("[", "/", "]"),
                row("-", "/", "="),
                row("w", "G"),
                row("*", "f", "F"),
                row("*", "#esc"));
        section("line_edit_built",
                row("*", "#delete"),
                row("*", "#esc"));
        section("line_edit_joint",
                row("*", "C"),
                row("*", "#drag"),
                row("*", "#esc"));
    }

    private Hints() {
    }

    // Caps of one row; leading flags: "*" essential, "w" walls only, "f" gaps only.
    private static String[] row(String... t) {
        return t;
    }

    private static void section(String id, String[]... rows) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            List<String> r = new ArrayList<>(List.of(rows[i]));
            boolean ess = false, wall = false, gaps = false;
            while (!r.isEmpty() && List.of("*", "w", "f").contains(r.get(0))) {
                String f = r.remove(0);
                ess |= f.equals("*");
                wall |= f.equals("w");
                gaps |= f.equals("f");
            }
            out.add(new Row(List.copyOf(r), "mccitybuilder.hint." + id + "." + (i + 1), ess, wall, gaps));
        }
        SECTIONS.put(id, out);
    }

    /** @return the section's rows shown: all, or the essential ones (compact); wall / gap rows only when they apply */
    public static List<Row> rows(String id, boolean all, Ctx ctx) {
        return SECTIONS.get(id).stream().filter(r -> (all || r.essential()) && (!r.wallOnly() || ctx.wall()) && (!r.gapsOnly() || ctx.gaps())).toList();
    }

    /** @return the lang key of a section's heading (the line tool's name the tool) */
    public static String title(String id, Ctx ctx) {
        return "mccitybuilder.hint.section." + id + (LINE_STATES.contains(id) ? ctx.wall() ? ".wall" : ".road" : "");
    }

    /** @return every lang key the guide uses (titles, sections, caps, actions, the All / Less toggle) */
    public static List<String> langKeys() {
        List<String> out = new ArrayList<>(List.of("mccitybuilder.hint.title", "mccitybuilder.hint.toggle", "mccitybuilder.hint.all", "mccitybuilder.hint.less",
                "mccitybuilder.hint.all.tip"));
        for (var en : SECTIONS.entrySet()) {
            if (LINE_STATES.contains(en.getKey())) {
                out.add("mccitybuilder.hint.section." + en.getKey() + ".wall");
                out.add("mccitybuilder.hint.section." + en.getKey() + ".road");
            } else out.add("mccitybuilder.hint.section." + en.getKey());
            for (Row r : en.getValue()) {
                out.add(r.action());
                for (String c : r.caps()) if (c.startsWith("#")) out.add("mccitybuilder.hint.key." + c.substring(1));
            }
        }
        return out.stream().distinct().toList();
    }

    /** One placed piece: a key cap, or text ({@code title} = a section heading). Positions relative to the content. */
    public record Item(int x, int y, int w, String text, boolean cap, boolean title) {
    }

    public record Laid(List<Item> items, int height) {
    }

    /**
     * Lays out sections top to bottom inside {@code WIDTH - 2 * PAD} in two columns: the key caps (the key column as
     * wide as the widest caps shown, at most {@link #MAX_KEYS}), then the action from one fixed x, wrapped by words with
     * every line starting there. Caps wider than the column put their action on the next line. One rhythm: a row is a
     * cap high (plus a line per wrap) and {@link #ROW_GAP} apart.
     *
     * @param all   every row, or only the essential ones
     * @param tr    resolves a lang key (and {@code @toggle}) to display text
     * @param width text width in GUI px (the vanilla font)
     */
    public static Laid layout(List<String> sectionIds, boolean all, Ctx ctx, Function<String, String> tr, ToIntFunction<String> width) {
        List<Item> out = new ArrayList<>();
        int inner = WIDTH - 2 * PAD, keys = 0;
        for (String id : sectionIds) for (Row r : rows(id, all, ctx)) keys = Math.max(keys, capsWidth(r, tr, width));
        int ax = Math.min(keys, MAX_KEYS) + COL_GAP, aw = inner - ax, y = 0;
        for (String id : sectionIds) {
            if (!out.isEmpty()) y += SECTION_GAP - ROW_GAP;
            out.add(new Item(0, y, inner, tr.apply(title(id, ctx)), false, true));
            y += LINE_H + HEAD_GAP;
            for (Row r : rows(id, all, ctx)) {
                int x = 0;
                for (String c : r.caps()) {
                    String label = label(c, tr);
                    boolean sep = SEPARATORS.contains(c);
                    int w = sep ? width.applyAsInt(label) + 4 : width.applyAsInt(label) + 6;
                    out.add(new Item(sep ? x + 2 : x, sep ? y + 2 : y, w - (sep ? 4 : 0), label, !sep, false));
                    x += w + (sep ? 0 : 1);
                }
                // Caps wider than the key column: the action goes under them.
                int line = x - 1 > ax - COL_GAP ? 1 : 0;
                StringBuilder cur = new StringBuilder();
                for (String word : tr.apply(r.action()).split(" ")) {
                    String next = cur.isEmpty() ? word : cur + " " + word;
                    if (!cur.isEmpty() && width.applyAsInt(next) > aw) {
                        out.add(new Item(ax, y + 2 + line * LINE_H, width.applyAsInt(cur.toString()), cur.toString(), false, false));
                        line++;
                        cur = new StringBuilder(word);
                    } else cur = new StringBuilder(next);
                }
                out.add(new Item(ax, y + 2 + line * LINE_H, width.applyAsInt(cur.toString()), cur.toString(), false, false));
                y += Math.max(CAP_H, 2 + (line + 1) * LINE_H) + ROW_GAP;
            }
        }
        return new Laid(out, y - ROW_GAP);
    }

    private static String label(String c, Function<String, String> tr) {
        return c.startsWith("#") ? tr.apply("mccitybuilder.hint.key." + c.substring(1)) : c.startsWith("@") ? tr.apply(c) : c;
    }

    /** @return the caps of a row laid side by side (GUI px) */
    private static int capsWidth(Row r, Function<String, String> tr, ToIntFunction<String> width) {
        int x = 0;
        for (String c : r.caps()) {
            boolean sep = SEPARATORS.contains(c);
            x += sep ? width.applyAsInt(label(c, tr)) + 4 : width.applyAsInt(label(c, tr)) + 7;
        }
        return Math.max(0, x - 1);
    }
}
