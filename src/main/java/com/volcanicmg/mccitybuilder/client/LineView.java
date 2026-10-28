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
    // The planned segment under the cursor (Del / right-click remove it).
    static int hoverLine = -1;
    // The planned piece under the cursor (the preview card shows it).
    static PieceCatalog.Placed hoverPiece;
    // Undo: the plan's lines, joint types, chain and selection before each user change (newest first).
    private record Snap(List<Plan.Line> lines, java.util.Map<Long, Integer> joints, List<Plan.Line> chain, int[] start, int[] prevDir, int chainBase,
                        int selectedLine, int[] selectedJoint) {
    }

    private static final java.util.ArrayDeque<Snap> undos = new java.util.ArrayDeque<>();
    // Built segments whose removal went to the server: an undo brings them back as plans.
    private static final java.util.Set<Plan.Line> takenDown = new java.util.HashSet<>();
    // Snapping feedback: what snapped ("joint", "piece", "axis", "tee", "pieces"), how many pieces, the guide line
    // {x0, z0, x1, z1} along the snapped axis, and a joint's snap points (centre and one per face).
    static String snap;
    static int snapPieces;
    static int[] guide;
    static List<int[]> snapPoints = List.of();
    // Edit mode: no drawing; hover / select a segment or a joint, drag ends, bodies and joints.
    static boolean edit;
    static int[] hoverJoint, selectedJoint;
    // A drag in edit mode: what is dragged (0 = an end of the selected segment, 1 = the segment, 2 = the joint), the
    // points it moves, the cell it started at, and the plan as it would be (null = can't move there).
    private static int dragKind = -1;
    private static List<int[]> dragPoints = List.of();
    private static int[] dragFrom;
    static List<Plan.Line> dragPreview;
    private static int[] dragDelta;
    // The joint default for new joints (Plan.J_*), set in the panel.
    static int jointDefault = Plan.J_AUTO;
    // The live end joins another segment's free end that moves onto this line: {line, end, x, z}; or the live
    // segment goes straight on from the chain's last one (dropped as its extension).
    private static int[] adjust;
    private static boolean merge;
    // The chain's last segment grows by this vector at the corner the live segment makes (null: no growth).
    private static int[] grow;
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
    // A live piece would clear or replace a planned one (crossing without a joint).
    static boolean liveClash;
    private static int[] liveKey;
    private static boolean liveIncomplete;
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
        if (liveA != null) PlanView.relayout();
        liveA = liveB = null;
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
        if (edit) {
            // Edit mode draws nothing: the cursor finds a joint (a segment end) or a segment.
            if (liveKey != null || ghosts > 0 || !live.isEmpty()) clearLive();
            end = hover = null;
            // Hover comes from the screen-space handles (PlannerScreen.editHandles).
            return;
        }
        if (hit == null || fam == null) {
            end = hover = null;
            if (liveKey != null || ghosts > 0 || !live.isEmpty()) clearLive();
            return;
        }
        double cx = hit.getLocation().x, cz = hit.getLocation().z, r = radius(hit);
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
            // Turning off the chain's last segment: the corner takes cells from it, so it grows to keep its straights
            // whole and this segment moves out with the corner (shown live, applied on the click).
            grow = chainGrowth(end);
            if (grow != null) {
                a = new int[]{start[0] + grow[0], start[1] + grow[1]};
                b = new int[]{end[0] + grow[0], end[1] + grow[1]};
            }
        }
        int[] key = {a[0], a[1], b[0], b[1], start == null ? 0 : 1, PlanView.layoutVersion,
                Objects.hash(tool, fam, PlannerScreen.pack(), level, variant, towers, gate, flip, heightMode, dy, chainBase),
                liveIncomplete ? PieceCatalog.loadVersion * 31 + (int) (System.currentTimeMillis() / 500) : 0};
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
            // The chain's last segment grown for the corner.
            if (grow != null && !chain.isEmpty()) {
                int gi = all.indexOf(chain.get(chain.size() - 1));
                if (gi >= 0) all.set(gi, grown(all.get(gi), grow));
            }
            // Joining a free end that moves onto this line: preview it moved.
            if (adjust != null && adjust[0] < all.size()) all.set(adjust[0], moveEnd(all.get(adjust[0]), adjust[1], adjust[2], adjust[3]));
            // Ending on a segment: preview it split there (the click splits it for real), so the T shows.
            if (tee) Plan.split(all, b, PlanView::locked);
            // Passing a wall's free end: the live run itself is split there.
            if (teeCell != null && !merge) {
                List<Plan.Line> one = new ArrayList<>(List.of(nl));
                if (Plan.split(one, teeCell, l -> false) == 0) {
                    all.add(one.get(0));
                    nl = one.get(1);
                }
            }
            if (merge && !chain.isEmpty() && all.remove(chain.get(chain.size() - 1))) {
                // Straight on: the chain's last segment, extended, is what is laid out live.
                Plan.Line prev = chain.get(chain.size() - 1);
                nl = prev.between(prev.x0(), prev.z0(), b[0], b[1]);
            }
            all.add(nl);
            PieceCatalog.Layout lay = PieceCatalog.layout(all, previewTypes());
            int last = all.size() - 1;
            List<PieceCatalog.Placed> ps = new ArrayList<>(lay.lines().get(last));
            // The segments the live one attaches to are laid out with it (their joint, cap, shift, heights change):
            // their pieces come from this layout, and the plan stops drawing its own for them (PlanView.liveTouched).
            for (int k = 0; k < last; k++)
                if (onLine(all.get(k), a) || onLine(all.get(k), b))
                    for (PieceCatalog.Placed p : lay.lines().get(k)) if (!ps.contains(p)) ps.add(p);
            boolean wasEmpty = live.isEmpty();
            live = ps;
            if (wasEmpty != live.isEmpty() || liveA == null || liveA[0] != a[0] || liveA[1] != a[1] || liveB[0] != b[0] || liveB[1] != b[1]) PlanView.relayout();
            liveA = a.clone();
            liveB = b.clone();
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
        liveClash = false;
        for (PieceCatalog.Placed p : live) {
            boolean red = PlanView.bad(p, buildings), out = red;
            // Clearing or replacing blocks of another planned or live piece.
            for (int k = 0; !red && k < PlanView.linePieces.size() && k < PlanView.lines.size(); k++) {
                // Segments the live one attaches to are laid out again once it is dropped (their caps, joints and end
                // pieces move): their current pieces don't count.
                if (start != null && (onLine(PlanView.lines.get(k), a) || onLine(PlanView.lines.get(k), b))) continue;
                for (PieceCatalog.Placed q : PlanView.linePieces.get(k)) red |= PieceCatalog.clash(pk, p, PlanView.lines.get(k).pack(), q);
            }
            for (PieceCatalog.Placed q : live) red |= q != p && PieceCatalog.clash(pk, p, pk, q);
            bad.add(red);
            liveClash |= red && !out;
        }
        liveBad = bad;
        showGhosts();
        // Pieces still loading: lay out again next frame.
        // Pieces still loading: laid out again when one finishes (or twice a second), not every frame.
        liveIncomplete = !complete;
    }

    // The live segment's ends as last laid out; the plan skips its ghosts for segments touching them (the live layout draws them).
    private static int[] liveA, liveB;

    /** @return true when the live layout draws this planned segment's pieces instead of the plan */
    static boolean liveTouches(Plan.Line l) {
        return !live.isEmpty() && liveA != null && (onLine(l, liveA) || onLine(l, liveB));
    }

    /** @return true when cell c is on the segment (ends included) */
    private static boolean onLine(Plan.Line l, int[] c) {
        return c[0] >= Math.min(l.x0(), l.x1()) && c[0] <= Math.max(l.x0(), l.x1()) && c[1] >= Math.min(l.z0(), l.z1()) && c[1] <= Math.max(l.z0(), l.z1());
    }

    /** @return the snap radius: about 24 GUI pixels at the cursor's distance, in blocks */
    static double radius(BlockHitResult hit) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        double dist = mc.gameRenderer.getMainCamera().getPosition().distanceTo(hit.getLocation());
        return LineSnap.radiusPx(dist, mc.options.fov().get(), mc.getWindow().getGuiScaledHeight(), 24);
    }

    // ---- edit mode ----

    /** X / the Edit button: edit mode on or off (on: the chain ends; off: the selection clears). */
    static void toggleEdit() {
        edit = !edit;
        finishChain();
        select(List.of(), false);
        hoverJoint = null;
        hoverMid = -1;
        dragKind = -1;
        dragPreview = null;
    }

    // Edit selection: the selected segments (indices), whether they are a whole connected wall, the select mode.
    static final List<Integer> sel = new ArrayList<>();
    static boolean selWhole, wholeMode;
    // Hover from the screen-space handles: a joint node, a segment's middle handle, or a segment's body.
    static int hoverMid = -1;
    // The last drag's outcome for the flash, and a short status while dragging.
    static String dragStatus;
    static boolean dragFits = true;
    private static long lastPress;
    private static int lastPressLine = -1;

    /** @return true when segment i is selected (edit mode) */
    static boolean selected(int i) {
        return edit && sel.contains(i);
    }

    /** @return what a click would select now (lighter outline): the hovered segment, or its whole wall */
    static List<Integer> hoverPreview(boolean alt) {
        int i = hoverMid >= 0 ? hoverMid : hoverLine;
        if (!edit || hoverJoint != null || i < 0) return List.of();
        return wholeMode || alt ? Plan.connected(PlanView.lines, i) : List.of(i);
    }

    private static void select(List<Integer> ls, boolean whole) {
        sel.clear();
        sel.addAll(ls);
        selWhole = whole;
        selectedJoint = null;
        PlanView.selectedLine = sel.size() == 1 ? sel.get(0) : -1;
    }

    /**
     * A press in edit mode: a joint node selects that joint and drags it; a segment's middle handle selects the
     * segment (or its whole wall: Whole wall mode, Alt or a double-click) and drags the selection; a click on a
     * selected segment's body drags the selection; elsewhere on a body selects; anywhere else clears the selection.
     */
    static void press(BlockHitResult hit, boolean alt) {
        long now = System.currentTimeMillis();
        int body = hoverLine, line = hoverMid >= 0 ? hoverMid : body;
        boolean dbl = line >= 0 && line == lastPressLine && now - lastPress < 350;
        lastPress = now;
        lastPressLine = line;
        int[] from = hit == null ? null : new int[]{hit.getBlockPos().getX(), hit.getBlockPos().getZ()};
        closeJointMenu(false);
        if (hoverJoint != null) {
            select(List.of(), false);
            selectedJoint = hoverJoint.clone();
            openJointMenu();
            if (from != null) startDrag(2, List.of(selectedJoint), from);
            return;
        }
        if (line < 0) {
            select(List.of(), false);
            return;
        }
        boolean whole = wholeMode || alt || dbl;
        if (!sel.contains(line) || whole && !selWhole || dbl) select(whole ? Plan.connected(PlanView.lines, line) : List.of(line), whole);
        else if (hoverMid < 0 && !sel.contains(body)) return;
        if (from == null) return;
        List<int[]> pts = new ArrayList<>();
        for (int i : sel) for (int[] e : PlanView.lines.get(i).ends()) if (pts.stream().noneMatch(p -> p[0] == e[0] && p[1] == e[1])) pts.add(e);
        startDrag(1, pts, from);
    }

    private static void startDrag(int kind, List<int[]> points, int[] from) {
        dragKind = kind;
        dragPoints = points;
        dragFrom = from;
        dragPreview = null;
        dragDelta = null;
        dragStatus = null;
    }

    static boolean dragging() {
        return dragKind >= 0;
    }

    /** Esc while dragging: nothing moves. */
    static void cancelDrag() {
        dragKind = -1;
        dragPreview = null;
        dragDelta = null;
        dragStatus = null;
        flash = "mccitybuilder.edit.cancelled";
        flashUntil = System.currentTimeMillis() + 1200;
    }

    /**
     * While dragging: the plan as the move would leave it. A single point snaps onto another segment's end; without
     * Shift the move snaps to the nearest position where every run with joints at both ends stays whole
     * ({@link Plan#fitMove}); with Shift it goes where the cursor is, and the status says if a piece is lost.
     */
    static void dragTo(BlockHitResult hit, boolean free) {
        if (dragKind < 0 || hit == null) return;
        int[] c = {hit.getBlockPos().getX(), hit.getBlockPos().getZ()};
        if (dragPoints.size() == 1) {
            List<int[][]> others = new ArrayList<>();
            for (Plan.Line l : PlanView.lines) {
                boolean mine = false;
                for (int[] e : l.ends()) mine |= e[0] == dragPoints.get(0)[0] && e[1] == dragPoints.get(0)[1];
                if (!mine) others.add(l.ends());
            }
            int[] e = LineSnap.nearEndpoint(hit.getLocation().x, hit.getLocation().z, others, radius(hit));
            if (e != null) c = new int[]{e[0] + dragFrom[0] - dragPoints.get(0)[0], e[1] + dragFrom[1] - dragPoints.get(0)[1]};
        }
        int dx = c[0] - dragFrom[0], dz = c[1] - dragFrom[1];
        List<int[]> lens = lensAll();
        if (!free) {
            int[] f = Plan.fitMove(PlanView.lines, dragPoints, dx, dz, PlanView::locked, PlanView.lineOffs, lens, 9);
            if (f != null) {
                dx = f[0];
                dz = f[1];
            }
        }
        dragDelta = new int[]{dx, dz};
        // A real drag is a move, not a menu pick.
        if (dx != 0 || dz != 0) closeJointMenu(false);
        dragPreview = Plan.move(PlanView.lines, dragPoints, dx, dz, PlanView::locked);
        dragFits = dragPreview != null && Plan.allFit(PlanView.lines, dragPreview, PlanView.lineOffs, lens);
        boolean out = dragPreview != null && movesOut(PlanView.lines, dragPreview);
        if (out) dragFits = false;
        dragStatus = net.minecraft.network.chat.Component.translatable(out ? "mccitybuilder.edit.cant_outside" : dragPreview == null ? "mccitybuilder.edit.cant_move"
                : dragFits ? "mccitybuilder.edit.moving" : "mccitybuilder.edit.moving_loses", kindName(), Math.abs(dx) + Math.abs(dz)).getString();
    }

    /** @return true when a segment's centreline leaves the colony's claims (unknown claims: never) */
    static boolean outside(Plan.Line l) {
        java.util.Set<Long> c = Planner.claimed();
        return !c.isEmpty() && !com.volcanicmg.mccitybuilder.planner.ColonyGeometry.footprintClaimed(l.x0(), l.z0(), l.x1(), l.z1(), c);
    }

    /** @return true when a segment the move changes would leave the claims */
    private static boolean movesOut(List<Plan.Line> before, List<Plan.Line> after) {
        for (int i = 0; i < after.size(); i++) if ((i >= before.size() || !after.get(i).equals(before.get(i))) && outside(after.get(i))) return true;
        return false;
    }

    private static String kindName() {
        return net.minecraft.network.chat.Component.translatable("mccitybuilder.edit.kind." + (dragKind == 2 ? "joint" : selWhole ? "wall" : "segment")).getString();
    }

    /** @return each segment's straight lengths (for fitting) */
    static List<int[]> lensAll() {
        List<int[]> out = new ArrayList<>();
        for (Plan.Line l : PlanView.lines) out.add(PieceCatalog.runLengths(l));
        return out;
    }

    /**
     * Release: the move is made again from the plan as it is now (a batch answer may have locked a segment, or a line
     * count changed); segments with a free end then re-fit to whole straights. Joint types and the selection follow.
     * A flash says whether it worked.
     */
    static void release() {
        if (dragKind < 0) return;
        int kind = dragKind;
        dragKind = -1;
        dragPreview = null;
        dragStatus = null;
        if (dragDelta == null || dragDelta[0] == 0 && dragDelta[1] == 0) return;
        List<Plan.Line> before = PlanView.lines, moved = Plan.move(before, dragPoints, dragDelta[0], dragDelta[1], PlanView::locked);
        dragDelta = null;
        flashUntil = System.currentTimeMillis() + 1500;
        if (moved == null) {
            boolean lockedHit = false;
            for (Plan.Line l : before)
                for (int[] e : l.ends()) for (int[] p : dragPoints) lockedHit |= p[0] == e[0] && p[1] == e[1] && PlanView.locked(l);
            flash = lockedHit ? "mccitybuilder.edit.cant_built" : "mccitybuilder.edit.cant_short";
            return;
        }
        java.util.Set<Integer> changed = new java.util.HashSet<>();
        for (int i = 0; i < moved.size(); i++) if (!moved.get(i).equals(before.get(i))) changed.add(i);
        moved = Plan.refitFree(moved, changed, PlanView.lineOffs, lensAll(), PlanView::locked, 12);
        if (movesOut(before, moved)) {
            flash = "mccitybuilder.edit.cant_outside_flash";
            return;
        }
        checkpoint();
        java.util.Map<Long, int[]> to = Plan.moved(before, moved, dragPoints);
        java.util.Map<Long, Integer> types = new java.util.HashMap<>();
        for (var en : to.entrySet()) {
            Integer t = PlanView.jointTypes.remove(en.getKey());
            if (t != null) types.put(Plan.cell(en.getValue()[0], en.getValue()[1]), t);
        }
        PlanView.jointTypes.putAll(types);
        PlanView.setLines(moved);
        flash = "mccitybuilder.edit.moved";
        if (kind == 2 && selectedJoint != null) {
            int[] n = to.get(Plan.cell(selectedJoint[0], selectedJoint[1]));
            if (n != null) selectedJoint = n.clone();
        }
    }

    /** C / the joint picker: the selected joint's type, or the default for new joints. */
    static void cycleJoint(int d) {
        if (selectedJoint == null) {
            if (!edit) jointDefault = Math.floorMod(jointDefault + d, Plan.J_TYPES);
            return;
        }
        long k = Plan.cell(selectedJoint[0], selectedJoint[1]);
        setJointType(Math.floorMod(PlanView.jointTypes.getOrDefault(k, Plan.J_AUTO) + d, Plan.J_TYPES));
    }

    /** A trial of a joint type at the selected joint: the types, the lines re-fitted (flipped where that aligns better), the blocks a fixed run would lack (0 = fits), the least run match there, and how many walls flip. */
    private record Trial(java.util.Map<Long, Integer> types, List<Plan.Line> lines, int lacks, double match, int flips) {
    }

    /**
     * Tries the type with every flip combination of the unlocked walls at the joint (a corner piece's ledge sits on
     * one side: a wall facing the other way meets it stepped): the one that fits, then matches best, then flips fewest.
     */
    private static Trial trial(int t) {
        List<Integer> at = linesAt(selectedJoint);
        List<Integer> flippable = new ArrayList<>();
        for (int i : at) if (!PlanView.locked(PlanView.lines.get(i))) flippable.add(i);
        Trial best = null;
        for (int mask = 0; mask < 1 << Math.min(3, flippable.size()); mask++) {
            List<Plan.Line> ls = new ArrayList<>(PlanView.lines);
            for (int b = 0; b < flippable.size() && b < 3; b++)
                if ((mask >> b & 1) == 1) ls.set(flippable.get(b), ls.get(flippable.get(b)).withFlip(!ls.get(flippable.get(b)).flip()));
            Trial tr = trial(t, ls, at, Integer.bitCount(mask));
            if (best == null || tr.lacks() == 0 && best.lacks() > 0 || (tr.lacks() == 0) == (best.lacks() == 0)
                    && (tr.match() > best.match() + 1e-9 || Math.abs(tr.match() - best.match()) < 1e-9 && tr.flips() < best.flips())) best = tr;
        }
        PieceCatalog.layout(PlanView.lines, PlanView.jointTypes);
        return best;
    }

    private static Trial trial(int t, List<Plan.Line> base, List<Integer> at, int flips) {
        long k = Plan.cell(selectedJoint[0], selectedJoint[1]);
        java.util.Map<Long, Integer> trial = Plan.withJoint(PlanView.jointTypes, k, t);
        // A type that costs a run a straight: runs with a free end grow to fit; a run fixed at both ends refuses it.
        PieceCatalog.Layout lay = PieceCatalog.layout(base, trial);
        List<Plan.Line> ls = Plan.refitFree(base, at, lay.offs(), lensAll(), PlanView::locked, 12);
        PieceCatalog.Layout lay2 = PieceCatalog.layout(ls, trial);
        double match = PieceCatalog.jointMatch.getOrDefault(k, t == Plan.J_NONE ? 1.0 : 0.0);
        int short2 = 0;
        for (int i : at) {
            int before = straights(PlanView.linePieces, i), after = straights(lay2.lines(), i);
            int avail = Plan.cells(ls.get(i)) - lay2.offs().get(i)[0] - lay2.offs().get(i)[1];
            if (after < before) short2 = Math.max(short2, Math.max(1, before * minRun(i) - avail));
        }
        return new Trial(trial, ls, short2, match, flips);
    }

    /** @return true when the selected joint took type {@code t}; refused (a flash) when a run fixed at both ends would lose a straight */
    static boolean setJointType(int t) {
        if (selectedJoint == null) return false;
        Trial tr = trial(t);
        if (tr.lacks() > 0) {
            flash = "mccitybuilder.edit.joint_no_fit." + t;
            flashArg = tr.lacks();
            flashUntil = System.currentTimeMillis() + 2500;
            return false;
        }
        checkpoint();
        PlanView.jointTypes.clear();
        PlanView.jointTypes.putAll(tr.types());
        PlanView.setLines(tr.lines());
        return true;
    }

    private static List<Integer> linesAt(int[] c) {
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < PlanView.lines.size(); i++)
            for (int[] e : PlanView.lines.get(i).ends()) if (e[0] == c[0] && e[1] == c[1]) at.add(i);
        return at;
    }

    // ---- the joint menu (a click on a joint node) ----

    /** One option of the joint menu: the type, the piece it would use (null for none), the blocks a fixed run would lack (0 fits), the run match (1 = continuous) and the walls that flip for it. */
    record JointOption(int type, PieceSets.Piece piece, int lacks, double match, int flips) {
    }

    static boolean jointMenu;
    static List<JointOption> jointOptions = List.of();
    // The type previewed in the world while an option is hovered (-1: none): a rendering override, never written to the plan.
    private static int menuPreview = -1;

    /** @return the joint types to lay out with for drawing: the plan's, with the menu's hovered type at the selected joint */
    static java.util.Map<Long, Integer> previewTypes() {
        if (!jointMenu || selectedJoint == null || menuPreview < 0) return PlanView.jointTypes;
        return Plan.withJoint(PlanView.jointTypes, Plan.cell(selectedJoint[0], selectedJoint[1]), menuPreview);
    }

    /**
     * Opens the menu for the selected joint: the types that mean something for its shape ({@link Plan#menuTypes}: a
     * free end: auto, end cap, tower, none; an L: auto, corner, inner corner, tower, none; a T: auto, T, tower, none; a
     * cross: auto, cross, tower, none; straight on: auto, tower, none), only those the family has a piece for, each
     * with its piece and whether it fits.
     */
    static void openJointMenu() {
        if (selectedJoint == null) return;
        List<Integer> at = linesAt(selectedJoint);
        if (at.isEmpty()) return;
        Plan.Line l = PlanView.lines.get(at.get(0));
        var fs = PieceCatalog.families(l.pack());
        var f = fs == null ? null : fs.get(l.family());
        int degree = at.size();
        boolean collinear = degree == 2 && Plan.collinearAt(PlanView.lines, at.get(0), at.get(1));
        List<Integer> types = Plan.menuTypes(degree, collinear);
        List<JointOption> out = new ArrayList<>();
        for (int t : types) {
            PieceSets.Piece piece = null;
            for (PieceSets.Role r : com.volcanicmg.mccitybuilder.planner.PieceLayout.jointRoles(t, degree, collinear, l.towers())) {
                var gs = f == null ? List.<PieceSets.Group>of() : PieceCatalog.groups(l.pack(), f, r);
                if (!gs.isEmpty()) {
                    piece = gs.get(0).pick(l.level(), 0);
                    break;
                }
            }
            // A chosen role the family lacks is a stand-in for another option: skip it (auto and none always count).
            if (piece == null && t != Plan.J_AUTO && t != Plan.J_NONE) continue;
            final PieceSets.Piece pc = piece;
            if (t != Plan.J_AUTO && t != Plan.J_NONE && pc != null && out.stream().anyMatch(o -> o.piece() != null && o.piece().path().equals(pc.path()) && o.type() != Plan.J_AUTO)) continue;
            Trial tr = trial(t);
            out.add(new JointOption(t, piece, tr.lacks(), tr.match(), tr.flips()));
        }
        jointOptions = out;
        jointMenu = true;
        menuPreview = -1;
    }

    /** @return true when the selected node is a wall's free end (one segment end there), not a joint */
    static boolean selectedIsEnd() {
        return selectedJoint != null && linesAt(selectedJoint).size() == 1;
    }

    /** Shows the joint with type {@code t} in the world while an option is hovered (-1: its own type); drawing only. */
    static void previewJoint(int t) {
        if (!jointMenu || selectedJoint == null || t == menuPreview) return;
        menuPreview = t;
        PlanView.relayout();
    }

    /** Closes the menu (the world shows the plan's own types again). Safe to call any time: entry, exit, a commit. */
    static void closeJointMenu(boolean applied) {
        if (jointMenu && menuPreview >= 0) PlanView.relayout();
        jointMenu = false;
        menuPreview = -1;
        jointOptions = List.of();
    }

    /** An option clicked: the joint takes the type (refused with a flash when a run can't fit); the menu closes. */
    static void pickJoint(int t) {
        closeJointMenu(false);
        setJointType(t);
    }

    private static int straights(List<List<PieceCatalog.Placed>> ps, int i) {
        int n = 0;
        if (i < ps.size()) for (PieceCatalog.Placed p : ps.get(i)) if (p.role() == PieceSets.Role.STRAIGHT || p.role() == PieceSets.Role.GATE) n++;
        return n;
    }

    private static int minRun(int i) {
        int m = Integer.MAX_VALUE;
        for (int l : PieceCatalog.runLengths(PlanView.lines.get(i))) m = Math.min(m, l);
        return m == Integer.MAX_VALUE ? 1 : m;
    }

    /** Esc in edit mode: deselect; with nothing selected, @return false (edit mode then ends) */
    static boolean deselect() {
        if (jointMenu) {
            closeJointMenu(false);
            return true;
        }
        if (selectedJoint == null && sel.isEmpty() && PlanView.selectedLine < 0) return false;
        select(List.of(), false);
        return true;
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
        // Free: onto nearby segments' axes and their seam grid, with a small fixed radius (a nudge, not a magnet).
        PieceSets.Piece p = PieceCatalog.primary(next(new int[]{0, 0}, new int[]{0, 0}), 0);
        List<List<Integer>> ax = LineSnap.axes(LineSnap.near(segs, cx, cz, 32), p == null ? 0 : p.run(), 3);
        double ar = Math.min(r, LineSnap.RADIUS);
        int x = LineSnap.nearest(cx, ax.get(0), ar), z = LineSnap.nearest(cz, ax.get(1), ar);
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
        adjust = null;
        merge = false;
        // Another segment's end, from any side: a joint exactly there (its free end may move onto this line).
        int bestI = -1, bestK = -1;
        double bd = r * r;
        for (int i = 0; i < PlanView.lines.size(); i++) {
            Plan.Line l = PlanView.lines.get(i);
            int[][] ends = l.ends();
            for (int k = 0; k < 2; k++) {
                double d = (ends[k][0] + 0.5 - cx) * (ends[k][0] + 0.5 - cx) + (ends[k][1] + 0.5 - cz) * (ends[k][1] + 0.5 - cz);
                if (d <= bd && !(ends[k][0] == start[0] && ends[k][1] == start[1])) {
                    bd = d;
                    bestI = i;
                    bestK = k;
                }
            }
        }
        if (bestI >= 0) {
            Plan.Line a = PlanView.lines.get(bestI);
            int[] e = a.ends()[bestK];
            boolean free = endCount(e) == 1 && !PlanView.locked(a);
            int[] j = LineSnap.joinEnd(start, a.ends(), bestK, free, r);
            if (j != null && (j[2] != e[0] || j[3] != e[1])) {
                // The other segment's end moves: refused when that would leave it without a straight.
                int[] o = a.ends()[1 - bestK];
                int cells = Math.max(Math.abs(j[2] - o[0]), Math.abs(j[3] - o[1])) + 1, other = bestI < PlanView.lineOffs.size() ? PlanView.lineOffs.get(bestI)[1 - bestK] : 0;
                if (!LineSnap.joinKeeps(cells, other, PieceCatalog.expectedReach(a, jointDefault), PieceCatalog.runLengths(a))) j = null;
            }
            if (j != null) {
                if (j[2] != e[0] || j[3] != e[1]) adjust = new int[]{bestI, bestK, j[2], j[3]};
                snap = adjust != null ? "join_moved" : "joint";
                jointPoints(new int[]{j[0], j[1]});
                return new int[]{j[0], j[1]};
            }
        }
        int[] raw = LineSnap.snapEnd(start, cx, cz, prevDir, false, List.of(), 90);
        if (raw[0] == start[0] && raw[1] == start[1]) return raw;
        int dir = com.volcanicmg.mccitybuilder.planner.PieceLayout.dir(start, raw), sx = dir == 0 ? 1 : dir == 2 ? -1 : 0, sz = dir == 1 ? 1 : dir == 3 ? -1 : 0;
        double along = dir % 2 == 0 ? (cx - start[0] - 0.5) * sx : (cz - start[1] - 0.5) * sz;
        // Onto another segment's body: a T at the middle of its nearest piece (split on the click).
        int t = LineSnap.crossing(start, dir, along, segs, r);
        if (t > 0) {
            int[] c = {start[0] + sx * t, start[1] + sz * t};
            // The chain's own start within reach of the T cell, or an end right at it, is the joint instead: a ring
            // must close on a corner, never split its first wall a cell from its start (other ends never hijack a T).
            int[] chainStart = chain.isEmpty() ? null : new int[]{chain.get(0).x0(), chain.get(0).z0()};
            for (int i = 0; i < PlanView.lines.size(); i++) {
                Plan.Line a = PlanView.lines.get(i);
                for (int k = 0; k < 2; k++) {
                    int[] e = a.ends()[k];
                    if (e[0] == start[0] && e[1] == start[1] || !LineSnap.closes(c, e, chainStart, r)) continue;
                    int[] j = LineSnap.joinEnd(start, a.ends(), k, endCount(e) == 1 && !PlanView.locked(a), r);
                    if (j == null) continue;
                    if (j[2] != e[0] || j[3] != e[1]) adjust = new int[]{i, k, j[2], j[3]};
                    snap = adjust != null ? "join_moved" : "joint";
                    jointPoints(new int[]{j[0], j[1]});
                    return new int[]{j[0], j[1]};
                }
            }
            snap = "tee";
            snapPoints = List.of(c);
            return c;
        }
        int cells = LineSnap.length(start, raw) + 1;
        // A perpendicular wall's free end the run passes: the end moves onto the line (along its own axis) and the
        // run is split there on the click, a T, like dragging that end onto the line by hand.
        teeCell = null;
        List<int[]> ends = new ArrayList<>();
        List<int[]> endRef = new ArrayList<>();
        for (int i = 0; i < PlanView.lines.size(); i++) {
            Plan.Line a = PlanView.lines.get(i);
            boolean across = (a.z0() == a.z1()) != (dir % 2 == 0);
            for (int k = 0; k < 2; k++) {
                int[] e = a.ends()[k];
                boolean free = endCount(e) == 1 && !PlanView.locked(a) && !(e[0] == start[0] && e[1] == start[1]);
                ends.add(new int[]{e[0], e[1], across && free ? 1 : 0});
                endRef.add(new int[]{i, k});
            }
        }
        int[] pass = LineSnap.passing(start, dir, cells, ends, r);
        if (pass != null) {
            int[] ref = endRef.get(pass[2]), e = ends.get(pass[2]);
            if (e[0] != pass[0] || e[1] != pass[1]) adjust = new int[]{ref[0], ref[1], pass[0], pass[1]};
            teeCell = new int[]{pass[0], pass[1]};
            snap = "tee_end";
            snapPoints = List.of(teeCell);
        }
        Plan.Line nl = next(start, raw);
        int[] lens = PieceCatalog.runLengths(nl);
        if (liveOffs != null && lens.length > 0 && !net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            // The free end tiles exactly (its cap if any); the joint a next segment brings grows this one then.
            int off1 = liveOffs[1];
            Plan.Line prev = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            boolean straightOn = prev != null && !PlanView.locked(prev) && prevDir != null && Integer.signum(prevDir[0]) == sx && Integer.signum(prevDir[1]) == sz;
            int base = 0, off0 = liveOffs[0];
            if (straightOn) {
                // Straight on: one longer segment (dropped as an extension of the last), tiled as a whole.
                int idx = PlanView.lines.indexOf(prev);
                base = LineSnap.length(new int[]{prev.x0(), prev.z0()}, start);
                off0 = idx >= 0 && idx < PlanView.lineOffs.size() ? PlanView.lineOffs.get(idx)[0] : 0;
                merge = true;
            }
            int n = LineSnap.snapLength(base + cells, off0, off1, lens);
            if (n - base >= 2 && LineSnap.exact(n, off0, off1, lens)) {
                snap = "pieces";
                snapPieces = com.volcanicmg.mccitybuilder.planner.PieceLayout.fill(n - off0 - off1, lens).size();
                cells = n - base;
            }
        }
        int[] c = {start[0] + sx * (cells - 1), start[1] + sz * (cells - 1)};
        guide = new int[]{start[0], start[1], c[0], c[1]};
        return c;
    }

    /**
     * @return how far (as a vector) the chain's last segment grows at its end so its straights stay whole once the
     * corner the live segment makes takes its cells, or null (no corner, a snapped end, locked, already fitting)
     */
    private static int[] chainGrowth(int[] e) {
        if (chain.isEmpty() || merge || adjust != null || e == null || e[0] == start[0] && e[1] == start[1] || snap != null && !snap.equals("pieces")) return null;
        Plan.Line prev = chain.get(chain.size() - 1);
        int idx = PlanView.lines.indexOf(prev);
        if (idx < 0 || PlanView.locked(prev) || prev.x1() != start[0] || prev.z1() != start[1] || endCount(start) != 1 || idx >= PlanView.lineOffs.size()) return null;
        int pdir = com.volcanicmg.mccitybuilder.planner.PieceLayout.dir(new int[]{prev.x0(), prev.z0()}, start), ldir = com.volcanicmg.mccitybuilder.planner.PieceLayout.dir(start, e);
        if (ldir == pdir || ldir == (pdir + 2) % 4) return null;
        int type = PlanView.jointTypes.getOrDefault(Plan.cell(start[0], start[1]), jointDefault);
        List<PieceSets.Role> roles = com.volcanicmg.mccitybuilder.planner.PieceLayout.jointRoles(type, 2, false, prev.towers());
        PieceSets.Role role = roles.isEmpty() ? null : roles.get(0);
        int[] lens = PieceCatalog.runLengths(prev);
        int reach = PieceCatalog.reachOf(prev, role), max = reach;
        for (int l : lens) max = Math.max(max, l + reach);
        int d = LineSnap.extension(Plan.cells(prev), PlanView.lineOffs.get(idx)[0], reach, lens, max);
        if (d <= 0) return null;
        int sx = Integer.signum(start[0] - prev.x0()), sz = Integer.signum(start[1] - prev.z0());
        return new int[]{sx * d, sz * d};
    }

    private static Plan.Line grown(Plan.Line l, int[] g) {
        return l.between(l.x0(), l.z0(), l.x1() + g[0], l.z1() + g[1]);
    }

    /**
     * After a drop: walls meeting it at {@code cells} whose joint changed (a corner becoming a T) and lost a straight
     * grow at their free end to keep it; a wall fixed at both ends that lost one is reported.
     */
    private static void refitAround(int[][] cells, Plan.Line added) {
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < PlanView.lines.size(); i++) {
            Plan.Line o = PlanView.lines.get(i);
            if (o.equals(added)) continue;
            for (int[] e : o.ends()) for (int[] c : cells) if (e[0] == c[0] && e[1] == c[1] && !at.contains(i)) at.add(i);
        }
        if (at.isEmpty()) return;
        List<List<PieceCatalog.Placed>> before = PlanView.linePieces;
        PieceCatalog.Layout lay = PieceCatalog.layout(PlanView.lines, PlanView.jointTypes);
        List<Integer> lost = new ArrayList<>();
        for (int i : at) if (i < before.size() && straights(lay.lines(), i) < straights(before, i)) lost.add(i);
        if (lost.isEmpty()) return;
        List<Plan.Line> ls = Plan.refitFree(PlanView.lines, lost, lay.offs(), lensAll(), PlanView::locked, 12);
        PieceCatalog.Layout lay2 = PieceCatalog.layout(ls, PlanView.jointTypes);
        for (int i : lost)
            if (straights(lay2.lines(), i) < straights(before, i)) {
                flash = "mccitybuilder.line.lost_piece";
                flashUntil = System.currentTimeMillis() + 2500;
            }
        for (int k = 0; k < chain.size(); k++) {
            int ci = PlanView.lines.indexOf(chain.get(k));
            if (ci >= 0) chain.set(k, ls.get(ci));
        }
        PlanView.setLines(ls);
    }

    /** @return how many planned segments end at cell c */
    private static int endCount(int[] c) {
        int n = 0;
        for (Plan.Line l : PlanView.lines) for (int[] e : l.ends()) if (e[0] == c[0] && e[1] == c[1]) n++;
        return n;
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
            checkpoint();
            start = hover != null ? hover : LineSnap.snapStart(hit.getLocation().x, hit.getLocation().z, segments());
            PlanView.splitAt(start);
            start = extendForJoint(start);
            liveKey = null;
            return true;
        }
        if (end == null || end[0] == start[0] && end[1] == start[1]) return true;
        if (outside(next(start, end))) {
            flash = "mccitybuilder.line.outside";
            flashUntil = System.currentTimeMillis() + 1500;
            return false;
        }
        if (!LineSnap.canDrop(liveLen, liveNeed)) {
            flash = liveNeed == Integer.MAX_VALUE ? "mccitybuilder.line.no_fit" : "mccitybuilder.line.too_short";
            flashArg = liveNeed;
            flashUntil = System.currentTimeMillis() + 1500;
            return false;
        }
        checkpoint();
        // The chain's last segment grows for the corner; this segment starts at the grown end.
        if (grow != null && !chain.isEmpty()) {
            Plan.Line prev = chain.get(chain.size() - 1), g2 = grown(prev, grow);
            PlanView.replaceLine(prev, g2);
            chain.set(chain.size() - 1, g2);
            start = new int[]{start[0] + grow[0], start[1] + grow[1]};
            end = new int[]{end[0] + grow[0], end[1] + grow[1]};
            grow = null;
        }
        // A free end joined from across moves onto this line; ending on a segment splits it there (a T).
        if (adjust != null && adjust[0] < PlanView.lines.size()) {
            Plan.Line a = PlanView.lines.get(adjust[0]);
            PlanView.replaceLine(a, moveEnd(a, adjust[1], adjust[2], adjust[3]));
        }
        PlanView.splitAt(end);
        if (heightMode == Plan.CHAIN) {
            // The chain shares one level: high enough for this segment's ground too; earlier segments follow it.
            int nb = com.volcanicmg.mccitybuilder.planner.PieceLayout.chainBase(chainBase, liveSegMax);
            if (nb != chainBase)
                for (int k = 0; k < chain.size(); k++) {
                    if (PlanView.locked(chain.get(k))) continue;
                    Plan.Line o = chain.get(k), n = o.withHeight(o.height(), o.dy(), nb);
                    PlanView.replaceLine(o, n);
                    chain.set(k, n);
                }
            chainBase = nb;
        }
        Plan.Line l = next(start, end);
        int[] splitAt = teeCell == null ? null : teeCell.clone();
        if (merge && !chain.isEmpty() && !PlanView.locked(chain.get(chain.size() - 1))) {
            // Straight on: the last segment grows instead of a second one butting against it.
            Plan.Line prev = chain.get(chain.size() - 1);
            l = prev.between(prev.x0(), prev.z0(), end[0], end[1]);
            PlanView.replaceLine(prev, l);
            chain.set(chain.size() - 1, l);
        } else {
            PlanView.addLine(l);
            chain.add(l);
        }
        if (splitAt != null) PlanView.splitAt(splitAt);
        // The joint default for the joints this segment makes.
        for (int[] c : splitAt == null ? new int[][]{start, end} : new int[][]{start, end, splitAt})
            if (jointDefault != Plan.J_AUTO && !PlanView.jointTypes.containsKey(Plan.cell(c[0], c[1]))) PlanView.setJointType(c[0], c[1], jointDefault);
        refitAround(new int[][]{start, end}, l);
        prevDir = new int[]{end[0] - start[0], end[1] - start[1]};
        start = end;
        end = null;
        // A gate is for one segment.
        gate = false;
        clearLive();
        return true;
    }

    /** @return the segment with end {@code k} (0 = start) moved to (x, z) */
    static Plan.Line moveEnd(Plan.Line l, int k, int x, int z) {
        return k == 0 ? l.between(x, z, l.x1(), l.z1()) : l.between(l.x0(), l.z0(), x, z);
    }

    /**
     * Starting on a segment's free end: the joint the new segment brings takes cells from that segment, so it grows by
     * the fewest cells that keep its straights whole (none when it already fits).
     *
     * @return where the chain starts (the moved end)
     */
    private static int[] extendForJoint(int[] c) {
        if (endCount(c) != 1) return c;
        for (int i = 0; i < PlanView.lines.size(); i++) {
            Plan.Line a = PlanView.lines.get(i);
            int k = a.x0() == c[0] && a.z0() == c[1] ? 0 : a.x1() == c[0] && a.z1() == c[1] ? 1 : -1;
            if (k < 0 || PlanView.locked(a)) continue;
            int[] lens = PieceCatalog.runLengths(a);
            int reach = PieceCatalog.expectedReach(a, jointDefault), other = i < PlanView.lineOffs.size() ? PlanView.lineOffs.get(i)[1 - k] : 0;
            int cells = LineSnap.length(new int[]{a.x0(), a.z0()}, new int[]{a.x1(), a.z1()}) + 1, max = reach;
            for (int l : lens) max = Math.max(max, l + reach);
            int d = LineSnap.extension(cells, other, reach, lens, max);
            if (d <= 0) return c;
            int[] o = a.ends()[1 - k];
            int dx = Integer.signum(c[0] - o[0]), dz = Integer.signum(c[1] - o[1]);
            int[] moved = {c[0] + dx * d, c[1] + dz * d};
            PlanView.replaceLine(a, moveEnd(a, k, moved[0], moved[1]));
            flash = "mccitybuilder.line.extended";
            flashArg = d;
            flashUntil = System.currentTimeMillis() + 1500;
            return moved;
        }
        return c;
    }

    /**
     * Done / right-click / Esc: the chain's last segment keeps no gap at its free end (the room it kept for a joint
     * that didn't come) - it is trimmed to its straights - then the chain ends.
     */
    static void finishChain() {
        if (!chain.isEmpty()) {
            Plan.Line l = chain.get(chain.size() - 1);
            int i = PlanView.lines.indexOf(l);
            int left = i >= 0 && i < PlanView.lineShort.size() ? PlanView.lineShort.get(i) : 0;
            int cells = LineSnap.length(new int[]{l.x0(), l.z0()}, new int[]{l.x1(), l.z1()}) + 1;
            int need = i >= 0 && i < PlanView.lineNeed.size() ? PlanView.lineNeed.get(i) : Integer.MAX_VALUE;
            if (left > 0 && endCount(new int[]{l.x1(), l.z1()}) == 1 && cells - left >= need && !PlanView.locked(l)) {
                int dx = Integer.signum(l.x1() - l.x0()), dz = Integer.signum(l.z1() - l.z0());
                checkpoint();
                PlanView.replaceLine(l, l.between(l.x0(), l.z0(), l.x1() - dx * left, l.z1() - dz * left));
            }
        }
        endChain();
    }

    private static Snap snap() {
        return new Snap(new ArrayList<>(PlanView.lines), new java.util.HashMap<>(PlanView.jointTypes), new ArrayList<>(chain), start == null ? null : start.clone(),
                prevDir == null ? null : prevDir.clone(), chainBase, PlanView.selectedLine, selectedJoint == null ? null : selectedJoint.clone());
    }

    private static boolean same(Snap a, Snap b) {
        return a.lines().equals(b.lines()) && a.joints().equals(b.joints()) && a.chain().equals(b.chain()) && java.util.Arrays.equals(a.start(), b.start())
                && java.util.Arrays.equals(a.prevDir(), b.prevDir()) && a.chainBase() == b.chainBase();
    }

    /** Remembers the plan's lines before a user change, for Undo (a state equal to the last one isn't kept twice). */
    static void checkpoint() {
        Snap s = snap();
        if (!undos.isEmpty() && same(undos.peek(), s)) return;
        undos.push(s);
        while (undos.size() > 64) undos.removeLast();
    }

    /** Forgets the undo history (another plan was loaded). */
    static void resetUndo() {
        undos.clear();
        takenDown.clear();
    }

    static boolean canUndo() {
        return !undos.isEmpty();
    }

    /**
     * Backspace / Undo: back to the last checkpoint (one that changes nothing is skipped). Built and in-flight segments
     * stay as they are now ({@link Plan#restore}); right after a click the chain goes on from the previous end.
     */
    static void undo() {
        Snap now = snap();
        while (!undos.isEmpty()) {
            Snap s = undos.pop();
            List<Plan.Line> ls = Plan.restore(s.lines(), PlanView.lines, PlanView::locked, takenDown::contains);
            List<Plan.Line> ch = new ArrayList<>(s.chain());
            ch.retainAll(ls);
            Snap to = new Snap(ls, s.joints(), ch, s.start(), s.prevDir(), s.chainBase(), s.selectedLine(), s.selectedJoint());
            if (same(to, now)) continue;
            closeJointMenu(false);
            PlanView.jointTypes.clear();
            PlanView.jointTypes.putAll(s.joints());
            chain.clear();
            chain.addAll(ch);
            start = s.start();
            prevDir = s.prevDir();
            chainBase = s.chainBase();
            end = null;
            select(s.selectedLine() >= 0 && s.selectedLine() < ls.size() ? List.of(s.selectedLine()) : List.of(), false);
            selectedJoint = s.selectedJoint();
            PlanView.setLines(ls);
            clearLive();
            return;
        }
        endChain();
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
        if (s != null) setSelected(s.with(nx, s.level(), s.variant(), s.towers(), s.gate()));
        else if ("wall".equals(t)) wallFamily = nx;
        else roadFamily = nx;
    }

    /** Level (when the family's straights are levels) or variant (-1 = varied, then 0..n-1). */
    static void cycleLevel(int d, int max, boolean levels) {
        Plan.Line s = PlanView.selectedLine();
        int lv = s != null ? s.level() : level, va = s != null ? s.variant() : variant;
        if (levels) lv = Math.floorMod(lv - 1 + d, Math.max(1, max)) + 1;
        else va = Math.floorMod(va + 1 + d, max + 1) - 1;
        if (s != null) setSelected(s.with(s.family(), lv, va, s.towers(), s.gate()));
        else {
            level = lv;
            variant = va;
        }
    }

    /** R: the other side out (straights and gates turned 180°), for the selected segment or the next ones. */
    static void toggleFlip() {
        if (edit && sel.size() > 1) {
            List<Plan.Line> ls = new ArrayList<>(PlanView.lines);
            for (int i : sel) if (i < ls.size() && !PlanView.locked(ls.get(i))) ls.set(i, ls.get(i).withFlip(!ls.get(i).flip()));
            if (!ls.equals(PlanView.lines)) checkpoint();
            PlanView.setLines(ls);
            return;
        }
        Plan.Line s = PlanView.selectedLine();
        if (s != null) setSelected(s.withFlip(!s.flip()));
        else flip = !flip;
    }

    /** An option change on the selected segment (undoable; none when it changes nothing). */
    private static void setSelected(Plan.Line n) {
        if (n.equals(PlanView.selectedLine())) return;
        checkpoint();
        PlanView.setLine(n);
    }

    /** Height mode: follow the ground, flat per segment, flat per chain, smooth. */
    static void cycleHeight(int d) {
        Plan.Line s = PlanView.selectedLine();
        int m = Math.floorMod((s != null ? s.height() : heightMode) + d, 4);
        if (s != null) setSelected(s.withHeight(m, s.dy(), m == Plan.CHAIN ? s.base() : Plan.NO_BASE));
        else {
            heightMode = m;
            chainBase = Plan.NO_BASE;
        }
    }

    /** PgUp / PgDn: raise or lower (blocks added to the mode's height). */
    static void raise(int d) {
        Plan.Line s = PlanView.selectedLine();
        if (s != null) setSelected(s.withHeight(s.height(), Math.max(-64, Math.min(64, s.dy() + d)), s.base()));
        else dy = Math.max(-64, Math.min(64, dy + d));
    }

    /**
     * Del / right-click / Remove: deletes the hovered planned segment (or the selected one); Undo brings it back.
     *
     * @return false when there was none
     */
    static boolean removeHovered() {
        List<Integer> which = edit && !sel.isEmpty() ? new ArrayList<>(sel)
                : List.of(hoverLine >= 0 && hoverLine < PlanView.lines.size() ? hoverLine : PlanView.selectedLine);
        List<Plan.Line> gone = new ArrayList<>();
        for (int i : which) if (i >= 0 && i < PlanView.lines.size()) gone.add(PlanView.lines.get(i));
        if (gone.isEmpty()) return false;
        // Built records: with the server part their pieces are taken down too (creative: matching blocks removed at
        // once; survival: a builder's REMOVE order per piece, which clears whatever stands where its blocks are).
        checkpoint();
        int sent = 0;
        for (Plan.Line l : gone)
            if (l.built() && PlanView.sendRemoval(l)) {
                sent++;
                takenDown.add(l);
            }
        if (sent > 0) {
            flash = Planner.creative() ? "mccitybuilder.line.built_deleted" : "mccitybuilder.line.built_remove_ordered";
            flashArg = sent;
            flashUntil = System.currentTimeMillis() + 4000;
        }
        List<Plan.Line> keep = new ArrayList<>(PlanView.lines);
        keep.removeAll(gone);
        PlanView.setLines(keep);
        select(List.of(), false);
        PlanView.selectedLine = -1;
        hoverLine = -1;
        endChain();
        return true;
    }

    // A perpendicular wall's free end the live run passes: the run is split there on the click (a T).
    static int[] teeCell;

    private static long clearArmed;

    /**
     * The tool's Clear: removes its planned segments (built and in-flight ones stay; huts, decorations and the other
     * tool's segments are untouched). The first click arms it for 3 s; Undo brings them all back.
     */
    static void clearTool() {
        List<Plan.Line> keep = Plan.clearTool(PlanView.lines, tool, PlanView::locked);
        int n = PlanView.lines.size() - keep.size();
        if (n == 0) return;
        long now = System.currentTimeMillis();
        if (now > clearArmed) {
            clearArmed = now + 3000;
            flash = "mccitybuilder.line.clear_confirm." + tool;
            flashArg = n;
            flashUntil = clearArmed;
            return;
        }
        clearArmed = 0;
        checkpoint();
        endChain();
        select(List.of(), false);
        PlanView.setLines(keep);
        // What stays and why: built records (Remove deletes them) and segments on their way to the server.
        int kept = 0;
        for (Plan.Line l : keep) if (l.tool().equals(tool)) kept++;
        flash = kept > 0 ? "mccitybuilder.line.cleared_kept." + tool : "mccitybuilder.line.cleared";
        flashArg = kept > 0 ? kept : n;
        flashUntil = now + 1500;
    }

    /** Gate in the selected wall segment, or in the next one drawn. */
    static void toggleGate() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null && s.isWall()) setSelected(s.with(s.family(), s.level(), s.variant(), s.towers(), !s.gate()));
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
        if (edit) {
            renderEdit(pose, lines);
            return;
        }
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

    /** Edit mode: the hovered / selected joint, the selected segment's end handles, and a drag's outcome (cyan). */
    private static void renderEdit(PoseStack pose, VertexConsumer lines) {
        for (int[] j : new int[][]{hoverJoint, selectedJoint}) {
            if (j == null) continue;
            boolean sel = j == selectedJoint;
            int y = groundY(j[0], j[1]);
            LevelRenderer.renderLineBox(pose, lines, j[0] - 0.2, y, j[1] - 0.2, j[0] + 1.2, y + 2.5, j[1] + 1.2, sel ? 1f : 0.3f, sel ? 1f : 0.9f, 1f, 1f);
        }
        Plan.Line s = PlanView.selectedLine();
        if (s != null)
            for (int[] e : s.ends()) {
                int y = groundY(e[0], e[1]);
                LevelRenderer.renderLineBox(pose, lines, e[0] + 0.1, y, e[1] + 0.1, e[0] + 0.9, y + 1.5, e[1] + 0.9, 0.3f, 0.9f, 1f, 1f);
            }
        if (dragPreview != null)
            for (Plan.Line l : dragPreview) {
                int y = groundY(l.x0(), l.z0()) + 1;
                LevelRenderer.renderLineBox(pose, lines, Math.min(l.x0(), l.x1()), y, Math.min(l.z0(), l.z1()), Math.max(l.x0(), l.x1()) + 1, y + 0.3,
                        Math.max(l.z0(), l.z1()) + 1, 0.3f, 0.9f, 1f, 1f);
            }
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
