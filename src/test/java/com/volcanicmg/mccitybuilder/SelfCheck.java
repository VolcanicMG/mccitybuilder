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
import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
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
        lines();
        groundOffsets();
        snapshots();
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
        // Turning in place beside a 30-block tower (outside the footprint): the view sweeps it, the height must not move.
        Terrain tower = new Terrain((x, z) -> Math.abs(x) <= 2 && Math.abs(z - 7) <= 2 ? 94 : 64, (x, z) -> 64);
        var tr = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 30, 0, 0));
        for (int i = 0; i < 120; i++) tr.frame(tower, dt, 0, 0, 0, null, 1.2, Double.NaN);
        double y0 = tr.drone.y(), maxMove = 0;
        for (int i = 0; i < 480; i++) {
            tr.frame(tower, dt, 0, 0, 90, null, 1.2, Double.NaN);
            maxMove = Math.max(maxMove, Math.abs(tr.drone.y() - y0));
        }
        check(maxMove < 1e-9, "turning in place beside a tower moves the height: " + maxMove);
        // Panning toward it still lifts smoothly and never clips.
        var pr = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 30, -30, 0));
        double ppy = pr.drone.y(), peak = 0;
        for (int i = 0; i < 300; i++) {
            pr.frame(tower, dt, 0, 1, 0, null, 1.2, 64);
            aboveSurface(pr, tower, "pan to tower");
            ppy = capped(pr, ppy, "pan to tower");
            peak = Math.max(peak, pr.drone.y());
        }
        check(peak >= 94 + DroneCamera.CLEARANCE - 1e-6, "panning over the tower lifted the camera: " + peak);
        // V (straight down) beside a tall building: the target jumps over it, the drawn camera glides up, never snaps.
        Terrain tall = new Terrain((x, z) -> Math.abs(x) <= 2 && z >= -26 && z <= -20 ? 104 : 64, (x, z) -> 64);
        var vr = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 30, -40, 0));
        for (int i = 0; i < 120; i++) vr.frame(tall, dt, 0, 0, 0, null, 1.2, Double.NaN);
        vr.drone.turn(vr.drone.centreOn(64), 0, DroneCamera.MAX_PITCH - vr.drone.pitch);
        double vy = vr.drone.y();
        for (int i = 0; i < 300; i++) {
            vr.frame(tall, dt, 0, 0, 0, null, 1.2, Double.NaN);
            aboveSurface(vr, tall, "V beside a tower");
            vy = capped(vr, vy, "V beside a tower");
        }
        // Colony box out of the loaded circle (a teleport): close is signalled; the clamps settle, never flip-flop.
        DroneCamera far = new DroneCamera(0, 100, 0, 0);
        far.circleX = 0;
        far.circleZ = 0;
        far.circleR = 160;
        far.box = new double[]{1000, 1000, 1100, 1100};
        check(far.outOfBounds(), "disjoint bounds signal a close");
        double fx = Double.NaN, fz = Double.NaN;
        for (int i = 0; i < 60; i++) {
            far.update(1 / 60.0, 1, 1, 1, 64, 64);
            if (i > 0) check(Math.abs(far.x - fx) < 1e-9 && Math.abs(far.z - fz) < 1e-9, "disjoint bounds: camera oscillates");
            fx = far.x;
            fz = far.z;
        }
        far.box = new double[]{-50, -50, 50, 50};
        check(!far.outOfBounds(), "overlapping bounds: stays open");
        far.box = new double[]{155, -10, 400, 10};
        check(far.outOfBounds(), "a sliver of overlap closes too");
        check("outside".equals(ColonyGeometry.openCheck(0, true, false, false, false)) && "outside".equals(ColonyGeometry.openCheck(-1, true, false, false, false))
                && "loading".equals(ColonyGeometry.openCheck(-1, false, false, false, false)) && "loading".equals(ColonyGeometry.openCheck(3, true, false, false, false))
                && "not_member".equals(ColonyGeometry.openCheck(3, true, true, false, true)) && "no_permission".equals(ColonyGeometry.openCheck(3, true, true, true, false))
                && ColonyGeometry.openCheck(3, true, true, true, true) == null, "planner opens only inside your colony");
        check(ColonyGeometry.leftColony(0, 3, false) && ColonyGeometry.leftColony(4, 3, false) && ColonyGeometry.leftColony(-1, 3, true)
                && !ColonyGeometry.leftColony(-1, 3, false) && !ColonyGeometry.leftColony(3, 3, true), "closes on leaving the colony, not on missing claim data");
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
        // A hut placed on tall grass: the pick stops on the plant (y 75), the ground is the soil under it.
        check(cols.settle(1, 75, 0) == 69 && cols.settle(1, 69, 0) == 69, "pick settles past plants onto the ground");
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
        // A decoration's box at its anchor from blueprint offsets; a click inside it beats the building around it.
        int[] deco = ColonyGeometry.placed(new int[]{-1, 0, -2, 1, 3, 0}, 10, 64, 10);
        check(java.util.Arrays.equals(deco, new int[]{9, 64, 8, 11, 67, 10}), "decoration box placed: " + java.util.Arrays.toString(deco));
        List<int[]> withDeco = List.of(boxes.get(0), deco);
        check(ColonyGeometry.smallestContaining(withDeco, 10, 65, 9) == 1, "decoration inside a building wins");
        check(ColonyGeometry.smallestContaining(withDeco, 12, 65, 9) == 0, "beside the decoration: the building");
        // Built records: keys parse back; paths normalise one way.
        var rec = com.volcanicmg.mccitybuilder.planner.PasteRules.parseKey(com.volcanicmg.mccitybuilder.planner.PasteRules.recordKey(7, "Medieval", "decorations\\well.blueprint", -3, 70, 12, 5));
        check(rec != null && rec.colony() == 7 && rec.pack().equals("Medieval") && rec.path().equals("decorations/well.blueprint") && rec.x() == -3 && rec.y() == 70
                && rec.z() == 12 && rec.rot() == 5, "record key round trip");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.parseKey("1|p|a.blueprint|1,2|0") == null && com.volcanicmg.mccitybuilder.planner.PasteRules.parseKey("x|p|a|1,2,3|0") == null,
                "bad record keys");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.cleanPath("/decorations\\well.blueprint").equals("decorations/well.blueprint"), "path cleaned");
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

    /** Road / wall tools: snapping, chaining and branching, wall joints, height, gates, battlements, segments. */
    private static void lines() throws Exception {
        undoRestore();
        List<int[][]> segs = new java.util.ArrayList<>();
        segs.add(new int[][]{{0, 0}, {20, 0}});
        // Ends snap to an existing endpoint near the cursor; otherwise to 45° steps from the previous direction.
        int[] e = com.volcanicmg.mccitybuilder.planner.LineSnap.snapEnd(new int[]{5, 10}, 20.6, 1.2, null, false, segs);
        check(e[0] == 20 && e[1] == 0, "end snaps to an endpoint: " + e[0] + "," + e[1]);
        e = com.volcanicmg.mccitybuilder.planner.LineSnap.snapEnd(new int[]{0, 10}, 10.5, 13.2, null, false, List.of());
        check(e[1] == 10 && e[0] == 10, "near-horizontal snaps to 0°: " + e[0] + "," + e[1]);
        e = com.volcanicmg.mccitybuilder.planner.LineSnap.snapEnd(new int[]{0, 10}, 7.9, 18.6, null, false, List.of());
        check(e[0] - 0 == e[1] - 10 && e[0] > 0, "near-diagonal snaps to a true 45° cell: " + e[0] + "," + e[1]);
        e = com.volcanicmg.mccitybuilder.planner.LineSnap.snapEnd(new int[]{0, 10}, 7.9, 18.6, null, true, List.of());
        check(e[0] == 7 && e[1] == 18, "Shift = free angle");
        check(com.volcanicmg.mccitybuilder.planner.LineSnap.length(new int[]{0, 0}, new int[]{12, 5}) == 12, "length in blocks");
        // A chain starts at an endpoint, or branches from the middle of a segment.
        int[] st = com.volcanicmg.mccitybuilder.planner.LineSnap.snapStart(9.4, 1.6, segs);
        check(st[1] == 0 && st[0] == 9, "start snaps onto a segment (branch): " + st[0] + "," + st[1]);
        // Pieces snap to straight runs only (90°).
        e = com.volcanicmg.mccitybuilder.planner.LineSnap.snapEnd(new int[]{0, 10}, 7.9, 18.6, null, false, List.of(), 90);
        check(e[0] == 0 || e[1] == 10, "90° snap for pieces: " + e[0] + "," + e[1]);
        pieces();
        joints();
        buildAll();
        towerFaces();
        heightsAndRecords();
        snapping();
        buildQueue();
        joining();
        editing();
        continuity();
        jointChecks();
        cornerTurns();
        editRules();
        alignments();
        // Saved lines round trip; hostile rows (huge segments, old vanilla rows) are skipped.
        var line = new com.volcanicmg.mccitybuilder.planner.Plan.Line("wall", 1, 2, 30, 2, "medievaloak", "wall:walls/segment", 3, -1, true, true);
        String text = com.volcanicmg.mccitybuilder.planner.Plan.write(List.of(), List.of(line));
        check(com.volcanicmg.mccitybuilder.planner.Plan.readLines(text).equals(List.of(line)), "plan lines round trip");
        check(com.volcanicmg.mccitybuilder.planner.Plan.readLines(text + "L2\twall\t0\t0\t99999\t0\tp\tf\t1\t-1\tfalse\tfalse\nL\troad\t0\t0\t5\t0\t3\t0\tfalse\tfalse\tfalse\tfalse\tminecraft:gravel\n")
                .size() == 1, "huge and old rows skipped");
    }

    /** Style-pack pieces: classification of real names, levels vs variants, families, tiling math and profile alignment. */
    private static void pieces() {
        String[][] names = {{"wallstraight", "STRAIGHT"}, {"segment", "STRAIGHT"}, {"wallstraightlong", "STRAIGHT"}, {"loooong", "STRAIGHT"},
                {"simplepatheven", "STRAIGHT"}, {"longpath", "STRAIGHT"}, {"wallcorner", "CORNER"}, {"walloutercornertower", "CORNER"},
                {"wallinnercornertower", "INNER"}, {"innercorner", "INNER"}, {"outer", "CORNER"}, {"tcorner", "TEE"}, {"simpletjunctionodd", "TEE"},
                {"intersection", "CROSS"}, {"wallgate", "GATE"}, {"gatelarge", "GATE"}, {"tower", "TOWER"}, {"wallstraighttower", "TOWER"},
                {"wallstairs", "SLOPE"}, {"climb_left", "SLOPE"}, {"endroad", "END"}, {"simplelamppost", "OTHER"}};
        for (String[] n : names)
            check(com.volcanicmg.mccitybuilder.planner.PieceSets.role(n[0]).name().equals(n[1]), "role of " + n[0] + ": " + com.volcanicmg.mccitybuilder.planner.PieceSets.role(n[0]));
        check(com.volcanicmg.mccitybuilder.planner.PieceSets.split("road1730000000123.blueprint")[1].equals("0")
                && com.volcanicmg.mccitybuilder.planner.PieceSets.split("segment3.blueprint")[1].equals("3"), "long digit runs are not numbers");
        check(com.volcanicmg.mccitybuilder.planner.PieceSets.tool("walls/segment/segment1.blueprint").equals("wall")
                && com.volcanicmg.mccitybuilder.planner.PieceSets.tool("infrastructure/roads/simple/simplepatheven.blueprint").equals("road")
                && com.volcanicmg.mccitybuilder.planner.PieceSets.tool("decorations/misc/well.blueprint") == null, "tool by folder");
        // medievaloak-like: segment1..5 differ in height = levels; fortress-like wall1..4 identical = variants;
        // original-like cobble_ / fancy_ in one folder = two families.
        List<com.volcanicmg.mccitybuilder.planner.PieceSets.Piece> ps = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) ps.add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("walls/segment/segment" + i + ".blueprint", "segment", i, 6, 11 + i, 9));
        ps.add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("walls/corner/corner1.blueprint", "corner", 1, 7, 13, 7));
        for (int i = 1; i <= 4; i++) ps.add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("walls/stone/wall" + i + ".blueprint", "wall", i, 5, 14, 8));
        ps.add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("walls/cobble_segment_large.blueprint", "cobble_segment_large", 0, 12, 8, 5));
        ps.add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("walls/fancy_segment_large.blueprint", "fancy_segment_large", 0, 12, 9, 5));
        var fam = com.volcanicmg.mccitybuilder.planner.PieceSets.classify(ps);
        // walls/segment and walls/corner fold into one "walls" family (role-named sub-folders).
        var seg = fam.get("wall:walls");
        check(seg != null && seg.straights().get(0).levels() && seg.straights().get(0).pick(3, 0).number() == 3 && !seg.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER).isEmpty(),
                "segment1..5 are levels, corner folded in: " + fam.keySet());
        var wall = fam.get("wall:walls/stone");
        check(wall != null && !wall.straights().get(0).levels() && wall.straights().get(0).pieces().size() == 4, "wall1..4 are variants");
        check(fam.containsKey("wall:walls/cobble") && fam.containsKey("wall:walls/fancy"), "prefix families in a shared folder: " + fam.keySet());
        check(!fam.containsKey("wall:walls/corner") && com.volcanicmg.mccitybuilder.planner.PieceSets.prefix("long_base").isEmpty()
                && com.volcanicmg.mccitybuilder.planner.PieceSets.prefix("cobble_gate").equals("cobble"), "role folders / role prefixes don't split families");
        // Tiling: least left over, then fewest pieces; boxes end to end, centred across the line.
        check(com.volcanicmg.mccitybuilder.planner.PieceLayout.fill(10, new int[]{8, 5}).equals(List.of(5, 5)), "10 = 5 + 5, not 8 + 2 left");
        check(com.volcanicmg.mccitybuilder.planner.PieceLayout.fill(19, new int[]{8, 5}).equals(List.of(8, 5, 5)), "19 = 8 + 5 + 5 + 1 left");
        check(com.volcanicmg.mccitybuilder.planner.PieceLayout.fill(3, new int[]{8, 5}).isEmpty(), "too short for any piece");
        var bx = com.volcanicmg.mccitybuilder.planner.PieceLayout.boxes(new int[]{0, 0}, 0, 2, List.of(6, 6), 5, 0);
        check(bx.get(0)[0] == 2 && bx.get(0)[2] == 7 && bx.get(1)[0] == 8 && bx.get(0)[1] == -2 && bx.get(0)[3] == 2, "boxes east, centred");
        var bw = com.volcanicmg.mccitybuilder.planner.PieceLayout.boxes(new int[]{0, 0}, 3, 0, List.of(4), 4, 0);
        check(bw.get(0)[1] == -3 && bw.get(0)[3] == 0 && bw.get(0)[0] == -1 && bw.get(0)[2] == 2, "boxes north, even width");
        check(com.volcanicmg.mccitybuilder.planner.PieceLayout.turns(12, 6, 1) == 1 && com.volcanicmg.mccitybuilder.planner.PieceLayout.turns(5, 8, 1) == 0
                && com.volcanicmg.mccitybuilder.planner.PieceLayout.turns(5, 8, 0) == 3, "quarter turns to the run direction");
        check(java.util.Arrays.equals(com.volcanicmg.mccitybuilder.planner.PieceLayout.centred(new int[]{10, 10}, 7, 7), new int[]{7, 7, 13, 13}), "joint centred");
        check(com.volcanicmg.mccitybuilder.planner.PieceLayout.dir(new int[]{0, 0}, new int[]{0, -9}) == 3, "direction north");
    }

    /**
     * Joints: L-turns both ways, a T, a cross and a straight chain, with a joint piece (as wide as the road, and a
     * wall tower wider than the wall) and as butt joints. No two footprints overlap, the joint square and every
     * run's band are covered exactly once (no gap between a run and its joint), and leftover cells go to the free end.
     */
    private static void joints() {
        int[] c = {0, 0};
        int[][][][] shapes = {
                {{{-11, 0}, {0, 0}}, {{0, 0}, {0, 11}}},
                {{{-11, 0}, {0, 0}}, {{0, 0}, {0, -11}}},
                {{{0, 11}, {0, 0}}, {{0, 0}, {11, 0}}},
                {{{-11, 0}, {0, 0}}, {{0, 0}, {11, 0}}, {{0, 0}, {0, 11}}},
                {{{-11, 0}, {0, 0}}, {{0, 0}, {11, 0}}, {{0, 0}, {0, 11}}, {{0, -11}, {0, 0}}},
                {{{-11, 0}, {0, 0}}, {{0, 0}, {11, 0}}}};
        String[] names = {"L right", "L left", "L from the south", "T", "cross", "straight chain"};
        int[][] cases = {{4, 0}, {4, 4}, {3, 0}, {6, 0}, {6, 11}, {5, 5}};
        for (int s = 0; s < shapes.length; s++)
            for (int[] cs : cases) {
                int w = cs[0], jw = cs[1];
                if (jw > 0 && s == shapes.length - 1) continue;
                String what = names[s] + " width " + w + (jw > 0 ? " joint " + jw : " butt");
                int[][][] ls = shapes[s];
                int[] jb = jw > 0 ? PieceLayout.centred(c, jw, jw) : null;
                int[] dirs = new int[ls.length], widths = new int[ls.length];
                for (int k = 0; k < ls.length; k++) {
                    int dir = PieceLayout.dir(ls[k][0], ls[k][1]);
                    dirs[k] = java.util.Arrays.equals(ls[k][0], c) ? dir : (dir + 2) % 4;
                    widths[k] = w;
                }
                int[] sq = PieceLayout.square(c, dirs, widths);
                int owner = PieceLayout.owner(dirs);
                java.util.Map<Long, Integer> cover = new java.util.HashMap<>();
                java.util.Set<Long> want = new java.util.HashSet<>();
                if (jb != null) for (int x = jb[0]; x <= jb[2]; x++) for (int z = jb[1]; z <= jb[3]; z++) {
                    cover.merge((long) x << 32 ^ (z & 0xFFFFFFFFL), 1, Integer::sum);
                    want.add((long) x << 32 ^ (z & 0xFFFFFFFFL));
                }
                for (int k = 0; k < ls.length; k++) {
                    int[] a = ls[k][0], b = ls[k][1];
                    int dir = PieceLayout.dir(a, b), len = LineSnap.length(a, b) + 1;
                    boolean atStart = java.util.Arrays.equals(a, c);
                    int off = jb != null ? PieceLayout.reach(c, dirs[k], jb) : PieceLayout.offset(c, dirs[k], sq, k == owner);
                    int off0 = atStart ? off : 0, off1 = atStart ? 0 : off;
                    List<Integer> fill = PieceLayout.fill(len - off0 - off1, new int[]{1});
                    for (int[] bx : PieceLayout.boxes(a, dir, off0, fill, w, 0))
                        for (int x = bx[0]; x <= bx[2]; x++) for (int z = bx[1]; z <= bx[3]; z++) cover.merge((long) x << 32 ^ (z & 0xFFFFFFFFL), 1, Integer::sum);
                    // The band the line should cover: its full width, from the joint cell to its far end.
                    for (int[] bx : PieceLayout.boxes(a, dir, 0, List.of(len), w, 0))
                        for (int x = bx[0]; x <= bx[2]; x++) for (int z = bx[1]; z <= bx[3]; z++) want.add((long) x << 32 ^ (z & 0xFFFFFFFFL));
                }
                check(cover.values().stream().allMatch(v -> v == 1), what + ": footprints overlap");
                check(cover.keySet().containsAll(want), what + ": gap between a run and its joint");
                if (jb == null) for (int x = sq[0]; x <= sq[2]; x++) for (int z = sq[1]; z <= sq[3]; z++)
                    check(cover.getOrDefault((long) x << 32 ^ (z & 0xFFFFFFFFL), 0) == 1, what + ": joint square not covered once at " + x + "," + z);
            }
        // A T's bar runs through; the stem stops at it.
        check(PieceLayout.owner(new int[]{1, 2, 0}) == 1, "T: the bar owns the butt joint");
        // Leftover cells go to the free end: a run into a joint starts after them, a run out of one right at it.
        check(PieceLayout.runStart(0, 2, true, false) == 2 && PieceLayout.runStart(3, 2, false, true) == 3
                && PieceLayout.runStart(3, 2, false, false) == 3, "leftover at the free end");
        // Too short: the joint / cap offsets plus the shortest straight.
        check(PieceLayout.need(3, 2, new int[]{8, 5}) == 10 && !LineSnap.canDrop(9, 10) && LineSnap.canDrop(10, 10), "too short = below need");
        check(PieceLayout.need(0, 0, new int[]{}) == Integer.MAX_VALUE && !LineSnap.canDrop(500, Integer.MAX_VALUE), "nothing fits: never droppable");
        check(PieceLayout.need(-2, 0, new int[]{4}) == 2, "a butt owner reaching back needs less");
        // The live segment is laid out again only when its key changes.
        int[] k1 = {0, 0, 5, 0, 1, 7, 42};
        check(LineSnap.relayout(null, k1) && !LineSnap.relayout(k1, k1.clone()), "same key: no relayout");
        check(LineSnap.relayout(k1, new int[]{0, 0, 6, 0, 1, 7, 42}) && LineSnap.relayout(k1, new int[]{0, 0, 5, 0, 1, 8, 42})
                && LineSnap.relayout(k1, new int[]{0, 0, 5, 0, 1, 7, 43}), "end / plan / options change: relayout");
    }

    /** Build all: what is sent and what stays, with its reason, for every item kind, creative and survival. */
    private static void buildAll() {
        check(Plan.decide(false, true, false, true, false) == Plan.Build.SEND, "creative hut without the server part: Structurize paste");
        check(Plan.decide(false, true, true, false, false) == Plan.Build.SEND, "creative decoration");
        check(Plan.decide(true, true, true, true, true) == Plan.Build.OUTSIDE, "outside stays");
        check(Plan.decide(false, false, true, true, false) == Plan.Build.NO_ITEM, "survival hut without its item");
        check(Plan.decide(false, false, true, true, true) == Plan.Build.SEND, "survival hut with its item");
        check(Plan.decide(false, false, false, false, false) == Plan.Build.DECO_WINDOW, "survival decoration without the server part");
        check(Plan.decide(false, false, true, false, false) == Plan.Build.SEND, "survival decoration: work order");
        check(Plan.decideLine(true, false, false, false, true) == Plan.Build.SEND, "segment sent");
        check(Plan.decideLine(false, false, false, false, true) == Plan.Build.NEEDS_SERVER, "segment without the server part");
        check(Plan.decideLine(true, false, true, false, true) == Plan.Build.NO_PIECES && Plan.decideLine(true, true, false, false, true) == Plan.Build.OUTSIDE
                && Plan.decideLine(true, false, false, true, true) == Plan.Build.IN_FLIGHT, "segment kept reasons");
        // Blueprints still loading: pieces could be missing or a joint a butt joint, so it waits (even with pieces).
        check(Plan.decideLine(true, false, false, false, false) == Plan.Build.LOADING && Plan.decideLine(true, false, true, false, false) == Plan.Build.LOADING,
                "incomplete layout is never sent");
        check(Plan.Build.values().length == 8, "reasons");
    }

    /**
     * Real footprints (medievaloak level 5 wall segment 6 x 9 and its 7 x 7 tower, whose outer ring is only a parapet
     * overhang): for every face and turn of the tower and both ways round of the segment, the run starts exactly at
     * the tower body's face (its overhang skipped) and its centreline meets the face's centre.
     */
    private static void towerFaces() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) {
            System.out.println("CityBuilder self-check: " + PackPieces.JAR + " missing, tower face check skipped");
            return;
        }
        PieceLayout.Grid tower = PackPieces.grid("medievaloak/walls/tower/tower5.blueprint"), seg = PackPieces.grid("medievaloak/walls/segment/segment5.blueprint");
        int[] c = {0, 0};
        java.util.Set<Integer> gaps = new java.util.TreeSet<>();
        for (int k = 0; k < 4; k++) {
            PieceLayout.Grid t = tower.turned(k);
            int[] jb = PieceLayout.centred(c, t.sx(), t.sz());
            for (int d = 0; d < 4; d++)
                for (int runDir : new int[]{d, (d + 2) % 4}) {
                    PieceLayout.Grid sg = seg.turned(PieceLayout.turns(seg.sx(), seg.sz(), runDir));
                    int width = Math.min(seg.sx(), seg.sz()), run = Math.max(seg.sx(), seg.sz());
                    int[] at = PieceLayout.attach(c, d, jb, t, sg, width, run, Math.max(3, width));
                    String what = "tower turn " + k + " face " + d + " run " + runDir;
                    int ti = t.inset(d), si = sg.inset((d + 2) % 4);
                    check(ti == 1 && si == 0, what + ": insets " + ti + "," + si + " (tower ring is overhang)");
                    // The run never clears or replaces a block of the tower (either build order), and stops at the
                    // body face when that is safe, else at the box edge.
                    int[] rb = PieceLayout.boxes(c, d, at[0], List.of(run), width, at[1]).get(0);
                    check(!PieceLayout.destructive(t, jb[0], -t.ground(), jb[1], sg, rb[0], -sg.ground(), rb[1]), what + ": destructive overlap");
                    int bodyFace = PieceLayout.reach(c, d, jb) - 1 - ti, runFirst = at[0] + si;
                    check(runFirst == bodyFace + 1 || at[0] == PieceLayout.reach(c, d, jb), what + ": run starts at " + runFirst + ", body face at " + bodyFace);
                    gaps.add(runFirst - bodyFace - 1);
                    // Centrelines: the run's solid face, shifted, centred on the tower's face.
                    int ref = d % 2 == 0 ? c[1] : c[0];
                    int[] sbox = PieceLayout.boxes(c, d, at[0], List.of(run), width, 0).get(0);
                    var a = t.profile(d, ti, (d % 2 == 0 ? jb[1] : jb[0]) - ref, false);
                    var b = sg.profile((d + 2) % 4, si, (d % 2 == 0 ? sbox[1] : sbox[0]) - ref, false);
                    double ca = a.stream().mapToLong(v -> v & 0xFFFF).average().orElse(0), cb = b.stream().mapToLong(v -> v & 0xFFFF).average().orElse(0) + at[1];
                    check(Math.abs(ca - cb) <= 0.75, what + ": run centre " + cb + " vs tower face centre " + ca + " (shift " + at[1] + ")");
                }
        }
        System.out.println("CityBuilder self-check: medievaloak tower5 / segment5 gaps at the tower body: " + gaps);
        bundledJoints();
        // Grid turns match Structurize's clockwise turn: four turns are the identity, one swaps the sides.
        check(java.util.Arrays.equals(seg.turned(4).ids(), seg.ids()) && seg.turned(1).sx() == seg.sz(), "grid turns");
    }

    /**
     * Every bundled pack's road / wall families: each joint piece the layout uses (corner, T, cross, tower), every turn
     * and face, the family's main straight both ways round. The run never clears or replaces a block of the joint in
     * either build order; prints which families meet the joint body exactly and which keep a gap.
     */
    private static void bundledJoints() throws Exception {
        java.util.Map<String, List<com.volcanicmg.mccitybuilder.planner.PieceSets.Piece>> byPack = new java.util.TreeMap<>();
        java.util.Map<String, PieceLayout.Grid> grids = new java.util.HashMap<>();
        for (String full : PackPieces.pieces()) {
            String pack = full.substring(0, full.indexOf('/')), path = full.substring(pack.length() + 1);
            PieceLayout.Grid g = PackPieces.grid(full);
            grids.put(full, g);
            String[] sn = com.volcanicmg.mccitybuilder.planner.PieceSets.split(path.substring(path.lastIndexOf('/') + 1));
            byPack.computeIfAbsent(pack, k -> new java.util.ArrayList<>()).add(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece(path, sn[0], Integer.parseInt(sn[1]), g.sx(), g.sy(), g.sz()));
        }
        List<String> exact = new java.util.ArrayList<>(), gapped = new java.util.ArrayList<>(), tees = new java.util.ArrayList<>();
        int checked = 0;
        for (var en : byPack.entrySet())
            for (var fam : com.volcanicmg.mccitybuilder.planner.PieceSets.classify(en.getValue()).values()) {
                if (fam.straights().isEmpty()) continue;
                var st = fam.straights().get(0).first();
                PieceLayout.Grid sgrid = grids.get(en.getKey() + "/" + st.path());
                int width = st.across(), run = st.run(), worst = 0;
                String worstOf = "";
                // The joint roles the layout uses (inner corners are browse-only).
                for (var role : List.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TEE, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CROSS,
                        com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TOWER))
                    for (var grp : fam.of(role)) {
                        PieceLayout.Grid jg = grids.get(en.getKey() + "/" + grp.first().path());
                        int[] c = {0, 0};
                        for (int d = 0; d < 4; d++)
                            for (int runDir : new int[]{d, (d + 2) % 4}) {
                                PieceLayout.Grid sg = sgrid.turned(PieceLayout.turns(sgrid.sx(), sgrid.sz(), runDir));
                                // Every turn is checked for safety; the gap counts for the turn the layout picks for this face.
                                int best = 0;
                                double bestScore = -1;
                                for (int k = 0; k < 4; k++) {
                                    PieceLayout.Grid t = jg.turned(k);
                                    int[] jb = PieceLayout.centred(c, t.sx(), t.sz());
                                    int[] at = PieceLayout.attach(c, d, jb, t, sg, width, run, Math.max(3, width));
                                    int[] rb = PieceLayout.boxes(c, d, at[0], List.of(run), width, at[1]).get(0);
                                    check(!PieceLayout.destructive(t, jb[0], -t.ground(), jb[1], sg, rb[0], -sg.ground(), rb[1]),
                                            en.getKey() + " " + fam.key() + " " + grp.base() + " turn " + k + " face " + d + ": destructive overlap");
                                    double sc = PieceLayout.faceScore(c, d, jb, t, sg, width, run);
                                    if (sc > bestScore + 1e-9) {
                                        bestScore = sc;
                                        best = k;
                                    }
                                    checked++;
                                }
                                PieceLayout.Grid t = jg.turned(best);
                                int[] jb = PieceLayout.centred(c, t.sx(), t.sz());
                                int[] at = PieceLayout.attach(c, d, jb, t, sg, width, run, Math.max(3, width));
                                int gap = at[0] + sg.inset((d + 2) % 4) - (PieceLayout.reach(c, d, jb) - t.inset(d));
                                if (gap > worst) {
                                    worst = gap;
                                    worstOf = grp.base();
                                }
                            }
                    }
                // T junction with walls sized for this family's corner: straights lost with the T piece (before) and
                // with auto's pick, tower allowed as a fallback, and every same-width straight as a filler (after).
                if (!fam.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER).isEmpty() && !fam.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TEE).isEmpty()) {
                    int[] lensAll = fam.straights().stream().flatMap(gq -> gq.pieces().stream()).filter(pq -> pq.across() == width).mapToInt(com.volcanicmg.mccitybuilder.planner.PieceSets.Piece::run).distinct().toArray();
                    int[] c0 = {0, 0}, outs = {2, 1, 0};
                    PieceLayout.Grid cg = grids.get(en.getKey() + "/" + fam.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER).get(0).first().path());
                    int cells = cornerOff(cg, sgrid, new int[]{2, 1}, run, width) + 2 * run;
                    List<int[]> tf = new java.util.ArrayList<>();
                    List<String> names = new java.util.ArrayList<>();
                    for (var role : List.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TEE, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TOWER))
                        for (var grp : fam.of(role)) {
                            PieceLayout.Grid bt = bestTurn(grids.get(en.getKey() + "/" + grp.first().path()), sgrid, outs, run, width);
                            int pcs = 0, lft = 0;
                            for (int o : outs) {
                                PieceLayout.Grid sg = sgrid.turned(PieceLayout.turns(sgrid.sx(), sgrid.sz(), o));
                                int off = PieceLayout.attach(c0, o, PieceLayout.centred(c0, bt.sx(), bt.sz()), bt, sg, width, run, Math.max(3, width))[0];
                                int[] f = PieceLayout.runFit(cells - off, lensAll);
                                pcs += f[0];
                                lft += f[1];
                            }
                            tf.add(new int[]{pcs, lft});
                            names.add(role.name().toLowerCase(java.util.Locale.ROOT));
                        }
                    int[] teeOnly = tf.get(0);
                    int[] lone = PieceLayout.runFit(cells - 0, new int[]{run});
                    int teeStraights = fam.of(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TEE).size();
                    int pick = PieceLayout.pickJoint(tf, teeStraights);
                    tees.add(en.getKey() + " " + fam.key().substring(fam.key().indexOf(':') + 1) + ": gap " + teeOnly[1] + " -> " + tf.get(pick)[1] + " (" + names.get(pick) + ")");
                    check(tf.get(pick)[1] <= teeOnly[1] && lone[0] >= 0, "auto never worse than the T piece");
                }
                (worst == 0 ? exact : gapped).add(en.getKey() + " " + fam.key().substring(fam.key().indexOf(':') + 1) + (worst == 0 ? "" : " " + worst + " at " + worstOf));
            }
        check(checked > 100, "bundled joints checked: " + checked);
        System.out.println("CityBuilder self-check: runs meet the joint body exactly: " + exact);
        System.out.println("CityBuilder self-check: runs keep a gap (blocks) to the joint body: " + gapped);
        System.out.println("CityBuilder self-check: T junction of walls sized for the corner, uncovered cells T piece -> auto: " + tees);
    }

    /** Heights (flat = highest ground, chain = one level, joints = highest neighbour) and the plan file keeping them. */
    private static void heightsAndRecords() {
        int[] own = {64, 66, 63, 70}, high = {65, 67, 64, 71};
        int[] flat = PieceLayout.levels(Plan.FLAT, own, high, 2, Plan.NO_BASE);
        check(java.util.Arrays.stream(flat).allMatch(v -> v == 73), "flat segment = highest ground + dy: " + java.util.Arrays.toString(flat));
        check(java.util.Arrays.equals(PieceLayout.levels(Plan.FOLLOW, own, high, -1, Plan.NO_BASE), new int[]{63, 65, 62, 69}), "follow + dy");
        int[] sm = PieceLayout.levels(Plan.SMOOTH, own, high, 0, Plan.NO_BASE);
        boolean steps = true;
        for (int i = 0; i < sm.length; i++) steps &= sm[i] >= own[i] && (i == 0 || Math.abs(sm[i] - sm[i - 1]) <= 1);
        check(steps, "smooth: steps of 1, never buried: " + java.util.Arrays.toString(sm));
        // Chain: the first segment fixes the level, a higher one lifts it for all, a lower one keeps it.
        int base = PieceLayout.chainBase(Plan.NO_BASE, 70);
        base = PieceLayout.chainBase(base, 74);
        base = PieceLayout.chainBase(base, 66);
        int[] s1 = PieceLayout.levels(Plan.CHAIN, new int[]{60, 61}, new int[]{62, 63}, 1, base), s2 = PieceLayout.levels(Plan.CHAIN, new int[]{70}, new int[]{71}, 1, base);
        check(base == 74 && s1[0] == 75 && s1[1] == 75 && s2[0] == 75, "chain-flat shares one level: " + base);
        check(PieceLayout.jointLevel(64, new int[]{66, 70}) == 70 && PieceLayout.jointLevel(64, new int[]{}) == 64 && PieceLayout.jointLevel(72, new int[]{66, 70}) == 72,
                "joint = highest of its ground and its neighbours (one Y at the seam)");
        // A smoothed run whose first piece rose to its joint: the rest rises outward by at most 1 per piece, never down.
        check(java.util.Arrays.equals(PieceLayout.raiseSmooth(new int[]{64, 64, 65, 66}, 0, 68), new int[]{68, 67, 66, 66})
                && java.util.Arrays.equals(PieceLayout.raiseSmooth(new int[]{70, 64, 64}, 1, 66), new int[]{70, 66, 65}), "re-smoothed outward after a raise");
        // Deleting a decoration / a retired building: only the blueprint's own blocks, never a hut, storage with
        // contents or an entity's support; an emptied container (a moved building's) goes.
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.remove(true, false, true, false, false) && !com.volcanicmg.mccitybuilder.planner.PasteRules.remove(false, false, true, false, false)
                && !com.volcanicmg.mccitybuilder.planner.PasteRules.remove(true, true, false, false, false) && com.volcanicmg.mccitybuilder.planner.PasteRules.remove(true, true, true, false, false)
                && !com.volcanicmg.mccitybuilder.planner.PasteRules.remove(true, false, true, true, false) && !com.volcanicmg.mccitybuilder.planner.PasteRules.remove(true, false, true, false, true),
                "matching-only removal decisions");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.mode(List.of(64, 63, 64, 70, 64, 63)) == 64 && com.volcanicmg.mccitybuilder.planner.PasteRules.mode(List.of(5, 3)) == 3
                && com.volcanicmg.mccitybuilder.planner.PasteRules.mode(List.of()) == Integer.MIN_VALUE, "ground around = most common height");
        // A move's footprints may not share a block; records match exactly (pack, normalised path, anchor, turn).
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.overlap(new int[]{0, 60, 0, 9, 70, 9}, new int[]{9, 70, 9, 20, 80, 20})
                && !com.volcanicmg.mccitybuilder.planner.PasteRules.overlap(new int[]{0, 60, 0, 9, 70, 9}, new int[]{10, 60, 0, 20, 70, 9}), "footprint overlap");
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.recordKey(3, "p", "a\\b.blueprint", 1, 2, 3, 1).equals(com.volcanicmg.mccitybuilder.planner.PasteRules.recordKey(3, "p", "a/b.blueprint", 1, 2, 3, 1))
                && !com.volcanicmg.mccitybuilder.planner.PasteRules.recordKey(3, "p", "a/b.blueprint", 1, 2, 3, 1).equals(com.volcanicmg.mccitybuilder.planner.PasteRules.recordKey(3, "p", "a/b.blueprint", 1, 2, 3, 2)),
                "built record keys");
        // Built pieces round trip and stay as built (a new run meeting a built joint takes them as they are).
        var bp = new Plan.BuiltPiece(0, 0, 20, 0, "walls/corner/corner5.blueprint", 3, 23, 71, 2, 17, -3, 23, 3, "CORNER");
        var built = Plan.readBuilt(Plan.write(List.of(), List.of(new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 5, -1, false, false).asBuilt()), java.util.Map.of(), java.util.Map.of(bp.key(), List.of(bp))));
        check(built.get(Plan.lineKey(0, 0, 20, 0)).equals(List.of(bp)), "built pieces round trip");
        var pruned = new java.util.HashMap<>(built);
        Plan.pruneBuilt(pruned, List.of());
        check(pruned.isEmpty(), "built pieces go with their segment");
        // Sloped ground under a run with a cap at its start: the cap and the first straight meet at one level.
        check(java.util.Arrays.equals(PieceLayout.shareSeams(new int[]{64, 66, 67, 69}, new boolean[]{true, false, false, false}), new int[]{66, 66, 67, 69})
                && java.util.Arrays.equals(PieceLayout.shareSeams(new int[]{64, 66, 70, 67}, new boolean[]{false, false, true, false}), new int[]{64, 70, 70, 70}),
                "a cap / gate and its neighbours share a level");
        var l = new Plan.Line("wall", 0, 0, 20, 0, "medievaloak", "wall:walls/segment", 5, -1, true, false, true, Plan.CHAIN, -3, 74, true);
        String text = Plan.write(List.of(), List.of(l, new Plan.Line("road", 0, 0, 0, 9, "birch", "road:x", 1, -1, false, false)));
        var back = Plan.readLines(text);
        check(back.size() == 2 && back.get(0).equals(l) && back.get(0).built() && back.get(0).flip(), "height, flip and built records round trip: " + back);
        check(Plan.readLines("mccitybuilder-plan\t1\nL2\twall\t0\t0\t9\t0\tp\tf\t1\t-1\tfalse\tfalse\n").get(0).height() == Plan.FOLLOW, "old L2 rows read");
        check(Plan.readLines("mccitybuilder-plan\t1\nL3\twall\t0\t0\t9\t0\tp\tf\t1\t-1\tfalse\tfalse\tfalse\t9\t0\t0\tfalse\n").isEmpty(), "bad height mode skipped");
        var lines = new java.util.ArrayList<>(List.of(new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, -1, false, true).asBuilt(),
                new Plan.Line("wall", 0, 5, 20, 5, "p", "f", 1, -1, false, true)));
        check(Plan.split(lines, new int[]{8, 0}, Plan.Line::built) == -1 && lines.size() == 2, "built records are never split");
        check(Plan.split(lines, new int[]{8, 5}, Plan.Line::built) == 1 && lines.size() == 3 && lines.get(1).x1() == 8 && !lines.get(2).gate(), "split at a cell");
        check(Plan.readLines("mccitybuilder-plan\t1\nL3\twall\t0\t0\t9\t0\tp\tf\t1\t-1\tfalse\tfalse\tfalse\t2\t0\t99999\tfalse\n").isEmpty(), "hostile base skipped");
    }

    /** Snapping: exact lengths for real families, the T crossing, axes and seam grid, the zoom radius, browse order. */
    private static void snapping() {
        // Length snap gives zero gap: medievaloak segments (9), spacewars walls (12 / 6), birch simple path (10).
        int[][] fam = {{9}, {12, 6}, {10}};
        int[][] offs = {{4, 4}, {6, 0}, {0, 0}};
        for (int f = 0; f < fam.length; f++)
            for (int raw = 5; raw < 60; raw += 7) {
                int n = LineSnap.snapLength(raw, offs[f][0], offs[f][1], fam[f]);
                int rest = n - offs[f][0] - offs[f][1], sum = PieceLayout.fill(rest, fam[f]).stream().mapToInt(Integer::intValue).sum();
                check(sum == rest && rest > 0 && Math.abs(n - raw) <= 20, "length snap " + java.util.Arrays.toString(fam[f]) + " " + raw + " -> " + n + " leaves " + (rest - sum));
            }
        // T: a run east from (0, 5) crosses a north-south segment at x = 12.
        List<int[][]> segs = List.of(new int[][]{{12, 0}, {12, 20}}, new int[][]{{0, 30}, {20, 30}});
        check(LineSnap.crossing(new int[]{0, 5}, 0, 10.6, segs, 3) == 12 && LineSnap.crossing(new int[]{0, 5}, 0, 4, segs, 3) == -1, "T crossing snap");
        check(LineSnap.crossing(new int[]{0, 25}, 0, 12, segs, 3) == -1, "past the segment's end: no T");
        // Axes: a parallel wall's start lands on the other's line and seam grid; a perpendicular one on its axis.
        var ax = LineSnap.axes(List.<int[][]>of(new int[][]{{0, 30}, {20, 30}}), 9, 4);
        check(LineSnap.nearest(31.2, ax.get(1), 2.5) == 30 && LineSnap.nearest(17.4, ax.get(0), 2.5) == 18, "axis and seam grid snap");
        check(LineSnap.nearest(40, ax.get(1), 2.5) == Integer.MIN_VALUE, "far from any axis: free");
        var wall = List.<int[][]>of(new int[][]{{0, 30}, {20, 30}});
        check(LineSnap.near(wall, 10, 50, 32).size() == 1 && LineSnap.near(wall, 10, 80, 32).isEmpty() && LineSnap.near(wall, 60, 30, 32).isEmpty(), "axis snap only follows nearby walls");
        check(LineSnap.radius(20) == LineSnap.RADIUS && LineSnap.radius(80) == 8 && LineSnap.radius(400) == 10, "snap radius grows with height");
        // T on a straight: the branch lands on a piece's middle, not its seam (pieces 0..8 and 9..17).
        check(LineSnap.nearest(9.6, List.of(4, 13), Double.MAX_VALUE) == 13 && LineSnap.nearest(7.2, List.of(4, 13), Double.MAX_VALUE) == 4, "T at a piece's middle");
        // Browse order: straights, then corner .. tower, each blueprint once.
        var P = com.volcanicmg.mccitybuilder.planner.PieceSets.class;
        java.util.Map<com.volcanicmg.mccitybuilder.planner.PieceSets.Role, List<com.volcanicmg.mccitybuilder.planner.PieceSets.Piece>> per = new java.util.EnumMap<>(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.class);
        per.put(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.TOWER, List.of(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("t", "t", 1, 7, 22, 7)));
        per.put(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.STRAIGHT, List.of(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("s1", "s", 1, 6, 9, 9),
                new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("s2", "s", 2, 6, 9, 5)));
        per.put(com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER, List.of(new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("c", "c", 1, 7, 13, 7),
                new com.volcanicmg.mccitybuilder.planner.PieceSets.Piece("s1", "s", 1, 6, 9, 9)));
        var order = com.volcanicmg.mccitybuilder.planner.PieceSets.browse(per).stream().map(com.volcanicmg.mccitybuilder.planner.PieceSets.Piece::path).toList();
        check(order.equals(List.of("s1", "s2", "c", "t")) && P != null, "browse order: " + order);
    }

    /** Build all's single requests: three entries, the server busy once, all built in order; a refusal stays. */
    private static void buildQueue() {
        var q = new com.volcanicmg.mccitybuilder.planner.BuildQueue<String>();
        for (String s : List.of("baker", "well", "house")) q.add(s);
        long now = 0;
        int id = 1;
        List<String> sent = new java.util.ArrayList<>();
        boolean busied = false;
        for (int step = 0; step < 100 && !q.idle(); step++) {
            now += 500;
            String it = q.next(now, id);
            if (it == null) continue;
            sent.add(it);
            // Nothing else goes out while one is in flight.
            check(q.next(now + 1, id + 1) == null, "one at a time");
            if (it.equals("well") && !busied) {
                busied = true;
                check(q.answer(id, false, "busy", now) == null, "busy: not done yet");
            } else check(it.equals(q.answer(id, true, "", now)), "accepted: done");
            id++;
        }
        check(q.done().equals(List.of("baker", "well", "house")) && q.kept().isEmpty() && sent.equals(List.of("baker", "well", "well", "house")),
                "all built after a busy reply: " + q.done() + " sent " + sent);
        check(q.answer(99, true, "", now) == null, "stray answer ignored");
        var r = new com.volcanicmg.mccitybuilder.planner.BuildQueue<String>();
        r.add("tower");
        r.add("gate");
        check(r.next(0, 7).equals("tower") && r.answer(7, false, "refused", 0) == null && r.kept().get(0).reason().equals("refused"), "refused stays with its reason");
        check(r.next(10, 8).equals("gate") && r.next(10 + com.volcanicmg.mccitybuilder.planner.BuildQueue.ANSWER_MS, 9) == null && r.kept().get(1).reason().equals("timeout"),
                "no answer: kept as timeout");
        check(r.pending("gate") == false && r.idle(), "nothing left");
        // The timed-out gate was built after all: a late yes moves it to done, so it isn't sent again.
        check("gate".equals(r.answer(8, true, "", 10 + com.volcanicmg.mccitybuilder.planner.BuildQueue.ANSWER_MS + 5)) && r.done().contains("gate")
                && r.kept().size() == 1, "late acceptance after a timeout counts");
    }

    /**
     * Joining: another segment's end from all four sides gives a joint exactly there; the screenshot case (a wall
     * coming down across another's free end, 1-2 blocks off) moves that free end onto its line; inner / outer corners
     * by turn and flip; the T that used to cost medievaloak walls their pieces; growing a run for a new joint.
     */
    private static void joining() throws Exception {
        int[][] a = {{0, 0}, {10, 0}};
        for (int[] st : new int[][]{{10, -12}, {10, 12}, {22, 0}, {-12, 0}}) {
            if (st[0] == -12) continue;
            int[] j = LineSnap.joinEnd(st, a, 1, true, 3);
            check(j != null && j[0] == 10 && j[1] == 0 && j[2] == 10 && j[3] == 0, "joint exactly on the end from " + st[0] + "," + st[1]);
        }
        // Coming back the other way along the same line: still that end (the segment runs onto it from beyond).
        int[] back = LineSnap.joinEnd(new int[]{25, 0}, a, 1, true, 3);
        check(back != null && back[0] == 10, "approach from the far side");
        for (int off : new int[]{1, 2}) {
            int[] j = LineSnap.joinEnd(new int[]{10 + off, -12}, a, 1, true, 3);
            check(j != null && j[0] == 10 + off && j[1] == 0 && j[2] == 10 + off, "perpendicular end " + off + " off: the free end moves onto the line, a corner forms");
            check(LineSnap.joinEnd(new int[]{10 - off - 12, -12}, a, 1, true, 3) == null, "too far off: no joint");
        }
        check(LineSnap.joinEnd(new int[]{11, -12}, a, 1, false, 3) == null, "a shared end doesn't move");
        check(LineSnap.joinEnd(new int[]{3, 5}, new int[][]{{0, 0}, {10, 0}}, 1, true, 3) == null, "parallel and off the line: no joint");
        // Corners: a clockwise loop (east, then south) bends away from the left-hand outside: outer; the other way: inner.
        check(!PieceLayout.innerTurn(2, 1, false) && PieceLayout.innerTurn(2, 3, false) && PieceLayout.innerTurn(2, 1, true), "inner / outer corner by turn and flip");
        check(LineSnap.radiusPx(40, 70, 240, 24) > LineSnap.RADIUS && LineSnap.radiusPx(1, 70, 240, 24) == LineSnap.RADIUS, "snap radius in screen pixels");
        check(LineSnap.extension(18, 0, 4, new int[]{9}, 20) == 4 && LineSnap.extension(22, 0, 4, new int[]{9}, 20) == 0, "grow a run for a new joint");
        check(PieceLayout.pickJoint(List.of(new int[]{4, 2}, new int[]{6, 0}), 1) == 1 && PieceLayout.pickJoint(List.of(new int[]{6, 2}, new int[]{6, 0}), 1) == 0,
                "a fallback tower only when it keeps more straights");
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        // The bug: medievaloak walls sized for a corner, then a third wall joins. Each run must keep its straights.
        PieceLayout.Grid seg = PackPieces.grid("medievaloak/walls/segment/segment5.blueprint");
        java.util.Map<String, PieceLayout.Grid> joint = new java.util.LinkedHashMap<>();
        joint.put("corner", PackPieces.grid("medievaloak/walls/corner/corner5.blueprint"));
        joint.put("tee", PackPieces.grid("medievaloak/walls/corner/tcorner5.blueprint"));
        joint.put("tower", PackPieces.grid("medievaloak/walls/tower/tower5.blueprint"));
        int run = 9, width = 6;
        int[] c = {0, 0};
        int[] cornerOuts = {2, 1}, teeOuts = {2, 1, 0};
        int cells = cornerOff(joint.get("corner"), seg, cornerOuts, run, width) + 2 * run;
        List<int[]> fits = new java.util.ArrayList<>();
        for (String k : List.of("tee", "tower")) {
            PieceLayout.Grid best = bestTurn(joint.get(k), seg, teeOuts, run, width);
            int pieces = 0, left = 0;
            for (int o : teeOuts) {
                PieceLayout.Grid sg = seg.turned(PieceLayout.turns(seg.sx(), seg.sz(), o));
                int off = PieceLayout.attach(c, o, PieceLayout.centred(c, best.sx(), best.sz()), best, sg, width, run, Math.max(3, width))[0];
                int[] f = PieceLayout.runFit(cells - off, new int[]{run});
                pieces += f[0];
                left += f[1];
            }
            fits.add(new int[]{pieces, left});
        }
        int pick = PieceLayout.pickJoint(fits, 1);
        check(fits.get(pick)[0] == 6, "three walls sized for a corner keep both straights each at the T: " + fits.get(0)[0] + " with the T piece, "
                + fits.get(1)[0] + " with a tower, picked " + (pick == 0 ? "T" : "tower"));
        System.out.println("CityBuilder self-check: medievaloak T junction keeps straights: T piece " + fits.get(0)[0] + ", tower " + fits.get(1)[0]);
        // Plan side: adding the third / fourth wall and closing the ring never removes or shortens a wall.
        List<Plan.Line> ring = new java.util.ArrayList<>(List.of(new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 5, -1, false, false),
                new Plan.Line("wall", 20, 0, 20, 20, "p", "f", 5, -1, false, false)));
        List<Plan.Line> before = new java.util.ArrayList<>(ring);
        for (int[][] add : new int[][][]{{{20, 0}, {40, 0}}, {{20, 0}, {20, -20}}, {{20, 20}, {0, 20}}, {{0, 20}, {0, 0}}}) {
            check(Plan.split(ring, add[0], Plan.Line::built) == -1 && Plan.split(ring, add[1], Plan.Line::built) == -1, "ends at joints never split");
            ring.add(new Plan.Line("wall", add[0][0], add[0][1], add[1][0], add[1][1], "p", "f", 5, -1, false, false));
        }
        check(ring.containsAll(before) && ring.size() == 6, "no wall removed or shortened: " + ring.size());
    }

    private static int cornerOff(PieceLayout.Grid j, PieceLayout.Grid seg, int[] outs, int run, int width) {
        PieceLayout.Grid best = bestTurn(j, seg, outs, run, width);
        int[] c = {0, 0};
        PieceLayout.Grid sg = seg.turned(PieceLayout.turns(seg.sx(), seg.sz(), outs[0]));
        return PieceLayout.attach(c, outs[0], PieceLayout.centred(c, best.sx(), best.sz()), best, sg, width, run, Math.max(3, width))[0];
    }

    private static PieceLayout.Grid bestTurn(PieceLayout.Grid j, PieceLayout.Grid seg, int[] outs, int run, int width) {
        PieceLayout.Grid best = j;
        double bs = -1;
        int[] c = {0, 0};
        for (int k = 0; k < 4; k++) {
            PieceLayout.Grid t = j.turned(k);
            double sc = 0;
            for (int o : outs) sc += PieceLayout.faceScore(c, o, PieceLayout.centred(c, t.sx(), t.sz()), t, seg.turned(PieceLayout.turns(seg.sx(), seg.sz(), o)), width, run);
            if (sc > bs + 1e-9) {
                bs = sc;
                best = t;
            }
        }
        return best;
    }

    /** Edit mode moves: an end drag, a joint drag (one wall longer, the other shifted), a body drag; locked and reversing moves refused. */
    private static void editing() {
        Plan.Line h = new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, -1, false, false), v = new Plan.Line("wall", 20, 0, 20, 20, "p", "f", 1, -1, false, false);
        var m = Plan.move(List.of(h, v), List.of(new int[]{0, 0}), -5, 0, l -> false);
        check(m != null && m.get(0).x0() == -5 && m.get(1).equals(v), "end drag lengthens");
        // The corner moves east 3 and south 2: the horizontal wall grows, the vertical one shifts (its far end is free).
        m = Plan.move(List.of(h, v), List.of(new int[]{20, 0}), 3, 2, l -> false);
        check(m != null && m.get(0).x1() == 23 && m.get(0).z1() == 2 && m.get(0).z0() == 2 && m.get(1).x0() == 23 && m.get(1).x1() == 23 && m.get(1).z1() == 20,
                "joint drag: both ends follow " + m);
        // The horizontal wall's far end is shared with a third wall: it can't shift across, only along.
        Plan.Line w = new Plan.Line("wall", 0, 0, 0, -20, "p", "f", 1, -1, false, false);
        m = Plan.move(List.of(h, v, w), List.of(new int[]{20, 0}), 3, 2, l -> false);
        check(m != null && m.get(0).z1() == 0 && m.get(0).x1() == 23 && m.get(1).x0() == 23 && m.get(2).equals(w), "shared far end: only the free component moves " + m);
        m = Plan.move(List.of(h, v), List.of(h.ends()), 0, 4, l -> false);
        check(m != null && m.get(0).z0() == 4 && m.get(1).z0() == 4 && m.get(1).z1() == 20, "body drag: the neighbour stretches");
        check(Plan.move(List.of(h, v), List.of(new int[]{0, 0}), 25, 0, l -> false) == null, "an end can't pass the other");
        check(Plan.move(List.of(h.asBuilt(), v), List.of(new int[]{20, 0}), 1, 0, Plan.Line::built) == null, "built walls don't move");
        // A batch answer arriving mid-drag: the release moves from the plan as it is then, so the built wall stays
        // built and unmoved; a line added mid-drag doesn't break it either.
        List<int[]> pts = List.of(new int[]{20, 0});
        var preview = Plan.move(List.of(h, v), pts, 3, 0, l -> false);
        var now = List.of(h.asBuilt(), v, new Plan.Line("wall", 50, 50, 60, 50, "p", "f", 1, -1, false, false));
        check(preview != null && Plan.move(now, pts, 3, 0, Plan.Line::built) == null, "answer mid-drag: the built wall isn't moved back to unbuilt");
        var later = Plan.move(List.of(h, v, now.get(2)), pts, 3, 0, Plan.Line::built);
        check(later != null && later.size() == 3 && Plan.moved(List.of(h, v, now.get(2)), later, pts).get(Plan.cell(20, 0))[0] == 23, "count change mid-drag");
        // M2: moving another wall's free end onto the line must leave it a straight.
        check(LineSnap.joinKeeps(14, 0, 4, new int[]{9}) && !LineSnap.joinKeeps(12, 0, 4, new int[]{9}) && !LineSnap.joinKeeps(30, 0, 4, new int[]{}), "join keeps a straight");
        java.util.Map<Long, Integer> jt = new java.util.HashMap<>(java.util.Map.of(Plan.cell(20, 0), Plan.J_TEE, Plan.cell(99, 99), Plan.J_TOWER));
        Plan.pruneJoints(jt, List.of(h, v));
        check(jt.size() == 1 && jt.containsKey(Plan.cell(20, 0)), "joint types of vanished joints pruned");
        var t = Plan.readJoints(Plan.write(List.of(), List.of(h), java.util.Map.of(Plan.cell(20, 0), Plan.J_INNER, Plan.cell(-3, 7), Plan.J_TOWER)));
        check(t.get(Plan.cell(20, 0)) == Plan.J_INNER && t.get(Plan.cell(-3, 7)) == Plan.J_TOWER && t.size() == 2, "joint types round trip");
    }

    /**
     * Continuity on the real blueprints, measured on the placed blocks ({@link JointCases#report}): every bundled road /
     * wall family, every joint role it has (corner, inner corner, T, cross, tower, end cap), both turn directions,
     * flipped and unflipped. Each run's visible cross-section at its face must match the joint's next layer as well
     * as any turn / shift / depth could (the layout is optimal), and the fit's own metric must agree with the blocks.
     * Prints which joints are continuous and a table of those that can't be, with the rows that differ.
     */
    /**
     * Every road family at every level: an L turning left and one turning right take their corner turned differently
     * (a road's cross-section is symmetric, so one turn can't serve both). Corners whose runs never touch them (lamp
     * pieces whose air would clear each other) once fell back to one turn for both. Jungle levels 1-3 are the known
     * exceptions (their corner meets the left and right L's runs equally well either way: a tie kept in order).
     */
    private static void cornerTurns() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        Map<String, JointCases.Case> left = new java.util.HashMap<>(), right = new java.util.HashMap<>();
        for (var cs : JointCases.allLevels()) {
            if (!cs.family().contains("infrastructure")) continue;
            if (cs.name().equals("L-left")) left.put(cs.family(), cs);
            if (cs.name().equals("L-right")) right.put(cs.family(), cs);
        }
        List<String> same = new java.util.ArrayList<>();
        for (var en : left.entrySet()) {
            var r = right.get(en.getKey());
            if (r != null && java.util.Arrays.equals(en.getValue().joint().ids(), r.joint().ids()) && en.getValue().joint().sx() == r.joint().sx()) same.add(en.getKey());
        }
        same.sort(null);
        check(same.equals(List.of("jungle infrastructure/roads L1", "jungle infrastructure/roads L2", "jungle infrastructure/roads L3")),
                "road corners turn both ways at every level: " + same);
    }

    /**
     * Joint geometry checks over every family, level and variant ({@link JointCases#allLevels}), each a ratchet
     * against {@code src/test/resources/joint-baseline.txt}: entries are keyed by geometry (family, case, joint piece
     * and turn, each run's straight and off / shift / depth), so a level that lays out the same is one entry. A new
     * failure fails, and so does a baselined case that now passes, so the list only shrinks
     * ({@code -DwriteBaseline=true} rewrites both baseline files). Known classes (below) are listed, not judged.
     * <ul>
     * <li>wallCornerTurns: walls only, and only corners no run touches well (no contact, or the best run match under
     * 0.3): fails when the corner's closed sides look more like the straight's end than its arms do by over 0.15
     * ({@link PieceLayout#armClosed});</li>
     * <li>noOverhang: a run that touches nothing stays within the joint's lateral span (plus its own side trim);</li>
     * <li>throughArmsAligned: a T's or cross's opposite arms line up: their visible end profiles' world lateral
     * centres within half a cell;</li>
     * <li>tShiftCentred: a tower T's third arm that touches nothing sits on the tower's centre line;</li>
     * <li>noContactDelta: runs that touch nothing, per family, never more than the checked-in baseline
     * ({@code src/test/resources/no-contact.tsv}).</li>
     * </ul>
     */
    private static void jointChecks() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        List<JointCases.Case> all = JointCases.allLevels();
        Path allowFile = Path.of("src/test/resources/joint-baseline.txt");
        java.util.Set<String> allow = new java.util.HashSet<>();
        if (Files.exists(allowFile))
            for (String line : Files.readAllLines(allowFile)) if (!line.isBlank()) allow.add(line.split("\t")[0]);
        Map<String, String> failing = new java.util.TreeMap<>(), known = new java.util.TreeMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>(), passingAllowed = new java.util.TreeSet<>();
        List<String> fails = new java.util.ArrayList<>(), notes = new java.util.ArrayList<>();
        Map<String, Integer> noContact = new java.util.TreeMap<>();
        for (var cs : all) {
            for (var r : cs.runs()) if (r.fit().depth() < 0) noContact.merge(cs.family(), 1, Integer::sum);
            String fam = cs.family().replaceAll(" L\\d+$", "");
            StringBuilder g = new StringBuilder(fam + "|" + cs.name() + "|" + cs.jointPath() + "|" + Integer.toHexString(java.util.Arrays.hashCode(cs.joint().ids())) + "x" + cs.joint().sx());
            for (var r : cs.runs())
                g.append("|").append(r.out()).append(":").append(Integer.toHexString(java.util.Arrays.hashCode(r.straight().ids()))).append(":").append(r.fit().off())
                        .append(",").append(r.fit().shift()).append(",").append(r.fit().depth());
            String geo = g.toString(), id = cs.family() + "|" + cs.name();
            if (!seen.add(geo)) continue;
            String knownWhy = fam.equals("jungle infrastructure/roads") ? "jungle's walks include diagonal pieces, laid like axis runs" : null;
            java.util.function.BiConsumer<String, Boolean> verdict = (check, ok) -> {
                String k = check + " " + geo;
                if (knownWhy != null) {
                    if (!ok) known.put(check + " " + id, knownWhy);
                    return;
                }
                if (!ok) failing.put(k, check + " " + id);
                if (allow.contains(k)) {
                    if (ok) passingAllowed.add(check + " " + id);
                } else if (!ok) fails.add(check + " " + id);
            };
            if (fam.contains("walls") && cs.runs().size() == 2 && cs.name().startsWith("L-") && !cs.name().contains("tower")) {
                double bestMatch = 0;
                boolean contact = false;
                for (var r : cs.runs()) {
                    contact |= r.fit().depth() >= 0;
                    bestMatch = Math.max(bestMatch, r.fit().match());
                }
                PieceLayout.Grid j = cs.joint(), t1 = j.turned(1);
                if ((!contact || bestMatch < 0.3) && !(t1.sx() == j.sx() && java.util.Arrays.equals(t1.ids(), j.ids()))) {
                    List<Integer> outs = new java.util.ArrayList<>();
                    for (var r : cs.runs()) outs.add(r.out());
                    double[] ac = PieceLayout.armClosed(j, outs, cs.runs().get(0).straight(), false);
                    verdict.accept("wallCornerTurns", ac[1] - ac[0] <= 0.15);
                }
            }
            for (var r : cs.runs()) {
                if (r.fit().depth() >= 0 || cs.butt()) continue;
                int d = r.out(), lat0 = d % 2 == 0 ? 1 : 0, lat1 = lat0 + 2;
                int jl = cs.jointBox()[lat1] - cs.jointBox()[lat0] + 1, rl = r.box()[lat1] - r.box()[lat0] + 1;
                if (rl > jl) continue;
                int ia = r.straight().inset((d + 1) % 4), ib = r.straight().inset((d + 3) % 4), trim = Math.max(ia, ib);
                verdict.accept("noOverhang", r.box()[lat0] >= cs.jointBox()[lat0] - trim && r.box()[lat1] <= cs.jointBox()[lat1] + trim);
            }
            if ((cs.name().startsWith("T") || cs.name().startsWith("cross")) && !cs.butt()) {
                boolean ok = true;
                StringBuilder nums = new StringBuilder();
                for (var a : cs.runs())
                    for (var b : cs.runs())
                        if (b.out() == (a.out() + 2) % 4 && a.out() < b.out()) {
                            double ca = endCentre(a), cb = endCentre(b);
                            ok &= Math.abs(ca - cb) <= 0.5;
                            nums.append(String.format(" %d/%d: %.2f vs %.2f", a.out(), b.out(), ca, cb));
                        }
                verdict.accept("throughArmsAligned", ok);
                if (cs.family().matches("(medievaloak walls L5|fortress walls L1)") && cs.name().equals("cross"))
                    notes.add(id + (ok ? " aligned" : " NOT aligned") + nums);
                if (cs.name().startsWith("T") && cs.jointPath().toLowerCase(java.util.Locale.ROOT).contains("tower"))
                    for (var r : cs.runs()) {
                        boolean through = false;
                        for (var b : cs.runs()) through |= b.out() == (r.out() + 2) % 4;
                        if (through || r.fit().depth() >= 0) continue;
                        int lat0 = r.out() % 2 == 0 ? 1 : 0, lat1 = lat0 + 2;
                        double rc = (r.box()[lat0] + r.box()[lat1]) / 2.0, jc = (cs.jointBox()[lat0] + cs.jointBox()[lat1]) / 2.0;
                        verdict.accept("tShiftCentred", Math.abs(rc - jc) <= 0.5);
                    }
            }
        }
        for (String n : notes) System.out.println("CityBuilder self-check: through arms, " + n);
        Path base = Path.of("src/test/resources/no-contact.tsv");
        if (Boolean.getBoolean("writeBaseline")) {
            StringBuilder sb = new StringBuilder();
            noContact.forEach((f, n) -> sb.append(f).append('\t').append(n).append('\n'));
            Files.createDirectories(base.getParent());
            Files.writeString(base, sb.toString());
            StringBuilder al = new StringBuilder();
            failing.forEach((k, readable) -> al.append(k).append('\t').append(readable).append('\n'));
            Files.writeString(allowFile, al.toString());
            return;
        }
        Map<String, Integer> per = new java.util.TreeMap<>();
        for (String r : failing.values()) per.merge(r.substring(0, r.indexOf(' ')), 1, Integer::sum);
        System.out.println("CityBuilder self-check: joint checks, distinct geometries " + seen.size() + " of " + all.size() + "; failing (baselined) per check " + per);
        for (String r : failing.values()) System.out.println("  " + r);
        System.out.println("CityBuilder self-check: joint checks, known classes (" + known.size() + "): " + new java.util.TreeSet<>(known.values()));
        Map<String, Integer> was = new java.util.HashMap<>();
        if (Files.exists(base))
            for (String line : Files.readAllLines(base)) {
                String[] f = line.split("\t");
                if (f.length == 2) was.put(f[0], Integer.parseInt(f[1]));
            }
        noContact.forEach((f, n) -> {
            if (n > was.getOrDefault(f, 0)) fails.add("noContactDelta " + f + " " + was.getOrDefault(f, 0) + " -> " + n);
        });
        System.out.println("CityBuilder self-check: joint checks failing beyond the baseline (" + fails.size() + "): " + fails);
        check(fails.isEmpty(), "joint checks: " + fails.size() + " failing (listed above)");
        check(passingAllowed.isEmpty(), "baselined joint cases now passing (drop them from joint-baseline.txt): " + passingAllowed);
    }

    /** @return a run's visible end profile's lateral centre in world cells (its box's lateral origin plus the profile's mean) */
    private static double endCentre(JointCases.Run r) {
        int d = r.out(), lat0 = d % 2 == 0 ? 1 : 0;
        PieceLayout.Grid s = r.straight();
        java.util.Set<Long> p = PieceLayout.visible(s.profile((d + 2) % 4, s.inset((d + 2) % 4), 0, false), s);
        if (p.isEmpty()) return r.box()[lat0] + (r.box()[lat0 + 2] - r.box()[lat0]) / 2.0;
        double sum = 0;
        for (long v : p) sum += (v & 0xFFFF) - 30000;
        return r.box()[lat0] + sum / p.size();
    }

    private static void continuity() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        // Memoised fits equal fresh ones, and don't depend on where the joint is.
        PieceLayout.Grid tw = PackPieces.grid("medievaloak/walls/tower/tower5.blueprint").turned(1), sgm = PackPieces.grid("medievaloak/walls/segment/segment5.blueprint");
        for (int d = 0; d < 4; d++) {
            PieceLayout.Grid st = sgm.turned(PieceLayout.turns(sgm.sx(), sgm.sz(), d));
            int[] c0 = {0, 0}, c1 = {37, -12};
            int[] jb0 = PieceLayout.centred(c0, tw.sx(), tw.sz()), jb1 = PieceLayout.centred(c1, tw.sx(), tw.sz());
            var cached = PieceLayout.fit(c0, d, jb0, tw, st, 6, 9, 6);
            check(cached.equals(PieceLayout.fitNow(c0, d, jb0, tw, st, 6, 9, 6)) && cached.equals(PieceLayout.fit(c0, d, jb0, tw, st, 6, 9, 6))
                    && cached.equals(PieceLayout.fitNow(c1, d, jb1, tw, st, 6, 9, 6)) && cached.equals(PieceLayout.fit(c1, d, jb1, tw, st, 6, 9, 6)), "cached fit = fresh fit, anywhere");
            check(PieceLayout.faceScore(c1, d, jb1, tw, st, 6, 9) == PieceLayout.faceScoreNow(c0, d, jb0, tw, st, 6, 9), "cached face score = fresh");
        }
        List<String> ok = new java.util.ArrayList<>(), bad = new java.util.ArrayList<>();
        for (var cs : JointCases.all()) {
            String id = cs.family() + " " + cs.name() + " (" + cs.jointPath().substring(cs.jointPath().lastIndexOf('/') + 1) + ")";
            var rep = JointCases.report(cs);
            check(rep.match() >= rep.best() - 1e-9, "layout as continuous as the pieces allow: " + id + String.format(" %.2f < %.2f", rep.match(), rep.best()));
            for (var r : cs.runs())
                check(Math.abs(JointCases.measure(cs, r) - r.fit().match()) < 1e-9, "fit metric agrees with the placed blocks: " + id + " run " + "ESWN".charAt(r.out()));
            if (rep.match() >= 1 - 1e-9) ok.add(id);
            else bad.add(String.format("%-62s %.2f  %s", id, rep.match(), rep.reason()));
        }
        // The family in Ethan's screenshots: at every corner and T (it has no cross piece) the wall's body (rows
        // 3..6: above the plinth, below the walkway, which its corner piece lacks) continues without a sideways step.
        for (var cs : JointCases.cases("medievaloak", "wall:walls"))
            for (var r : cs.runs())
                if (!cs.name().contains("tower") && (cs.name().startsWith("L-") || cs.name().startsWith("T")))
                    check(JointCases.measure(cs, r, 3, 6) >= 1 - 1e-9, "medievaloak " + cs.name() + " run " + "ESWN".charAt(r.out()) + " body continues: " + JointCases.report(cs).reason());
        // Towers with joints pasted first (creative): a run may fill the ring's air, so it reaches the body.
        List<String> closed = new java.util.ArrayList<>(), still = new java.util.ArrayList<>();
        PieceLayout.jointFirst = true;
        try {
            for (var cs : JointCases.all()) {
                if (!cs.name().contains("tower")) continue;
                var rep = JointCases.report(cs);
                check(rep.match() >= rep.best() - 1e-9, "joint-first layout optimal: " + cs.family() + " " + cs.name());
                boolean gap = false;
                for (var r : cs.runs()) gap |= r.fit().depth() < 0;
                (gap ? still : closed).add(cs.family() + " " + cs.name() + String.format(" %.2f", rep.match()));
            }
        } finally {
            PieceLayout.jointFirst = false;
        }
        System.out.println("CityBuilder self-check: tower runs reaching the body with joints pasted first (" + closed.size() + "): " + closed);
        System.out.println("CityBuilder self-check: tower runs still stopping at the box (" + still.size() + "): " + still);
        System.out.println("CityBuilder self-check: continuous joints (" + ok.size() + "): " + ok);
        System.out.println("CityBuilder self-check: joints that can't be continuous (" + bad.size() + "):");
        for (String b : bad) System.out.println("  " + b);
    }

    /** Auto joints from the shape alone, edit handles, wall grouping, fitting moves and re-fits, the tools' Clear. */
    private static void editRules() {
        // Closing a ring on its own start: the end snap lands on the first wall's start (in line), the T snap never
        // takes the first wall's end cell, and every corner has exactly two ends, so each gets a joint (4 for a square,
        // 3 for a three-sided loop); Done never trims an end that two walls share.
        int[][] sq = {{0, 0}, {20, 0}, {20, 20}, {0, 20}};
        List<Plan.Line> ring = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) ring.add(new Plan.Line("wall", sq[i][0], sq[i][1], sq[(i + 1) % 4][0], sq[(i + 1) % 4][1], "p", "f", 1, -1, true, false));
        int[] closing = LineSnap.joinEnd(new int[]{0, 20}, ring.get(0).ends(), 0, true, 3);
        check(closing != null && closing[0] == 0 && closing[1] == 0, "chain closes on its own start");
        check(LineSnap.crossing(new int[]{0, 20}, 3, 20, List.<int[][]>of(ring.get(0).ends()), 3) == -1, "no T split at the first wall's end");
        for (int[] c : sq) {
            int ends = 0;
            for (Plan.Line l : ring) for (int[] e : l.ends()) if (e[0] == c[0] && e[1] == c[1]) ends++;
            check(ends == 2 && Plan.split(new java.util.ArrayList<>(ring), c, l -> false) == -1, "ring corner " + c[0] + "," + c[1] + " is a joint of two walls");
            check(PieceLayout.jointRoles(Plan.J_AUTO, 2, false, true).get(0) == PieceSets.Role.TOWER, "a towered ring gets towers at its corners");
        }
        // A T into a long wall with an unrelated free end 8 cells away still T-snaps; the ring's own start closes.
        check(!LineSnap.closes(new int[]{10, 0}, new int[]{18, 0}, new int[]{0, 0}, 16) && LineSnap.closes(new int[]{1, 0}, new int[]{0, 0}, new int[]{0, 0}, 16)
                && LineSnap.closes(new int[]{10, 0}, new int[]{12, 0}, null, 16) && !LineSnap.closes(new int[]{10, 0}, new int[]{13, 0}, null, 16), "a free end hijacks a T only when it is the chain's start or right at the cell");
        // A hovered joint type is a drawing override: the plan's map (what a save writes) keeps the original.
        java.util.Map<Long, Integer> types = new java.util.HashMap<>(java.util.Map.of(Plan.cell(0, 0), Plan.J_TOWER));
        var shown = Plan.withJoint(types, Plan.cell(0, 0), Plan.J_INNER);
        check(shown.get(Plan.cell(0, 0)) == Plan.J_INNER && types.get(Plan.cell(0, 0)) == Plan.J_TOWER && !Plan.withJoint(types, Plan.cell(0, 0), Plan.J_AUTO).containsKey(Plan.cell(0, 0)),
                "joint preview never touches the saved types");
        // A live run passing a perpendicular wall's free end within the radius: the T cell on the run's line beside it,
        // both sides and both directions; an end behind the start, past the cursor or a parallel wall's end is ignored.
        List<int[]> ends = List.of(new int[]{10, 2, 1}, new int[]{30, -1, 1}, new int[]{5, 0, 0});
        int[] ps = LineSnap.passing(new int[]{0, 0}, 0, 20, ends, 3);
        check(ps != null && ps[0] == 10 && ps[1] == 0 && ps[2] == 0, "passing a free end: T cell on the line");
        check(LineSnap.passing(new int[]{0, 0}, 0, 8, ends, 3) == null && LineSnap.passing(new int[]{0, 0}, 2, 20, ends, 3) == null, "no T behind or past the run");
        int[] pw = LineSnap.passing(new int[]{0, 0}, 3, 20, List.of(new int[]{-2, -12, 1}), 3);
        check(pw != null && pw[0] == 0 && pw[1] == -12, "passing an end on the other side heading north");
        // One confirmed Clear leaves no planned segment of the tool; built and sending ones and the other tool stay.
        List<Plan.Line> clearMix = List.of(new Plan.Line("wall", 0, 0, 9, 0, "p", "f", 1, -1, false, false), new Plan.Line("wall", 9, 0, 9, 9, "p", "f", 1, -1, false, false).asBuilt(),
                new Plan.Line("road", 0, 5, 9, 5, "p", "r", 1, -1, false, false), new Plan.Line("wall", 20, 0, 29, 0, "p", "f", 1, -1, false, false));
        List<Plan.Line> left = Plan.clearTool(clearMix, "wall", Plan.Line::built);
        check(left.size() == 2 && left.stream().noneMatch(l -> l.tool().equals("wall") && !l.built()), "one Clear removes every planned wall");
        List<Plan.Line> tri = List.of(new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, -1, false, false), new Plan.Line("wall", 20, 0, 20, 20, "p", "f", 1, -1, false, false),
                new Plan.Line("wall", 20, 20, 0, 20, "p", "f", 1, -1, false, false), new Plan.Line("wall", 0, 20, 0, 0, "p", "f", 1, -1, false, false));
        check(Plan.connected(tri, 0).size() == 4, "a closed loop is one wall");
        // Auto: every L gets the plain corner whichever way it turns or faces; T, cross and straight on by count.
        for (int a = 0; a < 4; a++)
            check(PieceLayout.autoRole(2, false, a, (a + 1) % 4, false) == PieceLayout.CORNER && PieceLayout.autoRole(2, false, a, (a + 3) % 4, true) == PieceLayout.CORNER, "every L gets the corner");
        check(PieceLayout.autoRole(3, false, 0, 1, false) == PieceLayout.TEE && PieceLayout.autoRole(4, false, 0, 1, true) == PieceLayout.CROSS
                && PieceLayout.autoRole(2, true, 0, 2, false) == PieceLayout.NONE, "T, cross, straight on");
        // A free end's menu: auto / end cap / tower / none, never an L's corners; a tower there is laid out like a joint
        // (its role first, the cap as the stand-in); its type survives pruning while the end exists.
        check(Plan.menuTypes(1, false).equals(List.of(Plan.J_AUTO, Plan.J_CAP, Plan.J_TOWER, Plan.J_NONE)) && !Plan.menuTypes(1, false).contains(Plan.J_CORNER)
                && Plan.menuTypes(2, false).contains(Plan.J_INNER) && !Plan.menuTypes(3, false).contains(Plan.J_CORNER), "menu types by degree");
        check(PieceLayout.jointRoles(Plan.J_TOWER, 1, false, false).equals(List.of(PieceSets.Role.TOWER, PieceSets.Role.END))
                && PieceLayout.jointRoles(Plan.J_AUTO, 1, false, true).equals(List.of(PieceSets.Role.END)) && PieceLayout.jointRoles(Plan.J_NONE, 1, false, false).isEmpty(),
                "free end roles: tower when chosen, else the cap, none removes it");
        java.util.Map<Long, Integer> endTypes = new java.util.HashMap<>(java.util.Map.of(Plan.cell(20, 0), Plan.J_TOWER, Plan.cell(50, 50), Plan.J_CAP));
        Plan.pruneJoints(endTypes, List.of(new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, -1, false, false)));
        check(endTypes.containsKey(Plan.cell(20, 0)) && !endTypes.containsKey(Plan.cell(50, 50)), "end types kept while the end exists, dropped with it");
        // Stand-ins: an inner corner pick falls back to the corner, a T or cross to a tower, none / straight on = butt.
        check(PieceLayout.jointRoles(Plan.J_INNER, 2, false, false).equals(List.of(PieceSets.Role.INNER, PieceSets.Role.CORNER))
                && PieceLayout.jointRoles(Plan.J_AUTO, 3, false, false).equals(List.of(PieceSets.Role.TEE, PieceSets.Role.TOWER))
                && PieceLayout.jointRoles(Plan.J_AUTO, 2, true, false).isEmpty() && PieceLayout.jointRoles(Plan.J_NONE, 3, false, true).isEmpty()
                && PieceLayout.jointRoles(Plan.J_AUTO, 2, false, true).get(0) == PieceSets.Role.TOWER, "joint role stand-ins");
        // Handles: a node beats a middle handle even when the middle one is nearer; beyond 12 px nothing.
        List<double[]> hs = List.of(new double[]{100, 100, 1}, new double[]{109, 100, 0}, new double[]{140, 100, 0});
        check(LineSnap.pickHandle(hs, 101, 100, 12) == 1 && LineSnap.pickHandle(hs, 70, 100, 12) == -1 && LineSnap.pickHandle(List.of(hs.get(0)), 101, 100, 12) == 0,
                "handle priority");
        // Whole walls: through joints, T's (a branch splits the bar there) and loops; a separate wall stays apart.
        List<Plan.Line> w = new java.util.ArrayList<>();
        int[][][] segs = {{{0, 0}, {10, 0}}, {{10, 0}, {10, 10}}, {{10, 10}, {0, 10}}, {{0, 10}, {0, 0}}, {{10, 0}, {20, 0}}, {{20, 0}, {20, -5}}, {{50, 50}, {60, 50}}};
        for (int[][] sg : segs) w.add(new Plan.Line("wall", sg[0][0], sg[0][1], sg[1][0], sg[1][1], "p", "f", 1, -1, false, false));
        check(Plan.connected(w, 2).equals(List.of(0, 1, 2, 3, 4, 5)) && Plan.connected(w, 6).equals(List.of(6)), "connected wall: " + Plan.connected(w, 2));
        // A run fixed at both ends (towers both sides, 9-long straights, joints taking 4 each): a drag of the far
        // joint snaps only to lengths that keep whole straights; the near wall's free end then re-fits.
        List<Plan.Line> two = List.of(new Plan.Line("wall", 0, 0, 25, 0, "p", "f", 1, -1, false, false), new Plan.Line("wall", 25, 0, 25, 30, "p", "f", 1, -1, false, false),
                new Plan.Line("wall", 0, 0, 0, 20, "p", "f", 1, -1, false, false));
        List<int[]> offs = List.of(new int[]{4, 4}, new int[]{4, 0}, new int[]{4, 0}), lens = List.of(new int[]{9}, new int[]{9}, new int[]{9});
        int[] f = Plan.fitMove(two, List.of(new int[]{25, 0}), 2, 0, l -> false, offs, lens, 9);
        check(f != null && LineSnap.exact(Plan.cells(Plan.move(two, List.of(new int[]{25, 0}), f[0], f[1], l -> false).get(0)), 4, 4, new int[]{9}), "drag snaps to a fitting length " + java.util.Arrays.toString(f));
        check(Plan.fitMove(two, List.of(new int[]{25, 0}), 2, 0, l -> false, offs, lens, 0) == null, "no fitting position within reach: none");
        // The screenshot: a tower (reach 5) replaces a corner (4) between fixed joints: no free end, so nothing re-fits
        // (the type change is refused); with a free end, the wall grows to fit.
        var fixed = Plan.refitFree(two, List.of(0), List.of(new int[]{5, 5}, new int[]{4, 0}, new int[]{4, 0}), lens, l -> false, 12);
        check(fixed.get(0).equals(two.get(0)), "a run fixed at both ends isn't moved (the change is refused instead)");
        var free = Plan.refitFree(List.of(new Plan.Line("wall", 0, 0, 16, 0, "p", "f", 1, -1, false, false)), List.of(0), List.of(new int[]{5, 0}), List.of(new int[]{9}), l -> false, 12);
        check(Plan.cells(free.get(0)) == 23 && free.get(0).x0() == 0, "a free end grows to fit: " + free.get(0));
        // The tools' Clear: walls only, not built or sending ones, not roads.
        var mixed = List.of(new Plan.Line("wall", 0, 0, 9, 0, "p", "f", 1, -1, false, false), new Plan.Line("road", 0, 5, 9, 5, "p", "f", 1, -1, false, false),
                new Plan.Line("wall", 0, 9, 9, 9, "p", "f", 1, -1, false, false).asBuilt());
        var kept = Plan.clearTool(mixed, "wall", Plan.Line::built);
        check(kept.size() == 2 && kept.get(0).tool().equals("road") && kept.get(1).built(), "clearing walls leaves roads and built walls");
    }

    /** Player joint alignments: file round trip, used exactly as set, destructive ones refused, reset back to auto. */
    private static void alignments() throws Exception {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("wall:walls|CORNER", "corner");
        String key = PieceLayout.alignKey("walls/tower/tower5.blueprint", 3, "walls/segment/segment5.blueprint", 1, 2);
        m.put(key, "1,2");
        var back = com.volcanicmg.mccitybuilder.planner.PieceSets.readOverrides(com.volcanicmg.mccitybuilder.planner.PieceSets.writeOverrides(m) + "junk\n");
        check(back.equals(m) && java.util.Arrays.equals(PieceLayout.parseAlign(back.get(key)), new int[]{1, 2}) && PieceLayout.parseAlign("x,1") == null
                && PieceLayout.parseAlign("99,1") == null, "alignment overrides round trip");
        check(key.equals(PieceLayout.alignKey("walls/tower/tower5.blueprint", 0, "walls/segment/segment5.blueprint", 2, 3)), "key relative to the run's direction");
        // Drafts: read before the saved value, never part of what is written; Save skips drafts that would break blocks.
        var saved = new java.util.LinkedHashMap<>(m);
        var drafts = java.util.Map.of(key, "3,1", "align|x|0|y|0", "0,0");
        check("3,1".equals(com.volcanicmg.mccitybuilder.planner.PieceSets.alignLookup(drafts, saved, key))
                && "1,2".equals(com.volcanicmg.mccitybuilder.planner.PieceSets.alignLookup(java.util.Map.of(), saved, key)), "a draft wins over the saved alignment");
        String file = com.volcanicmg.mccitybuilder.planner.PieceSets.writeOverrides(saved);
        check(!file.contains("3,1") && !file.contains("align|x|0|y|0"), "drafts are never written");
        var ok = com.volcanicmg.mccitybuilder.planner.PieceSets.saveable(java.util.Map.of("a", new int[]{1, 0}, "b", new int[]{0, 2}), java.util.Set.of("b"));
        check(ok.size() == 1 && ok.containsKey("a"), "save skips drafts that would break blocks");
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        PieceLayout.Grid tw = PackPieces.grid("medievaloak/walls/tower/tower5.blueprint"), sg0 = PackPieces.grid("medievaloak/walls/segment/segment5.blueprint");
        int d = 0;
        PieceLayout.Grid sg = sg0.turned(PieceLayout.turns(sg0.sx(), sg0.sz(), d));
        int[] c = {0, 0}, jb = PieceLayout.centred(c, tw.sx(), tw.sz());
        var auto = PieceLayout.fitNow(c, d, jb, tw, sg, 6, 9, 6);
        // A safe and a destructive hand setting.
        int[] safe = null, bad = null;
        for (int dp = 0; dp < 4; dp++)
            for (int sh = -3; sh <= 3; sh++) {
                boolean de = PieceLayout.destructiveAt(c, d, jb, tw, sg, 6, 9, sh, dp);
                if (!de && safe == null && (sh != auto.shift() || dp != auto.depth())) safe = new int[]{sh, dp};
                if (de && bad == null) bad = new int[]{sh, dp};
            }
        if (safe == null) safe = new int[]{2, -1};
        int[][] set = {safe};
        PieceLayout.setAlign((j, s2, dd) -> j == tw && s2 == sg && dd == 0 ? set[0] : null);
        var f = PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6);
        int si = sg.inset(2), edge = PieceLayout.reach(c, d, jb);
        check(f.shift() == safe[0] && f.depth() == safe[1] && f.off() == (safe[1] < 0 ? edge : edge - safe[1] - si), "override used exactly as set: " + f);
        check(java.util.Arrays.equals(PieceLayout.attach(c, d, jb, tw, sg, 6, 9, 6), new int[]{f.off(), f.shift()}), "attach follows the override");
        if (bad != null) {
            set[0] = bad;
            check(PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6).equals(auto), "a destructive override is refused (auto is used)");
        }
        PieceLayout.setAlign(null);
        check(PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6).equals(auto), "reset restores auto");
    }

    private static void overlays() {
        check(com.volcanicmg.mccitybuilder.planner.Overlays.showCitizen(true, true) && !com.volcanicmg.mccitybuilder.planner.Overlays.showCitizen(false, false)
                && !com.volcanicmg.mccitybuilder.planner.Overlays.showCitizen(true, false), "citizen markers only at live entities (no stale last-known spots)");
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

    private static void undoRestore() {
        // Undo keeps what is built / on its way, drops snapshot runs over it, brings taken-down walls back as plans.
        Plan.Line a = new Plan.Line("wall", 0, 0, 10, 0, "p", "f", 1, 0, false, false), b = new Plan.Line("wall", 10, 0, 10, 8, "p", "f", 1, 0, false, false);
        Plan.Line builtB = b.asBuilt(), c = new Plan.Line("wall", 10, 2, 10, 6, "p", "f", 1, 0, false, false);
        Plan.Line gone = new Plan.Line("wall", 0, 5, 0, 9, "p", "f", 1, 0, false, false).asBuilt();
        java.util.Set<Plan.Line> sending = java.util.Set.of(a);
        java.util.function.Predicate<Plan.Line> locked = l -> l.built() || sending.contains(l);
        // Snapshot: a (now sending), c (its cells now under built b), gone (built, taken down since).
        List<Plan.Line> r = Plan.restore(List.of(a, c, gone), List.of(a, builtB), locked, l -> l.equals(gone));
        check(r.equals(List.of(a, gone.asPlanned(), builtB)), "undo restore: " + r);
        check(Plan.restore(List.of(gone), List.of(), locked, l -> false).equals(List.of(gone)), "built record dropped without a removal: back as built");
        // Touching a built wall at one joint cell is fine; a run along it is not.
        Plan.Line touch = new Plan.Line("wall", 10, 8, 20, 8, "p", "f", 1, 0, false, false);
        check(Plan.restore(List.of(touch), List.of(builtB), locked, l -> false).contains(touch), "joint cell shared: kept");
        check(Plan.sharedCells(c, builtB) == 5 && Plan.sharedCells(touch, builtB) == 1, "shared cells");
    }

    private static void snapshots() {
        // File names: a fixed-length hash, nothing a key could use to leave the folder.
        String n = com.volcanicmg.mccitybuilder.planner.PasteRules.snapshotName("minecraft:overworld|3|../../evil|..\\x|1,2,3|0");
        check(n.matches("[0-9a-f]{40}\\.nbt"), "snapshot file name: " + n);
        check(!n.equals(com.volcanicmg.mccitybuilder.planner.PasteRules.snapshotName("minecraft:overworld|3|p|a|1,2,3|0"))
                && n.equals(com.volcanicmg.mccitybuilder.planner.PasteRules.snapshotName("minecraft:overworld|3|../../evil|..\\x|1,2,3|0")), "snapshot names: stable, per key");
        // Restore: only cells the paste may have changed, never under another live placement, a hut or an entity's support.
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.restore(false, false, false, false), "an ordinary cell comes back");
        check(!com.volcanicmg.mccitybuilder.planner.PasteRules.restore(true, false, false, false) && !com.volcanicmg.mccitybuilder.planner.PasteRules.restore(false, true, false, false)
                && !com.volcanicmg.mccitybuilder.planner.PasteRules.restore(false, false, true, false) && !com.volcanicmg.mccitybuilder.planner.PasteRules.restore(false, false, false, true),
                "placeholder, kept, under another placement or kept now: left alone");
        // Three placements stacked on one cell (terrain T, then A, B, C pasted in turn), once with B pasting a
        // substitution there (it leaves the cell, so its snapshot holds nothing for it): deleted in any order, each step
        // shows the newest still standing that changed the cell (or T), as restore (only with nothing newer having
        // changed the cell) and hand-over (to the oldest newer one that did) do it.
        for (boolean bSubstitutes : new boolean[]{false, true})
            for (int[] order : new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}}) {
                boolean[] changes = {true, !bSubstitutes, true}, live = {true, true, true};
                String[] block = {"A", "B", "C"}, snap = new String[3];
                String world = "T";
                for (int x = 0; x < 3; x++)
                    if (changes[x]) {
                        snap[x] = world;
                        world = block[x];
                    }
                for (int x : order) {
                    if (snap[x] != null) {
                        boolean[] changed = new boolean[3 - x - 1];
                        boolean newerChanged = false;
                        for (int y = x + 1; y < 3; y++) {
                            changed[y - x - 1] = live[y] && snap[y] != null;
                            newerChanged |= changed[y - x - 1];
                        }
                        if (!newerChanged) world = snap[x];
                        else snap[x + 1 + com.volcanicmg.mccitybuilder.planner.PasteRules.handOverTarget(changed)] = snap[x];
                    }
                    live[x] = false;
                    String want = "T";
                    for (int y = 0; y < 3; y++) if (live[y] && changes[y]) want = block[y];
                    check(world.equals(want), "stacked placements" + (bSubstitutes ? " (B substitutes)" : "") + " deleted in order "
                            + java.util.Arrays.toString(order) + ": " + world + " should be " + want);
                }
            }
        List<int[]> live = List.of(new int[]{0, 60, 0, 4, 66, 2}, new int[]{10, 60, 10, 12, 61, 12});
        check(com.volcanicmg.mccitybuilder.planner.PasteRules.inAny(live, 4, 66, 2) && com.volcanicmg.mccitybuilder.planner.PasteRules.inAny(live, 11, 60, 10)
                && !com.volcanicmg.mccitybuilder.planner.PasteRules.inAny(live, 5, 63, 1) && !com.volcanicmg.mccitybuilder.planner.PasteRules.inAny(List.of(), 0, 0, 0),
                "live footprints, inclusive");
    }

    private static void groundOffsets() throws Exception {
        // A joint next to built segments takes their level (built one lower by an older rule), not the highest...
        int F = PieceLayout.FOLLOWS, FL = PieceLayout.FLAT, B = PieceLayout.BUILT;
        check(PieceLayout.jointLevel(65, new int[]{64, 65, 66}, new int[]{B, F, F}) == 64, "joint at the built neighbour's level");
        check(PieceLayout.jointLevel(65, new int[]{64, 66}, new int[]{F, F}) == 66, "no built neighbour: the highest");
        // ...unless a flat run (which doesn't move) is higher: then the joint meets it.
        check(PieceLayout.jointLevel(65, new int[]{64, 67}, new int[]{B, FL}) == 67 && PieceLayout.jointLevel(65, new int[]{64, 63}, new int[]{B, FL}) == 64,
                "a higher flat run wins over the built level");
        // A smoothed run set down to a built joint follows one block per piece both ways; a follow run isn't smoothed.
        check(java.util.Arrays.equals(PieceLayout.setSmooth(new int[]{70, 70, 69, 66}, 1, 66), new int[]{67, 66, 67, 66}), "smooth run set down: "
                + java.util.Arrays.toString(PieceLayout.setSmooth(new int[]{70, 70, 69, 66}, 1, 66)));
        // A built segment's run piece nearest each end feeds the joint there.
        List<int[]> rb = List.of(new int[]{0, 0, 3, 2}, new int[]{4, 0, 7, 2}, new int[]{8, 0, 11, 2});
        check(PieceLayout.nearest(rb, new int[]{13, 1}) == 2 && PieceLayout.nearest(rb, new int[]{-2, 1}) == 0 && PieceLayout.nearest(List.of(), new int[]{0, 0}) == -1,
                "nearest run piece to a segment end");
        int A = PieceLayout.Grid.AIR;
        // A road: substitution layer, then its surface, then a lamp cell: the surface replaces the terrain's top block.
        int[] road = {0, 0, 0, 5, 5, 5, A, 7, A};
        check(PieceLayout.groundVote(3, 3, 1, road, 0, null, true) == 0, "road surface flush: anchor 1 below the default");
        check(PieceLayout.groundVote(3, 3, 1, road, 2, null, true) == 2, "road anchored on its lamp: offset 2");
        check(PieceLayout.groundVote(3, 3, 1, road, 0, 1, true) == 2, "a road's tagged layer is its surface");
        check(PieceLayout.groundVote(3, 2, 1, new int[]{A, 5, A, A, A, A}, 0, null, true) == null, "no full layer: no vote");
        // A wall: four full foundation layers, anchored at its top (py 6): stands on them; anchored low, the default.
        int[] wall = new int[3 * 8];
        java.util.Arrays.fill(wall, A);
        for (int i = 0; i < 12; i++) wall[i] = i % 2;
        for (int y = 4; y < 8; y++) wall[y * 3 + 1] = 5;
        check(PieceLayout.groundVote(3, 8, 1, wall, 6, null, false) == 3, "buried wall: foundation top just under the terrain");
        check(PieceLayout.groundVote(3, 8, 1, wall, 5, null, false) == 1, "two layers deep: the pack's own foundation, default");
        // Groups: road pieces sit by their own vote; walls and slopes move by their group's shift, ties to the smaller.
        Map<String, Integer[]> votes = new java.util.HashMap<>(Map.of(
                "p/walls/long1.blueprint", new Integer[]{1, 5}, "p/walls/corner1.blueprint", new Integer[]{1, 5}, "p/walls/gate1.blueprint", new Integer[]{1, 1},
                "p/walls/slope1.blueprint", new Integer[]{1, null}, "p/walls/long2.blueprint", new Integer[]{1, 2},
                "p/infrastructure/roads/straight1.blueprint", new Integer[]{1, 3}, "p/infrastructure/roads/corner1.blueprint", new Integer[]{1, 2},
                "p/infrastructure/roads/stairs1.blueprint", new Integer[]{1, 9}));
        java.util.Set<String> own = new java.util.HashSet<>();
        for (String k : votes.keySet()) if (PieceLayout.ownGround(k)) own.add(k);
        Map<String, Integer> o = PieceLayout.groundOffsets(votes, own);
        check(o.get("p/walls/gate1.blueprint") == 5 && o.get("p/walls/slope1.blueprint") == 5 && o.get("p/walls/long2.blueprint") == 2, "walls move by their group: " + o);
        check(o.get("p/infrastructure/roads/straight1.blueprint") == 3 && o.get("p/infrastructure/roads/corner1.blueprint") == 2
                && o.get("p/infrastructure/roads/stairs1.blueprint") == 2, "roads by their own vote, stairs by the group (tie: smaller): " + o);
        // The real packs: every flat road piece the line tool lays has its surface just above ground (replacing the
        // terrain's top block); Medieval Oak walls (confirmed right in game) keep Structurize's default.
        int roads = 0;
        for (String full : PackPieces.pieces()) {
            String in = full.substring(full.indexOf('/') + 1);
            PieceLayout.Grid g = PackPieces.grid(full);
            if (full.startsWith("medievaloak/walls/")) check(g.ground() == 6, "medieval oak wall ground: " + full + " " + g.ground());
            if (!PieceLayout.ownGround(in)) continue;
            int area = g.sx() * g.sz(), top = -1;
            for (int y = 0; y < g.sy(); y++) {
                int filled = 0, solid = 0;
                for (int i = y * area; i < (y + 1) * area; i++) {
                    if (g.ids()[i] != PieceLayout.Grid.AIR) filled++;
                    if (g.ids()[i] > 0) solid++;
                }
                if (filled * 20 >= area * 19 && solid * 4 >= area) top = y;
            }
            if (top < 0 || full.startsWith("darkoak/")) continue;
            check(top == g.ground() + 1, "road surface replaces the terrain's top block: " + full + " surface " + top + " ground " + g.ground());
            roads++;
        }
        check(roads > 300, "flat road pieces checked: " + roads);
        check(!PieceLayout.ownGround("infrastructure/roads/nice/nice_bridge_segment.blueprint") && PieceLayout.ownGround("infrastructure/roads/nice/nice_straight.blueprint"),
                "a bridge's deck isn't its road surface");
    }

    private static void hintsAndLang() throws Exception {
        String lang = Files.readString(Path.of("src/main/resources/assets/mccitybuilder/lang/en_us.json"));
        for (String id : List.of("view", "browse", "placing", "adjusting", "selected", "line"))
            check(Hints.SECTIONS.containsKey(id) && Hints.SECTIONS.get(id).size() <= Hints.MAX_ROWS, "hints section " + id);
        for (String k : Hints.langKeys()) check(lang.contains("\"" + k + "\":"), "lang key: " + k);
        for (String c : HutCatalog.CATEGORIES) check(lang.contains("\"mccitybuilder.category." + c + "\":"), "category lang: " + c);
        java.util.function.Function<String, String> tr = k -> {
            int a = lang.indexOf("\"" + k + "\":");
            if (a < 0) return "J";
            int b = lang.indexOf('"', lang.indexOf(':', a) + 1) + 1;
            return lang.substring(b, lang.indexOf('"', b));
        };
        for (String id : List.of("browse", "placing", "adjusting", "selected", "line")) {
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
