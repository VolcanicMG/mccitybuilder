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
     * as the planner sends it.
     */
    public record Decos(int colony, int page, List<PasteRules.Rec> recs) implements CustomPacketPayload {
        public static final Type<Decos> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "decos_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Decos> CODEC = StreamCodec.of((buf, d) -> {
            buf.writeVarInt(d.colony);
            buf.writeVarInt(d.page);
            buf.writeVarInt(d.recs.size());
            for (PasteRules.Rec r : d.recs) {
                buf.writeUtf(r.pack(), 256);
                buf.writeUtf(r.path(), 512);
                buf.writeVarInt(r.x());
                buf.writeVarInt(r.y());
                buf.writeVarInt(r.z());
                buf.writeByte(r.rot());
            }
        }, buf -> {
            int colony = buf.readVarInt(), page = buf.readVarInt(), n = Math.min(buf.readVarInt(), MAX_SENT);
            List<PasteRules.Rec> out = new ArrayList<>();
            for (int i = 0; i < n; i++)
                out.add(new PasteRules.Rec(colony, buf.readUtf(256), buf.readUtf(512), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByte()));
            return new Decos(colony, page, out);
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
        return built.computeIfAbsent(level, k -> new HashMap<>()).computeIfAbsent(colony, c -> build(level, c));
    }

    private static List<Decos> build(ServerLevel level, int colony) {
        List<PasteRules.Rec> recs = new ArrayList<>();
        Map<String, String> packs = null;
        for (PasteRules.Rec r : BuiltRecords.of(level).records(colony)) {
            if (!Crops.PACK.equals(r.pack())) {
                recs.add(r);
                continue;
            }
            if (packs == null) {
                packs = new HashMap<>();
                for (var m : com.ldtteam.structurize.storage.StructurePacks.getPackMetas())
                    packs.put(Crops.hash(m.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)), m.getName());
            }
            String[] src = com.volcanicmg.mccitybuilder.planner.PieceLayout.cropSource(r.path(), packs::get);
            if (src != null) recs.add(new PasteRules.Rec(r.colony(), src[0], src[1], r.x(), r.y(), r.z(), r.rot()));
        }
        // Page 0 always goes (an empty list clears the planner's).
        List<Decos> out = new ArrayList<>();
        for (int from = 0; from == 0 || from < recs.size(); from += MAX_SENT)
            out.add(new Decos(colony, from / MAX_SENT, List.copyOf(recs.subList(from, Math.min(recs.size(), from + MAX_SENT)))));
        return out;
    }
}
