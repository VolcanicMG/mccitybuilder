package com.volcanicmg.mccitybuilder.server;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.util.ColonyUtils;
import com.minecolonies.api.util.Utils;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.HutCatalog;
import com.volcanicmg.mccitybuilder.planner.PasteRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.Container;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Instant creative builds, the one server-side part of CityBuilder: place a hut at a chosen level, upgrade a hut to
 * the next level, or repair it at its level. The server pastes the blueprint with Structurize's fancy placement
 * (placeholders resolved, waypoints registered) but leaves the hut block, blocks that already match and blocks holding
 * items alone, then hands the blueprint's schematic data to the hut's block entity the way a finished builder work
 * order does ({@code AbstractWorkOrder.onCompleted}), which sets the building's corners and level.
 * <p>
 * The payload is optional: a server without CityBuilder just doesn't offer the channel and the client falls back.
 * Every request is re-checked here and again when its blueprint has loaded: creative, MANAGE_HUTS (and PLACE_HUTS for
 * placing) in the colony at the position, the level step, every footprint chunk owned by the colony, a NeoForge break
 * event per replaced block (claims and protection mods can refuse), one paste per player and position, a rate limit.
 */
public final class InstantBuild {
    /** PLACE a hut, UPGRADE / REPAIR / CLEAN (placeholders) a building, DECO = place a decoration (no hut anchor). */
    public static final int PLACE = 0, UPGRADE = 1, REPAIR = 2, DECO = 3, CLEAN = 4, DECO_ORDER = 5;
    static final long MIN_INTERVAL_MS = 1000;
    static final Map<UUID, Long> lastRequest = new HashMap<>();

    /**
     * @param mode  {@link #PLACE}, {@link #UPGRADE} or {@link #REPAIR}
     * @param level the level to paste (place: chosen; upgrade: current + 1; repair: current)
     * @param pack  place only: style pack; @param path place only: blueprint path; @param rot place only: rotation
     * @param id    above 0: the client wants a {@link BatchResult} with this id once the request finished (accepted)
     *              or was refused (reason "busy" = try again shortly)
     */
    public record Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot, int id) implements CustomPacketPayload {
        public Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot) {
            this(mode, pos, level, pack, path, rot, 0);
        }

        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "instant_build"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, r) -> {
            buf.writeByte(r.mode);
            buf.writeBlockPos(r.pos);
            buf.writeVarInt(r.level);
            buf.writeUtf(r.pack, 256);
            buf.writeUtf(r.path, 512);
            buf.writeByte(r.rot.ordinal());
            buf.writeVarInt(r.id);
        }, buf -> new Request(buf.readByte(), buf.readBlockPos(), buf.readVarInt(), buf.readUtf(256), buf.readUtf(512),
                RotationMirror.values()[Math.floorMod(buf.readByte(), RotationMirror.values().length)], buf.readVarInt()));

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    private InstantBuild() {
    }

    /** Registers the payload as optional, so clients and servers without the mod still connect. */
    public static void register(RegisterPayloadHandlersEvent e) {
        // Protocol 3: piece batches and single requests (with an id) get a batch_result answer; mismatched builds refuse
        // the channel cleanly.
        e.registrar("3").optional().playToServer(Request.TYPE, Request.CODEC, InstantBuild::handle)
                .playToServer(PieceBatch.TYPE, PieceBatch.CODEC, InstantBuild::handleBatch)
                .playToClient(BatchResult.TYPE, BatchResult.CODEC, (r, ctx) -> BatchResults.accept(r));
    }

    /** Prunes per-player state on logout. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            lastRequest.remove(e.getEntity().getUUID());
            busyPlayers.remove(e.getEntity().getUUID());
            batches.remove(e.getEntity().getUUID());
            batchStats.remove(e.getEntity().getUUID());
            refused.remove(e.getEntity().getUUID());
        });
    }

    // One paste per player and per position at a time (start time; stale after BUSY_MS in case a paste never ends).
    private static final long BUSY_MS = 300_000;
    private static final Map<UUID, Long> busyPlayers = new HashMap<>();
    private static final Map<String, Long> busyPositions = new HashMap<>();

    // Where Structurize stores packs synced from clients (BlueprintPlacementHandling: ./blueprints/clients/<uuid>/...).
    private static final java.nio.file.Path CLIENT_PACKS = java.nio.file.Path.of("blueprints", "clients").toAbsolutePath().normalize();
    // Footprint chunks of running pastes, so overlapping pastes from different anchors can't interleave.
    private static final Map<String, Long> busyChunks = new HashMap<>();

    private static <K> boolean busy(Map<K, Long> m, K k, long now) {
        Long t = m.get(k);
        return t != null && now - t < BUSY_MS;
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.asLong();
    }

    // Per player: single requests already answered as refused (their release must not answer them again as accepted).
    // Clients number their own ids, so they are only unique per player; the oldest go past REFUSED_KEPT.
    private static final Map<UUID, java.util.LinkedHashSet<Integer>> refused = new HashMap<>();
    private static final int REFUSED_KEPT = 64;

    /**
     * Clears the marks this request set (only if they are still its own, by time stamp); a single request (id above
     * 0) that wasn't refused finished, so it is answered as accepted, however late; then the batch goes on.
     */
    private static void release(ServerPlayer p, ServerLevel level, Request r, long stamp) {
        busyPlayers.remove(p.getUUID(), stamp);
        busyPositions.remove(key(level, r.pos), stamp);
        java.util.Set<Integer> mine = refused.get(p.getUUID());
        if (r.id > 0 && (mine == null || !mine.remove(r.id))) answer(p, r.id, true, "");
        continueBatch(p);
    }

    static void reject(ServerPlayer p, String why) {
        CityBuilder.LOGGER.info("CityBuilder: rejected instant build from {}: {}", p.getGameProfile().getName(), why);
    }

    /** Logs a refusal and answers a single request ("busy" when it may work a moment later, else "refused"). */
    static void reject(ServerPlayer p, Request r, String why) {
        reject(p, why);
        if (r.id <= 0) return;
        // Early refusals (before any busy mark) are never released: the oldest drop out once the player has many.
        java.util.LinkedHashSet<Integer> mine = refused.computeIfAbsent(p.getUUID(), k -> new java.util.LinkedHashSet<>());
        mine.add(r.id);
        while (mine.size() > REFUSED_KEPT) mine.remove(mine.iterator().next());
        answer(p, r.id, false, PasteRules.refusal(why));
    }

    // ---- road / wall pieces: a batch of real style-pack decorations, processed one after another ----

    /** Pieces per batch, and City Builder's open decoration work orders per colony. */
    public static final int MAX_BATCH = 16, MAX_OPEN_ORDERS = 64, MAX_QUEUED = 96;

    /** One road / wall piece: its blueprint path in the pack, anchor position and RotationMirror ordinal. */
    public record Piece(String path, BlockPos pos, int rot) {
    }

    /**
     * Up to {@link #MAX_BATCH} road or wall pieces of one style pack, placed (creative) or ordered (survival) one at a
     * time through the same checks as single decorations (DECO / DECO_ORDER), in the order given.
     */
    public record PieceBatch(int id, String pack, boolean survival, List<Piece> pieces) implements CustomPacketPayload {
        public static final Type<PieceBatch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "piece_batch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PieceBatch> CODEC = StreamCodec.of((buf, b) -> {
            buf.writeVarInt(b.id);
            buf.writeUtf(b.pack, 256);
            buf.writeBoolean(b.survival);
            buf.writeVarInt(b.pieces.size());
            for (Piece q : b.pieces) {
                buf.writeUtf(q.path, 512);
                buf.writeBlockPos(q.pos);
                buf.writeByte(q.rot);
            }
        }, buf -> {
            int id = buf.readVarInt();
            String pack = buf.readUtf(256);
            boolean survival = buf.readBoolean();
            int n = Math.min(buf.readVarInt(), MAX_BATCH);
            List<Piece> ps = new ArrayList<>();
            for (int i = 0; i < n; i++) ps.add(new Piece(buf.readUtf(512), buf.readBlockPos(), buf.readByte()));
            return new PieceBatch(id, pack, survival, ps);
        });

        @Override
        public Type<PieceBatch> type() {
            return TYPE;
        }
    }

    /**
     * The server's answer to a {@link PieceBatch}: accepted (queued) or not, with a lang key for why. The client keeps
     * a planned segment until all its batches are accepted.
     */
    public record BatchResult(int id, boolean accepted, String reason) implements CustomPacketPayload {
        public static final Type<BatchResult> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "batch_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BatchResult> CODEC = StreamCodec.of((buf, r) -> {
            buf.writeVarInt(r.id);
            buf.writeBoolean(r.accepted);
            buf.writeUtf(r.reason, 128);
        }, buf -> new BatchResult(buf.readVarInt(), buf.readBoolean(), buf.readUtf(128)));

        @Override
        public Type<BatchResult> type() {
            return TYPE;
        }
    }

    private static final Map<UUID, java.util.ArrayDeque<Request>> batches = new HashMap<>();
    // Per player while batches run: {done, total, survival 0/1}.
    private static final Map<UUID, int[]> batchStats = new HashMap<>();

    /**
     * @return the colony's open road / wall decoration work orders (any decoration order whose blueprint is a road /
     * wall piece): rebuilt from the colony's orders each time, so it survives restarts
     */
    static int openPieceOrders(IColony colony) {
        int n = 0;
        for (var wo : colony.getWorkManager().getWorkOrders().values())
            if (wo instanceof com.minecolonies.core.colony.workorders.WorkOrderDecoration && wo.getStructurePath() != null
                    && com.volcanicmg.mccitybuilder.planner.PieceSets.tool(wo.getStructurePath()) != null) n++;
        return n;
    }

    private static void answer(ServerPlayer p, int id, boolean ok, String reason) {
        if (!p.connection.hasChannel(BatchResult.TYPE)) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new BatchResult(id, ok, reason));
    }

    private static void handleBatch(PieceBatch b, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        resumeIfStale(p);
        java.util.ArrayDeque<Request> running = batches.get(p.getUUID());
        int[] st = batchStats.get(p.getUUID());
        String why = b.pieces.isEmpty() ? "empty" : last != null && now - last < MIN_INTERVAL_MS ? "busy" : !b.survival && !p.isCreative() ? "creative"
                : running != null && running.size() + b.pieces.size() > MAX_QUEUED ? "busy"
                // One kind at a time, so the summary at the end is about one thing.
                : st != null && (st[2] == 1) != b.survival ? "busy" : null;
        if (why == null)
            for (Piece q : b.pieces) if (com.volcanicmg.mccitybuilder.planner.PieceSets.tool(q.path) == null) why = "not_piece";
        ServerLevel level = p.serverLevel();
        IColony colony = null;
        if (why == null) {
            BlockPos first = b.pieces.get(0).pos;
            colony = level.isLoaded(first) ? IColonyManager.getInstance().getColonyByPosFromWorld(level, first) : null;
            if (colony == null || !allowed(colony, p, b.survival ? DECO_ORDER : DECO)) why = "permission";
        }
        // Survival: open road / wall orders plus what is already queued must stay under the colony's cap.
        if (why == null && b.survival && openPieceOrders(colony) + (running == null ? 0 : running.size()) + b.pieces.size() > MAX_OPEN_ORDERS) why = "too_many";
        if (why != null) {
            // Logged only: a single request still waiting keeps its own answer.
            CityBuilder.LOGGER.info("CityBuilder: rejected piece batch {} from {}: {}", b.id, p.getGameProfile().getName(), why);
            answer(p, b.id, false, why);
            return;
        }
        lastRequest.put(p.getUUID(), now);
        java.util.ArrayDeque<Request> q = new java.util.ArrayDeque<>();
        for (Piece pc : b.pieces)
            q.add(new Request(b.survival ? DECO_ORDER : DECO, pc.pos, 0, b.pack, pc.path, RotationMirror.values()[Math.floorMod(pc.rot, RotationMirror.values().length)]));
        // A batch arriving while one runs joins its queue (one summary at the end).
        if (running != null) {
            running.addAll(q);
            batchStats.get(p.getUUID())[1] += q.size();
        } else {
            batches.put(p.getUUID(), q);
            batchStats.put(p.getUUID(), new int[]{0, q.size(), b.survival ? 1 : 0});
        }
        answer(p, b.id, true, "");
        continueBatch(p);
    }

    /** A batch whose current piece never released (a paste that didn't finish): carry on with the rest. */
    private static void resumeIfStale(ServerPlayer p) {
        if (batches.containsKey(p.getUUID()) && !busy(busyPlayers, p.getUUID(), System.currentTimeMillis())) {
            busyPlayers.remove(p.getUUID());
            continueBatch(p);
        }
    }

    /** Runs the player's next batch piece once nothing of theirs is running; at the end, one summary message. */
    private static void continueBatch(ServerPlayer p) {
        java.util.ArrayDeque<Request> q = batches.get(p.getUUID());
        if (q == null || busy(busyPlayers, p.getUUID(), System.currentTimeMillis())) return;
        while (!q.isEmpty()) {
            if (submit(p, q.poll(), true)) return;
        }
        batches.remove(p.getUUID());
        int[] st = batchStats.remove(p.getUUID());
        if (st != null)
            p.displayClientMessage(Component.translatable(st[2] == 1 ? "mccitybuilder.pieces.ordered" : "mccitybuilder.pieces.placed", st[0], Math.max(0, st[1] - st[0])), false);
    }

    /** @return the colony's permission check for this request (PLACE needs PLACE_HUTS as well, like MineColonies) */
    static boolean allowed(IColony colony, ServerPlayer p, int mode) {
        return colony.getPermissions().hasPermission(p, Action.MANAGE_HUTS) && (mode != PLACE && mode != DECO || colony.getPermissions().hasPermission(p, Action.PLACE_HUTS));
    }

    private static void handle(Request r, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer p) submit(p, r, false);
    }

    /**
     * Checks a request and starts its blueprint load. @param batch true for a piece of a batch (no rate limit of its
     * own) @return true when the load started (the batch then continues when it is released)
     */
    private static boolean submit(ServerPlayer p, Request r, boolean batch) {
        if (!batch) resumeIfStale(p);
        if (!batch && batches.containsKey(p.getUUID())) {
            reject(p, r, "a piece batch is running");
            return false;
        }
        return submitChecked(p, r, batch);
    }

    private static boolean submitChecked(ServerPlayer p, Request r, boolean batch) {
        // DECO_ORDER (a decoration builder work order, as MineColonies' decoration window makes) is for survival too.
        if (!p.isCreative() && r.mode != DECO_ORDER) {
            reject(p, r, "not in creative");
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        if (!batch && last != null && now - last < MIN_INTERVAL_MS) {
            reject(p, r, "rate limit");
            return false;
        }
        if (!batch) lastRequest.put(p.getUUID(), now);
        ServerLevel level = p.serverLevel();
        if (r.mode < PLACE || r.mode > DECO_ORDER || !level.isLoaded(r.pos)) {
            reject(p, r, "bad mode or unloaded position");
            return false;
        }
        if (busy(busyPlayers, p.getUUID(), now) || busy(busyPositions, key(level, r.pos), now)) {
            reject(p, r, "a paste is still running");
            return false;
        }
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, r.pos);
        if (colony == null || !allowed(colony, p, r.mode)) {
            reject(p, r, "no colony or missing permission at " + r.pos.toShortString());
            return false;
        }
        String pack, path;
        RotationMirror rot;
        if (r.mode == PLACE || r.mode == DECO || r.mode == DECO_ORDER) {
            // Checked and used in one normalised form (Structurize turns \ into / itself).
            String reqPath = r.path.replace('\\', '/');
            String bad = PasteRules.badPath(reqPath, r.mode != PLACE ? 0 : r.level);
            StructurePackMeta meta = StructurePacks.getStructurePack(r.pack);
            if (bad == null && (meta == null || !StructurePacks.hasPack(r.pack))) bad = "unknown pack";
            // Client-synced packs live under blueprints/clients/<uuid>/: never trusted.
            if (bad == null && meta.getPath().toAbsolutePath().normalize().startsWith(CLIENT_PACKS)) bad = "client pack";
            if (bad == null && !meta.getPath().resolve(reqPath).normalize().startsWith(meta.getPath().normalize())) bad = "path leaves the pack";
            if (bad == null && r.mode == PLACE && (r.level < 1 || colony.getServerBuildingManager().getBuilding(r.pos) != null)) bad = "level < 1 or a building is already there";
            if (bad != null) {
                reject(p, r, bad + ": " + r.pack + " " + r.path);
                return false;
            }
            pack = r.pack;
            path = reqPath;
            rot = r.rot;
        } else {
            IBuilding b = colony.getServerBuildingManager().getBuilding(r.pos);
            if (b == null) {
                reject(p, r, "no building at " + r.pos.toShortString());
                return false;
            }
            String bp = b.getBlueprintPath() == null ? null : b.getBlueprintPath().endsWith(".blueprint") ? b.getBlueprintPath() : b.getBlueprintPath() + ".blueprint";
            path = HutCatalog.levelPath(bp, r.level);
            pack = b.getStructurePack();
            if (!levelOk(r, b) || path == null || pack == null || !StructurePacks.hasPack(pack)) {
                reject(p, r, "bad level step " + b.getBuildingLevel() + " -> " + r.level + " or no blueprint path");
                return false;
            }
            rot = b.getTileEntity() != null ? b.getTileEntity().getRotationMirror() : b.getRotationMirror();
        }
        busyPlayers.put(p.getUUID(), now);
        busyPositions.put(key(level, r.pos), now);
        int colonyId = colony.getID();
        StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).whenComplete((bp, t) -> level.getServer().execute(() -> {
            boolean started = false;
            try {
                if (bp == null) reject(p, r, "blueprint not found " + pack + " " + path);
                else started = start(p, level, colonyId, r, pack, path, rot, bp, now);
            } catch (RuntimeException ex) {
                CityBuilder.LOGGER.error("CityBuilder: instant build failed", ex);
                reject(p, r, "failed: " + ex);
            } finally {
                if (!started) release(p, level, r, now);
            }
        }));
        return true;
    }

    /** Counts a placed / ordered piece for the running batch's summary. */
    private static void done(ServerPlayer p) {
        int[] st = batchStats.get(p.getUUID());
        if (st != null) st[0]++;
    }

    /** UPGRADE: current + 1 within the max; REPAIR: the current level, built. */
    private static boolean levelOk(Request r, IBuilding b) {
        int cur = b.getBuildingLevel();
        return r.mode == UPGRADE ? r.level == cur + 1 && r.level <= b.getMaxBuildingLevel() : r.level == cur && cur >= 1;
    }

    /**
     * On the server thread with the blueprint loaded: re-checks everything (the player, permission, the building and
     * its level may have changed while it loaded), checks the footprint, places the hut for PLACE, then pastes.
     *
     * @return true when the paste was queued (its completion releases the busy marks)
     */
    private static boolean start(ServerPlayer p, ServerLevel level, int colonyId, Request r, String pack, String path, RotationMirror rot, Blueprint bp, long stamp) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        if (colony == null || !p.isCreative() && r.mode != DECO_ORDER || p.isRemoved() || !allowed(colony, p, r.mode)) {
            reject(p, r, "player or colony changed");
            return false;
        }
        IBuilding existing = colony.getServerBuildingManager().getBuilding(r.pos);
        if (r.mode == PLACE ? existing != null : r.mode != DECO && r.mode != DECO_ORDER && (existing == null || !levelOk(r, existing))) {
            reject(p, r, "building changed while loading");
            return false;
        }
        bp.setRotationMirror(rot, level);
        BlockPos offset = bp.getPrimaryBlockOffset(), origin = r.pos.subtract(offset);
        BlockPos far = origin.offset(bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1);
        // Every chunk under the footprint must be loaded and owned by this colony (MineColonies' own rule).
        for (int cx = origin.getX() >> 4; cx <= far.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= far.getZ() >> 4; cz++)
                if (!level.hasChunk(cx, cz) || ColonyUtils.getOwningColony(level.getChunk(cx, cz)) != colonyId) {
                    reject(p, r, "footprint leaves the colony or isn't loaded at chunk " + cx + "," + cz);
                    return false;
                }
        List<String> chunks = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int cx = origin.getX() >> 4; cx <= far.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= far.getZ() >> 4; cz++) chunks.add(level.dimension().location() + "|" + cx + "," + cz);
        for (String c : chunks)
            if (busy(busyChunks, c, now)) {
                reject(p, r, "another paste is running over chunk " + c);
                return false;
            }
        BlockState anchor = bp.getBlockState(offset);
        if (r.mode == PLACE) {
            if (!(anchor.getBlock() instanceof AbstractBlockHut<?> hut) || !level.getBlockState(r.pos).canBeReplaced()
                    || !colony.getServerBuildingManager().canPlaceAt(anchor.getBlock(), r.pos, p)
                    || r.level > hut.getBuildingEntry().produceBuilding(r.pos, colony).getMaxBuildingLevel()) {
                reject(p, r, "cannot place " + path + " at " + r.pos.toShortString());
                return false;
            }
        }
        // Decorations go through here only without a hut anchor (huts use PLACE, which registers the building).
        if ((r.mode == DECO || r.mode == DECO_ORDER) && anchor.getBlock() instanceof AbstractBlockHut<?>) {
            reject(p, r, "decoration request with a hut anchor: " + path);
            return false;
        }
        if (r.mode == CLEAN) return clean(p, r, level, colony, bp, origin, offset);
        if (r.mode == DECO_ORDER) {
            // What MineColonies' DecorationBuildRequestMessage does (MANAGE_HUTS, one order per location), after our
            // footprint checks: the builder then gathers the materials and builds it.
            for (var wo : colony.getWorkManager().getWorkOrders().values())
                if (wo.getLocation().equals(r.pos)) {
                    reject(p, r, "a work order is already at " + r.pos.toShortString());
                    return false;
                }
            // Road / wall pieces count against the colony's cap, checked again here (queued batches may overshoot).
            if (com.volcanicmg.mccitybuilder.planner.PieceSets.tool(path) != null && openPieceOrders(colony) >= MAX_OPEN_ORDERS) {
                reject(p, r, "too many open road / wall work orders in colony " + colony.getID());
                return false;
            }
            String[] split = path.split("/");
            var order = com.minecolonies.core.colony.workorders.WorkOrderDecoration.create(com.minecolonies.api.colony.workorders.WorkOrderType.BUILD, pack, path,
                    org.apache.commons.lang3.text.WordUtils.capitalizeFully(split[split.length - 1].replace(".blueprint", "")), r.pos, rot, 0);
            order.setBlueprint(bp, level);
            colony.getWorkManager().addWorkOrder(order, false);
            done(p);
            return false;
        }
        // Entities holding items (item frames, armour stands, chest carts...): their blocks and supports stay.
        java.util.Set<BlockPos> entityKept = entityProtected(level, origin, far);
        // Decide every position first; nothing in the world changes until all of it is allowed.
        List<BlockPos> dataPos = new ArrayList<>();
        List<CompoundTag> data = new ArrayList<>();
        CompoundTag[][][] tes = bp.getTileEntities();
        short[][][] st = bp.getStructure();
        BlockState[] palette = bp.getPalette();
        List<BlockPos> skip = new ArrayList<>();
        java.util.Set<Long> placeChecked = new java.util.HashSet<>();
        int kept = 0;
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockPos local = new BlockPos(x, y, z), world = origin.offset(local);
                    if (tes[y][z][x] != null && tes[y][z][x].contains("blueprintDataProvider")) {
                        dataPos.add(local);
                        data.add(tes[y][z][x].copy());
                    }
                    BlockState want = palette[st[y][z][x]], have = level.getBlockState(world);
                    PasteRules.Action a = PasteRules.decide(r.mode != DECO && local.equals(offset), have.equals(want), isProtected(level, world, have));
                    if (a == PasteRules.Action.PLACE && entityKept.contains(world)) a = PasteRules.Action.KEEP;
                    // Command, structure and jigsaw blocks are never pasted from a blueprint.
                    if (a == PasteRules.Action.PLACE && want.getBlock() instanceof GameMasterBlock) a = PasteRules.Action.SKIP;
                    // Block entity data only ops may set (signs, spawners...): the block is placed without it.
                    if (a == PasteRules.Action.PLACE && tes[y][z][x] != null && !p.canUseGameMasterBlocks() && opOnly(world, want)) tes[y][z][x] = null;
                    if (a == PasteRules.Action.PLACE && !have.isAir() && NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, world, have, p)).isCanceled()) {
                        reject(p, r, "a claim or protection refused breaking " + world.toShortString());
                        return false;
                    }
                    // Placing into air: one place event per chunk (claims are per chunk or area; not one per block).
                    if (a == PasteRules.Action.PLACE && have.isAir() && !want.isAir() && placeChecked.add(world.getX() >> 4 | (long) (world.getZ() >> 4) << 32)
                            && NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, world), have, p)).isCanceled()) {
                        reject(p, r, "a claim or protection refused placing at " + world.toShortString());
                        return false;
                    }
                    if (a == PasteRules.Action.PLACE) continue;
                    if (a == PasteRules.Action.KEEP) kept++;
                    skip.add(local);
                    tes[y][z][x] = null;
                }
        if (r.mode == PLACE) {
            BlockState previous = level.getBlockState(r.pos);
            level.setBlockAndUpdate(r.pos, anchor);
            ((AbstractBlockHut<?>) anchor.getBlock()).onBlockPlacedByBuildTool(level, r.pos, anchor, p, null, rot, pack, path);
            if (colony.getServerBuildingManager().getBuilding(r.pos) == null) {
                // No stray hut: put back what was there.
                level.setBlockAndUpdate(r.pos, previous);
                reject(p, r, "no building after placing at " + r.pos.toShortString());
                return false;
            }
        }
        for (String c : chunks) busyChunks.put(c, now);
        // Structurize's substitution block = "leave the world block" under fancy placement.
        BlockState leave = ModBlocks.blockSubstitution.get().defaultBlockState();
        for (BlockPos local : skip) bp.addBlockState(local, leave);
        bp.setCachePrimaryOffset(offset);
        if (r.mode == UPGRADE || r.mode == REPAIR) {
            // The paste does the job: any queued work order for this building is cancelled the way MineColonies does.
            for (var wo : new ArrayList<>(colony.getWorkManager().getWorkOrders().values()))
                if (wo.getLocation().equals(r.pos)) colony.getWorkManager().removeWorkOrder(wo.getID());
        }
        Handler handler = new Handler(level, r.pos, bp, rot, () -> {
            try {
                if (r.mode != DECO) finish(level, colonyId, r, bp, origin, offset, dataPos, data);
            } finally {
                release(p, level, r, stamp);
                chunks.forEach(busyChunks::remove);
            }
        });
        Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(handler), p));
        done(p);
        if (kept > 0) p.displayClientMessage(Component.translatable("mccitybuilder.kept_storage", kept), false);
        return true;
    }

    /**
     * After the paste, what a builder's finished work order does: the blueprint's schematic data goes to the hut's
     * block entity ({@code readSchematicDataFromNBT} → {@code upgradeBuildingLevelToSchematicData}: corners, level,
     * {@code onUpgradeComplete}), then every block in the footprint is registered with the building (racks, beds...).
     */
    private static void finish(ServerLevel level, int colonyId, Request r, Blueprint bp, BlockPos origin, BlockPos offset, List<BlockPos> dataPos, List<CompoundTag> data) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        IBuilding b = colony == null ? null : colony.getServerBuildingManager().getBuilding(r.pos);
        if (b == null) return;
        for (int i = 0; i < dataPos.size(); i++) {
            BlockPos tePos = origin.offset(dataPos.get(i));
            if (!(level.getBlockEntity(tePos) instanceof IBlueprintDataProviderBE be)) continue;
            CompoundTag tag = data.get(i).getCompound("blueprintDataProvider");
            tag.putString("pack", bp.getPackName());
            if (dataPos.get(i).equals(offset))
                tag.putString("path", StructurePacks.getStructurePack(bp.getPackName()).getSubPath(Utils.resolvePath(bp.getFilePath(), tag.getString("schematicName"))) + ".blueprint");
            be.readSchematicDataFromNBT(data.get(i));
            level.getChunkSource().blockChanged(tePos);
            ((BlockEntity) be).setChanged();
        }
        // No schematic data on the hut (custom packs): the builder's own fallback.
        if (r.mode != REPAIR && b.getBuildingLevel() < r.level) {
            b.onUpgradeComplete(bp, r.level);
            b.setBuildingLevel(r.level);
        }
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockPos w = origin.offset(x, y, z);
                    BlockState s = level.getBlockState(w);
                    if (!s.isAir()) b.registerBlockPosition(s, w, level);
                }
        b.markDirty();
    }

    /**
     * @return true for blocks the paste never replaces: storage (a Container block entity or an item handler on any
     * side, empty or not) and huts. Other block entities (beds, signs, banners...) are replaced like any block.
     */
    static boolean isProtected(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof AbstractBlockHut<?>) return true;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        if (be instanceof Container) return true;
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, be, null) != null) return true;
        for (Direction d : Direction.values())
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, be, d) != null) return true;
        return false;
    }

    /**
     * Positions around entities that hold items inside the footprint: the blocks they occupy, the block under them and,
     * for hanging entities (item frames), the block they hang on. Structurize's placer never removes entities itself;
     * this keeps them from losing their support or being built into.
     */
    static java.util.Set<BlockPos> entityProtected(ServerLevel level, BlockPos origin, BlockPos far) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(), far.getX() + 1, far.getY() + 1, far.getZ() + 1);
        for (Entity e : level.getEntities((Entity) null, box, InstantBuild::holdsItems)) {
            BlockPos.betweenClosedStream(e.getBoundingBox().inflate(0.01)).forEach(bp -> out.add(bp.immutable()));
            out.add(e.blockPosition().below());
            if (e instanceof net.minecraft.world.entity.decoration.HangingEntity h) out.add(h.getPos().relative(h.getDirection().getOpposite()));
        }
        return out;
    }

    /** @return true for entities with items: a filled item frame, an armour stand with gear, or any entity inventory */
    private static boolean holdsItems(Entity e) {
        if (e instanceof net.minecraft.world.entity.decoration.ItemFrame f) return !f.getItem().isEmpty();
        if (e instanceof net.minecraft.world.entity.decoration.ArmorStand a) {
            for (var st : a.getAllSlots()) if (!st.isEmpty()) return true;
            return false;
        }
        if (e instanceof Container c) return !c.isEmpty();
        var h = e.getCapability(Capabilities.ItemHandler.ENTITY, null);
        if (h != null) for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty()) return true;
        return false;
    }

    /**
     * CLEAN: Structurize placeholder blocks (substitution, solid / fluid / tag substitution) and MineColonies waypoint
     * blocks left in the building's footprint become what the blueprint has there, or the fill / fluid / air the
     * fancy placement would have used; waypoints are registered with the colony like a fancy paste does. Nothing else
     * is touched (storage and huts are never placeholders). @return false (nothing is queued)
     */
    private static boolean clean(ServerPlayer p, Request r, ServerLevel level, IColony colony, Blueprint bp, BlockPos origin, BlockPos offset) {
        short[][][] st = bp.getStructure();
        BlockState[] palette = bp.getPalette();
        java.util.Map<BlockPos, BlockState> changes = new java.util.LinkedHashMap<>();
        java.util.List<BlockPos> waypoints = new ArrayList<>();
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockPos world = origin.offset(x, y, z);
                    BlockState have = level.getBlockState(world), want = palette[st[y][z][x]];
                    if (!isPlaceholder(have)) continue;
                    if (have.getBlock() == com.minecolonies.api.blocks.ModBlocks.blockWayPoint) waypoints.add(world);
                    BlockState to = isPlaceholder(want) ? fillFor(level, world, want) : want;
                    if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, world, have, p)).isCanceled()) {
                        reject(p, r, "a claim or protection refused breaking " + world.toShortString());
                        return false;
                    }
                    changes.put(world, to);
                }
        changes.forEach((pos, to) -> level.setBlock(pos, to, 3));
        for (BlockPos w : waypoints) colony.addWayPoint(w, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        p.displayClientMessage(Component.translatable("mccitybuilder.cleaned", changes.size()), false);
        return false;
    }

    private static boolean isPlaceholder(BlockState s) {
        var b = s.getBlock();
        return b == ModBlocks.blockSubstitution.get() || b == ModBlocks.blockSolidSubstitution.get() || b == ModBlocks.blockFluidSubstitution.get()
                || b == ModBlocks.blockTagSubstitution.get() || b == com.minecolonies.api.blocks.ModBlocks.blockWayPoint;
    }

    /** @return what fancy placement puts for a placeholder in the blueprint: ground fill, the dimension's fluid, or air */
    private static BlockState fillFor(ServerLevel level, BlockPos pos, BlockState placeholder) {
        if (placeholder.getBlock() == ModBlocks.blockSolidSubstitution.get()) return com.ldtteam.structurize.util.BlockUtils.getSubstitutionBlockAtWorld(level, pos, null);
        if (placeholder.getBlock() == ModBlocks.blockFluidSubstitution.get()) return com.ldtteam.structurize.util.BlockUtils.getFluidForDimension(level);
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /** @return true when the block entity data is of a type only operators may set (signs, spawners, command blocks...) */
    private static boolean opOnly(BlockPos pos, BlockState state) {
        BlockEntity be = state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb ? eb.newBlockEntity(pos, state) : null;
        return be != null && be.onlyOpCanSetNbt();
    }

    /** Structurize's creative fancy handler with a completion callback. */
    private static final class Handler extends CreativeStructureHandler {
        private final Runnable done;

        Handler(Level level, BlockPos pos, Blueprint bp, RotationMirror rot, Runnable done) {
            super(level, pos, bp, rot, true);
            this.done = done;
        }

        @Override
        public void onCompletion() {
            super.onCompletion();
            try {
                done.run();
            } catch (RuntimeException e) {
                CityBuilder.LOGGER.error("CityBuilder: finishing instant build failed", e);
            }
        }
    }
}
