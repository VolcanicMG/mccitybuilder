package com.volcanicmg.mccitybuilder.server;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.Food;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.QueueOrder;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Creative "Builders (free)": a creative player marks the sites of the builder work orders they make ({@link Fund});
 * the first order made at a site after its mark is funded, and every 2 s the builder working a funded order gets its
 * open material and tool requests filled the way MineColonies' creative "fulfil" does it
 * ({@code IRequestManager.overruleRequest}, then the requested items into the builder citizen's inventory). The server
 * decides everything: the colony at the site, the sender's creative mode and permission (when marking and again before
 * every fill), and the items (the request's own). Marks are kept in the overworld's saved data until their order is
 * gone, the colony is gone, or no order came in time ({@link QueueOrder#fundExpired}).
 */
public final class FreeMaterials {
    public static final int MAX_SITES = InstantBuild.MAX_BATCH;
    // Per player: marks kept, messages per second.
    static final int MAX_PER_PLAYER = 256, MAX_MESSAGES = 20;
    private static final int EVERY_TICKS = 40;
    private static final Map<UUID, long[]> window = new HashMap<>();

    private FreeMaterials() {
    }

    /**
     * Sites (hut positions, decoration anchors) whose next work order should get free materials.
     *
     * @param window the order comes from a MineColonies window the player may close without ordering (a long wait for
     *               it); false: from a request already on its way (a short one)
     */
    public record Fund(List<BlockPos> sites, boolean window) implements CustomPacketPayload {
        public static final Type<Fund> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "fund_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Fund> CODEC = StreamCodec.of((buf, f) -> {
            List<BlockPos> s = f.sites.subList(0, Math.min(f.sites.size(), MAX_SITES));
            buf.writeVarInt(s.size());
            for (BlockPos p : s) buf.writeBlockPos(p);
            buf.writeBoolean(f.window);
        }, buf -> {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_SITES) throw new DecoderException("too many fund sites: " + n);
            List<BlockPos> s = new ArrayList<>();
            for (int i = 0; i < n; i++) s.add(buf.readBlockPos());
            return new Fund(s, buf.readBoolean());
        });

        @Override
        public Type<Fund> type() {
            return TYPE;
        }
    }

    static void handle(Fund f, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p) || !p.isCreative()) return;
        long now = System.currentTimeMillis();
        long[] w = window.computeIfAbsent(p.getUUID(), k -> new long[2]);
        if (now - w[0] >= 1000) {
            w[0] = now;
            w[1] = 0;
        }
        if (++w[1] > MAX_MESSAGES) {
            CityBuilder.LOGGER.info("CityBuilder: fund marks from {} dropped: rate limit", p.getGameProfile().getName());
            return;
        }
        ServerLevel level = p.serverLevel();
        Store s = Store.of(p.server);
        for (BlockPos pos : f.sites) {
            IColony c = level.isLoaded(pos) ? IColonyManager.getInstance().getColonyByPosFromWorld(level, pos) : null;
            if (c != null && InstantBuild.allowed(c, p, InstantBuild.UPGRADE)) s.mark(level.dimension().location().toString(), c, pos.immutable(), p, f.window, now);
        }
    }

    static void forget(UUID id) {
        window.remove(id);
    }

    static void clear() {
        window.clear();
    }

    /** Every {@link #EVERY_TICKS} ticks: binds marks to their orders, drops finished ones, fills funded builders' requests. */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % EVERY_TICKS != 0) return;
        Store s = Store.of(server);
        if (s.marks.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, Set<Integer>> taken = new HashMap<>();
        for (Mark m : s.marks) if (m.order != 0) taken.computeIfAbsent(m.dim + "|" + m.colony, k -> new HashSet<>()).add(m.order);
        boolean changed = false;
        for (var it = s.marks.iterator(); it.hasNext(); ) {
            Mark m = it.next();
            // A MineColonies exception must not take the server tick down: logged once per mark, retried next round.
            try {
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(m.dim)));
                IColony c = level == null ? null : IColonyManager.getInstance().getColonyByWorld(m.colony, level);
                if (c == null) {
                    it.remove();
                    changed = true;
                    continue;
                }
                if (m.order == 0) {
                    List<Integer> here = new ArrayList<>();
                    for (IServerWorkOrder o : c.getWorkManager().getWorkOrders().values()) if (m.pos.equals(o.getLocation())) here.add(o.getID());
                    Set<Integer> t = taken.computeIfAbsent(m.dim + "|" + m.colony, k -> new HashSet<>());
                    m.order = QueueOrder.fundedOrder(m.after, here, t);
                    if (m.order == 0) {
                        if (QueueOrder.fundExpired(m.window, now - m.since)) {
                            it.remove();
                            changed = true;
                        }
                        continue;
                    }
                    t.add(m.order);
                    changed = true;
                }
                IServerWorkOrder o = c.getWorkManager().getWorkOrder(m.order);
                // Built or cancelled.
                if (o == null) {
                    it.remove();
                    changed = true;
                    continue;
                }
                // Its maker offline, out of creative or no longer permitted: the order waits like any other.
                ServerPlayer p = server.getPlayerList().getPlayer(m.owner);
                if (p == null || !p.isCreative() || !InstantBuild.allowed(c, p, InstantBuild.UPGRADE) || !o.isClaimed()) continue;
                if (c.getServerBuildingManager().getBuilding(o.getClaimedBy()) instanceof AbstractBuildingStructureBuilder b && b.hasWorkOrder()
                        && b.getWorkOrder().getID() == m.order) fill(c, b);
            } catch (RuntimeException ex) {
                if (!m.warned) CityBuilder.LOGGER.warn("CityBuilder: free materials for the order at {} failed", m.pos.toShortString(), ex);
                m.warned = true;
            }
        }
        if (changed) s.setDirty();
    }

    /**
     * Fills the builder's open item requests as MineColonies' creative "fulfil" button does: the builder's own
     * requests (tools, some stacks) and the hut's material list (MineColonies files most of an order's materials under
     * the building, citizen id -1). Each is overruled with its first display stack at the requested count, then those
     * items go into the first loaded builder citizen's inventory (only once the overrule went through, so a failing
     * one never hands out items twice). The hut's list also holds other requests (its minimum stock, leftovers of
     * earlier orders): from it only items the current order still needs, at most that many
     * ({@code BuildingResourcesModule.getNeededResources()}); minimum-stock requests never.
     */
    private static void fill(IColony c, AbstractBuildingStructureBuilder b) {
        BuildingResourcesModule needs = b.getFirstModuleOccurance(BuildingResourcesModule.class);
        for (ICitizenData citizen : b.getAllAssignedCitizen()) {
            if (citizen.getEntity().isEmpty()) continue;
            for (int owner : new int[]{citizen.getId(), -1})
                for (IRequest<?> r : List.copyOf(b.getOpenRequests(owner))) {
                    if (!(r.getRequest() instanceof IDeliverable d) || d instanceof Food || d instanceof MinimumStack || d.getCount() <= 0) continue;
                    ItemStack shown = r.getDisplayStacks().stream().findFirst().orElse(ItemStack.EMPTY);
                    if (shown.isEmpty() || !d.matches(shown)) continue;
                    int count = owner == -1 ? Math.min(d.getCount(), stillNeeded(needs, shown)) : d.getCount();
                    if (count <= 0) continue;
                    c.getRequestManager().overruleRequest(r.getId(), shown.copyWithCount(count));
                    // What doesn't fit is dropped (creative items); the builder asks again for anything still missing.
                    for (int left = count; left > 0; left -= shown.getMaxStackSize())
                        InventoryUtils.addItemStackToItemHandlerWithResult(citizen.getInventory(), shown.copyWithCount(Math.min(left, shown.getMaxStackSize())));
                }
            // One builder: the hut's list is filled once.
            return;
        }
    }

    /** @return how many of this item the builder's current order still needs to place (0: none, or no module) */
    private static int stillNeeded(BuildingResourcesModule needs, ItemStack s) {
        if (needs == null) return 0;
        for (BuildingBuilderResource n : needs.getNeededResources().values())
            if (ItemStack.isSameItemSameComponents(n.getItemStack(), s)) return n.getAmount();
        return 0;
    }

    /** A funded site: the order made there after {@code after} (bound once seen, 0 before), by {@code owner}. */
    private static final class Mark {
        final String dim;
        final int colony, after;
        final BlockPos pos;
        final UUID owner;
        int order;
        long since;
        // From a MineColonies window (a long wait); warned: its failure was logged (not saved).
        boolean window, warned;

        Mark(String dim, int colony, BlockPos pos, UUID owner, int after, int order, long since, boolean window) {
            this.dim = dim;
            this.colony = colony;
            this.pos = pos;
            this.owner = owner;
            this.after = after;
            this.order = order;
            this.since = since;
            this.window = window;
        }
    }

    /** Every dimension's marks, in the overworld's saved data (orders run on across restarts). */
    static final class Store extends SavedData {
        private static final String NAME = "mccitybuilder_funded";
        final List<Mark> marks = new ArrayList<>();

        static Store of(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(Store::new, Store::load, null), NAME);
        }

        private static Store load(CompoundTag tag, HolderLookup.Provider regs) {
            Store s = new Store();
            for (Tag t : tag.getList("marks", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                if (c.hasUUID("owner") && ResourceLocation.tryParse(c.getString("dim")) != null)
                    s.marks.add(new Mark(c.getString("dim"), c.getInt("colony"), BlockPos.of(c.getLong("pos")), c.getUUID("owner"), c.getInt("after"),
                            c.getInt("order"), c.getLong("since"), c.getBoolean("window")));
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider regs) {
            ListTag l = new ListTag();
            for (Mark m : marks) {
                CompoundTag c = new CompoundTag();
                c.putString("dim", m.dim);
                c.putInt("colony", m.colony);
                c.putLong("pos", m.pos.asLong());
                c.putUUID("owner", m.owner);
                c.putInt("after", m.after);
                c.putInt("order", m.order);
                c.putLong("since", m.since);
                c.putBoolean("window", m.window);
                l.add(c);
            }
            tag.put("marks", l);
            return tag;
        }

        /** Marks the site for the player (a mark still waiting there is renewed), up to {@link #MAX_PER_PLAYER} each. */
        void mark(String dim, IColony c, BlockPos pos, ServerPlayer p, boolean window, long now) {
            int mine = 0;
            for (Mark m : marks) {
                if (!m.owner.equals(p.getUUID())) continue;
                if (m.order == 0 && m.colony == c.getID() && m.dim.equals(dim) && m.pos.equals(pos)) {
                    m.since = now;
                    m.window = window;
                    setDirty();
                    return;
                }
                mine++;
            }
            if (mine >= MAX_PER_PLAYER) {
                CityBuilder.LOGGER.info("CityBuilder: fund mark from {} dropped: {} marks open", p.getGameProfile().getName(), mine);
                return;
            }
            int after = 0;
            for (int id : c.getWorkManager().getWorkOrders().keySet()) after = Math.max(after, id);
            marks.add(new Mark(dim, c.getID(), pos, p.getUUID(), after, 0, now, window));
            setDirty();
        }
    }
}
