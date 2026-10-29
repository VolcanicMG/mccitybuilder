package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The road / wall tool's first-use coach tips ({@code mccitybuilder.tip.<id>}) and the word wrap the help uses. The
 * help itself is {@link Ponder}'s scenes. Pure, so the client, the offline preview and SelfCheck share it.
 */
public final class LineHelp {
    /** Coach tip box width and padding. */
    public static final int TIP_W = 170, TIP_PAD = 6;

    /** Coach tips, in order: id (lang {@code mccitybuilder.tip.<id>}) and where it is anchored ("centre", or "panel" = the line panel). */
    public static final List<String[]> TIPS = List.of(new String[]{"start", "centre"}, new String[]{"snap", "centre"}, new String[]{"corner", "centre"},
            new String[]{"close", "centre"}, new String[]{"edit", "panel"});

    private LineHelp() {
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

    /** @return every lang key the tips and the help screen's frame use */
    public static List<String> langKeys() {
        List<String> out = new ArrayList<>(List.of("mccitybuilder.help.button", "mccitybuilder.help.title", "mccitybuilder.tip.next", "mccitybuilder.tip.skip",
                "mccitybuilder.tip.done", "mccitybuilder.tip.step", "mccitybuilder.ponder.loading"));
        for (String b : new String[]{"back", "play", "pause", "next", "again", "replay", "close"}) out.add("mccitybuilder.ponder.btn." + b);
        for (String[] t : TIPS) out.add("mccitybuilder.tip." + t[0]);
        return out;
    }
}
