package com.dierks.homecraft.web;

import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.SeasonCalendar;
import com.dierks.homecraft.market.sim.SimMath;
import com.dierks.homecraft.market.sim.Source;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /api/news} shape (spec §14.2) and the event-to-row rules it shares with
 * {@code /api/market}.
 *
 * <p>Pinned here: the spec's example byte for byte; {@code live:false} is always exactly
 * {@code {"generatedAt":…,"live":false,"active":[],"news":[]}}; {@code season} is left out when
 * none runs; {@code news} is at most 50 rows from the last 7 days, newest first; a season's
 * {@code item} is JSON {@code null} and {@code before}/{@code after} appear only with both
 * prices; colour codes are stripped and the output is strict JSON.
 *
 * <p>And the one front-running rule: a HOT or DEAL is not news, not active and not a chart
 * marker until its silent ramp is over, and never if it was stopped during the ramp — so the
 * website cannot show a ramp before players are told.
 */
class NewsFeedTest {

    private static final long H = SimMath.HOUR_MS;
    private static final long T = 1_790_000_000_000L;
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static NewsFeed.NewsRow row(long id, long t) {
        return new NewsFeed.NewsRow(id, t, "up", "wheat", "Wheat", 1, 22, 3.46, 4.22, "h" + id, "l" + id, "sim", true);
    }

    private static MarketEvent hot(long t0) {
        return MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, t0, 4 * H, 30 * H, 10 * H);
    }

    // ---- shape --------------------------------------------------------------------------

    @Test
    void theSpecExampleByteForByte() {
        NewsFeed.SeasonRow season = new NewsFeed.SeasonRow("harvest_time", "Harvest Time", 1_793_512_800_000L);
        NewsFeed.ActiveRow iron = new NewsFeed.ActiveRow("iron_ingot", "Iron Ingot", "hot", 11.7,
                1_789_950_000_000L, 1_790_190_000_000L);
        NewsFeed.NewsRow harvest = new NewsFeed.NewsRow(55, 1_789_900_000_000L, "season", null, "Harvest Time", -1,
                -4.0, null, null, "Harvest time! The farms are full of wheat.", "Wheat -4% until Oct 31",
                "calendar", true);
        NewsFeed.NewsRow wheat = new NewsFeed.NewsRow(57, 1_789_999_000_000L, "up", "wheat", "Wheat", 1, 22.0,
                3.46, 4.22, "The villagers are having a party and need Wheat!",
                "&aWheat is going UP! &fCrate pays $3.29 → $4.01 &7(+22%) &e&lSell now!", "sim", true);

        String json = NewsFeed.json(T, true, season, List.of(iron), List.of(harvest, wheat));

        assertEquals("{\"generatedAt\":1790000000000,\"live\":true,"
                + "\"season\":{\"id\":\"harvest_time\",\"name\":\"Harvest Time\",\"endsAt\":1793512800000},"
                + "\"active\":[{\"item\":\"iron_ingot\",\"name\":\"Iron Ingot\",\"kind\":\"hot\",\"pct\":11.70,"
                + "\"startedAt\":1789950000000,\"endsAt\":1790190000000}],"
                + "\"news\":[{\"id\":57,\"t\":1789999000000,\"kind\":\"up\",\"item\":\"wheat\",\"name\":\"Wheat\","
                + "\"dir\":1,\"pct\":22.00,\"before\":3.46,\"after\":4.22,"
                + "\"headline\":\"The villagers are having a party and need Wheat!\","
                + "\"line\":\"Wheat is going UP! Crate pays $3.29 → $4.01 (+22%) Sell now!\","
                + "\"source\":\"sim\",\"active\":true},"
                + "{\"id\":55,\"t\":1789900000000,\"kind\":\"season\",\"item\":null,\"name\":\"Harvest Time\","
                + "\"dir\":-1,\"pct\":-4.00,\"headline\":\"Harvest time! The farms are full of wheat.\","
                + "\"line\":\"Wheat -4% until Oct 31\",\"source\":\"calendar\",\"active\":true}]}", json);

        JsonObject root = MarketFeedTest.strict(json).getAsJsonObject();
        assertEquals(List.of("generatedAt", "live", "season", "active", "news"), new ArrayList<>(root.keySet()));
        JsonObject first = root.getAsJsonArray("news").get(0).getAsJsonObject();
        assertEquals(List.of("id", "t", "kind", "item", "name", "dir", "pct", "before", "after", "headline", "line",
                "source", "active"), new ArrayList<>(first.keySet()));
        assertTrue(root.getAsJsonArray("news").get(1).getAsJsonObject().get("item").isJsonNull());
    }

    @Test
    void offIsAlwaysTheSameShortFeed() {
        String expected = "{\"generatedAt\":1790000000000,\"live\":false,\"active\":[],\"news\":[]}";
        assertEquals(expected, NewsFeed.json(T, false, null, null, null));
        assertEquals(expected, NewsFeed.json(T, false, new NewsFeed.SeasonRow("s", "S", T),
                        List.of(new NewsFeed.ActiveRow("a", "A", "hot", 1, T, T)), List.of(row(1, T))),
                "a paused sim says nothing, whatever it was handed");
        MarketFeedTest.strict(expected);
    }

    @Test
    void seasonIsLeftOutWhenNoneRunsAndEmptyListsAreEmptyArrays() {
        String json = NewsFeed.json(T, true, null, null, null);
        assertEquals("{\"generatedAt\":1790000000000,\"live\":true,\"active\":[],\"news\":[]}", json);
        List<NewsFeed.ActiveRow> active = new ArrayList<>();
        active.add(null);
        List<NewsFeed.NewsRow> news = new ArrayList<>();
        news.add(null);
        assertEquals(json, NewsFeed.json(T, true, null, active, news), "null rows are skipped");
    }

    @Test
    void newsIsTheNewestFiftyOfTheLastSevenDays() {
        List<NewsFeed.NewsRow> rows = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            rows.add(row(i, T - i * 3 * H)); // one every 3 hours, back 10 days
        }
        rows.add(row(500, T + 1)); // not yet: never published
        rows.add(row(501, T - NewsFeed.NEWS_WINDOW_MS)); // exactly 7 days: kept
        rows.add(row(502, T - NewsFeed.NEWS_WINDOW_MS - 1)); // just older: dropped

        List<NewsFeed.NewsRow> picked = NewsFeed.select(rows, T, NewsFeed.NEWS_LIMIT);
        assertEquals(50, picked.size());
        assertEquals(0, picked.get(0).id(), "newest first");
        for (int i = 1; i < picked.size(); i++) {
            assertTrue(picked.get(i - 1).t() >= picked.get(i).t());
        }

        List<NewsFeed.NewsRow> all = NewsFeed.select(rows, T, 1000);
        assertEquals(58, all.size(), "0..56 (56 x 3h is exactly 7 days) plus 501, also at exactly 7 days");
        assertTrue(all.stream().noneMatch(r -> r.id() == 500 || r.id() == 502));
        assertTrue(all.stream().anyMatch(r -> r.id() == 501));

        List<NewsFeed.NewsRow> ties = NewsFeed.select(List.of(row(3, T), row(9, T), row(4, T)), T, 10);
        assertEquals(List.of(9L, 4L, 3L), ties.stream().map(NewsFeed.NewsRow::id).toList(),
                "same time: newest id first");
        assertEquals(List.of(), NewsFeed.select(null, T, 10));
        assertEquals(List.of(), NewsFeed.select(rows, T, 0));

        JsonObject root = MarketFeedTest.strict(NewsFeed.json(T, true, null, null, rows)).getAsJsonObject();
        assertEquals(50, root.getAsJsonArray("news").size());
    }

    @Test
    void coloursAreStrippedAndTextIsEscaped() {
        NewsFeed.NewsRow r = new NewsFeed.NewsRow(1, T, "hot", "od\"d", "&6Oak §lLog", 1, 12.346, null, 5.0,
                "&fHot, hot, \"hot\"!\n", null, "admin", false);
        String json = NewsFeed.json(T, true, new NewsFeed.SeasonRow("x", "&2Harvest &lTime", 1),
                List.of(new NewsFeed.ActiveRow("oak_log", "&aOak Log", "hot", Double.NaN, 1, 2)), List.of(r));
        JsonObject root = MarketFeedTest.strict(json).getAsJsonObject();
        JsonObject news = root.getAsJsonArray("news").get(0).getAsJsonObject();
        assertEquals("Oak Log", news.get("name").getAsString());
        assertEquals("Hot, hot, \"hot\"!", news.get("headline").getAsString());
        assertEquals("", news.get("line").getAsString(), "a missing line is an empty string");
        assertEquals("od\"d", news.get("item").getAsString());
        assertEquals(12.35, news.get("pct").getAsDouble());
        assertFalse(news.has("before"), "only one price: neither is written");
        assertFalse(news.has("after"));
        assertEquals("Harvest Time", root.getAsJsonObject("season").get("name").getAsString());
        JsonObject active = root.getAsJsonArray("active").get(0).getAsJsonObject();
        assertEquals("Oak Log", active.get("name").getAsString());
        assertEquals(0, active.get("pct").getAsDouble(), "NaN writes 0, the feed stays parseable");
    }

    // ---- from events --------------------------------------------------------------------

    @Test
    void aHotOrDealIsInvisibleUntilItsRampIsOver() {
        MarketEvent hot = hot(T);
        for (long t : new long[] {T - 1, T, T + H, T + 4 * H - 1}) {
            assertFalse(NewsFeed.visible(hot, t), "ramping at " + (t - T));
            assertNull(NewsFeed.newsRow(hot, "Oak Log", t));
            assertNull(NewsFeed.activeRow(hot, "Oak Log", 5, t));
            assertNull(NewsFeed.mark(hot, t));
        }
        long full = T + 4 * H;
        assertTrue(NewsFeed.visible(hot, full));
        NewsFeed.NewsRow row = NewsFeed.newsRow(hot, "Oak Log", full);
        assertNotNull(row);
        assertEquals(full, row.t(), "it became news when it reached full strength");
        assertNotNull(NewsFeed.activeRow(hot, "Oak Log", 12, full));
        MarketFeed.EventMark mark = NewsFeed.mark(hot, full);
        assertNotNull(mark);
        assertEquals(T, mark.t(), "the chart marker sits where the move began");

        MarketEvent stoppedInRamp = hot.withStop(T + 2 * H, "stopped");
        for (long t = T; t < T + 60 * H; t += H) {
            assertFalse(NewsFeed.visible(stoppedInRamp, t), "stopped while silent: never news");
            assertNull(NewsFeed.newsRow(stoppedInRamp, "Oak Log", t));
            assertNull(NewsFeed.mark(stoppedInRamp, t));
        }
        MarketEvent stoppedLater = hot.withStop(T + 10 * H, "stopped");
        assertTrue(NewsFeed.visible(stoppedLater, T + 10 * H + 1));
        assertNotNull(NewsFeed.activeRow(stoppedLater, "Oak Log", 3, T + 10 * H + 1), "fading after a stop");
        assertNull(NewsFeed.activeRow(stoppedLater, "Oak Log", 3, T + 11 * H), "over an hour after the stop");

        MarketEvent adminDeal = MarketEvent.story(EventKind.DEAL, Source.ADMIN, "iron_ingot", 0.1, T, 0,
                30 * H, 10 * H);
        assertTrue(NewsFeed.visible(adminDeal, T), "an admin story starts at full strength");
    }

    @Test
    void aNewsRowCarriesTheEventsFields() {
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.22, T, 6 * H, 30 * H)
                .withId(57).withPrices(22.0, 3.46, 4.22)
                .withText("&fThe villagers need Wheat!", "&aWheat is going UP!");
        NewsFeed.NewsRow r = NewsFeed.newsRow(up, "Wheat", T + H);
        assertEquals(new NewsFeed.NewsRow(57, T, "up", "wheat", "Wheat", 1, 22.0, 3.46, 4.22,
                "&fThe villagers need Wheat!", "&aWheat is going UP!", "sim", true), r);
        assertFalse(NewsFeed.newsRow(up, "Wheat", T + 30 * H).active(), "gone at 30 h");
        assertNull(NewsFeed.newsRow(up, "Wheat", T - 1), "not before it happened");

        MarketEvent down = MarketEvent.shock(EventKind.DOWN, Source.ADMIN, "iron_ingot", 0.18, T, 6 * H, 30 * H)
                .withPrices(18.0, 22.49, 18.44);
        NewsFeed.NewsRow d = NewsFeed.newsRow(down, "Iron Ingot", T);
        assertEquals(-1, d.dir());
        assertEquals(-18.0, d.pct(), "a DOWN reads down whatever sign its pct was stored with");
        assertEquals("admin", d.source());

        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026",
                        T, T + 800 * H)
                .withPrices(-4.0, 0, 0).withText("Harvest time!", "Wheat -4% until Oct 31");
        NewsFeed.NewsRow s = NewsFeed.newsRow(season, "Harvest Time", T + H);
        assertNull(s.item());
        assertEquals(-1, s.dir());
        assertNull(s.before(), "no prices on a season");
        assertEquals("calendar", s.source());
        assertTrue(s.active());

        MarketEvent wanted = MarketEvent.info(EventKind.WANTED, Source.SIM, "gold_ingot", null, T, T)
                .withText("Crate is looking for Gold Ingots!", "&eBe the first to sell some!");
        NewsFeed.NewsRow w = NewsFeed.newsRow(wanted, "Gold Ingot", T);
        assertEquals(0, w.dir());
        assertEquals("wanted", w.kind());
        assertFalse(w.active());
        assertNull(NewsFeed.activeRow(wanted, "Gold Ingot", 0, T), "WANTED is not a live price event");
        assertNull(NewsFeed.mark(wanted, T));

        MarketEvent quietReal = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", -0.02, T, 24 * H, 96 * H);
        assertNull(NewsFeed.newsRow(quietReal, "Gold Ingot", T), "a REAL too small for a headline is not news…");
        MarketFeed.EventMark m = NewsFeed.mark(quietReal, T);
        assertNotNull(m, "…but it still marks the chart");
        assertEquals(T, m.t());
        assertEquals("real", m.kind());
        assertEquals(-2.0, m.pct(), 1e-9);
        assertNull(NewsFeed.activeRow(quietReal, "Gold Ingot", 0, T));
        MarketEvent announced = quietReal.withText("In the real world, gold prices went down today!", "");
        NewsFeed.NewsRow loud = NewsFeed.newsRow(announced, "Gold Ingot", T);
        assertEquals(-1, loud.dir());
        assertEquals("real", loud.source());
    }

    @Test
    void activeRowsAreRunningMoodEvents() {
        MarketEvent hot = hot(T);
        NewsFeed.ActiveRow a = NewsFeed.activeRow(hot, "Oak Log", 11.7, T + 5 * H);
        assertEquals(new NewsFeed.ActiveRow("oak_log", "Oak Log", "hot", 11.7, T, T + 44 * H), a);
        assertNull(NewsFeed.activeRow(hot, "Oak Log", 11.7, T + 44 * H), "over at ends_at");
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.22, T, 6 * H, 30 * H);
        assertNotNull(NewsFeed.activeRow(up, "Wheat", 1.0, T + 20 * H), "an UP in its tail is still live");
        assertNull(NewsFeed.activeRow(null, "x", 0, T));
    }

    @Test
    void theSeasonRowComesFromTheCalendar() {
        long shipDay = ZonedDateTime.of(2026, 9, 27, 12, 0, 0, 0, CHICAGO).toInstant().toEpochMilli();
        SeasonCalendar.Active harvest = SeasonCalendar.active(SeasonCalendar.SHIPPED, shipDay, CHICAGO, 2).stream()
                .filter(a -> a.season().id().equals("harvest_time")).findFirst().orElseThrow();
        NewsFeed.SeasonRow row = NewsFeed.seasonRow(harvest, CHICAGO);
        long midnightAfterOct31 = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, CHICAGO).toInstant().toEpochMilli();
        assertEquals(new NewsFeed.SeasonRow("harvest_time", "Harvest Time", midnightAfterOct31), row);
        assertEquals(1_793_509_200_000L, row.endsAt());
        assertNull(NewsFeed.seasonRow(null, CHICAGO));
    }

    /**
     * A real-world move on a sold-out or clamped item is stored with before == after and pct 0: the
     * feed must report 0, not fall back to the impulse's strength and claim a move Crate never made.
     */
    @Test
    void aRealMoveThatDidNotMoveCratesPriceReportsZero() {
        MarketEvent stuck = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", 0.04, T, 24 * H, 96 * H)
                .toBuilder().pct(0.0).priceBefore(150.0).priceAfter(150.0).build();
        assertEquals(0.0, NewsFeed.pct(stuck), 1e-9, "measured, and it did not move");
        MarketEvent unmeasured = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", 0.04, T, 24 * H, 96 * H);
        assertEquals(4.0, NewsFeed.pct(unmeasured), 1e-9, "no prices recorded: the strength stands in");
        MarketEvent stuckDown = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", -0.03, T, 24 * H, 96 * H)
                .toBuilder().pct(0.0).priceBefore(150.0).priceAfter(150.0).build();
        assertEquals("0.00", Json.num2(NewsFeed.pct(stuckDown)), "a stuck down-move is 0, never -0.00");
    }
}
