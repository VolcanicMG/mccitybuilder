package com.volcanicmg.mccitybuilder.planner;

/**
 * The planner's RTS "drone" camera: a position, yaw and pitch, where the user owns the altitude. Scrolling changes
 * only the altitude (eased briefly); WASD moves horizontally relative to yaw at a speed proportional to the height
 * above ground, with a tiny acceleration and an immediate stop; terrain never changes the user's altitude, it only
 * pushes the camera up by an eased clearance while something is right below it. Pure; the client feeds it the
 * ground heights and the input each frame.
 */
public final class DroneCamera {
    /** Altitude step per scroll notch as a share of the height above ground; ease times in seconds. */
    public static final double ZOOM_STEP = 0.11, ZOOM_TAU = 0.04, CLEAR_TAU = 0.2, ACCEL_TAU = 0.03;
    /** Highest altitude above ground, the clearance kept above nearby terrain, the speed floor height. */
    public static final double MAX_ABOVE = 160, CLEARANCE = 3, SPEED_FLOOR = 4;
    /** Vertical speed cap, blocks/s: at least MIN_VSPEED, else VSPEED_PER_BLOCK per block above the local terrain. */
    public static final double MIN_VSPEED = 16, VSPEED_PER_BLOCK = 3;
    /** ...and at least this many times the remaining gap per second (a glide that catches up within ~0.5 s). */
    public static final double VSPEED_GAP = 8;
    /** Horizontal speed cap for the rendered position, blocks/s (same idea; a turn or a clamp becomes a glide). */
    public static final double MIN_HSPEED = 32, HSPEED_PER_BLOCK = 6;
    /** A turn pivot farther than this many times the height above it is not trusted: turn in place instead. */
    public static final double PIVOT_REACH = 4;
    public static final float DEFAULT_PITCH = 60, MIN_PITCH = 30, MAX_PITCH = 89;

    public double x, z;
    /** The user's altitude (eased toward {@link #altitudeTarget}); terrain never moves it. */
    public double altitude, altitudeTarget;
    /** Extra height while terrain under the camera would be too close; eased, 0 on open ground. */
    public double clearance;
    public float yaw, pitch = DEFAULT_PITCH;
    /** Lowest altitude above the ground under the screen centre that scrolling reaches. */
    public double minAbove = 10;
    /** Highest altitude above that ground: {@link #MAX_ABOVE}, or less when the render distance is shorter. */
    public double maxAbove = MAX_ABOVE;
    private double vx, vz;
    // The camera height actually used: altitude + clearance, followed at a capped vertical speed (no jumps).
    private double yOut, xOut, zOut;
    /** The surface right under the camera's footprint (set by the rig): the zoom minimum sits above it. */
    public double groundTop = Double.NEGATIVE_INFINITY;
    // The last finite obstacle height, used when a frame has no loaded columns.
    private double lastObstacle = Double.NaN, lastFloor = Double.NaN, vStep;
    /** Where x / z may go: a circle (the loaded area) and a box (the colony + margin, or null); the circle wins. */
    public double circleX, circleZ, circleR = Double.POSITIVE_INFINITY;
    public double[] box;

    public DroneCamera(double x, double altitude, double z, float yaw) {
        this.x = x;
        this.z = z;
        this.altitude = this.altitudeTarget = altitude;
        this.yOut = altitude;
        this.xOut = x;
        this.zOut = z;
        this.yaw = yaw;
    }

    /** @return the largest vertical move the speed cap allowed last frame (the hard floor may add to it) */
    public double vStep() {
        return vStep;
    }

    /** @return the horizontal pan velocity, blocks/s (for the floor's look-ahead) */
    public double velocityX() {
        return vx;
    }

    public double velocityZ() {
        return vz;
    }

    /** @return where the camera is drawn: x / z followed at a capped horizontal speed */
    public double camX() {
        return xOut;
    }

    public double camZ() {
        return zOut;
    }

    /** @return the camera's actual height: the user's altitude plus any clearance, reached at a capped vertical speed */
    public double y() {
        return yOut;
    }

    /**
     * Scroll: altitude only. Each notch moves about {@link #ZOOM_STEP} of the height above ground (at least a block).
     *
     * @param notches positive = closer (down)
     * @param ground  smoothed ground height under the screen centre
     * @param top     the world's build height
     * @return the altitude change requested (for zoom-to-cursor)
     */
    public double scroll(double notches, double ground, double top) {
        double above = Math.max(1, altitudeTarget - ground), step = Math.max(1, above * ZOOM_STEP);
        double before = altitudeTarget;
        // Clamped on the target, so the eased altitude glides into the limit instead of bouncing off it.
        // The minimum also respects the local surface (a hill or tower under the camera), so zooming in never digs in.
        double low = Math.max(ground, groundTop) + minAbove;
        altitudeTarget = CameraMath.clamp(altitudeTarget - notches * step, low, Math.max(low, Math.min(ground + maxAbove, top)));
        return altitudeTarget - before;
    }

    /** How far the target may sit over the ceiling before it is pulled down (no chasing of small terrain changes). */
    public static final double CEILING_SLACK = 2;

    /**
     * Keeps the user's altitude within {@link #maxAbove} of the terrain under the camera: an input that does not move
     * with the eased altitude (the screen-centre hit does, which made the height chase itself). Only ever lowers the
     * target, with a deadband, and only when the render distance lowers {@link #maxAbove} below {@link #MAX_ABOVE}
     * (otherwise the scroll clamp alone sets the limit; the camera sits behind the view, so the terrain under it is
     * often lower than the centre ground and would cut the zoom). Clearance is added on top afterwards.
     */
    public void limitCeiling(double terrainUnderCamera) {
        if (Double.isNaN(terrainUnderCamera) || maxAbove >= MAX_ABOVE) return;
        double ceiling = terrainUnderCamera + maxAbove;
        if (altitudeTarget > ceiling + CEILING_SLACK) altitudeTarget = ceiling;
    }

    /** Raises or lowers the user's altitude with the terrain (the opt-in follow mode); no easing of its own. */
    public void shiftAltitude(double dy) {
        altitude += dy;
        altitudeTarget += dy;
    }

    /**
     * Advances one frame.
     *
     * @param dt            seconds since the last frame
     * @param right         input -1..1 along the screen's right
     * @param ahead         input -1..1 along the camera's horizontal facing
     * @param speedPerBlock horizontal speed in blocks/s per block of height above ground
     * @param ground        smoothed ground height under the screen centre (only scales the speed)
     * @param obstacleTop   highest surface (leaves included) around the camera and just ahead of it: the camera is
     *                      never drawn below it + {@link #CLEARANCE}
     */
    public void update(double dt, double right, double ahead, double speedPerBlock, double ground, double obstacleTop) {
        update(dt, right, ahead, speedPerBlock, ground, obstacleTop, obstacleTop);
    }

    /**
     * {@link #update(double, double, double, double, double, double)} with the hard floor separate from clearance:
     * {@code obstacleTop} (with look-ahead) only drives the eased clearance, {@code floorTop} (right under the camera)
     * is the instant floor, so things coming into view ahead lift the camera smoothly instead of snapping it.
     */
    public void update(double dt, double right, double ahead, double speedPerBlock, double ground, double obstacleTop, double floorTop) {
        altitude = CameraMath.ease(altitude, altitudeTarget, dt, ZOOM_TAU);
        double speed = Math.max(SPEED_FLOOR, Double.isNaN(ground) ? SPEED_FLOOR : altitude - ground) * speedPerBlock;
        double[] dir = CameraMath.pan(0, 0, yaw, right, ahead);
        double len = Math.hypot(dir[0], dir[1]);
        if (len < 1e-9) {
            // Immediate stop on release: no drift.
            vx = vz = 0;
        } else {
            vx = CameraMath.ease(vx, dir[0] / Math.max(1, len) * speed, dt, ACCEL_TAU);
            vz = CameraMath.ease(vz, dir[1] / Math.max(1, len) * speed, dt, ACCEL_TAU);
            x += vx * dt;
            z += vz * dt;
        }
        clampXZ();
        // A frame with no loaded columns keeps the last obstacle (never infinite: that would make NaN caps).
        if (Double.isFinite(obstacleTop)) lastObstacle = obstacleTop;
        else obstacleTop = Double.isNaN(lastObstacle) ? yOut - 2 * CLEARANCE : lastObstacle;
        if (Double.isFinite(floorTop)) lastFloor = floorTop;
        else floorTop = Double.isNaN(lastFloor) ? yOut - 2 * CLEARANCE : lastFloor;
        double needed = Math.max(0, obstacleTop + CLEARANCE - altitude);
        clearance = CameraMath.ease(clearance, needed, dt, CLEAR_TAU);
        // Safety net: whatever moves the height, the camera glides there.
        // The cap grows with the gap itself, so a climb toward an obstacle never stalls at the minimum.
        double target = altitude + clearance;
        double vmax = Math.max(MIN_VSPEED, Math.max(VSPEED_PER_BLOCK * Math.max(0, target - obstacleTop), VSPEED_GAP * Math.abs(target - yOut))) * dt;
        vStep = vmax;
        yOut += CameraMath.clamp(target - yOut, -vmax, vmax);
        // Hard floor, last: never inside the ground right under the camera, even if that means a jump up.
        yOut = Math.max(yOut, floorTop + CLEARANCE);
        // Never below the intended pan speed (fast pan included): this only turns jumps into glides.
        double hmax = Math.max(Math.max(MIN_HSPEED, HSPEED_PER_BLOCK * Math.max(0, yOut - obstacleTop)), 1.5 * Math.hypot(vx, vz)) * dt, dx = x - xOut, dz = z - zOut, dist = Math.hypot(dx, dz);
        double f = dist > hmax ? hmax / dist : 1;
        xOut += dx * f;
        zOut += dz * f;
    }

    /**
     * One frame with movement and turning together (WASD + Q/E): turn around the pivot, then move, and carry the
     * pivot along with the move so the next frame turns around the point that is now at the centre.
     *
     * @param turnRate degrees per second (negative = Q)
     * @param pivot    {x, y, z} updated in place, or null to turn in place
     */
    public void step(double dt, double right, double ahead, double turnRate, double[] pivot, double speedPerBlock, double ground, double obstacleTop) {
        step(dt, right, ahead, turnRate, pivot, speedPerBlock, ground, obstacleTop, obstacleTop);
    }

    /** {@link #step} with the hard floor separate from the clearance obstacle (see the 7-argument update). */
    public void step(double dt, double right, double ahead, double turnRate, double[] pivot, double speedPerBlock, double ground, double obstacleTop, double floorTop) {
        if (turnRate != 0) turn(pivot, (float) (turnRate * dt), 0);
        double ox = x, oz = z;
        update(dt, right, ahead, speedPerBlock, ground, obstacleTop, floorTop);
        // The pivot moves by the clamped move, so it can't creep outward against a bound.
        if (pivot != null) {
            pivot[0] += x - ox;
            pivot[2] += z - oz;
        }
    }

    /**
     * Turns (yaw) and tilts (pitch) around a ground point, normally the one at the screen centre: it stays at the
     * screen centre, the camera keeps its height and swings around it at the horizontal distance the new pitch needs.
     *
     * @param pivot {x, y, z} of the point, or null to turn in place
     */
    public void turn(double[] pivot, float dyaw, float dpitch) {
        float newYaw = (yaw + dyaw) % 360, newPitch = (float) CameraMath.clamp(pitch + dpitch, MIN_PITCH, MAX_PITCH);
        // A far or odd pivot (horizon, a distant mountain) would swing the camera sideways: turn in place then.
        if (pivot != null && Math.hypot(pivot[0] - x, pivot[2] - z) > PIVOT_REACH * Math.max(10, y() - pivot[1])) pivot = null;
        if (pivot != null && y() > pivot[1]) {
            // Yaw only: keep the distance (clearance lifting y mid-turn must not widen the orbit); tilt: from the height.
            double d = dpitch == 0 ? Math.hypot(pivot[0] - x, pivot[2] - z) : (y() - pivot[1]) / Math.tan(Math.toRadians(newPitch));
            double[] f = CameraMath.forward(newYaw, 0);
            x = pivot[0] - f[0] * d;
            z = pivot[2] - f[2] * d;
        }
        yaw = newYaw;
        pitch = newPitch;
        clampXZ();
    }

    /** Keeps x / z inside the box, then the circle (the loaded area always wins when they don't overlap). */
    public void clampXZ() {
        if (box != null) {
            x = CameraMath.clamp(x, box[0], box[2]);
            z = CameraMath.clamp(z, box[1], box[3]);
        }
        double[] c = CameraMath.clampToCircle(x, z, circleX, circleZ, circleR);
        x = c[0];
        z = c[1];
    }

    /** @return where the screen-centre ray meets the horizontal plane at {@code groundY}, or null if it never does */
    public double[] centreOn(double groundY) {
        double[] f = CameraMath.forward(yaw, pitch);
        if (f[1] >= -1e-6 || y() <= groundY) return null;
        double t = (groundY - y()) / f[1];
        return new double[]{x + f[0] * t, groundY, z + f[2] * t};
    }

    /** Toggles between straight down and the default tilt. */
    public void togglePitch() {
        pitch = pitch >= MAX_PITCH - 0.5f ? DEFAULT_PITCH : MAX_PITCH;
    }
}
