package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.mojang.blaze3d.platform.InputConstants;
import com.volcanicmg.mccitybuilder.planner.JointCount;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/**
 * The joint alignment editor: one joint of a style's walls or roads (pack, family, joint role, case) laid out with
 * its meeting runs the way the planner lays them out, in a 3D view and a top-down map. Per run: the sideways shift and
 * the depth it butts on the joint, with the cross-section match and a red mark when the overlap is destructive; the
 * joint piece's turn. Save stores the per-pack override (used before the automatic search); Reset returns a run to
 * auto. Adjustments show in the planned walls at once; Close drops what wasn't saved. A case list (with the joint
 * check's verdicts) steps through the family's joints; Auto-align drops the saved settings and reruns the search,
 * Try next best steps through the search's ranked candidates, the arrow keys nudge the run (left / right sideways, up /
 * down depth), R turns the joint piece, Copy to mirrored case carries an L corner's settings to the other hand. The
 * same screen in check mode ({@link #openCheck}) lists every joint case of a family with its verdict and a Fix button.
 * By default the editor is the plain fixing mode ({@link #drawSimple}): a big 3D close-up, the selected wall
 * highlighted, Auto-fix, Left / Right / Closer / Further and Turn buttons, and whether each wall touches; the numbers,
 * case and turn pickers and the rest sit behind More.
 */
final class JointEditorScreen extends Screen {
    private static final PieceSets.Role[] ROLES = {PieceSets.Role.CORNER, PieceSets.Role.INNER, PieceSets.Role.TEE, PieceSets.Role.CROSS, PieceSets.Role.TOWER,
            PieceSets.Role.END};
    private static final String[] CASES = {"L-left", "L-right", "T", "cross", "end"};
    // Directions out of the joint per case (0 east, 1 south, 2 west, 3 north); the first line arrives at the joint.
    private static final int[][] OUTS = {{2, 3}, {2, 1}, {2, 0, 1}, {2, 0, 1, 3}, {2}};

    private String pack, fam;
    // The level the layout takes (the line panel's, for the joint count), or -1 = the line tool's.
    private int lvl = -1;
    // turn: the joint piece's orientation chosen here (PieceLayout orientation, mirrored with PieceLayout.MIRROR), -1 = auto
    private int role, kase, turn = -1, runSel;
    // The hovered button's tooltip this frame, or null.
    private String hoverTip;
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
    // Check mode (the joint check list) and the verdicts per case (null = recompute).
    private boolean checking;
    private int scroll;
    private Map<Case, Verdict> status;
    private long statusAt;
    // The expert controls (More) instead of the fixing mode; the 3D view's projection and each arm's centre on screen.
    private boolean more;
    private org.joml.Matrix4f toGui;
    private final List<float[]> armAt = new ArrayList<>();
    private double pressX, pressY;

    /** One joint case of the family: a joint role laid out in one of {@link #CASES}. */
    private record Case(int role, int kase) {
    }

    /** What the joint check found for a case: still loading, or the problems (translation key suffixes: overlap, nofit, offcentre). */
    private record Verdict(boolean loading, List<String> issues) {
        boolean ok() {
            return !loading && issues.isEmpty();
        }
    }

    /** One laid-out run: direction, straight (path, turns, grid), fit, destructive, override key. */
    private record Run(int out, String path, int turns, PieceLayout.Grid grid, PieceLayout.Fit fit, boolean bad, String key, boolean overridden) {
    }

    /**
     * Opens the joint check for a style pack's road or wall family: every joint case (corner, inner corner, T, cross,
     * tower, end cap) the family has, laid out against its main straight as the planner does, listed as OK or with its
     * problems (destructive overlap, no fit, off-centre), each with a Fix button that opens the joint editor on it.
     *
     * @param pack   the style pack; @param family the family key (as {@code PieceCatalog.familyKeys}), or null for the first
     */
    static void openCheck(String pack, String family) {
        JointEditorScreen s = new JointEditorScreen(pack, family, PieceSets.Role.CORNER, -1);
        s.checking = true;
        Minecraft.getInstance().setScreen(s);
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
        if (modal.open()) {
            modal.close();
            return;
        }
        // Back to the planner if it still runs (unsaved adjustments go in removed()), after asking about them.
        confirm(() -> minecraft.setScreen(Planner.active() ? new PlannerScreen() : null));
    }

    private final Modal modal = new Modal();

    /** Runs a change that drops the unsaved adjustments: at once without any, else after "Discard N unsaved changes?". */
    private void confirm(Runnable change) {
        if (drafts.isEmpty()) {
            change.run();
            return;
        }
        modal.ask(Component.translatable("mccitybuilder.discard", drafts.size()).getString(), List.of(
                new Modal.Choice(Component.translatable("mccitybuilder.discard.yes").getString(), () -> {
                    dropDrafts();
                    change.run();
                }),
                new Modal.Choice(Component.translatable("mccitybuilder.discard.no").getString(), () -> {
                })));
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
        Planner.guard(() -> drawMain(g, mx, my));
        if (hoverTip != null && !modal.open()) g.renderTooltip(font, font.split(Component.literal(hoverTip), 200), mx, my);
        // Its own guard: a failing draw above can't leave an invisible box that swallows every click.
        Planner.guard(() -> modal.draw(g, font, width, height, mx, my));
    }

    private void drawMain(GuiGraphics g, int mx, int my) {
        hits.clear();
        hoverTip = null;
        renderBackground(g, mx, my, 0);
        if (checking) {
            drawCheck(g, mx, my);
            return;
        }
        ensureCase();
        if (noJoints) {
            drawNoJoints(g, mx, my);
            return;
        }
        if (!more) {
            drawSimple(g, mx, my);
            return;
        }
        int pw = 230, px = 6, py = 6, ph = height - 12;
        VanillaUi.panel(g, px, py, pw, ph);
        int ix = px + 7, iw = pw - 14, iy = py + 7;
        g.drawString(font, title, ix, iy, VanillaUi.LABEL, false);
        int lw = font.width(Component.translatable("mccitybuilder.fix.less").getString()) + 8;
        VanillaUi.button(g, ix + iw - lw, iy - 3, lw, 14, false, false, in(mx, my, ix + iw - lw, iy - 3, lw, 14));
        VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.fix.less").getString(), ix + iw - lw, iy - 3, lw, 14, VanillaUi.INK);
        hits.add(new Hit(ix + iw - lw, iy - 3, lw, 14, () -> more = false));
        iy += 14;
        iy = packFamily(g, ix, iy, iw, mx, my);
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.role", Component.translatable("mccitybuilder.line.role." + ROLES[role].name().toLowerCase(java.util.Locale.ROOT))).getString(),
                mx, my, d -> confirm(() -> {
                    // Only the joint roles the set has.
                    for (int i = 0; i < ROLES.length; i++) {
                        role = Math.floorMod(role + d, ROLES.length);
                        if (hasRole(role)) break;
                    }
                    int[] ks = kases(ROLES[role]);
                    if (java.util.Arrays.stream(ks).noneMatch(k -> k == kase)) kase = ks[0];
                    dropDrafts();
                }));
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.case", CASES[kase] + (flipped ? " (flipped)" : "")).getString(), mx, my, d -> confirm(() -> {
            // The cases this role makes (corners turn left or right; a tower stands at any joint), each also flipped.
            int[] ks = kases(ROLES[role]);
            int at = 0;
            for (int i = 0; i < ks.length; i++) if (ks[i] == kase) at = i;
            int k = Math.floorMod(at * 2 + (flipped ? 1 : 0) + d, ks.length * 2);
            kase = ks[k / 2];
            flipped = k % 2 == 1;
            runSel = 0;
            dropDrafts();
        }));
        Layout lay = layout();
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.turn", turn < 0 ? "auto (" + (lay == null ? "-" : turnLabel(lay.turn)) + ")" : turnLabel(turn)).getString(), mx, my,
                d -> confirm(() -> {
                    int i = Math.floorMod((turn < 0 ? -1 : PieceLayout.ordinal(turn)) + 1 + d, 9) - 1;
                    turn = i < 0 ? -1 : PieceLayout.fromOrdinal(i);
                    dropDrafts();
                }));
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
        boolean mirror = mirrorKase() >= 0;
        String[] k2 = {"autoalign", "nextbest", "mirror"};
        int n2 = mirror ? 3 : 2, bw2 = (iw - 2 * (n2 - 1)) / n2;
        for (int k = 0; k < n2; k++) {
            int bx = ix + k * (bw2 + 2), fk = k;
            VanillaUi.button(g, bx, iy, bw2, 20, false, false, in(mx, my, bx, iy, bw2, 20));
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.align." + k2[k]).getString(), bx, iy, bw2, 20, VanillaUi.INK);
            hits.add(new Hit(bx, iy, bw2, 20, () -> {
                if (fk == 0) autoAlign(lay);
                else if (fk == 1) nextBest(lay, sel);
                else copyMirrored(lay);
            }));
        }
        iy += 24;
        String[] k3 = {"prevcase", "nextcase", "check"};
        for (int k = 0; k < 3; k++) {
            int bx = ix + k * (bw + 2), fk = k;
            VanillaUi.button(g, bx, iy, bw, 20, false, false, in(mx, my, bx, iy, bw, 20));
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.align." + k3[k]).getString(), bx, iy, bw, 20, VanillaUi.INK);
            hits.add(new Hit(bx, iy, bw, 20, () -> {
                if (fk == 2) {
                    status = null;
                    checking = true;
                } else step(fk == 0 ? -1 : 1);
            }));
        }
        iy += 24;
        if (note != null && System.currentTimeMillis() < noteUntil) g.drawString(font, font.plainSubstrByWidth(note, iw), ix, iy, 0xFFC02020, false);
        iy += 12;
        // The family's joint cases with their verdicts (as many as fit).
        List<Case> cs = cases();
        Map<Case, Verdict> vs = verdicts(cs);
        for (Case cz : cs) {
            if (iy + 11 > py + ph - 14) break;
            boolean cur = cz.role() == role && cz.kase() == kase && !flipped;
            Verdict v = vs.get(cz);
            if (cur) g.fill(ix - 2, iy - 1, ix + iw + 2, iy + 10, 0x60FFFFFF);
            g.drawString(font, v == null || v.loading() ? "…" : v.ok() ? "✓" : "⚠", ix, iy, v == null || v.loading() ? 0xFF909090 : v.ok() ? 0xFF40C040 : 0xFFE0A020, false);
            g.drawString(font, font.plainSubstrByWidth(caseLabel(cz), iw - 14), ix + 12, iy, 0xFFFFFFFF, false);
            hits.add(new Hit(ix, iy - 1, iw, 11, () -> select(cz)));
            iy += 11;
        }
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.align.keys").getString(), iw), ix, py + ph - 11, 0xFF707070, false);
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

    /** A set without joint pieces: says so in plain words, with Close. */
    private void drawNoJoints(GuiGraphics g, int mx, int my) {
        int pw = Math.min(width - 12, 280), px = (width - pw) / 2, ph = 90, py = (height - ph) / 2;
        VanillaUi.panel(g, px, py, pw, ph);
        int ix = px + 7, iw = pw - 14, iy = py + 8;
        for (var line : font.split(Component.translatable(word("mccitybuilder.fix.nojoints"), fam == null ? "-" : folderName(fam)), iw)) {
            g.drawString(font, line, ix, iy, VanillaUi.LABEL, false);
            iy += 10;
        }
        bigButton(g, ix, py + ph - 27, iw, Component.translatable("mccitybuilder.jointcheck.close").getString(), mx, my, this::onClose);
    }

    /** @return each wall's state ({@link com.volcanicmg.mccitybuilder.planner.JointHelp}) as laid out now */
    private int[] armStates(Layout lay) {
        int[] st = new int[lay.runs.size()];
        for (int i = 0; i < st.length; i++) {
            Run r = lay.runs.get(i);
            PieceLayout.Fit f = r.fit();
            boolean bad = r.bad() || PieceLayout.destructiveAt(new int[]{0, 0}, r.out(), lay.jb, lay.joint, r.grid(), lay.width, lay.run, f.shift(), f.depth());
            st[i] = com.volcanicmg.mccitybuilder.planner.JointHelp.armState(bad, f.depth(), f.match());
        }
        return st;
    }

    private static final int[] STATE_COLOUR = {0xFF40C040, 0xFFE0A020, 0xFFFF4040};

    /**
     * The plain fixing mode: what joint this is and whether its walls touch, Auto-fix, the wall to adjust (also by
     * clicking it in 3D or on the map), Left / Right / Closer / Further, Turn joint piece, Save / Cancel, More; a big 3D
     * close-up with the selected wall outlined and a small top-down map in its corner.
     */
    private void drawSimple(GuiGraphics g, int mx, int my) {
        int pw = Math.max(150, Math.min(200, width / 3)), px = 6, py = 6, ph = height - 12;
        VanillaUi.panel(g, px, py, pw, ph);
        int ix = px + 7, iw = pw - 14, iy = py + 7;
        g.drawString(font, font.plainSubstrByWidth(caseLabel(new Case(role, kase)), iw), ix, iy, VanillaUi.LABEL, false);
        iy += 11;
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.fix.family", fam == null ? "-" : folderName(fam)).getString(), iw), ix, iy, 0xFF606060, false);
        iy += 14;
        // Bottom: Save / Cancel, and More above them.
        int by = py + ph - 7 - 20, half = (iw - 2) / 2;
        Layout lay = layout();
        if (lay != null) bigButton(g, ix, by, half, Component.translatable("mccitybuilder.align.save").getString(), mx, my, () -> save(lay));
        bigButton(g, ix + half + 2, by, half, Component.translatable("mccitybuilder.fix.cancel").getString(), mx, my, this::onClose);
        bigButton(g, ix, by - 22, iw, Component.translatable("mccitybuilder.fix.more").getString(), mx, my, () -> more = true);
        if (lay == null) {
            for (var line : font.split(Component.translatable("mccitybuilder.align.missing"), iw)) {
                g.drawString(font, line, ix, iy, 0xFFA02020, false);
                iy += 10;
            }
            return;
        }
        runSel = Math.min(runSel, lay.runs.size() - 1);
        int[] st = armStates(lay);
        int js = com.volcanicmg.mccitybuilder.planner.JointHelp.jointState(st);
        // The whole joint's state, large.
        g.pose().pushPose();
        g.pose().translate(ix, iy, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.fix.state." + js).getString(), (int) (iw / 1.5f)), 0, 0, STATE_COLOUR[js], true);
        g.pose().popPose();
        iy += 18;
        bigButton(g, ix, iy, iw, Component.translatable("mccitybuilder.fix.auto").getString(), mx, my, () -> autoFix(lay));
        iy += 24;
        // The wall to adjust: one button per wall, coloured by its state.
        g.drawString(font, Component.translatable(word("mccitybuilder.fix.wall")), ix, iy, VanillaUi.LABEL, false);
        iy += 10;
        int n = lay.runs.size(), tw = (iw - 2 * (n - 1)) / n;
        for (int r = 0; r < n; r++) {
            int bx = ix + r * (tw + 2), fr = r;
            VanillaUi.button(g, bx, iy, tw, 16, r == runSel, false, in(mx, my, bx, iy, tw, 16));
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.fix.dir." + lay.runs.get(r).out()).getString(), bx, iy, tw, 16,
                    r == runSel ? 0xFFFFFFFF : STATE_COLOUR[st[r]]);
            hits.add(new Hit(bx, iy, tw, 16, () -> runSel = fr));
        }
        iy += 20;
        Run sel = lay.runs.get(runSel);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable(word("mccitybuilder.fix.arm"), Component.translatable("mccitybuilder.fix.dir." + sel.out()),
                Component.translatable("mccitybuilder.fix.arm." + st[runSel])).getString(), iw), ix, iy, STATE_COLOUR[st[runSel]], false);
        iy += 14;
        int right = com.volcanicmg.mccitybuilder.planner.JointHelp.rightShift(sel.out());
        bigButton(g, ix, iy, half, Component.translatable("mccitybuilder.fix.left").getString(), mx, my, () -> adjust(sel, -right, 0));
        bigButton(g, ix + half + 2, iy, half, Component.translatable("mccitybuilder.fix.right").getString(), mx, my, () -> adjust(sel, right, 0));
        iy += 22;
        bigButton(g, ix, iy, half, Component.translatable("mccitybuilder.fix.closer").getString(), mx, my, () -> adjust(sel, 0, 1));
        bigButton(g, ix + half + 2, iy, half, Component.translatable("mccitybuilder.fix.further").getString(), mx, my, () -> adjust(sel, 0, -1));
        iy += 22;
        bigButton(g, ix, iy, half, Component.translatable("mccitybuilder.fix.turn").getString(), mx, my, () -> confirm(() -> turnJoint(lay)));
        if (in(mx, my, ix, iy, half, 20)) hoverTip = Component.translatable("mccitybuilder.fix.turn.tip").getString();
        bigButton(g, ix + half + 2, iy, half, Component.translatable("mccitybuilder.fix.mirror").getString(), mx, my, () -> confirm(() -> mirrorJoint(lay)));
        if (in(mx, my, ix + half + 2, iy, half, 20)) hoverTip = Component.translatable("mccitybuilder.fix.mirror.tip").getString();
        iy += 24;
        if (note != null && System.currentTimeMillis() < noteUntil)
            for (var line : font.split(Component.literal(note), iw)) {
                if (iy + 10 > by - 24) break;
                g.drawString(font, line, ix, iy, 0xFF804000, false);
                iy += 10;
            }
        // The close-up, with the selected wall outlined; the map in its corner.
        int vx = px + pw + 6, vw = width - vx - 6, vh = ph;
        view = new int[]{vx, py, vw, vh};
        VanillaUi.slot(g, vx, py, vw, vh);
        scene(g, lay, vx + 1, py + 1, vw - 2, vh - 2);
        int ms = Math.max(60, Math.min(130, Math.min(vw, vh) / 3));
        map(g, lay, vx + 4, py + vh - ms - 4, ms, ms);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable(word("mccitybuilder.fix.keys")).getString(), vw - ms - 16), vx + ms + 10, py + vh - 12, 0xFFB0B0B0, true);
    }

    /** The joint and its walls in 3D, the selected wall outlined; remembers where each wall is on screen (click to select). */
    private void scene(GuiGraphics g, Layout lay, int x, int y, int w, int h) {
        List<PieceView.Part> parts = new ArrayList<>();
        parts.add(new PieceView.Part("align|" + pack + "|" + lay.jointPath + "|" + lay.turn, lay.jointBp, lay.jb[0], -lay.joint.ground(), lay.jb[1]));
        List<float[]> centres = new ArrayList<>();
        float[] sel = null;
        for (int i = 0; i < lay.runs.size(); i++) {
            Run r = lay.runs.get(i);
            Blueprint bp = PieceCatalog.rotated(pack, r.path(), r.turns());
            int[] b0 = runBoxes(lay, r).get(0);
            float[] box = {b0[0], -r.grid().ground(), b0[1], b0[2] + 1, -r.grid().ground() + r.grid().sy(), b0[3] + 1};
            centres.add(box);
            if (i == runSel) sel = box;
            if (bp == null) continue;
            // One straight per wall: a close-up of the joint.
            parts.add(new PieceView.Part("align|" + pack + "|" + r.path() + "|" + Math.floorMod(r.turns(), 4), bp, b0[0], -r.grid().ground(), b0[1]));
        }
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (PieceView.Part p : parts) {
            bounds[0] = Math.min(bounds[0], p.x());
            bounds[1] = Math.min(bounds[1], p.y());
            bounds[2] = Math.min(bounds[2], p.z());
            bounds[3] = Math.max(bounds[3], p.x() + p.bp().getSizeX());
            bounds[4] = Math.max(bounds[4], p.y() + p.bp().getSizeY());
            bounds[5] = Math.max(bounds[5], p.z() + p.bp().getSizeZ());
        }
        logScene(lay, parts);
        float[] hl = sel;
        // The joint piece outlined like on the map (yellow): a tower as tall as its walls and built of the same stone
        // otherwise reads as the walls running into each other.
        net.minecraft.world.phys.AABB jbox = new net.minecraft.world.phys.AABB(lay.jb[0], -lay.joint.ground(), lay.jb[1], lay.jb[2] + 1,
                -lay.joint.ground() + lay.joint.sy(), lay.jb[3] + 1).inflate(0.05);
        toGui = PieceView.renderScene(g, parts, bounds, (pose, buffers) -> {
            var lines = buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines());
            net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, jbox, 1f, 0.88f, 0.25f, 1f);
            if (hl != null) net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines,
                    new net.minecraft.world.phys.AABB(hl[0], hl[1], hl[2], hl[3], hl[4], hl[5]).inflate(0.05), 0.25f, 0.9f, 1f, 1f);
        }, null, null, x, y, w, h);
        armAt.clear();
        for (float[] c : centres) {
            org.joml.Vector3f v = toGui.transformPosition(new org.joml.Vector3f((c[0] + c[3]) / 2, (c[1] + c[4]) / 2, (c[2] + c[5]) / 2));
            armAt.add(new float[]{v.x(), v.y()});
        }
    }

    private String sceneLogged;

    /** With {@link PieceView#DEBUG}: logs the 3D view's pieces (key, place, size) once per layout. */
    private void logScene(Layout lay, List<PieceView.Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (PieceView.Part p : parts) sb.append(" [").append(p.key()).append(" at ").append(p.x()).append(',').append(p.y()).append(',').append(p.z())
                .append(" size ").append(p.bp().getSizeX()).append('x').append(p.bp().getSizeY()).append('x').append(p.bp().getSizeZ()).append(']');
        String k = sb + " jb " + java.util.Arrays.toString(lay.jb) + " ground " + lay.joint.ground();
        if (!PieceView.DEBUG || k.equals(sceneLogged)) return;
        sceneLogged = k;
        com.volcanicmg.mccitybuilder.CityBuilder.LOGGER.info("City Builder: joint view{}", k);
    }

    /** Turn joint piece (R): a quarter turn on from what shows (a mirror stays). */
    private void turnJoint(Layout lay) {
        turn = PieceLayout.orient(turn < 0 ? lay.turn : turn, 1);
        dropDrafts();
    }

    /** Mirror joint piece (M): the piece as shown, mirrored (or back). */
    private void mirrorJoint(Layout lay) {
        turn = (turn < 0 ? lay.turn : turn) ^ PieceLayout.MIRROR;
        dropDrafts();
    }

    /** @return a joint orientation for the turn picker: "2", or "2, mirrored" */
    private static String turnLabel(int o) {
        return PieceLayout.mirrorOf(o) ? Component.translatable("mccitybuilder.align.turn.mirrored", o & 3).getString() : String.valueOf(o & 3);
    }

    private void bigButton(GuiGraphics g, int x, int y, int w, String label, int mx, int my, Runnable run) {
        boolean over = in(mx, my, x, y, w, 20);
        VanillaUi.button(g, x, y, w, 20, false, false, over);
        VanillaUi.buttonLabel(g, font, label, x, y, w, 20, VanillaUi.INK);
        hits.add(new Hit(x, y, w, 20, () -> {
            PlannerScreen.click();
            run.run();
        }));
    }

    /**
     * Auto-fix: every joint turn, per wall the search's ranked fits; the turn where every wall touches best is drafted
     * (Save keeps it). When no turn makes them all touch, the pieces weren't made to join: it says so and goes back to
     * the safe automatic layout (walls stop beside the joint piece, nothing is broken).
     */
    private void autoFix(Layout lay) {
        dropDrafts();
        List<List<List<PieceLayout.Fit>>> perTurn = new ArrayList<>();
        List<Layout> lays = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Layout l = layout(role, kase, flipped, t);
            lays.add(l);
            if (l == null) {
                perTurn.add(null);
                continue;
            }
            List<List<PieceLayout.Fit>> arms = new ArrayList<>();
            for (Run r : l.runs) arms.add(PieceLayout.ranked(new int[]{0, 0}, r.out(), l.jb, l.joint, r.grid(), l.width, l.run, Math.max(3, l.width), 40));
            perTurn.add(arms);
        }
        int[] fix = com.volcanicmg.mccitybuilder.planner.JointHelp.autoFix(perTurn);
        if (fix == null) {
            // Nothing saved is deleted here (Auto-align / Reset under More do that): the automatic layout is shown.
            turn = -1;
            say(Component.translatable(word("mccitybuilder.fix.nojoin")).getString());
            return;
        }
        turn = fix[0];
        Layout l = lays.get(fix[0]);
        for (int a = 0; a < l.runs.size(); a++) {
            PieceLayout.Fit f = perTurn.get(fix[0]).get(a).get(fix[a + 1]);
            setDraft(l.runs.get(a), new int[]{f.shift(), f.depth()});
        }
        status = null;
        say(Component.translatable(word("mccitybuilder.fix.fixed")).getString());
    }

    /** The pack and family pickers (the family resolves to one the pack has). */
    private int packFamily(GuiGraphics g, int ix, int iy, int iw, int mx, int my) {
        List<String> packs = Blueprints.packs();
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.pack", pack).getString(), mx, my, d -> confirm(() -> {
            pack = packs.get(Math.floorMod(packs.indexOf(pack) + d, packs.size()));
            fam = null;
            status = null;
            drafts.clear();
            PieceCatalog.clearDrafts();
        }));
        List<String> fams = new ArrayList<>();
        boolean keysLoading = false;
        for (String t : new String[]{"wall", "road"}) {
            List<String> k = PieceCatalog.familyKeys(pack, t);
            if (k != null) fams.addAll(k);
            else keysLoading = true;
        }
        // While the pack's families load, the family asked for stays (it may not be listed yet).
        if (!keysLoading && (fam == null || !fams.contains(fam))) {
            // A renamed family (an old line's key): the one it became, else the first.
            String to = fam == null ? null : PieceSets.resolveFamily(fam, fams);
            fam = to != null ? to : fams.isEmpty() ? null : fams.get(0);
        }
        iy = picker(g, ix, iy, iw, Component.translatable("mccitybuilder.align.family", fam == null ? "-" : folderName(fam)).getString(), mx, my, d -> confirm(() -> {
            if (!fams.isEmpty()) fam = fams.get(Math.floorMod(fams.indexOf(fam) + d, fams.size()));
            status = null;
            drafts.clear();
            PieceCatalog.clearDrafts();
        }));
        return iy;
    }

    /** The top-down map: columns by their top block's map colour, the joint's box (yellow), runs (green, cyan selected, red destructive). */
    private void map(GuiGraphics g, Layout lay, int x, int y, int w, int h) {
        VanillaUi.slot(g, x, y, w, h);
        // Long runs reach past the map: clipped to it.
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        int r = 16, cell = Math.max(2, Math.min((w - 4) / (2 * r + 1), (h - 4) / (2 * r + 1))), ox = x + w / 2, oz = y + h / 2;
        column(g, lay.joint, lay.jb[0], lay.jb[1], ox, oz, cell, r);
        for (Run rn : lay.runs) for (int[] b : runBoxes(lay, rn)) column(g, rn.grid(), b[0], b[1], ox, oz, cell, r);
        box(g, lay.jb, ox, oz, cell, 0xFFFFE040);
        for (int i = 0; i < lay.runs.size(); i++) {
            Run rn = lay.runs.get(i);
            int fi = i;
            int[] b = runBoxes(lay, rn).get(0);
            box(g, b, ox, oz, cell, rn.bad() ? 0xFFFF4040 : i == runSel ? 0xFF40E0FF : 0xFF40E040);
            hits.add(new Hit(Math.max(x, ox + b[0] * cell), Math.max(y, oz + b[1] * cell), Math.min(x + w, ox + (b[2] + 1) * cell) - Math.max(x, ox + b[0] * cell),
                    Math.min(y + h, oz + (b[3] + 1) * cell) - Math.max(y, oz + b[1] * cell), () -> runSel = fi));
        }
        g.disableScissor();
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

    private record Layout(String jointPath, int turn, Blueprint jointBp, PieceLayout.Grid joint, int[] jb, int width, int run, List<Run> runs, String jkey, int jrot, boolean tower) {
    }

    private List<int[]> runBoxes(Layout lay, Run r) {
        return PieceLayout.boxes(new int[]{0, 0}, r.out(), r.fit().off(), List.of(lay.run, lay.run), lay.width, r.fit().shift());
    }

    /** @return the selected case laid out ({@link #layout(int, int, boolean, int)}) */
    private Layout layout() {
        return layout(role, kase, flipped, turn);
    }

    /** @return a case's joint laid out as the planner would (overrides and drafts applied), or null while pieces load */
    private Layout layout(int role, int kase, boolean flipped, int turn) {
        PieceSets.Family f = PieceCatalog.familyOf(pack, fam);
        if (f == null) return null;
        List<PieceSets.Group> st = PieceCatalog.groups(pack, f, PieceSets.Role.STRAIGHT), js = PieceCatalog.groups(pack, f, ROLES[role]);
        if (st.isEmpty() || js.isEmpty()) return null;
        int level = Math.max(1, lvl >= 0 ? lvl : LineView.level);
        PieceSets.Piece sp = st.get(0).pick(st.get(0).levels() ? level : 0, 0), jp = js.get(0).pickAs(sp, level, 0);
        int[] outs = OUTS[kase], c = {0, 0};
        int width = sp.across(), run = sp.run();
        // The joint's turn: chosen, or the planner's pick (best face score over the runs).
        int t = turn;
        int[] sts = new int[outs.length];
        String[] sps = new String[outs.length];
        for (int q = 0; q < outs.length; q++) {
            sts[q] = sturn(sp, outs, q, flipped);
            sps[q] = sp.path();
        }
        // The configuration's built-in setting holds for its own turn only.
        boolean[] sym = new boolean[outs.length];
        java.util.Arrays.fill(sym, PieceCatalog.symmetric(pack, sp.path()));
        PieceLayout.JointFix fix = PieceLayout.jointFix(PieceCatalog.jointLookup(pack), PieceCatalog.playerAligned(pack), jp.path(), outs, sps, sts, sym);
        if (t < 0 && fix != null) t = fix.turn();
        if (fix != null && fix.turn() != t) fix = null;
        if (t < 0) {
            double bs = -1;
            for (int k = 0; k < 4; k++) {
                PieceLayout.Grid jg = PieceCatalog.gridFor(pack, jp.path(), k);
                if (jg == null) return null;
                double sc = 0;
                for (int q = 0; q < outs.length; q++) {
                    PieceLayout.Grid sg = PieceCatalog.gridFor(pack, sp.path(), sturn(sp, outs, q, flipped));
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
            int o = outs[q], stt = sturn(sp, outs, q, flipped);
            PieceLayout.Grid sg = PieceCatalog.gridFor(pack, sp.path(), stt);
            if (sg == null) return null;
            String key = PieceLayout.alignKey(jp.path(), t, sp.path(), stt, o);
            int[] dr = drafts.get(key);
            PieceLayout.Fit fit = dr != null ? PieceLayout.fitAt(c, o, jb, jg, sg, width, run, dr[0], dr[1]) : PieceLayout.fitWith(c, o, jb, jg, sg, width, run, Math.max(3, width),
                    // As the planner places it: a legacy world shift converted on this joint.
                    PieceLayout.relAlign(PieceLayout.alignAt(jg, sg, o), c, o, jb, width), fix == null ? null : fix.runs()[q]);
            boolean bad = dr != null && PieceLayout.destructiveAt(c, o, jb, jg, sg, width, run, dr[0], dr[1]);
            runs.add(new Run(o, sp.path(), stt, sg, fit, bad, key, dr != null || PieceCatalog.overrides(pack).containsKey(key)));
        }
        PieceLayout.JointKey jk = PieceLayout.jointKey(jp.path(), outs, sps, sts, sym);
        return new Layout(jp.path(), t, jbp, jg, jb, width, run, runs, jk.key(), jk.rot(), ROLES[role] == PieceSets.Role.TOWER);
    }

    /** @return the straight's turns on line q (the first arrives at the joint, the rest leave; +2 when flipped) */
    private static int sturn(PieceSets.Piece sp, int[] outs, int q, boolean flipped) {
        int dir = q == 0 ? (outs[0] + 2) % 4 : outs[q];
        return PieceLayout.straightTurns(sp.nativeDir(), dir, flipped);
    }

    /** Shift / depth of the selected run, starting from what it has now; non-destructive drafts show in the world too. */
    private void adjust(Run r, int dShift, int dDepth) {
        int[] cur = drafts.getOrDefault(r.key(), new int[]{r.fit().shift(), r.fit().depth()});
        setDraft(r, new int[]{Math.max(-16, Math.min(16, cur[0] + dShift)), Math.max(-1, Math.min(32, cur[1] + dDepth))});
    }

    /** Draft {world shift, depth} for a run; a non-destructive one shows in the world too. */
    private void setDraft(Run r, int[] n) {
        drafts.put(r.key(), n);
        status = null;
        Layout lay = layout();
        boolean bad = lay != null && lay.runs.stream().anyMatch(x -> x.key().equals(r.key()) && x.bad());
        PieceCatalog.draftAlignment(pack, r.key(), bad || lay == null ? null : stored(lay, r, n));
    }

    /** @return a run's {world shift, depth} as stored (the shift relative to the joint box, {@link PieceLayout#alignShift}) */
    private static int[] stored(Layout lay, Run r, int[] n) {
        return new int[]{PieceLayout.alignShift(new int[]{0, 0}, r.out(), lay.jb, lay.width, n[0]), n[1]};
    }

    /**
     * A different role, case or turn shows other runs: the drafts made for the old ones go (they couldn't be checked
     * or saved from here).
     */
    private void dropDrafts() {
        status = null;
        drafts.clear();
        PieceCatalog.clearDrafts();
    }

    /** Save: every adjusted run except those that would clear or replace blocks (they stay unsaved drafts, and the note says how many). */
    private void save(Layout lay) {
        java.util.Set<String> bad = new java.util.HashSet<>();
        for (Run r : lay.runs) if (r.bad()) bad.add(r.key());
        Map<String, int[]> ok = PieceSets.saveable(drafts, bad);
        for (Run r : lay.runs) if (ok.containsKey(r.key())) PieceCatalog.setAlignment(pack, r.key(), stored(lay, r, ok.get(r.key())));
        // A turn chosen here (Auto-fix, Turn joint piece) is the joint's setting: the planner keeps it, and the other
        // turns' alignments go. Auto leaves a saved turn alone (Auto-align / Reset remove it).
        if (turn >= 0) {
            List<String> others = new ArrayList<>();
            for (Run r : lay.runs)
                for (int t : PieceLayout.ORIENTS) if (t != turn) others.add(PieceLayout.alignKey(lay.jointPath(), t, r.path(), r.turns(), r.out()));
            PieceCatalog.clearAlignments(pack, com.volcanicmg.mccitybuilder.planner.JointHelp.clearable(others, usedByOtherCases()));
            PieceCatalog.setOverride(pack, lay.jkey(), PieceLayout.jointValue(turn, lay.jrot()));
        }
        int skipped = drafts.size() - ok.size();
        drafts.keySet().retainAll(bad);
        status = null;
        note = skipped > 0 ? Component.translatable("mccitybuilder.align.skipped", skipped).getString() : Component.translatable("mccitybuilder.align.saved").getString();
        noteUntil = System.currentTimeMillis() + 3000;
    }

    private void reset(Run r) {
        status = null;
        drafts.remove(r.key());
        PieceCatalog.draftAlignment(pack, r.key(), null);
        PieceCatalog.setAlignment(pack, r.key(), null);
    }

    // ---- auto-align, next best, mirrored copy ----

    /** Drops the saved alignments of this case's runs (and its joint setting) and reruns the automatic search. */
    private void autoAlign(Layout lay) {
        // Every joint turn's keys (and drafts): a setting saved while the joint stood another way would come back.
        List<String> runKeys = new ArrayList<>();
        for (Run r : lay.runs) for (int t : PieceLayout.ORIENTS) runKeys.add(PieceLayout.alignKey(lay.jointPath(), t, r.path(), r.turns(), r.out()));
        List<String> keys = new ArrayList<>(List.of(lay.jkey()));
        keys.addAll(com.volcanicmg.mccitybuilder.planner.JointHelp.clearable(runKeys, usedByOtherCases()));
        drafts.keySet().removeAll(keys);
        PieceCatalog.clearAlignments(pack, keys);
        status = null;
        turn = -1;
        boolean builtIn = PieceCatalog.jointLookup(pack).apply(lay.jkey()) != null;
        say(Component.translatable(builtIn ? "mccitybuilder.align.autodone.builtin" : "mccitybuilder.align.autodone").getString());
    }

    /** @return the alignment keys the joint piece's other cases (the role's other cases, flipped or not) use at their turns now */
    private java.util.Set<String> usedByOtherCases() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int k : kases(ROLES[role]))
            for (boolean fl : new boolean[]{false, true}) {
                if (k == kase && fl == flipped) continue;
                Layout l = layout(role, k, fl, -1);
                if (l != null) for (Run r : l.runs) out.add(r.key());
            }
        return out;
    }

    /** Steps the selected run to the next candidate of the search's ranking (the automatic pick, then the next best, ...), wrapping. */
    private void nextBest(Layout lay, Run r) {
        List<PieceLayout.Fit> rk = PieceLayout.ranked(new int[]{0, 0}, r.out(), lay.jb, lay.joint, r.grid(), lay.width, lay.run, Math.max(3, lay.width), 40);
        int at = -1;
        for (int i = 0; i < rk.size(); i++) if (rk.get(i).shift() == r.fit().shift() && rk.get(i).depth() == r.fit().depth()) at = i;
        if (rk.isEmpty()) {
            say(Component.translatable("mccitybuilder.align.noother").getString());
            return;
        }
        int n = (at + 1) % rk.size();
        PieceLayout.Fit f = rk.get(n);
        setDraft(r, new int[]{f.shift(), f.depth()});
        say(Component.translatable("mccitybuilder.align.candidate", n + 1, rk.size()).getString());
    }

    /** @return the L corner case on the other hand (the same role), or -1 when this case has no mirror */
    private int mirrorKase() {
        return (ROLES[role] == PieceSets.Role.CORNER || ROLES[role] == PieceSets.Role.INNER) && kase < 2 && !flipped ? 1 - kase : -1;
    }

    /**
     * Drafts this L case's alignments on the mirrored case (and shows it; Save keeps them): per run, the gaps to the joint box swap sides (a reflection
     * swaps left and right) and the contact depth carries over; runs whose copy would break blocks are skipped.
     */
    private void copyMirrored(Layout lay) {
        int mk = mirrorKase();
        Layout m = mk < 0 ? null : layout(role, mk, false, -1);
        if (m == null || m.runs.size() != lay.runs.size()) return;
        int[] c = {0, 0};
        int done = 0;
        Map<String, int[]> copies = new HashMap<>(), asStored = new HashMap<>();
        for (int q = 0; q < lay.runs.size(); q++) {
            Run a = lay.runs.get(q), b = m.runs.get(q);
            int[] ba = runBoxes(lay, a).get(0);
            int lat = a.out() % 2 == 0 ? 1 : 0, gLo = ba[lat] - lay.jb[lat], gHi = lay.jb[lat + 2] - ba[lat + 2];
            int depth = a.fit().depth();
            PieceLayout.Fit f0 = PieceLayout.fitAt(c, b.out(), m.jb, m.joint, b.grid(), m.width, m.run, 0, depth);
            int[] b0 = PieceLayout.boxes(c, b.out(), f0.off(), List.of(m.run), m.width, 0).get(0);
            int shift = m.jb[lat] + (a.out() % 2 == 0 ? gHi : gLo) - b0[lat];
            if (Math.abs(shift) > 16 || PieceLayout.destructiveAt(c, b.out(), m.jb, m.joint, b.grid(), m.width, m.run, shift, depth)) continue;
            copies.put(b.key(), new int[]{shift, depth});
            asStored.put(b.key(), stored(m, b, new int[]{shift, depth}));
            done++;
        }
        // Drafts like any adjustment, shown on the mirrored case: Save keeps them, Close drops them (the source
        // case's unsaved drafts stay too).
        kase = mk;
        flipped = false;
        turn = -1;
        runSel = 0;
        drafts.putAll(copies);
        asStored.forEach((k, v) -> PieceCatalog.draftAlignment(pack, k, v));
        status = null;
        say(Component.translatable("mccitybuilder.align.mirrored", done, lay.runs.size(), caseLabel(new Case(role, mk))).getString());
    }

    private void say(String text) {
        note = text;
        noteUntil = System.currentTimeMillis() + 3500;
    }

    // ---- cases and the joint check ----

    /** @return the joint cases this family has (corner and inner corner in both hands, T, cross, tower, end cap), by role order */
    private List<Case> cases() {
        PieceSets.Family f = PieceCatalog.familyOf(pack, fam);
        List<Case> out = new ArrayList<>();
        if (f == null || PieceCatalog.groups(pack, f, PieceSets.Role.STRAIGHT).isEmpty()) return out;
        for (int r = 0; r < ROLES.length; r++) {
            if (PieceCatalog.groups(pack, f, ROLES[r]).isEmpty()) continue;
            switch (ROLES[r]) {
                case CORNER, INNER -> {
                    out.add(new Case(r, 0));
                    out.add(new Case(r, 1));
                }
                case TEE -> out.add(new Case(r, 2));
                case END -> out.add(new Case(r, 4));
                default -> out.add(new Case(r, 3));
            }
        }
        return out;
    }

    /** @return the joint in plain words ("Corner (turning left)", "Tower where walls meet") */
    private String caseLabel(Case c) {
        PieceSets.Role r = ROLES[c.role()];
        String k = r.name().toLowerCase(java.util.Locale.ROOT) + (r == PieceSets.Role.CORNER || r == PieceSets.Role.INNER ? "." + c.kase() : "");
        return Component.translatable("mccitybuilder.fix.joint." + k).getString();
    }

    /** @return a family as the player knows it: its last folder ("avenue"), not "road:infrastructure/avenue" */
    private static String folderName(String family) {
        String f = family.substring(family.indexOf(':') + 1);
        return f.substring(f.lastIndexOf('/') + 1);
    }

    /** @return the lang key for a road set's wording ("road" for "wall") when the family is a road one */
    private String word(String key) {
        return fam != null && fam.startsWith("road:") ? key + ".road" : key;
    }

    /** @return the {@link #CASES} a joint role is laid out in */
    private static int[] kases(PieceSets.Role r) {
        return switch (r) {
            case CORNER, INNER -> new int[]{0, 1};
            case TEE -> new int[]{2};
            case CROSS -> new int[]{3};
            case END -> new int[]{4};
            default -> new int[]{0, 1, 2, 3};
        };
    }

    /** @return true when the set has a piece for the joint role */
    private boolean hasRole(int r) {
        PieceSets.Family f = PieceCatalog.familyOf(pack, fam);
        return f != null && !PieceCatalog.groups(pack, f, ROLES[r]).isEmpty();
    }

    // Set when the loaded set has no joint pieces at all (walls just meet end to end).
    private boolean noJoints;

    /** Once the set has loaded: a role it has no piece for moves to its first joint case (no dead end). */
    private void ensureCase() {
        if (PieceCatalog.families(pack) == null) return;
        List<Case> cs = cases();
        noJoints = cs.isEmpty();
        if (noJoints || hasRole(role) && java.util.Arrays.stream(kases(ROLES[role])).anyMatch(k -> k == kase)) return;
        select(cs.get(0));
    }

    /** @return the verdict per case, recomputed after a change (and every second while pieces still load) */
    private Map<Case, Verdict> verdicts(List<Case> cs) {
        boolean loading = status != null && status.values().stream().anyMatch(Verdict::loading);
        // Another set of cases (the family's pieces finished loading, a role pick changed): all of them again.
        if (status == null || !status.keySet().equals(new java.util.HashSet<>(cs)) || loading && System.currentTimeMillis() - statusAt > 1000) {
            Map<Case, Verdict> m = new java.util.LinkedHashMap<>();
            for (Case c : cs) {
                Layout l = layout(c.role(), c.kase(), false, -1);
                m.put(c, l == null ? new Verdict(true, List.of()) : new Verdict(false, issues(l)));
            }
            status = m;
            statusAt = System.currentTimeMillis();
        }
        return status;
    }

    /**
     * The joint check as a count, for the Pieces panel: headless, one case per {@link #step}, using this editor's own
     * layout and verdict code (so the count is the check list's, see {@link JointCount}).
     */
    static final class Probe {
        private final JointEditorScreen s;
        private List<Case> cs;
        private int[] states;
        private int next;
        /** Time spent in {@link #step} (total and the slowest call, ns) and the calls that did work. */
        long nanos, maxNanos;
        int steps;

        Probe(String pack, String fam, int level) {
            s = new JointEditorScreen(pack, fam, PieceSets.Role.CORNER, -1);
            s.lvl = level;
        }

        /** @return whether every case has been tried */
        boolean finished() {
            return cs != null && next >= cs.size();
        }

        /** Tries one more case (none once finished). @return the tally so far, or null while the family's pieces are still loading */
        JointCount.Result step() {
            if (cs == null) {
                if (PieceCatalog.families(s.pack) == null || PieceCatalog.familyOf(s.pack, s.fam) == null) return null;
                cs = s.cases();
                states = new int[cs.size()];
                java.util.Arrays.fill(states, JointCount.LOADING);
            }
            if (next < cs.size()) {
                long t0 = System.nanoTime();
                int i = next++;
                Layout l = s.layout(cs.get(i).role(), cs.get(i).kase(), false, -1);
                states[i] = l == null ? (s.failedPiece(cs.get(i)) ? JointCount.SETTLED : JointCount.LOADING) : JointCount.stateOf(s.issues(l));
                long dt = System.nanoTime() - t0;
                nanos += dt;
                maxNanos = Math.max(maxNanos, dt);
                steps++;
            }
            List<String> names = new ArrayList<>();
            for (Case c : cs) names.add(s.caseLabel(c));
            return JointCount.tally(names, states);
        }

        /** Opens the joint editor on the first case with a problem. */
        void openFirstBad() {
            // The first real problem, else the first minor one.
            for (int want : new int[]{JointCount.BAD, JointCount.MINOR})
                for (int i = 0; cs != null && i < cs.size(); i++)
                    if (states[i] == want) {
                        JointEditorScreen e = new JointEditorScreen(s.pack, s.fam, ROLES[cs.get(i).role()], -1);
                        e.lvl = s.lvl;
                        e.select(cs.get(i));
                        Minecraft.getInstance().setScreen(e);
                        return;
                    }
        }
    }

    /** @return the count as the check list itself works it out (its {@code verdicts}, all cases at once); the reference the {@link Probe} must match */
    static JointCount.Result reference(String pack, String fam, int lvl) {
        JointEditorScreen s = new JointEditorScreen(pack, fam, PieceSets.Role.CORNER, -1);
        s.lvl = lvl;
        List<Case> cs = s.cases();
        Map<Case, Verdict> vs = s.verdicts(cs);
        int[] st = new int[cs.size()];
        List<String> names = new ArrayList<>();
        for (int i = 0; i < st.length; i++) {
            Verdict v = vs.get(cs.get(i));
            st[i] = v == null || v.loading() ? (s.failedPiece(cs.get(i)) ? JointCount.SETTLED : JointCount.LOADING) : v.ok() ? JointCount.OK : JointCount.stateOf(v.issues());
            names.add(s.caseLabel(cs.get(i)));
        }
        return JointCount.tally(names, st);
    }

    /**
     * Dev tool (DevBridge {@code /tune}): fixes every failing case of a family at a level and Saves it, keeping a result only when the case then
     * lays out clean (no overlap, no fit gap, centred). First the editor's Auto-fix; when that leaves a problem, the best clean alternative over all
     * eight joint orientations and every safe run alignment ({@link #deepFix}). A fix that still has a problem, or a run matching the joint worse than
     * {@code minMatch}, is dropped: nothing broken or sloppy is saved.
     *
     * @param minMatch the least cross-section match (0 to 1) every run of a fix must have
     * @return one line per failing case: "label: fixed (how, turn T, min match M)" / "label: kept auto (reason)", or "loading" while pieces load
     */
    static List<String> tune(String pack, String fam, int lvl, double minMatch) {
        JointEditorScreen s = new JointEditorScreen(pack, fam, PieceSets.Role.CORNER, -1);
        s.lvl = lvl;
        List<Case> cs = s.cases();
        Map<Case, Verdict> vs = s.verdicts(cs);
        List<String> out = new ArrayList<>();
        for (Case c : cs) {
            Verdict v = vs.get(c);
            if (v == null || v.loading()) {
                out.add(s.caseLabel(c) + ": loading");
                continue;
            }
            if (v.ok()) continue;
            s.select(c);
            s.autoFix(s.layout());
            Layout after = s.layout();
            String how = "auto-fix";
            if (s.turn < 0 || after == null || !s.issues(after).isEmpty() || minMatch(after) < minMatch) {
                s.dropDrafts();
                s.turn = -1;
                how = s.deepFix(minMatch) ? "deep search" : null;
                after = s.layout();
            }
            List<String> left = after == null ? List.of("loading") : s.issues(after);
            if (how != null && s.turn >= 0 && left.isEmpty()) {
                double min = minMatch(after);
                s.save(after);
                out.add(s.caseLabel(c) + String.format(": fixed (%s, turn %d%s, min match %.2f)", how, s.turn & 3, PieceLayout.mirrorOf(s.turn) ? "m" : "", min));
            } else {
                s.dropDrafts();
                s.turn = -1;
                out.add(s.caseLabel(c) + ": kept auto (was " + v.issues() + (how == null ? ", no clean alternative" : ", fix left " + left) + ")");
            }
        }
        return out;
    }

    /**
     * Dev tool (DevBridge {@code /inv open}): opens the editor on a family's case whose label (as in the check list) contains {@code label}.
     *
     * @return whether such a case exists
     */
    static boolean openOn(String pack, String fam, int lvl, String label) {
        JointEditorScreen e = new JointEditorScreen(pack, fam, PieceSets.Role.CORNER, -1);
        e.lvl = lvl;
        for (Case c : e.cases())
            if (e.caseLabel(c).contains(label)) {
                e.select(c);
                Minecraft.getInstance().setScreen(e);
                return true;
            }
        return false;
    }

    /** @return each case of a family with its issues ("label: [overlap, opening]"), one per line (the dev bridge) */
    static String issuesDump(String pack, String fam, int lvl) {
        JointEditorScreen e = new JointEditorScreen(pack, fam, PieceSets.Role.CORNER, -1);
        e.lvl = lvl;
        StringBuilder sb = new StringBuilder();
        Map<Case, Verdict> vs = e.verdicts(e.cases());
        for (var en : vs.entrySet()) sb.append(e.caseLabel(en.getKey())).append(": ").append(en.getValue().loading() ? "loading" : en.getValue().issues()).append('\n');
        return sb.toString();
    }

    /** @return the worst run's match in a laid-out case */
    private static double minMatch(Layout lay) {
        double min = 1;
        for (Run r : lay.runs) min = Math.min(min, r.fit().match());
        return min;
    }

    /**
     * Drafts the clean arrangement with the best total match over every joint orientation: per run, the best-ranked safe fit that touches (depth
     * found, match {@code minMatch} or more) and keeps the run centred on the joint (the check list's three tests).
     *
     * @return whether one was found (the turn and drafts are set)
     */
    private boolean deepFix(double minMatch) {
        int bestTurn = -1;
        double best = -1;
        List<PieceLayout.Fit> bestFits = null;
        for (int t : PieceLayout.ORIENTS) {
            Layout l = layout(role, kase, flipped, t);
            if (l == null) continue;
            List<PieceLayout.Fit> picks = new ArrayList<>();
            double sum = 0;
            for (Run r : l.runs) {
                PieceLayout.Fit pick = null;
                int lat = r.out() % 2 == 0 ? 1 : 0;
                for (PieceLayout.Fit f : PieceLayout.ranked(new int[]{0, 0}, r.out(), l.jb, l.joint, r.grid(), l.width, l.run, Math.max(3, l.width), 1000)) {
                    int[] b = PieceLayout.boxes(new int[]{0, 0}, r.out(), f.off(), List.of(l.run, l.run), l.width, f.shift()).get(0);
                    if (f.depth() >= 0 && f.match() >= minMatch && Math.abs(b[lat] + b[lat + 2] - l.jb[lat] - l.jb[lat + 2]) <= 2) {
                        pick = f;
                        break;
                    }
                }
                if (pick == null) break;
                picks.add(pick);
                sum += pick.match();
            }
            if (picks.size() == l.runs.size() && sum > best + 1e-9) {
                best = sum;
                bestTurn = t;
                bestFits = picks;
            }
        }
        if (bestFits == null) return false;
        turn = bestTurn;
        Layout l = layout();
        for (int a = 0; a < l.runs.size(); a++) setDraft(l.runs.get(a), new int[]{bestFits.get(a).shift(), bestFits.get(a).depth()});
        return true;
    }

    /** @return whether a piece the case needs (its straight or joint group) failed to load for good */
    private boolean failedPiece(Case c) {
        PieceSets.Family f = PieceCatalog.familyOf(pack, fam);
        if (f == null) return false;
        // The pieces layout() picks: the main straight and the joint piece of its level.
        var st = PieceCatalog.groups(pack, f, PieceSets.Role.STRAIGHT);
        var js = PieceCatalog.groups(pack, f, ROLES[c.role()]);
        if (st.isEmpty() || js.isEmpty()) return false;
        int level = Math.max(1, lvl >= 0 ? lvl : LineView.level);
        PieceSets.Piece sp = st.get(0).pick(st.get(0).levels() ? level : 0, 0), jp = js.get(0).pickAs(sp, level, 0);
        return PieceCatalog.failed(pack, sp.path()) || PieceCatalog.failed(pack, jp.path());
    }

    /** @return the problems a laid-out case has: overlap (a run would clear or replace joint blocks), nofit (no contact or a poor match), offcentre (a run more than a cell off the joint's centre) */
    private List<String> issues(Layout l) {
        boolean overlap = false, nofit = false, off = false, opening = false, road = fam != null && fam.startsWith("road:");
        for (Run r : l.runs) {
            PieceLayout.Fit f = r.fit();
            overlap |= PieceLayout.destructiveAt(new int[]{0, 0}, r.out(), l.jb, l.joint, r.grid(), l.width, l.run, f.shift(), f.depth());
            opening |= PieceLayout.openingAt(new int[]{0, 0}, r.out(), l.jb, l.joint, r.grid(), l.width, l.run, f.shift(), f.depth(), road);
            nofit |= f.depth() < 0 || f.match() < 0.3;
            int[] b = runBoxes(l, r).get(0);
            int lat = r.out() % 2 == 0 ? 1 : 0;
            off |= Math.abs(b[lat] + b[lat + 2] - l.jb[lat] - l.jb[lat + 2]) > 2;
        }
        List<String> out = new ArrayList<>();
        if (overlap) out.add("overlap");
        // A tower's narrow slit beside a wall end (its parapet ring is air at wall height) reads as the stakes' own rhythm: minor.
        if (opening) out.add(l.tower() ? "towergap" : "opening");
        if (nofit) out.add("nofit");
        if (off) out.add("offcentre");
        return out;
    }

    /** @return how many of a family's cases the previous definition called real (a gap or an overlap: "overlap" or "nofit"), for comparing (the dev bridge) */
    static int legacyReal(String pack, String fam, int lvl) {
        JointEditorScreen s = new JointEditorScreen(pack, fam, PieceSets.Role.CORNER, -1);
        s.lvl = lvl;
        List<Case> cs = s.cases();
        int n = 0;
        for (Verdict v : s.verdicts(cs).values()) if (!v.loading() && (v.issues().contains("overlap") || v.issues().contains("nofit"))) n++;
        return n;
    }

    /** Jumps the editor to a case (auto turn, flat, the first run that has a problem selected). */
    private void select(Case c) {
        role = c.role();
        kase = c.kase();
        flipped = false;
        turn = -1;
        runSel = 0;
        dropDrafts();
        Layout l = layout();
        if (l == null) return;
        for (int i = 0; i < l.runs.size(); i++) {
            PieceLayout.Fit f = l.runs.get(i).fit();
            if (f.depth() < 0 || f.match() < 0.3) {
                runSel = i;
                break;
            }
        }
    }

    /** Previous / next case of the list. */
    private void step(int d) {
        List<Case> cs = cases();
        if (cs.isEmpty()) return;
        int at = cs.indexOf(new Case(role, kase));
        select(cs.get(Math.floorMod(at + d, cs.size())));
    }

    /** The joint check list: every case with its verdict and a Fix button (opens the editor on it). */
    private void drawCheck(GuiGraphics g, int mx, int my) {
        int pw = Math.min(width - 12, 460), px = (width - pw) / 2, py = 6, ph = height - 12;
        VanillaUi.panel(g, px, py, pw, ph);
        int ix = px + 7, iw = pw - 14, iy = py + 7;
        g.drawString(font, Component.translatable("mccitybuilder.jointcheck.title"), ix, iy, VanillaUi.LABEL, false);
        iy += 12;
        iy = packFamily(g, ix, iy, iw, mx, my);
        List<Case> cs = cases();
        Map<Case, Verdict> vs = verdicts(cs);
        long bad = cs.stream().map(vs::get).filter(v -> v != null && !v.loading() && !v.ok()).count(),
                wait = cs.stream().map(vs::get).filter(v -> v == null || v.loading()).count();
        String sum = cs.isEmpty() ? Component.translatable("mccitybuilder.jointcheck.none").getString()
                : Component.translatable("mccitybuilder.jointcheck.summary", cs.size() - bad - wait, cs.size(), bad).getString() + (wait > 0 ? " " + Component.translatable("mccitybuilder.jointcheck.loading", wait).getString() : "");
        g.drawString(font, font.plainSubstrByWidth(sum, iw), ix, iy + 2, bad > 0 ? 0xFFE0A020 : 0xFFFFFFFF, false);
        iy += 16;
        int rowH = 18, bottom = py + ph - 30, fit = Math.max(1, (bottom - iy) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cs.size() - fit)));
        for (int i = scroll; i < cs.size() && i < scroll + fit; i++) {
            Case c = cs.get(i);
            Verdict v = vs.get(c);
            boolean wait1 = v == null || v.loading();
            g.drawString(font, wait1 ? "…" : v.ok() ? "✓" : "⚠", ix, iy + 5, wait1 ? 0xFF909090 : v.ok() ? 0xFF40C040 : 0xFFE0A020, false);
            g.drawString(font, font.plainSubstrByWidth(caseLabel(c), 134), ix + 14, iy + 5, 0xFFFFFFFF, false);
            if (!wait1 && v.ok()) g.drawString(font, Component.translatable("mccitybuilder.fix.state.0"), ix + 150, iy + 5, 0xFF40C040, false);
            if (!wait1 && !v.ok()) {
                StringBuilder sb = new StringBuilder();
                for (String k : v.issues()) sb.append(sb.length() > 0 ? ", " : "").append(Component.translatable(word("mccitybuilder.jointcheck." + k)).getString());
                g.drawString(font, font.plainSubstrByWidth(sb.toString(), iw - 150 - 50), ix + 150, iy + 5, 0xFFE0A020, false);
            }
            if (wait1 || !v.ok()) {
                int bx = ix + iw - 44;
                VanillaUi.button(g, bx, iy, 44, 16, false, false, in(mx, my, bx, iy, 44, 16));
                String l = Component.translatable("mccitybuilder.jointcheck.fix").getString();
                g.drawString(font, l, bx + (44 - font.width(l)) / 2, iy + 4, VanillaUi.INK, false);
                hits.add(new Hit(bx, iy, 44, 16, () -> {
                    checking = false;
                    select(c);
                }));
            }
            iy += rowH;
        }
        int by = py + ph - 24, bw = (iw - 4) / 2;
        String[] ks = {"recheck", "close"};
        for (int k = 0; k < 2; k++) {
            int bx = ix + k * (bw + 2), fk = k;
            VanillaUi.button(g, bx, by, bw, 20, false, false, in(mx, my, bx, by, bw, 20));
            String l = Component.translatable("mccitybuilder.jointcheck." + ks[k]).getString();
            g.drawString(font, l, bx + (bw - font.width(l)) / 2, by + 6, VanillaUi.INK, false);
            hits.add(new Hit(bx, by, bw, 20, () -> {
                if (fk == 0) status = null;
                else onClose();
            }));
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (modal.open() && key != InputConstants.KEY_ESCAPE) return true;
        if (!checking) {
            Layout lay = layout();
            if (lay != null) {
                Run sel = lay.runs.get(Math.min(runSel, lay.runs.size() - 1));
                // As the buttons: left / right as seen from the wall's far end, up = closer (deeper into the joint).
                if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
                    adjust(sel, (key == InputConstants.KEY_LEFT ? -1 : 1) * com.volcanicmg.mccitybuilder.planner.JointHelp.rightShift(sel.out()), 0);
                    return true;
                }
                if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
                    adjust(sel, 0, key == InputConstants.KEY_UP ? 1 : -1);
                    return true;
                }
                if (key == InputConstants.KEY_R) {
                    confirm(() -> turnJoint(lay));
                    return true;
                }
                if (key == InputConstants.KEY_M) {
                    confirm(() -> mirrorJoint(lay));
                    return true;
                }
            }
        }
        return super.keyPressed(key, scan, mods);
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
            if (modal.click(mx, my)) return true;
            if (button != 0) return false;
            for (Hit h : List.copyOf(hits))
                if (in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                    h.run().run();
                    return true;
                }
            if (view != null && in(mx, my, view[0], view[1], view[2], view[3])) {
                drag = PieceView.dragging = true;
                pressX = mx;
                pressY = my;
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
        // A click (not a drag) in the 3D view selects the wall nearest the cursor.
        if (drag && Math.abs(mx - pressX) + Math.abs(my - pressY) < 4) {
            int best = -1;
            double bd = 40 * 40;
            for (int i = 0; i < armAt.size(); i++) {
                double dx = armAt.get(i)[0] - mx, dy = armAt.get(i)[1] - my;
                if (dx * dx + dy * dy < bd) {
                    bd = dx * dx + dy * dy;
                    best = i;
                }
            }
            if (best >= 0) runSel = best;
        }
        drag = PieceView.dragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (modal.scroll(sy)) return true;
        if (checking) {
            scroll = Math.max(0, scroll - (int) Math.signum(sy));
            return true;
        }
        if (view == null || !in(mx, my, view[0], view[1], view[2], view[3])) return false;
        PieceView.zoom = (float) Math.max(0.3, Math.min(5, PieceView.zoom * (sy > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }
}
