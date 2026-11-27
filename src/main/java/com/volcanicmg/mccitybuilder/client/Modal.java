package com.volcanicmg.mccitybuilder.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A small box over a screen that takes every click until one of its buttons is picked: a question ("Discard 3
 * unsaved changes?" Discard / Keep editing) or a short pick list ("Same as…"). Esc closes it (the screen's onClose
 * checks {@link #open} first). A list longer than the screen scrolls (wheel or ▲ ▼); its last button (Cancel) stays
 * pinned under it.
 */
final class Modal {
    /** One button: its label and what it does (the box closes first). */
    record Choice(String label, Runnable run) {
    }

    private String text;
    private List<Choice> choices;
    private final List<int[]> rects = new ArrayList<>();

    /** Opens the box. */
    void ask(String text, List<Choice> choices) {
        this.text = text;
        this.choices = choices;
        scroll = 0;
    }

    private int scroll, shown, scrollable;

    /** Scrolls the list (the wheel over the screen). @return true while the box is open (nothing else scrolls) */
    boolean scroll(double amount) {
        if (choices == null) return false;
        scroll = Math.max(0, Math.min(scrollable - shown, scroll - (int) Math.signum(amount)));
        return true;
    }

    boolean open() {
        return choices != null;
    }

    void close() {
        choices = null;
    }

    /** Draws it centred over the screen (dimming the rest); the buttons stack under the text, as many as fit. */
    void draw(GuiGraphics g, Font font, int width, int height, int mx, int my) {
        rects.clear();
        if (choices == null) return;
        // Above everything drawn so far: the screen's 3D outlines (scene space, any depth) included.
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);
        g.fill(0, 0, width, height, 0x90000000);
        // Drawn now: batched, it would land over the box's labels.
        g.flush();
        int w = Math.min(width - 20, 260), iw = w - 14;
        var lines = font.split(net.minecraft.network.chat.Component.literal(text), iw);
        int fit = Math.max(2, (height - 40 - lines.size() * 10) / 20);
        // Too many to show: all but the last scroll, the last (Cancel) stays put under them.
        boolean scrolls = choices.size() > fit;
        scrollable = scrolls ? choices.size() - 1 : choices.size();
        shown = scrolls ? fit - 1 : scrollable;
        scroll = Math.max(0, Math.min(scroll, scrollable - shown));
        int n = shown + (scrolls ? 1 : 0), bw = scrolls ? iw - 18 : iw;
        int h = 14 + lines.size() * 10 + 6 + n * 20, x = (width - w) / 2, y = Math.max(4, (height - h) / 2);
        VanillaUi.panel(g, x, y, w, h);
        int iy = y + 7;
        for (var l : lines) {
            g.drawString(font, l, x + 7, iy, VanillaUi.LABEL, false);
            iy += 10;
        }
        iy += 6;
        int listTop = iy;
        for (int k = 0; k < n; k++) {
            int i = scrolls && k == n - 1 ? choices.size() - 1 : scroll + k;
            int bx = x + 7, w2 = scrolls && k == n - 1 ? iw : bw;
            boolean over = mx >= bx && mx < bx + w2 && my >= iy && my < iy + 18;
            VanillaUi.button(g, bx, iy, w2, 18, false, false, over);
            VanillaUi.buttonLabel(g, font, choices.get(i).label(), bx, iy, w2, 18, VanillaUi.INK);
            rects.add(new int[]{bx, iy, w2, 18, i});
            iy += 20;
        }
        if (scrolls) {
            // ▲ ▼ beside the scrolled part (-1 / -2: scroll up / down).
            int ax = x + 7 + bw + 2, listH = shown * 20 - 2;
            for (int d = 0; d < 2; d++) {
                int ay = d == 0 ? listTop : listTop + listH - 18;
                boolean on = d == 0 ? scroll > 0 : scroll < scrollable - shown, over = mx >= ax && mx < ax + 16 && my >= ay && my < ay + 18;
                VanillaUi.button(g, ax, ay, 16, 18, false, !on, on && over);
                VanillaUi.buttonLabel(g, font, d == 0 ? "\u25b2" : "\u25bc", ax, ay, 16, 18, VanillaUi.ink(false, !on));
                rects.add(new int[]{ax, ay, 16, 18, -1 - d});
            }
        }
        g.pose().popPose();
    }

    /** @return the open box's text and buttons ("text | a | b"), or null when closed (the dev bridge reads it) */
    String describe() {
        if (choices == null) return null;
        StringBuilder sb = new StringBuilder(text);
        for (Choice c : choices) sb.append(" | ").append(c.label());
        return sb.toString();
    }

    /** Presses button i (negative: from the end), as a click would. @return false when closed or out of range */
    boolean press(int i) {
        if (choices == null) return false;
        int k = i < 0 ? choices.size() + i : i;
        if (k < 0 || k >= choices.size()) return false;
        Choice c = choices.get(k);
        choices = null;
        c.run().run();
        return true;
    }

    /** @return true when the box is open (a click outside its buttons does nothing) */
    boolean click(double mx, double my) {
        if (choices == null) return false;
        for (int[] r : rects)
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (r[4] < 0) {
                    scroll(r[4] == -1 ? 1 : -1);
                    break;
                }
                Choice c = choices.get(r[4]);
                choices = null;
                PlannerScreen.click();
                c.run().run();
                break;
            }
        return true;
    }
}
