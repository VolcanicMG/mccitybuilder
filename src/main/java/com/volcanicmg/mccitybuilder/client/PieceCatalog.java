package com.volcanicmg.mccitybuilder.client;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.storage.StructurePacks;
import com.volcanicmg.mccitybuilder.CityBuilder;
import com.volcanicmg.mccitybuilder.planner.PieceLayout;
import com.volcanicmg.mccitybuilder.planner.PieceSets;
import com.volcanicmg.mccitybuilder.planner.Plan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A style pack's road and wall pieces on the client: the blueprints of its road / wall folders (loaded in the
 * background), sorted into {@link PieceSets} families with the user's per-pack overrides (saved under
 * {@code mccitybuilder/piecesets/}), rotated copies for ghosts and profiles, and the layout of drawn lines into
 * placed pieces: straights tiled along each segment, corners / T / cross / tower pieces at joints (turned and shifted
 * by matching the solid profile of their faces to the neighbouring straights), end caps, gates.
 */
final class PieceCatalog {
    /**
     * A piece to place: its blueprint path, quarter turns, anchor, footprint box {x0, z0, x1, z1}, role, block count,
     * the bottom and height of its blueprint box (for outlines), the ground level it stands on and the lowest ground
     * under its footprint (a gap between them is air under the piece: a builder doesn't fill it).
     */
    record Placed(String path, int turns, BlockPos anchor, int[] box, PieceSets.Role role, int blocks, int y0, int h, int level, int low) {
        boolean joint() {
            return role == PieceSets.Role.CORNER || role == PieceSets.Role.TEE || role == PieceSets.Role.CROSS || role == PieceSets.Role.TOWER
                    || role == PieceSets.Role.INNER;
        }
    }

    private static final Map<String, Map<String, PieceSets.Family>> families = new HashMap<>();
    private static final Map<String, CompletableFuture<Blueprint>> loads = new HashMap<>();
    private static final Map<String, Blueprint> rotated = new HashMap<>();
    private static final Map<String, Integer> blockCounts = new HashMap<>();
    /** Bumped whenever a piece finishes loading (or fails): an incomplete layout is worth redoing only then. */
    static int loadVersion;
    // Pieces whose blueprint failed to load ("pack|path"): skipped, so their pack doesn't wait for them forever.
    private static final Set<String> failed = new HashSet<>();
    // Overrides: pack -> "family|ROLE" -> group base name ("-" = role disabled).
    private static final Map<String, Map<String, String>> overrides = new HashMap<>();

    private PieceCatalog() {
    }

    static void clear() {
        families.clear();
        loads.clear();
        rotated.clear();
        blockCounts.clear();
        grids.clear();
        gridOf.clear();
        groundOffsets.clear();
        PieceLayout.clearCache();
        failed.clear();
        PieceView.clear();
        overrides.clear();
    }

    /** @return a fresh copy of the blueprint turned {@code turns} quarter turns, or null while it loads */
    static Blueprint rotated(String pack, String path, int turns) {
        Minecraft mc = Minecraft.getInstance();
        turns = Math.floorMod(turns, 4);
        String key = pack + "|" + path + "|" + turns;
        Blueprint done = rotated.get(key);
        if (done != null || mc.level == null) return done;
        CompletableFuture<Blueprint> f = loads.computeIfAbsent(key, k -> StructurePacks.getBlueprintFuture(pack, path, true, mc.level.registryAccess()));
        if (!f.isDone()) return null;
        Blueprint bp = f.isCompletedExceptionally() ? null : f.getNow(null);
        if (bp == null) {
            if (failed.add(pack + "|" + path)) {
                CityBuilder.LOGGER.warn("City Builder: can't load piece {} of {}; skipping it", path, pack);
                loadVersion++;
            }
            return null;
        }
        RotationMirror rm = RotationMirror.NONE;
        for (int i = 0; i < turns; i++) rm = rm.rotate(Rotation.CLOCKWISE_90);
        bp.setRotationMirror(rm, mc.level);
        rotated.put(key, bp);
        loadVersion++;
        return bp;
    }

    /** @return true when the piece's blueprint failed to load (it is skipped, not waited for) */
    static boolean failed(String pack, String path) {
        return failed.contains(pack + "|" + path);
    }

    static RotationMirror rotation(int turns) {
        RotationMirror rm = RotationMirror.NONE;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) rm = rm.rotate(Rotation.CLOCKWISE_90);
        return rm;
    }

    /** @return the pack's families (all tools), or null while its pieces load */
    static Map<String, PieceSets.Family> families(String pack) {
        Map<String, PieceSets.Family> done = families.get(pack);
        if (done != null) return done;
        List<String> folders = new ArrayList<>();
        for (String f : Blueprints.decoFolders(pack)) if (PieceSets.tool(f + "/x.blueprint") != null) folders.add(f);
        if (Blueprints.decoFolders(pack).isEmpty()) return null;
        List<PieceSets.Piece> pieces = new ArrayList<>();
        boolean loading = false;
        for (String f : folders) {
            List<Blueprints.Hut> ds = Blueprints.decorations(pack, f);
            if (ds == null) {
                loading = true;
                continue;
            }
            for (Blueprints.Hut d : ds) {
                Blueprint bp = rotated(pack, d.path(), 0);
                if (bp == null) {
                    loading |= !failed(pack, d.path());
                    continue;
                }
                String[] sn = PieceSets.split(d.path().substring(d.path().lastIndexOf('/') + 1));
                pieces.add(new PieceSets.Piece(d.path(), sn[0], Integer.parseInt(sn[1]), bp.getSizeX(), bp.getSizeY(), bp.getSizeZ()));
            }
        }
        if (loading) return null;
        Map<String, Integer[]> votes = new HashMap<>();
        java.util.Set<String> own = new java.util.HashSet<>();
        for (PieceSets.Piece p : pieces) {
            if (PieceLayout.ownGround(p.path())) own.add(p.path());
            Blueprint bp = rotated(pack, p.path(), 0);
            int tag = BlueprintTagUtils.getGroundAnchorOffset(bp, Integer.MIN_VALUE);
            Integer t = tag == Integer.MIN_VALUE ? null : tag;
            votes.put(p.path(), new Integer[]{t == null ? 1 : t, PieceLayout.groundVote(bp.getSizeX(), bp.getSizeY(), bp.getSizeZ(), ids(bp),
                    bp.getPrimaryBlockOffset().getY(), t, "road".equals(PieceSets.tool(p.path())))});
        }
        groundOffsets.put(pack, PieceLayout.groundOffsets(votes, own));
        Map<String, PieceSets.Family> out = PieceSets.classify(pieces);
        families.put(pack, out);
        return out;
    }

    /** @return the usable families of a tool in the pack (have a straight), in order; null while loading */
    static List<String> familyKeys(String pack, String tool) {
        Map<String, PieceSets.Family> fs = families(pack);
        if (fs == null) return null;
        List<String> out = new ArrayList<>();
        for (var en : fs.entrySet()) if (en.getValue().tool().equals(tool) && !groups(pack, en.getValue(), PieceSets.Role.STRAIGHT).isEmpty()) out.add(en.getKey());
        return out;
    }

    /** @return the groups used for a role: the user's override if any, else every group the classifier found */
    static List<PieceSets.Group> groups(String pack, PieceSets.Family f, PieceSets.Role r) {
        String o = overrides(pack).get(f.tool() + ":" + f.key() + "|" + r.name());
        List<PieceSets.Group> all = new ArrayList<>(r == PieceSets.Role.STRAIGHT ? f.straights() : f.of(r));
        if (o == null) return all;
        if (o.equals("-")) return List.of();
        for (PieceSets.Family any : families(pack).values())
            if (any.tool().equals(f.tool()) && any.key().equals(f.key()))
                for (List<PieceSets.Group> gs : any.roles().values())
                    for (PieceSets.Group g : gs) if (g.base().equals(o)) return List.of(new PieceSets.Group(g.base(), r, g.pieces(), g.levels()));
        return all;
    }

    /** @return every group of the family (for the editor's choices) */
    static List<PieceSets.Group> allGroups(PieceSets.Family f) {
        List<PieceSets.Group> out = new ArrayList<>();
        for (List<PieceSets.Group> gs : f.roles().values()) out.addAll(gs);
        out.sort(java.util.Comparator.comparing(PieceSets.Group::base));
        return out;
    }

    // ---- overrides (the piece-set editor) ----

    private static Path overridesFile(String pack) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CityBuilder.MODID).resolve("piecesets").resolve(pack.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
    }

    static Map<String, String> overrides(String pack) {
        return overrides.computeIfAbsent(pack, k -> {
            Map<String, String> m = new LinkedHashMap<>();
            try {
                Path f = overridesFile(pack);
                if (Files.exists(f)) m.putAll(PieceSets.readOverrides(Files.readString(f)));
            } catch (Exception e) {
                CityBuilder.LOGGER.warn("City Builder: can't read piece set overrides for {}", pack, e);
            }
            return m;
        });
    }

    /** Sets (or with null, clears) the group used for a role in a family, and saves the pack's overrides. */
    static void override(String pack, String familyKey, PieceSets.Role role, String base) {
        Map<String, String> m = overrides(pack);
        if (base == null) m.remove(familyKey + "|" + role.name());
        else m.put(familyKey + "|" + role.name(), base);
        saveOverrides(pack);
    }

    private static void saveOverrides(String pack) {
        try {
            Path f = overridesFile(pack);
            Files.createDirectories(f.getParent());
            Files.writeString(f, PieceSets.writeOverrides(overrides(pack)));
        } catch (Exception e) {
            CityBuilder.LOGGER.warn("City Builder: can't save piece set overrides for {}", pack, e);
        }
    }

    // ---- profiles ----

    private static boolean solid(BlockState st) {
        return !st.isAir() && st.getBlock() != com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get();
    }

    private static final Map<String, PieceLayout.Grid> grids = new HashMap<>();
    // Which piece each grid is ({pack, path, turns}), for the player alignments.
    private static final Map<PieceLayout.Grid, Object[]> gridOf = new java.util.IdentityHashMap<>();

    static {
        // Player alignments from the joint alignment editor: looked up per joint / straight / direction.
        PieceLayout.setAlign((j, st, d) -> {
            Object[] a = gridOf.get(j), b = gridOf.get(st);
            if (a == null || b == null) return null;
            String k = PieceLayout.alignKey((String) a[1], (Integer) a[2], (String) b[1], (Integer) b[2], d);
            String v = PieceSets.alignLookup(PieceCatalog.drafts.getOrDefault((String) a[0], Map.of()), overrides((String) a[0]), k);
            return v == null ? null : PieceLayout.parseAlign(v);
        });
    }

    /**
     * Sets (or with null, resets to auto) a player alignment, saves it with the pack's overrides, and lays the plan out
     * again so it shows at once.
     */
    static void setAlignment(String pack, String key, int[] shiftDepth) {
        Map<String, String> m = overrides(pack);
        Map<String, String> dr = drafts.get(pack);
        if (dr != null) dr.remove(key);
        if (shiftDepth == null) m.remove(key);
        else m.put(key, shiftDepth[0] + "," + shiftDepth[1]);
        saveOverrides(pack);
        PieceLayout.clearCache();
        PlanView.linesChanged();
    }

    // Unsaved alignments while the editor adjusts them (pack -> key -> "shift,depth"): read before the saved ones,
    // never written to the file.
    private static final Map<String, Map<String, String>> drafts = new HashMap<>();

    /** A live (unsaved) alignment while the editor adjusts it; null = no draft (the saved one or auto shows). */
    static void draftAlignment(String pack, String key, int[] shiftDepth) {
        Map<String, String> m = drafts.computeIfAbsent(pack, k -> new HashMap<>());
        if (shiftDepth == null) m.remove(key);
        else m.put(key, shiftDepth[0] + "," + shiftDepth[1]);
        PieceLayout.clearCache();
        PlanView.linesChanged();
    }

    /** Drops every unsaved alignment (the editor closed, the planner ended) and lays the plan out again. */
    static void clearDrafts() {
        if (drafts.isEmpty()) return;
        drafts.clear();
        PieceLayout.clearCache();
        PlanView.linesChanged();
    }

    /** @return the grid of a piece's blueprint as placed (turned), or null while it loads */
    static PieceLayout.Grid gridFor(String pack, String path, int turns) {
        return grid(pack, path, turns);
    }

    /** @return the piece's blocks as placed (turned), for face matching; null while it loads */
    static PieceLayout.Grid grid(String pack, String path, int turns) {
        String key = pack + "|" + path + "|" + Math.floorMod(turns, 4);
        PieceLayout.Grid done = grids.get(key);
        if (done != null) return done;
        Blueprint bp = rotated(pack, path, turns);
        Integer gao = bp == null ? null : groundOffset(pack, path);
        if (gao == null) return null;
        int[] ids = ids(bp);
        // Ground: the local y at ground level, as the anchor math in place() puts it.
        PieceLayout.Grid g = new PieceLayout.Grid(bp.getSizeX(), bp.getSizeY(), bp.getSizeZ(), ids, bp.getPrimaryBlockOffset().getY() - gao,
                gao - BlueprintTagUtils.getGroundAnchorOffset(bp, 1));
        grids.put(key, g);
        gridOf.put(g, new Object[]{pack, path, Math.floorMod(turns, 4)});
        return g;
    }

    /** @return a blueprint's blocks as {@link PieceLayout.Grid} ids (air clears the world when pasted; substitution, 0, leaves it) */
    private static int[] ids(Blueprint bp) {
        int sx = bp.getSizeX(), sy = bp.getSizeY(), sz = bp.getSizeZ();
        int[] ids = new int[sx * sy * sz];
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    BlockState st = bp.getBlockState(new BlockPos(x, y, z));
                    ids[(y * sz + z) * sx + x] = st.isAir() ? PieceLayout.Grid.AIR : solid(st) ? 1 + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(st.getBlock()) : 0;
                }
        return ids;
    }

    // Per pack: each piece's ground anchor offset (PieceLayout.groundOffsets), once all its pieces have loaded.
    private static final Map<String, Map<String, Integer>> groundOffsets = new HashMap<>();

    /** @return the piece's ground anchor offset, or null while the pack's pieces load */
    static Integer groundOffset(String pack, String path) {
        Map<String, Integer> m = groundOffsets.get(pack);
        if (m == null && families(pack) != null) m = groundOffsets.get(pack);
        if (m == null) return null;
        Integer o = m.get(path);
        if (o != null) return o;
        // Not one of the pack's road / wall pieces (a built record of a removed file): its tag, else 1.
        Blueprint bp = rotated(pack, path, 0);
        return bp == null ? null : BlueprintTagUtils.getGroundAnchorOffset(bp, 1);
    }

    static int blocks(String pack, String path) {
        return blockCounts.computeIfAbsent(pack + "|" + path, k -> {
            Blueprint bp = rotated(pack, path, 0);
            if (bp == null) return 0;
            int n = 0;
            for (var info : bp.getBlockInfoAsList()) if (solid(info.getState())) n++;
            return n;
        });
    }

    // ---- layout ----

    /**
     * The result of laying out every planned line: placed pieces per line, cells left uncovered ("short"), where that
     * gap is (box or null), the length each line needs to fit a piece, and whether all blueprints were ready (per line,
     * and overall): an incomplete line may miss pieces or have a butt joint where a joint piece belongs, so it must
     * not be built yet. Offs per line: cells the joints / caps take at {start, end}. Heights per line: {flat level or MIN_VALUE when not flat, highest ground under its pieces}.
     */
    record Layout(List<List<Placed>> lines, List<Integer> shortened, List<int[]> gaps, List<Integer> need, List<Boolean> lineComplete,
                  List<int[]> heights, List<int[]> offs, boolean complete) {
    }

    /**
     * Lays out every line. Joints (2+ lines of the same family at a cell) are reserved first, once each: a corner /
     * T / cross piece (or a tower when the line asks), turned and shifted by profile matching; with no such piece, a
     * butt joint where one line runs through the joint square and the others stop at its face. Every run then starts
     * exactly at the joint's face (no overlap, no gap); free ends get an end cap; a gate goes in the middle when
     * asked; straights fill the rest, and what can't be covered is left at a free end and reported.
     */
    /** The built segments' pieces as built (keyed by {@link Plan#lineKey}): fixed inputs, never laid out again. Set by PlanView. */
    static Map<String, List<Plan.BuiltPiece>> builtPieces = Map.of();

    /** @return a built piece as placed when it was built, or null while its blueprint loads */
    static Placed frozen(String pack, Plan.BuiltPiece b) {
        Blueprint bp = rotated(pack, b.path(), b.turns());
        if (bp == null) return null;
        int[] box = {b.bx0(), b.bz0(), b.bx1(), b.bz1()};
        PieceLayout.Grid gr = grid(pack, b.path(), b.turns());
        if (gr == null) return null;
        int level = b.ay() - (bp.getPrimaryBlockOffset().getY() - gr.ground()) + 2;
        PieceSets.Role role;
        try {
            role = PieceSets.Role.valueOf(b.role());
        } catch (IllegalArgumentException e) {
            role = PieceSets.Role.OTHER;
        }
        return new Placed(b.path(), b.turns(), new BlockPos(b.ax(), b.ay(), b.az()), box, role, blocks(pack, b.path()), b.ay() - bp.getPrimaryBlockOffset().getY(),
                bp.getSizeY(), level, under(box)[0]);
    }

    /** @return the pieces of a placed segment as built records */
    static List<Plan.BuiltPiece> freeze(Plan.Line l, List<Placed> ps) {
        List<Plan.BuiltPiece> out = new ArrayList<>();
        for (Placed p : ps)
            out.add(new Plan.BuiltPiece(l.x0(), l.z0(), l.x1(), l.z1(), p.path(), p.turns(), p.anchor().getX(), p.anchor().getY(), p.anchor().getZ(),
                    p.box()[0], p.box()[1], p.box()[2], p.box()[3], p.role().name()));
        return out;
    }

    private static boolean covers(int[] box, int[] c) {
        return c[0] >= box[0] && c[0] <= box[2] && c[1] >= box[1] && c[1] <= box[3];
    }

    static Layout layout(List<Plan.Line> lines, Map<Long, Integer> jointTypes) {
        jointNotes.clear();
        jointMatch.clear();
        jointGapAir.clear();
        List<List<Placed>> out = new ArrayList<>();
        List<Integer> shortened = new ArrayList<>(), need = new ArrayList<>();
        List<int[]> gaps = new ArrayList<>();
        List<Boolean> lineComplete = new ArrayList<>();
        List<int[]> heights = new ArrayList<>(), offs = new ArrayList<>();
        // Joint pieces: where each sits (line, index) and the levels of the run pieces next to it.
        Map<Long, int[]> jointAt = new HashMap<>();
        Map<Long, List<int[]>> jointNext = new HashMap<>();
        boolean complete = true;
        // Endpoints: which lines meet at each cell.
        Map<Long, List<int[]>> at = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            at.computeIfAbsent(key(l.x0(), l.z0()), k -> new ArrayList<>()).add(new int[]{i, 0});
            at.computeIfAbsent(key(l.x1(), l.z1()), k -> new ArrayList<>()).add(new int[]{i, 1});
        }
        Map<Long, Placed> joints = new HashMap<>();
        // Butt joints: {x0, z0, x1, z1, owner line, 1 = only because the joint piece is still loading}.
        Map<Long, int[]> butts = new HashMap<>();
        // Built segments with their pieces stored: those pieces as built, their joint pieces registered first so new
        // runs attach to them (fixed: never moved or re-levelled).
        Map<Integer, List<Placed>> frozenLines = new HashMap<>();
        java.util.Set<Long> fixed = new java.util.HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            List<Plan.BuiltPiece> stored = l.built() ? builtPieces.get(Plan.lineKey(l.x0(), l.z0(), l.x1(), l.z1())) : null;
            if (stored == null || stored.isEmpty()) continue;
            List<Placed> ps = new ArrayList<>();
            boolean loading = false;
            for (Plan.BuiltPiece b : stored) {
                Placed p = frozen(l.pack(), b);
                if (p == null) loading = true;
                else ps.add(p);
            }
            frozenLines.put(i, ps);
            if (loading) complete = false;
            for (Placed p : ps)
                if (p.joint())
                    for (int[] e : l.ends())
                        if (covers(p.box(), e) && !joints.containsKey(key(e[0], e[1]))) {
                            joints.put(key(e[0], e[1]), p);
                            fixed.add(key(e[0], e[1]));
                        }
        }
        // Per line, for the seam levelling after the joints: the run pieces in order and their levels.
        Map<Integer, List<Integer>> lineRun = new HashMap<>();
        Map<Integer, int[]> lineLv = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Plan.Line l = lines.get(i);
            List<Placed> placed = new ArrayList<>();
            out.add(placed);
            if (frozenLines.containsKey(i)) {
                placed.addAll(frozenLines.get(i));
                // Its run pieces nearest each end, as built: a new joint there stands at their level.
                List<Integer> idx = new ArrayList<>();
                List<int[]> rb = new ArrayList<>();
                for (int q = 0; q < placed.size(); q++)
                    if (!placed.get(q).joint()) {
                        idx.add(q);
                        rb.add(placed.get(q).box());
                    }
                for (int[] e : l.ends()) {
                    int n = PieceLayout.nearest(rb, e);
                    if (n >= 0) jointNext.computeIfAbsent(key(e[0], e[1]), x -> new ArrayList<>()).add(new int[]{i, idx.get(n), 0, PieceLayout.BUILT});
                }
                lineComplete.add(true);
                heights.add(new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE});
                offs.add(new int[2]);
                shortened.add(0);
                gaps.add(null);
                need.add(Integer.MAX_VALUE);
                continue;
            }
            Map<String, PieceSets.Family> fs = families(l.pack());
            PieceSets.Family f = fs == null ? null : fs.get(l.family());
            List<PieceSets.Group> straights = f == null ? List.of() : groups(l.pack(), f, PieceSets.Role.STRAIGHT);
            if (straights.isEmpty()) {
                complete &= fs != null;
                lineComplete.add(fs != null);
                heights.add(new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE});
                offs.add(new int[2]);
                shortened.add(0);
                gaps.add(null);
                need.add(Integer.MAX_VALUE);
                continue;
            }
            int[] a = {l.x0(), l.z0()}, b = {l.x1(), l.z1()};
            int dir = PieceLayout.dir(a, b), len = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) + 1;
            PieceSets.Piece primary = primary(l, i);
            int width = primary.across();
            int[] off = new int[2];
            boolean[] free = new boolean[2];
            boolean aligned = false, ok = true;
            int shift = 0;
            // End caps: {piece, quarter turns, the run's shift relative to a cap on the line}, placed once the shift is known.
            Object[][] caps = new Object[2][];
            for (int end = 0; end < 2; end++) {
                int[] c = end == 0 ? a : b;
                int d = end == 0 ? dir : (dir + 2) % 4;
                long k = key(c[0], c[1]);
                List<int[]> meet = new ArrayList<>();
                for (int[] m : at.get(k))
                    if (m[0] != i && lines.get(m[0]).family().equals(l.family()) && lines.get(m[0]).pack().equals(l.pack())) meet.add(m);
                if (meet.isEmpty()) {
                    free[end] = true;
                    // A free end: its cap, a tower when chosen (laid out like a joint with one run), or nothing.
                    int endType = jointTypes.getOrDefault(Plan.cell(c[0], c[1]), Plan.J_AUTO);
                    List<PieceSets.Role> endRoles = PieceLayout.jointRoles(endType, 1, false, l.towers());
                    PieceSets.Role endRole = null;
                    for (PieceSets.Role r : endRoles) if (!groups(l.pack(), f, r).isEmpty()) {
                        endRole = r;
                        break;
                    }
                    if (endRole == PieceSets.Role.TOWER && !joints.containsKey(k)) {
                        jointLoading = false;
                        Placed tower = joint(l, f, c, d, meet, lines, i, primary, width, endType);
                        if (tower != null) {
                            joints.put(k, tower);
                            jointAt.put(k, new int[]{i, placed.size()});
                            placed.add(tower);
                            free[end] = false;
                        } else if (jointLoading) ok = false;
                    }
                    Placed endTower = joints.get(k);
                    if (endTower != null) {
                        free[end] = false;
                        int[] jb = endTower.box();
                        off[end] = PieceLayout.reach(c, d, jb);
                        PieceLayout.Grid jg = grid(l.pack(), endTower.path(), endTower.turns()), sg = grid(l.pack(), primary.path(), sturn(primary, dir, l.flip()));
                        if (jg != null && sg != null) {
                            PieceLayout.Fit ft = PieceLayout.fit(c, d, jb, jg, sg, width, primary.run(), Math.max(3, width));
                            noteFit(Plan.cell(c[0], c[1]), ft);
                            off[end] = ft.off();
                            if (end == 0 || !aligned) {
                                shift = ft.shift();
                                aligned = true;
                            }
                        }
                        continue;
                    }
                    List<PieceSets.Group> capGroups = endRole == PieceSets.Role.END ? groups(l.pack(), f, PieceSets.Role.END) : List.of();
                    if (!capGroups.isEmpty()) {
                        PieceSets.Piece cap = capGroups.get(0).pick(l.level(), i);
                        int[] cb = PieceLayout.boxes(c, d, 0, List.of(cap.run()), cap.across(), 0).get(0);
                        PieceLayout.Grid sg = grid(l.pack(), primary.path(), sturn(primary, dir, l.flip()));
                        int turns = PieceLayout.turns(cap.sx(), cap.sz(), (d + 2) % 4), rel = 0;
                        double bestScore = -1;
                        for (int k2 = turns; k2 < turns + 4; k2 += 2) {
                            PieceLayout.Grid cg = grid(l.pack(), cap.path(), k2);
                            if (cg == null || sg == null) {
                                ok &= failed(l.pack(), cg == null ? cap.path() : primary.path());
                                continue;
                            }
                            double sc = PieceLayout.faceScore(c, d, cb, cg, sg, width, primary.run());
                            if (sc > bestScore + 1e-9) {
                                bestScore = sc;
                                turns = k2;
                                int[] att = PieceLayout.attach(c, d, cb, cg, sg, width, primary.run(), Math.max(3, width));
                                off[end] = att[0];
                                rel = att[1];
                            }
                        }
                        if (bestScore < 0) off[end] = cap.run();
                        caps[end] = new Object[]{cap, turns, rel};
                    }
                    continue;
                }
                Placed joint = joints.get(k);
                // A built segment never owns a new joint (it wouldn't be built): an unbuilt one meeting there places it.
                boolean defer = false;
                if (l.built()) for (int[] m : meet) defer |= !lines.get(m[0]).built();
                if (defer && joint == null && !butts.containsKey(k)) continue;
                if (joint == null && !butts.containsKey(k)) {
                    jointLoading = false;
                    joint = joint(l, f, c, d, meet, lines, i, primary, width, jointTypes.getOrDefault(Plan.cell(c[0], c[1]), Plan.J_AUTO));
                    if (joint != null) {
                        joints.put(k, joint);
                        jointAt.put(k, new int[]{i, placed.size()});
                        placed.add(joint);
                    } else {
                        // No joint piece: one line runs through the joint square, the others stop at its face.
                        int[] who = new int[meet.size() + 1], dirs = new int[who.length], widths = new int[who.length];
                        List<int[]> all = new ArrayList<>(meet);
                        all.add(new int[]{i, end});
                        all.sort(java.util.Comparator.comparingInt(m -> m[0]));
                        for (int n = 0; n < all.size(); n++) {
                            Plan.Line o = lines.get(all.get(n)[0]);
                            int od = PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()});
                            who[n] = all.get(n)[0];
                            dirs[n] = all.get(n)[1] == 0 ? od : (od + 2) % 4;
                            PieceSets.Piece op = primary(o, who[n]);
                            widths[n] = op == null ? width : op.across();
                        }
                        int[] sq = PieceLayout.square(c, dirs, widths);
                        butts.put(k, new int[]{sq[0], sq[1], sq[2], sq[3], who[PieceLayout.owner(dirs)], jointLoading ? 1 : 0});
                    }
                }
                if (joint != null) {
                    int[] jb = joint.box();
                    off[end] = PieceLayout.reach(c, d, jb);
                    // The run meets the joint's body (trim skipped) and lines up with its face; the sideways shift from
                    // the start's joint, else the end's (lateral positions don't depend on where along the run).
                    PieceLayout.Grid jg = grid(l.pack(), joint.path(), joint.turns()), sg = grid(l.pack(), primary.path(), sturn(primary, dir, l.flip()));
                    if (sg == null && !failed(l.pack(), primary.path())) ok = false;
                    if (jg != null && sg != null) {
                        PieceLayout.Fit ft = PieceLayout.fit(c, d, jb, jg, sg, width, primary.run(), Math.max(3, width));
                        noteFit(Plan.cell(c[0], c[1]), ft);
                        off[end] = ft.off();
                        if (end == 0 || !aligned) {
                            shift = ft.shift();
                            aligned = true;
                        }
                    }
                } else {
                    int[] bt = butts.get(k);
                    off[end] = PieceLayout.offset(c, d, bt, bt[4] == i);
                    if (bt[5] == 1) ok = false;
                    // A branch onto the owner's side meets its body like a joint piece (the box may hold a margin).
                    Plan.Line o = lines.get(bt[4]);
                    int od = PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()});
                    PieceSets.Piece op = primary(o, bt[4]);
                    if (bt[4] != i && d % 2 != od % 2 && op != null) {
                        int oout = o.x0() == c[0] && o.z0() == c[1] ? od : (od + 2) % 4;
                        PieceLayout.Grid og = grid(o.pack(), op.path(), sturn(op, od, o.flip())), sg = grid(l.pack(), primary.path(), sturn(primary, dir, l.flip()));
                        if (og != null && sg != null) {
                            int[] ob = PieceLayout.boxes(c, oout, PieceLayout.offset(c, oout, bt, true), List.of(op.run()), op.across(), 0).get(0);
                            int[] att = PieceLayout.attach(c, d, ob, og, sg, width, primary.run(), Math.max(3, width));
                            off[end] = att[0];
                            if (end == 0 || !aligned) {
                                shift = att[1];
                                aligned = true;
                            }
                        }
                    }
                }
            }
            // Caps sit across from the run: on the line when no joint set the run's shift, else moved with it.
            for (int end = 0; end < 2; end++) {
                if (caps[end] == null) continue;
                PieceSets.Piece cap = (PieceSets.Piece) caps[end][0];
                int rel = (Integer) caps[end][2];
                if (!aligned) {
                    shift = rel;
                    aligned = true;
                }
                int[] c = end == 0 ? a : b;
                int[] cb = PieceLayout.boxes(c, end == 0 ? dir : (dir + 2) % 4, 0, List.of(cap.run()), cap.across(), shift - rel).get(0);
                Placed p = place(l.pack(), cap.path(), (Integer) caps[end][1], cb, PieceSets.Role.END);
                if (p == null) ok &= failed(l.pack(), cap.path());
                else placed.add(p);
            }
            // The run between the joints / caps: a gate in the middle when asked, straights around it.
            int n = len - off[0] - off[1];
            List<PieceSets.Group> gates = l.gate() ? groups(l.pack(), f, PieceSets.Role.GATE) : List.of();
            // Lengths of the straights as picked for this line (levels can change a piece's size), same width only.
            int[] lens = straights.stream().map(g -> g.pick(g.levels() ? l.level() : l.variant(), 0)).filter(p -> p.across() == width)
                    .mapToInt(PieceSets.Piece::run).distinct().toArray();
            need.add(PieceLayout.need(off[0], off[1], lens));
            offs.add(off.clone());
            List<Integer> tiles = new ArrayList<>();
            List<PieceSets.Piece> tilePieces = new ArrayList<>();
            int used;
            if (!gates.isEmpty() && n >= gates.get(0).first().run()) {
                PieceSets.Piece gate = gates.get(0).pick(l.level(), i);
                int left = (n - gate.run()) / 2;
                List<Integer> l1 = PieceLayout.fill(left, lens), l2 = PieceLayout.fill(n - gate.run() - left, lens);
                for (int x : l1) tiles.add(x);
                tiles.add(-1);
                for (int x : l2) tiles.add(x);
                used = gate.run() + l1.stream().mapToInt(Integer::intValue).sum() + l2.stream().mapToInt(Integer::intValue).sum();
                for (int t : tiles) tilePieces.add(t < 0 ? gate : straightOf(straights, width, t, l, i * 1000 + tilePieces.size()));
            } else {
                for (int x : PieceLayout.fill(n, lens)) tiles.add(x);
                used = tiles.stream().mapToInt(Integer::intValue).sum();
                for (int t : tiles) tilePieces.add(straightOf(straights, width, t, l, i * 1000 + tilePieces.size()));
            }
            // A gap the main straights leave: filled with the family's shorter pieces of the same width (other levels /
            // variants, end pieces), longest first.
            if (n - used > 0) {
                List<PieceSets.Piece> fillers = new ArrayList<>();
                for (PieceSets.Role r : List.of(PieceSets.Role.STRAIGHT, PieceSets.Role.END))
                    for (PieceSets.Group g : groups(l.pack(), f, r))
                        for (PieceSets.Piece p : g.pieces()) if (p.across() == width) fillers.add(p);
                fillers.sort((x, y) -> y.run() - x.run());
                // Never next to a joint (their faces weren't matched against it): between two main pieces, or at a free
                // end; with neither, the gap stays.
                int main = tiles.size(), fat = main >= 2 ? main / 2 : free[1] ? main : free[0] ? 0 : -1;
                for (PieceSets.Piece p : fat < 0 ? List.<PieceSets.Piece>of() : fillers)
                    while (p.run() <= n - used) {
                        tiles.add(fat, p.run());
                        tilePieces.add(fat, p);
                        used += p.run();
                    }
            }
            int left = Math.max(0, n - used);
            shortened.add(left);
            // What can't be covered goes to a free end, so runs always meet their joints.
            int start = PieceLayout.runStart(off[0], left, free[0], free[1]);
            gaps.add(left == 0 ? null : PieceLayout.boxes(a, dir, start == off[0] ? off[0] + used : off[0], List.of(left), width, shift).get(0));
            List<Integer> runs = new ArrayList<>();
            for (PieceSets.Piece p : tilePieces) runs.add(p.run());
            List<int[]> boxes = PieceLayout.boxes(a, dir, start, runs, width, shift);
            for (int k = 0; k < tilePieces.size(); k++) {
                PieceSets.Piece p = tilePieces.get(k);
                Placed pl = place(l.pack(), p.path(), sturn(p, dir, l.flip()), boxes.get(k), tiles.get(k) < 0 ? PieceSets.Role.GATE : PieceSets.Role.STRAIGHT);
                if (pl == null) ok &= failed(l.pack(), p.path());
                else placed.add(pl);
            }
            // Heights: the line's mode for its own pieces in order along the run; joints follow their neighbours below.
            List<Integer> run = new ArrayList<>();
            for (int q = 0; q < placed.size(); q++) if (!placed.get(q).joint()) run.add(q);
            int fdir = dir;
            run.sort(java.util.Comparator.comparingInt(q -> along(placed.get(q).box(), fdir)));
            int[] own = new int[run.size()], hi = new int[run.size()];
            for (int q = 0; q < run.size(); q++) {
                own[q] = placed.get(run.get(q)).level();
                hi[q] = under(placed.get(run.get(q)).box())[1];
            }
            int[] lv = PieceLayout.levels(l.height(), own, hi, l.dy(), l.base());
            if (l.height() == Plan.FOLLOW || l.height() == Plan.SMOOTH) {
                // A cap or gate and the pieces next to it meet at one level.
                boolean[] special = new boolean[run.size()];
                for (int q = 0; q < run.size(); q++) special[q] = placed.get(run.get(q)).role() != PieceSets.Role.STRAIGHT;
                lv = PieceLayout.shareSeams(lv, special);
            }
            for (int q = 0; q < run.size(); q++) placed.set(run.get(q), relevel(l.pack(), placed.get(run.get(q)), lv[q]));
            lineRun.put(i, run);
            lineLv.put(i, lv);
            int segMax = Integer.MIN_VALUE;
            for (int h : hi) segMax = Math.max(segMax, h);
            boolean flat = l.height() == Plan.FLAT || l.height() == Plan.CHAIN;
            heights.add(new int[]{flat && lv.length > 0 ? lv[0] : Integer.MIN_VALUE, segMax});
            // The run's first piece at each joint: {line, index in placed, follows the joint's level}.
            if (lv.length > 0)
                for (int end = 0; end < 2; end++) {
                    long k = end == 0 ? key(a[0], a[1]) : key(b[0], b[1]);
                    if (joints.containsKey(k)) jointNext.computeIfAbsent(k, x -> new ArrayList<>()).add(new int[]{i, run.get(end == 0 ? 0 : run.size() - 1), flat || l.built() ? 0 : 1,
                            flat || l.built() ? PieceLayout.FLAT : PieceLayout.FOLLOWS});
                }
            lineComplete.add(ok);
            complete &= ok;
        }
        // One level at every joint: the joint stands as high as its own ground or the first piece of any run meeting
        // it, and those first pieces (follow / smooth runs) rise to it; a run steps only further away.
        // A built joint keeps its level (the world has its blocks); the new runs' first pieces take it, up or down.
        for (long k : fixed) {
            int level = joints.get(k).level();
            for (int[] n : jointNext.getOrDefault(k, List.of())) if (n[2] == 1) setTo(out, lines, lineRun, lineLv, n, level);
        }
        for (var en : jointAt.entrySet()) {
            int[] where = en.getValue();
            List<Placed> ps = out.get(where[0]);
            Placed j = ps.get(where[1]);
            List<int[]> next = jointNext.getOrDefault(en.getKey(), List.of());
            int[] nb = new int[next.size()], kinds = new int[next.size()];
            boolean anyBuilt = false;
            for (int q = 0; q < nb.length; q++) {
                nb[q] = out.get(next.get(q)[0]).get(next.get(q)[1]).level();
                kinds[q] = next.get(q)[3];
                anyBuilt |= kinds[q] == PieceLayout.BUILT;
            }
            // Next to a built segment: its level, so the new joint and runs join it flush.
            int level = PieceLayout.jointLevel(j.level(), nb, kinds);
            ps.set(where[1], relevel(lines.get(where[0]).pack(), j, level));
            for (int[] n : next)
                if (n[2] == 1 && anyBuilt) setTo(out, lines, lineRun, lineLv, n, level);
                else if (n[2] == 1) raiseTo(out, lines, lineRun, lineLv, n, level);
        }
        return new Layout(out, shortened, gaps, need, lineComplete, heights, offs, complete);
    }

    /**
     * Sets a run's first piece {@code n} = {line, index} to a built joint's level, up or down: a follow run's other
     * pieces keep their own (it steps back to the terrain), a smoothed run follows outward one block per piece.
     */
    private static void setTo(List<List<Placed>> out, List<Plan.Line> lines, Map<Integer, List<Integer>> lineRun, Map<Integer, int[]> lineLv, int[] n, int level) {
        Plan.Line l = lines.get(n[0]);
        List<Integer> run = lineRun.get(n[0]);
        int[] lv = lineLv.get(n[0]);
        if (run == null || lv == null || l.height() != Plan.SMOOTH) {
            out.get(n[0]).set(n[1], relevel(l.pack(), out.get(n[0]).get(n[1]), level));
            return;
        }
        int[] set = PieceLayout.setSmooth(lv, run.indexOf(n[1]), level);
        for (int q = 0; q < run.size(); q++) if (set[q] != lv[q]) out.get(n[0]).set(run.get(q), relevel(l.pack(), out.get(n[0]).get(run.get(q)), set[q]));
        lineLv.put(n[0], set);
    }

    /**
     * Raises a run's first piece {@code n} = {line, index} to a joint's level; a smoothed run is smoothed outward from
     * it again (raised only), so no piece sits more than one block below the one before.
     */
    private static void raiseTo(List<List<Placed>> out, List<Plan.Line> lines, Map<Integer, List<Integer>> lineRun, Map<Integer, int[]> lineLv, int[] n, int level) {
        Plan.Line l = lines.get(n[0]);
        List<Integer> run = lineRun.get(n[0]);
        int[] lv = lineLv.get(n[0]);
        if (run == null || lv == null || l.height() != Plan.SMOOTH) {
            out.get(n[0]).set(n[1], relevel(l.pack(), out.get(n[0]).get(n[1]), Math.max(level, out.get(n[0]).get(n[1]).level())));
            return;
        }
        int[] raised = PieceLayout.raiseSmooth(lv, run.indexOf(n[1]), level);
        for (int q = 0; q < run.size(); q++) if (raised[q] != lv[q]) out.get(n[0]).set(run.get(q), relevel(l.pack(), out.get(n[0]).get(run.get(q)), raised[q]));
        lineLv.put(n[0], raised);
    }

    /**
     * @param type the joint type the next joint will most likely get ({@link Plan#J_AUTO}: a corner)
     * @return the cells such a joint takes from a run meeting it (0 while loading or with no such piece)
     */
    static int expectedReach(Plan.Line l, int type) {
        Map<String, PieceSets.Family> fs = families(l.pack());
        PieceSets.Family f = fs == null ? null : fs.get(l.family());
        if (f == null) return 0;
        for (PieceSets.Role r : PieceLayout.jointRoles(type, 2, false, l.towers())) if (!groups(l.pack(), f, r).isEmpty()) return reachOf(l, r);
        return 0;
    }

    /** @return the cells a joint piece of role {@code r} takes from a run meeting it (0 for none or while loading) */
    static int reachOf(Plan.Line l, PieceSets.Role r) {
        if (r == null) return 0;
        Map<String, PieceSets.Family> fs = families(l.pack());
        PieceSets.Family f = fs == null ? null : fs.get(l.family());
        PieceSets.Piece st = primary(l, 0);
        if (f == null || st == null) return 0;
        List<PieceSets.Group> gs = groups(l.pack(), f, r);
        if (gs.isEmpty()) return 0;
        PieceLayout.Grid jg = grid(l.pack(), gs.get(0).pick(l.level(), 0).path(), 0), sg = grid(l.pack(), st.path(), sturn(st, 0, l.flip()));
        if (jg == null || sg == null) return 0;
        int[] c = {0, 0};
        return PieceLayout.attach(c, 0, PieceLayout.centred(c, jg.sx(), jg.sz()), jg, sg, st.across(), st.run(), Math.max(3, st.across()))[0];
    }

    /** @return the run lengths of the line's straights of its main width (what length snapping tiles with) */
    static int[] runLengths(Plan.Line l) {
        PieceSets.Piece p = primary(l, 0);
        Map<String, PieceSets.Family> fs = families(l.pack());
        PieceSets.Family f = fs == null ? null : fs.get(l.family());
        if (p == null || f == null) return new int[0];
        return groups(l.pack(), f, PieceSets.Role.STRAIGHT).stream().map(g -> g.pick(g.levels() ? l.level() : l.variant(), 0))
                .filter(q -> q.across() == p.across()).mapToInt(PieceSets.Piece::run).distinct().toArray();
    }

    /**
     * @return every piece the line's family uses at its level / variant, with its role, in browse order (straights,
     * corner, inner corner, T, cross, end, gate, tower); empty while loading
     */
    static List<Map.Entry<PieceSets.Role, PieceSets.Piece>> browse(Plan.Line l) {
        Map<String, PieceSets.Family> fs = families(l.pack());
        PieceSets.Family f = fs == null ? null : fs.get(l.family());
        if (f == null) return List.of();
        Map<PieceSets.Role, List<PieceSets.Piece>> per = new java.util.EnumMap<>(PieceSets.Role.class);
        Map<String, PieceSets.Role> roleOf = new HashMap<>();
        for (PieceSets.Role r : PieceSets.BROWSE) {
            List<PieceSets.Piece> ps = new ArrayList<>();
            for (PieceSets.Group g : groups(l.pack(), f, r)) {
                ps.add(g.pick(g.levels() ? l.level() : Math.max(0, l.variant()), 0));
                if (r != PieceSets.Role.STRAIGHT) break;
            }
            per.put(r, ps);
            for (PieceSets.Piece p : ps) roleOf.putIfAbsent(p.path(), r);
        }
        List<Map.Entry<PieceSets.Role, PieceSets.Piece>> out = new ArrayList<>();
        for (PieceSets.Piece p : PieceSets.browse(per)) out.add(Map.entry(roleOf.get(p.path()), p));
        return out;
    }

    /** @return the line's main straight at its level / variant (sets its width), or null without one */
    static PieceSets.Piece primary(Plan.Line l, int seed) {
        Map<String, PieceSets.Family> fs = families(l.pack());
        PieceSets.Family f = fs == null ? null : fs.get(l.family());
        List<PieceSets.Group> st = f == null ? List.of() : groups(l.pack(), f, PieceSets.Role.STRAIGHT);
        return st.isEmpty() ? null : st.get(0).pick(st.get(0).levels() ? l.level() : l.variant(), seed * 1000);
    }

    /**
     * A straight piece of the line's family at the line's start (the preview under the cursor before a chain starts).
     *
     * @return the piece, or null while it loads
     */
    static Placed preview(Plan.Line l) {
        PieceSets.Piece p = primary(l, 0);
        if (p == null) return null;
        int[] a = {l.x0(), l.z0()};
        int dir = PieceLayout.dir(a, new int[]{l.x1(), l.z1()});
        return place(l.pack(), p.path(), sturn(p, dir, l.flip()), PieceLayout.boxes(a, dir, 0, List.of(p.run()), p.across(), 0).get(0), PieceSets.Role.STRAIGHT);
    }

    /** @return a straight of run length {@code run} and the line's width, at the line's level / variant */
    private static PieceSets.Piece straightOf(List<PieceSets.Group> straights, int width, int run, Plan.Line l, int seed) {
        for (PieceSets.Group g : straights) {
            PieceSets.Piece p = g.pick(g.levels() ? l.level() : l.variant(), seed);
            if (p.run() == run && p.across() == width) return p;
        }
        return straights.get(0).pick(straights.get(0).levels() ? l.level() : l.variant(), seed);
    }

    private static boolean collinear(Plan.Line l, int d, List<int[]> meet, List<Plan.Line> lines) {
        if (meet.size() != 1) return false;
        Plan.Line o = lines.get(meet.get(0)[0]);
        int od = meet.get(0)[1] == 0 ? PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()})
                : (PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()}) + 2) % 4;
        return od == (d + 2) % 4;
    }

    // Per joint cell, from the last layout: {role ordinal, cells the meeting runs leave uncovered, the same with a
    // tower (-1: no tower)}, for the edit-mode labels and the "try Tower" hint.
    static final Map<Long, int[]> jointNotes = new HashMap<>();
    /** Per joint cell from the last layout: the least match of the runs meeting it ({@link PieceLayout.Fit#match}, 1 = continuous). */
    static final Map<Long, Double> jointMatch = new HashMap<>();
    /** Per joint cell from the last layout: a run stops at the joint's box (its ring is air an overlap would clear). */
    static final java.util.Set<Long> jointGapAir = new java.util.HashSet<>();

    private static void noteFit(long cell, PieceLayout.Fit f) {
        jointMatch.merge(cell, f.match(), Math::min);
        if (f.depth() < 0) jointGapAir.add(cell);
    }

    // Set by joint() when it returned null only because a blueprint is still loading.
    private static boolean jointLoading;

    /**
     * The joint piece at cell {@code c}, by the joint's type ({@link Plan#J_AUTO} unless the player chose one):
     * auto = an outer or inner corner for an L (inner when the turn bends toward the walls' outside: unflipped walls
     * face out to the left of the first segment), T or cross by how many lines meet, nothing for two lines in a
     * straight line; a tower only when chosen (or for old segments drawn with towers), or when auto's pick would cost
     * the runs whole straights a tower keeps. Among the role's variants and turns: the turn whose faces best match a
     * straight's end; the variant leaving the runs the least uncovered cells ({@link PieceLayout#pickJoint}).
     *
     * @return the piece, or null for a butt joint (none / no piece) or while pieces load ({@link #jointLoading})
     */
    private static Placed joint(Plan.Line l, PieceSets.Family f, int[] c, int d, List<int[]> meet, List<Plan.Line> lines, int i, PieceSets.Piece straight,
                                int width, int type) {
        boolean collinear = collinear(l, d, meet, lines);
        int degree = meet.size() + 1;
        // Directions out of the joint along each line, and the lines.
        List<Integer> outs = new ArrayList<>(List.of(d));
        List<Plan.Line> at = new ArrayList<>(List.of(l));
        for (int[] m : meet) {
            Plan.Line o = lines.get(m[0]);
            int od = PieceLayout.dir(new int[]{o.x0(), o.z0()}, new int[]{o.x1(), o.z1()});
            outs.add(m[1] == 0 ? od : (od + 2) % 4);
            at.add(o);
        }
        // The first role the family has, in the type's order of stand-ins; none = a butt joint.
        List<PieceSets.Role> primary = new ArrayList<>(), fallback = new ArrayList<>();
        for (PieceSets.Role r : PieceLayout.jointRoles(type, degree, collinear, l.towers()))
            if (!groups(l.pack(), f, r).isEmpty()) {
                primary.add(r);
                break;
            }
        if (primary.isEmpty()) return null;
        // A tower, for the hint only ("try Tower: 0 gap"), never picked here.
        if (!primary.contains(PieceSets.Role.TOWER)) fallback.add(PieceSets.Role.TOWER);
        List<PieceSets.Piece> cands = new ArrayList<>();
        List<PieceSets.Role> roles = new ArrayList<>();
        int primaries = 0;
        for (List<PieceSets.Role> rs : List.of(primary, fallback)) {
            for (PieceSets.Role r : rs)
                for (PieceSets.Group g : groups(l.pack(), f, r)) {
                    cands.add(g.pick(l.level(), i));
                    roles.add(r);
                }
            if (rs == primary) primaries = cands.size();
        }
        if (cands.isEmpty()) return null;
        List<int[]> fits = new ArrayList<>();
        int[] turns = new int[cands.size()];
        for (int q = 0; q < cands.size(); q++) {
            PieceSets.Piece p = cands.get(q);
            double[] contact = new double[4], shape = new double[4], bestMatch = new double[4];
            for (int k = 0; k < 4; k++) {
                PieceLayout.Grid jg = grid(l.pack(), p.path(), k);
                // Every turn must be scored, or the pick could change once the rest loads.
                if (jg == null) {
                    jointLoading = !failed(l.pack(), p.path());
                    return null;
                }
                int[] jb = PieceLayout.centred(c, jg.sx(), jg.sz());
                double score = 0;
                for (int o = 0; o < outs.size(); o++) {
                    // Each line's straight as it is really placed: turned for that line's own direction and flip (a line
                    // arriving at the joint runs the other way from one leaving it).
                    Plan.Line ln = at.get(o);
                    PieceSets.Piece st = primary(ln, 0);
                    if (st == null) continue;
                    int ldir = PieceLayout.dir(new int[]{ln.x0(), ln.z0()}, new int[]{ln.x1(), ln.z1()});
                    PieceLayout.Grid sg = grid(ln.pack(), st.path(), sturn(st, ldir, ln.flip()));
                    if (sg == null) {
                        jointLoading = !failed(ln.pack(), st.path());
                        return null;
                    }
                    // By shape and material: a road corner's faces can share a shape, its open sides share the road's blocks.
                    score += PieceLayout.faceScore(c, outs.get(o), jb, jg, sg, st.across(), st.run());
                    bestMatch[k] = Math.max(bestMatch[k], PieceLayout.fit(c, outs.get(o), jb, jg, sg, st.across(), st.run(), Math.max(3, st.across())).match());
                }
                contact[k] = score;
                // Ties (no run touches, or symmetric faces): the turn whose arms look like the line's straight.
                PieceSets.Piece st0 = primary(l, 0);
                PieceLayout.Grid sg0 = st0 == null ? null : grid(l.pack(), st0.path(), 0);
                if (sg0 != null) {
                    double[] ac = PieceLayout.armClosed(jg, outs, sg0, l.flip());
                    shape[k] = ac[0] - ac[1];
                }
            }
            int best = PieceLayout.pickTurn(contact, bestMatch, shape);
            turns[q] = best;
            // What the meeting runs keep with this piece: whole straights and uncovered cells (other ends taken as free).
            PieceLayout.Grid jg = grid(l.pack(), p.path(), best);
            int[] jb = PieceLayout.centred(c, jg.sx(), jg.sz());
            int pieces = 0, left = 0;
            for (int o = 0; o < at.size(); o++) {
                Plan.Line ln = at.get(o);
                PieceSets.Piece st = primary(ln, 0);
                if (st == null) continue;
                int ldir = PieceLayout.dir(new int[]{ln.x0(), ln.z0()}, new int[]{ln.x1(), ln.z1()});
                PieceLayout.Grid sg = grid(ln.pack(), st.path(), sturn(st, ldir, ln.flip()));
                if (sg == null) continue;
                int off = PieceLayout.attach(c, outs.get(o), jb, jg, sg, st.across(), st.run(), Math.max(3, st.across()))[0];
                int cells = Math.max(Math.abs(ln.x1() - ln.x0()), Math.abs(ln.z1() - ln.z0())) + 1 - off;
                int[] fit = PieceLayout.runFit(cells, runLengths(ln));
                pieces += fit[0];
                left += fit[1];
            }
            fits.add(new int[]{pieces, left});
        }
        int q = PieceLayout.pickJoint(fits.subList(0, primaries), primaries);
        int towerGap = -1;
        for (int k = primaries; k < fits.size(); k++) towerGap = towerGap < 0 ? fits.get(k)[1] : Math.min(towerGap, fits.get(k)[1]);
        jointNotes.put(Plan.cell(c[0], c[1]), new int[]{roles.get(q).ordinal(), fits.get(q)[1], towerGap});
        Blueprint jbp = rotated(l.pack(), cands.get(q).path(), turns[q]);
        if (jbp == null) return null;
        return place(l.pack(), cands.get(q).path(), turns[q], PieceLayout.centred(c, jbp.getSizeX(), jbp.getSizeZ()), roles.get(q));
    }


    /**
     * @return true when the two placed pieces overlap destructively: pasting either after the other would clear or
     * replace a block of the first (see {@link PieceLayout#destructive}); false while either loads
     */
    static boolean clash(String packA, Placed a, String packB, Placed b) {
        int[] x = a.box(), y = b.box();
        if (x[0] > y[2] || y[0] > x[2] || x[1] > y[3] || y[1] > x[3] || a.y0() + a.h() <= b.y0() || b.y0() + b.h() <= a.y0()) return false;
        PieceLayout.Grid ga = grid(packA, a.path(), a.turns()), gb = grid(packB, b.path(), b.turns());
        return ga != null && gb != null && PieceLayout.destructive(ga, x[0], a.y0(), x[1], gb, y[0], b.y0(), y[1]);
    }

    /** @return the quarter turns of a straight or gate running along {@code dir} (+2 when the segment is flipped) */
    static int sturn(PieceSets.Piece p, int dir, boolean flip) {
        return PieceLayout.turns(p.sx(), p.sz(), dir) + (flip ? 2 : 0);
    }

    /** @return the piece with its anchor on the ground at the box's centre (stepped per piece), or null while it loads */
    private static Placed place(String pack, String path, int turns, int[] box, PieceSets.Role role) {
        return placeAt(pack, path, turns, box, role, ground((box[0] + box[2]) / 2, (box[1] + box[3]) / 2));
    }

    /** @return the piece standing on ground level {@code level} (the first layer above the ground), or null while it loads */
    private static Placed placeAt(String pack, String path, int turns, int[] box, PieceSets.Role role, int level) {
        Blueprint bp = rotated(pack, path, turns);
        if (bp == null) return null;
        BlockPos o = bp.getPrimaryBlockOffset();
        PieceLayout.Grid gr = grid(pack, path, turns);
        if (gr == null) return null;
        int y = level - 1 + (o.getY() - gr.ground()) - 1;
        int[] g = under(box);
        return new Placed(path, turns, new BlockPos(box[0] + o.getX(), y, box[1] + o.getZ()), box, role, blocks(pack, path), y - o.getY(), bp.getSizeY(), level, g[0]);
    }

    private static int ground(int x, int z) {
        Integer g = Planner.groundAt(x, z);
        return g == null ? 64 : g;
    }

    /** @return {lowest, highest} ground level under a footprint (corners and centre) */
    private static int[] under(int[] b) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] p : new int[][]{{b[0], b[1]}, {b[2], b[1]}, {b[0], b[3]}, {b[2], b[3]}, {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2}}) {
            int g = ground(p[0], p[1]);
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
        }
        return new int[]{lo, hi};
    }

    private static Placed relevel(String pack, Placed p, int level) {
        if (p.level() == level) return p;
        Placed q = placeAt(pack, p.path(), p.turns(), p.box(), p.role(), level);
        return q == null ? p : q;
    }

    /** @return a box's position along a run direction (for ordering pieces from the run's start) */
    private static int along(int[] box, int dir) {
        return switch (dir) {
            case 0 -> box[0];
            case 2 -> -box[2];
            case 1 -> box[1];
            default -> -box[3];
        };
    }

    private static long key(int x, int z) {
        return (long) x << 32 ^ (z & 0xFFFFFFFFL);
    }
}
