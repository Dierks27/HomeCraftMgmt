package com.dierks.homecraft.games.gen.parkour;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jump simulation every parkour layout's proof rests on (GEN-SPEC §4.1): it reproduces the
 * spec's reach table and vanilla's jump peak, shows that a tiny pad gives nearly the full run-up
 * (so 1 x 1 pads are fair), and that reach only grows as the landing gets lower (so a drop is
 * never harder than the table says).
 */
class JumpSimTest {

    /** §4.1's table: dy, walk reach, sprint reach. */
    private static final double[][] TABLE = {
            {1, 2.12, 3.41},
            {0, 2.68, 4.28},
            {-1, 3.07, 4.86},
            {-2, 3.26, 5.15},
    };

    @Test
    void theReachTableMatchesTheSpecToFiveHundredths() {
        for (double[] row : TABLE) {
            int dy = (int) row[0];
            assertEquals(row[1], JumpSim.reach(dy, JumpSim.Mode.WALK), 0.05, "walking reach for dy " + dy);
            assertEquals(row[2], JumpSim.reach(dy, JumpSim.Mode.SPRINT), 0.05, "sprinting reach for dy " + dy);
            assertEquals(JumpSim.reach(dy, JumpSim.Mode.SPRINT), JumpSim.sprintReach(dy), 0.0,
                    "sprintReach is the sprint column");
            assertEquals(JumpSim.reach(dy, JumpSim.Mode.WALK), JumpSim.walkReach(dy), 0.0,
                    "walkReach is the walk column");
        }
    }

    @Test
    void aJumpPeaksAtVanillasHeight() {
        assertEquals(1.2522, JumpSim.peak(), 0.001, "0.42 up, then gravity 0.08 with 0.98 drag, tops out at 1.2522");
        assertEquals(0, JumpSim.reach(1.3, JumpSim.Mode.SPRINT), 0.0, "a landing above the peak can't be reached");
        assertEquals(0, JumpSim.reach(2, JumpSim.Mode.WALK), 0.0, "nor can one two blocks up");
        assertTrue(JumpSim.reach(1.25, JumpSim.Mode.SPRINT) > 0, "just under the peak still can");
    }

    @Test
    void aOneByOnePadGivesAtLeastNinetySevenPercentOfAFullRunUp() {
        for (JumpSim.Mode mode : JumpSim.Mode.values()) {
            for (double[] row : TABLE) {
                double full = JumpSim.reach(row[0], mode);
                // a 1 x 1 pad: from standing at its back edge to leaving its front edge is 1 + 0.6
                double tiny = JumpSim.reachFromRest(row[0], mode, 1 + JumpSim.HITBOX);
                assertTrue(tiny >= 0.97 * full, mode + " dy " + row[0] + ": a 1x1 run-up gives " + tiny + " of "
                        + full);
                assertTrue(tiny <= full + 1e-9, mode + " dy " + row[0] + ": never more than a full run-up");
            }
        }
    }

    @Test
    void reachOnlyGrowsAsTheLandingDrops() {
        for (JumpSim.Mode mode : JumpSim.Mode.values()) {
            for (int dy = 0; dy >= -2; dy--) {
                assertTrue(JumpSim.reach(dy, mode) > JumpSim.reach(dy + 1, mode), mode + ": the table grows row by"
                        + " row, at dy " + dy);
            }
            // further down, two whole-block drops can end in the same tick: never less, and more every few
            double previous = JumpSim.reach(-2, mode);
            for (int dy = -3; dy >= -40; dy--) {
                double r = JumpSim.reach(dy, mode);
                assertTrue(r >= previous, mode + ": reach at dy " + dy + " (" + r + ") is at least dy " + (dy + 1));
                assertTrue(r > JumpSim.reach(dy + 3, mode), mode + ": and more than three blocks higher, at " + dy);
                previous = r;
            }
            assertTrue(JumpSim.reach(-100, mode) > JumpSim.reach(-40, mode), mode + ": beyond the table too");
            assertTrue(JumpSim.reach(-2.5, mode) >= JumpSim.reach(-2, mode), mode + ": and between whole blocks");
        }
    }

    @Test
    void sprintingAlwaysReachesFurtherThanWalking() {
        for (int dy = 1; dy >= -30; dy--) {
            assertTrue(JumpSim.reach(dy, JumpSim.Mode.SPRINT) > JumpSim.reach(dy, JumpSim.Mode.WALK),
                    "sprinting beats walking at dy " + dy);
        }
    }

    @Test
    void pressingJumpATickEarlyCostsOneGroundStep() {
        assertEquals(0.22, JumpSim.tickLoss(JumpSim.Mode.WALK), 0.01, "walking covers about 0.22 a tick (4.3 b/s)");
        assertEquals(0.28, JumpSim.tickLoss(JumpSim.Mode.SPRINT), 0.01, "sprinting about 0.28 (5.6 b/s)");
        for (JumpSim.Mode mode : JumpSim.Mode.values()) {
            assertEquals(JumpSim.reachAfter(0, mode, JumpSim.FULL_RUN_UP), JumpSim.reach(0, mode), 1e-9,
                    mode + ": the table is for a full run-up");
            assertTrue(JumpSim.reachAfter(0, mode, 1) < JumpSim.reach(0, mode) - 0.1,
                    mode + ": a jump from standing still is clearly shorter");
        }
    }
}
