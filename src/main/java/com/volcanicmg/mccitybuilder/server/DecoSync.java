package com.volcanicmg.mccitybuilder.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.PasteRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The decorations and road / wall pieces City Builder placed in a colony ({@link BuiltRecords}), for the planner to
 * select them and lay new segments out against them: a planner asks when it opens ({@link Query}) and from then on
 * gets the colony's list again after changes ({@link Decos}, in pages, at most every {@link #MIN_RESEND_MS} per
 * colony, built once and sent to every watcher). Only for players with hut access there; the server re-checks every
 * action taken on them anyway.
 * <p>
 * ponytail: always the whole list (about 45 bytes a record); deltas (a page of adds / removes since a version) when
 * colonies get past tens of thousands of records.
 */
public final class DecoSync {
    /** Records per {@link Decos} page. */
    static final int MAX_SENT = 1024;
    /** A colony's list goes out again at most this often (a running piece batch changes it every few ticks). */
    static final long MIN_RESEND_MS = 2000;
    // Colonies whose records changed and weren't sent yet, per dimension.
    private static final Map<ServerLevel, java.util.Set<Integer>> dirty = new HashMap<>();
    // Per dimension and colony: its pages as last built (dropped on a change), and when they last went out.
    private static final Map<ServerLevel, Map<Integer, List<Decos>>> built = new HashMap<>();
    private static final Map<ServerLevel, Map<Integer, Long>> lastSent = new HashMap<>();
    private static final Map<UUID, Integer> watching = new HashMap<>();
    private static final Map<UUID, Long> lastQuery = new HashMap<>();
    private static Consumer<Decos> handler = d -> {
    };

    private DecoSync() {
    }

    /** Client → server: send me this colony's decoration records (and their changes from now on). */
    public record Query(int colony) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "deco_query_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of((buf, q) -> buf.writeVarInt(q.colony), buf -> new Query(buf.readVarInt()));

        @Override
        public Type<Query> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: a page of a colony's records (colony field of each record unused); page 0 starts the list
     * again. A cropped piece comes as its source pack and {@link com.volcanicmg.mccitybuilder.planner.PieceLayout#cropPath},
     * as the planner sends it. {@code owners}: who placed each record (same order; null = unknown), sent as a table.
     */
    public record Decos(int colony, int page, List<PasteRules.Rec> recs, List<UUID> owners) implements CustomPacketPayload {
        public static final Type<Decos> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "decos_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Decos> CODEC = StreamCodec.of((buf, d) -> {
            buf.writeVarInt(d.colony);
            buf.writeVarInt(d.page);
            // Owners as a table (a colony has few placers), then an index per record (0 = unknown).
            List<UUID> table = new ArrayList<>(new java.util.LinkedHashSet<>(d.owners.stream().filter(java.util.Objects::nonNull).toList()));
            buf.writeVarInt(table.size());
            for (UUID u : table) buf.writeUUID(u);
            buf.writeVarInt(d.recs.size());
            for (int i = 0; i < d.recs.size(); i++) {
                PasteRules.Rec r = d.recs.get(i);
                UUID o = i < d.owners.size() ? d.owners.get(i) : null;
                buf.writeVarInt(o == null ? 0 : table.indexOf(o) + 1);
                buf.writeUtf(r.pack(), 256);
                buf.writeUtf(r.path(), 512);
                buf.writeVarInt(r.x());
                buf.writeVarInt(r.y());
                buf.writeVarInt(r.z());
                buf.writeByte(r.rot());
            }
        }, buf -> {
            int colony = buf.readVarInt(), page = buf.readVarInt(), t = Math.min(buf.readVarInt(), MAX_SENT);
            List<UUID> table = new ArrayList<>();
            for (int i = 0; i < t; i++) table.add(buf.readUUID());
            int n = Math.min(buf.readVarInt(), MAX_SENT);
            List<PasteRules.Rec> out = new ArrayList<>();
            List<UUID> owners = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int o = buf.readVarInt();
                owners.add(o > 0 && o <= table.size() ? table.get(o - 1) : null);
                out.add(new PasteRules.Rec(colony, buf.readUtf(256), buf.readUtf(512), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByte()));
            }
            return new Decos(colony, page, out, owners);
        });

        @Override
        public Type<Decos> type() {
            return TYPE;
        }
    }

    /** The client's receiver (set at client start-up; this class stays free of client imports). */
    public static void handler(Consumer<Decos> h) {
        handler = h;
    }

    static void accept(Decos d) {
        handler.accept(d);
    }

    /** Server stopped: nothing kept for its levels. */
    static void clear() {
        dirty.clear();
        built.clear();
        lastSent.clear();
        watching.clear();
        lastQuery.clear();
    }

    static void forget(UUID id) {
        watching.remove(id);
        lastQuery.remove(id);
    }

    static void handleQuery(Query q, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        Long last = lastQuery.get(p.getUUID());
        if (last != null && now - last < InstantBuild.MIN_INTERVAL_MS) return;
        lastQuery.put(p.getUUID(), now);
        IColony colony = IColonyManager.getInstance().getColonyByWorld(q.colony, p.serverLevel());
        if (colony == null || !colony.getPermissions().hasPermission(p, Action.ACCESS_HUTS)) {
            watching.remove(p.getUUID());
            return;
        }
        watching.put(p.getUUID(), q.colony);
        send(p, p.serverLevel(), q.colony);
    }

    /** A colony's records changed: its watchers in that dimension get the new list at the end of a tick. */
    static void changed(ServerLevel level, int colony) {
        dirty.computeIfAbsent(level, k -> new java.util.HashSet<>()).add(colony);
        Map<Integer, List<Decos>> b = built.get(level);
        if (b != null) b.remove(colony);
    }

    /** End of a server tick: changed colonies not sent in the last {@link #MIN_RESEND_MS} go to their watchers. */
    static void flush() {
        if (dirty.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var e : dirty.entrySet()) {
            ServerLevel level = e.getKey();
            Map<Integer, Long> sent = lastSent.computeIfAbsent(level, k -> new HashMap<>());
            for (var it = e.getValue().iterator(); it.hasNext(); ) {
                int colony = it.next();
                Long last = sent.get(colony);
                if (last != null && now - last < MIN_RESEND_MS) continue;
                it.remove();
                sent.put(colony, now);
                IColony c = IColonyManager.getInstance().getColonyByWorld(colony, level);
                if (c == null) continue;
                for (ServerPlayer p : level.players())
                    if (Integer.valueOf(colony).equals(watching.get(p.getUUID())) && c.getPermissions().hasPermission(p, Action.ACCESS_HUTS)) send(p, level, colony);
            }
        }
        dirty.values().removeIf(java.util.Set::isEmpty);
    }

    private static void send(ServerPlayer p, ServerLevel level, int colony) {
        if (!p.connection.hasChannel(Decos.TYPE)) return;
        for (Decos d : pages(level, colony)) PacketDistributor.sendToPlayer(p, d);
    }

    /** @return the colony's record pages as last built, built now if it changed since (the same objects for every watcher) */
    private static List<Decos> pages(ServerLevel level, int colony) {
        prune(level, colony);
        return built.computeIfAbsent(level, k -> new HashMap<>()).computeIfAbsent(colony, c -> build(level, c));
    }

    private static List<Decos> build(ServerLevel level, int colony) {
        List<PasteRules.Rec> recs = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        BuiltRecords br = BuiltRecords.of(level);
        Map<String, String> packs = null;
        for (PasteRules.Rec r : br.records(colony)) {
            UUID owner = br.owner(colony, r.pack(), r.path(), new net.minecraft.core.BlockPos(r.x(), r.y(), r.z()), r.rot());
            if (!Crops.PACK.equals(r.pack())) {
                recs.add(r);
                owners.add(owner);
                continue;
            }
            if (packs == null) {
                packs = new HashMap<>();
                for (var m : com.ldtteam.structurize.storage.StructurePacks.getPackMetas())
                    packs.put(Crops.hash(m.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)), m.getName());
            }
            String[] src = com.volcanicmg.mccitybuilder.planner.PieceLayout.cropSource(r.path(), packs::get);
            if (src != null) {
                recs.add(new PasteRules.Rec(r.colony(), src[0], src[1], r.x(), r.y(), r.z(), r.rot()));
                owners.add(owner);
            }
        }
        // Page 0 always goes (an empty list clears the planner's).
        List<Decos> out = new ArrayList<>();
        for (int from = 0; from == 0 || from < recs.size(); from += MAX_SENT)
            out.add(new Decos(colony, from / MAX_SENT, List.copyOf(recs.subList(from, Math.min(recs.size(), from + MAX_SENT))),
                    java.util.Collections.unmodifiableList(new ArrayList<>(owners.subList(from, Math.min(owners.size(), from + MAX_SENT))))));
        return out;
    }

    // ---- stale records: the world no longer holds what they stand for (cleared, undone, rebuilt by hand) ----

    /** A colony's records are checked against the world at most this often, this many per check (round robin). */
    static final long PRUNE_EVERY_MS = 30_000;
    static final int PRUNE_BUDGET = 256;
    // Per "dimension|colony": {last check, where the next one starts}.
    private static final Map<String, long[]> pruned = new HashMap<>();
    // Per "pack|path|rot": cells of the turned blueprint to look for, relative to the anchor, and their blocks (empty: can't tell).
    private static final Map<String, List<Object[]>> samples = new HashMap<>();

    /**
     * Drops up to {@link #PRUNE_BUDGET} records of the colony (round robin, at most every {@link #PRUNE_EVERY_MS})
     * of which no sampled block stands any more; never one in an unloaded chunk, with an open work order there
     * (ordered, not built yet), or whose blueprint can't be read.
     */
    private static void prune(ServerLevel level, int colony) {
        long[] st = pruned.computeIfAbsent(level.dimension().location() + "|" + colony, k -> new long[2]);
        long now = System.currentTimeMillis();
        if (now - st[0] < PRUNE_EVERY_MS) return;
        st[0] = now;
        BuiltRecords br = BuiltRecords.of(level);
        List<PasteRules.Rec> all = br.records(colony);
        IColony c = IColonyManager.getInstance().getColonyByWorld(colony, level);
        if (all.isEmpty() || c == null) return;
        java.util.Set<net.minecraft.core.BlockPos> ordered = new java.util.HashSet<>();
        for (var wo : c.getWorkManager().getWorkOrders().values()) ordered.add(wo.getLocation());
        int n = Math.min(PRUNE_BUDGET, all.size()), from = (int) (st[1] % all.size());
        st[1] = from + n;
        List<PasteRules.Rec> gone = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PasteRules.Rec r = all.get((from + i) % all.size());
            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(r.x(), r.y(), r.z());
            if (!ordered.contains(at) && Boolean.FALSE.equals(standing(level, r, at))) gone.add(r);
        }
        for (PasteRules.Rec r : gone) br.remove(colony, r.pack(), r.path(), new net.minecraft.core.BlockPos(r.x(), r.y(), r.z()), r.rot());
        if (!gone.isEmpty()) CityBuilder.LOGGER.info("CityBuilder: dropped {} records of colony {} whose blocks are gone", gone.size(), colony);
    }

    /** @return true when a sampled block of the record's blueprint stands where it put it, false when none does, null when it can't tell */
    private static Boolean standing(ServerLevel level, PasteRules.Rec r, net.minecraft.core.BlockPos at) {
        if (!level.isLoaded(at)) return null;
        List<Object[]> cells = samples.computeIfAbsent(r.pack() + "|" + r.path() + "|" + r.rot(), k -> sample(level, r));
        if (cells.isEmpty()) return null;
        for (Object[] cell : cells) {
            net.minecraft.core.BlockPos w = at.offset((net.minecraft.core.BlockPos) cell[0]);
            if (!level.isLoaded(w)) return null;
            if (level.getBlockState(w).getBlock() == cell[1]) return true;
        }
        return false;
    }

    /** @return up to 8 solid cells of the record's blueprint, turned as recorded, spread over it (empty when it can't be read) */
    private static List<Object[]> sample(ServerLevel level, PasteRules.Rec r) {
        try {
            if (Crops.PACK.equals(r.pack())) Crops.ensurePack();
            var bp0 = com.ldtteam.structurize.storage.StructurePacks.getBlueprint(r.pack(), r.path(), true, level.registryAccess());
            if (bp0 == null) return List.of();
            // ponytail: one blueprint read per distinct piece and turn on the server thread, cached for the run; load ahead if packs get huge.
            var bp = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(bp0).copy(),
                    level.registryAccess());
            var rms = com.ldtteam.structurize.api.RotationMirror.values();
            bp.setRotationMirror(rms[Math.floorMod(r.rot(), rms.length)], level);
            net.minecraft.core.BlockPos o = bp.getPrimaryBlockOffset();
            List<Object[]> solid = new ArrayList<>();
            var pal = bp.getPalette();
            short[][][] st = bp.getStructure();
            for (int y = 0; y < bp.getSizeY(); y++)
                for (int z = 0; z < bp.getSizeZ(); z++)
                    for (int x = 0; x < bp.getSizeX(); x++) {
                        var b = pal[st[y][z][x]].getBlock();
                        if (pal[st[y][z][x]].isAir() || b == com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()
                                || b == com.ldtteam.structurize.blocks.ModBlocks.blockSolidSubstitution.get() || b == com.ldtteam.structurize.blocks.ModBlocks.blockFluidSubstitution.get())
                            continue;
                        solid.add(new Object[]{new net.minecraft.core.BlockPos(x - o.getX(), y - o.getY(), z - o.getZ()), b});
                    }
            List<Object[]> out = new ArrayList<>();
            for (int i = 0; i < 8 && i < solid.size(); i++) out.add(solid.get(i * solid.size() / Math.min(8, solid.size())));
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }
}
