package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.Hints;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.LineHelp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Offline mock of the planner screen at GUI scale 2 with the real vanilla font and widget sprites (read from the
 * Minecraft client jar at run time) and MineColonies' hut names (from libs/): palette, builder queue, selected
 * building and controls guide, laid out like {@code PlannerScreen}. Hut icons are letter placeholders.
 */
public final class PlannerPreview {
    private static final int PAD = 7, SLOT = 18, COLS = 9, PALETTE_W = PAD * 2 + COLS * SLOT, QUEUE_W = 210, ROW_H = 24;
    private static final int PANEL = 0xFFC6C6C6, LABEL = 0xFF404040, INK = 0xFF3F3F3F, SLOT_BG = 0xFF8B8B8B, SLOT_DARK = 0xFF373737;

    public static void main(String[] args) throws Exception {
        int sw = 560, sh = 400, scale = 2;
        BufferedImage img = new BufferedImage(sw * scale, sh * scale, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        McAssets a = new McAssets(g, scale);
        // Stand-in for the world seen from above.
        for (int y = 0; y < sh; y += 8)
            for (int x = 0; x < sw; x += 8) a.fill(x, y, 8, 8, ((x / 8 * 7 + y / 8 * 13) % 5 == 0) ? 0xFF5E7F3A : 0xFF6A8C42);
        String ownLang = Files.readString(Path.of("src/main/resources/assets/mccitybuilder/lang/en_us.json"), StandardCharsets.UTF_8);
        String mcLang;
        try (ZipFile z = new ZipFile("libs/minecolonies-1.1.1403-1.21.1.jar")) {
            mcLang = new String(z.getInputStream(z.getEntry("assets/minecolonies/lang/en_us.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
        Function<String, String> tr = k -> {
            if (k.equals("@toggle")) return "J";
            for (String src : new String[]{ownLang, mcLang}) {
                int i = src.indexOf("\"" + k + "\":");
                if (i >= 0) {
                    int b = src.indexOf('"', src.indexOf(':', i) + 1) + 1;
                    return src.substring(b, src.indexOf('"', b)).replace("\\u2026", "…");
                }
            }
            return k;
        };
        String banner = tr.apply("mccitybuilder.banner").replace("%s", "Riverside");
        int bw = a.width(banner) + 16;
        frame(a, (sw - bw) / 2, 2, bw, 16);
        a.text(banner, (sw - bw) / 2 + 8, 6, LABEL);
        palette(a, tr);
        queue(a, sw, tr);
        selection(a, sw, sh, tr);
        // The ADJUST action bar under a dropped ghost (here: screen centre).
        int bw2 = 6 * 22 + 6, bx2 = (sw - bw2) / 2, by2 = sh / 2;
        frame(a, bx2, by2, bw2, 28);
        String[] icons = {"place", "rotate", "mirror", "up", "down", "cancel"};
        for (int i = 0; i < icons.length; i++) iconButton(a, icons[i], bx2 + 4 + i * 22, by2 + 4);
        guide(a, List.of("adjusting"), false, Hints.Ctx.NONE, sw, sh, tr);
        g.dispose();
        File out = new File(args[0]);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
        // The guide alone per planner state, compact and with every row, beside the mock: <name>-guide-<state>-<compact|all>.png.
        String base = out.getPath().replaceAll("\\.png$", "");
        // Every state; the line tool's for walls and for roads (both with gaps to fill).
        for (String id : Hints.SECTIONS.keySet()) {
            if (id.equals("view")) continue;
            List<String> ids = id.equals("browse") ? List.of(id, "view") : List.of(id);
            for (boolean wall : Hints.LINE_STATES.contains(id) ? new boolean[]{true, false} : new boolean[]{true})
                for (boolean all : new boolean[]{false, true})
                    guidePng(ids, all, new Hints.Ctx(wall, true), tr, new File(base + "-guide-" + id + (Hints.LINE_STATES.contains(id) ? wall ? "-wall" : "-road" : "")
                            + (all ? "-all" : "-compact") + ".png"));
        }
        // The road / wall help pages and the coach tips: <name>-help-<n>-<page>.png, <name>-tip-<n>-<id>.png.
        // (The help is 3D now: PonderScreen, checked live.)
        for (int i = 0; i < LineHelp.TIPS.size(); i++) tipPng(i, tr, new File(base + "-tip-" + (i + 1) + "-" + LineHelp.TIPS.get(i)[0] + ".png"));
    }

    /** One coach tip as PlannerScreen draws it, at GUI scale 2. */
    private static void tipPng(int i, Function<String, String> tr, File f) throws Exception {
        int tp = LineHelp.TIP_PAD, w = LineHelp.TIP_W;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D pg = probe.createGraphics();
        McAssets m = new McAssets(pg, 2);
        List<String> lines = LineHelp.wrap(tr.apply("mccitybuilder.tip." + LineHelp.TIPS.get(i)[0]), w - 2 * tp, m::width);
        pg.dispose();
        int h = tp + 11 + lines.size() * 10 + 4 + 16 + tp;
        BufferedImage img = new BufferedImage((w + 8) * 2, (h + 8) * 2, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        McAssets a = new McAssets(g, 2);
        a.fill(0, 0, w + 8, h + 8, 0xFF6A8C42);
        int x = 4, y = 4;
        frame(a, x, y, w, h);
        a.text(tr.apply("mccitybuilder.tip.step").replaceFirst("%s", "" + (i + 1)).replaceFirst("%s", "" + LineHelp.TIPS.size()), x + tp, y + tp, 0xFF206080);
        for (int k = 0; k < lines.size(); k++) a.text(lines.get(k), x + tp, y + tp + 11 + k * 10, LABEL);
        boolean last = i == LineHelp.TIPS.size() - 1;
        String[] keys = last ? new String[]{"done"} : new String[]{"skip", "next"};
        int bw = 50, by = y + h - tp - 16;
        for (int k = 0; k < keys.length; k++) {
            int bx = x + w - tp - (keys.length - k) * (bw + 2) + 2;
            a.sprite("widget/button", bx, by, bw, 16);
            String l = tr.apply("mccitybuilder.tip." + keys[k]);
            a.text(l, bx + (bw - a.width(l)) / 2, by + 4, INK);
        }
        g.dispose();
        ImageIO.write(img, "png", f);
        System.out.println("wrote " + f);
    }

    /** One guide panel on a plain background, at GUI scale 2. */
    private static void guidePng(List<String> ids, boolean all, Hints.Ctx ctx, Function<String, String> tr, File f) throws Exception {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D pg = probe.createGraphics();
        Hints.Laid laid = Hints.layout(ids, all, ctx, tr, new McAssets(pg, 2)::width);
        pg.dispose();
        int w = Hints.WIDTH + 8, h = Hints.PAD + 20 + laid.height() + Hints.PAD + 8;
        BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        McAssets a = new McAssets(g, 2);
        a.fill(0, 0, w, h, 0xFF6A8C42);
        guide(a, ids, all, ctx, w, h, tr);
        g.dispose();
        ImageIO.write(img, "png", f);
        System.out.println("wrote " + f);
    }

    private static void palette(McAssets a, Function<String, String> tr) throws Exception {
        List<String> huts = new ArrayList<>();
        for (String id : List.of("blockhuttownhall", "blockhutbuilder", "blockhutcitizen", "blockhutwarehouse", "blockhutdeliveryman", "blockhuttavern"))
            if (HutCatalog.category(id).equals("core")) huts.add(id);
        String[][] mats = {{"64/64", "Oak Planks", "0xFF206020"}, {"20/48", "Cobblestone", "0xFF806000"}, {"0/12", "Glass Pane", "0xFFA02020"}, {"8/8", "Torch", "0xFF206020"}};
        int rows = (huts.size() + COLS - 1) / COLS, x = 4, y = 4, w = PALETTE_W;
        int h = PAD + 10 + 20 + 20 + 20 + 10 + SLOT + 4 + rows * SLOT + 6 + 62 + 12 + mats.length * 10 + 10 + PAD;
        frame(a, x, y, w, h);
        int ix = x + PAD, yy = y + PAD;
        a.text(tr.apply("mccitybuilder.huts"), ix + 1, yy, LABEL);
        yy += 10;
        a.sprite("widget/text_field", ix, yy, w - 2 * PAD, 16);
        a.shadowText("bak_", ix + 4, yy + 4, 0xFFE0E0E0);
        yy += 20;
        yy = picker(a, ix, yy, w - 2 * PAD, "minecolonies");
        yy = picker(a, ix, yy, w - 2 * PAD, tr.apply("mccitybuilder.category.core"));
        a.text(tr.apply("mccitybuilder.recent"), ix + 1, yy, LABEL);
        yy += 10;
        for (int i = 0; i < 2; i++) {
            slot(a, ix + i * SLOT, yy, SLOT, SLOT);
            a.shadowText(i == 0 ? "B" : "F", ix + i * SLOT + 6, yy + 5, 0xFFFFFFFF);
        }
        yy += SLOT + 4;
        for (int i = 0; i < huts.size(); i++) {
            int sx = ix + i % COLS * SLOT, sy = yy + i / COLS * SLOT;
            slot(a, sx, sy, SLOT, SLOT);
            String n = tr.apply("block.minecolonies." + huts.get(i));
            a.shadowText(n.substring(0, 1), sx + 6, sy + 5, 0xFFFFFFFF);
            if (i == 1) {
                a.fill(sx + 1, sy + 1, SLOT - 2, 1, 0xFFFFFFFF);
                a.fill(sx + 1, sy + SLOT - 2, SLOT - 2, 1, 0xFFFFFFFF);
                a.fill(sx + 1, sy + 1, 1, SLOT - 2, 0xFFFFFFFF);
                a.fill(sx + SLOT - 2, sy + 1, 1, SLOT - 2, 0xFFFFFFFF);
            }
            if (i >= 4) a.fill(sx + 1, sy + 1, SLOT - 2, SLOT - 2, 0x908B8B8B);
        }
        yy += rows * SLOT + 6;
        a.text(tr.apply("block.minecolonies.blockhutbuilder"), ix + 1, yy, LABEL);
        a.text(tr.apply("mccitybuilder.adjust_then_place"), ix + 1, yy + 11, 0xFF206020);
        a.text(tr.apply("mccitybuilder.rotation").replaceFirst("%s", "90"), ix + 1, yy + 22, LABEL);
        a.text(tr.apply("mccitybuilder.height").replace("%s", "+1"), ix + 1, yy + 37, LABEL);
        int iw = PALETTE_W - 2 * PAD;
        iconButton(a, "up", ix + iw - 40, yy + 32);
        iconButton(a, "down", ix + iw - 20, yy + 32);
        yy += 62;
        a.text(tr.apply("mccitybuilder.materials"), ix + 1, yy, LABEL);
        yy += 12;
        for (String[] m : mats) {
            a.text(m[0], ix + 1, yy, (int) Long.parseLong(m[2].substring(2), 16));
            a.text(m[1], ix + 46, yy, LABEL);
            yy += 10;
        }
        a.text(tr.apply("mccitybuilder.more_items").replace("%s", "7"), ix + 1, yy, 0xFF606060);
    }

    private static int picker(McAssets a, int x, int y, int w, String label) throws Exception {
        a.sprite("widget/button", x, y, 16, 16);
        a.sprite("widget/button", x + w - 16, y, 16, 16);
        slot(a, x + 18, y, w - 36, 16);
        a.text("<", x + 5, y + 4, INK);
        a.text(">", x + w - 10, y + 4, INK);
        a.shadowText(label, x + (w - a.width(label)) / 2, y + 4, 0xFFFFFFFF);
        return y + 20;
    }

    private static void queue(McAssets a, int sw, Function<String, String> tr) throws Exception {
        String[][] orders = {
                {"Build", tr.apply("block.minecolonies.blockhutbaker"), "Lv 0 > 1", "Bob Smith  build_solid", "build"},
                {"Upgrade", tr.apply("block.minecolonies.blockhutcitizen"), "Lv 2 > 3", tr.apply("mccitybuilder.unassigned"), "upgrade"},
                {"Repair", tr.apply("block.minecolonies.blockhutwarehouse"), "Lv 3 > 3", tr.apply("mccitybuilder.unassigned"), "repair"}};
        int x = sw - 4 - QUEUE_W, y = 4, h = PAD + 12 + orders.length * ROW_H + PAD;
        frame(a, x, y, QUEUE_W, h);
        a.text(tr.apply("mccitybuilder.queue").replace("%s", "3"), x + PAD + 1, y + PAD, LABEL);
        int top = y + PAD + 12;
        for (int r = 0; r < orders.length; r++) {
            int ry = top + r * ROW_H, rx = x + PAD, rw = QUEUE_W - 2 * PAD;
            slot(a, rx, ry, rw, ROW_H - 2);
            String[] icon = com.volcanicmg.mccitybuilder.planner.QueueIcons.ICONS.get(orders[r][4]);
            for (int iy = 0; iy < 9; iy++)
                for (int ix2 = 0; ix2 < 9; ix2++) if (icon[iy].charAt(ix2) == '#') a.fill(rx + 3 + ix2, ry + 3 + iy, 1, 1, 0xFFFFFFFF);
            a.shadowText(orders[r][0] + ": " + orders[r][1], rx + 15, ry + 3, 0xFFFFFFFF);
            if (r == 0) {
                a.fill(rx + 1, ry + ROW_H - 4, rw - 2, 1, SLOT_DARK);
                a.fill(rx + 1, ry + ROW_H - 4, (int) ((rw - 2) * com.volcanicmg.mccitybuilder.planner.QueueOrder.stageFraction("BUILD_SOLID")), 1, 0xFF55FF55);
            }
            a.text(orders[r][2] + "  " + orders[r][3], rx + 3, ry + 12, 0xFFE0E0E0);
            int bx = rx + rw - 40;
            a.sprite("widget/button", bx, ry + 2, 18, 18);
            a.sprite("widget/button", bx + 20, ry + 2, 18, 18);
            a.text("B", bx + 6, ry + 7, INK);
            a.text("x", bx + 26, ry + 6, INK);
        }
    }

    private static void selection(McAssets a, int sw, int sh, Function<String, String> tr) throws Exception {
        int w = PALETTE_W, h = 58, x = 4 + PALETTE_W + 4, y = sh - h - 4;
        frame(a, x, y, w, h);
        a.text(tr.apply("block.minecolonies.blockhutbuilder") + "  " + tr.apply("mccitybuilder.level").replaceFirst("%s", "2").replaceFirst("%s", "5"),
                x + PAD + 1, y + PAD, LABEL);
        a.text(tr.apply("mccitybuilder.workers").replace("%s", "1"), x + PAD + 1, y + PAD + 11, 0xFF555555);
        y += 12;
        String[] keys = {"upgrade", "repair", "open"};
        int bw = (w - 2 * PAD - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int bx = x + PAD + i * (bw + 2), by = y + PAD + 12;
            a.sprite(i == 0 ? "widget/button_highlighted" : "widget/button", bx, by, bw, 20);
            String l = tr.apply("mccitybuilder." + keys[i]);
            a.text(l, bx + (bw - a.width(l)) / 2, by + 6, INK);
        }
    }

    private static void guide(McAssets a, List<String> ids, boolean all, Hints.Ctx ctx, int sw, int sh, Function<String, String> tr) throws Exception {
        Hints.Laid laid = Hints.layout(ids, all, ctx, tr, a::width);
        int w = Hints.WIDTH, x = sw - 4 - w, h = Hints.PAD + 20 + laid.height() + Hints.PAD, y = sh - 4 - h;
        frame(a, x, y, w, h);
        a.text(tr.apply("mccitybuilder.hint.title"), x + Hints.PAD + 1, y + Hints.PAD + 2, LABEL);
        a.sprite("widget/button", x + w - 22, y + 4, 18, 18);
        a.text("?", x + w - 15, y + 9, INK);
        String al = tr.apply(all ? "mccitybuilder.hint.less" : "mccitybuilder.hint.all");
        int aw = a.width(al) + 10;
        a.sprite("widget/button", x + w - 24 - aw, y + 4, aw, 18);
        a.text(al, x + w - 24 - aw + 5, y + 9, INK);
        int cx = x + Hints.PAD, cy = y + Hints.PAD + 20, capH = Hints.CAP_H;
        for (Hints.Item it : laid.items()) {
            if (it.cap()) {
                int kx = cx + it.x(), ky = cy + it.y(), kw = it.w();
                a.fill(kx, ky, kw, capH, SLOT_DARK);
                a.fill(kx + 1, ky + 1, kw - 2, capH - 2, PANEL);
                a.fill(kx + 1, ky + 1, kw - 3, 1, 0xFFFFFFFF);
                a.fill(kx + 1, ky + 1, 1, capH - 3, 0xFFFFFFFF);
                a.fill(kx + 2, ky + capH - 2, kw - 3, 1, SLOT_BG);
                a.fill(kx + kw - 2, ky + 2, 1, capH - 3, SLOT_BG);
                a.text(it.text(), kx + 3, ky + 2, LABEL);
            } else if (it.title()) {
                a.text(it.text(), cx + it.x() + 1, cy + it.y(), LABEL);
                a.fill(cx, cy + it.y() + 9, it.w(), 1, SLOT_BG);
            } else a.text(it.text(), cx + it.x(), cy + it.y(), 0xFF555555);
        }
    }

    private static void iconButton(McAssets a, String icon, int x, int y) throws Exception {
        a.sprite("widget/button", x, y, 20, 20);
        for (int r = 0; r < 16; r++)
            for (int c = 0; c < 16; c++) {
                int col = com.volcanicmg.mccitybuilder.planner.ActionIcons.color(icon, c, r);
                if (col != 0) a.fill(x + 2 + c, y + 2 + r, 1, 1, col);
            }
    }

    /** Inventory-style frame, as VanillaUi.panel draws it. */
    private static void frame(McAssets a, int x, int y, int w, int h) {
        a.fill(x + 1, y + 1, w - 2, h - 2, PANEL);
        a.fill(x + 2, y, w - 4, 1, 0xFF000000);
        a.fill(x + 2, y + h - 1, w - 4, 1, 0xFF000000);
        a.fill(x, y + 2, 1, h - 4, 0xFF000000);
        a.fill(x + w - 1, y + 2, 1, h - 4, 0xFF000000);
        a.fill(x + 1, y + 1, 1, 1, 0xFF000000);
        a.fill(x + w - 2, y + 1, 1, 1, 0xFF000000);
        a.fill(x + 1, y + h - 2, 1, 1, 0xFF000000);
        a.fill(x + w - 2, y + h - 2, 1, 1, 0xFF000000);
        a.fill(x + 2, y + 1, w - 5, 2, 0xFFFFFFFF);
        a.fill(x + 1, y + 2, 2, h - 5, 0xFFFFFFFF);
        a.fill(x + 3, y + h - 3, w - 5, 2, 0xFF555555);
        a.fill(x + w - 3, y + 3, 2, h - 5, 0xFF555555);
    }

    private static void slot(McAssets a, int x, int y, int w, int h) {
        a.fill(x, y, w, h, SLOT_BG);
        a.fill(x, y, w - 1, 1, SLOT_DARK);
        a.fill(x, y, 1, h - 1, SLOT_DARK);
        a.fill(x + 1, y + h - 1, w - 1, 1, 0xFFFFFFFF);
        a.fill(x + w - 1, y + 1, 1, h - 1, 0xFFFFFFFF);
    }
}
