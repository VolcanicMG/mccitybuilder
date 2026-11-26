package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

/**
 * Piece-set classification dumps: the bundled packs' (SelfCheck's golden, {@code bundled-piecesets.txt}) and what
 * detection makes of any style pack jar ({@code ./gradlew pieceScan -Pjars=a.jar,b.jar}; {@code -Pjars=golden,out.txt}
 * rewrites the golden). {@code -Pshow=regex} also prints the top-down body of matching pieces.
 */
final class PieceScan {
    private PieceScan() {
    }

    /** @return one line per classified piece of the bundled packs: pack, family, role, group, levels, path, axis */
    static List<String> bundled() throws Exception {
        List<String> out = new ArrayList<>();
        for (var en : new TreeMap<>(JointCases.packs()).entrySet()) {
            Map<String, Integer> shapes = new HashMap<>();
            for (var p : en.getValue()) {
                PieceLayout.Grid g = PackPieces.grid(en.getKey() + "/" + p.path());
                shapes.put(p.path(), PieceSets.openSides(PieceSets.body(g, "road".equals(PieceSets.tool(p.path()))), g.sx(), g.sz()));
            }
            out.addAll(lines(en.getKey(), PieceSets.classify(en.getValue(), shapes)));
        }
        return out;
    }

    static List<String> lines(String pack, Map<String, PieceSets.Family> fams) {
        List<String> out = new ArrayList<>();
        for (var f : fams.entrySet())
            for (var r : f.getValue().roles().entrySet())
                for (var g : r.getValue())
                    for (var p : g.pieces())
                        out.add(pack + "\t" + f.getKey() + "\t" + r.getKey() + "\t" + g.base() + "\t" + g.levels() + "\t" + p.path() + "\t" + p.axis());
        java.util.Collections.sort(out);
        return out;
    }

    /** A jar's road / wall pieces: pack -> path -> grid (ids by block name, unique per scan). */
    static Map<String, Map<String, PieceLayout.Grid>> read(String jar) throws Exception {
        Map<String, Integer> ids = new HashMap<>();
        Map<String, Map<String, PieceLayout.Grid>> out = new TreeMap<>();
        try (ZipFile z = new ZipFile(jar)) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("blueprints/") || !n.endsWith(".blueprint")) continue;
                String[] s = n.split("/", 4);
                if (s.length < 4 || PieceSets.tool(s[3]) == null) continue;
                try (InputStream in = z.getInputStream(e)) {
                    DataInputStream d = new DataInputStream(new GZIPInputStream(in));
                    d.readByte();
                    d.readUTF();
                    @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) PackPieces.read(d, 10);
                    int sx = ((Number) root.get("size_x")).intValue(), sy = ((Number) root.get("size_y")).intValue(), sz = ((Number) root.get("size_z")).intValue();
                    List<?> palette = (List<?>) root.get("palette");
                    int[] packed = (int[]) root.get("blocks"), g = new int[sx * sy * sz];
                    for (int i = 0; i < g.length; i++) {
                        String bn = (String) ((Map<?, ?>) palette.get(i % 2 == 0 ? packed[i / 2] >>> 16 : packed[i / 2] & 0xFFFF)).get("Name");
                        g[i] = bn.endsWith(":air") ? PieceLayout.Grid.AIR : bn.endsWith(":blocksubstitution") ? 0 : ids.computeIfAbsent(bn, k -> ids.size() + 1);
                    }
                    Map<?, ?> po = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) root.get("optional_data")).get("structurize")).get("primary_offset");
                    Integer tag = PackPieces.groundTag(root);
                    // A hut blueprint (anchor on a MineColonies hut block) is no decoration in the game, so no piece.
                    int px = ((Number) po.get("x")).intValue(), pz = ((Number) po.get("z")).intValue(), py = ((Number) po.get("y")).intValue();
                    int pi = (py * sz + pz) * sx + px;
                    String anchor = (String) ((Map<?, ?>) palette.get(pi % 2 == 0 ? packed[pi / 2] >>> 16 : packed[pi / 2] & 0xFFFF)).get("Name");
                    if (anchor.startsWith("minecolonies:blockhut")) {
                        System.out.println("  hut, not a piece: " + n);
                        continue;
                    }
                    out.computeIfAbsent(s[1] + "/" + s[2], k -> new TreeMap<>()).put(s[3],
                            new PieceLayout.Grid(sx, sy, sz, g, ((Number) po.get("y")).intValue() - (tag == null ? 1 : tag)));
                } catch (Exception ex) {
                    System.out.println("  can't read " + n + ": " + ex);
                }
            }
        }
        return out;
    }

    /** Prints folder → family → role per piece of each pack in the jar (name role, shape, final). */
    static void scan(String jar) throws Exception {
        String show = System.getProperty("show", "");
        for (var pk : read(jar).entrySet()) {
            System.out.println("== " + pk.getKey());
            List<PieceSets.Piece> ps = new ArrayList<>();
            Map<String, Integer> shapes = new HashMap<>();
            for (var en : pk.getValue().entrySet()) {
                PieceLayout.Grid g = en.getValue();
                String[] sn = PieceSets.split(en.getKey().substring(en.getKey().lastIndexOf('/') + 1));
                ps.add(new PieceSets.Piece(en.getKey(), sn[0], Integer.parseInt(sn[1]), g.sx(), g.sy(), g.sz()));
                boolean road = "road".equals(PieceSets.tool(en.getKey()));
                boolean[] body = PieceSets.body(g, road);
                shapes.put(en.getKey(), PieceSets.openSides(body, g.sx(), g.sz()));
                if (!show.isEmpty() && en.getKey().matches(show)) {
                    System.out.println("  " + en.getKey() + " " + g.sx() + "x" + g.sz() + " sides " + PieceSets.openSides(body, g.sx(), g.sz()));
                    for (int z = 0; z < g.sz(); z++) {
                        StringBuilder sb = new StringBuilder("    ");
                        for (int x = 0; x < g.sx(); x++) sb.append(body[z * g.sx() + x] ? '#' : '.');
                        sb.append("   ");
                        for (int x = 0; x < g.sx(); x++) {
                            int t = -99;
                            for (int y = 0; y < g.sy(); y++) if (g.id(x, y, z) > 0) t = y - g.ground();
                            sb.append(t == -99 ? ' ' : t < 0 ? '-' : t > 9 ? '+' : (char) ('0' + t));
                        }
                        System.out.println(sb);
                    }
                }
            }
            var fams = PieceSets.classify(ps, shapes);
            var tools = PieceSets.toolPieces(fams, PieceSets.Family::of);
            System.out.println("  pieces " + ps.size() + ": the road / wall tools place " + tools.size() + ", the Other tab gets " + (ps.size() - tools.size()));
            Map<String, String> famOf = new HashMap<>();
            for (var f : fams.entrySet())
                for (var r : f.getValue().roles().entrySet())
                    for (var g : r.getValue()) for (var p : g.pieces()) famOf.put(p.path(), f.getKey() + "\t" + r.getKey());
            for (var p : ps) {
                String file = p.path().substring(p.path().lastIndexOf('/') + 1).replace(".blueprint", "");
                Integer sh = shapes.get(p.path());
                System.out.println("  " + p.path().substring(0, Math.max(0, p.path().lastIndexOf('/'))) + "\t" + file + "\t" + p.sx() + "x" + p.sz() + "x" + p.sy() + "\tname "
                        + PieceSets.role(p.base()) + "\tshape " + (sh == null ? "-" : PieceSets.sidesName(sh)) + "\t" + famOf.getOrDefault(p.path(), "(none)"));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("golden")) {
            java.nio.file.Files.write(java.nio.file.Path.of(args[1]), bundled());
            return;
        }
        for (String jar : args) scan(jar);
    }
}
