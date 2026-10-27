package com.volcanicmg.mccitybuilder;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Read-only access to vanilla GUI assets for the offline previews: widget sprites (with their nine-slice borders) and
 * the default bitmap font, read at run time from the Minecraft 1.21.1 client jar in the Gradle cache. Nothing from
 * the jar is copied into the repo. Drawing is in GUI pixels at an integer {@link #scale}, nearest-neighbour.
 */
final class McAssets {
    private static final String JAR = System.getProperty("mcClientJar",
            System.getProperty("user.home") + "/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar");
    private final ZipFile zip;
    private final Map<String, BufferedImage> images = new HashMap<>();
    private final int[] advance = new int[256];
    private final BufferedImage font;
    final Graphics2D g;
    final int scale;

    McAssets(Graphics2D g, int scale) throws IOException {
        this.g = g;
        this.scale = scale;
        if (!new File(JAR).isFile()) throw new IOException("Minecraft client jar not found: " + JAR + " (set -DmcClientJar)");
        zip = new ZipFile(JAR);
        font = image("textures/font/ascii.png");
        for (int c = 0; c < 256; c++) {
            int cx = c % 16 * 8, cy = c / 16 * 8, right = -1;
            for (int x = 0; x < 8; x++)
                for (int y = 0; y < 8; y++) if ((font.getRGB(cx + x, cy + y) >>> 24) != 0) right = Math.max(right, x);
            advance[c] = c == ' ' ? 4 : right + 2;
        }
    }

    private BufferedImage image(String path) throws IOException {
        BufferedImage b = images.get(path);
        if (b == null) {
            var e = zip.getEntry("assets/minecraft/" + path);
            if (e == null) throw new IOException("missing " + path);
            try (var in = zip.getInputStream(e)) {
                b = ImageIO.read(in);
            }
            images.put(path, b);
        }
        return b;
    }

    /** Width of {@code s} in the default font, like {@code Font.width}. */
    int width(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += c < 256 ? advance[c] : 6;
        return w;
    }

    /** Draws {@code s} in the default font (no shadow) with its top-left at (x, y). */
    void text(String s, int x, int y, int argb) {
        for (char c : s.toCharArray()) {
            if (c < 256 && c != ' ')
                for (int gy = 0; gy < 8; gy++)
                    for (int gx = 0; gx < 8; gx++)
                        if ((font.getRGB(c % 16 * 8 + gx, c / 16 * 8 + gy) >>> 24) != 0) fill(x + gx, y + gy, 1, 1, argb);
            x += c < 256 ? advance[c] : 6;
        }
    }

    /** Draws {@code s} with the vanilla drop shadow (colour / 4, offset 1). */
    void shadowText(String s, int x, int y, int argb) {
        text(s, x + 1, y + 1, (argb & 0xFCFCFC) >> 2 | argb & 0xFF000000);
        text(s, x, y, argb);
    }

    void fill(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        g.setColor(new java.awt.Color(argb, true));
        g.fillRect(x * scale, y * scale, w * scale, h * scale);
    }

    /** Draws a GUI sprite ({@code widget/button}) like {@code GuiGraphics.blitSprite}: nine-slice, edges and centre tiled. */
    void sprite(String name, int x, int y, int w, int h) throws IOException {
        BufferedImage s = image("textures/gui/sprites/" + name + ".png");
        int[] b = border(name);
        int l = b[0], t = b[1], r = b[2], bo = b[3], sw = s.getWidth(), sh = s.getHeight();
        int[][] xs = {{0, 0, l}, {l, l, sw - l - r}, {sw - r, w - r, r}};
        int[][] ys = {{0, 0, t}, {t, t, sh - t - bo}, {sh - bo, h - bo, bo}};
        for (int[] cy : ys)
            for (int[] cx : xs) {
                int dw = cx == xs[1] ? w - l - r : cx[2], dh = cy == ys[1] ? h - t - bo : cy[2];
                for (int yy = 0; yy < dh; yy++)
                    for (int xx = 0; xx < dw; xx++) {
                        int argb = s.getRGB(cx[0] + xx % cx[2], cy[0] + yy % cy[2]);
                        if ((argb >>> 24) != 0) fill(x + cx[1] + xx, y + cy[1] + yy, 1, 1, argb);
                    }
            }
    }

    private int[] border(String name) throws IOException {
        var e = zip.getEntry("assets/minecraft/textures/gui/sprites/" + name + ".png.mcmeta");
        if (e == null) return new int[4];
        String m;
        try (var in = zip.getInputStream(e)) {
            m = new String(in.readAllBytes());
        }
        Matcher one = Pattern.compile("\"border\"\\s*:\\s*(\\d+)").matcher(m);
        if (one.find()) {
            int v = Integer.parseInt(one.group(1));
            return new int[]{v, v, v, v};
        }
        int[] out = new int[4];
        String[] k = {"left", "top", "right", "bottom"};
        for (int i = 0; i < 4; i++) {
            Matcher mm = Pattern.compile("\"" + k[i] + "\"\\s*:\\s*(\\d+)").matcher(m);
            if (mm.find()) out[i] = Integer.parseInt(mm.group(1));
        }
        return out;
    }

    static BufferedImage canvas(int w, int h, int scale) {
        return new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
    }
}
