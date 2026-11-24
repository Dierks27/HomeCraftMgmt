package com.dierks.homecraft.games.chance.sportsbook;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sports Board's reading of Kalshi and its numbers, on real replies saved from Kalshi's public API on
 * 2026-11-24 around 02:47 UTC ({@code src/test/resources/kalshi/}: three leagues' {@code /events} pages trimmed to a few
 * games each, a {@code /markets?tickers=} reply, a {@code /markets/{ticker}} reply and the "too many requests" error).
 * Nothing here touches the network.
 *
 * <ul>
 *   <li>a page of events reads into games, each with its real start (the milestone's) and its sides; a game that has
 *       started, or starts after {@code hours_ahead}, is left off;</li>
 *   <li>a side's chance is its bid and ask's midpoint, the last trade when they are far apart or missing, else none;</li>
 *   <li>a pick pays {@code stake x rtp / price}, rounded down to the cent, never over the payout cap;</li>
 *   <li>a side under 5 or over 95 in 100, or whose win would give back no more than was put in, isn't offered;</li>
 *   <li>a market's result reads as yes, no, called off or not yet;</li>
 *   <li>the kept board reads back as it was.</li>
 * </ul>
 */
class KalshiLinesTest {

    /** When the replies were saved: 8:50 PM in Chicago on Monday 23 November 2026. */
    static final long NOW = Instant.parse("2026-11-24T02:50:00Z").toEpochMilli();
    static final long DAY = 24 * 3_600_000L;

    static String fixture(String name) {
        try (InputStream in = KalshiLinesTest.class.getResourceAsStream("/kalshi/" + name)) {
            assertNotNull(in, "fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Board.Game game(List<Board.Game> games, String event) {
        for (Board.Game g : games) {
            if (g.event().endsWith(event)) {
                return g;
            }
        }
        return null;
    }

    private static Board.Side side(Board.Game g, String label) {
        for (Board.Side s : g.sides()) {
            if (s.label().equals(label)) {
                return s;
            }
        }
        throw new AssertionError(label + " in " + g);
    }

    // ---- reading the replies --------------------------------------------------------------------------------

    @Test
    void anNbaPageReadsIntoTonightsGamesWithTheirRealStartsAndSides() {
        Kalshi.Page page = Kalshi.events(fixture("events_nba.json"), "nba", NOW, NOW + DAY);
        assertEquals(7, page.events(), "the page held seven games");
        assertEquals("", page.cursor(), "and was the last page");
        List<String> events = new ArrayList<>();
        for (Board.Game g : page.games()) {
            events.add(g.event());
        }
        assertEquals(List.of("KXNBAGAME-26NOV23SASSAC", "KXNBAGAME-26NOV23UTALAL", "KXNBAGAME-26NOV24DETORL",
                "KXNBAGAME-26NOV24BOSWAS"), events,
                "the two games already under way (BKN-MIN, MIL-TOR) and the one two days off (CLE-NYK) are left off");
        Board.Game sas = game(page.games(), "SASSAC");
        assertEquals("San Antonio vs Sacramento", sas.title());
        assertEquals("nba", sas.league());
        assertEquals(Instant.parse("2026-11-24T03:00:00Z").toEpochMilli(), sas.startsAt(),
                "the milestone's start_date, not the market's close two days later");
        assertEquals("scheduled", sas.state());
        assertEquals(2, sas.sides().size(), "one market a team: each team is its market's yes");
        Board.Side spurs = side(sas, "San Antonio");
        assertEquals("KXNBAGAME-26NOV23SASSAC-SAS", spurs.ticker());
        assertEquals("yes", spurs.side());
        assertEquals(720, spurs.bid());
        assertEquals(730, spurs.ask());
        assertEquals(730, spurs.last());
        assertEquals(725, spurs.price(), "the midpoint of 72c and 73c");
        assertEquals(285, side(sas, "Sacramento").price());
        assertEquals("9:00 PM", Sportsbook.when(sas.startsAt(), NOW, ZoneId.of("America/Chicago")),
                "in the server's zone");
        assertEquals("Tue 7:00 PM", Sportsbook.when(game(page.games(), "BOSWAS").startsAt(), NOW,
                ZoneId.of("America/Chicago")), "another day says which");
    }

    @Test
    void aGameThatStartedIsLeftOffEvenWhileKalshiStillSaysScheduled() {
        Kalshi.Page nhl = Kalshi.events(fixture("events_nhl.json"), "nhl", NOW, NOW + DAY);
        List<String> events = new ArrayList<>();
        for (Board.Game g : nhl.games()) {
            events.add(g.event().substring("KXNHLGAME-".length()));
        }
        assertEquals(List.of("26NOV23MINSJ", "26NOV24VGKEDM"), events,
                "ANA-SEA started at 8:40 (its milestone still said scheduled) and CGY-OTT was under way");
        Kalshi.Page before = Kalshi.events(fixture("events_nhl.json"), "nhl",
                Instant.parse("2026-11-24T00:00:00Z").toEpochMilli(), NOW + DAY);
        Board.Game cgy = game(before.games(), "CGYOTT");
        assertNotNull(cgy, "read an hour earlier, CGY-OTT was still to come");
        assertFalse(cgy.open(Instant.parse("2026-11-24T00:00:00Z").toEpochMilli()),
                "but Kalshi says it is under way: no picks on it");
        assertTrue(game(before.games(), "MINSJ").open(Instant.parse("2026-11-24T00:00:00Z").toEpochMilli()));
    }

    @Test
    void aReplyThatIsKalshisErrorOrNotEventsIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Kalshi.events(fixture("error_too_many.json"), "nba", NOW, NOW + DAY));
        assertEquals("Kalshi said: too_many_requests", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Kalshi.events("<html>", "nba", NOW, NOW + DAY));
        assertThrows(IllegalArgumentException.class, () -> Kalshi.events("{\"markets\":[]}", "nba", NOW, NOW + DAY));
    }

    @Test
    void theMarketsRepliesReadAsYesNoOrNotYet() {
        Map<String, Kalshi.Market> m = Kalshi.markets(fixture("markets_by_ticker.json"));
        assertEquals(2, m.size());
        assertEquals(Kalshi.Outcome.YES, m.get("KXNBAGAME-26NOV23PORPHI-POR").outcome(), "finalized, result yes");
        assertEquals(Kalshi.Outcome.OPEN, m.get("KXNBAGAME-26NOV23MILTOR-MIL").outcome(), "still active");
        Map<String, Kalshi.Market> one = Kalshi.markets(fixture("market_finalized.json"));
        assertEquals(Kalshi.Outcome.YES, one.get("KXNBAGAME-26NOV23PORPHI-POR").outcome(), "one market's own reply");
        assertEquals(Kalshi.Outcome.NO, new Kalshi.Market("t", "finalized", "no").outcome());
        assertEquals(Kalshi.Outcome.NO, new Kalshi.Market("t", "determined", "no").outcome());
        assertEquals(Kalshi.Outcome.VOID, new Kalshi.Market("t", "finalized", "void").outcome());
        assertEquals(Kalshi.Outcome.VOID, new Kalshi.Market("t", "settled", "").outcome(),
                "settled with no winner: a game called off, settled at a fair price");
        assertEquals(Kalshi.Outcome.OPEN, new Kalshi.Market("t", "closed", "").outcome(), "closed, not decided yet");
        assertEquals(Kalshi.Outcome.OPEN, new Kalshi.Market("t", "active", "").outcome());
        assertThrows(IllegalArgumentException.class, () -> Kalshi.markets(fixture("error_too_many.json")));
    }

    @Test
    void whatIsAskedNamesOnlyTheLeagueOrTheMarkets() {
        assertEquals("/events?series_ticker=KXNBAGAME&status=open&with_nested_markets=true&with_milestones=true"
                + "&limit=100", Kalshi.eventsPath("KXNBAGAME", ""));
        assertTrue(Kalshi.eventsPath("KXNBAGAME", "abc=").endsWith("&cursor=abc%3D"));
        assertEquals("/markets?tickers=A-1%2CB-2&limit=100", Kalshi.marketsPath(List.of("A-1", "B-2")));
    }

    // ---- prices and pays ------------------------------------------------------------------------------------

    @Test
    void aSidesChanceIsTheMidpointThenTheLastTradeThenNone() {
        assertEquals(725, Lines.price(720, 730, 730), "bid and ask close: their midpoint");
        assertEquals(875, Lines.price(840, 910, 900), "7c apart is close enough (Yale's real book)");
        assertEquals(900, Lines.price(800, 910, 900), "11c apart: the last trade");
        assertEquals(10, Lines.price(0, 10, 10), "no bid: the last trade");
        assertEquals(990, Lines.price(990, 1000, 990), "an ask of a whole dollar is no ask");
        assertEquals(Lines.NONE, Lines.price(Lines.NONE, Lines.NONE, Lines.NONE), "nothing at all: no price");
        assertEquals(625, Lines.mills("0.6250"));
        assertEquals(Lines.NONE, Lines.mills(""));
        assertEquals(Lines.NONE, Lines.mills("1.5"));
        assertEquals(620, Lines.millsOfCents(62), "Kalshi's older whole-cent fields");
        assertEquals(380, Lines.other(620), "the no side of a 62c yes");
    }

    @Test
    void aPickPaysStakeTimesRtpOverThePriceRoundedDownToTheCent() {
        assertEquals(1241, Lines.payoutCents(10, 725, 900), "$10 at 72.5 in 100, 90 back: $12.41");
        assertEquals(3157, Lines.payoutCents(10, 285, 900), "$10 at 28.5: $31.57");
        assertEquals(1800, Lines.payoutCents(10, 500, 900), "an even game: $18");
        assertEquals(1894, Lines.payoutCents(10, 500, 947), "rtp 94.7");
        for (int price = Lines.LOW; price < 900; price += 7) {
            long cents = Lines.payoutCents(100, price, 900);
            double back = cents / 100.0 * price / Lines.WHOLE;
            assertTrue(back <= 90.0 && back > 89.99, "at " + price + " a win gives back 90 of 100 at the price: "
                    + back);
        }
        assertEquals(73, Lines.inHundred(725), "wins about 73 in 100");
        assertEquals(1, Lines.inHundred(2));
    }

    @Test
    void lopsidedSidesAndWinsThatPayBackLessAreLeftOut() {
        assertFalse(Lines.offered(49, 900), "under 5 in 100");
        assertTrue(Lines.offered(50, 900));
        assertFalse(Lines.offered(951, 950), "over 95 in 100");
        assertFalse(Lines.offered(900, 900), "a win at 90 in 100 with 90 back pays back exactly the stake");
        assertFalse(Lines.offered(899, 900), "$5 at 89.9 in 100 would pay $5.00 back: no win at all");
        assertTrue(Lines.offered(891, 900), "a win pays at least 1% more than went in");
        assertFalse(Lines.offered(892, 900));
        assertTrue(Lines.offered(930, 950), "with 95 back a 93 in 100 favourite still pays more than it took");
        assertFalse(Lines.offered(Lines.NONE, 900));
        Kalshi.Page early = Kalshi.events(fixture("events_nba.json"), "nba",
                Instant.parse("2026-11-23T20:00:00Z").toEpochMilli(), NOW + DAY);
        Board.Game bkn = game(early.games(), "BKNMIN");
        assertEquals(10, side(bkn, "Brooklyn").price(), "1c: the last trade, with no bid");
        assertEquals(990, side(bkn, "Minnesota").price());
        assertFalse(Lines.offered(side(bkn, "Brooklyn").price(), 900), "too lopsided");
        assertFalse(Lines.offered(side(bkn, "Minnesota").price(), 900), "too lopsided");
        Kalshi.Page wb = Kalshi.events(fixture("events_ncaawb.json"), "ncaawb", NOW, NOW + DAY);
        Board.Game byu = game(wb.games(), "BYUUVU");
        assertEquals(930, side(byu, "BYU").price());
        assertFalse(Lines.offered(930, 900), "BYU at 93 in 100 is left out: a win would pay back less than went in");
        assertTrue(Lines.offered(side(byu, "Utah Valley").price(), 900), "Utah Valley at 6.5 in 100 is offered");
    }

    @Test
    void thePayoutCapLeavesALongShotToSmallerStakes() {
        assertEquals(36, Lines.maxStake(65, 900, 500), "at 6.5 in 100, $36 pays $498.46; $37 would pass $500");
        assertTrue(Lines.payoutCents(36, 65, 900) <= 50_000);
        assertTrue(Lines.payoutCents(37, 65, 900) > 50_000);
        assertEquals(27, Lines.maxStake(50, 900, 500), "at 5 in 100: $27 pays $486");
        assertTrue(Lines.maxStake(725, 900, 500) >= 50, "a favourite takes any stake the Parlor allows");
        assertEquals(0, Lines.maxStake(50, 900, 0));
    }

    // ---- the kept board -------------------------------------------------------------------------------------

    @Test
    void theKeptBoardReadsBackAsItWas() {
        List<Board.Game> games = new ArrayList<>(Kalshi.events(fixture("events_nba.json"), "nba", NOW, NOW + DAY)
                .games());
        games.addAll(Kalshi.events(fixture("events_ncaawb.json"), "ncaawb", NOW, NOW + DAY).games());
        Board b = new Board(NOW, games);
        assertEquals(b, Board.fromJson(b.toJson()));
        assertEquals(Board.EMPTY, Board.fromJson("nonsense"));
        assertEquals(Board.EMPTY, Board.fromJson(null));
        assertEquals(4, b.upcoming(NOW, "nba").size());
        assertNull(b.find("nope", "yes"));
        assertEquals("Yale", b.find("KXNCAAWBGAME-26NOV24AMERYALE-YALE", "yes").side().label());
    }
}
