package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.holdem.Decks;
import com.dierks.homecraft.games.chance.holdem.Holdem;
import com.dierks.homecraft.games.chance.holdem.HoldemHand;
import com.dierks.homecraft.games.chance.holdem.HoldemSettings;
import com.dierks.homecraft.games.chance.holdem.HoldemTable;
import com.dierks.homecraft.games.chance.holdem.HoldemTable.Kind;
import com.dierks.homecraft.games.chance.holdem.HoldemTable.Phase;
import com.dierks.homecraft.games.chance.holdem.HoldemTable.Seat;
import com.dierks.homecraft.games.cpu.CpuRoster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0, Texas Hold'em's tables, run for real over the framework's gate, rounds and database:
 *
 * <ul>
 *   <li>a tokens table's buy-in goes in through the play gate as the seat's round; getting up pays back the chips; the
 *       tokens across the players add up to what they started with, hand after hand;</li>
 *   <li>the server stopping mid-hand gives everyone exactly what they had before the hand;</li>
 *   <li>a player who leaves mid-hand leaves their chips in the pot (as carries in the others' rounds), so a stop then
 *       still adds up, and so does the hand played out;</li>
 *   <li>a round finished elsewhere calls the hand off and puts every seat back to what its round says;</li>
 *   <li>the money table moves dollars the same way; the day's limits and Take a break hold;</li>
 *   <li>the friendly table moves nothing, and is where NPC players sit (never at a stakes table).</li>
 * </ul>
 */
class HoldemTableTest {

    private Host host;
    private GamesService games;
    private Holdem game;
    private final List<Fake> players = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        setUp(HoldemSettings.defaults(), 1000);
    }

    private void setUp(HoldemSettings settings, int chanceDailyTokens) throws Exception {
        if (host != null) {
            host.connection.close();
        }
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.bankOn = true;
        host.config = GamesKit.config(GamesKit.common(true, chanceDailyTokens, 600, 6), "holdem", settings);
        games = GamesKit.service(host, List.of(Holdem.SPEC));
        games.start(); // running: a quit reaches the game before its rounds are finished, as on the server
        game = (Holdem) games.game("holdem");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 500);
        host.money.put(f.id, 300.0);
        games.parlorGuest(f.id);
        players.add(f);
        return f;
    }

    private Seat sit(HoldemTable t, Fake f) {
        return t.sit(f.player, () -> { });
    }

    /** Let the table gather and deal (its once-a-second clock), with {@code deck} as the next hand's cards. */
    private void deal(HoldemTable t, int[] deck) {
        if (deck != null) {
            t.nextDeck(deck);
        }
        for (int i = 0; i < 6 && t.phase() != Phase.PLAYING; i++) {
            host.move(1_000);
            t.tick(null);
        }
        assertEquals(Phase.PLAYING, t.phase(), "a hand is dealt");
    }

    private Fake on(HoldemTable t) {
        Seat turn = t.turn();
        assertNotNull(turn, "someone's turn");
        for (Fake f : players) {
            if (f.id.equals(turn.id())) {
                return f;
            }
        }
        throw new AssertionError("the turn is nobody's");
    }

    /** Everyone checks or calls to the end of the hand. */
    private void checkDown(HoldemTable t) {
        for (int i = 0; i < 40 && t.phase() == Phase.PLAYING; i++) {
            assertTrue(t.checkCall(on(t).player));
        }
    }

    private int tokens() {
        int sum = 0;
        for (Fake f : players) {
            sum += host.balanceOf(f.id);
        }
        return sum;
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** The chips every open Hold'em round holds (its seat's chips plus any carry), by the exit rule. */
    private int chipsInOpenRounds() throws Exception {
        int sum = 0;
        for (ChanceRounds.Round r : host.dao.openRounds()) {
            if (r.gameId().equals("holdem")) {
                sum += HoldemTable.settleOnExit(r.seed(), r.stake(), r.data());
            }
        }
        return sum;
    }

    // ---- the tokens table -------------------------------------------------------------------------------

    @Test
    void aBuyInIsARoundAndGettingUpPaysTheChipsBack() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(t, alex);
        sit(t, sam);
        assertFalse(t.buyIn(alex.player, 30), "only the configured buy-ins");
        assertTrue(t.buyIn(alex.player, 50));
        assertTrue(t.buyIn(sam.player, 50));
        assertEquals(450, host.balanceOf(alex.id), "the buy-in went in");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'holdem' AND state = 'OPEN'"));
        assertEquals(100, chipsInOpenRounds());
        // Alex is the button (the first seat): the small blind, first to act heads-up
        deal(t, Decks.deck(2, 0, List.of("As Ad", "Ks Kd"), "2c 7d 9h Jc 3s"));
        assertEquals(alex.id, t.turn().id());
        assertTrue(t.allIn(alex.player));
        assertTrue(t.checkCall(sam.player));
        assertEquals(Phase.BETWEEN, t.phase(), "the Aces held up");
        assertEquals(100, t.seat(alex.id).stack());
        assertEquals(0, t.seat(sam.id).stack());
        assertFalse(t.seat(sam.id).boughtIn(), "no chips left: Sam's round is over (paid nothing)");
        assertTrue(sam.heard().contains("your chips are gone this time"), sam.heard());
        assertTrue(alex.heard().contains("Alex wins the pot of 100 chips with a Pair of Aces"), alex.heard());
        assertEquals(100, chipsInOpenRounds(), "Alex's round holds the whole table");
        t.leave(alex.id);
        assertNull(t.seat(alex.id), "getting up between hands");
        assertEquals(550, host.balanceOf(alex.id), "100 chips back as 100 tokens");
        assertEquals(1000, tokens(), "nothing made, nothing kept");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'holdem' AND state = 'SETTLED'"));
        assertTrue(alex.heard().contains("you got up with 100 chips: 100 tokens back (50 in)"), alex.heard());
    }

    @Test
    void handAfterHandTheTokensAddUp() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        Fake kim = player("Kim");
        for (Fake f : players) {
            sit(t, f);
            assertTrue(t.buyIn(f.player, 100));
        }
        SplittableRandom r = new SplittableRandom(7);
        t.shuffler(r);
        int hands = 0;
        for (int n = 0; n < 400 && hands < 12; n++) {
            if (t.phase() != Phase.PLAYING) {
                host.move(1_000);
                t.tick(null);
                continue;
            }
            hands++;
            for (int k = 0; k < 60 && t.phase() == Phase.PLAYING; k++) {
                Fake f = on(t);
                int pick = r.nextInt(6);
                if (pick == 0 && t.fold(f.player)) {
                    continue;
                }
                if (pick == 1) {
                    t.raiseTo(f.player, t.hand().currentBet() + 6);
                } else if (pick == 2 && r.nextInt(4) == 0) {
                    t.allIn(f.player);
                } else {
                    t.checkCall(f.player);
                }
            }
            int behind = 0;
            for (Seat s : t.seats()) {
                behind += s.stack();
            }
            assertEquals(behind, chipsInOpenRounds(), "every seat's round says its chips");
            assertEquals(1500, tokens() + behind, "tokens out plus chips on the table: always the same");
            for (Fake f : players) {
                Seat s = t.seat(f.id);
                if (s != null && s.stack() == 0 && t.phase() != Phase.PLAYING) {
                    host.move(1_000);
                    assertTrue(t.buyIn(f.player, 20), "a seat with no chips buys in again");
                }
            }
        }
        assertTrue(hands >= 5, "hands were played: " + hands);
        for (Fake f : players) {
            t.leave(f.id);
        }
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
        int in = count("SELECT COALESCE(SUM(stake), 0) FROM game_rounds WHERE game = 'holdem'");
        int back = count("SELECT COALESCE(SUM(payout), 0) FROM game_rounds WHERE game = 'holdem'");
        assertEquals(in, back, "the Arcade kept nothing: every token put in came back to someone");
        assertEquals(1500, tokens());
    }

    @Test
    void theServerStoppingMidHandGivesEveryoneWhatTheyHadBeforeIt() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(t, alex);
        sit(t, sam);
        t.buyIn(alex.player, 50);
        t.buyIn(sam.player, 100);
        deal(t, null);
        t.raiseTo(on(t).player, 20);
        t.checkCall(on(t).player);
        assertEquals(HoldemHand.Street.FLOP, t.hand().street());
        assertEquals(40, t.hand().pot());
        // the server goes down: both are gone, the table is only memory
        host.online.clear();
        GamesService restarted = GamesKit.service(host, List.of(Holdem.SPEC));
        restarted.start();
        assertEquals(500, host.balanceOf(alex.id), "the hand never happened: Alex's 50 back");
        assertEquals(500, host.balanceOf(sam.id), "and Sam's 100");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
    }

    @Test
    void aPlayerWhoLeavesMidHandLeavesTheirChipsInThePot() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        Fake kim = player("Kim");
        for (Fake f : players) {
            sit(t, f);
            t.buyIn(f.player, 50);
        }
        // Alex the button, Sam the small blind, Kim the big blind
        deal(t, Decks.deck(3, 0, List.of("As Ad", "Ks Kd", "2s 7d"), "2c 8d 9h Jc 3s"));
        assertEquals(alex.id, t.turn().id());
        t.raiseTo(alex.player, 20);
        t.checkCall(sam.player);
        assertEquals(kim.id, t.turn().id());
        games.onQuit(kim.player); // Kim leaves the server on her turn, 2 chips in
        host.online.remove(kim.id);
        assertNull(t.seat(kim.id));
        assertEquals(498, host.balanceOf(kim.id), "Kim got up with what she had behind: 48");
        assertEquals(102, chipsInOpenRounds(), "her 2 chips stay at the table, as carries");
        assertEquals(HoldemHand.Street.FLOP, t.hand().street(), "the hand goes on");
        // now the server stops: the carries go back with the others' own chips
        host.online.clear();
        GamesService restarted = GamesKit.service(host, List.of(Holdem.SPEC));
        restarted.start();
        assertEquals(1500, tokens(), "every token accounted for");
        assertEquals(501, host.balanceOf(alex.id), "50 and half of Kim's 2");
        assertEquals(501, host.balanceOf(sam.id));
    }

    @Test
    void theHandPlayedOutAfterSomeoneLeftAddsUpToo() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        Fake kim = player("Kim");
        for (Fake f : players) {
            sit(t, f);
            t.buyIn(f.player, 50);
        }
        deal(t, Decks.deck(3, 0, List.of("As Ad", "Ks Kd", "2s 7d"), "2c 8d 9h Jc 3s"));
        t.raiseTo(alex.player, 20);
        t.checkCall(sam.player);
        t.leave(kim.id); // Kim closes the screen on her turn: away, still in
        assertNotNull(t.seat(kim.id), "away, not gone");
        assertTrue(t.seat(kim.id).away());
        host.move(1_000);
        t.tick(null); // the away clock starts
        host.move(10_000);
        t.tick(null); // and runs out: Kim, facing a raise, folds
        assertEquals(HoldemHand.Street.FLOP, t.hand().street());
        checkDown(t);
        assertEquals(Phase.BETWEEN, t.phase());
        assertEquals(50 + 20 + 2, t.seat(alex.id).stack(), "the Aces take Sam's 20 and Kim's 2");
        host.move(6_000);
        t.tick(null); // between hands: the away seat gets up
        assertNull(t.seat(kim.id));
        assertEquals(498, host.balanceOf(kim.id));
        assertTrue(kim.heard().contains("you were away from the table"), kim.heard());
        for (Fake f : List.of(alex, sam)) {
            t.leave(f.id);
        }
        assertEquals(1500, tokens());
        assertEquals(522, host.balanceOf(alex.id));
    }

    @Test
    void aRoundFinishedElsewhereCallsTheHandOff() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(t, alex);
        sit(t, sam);
        t.buyIn(alex.player, 50);
        t.buyIn(sam.player, 50);
        deal(t, Decks.deck(2, 0, List.of("As Ad", "Ks Kd"), "2c 7d 9h Jc 3s"));
        ChanceRounds.Round sams = games.rounds().openRound(sam.id, "holdem");
        games.rounds().finishNow(sams); // the sweep, an admin: Sam is paid what his round says
        assertEquals(500, host.balanceOf(sam.id));
        t.allIn(alex.player);
        t.checkCall(sam.player);
        assertNull(t.seat(sam.id), "Sam's seat is gone: his round was paid");
        assertEquals(50, t.seat(alex.id).stack(), "the hand was called off: Alex has his 50");
        assertTrue(alex.heard().contains("that hand was called off"), alex.heard());
        t.leave(alex.id);
        assertEquals(1000, tokens());
    }

    @Test
    void theAdminsStopPaysEveryoneTheirChipsBeforeTheHand() throws Exception {
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(t, alex);
        sit(t, sam);
        t.buyIn(alex.player, 50);
        t.buyIn(sam.player, 20);
        deal(t, null);
        t.allIn(on(t).player);
        assertEquals(2, t.stopAll());
        assertTrue(t.seats().isEmpty());
        assertEquals(500, host.balanceOf(alex.id));
        assertEquals(500, host.balanceOf(sam.id));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
    }

    // ---- the limits ---------------------------------------------------------------------------------------

    @Test
    void theBuyInsADayAndTakeABreakHold() throws Exception {
        setUp(new HoldemSettings(true, List.of(20, 50, 100), 1, 2, 200, 1, 4, 10, 5), 1000);
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(t, alex);
        sit(t, sam);
        assertTrue(t.buyIn(alex.player, 20));
        t.leave(alex.id);
        sit(t, alex);
        host.move(1_000);
        assertFalse(t.buyIn(alex.player, 20), "one buy-in a day");
        assertTrue(alex.heard().contains("Texas Hold'em"), "told the day's limit: " + alex.heard());
        // Sam buys in, then starts a break: at the next deal he gets up with his chips
        assertTrue(t.buyIn(sam.player, 50));
        Fake kim = player("Kim");
        sit(t, kim);
        assertTrue(t.buyIn(kim.player, 50));
        assertTrue(games.breaks().pause(sam.id, 1));
        for (int i = 0; i < 5; i++) {
            host.move(1_000);
            t.tick(null);
        }
        assertNull(t.seat(sam.id), "on a break: up from the table at the next deal");
        assertEquals(500, host.balanceOf(sam.id), "with his 50 back");
        assertTrue(sam.heard().contains("break"), sam.heard());
        assertEquals(Phase.WAITING, t.phase(), "and no hand of one");
    }

    @Test
    void theTokenBuyInCountsTowardTheDaysTokenLimit() throws Exception {
        setUp(HoldemSettings.defaults(), 60);
        HoldemTable t = game.table(Kind.TOKENS);
        Fake alex = player("Alex");
        sit(t, alex);
        assertFalse(t.buyIn(alex.player, 100), "over the day's 60 tokens");
        assertTrue(t.buyIn(alex.player, 50));
    }

    // ---- the money table ----------------------------------------------------------------------------------

    @Test
    void theMoneyTableMovesDollarsTheSameWay() throws Exception {
        HoldemTable t = game.table(Kind.MONEY);
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(t, alex);
        sit(t, sam);
        assertFalse(t.buyIn(alex.player, 60), "over the Parlor's biggest stake");
        assertTrue(t.buyIn(alex.player, 25));
        assertTrue(t.buyIn(sam.player, 25));
        assertEquals(275.0, host.money.get(alex.id));
        assertEquals(500, host.balanceOf(alex.id), "no tokens move at the money table");
        deal(t, Decks.deck(2, 0, List.of("As Ad", "Ks Kd"), "2c 7d 9h Jc 3s"));
        t.allIn(alex.player);
        t.checkCall(sam.player);
        t.leave(alex.id);
        t.leave(sam.id);
        assertEquals(325.0, host.money.get(alex.id), "$50 back for $25 in");
        assertEquals(275.0, host.money.get(sam.id));
        assertEquals(600.0, host.money.get(alex.id) + host.money.get(sam.id), "every dollar accounted for");
        assertTrue(alex.heard().contains("$50 back ($25 in)"), alex.heard());
    }

    // ---- the friendly table and NPC players -------------------------------------------------------------

    @Test
    void theFriendlyTableMovesNothingAndNpcsJoinIn() throws Exception {
        HoldemTable t = game.table(Kind.FRIENDLY);
        NpcTableTest.Npcs npcs = new NpcTableTest.Npcs();
        Fake alex = player("Alex");
        Seat me = sit(t, alex);
        assertEquals(200, me.stack(), "free play chips at once");
        for (int i = 0; i < 25; i++) {
            host.move(1_000);
            t.tick(npcs);
        }
        assertEquals(1, t.npcCount(), "an NPC sat in after join_after_seconds");
        int hands = 0;
        int lastHand = 0;
        for (int s = 0; s < 600 && hands < 6; s++) {
            host.move(1_000);
            t.tick(npcs);
            if (t.phase() == Phase.PLAYING && t.turn() != null && t.turn().id().equals(alex.id)) {
                t.checkCall(alex.player);
            }
            if (t.handNo() != lastHand) {
                lastHand = t.handNo();
                hands++;
            }
            if (t.phase() == Phase.BETWEEN) {
                HoldemHand h = t.hand();
                int start = 0;
                int end = 0;
                for (int i = 0; i < h.size(); i++) {
                    start += h.player(i).start();
                    end += h.player(i).stack();
                }
                assertEquals(start, end, "play chips only move between seats");
            }
        }
        assertTrue(hands >= 3, "hands were played with the NPCs: " + hands);
        assertTrue(t.npcCount() >= 1);
        assertTrue(t.npcCount() + 1 <= 4, "NPCs fill the table up to npc_seats");
        assertEquals(500, host.balanceOf(alex.id), "no tokens move at the friendly table");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "no round, ever");
        for (Seat seat : t.seats()) {
            if (seat.npc() != null) {
                assertTrue(CpuRoster.isCpu(seat.id()));
            }
        }
    }

    @Test
    void npcsNeverSitAtAStakesTableAndLeaveWhenSomeoneHasThemOff() {
        NpcTableTest.Npcs npcs = new NpcTableTest.Npcs();
        HoldemTable stakes = game.table(Kind.TOKENS);
        assertNull(stakes.sitNpc(CpuRoster.ALL.get(0), npcs), "an NPC never touches tokens or money");
        Fake alex = player("Alex");
        sit(stakes, alex);
        for (int i = 0; i < 40; i++) {
            host.move(1_000);
            stakes.tick(npcs);
        }
        assertEquals(0, stakes.npcCount(), "none joins a stakes table");
        HoldemTable friendly = game.table(Kind.FRIENDLY);
        Fake sam = player("Sam");
        sit(friendly, sam);
        assertNotNull(friendly.sitNpc(CpuRoster.ALL.get(0), npcs));
        npcs.off.add(sam.id);
        host.move(1_000);
        friendly.tick(npcs);
        assertEquals(0, friendly.npcCount(), "Sam has NPCs off: it gets up");
    }

    @Test
    void theExitRuleReadsTheChipsAndCarry() {
        assertEquals(60, HoldemTable.settleOnExit(1, 50, "holdem:1;t=tokens;s=57;c=3;h=4"));
        assertEquals(0, HoldemTable.settleOnExit(1, 50, "holdem:1;t=tokens;s=0;c=0;h=4"));
        assertThrows(IllegalArgumentException.class, () -> HoldemTable.settleOnExit(1, 50, "tr:3;x=1"));
        assertEquals(com.dierks.homecraft.arcade.TokenService.Source.ARCADE_HOLDEM, game.source());
        assertTrue(com.dierks.homecraft.storage.GamesDao.CHANCE_SOURCES.contains("ARCADE_HOLDEM"),
                "a buy-in counts toward the day's token limit");
    }
}
