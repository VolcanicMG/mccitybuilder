package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/**
 * The joint alignment editor: one joint of a style's walls or roads (pack, family, joint role, case) laid out with
 * its meeting runs the way the planner lays them out, in a 3D view and a top-down map. Per run: the sideways shift and
 * the depth it butts on the joint, with the cross-section match and a red mark when the overlap is destructive; the
 * joint piece's turn. Save stores the per-pack override (used before the automatic search); Reset returns a run to
 * auto. Adjustments show in the planned walls at once; Close drops what wasn't saved.
 */
final class JointEditorScreen extends Screen {
    private static final PieceSets.Role[] ROLES = {PieceSets.Role.CORNER, PieceSets.Role.INNER, PieceSets.Role.TEE, PieceSets.Role.CROSS, PieceSets.Role.TOWER,
            PieceSets.Role.END};
    private static final String[] CASES = {"L-left", "L-right", "T", "cross", "end"};
    // Directions out of the joint per case (0 east, 1 south, 2 west, 3 north); the first line arrives at the joint.
    private static final int[][] OUTS = {{2, 3}, {2, 1}, {2, 0, 1}, {2, 0, 1, 3}, {2}};

    private String pack, fam;
    private int role, kase, turn = -1, runSel;
    private boolean flipped;
    // Unsaved adjustments: override key -> {shift, depth}.
    private final Map<String, int[]> drafts = new HashMap<>();
    private record Hit(int x, int y, int w, int h, Runnable run) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private String note;
    private long noteUntil;
    private int[] view;
    private boolean drag;

    /** One laid-out run: direction, straight (path, turns, grid), fit, destructive, override key. */
    private record Run(int out, String path, int turns, PieceLayout.Grid grid, PieceLayout.Fit fit, boolean bad, String key, boolean overridden) {
    }

    JointEditorScreen(String pack, String family, PieceSets.Role role, int turn) {
        super(Component.translatable("mccitybuilder.align.title"));
        this.pack = pack;
        this.fam = family;
        for (int i = 0; i < ROLES.length; i++) if (ROLES[i] == role) this.role = i;
        this.kase = role == PieceSets.Role.TEE ? 2 : role == PieceSets.Role.CROSS ? 3 : role == PieceSets.Role.END ? 4 : role == PieceSets.Role.INNER ? 0 : 1;
        this.turn = turn;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // Back to the planner if it still runs (unsaved adjustments go in removed()).
        minecraft.setScreen(Planner.active() ? new PlannerScreen() : null);
    }

    @Override
    public void removed() {
        // However the screen goes (Esc, the planner ending, death, another screen), unsaved drafts don't stay.
        PieceCatalog.clearDrafts();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        Planner.guard(() -> draw(g, mx, my));
    }

    private void draw(GuiGraphics g, int mx, int my) {
        hits.clear();
        renderBackground(g, mx, my, 0);
        int pw = 200, px = 6, py = 6, ph = height - 12;
        VanillaUi.panel(g, px, py, pw, ph);
        int ix = px + 7, iw = pw - 14, iy = py + 7;
        g.drawString(font, title, ix, iy, VanillaUi.LABEL, false);
        iy += 12;
        List<String> packs = Blueprints.packs();
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.pack", pack).getString(), mx, my, d -> {
            pack = packs.get(Math.floorMod(packs.indexOf(pack) + d, packs.size()));
            fam = null;
            drafts.clear();
            PieceCatalog.clearDrafts();
        });
        List<String> fams = new ArrayList<>();
        for (String t : new String[]{"wall", "road"}) {
            List<String> k = PieceCatalog.familyKeys(pack, t);
            if (k != null) fams.addAll(k);
        }
        if (fam == null || !fams.contains(fam)) fam = fams.isEmpty() ? null : fams.get(0);
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.family", fam == null ? "-" : fam).getString(), mx, my, d -> {
            if (!fams.isEmpty()) fam = fams.get(Math.floorMod(fams.indexOf(fam) + d, fams.size()));
            drafts.clear();
            PieceCatalog.clearDrafts();
        });
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.role", Component.translatable("mccitybuilder.line.role." + ROLES[role].name().toLowerCase(java.util.Locale.ROOT))).getString(),
                mx, my, d -> {
                    role = Math.floorMod(role + d, ROLES.length);
                    dropDrafts();
                });
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.case", CASES[kase] + (flipped ? " (flipped)" : "")).getString(), mx, my, d -> {
            int k = Math.floorMod(kase * 2 + (flipped ? 1 : 0) + d, CASES.length * 2);
            kase = k / 2;
            flipped = k % 2 == 1;
            runSel = 0;
            dropDrafts();
        });
        Layout lay = layout();
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.turn", turn < 0 ? "auto (" + (lay == null ? "-" : lay.turn) + ")" : "" + turn).getString(), mx, my,
                d -> {
                    turn = Math.floorMod(turn + 1 + d, 5) - 1;
                    dropDrafts();
                });
        if (lay == null) {
            g.drawString(font, Component.translatable("mccitybuilder.align.missing"), ix, iy + 4, 0xFFA02020, false);
            return;
        }
        // Run tabs.
        runSel = Math.min(runSel, lay.runs.size() - 1);
        int tw = (iw - 2 * (lay.runs.size() - 1)) / lay.runs.size();
        for (int r = 0; r < lay.runs.size(); r++) {
            int bx = ix + r * (tw + 2), fr = r;
            Run run = lay.runs.get(r);
            VanillaUi.button(g, bx, iy, tw, 16, r == runSel, false, in(mx, my, bx, iy, tw, 16));
            String l = "ESWN".charAt(run.out()) + (run.overridden() ? "*" : "");
            g.drawString(font, l, bx + (tw - font.width(l)) / 2, iy + 4, run.bad() ? 0xFFFF4040 : VanillaUi.ink(r == runSel, false), false);
            hits.add(new Hit(bx, iy, tw, 16, () -> runSel = fr));
        }
        iy += 20;
        Run sel = lay.runs.get(runSel);
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.shift", sel.fit().shift()).getString(), mx, my, d -> adjust(sel, d, 0));
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.depth", sel.fit().depth()).getString(), mx, my, d -> adjust(sel, 0, d));
        g.drawString(font, Component.translatable("mccitybuilder.align.match", String.format("%.2f", sel.fit().match())), ix, iy, VanillaUi.LABEL, false);
        iy += 11;
        if (sel.bad()) g.drawString(font, Component.translatable("mccitybuilder.align.destructive"), ix, iy, 0xFFC02020, false);
        else g.drawString(font, Component.translatable(sel.overridden() ? "mccitybuilder.align.custom" : "mccitybuilder.align.auto"), ix, iy, 0xFF505050, false);
        iy += 14;
        String[] keys = {"save", "reset", "close"};
        int bw = (iw - 4) / 3;
        for (int k = 0; k < 3; k++) {
            int bx = ix + k * (bw + 2), fk = k;
            VanillaUi.button(g, bx, iy, bw, 20, false, false, in(mx, my, bx, iy, bw, 20));
            String l = Component.translatable("mccitybuilder.align." + keys[k]).getString();
            g.drawString(font, l, bx + (bw - font.width(l)) / 2, iy + 6, VanillaUi.INK, false);
            hits.add(new Hit(bx, iy, bw, 20, () -> {
                if (fk == 0) save(lay);
                else if (fk == 1) reset(sel);
                else onClose();
            }));
        }
        iy += 24;
        if (note != null && System.currentTimeMillis() < noteUntil) g.drawString(font, font.plainSubstrByWidth(note, iw), ix, iy, 0xFFC02020, false);
        // The 3D view (drag turns it, the wheel zooms) and the top-down map with the boxes.
        int vx = px + pw + 6, vw = width - vx - 6, mh = Math.min(160, height / 3), vh = height - 12 - mh - 6;
        view = new int[]{vx, py, vw, vh};
        VanillaUi.slot(g, vx, py, vw, vh);
        List<PieceView.Part> parts = new ArrayList<>();
        parts.add(new PieceView.Part("align|" + pack + "|" + lay.jointPath + "|" + lay.turn, lay.jointBp, lay.jb[0], -lay.joint.ground(), lay.jb[1]));
        for (Run r : lay.runs) {
            Blueprint bp = PieceCatalog.rotated(pack, r.path(), r.turns());
            if (bp == null) continue;
            for (int[] b : runBoxes(lay, r)) parts.add(new PieceView.Part("align|" + pack + "|" + r.path() + "|" + Math.floorMod(r.turns(), 4), bp, b[0], -r.grid().ground(), b[1]));
        }
        PieceView.renderScene(g, parts, vx + 1, py + 1, vw - 2, vh - 2);
        map(g, lay, vx, py + vh + 6, vw, mh);
    }

    /** The top-down map: columns by their top block's map colour, the joint's box (yellow), runs (green, cyan selected, red destructive). */
    private void map(GuiGraphics g, Layout lay, int x, int y, int w, int h) {
        VanillaUi.slot(g, x, y, w, h);
        int r = 16, cell = Math.max(2, Math.min((w - 4) / (2 * r + 1), (h - 4) / (2 * r + 1))), ox = x + w / 2, oz = y + h / 2;
        column(g, lay.joint, lay.jb[0], lay.jb[1], ox, oz, cell, r);
        for (Run rn : lay.runs) for (int[] b : runBoxes(lay, rn)) column(g, rn.grid(), b[0], b[1], ox, oz, cell, r);
        box(g, lay.jb, ox, oz, cell, 0xFFFFE040);
        for (int i = 0; i < lay.runs.size(); i++) {
            Run rn = lay.runs.get(i);
            int fi = i;
            int[] b = runBoxes(lay, rn).get(0);
            box(g, b, ox, oz, cell, rn.bad() ? 0xFFFF4040 : i == runSel ? 0xFF40E0FF : 0xFF40E040);
            hits.add(new Hit(ox + b[0] * cell, oz + b[1] * cell, (b[2] - b[0] + 1) * cell, (b[3] - b[1] + 1) * cell, () -> runSel = fi));
        }
    }

    private static void column(GuiGraphics g, PieceLayout.Grid p, int px, int pz, int ox, int oz, int cell, int r) {
        for (int x = 0; x < p.sx(); x++)
            for (int z = 0; z < p.sz(); z++)
                for (int y = p.sy() - 1; y >= 0; y--) {
                    int id = p.id(x, y, z);
                    if (id <= 0) continue;
                    int wx = px + x, wz = pz + z;
                    if (Math.abs(wx) > r || Math.abs(wz) > r) break;
                    int c = BuiltInRegistries.BLOCK.byId(id - 1).defaultMapColor().col;
                    g.fill(ox + wx * cell, oz + wz * cell, ox + (wx + 1) * cell, oz + (wz + 1) * cell, 0xFF000000 | c);
                    break;
                }
    }

    private static void box(GuiGraphics g, int[] b, int ox, int oz, int cell, int c) {
        int x0 = ox + b[0] * cell, z0 = oz + b[1] * cell, x1 = ox + (b[2] + 1) * cell, z1 = oz + (b[3] + 1) * cell;
        g.fill(x0, z0, x1, z0 + 1, c);
        g.fill(x0, z1 - 1, x1, z1, c);
        g.fill(x0, z0, x0 + 1, z1, c);
        g.fill(x1 - 1, z0, x1, z1, c);
    }

    private record Layout(String jointPath, int turn, Blueprint jointBp, PieceLayout.Grid joint, int[] jb, int width, int run, List<Run> runs) {
    }

    private List<int[]> runBoxes(Layout lay, Run r) {
        return PieceLayout.boxes(new int[]{0, 0}, r.out(), r.fit().off(), List.of(lay.run, lay.run), lay.width, r.fit().shift());
    }

    /** @return the joint laid out as the planner would (overrides and drafts applied), or null while pieces load */
    private Layout layout() {
        var fs = PieceCatalog.families(pack);
        PieceSets.Family f = fs == null || fam == null ? null : fs.get(fam);
        if (f == null) return null;
        List<PieceSets.Group> st = PieceCatalog.groups(pack, f, PieceSets.Role.STRAIGHT), js = PieceCatalog.groups(pack, f, ROLES[role]);
        if (st.isEmpty() || js.isEmpty()) return null;
        int level = Math.max(1, LineView.level);
        PieceSets.Piece sp = st.get(0).pick(st.get(0).levels() ? level : 0, 0), jp = js.get(0).pick(level, 0);
        int[] outs = OUTS[kase], c = {0, 0};
        int width = sp.across(), run = sp.run();
        // The joint's turn: chosen, or the planner's pick (best face score over the runs).
        int t = turn;
        if (t < 0) {
            double bs = -1;
            for (int k = 0; k < 4; k++) {
                PieceLayout.Grid jg = PieceCatalog.gridFor(pack, jp.path(), k);
                if (jg == null) return null;
                double sc = 0;
                for (int q = 0; q < outs.length; q++) {
                    PieceLayout.Grid sg = PieceCatalog.gridFor(pack, sp.path(), sturn(sp, outs, q));
                    if (sg == null) return null;
                    sc += PieceLayout.faceScore(c, outs[q], PieceLayout.centred(c, jg.sx(), jg.sz()), jg, sg, width, run);
                }
                if (sc > bs + 1e-9) {
                    bs = sc;
                    t = k;
                }
            }
        }
        PieceLayout.Grid jg = PieceCatalog.gridFor(pack, jp.path(), t);
        Blueprint jbp = PieceCatalog.rotated(pack, jp.path(), t);
        if (jg == null || jbp == null) return null;
        int[] jb = PieceLayout.centred(c, jg.sx(), jg.sz());
        List<Run> runs = new ArrayList<>();
        for (int q = 0; q < outs.length; q++) {
            int o = outs[q], stt = sturn(sp, outs, q);
            PieceLayout.Grid sg = PieceCatalog.gridFor(pack, sp.path(), stt);
            if (sg == null) return null;
            String key = PieceLayout.alignKey(jp.path(), t, sp.path(), stt, o);
            int[] dr = drafts.get(key);
            PieceLayout.Fit fit = dr != null ? PieceLayout.fitAt(c, o, jb, jg, sg, width, run, dr[0], dr[1]) : PieceLayout.fit(c, o, jb, jg, sg, width, run, Math.max(3, width));
            boolean bad = dr != null && PieceLayout.destructiveAt(c, o, jb, jg, sg, width, run, dr[0], dr[1]);
            runs.add(new Run(o, sp.path(), stt, sg, fit, bad, key, dr != null || PieceCatalog.overrides(pack).containsKey(key)));
        }
        return new Layout(jp.path(), t, jbp, jg, jb, width, run, runs);
    }

    /** @return the straight's turns on line q (the first arrives at the joint, the rest leave; +2 when flipped) */
    private int sturn(PieceSets.Piece sp, int[] outs, int q) {
        int dir = q == 0 ? (outs[0] + 2) % 4 : outs[q];
        return Math.floorMod(PieceLayout.turns(sp.sx(), sp.sz(), dir) + (flipped ? 2 : 0), 4);
    }

    /** Shift / depth of the selected run, starting from what it has now; non-destructive drafts show in the world too. */
    private void adjust(Run r, int dShift, int dDepth) {
        int[] cur = drafts.getOrDefault(r.key(), new int[]{r.fit().shift(), r.fit().depth()});
        int[] n = {Math.max(-16, Math.min(16, cur[0] + dShift)), Math.max(-1, Math.min(32, cur[1] + dDepth))};
        drafts.put(r.key(), n);
        Layout lay = layout();
        boolean bad = lay != null && lay.runs.stream().anyMatch(x -> x.key().equals(r.key()) && x.bad());
        PieceCatalog.draftAlignment(pack, r.key(), bad ? null : n);
    }

    /**
     * A different role, case or turn shows other runs: the drafts made for the old ones go (they couldn't be checked
     * or saved from here).
     */
    private void dropDrafts() {
        drafts.clear();
        PieceCatalog.clearDrafts();
    }

    /** Save: every adjusted run except those that would clear or replace blocks (they stay unsaved drafts, and the note says how many). */
    private void save(Layout lay) {
        java.util.Set<String> bad = new java.util.HashSet<>();
        for (Run r : lay.runs) if (r.bad()) bad.add(r.key());
        Map<String, int[]> ok = PieceSets.saveable(drafts, bad);
        for (var en : ok.entrySet()) PieceCatalog.setAlignment(pack, en.getKey(), en.getValue());
        int skipped = drafts.size() - ok.size();
        drafts.keySet().retainAll(bad);
        note = skipped > 0 ? Component.translatable("mccitybuilder.align.skipped", skipped).getString() : Component.translatable("mccitybuilder.align.saved").getString();
        noteUntil = System.currentTimeMillis() + 3000;
    }

    private void reset(Run r) {
        drafts.remove(r.key());
        PieceCatalog.draftAlignment(pack, r.key(), null);
        PieceCatalog.setAlignment(pack, r.key(), null);
    }

    private int picker(GuiGraphics g, int x, int y, int w, String label, int mx, int my, java.util.function.IntConsumer step) {
        VanillaUi.button(g, x, y, 16, 16, false, false, in(mx, my, x, y, 16, 16));
        VanillaUi.button(g, x + w - 16, y, 16, 16, false, false, in(mx, my, x + w - 16, y, 16, 16));
        VanillaUi.slot(g, x + 18, y, w - 36, 16);
        g.drawString(font, "<", x + 5, y + 4, VanillaUi.INK, false);
        g.drawString(font, ">", x + w - 10, y + 4, VanillaUi.INK, false);
        String l = font.plainSubstrByWidth(label, w - 42);
        g.drawString(font, l, x + (w - font.width(l)) / 2, y + 4, 0xFFFFFFFF, true);
        hits.add(new Hit(x, y, 16, 16, () -> step.accept(-1)));
        hits.add(new Hit(x + w - 16, y, 16, 16, () -> step.accept(1)));
        return y + 20;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return Planner.guard(() -> {
            if (button != 0) return false;
            for (Hit h : List.copyOf(hits))
                if (in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                    h.run().run();
                    return true;
                }
            if (view != null && in(mx, my, view[0], view[1], view[2], view[3])) {
                drag = PieceView.dragging = true;
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!drag) return false;
        PieceView.yaw += (float) dx * 1.5f;
        PieceView.pitch = Math.max(-10, Math.min(89, PieceView.pitch + (float) dy * 1.5f));
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        drag = PieceView.dragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (view == null || !in(mx, my, view[0], view[1], view[2], view[3])) return false;
        PieceView.zoom = (float) Math.max(0.3, Math.min(5, PieceView.zoom * (sy > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }
}
