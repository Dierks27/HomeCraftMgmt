package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starting grid (EVENTS-DROPPER-SPEC §A.4.3), on a fake {@link RaceGrid.Surface}: rows of two on
 * a straight track and on a Fresh Ice Boat loop, a failing spot nudged sideways, single file when
 * rows of two can't fit, never within a block of a wall, a hand-set grid's rules, and a stored grid
 * dropped once the layout changes. Five hundred Fresh Ice Boat seeds per tier seat at least 8 (easy
 * and medium) or 2 (hard).
 */
class RaceGridTest {

    /** A world of blocks the test sets: everything else is air. */
    static final class Blocks implements RaceGrid.Surface {
        final Set<Long> solid = new HashSet<>();
        final Set<Long> water = new HashSet<>();

        static long key(int x, int y, int z) {
            return ((long) (y & 0xFFF) << 52) ^ ((long) (x & 0x3FFFFFF) << 26) ^ (z & 0x3FFFFFFL);
        }

        void solid(int x, int y, int z) {
            solid.add(key(x, y, z));
        }

        void air(int x, int y, int z) {
            solid.remove(key(x, y, z));
            water.remove(key(x, y, z));
        }

        @Override
        public RaceGrid.Cell at(int x, int y, int z) {
            long k = key(x, y, z);
            return solid.contains(k) ? RaceGrid.Cell.SOLID : water.contains(k) ? RaceGrid.Cell.WATER
                    : RaceGrid.Cell.AIR;
        }
    }

    /**
     * A straight walled ice lane along z, the ice at y 64, walls two high at x = -{@code half} - 1
     * and {@code half} + 1 (blocks), the lane blocks -{@code half}..{@code half}, from z -70 to 10.
     */
    static Blocks lane(int half) {
        Blocks b = new Blocks();
        for (int z = -70; z <= 10; z++) {
            for (int x = -half - 1; x <= half + 1; x++) {
                b.solid(x, 64, z);
            }
            for (int y = 65; y <= 66; y++) {
                b.solid(-half - 1, y, z);
                b.solid(half + 1, y, z);
            }
        }
        return b;
    }

    /** A straight boat course on {@link #lane}: the start at the lane's middle, facing +z (yaw 0). */
    static Course straightBoat(double midX) {
        return new Course("lane", TrialKind.BOAT, "Lane", Tier.EASY, "games", new Course.Spot(midX, 65, 0, 0, 0),
                List.of(new Course.Mark(midX, 65, 5, 4)), new Course.Mark(midX, 65, 9, 4), 60.0, null, true, false,
                1);
    }

    /** The shortest distance across the ground from {@code p} to any wall block at its boat height. */
    static double toWall(Blocks b, Point p) {
        int by = (int) Math.floor(p.y());
        double best = Double.MAX_VALUE;
        for (int x = (int) Math.floor(p.x()) - 3; x <= (int) Math.floor(p.x()) + 3; x++) {
            for (int z = (int) Math.floor(p.z()) - 3; z <= (int) Math.floor(p.z()) + 3; z++) {
                if (b.at(x, by, z) == RaceGrid.Cell.SOLID || b.at(x, by + 1, z) == RaceGrid.Cell.SOLID) {
                    double ex = Math.max(0, Math.max(x - p.x(), p.x() - (x + 1)));
                    double ez = Math.max(0, Math.max(z - p.z(), p.z() - (z + 1)));
                    best = Math.min(best, Math.hypot(ex, ez));
                }
            }
        }
        return best;
    }

    @Test
    void aStraightTrackSeatsRowsOfTwoBehindTheStart() {
        Blocks b = lane(4);
        Course c = straightBoat(0.5);
        RaceGrid.Grid g = RaceGrid.forCourse(c, b, 8);
        assertEquals(RaceGrid.Mode.DOUBLE, g.mode(), "a 9-wide lane seats rows of two: " + g.notes());
        assertEquals(8, g.size(), "everyone gets a spot");
        for (int i = 0; i < g.size(); i++) {
            Course.Spot s = g.spot(i);
            int row = i / 2;
            assertEquals(-row * RaceGrid.ROW_GAP, s.z(), 1e-9, "row " + row + " is " + (row * 4) + " behind the start");
            assertEquals(RaceGrid.SIDE, Math.abs(s.x() - 0.5), 1e-9, "1.5 either side of the path");
            assertEquals(0f, s.yaw(), 1e-3f, "facing the way the race goes");
            assertEquals(65, s.y(), 1e-9, "at the start's height");
            assertTrue(toWall(b, s.point()) >= RaceGrid.WALL_GAP, "a block from any wall");
        }
        assertTrue(g.spot(0).x() != g.spot(1).x(), "the two of a row are side by side");
        assertEquals(12, RaceGrid.forCourse(c, b, 20).size(), "never more than 12 spots");
    }

    @Test
    void aFailingSpotIsNudgedSidewaysAndStaysOnItsSide() {
        Blocks b = lane(4);
        Course c = straightBoat(0.5);
        Course.Spot wanted = RaceGrid.forCourse(c, b, 4).spot(2); // row 1, the first side
        int hx = (int) Math.floor(wanted.x());
        int hz = (int) Math.floor(wanted.z());
        b.air(hx, 64, hz); // a hole in the ice under it
        RaceGrid.Grid g = RaceGrid.forCourse(c, b, 4);
        assertEquals(RaceGrid.Mode.DOUBLE, g.mode(), "still rows of two");
        Course.Spot nudged = g.spot(2);
        assertEquals(wanted.z(), nudged.z(), 1e-9, "the same row");
        double moved = Math.abs(nudged.x() - wanted.x());
        assertTrue(moved > 0 && moved <= RaceGrid.NUDGE + 1e-9 && Math.abs(moved / 0.5 - Math.round(moved / 0.5)) < 1e-9,
                "nudged sideways in half blocks, up to 2: " + moved);
        assertTrue((nudged.x() - 0.5) * (wanted.x() - 0.5) > 0, "on the same side of the path");
        assertTrue(RaceGrid.problem(nudged.point(), b) == null, "and somewhere a boat fits");
        assertTrue(RaceGrid.problem(wanted.point(), b) != null, "where the hole is, it doesn't");
    }

    @Test
    void aNarrowTrackFallsBackToSingleFile() {
        Blocks b = lane(1); // three blocks of ice: no room for two side by side
        Course c = straightBoat(0.5);
        RaceGrid.Grid g = RaceGrid.forCourse(c, b, 6);
        assertEquals(RaceGrid.Mode.SINGLE, g.mode(), "single file: " + g.notes());
        assertEquals(6, g.size(), "everyone still gets a spot");
        for (int i = 0; i < g.size(); i++) {
            assertEquals(0.5, g.spot(i).x(), 1e-9, "on the path");
            assertEquals(-i * RaceGrid.SINGLE_GAP, g.spot(i).z(), 1e-9, "5 apart");
            assertTrue(toWall(b, g.spot(i).point()) >= RaceGrid.WALL_GAP, "clear of the walls");
        }
        assertTrue(!g.notes().isEmpty(), "and it says why rows of two didn't fit");
    }

    @Test
    void wallsAndBlockedWaysAreRespected() {
        Blocks b = lane(4);
        Course c = straightBoat(0.5);
        // a wall right across the lane 10 behind the start: nothing behind it can get to the start
        for (int x = -4; x <= 4; x++) {
            b.solid(x, 65, -10);
        }
        RaceGrid.Grid g = RaceGrid.forCourse(c, b, 12);
        for (Course.Spot s : g.spots()) {
            assertTrue(s.z() > -10, "no spot behind the wall across the lane: " + s);
            assertTrue(toWall(b, s.point()) >= RaceGrid.WALL_GAP, "every spot a block from any wall: " + s);
        }
        assertTrue(g.size() >= 2 && g.size() < 12, "the rows in front of it only: " + g.size());
        // a lane with no ice: nowhere to seat a boat at all
        Blocks none = new Blocks();
        assertEquals(0, RaceGrid.forCourse(c, none, 4).size(), "no floor, no grid");
        assertEquals("no ice, water or floor under it", RaceGrid.problem(new Point(0.5, 65, 0), none), "in its words");
        Blocks pond = new Blocks();
        pond.water.add(Blocks.key(0, 64, 0));
        assertNull(RaceGrid.problem(new Point(0.5, 65, 0.5), pond), "a boat floats on water");
    }

    @Test
    void runnersAndFlyersShareTheStart() {
        Course run = LapsTest.straight();
        RaceGrid.Grid g = RaceGrid.forCourse(run, (x, y, z) -> RaceGrid.Cell.AIR, 5);
        assertEquals(RaceGrid.Mode.SHARED, g.mode(), "on foot nobody can push anybody: everyone on the start");
        assertEquals(5, g.size(), "a spot each");
        for (Course.Spot s : g.spots()) {
            assertEquals(run.start(), s, "the start itself");
        }
    }

    @Test
    void aHandSetGridHasItsRulesAndIsDroppedByALayoutChange() {
        Course c = straightBoat(0.5);
        List<Course.Spot> spots = new ArrayList<>();
        assertNull(RaceGrid.addProblem(c, spots, new Course.Spot(-1, 65, -3, 0, 0)), "behind the start: fine");
        spots.add(new Course.Spot(-1, 65, -3, 0, 0));
        assertTrue(RaceGrid.addProblem(c, spots, new Course.Spot(0, 65, -4, 0, 0)).contains("apart"),
                "too close to another spot");
        assertTrue(RaceGrid.addProblem(c, spots, new Course.Spot(0, 65, 6, 0, 0)).contains("behind the start"),
                "not ahead of the start");
        assertTrue(RaceGrid.addProblem(c, spots, new Course.Spot(0, 65, -30, 0, 0)).contains("within 24"),
                "not more than 24 from it");
        for (int i = 1; i < RaceGrid.HAND_MAX; i++) {
            spots.add(new Course.Spot(-1 + (i % 2) * 3, 65, -3 - i * 2.6, 0, 0));
        }
        assertTrue(RaceGrid.addProblem(c, spots, new Course.Spot(3, 65, -1, 0, 0)).contains("at most 8"),
                "at most 8 spots");

        int layout = c.layoutHash();
        assertEquals(spots, RaceGrid.stored(spots, layout, c), "the layout it was set on: the stored grid");
        Course moved = c.withFinish(new Course.Mark(0.5, 65, 12, 4));
        assertNull(RaceGrid.stored(spots, layout, moved), "the layout changed: dropped, the auto grid applies");
        assertNull(RaceGrid.stored(spots, layout, null), "the course is gone");
        assertNull(RaceGrid.stored(List.of(), layout, c), "nothing stored");
    }

    // ---- Fresh Ice Boat ---------------------------------------------------------------------------

    /** The plan's blocks as the grid reads them: ice and walls are solid. */
    static Blocks blocksOf(Plan p) {
        Blocks b = new Blocks();
        for (BlockOp op : p.ops()) {
            if (!Palette.poolWater(p.blockOf(op))) {
                b.solid(op.x(), op.y(), op.z());
            }
        }
        return b;
    }

    static Plan freshBoat(long seed, String tier) throws GenFailed {
        Slots.Def slot = Slots.ICE_BOAT;
        return new BoatPlanner().plan(new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', 20725, 0, seed, tier, 6, 0,
                null));
    }

    @Test
    void aFreshIceBoatMountainRunSeatsTwelveInRowsOfTwoInItsPitBehindTheStart() throws GenFailed {
        Plan p = freshBoat(0xC0FFEEL, "medium");
        Course c = ((PlannedTrial) p.course()).course();
        Blocks b = blocksOf(p);
        assertFalse(Laps.loop(c), "Fresh Ice Boat is a downhill sprint (algo 3), never a loop");
        RaceGrid.Grid g = RaceGrid.forCourse(c, b, RaceGrid.MAX_SPOTS);
        assertEquals(RaceGrid.Mode.DOUBLE, g.mode(), "rows of two in the 7-wide pit: " + g.notes());
        assertEquals(RaceGrid.MAX_SPOTS, g.size(), "a full Race Night");
        List<Point> path = RaceGrid.path(c);
        assertEquals(c.start().point(), path.get(0), "the path starts at the start");
        for (Course.Spot s : g.spots()) {
            assertNull(RaceGrid.problem(s.point(), b), "every spot fits a boat: " + s);
            assertTrue(toWall(b, s.point()) >= RaceGrid.WALL_GAP, "a block from the walls");
            assertTrue(s.point().flatDistance(c.start().point()) <= 21, "in the pit, 0-20 behind the start");
            assertEquals(c.start().y(), s.y(), 1e-9, "on the pit's ice, level with the start");
        }
    }

    @Test
    void aHundredFreshBoatSeedsPerTierSeatAFullGrid() {
        for (String tier : List.of("easy", "medium", "hard")) {
            List<String> short_ = Collections.synchronizedList(new ArrayList<>());
            IntStream.range(0, 100).parallel().forEach(day -> {
                long seed = GenSeed.seed(0x5EC12E7L, 20_000 + day, Slots.ICE_BOAT.id(), 0);
                try {
                    Plan p = freshBoat(seed, tier);
                    Course c = ((PlannedTrial) p.course()).course();
                    RaceGrid.Grid g = RaceGrid.forCourse(c, blocksOf(p), RaceGrid.MAX_SPOTS);
                    if (g.size() < RaceGrid.MAX_SPOTS || g.mode() != RaceGrid.Mode.DOUBLE) {
                        short_.add("day " + day + ": " + g.size() + " " + g.mode() + " " + g.notes());
                    }
                } catch (GenFailed e) {
                    short_.add("day " + day + ": " + e.getMessage());
                }
            });
            assertTrue(short_.isEmpty(), tier + ": every seed seats 12 in rows of two, " + short_.size()
                    + " didn't, first " + short_.subList(0, Math.min(3, short_.size())));
        }
    }

    @Test
    void theNudgesAreHalfBlocksOutToTwo() {
        double[] n = RaceGrid.nudges();
        assertEquals(9, n.length, "none, then 4 each way");
        assertEquals(0, n[0], 1e-9, "the spot itself first");
        assertEquals(0.5, n[1], 1e-9, "then half a block");
        assertEquals(-0.5, n[2], 1e-9, "either way");
        assertEquals(2.0, n[7], 1e-9, "out to 2");
        assertEquals(-2.0, n[8], 1e-9, "both ways");
        assertNotNull(RaceGrid.forCourse(Course.create("x", TrialKind.BOAT, Tier.EASY), (x, y, z) -> RaceGrid.Cell.AIR,
                3), "a course with no start gives an empty grid, never a throw");
    }
}
