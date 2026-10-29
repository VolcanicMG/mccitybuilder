package com.volcanicmg.mccitybuilder.client;

import com.volcanicmg.mccitybuilder.planner.LineHelp;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** "How roads and walls work": the pages of {@link LineHelp}, one at a time; Esc or Close goes back to the planner. */
final class LineHelpScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable run) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private int page;

    LineHelpScreen() {
        super(Component.translatable("mccitybuilder.help.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(Planner.active() ? new PlannerScreen() : null);
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
        int w = LineHelp.W, h = LineHelp.H, x = (width - w) / 2, y = Math.max(4, (height - h) / 2), p = LineHelp.PAD;
        VanillaUi.panel(g, x, y, w, h);
        LineHelp.Page pg = LineHelp.PAGES.get(page);
        g.drawString(font, title, x + p, y + p, VanillaUi.LABEL, false);
        String num = Component.translatable("mccitybuilder.help.page", page + 1, LineHelp.PAGES.size()).getString();
        g.drawString(font, num, x + w - p - font.width(num), y + p, 0xFF707070, false);
        g.drawString(font, Component.translatable("mccitybuilder.help." + pg.id() + ".title"), x + p, y + p + 14, 0xFF206080, false);
        g.fill(x + p, y + p + 24, x + w - p, y + p + 25, VanillaUi.SLOT);
        int ty = y + p + 30;
        if (pg.diagram() == null) {
            // The colour legend: a swatch and what it means.
            for (int i = 0; i < LineHelp.LEGEND.size(); i++) {
                int ry = ty + i * 13;
                g.fill(x + p, ry, x + p + 10, ry + 10, 0xFF202020);
                g.fill(x + p + 1, ry + 1, x + p + 9, ry + 9, LineHelp.LEGEND_COLOURS[i]);
                g.drawString(font, Component.translatable("mccitybuilder.help.colours." + LineHelp.LEGEND.get(i)), x + p + 16, ry + 1, 0xFF404040, false);
            }
        } else {
            for (String line : LineHelp.pageLines(pg, k -> Component.translatable(k).getString(), font::width)) {
                g.drawString(font, line, x + p, ty, 0xFF404040, false);
                ty += LineHelp.LINE_H;
            }
            // The diagram, right of the text, on a slot.
            String[] d = pg.diagram();
            int c = LineHelp.CELL, dw = d[0].length() * c, dh = d.length * c, dx = x + w - p - dw - 4, dy = y + p + 30;
            VanillaUi.slot(g, dx - 2, dy - 2, dw + 4, dh + 4);
            for (int r = 0; r < d.length; r++)
                for (int k = 0; k < d[r].length(); k++) {
                    int col = LineHelp.color(d[r].charAt(k));
                    if (col != 0) g.fill(dx + k * c, dy + r * c, dx + k * c + c, dy + r * c + c, col);
                }
        }
        // Back, Next, Replay tips, Close.
        String[] keys = {"back", "next", "replay", "close"};
        int bw = (w - 2 * p - 6) / 4, by = y + h - p - 20;
        for (int i = 0; i < 4; i++) {
            int bx = x + p + i * (bw + 2), fi = i;
            boolean off = i == 0 && page == 0 || i == 1 && page == LineHelp.PAGES.size() - 1;
            boolean over = mx >= bx && mx < bx + bw && my >= by && my < by + 20;
            VanillaUi.button(g, bx, by, bw, 20, false, off, over);
            VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.help." + keys[i]).getString(), bx, by, bw, 20, off ? VanillaUi.INK_DISABLED : VanillaUi.INK);
            if (!off) hits.add(new Hit(bx, by, bw, 20, () -> {
                if (fi == 0) page--;
                else if (fi == 1) page++;
                else if (fi == 2) {
                    PlannerScreen.tipStep = 0;
                    onClose();
                } else onClose();
            }));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0)
            for (Hit h : hits)
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    h.run().run();
                    return true;
                }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_RIGHT && page < LineHelp.PAGES.size() - 1) page++;
        else if (key == GLFW.GLFW_KEY_LEFT && page > 0) page--;
        else return super.keyPressed(key, scan, mods);
        return true;
    }
}
