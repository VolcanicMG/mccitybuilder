package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.math.Axis;
import com.volcanicmg.mccitybuilder.CityBuilder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/**
 * A small 3D view of one road / wall piece in the GUI: its blueprint's blocks baked once per piece into vertex
 * buffers (one per render type, the block models the world uses, full-bright) and drawn with inventory lighting,
 * turning slowly; drag turns it, the wheel zooms. Blocks with only an animated / entity model (chests) are left out.
 */
final class PieceView {
    /** Most blocks baked per piece (no bundled piece comes near it). */
    static final int MAX_BLOCKS = 6000;
    private static final Map<String, Map<RenderType, VertexBuffer>> baked = new HashMap<>();
    static float yaw = 30, pitch = 25, zoom = 1;
    static boolean dragging;
    private static long last;
    // Set by a resource reload (maybe off the render thread): the baked UVs are stale, rebake on the next draw.
    static volatile boolean stale;

    private PieceView() {
    }

    /** Frees every baked piece (on the render thread, like everything here). */
    static void clear() {
        for (Map<RenderType, VertexBuffer> m : baked.values()) m.values().forEach(VertexBuffer::close);
        baked.clear();
    }

    /** Bakes the piece's blocks around its own origin, in blueprint coordinates. */
    private static Map<RenderType, VertexBuffer> bake(Blueprint bp) {
        Minecraft mc = Minecraft.getInstance();
        var br = mc.getBlockRenderer();
        Map<RenderType, ByteBufferBuilder> memory = new LinkedHashMap<>();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        PoseStack pose = new PoseStack();
        RandomSource rand = RandomSource.create(42);
        int n = 0;
        try {
        for (int y = 0; y < bp.getSizeY() && n < MAX_BLOCKS; y++)
            for (int z = 0; z < bp.getSizeZ(); z++)
                for (int x = 0; x < bp.getSizeX(); x++) {
                    BlockState st = bp.getBlockState(new BlockPos(x, y, z));
                    if (st.isAir() || st.getRenderShape() != RenderShape.MODEL || st.getBlock() == com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get())
                        continue;
                    BakedModel model = br.getBlockModel(st);
                    int c = mc.getBlockColors().getColor(st, null, null, 0);
                    float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
                    pose.pushPose();
                    pose.translate(x, y, z);
                    rand.setSeed(42);
                    for (RenderType chunk : model.getRenderTypes(st, rand, ModelData.EMPTY)) {
                        RenderType rt = RenderTypeHelper.getEntityRenderType(chunk, false);
                        BufferBuilder bb = builders.computeIfAbsent(rt, k -> {
                            ByteBufferBuilder mem = new ByteBufferBuilder(k.bufferSize());
                            memory.put(k, mem);
                            return new BufferBuilder(mem, k.mode(), k.format());
                        });
                        br.getModelRenderer().renderModel(pose.last(), bb, st, model, r, g, b, 0xF000F0, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, chunk);
                    }
                    pose.popPose();
                    n++;
                }
        } catch (RuntimeException e) {
            memory.values().forEach(ByteBufferBuilder::close);
            throw e;
        }
        Map<RenderType, VertexBuffer> out = new LinkedHashMap<>();
        try {
            for (var en : builders.entrySet()) {
                MeshData mesh = en.getValue().build();
                if (mesh == null) continue;
                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                out.put(en.getKey(), vb);
                vb.bind();
                vb.upload(mesh);
                VertexBuffer.unbind();
            }
        } catch (RuntimeException e) {
            out.values().forEach(VertexBuffer::close);
            throw e;
        } finally {
            memory.values().forEach(ByteBufferBuilder::close);
        }
        return out;
    }

    /**
     * Draws the piece into the box at (x, y), w by h GUI pixels.
     *
     * @param key a stable id for the piece (it is baked once)
     */
    static void render(GuiGraphics g, String key, Blueprint bp, int x, int y, int w, int h) {
        long now = System.currentTimeMillis();
        if (!dragging && last != 0) yaw += Math.min(100, now - last) * 0.02f;
        last = now;
        if (stale) {
            stale = false;
            clear();
        }
        Map<RenderType, VertexBuffer> mesh = baked.get(key);
        if (mesh == null) {
            try {
                mesh = bake(bp);
            } catch (RuntimeException e) {
                CityBuilder.LOGGER.warn("City Builder: can't bake the preview of {}", key, e);
                mesh = Map.of();
            }
            baked.put(key, mesh);
        }
        int sx = bp.getSizeX(), sy = bp.getSizeY(), sz = bp.getSizeZ();
        float scale = Math.min(w, h) / (Math.max(Math.max(sx, sz) * 1.45f, sy * 1.15f)) * zoom;
        g.flush();
        g.enableScissor(x, y, x + w, y + h);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 400);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-sx / 2f, -sy / 2f, -sz / 2f);
        Lighting.setupForEntityInInventory();
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        for (var en : mesh.entrySet()) {
            en.getKey().setupRenderState();
            en.getValue().bind();
            en.getValue().drawWithShader(mv, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
            VertexBuffer.unbind();
            en.getKey().clearRenderState();
        }
        Lighting.setupFor3DItems();
        pose.popPose();
        g.disableScissor();
    }

    /** One piece of a scene: its bake key, blueprint (turned) and where its local 0 sits. */
    record Part(String key, Blueprint bp, int x, int y, int z) {
    }

    /**
     * Draws several pieces at their offsets into the box at (x, y), w by h GUI pixels: the scene is centred on all of
     * them and turns, zooms and is lit like a single piece (each piece is baked once).
     */
    static void renderScene(GuiGraphics g, List<Part> parts, int x, int y, int w, int h) {
        if (parts.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!dragging && last != 0) yaw += Math.min(100, now - last) * 0.01f;
        last = now;
        if (stale) {
            stale = false;
            clear();
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Part p : parts) {
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            z0 = Math.min(z0, p.z());
            x1 = Math.max(x1, p.x() + p.bp().getSizeX());
            y1 = Math.max(y1, p.y() + p.bp().getSizeY());
            z1 = Math.max(z1, p.z() + p.bp().getSizeZ());
        }
        float scale = Math.min(w, h) / (Math.max(Math.max(x1 - x0, z1 - z0) * 1.45f, (y1 - y0) * 1.15f)) * zoom;
        g.flush();
        g.enableScissor(x, y, x + w, y + h);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 400);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-(x0 + x1) / 2f, -(y0 + y1) / 2f, -(z0 + z1) / 2f);
        Lighting.setupForEntityInInventory();
        for (Part p : parts) {
            Map<RenderType, VertexBuffer> mesh = baked.get(p.key());
            if (mesh == null) {
                try {
                    mesh = bake(p.bp());
                } catch (RuntimeException e) {
                    CityBuilder.LOGGER.warn("City Builder: can't bake the preview of {}", p.key(), e);
                    mesh = Map.of();
                }
                baked.put(p.key(), mesh);
            }
            pose.pushPose();
            pose.translate(p.x(), p.y(), p.z());
            Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
            for (var en : mesh.entrySet()) {
                en.getKey().setupRenderState();
                en.getValue().bind();
                en.getValue().drawWithShader(mv, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                VertexBuffer.unbind();
                en.getKey().clearRenderState();
            }
            pose.popPose();
        }
        Lighting.setupFor3DItems();
        pose.popPose();
        g.disableScissor();
    }
}
