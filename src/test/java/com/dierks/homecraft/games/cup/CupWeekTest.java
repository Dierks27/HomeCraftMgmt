package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.Edition;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cup week (§D2: "paid at the week's rollover, on the quests' week start at 04:00, the same time
 * as the Fresh Courses change"): 03:59 on the first day is still last week's Cup and 04:00 is the new
 * one; a week ends at the next week's 04:00, an hour longer across the autumn DST change; a run
 * counts only for the Cup week it started and finished in; and only a Fresh slot that keeps one
 * layout for each whole week can run a Cup.
 */
class CupWeekTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Edition MONDAYS = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY);

    private static long at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static long day(int y, int mo, int d) {
        return LocalDate.of(y, mo, d).toEpochDay();
    }

    @Test
    void theCupWeekTurnsAtFourInTheMorningOnTheFirstDayOfTheWeek() {
        assertEquals(day(2026, 9, 21), CupRules.week(MONDAYS, at(2026, 9, 28, 3, 59)),
                "Monday 03:59 is still last week's Cup");
        assertEquals(day(2026, 9, 28), CupRules.week(MONDAYS, at(2026, 9, 28, 4, 0)),
                "Monday 04:00 is the new week's Cup");
        assertEquals(day(2026, 9, 28), CupRules.week(MONDAYS, at(2026, 10, 5, 3, 59)),
                "the next Monday 03:59 is still this week's");
        assertEquals(day(2026, 9, 28), CupRules.week(MONDAYS, at(2026, 10, 4, 0, 0)),
                "Sunday midnight is still this week's, although the time trials' own week has turned by then");
        assertEquals(CupFixtures.CUP.week(), CupRules.week(MONDAYS, at(2026, 9, 29, 12, 0)),
                "the fixtures' week is the week of 28 September");
    }

    @Test
    void aWeekIsSettledAtTheNextWeeksFirstRollover() {
        long week = day(2026, 9, 28);
        assertEquals(at(2026, 10, 5, 4, 0), CupRules.settlesAt(MONDAYS, week), "settled Monday 5 October 04:00");
        long dst = day(2026, 10, 26);
        assertEquals(7L * 24 * 3600_000 + 3600_000, CupRules.settlesAt(MONDAYS, dst) - MONDAYS.startOf(dst),
                "the week the clocks go back (1 November) is 169 hours long");
        assertEquals(dst + 7, CupRules.week(MONDAYS, CupRules.settlesAt(MONDAYS, dst)),
                "the instant a week is settled is the first instant of the next one");
        assertEquals(dst, CupRules.week(MONDAYS, CupRules.settlesAt(MONDAYS, dst) - 1),
                "a millisecond before, it is still the old week");
    }

    @Test
    void aRunCountsForTheCupWeekItStartedAndFinishedIn() {
        assertEquals(OptionalLong.of(day(2026, 9, 28)), CupRules.runWeek(MONDAYS, at(2026, 9, 29, 12, 0),
                at(2026, 9, 29, 12, 2)), "a run inside the week counts for that week");
        assertEquals(OptionalLong.empty(), CupRules.runWeek(MONDAYS, at(2026, 10, 5, 3, 58), at(2026, 10, 5, 4, 1)),
                "a run across the rollover counts for neither week's Cup: the old one was settled while it ran");
        assertEquals(OptionalLong.of(day(2026, 9, 28)), CupRules.runWeek(MONDAYS, at(2026, 10, 4, 23, 58),
                at(2026, 10, 5, 0, 1)), "across midnight Sunday is still inside the Cup week");
    }

    @Test
    void onlyFreshSlotsThatKeepALayoutAllWeekCanRunACup() {
        assertTrue(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.MONDAY)),
                "weekly, changing on the week start: one layout per Cup week");
        assertTrue(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 14, null)),
                "every other week, on the week start: each Cup week has one layout");
        assertFalse(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 1, null)),
                "daily: the layout changes six times a week, so the Cup would be voided every day");
        assertFalse(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.FRIDAY)),
                "weekly but changing on Fridays: the layout changes mid-week");
    }

    @Test
    void theWeekFollowsTheQuestsWeekStart() {
        Edition sundays = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY);
        assertEquals(day(2026, 9, 27), CupRules.week(sundays, at(2026, 9, 29, 12, 0)),
                "with weeks starting on Sunday, Tuesday is in the week of Sunday 27 September");
        assertEquals(day(2026, 9, 20), CupRules.week(sundays, at(2026, 9, 27, 3, 59)),
                "Sunday 03:59 is still the week before");
    }
}
