package com.dierks.homecraft.games.chance.wheel;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Wheel sits on the screen and how the highlight travels (spec §5.5, R1.7, §2): the ring
 * is the border of the top five rows, clockwise; the spin makes whole laps plus the way to the
 * landing space over a fixed number of frames on a fixed, slowing schedule — the same length and
 * timing whatever it lands on and whatever that space pays — and never stalls beside a space.
 */
class WheelRingTest {

    @Test
    void theRingIsTheBorderOfTheTopFiveRowsClockwise() {
        int[] expected = {0, 1, 2, 3, 4, 5, 6, 7, 8, 17, 26, 35, 44, 43, 42, 41, 40, 39, 38, 37, 36, 27, 18, 9};
        assertArrayEquals(expected, WheelRing.SLOTS, "clockwise from the top-left corner");
        Set<Integer> seen = new HashSet<>();
        for (int slot : WheelRing.SLOTS) {
            int row = slot / 9;
            int col = slot % 9;
            assertTrue(row <= 4, "the ring stays out of the button row: " + slot);
            assertTrue(row == 0 || row == 4 || col == 0 || col == 8, "every ring slot is on the border: " + slot);
            assertTrue(seen.add(slot), "no slot twice: " + slot);
        }
        assertEquals(24, seen.size(), "24 spaces");
        for (int i = 0; i < 24; i++) {
            int a = WheelRing.SLOTS[i];
            int b = WheelRing.SLOTS[(i + 1) % 24];
            int dr = Math.abs(a / 9 - b / 9);
            int dc = Math.abs(a % 9 - b % 9);
            assertEquals(1, dr + dc, "neighbouring spaces touch: " + a + " and " + b);
        }
    }

    @Test
    void theSpinIsTheSameLengthAndTimingWhateverItLandsOnAndWhateverThatPays() {
        WheelOdds shipped = WheelSettings.defaults().odds(10);
        for (WheelRing.Plan plan : new WheelRing.Plan[]{WheelRing.JAVA, WheelRing.BEDROCK}) {
            long[] schedule = WheelRing.schedule(plan);
            assertEquals(plan.frames(), schedule.length, "one tick per frame");
            assertEquals(plan.ticks(), schedule[schedule.length - 1], "it always stops on the same tick");
            for (int start = 0; start < 24; start++) {
                for (int target = 0; target < 24; target++) {
                    int[] path = WheelRing.path(start, target, plan);
                    assertEquals(plan.frames(), path.length, "the same number of frames for every landing space "
                            + "(prize " + shipped.prize(target) + ")");
                    assertEquals(target, path[path.length - 1], "it stops on the space the spin landed on");
                    int[] steps = WheelRing.steps(WheelRing.distance(start, target, plan), plan.frames());
                    assertEquals(plan.laps() * 24 + Math.floorMod(target - start, 24), Arrays.stream(steps).sum(),
                            "whole laps plus the way there, no more");
                    for (int i = 0; i < steps.length; i++) {
                        assertTrue(steps[i] >= 1, "it never stands still on a neighbour");
                        if (i > 0) {
                            assertTrue(steps[i] <= steps[i - 1], "it only slows down, never lurches forward");
                        }
                    }
                }
            }
            assertArrayEquals(schedule, WheelRing.schedule(plan), "the schedule is fixed");
        }
    }

    @Test
    void theScheduleSlowsDownAndStaysShort() {
        for (WheelRing.Plan plan : new WheelRing.Plan[]{WheelRing.JAVA, WheelRing.BEDROCK}) {
            long[] at = WheelRing.schedule(plan);
            long last = 0;
            long gap = 0;
            for (long t : at) {
                assertTrue(t - last >= 1, "every frame gets at least a tick");
                assertTrue(t - last >= gap, "the gaps only grow: " + Arrays.toString(at));
                gap = t - last;
                last = t;
            }
            assertTrue(last <= 40, "a spin is over in two seconds: " + last + " ticks");
        }
        assertTrue(WheelRing.BEDROCK.frames() < WheelRing.JAVA.frames(), "Bedrock gets fewer frames");
    }
}
