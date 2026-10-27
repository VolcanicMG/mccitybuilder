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

    /** Road / wall tools: snapping, chaining and branching, wall joints, height, gates, battlements, segments. */
    private static void lines() throws Exception {
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
        // Profile alignment: a wall face whose solid rows sit 2 to the side is shifted back onto the corner's face.
        java.util.Set<Long> corner = new java.util.HashSet<>(), straight = new java.util.HashSet<>();
        for (int y = 0; y < 5; y++)
            for (int l = 0; l < 3; l++) {
                corner.add((long) y * 65536 + 30000 + l);
                straight.add((long) y * 65536 + 30000 + l - 2);
            }
        check(com.volcanicmg.mccitybuilder.planner.PieceLayout.bestShift(corner, straight, 3) == 2
                && com.volcanicmg.mccitybuilder.planner.PieceLayout.jaccard(corner, straight, 2) == 1.0, "profile alignment shift");
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
        List<String> exact = new java.util.ArrayList<>(), gapped = new java.util.ArrayList<>();
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
                (worst == 0 ? exact : gapped).add(en.getKey() + " " + fam.key().substring(fam.key().indexOf(':') + 1) + (worst == 0 ? "" : " " + worst + " at " + worstOf));
            }
        check(checked > 100, "bundled joints checked: " + checked);
        System.out.println("CityBuilder self-check: runs meet the joint body exactly: " + exact);
        System.out.println("CityBuilder self-check: runs keep a gap (blocks) to the joint body: " + gapped);
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
        check(PieceLayout.jointLevel(64, new int[]{66, 70}) == 70 && PieceLayout.jointLevel(64, new int[]{}) == 64, "joint = highest neighbour");
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
