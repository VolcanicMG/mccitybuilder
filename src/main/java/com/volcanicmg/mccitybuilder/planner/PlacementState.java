package com.volcanicmg.mccitybuilder.planner;

/**
 * Placing a hut, like Structurize's build tool: the ghost FOLLOWs the cursor; a click drops it into ADJUST, where
 * arrow keys, height steps and drags move it by whole blocks and Place sends it. Esc / right-click in ADJUST goes
 * back to FOLLOW; in FOLLOW it ends placing. Height is an offset from the ground-aligned anchor height. Pure.
 */
public final class PlacementState {
    public enum Mode { FOLLOW, ADJUST }

    /** Arrow keys, relative to the camera. */
    public enum Dir { FORWARD, BACK, LEFT, RIGHT }

    public Mode mode = Mode.FOLLOW;
    /** The anchor (hut block) position. */
    public int x, y, z;
    /** Anchor height with offset 0 (ground-aligned), and the user's offset from it. */
    public int groundY, offset;
    public boolean hasAnchor;

    /** FOLLOW: the anchor goes where the cursor's ground-aligned anchor is, keeping the height offset. */
    public void follow(int ax, int groundAnchorY, int az) {
        if (mode != Mode.FOLLOW) return;
        x = ax;
        z = az;
        groundY = groundAnchorY;
        y = groundY + offset;
        hasAnchor = true;
    }

    /** FOLLOW: nothing under the cursor. */
    public void lose() {
        if (mode == Mode.FOLLOW) hasAnchor = false;
    }

    /** @return true if the ghost was dropped (needs an anchor) */
    public boolean drop() {
        if (mode != Mode.FOLLOW || !hasAnchor) return false;
        mode = Mode.ADJUST;
        return true;
    }

    /** Esc / right-click. @return false when placing should end (already following) */
    public boolean back() {
        if (mode == Mode.ADJUST) {
            mode = Mode.FOLLOW;
            return true;
        }
        return false;
    }

    /** ADJUST: moves the ghost by whole blocks. */
    public void move(int dx, int dz) {
        if (mode != Mode.ADJUST) return;
        x += dx;
        z += dz;
    }

    /** Raises (+) or lowers (-) the ghost, in either mode. */
    public void raise(int d) {
        offset += d;
        y = groundY + offset;
    }

    /** Back to ground level. */
    public void snap() {
        offset = 0;
        y = groundY;
    }

    /** @return "+2", "ground" or "-1" style text key and value: {key suffix, number} */
    public String offsetLabel() {
        return offset == 0 ? "ground" : (offset > 0 ? "+" : "") + offset;
    }

    /**
     * One arrow-key step relative to the camera: forward / back along the axis the camera faces most, left / right
     * along the screen's sideways axis.
     *
     * @return {dx, dz}
     */
    public static int[] step(float yawDeg, Dir dir, int blocks) {
        double y = Math.toRadians(yawDeg);
        double fx = -Math.sin(y), fz = Math.cos(y);
        int[] f = Math.abs(fx) > Math.abs(fz) ? new int[]{(int) Math.signum(fx), 0} : new int[]{0, (int) Math.signum(fz)};
        // Screen right is the facing turned clockwise seen from above: (fx, fz) -> (-fz, fx).
        int[] r = {-f[1], f[0]};
        return switch (dir) {
            case FORWARD -> new int[]{f[0] * blocks, f[1] * blocks};
            case BACK -> new int[]{-f[0] * blocks, -f[1] * blocks};
            case RIGHT -> new int[]{r[0] * blocks, r[1] * blocks};
            case LEFT -> new int[]{-r[0] * blocks, -r[1] * blocks};
        };
    }
}
