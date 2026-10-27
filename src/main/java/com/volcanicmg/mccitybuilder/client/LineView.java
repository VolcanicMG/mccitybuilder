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
    static boolean towers, gate;
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
     * Each frame: the live segment's end under the cursor (0 / 90° only), or before a chain the cell under it; laid out
     * again (pieces, conflicts, ghosts) only when the key - ends, plan layout, options - changed.
     */
    static void aim(BlockHitResult hit) {
        String fam = active() ? family() : null;
        if (hit == null || fam == null) {
            end = hover = null;
            if (liveKey != null || ghosts > 0 || !live.isEmpty()) clearLive();
            return;
        }
        double cx = hit.getLocation().x, cz = hit.getLocation().z;
        int[] a, b;
        if (start == null) {
            hover = LineSnap.snapStart(cx, cz, segments());
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
            end = LineSnap.snapEnd(start, cx, cz, prevDir, false, segments(), 90);
            a = start;
            b = end;
        }
        int[] key = {a[0], a[1], b[0], b[1], start == null ? 0 : 1, PlanView.layoutVersion,
                Objects.hash(tool, fam, PlannerScreen.pack(), level, variant, towers, gate)};
        if (!LineSnap.relayout(liveKey, key)) return;
        liveKey = key;
        Plan.Line nl = next(a, b);
        boolean complete;
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
            all.add(nl);
            PieceCatalog.Layout lay = PieceCatalog.layout(all);
            int last = all.size() - 1;
            live = lay.lines().get(last);
            liveShort = lay.shortened().get(last);
            liveGap = lay.gaps().get(last);
            liveNeed = lay.need().get(last);
            liveLen = LineSnap.length(a, b) + 1;
            complete = lay.complete();
        }
        List<int[]> buildings = PlanView.buildingBoxes();
        List<Boolean> bad = new ArrayList<>();
        for (PieceCatalog.Placed p : live) bad.add(PlanView.bad(p, buildings));
        liveBad = bad;
        showGhosts();
        // Pieces still loading: lay out again next frame.
        if (!complete) liveKey = null;
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
            endChain();
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
        return new Plan.Line(tool, a[0], a[1], b[0], b[1], PlannerScreen.pack(), family(), level, variant, towers, wall() && gate);
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
