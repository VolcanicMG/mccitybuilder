package com.volcanicmg.mccitybuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The road and wall tools, city-builder style, building from the style pack's own road / wall pieces: click a start
 * (snapped to a planned segment's end, or onto a segment to branch: that segment is split there), a live segment
 * follows the cursor along 0 / 90° (the pieces are straight runs), each click drops a segment into the plan and starts
 * the next at its end. Right-click / Esc ends the chain, Backspace takes back the last segment. Family, level /
 * variant and towers apply to the next segments, or to the selected planned segment.
 */
final class LineView {
    /** "road", "wall", or null when no line tool is active. */
    static String tool;
    // Options for the next segments: family per tool, level (level groups) or variant (-1 = varied), towers at joints.
    static String roadFamily, wallFamily;
    static int level = 1, variant = -1;
    static boolean towers;
    // The chain being drawn: its current start, the previous segment's direction, the plan lines it added (as lines).
    static int[] start, prevDir, end;
    static final List<Plan.Line> chain = new ArrayList<>();
    static List<PieceCatalog.Placed> live = List.of();
    static int liveShort;
    private static int[] liveFor;

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
        live = List.of();
        liveFor = null;
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

    /** Each frame: the live segment's end under the cursor (0 / 90° only) and its pieces. */
    static void aim(BlockHitResult hit) {
        if (!active() || start == null || hit == null || family() == null) {
            end = null;
            return;
        }
        end = LineSnap.snapEnd(start, hit.getLocation().x, hit.getLocation().z, prevDir, false, segments(), 90);
        if (liveFor == null || liveFor[0] != end[0] || liveFor[1] != end[1]) {
            liveFor = end;
            List<Plan.Line> all = new ArrayList<>(PlanView.lines);
            all.add(next(start, end));
            PieceCatalog.Layout lay = PieceCatalog.layout(all);
            live = lay.lines().get(lay.lines().size() - 1);
            liveShort = lay.shortened().get(lay.shortened().size() - 1);
        }
    }

    /** Click: start a chain (an endpoint, or a branch that splits a segment), or drop the live segment and go on. */
    static void click(BlockHitResult hit) {
        if (!active() || hit == null || family() == null) return;
        if (start == null) {
            start = LineSnap.snapStart(hit.getLocation().x, hit.getLocation().z, segments());
            PlanView.splitAt(start);
            return;
        }
        if (end == null || end[0] == start[0] && end[1] == start[1]) return;
        Plan.Line l = next(start, end);
        PlanView.addLine(l);
        chain.add(l);
        prevDir = new int[]{end[0] - start[0], end[1] - start[1]};
        start = end;
        end = null;
        liveFor = null;
        live = List.of();
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
        liveFor = null;
    }

    static Plan.Line next(int[] a, int[] b) {
        return new Plan.Line(tool, a[0], a[1], b[0], b[1], PlannerScreen.pack(), family(), level, variant, towers, false);
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
        liveFor = null;
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
        liveFor = null;
    }

    static void toggleTowers() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null) PlanView.setLine(s.with(s.family(), s.level(), s.variant(), !s.towers(), s.gate()));
        else towers = !towers;
        liveFor = null;
    }

    static void toggleGate() {
        Plan.Line s = PlanView.selectedLine();
        if (s != null && s.isWall()) PlanView.setLine(s.with(s.family(), s.level(), s.variant(), s.towers(), !s.gate()));
    }

    /** @return the summed block count of placed pieces (their material cost in blocks) */
    static int cost(List<PieceCatalog.Placed> ps) {
        int n = 0;
        for (PieceCatalog.Placed p : ps) n += p.blocks();
        return n;
    }

    /** The live segment's pieces as footprint outlines (cyan) and the chain start (white). */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        if (!active()) return;
        PlanView.outline(pose, lines, live, 0.3f, 0.85f, 1f);
        if (start != null) {
            Integer g = Planner.groundAt(start[0], start[1]);
            double y = g == null ? 64 : g;
            LevelRenderer.renderLineBox(pose, lines, start[0], y, start[1], start[0] + 1, y + 0.4, start[1] + 1, 1f, 1f, 1f, 1f);
        }
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
