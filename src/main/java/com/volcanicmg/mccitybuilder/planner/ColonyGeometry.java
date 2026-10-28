package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Colony claims on the chunk grid: which chunks a blueprint footprint touches (MineColonies' server rule is that every
 * one of them is owned by the colony), the border edges to outline, and the bounds the camera may pan in. Chunks are
 * {@code long}s packed like {@code ChunkPos.asLong} (x in the low 32 bits, z in the high). Pure.
 */
public final class ColonyGeometry {
    private ColonyGeometry() {
    }

    public static long chunk(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    public static int chunkX(long c) {
        return (int) c;
    }

    public static int chunkZ(long c) {
        return (int) (c >>> 32);
    }

    /** @return every chunk overlapping the block box [x0..x1] x [z0..z1] (inclusive, any order) */
    public static List<Long> footprintChunks(int x0, int z0, int x1, int z1) {
        List<Long> out = new ArrayList<>();
        for (int cx = Math.min(x0, x1) >> 4; cx <= Math.max(x0, x1) >> 4; cx++)
            for (int cz = Math.min(z0, z1) >> 4; cz <= Math.max(z0, z1) >> 4; cz++) out.add(chunk(cx, cz));
        return out;
    }

    /** @return true when every chunk of the footprint is claimed */
    public static boolean footprintClaimed(int x0, int z0, int x1, int z1, Set<Long> claimed) {
        for (long c : footprintChunks(x0, z0, x1, z1)) if (!claimed.contains(c)) return false;
        return true;
    }

    /** @return border edges as {x1, z1, x2, z2} in block coordinates: claimed chunk sides facing unclaimed chunks */
    public static List<int[]> borderEdges(Set<Long> claimed) {
        List<int[]> out = new ArrayList<>();
        for (long c : claimed) {
            int cx = chunkX(c), cz = chunkZ(c), x = cx << 4, z = cz << 4;
            if (!claimed.contains(chunk(cx, cz - 1))) out.add(new int[]{x, z, x + 16, z});
            if (!claimed.contains(chunk(cx, cz + 1))) out.add(new int[]{x, z + 16, x + 16, z + 16});
            if (!claimed.contains(chunk(cx - 1, cz))) out.add(new int[]{x, z, x, z + 16});
            if (!claimed.contains(chunk(cx + 1, cz))) out.add(new int[]{x + 16, z, x + 16, z + 16});
        }
        return out;
    }

    /** @return {minX, minZ, maxX, maxZ} of the claimed chunks grown by {@code margin} blocks, or null with none */
    public static double[] bounds(Set<Long> claimed, double margin) {
        if (claimed.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long c : claimed) {
            x0 = Math.min(x0, chunkX(c));
            z0 = Math.min(z0, chunkZ(c));
            x1 = Math.max(x1, chunkX(c));
            z1 = Math.max(z1, chunkZ(c));
        }
        return new double[]{(x0 << 4) - margin, (z0 << 4) - margin, ((x1 + 1) << 4) + margin, ((z1 + 1) << 4) + margin};
    }

    /** @return the block box {x0, y0, z0, x1, y1, z1} of blueprint offsets {@code r} (from its anchor) placed at an anchor */
    public static int[] placed(int[] r, int x, int y, int z) {
        return new int[]{x + r[0], y + r[1], z + r[2], x + r[3], y + r[4], z + r[5]};
    }

    /**
     * @param boxes inclusive block boxes {minX, minY, minZ, maxX, maxY, maxZ}
     * @return the index of the smallest box containing the block (x, y, z), or -1
     */
    public static int smallestContaining(List<int[]> boxes, int x, int y, int z) {
        int best = -1;
        long bestVol = Long.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (x < b[0] || y < b[1] || z < b[2] || x > b[3] || y > b[4] || z > b[5]) continue;
            long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            if (vol < bestVol) {
                bestVol = vol;
                best = i;
            }
        }
        return best;
    }

    /**
     * Whether the planner may open where the player stands.
     *
     * @param owner       the colony id owning the player's chunk: 0 = unclaimed, -1 = no claim data there
     * @param claimsKnown the client has claim data around the player (so a missing entry means unclaimed)
     * @param viewKnown   the owning colony's view has synced to this client
     * @param member      the player is a member of it
     * @param access      the player may access its huts
     * @return null to open, else the reason: "loading", "outside", "not_member" or "no_permission"
     */
    public static String openCheck(int owner, boolean claimsKnown, boolean viewKnown, boolean member, boolean access) {
        if (owner < 0 && !claimsKnown) return "loading";
        if (owner <= 0) return "outside";
        if (!viewKnown) return "loading";
        if (!member) return "not_member";
        return access ? null : "no_permission";
    }

    /**
     * @param owner       as for {@link #openCheck}
     * @param claimsKnown the client has claim data right around the player
     * @return true when the open planner should close: the player now stands on another colony's or unclaimed land
     */
    public static boolean leftColony(int owner, int colony, boolean claimsKnown) {
        if (owner < 0) return claimsKnown;
        return owner != colony;
    }
}
