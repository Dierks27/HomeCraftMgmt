package com.dierks.homecraft.games.gen.rings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The elytra port every Sky Rings layout is proven with (GEN-SPEC §4.2): a level glide comes out
 * at vanilla's 10:1, a rocket climbs and carries as it does in game, and the autopilot flies a
 * ring below its glide line but not one above it.
 */
class ElytraSimTest {

    @Test
    void aLevelGlideIsTenToOne() {
        assertEquals(10, ElytraSim.glideRatio(0), 0.3, "vanilla's best glide, about 10:1 at a level look");
        assertTrue(ElytraSim.glideRatio(5) > 9.7, "a slight nose-down glides about as far");
        assertTrue(ElytraSim.glideRatio(30) < ElytraSim.glideRatio(10), "a steeper look comes down faster");
        assertTrue(ElytraSim.glideRatio(45) < 5, "a real dive drops fast");
    }

    @Test
    void aRocketClimbsWhileLookingUp() {
        double[] l = ElytraSim.look(180, -30);
        ElytraSim.Flyer f = new ElytraSim.Flyer(0, 0, 0, 0, 0, -1);
        for (int t = 0; t < 60; t++) {
            if (t % ElytraSim.ROCKET_TICKS == 0) {
                f.rocket = ElytraSim.ROCKET_TICKS;
            }
            ElytraSim.glide(f, l[0], l[1], l[2]);
        }
        assertEquals(0.93, f.vy, 0.05, "at 30 degrees up, rockets climb about 0.93 blocks a tick");
        assertTrue(f.y > 50, "three rockets lift more than 50 blocks: " + f.y);
        assertEquals(1.39, f.horizontalSpeed(), 0.05, "while still going forward at about 1.4 a tick");
    }

    @Test
    void aRocketCarriesAStandingPlayerLevel() {
        double[] l = ElytraSim.look(180, 0);
        ElytraSim.Flyer f = new ElytraSim.Flyer(0, 0, 0, 0, 0, 0);
        f.rocket = ElytraSim.ROCKET_TICKS;
        for (int t = 0; t < ElytraSim.ROCKET_TICKS; t++) {
            ElytraSim.glide(f, l[0], l[1], l[2]);
        }
        assertTrue(-f.z > 30, "one rocket from a standstill carries more than 30 blocks: " + -f.z);
        assertTrue(f.y > -1, "losing less than a block of height: " + f.y);
        assertTrue(f.speed() > 1.6, "and leaves the player at glide speed: " + f.speed());
        assertEquals(0, f.rocket, "a rocket burns out");
    }

    @Test
    void theLookVectorFollowsMinecraftsAngles() {
        double[] south = ElytraSim.look(0, 0);
        assertEquals(1, south[2], 1e-12, "yaw 0 looks south (+z)");
        double[] west = ElytraSim.look(90, 0);
        assertEquals(-1, west[0], 1e-12, "yaw 90 looks west (-x)");
        double[] down = ElytraSim.look(0, 90);
        assertEquals(-1, down[1], 1e-12, "pitch 90 looks straight down");
    }

    @Test
    void theAutopilotFliesRingsBelowItsGlideLine() {
        // three rings 25 apart, each 5 lower: 1 in 5, twice as steep as the glide
        List<ElytraSim.Target> rings = List.of(
                new ElytraSim.Target(0.5, -4.5, -24.5, 3.5, 0, -1, false),
                new ElytraSim.Target(0.5, -9.5, -49.5, 3.5, 0, -1, false),
                new ElytraSim.Target(0.5, -14.5, -74.5, 3.5, 0, -1, true));
        for (double speed : new double[]{0.8, 1.2, 1.6}) {
            ElytraSim.Flyer f = new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, -speed);
            ElytraSim.Result r = ElytraSim.fly(f, rings, 0, null);
            assertTrue(r.passed(), "entering at " + speed + ": " + r);
            assertEquals(3, r.reached(), "every ring");
        }
    }

    @Test
    void aStandingStartNeedsItsRocketOnAShallowLeg() {
        // one ring 30 ahead and 4 down: shallower than the glide from a standstill manages
        List<ElytraSim.Target> ring = List.of(new ElytraSim.Target(0.5, -3.5, -29.5, 3.5, 0, -1, true));
        ElytraSim.Result without = ElytraSim.fly(new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, 0), ring, 0, null);
        assertFalse(without.passed(), "from a standstill with no rocket it falls short: " + without);
        ElytraSim.Pilot pilot = new ElytraSim.Pilot(new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, 0), 1);
        ElytraSim.Result with = pilot.fly(ring, null);
        assertTrue(with.passed(), "one rocket fired when it falls short makes it: " + with);
        assertEquals(0, pilot.rockets(), "and the rocket is spent");
    }

    @Test
    void aRingAboveTheGlideLineCantBeReached() {
        // from a standstill there is no speed to trade for height
        List<ElytraSim.Target> high = List.of(new ElytraSim.Target(0.5, 3.5, -39.5, 3.5, 0, -1, true));
        ElytraSim.Result r = ElytraSim.fly(new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, 0), high, 0, null);
        assertFalse(r.passed(), "a ring above can't be glided to: " + r);
        // with speed some climb is possible (speed turns into height), but not twenty-five blocks
        List<ElytraSim.Target> higher = List.of(new ElytraSim.Target(0.5, 25.5, -39.5, 3.5, 0, -1, true));
        assertFalse(ElytraSim.fly(new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, -1.6), higher, 0, null).passed(),
                "twenty-five blocks up is out of reach even at glide speed");
        assertTrue(r.why() != null && !r.why().isEmpty(), "and the flight says why");
    }

    @Test
    void aFlightThatTouchesABlockFails() {
        List<ElytraSim.Target> ring = List.of(new ElytraSim.Target(0.5, -4.5, -24.5, 3.5, 0, -1, true));
        // a wall across the way at z = -10
        ElytraSim.Solid wall = (x, y, z) -> z == -10;
        ElytraSim.Result r = ElytraSim.fly(new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, -1.2), ring, 0, wall);
        assertFalse(r.passed(), "a flight into a wall fails");
        assertTrue(r.why().startsWith("touched a block"), "and says it touched a block: " + r.why());
    }

    @Test
    void aCopiedPilotFliesOnWithoutTouchingTheOriginal() {
        ElytraSim.Pilot a = new ElytraSim.Pilot(new ElytraSim.Flyer(0.5, 0.5, 0.5, 0, 0, -1.2), 1);
        ElytraSim.Pilot b = a.copy();
        b.fly(List.of(new ElytraSim.Target(0.5, -4.5, -24.5, 3.5, 0, -1, false)), null);
        assertEquals(0.5, a.flyer.z, 0.0, "the original stays where it was");
        assertTrue(b.flyer.z < -20, "the copy flew on");
    }

    @Test
    void theSphereTestMatchesTheCheckpoints() {
        assertEquals(0.25, ElytraSim.firstHit(-2, 0, 0, 2, 0, 0, 0, 0, 0, 1, 0), 1e-12,
                "a move through a sphere first touches it where it enters, a quarter of the way along");
        assertTrue(Double.isNaN(ElytraSim.firstHit(-2, 3, 0, 2, 3, 0, 0, 0, 0, 1, 0)), "a move past it misses");
        assertEquals(0.0, ElytraSim.firstHit(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0), 0.0, "standing inside counts");
    }

    @Test
    void aFastMoveThatClipsACornerBetweenTwoTickEndsTouchesIt() {
        ElytraSim.Solid block = (x, y, z) -> x == 0 && y == 0 && z == 0;
        assertFalse(ElytraSim.touches(-0.5, 0.2, 0.5, block), "the hitbox at the start of the tick is clear");
        assertFalse(ElytraSim.touches(0.5, 0.2, -0.5, block), "and at its end, 1.41 blocks on");
        assertTrue(ElytraSim.touchesAlong(-0.5, 0.2, 0.5, new ElytraSim.Flyer(0.5, 0.2, -0.5, 0, 0, 0), block),
                "but halfway the move cuts across the block's corner: a touch");
        assertTrue(ElytraSim.touches(-0.35, 0.2, 0.5, block),
                "and passing 0.05 from a face is a graze, which counts as a touch too");
        assertFalse(ElytraSim.touches(-0.45, 0.2, 0.5, block), "while 0.15 away is clear");
    }
}
