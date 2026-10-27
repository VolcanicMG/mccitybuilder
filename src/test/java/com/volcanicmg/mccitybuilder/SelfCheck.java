package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.CameraMath;
import com.volcanicmg.mccitybuilder.planner.ColonyGeometry;
import com.volcanicmg.mccitybuilder.planner.Costs;
import com.volcanicmg.mccitybuilder.planner.QueueIcons;
import com.volcanicmg.mccitybuilder.planner.DroneCamera;
import com.volcanicmg.mccitybuilder.planner.PlacementState;
import com.volcanicmg.mccitybuilder.planner.Hints;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.QueueOrder;
import com.volcanicmg.mccitybuilder.planner.TerrainFollow;
import com.volcanicmg.mccitybuilder.planner.CameraRig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Assert-based checks of the pure logic ({@code ./gradlew selfCheck}, part of {@code check}). */
public final class SelfCheck {
    private SelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        camera();
        drone();
        placement();
        queue();
        colonyGeometry();
        costs();
        catalog();
        overlays();
        plans();
        roads();
        hintsAndLang();
        System.out.println("CityBuilder self-check passed");
    }

    private static void camera() {
        double[] f = CameraMath.forward(0, 0);
        check(near(f, 0, 0, 1), "yaw 0 looks south (+Z)");
        check(near(CameraMath.forward(90, 0), -1, 0, 0), "yaw 90 looks west (-X)");
        check(near(CameraMath.forward(0, 90), 0, -1, 0), "pitch 90 looks straight down");
        check(near(CameraMath.ray(30, 60, 70, 16 / 9.0, 0, 0), CameraMath.forward(30, 60)), "centre ray = forward");
        // Facing south, level: the right edge of the screen is west (-X), the top is up.
        double[] r = CameraMath.ray(0, 0, 90, 1, 1, 0), u = CameraMath.ray(0, 0, 90, 1, 0, 1);
        check(r[0] < 0 && Math.abs(r[1]) < 1e-9 && r[2] > 0 && Math.abs(Math.abs(r[0]) - r[2]) < 1e-9, "right edge at 45° west of south (fov 90)");
        check(u[1] > 0 && Math.abs(u[0]) < 1e-9 && Math.abs(u[1] - u[2]) < 1e-9, "top edge at 45° up");
        // Looking down at 60°, the bottom of the screen hits the ground closer than the centre.
        double[] c = CameraMath.ray(0, 60, 70, 1.5, 0, 0), b = CameraMath.ray(0, 60, 70, 1.5, 0, -1);
        check(b[1] / Math.hypot(b[0], b[2]) < c[1] / Math.hypot(c[0], c[2]), "lower rays are steeper");
        double[] p = CameraMath.pan(0, 0, 0, 0, 5);
        check(Math.abs(p[0]) < 1e-9 && Math.abs(p[1] - 5) < 1e-9, "pan ahead facing south moves +Z");
        p = CameraMath.pan(0, 0, 0, 3, 0);
        check(Math.abs(p[0] + 3) < 1e-9 && Math.abs(p[1]) < 1e-9, "pan right facing south moves -X");
        p = CameraMath.pan(0, 0, 90, 0, 2);
        check(Math.abs(p[0] + 2) < 1e-9 && Math.abs(p[1]) < 1e-9, "pan ahead facing west moves -X");
        double[] k = CameraMath.clampToCircle(30, 40, 0, 0, 10);
        check(Math.abs(Math.hypot(k[0], k[1]) - 10) < 1e-9 && Math.abs(k[0] / k[1] - 0.75) < 1e-9, "clamp keeps direction, radius 10");
        check(near2(CameraMath.clampToCircle(3, 4, 0, 0, 10), 3, 4), "inside stays put");
        check(CameraMath.clamp(5, 10, 20) == 10 && CameraMath.clamp(25, 10, 20) == 20, "clamp");
        // Easing is frame-rate independent: two half steps land where one full step does, and it settles.
        double one = CameraMath.ease(0, 10, 0.1, 0.15), two = CameraMath.ease(CameraMath.ease(0, 10, 0.05, 0.15), 10, 0.05, 0.15);
        check(Math.abs(one - two) < 1e-9 && one > 0 && one < 10, "ease frame-rate independent");
        check(Math.abs(CameraMath.ease(0, 10, 0.6, 0.15) - 10) < 0.2, "settles within ~0.6 s");
        check(CameraMath.ease(3, 10, 0, 0.15) == 3, "no time, no move");
        // Terrain height: a single spike (a tree trunk, a tower) does not lift the camera target; a plateau does.
        int[] flat = {64, 64, 65, 64, 64, 63, 64, 64, 90};
        check(CameraMath.quantile(flat, 0.75, 0) == 64, "spike ignored: " + CameraMath.quantile(flat, 0.75, 0));
        int[] hill = {64, 64, 70, 70, 70, 70, 70, 70, 70};
        check(CameraMath.quantile(hill, 0.75, 0) == 70, "plateau followed");
        check(CameraMath.quantile(new int[0], 0.75, 42) == 42, "no samples = fallback");
    }

    /**
     * The height settles and stays put over awkward terrain. The screen-centre ground is modelled the bad way (it
     * depends on the camera's own height: a cliff edge seen from above or below), the ceiling uses the terrain under
     * the camera, a tower gives clearance, and the render distance gives a low ceiling.
     */
    private static void settles() {
        record Case(String name, double under, double obstacle, double maxAbove, double start) {
        }
        for (Case k : List.of(new Case("cliff edge", 64, 64, 160, 230), new Case("deep valley", 20, 20, 160, 200),
                new Case("tall building", 64, 140, 160, 150), new Case("low render distance", 64, 64, 112, 230),
                new Case("ceiling under the clearance", 64, 190, 112, 230))) {
            DroneCamera d = new DroneCamera(0, k.start(), 0, 0);
            d.maxAbove = k.maxAbove();
            double last = Double.NaN;
            for (int i = 0; i < 1200; i++) {
                double centre = d.y() > 150 ? 20 : 90;
                d.limitCeiling(k.under());
                d.update(1 / 60.0, 0, 0, 1, centre, k.obstacle());
                if (i == 1199 - 60) last = d.y();
            }
            check(Math.abs(d.y() - last) < 1e-3, "height settles: " + k.name() + " " + last + " -> " + d.y());
            check(d.y() >= k.obstacle() + DroneCamera.CLEARANCE - 0.01, "clearance wins over the ceiling: " + k.name());
            if (k.maxAbove() < DroneCamera.MAX_ABOVE)
                check(d.altitudeTarget <= k.under() + k.maxAbove() + DroneCamera.CEILING_SLACK, "under the ceiling: " + k.name());
        }
        // Render distance 12 (no cap): terrain under the camera 30 below the centre ground must not cut the zoom-out.
        DroneCamera far = new DroneCamera(0, 100, 0, 0);
        for (int i = 0; i < 100; i++) far.scroll(-1, 64, 320);
        for (int i = 0; i < 300; i++) {
            far.limitCeiling(34);
            far.update(1 / 60.0, 0, 0, 1, 64, 34);
        }
        check(far.altitudeTarget == 64 + DroneCamera.MAX_ABOVE, "full zoom-out kept at rd 12: " + far.altitudeTarget);
    }

    /** Terrain follow: a 40-block plateau step is followed smoothly to the same height above ground; ±2 noise is ignored. */
    private static void follows() {
        TerrainFollow f = new TerrainFollow();
        DroneCamera d = new DroneCamera(0, 64 + 50, 0, 0);
        double peak = 0, prev = d.altitude;
        boolean monotonic = true;
        for (int i = 0; i < 1200; i++) {
            d.x = i * 0.2;
            // Broad median: low ground, then a plateau 40 higher once the camera is well over it, with ±2 noise.
            double raw = (d.x > 60 ? 104 : 64) + (i % 2 == 0 ? 2 : -2);
            d.shiftAltitude(f.step(raw, 1 / 60.0));
            d.update(1 / 60.0, 0, 0, 1, raw, 0);
            if (i > 0 && d.altitude < prev - 1e-9) monotonic = false;
            prev = d.altitude;
            peak = Math.max(peak, d.altitude);
        }
        check(Math.abs(d.altitude - 104 - 50) <= TerrainFollow.DEADBAND, "same height above the plateau: " + (d.altitude - 104));
        check(peak <= 104 + 50 + TerrainFollow.DEADBAND + 1e-6 && monotonic, "no overshoot or oscillation: peak " + peak);
        TerrainFollow n = new TerrainFollow();
        double moved = 0;
        for (int i = 0; i < 600; i++) moved += Math.abs(n.step(64 + (i % 3 - 1) * 2, 1 / 60.0));
        check(moved == 0, "±2 noise doesn't move the camera: " + moved);
    }

    /**
     * No jumps: panning over rough terrain (±30 hills, 12-block buildings) into an unloaded area, with the centre ray
     * missing every 10th frame, keeps the camera's per-frame height change and its second difference small; a far
     * turn pivot and a far zoom-to-cursor hit move the camera only a little; the zoom limit ignores the centre hit.
     */
    private static void noJumps() {
        CameraMath.Heights hm = (bx, bz) -> bz > 500 ? null
                : (int) Math.round(64 + 30 * Math.sin(bx / 23.0) * Math.cos(bz / 31.0) + (Math.floorMod(bx * 7 + bz * 13, 9) == 0 ? 12 : 0));
        DroneCamera d = new DroneCamera(0, 130, 0, 0);
        TerrainFollow f = new TerrainFollow();
        double broad = Double.NaN, prevY = d.y(), prevDy = 0, maxDy = 0, maxDdy = 0;
        for (int i = 0; i < 1800; i++) {
            if (i % 15 == 0) broad = CameraMath.sampleQuantile(d.x, d.z, 28, 4, 0.5, Double.isNaN(broad) ? 64 : broad, hm);
            d.shiftAltitude(f.step(broad, 1 / 60.0));
            d.limitCeiling(f.baseline());
            double obstacle = Double.NEGATIVE_INFINITY;
            for (int dx = -3; dx <= 3; dx += 3)
                for (int dz = -3; dz <= 3; dz += 3) {
                    Integer h = hm.at((int) Math.floor(d.x) + dx, (int) Math.floor(d.z) + dz);
                    if (h != null) obstacle = Math.max(obstacle, h);
                }
            Integer c = hm.at((int) d.x, (int) d.z + 40);
            double centre = i % 10 == 0 || c == null ? Double.NaN : c;
            d.update(1 / 60.0, 0, 1, 1.2, centre, Double.isInfinite(obstacle) ? 0 : obstacle);
            double dy = d.y() - prevY;
            if (i > 1) {
                maxDy = Math.max(maxDy, Math.abs(dy));
                maxDdy = Math.max(maxDdy, Math.abs(dy - prevDy));
            }
            prevY = d.y();
            prevDy = dy;
        }
        check(d.z > 520, "panned into the unloaded area: " + d.z);
        check(maxDy < 1.0 && maxDdy < 0.25, "smooth height over rough terrain: max dy " + maxDy + ", max d2y " + maxDdy);
        // A far pivot (the horizon) during a turn: the camera turns in place instead of swinging away.
        DroneCamera t = new DroneCamera(0, 100, 0, 0);
        double bx = t.camX(), bz = t.camZ();
        t.turn(new double[]{0, 64, 900}, 30, 0);
        t.update(1 / 60.0, 0, 0, 1, 64, 64);
        check(Math.hypot(t.x - bx, t.z - bz) < 1e-9 && Math.hypot(t.camX() - bx, t.camZ() - bz) < 1, "far pivot: turn in place");
        // A near pivot still orbits, but the drawn position glides (capped per frame).
        DroneCamera o = new DroneCamera(0, 100, 0, 0);
        double[] piv = o.centreOn(64);
        o.turn(piv, 90, 0);
        double ox = o.camX(), oz = o.camZ();
        o.update(1 / 60.0, 0, 0, 1, 64, 64);
        check(Math.hypot(o.camX() - ox, o.camZ() - oz) <= Math.max(DroneCamera.MIN_HSPEED, DroneCamera.HSPEED_PER_BLOCK * 36) / 60.0 + 1e-9, "orbit jump becomes a glide");
        // Zoom-to-cursor with a cursor hit 600 blocks away: the shift stays within twice the altitude change.
        double[] s = CameraMath.zoomShift(600, 0, 1, 6);
        check(Math.hypot(s[0], s[1]) <= 12 + 1e-9, "far cursor hit can't throw the camera");
        // One height reference: ocean vs mountain at the centre, same baseline → same zoom-out limit.
        DroneCamera ocean = new DroneCamera(0, 100, 0, 0), mountain = new DroneCamera(0, 100, 0, 0);
        for (int i = 0; i < 100; i++) {
            ocean.scroll(-1, 70, 320);
            mountain.scroll(-1, 70, 320);
            ocean.update(1 / 60.0, 0, 0, 1, 40, 70);
            mountain.update(1 / 60.0, 0, 0, 1, 140, 70);
        }
        check(ocean.altitudeTarget == mountain.altitudeTarget && ocean.altitudeTarget == 70 + DroneCamera.MAX_ABOVE, "max altitude ignores the centre hit");
    }

    /**
     * Never inside the ground: zooming fully in over a 40-high tower on flat land, panning low onto a cliff top, and
     * zoom-to-cursor carrying the camera onto a mountain keep the drawn height at or above surface + clearance on
     * every frame.
     */
    private static void neverUnderground() {
        // Tower: baseline 64 (flat land), local surface 104.
        DroneCamera t = new DroneCamera(0, 130, 0, 0);
        t.groundTop = 104;
        for (int i = 0; i < 300; i++) {
            t.scroll(1, 64, 320);
            t.update(1 / 60.0, 0, 0, 1, 64, 104);
            check(t.y() >= 104 + DroneCamera.CLEARANCE - 1e-9, "tower: above the roof on frame " + i + ": " + t.y());
        }
        check(t.altitudeTarget >= 104 + t.minAbove - 1e-9, "zoom minimum respects the tower: " + t.altitudeTarget);
        // Cliff: low over flat land, then the surface jumps 40 up under the camera.
        DroneCamera c = new DroneCamera(0, 76, 0, 0);
        for (int i = 0; i < 300; i++) {
            double surface = i < 100 ? 64 : 104;
            c.update(1 / 60.0, 0, 1, 1.2, 64, surface);
            check(c.y() >= surface + DroneCamera.CLEARANCE - 1e-9, "cliff: never under the top on frame " + i + ": " + c.y());
        }
        // Zoom-to-cursor toward a mountain: the camera moves over it while descending.
        DroneCamera m = new DroneCamera(0, 120, 0, 0);
        for (int i = 0; i < 300; i++) {
            m.scroll(1, 64, 320);
            double[] s = CameraMath.zoomShift(0, 200, 1, 3);
            m.z += s[1];
            double surface = m.z > 30 ? 64 + Math.min(80, (m.z - 30) * 2) : 64;
            m.update(1 / 60.0, 0, 0, 1, 64, surface);
            check(m.y() >= surface + DroneCamera.CLEARANCE - 1e-9, "mountain: never inside on frame " + i + ": " + m.y());
        }
    }

    /** A synthetic world for {@link CameraRig}: buildings count for both heights, trees only for the surface. */
    private record Terrain(java.util.function.IntBinaryOperator ground, java.util.function.IntBinaryOperator trees) implements com.volcanicmg.mccitybuilder.planner.CameraRig.World {
        public Integer ground(int x, int z) {
            return ground.applyAsInt(x, z);
        }

        public Integer surface(int x, int z) {
            return Math.max(ground.applyAsInt(x, z), trees.applyAsInt(x, z));
        }
    }

    /** Fails if the height moved more than the speed cap allowed this frame (no snaps). */
    private static double capped(com.volcanicmg.mccitybuilder.planner.CameraRig r, double prevY, String what) {
        double dy = r.drone.y() - prevY;
        check(Math.abs(dy) <= r.drone.vStep() + 1e-6, what + ": snapped " + dy + " (cap " + r.drone.vStep() + ")");
        return r.drone.y();
    }

    /** Fails if the drawn camera is inside or below the surface right around it. */
    private static void aboveSurface(com.volcanicmg.mccitybuilder.planner.CameraRig r, Terrain w, String what) {
        DroneCamera d = r.drone;
        int cx = (int) Math.floor(d.camX()), cz = (int) Math.floor(d.camZ());
        check(d.y() >= w.surface(cx, cz) + DroneCamera.CLEARANCE - 1e-6, what + ": below surface + clearance at y " + d.y());
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) check(d.y() > w.surface(cx + dx, cz + dz), what + ": inside a block at y " + d.y());
    }

    /**
     * The real per-frame loop end to end (CameraRig: baseline sampling, follow, ceiling, step with the hard floor) over
     * synthetic terrain: open, scroll fully out (baseline + 160 within 3 s), scroll fully in (stops at max(baseline,
     * local surface) + minHeight), never inside the ground; on flat land, a hill, a tower; and panning at normal and
     * fast speed into a 60-block cliff wall while low.
     */
    private static void rigIntegration() {
        double dt = 1 / 60.0;
        java.util.Map<String, Terrain> worlds = new java.util.LinkedHashMap<>();
        worlds.put("flat", new Terrain((x, z) -> 64, (x, z) -> 64));
        worlds.put("hill", new Terrain((x, z) -> (int) Math.round(64 + 40 * Math.exp(-(x * x + z * z) / 400.0)), (x, z) -> 64));
        worlds.put("tower", new Terrain((x, z) -> Math.abs(x) <= 2 && Math.abs(z) <= 2 ? 104 : 64, (x, z) -> 64));
        worlds.put("forest", new Terrain((x, z) -> 64, (x, z) -> Math.floorMod(x * 5 + z * 3, 7) == 0 ? 72 : 64));
        for (var en : worlds.entrySet()) {
            Terrain w = en.getValue();
            var r = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 30, 0, 0));
            for (int i = 0; i < 60; i++) {
                r.frame(w, dt, 0, 0, 0, null, 1.2, Double.NaN);
                aboveSurface(r, w, en.getKey() + " open");
            }
            for (int i = 0; i < 180; i++) {
                if (i % 3 == 0 && i < 90) r.scroll(-1);
                r.frame(w, dt, 0, 0, 0, null, 1.2, Double.NaN);
                aboveSurface(r, w, en.getKey() + " out");
            }
            check(Math.abs(r.drone.y() - (r.baseline() + DroneCamera.MAX_ABOVE)) <= 2, en.getKey() + ": full zoom-out = baseline + 160: y " + r.drone.y() + " baseline " + r.baseline());
            double py = r.drone.y();
            for (int i = 0; i < 300; i++) {
                if (i % 3 == 0 && i < 90) r.scroll(1);
                r.frame(w, dt, 0, 0, 0, null, 1.2, Double.NaN);
                aboveSurface(r, w, en.getKey() + " in");
                py = capped(r, py, en.getKey() + " in");
            }
            double local = r.localSurface(w, dt);
            check(Math.abs(r.drone.altitudeTarget - (Math.max(r.baseline(), local) + r.drone.minAbove)) < 1e-6,
                    en.getKey() + ": full zoom-in stops at max(baseline, local) + min: " + r.drone.altitudeTarget + " local " + local);
        }
        // Low and fast toward a 60-block cliff wall: the camera rises; never inside the wall.
        Terrain cliff = new Terrain((x, z) -> z > 100 ? 124 : 64, (x, z) -> 64);
        for (double speed : new double[]{1.2, 3.6}) {
            var r = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 10, 0, 0));
            double py = r.drone.y();
            for (int i = 0; i < 900; i++) {
                r.frame(cliff, dt, 0, 1, 0, null, speed, 64);
                aboveSurface(r, cliff, "cliff wall at " + speed);
                py = capped(r, py, "cliff wall at " + speed);
            }
            check(r.drone.camZ() > 110, "crossed the wall: " + r.drone.camZ());
        }
    }

    /**
     * The client's column data: MOTION_BLOCKING only (the NO_LEAVES heightmap is never sent and reads minBuildHeight
     * there, which once made "ground" -64). A tree column must give the soil, not the canopy and not -64.
     */
    private static void clientColumns() {
        var src = new com.volcanicmg.mccitybuilder.planner.Columns.Source() {
            public boolean loaded(int x, int z) {
                return x < 1000;
            }

            public int motionBlocking(int x, int z) {
                return x % 5 == 0 ? 80 : 70;
            }

            public boolean notGround(int x, int y, int z) {
                return y >= 70;
            }

            public int minY() {
                return -64;
            }
        };
        var cols = new com.volcanicmg.mccitybuilder.planner.Columns(src);
        check(cols.ground(5, 0) == 70 && cols.surface(5, 0) == 80, "tree column: ground = soil, surface = canopy");
        check(cols.ground(1, 0) == 70 && cols.ground(2000, 0) == null, "open column and unloaded column");
        // The whole loop on that data: zoom-out reaches the real land + 160, never anything near -64.
        var r = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 100, 0, 0));
        for (int i = 0; i < 240; i++) {
            if (i % 3 == 0 && i < 90) r.scroll(-1);
            r.frame(cols, 1 / 60.0, 0, 0, 0, null, 1.2, Double.NaN);
        }
        check(Math.abs(r.baseline() - 70) < 1e-6 && Math.abs(r.drone.y() - 230) <= 2, "client data: baseline " + r.baseline() + ", full zoom-out " + r.drone.y());
    }

    /** Review fixes: held follow during turns, entry clamp, no loaded columns, bounds inside the drone. */
    private static void rigEdges() {
        double dt = 1 / 60.0;
        // Holding Q around a pivot on a steep slope: the height doesn't run away (follow waits for the turn to end).
        Terrain slope = new Terrain((x, z) -> 64 + (int) Math.round(0.8 * x), (x, z) -> 64 + (int) Math.round(0.8 * x));
        var r = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 120, 0, 0));
        for (int i = 0; i < 60; i++) r.frame(slope, dt, 0, 0, 0, null, 1.2, 64);
        double[] pivot = r.drone.centreOn(64);
        double before = r.drone.altitudeTarget;
        for (int i = 0; i < 600; i++) r.frame(slope, dt, 0, 0, 90, pivot, 1.2, 64);
        check(Math.abs(r.drone.altitudeTarget - before) < 1e-9, "no altitude feedback while orbiting: " + before + " -> " + r.drone.altitudeTarget);
        // Turning low next to a tower sweeps it into the view look-ahead: the camera rises smoothly, no snap.
        Terrain tower = new Terrain((x, z) -> Math.abs(x - 7) <= 2 && Math.abs(z) <= 2 ? 100 : 64, (x, z) -> 64);
        var tr = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 12, -8, 0));
        for (int i = 0; i < 30; i++) tr.frame(tower, dt, 0, 0, 0, null, 1.2, 64);
        double ty = tr.drone.y();
        for (int i = 0; i < 480; i++) {
            tr.frame(tower, dt, 0, 0, 90, null, 1.2, 64);
            aboveSurface(tr, tower, "turn by a tower");
            ty = capped(tr, ty, "turn by a tower");
        }
        // Entry far too high: the first real baseline clamps it into range.
        var hi = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 400, 0, 0));
        hi.frame(new Terrain((x, z) -> 64, (x, z) -> 64), dt, 0, 0, 0, null, 1.2, Double.NaN);
        check(hi.drone.altitudeTarget <= 64 + DroneCamera.MAX_ABOVE + 1e-9, "entry clamped: " + hi.drone.altitudeTarget);
        // Nothing loaded yet (login, teleport): the height stays finite and the baseline unknown.
        var none = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 100, 0, 0));
        CameraRig.World empty = new CameraRig.World() {
            public Integer ground(int x, int z) {
                return null;
            }

            public Integer surface(int x, int z) {
                return null;
            }
        };
        none.frame(empty, 0, 0, 0, 0, null, 1.2, Double.NaN);
        for (int i = 0; i < 60; i++) none.frame(empty, dt, 0, 1, 0, null, 1.2, Double.NaN);
        check(Double.isFinite(none.drone.y()) && Double.isNaN(none.baseline()), "no loaded columns: finite height " + none.drone.y());
        // Bounds live in the drone: an orbit around a pivot outside the box stays in the box, and the pivot doesn't creep.
        DroneCamera b = new DroneCamera(0, 100, 0, 0);
        b.box = new double[]{-20, -20, 20, 20};
        double[] piv = {0, 64, 30};
        b.turn(piv, 180, 0);
        check(b.x >= -20 && b.x <= 20 && b.z >= -20 && b.z <= 20, "turn clamped to the box: " + b.x + "," + b.z);
        double[] p2 = {0, 64, 10};
        for (int i = 0; i < 120; i++) b.step(dt, 0, 1, 0, p2, 5, 64, 64);
        check(b.z <= 20 && Math.abs(p2[2] - 10 - (b.z - 0)) < 50, "panning into the bound: z " + b.z);
    }

    private static void drone() {
        // Scroll changes only the altitude: x, z, yaw and pitch stay.
        DroneCamera d = new DroneCamera(100, 120, -50, 30);
        d.scroll(1, 64, 320);
        check(d.x == 100 && d.z == -50 && d.yaw == 30 && d.pitch == DroneCamera.DEFAULT_PITCH && d.altitudeTarget < 120, "scroll = altitude only");
        for (int i = 0; i < 60; i++) d.update(1 / 60.0, 0, 0, 1, 64, 0);
        check(d.x == 100 && d.z == -50 && Math.abs(d.altitude - d.altitudeTarget) < 0.05, "altitude eases to the target in ~1 s, no drift");
        double step1 = 120 - d.altitudeTarget;
        check(Math.abs(step1 - 56 * DroneCamera.ZOOM_STEP) < 1e-9, "step = 11% of the height above ground: " + step1);
        // Zooming in stops at ground + minAbove; the eased altitude glides into it without overshooting.
        for (int i = 0; i < 100; i++) d.scroll(1, 64, 320);
        check(d.altitudeTarget == 64 + d.minAbove, "min height clamp: " + d.altitudeTarget);
        double prev = d.altitude;
        for (int i = 0; i < 120; i++) {
            d.update(1 / 60.0, 0, 0, 1, 64, 0);
            check(d.altitude <= prev + 1e-9 && d.altitude >= 64 + d.minAbove - 1e-9, "no bounce against the clamp");
            prev = d.altitude;
        }
        for (int i = 0; i < 100; i++) d.scroll(-1, 64, 320);
        check(d.altitudeTarget == 64 + DroneCamera.MAX_ABOVE, "max height clamp");
        d.maxAbove = 100;
        d.scroll(-1, 64, 320);
        check(d.altitudeTarget == 64 + 100, "short render distance lowers the ceiling");
        settles();
        follows();
        noJumps();
        neverUnderground();
        rigIntegration();
        clientColumns();
        rigEdges();
        // Pan speed scales with the height above ground; release stops at once.
        DroneCamera low = new DroneCamera(0, 74, 0, 0), high = new DroneCamera(0, 104, 0, 0);
        for (int i = 0; i < 30; i++) {
            low.update(1 / 60.0, 0, 1, 1, 64, 0);
            high.update(1 / 60.0, 0, 1, 1, 64, 0);
        }
        check(Math.abs(high.z / low.z - 4) < 0.05 && low.x == 0, "speed ∝ height: " + high.z / low.z);
        double zStop = low.z;
        low.update(1 / 60.0, 0, 0, 1, 64, 0);
        check(low.z == zStop, "no drift after release");
        // Clearance: a tower under the camera pushes it up; the user's altitude never changes; it releases after.
        DroneCamera c = new DroneCamera(0, 80, 0, 0);
        for (int i = 0; i < 120; i++) c.update(1 / 60.0, 0, 0, 1, 64, 90);
        check(c.altitude == 80 && Math.abs(c.y() - 93) < 0.05, "pushed up to 90 + 3, altitude kept: " + c.y());
        for (int i = 0; i < 120; i++) c.update(1 / 60.0, 0, 0, 1, 64, 64);
        check(c.altitude == 80 && Math.abs(c.y() - 80) < 0.05, "released back to the user's altitude");
        // Frame-rate independence of the short eases: 60 fps and 240 fps land at the same place.
        DroneCamera a60 = new DroneCamera(0, 150, 0, 0), a240 = new DroneCamera(0, 150, 0, 0);
        a60.scroll(3, 64, 320);
        a240.scroll(3, 64, 320);
        for (int i = 0; i < 6; i++) a60.update(1 / 60.0, 0, 0, 1, 64, 0);
        for (int i = 0; i < 24; i++) a240.update(1 / 240.0, 0, 0, 1, 64, 0);
        check(Math.abs(a60.altitude - a240.altitude) < 1e-6, "zoom ease frame-rate independent");
        // Turning keeps the ground point at the screen centre fixed, altitude and pitch unchanged.
        DroneCamera t = new DroneCamera(10, 100, 20, 0);
        double[] pivot = t.centreOn(64);
        t.turn(pivot, 90, 0);
        double[] after = t.centreOn(64);
        check(Math.abs(after[0] - pivot[0]) < 1e-9 && Math.abs(after[2] - pivot[2]) < 1e-9 && t.altitude == 100
                && t.pitch == DroneCamera.DEFAULT_PITCH && t.yaw == 90, "90° turn keeps the centre point");
        t.turn(pivot, 0, 20);
        after = t.centreOn(64);
        check(Math.abs(after[0] - pivot[0]) < 1e-9 && Math.abs(after[2] - pivot[2]) < 1e-9 && t.altitude == 100, "tilt pivots too");
        DroneCamera inPlace = new DroneCamera(5, 90, 5, 0);
        inPlace.turn(null, 45, 0);
        check(inPlace.x == 5 && inPlace.z == 5 && inPlace.yaw == 45, "no pivot: turn in place");
    }

    private static void placement() {
        // Arrow-key steps follow the camera: facing south (+Z) forward is +Z and right is west (-X); facing east, +X / +Z.
        check(java.util.Arrays.equals(PlacementState.step(0, PlacementState.Dir.FORWARD, 1), new int[]{0, 1}), "south: forward +Z");
        check(java.util.Arrays.equals(PlacementState.step(0, PlacementState.Dir.RIGHT, 1), new int[]{-1, 0}), "south: right -X");
        check(java.util.Arrays.equals(PlacementState.step(-90, PlacementState.Dir.FORWARD, 5), new int[]{5, 0}), "east: forward +X x5");
        check(java.util.Arrays.equals(PlacementState.step(-90, PlacementState.Dir.LEFT, 1), new int[]{0, -1}), "east: left -Z");
        check(java.util.Arrays.equals(PlacementState.step(180, PlacementState.Dir.BACK, 1), new int[]{0, 1}), "north: back +Z");
        check(java.util.Arrays.equals(PlacementState.step(40, PlacementState.Dir.FORWARD, 1), new int[]{0, 1}), "mostly south snaps to +Z");
        // State machine: follow -> drop -> adjust (moves, height) -> back -> follow -> back ends.
        PlacementState p = new PlacementState();
        check(!p.drop(), "no anchor, no drop");
        p.follow(10, 64, 20);
        p.raise(2);
        check(p.y == 66 && "+2".equals(p.offsetLabel()), "height offset +2");
        p.follow(11, 70, 21);
        check(p.x == 11 && p.y == 72, "follow keeps the offset on new ground");
        check(p.drop() && p.mode == PlacementState.Mode.ADJUST, "click drops into adjust");
        p.follow(99, 99, 99);
        check(p.x == 11 && p.z == 21, "adjust ignores the cursor");
        p.move(1, -5);
        check(p.x == 12 && p.z == 16, "nudge");
        p.raise(-3);
        check(p.y == 69 && "-1".equals(p.offsetLabel()), "height -1");
        p.snap();
        check(p.y == 70 && "ground".equals(p.offsetLabel()), "snap to ground");
        check(p.back() && p.mode == PlacementState.Mode.FOLLOW, "Esc: back to follow");
        p.move(5, 5);
        check(p.x == 12, "follow ignores nudges");
        check(!p.back(), "second Esc ends placing");
        // W + Q together for 1 s: the camera moves and turns, altitude unchanged.
        DroneCamera d = new DroneCamera(0, 100, 0, 0);
        double[] pivot = d.centreOn(64);
        for (int i = 0; i < 60; i++) d.step(1 / 60.0, 0, 1, -90, pivot, 1, 64, 0);
        check(Math.abs(d.yaw + 90) < 1e-3 && d.altitude == 100 && Math.hypot(d.x, d.z) > 5, "W+Q: moved and turned: " + d.x + "," + d.z + " yaw " + d.yaw);
        double[] c = d.centreOn(64);
        check(Math.abs(c[0] - pivot[0]) < 1e-6 && Math.abs(c[2] - pivot[2]) < 1e-6, "pivot travels with the pan");
        // Projection is the inverse of the ray.
        double[] cam = {3, 90, -4}, ray = CameraMath.ray(30, 60, 70, 1.6, 0.4, -0.25);
        double[] pt = {cam[0] + ray[0] * 50, cam[1] + ray[1] * 50, cam[2] + ray[2] * 50};
        double[] ndc = CameraMath.project(cam, 30, 60, 70, 1.6, pt);
        check(Math.abs(ndc[0] - 0.4) < 1e-9 && Math.abs(ndc[1] + 0.25) < 1e-9, "project(ray) round trip");
    }

    private static void queue() {
        List<QueueOrder.Entry> in = List.of(new QueueOrder.Entry(1, 0), new QueueOrder.Entry(2, 5), new QueueOrder.Entry(3, 5), new QueueOrder.Entry(4, 1));
        List<QueueOrder.Entry> s = QueueOrder.sorted(in);
        check(s.get(0).id() == 2 && s.get(1).id() == 3 && s.get(2).id() == 4 && s.get(3).id() == 1, "sorted: priority desc, then id");
        // Drag the last (id 1) to the top: it must end up with the highest priority, the rest keep their order.
        Map<Integer, Integer> m = QueueOrder.move(s, 3, 0);
        int[] order = applied(s, m);
        check(order[0] == 1 && order[1] == 2 && order[2] == 3 && order[3] == 4, "moved to top: " + java.util.Arrays.toString(order));
        m = QueueOrder.move(s, 0, 2);
        order = applied(s, m);
        check(order[0] == 3 && order[1] == 4 && order[2] == 2 && order[3] == 1, "moved down: " + java.util.Arrays.toString(order));
        check(QueueOrder.move(s, 1, 1).size() <= 4 && QueueOrder.move(s, 9, 0).isEmpty(), "no-op / out of range");
        List<QueueOrder.Entry> already = List.of(new QueueOrder.Entry(7, 2), new QueueOrder.Entry(8, 1));
        check(QueueOrder.move(already, 0, 0).isEmpty(), "unchanged order sends nothing");
    }

    /** @return ids in the order MineColonies would take them after applying the priority changes */
    private static int[] applied(List<QueueOrder.Entry> s, Map<Integer, Integer> changes) {
        List<QueueOrder.Entry> after = s.stream().map(e -> new QueueOrder.Entry(e.id(), changes.getOrDefault(e.id(), e.priority()))).toList();
        return QueueOrder.sorted(after).stream().mapToInt(QueueOrder.Entry::id).toArray();
    }

    private static void colonyGeometry() {
        // Chunks (0,0) and (1,0) claimed: a footprint inside both passes, one reaching chunk (2,0) or (0,-1) fails.
        java.util.Set<Long> claimed = java.util.Set.of(ColonyGeometry.chunk(0, 0), ColonyGeometry.chunk(1, 0));
        check(ColonyGeometry.footprintClaimed(2, 2, 30, 14, claimed), "footprint inside the claims");
        check(!ColonyGeometry.footprintClaimed(2, 2, 33, 14, claimed), "footprint into an unclaimed chunk");
        check(!ColonyGeometry.footprintClaimed(2, -1, 10, 5, claimed), "footprint across a negative chunk edge");
        check(ColonyGeometry.footprintChunks(15, 15, 16, 16).size() == 4, "a 2x2 corner box spans 4 chunks");
        long c = ColonyGeometry.chunk(-3, 7);
        check(ColonyGeometry.chunkX(c) == -3 && ColonyGeometry.chunkZ(c) == 7 && c == ((long) -3 & 0xFFFFFFFFL | 7L << 32), "ChunkPos.asLong packing");
        check(ColonyGeometry.borderEdges(claimed).size() == 6, "two chunks side by side: 6 border edges");
        double[] b = ColonyGeometry.bounds(claimed, 32);
        check(b[0] == -32 && b[1] == -32 && b[2] == 64 && b[3] == 48, "bounds + margin: " + java.util.Arrays.toString(b));
        check(ColonyGeometry.bounds(java.util.Set.of(), 32) == null, "no claims, no bounds");
        // A tower nested in a barracks: the click picks the smaller box; outside both, nothing.
        List<int[]> boxes = List.of(new int[]{0, 60, 0, 20, 80, 20}, new int[]{4, 60, 4, 8, 90, 8}, new int[]{30, 60, 30, 40, 70, 40});
        check(ColonyGeometry.smallestContaining(boxes, 5, 70, 5) == 1, "nested: smallest box wins");
        check(ColonyGeometry.smallestContaining(boxes, 15, 61, 15) == 0, "inside only the big box");
        check(ColonyGeometry.smallestContaining(boxes, 20, 80, 20) == 0, "max corner is inclusive");
        check(ColonyGeometry.smallestContaining(boxes, 25, 65, 25) == -1, "between buildings");
    }

    private static void costs() {
        Map<String, Integer> need = Costs.newMap();
        Costs.add(need, "minecraft:oak_planks", 40);
        Costs.add(need, "minecraft:cobblestone", 64);
        Costs.add(need, "minecraft:oak_planks", 30);
        Costs.add(need, "minecraft:glass", 0);
        Costs.add(need, "minecraft:torch", 4);
        Costs.Summary sum = Costs.summarize(need, Map.of("minecraft:oak_planks", 70, "minecraft:torch", 1), 2);
        check(sum.rows().size() == 2 && sum.more() == 1 && sum.totalItems() == 138, "cap and +more");
        check(sum.rows().get(0).item().equals("minecraft:oak_planks") && sum.rows().get(0).need() == 70
                && sum.rows().get(0).status() == Costs.Have.ENOUGH, "merged, biggest first, enough");
        check(sum.rows().get(1).status() == Costs.Have.NONE, "none carried");
        check(Costs.summarize(need, Map.of("minecraft:torch", 1), 9).rows().get(2).status() == Costs.Have.PARTIAL, "partly carried");
        check(QueueOrder.stageFraction("CLEAR") > 0 && QueueOrder.stageFraction("SPAWN") < 1
                && QueueOrder.stageFraction("DECORATE") > QueueOrder.stageFraction("BUILD_SOLID") && QueueOrder.stageFraction(null) < 0, "stage progress");
        for (var e : QueueIcons.ICONS.entrySet()) {
            check(e.getValue().length == QueueIcons.SIZE, "icon rows: " + e.getKey());
            for (String r : e.getValue()) check(r.length() == QueueIcons.SIZE && r.matches("[.#]+"), "icon row: " + e.getKey());
        }
        for (String k : List.of("build", "upgrade", "repair", "remove", "decoration")) check(QueueIcons.ICONS.containsKey(k), "icon for " + k);
        for (String k : List.of("place", "rotate", "mirror", "up", "down", "cancel")) {
            String[] g = com.volcanicmg.mccitybuilder.planner.ActionIcons.ICONS.get(k);
            check(g != null && g.length == 16, "action icon " + k);
            for (String r : g) check(r.length() == 16 && r.chars().allMatch(ch -> ch == '.' || com.volcanicmg.mccitybuilder.planner.ActionIcons.COLORS.containsKey((char) ch)), "action icon row " + k);
        }
    }

    private static void catalog() {
        check(HutCatalog.category("blockhuttownhall").equals("core") && HutCatalog.category("blockhutbaker").equals("food"), "categories");
        check(HutCatalog.category("blockhutsomethingnew").equals("other"), "unknown hut = other");
        check(HutCatalog.hidden("blockhutfield") && HutCatalog.hidden("blockhutbarrackstower") && !HutCatalog.hidden("blockhutbuilder"), "hidden huts");
        check(HutCatalog.CATEGORIES.contains("other") && HutCatalog.CATEGORIES.size() == 7, "category list");
        // Creative server paste: the hut and matching blocks are left alone, full storage is kept and reported.
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.decide(true, false, true) == com.volcanicmg.mccitybuilder.planner.PasteRules.Action.SKIP, "hut block skipped");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.decide(false, true, true) == com.volcanicmg.mccitybuilder.planner.PasteRules.Action.SKIP, "same block (a full rack) skipped");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.decide(false, false, true) == com.volcanicmg.mccitybuilder.planner.PasteRules.Action.KEEP, "different block holding items kept + reported");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.decide(false, false, false) == com.volcanicmg.mccitybuilder.planner.PasteRules.Action.PLACE, "plain block replaced");
        // Place requests: only level-N blueprints inside the pack.
        for (String bad : List.of("../../world/x1.blueprint", "/etc/x1.blueprint", "C:/x1.blueprint", "scans/x1.blueprint", "a/b/baker2.blueprint"))
            check(com.volcanicmg.mccitybuilder.planner.PasteRules.badPath(bad, 1) != null, "unsafe path rejected: " + bad);
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.badPath("craftsmanship/luxury/baker3.blueprint", 3) == null, "pack path accepted");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.badPath("decorations/misc/well.blueprint", 0) == null
                && com.volcanicmg.mccitybuilder.planner.PasteRules.badPath("decorations/misc/well.txt", 0) != null, "decoration paths (no level)");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.badPath("scans\\x1.blueprint", 1) != null && com.volcanicmg.mccitybuilder.planner.PasteRules.badPath("..\\x1.blueprint", 1) != null, "backslash paths normalised first");
        check("craftsmanship/luxury/baker3.blueprint".equals(HutCatalog.levelPath("craftsmanship/luxury/baker1.blueprint", 3)), "level path");
        check(HutCatalog.levelPath("decorations/well.blueprint", 2) == null && HutCatalog.levelPath(null, 2) == null, "no level digit");
    }

    /** Plans: text round trip (damaged lines skipped), moving, and conflicts. */
    private static void plans() {
        var a = new com.volcanicmg.mccitybuilder.planner.Plan.Entry("minecolonies:blockhutbaker", "birch", "craftsmanship/luxury/baker2.blueprint", 2,
                10, 64, 10, 1, 5, 64, 5, 14, 72, 15);
        var b = new com.volcanicmg.mccitybuilder.planner.Plan.Entry(null, "birch", "decorations/misc/well.blueprint", 0, 40, 64, 40, 0, 38, 64, 38, 42, 68, 42);
        String text = com.volcanicmg.mccitybuilder.planner.Plan.write(List.of(a, b));
        var back = com.volcanicmg.mccitybuilder.planner.Plan.read(text + "garbage\tline\n");
        check(back.equals(List.of(a, b)), "plan round trip: " + back);
        check(com.volcanicmg.mccitybuilder.planner.Plan.read("not a plan\n" + text.substring(text.indexOf('\n') + 1)).isEmpty(), "wrong header = empty plan");
        check(a.moved(1, 0, -2).minX() == 6 && a.moved(1, 0, -2).z() == 8, "moving moves the box");
        java.util.Set<Long> claims = new java.util.HashSet<>();
        for (int cx = 0; cx <= 2; cx++) for (int cz = 0; cz <= 2; cz++) claims.add(ColonyGeometry.chunk(cx, cz));
        var c = new com.volcanicmg.mccitybuilder.planner.Plan.Entry(null, "birch", "x.blueprint", 0, 12, 64, 12, 0, 12, 64, 12, 16, 66, 16);
        var out = new com.volcanicmg.mccitybuilder.planner.Plan.Entry(null, "birch", "x.blueprint", 0, 60, 64, 60, 0, 58, 64, 58, 62, 66, 62);
        var cs = com.volcanicmg.mccitybuilder.planner.Plan.conflicts(List.of(a, b, c, out), List.of(new int[]{38, 60, 38, 39, 70, 39}), claims);
        check(cs.equals(List.of(com.volcanicmg.mccitybuilder.planner.Plan.Conflict.OVERLAP_PLAN, com.volcanicmg.mccitybuilder.planner.Plan.Conflict.OVERLAP_BUILDING,
                com.volcanicmg.mccitybuilder.planner.Plan.Conflict.OVERLAP_PLAN, com.volcanicmg.mccitybuilder.planner.Plan.Conflict.OUTSIDE)), "plan conflicts: " + cs);
    }

    /** Roads: centreline continuity, smoothed steps of at most 1 never below the ground, width and the column cap. */
    private static void roads() {
        var line = com.volcanicmg.mccitybuilder.planner.Roads.centreline(List.of(new int[]{0, 0}, new int[]{10, 3}, new int[]{10, -5}));
        boolean connected = true;
        for (int i = 1; i < line.size(); i++)
            connected &= Math.max(Math.abs(line.get(i)[0] - line.get(i - 1)[0]), Math.abs(line.get(i)[1] - line.get(i - 1)[1])) == 1;
        check(connected && line.get(0)[0] == 0 && line.get(line.size() - 1)[1] == -5, "centreline is continuous end to end");
        int[] ground = {64, 64, 70, 64, 63, 63, 80, 80};
        int[] h = com.volcanicmg.mccitybuilder.planner.Roads.smooth(ground, 1);
        boolean ok = true;
        for (int i = 0; i < h.length; i++) ok &= h[i] >= ground[i] && (i == 0 || Math.abs(h[i] - h[i - 1]) <= 1);
        check(ok, "road steps <= 1 and never below the ground: " + java.util.Arrays.toString(h));
        var cols = com.volcanicmg.mccitybuilder.planner.Roads.columns(List.of(new int[]{0, 0}, new int[]{20, 0}), 3, (x, z) -> 64);
        check(cols.size() == 21 * 3 + 2 * 3 && cols.stream().allMatch(c -> c.y() == 64 && Math.abs(c.z()) <= 1), "3-wide straight road (round ends): " + cols.size());
        var big = com.volcanicmg.mccitybuilder.planner.Roads.columns(List.of(new int[]{0, 0}, new int[]{5000, 0}), 7, (x, z) -> 64);
        check(big.size() == com.volcanicmg.mccitybuilder.planner.Roads.MAX_COLUMNS, "column cap");
    }

    private static void overlays() {
        check(com.volcanicmg.mccitybuilder.planner.Overlays.next("none").equals("builders") && com.volcanicmg.mccitybuilder.planner.Overlays.next("workers").equals("none"), "overlay cycle");
        check(com.volcanicmg.mccitybuilder.planner.Overlays.staffing(0, 0) == com.volcanicmg.mccitybuilder.planner.Overlays.Staff.NO_JOBS
                && com.volcanicmg.mccitybuilder.planner.Overlays.staffing(0, 2) == com.volcanicmg.mccitybuilder.planner.Overlays.Staff.NONE
                && com.volcanicmg.mccitybuilder.planner.Overlays.staffing(1, 2) == com.volcanicmg.mccitybuilder.planner.Overlays.Staff.UNDER
                && com.volcanicmg.mccitybuilder.planner.Overlays.staffing(2, 2) == com.volcanicmg.mccitybuilder.planner.Overlays.Staff.FULL, "staffing");
        check(com.volcanicmg.mccitybuilder.planner.Overlays.jobColor("com.minecolonies.job.builder") == com.volcanicmg.mccitybuilder.planner.Overlays.jobColor("com.minecolonies.job.builder")
                && com.volcanicmg.mccitybuilder.planner.Overlays.jobColor("") == 0xFFA0A0A0, "job colours stable");
        var w = com.volcanicmg.mccitybuilder.planner.Overlays.waiting(List.of(new com.volcanicmg.mccitybuilder.planner.Overlays.Need("Glass", 4),
                new com.volcanicmg.mccitybuilder.planner.Overlays.Need("Planks", 0), new com.volcanicmg.mccitybuilder.planner.Overlays.Need("Stone", 30),
                new com.volcanicmg.mccitybuilder.planner.Overlays.Need("Torch", 4)), 2);
        check(w.size() == 2 && w.get(0).name().equals("Stone") && w.get(1).name().equals("Glass"), "waiting list: missing only, most first, capped: " + w);
    }

    private static void hintsAndLang() throws Exception {
        String lang = Files.readString(Path.of("src/main/resources/assets/mccitybuilder/lang/en_us.json"));
        for (String id : List.of("view", "browse", "placing", "adjusting", "selected"))
            check(Hints.SECTIONS.containsKey(id) && Hints.SECTIONS.get(id).size() <= Hints.MAX_ROWS, "hints section " + id);
        for (String k : Hints.langKeys()) check(lang.contains("\"" + k + "\":"), "lang key: " + k);
        for (String c : HutCatalog.CATEGORIES) check(lang.contains("\"mccitybuilder.category." + c + "\":"), "category lang: " + c);
        java.util.function.Function<String, String> tr = k -> {
            int a = lang.indexOf("\"" + k + "\":");
            if (a < 0) return "J";
            int b = lang.indexOf('"', lang.indexOf(':', a) + 1) + 1;
            return lang.substring(b, lang.indexOf('"', b));
        };
        for (String id : List.of("browse", "placing", "adjusting", "selected")) {
            Hints.Laid l = Hints.layout(List.of(id, "view"), tr, s -> s.length() * 6);
            for (Hints.Item it : l.items())
                check(it.x() >= 0 && it.x() + it.w() <= Hints.WIDTH - 2 * Hints.PAD, "hint fits: " + id + " '" + it.text() + "'");
        }
        // Every "mccitybuilder.*" literal in the client code has an English string.
        Pattern lit = Pattern.compile("\"(mccitybuilder\\.[a-z0-9_.]+)\"");
        int n = 0;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = lit.matcher(Files.readString(f));
                while (m.find()) {
                    String k = m.group(1);
                    if (k.endsWith(".")) continue;
                    check(lang.contains("\"" + k + "\":"), "lang key used in " + f.getFileName() + ": " + k);
                    n++;
                }
            }
        }
        check(n > 20, "lang literals found: " + n);
    }

    private static boolean near(double[] v, double x, double y, double z) {
        return Math.abs(v[0] - x) < 1e-9 && Math.abs(v[1] - y) < 1e-9 && Math.abs(v[2] - z) < 1e-9;
    }

    private static boolean near(double[] a, double[] b) {
        return near(a, b[0], b[1], b[2]);
    }

    private static boolean near2(double[] v, double x, double z) {
        return Math.abs(v[0] - x) < 1e-9 && Math.abs(v[1] - z) < 1e-9;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
