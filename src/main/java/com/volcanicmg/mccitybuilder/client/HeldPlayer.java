package com.volcanicmg.mccitybuilder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

/**
 * The player's body while the planner camera is out. Vanilla only reads movement input and sends the position for the
 * camera entity: the last input stays applied (opened mid-jump, the player keeps jumping; walking, keeps walking) and
 * the server keeps the last position it got, which mid-air reads as flying (kicked after 4 s). Here the input is zeroed
 * and the (falling, landing) position is sent the way vanilla's position reminder does.
 */
final class HeldPlayer {
    private static double x, y, z;
    private static boolean ground;
    private static int reminder;

    private HeldPlayer() {
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        // Only the planner's camera (spectating a mob or another mod's camera is vanilla's business).
        if (p == null || !Planner.active() || mc.getCameraEntity() == p) {
            reminder = 20;
            return;
        }
        p.xxa = 0;
        p.zza = 0;
        p.setJumping(false);
        p.setShiftKeyDown(false);
        // A rider's vehicle reads the zeroed input; vanilla sends the vehicle's position itself.
        if (p.isPassenger()) return;
        boolean moved = Mth.lengthSquared(p.getX() - x, p.getY() - y, p.getZ() - z) > Mth.square(2.0E-4);
        if (moved || ground != p.onGround() || ++reminder >= 20) {
            p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY(), p.getZ(), p.onGround()));
            x = p.getX();
            y = p.getY();
            z = p.getZ();
            ground = p.onGround();
            reminder = 0;
        }
    }
}
