package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Top-down pictures of real road / wall joints as the planner lays them out ({@link JointCases}): every column coloured by
 * its highest block (lighter = higher), the joint's box yellow, the runs' boxes green, and under it one strip per run
 * showing the contact: the run's end face (blue), the joint layer it butts on (grey), both (green), run cells with
 * nothing in front (red). {@code ./gradlew wallJoints [-Pout=dir] [-Ponly=file name regex]}.
 */
public final class WallJoints {
    private static final int CELL = 14, R = 16;

    private WallJoints() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("contactFloor") != null) PieceLayout.contactFloor = Double.parseDouble(System.getProperty("contactFloor"));
        File dir = new File(args.length > 0 ? args[0] : "build/wall-joints");
        dir.mkdirs();
        int n = 0;
        for (var cs : Boolean.getBoolean("allLevels") ? JointCases.allLevels() : JointCases.all()) {
            File f = new File(dir, (cs.family().replace(' ', '_').replace('/', '_') + "_" + cs.name() + ".png"));
            if (!f.getName().matches(System.getProperty("only", ".*"))) continue;
            ImageIO.write(render(cs), "png", f);
            n++;
        }
        System.out.println("wrote " + n + " pictures to " + dir.getAbsolutePath());
    }

    private static BufferedImage render(JointCases.Case cs) {
        int side = (2 * R + 1) * CELL, strip = 120;
        BufferedImage img = new BufferedImage(side, side + 20 + strip * cs.runs().size(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 60, 35));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        // Top-down: the highest block per column, over the joint and every run (two straights each).
        int[][] top = new int[2 * R + 1][2 * R + 1];
        String[][] name = new String[2 * R + 1][2 * R + 1];
        for (int[] t : top) java.util.Arrays.fill(t, Integer.MIN_VALUE);
        paint(top, name, cs.joint(), cs.jointBox()[0], cs.jointBox()[1]);
        for (var r : cs.runs()) {
            for (int[] b : PieceLayout.boxes(new int[]{0, 0}, r.out(), r.fit().off(), List.of(Math.max(r.straight().sx(), r.straight().sz()),
                    Math.max(r.straight().sx(), r.straight().sz())), Math.min(r.straight().sx(), r.straight().sz()), r.fit().shift()))
                paint(top, name, r.straight(), b[0], b[1]);
        }
        for (int x = 0; x <= 2 * R; x++)
            for (int z = 0; z <= 2 * R; z++)
                if (name[x][z] != null) {
                    g.setColor(shade(colour(name[x][z]), top[x][z]));
                    g.fillRect(x * CELL, z * CELL, CELL, CELL);
                }
        g.setStroke(new BasicStroke(2));
        box(g, cs.jointBox(), Color.YELLOW);
        for (var r : cs.runs()) box(g, r.box(), Color.GREEN);
        g.setColor(Color.WHITE);
        g.drawString(cs.family() + " " + cs.name() + "  " + cs.jointPath() + String.format("  match %.2f", JointCases.report(cs).match()), 4, side + 14);
        // One strip per run: its end face against the joint layer it meets.
        int y0 = side + 20;
        for (var r : cs.runs()) {
            drawContact(g, cs, r, y0, strip);
            y0 += strip;
        }
        g.dispose();
        return img;
    }

    /** Highest block per column of a piece placed with its local 0 at (ox, oz). */
    private static void paint(int[][] top, String[][] name, PieceLayout.Grid p, int ox, int oz) {
        for (int x = 0; x < p.sx(); x++)
            for (int z = 0; z < p.sz(); z++)
                for (int y = p.sy() - 1; y >= 0; y--) {
                    int id = p.id(x, y, z);
                    if (id <= 0) continue;
                    int wx = ox + x + R, wz = oz + z + R, h = y - p.ground();
                    if (wx < 0 || wz < 0 || wx > 2 * R || wz > 2 * R) break;
                    if (h > top[wx][wz]) {
                        top[wx][wz] = h;
                        name[wx][wz] = PackPieces.name(id);
                    }
                    break;
                }
    }

    private static void drawContact(Graphics2D g, JointCases.Case cs, JointCases.Run r, int y0, int h) {
        int d = r.out(), ref = 0, depth = Math.max(0, r.fit().depth());
        int[] jb = cs.jointBox(), sb = r.box();
        Set<Long> j = cs.joint().profile(d, depth, (d % 2 == 0 ? jb[1] : jb[0]) - ref, false);
        Set<Long> s = r.straight().profile((d + 2) % 4, r.straight().inset((d + 2) % 4), (d % 2 == 0 ? sb[1] : sb[0]) - ref, false);
        int c = 5;
        g.setColor(Color.WHITE);
        g.drawString("run " + "ESWN".charAt(d) + String.format(": off %d shift %d match %.2f depth %d", r.fit().off(), r.fit().shift(), r.fit().match(), r.fit().depth()), 4, y0 + 12);
        for (int lat = -12; lat <= 12; lat++)
            for (int y = -2; y < 20; y++) {
                long v = (long) (y + 1000) * 65536 + lat + 30000;
                boolean inJ = j.contains(v), inS = s.contains(v);
                if (!inJ && !inS) continue;
                g.setColor(inJ && inS ? Color.GREEN : inS ? (anyJoint(cs, d, v) ? Color.BLUE : Color.RED) : Color.GRAY);
                g.fillRect(150 + (lat + 12) * c, y0 + h - 8 - (y + 3) * c, c - 1, c - 1);
            }
    }

    /** @return true when a run cell has some joint block in front at any depth */
    private static boolean anyJoint(JointCases.Case cs, int d, long v) {
        int[] jb = cs.jointBox();
        int layers = d % 2 == 0 ? cs.joint().sx() : cs.joint().sz();
        for (int i = 0; i < layers; i++) if (cs.joint().profile(d, i, (d % 2 == 0 ? jb[1] : jb[0]), false).contains(v)) return true;
        return false;
    }

    private static void box(Graphics2D g, int[] b, Color c) {
        g.setColor(c);
        g.drawRect((b[0] + R) * CELL, (b[1] + R) * CELL, (b[2] - b[0] + 1) * CELL, (b[3] - b[1] + 1) * CELL);
    }

    private static Color colour(String n) {
        if (n.contains("stone_brick")) return new Color(125, 125, 125);
        if (n.contains("cobble")) return new Color(105, 105, 105);
        if (n.contains("andesite")) return new Color(140, 140, 136);
        if (n.contains("sandstone")) return new Color(216, 202, 155);
        if (n.contains("plank") || n.contains("log") || n.contains("fence") || n.contains("wood") || n.contains("trapdoor")) return new Color(150, 110, 60);
        if (n.contains("glass")) return new Color(170, 210, 230);
        if (n.contains("lantern") || n.contains("torch")) return new Color(240, 200, 80);
        int h = n.hashCode();
        return new Color(80 + (h & 127), 80 + (h >> 8 & 127), 80 + (h >> 16 & 127));
    }

    private static Color shade(Color c, int h) {
        float k = Math.max(0.5f, Math.min(1.3f, 0.7f + h * 0.04f));
        return new Color(Math.min(255, (int) (c.getRed() * k)), Math.min(255, (int) (c.getGreen() * k)), Math.min(255, (int) (c.getBlue() * k)));
    }
}
