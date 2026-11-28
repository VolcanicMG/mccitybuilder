package com.volcanicmg.mccitybuilder;

import com.volcanicmg.mccitybuilder.planner.CameraMath;
import com.volcanicmg.mccitybuilder.planner.ColonyGeometry;
import com.volcanicmg.mccitybuilder.planner.Costs;
import com.volcanicmg.mccitybuilder.planner.Materials;
import com.volcanicmg.mccitybuilder.planner.QueueIcons;
import com.volcanicmg.mccitybuilder.planner.DroneCamera;
import com.volcanicmg.mccitybuilder.planner.PlacementState;
import com.volcanicmg.mccitybuilder.planner.Hints;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.QueueOrder;
import com.volcanicmg.mccitybuilder.planner.TerrainFollow;
import com.volcanicmg.mccitybuilder.planner.CameraRig;
import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.JointCount;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.planner.Route;
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
        materials();
        catalog();
        overlays();
        plans();
        roads();
        lines();
        groundOffsets();
        snapshots();
        hintsAndLang();
        pieceSetDetection();
        builtInSettings();
        routes();
        System.out.println("CityBuilder self-check passed");
    }

    private static void materials() {
        Map<String, Integer> roads = Costs.newMap(), huts = Costs.newMap();
        Costs.add(roads, "minecraft:stone_bricks", 100);
        Costs.add(roads, "minecraft:stone_bricks", 40);
        Costs.add(roads, "minecraft:gravel", 10);
        Costs.add(huts, "minecraft:stone_bricks", 60);
        Costs.add(huts, "minecraft:oak_planks", 30);
        Costs.add(huts, "minecraft:dirt", 0);
        Map<String, Integer> have = Map.of("minecraft:stone_bricks", 150, "minecraft:oak_planks", 40);
        // Built pieces never reach the maps (the caller skips built lines), so "built excluded" is by construction.
        check(Materials.merge(roads, huts).get("minecraft:stone_bricks") == 200 && !Materials.merge(roads, huts).containsKey("minecraft:dirt"), "materials: merge sums items");
        Materials.View all = Materials.view(roads, huts, Materials.Tab.ALL, have);
        check(all.need() == 240 && all.missing() == 50 + 10, "materials: all tab totals and shortfall " + all.need() + "/" + all.missing());
        check(all.rows().get(0).item().equals("minecraft:stone_bricks") && all.rows().get(0).missing() == 50, "materials: most missing first");
        check(all.rows().get(all.rows().size() - 1).missing() == 0, "materials: extra stock is no negative shortfall");
        Materials.View r = Materials.view(roads, huts, Materials.Tab.ROADS, have), h = Materials.view(roads, huts, Materials.Tab.HUTS, have);
        check(r.need() == 150 && r.missing() == 10 && r.rows().size() == 2, "materials: roads tab has road pieces only");
        check(h.need() == 90 && h.missing() == 0 && h.rows().size() == 2, "materials: huts tab has huts only, covered by stock");
        check(Materials.view(Map.of(), Map.of(), Materials.Tab.ALL, have).rows().isEmpty(), "materials: nothing planned, no rows");
        check(HutCatalog.levelPaths("fundamentals/baker3.blueprint", 3, true).equals(List.of("fundamentals/baker1.blueprint", "fundamentals/baker2.blueprint", "fundamentals/baker3.blueprint")), "materials: a level-3 hut is built through levels 1-3");
        check(HutCatalog.levelPaths("fundamentals/baker1.blueprint", 1, true).size() == 1 && HutCatalog.levelPaths("decos/bench3.blueprint", 3, false).size() == 1, "materials: level 1 and decorations count their own blueprint");
        check(Materials.allowed(null, 0, 2000) && !Materials.allowed(1000L, 2500, 2000) && Materials.allowed(1000L, 3000, 2000), "materials: one request per 2 s");
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

    /** Fails if the drawn camera is inside, or closer than the floor gap above, the surface right around it. */
    private static void aboveSurface(com.volcanicmg.mccitybuilder.planner.CameraRig r, Terrain w, String what) {
        DroneCamera d = r.drone;
        int cx = (int) Math.floor(d.camX()), cz = (int) Math.floor(d.camZ());
        // The hard floor's gap right under it (the full clearance is eased in on top).
        check(d.y() >= w.surface(cx, cz) + CameraRig.FLOOR_GAP - 1e-6, what + ": below surface + floor gap at y " + d.y());
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) check(d.y() > w.surface(cx + dx, cz + dz), what + ": inside a block at y " + d.y());
    }

    /**
     * The real per-frame loop end to end (CameraRig: baseline sampling, follow, ceiling, step with the hard floor) over
     * synthetic terrain: open, scroll fully out (baseline + 160 within 3 s), scroll fully in (stops at max(baseline,
     * local surface) + minHeight), never inside the ground; on flat land, a hill, a tower; and panning at normal and
     * fast speed into a 60-block cliff wall while low.
     */
    /** Per-frame smoothness of one camera run: max |second difference| of drawn x / y / z, dy sign flips, holds, speeds. */
    private record Jerk(double x, double y, double z, int flips, double minSpeed, double maxSpeed, double lowY, double highY, double minR, double maxR) {
        @Override
        public String toString() {
            return String.format("jerk x %.4f y %.4f z %.4f, dy flips %d, h speed %.2f..%.2f, y %.1f..%.1f", x, y, z, flips, minSpeed, maxSpeed, lowY, highY)
                    + (Double.isNaN(minR) ? "" : String.format(", orbit radius %.2f..%.2f", minR, maxR));
        }
    }

    /**
     * Runs the rig for {@code frames} at 60 fps with fixed input (orbiting the screen-centre ground point when
     * {@code turn} is set, the centre ground read off the terrain like the client's ray), after a 2 s settle.
     */
    private static Jerk jerk(com.volcanicmg.mccitybuilder.planner.CameraRig r, Terrain w, int frames, double ahead, double turn, String what) {
        double dt = 1 / 60.0;
        java.util.function.Supplier<Double> centre = () -> {
            double[] c = r.drone.centreOn(64);
            return c == null ? Double.NaN : (double) w.ground((int) Math.floor(c[0]), (int) Math.floor(c[2]));
        };
        for (int i = 0; i < 120; i++) r.frame(w, dt, 0, 0, 0, null, 1.2, centre.get());
        double[] pivot = turn != 0 ? r.drone.centreOn(64) : null;
        List<double[]> p = new java.util.ArrayList<>();
        double minR = Double.NaN, maxR = Double.NaN;
        for (int i = 0; i < frames; i++) {
            r.frame(w, dt, 0, ahead, turn, pivot, 1.2, centre.get());
            if (pivot != null) {
                double rad = Math.hypot(pivot[0] - r.drone.x, pivot[2] - r.drone.z);
                minR = Double.isNaN(minR) ? rad : Math.min(minR, rad);
                maxR = Double.isNaN(maxR) ? rad : Math.max(maxR, rad);
            }
            aboveSurface(r, w, what);
            p.add(new double[]{r.drone.camX(), r.drone.y(), r.drone.camZ()});
        }
        double[] j = new double[3];
        int flips = 0;
        double lastDy = 0, minS = Double.MAX_VALUE, maxS = 0, lowY = Double.MAX_VALUE, highY = -Double.MAX_VALUE;
        for (double[] q : p) {
            lowY = Math.min(lowY, q[1]);
            highY = Math.max(highY, q[1]);
        }
        for (int i = 1; i < p.size() - 1; i++) {
            for (int k = 0; k < 3; k++) j[k] = Math.max(j[k], Math.abs(p.get(i + 1)[k] - 2 * p.get(i)[k] + p.get(i - 1)[k]));
            double dy = p.get(i)[1] - p.get(i - 1)[1];
            if (Math.abs(dy) > 1e-4) {
                if (lastDy != 0 && Math.signum(dy) != Math.signum(lastDy)) flips++;
                lastDy = dy;
            }
            // Speed after the first half second (acceleration).
            if (i > 30) {
                double s = Math.hypot(p.get(i)[0] - p.get(i - 1)[0], p.get(i)[2] - p.get(i - 1)[2]) / dt;
                minS = Math.min(minS, s);
                maxS = Math.max(maxS, s);
            }
        }
        Jerk out = new Jerk(j[0], j[1], j[2], flips, minS, maxS, lowY, highY, minR, maxR);
        System.out.println("CityBuilder self-check: camera " + what + ": " + out);
        return out;
    }

    /**
     * Jitter near terrain and the border: orbits (Q/E) low beside a step and over bumpy ground, an orbit pushing
     * against the colony box, a pan along a jagged ridge; and the pan speed over flat ground vs toward a hill.
     */
    private static void cameraJitter() {
        // At the default lowest zoom (10 above the baseline), beside things taller than the clearance allows. Limits:
        // drawn acceleration in blocks / frame² at 60 fps (0.08 ≈ 290 blocks/s²; a whole-block snap is >= 1), no
        // height wobble (at most 2 direction changes: up, down), no held / bursting horizontal speed.
        java.util.function.IntBinaryOperator trees = (x, z) -> Math.floorMod(x * 73856093 ^ z * 19349663, 11) == 0 ? 64 + 6 + Math.floorMod(x * 31 + z * 17, 4) : 64;
        List<Jerk> smooth = new java.util.ArrayList<>();
        for (int step : new int[]{8, 12}) {
            Terrain cliff = new Terrain((x, z) -> x >= 0 ? 64 + step : 64, (x, z) -> 64);
            smooth.add(jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(-5, 64 + 10, 0, 0)), cliff, 240, 0, 90, "orbit beside a " + step + "-block step"));
        }
        smooth.add(jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 10, 0, 0)), new Terrain((x, z) -> 64, trees), 240, 0, 90, "orbit over trees"));
        Terrain hall = new Terrain((x, z) -> x >= 8 && x <= 14 && z >= 3 && z <= 9 ? 64 + 24 : 64, (x, z) -> 64);
        smooth.add(jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 10, 0, 0)), hall, 300, 0, 90, "orbit beside a 24-block building"));
        // Wide and fast, low over a canopy (35 blocks out at 30°, ~55 blocks/s, a few blocks above the trees, where the
        // drawn speed cap is only 32): the cap doesn't hold the orbit back.
        var wide = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 20, 0, 0));
        wide.drone.pitch = 30;
        Jerk wj = jerk(wide, new Terrain((x, z) -> 64, (x, z) -> 80), 240, 0, 90, "wide fast orbit over a canopy");
        smooth.add(wj);
        check(wj.minSpeed() > 50, "the drawn camera lags a fast orbit: " + wj);
        Terrain ridge = new Terrain((x, z) -> 64, (x, z) -> x == 2 + Math.floorMod(z / 3, 2) ? 64 + (Math.floorMod(z, 5) < 2 ? 9 : 6) : 64);
        smooth.add(jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 10, 0, 0)), ridge, 300, 1, 0, "pan along a ridge"));
        smooth.add(jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 10, 0, 0)), new Terrain((x, z) -> 64, trees), 300, 1, 0, "pan over trees"));
        for (Jerk j : smooth) {
            // Pans start from rest: their first frames' acceleration (0.034) is the intended ease-in.
            check(j.x() <= 0.04 && j.y() <= 0.08 && j.z() <= 0.04, "camera jerks near terrain: " + j);
            check(j.flips() <= 2, "camera height wobbles near terrain: " + j);
            check(j.minSpeed() >= 0.95 * j.maxSpeed(), "camera held or burst sideways near terrain: " + j);
        }
        // Orbiting into the colony box: the camera slides along the edge at the same distance from the pivot.
        var edge = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(15, 64 + 20, 0, 0));
        edge.drone.box = new double[]{-20, -20, 20, 20};
        Jerk e = jerk(edge, new Terrain((x, z) -> 64, (x, z) -> 64), 240, 0, 90, "orbit against the box edge");
        check(e.maxR() - e.minR() < 0.01 && e.y() < 1e-9, "orbit at the box edge changes its radius or height: " + e);
        // Pan speed comes from the height above the terrain, not the screen-centre hit: looking at a hill doesn't slow it.
        Jerk flat = jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 12, 0, 0)), new Terrain((x, z) -> 64, (x, z) -> 64), 240, 1, 0, "pan over flat land");
        Terrain hill = new Terrain((x, z) -> (int) Math.round(64 + 14 * Math.exp(-(x * x + (z - 40) * (z - 40)) / 300.0)), (x, z) -> 64);
        Jerk h = jerk(new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(-14, 64 + 12, 0, 0)), hill, 240, 1, 0, "pan past a hill");
        check(h.minSpeed() >= 0.95 * flat.maxSpeed(), "panning toward a hill slows the camera: " + h + " vs flat " + flat);
        // Panning low toward a step at uneven frame times (with a 0.1 s hitch): the clearance never climbs past the most
        // it was asked for, and the height doesn't bob (at most one rise and one settle).
        for (int step : new int[]{8, 12}) {
            Terrain cliff = new Terrain((x, z) -> z >= 20 ? 64 + step : 64, (x, z) -> 64);
            var r = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 10, 0, 0));
            double[] dts = {1 / 60.0, 1 / 30.0, 1 / 144.0, 1 / 60.0, 1 / 45.0};
            double maxNeed = 0, maxClear = 0, py = r.drone.y(), lastDy = 0;
            int flips = 0;
            for (int i = 0; i < 400; i++) {
                double dt = i == 150 ? 0.1 : dts[i % dts.length];
                r.frame(cliff, dt, 0, i < 200 ? 1 : 0, 0, null, 1.2, 64);
                aboveSurface(r, cliff, "pan to a " + step + "-block step");
                maxNeed = Math.max(maxNeed, r.localSurface(cliff, dt) + 0.5 + DroneCamera.CLEARANCE - r.drone.altitude);
                maxClear = Math.max(maxClear, r.drone.clearance);
                double dy = r.drone.y() - py;
                py = r.drone.y();
                if (Math.abs(dy) > 1e-3) {
                    if (lastDy != 0 && Math.signum(dy) != Math.signum(lastDy)) flips++;
                    lastDy = dy;
                }
            }
            System.out.printf("CityBuilder self-check: camera pan to a %d-block step, uneven frames: clearance peak %.2f (most needed %.2f), dy flips %d%n", step, maxClear, maxNeed, flips);
            check(maxClear <= maxNeed + 0.05, "clearance overshoots at a " + step + "-block step: " + maxClear + " > " + maxNeed);
            check(flips <= 2, "height bobs at a " + step + "-block step: " + flips + " flips");
        }
        // Column reads per frame while panning fast (the windows overlap; each column is read once per frame).
        int[] reads = {0};
        Terrain counting = new Terrain((x, z) -> {
            reads[0]++;
            return 64;
        }, (x, z) -> 64);
        var cr = new com.volcanicmg.mccitybuilder.planner.CameraRig(new DroneCamera(0, 64 + 30, 0, 0));
        for (int i = 0; i < 60; i++) cr.frame(counting, 1 / 60.0, 0, 1, 0, null, 3.6, 64);
        reads[0] = 0;
        for (int i = 0; i < 60; i++) cr.frame(counting, 1 / 60.0, 0, 1, 0, null, 3.6, 64);
        System.out.println("CityBuilder self-check: camera column reads per frame, fast pan: " + reads[0] / 60);
        check(reads[0] / 60 < 1500, "camera reads too many columns per frame: " + reads[0] / 60);
        // The clearance alone, for a need that appears at once (a target jump next to something tall): no overshoot.
        for (double gap : new double[]{3, 6, 10, 20}) {
            DroneCamera d = new DroneCamera(0, 74, 0, 0);
            double peak = 0;
            for (int i = 0; i < 300; i++) {
                double dt = i == 20 ? 0.1 : i % 2 == 0 ? 1 / 60.0 : 1 / 30.0;
                d.update(dt, 0, 0, 1, 64, 74 - DroneCamera.CLEARANCE + gap, 64);
                peak = Math.max(peak, d.clearance);
            }
            // ...and the release back to open ground settles, no stop dead at zero.
            double prev = d.clearance, prevV = 0, kick = 0;
            for (int i = 0; i < 300; i++) {
                d.update(1 / 60.0, 0, 0, 1, 64, 64, 64);
                double v = d.clearance - prev;
                kick = Math.max(kick, Math.abs(v - prevV));
                prev = d.clearance;
                prevV = v;
            }
            System.out.printf("CityBuilder self-check: clearance step of %.0f: peak %.3f, release max accel %.4f blocks/frame², left %.3f%n", gap, peak, kick, d.clearance);
            check(peak <= gap + 0.05, "clearance overshoots a step of " + gap + ": " + peak);
            check(kick <= 0.08 && d.clearance < 0.01, "clearance release jerks or doesn't settle at a step of " + gap + ": " + kick);
        }
    }

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
        cameraJitter();
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
        // Free materials: only an order made after the mark, the first such, never one another mark holds.
        check(QueueOrder.fundedOrder(10, List.of(4, 10), java.util.Set.of()) == 0, "older orders at the site aren't funded");
        check(QueueOrder.fundedOrder(10, List.of(14, 12, 9), java.util.Set.of()) == 12, "first order after the mark");
        check(QueueOrder.fundedOrder(10, List.of(14, 12), java.util.Set.of(12)) == 14, "an order another mark funds is skipped");
        check(QueueOrder.fundedOrder(0, List.of(), java.util.Set.of()) == 0, "no order yet");
        // A mark from a sent request goes soon (a closed window's mark mustn't fund a later stranger's order for long).
        check(QueueOrder.fundExpired(false, 61_000) && !QueueOrder.fundExpired(false, 30_000), "a direct mark waits about a minute");
        check(!QueueOrder.fundExpired(true, 61_000) && QueueOrder.fundExpired(true, 301_000), "a window's mark waits five minutes");
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
        check(!ColonyGeometry.beyondRender(0, 0, 8, false) && !ColonyGeometry.beyondRender(7, 0, 8, false) && ColonyGeometry.beyondRender(8, 0, 8, false)
                && ColonyGeometry.beyondRender(5, 5, 8, false) && !ColonyGeometry.beyondRender(4, 5, 8, false) && ColonyGeometry.beyondRender(1, 0, 1, false),
                "beyond render: outside the circle of radius rd - 1 around the camera's chunk (the edge ring counts as not drawn)");
        check(ColonyGeometry.beyondRender(7, 0, 8, true) && !ColonyGeometry.beyondRender(6, 0, 8, true) && !ColonyGeometry.beyondRender(7, 0, 8, false),
                "beyond render hysteresis: once far, near again only inside rd - 2");
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
        // Level targets: their own rows, invisible to the entry / line readers.
        String withT = Plan.write(List.of(a), List.of(), Map.of(), Map.of(), List.of(new int[]{10, 64, 10, 3}, new int[]{1, 2, 3, 99}));
        var ts = Plan.readTargets(withT + "T\tx\t1\t2\t3\n");
        check(ts.size() == 1 && java.util.Arrays.equals(ts.get(0), new int[]{10, 64, 10, 3, 0, 0, 0}), "targets round trip, bad levels and rows skipped");
        // A target's builder: its own TB row (older builds keep the T row and skip it); older files read as any builder.
        String withB = Plan.write(List.of(a), List.of(), Map.of(), Map.of(), List.of(new int[]{10, 64, 10, 3, 20, 65, -4}, new int[]{30, 64, 30, 2, 0, 0, 0}));
        var tb = Plan.readTargets(withB + "TB\t30\t64\t30\tx\t1\t1\n");
        check(tb.size() == 2 && java.util.Arrays.equals(tb.get(0), new int[]{10, 64, 10, 3, 20, 65, -4}) && java.util.Arrays.equals(tb.get(1), new int[]{30, 64, 30, 2, 0, 0, 0})
                && withB.split("\nTB\t", -1).length == 2, "target builders round trip, any builder writes no TB row, a damaged TB row is skipped");
        check(withB.lines().filter(l -> l.startsWith("T\t")).allMatch(l -> l.split("\t").length == 5), "T rows keep 5 fields (older builds still read them)");
        check(Plan.read(withT).equals(List.of(a)) && Plan.readLines(withT).isEmpty(), "target rows don't disturb entries or lines");
        check(Plan.readTargets("not a plan\nT\t1\t2\t3\t4\n").isEmpty(), "targets need the plan header");
        // Shared plans: what a server keeps from a client.
        check(Plan.shareable(a, 0, 0, 200, -64, 320) && Plan.shareable(b, 0, 0, 200, -64, 320), "plain entries are shareable");
        check(!Plan.shareable(a, 1000, 0, 200, -64, 320), "an entry far from the colony isn't");
        check(!Plan.shareable(a.with("../../x.blueprint", 2, 1, a.box()), 0, 0, 200, -64, 320), "a path out of the pack isn't");
        check(!Plan.shareable(a.with(a.path(), 2, 9, a.box()), 0, 0, 200, -64, 320), "a bad turn isn't");
        check(!Plan.shareable(a.with(a.path(), 2, 1, new int[]{5, 64, 5, 400, 72, 15}), 0, 0, 200, -64, 320), "a huge box isn't");
        check(!Plan.shareable(new Plan.Entry("Bad Id", "birch", a.path(), 2, 10, 64, 10, 1, 5, 64, 5, 14, 72, 15), 0, 0, 200, -64, 320), "a bad hut id isn't");
        var sl = new Plan.Line("road", 0, 0, 20, 0, "birch", "fancy", 1, -1, false, false);
        check(Plan.shareable(sl, 0, 0, 200) && !Plan.shareable(sl.asBuilt(), 0, 0, 200), "planned segments are shareable, built ones not");
        check(!Plan.shareable(new Plan.Line("lava", 0, 0, 20, 0, "birch", "fancy", 1, -1, false, false), 0, 0, 200), "unknown tools aren't");
        check(!Plan.shareable(sl.between(0, 0, 300, 0), 0, 0, 1000), "too long a segment isn't");
        // Tabs and newlines would split a saved row into injected ones.
        check(!Plan.shareable(new Plan.Line("road", 0, 0, 20, 0, "birch", "fancy\nL3\troad", 1, -1, false, false), 0, 0, 200), "a newline in a family isn't");
        check(!Plan.shareable(new Plan.Line("road", 0, 0, 20, 0, "birch", "fan\tcy", 1, -1, false, false), 0, 0, 200), "a tab in a family isn't");
        check(!Plan.shareable(a.with("craftsmanship/x\n-\tbirch\ty.blueprint", 2, 1, a.box()), 0, 0, 200, -64, 320), "a newline in a path isn't");
        check(!Plan.shareable(a.with("craftsmanship/x\ty.blueprint", 2, 1, a.box()), 0, 0, 200, -64, 320), "a tab in a path isn't");
        check(!Plan.wellFormed(new Plan.Entry(null, "bir\nch", a.path(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "a newline in a pack isn't");
        check(!Plan.wellFormed(new Plan.Entry(null, "bir\rch", a.path(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "a carriage return in a pack isn't");
        check(Plan.wellFormed(new Plan.Entry(null, "Bob's Café & Co (v2)", "décor/l'été.blueprint", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                && Plan.shareable(new Plan.Line("wall", 0, 0, 20, 0, "Bob's Café", "fortés (old)", 1, -1, false, false), 0, 0, 200), "apostrophes, accents, & and brackets are");
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
    /** Ethan's U: top wall open at its right end, left side, the bottom drawn from the bottom-left corner. */
    private static void autoConnect() {
        java.util.function.BiFunction<int[], int[], Plan.Line> w = (a, b) -> new Plan.Line("wall", a[0], a[1], b[0], b[1], "p", "f", 1, 0, false, false);
        List<Plan.Line> u = List.of(w.apply(new int[]{0, 0}, new int[]{20, 0}), w.apply(new int[]{0, 0}, new int[]{0, 15}));
        int[] start = {0, 15}, target = {20, 0};
        // Going north first would run back along the left side: only the bottom-right elbow.
        List<int[]> els = Plan.elbows(u, "wall", start, target, -1);
        check(els.size() == 1 && els.get(0)[0] == 20 && els.get(0)[1] == 15, "U closes through the bottom-right elbow: " + els.stream().map(java.util.Arrays::toString).toList());
        check(Plan.elbows(u, "road", start, target, -1).size() == 2, "another tool's lines don't block an elbow");
        // Collinear (or the same cell): no route, plain end snapping.
        check(Plan.elbows(u, "wall", new int[]{0, 15}, new int[]{0, 0}, -1).isEmpty() && Plan.elbows(u, "wall", new int[]{5, 15}, new int[]{20, 15}, -1).isEmpty(), "collinear target: no elbow");
        // Free field: both elbows, the preferred direction first.
        List<Plan.Line> one = List.of(w.apply(new int[]{20, 0}, new int[]{30, 0}));
        List<int[]> two = Plan.elbows(one, "wall", new int[]{0, 10}, new int[]{20, 0}, 3);
        check(two.size() == 2 && two.get(0)[0] == 0 && two.get(0)[1] == 0, "preferred first segment north: elbow (0,0) first");
        two = Plan.elbows(one, "wall", new int[]{0, 10}, new int[]{20, 0}, 0);
        check(two.size() == 2 && two.get(0)[0] == 20 && two.get(0)[1] == 10, "preferred first segment east: elbow (20,10) first");
        // The target's own arm: entering it along its line is refused (that elbow lies on it anyway).
        List<int[]> e3 = Plan.elbows(List.of(w.apply(new int[]{10, 0}, new int[]{20, 0})), "wall", new int[]{0, 10}, new int[]{20, 0}, -1);
        check(e3.size() == 1 && e3.get(0)[0] == 20 && e3.get(0)[1] == 10, "never along the target's arm: " + e3.stream().map(java.util.Arrays::toString).toList());
        // A leg crossing a wall: rejected.
        List<Plan.Line> wall = List.of(w.apply(new int[]{10, -5}, new int[]{10, 20}), w.apply(new int[]{20, 0}, new int[]{25, 0}));
        check(Plan.elbows(wall, "wall", new int[]{0, 10}, new int[]{20, 0}, -1).isEmpty(), "both routes cross the wall at x 10: none");
        // A leg lying along a wall: rejected.
        List<Plan.Line> along = List.of(w.apply(new int[]{20, 0}, new int[]{30, 0}), w.apply(new int[]{3, 10}, new int[]{12, 10}));
        List<int[]> e4 = Plan.elbows(along, "wall", new int[]{0, 10}, new int[]{20, 0}, 0);
        check(e4.size() == 1 && e4.get(0)[0] == 0 && e4.get(0)[1] == 0, "the east-first route overlaps the wall on z 10: only (0,0)");
        // A diagonal wall through a route's cell blocks that route.
        List<Plan.Line> diag = List.of(w.apply(new int[]{20, 0}, new int[]{30, 0}), w.apply(new int[]{5, 5}, new int[]{15, 15}));
        List<int[]> e5 = Plan.elbows(diag, "wall", new int[]{0, 10}, new int[]{20, 0}, 0);
        check(e5.size() == 1 && e5.get(0)[0] == 0 && e5.get(0)[1] == 0, "the diagonal crosses z 10 at x 10: only (0,0)");
        // The target, in screen space: the cursor within r GUI px of an end's marker; held out to 1.6 r; never on the
        // start's axes; ends off screen (null) never.
        List<int[]> free = List.of(new int[]{20, 0}, new int[]{0, 30}, new int[]{40, 40});
        List<double[]> px = java.util.Arrays.asList(new double[]{200, 100}, new double[]{50, 300}, null);
        int[] st = {0, 15};
        int[] t = LineSnap.connectTarget(st, new double[]{208, 104}, free, px, 11, null);
        check(t != null && t[0] == 20 && t[1] == 0, "cursor on the end's marker: connect");
        check(LineSnap.connectTarget(st, new double[]{214, 100}, free, px, 11, null) == null, "14 px off: no connect (axis / length snapping as before)");
        t = LineSnap.connectTarget(st, new double[]{214, 100}, free, px, 11, new int[]{20, 0});
        check(t != null && t[0] == 20, "connected: holds at 14 px (hysteresis)");
        check(LineSnap.connectTarget(st, new double[]{220, 100}, free, px, 11, new int[]{20, 0}) == null, "connected: lets go past 1.6 r");
        check(LineSnap.connectTarget(st, new double[]{50, 300}, free, px, 11, null) == null, "an end on the start's axis: plain end snapping");
        check(LineSnap.connectTarget(st, new double[]{208, 104}, List.of(), List.of(), 11, null) == null, "no free end (a joint, a built end): no connect");
        int[] near = LineSnap.nearestEnd(st, new double[]{218, 100}, free, px, 22);
        check(near != null && near[0] == 20, "approaching within 2 r: the end's marker is hinted");
    }

    /** Fill gaps: a straight cropped to the gap (path, window turns, window choice), the payload, the flag round-trips. */
    private static void fillGaps() {
        String cp = PieceLayout.cropPath("walls/colonial/straight.blueprint", 1, 2, 3);
        check(PieceLayout.cropBase(cp).equals("walls/colonial/straight.blueprint") && java.util.Arrays.equals(PieceLayout.cropOf(cp), new int[]{1, 2, 3}), "crop path round-trips");
        check(PieceLayout.cropOf("walls/a.blueprint") == null && PieceLayout.cropOf("walls/a.blueprint#crop:y:1:2") == null
                && PieceLayout.cropOf("walls/a.blueprint#crop:x:1") == null && PieceLayout.cropOf("walls/a.blueprint#crop:x:-1:2") == null, "whole or malformed: no crop");
        check(PieceSets.tool(cp) != null, "a crop still reads as a wall piece");
        // A window of the piece as placed (turned) is the same cells as its unturned window, cropped, then turned.
        int[] ids = new int[5 * 2 * 3];
        for (int k = 0; k < ids.length; k++) ids[k] = k + 1;
        PieceLayout.Grid g = new PieceLayout.Grid(5, 2, 3, ids, 0);
        for (int t = 0; t < 4; t++) {
            PieceLayout.Grid tg = g.turned(t);
            for (boolean alongX : new boolean[]{true, false}) {
                int len = alongX ? tg.sx() : tg.sz();
                for (int r = 1; r < len; r++)
                    for (int w = 0; w + r <= len; w++) {
                        int[] u = PieceLayout.unturnedWindow(t, alongX, w, r, 5, 3);
                        check(sameGrid(cropGrid(tg, alongX ? 0 : 1, w, r), cropGrid(g, u[0], u[1], r).turned(t)), "turned window " + t + " " + alongX + " " + w + "+" + r);
                    }
            }
        }
        // The window choice: its edges continue the neighbours (what follows lo = hi's first cell, what precedes hi = lo's last).
        int A = 2, B = 3, D = 4;
        PieceLayout.Grid s = columns(D, A, B, D, D, D), lo = columns(D, D, D, D, D, B), hi = columns(A, D, D, D, D, D);
        double[] w = PieceLayout.cropWindow(s, 2, true, lo, hi, null);
        check(w != null && w[0] == 1 && w[1] == 2, "the window whose edges continue both neighbours: " + java.util.Arrays.toString(w));
        PieceLayout.Grid flat = columns(D, D, D, D, D, D);
        check(PieceLayout.cropWindow(flat, 2, true, null, null, null)[0] == 2, "all alike: the piece's middle");
        check(PieceLayout.cropWindow(flat, 2, true, null, null, x -> x == 2 ? 1 : 0)[0] != 2, "ties: the window cutting no decoration");
        check(PieceLayout.cropWindow(flat, 6, true, null, null, null) == null && PieceLayout.cropWindow(flat, 0, true, null, null, null) == null, "no window as long as the piece");
        // The payload: a cropped piece keeps its window; a whole one has none.
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        var sent = new com.volcanicmg.mccitybuilder.server.InstantBuild.PieceBatch(7, "Colonial", com.volcanicmg.mccitybuilder.server.InstantBuild.DECO, List.of(
                new com.volcanicmg.mccitybuilder.server.InstantBuild.Piece(cp, new net.minecraft.core.BlockPos(1, 64, -3), 2),
                new com.volcanicmg.mccitybuilder.server.InstantBuild.Piece("walls/colonial/straight.blueprint", new net.minecraft.core.BlockPos(9, 65, -3), 1)));
        com.volcanicmg.mccitybuilder.server.InstantBuild.PieceBatch.CODEC.encode(buf, sent);
        var got = com.volcanicmg.mccitybuilder.server.InstantBuild.PieceBatch.CODEC.decode(buf);
        check(got.id() == 7 && got.pack().equals("Colonial") && got.pieces().size() == 2 && got.pieces().get(0).path().equals("walls/colonial/straight.blueprint")
                && java.util.Arrays.equals(got.pieces().get(0).crop(), new int[]{1, 2, 3}) && got.pieces().get(0).pos().equals(new net.minecraft.core.BlockPos(1, 64, -3))
                && got.pieces().get(0).rot() == 2 && got.pieces().get(1).crop() == null && got.pieces().get(1).rot() == 1 && buf.readableBytes() == 0, "piece batch round-trips its crop window");
        // A crop record goes to planners as its source pack and the path they send (Crops.packPath's file name).
        String[] src = PieceLayout.cropSource("walls/colonial/straight_crop_0a1b2c3d_deadbeef_z2_3.blueprint", h -> h.equals("0a1b2c3d") ? "Colonial" : null);
        check(src != null && src[0].equals("Colonial") && src[1].equals(PieceLayout.cropPath("walls/colonial/straight.blueprint", 1, 2, 3)), "crop record → source pack and crop path");
        check(PieceLayout.cropSource("walls/colonial/straight_crop_0a1b2c3d_deadbeef_z2_3.blueprint", h -> null) == null
                && PieceLayout.cropSource("walls/colonial/straight.blueprint", h -> "Colonial") == null, "unknown pack or not a crop: none");
        java.util.UUID ow = new java.util.UUID(1, 2);
        var decos = new com.volcanicmg.mccitybuilder.server.DecoSync.Decos(4, 2, List.of(new com.volcanicmg.mccitybuilder.planner.PasteRules.Rec(0, "Colonial", src[1], 5, 70, -9, 3),
                new com.volcanicmg.mccitybuilder.planner.PasteRules.Rec(0, "Colonial", "walls/short2.blueprint", 6, 70, -9, 0)), java.util.Arrays.asList(ow, null));
        com.volcanicmg.mccitybuilder.server.DecoSync.Decos.CODEC.encode(buf, decos);
        var dg = com.volcanicmg.mccitybuilder.server.DecoSync.Decos.CODEC.decode(buf);
        check(dg.colony() == 4 && dg.page() == 2 && dg.recs().size() == 2 && dg.recs().get(0).path().equals(src[1]) && dg.recs().get(0).rot() == 3 && buf.readableBytes() == 0
                        && ow.equals(dg.owners().get(0)) && dg.owners().get(1) == null,
                "record page round-trips");
        // Plan keys: an address as typed, normalised (case, default port, loopback names as one).
        check(Plan.addressKey("LocalHost").equals("localhost:25565") && Plan.addressKey("127.0.0.1:25565").equals("localhost:25565")
                && Plan.addressKey("[::1]").equals("localhost:25565") && Plan.addressKey("Play.Example.org:25570").equals("play.example.org:25570")
                && Plan.addressKey("2001:db8::1").equals("2001:db8::1:25565") && Plan.addressKey("host:bad").equals("host:25565"), "plan address keys");
        // The flag: written only when set (older builds read 17 fields), read back either way.
        Plan.Line l = new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, 0, false, false), lf = l.between(0, 5, 20, 5).withFill(true);
        String txt = Plan.write(List.of(), List.of(l, lf));
        check(Plan.readLines(txt).equals(List.of(l, lf)), "fill flag round-trips");
        // An older build reads only L3 rows of exactly 17 fields and skips unknown rows: it must keep every line.
        check(txt.lines().filter(x -> x.startsWith("L3") && x.split("\t").length == 17).count() == 2, "old parsers keep filled lines (the flag is an F row)");
        check(txt.lines().anyMatch(x -> x.equals("F\t0\t5\t20\t5")), "the fill flag is its own row");
        check(lf.between(0, 0, 10, 0).fill() && lf.withFlip(true).fill() && lf.asBuilt().fill(), "copies keep the flag");
    }

    /** @return a grid of one cell across, one high, along X: one block id per column */
    private static PieceLayout.Grid columns(int... col) {
        return new PieceLayout.Grid(col.length, 1, 1, col.clone(), 0);
    }

    /** @return the cells of a grid in a window along X (axis 0) or Z */
    private static PieceLayout.Grid cropGrid(PieceLayout.Grid g, int axis, int from, int len) {
        int nsx = axis == 0 ? len : g.sx(), nsz = axis == 1 ? len : g.sz();
        int[] o = new int[nsx * g.sy() * nsz];
        for (int y = 0; y < g.sy(); y++)
            for (int z = 0; z < nsz; z++)
                for (int x = 0; x < nsx; x++) o[(y * nsz + z) * nsx + x] = g.id(x + (axis == 0 ? from : 0), y, z + (axis == 1 ? from : 0));
        return new PieceLayout.Grid(nsx, g.sy(), nsz, o, g.ground());
    }

    private static boolean sameGrid(PieceLayout.Grid a, PieceLayout.Grid b) {
        return a.sx() == b.sx() && a.sy() == b.sy() && a.sz() == b.sz() && java.util.Arrays.equals(a.ids(), b.ids());
    }

    /**
     * Walls face out on their own ({@link Plan#outwardFlip}): a loop out of what it encloses whichever way it was drawn,
     * an open chain away from the town hall (on both sides of it), a branch on from the wall it joins; a chain the
     * player flipped in part is left alone.
     */
    private static void faceOut() {
        java.util.function.Function<int[][], List<Plan.Line>> chain = pts -> {
            List<Plan.Line> out = new java.util.ArrayList<>();
            for (int k = 0; k + 1 < pts.length; k++) out.add(new Plan.Line("wall", pts[k][0], pts[k][1], pts[k + 1][0], pts[k + 1][1], "p", "f", 1, 0, false, false));
            return out;
        };
        int[][] ccw = {{20, 0}, {0, 0}, {0, 10}, {20, 10}, {20, 0}}, cw = {{0, 0}, {20, 0}, {20, 10}, {0, 10}, {0, 0}};
        List<Plan.Line> none = List.of();
        // A loop: the town hall doesn't matter (inside, outside or unknown).
        for (int[] th : new int[][]{null, {10, 5}, {100, 100}}) {
            check(Boolean.FALSE.equals(Plan.outwardFlip(chain.apply(ccw), none, th)), "a loop drawn counter-clockwise already faces out");
            check(Boolean.TRUE.equals(Plan.outwardFlip(chain.apply(cw), none, th)), "a loop drawn clockwise flips to face out");
        }
        // An L-shaped loop (a concave corner): still out of what it encloses.
        int[][] ell = {{0, 0}, {20, 0}, {20, 10}, {10, 10}, {10, 20}, {0, 20}, {0, 0}};
        check(Boolean.TRUE.equals(Plan.outwardFlip(chain.apply(ell), none, null)), "an L-shaped loop drawn clockwise flips");
        // Open: away from the town hall, on either side; none known: the default stays.
        int[][] open = {{0, 0}, {20, 0}, {20, 10}};
        check(Boolean.TRUE.equals(Plan.outwardFlip(chain.apply(open), none, new int[]{10, 5})), "an open chain around the town hall on its right flips");
        check(Boolean.FALSE.equals(Plan.outwardFlip(chain.apply(open), none, new int[]{30, -10})), "an open chain with the town hall on its left already faces out");
        check(Boolean.FALSE.equals(Plan.outwardFlip(chain.apply(new int[][]{{0, 0}, {20, 0}}), none, new int[]{10, -6})), "a straight wall south of the town hall faces south");
        check(Boolean.TRUE.equals(Plan.outwardFlip(chain.apply(new int[][]{{0, 0}, {20, 0}}), none, new int[]{10, 6})), "a straight wall north of the town hall faces north");
        check(Plan.outwardFlip(chain.apply(open), none, null) == null && Plan.outwardFlip(chain.apply(new int[][]{{0, 0}, {20, 0}}), none, new int[]{30, 0}) == null,
                "no town hall, or the town hall on the wall's line: the default stays");
        // Joining another wall: its outside carries on through the joint, whatever the town hall says.
        Plan.Line wall = new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, 0, false, false);
        for (boolean f : new boolean[]{false, true}) {
            Plan.Line w = wall.withFlip(f);
            check(Boolean.valueOf(f).equals(Plan.outwardFlip(chain.apply(new int[][]{{20, 0}, {20, 10}}), List.of(w), new int[]{25, 5})), "on from a wall's end: its flip");
            check(Boolean.valueOf(!f).equals(Plan.outwardFlip(chain.apply(new int[][]{{0, 0}, {0, 10}}), List.of(w), new int[]{5, 5})), "off a wall's start: the other flip");
            check(Boolean.valueOf(f).equals(Plan.outwardFlip(chain.apply(new int[][]{{0, 10}, {0, 0}}), List.of(w), new int[]{5, 5})), "into a wall's start: its flip");
            // A T branch (the wall split at the branch, or not yet): continues the half arriving at the joint.
            List<Plan.Line> split = List.of(w.between(0, 0, 10, 0), w.between(10, 0, 20, 0));
            for (List<Plan.Line> ls : List.of(split, List.of(w)))
                for (int[][] br : new int[][][]{{{10, 0}, {10, 10}}, {{10, 0}, {10, -10}}, {{10, 10}, {10, 0}}})
                    check(Boolean.valueOf(f).equals(Plan.outwardFlip(chain.apply(br), ls, new int[]{3, 3})), "a T branch follows the wall it joins");
        }
        // The player's flip: a chain flipped in part is left alone.
        List<Plan.Line> mixed = new java.util.ArrayList<>(chain.apply(cw));
        mixed.set(1, mixed.get(1).withFlip(true));
        check(Plan.outwardFlip(mixed, none, null) == null, "a loop the player flipped in part is left alone");
        check(Plan.outwardFlip(List.of(new Plan.Line("road", 0, 0, 20, 0, "p", "f", 1, 0, false, false)), none, new int[]{10, 6}) == null, "roads have no outside");
    }

    /**
     * One click of the wall tool as LineView.drop makes it, with the plan's own calls: the segment's side
     * (LineView.flipFor: {@link Plan#autoFlip} with it added), a free end joined from across moved (LineView.moveEnd =
     * {@link Plan.Line#between}), an end on a wall's body split there ({@link Plan#split}, PlanView.splitAt), the
     * segment added, the walls it passes split ({@link Plan#splitThrough}), then {@link Plan#faceChain} (LineView.faceOut).
     * Left out (they don't change which walls a chain holds or how they face): the drop's straight-on merge and corner
     * grow, the T-cell split of a passed free end, the crossing code's own swap of the new segment's parts (faceChain
     * finds them again anyway), refitAround's length refits; and the side is picked before the free-end move and end
     * split, which the drop does after building the segment (neither moves a wall of the chain).
     *
     * @param adjust {line index, end, x, z} of a free end joined from across, or null
     */
    private static Plan.Faced click(List<Plan.Line> lines, List<Plan.Line> chain, int[] hall, int[] a, int[] b, int[] adjust) {
        List<Plan.Line> ls = new java.util.ArrayList<>(lines), ch = new java.util.ArrayList<>(chain);
        Plan.Line l = new Plan.Line("wall", a[0], a[1], b[0], b[1], "p", "f", 1, 0, false, false);
        List<Plan.Line> with = new java.util.ArrayList<>(ch);
        with.add(l);
        Boolean f = Plan.autoFlip(with, ls, hall);
        l = l.faced(f != null ? f : !ch.isEmpty() && ch.get(0).flip());
        if (adjust != null) {
            Plan.Line o = ls.get(adjust[0]);
            ls.set(adjust[0], adjust[1] == 0 ? o.between(adjust[2], adjust[3], o.x1(), o.z1()) : o.between(o.x0(), o.z0(), adjust[2], adjust[3]));
        }
        Plan.split(ls, b, x -> false);
        ls.add(l);
        ch.add(l);
        Plan.splitThrough(ls, l, x -> false);
        return Plan.faceChain(ls, ch, hall, x -> false, true);
    }

    /** @return the plan after drawing one chain through {@code pts} on {@code lines} */
    private static List<Plan.Line> drawChain(List<Plan.Line> lines, int[] hall, int[]... pts) {
        Plan.Faced r = new Plan.Faced(lines, List.of());
        for (int k = 0; k + 1 < pts.length; k++) r = click(r.lines(), r.chain(), hall, pts[k], pts[k + 1], null);
        return r.lines();
    }

    /** @return true when the single loop through segment i faces out of what it encloses (every wall as {@link Plan#loopFlips} wants) */
    private static boolean loopOut(List<Plan.Line> lines, int i) {
        var m = Plan.loopFlips(lines, i);
        return m != null && m.entrySet().stream().allMatch(e -> lines.get(e.getKey()).flip() == e.getValue());
    }

    /**
     * Walls drawn click by click (the line tool's real split / move / face calls) face out as one chain, never mixed;
     * loops (a lasso, a self-crossing chain, rings of two chains closed by a T or by joining a free end from across)
     * face out of what they enclose; a wall the player flipped stays; Undo then redrawing gives the same plan.
     */
    private static void autoChains() {
        java.util.function.Function<List<Plan.Line>, java.util.Set<Boolean>> sides = ls -> {
            java.util.Set<Boolean> s = new java.util.HashSet<>();
            for (Plan.Line l : ls) s.add(l.flip());
            return s;
        };
        int[][] cw = {{0, 0}, {20, 0}, {20, 10}, {0, 10}, {0, 0}}, ccw = {{0, 0}, {0, 10}, {20, 10}, {20, 0}, {0, 0}};
        // The town hall outside the ring: the open chain faced one way until the ring closed; the whole ring turns.
        for (int[] hall : new int[][]{{100, 5}, {-50, -50}, {10, 5}, null}) {
            check(sides.apply(drawChain(List.of(), hall, cw)).equals(java.util.Set.of(true)), "a ring drawn clockwise faces out as one");
            check(sides.apply(drawChain(List.of(), hall, ccw)).equals(java.util.Set.of(false)), "a ring drawn counter-clockwise faces out as one");
        }
        // An open chain whose side of the town hall changes as it grows: one side for all of it, the final chain's.
        List<Plan.Line> g = drawChain(List.of(), new int[]{5, 5}, new int[]{0, 0}, new int[]{10, 0}, new int[]{10, -20}, new int[]{40, -20}, new int[]{40, 30});
        check(sides.apply(g).size() == 1 && Boolean.valueOf(g.get(0).flip()).equals(Plan.autoFlip(g, g, new int[]{5, 5})), "a growing open chain stays one side");
        // The town hall north-west: the open chains face the other way until their loop closes.
        int[] far = {-50, -50};
        // A lasso: the chain ends on its own first wall (split there); the ring and its tail face out of the ring.
        List<Plan.Line> lasso = drawChain(List.of(), far, new int[]{0, 0}, new int[]{20, 0}, new int[]{20, 10}, new int[]{10, 10}, new int[]{10, 0});
        check(lasso.size() == 5 && sides.apply(lasso).equals(java.util.Set.of(true)) && loopOut(lasso, lasso.size() - 1), "a lasso faces out of its ring");
        // A chain crossing its own first wall (both split): the same.
        List<Plan.Line> cross = drawChain(List.of(), far, new int[]{0, 0}, new int[]{20, 0}, new int[]{20, 10}, new int[]{10, 10}, new int[]{10, -10});
        check(cross.size() == 6 && sides.apply(cross).equals(java.util.Set.of(true)) && loopOut(cross, 1), "a self-crossing chain faces out of its loop: " + cross);
        // A ring of two chains closed by a T onto the first one's body.
        List<Plan.Line> a = drawChain(List.of(), far, new int[]{0, 0}, new int[]{20, 0}, new int[]{20, 10});
        List<Plan.Line> tee = drawChain(a, far, new int[]{20, 10}, new int[]{10, 10}, new int[]{10, 0});
        check(loopOut(tee, tee.size() - 1), "a ring closed by a T onto another chain faces out of it");
        // ... closed by joining the first chain's free end from across (that end moves onto the line).
        List<Plan.Line> b = drawChain(List.of(), far, new int[]{2, 0}, new int[]{20, 0}, new int[]{20, 10});
        Plan.Faced j = click(b, List.of(), far, new int[]{20, 10}, new int[]{0, 10}, null);
        j = click(j.lines(), j.chain(), far, new int[]{0, 10}, new int[]{0, 0}, new int[]{0, 0, 0, 0});
        check(loopOut(j.lines(), j.lines().size() - 1), "a ring closed by joining a free end from across faces out of it");
        // ... but a wall the player flipped since stays as it is (withFlip: the player's side).
        List<Plan.Line> pf = new java.util.ArrayList<>(drawChain(List.of(), far, new int[]{0, 0}, new int[]{20, 0}, new int[]{20, 10}));
        pf.set(0, pf.get(0).withFlip(!pf.get(0).flip()));
        boolean kept = pf.get(0).flip();
        check(drawChain(pf, far, new int[]{20, 10}, new int[]{0, 10}, new int[]{0, 0}).get(0).flip() == kept && !pf.get(0).faced(),
                "a wall the player flipped stays when a loop closes");
        // Undo of a loop closure, then the same click again: the same plan (sides and faced marks travel in the lines).
        Plan.Faced half = new Plan.Faced(List.of(), List.of());
        for (int k = 0; k < 3; k++) half = click(half.lines(), half.chain(), far, cw[k], cw[k + 1], null);
        Plan.Faced closed = click(half.lines(), half.chain(), far, cw[3], cw[4], null);
        Plan.Faced again = click(half.lines(), half.chain(), far, cw[3], cw[4], null);
        check(closed.lines().equals(again.lines()) && closed.lines().stream().allMatch(Plan.Line::faced), "undo then redraw closes the loop the same way");
        // A chain holding a locked (sending) segment isn't turned: it stays one side.
        List<Plan.Line> open = drawChain(List.of(), new int[]{10, 5}, new int[]{0, 0}, new int[]{20, 0});
        Plan.Line sending = open.get(0);
        Plan.Faced held = Plan.faceChain(open, open, new int[]{10, -5}, l -> l.equals(sending), true);
        check(held.lines().equals(open), "a chain with a sending segment isn't turned");
        // A chord makes no single loop.
        List<Plan.Line> chord = new java.util.ArrayList<>(drawChain(List.of(), null, cw));
        chord.add(new Plan.Line("wall", 0, 0, 20, 10, "p", "f", 1, 0, false, false));
        check(Plan.loopFlips(chord, 0) == null, "a chord: no single loop");
        // Saved and read back, the planner's sides stay the planner's; a line saved without the mark is the player's.
        String txt = Plan.write(List.of(), closed.lines(), java.util.Map.of(), java.util.Map.of(), List.of());
        check(Plan.readLines(txt).equals(closed.lines()) && !Plan.readLines(txt.replaceAll("(?m)^A\t.*\n", "")).get(0).faced(), "faced marks are saved");
    }

    /**
     * Minecolonies Original's palisade (Ethan's "posts inward" corner): its straights run along their 6-long side, and a
     * ring's corners (counter-clockwise as drawn = L-left; clockwise, faced out by the flip = L-right-flipped) turn the
     * corner's log L onto both runs.
     */
    private static void palisadeRing() throws Exception {
        var fam = JointCases.classify("original").get("wall:walls/palisade");
        for (var g : fam.straights()) for (var p : g.pieces()) check(p.nativeDir() == 0, "palisade straights run along x: " + p.path());
        for (JointCases.Case cs : JointCases.cases("original", "wall:walls/palisade"))
            if (cs.name().equals("L-left") || cs.name().equals("L-right-flipped"))
                for (JointCases.Run r : cs.runs()) check(r.fit().match() >= 0.9, "palisade ring corner meets its run: " + cs.name() + " " + r.fit().match());
    }

    /** Edit-mode nodes: a node shared with a built segment can't be dragged (it would move the built end). */
    private static void builtNodes() {
        Plan.Line built = new Plan.Line("wall", 0, 0, 10, 0, "p", "f", 1, 0, false, false).asBuilt(), plan = new Plan.Line("wall", 10, 0, 10, 10, "p", "f", 1, 0, false, false);
        List<Plan.Line> ls = List.of(built, plan);
        check(!Plan.nodeMovable(ls, new int[]{0, 0}, Plan.Line::built) && !Plan.nodeMovable(ls, new int[]{10, 0}, Plan.Line::built), "built ends and shared joints don't drag");
        check(Plan.nodeMovable(ls, new int[]{10, 10}, Plan.Line::built), "a planned segment's free end drags");
    }

    private static void lines() throws Exception {
        undoRestore();
        autoConnect();
        builtNodes();
        fillGaps();
        faceOut();
        autoChains();
        // Early on purpose: recorded layouts hash block names, so the order pieces are read in doesn't matter.
        palisadeRing();
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
        jointGaps();
        runNumbers();
        jointVariants();
        intersections();
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
        check(Plan.split(lines, new int[]{8, 5}, Plan.Line::built) == 1 && lines.size() == 3 && lines.get(1).x1() == 8 && !lines.get(1).gate() && lines.get(2).gate(), "split at a cell: the gate stays with the middle");
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
        // Corners: east then north (a left turn) with the outside on the right wraps around it: outer; east then south: inner.
        Plan.Line east = new Plan.Line("wall", 0, 0, 10, 0, "p", "f", 1, 0, false, false), north = new Plan.Line("wall", 10, 0, 10, -10, "p", "f", 1, 0, false, false),
                south = new Plan.Line("wall", 10, 0, 10, 10, "p", "f", 1, 0, false, false);
        check(PieceLayout.outside(0, false) == 1 && PieceLayout.outside(0, true) == 3 && PieceLayout.outside(3, false) == 0, "walls face out to the right, flipped to the left");
        check(!PieceLayout.concave(List.of(east, north), List.of(2, 3)) && PieceLayout.concave(List.of(east, south), List.of(2, 1))
                && PieceLayout.concave(List.of(east.withFlip(true), north.withFlip(true)), List.of(2, 3)), "inner / outer corner by turn and flip");
        check(!PieceLayout.concave(List.of(east, north.withFlip(true)), List.of(2, 3)) && !PieceLayout.concave(List.of(east, south.withFlip(true)), List.of(2, 1)),
                "walls facing different ways: the plain corner");
        Plan.Line re = new Plan.Line("road", 0, 0, 10, 0, "p", "f", 1, 0, false, false), rs = new Plan.Line("road", 10, 0, 10, 10, "p", "f", 1, 0, false, false);
        check(!PieceLayout.concave(List.of(re, rs), List.of(2, 1)), "roads have no outside");
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
     * pieces whose air would clear each other) once fell back to one turn for both. Jungle levels 1-3 were exceptions
     * while the harness laid square straights along their x side; along the family's run axis (as the planner does) they turn too.
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
        check(same.isEmpty(),
                "road corners turn both ways at every level: " + same);
    }

    /**
     * Joint geometry checks over every family, level and variant ({@link JointCases#allLevels}), each a ratchet
     * against {@code src/test/resources/joint-baseline.txt}: entries are keyed by geometry (family, case, joint piece
     * and turn, each run's straight and off / shift / depth), so a level that lays out the same is one entry. A new
     * failure fails, and so does a baselined case that now passes, so the list only shrinks
     * ({@code -DwriteBaseline=true} rewrites both baseline files). Known classes (below) are listed, not judged.
     * <ul>
     * <li>wallCornerTurns: an asymmetric wall corner whose closed sides look more like the straight's end than its
     * arms do by over 0.15 ({@link PieceLayout#armClosed}) fails when no run touches it well (none, the best under
     * 0.3) or one barely does (under 0.5); with both runs meeting it well it is only listed for review;</li>
     * <li>noOverhang: a run that touches nothing stays within the joint's lateral span (plus its own side trim);</li>
     * <li>throughArmsAligned: a T's or cross's opposite arms of one line running through (straights laid the same
     * way) line up: their visible end profiles' world lateral centres within half a cell;</li>
     * <li>tShiftCentred: a tower T's third arm that touches nothing sits on the tower's centre line;</li>
     * <li>noContactDelta: runs that touch nothing, per family, never more than the checked-in baseline
     * ({@code src/test/resources/no-contact.tsv}).</li>
     * </ul>
     */
    /**
     * A shape drawn turned lays out the same, turned: the joint's piece and each run against the joint's box. The
     * search isn't symmetric (the boxes' centring, ties kept in order), so the drawing direction could change a corner
     * (Ethan's "moving to the left creates a weird offset"); a built-in setting pins one layout for every direction.
     * Prints the differing cases and, with {@code -DpinLines=true}, the base layouts as built-in lines.
     *
     * @return "family|case" of every case that lays out differently drawn another way
     */
    static java.util.Set<String> rotationCheck(List<JointCases.Case> base) throws Exception {
        Map<String, JointCases.Case> byId = new java.util.HashMap<>();
        for (var cs : base) byId.put(cs.family() + "|" + cs.name(), cs);
        java.util.Set<String> bad = new java.util.TreeSet<>();
        Map<String, String> pins = new java.util.TreeMap<>();
        try {
            for (int r = 1; r < 4; r++) {
                JointCases.rot = r;
                for (var cs : JointCases.allLevels()) {
                    var b = byId.get(cs.family() + "|" + cs.name());
                    if (b == null || cs.butt() || cs.name().startsWith("T-split") || cs.name().startsWith("end")) continue;
                    if (!sameTurned(b, cs, r)) {
                        bad.add(cs.family() + "|" + cs.name());
                        String pin = JointCases.PINS.get(b);
                        if (pin != null) pins.put(pin.substring(0, pin.lastIndexOf('\t')), pin);
                    }
                }
            }
        } finally {
            JointCases.rot = 0;
        }
        if (Boolean.getBoolean("pinLines")) for (String p : pins.values()) System.out.println("PIN " + p);
        return bad;
    }

    /** @return true when {@code c} is {@code b} turned {@code r} quarter turns (joint piece, run boxes against the joint box) */
    private static boolean sameTurned(JointCases.Case b, JointCases.Case c, int r) {
        PieceLayout.Grid t = b.joint().turned(r);
        if (t.sx() != c.joint().sx() || !java.util.Arrays.equals(t.ids(), c.joint().ids()) || b.runs().size() != c.runs().size()) return false;
        int[] jb = b.jointBox();
        for (var rb : b.runs()) {
            int[] x = rb.box().clone();
            int w = jb[2] - jb[0] + 1, h = jb[3] - jb[1] + 1;
            int x0 = x[0] - jb[0], z0 = x[1] - jb[1], x1 = x[2] - jb[0], z1 = x[3] - jb[1];
            for (int i = 0; i < r; i++) {
                int nx0 = h - 1 - z1, nx1 = h - 1 - z0;
                z0 = x0;
                z1 = x1;
                x0 = nx0;
                x1 = nx1;
                int tmp = w;
                w = h;
                h = tmp;
            }
            int out = (rb.out() + r) % 4;
            boolean found = false;
            for (var rc : c.runs())
                if (rc.out() == out) found = rc.box()[0] - c.jointBox()[0] == x0 && rc.box()[1] - c.jointBox()[1] == z0 && rc.box()[2] - c.jointBox()[0] == x1
                        && rc.box()[3] - c.jointBox()[1] == z1;
            if (!found) return false;
        }
        return true;
    }

    /** @return a grid's hash by block names, not the reader's ids (those depend on the order pieces were first read) */
    private static int gridHash(PieceLayout.Grid g) {
        int h = 1;
        for (int id : g.ids()) h = 31 * h + (id > 0 ? PackPieces.name(id).hashCode() : id);
        return h;
    }

    /** @return a joint's layout as the planner places it: piece, turn (its blocks), box and each run's out, offset, shift, depth */
    private static String layoutSig(JointCases.Case cs) {
        StringBuilder g = new StringBuilder(cs.jointPath() + "|" + Integer.toHexString(gridHash(cs.joint())) + "|" + java.util.Arrays.toString(cs.jointBox()));
        for (var r : cs.runs()) g.append("|").append(r.out()).append(":").append(r.fit().off()).append(",").append(r.fit().shift()).append(",").append(r.fit().depth());
        return g.toString();
    }

    /**
     * Saved plans laid out joint by joint ({@link JointCases#fromPlan}): a line meeting joints at both ends takes one
     * sideways shift, so both its ends must put the run on the same line, and a run touching its joint must start right
     * at the joint's box (Ethan's colonial road corners: arms 1-2 cells off their runs, 1-cell grass gaps).
     *
     * @return the defects, per "joint cell run direction"
     */
    static List<String> planJointDefects(String text) throws Exception {
        List<Plan.Line> lines = Plan.readLines(text);
        Map<String, Integer> at = new java.util.HashMap<>();
        List<String> bad = new java.util.ArrayList<>();
        for (var cs : JointCases.fromPlan(text)) {
            String[] xz = cs.name().split(",");
            int[] c = {Integer.parseInt(xz[0]), Integer.parseInt(xz[1])}, jb = cs.jointBox();
            for (var r : cs.runs()) {
                int d = r.out(), lat = d % 2 == 0 ? 1 : 0;
                int[] b = r.box();
                int gap = switch (d) {
                    case 0 -> b[0] - jb[2] - 1;
                    case 2 -> jb[0] - b[2] - 1;
                    case 1 -> b[1] - jb[3] - 1;
                    default -> jb[1] - b[3] - 1;
                };
                if (r.fit().depth() >= 0 && gap > 0) bad.add(cs.name() + " " + "ESWN".charAt(d) + " gap " + gap);
                // The line leaving c in direction d: where its run lies across it, seen from this end.
                for (Plan.Line l : lines) {
                    int[][] e = l.ends();
                    for (int k = 0; k < 2; k++) {
                        if (e[k][0] != c[0] || e[k][1] != c[1] || PieceLayout.dir(e[k], e[1 - k]) != d) continue;
                        Integer seen = at.put(Plan.lineKey(l.x0(), l.z0(), l.x1(), l.z1()), c[lat] + b[lat]);
                        if (seen != null && seen != c[lat] + b[lat]) bad.add(cs.name() + " " + "ESWN".charAt(d) + " off its line by " + (c[lat] + b[lat] - seen));
                    }
                }
            }
        }
        return bad;
    }

    /**
     * Road corners (Ethan's colonial level-1 corner1: its closed dirt-border sides scored like the road's end, so every
     * corner came out a quarter turn off, off its runs, with gaps). Only corners turned off their contact pick may
     * change: every other road joint must keep the layout of the build before (road-layouts.tsv; -DnoRoadFix -DwriteRoadLayouts
     * writes it), since the line tool snaps lengths from these offsets (fortress / birch line ends moved live when an
     * earlier fix changed them).
     */
    private static void roadCornerChecks(List<JointCases.Case> all) throws Exception {
        Map<String, String> now = new java.util.TreeMap<>();
        java.util.Set<String> turned = new java.util.TreeSet<>(), closed = new java.util.TreeSet<>();
        java.util.function.BiConsumer<String, JointCases.Case> add = (key, cs) -> {
            now.put(key, layoutSig(cs));
            if (JointCases.TURNED.contains(cs)) turned.add(key);
            if (JointCases.CLOSED.contains(cs)) closed.add(key);
        };
        for (var cs : all) if (cs.family().contains("infrastructure")) add.accept(cs.family() + "|" + cs.name(), cs);
        List<String> plans = List.of("ab-roads.txt", "ethan-road-corners.txt", "ethan-colonial-roads.txt");
        for (String plan : plans) for (var cs : JointCases.fromPlan(Files.readString(Path.of("src/test/resources/" + plan)))) add.accept(plan + "|" + cs.family() + "|" + cs.name(), cs);
        Path ref = Path.of("src/test/resources/road-layouts.tsv");
        if (Boolean.getBoolean("writeRoadLayouts")) {
            StringBuilder sb = new StringBuilder();
            now.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
            Files.writeString(ref, sb.toString());
            return;
        }
        List<String> changed = new java.util.ArrayList<>();
        int compared = 0;
        for (String line : Files.readAllLines(ref)) {
            String[] f = line.split("\t");
            if (f.length != 2 || turned.contains(f[0])) continue;
            compared++;
            if (!f[1].equals(now.get(f[0]))) changed.add(f[0] + ": " + f[1] + " -> " + now.get(f[0]));
        }
        System.out.println("CityBuilder self-check: road joints turned off their pick (" + turned.size() + "): " + turned);
        System.out.println("CityBuilder self-check: other road joints changed from road-layouts.tsv (" + changed.size() + " of " + compared + "): " + changed);
        check(compared > 1000 && changed.isEmpty(), "road joints not turned keep their layout (and line-end snapping): " + changed);
        System.out.println("CityBuilder self-check: road corners with a closed side toward a run (" + closed.size() + "): " + closed);
        // The rest are left by PieceLayout#pickRoadCorner (no other turn meets the runs as well, e.g. fortress, right live).
        check(closed.size() <= 141 && closed.stream().noneMatch(k -> k.contains("colonial")), "road corners face their runs with their open sides (ratchet 141, colonial none): " + closed);
        // The A/B plan's fortress corners aren't turned (the snapshot above); its birch corners turn here only because the harness lays out the even group, live picks odd.
        for (String plan : List.of("ethan-road-corners.txt", "ethan-colonial-roads.txt")) {
            List<String> d = planJointDefects(Files.readString(Path.of("src/test/resources/" + plan)));
            System.out.println("CityBuilder self-check: " + plan + " joint defects (" + d.size() + "): " + d);
            check(d.isEmpty(), "plan joints line up with their runs, no gaps: " + plan + " " + d);
        }
    }

    private static void jointChecks() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        if (System.getProperty("contactFloor") != null) {
            PieceLayout.contactFloor = Double.parseDouble(System.getProperty("contactFloor"));
            PieceLayout.clearCache();
        }
        List<JointCases.Case> all = JointCases.allLevels();
        roadCornerChecks(all);
        var turnedDiffer = rotationCheck(all);
        System.out.println("CityBuilder self-check: cases laid out differently when drawn turned (" + turnedDiffer.size() + "):");
        for (String r : turnedDiffer) System.out.println("  turned " + r);
        Path allowFile = Path.of("src/test/resources/joint-baseline.txt");
        java.util.Set<String> allow = new java.util.HashSet<>();
        if (Files.exists(allowFile))
            for (String line : Files.readAllLines(allowFile)) if (!line.isBlank()) allow.add(line.split("\t")[0]);
        Map<String, String> failing = new java.util.TreeMap<>(), known = new java.util.TreeMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>(), passingAllowed = new java.util.TreeSet<>();
        List<String> fails = new java.util.ArrayList<>(), notes = new java.util.ArrayList<>(), review = new java.util.ArrayList<>(), mirrored = new java.util.ArrayList<>();
        Map<String, Integer> noContact = new java.util.TreeMap<>();
        for (var cs : all) {
            for (var r : cs.runs()) if (r.fit().depth() < 0) noContact.merge(cs.family(), 1, Integer::sum);
            String fam = cs.family().replaceAll(" L\\d+$", "");
            StringBuilder g = new StringBuilder(fam + "|" + cs.name() + "|" + cs.jointPath() + "|" + Integer.toHexString(gridHash(cs.joint())) + "x" + cs.joint().sx());
            for (var r : cs.runs())
                g.append("|").append(r.out()).append(":").append(Integer.toHexString(gridHash(r.straight()))).append(":").append(r.fit().off())
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
                PieceLayout.Grid j = cs.joint(), t1 = j.turned(1);
                if (!(t1.sx() == j.sx() && java.util.Arrays.equals(t1.ids(), j.ids()))) {
                    List<Integer> outs = new java.util.ArrayList<>();
                    for (var r : cs.runs()) outs.add(r.out());
                    double[] ac = PieceLayout.armClosed(j, outs, cs.runs().get(0).straight(), false);
                    double best = 0, worst = 1;
                    boolean contact = false;
                    for (var r : cs.runs()) {
                        contact |= r.fit().depth() >= 0;
                        best = Math.max(best, r.fit().match());
                        worst = Math.min(worst, r.fit().match());
                    }
                    boolean looksTurned = ac[1] - ac[0] > 0.15;
                    // Weak contact (none, or the best run under 0.3) or a run that barely meets it (under 0.5): gates.
                    // A corner both runs meet well but whose closed sides look like the straight: listed for review.
                    if (!contact || best < 0.3 || worst < 0.5) verdict.accept("wallCornerTurns", !looksTurned);
                    else if (looksTurned) review.add(String.format("%s (arm %.2f, closed %.2f, runs %.2f-%.2f)", id, ac[0], ac[1], worst, best));
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
                    for (var b : cs.runs()) {
                        if (b.out() != (a.out() + 2) % 4 || a.out() > b.out()) continue;
                        if (!java.util.Arrays.equals(a.straight().ids(), b.straight().ids())) {
                            // Two chains both leaving the joint lay mirrored straights, whose sides differ by design:
                            // their world extents are only listed when they differ.
                            double[] ea = endExtent(a), eb = endExtent(b);
                            if (Math.abs(ea[0] - eb[0]) > 0.5 || Math.abs(ea[1] - eb[1]) > 0.5)
                                mirrored.add(String.format("%s %d/%d: [%.0f, %.0f] vs [%.0f, %.0f]", id, a.out(), b.out(), ea[0], ea[1], eb[0], eb[1]));
                            continue;
                        }
                        // One line running through (both straights laid the same way).
                        double ca = endCentre(a), cb = endCentre(b);
                        ok &= Math.abs(ca - cb) <= 0.5;
                        nums.append(String.format(" %d/%d: %.2f vs %.2f", a.out(), b.out(), ca, cb));
                    }
                verdict.accept("throughArmsAligned", ok);
                if (cs.family().matches("(medievaloak walls|fortress walls) L\\d+") && cs.name().equals("cross"))
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
        // An L turning left and one turning right need the corner turned differently (its arms face other ways):
        // the same turn for both, of a piece that isn't the same a quarter turn on, has one of them backwards.
        Map<String, JointCases.Case> lefts = new java.util.HashMap<>();
        for (var cs : all) if (cs.name().startsWith("L-left") && !cs.butt()) lefts.put(cs.family() + "|" + cs.name().substring("L-left".length()), cs);
        java.util.Set<String> sameTurn = new java.util.TreeSet<>();
        for (var cs : all) {
            if (!cs.name().startsWith("L-right") || cs.butt()) continue;
            var l = lefts.get(cs.family() + "|" + cs.name().substring("L-right".length()));
            PieceLayout.Grid j = cs.joint(), t1 = j.turned(1);
            if (l == null || !l.jointPath().equals(cs.jointPath()) || (t1.sx() == j.sx() && java.util.Arrays.equals(t1.ids(), j.ids()))) continue;
            if (l.joint().sx() == j.sx() && java.util.Arrays.equals(l.joint().ids(), j.ids())) sameTurn.add(cs.family() + "|L-left/L-right" + cs.name().substring("L-right".length()));
        }
        System.out.println("CityBuilder self-check: L-left and L-right with one joint turn (not gating) (" + sameTurn.size() + "):");
        for (String r : sameTurn) System.out.println("  same turn " + r);
        for (String n : notes) System.out.println("CityBuilder self-check: through arms, " + n);
        System.out.println("CityBuilder self-check: wallCornerTurns review (both runs meet it well, closed sides look like the straight; not gating) (" + review.size() + "):");
        for (String r : review) System.out.println("  review " + r);
        System.out.println("CityBuilder self-check: mirrored through lines whose extents differ (not gating) (" + mirrored.size() + "):");
        for (String m : mirrored) System.out.println("  mirrored " + m);
        if (Boolean.getBoolean("jointReport")) {
            // By case (check + family + level + case), whatever the layout: baselined ones no longer failing are fixed,
            // failing ones not baselined are new.
            java.util.Set<String> was = new java.util.TreeSet<>(), now = new java.util.TreeSet<>(failing.values());
            if (Files.exists(allowFile))
                for (String line : Files.readAllLines(allowFile)) if (line.contains("\t")) was.add(line.split("\t")[1]);
            Map<String, Integer> fixed = new java.util.TreeMap<>(), added = new java.util.TreeMap<>();
            for (String k : was) if (!now.contains(k)) fixed.merge(k.substring(0, k.indexOf(' ')), 1, Integer::sum);
            for (String k : now) if (!was.contains(k)) added.merge(k.substring(0, k.indexOf(' ')), 1, Integer::sum);
            System.out.println("CityBuilder joint report: floor " + PieceLayout.contactFloor + " fixed " + fixed + " new " + added + " review " + review.size());
            for (String k : was) if (!now.contains(k)) System.out.println("  fixed " + k);
            for (String k : now) if (!was.contains(k)) System.out.println("  new " + k);
            return;
        }
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

    /** @return a run's visible end profile's world lateral extent {lo, hi} (its box's lateral origin plus the profile's) */
    private static double[] endExtent(JointCases.Run r) {
        int d = r.out(), lat0 = d % 2 == 0 ? 1 : 0;
        PieceLayout.Grid s = r.straight();
        java.util.Set<Long> p = PieceLayout.visible(s.profile((d + 2) % 4, s.inset((d + 2) % 4), 0, false), s);
        if (p.isEmpty()) return new double[]{r.box()[lat0], r.box()[lat0 + 2]};
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (long v : p) {
            int l = (int) (v & 0xFFFF) - 30000;
            lo = Math.min(lo, l);
            hi = Math.max(hi, l);
        }
        return new double[]{r.box()[lat0] + lo, r.box()[lat0] + hi};
    }

    /**
     * @return a run's visible end profile's lateral centre in world cells: its box's lateral origin plus the middle of
     * the profile's lateral extent (the extent's middle, not the mean, so a wall turned round for the other arm, its
     * walkway on the other side, still has the same centre)
     */
    private static double endCentre(JointCases.Run r) {
        int d = r.out(), lat0 = d % 2 == 0 ? 1 : 0;
        PieceLayout.Grid s = r.straight();
        java.util.Set<Long> p = PieceLayout.visible(s.profile((d + 2) % 4, s.inset((d + 2) % 4), 0, false), s);
        if (p.isEmpty()) return r.box()[lat0] + (r.box()[lat0 + 2] - r.box()[lat0]) / 2.0;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (long v : p) {
            int l = (int) (v & 0xFFFF) - 30000;
            lo = Math.min(lo, l);
            hi = Math.max(hi, l);
        }
        return r.box()[lat0] + (lo + hi) / 2.0;
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
            // A built-in setting is the hand-checked layout (flush outer edges before the best cross-section match).
            if (!cs.fixed()) check(rep.match() >= rep.best() - 1e-9, "layout as continuous as the pieces allow: " + id + String.format(" %.2f < %.2f", rep.match(), rep.best()));
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
                if (!cs.fixed()) check(rep.match() >= rep.best() - 1e-9, "joint-first layout optimal: " + cs.family() + " " + cs.name());
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
        // Auto: an L gets the outer corner, the inner one when the walls face out into its bend; T, cross and straight on by count.
        check(PieceLayout.autoRole(2, false, false) == PieceLayout.CORNER && PieceLayout.autoRole(2, false, true) == PieceLayout.INNER, "an L's corner follows the walls' outside");
        check(PieceLayout.jointRoles(Plan.J_AUTO, 2, false, false, true).equals(List.of(PieceSets.Role.INNER, PieceSets.Role.CORNER))
                && PieceLayout.jointRoles(Plan.J_CORNER, 2, false, false, true).get(0) == PieceSets.Role.CORNER
                && PieceLayout.jointRoles(Plan.J_AUTO, 2, false, true, true).get(0) == PieceSets.Role.TOWER, "a chosen type or towers win over the outside");
        check(PieceLayout.autoRole(3, false, true) == PieceLayout.TEE && PieceLayout.autoRole(4, false, false) == PieceLayout.CROSS
                && PieceLayout.autoRole(2, true, true) == PieceLayout.NONE, "T, cross, straight on");
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

    /**
     * Ethan's "1 blocks left uncovered" at a colonial L: a run between two joints that the straights can't cover
     * exactly closes a 1-2 cell gap by reaching that far into a joint; a joint between lines of different levels takes
     * the widest line's level whichever line is laid out first.
     */
    private static void intersections() {
        // A chain started on a segment's body splits it; ending the chain with nothing drawn merges it back.
        Plan.Line whole = new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, -1, false, true);
        List<Plan.Line> sp = new java.util.ArrayList<>(List.of(whole));
        Plan.split(sp, new int[]{8, 0}, l -> false);
        var back = Plan.unsplit(sp, new int[]{8, 0});
        check(back.size() == 1 && back.get(0).x0() == 0 && back.get(0).x1() == 20 && back.get(0).gate(), "an unused branch start merges the split back");
        List<Plan.Line> tj = new java.util.ArrayList<>(sp);
        tj.add(new Plan.Line("wall", 8, 0, 8, 9, "p", "f", 1, -1, false, false));
        check(Plan.unsplit(tj, new int[]{8, 0}) == tj, "a real joint stays");
        Plan.Line h = new Plan.Line("wall", -10, 0, 10, 0, "p", "f", 1, -1, false, false), run = new Plan.Line("wall", 0, -10, 0, 10, "p", "f", 1, -1, false, true);
        List<Plan.Line> ls = new java.util.ArrayList<>(List.of(h, run));
        var cs = Plan.crossings(ls, run, l -> false);
        check(cs.size() == 1 && cs.get(0)[0] == 0 && cs.get(0)[1] == 0 && cs.get(0)[2] == 0, "a run through a segment crosses it once: " + cs.size());
        var parts = Plan.splitThrough(ls, run, l -> false);
        int atCross = 0;
        for (Plan.Line l : ls) for (int[] e : l.ends()) if (e[0] == 0 && e[1] == 0) atCross++;
        check(ls.size() == 4 && parts.size() == 2 && atCross == 4 && parts.get(0).z0() == -10 && parts.get(1).z1() == 10 && !parts.get(1).gate(),
                "an intersection: both split, four ends at the cross, one gate kept");
        // A built segment stays whole (the crossing is reported, the drop refused as before); an end on it is a T, not a cross.
        List<Plan.Line> lk = new java.util.ArrayList<>(List.of(h, run));
        check(Plan.crossings(lk, run, l -> l == h).get(0)[2] == 1 && Plan.splitThrough(lk, run, l -> l == h).size() == 1 && lk.size() == 2, "a locked segment isn't split");
        Plan.Line toT = new Plan.Line("wall", 0, -10, 0, 0, "p", "f", 1, -1, false, false);
        check(Plan.crossings(List.of(h, toT), toT, l -> false).isEmpty(), "ending on a segment isn't a crossing");
        Plan.Line road = new Plan.Line("road", 0, -10, 0, 10, "p", "f", 1, -1, false, false), par = new Plan.Line("wall", -10, 2, 10, 2, "p", "f", 1, -1, false, false);
        check(Plan.crossings(List.of(h, road), road, l -> false).isEmpty() && Plan.crossings(List.of(h, par), par, l -> false).isEmpty(), "other tools and parallel segments don't cross");
        Plan.Line h2 = new Plan.Line("wall", -10, 6, 10, 6, "p", "f", 1, -1, false, false);
        List<Plan.Line> two = new java.util.ArrayList<>(List.of(h2, h, run));
        var p2 = Plan.splitThrough(two, run, l -> false);
        check(p2.size() == 3 && p2.get(0).z1() == 0 && p2.get(1).z1() == 6 && two.size() == 7, "two crossings in order along the run");
        // Through an existing joint (Ethan's road through the T at (-3502,-4583)): only the run splits; the joint gains arms.
        Plan.Line tw = new Plan.Line("road", -3510, -4583, -3502, -4583, "p", "f", 1, 0, false, false), te = new Plan.Line("road", -3502, -4583, -3463, -4583, "p", "f", 1, 0, false, false),
                ts = new Plan.Line("road", -3502, -4583, -3502, -4560, "p", "f", 1, 0, false, false), thru = new Plan.Line("road", -3502, -4600, -3502, -4570, "p", "f", 1, 0, false, false);
        // A T whose south arm lies along the run: refused (the run would overlap it).
        check(Plan.crossings(List.of(tw, te, ts, thru), thru, l -> false).get(0)[2] == 2, "through a T along its arm: refused");
        // An L (west + east halves of a road meeting, plus nothing on the run's line): the run through it makes a cross.
        Plan.Line run2 = new Plan.Line("road", -3502, -4595, -3502, -4570, "p", "f", 1, 0, false, false);
        List<Plan.Line> t2 = new java.util.ArrayList<>(List.of(tw, te, run2));
        var c2 = Plan.crossings(t2, run2, l -> false);
        var p3 = Plan.splitThrough(t2, run2, l -> false);
        check(c2.size() == 1 && c2.get(0)[2] == 0 && c2.get(0)[3] == 1 && p3.size() == 2 && t2.size() == 4 && Plan.arms(t2, new int[]{-3502, -4583}).size() == 4,
                "through a joint of two segments: the run splits there, four arms (a cross)");
        Plan.Line lw = new Plan.Line("wall", -10, 0, 0, 0, "p", "f", 1, -1, false, false), ln = new Plan.Line("wall", 0, 0, 0, -10, "p", "f", 1, -1, false, false),
                lrun = new Plan.Line("wall", -5, 0, 5, 0, "p", "f", 1, -1, false, false);
        List<Plan.Line> lcase = new java.util.ArrayList<>(List.of(lw, ln, lrun));
        check(Plan.crossings(lcase, lrun, l -> false).get(0)[2] == 2, "through an L along its west arm: refused");
        Plan.Line lrun2 = new Plan.Line("wall", 0, -5, 0, 5, "p", "f", 1, -1, false, false), le = new Plan.Line("wall", 0, 0, 10, 0, "p", "f", 1, -1, false, false);
        List<Plan.Line> lcase2 = new java.util.ArrayList<>(List.of(lw, le, new Plan.Line("wall", 0, 3, 0, 9, "p", "f", 1, -1, false, false)));
        Plan.Line endRun = new Plan.Line("wall", 0, -6, 0, 2, "p", "f", 1, -1, false, false);
        lcase2.add(endRun);
        check(Plan.crossings(lcase2, endRun, l -> false).get(0)[3] == 1, "through a straight joint of two segments (an end on the run): a crossing");
        // A plain end lying across the run (an L forms mid-run): the run splits there, a T.
        List<Plan.Line> pe = new java.util.ArrayList<>(List.of(lw, lrun2));
        Plan.splitThrough(pe, lrun2, l -> false);
        check(pe.size() == 3 && Plan.arms(pe, new int[]{0, 0}).size() == 3, "through a segment's free end: a T");
        // A diagonal segment's end on the run isn't a crossing; a cell where a built joint stands isn't split.
        Plan.Line dg = new Plan.Line("wall", -5, -5, 0, 0, "p", "f", 1, -1, false, false);
        check(Plan.crossings(List.of(dg, lrun), lrun, l -> false).isEmpty(), "a diagonal segment's end isn't crossed");
        List<Plan.Line> fx = new java.util.ArrayList<>(List.of(lw, lrun2));
        check(Plan.splitThrough(fx, lrun2, l -> false, cc -> cc[0] == 0 && cc[1] == 0).size() == 1 && fx.size() == 2, "no split where a built joint stands");
        // Two segments crossing at one cell: one split there, no zero-length half (issue #2).
        List<Plan.Line> dup = new java.util.ArrayList<>(List.of(h, new Plan.Line("wall", -10, 0, 10, 0, "p", "f", 1, -1, false, false), run));
        var pd = Plan.splitThrough(dup, run, l -> false);
        check(pd.size() == 2 && dup.stream().noneMatch(l -> l.x0() == l.x1() && l.z0() == l.z1()), "a duplicate crossing makes no zero-length part");
        // A run not in the list: nothing split, no exception.
        List<Plan.Line> without = new java.util.ArrayList<>(List.of(h));
        check(Plan.splitThrough(without, run, l -> false).size() == 1, "a run missing from the list isn't split");
        // Auto-connect: the grown elbow must leave the second leg pointing at the end.
        int[] el0 = {5, 0}, target = {5, 5};
        check(!LineSnap.connectPassed(el0, el0, target) && !LineSnap.connectPassed(el0, new int[]{5, 2}, target), "a connect leg toward the end is valid");
        check(LineSnap.connectPassed(el0, new int[]{5, 5}, target) && LineSnap.connectPassed(el0, new int[]{7, 0}, target)
                && LineSnap.connectPassed(el0, new int[]{5, 7}, target), "a zero-length or reversed connect leg is refused");
        // A built joint piece there: the drop refuses it (the crossing cell is under a built T piece).
        var builtTee = java.util.Map.of("k", List.of(new Plan.BuiltPiece(-3510, -4583, -3502, -4583, "roads/t.blueprint", 0, -3502, 64, -4583, -3506, -4587, -3498, -4579, "TEE")));
        check(Plan.builtJointAt(builtTee.values(), c2.get(0)), "through a built joint: refused");
        // A diagonal run crosses nothing (no shared cell on both axes); a road through the cell isn't split by a wall.
        Plan.Line diag = new Plan.Line("wall", -5, -5, 5, 5, "p", "f", 1, -1, false, false);
        check(Plan.crossings(List.of(h, diag), diag, l -> false).isEmpty(), "a diagonal run makes no intersection");
        Plan.Line roadX = new Plan.Line("road", -10, 0, 10, 0, "p", "f", 1, -1, false, false);
        List<Plan.Line> mix = new java.util.ArrayList<>(List.of(roadX, h, run));
        Plan.splitThrough(mix, run, l -> false);
        check(mix.contains(roadX) && mix.size() == 5, "only the crossed segment of the run's tool splits");
        // The gate stays with the half holding the segment's middle.
        Plan.Line gated = new Plan.Line("wall", 0, 0, 20, 0, "p", "f", 1, -1, false, true);
        List<Plan.Line> g1 = new java.util.ArrayList<>(List.of(gated));
        Plan.split(g1, new int[]{4, 0}, l -> false);
        check(!g1.get(0).gate() && g1.get(1).gate(), "a split keeps the gate on the half with the middle");
        // A T turns into a cross: a segment out of the joint's open side gives four arms (the cross piece's menu), along an
        // arm already there is refused; the T itself offers the cross piece as a type.
        Plan.Line w = new Plan.Line("wall", -10, 0, 0, 0, "p", "f", 1, -1, false, false), e = new Plan.Line("wall", 0, 0, 10, 0, "p", "f", 1, -1, false, false),
                s0 = new Plan.Line("wall", 0, 0, 0, 10, "p", "f", 1, -1, false, false);
        List<Plan.Line> tee = new java.util.ArrayList<>(List.of(w, e, s0));
        check(Plan.arms(tee, new int[]{0, 0}).equals(java.util.Set.of(0, 1, 2)) && !Plan.arms(tee, new int[]{0, 0}).contains(3)
                && Plan.menuTypes(3, false).contains(Plan.J_CROSS), "a T: three arms, the north side open, cross offered");
        tee.add(new Plan.Line("wall", 0, 0, 0, -10, "p", "f", 1, -1, false, false));
        check(Plan.arms(tee, new int[]{0, 0}).size() == 4 && Plan.menuTypes(4, false).contains(Plan.J_CROSS)
                && PieceLayout.jointRoles(Plan.J_AUTO, 4, false, false).get(0) == com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CROSS, "drawn out of the open side: a cross, auto picks the cross piece");
        // A branch at a built segment's plain end (its pieces: straights only) is allowed; where a built joint piece
        // stands (a corner covering the cell) it is refused.
        var builtEnd = java.util.Map.of("k", List.of(new Plan.BuiltPiece(0, 0, 20, 0, "walls/long1.blueprint", 0, 5, 64, 1, 0, -1, 17, 1, "STRAIGHT")));
        var builtCorner = java.util.Map.of("k", List.of(new Plan.BuiltPiece(0, 0, 20, 0, "walls/outer_corner1.blueprint", 0, 20, 64, 0, 17, -3, 23, 3, "CORNER")));
        check(!Plan.builtJointAt(builtEnd.values(), new int[]{20, 0}) && Plan.builtJointAt(builtCorner.values(), new int[]{20, 0}),
                "branch at a built segment's end: allowed without a built joint piece, refused on one");
    }

    /**
     * Ethan's "variant wall" (colonial walls, variant 2 of 2): its straights are variants (one footprint) but its
     * corners differ in size, so they read as levels; a corner must share the straight's number, not the line's level.
     */
    private static void jointVariants() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        var fam = PieceSets.classify(JointCases.packs().get("colonial")).get("wall:walls");
        var st = fam.straights().get(0);
        PieceSets.Piece v2 = st.pick(st.levels() ? 2 : 1, 0);
        PieceSets.Piece outer = fam.of(PieceSets.Role.CORNER).get(0).pickAs(v2, 1, 0), inner = fam.of(PieceSets.Role.INNER).get(0).pickAs(v2, 1, 0);
        check(!st.levels() && v2.number() == 2 && outer.path().equals("walls/outer_corner2.blueprint") && inner.path().equals("walls/inner_corner2.blueprint"),
                "a variant-2 colonial wall takes the variant-2 corners: " + outer.path() + " " + inner.path());
    }

    /**
     * Ethan's level-1 colonial road with wide cobble pieces in it: a straight group without the run's number used to
     * give another number's piece of the same width (its lowest), and fillers came from every number. A run's pieces
     * (main and fillers) and its joint now share the run's number, for every road and wall family at every number.
     */
    private static void runNumbers() throws Exception {
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        java.util.Set<String> leaked = new java.util.TreeSet<>();
        for (var pk : JointCases.packs().entrySet())
            for (var fam : PieceSets.classify(pk.getValue()).values()) {
                if (fam.straights().isEmpty()) continue;
                PieceSets.Group g0 = fam.straights().get(0);
                for (PieceSets.Piece main : g0.pieces()) {
                    int n = main.number();
                    for (PieceSets.Piece p : PieceSets.runPieces(fam.straights(), n, main.across())) {
                        // Another number only when unnumbered or when the numbers are sizes.
                        boolean sizes = fam.straights().stream().filter(g -> g.pieces().contains(p)).anyMatch(g -> g.pieces().stream().mapToInt(PieceSets.Piece::run).distinct().count() > 1);
                        check(p.number() == n || p.number() == 0 || sizes, "run piece of number " + n + ": " + pk.getKey() + " " + p.path());
                    }
                    // The old pick: each straight group at the line's level.
                    for (PieceSets.Group g : fam.straights())
                        if (g.levels() && g.pick(n, 0).number() != n && g.pick(n, 0).across() == main.across()) leaked.add(pk.getKey() + " " + fam.key() + " L" + n + " " + g.pick(n, 0).path());
                    for (PieceSets.Role r : List.of(PieceSets.Role.CORNER, PieceSets.Role.INNER, PieceSets.Role.TEE, PieceSets.Role.CROSS))
                        for (PieceSets.Group jg : fam.of(r))
                            if (jg.pieces().stream().anyMatch(q -> q.number() == n)) check(jg.pickAs(main, n, 0).number() == n, "joint shares the run's number: " + pk.getKey() + " " + jg.base() + " " + n);
                }
            }
        System.out.println("CityBuilder self-check: straights another number's piece used to fill (" + leaked.size() + "): " + leaked);
        // Colonial roads: the short straight (4x5) runs along x like the long one (8x5), so it is the road's width, not
        // 4 wide; it used to be dropped as "another width" and runs left 1-7 cells open ("Corner · gap 7").
        var road = PieceSets.classify(JointCases.packs().get("colonial")).get("road:infrastructure/roads");
        var lv1 = PieceSets.runPieces(road.straights(), 1, 5);
        check(lv1.stream().mapToInt(PieceSets.Piece::run).distinct().sorted().boxed().toList().equals(List.of(4, 8))
                && lv1.stream().allMatch(p -> p.nativeDir() == 0 && p.number() == 1), "colonial road level 1 tiles with 8 and 4 of its own number: " + lv1);
        // Unnumbered fillers and numbers that are sizes stay: fortress roads' footpath (6) and acacia's straight4 / 8.
        for (String[] t : new String[][]{{"fortress", "road:infrastructure/roads", "26", "longpath"}, {"acacia", "road:infrastructure/roads", "15", "roadsimplestraight"}}) {
            var fam = PieceSets.classify(JointCases.packs().get(t[0])).get(t[1]);
            PieceSets.Piece main = fam.straights().stream().filter(g -> g.base().equals(t[3])).findFirst().orElseThrow().pick(1, 0);
            int[] lens = PieceSets.runPieces(fam.straights(), main.number(), main.across()).stream().mapToInt(PieceSets.Piece::run).distinct().toArray();
            int n = Integer.parseInt(t[2]);
            check(PieceLayout.fill(n, lens).stream().mapToInt(Integer::intValue).sum() == n, t[0] + " roads tile " + n + " cells with " + java.util.Arrays.toString(lens));
        }
    }

    private static void jointGaps() throws Exception {
        check(PieceLayout.jointLevelLine(new int[]{5, 9}, new int[]{1, 3}) == 1 && PieceLayout.jointLevelLine(new int[]{9, 5}, new int[]{3, 1}) == 0
                && PieceLayout.jointLevelLine(new int[]{5, 5}, new int[]{1, 2}) == 1 && PieceLayout.jointLevelLine(new int[]{-1, 5}, new int[]{3, 1}) == -1, "joint level from the widest line, any order; waits while a width loads");
        int[] ov = PieceLayout.overlapFill(17, new int[]{18, 9, 6}, 2);
        check(ov != null && ov[0] == 1 && java.util.Arrays.stream(ov).skip(1).sum() == 18, "17 cells: one piece of 18 reaching 1 into a joint");
        check(PieceLayout.overlapFill(18, new int[]{18}, 2) == null || PieceLayout.overlapFill(18, new int[]{18}, 2)[0] > 0, "exact fills never need it");
        check(PieceLayout.overlapFill(15, new int[]{18}, 2) == null, "no closing beyond two cells");
        if (!new java.io.File(PackPieces.JAR).isFile()) return;
        // Colonial level 1 walls: every run length between two joints is covered exactly or within two cells of reach.
        java.util.Set<Integer> lens = new java.util.TreeSet<>();
        for (String n : List.of("long1", "medium1", "short1")) {
            PieceLayout.Grid g = PackPieces.grid("colonial/walls/" + n + ".blueprint");
            lens.add(Math.max(g.sx(), g.sz()));
        }
        int[] ls = lens.stream().mapToInt(Integer::intValue).toArray();
        List<Integer> open = new java.util.ArrayList<>();
        for (int n = 20; n <= 80; n++) {
            int cov = PieceLayout.fill(n, ls).stream().mapToInt(Integer::intValue).sum();
            if (cov != n && PieceLayout.overlapFill(n, ls, 2) == null) open.add(n);
        }
        check(open.isEmpty(), "colonial L1 walls " + lens + ": runs left open " + open);
    }

    /** Player joint alignments: file round trip, used exactly as set, destructive ones refused, reset back to auto. */
    private static void alignments() throws Exception {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("wall:walls|CORNER", "corner");
        String key = PieceLayout.alignKey("walls/tower/tower5.blueprint", 3, "walls/segment/segment5.blueprint", 1, 2);
        m.put(key, "1,2");
        var back = com.volcanicmg.mccitybuilder.planner.PieceSets.loadOverrides(com.volcanicmg.mccitybuilder.planner.PieceSets.writeOverrides(m) + "junk\n");
        check(com.volcanicmg.mccitybuilder.planner.PieceSets.writeOverrides(m).startsWith("#v2\n") && back.equals(m) && java.util.Arrays.equals(PieceLayout.parseAlign(back.get(key)), new int[]{1, 2}) && PieceLayout.parseAlign("x,1") == null
                && PieceLayout.parseAlign("99,1") == null, "alignment overrides round trip");
        check(key.equals(PieceLayout.alignKey("walls/tower/tower5.blueprint", 0, "walls/segment/segment5.blueprint", 2, 3)), "key relative to the run's direction");
        // Drafts: read before the saved value, never part of what is written; Save skips drafts that would break blocks.
        var saved = new java.util.LinkedHashMap<>(m);
        var drafts = java.util.Map.of(key, "3,1", "align|x|0|y|0", "0,0");
        var builtIn = java.util.Map.of(key, "0,0", "align|b|0|c|0", "2,0");
        check("3,1".equals(com.volcanicmg.mccitybuilder.planner.PieceSets.alignLookup(drafts, saved, builtIn, key))
                && "1,2".equals(com.volcanicmg.mccitybuilder.planner.PieceSets.alignLookup(java.util.Map.of(), saved, builtIn, key)), "a draft wins over the saved alignment");
        check("2,0".equals(com.volcanicmg.mccitybuilder.planner.PieceSets.alignLookup(drafts, saved, builtIn, "align|b|0|c|0")), "built-in alignment under the player's");
        // A joint configuration's setting holds however the shape is turned (the whole shape a quarter turn on: same
        // key, turn + 1, each run's setting following its line); a mirrored shape is another key.
        String[] sp = {"walls/s.blueprint", "walls/s.blueprint"};
        var jk = PieceLayout.jointKey("walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, null);
        java.util.function.Predicate<String> none = k -> false;
        java.util.function.Function<String, String> one = k -> k.equals(jk.key())
                ? Math.floorMod(3 - jk.rot(), 4) + " " + Math.floorMod(2 - jk.rot(), 4) + ":1,0 " + Math.floorMod(1 - jk.rot(), 4) + ":-2,1" : null;
        var f0 = PieceLayout.jointFix(one, none, "walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, null);
        var f1 = PieceLayout.jointFix(one, none, "walls/c.blueprint", new int[]{3, 2}, sp, new int[]{1, 2}, null);
        check(f0 != null && f0.turn() == 3 && java.util.Arrays.equals(f0.runs()[0], new int[]{1, 0}) && java.util.Arrays.equals(f0.runs()[1], new int[]{-2, 1})
                && f1 != null && f1.turn() == 0 && java.util.Arrays.equals(f1.runs()[0], new int[]{1, 0}) && java.util.Arrays.equals(f1.runs()[1], new int[]{-2, 1})
                && PieceLayout.jointFix(one, none, "walls/c.blueprint", new int[]{2, 3}, sp, new int[]{0, 1}, null) == null, "joint setting relative to the shape");
        // A player's run alignment under turn 1 keeps turn 1 and drops the setting's runs.
        String mine = PieceLayout.alignKey("walls/c.blueprint", 1, sp[1], 1, 1);
        var fp = PieceLayout.jointFix(one, mine::equals, "walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, null);
        check(fp != null && fp.turn() == 1 && fp.runs()[0] == null && fp.runs()[1] == null, "player alignments keep their turn over a built-in");
        // A straight that is the same turned 180°: both its turns give one key.
        check(PieceLayout.jointKey("walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, new boolean[]{true, true}).key()
                .equals(PieceLayout.jointKey("walls/c.blueprint", new int[]{2, 1}, sp, new int[]{2, 3}, new boolean[]{true, true}).key()), "symmetric straights share a key");
        mirrorChecks(jk, sp, none);
        // A file saved before the version line holds world shifts: marked and used as saved; re-saved, the mark stays until the editor writes it.
        var legacy = com.volcanicmg.mccitybuilder.planner.PieceSets.loadOverrides("wall:walls|CORNER\tcorner\n" + key + "\t1,2\n");
        check("1,2,w".equals(legacy.get(key)) && "corner".equals(legacy.get("wall:walls|CORNER")) && java.util.Arrays.equals(PieceLayout.parseAlign("1,2,w"), new int[]{1, 2, 1})
                && "1,2,w".equals(com.volcanicmg.mccitybuilder.planner.PieceSets.loadOverrides(com.volcanicmg.mccitybuilder.planner.PieceSets.writeOverrides(legacy)).get(key)) && "1,2".equals(back.get(key)), "legacy alignments load as world shifts");
        String file = com.volcanicmg.mccitybuilder.planner.PieceSets.writeOverrides(saved);
        check(!file.contains("3,1") && !file.contains("align|x|0|y|0"), "drafts are never written");
        var ok = com.volcanicmg.mccitybuilder.planner.PieceSets.saveable(java.util.Map.of("a", new int[]{1, 0}, "b", new int[]{0, 2}), java.util.Set.of("b"));
        check(ok.size() == 1 && ok.containsKey("a"), "save skips drafts that would break blocks");
        // Ranked candidates: the automatic pick first, then safe, distinct, best-first.
        {
            int[] ids = new int[5 * 3 * 5], sids = new int[9 * 3 * 3];
            java.util.Arrays.fill(ids, 1);
            java.util.Arrays.fill(sids, 1);
            PieceLayout.Grid jt = new PieceLayout.Grid(5, 3, 5, ids, 0), st = new PieceLayout.Grid(9, 3, 3, sids, 0);
            int[] c0 = {0, 0}, jb0 = PieceLayout.centred(c0, 5, 5);
            var rk = PieceLayout.ranked(c0, 0, jb0, jt, st, 3, 9, 3, 50);
            boolean sorted = true, safeAll = true, distinct = true;
            for (int i = 0; i < rk.size(); i++) {
                var f = rk.get(i);
                safeAll &= !PieceLayout.destructiveAt(c0, 0, jb0, jt, st, 3, 9, f.shift(), f.depth());
                for (int j = 0; j < i; j++) distinct &= !(rk.get(j).shift() == f.shift() && rk.get(j).depth() == f.depth());
                if (i > 1) sorted &= rk.get(i - 1).match() >= f.match() - 1e-9;
            }
            var au = PieceLayout.fitNow(c0, 0, jb0, jt, st, 3, 9, 3);
            boolean autoFirst = PieceLayout.destructiveAt(c0, 0, jb0, jt, st, 3, 9, au.shift(), au.depth()) || !rk.isEmpty() && rk.get(0).equals(au);
            check(autoFirst && sorted && safeAll && distinct, "ranked candidates: auto first when safe, all safe, distinct, best first (" + rk.size() + ")");
        }
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
        int[][] set = {{PieceLayout.alignShift(c, d, jb, 6, safe[0]), safe[1]}};
        PieceLayout.setAlign((j, s2, dd) -> j == tw && s2 == sg && dd == 0 ? set[0] : null);
        var f = PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6);
        int si = sg.inset(2), edge = PieceLayout.reach(c, d, jb);
        check(f.shift() == safe[0] && f.depth() == safe[1] && f.off() == (safe[1] < 0 ? edge : edge - safe[1] - si), "override used exactly as set: " + f);
        check(java.util.Arrays.equals(PieceLayout.attach(c, d, jb, tw, sg, 6, 9, 6), new int[]{f.off(), f.shift()}), "attach follows the override");
        // A legacy world shift lands where the player saw it on the world's joint, whatever frame the joint is laid out in.
        for (int w = 0; w < 4; w++) {
            PieceLayout.Grid twW = tw.turned(w), sgW = sg0.turned(PieceLayout.turns(sg0.sx(), sg0.sz(), w));
            int[] jbW = PieceLayout.centred(c, twW.sx(), twW.sz());
            var seen = PieceLayout.fitAt(c, w, jbW, twW, sgW, 6, 9, safe[0], safe[1]);
            for (int r = 0; r < 4; r++) {
                int dC = Math.floorMod(w - r, 4);
                var got = PieceLayout.fitBack(c, dC, PieceLayout.fitWith(c, dC, PieceLayout.rotBox(c, jbW, -r), twW.turned(-r), sgW.turned(-r), 6, 9, 6,
                        PieceLayout.relAlign(new int[]{safe[0], safe[1], 1}, c, w, jbW, 6), null), 6, 9, r);
                check(got.off() == seen.off() && got.shift() == seen.shift(), "legacy world shift as seen, run " + w + " frame " + r + ": " + got + " vs " + seen);
            }
        }
        set[0] = new int[]{safe[0], safe[1], 1};
        check(PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6).equals(auto), "a legacy value isn't applied without its world geometry");
        set[0] = new int[]{PieceLayout.alignShift(c, d, jb, 6, safe[0]), safe[1]};
        if (bad != null) {
            set[0] = new int[]{PieceLayout.alignShift(c, d, jb, 6, bad[0]), bad[1]};
            check(PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6).equals(auto), "a destructive override is refused (auto is used)");
        }
        PieceLayout.setAlign(null);
        check(PieceLayout.fit(c, d, jb, tw, sg, 6, 9, 6).equals(auto), "reset restores auto");
        // A stored shift places the run the same against the joint box however the shape is turned (an uneven box).
        int[] b0 = null, j0 = null;
        for (int dd = 0; dd < 4; dd++) {
            int[] jbd = PieceLayout.centred(c, dd % 2 == 0 ? 6 : 7, dd % 2 == 0 ? 7 : 6);
            int ws = PieceLayout.worldShift(c, dd, jbd, 4, 1);
            int[] bd = PieceLayout.boxes(c, dd, PieceLayout.reach(c, dd, jbd), List.of(9), 4, ws).get(0);
            check(PieceLayout.alignShift(c, dd, jbd, 4, ws) == 1, "stored shift round trip");
            if (dd == 0) {
                b0 = bd;
                j0 = jbd;
                continue;
            }
            // b0 turned dd quarter turns inside the joint box's frame ((x, z) -> (H-1-z, x) per turn).
            int x0 = b0[0] - j0[0], z0 = b0[1] - j0[1], x1 = b0[2] - j0[0], z1 = b0[3] - j0[1], w = 6, h = 7;
            for (int t = 0; t < dd; t++) {
                int nx0 = h - 1 - z1, nx1 = h - 1 - z0;
                z0 = x0;
                z1 = x1;
                x0 = nx0;
                x1 = nx1;
                int tmp = w;
                w = h;
                h = tmp;
            }
            check(x0 == bd[0] - jbd[0] && z0 == bd[1] - jbd[1] && x1 == bd[2] - jbd[0] && z1 == bd[3] - jbd[1], "stored shift turns with the shape: " + dd);
        }
    }

    private static void overlays() {
        check(com.volcanicmg.mccitybuilder.planner.Overlays.showCitizen(true, true) && !com.volcanicmg.mccitybuilder.planner.Overlays.showCitizen(false, false)
                && !com.volcanicmg.mccitybuilder.planner.Overlays.showCitizen(true, false), "citizen markers at live entities only (others fall back to a faded last-known spot)");
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
        // Every state: 1 to 6 compact rows; no wall-only row for roads, no Fill gaps row without gaps.
        Hints.Ctx road = new Hints.Ctx(false, true), wall = new Hints.Ctx(true, true), noGaps = new Hints.Ctx(true, false);
        for (String id : Hints.SECTIONS.keySet()) {
            for (Hints.Ctx c : List.of(road, wall, noGaps))
                check(!Hints.rows(id, false, c).isEmpty() && Hints.rows(id, false, c).size() <= 6, "compact hints, 1-6 rows: " + id + " " + c);
            check(Hints.rows(id, true, road).stream().noneMatch(Hints.Row::wallOnly), "no wall-only row for roads: " + id);
            check(Hints.rows(id, true, noGaps).stream().noneMatch(Hints.Row::gapsOnly), "no Fill gaps row without gaps: " + id);
        }
        for (String k : Hints.langKeys()) check(lang.contains("\"" + k + "\":"), "lang key: " + k);
        for (String c : HutCatalog.CATEGORIES) check(lang.contains("\"mccitybuilder.category." + c + "\":"), "category lang: " + c);
        java.util.function.Function<String, String> tr = k -> {
            int a = lang.indexOf("\"" + k + "\":");
            if (a < 0) return "J";
            int b = lang.indexOf('"', lang.indexOf(':', a) + 1) + 1;
            return lang.substring(b, lang.indexOf('"', b));
        };
        // Every row, compact and full, inside the panel (6 px a character: wider than the vanilla font's average); the
        // tallest guide shown (state + camera, all rows) fits a 1147x480 GUI under the queue, or degrades to compact.
        for (String id : Hints.SECTIONS.keySet())
            for (boolean all : new boolean[]{false, true}) {
                if (id.equals("view")) continue;
                Hints.Laid l = Hints.layout(id.equals("browse") ? List.of(id, "view") : List.of(id), all, wall, tr, s -> s.length() * 6);
                for (Hints.Item it : l.items())
                    check(it.x() >= 0 && it.x() + it.w() <= Hints.WIDTH - 2 * Hints.PAD, "hint fits: " + id + (all ? " all" : "") + " '" + it.text() + "'");
                // Actions start at one x (the action column) within a section list.
                long cols = l.items().stream().filter(it -> !it.cap() && !it.title() && !Hints.SEPARATORS.contains(it.text())).mapToInt(Hints.Item::x).distinct().count();
                check(cols == 1, "hint actions share one column: " + id);
                int h = Hints.PAD + 20 + l.height() + Hints.PAD;
                // The queue panel takes the top ~110 px of a 480 px GUI.
                if (!all) check(h <= 480 - 8 - 110, "compact guide fits at 1147x480: " + id + " " + h);
            }
        for (String id : Hints.SECTIONS.keySet()) for (Hints.Row r : Hints.SECTIONS.get(id)) check(tr.apply(r.action()).length() <= 20, "short hint: " + r.action());
        lineHelp(lang, tr);
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

    /**
     * The road / wall help (Ponder scenes) and coach tips: every string exists; every scene step parses, references
     * real segments (colours, joints), keeps its callouts and cursor on cells, and its caption fits the caption area
     * (3 lines of the viewport, 6 px a character); every tip fits its box.
     */
    private static void lineHelp(String lang, java.util.function.Function<String, String> tr) {
        java.util.function.ToIntFunction<String> w6 = s -> s.length() * 6;
        for (String k : com.volcanicmg.mccitybuilder.planner.LineHelp.langKeys()) check(lang.contains("\"" + k + "\":"), "help lang key: " + k);
        for (String k : com.volcanicmg.mccitybuilder.planner.Ponder.langKeys()) check(lang.contains("\"" + k + "\":"), "ponder lang key: " + k);
        // The viewport at a 1147x480 GUI: the panel is 460 wide, the topics 100.
        int capW = 460 - 14 - 100 - 6 - 4;
        for (var t : com.volcanicmg.mccitybuilder.planner.Ponder.TOPICS) {
            check(!t.steps().isEmpty(), "ponder topic has steps: " + t.id());
            for (var st : t.steps()) {
                List<String> cap = com.volcanicmg.mccitybuilder.planner.LineHelp.wrap(tr.apply(st.caption), capW, w6);
                check(cap.size() <= 3, "ponder caption fits 3 lines: " + st.caption + " " + cap.size());
                List<com.volcanicmg.mccitybuilder.planner.Ponder.Seg> segs = new java.util.ArrayList<>();
                for (String spec : st.lines) segs.add(com.volcanicmg.mccitybuilder.planner.Ponder.seg(spec, 9));
                for (var sg : segs) check(sg.x0() == sg.x1() || sg.z0() == sg.z1(), "ponder segment on an axis: " + st.caption);
                for (int k : st.colours.keySet()) check(k >= 0 && k < segs.size(), "ponder colour names a segment: " + st.caption);
                for (String cell : st.joints.keySet()) {
                    String[] c = cell.split(" ");
                    int x = com.volcanicmg.mccitybuilder.planner.Ponder.coord(c[0], 9), z = com.volcanicmg.mccitybuilder.planner.Ponder.coord(c[1], 9);
                    check(segs.stream().anyMatch(sg -> sg.x0() == x && sg.z0() == z || sg.x1() == x && sg.z1() == z), "ponder joint at a segment end: " + st.caption);
                }
                for (var c : st.callouts) {
                    com.volcanicmg.mccitybuilder.planner.Ponder.coord(c.x(), 9);
                    com.volcanicmg.mccitybuilder.planner.Ponder.coord(c.z(), 9);
                    check(c.y() > 0 && tr.apply(c.text()).length() <= 40, "ponder callout anchored above the ground, short: " + c.text());
                }
                if (st.cursor != null) com.volcanicmg.mccitybuilder.planner.Ponder.coord(st.cursor[0], 9);
                // Walls face one way along a chain: at a cell two arms meet (or the two arms of a T / cross in line),
                // one arrives and one leaves with the same flip, or both arrive / leave with opposite flips.
                for (var x : segs)
                    for (var y : segs) {
                        if (x == y || x.ghost() != y.ghost()) continue;
                        for (int[] c : new int[][]{{x.x0(), x.z0()}, {x.x1(), x.z1()}}) {
                            boolean xEnds = c[0] == x.x1() && c[1] == x.z1(), yStarts = c[0] == y.x0() && c[1] == y.z0(), yEnds = c[0] == y.x1() && c[1] == y.z1();
                            if (!yStarts && !yEnds) continue;
                            long arms = segs.stream().filter(q -> q.ghost() == x.ghost() && (q.x0() == c[0] && q.z0() == c[1] || q.x1() == c[0] && q.z1() == c[1])).count();
                            boolean inLine = x.x0() == x.x1() && y.x0() == y.x1() || x.z0() == x.z1() && y.z0() == y.z1();
                            if (arms > 2 && !inLine) continue;
                            check((xEnds != yEnds) == (x.flip() == y.flip()), "ponder walls face one way: " + st.caption + " at " + c[0] + "," + c[1]);
                        }
                    }
            }
        }
        check(com.volcanicmg.mccitybuilder.planner.Ponder.coord("2L-1", 9) == 17 && com.volcanicmg.mccitybuilder.planner.Ponder.coord("-L", 9) == -9, "ponder L coordinates");
        for (String[] t : com.volcanicmg.mccitybuilder.planner.LineHelp.TIPS) {
            List<String> ls = com.volcanicmg.mccitybuilder.planner.LineHelp.wrap(tr.apply("mccitybuilder.tip." + t[0]), com.volcanicmg.mccitybuilder.planner.LineHelp.TIP_W - 2 * com.volcanicmg.mccitybuilder.planner.LineHelp.TIP_PAD, w6);
            check(ls.size() <= 6, "coach tip short enough: " + t[0] + " " + ls.size());
        }
    }

    private static boolean near(double[] v, double x, double y, double z) {
        return Math.abs(v[0] - x) < 1e-9 && Math.abs(v[1] - y) < 1e-9 && Math.abs(v[2] - z) < 1e-9;
    }

    private static boolean near(double[] a, double[] b) {
        return near(a, b[0], b[1], b[2]);
    }

    /**
     * The mod's built-in settings (src/main/resources/mccitybuilder/alignments/*.txt) are well-formed, and for a third-party pack with a fixture
     * (src/test/resources/packs) every piece they name exists in it and every role pick names a group of its family. The tuned-joint ratchet file
     * (src/test/resources/joint-tuned.txt, asserted in game by tools/smoke.sh: the third-party blueprints aren't in the test, so the check list
     * can't run here) is well-formed too.
     */
    private static void builtInSettings() throws Exception {
        Map<String, String> fixtureOf = Map.of("Byzantine_for_1.20.txt", "Byzantine_for_1.20.txt", "Nile.txt", "Nile.txt", "Shogun.txt", "Shogun.txt",
                "Sword_and_Kingdom.txt", "sword_and_kingdom.txt", "______-f96960dd.txt", "sword_and_kingdom_zh.txt");
        Pattern piece = Pattern.compile("(?:^|[|:,])([^|:,]*?\\.blueprint)");
        check(PieceSets.fileName("Sword and Kingdom").equals("Sword_and_Kingdom") && PieceSets.fileName("Colonial").equals("Colonial"), "an ASCII-only pack keeps its plain name");
        check(PieceSets.fileName("\u5251\u4e0e\u738b\u56fd\u98ce\u683c").equals("______-f96960dd"), "a non-Latin pack name gets its hash: " + PieceSets.fileName("\u5251\u4e0e\u738b\u56fd\u98ce\u683c"));
        check(!PieceSets.fileName("\u4e00\u4e8c\u4e09\u56db\u4e94\u516d").equals(PieceSets.fileName("\u5251\u4e0e\u738b\u56fd\u98ce\u683c")), "two six-character non-Latin names don't share a file");
        check(!PieceSets.builtIn("\u5251\u4e0e\u738b\u56fd\u98ce\u683c").isEmpty() && PieceSets.builtIn("\u4e00\u4e8c\u4e09\u56db\u4e94\u516d").isEmpty(), "the Chinese Sword and Kingdom built-ins load for that pack only");
        int files = 0, keys = 0, checked = 0;
        try (var dir = Files.list(Path.of("src/main/resources/mccitybuilder/alignments"))) {
            for (Path f : (Iterable<Path>) dir.sorted()::iterator) {
                String name = f.getFileName().toString();
                check(name.matches("[A-Za-z0-9._-]+\\.txt"), "built-in file name is a sanitised pack name: " + name);
                java.util.Set<String> paths = new java.util.HashSet<>(), groups = new java.util.HashSet<>();
                if (fixtureOf.containsKey(name))
                    for (String line : Files.readAllLines(Path.of("src/test/resources/packs", fixtureOf.get(name)))) {
                        String[] c = line.split("\t");
                        if (c[0].equals("P")) paths.add(c[1]);
                        else if (c[0].equals("C")) groups.add(c[2] + "|" + c[4]);
                    }
                files++;
                for (String line : Files.readAllLines(f)) {
                    String[] kv = line.split("\t");
                    check(kv.length == 2 && !kv[1].isBlank(), name + ": 'key TAB value' lines only: " + line);
                    check(kv[0].matches("(align|joint|tool)\\|.*|(road|wall):[^|]+\\|[A-Z]+"), name + ": unknown key kind: " + kv[0]);
                    keys++;
                    if (fixtureOf.containsKey(name)) {
                        if (kv[0].matches("(road|wall):[^|]+\\|[A-Z]+")) check(groups.contains(kv[0].substring(0, kv[0].lastIndexOf('|')) + "|" + kv[1]), name + ": role pick names no group of its family: " + line);
                        else {
                            Matcher m = piece.matcher(kv[0]);
                            while (m.find()) {
                                check(paths.contains(m.group(1)), name + ": names a piece the fixture lacks: " + m.group(1));
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        int rows = 0;
        for (String line : Files.readAllLines(Path.of("src/test/resources/joint-tuned.txt"))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] f = line.split("\t");
            check(f.length == 4 && f[2].matches("\\d+") && f[3].matches("\\d+"), "joint-tuned.txt: pack TAB family TAB level TAB max bad: " + line);
            rows++;
        }
        check(rows > 0, "joint-tuned.txt has rows");
        System.out.println("CityBuilder self-check: built-in settings, " + files + " files, " + keys + " keys, " + checked + " piece paths checked against fixtures; " + rows + " tuned-joint ratchet rows");
    }

    /** Piece sets of packs outside MineColonies' folder / name conventions: folders, keywords, shapes; bundled packs unchanged. */
    private static void pieceSetDetection() {
        // Folders: synonyms under infrastructure/, wall words anywhere; non-pieces out; MineColonies' folders as before.
        String[][] tools = {{"infrastructure/road/straight.blueprint", "road"}, {"infrastructure/avenue_early/turn.blueprint", "road"},
                {"infrastructure/avenueodd/short.blueprint", "road"}, {"infrastructure/boulevard/end.blueprint", "road"},
                {"infrastructure/道路/x.blueprint", "road"}, {"infrastructure/retainingwall/wall/long/x.blueprint", "wall"},
                {"fortifications/x.blueprint", "wall"}, {"decorations/wall/x.blueprint", "wall"}, {"infrastructure/hangarbay/wall/x.blueprint", null},
                {"infrastructure/bridge/road/x.blueprint", null}, {"infrastructure/road/Streetlight【1】.blueprint", null},
                {"infrastructure/road/Aqueduct【1】.blueprint", null}, {"decorations/harbor/paths/x.blueprint", null},
                {"infrastructure/roads/fancy/fancylamppost.blueprint", "road"}};
        for (String[] t : tools) check(java.util.Objects.equals(PieceSets.tool(t[0]), t[1]), "tool of " + t[0] + ": " + PieceSets.tool(t[0]));
        String[][] roles = {{"crossthree", "TEE"}, {"crossthreedio", "TEE"}, {"crossfour", "CROSS"}, {"turn", "CORNER"}, {"bend_short", "CORNER"},
                {"turnwide", "CORNER"}, {"diag_short", "OTHER"}, {"offset", "OTHER"}, {"shim", "OTHER"}, {"ess_rfour", "OTHER"}, {"straightoneup", "SLOPE"},
                {"halfincline", "SLOPE"}, {"upone", "SLOPE"}, {"endwalker", "END"}, {"cornerin", "INNER"}, {"insideturn", "INNER"}, {"outsideturn", "CORNER"},
                {"3-lane road【long】", "STRAIGHT"}, {"3-lane road【short】", "STRAIGHT"}, {"3-lane road【curve】", "CORNER"},
                {"3-lane road【↑↑】", "SLOPE"}, {"3 or 5-lane wide intersection【1】", "CROSS"}, {"5-lane road【grade separation】", "OTHER"},
                {"city wall[━]", "STRAIGHT"}, {"city wall[━━━]", "STRAIGHT"}, {"city wall[╲]", "OTHER"}, {"city wall[gate]", "GATE"},
                {"city wall[■]", "OTHER"}, {"Cliff spiral staircase【Bottom】", "SLOPE"}, {"guardtower3", "TOWER"}, {"rampart", "OTHER"}};
        for (String[] r : roles) check(PieceSets.role(r[0]).name().equals(r[1]), "role of " + r[0] + ": " + PieceSets.role(r[0]));
        check(PieceSets.notInSet("diag2") && PieceSets.notInSet("city wall[━╲]") && !PieceSets.notInSet("straight"), "not-in-set names");
        // S-jogs by shape (Shogun's bend 12x8, bend_short 6x5, curve 10x7 as scanned): a "corner" running through and out
        // the far side is no corner; a curved L (turnwide 7x7) and a straight with an empty margin stay what they are.
        String[][] jogs = {
                {".......###..", ".....#######", "....########", "..##########", "#######...##", "#####.......", "####........", "##.........."},
                {".###..", "######", "######", "######", "#...##"},
                {"......##..", "....######", "..########", "##########", "######..##", "####......", "##........"}};
        for (String[] rows : jogs) {
            int sx = rows[0].length(), sz = rows.length;
            boolean[] b = new boolean[sx * sz];
            for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) b[z * sx + x] = rows[z].charAt(x) == '#';
            int got = PieceSets.openSides(b, sx, sz);
            check(got == PieceSets.THROUGH && PieceSets.decide("bend", got) == PieceSets.Role.OTHER && PieceSets.decide("curve", got) == PieceSets.Role.OTHER,
                    "an S-jog is no corner: " + PieceSets.sidesName(got));
        }
        String[] wide = {"....###", "...####", "..#####", ".######", "######.", "#####..", "####..."};
        boolean[] wb = new boolean[49];
        for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) wb[z * 7 + x] = wide[z].charAt(x) == '#';
        check(PieceSets.openSides(wb, 7, 7) != PieceSets.THROUGH && PieceSets.decide("bend", 15) == PieceSets.Role.CORNER, "a curved L stays a corner, also named bend");
        // Shapes, top-down ('#' body): straight, corner (outer sides are face bands), T (the bar is a face), cross, end; a
        // full oblong is a straight along its long side, a full square is unclear; merlon rows are trimmed.
        String[][] shapes = {
                {"..........", "##########", "##########", "##########", ".........."},
                {"#####", "#####", "##...", "##...", "##..."},
                {"#######", "#######", "..###..", "..###..", "..###.."},
                {"..###..", "..###..", "#######", "#######", "#######", "..###..", "..###.."},
                {".......", "######.", "######.", "######.", "......."},
                {"########", "########", "########"},
                {"####", "####", "####", "####"},
                {".#..#..#.", "#########", "#########", "#########", ".#..#..#."}};
        int[] want = {1 | 4, 1 | 2, 1 | 2 | 4, 15, 4, 1 | 4, -1, 1 | 4};
        String[] roleOf = {"STRAIGHT", "CORNER", "TEE", "CROSS", "END", "STRAIGHT", null, "STRAIGHT"};
        for (int i = 0; i < shapes.length; i++) {
            String[] rows = shapes[i];
            int sx = rows[0].length(), sz = rows.length;
            boolean[] b = new boolean[sx * sz];
            for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) b[z * sx + x] = rows[z].charAt(x) == '#';
            int got = PieceSets.openSides(b, sx, sz);
            check(got == want[i], "shape " + i + ": " + PieceSets.sidesName(got));
            var r = PieceSets.shapeRole(got);
            check(java.util.Objects.equals(r == null ? null : r.name(), roleOf[i]), "shape role " + i + ": " + r);
        }
        // Shape decides only names the keywords can't read; it never overrides a worded name (it would on bundled packs).
        check(PieceSets.decide("city wall[■]", 15) == PieceSets.Role.CROSS && PieceSets.decide("straight", 15) == PieceSets.Role.STRAIGHT
                && PieceSets.decide("fancylamppost", 5) == PieceSets.Role.OTHER && PieceSets.decide("diag", 5) == PieceSets.Role.OTHER
                && PieceSets.shapeHint("Coastal road【1】", 7) == PieceSets.Role.TEE, "shape only for unreadable names");
        // Sword and Kingdom walls: named straights lead over shape-only arches; a shape-only square crossing wider than the wall is a tower.
        var sk = PieceSets.classify(List.of(new PieceSets.Piece("walls/city wall[━].blueprint", "city wall[━]", 0, 9, 22, 6),
                new PieceSets.Piece("walls/city wall[∩∩∩].blueprint", "city wall[∩∩∩]", 0, 27, 22, 6),
                new PieceSets.Piece("walls/city wall[■].blueprint", "city wall[■]", 0, 9, 21, 9)),
                Map.of("walls/city wall[∩∩∩].blueprint", 5, "walls/city wall[■].blueprint", 15)).get("wall:walls");
        check(sk != null && sk.straights().get(0).base().equals("city wall[━]") && sk.of(PieceSets.Role.TOWER).size() == 1 && sk.of(PieceSets.Role.CROSS).isEmpty(),
                "S&K walls: plain straight first, [■] a tower: " + (sk == null ? null : sk.roles()));
        // Joint fixing mode: arm states, Left / Right per arm, auto-fix over turns and ranked fits.
        check(com.volcanicmg.mccitybuilder.planner.JointHelp.armState(true, 2, 0.9) == com.volcanicmg.mccitybuilder.planner.JointHelp.BREAKS
                && com.volcanicmg.mccitybuilder.planner.JointHelp.armState(false, -1, 0) == com.volcanicmg.mccitybuilder.planner.JointHelp.GAP
                && com.volcanicmg.mccitybuilder.planner.JointHelp.armState(false, 1, 0.2) == com.volcanicmg.mccitybuilder.planner.JointHelp.GAP
                && com.volcanicmg.mccitybuilder.planner.JointHelp.armState(false, 0, 0.8) == com.volcanicmg.mccitybuilder.planner.JointHelp.TOUCHING
                && com.volcanicmg.mccitybuilder.planner.JointHelp.jointState(new int[]{0, 1, 0}) == 1, "arm states");
        // Seen from an arm's far end looking at the joint: the west arm's right is south (+z), the north arm's is west (-x).
        check(com.volcanicmg.mccitybuilder.planner.JointHelp.rightShift(2) == 1 && com.volcanicmg.mccitybuilder.planner.JointHelp.rightShift(0) == -1
                && com.volcanicmg.mccitybuilder.planner.JointHelp.rightShift(3) == -1 && com.volcanicmg.mccitybuilder.planner.JointHelp.rightShift(1) == 1, "arm left / right");
        var gapF = new PieceLayout.Fit(0, 0, 0, -1);
        var okF = new PieceLayout.Fit(0, 1, 0.8, 0);
        var okG = new PieceLayout.Fit(0, -1, 0.95, 1);
        // Turn 0: one arm never touches; turn 1: both touch (second candidate for arm 0); turn 2: both, better match.
        List<List<List<PieceLayout.Fit>>> turns = new java.util.ArrayList<>(List.of(List.of(List.of(gapF), List.of(okF)), List.of(List.of(gapF, okF), List.of(okF)),
                List.of(List.of(okG), List.of(okG))));
        turns.add(null);
        int[] fx = com.volcanicmg.mccitybuilder.planner.JointHelp.autoFix(turns);
        check(fx != null && fx[0] == 2 && fx[1] == 0 && fx[2] == 0, "auto-fix: the turn where every arm touches best: " + java.util.Arrays.toString(fx));
        check(com.volcanicmg.mccitybuilder.planner.JointHelp.autoFix(List.of(List.of(List.of(gapF), List.of(okF)))) == null, "auto-fix: nothing joins, says so");
        // The palette's Other tab: road / wall folder pieces the tools don't place (slopes, unused, families without a straight).
        var other = PieceSets.classify(List.of(new PieceSets.Piece("walls/a/straight.blueprint", "straight", 0, 9, 6, 5),
                new PieceSets.Piece("walls/a/diag.blueprint", "diag", 0, 9, 6, 9), new PieceSets.Piece("walls/a/stair.blueprint", "stair", 0, 9, 6, 5),
                new PieceSets.Piece("walls/a/gate.blueprint", "gate", 0, 9, 6, 5), new PieceSets.Piece("walls/b/incline.blueprint", "incline", 0, 9, 6, 5),
                new PieceSets.Piece("walls/b/corner.blueprint", "corner", 0, 5, 6, 5)));
        check(PieceSets.toolPieces(other, PieceSets.Family::of).equals(java.util.Set.of("walls/a/straight.blueprint", "walls/a/gate.blueprint")),
                "tool pieces (the rest is Other): " + PieceSets.toolPieces(other, PieceSets.Family::of));
        check(!HutCatalog.hidden("simplequarry") && !HutCatalog.hidden("largequarry") && HutCatalog.category("mediumquarry").equals("production")
                && HutCatalog.hidden("blockpostbox") && HutCatalog.hidden("blockstash"), "quarries on the Huts tab; postbox / stash hidden");
        // A turn chosen in the joint editor is stored relative to the configuration key's rotation and read back as given.
        {
            int[] outs = {2, 1};
            String[] sps = {"walls/s.blueprint", "walls/s.blueprint"};
            int[] sts = {0, 1};
            var jk = PieceLayout.jointKey("walls/t.blueprint", outs, sps, sts, null);
            for (int t = 0; t < 4; t++) {
                String v = PieceLayout.jointValue(t, jk.rot());
                var fx2 = PieceLayout.jointFix(k -> k.equals(jk.key()) ? v : null, k -> false, "walls/t.blueprint", outs, sps, sts, null);
                check(fx2 != null && fx2.turn() == t, "joint turn round trip " + t + ": " + (fx2 == null ? null : fx2.turn()));
            }
            // A saved turn wins over alignments the player has under other turns (saved turn 2, alignments at 1 and 2).
            String v2 = PieceLayout.jointValue(2, jk.rot());
            java.util.Set<String> aligned = java.util.Set.of(PieceLayout.alignKey("walls/t.blueprint", 1, sps[0], sts[0], outs[0]),
                    PieceLayout.alignKey("walls/t.blueprint", 2, sps[0], sts[0], outs[0]));
            var fx3 = PieceLayout.jointFix(k -> k.equals(jk.key()) ? v2 : null, aligned::contains, "walls/t.blueprint", outs, sps, sts, null);
            check(fx3 != null && fx3.turn() == 2, "a saved joint turn beats alignments under other turns: " + (fx3 == null ? null : fx3.turn()));
            // L-left and L-right share the first arm's keys: clearing one case spares what the other uses at its turn.
            String shared = PieceLayout.alignKey("walls/t.blueprint", 1, sps[0], sts[0], 2), own = PieceLayout.alignKey("walls/t.blueprint", 3, sps[0], sts[0], 2);
            check(com.volcanicmg.mccitybuilder.planner.JointHelp.clearable(List.of(shared, own), java.util.Set.of(shared)).equals(List.of(own)), "mirrored case keys are spared");
        }
        // A line's family after a rename: itself, else the one family of its folder and tool (never the other tool), else none.
        check("wall:walls/walls".equals(PieceSets.resolveFamily("wall:walls/walls/tee", List.of("wall:walls/walls", "wall:walls", "road:infrastructure/roads")))
                && "road:infrastructure/road".equals(PieceSets.resolveFamily("road:infrastructure/road", List.of("road:infrastructure/road", "road:infrastructure/road/dio")))
                && PieceSets.resolveFamily("road:infrastructure/road", List.of("road:infrastructure/road/dio", "road:infrastructure/road/bevel")) == null
                && PieceSets.resolveFamily("road:infrastructure/forum", List.of("wall:infrastructure/forum")) == null
                && PieceSets.resolveFamily("road:elsewhere", List.of("road:infrastructure/road")) == null, "family keys resolve after a rename");
        // Variant chains: A of B, B of C: all in C.
        var chain = PieceSets.classify(List.of(new PieceSets.Piece("walls/a.blueprint", "a", 0, 9, 6, 5), new PieceSets.Piece("walls/b.blueprint", "b", 0, 9, 6, 5),
                new PieceSets.Piece("walls/wall.blueprint", "wall", 0, 9, 6, 5)), Map.of(), Map.of("piece|walls/a.blueprint", "variant:b", "piece|walls/b.blueprint", "variant:wall"));
        check(chain.get("wall:walls").straights().size() == 1 && chain.get("wall:walls").straights().get(0).pieces().size() == 3, "variant chains join the root group");
        // Style suffixes split a folder like prefixes do (Byzantine's ...dio).
        var dio = PieceSets.classify(List.of(new PieceSets.Piece("infrastructure/road/straight.blueprint", "straight", 0, 9, 3, 5),
                new PieceSets.Piece("infrastructure/road/straightdio.blueprint", "straightdio", 0, 9, 3, 5)));
        check(dio.containsKey("road:infrastructure/road") && dio.containsKey("road:infrastructure/road/dio"), "style suffix families: " + dio.keySet());
        // Set-up overrides: a folder / blueprint's tool (nearest key wins), a blueprint's role, variant or exclusion.
        Map<String, String> ov = new java.util.HashMap<>(Map.of("tool|infrastructure/forum", "road", "tool|infrastructure/forum/plaza_end/x.blueprint", "none",
                "tool|walls/moat", "none", "tool|walls/moat/deep", "wall", "tool|infrastructure/forum/odd", "bogus"));
        check("road".equals(PieceSets.toolOf("infrastructure/forum/plaza_mid/a.blueprint", ov)) && PieceSets.toolOf("infrastructure/forum/plaza_end/x.blueprint", ov) == null
                && PieceSets.toolOf("walls/moat/a.blueprint", ov) == null && "wall".equals(PieceSets.toolOf("walls/moat/deep/a.blueprint", ov))
                && "road".equals(PieceSets.toolOf("infrastructure/forum/odd/a.blueprint", ov)) && "wall".equals(PieceSets.toolOf("walls/a.blueprint", ov)), "tool overrides");
        List<PieceSets.Piece> set = List.of(new PieceSets.Piece("infrastructure/forum/a.blueprint", "a", 0, 9, 3, 5),
                new PieceSets.Piece("infrastructure/forum/b.blueprint", "b", 0, 9, 3, 5), new PieceSets.Piece("infrastructure/forum/c.blueprint", "c", 0, 5, 3, 5),
                new PieceSets.Piece("infrastructure/forum/lamp.blueprint", "lamp", 0, 1, 4, 1));
        var forum = PieceSets.classify(set, Map.of(), Map.of("tool|infrastructure/forum", "road", "piece|infrastructure/forum/a.blueprint", "STRAIGHT",
                "piece|infrastructure/forum/b.blueprint", "variant:a", "piece|infrastructure/forum/c.blueprint", "CORNER",
                "piece|infrastructure/forum/lamp.blueprint", "excluded")).get("road:infrastructure/forum");
        check(forum != null && forum.straights().size() == 1 && forum.straights().get(0).pieces().size() == 2 && forum.of(PieceSets.Role.CORNER).size() == 1
                && forum.roles().values().stream().flatMap(List::stream).noneMatch(g -> g.base().equals("lamp")), "role / variant / excluded overrides: " + forum);
        check(PieceSets.classify(set).isEmpty(), "an unknown folder is no set without its override");
        // Completeness: the slots a tool's set has, and which a family lacks.
        check(PieceSets.missing("road", r -> r == PieceSets.Role.STRAIGHT || r == PieceSets.Role.CORNER)
                .equals(List.of(PieceSets.Role.TEE, PieceSets.Role.CROSS, PieceSets.Role.END))
                && PieceSets.missing("wall", r -> r != PieceSets.Role.GATE).equals(List.of(PieceSets.Role.GATE)), "missing slots");
        try {
            String lang = Files.readString(Path.of("src/main/resources/assets/mccitybuilder/lang/en_us.json"));
            for (PieceSets.Role r : PieceSets.Role.values())
                for (String k : List.of("mccitybuilder.sets.missing.", "mccitybuilder.sets.role."))
                    if (k.endsWith("role.") || PieceSets.slots("wall").contains(r))
                        check(lang.contains("\"" + k + r.name().toLowerCase(java.util.Locale.ROOT) + "\":"), "lang key: " + k + r);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        // Every bundled MineColonies pack classifies exactly as recorded (bundled-piecesets.txt; PieceScan rewrites it).
        try {
            List<String> golden = Files.readAllLines(Path.of("src/test/resources/bundled-piecesets.txt")), now = PieceScan.bundled();
            List<String> gone = new java.util.ArrayList<>(golden), added = new java.util.ArrayList<>(now);
            gone.removeAll(now);
            added.removeAll(golden);
            check(gone.isEmpty() && added.isEmpty(), "bundled packs classify as recorded; lost " + gone.stream().limit(5).toList() + " new " + added.stream().limit(5).toList());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // Third-party packs: recorded detection inputs (packs/*.txt, no blueprint data) classify as their recorded result.
        try (var fs = Files.list(Path.of("src/test/resources/packs"))) {
            for (Path f : fs.filter(x -> x.toString().endsWith(".txt")).sorted().toList()) {
                List<String> all = Files.readAllLines(f), golden = all.stream().filter(l -> !l.startsWith("P\t")).toList(), now = PieceScan.classifyFixture(all);
                List<String> gone = new java.util.ArrayList<>(golden), added = new java.util.ArrayList<>(now);
                gone.removeAll(now);
                added.removeAll(golden);
                check(gone.isEmpty() && added.isEmpty(), f.getFileName() + " classifies as recorded; lost " + gone.stream().limit(5).toList() + " new " + added.stream().limit(5).toList());
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean near2(double[] v, double x, double z) {
        return Math.abs(v[0] - x) < 1e-9 && Math.abs(v[1] - z) < 1e-9;
    }

    /**
     * Mirrored joint pieces: grids (Structurize's order, mirrored first then turned), orientation arithmetic, mirrored
     * joint settings ("1m") and alignment keys next to the old plain ones, and per-joint orientations in the plan's
     * joint rows (old rows load unchanged).
     */
    private static void mirrorChecks(PieceLayout.JointKey jk, String[] sp, java.util.function.Predicate<String> none) {
        // An asymmetric 3 x 2 x 2 piece: ids 1..12 by position.
        int[] ids = new int[12];
        for (int i = 0; i < 12; i++) ids[i] = i + 1;
        PieceLayout.Grid g = new PieceLayout.Grid(3, 2, 2, ids, 0);
        PieceLayout.Grid m = g.mirrored();
        boolean flips = true;
        for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 3; x++) flips &= m.id(x, y, z) == g.id(2 - x, y, z);
        check(flips && java.util.Arrays.equals(m.mirrored().ids(), ids) && !java.util.Arrays.equals(m.ids(), ids), "mirror flips x (Mirror.FRONT_BACK), twice is the piece");
        boolean comp = true;
        for (int o : PieceLayout.ORIENTS)
            for (int k = -4; k <= 4; k++) {
                PieceLayout.Grid a = g.oriented(PieceLayout.orient(o, k)), b = g.oriented(o).turned(k);
                comp &= a.sx() == b.sx() && java.util.Arrays.equals(a.ids(), b.ids());
            }
        check(comp && java.util.Arrays.equals(g.oriented(PieceLayout.MIRROR | 1).ids(), m.turned(1).ids()) && java.util.Arrays.equals(g.oriented(3).ids(), g.turned(3).ids()),
                "orientations: mirrored then turned, more turns keep the mirror");
        boolean ords = true;
        for (int r = 0; r < 8; r++) ords &= PieceLayout.ordinal(PieceLayout.fromOrdinal(r)) == r && PieceLayout.mirrorOf(PieceLayout.fromOrdinal(r)) == r >= 4;
        check(ords && PieceLayout.normOrient(5) == 1 && PieceLayout.normOrient(4) == 0 && PieceLayout.normOrient(-1) == 3 && PieceLayout.normOrient(PieceLayout.MIRROR | 1) == (PieceLayout.MIRROR | 1)
                && !PieceLayout.mirrorOf(PieceLayout.normOrient(-3)), "stored orientations: only the mirror flag mirrors, plain ints are turns mod 4");
        // The planner's straight / gate turns (sturn) never carry a mirror, nor any frame arithmetic on them (sturn - rot,
        // turns + rot): a flipped line heading the back way gave 4 / 5, which read as mirrored before.
        boolean plain = true;
        for (int nd = 0; nd < 2; nd++)
            for (int dir = 0; dir < 4; dir++)
                for (boolean flip : new boolean[]{false, true}) {
                    int st = PieceLayout.straightTurns(nd, dir, flip);
                    plain &= st >= 0 && st < 4 && st == Math.floorMod(dir - nd + (flip ? 2 : 0), 4);
                    for (int r = -7; r <= 7; r++)
                        plain &= !PieceLayout.mirrorOf(PieceLayout.normOrient(st - r)) && !PieceLayout.mirrorOf(PieceLayout.normOrient(st + r)) && PieceLayout.ordinal(st + r) < 4
                                && PieceLayout.ordinal(Math.floorMod(dir - nd, 4) + (flip ? 2 : 0) - r) < 4;
                }
        check(plain, "straights and gates are never mirrored, flipped or not, in any frame");
        // Joint settings: the plain value is as before, a mirrored one carries "m"; both hold however the shape is turned.
        boolean values = true;
        for (int o : PieceLayout.ORIENTS) {
            for (int r = 0; r < 4; r++) {
                String v = PieceLayout.jointValue(o, r);
                values &= v.endsWith("m") == PieceLayout.mirrorOf(o) && (PieceLayout.mirrorOf(o) || v.equals(String.valueOf(Math.floorMod(o - r, 4))));
            }
            String v = PieceLayout.jointValue(o, jk.rot());
            var fx = PieceLayout.jointFix(k -> k.equals(jk.key()) ? v : null, none, "walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, null);
            values &= fx != null && fx.turn() == o;
        }
        var fm = PieceLayout.jointFix(k -> k.equals(jk.key()) ? Math.floorMod(1 - jk.rot(), 4) + "m " + Math.floorMod(2 - jk.rot(), 4) + ":0,0" : null, none, "walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, null);
        check(values && fm != null && fm.turn() == (PieceLayout.MIRROR | 1) && fm.runs()[0] != null
                && PieceLayout.jointFix(k -> k.equals(jk.key()) ? "5" : null, none, "walls/c.blueprint", new int[]{2, 1}, sp, new int[]{0, 1}, null) == null, "mirrored joint settings");
        check(PieceLayout.alignKey("j", 1, "s", 0, 2).equals("align|j|3|s|2") && PieceLayout.alignKey("j", PieceLayout.MIRROR | 1, "s", 0, 2).equals("align|j|3m|s|2"),
                "a mirrored joint's alignments have their own keys, plain ones unchanged");
        // Per joint in the plan: the type and an orientation in one value; old rows load as they were.
        long c = Plan.cell(4, -7);
        Map<Long, Integer> js = Plan.withOrient(Plan.withJoint(Map.of(), c, Plan.J_CORNER), c, PieceLayout.MIRROR | 2);
        Map<Long, Integer> back = Plan.readJoints(Plan.write(List.of(), List.of(), js));
        check(Plan.jointType(back.get(c)) == Plan.J_CORNER && Plan.jointOrient(back.get(c)) == (PieceLayout.MIRROR | 2)
                        && Plan.jointOrient(Plan.readJoints("J\t4\t-7\t3\n").get(c)) == -1 && Plan.jointType(Plan.readJoints("J\t4\t-7\t3\n").get(c)) == Plan.J_INNER
                        && Plan.jointType(Plan.withOrient(Map.of(), c, 1).get(c)) == Plan.J_AUTO && Plan.withOrient(Plan.withOrient(Map.of(), c, 1), c, -1).isEmpty()
                        && Plan.jointOrient(Plan.withJoint(js, c, Plan.J_TOWER).get(c)) == -1 && Plan.readJoints("J\t0\t0\t300\n").isEmpty(),
                "per-joint turn / mirror in the plan");
        // Built pieces: a mirrored one is written "2m"; a plain number is turns mod 4 (0.3.1 wrote 4 / 5 for flipped straights).
        var bp = new Plan.BuiltPiece(0, 0, 9, 0, "walls/c.blueprint", PieceLayout.MIRROR | 2, 1, 2, 3, 0, 0, 4, 4, "CORNER");
        var bs = new Plan.BuiltPiece(0, 0, 9, 0, "walls/s.blueprint", 1, 1, 2, 3, 0, 0, 4, 4, "STRAIGHT");
        String txt = Plan.write(List.of(), List.of(), Map.of(), Map.of(bp.key(), List.of(bp, bs)));
        var rb = Plan.readBuilt(txt).get(bp.key());
        var old = Plan.readBuilt(txt.replace("\twalls/s.blueprint\t1\t", "\twalls/s.blueprint\t5\t")).get(bp.key());
        check(txt.contains("\t2m\t") && rb != null && rb.get(0).turns() == (PieceLayout.MIRROR | 2) && rb.get(1).turns() == 1 && old != null && old.get(1).turns() == 1,
                "built pieces keep a mirror explicitly; old turns 4 / 5 load plain");
        // Run axis: with a square straight and a square joint as wide, the shared side is the width (Byzantine avenues).
        java.util.function.Function<Boolean, Integer> shortAxis = withTurn -> {
            List<PieceSets.Piece> ps = new java.util.ArrayList<>(List.of(new PieceSets.Piece("infrastructure/roads/av/straight.blueprint", "straight", 1, 12, 5, 12),
                    new PieceSets.Piece("infrastructure/roads/av/short.blueprint", "short", 1, 6, 5, 12)));
            if (withTurn) ps.add(new PieceSets.Piece("infrastructure/roads/av/turn.blueprint", "turn", 1, 12, 5, 12));
            for (var gr : PieceSets.classify(ps).get("road:infrastructure/roads/av").roles().get(PieceSets.Role.STRAIGHT))
                for (var p : gr.pieces()) if (p.path().endsWith("short.blueprint")) return p.nativeDir();
            return -1;
        };
        check(shortAxis.apply(true) == 0 && shortAxis.apply(false) == 1, "a 6x12 short beside 12x12 straights and a 12x12 turn runs along its 6 side");
        // The joint count (Pieces panel "Fix joints (N)"): bad cases named in order, the first to open, nothing shown while verdicts are pending.
        var names = List.of("Corner (left)", "Corner (right)", "T", "Cross", "End");
        var jcNone = JointCount.tally(names, new int[]{0, 0, 0, 0, 0});
        var two = JointCount.tally(names, new int[]{0, 1, 0, 1, 0});
        var wait = JointCount.tally(names, new int[]{0, 1, 2, 0, 0});
        check(jcNone.bad() == 0 && jcNone.badge().isEmpty() && jcNone.first() == -1 && jcNone.done(), "joint count: all fit shows no number");
        check(two.bad() == 2 && two.badge().equals("2") && two.failing().equals(List.of("Corner (right)", "Cross")) && two.first() == 1 && two.total() == 5,
                "joint count: bad cases counted and named in order, first to open");
        check(!wait.done() && wait.pending() == 1 && wait.badge().isEmpty() && wait.bad() == 1, "joint count: no badge until every case has a verdict");
        check(JointCount.tally(List.of(), new int[0]).done() && JointCount.tally(List.of(), new int[0]).badge().isEmpty(), "joint count: a family without joints counts 0");
        var failed = JointCount.tally(names, new int[]{0, 3, 1, 0, 3});
        check(failed.done() && failed.pending() == 0 && failed.bad() == 1 && failed.badge().equals("1") && JointCount.tally(names, new int[]{0, 3, 0, 0, 3}).badge().isEmpty(),
                "joint count: a piece that failed to load for good settles its case (no waiting, not counted)");
        // Real vs minor: only real problems badge and count in amber; a click opens the first real one, else the first minor one.
        var mn = JointCount.tally(names, new int[]{0, 4, 0, 4, 0});
        var mixed = JointCount.tally(names, new int[]{4, 0, 1, 4, 1});
        check(mn.bad() == 0 && mn.minor() == 2 && mn.badge().isEmpty() && mn.minorNames().equals(List.of("Corner (right)", "Cross")) && mn.openIndex() == 1 && mn.done(),
                "joint count: minor-only joints show no badge, are listed, and open the first minor one");
        check(mixed.bad() == 2 && mixed.minor() == 2 && mixed.badge().equals("2") && mixed.failing().equals(List.of("T", "End")) && mixed.openIndex() == 2 && mixed.firstMinor() == 0,
                "joint count: real ones count and open first, minor ones are listed apart");
        check(JointCount.stateOf(List.of()) == JointCount.OK && JointCount.stateOf(List.of("offcentre")) == JointCount.MINOR && JointCount.stateOf(List.of("overlap")) == JointCount.BAD
                        && JointCount.stateOf(List.of("opening")) == JointCount.BAD && JointCount.stateOf(List.of("nofit", "offcentre")) == JointCount.MINOR,
                "joint count: broken blocks and a hole through the wall are real, a bare gap and off-centre are minor");
        // Through-openings: a whole empty slice between the joint and the run is real; a gap that leaves blocks in every slice is minor.
        PieceLayout.Grid wJoint = block(4, 4, 2, 1, 1, 3, 0, 1), wRun = block(4, 4, 2, 1, 1, 3, 0, 1);
        check(PieceLayout.opening(wJoint, 0, 0, wRun, 5, 0, 0, false) && !PieceLayout.opening(wJoint, 0, 0, wRun, 4, 0, 0, false) && !PieceLayout.opening(wJoint, 0, 0, wRun, 3, 0, 0, false),
                "through-opening: a full-height wall hole (an empty slice) is real, touching or overlapping runs have none");
        // A berm (low earth) missing at the seam while the wall body continues: every slice still has blocks.
        PieceLayout.Grid bJoint = blocks(6, 4, 3, new int[][]{{0, 1, 0, 3, 3, 1}, {0, 1, 2, 3, 1, 2}}, 1), bRun = blocks(6, 4, 3, new int[][]{{0, 1, 0, 3, 3, 1}}, 1);
        check(!PieceLayout.opening(bJoint, 0, 0, bRun, 4, 0, 0, false), "through-opening: a gap in the berm only (the body is continuous) is minor");
        // A road: the surface (ground -1) missing across the whole width at the seam is a break; a missing kerb at the edges only is not.
        PieceLayout.Grid rJoint = blocks(6, 3, 5, new int[][]{{0, 0, 0, 3, 1, 4}}, 1), rRun = blocks(6, 3, 5, new int[][]{{0, 0, 0, 3, 1, 4}}, 1), rKerb = blocks(6, 3, 5, new int[][]{{0, 0, 1, 3, 1, 3}}, 1);
        check(PieceLayout.opening(rJoint, 0, 0, rRun, 5, 0, 0, true) && !PieceLayout.opening(rJoint, 0, 0, rRun, 4, 0, 0, true) && !PieceLayout.opening(rJoint, 0, 0, rKerb, 4, 0, 0, true),
                "through-opening: a break in the road's surface across its width is real, a missing kerb only is minor");
        check(PieceLayout.opening(wJoint, 5, 0, wRun, 0, 0, 2, false) && !PieceLayout.opening(wJoint, 5, 0, wRun, 3, 0, 2, false), "through-opening: runs going west work the same");
        check(JointCount.stateOf(List.of("opening", "nofit")) == JointCount.BAD && JointCount.stateOf(List.of("nofit")) == JointCount.MINOR && JointCount.stateOf(List.of("nofit", "offcentre")) == JointCount.MINOR
                        && JointCount.stateOf(List.of("overlap")) == JointCount.BAD, "joint count: only overlap and opening are real; a bare gap (nofit) and off-centre are minor");
        int[] many = new int[120];
        java.util.Arrays.fill(many, JointCount.BAD);
        check(JointCount.tally(java.util.Collections.nCopies(120, "x"), many).badge().equals("99+"), "joint count: badge caps at 99+");
    }

    /** A grid of {@code sx x sy x sz} with one solid box {x0, y0, z0, x1, y1, z1} (inclusive) of block 1; {@code ground} is its ground layer. */
    private static PieceLayout.Grid block(int sx, int sy, int sz, int x0, int y0, int y1, int z0, int ground) {
        return blocks(sx, sy, sz, new int[][]{{0, y0, z0, sx - 1, y1, sz - 1}}, ground);
    }

    private static PieceLayout.Grid blocks(int sx, int sy, int sz, int[][] boxes, int ground) {
        int[] ids = new int[sx * sy * sz];
        for (int[] b : boxes)
            for (int y = b[1]; y <= b[4]; y++)
                for (int z = b[2]; z <= b[5]; z++)
                    for (int x = b[0]; x <= b[3]; x++) ids[(y * sz + z) * sx + x] = 1;
        return new PieceLayout.Grid(sx, sy, sz, ids, ground);
    }

    /** A flat 60 x 30 grid (ground 64) for the route cases. */
    private static Route.Grid routeGrid() {
        int w = 60, h = 30;
        int[] g = new int[w * h];
        java.util.Arrays.fill(g, 64);
        return new Route.Grid(0, 0, w, h, g, new byte[w * h]);
    }

    private static Route.Params routeParams(int[] s, int[] t, int maxLen, boolean bridges, List<List<int[]>> avoid, java.util.Set<Integer> banStart) {
        return new Route.Params(s, t, 1, new int[]{4}, 0, 0, 2, maxLen, Route.TURN, Route.SLOPE, Route.STEEP, bridges, 8, Route.BRIDGE, avoid, Route.AVOID,
                banStart, java.util.Set.of());
    }

    private static Route.Result route(Route.Grid g, int[] s, int[] t) {
        return Route.find(g, routeParams(s, t, 256, false, List.of(), java.util.Set.of()), () -> false);
    }

    /** Every segment of a route is on an axis and droppable; between corners it tiles exactly (corner reach 2, straights 4). */
    private static void routeValid(Route.Result r, Route.Params p, String what) {
        check(r.fail() == null && r.points().size() >= 2, what + ": found (" + r.fail() + ")");
        List<int[]> pts = r.points();
        check(java.util.Arrays.equals(pts.get(0), p.start()) && java.util.Arrays.equals(pts.get(pts.size() - 1), p.end()), what + ": starts and ends where asked");
        int total = 0;
        for (int k = 0; k + 1 < pts.size(); k++) {
            int[] a = pts.get(k), b = pts.get(k + 1);
            check(a[0] == b[0] || a[1] == b[1], what + ": segment " + k + " on an axis");
            int cells = LineSnap.length(a, b) + 1, o0 = k == 0 ? p.offStart() : p.corner(), o1 = k + 2 == pts.size() ? p.offEnd() : p.corner();
            total += cells - 1;
            check(LineSnap.canDrop(cells, PieceLayout.need(o0, o1, p.lens())), what + ": segment " + k + " long enough to drop (" + cells + ")");
            if (k + 2 < pts.size()) check(LineSnap.exact(cells, o0, o1, p.lens()), what + ": segment " + k + " tiles exactly (" + cells + ")");
            if (k > 0) {
                int[] o = pts.get(k - 1);
                check((o[0] == a[0]) != (a[0] == b[0]), what + ": point " + k + " is a turn");
            }
        }
        check(total == r.length() && total <= p.maxLen(), what + ": length " + total + " = " + r.length());
    }

    private static boolean routeTouches(Route.Result r, Route.Grid g, byte flag, int half) {
        for (int[] c : Route.cells(r.points()))
            for (int dz = -half; dz <= half; dz++)
                for (int dx = -half; dx <= half; dx++)
                    if (g.in(c[0] + dx, c[1] + dz) && (g.flags()[g.at(c[0] + dx, c[1] + dz)] & flag) != 0) return true;
        return false;
    }

    /** Auto-route ({@link Route}): the pure pathfinder over terrain snapshots. */
    private static void routes() {
        int[] s = {4, 15}, t = {50, 15};
        Route.Grid flat = routeGrid();
        Route.Result straight = route(flat, s, t);
        routeValid(straight, routeParams(s, t, 256, false, List.of(), java.util.Set.of()), "route straight");
        check(straight.points().size() == 2 && straight.length() == 46, "route straight: one segment on open ground");
        // A wall across the way with a gap at the top: around it, never through it (the road's half width included).
        Route.Grid wall = routeGrid();
        for (int z = 6; z < 30; z++) wall.flags()[wall.at(27, z)] = Route.BLOCKED;
        Route.Result around = route(wall, s, t);
        routeValid(around, routeParams(s, t, 256, false, List.of(), java.util.Set.of()), "route around an obstacle");
        check(!routeTouches(around, wall, Route.BLOCKED, 1) && around.points().size() >= 4, "route around an obstacle: clear of it, with turns");
        // A steep ridge (6 blocks) across the straight line with low ground round its end: the detour wins over the climb.
        Route.Grid ridge = routeGrid();
        for (int z = 8; z < 30; z++) for (int x = 24; x <= 30; x++) ridge.ground()[ridge.at(x, z)] = 70;
        Route.Result low = route(ridge, s, t);
        routeValid(low, routeParams(s, t, 256, false, List.of(), java.util.Set.of()), "route slope avoidance");
        boolean climbs = false;
        for (int[] c : Route.cells(low.points())) climbs |= ridge.ground()[ridge.at(c[0], c[1])] > 64;
        check(!climbs, "route slope avoidance: goes round the ridge");
        // A gentle rise (1 block per 6) is simply climbed: no detour for small steps.
        Route.Grid rise = routeGrid();
        for (int x = 0; x < 60; x++) for (int z = 0; z < 30; z++) rise.ground()[rise.at(x, z)] = 64 + x / 6;
        check(route(rise, s, t).points().size() == 2, "route slope: a gentle rise is climbed straight");
        // A river with a ford at the top: without bridges the route keeps to land; with them it crosses straight.
        Route.Grid river = routeGrid();
        for (int z = 6; z < 30; z++) for (int x = 26; x <= 29; x++) river.flags()[river.at(x, z)] = Route.WATER;
        Route.Result dry = route(river, s, t);
        routeValid(dry, routeParams(s, t, 256, false, List.of(), java.util.Set.of()), "route water detour");
        check(!routeTouches(dry, river, Route.WATER, 1), "route water detour: no cell over the river");
        Route.Params bp = routeParams(s, t, 256, true, List.of(), java.util.Set.of());
        Route.Result bridge = Route.find(river, bp, () -> false);
        routeValid(bridge, bp, "route bridge crossing");
        check(bridge.points().size() == 2 && routeTouches(bridge, river, Route.WATER, 0), "route bridge crossing: straight over the river");
        for (int[] c : bridge.points()) check((river.flags()[river.at(c[0], c[1])] & Route.WATER) == 0, "route bridge: no corner on water");
        Route.Params tooWide = new Route.Params(s, t, 1, new int[]{4}, 0, 0, 2, 256, Route.TURN, Route.SLOPE, Route.STEEP, true, 3, Route.BRIDGE, List.of(), Route.AVOID,
                java.util.Set.of(), java.util.Set.of());
        check(!routeTouches(Route.find(river, tooWide, () -> false), river, Route.WATER, 1), "route bridge: a river wider than the longest bridge is gone round");
        // Joining an existing road with a T: the end lies on the road's body (its cells are free near the ends only).
        Route.Grid roads = routeGrid();
        for (int x = 0; x < 60; x++) for (int z = 21; z <= 23; z++) roads.flags()[roads.at(x, z)] = Route.ROAD;
        int[] onRoad = {30, 22}, below = {30, 4};
        Route.Result tee = route(roads, below, onRoad);
        routeValid(tee, routeParams(below, onRoad, 256, false, List.of(), java.util.Set.of()), "route T onto a road");
        check(tee.points().size() == 2, "route T onto a road: straight onto its body");
        // Never along an existing road: between two cells beside it the route keeps off it.
        Route.Result along = route(roads, new int[]{4, 18}, new int[]{50, 18});
        check(along.fail() == null && !routeTouches(along, roads, Route.ROAD, 1), "route never runs along an existing road");
        // Leaving a road's end: not back along its arm.
        Route.Params ban = routeParams(new int[]{20, 10}, new int[]{4, 10}, 256, false, List.of(), java.util.Set.of(2));
        Route.Result turned = Route.find(flat, ban, () -> false);
        check(turned.fail() == null && turned.points().size() >= 3 && PieceLayout.dir(turned.points().get(0), turned.points().get(1)) != 2,
                "route: the first segment never leaves along a banned arm");
        // The maximum length: refused when the ends are farther apart; a detour that would need more is not found.
        check("too_far".equals(Route.find(flat, routeParams(s, t, 40, false, List.of(), java.util.Set.of()), () -> false).fail()), "route over the max length is refused");
        check(around.length() > 46 && Route.find(wall, routeParams(s, t, 50, false, List.of(), java.util.Set.of()), () -> false).fail() != null,
                "route: a detour longer than the max length isn't found");
        // Near the length limit a dearer but shorter way into a corner wins: only row 5 (bumpy, dear) reaches (20, 5) short
        // enough; the cheap way in round row 0 is 10 longer and leaves no room for the last leg (the length-only retry finds it).
        int[] lg = new int[30 * 20];
        java.util.Arrays.fill(lg, 64);
        Route.Grid tight = new Route.Grid(0, 0, 30, 20, lg, new byte[30 * 20]);
        for (int x = 1; x <= 9; x++) lg[tight.at(x, 5)] = x % 2 == 0 ? 66 : 64;
        for (int x = 0; x <= 19; x++) for (int z = 6; z <= 15; z++) tight.flags()[tight.at(x, z)] = Route.BLOCKED;
        Route.Params tp = new Route.Params(new int[]{0, 5}, new int[]{20, 15}, 0, new int[]{1}, 0, 0, 0, 30, Route.TURN, Route.SLOPE, Route.STEEP, false, 0,
                Route.BRIDGE, List.of(), Route.AVOID, java.util.Set.of(), java.util.Set.of());
        Route.Result tr = Route.find(tight, tp, () -> false);
        check(tr.fail() == null && tr.length() == 30 && tr.points().size() == 3 && java.util.Arrays.equals(tr.points().get(1), new int[]{20, 5}),
                "route near the length limit: the shorter, dearer way is found (" + tr.fail() + " " + tr.length() + ")");
        // The same with room to turn south from x = 15 on: several routes fit the limit; the retry shows the one with the fewest turns.
        Route.Grid roomy = new Route.Grid(0, 0, 30, 20, lg.clone(), new byte[30 * 20]);
        for (int x = 0; x <= 14; x++) for (int z = 6; z <= 15; z++) roomy.flags()[roomy.at(x, z)] = Route.BLOCKED;
        Route.Result rr = Route.find(roomy, tp, () -> false);
        check(rr.fail() == null && rr.length() == 30 && rr.points().size() == 3, "route near the length limit: the fewest turns among the routes that fit ("
                + rr.fail() + " " + rr.points().size() + " points)");
        // No route: a wall all the way across; an end on an obstacle.
        Route.Grid shut = routeGrid();
        for (int z = 0; z < 30; z++) shut.flags()[shut.at(27, z)] = Route.BLOCKED;
        check("no_route".equals(route(shut, s, t).fail()), "route: no way through");
        check("blocked".equals(route(wall, s, new int[]{27, 20}).fail()), "route: an end on an obstacle");
        // Try another: the earlier route's cells cost more, so a different one comes back.
        Route.Grid open = routeGrid();
        int[] a = {6, 4}, b = {46, 24};
        Route.Result one = route(open, a, b);
        Route.Params again = routeParams(a, b, 256, false, List.of(one.points()), java.util.Set.of());
        Route.Result two = Route.find(open, again, () -> false);
        routeValid(one, routeParams(a, b, 256, false, List.of(), java.util.Set.of()), "route L");
        routeValid(two, again, "route try another");
        check(!Route.same(one.points(), two.points()), "route try another gives a different path");
        // Free ends (a click on open ground): the end segments needn't tile, the route's ends move in by what is left over,
        // so every segment tiles exactly and the ends stay within a straight of the clicks.
        Route.Params free = new Route.Params(a, new int[]{47, 25}, 1, new int[]{4}, 1, 1, 2, 256, Route.TURN, Route.SLOPE, Route.STEEP, false, 8, Route.BRIDGE,
                List.of(), Route.AVOID, java.util.Set.of(), java.util.Set.of(), true, true);
        Route.Result fr = Route.find(open, free, () -> false);
        check(fr.fail() == null && fr.points().size() <= 3, "route free ends: found, at most one turn (" + fr.points().size() + ")");
        List<int[]> fp = fr.points();
        for (int k = 0; k + 1 < fp.size(); k++)
            check(LineSnap.exact(LineSnap.length(fp.get(k), fp.get(k + 1)) + 1, k == 0 ? 1 : 2, k + 2 == fp.size() ? 1 : 2, new int[]{4}), "route free ends: segment " + k + " tiles exactly");
        check(LineSnap.length(fp.get(0), a) < 4 && LineSnap.length(fp.get(fp.size() - 1), new int[]{47, 25}) < 4, "route free ends: within a straight of the clicks");
        // Cancel: the search stops.
        int[] big = new int[400 * 400];
        java.util.Arrays.fill(big, 64);
        Route.Grid huge = new Route.Grid(0, 0, 400, 400, big, new byte[400 * 400]);
        for (int z = 0; z < 399; z++) huge.flags()[huge.at(200, z)] = Route.BLOCKED;
        check("cancelled".equals(Route.find(huge, routeParams(new int[]{10, 10}, new int[]{390, 10}, 1024, false, List.of(), java.util.Set.of()), () -> true).fail()),
                "route: a cancelled search stops");
        check(java.util.Arrays.equals(Route.box(new int[]{10, 5}, new int[]{0, 20}, 4), new int[]{-4, 1, 19, 24}), "route box");
        check(Route.margin(new int[]{0, 0}, new int[]{200, 0}, 256, 48) == 32 && Route.margin(new int[]{0, 0}, new int[]{10, 0}, 256, 48) == 48, "route margin");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
