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
 * A row is key caps then one short action; essential rows make the compact guide, "All" shows every row. Cap tokens: {@code #name} = a key cap labelled {@code mccitybuilder.hint.key.name},
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

    /** @param essential shown in the compact guide (the rest only with "All") */
    public record Row(List<String> caps, String action, boolean essential) {
    }

    /** Section id → rows: "view" (camera) and one per state: "browse", "placing", "adjusting", "planned", "line", "selected". */
    public static final Map<String, List<Row>> SECTIONS = new LinkedHashMap<>();

    static {
        // "*" first: an essential row. One action per row.
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
                row("*", "#rmb"),
                row("*", "K"),
                row("*", "N"),
                row("*", "B"));
        section("placing",
                row("*", "#click"),
                row("*", "R"),
                row("M"),
                row("*", "#shift", "+", "#wheel"),
                row("G"),
                row("*", "#rmb", "/", "#esc"));
        section("adjusting",
                row("*", "#arrows"),
                row("#shift", "+", "#arrows"),
                row("#drag"),
                row("*", "#shift", "+", "#wheel"),
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
        section("line",
                row("*", "#click"),
                row("#shift"),
                row("*", "#backspace"),
                row("*", "#delete"),
                row("R"),
                row("*", "X"),
                row("H"),
                row("#pgup", "/", "#pgdn"),
                row("[", "/", "]"),
                row("-", "/", "="),
                row("*", "C"),
                row("G"),
                row("*", "#rmb", "/", "#esc"));
        section("selected",
                row("*", "U"),
                row("*", "P"),
                row("*", "O"),
                row("*", "#rmb", "/", "#esc"));
    }

    private Hints() {
    }

    // Caps of one row; a leading "*" marks it essential.
    private static List<String> row(String... t) {
        return List.of(t);
    }

    @SafeVarargs
    private static void section(String id, List<String>... rows) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            boolean ess = rows[i].get(0).equals("*");
            out.add(new Row(ess ? rows[i].subList(1, rows[i].size()) : rows[i], "mccitybuilder.hint." + id + "." + (i + 1), ess));
        }
        SECTIONS.put(id, out);
    }

    /** @return the section's rows shown: all, or the essential ones (compact) */
    public static List<Row> rows(String id, boolean all) {
        return SECTIONS.get(id).stream().filter(r -> all || r.essential()).toList();
    }

    /** @return every lang key the guide uses (titles, sections, caps, actions, the All / Less toggle) */
    public static List<String> langKeys() {
        List<String> out = new ArrayList<>(List.of("mccitybuilder.hint.title", "mccitybuilder.hint.toggle", "mccitybuilder.hint.all", "mccitybuilder.hint.less",
                "mccitybuilder.hint.all.tip"));
        for (var en : SECTIONS.entrySet()) {
            out.add("mccitybuilder.hint.section." + en.getKey());
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
    public static Laid layout(List<String> sectionIds, boolean all, Function<String, String> tr, ToIntFunction<String> width) {
        List<Item> out = new ArrayList<>();
        int inner = WIDTH - 2 * PAD, keys = 0;
        for (String id : sectionIds) for (Row r : rows(id, all)) keys = Math.max(keys, capsWidth(r, tr, width));
        int ax = Math.min(keys, MAX_KEYS) + COL_GAP, aw = inner - ax, y = 0;
        for (String id : sectionIds) {
            if (!out.isEmpty()) y += SECTION_GAP - ROW_GAP;
            out.add(new Item(0, y, inner, tr.apply("mccitybuilder.hint.section." + id), false, true));
            y += LINE_H + HEAD_GAP;
            for (Row r : rows(id, all)) {
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
