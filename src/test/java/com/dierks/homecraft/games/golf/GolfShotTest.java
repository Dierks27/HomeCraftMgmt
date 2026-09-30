package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics.Surface;
import com.dierks.homecraft.games.golf.BallPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One putt played out with no server (GEN-SPEC §4.0, §4.3): Daily Golf plans, verifies and players
 * play with the same putt, so {@link GolfShot} must be {@link LiveRound} exactly.
 *
 * <p>Pinned here: on 500 random putts over random grids (floors of every surface, walls with
 * gaps, slime, slabs, rocks, water, pits, sunken and flush cups, tight bounds), strung together
 * the way a round strings them, GolfShot ends every putt the same way as the round — the same
 * outcome, the same ticks, the same strokes and the ball on exactly the same spot, water and out
 * of bounds putting it back where it was putted from; the direction is the StrictMath one the
 * round now uses; and a line replayed from the tee counts its putts and penalty strokes.
 */
class GolfShotTest {

    private static final int FLOOR = 63;
    private static final double GROUND = 64;

    private record Scene(BallPhysics.Blocks grid, GolfCourse.Hole hole) {
    }

    /**
     * {@code grid} as a course playing Adventure Golf's rules sees it ({@link GolfShot.Rules}: a
     * golf layout of version 3 or later, or a course kept from one).
     */
    record Adventure(BallPhysics.Blocks grid) implements BallPhysics.Blocks, GolfShot.Rules {

        @Override
        public double top(int x, int y, int z, double px, double pz) {
            return grid.top(x, y, z, px, pz);
        }

        @Override
        public Surface surface(int x, int y, int z) {
            return grid.surface(x, y, z);
        }

        @Override
        public boolean adventure() {
            return true;
        }
    }

    /** A flat lane, x -5..30 and z -3..3, with a pond (3 across) from x = 10 to 12 right across it. */
    static Grid pondAcross() {
        Grid g = new Grid().floor(-5, 30, -3, 3, FLOOR, Surface.NORMAL);
        for (int x = 10; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                g.remove(x, FLOOR, z).set(x, FLOOR, z, BallPhysics.Blocks.NONE, Surface.WATER);
            }
        }
        return g;
    }

    /**
     * Where to tap from (power 1, along +x, at z 0.5) so the ball stops with its centre 0.05 past
     * the pond's edge at x = 10, its back edge still on the turf: how far a tap rolls on the flat,
     * taken back from there (the flat plays the same wherever the tap starts).
     */
    static double tapToThePondsEdge() {
        Grid flat = new Grid().floor(-5, 30, -3, 3, FLOOR, Surface.NORMAL);
        BallPhysics.Hole area = BallPhysics.Hole.of(29, FLOOR, 0, -5, FLOOR, -3, 30, FLOOR + 3, 3);
        GolfShot.Result tap = GolfShot.play(flat, area, new BallPhysics.Ball(0.5, GROUND, 0.5), new Putt(-90f, 1));
        return 10.05 - (tap.x() - 0.5);
    }

    private static Scene randomScene(GenRandom r) {
        Grid g = new Grid();
        int minX = -5;
        int maxX = 30;
        int minZ = -6;
        int maxZ = 6;
        Surface[] floors = {Surface.NORMAL, Surface.NORMAL, Surface.NORMAL, Surface.ICE, Surface.SLOW, Surface.SLIME};
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                g.set(x, FLOOR, z, 1.0, r.chance(0.8) ? Surface.NORMAL : floors[r.nextInt(floors.length)]);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z : new int[]{minZ, maxZ}) {
                if (r.chance(0.9)) {
                    g.set(x, FLOOR + 1, z, 1.0, r.chance(0.2) ? Surface.SLIME : Surface.NORMAL);
                }
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            for (int x : new int[]{minX, maxX}) {
                if (r.chance(0.9)) {
                    g.set(x, FLOOR + 1, z, 1.0, r.chance(0.2) ? Surface.SLIME : Surface.NORMAL);
                }
            }
        }
        for (int i = r.nextInt(7); i > 0; i--) {
            g.set(r.nextInt(minX + 1, maxX - 1), FLOOR + 1, r.nextInt(minZ + 1, maxZ - 1), 1.0,
                    r.chance(0.3) ? Surface.SLIME : Surface.NORMAL);
        }
        for (int i = r.nextInt(7); i > 0; i--) {
            g.set(r.nextInt(minX + 1, maxX - 1), FLOOR + 1, r.nextInt(minZ + 1, maxZ - 1), 0.5, Surface.NORMAL);
        }
        for (int i = r.nextInt(4); i > 0; i--) {
            int x = r.nextInt(minX + 1, maxX - 1);
            int z = r.nextInt(minZ + 1, maxZ - 1);
            g.remove(x, FLOOR, z).set(x, FLOOR, z, BallPhysics.Blocks.NONE, Surface.WATER);
        }
        for (int i = r.nextInt(3); i > 0; i--) {
            g.remove(r.nextInt(minX + 1, maxX - 1), FLOOR, r.nextInt(minZ + 1, maxZ - 1));
        }
        int cx = r.nextInt(5, 25);
        int cz = r.nextInt(-3, 3);
        int cupY = FLOOR;
        if (r.nextBoolean()) {
            g.remove(cx, FLOOR, cz).remove(cx, FLOOR + 1, cz).set(cx, FLOOR - 1, cz, 1.0, Surface.NORMAL);
            cupY = FLOOR - 1;
        } else {
            g.remove(cx, FLOOR + 1, cz).set(cx, FLOOR, cz, 1.0, Surface.NORMAL);
        }
        boolean tight = r.chance(0.3);
        GolfCourse.Hole hole = new GolfCourse.Hole(
                new GolfCourse.Tee(r.nextInt(-3, 26) + 0.5, GROUND, r.nextInt(-4, 4) + 0.5, 0f),
                new GolfCourse.Spot(cx, cupY, cz), 6,
                new GolfCourse.Spot(tight ? -3 : minX, FLOOR - 2, tight ? -4 : minZ),
                new GolfCourse.Spot(tight ? 28 : maxX, FLOOR + 4, tight ? 4 : maxZ));
        return new Scene(g, hole);
    }

    @Test
    void golfShotPlaysExactlyAsTheRoundDoesOn500RandomPutts() {
        GenRandom r = new GenRandom(2026);
        int putts = 0;
        int inCup = 0;
        int penalties = 0;
        int scenes = 0;
        while (putts < 500) {
            Scene drawn = randomScene(r);
            scenes++;
            // every other scene is played by Adventure Golf's rules: both ways, GolfShot is the round
            Scene s = scenes % 2 == 0 ? new Scene(new Adventure(drawn.grid()), drawn.hole()) : drawn;
            GolfCourse course = new GolfCourse("t", "Test", "games", true, 1, List.of(s.hole()));
            LiveRound round = new LiveRound(UUID.randomUUID(), course, new GolfRun(course.pars(), 1000), null);
            round.tee(s.grid());
            BallPhysics.settle(round.ball, s.grid());
            round.markSpot();
            BallPhysics.Hole area = GolfShot.area(s.grid(), s.hole());
            BallPhysics.Ball ball = GolfShot.tee(s.grid(), s.hole());
            assertEquals(round.ball.y(), ball.y(), "both tee up on the same spot (scene " + scenes + ")");
            for (int i = 0; i < 6 && putts < 500; i++) {
                Putt putt = new Putt((float) r.nextDouble(-360, 360), r.nextInt(1, 5));
                putts++;
                int strokesBefore = round.run.strokes();
                round.putt(putt.yaw(), putt.power());
                LiveRound.Result live = LiveRound.Result.ROLLING;
                int ticks = 0;
                while (live == LiveRound.Result.ROLLING) {
                    live = round.roll(s.grid());
                    ticks++;
                }
                GolfShot.Result shot = GolfShot.play(s.grid(), area, ball, putt);
                String where = "scene " + scenes + ", putt " + (i + 1) + " " + putt;
                LiveRound.Result expected = switch (shot.outcome()) {
                    case IN_CUP -> LiveRound.Result.IN_CUP;
                    case STOPPED -> LiveRound.Result.STILL;
                    case WATER, OUT -> LiveRound.Result.BACK;
                    case ROLLING -> LiveRound.Result.ROLLING;
                };
                assertEquals(expected, live, "the same ending: " + where);
                if (shot.penalty()) {
                    assertEquals(round.outcome, shot.outcome(), "the same kind of penalty: " + where);
                    penalties++;
                }
                assertEquals(ticks, shot.ticks(), "the same number of ticks: " + where);
                // A holed ball ends the hole: its strokes are in the hole's score, and the run moves on.
                int strokesAfter = live == LiveRound.Result.IN_CUP ? round.last.strokes() : round.run.strokes();
                assertEquals(strokesAfter - strokesBefore, shot.strokes(), "the same strokes: " + where);
                assertEquals(round.ball.x(), ball.x(), 0.0, "the ball on exactly the same x: " + where);
                assertEquals(round.ball.y(), ball.y(), 0.0, "the same y: " + where);
                assertEquals(round.ball.z(), ball.z(), 0.0, "the same z: " + where);
                assertEquals(ball.x(), shot.x(), 0.0, "the result says where the ball is: " + where);
                if (shot.inCup()) {
                    inCup++;
                    break;
                }
            }
        }
        assertTrue(inCup > 0, "some putts dropped (" + inCup + "), so the cup path was compared");
        assertTrue(penalties > 20, "plenty went in the water or out (" + penalties + "), so that path was too");
    }

    /**
     * Adventure Golf's pond rule (the review of Course Variety): the ball's physics holds a ball up
     * by the edge of its footprint, so a tap can stop hanging over a pond's edge — its centre over the
     * water, its back edge on the turf — or, rolled along a wall's lower block, float on the pond
     * itself, where almost every putt then splashes and puts it back on the same floating spot. On a
     * course playing Adventure Golf's rules a ball that comes to rest with its centre over water has
     * fallen in: +1, back where it was putted from. A hand-built course plays exactly as before.
     */
    @Test
    void onAnAdventureCourseABallThatStopsOverWaterHasFallenIn() {
        double teeX = tapToThePondsEdge();
        Grid pond = pondAcross();
        BallPhysics.Hole area = BallPhysics.Hole.of(29, FLOOR, 0, -5, FLOOR, -3, 30, FLOOR + 3, 3);
        GolfShot.Result hand = GolfShot.play(pond, area, new BallPhysics.Ball(teeX, GROUND, 0.5), new Putt(-90f, 1));
        assertEquals(BallPhysics.Outcome.STOPPED, hand.outcome(), "a hand-built course, as it always did: the tap"
                + " stops on the water's edge");
        assertEquals(10.05, hand.x(), 1e-9, "its centre over the pond, held up by its back edge on the turf");
        assertEquals(GROUND, hand.y(), 1e-12, "at the turf's height");
        BallPhysics.Ball ball = new BallPhysics.Ball(teeX, GROUND, 0.5);
        GolfShot.Result adventure = GolfShot.play(new Adventure(pond), area, ball, new Putt(-90f, 1));
        assertEquals(BallPhysics.Outcome.WATER, adventure.outcome(), "on Adventure Golf it has fallen in");
        assertTrue(adventure.penalty() && adventure.strokes() == 2, "the putt and a penalty stroke");
        assertEquals(teeX, ball.x(), 0.0, "back on the spot it was putted from");
        assertEquals(GROUND, ball.y(), 0.0, "on the turf");
        assertFalse(ball.moving(), "still, ready for the next putt");
        assertEquals(hand.ticks(), adventure.ticks(), "it rolled exactly the same way: only its rest is judged");
        GolfShot.Result dry = GolfShot.play(new Adventure(new Grid().floor(-5, 30, -3, 3, FLOOR, Surface.NORMAL)),
                area, new BallPhysics.Ball(teeX, GROUND, 0.5), new Putt(-90f, 1));
        assertEquals(BallPhysics.Outcome.STOPPED, dry.outcome(), "the same tap with no pond just stops");
        assertTrue(GolfShot.adventure(new Adventure(pond)) && !GolfShot.adventure(pond),
                "blocks say which rules they play by; blocks that don't say play the old ones");
    }

    @Test
    void theDirectionIsStrictMathsAndPointsWhereThePlayerLooks() {
        GolfShot.Direction south = GolfShot.direction(0f);
        assertEquals(1.0, south.dz(), 0.0, "yaw 0 is +z");
        assertEquals(0.0, south.dx(), 0.0, "and nothing sideways");
        GolfShot.Direction east = GolfShot.direction(-90f);
        assertEquals(1.0, east.dx(), 0.0, "yaw -90 is +x");
        GolfShot.Direction west = GolfShot.direction(90f);
        assertEquals(-1.0, west.dx(), 0.0, "yaw 90 is -x");
        GenRandom r = new GenRandom(4);
        for (int i = 0; i < 1000; i++) {
            float yaw = (float) r.nextDouble(-720, 720);
            GolfShot.Direction d = GolfShot.direction(yaw);
            double rad = StrictMath.toRadians(yaw);
            assertEquals(-StrictMath.sin(rad), d.dx(), 0.0, "exactly StrictMath's sine for " + yaw);
            assertEquals(StrictMath.cos(rad), d.dz(), 0.0, "exactly StrictMath's cosine for " + yaw);
            assertEquals(1.0, Math.hypot(d.dx(), d.dz()), 1e-12, "one block long");
        }
    }

    @Test
    void aReplayedLineCountsItsPuttsAndPenalties() {
        Grid lane = new Grid().floor(-5, 20, -3, 3, FLOOR, Surface.NORMAL);
        GolfCourse.Hole hole = new GolfCourse.Hole(new GolfCourse.Tee(0.5, GROUND, 0.5, -90f),
                new GolfCourse.Spot(10, FLOOR, 0), 3, new GolfCourse.Spot(-5, FLOOR, -3),
                new GolfCourse.Spot(20, FLOOR + 3, 3));
        GolfShot.Replay two = GolfShot.replay(lane, hole, List.of(new Putt(-90f, 3), new Putt(-90f, 3),
                new Putt(0f, 1)));
        assertTrue(two.holed(), "two chips down the lane drop");
        assertEquals(2, two.putts(), "on the second putt: the third is never played");
        assertEquals(2, two.strokes(), "two strokes");
        GolfShot.Replay out = GolfShot.replay(lane, hole, List.of(new Putt(0f, 5), new Putt(-90f, 3),
                new Putt(-90f, 3)));
        assertTrue(out.holed(), "out of bounds, back on the tee, then the two chips");
        assertEquals(3, out.putts(), "three putts");
        assertEquals(4, out.strokes(), "and a penalty stroke");
        GolfShot.Replay short1 = GolfShot.replay(lane, hole, List.of(new Putt(-90f, 1)));
        assertFalse(short1.holed(), "a tap doesn't reach");
        assertEquals(1, short1.strokes(), "one stroke all the same");
    }
}
