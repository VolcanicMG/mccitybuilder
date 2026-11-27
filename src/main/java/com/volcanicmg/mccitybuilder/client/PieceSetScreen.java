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
 * group of the folder under captions by its role now (icon, name, size, a compact < role > picker; a click on a row
 * previews it, the picker's arrows or a click on its value pick the next role, right-click the one before); right, the
 * set's role tiles (a green check, or an amber "!" with the fix in a hover tip) and a turning 3D view of the hovered
 * (else picked) piece. "More" adds making a piece a variant of another and
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

    /** @return a role label without its bracketed part ("Corner (L)" is "Corner"), for the small picker */
    private static String shortLabel(String label) {
        int i = label.indexOf(" (");
        return i > 0 ? label.substring(0, i) : label;
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

    // The last frame's model (the dev bridge steps rows and reads the state through these).
    private List<Row> lastRows = List.of(), lastOrder = List.of();
    private Map<String, String> lastSetup = Map.of();
    private Map<String, PieceSets.Family> lastFams = Map.of();
    private String lastTool, dumpText = "";
    private final Map<String, int[]> anchors = new java.util.HashMap<>();
    // A rich tooltip this frame: coloured title, grey explanation, white hint.
    private String richTitle, richBody, richHint;
    // The list order held while the mouse is over it ("#caption" / "p:path"), last frame's order, and a row to scroll into view.
    private List<String> frozenKeys, prevKeys = List.of();
    private String scrollTo;
    private int richColour;

    /** @return named boxes {x, y, w, h} of this frame's controls (the dev bridge clicks them) */
    Map<String, int[]> anchors() {
        return anchors;
    }

    /** @return the last frame's state: the tool, each role tile ("ROLE:ok|missing") and each row's role ("index:ROLE", path order) */
    String dump() {
        return dumpText;
    }

    /** Steps a row's role picker as its arrows do (+1 next, -1 back). @return false when there is no such row */
    boolean stepRow(int index, int d) {
        if (lastTool == null || index < 0 || index >= lastRows.size()) return false;
        stepRole(lastRows.get(index), d, lastTool, lastRows, lastSetup, lastFams);
        return true;
    }

    private void stepRole(Row r, int d, String tool, List<Row> rows, Map<String, String> setup, Map<String, PieceSets.Family> fams) {
        String cur = current(r, setup, fams);
        List<String> cs = choices(tool, r, rows);
        int k = cs.indexOf(cur.startsWith(PieceSets.VARIANT) ? PieceSets.VARIANT : cur);
        String next = cs.get(Math.floorMod((k < 0 ? -1 : k) + d, cs.size()));
        if (next.equals(PieceSets.VARIANT)) pickVariant(r, rows, setup, fams, tool);
        else choose(r, next, tool);
    }

    /** A button: optional pixel icon and a centred white label; {@code green} is the primary look. */
    private void btn(GuiGraphics g, int x, int y, int w, int h, String icon, String label, boolean green, boolean over, boolean off) {
        VanillaUi.button(g, x, y, w, h, false, off, over);
        if (green) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3C8A3C);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF7CCB7C);
            g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFF7CCB7C);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF245C2A);
            g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, 0xFF245C2A);
        }
        int is = icon == null ? 0 : PanelIcons.size(icon), gap = is > 0 ? 4 : 0, room = w - 8 - is - gap;
        String t = font.width(label) <= room ? label : font.plainSubstrByWidth(label, Math.max(0, room - font.width("…"))) + "…";
        int cx = x + (w - is - gap - font.width(t)) / 2;
        if (is > 0) {
            PanelIcons.draw(g, icon, cx, y + (h - is) / 2, 1, off);
            cx += is + gap;
        }
        g.drawString(font, t, cx, y + (h - 8) / 2, off ? VanillaUi.INK_DISABLED : 0xFFFFFFFF, !off);
    }

    /** A section caption (small grey capitals) with a rule to its right. @return the y below it */
    private int caption(GuiGraphics g, int x, int y, int w, String text) {
        String t = text.toUpperCase(Locale.ROOT);
        g.drawString(font, font.plainSubstrByWidth(t, w), x + 1, y, 0xFF6A6A6A, false);
        g.fill(x + font.width(t) + 5, y + 4, x + w, y + 5, 0xFF8B8B8B);
        return y + 12;
    }

    /** @return the row's size as "9 x 6 x 22" (longest side first), "" when unknown */
    private static String size(Blueprints.Hut h) {
        var bp = h.blueprint();
        return bp == null ? "" : Component.translatable("mccitybuilder.line.card.size", Math.max(bp.getSizeX(), bp.getSizeZ()), Math.min(bp.getSizeX(), bp.getSizeZ()), bp.getSizeY()).getString();
    }

    private void draw(GuiGraphics g, int mx, int my) {
        hits.clear();
        rightHits.clear();
        anchors.clear();
        tip = null;
        richTitle = null;
        shownPath = null;
        shownRole = null;
        view = null;
        renderBackground(g, mx, my, 0);
        int pad = 7, top = 6, h = height - 12, lw = Math.min(260, (width - 18) * 11 / 20), rw = Math.min(240, width - 18 - lw);
        int lx = (width - lw - rw - 6) / 2, rx = lx + lw + 6;
        VanillaUi.panel(g, lx, top, lw, h);
        VanillaUi.panel(g, rx, top, rw, h);
        PanelIcons.draw(g, "pieces", lx + pad, top + pad - 3, 1, false);
        String ttl = title.getString();
        g.drawString(font, ttl, lx + pad + 20, top + pad + 1, VanillaUi.LABEL, false);
        g.drawString(font, font.plainSubstrByWidth(folder, lw - 2 * pad - 24 - font.width(ttl)), lx + pad + 24 + font.width(ttl), top + pad + 1, 0xFF707070, false);
        List<Blueprints.Hut> huts = huts();
        if (huts == null) {
            g.drawString(font, Component.translatable("mccitybuilder.invalid.loading"), lx + pad, top + pad + 20, 0xFF606060, false);
            return;
        }
        Map<String, String> setup = setup();
        String tool = toolNow(setup);
        Map<String, PieceSets.Family> fams = preview(huts, setup);
        // Rows: one per base name, in path order.
        Map<String, Row> byBase = new LinkedHashMap<>();
        for (Blueprints.Hut hh : huts) byBase.computeIfAbsent(hh.path().substring(0, hh.path().lastIndexOf('/') + 1) + base(hh.path()), k -> new Row(base(hh.path()), new ArrayList<>())).huts().add(hh);
        List<Row> rows = new ArrayList<>(byBase.values());
        lastRows = rows;
        lastSetup = setup;
        lastFams = fams;
        lastTool = tool;
        int iy = top + pad + 17;
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.sets.sub").getString(), lw - 2 * pad), lx + pad + 1, iy, 0xFF555555, false);
        iy += 12;
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
                boolean on = java.util.Objects.equals(tool, t);
                btn(g, bx, iy, bw, 16, null, Component.translatable("mccitybuilder.sets.use." + (t == null ? "none" : t)).getString(), on, in(mx, my, bx, iy, bw, 16), false);
                hits.add(new Hit(bx, iy, bw, 16, () -> {
                    PlannerScreen.click();
                    setTool(t);
                }));
            }
            iy += 20;
        }
        // The list: pieces under captions by their role now (a caption is a String, a piece a Row).
        List<Object> items = new ArrayList<>();
        List<Row> order = new ArrayList<>();
        if (tool == null) items.addAll(rows);
        else {
            Map<String, List<Row>> groups = new LinkedHashMap<>();
            for (PieceSets.Role r : PieceSets.slots(tool)) groups.put(r.name(), new ArrayList<>());
            groups.put(PieceSets.Role.INNER.name(), new ArrayList<>());
            groups.put(PieceSets.Role.SLOPE.name(), new ArrayList<>());
            groups.put(PieceSets.VARIANT, new ArrayList<>());
            groups.put(NOT_IN_SET, new ArrayList<>());
            for (Row r : rows) {
                String c = current(r, setup, fams);
                groups.computeIfAbsent(c.startsWith(PieceSets.VARIANT) ? PieceSets.VARIANT : c, k -> new ArrayList<>()).add(r);
            }
            for (var en : groups.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                String cap = en.getKey().equals(PieceSets.VARIANT) ? Component.translatable("mccitybuilder.sets.variants").getString() : label(en.getKey());
                items.add(cap + (en.getValue().size() > 1 ? " (" + en.getValue().size() + ")" : ""));
                items.addAll(en.getValue());
            }
        }
        int listTop = iy, listBottom = top + h - pad - 11, avail = listBottom - listTop;
        if (pickedPath == null && !rows.isEmpty()) pickedPath = rows.get(0).huts().get(0).path();
        // While the mouse is over the list its order stays as it was (a role change would regroup the rows under the cursor and
        // the next click on the same picker would step another piece); it regroups once the mouse leaves, keeping the picked row in view.
        List<String> keys = new ArrayList<>();
        for (Object o : items) keys.add(o instanceof Row r ? "p:" + r.huts().get(0).path() : "#" + o);
        if (in(mx, my, lx, listTop, lw, avail) && !modal.open()) {
            if (frozenKeys == null) frozenKeys = keys;
            else {
                Map<String, Row> byPath = new java.util.HashMap<>();
                for (Row r : rows) byPath.put(r.huts().get(0).path(), r);
                List<Object> fr = new ArrayList<>();
                for (String k : frozenKeys) {
                    if (k.startsWith("#")) fr.add(k.substring(1));
                    else if (byPath.containsKey(k.substring(2))) fr.add(byPath.get(k.substring(2)));
                }
                items = fr;
                keys = frozenKeys;
            }
        } else {
            frozenKeys = null;
            if (!keys.equals(prevKeys)) scrollTo = pickedPath;
        }
        prevKeys = keys;
        for (Object o : items) if (o instanceof Row r) order.add(r);
        lastOrder = order;
        // The first item shown: scrolls by item, never past what leaves the end in view.
        int acc = 0, maxScroll = Math.max(0, items.size() - 1);
        for (int s = items.size() - 1; s >= 0; s--) {
            acc += items.get(s) instanceof Row ? 24 : 12;
            if (acc > avail) break;
            maxScroll = s;
        }
        if (scrollTo != null) {
            // Bring a row into view (a regrouped list, a previewed piece stepped to with the arrows).
            int at = -1;
            for (int i = 0; i < items.size(); i++) if (items.get(i) instanceof Row r && r.huts().get(0).path().equals(scrollTo)) at = i;
            if (at >= 0) {
                if (at < scroll) scroll = at > 0 && !(items.get(at - 1) instanceof Row) ? at - 1 : at;
                else {
                    int span = 0;
                    for (int j = scroll; j <= at; j++) span += items.get(j) instanceof Row ? 24 : 12;
                    while (span > avail && scroll < at) span -= items.get(scroll++) instanceof Row ? 24 : 12;
                }
            }
            scrollTo = null;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int ry = listTop, shownItems = 0;
        for (int i = scroll; i < items.size(); i++) {
            Object it = items.get(i);
            int ih = it instanceof Row ? 24 : 12;
            if (ry + ih > listBottom) break;
            shownItems++;
            if (it instanceof String cap) {
                caption(g, lx + pad, ry + 2, lw - 2 * pad - 5, cap);
                ry += ih;
                continue;
            }
            Row r = (Row) it;
            Blueprints.Hut first = r.huts().get(0);
            String cur = tool == null ? NOT_IN_SET : current(r, setup, fams);
            boolean sel = first.path().equals(pickedPath), pickerShown = tool != null, notUsed = cur.equals(NOT_IN_SET);
            int pw = pickerShown ? 14 + 2 + 50 + 2 + 14 : 0, px = lx + lw - pad - 5 - pw;
            if (in(mx, my, lx + pad, ry, lw - 2 * pad - 5, 24) || shownPath == null && sel) {
                shownPath = first.path();
                shownRole = tool == null ? null : label(cur);
            }
            if (sel) g.fill(lx + pad, ry, lx + lw - pad - 5, ry + 23, 0x55486FFF);
            anchor("row_" + rows.indexOf(r), lx + pad, ry, lw - 2 * pad - 5, 24);
            // The role's icon tile, the name and its size, the compact picker.
            VanillaUi.slot(g, lx + pad + 1, ry + 1, 20, 20);
            if (notUsed) g.drawString(font, "x", lx + pad + 8, ry + 7, 0xFF606060, false);
            else if (cur.startsWith(PieceSets.VARIANT)) g.drawString(font, "=", lx + pad + 8, ry + 7, 0xFFC4C4C4, false);
            else PanelIcons.draw(g, PanelIcons.forRole(tool, PieceSets.Role.valueOf(cur)), lx + pad + 3, ry + 3, 1, false);
            String name = r.base() + (r.huts().size() > 1 ? " ×" + r.huts().size() : "") + subLabel(first.path());
            int nx = lx + pad + 25, nw = px - nx - 3;
            g.drawString(font, font.plainSubstrByWidth(name, nw), nx, ry + 3, notUsed ? 0xFF606060 : VanillaUi.LABEL, false);
            g.drawString(font, font.plainSubstrByWidth(size(first), nw), nx, ry + 13, 0xFF707070, false);
            hits.add(new Hit(lx + pad, ry, px - lx - pad, 24, () -> {
                pickedPath = first.path();
                frozenKeys = null;
                scrollTo = first.path();
            }));
            if (pickerShown) {
                int by = ry + 3;
                for (int k = 0; k < 2; k++) {
                    int bx = k == 0 ? px : px + 14 + 2 + 50 + 2, d = k == 0 ? -1 : 1;
                    VanillaUi.button(g, bx, by, 14, 16, false, false, in(mx, my, bx, by, 14, 16));
                    g.drawString(font, k == 0 ? "<" : ">", bx + 4, by + 4, 0xFFFFFFFF, true);
                    hits.add(new Hit(bx, by, 14, 16, () -> {
                        PlannerScreen.click();
                        pickedPath = first.path();
                        stepRole(r, d, tool, rows, setup, fams);
                    }));
                    anchor("row_" + (d < 0 ? "prev_" : "next_") + rows.indexOf(r), bx, by, 14, 16);
                }
                int vx = px + 16;
                boolean overV = in(mx, my, vx, by, 50, 16);
                VanillaUi.button(g, vx, by, 50, 16, false, false, overV);
                VanillaUi.buttonLabel(g, font, font.plainSubstrByWidth(shortLabel(label(cur)), 46), vx, by, 50, 16, 0xFFFFFFFF);
                hits.add(new Hit(vx, by, 50, 16, () -> {
                    PlannerScreen.click();
                    pickedPath = first.path();
                    stepRole(r, 1, tool, rows, setup, fams);
                }));
                rightHits.add(new Hit(vx, by, 50, 16, () -> stepRole(r, -1, tool, rows, setup, fams)));
                if (overV) {
                    PieceSets.Role hint = PieceSets.shapeHint(r.base(), shapeOf(first.path(), tool));
                    tip = hint == null ? Component.translatable("mccitybuilder.sets.badge.tip").getString()
                            : Component.translatable("mccitybuilder.sets.shape_hint", label(hint.name())).getString();
                }
            }
            ry += ih;
        }
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("mccitybuilder.sets.row_hint").getString(), lw - 2 * pad), lx + pad + 1, top + h - pad - 8, 0xFF555555, false);
        // A scroll thumb at the list's right edge when it doesn't all fit.
        if (items.size() > shownItems) {
            int tx = lx + lw - pad - 3, th = Math.max(8, avail * shownItems / items.size()), ty = listTop + (avail - th) * scroll / Math.max(1, maxScroll);
            g.fill(tx, listTop, tx + 3, listBottom, 0xFF8B8B8B);
            g.fill(tx, ty, tx + 3, ty + th, 0xFF373737);
        }
        // Never an empty preview: the picked piece even when its row is scrolled out of view, else the first one listed.
        if (shownPath == null && !order.isEmpty()) {
            Row pick = order.get(0);
            for (Row r : order) if (r.huts().get(0).path().equals(pickedPath)) pick = r;
            shownPath = pick.huts().get(0).path();
            shownRole = tool == null ? null : label(current(pick, setup, fams));
        }
        slots(g, rx, top, rw, h, pad, tool, fams, mx, my);
        if (modal.open()) {
            richTitle = null;
            tip = null;
        }
        if (richTitle != null) drawRich(g, mx, my);
        else if (tip != null) g.renderTooltip(font, font.split(Component.literal(tip), 220), mx, my);
    }

    private void anchor(String name, int x, int y, int w, int h) {
        anchors.put(name, new int[]{x, y, w, h});
    }

    /** The rich tooltip: a coloured title, a grey explanation and a white hint, in Minecraft's tooltip look, over everything. */
    private void drawRich(GuiGraphics g, int mx, int my) {
        var body = richBody == null ? List.<net.minecraft.util.FormattedCharSequence>of() : font.split(net.minecraft.network.chat.FormattedText.of(richBody), 190);
        var hint = richHint == null ? List.<net.minecraft.util.FormattedCharSequence>of() : font.split(net.minecraft.network.chat.FormattedText.of(richHint), 190);
        int tw = font.width(richTitle);
        for (var s : body) tw = Math.max(tw, font.width(s));
        for (var s : hint) tw = Math.max(tw, font.width(s));
        int w = tw + 10, h = 8 + 10 + (body.size() + hint.size()) * 10 + (hint.isEmpty() ? 0 : 2);
        int x = Math.max(4, Math.min(mx + 12, width - 4 - w)), y = Math.max(4, Math.min(my + 12, height - 4 - h));
        g.pose().pushPose();
        g.pose().translate(0, 0, 1000);
        g.fill(x + 1, y, x + w - 1, y + h, 0xF0100010);
        g.fill(x, y + 1, x + w, y + h - 1, 0xF0100010);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF3A0F8A);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFF3A0F8A);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF22084E);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF22084E);
        int ty = y + 5;
        g.drawString(font, richTitle, x + 5, ty, richColour, true);
        ty += 10;
        for (var s : body) {
            g.drawString(font, s, x + 5, ty, 0xFFA8A8A8, true);
            ty += 10;
        }
        ty += hint.isEmpty() ? 0 : 2;
        for (var s : hint) {
            g.drawString(font, s, x + 5, ty, 0xFFFFFFFF, true);
            ty += 10;
        }
        g.pose().popPose();
    }

    /** The right panel: the chosen family's role tiles, the 3D preview, and More / Cancel / Save (or, once saved, the joint-check notice). */
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
            anchor("saved_done", ix, iy, iw, 16);
            button(g, ix, iy, iw, Component.translatable("mccitybuilder.sets.done").getString(), mx, my, this::onClose);
            return;
        }
        if (keys.size() > 1) {
            String fl = famKey.substring(famKey.indexOf(':') + 1);
            iy = picker(g, ix, iy, iw, !folder.isEmpty() && fl.startsWith(folder + "/") ? fl.substring(folder.length() + 1) : fl, mx, my, d -> famIndex = Math.floorMod(Math.floorMod(famIndex - 1, keys.size()) + d, keys.size()) + 1);
        }
        iy = caption(g, ix, iy, iw, Component.translatable("mccitybuilder.sets.slots_cap").getString());
        PieceSets.Family fam = famKey == null ? null : fams.get(famKey);
        StringBuilder tiles = new StringBuilder();
        if (tool == null || fam == null) {
            for (var line : font.split(Component.translatable(tool == null ? "mccitybuilder.sets.pick_tool" : "mccitybuilder.sets.no_pieces"), iw)) {
                g.drawString(font, line, ix, iy, 0xFF606060, false);
                iy += 10;
            }
        } else {
            // A grid of role tiles, two across: icon with a green check or an amber "!", the role and its piece (or "Not set").
            List<PieceSets.Role> roles = PieceSets.slots(fam.tool());
            int cw = (iw - 2) / 2;
            for (int i = 0; i < roles.size(); i++) {
                PieceSets.Role r = roles.get(i);
                List<PieceSets.Group> gs = r == PieceSets.Role.STRAIGHT ? fam.straights() : fam.of(r);
                boolean ok = !gs.isEmpty();
                int tx = ix + (i % 2) * (cw + 2), ty = iy + (i / 2) * 26, tw = i % 2 == 1 ? iw - cw - 2 : cw;
                boolean over = in(mx, my, tx, ty, tw, 24);
                VanillaUi.button(g, tx, ty, tw, 24, false, false, over);
                VanillaUi.slot(g, tx + 2, ty + 2, 20, 20);
                PanelIcons.draw(g, PanelIcons.forRole(fam.tool(), r), tx + 4, ty + 4, 1, !ok);
                PanelIcons.draw(g, ok ? "okb" : "warnb", tx + 16, ty, 1, false);
                g.drawString(font, font.plainSubstrByWidth(label(r.name()), tw - 30), tx + 26, ty + 4, 0xFFFFFFFF, true);
                String piece = ok ? gs.get(0).base() + (gs.size() > 1 ? " +" + (gs.size() - 1) : "") : Component.translatable("mccitybuilder.sets.not_set_short").getString();
                g.drawString(font, font.plainSubstrByWidth(piece, tw - 30), tx + 26, ty + 13, ok ? 0xFFD0D0D0 : 0xFFFFC040, false);
                anchor("tile_" + r.name(), tx, ty, tw, 24);
                tiles.append(tiles.length() > 0 ? "," : "").append(r.name()).append(':').append(ok ? "ok" : "missing");
                if (over) {
                    String lbl = label(r.name()), lower = r.name().toLowerCase(Locale.ROOT);
                    if (ok) {
                        richTitle = lbl + ": " + piece;
                        richColour = 0xFFFFFFFF;
                        richBody = Component.translatable("mccitybuilder.sets.about." + lower).getString();
                        richHint = null;
                    } else {
                        richTitle = lbl + ": " + Component.translatable("mccitybuilder.sets.not_set_short").getString().toLowerCase(Locale.ROOT);
                        richColour = 0xFFFFC040;
                        richBody = Component.translatable("mccitybuilder.sets.missing." + lower).getString();
                        richHint = Component.translatable("mccitybuilder.sets.fix_hint", lbl).getString();
                    }
                }
            }
            iy += (roles.size() + 1) / 2 * 26;
        }
        dumpText = "tool=" + tool + " tiles=" + tiles + " rows=" + rowDump();
        // Bottom: More, Cancel and Save; the 3D view of the hovered / picked piece fills the space between.
        int by = y + h - pad - 20, mw = 54, cw2 = 60;
        iy = caption(g, ix, iy + 2, iw, Component.translatable("mccitybuilder.sets.preview").getString());
        preview(g, ix, iy, iw, by - 4 - iy, mx, my);
        boolean overS = in(mx, my, ix + mw + cw2 + 4, by, iw - mw - cw2 - 4, 20);
        // Always the one way out that keeps what is shown (nothing changed: detection's set stands, nothing written).
        btn(g, ix + mw + cw2 + 4, by, iw - mw - cw2 - 4, 20, "okb", Component.translatable(changes.isEmpty() ? "mccitybuilder.sets.done" : "mccitybuilder.sets.save").getString(), true, overS, false);
        anchor("save", ix + mw + cw2 + 4, by, iw - mw - cw2 - 4, 20);
        hits.add(new Hit(ix + mw + cw2 + 4, by, iw - mw - cw2 - 4, 20, () -> {
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
        boolean overM = in(mx, my, ix, by, mw, 20), overC = in(mx, my, ix + mw + 2, by, cw2, 20);
        btn(g, ix, by, mw, 20, null, Component.translatable(more ? "mccitybuilder.sets.less" : "mccitybuilder.sets.more").getString(), more, overM, false);
        anchor("more", ix, by, mw, 20);
        hits.add(new Hit(ix, by, mw, 20, () -> {
            PlannerScreen.click();
            more = !more;
        }));
        if (overM) tip = Component.translatable("mccitybuilder.sets.more.tip").getString();
        btn(g, ix + mw + 2, by, cw2, 20, null, Component.translatable("mccitybuilder.sets.cancel").getString(), false, overC, false);
        anchor("cancel", ix + mw + 2, by, cw2, 20);
        hits.add(new Hit(ix + mw + 2, by, cw2, 20, this::onClose));
    }

    /** @return each row's role now, "index:ROLE" in path order (the dump) */
    private String rowDump() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastRows.size() && lastTool != null; i++) sb.append(i > 0 ? "," : "").append(i).append(':').append(current(lastRows.get(i), lastSetup, lastFams));
        return sb.toString();
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

    /** The shown piece in 3D (drag to turn, wheel to zoom, arrows step the pieces) with its name left and "size . role" right above it. */
    private void preview(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        String key = shownPath == null ? null : "sets|" + pack + "|" + shownPath;
        if (previewKey != null && !previewKey.equals(key)) PieceView.forget(previewKey);
        previewKey = key;
        if (key == null || h < 36) return;
        var bp = PieceCatalog.rotated(pack, shownPath, 0);
        String name = shownPath.substring(shownPath.lastIndexOf('/') + 1).replace(".blueprint", "");
        Blueprints.Hut sh = null;
        for (Row r : lastRows) if (r.huts().get(0).path().equals(shownPath)) sh = r.huts().get(0);
        String right = (sh == null ? "" : size(sh)) + (shownRole == null ? "" : " \u00b7 " + shownRole);
        int rw = font.width(right);
        g.drawString(font, font.plainSubstrByWidth(name, w - rw - 8), x + 1, y, VanillaUi.LABEL, false);
        g.drawString(font, right, x + w - 1 - rw, y, 0xFF707070, false);
        int vy = y + 11, vh = h - 11;
        VanillaUi.slot(g, x, vy, w, vh);
        view = new int[]{x, vy, w, vh};
        if (bp != null) PieceView.render(g, key, bp, x + 1, vy + 1, w - 2, vh - 2);
        // The arrows step through the pieces in the list's order; drawn in front of the 3D view.
        int at = -1;
        for (int i = 0; i < lastOrder.size(); i++) if (lastOrder.get(i).huts().get(0).path().equals(shownPath)) at = i;
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        for (int k = 0; k < 2; k++) {
            int bx = k == 0 ? x + 3 : x + w - 17, by = vy + vh / 2 - 8, d = k == 0 ? -1 : 1;
            boolean off = lastOrder.size() < 2;
            VanillaUi.button(g, bx, by, 14, 16, false, off, in(mx, my, bx, by, 14, 16));
            g.drawString(font, k == 0 ? "<" : ">", bx + 4, by + 4, off ? VanillaUi.INK_DISABLED : 0xFFFFFFFF, !off);
            int from = at;
            if (!off) hits.add(new Hit(bx, by, 14, 16, () -> {
                PlannerScreen.click();
                pickedPath = lastOrder.get(Math.floorMod(from + d, lastOrder.size())).huts().get(0).path();
                scrollTo = pickedPath;
            }));
        }
        g.pose().popPose();
    }

    @Override
    public void removed() {
        if (previewKey != null) PieceView.forget(previewKey);
        previewKey = null;
    }

    private int button(GuiGraphics g, int x, int y, int w, String label, int mx, int my, Runnable run) {
        boolean over = in(mx, my, x, y, w, 16);
        btn(g, x, y, w, 16, null, label, false, over, false);
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
        g.drawString(font, "<", x + 5, y + 4, 0xFFFFFFFF, true);
        g.drawString(font, ">", x + w - 10, y + 4, 0xFFFFFFFF, true);
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
