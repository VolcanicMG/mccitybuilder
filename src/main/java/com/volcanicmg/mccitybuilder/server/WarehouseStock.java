package com.volcanicmg.mccitybuilder.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.Materials;
import io.netty.handler.codec.DecoderException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What the colony's warehouses hold, for the planner's Materials panel: a player with hut access to the colony asks
 * ({@link Query}, at most one per {@link #MIN_GAP_MS}), the server sums the racks of every warehouse (loaded ones
 * only; builders' own stock is not counted) and answers item to count ({@link Stock}).
 */
public final class WarehouseStock {
    /** Per player: a request at most this often (an earlier one is dropped, the client asks again). */
    static final long MIN_GAP_MS = 2000;
    /** Items in one reply (a decoder refuses more). */
    static final int MAX_ITEMS = 4096;
    private static final Map<UUID, Long> last = new HashMap<>();
    private static Consumer<Stock> handler = s -> {
    };

    private WarehouseStock() {
    }

    /** Client to server: the stock of this colony's warehouses, please. */
    public record Query(int colony) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "stock_query_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of((buf, q) -> buf.writeVarInt(q.colony), buf -> new Query(buf.readVarInt()));

        @Override
        public Type<Query> type() {
            return TYPE;
        }
    }

    /** Server to client: item id to count over the colony's warehouse racks. */
    public record Stock(int colony, Map<String, Integer> items) implements CustomPacketPayload {
        public static final Type<Stock> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "stock_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stock> CODEC = StreamCodec.of((buf, s) -> {
            buf.writeVarInt(s.colony);
            int n = Math.min(s.items.size(), MAX_ITEMS);
            buf.writeVarInt(n);
            int i = 0;
            for (Map.Entry<String, Integer> e : s.items.entrySet()) {
                if (i++ >= n) break;
                buf.writeUtf(e.getKey(), 128);
                buf.writeVarInt(e.getValue());
            }
        }, buf -> {
            int colony = buf.readVarInt(), n = buf.readVarInt();
            if (n < 0 || n > MAX_ITEMS) throw new DecoderException("too many stock items: " + n);
            Map<String, Integer> items = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) items.put(buf.readUtf(128), buf.readVarInt());
            return new Stock(colony, items);
        });

        @Override
        public Type<Stock> type() {
            return TYPE;
        }
    }

    /** The client's receiver (set at client start-up; this class stays free of client imports). */
    public static void handler(Consumer<Stock> h) {
        handler = h;
    }

    static void accept(Stock s) {
        handler.accept(s);
    }

    static void forget(UUID id) {
        last.remove(id);
    }

    static void clear() {
        last.clear();
    }

    static void handle(Query q, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        if (!Materials.allowed(last.get(p.getUUID()), now, MIN_GAP_MS)) return;
        last.put(p.getUUID(), now);
        IColony colony = IColonyManager.getInstance().getColonyByWorld(q.colony, p.serverLevel());
        if (colony == null || !colony.getPermissions().hasPermission(p, Action.ACCESS_HUTS) || !p.connection.hasChannel(Stock.TYPE)) return;
        PacketDistributor.sendToPlayer(p, new Stock(q.colony, count(colony, p.serverLevel())));
    }

    /** @return item id to count over the rack inventories of the colony's warehouses (racks in unloaded chunks are skipped) */
    static Map<String, Integer> count(IColony colony, net.minecraft.server.level.ServerLevel level) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (IWareHouse w : colony.getServerBuildingManager().getWareHouses())
            for (BlockPos pos : w.getContainers()) {
                if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof AbstractTileEntityRack rack)) continue;
                IItemHandler inv = rack.getInventory();
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (!st.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
                }
            }
        return out;
    }
}
