package com.dierks.homecraft.games.gen.boat;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wide road's mountain (MOUNTAIN-V21-SPEC D6, D11, boat planner algo 5): the trees keep their reserve of
 * the budget, so a wide road's mountain still grows its trees, and the road cut uphill of a road rises at most
 * 2 over it. Algo 4 keeps 0.37.0's numbers (its frozen fixtures, V4FixturesTest).
 */
class MountainSceneryTest {

    @Test
    void theTreesAreNeverStarved() {
        assertEquals(290_000, MountainScenery.BUDGET, "the wide road's budget (D11)");
        assertEquals(20_500, MountainScenery.TREE_RESERVE, "320 trees x 62.8 blocks + 400, kept off the skin's share");
        assertEquals(6, MountainScenery.FAR_STEP, "the far skin is cut 6 columns at a time");
        assertEquals(280_000, MountainScenery.BUDGET_V4, "algo 4's budget stays");
        assertEquals(12, MountainScenery.FAR_STEP_V4, "and its step");
        for (long seed : MountainPlannerTest.ROADS) {
            MountainPlanner.Made m = MountainPlannerTest.made(MountainPlannerTest.HALF_A, seed, "medium");
            String name = "road medium seed " + seed;
            assertTrue(m.tier.wide(), name + ": the wide road");
            assertTrue(m.trees >= 300, name + ": " + m.trees + " trees");
            assertTrue(m.plan.ops().size() <= MountainValidator.MAX_OPS - 8_000,
                    name + ": " + m.plan.ops().size() + " blocks, inside the room formula's 392,000");
        }
    }

    @Test
    void theRoadCutIsTwoHigh() {
        assertEquals(2, MountainScenery.BANK_HIGH, "the wide road's cut (D6)");
        assertEquals(5, MountainScenery.BANK_HIGH_V4, "algo 4's stays");
        MountainPlanner.Made m = MountainPlannerTest.made(MountainPlannerTest.HALF_A, 2, "medium");
        RasterV4 r = m.raster;
        // every column's distance to the nearest track column, 8 ways, as the mountain measures it
        int[] dist = new int[r.sx * r.sz];
        Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < r.sx; x++) {
            for (int z = 0; z < r.sz; z++) {
                if (r.drive(x, z)) {
                    dist[r.idx(x, z)] = 0;
                    queue.add(new int[]{x, z});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = c[0] + dx;
                    int nz = c[1] + dz;
                    if (r.inside(nx, nz) && dist[r.idx(nx, nz)] == Integer.MAX_VALUE) {
                        dist[r.idx(nx, nz)] = dist[r.idx(c[0], c[1])] + 1;
                        queue.add(new int[]{nx, nz});
                    }
                }
            }
        }
        // where the summit stands (behind the pit), and how far its cone could reach down to a road
        double[] pit = r.sk.line.at(0);
        double[] dir = r.sk.line.tangent(0);
        double cx = pit[0] - dir[0] * 26;
        double cz = Math.max(18, pit[1] - 18);
        int samples = 0;
        for (int x = 24; x < r.sx - 24; x++) {
            for (int z = 8; z < r.sz - 2; z++) {
                int d = dist[r.idx(x, z)];
                if (d < 2 || d > 3 || r.nearStand(x, z)) {
                    continue;
                }
                // a lone road below it: every track column within 14 lies downhill (south) of it, at one height
                int ice = RasterV4.NONE;
                boolean lone = true;
                for (int dx = -14; dx <= 14 && lone; dx++) {
                    for (int dz = -14; dz <= 14 && lone; dz++) {
                        if (!r.drive(x + dx, z + dz)) {
                            continue;
                        }
                        int h = r.h[r.idx(x + dx, z + dz)];
                        lone = dz > 0 && (ice == RasterV4.NONE || ice == h);
                        ice = h;
                    }
                }
                double cone = 16 + (r.topIce + MountainScenery.SUMMIT_HIGH - ice) / 0.75;
                if (!lone || Math.hypot(x - cx, z - cz) < cone) {
                    continue;
                }
                int top = Integer.MIN_VALUE;
                for (int[] b : r.vox.column(x, z)) {
                    String id = r.vox.palette.get(b[1]);
                    if (!id.contains("_leaves") && !id.contains("_log")) {
                        top = Math.max(top, b[0]);
                    }
                }
                long rise = Math.round(Math.min(MountainScenery.BANK_HIGH,
                        (d - 1) * MountainScenery.BANK_HIGH / (double) MountainScenery.BANK_COLUMNS));
                assertEquals(ice + rise, top, "the ground " + d + " uphill of the road at " + x + " " + z
                        + " stands " + rise + " over its ice");
                samples++;
            }
        }
        assertTrue(samples >= 50, "fixture: ground beside lone roads was read: " + samples);
    }
}
