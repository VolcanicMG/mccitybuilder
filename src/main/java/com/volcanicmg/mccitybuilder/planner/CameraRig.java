package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * The planner camera's whole per-frame height loop, pure so it can be tested end to end: terrain baseline sampling
 * (4 Hz), terrain follow, the render-distance ceiling, then the drone step over continuous clearance and floor heights.
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
    // The drawn camera's movement last frame, blocks/s (pan, orbit or glide), for the look-ahead.
    private double broad = Double.NaN, clock, pendingFollow, moveX, moveZ;
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
     * @param centreGround height of the screen-centre hit (pan speed until the first baseline sample), NaN on a miss
     * @param pivot        turn pivot or null; @param turnRate degrees per second
     */
    public void frame(World w, double dt, double right, double ahead, double turnRate, double[] pivot, double speedPerBlock, double centreGround) {
        clock += dt;
        // The terrain may change between frames, never within one.
        columns.clear();
        columnsOf = w;
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
        // Look-ahead and the target position only lift through the eased clearance; the instant floor is what is right
        // under the drawn camera (a target jump - V, zoom-to-cursor, an orbit step - must not snap it up).
        // Pan speed from the camera's height above the terrain baseline (the reference its altitude keeps), not the
        // screen-centre hit: looking at a hill must not slow the pan.
        double speedGround = Double.isNaN(baseline()) ? centreGround : baseline() - pendingFollow, px = drone.camX(), pz = drone.camZ(), py = drone.y();
        // Half a block extra, so the eased climb actually reaches what the drawn camera needs below.
        drone.step(dt, right, ahead, turnRate, pivot, speedPerBlock, speedGround, Math.max(localSurface(w, dt, moveX, moveZ), orbitAhead(w, turnRate, pivot)) + 0.5,
                floor(w, drone.camX(), drone.camZ()) - DroneCamera.CLEARANCE);
        // Where the drawn camera went this frame: the floor there too. It is continuous, so near terrain that is a climb
        // within the speed cap; only something too tall to climb this frame (a wall) holds the drawn x / z while it rises.
        double next = floor(w, drone.camX(), drone.camZ());
        if (next > py + drone.vStep() + 1e-9) drone.holdDrawn(px, pz);
        else drone.raiseTo(next);
        moveX = dt > 0 ? (drone.camX() - px) / dt : 0;
        moveZ = dt > 0 ? (drone.camZ() - pz) / dt : 0;
    }

    // Surface heights read this frame (the floor and clearance windows overlap heavily); MIN_VALUE = not loaded.
    private final java.util.HashMap<Long, Integer> columns = new java.util.HashMap<>();
    private World columnsOf;

    /** @return {@code w}'s surface at a column, read once per frame, or MIN_VALUE where it isn't loaded */
    private int surface(World w, int x, int z) {
        if (w != columnsOf) {
            columns.clear();
            columnsOf = w;
        }
        long k = (long) x << 32 ^ (z & 0xFFFFFFFFL);
        Integer h = columns.get(k);
        if (h == null) {
            Integer s = w.surface(x, z);
            h = s == null ? Integer.MIN_VALUE : s;
            columns.put(k, h);
        }
        return h;
    }

    /**
     * The hard floor: {@link #FLOOR_GAP} above the column under the camera, falling off by {@link #FLOOR_FALL} per block
     * of distance for the columns around (never inside one); the full {@link DroneCamera#CLEARANCE} comes from the
     * eased clearance, whose obstacle falls off by {@link #OBSTACLE_FALL} per block.
     */
    public static final double FLOOR_GAP = 2.1, FLOOR_FALL = 2, OBSTACLE_FALL = 1;

    /**
     * @return the lowest height the drawn camera may be at over (x, z), -inf with none loaded; continuous in x / z,
     * so moving or orbiting never makes it jump by a block
     */
    public double floor(World w, double x, double z) {
        return near(w, x, z, 2, 0, FLOOR_FALL) + FLOOR_GAP;
    }

    /**
     * @return the highest surface around (x, z) within {@code r} columns, each lowered by {@code fall} per block of
     * distance to it beyond {@code full} blocks
     */
    private double near(World w, double x, double z, int r, double full, double fall) {
        double top = Double.NEGATIVE_INFINITY;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int h = surface(w, cx + dx, cz + dz);
                if (h == Integer.MIN_VALUE) continue;
                // Distance from the point to the column's square (0 inside it).
                double ex = Math.max(0, Math.max(cx + dx - x, x - (cx + dx + 1))), ez = Math.max(0, Math.max(cz + dz - z, z - (cz + dz + 1)));
                top = Math.max(top, h - fall * Math.max(0, Math.hypot(ex, ez) - full));
            }
        return top;
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

    /** {@link #localSurface(World, double, double, double)} with the pan velocity as the movement. */
    public double localSurface(World w, double dt) {
        return localSurface(w, dt, drone.velocityX(), drone.velocityZ());
    }

    /**
     * @return the highest surface along the next 1.5 s of an orbit around {@code pivot} (every 4 blocks of arc, at most
     * 16 points), falling off like the clearance obstacle; -inf when not orbiting. A straight look-ahead along the
     * movement leaves the circle, so a tall building beside the orbit came too late and the floor had to lift the camera.
     */
    public double orbitAhead(World w, double turnRate, double[] pivot) {
        if (pivot == null || turnRate == 0) return Double.NEGATIVE_INFINITY;
        double d = Math.hypot(drone.x - pivot[0], drone.z - pivot[2]), sweep = Math.toRadians(turnRate) * 1.5;
        int n = (int) Math.min(16, Math.ceil(Math.abs(sweep) * d / 4));
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 1; i <= n; i++) {
            // Where turn() puts the camera for that yaw: pivot - forward(yaw) * d.
            double yaw = Math.toRadians(drone.yaw) + sweep * i / n;
            top = Math.max(top, near(w, pivot[0] + Math.sin(yaw) * d, pivot[2] - Math.cos(yaw) * d, 4, 2, OBSTACLE_FALL));
        }
        return top;
    }

    /**
     * @return the highest surface over the camera's footprint (drawn and target position, 2 blocks around, then
     * falling off by {@link #OBSTACLE_FALL} per block out to 4 so a column coming near raises it gradually) and ahead along the
     * movement {@code vx, vz} (blocks/s) by this frame's move plus 1.5 s and 4 blocks; nothing ahead when not moving,
     * so turning in place never reacts to what the view sweeps across
     */
    public double localSurface(World w, double dt, double vx, double vz) {
        double v = Math.hypot(vx, vz), look = v > 1e-6 ? v * (dt + 1.5) + 4 : 0;
        List<double[]> at = new ArrayList<>(List.of(new double[]{drone.camX(), drone.camZ()}, new double[]{drone.x, drone.z}));
        // ponytail: samples every 4 blocks up to 64 ahead; a faster pan sees less ahead (it is high up then anyway).
        for (double s = 4; v > 1e-6 && s <= Math.min(look, 64); s += 4) at.add(new double[]{drone.x + vx / v * s, drone.z + vz / v * s});
        // The way the drawn camera still has to glide to the target (it waits over anything it can't clear yet).
        double gx = drone.x - drone.camX(), gz = drone.z - drone.camZ(), gd = Math.hypot(gx, gz);
        for (double s = 4; s < Math.min(gd, 64); s += 4) at.add(new double[]{drone.camX() + gx / gd * s, drone.camZ() + gz / gd * s});
        double top = Double.NEGATIVE_INFINITY;
        for (double[] p : at) top = Math.max(top, near(w, p[0], p[1], 4, 2, OBSTACLE_FALL));
        return top;
    }
}
