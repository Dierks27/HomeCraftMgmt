package com.dierks.homecraft.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The players' calendar. A day ends at midnight in Minnesota — not at 7 PM, which is when a UTC
 * day ends there in summer — and the two DST changes are where "a day is 24 hours" stops being
 * true, so both are pinned.
 */
class GameClockTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static GameClock at(String instant) {
        return new GameClock(CHICAGO, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static long day(String date) {
        return LocalDate.parse(date).toEpochDay();
    }

    @Test
    void anEveningIsStillTheSameDay() {
        // 19:30 CDT on the 25th is already the 26th in UTC — the old rollover, mid-evening.
        GameClock clock = at("2026-09-26T00:30:00Z");
        assertEquals(day("2026-09-25"), clock.dayKey());
        assertEquals(Duration.ofMinutes(270).toMillis(), clock.msUntilNextDay(), "midnight is 4h30m away");
    }

    @Test
    void theFallBackDayIsTwentyFiveHoursLong() {
        // 00:30 CDT on Sunday 1 November 2026; the clocks go back at 02:00.
        GameClock clock = at("2026-11-01T05:30:00Z");
        assertEquals(day("2026-11-01"), clock.dayKey());
        assertEquals(Duration.ofHours(24).plusMinutes(30).toMillis(), clock.msUntilNextDay());

        // 23:30 CST the same day: the key is still the 1st, and midnight is half an hour off.
        GameClock late = at("2026-11-02T05:30:00Z");
        assertEquals(day("2026-11-01"), late.dayKey());
        assertEquals(Duration.ofMinutes(30).toMillis(), late.msUntilNextDay());
    }

    @Test
    void theSpringForwardDayIsTwentyThreeHoursLong() {
        // Local midnight on Sunday 14 March 2027 (CST); the clocks jump at 02:00.
        GameClock clock = at("2027-03-14T06:00:00Z");
        assertEquals(day("2027-03-14"), clock.dayKey());
        assertEquals(Duration.ofHours(23).toMillis(), clock.msUntilNextDay());
        // The next midnight is CDT — five hours behind UTC now, not six.
        assertEquals(Instant.parse("2027-03-15T05:00:00Z").toEpochMilli(), clock.millisAt(LocalDate.parse("2027-03-15")));
    }

    @Test
    void theWeekTurnsOverAtLocalMidnightToo() {
        // 23:30 CDT on Sunday 27 September: UTC says Monday already, the family says Sunday.
        GameClock clock = at("2026-09-28T04:30:00Z");
        assertEquals(day("2026-09-21"), clock.weekKey(DayOfWeek.MONDAY));
        assertEquals(Duration.ofMinutes(30).toMillis(), clock.msUntilNextWeek(DayOfWeek.MONDAY));
    }

    @Test
    void aWeekSpanningTheFallBackIsAnHourLonger() {
        // Noon CDT Saturday 31 October → midnight CST Monday 2 November: 36 local hours, 37 real.
        GameClock clock = at("2026-10-31T17:00:00Z");
        assertEquals(Duration.ofHours(37).toMillis(), clock.msUntilNextWeek(DayOfWeek.MONDAY));
    }

    @Test
    void aBadZoneFallsBackToUtcAndSaysSo() {
        List<String> warnings = new ArrayList<>();
        assertEquals(ZoneOffset.UTC, GameClock.parseZone("", warnings::add));
        assertEquals(ZoneOffset.UTC, GameClock.parseZone("America/Minneapolis", warnings::add));
        assertEquals(ZoneOffset.UTC, GameClock.parseZone(null, warnings::add));
        assertEquals(3, warnings.size(), "every fallback is logged: " + warnings);
        assertTrue(warnings.get(1).contains("America/Minneapolis"), "the warning names the bad value");

        assertEquals(CHICAGO, GameClock.parseZone(" America/Chicago ", warnings::add));
        assertEquals(3, warnings.size(), "a good zone logs nothing");
    }

    @Test
    void dayKeyAtReadsAnInstantInTheLocalZone() {
        GameClock clock = at("2026-09-26T12:00:00Z");
        long evening = Instant.parse("2026-09-27T02:00:00Z").toEpochMilli(); // 21:00 CDT on the 26th
        assertEquals(day("2026-09-26"), clock.dayKeyAt(evening));
        assertEquals(evening - Duration.ofHours(21).toMillis(), clock.startOfDay(day("2026-09-26")));
    }
}
