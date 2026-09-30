package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adventure Golf's lane reading (Course Variety §3.6-3.7): an older layout is read exactly as it
 * always was; version 3 takes T from the bounds (a raised tee works), reads the ball's layer up to
 * T + 2 (a wall beside a terrace is a wall), treats a pond as a hazard the lane, the path distances
 * and every sight line go round, and names lane the ball can never leave for the cup. And the flight
 * rule's reach is the physics' own number: a wall just beyond it always stops a full-power ball off
 * the lip, and one well inside it doesn't.
 */
class LaneMapV3Test {

    private static final int T = AdventureKit.TURF;

    /** Every column of two readings of the same hole agrees: surface, lane, pond, path distance. */
    private static void same(LaneMap a, LaneMap b, String what) {
        assertEquals(a.turfY, b.turfY, what + ": the same T");
        for (int x = a.minX; x < a.minX + a.sizeX; x++) {
            for (int z = a.minZ; z < a.minZ + a.sizeZ; z++) {
                String at = what + " at " + x + "," + z;
                assertEquals(a.surface(x, z), b.surface(x, z), 1e-9, at + ": the surface");
                assertEquals(a.isLane(x, z), b.isLane(x, z), at + ": lane or not");
                assertEquals(a.isHazard(x, z), b.isHazard(x, z), at + ": pond or not");
                assertEquals(a.pathDistance(x + 0.5, z + 0.5), b.pathDistance(x + 0.5, z + 0.5), 1e-9,
                        at + ": the path distance");
            }
        }
        assertEquals(a.waypoints(), b.waypoints(), what + ": the same centre line");
        for (int i = 0; i < a.waypoints(); i++) {
            assertEquals(a.waypointX(i), b.waypointX(i), 1e-9, what + ": waypoint " + i);
            assertEquals(a.waypointZ(i), b.waypointZ(i), 1e-9, what + ": waypoint " + i);
        }
    }

    @Test
    void anOlderLayoutIsReadAsItAlwaysWasAndVersionThreeReadsTheOldShapesTheSame() {
        for (HoleTemplate t : HoleTemplate.values()) {
            for (char tier : new char[]{'E', 'M', 'H'}) {
                if (!t.fits(tier) && t != HoleTemplate.SAFE_STRAIGHT) {
                    continue;
                }
                for (long seed = 0; seed < 5; seed++) {
                    HoleLayout l = GolfKit.draw(t, tier, seed);
                    PlanBlocks g = GolfKit.grid(l);
                    GolfCourse.Hole h = GolfKit.hole(l);
                    String what = t + " " + tier + " seed " + seed;
                    same(LaneMap.of(g, h), LaneMap.of(g, h, 1), what + " (version 1)");
                    same(LaneMap.of(g, h), LaneMap.of(g, h, 2), what + " (version 2)");
                    same(LaneMap.of(g, h), LaneMap.of(g, h, 3), what + " (read as version 3: no terrace, no pond)");
                    assertEquals(0, LaneMap.of(g, h, 3).hazards(), what + ": and no pond");
                    assertEquals(List.of(), LaneMap.of(g, h, 3).stranded(), what + ": nothing stranded");
                }
            }
        }
    }

    /** A tee terrace two blocks up, a step to one up, then the turf with the cup. */
    private static AdventureKit.Drawn terraces() {
        return AdventureKit.draw(0,
                "%%%%%%%",
                "%44444%",
                "%44444%",
                "%44444%",
                "%44444%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "%22222%",
                "=00000=",
                "=00000=",
                "=00c00=",
                "=00000=",
                "=======").tee(3, 2);
    }

    @Test
    void theBaseIsTheBoundsFloorSoATeeOnATerraceWorks() {
        AdventureKit.Drawn d = terraces();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(3);
        assertEquals(T + 2, h.tee().y(), 1e-9, "the tee stands on the top terrace, two blocks up");
        LaneMap v3 = LaneMap.of(g, h, 3);
        assertEquals(T, v3.turfY, "version 3: T is the bounds' floor + 3, whatever the tee stands on");
        assertEquals(T + 2, LaneMap.of(g, h).turfY, "(the old reading takes it from the tee: two blocks too high)");
        assertEquals(T + 2, v3.surface(d.x(3), d.z(2)), 1e-9, "the top terrace");
        assertEquals(T + 1, v3.surface(d.x(3), d.z(6)), 1e-9, "the middle one");
        assertEquals(T, v3.surface(d.x(3), d.z(14)), 1e-9, "the turf");
        assertTrue(v3.isLane(d.x(3), d.z(14)) && v3.isLane(d.x(1), d.z(16)), "every terrace is lane, down from the tee");
        assertTrue(v3.pathDistance(h.tee().x(), h.tee().z()) < Double.POSITIVE_INFINITY, "the cup is reached");
        assertEquals(List.of(), v3.stranded(), "and every cell has its way down to the cup");
    }

    @Test
    void theWindowReachesTwoAboveTSoAWallBesideTheTopTerraceIsAWall() {
        AdventureKit.Drawn d = terraces();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(3);
        int wx = d.x(0);
        int wz = d.z(2);
        LaneMap v3 = LaneMap.of(g, h, 3);
        assertEquals(T + 3, v3.surface(wx, wz), 1e-9, "the wall beside the T + 2 terrace reads its real top");
        assertFalse(v3.isLane(wx, wz), "a block above the terrace: a wall, not lane");
        // read with the old window (T + 1) from the same T, that wall's top block is out of sight
        assertEquals(T + 2, LaneMap.columnTop(g, wx, wz, T), 1e-9,
                "the old window would see it no higher than the terrace beside it: as lane");
    }

    /** A 9-wide lane with a 3 x 3 pond in its middle, the tee and cup either side. */
    static final String[] MIDDLE_POND = {
            "###########",
            "#000000000#",
            "#0000t0000#",
            "#000000000#",
            "#000000000#",
            "#000000000#",
            "#000000000#",
            "#000~~~000#",
            "#000~~~000#",
            "#000~~~000#",
            "#000000000#",
            "#000000000#",
            "#000000000#",
            "#000000000#",
            "#0000c0000#",
            "#000000000#",
            "###########"};

    @Test
    void aPondIsAHazardTheLaneThePathAndEverySightLineGoRound() {
        AdventureKit.Drawn d = AdventureKit.draw(0, MIDDLE_POND);
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(3);
        LaneMap v3 = LaneMap.of(g, h, 3);
        assertEquals(9, v3.hazards(), "the 3 x 3 pond");
        int px = d.x(5);
        int pz = d.z(8);
        assertTrue(v3.isHazard(px, pz), "its middle is a hazard");
        assertFalse(v3.isLane(px, pz), "not lane: the ball can't roll there");
        assertTrue(Double.isNaN(v3.surface(px, pz)), "no surface to stand on");
        assertEquals(Double.POSITIVE_INFINITY, v3.pathDistance(px + 0.5, pz + 0.5), "no path through it");
        double behind = v3.pathDistance(d.x(5) + 0.5, d.z(6) + 0.5);
        assertTrue(behind > 8 + 1, "from just before the pond the way to the cup goes round it: " + behind);
        assertFalse(v3.clear(d.x(5) + 0.5, d.z(6) + 0.5, d.x(5) + 0.5, d.z(10) + 0.5),
                "no sight line over the water: the kid never aims across it");
        assertTrue(v3.clear(d.x(2) + 0.5, d.z(6) + 0.5, d.x(2) + 0.5, d.z(10) + 0.5), "but it sees past beside it");
        for (int i = 0; i < v3.waypoints(); i++) {
            int x = (int) Math.floor(v3.waypointX(i));
            int z = (int) Math.floor(v3.waypointZ(i));
            assertFalse(v3.isHazard(x, z), "waypoint " + i + " is never in the pond");
        }
        LaneMap v2 = LaneMap.of(g, h);
        assertEquals(0, v2.hazards(), "(the old reading has no ponds: no older layout has water)");
    }

    @Test
    void laneTheBallCanReachButNeverLeaveForTheCupIsStranded() {
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "%%%%%%%%%%",
                "%22222%%%%",
                "%22222%%%%",
                "%22222%%%%",
                "%222222000",
                "%222222000",
                "%222222000",
                "%22222%%%%",
                "%22222%%%%",
                "%22222%%%%",
                "%%%%%%%%%%").tee(3, 2).cup(3, 8);
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(3);
        LaneMap v3 = LaneMap.of(g, h, 3);
        List<int[]> stranded = v3.stranded();
        Set<String> cells = new HashSet<>();
        for (int[] c : stranded) {
            cells.add((c[0] - d.x(0)) + "," + (c[1] - d.z(0)));
        }
        assertEquals(Set.of("7,4", "8,4", "9,4", "7,5", "8,5", "9,5", "7,6", "8,6", "9,6"), cells,
                "the bay a block down off the side: a ball drops in and can't climb back to the cup");
    }

    // ---- the flight rule's reach -----------------------------------------------------------------------

    @Test
    void theReachIsPinned() {
        assertEquals(1.549, LaneMap.flightReach(0), 1e-3, "no drop at all: the level first tick and the ball's size");
        assertEquals(7.216, LaneMap.flightReach(0.5), 1e-3, "half a block");
        assertEquals(9.710, LaneMap.flightReach(1.0), 1e-3, "a block");
        assertEquals(11.563, LaneMap.flightReach(1.5), 1e-3, "a block and a half");
        assertEquals(13.107, LaneMap.flightReach(2.0), 1e-3, "two blocks");
        assertEquals(1.3, LaneMap.FASTEST, 1e-12, "at the hardest putt, the fastest a ball ever goes");
        double last = -1;
        for (double drop = 0; drop <= 3; drop += 0.25) {
            double r = LaneMap.flightReach(drop);
            assertTrue(r > last, "a longer drop takes longer: " + drop);
            last = r;
        }
        assertEquals(LaneMap.flightReach(0.75), LaneMap.flightReach(0.75), 1e-12, "between half blocks too");
    }

    /**
     * A ledge (x < 0) whose top is at {@code lip}, a floor at T beyond it, a one-column wall at x =
     * {@code wall} whose top is {@code top}, and floor again past it.
     */
    private static BallPhysics.Blocks ledge(double lip, int wall, double top) {
        return new BallPhysics.Blocks() {
            @Override
            public double top(int x, int y, int z, double px, double pz) {
                double surface = x < 0 ? lip : x == wall ? top : T;
                return y >= T - 1 && y + 1 <= surface + 1e-9 ? 1.0 : NONE;
            }

            @Override
            public BallPhysics.Surface surface(int x, int y, int z) {
                return BallPhysics.Surface.NORMAL;
            }
        };
    }

    /**
     * Whether any full-power putt straight off the lip, from anywhere on its last block (every
     * hundredth of a block, out to where its footprint still rests on the edge), gets onto or past
     * the wall.
     */
    private static boolean escapes(double lip, int wall, double top) {
        BallPhysics.Blocks b = ledge(lip, wall, top);
        BallPhysics.Hole area = BallPhysics.Hole.of(1000, T, 1000, -50, T - 10, -50, 200, T + 10, 50);
        for (int i = 0; i <= 112; i++) {
            double x0 = -1 + i * 0.01;
            GolfShot.Result r = GolfShot.play(b, area, new BallPhysics.Ball(x0, lip, 0.5), new Putt(-90, 5));
            if (r.x() >= wall - 1e-9) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aWallJustBeyondTheReachStopsEveryBallOffTheLipAndOneWellInsideItDoesnt() {
        // lip one block up, wall one above the lower lane: the ball must drop half a block to be stopped
        double r = LaneMap.flightReach(0.5);
        int beyond = (int) Math.floor(r) + 1; // the wall column whose near edge is just past the reach
        assertFalse(escapes(T + 1, beyond, T + 1), "a wall " + beyond + " off (reach " + r + ") stops every ball");
        assertTrue(escapes(T + 1, beyond - 2, T + 1), "one two blocks nearer is climbed or flown over");
        // lip two blocks up, the same wall: a drop of a block and a half
        double r15 = LaneMap.flightReach(1.5);
        int beyond15 = (int) Math.floor(r15) + 1;
        assertFalse(escapes(T + 2, beyond15, T + 1), "a wall " + beyond15 + " off (reach " + r15 + ") stops them");
        assertTrue(escapes(T + 2, beyond15 - 2, T + 1), "one two blocks nearer doesn't");
        // a wall no higher than the lip: nothing stops a ball flying level at it but distance
        assertTrue(escapes(T + 1, 1, T + 1), "a wall as high as the lip, one block off, is simply rolled onto");
    }
}
