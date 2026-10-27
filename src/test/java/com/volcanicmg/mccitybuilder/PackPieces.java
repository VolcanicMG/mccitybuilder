package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

/**
 * Real style-pack pieces for the self-check, read straight from the MineColonies jar in {@code libs/} (compile-only,
 * gitignored): a minimal reader for Structurize's gzipped NBT blueprints, enough for sizes, palette and blocks.
 */
final class PackPieces {
    static final String JAR = System.getProperty("mcJar", "libs/minecolonies-1.1.1403-1.21.1.jar");

    private PackPieces() {
    }

    /** @return every road / wall blueprint path in the jar, as "pack/path inside the pack" */
    static List<String> pieces() throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(JAR)) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith("blueprints/minecolonies/") || !n.endsWith(".blueprint")) continue;
                String rest = n.substring("blueprints/minecolonies/".length());
                String inPack = rest.substring(rest.indexOf('/') + 1);
                if (com.volcanicmg.mccitybuilder.planner.PieceSets.tool(inPack) != null) out.add(rest);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** @return the piece as a {@link PieceLayout.Grid} (air, substitution 0, blocks), ground = its primary offset y */
    static PieceLayout.Grid grid(String path) throws IOException {
        try (ZipFile z = new ZipFile(JAR); InputStream in = z.getInputStream(z.getEntry("blueprints/minecolonies/" + path))) {
            DataInputStream d = new DataInputStream(new GZIPInputStream(in));
            d.readByte();
            d.readUTF();
            @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) read(d, 10);
            int sx = ((Number) root.get("size_x")).intValue(), sy = ((Number) root.get("size_y")).intValue(), sz = ((Number) root.get("size_z")).intValue();
            List<?> palette = (List<?>) root.get("palette");
            String[] names = new String[palette.size()];
            for (int i = 0; i < names.length; i++) names[i] = (String) ((Map<?, ?>) palette.get(i)).get("Name");
            int[] packed = (int[]) root.get("blocks"), ids = new int[sx * sy * sz];
            for (int i = 0; i < ids.length; i++) {
                int v = packed[i / 2], s = i % 2 == 0 ? v >>> 16 : v & 0xFFFF;
                String n = names[s];
                ids[i] = n.endsWith(":air") ? PieceLayout.Grid.AIR : n.endsWith(":blocksubstitution") ? 0 : s + 1;
            }
            Map<?, ?> po = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) root.get("optional_data")).get("structurize")).get("primary_offset");
            return new PieceLayout.Grid(sx, sy, sz, ids, ((Number) po.get("y")).intValue());
        }
    }

    private static Object read(DataInputStream d, int t) throws IOException {
        switch (t) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                byte[] b = new byte[d.readInt()];
                d.readFully(b);
                return b;
            }
            case 8: return d.readUTF();
            case 9: {
                int et = d.readByte(), n = d.readInt();
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < n; i++) out.add(read(d, et));
                return out;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                for (int tt; (tt = d.readByte()) != 0; ) {
                    String k = d.readUTF();
                    m.put(k, read(d, tt));
                }
                return m;
            }
            case 11: {
                int[] a = new int[d.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = d.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[d.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = d.readLong();
                return a;
            }
            default: throw new IOException("bad NBT tag " + t);
        }
    }
}
