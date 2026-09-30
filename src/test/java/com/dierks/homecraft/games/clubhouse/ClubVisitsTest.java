package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is in the Clubhouse and for how long (CLUBHOUSE-SPEC §4, §8): {@code max_minutes} with a
 * warning a minute before, a race or a party stopping the clock, the restart hold sending everyone
 * home a minute after it starts (a late arrival too) and always before the restart's own minute, and
 * an arrival that never lands.
 */
class ClubVisitsTest {

    private static final long MIN = ClubVisits.MINUTE;
    private final UUID ava = UUID.randomUUID();
    private final UUID ben = UUID.randomUUID();

    private static List<ClubVisits.What> whats(List<ClubVisits.Act> acts, UUID who) {
        return acts.stream().filter(a -> a.player().equals(who)).map(ClubVisits.Act::what).toList();
    }

    @Test
    void maxMinutesWithAWarningAMinuteBefore() {
        ClubVisits v = new ClubVisits();
        v.enter(ava, "Ava", ClubVisits.Kind.VISIT, 0);
        assertEquals(List.of(), v.second(28 * MIN, 30, id -> false, false), "28 minutes: nothing yet");
        assertEquals(List.of(ClubVisits.What.WARN_IDLE), whats(v.second(29 * MIN, 30, id -> false, false), ava),
                "a friendly warning a minute before");
        assertEquals(List.of(), whats(v.second(29 * MIN + 30_000, 30, id -> false, false), ava), "once");
        assertEquals(List.of(ClubVisits.What.HOME_IDLE), whats(v.second(30 * MIN, 30, id -> false, false), ava),
                "home at 30 minutes");
    }

    @Test
    void aRaceOrAPartyGoingStopsTheClock() {
        ClubVisits v = new ClubVisits();
        v.enter(ava, "Ava", ClubVisits.Kind.PARTY, 0);
        Set<UUID> busy = new HashSet<>(Set.of(ava));
        for (long t = MIN; t <= 60 * MIN; t += MIN) {
            assertEquals(List.of(), v.second(t, 30, busy::contains, false), "in a party for an hour: never sent home");
        }
        busy.clear();
        assertEquals(List.of(), v.second(61 * MIN, 30, busy::contains, false), "the clock starts when the party ends");
        assertEquals(List.of(ClubVisits.What.WARN_IDLE), whats(v.second(89 * MIN, 30, busy::contains, false), ava),
                "29 minutes later: the warning");
    }

    @Test
    void theRestartHoldSendsEveryoneHomeAMinuteAfterItStarts() {
        ClubVisits v = new ClubVisits();
        v.enter(ava, "Ava", ClubVisits.Kind.VISIT, 0);
        v.enter(ben, "Ben", ClubVisits.Kind.PARTY, 0);
        long hold = 10 * MIN;
        List<ClubVisits.Act> first = v.second(hold, 30, id -> true, true);
        assertEquals(List.of(ClubVisits.What.WARN_HOLD), whats(first, ava), "warned when the hold starts");
        assertEquals(List.of(ClubVisits.What.WARN_HOLD), whats(first, ben), "busy or not");
        assertEquals(List.of(), v.second(hold + 30_000, 30, id -> true, true), "nothing in the minute");
        List<ClubVisits.Act> home = v.second(hold + MIN, 30, id -> true, true);
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(home, ava), "home at hold start + 1 minute");
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(home, ben), "everyone");
        UUID cal = UUID.randomUUID();
        v.enter(cal, "Cal", ClubVisits.Kind.PARTY, hold + 3 * MIN); // in late some other way (a seat handed back)
        assertEquals(List.of(ClubVisits.What.WARN_HOLD), whats(v.second(hold + 3 * MIN, 30, id -> true, true), cal),
                "a late arrival is warned at once");
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(v.second(hold + 4 * MIN, 30, id -> true, true), cal),
                "and gets the same minute: never here across a restart");
    }

    /**
     * The PRODBUG the journeys found: a visitor warned less than a minute before the restart (a one-minute
     * hold, or one who got in late) was sent home only in the restart's own minute, maybe after the server
     * stopped. Home now comes {@link ClubVisits#HOME_BEFORE_RESTART} before the restart at the latest.
     */
    @Test
    void theHoldsMinuteNeverRunsIntoTheRestartsOwnMinute() {
        ClubVisits v = new ClubVisits();
        long restart = 60 * MIN;
        long lastCall = restart - ClubVisits.HOME_BEFORE_RESTART;
        v.enter(ava, "Ava", ClubVisits.Kind.VISIT, 0);
        v.enter(ben, "Ben", ClubVisits.Kind.PARTY, 0);
        assertEquals(List.of(), v.second(restart - MIN - 1_000, 30, id -> true, false, restart), "no hold yet");
        List<ClubVisits.Act> warn = v.second(restart - MIN + 500, 30, id -> true, true, restart); // a one-minute hold
        assertEquals(List.of(ClubVisits.What.WARN_HOLD), whats(warn, ava), "warned as the hold starts");
        assertEquals(List.of(ClubVisits.What.WARN_HOLD), whats(warn, ben), "everyone");
        assertEquals(List.of(), v.second(lastCall - 1, 30, id -> true, true, restart),
                "nobody sent home before the last call");
        List<ClubVisits.Act> home = v.second(lastCall, 30, id -> true, true, restart);
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(home, ava),
                "home at the last call, not a minute after the warning (in the restart's own minute)");
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(home, ben), "everyone");
        v.leave(ava);
        v.leave(ben);

        UUID cal = UUID.randomUUID();
        v.enter(cal, "Cal", ClubVisits.Kind.PARTY, restart - 3_000); // in late, after the last call
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(v.second(restart - 3_000, 30, id -> true, true, restart),
                cal), "home at once, never told 'in 1 minute' with seconds left");
        v.leave(cal);
        UUID dee = UUID.randomUUID();
        v.enter(dee, "Dee", ClubVisits.Kind.VISIT, restart + 20_000); // in the restart's own minute, not stopped yet
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(v.second(restart + 20_000, 30, id -> true, true, restart),
                dee), "the restart's own minute: home at once");
        v.leave(dee);

        UUID eve = UUID.randomUUID();
        long hold = restart - 5 * MIN; // the shipped five-minute hold
        v.enter(eve, "Eve", ClubVisits.Kind.VISIT, hold - MIN);
        assertEquals(List.of(ClubVisits.What.WARN_HOLD), whats(v.second(hold, 30, id -> true, true, restart), eve),
                "a five-minute hold warns as it starts");
        assertEquals(List.of(), v.second(hold + 30_000, 30, id -> true, true, restart), "nothing in the minute");
        assertEquals(List.of(ClubVisits.What.HOME_HOLD), whats(v.second(hold + MIN, 30, id -> true, true, restart), eve),
                "and still sends home a whole minute after the warning, well before the restart");
        v.leave(eve);
        UUID fay = UUID.randomUUID();
        v.enter(fay, "Fay", ClubVisits.Kind.VISIT, restart + MIN);
        assertEquals(List.of(), v.second(restart + MIN, 30, id -> false, false, restart + 24 * 60 * MIN),
                "once the restart's minute is over (no hold), nobody is sent anywhere");
    }

    @Test
    void anArrivalThatNeverLandsIsLostAndOneSeenIsNot() {
        ClubVisits v = new ClubVisits();
        v.enter(ava, "Ava", ClubVisits.Kind.PARTY, 1_000);
        v.enter(ben, "Ben", ClubVisits.Kind.PARTY, 1_000);
        v.seen(ben);
        assertFalse(v.lost(ava, 1_000 + ClubVisits.ARRIVAL_MS - 1), "still on the way");
        assertTrue(v.lost(ava, 1_000 + ClubVisits.ARRIVAL_MS), "never seen in the room: lost (sent home, no ghost)");
        assertFalse(v.lost(ben, 1_000_000), "seen: never lost");
        v.recheck(ben, 2_000_000);
        assertTrue(v.lost(ben, 2_000_000 + ClubVisits.ARRIVAL_MS), "moved again: checked again");
    }

    @Test
    void visitorsKindsSpectatorsSpotsAndLeaving() {
        ClubVisits v = new ClubVisits();
        ClubVisits.Visit a = v.enter(ava, "Ava", ClubVisits.Kind.VISIT, 0);
        ClubVisits.Visit b = v.enter(ben, "Ben", ClubVisits.Kind.NIGHT, 0);
        assertEquals(0, a.spot(), "arrival spots in turn");
        assertEquals(1, b.spot(), "the next one");
        v.spectator(ava, true);
        assertTrue(v.get(ava).spectator(), "a Watch makes a spectator");
        assertEquals(a, v.enter(ava, "Ava", ClubVisits.Kind.PARTY, 5), "someone already here only changes kind");
        assertEquals(ClubVisits.Kind.PARTY, v.get(ava).kind(), "now a party racer");
        assertEquals(2, v.size(), "two here");
        assertEquals(b, v.leave(ben), "out");
        assertNull(v.leave(ben), "once");
        assertFalse(v.in(ben), "gone");
        v.clear();
        assertEquals(0, v.size(), "everyone out");
    }
}
