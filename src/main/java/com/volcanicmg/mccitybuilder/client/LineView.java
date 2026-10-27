package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The road and wall tools, city-builder style, building from the style pack's own road / wall pieces. Before the
 * first click a ghost of the current straight piece follows the cursor; click a start (snapped to a planned
 * segment's end, or onto a segment to branch: that segment is split there), then the live segment follows the cursor
 * along 0 / 90° as the real ghost pieces it will lay (laid out again only when its snapped end, the plan or an option
 * changes); each click drops it into the plan and starts the next at its end, unless it is too short for any piece.
 * Right-click / Esc ends the chain, Backspace takes back the last segment. Family, level / variant, towers and gate
 * apply to the next segments, or to the selected planned segment.
 */
final class LineView {
    /** "road", "wall", or null when no line tool is active. */
    static String tool;
    // Options for the next segments: family per tool, level (level groups) or variant (-1 = varied), towers, gate.
    static String roadFamily, wallFamily;
    static int level = 1, variant = -1;
    static boolean towers, gate, flip;
    // Height for the next segments: mode (Plan.FOLLOW..SMOOTH), blocks added, and the chain's shared level (CHAIN).
    static int heightMode = Plan.FOLLOW, dy, chainBase = Plan.NO_BASE;
    // The planned segment under the cursor (Del / right-click remove it), and removed segments (Undo restores them).
    static int hoverLine = -1;
    // The planned piece under the cursor (the preview card shows it).
    static PieceCatalog.Placed hoverPiece;
    static final java.util.ArrayDeque<Plan.Line> removed = new java.util.ArrayDeque<>();
    // Snapping feedback: what snapped ("joint", "piece", "axis", "tee", "pieces"), how many pieces, the guide line
    // {x0, z0, x1, z1} along the snapped axis, and a joint's snap points (centre and one per face).
    static String snap;
    static int snapPieces;
    static int[] guide;
    static List<int[]> snapPoints = List.of();
    // The live layout's joint / cap offsets at {start, end} (length snapping tiles between them), its flat level.
    private static int[] liveOffs;
    static int liveY = Integer.MIN_VALUE, liveSegMax = Integer.MIN_VALUE;
    // The chain being drawn: its current start, the previous segment's direction, the live end, the cell under the cursor.
    static int[] start, prevDir, end, hover;
    static final List<Plan.Line> chain = new ArrayList<>();
    // The live segment (or the piece under the cursor before a chain starts): pieces, conflicts, gap, length and need.
    static List<PieceCatalog.Placed> live = List.of();
    static List<Boolean> liveBad = List.of();
    static int[] liveGap;
    static int liveShort, liveLen, liveNeed = Integer.MAX_VALUE;
    private static int[] liveKey;
    private static int ghosts;
    // A short hint at the cursor (a refused click).
    static String flash;
    static int flashArg;
    static long flashUntil;

    private LineView() {
    }

    static boolean active() {
        return tool != null;
    }

    static boolean wall() {
        return "wall".equals(tool);
    }

    static void use(String t) {
        tool = t.equals(tool) ? null : t;
        endChain();
    }

    static void endChain() {
        start = prevDir = end = null;
        chainBase = Plan.NO_BASE;
        liveOffs = null;
        chain.clear();
        clearLive();
    }

    /** Drops the live pieces and their ghosts (they come back on the next aim while the tool is on). */
    static void clearLive() {
        live = List.of();
        liveBad = List.of();
        liveGap = null;
        liveShort = liveLen = 0;
        liveNeed = Integer.MAX_VALUE;
        liveKey = null;
        liveOffs = null;
        for (int i = 0; i < ghosts; i++) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_live_" + i);
        ghosts = 0;
    }

    /** @return the family used for the next segments of the active tool (the first usable one by default), or null */
    static String family() {
        List<String> keys = PieceCatalog.familyKeys(PlannerScreen.pack(), tool == null ? "road" : tool);
        if (keys == null || keys.isEmpty()) return null;
        String cur = wall() ? wallFamily : roadFamily;
        if (cur == null || !keys.contains(cur)) cur = keys.get(0);
        if (wall()) wallFamily = cur;
        else roadFamily = cur;
        return cur;
    }

    /** @return "loading", "none" (this style has no pieces for the tool) or null when pieces are ready */
    static String status() {
        List<String> keys = PieceCatalog.familyKeys(PlannerScreen.pack(), tool == null ? "road" : tool);
        return keys == null ? "loading" : keys.isEmpty() ? "none" : null;
    }

    private static List<int[][]> segments() {
        List<int[][]> out = new ArrayList<>();
        for (Plan.Line l : PlanView.lines) out.add(l.ends());
        return out;
    }

    /**
     * Each frame: the live segment's end under the cursor (0 / 90° only), snapped (see {@link #snapEnd}), or before a
     * chain the snapped start under it; laid out again (pieces, conflicts, ghosts) only when the key - ends, plan
     * layout, options - changed. The live pieces include the joint with the segments it meets (that piece belongs to
     * the planned segment in the layout, so it would otherwise only appear after the click).
     */
    static void aim(BlockHitResult hit) {
        String fam = active() ? family() : null;
        snap = null;
        guide = null;
        snapPoints = List.of();
        if (hit == null || fam == null) {
            end = hover = null;
            if (liveKey != null || ghosts > 0 || !live.isEmpty()) clearLive();
            return;
        }
        double cx = hit.getLocation().x, cz = hit.getLocation().z, r = LineSnap.radius(Planner.heightAboveGround());
        List<int[][]> segs = segments();
        int[] a, b;
        boolean tee = false;
        if (start == null) {
            hover = snapStart(cx, cz, segs, r);
            end = null;
            // One straight, lying across the screen, centred on the cursor.
            PieceSets.Piece p = PieceCatalog.primary(next(hover, hover), 0);
            int run = p == null ? 1 : p.run();
            float yaw = Planner.drone == null ? 0 : Planner.drone.yaw;
            boolean alongX = Math.abs(Math.cos(Math.toRadians(yaw))) >= Math.abs(Math.sin(Math.toRadians(yaw)));
            int[] d = alongX ? new int[]{1, 0} : new int[]{0, 1};
            a = new int[]{hover[0] - d[0] * (run / 2), hover[1] - d[1] * (run / 2)};
            b = new int[]{a[0] + d[0] * (run - 1), a[1] + d[1] * (run - 1)};
        } else {
            hover = null;
            end = snapEnd(cx, cz, segs, r);
            tee = "tee".equals(snap);
            a = start;
            b = end;
        }
        int[] key = {a[0], a[1], b[0], b[1], start == null ? 0 : 1, PlanView.layoutVersion,
                Objects.hash(tool, fam, PlannerScreen.pack(), level, variant, towers, gate, flip, heightMode, dy, chainBase)};
        if (!LineSnap.relayout(liveKey, key)) return;
        liveKey = key;
        Plan.Line nl = next(a, b);
        boolean complete;
        liveY = Integer.MIN_VALUE;
        if (start == null) {
            PieceCatalog.Placed p = PieceCatalog.preview(nl);
            live = p == null ? List.of() : List.of(p);
            liveGap = null;
            liveShort = 0;
            liveLen = 0;
            liveNeed = Integer.MAX_VALUE;
            complete = p != null;
        } else if (a[0] == b[0] && a[1] == b[1]) {
            live = List.of();
            liveGap = null;
            liveShort = 0;
            liveLen = 1;
            complete = true;
        } else {
            List<Plan.Line> all = new ArrayList<>(PlanView.lines);
            // Ending on a segment: preview it split there (the click splits it for real), so the T shows.
            if (tee) Plan.split(all, b, PlanView::locked);
            all.add(nl);
            PieceCatalog.Layout lay = PieceCatalog.layout(all);
            int last = all.size() - 1;
            List<PieceCatalog.Placed> ps = new ArrayList<>(lay.lines().get(last));
            // Joint pieces at the live segment's ends that another segment owns in the layout.
            for (int k = 0; k < last; k++)
                for (PieceCatalog.Placed p : lay.lines().get(k))
                    if (p.joint() && (covers(p.box(), a) || covers(p.box(), b)) && !ps.contains(p)) ps.add(p);
            live = ps;
            liveShort = lay.shortened().get(last);
            liveGap = lay.gaps().get(last);
            liveNeed = lay.need().get(last);
            liveOffs = lay.offs().get(last);
            liveY = lay.heights().get(last)[0];
            liveSegMax = lay.heights().get(last)[1];
            liveLen = LineSnap.length(a, b) + 1;
            complete = lay.complete();
        }
        List<int[]> buildings = PlanView.buildingBoxes();
        List<Boolean> bad = new ArrayList<>();
        String pk = PlannerScreen.pack();
        for (PieceCatalog.Placed p : live) {
            boolean red = PlanView.bad(p, buildings);
            // Clearing or replacing blocks of another planned or live piece.
            for (int k = 0; !red && k < PlanView.linePieces.size() && k < PlanView.lines.size(); k++) {
                // Segments the live one attaches to are laid out again once it is dropped (their caps, joints and end
                // pieces move): their current pieces don't count.
                if (start != null && (onLine(PlanView.lines.get(k), a) || onLine(PlanView.lines.get(k), b))) continue;
                for (PieceCatalog.Placed q : PlanView.linePieces.get(k)) red |= PieceCatalog.clash(pk, p, PlanView.lines.get(k).pack(), q);
            }
            for (PieceCatalog.Placed q : live) red |= q != p && PieceCatalog.clash(pk, p, pk, q);
            bad.add(red);
        }
        liveBad = bad;
        showGhosts();
        // Pieces still loading: lay out again next frame.
        if (!complete) liveKey = null;
    }

    /** @return true when cell c is on the segment (ends included) */
    private static boolean onLine(Plan.Line l, int[] c) {
        return c[0] >= Math.min(l.x0(), l.x1()) && c[0] <= Math.max(l.x0(), l.x1()) && c[1] >= Math.min(l.z0(), l.z1()) && c[1] <= Math.max(l.z0(), l.z1());
    }

    private static boolean covers(int[] box, int[] c) {
        return c[0] >= box[0] && c[0] <= box[2] && c[1] >= box[1] && c[1] <= box[3];
    }

    /**
     * Where a chain starts: a segment's end (its joint), else the middle of the nearest piece of a segment (a branch
     * there makes a T that doesn't cut a piece's seam), else the cursor's cell, snapped onto the segments' axes and
     * seam grid.
     */
    private static int[] snapStart(double cx, double cz, List<int[][]> segs, double r) {
        int[] e = LineSnap.nearEndpoint(cx, cz, segs, r);
        if (e != null) {
            snap = "joint";
            jointPoints(e);
            return e.clone();
        }
        // On a segment: the nearest piece centre along it.
        for (int i = 0; i < PlanView.lines.size() && i < PlanView.linePieces.size(); i++) {
            Plan.Line l = PlanView.lines.get(i);
            boolean alongX = l.z0() == l.z1();
            int across = alongX ? l.z0() : l.x0();
            if (Math.abs((alongX ? cz : cx) - (across + 0.5)) > r) continue;
            int lo = alongX ? Math.min(l.x0(), l.x1()) : Math.min(l.z0(), l.z1()), hi = alongX ? Math.max(l.x0(), l.x1()) : Math.max(l.z0(), l.z1());
            double v = alongX ? cx : cz;
            if (v < lo || v > hi + 1) continue;
            List<Integer> mids = new ArrayList<>();
            for (PieceCatalog.Placed p : PlanView.linePieces.get(i))
                if (!p.joint()) {
                    int m = alongX ? (p.box()[0] + p.box()[2]) / 2 : (p.box()[1] + p.box()[3]) / 2;
                    if (m > lo && m < hi) mids.add(m);
                }
            int m = LineSnap.nearest(v, mids, Double.MAX_VALUE);
            if (m == Integer.MIN_VALUE) m = (int) Math.floor(v);
            snap = "piece";
            return alongX ? new int[]{m, across} : new int[]{across, m};
        }
        // Free: onto the segments' axes and their seam grid.
        PieceSets.Piece p = PieceCatalog.primary(next(new int[]{0, 0}, new int[]{0, 0}), 0);
        List<List<Integer>> ax = LineSnap.axes(segs, p == null ? 0 : p.run(), 16);
        int x = LineSnap.nearest(cx, ax.get(0), r), z = LineSnap.nearest(cz, ax.get(1), r);
        int[] c = {x == Integer.MIN_VALUE ? (int) Math.floor(cx) : x, z == Integer.MIN_VALUE ? (int) Math.floor(cz) : z};
        if (x != Integer.MIN_VALUE || z != Integer.MIN_VALUE) {
            snap = "axis";
            guide = x != Integer.MIN_VALUE ? new int[]{c[0], c[1] - 24, c[0], c[1] + 24} : new int[]{c[0] - 24, c[1], c[0] + 24, c[1]};
        }
        return c;
    }

    /**
     * The live end: a segment's end in line with the start (a joint), else a perpendicular segment the run crosses
     * (a T, split on the click), else a length the family's pieces cover exactly (whole straights between the joints /
     * caps; Shift: any length).
     */
    private static int[] snapEnd(double cx, double cz, List<int[][]> segs, double r) {
        int[] e = LineSnap.nearEndpoint(cx, cz, segs, r);
        if (e != null && !(e[0] == start[0] && e[1] == start[1]) && (e[0] == start[0] || e[1] == start[1])) {
            snap = "joint";
            jointPoints(e);
            return e.clone();
        }
        int[] raw = LineSnap.snapEnd(start, cx, cz, prevDir, false, List.of(), 90);
        if (raw[0] == start[0] && raw[1] == start[1]) return raw;
        int dir = com.volcanicmg.mccitybuilder.planner.PieceLayout.dir(start, raw), sx = dir == 0 ? 1 : dir == 2 ? -1 : 0, sz = dir == 1 ? 1 : dir == 3 ? -1 : 0;
        double along = dir % 2 == 0 ? (cx - start[0] - 0.5) * sx : (cz - start[1] - 0.5) * sz;
        int t = LineSnap.crossing(start, dir, along, segs, r);
        if (t > 0) {
            snap = "tee";
            return new int[]{start[0] + sx * t, start[1] + sz * t};
        }
        int cells = LineSnap.length(start, raw) + 1;
        int[] lens = PieceCatalog.runLengths(next(start, raw));
        if (liveOffs != null && lens.length > 0 && !net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            int n = LineSnap.snapLength(cells, liveOffs[0], liveOffs[1], lens);
            if (LineSnap.exact(n, liveOffs[0], liveOffs[1], lens)) {
                snap = "pieces";
                snapPieces = com.volcanicmg.mccitybuilder.planner.PieceLayout.fill(n - liveOffs[0] - liveOffs[1], lens).size();
                cells = n;
            }
        }
        int[] c = {start[0] + sx * (cells - 1), start[1] + sz * (cells - 1)};
        guide = new int[]{start[0], start[1], c[0], c[1]};
        return c;
    }

    /** A joint's snap points: the cell itself and the middle of each face of its piece. */
    private static void jointPoints(int[] c) {
        for (List<PieceCatalog.Placed> ps : PlanView.linePieces)
            for (PieceCatalog.Placed p : ps)
                if (p.joint() && covers(p.box(), c)) {
                    int[] b = p.box();
                    int mx = (b[0] + b[2]) / 2, mz = (b[1] + b[3]) / 2;
                    snapPoints = List.of(c, new int[]{b[0], mz}, new int[]{b[2], mz}, new int[]{mx, b[1]}, new int[]{mx, b[3]});
                    snap = "tower";
                    return;
                }
        snapPoints = List.of(c);
    }

    /** Structurize ghosts of the live pieces, one cache key each; stale keys removed. */
    private static void showGhosts() {
        int n = 0;
        for (PieceCatalog.Placed p : live) {
            Blueprint bp = PieceCatalog.rotated(PlannerScreen.pack(), p.path(), p.turns());
            if (bp == null) continue;
            BlueprintPreviewData data = new BlueprintPreviewData(false);
            data.setBlueprint(bp);
            data.setRotationMirror(PieceCatalog.rotation(p.turns()));
            data.setPos(p.anchor());
            RenderingCache.queue(Planner.PREVIEW_KEY + "_live_" + n++, data);
        }
        for (int i = n; i < ghosts; i++) RenderingCache.removeBlueprint(Planner.PREVIEW_KEY + "_live_" + i);
        ghosts = n;
    }

    /**
     * Click: start a chain (an endpoint, or a branch that splits a segment), or drop the live segment and go on.
     *
     * @return false when the click was refused (the segment is too short for any piece); a hint flashes then
     */
    static boolean click(BlockHitResult hit) {
        if (!active() || hit == null || family() == null) return true;
        if (start == null) {
            start = hover != null ? hover : LineSnap.snapStart(hit.getLocation().x, hit.getLocation().z, segments());
            PlanView.splitAt(start);
            liveKey = null;
            return true;
        }
        if (end == null || end[0] == start[0] && end[1] == start[1]) return true;
        if (!LineSnap.canDrop(liveLen, liveNeed)) {
            flash = liveNeed == Integer.MAX_VALUE ? "mccitybuilder.line.no_fit" : "mccitybuilder.line.too_short";
            flashArg = liveNeed;
            flashUntil = System.currentTimeMillis() + 1500;
            return false;
        }
        // Ending on a segment splits it there (a T).
        PlanView.splitAt(end);
        if (heightMode == Plan.CHAIN) {
            // The chain shares one level: high enough for this segment's ground too; earlier segments follow it.
            int nb = com.volcanicmg.mccitybuilder.planner.PieceLayout.chainBase(chainBase, liveSegMax);
            if (nb != chainBase)
                for (int k = 0; k < chain.size(); k++) {
                    Plan.Line o = chain.get(k), n = o.withHeight(o.height(), o.dy(), nb);
                    PlanView.replaceLine(o, n);
                    chain.set(k, n);
                }
            chainBase = nb;
        }
        Plan.Line l = next(start, end);
        PlanView.addLine(l);
        chain.add(l);
        prevDir = new int[]{end[0] - start[0], end[1] - start[1]};
        start = end;
        end = null;
        // A gate is for one segment.
        gate = false;
        clearLive();
        return true;
    }

    /** Backspace: removes the chain's last segment (found by value, so other edits can't misdirect it). */
    static void undo() {
        if (chain.isEmpty()) {
            // Nothing drawn in this chain: bring back the last removed segment, if any.
            if (!removed.isEmpty()) PlanView.addLine(removed.pop());
            else endChain();
            return;
        }
        Plan.Line l = chain.remove(chain.size() - 1);
        PlanView.removeLineValue(l);
        start = new int[]{l.x0(), l.z0()};
        prevDir = chain.isEmpty() ? null : new int[]{chain.get(chain.size() - 1).x1() - chain.get(chain.size() - 1).x0(),
                chain.get(chain.size() - 1).z1() - chain.get(chain.size() - 1).z0()};
        liveKey = null;
    }

    static Plan.Line next(int[] a, int[] b) {
        return new Plan.Line(tool, a[0], a[1], b[0], b[1], PlannerScreen.pack(), family(), level, variant, towers, wall() && gate, flip, heightMode, dy,
                heightMode == Plan.CHAIN ? chainBase : Plan.NO_BASE, false);
    }

    // ---- options: the selected planned segment if any, else the next segments ----

    static void cycleFamily(int d) {
        Plan.Line s = PlanView.selectedLine();
        String t = s != null ? s.tool() : tool;
        List<String> keys = PieceCatalog.familyKeys(s != null ? s.pack() : PlannerScreen.pack(), t);
        if (keys == null || keys.isEmpty()) return;
        String cur = s != null ? s.family() : family();
        String nx = keys.get(Math.floorMod(Math.max(0, keys.indexOf(cur)) + d, keys.size()));
        if (s != null) PlanView.setLine(s.with(nx, s.level(), s.variant(), s.towers(), s.gate()));
        else if ("wall".equals(t)) wallFamily = nx;
        else roadFamily = nx;
    }

    /** Level (when the family's straights are levels) or variant (-1 = varied, then 0..n-1). */
    static void cycleLevel(int d, int max, boolean levels) {
        Plan.Line s = PlanView.selectedLine();
        int lv = s != null ? s.level() : level, va = s != null ? s.variant() : variant;
        if (levels) lv = Math.floorMod(lv - 1 + d, Math.max(1, max)) + 1;
        else va = Math.floorMod(va + 1 + d, max + 1) - 1;
        if (s != null) PlanView.setLine(s.with(s.family(), lv, va, s.towers(), s.gate()));
        else {
            level = lv;
            variant = va;
        }
    }

    static void toggleTowers() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null) PlanView.setLine(s.with(s.family(), s.level(), s.variant(), !s.towers(), s.gate()));
        else towers = !towers;
    }

    /** R: the other side out (straights and gates turned 180°), for the selected segment or the next ones. */
    static void toggleFlip() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null) PlanView.setLine(s.withFlip(!s.flip()));
        else flip = !flip;
    }

    /** Height mode: follow the ground, flat per segment, flat per chain, smooth. */
    static void cycleHeight(int d) {
        Plan.Line s = PlanView.selectedLine();
        int m = Math.floorMod((s != null ? s.height() : heightMode) + d, 4);
        if (s != null) PlanView.setLine(s.withHeight(m, s.dy(), m == Plan.CHAIN ? s.base() : Plan.NO_BASE));
        else {
            heightMode = m;
            chainBase = Plan.NO_BASE;
        }
    }

    /** PgUp / PgDn: raise or lower (blocks added to the mode's height). */
    static void raise(int d) {
        Plan.Line s = PlanView.selectedLine();
        if (s != null) PlanView.setLine(s.withHeight(s.height(), Math.max(-64, Math.min(64, s.dy() + d)), s.base()));
        else dy = Math.max(-64, Math.min(64, dy + d));
    }

    /**
     * Del / right-click / Remove: deletes the hovered planned segment (or the selected one); Undo brings it back.
     *
     * @return false when there was none
     */
    static boolean removeHovered() {
        int i = hoverLine >= 0 && hoverLine < PlanView.lines.size() ? hoverLine : PlanView.selectedLine;
        if (i < 0 || i >= PlanView.lines.size()) return false;
        removed.push(PlanView.lines.get(i));
        while (removed.size() > 32) removed.removeLast();
        PlanView.removeLine(i);
        hoverLine = -1;
        return true;
    }

    /** Gate in the selected wall segment, or in the next one drawn. */
    static void toggleGate() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null && s.isWall()) PlanView.setLine(s.with(s.family(), s.level(), s.variant(), s.towers(), !s.gate()));
        else if (s == null && wall()) gate = !gate;
    }

    /** @return the summed block count of placed pieces (their material cost in blocks) */
    static int cost(List<PieceCatalog.Placed> ps) {
        int n = 0;
        for (PieceCatalog.Placed p : ps) n += p.blocks();
        return n;
    }

    /** The live pieces' outlines (green, red where they conflict), the uncovered gap (amber), the chain start (white). */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        if (!active()) return;
        PlanView.outline(pose, lines, live, liveBad, false);
        if (liveGap != null) PlanView.gap(pose, lines, liveGap, live.isEmpty() ? groundY(liveGap[0], liveGap[1]) : live.get(0).anchor().getY());
        int[] c = start != null ? start : hover;
        if (c != null) {
            int y = groundY(c[0], c[1]);
            LevelRenderer.renderLineBox(pose, lines, c[0], y, c[1], c[0] + 1, y + 0.4, c[1] + 1, 1f, 1f, 1f, 1f);
        }
        // Snapping: the joint's snap points (cyan) and a guide along the snapped axis.
        for (int[] p : snapPoints) {
            int y = groundY(p[0], p[1]);
            LevelRenderer.renderLineBox(pose, lines, p[0] + 0.25, y + 0.1, p[1] + 0.25, p[0] + 0.75, y + 0.6, p[1] + 0.75, 0.3f, 0.9f, 1f, 1f);
        }
        if (guide != null) {
            int y = groundY(guide[0], guide[1]) + 1;
            var m = pose.last();
            float nx = Math.signum(guide[2] - guide[0]), nz = Math.signum(guide[3] - guide[1]);
            lines.addVertex(m, guide[0] + 0.5f, y, guide[1] + 0.5f).setColor(0.3f, 0.9f, 1f, 0.6f).setNormal(m, nx, 0, nz);
            lines.addVertex(m, guide[2] + 0.5f, y, guide[3] + 0.5f).setColor(0.3f, 0.9f, 1f, 0.6f).setNormal(m, nx, 0, nz);
        }
        PlanView.floating(pose, lines, live);
    }

    private static int groundY(int x, int z) {
        Integer g = Planner.groundAt(x, z);
        return g == null ? 64 : g;
    }

    /** @return the piece the next segments are built from (or the selected segment's): its main straight, or null */
    static PieceSets.Piece current() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null) return PieceCatalog.primary(s, 0);
        if (!active() || family() == null) return null;
        return PieceCatalog.primary(next(new int[]{0, 0}, new int[]{0, 0}), 0);
    }

    /** @return the role groups of the family used by the panel's level / variant picker: {max, levels?1:0} */
    static int[] levelInfo() {
        Plan.Line s = PlanView.selectedLine();
        String pack = s != null ? s.pack() : PlannerScreen.pack(), fam = s != null ? s.family() : family();
        var fs = PieceCatalog.families(pack);
        PieceSets.Family f = fs == null || fam == null ? null : fs.get(fam);
        if (f == null) return new int[]{1, 1};
        List<PieceSets.Group> st = PieceCatalog.groups(pack, f, PieceSets.Role.STRAIGHT);
        if (st.isEmpty()) return new int[]{1, 1};
        PieceSets.Group g = st.get(0);
        return g.levels() ? new int[]{g.pieces().get(g.pieces().size() - 1).number(), 1} : new int[]{g.pieces().size(), 0};
    }
}
