package com.volcanicmg.mccitybuilder.client;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.colony.building.BuildRequestMessage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Survival "build up to level N": a hut placed (or built from the plan) with a level above 1 is placed at level 1 as
 * MineColonies requires, then gets a builder order for its next level each time the last one finished, until it
 * reaches the target ({@link PlanView#targets}, saved with the plan). A target goes when it is reached, the building
 * is gone, an order disappears without the level rising (cancelled), or ours never shows up (refused).
 * <p>
 * MineColonies' BUILD request toggles (an existing order is cancelled), so exactly one goes per level, and only while
 * the player stands in the colony (its view is kept fresh there); it is never sent again for that level.
 */
final class BuildUp {
    /** A building with no hut view yet keeps its target this long; an order of ours that never shows up is given up after this. */
    private static final long GRACE_MS = 60_000, ANSWER_MS = 45_000;
    // No request this soon after a target is set or (re)loaded: the view gets to show an order already out (BUILD toggles).
    private static final long SETTLE_MS = 5_000;
    // Per hut: when its target was set or loaded, the level an order was seen at, and the level / time our request went at.
    private static final Map<BlockPos, Long> since = new HashMap<>(), sentMs = new HashMap<>();
    private static final Map<BlockPos, Integer> orderedAt = new HashMap<>(), sentFor = new HashMap<>();

    private BuildUp() {
    }

    /** Orders up to {@code level} once the hut at {@code at} is built to level 1. */
    static void set(BlockPos at, int level) {
        PlanView.targets.put(at.immutable(), level);
        forgetState(at);
        since.put(at.immutable(), System.currentTimeMillis());
        PlanView.targetsChanged();
    }

    /** @return the level the hut is ordered up to, or 0 */
    static int target(BlockPos at) {
        return PlanView.targets.getOrDefault(at, 0);
    }

    static void cancel(BlockPos at) {
        if (PlanView.targets.remove(at) == null) return;
        forgetState(at);
        PlanView.targetsChanged();
    }

    /** A plan (and its targets) was loaded, or the player logged out: each target starts over with a fresh grace period. */
    static void loaded() {
        since.clear();
        sentMs.clear();
        orderedAt.clear();
        sentFor.clear();
        long now = System.currentTimeMillis();
        for (BlockPos p : PlanView.targets.keySet()) since.put(p, now);
    }

    private static void forgetState(BlockPos at) {
        since.remove(at);
        sentMs.remove(at);
        orderedAt.remove(at);
        sentFor.remove(at);
    }

    /** Each client tick, with or without the planner open: watches each target, ordering the next level when due. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (PlanView.targets.isEmpty() || mc.level == null || mc.player == null || mc.player.isCreative() || !mc.level.dimension().equals(PlanView.colonyDim)) return;
        IColonyView colony = IColonyManager.getInstance().getColonyView(PlanView.colonyId, mc.level.dimension());
        if (colony == null) return;
        // Requests only from inside the colony, where its view is current; outside it is only watched.
        var claim = IColonyManager.getInstance().getClaimData(mc.level.dimension(), new ChunkPos(mc.player.blockPosition()));
        boolean fresh = claim != null && claim.getOwningColony() == PlanView.colonyId;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (var it = PlanView.targets.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            BlockPos at = en.getKey();
            if (step(colony.getClientBuildingManager().getBuilding(at), en.getValue(), at, now, fresh) != null) {
                it.remove();
                forgetState(at);
                changed = true;
            }
        }
        if (changed) PlanView.targetsChanged();
    }

    /** @return why the hut's target goes (non-null), after sending its next order if it is due one */
    private static String step(IBuildingView b, int target, BlockPos at, long now, boolean fresh) {
        if (b == null) return now - since.getOrDefault(at, now) > GRACE_MS ? "gone" : null;
        int level = b.getBuildingLevel();
        if (level >= target || b.isBuildingMaxLevel()) return "reached";
        if (b.hasWorkOrder()) {
            orderedAt.put(at, level);
            return null;
        }
        // An order seen at this level is gone and the level didn't rise: cancelled (the level-1 placement order too).
        Integer o = orderedAt.get(at);
        if (o != null && o == level) return "cancelled";
        // Ours went for this level and no order ever showed up: refused (never sent again: a second one would toggle).
        Integer s = sentFor.get(at);
        if (s != null && s == level) return now - sentMs.getOrDefault(at, now) > ANSWER_MS ? "refused" : null;
        // Level 0: the placement's own build request (Planner.expectBuilding) comes first.
        if (level < 1 || !fresh || now - since.getOrDefault(at, now) < SETTLE_MS) return null;
        sentFor.put(at, level);
        sentMs.put(at, now);
        new BuildRequestMessage(b, BuildRequestMessage.Mode.BUILD, BlockPos.ZERO).sendToServer();
        return null;
    }
}
