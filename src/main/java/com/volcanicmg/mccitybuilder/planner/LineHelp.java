package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * "How it works" for the road and wall tools: the help screen's pages (lang {@code mccitybuilder.help.<page>.title} and
 * {@code .1..n}, each with a small pixel diagram), its colour legend, and the first-use coach tips
 * ({@code mccitybuilder.tip.<id>}). Pure, so the client, the offline preview and SelfCheck share it.
 */
public final class LineHelp {
    /** Help screen size and padding, its text column width, the diagram's pixel scale (GUI px per cell). */
    public static final int W = 300, H = 250, PAD = 8, TEXT_W = 200, CELL = 2, LINE_H = 10;
    /** Coach tip box width and padding. */
    public static final int TIP_W = 170, TIP_PAD = 6;

    /** @param lines text lines (lang {@code .1..lines}); @param diagram rows of palette chars ({@link #color}), or none */
    public record Page(String id, int lines, String[] diagram) {
    }

    public static final List<Page> PAGES = List.of(
            new Page("pieces", 3, new String[]{
                    "................................",
                    "................................",
                    "ggggggggggggggggggggggggggggggg.",
                    "gssssssgssssssgssssssgssssssssg.",
                    "gssssssgssssssgssssssgssssssssg.",
                    "ggggggggggggggggggggggggggggggg.",
                    "................................",
                    ".........................cc.....",
                    "........................c..c....",
                    ".........................cc.....",
                    "................................"}),
            new Page("joints", 3, new String[]{
                    "gggggggggggggggggggg............",
                    "gssssssgsssssssjjjjg............",
                    "gssssssgsssssssjjjjg............",
                    "gggggggggggggggjjjjg............",
                    "...............gssg.............",
                    "...............gssg.............",
                    "...............gssg.....cccc....",
                    "...............gssg.....c..c....",
                    "...............gggg.....cccc....",
                    "................................"}),
            new Page("height", 2, new String[]{
                    "................................",
                    "gggggggggggggggggggggggggggggggg",
                    "gssssssssssssssssssssssssssssssg",
                    "gggggggggggggggggggggggggggggggg",
                    "..aaaa..........aaaaaaaa........",
                    "..a..a..........a......a........",
                    "..a..a......dddda......a....dddd",
                    "ddddddddddddd...dddddddddddddd..",
                    "................................"}),
            new Page("gaps", 3, new String[]{
                    "................................",
                    "ggggggggg.aaa.gggggggggrrrrrrr..",
                    "gsssssssg.a.a.gsssssssgr.....r..",
                    "gsssssssg.a.a.gsssssssgr.....r..",
                    "ggggggggg.aaa.gggggggggrrrrrrr..",
                    "................................"}),
            new Page("edit", 3, new String[]{
                    "................................",
                    "...o...........o...........o....",
                    "..o.o.........o.o.........o.o...",
                    "...oggggggggggggggggggggggo.....",
                    "....gsssssssssssssssssssssg.....",
                    "....gggggggggggggggggggggggg....",
                    "................................"}),
            new Page("building", 3, new String[]{
                    "................................",
                    "gggggggggggggggg.eeeeeeeeeeeeee.",
                    "gssssssssssssssg.essssssssssssse",
                    "gggggggggggggggg.eeeeeeeeeeeeee.",
                    "................................"}),
            new Page("colours", 0, null));

    /** The colour legend: lang {@code mccitybuilder.help.colours.<key>} and the swatch colour (as drawn in the world / UI). */
    public static final List<String> LEGEND = List.of("green", "red", "white", "cyan", "hover", "amber", "grey", "nodes");
    public static final int[] LEGEND_COLOURS = {0xFF4DFF4D, 0xFFFF4040, 0xFFFFFFFF, 0xFF40E6FF, 0xFFFFE680, 0xFFFFB31A, 0xFF8C8C8C, 0xFFF0A020};

    /** Coach tips, in order: id (lang {@code mccitybuilder.tip.<id>}) and where it is anchored ("centre", or "panel" = the line panel). */
    public static final List<String[]> TIPS = List.of(new String[]{"start", "centre"}, new String[]{"snap", "centre"}, new String[]{"corner", "centre"},
            new String[]{"close", "centre"}, new String[]{"edit", "panel"});

    private LineHelp() {
    }

    /** @return a diagram cell's colour (0 = none): g outline green, s piece, j joint, c cyan, a amber, r red, d ground, o node, e built grey */
    public static int color(char c) {
        return switch (c) {
            case 'g' -> 0xFF4DFF4D;
            case 's' -> 0xFF8B6B3E;
            case 'j' -> 0xFFB08850;
            case 'c' -> 0xFF40E6FF;
            case 'a' -> 0xFFFFB31A;
            case 'r' -> 0xFFFF4040;
            case 'd' -> 0xFF5E7F3A;
            case 'o' -> 0xFFF0A020;
            case 'e' -> 0xFF8C8C8C;
            default -> 0;
        };
    }

    /** @return text broken into lines no wider than {@code w} (words longer than a line stand alone) */
    public static List<String> wrap(String text, int w, ToIntFunction<String> width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (!cur.isEmpty() && width.applyAsInt(next) > w) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else cur = new StringBuilder(next);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** @return a page's text as lines in the text column, a blank line between its paragraphs */
    public static List<String> pageLines(Page p, Function<String, String> tr, ToIntFunction<String> width) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= p.lines(); i++) {
            if (i > 1) out.add("");
            out.addAll(wrap(tr.apply("mccitybuilder.help." + p.id() + "." + i), p.diagram() == null ? W - 2 * PAD : TEXT_W, width));
        }
        return out;
    }

    /** @return every lang key the help screen and tips use */
    public static List<String> langKeys() {
        List<String> out = new ArrayList<>(List.of("mccitybuilder.help.button", "mccitybuilder.help.title", "mccitybuilder.help.back", "mccitybuilder.help.next",
                "mccitybuilder.help.close", "mccitybuilder.help.replay", "mccitybuilder.help.page", "mccitybuilder.tip.next", "mccitybuilder.tip.skip",
                "mccitybuilder.tip.done", "mccitybuilder.tip.step"));
        for (Page p : PAGES) {
            out.add("mccitybuilder.help." + p.id() + ".title");
            for (int i = 1; i <= p.lines(); i++) out.add("mccitybuilder.help." + p.id() + "." + i);
        }
        for (String k : LEGEND) out.add("mccitybuilder.help.colours." + k);
        for (String[] t : TIPS) out.add("mccitybuilder.tip." + t[0]);
        return out;
    }
}
