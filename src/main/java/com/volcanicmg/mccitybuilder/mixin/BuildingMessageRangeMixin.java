package com.volcanicmg.mccitybuilder.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import com.volcanicmg.mccitybuilder.server.PlannerRange;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MineColonies 1.1.1420+: its building messages' 15-block range check passes for a player with City Builder's planner
 * open over the building's colony ({@link PlannerRange#planning}); everything else (permissions, the message itself)
 * runs as before. Older MineColonies has no such call: {@code require = 0} leaves the mixin unapplied there.
 */
@Mixin(value = AbstractBuildingServerMessage.class, remap = false)
public abstract class BuildingMessageRangeMixin {
    @WrapOperation(method = "onExecute(Lnet/neoforged/neoforge/network/handling/IPayloadContext;Lnet/minecraft/server/level/ServerPlayer;Lcom/minecolonies/api/colony/IColony;)V",
            at = @At(value = "INVOKE", target = "Lcom/minecolonies/core/network/messages/server/AbstractBuildingServerMessage;isInRange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Z"),
            require = 0, remap = false)
    private boolean mccitybuilder$plannerRange(AbstractBuildingServerMessage<?> self, BlockPos from, BlockPos building, Operation<Boolean> original,
                                               @Local(argsOnly = true) ServerPlayer player, @Local(argsOnly = true) IColony colony) {
        return original.call(self, from, building) || PlannerRange.planning(player, colony);
    }
}
