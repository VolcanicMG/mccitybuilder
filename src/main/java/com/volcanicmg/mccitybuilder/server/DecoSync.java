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
 * The decorations City Builder placed in a colony ({@link BuiltRecords}), for the planner to select them: a planner
 * asks when it opens ({@link Query}) and from then on gets the colony's list again after every change ({@link Decos}).
 * Only for players with hut access there; the server re-checks every action taken on them anyway.
 */
public final class DecoSync {
    /** Records sent per colony at most. */
    static final int MAX_SENT = 1024;
    private static final Map<UUID, Integer> watching = new HashMap<>();
    private static final Map<UUID, Long> lastQuery = new HashMap<>();
    private static Consumer<Decos> handler = d -> {
    };

    private DecoSync() {
    }

    /** Client → server: send me this colony's decoration records (and their changes from now on). */
    public record Query(int colony) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "deco_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of((buf, q) -> buf.writeVarInt(q.colony), buf -> new Query(buf.readVarInt()));

        @Override
        public Type<Query> type() {
            return TYPE;
        }
    }

    /** Server → client: a colony's decoration records (colony field of each record unused). */
    public record Decos(int colony, List<PasteRules.Rec> recs) implements CustomPacketPayload {
        public static final Type<Decos> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "decos"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Decos> CODEC = StreamCodec.of((buf, d) -> {
            buf.writeVarInt(d.colony);
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
            int colony = buf.readVarInt(), n = Math.min(buf.readVarInt(), MAX_SENT);
            List<PasteRules.Rec> out = new ArrayList<>();
            for (int i = 0; i < n; i++)
                out.add(new PasteRules.Rec(colony, buf.readUtf(256), buf.readUtf(512), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByte()));
            return new Decos(colony, out);
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

    /** A colony's decoration records changed: its watchers in that dimension get the new list. */
    static void changed(ServerLevel level, int colony) {
        IColony c = IColonyManager.getInstance().getColonyByWorld(colony, level);
        if (c == null) return;
        for (ServerPlayer p : level.players())
            if (Integer.valueOf(colony).equals(watching.get(p.getUUID())) && c.getPermissions().hasPermission(p, Action.ACCESS_HUTS)) send(p, level, colony);
    }

    private static void send(ServerPlayer p, ServerLevel level, int colony) {
        if (!p.connection.hasChannel(Decos.TYPE)) return;
        List<PasteRules.Rec> recs = BuiltRecords.of(level).decorations(colony);
        // ponytail: a colony past MAX_SENT decorations sends only the first ones; page it if colonies get that big.
        if (recs.size() > MAX_SENT) recs = recs.subList(0, MAX_SENT);
        PacketDistributor.sendToPlayer(p, new Decos(colony, recs));
    }
}
