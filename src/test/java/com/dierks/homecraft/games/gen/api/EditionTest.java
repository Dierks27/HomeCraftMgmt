package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course day (GEN-SPEC §3.1): 03:59 is still yesterday and 04:00 is today; the next change is
 * the next rollover, 23 or 25 hours away across a DST change in America/Chicago; a rollover inside
 * the spring-forward gap happens at the first instant after it; the day never steps back in the
 * repeated fall-back hour; and a week is the quests' week.
 */
class EditionTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Edition FOUR_AM = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY);

    private static long at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static long day(int y, int mo, int d) {
        return LocalDate.of(y, mo, d).toEpochDay();
    }

    @Test
    void theDayChangesAtTheRolloverNotAtMidnight() {
        assertEquals(20725, day(2026, 9, 29), "the spec's example day is 2026-09-29");
        assertEquals(day(2026, 9, 28), FOUR_AM.day(at(2026, 9, 29, 3, 59)), "03:59 is still yesterday");
        assertEquals(day(2026, 9, 29), FOUR_AM.day(at(2026, 9, 29, 4, 0)), "04:00 is today");
        assertEquals(day(2026, 9, 28), FOUR_AM.day(at(2026, 9, 29, 0, 30)), "just after midnight is yesterday");
        assertEquals(day(2026, 9, 29), FOUR_AM.day(at(2026, 9, 29, 23, 59)), "late evening is today");
        assertEquals(day(2026, 9, 28), FOUR_AM.day(at(2026, 9, 29, 4, 0) - 1), "one millisecond before is yesterday");
    }

    @Test
    void theNextChangeIsTheNextRollover() {
        assertEquals(at(2026, 9, 30, 4, 0), FOUR_AM.nextChangeAt(at(2026, 9, 29, 10, 0)), "tomorrow at four");
        assertEquals(at(2026, 9, 29, 4, 0), FOUR_AM.nextChangeAt(at(2026, 9, 29, 3, 59)), "at 03:59, in a minute");
        assertEquals(at(2026, 9, 30, 4, 0), FOUR_AM.nextChangeAt(at(2026, 9, 29, 4, 0)),
                "at 04:00 exactly, the day just changed: the next one is tomorrow");
        assertEquals(at(2026, 9, 29, 4, 0), FOUR_AM.startOf(day(2026, 9, 29)), "a day starts at its rollover");
    }

    @Test
    void springForwardMakesATwentyThreeHourCourseDay() {
        // 2026-03-08: Chicago skips 02:00-03:00.
        long start = FOUR_AM.startOf(day(2026, 3, 8));
        assertEquals(23 * 3_600_000L, start - FOUR_AM.startOf(day(2026, 3, 7)), "the day before is 23 hours long");
        assertEquals(start, FOUR_AM.nextChangeAt(at(2026, 3, 7, 12, 0)), "the change is at 04:00 CDT");
        assertEquals(day(2026, 3, 7), FOUR_AM.day(at(2026, 3, 8, 3, 59)), "03:59 CDT is still the 7th");
        assertEquals(day(2026, 3, 8), FOUR_AM.day(start), "04:00 CDT is the 8th");
    }

    @Test
    void aRolloverInsideTheSpringGapIsTheFirstInstantAfterIt() {
        Edition halfTwo = new Edition(CHICAGO, LocalTime.of(2, 30), DayOfWeek.MONDAY);
        long gapEnd = LocalDateTime.of(2026, 3, 8, 3, 0).atZone(CHICAGO).toInstant().toEpochMilli();
        assertEquals(gapEnd, halfTwo.startOf(day(2026, 3, 8)), "02:30 doesn't exist that day: it is 03:00 CDT");
        assertEquals(day(2026, 3, 7), halfTwo.day(gapEnd - 1), "the instant before the gap ends is the 7th");
        assertEquals(day(2026, 3, 8), halfTwo.day(gapEnd), "the gap's end is the 8th");
    }

    @Test
    void fallBackMakesATwentyFiveHourCourseDay() {
        // 2026-11-01: Chicago repeats 01:00-02:00.
        assertEquals(25 * 3_600_000L, FOUR_AM.startOf(day(2026, 11, 1)) - FOUR_AM.startOf(day(2026, 10, 31)),
                "the day before is 25 hours long");
        assertEquals(24 * 3_600_000L, FOUR_AM.startOf(day(2026, 11, 2)) - FOUR_AM.startOf(day(2026, 11, 1)),
                "and the day after is 24 again");
    }

    @Test
    void theDayNeverStepsBackInTheRepeatedHour() {
        Edition halfOne = new Edition(CHICAGO, LocalTime.of(1, 30), DayOfWeek.MONDAY);
        long from = at(2026, 10, 31, 20, 0);
        long to = from + 12 * 3_600_000L;
        long last = halfOne.day(from);
        int changes = 0;
        for (long t = from; t <= to; t += 60_000L) {
            long d = halfOne.day(t);
            assertTrue(d >= last, "the course day never goes back (at " + t + ")");
            if (d != last) {
                changes++;
                assertEquals(halfOne.startOf(d), t, "and it changes exactly at the rollover");
            }
            last = d;
        }
        assertEquals(1, changes, "one change in the night, at the first 01:30");
        long firstHalfOne = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(CHICAGO).withEarlierOffsetAtOverlap()
                .toInstant().toEpochMilli();
        assertEquals(firstHalfOne, halfOne.startOf(day(2026, 11, 1)), "the first of the two 01:30s");
    }

    @Test
    void aWeekIsTheQuestsWeek() {
        assertEquals(day(2026, 9, 28), FOUR_AM.weekKey(day(2026, 9, 29)), "a Tuesday is in the week from Monday");
        assertEquals(day(2026, 9, 28), FOUR_AM.weekKey(day(2026, 9, 28)), "a Monday starts its own week");
        assertEquals(day(2026, 9, 28), FOUR_AM.weekKey(day(2026, 10, 4)), "the Sunday is still that week");
        Edition sunday = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY);
        assertEquals(day(2026, 9, 27), sunday.weekKey(day(2026, 9, 29)), "weeks can start on Sunday");
    }

    @Test
    void editionKeysAndDefaults() {
        assertEquals("20725", Edition.editionKey(20725, 0), "no reroll: the day");
        assertEquals("20725r2", Edition.editionKey(20725, 2), "a reroll: <day>r<n>");
        assertEquals(LocalDate.of(2026, 9, 29), Edition.date(20725), "a day's date");
        Edition d = new Edition(null, null, null);
        assertEquals(ZoneOffset.UTC, d.zone(), "no zone reads as UTC");
        assertEquals(Edition.DEFAULT_ROLLOVER, d.rollover(), "no rollover reads as 04:00");
        assertEquals(DayOfWeek.MONDAY, d.weekStart(), "weeks start on Monday by default");
    }
}
