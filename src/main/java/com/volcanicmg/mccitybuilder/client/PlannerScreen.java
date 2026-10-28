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
        // A planner screen left over after the planner ended (it can't exit again): just close.
        if (Planner.active()) Planner.exit(null);
        else minecraft.setScreen(null);
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
        editOverlay(g, mouseX, mouseY);
        actionBar(g, mouseX, mouseY);
        guide(g, mouseX, mouseY);
        status(g);
        boolean overUi = overPanel(mouseX, mouseY);
        // The planned segment under the cursor (not while drawing): highlighted, Del / right-click remove it.
        if (!overUi) {
            LineView.hoverLine = LineView.active() && LineView.start == null && hit != null ? PlanView.lineAt(hit.getBlockPos()) : -1;
            LineView.hoverPiece = LineView.hoverLine >= 0 ? PlanView.pieceAt(LineView.hoverLine, hit.getBlockPos()) : null;
        }
        // Re-picked every frame from the current camera; a miss only clears it after a few frames, so it holds steady.
        Object now = !overUi && !Planner.isPlacing() && hit != null ? Planner.pickAt(hit.getBlockPos()) : null;
        if (now != null || overUi || Planner.isPlacing() || ++hoverMisses > HOVER_HOLD) {
            Planner.hovered = now instanceof IBuildingView b ? b : null;
            Planner.hoveredDeco = now instanceof Planner.Deco d ? d : null;
            hoverMisses = 0;
        }
        decoPreview(g);
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
        else if (Planner.hoveredDeco != null) g.renderTooltip(font, Component.literal(Planner.hoveredDeco.name()), mouseX, mouseY);
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
        int icons = 0;
        for (int i = 0; i < ms.size(); i++) {
            OverlayView.Marker m = ms.get(i);
            double[] ndc = Planner.project(m.pos().x, m.pos().y, m.pos().z);
            if (ndc == null || Math.abs(ndc[0]) > 1.05 || Math.abs(ndc[1]) > 1.05) continue;
            int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height);
            if (m.site() != null) {
                // A builder: its hammer badge, or the tool it actually holds.
                g.fill(sx - 7, sy - 7, sx + 7, sy + 7, 0xFF000000);
                g.fill(sx - 6, sy - 6, sx + 6, sy + 6, m.color());
                if (!m.held().isEmpty() && icons < MAX_JOB_ICONS) {
                    icons++;
                    miniItem(g, m.held(), sx - 5, sy - 5, 0.625f);
                } else icon(g, "build", sx - 4, sy - 4, 0xFF202020);
                icons += armourBadge(g, m, sx, sy, icons);
            } else if (m.mainIcon() != null && icons < MAX_JOB_ICONS) {
                // A worker: what it holds (else its hut's item) on a dark rounded backing with a job-coloured ring, ~10 px.
                icons++;
                g.fill(sx - 7, sy - 6, sx + 7, sy + 6, m.color());
                g.fill(sx - 6, sy - 7, sx + 6, sy + 7, m.color());
                g.fill(sx - 6, sy - 5, sx + 6, sy + 5, 0xFF202020);
                g.fill(sx - 5, sy - 6, sx + 5, sy + 6, 0xFF202020);
                miniItem(g, m.mainIcon(), sx - 5, sy - 5, 0.625f);
                icons += armourBadge(g, m, sx, sy, icons);
            } else {
                g.fill(sx - 4, sy - 4, sx + 4, sy + 4, 0xFF000000);
                g.fill(sx - 3, sy - 3, sx + 3, sy + 3, m.color());
            }
            drawnMarkers.add(new int[]{i, sx, sy});
        }
    }

    // Item icons drawn per frame at most (each is a model render); armour badges count too.
    private static final int MAX_JOB_ICONS = 96;

    /** An item drawn at {@code scale} of its 16 px size with its top-left at (x, y). */
    private void miniItem(GuiGraphics g, net.minecraft.world.item.ItemStack st, int x, int y, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(st, 0, 0);
        g.pose().popPose();
    }

    /** The armour badge at a marker's bottom-right corner: the best piece worn as a ~7 px item. @return icons used (0 or 1) */
    private int armourBadge(GuiGraphics g, OverlayView.Marker m, int sx, int sy, int icons) {
        if (m.armour().isEmpty() || icons >= MAX_JOB_ICONS) return 0;
        g.fill(sx + 2, sy + 2, sx + 10, sy + 10, 0xFF202020);
        miniItem(g, m.armour(), sx + 2, sy + 2, 0.45f);
        return 1;
    }

    private static String jointHint(LineView.JointOption o) {
        int pct = (int) Math.round(o.match() * 100);
        return o.type() == com.volcanicmg.mccitybuilder.planner.Plan.J_NONE ? "" : o.lacks() > 0 ? Component.translatable("mccitybuilder.edit.menu_no_fit", o.lacks()).getString()
                : Component.translatable(o.flips() > 0 ? "mccitybuilder.edit.menu_fits_flip" : "mccitybuilder.edit.menu_fits_pct", pct, o.flips()).getString();
    }

    /**
     * The joint menu beside the selected joint: one row per type that fits its shape (a 3D thumbnail of the piece,
     * the name, "fits" / "gap N" / "won't fit"); hovering a row shows it in the world, a click applies it, Esc or a
     * click elsewhere closes it (C still cycles).
     */
    private void jointMenu(GuiGraphics g, int mx, int my) {
        if (!LineView.jointMenu || LineView.selectedJoint == null || LineView.jointOptions.isEmpty()) return;
        double[] p = screen(LineView.selectedJoint[0], LineView.selectedJoint[1], 1.5);
        if (p == null) {
            LineView.closeJointMenu(false);
            return;
        }
        // Wide enough for the longest row text.
        int rowH = 28, w = 160, h = LineView.jointOptions.size() * rowH + 2 * PAD;
        for (LineView.JointOption o : LineView.jointOptions) w = Math.max(w, font.width(jointHint(o)) + 30 + 2 * PAD + 4);
        int x = Math.min(width - w - 2, (int) p[0] + 14), y = Math.max(2, Math.min(height - h - 2, (int) p[1] - h / 2));
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int hovered = -1;
        for (int i = 0; i < LineView.jointOptions.size(); i++) {
            LineView.JointOption o = LineView.jointOptions.get(i);
            int ry = y + PAD + i * rowH, rx = x + PAD, rw = w - 2 * PAD;
            boolean over = mx >= rx && mx < rx + rw && my >= ry && my < ry + rowH - 2;
            if (over) hovered = o.type();
            int current = PlanView.jointTypes.getOrDefault(com.volcanicmg.mccitybuilder.planner.Plan.cell(LineView.selectedJoint[0], LineView.selectedJoint[1]), com.volcanicmg.mccitybuilder.planner.Plan.J_AUTO);
            VanillaUi.button(g, rx, ry, rw, rowH - 2, o.type() == current, o.lacks() > 0, over);
            if (o.piece() != null) {
                var bp = PieceCatalog.rotated(pack(), o.piece().path(), 0);
                if (bp != null) PieceView.render(g, "menu|" + pack() + "|" + o.piece().path(), bp, rx + 2, ry + 1, 24, 24);
            }
            int ink = VanillaUi.ink(o.type() == current, o.lacks() > 0);
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.line.joint." + o.type()).getString(), rw - 32), rx + 30, ry + 4, ink, false);
            // "fits · 92%" green from 90% match, amber below; "flips 1 wall" when the walls turn to meet the piece.
            g.drawString(font, jointHint(o), rx + 30, ry + 15, o.lacks() > 0 ? 0xFFA02020 : o.match() >= 0.9 ? 0xFF206020 : 0xFFA06010, false);
            int t = o.type();
            hits.add(new Hit(rx, ry, rw, rowH - 2, () -> {
                click();
                LineView.pickJoint(t);
            }, Component.translatable("mccitybuilder.edit.menu.tip")));
        }
        LineView.previewJoint(hovered);
        // Below the menu: the alignment editor for this joint (its style, family, piece role and turn pre-selected).
        int ay = y + h + 2;
        boolean overA = mx >= x && mx < x + w && my >= ay && my < ay + 18;
        panels.add(new int[]{x, ay, w, 18});
        VanillaUi.button(g, x, ay, w, 18, false, false, overA);
        String al = Component.translatable("mccitybuilder.align.adjust").getString();
        g.drawString(font, al, x + (w - font.width(al)) / 2, ay + 5, VanillaUi.INK, false);
        hits.add(new Hit(x, ay, w, 18, () -> {
            click();
            openAlignmentFor(LineView.selectedJoint);
        }, Component.translatable("mccitybuilder.align.open.tip")));
    }

    /** Opens the joint alignment editor on the planned joint at cell c: its pack, family, piece role and turn. */
    private void openAlignmentFor(int[] c) {
        for (int i = 0; i < PlanView.lines.size() && i < PlanView.linePieces.size(); i++) {
            var l = PlanView.lines.get(i);
            for (var p : PlanView.linePieces.get(i)) {
                int[] b = p.box();
                if (!p.joint() || c[0] < b[0] || c[0] > b[2] || c[1] < b[1] || c[1] > b[3]) continue;
                minecraft.setScreen(new JointEditorScreen(l.pack(), l.family(), p.role(), Math.floorMod(p.turns(), 4)));
                return;
            }
        }
        var line = PlanView.lines.stream().filter(l -> l.x0() == c[0] && l.z0() == c[1] || l.x1() == c[0] && l.z1() == c[1]).findFirst().orElse(null);
        if (line != null) minecraft.setScreen(new JointEditorScreen(line.pack(), line.family(), com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER, -1));
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
        if (!m.held().isEmpty()) out.add(Component.translatable("mccitybuilder.overlay.holding", m.held().getHoverName()).withStyle(net.minecraft.ChatFormatting.GRAY));
        if (!m.armour().isEmpty()) {
            // "Iron Chestplate" -> "Iron": the material is the name's first word.
            String tier = m.armour().getHoverName().getString().split(" ")[0];
            out.add(Component.translatable("mccitybuilder.overlay.armour", tier, m.armourPieces()).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
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

    // The piece preview: browse index, its box this frame (drag / wheel act there), the large view.
    private static int browseIndex;
    private int[] cardBox;
    private boolean bigPreview, cardDrag;

    /**
     * The road / wall panel: family, level or variant, height (mode and blocks), Towers / Gate / Flip, a gear opening
     * the piece-set editor popup, a 3D preview of every piece the line uses (browse with the arrows; the hovered
     * piece shows by default), the segment's numbers, and Done / Undo / Remove / Build.
     */
    private void linePanel(GuiGraphics g, int mx, int my) {
        stackTop = height - 4;
        cardBox = null;
        if (!LineView.active() && PlanView.selectedLine() == null) {
            editorOpen = false;
            bigPreview = false;
            return;
        }
        com.volcanicmg.mccitybuilder.planner.Plan.Line sel = PlanView.selectedLine();
        String tool = sel != null ? sel.tool() : LineView.tool, pk = sel != null ? sel.pack() : pack();
        String status = sel != null ? null : LineView.status();
        String fam = sel != null ? sel.family() : status == null ? LineView.family() : null;
        int w = 220, h = status != null ? 40 : 276, x = Math.max(4 + PALETTE_W + 4, width - 4 - guideRoom() - 4 - w), y = height - h - 4;
        stackTop = y - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, iw = w - 2 * PAD;
        g.drawString(font, Component.translatable(sel != null ? "mccitybuilder.line.selected." + tool : LineView.edit ? "mccitybuilder.line.edit_title." + tool : "mccitybuilder.line.title." + tool), ix + 1, y + PAD, VanillaUi.LABEL, false);
        if (status != null) {
            g.drawString(font, Component.translatable("mccitybuilder.line.status." + status + "." + tool, pk), ix + 1, y + PAD + 13, 0xFFA02020, false);
            return;
        }
        // Settings: the piece-set editor.
        int gx = x + w - PAD - 14, gy = y + PAD - 3;
        boolean overG = mx >= gx && mx < gx + 14 && my >= gy && my < gy + 14;
        VanillaUi.button(g, gx, gy, 14, 14, editorOpen, false, overG);
        g.drawString(font, "\u2699", gx + 3, gy + 3, VanillaUi.INK, false);
        hits.add(new Hit(gx, gy, 14, 14, () -> {
            click();
            editorOpen = !editorOpen;
        }, Component.translatable("mccitybuilder.line.pieces.tip")));
        // Edit mode: a full-width button with a clearly pressed look while on.
        int ey = y + PAD + 11;
        boolean overEd = mx >= ix && mx < ix + iw && my >= ey && my < ey + 20;
        VanillaUi.button(g, ix, ey, iw, 20, LineView.edit, false, overEd);
        String el = Component.translatable(LineView.edit ? "mccitybuilder.line.editing" : "mccitybuilder.line.edit_button").getString();
        g.drawString(font, el, ix + (iw - font.width(el)) / 2, ey + 6, LineView.edit ? 0xFFFFFF60 : VanillaUi.INK, LineView.edit);
        hits.add(new Hit(ix, ey, iw, 20, () -> {
            click();
            LineView.toggleEdit();
        }, Component.translatable("mccitybuilder.line.edit.tip")));
        int iy = picker(g, ix, ey + 24, iw, Component.translatable("mccitybuilder.line.family", fam == null ? "-" : fam.substring(fam.indexOf(':') + 1)).getString(),
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
        // Height: mode, and for flat modes the level with the blocks added ("Y 72 (+3)").
        int mode = sel != null ? sel.height() : LineView.heightMode, hdy = sel != null ? sel.dy() : LineView.dy;
        int flatY = sel != null ? selectedFlatY() : LineView.liveY;
        String hText = Component.translatable("mccitybuilder.line.height." + mode).getString()
                + (flatY != Integer.MIN_VALUE ? "  " + Component.translatable("mccitybuilder.line.height.y", flatY, (hdy >= 0 ? "+" : "") + hdy).getString()
                : hdy != 0 ? "  " + (hdy > 0 ? "+" : "") + hdy : "");
        iy = picker(g, ix, iy, iw, hText, mx, my, d -> {
            LineView.cycleHeight(d);
            click();
        }, Component.translatable("mccitybuilder.line.height.tip"));
        // Joints: the selected joint's type (edit mode), else the default for new joints.
        int jt = LineView.selectedJoint != null ? PlanView.jointTypes.getOrDefault(com.volcanicmg.mccitybuilder.planner.Plan.cell(LineView.selectedJoint[0], LineView.selectedJoint[1]),
                com.volcanicmg.mccitybuilder.planner.Plan.J_AUTO) : LineView.jointDefault;
        iy = picker(g, ix, iy, iw, Component.translatable(LineView.selectedJoint != null ? LineView.selectedIsEnd() ? "mccitybuilder.line.joint.this_end" : "mccitybuilder.line.joint.this" : "mccitybuilder.line.joint.new",
                Component.translatable("mccitybuilder.line.joint." + jt)).getString(), mx, my, d -> {
            LineView.cycleJoint(d);
            click();
        }, Component.translatable("mccitybuilder.line.joint.tip"));
        // A gate (walls: the selected segment or the next one), the other side out, edit mode.
        boolean wall = "wall".equals(tool);
        boolean[] on = {sel != null ? sel.gate() : LineView.gate, sel != null ? sel.flip() : LineView.flip, LineView.wholeMode};
        String[] keys = {"gate", "flip", LineView.wholeMode ? "select_wall" : "select_segment"};
        int bw3 = (iw - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = ix + i * (bw3 + 2);
            boolean off = i == 0 && !wall, over = mx >= bx && mx < bx + bw3 && my >= iy && my < iy + 16;
            VanillaUi.button(g, bx, iy, bw3, 16, on[i], off, over);
            String l = Component.translatable("mccitybuilder.line." + keys[i] + (i == 2 ? ".btn" : "")).getString();
            VanillaUi.buttonLabel(g, font, l, bx, iy, bw3, 16, off ? VanillaUi.INK_DISABLED : VanillaUi.INK);
            int fi = i;
            hits.add(new Hit(bx, iy, bw3, 16, () -> {
                if (fi == 0 && !wall) return;
                click();
                if (fi == 0) LineView.toggleGate();
                else if (fi == 1) LineView.toggleFlip();
                else LineView.wholeMode = !LineView.wholeMode;
            }, Component.translatable(i == 2 ? "mccitybuilder.line.select.tip" : "mccitybuilder.line." + keys[i] + ".tip")));
        }
        iy += 20;
        iy = pieceCard(g, ix, iy, iw, mx, my, sel, pk);
        // The segment's numbers: the live one and the chain, or the selected segment.
        String info;
        if (sel != null) {
            int idx = PlanView.selectedLine;
            var ps = idx < PlanView.linePieces.size() ? PlanView.linePieces.get(idx) : java.util.List.<PieceCatalog.Placed>of();
            int sh = idx < PlanView.lineShort.size() ? PlanView.lineShort.get(idx) : 0;
            info = Component.translatable("mccitybuilder.line.cost_selected", com.volcanicmg.mccitybuilder.planner.LineSnap.length(new int[]{sel.x0(), sel.z0()},
                    new int[]{sel.x1(), sel.z1()}) + 1, ps.size(), LineView.cost(ps), sh).getString();
        } else if (LineView.edit) info = Component.translatable(LineView.selectedJoint != null ? "mccitybuilder.line.edit_joint" : "mccitybuilder.line.edit_hint").getString();
        else if (LineView.hoverLine >= 0) info = Component.translatable("mccitybuilder.line.hover_remove").getString();
        else if (LineView.start == null) info = Component.translatable("mccitybuilder.line.click_start").getString();
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
        String[] bkeys = {"done", "undo", "remove", "build", "clear"};
        boolean canRemove = LineView.hoverLine >= 0 || sel != null || !LineView.sel.isEmpty();
        boolean[] off = {LineView.start == null, !LineView.canUndo(), !canRemove,
                PlanView.lines.isEmpty() && PlanView.entries.isEmpty(), PlanView.lines.stream().noneMatch(l -> l.tool().equals(tool) && !PlanView.locked(l))};
        Runnable[] run = {LineView::finishChain, LineView::undo, () -> {
            if (LineView.removeHovered()) toast(Component.translatable("mccitybuilder.line.removed"));
        }, () -> toast(PlanView.commit(false)), LineView::clearTool};
        int bw = (iw - 8) / 5;
        for (int i = 0; i < 5; i++) {
            int bx = ix + i * (bw + 2);
            boolean over = mx >= bx && mx < bx + bw && my >= iy && my < iy + 20;
            VanillaUi.button(g, bx, iy, bw, 20, false, off[i], over);
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.line." + bkeys[i]).getString(), bx, iy, bw, 20, off[i] ? VanillaUi.INK_DISABLED : VanillaUi.INK);
            int fi = i;
            hits.add(new Hit(bx, iy, bw, 20, () -> {
                if (off[fi]) return;
                click();
                run[fi].run();
            }, Component.translatable(i == 3 && !server ? "mccitybuilder.line.needs_server" : i == 4 ? "mccitybuilder.line.clear.tip." + tool : "mccitybuilder.line." + bkeys[i] + ".tip")));
        }
        if (editorOpen && fam != null) pieceEditor(g, x - 4 - w, Math.max(4, y + h - (PAD * 2 + 46 + EDIT_ROLES.length * 18)), w, pk, fam, mx, my);
    }

    /** @return the selected segment's flat level from the layout, or MIN_VALUE */
    private static int selectedFlatY() {
        int i = PlanView.selectedLine;
        return i >= 0 && i < PlanView.lineHeights.size() ? PlanView.lineHeights.get(i)[0] : Integer.MIN_VALUE;
    }

    /**
     * The piece preview: a 3D view of one piece (the hovered planned piece, else the browsed one of every piece the
     * line uses), its role, name and size; arrows browse, the corner button opens a large view.
     *
     * @return the y below the card
     */
    private int pieceCard(GuiGraphics g, int x, int y, int w, int mx, int my, com.volcanicmg.mccitybuilder.planner.Plan.Line sel, String pk) {
        int ch = 84;
        VanillaUi.slot(g, x, y, w, ch);
        var line = sel != null ? sel : LineView.next(new int[]{0, 0}, new int[]{0, 0});
        var list = PieceCatalog.browse(line);
        String path;
        String role;
        int turns = 0;
        PieceCatalog.Placed hp = LineView.hoverPiece;
        if (hp != null) {
            path = hp.path();
            role = hp.role().name();
            turns = hp.turns();
        } else if (!list.isEmpty()) {
            browseIndex = Math.floorMod(browseIndex, list.size());
            path = list.get(browseIndex).getValue().path();
            role = list.get(browseIndex).getKey().name();
        } else {
            g.drawString(font, Component.translatable("mccitybuilder.line.card.loading"), x + 6, y + 6, 0xFFFFFFFF, true);
            return y + ch + 4;
        }
        com.ldtteam.structurize.blueprints.v1.Blueprint bp = PieceCatalog.rotated(pk, path, turns);
        int vx = x + 18, vw = w - 36, vy = y + 2, vh = ch - 24;
        cardBox = new int[]{vx, vy, vw, vh};
        if (bp != null) PieceView.render(g, pk + "|" + path + "|" + turns, bp, vx, vy, vw, vh);
        // Browse arrows (every piece of the family at this level / variant) and the large view.
        for (int k = 0; k < 2; k++) {
            int bx = k == 0 ? x + 2 : x + w - 16;
            boolean over = mx >= bx && mx < bx + 14 && my >= y + 26 && my < y + 42;
            VanillaUi.button(g, bx, y + 26, 14, 16, false, list.size() < 2 || hp != null, over);
            g.drawString(font, k == 0 ? "<" : ">", bx + 4, y + 30, VanillaUi.INK, false);
            int d = k == 0 ? -1 : 1;
            hits.add(new Hit(bx, y + 26, 14, 16, () -> {
                click();
                browseIndex += d;
            }, Component.translatable("mccitybuilder.line.card.browse")));
        }
        int ex = x + w - 16, ey = y + 2;
        boolean overE = mx >= ex && mx < ex + 14 && my >= ey && my < ey + 14;
        VanillaUi.button(g, ex, ey, 14, 14, bigPreview, false, overE);
        g.drawString(font, "\u2922", ex + 3, ey + 3, VanillaUi.INK, false);
        hits.add(new Hit(ex, ey, 14, 14, () -> {
            click();
            bigPreview = !bigPreview;
        }, Component.translatable("mccitybuilder.line.card.expand")));
        String name = path.substring(path.lastIndexOf('/') + 1).replace(".blueprint", "");
        String size = bp == null ? "" : Component.translatable("mccitybuilder.line.card.size", Math.max(bp.getSizeX(), bp.getSizeZ()), Math.min(bp.getSizeX(), bp.getSizeZ()),
                bp.getSizeY()).getString();
        String head = Component.translatable("mccitybuilder.line.role." + role.toLowerCase(java.util.Locale.ROOT)).getString()
                + (hp == null && list.size() > 1 ? " " + (browseIndex + 1) + "/" + list.size() : "");
        g.drawString(font, font.plainSubstrByWidth(head + " · " + size, w - 6), x + 3, y + ch - 21, 0xFFFFFFFF, true);
        g.drawString(font, font.plainSubstrByWidth(name, w - 6), x + 3, y + ch - 10, 0xFFA0A0A0, false);
        if (bigPreview && bp != null) {
            int bw = Math.min(width - 40, 320), bh = Math.min(height - 60, 260), bx = (width - bw) / 2, by = (height - bh) / 2;
            panels.add(new int[]{bx, by, bw, bh});
            VanillaUi.panel(g, bx, by, bw, bh);
            g.drawString(font, font.plainSubstrByWidth(head + " · " + size + " · " + name, bw - 30), bx + PAD + 1, by + PAD, VanillaUi.LABEL, false);
            int cx = bx + bw - PAD - 12;
            boolean overX = mx >= cx && mx < cx + 12 && my >= by + PAD - 2 && my < by + PAD + 10;
            VanillaUi.button(g, cx, by + PAD - 2, 12, 12, false, false, overX);
            g.drawString(font, "x", cx + 3, by + PAD, VanillaUi.INK, false);
            hits.add(0, new Hit(cx, by + PAD - 2, 12, 12, () -> {
                click();
                bigPreview = false;
            }, null));
            cardBox = new int[]{bx + PAD, by + PAD + 12, bw - 2 * PAD, bh - 2 * PAD - 12};
            VanillaUi.slot(g, cardBox[0], cardBox[1], cardBox[2], cardBox[3]);
            PieceView.render(g, pk + "|" + path + "|" + turns, bp, cardBox[0], cardBox[1], cardBox[2], cardBox[3]);
        }
        return y + ch + 4;
    }

    /** The per-role piece editor, a popup beside the line panel: which piece group plays each role (auto / none / any). */
    private void pieceEditor(GuiGraphics g, int x, int y, int w, String pk, String fam, int mx, int my) {
        var fs = PieceCatalog.families(pk);
        var f = fs == null ? null : fs.get(fam);
        if (f == null) return;
        int h = PAD * 2 + 46 + EDIT_ROLES.length * 18;
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
        // The joint alignment editor for this style and family.
        iy += 4;
        boolean overA = mx >= ix && mx < ix + iw && my >= iy && my < iy + 20;
        VanillaUi.button(g, ix, iy, iw, 20, false, false, overA);
        String al = Component.translatable("mccitybuilder.align.open").getString();
        g.drawString(font, al, ix + (iw - font.width(al)) / 2, iy + 6, VanillaUi.INK, false);
        hits.add(new Hit(ix, iy, iw, 20, () -> {
            click();
            minecraft.setScreen(new JointEditorScreen(pk, fam, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER, -1));
        }, Component.translatable("mccitybuilder.align.open.tip")));
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
            } else if (LineView.liveClash) {
                warn = Component.translatable("mccitybuilder.line.crossing").getString();
                warnColour = 0xFFFF8080;
            } else if (LineView.liveShort > 0) warn = Component.translatable("mccitybuilder.line.short", LineView.liveShort).getString();
        }
        // What snapped, under the hint.
        if (LineView.snap != null && warn == null)
            warn = Component.translatable("mccitybuilder.line.snap." + LineView.snap, LineView.snapPieces).getString();
        if (LineView.flash != null && System.currentTimeMillis() < LineView.flashUntil) {
            warn = Component.translatable(LineView.flash, LineView.flashArg).getString();
            warnColour = 0xFFFF8080;
        }
        if (warn == null || overPanel(mx, my)) return;
        int w = font.width(warn);
        g.fill(mx + 10, my + 10, mx + 16 + w, my + 23, 0xC0202020);
        g.drawString(font, warn, mx + 13, my + 13, LineView.snap != null && warnColour == 0xFFFFC040 && LineView.liveShort == 0 ? 0xFF80E0FF : warnColour, false);
    }

    /**
     * Edit mode on screen: a node at every segment end / joint and a handle in every segment's middle (constant size,
     * picked within 12 GUI pixels, nodes before handles; white idle, yellow hovered, cyan selected, green / red while
     * a drag fits / doesn't), the joint types over the joints, a label for the selection, a status line while dragging,
     * a banner, and a hint at the cursor for what a click or drag does.
     */
    private void editOverlay(GuiGraphics g, int mx, int my) {
        if (!LineView.active() || !LineView.edit) {
            LineView.hoverJoint = null;
            LineView.hoverMid = -1;
            return;
        }
        List<double[]> hs = new ArrayList<>();
        List<Object> what = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<com.volcanicmg.mccitybuilder.planner.Plan.Line> ls = PlanView.lines;
        for (var l : ls)
            for (int[] e : l.ends())
                if (seen.add(com.volcanicmg.mccitybuilder.planner.Plan.cell(e[0], e[1]))) {
                    double[] p = screen(e[0], e[1], 1.5);
                    if (p != null) {
                        hs.add(new double[]{p[0], p[1], 0});
                        what.add(e);
                    }
                }
        for (int i = 0; i < ls.size(); i++) {
            var l = ls.get(i);
            double[] p = screen((l.x0() + l.x1()) / 2, (l.z0() + l.z1()) / 2, 1.5);
            if (p != null) {
                hs.add(new double[]{p[0], p[1], 1});
                what.add(i);
            }
        }
        int hv = overPanel(mx, my) || LineView.dragging() ? -1 : com.volcanicmg.mccitybuilder.planner.LineSnap.pickHandle(hs, mx, my, 12);
        if (!LineView.dragging()) {
            LineView.hoverJoint = hv >= 0 && hs.get(hv)[2] == 0 ? (int[]) what.get(hv) : null;
            LineView.hoverMid = hv >= 0 && hs.get(hv)[2] == 1 ? (Integer) what.get(hv) : -1;
        }
        // Joint types over the joints (from the last layout): "corner", "T · gap 4 (tower: 0)".
        for (var en : PlanView.jointNotes.entrySet()) {
            int jx = com.volcanicmg.mccitybuilder.planner.Plan.cellX(en.getKey()), jz = com.volcanicmg.mccitybuilder.planner.Plan.cellZ(en.getKey());
            double[] p = screen(jx, jz, 5);
            if (p == null) continue;
            int[] n = en.getValue();
            String t = Component.translatable("mccitybuilder.line.role." + com.volcanicmg.mccitybuilder.planner.PieceSets.Role.values()[n[0]].name().toLowerCase(java.util.Locale.ROOT)).getString()
                    + (n[1] > 0 ? " · " + Component.translatable("mccitybuilder.edit.gap", n[1]).getString() + (n[2] >= 0 && n[2] < n[1] ? " " + Component.translatable("mccitybuilder.edit.try_tower", n[2]).getString() : "") : "")
                    + (PlanView.jointGapAir.contains(en.getKey()) ? " · " + Component.translatable("mccitybuilder.edit.gap_air").getString() : "");
            int tw = font.width(t);
            g.fill((int) p[0] - tw / 2 - 2, (int) p[1] - 5, (int) p[0] + tw / 2 + 2, (int) p[1] + 5, 0xA0202020);
            g.drawString(font, t, (int) p[0] - tw / 2, (int) p[1] - 4, n[1] > 0 ? 0xFFFFC040 : 0xFFE0E0E0, false);
        }
        boolean dragging = LineView.dragging();
        for (int k = 0; k < hs.size(); k++) {
            double[] h = hs.get(k);
            boolean joint = h[2] == 0, hover = k == hv;
            boolean selected = joint ? LineView.selectedJoint != null && ((int[]) what.get(k))[0] == LineView.selectedJoint[0] && ((int[]) what.get(k))[1] == LineView.selectedJoint[1]
                    : LineView.sel.contains((Integer) what.get(k));
            // Amber, so the nodes never read as citizen markers: a diamond ring for a joint, a small diamond for a
            // middle handle; paler when hovered, white-hot when selected, green / orange / red while a drag fits / doesn't.
            int c = dragging && selected ? (LineView.dragPreview == null ? 0xFFFF3020 : LineView.dragFits ? 0xFFC0FF40 : 0xFFFF8020)
                    : selected ? 0xFFFFF0B0 : hover ? 0xFFFFD060 : 0xFFF0A020;
            int x = (int) h[0], y = (int) h[1], r = joint ? 6 : 4;
            for (int d = -r; d <= r; d++) {
                int half = r - Math.abs(d);
                g.fill(x - half - 1, y + d, x + half + 2, y + d + 1, 0xFF202020);
                if (half > 0) g.fill(x - half, y + d, x + half + 1, y + d + 1, c);
            }
            // The ring's hole.
            if (joint) for (int d = -2; d <= 2; d++) {
                int half = 2 - Math.abs(d);
                g.fill(x - half, y + d, x + half + 1, y + d + 1, 0xFF202020);
            }
        }
        jointMenu(g, mx, my);
        // The selection's label.
        if (!LineView.sel.isEmpty() && LineView.sel.get(0) < ls.size()) {
            var first = ls.get(LineView.sel.get(0));
            var wall = com.volcanicmg.mccitybuilder.planner.Plan.connected(ls, LineView.sel.get(0));
            int blocks = 0;
            for (int i : LineView.sel) if (i < ls.size()) blocks += com.volcanicmg.mccitybuilder.planner.Plan.cells(ls.get(i));
            String t = LineView.selWhole ? Component.translatable("mccitybuilder.edit.whole", LineView.sel.size(), blocks).getString()
                    : Component.translatable("mccitybuilder.edit.segment", wall.indexOf(LineView.sel.get(0)) + 1, wall.size(), blocks).getString();
            double[] p = screen((first.x0() + first.x1()) / 2, (first.z0() + first.z1()) / 2, 8);
            if (p != null) {
                int tw = font.width(t);
                g.fill((int) p[0] - tw / 2 - 3, (int) p[1] - 6, (int) p[0] + tw / 2 + 3, (int) p[1] + 6, 0xC0103040);
                g.drawString(font, t, (int) p[0] - tw / 2, (int) p[1] - 4, 0xFF80F0FF, false);
            }
        }
        // Banner and drag status, top centre.
        String top = LineView.dragStatus != null ? LineView.dragStatus
                : Component.translatable("mccitybuilder.edit.banner", Component.translatable(LineView.wholeMode ? "mccitybuilder.line.select_wall" : "mccitybuilder.line.select_segment")).getString();
        int tw = font.width(top);
        g.fill(width / 2 - tw / 2 - 4, 46, width / 2 + tw / 2 + 4, 60, LineView.dragStatus != null && !LineView.dragFits ? 0xE0503010 : 0xE0103040);
        g.drawString(font, top, width / 2 - tw / 2, 49, 0xFFFFFFFF, false);
        // What a click / drag does here.
        if (!dragging && !overPanel(mx, my)) {
            String hint = LineView.hoverJoint != null ? "mccitybuilder.edit.hint_joint" : LineView.hoverMid >= 0 ? "mccitybuilder.edit.hint_mid"
                    : LineView.hoverLine >= 0 ? (LineView.wholeMode ? "mccitybuilder.edit.hint_body_wall" : "mccitybuilder.edit.hint_body") : null;
            if (hint != null) {
                String t = Component.translatable(hint).getString();
                int w2 = font.width(t);
                g.fill(mx + 10, my - 14, mx + 16 + w2, my - 1, 0xC0202020);
                g.drawString(font, t, mx + 13, my - 11, 0xFFFFE080, false);
            }
        }
    }

    /** @return where cell (x, z) at {@code up} blocks over its ground appears on screen (GUI pixels), or null */
    private double[] screen(int x, int z, double up) {
        Integer gy = Planner.groundAt(x, z);
        double[] ndc = Planner.project(x + 0.5, (gy == null ? 64 : gy) + up, z + 0.5);
        if (ndc == null || Math.abs(ndc[0]) > 1.2 || Math.abs(ndc[1]) > 1.2) return null;
        return new double[]{(ndc[0] + 1) / 2 * width, (1 - ndc[1]) / 2 * height};
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
        int w = 200, h = 58, x = Math.max(4 + PALETTE_W + 4, width - 4 - guideRoom() - 4 - w), y = stackTop - h;
        if (Planner.selected != null || Planner.selectedDeco != null) y -= 62;
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
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.plan." + keys[i]).getString(), bx, by, bw, 20, off[i] ? VanillaUi.INK_DISABLED : VanillaUi.INK);
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

    // The palette's tab: huts, or the pack's decorations (kept for the client session).
    private static boolean decoTab;

    /**
     * @return the category picker's ids: on the Huts tab creative starts with "all" (every hut in one grid), survival
     * as before; on the Decorations tab "all" then the style pack's decoration folders, each once its blueprints have
     * loaded and it has any that aren't road / wall pieces
     */
    private static List<String> categories() {
        List<String> out = new ArrayList<>();
        if (decoTab) {
            out.add("all");
            for (String f : Blueprints.decoFolders(pack())) if (!decorations(f).isEmpty()) out.add("deco:" + f);
            return out;
        }
        if (Planner.creative()) out.add("all");
        out.addAll(HutCatalog.CATEGORIES);
        return out;
    }

    /**
     * @return a folder's decorations without the road / wall pieces (the line tools' business), unless that would
     * hide every one of them; empty while they load
     */
    private static List<Blueprints.Hut> decorations(String folder) {
        List<Blueprints.Hut> all = Blueprints.decorations(pack(), folder);
        if (all == null) return List.of();
        List<Blueprints.Hut> out = new ArrayList<>();
        for (Blueprints.Hut h : all) if (com.volcanicmg.mccitybuilder.planner.PieceSets.tool(h.path()) == null) out.add(h);
        return out.isEmpty() ? all : out;
    }

    /** @return the chosen decoration folder on the Decorations tab ("" for all of them), or null on the Huts tab */
    private static String decoFolder() {
        return !decoTab ? null : category().startsWith("deco:") ? category().substring(5) : "";
    }

    /** @return the chosen decoration folder's decorations (every folder's for "all") matching the search */
    private static List<Blueprints.Hut> decos() {
        String folder = decoFolder();
        if (folder == null) return List.of();
        List<Blueprints.Hut> all = new ArrayList<>();
        if (folder.isEmpty()) for (String f : Blueprints.decoFolders(pack())) all.addAll(decorations(f));
        else all.addAll(decorations(folder));
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
        int fixedH = PAD + 20 + 16 + 4 + 20 + 20 + (recent.isEmpty() ? 0 : 10 + SLOT + 4) + gridH + 6 + placingH + 12 + 10 + PAD;
        // As many material rows as fit on screen (at least 3, at most 12).
        int costRows = Math.max(3, Math.min(COST_ROWS, (height - 8 - fixedH) / 10));
        com.volcanicmg.mccitybuilder.planner.Costs.Summary cost = need == null ? null
                : com.volcanicmg.mccitybuilder.planner.Costs.summarize(need, inventory(), costRows);
        int costH = !Planner.isPlacing() ? 0 : 12 + (cost == null ? 10 : cost.rows().size() * 10 + (cost.more() > 0 ? 10 : 0));
        int x = 4, y = 4, w = PALETTE_W;
        int h = PAD + 20 + 16 + 4 + 20 + 20 + (recent.isEmpty() ? 0 : 10 + SLOT + 4) + gridH + 6 + placingH + costH + PAD;
        panels.add(new int[]{x, y, w, h});
        paletteRect = new int[]{x, y, w, h};
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, yy = y + PAD, iw = w - 2 * PAD;
        // Tabs: Huts | Decorations (the chosen one drawn as an inset).
        int tw2 = (iw - 2) / 2;
        for (int i = 0; i < 2; i++) {
            int bx = ix + i * (tw2 + 2);
            boolean on = decoTab == (i == 1), over = mx >= bx && mx < bx + tw2 && my >= yy && my < yy + 16;
            VanillaUi.button(g, bx, yy, tw2, 16, on, false, over);
            VanillaUi.buttonLabel(g, font, Component.translatable(i == 0 ? "mccitybuilder.huts" : "mccitybuilder.decorations").getString(), bx, yy, tw2, 16, VanillaUi.ink(on, false));
            boolean deco = i == 1;
            hits.add(new Hit(bx, yy, tw2, 16, () -> {
                if (decoTab == deco) return;
                click();
                decoTab = deco;
                search = "";
                decoScroll = 0;
                if (deco ? Planner.placing != null : Planner.deco != null) Planner.stopPlacing();
            }, Component.translatable(deco ? "mccitybuilder.decorations.tip" : "mccitybuilder.huts.tip")));
        }
        yy += 20;
        // Search field (click to type; Esc clears it).
        VanillaUi.textField(g, ix, yy, iw, 16);
        String caret = searchFocused && System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "";
        boolean empty = search.isEmpty();
        g.drawString(font, empty && !searchFocused ? Component.translatable(decoTab ? "mccitybuilder.search_deco" : "mccitybuilder.search").getString() : search + caret, ix + 4, yy + 4,
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
            // A building move keeps going; the pack only changes what a new hut would be.
            if (Planner.placing != null && Planner.moving == null) Planner.startPlacing(Planner.placing);
            else if (Planner.deco != null) Planner.stopPlacing();
            click();
        }, Component.translatable("mccitybuilder.pack"));
        String catLabel = !search.isEmpty() ? Component.translatable("mccitybuilder.search_results", decoMode ? decos.size() : huts.size()).getString()
                : decoMode && !decoFolder().isEmpty() ? Component.translatable("mccitybuilder.category.deco", decoFolder()).getString()
                : Component.translatable("mccitybuilder.category." + (decoMode ? "all_deco" : category())).getString();
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
                if (over) {
                    previewDeco = d;
                    previewY = ry;
                }
                if (sel || over) g.fill(ix, ry, ix + iw, ry + DECO_ROW_H, sel ? 0x60FFFFFF : 0x30FFFFFF);
                g.drawString(font, font.plainSubstrByWidth(d.blueprint().getFileName(), iw - 4), ix + 2, ry + 2, VanillaUi.LABEL, false);
                hits.add(new Hit(ix, ry, iw, DECO_ROW_H, () -> {
                    click();
                    if (sel) Planner.stopPlacing();
                    else Planner.startPlacingDeco(d);
                }, null));
            }
        } else for (int i = 0; i < huts.size(); i++) hutSlot(g, huts.get(i), ix + i % COLS * SLOT, yy + i / COLS * SLOT, mx, my);
        if (decoMode ? decos.isEmpty() : huts.isEmpty()) g.drawString(font, Component.translatable(decoMode ? "mccitybuilder.no_match_deco" : "mccitybuilder.no_match"), ix + 1, yy + 2, 0xFF606060, false);
        yy += gridH + 6;
        String counter = decoMode && decos.size() > DECO_ROWS ? (decoScroll + 1) + "-" + Math.min(decos.size(), decoScroll + DECO_ROWS) + "/" + decos.size() : "";
        int cw = counter.isEmpty() ? 0 : font.width(counter) + 4;
        if (cw > 0) g.drawString(font, counter, ix + iw - cw + 4, yy, 0xFF606060, false);
        if (!Planner.isPlacing()) {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(decoMode ? "mccitybuilder.pick_deco" : "mccitybuilder.pick_hut").getString(), iw - cw), ix + 1, yy, VanillaUi.LABEL, false);
            return;
        }
        // What is being placed, its state, and its materials.
        g.drawString(font, font.plainSubstrByWidth(Planner.placingName(), iw - cw), ix + 1, yy, VanillaUi.LABEL, false);
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

    // The decoration name under the cursor in the palette this frame, and its row.
    private Blueprints.Hut previewDeco;
    private int previewY;
    // The one decoration preview kept baked; freed when the hover moves on.
    private String previewKey;

    /** A turning 3D preview of the hovered palette decoration beside the palette, with its name and size. */
    private void decoPreview(GuiGraphics g) {
        Blueprints.Hut d = previewDeco;
        previewDeco = null;
        String key = d == null ? null : "deco|" + d.pack() + "|" + d.path();
        if (previewKey != null && !previewKey.equals(key)) PieceView.forget(previewKey);
        previewKey = key;
        if (d == null) return;
        var bp = d.blueprint();
        int w = 128, h = 128 + 24, x = 4 + PALETTE_W + 4, y = Math.max(4, Math.min(previewY - 20, height - h - 4));
        VanillaUi.panel(g, x, y, w, h);
        PieceView.render(g, key, bp, x + PAD, y + PAD, w - 2 * PAD, w - 2 * PAD);
        g.drawString(font, font.plainSubstrByWidth(bp.getFileName(), w - 2 * PAD), x + PAD + 1, y + w - PAD + 3, VanillaUi.LABEL, false);
        g.drawString(font, bp.getSizeX() + " \u00d7 " + bp.getSizeY() + " \u00d7 " + bp.getSizeZ(), x + PAD + 1, y + w - PAD + 13, 0xFF555555, false);
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
        if (Planner.selectedDeco != null) {
            decoSelection(g, mx, my, Planner.selectedDeco);
            return;
        }
        IBuildingView b = Planner.selected;
        if (b == null) return;
        // Bottom, right of the palette (which can run tall).
        // Creative with the server part adds an instant Clean (placeholders) button; it pastes over pending work orders too.
        boolean instant = Planner.creative() && Planner.serverInstant();
        int n = instant ? 5 : 3, w = instant ? PALETTE_W + 88 : PALETTE_W, h = 58, x = 4 + PALETTE_W + 4, y = height - h - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        g.drawString(font, font.plainSubstrByWidth(name(b) + "  " + levelText(b), w - 2 * PAD), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        String info = Component.translatable("mccitybuilder.workers", b.getAllAssignedCitizens().size()).getString()
                + (b.hasWorkOrder() ? "  " + Component.translatable("mccitybuilder.has_work_order").getString() : "");
        g.drawString(font, font.plainSubstrByWidth(info, w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        String[] keys = {"upgrade", "repair", "open", "clean", "move"};
        // Open uses the client-side building view, so it works however buried or far the hut block is.
        Runnable[] run = {() -> upgrade(b), () -> repair(b), () -> b.openGui(false), () -> {
            if (Planner.creativeClean(b)) toast(Component.translatable("mccitybuilder.clean_sent"));
        }, () -> {
            if (Planner.startMove(b)) toast(Component.translatable("mccitybuilder.move_started"));
        }};
        // Why a button is off, or null when it is on.
        boolean pending = b.hasWorkOrder() && !instant;
        boolean townHall = b instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall.View, family = b.getParent() != null && !b.getParent().equals(BlockPos.ZERO);
        String[] why = {b.isBuildingMaxLevel() ? "mccitybuilder.upgrade.max" : pending ? "mccitybuilder.upgrade.pending" : null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : pending ? "mccitybuilder.upgrade.pending" : null, null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : townHall ? "mccitybuilder.move.townhall" : family ? "mccitybuilder.move.family" : null};
        buttonRow(g, x, y, w, n, keys, run, why, mx, my);
    }

    /** A placed decoration: Move and Delete in creative, Remove (a builder order) in survival; all through the server part. */
    private void decoSelection(GuiGraphics g, int mx, int my, Planner.Deco d) {
        boolean creative = Planner.creative(), server = Planner.serverInstant();
        int w = PALETTE_W, h = 58, x = 4 + PALETTE_W + 4, y = height - h - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        g.drawString(font, font.plainSubstrByWidth(d.name(), w - 2 * PAD), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.deco.info", d.pack()).getString(), w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        String off = server ? null : "mccitybuilder.deco.no_server";
        String[] keys = creative ? new String[]{"deco_move", "deco_delete"} : new String[]{"deco_remove"};
        Runnable[] run = creative ? new Runnable[]{() -> {
            if (Planner.startMoveDeco(d)) toast(Component.translatable("mccitybuilder.deco_move_started"));
            else toast(Component.translatable("mccitybuilder.invalid.loading"));
        }, () -> {
            if (Planner.sendDeco(com.volcanicmg.mccitybuilder.server.InstantBuild.DECO_DELETE, d)) toast(Component.translatable("mccitybuilder.deco_delete_sent"));
            Planner.select(null);
        }} : new Runnable[]{() -> {
            if (Planner.sendDeco(com.volcanicmg.mccitybuilder.server.InstantBuild.DECO_REMOVE_ORDER, d)) toast(Component.translatable("mccitybuilder.deco_remove_sent"));
            Planner.select(null);
        }};
        buttonRow(g, x, y, w, keys.length, keys, run, creative ? new String[]{off, off} : new String[]{off}, mx, my);
    }

    /** The selection panel's buttons: label {@code mccitybuilder.<key>}, tip {@code .tip}, or {@code why[i]} when off. */
    private void buttonRow(GuiGraphics g, int x, int y, int w, int n, String[] keys, Runnable[] run, String[] why, int mx, int my) {
        int bw = (w - 2 * PAD - 2 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            int bx = x + PAD + i * (bw + 2), by = y + PAD + 24;
            boolean off = why[i] != null, over = mx >= bx && mx < bx + bw && my >= by && my < by + 20;
            VanillaUi.button(g, bx, by, bw, 20, false, off, over);
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder." + keys[i]).getString(), bx, by, bw, 20, off ? VanillaUi.INK_DISABLED : VanillaUi.INK);
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

    /** @return the width the controls guide takes at the right edge (just its "?" button while closed) */
    private static int guideRoom() {
        return Config.SHOW_HINTS.get() ? Hints.WIDTH : 20;
    }

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
        // Dragging in the piece preview turns it.
        if (button == 0 && cardBox != null && mx >= cardBox[0] && mx < cardBox[0] + cardBox[2] && my >= cardBox[1] && my < cardBox[1] + cardBox[3]) {
            cardDrag = PieceView.dragging = true;
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
                if (LineView.edit) LineView.press(hit, hasAltDown());
                else LineView.click(hit);
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
        Object picked = pe >= 0 || hit == null ? null : Planner.pickAt(hit.getBlockPos());
        if (picked instanceof Planner.Deco d) Planner.selectDeco(d);
        else Planner.select((IBuildingView) picked);
        return true;
    }

    private boolean draggedImpl(double mx, double my, double dx, double dy) {
        if (LineView.dragging()) {
            LineView.dragTo(Planner.pick(mx / width, my / height), hasShiftDown());
            return true;
        }
        if (cardDrag) {
            PieceView.yaw += (float) dx * 1.5f;
            PieceView.pitch = Math.max(-10, Math.min(89, PieceView.pitch + (float) dy * 1.5f));
            return true;
        }
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
        if (button == 0 && cardDrag) {
            cardDrag = PieceView.dragging = false;
            return true;
        }
        if (button == 0 && LineView.dragging()) {
            LineView.release();
            return true;
        }
        if (button == 1 && rightDown) {
            rightDown = false;
            if (rightDragged) Planner.endDragTurn();
            else {
                // A plain right-click backs out one step: adjust -> follow -> not placing; ends a road / wall chain; deselect too.
                if (LineView.active()) {
                    if (LineView.start != null) LineView.finishChain();
                    // On a planned segment: remove it (Undo brings it back); elsewhere: leave the tool.
                    else if (LineView.hoverLine >= 0 && LineView.removeHovered()) toast(Component.translatable("mccitybuilder.line.removed"));
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
        if (cardBox != null && mx >= cardBox[0] && mx < cardBox[0] + cardBox[2] && my >= cardBox[1] && my < cardBox[1] + cardBox[3]) {
            PieceView.zoom = (float) Math.max(0.5, Math.min(4, PieceView.zoom * (sy > 0 ? 1.15 : 1 / 1.15)));
            return true;
        }
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
        if (key == GLFW.GLFW_KEY_ESCAPE && LineView.dragging()) {
            LineView.cancelDrag();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && LineView.jointMenu) {
            LineView.closeJointMenu(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB && LineView.active() && LineView.edit) {
            LineView.wholeMode = !LineView.wholeMode;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (Planner.isPlacing()) Planner.back();
            else if (LineView.active() && LineView.edit) {
                if (!LineView.deselect()) LineView.toggleEdit();
            } else if (LineView.active() && LineView.start != null) LineView.finishChain();
            else if (LineView.active()) useTool(LineView.tool);
            else if (PlanView.selectedLine() != null) PlanView.selectedLine = -1;
            else if (PlanView.hasSelection()) PlanView.selected = -1;
            else if (Planner.selected != null || Planner.selectedDeco != null) Planner.select(null);
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
                case GLFW.GLFW_KEY_DELETE -> {
                    if (LineView.removeHovered()) toast(Component.translatable("mccitybuilder.line.removed"));
                }
                case GLFW.GLFW_KEY_R -> LineView.toggleFlip();
                case GLFW.GLFW_KEY_H -> LineView.cycleHeight(1);
                case GLFW.GLFW_KEY_PAGE_UP -> LineView.raise(1);
                case GLFW.GLFW_KEY_PAGE_DOWN -> LineView.raise(-1);
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
                case GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_C -> LineView.cycleJoint(1);
                case GLFW.GLFW_KEY_X -> {
                    if (LineView.active()) LineView.toggleEdit();
                }
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
