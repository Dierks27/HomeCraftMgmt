package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Mountain Run v2 made by hand for the proof's tests (MOUNTAIN-V2-SPEC §4, §7, §9): a serpentine
 * of square-cornered traverses down a 480 x 176 x 640 half, built block by block from a few
 * numbers, with every piece {@link MountainValidator} has a rule for, so a mutation can break one
 * rule at a time.
 *
 * <p>The route (half-local columns): the start pit and the summit traverse along +x at z
 * {@code bandZ[0]} from the pit's lime back wall at x {@link #pitBack}; then down a link at x
 * {@value #XE}, back along -x, down at x {@value #XW}, and so on, every traverse x {@value #XW} to
 * {@value #XE}; the last link drops at x {@value #XW} into the finish band along +x at z
 * {@value #FINISH_Z}, through a stone tunnel (roads), over the Final Drop (a 1, at x
 * {@value #FINAL_LIP}) to the finish at x 240.5 (24.5 from the stand's platform), 14 of run-out, a
 * sand paddock (x 255-260) and the end wall at x 261. The stand stands at {@link MountainValidator#standSpot},
 * the platform at x 237-243, z 597-603, the finish's ice + 5. Drops sit on the traverses in order
 * (each a checkpoint r before its edge and Z(d) + 3 past it, as §7.2 puts them), with checkpoints
 * round every square corner and at most {@link #fill} apart elsewhere. Walls are wood two above the
 * ice then glass, raised round every landing, with risers under the 2-block drops, yellow caps and
 * lanterns at the lips, light-blue checkpoint and gold finish markers. Scenery: a stone and snow
 * summit by the pit, a moss valley under the stand, a spruce and an oak with vanilla's leaf
 * distances, and (with {@link #skin}) a moss skin over every other column, a real-sized plan.
 *
 * <p>A slalom ({@link #slalom}) has a corridor W wide and a gate field on the summit traverse:
 * red and blue fences across the corridor every {@link #gateSpacing}, their openings g wide
 * against alternate walls, a lit pole one higher at each opening's edge, a checkpoint between each
 * pair.
 *
 * <p>Its fields are open so a test can build a variant ({@link #extra} drive areas, {@link #carve}d
 * columns, the drops, the pit), and the static helpers edit single blocks of a built plan.
 */
final class HandMountain {

    /** Mountain Run v2's half A at the shipped spot: 480 x 176 x 640 at (6080, 96, 2880). */
    static final Box HALF = Box.sized(6080, 96, 2880, 480, 176, 640);
    static final int NONE = Integer.MIN_VALUE;
    /** The traverses' ends (their centrelines' corner columns). */
    static final int XW = 24;
    static final int XE = 456;
    /** The finish band's centreline row, and its end. */
    static final int FINISH_Z = 572;
    static final int FINAL_LIP = 190;
    static final double FINISH_X = 240.5;
    static final int PADDOCK = 255;
    static final int END = 260;
    /** The finish's ice: ly 14 (§4.1). */
    static final int BOTTOM = HALF.minY() + 14;

    final String tier;
    final boolean slalom;
    /** The lane's half width: lanes are 2h + 1 wide. */
    final int h;
    /** The drops in order along the track; the last is the Final Drop. */
    final List<Integer> drops;
    /** How many full traverses before the finish band (even: the finish band runs +x). */
    int bands;
    /** The corners: square (0), or quarter rings of this centreline radius, as a planner draws them. */
    int radius;
    /** The pit's back wall (x), and the start's x. */
    int pitBack = 20;
    double startX = 51.5;
    /** Checkpoints at most this far apart along a stretch with no drop. */
    int fill = 40;
    /** A stone tunnel on the finish band (roads). */
    boolean tunnel = true;
    /** A moss skin over every column away from the track: a real-sized plan. */
    boolean skin = false;
    /** The slalom's gate field on the summit traverse: how many fences, how far apart, how wide the openings. */
    int gates = 0;
    int gateSpacing = 26;
    int opening = 6;
    int gateFrom = 58;
    long seed;
    /** More drive areas, {x0, z0, x1, z1, level} half-local, laid after the route (a bay, a pocket). */
    final List<int[]> extra = new ArrayList<>();
    /** Columns taken out of the track, {x0, z0, x1, z1}. */
    final List<int[]> carve = new ArrayList<>();

    // what the last build made
    int[][] level;
    final Map<Long, String> blocks = new LinkedHashMap<>();
    final List<SignText> signs = new ArrayList<>();
    final List<Course.Mark> checkpoints = new ArrayList<>();
    /** Each checkpoint's kind, in order: "start", "corner-after", "corner-before", "before", "after", "fill", "gate", "tunnel". */
    final List<String> kinds = new ArrayList<>();
    final List<Leg> legs = new ArrayList<>();
    /** Every lip: {leg, sigma, drop, x, z of its middle lip cell, upper ice}. */
    final List<int[]> lips = new ArrayList<>();
    /** Every gate fence: {x, z0, z1 of the fence, opening side (-1 north, 1 south), ice}. */
    final List<int[]> fences = new ArrayList<>();
    Course.Mark finish;
    Course.Spot start;
    int top;
    int[] bandZ;

    /** One straight of the route: from column (x0, z0) along (dx, dz) for {@code length}, its ice {@code base} at the start. */
    record Leg(int x0, int z0, int dx, int dz, int length, int extA, int extB, int base) {

        int x(int s) {
            return x0 + dx * s;
        }

        int z(int s) {
            return z0 + dz * s;
        }

        /** The middle of the lane at sigma {@code s} (block units along), as world-local doubles. */
        double cx(double s) {
            return x0 + 0.5 + dx * s;
        }

        double cz(double s) {
            return z0 + 0.5 + dz * s;
        }
    }

    private HandMountain(String tier, boolean slalom, int width, List<Integer> drops, int bands) {
        this.tier = tier;
        this.slalom = slalom;
        this.h = (width - 1) / 2;
        this.drops = new ArrayList<>(drops);
        this.bands = bands;
        this.seed = seedFor(slalom);
    }

    /** The first seed whose style is the one wanted, so {@link MountainValidator#problems(Plan, String)} judges it so. */
    static long seedFor(boolean slalom) {
        long s = 0x5EEDL;
        while (MountainValidator.slalom(s) != slalom) {
            s++;
        }
        return s;
    }

    /** A Winding Road of {@code tier}: easy 9 wide, 14 drops (4 of 2), 18 down; medium 7 wide, 21 (14 of 2), 35 down; hard 7 wide, 25 (20 of 2), 45 down. */
    static HandMountain road(String tier) {
        return switch (tier) {
            case "easy" -> new HandMountain(tier, false, 9, drops(13, 4, 1), 4);
            case "medium" -> new HandMountain(tier, false, 7, drops(20, 14, 1), 4);
            default -> new HandMountain(tier, false, 7, drops(24, 20, 1), 6);
        };
    }

    /** A Slalom of {@code tier} (1-block drops: a wide corridor's checkpoints can't span a 2-block flight in 60). */
    static HandMountain slalom(String tier) {
        HandMountain m = switch (tier) {
            case "easy" -> new HandMountain(tier, true, 15, drops(7, 0, 1), 4);
            case "medium" -> new HandMountain(tier, true, 13, drops(15, 0, 1), 4);
            default -> new HandMountain(tier, true, 11, drops(19, 0, 1), 6);
        };
        m.tunnel = false;
        m.radius = 24;
        m.gates = 6;
        m.opening = switch (tier) {
            case "easy" -> 8;
            case "medium" -> 6;
            default -> 5;
        };
        return m;
    }

    /** {@code n} drops before the Final Drop, the first {@code twos} of them 2 blocks, then the Final Drop. */
    static List<Integer> drops(int n, int twos, int last) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i < twos ? 2 : 1);
        }
        out.add(last);
        return out;
    }

    /** A checkpoint's radius: half the lane and a half. */
    double r() {
        return h + 1.0;
    }

    /** How far from a square corner its checkpoints stand: their spheres keep apart round it. */
    int corner() {
        return h + 6;
    }

    int descent() {
        int d = 0;
        for (int x : drops) {
            d += x;
        }
        return d;
    }

    static int wx(int x) {
        return HALF.minX() + x;
    }

    static int wz(int z) {
        return HALF.minZ() + z;
    }

    int level(int x, int z) {
        return x < 0 || z < 0 || x >= level.length || z >= level[0].length ? NONE : level[x][z];
    }

    // ---- the route ---------------------------------------------------------------------------------

    private void route() {
        legs.clear();
        bandZ = new int[bands + 1];
        int z0 = 40;
        for (int k = 0; k <= bands; k++) {
            bandZ[k] = k == bands ? FINISH_Z : z0 + (int) Math.round(k * (FINISH_Z - z0) / (double) bands);
        }
        top = BOTTOM + descent();
        // traverses and links
        int x = pitBack + 1;
        for (int k = 0; k < bands; k++) {
            boolean east = k % 2 == 0;
            int from = k == 0 ? pitBack + 1 : east ? XW : XE;
            int to = east ? XE : XW;
            int ext = radius > 0 ? -radius : h;
            legs.add(new Leg(from, bandZ[k], east ? 1 : -1, 0, Math.abs(to - from), k == 0 ? 0 : ext, ext, 0));
            legs.add(new Leg(to, bandZ[k], 0, 1, bandZ[k + 1] - bandZ[k], ext, ext, 0));
            x = to;
        }
        legs.add(new Leg(XW, FINISH_Z, 1, 0, END - XW, radius > 0 ? -radius : h, 0, 0));
    }

    /** Lips per leg: {sigma of the last high cell, drop}; the Final Drop on the finish band, the rest greedily on the traverses. */
    private List<List<int[]>> placeLips() {
        List<List<int[]>> out = new ArrayList<>();
        for (int j = 0; j < legs.size(); j++) {
            out.add(new ArrayList<>());
        }
        int r = (int) r();
        int a = corner() + radius;
        int next = 0;
        int last = drops.size() - 1;
        for (int j = 0; j < legs.size() - 1 && next < last; j += 2) {
            Leg leg = legs.get(j);
            int s = j == 0 ? (int) Math.ceil(startX - leg.x0()) + 60 : a + 3 * r + 2;
            if (j == 0 && gates > 0) {
                s = Math.max(s, gateFrom + gates * gateSpacing + r + 4);
            }
            while (next < last) {
                int d = drops.get(next);
                int after = s + BoatEnvelope.zone(d) + 3;
                if (after + 2 * r + 2 > leg.length() - a) {
                    break;
                }
                out.get(j).add(new int[]{s, d});
                next++;
                s = after + 3 * r + 2;
            }
        }
        if (next < last) {
            throw new IllegalStateException("the hand-made mountain has room for " + next + " of " + last + " drops");
        }
        out.get(legs.size() - 1).add(new int[]{FINAL_LIP - XW, drops.get(last)});
        return out;
    }

    // ---- the build ---------------------------------------------------------------------------------

    Plan plan() {
        blocks.clear();
        signs.clear();
        checkpoints.clear();
        kinds.clear();
        lips.clear();
        fences.clear();
        route();
        List<List<int[]>> lipsOf = placeLips();
        int sx = HALF.sizeX();
        int sz = HALF.sizeZ();
        level = new int[sx][sz];
        for (int[] row : level) {
            java.util.Arrays.fill(row, NONE);
        }
        // the lane, leg by leg, each column at its leg's level there
        int ice = top;
        List<Leg> placed = new ArrayList<>();
        for (int j = 0; j < legs.size(); j++) {
            Leg leg = legs.get(j);
            Leg withBase = new Leg(leg.x0(), leg.z0(), leg.dx(), leg.dz(), leg.length(), leg.extA(), leg.extB(), ice);
            placed.add(withBase);
            List<int[]> ls = lipsOf.get(j);
            for (int s = -leg.extA(); s <= leg.length() + leg.extB(); s++) {
                int lv = ice;
                for (int[] l : ls) {
                    if (s > l[0]) {
                        lv -= l[1];
                    }
                }
                for (int o = -h; o <= h; o++) {
                    int x = leg.x(s) + (leg.dz() != 0 ? o : 0);
                    int z = leg.z(s) + (leg.dx() != 0 ? o : 0);
                    level[x][z] = lv;
                }
            }
            for (int[] l : ls) {
                ice -= l[1];
                lips.add(new int[]{j, l[0], l[1], leg.x(l[0]), leg.z(l[0]), withBase.base() - dropsBefore(ls, l)});
            }
        }
        legs.clear();
        legs.addAll(placed);
        if (radius > 0) {
            for (int j = 0; j + 1 < legs.size(); j++) {
                arc(legs.get(j), legs.get(j + 1));
            }
        }
        if (ice != BOTTOM) {
            throw new IllegalStateException("the hand-made mountain ends at " + ice + ", not " + BOTTOM);
        }
        for (int[] e : extra) {
            for (int x = e[0]; x <= e[2]; x++) {
                for (int z = e[1]; z <= e[3]; z++) {
                    level[x][z] = e[4];
                }
            }
        }
        gateColumns();
        for (int[] c : carve) {
            for (int x = c[0]; x <= c[2]; x++) {
                for (int z = c[1]; z <= c[3]; z++) {
                    level[x][z] = NONE;
                }
            }
        }
        // the drive blocks: ice, the paddock's sand
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (level[x][z] == NONE) {
                    continue;
                }
                boolean paddock = x >= PADDOCK && x <= END && Math.abs(z - FINISH_Z) <= h;
                put(x, level[x][z], z, paddock ? Palette.SAND : iceAt(x, z));
            }
        }
        // risers under the high side of every 2-block edge
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (level[x][z] == NONE) {
                    continue;
                }
                for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int n = level(x + s[0], z + s[1]);
                    if (n != NONE && level[x][z] - n >= 2) {
                        for (int y = n + 1; y < level[x][z]; y++) {
                            put(x, y, z, Palette.TRACK_WALL);
                        }
                    }
                }
            }
        }
        walls();
        gateFences();
        if (tunnel && !slalom) {
            tunnel();
        }
        features();
        course();
        scenery();
        stand();
        if (skin) {
            skin();
        }
        return build();
    }

    private static int dropsBefore(List<int[]> ls, int[] l) {
        int d = 0;
        for (int[] o : ls) {
            if (o == l) {
                break;
            }
            d += o[1];
        }
        return d;
    }

    /**
     * The quarter ring of {@link #radius} round the corner from leg {@code in} to leg {@code out},
     * {@code h} + 0.5 either side of its centreline, at the level where {@code in} ends (as
     * {@code HandRun}'s rounded corners, a planner's arc on whole blocks).
     */
    private void arc(Leg in, Leg out) {
        int px = in.x(in.length());
        int pz = in.z(in.length());
        int lv = level[in.x(in.length() - radius)][in.z(in.length() - radius)];
        double ox = px + 0.5 - radius * in.dx() + radius * out.dx();
        double oz = pz + 0.5 - radius * in.dz() + radius * out.dz();
        for (int x = (int) Math.floor(ox) - radius - h - 2; x <= (int) Math.floor(ox) + radius + h + 2; x++) {
            for (int z = (int) Math.floor(oz) - radius - h - 2; z <= (int) Math.floor(oz) + radius + h + 2; z++) {
                double cx = x + 0.5 - ox;
                double cz = z + 0.5 - oz;
                if (cx * in.dx() + cz * in.dz() < 0 || cx * -out.dx() + cz * -out.dz() < 0) {
                    continue;
                }
                if (Math.abs(Math.hypot(cx, cz) - radius) <= h + 0.5) {
                    level[x][z] = lv;
                }
            }
        }
    }

    /** Hard's first traverse is blue (a blue straight); everything else packed. */
    private String iceAt(int x, int z) {
        boolean firstBand = Math.abs(z - bandZ[0]) <= h && x > pitBack;
        return tier.equals("hard") && firstBand ? Palette.TRACK_FAST : Palette.TRACK;
    }

    /** The slalom's fences: columns across the corridor, the opening against alternate walls, taken out of the track. */
    private void gateColumns() {
        if (gates == 0) {
            return;
        }
        Leg leg = legs.get(0);
        for (int i = 0; i < gates; i++) {
            int s = gateFrom + i * gateSpacing;
            int x = leg.x(s);
            int side = i % 2 == 0 ? -1 : 1; // the opening's side: -1 north (low z), 1 south
            int zc = leg.z0();
            int z0 = side < 0 ? zc - h + opening : zc - h;
            int z1 = side < 0 ? zc + h : zc + h - opening;
            for (int z = z0; z <= z1; z++) {
                level[x][z] = NONE;
            }
            fences.add(new int[]{x, z0, z1, side, top});
        }
    }

    /** Every column beside the track: wood from the lowest ice beside it to 2 over the highest, glass on top where raised. */
    private void walls() {
        int sx = HALF.sizeX();
        int sz = HALF.sizeZ();
        int[][] raise = new int[sx][sz];
        for (int[] row : raise) {
            java.util.Arrays.fill(row, NONE);
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int up = level[x][z];
                if (up == NONE) {
                    continue;
                }
                int d = 0;
                for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int n = level(x + s[0], z + s[1]);
                    if (n != NONE && n < up) {
                        d = Math.max(d, up - n);
                    }
                }
                if (d == 0) {
                    continue;
                }
                int reach = BoatEnvelope.zone(d) + 2;
                for (int ox = Math.max(0, x - reach); ox <= Math.min(sx - 1, x + reach); ox++) {
                    for (int oz = Math.max(0, z - reach); oz <= Math.min(sz - 1, z + reach); oz++) {
                        if (level[ox][oz] != NONE && level[ox][oz] < up
                                && (ox - x) * (ox - x) + (oz - z) * (oz - z) <= reach * reach) {
                            raise[ox][oz] = Math.max(raise[ox][oz], up);
                        }
                    }
                }
            }
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (level[x][z] != NONE) {
                    continue;
                }
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                int need = Integer.MIN_VALUE;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int n = level(x + dx, z + dz);
                        if (n == NONE) {
                            continue;
                        }
                        lo = Math.min(lo, n);
                        hi = Math.max(hi, n);
                        need = Math.max(need, n + 2);
                        int rr = raise[x + dx][z + dz];
                        if (rr != NONE) {
                            need = Math.max(need, rr + 2);
                        }
                    }
                }
                if (lo == Integer.MAX_VALUE) {
                    continue;
                }
                for (int y = lo; y <= need; y++) {
                    put(x, y, z, y <= hi + 1 ? (x == pitBack ? Palette.START : Palette.TRACK_WALL) : Palette.GLASS);
                }
            }
        }
    }

    /** The fences' blocks: red (opening north) or blue (south), ice to ice + 2; the pole at the opening one higher, lit. */
    private void gateFences() {
        for (int[] f : fences) {
            int x = f[0];
            int ice = f[4];
            String b = f[3] < 0 ? Palette.GATE_LEFT : Palette.GATE_RIGHT;
            int pole = f[3] < 0 ? f[1] : f[2];
            for (int z = f[1]; z <= f[2]; z++) {
                for (int y = ice; y <= ice + 2; y++) {
                    put(x, y, z, b);
                }
            }
            put(x, ice + 2, pole, Palette.SEA_LANTERN);
            put(x, ice + 3, pole, b);
        }
    }

    /** A stone tunnel on the finish band, x 70-89: stone walls to ice + 5, a stone roof 5 over the ice, rock and snow over it, lanterns. */
    private void tunnel() {
        int ice = BOTTOM + drops.get(drops.size() - 1);
        for (int x = 70; x <= 89; x++) {
            for (int z = FINISH_Z - h - 1; z <= FINISH_Z + h + 1; z++) {
                boolean wall = Math.abs(z - FINISH_Z) == h + 1;
                if (wall) {
                    for (int y = ice; y <= ice + 4; y++) {
                        put(x, y, z, x % 6 == 0 && y == ice + 1 ? Palette.SEA_LANTERN : Palette.STONE);
                    }
                }
                put(x, ice + 5, z, Palette.STONE);
                put(x, ice + 6, z, Palette.STONE);
                put(x, ice + 7, z, Palette.SNOW);
            }
        }
    }

    /** Lip caps and lights in both walls at every lip; arrows on the summit traverse. */
    private void features() {
        for (int[] l : lips) {
            Leg leg = legs.get(l[0]);
            int up = l[5];
            for (int side = -1; side <= 1; side += 2) {
                int o = side * (h + 1);
                int x = leg.x(l[1]) + (leg.dz() != 0 ? o : 0);
                int z = leg.z(l[1]) + (leg.dx() != 0 ? o : 0);
                if (blocks.containsKey(key(x, up + 1, z)) && blocks.containsKey(key(x, up + 2, z))) {
                    put(x, up + 1, z, Palette.LIP_CAP);
                    put(x, up + 2, z, Palette.SEA_LANTERN);
                }
            }
        }
        Leg first = legs.get(0);
        for (int side = -1; side <= 1; side += 2) {
            put(90, first.base() + 1, first.z0() + side * (h + 1), Palette.arrow("west"));
        }
    }

    // ---- the course --------------------------------------------------------------------------------

    /** An anchor of the checkpoint chain: sigma on a leg, its kind, and whether the stretch after it is the next anchor's alone (a drop, a tunnel, a gate). */
    private record Anchor(int leg, double s, String kind, boolean noFill) {
    }

    private void course() {
        double r = r();
        int a = corner() + radius;
        List<Anchor> anchors = new ArrayList<>();
        int finalLeg = legs.size() - 1;
        for (int j = 0; j < legs.size(); j++) {
            Leg leg = legs.get(j);
            List<Anchor> here = new ArrayList<>();
            if (j > 0) {
                here.add(new Anchor(j, a, "corner-after", false));
            }
            if (j == 0 && gates > 0) {
                for (int i = 0; i < gates; i++) {
                    double s0 = gateFrom + i * gateSpacing;
                    here.add(new Anchor(j, i == 0 ? s0 - r - 3 : s0 - gateSpacing / 2.0, i == 0 ? "fill" : "gate", true));
                }
                here.add(new Anchor(j, gateFrom + (gates - 1) * gateSpacing + r + 3, "fill", false));
            }
            if (j == finalLeg && tunnel && !slalom) {
                here.add(new Anchor(j, 70 - XW - r - 2, "tunnel", true));
                here.add(new Anchor(j, 89 - XW + r + 2, "tunnel", false));
            }
            for (int[] l : lips) {
                if (l[0] != j) {
                    continue;
                }
                here.add(new Anchor(j, l[1] - r, "before", true));
                if (l != lips.get(lips.size() - 1)) {
                    // the Final Drop's leg runs on to the finish
                    here.add(new Anchor(j, l[1] + BoatEnvelope.zone(l[2]) + 3, "after", false));
                }
            }
            if (j < finalLeg) {
                here.add(new Anchor(j, leg.length() - a, "corner-before", false));
            }
            here.sort((p, q) -> Double.compare(p.s(), q.s()));
            anchors.addAll(here);
        }
        // the start, then the chain with fills where a stretch is longer than {@link #fill}
        Leg first = legs.get(0);
        double startS = startX - (first.x0() + 0.5);
        start = new Course.Spot(HALF.minX() + startX, first.base() + 1, HALF.minZ() + first.z0() + 0.5, 270f, 0f);
        int prevLeg = 0;
        double prevS = startS;
        boolean prevNoFill = false;
        for (Anchor an : anchors) {
            if (an.leg() == prevLeg && !prevNoFill) {
                double gap = an.s() - prevS;
                int n = (int) Math.ceil(gap / fill) - 1;
                for (int i = 1; i <= n; i++) {
                    addMark(an.leg(), prevS + gap * i / (n + 1), "fill");
                }
            }
            addMark(an.leg(), an.s(), an.kind());
            prevLeg = an.leg();
            prevS = an.s();
            prevNoFill = an.noFill();
        }
        Leg fin = legs.get(finalLeg);
        finish = new Course.Mark(HALF.minX() + FINISH_X, BOTTOM + 1, HALF.minZ() + FINISH_Z + 0.5, h + 2.0);
        double gap = (FINISH_X - (fin.x0() + 0.5)) - prevS;
        if (prevLeg == finalLeg && !prevNoFill && gap > 60) {
            throw new IllegalStateException("the finish's leg is " + gap);
        }
        for (Course.Mark m : checkpoints) {
            markers(m, Palette.CHECKPOINT, 1);
        }
        markers(finish, Palette.FINISH, 2);
        signs.add(new SignText(wx(40), first.base() + 3, wz(first.z0() - h - 1), Palette.sign(4), GenCopy.boatRun()));
    }

    private void addMark(int j, double s, String kind) {
        Leg leg = legs.get(j);
        int x = (int) Math.floor(leg.cx(s));
        int z = (int) Math.floor(leg.cz(s));
        int ice = level[x][z];
        if (ice == NONE) {
            throw new IllegalStateException("a checkpoint off the track at " + x + " " + z);
        }
        checkpoints.add(new Course.Mark(HALF.minX() + leg.cx(s), ice + 1, HALF.minZ() + leg.cz(s), r()));
        kinds.add(kind);
    }

    /** The mark's colour in both walls beside it, from the ice + 1 up {@code high}. */
    private void markers(Course.Mark m, String block, int high) {
        int cx = (int) Math.floor(m.x()) - HALF.minX();
        int cz = (int) Math.floor(m.z()) - HALF.minZ();
        int ice = level[cx][cz];
        for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int x = cx;
            int z = cz;
            while (level(x, z) != NONE) {
                x += s[0];
                z += s[1];
            }
            if (Math.abs(x - cx) + Math.abs(z - cz) <= h + 2) {
                for (int y = ice + 1; y <= ice + high; y++) {
                    if (blocks.containsKey(key(x, y, z))) {
                        put(x, y, z, block);
                    }
                }
            }
        }
    }

    // ---- scenery and the stand -------------------------------------------------------------------

    /** The summit by the pit, the valley under the stand, two trees. */
    private void scenery() {
        // the summit: stone, snow on top, north of the summit traverse
        for (int x = 60; x <= 100; x++) {
            for (int z = 4; z <= 26; z++) {
                int peak = top + 30 - (Math.abs(x - 80) + Math.abs(z - 15)) / 2;
                put(x, peak - 1, z, Palette.STONE);
                put(x, peak, z, peak > top + 20 ? Palette.SNOW : Palette.STONE);
            }
        }
        // the valley floor round the stand, under the finish
        for (int x = 200; x <= 280; x++) {
            for (int z = 582; z <= 625; z++) {
                put(x, BOTTOM - 8, z, Palette.MOSS);
            }
        }
        // a spruce on the summit's flank and an oak in the valley, leaves at vanilla's distances
        tree(110, 14, top + 2, "spruce");
        tree(300, 600, BOTTOM - 7, "oak");
    }

    private void tree(int x, int z, int ground, String wood) {
        put(x, ground, z, Palette.MOSS);
        for (int y = ground + 1; y <= ground + 5; y++) {
            put(x, y, z, Palette.log(wood));
        }
        List<int[]> leaves = new ArrayList<>();
        for (int y = ground + 4; y <= ground + 6; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean trunk = dx == 0 && dz == 0 && y <= ground + 5;
                    boolean cornerTop = dx != 0 && dz != 0 && y == ground + 6;
                    if (!trunk && !cornerTop) {
                        leaves.add(new int[]{x + dx, y, z + dz});
                    }
                }
            }
        }
        for (int[] l : leaves) {
            put(l[0], l[1], l[2], "LEAF:" + wood);
        }
        placeLeaves();
    }

    /** Every leaf written with vanilla's own distance, worked out over the plan's own blocks. */
    private void placeLeaves() {
        List<int[]> logs = new ArrayList<>();
        List<int[]> leaves = new ArrayList<>();
        Map<Long, String> woodOf = new HashMap<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            int[] p = unkey(e.getKey());
            String b = e.getValue();
            if (b.startsWith("LEAF:") || Palette.isLeaves(b)) {
                leaves.add(p);
                woodOf.put(e.getKey(), b.startsWith("LEAF:") ? b.substring(5)
                        : Palette.id(b).replace("minecraft:", "").replace("_leaves", ""));
            } else if (Palette.holdsLeaves(b)) {
                logs.add(p);
            }
        }
        Map<Long, Integer> d = Palette.leafDistances(logs, leaves);
        for (int[] p : leaves) {
            long k = Palette.blockKey(p[0], p[1], p[2]);
            blocks.put(k, Palette.leaves(woodOf.get(k), d.get(k)));
        }
    }

    /** The viewing stand at the bottom: the 7 x 7 platform at {@link MountainValidator#standSpot}'s floor, its rail, its sign. */
    private void stand() {
        int floorY = RaceStand.floorY(BOTTOM + 1);
        int cx = MountainValidator.standX(HALF) - HALF.minX();
        int cz = MountainValidator.standZ(HALF) - HALF.minZ();
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                put(x, floorY, z, RaceStand.FLOOR);
                if (Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3) {
                    put(x, floorY + 1, z, RaceStand.RAIL_BLOCK);
                    put(x, floorY + 2, z, RaceStand.RAIL_BLOCK);
                }
            }
        }
        signs.add(new SignText(wx(cx), floorY + 1, wz(cz + 2), Palette.sign(8), RaceStand.SIGN));
    }

    /** Moss over every column that has nothing and is 2 or more from the track and the half's edge: a real-sized plan. */
    private void skin() {
        Set<Long> used = new HashSet<>();
        for (long k : blocks.keySet()) {
            int[] p = unkey(k);
            used.add(((long) p[0] << 32) ^ (p[2] & 0xFFFFFFFFL));
        }
        for (int x = 2; x < HALF.sizeX() - 2; x++) {
            for (int z = 2; z < HALF.sizeZ() - 2; z++) {
                if (used.contains(((long) wx(x) << 32) ^ (wz(z) & 0xFFFFFFFFL))) {
                    continue;
                }
                boolean near = false;
                for (int dx = -1; dx <= 1 && !near; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (level(x + dx, z + dz) != NONE) {
                            near = true;
                            break;
                        }
                    }
                }
                if (!near) {
                    put(x, HALF.minY() + 2 + Math.floorMod(x * 7 + z * 13, 3), z, Palette.MOSS);
                }
            }
        }
    }

    private Plan build() {
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        List<BlockOp> ops = new ArrayList<>(blocks.size());
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            int[] p = unkey(e.getKey());
            Integer i = index.get(e.getValue());
            if (i == null) {
                i = palette.size();
                palette.add(e.getValue());
                index.put(e.getValue(), i);
            }
            ops.add(new BlockOp(p[0], p[1], p[2], (short) (int) i));
        }
        Course draft = new Course(Slots.ICE_BOAT.id(), TrialKind.BOAT, "Ice Boat", Tier.of(tier), "", start,
                checkpoints, finish, (double) (BOTTOM - 3), null, true, false, 1);
        int min = MountainValidator.minSeconds(draft);
        Course course = draft.withMinSeconds(min);
        long refMs = Math.max(120_000, min * 1000L + 1000);
        List<Box> keep = new ArrayList<>();
        for (Leg leg : legs) {
            int x0 = Math.min(leg.x(0), leg.x(leg.length())) - (leg.dz() != 0 ? h : 0);
            int x1 = Math.max(leg.x(0), leg.x(leg.length())) + (leg.dz() != 0 ? h : 0);
            int z0 = Math.min(leg.z(0), leg.z(leg.length())) - (leg.dx() != 0 ? h : 0);
            int z1 = Math.max(leg.z(0), leg.z(leg.length())) + (leg.dx() != 0 ? h : 0);
            keep.add(new Box(wx(x0), BOTTOM + 1, wz(z0), wx(x1), Math.min(HALF.maxY(), leg.base() + 4), wz(z1)));
        }
        return Plan.of(Slots.ICE_BOAT.id(), MountainValidator.FIRST_ALGO, seed, HALF, palette, ops, signs, keep,
                new PlannedTrial(course, refMs), List.of("a hand-made Mountain Run v2 (" + tier + " "
                        + (slalom ? "slalom" : "road") + ")"), 1);
    }

    // ---- blocks ------------------------------------------------------------------------------------

    /** Put {@code block} at half-local column (x, z), world height y. */
    void put(int x, int y, int z, String block) {
        blocks.put(key(x, y, z), block);
    }

    static long key(int x, int y, int z) {
        return Palette.blockKey(wx(x), y, wz(z));
    }

    static int[] unkey(long k) {
        return HandRun.unkey(k);
    }
}
