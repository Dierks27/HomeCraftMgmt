package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.TokenService.StreakDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login streak moves from UTC days to local days. The stored {@code last_streak_day} of
 * everyone who claimed before the switch is a UTC epoch day, which in a Minnesota evening is
 * already TOMORROW. Two promises: nobody is paid twice for one local day, and nobody's streak
 * breaks because the calendar moved under them.
 */
class StreakTransitionTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static long utcDay(String instant) {
        return Instant.parse(instant).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    private static long localDay(String instant) {
        return Instant.parse(instant).atZone(CHICAGO).toLocalDate().toEpochDay();
    }

    @Test
    void aUtcDayAheadOfLocalTodayCountsAsClaimedAndIsRewound() {
        // Claimed at 19:30 CDT on the 25th under the old build: stored as UTC 26 September.
        long stored = utcDay("2026-09-26T00:30:00Z");
        // The new build runs at 20:00 CDT the same evening.
        long today = localDay("2026-09-26T01:00:00Z");
        assertEquals(stored - 1, today, "UTC is a day ahead in the evening");

        StreakDecision d = TokenService.decideStreak(4, stored, today);
        assertFalse(d.pay(), "already claimed today — never pay twice");
        assertEquals(today, d.rewind(), "pull the stored day back so tomorrow is exactly one later");
        assertEquals(4, d.streak());

        // After local midnight: the rewound day is yesterday, so the streak carries on.
        StreakDecision next = TokenService.decideStreak(4, today, today + 1);
        assertTrue(next.pay());
        assertEquals(5, next.streak(), "the streak survives the switch");
    }

    @Test
    void aUtcDayEqualToLocalTodayIsAlreadyClaimed() {
        long today = LocalDate.parse("2026-09-26").toEpochDay();
        StreakDecision d = TokenService.decideStreak(2, today, today);
        assertFalse(d.pay());
        assertEquals(-1, d.rewind(), "nothing to rewind");
    }

    @Test
    void yesterdayContinuesAndAGapRestarts() {
        long today = LocalDate.parse("2026-09-26").toEpochDay();
        assertEquals(new StreakDecision(true, 7, -1), TokenService.decideStreak(6, today - 1, today));
        assertEquals(new StreakDecision(true, 1, -1), TokenService.decideStreak(6, today - 2, today));
        assertEquals(new StreakDecision(true, 1, -1), TokenService.decideStreak(0, 0, today),
                "a first-ever claim is day 1");
    }

    @Test
    void stayingOnlineAcrossMidnightPaysOnceForTheNewDay() {
        long today = LocalDate.parse("2026-09-26").toEpochDay();
        // Claimed at 21:00; the five-minute tick keeps asking all evening.
        StreakDecision evening = TokenService.decideStreak(3, today, today);
        assertFalse(evening.pay());
        // 00:04 — the first tick of the new day pays…
        StreakDecision midnight = TokenService.decideStreak(3, today, today + 1);
        assertTrue(midnight.pay());
        assertEquals(4, midnight.streak());
        // …and the next tick, with that claim stored, does not.
        assertFalse(TokenService.decideStreak(4, today + 1, today + 1).pay());
    }
}
