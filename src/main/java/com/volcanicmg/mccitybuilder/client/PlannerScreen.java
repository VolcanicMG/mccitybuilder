package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.AbstractColonyBlock;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.workorders.IWorkOrderView;
import com.minecolonies.core.colony.buildings.views.AbstractBuildingBuilderView;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.network.messages.server.colony.WorkOrderChangeMessage;
import com.minecolonies.core.network.messages.server.colony.building.BuildRequestMessage;
import com.minecolonies.core.network.messages.server.colony.building.builder.BuilderSelectWorkOrderMessage;
import com.volcanicmg.mccitybuilder.Config;
import com.volcanicmg.mccitybuilder.planner.ActionIcons;
import com.volcanicmg.mccitybuilder.planner.Hints;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.QueueIcons;
import com.volcanicmg.mccitybuilder.planner.QueueOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * The planner's screen: no background (the world shows through from the overhead camera), the hut palette on the
 * left, the builder queue on the right, the selected building's actions at the bottom and the controls guide. Clicks
 * that miss the panels go to the world: place the hut being placed, or select a building.
 */
final class PlannerScreen extends Screen {
    private static final int PAD = 7, SLOT = 18, COLS = 9, PALETTE_W = PAD * 2 + COLS * SLOT, QUEUE_W = 210, ROW_H = 24;
    private static String pack, category;
    // The pack the player picked this client session, per colony id: it wins over the colony's own style.
    private static final java.util.Map<Integer, String> pickedPack = new java.util.HashMap<>();
    // Colonies already told their style isn't installed (once per session).
    private static final java.util.Set<Integer> missingTold = new java.util.HashSet<>();
    private static int queueScroll;

    /** A clickable area this frame. */
    private record Hit(int x, int y, int w, int h, Runnable action, net.minecraft.network.chat.FormattedText tip) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<int[]> panels = new ArrayList<>();
    // Queue drag: the row index pressed and the row it is over now.
    private int dragFrom = -1, dragOver = -1;
    private List<IWorkOrderView> shownOrders = List.of();
    private int queueTop, queueBottom;
    // Right button: a drag turns the camera, a press without one (under DRAG_PX) backs out on release.
    private static final double DRAG_PX = 4;
    private boolean rightDown, rightDragged;
    // Frames a hover survives pick misses (a ray that finds nothing for a frame while the camera moves).
    private static final int HOVER_HOLD = 6;
    private int hoverMisses;
    private double rightX, rightY;
    private Component toast;
    private long toastUntil;

    PlannerScreen() {
        super(Component.translatable("mccitybuilder.title"));
    }

    /**
     * @return the style pack the palette and the road / wall tools use: the one the player picked for this colony this
     * session, else the colony's own style (town hall setting), else the first installed pack (with a one-time hint)
     */
    static String pack() {
        List<String> all = Blueprints.packs();
        IColonyView c = Planner.colony();
        String picked = c == null ? null : pickedPack.get(c.getID());
        String want = picked != null && all.contains(picked) ? picked : c != null ? c.getStructurePack() : pack;
        if (want != null && all.contains(want)) pack = want;
        else if (pack == null || !all.contains(pack)) {
            pack = all.isEmpty() ? "" : all.get(0);
            if (c != null && !all.isEmpty() && want != null && !want.isEmpty() && missingTold.add(c.getID()) && Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.displayClientMessage(Component.translatable("mccitybuilder.pack.missing", want, pack), false);
        }
        return pack;
    }

    /** The player picks a pack: it stays for this colony until the game closes. */
    private static void pickPack(String p) {
        pack = p;
        IColonyView c = Planner.colony();
        if (c != null) pickedPack.put(c.getID(), p);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // The world from the planner camera is the background.
    }

    @Override
    public void onClose() {
        Planner.exit(null);
    }

    // Every entry point is guarded: an exception leaves planner view cleanly instead of crashing the client.

    @Override
    public void tick() {
        Planner.guard(this::tickImpl);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        Planner.guard(() -> renderImpl(g, mouseX, mouseY));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return Planner.guard(() -> clickedImpl(mx, my, button));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return Planner.guard(() -> draggedImpl(mx, my, dx, dy));
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return Planner.guard(() -> releasedImpl(mx, my, button));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        return Planner.guard(() -> scrolledImpl(mx, my, sy));
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        return Planner.guard(() -> keyImpl(key, scancode));
    }

    // ---- per-frame ----

    private void tickImpl() {
        // Camera movement is polled every frame in Planner.updateCamera.
    }

    private void renderImpl(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        panels.clear();
        IColonyView colony = Planner.colony();
        if (colony == null) return;
        // Aim the ghost and find the building under the cursor (every frame, so both follow smoothly).
        BlockHitResult hit = Planner.pick(mouseX / (double) width, mouseY / (double) height);
        Planner.aim(hit);
        LineView.aim(hit);
        banner(g, colony);
        overlayMarkers(g);
        overlayPicker(g, mouseX, mouseY);
        palette(g, mouseX, mouseY);
        queue(g, colony, mouseX, mouseY);
        selection(g, mouseX, mouseY);
        // The line panel first: it sits lowest and the plan panel stacks above it (never under it, where its
        // hit boxes would take the line panel's clicks).
        linePanel(g, mouseX, mouseY);
        planPanel(g, mouseX, mouseY);
        lineLabels(g, mouseX, mouseY);
        actionBar(g, mouseX, mouseY);
        guide(g, mouseX, mouseY);
        status(g);
        boolean overUi = overPanel(mouseX, mouseY);
        // Re-picked every frame from the current camera; a miss only clears it after a few frames, so it holds steady.
        IBuildingView now = !overUi && !Planner.isPlacing() && hit != null ? Planner.buildingAt(hit.getBlockPos()) : null;
        if (now != null || overUi || Planner.isPlacing() || ++hoverMisses > HOVER_HOLD) {
            Planner.hovered = now;
            hoverMisses = 0;
        }
        for (Hit h : hits)
            if (h.in(mouseX, mouseY) && h.tip() != null) {
                g.renderTooltip(font, font.split(h.tip(), 220), mouseX, mouseY);
                return;
            }
        if (overUi) return;
        int[] mk = markerAt(mouseX, mouseY);
        if (mk != null) {
            g.renderComponentTooltip(font, markerTip(OverlayView.markers.get(mk[0])), mouseX, mouseY);
            return;
        }
        if (Planner.hovered != null)
            g.renderTooltip(font, Component.literal(name(Planner.hovered) + " " + levelText(Planner.hovered)), mouseX, mouseY);
        else if (Planner.isPlacing() && Planner.invalidReason != null && !Planner.invalidReason.equals("loading")) {
            // Why it cannot go here, right at the cursor.
            Component r = Component.translatable("mccitybuilder.invalid." + Planner.invalidReason);
            int w = font.width(r);
            g.fill(mouseX + 10, mouseY + 10, mouseX + 16 + w, mouseY + 23, 0xC0300000);
            g.drawString(font, r, mouseX + 13, mouseY + 13, 0xFFFF8080, false);
        }
    }

    // ---- overlays ----

    // Where each overlay marker was drawn this frame: {index, x, y}.
    private final List<int[]> drawnMarkers = new ArrayList<>();

    /** Citizen markers projected to the screen: a hammer badge for builders, a job-coloured square for workers. */
    private void overlayMarkers(GuiGraphics g) {
        drawnMarkers.clear();
        // Staffing "assigned/slots" over each hut with jobs (red none, amber under, green full); a label that would
        // overlap one already drawn is skipped, and they fade when the camera is high.
        List<int[]> labels = new ArrayList<>();
        int alpha = Planner.heightAboveGround() > 90 ? 0x80 : 0xC0;
        if (com.volcanicmg.mccitybuilder.Config.SHOW_STAFFING.get()) for (var en : OverlayView.counts.entrySet()) {
            BlockPos p = en.getKey();
            double[] ndc = Planner.project(p.getX() + 0.5, p.getY() + 2.5, p.getZ() + 0.5);
            if (ndc == null || Math.abs(ndc[0]) > 1 || Math.abs(ndc[1]) > 1) continue;
            int[] c = en.getValue();
            String t = c[0] + "/" + c[1];
            int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height), tw = font.width(t);
            int[] r = {sx - tw / 2 - 2, sy - 6, sx + tw / 2 + 2, sy + 5};
            boolean overlap = false;
            for (int[] o : labels) overlap |= r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3];
            if (overlap) continue;
            labels.add(r);
            int col = c[0] == 0 ? 0xFF6060 : c[0] < c[1] ? 0xFFC040 : 0x70E070;
            g.fill(r[0], r[1], r[2], r[3], alpha << 24);
            g.drawString(font, t, sx - tw / 2, sy - 4, (alpha + 0x3F) << 24 | col, false);
        }
        List<OverlayView.Marker> ms = OverlayView.markers;
        for (int i = 0; i < ms.size(); i++) {
            OverlayView.Marker m = ms.get(i);
            double[] ndc = Planner.project(m.pos().x, m.pos().y, m.pos().z);
            if (ndc == null || Math.abs(ndc[0]) > 1.05 || Math.abs(ndc[1]) > 1.05) continue;
            int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height);
            if (m.site() != null) {
                g.fill(sx - 7, sy - 7, sx + 7, sy + 7, 0xFF000000);
                g.fill(sx - 6, sy - 6, sx + 6, sy + 6, m.color());
                icon(g, "build", sx - 4, sy - 4, 0xFF202020);
            } else {
                g.fill(sx - 4, sy - 4, sx + 4, sy + 4, 0xFF000000);
                g.fill(sx - 3, sy - 3, sx + 3, sy + 3, m.color());
            }
            drawnMarkers.add(new int[]{i, sx, sy});
        }
    }

    /** @return {index, x, y} of the overlay marker under the mouse, or null */
    private int[] markerAt(double mx, double my) {
        for (int k = drawnMarkers.size() - 1; k >= 0; k--) {
            int[] d = drawnMarkers.get(k);
            int r = OverlayView.markers.size() > d[0] && OverlayView.markers.get(d[0]).site() != null ? 7 : 5;
            if (Math.abs(mx - d[1]) <= r && Math.abs(my - d[2]) <= r) return d;
        }
        return null;
    }

    private List<Component> markerTip(OverlayView.Marker m) {
        List<Component> out = new ArrayList<>();
        ICitizenDataView c = m.citizen();
        out.add(Component.literal(c == null ? "?" : c.getName()));
        if (c != null) out.add(c.getJobComponent().copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        if (c != null && c.hasBlockingInteractions()) out.add(Component.translatable("mccitybuilder.overlay.needs_help").withStyle(net.minecraft.ChatFormatting.RED));
        if (m.site() != null) {
            out.add(m.site().site() == null ? Component.translatable("mccitybuilder.overlay.idle")
                    : Component.translatable("mccitybuilder.overlay.site", m.site().site().toShortString()));
            if (!m.site().needs().isEmpty()) out.add(Component.translatable("mccitybuilder.overlay.waiting").withStyle(net.minecraft.ChatFormatting.GOLD));
            for (var n : m.site().needs()) out.add(Component.literal(" " + n.missing() + " x " + n.name()));
        } else out.add(Component.translatable("mccitybuilder.overlay.click_citizen").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return out;
    }

    private static boolean editorOpen;
    private static final com.volcanicmg.mccitybuilder.planner.PieceSets.Role[] EDIT_ROLES = {com.volcanicmg.mccitybuilder.planner.PieceSets.Role.STRAIGHT,
            com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TEE,
            com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CROSS, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.END,
            com.volcanicmg.mccitybuilder.planner.PieceSets.Role.GATE, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TOWER};
    // Top of the bottom-right panel stack this frame (the line panel sits lowest, the plan panel above it).
    private int stackTop;

    /**
     * The road / wall panel: family, level or variant, towers / gate / Pieces (the per-role editor opens as its own
     * popup), a card showing the piece being laid (top-down render, name, size, role), the segment's numbers, and
     * Done / Undo / Build.
     */
    private void linePanel(GuiGraphics g, int mx, int my) {
        stackTop = height - 4;
        if (!LineView.active() && PlanView.selectedLine() == null) {
            editorOpen = false;
            return;
        }
        com.volcanicmg.mccitybuilder.planner.Plan.Line sel = PlanView.selectedLine();
        String tool = sel != null ? sel.tool() : LineView.tool, pk = sel != null ? sel.pack() : pack();
        String status = sel != null ? null : LineView.status();
        String fam = sel != null ? sel.family() : status == null ? LineView.family() : null;
        int w = 220, h = status != null ? 40 : 182, x = Math.max(4 + PALETTE_W + 4, width - 4 - Hints.WIDTH - 4 - w), y = height - h - 4;
        stackTop = y - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, iw = w - 2 * PAD;
        g.drawString(font, Component.translatable(sel != null ? "mccitybuilder.line.selected." + tool : "mccitybuilder.line.title." + tool), ix + 1, y + PAD, VanillaUi.LABEL, false);
        if (status != null) {
            g.drawString(font, Component.translatable("mccitybuilder.line.status." + status + "." + tool, pk), ix + 1, y + PAD + 13, 0xFFA02020, false);
            return;
        }
        int iy = picker(g, ix, y + PAD + 11, iw, Component.translatable("mccitybuilder.line.family", fam == null ? "-" : fam.substring(fam.indexOf(':') + 1)).getString(),
                mx, my, d -> {
                    LineView.cycleFamily(d);
                    click();
                }, Component.translatable("mccitybuilder.line.family.tip"));
        int[] li = LineView.levelInfo();
        int lv = sel != null ? sel.level() : LineView.level, va = sel != null ? sel.variant() : LineView.variant;
        String lvText = li[1] == 1 ? Component.translatable("mccitybuilder.line.level", Math.min(lv, li[0]), li[0]).getString()
                : va < 0 ? Component.translatable("mccitybuilder.line.variant_random", li[0]).getString() : Component.translatable("mccitybuilder.line.variant", va + 1, li[0]).getString();
        iy = picker(g, ix, iy, iw, lvText, mx, my, d -> {
            LineView.cycleLevel(d, li[0], li[1] == 1);
            click();
        }, Component.translatable("mccitybuilder.line.level.tip"));
        // Towers at joints, a gate (walls: the selected segment or the next one), and the per-role editor.
        boolean wall = "wall".equals(tool);
        boolean[] on = {sel != null ? sel.towers() : LineView.towers, sel != null ? sel.gate() : LineView.gate, editorOpen};
        String[] keys = {"towers", "gate", "pieces"};
        int bw3 = (iw - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = ix + i * (bw3 + 2);
            boolean off = i == 1 && !wall, over = mx >= bx && mx < bx + bw3 && my >= iy && my < iy + 16;
            VanillaUi.button(g, bx, iy, bw3, 16, on[i], off, over);
            String l = Component.translatable("mccitybuilder.line." + keys[i]).getString();
            g.drawString(font, l, bx + (bw3 - font.width(l)) / 2, iy + 4, off ? VanillaUi.INK_DISABLED : VanillaUi.INK, false);
            int fi = i;
            hits.add(new Hit(bx, iy, bw3, 16, () -> {
                if (fi == 1 && !wall) return;
                click();
                if (fi == 0) LineView.toggleTowers();
                else if (fi == 1) LineView.toggleGate();
                else editorOpen = !editorOpen;
            }, Component.translatable("mccitybuilder.line." + keys[i] + ".tip")));
        }
        iy += 20;
        iy = pieceCard(g, ix, iy, iw);
        // The segment's numbers: the live one and the chain, or the selected segment.
        String info;
        if (sel != null) {
            int idx = PlanView.selectedLine;
            var ps = idx < PlanView.linePieces.size() ? PlanView.linePieces.get(idx) : java.util.List.<PieceCatalog.Placed>of();
            int sh = idx < PlanView.lineShort.size() ? PlanView.lineShort.get(idx) : 0;
            info = Component.translatable("mccitybuilder.line.cost_selected", com.volcanicmg.mccitybuilder.planner.LineSnap.length(new int[]{sel.x0(), sel.z0()},
                    new int[]{sel.x1(), sel.z1()}) + 1, ps.size(), LineView.cost(ps), sh).getString();
        } else if (LineView.start == null) info = Component.translatable("mccitybuilder.line.click_start").getString();
        else {
            int chainPieces = 0;
            for (var l : LineView.chain) {
                int idx = PlanView.lines.indexOf(l);
                if (idx >= 0 && idx < PlanView.linePieces.size()) chainPieces += PlanView.linePieces.get(idx).size();
            }
            info = Component.translatable("mccitybuilder.line.chain", LineView.chain.size(), chainPieces).getString();
        }
        Component prog = PlanView.progress();
        if (prog != null) info = prog.getString();
        g.drawString(font, font.plainSubstrByWidth(info, iw), ix + 1, iy, 0xFF555555, false);
        iy += 12;
        boolean server = Planner.serverInstant();
        String[] bkeys = {"done", "undo", "build"};
        boolean[] off = {LineView.start == null, LineView.chain.isEmpty(), PlanView.lines.isEmpty() && PlanView.entries.isEmpty()};
        Runnable[] run = {LineView::endChain, LineView::undo, () -> toast(PlanView.commit(false))};
        int bw = (iw - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = ix + i * (bw + 2);
            boolean over = mx >= bx && mx < bx + bw && my >= iy && my < iy + 20;
            VanillaUi.button(g, bx, iy, bw, 20, false, off[i], over);
            String l = Component.translatable("mccitybuilder.line." + bkeys[i]).getString();
            g.drawString(font, l, bx + (bw - font.width(l)) / 2, iy + 6, off[i] ? VanillaUi.INK_DISABLED : VanillaUi.INK, false);
            int fi = i;
            hits.add(new Hit(bx, iy, bw, 20, () -> {
                if (off[fi]) return;
                click();
                run[fi].run();
            }, Component.translatable(i == 2 && !server ? "mccitybuilder.line.needs_server" : "mccitybuilder.line." + bkeys[i] + ".tip")));
        }
        if (editorOpen && fam != null) pieceEditor(g, x - 4 - w, Math.max(4, y + h - (PAD * 2 + 22 + EDIT_ROLES.length * 18)), w, pk, fam, mx, my);
    }

    /**
     * "What am I placing": a top-down render of the current straight (or the selected segment's), its name, its size
     * (length x width x height along the run) and role.
     *
     * @return the y below the card
     */
    private int pieceCard(GuiGraphics g, int x, int y, int w) {
        int ch = 44;
        VanillaUi.slot(g, x, y, w, ch);
        com.volcanicmg.mccitybuilder.planner.PieceSets.Piece p = LineView.current();
        if (p == null) {
            g.drawString(font, Component.translatable("mccitybuilder.line.card.loading"), x + 6, y + 6, 0xFFFFFFFF, true);
            return y + ch + 4;
        }
        String pk = PlanView.selectedLine() != null ? PlanView.selectedLine().pack() : pack();
        int box = ch - 4;
        int[] top = PieceCatalog.topView(pk, p.path());
        if (top != null) {
            // Laid along the screen's X: the run left to right.
            boolean turn = p.sz() > p.sx();
            int rw = turn ? p.sz() : p.sx(), rh = turn ? p.sx() : p.sz(), cell = Math.max(1, Math.min(box / Math.max(1, rw), box / Math.max(1, rh)));
            int ox = x + 2 + (box - rw * cell) / 2, oy = y + 2 + (box - rh * cell) / 2;
            for (int u = 0; u < rw; u++)
                for (int v = 0; v < rh; v++) {
                    int c = turn ? top[u * p.sx() + v] : top[v * p.sx() + u];
                    if (c != 0) g.fill(ox + u * cell, oy + v * cell, ox + (u + 1) * cell, oy + (v + 1) * cell, c);
                }
        }
        int tx = x + box + 8, tw = w - box - 12;
        String name = p.path().substring(p.path().lastIndexOf('/') + 1).replace(".blueprint", "");
        g.drawString(font, font.plainSubstrByWidth(name, tw), tx, y + 4, 0xFFFFFFFF, true);
        g.drawString(font, Component.translatable("mccitybuilder.line.card.size", p.run(), p.across(), p.sy()), tx, y + 16, 0xFFD0D0D0, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.line.card.role",
                Component.translatable("mccitybuilder.line.role.straight")).getString(), tw), tx, y + 28, 0xFFA0A0A0, false);
        return y + ch + 4;
    }

    /** The per-role piece editor, a popup beside the line panel: which piece group plays each role (auto / none / any). */
    private void pieceEditor(GuiGraphics g, int x, int y, int w, String pk, String fam, int mx, int my) {
        var fs = PieceCatalog.families(pk);
        var f = fs == null ? null : fs.get(fam);
        if (f == null) return;
        int h = PAD * 2 + 22 + EDIT_ROLES.length * 18;
        x = Math.max(4, x);
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, iw = w - 2 * PAD;
        g.drawString(font, Component.translatable("mccitybuilder.line.editor"), ix + 1, y + PAD, VanillaUi.LABEL, false);
        int cx = x + w - PAD - 12;
        boolean overX = mx >= cx && mx < cx + 12 && my >= y + PAD - 2 && my < y + PAD + 10;
        VanillaUi.button(g, cx, y + PAD - 2, 12, 12, false, false, overX);
        g.drawString(font, "x", cx + 3, y + PAD, VanillaUi.INK, false);
        hits.add(new Hit(cx, y + PAD - 2, 12, 12, () -> {
            click();
            editorOpen = false;
        }, null));
        int iy = y + PAD + 14;
        java.util.List<com.volcanicmg.mccitybuilder.planner.PieceSets.Group> all = PieceCatalog.allGroups(f);
        for (var role : EDIT_ROLES) {
            String cur = PieceCatalog.overrides(pk).get(fam + "|" + role.name());
            var used = PieceCatalog.groups(pk, f, role);
            String label = Component.translatable("mccitybuilder.line.role." + role.name().toLowerCase(java.util.Locale.ROOT)).getString() + ": "
                    + (cur == null ? (used.isEmpty() ? "-" : used.get(0).base()) + " (auto)" : cur.equals("-") ? "none" : cur);
            // Choices: auto, none, then every piece group of the family.
            java.util.List<String> choices = new java.util.ArrayList<>(java.util.Arrays.asList(null, "-"));
            for (var gr : all) choices.add(gr.base());
            iy = picker(g, ix, iy, iw, label, mx, my, d -> {
                int k = choices.indexOf(cur);
                String nx = choices.get(Math.floorMod(k + d, choices.size()));
                PieceCatalog.override(pk, fam, role, nx);
                PlanView.linesChanged();
                click();
            }, Component.translatable("mccitybuilder.line.role.tip")) - 2;
        }
    }

    /**
     * In the world while drawing: the live segment's length and piece count at its midpoint, and at the cursor the
     * short-gap hint, "too short", or a refused click's hint.
     */
    private void lineLabels(GuiGraphics g, int mx, int my) {
        if (!LineView.active()) return;
        String warn = null;
        int warnColour = 0xFFFFC040;
        if (LineView.start != null && LineView.end != null && LineView.liveLen > 1) {
            boolean fits = com.volcanicmg.mccitybuilder.planner.LineSnap.canDrop(LineView.liveLen, LineView.liveNeed);
            Component t = fits ? Component.translatable("mccitybuilder.line.label", LineView.liveLen, LineView.live.size())
                    : LineView.liveNeed == Integer.MAX_VALUE ? Component.translatable("mccitybuilder.line.no_fit")
                    : Component.translatable("mccitybuilder.line.too_short", LineView.liveNeed);
            double wx = (LineView.start[0] + LineView.end[0]) / 2.0 + 0.5, wz = (LineView.start[1] + LineView.end[1]) / 2.0 + 0.5;
            Integer gy = Planner.groundAt((int) Math.floor(wx), (int) Math.floor(wz));
            int top = 0;
            for (PieceCatalog.Placed p : LineView.live) top = Math.max(top, p.y0() + p.h());
            double[] ndc = Planner.project(wx, Math.max(top, (gy == null ? 64 : gy) + 2) + 1, wz);
            if (ndc != null && Math.abs(ndc[0]) <= 1 && Math.abs(ndc[1]) <= 1) {
                int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height), tw = font.width(t);
                g.fill(sx - tw / 2 - 3, sy - 6, sx + tw / 2 + 3, sy + 6, 0xC0202020);
                g.drawString(font, t, sx - tw / 2, sy - 4, fits ? 0xFFFFFFFF : 0xFFFF8080, false);
            }
            if (!fits) {
                warn = t.getString();
                warnColour = 0xFFFF8080;
            } else if (LineView.liveShort > 0) warn = Component.translatable("mccitybuilder.line.short", LineView.liveShort).getString();
        }
        if (LineView.flash != null && System.currentTimeMillis() < LineView.flashUntil) {
            warn = Component.translatable(LineView.flash, LineView.flashArg).getString();
            warnColour = 0xFFFF8080;
        }
        if (warn == null || overPanel(mx, my)) return;
        int w = font.width(warn);
        g.fill(mx + 10, my + 10, mx + 16 + w, my + 23, 0xC0202020);
        g.drawString(font, warn, mx + 13, my + 13, warnColour, false);
    }

    /** Road / wall tool on or off (they replace each other; neither changes hut placement). */
    private void useTool(String t) {
        LineView.use(t);
        PlanView.linesChanged();
        PlanView.selectedLine = -1;
        click();
        toast(Component.translatable(LineView.active() ? "mccitybuilder.line.on." + LineView.tool : "mccitybuilder.line.off"));
    }

    private void togglePlan() {
        PlanView.on = !PlanView.on;
        PlanView.linesChanged();
        if (!PlanView.on) {
            PlanView.hide();
            PlanView.selected = -1;
        }
        click();
        toast(Component.translatable(PlanView.on ? "mccitybuilder.plan.on" : "mccitybuilder.plan.off"));
    }

    /** Plan mode: the plan's state and its Build all / Build selected / Clear buttons. */
    private void planPanel(GuiGraphics g, int mx, int my) {
        if (!PlanView.on) return;
        int w = 200, h = 58, x = Math.max(4 + PALETTE_W + 4, width - 4 - Hints.WIDTH - 4 - w), y = stackTop - h;
        if (Planner.selected != null) y -= 62;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        long bad = PlanView.conflicts.stream().filter(c -> c != com.volcanicmg.mccitybuilder.planner.Plan.Conflict.NONE).count();
        g.drawString(font, Component.translatable("mccitybuilder.plan.title", PlanView.entries.size(), PlanView.lines.size(), bad), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        String sub = PlanView.progress() != null ? PlanView.progress().getString()
                : PlanView.hasSelection() ? selectedPlanText() : Component.translatable("mccitybuilder.plan.hint").getString();
        g.drawString(font, font.plainSubstrByWidth(sub, w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        String[] keys = {"build_all", "build_sel", "clear"};
        boolean empty = PlanView.entries.isEmpty() && PlanView.lines.isEmpty();
        boolean[] off = {empty, !PlanView.hasSelection() && PlanView.selectedLine() == null, empty};
        Runnable[] run = {() -> toast(PlanView.commit(false)), () -> toast(PlanView.commit(true)), PlanView::clear};
        int bw = (w - 2 * PAD - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = x + PAD + i * (bw + 2), by = y + PAD + 24;
            boolean over = mx >= bx && mx < bx + bw && my >= by && my < by + 20;
            VanillaUi.button(g, bx, by, bw, 20, false, off[i], over);
            String l = Component.translatable("mccitybuilder.plan." + keys[i]).getString();
            g.drawString(font, l, bx + (bw - font.width(l)) / 2, by + 6, off[i] ? VanillaUi.INK_DISABLED : VanillaUi.INK, false);
            int fi = i;
            hits.add(new Hit(bx, by, bw, 20, () -> {
                if (off[fi]) return;
                click();
                run[fi].run();
            }, Component.translatable("mccitybuilder.plan." + keys[i] + ".tip")));
        }
    }

    private String selectedPlanText() {
        var e = PlanView.entries.get(PlanView.selected);
        String name = e.isHut() ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(e.hut())).getName().getString()
                : e.path().substring(e.path().lastIndexOf('/') + 1).replace(".blueprint", "");
        int i = PlanView.selected;
        var c = i < PlanView.conflicts.size() ? PlanView.conflicts.get(i) : com.volcanicmg.mccitybuilder.planner.Plan.Conflict.NONE;
        return name + (e.isHut() ? " Lv " + e.level() : "") + (c == com.volcanicmg.mccitybuilder.planner.Plan.Conflict.NONE ? ""
                : "  " + Component.translatable("mccitybuilder.plan.conflict." + c.name().toLowerCase(java.util.Locale.ROOT)).getString());
    }

    /** Overlay picker under the banner: [<] Overlay: Builders [>] (L cycles); the Plan toggle beside it. */
    private void overlayPicker(GuiGraphics g, int mx, int my) {
        // Plan, Road and Wall under the banner (the overlay is always on; config can hide parts of it).
        int y = 20, px = (width - 164) / 2;
        // Road and Wall tools beside the Plan toggle.
        String[] tools = {"road", "wall"};
        for (int i = 0; i < 2; i++) {
            int tx = px + 64 + i * 52;
            String t = tools[i];
            panels.add(new int[]{tx, y, 48, 16});
            boolean overT = mx >= tx && mx < tx + 48 && my >= y && my < y + 16;
            VanillaUi.button(g, tx, y, 48, 16, t.equals(LineView.tool), false, overT);
            String tl = Component.translatable("mccitybuilder.line.tool." + t).getString();
            g.drawString(font, tl, tx + (48 - font.width(tl)) / 2, y + 4, VanillaUi.INK, false);
            hits.add(new Hit(tx, y, 48, 16, () -> useTool(t), Component.translatable("mccitybuilder.line.tool." + t + ".tip")));
        }
        panels.add(new int[]{px, y, 60, 16});
        boolean overP = mx >= px && mx < px + 60 && my >= y && my < y + 16;
        VanillaUi.button(g, px, y, 60, 16, PlanView.on, false, overP);
        String pl = Component.translatable(PlanView.on ? "mccitybuilder.plan.on_short" : "mccitybuilder.plan.off_short").getString();
        g.drawString(font, pl, px + (60 - font.width(pl)) / 2, y + 4, VanillaUi.INK, false);
        hits.add(new Hit(px, y, 60, 16, this::togglePlan, Component.translatable("mccitybuilder.plan.tip")));
    }

    /** "Planner · colony name" at the top, so planner mode is obvious. */
    private void banner(GuiGraphics g, IColonyView colony) {
        String t = Component.translatable("mccitybuilder.banner", colony.getName()).getString();
        int w = font.width(t) + 16, x = (width - w) / 2;
        panels.add(new int[]{x, 2, w, 16});
        VanillaUi.panel(g, x, 2, w, 16);
        g.drawString(font, t, x + 8, 6, VanillaUi.LABEL, false);
    }

    private boolean overPanel(double mx, double my) {
        for (int[] p : panels) if (mx >= p[0] && mx < p[0] + p[2] && my >= p[1] && my < p[1] + p[3]) return true;
        return false;
    }

    // ---- palette ----

    /** @return the category picker's ids: creative starts with "all" (every hut in one grid), survival as before */
    private static List<String> categories() {
        List<String> out = new ArrayList<>();
        if (Planner.creative()) out.add("all");
        out.addAll(HutCatalog.CATEGORIES);
        // Then the style pack's decoration folders, each once its blueprints have loaded and it has any.
        for (String f : Blueprints.decoFolders(pack())) {
            List<Blueprints.Hut> d = Blueprints.decorations(pack(), f);
            if (d != null && !d.isEmpty()) out.add("deco:" + f);
        }
        return out;
    }

    /** @return the decoration folder of the chosen category, or null for a hut category */
    private static String decoFolder() {
        return category().startsWith("deco:") ? category().substring(5) : null;
    }

    /** @return the chosen decoration folder's decorations matching the search */
    private static List<Blueprints.Hut> decos() {
        List<Blueprints.Hut> all = Blueprints.decorations(pack(), decoFolder());
        if (all == null) return List.of();
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        return q.isEmpty() ? all : all.stream().filter(h -> h.blueprint().getFileName().toLowerCase(java.util.Locale.ROOT).contains(q)).toList();
    }

    private static final int DECO_ROWS = 12, DECO_ROW_H = 11;
    private static int decoScroll;
    private int[] paletteRect = new int[4];

    /** @return the chosen category, or the first one when none is chosen yet (or it is creative-only in survival) */
    private static String category() {
        if (category == null || !categories().contains(category)) category = categories().get(0);
        return category;
    }

    /** @return palette huts: the category's, or with a search every hut whose name contains it */
    private static List<Block> huts() {
        List<Block> out = new ArrayList<>();
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        for (AbstractColonyBlock<?> b : ModBlocks.getHuts()) {
            if (!(b instanceof AbstractBlockHut<?>)) continue;
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (HutCatalog.hidden(path)) continue;
            if (q.isEmpty() ? (category().equals("all") || HutCatalog.category(path).equals(category())) : b.getName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) out.add(b);
        }
        out.sort(Comparator.comparing(b -> b.getName().getString()));
        return out;
    }

    // Palette search text and whether the field has the keyboard.
    private static String search = "";
    private static boolean searchFocused;

    /** @return true while the palette search has the keyboard (camera keys then do nothing) */
    static boolean typing() {
        return searchFocused;
    }
    private static final int COST_ROWS = 12;

    private void palette(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        boolean decoMode = decoFolder() != null;
        List<Block> huts = decoMode ? List.of() : huts();
        List<Blueprints.Hut> decos = decoMode ? decos() : List.of();
        List<Block> recent = Planner.recent;
        int rows = Math.max(1, (huts.size() + COLS - 1) / COLS);
        decoScroll = Math.max(0, Math.min(decoScroll, decos.size() - DECO_ROWS));
        int gridH = decoMode ? Math.max(1, Math.min(decos.size(), DECO_ROWS)) * DECO_ROW_H : rows * SLOT;
        Map<String, Integer> need = Planner.placingHut != null ? Blueprints.cost(Planner.placingHut) : null;
        // The placing block; creative adds a level picker row.
        int placingH = !Planner.isPlacing() ? 12 : 62 + (Planner.creative() && Planner.placing != null ? 20 : 0);
        int fixedH = PAD + 10 + 16 + 4 + 20 + 20 + (recent.isEmpty() ? 0 : 10 + SLOT + 4) + gridH + 6 + placingH + 12 + 10 + PAD;
        // As many material rows as fit on screen (at least 3, at most 12).
        int costRows = Math.max(3, Math.min(COST_ROWS, (height - 8 - fixedH) / 10));
        com.volcanicmg.mccitybuilder.planner.Costs.Summary cost = need == null ? null
                : com.volcanicmg.mccitybuilder.planner.Costs.summarize(need, inventory(), costRows);
        int costH = !Planner.isPlacing() ? 0 : 12 + (cost == null ? 10 : cost.rows().size() * 10 + (cost.more() > 0 ? 10 : 0));
        int x = 4, y = 4, w = PALETTE_W;
        int h = PAD + 10 + 16 + 4 + 20 + 20 + (recent.isEmpty() ? 0 : 10 + SLOT + 4) + gridH + 6 + placingH + costH + PAD;
        panels.add(new int[]{x, y, w, h});
        paletteRect = new int[]{x, y, w, h};
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, yy = y + PAD, iw = w - 2 * PAD;
        g.drawString(font, Component.translatable("mccitybuilder.huts"), ix + 1, yy, VanillaUi.LABEL, false);
        yy += 10;
        // Search field (click to type; Esc clears it).
        VanillaUi.textField(g, ix, yy, iw, 16);
        String caret = searchFocused && System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "";
        boolean empty = search.isEmpty();
        g.drawString(font, empty && !searchFocused ? Component.translatable("mccitybuilder.search").getString() : search + caret, ix + 4, yy + 4,
                empty && !searchFocused ? 0xFF707070 : 0xFFE0E0E0, true);
        int fy = yy;
        hits.add(new Hit(ix, fy, iw, 16, () -> searchFocused = true, null));
        yy += 20;
        List<String> packs = Blueprints.packs();
        String cur = pack();
        yy = picker(g, ix, yy, iw, cur.isEmpty() ? "-" : cur, mx, my, d -> {
            if (packs.isEmpty()) return;
            int i = Math.max(0, packs.indexOf(pack()));
            pickPack(packs.get(Math.floorMod(i + d, packs.size())));
            if (Planner.placing != null) Planner.startPlacing(Planner.placing);
            else if (Planner.deco != null) Planner.stopPlacing();
            click();
        }, Component.translatable("mccitybuilder.pack"));
        String catLabel = !search.isEmpty() ? Component.translatable("mccitybuilder.search_results", decoMode ? decos.size() : huts.size()).getString()
                : decoMode ? Component.translatable("mccitybuilder.category.deco", decoFolder()).getString()
                : Component.translatable("mccitybuilder.category." + category()).getString();
        yy = picker(g, ix, yy, iw, catLabel, mx, my, d -> {
            List<String> cats = categories();
            category = cats.get(Math.floorMod(cats.indexOf(category()) + d, cats.size()));
            search = "";
            decoScroll = 0;
            click();
        }, Component.translatable("mccitybuilder.category"));
        if (!recent.isEmpty()) {
            g.drawString(font, Component.translatable("mccitybuilder.recent"), ix + 1, yy, VanillaUi.LABEL, false);
            yy += 10;
            for (int i = 0; i < recent.size(); i++) hutSlot(g, recent.get(i), ix + i * SLOT, yy, mx, my);
            yy += SLOT + 4;
        }
        if (decoMode) {
            // Decorations: a scrollable list of names (wheel over the palette).
            for (int i = 0; i < Math.min(DECO_ROWS, decos.size() - decoScroll); i++) {
                Blueprints.Hut d = decos.get(decoScroll + i);
                int ry = yy + i * DECO_ROW_H;
                boolean sel = Planner.deco != null && Planner.deco.path().equals(d.path()), over = mx >= ix && mx < ix + iw && my >= ry && my < ry + DECO_ROW_H;
                if (sel || over) g.fill(ix, ry, ix + iw, ry + DECO_ROW_H, sel ? 0x60FFFFFF : 0x30FFFFFF);
                g.drawString(font, font.plainSubstrByWidth(d.blueprint().getFileName(), iw - 4), ix + 2, ry + 2, VanillaUi.LABEL, false);
                hits.add(new Hit(ix, ry, iw, DECO_ROW_H, () -> {
                    click();
                    if (sel) Planner.stopPlacing();
                    else Planner.startPlacingDeco(d);
                }, Component.literal(d.path())));
            }
            if (decos.size() > DECO_ROWS)
                g.drawString(font, (decoScroll + 1) + "-" + Math.min(decos.size(), decoScroll + DECO_ROWS) + "/" + decos.size(), ix + iw - 40, yy - 9, 0xFF606060, false);
        } else for (int i = 0; i < huts.size(); i++) hutSlot(g, huts.get(i), ix + i % COLS * SLOT, yy + i / COLS * SLOT, mx, my);
        if (decoMode ? decos.isEmpty() : huts.isEmpty()) g.drawString(font, Component.translatable("mccitybuilder.no_match"), ix + 1, yy + 2, 0xFF606060, false);
        yy += gridH + 6;
        if (!Planner.isPlacing()) {
            g.drawString(font, Component.translatable(decoMode ? "mccitybuilder.pick_deco" : "mccitybuilder.pick_hut"), ix + 1, yy, VanillaUi.LABEL, false);
            return;
        }
        // What is being placed, its state, and its materials.
        g.drawString(font, font.plainSubstrByWidth(Planner.placingName(), iw), ix + 1, yy, VanillaUi.LABEL, false);
        String reason = Planner.invalidReason;
        Component line = reason != null ? Component.translatable("mccitybuilder.invalid." + reason)
                : Component.translatable(Planner.adjusting() ? "mccitybuilder.adjust_then_place" : "mccitybuilder.click_to_drop");
        if (Planner.placing != null && Blueprints.loading(pack(), Planner.placing)) line = Component.translatable("mccitybuilder.invalid.loading");
        else if (Planner.placing != null && Blueprints.missing(pack(), Planner.placing)) line = Component.translatable("mccitybuilder.no_blueprint");
        g.drawString(font, font.plainSubstrByWidth(line.getString(), iw), ix + 1, yy + 11, reason == null ? 0xFF206020 : 0xFFA02020, false);
        g.drawString(font, Component.translatable(Planner.rotation.isMirrored() ? "mccitybuilder.rotation_mirrored" : "mccitybuilder.rotation",
                degrees(Planner.rotation.rotation())), ix + 1, yy + 22, VanillaUi.LABEL, false);
        // Height relative to the ground, with ▲ ▼ buttons.
        String off = Planner.place.offset == 0 ? Component.translatable("mccitybuilder.height_ground").getString() : Planner.place.offsetLabel();
        g.drawString(font, Component.translatable("mccitybuilder.height", off), ix + 1, yy + 37, VanillaUi.LABEL, false);
        iconButton(g, "up", ix + iw - 40, yy + 32, mx, my, () -> Planner.raise(1), Component.translatable("mccitybuilder.raise"));
        iconButton(g, "down", ix + iw - 20, yy + 32, mx, my, () -> Planner.raise(-1), Component.translatable("mccitybuilder.lower"));
        if (Planner.creative()) g.drawString(font, Component.translatable("mccitybuilder.creative_instant"), ix + 1, yy + 50, 0xFF2050A0, false);
        else if (Planner.deco != null) g.drawString(font, Component.translatable("mccitybuilder.deco_survival"), ix + 1, yy + 50, 0xFF2050A0, false);
        if (Planner.creative() && Planner.placing != null) {
            // Level picker: 1 .. the highest level the style pack has a blueprint for.
            Blueprints.Hut one = Blueprints.get(pack(), Planner.placing);
            int max = one == null ? 1 : Blueprints.maxLevel(one);
            picker(g, ix, yy + 62, iw, Component.translatable("mccitybuilder.paste_level", Planner.placingLevel, max).getString(), mx, my, d -> {
                Planner.setPlacingLevel(Math.floorMod(Planner.placingLevel - 1 + d, max) + 1);
                click();
            }, Component.translatable("mccitybuilder.paste_level.tip"));
        }
        yy += Planner.creative() && Planner.placing != null ? 82 : 62;
        g.drawString(font, Component.translatable("mccitybuilder.materials"), ix + 1, yy, VanillaUi.LABEL, false);
        yy += 12;
        if (cost == null) {
            g.drawString(font, Component.translatable("mccitybuilder.invalid.loading"), ix + 1, yy, 0xFF606060, false);
            return;
        }
        for (com.volcanicmg.mccitybuilder.planner.Costs.Row r : cost.rows()) {
            var item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(r.item()));
            int c = switch (r.status()) {
                case ENOUGH -> 0xFF206020;
                case PARTIAL -> 0xFF806000;
                case NONE -> 0xFFA02020;
            };
            String count = Math.min(r.have(), r.need()) + "/" + r.need();
            g.drawString(font, count, ix + 1, yy, c, false);
            g.drawString(font, font.plainSubstrByWidth(new ItemStack(item).getHoverName().getString(), iw - 46), ix + 46, yy, VanillaUi.LABEL, false);
            yy += 10;
        }
        if (cost.more() > 0) g.drawString(font, Component.translatable("mccitybuilder.more_items", cost.more()), ix + 1, yy, 0xFF606060, false);
    }

    /** @return item id → count in the player's inventory */
    private static Map<String, Integer> inventory() {
        Map<String, Integer> out = new java.util.HashMap<>();
        var inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
        }
        return out;
    }

    /**
     * One hut in an inventory slot. Survival: greyed with none in the inventory or while research still locks it.
     * Creative: no restrictions; greyed only once the style pack turns out to have no blueprint for it.
     */
    private void hutSlot(GuiGraphics g, Block hut, int sx, int sy, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        boolean creative = mc.player.isCreative();
        VanillaUi.slot(g, sx, sy, SLOT, SLOT);
        int count = mc.player.getInventory().countItem(hut.asItem());
        boolean locked = !creative && hut instanceof AbstractBlockHut<?> h && !h.areRequirementsMet(mc.level, mc.player.blockPosition(), mc.player);
        boolean missing = creative && Blueprints.missing(pack(), hut);
        g.renderItem(new ItemStack(hut), sx + 1, sy + 1);
        if (count > 0) g.renderItemDecorations(font, new ItemStack(hut, count), sx + 1, sy + 1);
        if (locked || missing || count <= 0 && !creative) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, locked ? 0xB0555555 : 0x908B8B8B);
        if (hut == Planner.placing) outline(g, sx + 1, sy + 1, SLOT - 2, SLOT - 2, 0xFFFFFFFF);
        if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x80FFFFFF);
        net.minecraft.network.chat.MutableComponent tip = Component.empty().append(hut.getName());
        if (locked) {
            tip.append(Component.literal("\n")).append(Component.translatable("mccitybuilder.requires_research").withStyle(net.minecraft.ChatFormatting.RED));
            for (var req : ((AbstractBlockHut<?>) hut).getRequirements(mc.level, mc.player.blockPosition(), mc.player))
                tip.append(Component.literal("\n")).append(req);
        } else if (creative) tip.append(Component.literal("\n")).append(Component.translatable(missing ? "mccitybuilder.no_blueprint" : "mccitybuilder.creative"));
        else tip.append(Component.literal("\n")).append(count > 0 ? Component.translatable("mccitybuilder.in_inventory", count)
                : Component.translatable("mccitybuilder.none_in_inventory"));
        hits.add(new Hit(sx, sy, SLOT, SLOT, () -> {
            if (locked) return;
            click();
            if (Planner.placing == hut) Planner.stopPlacing();
            else Planner.startPlacing(hut);
        }, tip));
    }

    private static int degrees(net.minecraft.world.level.block.Rotation r) {
        return switch (r) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
        };
    }

    /** A 20x20 vanilla button with a 16x16 {@link ActionIcons} icon, 1 texel = 1 GUI pixel. */
    private void iconButton(GuiGraphics g, String icon, int x, int y, int mx, int my, Runnable run, Component tip) {
        boolean over = mx >= x && mx < x + 20 && my >= y && my < y + 20;
        VanillaUi.button(g, x, y, 20, 20, false, false, over);
        for (int r = 0; r < ActionIcons.SIZE; r++)
            for (int c = 0; c < ActionIcons.SIZE; c++) {
                int col = ActionIcons.color(icon, c, r);
                if (col != 0) g.fill(x + 2 + c, y + 2 + r, x + 3 + c, y + 3 + r, col);
            }
        hits.add(new Hit(x, y, 20, 20, () -> {
            click();
            run.run();
        }, tip));
    }

    /** ADJUST: a floating bar of vanilla buttons under the dropped ghost: place, rotate, mirror, raise, lower, cancel. */
    private void actionBar(GuiGraphics g, int mx, int my) {
        BlockPos at = Planner.anchor();
        if (!Planner.adjusting() || at == null) return;
        double[] ndc = Planner.project(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        int n = 6, w = n * 22 + 6, h = 28;
        int x = ndc == null ? (width - w) / 2 : (int) Math.round((ndc[0] + 1) / 2 * width) - w / 2;
        int y = ndc == null ? height - 80 : (int) Math.round((1 - ndc[1]) / 2 * height) + 16;
        x = Math.max(4, Math.min(width - w - 4, x));
        y = Math.max(24, Math.min(height - h - 4, y));
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        String[] icons = {"place", "rotate", "mirror", "up", "down", "cancel"};
        String[] tips = {"mccitybuilder.action.place", "mccitybuilder.action.rotate", "mccitybuilder.action.mirror", "mccitybuilder.raise",
                "mccitybuilder.lower", "mccitybuilder.action.cancel"};
        Runnable[] run = {this::place, Planner::rotatePlacing, Planner::mirrorPlacing, () -> Planner.raise(1), () -> Planner.raise(-1), Planner::back};
        for (int i = 0; i < n; i++) iconButton(g, icons[i], x + 4 + i * 22, y + 4, mx, my, run[i], Component.translatable(tips[i]));
    }

    /** Place / Enter: sends the placement with feedback. */
    private void place() {
        if (Planner.confirm()) {
            toast(Component.translatable(Planner.creative() ? "mccitybuilder.placed_creative" : "mccitybuilder.placed"));
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.ANVIL_PLACE, 1.2f, 0.35f));
        } else if (Planner.invalidReason != null) toast(Component.translatable("mccitybuilder.invalid." + Planner.invalidReason));
    }

    static void click() {
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    /** A "[<] label [>]" row; {@code step} gets -1 or +1. @return the y below it */
    private int picker(GuiGraphics g, int x, int y, int w, String label, int mx, int my, java.util.function.IntConsumer step, Component tip) {
        int bw = 16;
        boolean overL = mx >= x && mx < x + bw && my >= y && my < y + 16, overR = mx >= x + w - bw && mx < x + w && my >= y && my < y + 16;
        VanillaUi.button(g, x, y, bw, 16, false, false, overL);
        VanillaUi.button(g, x + w - bw, y, bw, 16, false, false, overR);
        VanillaUi.slot(g, x + bw + 2, y, w - 2 * bw - 4, 16);
        g.drawString(font, "<", x + 5, y + 4, VanillaUi.INK, false);
        g.drawString(font, ">", x + w - bw + 6, y + 4, VanillaUi.INK, false);
        String l = font.plainSubstrByWidth(label, w - 2 * bw - 10);
        g.drawString(font, l, x + (w - font.width(l)) / 2, y + 4, 0xFFFFFFFF, true);
        hits.add(new Hit(x, y, bw, 16, () -> step.accept(-1), tip));
        hits.add(new Hit(x + w - bw, y, bw, 16, () -> step.accept(1), tip));
        return y + 20;
    }

    // ---- queue ----

    private void queue(GuiGraphics g, IColonyView colony, int mx, int my) {
        List<IWorkOrderView> orders = new ArrayList<>(colony.getWorkOrders());
        List<QueueOrder.Entry> entries = new ArrayList<>();
        for (IWorkOrderView o : orders) entries.add(new QueueOrder.Entry(o.getID(), o.getPriority()));
        List<IWorkOrderView> sorted = new ArrayList<>();
        for (QueueOrder.Entry e : QueueOrder.sorted(entries)) sorted.add(colony.getWorkOrder(e.id()));
        sorted.removeIf(java.util.Objects::isNull);
        shownOrders = sorted;
        int x = width - 4 - QUEUE_W, y = 4, maxRows = Math.max(1, (height / 2 - 30) / ROW_H);
        queueScroll = Math.max(0, Math.min(queueScroll, Math.max(0, sorted.size() - maxRows)));
        int rows = Math.min(maxRows, Math.max(1, sorted.size()));
        int h = PAD + 12 + rows * ROW_H + PAD;
        queueBottom = y + h;
        panels.add(new int[]{x, y, QUEUE_W, h});
        VanillaUi.panel(g, x, y, QUEUE_W, h);
        g.drawString(font, Component.translatable("mccitybuilder.queue", sorted.size()), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        queueTop = y + PAD + 12;
        if (sorted.isEmpty()) {
            g.drawString(font, Component.translatable("mccitybuilder.queue_empty"), x + PAD + 1, queueTop + 6, 0xFF606060, false);
            return;
        }
        List<IBuildingView> builders = builders(colony);
        for (int r = 0; r < rows && queueScroll + r < sorted.size(); r++) {
            int idx = queueScroll + r;
            IWorkOrderView o = sorted.get(idx);
            int ry = queueTop + r * ROW_H, rx = x + PAD, rw = QUEUE_W - 2 * PAD;
            VanillaUi.slot(g, rx, ry, rw, ROW_H - 2);
            if (dragFrom >= 0 && idx == dragOver) g.fill(rx + 1, ry + 1, rx + rw - 1, ry + 2, 0xFFFFFFFF);
            if (idx == dragFrom) g.fill(rx + 1, ry + 1, rx + rw - 1, ry + ROW_H - 3, 0x40FFFFFF);
            String kind = o instanceof com.minecolonies.core.colony.workorders.view.WorkOrderDecorationView ? "decoration"
                    : o.getWorkOrderType().name().toLowerCase();
            icon(g, kind, rx + 3, ry + 3, 0xFFFFFFFF);
            String type = Component.translatable("mccitybuilder.wo." + kind).getString();
            String title = type + ": " + o.getDisplayName().getString();
            g.drawString(font, font.plainSubstrByWidth(title, rw - 58), rx + 15, ry + 3, 0xFFFFFFFF, true);
            String builder = builderName(colony, o);
            String detail = Component.translatable("mccitybuilder.wo.levels", o.getCurrentLevel(), o.getTargetLevel()).getString() + "  " + builder;
            g.drawString(font, font.plainSubstrByWidth(detail, rw - 46), rx + 3, ry + 12, 0xFFE0E0E0, false);
            // Progress: the builder's stage (the client view has no finer progress).
            double frac = o.isClaimed() && o.getStage() != null ? QueueOrder.stageFraction(o.getStage().name()) : -1;
            if (frac >= 0) {
                g.fill(rx + 1, ry + ROW_H - 4, rx + rw - 1, ry + ROW_H - 3, 0xFF373737);
                g.fill(rx + 1, ry + ROW_H - 4, rx + 1 + (int) ((rw - 2) * frac), ry + ROW_H - 3, 0xFF55FF55);
            }
            // Assign builder (cycles through the colony's builders) and cancel.
            int bx = rx + rw - 40;
            boolean overB = mx >= bx && mx < bx + 18 && my >= ry + 2 && my < ry + 20, overX = mx >= bx + 20 && mx < bx + 38 && my >= ry + 2 && my < ry + 20;
            VanillaUi.button(g, bx, ry + 2, 18, 18, false, builders.isEmpty(), overB);
            VanillaUi.button(g, bx + 20, ry + 2, 18, 18, false, false, overX);
            g.drawString(font, "B", bx + 6, ry + 7, builders.isEmpty() ? VanillaUi.INK_DISABLED : VanillaUi.INK, false);
            g.drawString(font, "x", bx + 26, ry + 6, VanillaUi.INK, false);
            hits.add(new Hit(bx, ry + 2, 18, 18, () -> {
                click();
                assignNext(colony, o, builders);
            }, Component.translatable("mccitybuilder.assign")));
            hits.add(new Hit(bx + 20, ry + 2, 18, 18, () -> {
                click();
                cancel(colony, o);
            }, Component.translatable("mccitybuilder.cancel_order")));
        }
    }

    /** A {@link QueueIcons} 9x9 pixel icon, 1 texel = 1 GUI pixel. */
    private static void icon(GuiGraphics g, String name, int x, int y, int color) {
        for (int r = 0; r < QueueIcons.SIZE; r++)
            for (int c = 0; c < QueueIcons.SIZE; c++) if (QueueIcons.ink(name, c, r)) g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
    }

    private static List<IBuildingView> builders(IColonyView colony) {
        List<IBuildingView> out = new ArrayList<>();
        for (IBuildingView b : colony.getClientBuildingManager().getBuildings().values())
            if (b instanceof BuildingBuilder.View) out.add(b);
        out.sort(Comparator.comparing(b -> b.getID().asLong()));
        return out;
    }

    private static String builderName(IColonyView colony, IWorkOrderView o) {
        if (!o.isClaimed()) return Component.translatable("mccitybuilder.unassigned").getString();
        IBuildingView b = colony.getClientBuildingManager().getBuilding(o.getClaimedBy());
        String stage = o.getStage() == null ? "" : " · " + o.getStage().name().toLowerCase();
        if (b instanceof AbstractBuildingBuilderView bv) {
            String n = bv.getWorkerName();
            return (n == null || n.isEmpty() ? Component.translatable("mccitybuilder.builder").getString() : n) + stage;
        }
        return Component.translatable("mccitybuilder.builder").getString() + stage;
    }

    private static IBuildingView townHall(IColonyView colony) {
        return colony.getClientBuildingManager().getTownHall();
    }

    private void assignNext(IColonyView colony, IWorkOrderView o, List<IBuildingView> builders) {
        if (builders.isEmpty()) return;
        int i = -1;
        for (int k = 0; k < builders.size(); k++) if (o.isClaimed() && builders.get(k).getID().equals(o.getClaimedBy())) i = k;
        IBuildingView next = builders.get((i + 1) % builders.size());
        new BuilderSelectWorkOrderMessage(next, o.getID()).sendToServer();
        toast(Component.translatable("mccitybuilder.assigned", next.getID().toShortString()));
    }

    private void cancel(IColonyView colony, IWorkOrderView o) {
        IBuildingView th = townHall(colony);
        if (th == null) return;
        new WorkOrderChangeMessage(th, o.getID(), true, 0).sendToServer();
    }

    /** Sends the priorities that put the dragged work order at its new place. */
    private void reorder(IColonyView colony, int from, int to) {
        IBuildingView th = townHall(colony);
        if (th == null || from == to) return;
        List<QueueOrder.Entry> displayed = new ArrayList<>();
        for (IWorkOrderView o : shownOrders) displayed.add(new QueueOrder.Entry(o.getID(), o.getPriority()));
        for (Map.Entry<Integer, Integer> c : QueueOrder.move(displayed, from, to).entrySet())
            new WorkOrderChangeMessage(th, c.getKey(), false, c.getValue()).sendToServer();
    }

    private int queueRowAt(double mx, double my) {
        int x = width - 4 - QUEUE_W + PAD;
        if (mx < x || mx >= x + QUEUE_W - 2 * PAD - 42 || my < queueTop) return -1;
        int idx = queueScroll + (int) ((my - queueTop) / ROW_H);
        return idx < shownOrders.size() ? idx : -1;
    }

    // ---- selection ----

    private void selection(GuiGraphics g, int mx, int my) {
        IBuildingView b = Planner.selected;
        if (b == null) return;
        // Bottom, right of the palette (which can run tall).
        // Creative with the server part adds an instant Clean (placeholders) button; it pastes over pending work orders too.
        boolean instant = Planner.creative() && Planner.serverInstant();
        int n = instant ? 4 : 3, w = instant ? PALETTE_W + 44 : PALETTE_W, h = 58, x = 4 + PALETTE_W + 4, y = height - h - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        g.drawString(font, font.plainSubstrByWidth(name(b) + "  " + levelText(b), w - 2 * PAD), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        String info = Component.translatable("mccitybuilder.workers", b.getAllAssignedCitizens().size()).getString()
                + (b.hasWorkOrder() ? "  " + Component.translatable("mccitybuilder.has_work_order").getString() : "");
        g.drawString(font, font.plainSubstrByWidth(info, w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        String[] keys = {"upgrade", "repair", "open", "clean"};
        // Open uses the client-side building view, so it works however buried or far the hut block is.
        Runnable[] run = {() -> upgrade(b), () -> repair(b), () -> b.openGui(false), () -> {
            if (Planner.creativeClean(b)) toast(Component.translatable("mccitybuilder.clean_sent"));
        }};
        // Why a button is off, or null when it is on.
        boolean pending = b.hasWorkOrder() && !instant;
        String[] why = {b.isBuildingMaxLevel() ? "mccitybuilder.upgrade.max" : pending ? "mccitybuilder.upgrade.pending" : null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : pending ? "mccitybuilder.upgrade.pending" : null, null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : null};
        int bw = (w - 2 * PAD - 2 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            int bx = x + PAD + i * (bw + 2), by = y + PAD + 24;
            boolean off = why[i] != null, over = mx >= bx && mx < bx + bw && my >= by && my < by + 20;
            VanillaUi.button(g, bx, by, bw, 20, false, off, over);
            String l = Component.translatable("mccitybuilder." + keys[i]).getString();
            g.drawString(font, l, bx + (bw - font.width(l)) / 2, by + 6, off ? VanillaUi.INK_DISABLED : VanillaUi.INK, false);
            int fi = i;
            hits.add(new Hit(bx, by, bw, 20, () -> {
                if (off) return;
                click();
                run[fi].run();
            }, Component.translatable(off ? why[i] : "mccitybuilder." + keys[i] + ".tip")));
        }
    }

    /** Upgrade: creative pastes at once (any level with CityBuilder on the server, else only level 0 → 1); otherwise a work order. */
    private void upgrade(IBuildingView b) {
        boolean pasted = Planner.creative() && Planner.creativeUpgrade(b);
        if (!pasted) Planner.requestBuild(b, BuildRequestMessage.Mode.BUILD);
        toast(Component.translatable(pasted ? "mccitybuilder.upgraded_creative" : "mccitybuilder.requested"));
    }

    /** Repair: creative with CityBuilder on the server repairs at once; otherwise a builder work order. */
    private void repair(IBuildingView b) {
        boolean pasted = Planner.creative() && Planner.creativeRepair(b);
        if (!pasted) Planner.requestBuild(b, BuildRequestMessage.Mode.REPAIR);
        toast(Component.translatable(pasted ? "mccitybuilder.repaired_creative" : "mccitybuilder.requested"));
    }

    private static String name(IBuildingView b) {
        String custom = b.getCustomName();
        return custom != null && !custom.isEmpty() ? custom : Component.translatable(b.getBuildingDisplayName()).getString();
    }

    private static String levelText(IBuildingView b) {
        return Component.translatable("mccitybuilder.level", b.getBuildingLevel(), b.getBuildingMaxLevel()).getString();
    }

    // ---- controls guide ----

    private void guide(GuiGraphics g, int mx, int my) {
        int w = Hints.WIDTH, x = width - 4 - w;
        boolean open = Config.SHOW_HINTS.get();
        String state = Planner.adjusting() ? "adjusting" : Planner.isPlacing() ? "placing" : LineView.active() ? "line" : PlanView.hasSelection() ? "planned"
                : Planner.selected != null ? "selected" : "browse";
        // The camera keys only while nothing else is going on; the guide must stay below the queue panel.
        List<String> ids = state.equals("browse") ? List.of(state, "view") : List.of(state);
        Hints.Laid laid = open ? Hints.layout(ids, PlannerScreen::hintText, font::width) : null;
        int room = height - 8 - queueBottom;
        if (open && Hints.PAD + 20 + laid.height() + Hints.PAD > room) laid = Hints.layout(List.of(state), PlannerScreen::hintText, font::width);
        if (open && Hints.PAD + 20 + laid.height() + Hints.PAD > room) open = false;
        int h = open ? Hints.PAD + 20 + laid.height() + Hints.PAD : 20;
        int y = height - 4 - h;
        int bx = open ? x + w - 4 - 18 : width - 4 - 20, by = open ? y + 4 : y, bs = open ? 18 : 20;
        if (open) {
            panels.add(new int[]{x, y, w, h});
            VanillaUi.panel(g, x, y, w, h);
            g.drawString(font, Component.translatable("mccitybuilder.hint.title"), x + Hints.PAD + 1, y + Hints.PAD + 2, VanillaUi.LABEL, false);
            int cx = x + Hints.PAD, cy = y + Hints.PAD + 20;
            for (Hints.Item it : laid.items()) {
                if (it.cap()) keyCap(g, cx + it.x(), cy + it.y(), it.w(), it.text());
                else if (it.title()) {
                    g.drawString(font, it.text(), cx + it.x() + 1, cy + it.y(), VanillaUi.LABEL, false);
                    g.fill(cx, cy + it.y() + 9, cx + it.w(), cy + it.y() + 10, VanillaUi.SLOT);
                } else g.drawString(font, it.text(), cx + it.x(), cy + it.y(), 0xFF555555, false);
            }
        } else panels.add(new int[]{bx, by, bs, bs});
        boolean over = mx >= bx && mx < bx + bs && my >= by && my < by + bs;
        VanillaUi.button(g, bx, by, bs, bs, false, false, over);
        g.drawString(font, "?", bx + (bs - font.width("?")) / 2 + 1, by + (bs - 8) / 2, VanillaUi.INK, false);
        hits.add(new Hit(bx, by, bs, bs, () -> {
            Config.SHOW_HINTS.set(!Config.SHOW_HINTS.get());
            Config.SHOW_HINTS.save();
        }, Component.translatable("mccitybuilder.hint.toggle")));
    }

    private static String hintText(String key) {
        return key.equals("@toggle") ? PlannerClient.TOGGLE.getTranslatedKeyMessage().getString() : Component.translatable(key).getString();
    }

    private void keyCap(GuiGraphics g, int x, int y, int w, String label) {
        g.fill(x, y, x + w, y + Hints.CAP_H, VanillaUi.SLOT_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + Hints.CAP_H - 1, VanillaUi.PANEL);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, VanillaUi.HIGHLIGHT);
        g.fill(x + 1, y + 1, x + 2, y + Hints.CAP_H - 2, VanillaUi.HIGHLIGHT);
        g.fill(x + 2, y + Hints.CAP_H - 2, x + w - 1, y + Hints.CAP_H - 1, VanillaUi.SLOT);
        g.fill(x + w - 2, y + 2, x + w - 1, y + Hints.CAP_H - 1, VanillaUi.SLOT);
        g.drawString(font, label, x + 3, y + 2, VanillaUi.LABEL, false);
    }

    private void status(GuiGraphics g) {
        if (toast == null || System.currentTimeMillis() > toastUntil) return;
        var rows = font.split(toast, Math.min(400, width - 40));
        int w = 0;
        for (var r : rows) w = Math.max(w, font.width(r));
        g.fill(width / 2 - w / 2 - 4, 6, width / 2 + w / 2 + 4, 10 + rows.size() * 10, 0xE0202020);
        for (int i = 0; i < rows.size(); i++) g.drawString(font, rows.get(i), width / 2 - w / 2, 9 + i * 10, 0xFFFFFFFF, false);
    }

    private void toast(Component c) {
        toast = c;
        toastUntil = System.currentTimeMillis() + Math.min(8000, 2500 + 30L * c.getString().length());
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    // ---- input ----

    private boolean clickedImpl(double mx, double my, int button) {
        searchFocused = false;
        if (button == 0)
            for (Hit h : hits)
                if (h.in(mx, my)) {
                    h.action().run();
                    return true;
                }
        if (button == 0 && queueRowAt(mx, my) >= 0) {
            dragFrom = dragOver = queueRowAt(mx, my);
            return true;
        }
        if (overPanel(mx, my)) return true;
        int[] mk = button == 0 ? markerAt(mx, my) : null;
        if (mk != null && mk[0] < OverlayView.markers.size()) {
            // Builder badge: select its hut. Worker marker: MineColonies' citizen window (client side).
            OverlayView.Marker m = OverlayView.markers.get(mk[0]);
            click();
            if (m.site() != null) Planner.select(m.site().builder());
            else if (m.citizen() != null) new com.minecolonies.core.client.gui.citizen.MainWindowCitizen(m.citizen()).open();
            return true;
        }
        if (button == 1) {
            rightDown = true;
            rightDragged = false;
            rightX = mx;
            rightY = my;
            return true;
        }
        if (button != 0) return false;
        if (Planner.isPlacing()) {
            // Follow: the first click drops the ghost for fine adjustment. Adjust: a press starts dragging it.
            if (Planner.adjusting()) Planner.startGhostDrag(Planner.pick(mx / width, my / height));
            else if (Planner.drop()) click();
            return true;
        }
        BlockHitResult hit = Planner.pick(mx / width, my / height);
        // Road / wall tool: clicks start a chain or drop the live segment.
        if (LineView.active()) {
            if (hit != null) {
                LineView.click(hit);
                click();
            }
            return true;
        }
        // Plan mode: a click on a planned segment or inside a planned footprint selects it first.
        int pl = PlanView.on && hit != null ? PlanView.lineAt(hit.getBlockPos()) : -1;
        PlanView.selectedLine = pl;
        int pe = PlanView.on && hit != null && pl < 0 ? PlanView.entryAt(hit.getBlockPos()) : -1;
        PlanView.selected = pe;
        if (pl >= 0) {
            Planner.select(null);
            return true;
        }
        Planner.select(pe >= 0 || hit == null ? null : Planner.buildingAt(hit.getBlockPos()));
        return true;
    }

    private boolean draggedImpl(double mx, double my, double dx, double dy) {
        if (Planner.adjusting()) Planner.ghostDrag(Planner.pick(mx / width, my / height));
        if (rightDown) {
            if (!rightDragged && Math.hypot(mx - rightX, my - rightY) >= DRAG_PX) {
                rightDragged = true;
                Planner.startDragTurn();
            }
            if (rightDragged) Planner.dragTurn((float) dx * 0.5f, (float) dy * 0.3f);
            return true;
        }
        if (dragFrom >= 0) {
            int r = queueRowAt(mx, my);
            if (r >= 0) dragOver = r;
            return true;
        }
        return false;
    }

    private boolean releasedImpl(double mx, double my, int button) {
        if (button == 1 && rightDown) {
            rightDown = false;
            if (rightDragged) Planner.endDragTurn();
            else {
                // A plain right-click backs out one step: adjust -> follow -> not placing; ends a road / wall chain; deselect too.
                if (LineView.active()) {
                    if (LineView.start != null) LineView.endChain();
                    else useTool(LineView.tool);
                    rightDown = false;
                    return true;
                }
                if (Planner.isPlacing()) Planner.back();
                Planner.select(null);
                PlanView.selected = -1;
            }
            return true;
        }
        if (button == 0) Planner.endGhostDrag();
        if (button == 0 && dragFrom >= 0) {
            if (dragOver >= 0 && dragOver != dragFrom && Planner.colony() != null) reorder(Planner.colony(), dragFrom, dragOver);
            dragFrom = dragOver = -1;
            return true;
        }
        return false;
    }

    private boolean scrolledImpl(double mx, double my, double sy) {
        if (mx >= width - 4 - QUEUE_W && my < height / 2.0 && overPanel(mx, my)) {
            queueScroll = Math.max(0, queueScroll - (int) Math.signum(sy));
            return true;
        }
        if (decoFolder() != null && mx >= paletteRect[0] && mx < paletteRect[0] + paletteRect[2] && my >= paletteRect[1] && my < paletteRect[1] + paletteRect[3]) {
            decoScroll = Math.max(0, decoScroll - (int) Math.signum(sy));
            return true;
        }
        if (overPanel(mx, my)) return true;
        // Shift+scroll raises / lowers the ghost; plain scroll moves the camera.
        if (Planner.isPlacing() && hasShiftDown()) Planner.raise((int) Math.signum(sy));
        else Planner.zoom(sy, mx / width, my / height);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return Planner.guard(() -> {
            if (!searchFocused) return false;
            if (c >= ' ' && search.length() < 32) search += c;
            return true;
        });
    }

    private boolean keyImpl(int key, int scancode) {
        if (PlannerClient.TOGGLE.matches(key, scancode)) {
            Planner.exit(null);
            return true;
        }
        if (searchFocused || !search.isEmpty() && key == GLFW.GLFW_KEY_ESCAPE) {
            // The search field first: Esc clears it, Backspace edits; nothing else while it has the keyboard.
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                search = "";
                searchFocused = false;
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) search = search.substring(0, search.length() - 1);
            else if (key == GLFW.GLFW_KEY_ENTER) searchFocused = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (Planner.isPlacing()) Planner.back();
            else if (LineView.active() && LineView.start != null) LineView.endChain();
            else if (LineView.active()) useTool(LineView.tool);
            else if (PlanView.selectedLine() != null) PlanView.selectedLine = -1;
            else if (PlanView.hasSelection()) PlanView.selected = -1;
            else if (Planner.selected != null) Planner.select(null);
            else Planner.exit(null);
            return true;
        }
        if (key == GLFW.GLFW_KEY_V) {
            Planner.togglePitch();
            return true;
        }
        if (Planner.isPlacing()) {
            switch (key) {
                case GLFW.GLFW_KEY_R -> Planner.rotatePlacing();
                case GLFW.GLFW_KEY_M -> Planner.mirrorPlacing();
                case GLFW.GLFW_KEY_PAGE_UP -> Planner.raise(1);
                case GLFW.GLFW_KEY_PAGE_DOWN -> Planner.raise(-1);
                case GLFW.GLFW_KEY_G -> Planner.snapToGround();
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (Planner.adjusting()) place();
                }
                case GLFW.GLFW_KEY_UP -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.FORWARD, hasShiftDown());
                case GLFW.GLFW_KEY_DOWN -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.BACK, hasShiftDown());
                case GLFW.GLFW_KEY_LEFT -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.LEFT, hasShiftDown());
                case GLFW.GLFW_KEY_RIGHT -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.RIGHT, hasShiftDown());
                default -> {
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_K) {
            togglePlan();
            return true;
        }
        if (key == GLFW.GLFW_KEY_N || key == GLFW.GLFW_KEY_B) {
            useTool(key == GLFW.GLFW_KEY_N ? "road" : "wall");
            return true;
        }
        if ((LineView.active() || PlanView.selectedLine() != null) && !Planner.isPlacing()) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> LineView.undo();
                case GLFW.GLFW_KEY_DELETE -> PlanView.removeLine(PlanView.selectedLine);
                case GLFW.GLFW_KEY_LEFT_BRACKET -> {
                    int[] li = LineView.levelInfo();
                    LineView.cycleLevel(-1, li[0], li[1] == 1);
                }
                case GLFW.GLFW_KEY_RIGHT_BRACKET -> {
                    int[] li = LineView.levelInfo();
                    LineView.cycleLevel(1, li[0], li[1] == 1);
                }
                case GLFW.GLFW_KEY_MINUS -> LineView.cycleFamily(-1);
                case GLFW.GLFW_KEY_EQUAL -> LineView.cycleFamily(1);
                case GLFW.GLFW_KEY_T -> LineView.toggleTowers();
                case GLFW.GLFW_KEY_G -> LineView.toggleGate();
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (PlanView.hasSelection()) {
            // A planned entry: move (camera-relative), raise / lower, rotate, mirror, level, delete.
            int far = hasShiftDown() ? 5 : 1;
            float yaw = Planner.drone == null ? 0 : Planner.drone.yaw;
            com.volcanicmg.mccitybuilder.planner.PlacementState.Dir dir = switch (key) {
                case GLFW.GLFW_KEY_UP -> com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.FORWARD;
                case GLFW.GLFW_KEY_DOWN -> com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.BACK;
                case GLFW.GLFW_KEY_LEFT -> com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.LEFT;
                case GLFW.GLFW_KEY_RIGHT -> com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.RIGHT;
                default -> null;
            };
            if (dir != null) {
                int[] d = com.volcanicmg.mccitybuilder.planner.PlacementState.step(yaw, dir, far);
                PlanView.move(d[0], 0, d[1]);
            }
            switch (key) {
                case GLFW.GLFW_KEY_PAGE_UP -> PlanView.move(0, 1, 0);
                case GLFW.GLFW_KEY_PAGE_DOWN -> PlanView.move(0, -1, 0);
                case GLFW.GLFW_KEY_R -> PlanView.rotate(false);
                case GLFW.GLFW_KEY_M -> PlanView.rotate(true);
                case GLFW.GLFW_KEY_LEFT_BRACKET -> PlanView.level(-1);
                case GLFW.GLFW_KEY_RIGHT_BRACKET -> PlanView.level(1);
                case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> PlanView.delete();
                default -> {
                }
            }
            return true;
        }
        IBuildingView b = Planner.selected;
        if (b != null) {
            switch (key) {
                case GLFW.GLFW_KEY_U -> {
                    if (!b.isBuildingMaxLevel() && (!b.hasWorkOrder() || Planner.creative() && Planner.serverInstant())) {
                        click();
                        upgrade(b);
                    }
                }
                case GLFW.GLFW_KEY_P -> {
                    if (b.getBuildingLevel() > 0 && (!b.hasWorkOrder() || Planner.creative() && Planner.serverInstant())) {
                        click();
                        repair(b);
                    }
                }
                case GLFW.GLFW_KEY_O -> b.openGui(false);
                default -> {
                }
            }
        }
        return true;
    }
}
