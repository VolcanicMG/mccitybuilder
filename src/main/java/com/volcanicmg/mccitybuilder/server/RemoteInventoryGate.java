package com.volcanicmg.mccitybuilder.server;

import com.minecolonies.api.inventory.container.ContainerBuildingInventory;
import com.minecolonies.api.inventory.container.ContainerCitizenInventory;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * The server side of {@link Config#STRICT_INVENTORY_REACH}: MineColonies opens a citizen's, a hut's, a rack's or
 * a grave's inventory for anyone with hut permission wherever they stand (its open message checks no distance, its
 * menus stay valid), so while the setting is on a survival player's one that opens beyond reach is closed again.
 * This applies to MineColonies' own remote windows too (a citizen's inventory from the town hall list, a warehouse rack).
 */
public final class RemoteInventoryGate {
    /** Blocks beyond the player's interaction range that still count as in person (citizens walk: more for them). */
    static final double SLACK = 2.0, CITIZEN_SLACK = 10.0;
    private static boolean warned;

    private RemoteInventoryGate() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Open.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && tooFar(p, e.getContainer())) {
                p.closeContainer();
                // In chat: the planner hides the action bar.
                p.displayClientMessage(Component.translatableWithFallback("mccitybuilder.remote_inventory.closed",
                        "This server keeps hut, rack and citizen inventories to in person in survival: walk up to them (server setting strictInventoryReach)"), false);
            }
        });
    }

    /** @return true for a survival player's citizen / hut inventory opened out of reach while remote access is off */
    static boolean tooFar(ServerPlayer p, AbstractContainerMenu menu) {
        if (p.isCreative() || p.isSpectator() || !Config.STRICT_INVENTORY_REACH.get()) return false;
        if (menu instanceof ContainerCitizenInventory ci) {
            Entity e = ci.getEntity().orElse(null);
            if (e != null) return !p.canInteractWithEntity(e, CITIZEN_SLACK);
        } else if (menu instanceof com.minecolonies.api.inventory.container.ContainerRack r) {
            // A double rack: either half within reach.
            if (r.rack != null) return !p.canInteractWithBlock(r.rack.getBlockPos(), SLACK) && (r.neighborRack == null || !p.canInteractWithBlock(r.neighborRack.getBlockPos(), SLACK));
        } else if (menu instanceof com.minecolonies.api.inventory.container.ContainerGrave g) {
            if (g.grave != null) return !p.canInteractWithBlock(g.grave.getBlockPos(), SLACK);
        } else if (menu instanceof ContainerBuildingInventory) {
            BlockPos at = hutPos(menu);
            if (at != null) return !p.canInteractWithBlock(at, SLACK);
        } else return false;
        if (!warned) {
            warned = true;
            CityBuilder.LOGGER.warn("CityBuilder: can't tell where a {} is; left open (strictInventoryReach can't apply to it)", menu.getClass().getSimpleName());
        }
        return false;
    }

    /** @return the hut block of a building inventory menu (MineColonies keeps its block entity private, no accessor), or null */
    private static BlockPos hutPos(AbstractContainerMenu menu) {
        try {
            var f = ContainerBuildingInventory.class.getDeclaredField("tileEntityColonyBuilding");
            f.setAccessible(true);
            return f.get(menu) instanceof net.minecraft.world.level.block.entity.BlockEntity be ? be.getBlockPos() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
