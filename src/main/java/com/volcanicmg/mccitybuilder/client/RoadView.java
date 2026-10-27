package com.volcanicmg.mccitybuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.planner.Roads;
import com.volcanicmg.mccitybuilder.server.InstantBuild;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Road mode: click ground points to draw a polyline; the road (width, material) previews over the terrain with steps
 * of at most one block ({@link Roads}); Build sends it to the server part (creative only for now: a survival road
 * would need a generated blueprint and a work order, which the planner doesn't do yet).
 */
final class RoadView {
    /** Road materials, cycled in the road panel. */
    static final List<String> MATERIALS = List.of("minecraft:dirt_path", "minecraft:gravel", "minecraft:cobblestone", "minecraft:stone_bricks",
            "minecraft:smooth_stone", "minecraft:oak_planks", "minecraft:packed_mud");
    static boolean on;
    static final List<int[]> points = new ArrayList<>();
    static int width = 3, material;
    static List<Roads.Column> columns = List.of();
    private static long lastBuild;

    private RoadView() {
    }

    static void toggle() {
        on = !on;
        if (!on) points.clear();
        refresh();
    }

    static void add(BlockPos hit) {
        points.add(new int[]{hit.getX(), hit.getZ()});
        refresh();
    }

    static void undo() {
        if (!points.isEmpty()) points.remove(points.size() - 1);
        refresh();
    }

    static void width(int d) {
        width = Math.max(Roads.MIN_WIDTH, Math.min(Roads.MAX_WIDTH, width + d));
        refresh();
    }

    static void material(int d) {
        material = Math.floorMod(material + d, MATERIALS.size());
    }

    /** Recomputes the columns over the current terrain (the ground from the planner's column data). */
    static void refresh() {
        columns = points.isEmpty() ? List.of() : Roads.columns(points, width, (x, z) -> {
            Integer g = Planner.groundAt(x, z);
            return g == null ? 63 : g - 1;
        });
    }

    /** Preview: an outline over every road column (amber), the clicked points (white). */
    static void renderWorld(PoseStack pose, VertexConsumer lines) {
        if (!on) return;
        for (Roads.Column c : columns)
            LevelRenderer.renderLineBox(pose, lines, c.x() + 0.05, c.y() + 0.9, c.z() + 0.05, c.x() + 0.95, c.y() + 1.02, c.z() + 0.95, 1f, 0.7f, 0.15f, 0.8f);
        for (int[] p : points) {
            Integer g = Planner.groundAt(p[0], p[1]);
            double y = g == null ? 64 : g;
            LevelRenderer.renderLineBox(pose, lines, p[0], y, p[1], p[0] + 1, y + 0.3, p[1] + 1, 1f, 1f, 1f, 1f);
        }
    }

    /** Sends the road (creative, server part). @return a message for the player */
    static Component build() {
        if (!Planner.creative() || !Planner.serverInstant()) return Component.translatable("mccitybuilder.road.creative_only");
        if (columns.isEmpty()) return Component.translatable("mccitybuilder.road.empty");
        if (System.currentTimeMillis() - lastBuild < 1200) return Component.translatable("mccitybuilder.road.wait");
        lastBuild = System.currentTimeMillis();
        List<BlockPos> road = new ArrayList<>();
        for (Roads.Column c : columns) road.add(new BlockPos(c.x(), c.y(), c.z()));
        PacketDistributor.sendToServer(new InstantBuild.RoadRequest(road, MATERIALS.get(material)));
        points.clear();
        refresh();
        return Component.translatable("mccitybuilder.road.sent", road.size());
    }

    static String materialName() {
        var b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(MATERIALS.get(material)));
        return b.getName().getString();
    }

    static boolean active() {
        return on && Minecraft.getInstance().level != null;
    }
}
