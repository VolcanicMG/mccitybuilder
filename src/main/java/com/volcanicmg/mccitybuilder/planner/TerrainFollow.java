package com.volcanicmg.mccitybuilder.planner;

/**
 * Terrain follow for the drone camera: a broad terrain baseline under the camera (sampled by the caller, independent
 * of the camera's height) that only moves once the terrain changes by more than {@link #DEADBAND} blocks, and then
 * glides there over ~{@link #TAU} s. The camera's altitude is shifted by each frame's baseline change, so it keeps its
 * height above the land without jitter from trees, buildings or small bumps. Pure.
 */
public final class TerrainFollow {
    public static final double DEADBAND = 4.5, TAU = 1.2;
    private double target = Double.NaN, baseline = Double.NaN;

    /**
     * @param raw the latest broad terrain sample (NaN when unknown)
     * @param dt  seconds since the last frame
     * @return how far the baseline moved this frame: shift the altitude by it
     */
    public double step(double raw, double dt) {
        if (Double.isNaN(raw)) return 0;
        if (Double.isNaN(baseline)) {
            baseline = target = raw;
            return 0;
        }
        if (Math.abs(raw - target) > DEADBAND) target = raw;
        double before = baseline;
        baseline = CameraMath.ease(baseline, target, dt, TAU);
        return baseline - before;
    }

    /** @return the eased baseline: the one height reference for zoom limits, ceiling, saved height (NaN until sampled) */
    public double baseline() {
        return baseline;
    }

    /** Forgets the baseline (a new planner session starts from the terrain it finds). */
    public void reset() {
        target = baseline = Double.NaN;
    }
}
