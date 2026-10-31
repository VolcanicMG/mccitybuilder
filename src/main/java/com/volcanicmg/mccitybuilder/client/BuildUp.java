package com.volcanicmg.mccitybuilder.client;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.colony.building.BuildRequestMessage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Survival "build up to level N": a hut placed (or built from the plan) with a level above 1 is placed at level 1 as
 * MineColonies requires, then gets a builder order for its next level each time the last one finished, until it
 * reaches the target ({@link PlanView#targets}, saved with the plan). A target goes when it is reached, the building
 * is gone, an order of ours disappears without the level rising (cancelled), or orders keep being refused.
 */
final class BuildUp {
    /** One order per building at most this often; a building with no hut view yet keeps its target this long. */
    private static final long RESEND_MS = 5000, GRACE_MS = 60_000;
    /** Orders sent without one ever showing up before the target is dropped (the server refuses them). */
    private static final int MAX_TRIES = 3;
    // Per hut: when its target was set or loaded, the level it had an order at, the last request and the unanswered ones.
    private static final Map<BlockPos, Long> since = new HashMap<>(), lastSent = new HashMap<>();
    private static final Map<BlockPos, Integer> orderedAt = new HashMap<>(), tries = new HashMap<>();

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

    /** A plan (and its targets) was loaded: each gets a fresh grace period. */
    static void loaded() {
        since.clear();
        lastSent.clear();
        orderedAt.clear();
        tries.clear();
        long now = System.currentTimeMillis();
        for (BlockPos p : PlanView.targets.keySet()) since.put(p, now);
    }

    private static void forgetState(BlockPos at) {
        since.remove(at);
        lastSent.remove(at);
        orderedAt.remove(at);
        tries.remove(at);
    }

    /** Each client tick, with or without the planner open: the next order for each hut that is due one. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (PlanView.targets.isEmpty() || mc.level == null || mc.player == null || mc.player.isCreative() || !mc.level.dimension().equals(PlanView.colonyDim)) return;
        IColonyView colony = IColonyManager.getInstance().getColonyView(PlanView.colonyId, mc.level.dimension());
        if (colony == null) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (var it = PlanView.targets.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            BlockPos at = en.getKey();
            IBuildingView b = colony.getClientBuildingManager().getBuilding(at);
            String drop = step(b, en.getValue(), at, now);
            if (drop != null) {
                it.remove();
                forgetState(at);
                changed = true;
            }
        }
        if (changed) PlanView.targetsChanged();
    }

    /** @return why the hut's target goes (non-null), after sending its next order if it is due one */
    private static String step(IBuildingView b, int target, BlockPos at, long now) {
        if (b == null) return now - since.getOrDefault(at, now) > GRACE_MS ? "gone" : null;
        int level = b.getBuildingLevel();
        if (level >= target || b.isBuildingMaxLevel()) return "reached";
        if (b.hasWorkOrder()) {
            orderedAt.put(at, level);
            tries.remove(at);
            return null;
        }
        // An order seen at this level is gone and the level didn't rise: cancelled (the level-1 placement order too).
        Integer o = orderedAt.get(at);
        if (o != null && o == level) return "cancelled";
        // Level 0: the placement's own build request (Planner.expectBuilding) comes first.
        if (level < 1 || now - lastSent.getOrDefault(at, 0L) < RESEND_MS) return null;
        if (tries.merge(at, 1, Integer::sum) > MAX_TRIES) return "refused";
        lastSent.put(at, now);
        new BuildRequestMessage(b, BuildRequestMessage.Mode.BUILD, BlockPos.ZERO).sendToServer();
        return null;
    }
}
