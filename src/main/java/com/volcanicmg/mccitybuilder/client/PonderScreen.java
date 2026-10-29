package com.volcanicmg.mccitybuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volcanicmg.mccitybuilder.planner.LineHelp;
import com.volcanicmg.mccitybuilder.planner.LineSnap;
import com.volcanicmg.mccitybuilder.planner.Plan;
import com.volcanicmg.mccitybuilder.planner.Ponder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * "How roads and walls work", Create-Ponder style: topic cards, a stage with the player's own pieces on a block plate
 * (each step's little plan laid out by the planner itself, {@link Ponder}) that animate in, outlines in the planner's
 * colours, a gliding cursor, key caps and callouts, a captioned step and a segmented timeline. It plays by itself
 * (Space pauses, arrows step, up / down change topic); drag turns, scroll zooms, a double-click gives the camera back.
 * Esc / Close goes back to the planner.
 */
final class PonderScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable run) {
    }

    /**
     * One laid-out step: its pieces as scene parts; boxes {x0, y0, z0, x1, y1, z1, colour, part index or -1} (outlines,
     * gaps, air); the segments; the ground kind.
     */
    private record Scene(List<PieceView.Part> parts, List<float[]> boxes, List<Plan.Line> lines, String ground, boolean ok) {
    }

    // The topic last looked at, for the next time the help opens.
    private static int lastTopic;
    private static final float ZOOM = 1.0f;
    private static final int DROP_MS = 450, STAGGER_MS = 70;
    /** Where in a step the cursor clicks, and how fast it glides there (it arrives at 1 / GLIDE). */
    private static final float CLICK_T = 0.5f, GLIDE = 2.2f;

    private final List<Hit> hits = new ArrayList<>();
    private final String pack;
    private int topic, step;
    private long stepStart = System.currentTimeMillis(), lastFrame;
    private boolean paused, sounded;
    private List<Scene> scenes;
    private float[] bounds, cam;
    private int[] view;
    private double dragX, dragY;
    private boolean viewDrag, userView;
    private long lastClick;
    // Where the cursor was last drawn (it glides from there to the next step's point).
    private float[] cursorFrom, cursorAt;
    private final float[] cardView = {PieceView.yaw, PieceView.pitch, PieceView.zoom};

    PonderScreen() {
        super(Component.translatable("mccitybuilder.help.title"));
        String p = PlannerScreen.pack();
        // The player's style when it has road / wall pieces, else Colonial.
        pack = PieceCatalog.familyKeys(p, "wall") != null && !PieceCatalog.familyKeys(p, "wall").isEmpty() ? p : "Colonial";
        topic = Math.min(lastTopic, Ponder.TOPICS.size() - 1);
        PieceView.yaw = 35;
        PieceView.pitch = 32;
        PieceView.zoom = ZOOM;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(Planner.active() ? new PlannerScreen() : null);
    }

    @Override
    public void removed() {
        PieceView.yaw = cardView[0];
        PieceView.pitch = cardView[1];
        PieceView.zoom = cardView[2];
        // The plates and scene pieces are baked once per visit; the scenes' layouts left PieceCatalog's per-layout
        // state behind, so the plan is laid out again.
        PieceView.clear();
        PlanView.relayout();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xB0101010);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        Planner.guard(() -> draw(g, mx, my));
    }

    private Ponder.Topic topic() {
        return Ponder.TOPICS.get(topic);
    }

    private void go(int t, int s) {
        if (t != topic) {
            scenes = null;
            cam = null;
            cursorFrom = null;
            topic = lastTopic = t;
        }
        cursorFrom = cursorAt;
        step = Math.max(0, Math.min(topic().steps().size() - 1, s));
        stepStart = System.currentTimeMillis();
        sounded = false;
    }

    // ---- the scenes: each step's plan laid out with the player's pieces ----

    private String family(String tool) {
        List<String> keys = PieceCatalog.familyKeys(pack, tool);
        String cur = tool.equals("wall") ? LineView.wallFamily : LineView.roadFamily;
        return keys == null || keys.isEmpty() ? null : cur != null && keys.contains(cur) ? cur : keys.get(0);
    }

    /** @return the topic's scenes, laid out (null while pieces load) */
    private List<Scene> scenes() {
        if (scenes != null) return scenes;
        String tool = topic().tool() != null ? topic().tool() : LineView.tool != null ? LineView.tool : "wall";
        String fam = family(tool);
        if (fam == null) return null;
        Plan.Line probe = new Plan.Line(tool, 0, 0, 30, 0, pack, fam, LineView.level, LineView.variant, false, false);
        int[] lens = PieceCatalog.runLengths(probe);
        if (lens.length == 0) return null;
        int longest = 0;
        for (int l : lens) longest = Math.max(longest, l);
        List<Scene> out = new ArrayList<>();
        for (Ponder.Step st : topic().steps()) {
            Scene sc = scene(st, tool, fam, lens, longest);
            if (!sc.ok()) return null;
            out.add(sc);
        }
        // One frame for the whole topic, with a margin of plate.
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Scene sc : out)
            for (float[] bx : sc.boxes()) {
                for (int k = 0; k < 3; k++) b[k] = Math.min(b[k], bx[k]);
                for (int k = 3; k < 6; k++) b[k] = Math.max(b[k], bx[k]);
            }
        if (b[0] == Float.MAX_VALUE) b = new float[]{-4, 0, -4, 4, 4, 4};
        bounds = new float[]{b[0] - 3, b[1], b[2] - 3, b[3] + 3, b[4], b[5] + 3};
        scenes = out;
        return out;
    }

    private Scene scene(Ponder.Step st, String tool, String fam, int[] lens, int longest) {
        List<Ponder.Seg> segs = new ArrayList<>();
        for (String spec : st.lines) segs.add(Ponder.seg(spec, longest));
        Map<Long, Integer> types = new HashMap<>();
        for (var en : st.joints.entrySet()) {
            String[] c = en.getKey().split(" ");
            types.put(Plan.cell(Ponder.coord(c[0], longest), Ponder.coord(c[1], longest)), en.getValue());
        }
        String ground = st.ground;
        PieceCatalog.groundOverride = (x, z) -> Ponder.ground(ground, x, z);
        try {
            // "gap": the far end moves out a cell at a time until some cells stay uncovered (laid out without Fill gaps).
            for (int i = 0; i < segs.size(); i++) {
                Ponder.Seg s = segs.get(i);
                for (int k = 0; s.gap() && k < 2 * longest; k++) {
                    List<Ponder.Seg> plain = new ArrayList<>(segs);
                    plain.set(i, new Ponder.Seg(s.x0(), s.z0(), s.x1(), s.z1(), s.fit(), false, s.height(), s.dy(), true));
                    if (PieceCatalog.layout(lines(plain, tool, fam, ground), types).shortened().get(i) >= 1) break;
                    int dx = Integer.signum(s.x1() - s.x0()), dz = Integer.signum(s.z1() - s.z0());
                    for (int q = 0; q < segs.size(); q++) segs.set(q, moveEnd(segs.get(q), s.x1(), s.z1(), dx, dz));
                    s = segs.get(i);
                }
            }
            List<Plan.Line> lines = lines(segs, tool, fam, ground);
            PieceCatalog.Layout lay = PieceCatalog.layout(lines, types);
            // "~": a far end moves out until the pieces fit exactly (and every end there with it).
            for (int pass = 0; pass < 2; pass++) {
                boolean moved = false;
                for (int i = 0; i < segs.size(); i++) {
                    Ponder.Seg s = segs.get(i);
                    if (!s.fit() || lay.shortened().get(i) == 0) continue;
                    int cells = LineSnap.length(new int[]{s.x0(), s.z0()}, new int[]{s.x1(), s.z1()}) + 1, max = 2 * longest;
                    int d = LineSnap.extension(cells, lay.offs().get(i)[0], lay.offs().get(i)[1], lens, max);
                    if (d <= 0) continue;
                    int dx = Integer.signum(s.x1() - s.x0()) * d, dz = Integer.signum(s.z1() - s.z0()) * d;
                    for (int k = 0; k < segs.size(); k++) segs.set(k, moveEnd(segs.get(k), s.x1(), s.z1(), dx, dz));
                    moved = true;
                }
                if (!moved) break;
                lines = lines(segs, tool, fam, ground);
                lay = PieceCatalog.layout(lines, types);
            }
            List<PieceView.Part> parts = new ArrayList<>();
            List<float[]> boxes = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                int col = colour(st.colours.getOrDefault(i, "green"));
                for (PieceCatalog.Placed p : lay.lines().get(i)) {
                    var bp = PieceCatalog.rotated(pack, p.path(), p.turns());
                    if (bp == null) return new Scene(List.of(), List.of(), List.of(), ground, false);
                    parts.add(new PieceView.Part("ponder|" + pack + "|" + p.path() + "|" + Math.floorMod(p.turns(), 4), bp, p.box()[0], p.y0(), p.box()[1]));
                    int[] b = p.box();
                    // The outline round the blocks the piece really has, from the ground up (its buried rows are under the plate).
                    int[] sb = solid(parts.get(parts.size() - 1).key(), bp);
                    boxes.add(new float[]{b[0] + sb[0], Math.max(p.y0() + sb[1], p.level()), b[1] + sb[2], b[0] + sb[3] + 1, p.y0() + sb[4] + 1, b[1] + sb[5] + 1, col, parts.size() - 1});
                    // Air under a raised piece: amber from the lowest ground up to where it stands.
                    if (p.level() - p.low() >= 2) boxes.add(new float[]{b[0] + 0.1f, p.low(), b[1] + 0.1f, b[2] + 0.9f, p.level(), b[3] + 0.9f, 0xFFFFB31A, parts.size() - 1});
                }
                int[] gp = lay.gaps().get(i);
                if (gp != null && !lay.lines().get(i).isEmpty()) {
                    int gy = lay.lines().get(i).get(0).level();
                    boxes.add(new float[]{gp[0], gy, gp[1], gp[2] + 1, gy + 1.5f, gp[3] + 1, 0xFFFFB31A, -1});
                }
            }
            return new Scene(parts, boxes, lines, ground, lay.complete());
        } finally {
            PieceCatalog.groundOverride = null;
        }
    }

    private static final Map<String, int[]> SOLID = new HashMap<>();

    /** @return the box of a piece's blocks (air and substitution left out) in its blueprint: {x0, y0, z0, x1, y1, z1} */
    private static int[] solid(String key, com.ldtteam.structurize.blueprints.v1.Blueprint bp) {
        return SOLID.computeIfAbsent(key, k -> {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int y = 0; y < bp.getSizeY(); y++)
                for (int z = 0; z < bp.getSizeZ(); z++)
                    for (int x = 0; x < bp.getSizeX(); x++) {
                        BlockState st = bp.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                        if (st.isAir() || st.getBlock() == com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()) continue;
                        b[0] = Math.min(b[0], x);
                        b[1] = Math.min(b[1], y);
                        b[2] = Math.min(b[2], z);
                        b[3] = Math.max(b[3], x);
                        b[4] = Math.max(b[4], y);
                        b[5] = Math.max(b[5], z);
                    }
            return b[0] == Integer.MAX_VALUE ? new int[]{0, 0, 0, bp.getSizeX() - 1, bp.getSizeY() - 1, bp.getSizeZ() - 1} : b;
        });
    }

    private List<Plan.Line> lines(List<Ponder.Seg> segs, String tool, String fam, String ground) {
        // A flat chain's level: the highest plate under its segments.
        int base = Plan.NO_BASE;
        for (Ponder.Seg s : segs)
            if (s.height() == Plan.CHAIN)
                for (int[] c : cellsOf(s)) base = Math.max(base == Plan.NO_BASE ? Integer.MIN_VALUE : base, Ponder.ground(ground, c[0], c[1]));
        List<Plan.Line> out = new ArrayList<>();
        for (Ponder.Seg s : segs)
            out.add(new Plan.Line(tool, s.x0(), s.z0(), s.x1(), s.z1(), pack, fam, LineView.level, LineView.variant, false, false, false, s.height(), s.dy(),
                    s.height() == Plan.CHAIN ? base : Plan.NO_BASE, false, s.fill()));
        return out;
    }

    private static int[][] cellsOf(Ponder.Seg s) {
        int n = Math.max(Math.abs(s.x1() - s.x0()), Math.abs(s.z1() - s.z0())), dx = Integer.signum(s.x1() - s.x0()), dz = Integer.signum(s.z1() - s.z0());
        int[][] out = new int[n + 1][];
        for (int k = 0; k <= n; k++) out[k] = new int[]{s.x0() + dx * k, s.z0() + dz * k};
        return out;
    }

    private static Ponder.Seg moveEnd(Ponder.Seg s, int x, int z, int dx, int dz) {
        if (s.x1() == x && s.z1() == z) return new Ponder.Seg(s.x0(), s.z0(), x + dx, z + dz, s.fit(), s.fill(), s.height(), s.dy(), s.gap());
        if (s.x0() == x && s.z0() == z) return new Ponder.Seg(x + dx, z + dz, s.x1(), s.z1(), s.fit(), s.fill(), s.height(), s.dy(), s.gap());
        return s;
    }

    private static int colour(String c) {
        return switch (c) {
            case "red" -> 0xFFFF4040;
            case "cyan" -> 0xFF40E6FF;
            case "white" -> 0xFFFFFFFF;
            case "grey" -> 0x808C8C8C;
            default -> 0xFF4DFF4D;
        };
    }

    // ---- easing ----

    private static float clamp01(float t) {
        return Math.max(0, Math.min(1, t));
    }

    private static float easeOut(float t) {
        t = clamp01(t);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** Ease out with a slight overshoot (Create's pieces settle like this). */
    private static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.4f, c3 = c1 + 1;
        return 1 + c3 * (t - 1) * (t - 1) * (t - 1) + c1 * (t - 1) * (t - 1);
    }

    private static float easeInOut(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    private static int alpha(int argb, float a) {
        return (Math.round((argb >>> 24) * clamp01(a)) << 24) | argb & 0xFFFFFF;
    }

    // ---- drawing ----

    private void draw(GuiGraphics g, int mx, int my) {
        hits.clear();
        renderBackground(g, mx, my, 0);
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        lastFrame = now;
        int w = Math.min(width - 8, Math.max(420, (int) (width * 0.9f))), h = Math.min(height - 8, Math.max(280, (int) (height * 0.92f)));
        int x = (width - w) / 2, y = (height - h) / 2, p = 6;
        VanillaUi.panel(g, x, y, w, h);
        Ponder.Topic tp = topic();
        int n = tp.steps().size();
        // Title bar: the topic's item, "Help · Topic · step n/N".
        // Its own slot, clear of the text.
        VanillaUi.slot(g, x + p, y + p - 3, 18, 18);
        icon(g, tp.id(), x + p + 1, y + p - 2, 1);
        String head = title.getString() + "  ·  " + Component.translatable("mccitybuilder.ponder." + tp.id() + ".title").getString() + "  ·  "
                + Component.translatable("mccitybuilder.ponder.step", step + 1, n).getString();
        g.drawString(font, head, x + p + 23, y + p + 2, VanillaUi.LABEL, false);
        // Topic cards, left: the topic's item in a slot, its title and a one-line summary, never cut.
        int need = 0;
        for (Ponder.Topic tt : Ponder.TOPICS)
            for (String k : new String[]{".title", ".summary"}) need = Math.max(need, font.width(Component.translatable("mccitybuilder.ponder." + tt.id() + k).getString()));
        int cw = Math.min(need + 34, w / 4), cy = y + p + 16, ch = Math.min(36, (h - 22 - p) / Ponder.TOPICS.size() - 2);
        for (int i = 0; i < Ponder.TOPICS.size(); i++) {
            int by = cy + i * (ch + 2), fi = i;
            boolean on = i == topic, over = mx >= x + p && mx < x + p + cw && my >= by && my < by + ch;
            if (on) VanillaUi.slot(g, x + p, by, cw, ch);
            else VanillaUi.button(g, x + p, by, cw, ch, false, false, over);
            int slotY = by + (ch - 18) / 2;
            VanillaUi.slot(g, x + p + 5, slotY, 18, 18);
            if (on) {
                // The active topic: a white frame round its slot.
                g.fill(x + p + 4, slotY - 1, x + p + 24, slotY, 0xFFFFFFFF);
                g.fill(x + p + 4, slotY + 18, x + p + 24, slotY + 19, 0xFFFFFFFF);
                g.fill(x + p + 4, slotY, x + p + 5, slotY + 18, 0xFFFFFFFF);
                g.fill(x + p + 23, slotY, x + p + 24, slotY + 18, 0xFFFFFFFF);
            }
            icon(g, Ponder.TOPICS.get(i).id(), x + p + 6, slotY + 1, 1);
            String t = Component.translatable("mccitybuilder.ponder." + Ponder.TOPICS.get(i).id() + ".title").getString();
            String sm = Component.translatable("mccitybuilder.ponder." + Ponder.TOPICS.get(i).id() + ".summary").getString();
            int tx = x + p + 29;
            boolean two = ch > 26;
            g.drawString(font, t, tx, two ? by + ch / 2 - 10 : by + (ch - 8) / 2, on ? 0xFFFFFFA0 : VanillaUi.INK, on);
            if (two) g.drawString(font, sm, tx, by + ch / 2 + 1, on ? 0xFFE0E0E0 : 0xFF505050, false);
            hits.add(new Hit(x + p, by, cw, ch, () -> go(fi, 0)));
        }
        // The stage, caption, timeline and controls, right.
        int sx = x + p + cw + 6, sw = x + w - p - sx, sy = y + p + 16, ctrlY = y + h - p - 20, tlY = ctrlY - 12, capH = 34, capY = tlY - 4 - capH;
        int sh = capY - 4 - sy;
        view = new int[]{sx, sy, sw, sh};
        stage(g, sx, sy, sw, sh);
        List<Scene> sc = scenes();
        Ponder.Step st = tp.steps().get(step);
        long el = paused ? Long.MAX_VALUE / 4 : now - stepStart;
        float t = clamp01(el / (float) Ponder.STEP_MS);
        if (sc == null) g.drawString(font, Component.translatable("mccitybuilder.ponder.loading"), sx + 8, sy + 8, 0xFFE0E0E0, false);
        else scene(g, mx, my, sc, st, el, t, dt, sx, sy, sw, sh);
        caption(g, st, el, sx, capY, sw, capH);
        timeline(g, mx, my, t, sx, tlY, sw, n);
        controls(g, mx, my, sx, ctrlY, sw);
        // Auto-play: the next step when this one is done; the last one stays.
        if (!paused && el > Ponder.STEP_MS + 700 && step < n - 1) go(topic, step + 1);
    }

    /** A dark stage: a vertical gradient and a soft vignette. */
    private void stage(GuiGraphics g, int x, int y, int w, int h) {
        VanillaUi.slot(g, x - 1, y - 1, w + 2, h + 2);
        g.fillGradient(x, y, x + w, y + h, 0xFF2E3946, 0xFF101419);
        int v = Math.min(36, Math.min(w, h) / 6);
        g.fillGradient(x, y, x + w, y + v, 0x90000000, 0x00000000);
        g.fillGradient(x, y + h - v, x + w, y + h, 0x00000000, 0x90000000);
        for (int i = 0; i < v; i++) {
            int a = (int) (0x90 * (1 - i / (float) v) * (1 - i / (float) v));
            g.fill(x + i, y, x + i + 1, y + h, a << 24);
            g.fill(x + w - 1 - i, y, x + w - i, y + h, a << 24);
        }
    }

    private void scene(GuiGraphics g, int mx, int my, List<Scene> sc, Ponder.Step st, long el, float t, float dt, int sx, int sy, int sw, int sh) {
        Scene s = sc.get(step), prev = step > 0 ? sc.get(step - 1) : null;
        Set<String> before = new HashSet<>(), now = new HashSet<>();
        if (prev != null) for (PieceView.Part q : prev.parts()) before.add(id(q));
        for (PieceView.Part q : s.parts()) now.add(id(q));
        // The plate under this step (holes where its pieces stand), baked once.
        String plateKey = "ponderplate|" + pack + "|" + topic + "|" + step;
        int[] pb = plate(s, plateKey);
        List<PieceView.Part> parts = new ArrayList<>();
        List<Float> lifts = new ArrayList<>(), grows = new ArrayList<>();
        parts.add(new PieceView.Part(plateKey, null, pb[0], pb[1], pb[2]));
        lifts.add(0f);
        grows.add(1f);
        // New pieces drop in one after another with a little overshoot; the ones going sink out.
        float[] land = new float[s.parts().size()], ghost = new float[s.parts().size()];
        // A click step: until the click its new pieces are the planner's outline, growing behind the cursor; then they drop.
        long wait = st.click && !st.nodes ? (long) (CLICK_T * Ponder.STEP_MS) : 0;
        int fresh0 = 0;
        for (PieceView.Part q : s.parts()) if (!before.contains(id(q))) fresh0++;
        float glide = easeInOut(t * GLIDE);
        int k = 0;
        List<float[]> landed = new ArrayList<>();
        for (int i = 0; i < s.parts().size(); i++) {
            PieceView.Part q = s.parts().get(i);
            boolean fresh = !before.contains(id(q));
            long at = el - wait - k * STAGGER_MS;
            float u = fresh ? clamp01(at / (float) DROP_MS) : 1;
            if (fresh && at <= 0) ghost[i] = glide >= (k + 1) / (float) fresh0 - 0.02f ? 1 : 0;
            if (fresh) k++;
            land[i] = u;
            if (fresh && at <= 0) continue;
            parts.add(q);
            lifts.add(fresh ? 5 * (1 - easeOutBack(u)) : 0f);
            grows.add(fresh ? 0.85f + 0.15f * easeOut(u) : 1f);
            if (fresh && u >= 1 && at - DROP_MS < 350) landed.add(new float[]{i, (at - DROP_MS) / 350f});
        }
        if (prev != null && el < 450)
            for (PieceView.Part q : prev.parts())
                if (!now.contains(id(q))) {
                    parts.add(q);
                    lifts.add(-6 * easeOut(el / 450f));
                    grows.add(1f);
                }
        if (k > 0 && !sounded && el > wait + DROP_MS * 0.8f) {
            sounded = true;
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.STONE_PLACE, 1.0f, 0.25f));
        }
        // The camera eases to this step's subject (the new pieces, else all of them); the player's drag / zoom wins.
        float[] target = userView ? bounds : focus(s, before);
        if (cam == null) cam = target.clone();
        float ease = 1 - (float) Math.exp(-dt / 280f);
        for (int i = 0; i < 6; i++) cam[i] += (target[i] - cam[i]) * ease;
        float[] lf = new float[lifts.size()], gr = new float[grows.size()];
        for (int i = 0; i < lf.length; i++) {
            lf[i] = lifts.get(i);
            gr[i] = grows.get(i);
        }
        float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 260.0);
        Matrix4f m = PieceView.renderScene(g, parts, cam, (pose, buf) -> boxes(pose, buf, s, pb, land, ghost, el, pulse), lf, gr, sx + 1, sy + 1, sw - 2, sh - 2);
        // Overlays in front of the scene (it was drawn deep in z).
        g.pose().pushPose();
        g.pose().translate(0, 0, 900);
        g.enableScissor(sx, sy, sx + sw, sy + sh);
        for (float[] l : landed) poof(g, m, s.parts().get((int) l[0]), l[1]);
        overlays(g, m, s, st, el, t, sx, sy, sw, sh);
        keys(g, st, el, sx, sy);
        hover(g, m, s, mx, my, sx, sy, sw, sh);
        g.disableScissor();
        g.pose().popPose();
    }

    private static String id(PieceView.Part q) {
        return q.key() + "@" + q.x() + "," + q.y() + "," + q.z();
    }

    /** @return the box the camera frames for a step: its new pieces (or all of them) with some plate around */
    private float[] focus(Scene s, Set<String> before) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (boolean fresh : new boolean[]{true, false}) {
            for (float[] bx : s.boxes()) {
                int pi = (int) bx[7];
                if (fresh && (pi < 0 || before.contains(id(s.parts().get(pi))))) continue;
                any = true;
                for (int i = 0; i < 3; i++) b[i] = Math.min(b[i], bx[i]);
                for (int i = 3; i < 6; i++) b[i] = Math.max(b[i], bx[i]);
            }
            // Mostly new pieces: frame them with the rest a little in view; none new: everything.
            if (any) break;
        }
        if (!any) return bounds.clone();
        float m = 4;
        return new float[]{b[0] - m, b[1] - 1, b[2] - m, b[3] + m, b[4] + 1, b[5] + m};
    }

    /**
     * Bakes (once) the step's plate: grass on top of dirt and stone, a few blocks deep under the topic's frame, open
     * only where this step's pieces have blocks, from each column's lowest one up (no pit beside a piece).
     *
     * @return {x, y, z} of its corner
     */
    private int[] plate(Scene s, String key) {
        int x0 = (int) Math.floor(bounds[0]), z0 = (int) Math.floor(bounds[2]), x1 = (int) Math.ceil(bounds[3]), z1 = (int) Math.ceil(bounds[5]);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = x0; x < x1; x++)
            for (int z = z0; z < z1; z++) {
                lo = Math.min(lo, Ponder.ground(s.ground(), x, z));
                hi = Math.max(hi, Ponder.ground(s.ground(), x, z));
            }
        int y0 = lo - 4, sy = hi - y0;
        // Per column: the lowest block a piece really has there (air and substitution leave the ground as it is).
        Map<Long, Integer> under = new HashMap<>();
        for (PieceView.Part q : s.parts())
            for (int x = 0; x < q.bp().getSizeX(); x++)
                for (int z = 0; z < q.bp().getSizeZ(); z++)
                    for (int y = 0; y < q.bp().getSizeY(); y++) {
                        BlockState st = q.bp().getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                        if (st.isAir() || st.getBlock() == com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()) continue;
                        under.merge(Plan.cell(q.x() + x, q.z() + z), q.y() + y, Math::min);
                        break;
                    }
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        PieceView.bakeBlocks(key, x1 - x0, sy, z1 - z0, (lx, ly, lz) -> {
            int wx = x0 + lx, wy = y0 + ly, wz = z0 + lz, top = Ponder.ground(s.ground(), wx, wz) - 1;
            Integer piece = under.get(Plan.cell(wx, wz));
            if (wy > top || piece != null && wy >= piece) return null;
            return wy == top ? grass : wy >= top - 2 ? dirt : stone;
        });
        return new int[]{x0, y0, z0, x1, y0 + sy, z1};
    }

    /** The plate's soft shadow, then the boxes that teach something: gap and air (amber), red, cyan, white, grey (fading in as their piece lands, pulsing). */
    private void boxes(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buf, Scene s, int[] pb, float[] land, float[] ghost, long el, float pulse) {
        VertexConsumer q = buf.getBuffer(RenderType.gui());
        Matrix4f m = pose.last().pose();
        for (int r = 3; r >= 1; r--) {
            float e = r * 0.6f, yy = pb[1] - 0.02f * r;
            int c = 0x30000000;
            // Both windings: the quad shows from above and below.
            q.addVertex(m, pb[0] - e, yy, pb[2] - e).setColor(c);
            q.addVertex(m, pb[0] - e, yy, pb[5] + e).setColor(c);
            q.addVertex(m, pb[3] + e, yy, pb[5] + e).setColor(c);
            q.addVertex(m, pb[3] + e, yy, pb[2] - e).setColor(c);
            q.addVertex(m, pb[3] + e, yy, pb[2] - e).setColor(c);
            q.addVertex(m, pb[3] + e, yy, pb[5] + e).setColor(c);
            q.addVertex(m, pb[0] - e, yy, pb[5] + e).setColor(c);
            q.addVertex(m, pb[0] - e, yy, pb[2] - e).setColor(c);
        }
        VertexConsumer l = buf.getBuffer(RenderType.lines());
        for (float[] b : s.boxes()) {
            int c = (int) b[6], pi = (int) b[7];
            if (pi >= 0 && pi < ghost.length && ghost[pi] > 0) {
                LevelRenderer.renderLineBox(pose, l, b[0] + 0.05, b[1] + 0.02, b[2] + 0.05, b[3] - 0.05, b[4] - 0.02, b[5] - 0.05,
                        (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, pulse);
                continue;
            }
            if (pi >= 0 && pi < land.length && land[pi] <= 0) continue;
            float a = clamp01((el - 250) / 300f);
            if (pi >= 0 && pi < land.length) a = Math.min(a, clamp01((land[pi] - 0.6f) / 0.4f));
            // Only outlines that teach something: plain green pieces (and the plate) have none.
            if (c == 0xFF4DFF4D) continue;
            a *= pulse;
            if (a <= 0.02f) continue;
            LevelRenderer.renderLineBox(pose, l, b[0] + 0.05, b[1] + 0.02, b[2] + 0.05, b[3] - 0.05, b[4] - 0.02, b[5] - 0.05,
                    (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, (c >>> 24) / 255f * a);
        }
    }

    /** @return a scene point on screen (GUI px) */
    private static float[] project(Matrix4f m, float x, float y, float z) {
        Vector3f v = m.transformPosition(x, y, z, new Vector3f());
        return new float[]{v.x, v.y};
    }

    /** A little dust where a piece landed: specks flying out and fading. */
    private void poof(GuiGraphics g, Matrix4f m, PieceView.Part q, float u) {
        float[] c = project(m, q.x() + q.bp().getSizeX() / 2f, q.y() + 1, q.z() + q.bp().getSizeZ() / 2f);
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4 + 0.3;
            float r = 4 + 14 * easeOut(u);
            int px = Math.round(c[0] + (float) Math.cos(ang) * r), py = Math.round(c[1] + (float) Math.sin(ang) * r * 0.5f - 4 * u);
            g.fill(px, py, px + 2, py + 2, alpha(i % 2 == 0 ? 0xFFD8D0C0 : 0xFFA89880, 1 - u));
        }
    }

    /** Edit nodes, the gliding cursor and its click ripple, callouts with leader lines (stacked, kept on the stage). */
    private void overlays(GuiGraphics g, Matrix4f m, Scene s, Ponder.Step st, long el, float t, int vx, int vy, int vw, int vh) {
        int longest = longest();
        if (st.nodes) {
            float a = clamp01(el / 300f);
            for (Plan.Line l : s.lines()) {
                int[][] pts = {{l.x0(), l.z0()}, {l.x1(), l.z1()}, {(l.x0() + l.x1()) / 2, (l.z0() + l.z1()) / 2}};
                for (int k = 0; k < 3; k++) {
                    float[] sp = project(m, pts[k][0] + 0.5f, Ponder.ground(s.ground(), pts[k][0], pts[k][1]) + 2, pts[k][1] + 0.5f);
                    diamond(g, (int) sp[0], (int) sp[1], k < 2 ? 5 : 3, a);
                }
            }
        }
        List<int[]> placed = new ArrayList<>();
        for (Ponder.Callout c : st.callouts) {
            int cx = Ponder.coord(c.x(), longest), cz = Ponder.coord(c.z(), longest);
            float[] an = project(m, cx + 0.5f, Ponder.ground(s.ground(), cx, cz) + c.y(), cz + 0.5f);
            // A gap callout points at the gap's own box (where the layout left it).
            if (c.text().endsWith(".gap"))
                for (float[] bx : s.boxes())
                    if ((int) bx[7] < 0) an = project(m, (bx[0] + bx[3]) / 2, bx[4], (bx[2] + bx[5]) / 2);
            float a = easeOut((el - 350) / 300f);
            if (a <= 0) continue;
            String text = Component.translatable(c.text()).getString();
            List<String> ls = LineHelp.wrap(text, 130, font::width);
            int tw = 0;
            for (String ln : ls) tw = Math.max(tw, font.width(ln));
            int bw = tw + 10, bh = ls.size() * 10 + 6;
            int bx = Math.max(vx + 4, Math.min((int) an[0] + 16, vx + vw - bw - 4)), by = Math.max(vy + 22, Math.min((int) an[1] - 34 + Math.round(6 * (1 - a)), vy + vh - bh - 4));
            // Stacked: never over another callout.
            for (boolean moved = true; moved; ) {
                moved = false;
                for (int[] r : placed)
                    if (bx < r[0] + r[2] && bx + bw > r[0] && by < r[1] + r[3] && by + bh > r[1]) {
                        by = r[1] + r[3] + 3;
                        moved = true;
                    }
            }
            placed.add(new int[]{bx, by, bw, bh});
            int acc = Ponder.calloutColor(c.text());
            line(g, (int) an[0], (int) an[1], bx + (an[0] < bx ? 0 : bw), by + bh / 2, alpha(0xFFFFFFFF, a));
            g.fill((int) an[0] - 2, (int) an[1] - 2, (int) an[0] + 2, (int) an[1] + 2, alpha(acc, a));
            g.fill(bx, by, bx + bw, by + bh, alpha(0xE8181C22, a));
            g.fill(bx, by, bx + bw, by + 1, alpha(acc, a));
            g.fill(bx, by + bh - 1, bx + bw, by + bh, alpha(acc, a));
            g.fill(bx, by, bx + 1, by + bh, alpha(acc, a));
            g.fill(bx + bw - 1, by, bx + bw, by + bh, alpha(acc, a));
            int ly = by + 4;
            for (String ln : ls) {
                g.drawString(font, ln, bx + 5, ly, alpha(0xFFFFFFFF, a), false);
                ly += 10;
            }
        }
        if (st.cursor != null) {
            int cx = Ponder.coord(st.cursor[0], longest), cz = Ponder.coord(st.cursor[1], longest);
            float[] to = project(m, cx + 0.5f, Ponder.ground(s.ground(), cx, cz) + 1, cz + 0.5f);
            float[] from = cursorFrom != null ? cursorFrom : new float[]{vx + vw * 0.75f, vy + vh * 0.8f};
            float k = easeInOut(t * GLIDE);
            float px = from[0] + (to[0] - from[0]) * k, py = from[1] + (to[1] - from[1]) * k;
            cursorAt = new float[]{px, py};
            if (st.click && t > CLICK_T && t < 0.9f) {
                float u = (t - CLICK_T) / 0.4f;
                ring(g, (int) px, (int) py, (int) (3 + 12 * easeOut(u)), alpha(0xFFFFFFFF, 1 - u));
                ring(g, (int) px, (int) py, (int) (2 + 6 * easeOut(u)), alpha(0xFF40E6FF, 1 - u));
            }
            // The pointer's tip pixel sits on the target; pressed while it clicks or drags; a drag has the move badge.
            boolean down = st.click && t > CLICK_T && (st.nodes || t < 0.7f);
            pointer(g, down ? "pressed" : "arrow", Math.round(px), Math.round(py));
            if (st.nodes) icon(g, Ponder.BADGES.get("move"), Math.round(px) + 8, Math.round(py) + 9);
        }
    }

    /** The step's key caps, top left of the stage, popping in. */
    private void keys(GuiGraphics g, Ponder.Step st, long el, int vx, int vy) {
        int kx = vx + 6, ky = vy + 6;
        for (int i = 0; i < st.keys.size(); i++) {
            String k = st.keys.get(i);
            float u = easeOutBack((el - 120 - i * 60) / 220f);
            if (u <= 0) continue;
            String l = k.startsWith("#") ? Component.translatable("mccitybuilder.hint.key." + k.substring(1)).getString() : k;
            if (k.equals("/")) {
                g.drawString(font, l, kx + 1, ky + 4, 0xFFFFFFFF, false);
                kx += font.width(l) + 3;
                continue;
            }
            int cw = font.width(l) + 8;
            g.pose().pushPose();
            g.pose().translate(kx + cw / 2f, ky + 8, 0);
            g.pose().scale(u, u, 1);
            g.pose().translate(-(kx + cw / 2f), -(ky + 8), 0);
            cap(g, kx, ky, cw, 16, l);
            g.pose().popPose();
            kx += cw + 3;
        }
    }

    /** A key cap: the planner guide's style, a little larger. */
    private void cap(GuiGraphics g, int x, int y, int w, int h, String label) {
        g.fill(x, y, x + w, y + h, VanillaUi.SLOT_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, VanillaUi.PANEL);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, VanillaUi.HIGHLIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, VanillaUi.HIGHLIGHT);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, VanillaUi.SLOT);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, VanillaUi.SLOT);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, VanillaUi.LABEL, false);
    }

    /** Hovering a piece: its name and size, like the piece card. */
    private void hover(GuiGraphics g, Matrix4f m, Scene s, int mx, int my, int vx, int vy, int vw, int vh) {
        if (viewDrag || mx < vx || my < vy || mx >= vx + vw || my >= vy + vh) return;
        PieceView.Part best = null;
        double bd = 14;
        for (PieceView.Part q : s.parts()) {
            float[] c = project(m, q.x() + q.bp().getSizeX() / 2f, q.y() + q.bp().getSizeY() / 2f, q.z() + q.bp().getSizeZ() / 2f);
            double d = Math.hypot(c[0] - mx, c[1] - my);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) return;
        String path = best.key().split("\\|")[2];
        String name = path.substring(path.lastIndexOf('/') + 1).replace(".blueprint", "");
        String t = name + "  " + best.bp().getSizeX() + " x " + best.bp().getSizeY() + " x " + best.bp().getSizeZ();
        int tw = font.width(t), tx = Math.min(mx + 10, vx + vw - tw - 8), ty = Math.max(vy + 4, my - 16);
        g.fill(tx, ty, tx + tw + 6, ty + 12, 0xE8181C22);
        g.drawString(font, t, tx + 3, ty + 2, 0xFFFFFFFF, false);
    }

    /** The caption under the stage, fading in, with key names drawn as key caps. */
    private void caption(GuiGraphics g, Ponder.Step st, long el, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF1C2128);
        g.fill(x, y, x + 2, y + h, 0xFF40A0E0);
        float a = clamp01(el / 250f);
        int tx = x + 8, ty = y + 5, lineH = 12;
        for (String word : Component.translatable(st.caption).getString().split(" ")) {
            String bare = word.replaceAll("^[(]|[).,:;]+$", "");
            boolean key = CAPTION_KEYS.contains(bare);
            int ww = key ? font.width(bare) + 6 + font.width(word.substring(word.indexOf(bare) + bare.length())) : font.width(word);
            if (tx + ww > x + w - 6) {
                tx = x + 8;
                ty += lineH;
                if (ty + 9 > y + h) break;
            }
            if (key) {
                String pre = word.substring(0, word.indexOf(bare)), post = word.substring(word.indexOf(bare) + bare.length());
                g.drawString(font, pre, tx, ty, alpha(0xFFFFFFFF, a), false);
                tx += font.width(pre);
                int cw = font.width(bare) + 6;
                g.fill(tx, ty - 2, tx + cw, ty + 9, alpha(0xFF373737, a));
                g.fill(tx + 1, ty - 1, tx + cw - 1, ty + 8, alpha(0xFFC6C6C6, a));
                g.drawString(font, bare, tx + 3, ty, alpha(0xFF404040, a), false);
                tx += cw;
                g.drawString(font, post, tx, ty, alpha(0xFFFFFFFF, a), false);
                tx += font.width(post);
            } else {
                g.drawString(font, word, tx, ty, alpha(0xFFFFFFFF, a), false);
                tx += font.width(word);
            }
            tx += font.width(" ");
        }
    }

    // Words in captions drawn as key caps.
    private static final Set<String> CAPTION_KEYS = Set.of("Shift", "C", "R", "X", "H", "F", "G", "Tab", "Del", "Backspace", "Esc", "PgUp", "PgDn", "Alt-click");

    /** One segment per step: done ones full, the current one filling, all clickable. */
    private void timeline(GuiGraphics g, int mx, int my, float t, int x, int y, int w, int n) {
        int gap = 3, segW = (w - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) {
            int sx = x + i * (segW + gap), fi = i;
            boolean over = mx >= sx && mx < sx + segW && my >= y - 2 && my < y + 10;
            g.fill(sx, y, sx + segW, y + 8, VanillaUi.SLOT_DARK);
            g.fill(sx + 1, y + 1, sx + segW - 1, y + 7, over ? 0xFF4A5560 : 0xFF2A3038);
            int fw = i < step ? segW - 2 : i == step ? (int) ((segW - 2) * t) : 0;
            g.fill(sx + 1, y + 1, sx + 1 + fw, y + 7, i == step ? 0xFF56B4F0 : 0xFF3A7FB0);
            hits.add(new Hit(sx, y - 2, segW, 12, () -> go(topic, fi)));
        }
    }

    /** Back, play / pause, next, replay as icon buttons; Tips and Close as text buttons, right. */
    private void controls(GuiGraphics g, int mx, int my, int x, int y, int w) {
        String[] icons = {"back", paused ? "play" : "pause", "next", "again"};
        for (int i = 0; i < icons.length; i++) {
            int bx = x + i * 22, fi = i;
            boolean over = mx >= bx && mx < bx + 20 && my >= y && my < y + 20;
            VanillaUi.button(g, bx, y, 20, 20, false, false, over);
            icon(g, icons[i], bx + 4, y + 4, 1);
            hits.add(new Hit(bx, y, 20, 20, () -> button(fi)));
        }
        String[] keys = {"replay", "close"};
        for (int i = 0; i < keys.length; i++) {
            String l = Component.translatable("mccitybuilder.ponder.btn." + keys[i]).getString();
            int bw = Math.max(50, font.width(l) + 12), bx = x + w - (keys.length - i) * (bw + 2) + 2, fi = i + 4;
            boolean over = mx >= bx && mx < bx + bw && my >= y && my < y + 20;
            VanillaUi.button(g, bx, y, bw, 20, false, false, over);
            VanillaUi.buttonLabel(g, font, l, bx, y, bw, 20, VanillaUi.INK);
            hits.add(new Hit(bx, y, bw, 20, () -> button(fi)));
        }
    }

    private void button(int i) {
        switch (i) {
            case 0 -> go(topic, step - 1);
            case 1 -> {
                paused = !paused;
                stepStart = System.currentTimeMillis();
            }
            case 2 -> go(topic, step + 1);
            case 3 -> {
                paused = false;
                go(topic, 0);
            }
            case 4 -> {
                PlannerScreen.tipStep = 0;
                onClose();
            }
            default -> onClose();
        }
    }

    /** A {@link Ponder#ICONS} icon, {@code px} GUI pixels per icon pixel. */
    private static void icon(GuiGraphics g, String id, int x, int y, int px) {
        String[] rows = Ponder.ICONS.get(id);
        if (rows == null) return;
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length(); c++) {
                int col = Ponder.iconColor(rows[r].charAt(c));
                if (col != 0) g.fill(x + c * px, y + r * px, x + c * px + px, y + r * px + px, col);
            }
    }

    private int longest() {
        String tool = topic().tool() != null ? topic().tool() : LineView.tool != null ? LineView.tool : "wall";
        String fam = family(tool);
        if (fam == null) return 5;
        int lg = 0;
        for (int l : PieceCatalog.runLengths(new Plan.Line(tool, 0, 0, 30, 0, pack, fam, LineView.level, LineView.variant, false, false))) lg = Math.max(lg, l);
        return Math.max(1, lg);
    }

    private static void diamond(GuiGraphics g, int x, int y, int r, float a) {
        for (int d = -r; d <= r; d++) {
            int half = r - Math.abs(d);
            g.fill(x - half - 1, y + d, x + half + 2, y + d + 1, alpha(0xFF202020, a));
            if (half > 0) g.fill(x - half, y + d, x + half + 1, y + d + 1, alpha(0xFFF0A020, a));
        }
    }

    private static void ring(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int i = 0; i < 32; i++) {
            double an = i * Math.PI / 16;
            int px = cx + (int) Math.round(Math.cos(an) * r), py = cy + (int) Math.round(Math.sin(an) * r);
            g.fill(px, py, px + 1, py + 1, c);
        }
    }

    /** A {@link Ponder#CURSORS} pointer, 1 GUI pixel per pixel, its top left (the tip) at (x, y). */
    private static void pointer(GuiGraphics g, String kind, int x, int y) {
        icon(g, Ponder.CURSORS.get(kind), x, y);
    }

    /** Pixel art rows ({@link Ponder#iconColor}), 1 GUI pixel per pixel, top left at (x, y). */
    private static void icon(GuiGraphics g, String[] rows, int x, int y) {
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length(); c++) {
                int col = Ponder.iconColor(rows[r].charAt(c));
                if (col != 0) g.fill(x + c, y + r, x + c + 1, y + r + 1, col);
            }
    }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int c) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int px = x0 + (x1 - x0) * i / Math.max(1, n), py = y0 + (y1 - y0) * i / Math.max(1, n);
            g.fill(px, py, px + 1, py + 1, c);
        }
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0)
            for (Hit h : hits)
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    h.run().run();
                    return true;
                }
        if (button == 0 && view != null && mx >= view[0] && mx < view[0] + view[2] && my >= view[1] && my < view[1] + view[3]) {
            long now = System.currentTimeMillis();
            if (now - lastClick < 350) {
                // A double-click gives the camera back to the scenes.
                userView = false;
                PieceView.yaw = 35;
                PieceView.pitch = 32;
                PieceView.zoom = ZOOM;
            }
            lastClick = now;
            viewDrag = PieceView.dragging = true;
            dragX = mx;
            dragY = my;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!viewDrag) return super.mouseDragged(mx, my, button, dx, dy);
        if (Math.abs(mx - dragX) + Math.abs(my - dragY) > 0) userView = true;
        PieceView.yaw += (float) (mx - dragX);
        PieceView.pitch = (float) Math.max(5, Math.min(85, PieceView.pitch + (my - dragY)));
        dragX = mx;
        dragY = my;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        viewDrag = PieceView.dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        userView = true;
        PieceView.zoom = (float) Math.max(0.4, Math.min(5, PieceView.zoom * (sy > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> button(1);
            case GLFW.GLFW_KEY_RIGHT -> go(topic, step + 1);
            case GLFW.GLFW_KEY_LEFT -> go(topic, step - 1);
            case GLFW.GLFW_KEY_DOWN -> go(Math.min(Ponder.TOPICS.size() - 1, topic + 1), 0);
            case GLFW.GLFW_KEY_UP -> go(Math.max(0, topic - 1), 0);
            default -> {
                return super.keyPressed(key, scan, mods);
            }
        }
        return true;
    }
}
