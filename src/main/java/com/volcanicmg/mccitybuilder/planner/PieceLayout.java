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

    /**
     * @param widths each line's straight width at a joint (-1 while it loads); @param levels each line's level
     * @return the line whose level the joint piece takes: the widest, then the highest level, then the first (so the
     * drawing order never decides); -1 while a width is unknown (the joint waits rather than flickering)
     */
    public static int jointLevelLine(int[] widths, int[] levels) {
        for (int w : widths) if (w < 0) return -1;
        int best = 0;
        for (int q = 1; q < widths.length; q++)
            if (widths[q] > widths[best] || widths[q] == widths[best] && levels[q] > levels[best]) best = q;
        return best;
    }

    /**
     * The frame a joint is laid out in: an L turned so its arms point west and north, a T so its open side is north, a
     * free end's tower so its arm points west, a cross so the arm whose straight runs unlike the other three's points
     * west (the orientations the self-check pictures were reviewed in); anything else by its key's rotation.
     *
     * @param kinds per arm, its straight's turns less its direction out (rotation-free); null for none
     * @param keyRot {@link #jointKey}'s rotation for the configuration
     * @return the quarter turns from that frame to the world
     */
    public static int frameRot(int[] outs, int[] kinds, int keyRot) {
        if (outs.length == 2 && outs[0] % 2 != outs[1] % 2)
            for (int r = 0; r < 4; r++) {
                int a = Math.floorMod(outs[0] - r, 4), b = Math.floorMod(outs[1] - r, 4);
                if (a + b == 5 && a * b == 6) return r;
            }
        if (outs.length == 1) return Math.floorMod(outs[0] - 2, 4);
        if (outs.length == 3) {
            int missing = 0 + 1 + 2 + 3;
            for (int o : outs) missing -= o;
            return Math.floorMod(missing - 3, 4);
        }
        if (outs.length == 4 && kinds != null)
            for (int q = 0; q < 4; q++) {
                int same = 0;
                for (int k : kinds) if (Math.floorMod(k - kinds[q], 4) == 0) same++;
                if (same == 1) {
                    boolean restAlike = true;
                    for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) if (a != q && b != q) restAlike &= Math.floorMod(kinds[a] - kinds[b], 4) == 0;
                    if (restAlike) return Math.floorMod(outs[q] - 2, 4);
                }
            }
        return keyRot;
    }

    /**
     * An L can be laid out with its arms west and north, or west and south (both reviewed shapes, whose straights then
     * meet the corner differently when the two arms' straights differ): the layout searches both and keeps the one
     * whose runs meet the corner better (the first on a tie). Anything else has one frame ({@link #frameRot}).
     *
     * @return the candidate frames' quarter turns to the world
     */
    public static int[] frameRots(int[] outs, int[] kinds, int keyRot) {
        if (outs.length == 2 && outs[0] % 2 != outs[1] % 2)
            for (int r = 0; r < 4; r++) {
                int a = Math.floorMod(outs[0] - r, 4), b = Math.floorMod(outs[1] - r, 4);
                if (a + b == 3 && a * b == 2) return new int[]{frameRot(outs, kinds, keyRot), r};
            }
        return new int[]{frameRot(outs, kinds, keyRot)};
    }

    /** @return how well a frame's layout meets its runs: the weakest run's match first, then the summed contact */
    public static double frameScore(double worstMatch, double contact) {
        return Math.round(worstMatch * 1000) * 100.0 + contact;
    }

    /**
     * Breaks a tie between an L's frames the same way whichever way it is drawn: runs are better flush with the L's
     * outer edge (away from the other arm) than inside it.
     *
     * @param boxes each run's box
     * @return how many runs are flush with the joint box's outer edge
     */
    public static int outerFlush(int[] outs, int[] jb, List<int[]> boxes) {
        int n = 0;
        for (int q = 0; q < outs.length; q++) {
            int d = outs[q], other = -1, lat = d % 2 == 0 ? 1 : 0;
            for (int o : outs) if (o % 2 != d % 2) other = o;
            if (other < 0) continue;
            boolean high = other == 2 || other == 3;
            if (high ? boxes.get(q)[lat + 2] == jb[lat + 2] : boxes.get(q)[lat] == jb[lat]) n++;
        }
        return n;
    }

    /** @return box {x0, z0, x1, z1} turned {@code r} quarter turns clockwise about cell {@code c} (like Structurize) */
    public static int[] rotBox(int[] c, int[] b, int r) {
        int x0 = b[0] - c[0], z0 = b[1] - c[1], x1 = b[2] - c[0], z1 = b[3] - c[1];
        for (int i = 0; i < Math.floorMod(r, 4); i++) {
            int nx0 = -z1, nx1 = -z0;
            z0 = x0;
            z1 = x1;
            x0 = nx0;
            x1 = nx1;
        }
        return new int[]{c[0] + x0, c[1] + z0, c[0] + x1, c[1] + z1};
    }

    /**
     * Joints are laid out in their configuration's own frame ({@link #jointKey}'s rotation), so a shape lays out the same
     * whichever way it is drawn (the boxes' centring and the search's ties aren't symmetric): a run's fit made there
     * ({@code dC}, the direction in that frame) back in the world, turned {@code r}.
     */
    public static Fit fitBack(int[] c, int dC, Fit f, int width, int run, int r) {
        if (Math.floorMod(r, 4) == 0) return f;
        int d = Math.floorMod(dC + r, 4), lat = d % 2 == 0 ? 1 : 0;
        int[] bw = rotBox(c, boxes(c, dC, f.off(), List.of(run), width, f.shift()).get(0), r);
        return new Fit(f.off(), bw[lat] - c[lat] + (width - 1) / 2, f.match(), f.depth());
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
     * A run between two joints the straights can't cover exactly: lengths covering it exactly with one or two cells
     * more (the run reaching that much into a joint).
     *
     * @return {extra cells, lengths longest first...}, or null when no extra up to {@code maxOver} covers exactly
     */
    public static int[] overlapFill(int n, int[] lens, int maxOver) {
        for (int e = 1; e <= maxOver; e++) {
            List<Integer> f = fill(n + e, lens);
            if (f.stream().mapToInt(Integer::intValue).sum() != n + e) continue;
            int[] out = new int[f.size() + 1];
            out[0] = e;
            for (int k = 0; k < f.size(); k++) out[k + 1] = f.get(k);
            return out;
        }
        return null;
    }

    /**
     * Fill gaps (opt-in per segment): a run of {@code n} cells its straights leave uncovered gets one more straight of
     * {@code extra} cells, and two neighbouring straights overlap by the excess at one seam (never at a joint: the run
     * still starts and ends where it did). Every placement of the extra piece and the overlapping seam.
     *
     * @param tiles the run's straights in order (lengths)
     * @return per option {insert index of the extra piece, overlapping seam (between pieces seam and seam + 1 of the new
     * order), then each piece's start in cells from the run's start}; empty when it can't: no straight in the run, no
     * gap, or the overlap would reach a whole piece
     */
    public static List<int[]> gapFills(int n, List<Integer> tiles, int extra) {
        List<int[]> out = new ArrayList<>();
        int total = extra;
        for (int t : tiles) total += t;
        int over = total - n;
        if (tiles.isEmpty() || over < 1) return out;
        for (int p = 0; p <= tiles.size(); p++) {
            List<Integer> seq = new ArrayList<>(tiles);
            seq.add(p, extra);
            for (int seam = 0; seam + 1 < seq.size(); seam++) {
                if (over >= Math.min(seq.get(seam), seq.get(seam + 1))) continue;
                int[] o = new int[2 + seq.size()];
                o[0] = p;
                o[1] = seam;
                int at = 0;
                for (int k = 0; k < seq.size(); k++) {
                    o[2 + k] = at;
                    at += seq.get(k) - (k == seam ? over : 0);
                }
                out.add(o);
            }
        }
        return out;
    }

    /**
     * How well two overlapping pieces agree where they overlap (world boxes, ground-aligned heights): the share of cells
     * either one fills with a block where both hold the same block; 1 when nothing differs (the seam doesn't show).
     */
    public static double seamMatch(Grid a, int[] boxA, Grid b, int[] boxB) {
        int x0 = Math.max(boxA[0], boxB[0]), x1 = Math.min(boxA[2], boxB[2]), z0 = Math.max(boxA[1], boxB[1]), z1 = Math.min(boxA[3], boxB[3]);
        int cells = 0, same = 0;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = 0; y < a.sy(); y++) {
                    int yb = y - a.ground() + b.ground();
                    int ia = a.id(x - boxA[0], y, z - boxA[1]), ib = yb < 0 || yb >= b.sy() ? 0 : b.id(x - boxB[0], yb, z - boxB[1]);
                    if (ia <= 0 && ib <= 0) continue;
                    cells++;
                    if (ia == ib) same++;
                }
        return cells == 0 ? 1 : (double) same / cells;
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
        return fit(c, d, jb, joint, straight, width, run, maxShift, null);
    }

    /**
     * {@link #fit} with the joint's built-in setting for this run ({@link JointFix}, null for none): a player-set
     * alignment first, then the built-in one, then the search; a destructive setting is skipped.
     */
    public static Fit fit(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift, int[] builtIn) {
        // A player-set alignment wins over the search (it is looked up every time, so a change shows at once). A legacy
        // world shift only means something in the world's geometry: the caller converts it ({@link #relAlign}).
        int[] o = align.get(joint, straight, d);
        return fitWith(c, d, jb, joint, straight, width, run, maxShift, o != null && o.length > 2 ? null : o, builtIn);
    }

    /** @return the player's alignment for a joint and a straight as placed in the world (a legacy one marked), or null */
    public static int[] alignAt(Grid joint, Grid straight, int d) {
        return align.get(joint, straight, d);
    }

    /**
     * @param jb the joint's box in the world (where a legacy world shift was seen)
     * @return the alignment with a legacy world shift turned into a shift against the joint box ({@link #alignShift}),
     * so it lands where the player saw it in whatever frame the joint is laid out
     */
    public static int[] relAlign(int[] o, int[] c, int d, int[] jb, int width) {
        return o == null || o.length < 3 ? o : new int[]{alignShift(c, d, jb, width, o[0]), o[1]};
    }

    /** {@link #fit} with the player's alignment given ({shift, depth} against the joint box, or null) instead of looked up. */
    public static Fit fitWith(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift, int[] player, int[] builtIn) {
        for (int[] o : new int[][]{player, builtIn}) {
            if (o == null) continue;
            int os = worldShift(c, d, jb, width, o[0]);
            if (!destructiveAt(c, d, jb, joint, straight, width, run, os, o[1])) return fitAt(c, d, jb, joint, straight, width, run, os, o[1]);
        }
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

    /**
     * @return a run's world shift from an alignment's: the run box's right-hand edge (looking along {@code d}) less the
     * joint box's, so a setting holds however the shape is turned (the boxes' centring isn't symmetric)
     */
    public static int worldShift(int[] c, int d, int[] jb, int width, int rel) {
        int lat = d % 2 == 0 ? 1 : 0, cl = c[lat];
        return d == 0 || d == 3 ? rel - cl - width / 2 + jb[lat + 2] : jb[lat] - cl + (width - 1) / 2 - rel;
    }

    /** @return an alignment's shift for a run's world shift (the inverse of {@link #worldShift}) */
    public static int alignShift(int[] c, int d, int[] jb, int width, int shift) {
        int lat = d % 2 == 0 ? 1 : 0, cl = c[lat];
        return d == 0 || d == 3 ? shift + cl + width / 2 - jb[lat + 2] : jb[lat] - cl + (width - 1) / 2 - shift;
    }

    /** Player-set alignments (the joint alignment editor): for a joint and a straight as placed and the run's direction. */
    public interface AlignSource {
        /** @return {shift ({@link #worldShift}), depth}, {world shift, depth, 1} for a legacy one ({@link #parseAlign}), or null */
        int[] get(Grid joint, Grid straight, int d);
    }

    private static AlignSource align = (j, s, d) -> null;

    /** Sets where player alignments come from (the client's per-pack overrides). */
    public static void setAlign(AlignSource a) {
        align = a == null ? (j, s, d) -> null : a;
    }

    /**
     * A run placed by hand: sideways by {@code shift}, butting on the joint's layer {@code depth} from its face
     * inward (-1 = at the joint's box), with the match of the two visible cross-sections there (0 when under half the
     * run's cells meet a block).
     */
    public static Fit fitAt(int[] c, int d, int[] jb, Grid joint, Grid straight, int width, int run, int shift, int depth) {
        int si = straight.inset((d + 2) % 4), edge = reach(c, d, jb), ref = d % 2 == 0 ? c[1] : c[0];
        if (depth < 0) return new Fit(edge, shift, 0, -1);
        int[] sbox0 = boxes(c, d, edge, List.of(run), width, 0).get(0);
        int ja = (d % 2 == 0 ? jb[1] : jb[0]) - ref, sa = (d % 2 == 0 ? sbox0[1] : sbox0[0]) - ref;
        Set<Long> S = visible(straight.profile((d + 2) % 4, si, sa, false), straight);
        int layers = d % 2 == 0 ? joint.sx() : joint.sz();
        Set<Long> J = depth < layers ? visible(joint.profile(d, depth, ja, false), joint) : Set.of();
        int hit = 0;
        for (long v : S) if (J.contains(v + shift)) hit++;
        // Under half the run's cells meeting a block isn't a joint (as in the search).
        double m = hit * 2 < S.size() ? 0 : jaccard(J, S, shift);
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

    /** A joint configuration's override key and the quarter turns taking the shape to the key's (least) rotation. */
    public record JointKey(String key, int rot) {
    }

    /**
     * @param outs each line's direction out of the joint; @param straightTurns each line's straight as placed
     * @param symmetric per line, its straight is the same turned 180° (its turns then count modulo 2), or null
     * @return the override key of a joint configuration: the joint piece and every line's out and straight (path,
     * turns), relative to the rotation whose key sorts first, so it holds however the shape is turned
     */
    public static JointKey jointKey(String jointPath, int[] outs, String[] straightPaths, int[] straightTurns, boolean[] symmetric) {
        String best = null;
        int rot = 0;
        for (int r = 0; r < 4; r++) {
            List<String> e = new java.util.ArrayList<>();
            for (int q = 0; q < outs.length; q++)
                e.add(Math.floorMod(outs[q] - r, 4) + ":" + straightPaths[q] + ":" + Math.floorMod(straightTurns[q] - r, 4) % (symmetric != null && symmetric[q] ? 2 : 4));
            e.sort(null);
            String k = "joint|" + jointPath + "|" + String.join(",", e);
            if (best == null || k.compareTo(best) < 0) {
                best = k;
                rot = r;
            }
        }
        return new JointKey(best, rot);
    }

    /** A joint set by hand for one configuration: its turn, and per line (as given) {shift, depth} ({@link #fit}) or null. */
    public record JointFix(int turn, int[][] runs) {
    }

    /**
     * Only the exact configuration is affected (the per-run settings never reach other joints or the turn search).
     * Value: the turn in the key's rotation, then per run "out:shift,depth" with the out in that rotation. A player's
     * run alignment ({@link #alignKey}) for this configuration under some turn keeps that turn (it was set for it) and
     * the setting's runs step aside.
     *
     * @param playerAligned whether the player has an alignment saved (or drafted) under a key
     * @return the joint's setting ({@code lookup} by {@link #jointKey}), or null
     */
    public static JointFix jointFix(java.util.function.Function<String, String> lookup, java.util.function.Predicate<String> playerAligned, String jointPath,
                                    int[] outs, String[] straightPaths, int[] straightTurns, boolean[] symmetric) {
        JointKey k = jointKey(jointPath, outs, straightPaths, straightTurns, symmetric);
        String v = lookup.apply(k.key());
        if (v == null) return null;
        for (int t = 0; t < 4; t++)
            for (int q = 0; q < outs.length; q++)
                if (playerAligned.test(alignKey(jointPath, t, straightPaths[q], straightTurns[q], outs[q]))) return new JointFix(t, new int[outs.length][]);
        try {
            String[] f = v.trim().split("\\s+");
            int[][] runs = new int[outs.length][];
            for (int i = 1; i < f.length; i++) {
                int o = Math.floorMod(Integer.parseInt(f[i].substring(0, f[i].indexOf(':'))) + k.rot(), 4);
                for (int q = 0; q < outs.length; q++) if (outs[q] == o) runs[q] = parseAlign(f[i].substring(f[i].indexOf(':') + 1));
            }
            return new JointFix(Math.floorMod(Integer.parseInt(f[0]) + k.rot(), 4), runs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @return {shift, depth} from an override value "shift,depth", {shift, depth, 1} from "shift,depth,w" (a world shift
     * saved before shifts were stored relative to the joint box, {@link PieceSets#loadOverrides}), or null when damaged
     */
    public static int[] parseAlign(String v) {
        try {
            String[] f = v.split(",");
            int s = Integer.parseInt(f[0].trim()), dp = Integer.parseInt(f[1].trim());
            if (Math.abs(s) > 16 || dp < -1 || dp > 32) return null;
            if (f.length == 3 && f[2].trim().equals("w")) return new int[]{s, dp, 1};
            return f.length == 2 ? new int[]{s, dp} : null;
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

    /**
     * When every turn of an L corner has a run matching below this (its weakest run), no turn is trusted to touch well
     * and the corner's shape decides ({@link #pickTurn}). Off (0) until its picture review is done: at 0.4 it turns 11
     * flagged wall corners the right way but 2 visibly wrong, and changes ~250 other cases still unreviewed.
     */
    public static double contactFloor = 0;

    /**
     * @param contact per turn, the summed {@link #faceScore} (runs that touch); @param bestMatch per turn, the match its
     *                weakest run reaches (1 for joints the floor doesn't judge); @param shape per turn, arm minus closed ({@link #armClosed})
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

    /**
     * A road corner centred on its cell lands its arms a cell or two off a run's centre line whenever its box is even
     * or turned (the centring isn't symmetric), and a line's runs take one sideways shift for both ends: the second
     * corner then meets its run off-line (Ethan's colonial road corners, 1-2 cells off). Moving the corner instead,
     * so its runs sit on their lines: the move, per axis the opposite of the shift of the runs across it.
     *
     * @return {dx, dz} for the joint (0 on an axis whose runs disagree)
     */
    public static int[] jointMove(int[] outs, Fit[] fits) {
        Integer sx = null, sz = null;
        boolean bx = false, bz = false;
        for (int q = 0; q < outs.length; q++) {
            int s = fits[q].shift();
            if (outs[q] % 2 == 0) {
                bz |= sz != null && sz != s;
                sz = s;
            } else {
                bx |= sx != null && sx != s;
                sx = s;
            }
        }
        return new int[]{sx == null || bx ? 0 : -sx, sz == null || bz ? 0 : -sz};
    }

    /** @return a run's fit with everything moved by {@code mv} ({@link #jointMove}): its box at the same place against the joint */
    public static Fit moved(int[] c, int d, Fit f, int width, int run, int[] mv) {
        int[] b = boxes(c, d, f.off(), List.of(run), width, f.shift()).get(0);
        b = new int[]{b[0] + mv[0], b[1] + mv[1], b[2] + mv[0], b[3] + mv[1]};
        int lat = d % 2 == 0 ? 1 : 0;
        int off = switch (d) {
            case 0 -> b[0] - c[0];
            case 2 -> c[0] - b[2];
            case 1 -> b[1] - c[1];
            default -> c[1] - b[3];
        };
        return new Fit(off, b[lat] - c[lat] + (width - 1) / 2, f.match(), f.depth());
    }

    /**
     * @return true when a face toward a run looks less like the straight's end than one of the joint's closed faces
     * ({@link #faceLike}): the joint turns a closed side toward a run
     */
    public static boolean closedTowardRun(Grid joint, List<Integer> outs, Grid straight, boolean flip) {
        double arm = Double.MAX_VALUE, closed = 0;
        for (int f = 0; f < 4; f++) {
            double v = faceLike(joint, f, straight, flip);
            if (outs.contains(f)) arm = Math.min(arm, v);
            else closed = Math.max(closed, v);
        }
        return arm < closed - 1e-9;
    }

    /**
     * A road corner's turn: the contact pick ({@link #pickTurn}), unless it turns a closed side toward a run (its full
     * border row scores like the road's end: Ethan's colonial level-1 corners, one quarter turn off) and another turn
     * doesn't, while its weakest run meets the corner as well, its arms look clearly (0.2) more like the road's end and
     * its contact is near (3/4);
     * then of those, the arms most like the road's end, then the most contact.
     *
     * @param closed per turn, {@link #closedTowardRun}; @param weakest per turn, its weakest run's match
     */
    public static int pickRoadCorner(double[] contact, double[] bestMatch, double[] shape, boolean[] closed, double[] weakest) {
        int byContact = pickTurn(contact, bestMatch, shape);
        if (!closed[byContact]) return byContact;
        int best = -1;
        for (int k = 0; k < closed.length; k++)
            if (!closed[k] && weakest[k] >= weakest[byContact] - 1e-9 && shape[k] >= shape[byContact] + 0.2 && contact[k] >= 0.75 * contact[byContact]
                    && (best < 0 || shape[k] > shape[best] + 1e-9 || shape[k] > shape[best] - 1e-9 && contact[k] > contact[best] + 1e-9)) best = k;
        return best < 0 ? byContact : best;
    }

    /**
     * A road corner's run: of the shifts meeting the corner as well as the search's (same depth), one whose outer edge
     * (away from the other arm) is flush with the corner's box; else the search's.
     *
     * @param other the other arm's direction out of the joint
     */
    public static Fit outerFit(int[] c, int d, int other, int[] jb, Grid joint, Grid straight, int width, int run, int maxShift, Fit f) {
        if (f.depth() < 0) return f;
        boolean high = other == 2 || other == 3;
        int lat = d % 2 == 0 ? 1 : 0;
        Fit best = null;
        for (int s = -maxShift; s <= maxShift; s++) {
            if (destructiveAt(c, d, jb, joint, straight, width, run, s, f.depth())) continue;
            Fit g = fitAt(c, d, jb, joint, straight, width, run, s, f.depth());
            if (g.match() < f.match() - 1e-9) continue;
            int[] b = boxes(c, d, g.off(), List.of(run), width, s).get(0);
            if ((high ? b[lat + 2] == jb[lat + 2] : b[lat] == jb[lat]) && (best == null || Math.abs(s - f.shift()) < Math.abs(best.shift() - f.shift()))) best = g;
        }
        return best == null ? f : best;
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
