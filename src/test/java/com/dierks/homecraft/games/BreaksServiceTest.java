package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.storage.GamesDao.BreakRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Take a break as a service (spec §4.2, §4.3, R1.9-R1.15): the pure rules (tested in
 * {@code BreaksTest}) applied to the database with a fixed local clock.
 *
 * <p>Pinned here: a lower limit applies now and a higher one waits the raise delay and then the
 * next local midnight (and is saved once it starts); a pending raise can be cancelled; a pause can
 * be lengthened, never shortened; a player can't lift what an admin set, and the lowest limit
 * wins; {@code clear} removes only the admin's values and {@code clear-own} only the player's;
 * the hook for the Scratch Ticket, Crates and token Card Packs applies the server limit only while
 * the games are on, checks the chance permission, and fails closed; "tokens put in today" counts
 * only the chance sources' debits since local midnight.
 */
class BreaksServiceTest {

    private Host host;
    private Breaks breaks;
    private Fake kid;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0)); // a Tuesday afternoon
        breaks = new Breaks(host);
        kid = new Fake("Kid");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void aLowerLimitAppliesNowAndAHigherOneWaitsThenStartsAtMidnight() {
        assertTrue(breaks.setLimit(kid.id, 50), "a first limit is stricter than none");
        assertEquals(50, breaks.limit(kid.id), "so it applies now");
        assertTrue(breaks.setLimit(kid.id, 25), "lowering");
        assertEquals(25, breaks.limit(kid.id), "applies now too");
        assertTrue(breaks.setLimit(kid.id, 100), "raising");
        assertEquals(25, breaks.limit(kid.id), "a raise does not apply now");
        BreakRow row = breaks.row(kid.id);
        assertEquals(100, row.pendingTokens(), "it waits as a pending change");
        long startDay = host.clock.dayKeyAt(GamesKit.at(2026, 3, 17, 15, 0)) + 1;
        assertEquals(startDay, row.pendingDay(), "seven days, then the next local midnight: Wednesday the 18th");
        host.time.now = GamesKit.at(2026, 3, 17, 23, 59);
        assertEquals(25, breaks.limit(kid.id), "still waiting one minute before that midnight");
        host.time.now = GamesKit.at(2026, 3, 18, 0, 1);
        assertEquals(100, breaks.limit(kid.id), "from local midnight the new limit holds");
        assertEquals(Breaks.NO_PENDING, breaks.row(kid.id).pendingTokens(), "and nothing is pending any more");
    }

    @Test
    void removingTheLimitIsARaiseAndCanBeCancelled() {
        breaks.setLimit(kid.id, 25);
        assertTrue(breaks.setLimit(kid.id, Breaks.NO_LIMIT), "asking for no limit");
        assertEquals(25, breaks.limit(kid.id), "waits like any raise");
        assertTrue(breaks.cancelPending(kid.id), "cancelling it");
        assertEquals(Breaks.NO_PENDING, breaks.row(kid.id).pendingTokens(), "drops the change");
        assertEquals(25, breaks.limit(kid.id), "and the current limit stays");
        breaks.setLimit(kid.id, 50);
        breaks.setLimit(kid.id, 10);
        assertEquals(Breaks.NO_PENDING, breaks.row(kid.id).pendingTokens(), "lowering clears a pending raise");
        assertEquals(10, breaks.limit(kid.id), "and applies now");
    }

    @Test
    void aPauseCanBeMadeLongerButNeverShorter() {
        assertTrue(breaks.pause(kid.id, 7), "a week");
        long week = breaks.pausedUntil(kid.id);
        assertEquals(GamesKit.at(2026, 3, 18, 0, 0), week, "ends at the first local midnight a full week away");
        assertTrue(breaks.pause(kid.id, 1), "asking for one day now");
        assertEquals(week, breaks.pausedUntil(kid.id), "can't shorten it");
        assertTrue(breaks.pause(kid.id, 30), "thirty days");
        assertTrue(breaks.pausedUntil(kid.id) > week, "lengthens it");
    }

    @Test
    void thePlayerCantLiftWhatAnAdminSetAndTheLowestLimitWins() {
        breaks.setAdminLimit(kid.id, 10);
        breaks.setLimit(kid.id, 50);
        assertEquals(10, breaks.limit(kid.id), "the admin's 10 is lower than their own 50");
        breaks.setLimit(kid.id, 5);
        assertEquals(5, breaks.limit(kid.id), "their own 5 is lower still");
        breaks.setAdminPause(kid.id, 1);
        breaks.setLimit(kid.id, Breaks.NO_LIMIT);
        host.time.now = GamesKit.at(2026, 3, 25, 12, 0);
        assertEquals(10, breaks.limit(kid.id), "removing their own limit leaves the admin's");
        assertEquals(0, breaks.pausedUntil(kid.id) > host.time.now ? 1 : 0, "the admin's one-day pause has ended");
    }

    @Test
    void clearRemovesOnlyTheAdminsValuesAndClearOwnOnlyThePlayers() {
        breaks.setLimit(kid.id, 20);
        breaks.pause(kid.id, 7);
        breaks.setAdminLimit(kid.id, 10);
        breaks.setAdminPause(kid.id, 30);
        assertTrue(breaks.clearAdmin(kid.id), "clear");
        BreakRow row = breaks.row(kid.id);
        assertEquals(Breaks.NO_LIMIT, row.adminTokens(), "the admin limit is gone");
        assertEquals(0, row.adminPausedUntil(), "the admin pause is gone");
        assertEquals(20, row.dailyTokens(), "their own limit stays");
        assertTrue(row.pausedUntil() > 0, "their own pause stays");
        breaks.setAdminLimit(kid.id, 10);
        assertTrue(breaks.clearOwn(kid.id), "clear-own");
        row = breaks.row(kid.id);
        assertEquals(Breaks.NO_LIMIT, row.dailyTokens(), "their own limit is gone");
        assertEquals(0, row.pausedUntil(), "their own pause is gone");
        assertEquals(10, row.adminTokens(), "the admin's limit stays");
    }

    @Test
    void theHookAppliesTheServerLimitOnlyWhileTheGamesAreOn() throws Exception {
        host.tokens.change(kid.id, 1_000, "ADMIN", "seed", host.time.now);
        host.tokens.change(kid.id, -95, "CRATE", "Crate", host.time.now);
        host.config = GamesKit.config(GamesKit.common(false, 100, 600, 6));
        assertNull(breaks.chanceAllowed(kid.player, 50),
                "games off and no break of their own: the Scratch Ticket and Crates work exactly as before");
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6));
        assertEquals(Refusal.personalLimit(100), breaks.chanceAllowed(kid.player, 10),
                "games on: the server's daily limit covers Crates too");
        host.config = GamesKit.config(GamesKit.common(false, 100, 600, 6));
        breaks.setLimit(kid.id, 90);
        assertEquals(Refusal.personalLimit(90), breaks.chanceAllowed(kid.player, 1),
                "their own limit applies even while the games are off");
        breaks.pause(kid.id, 1);
        assertEquals(Refusal.Reason.PAUSED, breaks.chanceAllowed(kid.player, 1).reason(), "so does their pause");
    }

    @Test
    void theHookChecksThePermissionAndFailsClosed() throws Exception {
        kid.denied.add("hcm.games.chance");
        assertEquals(Refusal.NO_CHANCE, breaks.chanceAllowed(kid.player, 1),
                "without hcm.games.chance the Scratch Ticket and Crates are closed to them too");
        kid.denied.clear();
        host.connection.close();
        assertEquals(Refusal.CHANCE_CLOSED, breaks.chanceAllowed(kid.player, 1),
                "limits that can't be read close games of chance rather than open them");
        assertNull(breaks.row(kid.id), "the row reads as unknown");
        assertEquals(0, breaks.limit(kid.id), "and the limit as nothing allowed");
    }

    @Test
    void tokensPutInTodayCountOnlyTheChanceSourcesDebitsSinceLocalMidnight() throws Exception {
        host.tokens.change(kid.id, 500, "ADMIN", "seed", GamesKit.at(2026, 3, 9, 12, 0));
        host.tokens.change(kid.id, -40, "LOTTO", "Scratch Ticket", GamesKit.at(2026, 3, 9, 23, 59));
        host.tokens.change(kid.id, -10, "LOTTO", "Scratch Ticket", GamesKit.at(2026, 3, 10, 0, 1));
        host.tokens.change(kid.id, -5, "PACK", "Card Pack", GamesKit.at(2026, 3, 10, 9, 0));
        host.tokens.change(kid.id, -7, "ARCADE_SLOTS", "Ore Slots: 7 in", GamesKit.at(2026, 3, 10, 9, 5));
        host.tokens.change(kid.id, 30, "ARCADE_SLOTS", "Ore Slots: won 30", GamesKit.at(2026, 3, 10, 9, 5));
        host.tokens.change(kid.id, -20, "PRIZE", "Prize Counter", GamesKit.at(2026, 3, 10, 10, 0));
        assertEquals(22, breaks.tokensInToday(kid.id),
                "10 + 5 + 7: yesterday's ticket, a payout and the Prize Counter don't count");
        Breaks.Today today = breaks.today(kid.id);
        assertNotNull(today, "readable");
        assertEquals(22, today.tokensIn(), "the snapshot agrees");
        assertEquals(100, today.limit(), "the server's limit");
        assertTrue(today.over(79) && !today.over(78), "22 + 78 = 100 is the most allowed");
    }
}
