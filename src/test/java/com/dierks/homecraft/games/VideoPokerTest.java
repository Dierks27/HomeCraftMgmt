package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.poker.PokerDeal;
import com.dierks.homecraft.games.chance.poker.PokerHand;
import com.dierks.homecraft.games.chance.poker.PokerMath;
import com.dierks.homecraft.games.chance.poker.VideoPoker;
import com.dierks.homecraft.games.chance.poker.VideoPokerSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: Video Poker's hands as the screen plays them, over the framework's gate, rounds and database: the deal puts
 * the stake in as an open round carrying every prize; the draw settles it once with the prize of what the cards make;
 * a hand left open (a quit, a restart) is drawn with the best hold from its own data, once; dollars pay dollars, and
 * the day's hands are counted.
 */
class VideoPokerTest {

    private Host host;
    private GamesService games;
    private VideoPoker poker;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 24, 20, 0));
        host.bankOn = true;
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "video_poker",
                VideoPokerSettings.defaults());
        games = GamesKit.service(host, List.of(VideoPoker.SPEC));
        games.start();
        poker = (VideoPoker) games.game("video_poker");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 200);
        host.money.put(f.id, 300.0);
        games.parlorGuest(f.id);
        return f;
    }

    /** The deal, as the screen makes it: the stake in, an open round with every prize. */
    private ChanceRounds.Round deal(Fake f, VideoPokerSettings.Odds odds, boolean money) {
        return games.rounds().open(f.player, poker, odds.stake(), PokerDeal.openData(odds.stake(), odds.prizeArray()),
                money);
    }

    @Test
    void aHandIsDealtDrawnAndPaidOnce() {
        Fake alex = player("Alex");
        assertEquals(TokenService.Source.ARCADE_VIDEO_POKER, poker.source());
        assertTrue(poker.configEnabled());
        VideoPokerSettings.Odds five = poker.settings().odds(5);
        ChanceRounds.Round round = deal(alex, five, false);
        assertNotNull(round);
        assertEquals(195, host.balanceOf(alex.id), "the stake went in at the deal");
        assertEquals(5, games.breaks().tokensInToday(alex.id), "and counts toward today's tokens");
        assertNotNull(games.rounds().openRound(alex.id, "video_poker"), "open until the draw");
        PokerDeal d = PokerDeal.resume(round.seed(), round.data());
        d.toggle(0);
        d.toggle(1);
        d.draw();
        int pay = d.payout();
        assertEquals(five.prize(d.hand()), pay);
        assertTrue(games.rounds().close(round, pay, d.data()));
        assertFalse(games.rounds().close(round, pay, d.data()), "never twice");
        assertEquals(195 + pay, host.balanceOf(alex.id));
        assertNull(games.rounds().openRound(alex.id, "video_poker"));
    }

    @Test
    void aHandLeftOpenIsDrawnWithTheBestHoldWhenThePlayerLeavesOrTheServerRestarts() {
        Fake alex = player("Alex");
        VideoPokerSettings.Odds one = poker.settings().odds(1);
        ChanceRounds.Round round = deal(alex, one, false);
        PokerDeal d = PokerDeal.deal(round.seed(), 1, one.prizeArray());
        int best = PokerMath.bestHold(d.dealt(), one.prizeArray());
        int expected = one.prize(PokerHand.of(d.after(best)));
        host.online.remove(alex.id);
        games.onQuit(alex.player);
        assertEquals(199 + expected, host.balanceOf(alex.id), "drawn for Alex with the best hold");
        // a crash: the next start finds a dealt hand of an offline player and finishes it, once
        Fake sam = player("Sam");
        VideoPokerSettings.Odds dollars = poker.moneyOdds(5);
        ChanceRounds.Round samRound = deal(sam, dollars, true);
        assertNotNull(samRound);
        assertEquals(295.0, host.money.get(sam.id));
        PokerDeal sd = PokerDeal.deal(samRound.seed(), 5, dollars.prizeArray());
        int samPay = dollars.prize(PokerHand.of(sd.after(PokerMath.bestHold(sd.dealt(), dollars.prizeArray()))));
        host.online.clear();
        GamesService after = GamesKit.service(host, List.of(VideoPoker.SPEC));
        after.start();
        assertEquals(295.0 + samPay, host.money.get(sam.id), "Sam's dollar hand finished at the start-up");
        GamesService again = GamesKit.service(host, List.of(VideoPoker.SPEC));
        again.start();
        assertEquals(295.0 + samPay, host.money.get(sam.id), "and never again");
        assertEquals(199 + expected, host.balanceOf(alex.id));
    }

    @Test
    void dollarStakesAreCountedUnderTheParlorsBiggestPayout() {
        VideoPokerSettings.Odds five = poker.moneyOdds(5);
        assertNotNull(five);
        assertEquals(500, five.prize(PokerHand.ROYAL_FLUSH), "$5: the Royal Flush's 100 times fits under $500");
        assertEquals(VideoPokerSettings.defaults().odds(1).exact(), five.exact(), "the shipped table's own return");
        VideoPokerSettings.Odds ten = poker.moneyOdds(10);
        assertEquals(500, ten.prize(PokerHand.ROYAL_FLUSH), "$10: held to $500");
        assertEquals(VideoPokerSettings.defaults().odds(5).exact(), ten.exact(), "the capped table's return");
        VideoPokerSettings.Odds fifty = poker.moneyOdds(50);
        assertNotNull(fifty, "$50 is still inside the band (89.4)");
        assertTrue(RtpLimits.inBand(fifty.exact()));
        assertEquals(500, fifty.prize(PokerHand.FOUR_OF_A_KIND));
        assertNull(poker.moneyOdds(0));
    }

    @Test
    void theDaysHandsAreCounted() {
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "video_poker",
                new VideoPokerSettings(true, List.of(1, 2, 5), 2, VideoPokerSettings.shippedPays(),
                        VideoPokerSettings.defaults().odds()));
        games.reload();
        poker = (VideoPoker) games.game("video_poker");
        Fake alex = player("Alex");
        VideoPokerSettings.Odds one = poker.settings().odds(1);
        for (int i = 0; i < 2; i++) {
            ChanceRounds.Round r = deal(alex, one, false);
            assertNotNull(r, "hand " + (i + 1));
            PokerDeal d = PokerDeal.resume(r.seed(), r.data());
            d.draw();
            assertTrue(games.rounds().close(r, d.payout(), d.data()));
            host.move(1_000);
        }
        assertNull(deal(alex, one, false), "two hands a day");
        assertTrue(alex.heard().toLowerCase(java.util.Locale.ROOT).contains("video poker"), alex.heard());
    }

    @Test
    void theOddsLinesAndTheTileSayTheExactCount() {
        List<String> lines = poker.oddsLines();
        assertTrue(lines.get(0).contains("gives back about 93 of every 100 tokens"), lines.get(0));
        assertTrue(lines.get(1).contains("93.4%"), lines.get(1));
        assertTrue(lines.get(3).contains("93.3%"), "5 tokens, the Royal Flush held to the cap: " + lines.get(3));
    }
}
