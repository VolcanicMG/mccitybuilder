package com.volcanicmg.mccitybuilder.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client wiring: the planner key and the events that drive the overhead view. */
public final class PlannerClient {
    /** Enters / leaves planner view; J by default (free in the pack), rebindable. */
    public static final KeyMapping TOGGLE = new KeyMapping("key.mccitybuilder.toggle", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.mccitybuilder");

    private PlannerClient() {
    }

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(TOGGLE));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            while (TOGGLE.consumeClick()) Planner.guard(Planner::toggle);
            Planner.guard(Planner::tick);
        });
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> Planner.guard(() -> Planner.updateCamera(e.getPartialTick().getGameTimeDeltaPartialTick(true))));
        // In planner view the player's hand and HUD are hidden; our screen draws the UI.
        NeoForge.EVENT_BUS.addListener(RenderHandEvent.class, e -> {
            if (Planner.active()) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(RenderGuiLayerEvent.Pre.class, e -> {
            if (Planner.active()) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, e -> {
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS && Planner.active())
                Planner.guard(() -> Planner.renderWorld(e.getPoseStack(), e.getCamera()));
            // The border last, after every pass and composite, so nothing drawn later can cover it.
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && Planner.active())
                Planner.guard(() -> {
                    var mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
                    mv.pushMatrix();
                    mv.mul(e.getModelViewMatrix());
                    com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
                    try {
                        Planner.renderBorder(e.getPoseStack(), e.getCamera());
                    } finally {
                        mv.popMatrix();
                        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
                    }
                });
        });
        // Terrain fog measures max(horizontal, vertical) distance, so from high up it hid the ground: push it out by the height.
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, e -> {
            if (!Planner.active() || e.getMode() != net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) return;
            float extra = (float) Planner.heightAboveGround();
            e.setNearPlaneDistance(e.getNearPlaneDistance() + extra);
            e.setFarPlaneDistance(e.getFarPlaneDistance() + extra);
            e.setCanceled(true);
            // The border is drawn after the level with a shader that has no fog: it fogs itself with these.
            float[] c = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
            Planner.fog = new float[]{e.getNearPlaneDistance(), e.getFarPlaneDistance(), c[0], c[1], c[2], c[3]};
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            Planner.guard(() -> Planner.exit(null));
            Blueprints.clear();
        });
    }

    /** @return true when {@code key} is held (polled each tick for camera movement) */
    static boolean down(int key) {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key);
    }
}
