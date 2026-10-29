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

    /** Frees one baked piece, if it is baked. */
    static void forget(String key) {
        Map<RenderType, VertexBuffer> m = baked.remove(key);
        if (m != null) m.values().forEach(VertexBuffer::close);
    }

    /** A block source for {@link #bake}: the state at (x, y, z) inside its box. */
    interface Blocks {
        BlockState at(int x, int y, int z);
    }

    /**
     * Bakes the piece's blocks around its own origin, in blueprint coordinates. Blocks whose model is drawn from their
     * block entity's data (Domum Ornamentum's materially textured walls, posts, slabs...) get that data from the
     * blueprint, or they bake to nothing.
     */
    private static Map<RenderType, VertexBuffer> bake(Blueprint bp) {
        BlockState[] connected = connected(bp);
        int sx = bp.getSizeX(), sz = bp.getSizeZ();
        return bake(sx, bp.getSizeY(), sz, (x, y, z) -> connected[(y * sz + z) * sx + x], MAX_BLOCKS, (x, y, z) -> {
            try {
                var be = bp.getBlockEntity(new BlockPos(x, y, z));
                return be == null ? ModelData.EMPTY : be.getModelData();
            } catch (RuntimeException e) {
                // Block entity data that doesn't load: the block's plain model.
                return ModelData.EMPTY;
            }
        });
    }

    /**
     * @return the blueprint's blocks as the world shows them once placed: each shape-updated against its neighbours in
     * the blueprint (walls, fences, panes, stairs connect; a blueprint stores them as they were scanned or rotated,
     * often unconnected, which bakes bare posts), indexed (y * sz + z) * sx + x
     */
    static BlockState[] connected(Blueprint bp) {
        int sx = bp.getSizeX(), sy = bp.getSizeY(), sz = bp.getSizeZ();
        BlockState[] out = new BlockState[sx * sy * sz];
        com.ldtteam.structurize.client.fakelevel.BlueprintBlockAccess level =
                Minecraft.getInstance().level == null ? null : new com.ldtteam.structurize.client.fakelevel.BlueprintBlockAccess(bp);
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = bp.getBlockState(p);
                    if (level != null && !st.isAir())
                        try {
                            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                                BlockPos n = p.relative(d);
                                st = st.updateShape(d, level.getBlockState(n), level, p, n);
                            }
                        } catch (RuntimeException e) {
                            // A block whose update needs more of a world than a blueprint has: as stored.
                            st = bp.getBlockState(p);
                        }
                    out[(y * sz + z) * sx + x] = st;
                }
        return out;
    }

    /** A block's model data for {@link #bake} (its block entity's, else empty). */
    private interface Data {
        ModelData at(int x, int y, int z);
    }

    /** @return the face shading the world gives a face this way (up full, down half, north / south 0.8, east / west 0.6) */
    private static float shade(net.minecraft.core.Direction d) {
        return switch (d) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            default -> 0.6f;
        };
    }

    /**
     * Bakes blocks of a box: each face shaded the way the world shades it, faces against a neighbouring opaque block
     * left out (they never show).
     */
    private static Map<RenderType, VertexBuffer> bake(int sx, int sy, int sz, Blocks blocks, int max, Data data) {
        Minecraft mc = Minecraft.getInstance();
        var br = mc.getBlockRenderer();
        Map<RenderType, ByteBufferBuilder> memory = new LinkedHashMap<>();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        PoseStack pose = new PoseStack();
        RandomSource rand = RandomSource.create(42);
        int n = 0;
        try {
        for (int y = 0; y < sy && n < max; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    BlockState st = blocks.at(x, y, z);
                    if (st == null) continue;
                    if (st.isAir() || st.getRenderShape() != RenderShape.MODEL || st.getBlock() == com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get())
                        continue;
                    BakedModel model = br.getBlockModel(st);
                    ModelData md = data == null ? ModelData.EMPTY : data.at(x, y, z);
                    int c = mc.getBlockColors().getColor(st, null, null, 0);
                    float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
                    pose.pushPose();
                    pose.translate(x, y, z);
                    rand.setSeed(42);
                    for (RenderType chunk : model.getRenderTypes(st, rand, md)) {
                        // The block render types: lit by the lightmap and the baked face shading, like blocks in the world (the entity
                        // types' directional lights darkened everything); translucent stays an entity type (its world target isn't drawn here).
                        RenderType rt = chunk == RenderType.translucent() ? RenderTypeHelper.getEntityRenderType(chunk, false) : chunk;
                        BufferBuilder bb = builders.computeIfAbsent(rt, k -> {
                            ByteBufferBuilder mem = new ByteBufferBuilder(k.bufferSize());
                            memory.put(k, mem);
                            return new BufferBuilder(mem, k.mode(), k.format());
                        });
                        for (net.minecraft.core.Direction d : FACES) {
                            if (d != null) {
                                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                                BlockState nb = nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz ? null : blocks.at(nx, ny, nz);
                                if (nb != null && nb.canOcclude() && nb.getRenderShape() == RenderShape.MODEL) continue;
                            }
                            rand.setSeed(42);
                            for (var q : model.getQuads(st, d, rand, md, chunk)) {
                                float f = q.isShade() ? shade(q.getDirection()) : 1f;
                                float tr = q.isTinted() ? r : 1f, tg = q.isTinted() ? g : 1f, tb = q.isTinted() ? b : 1f;
                                bb.putBulkData(pose.last(), q, tr * f, tg * f, tb * f, 1f, 0xF000F0, OverlayTexture.NO_OVERLAY);
                            }
                        }
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
    private static final net.minecraft.core.Direction[] FACES = {null, net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST};

    /** Bakes a box of blocks under {@code key} once (a help scene's plate); null states are empty. */
    static void bakeBlocks(String key, int sx, int sy, int sz, Blocks blocks) {
        if (baked.containsKey(key)) return;
        try {
            baked.put(key, bake(sx, sy, sz, blocks, 60000, null));
        } catch (RuntimeException e) {
            CityBuilder.LOGGER.warn("City Builder: can't bake {}", key, e);
            baked.put(key, Map.of());
        }
    }

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
            var sh = RenderSystem.getShader();
            if (sh != null && sh.CHUNK_OFFSET != null) sh.CHUNK_OFFSET.set(0f, 0f, 0f);
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
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Part p : parts) {
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            z0 = Math.min(z0, p.z());
            x1 = Math.max(x1, p.x() + p.bp().getSizeX());
            y1 = Math.max(y1, p.y() + p.bp().getSizeY());
            z1 = Math.max(z1, p.z() + p.bp().getSizeZ());
        }
        renderScene(g, parts, new float[]{x0, y0, z0, x1, y1, z1}, null, null, null, x, y, w, h);
    }

    /**
     * {@link #renderScene}, framed on fixed bounds {x0, y0, z0, x1, y1, z1} (so it holds still while pieces come and
     * go), with {@code extra} drawn in scene space after the pieces (a plate, outline boxes).
     *
     * @return scene space to GUI pixels (callouts project through it)
     */
    static Matrix4f renderScene(GuiGraphics g, List<Part> parts, float[] bounds, java.util.function.BiConsumer<PoseStack, net.minecraft.client.renderer.MultiBufferSource> extra,
                                float[] lift, float[] grow, int x, int y, int w, int h) {
        long now = System.currentTimeMillis();
        if (!dragging && last != 0) yaw += Math.min(100, now - last) * 0.01f;
        last = now;
        if (stale) {
            stale = false;
            clear();
        }
        float x0 = bounds[0], y0 = bounds[1], z0 = bounds[2], x1 = bounds[3], y1 = bounds[4], z1 = bounds[5];
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
        Matrix4f toGui = new Matrix4f(pose.last().pose());
        Lighting.setupForFlatItems();
        for (int pi = 0; pi < parts.size(); pi++) {
            Part p = parts.get(pi);
            Map<RenderType, VertexBuffer> mesh = baked.get(p.key());
            if (mesh == null && p.bp() == null) continue;
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
            pose.translate(p.x(), p.y() + (lift == null ? 0 : lift[pi]), p.z());
            float k = grow == null ? 1 : grow[pi];
            if (k != 1 && p.bp() != null) {
                // Grows from its own centre (a piece popping in).
                float cx = p.bp().getSizeX() / 2f, cz = p.bp().getSizeZ() / 2f;
                pose.translate(cx, 0, cz);
                pose.scale(k, k, k);
                pose.translate(-cx, 0, -cz);
            }
            Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
            for (var en : mesh.entrySet()) {
                en.getKey().setupRenderState();
                // Block shaders offset by the last drawn chunk otherwise.
                var sh = RenderSystem.getShader();
                if (sh != null && sh.CHUNK_OFFSET != null) sh.CHUNK_OFFSET.set(0f, 0f, 0f);
                en.getValue().bind();
                en.getValue().drawWithShader(mv, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                VertexBuffer.unbind();
                en.getKey().clearRenderState();
            }
            pose.popPose();
        }
        Lighting.setupFor3DItems();
        if (extra != null) {
            extra.accept(pose, g.bufferSource());
            g.flush();
        }
        pose.popPose();
        g.disableScissor();
        return toGui;
    }
}
