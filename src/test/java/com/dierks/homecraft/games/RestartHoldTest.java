package com.dierks.homecraft.games;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled-restart hold's clock arithmetic ({@link RestartHold}), with no server.
 *
 * <p>Pinned here: the next restart is the nearest configured time strictly after now, over
 * several times a day and across midnight; the hold is exactly {@code [restart - hold, restart)};
 * an empty list never holds; the times stay on the wall clock in America/Chicago on both DST days,
 * a time a spring-forward day skips happens at the first instant after the gap, and a time a
 * fall-back day repeats happens once, the first time; the times read the way players read them;
 * and a config entry is a time only when it is a real 24-hour "HH:mm".
 */
class RestartHoldTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final long MINUTE = 60_000L;

    /** Epoch millis of a Chicago wall-clock time (the earlier one on a fall-back day). */
    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static long utc(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static RestartHold hold(int minutes, String... times) {
        return new RestartHold(Arrays.stream(times).map(LocalTime::parse).toList(), CHICAGO, minutes);
    }

    // ---- which restart is next ----------------------------------------------------------------

    @Test
    void theNextRestartIsTheNearestTimeOfTheDayStillAhead() {
        RestartHold owner = hold(5, "04:00", "16:00");
        assertEquals(at(2026, 6, 10, 16, 0), owner.next(at(2026, 6, 10, 10, 0)),
                "mid-morning, the next restart is this afternoon's");
        assertEquals(at(2026, 6, 11, 4, 0), owner.next(at(2026, 6, 10, 17, 0)),
                "in the evening, the next one is tomorrow's early one");
        assertEquals(at(2026, 6, 10, 4, 0), owner.next(at(2026, 6, 10, 0, 30)),
                "just after midnight, the next one is later the same night");
    }

    @Test
    void aRestartHappeningRightNowIsNoLongerTheNextOne() {
        RestartHold owner = hold(5, "04:00", "16:00");
        assertEquals(at(2026, 6, 11, 4, 0), owner.next(at(2026, 6, 10, 16, 0)),
                "at 16:00 exactly the restart is happening, so the next is tomorrow's 04:00");
        assertEquals(at(2026, 6, 10, 16, 0), owner.next(at(2026, 6, 10, 16, 0) - 1),
                "a millisecond earlier it is still this afternoon's");
    }

    @Test
    void anEmptyListNeverHolds() {
        RestartHold none = new RestartHold(List.of(), CHICAGO, 5);
        assertTrue(none.off(), "no restart times means the hold is off");
        for (long now = at(2026, 6, 10, 0, 0); now < at(2026, 6, 11, 0, 0); now += MINUTE) {
            assertFalse(none.holding(now), "nothing is ever held with no restart times, at " + Instant.ofEpochMilli(now));
        }
        assertEquals(-1, none.next(at(2026, 6, 10, 12, 0)), "there is no next restart");
        assertNull(none.heldFor(at(2026, 6, 10, 12, 0)), "and nothing to tell players");
        assertEquals("No restart times set", none.status(at(2026, 6, 10, 12, 0)), "the status line says so");
        assertTrue(new RestartHold(null, null, 5).off(), "a missing list is an empty one, never a crash");
    }

    // ---- the hold window -------------------------------------------------------------------------

    @Test
    void theHoldRunsFromHoldMinutesBeforeTheRestartUpToIt() {
        RestartHold owner = hold(5, "04:00", "16:00");
        long restart = at(2026, 6, 10, 16, 0);
        assertFalse(owner.holding(restart - 5 * MINUTE - 1), "a millisecond before 15:55 nothing is held yet");
        assertTrue(owner.holding(restart - 5 * MINUTE), "from 15:55 exactly, new things are held");
        assertTrue(owner.holding(restart - 1), "right up to the restart");
        assertFalse(owner.holding(restart), "at the restart the window closes (the server is going down)");
        assertFalse(owner.holding(at(2026, 6, 10, 10, 0)), "the rest of the day plays as normal");
        assertEquals("4:00 PM", owner.heldFor(restart - MINUTE), "while held, the restart's time for players");
        assertNull(owner.heldFor(restart - 6 * MINUTE), "and nothing when not held");
    }

    @Test
    void theHoldBeforeARestartJustAfterMidnightStartsTheDayBefore() {
        RestartHold late = hold(5, "00:02");
        assertFalse(late.holding(at(2026, 6, 10, 23, 56)), "23:56 is outside the hold");
        assertTrue(late.holding(at(2026, 6, 10, 23, 57)), "the 00:02 restart holds from 23:57 the day before");
        assertTrue(late.holding(at(2026, 6, 11, 0, 1)), "and across midnight");
        assertFalse(late.holding(at(2026, 6, 11, 0, 2)), "until the restart itself");
        assertEquals("12:02 AM", late.heldFor(at(2026, 6, 10, 23, 58)), "players read it as 12:02 AM");
        assertEquals("Next restart: 12:02 AM (new runs held from 11:57 PM)", late.status(at(2026, 6, 10, 20, 0)),
                "the status line shows the hold starting the evening before");
    }

    @Test
    void aHoldLongerThanTheGapBetweenTwoRestartsHoldsThroughBoth() {
        RestartHold twice = hold(5, "04:00", "04:03");
        assertTrue(twice.holding(at(2026, 6, 10, 3, 56)), "held before the first");
        assertTrue(twice.holding(at(2026, 6, 10, 4, 1)), "and still held between them: the second is 2 minutes off");
        assertEquals("4:03 AM", twice.heldFor(at(2026, 6, 10, 4, 1)), "naming the one that is next");
        assertFalse(twice.holding(at(2026, 6, 10, 4, 3)), "free again once both are past");
    }

    // ---- DST in America/Chicago ------------------------------------------------------------------

    @Test
    void theOwnersRestartsStayOnTheWallClockAcrossBothDstChanges() {
        RestartHold owner = hold(5, "04:00", "16:00");
        // 2026-03-08: 2:00 AM CST jumps to 3:00 AM CDT. 04:00 is 09:00 UTC that day, 10:00 UTC the day before.
        assertEquals(utc(2026, 3, 8, 9, 0), owner.next(at(2026, 3, 7, 20, 0)),
                "across spring-forward night the 04:00 restart is still at 4:00 on the wall (09:00 UTC)");
        assertTrue(owner.holding(utc(2026, 3, 8, 8, 55)), "held from 3:55 CDT, five real minutes before");
        assertFalse(owner.holding(utc(2026, 3, 8, 7, 55)), "not an hour early because of the change");
        assertEquals("4:00 AM", owner.heldFor(utc(2026, 3, 8, 8, 57)), "players read 4:00 AM");
        // 2026-11-01: 2:00 AM CDT falls back to 1:00 AM CST. 04:00 is 10:00 UTC that day.
        assertEquals(utc(2026, 11, 1, 10, 0), owner.next(at(2026, 10, 31, 20, 0)),
                "across fall-back night the 04:00 restart is at 4:00 CST (10:00 UTC)");
        assertTrue(owner.holding(utc(2026, 11, 1, 9, 55)), "held from 3:55 CST");
        assertFalse(owner.holding(utc(2026, 11, 1, 8, 55)), "not an hour early");
        assertEquals(at(2026, 11, 1, 16, 0), owner.next(utc(2026, 11, 1, 10, 0)),
                "and the afternoon one follows at 4:00 PM");
    }

    @Test
    void aTimeTheSpringForwardDaySkipsHappensAtTheFirstInstantAfterTheGap() {
        RestartHold skipped = hold(5, "02:30");
        long next = skipped.next(at(2026, 3, 8, 0, 0));
        assertEquals(utc(2026, 3, 8, 8, 0), next,
                "02:30 doesn't exist on 2026-03-08 in Chicago: it happens at 3:00 AM CDT, when the clock lands");
        assertEquals("3:00 AM", skipped.clock(next), "and players are told the time it really happens");
        assertTrue(skipped.holding(utc(2026, 3, 8, 7, 55)), "held from five real minutes before (1:55 AM CST)");
        assertFalse(skipped.holding(utc(2026, 3, 8, 7, 54)), "not before");
        assertEquals("Next restart: 3:00 AM (new runs held from 1:55 AM)", skipped.status(at(2026, 3, 8, 0, 0)),
                "the hold reads 1:55 AM: the gap is not counted as time to wait");
        assertEquals(at(2026, 3, 9, 2, 30), skipped.next(next), "the next day 02:30 exists again and is used as is");
    }

    @Test
    void aTimeTheFallBackDayRepeatsHappensOnceTheFirstTime() {
        RestartHold twice = hold(5, "01:30");
        long first = utc(2026, 11, 1, 6, 30); // 01:30 CDT
        assertEquals(first, twice.next(at(2026, 10, 31, 20, 0)), "the first 01:30 (CDT) is the restart");
        assertTrue(twice.holding(first - MINUTE), "held before it");
        assertEquals(at(2026, 11, 2, 1, 30), twice.next(first + 10 * MINUTE),
                "once it has passed, the repeated 01:30 (CST) is not a second restart: the next is tomorrow's");
        assertFalse(twice.holding(utc(2026, 11, 1, 7, 27)), "so nothing is held before the repeat");
    }

    @Test
    void theInstantOfATimeUsesTheZonesRulesForTheDate() {
        assertEquals(utc(2026, 7, 1, 21, 0), RestartHold.instant(LocalDate.of(2026, 7, 1), LocalTime.of(16, 0), CHICAGO),
                "summer: 4 PM CDT is 21:00 UTC");
        assertEquals(utc(2026, 1, 15, 22, 0), RestartHold.instant(LocalDate.of(2026, 1, 15), LocalTime.of(16, 0), CHICAGO),
                "winter: 4 PM CST is 22:00 UTC");
    }

    // ---- words and settings ----------------------------------------------------------------------

    @Test
    void timesReadTheWayPlayersReadThem() {
        RestartHold owner = hold(5, "04:00", "16:00");
        assertEquals("4:00 PM", owner.clock(at(2026, 6, 10, 16, 0)), "an afternoon time, no leading zero");
        assertEquals("12:00 PM", owner.clock(at(2026, 6, 10, 12, 0)), "noon");
        assertEquals("12:00 AM", owner.clock(at(2026, 6, 10, 0, 0)), "midnight");
        assertEquals("Next restart: 4:00 PM (new runs held from 3:55 PM)", owner.status(at(2026, 6, 10, 10, 0)),
                "the /hcm games status line");
    }

    @Test
    void theTimesAreSortedWithoutDuplicatesAndTheHoldIsKeptInRange() {
        RestartHold h = new RestartHold(Arrays.asList(LocalTime.of(16, 0), null, LocalTime.of(4, 0),
                LocalTime.of(16, 0)), CHICAGO, 0);
        assertEquals(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), h.times(),
                "sorted, the duplicate and the gap in the list ignored");
        assertEquals(1, h.holdMinutes(), "a hold of 0 would never hold: at least a minute");
        assertEquals(60, hold(90, "04:00").holdMinutes(), "at most an hour");
        assertEquals(5 * MINUTE, hold(5, "04:00").holdMillis(), "five minutes in milliseconds");
    }

    @Test
    void aConfigEntryIsATimeOnlyWhenItIsARealTwentyFourHourTime() {
        assertEquals(LocalTime.of(4, 0), RestartHold.parseTime("04:00"), "the shipped form");
        assertEquals(LocalTime.of(4, 0), RestartHold.parseTime("4:00"), "a single-digit hour is the same time");
        assertEquals(LocalTime.of(16, 0), RestartHold.parseTime(" 16:00 "), "spaces around it don't matter");
        assertEquals(LocalTime.MIDNIGHT, RestartHold.parseTime("00:00"), "midnight");
        assertEquals(LocalTime.of(23, 59), RestartHold.parseTime("23:59"), "the last minute of the day");
        for (Object junk : Arrays.asList("24:00", "12:60", "noon", "4", "4:0", "04:00:00", "4 PM", "-1:00", "",
                960, 4, null, List.of("04:00"))) {
            assertNull(RestartHold.parseTime(junk), "\"" + junk + "\" is not a 24-hour HH:mm time");
        }
    }
}
