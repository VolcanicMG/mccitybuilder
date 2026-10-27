package com.volcanicmg.mccitybuilder;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client settings. */
public final class Config {
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_HINTS;
    public static final ModConfigSpec.DoubleValue PAN_SPEED;
    public static final ModConfigSpec.DoubleValue FAST_PAN;
    public static final ModConfigSpec.BooleanValue EDGE_PAN;
    public static final ModConfigSpec.BooleanValue FOLLOW_TERRAIN;
    public static final ModConfigSpec.DoubleValue TURN_SPEED;
    public static final ModConfigSpec.DoubleValue MIN_HEIGHT;
    public static final ModConfigSpec.BooleanValue ZOOM_TO_CURSOR;

    static {
        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        SHOW_HINTS = c.comment("Show the controls guide in planner view (toggled with its ? button).").define("showHints", true);
        PAN_SPEED = c.comment("Camera pan speed in blocks per second at 20 blocks above the ground (it scales with height).")
                .defineInRange("panSpeed", 24.0, 4.0, 200.0);
        FAST_PAN = c.comment("Pan speed multiplier while Shift is held (WASD and edge pan).").defineInRange("fastPanMultiplier", 3.0, 1.0, 10.0);
        MIN_HEIGHT = c.comment("Lowest camera height above the ground at the screen centre that zooming reaches, blocks.")
                .defineInRange("minHeight", 10.0, 2.0, 64.0);
        TURN_SPEED = c.comment("Q/E turn speed, degrees per second.").defineInRange("turnSpeed", 90.0, 15.0, 360.0);
        ZOOM_TO_CURSOR = c.comment("Scrolling also moves toward / away from the point under the mouse.").define("zoomToCursor", false);
        EDGE_PAN = c.comment("Pan when the mouse touches the screen edge.").define("edgePan", true);
        FOLLOW_TERRAIN = c.comment("Keep the camera's height above the land: it glides up over hills and plateaus and down into valleys (a broad, slow terrain baseline under the camera; trees, buildings and small bumps don't move it). Off: you set an absolute altitude; terrain only pushes the camera up when it would get too close.")
                .define("followTerrain", true);
        CLIENT_SPEC = c.build();
    }

    private Config() {
    }
}
