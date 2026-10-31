package com.volcanicmg.mccitybuilder.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.planner.PasteRules;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Colony mates see each other's planner: the ghost a player has out ({@link Ghost}: a hut, decoration, move or the
 * road / wall segment being drawn) and their plan ({@link Shared}: entries and unbuilt segments). A client sends its
 * own (owner and name blank: the server fills in the sender); the server checks it (member with hut access, sizes,
 * paths in the pack, positions near the colony, a rate limit), keeps plans per colony and player in the dimension's
 * saved data (so they stay after logout), and passes both on to the colony's other members in that dimension. A
 * planner opening ({@link DecoSync.Query}) gets the colony's current ones.
 */
public final class PlanShare {
    /** Ghost kinds: none (cleared), a hut, a decoration, a building / decoration being moved, a segment being drawn. */
    public static final int NONE = 0, HUT = 1, DECO = 2, MOVE = 3, LINE = 4;
    public static final int MAX_ENTRIES = 256, MAX_LINES = 256;
    /** Per player: a ghost at most this often (the client sends 5 a second), a plan at most this often (1 a second). */
    static final long GHOST_MS = 150, PLAN_MS = 500, ALL_MS = 250;
    private static final UUID NOBODY = new UUID(0, 0);
    // Live ghosts by owner with the dimension they are in (never saved), and the per-player rate limits.
    private record Live(Ghost ghost, ServerLevel level) {
    }

    private static final Map<UUID, Live> ghosts = new HashMap<>();
    private static final Map<UUID, Long> lastGhost = new HashMap<>(), lastPlan = new HashMap<>(), lastAll = new HashMap<>();
    // What came in too soon, newest only, applied when its window opens (the latest state is never lost); and the
    // colony a planner that opened is owed the plans and ghosts of.
    private static final Map<UUID, Ghost> pendingGhost = new HashMap<>();
    private static final Map<UUID, Shared> pendingPlan = new HashMap<>();
    private static final Map<UUID, Integer> pendingAll = new HashMap<>();
    private static Consumer<Ghost> ghostHandler = g -> {
    };
    private static Consumer<Shared> planHandler = s -> {
    };

    private PlanShare() {
    }

    /**
     * A player's ghost. {@code path}: the blueprint ({@link #LINE}: "road" or "wall"); {@code pos}: its anchor (a
     * segment's start); {@code to}: a segment's end (else unused); {@code rot}: RotationMirror ordinal.
     */
    public record Ghost(int colony, UUID owner, String name, int kind, String pack, String path, int level, BlockPos pos, int rot, BlockPos to)
            implements CustomPacketPayload {
        public static final Type<Ghost> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "ghost_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ghost> CODEC = StreamCodec.of((buf, g) -> {
            buf.writeVarInt(g.colony);
            buf.writeUUID(g.owner);
            buf.writeUtf(g.name, 64);
            buf.writeByte(g.kind);
            buf.writeUtf(g.pack, Plan.MAX_NAME);
            buf.writeUtf(g.path, Plan.MAX_PATH);
            buf.writeVarInt(g.level);
            buf.writeBlockPos(g.pos);
            buf.writeByte(g.rot);
            buf.writeBlockPos(g.to);
        }, buf -> new Ghost(buf.readVarInt(), buf.readUUID(), buf.readUtf(64), buf.readByte(), buf.readUtf(Plan.MAX_NAME), buf.readUtf(Plan.MAX_PATH),
                buf.readVarInt(), buf.readBlockPos(), buf.readByte(), buf.readBlockPos()));

        /** @return a client's ghost (owner and name filled in by the server) */
        public static Ghost of(int colony, int kind, String pack, String path, int level, BlockPos pos, int rot, BlockPos to) {
            return new Ghost(colony, NOBODY, "", kind, pack, path, level, pos, rot, to);
        }

        public static Ghost none(int colony) {
            return of(colony, NONE, "", "", 0, BlockPos.ZERO, 0, BlockPos.ZERO);
        }

        @Override
        public Type<Ghost> type() {
            return TYPE;
        }
    }

    /** A player's plan: its entries and planned segments (none of either: the plan is gone). */
    public record Shared(int colony, UUID owner, String name, List<Plan.Entry> entries, List<Plan.Line> lines) implements CustomPacketPayload {
        public static final Type<Shared> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CityBuilder.MODID, "plan_" + InstantBuild.PROTOCOL));
        public static final StreamCodec<RegistryFriendlyByteBuf, Shared> CODEC = StreamCodec.of((buf, s) -> {
            buf.writeVarInt(s.colony);
            buf.writeUUID(s.owner);
            buf.writeUtf(s.name, 64);
            // Never more than the decoder takes (it refuses the whole payload).
            List<Plan.Entry> entries = s.entries.subList(0, Math.min(s.entries.size(), MAX_ENTRIES));
            List<Plan.Line> lines = s.lines.subList(0, Math.min(s.lines.size(), MAX_LINES));
            buf.writeVarInt(entries.size());
            for (Plan.Entry e : entries) {
                buf.writeUtf(e.isHut() ? e.hut() : "", Plan.MAX_NAME);
                buf.writeUtf(e.pack(), Plan.MAX_NAME);
                buf.writeUtf(e.path(), Plan.MAX_PATH);
                for (int v : new int[]{e.level(), e.x(), e.y(), e.z(), e.rot(), e.minX(), e.minY(), e.minZ(), e.maxX(), e.maxY(), e.maxZ()}) buf.writeVarInt(v);
            }
            buf.writeVarInt(lines.size());
            for (Plan.Line l : lines) {
                buf.writeUtf(l.tool(), 16);
                buf.writeUtf(l.pack(), Plan.MAX_NAME);
                buf.writeUtf(l.family(), Plan.MAX_NAME);
                for (int v : new int[]{l.x0(), l.z0(), l.x1(), l.z1(), l.level(), l.variant(), l.height(), l.dy(), l.base()}) buf.writeVarInt(v);
                buf.writeBoolean(l.towers());
                buf.writeBoolean(l.gate());
                buf.writeBoolean(l.flip());
                buf.writeBoolean(l.fill());
            }
        }, buf -> {
            int colony = buf.readVarInt();
            UUID owner = buf.readUUID();
            String name = buf.readUtf(64);
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_ENTRIES) throw new DecoderException("too many plan entries: " + n);
            List<Plan.Entry> es = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String hut = buf.readUtf(Plan.MAX_NAME), pack = buf.readUtf(Plan.MAX_NAME), path = buf.readUtf(Plan.MAX_PATH);
                int[] v = new int[11];
                for (int k = 0; k < v.length; k++) v[k] = buf.readVarInt();
                es.add(new Plan.Entry(hut.isEmpty() ? null : hut, pack, path, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10]));
            }
            n = buf.readVarInt();
            if (n < 0 || n > MAX_LINES) throw new DecoderException("too many plan lines: " + n);
            List<Plan.Line> ls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String tool = buf.readUtf(16), pack = buf.readUtf(Plan.MAX_NAME), family = buf.readUtf(Plan.MAX_NAME);
                int[] v = new int[9];
                for (int k = 0; k < v.length; k++) v[k] = buf.readVarInt();
                boolean towers = buf.readBoolean(), gate = buf.readBoolean(), flip = buf.readBoolean(), fill = buf.readBoolean();
                ls.add(new Plan.Line(tool, v[0], v[1], v[2], v[3], pack, family, v[4], v[5], towers, gate, flip, v[6], v[7], v[8], false, fill));
            }
            return new Shared(colony, owner, name, es, ls);
        });

        @Override
        public Type<Shared> type() {
            return TYPE;
        }
    }

    /** The client's receivers (set at client start-up; this class stays free of client imports). */
    public static void handlers(Consumer<Ghost> ghost, Consumer<Shared> plan) {
        ghostHandler = ghost;
        planHandler = plan;
    }

    static void clear() {
        ghosts.clear();
        lastGhost.clear();
        lastPlan.clear();
        lastAll.clear();
        pendingGhost.clear();
        pendingPlan.clear();
        pendingAll.clear();
    }

    /** Logout or another dimension: the player's ghost goes for everyone where it was (their plan stays). */
    static void forget(ServerPlayer p) {
        lastGhost.remove(p.getUUID());
        lastPlan.remove(p.getUUID());
        lastAll.remove(p.getUUID());
        pendingGhost.remove(p.getUUID());
        pendingAll.remove(p.getUUID());
        // A plan that came in too soon is still kept.
        Shared s = pendingPlan.remove(p.getUUID());
        if (s != null) applyPlan(p, s);
        clearGhost(p);
    }

    /** @return true for a colony member with hut access (who may open the planner there) */
    private static boolean member(IColony c, ServerPlayer p) {
        return c.getPermissions().isColonyMember(p) && c.getPermissions().hasPermission(p, Action.ACCESS_HUTS);
    }

    /** @return true while an offline or online player is still listed in the colony with hut access */
    private static boolean member(IColony c, UUID id) {
        var rank = c.getPermissions().getRank(id);
        return rank != null && c.getPermissions().getPlayers().containsKey(id) && c.getPermissions().hasPermission(rank, Action.ACCESS_HUTS);
    }

    /** @return true when it came in too soon after the last one (the time is kept otherwise) */
    private static boolean tooSoon(Map<UUID, Long> last, UUID id, long ms) {
        long now = System.currentTimeMillis();
        Long t = last.get(id);
        if (t != null && now - t < ms) return true;
        last.put(id, now);
        return false;
    }

    /** End of a server tick: what waited for its rate window goes now. */
    static void tick(net.minecraft.server.MinecraftServer server) {
        if (pendingGhost.isEmpty() && pendingPlan.isEmpty() && pendingAll.isEmpty()) return;
        for (UUID id : new ArrayList<>(pendingGhost.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) pendingGhost.remove(id);
            else if (!tooSoon(lastGhost, id, GHOST_MS)) applyGhost(p, pendingGhost.remove(id));
        }
        for (UUID id : new ArrayList<>(pendingPlan.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) pendingPlan.remove(id);
            else if (!tooSoon(lastPlan, id, PLAN_MS)) applyPlan(p, pendingPlan.remove(id));
        }
        for (UUID id : new ArrayList<>(pendingAll.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) pendingAll.remove(id);
            else if (!tooSoon(lastAll, id, ALL_MS)) sendAll(p, pendingAll.remove(id));
        }
    }

    static void handleGhost(Ghost g, IPayloadContext ctx) {
        if (ctx.flow().isClientbound()) {
            ghostHandler.accept(g);
            return;
        }
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        // A clear goes at once (only to where a ghost was out, so repeating it costs nothing) and drops one waiting.
        if (g.kind == NONE) {
            pendingGhost.remove(p.getUUID());
            clearGhost(p);
        } else if (tooSoon(lastGhost, p.getUUID(), GHOST_MS)) pendingGhost.put(p.getUUID(), g);
        else applyGhost(p, g);
    }

    /** The player's ghost goes: its colony's members where it was are told. */
    private static void clearGhost(ServerPlayer p) {
        Live prev = ghosts.remove(p.getUUID());
        if (prev == null) return;
        Ghost g = prev.ghost;
        IColony c = IColonyManager.getInstance().getColonyByWorld(g.colony, prev.level);
        if (c != null) relay(prev.level, c, p, new Ghost(g.colony, p.getUUID(), g.name, NONE, "", "", 0, BlockPos.ZERO, 0, BlockPos.ZERO));
    }

    private static void applyGhost(ServerPlayer p, Ghost g) {
        ServerLevel level = p.serverLevel();
        IColony c = IColonyManager.getInstance().getColonyByWorld(g.colony, level);
        if (c == null || !member(c, p) || !ghostOk(g, c, level)) return;
        Ghost out = new Ghost(g.colony, p.getUUID(), p.getGameProfile().getName(), g.kind, g.pack, g.path.replace('\\', '/'), g.level, g.pos, g.rot, g.to);
        Live prev = ghosts.get(p.getUUID());
        // Out in another colony or dimension now: the old one's members drop it.
        if (prev != null && (prev.ghost.colony != g.colony || prev.level != level)) clearGhost(p);
        ghosts.put(p.getUUID(), new Live(out, level));
        relay(level, c, p, out);
    }

    /** @return true for a ghost that may go to others: a known kind, near the colony, a safe blueprint of a trusted pack */
    private static boolean ghostOk(Ghost g, IColony c, ServerLevel level) {
        int cx = c.getCenter().getX(), cz = c.getCenter().getZ(), r = InstantBuild.reach();
        if (g.kind < HUT || g.kind > LINE || g.rot < 0 || g.rot > 7 || g.level < 0 || g.level > 64 || !Plan.near(g.pos.getX(), g.pos.getZ(), cx, cz, r)
                || g.pos.getY() < level.getMinBuildHeight() || g.pos.getY() > level.getMaxBuildHeight() || !Plan.plain(g.pack, Plan.MAX_NAME)) return false;
        if (g.kind == LINE)
            return ("road".equals(g.path) || "wall".equals(g.path)) && com.ldtteam.structurize.storage.StructurePacks.hasPack(g.pack)
                    && Plan.near(g.to.getX(), g.to.getZ(), cx, cz, r) && Math.abs(g.to.getX() - g.pos.getX()) + Math.abs(g.to.getZ() - g.pos.getZ()) <= Plan.MAX_LINE;
        String path = g.path.replace('\\', '/');
        return Plan.plain(path, Plan.MAX_PATH) && PasteRules.badPath(path, 0) == null && InstantBuild.badPack(g.pack, path) == null;
    }

    static void handleShared(Shared s, IPayloadContext ctx) {
        if (ctx.flow().isClientbound()) {
            planHandler.accept(s);
            return;
        }
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        if (tooSoon(lastPlan, p.getUUID(), PLAN_MS)) pendingPlan.put(p.getUUID(), s);
        else {
            pendingPlan.remove(p.getUUID());
            applyPlan(p, s);
        }
    }

    private static void applyPlan(ServerPlayer p, Shared s) {
        ServerLevel level = p.serverLevel();
        IColony c = IColonyManager.getInstance().getColonyByWorld(s.colony, level);
        if (c == null || !member(c, p)) return;
        int cx = c.getCenter().getX(), cz = c.getCenter().getZ(), r = InstantBuild.reach();
        // What doesn't pass is left out, the rest kept (a plan can hold an entry of a pack since removed).
        List<Plan.Entry> es = new ArrayList<>();
        for (Plan.Entry e : s.entries) {
            String path = e.path().replace('\\', '/');
            if (Plan.shareable(e, cx, cz, r, level.getMinBuildHeight(), level.getMaxBuildHeight()) && InstantBuild.badPack(e.pack(), path) == null)
                es.add(e.with(path, e.level(), e.rot(), e.box()));
        }
        List<Plan.Line> ls = new ArrayList<>();
        for (Plan.Line l : s.lines)
            if (Plan.shareable(l, cx, cz, r) && com.ldtteam.structurize.storage.StructurePacks.hasPack(l.pack())) ls.add(l);
        Shared out = new Shared(s.colony, p.getUUID(), p.getGameProfile().getName(), List.copyOf(es), List.copyOf(ls));
        Store.of(level).put(out);
        relay(level, c, p, out);
    }

    /**
     * A planner opened ({@link DecoSync.Query}, rate limited or not): the colony's plans and live ghosts go to the
     * player within {@link #ALL_MS} (the client cleared its copy when it asked, so this is never dropped).
     */
    static void requestAll(ServerPlayer p, int colony) {
        if (tooSoon(lastAll, p.getUUID(), ALL_MS)) pendingAll.put(p.getUUID(), colony);
        else {
            pendingAll.remove(p.getUUID());
            sendAll(p, colony);
        }
    }

    /** The colony's plans and live ghosts in the player's dimension, but their own; plans of players no longer in it are dropped. */
    private static void sendAll(ServerPlayer p, int colony) {
        ServerLevel level = p.serverLevel();
        IColony c = IColonyManager.getInstance().getColonyByWorld(colony, level);
        if (c == null || !member(c, p) || !p.connection.hasChannel(Shared.TYPE)) return;
        Store store = Store.of(level);
        for (Shared s : store.plans(colony)) {
            if (!member(c, s.owner)) store.put(new Shared(colony, s.owner, s.name, List.of(), List.of()));
            else if (!s.owner.equals(p.getUUID())) PacketDistributor.sendToPlayer(p, s);
        }
        for (Live l : ghosts.values())
            if (l.level == level && l.ghost.colony == colony && !l.ghost.owner.equals(p.getUUID())) PacketDistributor.sendToPlayer(p, l.ghost);
    }

    /** Sends to every other member of the colony in that dimension that has the channel. */
    private static void relay(ServerLevel level, IColony c, ServerPlayer from, CustomPacketPayload msg) {
        for (ServerPlayer p : level.players())
            if (p != from && p.connection.hasChannel(msg.type()) && member(c, p)) PacketDistributor.sendToPlayer(p, msg);
    }

    /** The dimension's shared plans, per colony and owner, saved as plan text ({@link Plan#write}). */
    static final class Store extends SavedData {
        private static final String NAME = "mccitybuilder_plans";
        private final Map<Integer, Map<UUID, Shared>> byColony = new HashMap<>();

        static Store of(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new Factory<>(Store::new, Store::load, null), NAME);
        }

        private static Store load(CompoundTag tag, HolderLookup.Provider regs) {
            Store s = new Store();
            for (Tag t : tag.getList("plans", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                if (!c.hasUUID("owner")) continue;
                String text = c.getString("plan");
                // Re-checked and capped as when it came in (saved data from an older build may hold anything).
                List<Plan.Entry> es = Plan.read(text).stream().filter(Plan::wellFormed).limit(MAX_ENTRIES).toList();
                List<Plan.Line> ls = Plan.readLines(text).stream().filter(Plan::wellFormed).limit(MAX_LINES).toList();
                String name = c.getString("name");
                if (!es.isEmpty() || !ls.isEmpty())
                    s.byColony.computeIfAbsent(c.getInt("colony"), k -> new HashMap<>()).put(c.getUUID("owner"),
                            new Shared(c.getInt("colony"), c.getUUID("owner"), name.length() > 64 ? name.substring(0, 64) : name, es, ls));
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider regs) {
            ListTag l = new ListTag();
            byColony.values().forEach(m -> m.values().forEach(s -> {
                CompoundTag c = new CompoundTag();
                c.putInt("colony", s.colony);
                c.putUUID("owner", s.owner);
                c.putString("name", s.name);
                c.putString("plan", Plan.write(s.entries, s.lines));
                l.add(c);
            }));
            tag.put("plans", l);
            return tag;
        }

        /** Keeps the owner's plan for the colony; an empty one removes it. */
        void put(Shared s) {
            Map<UUID, Shared> m = byColony.computeIfAbsent(s.colony, k -> new HashMap<>());
            if (s.entries.isEmpty() && s.lines.isEmpty()) m.remove(s.owner);
            else m.put(s.owner, s);
            setDirty();
        }

        List<Shared> plans(int colony) {
            return new ArrayList<>(byColony.getOrDefault(colony, Map.of()).values());
        }
    }
}
