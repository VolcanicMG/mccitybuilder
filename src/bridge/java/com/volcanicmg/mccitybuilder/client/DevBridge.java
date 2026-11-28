package com.volcanicmg.mccitybuilder.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dev-only control port (runBridge; never in the release jar): drives the game from curl so placements can be checked
 * without a person. Loopback only, and every request needs an {@code X-Bridge} header so a web page can't post to it.
 * Mouse and keys go to the open screen in-process, never through the OS.
 */
@EventBusSubscriber(modid = "mccitybuilder", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class DevBridge {
    private DevBridge() {
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent e) throws IOException {
        String port = System.getProperty("mccitybuilder.bridge");
        if (port == null) return;
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port)), 0);
        http.createContext("/", DevBridge::handle);
        http.start();
    }

    private static void handle(HttpExchange ex) throws IOException {
        int code = 200;
        String out;
        try {
            if (ex.getRequestHeaders().getFirst("X-Bridge") == null) throw new IllegalArgumentException("missing X-Bridge header");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            out = route(ex.getRequestURI().getPath(), body);
        } catch (Exception t) {
            code = 400;
            out = t.toString();
        }
        byte[] b = (out + "\n").getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong recvBytes = new java.util.concurrent.atomic.AtomicLong(), recvPages = new java.util.concurrent.atomic.AtomicLong();

    private static String route(String path, String body) throws Exception {
        String[] a = body.isEmpty() ? new String[0] : body.split("\\s+");
        return switch (path) {
            case "/state" -> client(DevBridge::state);
            case "/cmd" -> client(() -> {
                Minecraft.getInstance().player.connection.sendCommand(body.startsWith("/") ? body.substring(1) : body);
                return "ok";
            });
            case "/planner" -> client(() -> {
                if (a[0].equals("on") != Planner.active()) Planner.toggle();
                return Planner.active() ? "on" : "off (" + actionBar() + ")";
            });
            case "/camera" -> client(() -> camera(a));
            case "/mouse" -> client(() -> mouse(a));
            case "/key" -> client(() -> key(a));
            case "/type" -> client(() -> {
                var s = Minecraft.getInstance().screen;
                for (char c : body.toCharArray()) s.charTyped(c, 0);
                return "ok";
            });
            case "/project" -> client(() -> {
                double[] n = Planner.project(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]));
                var w = Minecraft.getInstance().getWindow();
                return n == null ? "behind camera" : String.format("%.1f %.1f", (n[0] + 1) / 2 * w.getGuiScaledWidth(), (1 - n[1]) / 2 * w.getGuiScaledHeight());
            });
            case "/pick" -> client(() -> {
                var w = Minecraft.getInstance().getWindow();
                var hit = Planner.pick(Double.parseDouble(a[0]) / w.getGuiScaledWidth(), Double.parseDouble(a[1]) / w.getGuiScaledHeight());
                return hit == null ? "miss" : hit.getBlockPos().toShortString() + " " + hit.getDirection();
            });
            case "/pack" -> client(() -> {
                if (!body.isEmpty()) {
                    var m = PlannerScreen.class.getDeclaredMethod("pickPack", String.class);
                    m.setAccessible(true);
                    m.invoke(null, body);
                }
                return PlannerScreen.pack() + " of " + Blueprints.packs();
            });
            case "/window" -> client(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (a[0].equals("full")) {
                    if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                    mc.options.fullscreen().set(true);
                    if (a.length > 1) {
                        mc.options.guiScale().set(Integer.parseInt(a[1]));
                        mc.resizeDisplay();
                    }
                    return "ok";
                }
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                if (a.length > 2) {
                    mc.options.guiScale().set(Integer.parseInt(a[2]));
                    mc.resizeDisplay();
                }
                return "ok";
            });
            // huts|deco|other: the palette's tab as its tab buttons set it; answers the palette (see /palette).
            case "/tab" -> client(() -> {
                if (!(Minecraft.getInstance().screen instanceof PlannerScreen)) return "not the planner";
                setPlanner("decoTab", !a[0].equals("huts"));
                setPlanner("otherTab", a[0].equals("other"));
                setPlanner("category", null);
                setPlanner("search", "");
                setPlanner("decoScroll", 0);
                if (Planner.placing != null || Planner.deco != null) Planner.stopPlacing();
                return palette();
            });
            // text: the palette search field's text (empty clears it); answers the palette.
            case "/search" -> client(() -> {
                setPlanner("search", body);
                return palette();
            });
            // The palette: tab, search, item count and the first names.
            case "/palette" -> client(DevBridge::palette);
            // road|wall|off: the line tool, as the toolbar buttons set it.
            case "/tool" -> client(() -> {
                if (!Planner.active()) return "planner is off";
                if (a[0].equals("off") ? LineView.active() : !a[0].equals(LineView.tool)) LineView.use(a[0].equals("off") ? LineView.tool : a[0]);
                PlanView.linesChanged();
                PlanView.selectedLine = -1;
                return "tool=" + LineView.tool;
            });
            // on|off: the Pieces panel beside the line panel (needs a tool with a family).
            case "/rolepanel" -> client(() -> {
                setPlanner("editorOpen", a[0].equals("on"));
                return "rolepanel=" + a[0] + " family=" + LineView.family();
            });
            // <name>|list: a line / Pieces panel control's box as "x y w h cx cy" (GUI coords; mouse commands take cx cy), or "none".
            case "/anchor" -> client(() -> {
                if (a.length < 1) return "usage: anchor <name>|list";
                if (a[0].equals("list")) return String.join(" ", (Minecraft.getInstance().screen instanceof PieceSetScreen ps0 ? ps0.anchors() : PlannerScreen.anchors).keySet());
                var scr = Minecraft.getInstance().screen;
                int[] r = scr instanceof PieceSetScreen ps ? ps.anchors().get(a[0]) : PlannerScreen.anchors.get(a[0]);
                return r == null ? "none" : r[0] + " " + r[1] + " " + r[2] + " " + r[3] + " " + (r[0] + r[2] / 2) + " " + (r[1] + r[3] / 2);
            });
            // The joint count of the current family three ways: the check list's own verdicts ("ref"), a fresh probe stepped to the end ("probe", timed),
            // and what the line panel worked out (render path "panel"): "ref=bad/total probe=bad/total panel=bad/total failing=[..] <timing>".
            case "/jointcount" -> client(() -> {
                String pk = PlannerScreen.pack();
                var f = PieceCatalog.familyOf(pk, LineView.family());
                if (f == null) return "no family";
                String key = f.tool() + ":" + f.key();
                var ref = JointEditorScreen.reference(pk, key, LineView.level);
                var pr = new JointEditorScreen.Probe(pk, key, LineView.level);
                com.volcanicmg.mccitybuilder.planner.JointCount.Result r = null;
                for (int i = 0; i < 200 && !pr.finished(); i++) r = pr.step();
                if (r == null) r = pr.step();
                var pn = JointCheck.current();
                return "ref=" + ref.bad() + "/" + ref.total() + " minor=" + ref.minor() + (ref.done() ? "" : "+" + ref.pending()) + " probe=" + r.bad() + "/" + r.total() + (r.done() ? "" : "+" + r.pending())
                        + " panel=" + (pn == null ? "none" : pn.bad() + "/" + pn.total() + (pn.done() ? "" : "+" + pn.pending())) + " badge=[" + (pn == null ? "" : pn.badge()) + "] failing=" + r.failing()
                        + String.format(" steps=%d totalMs=%.2f maxStepMs=%.2f", pr.steps, pr.nanos / 1e6, pr.maxNanos / 1e6);
            });
            // info | open|<family>|<level>|<case label part> | count|<family>|<level> | tune|<family>|<level>[|<min match, default 0.6>] (split on |): the current pack's families with their roles and levels, a family's
            // joint count as the check list works it out, or Auto-fix + Save on its failing cases (JointEditorScreen.tune; clean results only).
            case "/inv" -> client(() -> {
                String pk = PlannerScreen.pack();
                String[] p = body.split("\\|");
                if (PieceCatalog.families(pk) == null) return "loading";
                if (p[0].equals("count")) {
                    // The strict count (real + minor problems) first: the ratchet keeps tuning from regressing on either; then the split.
                    var r = JointEditorScreen.reference(pk, p[1], Integer.parseInt(p[2]));
                    return (r.bad() + r.minor()) + "/" + r.total() + (r.done() ? "" : "+" + r.pending()) + " real=" + r.bad() + " minor=" + r.minor() + " oldreal=" + JointEditorScreen.legacyReal(pk, p[1], Integer.parseInt(p[2])) + " " + r.failing() + r.minorNames();
                }
                if (p[0].equals("issues")) return JointEditorScreen.issuesDump(pk, p[1], Integer.parseInt(p[2]));
                if (p[0].equals("open")) return JointEditorScreen.openOn(pk, p[1], Integer.parseInt(p[2]), p[3]) ? "ok" : "no such case";
                if (p[0].equals("tune")) return String.join("\n", JointEditorScreen.tune(pk, p[1], Integer.parseInt(p[2]), p.length > 3 ? Double.parseDouble(p[3]) : 0.6));
                StringBuilder sb = new StringBuilder();
                for (String t : new String[]{"road", "wall"})
                    for (String key : PieceCatalog.familyKeys(pk, t)) {
                        var f = PieceCatalog.familyOf(pk, key);
                        var main = PieceCatalog.groups(pk, f, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.STRAIGHT).get(0);
                        var lv = new java.util.TreeSet<Integer>();
                        if (main.levels()) for (var pc : main.pieces()) lv.add(Math.max(1, pc.number()));
                        else lv.add(1);
                        sb.append(key).append("\tlevels=").append(lv).append("\troles=");
                        for (var r : com.volcanicmg.mccitybuilder.planner.PieceSets.Role.values()) {
                            var all = f.of(r);
                            var chosen = PieceCatalog.groups(pk, f, r);
                            if (all.isEmpty()) continue;
                            sb.append(r).append('=');
                            for (var g : chosen) sb.append(g.base()).append('(').append(g.pieces().size()).append(')').append('/');
                            sb.append("of").append(all.size()).append(' ');
                        }
                        sb.append('\n');
                    }
                return sb.toString();
            });
            // open <x> <z> | type | set <t>: the edit-mode joint menu on the segment end nearest (x, z) (placed over the line panel: it must win clicks there),
            // the selected joint's type, or a type set as the menu's pick does.
            case "/jointmenu" -> client(() -> {
                if (a.length < 1) return "usage: jointmenu open <x> <z>|type|set <t>";
                switch (a[0]) {
                    case "open": {
                        if (a.length < 3 || !LineView.edit) return "needs edit mode and <x> <z>";
                        int[] c = null;
                        int best = 5;
                        for (var l : PlanView.lines)
                            for (int[] e : l.ends()) {
                                int dd = Math.max(Math.abs(e[0] - Integer.parseInt(a[1])), Math.abs(e[1] - Integer.parseInt(a[2])));
                                if (dd < best) {
                                    best = dd;
                                    c = e.clone();
                                }
                            }
                        if (c == null) return "no joint near";
                        LineView.selectedJoint = c;
                        LineView.openJointMenu();
                        int[] ed = PlannerScreen.anchors.get("edit");
                        PlannerScreen.menuAt = ed == null ? null : new int[]{ed[0] + 4, ed[1] + 24};
                        return "ok options=" + LineView.jointOptions.size();
                    }
                    case "type":
                        return LineView.selectedJoint == null ? "none" : String.valueOf(com.volcanicmg.mccitybuilder.planner.Plan.jointType(
                                PlanView.jointTypes.get(com.volcanicmg.mccitybuilder.planner.Plan.cell(LineView.selectedJoint[0], LineView.selectedJoint[1]))));
                    case "set":
                        if (a.length < 2) return "usage: jointmenu set <t>";
                        PlannerScreen.menuAt = null;
                        LineView.pickJoint(Integer.parseInt(a[1]));
                        return "ok";
                    default:
                        return "unknown";
                }
            });
            // The 3D hover tooltip drawn last frame ("title | size"), "" when none.
            case "/tip" -> client(() -> PlannerScreen.tip3dText);
            // The set-up screen's last frame: "tool=.. tiles=ROLE:ok|missing,.. rows=index:ROLE,.." (rows in path order); setupstep <row> <+1|-1> steps a row's role picker.
            case "/setupstate" -> client(() -> Minecraft.getInstance().screen instanceof PieceSetScreen s ? s.dump() : "not the set-up screen");
            case "/setupstep" -> client(() -> {
                if (!(Minecraft.getInstance().screen instanceof PieceSetScreen s)) return "not the set-up screen";
                if (a.length < 2) return "usage: setupstep <row> <+1|-1>";
                return s.stepRow(Integer.parseInt(a[0]), Integer.parseInt(a[1])) ? "ok" : "no such row";
            });
            // preview | role <ROLE> | picks: the role the preview shows, a role's pick in the current set, whether the Picks row is drawn.
            case "/panelstate" -> client(() -> panelState(a));
            // setup|joint: the Set up pieces screen / the joint editor for the pack's first road (else wall) family, as the role panel's buttons open them.
            case "/open" -> client(() -> {
                Minecraft mc = Minecraft.getInstance();
                String pk = PlannerScreen.pack(), fam = null;
                // open setup|joint [road|wall]: the first family of that tool, else the first road (else wall) family.
                for (String t : a.length > 1 ? new String[]{a[1]} : new String[]{"road", "wall"}) {
                    var k = PieceCatalog.familyKeys(pk, t);
                    if (k == null) return "loading";
                    if (fam == null && !k.isEmpty()) fam = k.get(0);
                }
                if (fam == null) return "no families";
                switch (a[0]) {
                    case "setup" -> mc.setScreen(new PieceSetScreen(pk, fam.substring(fam.indexOf(':') + 1), fam));
                    case "joint" -> mc.setScreen(new JointEditorScreen(pk, fam, com.volcanicmg.mccitybuilder.planner.PieceSets.Role.CORNER, -1));
                    default -> throw new IllegalArgumentException("open setup|joint");
                }
                return "ok " + fam;
            });
            // The open screen's Cancel / Close (its onClose: back to the planner).
            case "/close" -> client(() -> {
                var s = Minecraft.getInstance().screen;
                if (s == null) return "no screen";
                s.onClose();
                return "ok";
            });
            // (empty) | discard | keep: the open screen's question box (Modal): its text and buttons, or press its first
            // button (Discard / Save anyway / the first pick) or its last (Keep editing / Cancel); "none" when none is open.
            case "/modal" -> client(() -> {
                var s = Minecraft.getInstance().screen;
                Modal m = null;
                if (s != null)
                    for (var f : s.getClass().getDeclaredFields())
                        if (f.getType() == Modal.class) {
                            f.setAccessible(true);
                            m = (Modal) f.get(s);
                        }
                if (m == null || m.describe() == null) return "none";
                if (a.length == 0 || a[0].isEmpty()) return "open: " + m.describe();
                return switch (a[0]) {
                    case "discard" -> m.press(0) ? "ok" : "none";
                    case "keep" -> m.press(-1) ? "ok" : "none";
                    default -> throw new IllegalArgumentException("modal [discard|keep]");
                };
            });
            // The Set up pieces screen's "Same as…" list for its first row; answers the box as /modal does.
            case "/variantpick" -> client(() -> {
                if (!(Minecraft.getInstance().screen instanceof PieceSetScreen s)) return "not the set-up screen";
                if (!s.openVariantPicker()) return "loading";
                for (var f : PieceSetScreen.class.getDeclaredFields())
                    if (f.getType() == Modal.class) {
                        f.setAccessible(true);
                        return "open: " + ((Modal) f.get(s)).describe();
                    }
                return "none";
            });
            // The joint editor's Auto-fix button; answers the turn it chose ("loading" while the layout isn't ready).
            case "/autofix" -> client(() -> {
                if (!(Minecraft.getInstance().screen instanceof JointEditorScreen s)) return "not the joint editor";
                var lm = JointEditorScreen.class.getDeclaredMethod("layout");
                lm.setAccessible(true);
                Object lay = lm.invoke(s);
                if (lay == null) return "loading";
                for (var m : JointEditorScreen.class.getDeclaredMethods())
                    if (m.getName().equals("autoFix") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        m.invoke(s, lay);
                    }
                var tf = JointEditorScreen.class.getDeclaredField("turn");
                tf.setAccessible(true);
                return "ok turn=" + tf.get(s);
            });
            // The joint editor's chosen orientation ("turn=-1" auto, 4-7 mirrored) and whether its case lays out.
            case "/editorturn" -> client(() -> {
                if (!(Minecraft.getInstance().screen instanceof JointEditorScreen s)) return "not the joint editor";
                var lm = JointEditorScreen.class.getDeclaredMethod("layout");
                lm.setAccessible(true);
                var tf = JointEditorScreen.class.getDeclaredField("turn");
                tf.setAccessible(true);
                int t = (int) tf.get(s);
                return "turn=" + (t < 0 ? -1 : com.volcanicmg.mccitybuilder.planner.PieceLayout.ordinal(t)) + " layout=" + (lm.invoke(s) != null);
            });
            // turn|mirror|show x z: the joint menu's Turn / Mirror on the planned joint at x z (show: nothing changed);
            // answers the stored per-joint orientation and the joint piece's orientation as laid out now.
            case "/jointorient" -> client(() -> {
                int[] c = {Integer.parseInt(a[1]), Integer.parseInt(a[2])};
                // The nearest segment end within 4 cells (a click snaps): the joint node the menu acts on.
                int best = 5;
                for (var l : PlanView.lines)
                    for (int[] e : l.ends()) {
                        int dd = Math.max(Math.abs(e[0] - Integer.parseInt(a[1])), Math.abs(e[1] - Integer.parseInt(a[2])));
                        if (dd < best) {
                            best = dd;
                            c = e.clone();
                        }
                    }
                LineView.selectedJoint = c;
                boolean ok = a[0].equals("show") || LineView.orientSelectedJoint(a[0].equals("mirror")) == null;
                var p = LineView.jointPieceAt(c);
                return (ok ? "ok" : "none") + " at=" + c[0] + "," + c[1] + " orient=" + ord(com.volcanicmg.mccitybuilder.planner.Plan.jointOrient(PlanView.jointTypes.get(com.volcanicmg.mccitybuilder.planner.Plan.cell(c[0], c[1]))))
                        + " piece=" + (p == null ? "none" : ord(p.turns()));
            });
            // Per planned segment: flip, direction and the RotationMirror ordinals its straights / gates are sent with.
            case "/lineturns" -> client(() -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < PlanView.lines.size() && i < PlanView.linePieces.size(); i++) {
                    var l = PlanView.lines.get(i);
                    sb.append(i).append(":flip=").append(l.flip()).append(",dir=")
                            .append(com.volcanicmg.mccitybuilder.planner.PieceLayout.dir(new int[]{l.x0(), l.z0()}, new int[]{l.x1(), l.z1()})).append(",rot=");
                    for (var p : PlanView.linePieces.get(i))
                        if (p.role() == com.volcanicmg.mccitybuilder.planner.PieceSets.Role.STRAIGHT || p.role() == com.volcanicmg.mccitybuilder.planner.PieceSets.Role.GATE)
                            sb.append(PieceCatalog.rotation(p.turns()).ordinal()).append('/');
                    sb.append(' ');
                }
                return sb.toString().trim();
            });
            // Route mode: (no args) its state; on | off; at x z (a click on that column's ground); accept | another | cancel | undo; lines (the planned roads).
            case "/route" -> client(() -> {
                if (a.length == 0) return RouteView.describe();
                switch (a[0]) {
                    case "on", "off" -> {
                        if (RouteView.on != a[0].equals("on")) RouteView.toggle();
                        PlannerScreen.routeTip = false;
                    }
                    case "at" -> {
                        int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]), y = LineView.groundY(x, z);
                        var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(x + 0.5, y, z + 0.5), net.minecraft.core.Direction.UP,
                                new net.minecraft.core.BlockPos(x, y - 1, z), false);
                        if (!RouteView.click(hit)) return "ignored " + RouteView.describe();
                    }
                    case "accept" -> {
                        if (!RouteView.accept()) return "refused flash=" + LineView.flash + " " + RouteView.describe();
                    }
                    case "another" -> RouteView.another();
                    case "cancel" -> RouteView.back();
                    case "undo" -> LineView.undo();
                    case "height" -> LineView.heightMode = Integer.parseInt(a[1]);
                    // The preview's pieces (ys) or the planned roads' pieces (planys) as sorted "x,y,z" anchors.
                    case "ys", "planys" -> {
                        java.util.List<String> out = new java.util.ArrayList<>();
                        if (a[0].equals("ys")) for (var p : LineView.live) out.add(p.anchor().getX() + "," + p.anchor().getY() + "," + p.anchor().getZ());
                        else
                            for (int i = 0; i < PlanView.lines.size() && i < PlanView.linePieces.size(); i++)
                                if (PlanView.lines.get(i).tool().equals("road"))
                                    for (var p : PlanView.linePieces.get(i)) out.add(p.anchor().getX() + "," + p.anchor().getY() + "," + p.anchor().getZ());
                        java.util.Collections.sort(out);
                        return String.join(" ", out);
                    }
                    // walls: the planned wall segments; flag <x> <z>: the search's flag at a cell
                    case "walls" -> {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < PlanView.lines.size(); i++) {
                            var l = PlanView.lines.get(i);
                            if (!l.tool().equals("road")) sb.append(l.x0()).append(',').append(l.z0()).append('>').append(l.x1()).append(',').append(l.z1()).append(" pieces=")
                                    .append(i < PlanView.linePieces.size() ? PlanView.linePieces.get(i).size() : -1).append(' ');
                        }
                        return sb.toString().trim();
                    }
                    case "flag" -> {
                        return String.valueOf(RouteView.flagAt(Integer.parseInt(a[1]), Integer.parseInt(a[2])));
                    }
                    case "lines" -> {
                        StringBuilder sb = new StringBuilder();
                        for (var l : PlanView.lines) if (l.tool().equals("road")) sb.append(l.x0()).append(',').append(l.z0()).append('>').append(l.x1()).append(',').append(l.z1()).append(' ');
                        return sb.toString().trim();
                    }
                    default -> {
                        return "usage: route [on|off|at x z|accept|another|cancel|undo|lines|height n|ys|planys]";
                    }
                }
                return RouteView.describe();
            });
            // The tool's Clear (armed, then confirmed); answers the planned segment count.
            case "/clearlines" -> client(() -> {
                LineView.clearTool();
                LineView.clearTool();
                return "lines=" + PlanView.lines.size();
            });
            case "/screenshot" -> client(() -> shot(a.length > 0 ? a[0] : "shot"));
            case "/blocks" -> blocks(a);
            // x y z item count [item count ...]: items into the colony building at x y z (its own and its racks' inventories).
            case "/stock" -> server(() -> {
                BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var c = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, at);
                var b = c == null ? null : c.getServerBuildingManager().getBuilding(at);
                if (b == null) return "no building";
                var h = b.getItemHandlerCap();
                StringBuilder sb = new StringBuilder();
                for (int i = 3; i + 1 < a.length; i += 2) {
                    // An item with components, as /give takes it: minecraft:netherite_pickaxe[enchantments={levels:{efficiency:5}}].
                    var parsed = new net.minecraft.commands.arguments.item.ItemParser(level.registryAccess()).parse(new com.mojang.brigadier.StringReader(a[i]));
                    var stack = new net.minecraft.commands.arguments.item.ItemInput(parsed.item(), parsed.components()).createItemStack(Integer.parseInt(a[i + 1]), false);
                    var left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(h, stack, false);
                    sb.append(a[i]).append(" left ").append(left.getCount()).append('\n');
                }
                return sb.toString();
            });
            // x y z item count: items into the block's own item handler (a rack, a chest); "count" reads it instead.
            case "/put" -> server(() -> {
                BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var h = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, at, null);
                if (h == null) return "no item handler";
                if (a.length > 4) {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(a[3]));
                    var left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(h, new net.minecraft.world.item.ItemStack(item, Integer.parseInt(a[4])), false);
                    return "left " + left.getCount();
                }
                java.util.Map<String, Integer> n = new java.util.TreeMap<>();
                for (int i = 0; i < h.getSlots(); i++)
                    if (!h.getStackInSlot(i).isEmpty()) n.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.getStackInSlot(i).getItem()).toString(), h.getStackInSlot(i).getCount(), Integer::sum);
                return n.toString();
            });
            // x y z [fill]: the open requests of a building's workers; "fill" puts each requested stack (exact item and
            // components, full count) into the building's inventory.
            case "/requests" -> server(() -> {
                BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var c = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, at);
                var b = c == null ? null : c.getServerBuildingManager().getBuilding(at);
                if (b == null) return "no building";
                boolean fill = a.length > 3 && a[3].equals("fill");
                StringBuilder sb = new StringBuilder();
                java.util.List<Integer> ids = new java.util.ArrayList<>(java.util.List.of(-1));
                for (var cit : c.getCitizenManager().getCitizens()) if (cit.getWorkBuilding() == b) ids.add(cit.getId());
                for (int id : ids)
                    for (var rq : b.getOpenRequests(id)) {
                        var req = rq.getRequest();
                        sb.append(id).append(": ").append(req.getClass().getSimpleName()).append(' ').append(rq.getState()).append(' ');
                        if (req instanceof com.minecolonies.api.colony.requestsystem.requestable.Stack st) {
                            sb.append(st.getStack().getCount() > 0 ? st.getStack().getItem() : "?").append(" x").append(st.getCount());
                            if (fill) {
                                var give = st.getStack().copyWithCount(st.getCount());
                                var left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(b.getItemHandlerCap(), give, false);
                                sb.append(" filled, left ").append(left.getCount());
                            }
                        } else if (req instanceof com.minecolonies.api.colony.requestsystem.requestable.IDeliverable d) {
                            sb.append("count ").append(d.getCount());
                        }
                        sb.append(" | ").append(rq.getShortDisplayString().getString()).append('\n');
                    }
                return sb.toString();
            });
            // x y z: every worker of that building at the top of every skill (99) and full saturation.
            case "/maxworkers" -> server(() -> {
                BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var c = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, at);
                var b = c == null ? null : c.getServerBuildingManager().getBuilding(at);
                if (b == null) return "no building";
                StringBuilder sb = new StringBuilder();
                for (var cit : c.getCitizenManager().getCitizens()) {
                    if (cit.getWorkBuilding() != b) continue;
                    var sk = cit.getCitizenSkillHandler();
                    for (var skill : com.minecolonies.api.entity.citizen.Skill.values()) sk.incrementLevel(skill, Math.max(0, 99 - sk.getLevel(skill)));
                    cit.setSaturation(com.minecolonies.api.colony.ICitizenData.MAX_SATURATION);
                    cit.markDirty(0);
                    sb.append(cit.getName()).append(' ').append(sk.getLevel(com.minecolonies.api.entity.citizen.Skill.Adaptability)).append('/')
                            .append(sk.getLevel(com.minecolonies.api.entity.citizen.Skill.Athletics)).append('\n');
                }
                return sb.toString();
            });
            // The colonies' work orders: id, type, location, path, claimed by. "claim <id> <x y z>" hands one to a builder.
            case "/orders" -> server(() -> {
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                StringBuilder sb = new StringBuilder();
                for (var c : com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies())
                    for (var o : c.getWorkManager().getWorkOrders().values()) {
                        if (a.length >= 5 && a[0].equals("claim") && o.getID() == Integer.parseInt(a[1])) {
                            o.setClaimedBy(new BlockPos(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])));
                            c.getWorkManager().setDirty(true);
                        }
                        sb.append(o.getID()).append(' ').append(o.getWorkOrderType()).append(' ').append(o.getLocation().toShortString()).append(' ')
                                .append(o.getStructurePath()).append(" claimed=").append(o.isClaimed() ? o.getClaimedBy().toShortString() : "-").append('\n');
                    }
                return sb.toString();
            });
            // x y z: a building's container contents (item:count, merged), for move checks.
            case "/contents" -> server(() -> {
                BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var c = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, at);
                var b = c == null ? null : c.getServerBuildingManager().getBuilding(at);
                if (b == null) return "no building";
                java.util.Map<String, Integer> n = new java.util.TreeMap<>();
                for (var h : b.getHandlers())
                    for (int i = 0; i < h.getSlots(); i++) {
                        var st = h.getStackInSlot(i);
                        if (!st.isEmpty()) n.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
                    }
                return n.toString() + " containers=" + b.getContainers().size();
            });
            // The player's colony's buildings (server side): position, type, level, pack, path, rotation, corners.
            case "/buildings" -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                yield server.submit(() -> {
                    StringBuilder sb = new StringBuilder();
                    for (var c : com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies())
                        for (var b : c.getServerBuildingManager().getBuildings().values()) {
                            var te = b.getTileEntity();
                            sb.append(b.getID().toShortString()).append(' ').append(b.getBuildingType().getRegistryName()).append(" lv").append(b.getBuildingLevel())
                                    .append(" pack=").append(b.getStructurePack()).append(" path=").append(b.getBlueprintPath())
                                    .append(" rot=").append(te == null ? "?" : te.getRotationMirror()).append(" brot=").append(b.getRotationMirror())
                                    .append(" corners=").append(b.getCorners() == null ? "-" : b.getCorners().getA().toShortString() + ".." + b.getCorners().getB().toShortString()).append('\n');
                        }
                    return sb.toString();
                }).get();
            }
            // <mode> <pack (spaces as _)> <path> <x y z> <rot>: an InstantBuild request as the planner sends it.
            // ... [level] [from x y z] [fromRot]: also a hut's level, a move's source.
            case "/deco" -> client(() -> {
                BlockPos from = a.length > 10 ? new BlockPos(Integer.parseInt(a[8]), Integer.parseInt(a[9]), Integer.parseInt(a[10])) : BlockPos.ZERO;
                var rms = com.ldtteam.structurize.api.RotationMirror.values();
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.volcanicmg.mccitybuilder.server.InstantBuild.Request(Integer.parseInt(a[0]),
                        new BlockPos(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5])), a.length > 7 ? Integer.parseInt(a[7]) : 0, a[1].replace('_', ' '), a[2],
                        rms[Integer.parseInt(a[6])], 0, from, a.length > 11 ? rms[Integer.parseInt(a[11])] : com.ldtteam.structurize.api.RotationMirror.NONE));
                return "ok";
            });
            // yaw pitch zoom: the 3D views held still at that view (dragging stops the turning); "free" lets go.
            case "/view" -> client(() -> {
                if (a[0].equals("free")) PieceView.dragging = false;
                else {
                    PieceView.yaw = Float.parseFloat(a[0]);
                    PieceView.pitch = Float.parseFloat(a[1]);
                    PieceView.zoom = Float.parseFloat(a[2]);
                    PieceView.dragging = true;
                }
                return "ok";
            });
            // Multiplayer checks from the client's side: its colony view, its known decorations / others' pieces, its chat.
            case "/cview" -> client(() -> {
                var c = Planner.colony();
                if (c == null) return "no colony (planner closed?)";
                StringBuilder sb = new StringBuilder();
                for (var b : c.getClientBuildingManager().getBuildings().values())
                    sb.append("B ").append(b.getPosition().toShortString()).append(' ').append(b.getBuildingType().getRegistryName()).append(" lv").append(b.getBuildingLevel()).append('\n');
                for (var o : c.getWorkOrders())
                    sb.append("O ").append(o.getID()).append(' ').append(o.getWorkOrderType()).append(' ').append(o.getLocation().toShortString()).append(' ').append(o.getStructurePath()).append('\n');
                for (var d : Planner.decos()) sb.append(d.piece() ? "P " : "D ").append(d.pack()).append(' ').append(d.path()).append(' ').append(d.pos().toShortString()).append(" rot=").append(d.rot()).append('\n');
                sb.append("otherPlaced=").append(PlanView.otherPlaced.size()).append(" lines=").append(PlanView.lines.size()).append('\n');
                return sb.toString();
            });
            // Shared ghosts and plans: what this client knows of mates' (and what it last sent).
            case "/others" -> client(Others::dump);
            case "/far" -> client(FarBuildings::dump);
            // Leave the server the way the pause menu does (same JVM, so client caches carry over to the next join).
            case "/disconnect" -> client(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) mc.level.disconnect();
                mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen());
                return "ok";
            });
            // host:port: join a server as the multiplayer screen does.
            case "/connect" -> client(() -> {
                Minecraft mc = Minecraft.getInstance();
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(a[0]),
                        new net.minecraft.client.multiplayer.ServerData("test", a[0], net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
                return "ok";
            });
            // The plan as loaded on this client: entries and lines.
            // (nothing)|open|close|all|roads|huts|refresh|mode <BUILDERS|BUILDERS_FREE|INSTANT>: the Materials state (totals, rows, stock), or its panel / tab / a stock request / the creative build mode.
            case "/materials" -> client(() -> {
                if (a.length > 0) switch (a[0]) {
                    case "open" -> MaterialsView.open = true;
                    case "close" -> MaterialsView.open = false;
                    case "all", "roads", "huts" -> MaterialsView.tab = com.volcanicmg.mccitybuilder.planner.Materials.Tab.valueOf(a[0].toUpperCase());
                    case "refresh" -> MaterialsView.forceRefresh();
                    case "mode" -> com.volcanicmg.mccitybuilder.Config.BUILD_MODE.set(com.volcanicmg.mccitybuilder.Config.BuildMode.valueOf(a[1]));
                    default -> {
                        return "usage: materials [open|close|all|roads|huts|refresh|mode X]";
                    }
                }
                StringBuilder sb = new StringBuilder(MaterialsView.dump());
                for (var r : MaterialsView.view(MaterialsView.tab).rows()) sb.append('\n').append(r.item()).append(" need=").append(r.need()).append(" have=").append(r.have()).append(" missing=").append(r.missing());
                return sb.toString();
            });
            case "/plan" -> client(() -> "entries=" + PlanView.entries.size() + " lines=" + PlanView.lines.size() + " on=" + PlanView.on);
            // hutId x y z: a hut ghost dropped at that anchor (as if placed and clicked once), shared with mates.
            case "/ghost" -> client(() -> {
                if (a[0].equals("off")) Planner.stopPlacing();
                else {
                    Planner.startPlacing(BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(a[0])));
                    Planner.place.follow(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                    Planner.place.drop();
                }
                return "ok";
            });
            // hutId|- pack (spaces as _) path level x y z: an entry added to this player's plan (box = the anchor block); "clear" clears it.
            case "/planentry" -> client(() -> {
                if (a[0].equals("clear")) PlanView.clear();
                else {
                    int x = Integer.parseInt(a[4]), y = Integer.parseInt(a[5]), z = Integer.parseInt(a[6]);
                    var es = new java.util.ArrayList<>(PlanView.entries);
                    es.add(new com.volcanicmg.mccitybuilder.planner.Plan.Entry(a[0].equals("-") ? null : a[0], a[1].replace('_', ' '), a[2], Integer.parseInt(a[3]),
                            x, y, z, 0, x, y, z, x, y, z));
                    PlanView.setEntries(es);
                    // Saves (and so shares) the plan.
                    PlanView.setLines(PlanView.lines);
                }
                return "entries=" + PlanView.entries.size();
            });
            // n: the last n chat lines (command feedback included), newest last.
            case "/chat" -> client(() -> {
                var f = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
                f.setAccessible(true);
                java.util.List<?> all = (java.util.List<?>) f.get(Minecraft.getInstance().gui.getChat());
                StringBuilder sb = new StringBuilder();
                for (int i = Math.min(all.size(), a.length > 0 ? Integer.parseInt(a[0]) : 10) - 1; i >= 0; i--)
                    sb.append(((net.minecraft.client.GuiMessage) all.get(i)).content().getString()).append('\n');
                return sb.toString();
            });
            // pack (spaces as _) path x y z rot: a survival hut placement as the planner sends it (the hut item must be in the inventory).
            case "/survivalplace" -> client(() -> {
                new com.ldtteam.structurize.network.messages.BuildToolPlacementMessage(com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.Survival,
                        "minecolonies", a[0].replace('_', ' '), a[1], new BlockPos(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])),
                        com.ldtteam.structurize.api.RotationMirror.values()[Integer.parseInt(a[5])]).sendToServer();
                return "ok";
            });
            // x y z: the planner's survival Build request for the building there (MineColonies' BuildRequestMessage).
            case "/buildreq" -> client(() -> {
                var b = Planner.colony() == null ? null : Planner.colony().getClientBuildingManager().getBuilding(new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])));
                if (b == null) return "no building";
                Planner.requestBuild(b, com.minecolonies.core.network.messages.server.colony.building.BuildRequestMessage.Mode.BUILD);
                return "ok";
            });
            // mode pack (spaces as _) path x y z rot: a selected decoration's / piece's action as the selection panel sends it.
            case "/senddeco" -> client(() -> Planner.sendDeco(Integer.parseInt(a[0]), new Planner.Deco(a[1].replace('_', ' '), a[2],
                    new BlockPos(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5])), Integer.parseInt(a[6]))) ? "sent" : "no server part");
            // n [crops%]: n synthetic road / wall records for colony 1 on a 2-block grid around its centre (perf checks; the world copy is throwaway).
            case "/synthrecs" -> server(() -> {
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var c = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByWorld(1, level);
                var recs = com.volcanicmg.mccitybuilder.server.BuiltRecords.of(level);
                int n = Integer.parseInt(a[0]), cropPct = a.length > 1 ? Integer.parseInt(a[1]) : 10;
                String ph = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("Colonial".getBytes(StandardCharsets.UTF_8)), 0, 4);
                BlockPos ctr = c.getCenter();
                for (int i = 0; i < n; i++) {
                    BlockPos p = ctr.offset((i % 100) * 2 - 100, 0, (i / 100) * 2 - 100);
                    boolean crop = i % 100 < cropPct;
                    String pack = crop ? "City Builder Crops" : "Colonial", bp = crop ? "walls/long2_crop_" + ph + "_0badf00d_z9_1.blueprint" : "walls/short2.blueprint";
                    recs.add(1, pack, bp, p, i % 4, new int[]{p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 4, p.getZ() + 1}, new java.util.UUID(0, 1));
                }
                return "added " + n;
            });
            // Times one DecoSync flush of colony 1 (server ms), then reports what the planner received since the last call.
            case "/flushms" -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                double ms = server.submit(() -> {
                    try {
                        var level = server.overworld();
                        var ch = com.volcanicmg.mccitybuilder.server.DecoSync.class.getDeclaredMethod("changed", net.minecraft.server.level.ServerLevel.class, int.class);
                        var fl = com.volcanicmg.mccitybuilder.server.DecoSync.class.getDeclaredMethod("flush");
                        ch.setAccessible(true);
                        fl.setAccessible(true);
                        // What the planner receives, counted as encoded (an integrated server hands the objects over as they are).
                        com.volcanicmg.mccitybuilder.server.DecoSync.handler(d -> {
                            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess(),
                                    net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
                            com.volcanicmg.mccitybuilder.server.DecoSync.Decos.CODEC.encode(buf, d);
                            recvBytes.addAndGet(buf.readableBytes());
                            recvPages.incrementAndGet();
                            Planner.onDecos(d);
                        });
                        recvBytes.set(0);
                        recvPages.set(0);
                        ch.invoke(null, level, 1);
                        long t = System.nanoTime();
                        fl.invoke(null);
                        return (System.nanoTime() - t) / 1e6;
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                }).get();
                Thread.sleep(2100);
                yield String.format("flush %.2f ms, received %d pages %d bytes", ms, recvPages.get(), recvBytes.get());
            }
            // x y z: times the planner's hover pick there (client ms, 20 runs averaged), and the frame rate.
            case "/pickms" -> client(() -> {
                BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                long t = System.nanoTime();
                Object hit = null;
                for (int i = 0; i < 20; i++) hit = Planner.pickAt(at);
                return String.format("pickAt %.3f ms -> %s, fps %d", (System.nanoTime() - t) / 20e6, hit, Minecraft.getInstance().getFps());
            });
            // The plan / line outlines pass (others' grey pieces included): CPU ms per frame, 100 runs into a sink that drops the vertices.
            case "/renderms" -> client(() -> {
                var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                long[] verts = {0};
                com.mojang.blaze3d.vertex.VertexConsumer sink = new com.mojang.blaze3d.vertex.VertexConsumer() {
                    public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
                        verts[0]++;
                        return this;
                    }

                    public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
                        return this;
                    }

                    public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
                        return this;
                    }

                    public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
                        return this;
                    }

                    public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
                        return this;
                    }

                    public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
                        return this;
                    }
                };
                long t = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    var pose = new com.mojang.blaze3d.vertex.PoseStack();
                    pose.translate(-cam.x, -cam.y, -cam.z);
                    PlanView.renderWorld(pose, sink);
                }
                double sinkMs = (System.nanoTime() - t) / 100e6;
                // The same into a real line buffer (what a frame writes before the draw), dropped unbuilt.
                long t2 = System.nanoTime();
                for (int i = 0; i < 20; i++)
                    try (var bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 20)) {
                        var bb = new com.mojang.blaze3d.vertex.BufferBuilder(bytes, com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES,
                                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL);
                        var pose = new com.mojang.blaze3d.vertex.PoseStack();
                        pose.translate(-cam.x, -cam.y, -cam.z);
                        PlanView.renderWorld(pose, bb);
                    }
                return String.format("plan outlines %.3f ms/frame (sink), %.3f ms/frame (line buffer), %d vertices/frame, otherPlaced=%d", sinkMs,
                        (System.nanoTime() - t2) / 20e6, verts[0] / 100, PlanView.otherPlaced.size());
            });
            // Times the planner's next line layout (client ms), forced now.
            case "/relayoutms" -> client(() -> {
                PlanView.relayout();
                long t = System.nanoTime();
                PlanView.refreshLines();
                return String.format("relayout %.2f ms, otherPlaced=%d (grey %d), lines=%d", (System.nanoTime() - t) / 1e6, PlanView.otherPlaced.size(), PlanView.greyPlaced.size(), PlanView.lines.size());
            });
            // The colony's citizens as the overlay sees them: view entity id, what that id is now, the loaded citizen entity with that civilian id, distance, and marker.
            case "/citizens" -> client(() -> {
                var mc = Minecraft.getInstance();
                var c = Planner.colony();
                if (c == null) return "no colony (planner closed?)";
                java.util.Map<Integer, net.minecraft.world.entity.Entity> byCiv = new java.util.HashMap<>();
                StringBuilder sb = new StringBuilder(), other = new StringBuilder();
                for (var e : mc.level.entitiesForRendering())
                    if (e instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen ce) {
                        if (ce.getCitizenColonyHandler().getColonyId() == c.getID() && !(ce instanceof com.minecolonies.core.entity.visitor.VisitorCitizen)) byCiv.put(ce.getCivilianID(), e);
                        else other.append("  other ").append(e.getClass().getSimpleName()).append(" civ=").append(ce.getCivilianID()).append(" colony=")
                                .append(ce.getCitizenColonyHandler().getColonyId()).append(" name=").append(e.getName().getString()).append(" at ").append(e.blockPosition().toShortString()).append('\n');
                    }
                java.util.Map<Integer, String> marked = new java.util.HashMap<>();
                for (var m : OverlayView.markers) if (m.citizen() != null) marked.put(m.citizen().getId(), m.far() ? "far" : "live");
                for (var cit : c.getCitizens().values()) {
                    var byId = mc.level.getEntity(cit.getEntityId());
                    var live = byCiv.get(cit.getId());
                    sb.append(cit.getId()).append(' ').append(cit.getName()).append(" viewEntity=").append(cit.getEntityId()).append(" -> ")
                            .append(byId == null ? "none" : byId.getClass().getSimpleName() + (byId instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen ce ? " civ=" + ce.getCivilianID() : ""))
                            .append(" | live=").append(live == null ? "none" : live.getId() + " at " + live.blockPosition().toShortString() + " d=" + (int) live.distanceTo(mc.player))
                            .append(" marker=").append(marked.getOrDefault(cit.getId(), "none")).append(" home=").append(cit.getHomeBuilding()).append(" work=").append(cit.getWorkBuilding())
                            .append(" sat=").append(cit.getSaturation()).append(" happy=").append(cit.getHappiness())
                            .append(" asks=").append(cit.getOrderedInteractions().stream().map(h -> h.getPriority() + ":" + (h.getInquiry() == null ? "-" : h.getInquiry().getString())).toList()).append('\n');
                }
                return sb + other.toString();
            });
            // "hut x y z" / "citizen <civilian id>": MineColonies' own open-inventory message, as its windows send it (the
            // planner's gate bypassed), then what screen came up a moment later.
            case "/openinv" -> {
                String sent = client(() -> {
                    var c = Planner.colony();
                    if (c == null) return "no colony";
                    if (a[0].equals("hut")) {
                        var b = c.getClientBuildingManager().getBuilding(new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                        if (b == null) return "no building";
                        var msg = new com.minecolonies.core.network.messages.server.colony.OpenInventoryMessage(b);
                        // "hut x y z rx ry rz": the same message aimed at another block (a rack), as a modified client could send it.
                        if (a.length > 6) {
                            var f = msg.getClass().getDeclaredField("tePos");
                            f.setAccessible(true);
                            f.set(msg, new BlockPos(Integer.parseInt(a[4]), Integer.parseInt(a[5]), Integer.parseInt(a[6])));
                        }
                        msg.sendToServer();
                    } else {
                        var cit = c.getCitizen(Integer.parseInt(a[1]));
                        if (cit == null) return "no citizen";
                        new com.minecolonies.core.network.messages.server.colony.OpenInventoryMessage(c, cit.getName(), cit.getEntityId()).sendToServer();
                    }
                    return "sent";
                });
                Thread.sleep(1500);
                yield sent + "; screen now " + client(() -> Minecraft.getInstance().screen == null ? "none" : Minecraft.getInstance().screen.getClass().getSimpleName());
            }
            // x y z: the server opens the rack there for this (singleplayer) player as MineColonies' rack block does; then the screen.
            case "/openrack" -> {
                String r = server(() -> {
                    var sp = Minecraft.getInstance().getSingleplayerServer();
                    var p = sp.getPlayerList().getPlayers().get(0);
                    BlockPos at = new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                    if (!(p.level().getBlockEntity(at) instanceof com.minecolonies.api.tileentities.AbstractTileEntityRack rack)) return "no rack";
                    var other = rack.getOtherChest();
                    p.openMenu(rack, buf -> {
                        buf.writeBlockPos(at);
                        buf.writeBlockPos(other == null ? BlockPos.ZERO : other.getBlockPos());
                    });
                    return "opened";
                });
                Thread.sleep(1200);
                yield r + "; screen now " + client(() -> Minecraft.getInstance().screen == null ? "none" : Minecraft.getInstance().screen.getClass().getSimpleName());
            }
            // civilian id: moves the mouse onto that citizen's marker as last drawn (GUI coords), or says it isn't drawn.
            case "/hovercitizen" -> client(() -> {
                var s = Minecraft.getInstance().screen;
                for (var m : OverlayView.markers)
                    if (m.citizen() != null && m.citizen().getId() == Integer.parseInt(a[0])) {
                        double[] ndc = Planner.project(m.pos().x, m.pos().y, m.pos().z);
                        if (ndc == null) return "behind camera";
                        long sx = Math.round((ndc[0] + 1) / 2 * s.width), sy = Math.round((1 - ndc[1]) / 2 * s.height);
                        // Snap to the nearest marker as last drawn (the projection here can be off by a frame's movement).
                        var f = s.getClass().getDeclaredField("drawnMarkers");
                        f.setAccessible(true);
                        @SuppressWarnings("unchecked") java.util.List<int[]> drawn = (java.util.List<int[]>) f.get(s);
                        long best = Long.MAX_VALUE, bx = sx, by = sy;
                        for (int[] d : drawn) {
                            long dd = (d[1] - sx) * (d[1] - sx) + (d[2] - sy) * (d[2] - sy);
                            if (dd < best) {
                                best = dd;
                                bx = d[1];
                                by = d[2];
                            }
                        }
                        sx = bx;
                        sy = by;
                        mouse(new String[]{"move", String.valueOf(sx), String.valueOf(sy)});
                        return sx + " " + sy;
                    }
                return "no marker";
            });
            // on|off: the citizen card as with Shift held.
            case "/cardfull" -> client(() -> {
                CitizenCard.showFull = a[0].equals("on");
                return "ok";
            });
            case "/states" -> client(() -> states(a[0].replace('_', ' '), a[1], Integer.parseInt(a[2])));
            case "/cropcheck" -> cropCheck(a[0].replace('_', ' '), a[1]);
            default -> throw new IllegalArgumentException("paths: /state /cmd /planner /camera /mouse /key /type /project /pick /pack /window /screenshot /blocks /cropcheck");
        };
    }

    private static String client(Callable<String> c) throws Exception {
        CompletableFuture<String> f = Minecraft.getInstance().submit(() -> {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return f.get(20, TimeUnit.SECONDS);
    }

    /** The line panel's state for {@code /panelstate}: {@code picks}, {@code preview}, {@code role <ROLE>}. */
    private static String panelState(String[] a) throws ReflectiveOperationException {
        if (a.length < 1) return "usage: panelstate picks|preview|layout|has <ROLE>|role <ROLE>|setrole <ROLE> <auto|value>";
        var pk = PlannerScreen.pack();
        var f = PieceCatalog.familyOf(pk, LineView.family());
        String key = f == null ? null : f.tool() + ":" + f.key();
        com.volcanicmg.mccitybuilder.planner.PieceSets.Role role = null;
        if (a.length > 1) {
            try {
                role = com.volcanicmg.mccitybuilder.planner.PieceSets.Role.valueOf(a[1]);
            } catch (IllegalArgumentException e) {
                return "unknown role " + a[1];
            }
        }
        switch (a[0]) {
            case "picks":
                return String.valueOf(PlannerScreen.anchors.containsKey("pick_segment"));
            case "dragging":
                return String.valueOf(PieceView.dragging);
            case "layout":
                // x y w h queueBottom previewHeight tier, and the GUI size
                var d = PlannerScreen.layoutDbg;
                var w = Minecraft.getInstance().getWindow();
                return d[0] + " " + d[1] + " " + d[2] + " " + d[3] + " " + d[4] + " " + d[5] + " " + d[6] + " gui=" + w.getGuiScaledWidth() + "x" + w.getGuiScaledHeight();
            case "preview": {
                var pf = PlannerScreen.class.getDeclaredField("previewRole");
                pf.setAccessible(true);
                return String.valueOf(pf.get(null));
            }
            case "has":
                if (role == null) return "usage: panelstate has <ROLE>";
                return String.valueOf(f != null && !PieceCatalog.groups(pk, f, role).isEmpty());
            case "role": {
                if (role == null || key == null) return "no family or role";
                var o = PieceCatalog.overrides(pk).get(key + "|" + role.name());
                return "role=" + a[1] + " pick=" + (o == null ? "auto" : o);
            }
            case "setrole":
                // a[2]: "auto" clears the pick, anything else is the group base name or "-"
                if (role == null || key == null || a.length < 3) return "usage: panelstate setrole <ROLE> <auto|value>";
                PieceCatalog.override(pk, key, role, a[2].equals("auto") ? null : a[2]);
                PlanView.linesChanged();
                return "ok";
            default:
                return "unknown";
        }
    }

    /** Sets a private static of the planner screen (its palette / panel state). */
    private static void setPlanner(String name, Object v) throws ReflectiveOperationException {
        var f = PlannerScreen.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    /** @return the palette's tab, search, item count and first names, read from the screen's own lists */
    private static String palette() throws ReflectiveOperationException {
        var df = PlannerScreen.class.getDeclaredMethod("decoFolder");
        df.setAccessible(true);
        boolean deco = df.invoke(null) != null;
        var lm = PlannerScreen.class.getDeclaredMethod(deco ? "decos" : "huts");
        lm.setAccessible(true);
        var sf = PlannerScreen.class.getDeclaredField("search");
        sf.setAccessible(true);
        var of = PlannerScreen.class.getDeclaredField("otherTab");
        of.setAccessible(true);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Object o : (java.util.List<?>) lm.invoke(null))
            names.add(o instanceof Blueprints.Hut h ? h.blueprint().getFileName() : ((net.minecraft.world.level.block.Block) o).getName().getString());
        return "tab=" + (!deco ? "huts" : (boolean) of.get(null) ? "other" : "deco") + " search=\"" + sf.get(null) + "\" count=" + names.size() + " first=" + names.subList(0, Math.min(5, names.size()));
    }

    private static String state() {
        Minecraft mc = Minecraft.getInstance();
        var w = mc.getWindow();
        var sb = new StringBuilder();
        sb.append("screen=").append(mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        sb.append(" gui=").append(w.getGuiScaledWidth()).append('x').append(w.getGuiScaledHeight()).append('@').append(w.getGuiScale());
        if (mc.player != null) sb.append(" player=").append(mc.player.blockPosition().toShortString());
        sb.append(" planner=").append(Planner.active());
        if (Planner.active()) sb.append(" pack=").append(PlannerScreen.pack()).append(" family=").append(LineView.family());
        if (Planner.active() && Planner.drone != null) {
            var d = Planner.drone;
            sb.append(String.format(" camera=%.1f %.1f %.1f yaw=%.0f pitch=%.0f", d.x, d.altitude, d.z, d.yaw, d.pitch));
        }
        if (Planner.isPlacing()) sb.append(" placing=").append(Planner.placingName()).append(" anchor=").append(Planner.anchor());
        sb.append(" bar=").append(actionBar());
        return sb.toString();
    }

    private static String actionBar() {
        try {
            var f = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
            f.setAccessible(true);
            Object m = f.get(Minecraft.getInstance().gui);
            return m == null ? "" : "\"" + ((net.minecraft.network.chat.Component) m).getString() + "\"";
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** {@code x z altitude [yaw pitch]}: puts the planner camera there (world coords, altitude is the camera's y). */
    /** @return an orientation as its RotationMirror ordinal (4-7 mirrored), -1 stays */
    private static int ord(int o) {
        return o < 0 ? -1 : com.volcanicmg.mccitybuilder.planner.PieceLayout.ordinal(o);
    }

    private static String camera(String[] a) {
        var d = Planner.drone;
        if (!Planner.active() || d == null) return "planner is off";
        d.x = Double.parseDouble(a[0]);
        d.z = Double.parseDouble(a[1]);
        d.altitude = d.altitudeTarget = Double.parseDouble(a[2]);
        if (a.length > 3) d.yaw = Float.parseFloat(a[3]);
        if (a.length > 4) d.pitch = Float.parseFloat(a[4]);
        return "ok";
    }

    /**
     * GUI-scaled coords, like screenshots divided by the gui scale: {@code move x y}, {@code click x y [button]},
     * {@code press x y [button]}, {@code drag x y [button]}, {@code release x y [button]}, {@code scroll x y amount}.
     */
    private static String mouse(String[] a) throws ReflectiveOperationException {
        Minecraft mc = Minecraft.getInstance();
        var s = mc.screen;
        double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]);
        int button = a.length > 3 && !a[0].equals("scroll") ? Integer.parseInt(a[3]) : 0;
        double scale = mc.getWindow().getGuiScale();
        double px = x * scale, py = y * scale;
        double lastX = field("xpos") / scale, lastY = field("ypos") / scale;
        setField("xpos", px);
        setField("ypos", py);
        if (s == null) return "no screen";
        switch (a[0]) {
            case "move" -> s.mouseMoved(x, y);
            case "click" -> {
                s.mouseMoved(x, y);
                s.mouseClicked(x, y, button);
                s.mouseReleased(x, y, button);
            }
            case "press" -> {
                s.mouseMoved(x, y);
                s.mouseClicked(x, y, button);
            }
            case "drag" -> s.mouseDragged(x, y, button, x - lastX, y - lastY);
            case "release" -> s.mouseReleased(x, y, button);
            case "scroll" -> s.mouseScrolled(x, y, 0, Double.parseDouble(a[3]));
            default -> throw new IllegalArgumentException("mouse move|click|press|drag|release|scroll");
        }
        return "ok";
    }

    private static double field(String name) throws ReflectiveOperationException {
        var f = net.minecraft.client.MouseHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(Minecraft.getInstance().mouseHandler);
    }

    private static void setField(String name, double v) throws ReflectiveOperationException {
        var f = net.minecraft.client.MouseHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(Minecraft.getInstance().mouseHandler, v);
    }

    /** {@code <glfw key name like key.keyboard.r | escape | ...> [mods]}: a press and release on the open screen. */
    private static String key(String[] a) {
        String name = a[0].contains(".") ? a[0] : "key.keyboard." + a[0];
        int k = InputConstants.getKey(name).getValue();
        int mods = a.length > 1 ? Integer.parseInt(a[1]) : 0;
        var s = Minecraft.getInstance().screen;
        if (s == null) return "no screen";
        s.keyPressed(k, 0, mods);
        s.keyReleased(k, 0, mods);
        return "ok " + k;
    }

    private static String shot(String name) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath().resolve("screenshots").resolve("bridge");
        Files.createDirectories(dir);
        Path p = dir.resolve(name.replaceAll("[^A-Za-z0-9_-]", "_") + ".png");
        try (var img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            img.writeToFile(p);
        }
        return p.toAbsolutePath().toString();
    }

    /**
     * {@code x1 y1 z1 x2 y2 z2 [states]}: the server's blocks in that box, top layer first; rows run north to south,
     * columns west to east, {@code .} is air. With {@code states} the legend keeps each block's properties.
     */
    /**
     * {@code <pack (spaces as _)> <straight path>}: every window of the straight along its run, every turn: the crop
     * read back from its file has the primary offset it had in memory, also once Structurize's cached one is dropped
     * (an edit); the anchor in or outside the window. @return "ok N" or the first mismatch
     */
    private static String cropCheck(String pack, String path) throws Exception {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return "no integrated server";
        return server.submit(() -> {
            var level = server.overworld();
            var src = com.ldtteam.structurize.storage.StructurePacks.getBlueprint(pack, path, level.registryAccess());
            if (src == null) return "no blueprint";
            int axis = src.getSizeX() >= src.getSizeZ() ? 0 : 1, run = axis == 0 ? src.getSizeX() : src.getSizeZ(), n = 0;
            for (int len = 1; len < run; len++)
                for (int from = 0; from + len <= run; from++)
                    for (int t = 0; t < 4; t++) {
                        int[] w = {axis, from, len};
                        var c = com.volcanicmg.mccitybuilder.server.Crops.crop(com.ldtteam.structurize.storage.StructurePacks.getBlueprint(pack, path, level.registryAccess()), w);
                        var tag = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(c);
                        var back = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(tag, level.registryAccess());
                        var edited = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(tag, level.registryAccess());
                        BlockPos far = new BlockPos(0, edited.getSizeY() - 1, 0);
                        edited.addBlockState(far, edited.getBlockState(far));
                        var rm = PieceCatalog.rotation(t);
                        c.setRotationMirror(rm, level);
                        back.setRotationMirror(rm, level);
                        edited.setRotationMirror(rm, level);
                        if (!c.getPrimaryBlockOffset().equals(back.getPrimaryBlockOffset()) || !c.getPrimaryBlockOffset().equals(edited.getPrimaryBlockOffset()))
                            return "mismatch " + java.util.Arrays.toString(w) + " turn " + t + ": memory " + c.getPrimaryBlockOffset() + " file " + back.getPrimaryBlockOffset()
                                    + " edited " + edited.getPrimaryBlockOffset();
                        n++;
                    }
            return "ok " + n + " (anchor " + src.getPrimaryBlockOffset() + ", size " + src.getSizeX() + "x" + src.getSizeZ() + ")";
        }).get();
    }

    /**
     * {@code <pack (spaces as _)> <path> <turns>}: a piece's wall / fence / pane / stair blocks as stored in its turned
     * blueprint and as the help scenes bake them ({@link PieceView#connected}), one line each where they differ.
     */
    private static String states(String pack, String path, int turns) {
        var bp = PieceCatalog.rotated(pack, path, turns);
        if (bp == null) return "loading";
        var con = PieceView.connected(bp);
        StringBuilder sb = new StringBuilder();
        int sx = bp.getSizeX(), sz = bp.getSizeZ(), n = 0, diff = 0;
        for (int y = 0; y < bp.getSizeY(); y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    var st = bp.getBlockState(new BlockPos(x, y, z));
                    var b = st.getBlock();
                    if (!(b instanceof net.minecraft.world.level.block.WallBlock || b instanceof net.minecraft.world.level.block.CrossCollisionBlock
                            || b instanceof net.minecraft.world.level.block.StairBlock)) continue;
                    n++;
                    var c = con[(y * sz + z) * sx + x];
                    if (c.equals(st)) continue;
                    diff++;
                    sb.append(x).append(',').append(y).append(',').append(z).append(' ').append(st).append("  ->  ").append(c).append('\n');
                }
        return n + " connecting blocks, " + diff + " changed\n" + sb;
    }

    /** Runs on the integrated server thread and waits for its answer. */
    private static String server(java.util.concurrent.Callable<String> c) throws Exception {
        return Minecraft.getInstance().getSingleplayerServer().submit(() -> {
            try {
                return c.call();
            } catch (Exception e) {
                return "error " + e;
            }
        }).get();
    }

    private static String blocks(String[] a) throws Exception {
        var server = Minecraft.getInstance().getSingleplayerServer();
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(a[i]);
        boolean states = a.length > 6;
        BlockPos lo = new BlockPos(Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]));
        BlockPos hi = new BlockPos(Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
        long cells = (long) (hi.getX() - lo.getX() + 1) * (hi.getY() - lo.getY() + 1) * (hi.getZ() - lo.getZ() + 1);
        if (cells > 200_000) return "box too big: " + cells;
        // On a server: the blocks this client has loaded (unloaded chunks read as air).
        java.util.concurrent.Executor ex = server != null ? server : Minecraft.getInstance();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            net.minecraft.world.level.Level level = server == null ? Minecraft.getInstance().level
                    : Minecraft.getInstance().player == null ? server.overworld() : server.getLevel(Minecraft.getInstance().player.level().dimension());
            Map<String, Character> legend = new LinkedHashMap<>();
            String sym = "#abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@$%&*+=?";
            var layers = new StringBuilder();
            for (int y = hi.getY(); y >= lo.getY(); y--) {
                layers.append("y=").append(y).append('\n');
                for (int z = lo.getZ(); z <= hi.getZ(); z++) {
                    for (int x = lo.getX(); x <= hi.getX(); x++) {
                        BlockState st = level.getBlockState(new BlockPos(x, y, z));
                        if (st.isAir()) {
                            layers.append('.');
                            continue;
                        }
                        String id = states ? st.toString() : BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                        layers.append(legend.computeIfAbsent(id, k -> legend.size() < sym.length() ? sym.charAt(legend.size()) : '~'));
                    }
                    layers.append('\n');
                }
            }
            var sb = new StringBuilder("origin=").append(lo.toShortString()).append('\n');
            legend.forEach((k, c) -> sb.append(c).append(' ').append(k).append('\n'));
            return sb.append(layers).toString();
        }, ex).get(20, TimeUnit.SECONDS);
    }
}
