package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.bingo.Bingo;
import com.dierks.homecraft.games.chance.bingo.Bingo.Entry;
import com.dierks.homecraft.games.chance.bingo.Bingo.Holder;
import com.dierks.homecraft.games.chance.bingo.Bingo.Phase;
import com.dierks.homecraft.games.chance.bingo.BingoPrize;
import com.dierks.homecraft.games.chance.bingo.BingoSettings;
import com.dierks.homecraft.games.chance.bingo.BingoTicket;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuRoster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bingo Night (0.57.0) run for real over the framework's gate, rounds and database, with a fake bank:
 *
 * <ul>
 *   <li>each player's cards are one round: what they cost goes in when they are bought, and every round is decided and
 *       settled once, the moment the calling starts, before a number is called;</li>
 *   <li>a round is played with at least {@code room_cards} cards (the room's own fill it), and its prize is the one
 *       the cards in play make: paid out whole, every winner's share in their own money;</li>
 *   <li>cards bought for a round that can't be played are given back (a quit, a crash, a round of NPCs alone);</li>
 *   <li>NPCs' cards have no round and are paid nothing.</li>
 * </ul>
 */
class BingoTest {

    private Host host;
    private GamesService games;
    private Bingo bingo;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 24, 20, 0));
        host.bankOn = true;
        open(BingoSettings.defaults());
    }

    private void open(BingoSettings settings) {
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "bingo", settings);
        games = GamesKit.service(host, List.of(Bingo.SPEC));
        bingo = (Bingo) games.game("bingo");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** A player in the Parlor with 200 tokens and $300. */
    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 200);
        host.money.put(f.id, 300.0);
        games.parlorGuest(f.id);
        return f;
    }

    private void pass(long ms) {
        host.move(ms);
        bingo.tick();
    }

    /** Call the round to its end (the result standing). */
    private void callToTheEnd() {
        assertEquals(Phase.CALLING, bingo.phase());
        for (int i = 0; i < 100 && bingo.phase() == Phase.CALLING; i++) {
            pass(bingo.settings().callMs());
        }
        assertEquals(Phase.DONE, bingo.phase(), "the last part is won");
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private int paid(UUID player) throws Exception {
        return count("SELECT COALESCE(SUM(payout), 0) FROM game_rounds WHERE player = '" + player + "'");
    }

    private static String label(CpuPlayer c) {
        return CpuRoster.label(c.name(), c.level());
    }

    // ---- a whole round ------------------------------------------------------------------------------------

    @Test
    void twoPlayersOneInTokensOneInDollarsPlayOneRoundSettledOnceBeforeTheFirstCall() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertEquals(Phase.IDLE, bingo.phase());
        assertTrue(bingo.buy(alex.player, 2, false), "Alex: two cards for tokens");
        assertEquals(Phase.GATHERING, bingo.phase(), "the first cards start the wait");
        assertEquals(20, bingo.secondsLeft());
        assertEquals(180, host.balanceOf(alex.id), "20 tokens went in when they were bought");
        host.move(3_000);
        assertTrue(bingo.buy(sam.player, 1, true), "Sam: one card for dollars");
        assertEquals(290.0, host.money.get(sam.id), "$10 went in");
        assertEquals(200, host.balanceOf(sam.id), "and none of Sam's tokens");
        assertEquals(300.0, host.money.get(alex.id), "nor any of Alex's money");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"), "a round each");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN' AND data NOT LIKE '%calls=%'"),
                "nothing about the calls exists while cards can still be bought");
        CpuPlayer pip = CpuRoster.ALL.get(0);
        assertTrue(bingo.enterNpc(pip.id(), label(pip), 2), "an NPC takes two cards for fun");
        assertEquals(5, bingo.cardsNow());
        assertEquals(8, bingo.cardsInPlay(), "a round is played with at least 8 cards");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds"), "the NPC's cards have no round");

        pass(16_000);
        assertEquals(Phase.GATHERING, bingo.phase(), "a second to go");
        pass(1_000);
        assertEquals(Phase.CALLING, bingo.phase(), "the calling starts");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"),
                "every round was settled the moment the calling started, before a number is called");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED' AND data LIKE '%;n=8;%'"),
                "both rounds know the 8 cards in play");
        Entry room = bingo.entry(Bingo.ROOM);
        assertNotNull(room, "the room's own cards made up the 8");
        assertEquals(3, room.cards().size());
        assertEquals(Holder.ROOM, room.holder());

        Entry a = bingo.entry(alex.id);
        Entry s = bingo.entry(sam.id);
        assertEquals(paid(alex.id), a.pays(), "what Alex's cards made is what Alex's round paid");
        assertEquals(paid(sam.id), s.pays());
        assertEquals(180 + a.pays(), host.balanceOf(alex.id), "paid already, before the first call");
        assertEquals(290.0 + s.pays(), host.money.get(sam.id), "Sam in dollars, straight to the bank");
        assertEquals(0, host.balanceOf(pip.id()), "the NPC has nothing, ever");
        assertEquals(0, bingo.called(), "nothing called yet");
        assertFalse(bingo.lineCalled());

        // the prize, in tokens: every card that is not Sam's in tokens (the NPC's and the room's as play chips)
        int pool = BingoPrize.pool(10, 8, 90);
        int samPlace = s.place(0);
        int tokensPaid = 0;
        for (int place = 0; place < 8; place++) {
            if (place != samPlace) {
                tokensPaid += bingo.lineShare(place) + bingo.cornersShare(place);
            }
        }
        // every card's share is from its part, and the parts are paid out whole (Sam's share is in dollars, at the
        // same price, so the same amount)
        assertEquals(pool, tokensPaid + bingo.lineShare(samPlace) + bingo.cornersShare(samPlace),
                "the whole prize is shared out: " + pool);

        callToTheEnd();
        assertTrue(bingo.lineCalled());
        assertTrue(bingo.cornersCalled());
        assertTrue(alex.heard().contains("BINGO!"), "everyone hears each part won: " + alex.heard());
        assertTrue(alex.heard().contains("Bingo Night: "), alex.heard());
        assertTrue(sam.heard().contains("Bingo Night: "), sam.heard());
        pass(Bingo.DONE_MS);
        assertEquals(Phase.IDLE, bingo.phase(), "and the next round can be bought");
        assertTrue(bingo.entries().isEmpty());

        // nothing more moves, and nothing can be paid again
        games.rounds().settleOpen(alex.id);
        games.rounds().settleOpen(sam.id);
        games.rounds().sweep();
        assertEquals(180 + a.pays(), host.balanceOf(alex.id), "paid once");
        assertEquals(290.0 + s.pays(), host.money.get(sam.id));
        assertEquals(0, host.severe());
    }

    @Test
    void everyTokenAddsUpOverManyRounds() throws Exception {
        Fake[] p = {player("A"), player("B"), player("C")};
        int[] cards = {1, 4, 2};
        long in = 0;
        long out = 0;
        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < p.length; i++) {
                host.give(p[i].id, 40);
                assertTrue(bingo.buy(p[i].player, cards[i], false), "round " + round);
                in += cards[i] * 10L;
            }
            for (Fake f : p) {
                bingo.startNow(f.player);
            }
            assertEquals(Phase.CALLING, bingo.phase(), "everyone asked: it starts at once");
            int pool = BingoPrize.pool(10, bingo.cardsInPlay(), 90);
            int all = 0;
            for (int place = 0; place < bingo.cardsInPlay(); place++) {
                all += bingo.lineShare(place) + bingo.cornersShare(place);
            }
            assertEquals(pool, all, "the whole prize, every round, shared between the cards that made it");
            for (Fake f : p) {
                out += bingo.entry(f.id).pays();
            }
            callToTheEnd();
            pass(Bingo.DONE_MS);
            host.move(1_000); // past the click cooldown
        }
        long balances = 0;
        long paid = 0;
        for (Fake f : p) {
            balances += host.balanceOf(f.id);
            paid += paid(f.id);
        }
        assertEquals(paid, out, "every round paid what the round said");
        assertEquals(3 * 200L + rounds * 3 * 40L - in + out, balances, "every token accounted for");
        assertEquals(3 * rounds, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'"));
        assertEquals(0, host.severe());
    }

    // ---- refused, and given back --------------------------------------------------------------------------

    @Test
    void buyingIsRefusedTwiceWhileCalledAndPastTheRoom() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertTrue(bingo.buy(alex.player, 1, false));
        host.move(1_000);
        assertFalse(bingo.buy(alex.player, 1, false), "one buy a round");
        assertTrue(alex.heard().contains("You have your cards for this round already."), alex.heard());
        assertFalse(bingo.buy(sam.player, 5, false), "four cards at most");
        bingo.startNow(alex.player);
        assertEquals(Phase.CALLING, bingo.phase());
        assertFalse(bingo.buy(sam.player, 1, false), "nobody buys while a round is called");
        assertTrue(sam.heard().contains("A round is being called."), sam.heard());
        assertEquals(200, host.balanceOf(sam.id), "nothing moved");

        open(new BingoSettings(true, 10, 10, 90, 60, 4, 4, 30, 20, 2)); // a room of 4 cards at most
        Fake c = player("Cleo");
        Fake d = player("Dan");
        assertTrue(bingo.buy(c.player, 3, false));
        host.move(1_000);
        assertFalse(bingo.buy(d.player, 2, false), "the round has room for one more card");
        assertTrue(d.heard().contains("all the cards it can take"), d.heard());
        assertTrue(bingo.buy(d.player, 1, false));
        assertEquals(0, host.severe());
    }

    @Test
    void cardsAreGivenBackWhenTheRoundCantBePlayed() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        // a quit before the calling: the framework finishes the round by its exit rule, the cards come back
        assertTrue(bingo.buy(alex.player, 3, false));
        assertTrue(bingo.buy(sam.player, 1, false));
        host.online.remove(alex.id);
        bingo.onQuit(alex.player);
        games.rounds().settleOpen(alex.id);
        assertEquals(200, host.balanceOf(alex.id), "30 in, 30 back");
        assertNull(bingo.entry(alex.id));
        // the last player leaves: an NPC alone never plays, and Sam's card comes back too
        CpuPlayer pip = CpuRoster.ALL.get(1);
        assertTrue(bingo.enterNpc(pip.id(), label(pip), 2));
        host.online.remove(sam.id);
        pass(1_000);
        assertEquals(Phase.IDLE, bingo.phase(), "a round of NPCs alone never runs");
        assertTrue(bingo.entries().isEmpty());
        assertEquals(200, host.balanceOf(sam.id), "Sam's 10 came back whole");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
        assertFalse(bingo.enterNpc(pip.id(), label(pip), 2), "no round to join");
    }

    @Test
    void aRoundLeftOpenIsFinishedFromItsOwnData() throws Exception {
        Fake alex = player("Alex");
        // a crash while cards were sold: nothing was drawn, the cards are given back
        assertTrue(bingo.buy(alex.player, 2, false));
        open(BingoSettings.defaults()); // a new game object: its memory of the round is gone
        games.parlorGuest(alex.id);
        games.rounds().settleOpen(alex.id);
        assertEquals(200, host.balanceOf(alex.id), "20 in, 20 back");
        assertTrue(alex.heard().contains("20 tokens back"), alex.heard());

        // drawn but not yet settled (a crash between the two): the round pays what was written
        host.move(2_000);
        assertTrue(bingo.buy(alex.player, 2, false));
        ChanceRounds.Round r = games.rounds().openRound(alex.id, "bingo");
        BingoTicket t = BingoTicket.read(r.data());
        assertNotNull(games.rounds().step(r, t.drawn(77L, 8, 31).data()));
        games.rounds().settleOpen(alex.id);
        assertEquals(200 - 20 + 31, host.balanceOf(alex.id), "what its cards made, from its data");

        // an old round left open is finished before new cards are bought
        open(BingoSettings.defaults());
        games.parlorGuest(alex.id);
        host.move(2_000);
        assertTrue(bingo.buy(alex.player, 1, false));
        open(BingoSettings.defaults());
        games.parlorGuest(alex.id);
        host.move(2_000);
        assertTrue(bingo.buy(alex.player, 2, false), "the old card is given back first, then these go in");
        assertEquals(211 - 20, host.balanceOf(alex.id));
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
        assertEquals(0, host.severe());
    }

    @Test
    void stoppingMidRoundTellsItsPlayersHowItCameOut() throws Exception {
        Fake alex = player("Alex");
        assertTrue(bingo.buy(alex.player, 4, false));
        bingo.startNow(alex.player);
        assertEquals(Phase.CALLING, bingo.phase());
        int pays = bingo.entry(alex.id).pays();
        bingo.stop();
        assertEquals(Phase.IDLE, bingo.phase());
        assertTrue(alex.heard().contains("Bingo Night: "), "paid at the start, so told now: " + alex.heard());
        assertEquals(160 + pays, host.balanceOf(alex.id));
    }

    // ---- NPCs ----------------------------------------------------------------------------------------------

    @Test
    void npcsGiveWayAndAreNeverPaid() throws Exception {
        open(new BingoSettings(true, 10, 10, 90, 60, 4, 6, 30, 20, 2)); // at most 6 cards
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        CpuPlayer pip = CpuRoster.ALL.get(0);
        CpuPlayer bo = CpuRoster.ALL.get(2);
        assertFalse(bingo.enterNpc(pip.id(), label(pip), 2), "no round yet: nothing to join");
        assertTrue(bingo.buy(alex.player, 1, false));
        assertTrue(bingo.enterNpc(pip.id(), label(pip), 4));
        assertFalse(bingo.enterNpc(pip.id(), label(pip), 1), "once each");
        assertTrue(bingo.enterNpc(bo.id(), label(bo), 4), "it takes only what's left");
        assertEquals(1, bingo.entry(bo.id()).cards().size());
        assertTrue(bingo.buy(sam.player, 3, false), "an NPC gives way to a player");
        assertEquals(6, bingo.cardsNow(), "the NPCs gave way: the last one's card went, then two of the first's");
        assertNull(bingo.entry(bo.id()), "the last NPC had no card left, so it left");
        assertEquals(2, bingo.entry(pip.id()).cards().size());
        bingo.startNow(alex.player);
        bingo.startNow(sam.player);
        assertEquals(Phase.CALLING, bingo.phase());
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds"), "only the players' rounds");
        assertEquals(0, host.balanceOf(pip.id()));
        assertEquals(0, host.balanceOf(bo.id()));
        assertTrue(Bingo.real(alex.id));
        assertFalse(Bingo.real(pip.id()));
        assertFalse(Bingo.real(Bingo.ROOM));
    }

    // ---- what it publishes ---------------------------------------------------------------------------------

    @Test
    void whatItPublishesIsTheExactReturn() {
        double low = bingo.lowestRtp();
        assertTrue(RtpLimits.inBand(low), "every card gives back 85-95: " + low);
        List<String> lines = bingo.oddsLines();
        assertTrue(lines.get(0).contains(RtpLimits.playerLine(low)), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("8 cards → a prize of 72 (line 29, four corners 43)")),
                lines.toString());
        assertEquals(GameKind.CHANCE, bingo.kind());
        assertFalse(bingo.featurable());
        for (String rule : bingo.rules()) {
            assertFalse(rule.toLowerCase(java.util.Locale.ROOT).contains("bet"), rule);
        }
    }
}
