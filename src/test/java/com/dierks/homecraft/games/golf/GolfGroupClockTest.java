package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golf-together hole clock on Golf v4 holes (GOLF-V4-SPEC §6.4, owner decision D9): on a course
 * whose tag is golf planner version 4 or later each hole gives max(120, 30 x (par + 1)) seconds —
 * 120, 120, 150, 180 and 210 for par 2-6 — worked out from the course when the group starts; a
 * hand-built course, a kept one (no tag) and every older layout (algo 2 and 3, and their recalls)
 * keep 120 seconds on every hole.
 */
class GolfGroupClockTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);

    private static GolfCourse course(GenTag tag, int... pars) {
        List<GolfCourse.Hole> holes = new ArrayList<>();
        for (int par : pars) {
            holes.add(new GolfCourse.Hole(new GolfCourse.Tee(10.5, 64.0, -3.5, 0f), new GolfCourse.Spot(18, 63, -3), par,
                    new GolfCourse.Spot(8, 63, -6), new GolfCourse.Spot(20, 66, 0)));
        }
        return new GolfCourse("fresh_golf", "Golf of the Week", "games", true, 1, holes, tag);
    }

    private static GenTag tag(int algo, GenTag.Recall recall) {
        return new GenTag("fresh_golf", Slots.GOLF, algo, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0, 7,
                recall);
    }

    private static Map<UUID, String> two() {
        Map<UUID, String> names = new LinkedHashMap<>();
        names.put(SAM, "Sam");
        names.put(AVA, "Ava");
        return names;
    }

    @Test
    void aGolfV4HolesClockIsThirtySecondsAStrokeOfParPlusOneAndNeverUnderTwoMinutes() {
        assertEquals(List.of(120, 120, 150, 180, 210), GolfGroup.clocks(course(tag(4, null), 2, 3, 4, 5, 6)),
                "par 2-6: 120, 120, 150, 180, 210 seconds");
        assertEquals(List.of(150, 210), GolfGroup.clocks(course(tag(5, null), 4, 6)), "and on later versions");
        for (int par = 2; par <= 6; par++) {
            assertEquals(Math.max(120, 30 * (par + 1)), GolfGroup.holeClock(par), "par " + par);
        }
    }

    @Test
    void handBuiltKeptAndOlderLayoutsKeepTwoMinutes() {
        assertEquals(List.of(120, 120, 120), GolfGroup.clocks(course(null, 3, 5, 6)),
                "a hand-built (or kept) course has no tag: 120 s");
        assertEquals(List.of(120, 120), GolfGroup.clocks(course(tag(3, null), 4, 5)), "an Adventure Golf layout: 120 s");
        assertEquals(List.of(120, 120), GolfGroup.clocks(course(tag(2, null), 3, 4)), "an algo-2 layout: 120 s");
        GenTag.Recall recall = new GenTag.Recall("fresh_classic_golf", 1_790_000_000_000L, 20725);
        assertEquals(List.of(120), GolfGroup.clocks(course(tag(3, recall), 5)), "a recalled v3 course: 120 s");
        assertEquals(List.of(180), GolfGroup.clocks(course(tag(4, recall), 5)), "a recalled v4 course has v4 holes");
        GenTag boat = new GenTag("fresh_golf", Slots.BOAT, 4, 20725, 0, 1, 'A', "", 0, 0, 0, List.of(), List.of(), 0);
        assertEquals(List.of(120), GolfGroup.clocks(course(boat, 5)), "only a golf planner's tag counts");
        assertEquals(List.of(), GolfGroup.clocks(null), "no course, no clocks");
    }

    @Test
    void theFirstBallInStartsTheHolesOwnClockAndMovingOnSwitchesToTheNext() {
        GolfCourse c = course(tag(4, null), 2, 5, 3);
        GolfGroup g = new GolfGroup(9, c.id(), c.name(), c.pars(), GolfGroup.clocks(c), two());
        assertEquals(120, g.holeClock(), "hole 1, par 2: two minutes");
        assertEquals(-1, g.clock(), "not running until a ball is in");
        assertFalse(g.holeDone(SAM, new GolfRun.HoleScore(2, 2, false)), "Sam is in, Ava is out");
        assertEquals(120, g.clock(), "the first ball in starts hole 1's clock");
        assertTrue(g.holeDone(AVA, new GolfRun.HoleScore(2, 3, false)), "Ava is in");
        assertTrue(g.advance(), "on to hole 2");
        assertEquals(180, g.holeClock(), "hole 2, par 5: three minutes");
        g.holeDone(SAM, new GolfRun.HoleScore(5, 5, false));
        assertEquals(180, g.clock(), "the first ball in starts hole 2's own clock");
        for (int s = 0; s < 179; s++) {
            assertFalse(g.second(), "second " + (s + 1) + ": still time");
        }
        assertTrue(g.second(), "at 180 seconds the ball still out is picked up");
        assertEquals(0, g.clock(), "the clock has run out");
    }

    @Test
    void aGroupWithoutClocksGivesTwoMinutesAndAWrongListIsRefused() {
        GolfGroup g = new GolfGroup(9, "meadow", "Meadow", List.of(5, 6), two());
        assertEquals(GolfGroup.HOLE_CLOCK_SECONDS, g.holeClock(), "the old constructor: 120 s");
        g.holeDone(SAM, new GolfRun.HoleScore(5, 4, false));
        assertEquals(120, g.clock(), "on every hole");
        assertThrows(IllegalArgumentException.class, () -> new GolfGroup(9, "m", "M", List.of(3, 4), List.of(120),
                two()), "a clock per hole");
    }
}
