package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A track as Race Night sees it ({@link RaceTrack}, EVENTS-DROPPER-SPEC §A.4.1-§A.4.4), with no
 * server: the live blocks are a fake {@link RaceTrack.Probe}.
 *
 * <p>Pinned here: a stored 2-lap loop is found and can be raced over N laps (the 64-target limit
 * holds, a point-to-point course stays at 1); the raced course starts at the grid spot; the
 * automatic grid seats rows of two behind the start on an open track, nudges round an obstacle,
 * falls back to single file in a narrow lane, and never puts a spot through a wall; an admin's grid
 * and stand follow the spacing, reach and clearance rules; a stored grid or stand is dropped by a
 * layout change; and a Fresh Boat layout of algo 2 has its stand at the half's centre, 5 above the
 * start.
 */
class RaceTrackTest {

    /** A square loop around (0, 0) of side 40, stored with two laps, the start just before the finish. */
    private static Course loop() {
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        List<Course.Mark> two = new ArrayList<>(lap);
        two.addAll(lap);
        // the start at (20, -20) facing +z (towards (20, 20)); the finish on it
        return new Course("ice", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games",
                new Course.Spot(20, 64, -16, 0, 0), two, new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
    }

    private static Course straight() {
        return new Course("run", TrialKind.BOAT, "River Run", Tier.EASY, "games", new Course.Spot(0, 64, 0, 0, 0),
                List.of(new Course.Mark(0, 64, 40, 3)), new Course.Mark(0, 64, 80, 3), null, null, true, false, 1);
    }

    /** Open ice everywhere. */
    private static final RaceTrack.Probe OPEN = new RaceTrack.Probe() {
        @Override
        public boolean seat(double x, double y, double z) {
            return true;
        }

        @Override
        public boolean open(Point a, Point b) {
            return true;
        }
    };

    /** A lane |x| <= halfWidth along the z axis: walls outside it. */
    private static RaceTrack.Probe lane(double halfWidth) {
        return new RaceTrack.Probe() {
            @Override
            public boolean seat(double x, double y, double z) {
                return Math.abs(x) <= halfWidth - 1.0; // a block from each wall
            }

            @Override
            public boolean open(Point a, Point b) {
                return Math.abs(a.x()) <= halfWidth && Math.abs(b.x()) <= halfWidth;
            }
        };
    }

    // ---- laps -------------------------------------------------------------------------------------

    @Test
    void aStoredTwoLapLoopIsFound() {
        Course c = loop();
        assertTrue(RaceTrack.loop(c), "its finish is at its start, with 8 checkpoints");
        assertEquals(4, RaceTrack.period(c.checkpoints()), "the checkpoints repeat every 4");
        assertEquals(2, RaceTrack.naturalLaps(c), "two laps stored");
        assertFalse(RaceTrack.loop(straight()), "a straight course isn't a loop");
        assertEquals(1, RaceTrack.naturalLaps(straight()), "and has one lap");
    }

    @Test
    void aLoopCanBeRacedOverOtherLapsAndAStraightOneCannot() {
        Course c = loop();
        Course three = RaceTrack.raced(c, new Course.Spot(18, 64, -24, 0, 0), 3);
        assertEquals(12, three.checkpoints().size(), "3 laps of 4 checkpoints");
        assertEquals(new Course.Spot(18, 64, -24, 0, 0), three.start(), "the raced course starts at the grid spot");
        assertEquals(45, three.minSeconds(), "the shortest time scales with the laps (30 for 2 laps, 45 for 3)");
        assertEquals(8, RaceTrack.raced(c, null, 0).checkpoints().size(), "laps 0: the course's own");
        assertNotNull(RaceTrack.lapsProblem(straight(), 3), "a straight course can't have laps");
        assertEquals(1, RaceTrack.laps(straight(), 3), "so it races its one lap");
        assertNotNull(RaceTrack.lapsProblem(c.withCheckpoints(repeat(c.checkpoints().subList(0, 4), 1, 13)), 5),
                "5 laps of 13 checkpoints would be 66 targets: refused (64 at most)");
    }

    private static List<Course.Mark> repeat(List<Course.Mark> base, int times, int per) {
        List<Course.Mark> one = new ArrayList<>();
        for (int i = 0; i < per; i++) {
            Course.Mark m = base.get(i % base.size());
            one.add(new Course.Mark(m.x() + i * 0.5, m.y(), m.z(), m.radius()));
        }
        List<Course.Mark> out = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            out.addAll(one);
        }
        return out;
    }

    // ---- the automatic grid ---------------------------------------------------------------------

    @Test
    void anOpenTrackSeatsRowsOfTwoBehindTheStart() {
        List<Course.Spot> grid = RaceTrack.autoGrid(straight(), 8, OPEN);
        assertEquals(8, grid.size(), "eight spots");
        for (Course.Spot s : grid) {
            assertTrue(s.z() <= 0.01, "every spot is behind the start (z <= 0): " + s);
            assertEquals(0f, s.yaw(), 0.5f, "facing along the course: " + s);
        }
        assertEquals(1.5, Math.abs(grid.get(0).x()), 1e-9, "rows of two, 1.5 either side of the path");
        assertEquals(-grid.get(0).x(), grid.get(1).x(), 1e-9, "the pole's row-mate on the other side");
        for (int i = 0; i < grid.size(); i++) {
            for (int j = i + 1; j < grid.size(); j++) {
                assertTrue(grid.get(i).point().flatDistance(grid.get(j).point()) >= RaceTrack.SPACING,
                        "spots are spaced: " + grid.get(i) + " / " + grid.get(j));
            }
        }
    }

    @Test
    void aNarrowLaneFallsBackToSingleFileAndNeverSeatsInAWall() {
        List<Course.Spot> grid = RaceTrack.autoGrid(straight(), 4, lane(1.5));
        assertEquals(4, grid.size(), "a 3-wide lane still seats 4, in single file");
        for (Course.Spot s : grid) {
            assertTrue(Math.abs(s.x()) <= 0.5, "every spot is clear of the walls: " + s);
        }
        assertTrue(RaceTrack.autoGrid(straight(), 4, new RaceTrack.Probe() {
            @Override
            public boolean seat(double x, double y, double z) {
                return false;
            }

            @Override
            public boolean open(Point a, Point b) {
                return false;
            }
        }).isEmpty(), "nowhere a boat can sit: no grid (the track isn't raceable)");
    }

    @Test
    void aSpotBesideAnObstacleIsNudgedSideways() {
        RaceTrack.Probe rock = new RaceTrack.Probe() {
            @Override
            public boolean seat(double x, double y, double z) {
                return !(x > 1 && x < 2.5 && z > -1 && z < 1); // a rock where the pole's right spot would be
            }

            @Override
            public boolean open(Point a, Point b) {
                return true;
            }
        };
        List<Course.Spot> grid = RaceTrack.autoGrid(straight(), 2, rock);
        assertEquals(2, grid.size(), "still two spots");
        for (Course.Spot s : grid) {
            assertTrue(rock.seat(s.x(), s.y(), s.z()), "no spot on the rock: " + s);
        }
    }

    @Test
    void aLoopsGridFollowsTheTrackBackFromTheStart() {
        List<Course.Spot> grid = RaceTrack.autoGrid(loop(), 6, OPEN);
        assertEquals(6, grid.size(), "six spots on the loop");
        for (Course.Spot s : grid) {
            assertTrue(s.z() <= -14, "behind the finish line, back towards the last checkpoint: " + s);
        }
    }

    // ---- an admin's grid and stand --------------------------------------------------------------

    @Test
    void anAdminsGridFollowsTheRules() {
        Course c = straight();
        List<Course.Spot> grid = new ArrayList<>();
        assertNull(RaceTrack.gridProblem(c, grid, new Course.Spot(1.5, 64, -3, 0, 0)), "behind the start: fine");
        grid.add(new Course.Spot(1.5, 64, -3, 0, 0));
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(1.5, 64, -4, 0, 0)), "closer than 2.5 to another");
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(0, 64, 5, 0, 0)), "in front of the start");
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(0, 64, -30, 0, 0)), "more than 24 from the start");
        for (int i = 1; i < RaceTrack.MAX_GRID; i++) {
            grid.add(new Course.Spot(-1.5 + (i % 2) * 3, 64, -3 - 4 * i, 0, 0));
        }
        assertNotNull(RaceTrack.gridProblem(c, grid, new Course.Spot(10, 64, -3, 0, 0)), "at most 8 spots");
    }

    @Test
    void aStandMustBeClearOfTheRacingLine() {
        Course c = straight();
        assertNotNull(RaceTrack.standProblem(c, List.of(), new Point(3, 70, 40)), "3 blocks off the line: too close");
        assertNull(RaceTrack.standProblem(c, List.of(), new Point(12, 70, 40)), "12 blocks off: fine");
    }

    @Test
    void aStoredGridOrStandIsDroppedByALayoutChange() {
        Course c = straight();
        List<Course.Spot> grid = List.of(new Course.Spot(1.5, 64, -3, 0, 0), new Course.Spot(-1.5, 64, -3, 0, 0));
        String stored = RaceTrack.encodeGrid(c.layoutHash(), grid);
        assertEquals(grid, RaceTrack.decodeGrid(stored, c.layoutHash()), "read back for the same layout");
        Course moved = c.withFinish(new Course.Mark(0, 64, 90, 3));
        assertNull(RaceTrack.decodeGrid(stored, moved.layoutHash()), "a new layout: the grid is dropped");
        assertTrue(RaceTrack.stale(stored, moved.layoutHash()), "and it says so (a WARN)");
        String stand = RaceTrack.encodeStand(c.layoutHash(), new Point(12, 70, 40));
        assertEquals(new Point(12, 70, 40), RaceTrack.decodeStand(stand, c.layoutHash()), "the stand, read back");
        assertNull(RaceTrack.decodeStand(stand, moved.layoutHash()), "dropped by a layout change");
    }

    @Test
    void aFreshBoatLayoutOfAlgoTwoHasItsStandBuiltIn() {
        GenTag algo2 = new GenTag("fresh_boat", "boat", 2, 20_725, 0, 1L, 'A', "abc", 0, 0, 0, List.of(0), List.of(),
                1L);
        Course c = loop().withGen(algo2);
        Box half = new Box(-64, 60, -64, 63, 75, 63);
        assertEquals(new Point(0, 69, 0), RaceTrack.freshStand(c, half), "the half's centre, 5 above the start");
        GenTag algo1 = new GenTag("fresh_boat", "boat", 1, 20_725, 0, 1L, 'A', "abc", 0, 0, 0, List.of(0), List.of(),
                1L);
        assertNull(RaceTrack.freshStand(loop().withGen(algo1), half), "algo 1 has no stand");
        assertNull(RaceTrack.freshStand(loop(), half), "a hand-built track has no built-in stand");
    }

    @Test
    void anAutoNightTakesTurnsByItsIdAndNeverAtRandom() {
        List<String> tracks = List.of("fresh_boat", "river_run", "ice_loop");
        String a = RaceTrack.pick(tracks, "rn-20261002-1900");
        assertEquals(a, RaceTrack.pick(List.of("ice_loop", "fresh_boat", "river_run"), "rn-20261002-1900"),
                "the same night picks the same track, however the list is ordered");
        assertTrue(tracks.contains(a), "one of the raceable tracks");
        assertNull(RaceTrack.pick(List.of(), "rn-20261002-1900"), "no track, no pick");
    }
}
