package com.volcanicmg.mccitybuilder.planner;

/**
 * {@link CameraRig.World} over a level's column data. Clients only receive the WORLD_SURFACE and MOTION_BLOCKING
 * heightmaps; MOTION_BLOCKING_NO_LEAVES stays empty there (it reads minBuildHeight), so "ground" is MOTION_BLOCKING
 * walked down past leaves and other non-solid blocks instead. Pure: the client passes a {@link Source} over the level.
 */
public final class Columns implements CameraRig.World {
    /** What the level offers per column. */
    public interface Source {
        boolean loaded(int x, int z);

        /** MOTION_BLOCKING height: the first free y above the top motion-blocking block or fluid (leaves included). */
        int motionBlocking(int x, int z);

        /** True when the block at (x, y, z) doesn't count as ground: leaves, or something that neither blocks motion nor holds fluid. */
        boolean notGround(int x, int y, int z);

        int minY();
    }

    /** How far down a canopy is walked through before giving up (the tallest trees). */
    public static final int MAX_WALK = 40;
    private final Source src;

    public Columns(Source src) {
        this.src = src;
    }

    @Override
    public Integer ground(int x, int z) {
        if (!src.loaded(x, z)) return null;
        int y = src.motionBlocking(x, z), stop = Math.max(src.minY(), y - MAX_WALK);
        while (y > stop && src.notGround(x, y - 1, z)) y--;
        return y;
    }

    /**
     * @return the ground block at or below y: a picked block walked down past leaves, plants, snow layers and other
     * blocks that don't count as ground (the hut ghost otherwise stands one up on tall grass), at most {@link #MAX_WALK}
     */
    public int settle(int x, int y, int z) {
        int stop = Math.max(src.minY(), y - MAX_WALK);
        while (y > stop && src.notGround(x, y, z)) y--;
        return y;
    }

    @Override
    public Integer surface(int x, int z) {
        return src.loaded(x, z) ? src.motionBlocking(x, z) : null;
    }
}
