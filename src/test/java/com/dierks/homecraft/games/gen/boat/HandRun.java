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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A Mountain Run made by hand for the proof's tests (Course Variety §2.10, §9): a rectangular
 * spiral round the viewing stand of the Ice Boat's half A, built block by block from a few numbers,
 * with every piece the validator has a rule for, so a mutation can break one rule at a time.
 *
 * <p>The route (half-relative columns, the stand's platform at 61-67): the launch pit and first leg
 * along +x at z 10 (top level, ice at H0 + 8); a leg down +z at x 118 with the first drop at z 32 and
 * a split round a tree island at z 85-100; back along -x at z 118 with a sand kerb, the second drop
 * at x 96 and a glass-roofed ice cave at x 18-32; up -z at x 10 with the third drop at z 96; the
 * inner ring along +x at z 34 with the Final Drop at x 49; and the last leg down +z at x 94, the
 * finish at z 64.5 (26.5 from the stand's edge), 14 of run-out, a sand paddock and the end wall.
 * Easy drops 1, 1, 1, 1 (a fall of 4); medium 1, 2, 1, 1 (5); hard 1, 2, 2, 1 (6). Lanes are 9, 7
 * and 5 wide (hard's pit 7). Checkpoints sit where §2.6 puts them: r + 0.5 before each drop's edge
 * and 3 past its flight zone. Walls are wood two above the ice then glass, raised round every
 * landing, with risers under the 2-block drops, yellow caps at the lips, light-blue checkpoint and
 * gold finish markers, a lime pit wall, arrows and cave lanterns. Two trees (on the island and on a
 * terrace), both with vanilla's own leaf distances.
 *
 * <p>Its fields are open so a test can build a variant ({@link #extra} drive areas, {@link #carve}d
 * obstacles, the pit's length, a tier's drops, corners rounded to arcs as a planner draws them) and
 * {@link #edit} changes single blocks of a built plan.
 */
final class HandRun {

    /** The Ice Boat's half A: 128 x 16 x 128 at (4480, 160, 4352). */
    static final Box HALF = Slots.ICE_BOAT.half('A');
    static final int H0 = HALF.minY();
    /** The top ice (the pit), H0 + 8: the start's surface is H0 + 9, the stand's floor H0 + 13. */
    static final int TOP = H0 + 8;
    static final int NONE = Integer.MIN_VALUE;

    final DownhillValidator.Tier tier;
    /** The lane's half width (lanes are 2h + 1 wide), and the pit's. */
    final int h;
    final int hp;
    /** The four drops, in order. */
    final int[] drops;
    /** Where the pit's back wall is (x), and the start (x of its centre). */
    int pitBack = 11;
    /** The corners' radius (0: square), all but the third drop's corner, which stays square. */
    int round = 0;
    double startX = 42.5;
    /** More drive areas, {x0, z0, x1, z1, level} half-relative, laid after the route (a bay, a pocket). */
    final List<int[]> extra = new ArrayList<>();
    /** Columns to take out of the track, {x0, z0, x1, z1}: an obstacle, the walls fill round it. */
    final List<int[]> carve = new ArrayList<>();

    // what the last build made
    int[][] level;
    final Map<Long, String> blocks = new LinkedHashMap<>();
    final List<SignText> signs = new ArrayList<>();
    final List<Course.Mark> checkpoints = new ArrayList<>();
    Course.Mark finish;
    Course.Spot start;
    int[] levels;

    private HandRun(DownhillValidator.Tier tier, int w, int pitWidth, int... drops) {
        this.tier = tier;
        this.h = (w - 1) / 2;
        this.hp = (pitWidth - 1) / 2;
        this.drops = drops;
    }

    static HandRun easy() {
        return new HandRun(DownhillValidator.Tier.EASY, 9, 9, 1, 1, 1, 1);
    }

    static HandRun medium() {
        return new HandRun(DownhillValidator.Tier.MEDIUM, 7, 7, 1, 2, 1, 1);
    }

    static HandRun hard() {
        return new HandRun(DownhillValidator.Tier.HARD, 5, 7, 1, 2, 2, 1);
    }

    static HandRun of(String tier) {
        return switch (tier) {
            case "easy" -> easy();
            case "medium" -> medium();
            default -> hard();
        };
    }

    /** A checkpoint's radius on a lane of this tier (w / 2 + 0.5), and on the pit's leg. */
    double r() {
        return h + 1.0;
    }

    double rPit() {
        return hp + 1.0;
    }

    /** The ice height after {@code n} drops. */
    int after(int n) {
        return levels[n];
    }

    /** The drive level (ice y) at half-relative column (x, z), or {@link #NONE}. */
    int level(int x, int z) {
        return x < 0 || z < 0 || x >= level.length || z >= level[0].length ? NONE : level[x][z];
    }

    static int wx(int x) {
        return HALF.minX() + x;
    }

    static int wz(int z) {
        return HALF.minZ() + z;
    }

    static double ax(double x) {
        return HALF.minX() + x;
    }

    static double az(double z) {
        return HALF.minZ() + z;
    }

    // ---- the build ------------------------------------------------------------------------------------

    Plan plan() {
        blocks.clear();
        signs.clear();
        checkpoints.clear();
        levels = new int[5];
        levels[0] = TOP;
        for (int i = 0; i < 4; i++) {
            levels[i + 1] = levels[i] - drops[i];
        }
        int sx = HALF.sizeX();
        int sz = HALF.sizeZ();
        level = new int[sx][sz];
        for (int[] row : level) {
            java.util.Arrays.fill(row, NONE);
        }
        int l0 = levels[0];
        int l1 = levels[1];
        int l2 = levels[2];
        int l3 = levels[3];
        int l4 = levels[4];
        // the route: six legs, their corners square or rounded (radius {@link #round})
        int[][] points = {{pitBack + 1, 10}, {118, 10}, {118, 118}, {10, 118}, {10, 34}, {94, 34}, {94, 83}};
        LevelAt[] legs = {
                (x, z) -> l0, // A: the pit and the first leg, +x at z 10
                (x, z) -> z <= 32 ? l0 : l1, // B: +z at x 118, the first drop after z 32
                (x, z) -> x >= 96 ? l1 : l2, // C: -x at z 118, the second drop before x 96
                (x, z) -> z >= 96 ? l2 : l3, // D: -z at x 10, the third drop before z 96
                (x, z) -> x <= 49 ? l3 : l4, // E: +x at z 34, the Final Drop after x 49
                (x, z) -> l4}; // F: +z at x 94 to the end wall at z 84
        int[] radius = {round, round, round, 0, round}; // D to E stays square: the third drop's checkpoint is near it
        for (int i = 0; i < legs.length; i++) {
            int half = i == 0 ? hp : h;
            int rIn = i == 0 ? 0 : radius[i - 1];
            int rOut = i == legs.length - 1 ? 0 : radius[i];
            leg(points[i], points[i + 1], half, i == 0 ? 0 : rIn == 0 ? -h : rIn, i == legs.length - 1 ? 0
                    : rOut == 0 ? -h : rOut, legs[i]);
            if (i < legs.length - 1 && rOut > 0) {
                arc(points[i], points[i + 1], points[i + 2], rOut, h, legs[i]);
            }
        }
        // the split: widen by the branches round a 3-wide island at z 85-100
        int branch = tier.narrowest();
        fill(117 - branch, 80, 119 + branch, 105, (x, z) -> l1);
        for (int[] e : extra) {
            fill(e[0], e[1], e[2], e[3], (x, z) -> e[4]);
        }
        for (int x = 117; x <= 119; x++) {
            for (int z = 85; z <= 100; z++) {
                level[x][z] = NONE; // the island
            }
        }
        for (int[] c : carve) {
            for (int x = c[0]; x <= c[2]; x++) {
                for (int z = c[1]; z <= c[3]; z++) {
                    level[x][z] = NONE;
                }
            }
        }

        // the drive blocks: ice, the kerb and the paddock sand
        String ice = tier == DownhillValidator.Tier.HARD ? Palette.TRACK_FAST : Palette.TRACK;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (level[x][z] == NONE) {
                    continue;
                }
                boolean kerb = z >= 118 + h - 1 && z <= 118 + h && x >= 107 && x <= 112;
                boolean paddock = z >= 78 && z <= 83 && x >= 94 - h && x <= 94 + h;
                put(x, level[x][z], z, kerb || paddock ? Palette.SAND : ice);
            }
        }
        // risers under the high side of every drop of 2 or more
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
        features();
        course();
        trees();
        stand();
        return build();
    }

    private interface LevelAt {
        int at(int x, int z);
    }

    private void fill(int x0, int z0, int x1, int z1, LevelAt at) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level[x][z] = at.at(x, z);
            }
        }
    }

    /**
     * The straight of a leg from {@code a} to {@code b} (one axis), {@code half} either side of its
     * centre line: {@code cutA} blocks short of {@code a} (negative: that far past it, a square
     * corner), and {@code cutB} short of {@code b} likewise.
     */
    private void leg(int[] a, int[] b, int half, int cutA, int cutB, LevelAt at) {
        int dx = Integer.signum(b[0] - a[0]);
        int dz = Integer.signum(b[1] - a[1]);
        int ax = a[0] + dx * cutA;
        int az = a[1] + dz * cutA;
        int bx = b[0] - dx * cutB;
        int bz = b[1] - dz * cutB;
        int x0 = Math.min(ax, bx) - (dx == 0 ? half : 0);
        int x1 = Math.max(ax, bx) + (dx == 0 ? half : 0);
        int z0 = Math.min(az, bz) - (dz == 0 ? half : 0);
        int z1 = Math.max(az, bz) + (dz == 0 ? half : 0);
        fill(x0, z0, x1, z1, at);
    }

    /**
     * A rounded corner at {@code p} from the leg coming from {@code a} to the leg going to {@code b}:
     * the quarter ring of radius {@code r} round the corner's centre, {@code half} + 0.5 either side
     * of it, at the incoming leg's level there.
     */
    private void arc(int[] a, int[] p, int[] b, int r, int half, LevelAt at) {
        double ux = Integer.signum(p[0] - a[0]);
        double uz = Integer.signum(p[1] - a[1]);
        double vx = Integer.signum(b[0] - p[0]);
        double vz = Integer.signum(b[1] - p[1]);
        double ox = p[0] + 0.5 - r * ux + r * vx;
        double oz = p[1] + 0.5 - r * uz + r * vz;
        int lv = at.at(p[0], p[1]);
        for (int x = (int) Math.floor(ox) - r - half - 2; x <= (int) Math.floor(ox) + r + half + 2; x++) {
            for (int z = (int) Math.floor(oz) - r - half - 2; z <= (int) Math.floor(oz) + r + half + 2; z++) {
                double cx = x + 0.5 - ox;
                double cz = z + 0.5 - oz;
                if (cx * ux + cz * uz < 0 || cx * -vx + cz * -vz < 0) {
                    continue; // outside the quarter the corner turns through
                }
                if (Math.abs(Math.hypot(cx, cz) - r) <= half + 0.5 && x >= 0 && z >= 0 && x < level.length
                        && z < level[0].length) {
                    level[x][z] = lv;
                }
            }
        }
    }

    /** Every column beside the track: wood from the lowest ice beside it to 2 over the highest, glass on top where raised. */
    private void walls() {
        int sx = HALF.sizeX();
        int sz = HALF.sizeZ();
        // how high each landing's walls must go: the lower track within Z(d) + 2 of a drop's edge
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
                        int r = raise[x + dx][z + dz];
                        if (r != NONE) {
                            need = Math.max(need, r + 2);
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

    /** Lip caps, the cave, arrows: the wall's colour language. */
    private void features() {
        int sx = HALF.sizeX();
        int sz = HALF.sizeZ();
        // yellow caps beside every lip, at the high surface
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int up = level[x][z];
                if (up == NONE) {
                    continue;
                }
                boolean lip = false;
                for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int n = level(x + s[0], z + s[1]);
                    lip |= n != NONE && n < up;
                }
                if (!lip) {
                    continue;
                }
                for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int ox = x + s[0];
                    int oz = z + s[1];
                    if (level(ox, oz) == NONE && blocks.containsKey(key(ox, up + 1, oz))) {
                        put(ox, up + 1, oz, Palette.LIP_CAP);
                    }
                }
            }
        }
        // the ice cave on leg C, x 18-32: a light-blue glass roof 5 over the ice, walls up to it, lanterns every 5
        int cave = levels[2];
        for (int x = 18; x <= 32; x++) {
            for (int z = 118 - h - 1; z <= 118 + h + 1; z++) {
                if (level[x][z] == NONE) {
                    for (int y = cave + 3; y <= cave + 4; y++) {
                        put(x, y, z, Palette.GLASS);
                    }
                    if (x % 5 == 0) {
                        put(x, cave + 1, z, Palette.SEA_LANTERN);
                    }
                }
                put(x, cave + 5, z, Palette.BLUE_GLASS);
            }
        }
        // arrows on leg A, both walls, pointing +x
        for (int z : new int[]{10 - hp - 1, 10 + hp + 1}) {
            put(90, TOP + 1, z, Palette.arrow("west"));
        }
    }

    /** The start, checkpoints (and their wall markers), the finish (gold posts), the start sign. */
    private void course() {
        int l0 = levels[0];
        start = new Course.Spot(ax(startX), l0 + 1, az(10.5), 270f, 0f);
        double r = r();
        int z1 = 32;
        checkpoints.add(mark(80.5, 10.5, levels[0], rPit()));
        checkpoints.add(mark(118.5, z1 - r + 0.5, levels[0], r));
        checkpoints.add(mark(118.5, z1 + BoatEnvelope.zone(drops[0]) + 3.5, levels[1], r));
        checkpoints.add(mark(96.5 + r, 118.5, levels[1], r));
        checkpoints.add(mark(96.5 - BoatEnvelope.zone(drops[1]) - 3, 118.5, levels[2], r));
        checkpoints.add(mark(10.5, 96.5 + r, levels[2], r));
        checkpoints.add(mark(10.5, 96.5 - BoatEnvelope.zone(drops[2]) - 3, levels[3], r));
        checkpoints.add(mark(49 - r + 0.5, 34.5, levels[3], r));
        finish = mark(94.5, 64.5, levels[4], h + 2.0);
        for (Course.Mark m : checkpoints) {
            markers(m, Palette.CHECKPOINT);
        }
        markers(finish, Palette.FINISH);
        int wallZ = 10 - hp - 1;
        signs.add(new SignText(wx(40), l0 + 3, wz(wallZ), Palette.sign(4), GenCopy.boatRun()));
    }

    static Course.Mark mark(double x, double z, int ice, double radius) {
        return new Course.Mark(ax(x), ice + 1, az(z), radius);
    }

    /** The mark's colour in both walls beside it, at the ice + 1. */
    private void markers(Course.Mark m, String block) {
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
            if (Math.abs(x - cx) + Math.abs(z - cz) <= h + 2 && blocks.containsKey(key(x, ice + 1, z))) {
                put(x, ice + 1, z, block);
            }
        }
    }

    /** A tree on the split's island (its canopy 5 over the branches) and one on the terrace between the rings. */
    private void trees() {
        int island = levels[1];
        for (int x = 117; x <= 119; x++) {
            for (int z = 85; z <= 100; z++) {
                boolean rim = x != 118 || z == 85 || z == 100;
                for (int y = island; y <= island + 2; y++) {
                    put(x, y, z, rim ? Palette.TRACK_WALL : Palette.MOSS);
                }
            }
        }
        List<int[]> leaves = new ArrayList<>();
        for (int y = island + 3; y <= island + 5; y++) {
            put(118, y, 92, Palette.log("oak"));
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && (Math.abs(dx) < 2 || dz == 0)) {
                    leaves.add(new int[]{118 + dx, island + 5, 92 + dz});
                }
            }
        }
        // the terrace tree, between the pit's leg and the inner ring
        int ground = levels[4];
        put(60, ground, 22, Palette.MOSS);
        for (int y = ground + 1; y <= ground + 5; y++) {
            put(60, y, 22, Palette.log("birch"));
        }
        for (int y = ground + 4; y <= ground + 6; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean trunk = dx == 0 && dz == 0 && y <= ground + 5;
                    boolean corner = dx != 0 && dz != 0 && y == ground + 6;
                    if (!trunk && !corner) {
                        leaves.add(new int[]{60 + dx, y, 22 + dz});
                    }
                }
            }
        }
        for (int[] l : leaves) {
            put(l[0], l[1], l[2], "LEAF");
        }
        placeLeaves("oak");
    }

    /** Every leaf written with vanilla's own distance, worked out over the plan's own blocks. */
    void placeLeaves(String wood) {
        List<int[]> logs = new ArrayList<>();
        List<int[]> leaves = new ArrayList<>();
        Map<Long, String> woodOf = new HashMap<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            int[] p = unkey(e.getKey());
            if (e.getValue().equals("LEAF") || Palette.isLeaves(e.getValue())) {
                leaves.add(p);
                woodOf.put(e.getKey(), e.getValue().equals("LEAF") ? wood
                        : Palette.id(e.getValue()).replace("minecraft:", "").replace("_leaves", ""));
            } else if (Palette.holdsLeaves(e.getValue())) {
                logs.add(p);
            }
        }
        Map<Long, Integer> d = Palette.leafDistances(logs, leaves);
        for (int[] p : leaves) {
            long k = Palette.blockKey(p[0], p[1], p[2]);
            blocks.put(key(p[0] - HALF.minX(), p[1], p[2] - HALF.minZ()),
                    Palette.leaves(woodOf.get(k), d.get(k)));
        }
    }

    /** The viewing stand: the 7 x 7 platform at the half's middle, its two-high rail, its sign. */
    private void stand() {
        int floorY = RaceStand.floorY(start.y());
        int cx = RaceStand.centreX(HALF) - HALF.minX();
        int cz = RaceStand.centreZ(HALF) - HALF.minZ();
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                put(x, floorY, z, RaceStand.FLOOR);
                if (Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3) {
                    put(x, floorY + 1, z, RaceStand.RAIL_BLOCK);
                    put(x, floorY + 2, z, RaceStand.RAIL_BLOCK);
                }
            }
        }
        signs.add(new SignText(wx(cx), floorY + 1, wz(cz - 2), Palette.sign(0), RaceStand.SIGN));
    }

    private Plan build() {
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = new ArrayList<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            int[] p = unkey(e.getKey());
            int i = palette.indexOf(e.getValue());
            if (i < 0) {
                palette.add(e.getValue());
                i = palette.size() - 1;
            }
            ops.add(new BlockOp(p[0], p[1], p[2], (short) i));
        }
        int bottom = levels[4];
        Course draft = new Course(Slots.ICE_BOAT.id(), TrialKind.BOAT, "Ice Boat", Tier.of(tier.id()), "", start,
                checkpoints, finish, (double) (bottom - 3), null, true, false, 1);
        int min = DownhillValidator.minSeconds(draft);
        Course course = draft.withMinSeconds(min);
        long refMs = Math.max(19_200, min * 1000L + 1000);
        List<Box> keep = new ArrayList<>();
        keep.add(new Box(wx(pitBack + 1), TOP + 1, wz(10 - hp), wx(118 + h), TOP + 4, wz(10 + hp)));
        keep.add(new Box(wx(118 - h), levels[1] + 1, wz(33), wx(118 + h), levels[0] + 3, wz(118 + h)));
        keep.add(new Box(wx(10 - h), levels[2] + 1, wz(118 - h), wx(95), levels[1] + 3, wz(118 + h)));
        keep.add(new Box(wx(10 - h), levels[3] + 1, wz(34 - h), wx(10 + h), levels[2] + 3, wz(95)));
        keep.add(new Box(wx(50), levels[4] + 1, wz(34 - h), wx(94 + h), levels[3] + 3, wz(83)));
        return Plan.of(Slots.ICE_BOAT.id(), 3, 0x5EEDL, HALF, palette, ops, signs, keep,
                new PlannedTrial(course, refMs), List.of("a hand-made Mountain Run (" + tier.id() + ")"), 1);
    }

    // ---- blocks -----------------------------------------------------------------------------------------

    /** Put {@code block} at half-relative column (x, z), world height y. */
    void put(int x, int y, int z, String block) {
        blocks.put(key(x, y, z), block);
    }

    static long key(int x, int y, int z) {
        return Palette.blockKey(wx(x), y, wz(z));
    }

    static int[] unkey(long k) {
        int x = (int) (k >> 38);
        int z = (int) ((k >> 12) & 0x3FFFFFF);
        int y = (int) (k & 0xFFF);
        return new int[]{x, y, z};
    }

    // ---- editing a built plan ------------------------------------------------------------------------------

    /**
     * {@code p} with the blocks {@code remove} matches taken out and {@code add} (world x, y, z to
     * block text) put in (replacing what was there), its hash made again.
     */
    static Plan edit(Plan p, Predicate<BlockOp> remove, Map<int[], String> add) {
        Map<Long, String> all = new LinkedHashMap<>();
        for (BlockOp op : p.ops()) {
            if (!remove.test(op)) {
                all.put(Palette.blockKey(op.x(), op.y(), op.z()), p.blockOf(op));
            }
        }
        for (Map.Entry<int[], String> e : add.entrySet()) {
            int[] c = e.getKey();
            all.put(Palette.blockKey(c[0], c[1], c[2]), e.getValue());
        }
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = new ArrayList<>();
        for (Map.Entry<Long, String> e : all.entrySet()) {
            int[] c = unkey(e.getKey());
            int i = palette.indexOf(e.getValue());
            if (i < 0) {
                palette.add(e.getValue());
                i = palette.size() - 1;
            }
            ops.add(new BlockOp(c[0], c[1], c[2], (short) i));
        }
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), palette, ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    /** {@code p} with the block at world (x, y, z) taken out. */
    static Plan without(Plan p, int x, int y, int z) {
        return edit(p, op -> op.x() == x && op.y() == y && op.z() == z, Map.of());
    }

    /** {@code p} with {@code block} at world (x, y, z). */
    static Plan with(Plan p, int x, int y, int z, String block) {
        Map<int[], String> add = new HashMap<>();
        add.put(new int[]{x, y, z}, block);
        return edit(p, op -> false, add);
    }

    /** {@code p} with its course (and reference time) replaced. */
    static Plan withCourse(Plan p, Course c, long refMs) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(),
                new PlannedTrial(c, refMs), p.summary(), p.work());
    }

    static Course course(Plan p) {
        return ((PlannedTrial) p.course()).course();
    }

    static long refMs(Plan p) {
        return ((PlannedTrial) p.course()).refMs();
    }

    /** {@code p}'s course with checkpoint {@code i} (0-based) replaced, its shortest time made to fit again. */
    static Plan withCheckpoint(Plan p, int i, Course.Mark m) {
        Course c = course(p);
        List<Course.Mark> cps = new ArrayList<>(c.checkpoints());
        cps.set(i, m);
        Course moved = c.withCheckpoints(cps);
        moved = moved.withMinSeconds(DownhillValidator.minSeconds(moved));
        return withCourse(p, moved, Math.max(refMs(p), moved.minSeconds() * 1000L + 1000));
    }

    /** {@code p} with its signs replaced. */
    static Plan withSigns(Plan p, List<SignText> signs) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), signs, p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    /** {@code p} with its keep-clear boxes replaced. */
    static Plan withBoxes(Plan p, List<Box> boxes) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), p.signs(), boxes, p.course(),
                p.summary(), p.work());
    }
}
