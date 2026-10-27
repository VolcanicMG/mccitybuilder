package com.volcanicmg.mccitybuilder.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Vanilla-styled chrome for the planner's panels: inventory-style panels, the vanilla button,
 * slider, scroller and text-field sprites, inventory-slot insets and colour swatches. Sprites draw immediately while
 * fills are batched, so sprite helpers flush first; anything drawn after them (icons, text) lands on top.
 */
final class VanillaUi {
    /** Inventory-style panel colours, label text, button ink, inset slot, swatch border. */
    static final int PANEL = 0xFFC6C6C6, HIGHLIGHT = 0xFFFFFFFF, SHADOW = 0xFF555555, OUTLINE = 0xFF000000, LABEL = 0xFF404040,
            INK = 0xFF3F3F3F, INK_SELECTED = 0xFFFFFFFF, INK_DISABLED = 0xFFA0A0A0,
            SLOT = 0xFF8B8B8B, SLOT_DARK = 0xFF373737, SLOT_LIGHT = 0xFFFFFFFF, SWATCH_BORDER = 0xFF373737;

    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button"),
            BUTTON_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted"),
            BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            SLIDER = ResourceLocation.withDefaultNamespace("widget/slider"),
            SLIDER_HOVER = ResourceLocation.withDefaultNamespace("widget/slider_highlighted"),
            HANDLE = ResourceLocation.withDefaultNamespace("widget/slider_handle"),
            HANDLE_HOVER = ResourceLocation.withDefaultNamespace("widget/slider_handle_highlighted"),
            SCROLLER = ResourceLocation.withDefaultNamespace("widget/scroller"),
            SCROLLER_BG = ResourceLocation.withDefaultNamespace("widget/scroller_background"),
            TEXT_FIELD = ResourceLocation.withDefaultNamespace("widget/text_field_highlighted");

    private VanillaUi() {
    }

    /** Inventory-style frame: black outline with cut corners, 2 px white / #555555 bevel, #C6C6C6 body. */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        int o = OUTLINE;
        g.fill(x + 2, y, x + w - 2, y + 1, o);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, o);
        g.fill(x, y + 2, x + 1, y + h - 2, o);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, o);
        g.fill(x + 1, y + 1, x + 2, y + 2, o);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, o);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, o);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, o);
        g.fill(x + 2, y + 1, x + w - 3, y + 3, HIGHLIGHT);
        g.fill(x + 1, y + 2, x + 3, y + h - 3, HIGHLIGHT);
        g.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, SHADOW);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, SHADOW);
    }

    /**
     * A toggle or action button: the vanilla button sprite (highlighted on hover, greyed when disabled); a selected
     * toggle is an inset like an inventory slot.
     */
    static void button(GuiGraphics g, int x, int y, int w, int h, boolean selected, boolean disabled, boolean hover) {
        if (selected) {
            slot(g, x, y, w, h);
            return;
        }
        g.flush();
        g.blitSprite(disabled ? BUTTON_DISABLED : hover ? BUTTON_HOVER : BUTTON, x, y, w, h);
    }

    /** @return icon / label colour for a button state */
    static int ink(boolean selected, boolean disabled) {
        return disabled ? INK_DISABLED : selected ? INK_SELECTED : INK;
    }

    /** Inventory-slot inset: #373737 top-left, white bottom-right, #8B8B8B inside. */
    static void slot(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT);
        g.fill(x, y, x + w - 1, y + 1, SLOT_DARK);
        g.fill(x, y, x + 1, y + h - 1, SLOT_DARK);
        g.fill(x + 1, y + h - 1, x + w, y + h, SLOT_LIGHT);
        g.fill(x + w - 1, y + 1, x + w, y + h, SLOT_LIGHT);
    }

    /** A colour swatch with a 1 px dark border (checker when translucent); selected gets a white ring. */
    static void swatch(GuiGraphics g, int x, int y, int w, int h, int argb, boolean selected) {
        if (selected) g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFFFFFFFF);
        g.fill(x, y, x + w, y + h, SWATCH_BORDER);
        if ((argb >>> 24) != 0xFF) checker(g, x + 1, y + 1, w - 2, h - 2);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, argb);
    }

    /** Light/grey checkerboard (4 px squares) behind translucent colours. */
    static void checker(GuiGraphics g, int x0, int y0, int w, int h) {
        for (int yy = 0; yy < h; yy += 4)
            for (int xx = 0; xx < w; xx += 4)
                g.fill(x0 + xx, y0 + yy, x0 + Math.min(w, xx + 4), y0 + Math.min(h, yy + 4),
                        (xx / 4 + yy / 4) % 2 == 0 ? 0xFFFFFFFF : 0xFFBFBFBF);
    }

    /** The vanilla slider: track, handle at {@code v} (0..1); the caller draws the label on top. */
    static void slider(GuiGraphics g, int x, int y, int w, int h, double v, boolean hover) {
        g.flush();
        g.blitSprite(hover ? SLIDER_HOVER : SLIDER, x, y, w, h);
        g.blitSprite(hover ? HANDLE_HOVER : HANDLE, x + (int) Math.round(v * (w - 8)), y, 8, h);
    }

    /** The vanilla scrollbar: dark track and the thumb. */
    static void scroller(GuiGraphics g, int x, int y, int h, int thumbY, int thumbH) {
        g.flush();
        g.blitSprite(SCROLLER_BG, x, y, 6, h);
        g.blitSprite(SCROLLER, x, thumbY, 6, thumbH);
    }

    /** The vanilla (focused) text field box. */
    static void textField(GuiGraphics g, int x, int y, int w, int h) {
        g.flush();
        g.blitSprite(TEXT_FIELD, x, y, w, h);
    }
}
