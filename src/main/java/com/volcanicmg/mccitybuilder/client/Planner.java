package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.network.messages.BuildToolPlacementMessage;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.core.network.messages.server.colony.building.BuildRequestMessage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.Config;
import com.volcanicmg.mccitybuilder.planner.CameraMath;
import com.volcanicmg.mccitybuilder.planner.ColonyGeometry;
import com.volcanicmg.mccitybuilder.planner.DroneCamera;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.PlacementState;
import com.volcanicmg.mccitybuilder.planner.CameraRig;
import com.volcanicmg.mccitybuilder.planner.Columns;
import com.volcanicmg.mccitybuilder.server.InstantBuild;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Planner view state: a detached overhead camera (a client-only marker entity set as Minecraft's camera entity, so the
 * player's body stays put and renders), the hut being placed and its Structurize ghost, the selected building, and
 * the build request that follows a placement.
 * <p>
 * Placement is MineColonies' own: {@link BuildToolPlacementMessage} with its "minecolonies" survival handler (checks
 * on the server: colony membership via MANAGE_HUTS permission, inside the colony, hut item in inventory unless
 * creative, the item's colony id, town hall spacing; no reach or line-of-sight check), then a
 * {@link BuildRequestMessage} once the new hut's building view arrives, which creates the builder work order.
 */
public final class Planner {
    static final String PREVIEW_KEY = "mccitybuilder";

    private static boolean active;
    private static Marker camera;
    private static CameraType previousCameraType;
    private static IColonyView colony;
    // The drone camera (the user owns its altitude), the smoothed ground under the screen centre (only scales speeds
    // and zoom steps), the ground point a turn pivots around, and the frame clock.
    static DroneCamera drone;
    private static double groundCentre = Double.NaN, groundRaw;
    // The height loop (baseline, follow, ceiling, floor) around the drone; pure, see CameraRig.
    private static CameraRig rig;
    // Column heights from the client level. Never MOTION_BLOCKING_NO_LEAVES: clients don't receive it (see Columns).
    private static final Columns WORLD = new Columns(new Columns.Source() {
        @Override
        public boolean loaded(int x, int z) {
            var l = Minecraft.getInstance().level;
            return l != null && l.hasChunk(x >> 4, z >> 4);
        }

        @Override
        public int motionBlocking(int x, int z) {
            return Minecraft.getInstance().level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }

        @Override
        public boolean notGround(int x, int y, int z) {
            var st = Minecraft.getInstance().level.getBlockState(new BlockPos(x, y, z));
            return st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || !st.blocksMotion() && st.getFluidState().isEmpty();
        }

        @Override
        public int minY() {
            return Minecraft.getInstance().level.getMinBuildHeight();
        }
    });
    private static double[] turnPivot;
    private static boolean turning, dragTurning;
    private static long lastFrameNanos, lastSampleMs;
    private static final double GROUND_TAU = 0.3, SAMPLE_RADIUS = 12, SAMPLE_STEP = 4, ENTRY_HEIGHT = 30, SPEED_HEIGHT = 20;
    private static boolean failed;
    private static final double PICK_REACH = 1024;
    // Height above the ground when the planner last closed (client session); NaN until then.
    private static double savedAbove = Double.NaN;
    // Placing: the hut, its blueprint, rotation/mirror, the ghost (follow / adjust state), why it is invalid.
    static Block placing;
    static Blueprints.Hut placingHut;
    // A decoration being placed instead of a hut (placing is then null).
    static Blueprints.Hut deco;
    // Creative: the level the paste builds (survival always starts at 1).
    static int placingLevel = 1;
    static RotationMirror rotation = RotationMirror.NONE;
    static PlacementState place = new PlacementState();
    static String invalidReason;
    static IBuildingView selected, hovered;
    /** A placed decoration: from City Builder's records (server) or a MineColonies decoration controller in view. */
    record Deco(String pack, String path, BlockPos pos, int rot) {
        String name() {
            return path.substring(path.lastIndexOf('/') + 1).replace(".blueprint", "");
        }
    }

    static Deco selectedDeco, hoveredDeco, movingDeco;
    // The server's decoration records per colony id (kept across planner sessions; a reopen's query may be rate limited).
    private static final java.util.Map<Integer, java.util.List<Deco>> syncedDecos = new java.util.HashMap<>();

    /** Logout: colony ids repeat across worlds and servers. */
    static void clearDecos() {
        syncedDecos.clear();
    }
    private static java.util.List<Deco> controllerDecos = java.util.List.of();
    // The colony's claimed chunks (client claim data, refreshed each second), its border edges and pan bounds.
    private static java.util.Set<Long> claimed = java.util.Set.of();
    private static java.util.List<int[]> border = java.util.List.of();
    private static double[] bounds;
    private static long lastClaimsMs;
    // Recently placed huts, newest first.
    static final java.util.List<Block> recent = new java.util.ArrayList<>();
    // Huts we placed whose build requests go out once their building views exist (placement time).
    private static final java.util.Map<BlockPos, Long> pendingBuilds = new java.util.HashMap<>();

    private Planner() {
    }

    static boolean active() {
        return active;
    }

    /** Runs planner work; any exception logs (once per session), removes our preview and leaves planner view. */
    static void guard(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** {@link #guard} for screen handlers that answer whether they consumed the event. */
    static boolean guard(java.util.function.BooleanSupplier r) {
        try {
            return r.getAsBoolean();
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    private static void fail(Throwable t) {
        if (!failed) com.volcanicmg.mccitybuilder.CityBuilder.LOGGER.error("CityBuilder failed; leaving planner view", t);
        failed = true;
        try {
            RenderingCache.removeBlueprint(PREVIEW_KEY);
        } catch (Throwable ignored) {
            // Best effort: restoring the camera below matters more.
        }
        placing = null;
        placingHut = null;
        place = new PlacementState();
        try {
            exit(Component.translatable("mccitybuilder.exit.error"));
        } catch (Throwable t2) {
            // Last resort: hand the camera back no matter what.
            active = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.setCameraEntity(mc.player);
            if (previousCameraType != null) mc.options.setCameraType(previousCameraType);
            if (mc.screen instanceof PlannerScreen) mc.setScreen(null);
        }
    }

    static IColonyView colony() {
        return colony;
    }

    static void toggle() {
        if (active) exit(null);
        else enter();
    }

    /**
     * @return the id of the colony owning the chunk the player stands in; 0 for none; -1 when the client has no claim
     * data there (unclaimed, or not synced yet: {@link #claimsAround} tells which)
     */
    private static int owner(Minecraft mc) {
        var claim = IColonyManager.getInstance().getClaimData(mc.level.dimension(), new net.minecraft.world.level.ChunkPos(mc.player.blockPosition()));
        return claim == null ? -1 : claim.getOwningColony();
    }

    /** @return true when the client has claim data for some chunk within {@code r} chunks of the player (probed per chunk) */
    private static boolean claimsAround(Minecraft mc, int r) {
        var here = new net.minecraft.world.level.ChunkPos(mc.player.blockPosition());
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (IColonyManager.getInstance().getClaimData(mc.level.dimension(), new net.minecraft.world.level.ChunkPos(here.x + dx, here.z + dz)) != null) return true;
        return false;
    }

    /** Enters planner view over the colony the player stands in; needs membership and hut access there. */
    static void enter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        LineView.closeJointMenu(false);
        // Only from inside a colony the player belongs to: the owner of the chunk they stand in (MineColonies' claims).
        int owner = owner(mc);
        IColonyView c = owner > 0 ? IColonyManager.getInstance().getColonyView(owner, mc.level.dimension()) : null;
        String why = ColonyGeometry.openCheck(owner, owner >= 0 || claimsAround(mc, 8), c != null, c != null && c.getPermissions().isColonyMember(mc.player),
                c != null && c.getPermissions().hasPermission(mc.player, Action.ACCESS_HUTS));
        if (why != null) {
            mc.player.displayClientMessage(Component.translatable("mccitybuilder.open." + why), true);
            return;
        }
        colony = c;
        refreshClaims();
        refreshDecos();
        var conn = mc.getConnection();
        if (conn != null && conn.hasChannel(com.volcanicmg.mccitybuilder.server.DecoSync.Query.TYPE))
            PacketDistributor.sendToServer(new com.volcanicmg.mccitybuilder.server.DecoSync.Query(c.getID()));
        camera = new Marker(EntityType.MARKER, mc.level);
        // Start above the land at the player (the same baseline the limits use), looking 60° down the way the player
        // faces; the height is the last session's, clamped to today's range, or 30 blocks the first time.
        double ground = CameraMath.sampleQuantile(mc.player.getX(), mc.player.getZ(), CameraRig.BASELINE_RADIUS, CameraRig.SAMPLE_STEP, 0.5,
                mc.player.getY(), WORLD::ground);
        double above = Double.isNaN(savedAbove) ? ENTRY_HEIGHT : CameraMath.clamp(savedAbove, Config.MIN_HEIGHT.get(), maxAbove());
        drone = new DroneCamera(mc.player.getX(), ground + above, mc.player.getZ(), mc.player.getYRot());
        drone.minAbove = Config.MIN_HEIGHT.get();
        drone.maxAbove = maxAbove();
        rig = new CameraRig(drone);
        PlanView.load(c);
        rig.top = mc.level.getMaxBuildHeight();
        groundCentre = Double.NaN;
        // The marker sits at the camera before the first pick (else the first ray starts at 0,0,0).
        camera.setPos(drone.camX(), drone.y(), drone.camZ());
        lastFrameNanos = lastSampleMs = 0;
        turnPivot = null;
        turning = false;
        previousCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        active = true;
        updateCamera(1);
        mc.setCameraEntity(camera);
        mc.setScreen(new PlannerScreen());
    }

    /** Leaves planner view (also on death, damage, dimension change, disconnect). @param why message or null */
    static void exit(Component why) {
        if (!active) return;
        active = false;
        Minecraft mc = Minecraft.getInstance();
        stopPlacing();
        PlanView.hide();
        selected = null;
        selectedDeco = hoveredDeco = null;
        // Measured like the scroll clamp (from the centre ground), so a full zoom-out restores as 160, not more.
        if (drone != null && !Double.isNaN(baseline())) savedAbove = drone.altitudeTarget + rig.pendingFollow() - baseline();
        if (mc.player != null) mc.setCameraEntity(mc.player);
        if (previousCameraType != null) mc.options.setCameraType(previousCameraType);
        // The planner's screens close with it (the joint editor drops its unsaved drafts when removed).
        if (mc.screen instanceof PlannerScreen || mc.screen instanceof JointEditorScreen) mc.setScreen(null);
        PieceCatalog.clearDrafts();
        camera = null;
        drone = null;
        colony = null;
        if (why != null && mc.player != null) mc.player.displayClientMessage(why, true);
    }

    /** Safety exits, reopening the planner after a hut window closes, and the pending build request. */
    static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || camera == null) {
            exit(null);
            return;
        }
        if (mc.player.isDeadOrDying()) exit(Component.translatable("mccitybuilder.exit.died"));
        else if (mc.player.hurtTime > 0) exit(Component.translatable("mccitybuilder.exit.hurt"));
        else if (camera.level() != mc.level) exit(Component.translatable("mccitybuilder.exit.dimension"));
        // Out of the colony (teleported, pushed): another colony's or unclaimed land, once claims around are known.
        else if (ColonyGeometry.leftColony(owner(mc), colony.getID(), owner(mc) < 0 && claimsAround(mc, 2))) exit(Component.translatable("mccitybuilder.exit.left_colony"));
        if (!active) return;
        // A hut window (or any screen) opened from the planner: come back when it closes.
        if (mc.screen == null) mc.setScreen(new PlannerScreen());
        refreshColony();
        OverlayView.refresh(colony);
        if (System.currentTimeMillis() - lastClaimsMs > 1000) {
            lastClaimsMs = System.currentTimeMillis();
            refreshClaims();
            refreshDecos();
        }
        PlanView.tick();
        for (var it = pendingBuilds.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            IBuildingView b = colony.getClientBuildingManager().getBuilding(en.getKey());
            if (b != null) {
                new BuildRequestMessage(b, BuildRequestMessage.Mode.BUILD, BlockPos.ZERO).sendToServer();
                it.remove();
            } else if (System.currentTimeMillis() - en.getValue() > 15_000) it.remove();
        }
    }

    /** The colony view object is replaced on resync; look it up again by id. */
    /** Re-reads the colony's claimed chunks from MineColonies' client claim data. */
    private static void refreshClaims() {
        Minecraft mc = Minecraft.getInstance();
        java.util.Set<Long> out = new java.util.HashSet<>();
        var all = IColonyManager.getInstance().getClaimData(mc.level.dimension());
        if (all != null)
            all.forEach((pos, data) -> {
                if (data != null && data.getOwningColony() == colony.getID()) out.add(pos.toLong());
            });
        claimed = out;
        border = ColonyGeometry.borderEdges(out);
        bounds = ColonyGeometry.bounds(out, 32);
    }

    /** The server's decoration records for a colony (stored even while the planner is closed). */
    static void onDecos(com.volcanicmg.mccitybuilder.server.DecoSync.Decos d) {
        java.util.List<Deco> out = new java.util.ArrayList<>();
        for (var r : d.recs()) out.add(new Deco(r.pack(), r.path(), new BlockPos(r.x(), r.y(), r.z()), r.rot()));
        syncedDecos.put(d.colony(), out);
        if (active && colony != null && d.colony() == colony.getID()) refreshDecos();
    }

    /** Re-reads the decoration controllers in the colony's loaded chunks; a selection that is gone is dropped. */
    private static void refreshDecos() {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<Deco> out = new java.util.ArrayList<>();
        for (long c : claimed) {
            var chunk = mc.level.getChunkSource().getChunk(ColonyGeometry.chunkX(c), ColonyGeometry.chunkZ(c), false);
            if (chunk == null) continue;
            for (var be : chunk.getBlockEntities().values())
                if (be instanceof com.minecolonies.core.tileentities.TileEntityDecorationController d && d.getPackName() != null && !d.getPackName().isEmpty()
                        && d.getBlueprintPath() != null && d.getRotationMirror() != null) {
                    String path = com.volcanicmg.mccitybuilder.planner.PasteRules.cleanPath(d.getBlueprintPath());
                    if (path.endsWith(".blueprint") && com.volcanicmg.mccitybuilder.planner.PieceSets.tool(path) == null)
                        out.add(new Deco(d.getPackName(), path, be.getBlockPos().immutable(), d.getRotationMirror().ordinal()));
                }
        }
        controllerDecos = out;
        if (selectedDeco != null && !decos().contains(selectedDeco)) selectedDeco = null;
    }

    /** @return the colony's known decorations, one per anchor (a server record wins over a controller) */
    static java.util.Collection<Deco> decos() {
        java.util.Map<BlockPos, Deco> by = new java.util.LinkedHashMap<>();
        for (Deco d : controllerDecos) by.put(d.pos(), d);
        if (colony != null) for (Deco d : syncedDecos.getOrDefault(colony.getID(), java.util.List.of())) by.put(d.pos(), d);
        return by.values();
    }

    /** @return the decoration's block box at its anchor, or null while its blueprint loads */
    static int[] box(Deco d) {
        int[] r = Blueprints.bounds(d.pack(), d.path(), RotationMirror.values()[Math.floorMod(d.rot(), RotationMirror.values().length)]);
        return r == null ? null : ColonyGeometry.placed(r, d.pos().getX(), d.pos().getY(), d.pos().getZ());
    }

    private static void refreshColony() {
        Minecraft mc = Minecraft.getInstance();
        IColonyView fresh = IColonyManager.getInstance().getColonyView(colony.getID(), mc.level.dimension());
        if (fresh != null) colony = fresh;
        if (selected != null) selected = colony.getClientBuildingManager().getBuilding(selected.getID());
    }

    /**
     * Moves the drone for this frame: WASD / screen-edge input (polled each frame, so release stops at once), Q/E turn
     * around the ground point at the screen centre, clearance above nearby terrain, and the loaded-area bound.
     */
    static void updateCamera(float partial) {
        if (!active || camera == null || drone == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        if (System.currentTimeMillis() - lastSampleMs > 250) {
            lastSampleMs = System.currentTimeMillis();
            groundRaw = centreGround();
        }
        groundCentre = Double.isNaN(groundCentre) ? groundRaw : CameraMath.ease(groundCentre, groundRaw, dt, GROUND_TAU);
        // Never higher above the ground than the world still renders below the camera (see maxAbove).
        drone.maxAbove = maxAbove();
        rig.followTerrain = Config.FOLLOW_TERRAIN.get();
        // Bounds live in the drone, so turns, pans and the pivot all respect them: the loaded area (wins) and the colony.
        drone.circleX = mc.player.getX();
        drone.circleZ = mc.player.getZ();
        drone.circleR = Math.max(16, (mc.options.getEffectiveRenderDistance() - 1) * 16.0);
        drone.box = bounds;
        // The colony out of the loaded area (a teleport): the clamps would fight over unloaded land, so close instead.
        if (drone.outOfBounds()) {
            exit(Component.translatable("mccitybuilder.exit.left_colony"));
            return;
        }
        double right = 0, ahead = 0, turn = 0;
        // Shift: faster panning; the velocity eases to the new speed, so no jump on press or release.
        boolean fast = mc.screen instanceof PlannerScreen && mc.isWindowActive() && !PlannerScreen.typing() && Screen.hasShiftDown();
        if (mc.screen instanceof PlannerScreen && mc.isWindowActive() && !PlannerScreen.typing()) {
            if (PlannerClient.down(mc.options.keyUp.getKey().getValue())) ahead++;
            if (PlannerClient.down(mc.options.keyDown.getKey().getValue())) ahead--;
            if (PlannerClient.down(mc.options.keyRight.getKey().getValue())) right++;
            if (PlannerClient.down(mc.options.keyLeft.getKey().getValue())) right--;
            if (Config.EDGE_PAN.get()) {
                double mx = mc.mouseHandler.xpos() / mc.getWindow().getWidth(), my = mc.mouseHandler.ypos() / mc.getWindow().getHeight();
                if (mx <= 0.003) right--;
                if (mx >= 0.997) right++;
                if (my <= 0.003) ahead++;
                if (my >= 0.997) ahead--;
            }
            if (PlannerClient.down(org.lwjgl.glfw.GLFW.GLFW_KEY_Q)) turn--;
            if (PlannerClient.down(org.lwjgl.glfw.GLFW.GLFW_KEY_E)) turn++;
        }
        // The pivot is found when a turn starts and then moves along with any panning, so WASD and Q/E combine.
        if (turn != 0 && !turning) turnPivot = centrePoint();
        turning = turn != 0;
        rig.frame(WORLD, dt, right, ahead, turn * Config.TURN_SPEED.get(), turning || dragTurning ? turnPivot : null,
                Config.PAN_SPEED.get() / SPEED_HEIGHT * (fast ? Config.FAST_PAN.get() : 1), groundCentre);
        double y = drone.y();
        camera.setPos(drone.camX(), y, drone.camZ());
        camera.xo = camera.xOld = drone.camX();
        camera.yo = camera.yOld = y;
        camera.zo = camera.zOld = drone.camZ();
        camera.setYRot(drone.yaw);
        camera.setXRot(drone.pitch);
        camera.yRotO = drone.yaw;
        camera.xRotO = drone.pitch;
        if (isPlacing()) updatePreview();
        PlanView.refresh();
        PlanView.refreshLines();
    }

    /**
     * The zoom ceiling above the ground: {@link DroneCamera#MAX_ABOVE}, but vanilla skips sections more than the
     * render distance (in blocks) above or below the camera, so one section less than that.
     */
    private static double maxAbove() {
        double rd = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 16;
        return Math.max(Config.MIN_HEIGHT.get(), Math.min(DroneCamera.MAX_ABOVE, rd));
    }

    /** @return the camera's height above the smoothed ground under the screen centre (0 when unknown) */
    static double heightAboveGround() {
        return drone == null || Double.isNaN(baseline()) ? 0 : Math.max(0, drone.y() - baseline());
    }

    /** @return the terrain baseline under the camera, the one height reference (NaN before the first sample) */
    static double baseline() {
        return rig == null ? Double.NaN : rig.baseline();
    }

    /** Scroll: altitude only; with {@code zoomToCursor} the point under the cursor stays under it. */
    static void zoom(double notches, double fx, double fy) {
        Minecraft mc = Minecraft.getInstance();
        if (drone == null || mc.level == null) return;
        double ground = Double.isNaN(baseline()) ? drone.y() - 20 : baseline();
        double above = Math.max(1, drone.altitudeTarget - ground);
        double dAlt = rig.scroll(notches);
        if (Config.ZOOM_TO_CURSOR.get() && dAlt != 0) {
            BlockHitResult cur = pick(fx, fy);
            double[] mid = centrePoint();
            if (cur != null && mid != null) {
                double[] s = CameraMath.zoomShift(cur.getLocation().x - mid[0], cur.getLocation().z - mid[2], CameraMath.clamp(-dAlt / above, -1, 1), dAlt);
                drone.x += s[0];
                drone.z += s[1];
                drone.clampXZ();
            }
        }
    }

    /** Middle-drag: yaw and pitch around the screen-centre ground point found when the drag began. */
    static void startDragTurn() {
        turnPivot = centrePoint();
        dragTurning = true;
    }

    static void endDragTurn() {
        dragTurning = false;
    }

    static void dragTurn(float dyaw, float dpitch) {
        if (drone != null) drone.turn(turnPivot, dyaw, dpitch);
    }

    /** Toggles straight down / the default tilt, around the screen-centre ground point. */
    static void togglePitch() {
        if (drone == null) return;
        float target = drone.pitch >= DroneCamera.MAX_PITCH - 0.5f ? DroneCamera.DEFAULT_PITCH : DroneCamera.MAX_PITCH;
        drone.turn(centrePoint(), 0, target - drone.pitch);
    }

    /** @return the ground point at the screen centre, or null if the centre ray hits nothing in range */
    private static double[] centrePoint() {
        BlockHitResult hit = pick(0.5, 0.5);
        return hit == null ? null : new double[]{hit.getLocation().x, hit.getLocation().y, hit.getLocation().z};
    }

    /**
     * Ground height under the screen centre (hit point); on a miss the last value, else the terrain around the camera.
     * Never derived from the camera's own height, which would feed back into it.
     */
    private static double centreGround() {
        double[] p = centrePoint();
        if (p != null) return p[1];
        return !Double.isNaN(groundCentre) ? groundRaw : sampleTerrain(drone.x, drone.z, Minecraft.getInstance().level.getSeaLevel());
    }

    /** Surface heights (leaves ignored) on a 4-block grid within 12 of (x, z): the 75% quantile, or {@code fallback}. */
    private static double sampleTerrain(double x, double z, double fallback) {
        return CameraMath.sampleQuantile(x, z, SAMPLE_RADIUS, SAMPLE_STEP, 0.75, fallback, WORLD::ground);
    }

    /**
     * Casts the ray under the mouse from the planner camera.
     *
     * @param fx mouse x as a fraction of the window width; @param fy mouse y as a fraction of its height
     * @return the block hit, or null
     */
    static BlockHitResult pick(double fx, double fy) {
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || mc.level == null) return null;
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double[] d = CameraMath.ray(drone.yaw, drone.pitch, mc.options.fov().get(), aspect, fx * 2 - 1, 1 - fy * 2);
        // Far enough for shallow rays from the zoom ceiling (160 up at 30° is 320 to the centre alone).
        Vec3 from = camera.position(), to = from.add(d[0] * PICK_REACH, d[1] * PICK_REACH, d[2] * PICK_REACH);
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, camera));
        return hit.getType() == HitResult.Type.MISS ? null : hit;
    }

    /** @return where a world point appears on screen {ndcX, ndcY}, or null when behind the camera */
    static double[] project(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || drone == null) return null;
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        return CameraMath.project(new double[]{camera.getX(), camera.getY(), camera.getZ()}, drone.yaw, drone.pitch, mc.options.fov().get(), aspect,
                new double[]{x, y, z});
    }

    // ---- placing ----

    static void startPlacing(Block hut) {
        // A move in progress ends with any new placement (startMove / startMoveDeco set theirs again after).
        moving = null;
        movingDeco = null;
        placing = hut;
        deco = null;
        placingHut = null;
        placingLevel = 1;
        rotation = RotationMirror.NONE;
        place = new PlacementState();
        selected = null;
        selectedDeco = null;
    }

    /** Creative: switches the ghost to another level of the same hut, keeping its spot. */
    static void setPlacingLevel(int level) {
        if (placing == null || level == placingLevel) return;
        placingLevel = level;
        placingHut = null;
        RenderingCache.removeBlueprint(PREVIEW_KEY);
    }

    /** Starts placing a decoration (any non-hut blueprint of the style pack). */
    static void startPlacingDeco(Blueprints.Hut d) {
        startPlacing(null);
        deco = d;
        placingHut = d;
    }

    /** @return true while a hut or a decoration ghost is out */
    static boolean isPlacing() {
        return placing != null || deco != null;
    }

    /** @return the name of what is being placed (hut block name or decoration file name) */
    static String placingName() {
        return deco != null ? deco.blueprint().getFileName() : placing != null ? placing.getName().getString() : "";
    }

    static void stopPlacing() {
        moving = null;
        movingDeco = null;
        placing = null;
        deco = null;
        placingHut = null;
        place = new PlacementState();
        invalidReason = null;
        RenderingCache.removeBlueprint(PREVIEW_KEY);
    }

    /** @return the ghost's anchor (hut block) position, or null */
    static BlockPos anchor() {
        return isPlacing() && place.hasAnchor ? new BlockPos(place.x, place.y, place.z) : null;
    }

    static boolean adjusting() {
        return isPlacing() && place.mode == PlacementState.Mode.ADJUST;
    }

    static void rotatePlacing() {
        rotation = rotation.rotate(Rotation.CLOCKWISE_90);
        applyPreviewTransform();
    }

    static void mirrorPlacing() {
        rotation = rotation.mirrorate();
        applyPreviewTransform();
    }

    /**
     * FOLLOW mode, each frame: the anchor goes on the ground under the cursor, like the build tool's default ground
     * style (the looked-at block raised by the blueprint's ground anchor offset minus one), plus the height offset.
     */
    static void aim(BlockHitResult hit) {
        if (!isPlacing() || place.mode != PlacementState.Mode.FOLLOW) return;
        if (hit == null) {
            place.lose();
            return;
        }
        BlockPos ground = hit.getBlockPos();
        // The ray stops on grass and flowers: the ground is the first real block under the hit.
        int gy = WORLD.settle(ground.getX(), ground.getY(), ground.getZ());
        int offset = placingHut != null ? BlueprintTagUtils.getGroundAnchorOffset(placingHut.blueprint(), 1) - 1 : 0;
        place.follow(ground.getX(), gy + offset, ground.getZ());
    }

    /** First click: drop the ghost where it is and switch to ADJUST. @return true if dropped */
    static boolean drop() {
        return isPlacing() && placingHut != null && place.drop();
    }

    /** ADJUST: arrow-key nudge relative to the camera. */
    static void nudge(PlacementState.Dir dir, boolean far) {
        if (drone == null) return;
        int[] d = PlacementState.step(drone.yaw, dir, far ? 5 : 1);
        place.move(d[0], d[1]);
    }

    static void raise(int d) {
        if (isPlacing()) place.raise(d);
    }

    static void snapToGround() {
        if (isPlacing()) place.snap();
    }

    /** Esc / right-click: ADJUST back to FOLLOW, else stop placing. */
    static void back() {
        if (!isPlacing()) return;
        if (!place.back()) stopPlacing();
    }

    // Left-drag on the ghost in ADJUST: the ground point it started from.
    private static BlockPos dragFrom;

    static void startGhostDrag(BlockHitResult hit) {
        dragFrom = adjusting() && hit != null ? hit.getBlockPos() : null;
    }

    static void ghostDrag(BlockHitResult hit) {
        if (dragFrom == null || hit == null || !adjusting()) return;
        BlockPos to = hit.getBlockPos();
        place.move(to.getX() - dragFrom.getX(), to.getZ() - dragFrom.getZ());
        dragFrom = to;
    }

    static void endGhostDrag() {
        dragFrom = null;
    }

    /**
     * Keeps our Structurize preview in step: nothing is queued until the blueprint is loaded and there is an anchor.
     * {@code setGroundOffset} is never used: it shifts an existing pos (and dereferences it), while our anchor already
     * includes the ground offset.
     */
    private static void updatePreview() {
        if (placingHut == null) placingHut = deco != null ? deco : Blueprints.get(PlannerScreen.pack(), placing, creative() ? placingLevel : 1);
        invalidReason = validate();
        BlockPos at = anchor();
        if (placingHut == null || at == null) {
            RenderingCache.removeBlueprint(PREVIEW_KEY);
            return;
        }
        BlueprintPreviewData data = RenderingCache.getBlueprintPreviewData(PREVIEW_KEY);
        if (data == null) applyPreviewTransform();
        else if (!at.equals(data.getPos())) data.setPos(at);
    }

    /** (Re)queues the preview: blueprint, then rotation, then position (each setter's precondition is the previous). */
    private static void applyPreviewTransform() {
        BlockPos at = anchor();
        if (placingHut == null || at == null) {
            RenderingCache.removeBlueprint(PREVIEW_KEY);
            return;
        }
        BlueprintPreviewData data = new BlueprintPreviewData(false);
        data.setBlueprint(placingHut.blueprint());
        data.setRotationMirror(rotation);
        data.setPos(at);
        RenderingCache.queue(PREVIEW_KEY, data);
    }

    /** @return the blueprint's block bounds at the anchor {min, max}, or null */
    static BlockPos[] footprint() {
        BlockPos at = anchor();
        if (placingHut == null || at == null) return null;
        var bp = placingHut.blueprint();
        BlockPos min = at.subtract(bp.getPrimaryBlockOffset());
        return new BlockPos[]{min, min.offset(bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1)};
    }

    /** Client-side check (the server checks again): blueprint loaded, footprint in the colony, hut in inventory. */
    private static String validate() {
        Minecraft mc = Minecraft.getInstance();
        if (placingHut == null) return "loading";
        if (anchor() == null) return "no_target";
        // MineColonies' server rule: every chunk under the blueprint belongs to the colony.
        BlockPos[] f = footprint();
        if (!ColonyGeometry.footprintClaimed(f[0].getX(), f[0].getZ(), f[1].getX(), f[1].getZ(), claimed)) return "outside";
        if (placing != null && !mc.player.isCreative() && mc.player.getInventory().countItem(placing.asItem()) <= 0) return "no_item";
        if (!colony.getPermissions().hasPermission(mc.player, Action.MANAGE_HUTS)) return "no_permission";
        return null;
    }

    /** @return true when placing pastes the hut at once: the build tool's creative "Pretty" placement */
    static boolean creative() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.isCreative();
    }

    /**
     * Sends the placement. Survival: Structurize's build tool message with MineColonies' survival handler (hut block
     * placed at level 0, item taken), then a build request once the building view arrives. Creative: the build tool's
     * {@code HandlerType.Pretty} paste: placeholders become fill / air, waypoints are registered instead of placed, and
     * MineColonies registers the building (at level 1 whatever the blueprint's level: its setup misreads the digit).
     */
    static boolean confirm() {
        BlockPos at = anchor();
        // A decoration being moved: pasted at the ghost, its containers copied over, the old one deleted (server part).
        if (movingDeco != null && deco != null && at != null) {
            if (validate() != null || !creative() || !serverInstant()) return false;
            PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.DECO_MOVE, at, 0, deco.pack(), deco.path(), rotation, PlanView.decoRequest(),
                    movingDeco.pos(), RotationMirror.values()[Math.floorMod(movingDeco.rot(), RotationMirror.values().length)]));
            stopPlacing();
            return true;
        }
        // Plan mode: the ghost goes into the plan instead (the plan's own checks show conflicts).
        if (PlanView.on && isPlacing() && placingHut != null && at != null) {
            PlanView.add(placing, placingHut, deco != null ? 0 : creative() ? placingLevel : 1, at, rotation);
            stopPlacing();
            return true;
        }
        if (!isPlacing() || placingHut == null || at == null || validate() != null) return false;
        if (deco != null) {
            // Decorations: creative pastes (server part if present, else Structurize's own pretty paste); survival goes
            // through MineColonies' survival handler, which opens its decoration build window (a builder work order).
            if (creative() && serverInstant())
                PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.DECO, at, 0, deco.pack(), deco.path(), rotation));
            else new BuildToolPlacementMessage(creative() ? BuildToolPlacementMessage.HandlerType.Pretty : BuildToolPlacementMessage.HandlerType.Survival,
                    creative() ? "" : "minecolonies", deco.pack(), deco.path(), at, rotation).sendToServer();
            stopPlacing();
            return true;
        }
        if (moving != null && creative() && serverInstant()) {
            PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.MOVE, at, moving.getBuildingLevel(), "", "", rotation, 0, moving.getPosition(), RotationMirror.NONE));
            moving = null;
            stopPlacing();
            return true;
        }
        if (creative() && serverInstant()) {
            PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.PLACE, at, creative() ? placingLevel : 1, placingHut.pack(), placingHut.path(), rotation));
        } else if (creative()) {
            new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Pretty, "", placingHut.pack(), placingHut.path(),
                    at, rotation).sendToServer();
        } else {
            new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Survival, "minecolonies", placingHut.pack(), placingHut.path(),
                    at, rotation).sendToServer();
            pendingBuilds.put(at, System.currentTimeMillis());
        }
        recent.remove(placing);
        recent.add(0, placing);
        if (recent.size() > 9) recent.remove(9);
        stopPlacing();
        return true;
    }

    /**
     * Creative upgrade. With CityBuilder on the server: its instant build (next level, hut block and storage kept,
     * level recorded). Without: only an unbuilt (level 0) hut gets a Pretty paste of level 1; built huts are not pasted
     * over (MineColonies' Pretty setup breaks and re-places the hut block, spilling it, and resets the level to 1).
     *
     * @return false when the caller should send a normal work order instead
     */
    static boolean creativeUpgrade(IBuildingView b) {
        if (serverInstant()) {
            if (b.isBuildingMaxLevel()) return false;
            PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.UPGRADE, b.getPosition(), b.getBuildingLevel() + 1, "", "", RotationMirror.NONE));
            return true;
        }
        String pack = b.getStructurePack(), path = HutCatalog.levelPath(b.getStructurePath(), 1);
        if (b.getBuildingLevel() > 0 || pack == null || path == null) return false;
        new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Pretty, "", pack, path, b.getPosition(), b.getRotationMirror()).sendToServer();
        return true;
    }

    /** Survival: a hut placed by the plan gets its build request once its building view exists. */
    static void expectBuilding(BlockPos at) {
        pendingBuilds.put(at, System.currentTimeMillis());
    }

    /** @return the first free y above the ground (leaves ignored) at a column, or null when not loaded */
    static Integer groundAt(int x, int z) {
        return WORLD.ground(x, z);
    }

    /** @return the colony's claimed chunks (client claim data) */
    static java.util.Set<Long> claimed() {
        return claimed;
    }

    /** Creative, server part: turns leftover placeholder and waypoint blocks in the building into what they stand for. */
    static boolean creativeClean(IBuildingView b) {
        if (!serverInstant() || b.getBuildingLevel() < 1) return false;
        PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.CLEAN, b.getPosition(), b.getBuildingLevel(), "", "", RotationMirror.NONE));
        return true;
    }

    /** Creative repair with CityBuilder on the server: the current level pasted at once. @return false otherwise */
    // A building being moved (creative, server part): placed like a new hut, then the server rebuilds it there.
    static IBuildingView moving;

    /** Move: the building's hut goes out as a ghost at its level; confirming sends MOVE. @return false without the server part */
    static boolean startMove(IBuildingView b) {
        if (!serverInstant() || !creative() || b.getBuildingLevel() < 1 || !(b.getBuildingType().getBuildingBlock() instanceof Block hut)) return false;
        startPlacing(hut);
        placingLevel = b.getBuildingLevel();
        moving = b;
        return true;
    }

    static boolean creativeRepair(IBuildingView b) {
        if (!serverInstant() || b.getBuildingLevel() < 1) return false;
        PacketDistributor.sendToServer(new InstantBuild.Request(InstantBuild.REPAIR, b.getPosition(), b.getBuildingLevel(), "", "", RotationMirror.NONE));
        return true;
    }

    /** @return true when the server runs CityBuilder's instant-build handler (singleplayer always does) */
    static boolean serverInstant() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && c.hasChannel(InstantBuild.Request.TYPE);
    }

    // ---- selection ----

    /** @return the colony building whose box contains {@code pos} (the smallest when boxes nest or overlap), or null */
    static IBuildingView buildingAt(BlockPos pos) {
        if (colony == null || pos == null) return null;
        java.util.List<IBuildingView> all = new java.util.ArrayList<>(colony.getClientBuildingManager().getBuildings().values());
        java.util.List<int[]> boxes = new java.util.ArrayList<>();
        for (IBuildingView b : all) boxes.add(box(b));
        int i = ColonyGeometry.smallestContaining(boxes, pos.getX(), pos.getY(), pos.getZ());
        return i < 0 ? null : all.get(i);
    }

    /** @return the building or decoration ({@link Deco}) whose box contains {@code pos}, the smallest one, or null */
    static Object pickAt(BlockPos pos) {
        if (colony == null || pos == null) return null;
        java.util.List<Object> all = new java.util.ArrayList<>();
        java.util.List<int[]> boxes = new java.util.ArrayList<>();
        for (IBuildingView b : colony.getClientBuildingManager().getBuildings().values()) {
            all.add(b);
            boxes.add(box(b));
        }
        for (Deco d : decos()) {
            int[] r = box(d);
            if (r == null) continue;
            all.add(d);
            boxes.add(r);
        }
        int i = ColonyGeometry.smallestContaining(boxes, pos.getX(), pos.getY(), pos.getZ());
        return i < 0 ? null : all.get(i);
    }

    /** @return the building's block box {minX, minY, minZ, maxX, maxY, maxZ} from its blueprint; its hut block while that loads */
    static int[] box(IBuildingView b) {
        BlockPos p = b.getPosition();
        // The stored path keeps the level it was placed at; the box is the current level's blueprint.
        String path = b.getStructurePath(), now = HutCatalog.levelPath(path, Math.max(1, b.getBuildingLevel()));
        int[] r = Blueprints.bounds(b.getStructurePack(), now != null ? now : path, b.getRotationMirror());
        if (r == null) return new int[]{p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()};
        return new int[]{p.getX() + r[0], p.getY() + r[1], p.getZ() + r[2], p.getX() + r[3], p.getY() + r[4], p.getZ() + r[5]};
    }

    static AABB aabb(IBuildingView b) {
        int[] r = box(b);
        return new AABB(r[0], r[1], r[2], r[3] + 1, r[4] + 1, r[5] + 1).inflate(0.02);
    }

    static void select(IBuildingView b) {
        selected = b;
        selectedDeco = null;
    }

    /** Selects a decoration (its blueprint starts loading, for a move). */
    static void selectDeco(Deco d) {
        selected = null;
        selectedDeco = d;
        if (d != null) Blueprints.deco(d.pack(), d.path());
    }

    /** Move a decoration: its ghost at its turn; confirming sends DECO_MOVE. @return false without the server part or while it loads */
    static boolean startMoveDeco(Deco d) {
        Blueprints.Hut h = serverInstant() && creative() ? Blueprints.deco(d.pack(), d.path()) : null;
        if (h == null) return false;
        startPlacingDeco(h);
        rotation = RotationMirror.values()[Math.floorMod(d.rot(), RotationMirror.values().length)];
        movingDeco = d;
        return true;
    }

    /** Creative DECO_DELETE or survival DECO_REMOVE_ORDER of a decoration through the server part. @return false without it */
    static boolean sendDeco(int mode, Deco d) {
        if (!serverInstant()) return false;
        PacketDistributor.sendToServer(new InstantBuild.Request(mode, d.pos(), 0, d.pack(), d.path(),
                RotationMirror.values()[Math.floorMod(d.rot(), RotationMirror.values().length)], PlanView.decoRequest()));
        return true;
    }

    static void requestBuild(IBuildingView b, BuildRequestMessage.Mode mode) {
        new BuildRequestMessage(b, mode, BlockPos.ZERO).sendToServer();
    }

    // The border ribbon: a faint pass that shows through terrain, then a bright depth-tested one on top.
    private static final RenderType BORDER_THROUGH = borderType("mccitybuilder_border_through", RenderStateShard.NO_DEPTH_TEST);
    private static final RenderType BORDER = borderType("mccitybuilder_border", RenderStateShard.LEQUAL_DEPTH_TEST);

    private static RenderType borderType(String name, RenderStateShard.DepthTestStateShard depth) {
        return RenderType.create(name, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                8192, false, true, RenderType.CompositeState.builder().setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY).setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(depth).setWriteMaskState(RenderStateShard.COLOR_WRITE).createCompositeState(false));
    }

    /**
     * The colony border as an amber ribbon lying on the terrain, 0.2 above the highest block beside each 2-block step
     * (shared endpoints keep it unbroken), about 2.5 screen pixels wide at every distance (at most 0.75 blocks).
     */
    private static void border(PoseStack pose, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        // World size of one screen pixel per block of distance.
        double perPx = 2 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2) / Math.max(1, mc.getWindow().getHeight());
        for (RenderType type : new RenderType[]{BORDER_THROUGH, BORDER}) {
            float a = type == BORDER ? 0.9f : 0.3f;
            VertexConsumer q = buffers.getBuffer(type);
            var m = pose.last();
            for (int[] e : border) {
                int len = Math.abs(e[2] - e[0]) + Math.abs(e[3] - e[1]), sx = Integer.signum(e[2] - e[0]), sz = Integer.signum(e[3] - e[1]);
                for (int i = 0; i < len; i += 2) {
                    int x0 = e[0] + sx * i, z0 = e[1] + sz * i, x1 = x0 + sx * 2, z1 = z0 + sz * 2;
                    float y0 = edgeHeight(mc, x0, z0), y1 = edgeHeight(mc, x1, z1);
                    // Half-width per endpoint, so neighbouring pieces share it.
                    float h0 = halfWidth(eye, x0, y0, z0, perPx), h1 = halfWidth(eye, x1, y1, z1, perPx);
                    float[] c0 = fogged(eye, x0, y0, z0, a), c1 = fogged(eye, x1, y1, z1, a);
                    q.addVertex(m, x0 + sz * h0, y0, z0 - sx * h0).setColor(c0[0], c0[1], c0[2], c0[3]);
                    q.addVertex(m, x1 + sz * h1, y1, z1 - sx * h1).setColor(c1[0], c1[1], c1[2], c1[3]);
                    q.addVertex(m, x1 - sz * h1, y1, z1 + sx * h1).setColor(c1[0], c1[1], c1[2], c1[3]);
                    q.addVertex(m, x0 - sz * h0, y0, z0 + sx * h0).setColor(c0[0], c0[1], c0[2], c0[3]);
                }
            }
            buffers.endBatch(type);
        }
    }

    /** Terrain fog as last set for the planner: {start, end, r, g, b, a}, or null. */
    static float[] fog;

    /**
     * @return the border's amber at a point, fogged like terrain (vanilla's cylindrical distance, max of horizontal and
     * vertical, and its linear mix toward the fog colour)
     */
    private static float[] fogged(Vec3 eye, double x, double y, double z, float a) {
        float r = 1f, g = 0.7f, b = 0.15f;
        if (fog != null && fog[1] > fog[0]) {
            double d = Math.max(Math.hypot(x - eye.x, z - eye.z), Math.abs(y - eye.y));
            float f = (float) CameraMath.clamp((d - fog[0]) / (fog[1] - fog[0]), 0, 1) * fog[5];
            r += (fog[2] - r) * f;
            g += (fog[3] - g) * f;
            b += (fog[4] - b) * f;
        }
        return new float[]{r, g, b, a};
    }

    /** @return half the border width at a point: ~2.5 screen pixels at its distance, at most 0.375 blocks */
    private static float halfWidth(Vec3 eye, double x, double y, double z, double perPx) {
        return (float) Math.min(0.375, 1.25 * perPx * eye.distanceTo(new Vec3(x, y, z)));
    }

    /** @return the surface at a block corner: the highest of the four columns touching it, plus 0.2 */
    private static float edgeHeight(Minecraft mc, int x, int z) {
        int h = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 0; dz++) h = Math.max(h, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz));
        return h + 0.2f;
    }

    /** The colony border, drawn at the end of the level render (see PlannerClient). */
    static void renderBorder(PoseStack pose, Camera cam) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 c = cam.getPosition();
        pose.pushPose();
        try {
            pose.translate(-c.x, -c.y, -c.z);
            border(pose, mc.renderBuffers().bufferSource());
        } finally {
            pose.popPose();
        }
    }

    /** Outlines in the world: the selected hut and an invalid placement anchor (red). */
    static void renderWorld(PoseStack pose, Camera cam) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Vec3 c = cam.getPosition();
        pose.pushPose();
        try {
            pose.translate(-c.x, -c.y, -c.z);
            // Lines only after the border: switching buffers ends the previous batch.
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            // The whole building's box: subtle on hover, full white when selected.
            if (hovered != null && hovered != selected) LevelRenderer.renderLineBox(pose, lines, aabb(hovered), 0.8f, 0.8f, 0.8f, 0.8f);
            if (selected != null) LevelRenderer.renderLineBox(pose, lines, aabb(selected), 1, 1, 1, 1);
            int[] hd = hoveredDeco != null && !hoveredDeco.equals(selectedDeco) ? box(hoveredDeco) : null, sd = selectedDeco != null ? box(selectedDeco) : null;
            if (hd != null) LevelRenderer.renderLineBox(pose, lines, new AABB(hd[0], hd[1], hd[2], hd[3] + 1, hd[4] + 1, hd[5] + 1).inflate(0.02), 0.8f, 0.8f, 0.8f, 0.8f);
            if (sd != null) LevelRenderer.renderLineBox(pose, lines, new AABB(sd[0], sd[1], sd[2], sd[3] + 1, sd[4] + 1, sd[5] + 1).inflate(0.02), 1, 1, 1, 1);
            OverlayView.renderWorld(pose, lines);
            PlanView.renderWorld(pose, lines);
            LineView.renderWorld(pose, lines);
            BlockPos[] f = isPlacing() ? footprint() : null;
            if (f != null) {
                boolean ok = invalidReason == null;
                AABB box = new AABB(f[0].getX(), f[0].getY(), f[0].getZ(), f[1].getX() + 1, f[1].getY() + 1, f[1].getZ() + 1).inflate(0.03);
                LevelRenderer.renderLineBox(pose, lines, box, ok ? 0.3f : 1f, ok ? 1f : 0.2f, ok ? 0.3f : 0.2f, 1);
                buffers.endBatch(RenderType.lines());
                // Invalid: a translucent red box over the whole footprint.
                if (!ok) {
                    VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
                    LevelRenderer.addChainedFilledBoxVertices(pose, fill, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 1f, 0.1f, 0.1f, 0.25f);
                    buffers.endBatch(RenderType.debugFilledBox());
                }
            }
        } finally {
            pose.popPose();
        }
        buffers.endBatch(RenderType.lines());
    }
}
