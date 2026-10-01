package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.engine.GenKit.FakeWorld;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.golf.HoleLayout;
import com.dierks.homecraft.games.gen.golf.HoleTemplate;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last word on a built course (GEN-SPEC §3.3 step 6, §3.5): each golf hole's witness line must
 * hole out in exactly its own number of putts on the blocks as they are; and a layout from an older
 * planner gets the quick check of a solid block under every place a player or ball must stand (every
 * boat checkpoint too, Course Variety §2.12), and on golf every pond in a hole's plot still sealed
 * (§1.3) — the whole plot, an Easy hole's pond to look at included, sealed by the plan's own test (a
 * full block, never a slab, a sign or leaves).
 */
class LiveProofTest {

    private static final int FLOOR = 63;

    /** A walled straight lane along +z with a sunken cup 5 ahead of the tee. */
    private static FakeWorld lane() {
        FakeWorld w = new FakeWorld("games");
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 14; z++) {
                boolean wall = Math.abs(x) == 3 || z == -2 || z == 14;
                w.put(x, FLOOR, z, "minecraft:lime_concrete");
                if (wall) {
                    w.put(x, FLOOR + 1, z, "minecraft:stripped_spruce_wood");
                }
            }
        }
        w.blocks.remove(GenKit.pos(0, FLOOR, 5));
        w.put(0, FLOOR - 1, 5, "minecraft:black_concrete");
        return w;
    }

    private static GolfCourse.Hole hole() {
        return new GolfCourse.Hole(new GolfCourse.Tee(0.5, FLOOR + 1, 0.5, 0f), new GolfCourse.Spot(0, FLOOR - 1, 5), 2,
                new GolfCourse.Spot(-3, FLOOR - 3, -2), new GolfCourse.Spot(3, FLOOR + 4, 14));
    }

    /** A one-putt line down the lane, found the way the planner would (or null). */
    private static List<Putt> onePutt(FakeWorld w) {
        for (int power = 1; power <= 5; power++) {
            for (float yaw = -4f; yaw <= 4f; yaw += 0.5f) {
                List<Putt> line = List.of(new Putt(yaw, power));
                GolfShot.Replay r = GolfShot.replay(w.ballBlocks(), hole(), line);
                if (r.holed() && r.strokes() == 1) {
                    return line;
                }
            }
        }
        return null;
    }

    @Test
    void aWitnessThatHolesOutPassesAndOneThatDoesNotFailsNamingTheHole() {
        FakeWorld w = lane();
        List<Putt> line = onePutt(w);
        assertNotNull(line, "a straight 5-block lane has a one-putt line");
        assertEquals(List.of(), LiveProof.replay(w.ballBlocks(), List.of(hole()), List.of(line)),
                "the witness replays on the blocks it was proven on");
        List<Putt> tooLong = List.of(line.get(0), new Putt(0f, 1));
        List<String> extra = LiveProof.replay(w.ballBlocks(), List.of(hole()), List.of(tooLong));
        assertFalse(extra.isEmpty(), "a line that holes out before its last putt isn't the proven line");

        w.blocks.remove(GenKit.pos(0, FLOOR - 1, 5)); // the cup floor is gone on the real blocks
        List<String> broken = LiveProof.replay(w.ballBlocks(), List.of(hole()), List.of(line));
        assertEquals(1, broken.size(), "one problem: " + broken);
        assertTrue(broken.get(0).startsWith("hole 1"), "naming the hole: " + broken);
        assertFalse(LiveProof.replay(w.ballBlocks(), List.of(hole(), hole()), List.of(line)).isEmpty(),
                "every hole needs its witness line");
    }

    @Test
    void theQuickCheckWantsASolidBlockUnderEveryStandingPlace() {
        FakeWorld w = new FakeWorld("games");
        w.put(10, 99, 10, "minecraft:lime_concrete");
        w.put(20, 99, 10, "minecraft:light_blue_concrete");
        w.put(30, 99, 10, "minecraft:gold_block");
        LiveProof.Solid solid = (x, y, z) -> w.at(x, y, z) != null;
        Course c = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY, "games",
                new Course.Spot(10.5, 100, 10.5, 0f, 0f), List.of(new Course.Mark(20.5, 100, 10.5, 2.2)),
                new Course.Mark(30.5, 100, 10.5, 3), null, null, true, false, 1);
        assertEquals(List.of(), LiveProof.structure(c, solid), "every pad is there");
        w.blocks.remove(GenKit.pos(20, 99, 10));
        assertEquals(List.of("nothing solid under checkpoint 1"), LiveProof.structure(c, solid), "a missing pad");
        Course rings = new Course("fresh_rings", TrialKind.ELYTRA, "Sky Rings", Tier.EASY, "games",
                new Course.Spot(10.5, 100, 10.5, 0f, 0f), List.of(new Course.Mark(20.5, 150, 10.5, 5)),
                new Course.Mark(30.5, 120, 10.5, 5), null, null, true, false, 1);
        assertEquals(List.of(), LiveProof.structure(rings, solid), "rings float: only the tower is checked");

        GolfCourse golf = new GolfCourse("fresh_tiny_golf", "Tiny Golf", "games", true, 1, List.of(new GolfCourse.Hole(
                new GolfCourse.Tee(10.5, 100, 10.5, 0f), new GolfCourse.Spot(30, 99, 10), 2,
                new GolfCourse.Spot(0, 90, 0), new GolfCourse.Spot(40, 110, 20))));
        assertEquals(List.of(), LiveProof.structure(golf, solid), "a tee on turf and a cup block");
        w.blocks.remove(GenKit.pos(30, 99, 10));
        assertEquals(List.of("hole 1's cup block is missing"), LiveProof.structure(golf, solid), "a missing cup");
    }

    @Test
    void aDropperWantsSolidUnderEveryLedgeAndWaterInEveryPool() {
        // EVENTS-DROPPER-SPEC §B.1.9, the C1 hook: two levels, easy pools (11 a side: radius 6)
        FakeWorld w = new FakeWorld("games");
        Course.Spot ledge1 = new Course.Spot(10.5, 216, 3.5, 0f, 30f);
        Course.Mark pool1 = new Course.Mark(10.5, 184 - 5.5, 8.5, 6);   // surface at y 184
        Course.Mark ledge2 = new Course.Mark(22.5, 216, 3.5, 2.5);
        Course.Mark pool2 = new Course.Mark(22.5, 176 - 5.5, 8.5, 6);   // surface at y 176
        Course drop = new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.EASY, "games", ledge1,
                List.of(pool1, ledge2), pool2, 160.0, null, true, false, 1);
        w.put(10, 215, 3, "minecraft:lime_concrete");
        w.put(22, 215, 3, "minecraft:lime_concrete");
        w.put(10, 183, 8, "minecraft:water[level=0]");
        w.put(22, 175, 8, "minecraft:water[level=0]");
        LiveProof.Solid solid = (x, y, z) -> w.at(x, y, z) != null;
        LiveProof.Solid water = (x, y, z) -> w.at(x, y, z) != null && w.at(x, y, z).startsWith("minecraft:water");
        assertEquals(List.of(), LiveProof.structure(drop, solid, water), "both ledges and both pools stand");
        w.blocks.remove(GenKit.pos(22, 175, 8));
        w.blocks.remove(GenKit.pos(22, 215, 3));
        assertEquals(List.of("nothing solid under level 2's ledge", "level 2's pool has no water at its centre"),
                LiveProof.structure(drop, solid, water), "a missing ledge and a drained pool, by level");
        assertEquals(List.of(), LiveProof.structure(drop, solid),
                "without a way to see water a dropper gets the start check only, as before");
        Course c = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY, "games",
                new Course.Spot(10.5, 216, 3.5, 0f, 0f), List.of(), new Course.Mark(10.5, 216, 3.5, 2), null, null,
                true, false, 1);
        assertEquals(LiveProof.structure(c, solid), LiveProof.structure(c, solid, water),
                "every other kind ignores the water check");
    }

    @Test
    void aBoatWantsSolidIceUnderItsStartEveryCheckpointAndItsFinish() {
        // Course Variety §2.12: a boat's marks sit on the ice (every v2 checkpoint does), so BOAT joins PARKOUR
        FakeWorld w = new FakeWorld("games");
        for (int x = 0; x <= 40; x++) {
            w.put(x, 99, 10, "minecraft:packed_ice");
        }
        LiveProof.Solid solid = (x, y, z) -> w.at(x, y, z) != null;
        Course boat = new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "games",
                new Course.Spot(2.5, 100, 10.5, 270f, 0f), List.of(new Course.Mark(12.5, 100, 10.5, 4),
                new Course.Mark(24.5, 100, 10.5, 4)), new Course.Mark(36.5, 100, 10.5, 4.5), 96.0, 1, true, false, 1);
        assertEquals(List.of(), LiveProof.structure(boat, solid), "the ice is under every mark");
        w.blocks.remove(GenKit.pos(24, 99, 10));
        assertEquals(List.of("nothing solid under checkpoint 2"), LiveProof.structure(boat, solid),
                "a boat mark with nothing under it fails");
        w.blocks.remove(GenKit.pos(36, 99, 10));
        assertEquals(List.of("nothing solid under checkpoint 2", "nothing solid under the finish"),
                LiveProof.structure(boat, solid), "and so does the finish");
        assertEquals(LiveProof.structure(boat, solid), LiveProof.structure(boat, solid, (x, y, z) -> false),
                "a boat has no water to look for");
    }

    /** A 3 x 3 pond at FLOOR - 1 in a turf plot (x 0-12, z 0-20), floored and walled. */
    private static FakeWorld pondPlot() {
        FakeWorld w = new FakeWorld("games");
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 20; z++) {
                w.put(x, FLOOR - 2, z, "minecraft:blue_concrete");
                boolean pond = x >= 5 && x <= 7 && z >= 8 && z <= 10;
                w.put(x, FLOOR - 1, z, pond ? "minecraft:water[level=0]" : "minecraft:lime_concrete");
            }
        }
        return w;
    }

    @Test
    void theGolfQuickCheckScansEveryHolesPlotForAnUnsealedPond() {
        FakeWorld w = pondPlot();
        LiveProof.Solid solid = (x, y, z) -> w.at(x, y, z) != null;
        LiveProof.Solid water = (x, y, z) -> w.at(x, y, z) != null && w.at(x, y, z).startsWith("minecraft:water");
        LiveProof.Solid seals = seals(w);
        w.put(3, FLOOR - 2, 3, "minecraft:black_concrete");
        GolfCourse golf = new GolfCourse("fresh_golf", "Golf of the Week", "games", true, 1, List.of(new GolfCourse.Hole(
                new GolfCourse.Tee(6.5, FLOOR, 2.5, 0f), new GolfCourse.Spot(3, FLOOR - 2, 3), 3,
                new GolfCourse.Spot(1, FLOOR - 3, 1), new GolfCourse.Spot(11, FLOOR + 4, 19))));
        assertEquals(List.of(), LiveProof.structure(golf, null, solid, seals, water), "a sealed pond in the plot is"
                + " fine");
        assertEquals(List.of(), LiveProof.pools(new com.dierks.homecraft.games.gen.api.Box(0, FLOOR - 3, 0, 12,
                FLOOR + 3, 20), seals, water), "the scan on its own");

        w.blocks.remove(GenKit.pos(8, FLOOR - 1, 9)); // the turf beside the pond is gone
        List<String> leak = LiveProof.structure(golf, null, solid, seals, water);
        assertEquals(1, leak.size(), "one problem: " + leak);
        assertTrue(leak.get(0).startsWith("hole 1: 1 water block has air, a slab, a sign or leaves beside or under it"
                + " (first at 7 " + (FLOOR - 1) + " 9)"), "the hole and the first open block are named: " + leak);
        assertEquals(List.of(), LiveProof.structure(golf, solid),
                "without a way to see water only the tee and cup are checked, as before");
        assertEquals(List.of(), LiveProof.structure(golf, null, solid, seals, null), "and so with none given");

        w.put(8, FLOOR - 1, 9, "minecraft:lime_concrete");
        w.blocks.remove(GenKit.pos(6, FLOOR - 2, 9)); // a hole in the pond's floor
        assertEquals(1, LiveProof.structure(golf, null, solid, seals, water).size(), "water with nothing under it is"
                + " caught");

        FakeWorld far = pondPlot();
        far.blocks.remove(GenKit.pos(8, FLOOR - 1, 9));
        LiveProof.Solid farSolid = (x, y, z) -> far.at(x, y, z) != null;
        LiveProof.Solid farWater = (x, y, z) -> far.at(x, y, z) != null
                && far.at(x, y, z).startsWith("minecraft:water");
        GolfCourse elsewhere = new GolfCourse("fresh_golf", "Golf of the Week", "games", true, 1, List.of(
                new GolfCourse.Hole(new GolfCourse.Tee(6.5, FLOOR, 30.5, 0f), new GolfCourse.Spot(6, FLOOR - 2, 40),
                        3, new GolfCourse.Spot(1, FLOOR - 3, 28), new GolfCourse.Spot(11, FLOOR + 4, 45))));
        far.put(6, FLOOR - 2, 40, "minecraft:black_concrete");
        far.put(6, FLOOR - 1, 30, "minecraft:lime_concrete");
        assertEquals(List.of(), LiveProof.structure(elsewhere, null, farSolid, seals(far), farWater),
                "only each hole's own plot is scanned (bounded)");
        assertEquals(1, LiveProof.pools(new com.dierks.homecraft.games.gen.api.Box(0, 0, 0, 512, 0, 511), seals(far),
                farWater).size(), "a box too big to scan says so");
    }

    // ---- the heal path's pond scan: the whole plot, and the plan's own seal test (Course Variety §1.3) ----

    /** A Tiny Golf sized half drawn here (64 x 16 x 48), clear of any slot. */
    private static final Box HALF = new Box(1000, 100, 2000, 1063, 115, 2047);
    private static final int T = HALF.minY() + GolfPlanner.TURF_ABOVE_FLOOR;

    /** The world as the heal path reads it (a chunk snapshot), over the fake world's blocks. */
    private static WorldPort.ChunkView view(FakeWorld w) {
        return new WorldPort.ChunkView() {
            @Override
            public boolean sectionEmpty(int y) {
                return false;
            }

            @Override
            public boolean air(int x, int y, int z) {
                return w.at(x, y, z) == null;
            }

            @Override
            public String block(int x, int y, int z) {
                return w.at(x, y, z);
            }
        };
    }

    /** What seals a pond on the heal path: the plan's own seal test on the real block ({@link GenService#seals}). */
    private static LiveProof.Solid seals(FakeWorld w) {
        WorldPort.ChunkView v = view(w);
        return (x, y, z) -> GenService.seals(v, x, y, z);
    }

    /** The heal path's structural check of {@code golf} in {@link #HALF}, on {@code w}. */
    private static List<String> healCheck(GolfCourse golf, FakeWorld w) {
        WorldPort.ChunkView v = view(w);
        return LiveProof.structure(golf, HALF, (x, y, z) -> !v.air(x, y, z), seals(w),
                (x, y, z) -> GenService.water(v, x, y, z));
    }

    /** An Easy POND_SIDE in {@link #HALF}'s first plot, as the golf planner draws it: its pond to look at. */
    private static HoleLayout pondSide() {
        int[] p = GolfPlanner.plot(HALF, 0);
        return HoleTemplate.POND_SIDE.draw(new GenRandom(5), 'E', p[0], p[1], T);
    }

    /** The hole's blocks and its scenery, built. */
    private static FakeWorld built(HoleLayout l) {
        FakeWorld w = new FakeWorld("games");
        for (HoleLayout.Placed b : l.blocks()) {
            w.put(b.x(), b.y(), b.z(), b.blockData());
        }
        for (HoleLayout.Placed b : l.scenery()) {
            w.put(b.x(), b.y(), b.z(), b.blockData());
        }
        return w;
    }

    private static GolfCourse course(HoleLayout l) {
        return new GolfCourse("fresh_tiny_golf", "Tiny Golf", "games", true, 1, List.of(l.hole(2)));
    }

    /** The pond's rim block beside its first water block (on the side away from the lane), with that water block. */
    private static int[][] rimBesideWater(HoleLayout l) {
        List<int[]> water = new ArrayList<>();
        for (HoleLayout.Placed b : l.scenery()) {
            if (b.blockData().startsWith("minecraft:water")) {
                water.add(new int[]{b.x(), b.y(), b.z()});
            }
        }
        assertFalse(water.isEmpty(), l.describe() + ": an Easy POND_SIDE has a pond to look at");
        int minX = water.stream().mapToInt(a -> a[0]).min().orElseThrow();
        int maxX = water.stream().mapToInt(a -> a[0]).max().orElseThrow();
        boolean pondEast = minX > l.hole(2).tee().x(); // the rim on the far side from the lane
        int[] w = water.get(0);
        int rimX = pondEast ? maxX + 1 : minX - 1;
        return new int[][]{{rimX, w[1], w[2]}, w};
    }

    /**
     * The final gate of Course Variety (the water review): an Easy POND_SIDE — Tiny Golf deals it —
     * puts its pond to look at two or more columns outside the hole's bounds, so the heal path's pond
     * scan, when it looked only round the bounds, never saw it. It scans the hole's whole plot now: the
     * sealed pond passes, and a gap in its rim (a torn save, a stray edit) is caught.
     */
    @Test
    void anEasyPondSidesPondToLookAtIsScannedAcrossTheWholePlotAndAGapInItsRimIsCaught() {
        HoleLayout l = pondSide();
        FakeWorld w = built(l);
        GolfCourse golf = course(l);
        int[][] rim = rimBesideWater(l);
        int[] water = rim[1];
        assertFalse(LiveProof.plotBox(golf.holes().get(0)).contains(water[0], water[1], water[2]),
                "the pond lies outside the hole's bounds and one more: " + l.describe());
        assertTrue(LiveProof.scanBox(golf.holes().get(0), 0, HALF).contains(water[0], water[1], water[2]),
                "but inside the plot the heal path scans");
        assertEquals(List.of(), healCheck(golf, w), "the whole hole, its pond to look at sealed: fine");

        assertEquals("minecraft:moss_block", w.at(rim[0][0], rim[0][1], rim[0][2]), "(the rim is moss)");
        w.blocks.remove(GenKit.pos(rim[0][0], rim[0][1], rim[0][2])); // a gap in the rim
        List<String> leak = healCheck(golf, w);
        assertEquals(1, leak.size(), "one problem: " + leak);
        assertTrue(leak.get(0).startsWith("hole 1: 1 water block has air, a slab, a sign or leaves beside or under it")
                && leak.get(0).endsWith("a pond isn't sealed"), "the hole is named: " + leak);
    }

    /**
     * The heal path seals a pond by the plan's own test ({@code Pools.seals}): a full block, never a
     * slab, a sign or leaves, which can hold water — where it used to count any block there as a
     * seal.
     */
    @Test
    void aPondWalledByASlabASignOrLeavesIsNotSealedOnTheHealPath() {
        HoleLayout l = pondSide();
        GolfCourse golf = course(l);
        int[] rim = rimBesideWater(l)[0];
        for (String wall : List.of("minecraft:smooth_stone_slab[type=bottom]", "minecraft:oak_sign[rotation=0]",
                "minecraft:oak_leaves[distance=1,persistent=true]")) {
            FakeWorld w = built(l);
            w.put(rim[0], rim[1], rim[2], wall);
            List<String> leak = healCheck(golf, w);
            assertEquals(1, leak.size(), "walled by " + wall + ": not sealed: " + leak);
            assertTrue(leak.get(0).endsWith("a pond isn't sealed"), "so it says: " + leak);
        }
        FakeWorld w = built(l);
        w.put(rim[0], rim[1], rim[2], "minecraft:stripped_spruce_wood[axis=y]");
        assertEquals(List.of(), healCheck(golf, w), "any full block of the palette seals it (a wall's wood here)");
        assertFalse(GenService.seals(null, rim[0], rim[1], rim[2]), "no snapshot: nothing seals");
    }
}
