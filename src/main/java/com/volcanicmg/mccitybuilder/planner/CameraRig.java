package com.volcanicmg.mccitybuilder.planner;

/**
 * The planner camera's whole per-frame height loop, pure so it can be tested end to end: terrain baseline sampling
 * (4 Hz), terrain follow, the render-distance ceiling, then the drone step with the local surface as its hard floor.
 * The client supplies a {@link World} over the real level; SelfCheck supplies synthetic terrain.
 */
public final class CameraRig {
    /** Terrain columns; null where not loaded. */
    public interface World {
        /** Surface ignoring leaves (the baseline: trees don't count). */
        Integer ground(int x, int z);

        /** Surface including leaves and everything solid (the hard floor). */
        Integer surface(int x, int z);
    }

    public static final double SAMPLE_PERIOD = 0.25, BASELINE_RADIUS = 28, SAMPLE_STEP = 4;
    public final DroneCamera drone;
    public final TerrainFollow follow = new TerrainFollow();
    public boolean followTerrain = true;
    /** The world's build height (the zoom-out limit's hard top). */
    public double top = 320;
    private double broad = Double.NaN, clock, pendingFollow;
    private boolean clampedOnce;

    public CameraRig(DroneCamera drone) {
        this.drone = drone;
    }

    /** @return the one height reference (the eased terrain baseline under the camera), NaN before the first sample */
    public double baseline() {
        return follow.baseline();
    }

    /**
     * One frame.
     *
     * @param centreGround height of the screen-centre hit (pan speed only), NaN on a miss
     * @param pivot        turn pivot or null; @param turnRate degrees per second
     */
    public void frame(World w, double dt, double right, double ahead, double turnRate, double[] pivot, double speedPerBlock, double centreGround) {
        clock += dt;
        if (Double.isNaN(broad) || clock >= SAMPLE_PERIOD) {
            clock = 0;
            // Stays NaN until a real sample (enough loaded columns): no guessed baseline to correct later.
            broad = CameraMath.sampleQuantile(drone.x, drone.z, BASELINE_RADIUS, SAMPLE_STEP, 0.5, broad, w::ground);
        }
        // While orbiting a pivot the orbit radius depends on the height, so following the slope there would feed
        // back into itself; the change is held and applied (as a glide) once the turn ends.
        pendingFollow += follow.step(broad, dt);
        if (followTerrain && pivot == null) {
            drone.shiftAltitude(pendingFollow);
            pendingFollow = 0;
        } else if (!followTerrain) pendingFollow = 0;
        if (!clampedOnce && !Double.isNaN(baseline())) {
            // First real baseline: bring the entry height inside today's limits once.
            clampedOnce = true;
            drone.scroll(0, baseline(), top);
        }
        // While a turn holds the follow back, the camera's reference is the baseline before that change.
        drone.limitCeiling(baseline() - pendingFollow);
        double under = footprint(w, drone.x, drone.z);
        if (Double.isFinite(under)) drone.groundTop = under;
        // Look-ahead only lifts through the eased clearance; the instant floor is what is right under the camera.
        double floor = Math.max(footprint(w, drone.camX(), drone.camZ()), footprint(w, drone.x, drone.z));
        drone.step(dt, right, ahead, turnRate, pivot, speedPerBlock, centreGround, localSurface(w, dt), floor);
    }

    /** @return terrain-follow change held back during a turn (not yet applied to the altitude) */
    public double pendingFollow() {
        return pendingFollow;
    }

    /** Scroll: altitude only, limits from the baseline (and the local surface for the minimum). @return the change */
    public double scroll(double notches) {
        double ground = Double.isNaN(baseline()) ? drone.y() - 20 : baseline();
        return drone.scroll(notches, ground, top);
    }

    /** @return the highest surface within 2 blocks of (x, z) (the zoom minimum's reference), -inf with none loaded */
    public double footprint(World w, double x, double z) {
        double top = Double.NEGATIVE_INFINITY;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                Integer h = w.surface((int) Math.floor(x) + dx, (int) Math.floor(z) + dz);
                if (h != null) top = Math.max(top, h);
            }
        return top;
    }

    /**
     * @return the highest surface over the camera's footprint (drawn and target position, 2 blocks around), a few blocks
     * ahead along the view, and ahead along the movement by this frame's move plus a margin: the hard floor
     */
    public double localSurface(World w, double dt) {
        double[] f = CameraMath.forward(drone.yaw, 0);
        double vx = drone.velocityX(), vz = drone.velocityZ(), v = Math.hypot(vx, vz), look = v * (dt + 1.5) + 4;
        double mx = v > 1e-6 ? vx / v * look : 0, mz = v > 1e-6 ? vz / v * look : 0;
        double[][] at = {{drone.camX(), drone.camZ()}, {drone.x, drone.z}, {drone.x + mx, drone.z + mz}, {drone.x + mx / 2, drone.z + mz / 2},
                {drone.camX() + f[0] * 4, drone.camZ() + f[2] * 4}, {drone.camX() + f[0] * 8, drone.camZ() + f[2] * 8}};
        double top = Double.NEGATIVE_INFINITY;
        for (double[] p : at)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    Integer h = w.surface((int) Math.floor(p[0]) + dx, (int) Math.floor(p[1]) + dz);
                    if (h != null) top = Math.max(top, h);
                }
        return top;
    }
}
