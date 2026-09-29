package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Today's pick among the courses, with no server.
 *
 * <p>Pinned here: a course is today's pick by its own id, and every course is when the game
 * itself ({@code /hcm games feature trials}) is — pinning the game used to pay no course at all.
 *
 * <p>And daily courses (GEN-SPEC §4.2, §5.4): they come first, in slot order, before the
 * hand-built courses' easiest-first order; their times go on the layout's own board; and only
 * Sky Rings (a generated elytra course) gets the "open your wings" tip.
 */
class TimeTrialsTest {

    @Test
    void aCourseIsTodaysPickByItsOwnIdOrWhenTheWholeGameIs() {
        assertTrue(TimeTrials.featured(Set.of("cliffs")::contains, "cliffs"), "the course itself is pinned");
        assertFalse(TimeTrials.featured(Set.of("cliffs")::contains, "river_run"), "another course is not");
        assertTrue(TimeTrials.featured(Set.of("trials")::contains, "river_run"),
                "the game pinned: every course is today's pick");
        assertFalse(TimeTrials.featured(Set.of("test_snake")::contains, "river_run"), "another game pinned: no course");
    }

    private static final GenTag RINGS = new GenTag("sky_rings", "rings", 1, 20_725, 0, 1L, 'A', "abcabcabcabc",
            30_000, 60_000, 90_000, List.of(), List.of(), 1L);

    private static Course course(String id, TrialKind kind, Tier tier, String name, GenTag gen) {
        return new Course(id, kind, name, tier, "games", new Course.Spot(0, 64, 0, 0, 0), List.of(),
                new Course.Mark(10, 64, 0, 2), null, null, true, false, 1, gen);
    }

    private static GenTag tag(String slot, int reroll) {
        return new GenTag(slot, "parkour", 1, 20_725, reroll, 1L, 'A', "abcabcabcabc", 1, 2, 3, List.of(), List.of(),
                1L);
    }

    @Test
    void dailyCoursesComeFirstInSlotOrder() {
        Course cliffs = course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs", null);
        Course river = course("river_run", TrialKind.BOAT, Tier.MEDIUM, "River Run", null);
        Course hard = course("daily_parkour_hard", TrialKind.PARKOUR, Tier.HARD, "Hard Parkour",
                tag("daily_parkour_hard", 0));
        Course easy = course("daily_parkour_easy", TrialKind.PARKOUR, Tier.EASY, "Easy Parkour",
                tag("daily_parkour_easy", 0));
        Course rings = course("sky_rings", TrialKind.ELYTRA, Tier.EASY, "Sky Rings", RINGS);
        assertEquals(List.of(easy, hard, rings, cliffs, river), TimeTrials.sorted(List.of(river, rings, cliffs, hard,
                easy)), "the daily ones in slot order, then the rest easiest first as before");
    }

    @Test
    void aDailyCoursesTimesGoOnItsLayoutsBoard() {
        Course easy = course("daily_parkour_easy", TrialKind.PARKOUR, Tier.EASY, "Easy Parkour",
                tag("daily_parkour_easy", 2));
        assertEquals(GenBoards.day("daily_parkour_easy", "20725r2"), TimeTrials.board(easy), "the layout's own board");
        assertEquals("course:cliffs", TimeTrials.board(course("cliffs", TrialKind.PARKOUR, Tier.EASY, "Cliffs", null)),
                "a hand-built course keeps its all-time board");
    }

    @Test
    void onlySkyRingsGetsTheWingsTip() {
        assertTrue(TimeTrials.wingsTipFor(course("sky_rings", TrialKind.ELYTRA, Tier.EASY, "Sky Rings", RINGS)),
                "a generated elytra course: the tip");
        assertFalse(TimeTrials.wingsTipFor(course("canyon", TrialKind.ELYTRA, Tier.EASY, "Canyon", null)),
                "a hand-built elytra course: as before");
        assertFalse(TimeTrials.wingsTipFor(course("daily_parkour_easy", TrialKind.PARKOUR, Tier.EASY, "Easy Parkour",
                tag("daily_parkour_easy", 0))), "parkour has no wings");
        assertFalse(TimeTrials.wingsTipFor(null), "no course");
    }
}
