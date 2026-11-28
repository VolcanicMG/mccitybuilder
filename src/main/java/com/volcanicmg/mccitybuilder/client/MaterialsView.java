package com.volcanicmg.mccitybuilder.client;

import com.volcanicmg.mccitybuilder.planner.Costs;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.Materials;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.server.WarehouseStock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The plan's materials, client side: what the planned, unbuilt huts / decorations and road / wall pieces still need
 * (each blueprint's list through {@link Blueprints#cost}, the palette's own logic) against the player's inventory plus
 * the colony's warehouse racks ({@link WarehouseStock}, asked for when the planner shows it and then every
 * {@link #POLL_MS}). The totals are cached and worked out again only when the plan, the layout or a loaded blueprint
 * changes, a few new blueprints per pass, so no frame stalls.
 */
final class MaterialsView {
    /** Warehouse stock is asked for this often while the numbers are shown; the refresh button asks at most every {@link #MIN_GAP_MS}. */
    static final long POLL_MS = 10_000, MIN_GAP_MS = 2200;
    /** New blueprint material lists worked out per pass (each is a structure walk). */
    private static final int BUDGET = 3;

    static boolean open;
    static Materials.Tab tab = Materials.Tab.ALL;
    static int scroll;

    private static Map<String, Integer> roads = Map.of(), huts = Map.of();
    private static boolean pending;
    private static List<Object> key = List.of();
    private static long computedAt;
    // The last answer: the colony it is for, when it came, and whether any came (a server without the part never answers).
    private static Map<String, Integer> stock = Map.of();
    private static int stockColony = -1;
    private static long askedAt, stockAt;

    private MaterialsView() {
    }

    /** @return whether materials are consumed at all (not creative Instant, not Builders (free)): else nothing is shown */
    static boolean shown() {
        return !Planner.instant() && !Planner.funded();
    }

    /** @return whether the server answers warehouse stock requests */
    static boolean serverKnows() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && c.hasChannel(WarehouseStock.Query.TYPE);
    }

    /** Worked out again when the plan, the layout or the loaded blueprints changed (or a list was still loading half a second ago). */
    static void refresh() {
        long now = System.currentTimeMillis();
        List<Object> k = List.of(PlanView.layoutVersion, PlanView.entries.hashCode(), Blueprints.generation, PieceCatalog.loadVersion);
        if (k.equals(key) && (!pending || now - computedAt < 500)) return;
        key = k;
        computedAt = now;
        pending = false;
        int[] budget = {BUDGET};
        Map<String, Integer> r = Costs.newMap(), h = Costs.newMap();
        // Distinct blueprints with their counts, so each list is merged once.
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (int i = 0; i < PlanView.lines.size() && i < PlanView.linePieces.size(); i++) {
            Plan.Line l = PlanView.lines.get(i);
            if (l.built()) continue;
            for (PieceCatalog.Placed p : PlanView.linePieces.get(i)) counts.computeIfAbsent(l.pack() + "\n" + p.path(), x -> new int[1])[0]++;
        }
        pending |= merge(r, counts, budget);
        counts.clear();
        // A hut planned at level L is built through levels 1..L, each its own blueprint.
        for (Plan.Entry e : PlanView.entries)
            if (!e.path().isEmpty())
                for (String p : HutCatalog.levelPaths(e.path(), e.level(), e.isHut())) counts.computeIfAbsent(e.pack() + "\n" + p, x -> new int[1])[0]++;
        pending |= merge(h, counts, budget);
        roads = r;
        huts = h;
    }

    /** @return true when some list is still loading (or over budget): it is counted on a later pass */
    private static boolean merge(Map<String, Integer> into, Map<String, int[]> counts, int[] budget) {
        boolean waiting = false;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String[] pp = e.getKey().split("\n", 2);
            Map<String, Integer> m = Blueprints.costDone(pp[0], pp[1]);
            if (m == null && budget[0] > 0) {
                budget[0]--;
                m = Blueprints.cost(pp[0], pp[1]);
            }
            if (m == null) {
                waiting = true;
                continue;
            }
            for (Map.Entry<String, Integer> it : m.entrySet()) Costs.add(into, it.getKey(), it.getValue() * e.getValue()[0]);
        }
        return waiting;
    }

    /** @return the tab's rows against the player's inventory plus the warehouse stock */
    static Materials.View view(Materials.Tab t) {
        refresh();
        Map<String, Integer> have = new HashMap<>(PlannerScreen.inventory());
        stock.forEach((k, v) -> have.merge(k, v, Integer::sum));
        return Materials.view(roads, huts, t, have);
    }

    /** @return true while some blueprint's list isn't counted yet (the totals may grow) */
    static boolean loading() {
        return pending;
    }

    /** @return whether the warehouse stock has been answered for the open colony */
    static boolean stockKnown() {
        var c = Planner.colony();
        return c != null && stockColony == c.getID() && stockAt > 0;
    }

    /** Asks for the warehouse stock now and then while the numbers are shown. */
    static void poll() {
        var c = Planner.colony();
        if (c == null || !shown() || !serverKnows()) return;
        long now = System.currentTimeMillis();
        if (stockColony != c.getID()) {
            // Another colony: its stock is not this one's.
            stock = Map.of();
            stockColony = c.getID();
            stockAt = 0;
            askedAt = 0;
        }
        if (now - askedAt >= POLL_MS) ask(now);
    }

    /** The refresh button: asks again, at most every {@link #MIN_GAP_MS}. */
    static void forceRefresh() {
        long now = System.currentTimeMillis();
        if (Planner.colony() == null || !serverKnows() || !Materials.allowed(askedAt == 0 ? null : askedAt, now, MIN_GAP_MS)) return;
        ask(now);
    }

    private static void ask(long now) {
        askedAt = now;
        PacketDistributor.sendToServer(new WarehouseStock.Query(Planner.colony().getID()));
    }

    /** The server's answer. */
    static void onStock(WarehouseStock.Stock s) {
        var c = Planner.colony();
        var lv = Minecraft.getInstance().level;
        if (c == null || c.getID() != s.colony() || lv == null || !lv.dimension().location().toString().equals(s.dim())) return;
        stock = new HashMap<>(s.items());
        stockColony = s.colony();
        stockAt = System.currentTimeMillis();
    }

    /** @return a one-line dump of the state (the dev bridge reads it) */
    static String dump() {
        Materials.View all = view(Materials.Tab.ALL), r = view(Materials.Tab.ROADS), h = view(Materials.Tab.HUTS);
        return "shown=" + shown() + " open=" + open + " tab=" + tab + " need=" + all.need() + " missing=" + all.missing() + " roads=" + r.need() + " huts=" + h.need()
                + " items=" + all.rows().size() + " stock=" + stock.size() + " stockKnown=" + stockKnown() + " loading=" + pending;
    }
}
