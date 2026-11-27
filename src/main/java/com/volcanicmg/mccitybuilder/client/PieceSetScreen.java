package com.volcanicmg.mccitybuilder.client;

import com.volcanicmg.mccitybuilder.planner.PieceSets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The piece-set set-up screen for one folder of a style pack: opens with what detection found. Left, every blueprint
 * group of the folder (name, role badge: a click picks the next role, right-click the one before); right, the set's
 * slots with ✓ or what the planner does without one, and a turning 3D view of the hovered (else last clicked) piece. "More" adds making a piece a variant of another and
 * using the folder as roads / walls / not a set. Save writes the changes to the pack's overrides file (as
 * {@link PieceSets#TOOL} / {@link PieceSets#PIECE} keys), then suggests the joint check.
 */
final class PieceSetScreen extends Screen {
    private static final int ROW_H = 18;
    private static final String NOT_IN_SET = "not";

    private final String pack, folder;
    private final String focusFamily;
    // Unsaved changes: override key -> value (null: drop the player's key, back to detection).
    private final Map<String, String> changes = new LinkedHashMap<>();
    private boolean more, saved;
    private int scroll, famIndex;
    private final List<Hit> hits = new ArrayList<>(), rightHits = new ArrayList<>();
    private String tip;
    // The 3D view: the piece shown (hovered row, else the last clicked one), its role label, the baked key, the box.
    private String pickedPath, shownPath, shownRole, previewKey;
    private int[] view;
    private boolean drag;

    private record Hit(int x, int y, int w, int h, Runnable run) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** One row: a group of blueprints sharing a base name (numbered levels / variants) in the folder. */
    private record Row(String base, List<Blueprints.Hut> huts) {
    }

    /**
     * @param folder the folder to set up (a family's folder, {@link PieceSets#folder}); its sub-folders come along
     * @param family the family to show first ("tool:key"), or null
     */
    PieceSetScreen(String pack, String folder, String family) {
        super(Component.translatable("mccitybuilder.sets.title"));
        this.pack = pack;
        this.folder = folder;
        this.focusFamily = family;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (modal.open()) {
            modal.close();
            return;
        }
        if (changes.isEmpty()) {
            closeNow();
            return;
        }
        modal.ask(Component.translatable("mccitybuilder.discard", changes.size()).getString(), List.of(
                new Modal.Choice(Component.translatable("mccitybuilder.discard.yes").getString(), this::closeNow),
                new Modal.Choice(Component.translatable("mccitybuilder.discard.no").getString(), () -> {
                })));
    }

    private void closeNow() {
        minecraft.setScreen(Planner.active() ? new PlannerScreen() : null);
    }

    private final Modal modal = new Modal();

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (modal.open() && key != com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        Planner.guard(() -> draw(g, mx, my));
        // Its own guard: a failing draw above can't leave an invisible box that swallows every click.
        Planner.guard(() -> modal.draw(g, font, width, height, mx, my));
    }

    // ---- model ----

    /** @return the folder's blueprints (and its sub-folders'), or null while they load */
    private List<Blueprints.Hut> huts() {
        List<Blueprints.Hut> out = new ArrayList<>();
        for (String f : Blueprints.decoFolders(pack)) {
            if (!folder.isEmpty() && !f.equals(folder) && !f.startsWith(folder + "/")) continue;
            List<Blueprints.Hut> ds = Blueprints.decorations(pack, f);
            if (ds == null) return null;
            out.addAll(ds);
        }
        return out;
    }

    private Map<String, String> setup() {
        Map<String, String> m = new LinkedHashMap<>(PieceCatalog.setup(pack));
        changes.forEach((k, v) -> {
            if (v == null) m.remove(k);
            else m.put(k, v);
        });
        // A removed player key falls back to the built-in one.
        changes.forEach((k, v) -> {
            if (v == null && PieceSets.builtIn(pack).containsKey(k)) m.put(k, PieceSets.builtIn(pack).get(k));
        });
        return m;
    }

    private static String base(String path) {
        return PieceSets.split(path.substring(path.lastIndexOf('/') + 1))[0];
    }

    private String toolNow(Map<String, String> setup) {
        return PieceSets.toolOf(folder + "/x.blueprint", setup);
    }

    private Integer shapeOf(String path, String tool) {
        return PieceCatalog.shape(pack, path, "road".equals(tool));
    }

    /** @return the families the folder's pieces make with the unsaved changes */
    private Map<String, PieceSets.Family> preview(List<Blueprints.Hut> huts, Map<String, String> setup) {
        List<PieceSets.Piece> ps = new ArrayList<>();
        Map<String, Integer> shapes = new java.util.HashMap<>();
        for (Blueprints.Hut h : huts) {
            String[] sn = PieceSets.split(h.path().substring(h.path().lastIndexOf('/') + 1));
            var bp = h.blueprint();
            ps.add(new PieceSets.Piece(h.path(), sn[0], Integer.parseInt(sn[1]), bp.getSizeX(), bp.getSizeY(), bp.getSizeZ()));
            Integer s = shapeOf(h.path(), PieceSets.toolOf(h.path(), setup));
            if (s != null) shapes.put(h.path(), s);
        }
        return PieceSets.classify(ps, shapes, setup);
    }

    /** @return what a row plays now: a role name, "variant:base", or {@link #NOT_IN_SET} */
    private String current(Row r, Map<String, String> setup, Map<String, PieceSets.Family> fams) {
        String v = setup.get(PieceSets.PIECE + r.huts().get(0).path());
        if (v != null && v.startsWith(PieceSets.VARIANT)) return v;
        for (PieceSets.Family f : fams.values())
            for (var gs : f.roles().values())
                for (PieceSets.Group g : gs)
                    for (PieceSets.Piece p : g.pieces()) if (p.path().equals(r.huts().get(0).path())) return g.role() == PieceSets.Role.OTHER ? NOT_IN_SET : g.role().name();
        return NOT_IN_SET;
    }

    /** @return the choices a row's badge steps through: the tool's slots, inner corner, slope, not in set, then (More) variants */
    private List<String> choices(String tool, Row self, List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (PieceSets.Role r : PieceSets.slots(tool)) out.add(r.name());
        out.add(PieceSets.Role.INNER.name());
        out.add(PieceSets.Role.SLOPE.name());
        out.add(NOT_IN_SET);
        if (more) out.add(PieceSets.VARIANT);
        return out;
    }

    /** Sets a row's role: a change per blueprint of the group, none where it is what detection gives anyway. */
    private void choose(Row r, String choice, String tool) {
        saved = false;
        String v = choice.equals(NOT_IN_SET) ? PieceSets.EXCLUDED : choice;
        for (Blueprints.Hut h : r.huts()) {
            String key = PieceSets.PIECE + h.path();
            // What the piece would be without the player's key: the built-in key, else detection (unused = excluded).
            String without = PieceSets.builtIn(pack).get(key);
            if (without == null) {
                PieceSets.Role auto = PieceSets.decide(base(h.path()), shapeOf(h.path(), tool));
                without = auto == PieceSets.Role.OTHER ? PieceSets.EXCLUDED : auto.name();
            }
            changes.put(key, v.equals(without) ? null : v);
        }
    }

    private void setTool(String tool) {
        saved = false;
        String key = PieceSets.TOOL + folder;
        // The folder's tool without the player's key (the built-in key, a parent folder's, else detection).
        Map<String, String> without = setup();
        without.remove(key);
        if (PieceSets.builtIn(pack).containsKey(key)) without.put(key, PieceSets.builtIn(pack).get(key));
        changes.put(key, java.util.Objects.equals(PieceSets.toolOf(folder + "/x.blueprint", without), tool) ? null : tool == null ? PieceSets.NONE : tool);
        famIndex = 0;
    }

    /** @return " (sub-folder)" for a blueprint below the set's folder, else "" */
    private String subLabel(String path) {
        int sl = path.lastIndexOf('/');
        String sub = sl < 0 ? "" : path.substring(0, sl);
        if (sub.equals(folder)) return "";
        return "  (" + (folder.isEmpty() ? sub : sub.substring(Math.min(sub.length(), folder.length() + 1))) + ")";
    }

    /**
     * "Same as…": a list of the rows this one can join (not itself, not rows that are variants themselves), with their
     * sub-folder when names repeat.
     */
    private void pickVariant(Row self, List<Row> rows, Map<String, String> setup, Map<String, PieceSets.Family> fams, String tool) {
        Map<String, Integer> count = new java.util.HashMap<>();
        for (Row r : rows) count.merge(r.base(), 1, Integer::sum);
        List<Modal.Choice> cs = new ArrayList<>();
        for (Row r : rows) {
            if (r == self || current(r, setup, fams).startsWith(PieceSets.VARIANT)) continue;
            String name = r.base() + (count.get(r.base()) > 1 ? subLabel(r.huts().get(0).path()) : "");
            cs.add(new Modal.Choice(name, () -> choose(self, PieceSets.VARIANT + r.base(), tool)));
        }
        cs.add(new Modal.Choice(Component.translatable("mccitybuilder.sets.cancel").getString(), () -> {
        }));
        modal.ask(Component.translatable("mccitybuilder.sets.variant.title", self.base()).getString(), cs);
    }

    private static String label(String choice) {
        if (choice.equals(PieceSets.VARIANT)) return Component.translatable("mccitybuilder.sets.variant.pick").getString();
        if (choice.startsWith(PieceSets.VARIANT)) return Component.translatable("mccitybuilder.sets.variant", choice.substring(PieceSets.VARIANT.length())).getString();
        return Component.translatable("mccitybuilder.sets.role." + (choice.equals(NOT_IN_SET) ? "other" : choice.toLowerCase(Locale.ROOT))).getString();
    }

    private static int colour(String choice) {
        // Light grey: the vanilla button is mid grey, so darker greys vanish on it.
        if (choice.equals(NOT_IN_SET)) return VanillaUi.INK_SELECTED;
        if (choice.startsWith(PieceSets.VARIANT)) return 0xFF4060A0;
        return choice.equals("SLOPE") || choice.equals("INNER") ? 0xFF806000 : 0xFF206020;
    }

    // ---- drawing ----

    private void draw(GuiGraphics g, int mx, int my) {
        hits.clear();
        rightHits.clear();
        tip = null;
        shownPath = null;
        shownRole = null;
        view = null;
        renderBackground(g, mx, my, 0);
        int pad = 7, top = 6, h = height - 12, lw = Math.min(260, (width - 18) * 11 / 20), rw = Math.min(240, width - 18 - lw);
        int lx = (width - lw - rw - 6) / 2, rx = lx + lw + 6;
        VanillaUi.panel(g, lx, top, lw, h);
        VanillaUi.panel(g, rx, top, rw, h);
        g.drawString(font, font.plainSubstrByWidth(title.getString() + ": " + folder, lw - 2 * pad), lx + pad, top + pad, VanillaUi.LABEL, false);
        List<Blueprints.Hut> huts = huts();
        if (huts == null) {
            g.drawString(font, Component.translatable("mccitybuilder.invalid.loading"), lx + pad, top + pad + 14, 0xFF606060, false);
            return;
        }
        Map<String, String> setup = setup();
        String tool = toolNow(setup);
        Map<String, PieceSets.Family> fams = preview(huts, setup);
        // Rows: one per base name, in path order.
        Map<String, Row> byBase = new LinkedHashMap<>();
        for (Blueprints.Hut hh : huts) byBase.computeIfAbsent(hh.path().substring(0, hh.path().lastIndexOf('/') + 1) + base(hh.path()), k -> new Row(base(hh.path()), new ArrayList<>())).huts().add(hh);
        List<Row> rows = new ArrayList<>(byBase.values());
        int iy = top + pad + 12;
        // Not a set yet: the tool choice up front; else behind More.
        if (tool == null || more) {
            if (tool == null) {
                g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.sets.not_set").getString(), lw - 2 * pad), lx + pad, iy, 0xFF804000, false);
                iy += 11;
            }
            int bw = (lw - 2 * pad - 4) / 3;
            String[] tools = {"road", "wall", null};
            for (int i = 0; i < 3; i++) {
                int bx = lx + pad + i * (bw + 2);
                String t = tools[i];
                boolean on = java.util.Objects.equals(tool, t), over = in(mx, my, bx, iy, bw, 16);
                VanillaUi.button(g, bx, iy, bw, 16, on, false, over);
                VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.sets.use." + (t == null ? "none" : t)).getString(), bx, iy, bw, 16, VanillaUi.ink(on, false));
                hits.add(new Hit(bx, iy, bw, 16, () -> {
                    PlannerScreen.click();
                    setTool(t);
                }));
            }
            iy += 20;
        }
        // The list.
        int listTop = iy, visible = Math.max(1, (top + h - pad - listTop) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible));
        for (int i = 0; i < Math.min(visible, rows.size() - scroll); i++) {
            Row r = rows.get(scroll + i);
            int ry = listTop + i * ROW_H;
            Blueprints.Hut first = r.huts().get(0);
            if (in(mx, my, lx, ry, lw, ROW_H) || shownPath == null && first.path().equals(pickedPath)) {
                shownPath = first.path();
                shownRole = tool == null ? null : label(current(r, setup, fams));
            }
            hits.add(new Hit(lx + pad, ry, lw - 2 * pad - 92, ROW_H, () -> pickedPath = first.path()));
            String name = r.base() + (r.huts().size() > 1 ? " ×" + r.huts().size() : "") + subLabel(first.path());
            int bw = 92, bx = lx + lw - pad - bw;
            if (first.path().equals(pickedPath)) g.fill(lx + pad, ry, bx - 2, ry + 16, 0x30FFFFFF);
            g.drawString(font, font.plainSubstrByWidth(name, bx - lx - pad - 6), lx + pad + 2, ry + 5, VanillaUi.LABEL, false);
            if (tool == null) continue;
            String cur = current(r, setup, fams);
            boolean over = in(mx, my, bx, ry, bw, 16);
            VanillaUi.button(g, bx, ry, bw, 16, false, false, over);
            VanillaUi.buttonLabel(g, font, font.plainSubstrByWidth(label(cur), bw - 6), bx, ry, bw, 16, colour(cur));
            List<String> cs = choices(tool, r, rows);
            java.util.function.IntConsumer step = d -> {
                PlannerScreen.click();
                int k = cs.indexOf(cur.startsWith(PieceSets.VARIANT) ? PieceSets.VARIANT : cur);
                String next = cs.get(Math.floorMod((k < 0 ? -1 : k) + d, cs.size()));
                if (next.equals(PieceSets.VARIANT)) pickVariant(r, rows, setup, fams, tool);
                else choose(r, next, tool);
            };
            hits.add(new Hit(bx, ry, bw, 16, () -> {
                pickedPath = first.path();
                step.accept(1);
            }));
            rightHits.add(new Hit(bx, ry, bw, 16, () -> step.accept(-1)));
            if (over) {
                PieceSets.Role hint = PieceSets.shapeHint(r.base(), shapeOf(first.path(), tool));
                tip = hint == null ? Component.translatable("mccitybuilder.sets.badge.tip").getString()
                        : Component.translatable("mccitybuilder.sets.shape_hint", label(hint.name())).getString();
            }
        }
        if (rows.size() > visible)
            g.drawString(font, (scroll + 1) + "-" + Math.min(rows.size(), scroll + visible) + "/" + rows.size(), lx + lw - pad - 40, top + pad, 0xFF606060, false);
        slots(g, rx, top, rw, h, pad, tool, fams, mx, my);
        if (tip != null) g.renderTooltip(font, font.split(Component.literal(tip), 220), mx, my);
    }

    /** The right panel: the chosen family's slots, More, and Save / Cancel (or, once saved, the joint-check notice). */
    private void slots(GuiGraphics g, int x, int y, int w, int h, int pad, String tool, Map<String, PieceSets.Family> fams, int mx, int my) {
        int ix = x + pad, iw = w - 2 * pad, iy = y + pad;
        List<String> keys = new ArrayList<>(fams.keySet());
        String focus = focusFamily == null ? null : PieceSets.resolveFamily(focusFamily, keys);
        if (focus != null && famIndex == 0) famIndex = keys.indexOf(focus) + 1;
        String famKey = keys.isEmpty() ? null : keys.get(Math.floorMod(famIndex - 1, keys.size()));
        if (saved) {
            for (var line : font.split(Component.translatable("mccitybuilder.sets.saved"), iw)) {
                g.drawString(font, line, ix, iy, VanillaUi.LABEL, false);
                iy += 10;
            }
            iy += 6;
            if (famKey != null && fams.get(famKey).usable())
                iy = button(g, ix, iy, iw, Component.translatable("mccitybuilder.sets.check_joints").getString(), mx, my,
                        () -> JointEditorScreen.openCheck(pack, famKey));
            button(g, ix, iy, iw, Component.translatable("mccitybuilder.sets.done").getString(), mx, my, this::onClose);
            return;
        }
        if (keys.size() > 1) {
            String fl = famKey.substring(famKey.indexOf(':') + 1);
            iy = picker(g, ix, iy, iw, !folder.isEmpty() && fl.startsWith(folder + "/") ? fl.substring(folder.length() + 1) : fl, mx, my, d -> famIndex = Math.floorMod(Math.floorMod(famIndex - 1, keys.size()) + d, keys.size()) + 1);
        }
        g.drawString(font, Component.translatable("mccitybuilder.sets.slots"), ix, iy, VanillaUi.LABEL, false);
        iy += 12;
        PieceSets.Family fam = famKey == null ? null : fams.get(famKey);
        if (tool == null || fam == null) {
            for (var line : font.split(Component.translatable(tool == null ? "mccitybuilder.sets.pick_tool" : "mccitybuilder.sets.no_pieces"), iw)) {
                g.drawString(font, line, ix, iy, 0xFF606060, false);
                iy += 10;
            }
        } else {
            // Short on room (small windows, big GUI scale): the missing parts' explanations become hover tips.
            int need = 0;
            for (PieceSets.Role r : PieceSets.slots(fam.tool())) {
                need += 12;
                if ((r == PieceSets.Role.STRAIGHT ? fam.straights() : fam.of(r)).isEmpty())
                    need += 9 * font.split(Component.translatable("mccitybuilder.sets.missing." + r.name().toLowerCase(Locale.ROOT)), iw - 8).size();
            }
            boolean compact = iy + need > y + h - pad - 16 - 24 - 70;
            for (PieceSets.Role r : PieceSets.slots(fam.tool())) {
                // Straights as the line tool orders them: its main straight first.
                List<PieceSets.Group> gs = r == PieceSets.Role.STRAIGHT ? fam.straights() : fam.of(r);
                boolean ok = !gs.isEmpty();
                String head = (ok ? "✔ " : "⚠ ") + label(r.name()) + (ok ? ": " + gs.get(0).base() + (gs.size() > 1 ? " +" + (gs.size() - 1) : "") : "");
                g.drawString(font, font.plainSubstrByWidth(head, iw), ix, iy, ok ? 0xFF206020 : 0xFF905000, false);
                Component why = Component.translatable("mccitybuilder.sets.missing." + r.name().toLowerCase(Locale.ROOT));
                if (!ok && compact && in(mx, my, ix, iy - 1, iw, 10)) tip = why.getString();
                iy += 10;
                if (!ok && !compact) {
                    for (var line : font.split(why, iw - 8)) {
                        g.drawString(font, line, ix + 8, iy, 0xFF505050, false);
                        iy += 9;
                    }
                }
                iy += 2;
            }
        }
        // Bottom: More, Cancel, Save; the 3D view of the hovered / picked piece fills the space above them.
        int by = y + h - pad - 16, half = (iw - 2) / 2;
        preview(g, ix, iy + 4, iw, by - 24 - iy - 4);
        // Always the one way out that keeps what is shown (nothing changed: detection's set stands, nothing written).
        boolean overS = in(mx, my, ix, by, iw, 16);
        VanillaUi.button(g, ix, by, iw, 16, false, false, overS);
        VanillaUi.buttonLabel(g, font, Component.translatable(changes.isEmpty() ? "mccitybuilder.sets.done" : "mccitybuilder.sets.save").getString(), ix, by, iw, 16, VanillaUi.INK);
        hits.add(new Hit(ix, by, iw, 16, () -> {
            PlannerScreen.click();
            int losing = changes.isEmpty() ? 0 : linesLosing();
            if (losing == 0) {
                save();
                return;
            }
            modal.ask(Component.translatable("mccitybuilder.sets.lines_lose", losing).getString(), List.of(
                    new Modal.Choice(Component.translatable("mccitybuilder.sets.save_anyway").getString(), this::save),
                    new Modal.Choice(Component.translatable("mccitybuilder.sets.cancel").getString(), () -> {
                    })));
        }));
        by -= 20;
        boolean overM = in(mx, my, ix, by, half, 16), overC = in(mx, my, ix + half + 2, by, half, 16);
        VanillaUi.button(g, ix, by, half, 16, more, false, overM);
        VanillaUi.buttonLabel(g, font, Component.translatable(more ? "mccitybuilder.sets.less" : "mccitybuilder.sets.more").getString(), ix, by, half, 16, VanillaUi.ink(more, false));
        hits.add(new Hit(ix, by, half, 16, () -> {
            PlannerScreen.click();
            more = !more;
        }));
        if (overM) tip = Component.translatable("mccitybuilder.sets.more.tip").getString();
        VanillaUi.button(g, ix + half + 2, by, half, 16, false, false, overC);
        VanillaUi.buttonLabel(g, font, Component.translatable("mccitybuilder.sets.cancel").getString(), ix + half + 2, by, half, 16, VanillaUi.INK);
        hits.add(new Hit(ix + half + 2, by, half, 16, this::onClose));
    }

    /** The first row's "Same as…" list (the dev bridge's smoke test checks its Cancel stays reachable). @return false while loading */
    boolean openVariantPicker() {
        List<Blueprints.Hut> hs = huts();
        if (hs == null || hs.isEmpty()) return false;
        Map<String, String> setup = setup();
        Map<String, Row> byBase = new LinkedHashMap<>();
        for (Blueprints.Hut hh : hs) byBase.computeIfAbsent(hh.path().substring(0, hh.path().lastIndexOf('/') + 1) + base(hh.path()), k -> new Row(base(hh.path()), new ArrayList<>())).huts().add(hh);
        List<Row> rows = new ArrayList<>(byBase.values());
        pickVariant(rows.get(0), rows, setup, preview(hs, setup), toolNow(setup));
        return true;
    }

    private void save() {
        if (!changes.isEmpty()) PieceCatalog.setSetup(pack, changes);
        changes.clear();
        saved = true;
    }

    /** @return how many planned lines of this pack would be left without a set by the unsaved changes */
    private int linesLosing() {
        var now = PieceCatalog.families(pack);
        List<Blueprints.Hut> hs = huts();
        if (now == null || hs == null) return 0;
        Map<String, String> before = new LinkedHashMap<>(PieceCatalog.setup(pack));
        java.util.Set<String> mine = preview(hs, before).keySet(), after = new java.util.HashSet<>(now.keySet());
        after.removeAll(mine);
        after.addAll(preview(hs, setup()).keySet());
        return PlanView.linesLosing(pack, now.keySet(), after);
    }

    /** The shown piece in 3D (drag to turn, wheel to zoom) with its name, size and role above it. */
    private void preview(GuiGraphics g, int x, int y, int w, int h) {
        String key = shownPath == null ? null : "sets|" + pack + "|" + shownPath;
        if (previewKey != null && !previewKey.equals(key)) PieceView.forget(previewKey);
        previewKey = key;
        if (key == null || h < 40) return;
        var bp = PieceCatalog.rotated(pack, shownPath, 0);
        String name = shownPath.substring(shownPath.lastIndexOf('/') + 1).replace(".blueprint", "");
        String size = bp == null ? "" : " \u00b7 " + Component.translatable("mccitybuilder.line.card.size", Math.max(bp.getSizeX(), bp.getSizeZ()),
                Math.min(bp.getSizeX(), bp.getSizeZ()), bp.getSizeY()).getString();
        g.drawString(font, font.plainSubstrByWidth(name + size, w), x, y, VanillaUi.LABEL, false);
        if (shownRole != null) g.drawString(font, font.plainSubstrByWidth(shownRole, w), x, y + 10, 0xFF206020, false);
        int vy = y + 22, vh = h - 22;
        VanillaUi.slot(g, x, vy, w, vh);
        view = new int[]{x, vy, w, vh};
        if (bp != null) PieceView.render(g, key, bp, x + 1, vy + 1, w - 2, vh - 2);
    }

    @Override
    public void removed() {
        if (previewKey != null) PieceView.forget(previewKey);
        previewKey = null;
    }

    private int button(GuiGraphics g, int x, int y, int w, String label, int mx, int my, Runnable run) {
        boolean over = in(mx, my, x, y, w, 16);
        VanillaUi.button(g, x, y, w, 16, false, false, over);
        VanillaUi.buttonLabel(g, font, label, x, y, w, 16, VanillaUi.INK);
        hits.add(new Hit(x, y, w, 16, () -> {
            PlannerScreen.click();
            run.run();
        }));
        return y + 20;
    }

    private int picker(GuiGraphics g, int x, int y, int w, String label, int mx, int my, java.util.function.IntConsumer step) {
        VanillaUi.button(g, x, y, 16, 16, false, false, in(mx, my, x, y, 16, 16));
        VanillaUi.button(g, x + w - 16, y, 16, 16, false, false, in(mx, my, x + w - 16, y, 16, 16));
        VanillaUi.slot(g, x + 18, y, w - 36, 16);
        g.drawString(font, "<", x + 5, y + 4, VanillaUi.INK, false);
        g.drawString(font, ">", x + w - 10, y + 4, VanillaUi.INK, false);
        String l = font.plainSubstrByWidth(label, w - 42);
        g.drawString(font, l, x + (w - font.width(l)) / 2, y + 4, 0xFFFFFFFF, true);
        hits.add(new Hit(x, y, 16, 16, () -> step.accept(-1)));
        hits.add(new Hit(x + w - 16, y, 16, 16, () -> step.accept(1)));
        return y + 20;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return Planner.guard(() -> {
            if (modal.click(mx, my)) return true;
            for (Hit h : List.copyOf(button == 1 ? rightHits : button == 0 ? hits : List.of()))
                if (h.in(mx, my)) {
                    h.run().run();
                    return true;
                }
            if (button == 0 && view != null && in(mx, my, view[0], view[1], view[2], view[3])) {
                drag = PieceView.dragging = true;
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!drag) return false;
        PieceView.yaw += (float) dx * 1.5f;
        PieceView.pitch = Math.max(-10, Math.min(89, PieceView.pitch + (float) dy * 1.5f));
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        drag = PieceView.dragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (modal.scroll(sy)) return true;
        if (view != null && in(mx, my, view[0], view[1], view[2], view[3])) {
            PieceView.zoom = (float) Math.max(0.3, Math.min(5, PieceView.zoom * (sy > 0 ? 1.15 : 1 / 1.15)));
            return true;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(sy));
        return true;
    }
}
