package com.volcanicmg.mccitybuilder.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.volcanicmg.mccitybuilder.CityBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * MineColonies 1.1.1420+ drops every building message ({@code AbstractBuildingServerMessage}: build requests, builder
 * picks, hut windows' actions) sent from more than 15 blocks away. The planner's camera flies while the player stands
 * still, so the server remembers who has the planner open and over which colony ({@link Open}, sent on open and close),
 * and the range check (mixin {@code BuildingMessageRangeMixin}) lets those players' messages for that colony through.
 * MineColonies' own permission checks still run; only the distance test is skipped. Cleared on close, logout,
 * dimension change and server stop.
 */
public final class PlannerRange {
    // Planner open: player → colony id and the dimension it was opened in.
    private static final Map<UUID, Integer> colonies = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> dims = new HashMap<>();

    private PlannerRange() {
    }

    /** The planner opened over a colony ({@code colony} > 0), or closed (0). */
    public record Open(int colony) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "planner_open_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of((buf, o) -> buf.writeVarInt(o.colony), buf -> new Open(buf.readVarInt()));

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    static void handle(Open o, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        forget(p.getUUID());
        if (o.colony <= 0 || p.isSpectator()) return;
        // Only a member with hut access, who may open the planner there.
        IColony c = IColonyManager.getInstance().getColonyByWorld(o.colony, p.serverLevel());
        if (c == null || !PlanShare.member(c, p)) return;
        colonies.put(p.getUUID(), o.colony);
        dims.put(p.getUUID(), p.serverLevel().dimension());
    }

    /**
     * @return true when the player has the planner open over this colony, in its dimension, stands inside it (the
     * planner closes when they leave) and is still a member with hut access
     */
    public static boolean planning(ServerPlayer p, IColony c) {
        Integer id = colonies.get(p.getUUID());
        return id != null && c != null && id == c.getID() && p.serverLevel().dimension().equals(dims.get(p.getUUID())) && c.getDimension().equals(dims.get(p.getUUID()))
                && !p.isSpectator() && c.isCoordInColony(p.serverLevel(), p.blockPosition()) && PlanShare.member(c, p);
    }

    static void forget(UUID id) {
        colonies.remove(id);
        dims.remove(id);
    }

    static void clear() {
        colonies.clear();
        dims.clear();
    }
}
