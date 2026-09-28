package com.dierks.homecraft.games;

import com.dierks.homecraft.games.ChanceRounds.Round;
import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-safe settlement for games of chance (spec §5.2, R2.11, R3.5) through the framework, against
 * a real database.
 *
 * <p>Pinned here: an instant play takes the tokens, writes the round and pays in one go, and a
 * refused one writes nothing; a payout is held to the game's cap; a multi-step round opens once,
 * resumes, and settles once — never paying twice; a raise over the day's limit takes nothing;
 * a round the player walked away from is finished by the game's exit rule (from its own data),
 * or gives the tokens back when the rule is missing or throws, logged once; the one-minute sweep
 * finishes only rounds untouched for ten minutes; start-up finishes offline players' rounds and
 * keeps the line for their next join; a game switched off still finishes its rounds.
 */
class ChanceRoundsTest {

    private Host host;
    private TestGame slots;
    private GamesService games;
    private Fake alex;
    private final AtomicInteger settled = new AtomicInteger();
    private ExitSettler settler = (seed, stake, data) -> {
        settled.incrementAndGet();
        return stake * 2;
    };

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 3, 10, 15, 0));
        build();
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 100);
    }

    private void build() {
        slots = GamesKit.chance();
        ChanceSettings settings = new ChanceSettings(true, List.of(1, 5, 10), 30);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "test_slots", settings);
        games = GamesKit.service(host, List.of(GamesKit.spec(slots, settings, (seed, stake, data) -> settler
                .payoutOnExit(seed, stake, data))));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void anInstantPlayTakesTheTokensWritesTheRoundAndPaysInOneGo() throws Exception {
        Integer outcome = games.rounds().play(alex.player, slots, 5, PlayGateTest.engine(12));
        assertEquals(12, outcome, "the engine's outcome comes back for the screen to show");
        assertEquals(107, host.balanceOf(alex.id), "5 in, 12 back");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED' AND stake = 5 AND payout = 12"),
                "one SETTLED round with the stake and the payout");
        assertEquals("v1;pays=12", one("SELECT data FROM game_rounds"), "the engine's data is stored with it");
        assertEquals(List.of("Test Slots: 5 in", "Test Slots: won 12"), details(),
                "the ledger says what went in and what came back");
        assertEquals(Refusal.SILENT, games.canStake(alex.player, slots, 5), "the play started the cooldown");
    }

    @Test
    void aPartialReturnIsNeverCalledAWin() throws Exception {
        games.rounds().play(alex.player, slots, 5, PlayGateTest.engine(3));
        assertEquals("Test Slots: 3 back", details().get(1), "3 back for 5 in is not a win");
    }

    @Test
    void aRefusedPlayWritesNothing() throws Exception {
        Fake sam = new Fake("Sam");
        host.give(sam.id, 3);
        assertNull(games.rounds().play(sam.player, slots, 5, PlayGateTest.engine(12)), "refused");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "no round");
        assertEquals(3, host.balanceOf(sam.id), "no tokens moved");
        assertTrue(sam.heard().contains("You need 2 more tokens."), "and they were told why");
    }

    @Test
    void aPlayThatWouldPayPastTheGamesCapIsRefusedAndTakesNothing() throws Exception {
        // The screen shows what the engine decided, so a round never pays a different number: an
        // engine past max_payout (a bug, or a screen left open while a reload lowered the cap) is
        // refused before anything moves.
        assertNull(games.rounds().play(alex.player, slots, 10, PlayGateTest.engine(100_000)), "refused");
        assertEquals(100, host.balanceOf(alex.id), "nothing taken, nothing paid");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "and no round written");
        assertTrue(alex.heard().contains("That game's settings just changed. Open it again to play."),
                "the player is told to reopen it: " + alex.heard());
        host.move(1_000);
        assertEquals(250, games.rounds().play(alex.player, slots, 10, PlayGateTest.engine(250)),
                "a payout at the cap (250) goes ahead");
        assertEquals(100 - 10 + 250, host.balanceOf(alex.id), "and is paid as decided");
    }

    @Test
    void aRoundOpensOnceResumesAndSettlesOnce() {
        Round round = games.rounds().open(alex.player, slots, 10, "v1;m=0.82");
        assertNotNull(round, "the round opens");
        assertEquals(90, host.balanceOf(alex.id), "the stake is taken at open");
        host.move(1_000);
        assertNull(games.rounds().open(alex.player, slots, 10, "v1"), "one OPEN round per game: resume it instead");
        assertEquals(90, host.balanceOf(alex.id), "and nothing more is taken");
        Round resumed = games.rounds().openRound(alex.id, slots.id());
        assertEquals(round.id(), resumed.id(), "opening the game again finds the same round");
        Round stepped = games.rounds().step(resumed, "v1;m=0.82;h");
        assertEquals("v1;m=0.82;h", stepped.data(), "a move is recorded before its card is shown");
        assertTrue(games.rounds().close(stepped, 19, null), "it settles");
        assertFalse(games.rounds().close(stepped, 19, null), "a second close is refused");
        assertEquals(109, host.balanceOf(alex.id), "and it paid exactly once");
        assertNull(games.rounds().step(stepped, "late"), "a move after it settled changes nothing");
    }

    @Test
    void aRaiseOverTheDaysLimitTakesNothing() throws Exception {
        games.breaks().setLimit(alex.id, 25);
        Round round = games.rounds().open(alex.player, slots, 10, "v1");
        assertFalse(games.rounds().raise(alex.player, round, 20), "10 in + 20 more would pass 25");
        assertEquals(90, host.balanceOf(alex.id), "nothing was taken");
        assertEquals(10, count("SELECT stake FROM game_rounds"), "the round's stake is unchanged");
        assertTrue(alex.heard().contains("That's your limit for today (25 tokens)."), "the player was told");
        assertTrue(games.rounds().raise(alex.player, round, 10), "10 more stays inside 25");
        assertEquals(80, host.balanceOf(alex.id), "and is taken");
        assertEquals(20, count("SELECT stake FROM game_rounds"), "the stake grows");
        assertTrue(games.rounds().close(round, 38, null), "settles");
        assertEquals("Test Slots: won 38", details().get(details().size() - 1), "the win is against all 20 put in");
    }

    @Test
    void aQuitFinishesTheRoundByItsExitRuleAndTheLineWaitsForTheNextJoin() {
        games.rounds().open(alex.player, slots, 10, "v1");
        games.onQuit(alex.player);
        assertEquals(1, settled.get(), "the game's exit rule ran once");
        assertEquals(110, host.balanceOf(alex.id), "and its payout was paid (10 in, 20 back)");
        assertTrue(alex.said.isEmpty(), "nothing is said to a player on their way out");
        games.onJoin(alex.player);
        host.runTasks();
        assertTrue(alex.heard().contains("Your Test Slots game from before was finished for you: 20 tokens back."),
                "the next join tells them what happened: " + alex.heard());
        alex.said.clear();
        games.onJoin(alex.player);
        host.runTasks();
        assertTrue(alex.said.isEmpty(), "a line is told once");
    }

    @Test
    void aMissingOrThrowingExitRuleGivesTheTokensBackAndIsLoggedOnce() throws Exception {
        settler = (seed, stake, data) -> {
            throw new IllegalStateException("bad data");
        };
        games.rounds().open(alex.player, slots, 10, "v1");
        games.rounds().settleOpen(alex.id);
        assertEquals(100, host.balanceOf(alex.id), "the stake came back");
        assertEquals("Test Slots: round returned", details().get(details().size() - 1), "under the game's own source");
        assertTrue(alex.heard().contains("was stopped, so your 10 tokens came back"), "said plainly");
        games.rounds().open(alex.player, slots, 10, "v1");
        games.rounds().settleOpen(alex.id);
        assertEquals(1, host.severe(), "a broken exit rule is logged once, not per round");
    }

    @Test
    void aGameWithNoExitRuleGivesTheTokensBack() {
        slots = GamesKit.chance();
        games = GamesKit.service(host, List.of(GamesKit.spec(slots, new ChanceSettings(true, List.of(10), 30), null)));
        games.rounds().open(alex.player, slots, 10, "v1");
        games.rounds().settleOpen(alex.id);
        assertEquals(100, host.balanceOf(alex.id), "no rule: the tokens go back");
    }

    @Test
    void theSweepFinishesOnlyRoundsUntouchedForTenMinutes() {
        Round round = games.rounds().open(alex.player, slots, 10, "v1");
        host.move(9 * 60_000L);
        games.rounds().step(round, "v1;h");
        host.move(9 * 60_000L);
        games.sweep();
        assertNotNull(games.rounds().openRound(alex.id, slots.id()), "a move nine minutes ago keeps it open");
        host.move(60_001L);
        games.sweep();
        assertNull(games.rounds().openRound(alex.id, slots.id()), "untouched for ten minutes: finished");
        assertTrue(alex.heard().contains("finished for you"), "an online player is told in chat");
    }

    @Test
    void startFinishesOfflinePlayersRoundsAndKeepsAFreshOneOfAnOnlinePlayer() {
        Fake sam = new Fake("Sam");
        host.give(sam.id, 50);
        host.online.put(sam.id, sam.player);
        games.rounds().open(alex.player, slots, 10, "v1");
        games.rounds().open(sam.player, slots, 10, "v1");
        host.online.remove(sam.id);
        GamesService restarted = GamesKit.service(host, List.of(GamesKit.spec(slots,
                new ChanceSettings(true, List.of(1, 5, 10), 30), settler)));
        restarted.start();
        assertNotNull(restarted.rounds().openRound(alex.id, slots.id()), "an online player's fresh round stays to resume");
        assertNull(restarted.rounds().openRound(sam.id, slots.id()), "an offline player's round is finished");
        assertEquals(60, host.balanceOf(sam.id), "by its exit rule");
        restarted.deliverNotices(sam.player);
        assertTrue(sam.heard().contains("finished for you"), "and the line waited for them");
    }

    @Test
    void aGameSwitchedOffStillFinishesItsRoundsByItsExitRule() {
        games.rounds().open(alex.player, slots, 10, "v1");
        games.fail(slots, new IllegalStateException("boom"));
        assertNotNull(games.rounds().openRound(alex.id, slots.id()), "the clean-up waits a tick");
        host.runTasks();
        assertNull(games.rounds().openRound(alex.id, slots.id()), "then the failed game's round is finished");
        assertEquals(110, host.balanceOf(alex.id), "by its own exit rule, though the game is off");
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String one(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private List<String> details() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = host.connection.prepareStatement(
                "SELECT detail FROM token_ledger WHERE source = 'ARCADE_SLOTS' ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
