package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expert search (GEN-SPEC §4.3) on holes whose answer is known from the measured physics:
 * straight 1-putt distances are 2, 3-4, 5-6, 8-9 and 12-13 blocks, so a 13-block straight is a
 * hole in one (par 2) and a 15-block one needs two; a short dogleg with no line of sight is holed
 * in one only off its slime elbow; an island green is reached up its ramp and never over its
 * side. Every witness line replays from the tee into the cup in exactly E strokes, and the search
 * is counted work: the same budget always ends the same way.
 */
class ExpertSearchTest {

    private static ExpertSearch.Result search(HoleLayout l, int depth, Work work) throws GenFailed {
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        return ExpertSearch.search(g, h, LaneMap.of(g, h), depth, work);
    }

    @Test
    void aStraight13BlockLaneIsAHoleInOne() throws GenFailed {
        ExpertSearch.Result r = search(GolfKit.straight(13, 0), 3, Work.unlimited());
        assertTrue(r.found(), "a 13-block straight is solvable");
        assertEquals(1, r.strokes(), "13 blocks is in the 12-13 band of a power-5 putt: E = 1");
        assertEquals(2, GolfPlanner.par(r.strokes()), "so par 2");
        assertEquals(5, r.witness().get(0).power(), "with the driver");
    }

    @Test
    void aStraight15BlockLaneTakesTwo() throws GenFailed {
        ExpertSearch.Result r = search(GolfKit.straight(15, 0), 3, Work.unlimited());
        assertTrue(r.found(), "a 15-block straight is solvable");
        assertEquals(2, r.strokes(), "15 blocks is past the longest putt (12.9): E = 2");
        assertEquals(3, GolfPlanner.par(r.strokes()), "so par 3");
    }

    @Test
    void aDoglegIsHoledInOneOnlyOffItsElbow() throws GenFailed {
        HoleLayout l = GolfKit.dogleg(6, 6, 1);
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        LaneMap lane = LaneMap.of(g, h);
        assertFalse(lane.clear(h.tee().x(), h.tee().z(), h.cup().x() + 0.5, h.cup().z() + 0.5),
                "the cup can't be seen from the tee: no straight putt reaches it");
        ExpertSearch.Result r = ExpertSearch.search(g, h, lane, 3, Work.unlimited());
        assertTrue(r.found(), "the dogleg is solvable");
        assertEquals(1, r.strokes(), "yet the expert holes it in one");
        BallPhysics.Hole area = GolfShot.area(g, h);
        BallPhysics.Ball ball = GolfShot.tee(g, h);
        Putt putt = r.witness().get(0);
        GolfShot.Direction d = GolfShot.direction(putt.yaw());
        ball.putt(d.dx(), d.dz(), BallPhysics.speed(putt.power()));
        boolean banked = false;
        BallPhysics.Outcome o = BallPhysics.Outcome.ROLLING;
        for (int t = 0; t < GolfShot.MAX_ROLL_TICKS && o == BallPhysics.Outcome.ROLLING; t++) {
            double vz = ball.vz();
            o = BallPhysics.tick(ball, g, area);
            banked |= vz > 0 && ball.vz() < 0;
        }
        assertEquals(BallPhysics.Outcome.IN_CUP, o, "the witness drops");
        assertTrue(banked, "and it gets there by bouncing back off the elbow's far wall: a bank shot");
    }

    @Test
    void anIslandGreenIsReachedUpItsRampAndNeverOverItsSide() throws GenFailed {
        HoleLayout l = GolfKit.island(6, 8, 10);
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        int turf = GolfKit.TURF;
        ExpertSearch.Result r = search(l, 3, Work.unlimited());
        assertTrue(r.found(), "the island is solvable");
        assertTrue(r.strokes() >= 1 && r.strokes() <= 3, "within three: " + r.strokes());
        BallPhysics.Hole area = GolfShot.area(g, h);
        BallPhysics.Ball ball = GolfShot.tee(g, h);
        boolean onRamp = false;
        boolean onGreen = false;
        for (Putt putt : r.witness()) {
            GolfShot.Direction d = GolfShot.direction(putt.yaw());
            ball.putt(d.dx(), d.dz(), BallPhysics.speed(putt.power()));
            BallPhysics.Outcome o = BallPhysics.Outcome.ROLLING;
            for (int t = 0; t < GolfShot.MAX_ROLL_TICKS && o == BallPhysics.Outcome.ROLLING; t++) {
                o = BallPhysics.tick(ball, g, area);
                onRamp |= Math.abs(ball.y() - (turf + 0.5)) < 1e-9;
                onGreen |= Math.abs(ball.y() - (turf + 1)) < 1e-9;
            }
        }
        assertTrue(onRamp && onGreen, "the expert's ball climbs the slabs onto the green");
        BallPhysics.Ball side = new BallPhysics.Ball(GolfKit.PLOT_X + 7.5, turf, GolfKit.PLOT_Z + 4.5);
        GolfShot.Result hit = GolfShot.play(g, area, side, new Putt(0, 5));
        assertEquals(BallPhysics.Outcome.STOPPED, hit.outcome(), "a drive up the side column stops");
        assertTrue(hit.y() < turf + 1e-9 && hit.z() < GolfKit.PLOT_Z + 9, "back down on the approach: the green's "
                + "side is a step too high to climb (" + hit.y() + ", " + hit.z() + ")");
    }

    @Test
    void everyWitnessReplaysExactlyFromTheTee() throws GenFailed {
        int checked = 0;
        for (HoleTemplate t : HoleTemplate.values()) {
            char tier = t == HoleTemplate.SAFE_STRAIGHT ? 'S' : HoleTemplate.forTier('E').contains(t) ? 'E'
                    : HoleTemplate.forTier('M').contains(t) ? 'M' : 'H';
            for (long seed = 0; seed < 6; seed++) {
                HoleLayout l = GolfKit.draw(t, tier, seed);
                ExpertSearch.Result r = search(l, 3, Work.unlimited());
                if (!r.found()) {
                    continue;
                }
                GolfShot.Replay replay = GolfShot.replay(GolfKit.grid(l), GolfKit.hole(l), r.witness());
                assertTrue(replay.holed(), l.describe() + ": the witness drops when replayed");
                assertEquals(r.strokes(), replay.putts(), l.describe() + ": in exactly E putts");
                assertEquals(r.strokes(), replay.strokes(), l.describe() + ": with no penalty strokes");
                assertEquals(r.strokes(), r.witness().size(), l.describe() + ": E is the witness's length");
                checked++;
            }
        }
        assertTrue(checked >= 40, "enough holes were checked: " + checked);
    }

    @Test
    void theBudgetIsCountedSoItEndsTheSameEveryTime() throws GenFailed {
        HoleLayout l = GolfKit.dogleg(12, 8, 0);
        Work a = Work.unlimited();
        Work b = Work.unlimited();
        ExpertSearch.Result first = search(l, 3, a);
        ExpertSearch.Result second = search(l, 3, b);
        assertEquals(first, second, "the same hole, the same line");
        assertEquals(a.used(), b.used(), "and exactly the same work");
        assertTrue(a.used() > 360, "a dogleg needs a second level: " + a.used());
        for (long cap : new long[]{1, 200, 360, a.used() - 5}) {
            Work c = new Work(cap, null);
            Work d = new Work(cap, null);
            ExpertSearch.Result cut = search(l, 3, c);
            assertEquals(ExpertSearch.Status.OVER_BUDGET, cut.status(), "a cap of " + cap + " runs out");
            assertEquals(cut, search(l, 3, d), "the same way every time");
            assertEquals(c.used(), d.used(), "after the same work");
            assertTrue(c.used() >= cap, "having spent its cap of " + cap);
        }
        assertEquals(ExpertSearch.Status.NONE, search(GolfKit.straight(15, 0), 1, Work.unlimited()).status(),
                "no line within the depth allowed is NONE, not a failure");
    }

    @Test
    void directionsAreTriedNearestTheLanesWayFirst() {
        int[] order = ExpertSearch.order(0);
        assertEquals(72, order.length, "every 5 degrees");
        assertArrayEquals(new int[]{0, 1, 71, 2, 70}, java.util.Arrays.copyOf(order, 5),
                "straight on, then 5 either side (the lower number first on a tie), then 10");
        assertEquals(List.of(18, 17, 19), List.of(ExpertSearch.order(90)[0], ExpertSearch.order(90)[1],
                ExpertSearch.order(90)[2]), "round a bearing of 90");
        assertEquals(10.0, ExpertSearch.angleBetween(355, 5), 1e-9, "the short way round");
    }
}
