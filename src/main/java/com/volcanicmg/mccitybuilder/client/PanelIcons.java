package com.volcanicmg.mccitybuilder.client;

import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The line panel's pixel-art icons (strings, one character per colour of {@link #color}), drawn with {@code g.fill} at
 * whole GUI units per pixel: road and wall piece icons are generated (top-down bands), the rest is hand-drawn.
 */
final class PanelIcons {
    private static final Map<String, String[]> art = new HashMap<>();
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static {
        art.put("pieces", new String[]{"................", ".kkkkkk..kkkkkk.", ".kddddk..kddddk.", ".kdyydk..kddddk.", ".kdyydk..kdyyyk.", ".kddddk..kdyddk.", ".kkkkkk..kkkkkk.", "................", "................", ".kkkkkk..kkkkkk.", ".kdyddk..kdyydk.", ".kyyyyk..kyyyyk.", ".kdyddk..kdyydk.", ".kddddk..kddddk.", ".kkkkkk..kkkkkk.", "................"});
        art.put("gate", new String[]{"................", ".kkkk......kkkk.", ".kSSk......kSSk.", ".kssk......kssk.", ".ksskkkkkkkkssk.", ".ksskBBBBBBkssk.", ".ksskBbBBbBkssk.", ".ksskBbBBbBkssk.", ".ksskBbBBbBkssk.", ".ksskBbBBbBkssk.", ".ksskBbBBbBkssk.", ".ksskBbBBbBkssk.", ".ksskBbBBbBkssk.", ".kkkkkkkkkkkkkk.", "................", "................"});
        art.put("flip", new String[]{"................", "................", ".........k......", ".........kk.....", "..kkkkkkkkWk....", "..kWWWWWWWWWk...", "..kkkkkkkkWk....", ".........kk.....", "......k.........", ".....kk.........", "....kWkkkkkkkk..", "...kWWWWWWWWWk..", "....kWkkkkkkkk..", ".....kk.........", "......k.........", "................"});
        art.put("segment", new String[]{"................", "................", "................", "................", ".kk..........kk.", ".kGk........kGk.", ".kGkkkkkkkkkkGk.", ".kGWWWWWWWWWWGk.", ".kGkkkkkkkkkkGk.", ".kGk........kGk.", ".kk..........kk.", "................", "................", "................", "................", "................"});
        art.put("fill", new String[]{"......kkkk......", "......kWWk......", "......kWWk......", "....kkkWWkkk....", "....kWWWWWWk....", ".....kWWWWk.....", "......kWWk......", ".......kk.......", "................", ".kkkkko..okkkkk.", ".kaaak....kaaak.", ".kdddko..okdddk.", ".kdddk....kdddk.", ".kdddko..okdddk.", ".kkkkkooookkkkk.", "................"});
        art.put("pencil", new String[]{"................", "...........kk...", "..........kSSk..", ".........kyykk..", "........kyyyk...", ".......kyyyk....", "......kyyyk.....", ".....kyyyk......", "....kyyyk.......", "...kBBBk........", "...kBBk.........", "...kkk..........", "................", "................", "................", "................"});
        art.put("undo", new String[]{"................", "................", "................", "....k...........", "...kWkkkkkk.....", "..kWWWWWWWWk....", "...kWkkkkkWk....", "....k.....kWk...", "..........kWk...", "..........kWk...", ".....kkkkkWk....", ".....kWWWWk.....", ".....kkkkk......", "................", "................", "................"});
        art.put("okb", new String[]{"..kkkkk..", ".kGGGGGk.", "kGGGGGGWk", "kGGGGGWWk", "kGWGGWWGk", "kGWWWWGGk", "kGGWWGGGk", ".kGGGGGk.", "..kkkkk.."});
        art.put("warnb", new String[]{"..kkkkk..", ".k" + "ooooo" + "k.", "k" + "ooo" + "k" + "ooo" + "k", "k" + "ooo" + "k" + "ooo" + "k", "k" + "ooo" + "k" + "ooo" + "k", "k" + "ooooooo" + "k", "k" + "ooo" + "k" + "ooo" + "k", ".k" + "ooooo" + "k.", "..kkkkk.."});
        art.put("joint", new String[]{"................", "......kkkk......", "......kddk......", "......kddk......", "......kddk......", "..kkkkkookkkkk..", "..kddddooddddk..", "..kddddooddddk..", "..kkkkkookkkkk..", "......kddk......", "......kddk......", "......kddk......", "......kkkk......", "................", "................", "................"});
        art.put("refresh", new String[]{"................", "................", "......kkkkk.....", "....kkWWWWWkk...", "...kWWkkkkkWWk..", "..kWWk.....kWWWk", "..kWk.......kWk.", "..kWk......kWWWk", "..kWk.......kWk.", "..kWk........k..", "...kWWk.....kWk.", "....kkWWkkkkWk..", ".....kkkWWWWk...", ".......kkkkk....", "................", "................"});
        art.put("hammer", new String[]{"............", ".kkkkkkkk...", ".kwwwwwwwk..", ".kwwwwwwwwk.", ".kkkkkkkkkk.", "....kBBk....", "....kBBk....", "....kBBk....", "....kBBk....", "....kBBk....", "....kkkk....", "............"});
        // Route: a start (green) and an end (amber) joined by a road with turns.
        art.put("route", new String[]{"................", ".kkkk...........", ".kGGk...........", ".kGGk...........", ".kkkk...........", "..kdk...........",
                "..kyk...........", "..kdkkkkkkkk....", "..kddyddyddk....", "..kkkkkkkkdk....", ".........kdk....", ".........kyk....", "........koook...",
                "........koook...", "........kkkkk...", "................"});
        art.put("r_straight", road(true, false, true, false, false));
        art.put("r_corner", road(false, true, true, false, false));
        art.put("r_tee", road(false, true, true, true, false));
        art.put("r_cross", road(true, true, true, true, false));
        art.put("r_end", road(false, false, true, false, true));
        art.put("tool_road", road(false, true, false, true, false));
        art.put("w_straight", wall(true, false, true, false, false));
        art.put("w_corner", wall(false, true, true, false, false));
        art.put("w_inner", wall(false, true, true, false, true));
        art.put("w_tee", wall(false, true, true, true, false));
        art.put("w_cross", wall(true, true, true, true, false));
        art.put("w_end", wall(false, false, true, false, false));
        art.put("w_tower", tower());
        art.put("tool_wall", wall(false, true, false, true, false));
    }

    private PanelIcons() {
    }

    /** @return the colour character as ARGB, 0 for transparent */
    private static int color(char c) {
        return switch (c) {
            case 'k' -> 0xFF1C1C1C;
            case 'd' -> 0xFF3A3A3A;
            case 'a' -> 0xFF565656;
            case 'y' -> 0xFFE3C34A;
            case 'S' -> 0xFFC4C4C4;
            case 's' -> 0xFF8E8E8E;
            case 'B' -> 0xFF9A6B3C;
            case 'b' -> 0xFF6B4423;
            case 'W' -> 0xFFFFFFFF;
            case 'G' -> 0xFF5FA055;
            case 'o' -> 0xFFD9A030;
            case 'c' -> 0xFFA0522D;
            case 'w' -> 0xFFDCDCDC;
            default -> 0;
        };
    }

    /** @return the icon's width in pixels, 0 for an unknown name */
    static int size(String name) {
        String[] a = art.get(name);
        return a == null ? 0 : a[0].length();
    }

    /**
     * Draws a named icon with its top-left at (x, y), {@code scale} GUI units per pixel.
     *
     * @param dim draw it faded (a part this set doesn't have)
     */
    static void draw(GuiGraphics g, String name, int x, int y, int scale, boolean dim) {
        String[] a = art.get(name);
        if (a == null) return;
        for (int j = 0; j < a.length; j++) {
            String row = a[j];
            for (int i = 0; i < row.length(); ) {
                char c = row.charAt(i);
                int k = i;
                while (k < row.length() && row.charAt(k) == c) k++;
                int col = color(c);
                if (col != 0) g.fill(x + i * scale, y + j * scale, x + k * scale, y + (j + 1) * scale, dim ? col & 0xFFFFFF | 0x66000000 : col);
                i = k;
            }
        }
    }

    /** @return the piece-type icon for a role in a tool ("road" / "wall") */
    static String forRole(String tool, PieceSets.Role r) {
        boolean wall = "wall".equals(tool);
        return switch (r) {
            case CORNER -> wall ? "w_corner" : "r_corner";
            case INNER -> "w_inner";
            case TEE -> wall ? "w_tee" : "r_tee";
            case CROSS -> wall ? "w_cross" : "r_cross";
            case END -> wall ? "w_end" : "r_end";
            case GATE -> "gate";
            case TOWER -> "w_tower";
            default -> wall ? "w_straight" : "r_straight";
        };
    }

    private static boolean in(boolean n, boolean e, boolean s, boolean w, int x, int y) {
        boolean c = x >= 4 && x <= 11 && y >= 4 && y <= 11;
        return c || n && x >= 4 && x <= 11 && y < 4 || s && x >= 4 && x <= 11 && y > 11 || w && y >= 4 && y <= 11 && x < 4 || e && y >= 4 && y <= 11 && x > 11;
    }

    private static boolean edge(boolean[][] m, int x, int y) {
        for (int[] o : DIRS) {
            int X = x + o[0], Y = y + o[1];
            if (X >= 0 && X < 16 && Y >= 0 && Y < 16 && !m[Y][X]) return true;
        }
        return false;
    }

    /** Recolours every {@code from} pixel that touches a {@code next} pixel to {@code to}. */
    private static void ring(char[][] g, char from, char next, char to) {
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                if (g[y][x] != from) continue;
                for (int[] o : DIRS) {
                    int X = x + o[0], Y = y + o[1];
                    if (X >= 0 && X < 16 && Y >= 0 && Y < 16 && g[Y][X] == next) {
                        g[y][x] = to;
                        break;
                    }
                }
            }
    }

    /** Top-down band icon: {@code edge} outline pixels, {@code body} inside, from the arms n / e / s / w. */
    private static char[][] band(boolean n, boolean e, boolean s, boolean w, boolean fat, char edge, char body) {
        boolean[][] m = new boolean[16][16];
        char[][] g = new char[16][16];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) m[y][x] = in(n, e, s, w, x, y) || fat && x > 11 && x < 14 && y > 11 && y < 14;
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) g[y][x] = !m[y][x] ? '.' : edge(m, x, y) ? edge : body;
        return g;
    }

    /** Top-down road piece: 8-wide band, light edge, sidewalk beside the road, centre dashes along each arm. */
    private static String[] road(boolean n, boolean e, boolean s, boolean w, boolean end) {
        char[][] g = band(n, e, s, w, false, 'w', 'd');
        ring(g, 'w', 'd', 'c');
        if (n) dash(g, 7, 1);
        if (s) dash(g, 7, 13);
        if (w) dash(g, 1, 7);
        if (e) dash(g, 13, 7);
        if (end) dash(g, 7, 9);
        return rows(g);
    }

    /** A 2x2 block of dashes at (x, y) where the road is plain. */
    private static void dash(char[][] g, int x, int y) {
        for (int j = y; j < y + 2; j++) for (int i = x; i < x + 2; i++) if (g[j][i] == 'd') g[j][i] = 'y';
    }

    /** Top-down wall piece in stone: dark outline, mid ring, light top; {@code fat} fills the inside of the bend. */
    private static String[] wall(boolean n, boolean e, boolean s, boolean w, boolean fat) {
        char[][] g = band(n, e, s, w, fat, 'k', 'w');
        ring(g, 'w', 'k', 'a');
        return rows(g);
    }

    /** A tower: a square block, a mid ring inside the outline and a darker well in the middle. */
    private static String[] tower() {
        char[][] g = new char[16][16];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                boolean b = x >= 3 && x <= 12 && y >= 3 && y <= 12, out = x == 3 || x == 12 || y == 3 || y == 12;
                boolean mid = x == 4 || x == 11 || y == 4 || y == 11;
                g[y][x] = !b ? '.' : out ? 'k' : mid ? 'a' : x >= 7 && x <= 8 && y >= 7 && y <= 8 ? 'd' : 'w';
            }
        return rows(g);
    }

    private static String[] rows(char[][] g) {
        String[] out = new String[g.length];
        for (int j = 0; j < g.length; j++) out[j] = new String(g[j]);
        return out;
    }
}
