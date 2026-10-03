package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A Mountain Run v2-sized plan made without the planner (which another package builds), shaped like
 * the spec's medium road (MOUNTAIN-V2-SPEC §3.6): a 480 x 176 x 640 half, a terrain skin over ~85% of
 * its columns that falls ~120 blocks from the summit in the north to the valley in the south (with
 * steps and banks where the ground drops by more than one), a 7-wide ice road ~3,400 blocks long that
 * winds back and forth down it between walls three high, a rocky summit and ridges, ~340 trees and a
 * few signs. About 350,000 ops, emitted in (x, z, y) column order as the v4 planner emits them, never
 * two at one spot. For PlanCodec v2's size and the lean BuildJob index's memory and build tests.
 */
public final class SyntheticMountain {

    /** The half: Mountain Run v2's half A. */
    public static final Box HALF = Box.sized(6080, 96, 2880, 480, 176, 640);

    /** Blocks from {@code Palette.ALLOWED} with the states its rules want, so the engine's checks pass it. */
    static final List<String> PALETTE = List.of(
            "minecraft:moss_block",
            "minecraft:green_concrete",
            "minecraft:soul_soil",
            "minecraft:smooth_sandstone",
            "minecraft:packed_ice",
            "minecraft:blue_ice",
            "minecraft:quartz_pillar",
            "minecraft:smooth_sandstone_slab[type=bottom,waterlogged=false]",
            "minecraft:oak_log[axis=y]",
            "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]",
            "minecraft:birch_log[axis=y]",
            "minecraft:birch_leaves[distance=1,persistent=true,waterlogged=false]",
            "minecraft:cherry_leaves[distance=1,persistent=true,waterlogged=false]",
            "minecraft:red_concrete",
            "minecraft:white_concrete",
            "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]");
    private static final short GRASS = 0;
    private static final short MOSS = 1;
    private static final short COARSE = 2;
    private static final short DIRT = 3;
    private static final short ICE = 4;
    private static final short BLUE_ICE = 5;
    private static final short ROCK = 6;
    private static final short TUFF = 7;
    private static final short WALL_RED = 13;
    private static final short WALL_WHITE = 14;

    private SyntheticMountain() {
    }

    /** The plan for {@code seed}: the same seed, the same plan. */
    public static Plan plan(long seed) {
        return plan(seed, 0);
    }

    /**
     * The plan for {@code seed} with exactly {@code exactly} blocks (0: as it comes, ~350,000), the extra
     * ones fill deep under the skin, column by column: {@code PlanCheck}'s Mountain Run v2 cap is 400,000.
     */
    public static Plan plan(long seed, int exactly) {
        int w = HALF.maxX() - HALF.minX() + 1;
        int d = HALF.maxZ() - HALF.minZ() + 1;
        int[][] h = new int[w][d];
        boolean[][] in = new boolean[w][d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                double fall = 120.0 * (1.0 - (double) z / d); // summit north, valley south
                double ridge = 14.0 * Math.max(0, 1.0 - Math.abs(x - w / 2.0) / 90.0) * (z < d / 3 ? 1 : 0.3);
                double n = 18 * noise(seed, x, z, 96) + 8 * noise(seed + 1, x, z, 40) + 3 * noise(seed + 2, x, z, 14)
                        + 2.5 * noise(seed + 3, x, z, 5) + 1.5 * noise(seed + 7, x, z, 2); // rough: steps everywhere
                h[x][z] = clamp((int) Math.round(104 + fall + ridge + n), HALF.minY() + 2, HALF.maxY() - 12);
                double edge = Math.min(Math.min(x, w - 1 - x), Math.min(z, d - 1 - z));
                in[x][z] = edge > 22 + 26 * (0.5 + 0.5 * noise(seed + 4, x, z, 60)); // ~85% of the columns
            }
        }
        Map<Long, Short> blocks = new HashMap<>(500_000);
        // The road: legs across, joined at the ends, falling gently, 7 wide, walls 3 high both sides.
        boolean[][] road = new boolean[w][d];
        int legs = 8;
        int z0 = 50;
        int pitch = (d - 110) / (legs - 1);
        for (int leg = 0; leg < legs; leg++) {
            int zc = z0 + leg * pitch;
            int from = 50;
            int to = w - 50;
            for (int x = from; x <= to; x++) {
                int bend = (int) Math.round(6 * Math.sin((x + leg * 37) / 23.0));
                stripe(road, x, zc + bend, true);
            }
            if (leg + 1 < legs) {
                int xe = leg % 2 == 0 ? to : from;
                for (int z = zc; z <= zc + pitch; z++) {
                    stripe(road, xe, z, false);
                }
            }
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!in[x][z] && !road[x][z]) {
                    continue;
                }
                int y = h[x][z];
                int lowest = y;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + n[0];
                    int nz = z + n[1];
                    if (nx >= 0 && nx < w && nz >= 0 && nz < d) {
                        lowest = Math.min(lowest, h[nx][nz] + 1);
                    }
                }
                for (int fy = Math.max(lowest, y - 6); fy < y; fy++) { // steps and banks
                    put(blocks, x, fy, z, DIRT);
                }
                if (road[x][z]) {
                    put(blocks, x, y, z, (x + z) % 9 == 0 ? BLUE_ICE : ICE);
                } else {
                    double kind = noise(seed + 5, x, z, 12) + 0.6 * noise(seed + 8, x, z, 2); // patchy
                    put(blocks, x, y, z, kind > 0.45 ? MOSS : kind < -0.5 ? COARSE : GRASS);
                    boolean wall = false;
                    for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + n[0];
                        int nz = z + n[1];
                        wall |= nx >= 0 && nx < w && nz >= 0 && nz < d && road[nx][nz];
                    }
                    if (wall) {
                        for (int k = 1; k <= 3; k++) {
                            put(blocks, x, y + k, z, (x / 4 + z / 4) % 2 == 0 ? WALL_RED : WALL_WHITE);
                        }
                    }
                }
            }
        }
        // The summit and the ridges: rock piled on the high ground.
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d / 3; z++) {
                if (!in[x][z] || road[x][z]) {
                    continue;
                }
                double r = noise(seed + 6, x, z, 18);
                int pile = (int) Math.round(5 * r);
                for (int k = 1; k <= pile; k++) {
                    put(blocks, x, h[x][z] + k, z, k == pile ? TUFF : ROCK);
                }
            }
        }
        // Trees: a trunk of five and a blob of leaves, off the road.
        SplittableRandom rnd = new SplittableRandom(seed);
        int trees = 0;
        while (trees < 340) {
            int x = 3 + rnd.nextInt(w - 6);
            int z = 3 + rnd.nextInt(d - 6);
            if (!in[x][z] || road[x][z] || near(road, x, z, 4)) {
                continue;
            }
            trees++;
            boolean birch = rnd.nextInt(3) == 0;
            short log = (short) (birch ? 10 : 8);
            short leaves = (short) (birch ? 11 : rnd.nextInt(5) == 0 ? 12 : 9);
            int y = h[x][z];
            for (int k = 1; k <= 5; k++) {
                put(blocks, x, y + k, z, log);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 4; dy <= 7; dy++) {
                        if ((dx != 0 || dz != 0 || dy > 5) && (dy < 7 || dx == 0 || dz == 0)) {
                            put(blocks, x + dx, y + dy, z + dz, leaves);
                        }
                    }
                }
            }
        }
        List<SignText> signs = new ArrayList<>();
        for (int i = 0; i < 4; i++) { // on posts by the start of each of the first four legs, where no block is
            int x = 60 + i * 90;
            int z = 30;
            int y = h[x][z] + 2;
            blocks.remove(((long) x << 40) | ((long) (y - HALF.minY()) << 20) | z);
            signs.add(new SignText(HALF.minX() + x, y, HALF.minZ() + z, Palette.sign(8),
                    List.of("Mountain Run", "Leg " + (i + 1))));
        }
        for (int k = 7; exactly > 0 && blocks.size() < exactly && k < 60; k++) { // below every step and bank
            for (int x = 0; x < w && blocks.size() < exactly; x++) {
                for (int z = 0; z < d && blocks.size() < exactly; z++) {
                    if (in[x][z] && h[x][z] - k >= HALF.minY()) {
                        put(blocks, x, h[x][z] - k, z, DIRT);
                    }
                }
            }
        }
        List<long[]> sorted = new ArrayList<>(blocks.size());
        for (Map.Entry<Long, Short> e : blocks.entrySet()) {
            long k = e.getKey();
            sorted.add(new long[]{k >> 40, (k >> 20) & 0xFFFFF, k & 0xFFFFF, e.getValue()});
        }
        sorted.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0])
                : a[2] != b[2] ? Long.compare(a[2], b[2]) : Long.compare(a[1], b[1])); // x, then z, then y
        List<BlockOp> ops = new ArrayList<>(sorted.size());
        for (long[] o : sorted) {
            ops.add(new BlockOp(HALF.minX() + (int) o[0], HALF.minY() + (int) o[1], HALF.minZ() + (int) o[2],
                    (short) o[3]));
        }
        List<Course.Mark> marks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            marks.add(new Course.Mark(HALF.minX() + 60.5 + i * 6, HALF.minY() + 80.0 - i, HALF.minZ() + 50.5 + i * 9,
                    4.0));
        }
        Course c = new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "",
                new Course.Spot(HALF.minX() + 50.5, HALF.minY() + 120.0, HALF.minZ() + 50.5, 90.0f, 0.0f), marks,
                new Course.Mark(HALF.minX() + 430.5, HALF.minY() + 20.0, HALF.minZ() + 590.5, 4.0),
                HALF.minY() + 0.0, null, false, false, 1);
        return Plan.of("fresh_boat", 4, seed, HALF, PALETTE, ops, signs, List.of(), new PlannedTrial(c, 120_000L),
                List.of("synthetic mountain"), 1);
    }

    private static void stripe(boolean[][] road, int x, int z, boolean across) {
        for (int k = -3; k <= 3; k++) {
            int rx = across ? x : x + k;
            int rz = across ? z + k : z;
            if (rx >= 0 && rx < road.length && rz >= 0 && rz < road[0].length) {
                road[rx][rz] = true;
            }
        }
    }

    private static boolean near(boolean[][] road, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nx < road.length && nz >= 0 && nz < road[0].length && road[nx][nz]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void put(Map<Long, Short> blocks, int x, int y, int z, short state) {
        int w = HALF.maxX() - HALF.minX() + 1;
        int d = HALF.maxZ() - HALF.minZ() + 1;
        if (x < 0 || x >= w || z < 0 || z >= d || y < HALF.minY() || y > HALF.maxY()) {
            return;
        }
        blocks.put(((long) x << 40) | ((long) (y - HALF.minY()) << 20) | z, state);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Smooth value noise in -1..1 at lattice spacing {@code scale}. */
    private static double noise(long seed, int x, int z, int scale) {
        int gx = Math.floorDiv(x, scale);
        int gz = Math.floorDiv(z, scale);
        double fx = smooth((x - gx * (double) scale) / scale);
        double fz = smooth((z - gz * (double) scale) / scale);
        double a = lattice(seed, gx, gz);
        double b = lattice(seed, gx + 1, gz);
        double c = lattice(seed, gx, gz + 1);
        double e = lattice(seed, gx + 1, gz + 1);
        return (a + (b - a) * fx) + ((c + (e - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lattice(long seed, int gx, int gz) {
        long v = seed * 0x9E3779B97F4A7C15L + gx * 0xC2B2AE3D27D4EB4FL + gz * 0x165667B19E3779F9L;
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        return ((v >>> 11) * 0x1.0p-53) * 2 - 1;
    }
}
