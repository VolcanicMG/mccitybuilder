package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder-queue ordering. MineColonies hands out work orders by priority, highest first ({@code WorkManager} sorts
 * by priority reversed), so a list shown top to bottom maps to descending priorities. Pure.
 */
public final class QueueOrder {
    private QueueOrder() {
    }

    /** A work order as the queue panel sees it. */
    public record Entry(int id, int priority) {
    }

    /** @return entries in the order MineColonies would take them: priority descending, then id ascending */
    public static List<Entry> sorted(List<Entry> in) {
        List<Entry> out = new ArrayList<>(in);
        out.sort((a, b) -> a.priority != b.priority ? Integer.compare(b.priority, a.priority) : Integer.compare(a.id, b.id));
        return out;
    }

    /**
     * Moves the entry at {@code from} to {@code to} in the displayed (sorted) order and gives every entry a priority
     * that reproduces the new order (n for the top ... 1 for the bottom).
     *
     * @return work order id → new priority, only for entries whose priority changes (what must be sent)
     */
    public static Map<Integer, Integer> move(List<Entry> displayed, int from, int to) {
        List<Entry> list = new ArrayList<>(displayed);
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) return Map.of();
        list.add(to, list.remove(from));
        Map<Integer, Integer> changes = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            int p = list.size() - i;
            if (list.get(i).priority != p) changes.put(list.get(i).id, p);
        }
        return changes;
    }

    /** Builder stages in the order a build goes through them (MineColonies' {@code BuildingProgressStage}). */
    public static final java.util.List<String> STAGES = java.util.List.of("CLEAR", "BUILD_SOLID", "WEAK_SOLID", "CLEAR_WATER",
            "CLEAR_NON_SOLIDS", "DECORATE", "SPAWN");

    /** @return a rough 0..1 progress for a builder stage name (middle of the stage), or -1 when unknown / none */
    public static double stageFraction(String stage) {
        int i = stage == null ? -1 : STAGES.indexOf(stage);
        return i < 0 ? -1 : (i + 0.5) / STAGES.size();
    }

    /**
     * Free materials: the work order a mark funds. Order ids only grow ({@code WorkManager.topWorkOrderId}), so an
     * order older than the mark (someone else's at the same spot) is never taken.
     *
     * @param after the colony's highest work order id when the mark came
     * @param ids   the ids of the orders at the marked site
     * @param taken order ids other marks already fund
     * @return the first order made at the site after the mark (the lowest such id), or 0 while there is none
     */
    public static int fundedOrder(int after, Iterable<Integer> ids, java.util.Set<Integer> taken) {
        int best = 0;
        for (int id : ids) if (id > after && !taken.contains(id) && (best == 0 || id < best)) best = id;
        return best;
    }
}
