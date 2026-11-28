package com.volcanicmg.mccitybuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.Config;
import com.volcanicmg.mccitybuilder.planner.ColonyGeometry;
import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.planner.Roads;
import com.volcanicmg.mccitybuilder.planner.Route;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The road tool's Route mode: click a start (a road, a hut or any point), then an end; the planner samples the
 * terrain between them on the client thread (a bounded number of columns per frame), finds a road over it off the
 * thread ({@link Route}) and shows it as ghosts with Accept / Try another / Cancel. Accept drops its segments as one
 * undo step through the line tool ({@link LineView#dropRoute}); afterwards they are ordinary planned roads.
 */
final class RouteView {
    enum Phase { START, END, SAMPLING, ROUTING, PREVIEW, FAILED }

    /** Columns sampled per frame: the snapshot never stalls a frame. */
    static final int COLUMNS_PER_FRAME = 3000;
    // Margin around the ends' box the search may use at most (blocks).
    private static final int MARGIN_CAP = 48;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CityBuilder route");
        t.setDaemon(true);
        return t;
    });

    static boolean on;
    static Phase phase = Phase.START;
    static int[] from, to, hover;
    /** The route shown (start .. end) and its numbers; why there is none (lang key suffix). */
    static List<int[]> points = List.of();
    static String fail;
    static int length, gaps;
    // Routes shown before (Try another avoids them).
    private static final List<List<int[]>> tried = new ArrayList<>();
    // The snapshot being sampled: its box, arrays, the next column, and what the search is asked.
    private static int[] box;
    private static int[] ground;
    private static byte[] flags;
    private static int next;
    private static Route.Params params;
    private static Future<Route.Result> job;
    private static AtomicBoolean cancel = new AtomicBoolean();
    private static long laidAt;
    private static boolean laidComplete, anyBad;
    // The route's shared level in Flat chain mode (Plan.NO_BASE otherwise); what the plan and the options were when the snapshot began.
    private static int base = Plan.NO_BASE, stamp, restarts;

    private RouteView() {
    }

    /** The panel's Route button: on (the chain ends, edit mode off) or off. */
    static void toggle() {
        boolean was = on;
        reset();
        on = !was;
        if (on) {
            LineView.endChain();
            if (LineView.edit) LineView.toggleEdit();
            if (!Config.ROUTE_TIP_SEEN.get()) PlannerScreen.routeTip = true;
        }
    }

    /** Off and forgotten: a running search is cancelled, the preview goes. */
    static void reset() {
        cancelJob();
        on = false;
        restart();
    }

    /** Back to picking a start (the mode stays on). */
    static void restart() {
        cancelJob();
        phase = Phase.START;
        from = to = hover = null;
        points = List.of();
        fail = null;
        tried.clear();
        box = null;
        ground = null;
        flags = null;
        LineView.clearLive();
    }

    private static void cancelJob() {
        cancel.set(true);
        if (job != null) job.cancel(true);
        job = null;
        cancel = new AtomicBoolean();
    }

    /** @return true while a snapshot is sampled or the search runs ("Routing...") */
    static boolean busy() {
        return phase == Phase.SAMPLING || phase == Phase.ROUTING;
    }

    /** Each frame: the hovered start / end cell, sampling, the search's answer, the preview laid out once its pieces load. */
    static void aim(BlockHitResult hit) {
        hover = hit == null || phase != Phase.START && phase != Phase.END && phase != Phase.FAILED ? null : pickCell(hit);
        if (phase == Phase.SAMPLING) sample();
        if (phase == Phase.ROUTING && job != null && job.isDone()) answer();
        if (phase == Phase.PREVIEW && !laidComplete && System.currentTimeMillis() - laidAt > 500) layPreview();
    }

    /** A click in the world: the start, then the end (the search starts). @return false when ignored */
    static boolean click(BlockHitResult hit) {
        if (hit == null || LineView.family() == null) return false;
        if (phase == Phase.START || phase == Phase.FAILED) {
            restart();
            from = pickCell(hit);
            phase = Phase.END;
            return true;
        }
        if (phase == Phase.END) {
            int[] c = pickCell(hit);
            if (c[0] == from[0] && c[1] == from[1]) return false;
            to = c;
            restarts = 0;
            begin();
            return true;
        }
        return false;
    }

    /** Esc / right-click: a running search or a preview is cancelled (the plan unchanged), else back a step. @return false when the mode was already idle */
    static boolean back() {
        if (phase == Phase.START) return false;
        restart();
        return true;
    }

    /** Accept: the route's segments into the plan as one undo step; the mode stays on for the next route. */
    static boolean accept() {
        if (phase != Phase.PREVIEW || points.size() < 2) return false;
        List<int[]> pts = points;
        // A piece outside the claims or into a building (a corner wider than the road, say): refused, it would be built red.
        // The plan or the options changed since the search began: search again rather than drop a stale route.
        if (stamp != stamp()) {
            restarts = 0;
            tried.clear();
            begin();
            LineView.flash = "mccitybuilder.route.changed";
            LineView.flashUntil = System.currentTimeMillis() + 2500;
            return false;
        }
        if (anyBad) {
            LineView.flash = "mccitybuilder.route.bad";
            LineView.flashUntil = System.currentTimeMillis() + 2500;
            return false;
        }
        LineView.clearLive();
        if (!LineView.dropRoute(pts, base)) {
            layPreview();
            return false;
        }
        restart();
        return true;
    }

    /** Try another: the same ends, the routes shown so far made dearer. */
    static void another() {
        if (phase != Phase.PREVIEW && !(phase == Phase.FAILED && "no_other".equals(fail))) return;
        if (!points.isEmpty()) tried.add(points);
        LineView.clearLive();
        search();
    }

    // ---- snapshot and search ----

    /**
     * @return a hash of what a search depends on besides the terrain: the planned lines' geometry and set (not their
     * built / sending state, which a Build changes), the entries, the joint types at the route's ends, the road options
     */
    private static int stamp() {
        int h = java.util.Objects.hash(PlanView.entries, PlannerScreen.pack(), LineView.family(), LineView.level, LineView.variant, LineView.jointDefault,
                LineView.heightMode, LineView.dy);
        for (Plan.Line l : PlanView.lines) h = 31 * h + java.util.Objects.hash(l.tool(), l.x0(), l.z0(), l.x1(), l.z1(), l.pack(), l.family(), l.level(), l.variant());
        for (int[] c : new int[][]{from, to}) if (c != null) h = 31 * h + java.util.Objects.hashCode(PlanView.jointTypes.get(Plan.cell(c[0], c[1])));
        return h;
    }

    private static void begin() {
        int max = Config.ROUTE_MAX_LENGTH.get();
        if (Math.abs(from[0] - to[0]) + Math.abs(from[1] - to[1]) > max) {
            failed("too_far");
            return;
        }
        box = Route.box(from, to, Route.margin(from, to, max, MARGIN_CAP));
        ground = new int[box[2] * box[3]];
        flags = new byte[box[2] * box[3]];
        next = 0;
        stamp = stamp();
        markObstacles();
        phase = Phase.SAMPLING;
    }

    /** Buildings, planned huts and decorations, other planned lines (walls block, roads are this tool's) into the flags. */
    private static void markObstacles() {
        for (int[] b : PlanView.buildingBoxes()) mark(b[0], b[2], b[3], b[5], Route.BLOCKED);
        for (Plan.Entry e : PlanView.entries) {
            int[] b = e.box();
            mark(b[0], b[2], b[3], b[5], Route.BLOCKED);
        }
        for (int i = 0; i < PlanView.lines.size(); i++) {
            byte f = PlanView.lines.get(i).tool().equals("road") ? Route.ROAD : Route.BLOCKED;
            // A segment whose pieces aren't laid out yet (just planned, or still loading) is not skipped: its centreline at least.
            List<PieceCatalog.Placed> placed = i < PlanView.linePieces.size() ? PlanView.linePieces.get(i) : List.of();
            for (PieceCatalog.Placed p : placed) mark(p.box()[0], p.box()[1], p.box()[2], p.box()[3], f);
            if (placed.isEmpty()) {
                Plan.Line l = PlanView.lines.get(i);
                for (int[] c : Roads.centreline(List.of(new int[]{l.x0(), l.z0()}, new int[]{l.x1(), l.z1()}))) mark(c[0], c[1], c[0], c[1], f);
            }
        }
    }

    /** @return the search's flag at a cell (the dev bridge): bit 1 blocked, bit 2 road..., -1 outside the sampled box or before a search */
    static int flagAt(int x, int z) {
        if (flags == null || box == null || x < box[0] || z < box[1] || x >= box[0] + box[2] || z >= box[1] + box[3]) return -1;
        return flags[(z - box[1]) * box[2] + (x - box[0])];
    }

    private static void mark(int x0, int z0, int x1, int z1, byte f) {
        for (int z = Math.max(z0, box[1]); z <= Math.min(z1, box[1] + box[3] - 1); z++)
            for (int x = Math.max(x0, box[0]); x <= Math.min(x1, box[0] + box[2] - 1); x++) flags[(z - box[1]) * box[2] + (x - box[0])] |= f;
    }

    /** Samples the next columns: ground, water / lava, unloaded or unclaimed (blocked). */
    private static void sample() {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var claimed = Planner.claimed();
        int n = box[2] * box[3], stop = Math.min(n, next + COLUMNS_PER_FRAME);
        for (; next < stop; next++) {
            int x = box[0] + next % box[2], z = box[1] + next / box[2];
            Integer g = Planner.groundAt(x, z);
            if (g == null || !ColonyGeometry.footprintClaimed(x, z, x, z, claimed)) {
                ground[next] = g == null ? 64 : g;
                flags[next] |= Route.BLOCKED;
                continue;
            }
            ground[next] = g;
            var fluid = level.getFluidState(new BlockPos(x, g - 1, z));
            if (fluid.is(FluidTags.WATER)) flags[next] |= Route.WATER;
            else if (fluid.is(FluidTags.LAVA)) flags[next] |= Route.BLOCKED;
        }
        if (next >= n) {
            params = params();
            if (params == null) failed("no_pieces");
            else search();
        }
    }

    /** @return the search's parameters from the road family the tool draws with (null while its pieces load) */
    private static Route.Params params() {
        Plan.Line probe = LineView.next(from, to);
        PieceSets.Piece st = PieceCatalog.primary(probe, 0);
        int[] lens = PieceCatalog.runLengths(probe);
        if (st == null || lens.length == 0) return null;
        // The ends as the line tool would split them (a road's body: a T), for their arms and offsets.
        List<Plan.Line> split = new ArrayList<>(PlanView.lines);
        Plan.split(split, from, PlanView::locked);
        Plan.split(split, to, PlanView::locked);
        java.util.Set<Integer> armsFrom = Plan.arms(split, from), armsTo = Plan.arms(split, to), banEnd = new java.util.HashSet<>();
        for (int a : armsTo) banEnd.add((a + 2) % 4);
        int corner = PieceCatalog.expectedReach(probe, LineView.jointDefault), cap = PieceCatalog.reachOf(probe, PieceSets.Role.END);
        int offFrom = armsFrom.isEmpty() ? cap : PieceCatalog.expectedReach(probe, armsFrom.size() >= 2 ? Plan.J_TEE : LineView.jointDefault);
        int offTo = armsTo.isEmpty() ? cap : PieceCatalog.expectedReach(probe, armsTo.size() >= 2 ? Plan.J_TEE : LineView.jointDefault);
        return new Route.Params(from, to, st.across() / 2, lens, offFrom, offTo, corner, Config.ROUTE_MAX_LENGTH.get(), Config.ROUTE_TURN_COST.get(),
                Route.SLOPE, Route.STEEP, false, 0, Route.BRIDGE, List.of(), Route.AVOID, armsFrom, banEnd, armsFrom.isEmpty(), armsTo.isEmpty());
    }

    private static void search() {
        cancelJob();
        Route.Grid g = new Route.Grid(box[0], box[1], box[2], box[3], ground, flags);
        Route.Params p = new Route.Params(params.start(), params.end(), params.half(), params.lens(), params.offStart(), params.offEnd(), params.corner(),
                params.maxLen(), params.turnCost(), params.slopeCost(), params.steepCost(), params.bridges(), params.bridgeMax(), params.bridgeCost(),
                List.copyOf(tried), params.avoidCost(), params.banStart(), params.banEnd(), params.freeStart(), params.freeEnd());
        AtomicBoolean c = cancel;
        job = WORKER.submit(() -> Route.find(g, p, c::get));
        phase = Phase.ROUTING;
    }

    private static void answer() {
        Route.Result r;
        try {
            r = job.get();
        } catch (Exception e) {
            CityBuilder.LOGGER.warn("CityBuilder: route search failed", e);
            failed("no_route");
            return;
        }
        job = null;
        // The plan or the road options changed while it searched: the snapshot is stale, sample and search again, once
        // (then the result shows; Accept checks again).
        if (stamp != stamp() && restarts++ == 0) {
            tried.clear();
            begin();
            return;
        }
        if (r.fail() != null) {
            failed(r.fail());
            return;
        }
        for (List<int[]> t : tried)
            if (Route.same(t, r.points())) {
                failed("no_other");
                return;
            }
        points = fitEnds(new ArrayList<>(r.points()));
        length = 0;
        for (int k = 0; k + 1 < points.size(); k++) length += LineSnap.length(points.get(k), points.get(k + 1));
        fail = null;
        phase = Phase.PREVIEW;
        layPreview();
    }

    /**
     * The real layout's check of the search's estimate: a free end whose segment still leaves cells uncovered (its cap
     * or corner takes other than estimated) moves in by them, when the segment stays droppable. @return the points
     */
    private static List<int[]> fitEnds(List<int[]> pts) {
        for (int pass = 0; pass < 3; pass++) {
            Laid l = laid(pts);
            PieceCatalog.Layout lay = l.lay();
            int first = l.first(), n = pts.size() - 1;
            if (!lay.complete()) return pts;
            boolean moved = false;
            // The end first (a lone segment with both ends free keeps its start), then the start.
            for (int[] e : new int[][]{{n - 1, n, n - 1, params.freeEnd() ? 1 : 0}, {0, 0, 1, params.freeStart() && !(n == 1 && params.freeEnd()) ? 1 : 0}}) {
                int seg = e[0], k = e[1], toward = e[2], u = lay.shortened().get(first + seg);
                if (e[3] == 0 || u <= 0) continue;
                int cells = LineSnap.length(pts.get(seg), pts.get(seg + 1)) + 1;
                if (cells - u < lay.need().get(first + seg)) continue;
                int[] a = pts.get(k), b = pts.get(toward);
                pts.set(k, new int[]{a[0] + Integer.signum(b[0] - a[0]) * u, a[1] + Integer.signum(b[1] - a[1]) * u});
                moved = true;
                break;
            }
            if (!moved) return pts;
        }
        return pts;
    }

    private static void failed(String why) {
        fail = why;
        points = List.of();
        phase = Phase.FAILED;
        LineView.clearLive();
    }

    /** The route laid out with the plan (the ends split as the drop will): its pieces as the live ghosts. */
    private static void layPreview() {
        Laid l = laid(points);
        List<Plan.Line> all = l.all();
        PieceCatalog.Layout lay = l.lay();
        int first = l.first();
        List<PieceCatalog.Placed> ps = new ArrayList<>();
        gaps = 0;
        for (int k = first; k < all.size(); k++) {
            ps.addAll(lay.lines().get(k));
            gaps += lay.shortened().get(k);
        }
        int own = ps.size();
        int[] a = points.get(0), b = points.get(points.size() - 1);
        for (int k = 0; k < first; k++)
            if (onLine(all.get(k), a) || onLine(all.get(k), b)) for (PieceCatalog.Placed p : lay.lines().get(k)) if (!ps.contains(p)) ps.add(p);
        List<int[]> buildings = PlanView.buildingBoxes();
        List<Boolean> bad = new ArrayList<>();
        anyBad = false;
        for (int i = 0; i < ps.size(); i++) {
            PieceCatalog.Placed p = ps.get(i);
            boolean b0 = PlanView.bad(p, buildings);
            bad.add(b0);
            // Blocks Accept: the route's own pieces, and of the roads it joins only the pieces at its ends (the re-laid joint).
            if (b0 && (i < own || near(p.box(), a) || near(p.box(), b))) anyBad = true;
        }
        LineView.showPreview(ps, bad, a, b);
        laidAt = System.currentTimeMillis();
        laidComplete = lay.complete();
    }

    /** The plan with a route's segments added (its ends split as the drop will), laid out; {@code first}: the first route segment's index. */
    private record Laid(List<Plan.Line> all, int first, PieceCatalog.Layout lay) {
    }

    /**
     * Lays the route out with the plan. In Flat chain mode its segments share one level, the highest ground under any
     * of them ({@link #base}, what Accept drops them at), as a chain drawn by hand would end up.
     */
    private static Laid laid(List<int[]> pts) {
        List<Plan.Line> all = new ArrayList<>(PlanView.lines);
        Plan.split(all, pts.get(0), PlanView::locked);
        Plan.split(all, pts.get(pts.size() - 1), PlanView::locked);
        int first = all.size();
        for (int k = 0; k + 1 < pts.size(); k++) all.add(LineView.next(pts.get(k), pts.get(k + 1)));
        PieceCatalog.Layout lay = PieceCatalog.layout(all, PlanView.jointTypes);
        base = Plan.NO_BASE;
        if (LineView.heightMode != Plan.CHAIN) return new Laid(all, first, lay);
        for (int k = first; k < all.size(); k++) {
            int m = lay.heights().get(k)[1];
            if (m != Integer.MIN_VALUE) base = PieceLayout.chainBase(base, m);
        }
        if (base == Plan.NO_BASE) return new Laid(all, first, lay);
        for (int k = first; k < all.size(); k++) all.set(k, all.get(k).withHeight(Plan.CHAIN, all.get(k).dy(), base));
        return new Laid(all, first, PieceCatalog.layout(all, PlanView.jointTypes));
    }

    /** @return true when piece box {x0, z0, x1, z1} holds cell c or a cell next to it */
    private static boolean near(int[] box, int[] c) {
        return c[0] >= box[0] - 1 && c[0] <= box[2] + 1 && c[1] >= box[1] - 1 && c[1] <= box[3] + 1;
    }

    private static boolean onLine(Plan.Line l, int[] c) {
        return c[0] >= Math.min(l.x0(), l.x1()) && c[0] <= Math.max(l.x0(), l.x1()) && c[1] >= Math.min(l.z0(), l.z1()) && c[1] <= Math.max(l.z0(), l.z1());
    }

    /**
     * @return the cell a click means: a planned road's end, else onto a road's body (a T), else the clicked cell; one
     * inside a building's box moves out of it to the nearest side, clear of the road's half width (its door side is unknown)
     */
    static int[] pickCell(BlockHitResult hit) {
        double cx = hit.getLocation().x, cz = hit.getLocation().z, r = LineView.radius(hit);
        List<int[][]> segs = new ArrayList<>();
        for (Plan.Line l : PlanView.lines) if (l.tool().equals("road")) segs.add(l.ends());
        int[] e = LineSnap.nearEndpoint(cx, cz, segs, r);
        if (e != null) return e.clone();
        int[] best = null;
        double bd = r * r;
        for (int[][] s : segs)
            for (int[] c : Roads.centreline(List.of(s[0], s[1]))) {
                double d = (c[0] + 0.5 - cx) * (c[0] + 0.5 - cx) + (c[1] + 0.5 - cz) * (c[1] + 0.5 - cz);
                if (d <= bd) {
                    bd = d;
                    best = c;
                }
            }
        if (best != null) return best.clone();
        int[] c = {(int) Math.floor(cx), (int) Math.floor(cz)};
        PieceSets.Piece st = PieceCatalog.primary(LineView.next(c, c), 0);
        int out = (st == null ? 2 : st.across() / 2) + 1;
        for (int[] b : PlanView.buildingBoxes())
            if (c[0] >= b[0] && c[0] <= b[3] && c[1] >= b[2] && c[1] <= b[5]) {
                int[] d = {c[0] - b[0], b[3] - c[0], c[1] - b[2], b[5] - c[1]};
                int k = 0;
                for (int i = 1; i < 4; i++) if (d[i] < d[k]) k = i;
                return switch (k) {
                    case 0 -> new int[]{b[0] - out, c[1]};
                    case 1 -> new int[]{b[3] + out, c[1]};
                    case 2 -> new int[]{c[0], b[2] - out};
                    default -> new int[]{c[0], b[5] + out};
                };
            }
        return c;
    }

    /** @return the number of turns in the shown route */
    static int turns() {
        return Math.max(0, points.size() - 2);
    }

    /** The route's centreline (cyan), its start and end cells, the hovered cell (white). */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        for (int k = 0; k + 1 < points.size(); k++) {
            int[] a = points.get(k), b = points.get(k + 1);
            LineView.segment(pose, lines, a[0] + 0.5f, a[1] + 0.5f, b[0] + 0.5f, b[1] + 0.5f, LineView.groundY(a[0], a[1]) + 1.2f, 0.9f);
        }
        for (int[] c : new int[][]{from, to, hover}) {
            if (c == null) continue;
            int y = LineView.groundY(c[0], c[1]);
            boolean white = c == hover;
            LevelRenderer.renderLineBox(pose, lines, c[0], y, c[1], c[0] + 1, y + (white ? 0.4 : 1.2), c[1] + 1, white ? 1f : 0.3f, white ? 1f : 0.9f, 1f, 1f);
        }
    }

    /** @return the line under the panel / at the cursor for the current phase */
    static net.minecraft.network.chat.Component status() {
        return switch (phase) {
            case START -> net.minecraft.network.chat.Component.translatable("mccitybuilder.route.pick_start");
            case END -> net.minecraft.network.chat.Component.translatable("mccitybuilder.route.pick_end");
            case SAMPLING, ROUTING -> net.minecraft.network.chat.Component.translatable("mccitybuilder.route.busy");
            case PREVIEW -> net.minecraft.network.chat.Component.translatable(gaps > 0 ? "mccitybuilder.route.found_gap" : "mccitybuilder.route.found", length, turns(), gaps);
            case FAILED -> net.minecraft.network.chat.Component.translatable("mccitybuilder.route.fail." + fail, Config.ROUTE_MAX_LENGTH.get());
        };
    }

    /** @return the cell the floating Accept / Try another / Cancel bar sits under: the route's end */
    static int[] barAnchor() {
        return phase == Phase.PREVIEW || phase == Phase.FAILED && to != null ? to : null;
    }

    /** @return the mode's state for the dev bridge: phase, why it failed, length, turns, gaps and points "x,z x,z ..." */
    static String describe() {
        StringBuilder sb = new StringBuilder(on ? phase.name().toLowerCase() : "off");
        if (fail != null) sb.append(" fail=").append(fail);
        // An end refused as blocked: the flags within the road's half width of each end, per cell "x,z:f".
        if ("blocked".equals(fail) && box != null && params != null)
            for (int[] c : new int[][]{from, to}) {
                sb.append(" near=");
                for (int dz = -params.half(); dz <= params.half(); dz++)
                    for (int dx = -params.half(); dx <= params.half(); dx++) {
                        int x = c[0] + dx - box[0], z = c[1] + dz - box[1];
                        if (x >= 0 && z >= 0 && x < box[2] && z < box[3] && flags[z * box[2] + x] != 0)
                            sb.append(c[0] + dx).append(',').append(c[1] + dz).append(':').append(flags[z * box[2] + x]).append(';');
                    }
            }
        if (!points.isEmpty()) {
            sb.append(" len=").append(length).append(" turns=").append(turns()).append(" gaps=").append(gaps).append(" pts=");
            for (int[] p : points) sb.append(p[0]).append(',').append(p[1]).append(' ');
        }
        return sb.toString().trim();
    }
}
