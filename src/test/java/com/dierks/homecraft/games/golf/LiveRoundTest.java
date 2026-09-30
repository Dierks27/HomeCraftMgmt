package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.golf.BallPhysics.Surface;
import com.dierks.homecraft.games.golf.BallPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round's rules on a fake course (spec §12): a putt goes the way the player looks and counts a
 * stroke from a new last spot; water and out of bounds put the ball back on that spot with a
 * penalty stroke; "Reset ball" does the same; the cup ends the hole with its strokes — its height
 * read from the course, so a putt that drops into a sunken hole or a slab counts at once (a
 * hole-in-one stays one); and a hole whose strokes reach par + max over par is picked up —
 * whether the ball stopped short or the last penalty got it there.
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
        r.tee(floor());
        return r;
    }

    /** One par 2 hole facing +x, its cup at (10, {@code cupY}, 0), on {@code g}. */
    private static LiveRound roundOn(double teeX, double teeZ, int cupY, BallPhysics.Blocks g) {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(teeX, GROUND, teeZ, -90f),
                new GolfCourse.Spot(10, cupY, 0), 2, new GolfCourse.Spot(-5, FLOOR, -5),
                new GolfCourse.Spot(30, FLOOR + 3, 5));
        GolfCourse c = new GolfCourse("t", "Test", "games", true, 1, List.of(h));
        LiveRound r = new LiveRound(UUID.randomUUID(), c, new GolfRun(c.pars(), 3), null);
        r.tee(g);
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

    /**
     * The round plays Adventure Golf's pond rule as {@link GolfShot} does (the review of Course
     * Variety): a tap that stops with its centre over the water has fallen in — "Splash!", +1, back
     * on its last spot — so the ball never sits on the water, and "Reset ball" then has nothing to do
     * (it is already on its spot, and the spot is dry). On a hand-built course it stops there, as
     * before.
     */
    /** A par 3 teed at ({@code teeX}, 0.5) facing +x on {@code g}, its cup far beyond the pond, at x 29. */
    private static LiveRound pastThePond(double teeX, BallPhysics.Blocks g) {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(teeX, GROUND, 0.5, -90f),
                new GolfCourse.Spot(29, FLOOR, 0), 3, new GolfCourse.Spot(-5, FLOOR, -3),
                new GolfCourse.Spot(30, FLOOR + 3, 3));
        GolfCourse c = new GolfCourse("t", "Test", "games", true, 1, List.of(h));
        LiveRound r = new LiveRound(UUID.randomUUID(), c, new GolfRun(c.pars(), 3), null);
        r.tee(g);
        return r;
    }

    @Test
    void onAnAdventureCourseABallThatStopsOverWaterHasFallenIn() {
        double teeX = GolfShotTest.tapToThePondsEdge();
        BallPhysics.Blocks adventure = new GolfShotTest.Adventure(GolfShotTest.pondAcross());
        LiveRound r = pastThePond(teeX, adventure);
        r.putt(-90f, 1);
        assertEquals(LiveRound.Result.BACK, rollOut(r, adventure), "it stopped over the water: in it goes");
        assertEquals(BallPhysics.Outcome.WATER, r.outcome, "a splash, so the words say so");
        assertEquals(2, r.run.strokes(), "the putt and the penalty");
        assertEquals(teeX, r.ball.x(), 0.0, "back on its last spot");
        assertEquals(GROUND, r.ball.y(), 0.0, "on the turf, not the water");
        assertTrue(r.atSpot(), "Reset ball has nothing to do: the ball is on its (dry) last spot");
        LiveRound hand = pastThePond(teeX, GolfShotTest.pondAcross());
        hand.putt(-90f, 1);
        assertEquals(LiveRound.Result.STILL, rollOut(hand, GolfShotTest.pondAcross()), "a hand-built course: as"
                + " it always did, it stops on the water's edge");
        assertEquals(10.05, hand.ball.x(), 1e-9, "its centre over the water");
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
    void aPuttThatDropsIntoASunkenHoleIsAHoleInOne() {
        Grid g = BallPhysicsTest.sunken();
        LiveRound r = roundOn(7.0, 0.2, FLOOR - 1, g); // the floor of the hole is the cup; 0.3 off its centre line
        assertEquals(GROUND - 1, r.area.cupY(), 1e-12, "the cup's top was read from the course: the hole's floor");
        r.putt(-90f, 2); // "Putt"
        assertEquals(LiveRound.Result.IN_CUP, rollOut(r, g), "it drops in and counts, with no second tap");
        assertEquals(1, r.last.strokes(), "one stroke");
        assertTrue(r.last.holeInOne(), "a hole-in-one");
        assertEquals(List.of(1), r.run.holesInOne(), "and the round says so, for its reward");
    }

    @Test
    void noPuttEndsAtRestInASunkenHoleUncounted() {
        Grid g = BallPhysicsTest.sunken();
        int in = 0;
        for (int power = 1; power <= 3; power++) {
            for (int i = 0; i <= 66; i++) {
                double teeX = 3.0 + i * 0.1;
                LiveRound r = roundOn(teeX, 0.2, FLOOR - 1, g);
                r.putt(-90f, power);
                LiveRound.Result res = rollOut(r, g);
                boolean down = r.ball.x() >= 10 && r.ball.x() < 11 && r.ball.y() < GROUND - 0.5;
                assertFalse(res == LiveRound.Result.STILL && down, String.format(Locale.ROOT,
                        "power %d from x %.1f came to rest in the hole at (%.3f, %.3f, %.3f) and must be in", power,
                        teeX, r.ball.x(), r.ball.y(), r.ball.z()));
                if (res == LiveRound.Result.IN_CUP) {
                    in++;
                }
            }
        }
        assertTrue(in > 0, "and some of them are in: " + in);
    }

    @Test
    void aSlabCupIsReadAsHalfABlock() {
        Grid g = new Grid().floor(-5, 30, -5, 5, FLOOR, Surface.NORMAL).set(10, FLOOR, 0, 0.5, Surface.NORMAL);
        LiveRound r = roundOn(7.0, 0.5, FLOOR, g);
        assertEquals(GROUND - 0.5, r.area.cupY(), 1e-12, "the cup's top is the slab's, half a block down");
        r.putt(-90f, 2);
        assertEquals(LiveRound.Result.IN_CUP, rollOut(r, g), "a Putt drops into the slab and counts");
        assertTrue(r.ball.y() < GROUND, "down below the green, into it: " + r.ball.y());
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
