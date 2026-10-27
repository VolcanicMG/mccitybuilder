package com.volcanicmg.mccitybuilder.planner;

/**
 * Overhead camera geometry in Minecraft's conventions (yaw 0 = looking south +Z, yaw grows clockwise seen from above,
 * pitch positive = looking down). Pure, so the self-check covers it.
 */
public final class CameraMath {
    private CameraMath() {
    }

    /** @return the unit forward vector {x, y, z} for a yaw and pitch in degrees */
    public static double[] forward(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /**
     * Ray through a screen point.
     *
     * @param ndcX     -1 (left) .. 1 (right)
     * @param ndcY     -1 (bottom) .. 1 (top)
     * @param fovYDeg  vertical field of view
     * @param aspect   width / height
     * @return the unit direction {x, y, z}
     */
    public static double[] ray(double yawDeg, double pitchDeg, double fovYDeg, double aspect, double ndcX, double ndcY) {
        double[] f = forward(yawDeg, pitchDeg);
        double y = Math.toRadians(yawDeg);
        // Screen right; for yaw 0 (facing south) that is west, -X.
        double[] r = {-Math.cos(y), 0, -Math.sin(y)};
        double[] u = cross(r, f);
        double t = Math.tan(Math.toRadians(fovYDeg) / 2);
        double sx = ndcX * t * aspect, sy = ndcY * t;
        double[] d = {f[0] + r[0] * sx + u[0] * sy, f[1] + r[1] * sx + u[1] * sy, f[2] + r[2] * sx + u[2] * sy};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return new double[]{d[0] / len, d[1] / len, d[2] / len};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /**
     * Moves a pan target by screen-relative amounts: {@code right} along the camera's screen right, {@code ahead}
     * along its horizontal facing.
     *
     * @return the new {x, z}
     */
    public static double[] pan(double x, double z, double yawDeg, double right, double ahead) {
        double y = Math.toRadians(yawDeg);
        return new double[]{x - Math.cos(y) * right - Math.sin(y) * ahead, z - Math.sin(y) * right + Math.cos(y) * ahead};
    }

    /** @return {x, z} pulled back inside the circle of {@code radius} around (cx, cz) */
    public static double[] clampToCircle(double x, double z, double cx, double cz, double radius) {
        double dx = x - cx, dz = z - cz, d = Math.sqrt(dx * dx + dz * dz);
        if (d <= radius || d == 0) return new double[]{x, z};
        return new double[]{cx + dx / d * radius, cz + dz / d * radius};
    }

    /** @return {@code v} limited to [lo, hi] */
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Frame-rate independent exponential easing: after {@code dt} seconds the gap to {@code target} shrinks by
     * {@code exp(-dt / tau)} (about 95% settled after 3 tau).
     */
    public static double ease(double current, double target, double dt, double tau) {
        if (tau <= 0 || dt <= 0) return dt <= 0 ? current : target;
        return target + (current - target) * Math.exp(-dt / tau);
    }

    /**
     * A terrain height that ignores small bumps and single spikes: the {@code q} quantile (0..1) of the samples.
     *
     * @return the height, or {@code fallback} with no samples
     */
    /**
     * Zoom-to-cursor's sideways shift: toward the cursor point by {@code k} of its offset from the centre point, but
     * never more than twice the altitude change, so a far cursor hit (horizon, ocean floor) can't throw the camera.
     */
    public static double[] zoomShift(double offX, double offZ, double k, double dAlt) {
        double sx = offX * k, sz = offZ * k, len = Math.hypot(sx, sz), max = 2 * Math.abs(dAlt);
        return len > max ? new double[]{sx * max / len, sz * max / len} : new double[]{sx, sz};
    }

    /** Surface height at a block column, or null where it isn't loaded. */
    public interface Heights {
        Integer at(int x, int z);
    }

    /**
     * The {@code q} quantile of surface heights on a {@code step} grid within {@code radius} of (x, z). With fewer than
     * half the columns loaded (the edge of the loaded area) it returns {@code fallback}, not a guess from a few samples.
     */
    public static double sampleQuantile(double x, double z, double radius, double step, double q, double fallback, Heights h) {
        java.util.List<Integer> hs = new java.util.ArrayList<>();
        int total = 0;
        for (double dx = -radius; dx <= radius; dx += step)
            for (double dz = -radius; dz <= radius; dz += step) {
                total++;
                Integer v = h.at((int) Math.floor(x + dx), (int) Math.floor(z + dz));
                if (v != null) hs.add(v);
            }
        if (hs.size() * 2 < total) return fallback;
        return quantile(hs.stream().mapToInt(Integer::intValue).toArray(), q, fallback);
    }

    public static double quantile(int[] samples, double q, double fallback) {
        if (samples.length == 0) return fallback;
        int[] s = samples.clone();
        java.util.Arrays.sort(s);
        return s[(int) Math.round(clamp(q, 0, 1) * (s.length - 1))];
    }

    /**
     * Where a world point appears on screen (the inverse of {@link #ray}).
     *
     * @return {ndcX, ndcY} (-1..1, y up), or null when the point is behind the camera
     */
    public static double[] project(double[] cam, double yawDeg, double pitchDeg, double fovYDeg, double aspect, double[] p) {
        double[] f = forward(yawDeg, pitchDeg);
        double y = Math.toRadians(yawDeg);
        double[] r = {-Math.cos(y), 0, -Math.sin(y)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double[] d = {p[0] - cam[0], p[1] - cam[1], p[2] - cam[2]};
        double depth = d[0] * f[0] + d[1] * f[1] + d[2] * f[2];
        if (depth <= 1e-6) return null;
        double t = Math.tan(Math.toRadians(fovYDeg) / 2);
        double sx = (d[0] * r[0] + d[1] * r[1] + d[2] * r[2]) / depth, sy = (d[0] * u[0] + d[1] * u[1] + d[2] * u[2]) / depth;
        return new double[]{sx / (t * aspect), sy / t};
    }
}
