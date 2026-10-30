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
 * The server side of {@link Config#SURVIVAL_REMOTE_INVENTORIES}: MineColonies opens a citizen's or a hut's inventory
 * for anyone with hut permission wherever they stand (its open message checks no distance, its menus stay valid), so
 * while the setting is off a survival player's citizen / hut inventory that opens beyond reach is closed again.
 */
public final class RemoteInventoryGate {
    /** Blocks beyond the player's interaction range that still count as in person. */
    static final double SLACK = 2.0;
    private static boolean warned;

    private RemoteInventoryGate() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Open.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p && tooFar(p, e.getContainer())) {
                p.closeContainer();
                // In chat: the planner hides the action bar.
                p.displayClientMessage(Component.translatableWithFallback("mccitybuilder.remote_inventory.closed",
                        "This server keeps hut and citizen inventories to in person in survival: walk up to them"), false);
            }
        });
    }

    /** @return true for a survival player's citizen / hut inventory opened out of reach while remote access is off */
    static boolean tooFar(ServerPlayer p, AbstractContainerMenu menu) {
        if (p.isCreative() || p.isSpectator() || Config.SURVIVAL_REMOTE_INVENTORIES.get()) return false;
        if (menu instanceof ContainerCitizenInventory ci) {
            Entity e = ci.getEntity().orElse(null);
            if (e != null) return !p.canInteractWithEntity(e, SLACK);
        } else if (menu instanceof ContainerBuildingInventory) {
            BlockPos at = hutPos(menu);
            if (at != null) return !p.canInteractWithBlock(at, SLACK);
        } else return false;
        if (!warned) {
            warned = true;
            CityBuilder.LOGGER.debug("CityBuilder: can't tell where a {} is; left open", menu.getClass().getSimpleName());
        }
        return false;
    }

    /** @return the hut block of a building inventory menu (MineColonies keeps its block entity private), or null */
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
