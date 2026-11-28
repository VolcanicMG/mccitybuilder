package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the plan still needs against what is at hand: totals per tab (road / wall pieces of planned, unbuilt lines;
 * huts and decorations), shortfall math and the rows of the Materials panel. Pure; the requirement lists themselves come
 * from {@link Costs} (the palette's logic), so the numbers agree.
 */
public final class Materials {
    public enum Tab { ALL, ROADS, HUTS }

    /** One item: how many are needed, at hand, and missing (never negative). */
    public record Row(String item, int need, int have, int missing) {
    }

    /** A tab's rows (most missing first), its item total and its shortfall total. */
    public record View(List<Row> rows, int need, int missing) {
    }

    private Materials() {
    }

    /** @return {@code a} and {@code b} summed per item into a new map */
    public static Map<String, Integer> merge(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = Costs.newMap();
        a.forEach((k, v) -> Costs.add(out, k, v));
        b.forEach((k, v) -> Costs.add(out, k, v));
        return out;
    }

    /** @return the tab's rows against {@code have}: missing first, then the larger need, then by name */
    public static View view(Map<String, Integer> roads, Map<String, Integer> huts, Tab tab, Map<String, Integer> have) {
        Map<String, Integer> need = tab == Tab.ROADS ? roads : tab == Tab.HUTS ? huts : merge(roads, huts);
        List<Row> rows = new ArrayList<>();
        int total = 0, missing = 0;
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int h = have.getOrDefault(e.getKey(), 0), m = Math.max(0, e.getValue() - h);
            rows.add(new Row(e.getKey(), e.getValue(), h, m));
            total += e.getValue();
            missing += m;
        }
        rows.sort((a, b) -> a.missing != b.missing ? Integer.compare(b.missing, a.missing) : a.need != b.need ? Integer.compare(b.need, a.need) : a.item.compareTo(b.item));
        return new View(rows, total, missing);
    }

    /** @param last when this player last asked (null = never) @return whether a request now is allowed, {@code gapMs} after the last one */
    public static boolean allowed(Long last, long now, long gapMs) {
        return last == null || now - last >= gapMs;
    }
}
