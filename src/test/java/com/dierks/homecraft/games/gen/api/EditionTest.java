package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course day and the edition (GEN-SPEC §3.1, weekly addendum §1): 03:59 is still yesterday and
 * 04:00 is today; the next change is the next rollover, 23 or 25 hours away across a DST change in
 * America/Chicago; a rollover inside the spring-forward gap happens at the first instant after it;
 * the day never steps back in the repeated fall-back hour; a week is the quests' week.
 *
 * <p>And the cadence: N = 1, 2, 3, 7, 14 and 28 start on the fixed grid {@code (d - anchor) mod N},
 * the anchor being the first rebuild day on or after 2026-01-05; the key is {@code N:<index>} and
 * changes only on a start day; Monday 03:59 vs 04:00 and the Sunday-to-Monday rollover; DST weeks in
 * America/Chicago; days before the anchor (floorMod); and moving the rebuild day never brings back an
 * earlier key: at most the running edition's own, once, on the new schedule's first start (which the
 * engine then treats as that edition carried on).
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
        assertEquals(20458, Edition.EPOCH_DAY, "2026-01-05, a Monday, is where editions count from");
        assertEquals(DayOfWeek.MONDAY, Edition.EPOCH.getDayOfWeek(), "and it is a Monday");
        assertEquals("1:267", Edition.editionKey(20725, 0), "a daily edition: 1:<days since 2026-01-05>");
        assertEquals("1:267r2", Edition.editionKey(20725, 2), "a reroll: r<n> after the key");
        assertEquals("7:38", Edition.editionKey(7, day(2026, 9, 28), 0), "the week of Mon 28 Sep is 7:38");
        assertEquals("7:38r1", Edition.editionKey(7, day(2026, 9, 28), 1), "and its first reroll");
        assertEquals(LocalDate.of(2026, 9, 29), Edition.date(20725), "a day's date");
        Edition d = new Edition(null, null, null);
        assertEquals(ZoneOffset.UTC, d.zone(), "no zone reads as UTC");
        assertEquals(Edition.DEFAULT_ROLLOVER, d.rollover(), "no rollover reads as 04:00");
        assertEquals(DayOfWeek.MONDAY, d.weekStart(), "weeks start on Monday by default");
        assertEquals(Edition.DAILY, d.cadenceDays(), "the three-part form is the course-day rules: daily");
        assertEquals(DayOfWeek.MONDAY, d.rebuildDay(), "an empty rebuild day is the week start");
        assertEquals(DayOfWeek.SUNDAY, new Edition(null, null, DayOfWeek.SUNDAY, 7, null).rebuildDay(),
                "the quests' week start, whatever it is");
        assertEquals(Edition.MAX_CADENCE, new Edition(null, null, null, 99, null).cadenceDays(), "28 days at most");
        assertEquals(Edition.DAILY, new Edition(null, null, null, 0, null).cadenceDays(), "and 1 at least");
    }

    // ---- cadences (weekly addendum §1) ------------------------------------------------------------

    private static Edition every(int days) {
        return new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, days, null);
    }

    @Test
    void theAnchorIsTheFirstRebuildDayOnOrAfterTheFifthOfJanuary() {
        assertEquals(day(2026, 1, 5), every(7).anchor(), "Monday: the fifth itself");
        Edition thursday = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.THURSDAY);
        assertEquals(day(2026, 1, 8), thursday.anchor(), "Thursday: the eighth");
        Edition sunday = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY, 7, null);
        assertEquals(day(2026, 1, 11), sunday.anchor(), "an empty rebuild day with Sunday weeks: the eleventh");
    }

    @Test
    void everyCadenceStartsOnItsFixedGridAndKeysChangeExactlyThere() {
        for (int n : new int[]{1, 2, 3, 7, 14, 28}) {
            Edition ed = every(n);
            long from = day(2026, 8, 1);
            long to = day(2027, 3, 31);
            int starts = 0;
            String lastKey = null;
            for (long d = from; d <= to; d++) {
                boolean start = Math.floorMod(d - day(2026, 1, 5), n) == 0;
                assertEquals(start, ed.starts(d), "N=" + n + ": (d - anchor) mod N == 0 is a start, " + d);
                long noon = ed.startOf(d) + 8 * 3_600_000L;
                long first = ed.editionStart(noon);
                assertTrue(first <= d && d < first + n, "N=" + n + ": the edition around " + d + " starts in the "
                        + "last N days: " + first);
                assertTrue(ed.starts(first), "N=" + n + ": and on the grid");
                assertEquals(ed.startOf(first + n), ed.nextChangeAt(noon), "N=" + n + ": it ends at the next start");
                String key = ed.key(noon);
                assertEquals(n + ":" + Math.floorDiv(first - day(2026, 1, 5), n), key, "N=" + n + ": N:<index>");
                if (lastKey != null) {
                    assertEquals(start, !key.equals(lastKey), "N=" + n + ": the key changes on a start day only, " + d);
                }
                lastKey = key;
                starts += start ? 1 : 0;
            }
            long days = to - from + 1;
            assertTrue(Math.abs(starts - days / (double) n) <= 1, "N=" + n + ": one start every N days (" + starts
                    + " in " + days + ")");
        }
    }

    @Test
    void mondayAt359IsStillLastWeekAndAt400ANewWeekBegins() {
        Edition weekly = every(7);
        long sun = at(2026, 10, 4, 12, 0);
        long mon359 = at(2026, 10, 5, 3, 59);
        long mon400 = at(2026, 10, 5, 4, 0);
        assertEquals(day(2026, 9, 28), weekly.editionStart(sun), "Sunday is in the week that began Monday 28 Sep");
        assertEquals(day(2026, 9, 28), weekly.editionStart(mon359), "Monday 03:59 is still that week");
        assertEquals("7:38", weekly.key(mon359), "edition 7:38");
        assertEquals(day(2026, 10, 5), weekly.editionStart(mon400), "Monday 04:00 is the new week");
        assertEquals("7:39", weekly.key(mon400), "edition 7:39");
        assertEquals(mon400, weekly.nextChangeAt(sun), "from Sunday the next change is Monday 04:00");
        assertEquals(mon400, weekly.nextChangeAt(mon359), "from 03:59, in a minute");
        assertEquals(at(2026, 10, 12, 4, 0), weekly.nextChangeAt(mon400), "from 04:00, a week later");
        assertEquals(weekly.key(at(2026, 9, 29, 9, 0)), weekly.key(at(2026, 10, 1, 23, 0)),
                "the courses don't change on Tuesday or any day but Monday");

        Edition daily = every(1);
        assertEquals("1:272", daily.key(at(2026, 10, 5, 3, 59)), "daily: Monday 03:59 is Sunday's (day 272)");
        assertEquals("1:273", daily.key(at(2026, 10, 5, 4, 0)), "and 04:00 is Monday's");
        Edition three = every(3);
        long before = at(2026, 9, 29, 3, 59);
        long after = at(2026, 9, 29, 4, 0);
        assertTrue(three.starts(day(2026, 9, 29)), "Tue 29 Sep is on the 3-day grid (267 days from the anchor)");
        assertFalse(three.starts(day(2026, 10, 1)), "Thu 1 Oct isn't");
        assertEquals("3:88", three.key(before), "03:59 is still the set from Sat 26 Sep");
        assertEquals("3:89", three.key(after), "every 3 days: it changes at 04:00 on the grid day");
        assertEquals(three.key(after), three.key(at(2026, 10, 2, 3, 59)), "and holds for three days");
        assertEquals(at(2026, 10, 2, 4, 0), three.nextChangeAt(after), "then Fri 2 Oct");
    }

    @Test
    void weeklyEditionsAcrossDstInChicagoAreAWeekOfLocalTime() {
        Edition weekly = every(7);
        // 2026-03-08 (a Sunday): Chicago skips 02:00-03:00; the week of Mon 2 Mar is an hour short.
        long mar2 = weekly.startOf(day(2026, 3, 2));
        assertEquals(weekly.startOf(day(2026, 3, 9)), weekly.nextChangeAt(mar2), "the next week is Mon 9 Mar 04:00");
        assertEquals(7 * 24 * 3_600_000L - 3_600_000L, weekly.nextChangeAt(mar2) - mar2,
                "which is 167 hours away");
        assertEquals(day(2026, 3, 2), weekly.editionStart(at(2026, 3, 9, 3, 59)), "03:59 CDT is still that week");
        assertEquals(day(2026, 3, 9), weekly.editionStart(at(2026, 3, 9, 4, 0)), "04:00 CDT is the next");
        // 2026-11-01 (a Sunday): Chicago repeats 01:00-02:00; the week of Mon 26 Oct is an hour long.
        long oct26 = weekly.startOf(day(2026, 10, 26));
        assertEquals(7 * 24 * 3_600_000L + 3_600_000L, weekly.nextChangeAt(oct26) - oct26, "169 hours");
        long last = weekly.editionStart(at(2026, 10, 31, 20, 0));
        for (long t = at(2026, 10, 31, 20, 0); t <= at(2026, 11, 2, 6, 0); t += 60_000L) {
            long e = weekly.editionStart(t);
            assertTrue(e >= last, "the edition never goes back in the repeated hour (at " + t + ")");
            last = e;
        }
        assertEquals(day(2026, 11, 2), last, "and moves on at Monday 04:00 CST");
        Edition three = every(3);
        long start = three.editionStart(at(2026, 11, 1, 12, 0));
        assertTrue(three.starts(start), "every 3 days across the fall-back weekend stays on its grid");
        assertEquals(three.startOf(start + 3), three.nextChangeAt(at(2026, 11, 1, 12, 0)),
                "and its change is at 04:00 local on the grid day");
    }

    @Test
    void daysBeforeTheAnchorUseFloorModNotRemainder() {
        Edition weekly = every(7);
        long dec31 = at(2025, 12, 31, 12, 0);
        assertEquals(day(2025, 12, 29), weekly.editionStart(dec31), "31 Dec 2025 is in the week of Mon 29 Dec");
        assertEquals("7:-1", weekly.key(dec31), "the week before the epoch is index -1, not 0");
        Edition three = every(3);
        assertEquals(day(2025, 12, 30), three.editionStart(dec31), "every 3 days: 30 Dec (six days before the 5th)");
        assertEquals("3:-2", three.key(dec31), "index -2");
        assertTrue(three.starts(day(2026, 1, 2)) && !three.starts(day(2026, 1, 1)), "2 Jan is a start, 1 Jan isn't");
        assertEquals(-1, Edition.index(7, day(2026, 1, 4)), "the Sunday before the epoch is week -1");
        assertEquals(0, Edition.index(7, day(2026, 1, 5)), "the epoch Monday is week 0");
    }

    @Test
    void anIndexDependsOnTheStartDateAloneSoAMovedRebuildDayCanRepeatOnlyTheRunningKeyAndOnlyOnce() {
        for (DayOfWeek rebuild : DayOfWeek.values()) {
            Edition daily = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 1, rebuild);
            assertEquals("1:267", daily.key(at(2026, 9, 29, 12, 0)), "a daily key is the same whatever the "
                    + "rebuild day (" + rebuild + ")");
            Edition weekly = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, rebuild);
            long start = weekly.editionStart(at(2026, 9, 29, 12, 0));
            assertEquals((start - weekly.anchor()) / 7, Edition.index(7, start),
                    "weekly and longer: exactly (start - anchor) / N (" + rebuild + ")");
        }
        Edition monday = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.MONDAY);
        Edition friday = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.FRIDAY);
        assertEquals(monday.key(at(2026, 9, 28, 12, 0)), friday.key(at(2026, 10, 2, 12, 0)),
                "Monday to Friday: the Friday after Mon 28 Sep carries the same key (7:38); the engine keeps the "
                        + "live layout through it instead of building it again (GenScheduler#target)");
        long switchDay = day(2026, 9, 20);
        for (int n : new int[]{1, 2, 3, 5, 7, 14}) {
            for (DayOfWeek from : DayOfWeek.values()) {
                for (DayOfWeek to : DayOfWeek.values()) {
                    Edition before = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, n, from);
                    Edition after = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, n, to);
                    java.util.Set<String> used = new java.util.HashSet<>();
                    String running = null;
                    for (long d = switchDay - 40; d < switchDay; d++) {
                        running = before.key(before.startOf(d) + 3_600_000L);
                        used.add(running);
                    }
                    // From the new schedule's first start after the change (noon on switchDay - 1), every
                    // key is new, except that the first may be the running one: GenScheduler#target then
                    // keeps the running layout through it (GenSchedulerTest walks that through the engine).
                    long first = after.day(after.nextChangeAt(at(2026, 9, 19, 12, 0)));
                    long previous = Long.MIN_VALUE;
                    for (long d = first; d < switchDay + 60; d++) {
                        if (!after.starts(d)) {
                            continue;
                        }
                        String key = after.key(after.startOf(d) + 3_600_000L);
                        long index = Edition.index(n, d);
                        String what = "N=" + n + ", " + from + " -> " + to + ": " + key + " on day " + d;
                        assertTrue(index > previous, what + " goes back or repeats");
                        previous = index;
                        assertTrue(!used.contains(key) || (key.equals(running) && d == first),
                                what + " was already used (only the running key, on the first new start, may be)");
                    }
                }
            }
        }
    }

    @Test
    void editionsStartingInAWeekAndKeysReadBack() {
        assertEquals(1, every(7).startsInWeek(day(2026, 9, 28)), "weekly: one a week");
        assertEquals(7, every(1).startsInWeek(day(2026, 9, 28)), "daily: seven");
        int two = every(3).startsInWeek(day(2026, 9, 28));
        assertTrue(two == 2 || two == 3, "every 3 days: two or three: " + two);
        assertEquals(1, every(14).startsInWeek(day(2026, 10, 12)) + every(14).startsInWeek(day(2026, 10, 19)),
                "every 14 days: one start in two weeks");
        Edition thursdays = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.THURSDAY);
        long thu = thursdays.editionStart(at(2026, 10, 3, 12, 0));
        assertEquals(day(2026, 10, 1), thu, "Saturday 3 Oct is in the week that began Thursday 1 Oct");
        Edition.Key thuKey = Edition.Key.parse(thursdays.key(at(2026, 10, 3, 12, 0)));
        assertEquals(thu, thursdays.startDayOf(thuKey), "a key reads back to its real first day under its rules");
        assertEquals(day(2026, 9, 28), thuKey.firstDay(), "(its earliest possible day is the Monday before)");
        assertEquals(thuKey.firstDay(), every(1).startDayOf(thuKey), "under another cadence, the earliest day");
        Edition.Key k = Edition.Key.parse("7:38r1");
        assertEquals(new Edition.Key(7, 38, 1), k, "a key reads back");
        assertEquals("7:38", k.base(), "its base has no reroll");
        assertEquals("7:38r1", k.toString(), "and it writes back the same");
        assertEquals(day(2026, 9, 28), k.firstDay(), "the week of 28 Sep");
        assertEquals(new Edition.Key(3, -2, 0), Edition.Key.parse("3:-2"), "negative indexes too");
        for (String bad : new String[]{null, "", "7", ":38", "0:3", "29:1", "7:x", "7:38r", "7:38r0", "20725"}) {
            assertEquals(null, Edition.Key.parse(bad), "'" + bad + "' is not a key");
        }
    }
}
