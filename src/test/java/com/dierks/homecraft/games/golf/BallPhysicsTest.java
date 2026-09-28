package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.golf.BallPhysics.Ball;
import com.dierks.homecraft.games.golf.BallPhysics.Outcome;
import com.dierks.homecraft.games.golf.BallPhysics.Surface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golf ball's physics against a fake block grid (spec §12, §14 "Golf physics basics").
 *
 * <p>Pinned here: a putt rolls straight and stops, about ten times its speed away on a normal
 * block; friction by block kind (ice slides, soul sand and honey slow); a wall reflects the speed
 * into it at 0.6 (slime 0.95) and leaves the speed along it, so the ball leaves at the matching
 * angle; slime bounces a dropped ball back up with its sideways speed kept, and pops up a ball
 * rolling onto it; a half-block step is climbed only with the speed for it, a full block always
 * bounces, and steps down are fallen down; water, lava and leaving the bounds end the stroke;
 * the cup takes a slow ball and lets a fast one lip out; and the same putt on the same grid
 * gives the same path every time.
 */
class BallPhysicsTest {

    /** The ball stands on blocks at y 63, whose tops are at 64. */
    private static final int FLOOR = 63;
    private static final double GROUND = 64;

    /** A block grid for tests: full or partial blocks by position, everything else air. */
    static final class Grid implements BallPhysics.Blocks {
        private record Cell(double top, Surface surface) {
        }

        private final Map<List<Integer>, Cell> cells = new HashMap<>();

        Grid set(int x, int y, int z, double top, Surface surface) {
            cells.put(List.of(x, y, z), new Cell(top, surface));
            return this;
        }

        /** Full blocks of {@code surface} over x1..x2, z1..z2 at height y. */
        Grid floor(int x1, int x2, int z1, int z2, int y, Surface surface) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    set(x, y, z, 1.0, surface);
                }
            }
            return this;
        }

        Grid remove(int x, int y, int z) {
            cells.remove(List.of(x, y, z));
            return this;
        }

        @Override
        public double top(int x, int y, int z, double px, double pz) {
            Cell c = cells.get(List.of(x, y, z));
            return c == null ? NONE : c.top();
        }

        @Override
        public Surface surface(int x, int y, int z) {
            Cell c = cells.get(List.of(x, y, z));
            return c == null ? Surface.NORMAL : c.surface();
        }
    }

    /** Wide bounds around the test area; the cup far away. */
    private static final BallPhysics.Hole OPEN = BallPhysics.Hole.of(100, FLOOR, 100, -50, FLOOR - 5, -50, 150,
            FLOOR + 20, 150);

    private static Grid lane(Surface surface) {
        return new Grid().floor(-20, 40, -3, 3, FLOOR, surface);
    }

    /** Tick until the ball does something other than roll, at most {@code max} ticks. */
    private static Outcome run(Ball b, BallPhysics.Blocks w, BallPhysics.Hole h, int max) {
        Outcome o = Outcome.ROLLING;
        for (int i = 0; i < max && o == Outcome.ROLLING; i++) {
            o = BallPhysics.tick(b, w, h);
        }
        return o;
    }

    private static int ticksToStop(Ball b, BallPhysics.Blocks w) {
        int ticks = 0;
        while (BallPhysics.tick(b, w, OPEN) == Outcome.ROLLING) {
            ticks++;
            assertTrue(ticks < 2000, "the ball must stop in the end");
        }
        return ticks;
    }

    // ---- rolling and stopping ----------------------------------------------------------------------

    @Test
    void aPuttRollsStraightAndStopsAboutTenTimesItsSpeedAway() {
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, 0.6);
        int ticks = ticksToStop(b, lane(Surface.NORMAL));
        assertFalse(b.moving(), "at rest");
        assertEquals(0.5, b.z(), 1e-12, "straight along x: no drift sideways");
        assertEquals(GROUND, b.y(), 1e-12, "still on the ground");
        double travelled = b.x() - 0.5;
        assertTrue(travelled > 5.5 && travelled < 6.0, "0.6 a tick at 0.9 kept per tick rolls just under 6 blocks: "
                + travelled);
        assertTrue(ticks < 60, "and stops within three seconds: " + ticks);
    }

    @Test
    void aStillBallStaysStill() {
        Ball b = new Ball(0.5, GROUND, 0.5);
        assertEquals(Outcome.STOPPED, BallPhysics.tick(b, lane(Surface.NORMAL), OPEN), "nothing to do");
        assertEquals(0.5, b.x(), 1e-12, "it hasn't moved");
    }

    @Test
    void theBallStopsBelowOneHundredthOfABlockATick() {
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, 0.0105);
        assertEquals(Outcome.STOPPED, BallPhysics.tick(b, lane(Surface.NORMAL), OPEN),
                "0.0105 × 0.9 is under 0.01: it stops after this tick");
        assertEquals(0.0, b.speed(), 0.0, "and has no speed left");
    }

    @Test
    void aBallTeedInTheAirSettlesOnTheGroundBelow() {
        Ball b = new Ball(0.5, GROUND + 0.3, 0.5);
        BallPhysics.settle(b, lane(Surface.NORMAL));
        assertEquals(GROUND, b.y(), 1e-12, "down onto the block under it");
        assertFalse(b.moving(), "and still");
        Ball nowhere = new Ball(100.5, GROUND + 0.3, 100.5);
        BallPhysics.settle(nowhere, lane(Surface.NORMAL));
        assertEquals(GROUND + 0.3, nowhere.y(), 1e-12, "with nothing below it stays where it was");
    }

    @Test
    void theFiveClubsGetFasterFromTapToDrive() {
        assertEquals(5, BallPhysics.clubs(), "five clubs");
        for (int p = 2; p <= 5; p++) {
            assertTrue(BallPhysics.speed(p) > BallPhysics.speed(p - 1), "power " + p + " is faster than " + (p - 1));
        }
        assertEquals(BallPhysics.speed(1), BallPhysics.speed(0), 0.0, "below 1 is Tap");
        assertEquals(BallPhysics.speed(5), BallPhysics.speed(9), 0.0, "above 5 is Drive");
    }

    // ---- friction ------------------------------------------------------------------------------------

    @Test
    void frictionComesFromTheBlockUnderTheBall() {
        for (Surface s : List.of(Surface.NORMAL, Surface.ICE, Surface.SLOW)) {
            Ball b = new Ball(0.5, GROUND, 0.5);
            b.putt(1, 0, 0.5);
            BallPhysics.tick(b, lane(s), OPEN);
            double kept = switch (s) {
                case ICE -> 0.98;
                case SLOW -> 0.80;
                default -> 0.90;
            };
            assertEquals(0.5 * kept, b.speed(), 1e-12, s + " keeps " + kept + " of the speed per tick");
        }
    }

    @Test
    void iceSlidesFurtherAndSoulSandStopsSooner() {
        double[] far = new double[3];
        List<Surface> kinds = List.of(Surface.SLOW, Surface.NORMAL, Surface.ICE);
        for (int i = 0; i < 3; i++) {
            Grid g = new Grid().floor(-2, 200, -3, 3, FLOOR, kinds.get(i));
            Ball b = new Ball(0.5, GROUND, 0.5);
            b.putt(1, 0, 0.5);
            BallPhysics.Hole wide = BallPhysics.Hole.of(500, FLOOR, 500, -10, FLOOR - 5, -10, 600, FLOOR + 5, 600);
            assertEquals(Outcome.STOPPED, run(b, g, wide, 5000), kinds.get(i) + " stops in the end");
            far[i] = b.x();
        }
        assertTrue(far[0] < far[1], "soul sand stops the ball sooner than a normal block");
        assertTrue(far[1] < far[2], "ice lets it slide further");
        assertTrue(far[2] > 3 * far[1], "much further: " + far[2] + " vs " + far[1]);
    }

    // ---- walls ---------------------------------------------------------------------------------------

    @Test
    void aWallReflectsTheSpeedIntoItAndKeepsTheSpeedAlongIt() {
        Grid g = lane(Surface.NORMAL);
        for (int z = -3; z <= 3; z++) {
            g.set(4, FLOOR + 1, z, 1.0, Surface.NORMAL); // a wall one block high across the lane at x = 4
        }
        Ball b = new Ball(3.7, GROUND, -1.0);
        b.putt(1, 1, 0.4 * Math.sqrt(2)); // 45 degrees: 0.4 along x and 0.4 along z
        double vx = b.vx();
        double vz = b.vz();
        BallPhysics.tick(b, g, OPEN);
        assertEquals(-vx * 0.6 * 0.9, b.vx(), 1e-12, "into the wall: back at 0.6, then rolling friction");
        assertEquals(vz * 0.9, b.vz(), 1e-12, "along the wall: untouched but for friction");
        assertTrue(b.x() < 4 - BallPhysics.RADIUS + 1e-9, "the ball never enters the wall");
        double in = Math.toDegrees(Math.atan2(vz, vx));
        double out = Math.toDegrees(Math.atan2(b.vz(), -b.vx()));
        assertTrue(out > in, "it leaves at a flatter angle to the wall than it came in (0.6 back): "
                + in + " -> " + out);
        assertEquals(Math.toDegrees(Math.atan(1 / 0.6)), out, 1e-9, "exactly atan(1 / 0.6) from the wall's normal");
    }

    @Test
    void aSlimeWallGivesBackNinetyFivePercent() {
        Grid g = lane(Surface.NORMAL);
        for (int z = -3; z <= 3; z++) {
            g.set(4, FLOOR + 1, z, 1.0, Surface.SLIME);
        }
        Ball b = new Ball(3.5, GROUND, 0.5);
        b.putt(1, 0, 0.5);
        BallPhysics.tick(b, g, OPEN);
        assertEquals(-0.5 * 0.95 * 0.9, b.vx(), 1e-12, "slime sends 0.95 of it back");
    }

    // ---- steps -----------------------------------------------------------------------------------------

    @Test
    void aHalfBlockStepIsClimbedWhenTheBallIsFastEnough() {
        Grid g = lane(Surface.NORMAL);
        for (int x = 4; x <= 40; x++) {
            for (int z = -3; z <= 3; z++) {
                g.set(x, FLOOR + 1, z, 0.5, Surface.NORMAL); // slabs from x = 4 on
            }
        }
        Ball fast = new Ball(0.5, GROUND, 0.5);
        fast.putt(1, 0, 0.6);
        run(fast, g, OPEN, 500);
        assertEquals(GROUND + 0.5, fast.y(), 1e-12, "a fast ball ends up on the slabs");
        assertTrue(fast.x() > 4, "past the step");

        Ball slow = new Ball(3.0, GROUND, 0.5);
        slow.putt(1, 0, 0.2);
        run(slow, g, OPEN, 500);
        assertEquals(GROUND, slow.y(), 1e-12, "a slow one (under 0.2 at the step) can't climb half a block");
        assertTrue(slow.x() < 4, "it bounced back off the step");
    }

    @Test
    void climbingCostsSpeed() {
        Grid g = lane(Surface.NORMAL);
        for (int z = -3; z <= 3; z++) {
            g.set(4, FLOOR + 1, z, 0.5, Surface.NORMAL);
        }
        Ball b = new Ball(3.8, GROUND, 0.5);
        b.putt(1, 0, 0.5);
        BallPhysics.tick(b, g, OPEN);
        double kept = Math.sqrt(0.25 - 2 * BallPhysics.GRAVITY * 0.5);
        assertEquals(kept * 0.9, b.vx(), 1e-12, "a climb of h leaves sqrt(v² - 2gh), then friction");
        assertEquals(GROUND + 0.5, b.y(), 1e-12, "on the slab");
    }

    @Test
    void aFullBlockAlwaysBounces() {
        Grid g = lane(Surface.NORMAL);
        for (int z = -3; z <= 3; z++) {
            g.set(4, FLOOR + 1, z, 1.0, Surface.NORMAL);
        }
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, BallPhysics.speed(5));
        run(b, g, OPEN, 500);
        assertEquals(GROUND, b.y(), 1e-12, "even a Drive can't climb a full block");
        assertTrue(b.x() < 4, "it came back off it");
    }

    @Test
    void aCarpetIsRolledOverAtAnySpeed() {
        Grid g = lane(Surface.NORMAL);
        for (int x = 2; x <= 40; x++) {
            g.set(x, FLOOR + 1, 0, 1.0 / 16, Surface.NORMAL);
        }
        Ball b = new Ball(1.7, GROUND, 0.5);
        b.putt(1, 0, 0.05);
        run(b, g, OPEN, 500);
        assertEquals(GROUND + 1.0 / 16, b.y(), 1e-12, "a slow ball still rolls onto a carpet");
    }

    @Test
    void theBallFallsDownSteps() {
        Grid g = new Grid().floor(-2, 3, -3, 3, FLOOR, Surface.NORMAL).floor(4, 40, -3, 3, FLOOR - 1, Surface.NORMAL);
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, 0.6);
        assertEquals(Outcome.STOPPED, run(b, g, OPEN, 500), "it lands and stops");
        assertEquals(GROUND - 1, b.y(), 1e-12, "on the lower level");
        assertTrue(b.x() > 4, "past the edge");
    }

    // ---- slime -------------------------------------------------------------------------------------------

    @Test
    void slimeBouncesADroppedBallBackUpAndKeepsItsSpeed() {
        Grid g = new Grid().floor(-10, 40, -10, 10, FLOOR, Surface.SLIME);
        Ball b = new Ball(0.5, GROUND + 3, 0.5);
        b.putt(1, 0, 0.1);
        double before = b.speed();
        boolean bounced = false;
        for (int i = 0; i < 200 && !bounced; i++) {
            double wasY = b.y();
            double wasVy = b.vy();
            before = b.speed();
            BallPhysics.tick(b, g, OPEN);
            if (wasVy < -BallPhysics.MIN_BOUNCE && b.vy() > 0) {
                bounced = true;
                assertTrue(b.y() >= GROUND - 1e-9 && wasY > GROUND, "it met the slime on the way down");
            }
        }
        assertTrue(bounced, "the slime sent it back up");
        assertEquals(before * BallPhysics.AIR_DRAG, b.speed(), 1e-12,
                "the bounce keeps its sideways speed (only the air's drag)");
    }

    @Test
    void aDroppedBallDoesNotBounceOnANormalBlock() {
        Grid g = lane(Surface.NORMAL);
        Ball b = new Ball(0.5, GROUND + 3, 0.5);
        b.putt(1, 0, 0.1);
        double lowest = Double.MAX_VALUE;
        boolean rose = false;
        for (int i = 0; i < 200 && b.moving(); i++) {
            double wasY = b.y();
            BallPhysics.tick(b, g, OPEN);
            lowest = Math.min(lowest, b.y());
            if (b.y() > wasY + 1e-12) {
                rose = true;
            }
        }
        assertFalse(rose, "it never goes up again");
        assertEquals(GROUND, lowest, 1e-12, "it lands on the block");
    }

    @Test
    void rollingOntoSlimePopsTheBallUp() {
        Grid g = new Grid().floor(-2, 3, -3, 3, FLOOR, Surface.NORMAL).floor(4, 40, -3, 3, FLOOR, Surface.SLIME);
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, 0.6);
        double highest = GROUND;
        for (int i = 0; i < 300 && b.moving(); i++) {
            BallPhysics.tick(b, g, OPEN);
            highest = Math.max(highest, b.y());
        }
        assertTrue(highest > GROUND + 0.2, "it hopped when it reached the slime: " + highest);
        assertFalse(b.moving(), "and settled in the end");
    }

    // ---- water, lava, bounds ---------------------------------------------------------------------------

    @Test
    void waterOrLavaEndsTheStroke() {
        for (Surface wet : List.of(Surface.WATER, Surface.LAVA)) {
            Grid g = lane(Surface.NORMAL);
            for (int x = 4; x <= 6; x++) {
                for (int z = -3; z <= 3; z++) {
                    g.remove(x, FLOOR, z).set(x, FLOOR, z, BallPhysics.Blocks.NONE, wet); // a pool three wide
                }
            }
            Ball b = new Ball(0.5, GROUND, 0.5);
            b.putt(1, 0, 0.6);
            assertEquals(Outcome.WATER, run(b, g, OPEN, 500), wet + " sends the ball back");
            assertFalse(b.moving(), "and leaves it still for the game to put back");
        }
    }

    @Test
    void leavingTheBoundsEndsTheStroke() {
        BallPhysics.Hole small = BallPhysics.Hole.of(20, FLOOR, 20, 0, FLOOR, -3, 5, FLOOR + 1, 3);
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, BallPhysics.speed(5));
        assertEquals(Outcome.OUT, run(b, lane(Surface.NORMAL), small, 500), "past x = 6 it's out");
        assertTrue(b.x() >= 6, "it was stopped at the line: " + b.x());
    }

    @Test
    void fallingBelowTheHoleIsOutOfBounds() {
        Grid g = new Grid().floor(-2, 3, -3, 3, FLOOR, Surface.NORMAL);
        BallPhysics.Hole h = BallPhysics.Hole.of(20, FLOOR, 20, -2, FLOOR, -3, 40, FLOOR + 1, 3);
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, 0.6);
        assertEquals(Outcome.OUT, run(b, g, h, 500), "off the edge into nothing: out, well before the void");
    }

    // ---- the cup ------------------------------------------------------------------------------------------

    @Test
    void aSlowBallDropsIntoTheCup() {
        BallPhysics.Hole h = BallPhysics.Hole.of(6, FLOOR, 0, -2, FLOOR, -3, 40, FLOOR + 2, 3);
        Ball b = new Ball(3.5, GROUND, 0.5);
        b.putt(1, 0, BallPhysics.speed(2));
        assertEquals(Outcome.IN_CUP, run(b, lane(Surface.NORMAL), h, 500), "rolling over the cup slowly: in");
    }

    @Test
    void aFastBallLipsOutAndKeepsGoing() {
        BallPhysics.Hole h = BallPhysics.Hole.of(6, FLOOR, 0, -2, FLOOR, -3, 40, FLOOR + 2, 3);
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(1, 0, BallPhysics.speed(5));
        Outcome o = run(b, lane(Surface.NORMAL), h, 500);
        assertNotEquals(Outcome.IN_CUP, o, "faster than CUP_SPEED it rolls straight over the cup");
        assertTrue(b.x() > 7, "and on past it: " + b.x());
    }

    @Test
    void aSlowBallThatMissesTheCupStaysOut() {
        BallPhysics.Hole h = BallPhysics.Hole.of(6, FLOOR, 0, -2, FLOOR, -3, 40, FLOOR + 2, 3);
        Ball b = new Ball(3.5, GROUND, 0.85); // 0.35 to the side of the cup's centre line
        b.putt(1, 0, BallPhysics.speed(2));
        assertEquals(Outcome.STOPPED, run(b, lane(Surface.NORMAL), h, 500), "0.35 off the centre is a miss");
    }

    @Test
    void theCupTakesABallThatStopsRightAtIt() {
        BallPhysics.Hole h = BallPhysics.Hole.of(6, FLOOR, 0, -2, FLOOR, -3, 40, FLOOR + 2, 3);
        Ball b = new Ball(6.3, GROUND, 0.5);
        b.putt(1, 0, 0.02);
        assertEquals(Outcome.IN_CUP, run(b, lane(Surface.NORMAL), h, 500), "trickling to the edge of the cup is in");
    }

    @Test
    void theSegmentDistanceIsMeasuredToTheClosestPoint() {
        assertEquals(1.0, BallPhysics.distanceToSegment(0, 1, 0, -1, 0, 0, 1, 0, 0), 1e-12, "above the middle");
        assertEquals(1.0, BallPhysics.distanceToSegment(2, 0, 0, -1, 0, 0, 1, 0, 0), 1e-12, "past the end");
        assertEquals(0.5, BallPhysics.distanceToSegment(0, 0.5, 0, 0, 0, 0, 0, 0, 0), 1e-12, "a point segment");
    }

    // ---- determinism --------------------------------------------------------------------------------------

    @Test
    void theSamePuttOnTheSameGridTakesTheSamePath() {
        Grid g = new Grid().floor(-5, 30, -8, 8, FLOOR, Surface.NORMAL).floor(6, 12, -2, 2, FLOOR, Surface.ICE)
                .floor(13, 16, -8, 8, FLOOR, Surface.SLIME);
        for (int z = -8; z <= 8; z++) {
            g.set(20, FLOOR + 1, z, 1.0, Surface.NORMAL);
        }
        BallPhysics.Hole h = BallPhysics.Hole.of(25, FLOOR, 5, -5, FLOOR - 1, -8, 30, FLOOR + 5, 8);
        List<Double> first = path(g, h);
        List<Double> second = path(g, h);
        assertEquals(first, second, "every position, every tick, exactly the same");
        assertTrue(first.size() > 30, "a path worth comparing");
    }

    private static List<Double> path(BallPhysics.Blocks g, BallPhysics.Hole h) {
        Ball b = new Ball(0.5, GROUND, 0.5);
        b.putt(0.9, 0.2, BallPhysics.speed(5));
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < 600 && b.moving(); i++) {
            BallPhysics.tick(b, g, h);
            out.add(b.x());
            out.add(b.y());
            out.add(b.z());
        }
        return out;
    }
}
