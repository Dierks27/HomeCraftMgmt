package com.dierks.homecraft.games;

import com.dierks.homecraft.storage.GamesDao.BreakRow;
import com.dierks.homecraft.util.GameClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Take a break's rules (spec §4.3, R1.12, R1.13), on a fixed {@link GameClock} in Chicago.
 *
 * <p>Pinned: a pause ends at the first local midnight at least N full days away — from 23:59 and
 * from 00:01, for 1, 7 and 30 days, across both DST changes — so a one-day pause lasts 24 to 48
 * hours and always ends at a midnight the screen can name; a pause can be made longer, never
 * shorter; lowering a limit applies now and clears a waiting raise; a raise waits
 * {@code raise_delay_days} (never less than one) and then starts at midnight; a new raise restarts
 * the wait; {@code clear} removes only what an admin set and {@code clear-own} only the player's
 * own; the effective limit is the lowest of own, admin and server, ignoring the unset ones.
 */
class BreaksTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final long DAY_MS = 86_400_000L;
    private static final long HOUR_MS = 3_600_000L;
    private static final GameClock CLOCK = new GameClock(CHICAGO, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final UUID player = UUID.randomUUID();

    /** A local wall-clock time in Chicago as epoch millis. */
    private static long local(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static long midnight(String date) {
        return LocalDate.parse(date).atStartOfDay(CHICAGO).toInstant().toEpochMilli();
    }

    private static long day(String date) {
        return LocalDate.parse(date).toEpochDay();
    }

    private static void assertPause(String from, int days, String endsOn) {
        long now = local(from);
        long end = Breaks.pauseEnd(CLOCK, now, days);
        assertEquals(midnight(endsOn), end, days + " day(s) from " + from + " ends at midnight starting " + endsOn);
        assertTrue(end - now >= days * DAY_MS, "at least " + days + " full day(s) from " + from);
        assertTrue(end - now <= days * DAY_MS + 48 * HOUR_MS, "and never much more, from " + from);
    }

    @Test
    void aPauseEndsAtTheFirstMidnightAtLeastNDaysAway() {
        assertPause("2026-09-28T23:59", 1, "2026-09-30");
        assertPause("2026-09-28T00:01", 1, "2026-09-30");
        assertPause("2026-09-28T23:59", 7, "2026-10-06");
        assertPause("2026-09-28T00:01", 7, "2026-10-06");
        assertPause("2026-09-28T23:59", 30, "2026-10-29");
        assertPause("2026-09-28T00:01", 30, "2026-10-29");

        long oneDay = Breaks.pauseEnd(CLOCK, local("2026-09-28T23:59"), 1) - local("2026-09-28T23:59");
        assertEquals(24 * HOUR_MS + 60_000, oneDay, "from 23:59 a one-day pause is 24 hours and a minute");
        oneDay = Breaks.pauseEnd(CLOCK, local("2026-09-28T00:01"), 1) - local("2026-09-28T00:01");
        assertEquals(48 * HOUR_MS - 60_000, oneDay, "from 00:01 it is 47 hours and 59 minutes");
    }

    @Test
    void aPauseAcrossTheSpringForwardStillEndsAtMidnight() {
        // The clocks jump from 2:00 to 3:00 on Sunday 8 March 2026: that day has 23 hours.
        assertPause("2026-03-07T23:59", 1, "2026-03-10");
        assertPause("2026-03-08T00:01", 1, "2026-03-10");
        assertPause("2026-03-07T23:59", 7, "2026-03-16");
        assertPause("2026-03-08T00:01", 30, "2026-04-08");
    }

    @Test
    void aPauseAcrossTheFallBackStillEndsAtMidnight() {
        // The clocks go back from 2:00 to 1:00 on Sunday 1 November 2026: that day has 25 hours.
        assertPause("2026-10-31T23:59", 1, "2026-11-02");
        assertPause("2026-11-01T00:01", 1, "2026-11-02");
        assertPause("2026-10-15T00:01", 30, "2026-11-14");
        assertPause("2026-10-31T23:59", 7, "2026-11-08");
    }

    @Test
    void aPauseCanBeMadeLongerButNeverShorter() {
        long now = local("2026-09-28T12:00");
        BreakRow row = Breaks.pauseOwn(BreakRow.empty(player), Breaks.pauseEnd(CLOCK, now, 7));
        long week = row.pausedUntil();
        assertEquals(midnight("2026-10-06"), week);
        assertEquals(week, Breaks.pauseOwn(row, Breaks.pauseEnd(CLOCK, now, 1)).pausedUntil(), "a shorter pause is ignored");
        assertEquals(midnight("2026-10-29"), Breaks.pauseOwn(row, Breaks.pauseEnd(CLOCK, now, 30)).pausedUntil());
        assertTrue(Breaks.paused(row, now));
        assertFalse(Breaks.paused(row, week), "it ends at that midnight");
    }

    @Test
    void theAdminPauseUsesTheSameEndAndTheLaterPauseWins() {
        long now = local("2026-09-28T23:59");
        BreakRow row = Breaks.adminPause(BreakRow.empty(player), Breaks.pauseEnd(CLOCK, now, 1));
        assertEquals(midnight("2026-09-30"), row.adminPausedUntil());
        row = Breaks.pauseOwn(row, Breaks.pauseEnd(CLOCK, now, 7));
        assertEquals(midnight("2026-10-06"), Breaks.pausedUntil(row), "the later of the two");
        assertEquals("Tue 12 AM", Breaks.untilText(CLOCK, Breaks.pausedUntil(row)));
        assertEquals("Wed 12 AM", Breaks.untilText(CLOCK, row.adminPausedUntil()));
    }

    @Test
    void lowerNowRaiseLater() {
        long now = local("2026-09-28T23:59");
        BreakRow row = Breaks.setOwnLimit(BreakRow.empty(player), 50, CLOCK, now, 7);
        assertEquals(50, row.dailyTokens(), "a first limit is stricter than none: it applies now");
        assertEquals(Breaks.NO_PENDING, row.pendingTokens());

        row = Breaks.setOwnLimit(row, 100, CLOCK, now, 7);
        assertEquals(50, row.dailyTokens(), "a raise waits");
        assertEquals(100, row.pendingTokens());
        assertEquals(day("2026-10-06"), row.pendingDay(), "seven days, then the next midnight");
        assertEquals("Tue Oct 6", Breaks.dateText(row.pendingDay()));

        BreakRow lowered = Breaks.setOwnLimit(row, 25, CLOCK, now, 7);
        assertEquals(25, lowered.dailyTokens(), "lowering applies now");
        assertEquals(Breaks.NO_PENDING, lowered.pendingTokens(), "and clears the waiting raise");

        BreakRow removed = Breaks.setOwnLimit(row, Breaks.NO_LIMIT, CLOCK, now + 3 * DAY_MS, 7);
        assertEquals(50, removed.dailyTokens(), "removing the limit is a raise too");
        assertEquals(Breaks.NO_LIMIT, removed.pendingTokens());
        assertEquals(day("2026-10-09"), removed.pendingDay(), "a new raise replaces the old and restarts the wait");

        BreakRow cancelled = Breaks.cancelPending(removed);
        assertEquals(50, cancelled.dailyTokens(), "cancelling keeps the current limit, now");
        assertEquals(Breaks.NO_PENDING, cancelled.pendingTokens());
    }

    @Test
    void aWaitingRaiseStartsOnItsDay() {
        long now = local("2026-09-28T00:01");
        BreakRow row = Breaks.setOwnLimit(Breaks.setOwnLimit(BreakRow.empty(player), 10, CLOCK, now, 7), 25, CLOCK,
                now, 7);
        assertEquals(day("2026-10-06"), row.pendingDay());
        assertEquals(row, Breaks.settle(row, day("2026-10-05")), "not yet");
        BreakRow started = Breaks.settle(row, day("2026-10-06"));
        assertEquals(25, started.dailyTokens());
        assertEquals(Breaks.NO_PENDING, started.pendingTokens());
    }

    @Test
    void aRaiseAlwaysWaitsAtLeastADay() {
        long now = local("2026-09-28T23:59");
        assertEquals(day("2026-09-30"), Breaks.pendingDay(CLOCK, now, 0), "a delay of 0 is floored to 1");
        assertEquals(day("2026-09-30"), Breaks.pendingDay(CLOCK, now, 1));
        assertEquals(day("2026-10-06"), Breaks.pendingDay(CLOCK, local("2026-09-28T00:01"), 7));
        assertTrue(Breaks.isRaise(10, 25));
        assertTrue(Breaks.isRaise(10, Breaks.NO_LIMIT));
        assertFalse(Breaks.isRaise(25, 10));
        assertFalse(Breaks.isRaise(25, 25));
        assertFalse(Breaks.isRaise(Breaks.NO_LIMIT, 100), "any limit is stricter than none");
    }

    @Test
    void clearRemovesOnlyTheAdminsAndClearOwnOnlyThePlayers() {
        long now = local("2026-09-28T12:00");
        BreakRow row = Breaks.setOwnLimit(BreakRow.empty(player), 25, CLOCK, now, 7);
        row = Breaks.setOwnLimit(row, 50, CLOCK, now, 7);
        row = Breaks.pauseOwn(row, Breaks.pauseEnd(CLOCK, now, 7));
        row = Breaks.adminPause(Breaks.adminLimit(row, 10), Breaks.pauseEnd(CLOCK, now, 1));

        BreakRow cleared = Breaks.clearAdmin(row);
        assertEquals(Breaks.NO_LIMIT, cleared.adminTokens());
        assertEquals(0, cleared.adminPausedUntil());
        assertEquals(25, cleared.dailyTokens(), "the player's own limit stays");
        assertEquals(50, cleared.pendingTokens(), "and their waiting change");
        assertEquals(row.pausedUntil(), cleared.pausedUntil(), "and their own pause");

        BreakRow own = Breaks.clearOwn(row);
        assertEquals(Breaks.NO_LIMIT, own.dailyTokens());
        assertEquals(Breaks.NO_PENDING, own.pendingTokens());
        assertEquals(0, own.pausedUntil());
        assertEquals(10, own.adminTokens(), "what an admin set stays");
        assertEquals(row.adminPausedUntil(), own.adminPausedUntil());
    }

    @Test
    void theEffectiveLimitIsTheLowestThatIsSet() {
        assertEquals(Breaks.NO_LIMIT, Breaks.effectiveLimit(Breaks.NO_LIMIT, Breaks.NO_LIMIT, 0), "none at all");
        assertEquals(100, Breaks.effectiveLimit(Breaks.NO_LIMIT, Breaks.NO_LIMIT, 100), "the server's");
        assertEquals(25, Breaks.effectiveLimit(25, Breaks.NO_LIMIT, 100), "the player's own, lower");
        assertEquals(10, Breaks.effectiveLimit(25, 10, 100), "the admin's wins when lower");
        assertEquals(10, Breaks.effectiveLimit(Breaks.NO_LIMIT, 10, 0));
        assertEquals(0, Breaks.effectiveLimit(50, 0, 100), "an admin limit of 0 means none today");
        assertEquals(50, Breaks.effectiveLimit(50, Breaks.NO_LIMIT, 0), "server 0 = off");
    }
}
