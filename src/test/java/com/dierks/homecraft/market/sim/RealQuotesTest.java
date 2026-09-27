package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.sim.RealQuotes.DailyClose;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real commodity closes (spec §9.2), against the fixture files in
 * {@code src/test/resources/market/real/} — the sandbox cannot reach Stooq or Yahoo, so these
 * are the shapes both return.
 *
 * <p>Pinned here: the closes each parser reads (oldest first, one per day, the later bar
 * winning a repeated day); Stooq's {@code Date}/{@code Close} found by name in any order and
 * case, bad rows skipped; "No data", the hits-limit notice, an HTML page, a Yahoo
 * {@code chart.error} and junk all give nothing rather than an exception; Yahoo dates are the
 * exchange's. Then the mapping: the latest move uses the two newest trade days and ignores data
 * more than 4 days old; the nudge is {@code 2 × move} capped at 4% (never past 4.5% whatever
 * the config) and skipped above 8%. And the URL: symbol encoded, {@code {from}} two weeks back.
 */
class RealQuotesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27); // a Sunday
    private static final double EPS = 1e-9;

    private static String fixture(String name) throws IOException {
        try (InputStream in = RealQuotesTest.class.getResourceAsStream("/market/real/" + name)) {
            assertNotNull(in, "missing test fixture market/real/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DailyClose close(String day, double close) {
        return new DailyClose(LocalDate.parse(day), close);
    }

    private static void assertCloses(List<DailyClose> expected, List<DailyClose> actual) {
        assertCloses(expected, actual, "");
    }

    private static void assertCloses(List<DailyClose> expected, List<DailyClose> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + " " + actual);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).day(), actual.get(i).day(), what + " day #" + i);
            assertEquals(expected.get(i).close(), actual.get(i).close(), 1e-4, what + " close #" + i);
        }
    }

    private static final List<DailyClose> GOLD = List.of(
            close("2026-09-14", 3659.8), close("2026-09-15", 3676.3), close("2026-09-16", 3668.1),
            close("2026-09-17", 3640.4), close("2026-09-18", 3655.2), close("2026-09-21", 3694.6),
            close("2026-09-22", 3705.9), close("2026-09-23", 3688.0), close("2026-09-24", 3700.0),
            close("2026-09-25", 3755.5));

    // ---- Stooq --------------------------------------------------------------------------

    @Test
    void stooqClosesAreReadOldestFirst() throws IOException {
        List<DailyClose> gold = RealQuotes.parseStooq(fixture("stooq_ok.csv"));
        assertCloses(GOLD, gold);
        assertEquals(3755.5, gold.get(gold.size() - 1).close(), "exact, not rounded");
    }

    @Test
    void stooqFindsItsColumnsByNameAndSkipsBadRows() throws IOException {
        // Volume,close,DATE,… with CRLF; an N/D close, a zero close, a bad date and a repeated
        // day (the zero loses to the real close).
        assertCloses(List.of(close("2026-09-17", 531.75), close("2026-09-21", 536.25),
                        close("2026-09-22", 540.0), close("2026-09-23", 529.2)),
                RealQuotes.parseStooq(fixture("stooq_reordered.csv")));
    }

    @Test
    void stooqNoticesAndPagesGiveNothing() throws IOException {
        assertEquals(List.of(), RealQuotes.parseStooq(fixture("stooq_nodata.txt")));
        assertEquals(List.of(), RealQuotes.parseStooq(fixture("stooq_limit.txt")));
        assertEquals(List.of(), RealQuotes.parseStooq(fixture("html_block.html")));
        assertEquals(List.of(), RealQuotes.parseStooq(""));
        assertEquals(List.of(), RealQuotes.parseStooq("\n\n"));
        assertEquals(List.of(), RealQuotes.parseStooq(null));
        assertEquals(List.of(), RealQuotes.parseStooq("Date,Open,High,Low,Volume\n2026-09-25,1,2,3,4\n"),
                "no Close column");
        assertEquals(List.of(), RealQuotes.parseStooq("Date,Close\n"), "a header alone");
        assertCloses(List.of(close("2026-09-25", 12.5)),
                RealQuotes.parseStooq("﻿Date,Close\n2026-09-25,12.5\n2026-09-26\n"), "BOM and a short row");
    }

    // ---- Yahoo --------------------------------------------------------------------------

    @Test
    void yahooClosesMatchStooqsAndTheLaterBarWinsARepeatedDay() throws IOException {
        // The last two bars are both Sep 25 in New York (midnight, and 17:00 once it closed).
        List<DailyClose> gold = RealQuotes.parseYahoo(fixture("yahoo_ok.json"));
        assertCloses(GOLD, gold);
        assertEquals(3659.800048828125, gold.get(0).close(), "Yahoo's float-sized closes come through as sent");
        assertEquals(3755.5, gold.get(gold.size() - 1).close(), "not the 3750.10 midnight bar");
    }

    @Test
    void yahooSkipsNullCloses() throws IOException {
        assertCloses(List.of(close("2026-09-14", 524.5), close("2026-09-16", 531.75),
                        close("2026-09-18", 536.25), close("2026-09-21", 540.0), close("2026-09-23", 540.0),
                        close("2026-09-24", 535.5)),
                RealQuotes.parseYahoo(fixture("yahoo_nulls.json")));
    }

    @Test
    void yahooErrorsAndJunkGiveNothing() throws IOException {
        assertEquals(List.of(), RealQuotes.parseYahoo(fixture("yahoo_error.json")));
        assertEquals(List.of(), RealQuotes.parseYahoo(fixture("html_block.html")));
        assertEquals(List.of(), RealQuotes.parseYahoo(fixture("stooq_ok.csv")));
        assertEquals(List.of(), RealQuotes.parseYahoo("{}"));
        assertEquals(List.of(), RealQuotes.parseYahoo("[]"));
        assertEquals(List.of(), RealQuotes.parseYahoo("{\"chart\":{\"result\":[],\"error\":null}}"));
        assertEquals(List.of(), RealQuotes.parseYahoo("{\"chart\":{\"result\":[{\"timestamp\":\"x\"}]}}"));
        assertEquals(List.of(), RealQuotes.parseYahoo("{\"chart\":{\"result\":[{\"timestamp\":[1],"
                + "\"indicators\":{\"quote\":[{\"close\":[5.0]}]}}],\"error\":{\"code\":\"x\"}}}"), "error wins");
        assertEquals(List.of(), RealQuotes.parseYahoo("{\"chart\":"));
        assertEquals(List.of(), RealQuotes.parseYahoo(""));
        assertEquals(List.of(), RealQuotes.parseYahoo(null));
    }

    @Test
    void yahooTradeDatesAreTheExchanges() {
        // 15:00 UTC on Sep 24 is already Sep 25 in Tokyo.
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"exchangeTimezoneName\":\"%s\"},"
                + "\"timestamp\":[1790262000,1790348400],"
                + "\"indicators\":{\"quote\":[{\"close\":[100.0,-1.0]}]}}],\"error\":null}}";
        assertCloses(List.of(close("2026-09-25", 100.0)), RealQuotes.parseYahoo(String.format(body, "Asia/Tokyo")));
        assertCloses(List.of(close("2026-09-24", 100.0)), RealQuotes.parseYahoo(String.format(body, "Not/AZone")),
                "an unknown zone is UTC");
        assertCloses(List.of(close("2026-09-24", 100.0)), RealQuotes.parseYahoo(body.replace(
                "\"meta\":{\"exchangeTimezoneName\":\"%s\"},", "")), "no meta is UTC");
    }

    // ---- mapping ------------------------------------------------------------------------

    @Test
    void theLatestMoveIsBetweenTheTwoNewestTradeDays() throws IOException {
        RealQuotes.Move move = RealQuotes.latestMove(RealQuotes.parseStooq(fixture("stooq_ok.csv")), TODAY, 4)
                .orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 24), move.prevDay());
        assertEquals(3700.0, move.prevClose());
        assertEquals(LocalDate.of(2026, 9, 25), move.day());
        assertEquals(3755.5, move.close());
        assertEquals(0.015, move.change(), EPS);

        RealQuotes.Move wheat = RealQuotes.latestMove(RealQuotes.parseStooq(fixture("stooq_reordered.csv")), TODAY, 4)
                .orElseThrow();
        assertEquals(-0.02, wheat.change(), EPS);
        assertEquals(RealQuotes.latestMove(GOLD, TODAY, 4),
                RealQuotes.latestMove(RealQuotes.parseYahoo(fixture("yahoo_ok.json")), TODAY, 4));
    }

    @Test
    void theLatestMoveIgnoresDataMoreThanFourDaysOld() {
        assertTrue(RealQuotes.latestMove(GOLD, LocalDate.of(2026, 9, 29), 4).isPresent(), "Sep 25 is 4 days back");
        assertFalse(RealQuotes.latestMove(GOLD, LocalDate.of(2026, 9, 30), 4).isPresent(), "5 days is stale");
        assertFalse(RealQuotes.latestMove(List.of(close("2026-09-25", 10)), TODAY, 4).isPresent(), "one day");
        assertFalse(RealQuotes.latestMove(List.of(), TODAY, 4).isPresent());
        assertFalse(RealQuotes.latestMove(null, TODAY, 4).isPresent());

        // A day after "today" is not a close yet (or is bad data): the two before it are used.
        List<DailyClose> withFuture = List.of(close("2026-09-24", 100), close("2026-09-25", 101),
                close("2026-09-28", 150));
        RealQuotes.Move m = RealQuotes.latestMove(withFuture, TODAY, 4).orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 25), m.day());
        assertEquals(0.01, m.change(), EPS);
    }

    @Test
    void theNudgeIsTwiceTheMoveCappedAndSkippedAboveEightPercent() {
        assertEquals(0.03, RealQuotes.impulse(0.015, 2.0, 0.04, 0.08), EPS);
        assertEquals(-0.03, RealQuotes.impulse(-0.015, 2.0, 0.04, 0.08), EPS);
        assertEquals(0.04, RealQuotes.impulse(0.03, 2.0, 0.04, 0.08), EPS, "capped at max_percent");
        assertEquals(-0.04, RealQuotes.impulse(-0.02, 2.0, 0.04, 0.08), EPS);
        assertEquals(0.04, RealQuotes.impulse(0.08, 2.0, 0.04, 0.08), EPS, "8% itself still counts");
        assertTrue(Double.isNaN(RealQuotes.impulse(0.081, 2.0, 0.04, 0.08)), "above 8% is bad data");
        assertTrue(Double.isNaN(RealQuotes.impulse(-0.2, 2.0, 0.04, 0.08)));
        assertTrue(Double.isNaN(RealQuotes.impulse(Double.NaN, 2.0, 0.04, 0.08)));
        assertTrue(Double.isNaN(RealQuotes.impulse(Double.POSITIVE_INFINITY, 2.0, 0.04, 0.08)));
        assertEquals(0.0, RealQuotes.impulse(0.0, 2.0, 0.04, 0.08));

        // The config can make it calmer, never wilder than the 4.5% predictable cap.
        assertEquals(0.045, RealQuotes.impulse(0.05, 2.0, 0.10, 0.08), EPS);
        assertEquals(0.01, RealQuotes.impulse(0.05, 2.0, 0.01, 0.08), EPS);
        assertEquals(0.0, RealQuotes.impulse(0.05, 2.0, -0.04, 0.08));
    }

    // ---- URLs and symbols ---------------------------------------------------------------

    @Test
    void theUrlEncodesTheSymbolAndFillsTheDates() {
        assertEquals("https://stooq.com/q/d/l/?s=gc.f&d1=20260913&d2=20260927&i=d",
                RealQuotes.url(RealQuotes.STOOQ_URL, "gc.f", TODAY));
        assertEquals("https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?range=1mo&interval=1d",
                RealQuotes.url(RealQuotes.YAHOO_URL, "GC=F", TODAY));
        assertEquals("https://example.org/q?s=%5EGSPC&from=20251218&to=20260101",
                RealQuotes.url(" https://example.org/q?s={symbol}&from={from}&to={to} ", "^GSPC",
                        LocalDate.of(2026, 1, 1)));

        assertEquals(RealQuotes.STOOQ_URL, RealQuotes.defaultUrl("stooq"));
        assertEquals(RealQuotes.YAHOO_URL, RealQuotes.defaultUrl(" Yahoo "));
        assertEquals(null, RealQuotes.defaultUrl("bloomberg"));
        assertTrue(RealQuotes.validUrl(RealQuotes.STOOQ_URL));
        assertTrue(RealQuotes.validUrl("HTTPS://stooq.com/{symbol}"));
        assertFalse(RealQuotes.validUrl("http://stooq.com/{symbol}"));
        assertFalse(RealQuotes.validUrl("https://"));
        assertFalse(RealQuotes.validUrl(""));
        assertFalse(RealQuotes.validUrl(null));
    }

    @Test
    void symbolsMatchTheAllowedPattern() {
        for (String ok : List.of("gc.f", "GC=F", "zw.f", "HG=F", "LBR=F", "^GSPC", "a", "x_y-z", "1234567890123456")) {
            assertTrue(RealQuotes.validSymbol(ok), ok);
        }
        for (String bad : List.of("", "gc f", "gc/f", "gc.f&x=1", "12345678901234567", "güld", "{symbol}")) {
            assertFalse(RealQuotes.validSymbol(bad), bad);
        }
        assertFalse(RealQuotes.validSymbol(null));
    }
}
