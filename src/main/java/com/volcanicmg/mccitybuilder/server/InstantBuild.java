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
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
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
    /**
     * PLACE a hut, UPGRADE / REPAIR / CLEAN (placeholders) a building, DECO = place a decoration (no hut anchor),
     * DECO_ORDER = a survival decoration work order; MOVE = rebuild a building at {@code pos} and retire the one at
     * {@code from} (inventories copied, citizens moved); DECO_DELETE = remove a decoration's blocks that still match
     * its blueprint; DECO_MOVE = paste a decoration at {@code pos}, copy its containers from {@code from}, delete the
     * old; DECO_REMOVE_ORDER = a survival REMOVE work order for a decoration; DECO_REPAIR = paste a decoration City
     * Builder knows (a record or its live controller) again where it stands, at its turn: like DECO, only differing
     * blocks are replaced and storage holding items stays (creative); REMOVE = retire a building (not the town hall)
     * and clear its blocks as a move clears its old site, its containers emptied into the player's inventory.
     */
    public static final int PLACE = 0, UPGRADE = 1, REPAIR = 2, DECO = 3, CLEAN = 4, DECO_ORDER = 5, MOVE = 6, DECO_DELETE = 7, DECO_MOVE = 8, DECO_REMOVE_ORDER = 9,
            DECO_REPAIR = 10, REMOVE = 11;
    static final long MIN_INTERVAL_MS = 1000;
    static final Map<UUID, Long> lastRequest = new HashMap<>();

    /**
     * @param mode  {@link #PLACE}, {@link #UPGRADE} or {@link #REPAIR}
     * @param level the level to paste (place: chosen; upgrade: current + 1; repair: current)
     * @param pack  place only: style pack; @param path place only: blueprint path; @param rot place only: rotation
     * @param id    above 0: the client wants a {@link BatchResult} with this id once the request finished (accepted)
     *              or was refused (reason "busy" = try again shortly)
     * @param crop  a road / wall piece only: null, or the window {axis, from, len} of the blueprint to paste / order /
     *              remove instead of the whole piece (Fill gaps, {@link Crops}); never sent on its own
     */
    public record Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot, int id, BlockPos from, RotationMirror fromRot, int[] crop)
            implements CustomPacketPayload {
        public Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot) {
            this(mode, pos, level, pack, path, rot, 0, BlockPos.ZERO, RotationMirror.NONE);
        }

        public Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot, int id) {
            this(mode, pos, level, pack, path, rot, id, BlockPos.ZERO, RotationMirror.NONE);
        }

        public Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot, int id, BlockPos from, RotationMirror fromRot) {
            this(mode, pos, level, pack, path, rot, id, from, fromRot, null);
        }

        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "instant_build_" + PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, r) -> {
            buf.writeByte(r.mode);
            buf.writeBlockPos(r.pos);
            buf.writeVarInt(r.level);
            buf.writeUtf(r.pack, 256);
            buf.writeUtf(r.path, 512);
            buf.writeByte(r.rot.ordinal());
            buf.writeVarInt(r.id);
            buf.writeBlockPos(r.from);
            buf.writeByte(r.fromRot.ordinal());
        }, buf -> new Request(buf.readByte(), buf.readBlockPos(), buf.readVarInt(), buf.readUtf(256), buf.readUtf(512),
                RotationMirror.values()[Math.floorMod(buf.readByte(), RotationMirror.values().length)], buf.readVarInt(), buf.readBlockPos(),
                RotationMirror.values()[Math.floorMod(buf.readByte(), RotationMirror.values().length)]));

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    private InstantBuild() {
    }

    /**
     * The payload protocol, part of every payload's id: a client and server of different protocols just don't share
     * the channels (optional, so the planner falls back to MineColonies' own messages) instead of failing NeoForge's
     * version check, which disconnects. 7: record pages carry road / wall pieces; 8: and their owners; 9: shared
     * ghosts and plans ({@link PlanShare}).
     */
    public static final String PROTOCOL = "9";

    /** Registers the payload as optional, so clients and servers without the mod (or another protocol) still connect. */
    public static void register(RegisterPayloadHandlersEvent e) {
        e.registrar(PROTOCOL).optional().playToServer(Request.TYPE, Request.CODEC, InstantBuild::handle)
                .playToServer(PieceBatch.TYPE, PieceBatch.CODEC, InstantBuild::handleBatch)
                .playToClient(BatchResult.TYPE, BatchResult.CODEC, (r, ctx) -> BatchResults.accept(r))
                .playToServer(DecoSync.Query.TYPE, DecoSync.Query.CODEC, DecoSync::handleQuery)
                .playToClient(DecoSync.Decos.TYPE, DecoSync.Decos.CODEC, (d, ctx) -> DecoSync.accept(d))
                .playBidirectional(PlanShare.Ghost.TYPE, PlanShare.Ghost.CODEC, PlanShare::handleGhost)
                .playBidirectional(PlanShare.Shared.TYPE, PlanShare.Shared.CODEC, PlanShare::handleShared);
    }

    /** Prunes per-player state on logout. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> {
            DecoSync.flush();
            PlanShare.tick(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppedEvent.class, e -> {
            DecoSync.clear();
            PlanShare.clear();
        });
        // Another dimension: its colonies are other ones; the planner asks again when it opens there.
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
            DecoSync.forget(e.getEntity().getUUID());
            if (e.getEntity() instanceof ServerPlayer p) PlanShare.forget(p);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            // A paste that left Structurize's queue without finishing frees its positions and chunks now, not in BUSY_MS.
            Runnable gone = pastes.remove(e.getEntity().getUUID());
            if (gone != null) gone.run();
            lastRequest.remove(e.getEntity().getUUID());
            busyPlayers.remove(e.getEntity().getUUID());
            batches.remove(e.getEntity().getUUID());
            batchStats.remove(e.getEntity().getUUID());
            refused.remove(e.getEntity().getUUID());
            DecoSync.forget(e.getEntity().getUUID());
            if (e.getEntity() instanceof ServerPlayer p) PlanShare.forget(p);
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
    // Per player with a paste queued: what frees its locks at logout if that paste is no longer queued.
    private static final Map<UUID, Runnable> pastes = new HashMap<>();
    // Chunks a running request keeps loaded, per "player|stamp" (a dedicated server may have nothing loaded where the
    // planner looks); each request's tickets carry their own id, so releasing one never drops another's.
    // Expires on its own after 5 min (BUSY_MS), so a paste that never finishes never pins its chunks.
    private static final net.minecraft.server.level.TicketType<Long> HOLD = net.minecraft.server.level.TicketType.create("mccitybuilder_paste", Long::compare, 20 * 300);
    private record Hold(long id, List<net.minecraft.world.level.ChunkPos> chunks) {
    }

    private static final Map<String, Hold> held = new HashMap<>();
    private static long nextHold;

    /** @return how far (blocks, per axis) from a colony's centre counts as near it */
    static int reach() {
        // ponytail: twice the claim size around the centre (buildings claim beyond it); the colony's real claim map needs the chunk loaded.
        return 2 * (com.minecolonies.api.IMinecoloniesAPI.getInstance().getConfig().getServer().maxColonySize.get() + 1) * 16;
    }

    /**
     * Loads the chunks under the block box {@code a}..{@code b} and keeps them loaded for request {@code hk} until
     * {@link #unhold}; only near a colony the player may change this way (no loading anywhere else).
     */
    private static void hold(ServerLevel level, ServerPlayer p, int mode, String hk, BlockPos a, BlockPos b) {
        int reach = reach();
        boolean near = false;
        for (IColony c : IColonyManager.getInstance().getColonies(level))
            if (Math.abs(c.getCenter().getX() - a.getX()) <= reach && Math.abs(c.getCenter().getZ() - a.getZ()) <= reach && allowed(c, p, mode)) near = true;
        if (!near) return;
        Hold h = held.computeIfAbsent(hk, k -> new Hold(nextHold++, new ArrayList<>()));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                var c = new net.minecraft.world.level.ChunkPos(cx, cz);
                if (h.chunks.contains(c)) continue;
                level.getChunkSource().addRegionTicket(HOLD, c, 0, h.id);
                h.chunks.add(c);
                level.getChunk(cx, cz);
            }
    }

    private static void unhold(ServerLevel level, String hk) {
        Hold h = held.remove(hk);
        if (h != null) for (var c : h.chunks) level.getChunkSource().removeRegionTicket(HOLD, c, 0, h.id);
    }

    /** @return true while Structurize's queue still holds the operation (true when its queue can't be read: locks then wait out BUSY_MS) */
    private static boolean queued(Object op) {
        try {
            // ponytail: Structurize keeps its queue private; read by reflection.
            var f = Manager.class.getDeclaredField("scanToolOperationPool");
            f.setAccessible(true);
            return ((java.util.Collection<?>) f.get(null)).contains(op);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

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
        busyPositions.remove(key(level, r.from), stamp);
        unhold(level, p.getUUID() + "|" + stamp);
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

    /**
     * One road / wall piece: its blueprint path in the pack, anchor position, RotationMirror ordinal and, for a
     * cropped straight (Fill gaps), its window {axis (0 = X, 1 = Z), from, len} of the unturned blueprint (else null).
     */
    public record Piece(String path, BlockPos pos, int rot, int[] crop) {
        /**
         * @param path a piece path, a cropped straight's with its window ({@link PieceLayout#cropPath}); one whose
         *             window doesn't parse is sent as it is, and the server refuses it (not a blueprint path)
         */
        public Piece(String path, BlockPos pos, int rot) {
            this(PieceLayout.cropOf(path) == null ? path : PieceLayout.cropBase(path), pos, rot, PieceLayout.cropOf(path));
        }
    }

    /**
     * Up to {@link #MAX_BATCH} road or wall pieces of one style pack, handled one at a time through the same checks as
     * single decorations, in the order given: {@code mode} = {@link #DECO} (placed, creative), {@link #DECO_ORDER}
     * (ordered, survival), {@link #DECO_DELETE} (matching blocks removed, creative) or {@link #DECO_REMOVE_ORDER}
     * (a builder removes them, survival).
     */
    public record PieceBatch(int id, String pack, int mode, List<Piece> pieces) implements CustomPacketPayload {
        public boolean survival() {
            return mode == DECO_ORDER || mode == DECO_REMOVE_ORDER;
        }

        public static final Type<PieceBatch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "piece_batch_" + PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, PieceBatch> CODEC = StreamCodec.of((buf, b) -> {
            buf.writeVarInt(b.id);
            buf.writeUtf(b.pack, 256);
            buf.writeByte(b.mode);
            buf.writeVarInt(b.pieces.size());
            for (Piece q : b.pieces) {
                buf.writeUtf(q.path, 512);
                buf.writeBlockPos(q.pos);
                buf.writeByte(q.rot);
                buf.writeByte(q.crop == null ? -1 : q.crop[0]);
                if (q.crop != null) {
                    buf.writeVarInt(q.crop[1]);
                    buf.writeVarInt(q.crop[2]);
                }
            }
        }, buf -> {
            int id = buf.readVarInt();
            String pack = buf.readUtf(256);
            int mode = buf.readByte();
            int n = Math.min(buf.readVarInt(), MAX_BATCH);
            List<Piece> ps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String path = buf.readUtf(512);
                BlockPos pos = buf.readBlockPos();
                int rot = buf.readByte(), axis = buf.readByte();
                // Bounds are checked against the blueprint when it has loaded (Crops.crop).
                ps.add(new Piece(path, pos, rot, axis < 0 ? null : new int[]{axis == 0 ? 0 : 1, buf.readVarInt(), buf.readVarInt()}));
            }
            return new PieceBatch(id, pack, mode, ps);
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
        public static final Type<BatchResult> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "batch_result_" + PROTOCOL));
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
    // Per player while batches run: {done, total, the batch's mode}.
    private static final Map<UUID, int[]> batchStats = new HashMap<>();

    /**
     * @return the colony's open road / wall decoration work orders (any decoration order whose blueprint is a road /
     * wall piece): rebuilt from the colony's orders each time, so it survives restarts
     */
    static int openPieceOrders(IColony colony) {
        int n = 0;
        for (var wo : colony.getWorkManager().getWorkOrders().values())
            if (wo instanceof com.minecolonies.core.colony.workorders.WorkOrderDecoration && (wo.getWorkOrderType() == com.minecolonies.api.colony.workorders.WorkOrderType.REMOVE
                    || wo.getStructurePath() != null && com.volcanicmg.mccitybuilder.planner.PieceSets.tool(wo.getStructurePath()) != null)) n++;
        return n;
    }

    private static void answer(ServerPlayer p, int id, boolean ok, String reason) {
        if (p.hasDisconnected() || !p.connection.hasChannel(BatchResult.TYPE)) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new BatchResult(id, ok, reason));
    }

    private static void handleBatch(PieceBatch b, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        resumeIfStale(p);
        java.util.ArrayDeque<Request> running = batches.get(p.getUUID());
        int[] st = batchStats.get(p.getUUID());
        boolean survival = b.survival();
        String why = b.pieces.isEmpty() ? "empty" : b.mode != DECO && b.mode != DECO_ORDER && b.mode != DECO_DELETE && b.mode != DECO_REMOVE_ORDER ? "refused"
                : last != null && now - last < MIN_INTERVAL_MS ? "busy" : !survival && !p.isCreative() ? "creative"
                : running != null && running.size() + b.pieces.size() > MAX_QUEUED ? "busy"
                // One kind at a time, so the summary at the end is about one thing.
                : st != null && st[2] != b.mode ? "busy" : null;
        if (why == null)
            for (Piece q : b.pieces) if (com.volcanicmg.mccitybuilder.planner.PieceSets.tool(q.path) == null) why = "not_piece";
        ServerLevel level = p.serverLevel();
        IColony colony = null;
        if (why == null) {
            BlockPos first = b.pieces.get(0).pos;
            colony = level.isLoaded(first) ? IColonyManager.getInstance().getColonyByPosFromWorld(level, first) : null;
            if (colony == null || !allowed(colony, p, b.mode)) why = "permission";
        }
        // Survival: open road / wall orders plus what is already queued must stay under the colony's cap.
        if (why == null && survival && openPieceOrders(colony) + (running == null ? 0 : running.size()) + b.pieces.size() > MAX_OPEN_ORDERS) why = "too_many";
        if (why != null) {
            // Logged only: a single request still waiting keeps its own answer.
            CityBuilder.LOGGER.info("CityBuilder: rejected piece batch {} from {}: {}", b.id, p.getGameProfile().getName(), why);
            answer(p, b.id, false, why);
            return;
        }
        lastRequest.put(p.getUUID(), now);
        java.util.ArrayDeque<Request> q = new java.util.ArrayDeque<>();
        for (Piece pc : b.pieces)
            q.add(new Request(b.mode, pc.pos, 0, b.pack, pc.path, RotationMirror.values()[Math.floorMod(pc.rot, RotationMirror.values().length)], 0, BlockPos.ZERO,
                    RotationMirror.NONE, pc.crop));
        // A batch arriving while one runs joins its queue (one summary at the end).
        if (running != null) {
            running.addAll(q);
            batchStats.get(p.getUUID())[1] += q.size();
        } else {
            batches.put(p.getUUID(), q);
            batchStats.put(p.getUUID(), new int[]{0, q.size(), b.mode});
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
            p.displayClientMessage(Component.translatable(st[2] == DECO_ORDER ? "mccitybuilder.pieces.ordered" : st[2] == DECO_DELETE ? "mccitybuilder.pieces.deleted"
                    : st[2] == DECO_REMOVE_ORDER ? "mccitybuilder.pieces.remove_ordered" : "mccitybuilder.pieces.placed", st[0], Math.max(0, st[1] - st[0])), false);
    }

    /** @return the colony's permission check for this request (PLACE needs PLACE_HUTS as well, like MineColonies) */
    static boolean allowed(IColony colony, ServerPlayer p, int mode) {
        boolean places = mode == PLACE || mode == DECO || mode == DECO_REPAIR || mode == MOVE || mode == DECO_MOVE;
        // Removing a hut is what breaking it is: MineColonies' BREAK_HUTS too.
        return colony.getPermissions().hasPermission(p, Action.MANAGE_HUTS) && (!places || colony.getPermissions().hasPermission(p, Action.PLACE_HUTS))
                && (mode != REMOVE || colony.getPermissions().hasPermission(p, Action.BREAK_HUTS));
    }

    /** @return true for the survival-allowed modes: builder work orders */
    static boolean survivalMode(int mode) {
        return mode == DECO_ORDER || mode == DECO_REMOVE_ORDER;
    }

    /** @return true for requests survival players may send: the order modes, and REMOVE of an unbuilt hut (pick-up; levelOk pins level 0) */
    static boolean survivalOk(Request r) {
        return survivalMode(r.mode) || r.mode == REMOVE && r.level == 0;
    }

    /** @return true for the modes that take a pack / path / rotation from the client */
    static boolean pathMode(int mode) {
        return mode == PLACE || mode == DECO || mode == DECO_REPAIR || mode == DECO_ORDER || mode == DECO_DELETE || mode == DECO_MOVE || mode == DECO_REMOVE_ORDER;
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
        if (!p.isCreative() && !survivalOk(r)) {
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
        String hk = p.getUUID() + "|" + now;
        if (r.mode >= PLACE && r.mode <= REMOVE) {
            hold(level, p, r.mode, hk, r.pos, r.pos);
            if (r.mode == MOVE || r.mode == DECO_MOVE) hold(level, p, r.mode, hk, r.from, r.from);
        }
        if (load(p, r, level, now)) return true;
        unhold(level, hk);
        return false;
    }

    /** @return why a (normalised, {@link PasteRules#badPath}-checked) blueprint path of this pack isn't trusted, or null */
    static String badPack(String pack, String path) {
        StructurePackMeta meta = StructurePacks.getStructurePack(pack);
        if (meta == null || !StructurePacks.hasPack(pack)) return "unknown pack";
        // Client-synced packs live under blueprints/clients/<uuid>/: never trusted.
        if (meta.getPath().toAbsolutePath().normalize().startsWith(CLIENT_PACKS)) return "client pack";
        // Crops are only ever made from the real straight (a crop of a crop would let windows multiply).
        if (Crops.PACK.equals(pack)) return "crop pack";
        return meta.getPath().resolve(path).normalize().startsWith(meta.getPath().normalize()) ? null : "path leaves the pack";
    }

    /** The rest of {@link #submitChecked}, its chunks held. @return true when the blueprint load started (release() then frees them) */
    private static boolean load(ServerPlayer p, Request r, ServerLevel level, long now) {
        boolean move = r.mode == MOVE || r.mode == DECO_MOVE;
        if (r.mode < PLACE || r.mode > REMOVE || !level.isLoaded(r.pos) || move && (!level.isLoaded(r.from) || r.from.equals(r.pos))) {
            reject(p, r, "bad mode or unloaded position");
            return false;
        }
        if (busy(busyPlayers, p.getUUID(), now) || busy(busyPositions, key(level, r.pos), now) || move && busy(busyPositions, key(level, r.from), now)) {
            reject(p, r, "a paste is still running");
            return false;
        }
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, r.pos);
        if (colony == null || !allowed(colony, p, r.mode) || move && IColonyManager.getInstance().getColonyByPosFromWorld(level, r.from) != colony) {
            reject(p, r, "no colony or missing permission at " + r.pos.toShortString());
            return false;
        }
        String pack, path;
        RotationMirror rot;
        if (r.mode == MOVE) {
            // The building to move: its own pack and blueprint at its level; the town hall and parent / child buildings stay.
            IBuilding src = colony.getServerBuildingManager().getBuilding(r.from);
            String why = src == null ? "no building to move" : src instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall ? "the town hall can't move"
                    : src.hasParent() || !src.getChildren().isEmpty() ? "a parent or child building can't move" : src.getBuildingLevel() < 1 ? "not built yet"
                    : colony.getServerBuildingManager().getBuilding(r.pos) != null ? "a building is already at the target" : null;
            String bp = why != null || src.getBlueprintPath() == null ? null : src.getBlueprintPath().endsWith(".blueprint") ? src.getBlueprintPath() : src.getBlueprintPath() + ".blueprint";
            path = why == null ? HutCatalog.levelPath(bp, src.getBuildingLevel()) : null;
            pack = why == null ? src.getStructurePack() : null;
            if (why == null && (path == null || pack == null || !StructurePacks.hasPack(pack) || r.level != src.getBuildingLevel())) why = "no blueprint path or wrong level";
            if (why != null) {
                reject(p, r, why + " at " + r.from.toShortString());
                return false;
            }
            rot = r.rot;
        } else if (pathMode(r.mode)) {
            // Checked and used in one normalised form (Structurize turns \ into / itself).
            String reqPath = r.path.replace('\\', '/');
            String bad = PasteRules.badPath(reqPath, r.mode != PLACE ? 0 : r.level);
            if (bad == null) bad = badPack(r.pack, reqPath);
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
            if (r.mode == REMOVE && (b instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall || b.hasParent() || !b.getChildren().isEmpty())) {
                reject(p, r, "the town hall and parent / child buildings can't be removed: " + r.pos.toShortString());
                return false;
            }
            String bp = b.getBlueprintPath() == null ? null : b.getBlueprintPath().endsWith(".blueprint") ? b.getBlueprintPath() : b.getBlueprintPath() + ".blueprint";
            // A level 0 hut (only its block) is cleared against nothing; level 1 just to have a blueprint to load.
            path = HutCatalog.levelPath(bp, r.mode == REMOVE ? Math.max(1, r.level) : r.level);
            pack = b.getStructurePack();
            if (!levelOk(r, b) || path == null || pack == null || !StructurePacks.hasPack(pack)) {
                reject(p, r, "bad level step " + b.getBuildingLevel() + " -> " + r.level + " or no blueprint path");
                return false;
            }
            rot = b.getTileEntity() != null ? b.getTileEntity().getRotationMirror() : b.getRotationMirror();
        }
        busyPlayers.put(p.getUUID(), now);
        busyPositions.put(key(level, r.pos), now);
        if (move) busyPositions.put(key(level, r.from), now);
        int colonyId = colony.getID();
        // A crop's source must be one of its folder's straights as City Builder sorts them (read off the server thread).
        java.util.concurrent.CompletableFuture<java.util.Set<String>> straights = r.crop == null ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> Crops.straightsOf(pack, path, level.registryAccess()));
        StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).thenCombine(straights, (bp, ss) -> new Object[]{bp, ss}).whenComplete((res, t) -> level.getServer().execute(() -> {
            boolean started = false;
            Blueprint bp = res == null ? null : (Blueprint) res[0];
            try {
                if (bp == null) reject(p, r, "blueprint not found " + pack + " " + path);
                else if (r.crop == null) started = start(p, level, colonyId, r, pack, path, rot, bp, now, null);
                else {
                    // A cropped straight: from here on a piece of the crop pack like any other (records, snapshots, orders).
                    // Only a straight, along the side it runs on (its longer one), shorter than it.
                    @SuppressWarnings("unchecked") java.util.Set<String> ss = (java.util.Set<String>) res[1];
                    boolean runAxis = r.crop[0] == (bp.getSizeX() >= bp.getSizeZ() ? 0 : 1);
                    Blueprint c = ss.contains(path) && runAxis ? Crops.crop(bp, r.crop) : null;
                    if (c == null) reject(p, r, "bad crop window for " + pack + " " + path);
                    else {
                        java.nio.file.Path src = StructurePacks.getStructurePack(pack).getPath().resolve(path);
                        String cropPath = Crops.packPath(pack, path, r.crop, Crops.hash(java.nio.file.Files.readAllBytes(src)));
                        Crops.ensurePack();
                        // Removing: the crop as it was built (its straight may have changed since), from its own file.
                        String[] pp = Crops.packPathParts(pack, path, r.crop);
                        String built = r.mode == DECO_DELETE || r.mode == DECO_REMOVE_ORDER
                                ? BuiltRecords.of(level).recordedPath(colonyId, Crops.PACK, pp[0], pp[1], r.pos, rot.ordinal()) : null;
                        if (built != null && !built.equals(cropPath)) {
                            // ponytail: a few KB read on the server thread; queue it like the source if removals get heavy.
                            Blueprint old = StructurePacks.getBlueprint(Crops.PACK, built, true, level.registryAccess());
                            if (old == null) reject(p, r, "the crop as built is gone: " + built);
                            else started = start(p, level, colonyId, r, Crops.PACK, built, rot, old, now, null);
                        } else {
                            // Its own name in the crop pack (a pasted anchor block records where it came from).
                            c.setPackName(Crops.PACK);
                            c.setFilePath(Crops.fileOf(cropPath).getParent());
                            c.setFileName(Crops.fileOf(cropPath).getFileName().toString().replace(".blueprint", ""));
                            // Written only by start(), after every check.
                            // A deep copy: the NBT shares the blueprint's block entity tags, which turning it for the paste rewrites.
                            started = start(p, level, colonyId, r, Crops.PACK, cropPath, rot, c, now, com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(c).copy());
                        }
                    }
                }
            } catch (java.io.IOException ex) {
                reject(p, r, "can't read " + pack + " " + path + ": " + ex);
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

    /** UPGRADE: current + 1 within the max; REPAIR: the current level, built; REMOVE: the current level. */
    private static boolean levelOk(Request r, IBuilding b) {
        int cur = b.getBuildingLevel();
        if (r.mode == REMOVE) return r.level == cur;
        return r.mode == UPGRADE ? r.level == cur + 1 && r.level <= b.getMaxBuildingLevel() : r.level == cur && cur >= 1;
    }

    /**
     * On the server thread with the blueprint loaded: re-checks everything (the player, permission, the building and
     * its level may have changed while it loaded), checks the footprint, places the hut for PLACE, then pastes.
     *
     * @return true when the paste was queued (its completion releases the busy marks)
     */
    private static boolean start(ServerPlayer p, ServerLevel level, int colonyId, Request r, String pack, String path, RotationMirror rot, Blueprint bp, long stamp,
                                 CompoundTag cropFile) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        if (colony == null || !p.isCreative() && !survivalOk(r) || p.isRemoved() || !allowed(colony, p, r.mode)) {
            reject(p, r, "player or colony changed");
            return false;
        }
        boolean placesHut = r.mode == PLACE || r.mode == MOVE, decoLike = r.mode == DECO || r.mode == DECO_REPAIR || r.mode == DECO_ORDER || r.mode == DECO_DELETE || r.mode == DECO_MOVE || r.mode == DECO_REMOVE_ORDER;
        IBuilding existing = colony.getServerBuildingManager().getBuilding(r.pos);
        if (placesHut ? existing != null : !decoLike && (existing == null || !levelOk(r, existing))) {
            reject(p, r, "building changed while loading");
            return false;
        }
        IBuilding source = r.mode == MOVE ? colony.getServerBuildingManager().getBuilding(r.from) : null;
        if (r.mode == MOVE && (source == null || source.getBuildingLevel() != r.level || source.hasParent() || !source.getChildren().isEmpty())) {
            reject(p, r, "the building to move changed while loading");
            return false;
        }
        // Removing / moving: only what City Builder itself placed or ordered here, as recorded (exact pack, path, anchor,
        // turn), or a decoration whose live controller at the anchor says exactly that (builders, the build tool).
        BuiltRecords records = BuiltRecords.of(level);
        boolean removes = r.mode == DECO_DELETE || r.mode == DECO_MOVE || r.mode == DECO_REMOVE_ORDER;
        BlockPos recAt = r.mode == DECO_MOVE ? r.from : r.pos;
        int recRot = r.mode == DECO_MOVE ? r.fromRot.ordinal() : rot.ordinal();
        // A repair, too: only what City Builder or a builder put there.
        if ((removes || r.mode == DECO_REPAIR) && !records.has(colonyId, pack, path, recAt, recRot) && !liveController(level, recAt, pack, path, recRot)) {
            reject(p, r, "not something City Builder built: " + pack + " " + path + " at " + recAt.toShortString());
            return false;
        }
        // A move's source footprint: the same blueprint as it stands there (rotated there first), locked and checked like the target's.
        // A move's old site is cleared against the blueprint as it is, not as the new paste marks what it keeps.
        Blueprint pristine = r.mode == MOVE || r.mode == DECO_MOVE ? copy(bp, level) : null;
        RotationMirror srcRot = null;
        BlockPos srcOrigin = null, srcFar = null;
        if (r.mode == MOVE || r.mode == DECO_MOVE) {
            srcRot = r.mode == MOVE ? (source.getTileEntity() != null ? source.getTileEntity().getRotationMirror() : source.getRotationMirror()) : r.fromRot;
            bp.setRotationMirror(srcRot, level);
            srcOrigin = r.from.subtract(bp.getPrimaryBlockOffset());
            srcFar = srcOrigin.offset(bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1);
        }
        bp.setRotationMirror(rot, level);
        BlockPos offset = bp.getPrimaryBlockOffset(), origin = r.pos.subtract(offset);
        BlockPos far = origin.offset(bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1);
        if (srcOrigin != null && PasteRules.overlap(new int[]{srcOrigin.getX(), srcOrigin.getY(), srcOrigin.getZ(), srcFar.getX(), srcFar.getY(), srcFar.getZ()},
                new int[]{origin.getX(), origin.getY(), origin.getZ(), far.getX(), far.getY(), far.getZ()})) {
            reject(p, r, "the new footprint overlaps the old one");
            return false;
        }
        if (removes && touchesOthers(level, colony, srcOrigin != null ? srcOrigin : origin, srcOrigin != null ? srcFar : far)) {
            reject(p, r, "the footprint reaches a building, hut or storage");
            return false;
        }
        String hk = p.getUUID() + "|" + stamp;
        hold(level, p, r.mode, hk, origin, far);
        if (srcOrigin != null) hold(level, p, r.mode, hk, srcOrigin, srcFar);
        // Every chunk under the footprint must be loaded and owned by this colony (MineColonies' own rule); a survival
        // pick-up of an unbuilt hut only touches its hut block (inside the colony, checked above).
        if (!(r.mode == REMOVE && r.level == 0)) for (int cx = origin.getX() >> 4; cx <= far.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= far.getZ() >> 4; cz++)
                if (!level.hasChunk(cx, cz) || ColonyUtils.getOwningColony(level.getChunk(cx, cz)) != colonyId) {
                    reject(p, r, "footprint leaves the colony or isn't loaded at chunk " + cx + "," + cz);
                    return false;
                }
        List<String> chunks = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int cx = origin.getX() >> 4; cx <= far.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= far.getZ() >> 4; cz++) chunks.add(level.dimension().location() + "|" + cx + "," + cz);
        if (srcOrigin != null)
            for (int cx = srcOrigin.getX() >> 4; cx <= srcFar.getX() >> 4; cx++)
                for (int cz = srcOrigin.getZ() >> 4; cz <= srcFar.getZ() >> 4; cz++) {
                    if (!level.hasChunk(cx, cz) || ColonyUtils.getOwningColony(level.getChunk(cx, cz)) != colonyId) {
                        reject(p, r, "the source leaves the colony or isn't loaded at chunk " + cx + "," + cz);
                        return false;
                    }
                    String c = level.dimension().location() + "|" + cx + "," + cz;
                    if (!chunks.contains(c)) chunks.add(c);
                }
        for (String c : chunks)
            if (busy(busyChunks, c, now)) {
                reject(p, r, "another paste is running over chunk " + c);
                return false;
            }
        BlockState anchor = bp.getBlockState(offset);
        if (r.mode == DECO_DELETE) {
            String rk = PasteRules.recordKey(colonyId, pack, path, r.pos.getX(), r.pos.getY(), r.pos.getZ(), rot.ordinal());
            if (restoreOrDelete(p, r, level, colony, bp, origin, far, Snapshots.decoKey(level, colonyId, pack, path, r.pos, rot.ordinal()), rk, null, true) >= 0)
                records.remove(colonyId, pack, path, r.pos, rot.ordinal());
            return false;
        }
        if (placesHut) {
            if (!(anchor.getBlock() instanceof AbstractBlockHut<?> hut) || !level.getBlockState(r.pos).canBeReplaced()
                    || !colony.getServerBuildingManager().canPlaceAt(anchor.getBlock(), r.pos, p)
                    || r.level > hut.getBuildingEntry().produceBuilding(r.pos, colony).getMaxBuildingLevel()) {
                reject(p, r, "cannot place " + path + " at " + r.pos.toShortString());
                return false;
            }
        }
        // Decorations go through here only without a hut anchor (huts use PLACE, which registers the building).
        if (decoLike && anchor.getBlock() instanceof AbstractBlockHut<?>) {
            reject(p, r, "decoration request with a hut anchor: " + path);
            return false;
        }
        if (r.mode == CLEAN) return clean(p, r, level, colony, bp, origin, offset);
        if (r.mode == REMOVE) return removeHut(p, r, level, colony, pack, path, rot, bp, origin, far);
        if (r.mode == DECO_ORDER || r.mode == DECO_REMOVE_ORDER) {
            // What MineColonies' DecorationBuildRequestMessage does (MANAGE_HUTS, one order per location), after our
            // footprint checks: the builder then gathers the materials and builds it.
            for (var wo : colony.getWorkManager().getWorkOrders().values())
                if (wo.getLocation().equals(r.pos)) {
                    reject(p, r, "a work order is already at " + r.pos.toShortString());
                    return false;
                }
            // Road / wall pieces and removals count against the colony's cap, checked again here (queued batches may overshoot).
            if ((com.volcanicmg.mccitybuilder.planner.PieceSets.tool(path) != null || r.mode == DECO_REMOVE_ORDER) && openPieceOrders(colony) >= MAX_OPEN_ORDERS) {
                reject(p, r, "too many open road / wall work orders in colony " + colony.getID());
                return false;
            }
            // A removal only of what still stands there (an order that was cancelled left nothing to remove).
            if (r.mode == DECO_REMOVE_ORDER && !mostlyThere(level, bp, origin)) {
                reject(p, r, "the decoration isn't standing at " + r.pos.toShortString());
                return false;
            }
            // A cropped straight: its file, for the builder to load (a creative paste only needed it in memory).
            if (cropFile != null)
                try {
                    Crops.store(cropFile, path);
                } catch (java.io.IOException ex) {
                    reject(p, r, "can't write crop " + path + ": " + ex.getMessage());
                    return false;
                }
            String[] split = path.split("/");
            var order = com.minecolonies.core.colony.workorders.WorkOrderDecoration.create(
                    r.mode == DECO_REMOVE_ORDER ? com.minecolonies.api.colony.workorders.WorkOrderType.REMOVE : com.minecolonies.api.colony.workorders.WorkOrderType.BUILD, pack, path,
                    org.apache.commons.lang3.text.WordUtils.capitalizeFully(split[split.length - 1].replace(".blueprint", "")), r.pos, rot, 0);
            order.setBlueprint(bp, level);
            colony.getWorkManager().addWorkOrder(order, false);
            if (r.mode == DECO_REMOVE_ORDER) {
                records.remove(colonyId, pack, path, r.pos, rot.ordinal());
                // A builder takes it down its own way: what stood before can't come back on top of that.
                Snapshots.delete(level, Snapshots.decoKey(level, colonyId, pack, path, r.pos, rot.ordinal()));
            } else {
                records.add(colonyId, pack, path, r.pos, rot.ordinal(), new int[]{origin.getX(), origin.getY(), origin.getZ(), far.getX(), far.getY(), far.getZ()}, p.getUUID());
                // A builder builds it over whatever stands then: an older snapshot of this spot no longer holds.
                Snapshots.delete(level, Snapshots.decoKey(level, colonyId, pack, path, r.pos, rot.ordinal()));
            }
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
        java.util.Set<BlockPos> keptCells = new java.util.HashSet<>();
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
                    PasteRules.Action a = PasteRules.decide(placesHut && local.equals(offset), have.equals(want), isProtected(level, world, have));
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
                    if (a == PasteRules.Action.PLACE && have.isAir() && !want.isAir() && placeChecked.add(net.minecraft.world.level.ChunkPos.asLong(world.getX() >> 4, world.getZ() >> 4))
                            && NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, world), have, p)).isCanceled()) {
                        reject(p, r, "a claim or protection refused placing at " + world.toShortString());
                        return false;
                    }
                    // Cells the paste leaves as they are aren't the snapshot's to put back: a substitution, a block
                    // already as the blueprint has it, a kept one (the hut block itself it does place).
                    if (want.getBlock() == ModBlocks.blockSubstitution.get() || a != PasteRules.Action.PLACE && !(placesHut && local.equals(offset)))
                        keptCells.add(world);
                    if (a == PasteRules.Action.PLACE) continue;
                    if (a == PasteRules.Action.KEEP) kept++;
                    skip.add(local);
                    tes[y][z][x] = null;
                }
        // A pasted crop's file: its removal must find the crop as built even after its straight changes.
        if (cropFile != null && (r.mode == DECO || r.mode == DECO_REPAIR))
            try {
                Crops.store(cropFile, path);
            } catch (java.io.IOException ex) {
                reject(p, r, "can't write crop " + path + ": " + ex.getMessage());
                return false;
            }
        // What stands there now, for a later delete or move to put back (huts by building position).
        // A repair keeps whatever snapshot there is (what stood there now is the damaged decoration itself).
        String snapKey = placesHut ? Snapshots.hutKey(level, colonyId, r.pos) : r.mode == DECO || r.mode == DECO_MOVE
                ? Snapshots.decoKey(level, colonyId, pack, path, r.pos, rot.ordinal()) : null;
        // The same decoration pasted again where it stands keeps the snapshot of what stood before it the first time.
        if (snapKey != null && !(Snapshots.exists(level, snapKey) && (placesHut ? existing != null : records.has(colonyId, pack, path, r.pos, rot.ordinal()))))
            Snapshots.take(level, snapKey, origin, far, keptCells);
        if (placesHut) {
            BlockState previous = level.getBlockState(r.pos);
            level.setBlockAndUpdate(r.pos, anchor);
            ((AbstractBlockHut<?>) anchor.getBlock()).onBlockPlacedByBuildTool(level, r.pos, anchor, p, null, rot, pack, path);
            if (colony.getServerBuildingManager().getBuilding(r.pos) == null) {
                // No stray hut: put back what was there.
                level.setBlockAndUpdate(r.pos, previous);
                Snapshots.delete(level, snapKey);
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
        final RotationMirror fSrcRot = srcRot;
        final BlockPos fSrcOrigin = srcOrigin, fSrcFar = srcFar;
        final String fPack = pack, fPath = path;
        Handler handler = new Handler(level, r.pos, bp, rot, () -> {
            try {
                if (r.mode != DECO && r.mode != DECO_REPAIR && r.mode != DECO_MOVE) finish(level, colonyId, r, bp, origin, offset, dataPos, data);
                if (r.mode == DECO || r.mode == DECO_REPAIR || r.mode == DECO_MOVE) {
                    // A moved record stays its placer's.
                    java.util.UUID owner = r.mode == DECO_MOVE ? records.owner(colonyId, fPack, fPath, r.from, r.fromRot.ordinal()) : null;
                    records.add(colonyId, fPack, fPath, r.pos, rot.ordinal(), new int[]{origin.getX(), origin.getY(), origin.getZ(), far.getX(), far.getY(), far.getZ()},
                            owner != null ? owner : p.getUUID());
                }
                if (r.mode == MOVE || r.mode == DECO_MOVE) {
                    moveFinish(p, level, colonyId, r, fSrcRot, fSrcOrigin, fSrcFar, pristine, fPack, fPath);
                    if (r.mode == DECO_MOVE) records.remove(colonyId, fPack, fPath, r.from, r.fromRot.ordinal());
                }
            } finally {
                pastes.remove(p.getUUID());
                release(p, level, r, stamp);
                chunks.forEach(c -> busyChunks.remove(c, now));
            }
        });
        PlaceStructureOperation op = new PlaceStructureOperation(new StructurePlacer(handler), p);
        pastes.put(p.getUUID(), () -> {
            if (queued(op)) return;
            release(p, level, r, stamp);
            chunks.forEach(c -> busyChunks.remove(c, now));
        });
        Manager.addToQueue(op);
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
     * After the new building (or decoration) stands: every container of the old footprint is emptied into the
     * container at the same blueprint-relative position of the new one (the hut's inventory into the new hut's),
     * what has no place goes to the new hut, then the player, then is dropped at the player (never voided); the
     * building's citizens move to the same modules of the new building and its custom name comes along; then the
     * old building is retired ({@code destroy}) and the old blocks that still match the old blueprint are removed
     * (containers now empty included; storage with contents, huts and entity supports stay).
     */
    private static void moveFinish(ServerPlayer p, ServerLevel level, int colonyId, Request r, RotationMirror srcRot, BlockPos srcOrigin, BlockPos srcFar, Blueprint bp,
                                   String pack, String path) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        IBuilding old = colony == null || r.mode != MOVE ? null : colony.getServerBuildingManager().getBuilding(r.from);
        IBuilding nu = colony == null || r.mode != MOVE ? null : colony.getServerBuildingManager().getBuilding(r.pos);
        if (r.mode == MOVE && (colony == null || old == null || nu == null)) {
            CityBuilder.LOGGER.warn("CityBuilder: move from {} to {}: building missing after the paste; nothing retired", r.from, r.pos);
            return;
        }
        RotationMirror oldRot = r.mode == MOVE ? (old.getTileEntity() != null ? old.getTileEntity().getRotationMirror() : old.getRotationMirror()) : r.fromRot;
        RotationMirror diff = oldRot.calcDifferenceTowards(r.rot);
        if (!oldRot.add(diff).equals(r.rot)) diff = r.rot.calcDifferenceTowards(oldRot);
        // Containers first (the hut's own inventory is a container block entity too, mapped to the new hut). A hut
        // takes only its own: the hut block and the containers registered with it (others' storage in the box stays).
        java.util.Set<BlockPos> own = r.mode == MOVE ? new java.util.HashSet<>(old.getContainers()) : null;
        if (own != null) own.add(r.from);
        int containers = 0, toPlayer = 0;
        List<ItemStack> spare = new ArrayList<>();
        for (int y = srcOrigin.getY(); y <= srcFar.getY(); y++)
            for (int z = srcOrigin.getZ(); z <= srcFar.getZ(); z++)
                for (int x = srcOrigin.getX(); x <= srcFar.getX(); x++) {
                    BlockPos w = new BlockPos(x, y, z);
                    if (own != null && !own.contains(w)) continue;
                    IItemHandler from = handlerAt(level, w);
                    if (from == null || from.getSlots() == 0) continue;
                    BlockPos target = r.pos.offset(diff.applyToPos(w.subtract(r.from)));
                    IItemHandler to = handlerAt(level, target);
                    boolean any = false;
                    for (int i = 0; i < from.getSlots(); i++) {
                        ItemStack st = from.extractItem(i, Integer.MAX_VALUE, false);
                        if (st.isEmpty()) continue;
                        any = true;
                        ItemStack left;
                        try {
                            left = to == null ? st : ItemHandlerHelper.insertItem(to, st, false);
                        } catch (RuntimeException ex) {
                            // A modded handler that throws never swallows the stack in hand.
                            CityBuilder.LOGGER.warn("CityBuilder: container at {} refused a stack: {}", target, ex.toString());
                            left = st;
                        }
                        if (!left.isEmpty()) spare.add(left);
                    }
                    if (any) containers++;
                }
        // What found no container: the new hut's inventory, the player's, the ground at the player.
        IItemHandler hut = r.mode == MOVE ? handlerAt(level, r.pos) : null;
        for (ItemStack st : spare) {
            ItemStack left;
            try {
                left = hut == null ? st : ItemHandlerHelper.insertItem(hut, st, false);
            } catch (RuntimeException ex) {
                left = st;
            }
            if (left.isEmpty()) continue;
            toPlayer++;
            give(p, left);
        }
        if (r.mode == MOVE) {
            // Citizens: each assigning module of the old building hands its citizens to the same module of the new one.
            List<com.minecolonies.api.colony.buildings.modules.IAssignsCitizen> oldModules = old.getModulesByType(com.minecolonies.api.colony.buildings.modules.IAssignsCitizen.class),
                    newModules = nu.getModulesByType(com.minecolonies.api.colony.buildings.modules.IAssignsCitizen.class);
            for (int m = 0; m < oldModules.size(); m++) {
                var om = oldModules.get(m);
                com.minecolonies.api.colony.buildings.modules.IAssignsCitizen nm = null;
                for (var cand : newModules) if (cand.getClass() == om.getClass() && newModules.indexOf(cand) >= m) {
                    nm = cand;
                    break;
                }
                for (var c : new ArrayList<>(om.getAssignedCitizen())) {
                    om.removeCitizen(c);
                    if (nm != null) nm.assignCitizen(c);
                }
            }
            String name = old.getCustomName();
            if (name != null && !name.isEmpty()) nu.setCustomBuildingName(name);
            old.destroy();
            if (level.getBlockState(r.from).getBlock() instanceof AbstractBlockHut<?>) level.removeBlock(r.from, false);
        }
        // The old site: the blueprint turned as it stood there (the paste is done with it); back as it was before it
        // stood there when a snapshot exists.
        bp.setRotationMirror(srcRot, level);
        // A hut built up level by level stands as all of its levels: what a higher level leaves as it was (its
        // placeholders) is still the lower levels' blocks, so the old site is cleared against all of them.
        if (r.mode == MOVE) {
            Blueprint all = levels(level, pack, path, r.level, srcRot, bp);
            if (all != bp) {
                bp = all;
                srcOrigin = r.from.subtract(bp.getPrimaryBlockOffset());
                srcFar = srcOrigin.offset(bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1);
            }
        }
        String rk = r.mode == DECO_MOVE ? PasteRules.recordKey(colonyId, pack, path, r.from.getX(), r.from.getY(), r.from.getZ(), r.fromRot.ordinal()) : "";
        String sk = r.mode == MOVE ? Snapshots.hutKey(level, colonyId, r.from) : Snapshots.decoKey(level, colonyId, pack, path, r.from, r.fromRot.ordinal());
        int removed = restoreOrDelete(p, r, level, colony, bp, srcOrigin, srcFar, sk, rk, r.mode == MOVE ? r.from : null, false);
        p.displayClientMessage(Component.translatable(r.mode == MOVE ? "mccitybuilder.moved" : "mccitybuilder.deco_moved", containers, toPlayer, Math.max(0, removed)), false);
        // A claim refusing the clean-up after the move is done: the move stands, the old site is partly kept.
        if (removed < 0) p.displayClientMessage(Component.translatable("mccitybuilder.moved_kept"), false);
        if (r.mode == MOVE) nu.markDirty();
    }

    /**
     * @param top the hut's blueprint at {@code upTo}, turned {@code rot}
     * @return levels 1 to {@code upTo} of a hut's blueprint as one, turned {@code rot}, in the top level's box (what
     * the move checked and locked): per cell the highest level's block or air, a lower level's where a higher one
     * leaves the world as it is (a placeholder); anchored at the hut block. {@code top} itself for a level 1 hut or
     * when a level's file is missing.
     */
    private static Blueprint levels(ServerLevel level, String pack, String path, int upTo, RotationMirror rot, Blueprint top) {
        if (upTo <= 1) return top;
        List<Blueprint> bps = new ArrayList<>();
        for (int l = 1; l < upTo; l++) {
            String lp = HutCatalog.levelPath(path, l);
            // ponytail: loaded on the server thread at the end of a move (a few small files); load ahead if moves get big.
            Blueprint b = lp == null ? null : StructurePacks.getBlueprint(pack, lp, true, level.registryAccess());
            if (b == null) return top;
            b.setRotationMirror(rot, level);
            bps.add(b);
        }
        bps.add(top);
        // The top level's box around the hut block: what the move checked and locked (lower levels outside it stay).
        BlockPos to = top.getPrimaryBlockOffset();
        int[] lo = {-to.getX(), -to.getY(), -to.getZ()};
        Blueprint out = new Blueprint((short) top.getSizeX(), top.getSizeY(), (short) top.getSizeZ(), level.registryAccess());
        for (Blueprint b : bps) {
            BlockPos o = b.getPrimaryBlockOffset();
            short[][][] st = b.getStructure();
            BlockState[] pal = b.getPalette();
            for (int y = 0; y < b.getSizeY(); y++)
                for (int z = 0; z < b.getSizeZ(); z++)
                    for (int x = 0; x < b.getSizeX(); x++) {
                        BlockState want = pal[st[y][z][x]];
                        // Only "leave as is" lets a lower level show through; a ground or fluid fill is what this level put there.
                        if (want.getBlock() == ModBlocks.blockSubstitution.get() || want.getBlock() == com.minecolonies.api.blocks.ModBlocks.blockWayPoint) continue;
                        BlockPos at = new BlockPos(x - o.getX() - lo[0], y - o.getY() - lo[1], z - o.getZ() - lo[2]);
                        if (at.getX() < 0 || at.getY() < 0 || at.getZ() < 0 || at.getX() >= out.getSizeX() || at.getY() >= out.getSizeY() || at.getZ() >= out.getSizeZ()) continue;
                        // A higher level's air cleared it: whatever stands there now isn't the hut's.
                        out.addBlockState(at, want);
                    }
        }
        out.setCachePrimaryOffset(new BlockPos(-lo[0], -lo[1], -lo[2]));
        return out;
    }

    /** @return an unturned copy of an unturned blueprint (its own block and entity arrays) */
    private static Blueprint copy(Blueprint bp, ServerLevel level) {
        Blueprint c = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(bp).copy(), level.registryAccess());
        c.setPackName(bp.getPackName());
        if (bp.getFilePath() != null) c.setFilePath(bp.getFilePath());
        if (bp.getFileName() != null) c.setFileName(bp.getFileName());
        return c;
    }

    /**
     * @return true when a footprint reaches into a colony building (its corners), a hut block or storage with
     * contents: nothing City Builder removes may touch those
     */
    private static boolean touchesOthers(ServerLevel level, IColony colony, BlockPos origin, BlockPos far) {
        int[] box = {origin.getX(), origin.getY(), origin.getZ(), far.getX(), far.getY(), far.getZ()};
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            var c = b.getCorners();
            if (c == null) continue;
            BlockPos a = c.getA(), z = c.getB();
            if (PasteRules.overlap(box, new int[]{Math.min(a.getX(), z.getX()), Math.min(a.getY(), z.getY()), Math.min(a.getZ(), z.getZ()),
                    Math.max(a.getX(), z.getX()), Math.max(a.getY(), z.getY()), Math.max(a.getZ(), z.getZ())})) return true;
        }
        for (BlockPos w : BlockPos.betweenClosed(origin, far)) {
            BlockState st = level.getBlockState(w);
            if (st.getBlock() instanceof AbstractBlockHut<?>) return true;
            IItemHandler h = handlerAt(level, w);
            if (h != null) for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty()) return true;
        }
        return false;
    }

    /** @return true when a MineColonies decoration controller at {@code at} names this pack, blueprint and rotation */
    private static boolean liveController(ServerLevel level, BlockPos at, String pack, String path, int rot) {
        return level.getBlockEntity(at) instanceof com.minecolonies.core.tileentities.TileEntityDecorationController c && pack.equals(c.getPackName())
                && c.getBlueprintPath() != null && PasteRules.cleanPath(path).equals(PasteRules.cleanPath(c.getBlueprintPath()))
                && c.getRotationMirror() != null && c.getRotationMirror().ordinal() == rot;
    }

    /** @return true when at least half the blueprint's solid positions hold its block (the decoration stands there) */
    private static boolean mostlyThere(ServerLevel level, Blueprint bp, BlockPos origin) {
        short[][][] st = bp.getStructure();
        BlockState[] palette = bp.getPalette();
        int solid = 0, same = 0;
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockState want = palette[st[y][z][x]];
                    if (want.isAir() || isPlaceholder(want)) continue;
                    solid++;
                    if (level.getBlockState(origin.offset(x, y, z)).equals(want)) same++;
                }
        return solid == 0 || same * 2 >= solid;
    }

    /**
     * Puts a stack in the player's inventory and the rest on the ground at them. Not {@code Inventory.add}: for a
     * creative player that voids what doesn't fit.
     *
     * @return true when some of it was dropped
     */
    private static boolean give(ServerPlayer p, ItemStack st) {
        boolean fits = p.getInventory().getSlotWithRemainingSpace(st) >= 0 || p.getInventory().getFreeSlot() >= 0;
        p.getInventory().placeItemBackInInventory(st);
        return !fits;
    }

    /** @return the block's item handler (any side), or null */
    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, st, be, null);
        for (Direction d : Direction.values()) if (h == null) h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, st, be, d);
        return h;
    }

    /**
     * Removes the blocks of a decoration (or a retired building) that still match its blueprint: only those, never
     * storage with contents, huts or entity supports; one break event per block, all or nothing. An empty container
     * matching the blueprint goes too (a moved building's chests were just emptied).
     *
     * @param report true to tell the player (a plain delete; a refusal then answers the request); @return blocks
     *               removed, -1 when a claim refused (after a move, only the clean-up is lost)
     */
    private static int delete(ServerPlayer p, Request r, ServerLevel level, Blueprint bp, BlockPos origin, BlockPos far, boolean report) {
        return delete(p, r, level, bp, origin, far, report, w -> false, w -> false);
    }

    /**
     * {@link #delete}, never touching the cells {@code keep} names (neither removing nor filling them), and leaving the
     * exposed dirt {@code keepTop} names as it is
     */
    private static int delete(ServerPlayer p, Request r, ServerLevel level, Blueprint bp, BlockPos origin, BlockPos far, boolean report,
                              java.util.function.Predicate<BlockPos> keep, java.util.function.Predicate<BlockPos> keepTop) {
        java.util.Set<BlockPos> entityKept = entityProtected(level, origin, far);
        short[][][] st = bp.getStructure();
        BlockState[] palette = bp.getPalette();
        List<BlockPos> gone = new ArrayList<>();
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockPos world = origin.offset(x, y, z);
                    BlockState have = level.getBlockState(world), want = palette[st[y][z][x]];
                    if (have.isAir() || isPlaceholder(want) || keep.test(world)) continue;
                    IItemHandler h = handlerAt(level, world);
                    boolean empty = true;
                    if (h != null) for (int i = 0; i < h.getSlots() && empty; i++) empty = h.getStackInSlot(i).isEmpty();
                    // Same block, any state: walls, bars and vines change shape with their neighbours.
                    if (!PasteRules.remove(have.getBlock() == want.getBlock(), isProtected(level, world, have), empty, have.getBlock() instanceof AbstractBlockHut<?>, entityKept.contains(world))) continue;
                    if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, world, have, p)).isCanceled()) {
                        if (report) reject(p, r, "a claim or protection refused breaking " + world.toShortString());
                        else reject(p, "a claim or protection refused breaking " + world.toShortString() + " after a move");
                        return -1;
                    }
                    gone.add(world);
                }
        // Top down, so nothing falls or pops before its support goes; up to the terrain around it comes back as ground.
        gone.sort((a, b) -> b.getY() - a.getY());
        int top = groundAround(level, origin, far);
        // Liquids first, without neighbour updates: the decoration's own water can't spill out while its walls go.
        for (BlockPos w : gone)
            if (!level.getFluidState(w).isEmpty()) level.setBlock(w, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        // No drops and no shape updates mid-way (a door's or bed's other half would pop off as an item); neighbours after.
        BlockState surface = surfaceAround(level, origin, far, top);
        java.util.Set<BlockPos> filled = new java.util.HashSet<>();
        for (BlockPos w : gone) {
            BlockState g = ground(w.getY(), top, surface);
            level.setBlock(w, g, 2 | 16 | 32);
            if (!g.isAir()) filled.add(w);
        }
        settle(level, gone);
        // The blueprint's own hollows under the ground around it (a basin, a cellar) fill too; never a cell with an
        // entity in it, and only where placing is allowed (one place event per chunk, as the paste does).
        java.util.Set<BlockPos> occupied = new java.util.HashSet<>(entityKept);
        for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(), far.getX() + 1, far.getY() + 1, far.getZ() + 1), e -> true))
            BlockPos.betweenClosedStream(e.getBoundingBox().deflate(0.01)).forEach(b -> occupied.add(b.immutable()));
        java.util.Map<Long, Boolean> placeOk = new HashMap<>();
        for (int y = 0; y < bp.getSizeY() && origin.getY() + y <= top; y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockPos w = origin.offset(x, y, z);
                    if (!palette[st[y][z][x]].isAir() || !level.getBlockState(w).isAir() || occupied.contains(w) || keep.test(w)) continue;
                    BlockState fill = ground(w.getY(), top, surface);
                    if (!placeOk.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(w.getX() >> 4, w.getZ() >> 4), k -> !NeoForge.EVENT_BUS.post(
                            new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, w), level.getBlockState(w.below()), p)).isCanceled())) continue;
                    level.setBlock(w, fill, 3);
                    filled.add(w);
                }
        // Each column's exposed top under the footprint: dirt there (a refill, or grass the placement smothered) takes
        // the surface block around it; never under water or another block, never a kept cell.
        for (int x = origin.getX(); x <= far.getX(); x++)
                for (int z = origin.getZ(); z <= far.getZ(); z++)
                    // Down to the column's top, under the box too (ground lower than the floor, smothered under it).
                    for (int y = far.getY(); y >= origin.getY() - 16; y--) {
                        BlockPos w = new BlockPos(x, y, z);
                        BlockState have = level.getBlockState(w);
                        // Grass, flowers, snow and the placement's own water still running off don't make a column's top.
                        if (have.isAir() || have.canBeReplaced() && !have.getFluidState().isSource()) continue;
                        BlockPos up = w.above();
                        // Claims are asked per chunk like the fill; sand never goes over air (it would fall).
                        if (have.is(net.minecraft.world.level.block.Blocks.DIRT) && !keepTop.test(w) && !level.getFluidState(up).isSource()
                                && !(surface.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && level.getBlockState(w.below()).isAir())
                                && placeOk.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4), k -> !NeoForge.EVENT_BUS.post(
                                        new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, w), level.getBlockState(w.below()), p)).isCanceled()))
                            level.setBlock(w, surface, 3);
                        break;
                    }
        if (report) {
            done(p);
            p.displayClientMessage(Component.translatable("mccitybuilder.deleted", gone.size()), false);
        }
        return gone.size();
    }

    /**
     * Takes a placement down: back to what stood there before it was pasted when a snapshot exists
     * ({@link #restore}), else {@link #delete}.
     *
     * @param snapKey the placement's snapshot key; @param recordKey its record (other records' footprints are kept)
     * @param retired a retired building's position (its old corners don't count as standing), or null
     */
    private static int restoreOrDelete(ServerPlayer p, Request r, ServerLevel level, IColony colony, Blueprint bp, BlockPos origin, BlockPos far, String snapKey,
                                       String recordKey, BlockPos retired, boolean report) {
        Snapshots.Snap snap = Snapshots.load(level, snapKey);
        if (snap == null || !snap.origin().equals(origin) || snap.sx() != bp.getSizeX() || snap.sy() != bp.getSizeY() || snap.sz() != bp.getSizeZ())
            return delete(p, r, level, bp, origin, far, report);
        // What still stands over it: the cells newer placements changed (their snapshots say which; a newer record
        // without one, by its box) and the colony's buildings. An older placement under it comes back from this
        // snapshot, which holds its blocks. Newer placements take over what stood before this one.
        int[] mine = {origin.getX(), origin.getY(), origin.getZ(), far.getX(), far.getY(), far.getZ()};
        List<int[]> live = new ArrayList<>();
        List<String> newer = new ArrayList<>();
        List<Snapshots.Snap> newerSnaps = new ArrayList<>();
        for (var en : BuiltRecords.of(level).otherBoxes(recordKey).entrySet()) {
            if (!PasteRules.overlap(mine, en.getValue())) continue;
            String yk = Snapshots.recordSnapKey(level, en.getKey());
            Snapshots.Snap y = Snapshots.load(level, yk);
            if (y != null && y.time() < snap.time()) continue;
            if (y == null) live.add(en.getValue());
            else {
                newer.add(yk);
                newerSnaps.add(y);
            }
        }
        if (colony != null)
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                var c = b.getCorners();
                if (c == null || b.getPosition().equals(retired)) continue;
                BlockPos a = c.getA(), z = c.getB();
                live.add(new int[]{Math.min(a.getX(), z.getX()), Math.min(a.getY(), z.getY()), Math.min(a.getZ(), z.getZ()),
                        Math.max(a.getX(), z.getX()), Math.max(a.getY(), z.getY()), Math.max(a.getZ(), z.getZ())});
            }
        java.util.function.Predicate<BlockPos> covered = w -> {
            if (PasteRules.inAny(live, w.getX(), w.getY(), w.getZ())) return true;
            for (Snapshots.Snap y : newerSnaps) {
                int i = y.at(w);
                if (i >= 0 && y.cells()[i] >= 0) return true;
            }
            return false;
        };
        int n = restore(p, r, level, bp, origin, far, snap, covered, report);
        if (n >= 0) {
            Snapshots.handOver(level, snap, newer);
            Snapshots.delete(level, snapKey);
        }
        // A retired hut's upgrades built where its first level left the world as it was (the snapshot doesn't hold
        // those cells): what still matches any of its levels goes too, down to the ground around it.
        if (n >= 0 && retired != null) {
            int m = delete(p, r, level, bp, origin, far, report, w -> {
                int i = snap.at(w);
                return i >= 0 && snap.cells()[i] >= 0 || covered.test(w);
            }, w -> {
                // Smothered grass the snapshot held comes back as grass; dirt the snapshot says was dirt stays.
                int i = snap.at(w);
                return covered.test(w) || i >= 0 && snap.cells()[i] >= 0 && snap.palette()[snap.cells()[i]].is(net.minecraft.world.level.block.Blocks.DIRT);
            });
            return m < 0 ? m : n + m;
        }
        return n;
    }

    /**
     * Puts each cell the paste may have changed back to its snapshot: never a placeholder cell (never touched), one the
     * paste kept, one inside another live footprint, a hut or an item-holding entity's support. A break event per
     * block it removes and a place event per chunk, all or nothing; liquids drain first; storage that holds items now
     * hands them to the player (or drops them at the player) before its block goes. Never voids items.
     *
     * @return cells changed, -1 when a claim refused
     */
    private static int restore(ServerPlayer p, Request r, ServerLevel level, Blueprint bp, BlockPos origin, BlockPos far, Snapshots.Snap snap,
                               java.util.function.Predicate<BlockPos> covered, boolean report) {
        java.util.Set<BlockPos> entityKept = entityProtected(level, origin, far);
        short[][][] st = bp.getStructure();
        BlockState[] palette = bp.getPalette();
        List<BlockPos> cells = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        java.util.Set<Long> placeChecked = new java.util.HashSet<>();
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockPos w = origin.offset(x, y, z);
                    int i = snap.index(x, y, z);
                    BlockState have = level.getBlockState(w);
                    // Only "leave as is" cells skip (older snapshots still hold them); filled placeholders come back like any cell.
                    if (!PasteRules.restore(palette[st[y][z][x]].getBlock() == ModBlocks.blockSubstitution.get(), snap.cells()[i] < 0, covered.test(w),
                            have.getBlock() instanceof AbstractBlockHut<?> || entityKept.contains(w))) continue;
                    BlockState want = snap.palette()[snap.cells()[i]];
                    if (have.equals(want) && !snap.bes().containsKey(i)) continue;
                    if (!have.isAir() && NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, w, have, p)).isCanceled()
                            || !want.isAir() && placeChecked.add(net.minecraft.world.level.ChunkPos.asLong(w.getX() >> 4, w.getZ() >> 4))
                            && NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, w), have, p)).isCanceled()) {
                        if (report) reject(p, r, "a claim or protection refused restoring " + w.toShortString());
                        else reject(p, "a claim or protection refused restoring " + w.toShortString() + " after a move");
                        return -1;
                    }
                    cells.add(w);
                    idx.add(i);
                }
        // Storage holding items now: its contents go to the player first.
        for (BlockPos w : cells) {
            IItemHandler h = handlerAt(level, w);
            if (h == null) continue;
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack st2 = h.extractItem(s, Integer.MAX_VALUE, false);
                if (!st2.isEmpty()) give(p, st2);
            }
        }
        // Liquids first, without neighbour updates, then every cell bottom up (supports before what stands on them).
        for (BlockPos w : cells)
            if (!level.getFluidState(w).isEmpty()) level.setBlock(w, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < cells.size(); k++) order.add(k);
        order.sort((a, b) -> cells.get(a).getY() - cells.get(b).getY());
        for (int k : order) {
            BlockPos w = cells.get(k);
            int i = idx.get(k);
            // No drops and no shape updates mid-way (a door's or bed's other half would pop off); neighbours after.
            level.setBlock(w, snap.palette()[snap.cells()[i]], 2 | 16 | 32);
            CompoundTag tag = snap.bes().get(i);
            BlockEntity be = tag == null ? null : level.getBlockEntity(w);
            if (be != null) {
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(w, snap.palette()[snap.cells()[i]], snap.palette()[snap.cells()[i]], 3);
            }
        }
        settle(level, cells);
        if (report) {
            done(p);
            p.displayClientMessage(Component.translatable("mccitybuilder.restored", cells.size()), false);
        }
        return cells.size();
    }

    /**
     * After cells were set without shape updates or drops: each takes the shape its neighbours give it, tells its
     * neighbours its own (fences, panes, walls, stairs, dust reconnect), then the usual neighbour updates.
     */
    private static void settle(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos w : cells) {
            BlockState s = level.getBlockState(w), u = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(s, level, w);
            if (u != s) level.setBlock(w, u, 2 | 32);
            u.updateNeighbourShapes(level, w, 2 | 32);
        }
        for (BlockPos w : cells) level.updateNeighborsAt(w, level.getBlockState(w).getBlock());
    }

    /** @return the refill for height y: dirt under the surrounding ground's top, grass at it, air above */
    private static BlockState ground(int y, int top, BlockState surface) {
        return y < top ? net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState() : y == top ? surface : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /**
     * @return the most common top block of the natural ground just outside the footprint at {@code top} (grass, sand,
     * podzol...), grass when there's none; the block a refill's exposed top takes
     */
    private static BlockState surfaceAround(ServerLevel level, BlockPos origin, BlockPos far, int top) {
        java.util.Map<BlockState, Integer> n = new HashMap<>();
        for (int x = origin.getX() - 1; x <= far.getX() + 1; x++)
            for (int z = origin.getZ() - 1; z <= far.getZ() + 1; z++) {
                if (x >= origin.getX() && x <= far.getX() && z >= origin.getZ() && z <= far.getZ()) continue;
                int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockState t = level.getBlockState(new BlockPos(x, h, z));
                // Plain dirt doesn't count: it is mostly grass the placement shaded (and would be the patch itself).
                if (!t.is(net.minecraft.world.level.block.Blocks.DIRT) && (t.is(net.minecraft.tags.BlockTags.DIRT) || t.is(net.minecraft.tags.BlockTags.SAND)
                        || t.is(net.minecraft.world.level.block.Blocks.GRAVEL))) n.merge(t.getBlock().defaultBlockState(), 1, Integer::sum);
            }
        BlockState best = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
        int most = 0;
        for (var en : n.entrySet())
            if (en.getValue() > most) {
                most = en.getValue();
                best = en.getKey();
            }
        return best;
    }

    /**
     * @return the top block y of the natural ground just outside the footprint (its most common height), or
     * Integer.MIN_VALUE (no refill) when the ring touches water or has no natural ground; buildings beside it don't count
     */
    private static int groundAround(ServerLevel level, BlockPos origin, BlockPos far) {
        List<int[]> ring = new ArrayList<>();
        for (int x = origin.getX() - 1; x <= far.getX() + 1; x++) {
            ring.add(new int[]{x, origin.getZ() - 1});
            ring.add(new int[]{x, far.getZ() + 1});
        }
        for (int z = origin.getZ(); z <= far.getZ(); z++) {
            ring.add(new int[]{origin.getX() - 1, z});
            ring.add(new int[]{far.getX() + 1, z});
        }
        List<Integer> hs = new ArrayList<>();
        for (int[] c : ring) {
            // Never load a chunk for this.
            if (!level.hasChunk(c[0] >> 4, c[1] >> 4)) continue;
            BlockPos surface = new BlockPos(c[0], level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, c[0], c[1]) - 1, c[1]);
            // By water the old site stays open so the water flows back.
            if (!level.getFluidState(surface).isEmpty()) return Integer.MIN_VALUE;
            BlockPos top = new BlockPos(c[0], level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0], c[1]) - 1, c[1]);
            BlockState t = level.getBlockState(top);
            if (t.is(net.minecraft.world.level.block.Blocks.SNOW)) t = level.getBlockState(top = top.below());
            // Terracotta stays out: it's a common roof and wall block.
            if (t.is(net.minecraft.tags.BlockTags.DIRT) || t.is(net.minecraft.tags.BlockTags.SAND) || t.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                    || t.is(net.minecraft.world.level.block.Blocks.GRAVEL) || t.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))
                hs.add(top.getY());
        }
        return PasteRules.mode(hs);
    }

    /**
     * @return true for blocks the paste never replaces: storage (a Container block entity or an item handler on any
     * side, empty or not) and huts. Other block entities (beds, signs, banners...) are replaced like any block.
     */
    static boolean isProtected(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof AbstractBlockHut<?>) return true;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        if (be instanceof Container || be instanceof net.minecraft.world.Clearable) return true;
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
        // Citizens, villagers and mobs carry their things with them (and walk off): only things that stay put count.
        if (e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)) return false;
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
    /**
     * REMOVE, on the server thread after every check: the building's own containers and its hut block are emptied
     * into the player's inventory (what doesn't fit is dropped at the player, never voided), its work orders are
     * cancelled, it is retired the way breaking its hut retires it ({@code destroy}: citizens unassigned), its hut block
     * goes (no item: MineColonies drops none for a creative break), then the site is cleared as a move clears its old
     * one (all levels merged, back to the snapshot when there is one, storage with contents and other buildings kept).
     *
     * @return false (nothing is pasted; the caller releases the busy marks)
     */
    private static boolean removeHut(ServerPlayer p, Request r, ServerLevel level, IColony colony, String pack, String path, RotationMirror rot, Blueprint bp,
                                     BlockPos origin, BlockPos far) {
        IBuilding b = colony.getServerBuildingManager().getBuilding(r.pos);
        if (b == null || b instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall) {
            reject(p, r, "no building to remove at " + r.pos.toShortString());
            return false;
        }
        java.util.Set<BlockPos> own = new java.util.LinkedHashSet<>(b.getContainers());
        own.add(r.pos);
        int stacks = 0, dropped = 0;
        for (BlockPos w : own) {
            // A registered container in an unloaded chunk keeps its items (never load chunks for this).
            if (!level.isLoaded(w)) continue;
            IItemHandler from = handlerAt(level, w);
            if (from == null) continue;
            for (int i = 0; i < from.getSlots(); i++) {
                ItemStack st = from.extractItem(i, Integer.MAX_VALUE, false);
                if (st.isEmpty()) continue;
                stacks++;
                if (give(p, st)) dropped++;
            }
        }
        for (var wo : new ArrayList<>(colony.getWorkManager().getWorkOrders().values()))
            if (wo.getLocation().equals(r.pos)) colony.getWorkManager().removeWorkOrder(wo.getID());
        b.destroy();
        net.minecraft.world.level.block.Block hut = level.getBlockState(r.pos).getBlock();
        if (hut instanceof AbstractBlockHut<?>) level.removeBlock(r.pos, false);
        // Survival pick-up of an unbuilt hut: the hut itself goes back to the player.
        boolean pickUp = !p.isCreative() && r.level == 0 && hut instanceof AbstractBlockHut<?>;
        if (pickUp && give(p, new ItemStack(hut))) dropped++;
        int removed = 0;
        if (r.level >= 1) {
            Blueprint all = levels(level, pack, path, r.level, rot, bp);
            BlockPos o = origin, f = far;
            if (all != bp) {
                o = r.pos.subtract(all.getPrimaryBlockOffset());
                f = o.offset(all.getSizeX() - 1, all.getSizeY() - 1, all.getSizeZ() - 1);
            }
            removed = restoreOrDelete(p, r, level, colony, all, o, f, Snapshots.hutKey(level, colony.getID(), r.pos), "", r.pos, false);
        }
        done(p);
        p.displayClientMessage(pickUp ? Component.translatable("mccitybuilder.picked_up", dropped)
                : Component.translatable("mccitybuilder.removed", stacks, dropped, Math.max(0, removed)), false);
        // A claim refusing part of the clean-up: the building is gone, part of its site stays.
        if (removed < 0) p.displayClientMessage(Component.translatable("mccitybuilder.moved_kept"), false);
        return false;
    }

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

    static boolean isPlaceholder(BlockState s) {
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
