package com.dierks.homecraft.games.gen.dropper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's physics, pinned to vanilla (EVENTS-DROPPER-SPEC §B.1.4): every value of the spec's
 * table, ticks exactly and blocks to ±0.02, and the constants themselves, so nobody "tunes" the
 * proof away from the game it proves things about. One row of the table is corrected: the spec's
 * 2.19-block coast assumed a walk-off leaves the ledge with air speed, and vanilla's two ledge ticks
 * leave it with ground speed.
 */
class DropSimTest {

    private static final double BLOCKS = 0.02;

    // ---- the constants ----------------------------------------------------------------------------

    @Test
    void theConstantsAreVanillasFloats() {
        assertEquals(0.08, DropSim.GRAVITY, 1e-12, "gravity is 0.08 a tick");
        assertEquals(0.98F, DropSim.VERTICAL_DRAG, 0, "vertical drag is vanilla's 0.98F, promoted as the game does");
        assertEquals(0.91F, DropSim.AIR_DRAG, 0, "air drag is vanilla's 0.91F");
        assertEquals(0.42F, DropSim.JUMP_POWER, 0, "a jump starts at vanilla's 0.42F");
        assertEquals(0.6F * 0.91F, DropSim.GROUND_DRAG, 0,
                "a tick begun on the ground keeps f x 0.91 of the speed, the float product, with the ledge's f = 0.6");
        assertEquals(0.6, DropSim.WIDTH, 1e-12, "the hitbox is 0.6 wide");
        assertEquals(1.8, DropSim.HEIGHT, 1e-12, "and 1.8 tall");
    }

    @Test
    void walkingAddsNotTwoHundredthsButVanillasPointZeroOneNineSix() {
        assertEquals(0.0196, DropSim.WALK_ACCEL, 1e-6,
                "a = 0.02 x 0.98: the input is scaled by 0.98 before the flying speed (ROBUST's 0.02 was 2% too much)");
        assertEquals(0.02548, DropSim.SPRINT_ACCEL, 1e-6, "sprinting would be 0.026 x 0.98, which no pilot uses");
        assertEquals(0.098, DropSim.GROUND_ACCEL, 1e-6,
                "on the ground it is the movement speed 0.1 x 0.98 (0.216 / f^3 is 1 at friction 0.6)");
    }

    // ---- the table ----------------------------------------------------------------------------------

    @Test
    void theTopSidewaysAirSpeedIsPointTwoOneEightWalkingAndPointTwoEightThreeSprinting() {
        assertEquals(0.218, DropSim.topSpeed(false), BLOCKS, "walking: 0.0196 / (1 - 0.91)");
        assertEquals(0.283, DropSim.topSpeed(true), BLOCKS, "sprinting: 0.02548 / (1 - 0.91)");
        assertEquals(DropSim.TOP_WALK, DropSim.topSpeed(false), 1e-12, "the constant and the method agree");
    }

    @Test
    void stoppingFromTopWalkingSpeedTakesEightTicksAndPointFiveNineBlocks() {
        double[] stop = DropSim.stop();
        assertEquals(8, (int) stop[0], "full reverse input turns top walking speed round in 8 ticks");
        assertEquals(0.59, stop[1], BLOCKS, "moving 0.59 blocks on the way");
    }

    @Test
    void aWalkOffLetGoAtTheEdgeCoastsOnePointTwoEightBlocksNotTheSpecsTwoPointOneNine() {
        assertEquals(1.28, DropSim.coast(), BLOCKS,
                "the 0.118 the ledge leaves it with, kept 0.91 a tick until the tiny-speed cut: §B.1.4's 2.19 is the"
                        + " coast of a body that left with the 0.198 of one already in the air, which no walker has");
    }

    @Test
    void walkOffFallsOfThirtyTwoFortyAndFortyEightTakeThirtyTwoThirtySevenAndFortyTicks() {
        assertEquals(32, DropSim.fallTicks(32), "Easy's 32-block drop, walking off");
        assertEquals(37, DropSim.fallTicks(40), "Medium's 40");
        assertEquals(40, DropSim.fallTicks(48), "Hard's 48");
    }

    @Test
    void jumpOffFallsTakeThirtyEightFortyTwoAndFortySixTicks() {
        assertEquals(38, DropSim.fallTicks(32, true), "a jump-off rises first: 32 blocks take 38 ticks");
        assertEquals(42, DropSim.fallTicks(40, true), "40 take 42");
        assertEquals(46, DropSim.fallTicks(48, true), "48 take 46");
    }

    @Test
    void theSpeedAtTheBottomIsOnePointEightTwoTwoPointZeroThreeAndTwoPointOneFour() {
        assertEquals(1.82, DropSim.speedAt(32), BLOCKS, "blocks a tick at Easy's water");
        assertEquals(2.03, DropSim.speedAt(40), BLOCKS, "at Medium's");
        assertEquals(2.14, DropSim.speedAt(48), BLOCKS, "at Hard's");
    }

    @Test
    void fromRestAPlayerReachesTwoPointThreeToTwoPointFiveBlocksBeforeTheFirstLayer() {
        assertEquals(19, DropSim.fallTicks(12), "the feet reach a layer 12 down in 19 ticks");
        assertEquals(20, DropSim.fallTicks(13), "and one 13 down in 20");
        assertEquals(2.3, DropSim.reachFromRest(19), BLOCKS, "19 ticks of full input from rest: 2.3 blocks");
        assertEquals(2.5, DropSim.reachFromRest(20), BLOCKS, "20 ticks: 2.5 blocks");
    }

    @Test
    void betweenLaterLayersAPlayerReachesOnlyAQuarterToHalfABlock() {
        assertEquals(0.26, DropSim.reachFromRest(5), BLOCKS, "5 ticks between layers: 0.26 blocks");
        assertEquals(0.46, DropSim.reachFromRest(7), BLOCKS, "7 ticks: 0.46 blocks");
        assertEquals(0.06, DropSim.reachFromRest(5 - 3), BLOCKS, "5 ticks with a 3-tick reaction delay: 0.06");
        assertEquals(0.18, DropSim.reachFromRest(7 - 3), BLOCKS, "7 ticks with the delay: 0.18");
    }

    @Test
    void aJumpFromTheLedgePeaksAtOnePointTwoFiveSoFourBlocksOfWallHoldAnyone() {
        assertEquals(1.25, DropSim.jumpPeak(), BLOCKS, "a jump lifts the feet 1.25 blocks");
        assertTrue(DropSim.jumpPeak() + DropSim.HEIGHT < DropperGeometry.WALL_ABOVE,
                "even the head at the jump's peak stays under the wall tops, 4 above the ledge");
    }

    // ---- the tick itself ----------------------------------------------------------------------------

    @Test
    void aWalkOffDropsNothingOnItsFirstTickAndVanillasPointZeroSevenEightFourOnItsSecond() {
        DropSim.Body b = DropSim.walkOff(0, 0, 0, 1, 0);
        DropSim.Body t1 = b.tick(0, 0);
        assertEquals(0, t1.y(), 1e-12, "a walk-off starts with no vertical speed");
        DropSim.Body t2 = t1.tick(0, 0);
        assertEquals(-0.08 * (double) 0.98F, t2.y(), 1e-15,
                "then (0 - 0.08) x 0.98F, the float promoted as vanilla does");
        assertEquals(-0.0784, t2.y(), 1e-8, "which is 0.0784");
    }

    @Test
    void aTickPushesThenMovesThenDrags() {
        DropSim.Body b = new DropSim.Body(0, 10, 0, 0.1, 0, 0);
        DropSim.Body t = b.tick(1, 0);
        assertEquals(0.1 + DropSim.WALK_ACCEL, t.x(), 1e-12, "the input is added before the move");
        assertEquals((0.1 + DropSim.WALK_ACCEL) * DropSim.AIR_DRAG, t.vx(), 1e-12, "and the drag comes after it");
    }

    @Test
    void anInputLongerThanOneIsShortenedToOne() {
        assertArrayEquals(new double[]{Math.sqrt(0.5), Math.sqrt(0.5)}, DropSim.input(1, 1), 1e-12,
                "a diagonal of two keys is normalised, as vanilla does: no faster diagonal");
        assertArrayEquals(new double[]{0.5, 0}, DropSim.input(0.5, 0), 0, "a shorter input is kept as it is");
    }

    @Test
    void aTinySpeedIsZeroedAtTheStartOfATick() {
        DropSim.Body slow = new DropSim.Body(0, 0, 0, 0.002, 0.002, 0.001);
        DropSim.Body s = slow.settled();
        assertEquals(0, s.vx(), 0, "a sideways speed under 0.003 is zeroed");
        assertEquals(0, s.vz(), 0, "along both axes together");
        assertEquals(0, s.vy(), 0, "and so is a vertical one");
        DropSim.Body fine = new DropSim.Body(0, 0, 0, 0.004, 0, 0);
        assertEquals(fine, fine.settled(), "a speed over the cut is left alone");
    }

    // ---- the ledge ticks -----------------------------------------------------------------------------

    @Test
    void aWalkingPlayerMovesPointTwoOneSixATickAndCarriesPointOneOneEightIntoTheNext() {
        assertEquals(4.317, DropSim.GROUND_WALK * 20, 0.001, "vanilla's walking speed, 4.317 blocks a second");
        assertEquals(0.216, DropSim.GROUND_WALK, BLOCKS, "0.098 / (1 - 0.546): a step of 0.216 a tick");
        assertEquals(0.118, DropSim.WALK_OFF_SPEED, 0.001, "and 0.216 x 0.546 = 0.118 carried into each tick");
        assertEquals(DropSim.GROUND_WALK * DropSim.GROUND_DRAG, DropSim.WALK_OFF_SPEED, 1e-12,
                "the constant is the step after the ground's drag");
    }

    @Test
    void aWalkOffIsTwoGroundTicksTheLastOverTheLedgeAndTheEdgeTickThenAir() {
        DropSim.Body b = DropSim.walkOff(0, 0, 0, 1, 0);
        assertEquals(DropSim.WALK_OFF_LEDGE_TICKS, b.ground(), "a walk-off has two ledge ticks to come");
        assertEquals(DropSim.WALK_OFF_SPEED, b.vx(), 0, "carrying a walker's 0.118 into the first");
        assertEquals(0, b.vy(), 0, "with no vertical speed: the ledge holds it up that tick");
        DropSim.Body t1 = b.tick(1, 0);
        assertEquals(DropSim.GROUND_WALK, t1.x(), 1e-12, "the last tick over the ledge is a whole walking step");
        assertEquals(0, t1.y(), 0, "and drops nothing");
        assertEquals(1, t1.ground(), "one ledge tick left");
        DropSim.Body t2 = t1.tick(1, 0);
        assertEquals(DropSim.GROUND_WALK, t2.x() - t1.x(), 1e-12,
                "the edge tick starts off the ledge but was begun on the ground: another whole step");
        assertEquals(-0.0784, t2.y(), 1e-8, "dropping vanilla's 0.0784");
        assertEquals(0, t2.ground(), "and after it the body is in the air");
        assertEquals(DropSim.WALK_OFF_SPEED, t2.vx(), 1e-12,
                "it leaves with the ground's 0.118, not the 0.198 a body already in the air would keep");
        DropSim.Body t3 = t2.tick(1, 0);
        assertEquals(DropSim.WALK_OFF_SPEED + DropSim.WALK_ACCEL, t3.x() - t2.x(), 1e-12,
                "the first air tick moves 0.118 + 0.0196 = 0.137, with air acceleration");
        assertEquals((DropSim.WALK_OFF_SPEED + DropSim.WALK_ACCEL) * DropSim.AIR_DRAG, t3.vx(), 1e-12,
                "and air drag");
    }

    @Test
    void aJumpOffIsOneGroundTickWithTheJumpsSpeed() {
        DropSim.Body j = DropSim.jumpOff(0, 0, 0, 0, 1);
        assertEquals(DropSim.JUMP_OFF_LEDGE_TICKS, j.ground(), "the jump's own tick is its one ledge tick");
        assertEquals(DropSim.JUMP_POWER, j.vy(), 0, "starting up at vanilla's 0.42");
        assertEquals(DropSim.WALK_OFF_SPEED, j.vz(), 0, "heading the way it was given at a walker's 0.118");
        DropSim.Body t1 = j.tick(0, 1);
        assertEquals(DropSim.GROUND_WALK, t1.z(), 1e-12, "the jump tick is a whole walking step forward");
        assertEquals(0.42, t1.y(), 1e-6, "and 0.42 up");
        assertEquals(0, t1.ground(), "then the air");
        assertEquals(DropSim.WALK_OFF_SPEED, t1.vz(), 1e-12, "carrying the ground's 0.118 out of it");
    }

    @Test
    void aBodyInTheAirHasNoLedgeTicksAndNeverGetsThemBack() {
        DropSim.Body air = new DropSim.Body(0, 10, 0, 0.1, 0, 0);
        assertEquals(0, air.ground(), "a body made without ledge ticks is in the air");
        DropSim.Body t = air.tick(1, 0).tick(1, 0);
        assertEquals(0, t.ground(), "and stays there: the count never goes below 0");
        assertEquals(0, new DropSim.Body(0, 0, 0, 0, 0, 0, -3).ground(), "a negative count is none");
    }
}
