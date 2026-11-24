package com.dierks.homecraft.games.chance.sportsbook;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One refresh's reads, over a fetcher that answers with the saved replies (never the network): requests spaced out,
 * a "too many requests" asked again a little later, a league that can't be read failing the lines as a whole while
 * the open picks' markets are still read, and those markets asked for twenty at a time. The live fetcher speaks https
 * only.
 */
class KalshiClientTest {

    private static final List<SportsbookSettings.League> NBA = List.of(SportsbookSettings.league("nba"));

    @Test
    void tooManyRequestsIsAskedAgainALittleLaterThenTheLinesAreRead() {
        List<String> asked = new ArrayList<>();
        List<Long> slept = new ArrayList<>();
        int[] busy = {2};
        KalshiClient.Result r = KalshiClient.fetch(path -> {
            asked.add(path);
            return busy[0]-- > 0 ? KalshiLinesTest.fixture("error_too_many.json") : KalshiLinesTest.fixture(
                    "events_nba.json");
        }, slept::add, NBA, List.of(), KalshiLinesTest.NOW, KalshiLinesTest.NOW + KalshiLinesTest.DAY, true);
        assertNull(r.error(), r.error());
        assertEquals(4, r.games().size(), "tonight's four NBA games");
        assertEquals(3, asked.size(), "asked three times");
        assertEquals(List.of(4_000L, 8_000L), slept, "4 s, then 8 s");
    }

    @Test
    void aLeagueThatCantBeReadFailsTheLinesButTheMarketsAreStillRead() {
        List<String> asked = new ArrayList<>();
        List<Long> slept = new ArrayList<>();
        List<String> tickers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tickers.add("T-" + i);
        }
        KalshiClient.Result r = KalshiClient.fetch(path -> {
            asked.add(path);
            if (path.startsWith("/events")) {
                throw new IOException("HTTP 503");
            }
            return KalshiLinesTest.fixture("markets_by_ticker.json");
        }, slept::add, NBA, tickers, KalshiLinesTest.NOW, KalshiLinesTest.NOW + KalshiLinesTest.DAY, true);
        assertNull(r.games(), "no lines: the board keeps its last reading");
        assertEquals("HTTP 503", r.error());
        assertEquals(3, asked.size(), "the league, then 25 markets in two requests of 20 and 5");
        assertTrue(asked.get(1).startsWith("/markets?tickers=T-0%2CT-1%2C"));
        assertEquals(2, r.markets().size(), "the markets that were read are kept");
        assertEquals(List.of(KalshiClient.SPACING_MS, KalshiClient.SPACING_MS), slept, "requests spaced out");
    }

    @Test
    void onlyTheMarketsAreReadWhenTheLinesArentWanted() {
        List<String> asked = new ArrayList<>();
        KalshiClient.Result r = KalshiClient.fetch(path -> {
            asked.add(path);
            return KalshiLinesTest.fixture("market_finalized.json");
        }, ms -> { }, NBA, List.of("KXNBAGAME-26NOV23PORPHI-POR"), KalshiLinesTest.NOW,
                KalshiLinesTest.NOW + KalshiLinesTest.DAY, false);
        assertNull(r.games());
        assertNull(r.error());
        assertEquals(1, asked.size());
        assertEquals(Kalshi.Outcome.YES, r.markets().get("KXNBAGAME-26NOV23PORPHI-POR").outcome());
    }

    @Test
    void theLiveFetcherSpeaksHttpsOnly() {
        assertThrows(IllegalArgumentException.class, () -> KalshiClient.http("http://api.elections.kalshi.com"));
        KalshiClient.http(SportsbookSettings.API_BASE); // built, never called here
        assertEquals("HomeCraftManagement", KalshiClient.USER_AGENT, "a plain User-Agent, nothing about anyone");
    }
}
