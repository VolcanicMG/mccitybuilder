package com.volcanicmg.mccitybuilder;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client settings, and the server's (synced to clients when they join). */
public final class Config {
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_HINTS;
    public static final ModConfigSpec.BooleanValue ALL_HINTS;
    public static final ModConfigSpec.BooleanValue LINE_TIPS_SEEN;
    public static final ModConfigSpec.DoubleValue PAN_SPEED;
    public static final ModConfigSpec.DoubleValue FAST_PAN;
    public static final ModConfigSpec.BooleanValue EDGE_PAN;
    public static final ModConfigSpec.BooleanValue FOLLOW_TERRAIN;
    public static final ModConfigSpec.DoubleValue TURN_SPEED;
    public static final ModConfigSpec.DoubleValue MIN_HEIGHT;
    public static final ModConfigSpec.BooleanValue ZOOM_TO_CURSOR;
    public static final ModConfigSpec.BooleanValue SHOW_CITIZENS;
    public static final ModConfigSpec.BooleanValue SHOW_STAFFING;
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.BooleanValue SURVIVAL_REMOTE_INVENTORIES;

    static {
        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        SHOW_HINTS = c.comment("Show the controls guide in planner view (toggled with its ? button).").define("showHints", true);
        LINE_TIPS_SEEN = c.comment("The road / wall tool's first-use tips were shown (set false to see them again).").define("lineTipsSeen", false);
        ALL_HINTS = c.comment("The controls guide lists every control, not only the main ones (its All / Less button).").define("allHints", false);
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
        SHOW_CITIZENS = c.comment("Planner overlay: show citizens as job-coloured markers (builders always show as hammer badges).").define("showCitizens", true);
        SHOW_STAFFING = c.comment("Planner overlay: show 'assigned/slots' over huts and outline under-staffed ones.").define("showStaffing", true);
        CLIENT_SPEC = c.build();
        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        SURVIVAL_REMOTE_INVENTORIES = s.comment("Survival players may open huts' and citizens' windows (and so their inventories) from the planner, however far away. Off: only in person, as usual; creative always may.")
                .define("survivalRemoteInventories", false);
        SERVER_SPEC = s.build();
    }

    /** @return the server's {@link #SURVIVAL_REMOTE_INVENTORIES}, false when it isn't known (a server without City Builder) */
    public static boolean survivalRemoteInventories() {
        try {
            return SURVIVAL_REMOTE_INVENTORIES.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Config() {
    }
}
