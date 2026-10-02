package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.KeptCourses;
import com.dierks.homecraft.storage.GamesDao;
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
 * 120, 120, 150, 180 and 210 for par 2-6 — worked out from the course when the group starts; so
 * does a course kept from such a plan (red-team F02); a hand-built course and every older layout
 * (algo 2 and 3, their recalls and the courses kept from them) keep 120 seconds on every hole.
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

    /** A planned course of golf planner version {@code algo}, kept: its row as the keep writes it, read back. */
    private static GolfCourse kept(int algo, int... pars) {
        GolfCourse planned = course(null, pars);
        PlannedGolf g = new PlannedGolf(planned, List.of(), List.of(), List.of(), List.of());
        Plan plan = Plan.of("fresh_golf", algo, 1, Box.sized(0, 60, -10, 128, 16, 224), List.of(), List.of(),
                List.of(), List.of(), g, List.of(), 0);
        return CourseCodec.fromRow(KeptCourses.row("my_links", "My Links", "games", plan, 1000));
    }

    /**
     * Red-team F02: a kept course has no tag, but it says the golf planner version it was kept from
     * ({@code kept_algo}), so a kept Golf v4 course keeps its clock by par (its par 5: three minutes)
     * and a kept Adventure Golf course, a hand-built one and a row kept before the version was recorded
     * keep two minutes.
     */
    @Test
    void aKeptGolfV4CourseKeepsItsClockByParAndOlderKeptOnesKeepTwoMinutes() {
        GolfCourse v4 = kept(4, 3, 5, 6);
        assertFalse(v4.generated(), "kept: a normal course, no tag");
        assertEquals(4, v4.keptAlgo(), "that says the version it was kept from");
        assertEquals(List.of(120, 180, 210), GolfGroup.clocks(v4), "its par 5 is 3:00, its par 6 3:30");
        assertTrue(CourseCodec.toRow(v4, 1000, 1000).data().contains("kept_algo: 4"), "the row says so");
        assertEquals(List.of(180), GolfGroup.clocks(kept(5, 5)), "a later version too");
        GolfCourse renamed = v4.withName("Our Links").withRev(4).withEnabled(false).withHole(1, v4.hole(1).withPar(4));
        assertEquals(List.of(150, 180, 210), GolfGroup.clocks(renamed), "and keeps it through the editor's changes");
        assertEquals(renamed, CourseCodec.fromRow(CourseCodec.toRow(renamed, 1000, 2000)), "and through its row");
        GolfCourse v3 = kept(3, 5, 6);
        assertEquals(3, v3.keptAlgo(), "kept from Adventure Golf: version 3");
        assertTrue(v3.adventure(), "(which plays Adventure Golf's rules)");
        assertEquals(List.of(120, 120), GolfGroup.clocks(v3), "keeps two minutes");
        GolfCourse before = CourseCodec.fromRow(new GamesDao.CourseRow("old_links", "golf",
                "golf", "Old Links", "games", true, CourseCodec.write(v4.holes(), null, true), 1, 1000, 1000));
        assertEquals(0, before.keptAlgo(), "a row kept before the version was recorded says none");
        assertEquals(List.of(120, 120, 120), GolfGroup.clocks(before), "and keeps two minutes");
        assertEquals(List.of(120, 120), GolfGroup.clocks(course(null, 5, 6)), "as does a hand-built course");
        String bad = CourseCodec.write(v4.holes(), null, true) + "kept_algo: four\n";
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.fromRow(new GamesDao.CourseRow("bad", "golf",
                "golf", "Bad", "games", true, bad, 1, 1000, 1000)), "a version that isn't one is an error, not a guess");
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
