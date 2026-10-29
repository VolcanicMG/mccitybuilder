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
            case "/screenshot" -> client(() -> shot(a.length > 0 ? a[0] : "shot"));
            case "/blocks" -> blocks(a);
            // <mode> <pack (spaces as _)> <path> <x y z> <rot>: an InstantBuild request as the planner sends it.
            case "/deco" -> client(() -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.volcanicmg.mccitybuilder.server.InstantBuild.Request(Integer.parseInt(a[0]),
                        new BlockPos(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5])), 0, a[1].replace('_', ' '), a[2],
                        com.ldtteam.structurize.api.RotationMirror.values()[Integer.parseInt(a[6])]));
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

    private static String blocks(String[] a) throws Exception {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return "no integrated server";
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(a[i]);
        boolean states = a.length > 6;
        BlockPos lo = new BlockPos(Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]));
        BlockPos hi = new BlockPos(Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
        long cells = (long) (hi.getX() - lo.getX() + 1) * (hi.getY() - lo.getY() + 1) * (hi.getZ() - lo.getZ() + 1);
        if (cells > 200_000) return "box too big: " + cells;
        return server.submit(() -> {
            var level = Minecraft.getInstance().player == null ? server.overworld() : server.getLevel(Minecraft.getInstance().player.level().dimension());
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
        }).get(20, TimeUnit.SECONDS);
    }
}
