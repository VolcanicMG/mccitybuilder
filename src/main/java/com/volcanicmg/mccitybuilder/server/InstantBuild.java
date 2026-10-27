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
    public static final int PLACE = 0, UPGRADE = 1, REPAIR = 2, DECO = 3, CLEAN = 4;
    private static final long MIN_INTERVAL_MS = 1000;
    private static final Map<UUID, Long> lastRequest = new HashMap<>();

    /**
     * @param mode  {@link #PLACE}, {@link #UPGRADE} or {@link #REPAIR}
     * @param level the level to paste (place: chosen; upgrade: current + 1; repair: current)
     * @param pack  place only: style pack; @param path place only: blueprint path; @param rot place only: rotation
     */
    public record Request(int mode, BlockPos pos, int level, String pack, String path, RotationMirror rot) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "instant_build"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, r) -> {
            buf.writeByte(r.mode);
            buf.writeBlockPos(r.pos);
            buf.writeVarInt(r.level);
            buf.writeUtf(r.pack, 256);
            buf.writeUtf(r.path, 512);
            buf.writeByte(r.rot.ordinal());
        }, buf -> new Request(buf.readByte(), buf.readBlockPos(), buf.readVarInt(), buf.readUtf(256), buf.readUtf(512),
                RotationMirror.values()[Math.floorMod(buf.readByte(), RotationMirror.values().length)]));

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** A creative road: the road block positions (each replaces the surface block there) and the block id. */
    public record RoadRequest(List<BlockPos> road, String block) implements CustomPacketPayload {
        public static final Type<RoadRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "instant_road"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RoadRequest> CODEC = StreamCodec.of((buf, r) -> {
            buf.writeVarInt(r.road.size());
            for (BlockPos p : r.road) buf.writeLong(p.asLong());
            buf.writeUtf(r.block, 256);
        }, buf -> {
            int n = Math.min(buf.readVarInt(), com.volcanicmg.mccitybuilder.planner.Roads.MAX_COLUMNS);
            List<BlockPos> road = new ArrayList<>();
            for (int i = 0; i < n; i++) road.add(BlockPos.of(buf.readLong()));
            return new RoadRequest(road, buf.readUtf(256));
        });

        @Override
        public Type<RoadRequest> type() {
            return TYPE;
        }
    }

    private InstantBuild() {
    }

    /**
     * A creative road, all-or-nothing like the pastes: creative, rate limit, MANAGE_HUTS in the colony at the first
     * position, every chunk loaded and owned by that colony, a plain block (no block entity, not a game-master block);
     * then per column the road block replaces the surface, two blocks above are cleared and an empty block below is
     * filled. Storage, huts and blocks around item-holding entities are never touched; break / place events first.
     */
    private static void handleRoad(RoadRequest r, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        if (!p.isCreative() || last != null && now - last < MIN_INTERVAL_MS || r.road.isEmpty()) {
            reject(p, "road: not creative, rate limit or empty");
            return;
        }
        lastRequest.put(p.getUUID(), now);
        ServerLevel level = p.serverLevel();
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(r.block)).orElse(null);
        BlockState mat = block == null ? null : block.defaultBlockState();
        if (mat == null || mat.isAir() || mat.hasBlockEntity() || block instanceof GameMasterBlock) {
            reject(p, "road: bad block " + r.block);
            return;
        }
        BlockPos first = r.road.get(0);
        IColony colony = level.isLoaded(first) ? IColonyManager.getInstance().getColonyByPosFromWorld(level, first) : null;
        if (colony == null || !allowed(colony, p, UPGRADE)) {
            reject(p, "road: no colony or permission");
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos q : r.road) {
            if (q.distManhattan(first) > 2048 || !level.hasChunk(q.getX() >> 4, q.getZ() >> 4)
                    || ColonyUtils.getOwningColony(level.getChunk(q.getX() >> 4, q.getZ() >> 4)) != colony.getID()) {
                reject(p, "road leaves the colony or isn't loaded at " + q.toShortString());
                return;
            }
            minX = Math.min(minX, q.getX());
            minY = Math.min(minY, q.getY());
            minZ = Math.min(minZ, q.getZ());
            maxX = Math.max(maxX, q.getX());
            maxY = Math.max(maxY, q.getY());
            maxZ = Math.max(maxZ, q.getZ());
        }
        java.util.Set<BlockPos> entityKept = entityProtected(level, new BlockPos(minX, minY - 1, minZ), new BlockPos(maxX, maxY + 2, maxZ));
        java.util.Map<BlockPos, BlockState> changes = new java.util.LinkedHashMap<>();
        java.util.Set<Long> placeChecked = new java.util.HashSet<>();
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        int kept = 0;
        for (BlockPos q : r.road)
            for (int dy = -1; dy <= 2; dy++) {
                BlockPos w = q.above(dy);
                BlockState have = level.getBlockState(w);
                BlockState want = dy == 0 ? mat : dy > 0 ? air : have.canBeReplaced() ? mat : have;
                if (have.equals(want) || changes.containsKey(w)) continue;
                if (isProtected(level, w, have) || entityKept.contains(w)) {
                    kept++;
                    continue;
                }
                if (!have.isAir() && NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, w, have, p)).isCanceled()
                        || have.isAir() && !want.isAir() && placeChecked.add(w.getX() >> 4 | (long) (w.getZ() >> 4) << 32)
                        && NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, w), have, p)).isCanceled()) {
                    reject(p, "a claim or protection refused the road at " + w.toShortString());
                    return;
                }
                changes.put(w, want);
            }
        changes.forEach((w, st) -> level.setBlock(w, st, 3));
        p.displayClientMessage(Component.translatable("mccitybuilder.road.built", r.road.size(), kept), false);
    }

    /** Registers the payload as optional, so clients and servers without the mod still connect. */
    public static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("1").optional().playToServer(Request.TYPE, Request.CODEC, InstantBuild::handle)
                .playToServer(RoadRequest.TYPE, RoadRequest.CODEC, InstantBuild::handleRoad);
    }

    /** Prunes per-player state on logout. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            lastRequest.remove(e.getEntity().getUUID());
            busyPlayers.remove(e.getEntity().getUUID());
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

    private static void release(ServerPlayer p, ServerLevel level, BlockPos pos) {
        busyPlayers.remove(p.getUUID());
        busyPositions.remove(key(level, pos));
    }

    private static void reject(ServerPlayer p, String why) {
        CityBuilder.LOGGER.info("CityBuilder: rejected instant build from {}: {}", p.getGameProfile().getName(), why);
    }

    /** @return the colony's permission check for this request (PLACE needs PLACE_HUTS as well, like MineColonies) */
    private static boolean allowed(IColony colony, ServerPlayer p, int mode) {
        return colony.getPermissions().hasPermission(p, Action.MANAGE_HUTS) && (mode != PLACE && mode != DECO || colony.getPermissions().hasPermission(p, Action.PLACE_HUTS));
    }

    private static void handle(Request r, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        if (!p.isCreative()) {
            reject(p, "not in creative");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            reject(p, "rate limit");
            return;
        }
        lastRequest.put(p.getUUID(), now);
        ServerLevel level = p.serverLevel();
        if (r.mode < PLACE || r.mode > CLEAN || !level.isLoaded(r.pos)) {
            reject(p, "bad mode or unloaded position");
            return;
        }
        if (busy(busyPlayers, p.getUUID(), now) || busy(busyPositions, key(level, r.pos), now)) {
            reject(p, "a paste is still running");
            return;
        }
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, r.pos);
        if (colony == null || !allowed(colony, p, r.mode)) {
            reject(p, "no colony or missing permission at " + r.pos.toShortString());
            return;
        }
        String pack, path;
        RotationMirror rot;
        if (r.mode == PLACE || r.mode == DECO) {
            // Checked and used in one normalised form (Structurize turns \ into / itself).
            String reqPath = r.path.replace('\\', '/');
            String bad = PasteRules.badPath(reqPath, r.mode == DECO ? 0 : r.level);
            StructurePackMeta meta = StructurePacks.getStructurePack(r.pack);
            if (bad == null && (meta == null || !StructurePacks.hasPack(r.pack))) bad = "unknown pack";
            // Client-synced packs live under blueprints/clients/<uuid>/: never trusted.
            if (bad == null && meta.getPath().toAbsolutePath().normalize().startsWith(CLIENT_PACKS)) bad = "client pack";
            if (bad == null && !meta.getPath().resolve(reqPath).normalize().startsWith(meta.getPath().normalize())) bad = "path leaves the pack";
            if (bad == null && r.mode == PLACE && (r.level < 1 || colony.getServerBuildingManager().getBuilding(r.pos) != null)) bad = "level < 1 or a building is already there";
            if (bad != null) {
                reject(p, bad + ": " + r.pack + " " + r.path);
                return;
            }
            pack = r.pack;
            path = reqPath;
            rot = r.rot;
        } else {
            IBuilding b = colony.getServerBuildingManager().getBuilding(r.pos);
            if (b == null) {
                reject(p, "no building at " + r.pos.toShortString());
                return;
            }
            String bp = b.getBlueprintPath() == null ? null : b.getBlueprintPath().endsWith(".blueprint") ? b.getBlueprintPath() : b.getBlueprintPath() + ".blueprint";
            path = HutCatalog.levelPath(bp, r.level);
            pack = b.getStructurePack();
            if (!levelOk(r, b) || path == null || pack == null || !StructurePacks.hasPack(pack)) {
                reject(p, "bad level step " + b.getBuildingLevel() + " -> " + r.level + " or no blueprint path");
                return;
            }
            rot = b.getTileEntity() != null ? b.getTileEntity().getRotationMirror() : b.getRotationMirror();
        }
        busyPlayers.put(p.getUUID(), now);
        busyPositions.put(key(level, r.pos), now);
        int colonyId = colony.getID();
        StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).whenComplete((bp, t) -> level.getServer().execute(() -> {
            boolean started = false;
            try {
                if (bp == null) reject(p, "blueprint not found " + pack + " " + path);
                else started = start(p, level, colonyId, r, pack, path, rot, bp);
            } catch (RuntimeException ex) {
                CityBuilder.LOGGER.error("CityBuilder: instant build failed", ex);
            } finally {
                if (!started) release(p, level, r.pos);
            }
        }));
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
    private static boolean start(ServerPlayer p, ServerLevel level, int colonyId, Request r, String pack, String path, RotationMirror rot, Blueprint bp) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        if (colony == null || !p.isCreative() || p.isRemoved() || !allowed(colony, p, r.mode)) {
            reject(p, "player or colony changed");
            return false;
        }
        IBuilding existing = colony.getServerBuildingManager().getBuilding(r.pos);
        if (r.mode == PLACE ? existing != null : r.mode != DECO && (existing == null || !levelOk(r, existing))) {
            reject(p, "building changed while loading");
            return false;
        }
        bp.setRotationMirror(rot, level);
        BlockPos offset = bp.getPrimaryBlockOffset(), origin = r.pos.subtract(offset);
        BlockPos far = origin.offset(bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1);
        // Every chunk under the footprint must be loaded and owned by this colony (MineColonies' own rule).
        for (int cx = origin.getX() >> 4; cx <= far.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= far.getZ() >> 4; cz++)
                if (!level.hasChunk(cx, cz) || ColonyUtils.getOwningColony(level.getChunk(cx, cz)) != colonyId) {
                    reject(p, "footprint leaves the colony or isn't loaded at chunk " + cx + "," + cz);
                    return false;
                }
        List<String> chunks = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int cx = origin.getX() >> 4; cx <= far.getX() >> 4; cx++)
            for (int cz = origin.getZ() >> 4; cz <= far.getZ() >> 4; cz++) chunks.add(level.dimension().location() + "|" + cx + "," + cz);
        for (String c : chunks)
            if (busy(busyChunks, c, now)) {
                reject(p, "another paste is running over chunk " + c);
                return false;
            }
        BlockState anchor = bp.getBlockState(offset);
        if (r.mode == PLACE) {
            if (!(anchor.getBlock() instanceof AbstractBlockHut<?> hut) || !level.getBlockState(r.pos).canBeReplaced()
                    || !colony.getServerBuildingManager().canPlaceAt(anchor.getBlock(), r.pos, p)
                    || r.level > hut.getBuildingEntry().produceBuilding(r.pos, colony).getMaxBuildingLevel()) {
                reject(p, "cannot place " + path + " at " + r.pos.toShortString());
                return false;
            }
        }
        // Decorations go through here only without a hut anchor (huts use PLACE, which registers the building).
        if (r.mode == DECO && anchor.getBlock() instanceof AbstractBlockHut<?>) {
            reject(p, "decoration request with a hut anchor: " + path);
            return false;
        }
        if (r.mode == CLEAN) return clean(p, level, colony, bp, origin, offset);
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
                        reject(p, "a claim or protection refused breaking " + world.toShortString());
                        return false;
                    }
                    // Placing into air: one place event per chunk (claims are per chunk or area; not one per block).
                    if (a == PasteRules.Action.PLACE && have.isAir() && !want.isAir() && placeChecked.add(world.getX() >> 4 | (long) (world.getZ() >> 4) << 32)
                            && NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, world), have, p)).isCanceled()) {
                        reject(p, "a claim or protection refused placing at " + world.toShortString());
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
                reject(p, "no building after placing at " + r.pos.toShortString());
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
                release(p, level, r.pos);
                chunks.forEach(busyChunks::remove);
            }
        });
        Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(handler), p));
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
    private static java.util.Set<BlockPos> entityProtected(ServerLevel level, BlockPos origin, BlockPos far) {
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
    private static boolean clean(ServerPlayer p, ServerLevel level, IColony colony, Blueprint bp, BlockPos origin, BlockPos offset) {
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
                        reject(p, "a claim or protection refused breaking " + world.toShortString());
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
