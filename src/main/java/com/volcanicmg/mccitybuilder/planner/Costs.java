package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A blueprint's material list for the palette: biggest first, against what the player carries. Pure. */
public final class Costs {
    public enum Have { ENOUGH, PARTIAL, NONE }

    public record Row(String item, int need, int have, Have status) {
    }

    /** @param rows the first rows to show; @param more how many items did not fit */
    public record Summary(List<Row> rows, int more, int totalItems) {
    }

    private Costs() {
    }

    /** Adds a requirement, merging equal items. */
    public static void add(Map<String, Integer> need, String item, int count) {
        if (count > 0) need.merge(item, count, Integer::sum);
    }

    /**
     * @param need item id → count needed
     * @param have item id → count carried
     * @param cap  rows to show at most
     */
    public static Summary summarize(Map<String, Integer> need, Map<String, Integer> have, int cap) {
        List<Map.Entry<String, Integer>> all = new ArrayList<>(need.entrySet());
        all.sort((a, b) -> !a.getValue().equals(b.getValue()) ? Integer.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        List<Row> rows = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Integer> e : all) {
            total += e.getValue();
            if (rows.size() >= cap) continue;
            int h = have.getOrDefault(e.getKey(), 0);
            rows.add(new Row(e.getKey(), e.getValue(), h, h >= e.getValue() ? Have.ENOUGH : h > 0 ? Have.PARTIAL : Have.NONE));
        }
        return new Summary(rows, Math.max(0, all.size() - cap), total);
    }

    /** @return a fresh insertion-ordered map for {@link #add} */
    public static Map<String, Integer> newMap() {
        return new LinkedHashMap<>();
    }
}
