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
 * A row is key caps then an action. Cap tokens: {@code #name} = a key cap labelled {@code mccitybuilder.hint.key.name},
 * {@code @toggle} = a cap with the planner key's current binding, {@code + / ,} = plain separators, anything else a
 * literal cap ({@code Z}). The action is the lang key {@code mccitybuilder.hint.<section>.<n>}. Only behaviour the planner
 * actually implements is listed.
 */
public final class Hints {
    /** Panel width (GUI px), padding, key-cap height, gap between rows, text line height. */
    public static final int WIDTH = 150, PAD = 7, CAP_H = 12, ROW_GAP = 3, LINE_H = 10, MAX_ROWS = 8;
    public static final List<String> SEPARATORS = List.of("+", "/", ",");

    public record Row(List<String> caps, String action) {
    }

    /** Section id → rows: "view" (camera) and one per state: "browse", "placing", "adjusting", "selected". */
    public static final Map<String, List<Row>> SECTIONS = new LinkedHashMap<>();

    static {
        section("view",
                caps("W", "A", "S", "D"),
                caps("#wheel"),
                caps("Q", "/", "E"),
                caps("#rdrag"),
                caps("V"),
                caps("@toggle", "/", "#esc"),
                caps("#shift", "+", "W", "A", "S", "D"));
        section("browse",
                caps("#click"),
                caps("#rmb"),
                caps("L"),
                caps("K"),
                caps("N"));
        section("placing",
                caps("#click"),
                caps("R", "/", "M"),
                caps("#shift", "+", "#wheel"),
                caps("G"),
                caps("#rmb", "/", "#esc"));
        section("adjusting",
                caps("#arrows"),
                caps("#drag"),
                caps("#shift", "+", "#wheel"),
                caps("R", "/", "M"),
                caps("G"),
                caps("#enter"),
                caps("#rmb", "/", "#esc"));
        section("planned",
                caps("#arrows"),
                caps("#pgup", "/", "#pgdn"),
                caps("R", "/", "M"),
                caps("[", "/", "]"),
                caps("#delete"),
                caps("#rmb", "/", "#esc"));
        section("road",
                caps("#click"),
                caps("#backspace"),
                caps("[", "/", "]"),
                caps("#enter"),
                caps("#esc"));
        section("selected",
                caps("U"),
                caps("P"),
                caps("O"),
                caps("#rmb", "/", "#esc"));
    }

    private Hints() {
    }

    private static List<String> caps(String... t) {
        return List.of(t);
    }

    @SafeVarargs
    private static void section(String id, List<String>... rows) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) out.add(new Row(rows[i], "mccitybuilder.hint." + id + "." + (i + 1)));
        SECTIONS.put(id, out);
    }

    /** @return every lang key the guide uses (titles, sections, caps, actions) */
    public static List<String> langKeys() {
        List<String> out = new ArrayList<>(List.of("mccitybuilder.hint.title", "mccitybuilder.hint.toggle"));
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
     * Lays out sections top to bottom inside {@code WIDTH - 2 * PAD}: a heading, then rows of caps followed by the
     * action, wrapped by words; continuation lines are indented.
     *
     * @param tr    resolves a lang key (and {@code @toggle}) to display text
     * @param width text width in GUI px (the vanilla font)
     */
    public static Laid layout(List<String> sectionIds, Function<String, String> tr, ToIntFunction<String> width) {
        List<Item> out = new ArrayList<>();
        int inner = WIDTH - 2 * PAD, y = 0;
        for (String id : sectionIds) {
            if (!out.isEmpty()) y += 4;
            out.add(new Item(0, y, inner, tr.apply("mccitybuilder.hint.section." + id), false, true));
            y += LINE_H + 1;
            for (Row r : SECTIONS.get(id)) {
                int x = 0;
                for (String c : r.caps()) {
                    String label = c.startsWith("#") ? tr.apply("mccitybuilder.hint.key." + c.substring(1)) : c.startsWith("@") ? tr.apply(c) : c;
                    boolean sep = SEPARATORS.contains(c);
                    int w = sep ? width.applyAsInt(label) + 2 : width.applyAsInt(label) + 6;
                    out.add(new Item(sep ? x + 1 : x, y, w - (sep ? 2 : 0), label, !sep, false));
                    x += w + (sep ? 0 : 1);
                }
                if (x > 0) x += 3;
                int lines = 0, lx = x;
                String[] words = tr.apply(r.action()).split(" ");
                // Caps too wide to leave room for the first word: the action starts on the next line.
                if (x > 0 && x + width.applyAsInt(words[0]) > inner) {
                    lines = 1;
                    lx = 8;
                }
                StringBuilder cur = new StringBuilder();
                for (String word : words) {
                    String next = cur.isEmpty() ? word : cur + " " + word;
                    if (!cur.isEmpty() && lx + width.applyAsInt(next) > inner) {
                        out.add(new Item(lx, y + 2 + lines * LINE_H, width.applyAsInt(cur.toString()), cur.toString(), false, false));
                        lines++;
                        lx = 8;
                        cur = new StringBuilder(word);
                    } else cur = new StringBuilder(next);
                }
                out.add(new Item(lx, y + 2 + lines * LINE_H, width.applyAsInt(cur.toString()), cur.toString(), false, false));
                y += Math.max(CAP_H, 2 + (lines + 1) * LINE_H) + ROW_GAP;
            }
        }
        return new Laid(out, y - ROW_GAP);
    }
}
