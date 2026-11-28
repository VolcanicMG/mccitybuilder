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
import com.volcanicmg.mccitybuilder.planner.JointCount;
import com.volcanicmg.mccitybuilder.planner.LineHelp;
import com.volcanicmg.mccitybuilder.planner.Materials;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
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
    /** The road / wall tool's coach tip shown ({@link LineHelp#TIPS} index), or -1. */
    static int tipStep = -1;
    // The Route tip is due (set when Route is first turned on).
    static boolean routeTip;
    // The line panel's box this frame (coach tips anchor next to it), or null.
    private int[] linePanelBox;
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
        setupHits.clear();
        panels.clear();
        anchors.clear();
        IColonyView colony = Planner.colony();
        if (colony == null) return;
        // Aim the ghost and find the building under the cursor (every frame, so both follow smoothly).
        BlockHitResult hit = Planner.pick(mouseX / (double) width, mouseY / (double) height);
        Planner.aim(hit);
        LineView.aim(hit);
        banner(g, colony);
        overlayMarkers(g);
        citizenRangeHint(g);
        overlayPicker(g, mouseX, mouseY);
        palette(g, mouseX, mouseY);
        queue(g, colony, mouseX, mouseY);
        selection(g, mouseX, mouseY);
        // The line panel first: it sits lowest and the plan panel stacks above it (never under it, where its
        // hit boxes would take the line panel's clicks).
        linePanel(g, mouseX, mouseY);
        planPanel(g, mouseX, mouseY);
        lineLabels(g, mouseX, mouseY);
        Others.labels(g, font, width, height);
        editOverlay(g, mouseX, mouseY);
        actionBar(g, mouseX, mouseY);
        routeBar(g, mouseX, mouseY);
        guide(g, mouseX, mouseY);
        coachTip(g, mouseX, mouseY);
        status(g);
        boolean overUi = overPanel(mouseX, mouseY);
        // The planned segment under the cursor (not while drawing): highlighted, Del / right-click remove it.
        if (!overUi) {
            LineView.hoverLine = LineView.active() && LineView.start == null && hit != null ? PlanView.lineAt(hit.getBlockPos()) : -1;
            LineView.hoverPiece = LineView.hoverLine >= 0 ? PlanView.pieceAt(LineView.hoverLine, hit.getBlockPos()) : null;
        }
        // Re-picked every frame from the current camera; a miss only clears it after a few frames, so it holds steady.
        Object now = !overUi && !Planner.isPlacing() ? Planner.pickUnder(mouseX / (double) width, mouseY / (double) height, hit) : null;
        if (now != null || overUi || Planner.isPlacing() || ++hoverMisses > HOVER_HOLD) {
            Planner.hovered = now instanceof IBuildingView b ? b : null;
            Planner.hoveredDeco = now instanceof Planner.Deco d ? d : null;
            hoverMisses = 0;
        }
        decoPreview(g);
        if (tip3d != null) {
            drawTip3d(g, mouseY);
            return;
        }
        for (Hit h : hits) {
            if (!h.in(mouseX, mouseY)) continue;
            if (h.tip() != null) {
                g.renderTooltip(font, font.split(h.tip(), 220), mouseX, mouseY);
                return;
            }
            if (h.action() == BLOCK) break;
        }
        if (overUi) return;
        int[] mk = markerAt(mouseX, mouseY);
        if (mk != null) {
            OverlayView.Marker m = OverlayView.markers.get(mk[0]);
            try {
                CitizenCard.draw(g, font, m, mouseX, mouseY, width, height, MAX_JOB_ICONS - iconsUsed);
            } catch (RuntimeException e) {
                // A MineColonies change must not close the planner: the name alone, logged once.
                if (!cardFailed) com.volcanicmg.mccitybuilder.CityBuilder.LOGGER.warn("CityBuilder: citizen card failed", e);
                cardFailed = true;
                if (m.citizen() != null) g.renderTooltip(font, Component.literal(m.citizen().getName()), mouseX, mouseY);
            }
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

    /** Citizen markers projected to the screen: a hammer badge for builders, a job-coloured square for workers, hollow when out of range. */
    private void overlayMarkers(GuiGraphics g) {
        drawnMarkers.clear();
        List<OverlayView.Marker> ms = OverlayView.markers;
        // The selected (else hovered) hut's people: a line from the hut to each one outside, and a ring on them (lines
        // first, so the staffing label and the markers stay on top).
        IBuildingView hut = Planner.selected != null ? Planner.selected : Planner.hovered;
        java.util.Set<Integer> linked = new java.util.HashSet<>();
        double[] hutNdc = null;
        if (hut != null) {
            linked.addAll(hut.getAllAssignedCitizens());
            BlockPos hp = hut.getPosition();
            hutNdc = Planner.project(hp.getX() + 0.5, hp.getY() + 1.5, hp.getZ() + 0.5);
        }
        int hx = hutNdc == null ? 0 : (int) Math.round((hutNdc[0] + 1) / 2 * width), hy = hutNdc == null ? 0 : (int) Math.round((1 - hutNdc[1]) / 2 * height);
        // Both ends on screen only: a point near or past the camera plane projects anywhere (stray, very long lines).
        if (hutNdc != null && Math.abs(hutNdc[0]) <= 1.05 && Math.abs(hutNdc[1]) <= 1.05)
            for (OverlayView.Marker m : ms) {
                if (m.citizen() == null || !linked.contains(m.citizen().getId())) continue;
                double[] ndc = Planner.project(m.pos().x, m.pos().y, m.pos().z);
                if (ndc != null && Math.abs(ndc[0]) <= 1.05 && Math.abs(ndc[1]) <= 1.05)
                    link(g, hx, hy, (int) Math.round((ndc[0] + 1) / 2 * width), (int) Math.round((1 - ndc[1]) / 2 * height));
            }
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
            // The hut's name on a line above its count.
            IBuildingView b = Planner.colony() == null ? null : Planner.colony().getClientBuildingManager().getBuilding(p);
            String n = b == null ? "" : font.plainSubstrByWidth(name(b), 120);
            int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height), tw = font.width(t);
            int half = Math.max(tw, font.width(n)) / 2, top = n.isEmpty() ? sy - 6 : sy - 16;
            int[] r = {sx - half - 2, top, sx + half + 2, sy + 5};
            boolean overlap = false;
            for (int[] o : labels) overlap |= r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3];
            if (overlap) continue;
            labels.add(r);
            int col = c[0] == 0 ? 0xFF6060 : c[0] < c[1] ? 0xFFC040 : 0x70E070;
            g.fill(r[0], r[1], r[2], r[3], alpha << 24);
            if (!n.isEmpty()) g.drawString(font, n, sx - font.width(n) / 2, sy - 14, (alpha + 0x3F) << 24 | 0xFFFFFF, false);
            g.drawString(font, t, sx - tw / 2, sy - 4, (alpha + 0x3F) << 24 | col, false);
        }
        int icons = 0;
        for (int i = 0; i < ms.size(); i++) {
            OverlayView.Marker m = ms.get(i);
            double[] ndc = Planner.project(m.pos().x, m.pos().y, m.pos().z);
            if (ndc == null || Math.abs(ndc[0]) > 1.05 || Math.abs(ndc[1]) > 1.05) continue;
            int sx = (int) Math.round((ndc[0] + 1) / 2 * width), sy = (int) Math.round((1 - ndc[1]) / 2 * height);
            if (m.citizen() != null && linked.contains(m.citizen().getId())) {
                g.fill(sx - 10, sy - 10, sx + 10, sy + 10, 0xFFFFFFFF);
                g.fill(sx - 9, sy - 9, sx + 9, sy + 9, 0xFF202020);
            }
            if (m.far()) {
                // Out of range (no loaded entity): a hollow, faded square at the last known spot.
                int r = m.site() != null ? 6 : 4, c = 0x90000000 | m.color() & 0xFFFFFF;
                g.fill(sx - r, sy - r, sx + r, sy + r, 0x60000000);
                g.renderOutline(sx - r + 1, sy - r + 1, 2 * r - 2, 2 * r - 2, c);
            } else if (m.site() != null) {
                // A builder: what it needs next on red, else the tool it holds, else its hammer badge.
                boolean needs = m.icon() != null && icons < MAX_JOB_ICONS;
                g.fill(sx - 7, sy - 7, sx + 7, sy + 7, 0xFF000000);
                g.fill(sx - 6, sy - 6, sx + 6, sy + 6, needs ? 0xFFD02020 : m.color());
                if (needs) {
                    icons++;
                    miniItem(g, m.icon(), sx - 5, sy - 5, 0.625f);
                } else if (!m.held().isEmpty() && icons < MAX_JOB_ICONS) {
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
        iconsUsed = icons;
    }

    /** A hut-to-citizen link: a 2 px dashed white line on a dark edge, the dashes marching from the hut outwards. */
    private static void link(GuiGraphics g, int x0, int y0, int x1, int y1) {
        // Steps as the pixel version took them (the longer axis), so dashes keep their length on diagonals.
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (n == 0) return;
        float sx = (x1 - x0) / (float) n, sy = (y1 - y0) / (float) n, len = (float) Math.hypot(x1 - x0, y1 - y0);
        // Unit normal across the line; everything sits 1 px right / down, like the 2 px fills did.
        float nx = -(y1 - y0) / len, ny = (x1 - x0) / len, cx = x0 + 1, cy = y0 + 1;
        int phase = (int) (System.currentTimeMillis() / 60 % 8);
        var m = g.pose().last().pose();
        var vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        // The dark edge first, so it never covers a dash; then one quad per dash.
        dash(vc, m, cx, cy, sx, sy, nx, ny, 0, n, 2f, 0x70000000);
        for (int s = phase - 8; s < n; s += 8) dash(vc, m, cx, cy, sx, sy, nx, ny, Math.max(0, s), Math.min(n, s + 5), 1f, 0xE0FFFFFF);
        g.flush();
    }

    /** One quad of a link: steps {@code from}..{@code to} along it, {@code w} px each side, wound like {@link GuiGraphics#fill}. */
    private static void dash(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, float cx, float cy, float sx, float sy, float nx, float ny,
                             float from, float to, float w, int argb) {
        if (to <= from) return;
        float ax = cx + sx * from, ay = cy + sy * from, bx = cx + sx * to, by = cy + sy * to;
        float[] q = {ax + nx * w, ay + ny * w, ax - nx * w, ay - ny * w, bx - nx * w, by - ny * w, bx + nx * w, by + ny * w};
        float area = 0;
        for (int i = 0; i < 4; i++) area += q[i * 2] * q[(i * 2 + 3) % 8] - q[(i * 2 + 2) % 8] * q[i * 2 + 1];
        // fill() winds its corners with a negative signed area in screen space; the other way round would be culled.
        for (int i = 0; i < 4; i++) {
            int k = area < 0 ? i : 3 - i;
            vc.addVertex(m, q[k * 2], q[k * 2 + 1], 0).setColor(argb);
        }
    }

    private static boolean cardFailed;

    // Item icons drawn per frame at most (each is a model render); armour badges count too.
    private static final int MAX_JOB_ICONS = 96;
    // Item icons the markers drew this frame (the hover card draws within what is left).
    private int iconsUsed;

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
    /** Dev bridge only: where the joint menu opens instead of beside its joint ({x, y}), or null. */
    static int[] menuAt;

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
        if (menuAt != null) {
            x = menuAt[0];
            y = menuAt[1];
        }
        int hitsFrom = hits.size();
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int hovered = -1;
        for (int i = 0; i < LineView.jointOptions.size(); i++) {
            LineView.JointOption o = LineView.jointOptions.get(i);
            int ry = y + PAD + i * rowH, rx = x + PAD, rw = w - 2 * PAD;
            boolean over = mx >= rx && mx < rx + rw && my >= ry && my < ry + rowH - 2;
            if (over) hovered = o.type();
            int current = com.volcanicmg.mccitybuilder.planner.Plan.jointType(PlanView.jointTypes.get(com.volcanicmg.mccitybuilder.planner.Plan.cell(LineView.selectedJoint[0], LineView.selectedJoint[1])));
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
            anchor("menu_row_" + t, rx, ry, rw, rowH - 2);
            hits.add(new Hit(rx, ry, rw, rowH - 2, () -> {
                click();
                LineView.pickJoint(t);
            }, Component.translatable("mccitybuilder.edit.menu.tip")));
        }
        LineView.previewJoint(hovered);
        // Below the menu: this joint's piece turned or mirrored (kept for this joint only).
        int oy = y + h + 2, ow = (w - 2) / 2;
        panels.add(new int[]{x, oy, w, 18});
        String[] ol = {Component.translatable("mccitybuilder.edit.joint_turn").getString(), Component.translatable("mccitybuilder.edit.joint_mirror").getString()};
        for (int b = 0; b < 2; b++) {
            int bx = x + b * (ow + 2);
            boolean mirror = b == 1;
            VanillaUi.button(g, bx, oy, ow, 18, false, false, mx >= bx && mx < bx + ow && my >= oy && my < oy + 18);
            g.drawString(font, ol[b], bx + (ow - font.width(ol[b])) / 2, oy + 5, VanillaUi.INK, false);
            hits.add(new Hit(bx, oy, ow, 18, () -> {
                click();
                String why = LineView.orientSelectedJoint(mirror);
                if (why != null) toast(Component.translatable(why));
            }, Component.translatable(mirror ? "mccitybuilder.edit.joint_mirror.tip" : "mccitybuilder.edit.joint_turn.tip")));
        }
        // Below that: the alignment editor for this joint (its style, family, piece role and turn pre-selected).
        int ay = oy + 20;
        boolean overA = mx >= x && mx < x + w && my >= ay && my < ay + 18;
        panels.add(new int[]{x, ay, w, 18});
        VanillaUi.button(g, x, ay, w, 18, false, false, overA);
        String al = Component.translatable("mccitybuilder.align.adjust").getString();
        g.drawString(font, al, x + (w - font.width(al)) / 2, ay + 5, VanillaUi.INK, false);
        hits.add(new Hit(x, ay, w, 18, () -> {
            click();
            openAlignmentFor(LineView.selectedJoint);
        }, Component.translatable("mccitybuilder.align.open.tip")));
        // Drawn over the line panel when they overlap: its hits go first, behind a blocker over the whole menu.
        raise(hitsFrom, x, y, w, ay + 18 - y);
    }

    /** Opens the joint alignment editor on the planned joint at cell c: its pack, family, piece role and turn. */
    private void openAlignmentFor(int[] c) {
        for (int i = 0; i < PlanView.lines.size() && i < PlanView.linePieces.size(); i++) {
            var l = PlanView.lines.get(i);
            for (var p : PlanView.linePieces.get(i)) {
                int[] b = p.box();
                if (!p.joint() || c[0] < b[0] || c[0] > b[2] || c[1] < b[1] || c[1] > b[3]) continue;
                minecraft.setScreen(new JointEditorScreen(l.pack(), l.family(), p.role(), com.volcanicmg.mccitybuilder.planner.PieceLayout.normOrient(p.turns())));
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
            if (Math.abs(mx - d[1]) <= r && Math.abs(my - d[2]) <= r) {
                stickyId = citizenId(d[0]);
                return d;
            }
        }
        // A walking citizen slips out from under the cursor: keep it only while its marker stays near the cursor.
        if (stickyId == null) return null;
        for (int[] d : drawnMarkers) {
            if (!stickyId.equals(citizenId(d[0]))) continue;
            if (Math.abs(mx - d[1]) <= STICKY_PX && Math.abs(my - d[2]) <= STICKY_PX) return d;
        }
        stickyId = null;
        return null;
    }

    // The citizen last under the cursor, held while its marker stays within STICKY_PX (moving the cursor away drops it).
    private static Integer stickyId;
    private static final int STICKY_PX = 20;

    private static Integer citizenId(int marker) {
        return marker < OverlayView.markers.size() && OverlayView.markers.get(marker).citizen() != null ? OverlayView.markers.get(marker).citizen().getId() : null;
    }


    private static boolean editorOpen;
    // The line panel's "Needs" row (height with its gap), and the Materials panel's height without its item rows.
    private static final int MAT_ROW = 16, MAT_FIXED = PAD * 2 + 22 + 12 + 18 + 12 + 4 + 22, MAT_RH = 18;
    // The Pieces panel's height without its role rows.
    private static final int PIECES_FIXED = PAD * 2 + 20 + 2 + 12 + 6 + 12 + 20;
    private static final PieceSets.Role[] EDIT_ROLES = {PieceSets.Role.STRAIGHT, PieceSets.Role.CORNER, PieceSets.Role.TEE,
            PieceSets.Role.CROSS, PieceSets.Role.END, PieceSets.Role.GATE, PieceSets.Role.TOWER};
    // Top of the bottom-right panel stack this frame (the line panel sits lowest, the plan panel above it).
    private int stackTop;

    // The piece preview: the role it shows and the browse index among that role's pieces, its box this frame (drag / wheel act there), the large view.
    private static PieceSets.Role previewRole = PieceSets.Role.STRAIGHT;
    private static int browseIndex;
    private int[] cardBox;
    private boolean bigPreview, cardDrag;

    /** A hover tooltip with a 3D piece: {@code path} null = text only, {@code sub} null = the piece's size; {@code near} = {left edge of the panel hovered, hover y}. */
    private record Tip3d(String title, String sub, String extra, String pk, String path, int[] near) {
    }

    private Tip3d tip3d;
    /** The 3D tooltip's text this frame ("title | size"), "" when none (the dev bridge reads it). */
    static String tip3dText = "";
    /** Named boxes {x, y, w, h} of the line / pieces panel's controls this frame (the dev bridge clicks and hovers them). */
    static final java.util.Map<String, int[]> anchors = new java.util.HashMap<>();

    private static void anchor(String name, int x, int y, int w, int h) {
        anchors.put(name, new int[]{x, y, w, h});
    }

    private static String roleName(PieceSets.Role r) {
        return Component.translatable("mccitybuilder.line.role." + r.name().toLowerCase(java.util.Locale.ROOT)).getString();
    }

    /** A section caption (small grey capitals) with a rule to its right. @return the y below it */
    private int caption(GuiGraphics g, int x, int y, int w, String text) {
        if (tier > 0) {
            // Tight layouts: a rule in a 4-unit gap, no text.
            g.fill(x, y + 1, x + w, y + 2, 0xFF8B8B8B);
            return y + 4;
        }
        String t = text.toUpperCase(java.util.Locale.ROOT);
        g.drawString(font, t, x + 1, y, 0xFF6A6A6A, false);
        g.fill(x + font.width(t) + 5, y + 4, x + w, y + 5, 0xFF8B8B8B);
        return y + 12;
    }

    /**
     * A button with a pixel icon beside a centred label (6 units of side padding, 4 between icon and label); {@code on}
     * draws it green (a toggle that is on).
     *
     * @param icon  a {@link PanelIcons} name, or null
     * @param ink   label colour, 0 = by state
     */
    private void iconButton(GuiGraphics g, int x, int y, int w, int h, String icon, String label, boolean on, boolean off, boolean over, int ink) {
        VanillaUi.button(g, x, y, w, h, false, off, over);
        if (on) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3C8A3C);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF7CCB7C);
            g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFF7CCB7C);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF245C2A);
            g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, 0xFF245C2A);
        }
        int is = icon == null ? 0 : PanelIcons.size(icon), gap = is > 0 && !label.isEmpty() ? 4 : 0, room = w - (w < 50 ? 8 : 12) - is - gap;
        String t = label.isEmpty() || font.width(label) <= room ? label : font.plainSubstrByWidth(label, Math.max(0, room - font.width("…"))) + "…";
        int cx = x + (w - is - gap - font.width(t)) / 2;
        if (is > 0) {
            PanelIcons.draw(g, icon, cx, y + (h - is) / 2, 1, off);
            cx += is + gap;
        }
        if (!t.isEmpty()) g.drawString(font, t, cx, y + (h - 8) / 2, ink != 0 ? ink : off ? VanillaUi.INK_DISABLED : 0xFFFFFFFF, !off);
    }

    /** A picker with its label to the left. @return the y below it */
    private int labeledPicker(GuiGraphics g, int x, int y, int w, String lead, String value, int mx, int my, java.util.function.IntConsumer step, Component tip) {
        g.drawString(font, lead, x + 1, y + 4, 0xFF555555, false);
        return picker(g, x + 42, y, w - 42, value, mx, my, step, tip);
    }

    /** The piece-type roles the tool's tile row shows: the core ones always (dimmed when the set lacks them), the rest when the set has them. */
    private static List<PieceSets.Role> tileRoles(String tool, PieceSets.Family f, String pk) {
        List<PieceSets.Role> out = new ArrayList<>();
        if (!"wall".equals(tool)) return List.of(PieceSets.Role.STRAIGHT, PieceSets.Role.CORNER, PieceSets.Role.TEE, PieceSets.Role.CROSS, PieceSets.Role.END);
        for (PieceSets.Role r : new PieceSets.Role[]{PieceSets.Role.STRAIGHT, PieceSets.Role.CORNER, PieceSets.Role.INNER, PieceSets.Role.TEE, PieceSets.Role.CROSS,
                PieceSets.Role.TOWER, PieceSets.Role.END, PieceSets.Role.GATE}) {
            boolean core = r == PieceSets.Role.STRAIGHT || r == PieceSets.Role.CORNER || r == PieceSets.Role.TOWER || r == PieceSets.Role.END || r == PieceSets.Role.GATE;
            if (core || f != null && !PieceCatalog.groups(pk, f, r).isEmpty()) out.add(r);
        }
        return out;
    }

    /**
     * The road / wall panel, in three sections: PIECES (set, level or variant, a 3D preview of the selected piece type
     * with piece-type tiles under it, and a Gate toggle for walls), SHAPE (height, joints, Flip, Fill gaps and the
     * segment's numbers) and BUILD (mode, builder, Undo / Remove / Clear / Done / Build). A Pieces button beside the
     * help one opens the {@link #piecesPanel}. Edit mode adds the Picks row.
     */
    private void linePanel(GuiGraphics g, int mx, int my) {
        lit = true;
        // This panel is drawn over the queue panel when they overlap: its hits (and a blocker over it) go first.
        List<Hit> earlier = new ArrayList<>(hits);
        hits.clear();
        try {
            linePanelBody(g, mx, my);
        } finally {
            lit = false;
            pickGap = 4;
            if (lineBlock != null) hits.add(lineBlock);
            hits.addAll(earlier);
        }
    }

    // Set while the line / Pieces panels draw: their pickers use white shadowed arrows like vanilla buttons, a row gap of pickGap.
    private boolean lit;
    private int pickGap = 4;
    // The layout tier of this frame (see layout()): 0 full ... 3 tightest.
    private int tier;
    /** The last line panel layout {x, y, w, h, queue bottom, preview height, tier} (the dev bridge reads it). */
    static int[] layoutDbg = new int[7];

    /** The line panel's layout, decided once per frame: its tier of chrome, preview height (0 = none) and panel height. */
    private record Lay(int tier, int ph, int h) {
    }

    /**
     * @return the panel's height without the preview at a tier: 0 full, 1 captions shrink to a gap, 2 tighter rows, 3 no
     * info line with Height / Joints and Mode / Builder sharing rows, 4 the Picks row joins the Edit row, the wall's Gate
     * is a toggle at the end of the tiles and the level picker shares the set's row
     */
    private static int baseH(int t, boolean picks, boolean lvl, boolean wall, boolean pickMode, boolean pickBuilder) {
        int gb = t < 2 ? 4 : 2, row = 16 + gb, bt = 20 + gb, cap = t == 0 ? 12 : 4, info = t == 0 ? 16 : t < 3 ? 12 : 0;
        return 2 * PAD + 2 * bt + (picks && t < 4 ? bt : 0) + cap + row + (lvl && t < 4 ? row : 0) + 22 + gb + (wall && t < 4 ? bt : 0) + cap + (t < 3 ? 2 * row : row) + bt + info + cap
                + (t >= 3 && pickMode && pickBuilder ? row : (pickMode ? row : 0) + (pickBuilder ? row : 0)) + (MaterialsView.shown() ? MAT_ROW : 0) + 20;
    }

    /**
     * Picks the tier and preview height for {@code avail} units: the info line (tiers 0-2) is kept before anything
     * else goes, so tiers 0-2 take a preview of 60 and then 48, then tiers 3-4 likewise; with no room for a preview
     * the lightest tier of 3-4 that fits without one (else tier 4).
     */
    private static Lay layout(int avail, boolean picks, boolean lvl, boolean wall, boolean pickMode, boolean pickBuilder) {
        int[][] passes = {{0, 2, 60}, {0, 2, 48}, {3, 4, 60}, {3, 4, 48}};
        for (int[] p : passes)
            for (int t = p[0]; t <= p[1]; t++) {
                int gb = t < 2 ? 4 : 2, base = baseH(t, picks, lvl, wall, pickMode, pickBuilder), ph = Math.min(100, avail - base - gb);
                if (ph >= p[2]) return new Lay(t, ph, base + ph + gb);
            }
        for (int t = 3; t <= 4; t++) {
            int base = baseH(t, picks, lvl, wall, pickMode, pickBuilder);
            if (base <= avail || t == 4) return new Lay(t, 0, base);
        }
        return null;
    }

    /** @return the large preview window's box */
    private int[] bigRect() {
        int bw = Math.min(width - 40, 320), bh = Math.min(height - 60, 260);
        return new int[]{(width - bw) / 2, (height - bh) / 2, bw, bh};
    }

    /** @return whether a tooltip for a control at the mouse is hidden by a panel drawn above it (the large preview; the Pieces panel for line-panel controls) */
    private boolean covered(int mx, int my, boolean fromPieces) {
        if (bigDrawnPrev != null) {
            int[] b = bigDrawnPrev;
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) return true;
        }
        return !fromPieces && piecesBox != null && mx >= piecesBox[0] && mx < piecesBox[0] + piecesBox[2] && my >= piecesBox[1] && my < piecesBox[1] + piecesBox[3];
    }

    // The Pieces panel's box this frame (drawn after the line panel), or null; the large preview window as drawn this / last frame, or null.
    private int[] piecesBox, bigDrawn, bigDrawnPrev;
    // The line panel's and the large preview's blocker hits this frame (the card they own may be dragged).
    private Hit lineBlock, bigBlock;

    /** A hit that takes the click and stops hover tooltips reaching what is under it. */
    private static final Runnable BLOCK = () -> {
    };

    /**
     * The pieces a line's set uses per role at its level / variant: one per group (a single group except for
     * straights). A role's list is empty exactly when its group list is, so tiles and the Pieces panel agree.
     */
    private static Map<PieceSets.Role, List<PieceSets.Piece>> rolePieces(String pk, PieceSets.Family f, Plan.Line l) {
        Map<PieceSets.Role, List<PieceSets.Piece>> per = new java.util.EnumMap<>(PieceSets.Role.class);
        if (f == null) return per;
        for (PieceSets.Role r : PieceSets.BROWSE) {
            List<PieceSets.Piece> ps = new ArrayList<>();
            for (PieceSets.Group g : PieceCatalog.groups(pk, f, r)) {
                ps.add(g.pick(g.levels() ? l.level() : Math.max(0, l.variant()), 0));
                if (r != PieceSets.Role.STRAIGHT) break;
            }
            per.put(r, ps);
        }
        return per;
    }

    private void linePanelBody(GuiGraphics g, int mx, int my) {
        stackTop = height - 4;
        cardBox = null;
        tip3d = null;
        tip3dText = "";
        piecesBox = null;
        matListBox = null;
        matBlock = null;
        bigDrawnPrev = bigDrawn;
        bigDrawn = null;
        lineBlock = bigBlock = null;
        if (!LineView.active() && PlanView.selectedLine() == null) {
            editorOpen = false;
            // Open from the plan panel's button: that panel shows it.
            if (!PlanView.on) MaterialsView.open = false;
            bigPreview = false;
            return;
        }
        MaterialsView.poll();
        Plan.Line sel = PlanView.selectedLine();
        String tool = sel != null ? sel.tool() : LineView.tool, pk = sel != null ? sel.pack() : pack();
        String status = sel != null ? null : LineView.status();
        String fam = sel != null ? sel.family() : status == null ? LineView.family() : null;
        boolean wall = "wall".equals(tool), pickBuilder = !Planner.instant(), pickMode = Planner.creative(), picks = LineView.edit;
        int[] li = LineView.levelInfo();
        boolean lvl = li[0] > 1;
        // How many of the family's joints don't fit (worked out a case per frame, cached): the badge and Fix joints (N).
        PieceSets.Family jf = status == null && fam != null ? PieceCatalog.familyOf(pk, fam) : null;
        JointCount.Result jc = jf == null ? null : JointCheck.of(pk, jf.tool() + ":" + jf.key(), sel != null ? sel.level() : LineView.level);
        // Layout, once: the room under the queue panel (and above the plan panel when it shows).
        int reserve = PlanView.on ? planPanelH() + 4 : 0;
        Lay lay = layout(height - 4 - (queueBottom + 4) - reserve, picks, lvl, wall, pickMode, pickBuilder);
        tier = lay.tier();
        int gb = tier < 2 ? 4 : 2, ph = lay.ph();
        pickGap = gb;
        int w = 230, h = status != null ? 40 : lay.h(), x = Math.max(4 + PALETTE_W + 4, width - 4 - guideRoom() - 4 - w);
        // Under the queue panel when it fits; else bottom-anchored over it (its hits come first below), never off the screen.
        int y = height - h - 4 >= queueBottom + 4 ? height - h - 4 : Math.max(4, height - h - 4);
        if (ph == 0) bigPreview = false;
        if (status == null) layoutDbg = new int[]{x, y, w, h, queueBottom, ph, tier};
        stackTop = y - 4;
        panels.add(new int[]{x, y, w, h});
        linePanelBox = new int[]{x, y, w, h};
        lineBlock = new Hit(x, y, w, h, BLOCK, null);
        int rs = Math.max(18, Math.min(22, (height - 8 - PIECES_FIXED) / EDIT_ROLES.length)), eh = PIECES_FIXED + EDIT_ROLES.length * rs;
        // One side panel at a time: Materials, else Pieces.
        boolean matOn = MaterialsView.open && MaterialsView.shown() && status == null;
        int mh = matHeight();
        if (matOn) piecesBox = new int[]{Math.max(4, x - 4 - w), Math.max(4, Math.min(y + h - mh, height - 4 - mh)), w, mh};
        else if (editorOpen && fam != null && status == null) piecesBox = new int[]{Math.max(4, x - 4 - w), Math.max(4, Math.min(y + h - eh, height - 4 - eh)), w, eh};
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, iw = w - 2 * PAD, hy = y + PAD;
        PanelIcons.draw(g, "tool_" + tool, ix, hy + 2, 1, false);
        String title = Component.translatable(sel != null ? "mccitybuilder.line.selected." + tool : LineView.edit ? "mccitybuilder.line.edit_title." + tool : "mccitybuilder.line.hdr." + tool).getString();
        if (status != null) {
            g.drawString(font, title, ix + 20, hy + 6, VanillaUi.LABEL, false);
            g.drawString(font, Component.translatable("mccitybuilder.line.status." + status + "." + tool, pk), ix + 1, hy + 22, 0xFFA02020, false);
            return;
        }
        // Header: [?] the help screen, [Pieces] the pieces panel (right to left).
        String pl = Component.translatable("mccitybuilder.line.pieces").getString();
        int pw = 12 + 16 + 4 + font.width(pl), px = ix + iw - pw, qx = px - 22;
        g.drawString(font, font.plainSubstrByWidth(title, qx - ix - 24), ix + 20, hy + 6, VanillaUi.LABEL, false);
        boolean overQ = mx >= qx && mx < qx + 20 && my >= hy && my < hy + 20;
        VanillaUi.button(g, qx, hy, 20, 20, false, false, overQ);
        g.drawString(font, "?", qx + 7, hy + 6, 0xFFFFFFFF, true);
        anchor("help", qx, hy, 20, 20);
        hits.add(new Hit(qx, hy, 20, 20, () -> {
            click();
            minecraft.setScreen(new PonderScreen());
        }, Component.translatable("mccitybuilder.help.button")));
        iconButton(g, px, hy, pw, 20, "pieces", pl, editorOpen, false, mx >= px && mx < px + pw && my >= hy && my < hy + 20, 0);
        anchor("pieces", px, hy, pw, 20);
        if (jc != null && !jc.badge().isEmpty()) {
            // A small amber badge on the button's corner: the joints that don't fit.
            String bt = jc.badge();
            int bwid = font.width(bt) + 6, bx0 = px + pw - bwid + 3, by0 = hy - 4;
            g.fill(bx0 - 1, by0 - 1, bx0 + bwid + 1, by0 + 11, 0xFF1C1C1C);
            g.fill(bx0, by0, bx0 + bwid, by0 + 10, 0xFFE0A020);
            g.drawString(font, bt, bx0 + 3, by0 + 1, 0xFF1C1C1C, false);
            anchor("joints_badge", bx0, by0, bwid, 10);
        }
        hits.add(new Hit(px, hy, pw, 20, () -> {
            click();
            editorOpen = !editorOpen;
            if (editorOpen) MaterialsView.open = false;
        }, Component.translatable("mccitybuilder.line.pieces.tip")));
        // Edit mode: a full-width button, green while on.
        int iy = hy + 20 + gb;
        int ew = picks && tier >= 4 ? iw - 48 : iw;
        iconButton(g, ix, iy, ew, 20, "pencil", Component.translatable((LineView.edit ? "mccitybuilder.line.editing_btn." : "mccitybuilder.line.edit_btn.") + tool).getString(),
                LineView.edit, false, mx >= ix && mx < ix + ew && my >= iy && my < iy + 20, 0);
        anchor("edit", ix, iy, ew, 20);
        hits.add(new Hit(ix, iy, ew, 20, () -> {
            click();
            LineView.toggleEdit();
        }, Component.translatable("mccitybuilder.line.edit.tip")));
        if (picks && tier >= 4)
            for (int i = 0; i < 2; i++) {
                // Tightest: the Picks as two small icon toggles at the end of the Edit row.
                int px2 = ix + iw - 46 + i * 24;
                boolean whole = i == 1;
                iconButton(g, px2, iy, 22, 20, whole ? "tool_" + tool : "segment", "", LineView.wholeMode == whole, false, mx >= px2 && mx < px2 + 22 && my >= iy && my < iy + 20, 0);
                anchor(whole ? "pick_whole" : "pick_segment", px2, iy, 22, 20);
                hits.add(new Hit(px2, iy, 22, 20, () -> {
                    click();
                    LineView.wholeMode = whole;
                }, Component.translatable(whole ? "mccitybuilder.line.pick_whole." + tool : "mccitybuilder.line.pick_segment")));
            }
        iy += 20 + gb;
        // Picks (edit mode only): what a click selects.
        if (picks && tier < 4) {
            g.drawString(font, Component.translatable("mccitybuilder.line.picks"), ix + 1, iy + 6, 0xFF555555, false);
            int bx = ix + 42, bw = (ix + iw - bx - 2) / 2;
            for (int i = 0; i < 2; i++) {
                int px2 = bx + i * (bw + 2);
                boolean whole = i == 1;
                iconButton(g, px2, iy, bw, 20, whole ? "tool_" + tool : "segment", Component.translatable(whole ? "mccitybuilder.line.pick_whole." + tool : "mccitybuilder.line.pick_segment").getString(),
                        LineView.wholeMode == whole, false, mx >= px2 && mx < px2 + bw && my >= iy && my < iy + 20, 0);
                anchor(whole ? "pick_whole" : "pick_segment", px2, iy, bw, 20);
                hits.add(new Hit(px2, iy, bw, 20, () -> {
                    click();
                    LineView.wholeMode = whole;
                }, Component.translatable("mccitybuilder.line.select.tip")));
            }
            iy += 20 + gb;
        }
        // PIECES: set, level / variant, the preview and its piece-type tiles, the wall's Gate.
        iy = caption(g, ix, iy, iw, pl);
        boolean famMissing = sel != null && PieceCatalog.familyMissing(sel);
        int rowY = iy, sw = lvl && tier >= 4 ? iw * 11 / 20 : iw;
        iy = picker(g, ix, iy, sw, Component.translatable(famMissing ? "mccitybuilder.line.family.missing" : "mccitybuilder.line.family",
                        fam == null ? "-" : fam.substring(fam.indexOf(':') + 1)).getString(),
                mx, my, d -> {
                    LineView.cycleFamily(d);
                    click();
                }, Component.translatable("mccitybuilder.line.family.tip"));
        if (lvl) {
            int lv = sel != null ? sel.level() : LineView.level, va = sel != null ? sel.variant() : LineView.variant;
            String lvText = li[1] == 1 ? Component.translatable("mccitybuilder.line.level", Math.min(lv, li[0]), li[0]).getString()
                    : va < 0 ? Component.translatable("mccitybuilder.line.variant_random", li[0]).getString() : Component.translatable("mccitybuilder.line.variant", va + 1, li[0]).getString();
            iy = picker(g, tier >= 4 ? ix + sw + 2 : ix, tier >= 4 ? rowY : iy, tier >= 4 ? iw - sw - 2 : iw, lvText, mx, my, d -> {
                LineView.cycleLevel(d, li[0], li[1] == 1);
                click();
            }, Component.translatable("mccitybuilder.line.level.tip"));
        }
        var line = sel != null ? sel : LineView.next(new int[]{0, 0}, new int[]{0, 0});
        var per = rolePieces(pk, fam == null ? null : PieceCatalog.familyOf(pk, fam), line);
        // The selected role must have pieces, else the first one that has.
        if (per.getOrDefault(previewRole, List.of()).isEmpty())
            for (PieceSets.Role r : tileRoles(tool, fam == null ? null : PieceCatalog.familyOf(pk, fam), pk))
                if (!per.getOrDefault(r, List.of()).isEmpty()) {
                    previewRole = r;
                    browseIndex = 0;
                    break;
                }
        // Tooltips go left of the panels: of the Pieces panel when it is open.
        int near = piecesBox != null ? piecesBox[0] : x;
        if (ph > 0) iy = pieceCard(g, ix, iy, iw, ph, gb, mx, my, pk, per, near);
        int tilesY = iy;
        iy = pieceTiles(g, ix, iy, wall && tier >= 4 ? iw - 24 : iw, gb, mx, my, tool, pk, fam, per, near);
        if (wall && tier >= 4) {
            // Tightest: the Gate toggle at the end of the tile row.
            int gx = ix + iw - 22;
            iconButton(g, gx, tilesY, 22, 22, "gate", "", sel != null ? sel.gate() : LineView.gate, false, mx >= gx && mx < gx + 22 && my >= tilesY && my < tilesY + 22, 0);
            anchor("gate", gx, tilesY, 22, 22);
            hits.add(new Hit(gx, tilesY, 22, 22, () -> {
                click();
                LineView.toggleGate();
            }, Component.translatable("mccitybuilder.line.gate.tip")));
        } else if (wall) {
            boolean gateOn = sel != null ? sel.gate() : LineView.gate;
            iconButton(g, ix, iy, iw, 20, "gate", Component.translatable("mccitybuilder.line.gate").getString(), gateOn, false, mx >= ix && mx < ix + iw && my >= iy && my < iy + 20, 0);
            anchor("gate", ix, iy, iw, 20);
            hits.add(new Hit(ix, iy, iw, 20, () -> {
                click();
                LineView.toggleGate();
            }, Component.translatable("mccitybuilder.line.gate.tip")));
            iy += 20 + gb;
        }
        // SHAPE: height and joints, Flip and Fill gaps side by side, then the segment's numbers.
        iy = caption(g, ix, iy, iw, Component.translatable("mccitybuilder.line.sec.shape").getString());
        int mode = sel != null ? sel.height() : LineView.heightMode, hdy = sel != null ? sel.dy() : LineView.dy;
        int flatY = sel != null ? selectedFlatY() : LineView.liveY;
        String hText = Component.translatable("mccitybuilder.line.height_s." + mode).getString()
                + (flatY != Integer.MIN_VALUE ? "  " + Component.translatable("mccitybuilder.line.height.y", flatY, (hdy >= 0 ? "+" : "") + hdy).getString()
                : hdy != 0 ? "  " + (hdy > 0 ? "+" : "") + hdy : "");
        java.util.function.IntConsumer stepH = d -> {
            LineView.cycleHeight(d);
            click();
        }, stepJ = d -> {
            LineView.cycleJoint(d);
            click();
        };
        // Joints: the selected joint's type (edit mode), else the default for new joints.
        int jt = LineView.selectedJoint != null ? Plan.jointType(PlanView.jointTypes.get(Plan.cell(LineView.selectedJoint[0], LineView.selectedJoint[1])))
                : LineView.jointDefault;
        String jText = Component.translatable("mccitybuilder.line.joint." + jt).getString();
        if (tier < 3) {
            iy = labeledPicker(g, ix, iy, iw, Component.translatable("mccitybuilder.line.height_label").getString(), hText, mx, my, stepH, Component.translatable("mccitybuilder.line.height.tip"));
            iy = labeledPicker(g, ix, iy, iw, Component.translatable(LineView.selectedJoint != null ? LineView.selectedIsEnd() ? "mccitybuilder.line.joint_label_end" : "mccitybuilder.line.joint_label_this"
                    : "mccitybuilder.line.joints_label").getString(), jText, mx, my, stepJ, Component.translatable("mccitybuilder.line.joint.tip"));
        } else {
            int hh = iw * 3 / 5;
            picker(g, ix, iy, hh, hText, mx, my, stepH, Component.translatable("mccitybuilder.line.height.tip"));
            iy = picker(g, ix + hh + 2, iy, iw - hh - 2, Component.translatable(LineView.selectedJoint != null ? LineView.selectedIsEnd() ? "mccitybuilder.line.joint.this_end" : "mccitybuilder.line.joint.this"
                    : "mccitybuilder.line.joint.new", jText).getString(), mx, my, stepJ, Component.translatable("mccitybuilder.line.joint.tip"));
        }
        boolean canFill = !LineView.fillTargets().isEmpty(), flipOn = LineView.flipShown();
        // Roads add Route (auto-route between two clicks) to the row: three buttons, icons only when narrow.
        int nb = "road".equals(tool) ? 3 : 2, hw = (iw - 2 * (nb - 1)) / nb;
        String[] bIcon = {"flip", "fill", "route"}, bKey = {"mccitybuilder.line.flip", "mccitybuilder.line.fill.btn", "mccitybuilder.route.btn"},
                bTip = {"mccitybuilder.line.flip.tip", "mccitybuilder.line.fill.tip", "mccitybuilder.route.tip"};
        // Labels on all of them, or (too narrow for any one) icons only on all of them.
        boolean labels = true;
        for (int i = 0; i < nb; i++) labels &= font.width(Component.translatable(bKey[i]).getString()) + 16 + 4 + 12 <= hw;
        for (int i = 0; i < nb; i++) {
            int bx = ix + i * (hw + 2), bwid = i == nb - 1 ? iw - (nb - 1) * (hw + 2) : hw;
            boolean off = i == 1 && !canFill;
            String label = labels ? Component.translatable(bKey[i]).getString() : "";
            iconButton(g, bx, iy, bwid, 20, bIcon[i], label, i == 0 && flipOn || i == 2 && RouteView.on, off, mx >= bx && mx < bx + bwid && my >= iy && my < iy + 20, 0);
            anchor(bIcon[i], bx, iy, bwid, 20);
            int fi = i;
            hits.add(new Hit(bx, iy, bwid, 20, () -> {
                if (off) return;
                click();
                if (fi == 0) LineView.toggleFlip();
                else if (fi == 1) LineView.fillGaps();
                else RouteView.toggle();
            }, Component.translatable(bTip[i])));
        }
        iy += 20 + gb;
        if (tier < 3) {
            // The segment's numbers: the live one and the chain, or the selected segment.
            String info;
            if (sel != null) {
                int idx = PlanView.selectedLine;
                var ps = idx < PlanView.linePieces.size() ? PlanView.linePieces.get(idx) : java.util.List.<PieceCatalog.Placed>of();
                int sh = idx < PlanView.lineShort.size() ? PlanView.lineShort.get(idx) : 0;
                info = Component.translatable("mccitybuilder.line.cost_selected", com.volcanicmg.mccitybuilder.planner.LineSnap.length(new int[]{sel.x0(), sel.z0()},
                        new int[]{sel.x1(), sel.z1()}) + 1, ps.size(), LineView.cost(ps), sh).getString();
            } else if (LineView.edit) info = Component.translatable(LineView.selectedJoint != null ? "mccitybuilder.line.edit_joint" : "mccitybuilder.line.edit_picks_hint." + tool).getString();
            else if (LineView.hoverLine >= 0) info = Component.translatable(LineView.hoverBuilt() ? "mccitybuilder.line.hover_built" : "mccitybuilder.line.hover_remove").getString();
            else if (LineView.start == null) info = Component.translatable("mccitybuilder.line.click_start").getString();
            else {
                int chainPieces = 0;
                for (var l : LineView.chain) {
                    int idx = PlanView.lines.indexOf(l);
                    if (idx >= 0 && idx < PlanView.linePieces.size()) chainPieces += PlanView.linePieces.get(idx).size();
                }
                info = Component.translatable("mccitybuilder.line.chain", LineView.chain.size(), chainPieces).getString();
                // The live segment's gap: why and how to fix it (the cursor label stays short).
                if (LineView.liveShort > 0) info = Component.translatable("mccitybuilder.line.short_fix", LineView.liveShort).getString();
            }
            if (RouteView.on) info = RouteView.status().getString();
            Component prog = PlanView.progress();
            if (prog != null) info = prog.getString();
            // Too long for one line: cut with an ellipsis, the whole text on hover.
            if (font.width(info) > iw - 2) {
                Component full = Component.literal(info);
                hits.add(new Hit(ix, iy - 2, iw, 12, () -> {
                }, full));
                info = font.plainSubstrByWidth(info, iw - 2 - font.width("…")) + "…";
            }
            g.drawString(font, info, ix + 1, iy, 0xFF555555, false);
            iy += tier == 0 ? 16 : 12;
        }
        // BUILD: mode, builder, then the footer.
        iy = caption(g, ix, iy, iw, Component.translatable("mccitybuilder.line.sec.build").getString());
        if (tier >= 3 && pickMode && pickBuilder) {
            // Tightest: Mode and Builder side by side, without their labels.
            int mw = iw * 11 / 20;
            buildModePicker(g, ix, iy, mw, mx, my, "");
            iy = builderPicker(g, ix + mw + 2, iy, iw - mw - 2, mx, my, true, "");
        } else {
            if (pickMode) iy = buildModePicker(g, ix, iy, iw, mx, my, Component.translatable("mccitybuilder.line.mode_label").getString());
            if (pickBuilder) iy = builderPicker(g, ix, iy, iw, mx, my, true, Component.translatable("mccitybuilder.line.builder_label").getString());
        }
        if (MaterialsView.shown()) iy = materialsRow(g, ix, iy, iw, mx, my);
        boolean server = Planner.serverInstant();
        String[] bkeys = {"undo", "remove", "clear", "done", "build"};
        boolean canRemove = LineView.hoverLine >= 0 || sel != null || !LineView.sel.isEmpty();
        boolean[] off = {!LineView.canUndo(), !canRemove, PlanView.lines.stream().noneMatch(l -> l.tool().equals(tool) && !PlanView.locked(l)),
                LineView.start == null, PlanView.lines.isEmpty() && PlanView.entries.isEmpty()};
        Runnable[] run = {LineView::undo, () -> {
            if (LineView.removeHovered()) toast(Component.translatable("mccitybuilder.line.removed"));
        }, LineView::clearTool, LineView::finishChain, () -> toast(PlanView.commit(false))};
        int[] fw = {22, 46, 42, 36, iw - 22 - 46 - 42 - 36 - 8};
        int bx = ix;
        for (int i = 0; i < 5; i++) {
            int bw = fw[i];
            boolean over = mx >= bx && mx < bx + bw && my >= iy && my < iy + 20;
            // Clear waiting for its second click: red "Sure?" and the time left draining along the bottom.
            float arm = i == 2 && !off[i] ? LineView.clearArm() : 0;
            String bl = i == 0 ? "" : Component.translatable(arm > 0 ? "mccitybuilder.line.clear_sure" : "mccitybuilder.line." + bkeys[i]).getString();
            iconButton(g, bx, iy, bw, 20, i == 0 ? "undo" : i == 4 ? "hammer" : null, bl, i == 4 && !off[i], off[i], over, arm > 0 ? 0xFFFF5555 : 0);
            if (arm > 0) g.fill(bx + 2, iy + 16, bx + 2 + Math.round((bw - 4) * arm), iy + 18, 0xFFFF5555);
            anchor(bkeys[i], bx, iy, bw, 20);
            int fi = i;
            hits.add(new Hit(bx, iy, bw, 20, () -> {
                if (off[fi]) return;
                click();
                run[fi].run();
            }, Component.translatable(i == 4 && !server ? "mccitybuilder.line.needs_server" : i == 2 ? "mccitybuilder.line.clear.tip." + tool : "mccitybuilder.line." + bkeys[i] + ".tip")));
            bx += bw + 2;
        }
        if (piecesBox != null && matOn) materialsPanel(g, piecesBox[0], piecesBox[1], w, mh, mx, my);
        else if (piecesBox != null) piecesPanel(g, piecesBox[0], piecesBox[1], w, eh, pk, fam, tool, mx, my, jc);
    }

    /** @return the Materials panel's height: its rows (3 to 9) as the screen allows */
    private int matHeight() {
        int mrows = Math.max(2, Math.min(9, (height - 8 - MAT_FIXED) / MAT_RH));
        return MAT_FIXED + MAT_RH * Math.max(3, Math.min(mrows, MaterialsView.view(MaterialsView.tab).rows().size()));
    }

    private static String num(int n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    /** The amber / green ink of a shortfall line: bright on the dark row slot, dark on the panel grey. */
    private static int shortInk(int missing, boolean dark) {
        return missing > 0 ? dark ? 0xFFFFB030 : 0xFFB36B00 : dark ? 0xFF55DD55 : 0xFF2E7D32;
    }

    /** The line panel's "Needs 2,140 items · short 340" row above the footer: amber when short, green when covered; a click opens the Materials panel. @return the y below it */
    private int materialsRow(GuiGraphics g, int x, int y, int w, int mx, int my) {
        Materials.View v = MaterialsView.view(Materials.Tab.ALL);
        String t = v.need() == 0 ? Component.translatable(MaterialsView.loading() ? "mccitybuilder.materials.loading" : "mccitybuilder.materials.row_none").getString()
                : Component.translatable(v.missing() > 0 ? "mccitybuilder.materials.row_short" : "mccitybuilder.materials.row_ok", num(v.need()), num(v.missing())).getString()
                + (MaterialsView.loading() ? " …" : "");
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 14;
        VanillaUi.slot(g, x, y, w, 14);
        if (over || MaterialsView.open) g.fill(x + 1, y + 1, x + w - 1, y + 13, over ? 0x40FFFFFF : 0x20FFFFFF);
        g.drawString(font, font.plainSubstrByWidth(t, w - 16), x + 4, y + 3, v.need() == 0 ? 0xFFB0B0B0 : shortInk(v.missing(), true), false);
        g.drawString(font, MaterialsView.open ? "<" : ">", x + w - 10, y + 3, 0xFF555555, false);
        anchor("materials", x, y, w, 14);
        hits.add(new Hit(x, y, w, 14, () -> {
            click();
            MaterialsView.open = !MaterialsView.open;
            if (MaterialsView.open) editorOpen = false;
        }, Component.translatable("mccitybuilder.materials.row.tip")));
        return y + MAT_ROW;
    }

    /**
     * The Materials panel (beside the line panel, where the Pieces panel opens): tabs All / Roads and walls / Huts, a
     * row per item with its icon, need, have and short (red), scrolling with the wheel, and a refresh button. Its hits go
     * to the front of {@code hits} (it is drawn over the line panel) behind a blocker over the whole panel.
     */
    private void materialsPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int before = hits.size();
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, iw = w - 2 * PAD, hy = y + PAD;
        PanelIcons.draw(g, "hammer", ix, hy + 2, 1, false);
        g.drawString(font, Component.translatable("mccitybuilder.materials.title"), ix + 20, hy + 6, VanillaUi.LABEL, false);
        int cx = ix + iw - 20, rx = cx - 22;
        VanillaUi.button(g, cx, hy, 20, 20, false, false, mx >= cx && mx < cx + 20 && my >= hy && my < hy + 20);
        g.drawString(font, "x", cx + 7, hy + 6, 0xFFFFFFFF, true);
        anchor("materials_close", cx, hy, 20, 20);
        hits.add(new Hit(cx, hy, 20, 20, () -> {
            click();
            MaterialsView.open = false;
        }, Component.translatable("mccitybuilder.materials.close.tip")));
        boolean canAsk = MaterialsView.serverKnows();
        VanillaUi.button(g, rx, hy, 20, 20, false, !canAsk, canAsk && mx >= rx && mx < rx + 20 && my >= hy && my < hy + 20);
        PanelIcons.draw(g, "refresh", rx + 2, hy + 2, 1, !canAsk);
        anchor("materials_refresh", rx, hy, 20, 20);
        hits.add(new Hit(rx, hy, 20, 20, () -> {
            if (!canAsk) return;
            click();
            MaterialsView.forceRefresh();
        }, Component.translatable("mccitybuilder.materials.refresh.tip")));
        Materials.View v = MaterialsView.view(MaterialsView.tab);
        int iy = hy + 22;
        String sum = v.need() == 0 ? Component.translatable(MaterialsView.loading() ? "mccitybuilder.materials.loading" : "mccitybuilder.materials.row_none").getString()
                : Component.translatable(v.missing() > 0 ? "mccitybuilder.materials.row_short" : "mccitybuilder.materials.row_ok", num(v.need()), num(v.missing())).getString();
        g.drawString(font, font.plainSubstrByWidth(sum, iw - 2), ix + 1, iy + 1, v.need() == 0 ? 0xFF6A6A6A : shortInk(v.missing(), false), false);
        iy += 12;
        // Tabs: All, Roads & walls, Huts (the chosen one an inset).
        Materials.Tab[] tabs = Materials.Tab.values();
        int[] tw = {44, iw - 44 - 52 - 4, 52};
        int tx = ix;
        for (int i = 0; i < tabs.length; i++) {
            Materials.Tab t = tabs[i];
            boolean on = MaterialsView.tab == t;
            VanillaUi.button(g, tx, iy, tw[i], 16, on, false, mx >= tx && mx < tx + tw[i] && my >= iy && my < iy + 16);
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.materials.tab." + t.name().toLowerCase(java.util.Locale.ROOT)).getString(), tx, iy, tw[i], 16,
                    VanillaUi.ink(on, false));
            anchor("materials_tab_" + t.name().toLowerCase(java.util.Locale.ROOT), tx, iy, tw[i], 16);
            hits.add(new Hit(tx, iy, tw[i], 16, () -> {
                click();
                MaterialsView.tab = t;
                MaterialsView.scroll = 0;
            }, null));
            tx += tw[i] + 2;
        }
        iy += 18;
        // Columns: right edges of Short, Have, Need (a scroll bar takes the last 6 units).
        int colW = 38, shortR = ix + iw - 8, haveR = shortR - colW, needR = haveR - colW, nameX = ix + 22;
        String[] heads = {"item", "need", "have", "short"};
        int[] hr = {nameX + font.width(Component.translatable("mccitybuilder.materials.col.item")), needR, haveR, shortR};
        for (int i = 0; i < heads.length; i++) {
            String t = Component.translatable("mccitybuilder.materials.col." + heads[i]).getString();
            g.drawString(font, t, hr[i] - font.width(t), iy, 0xFF6A6A6A, false);
        }
        g.fill(ix, iy + 10, ix + iw, iy + 11, 0xFF8B8B8B);
        iy += 12;
        int vis = (y + h - 4 - 22 - iy) / MAT_RH;
        List<Materials.Row> rows = v.rows();
        MaterialsView.scroll = Math.max(0, Math.min(MaterialsView.scroll, rows.size() - vis));
        matListBox = new int[]{ix, iy, iw, vis * MAT_RH};
        if (rows.isEmpty()) g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.materials.empty").getString(), iw - 2), ix + 1, iy + 4, 0xFF6A6A6A, false);
        for (int i = 0; i < vis && i + MaterialsView.scroll < rows.size(); i++) {
            Materials.Row r = rows.get(i + MaterialsView.scroll);
            int ry = iy + i * MAT_RH;
            ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(r.item())));
            g.renderItem(st, ix + 1, ry + 1);
            if (my >= ry && my < ry + MAT_RH && mx >= ix && mx < ix + iw) g.fill(ix, ry, ix + iw - 7, ry + MAT_RH, 0x30FFFFFF);
            g.drawString(font, font.plainSubstrByWidth(st.getHoverName().getString(), needR - font.width(num(r.need())) - nameX - 4), nameX, ry + 5, VanillaUi.LABEL, false);
            int[] vals = {r.need(), r.have(), r.missing()}, rights = {needR, haveR, shortR};
            for (int k = 0; k < 3; k++) {
                String t = k == 2 && vals[k] == 0 ? "-" : num(vals[k]);
                g.drawString(font, t, rights[k] - font.width(t), ry + 5, k == 2 ? vals[k] > 0 ? 0xFFB02020 : 0xFF6A6A6A : k == 1 && vals[k] < r.need() ? 0xFF555555 : 0xFF404040, false);
            }
        }
        if (rows.size() > vis && vis > 0) {
            int th = Math.max(8, vis * MAT_RH * vis / rows.size()), ty = iy + (vis * MAT_RH - th) * MaterialsView.scroll / (rows.size() - vis);
            VanillaUi.scroller(g, ix + iw - 6, iy, vis * MAT_RH, ty, th);
        }
        // Hint: what "have" counts (and what it doesn't).
        int hint = y + h - PAD - 20;
        g.drawString(font, font.plainSubstrByWidth(Component.translatable(MaterialsView.serverKnows() ? "mccitybuilder.materials.hint" : "mccitybuilder.materials.hint_nostock").getString(), iw), ix + 1, hint, 0xFF555555, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.materials.hint2").getString(), iw), ix + 1, hint + 10, 0xFF555555, false);
        List<Hit> mine = new ArrayList<>(hits.subList(before, hits.size()));
        hits.subList(before, hits.size()).clear();
        matBlock = new Hit(x, y, w, h, BLOCK, null);
        hits.add(0, matBlock);
        hits.addAll(0, mine);
    }

    // The Materials panel's item list box this frame (the wheel scrolls there), or null.
    private int[] matListBox;
    private Hit matBlock;

    /** @return the selected segment's flat level from the layout, or MIN_VALUE */
    private static int selectedFlatY() {
        int i = PlanView.selectedLine;
        return i >= 0 && i < PlanView.lineHeights.size() ? PlanView.lineHeights.get(i)[0] : Integer.MIN_VALUE;
    }

    /** @return the piece's blocks as "12 x 6 x 5" (longest side first), "" while it loads */
    private static String sizeText(com.ldtteam.structurize.blueprints.v1.Blueprint bp) {
        return bp == null ? "" : Component.translatable("mccitybuilder.line.card.size", Math.max(bp.getSizeX(), bp.getSizeZ()), Math.min(bp.getSizeX(), bp.getSizeZ()),
                bp.getSizeY()).getString();
    }

    /**
     * The piece preview: a 3D view of one piece of the selected role (the hovered planned piece shows instead), its
     * name and "n/m . size" in the corners; arrows browse the role's pieces (hover one for the next / previous piece in
     * 3D), the corner button opens a large view.
     *
     * @param near the panel's left edge (tooltips go left of it)
     * @return the y below the card
     */
    private int pieceCard(GuiGraphics g, int x, int y, int w, int ch, int gb, int mx, int my, String pk,
                          Map<PieceSets.Role, List<PieceSets.Piece>> per, int near) {
        VanillaUi.slot(g, x, y, w, ch);
        List<PieceSets.Piece> list = per.getOrDefault(previewRole, List.of());
        String path, role;
        int turns = 0;
        PieceCatalog.Placed hp = LineView.hoverPiece;
        if (hp != null) {
            path = hp.path();
            role = hp.role().name();
            turns = hp.turns();
        } else if (!list.isEmpty()) {
            browseIndex = Math.floorMod(browseIndex, list.size());
            path = list.get(browseIndex).path();
            role = previewRole.name();
        } else {
            g.drawString(font, Component.translatable("mccitybuilder.line.card.loading"), x + 6, y + 6, 0xFFFFFFFF, true);
            return y + ch + gb;
        }
        com.ldtteam.structurize.blueprints.v1.Blueprint bp = PieceCatalog.rotated(pk, path, turns);
        cardBox = new int[]{x + 1, y + 1, w - 2, ch - 2};
        if (bp != null) PieceView.render(g, pk + "|" + path + "|" + turns, bp, x + 1, y + 1, w - 2, ch - 2);
        anchor("card", x, y, w, ch);
        // Everything over the 3D view sits in front of it (the view draws at z 400).
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        boolean browse = list.size() > 1 && hp == null;
        for (int k = 0; k < 2; k++) {
            int bx = k == 0 ? x + 3 : x + w - 17, by = y + ch / 2 - 8, d = k == 0 ? -1 : 1;
            boolean over = mx >= bx && mx < bx + 14 && my >= by && my < by + 16;
            VanillaUi.button(g, bx, by, 14, 16, false, !browse, over);
            g.drawString(font, k == 0 ? "<" : ">", bx + 4, by + 4, browse ? 0xFFFFFFFF : VanillaUi.INK_DISABLED, browse);
            anchor(k == 0 ? "card_prev" : "card_next", bx, by, 14, 16);
            hits.add(new Hit(bx, by, 14, 16, () -> {
                click();
                browseIndex += d;
            }, browse ? null : Component.translatable("mccitybuilder.line.card.browse")));
            if (browse && over && !covered(mx, my, false)) {
                int n = Math.floorMod(browseIndex + d, list.size());
                tip3d = new Tip3d(Component.translatable(d > 0 ? "mccitybuilder.line.tip_next" : "mccitybuilder.line.tip_prev", roleName(previewRole).toLowerCase(java.util.Locale.ROOT), n + 1, list.size()).getString(),
                        null, Component.translatable("mccitybuilder.line.card.browse").getString(), pk, list.get(n).path(), new int[]{near, my});
            }
        }
        int ex = x + w - 17, ey = y + 3;
        boolean overE = mx >= ex && mx < ex + 14 && my >= ey && my < ey + 14;
        VanillaUi.button(g, ex, ey, 14, 14, bigPreview, false, overE);
        g.drawString(font, "⤢", ex + 3, ey + 3, 0xFFFFFFFF, true);
        anchor("expand", ex, ey, 14, 14);
        hits.add(new Hit(ex, ey, 14, 14, () -> {
            click();
            bigPreview = !bigPreview;
        }, Component.translatable("mccitybuilder.line.card.expand")));
        String name = path.substring(path.lastIndexOf('/') + 1).replace(".blueprint", "");
        String size = sizeText(bp);
        String head = roleName(PieceSets.Role.valueOf(role)) + ": " + name;
        String right = (hp == null && list.size() > 1 ? (browseIndex + 1) + "/" + list.size() + " · " : "") + size;
        int rw = font.width(right);
        g.drawString(font, font.plainSubstrByWidth(head, w - rw - 14), x + 4, y + ch - 11, 0xFFFFFFFF, true);
        g.drawString(font, right, x + w - 4 - rw, y + ch - 11, 0xFFE0E0E0, true);
        g.pose().popPose();
        if (bigPreview && bp != null) {
            int[] r = bigRect();
            int bw = r[2], bh = r[3], bx = r[0], by = r[1];
            panels.add(new int[]{bx, by, bw, bh});
            bigDrawn = r.clone();
            VanillaUi.panel(g, bx, by, bw, bh);
            g.drawString(font, font.plainSubstrByWidth(head + " · " + size, bw - 30), bx + PAD + 1, by + PAD, VanillaUi.LABEL, false);
            int cx = bx + bw - PAD - 12;
            boolean overX = mx >= cx && mx < cx + 12 && my >= by + PAD - 2 && my < by + PAD + 10;
            VanillaUi.button(g, cx, by + PAD - 2, 12, 12, false, false, overX);
            g.drawString(font, "x", cx + 3, by + PAD, 0xFFFFFFFF, true);
            // The close button first, then a blocker so clicks and tooltips don't reach what is under the window.
            bigBlock = new Hit(bx, by, bw, bh, BLOCK, null);
            hits.add(0, bigBlock);
            anchor("big_close", cx, by + PAD - 2, 12, 12);
            hits.add(0, new Hit(cx, by + PAD - 2, 12, 12, () -> {
                click();
                bigPreview = false;
            }, null));
            cardBox = new int[]{bx + PAD, by + PAD + 12, bw - 2 * PAD, bh - 2 * PAD - 12};
            VanillaUi.slot(g, cardBox[0], cardBox[1], cardBox[2], cardBox[3]);
            anchor("big_card", cardBox[0], cardBox[1], cardBox[2], cardBox[3]);
            PieceView.render(g, pk + "|" + path + "|" + turns, bp, cardBox[0], cardBox[1], cardBox[2], cardBox[3]);
        }
        return y + ch + gb;
    }

    /**
     * The row of piece-type tiles under the preview: click selects the role the preview shows; roles the set lacks are
     * dimmed; hover shows that role's first piece in 3D.
     *
     * @return the y below the row
     */
    private int pieceTiles(GuiGraphics g, int x, int y, int w, int gb, int mx, int my, String tool, String pk, String fam,
                           Map<PieceSets.Role, List<PieceSets.Piece>> per, int near) {
        var roles = tileRoles(tool, fam == null ? null : PieceCatalog.familyOf(pk, fam), pk);
        int n = roles.size(), tw = (w - (n - 1) * 2) / n;
        for (int i = 0; i < n; i++) {
            PieceSets.Role r = roles.get(i);
            PieceSets.Piece first = per.getOrDefault(r, List.of()).isEmpty() ? null : per.get(r).get(0);
            boolean has = first != null;
            int tx = x + i * (tw + 2);
            boolean over = mx >= tx && mx < tx + tw && my >= y && my < y + 22;
            VanillaUi.button(g, tx, y, tw, 22, r == previewRole, false, over);
            PanelIcons.draw(g, PanelIcons.forRole(tool, r), tx + (tw - 16) / 2, y + 3, 1, !has);
            anchor("tile_" + r.name(), tx, y, tw, 22);
            hits.add(new Hit(tx, y, tw, 22, () -> {
                click();
                if (has) {
                    previewRole = r;
                    browseIndex = 0;
                }
            }, has ? null : Component.translatable("mccitybuilder.line.tile_missing", roleName(r))));
            if (has && over && !covered(mx, my, false)) {
                String nm = first.path().substring(first.path().lastIndexOf('/') + 1).replace(".blueprint", "");
                tip3d = new Tip3d(roleName(r) + ": " + nm, null, null, pk, first.path(), new int[]{near, my});
            }
        }
        return y + 22 + gb;
    }

    /**
     * The 3D hover tooltip, drawn last so it is above every panel: Minecraft's tooltip look, a title, the piece's size,
     * an optional grey note and its 3D view (at its own zoom); left of the panel hovered, kept on screen.
     */
    private void drawTip3d(GuiGraphics g, int my) {
        Tip3d t = tip3d;
        var bp = t.path() == null ? null : PieceCatalog.rotated(t.pk(), t.path(), 0);
        String sub = t.sub() != null ? t.sub() : sizeText(bp);
        var note = t.extra() == null ? List.<net.minecraft.util.FormattedCharSequence>of() : font.split(net.minecraft.network.chat.FormattedText.of(t.extra()), 190);
        int nw = 0;
        for (var s : note) nw = Math.max(nw, font.width(s));
        int vh = t.path() == null ? 0 : 110, w = Math.max(150, Math.max(Math.max(font.width(t.title()), font.width(sub)), nw) + 12);
        int h = 5 + 11 + (sub.isEmpty() ? 0 : 11) + note.size() * 10 + (vh > 0 ? vh + 5 : 0) + 4;
        int x = Math.max(4, Math.min(t.near()[0] - w - 6, width - 4 - w)), y = Math.max(4, Math.min(t.near()[1] - h / 2, height - 4 - h));
        tip3dText = t.title() + " | " + sub;
        g.pose().pushPose();
        g.pose().translate(0, 0, 1000);
        g.fill(x + 1, y, x + w - 1, y + h, 0xF0100010);
        g.fill(x, y + 1, x + w, y + h - 1, 0xF0100010);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF3A0F8A);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFF3A0F8A);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF22084E);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF22084E);
        int ty = y + 5;
        g.drawString(font, t.title(), x + 6, ty, 0xFFFFFFFF, true);
        ty += 11;
        if (!sub.isEmpty()) {
            g.drawString(font, sub, x + 6, ty, 0xFFA8A8A8, true);
            ty += 11;
        }
        for (var s : note) {
            g.drawString(font, s, x + 6, ty, 0xFF808080, true);
            ty += 10;
        }
        if (vh > 0 && bp != null) {
            float z = PieceView.zoom;
            PieceView.zoom = 1;
            PieceView.render(g, t.pk() + "|" + t.path() + "|0", bp, x + 5, ty + 1, w - 10, vh);
            PieceView.zoom = z;
        }
        g.pose().popPose();
    }

    /**
     * The Pieces panel (beside the line panel): which piece plays each part. One row per role with its icon (a green
     * check when the role resolves to a piece), the piece and arrows through auto, none and every group of the family;
     * hover an arrow for the piece it would switch to, in 3D. FIX THE SET opens Set up pieces and the joint alignment.
     * Its hits go to the front of {@code hits} (it is drawn over the line panel) behind a blocker over the whole panel.
     */
    private void piecesPanel(GuiGraphics g, int x, int y, int w, int h, String pk, String famKey, String tool, int mx, int my, JointCount.Result jc) {
        var f = PieceCatalog.familyOf(pk, famKey);
        if (f == null) return;
        int before = hits.size();
        // The family as it is now (a line may still carry a renamed key): role picks and the screens use this key.
        String fam = f.tool() + ":" + f.key();
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        int ix = x + PAD, iw = w - 2 * PAD, hy = y + PAD;
        PanelIcons.draw(g, "pieces", ix, hy + 2, 1, false);
        String pl = Component.translatable("mccitybuilder.line.pieces").getString();
        g.drawString(font, pl, ix + 20, hy + 6, VanillaUi.LABEL, false);
        g.drawString(font, font.plainSubstrByWidth(f.key(), iw - 20 - font.width(pl) - 26), ix + 24 + font.width(pl), hy + 6, 0xFF707070, false);
        int cx = ix + iw - 20;
        VanillaUi.button(g, cx, hy, 20, 20, false, false, mx >= cx && mx < cx + 20 && my >= hy && my < hy + 20);
        g.drawString(font, "x", cx + 7, hy + 6, 0xFFFFFFFF, true);
        anchor("pieces_close", cx, hy, 20, 20);
        hits.add(new Hit(cx, hy, 20, 20, () -> {
            click();
            editorOpen = false;
        }, null));
        int iy = hy + 22;
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.line.editor_sub").getString(), iw), ix + 1, iy, 0xFF555555, false);
        iy += 12;
        java.util.List<PieceSets.Group> all = PieceCatalog.allGroups(f);
        int rs = (h - PIECES_FIXED) / EDIT_ROLES.length;
        for (var role : EDIT_ROLES) {
            String cur = PieceCatalog.overrides(pk).get(fam + "|" + role.name());
            var used = PieceCatalog.groups(pk, f, role);
            boolean has = !used.isEmpty();
            String piece = cur == null ? has ? used.get(0).base() + (used.size() > 1 ? " +" + (used.size() - 1) : "") : "-" : cur.equals("-") ? Component.translatable("mccitybuilder.line.role_none").getString() : cur;
            int sq = Math.min(20, rs);
            VanillaUi.slot(g, ix, iy, sq, sq);
            PanelIcons.draw(g, PanelIcons.forRole(tool, role), ix + (sq - 16) / 2, iy + (sq - 16) / 2, 1, !has);
            if (has) PanelIcons.draw(g, "okb", ix + 13, iy - 2, 1, false);
            int ink = has ? VanillaUi.LABEL : 0xFF8A8A8A;
            g.drawString(font, roleName(role), ix + 26, iy + 6, ink, false);
            int ax = ix + iw - 34;
            g.drawString(font, font.plainSubstrByWidth(piece, ax - (ix + 26 + 50) - 2), ix + 26 + 50, iy + 6, has ? 0xFF555555 : 0xFF8A8A8A, false);
            // Choices: auto, none, then every piece group of the family.
            java.util.List<String> choices = new java.util.ArrayList<>(java.util.Arrays.asList(null, "-"));
            for (var gr : all) choices.add(gr.base());
            int k = choices.indexOf(cur);
            for (int s = 0; s < 2; s++) {
                int bx = ax + s * 18, by = iy + 2, d = s == 0 ? -1 : 1;
                boolean over = mx >= bx && mx < bx + 16 && my >= by && my < by + 16;
                VanillaUi.button(g, bx, by, 16, 16, false, false, over);
                g.drawString(font, s == 0 ? "<" : ">", bx + 5 + s, by + 4, 0xFFFFFFFF, true);
                anchor((s == 0 ? "role_prev_" : "role_next_") + role.name(), bx, by, 16, 16);
                String target = choices.get(Math.floorMod(k + d, choices.size()));
                hits.add(new Hit(bx, by, 16, 16, () -> {
                    PieceCatalog.override(pk, fam, role, target);
                    PlanView.linesChanged();
                    click();
                }, null));
                if (over && !covered(mx, my, true)) {
                    // What the pick would resolve to: auto = the built-in set-up, none = nothing, else that group.
                    var gs = "-".equals(target) ? List.<PieceSets.Group>of()
                            : PieceCatalog.groups(pk, f, role, target == null ? PieceSets.builtIn(pk).get(fam + "|" + role.name()) : target);
                    String nm = target == null ? Component.translatable("mccitybuilder.line.role_auto").getString()
                            : target.equals("-") ? Component.translatable("mccitybuilder.line.role_none").getString() : target;
                    PieceSets.Piece pc = gs.isEmpty() ? null : gs.get(0).first();
                    tip3d = new Tip3d(Component.translatable(d > 0 ? "mccitybuilder.line.role_next" : "mccitybuilder.line.role_prev", roleName(role).toLowerCase(java.util.Locale.ROOT), nm,
                            Math.floorMod(k + d, choices.size()) + 1, choices.size()).getString(), pc == null ? "" : null,
                            Component.translatable("mccitybuilder.line.role.tip").getString(), pk, pc == null ? null : pc.path(), new int[]{x, my});
                }
            }
            iy += rs;
        }
        // FIX THE SET: the set-up screen for this family's folder and the joint alignment editor.
        int savedTier = tier;
        tier = 0;
        iy = caption(g, ix, iy + 6, iw, Component.translatable("mccitybuilder.line.fix_set").getString());
        tier = savedTier;
        int hw = (iw - 2) / 2;
        String setFolder = all.isEmpty() ? fam.substring(fam.indexOf(':') + 1) : PieceSets.folder(all.get(0).first().path());
        iconButton(g, ix, iy, hw, 20, "pieces", Component.translatable("mccitybuilder.sets.open").getString(), false, false, mx >= ix && mx < ix + hw && my >= iy && my < iy + 20, 0);
        anchor("set_up", ix, iy, hw, 20);
        hits.add(new Hit(ix, iy, hw, 20, () -> {
            click();
            minecraft.setScreen(new PieceSetScreen(pk, setFolder, fam));
        }, Component.translatable("mccitybuilder.sets.open.tip")));
        int jx = ix + hw + 2, jw = iw - hw - 2;
        // Fix joints (N): N = the joints that don't fit (hover lists them); a click opens the editor on the first, else on the corner.
        // Real problems (a gap, broken blocks) count in amber; minor ones (fit and touch, off-centre) only as a grey "(N minor)".
        boolean badJoints = jc != null && !jc.badge().isEmpty(), minorJoints = jc != null && jc.done() && jc.minor() > 0;
        String fixLabel = badJoints ? Component.translatable("mccitybuilder.align.fix_n", jc.badge()).getString()
                : minorJoints ? Component.translatable("mccitybuilder.align.fix_minor", jc.minor()).getString() : Component.translatable("mccitybuilder.align.fix").getString();
        iconButton(g, jx, iy, jw, 20, "joint", fixLabel, false, false, mx >= jx && mx < jx + jw && my >= iy && my < iy + 20, badJoints ? 0xFFFFC040 : minorJoints ? 0xFFB8B8B8 : 0);
        Component fixTip = Component.translatable("mccitybuilder.align.open.tip");
        if (badJoints || minorJoints) {
            // Real ones first under an amber heading, the minor ones under a grey one.
            net.minecraft.network.chat.MutableComponent t = Component.empty();
            if (badJoints)
                t.append(Component.translatable("mccitybuilder.align.fix.real").withStyle(net.minecraft.ChatFormatting.GOLD)).append(Component.literal("\n" + String.join(", ", jc.failing())));
            if (minorJoints) {
                if (badJoints) t.append(Component.literal("\n"));
                t.append(Component.translatable("mccitybuilder.align.fix.minor").withStyle(net.minecraft.ChatFormatting.GRAY))
                        .append(Component.literal("\n" + String.join(", ", jc.minorNames())).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            fixTip = t.append(Component.literal("\n")).append(Component.translatable("mccitybuilder.align.fix.click").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
        Component fixTipFinal = fixTip;
        anchor("fix_joints", jx, iy, jw, 20);
        hits.add(new Hit(jx, iy, jw, 20, () -> {
            click();
            if (badJoints || minorJoints) JointCheck.openFirst();
            else minecraft.setScreen(new JointEditorScreen(pk, fam, PieceSets.Role.CORNER, -1));
        }, fixTipFinal));
        // Drawn over the line panel and the palette: its hits (and a blocker over the panel) go first.
        List<Hit> mine = new ArrayList<>(hits.subList(before, hits.size()));
        hits.subList(before, hits.size()).clear();
        hits.add(0, new Hit(x, y, w, h, BLOCK, null));
        hits.addAll(0, mine);
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
            } else if (LineView.liveShort > 0) warn = Component.translatable(LineView.liveShort == 1 ? "mccitybuilder.line.short_one" : "mccitybuilder.line.short", LineView.liveShort).getString();
        }
        // What snapped, under the hint.
        if (LineView.snap != null && warn == null)
            warn = Component.translatable("mccitybuilder.line.snap." + LineView.snap, LineView.snapPieces).getString();
        // A connect says so even over a warning (it shares the line).
        // A connect says so even over a warning, kept short (the panel's status line says why and how to fix it).
        else if ("connect".equals(LineView.snap))
            warn = LineView.liveShort > 0 ? Component.translatable(LineView.liveShort == 1 ? "mccitybuilder.line.connect_gap_one" : "mccitybuilder.line.connect_gap", LineView.liveShort).getString()
                    : Component.translatable("mccitybuilder.line.snap.connect").getString() + " · " + warn;
        if (RouteView.on) {
            warn = RouteView.status().getString();
            warnColour = RouteView.phase == RouteView.Phase.FAILED ? 0xFFFF8080 : 0xFF80E0FF;
        }
        if (LineView.flash != null && System.currentTimeMillis() < LineView.flashUntil) {
            warn = Component.translatable(LineView.flash, LineView.flashArg).getString();
            warnColour = 0xFFFF8080;
        }
        if (warn == null || overPanel(mx, my)) return;
        int w = font.width(warn);
        // Left of the cursor when it would run into the line panel or off the screen.
        int lx = mx + 10, limit = linePanelBox != null && my + 23 > linePanelBox[1] ? linePanelBox[0] : width - 4;
        if (lx + w + 6 > limit) lx = mx - 16 - w;
        // Always fully on screen.
        lx = Math.max(4, Math.min(lx, width - 4 - w - 6));
        int ly = my + 10 + 13 > height - 4 ? my - 23 : my + 10;
        g.fill(lx, ly, lx + w + 6, ly + 13, 0xC0202020);
        g.drawString(font, warn, lx + 3, ly + 3, LineView.snap != null && warnColour == 0xFFFFC040 && LineView.liveShort == 0 ? 0xFF80E0FF : warnColour, false);
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
        // No node where a built joint piece stands, no handle on a built segment (a click on its body still selects it);
        // other nodes at built segments open the joint menu but don't drag.
        for (var l : ls)
            for (int[] e : l.ends())
                if (seen.add(com.volcanicmg.mccitybuilder.planner.Plan.cell(e[0], e[1])) && !com.volcanicmg.mccitybuilder.planner.Plan.builtJointAt(PlanView.builtPieces.values(), e)) {
                    double[] p = screen(e[0], e[1], 1.5);
                    if (p != null) {
                        hs.add(new double[]{p[0], p[1], 0});
                        what.add(e);
                    }
                }
        for (int i = 0; i < ls.size(); i++) {
            var l = ls.get(i);
            if (PlanView.locked(l)) continue;
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
        // The first time a line tool opens: its coach tips.
        if (LineView.active() && !Config.LINE_TIPS_SEEN.get() && tipStep < 0) tipStep = 0;
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
    /** @return whether the plan panel carries its Materials button: not while the line panel is up (its Needs row opens the panel, and the plan panel stays as tall as before) */
    private static boolean planMaterials() {
        return MaterialsView.shown() && !LineView.active() && PlanView.selectedLine() == null;
    }

    /** @return the plan panel's height: title, buttons, and the Build mode / Builder pickers and Materials button when shown (the line panel reserves room for it) */
    private static int planPanelH() {
        return 58 + (!Planner.instant() ? 20 : 0) + (Planner.creative() ? 20 : 0) + (planMaterials() ? 22 : 0);
    }

    private void planPanel(GuiGraphics g, int mx, int my) {
        if (!PlanView.on) return;
        boolean pickBuilder = !Planner.instant(), pickMode = Planner.creative();
        boolean mats = planMaterials();
        int w = 200, h = planPanelH(), x = Math.max(4 + PALETTE_W + 4, width - 4 - guideRoom() - 4 - w), y = stackTop - h;
        if (Planner.selected != null || Planner.selectedDeco != null) y -= selectionH() + 4;
        panels.add(new int[]{x, y, w, h});
        anchor("plan_panel", x, y, w, h);
        VanillaUi.panel(g, x, y, w, h);
        long bad = PlanView.conflicts.stream().filter(c -> c != com.volcanicmg.mccitybuilder.planner.Plan.Conflict.NONE).count();
        // The full line when it fits, else the short one, never past the panel.
        String head = Component.translatable("mccitybuilder.plan.title", PlanView.entries.size(), PlanView.lines.size(), bad).getString();
        if (font.width(head) > w - 2 * PAD) head = Component.translatable("mccitybuilder.plan.title_short", PlanView.entries.size(), PlanView.lines.size(), bad).getString();
        g.drawString(font, font.plainSubstrByWidth(head, w - 2 * PAD), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        String sub = PlanView.progress() != null ? PlanView.progress().getString()
                : PlanView.hasSelection() ? selectedPlanText() : Component.translatable("mccitybuilder.plan.hint").getString();
        g.drawString(font, font.plainSubstrByWidth(sub, w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        String[] keys = {"build_all", "build_sel", "clear"};
        boolean empty = PlanView.entries.isEmpty() && PlanView.lines.isEmpty();
        boolean[] off = {empty, !PlanView.hasSelection() && PlanView.selectedLine() == null, empty};
        Runnable[] run = {() -> toast(PlanView.commit(false)), () -> toast(PlanView.commit(true)), PlanView::clear};
        int bw = (w - 2 * PAD - 4) / 3, by = y + PAD + 24;
        if (pickMode) by = buildModePicker(g, x + PAD, by, w - 2 * PAD, mx, my);
        if (pickBuilder) by = builderPicker(g, x + PAD, by, w - 2 * PAD, mx, my, false);
        for (int i = 0; i < 3; i++) {
            int bx = x + PAD + i * (bw + 2);
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
        if (mats) {
            // Materials: opens the panel beside this one on the Huts tab; amber label while short.
            int my2 = by + 22, miss = MaterialsView.view(Materials.Tab.ALL).missing(), mw = w - 2 * PAD;
            String ml = Component.translatable("mccitybuilder.materials.title").getString() + (miss > 0 ? " \u00b7 " + Component.translatable("mccitybuilder.materials.col.short").getString().toLowerCase(java.util.Locale.ROOT) + " " + num(miss) : "");
            iconButton(g, x + PAD, my2, mw, 20, "hammer", ml, MaterialsView.open, false, mx >= x + PAD && mx < x + PAD + mw && my >= my2 && my < my2 + 20, miss > 0 ? 0xFFFFB030 : 0);
            anchor("plan_materials", x + PAD, my2, mw, 20);
            hits.add(new Hit(x + PAD, my2, mw, 20, () -> {
                click();
                MaterialsView.open = !MaterialsView.open;
                if (MaterialsView.open) {
                    MaterialsView.tab = Materials.Tab.HUTS;
                    MaterialsView.scroll = 0;
                    editorOpen = false;
                }
            }, Component.translatable("mccitybuilder.materials.row.tip")));
            // No line panel showing a side panel: it goes left of this one, with the same blocker and bottom edge.
            if (MaterialsView.open && piecesBox == null) {
                int mh = matHeight();
                piecesBox = new int[]{Math.max(4, x - 4 - w), Math.max(4, Math.min(y + h - mh, height - 4 - mh)), w, mh};
                materialsPanel(g, piecesBox[0], piecesBox[1], w, mh, mx, my);
            }
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

    /** The toolbar under the banner: the Plan toggle and the Road / Wall tools. */
    private void overlayPicker(GuiGraphics g, int mx, int my) {
        // Plan, Road and Wall under the banner, each with the help's pixel icon (the overlay is always on).
        // A narrow screen (a large GUI scale): icons only, so the side panels keep clear of it (the tooltips still name them).
        boolean compact = width < 800;
        int y = 20, bh = 22, pw = compact ? bh : 82, tw = compact ? bh : 74, gap = 4, px = (width - pw - 2 * (tw + gap)) / 2;
        toolbarButton(g, mx, my, px, y, pw, bh, "toolbar_plan", "tb_plan", compact ? "" : Component.translatable(PlanView.on ? "mccitybuilder.plan.on_short" : "mccitybuilder.plan.off_short").getString(),
                PlanView.on, this::togglePlan, Component.translatable("mccitybuilder.plan.tip"));
        String[] tools = {"road", "wall"}, icons = {"tb_road", "draw"};
        for (int i = 0; i < 2; i++) {
            String t = tools[i];
            toolbarButton(g, mx, my, px + pw + gap + i * (tw + gap), y, tw, bh, "toolbar_" + t, icons[i], compact ? "" : Component.translatable("mccitybuilder.line.tool." + t).getString(),
                    t.equals(LineView.tool), () -> useTool(t), Component.translatable("mccitybuilder.line.tool." + t + ".tip"));
        }
    }

    /** A toolbar toggle: icon at the left, label centred in the rest (no label: the icon centred). */
    private void toolbarButton(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String id, String icon, String label, boolean on, Runnable run, Component tip) {
        panels.add(new int[]{x, y, w, h});
        VanillaUi.button(g, x, y, w, h, on, false, mx >= x && mx < x + w && my >= y && my < y + h);
        PonderScreen.icon(g, icon, label.isEmpty() ? x + (w - 16) / 2 : x + 4, y + (h - 16) / 2, 1);
        if (!label.isEmpty()) VanillaUi.buttonLabel(g, font, label, x + 20, y, w - 22, h, VanillaUi.INK);
        anchor(id, x, y, w, h);
        hits.add(new Hit(x, y, w, h, run, tip));
    }

    /** "Planner · colony name" at the top, so planner mode is obvious. */
    private void banner(GuiGraphics g, IColonyView colony) {
        String t = Component.translatable("mccitybuilder.banner", colony.getName()).getString();
        int w = font.width(t) + 16, x = (width - w) / 2;
        panels.add(new int[]{x, 2, w, 16});
        VanillaUi.panel(g, x, 2, w, 16);
        g.drawString(font, t, x + 8, 6, VanillaUi.LABEL, false);
    }

    /** Where the view centre is beyond the player's entity tracking range: citizens there aren't sent, so say so (no stale dots). */
    private void citizenRangeHint(GuiGraphics g) {
        if (!com.volcanicmg.mccitybuilder.Config.SHOW_CITIZENS.get() || minecraft.player == null || Planner.drone == null) return;
        var hit = Planner.pick(0.5, 0.5);
        double x = hit != null ? hit.getLocation().x : Planner.drone.x, z = hit != null ? hit.getLocation().z : Planner.drone.z;
        int range = OverlayView.citizenRange();
        if (Math.hypot(x - minecraft.player.getX(), z - minecraft.player.getZ()) <= range) return;
        String t = Component.translatable("mccitybuilder.citizens.out_of_range", range).getString();
        int w = font.width(t) + 8, cx = (width - w) / 2, y = height - 16;
        g.fill(cx, y, cx + w, y + 12, 0x90000000);
        g.drawString(font, t, cx + 4, y + 2, 0xFFE0E0E0, false);
    }

    /** Moves the hits added since {@code from} to the front of {@code hits}, behind a blocker over the box (a panel drawn over the others takes its clicks and tooltips). */
    private void raise(int from, int x, int y, int w, int h) {
        List<Hit> mine = new ArrayList<>(hits.subList(from, hits.size()));
        hits.subList(from, hits.size()).clear();
        hits.add(0, new Hit(x, y, w, h, BLOCK, null));
        hits.addAll(0, mine);
    }

    /** @return the first blocker hit covering the point, or null */
    private Hit blockerAt(double mx, double my) {
        for (Hit h : hits) if (h.action() == BLOCK && h.in(mx, my)) return h;
        return null;
    }

    /** @return whether a blocker hit (a panel drawn over others) covers the point */
    private boolean blockedAt(double mx, double my) {
        for (Hit h : hits) if (h.action() == BLOCK && h.in(mx, my)) return true;
        return false;
    }

    private boolean overPanel(double mx, double my) {
        for (int[] p : panels) if (mx >= p[0] && mx < p[0] + p[2] && my >= p[1] && my < p[1] + p[3]) return true;
        return false;
    }

    // ---- palette ----

    // The palette's tab: huts, or the pack's decorations (kept for the client session).
    private static boolean decoTab;
    // The Other tab (a decoration-like tab: road / wall folder blueprints the line tools don't place).
    private static boolean otherTab;

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
        // Survival: the huts in the inventory come first, when there are any.
        else if (!PlanView.on && !ownedHuts().isEmpty()) out.add("owned");
        out.addAll(HutCatalog.CATEGORIES);
        return out;
    }

    /**
     * @return a folder's decorations without the road / wall pieces (the line tools' business), unless that would
     * hide every one of them; empty while they load
     */
    private static List<Blueprints.Hut> decorations(String folder) {
        // Worked out once per pack, tab, set-up and piece loads (the palette asks every frame, per folder).
        List<Object> stamp = List.of(pack(), otherTab, PieceCatalog.setupVersion, PieceCatalog.loadVersion, Blueprints.generation);
        if (!stamp.equals(decoStamp)) {
            decoCache.clear();
            decoStamp = stamp;
        }
        List<Blueprints.Hut> done = decoCache.get(folder);
        if (done != null) return done;
        List<Blueprints.Hut> out = decorationsNow(folder);
        // Not cached while the folder or the pack's pieces load (it changes without a stamp change).
        if (Blueprints.decorations(pack(), folder) != null && (!otherTab || PieceCatalog.families(pack()) != null)) decoCache.put(folder, out);
        return out;
    }

    private static final Map<String, List<Blueprints.Hut>> decoCache = new java.util.HashMap<>();
    private static List<Object> decoStamp;

    private static List<Blueprints.Hut> decorationsNow(String folder) {
        List<Blueprints.Hut> all = Blueprints.decorations(pack(), folder);
        if (all == null) return List.of();
        List<Blueprints.Hut> out = new ArrayList<>();
        if (otherTab) {
            for (Blueprints.Hut h : all) if (PieceCatalog.other(pack(), h.path())) out.add(h);
            return out;
        }
        for (Blueprints.Hut h : all) if (PieceCatalog.toolOf(pack(), h.path()) == null) out.add(h);
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

    /** @return the list's rows: on "all", each folder's name (a String header) then its matching decorations */
    private static List<Object> decoRows() {
        String folder = decoFolder();
        if (folder == null) return List.of();
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        List<Object> out = new ArrayList<>();
        for (String f : folder.isEmpty() ? Blueprints.decoFolders(pack()) : List.of(folder)) {
            List<Blueprints.Hut> in = decorations(f).stream().filter(h -> q.isEmpty() || h.blueprint().getFileName().toLowerCase(java.util.Locale.ROOT).contains(q)).toList();
            if (in.isEmpty()) continue;
            if (folder.isEmpty()) out.add(f);
            out.addAll(in);
        }
        return out;
    }

    private static final int DECO_ROWS = 12, DECO_ROW_H = 11;
    // Right-click targets this frame: palette decoration rows and folder headers (the set-up screen for their folder).
    private final List<Hit> setupHits = new ArrayList<>();
    private static int decoScroll;
    private int[] paletteRect = new int[4];

    /** @return the chosen category, or the first one when none is chosen yet (or it is creative-only in survival) */
    private static String category() {
        List<String> cats = categories();
        // Survival with huts in the inventory opens on them until another category is picked.
        if (category == null || !cats.contains(category) || "owned".equals(cats.get(0)) && !ownedPicked) category = cats.get(0);
        return category;
    }

    // Set once the player picks a category on the Huts tab this session (then "owned" no longer takes over).
    private static boolean ownedPicked;

    /** @return the placeable huts in the survival player's inventory (empty in creative) */
    private static java.util.Set<Block> ownedHuts() {
        Minecraft mc = Minecraft.getInstance();
        java.util.Set<Block> out = new java.util.HashSet<>();
        if (mc.player == null || mc.player.isCreative()) return out;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof AbstractBlockHut<?>
                    && !HutCatalog.hidden(BuiltInRegistries.BLOCK.getKey(bi.getBlock()).getPath())) out.add(bi.getBlock());
        return out;
    }

    /** @return palette huts: the category's, or with a search every hut whose name contains it */
    private static List<Block> huts() {
        List<Block> out = new ArrayList<>();
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.Set<Block> owned = ownedHuts();
        for (AbstractColonyBlock<?> b : ModBlocks.getHuts()) {
            if (!(b instanceof AbstractBlockHut<?>)) continue;
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (HutCatalog.hidden(path)) continue;
            String cat = category();
            boolean inCat = cat.equals("all") || (cat.equals("owned") ? owned.contains(b) : HutCatalog.category(path).equals(cat));
            if (q.isEmpty() ? inCat : b.getName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) out.add(b);
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
        List<Object> drows = decoMode ? decoRows() : List.of();
        decoScroll = Math.max(0, Math.min(decoScroll, drows.size() - DECO_ROWS));
        int gridH = decoMode ? Math.max(1, Math.min(drows.size(), DECO_ROWS)) * DECO_ROW_H : rows * SLOT;
        Map<String, Integer> need = Planner.placingHut != null ? Blueprints.cost(Planner.placingHut) : null;
        // The placing block; a hut adds a level picker row.
        int placingH = !Planner.isPlacing() ? 12 : 62 + (levelPicker() ? 20 : 0) + (designPicker() ? 20 : 0) + (modePickerShown() ? 20 : 0) + (builderPickerShown() ? 20 : 0);
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
        // Tabs: Huts | Decorations | Other (the chosen one drawn as an inset).
        int tw2 = (iw - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = ix + i * (tw2 + 2), tab = i;
            boolean on = (decoTab ? otherTab ? 2 : 1 : 0) == i, over = mx >= bx && mx < bx + tw2 && my >= yy && my < yy + 16;
            VanillaUi.button(g, bx, yy, tw2, 16, on, false, over);
            VanillaUi.buttonLabel(g, font, Component.translatable(i == 0 ? "mccitybuilder.huts" : i == 1 ? "mccitybuilder.decorations.tab" : "mccitybuilder.other").getString(), bx, yy, tw2, 16,
                    VanillaUi.ink(on, false));
            boolean deco = i >= 1;
            hits.add(new Hit(bx, yy, tw2, 16, () -> {
                if (on) return;
                click();
                boolean wasDeco = decoTab;
                decoTab = deco;
                otherTab = tab == 2;
                // Another decoration-like tab: its own folders.
                if (deco && wasDeco) category = null;
                search = "";
                decoScroll = 0;
                if (deco ? Planner.placing != null : Planner.deco != null) Planner.stopPlacing();
            }, Component.translatable(i == 2 ? "mccitybuilder.other.tip" : deco ? "mccitybuilder.decorations.tip" : "mccitybuilder.huts.tip")));
        }
        yy += 20;
        // Search field (click to type; Esc clears it).
        VanillaUi.textField(g, ix, yy, iw, 16);
        String caret = searchFocused && System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "";
        boolean empty = search.isEmpty();
        g.drawString(font, empty && !searchFocused ? Component.translatable(decoTab ? otherTab ? "mccitybuilder.search_other" : "mccitybuilder.search_deco" : "mccitybuilder.search").getString() : search + caret, ix + 4, yy + 4,
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
                : Component.translatable("mccitybuilder.category." + (decoMode ? otherTab ? "all_other" : "all_deco" : category())).getString();
        yy = picker(g, ix, yy, iw, catLabel, mx, my, d -> {
            List<String> cats = categories();
            category = cats.get(Math.floorMod(cats.indexOf(category()) + d, cats.size()));
            if (!decoMode) ownedPicked = true;
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
            // Decorations: a scrollable list of names under folder headers (wheel over the palette).
            for (int i = 0; i < Math.min(DECO_ROWS, drows.size() - decoScroll); i++) {
                int ry = yy + i * DECO_ROW_H;
                if (drows.get(decoScroll + i) instanceof String header) {
                    g.drawString(font, font.plainSubstrByWidth(header, iw - 4), ix + 1, ry + 2, 0xFF404040, false);
                    g.fill(ix, ry + DECO_ROW_H - 1, ix + iw, ry + DECO_ROW_H, VanillaUi.SLOT);
                    // A folder header opens that folder (its own category).
                    hits.add(new Hit(ix, ry, iw, DECO_ROW_H, () -> {
                        click();
                        category = "deco:" + header;
                        decoScroll = 0;
                    }, Component.translatable("mccitybuilder.palette.folder.tip")));
                    setupHits.add(new Hit(ix, ry, iw, DECO_ROW_H, () -> minecraft.setScreen(new PieceSetScreen(pack(), com.volcanicmg.mccitybuilder.planner.PieceSets.folder(header + "/x.blueprint"), null)), null));
                    continue;
                }
                Blueprints.Hut d = (Blueprints.Hut) drows.get(decoScroll + i);
                String dFolder = com.volcanicmg.mccitybuilder.planner.PieceSets.folder(d.path());
                setupHits.add(new Hit(ix, ry, iw, DECO_ROW_H, () -> minecraft.setScreen(new PieceSetScreen(pack(), dFolder, null)), null));
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
                }, Component.translatable("mccitybuilder.sets.deco.tip")));
            }
        } else for (int i = 0; i < huts.size(); i++) hutSlot(g, huts.get(i), ix + i % COLS * SLOT, yy + i / COLS * SLOT, mx, my);
        if (decoMode ? decos.isEmpty() : huts.isEmpty()) {
            // Other: still loading only while the pack's pieces load; then nothing here, or nothing matching.
            String none = !decoMode ? "mccitybuilder.no_match" : !otherTab ? "mccitybuilder.no_match_deco" : PieceCatalog.families(pack()) == null ? "mccitybuilder.invalid.loading"
                    : search.isEmpty() ? "mccitybuilder.other.none" : "mccitybuilder.no_match_other";
            g.drawString(font, Component.translatable(none), ix + 1, yy + 2, 0xFF606060, false);
        }
        yy += gridH + 6;
        String counter = decoMode && drows.size() > DECO_ROWS ? (decoScroll + 1) + "-" + Math.min(drows.size(), decoScroll + DECO_ROWS) + "/" + drows.size() : "";
        int cw = counter.isEmpty() ? 0 : font.width(counter) + 4;
        if (cw > 0) g.drawString(font, counter, ix + iw - cw + 4, yy, 0xFF606060, false);
        if (!Planner.isPlacing()) {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(decoMode ? otherTab ? "mccitybuilder.pick_other" : "mccitybuilder.pick_deco" : "mccitybuilder.pick_hut").getString(), iw - cw), ix + 1, yy, VanillaUi.LABEL, false);
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
        if (Planner.instant() || Planner.moving != null || Planner.movingDeco != null)
            g.drawString(font, Component.translatable("mccitybuilder.creative_instant"), ix + 1, yy + 50, 0xFF2050A0, false);
        else if (Planner.deco != null) g.drawString(font, Component.translatable("mccitybuilder.deco_survival"), ix + 1, yy + 50, 0xFF2050A0, false);
        if (levelPicker()) {
            // Level picker: 1 .. the highest level the style pack has a blueprint for (survival: placed at 1, then built up to it).
            Blueprints.Hut one = Blueprints.alt(pack(), Planner.placing, Planner.placingAlt);
            int max = one == null ? 1 : Blueprints.maxLevel(one);
            boolean c = Planner.instant() || Planner.moving != null;
            picker(g, ix, yy + 62, iw, Component.translatable(c ? "mccitybuilder.paste_level" : "mccitybuilder.build_level", Planner.placingLevel, max).getString(), mx, my, d -> {
                Planner.setPlacingLevel(Math.floorMod(Planner.placingLevel - 1 + d, max) + 1);
                click();
            }, Component.translatable(c ? "mccitybuilder.paste_level.tip" : "mccitybuilder.build_level.tip"));
        }
        yy += levelPicker() ? 82 : 62;
        // Design picker for huts the style pack has alternatives for (a move keeps the building's own).
        List<Blueprints.Hut> alts = designPicker() ? Blueprints.alts(pack(), Planner.placing) : null;
        if (alts != null) {
            int n = Math.floorMod(Planner.placingAlt, alts.size());
            yy = picker(g, ix, yy - 2, iw, Component.translatable("mccitybuilder.design", Blueprints.altName(alts, n), n + 1, alts.size()).getString(), mx, my, d -> {
                Planner.setPlacingAlt(Math.floorMod(n + d, alts.size()));
                click();
            }, Component.translatable("mccitybuilder.design.tip"));
        }
        if (modePickerShown()) yy = buildModePicker(g, ix, yy - 2, iw, mx, my);
        // Survival huts: which builder takes the build order (decorations go through MineColonies' own window, which asks).
        if (builderPickerShown()) yy = builderPicker(g, ix, yy - 2, iw, mx, my, false);
        // The list is the shown blueprint's: the picked level's for a hut (that level alone, not the levels below).
        g.drawString(font, Planner.placing != null ? Component.translatable("mccitybuilder.materials", Planner.placingLevel)
                : Component.translatable("mccitybuilder.materials_deco"), ix + 1, yy, VanillaUi.LABEL, false);
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
    static Map<String, Integer> inventory() {
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
        // Plan mode plans anything: research and the inventory only matter when the plan is built (Build all checks both).
        boolean blocked = locked && !PlanView.on;
        boolean missing = creative && Blueprints.missing(pack(), hut);
        g.renderItem(new ItemStack(hut), sx + 1, sy + 1);
        if (count > 0) g.renderItemDecorations(font, new ItemStack(hut, count), sx + 1, sy + 1);
        if (blocked || missing || count <= 0 && !creative && !PlanView.on) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, blocked ? 0xB0555555 : 0x908B8B8B);
        if (hut == Planner.placing) outline(g, sx + 1, sy + 1, SLOT - 2, SLOT - 2, 0xFFFFFFFF);
        // Survival: huts in the inventory get a green frame.
        else if (!creative && count > 0) outline(g, sx + 1, sy + 1, SLOT - 2, SLOT - 2, 0xFF55DD55);
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
            if (blocked) return;
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

    /** ADJUST: a floating bar of vanilla buttons under the dropped ghost: place, rotate, mirror, move (camera-relative), raise, lower, cancel. */
    /** Route mode: Accept / Try another / Cancel under the route's end once it is shown (Try another and Cancel when none was found). */
    private void routeBar(GuiGraphics g, int mx, int my) {
        int[] at = LineView.active() ? RouteView.barAnchor() : null;
        if (at == null) return;
        boolean found = RouteView.phase == RouteView.Phase.PREVIEW, other = found || "no_other".equals(RouteView.fail);
        String[] keys = {"accept", "another", "cancel"};
        int bw = 0, n = 3, h = 28;
        for (String k : keys) bw = Math.max(bw, font.width(Component.translatable("mccitybuilder.route." + k).getString()) + 16);
        int w = n * (bw + 2) + 6;
        double[] ndc = Planner.project(at[0] + 0.5, LineView.groundY(at[0], at[1]) + 1, at[1] + 0.5);
        int x = ndc == null ? (width - w) / 2 : (int) Math.round((ndc[0] + 1) / 2 * width) - w / 2;
        int y = ndc == null ? height - 80 : (int) Math.round((1 - ndc[1]) / 2 * height) + 16;
        x = Math.max(4, Math.min(width - w - 4, x));
        y = Math.max(24, Math.min(height - h - 4, y));
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        Runnable[] run = {() -> {
            if (RouteView.accept()) toast(Component.translatable("mccitybuilder.route.accepted"));
        }, RouteView::another, RouteView::restart};
        for (int i = 0; i < n; i++) {
            int bx = x + 4 + i * (bw + 2), by = y + 4;
            boolean off = i == 0 && !found || i == 1 && !other, over = mx >= bx && mx < bx + bw && my >= by && my < by + 20;
            // The panel's button look: white label with shadow (grey when off); Accept green, the primary action like Build.
            iconButton(g, bx, by, bw, 20, null, Component.translatable("mccitybuilder.route." + keys[i]).getString(), i == 0 && found, off, over, 0);
            anchor("route_" + keys[i], bx, by, bw, 20);
            Runnable r = run[i];
            hits.add(new Hit(bx, by, bw, 20, () -> {
                if (off) return;
                click();
                r.run();
            }, Component.translatable("mccitybuilder.route." + keys[i] + ".tip")));
        }
    }

    private void actionBar(GuiGraphics g, int mx, int my) {
        BlockPos at = Planner.anchor();
        if (!Planner.adjusting() || at == null) return;
        double[] ndc = Planner.project(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        int n = 10, w = n * 22 + 6, h = 28;
        int x = ndc == null ? (width - w) / 2 : (int) Math.round((ndc[0] + 1) / 2 * width) - w / 2;
        int y = ndc == null ? height - 80 : (int) Math.round((1 - ndc[1]) / 2 * height) + 16;
        x = Math.max(4, Math.min(width - w - 4, x));
        y = Math.max(24, Math.min(height - h - 4, y));
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        String[] icons = {"place", "rotate", "mirror", "left", "forward", "back", "right", "up", "down", "cancel"};
        String[] tips = {"mccitybuilder.action.place", "mccitybuilder.action.rotate", "mccitybuilder.action.mirror", "mccitybuilder.action.left",
                "mccitybuilder.action.forward", "mccitybuilder.action.back", "mccitybuilder.action.right", "mccitybuilder.raise", "mccitybuilder.lower",
                "mccitybuilder.action.cancel"};
        Runnable[] run = {this::place, Planner::rotatePlacing, Planner::mirrorPlacing, () -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.LEFT, hasShiftDown()),
                () -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.FORWARD, hasShiftDown()), () -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.BACK, hasShiftDown()),
                () -> Planner.nudge(com.volcanicmg.mccitybuilder.planner.PlacementState.Dir.RIGHT, hasShiftDown()), () -> Planner.raise(1), () -> Planner.raise(-1), Planner::back};
        for (int i = 0; i < n; i++) iconButton(g, icons[i], x + 4 + i * 22, y + 4, mx, my, run[i], Component.translatable(tips[i]));
    }

    /** Place / Enter: sends the placement with feedback. */
    private void place() {
        if (Planner.confirm()) {
            toast(Component.translatable(Planner.instant() ? "mccitybuilder.placed_creative" : "mccitybuilder.placed"));
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
        g.drawString(font, "<", x + 5, y + 4, lit ? 0xFFFFFFFF : VanillaUi.INK, lit);
        g.drawString(font, ">", x + w - bw + 6, y + 4, lit ? 0xFFFFFFFF : VanillaUi.INK, lit);
        String l = font.plainSubstrByWidth(label, w - 2 * bw - 10);
        g.drawString(font, l, x + (w - font.width(l)) / 2, y + 4, 0xFFFFFFFF, true);
        hits.add(new Hit(x, y, bw, 16, () -> step.accept(-1), tip));
        hits.add(new Hit(x + w - bw, y, bw, 16, () -> step.accept(1), tip));
        return y + 16 + pickGap;
    }

    /**
     * Creative only: the build mode (Instant / Builders (free) / Builders), saved in the client config. Builders (free)
     * on a server without City Builder reads "Builders (not free)" (it runs as Builders).
     *
     * @return the y below it
     */
    private int buildModePicker(GuiGraphics g, int x, int y, int w, int mx, int my) {
        return buildModePicker(g, x, y, w, mx, my, null);
    }

    /** @param lead a label to the left (the value then drops its own "Build: " prefix), or null */
    private int buildModePicker(GuiGraphics g, int x, int y, int w, int mx, int my, String lead) {
        var m = com.volcanicmg.mccitybuilder.Config.BUILD_MODE.get();
        String k = "mccitybuilder.build_mode." + m.name().toLowerCase(java.util.Locale.ROOT)
                + (m == com.volcanicmg.mccitybuilder.Config.BuildMode.BUILDERS_FREE && !Planner.funded() ? "_no_server" : "");
        java.util.function.IntConsumer step = d -> {
            Planner.cycleBuildMode(d);
            click();
        };
        String text = Component.translatable(k).getString();
        return lead == null ? picker(g, x, y, w, text, mx, my, step, Component.translatable(k + ".tip"))
                : lead.isEmpty() ? picker(g, x, y, w, afterColon(text), mx, my, step, Component.translatable(k + ".tip"))
                : labeledPicker(g, x, y, w, lead, afterColon(text), mx, my, step, Component.translatable(k + ".tip"));
    }

    /** @return the text after its first ": " (a picker value without its label), else the text */
    private static String afterColon(String s) {
        int i = s.indexOf(": ");
        return i < 0 ? s : s.substring(i + 2);
    }

    /**
     * The survival builder picker: Any, then each of the colony's builder huts (its builder's name, else the hut's).
     *
     * @param shared a road / wall Build (many orders): Any reads "Any (shared)", as free builders split them
     * @return the y below it
     */
    private int builderPicker(GuiGraphics g, int x, int y, int w, int mx, int my, boolean shared) {
        return builderPicker(g, x, y, w, mx, my, shared, null);
    }

    /** @param lead a label to the left (the value then drops its own "Builder: " prefix), or null */
    private int builderPicker(GuiGraphics g, int x, int y, int w, int mx, int my, boolean shared, String lead) {
        String n = Planner.builderName();
        String label = n != null ? Component.translatable("mccitybuilder.builder.named", n).getString()
                : Component.translatable(shared ? "mccitybuilder.builder.any_shared" : "mccitybuilder.builder.any").getString();
        java.util.function.IntConsumer step = d -> {
            Planner.cycleBuilder(d);
            click();
        };
        Component tip = Component.translatable(shared ? "mccitybuilder.builder.line_tip" : "mccitybuilder.builder.tip");
        return lead == null ? picker(g, x, y, w, label, mx, my, step, tip) : lead.isEmpty() ? picker(g, x, y, w, afterColon(label), mx, my, step, tip) : labeledPicker(g, x, y, w, lead, afterColon(label), mx, my, step, tip);
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
            // Clickable: opens the assigned builder's hut.
            else if (dragFrom < 0 && o.isClaimed() && mx >= rx && mx < rx + rw - 42 && my >= ry && my < ry + ROW_H - 2) g.fill(rx + 1, ry + 1, rx + rw - 1, ry + ROW_H - 3, 0x20FFFFFF);
            String kind = o instanceof com.minecolonies.core.colony.workorders.view.WorkOrderDecorationView ? "decoration"
                    : o.getWorkOrderType().name().toLowerCase();
            icon(g, kind, rx + 3, ry + 3, 0xFFFFFFFF);
            String type = Component.translatable("mccitybuilder.wo." + kind).getString();
            String title = type + ": " + o.getDisplayName().getString();
            g.drawString(font, font.plainSubstrByWidth(title, rw - 58), rx + 15, ry + 3, 0xFFFFFFFF, true);
            String builder = builderName(colony, o);
            String detail = Component.translatable("mccitybuilder.wo.levels", o.getCurrentLevel(), o.getTargetLevel()).getString() + "  " + builder;
            g.drawString(font, font.plainSubstrByWidth(detail, rw - 46), rx + 3, ry + 12, 0xFFE0E0E0, false);
            // Progress: the builder's "used" share of this order's materials, at least the stage estimate (clearing uses none).
            double frac = o.isClaimed() && o.getStage() != null ? QueueOrder.stageFraction(o.getStage().name()) : -1;
            IBuildingView claimer = o.isClaimed() ? colony.getClientBuildingManager().getBuilding(o.getClaimedBy()) : null;
            var res = claimer == null ? null : claimer.getModuleViewByType(com.minecolonies.core.colony.buildings.moduleviews.BuildingResourcesModuleView.class);
            if (res != null && res.getWorkOrderId() == o.getID()) frac = Math.max(frac, Math.min(1, res.getProgress() / 100.0));
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

    /** @return true when the placing panel offers a level: a hut (survival: not while moving, which is creative anyway) */
    private static boolean levelPicker() {
        return Planner.placing != null && (Planner.creative() || Planner.moving == null);
    }

    // The placing panel's optional rows: one condition each for its height and its drawing.
    private static boolean designPicker() {
        if (Planner.placing == null || Planner.moving != null) return false;
        List<Blueprints.Hut> alts = Blueprints.alts(pack(), Planner.placing);
        return alts != null && alts.size() > 1;
    }

    private static boolean builderPickerShown() {
        return Planner.placing != null && Planner.moving == null && !Planner.instant();
    }

    // Creative build mode: not while moving (always instant).
    private static boolean modePickerShown() {
        return Planner.creative() && Planner.moving == null && Planner.movingDeco == null;
    }

    /** @return the selected building's or decoration's panel height (a creative building's adds the build mode row) */
    private static int selectionH() {
        return Planner.selectedDeco == null && Planner.selected != null && Planner.creative() ? 78 : 58;
    }

    private void selection(GuiGraphics g, int mx, int my) {
        if (Planner.selectedDeco != null) {
            decoSelection(g, mx, my, Planner.selectedDeco);
            return;
        }
        IBuildingView b = Planner.selected;
        if (b == null) return;
        // Bottom, right of the palette (which can run tall).
        // Creative with the server part adds an instant Clean (placeholders) button; it pastes over pending work orders too.
        boolean instant = Planner.creative() && Planner.serverInstant(), open = !Planner.remoteOpenOff();
        int n = instant ? 6 : open ? 4 : 3, w = instant ? PALETTE_W + 136 : PALETTE_W + 44, h = selectionH(), x = 4 + PALETTE_W + 4, y = height - h - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        // Creative: the build mode for Build / Upgrade / Repair, under the buttons.
        if (Planner.creative()) buildModePicker(g, x + PAD, y + PAD + 46, w - 2 * PAD, mx, my);
        g.drawString(font, font.plainSubstrByWidth(name(b) + "  " + levelText(b), w - 2 * PAD), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        String info = Component.translatable("mccitybuilder.workers", b.getAllAssignedCitizens().size()).getString()
                + (b.hasWorkOrder() ? "  " + Component.translatable("mccitybuilder.has_work_order").getString() : "");
        // Survival build-up target: shown with an x that cancels it (the current order stays).
        int upTo = BuildUp.target(b.getPosition());
        if (upTo > 0) {
            String t = Component.translatable("mccitybuilder.build_up", upTo).getString();
            int tx = x + w - PAD - font.width(t) - 10, ty = y + PAD + 11;
            info = font.plainSubstrByWidth(info, tx - x - PAD - 4);
            g.drawString(font, t, tx, ty, 0xFF2050A0, false);
            boolean over = mx >= x + w - PAD - 8 && mx < x + w - PAD && my >= ty - 1 && my < ty + 9;
            g.drawString(font, "x", x + w - PAD - 6, ty, over ? 0xFFFF4040 : 0xFFA02020, false);
            hits.add(new Hit(x + w - PAD - 8, ty - 1, 8, 10, () -> {
                click();
                BuildUp.cancel(b.getPosition());
            }, Component.translatable("mccitybuilder.build_up.cancel")));
        }
        g.drawString(font, font.plainSubstrByWidth(info, w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        // An unbuilt hut (placed by hand, or its order cancelled): the same action reads Build.
        // Survival, unbuilt: Pick up (the hut back to the inventory) stands in for Remove.
        boolean pickUp = pickUpCase(b);
        String[] keys = {b.getBuildingLevel() == 0 ? "build" : "upgrade", "repair", "open", "clean", "move", pickUp ? "pickup" : "remove"};
        // Open uses the client-side building view, so it works however buried or far the hut block is.
        Runnable[] run = {() -> upgrade(b), () -> repair(b), () -> b.openGui(false), () -> {
            if (Planner.creativeClean(b)) toast(Component.translatable("mccitybuilder.clean_sent"));
        }, () -> {
            if (Planner.startMove(b)) toast(Component.translatable("mccitybuilder.move_started"));
        }, () -> remove(b)};
        // Why a button is off, or null when it is on.
        // Builder modes: a second BUILD request would cancel the order (MineColonies toggles it).
        boolean pending = b.hasWorkOrder() && !(instant && Planner.instant());
        boolean townHall = b instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall.View, family = b.getParent() != null && !b.getParent().equals(BlockPos.ZERO);
        String[] why = {b.isBuildingMaxLevel() ? "mccitybuilder.upgrade.max" : pending ? "mccitybuilder.upgrade.pending" : null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : pending ? "mccitybuilder.upgrade.pending" : null, null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : null,
                b.getBuildingLevel() == 0 ? "mccitybuilder.repair.unbuilt" : townHall ? "mccitybuilder.move.townhall" : family ? "mccitybuilder.move.family" : null,
                pickUp ? pickUpOff(b) : removeOff(b)};
        // Survival: no Clean / Move (instant only), and no Open without remote windows (the row closes up).
        int[] keep = instant ? new int[]{0, 1, 2, 3, 4, 5} : open ? new int[]{0, 1, 2, 5} : new int[]{0, 1, 5};
        String[] k2 = new String[keep.length], w2 = new String[keep.length];
        Runnable[] r2 = new Runnable[keep.length];
        for (int i = 0; i < keep.length; i++) {
            k2[i] = keys[keep[i]];
            r2[i] = run[keep[i]];
            w2[i] = why[keep[i]];
        }
        keys = k2;
        run = r2;
        why = w2;
        buttonRow(g, x, y, w, n, keys, run, why, mx, my);
    }

    /** A placed decoration: Move, Repair and Delete in creative (the server part); Repair (MineColonies' order) and Remove (a builder order) in survival. */
    private void decoSelection(GuiGraphics g, int mx, int my, Planner.Deco d) {
        boolean creative = Planner.creative(), server = Planner.serverInstant();
        int w = PALETTE_W, h = 58, x = 4 + PALETTE_W + 4, y = height - h - 4;
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        g.drawString(font, font.plainSubstrByWidth(d.name(), w - 2 * PAD), x + PAD + 1, y + PAD, VanillaUi.LABEL, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.deco.info", d.pack()).getString(), w - 2 * PAD), x + PAD + 1, y + PAD + 11, 0xFF555555, false);
        String off = server ? null : "mccitybuilder.deco.no_server";
        // Survival Repair is MineColonies' own decoration order: it needs the decoration's controller, and an order already
        // there would be cancelled by the same request.
        boolean controller = minecraft.level != null && minecraft.level.getBlockState(d.pos()).getBlock() instanceof com.minecolonies.core.blocks.BlockDecorationController;
        boolean ordered = Planner.colony() != null && Planner.colony().getWorkOrders().stream().anyMatch(o -> d.pos().equals(o.getLocation()));
        String repairOff = creative ? off : !controller ? "mccitybuilder.deco_repair.no_controller" : ordered ? "mccitybuilder.deco_repair.ordered" : null;
        String[] keys = creative ? new String[]{"deco_move", "deco_repair", "deco_delete"} : new String[]{"deco_repair", "deco_remove"};
        Runnable repair = () -> {
            if (creative ? Planner.sendDeco(com.volcanicmg.mccitybuilder.server.InstantBuild.DECO_REPAIR, d) : Planner.requestDecoRepair(d))
                toast(Component.translatable(creative ? "mccitybuilder.deco_repair_sent" : "mccitybuilder.requested"));
        };
        Runnable[] run = creative ? new Runnable[]{() -> {
            if (Planner.startMoveDeco(d)) toast(Component.translatable("mccitybuilder.deco_move_started"));
            else toast(Component.translatable("mccitybuilder.invalid.loading"));
        }, repair, () -> {
            if (Planner.sendDeco(com.volcanicmg.mccitybuilder.server.InstantBuild.DECO_DELETE, d)) toast(Component.translatable("mccitybuilder.deco_delete_sent"));
            Planner.select(null);
        }} : new Runnable[]{repair, () -> {
            if (Planner.sendDeco(com.volcanicmg.mccitybuilder.server.InstantBuild.DECO_REMOVE_ORDER, d)) toast(Component.translatable("mccitybuilder.deco_remove_sent"));
            Planner.select(null);
        }};
        // Another player's road / wall piece: only taken down (a move or repair of one piece would break its run).
        String pieceOff = off != null ? off : d.piece() ? "mccitybuilder.deco.piece" : null;
        buttonRow(g, x, y, w, keys.length, keys, run, creative ? new String[]{pieceOff, d.piece() ? pieceOff : repairOff, off} : new String[]{d.piece() ? pieceOff : repairOff, off}, mx, my);
    }

    /** The selection panel's buttons: label {@code mccitybuilder.<key>}, tip {@code .tip}, or {@code why[i]} when off. */
    private void buttonRow(GuiGraphics g, int x, int y, int w, int n, String[] keys, Runnable[] run, String[] why, int mx, int my) {
        int bw = (w - 2 * PAD - 2 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            int bx = x + PAD + i * (bw + 2), by = y + PAD + 24;
            boolean off = why[i] != null, over = mx >= bx && mx < bx + bw && my >= by && my < by + 20;
            VanillaUi.button(g, bx, by, bw, 20, false, off, over);
            // Remove waiting for its second click: red "Sure?" and the time left draining along the bottom, like Clear.
            float arm = !off && keys[i].equals("remove") ? removeArm() : 0;
            VanillaUi.buttonLabel(g, font, Component.translatable(arm > 0 ? "mccitybuilder.line.clear_sure" : "mccitybuilder." + keys[i]).getString(), bx, by, bw, 20,
                    off ? VanillaUi.INK_DISABLED : arm > 0 ? 0xFFFF5555 : VanillaUi.INK);
            if (arm > 0) g.fill(bx + 2, by + 16, bx + 2 + Math.round((bw - 4) * arm), by + 18, 0xFFFF5555);
            int fi = i;
            hits.add(new Hit(bx, by, bw, 20, () -> {
                if (off) return;
                click();
                run[fi].run();
            }, Component.translatable(off ? why[i] : "mccitybuilder." + keys[i] + ".tip")));
        }
    }

    /**
     * Upgrade: creative pastes at once (any level with CityBuilder on the server, else only level 0 → 1); survival
     * opens the hut's own build window (MineColonies' WindowBuildBuilding: needed materials, the builder to assign,
     * its own request on confirm), which isn't an inventory, so it opens from here whatever the server allows.
     * Creative builder modes: the survival window, the order it makes funded in Builders (free).
     */
    private void upgrade(IBuildingView b) {
        if (!Planner.instant() && Planner.colony() != null) {
            Planner.fundWindow(b.getPosition());
            new com.minecolonies.core.client.gui.WindowBuildBuilding(Planner.colony(), b).open();
            return;
        }
        boolean pasted = Planner.instant() && Planner.creativeUpgrade(b);
        if (!pasted) Planner.requestBuild(b, BuildRequestMessage.Mode.BUILD);
        toast(Component.translatable(pasted ? "mccitybuilder.upgraded_creative" : "mccitybuilder.requested"));
    }

    // Remove's confirm: armed until this time, for this building.
    private static long removeArmed;
    private static BlockPos removeArmedFor;

    /** @return how much of Remove's confirm window is left (1 just armed, 0 not armed) */
    private static float removeArm() {
        long left = removeArmed - System.currentTimeMillis();
        return left <= 0 || Planner.selected == null || !Planner.selected.getPosition().equals(removeArmedFor) ? 0 : left / 3000f;
    }

    /** @return why Remove is off for this building (a lang key), or null */
    private static String removeOff(IBuildingView b) {
        if (b instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall.View) return "mccitybuilder.remove.townhall";
        boolean parent = Planner.colony() != null && Planner.colony().getClientBuildingManager().getBuildings().values().stream().anyMatch(o -> b.getID().equals(o.getParent()));
        if (b.getParent() != null && !b.getParent().equals(BlockPos.ZERO) || parent) return "mccitybuilder.remove.family";
        if (Planner.creative()) return Planner.serverInstant() ? null : "mccitybuilder.remove.no_server";
        return b.hasWorkOrder() ? "mccitybuilder.remove.ordered" : null;
    }

    /**
     * Remove (button or Delete): the first press arms it for 3 s, the second removes. Creative: City Builder's
     * instant remove (contents to the player, site cleared); survival: MineColonies' own deconstruct order for a builder.
     */
    private void remove(IBuildingView b) {
        if (pickUpCase(b)) {
            // No confirm: the hut comes back to the inventory.
            if (pickUpOff(b) == null && Planner.creativeRemove(b)) toast(Component.translatable("mccitybuilder.pickup_sent"));
            Planner.select(null);
            return;
        }
        if (removeOff(b) != null) return;
        if (removeArm() <= 0) {
            removeArmed = System.currentTimeMillis() + 3000;
            removeArmedFor = b.getPosition();
            return;
        }
        removeArmed = 0;
        if (Planner.creative()) {
            if (Planner.creativeRemove(b)) toast(Component.translatable("mccitybuilder.remove_sent"));
        } else {
            Planner.requestBuild(b, BuildRequestMessage.Mode.REMOVE);
            toast(Component.translatable("mccitybuilder.requested"));
        }
        Planner.select(null);
    }

    /** @return true for a survival player's unbuilt (level 0) hut: Remove becomes Pick up */
    private static boolean pickUpCase(IBuildingView b) {
        return !Planner.creative() && b.getBuildingLevel() == 0;
    }

    /** @return why Pick up is off, or null; an open order is no reason (picking up cancels it) */
    private static String pickUpOff(IBuildingView b) {
        String off = removeOff(b);
        if (off != null && !off.equals("mccitybuilder.remove.ordered")) return off;
        return Planner.serverInstant() ? null : "mccitybuilder.pickup.no_server";
    }

    /** Repair: creative Instant with CityBuilder on the server repairs at once; otherwise a builder work order. */
    private void repair(IBuildingView b) {
        boolean pasted = Planner.instant() && Planner.creativeRepair(b);
        if (!pasted) {
            Planner.fund(b.getPosition());
            Planner.requestBuild(b, BuildRequestMessage.Mode.REPAIR);
        }
        toast(Component.translatable(pasted ? "mccitybuilder.repaired_creative" : "mccitybuilder.requested"));
    }

    private static String name(IBuildingView b) {
        String custom = b.getCustomName();
        return custom != null && !custom.isEmpty() ? custom : Component.translatable(b.getBuildingDisplayName()).getString();
    }

    private static String levelText(IBuildingView b) {
        return Component.translatable("mccitybuilder.level", b.getBuildingLevel(), b.getBuildingMaxLevel()).getString();
    }

    // ---- coach tips ----

    /**
     * The line tool's first-use tips, one at a time ({@link LineHelp#TIPS}): a small panel above the screen centre, or
     * left of the line panel for the edit-mode tip, with Skip and Next (Got it on the last); done or skipped, they
     * don't come back (the help screen's Replay tips does).
     */
    private void coachTip(GuiGraphics g, int mx, int my) {
        // The Route tip (once, the first time Route is turned on) waits for the line tips to finish.
        boolean route = tipStep < 0 && routeTip && RouteView.on;
        if (!route && tipStep < 0 || !LineView.active()) {
            if (!LineView.active()) linePanelBox = null;
            return;
        }
        String[] tip = route ? new String[]{"route", "panel"} : LineHelp.TIPS.get(tipStep);
        int tp = LineHelp.TIP_PAD, w = LineHelp.TIP_W;
        List<String> lines = LineHelp.wrap(Component.translatable("mccitybuilder.tip." + tip[0]).getString(), w - 2 * tp, font::width);
        int h = tp + 11 + lines.size() * 10 + 4 + 16 + tp;
        // Under the top bar, out of the drawing's way.
        int x = (width - w) / 2, y = 40;
        if (tip[1].equals("panel") && linePanelBox != null && linePanelBox[0] - w - 6 >= 0) {
            x = linePanelBox[0] - w - 6;
            y = Math.min(height - h - 4, linePanelBox[1] + 4);
        }
        // Always fully on screen.
        x = Math.max(4, Math.min(x, width - 4 - w));
        y = Math.max(4, Math.min(y, height - 4 - h));
        panels.add(new int[]{x, y, w, h});
        VanillaUi.panel(g, x, y, w, h);
        g.drawString(font, route ? Component.translatable("mccitybuilder.tip.route_title") : Component.translatable("mccitybuilder.tip.step", tipStep + 1, LineHelp.TIPS.size()),
                x + tp, y + tp, 0xFF206080, false);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + tp, y + tp + 11 + i * 10, VanillaUi.LABEL, false);
        boolean last = route || tipStep == LineHelp.TIPS.size() - 1;
        String[] keys = last ? new String[]{"done"} : new String[]{"skip", "next"};
        int bw = 50, by = y + h - tp - 16;
        for (int i = 0; i < keys.length; i++) {
            int bx = x + w - tp - (keys.length - i) * (bw + 2) + 2;
            boolean over = mx >= bx && mx < bx + bw && my >= by && my < by + 16;
            VanillaUi.button(g, bx, by, bw, 16, false, false, over);
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.tip." + keys[i]).getString(), bx, by, bw, 16, VanillaUi.INK);
            boolean next = keys[i].equals("next");
            // Before every other hit: the tip lies on top.
            hits.add(0, new Hit(bx, by, bw, 16, () -> {
                if (next) tipStep++;
                else if (route) {
                    routeTip = false;
                    Config.ROUTE_TIP_SEEN.set(true);
                    Config.ROUTE_TIP_SEEN.save();
                } else {
                    tipStep = -1;
                    Config.LINE_TIPS_SEEN.set(true);
                    Config.LINE_TIPS_SEEN.save();
                }
            }, null));
        }
        // After its buttons: the tip's body takes clicks and tooltips too.
        hits.add(keys.length, new Hit(x, y, w, h, BLOCK, null));
    }

    // ---- controls guide ----

    /** @return the width the controls guide takes at the right edge (just its "?" button while closed) */
    private int guideRoom() {
        return guideFits() ? Hints.WIDTH : 20;
    }

    /** @return true when the guide is on and there is room for it beside the palette and the line panel (else just "?") */
    private boolean guideFits() {
        return Config.SHOW_HINTS.get() && width >= 4 + PALETTE_W + 4 + 220 + 4 + Hints.WIDTH + 4;
    }

    private void guide(GuiGraphics g, int mx, int my) {
        int w = Hints.WIDTH, x = width - 4 - w;
        boolean open = guideFits();
        String state = guideState();
        com.volcanicmg.mccitybuilder.planner.Plan.Line sl = PlanView.selectedLine();
        Hints.Ctx ctx = new Hints.Ctx(sl != null ? sl.isWall() : LineView.wall(), LineView.active() && !LineView.fillTargets().isEmpty());
        // The camera keys only while nothing else is going on; the guide must stay below the queue panel.
        List<String> ids = state.equals("browse") ? List.of(state, "view") : List.of(state);
        boolean all = Config.ALL_HINTS.get();
        Hints.Laid laid = open ? Hints.layout(ids, all, ctx, PlannerScreen::hintText, font::width) : null;
        int room = height - 8 - queueBottom;
        // Too tall: the main controls only, then without the camera, then just the "?" button.
        if (open && all && Hints.PAD + 20 + laid.height() + Hints.PAD > room) laid = Hints.layout(ids, false, ctx, PlannerScreen::hintText, font::width);
        if (open && Hints.PAD + 20 + laid.height() + Hints.PAD > room) laid = Hints.layout(List.of(state), false, ctx, PlannerScreen::hintText, font::width);
        if (open && Hints.PAD + 20 + laid.height() + Hints.PAD > room) open = false;
        int h = open ? Hints.PAD + 20 + laid.height() + Hints.PAD : 20;
        int y = height - 4 - h;
        int bx = open ? x + w - 4 - 18 : width - 4 - 20, by = open ? y + 4 : y, bs = open ? 18 : 20;
        if (open) {
            panels.add(new int[]{x, y, w, h});
            VanillaUi.panel(g, x, y, w, h);
            g.drawString(font, Component.translatable("mccitybuilder.hint.title"), x + Hints.PAD + 1, y + Hints.PAD + 2, VanillaUi.LABEL, false);
            // All / Less, left of the "?" button.
            String al = Component.translatable(all ? "mccitybuilder.hint.less" : "mccitybuilder.hint.all").getString();
            int aw = font.width(al) + 10, ax = x + w - 4 - 18 - 2 - aw, ay = y + 4;
            boolean overA = mx >= ax && mx < ax + aw && my >= ay && my < ay + 18;
            VanillaUi.button(g, ax, ay, aw, 18, false, false, overA);
            g.drawString(font, al, ax + 5, ay + 5, VanillaUi.INK, false);
            hits.add(new Hit(ax, ay, aw, 18, () -> {
                Config.ALL_HINTS.set(!Config.ALL_HINTS.get());
                Config.ALL_HINTS.save();
            }, Component.translatable("mccitybuilder.hint.all.tip")));
            // The road / wall help, left of All while a line tool is on.
            if (LineView.active()) {
                String hl = Component.translatable("mccitybuilder.hint.help").getString();
                int hw = font.width(hl) + 10, hx = ax - 2 - hw;
                boolean overH = mx >= hx && mx < hx + hw && my >= ay && my < ay + 18;
                VanillaUi.button(g, hx, ay, hw, 18, false, false, overH);
                g.drawString(font, hl, hx + 5, ay + 5, VanillaUi.INK, false);
                hits.add(new Hit(hx, ay, hw, 18, () -> minecraft.setScreen(new PonderScreen()), Component.translatable("mccitybuilder.help.button")));
            }
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

    /** @return the controls guide's state ({@link Hints#SECTIONS}): what the player is doing right now */
    private static String guideState() {
        if (Planner.adjusting()) return "adjusting";
        if (Planner.isPlacing()) return Planner.deco != null ? "placing_deco" : "placing";
        if (LineView.active()) {
            if (LineView.edit) {
                if (LineView.selectedJoint != null) return "line_edit_joint";
                com.volcanicmg.mccitybuilder.planner.Plan.Line sl = PlanView.selectedLine();
                if (sl != null) return PlanView.locked(sl) ? "line_edit_built" : "line_edit_segment";
                return "line_edit";
            }
            if (LineView.start != null) return "connect".equals(LineView.snap) ? "line_connect" : "line_draw";
            return "line_idle";
        }
        if (PlanView.hasSelection()) return PlanView.entries.get(PlanView.selected).isHut() ? "planned" : "planned_deco";
        if (Planner.selected != null) return "selected";
        if (Planner.selectedDeco != null) return "selected_deco";
        return "browse";
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
        // Right-click on a decoration (or folder) in the palette: set its folder up as a road / wall set.
        if (button == 1 && !blockedAt(mx, my))
            for (Hit h : setupHits)
                if (h.in(mx, my)) {
                    click();
                    h.action().run();
                    return true;
                }
        Hit top = null;
        if (button == 0)
            for (Hit h : hits)
                if (h.in(mx, my)) {
                    if (h.action() == BLOCK) {
                        top = h;
                        break;
                    }
                    h.action().run();
                    return true;
                }
        // Dragging in the piece preview turns it (unless a panel drawn above it covers that spot).
        if (button == 0 && cardBox != null && mx >= cardBox[0] && mx < cardBox[0] + cardBox[2] && my >= cardBox[1] && my < cardBox[1] + cardBox[3]
                && (top == null || top == bigBlock || top == lineBlock && !bigPreview)) {
            cardDrag = PieceView.dragging = true;
            return true;
        }
        // A blocking panel (the line, Pieces or large preview panel) takes the click: nothing under it gets it.
        if (top != null) return true;
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
            else if (m.citizen() != null && !Planner.remoteOpenOff()) new com.minecolonies.core.client.gui.citizen.MainWindowCitizen(m.citizen()).open();
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
            if (RouteView.on) {
                if (RouteView.click(hit)) click();
                return true;
            }
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
        Object picked = pe >= 0 ? null : Planner.pickUnder(mx / width, my / height, hit);
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
                    if (RouteView.on) {
                        if (!RouteView.back()) RouteView.reset();
                    } else if (LineView.start != null) LineView.finishChain();
                    // On a planned segment: remove it (Undo brings it back); a built one only by Del / Remove; elsewhere: leave the tool.
                    else if (LineView.hoverBuilt()) {
                        LineView.flash = "mccitybuilder.line.built_right_click";
                        LineView.flashUntil = System.currentTimeMillis() + 2500;
                    } else if (LineView.hoverLine >= 0 && LineView.removeHovered()) toast(Component.translatable("mccitybuilder.line.removed"));
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
            // A click without a drag opens the assigned builder's hut.
            else if (dragOver == dragFrom && Planner.colony() != null && dragFrom < shownOrders.size() && shownOrders.get(dragFrom).isClaimed()) {
                IBuildingView b = Planner.colony().getClientBuildingManager().getBuilding(shownOrders.get(dragFrom).getClaimedBy());
                if (b != null && !Planner.remoteOpenOff()) {
                    click();
                    b.openGui(false);
                }
            }
            dragFrom = dragOver = -1;
            return true;
        }
        return false;
    }

    private boolean scrolledImpl(double mx, double my, double sy) {
        // A panel over the preview (the Pieces panel, the joint menu, a tip) takes the wheel.
        Hit blocker = blockerAt(mx, my);
        if (blocker != null && blocker != lineBlock && blocker != bigBlock && blocker != matBlock) return true;
        if (matListBox != null && mx >= matListBox[0] && mx < matListBox[0] + matListBox[2] && my >= matListBox[1] && my < matListBox[1] + matListBox[3]) {
            MaterialsView.scroll = Math.max(0, MaterialsView.scroll - (int) Math.signum(sy));
            return true;
        }
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
        if (LineView.active() && RouteView.on) {
            // Route mode: Esc backs out (a search or preview is cancelled; at the start the mode ends), Enter accepts.
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (!RouteView.back()) RouteView.reset();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (RouteView.accept()) toast(Component.translatable("mccitybuilder.route.accepted"));
                return true;
            }
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
                case GLFW.GLFW_KEY_R -> {
                    if (!LineView.flipElbow()) LineView.toggleFlip();
                }
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
                case GLFW.GLFW_KEY_F -> LineView.fillGaps();
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
                case GLFW.GLFW_KEY_DELETE -> {
                    if (removeOff(b) == null) {
                        click();
                        remove(b);
                    }
                }
                case GLFW.GLFW_KEY_U -> {
                    if (!b.isBuildingMaxLevel() && (!b.hasWorkOrder() || Planner.instant() && Planner.serverInstant())) {
                        click();
                        upgrade(b);
                    }
                }
                case GLFW.GLFW_KEY_P -> {
                    if (b.getBuildingLevel() > 0 && (!b.hasWorkOrder() || Planner.instant() && Planner.serverInstant())) {
                        click();
                        repair(b);
                    }
                }
                case GLFW.GLFW_KEY_O -> {
                    if (!Planner.remoteOpenOff()) b.openGui(false);
                }
                default -> {
                }
            }
        }
        return true;
    }
}
