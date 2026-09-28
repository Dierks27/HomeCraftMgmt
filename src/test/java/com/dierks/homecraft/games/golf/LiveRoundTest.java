package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.golf.BallPhysics.Surface;
import com.dierks.homecraft.games.golf.BallPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round's rules on a fake course (spec §12): a putt goes the way the player looks and counts a
 * stroke from a new last spot; water and out of bounds put the ball back on that spot with a
 * penalty stroke; "Reset ball" does the same; the cup ends the hole with its strokes; and a hole
 * whose strokes reach par + max over par is picked up — whether the ball stopped short or the
 * last penalty got it there.
 */
class LiveRoundTest {

    private static final int FLOOR = 63;
    private static final double GROUND = 64;

    /** One hole: tee at x 0.5 facing +x, the cup ten blocks on, bounds x -5..20, z -3..3. */
    private static GolfCourse course(int par) {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(0.5, GROUND, 0.5, -90f),
                new GolfCourse.Spot(10, FLOOR, 0), par, new GolfCourse.Spot(-5, FLOOR, -3), new GolfCourse.Spot(20, FLOOR + 3, 3));
        return new GolfCourse("t", "Test", "games", true, 1, List.of(h));
    }

    private static LiveRound round(int par, int maxOverPar) {
        GolfCourse c = course(par);
        LiveRound r = new LiveRound(UUID.randomUUID(), c, new GolfRun(c.pars(), maxOverPar), null);
        r.tee();
        return r;
    }

    private static Grid floor() {
        return new Grid().floor(-5, 20, -3, 3, FLOOR, Surface.NORMAL);
    }

    private static Grid pond() {
        Grid g = floor();
        for (int x = 3; x <= 5; x++) {
            for (int z = -3; z <= 3; z++) {
                g.remove(x, FLOOR, z).set(x, FLOOR, z, BallPhysics.Blocks.NONE, Surface.WATER);
            }
        }
        return g;
    }

    private static LiveRound.Result rollOut(LiveRound r, BallPhysics.Blocks g) {
        LiveRound.Result res = LiveRound.Result.ROLLING;
        for (int i = 0; i < 2000 && res == LiveRound.Result.ROLLING; i++) {
            res = r.roll(g);
        }
        return res;
    }

    @Test
    void aPuttGoesTheWayThePlayerLooksAndCountsAStroke() {
        LiveRound r = round(3, 3);
        r.putt(-90f, 2);
        assertEquals(1, r.run.strokes(), "one stroke");
        assertTrue(r.ball.vx() > 0 && Math.abs(r.ball.vz()) < 1e-12, "facing yaw -90 sends it along +x");
        LiveRound s = round(3, 3);
        s.putt(0f, 2);
        assertTrue(s.ball.vz() > 0 && Math.abs(s.ball.vx()) < 1e-12, "yaw 0 is +z");
        LiveRound w = round(3, 3);
        w.putt(90f, 2);
        assertTrue(w.ball.vx() < 0, "yaw 90 is -x");
        assertEquals(BallPhysics.speed(2), r.ball.speed(), 1e-12, "at the club's speed");
    }

    @Test
    void waterPutsTheBallBackOnItsLastSpotWithAPenaltyStroke() {
        LiveRound r = round(3, 3);
        r.putt(-90f, 3);
        assertEquals(LiveRound.Result.BACK, rollOut(r, pond()), "into the pond");
        assertEquals(BallPhysics.Outcome.WATER, r.outcome, "it was the water");
        assertEquals(2, r.run.strokes(), "the putt and the penalty");
        assertEquals(0.5, r.ball.x(), 1e-12, "back where it was putted from");
        assertEquals(GROUND, r.ball.y(), 1e-12, "on the ground");
        assertFalse(r.ball.moving(), "still, ready for the next putt");
    }

    @Test
    void outOfBoundsPutsTheBallBackToo() {
        LiveRound r = round(3, 3);
        r.putt(0f, 5); // straight at the z = 3 edge
        assertEquals(LiveRound.Result.BACK, rollOut(r, new Grid().floor(-5, 20, -10, 10, FLOOR, Surface.NORMAL)),
                "over the side");
        assertEquals(BallPhysics.Outcome.OUT, r.outcome, "it was out of bounds");
        assertEquals(2, r.run.strokes(), "the putt and the penalty");
        assertEquals(0.5, r.ball.z(), 1e-12, "back on the spot");
    }

    @Test
    void resettingTheBallCostsAStroke() {
        LiveRound r = round(3, 3);
        r.putt(-90f, 1);
        assertEquals(LiveRound.Result.STILL, rollOut(r, floor()), "a short putt stops");
        assertFalse(r.atSpot(), "it moved");
        assertEquals(LiveRound.Result.BACK, r.back(), "Reset ball");
        assertEquals(2, r.run.strokes(), "the putt and the reset");
        assertTrue(r.atSpot(), "back on the spot");
    }

    @Test
    void theCupEndsTheHoleWithItsStrokes() {
        LiveRound r = round(3, 3);
        r.putt(-90f, 3);
        assertEquals(LiveRound.Result.STILL, rollOut(r, floor()), "a Chip goes about six blocks: short");
        r.putt(-90f, 3);
        assertEquals(LiveRound.Result.IN_CUP, rollOut(r, floor()), "the next one reaches the cup slowly enough");
        assertEquals(2, r.last.strokes(), "two strokes");
        assertEquals(r.run.scores().get(0), r.last, "the hole's score is the one just made");
        assertTrue(r.run.finished(), "the only hole");
    }

    @Test
    void aHoleIsPickedUpWhenAPenaltyReachesTheLimit() {
        LiveRound r = round(2, 1); // limit 3
        r.putt(-90f, 3);
        assertEquals(LiveRound.Result.BACK, rollOut(r, pond()), "stroke 1 in the water: 2");
        r.putt(-90f, 3);
        assertEquals(LiveRound.Result.PICKED_UP, rollOut(r, pond()), "stroke 3 in the water: 4, past the limit");
        assertEquals(3, r.last.strokes(), "it scores the limit");
        assertTrue(r.last.pickedUp(), "picked up");
    }

    @Test
    void aBallThatStopsAtTheLimitIsPickedUp() {
        LiveRound r = round(2, 1); // limit 3
        for (int i = 0; i < 2; i++) {
            r.putt(-90f, 1); // Taps: about two blocks each, well short of the cup
            assertEquals(LiveRound.Result.STILL, rollOut(r, floor()), "short and still, under the limit");
        }
        r.putt(-90f, 1);
        assertEquals(LiveRound.Result.PICKED_UP, rollOut(r, floor()), "the third stops short of the cup");
        assertEquals(3, r.last.strokes(), "par + max over par");
    }
}
