package com.volcanicmg.mccitybuilder.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tiling style-pack pieces along a drawn segment. Directions: 0 = +X (east), 1 = +Z (south), 2 = -X, 3 = -Z; a
 * Structurize clockwise quarter turn takes one direction to the next. Pure.
 */
public final class PieceLayout {
    /** A piece on the ground: which one (index into the caller's list), world box (inclusive, x / z) and quarter turns. */
    public record Place(int piece, int x0, int z0, int x1, int z1, int turns) {
        public int cx() {
            return (x0 + x1) / 2;
        }

        public int cz() {
            return (z0 + z1) / 2;
        }
    }

    private PieceLayout() {
    }

    /** @return the direction from a to b (axis-aligned segments; the dominant axis otherwise) */
    public static int dir(int[] a, int[] b) {
        int dx = b[0] - a[0], dz = b[1] - a[1];
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? 0 : 2;
        return dz >= 0 ? 1 : 3;
    }

    /** @return quarter turns that bring a piece whose run is along its X (or Z) side to point in {@code dir} */
    public static int turns(int sx, int sz, int dir) {
        int nativeDir = sx >= sz ? 0 : 1;
        return Math.floorMod(dir - nativeDir, 4);
    }

    /**
     * Lengths that cover {@code n} cells best: the least left over, then the fewest pieces (coin change).
     *
     * @param lens the available piece lengths (any order)
     * @return the lengths used, longest first; the cells left over are n minus their sum
     */
    public static List<Integer> fill(int n, int[] lens) {
        if (n <= 0 || lens.length == 0) return List.of();
        int[] count = new int[n + 1], from = new int[n + 1];
        java.util.Arrays.fill(count, Integer.MAX_VALUE);
        count[0] = 0;
        for (int i = 1; i <= n; i++)
            for (int l : lens)
                if (l > 0 && l <= i && count[i - l] != Integer.MAX_VALUE && count[i - l] + 1 < count[i]) {
                    count[i] = count[i - l] + 1;
                    from[i] = l;
                }
        int best = n;
        while (best > 0 && count[best] == Integer.MAX_VALUE) best--;
        List<Integer> out = new ArrayList<>();
        for (int i = best; i > 0; i -= from[i]) out.add(from[i]);
        out.sort((x, y) -> y - x);
        return out;
    }

    /**
     * The boxes of pieces of the given lengths laid end to end from cell {@code start} along {@code dir}, centred on
     * the line across it.
     *
     * @param a      the segment's start cell {x, z}
     * @param offset cells along the run before the first piece
     * @param width  piece width across the run
     * @param shift  lateral shift (along +Z for east / west runs, +X for north / south runs)
     */
    public static List<int[]> boxes(int[] a, int dir, int offset, List<Integer> lens, int width, int shift) {
        List<int[]> out = new ArrayList<>();
        int s = offset, lo = -(width - 1) / 2 + shift, hi = width / 2 + shift;
        for (int len : lens) {
            int r0 = s, r1 = s + len - 1;
            out.add(switch (dir) {
                case 0 -> new int[]{a[0] + r0, a[1] + lo, a[0] + r1, a[1] + hi};
                case 2 -> new int[]{a[0] - r1, a[1] + lo, a[0] - r0, a[1] + hi};
                case 1 -> new int[]{a[0] + lo, a[1] + r0, a[0] + hi, a[1] + r1};
                default -> new int[]{a[0] + lo, a[1] - r1, a[0] + hi, a[1] - r0};
            });
            s += len;
        }
        return out;
    }

    /** @return the box of a joint piece (sx by sz after turning) centred on cell {@code c} */
    public static int[] centred(int[] c, int sx, int sz) {
        return new int[]{c[0] - (sx - 1) / 2, c[1] - (sz - 1) / 2, c[0] + sx / 2, c[1] + sz / 2};
    }

    /** @return |A ∩ shifted B| / |A ∪ shifted B| (1 when both are empty) */
    public static double jaccard(Set<Long> a, Set<Long> b, int shift) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int inter = 0;
        for (long v : b) if (a.contains(v + shift)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    // ---- joints: who covers the joint square, where each run starts ----

    /** @return the cells of {@code box} from {@code c} going in direction {@code d}, c included (0 when c is past it) */
    public static int reach(int[] c, int d, int[] box) {
        return Math.max(0, switch (d) {
            case 0 -> box[2] - c[0] + 1;
            case 2 -> c[0] - box[0] + 1;
            case 1 -> box[3] - c[1] + 1;
            default -> c[1] - box[1] + 1;
        });
    }

    /**
     * The square a butt joint (no joint piece) must cover once: each line's band across its run, where the lines
     * meet. East / west runs give the Z extent, north / south runs the X extent; along an axis no line crosses, the
     * joint cell alone.
     *
     * @param dirs   each meeting line's direction out of {@code c}
     * @param widths each line's piece width
     * @return {x0, z0, x1, z1}
     */
    public static int[] square(int[] c, int[] dirs, int[] widths) {
        int x0 = c[0], x1 = c[0], z0 = c[1], z1 = c[1];
        for (int k = 0; k < dirs.length; k++) {
            int lo = -(widths[k] - 1) / 2, hi = widths[k] / 2;
            if (dirs[k] % 2 == 0) {
                z0 = Math.min(z0, c[1] + lo);
                z1 = Math.max(z1, c[1] + hi);
            } else {
                x0 = Math.min(x0, c[0] + lo);
                x1 = Math.max(x1, c[0] + hi);
            }
        }
        return new int[]{x0, z0, x1, z1};
    }

    /** @return the line (index into {@code dirs}) that runs through a butt joint: the first with a line straight opposite, else the first */
    public static int owner(int[] dirs) {
        for (int k = 0; k < dirs.length; k++)
            for (int d : dirs) if (d == (dirs[k] + 2) % 4) return k;
        return 0;
    }

    /**
     * Where a run starts at a joint end, in cells from the joint cell along {@code d} (into the line): past the joint
     * piece's (or butt square's) far face, or for the butt joint's owner, back across the square so it covers it once.
     */
    public static int offset(int[] c, int d, int[] box, boolean owner) {
        return owner ? 1 - reach(c, (d + 2) % 4, box) : reach(c, d, box);
    }

    /** @return the segment length (cells) a piece needs: the joint / cap offsets plus the shortest straight; MAX_VALUE without one */
    public static int need(int off0, int off1, int[] lens) {
        int min = Integer.MAX_VALUE;
        for (int l : lens) if (l > 0) min = Math.min(min, l);
        return min == Integer.MAX_VALUE ? min : Math.max(1, off0 + off1 + min);
    }

    /**
     * @param left cells no piece covers
     * @return where the first piece starts (cells from the segment's start): uncovered cells go to a free end, so a
     * run always meets its joint
     */
    public static int runStart(int off0, int left, boolean free0, boolean free1) {
        return off0 + (free0 && !free1 ? left : 0);
    }

    // ---- piece shapes: where a run meets a joint piece's real face ----

    /**
     * What one road / wall piece's geometry says its ground anchor offset should be (its anchor goes {@code offset - 1}
     * above the terrain's top block; the layer {@code py - offset} is the ground under it), or null when it has no say.
     * Packs disagree on where the anchor sits (Structurize's default of 1 buries pieces anchored high up). A road's
     * walking surface, its topmost full layer (nearly every cell a block or substitution) that is at least a quarter
     * blocks (not a layer of substitution with a few posts poking through), replaces the
     * terrain's top block. A wall's foundation, its bottom run of full layers, ends just under the terrain's top, but
     * only when the default buries it three layers or more (smaller depths are each pack's own foundation). A
     * {@code groundlevel} tag is a road's surface, or a wall's ground as Structurize uses it.
     *
     * @param ids {@link Grid} ids (AIR, 0 substitution, blocks above 0); @param py the primary offset's y
     * @param tag the {@code groundlevel} tag's offset ({@code -tagY}), or null without one
     */
    public static Integer groundVote(int sx, int sy, int sz, int[] ids, int py, Integer tag, boolean road) {
        if (tag != null) return road ? tag + 1 : tag;
        int area = sx * sz, run = -1, top = -1;
        boolean bottom = true;
        for (int y = 0; y < sy; y++) {
            int filled = 0, solid = 0;
            for (int i = y * area; i < (y + 1) * area; i++) {
                if (ids[i] != Grid.AIR) filled++;
                if (ids[i] > 0) solid++;
            }
            // Full: 95% of the footprint (a stray air cell at a corner doesn't count).
            boolean full = filled * 20 >= area * 19;
            if (bottom && full) run = y;
            else bottom = false;
            if (full && solid * 4 >= area) top = y;
        }
        if (road) return top < 0 ? null : py - top + 1;
        if (run < 0) return null;
        return py - run >= 3 ? py - run : 1;
    }

    /**
     * Each piece's ground anchor offset. A piece in {@code own} takes its own vote; any other piece (walls, road
     * slopes, pieces without a vote) moves with its group: its default (tag, else 1) plus the most common vote minus
     * default among the group's pieces (ties: the smaller shift). A group is a folder and a level / variant number
     * ({@link PieceSets#split}), so a set's authored heights relative to each other stay as they are.
     *
     * @param pieces path → {default offset, vote or null}
     */
    public static java.util.Map<String, Integer> groundOffsets(java.util.Map<String, Integer[]> pieces, java.util.Set<String> own) {
        java.util.Map<String, java.util.Map<Integer, Integer>> votes = new java.util.HashMap<>();
        java.util.function.Function<String, String> group = path -> path.substring(0, Math.max(0, path.lastIndexOf('/'))) + "#"
                + PieceSets.split(path.substring(path.lastIndexOf('/') + 1))[1];
        for (var e : pieces.entrySet())
            if (e.getValue()[1] != null) votes.computeIfAbsent(group.apply(e.getKey()), k -> new java.util.HashMap<>()).merge(e.getValue()[1] - e.getValue()[0], 1, Integer::sum);
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (var e : pieces.entrySet()) {
            if (own.contains(e.getKey()) && e.getValue()[1] != null) {
                out.put(e.getKey(), e.getValue()[1]);
                continue;
            }
            int shift = 0, best = 0;
            for (var v : votes.getOrDefault(group.apply(e.getKey()), java.util.Map.of()).entrySet())
                if (v.getValue() > best || v.getValue() == best && v.getKey() < shift) {
                    best = v.getValue();
                    shift = v.getKey();
                }
            out.put(e.getKey(), e.getValue()[0] + shift);
        }
        return out;
    }

    /**
     * @return true for a piece that sits by its own vote ({@link #groundOffsets}): a flat road piece. Slopes, lamps
     * and bridges (whose top full layer is a deck above the road) move with their set instead.
     */
    public static boolean ownGround(String path) {
        String base = PieceSets.split(path.substring(path.lastIndexOf('/') + 1))[0];
        PieceSets.Role r = PieceSets.role(base);
        return "road".equals(PieceSets.tool(path)) && r != PieceSets.Role.SLOPE && r != PieceSets.Role.OTHER && !base.toLowerCase(java.util.Locale.ROOT).contains("bridge");
    }

    /**
     * A piece's blocks, as Structurize stores them: {@code ids[(y * sz + z) * sx + x]}: a block id above 0,
     * {@link #AIR} (pasting clears the world there) or 0 = substitution (pasting leaves the world as it is).
     *
     * @param ground the local y level that sits at the ground (so pieces with different foundations compare by height
     *               above ground)
     * @param hidden layers above ground the joint fit ignores ({@link #visible}): the piece's ground offset minus
     *               Structurize's default, so the fit compares the same cross-sections however high the piece stands
     */
    public record Grid(int sx, int sy, int sz, int[] ids, int ground, int hidden) {
        public static final int AIR = -1;

        public Grid(int sx, int sy, int sz, int[] ids, int ground) {
            this(sx, sy, sz, ids, ground, 0);
        }

        public int id(int x, int y, int z) {
            return ids[(y * sz + z) * sx + x];
        }

        /** @return this piece turned {@code k} quarter turns clockwise, like Structurize ((x, z) to (sz-1-z, x)) */
        public Grid turned(int k) {
            Grid g = this;
            for (int t = 0; t < Math.floorMod(k, 4); t++) {
                int nsx = g.sz, nsz = g.sx;
                int[] o = new int[g.ids.length];
                for (int y = 0; y < g.sy; y++)
                    for (int z = 0; z < g.sz; z++)
                        for (int x = 0; x < g.sx; x++) o[(y * nsz + x) * nsx + (g.sz - 1 - z)] = g.id(x, y, z);
                g = new Grid(nsx, g.sy, nsz, o, g.ground, g.hidden);
            }
            return g;
        }

        /** @return solid cells in slice {@code i} from the given face inward (0 = the face's own layer) */
        int slice(int face, int i) {
            int n = 0;
            for (int y = 0; y < sy; y++)
                if (face % 2 == 0) {
                    int x = face == 0 ? sx - 1 - i : i;
                    for (int z = 0; z < sz; z++) if (id(x, y, z) > 0) n++;
                } else {
                    int z = face == 1 ? sz - 1 - i : i;
                    for (int x = 0; x < sx; x++) if (id(x, y, z) > 0) n++;
                }
            return n;
        }

        /**
         * @return how many layers from {@code face} inward are only trim (fewer than half the blocks of the fullest
         * layer: an overhanging parapet, an empty margin); the body starts after them
         */
        public int inset(int face) {
            int n = face % 2 == 0 ? sx : sz, max = 0;
            for (int i = 0; i < n; i++) max = Math.max(max, slice(face, i));
            for (int i = 0; i < n; i++) if (slice(face, i) * 2 >= max && max > 0) return i;
            return 0;
        }

        /**
         * @param base the world lateral coordinate of local 0 minus the reference
         * @return the body's face (layer {@code inset} from {@code face}) as profile cells: height above ground and
         * lateral position, plus the block id when {@code withIds}
         */
        public Set<Long> profile(int face, int inset, int base, boolean withIds) {
            Set<Long> out = new java.util.HashSet<>();
            for (int y = 0; y < sy; y++)
                if (face % 2 == 0) {
                    int x = face == 0 ? sx - 1 - inset : inset;
                    for (int z = 0; z < sz; z++) add(out, id(x, y, z), y, base + z, withIds);
                } else {
                    int z = face == 1 ? sz - 1 - inset : inset;
                    for (int x = 0; x < sx; x++) add(out, id(x, y, z), y, base + x, withIds);
                }
            return out;
        }

        private void add(Set<Long> out, int id, int y, int lateral, boolean withIds) {
            if (id <= 0) return;
            long v = (long) (y - ground + 1000) * 65536 + lateral + 30000;
            out.add(withIds ? v + ((long) (id & 0x7FFF) << 32) : v);
        }
    }

    /**
     * How a run meets a joint piece's face: where it starts, its sideways shift, and how well it continues the piece.
     *
     * @param off   cells from the joint cell to the run's first cell; @param shift its lateral shift
     * @param match how well the run's visible end face continues the joint layer it meets (Jaccard over the cells
     *              above ground, 1 = the same cross-section: no air gap, no sideways step); @param depth that layer
     *              (from the joint's face inward; -1 when the run stops at the box with nothing to meet)
     */
    public record Fit(int off, int shift, double match, int depth) {
    }

    /**
     * Cells of a profile from the piece's default ground layer up ({@link Grid#hidden} above its ground): the part the
     * joint fit compares (foundations never decide).
     */
    public static Set<Long> visible(Set<Long> profile, Grid g) {
        Set<Long> out = new java.util.HashSet<>();
        for (long v : profile) if (((v >> 16) & 0xFFFF) - 1000 >= g.hidden()) out.add(v);
        return out;
    }

    /**
     * Brute force over the sideways shift (within ±maxShift): the run's end face (the straight's solid face toward the
     * joint, trim skipped) meets the joint at the first layer from its face inward holding blocks in front of at least
     * half its visible cells ({@link #visible}: foundations are buried, so they never decide), scored by how well the
     * two visible cross-sections match (Jaccard: 1 = the same shape, no air gap, no sideways step). A contact whose
     * overlap would be destructive in either build order, or no contact at all (a tower whose ring is air), stops the
     * run at the joint's box with match 0 (a few stray blocks touching aren't a joint). Best match first, then the shallowest contact, then the run centred on the
     * joint's fullest visible layer, then the smallest shift.
     *
     * @param jb       the joint's world box; @param joint the joint piece as placed (turned)
     * @param straight the run's straight as placed (turned for the run's direction); its face toward the joint is (d+2)%4
     * @param d        the direction from the joint cell into the run
     */
    public static Fit fit(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        // A player-set alignment wins over the search (it is looked up every time, so a change shows at once).
        int[] o = align.get(joint, straight, d);
        if (o != null && !destructiveAt(c, d, jb, joint, straight, width, run, o[0], o[1])) return fitAt(c, d, jb, joint, straight, width, run, o[0], o[1]);
        // The result depends only on the pieces and the joint box relative to the joint cell, never on where it is.
        FitKey k = new FitKey(joint, straight, d, width, run, maxShift, jb[0] - c[0], jb[1] - c[1], jb[2] - c[0], jb[3] - c[1]);
        Fit f = FITS.get(k);
        if (f == null) {
            if (FITS.size() > 50_000) FITS.clear();
            f = fitNow(c, d, jb, joint, straight, width, run, maxShift);
            FITS.put(k, f);
        }
        return f;
    }

    /** Player-set alignments (the joint alignment editor): for a joint and a straight as placed and the run's direction. */
    public interface AlignSource {
        /** @return {shift, depth} to use instead of the search, or null */
        int[] get(Grid joint, Grid straight, int d);
    }

    private static AlignSource align = (j, s, d) -> null;

    /** Sets where player alignments come from (the client's per-pack overrides). */
    public static void setAlign(AlignSource a) {
        align = a == null ? (j, s, d) -> null : a;
    }

    /**
     * A run placed by hand: sideways by {@code shift}, butting on the joint's layer {@code depth} from its face
     * inward (-1 = at the joint's box), with the match of the two visible cross-sections there.
     */
    public static Fit fitAt(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int shift, int depth) {
        int si = straight.inset((d + 2) % 4), edge = reach(c, d, jb), ref = d % 2 == 0 ? c[1] : c[0];
        if (depth < 0) return new Fit(edge, shift, 0, -1);
        int[] sbox0 = boxes(c, d, edge, List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox0[1] : sbox0[0]) - ref;
        Set<Long> S = visible(straight.profile((d + 2) % 4, si, sa, false), straight);
        int layers = d % 2 == 0 ? joint.sx() : joint.sz();
        double m = depth < layers ? jaccard(visible(joint.profile(d, depth, ja, false), joint), S, shift) : 0;
        return new Fit(edge - depth - si, shift, m, depth);
    }

    /** @return true when a run placed by hand ({@link #fitAt}) would clear or replace a block of the joint in either build order */
    public static boolean destructiveAt(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int shift, int depth) {
        Fit f = fitAt(c, d, jb, joint, straight, width, run, shift, depth);
        int[] bx = boxes(c, d, f.off(), List.of(run), width, shift).get(0);
        return destructive(joint, jb[0], -joint.ground(), jb[1], straight, bx[0], -straight.ground(), bx[1]);
    }

    /**
     * @return the override key of an alignment: the joint piece and its turn relative to the run's direction, the
     * straight and its turn relative to it (which side faces out), so the same setting holds wherever and however the
     * shape is turned
     */
    public static String alignKey(String jointPath, int jointTurns, String straightPath, int straightTurns, int d) {
        return "align|" + jointPath + "|" + Math.floorMod(jointTurns - d, 4) + "|" + straightPath + "|" + Math.floorMod(straightTurns - d, 4);
    }

    /** @return {shift, depth} from an override value "shift,depth", or null when damaged */
    public static int[] parseAlign(String v) {
        try {
            String[] f = v.split(",");
            int s = Integer.parseInt(f[0].trim()), dp = Integer.parseInt(f[1].trim());
            return f.length == 2 && Math.abs(s) <= 16 && dp >= -1 && dp <= 32 ? new int[]{s, dp} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link #fit} without the cache (the self-check compares the two). */
    public static Fit fitNow(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        int si = straight.inset((d + 2) % 4), edge = reach(c, d, jb), ref = d % 2 == 0 ? c[1] : c[0];
        int[] sbox0 = boxes(c, d, edge, List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox0[1] : sbox0[0]) - ref;
        Set<Long> S = visible(straight.profile((d + 2) % 4, si, sa, false), straight);
        int layers = d % 2 == 0 ? joint.sx() : joint.sz();
        List<Set<Long>> J = new ArrayList<>();
        Set<Long> fullest = Set.of();
        for (int i = 0; i < layers; i++) {
            J.add(visible(joint.profile(d, i, ja, false), joint));
            if (J.get(i).size() > fullest.size()) fullest = J.get(i);
        }
        double cj = centre(fullest), cs = S.isEmpty() ? cj : centre(S);
        Fit best = null;
        double bestOff = 0;
        for (int s = -maxShift; s <= maxShift; s++) {
            double off = Math.abs(cj - cs - s);
            Fit f = new Fit(edge, s, 0, -1);
            for (int i = 0; i < layers && !S.isEmpty(); i++) {
                int hit = 0;
                for (long v : S) if (J.get(i).contains(v + s)) hit++;
                if (hit * 2 < S.size()) continue;
                int[] bx = boxes(c, d, edge - i - si, List.of(run), width, s).get(0);
                if (i + si == 0 || !(jointFirst ? destructiveAfter(joint, jb[0], -joint.ground(), jb[1], straight, bx[0], -straight.ground(), bx[1])
                        : destructive(joint, jb[0], -joint.ground(), jb[1], straight, bx[0], -straight.ground(), bx[1])))
                    f = new Fit(edge - i - si, s, jaccard(J.get(i), S, s), i);
                break;
            }
            if (better(f, off, best, bestOff)) {
                best = f;
                bestOff = off;
            }
        }
        if (best != null && best.depth() < 0) best = new Fit(edge, noContactShift(d, ja, sa, joint, straight, width, best.shift()), 0, -1);
        return best;
    }

    /**
     * A run that touches the joint nowhere (lamps or posts whose air would clear each other): its lateral span stays
     * within the joint's (its arms never overhang the joint's outer edges); centred, unless the joint's outer face
     * continues the straight's very end clearly better at another shift (half its blocks or more). A joint more than
     * two cells wider than the run (a tower) keeps the run on its centre line.
     *
     * @param ja the joint's lateral start and {@code sa} the run's at shift 0 (relative to the joint cell)
     * @param centred the shift that centres the run on the joint's fullest layer
     */
    static int noContactShift(int d, int ja, int sa, Grid joint, Grid straight, int width, int centred) {
        int jl = d % 2 == 0 ? joint.sz() : joint.sx();
        int lo = width <= jl ? ja - sa : ja + jl - width - sa, hi = width <= jl ? ja + jl - width - sa : ja - sa;
        int base = Math.max(lo, Math.min(hi, centred));
        if (jl - width > 2) return base;
        // The straight's very end (its trim inset may stand on a lamp post, not the road's cross-section).
        Set<Long> J = visible(joint.profile(d, 0, ja, true), joint), S = visible(straight.profile((d + 2) % 4, 0, sa, true), straight);
        double bj = jaccard(J, S, base);
        int at = base;
        for (int s = lo; s <= hi; s++) {
            double j = jaccard(J, S, s);
            if (j > bj + 0.1 && j >= 0.8) {
                bj = j;
                at = s;
            }
        }
        return at;
    }

    /**
     * How much the joint's outer face toward {@code f} looks like a straight's very end facing it (blocks and all,
     * {@link #visible} cut, the best lateral shift): an open arm of a corner shows the road's or wall's
     * cross-section, a closed side its border.
     *
     * @param straight the straight as laid along x or z (any turn): it is turned to run out in {@code f}
     */
    public static double faceLike(Grid joint, int f, Grid straight, boolean flip) {
        Grid st = straight.turned(turns(straight.sx(), straight.sz(), f) + (flip ? 2 : 0));
        Set<Long> J = visible(joint.profile(f, 0, 0, true), joint), S = visible(st.profile((f + 2) % 4, 0, 0, true), st);
        int span = Math.max(Math.max(joint.sx(), joint.sz()), Math.max(st.sx(), st.sz()));
        double best = 0;
        for (int s = -span; s <= span; s++) best = Math.max(best, jaccard(J, S, s));
        return best;
    }

    /** @return {arm, closed}: the mean {@link #faceLike} of the joint's faces toward {@code outs} and of its other faces (0 with none) */
    public static double[] armClosed(Grid joint, List<Integer> outs, Grid straight, boolean flip) {
        double arm = 0, closed = 0;
        int na = 0, nc = 0;
        for (int f = 0; f < 4; f++) {
            double v = faceLike(joint, f, straight, flip);
            if (outs.contains(f)) {
                arm += v;
                na++;
            } else {
                closed += v;
                nc++;
            }
        }
        return new double[]{na == 0 ? 0 : arm / na, nc == 0 ? 0 : closed / nc};
    }

    /** Below this best run match no turn is trusted to touch well: the corner's shape decides ({@link #pickTurn}). */
    public static double contactFloor = 0;

    /**
     * @param contact per turn, the summed {@link #faceScore} (runs that touch); @param bestMatch per turn, its best run
     *                match; @param shape per turn, arm minus closed ({@link #armClosed})
     * @return the turn to use: the most contact, the first on a tie; but when no turn has a run matching at least
     * {@link #contactFloor} (or none touches at all), the one whose arms look most like the straight's end against its
     * closed sides
     */
    public static int pickTurn(double[] contact, double[] bestMatch, double[] shape) {
        int best = 0;
        boolean weak = true;
        for (int k = 0; k < contact.length; k++) {
            weak &= contact[k] < 1e-9 || bestMatch[k] < contactFloor;
            if (contact[k] > contact[best] + 1e-9) best = k;
        }
        if (!weak) return best;
        best = 0;
        for (int k = 1; k < shape.length; k++) if (shape[k] > shape[best] + 1e-9) best = k;
        return best;
    }

    /** @return true when f beats best: more match, then shallower, then more centred ({@code off}), then a smaller shift */
    private static boolean better(Fit f, double off, Fit best, double bestOff) {
        if (best == null || f.match() > best.match() + 1e-9) return true;
        if (f.match() < best.match() - 1e-9 || f.depth() > best.depth()) return false;
        if (f.depth() < best.depth() || off < bestOff - 1e-9) return true;
        return off < bestOff + 1e-9 && Math.abs(f.shift()) < Math.abs(best.shift());
    }

    /** @return the mean lateral coordinate of a profile (its low 16 bits) */
    private static double centre(Set<Long> p) {
        if (p.isEmpty()) return 0;
        double sum = 0;
        for (long v : p) sum += v & 0xFFFF;
        return sum / p.size();
    }

    /** @return {cells from the joint cell to the run's first cell, lateral shift} ({@link #fit}) */
    public static int[] attach(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift) {
        Fit f = fit(c, d, jb, joint, straight, width, run, maxShift);
        return new int[]{f.off(), f.shift()};
    }

    /**
     * @param ax the world x of a's local 0 (and so on; y relative to a common ground)
     * @return true when pasting one piece after the other, in either order, would clear or replace a block the first
     * placed: the second has air or a different block in a cell where the first has a block (substitution in the
     * second leaves it)
     */
    public static boolean destructive(Grid a, int ax, int ay, int az, Grid b, int bx, int by, int bz) {
        int x0 = Math.max(ax, bx), x1 = Math.min(ax + a.sx(), bx + b.sx()), y0 = Math.max(ay, by), y1 = Math.min(ay + a.sy(), by + b.sy());
        int z0 = Math.max(az, bz), z1 = Math.min(az + a.sz(), bz + b.sz());
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++)
                for (int z = z0; z < z1; z++) {
                    int ia = a.id(x - ax, y - ay, z - az), ib = b.id(x - bx, y - by, z - bz);
                    if (hurts(ia, ib) || hurts(ib, ia)) return true;
                }
        return false;
    }

    // Memoised fits and face scores: keyed by the pieces (grids are cached per piece and turn, so the same objects
    // come back; a record compares their block arrays by identity) and the joint box relative to the joint cell.
    private record FitKey(Grid joint, Grid straight, int d, int width, int run, int maxShift, int x0, int z0, int x1, int z1) {
    }

    private static final java.util.Map<FitKey, Fit> FITS = new java.util.HashMap<>();
    private static final java.util.Map<FitKey, Double> SCORES = new java.util.HashMap<>();

    /** Forgets the memoised fits (pieces reloaded). */
    public static void clearCache() {
        FITS.clear();
        SCORES.clear();
    }

    /**
     * Joints are always pasted before the runs meeting them (creative batches send them first): a run then only hurts a
     * joint where its air would clear a joint block; its blocks may fill the joint's air (a tower's parapet ring), so
     * runs reach the tower body. Off for survival, where builders take orders in any order.
     */
    public static boolean jointFirst;

    /** @return true when {@code b}, pasted after {@code a}, would clear or replace a block of {@code a} */
    public static boolean destructiveAfter(Grid a, int ax, int ay, int az, Grid b, int bx, int by, int bz) {
        int x0 = Math.max(ax, bx), x1 = Math.min(ax + a.sx(), bx + b.sx()), y0 = Math.max(ay, by), y1 = Math.min(ay + a.sy(), by + b.sy());
        int z0 = Math.max(az, bz), z1 = Math.min(az + a.sz(), bz + b.sz());
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++)
                for (int z = z0; z < z1; z++) if (hurts(a.id(x - ax, y - ay, z - az), b.id(x - bx, y - by, z - bz))) return true;
        return false;
    }

    private static boolean hurts(int first, int second) {
        return first > 0 && second != 0 && second != first;
    }

    /**
     * @return how well a turned joint piece's face toward {@code d} continues a straight leaving it there ({@link #fit}'s
     * match), plus how well the visible blocks match at the contact (a road corner's open and closed sides can share
     * a shape)
     */
    public static double faceScore(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run) {
        FitKey k = new FitKey(joint, straight, d, width, run, -1, jb[0] - c[0], jb[1] - c[1], jb[2] - c[0], jb[3] - c[1]);
        Double v = SCORES.get(k);
        if (v == null) {
            if (SCORES.size() > 50_000) SCORES.clear();
            v = faceScoreNow(c, d, jb, joint, straight, width, run);
            SCORES.put(k, v);
        }
        return v;
    }

    /** {@link #faceScore} without the cache. */
    public static double faceScoreNow(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run) {
        Fit f = fit(c, d, jb, joint, straight, width, run, Math.max(3, width));
        int si = straight.inset((d + 2) % 4), ref = d % 2 == 0 ? c[1] : c[0];
        if (f.depth() < 0) return 0;
        int[] sbox = boxes(c, d, f.off(), List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox[1] : sbox[0]) - ref;
        return f.match() + jaccard(visible(joint.profile(d, f.depth(), ja, true), joint), visible(straight.profile((d + 2) % 4, si, sa, true), straight), f.shift());
    }

    // ---- heights ----

    /**
     * Ground levels for a segment's pieces, in order along the run.
     *
     * @param mode  {@link Plan#FOLLOW} (each on its own ground), {@link Plan#SMOOTH} (raised so neighbours differ by
     *              at most 1), {@link Plan#FLAT} (all at the highest ground under them) or {@link Plan#CHAIN} (all at
     *              the chain's level, {@link #chainBase})
     * @param own   each piece's ground (at its centre); @param high the highest ground under each piece
     * @param dy    blocks added; @param base the chain's level, or {@link Plan#NO_BASE}
     */
    public static int[] levels(int mode, int[] own, int[] high, int dy, int base) {
        int[] out;
        int max = Integer.MIN_VALUE;
        for (int h : high) max = Math.max(max, h);
        switch (mode) {
            case Plan.SMOOTH -> out = Roads.smooth(own, 1);
            case Plan.FLAT -> {
                out = new int[own.length];
                java.util.Arrays.fill(out, max);
            }
            case Plan.CHAIN -> {
                out = new int[own.length];
                java.util.Arrays.fill(out, chainBase(base, max));
            }
            default -> out = own.clone();
        }
        for (int i = 0; i < out.length; i++) out[i] += dy;
        return out;
    }

    /** @return the chain's level after a segment with this highest ground joins it: never below any of its ground */
    public static int chainBase(int base, int segMax) {
        return base == Plan.NO_BASE ? segMax : Math.max(base, segMax);
    }

    /**
     * @return a joint piece's level: the highest of its own ground and the first pieces of the runs meeting it (those
     * pieces then take the same level, {@link #shareSeams}: one Y at every seam, runs step only further away)
     */
    public static int jointLevel(int own, int[] neighbours) {
        int best = own;
        for (int n : neighbours) best = Math.max(best, n);
        return best;
    }

    /** Neighbour kinds for {@link #jointLevel(int, int[], int[])}: a run that follows the joint, a flat / chain run, a built run. */
    public static final int FOLLOWS = 0, FLAT = 1, BUILT = 2;

    /**
     * A joint touching built segments stands at their level (the world has their blocks, maybe from an older height
     * rule): the highest built neighbour, unless a flat / chain run is higher still (it doesn't move, so the joint
     * meets it instead, as {@link #jointLevel(int, int[])} does); with no built neighbour, that rule.
     *
     * @param kinds per neighbour, {@link #FOLLOWS}, {@link #FLAT} or {@link #BUILT}
     */
    public static int jointLevel(int own, int[] neighbours, int[] kinds) {
        int built = Integer.MIN_VALUE, flat = Integer.MIN_VALUE;
        for (int q = 0; q < neighbours.length; q++) {
            if (kinds[q] == BUILT) built = Math.max(built, neighbours[q]);
            if (kinds[q] == FLAT) flat = Math.max(flat, neighbours[q]);
        }
        return built != Integer.MIN_VALUE && flat <= built ? built : jointLevel(own, neighbours);
    }

    /**
     * @return the levels with piece {@code idx} set to {@code level} (up or down) and the rest following outward, each
     * within one block of the piece before it and otherwise as it was: a smoothed run meeting a built joint
     */
    public static int[] setSmooth(int[] lv, int idx, int level) {
        int[] out = lv.clone();
        out[idx] = level;
        for (int k = idx + 1; k < out.length; k++) out[k] = Math.max(out[k - 1] - 1, Math.min(out[k - 1] + 1, lv[k]));
        for (int k = idx - 1; k >= 0; k--) out[k] = Math.max(out[k + 1] - 1, Math.min(out[k + 1] + 1, lv[k]));
        return out;
    }

    /** @return the index of the box {x0, z0, x1, z1} nearest the cell (Chebyshev distance, 0 inside), or -1 with none */
    public static int nearest(List<int[]> boxes, int[] c) {
        int best = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            int d = Math.max(Math.max(b[0] - c[0], c[0] - b[2]), Math.max(b[1] - c[1], c[1] - b[3]));
            d = Math.max(0, d);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * @return the levels with piece {@code idx} raised to at least {@code level} and the rest raised outward from it
     * so neighbours differ by at most 1 (never lowered): a smoothed run whose first piece rose to its joint
     */
    public static int[] raiseSmooth(int[] lv, int idx, int level) {
        int[] out = lv.clone();
        out[idx] = Math.max(out[idx], level);
        for (int k = idx + 1; k < out.length; k++) out[k] = Math.max(out[k], out[k - 1] - 1);
        for (int k = idx - 1; k >= 0; k--) out[k] = Math.max(out[k], out[k + 1] - 1);
        return out;
    }

    /**
     * Levels along a run with every seam at a cap or gate levelled: such a piece and the run pieces next to it take
     * the highest of their levels, so the pieces meet with no step (follow / smooth modes; flat runs are flat already).
     *
     * @param special which pieces are caps or gates
     */
    public static int[] shareSeams(int[] lv, boolean[] special) {
        int[] out = lv.clone();
        for (int i = 0; i < out.length; i++) {
            if (!special[i]) continue;
            int lo = Math.max(0, i - 1), hi = Math.min(out.length - 1, i + 1), m = Integer.MIN_VALUE;
            for (int k = lo; k <= hi; k++) m = Math.max(m, out[k]);
            for (int k = lo; k <= hi; k++) out[k] = m;
        }
        return out;
    }

    // ---- joint choice ----

    /** @return {whole straights, uncovered cells} for a run of {@code cells} tiled with {@code lens} */
    public static int[] runFit(int cells, int[] lens) {
        if (cells <= 0 || lens.length == 0) return new int[]{0, Math.max(0, cells)};
        List<Integer> f = fill(cells, lens);
        int sum = 0;
        for (int l : f) sum += l;
        return new int[]{f.size(), cells - sum};
    }

    /**
     * @param fits      per candidate joint piece: {whole straights the meeting runs keep, cells they leave uncovered}
     * @param primaries how many candidates (first in the list) the joint's type asks for; the rest are fallbacks
     *                  (a tower for an auto joint), taken only when they keep strictly more straights
     * @return the candidate to use: the primary leaving the fewest cells uncovered (then more straights, then order)
     */
    public static int pickJoint(List<int[]> fits, int primaries) {
        int best = 0;
        for (int q = 1; q < Math.max(1, primaries); q++) {
            int[] a = fits.get(q), b = fits.get(best);
            if (a[1] < b[1] || a[1] == b[1] && a[0] > b[0]) best = q;
        }
        int fb = -1;
        for (int q = primaries; q < fits.size(); q++)
            if (fits.get(q)[0] > fits.get(best)[0] && (fb < 0 || fits.get(q)[0] > fits.get(fb)[0] || fits.get(q)[0] == fits.get(fb)[0] && fits.get(q)[1] < fits.get(fb)[1])) fb = q;
        return fb >= 0 ? fb : best;
    }

    /**
     * @param outA the first line's direction out of the joint (travel arrives along it); @param outB the other's
     * @param flip the first line's flip (unflipped walls face out to the left of travel)
     * @return true when the turn bends toward the outside: an inner corner
     */
    public static boolean innerTurn(int outA, int outB, boolean flip) {
        int arrive = (outA + 2) % 4, turn = Math.floorMod(outB - arrive, 4);
        // Directions run clockwise seen from above (east, south, west, north): +1 is a right turn.
        boolean left = turn == 3;
        return left != flip;
    }

    /** Auto joint roles: none (a straight line), outer corner, inner corner, T, cross. */
    public static final int NONE = 0, CORNER = 1, INNER = 2, TEE = 3, CROSS = 4;

    /**
     * Auto's joint from the topology alone: how many lines meet, straight on or not. Every L gets the plain corner
     * (turned to fit either way round); the inner corner and the tower are only used when chosen per joint, so the
     * same gesture turning left or right looks the same ({@link #innerTurn} stays for the hints).
     */
    public static int autoRole(int degree, boolean collinear, int outA, int outB, boolean flip) {
        if (degree >= 4) return CROSS;
        if (degree == 3) return TEE;
        return collinear ? NONE : CORNER;
    }

    /**
     * The piece roles a joint may use, best first: the chosen type's role (or auto's, {@link #autoRole}), then the
     * stand-ins when the family lacks it (a corner for an inner corner and the other way round, a tower for a T or
     * cross and for a tower-less family's tower pick a corner); a free end its cap, or a tower when chosen. Empty = a
     * butt joint (none, or straight on) or a bare end.
     *
     * @param type   {@link Plan#J_AUTO} .. {@link Plan#J_NONE}; @param towers the line was drawn with towers
     */
    public static List<PieceSets.Role> jointRoles(int type, int degree, boolean collinear, boolean towers) {
        if (type == Plan.J_NONE) return List.of();
        // A free end: its cap, or a tower standing at the wall's end when chosen.
        if (degree <= 1) return type == Plan.J_TOWER ? List.of(PieceSets.Role.TOWER, PieceSets.Role.END) : List.of(PieceSets.Role.END);
        if (type == Plan.J_CAP) type = Plan.J_AUTO;
        if (type == Plan.J_AUTO) {
            if (towers) return List.of(PieceSets.Role.TOWER, PieceSets.Role.CORNER, PieceSets.Role.INNER);
            int auto = autoRole(degree, collinear, 0, 0, false);
            type = auto == NONE ? Plan.J_NONE : auto == CROSS ? Plan.J_CROSS : auto == TEE ? Plan.J_TEE : Plan.J_CORNER;
        }
        return switch (type) {
            case Plan.J_TOWER -> List.of(PieceSets.Role.TOWER, PieceSets.Role.CORNER, PieceSets.Role.INNER);
            case Plan.J_CORNER -> List.of(PieceSets.Role.CORNER, PieceSets.Role.INNER);
            case Plan.J_INNER -> List.of(PieceSets.Role.INNER, PieceSets.Role.CORNER);
            case Plan.J_TEE -> List.of(PieceSets.Role.TEE, PieceSets.Role.TOWER);
            case Plan.J_CROSS -> List.of(PieceSets.Role.CROSS, PieceSets.Role.TOWER);
            default -> List.of();
        };
    }
}
