package com.dierks.homecraft.web;

import com.dierks.homecraft.storage.PriceHistoryDao.Snapshot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /api/market} shape the website parses.
 *
 * <p>Pinned here: every field the feed carried before the long ranges keeps its name, order
 * and formatting byte for byte (checked both against a literal and against a verbatim copy of
 * the old in-server builder); {@code history7d} / {@code history30d} come after
 * {@code history}, with {@code p} to four decimals, and are absent — not {@code []} — while
 * empty; names lose their colour codes; the output is strict JSON.
 *
 * <p>And {@link MarketFeed#windowStart}: the window the long ranges are read from is
 * epoch-aligned and covers exactly N buckets, the newest being the one that holds "now", so
 * one point per bucket is never more than the 168 / 120 points the site expects and never
 * reaches further back than 7 / 30 days.
 *
 * <p>The live market (0.33): with no {@code SimInfo} on any row and no {@code Extras}, the feed
 * is byte for byte the 0.32 one; otherwise {@code usual}/{@code moodPct} always,
 * {@code status}/{@code statusEndsAt} only with a badge and {@code events} only when there are
 * some (the newest 20 of 30 days, oldest first, chart kinds only) follow the last history
 * array, and {@code live}/{@code hot}/{@code deals}/{@code season}/{@code news} follow
 * {@code items} — {@code season} left out when none, {@code news} the newest 5 of 7 days.
 */
class MarketFeedTest {

    private static final long T = 1_790_000_000_000L;
    private static final long HALF_HOUR = 1_800_000L;

    private static Snapshot snap(long t, double p, long s) {
        return new Snapshot("iron_ingot", p, s, t);
    }

    private static MarketFeed.Row iron(List<Snapshot> history, List<Snapshot> h7, List<Snapshot> h30) {
        return new MarketFeed.Row("iron_ingot", "&fIron &lIngot", "IRON_INGOT", 2.4, 2.52, 2.28,
                4200, 10000, 0.9, history, h7, h30);
    }

    // ---- the legacy shape ---------------------------------------------------------------

    @Test
    void aRowWithoutLongHistoryIsExactlyTheOldFeed() {
        List<Snapshot> history = List.of(snap(T - HALF_HOUR, 2.38, 4150), snap(T, 2.4, 4200));
        String json = MarketFeed.json("Crate Market", T, 30, List.of(iron(history, List.of(), null)));

        assertEquals("{\"title\":\"Crate Market\",\"generatedAt\":1790000000000,\"refreshSeconds\":30,"
                + "\"items\":[{\"id\":\"iron_ingot\",\"name\":\"Iron Ingot\",\"material\":\"IRON_INGOT\","
                + "\"price\":2.40,\"buy\":2.52,\"sell\":2.28,\"stock\":4200,\"maxStock\":10000,"
                + "\"change24h\":0.90,\"history\":[{\"t\":1789998200000,\"p\":2.38,\"s\":4150},"
                + "{\"t\":1790000000000,\"p\":2.40,\"s\":4200}]}]}", json);
        assertFalse(json.contains("history7d"), "an empty long range is left out, not sent as []");
        assertFalse(json.contains("history30d"));
    }

    @Test
    void theOldFieldsMatchTheOldBuilderByteForByte() {
        List<MarketFeed.Row> rows = new ArrayList<>();
        rows.add(iron(List.of(snap(T - HALF_HOUR, 2.375, 4150), snap(T, 2.4, 4200)), null, null));
        rows.add(new MarketFeed.Row("odd\"id", "§6Gold \\ \"Bar\"\n", "GOLD_INGOT", Double.NaN,
                Double.POSITIVE_INFINITY, -0.001, 0, 64, -12.345, null, null, null));
        rows.add(new MarketFeed.Row("empty", null, "", 1e7, 0.125, 0, -5, 0, 0, List.of(), List.of(), List.of()));

        String json = MarketFeed.json("My \"Market\"\t★", T, 45, rows);
        assertEquals(legacy("My \"Market\"\t★", T, 45, rows), json);
    }

    @Test
    void emptyAndNullRowListsWriteAnEmptyItemsArray() {
        String expected = "{\"title\":\"T\",\"generatedAt\":5,\"refreshSeconds\":2,\"items\":[]}";
        assertEquals(expected, MarketFeed.json("T", 5, 2, List.of()));
        assertEquals(expected, MarketFeed.json("T", 5, 2, null));
        List<MarketFeed.Row> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals(expected, MarketFeed.json("T", 5, 2, withNull));
        assertEquals("{\"title\":\"\",\"generatedAt\":5,\"refreshSeconds\":2,\"items\":[]}",
                MarketFeed.json(null, 5, 2, List.of()));
    }

    @Test
    void theNameLosesItsColourCodesButIdAndMaterialAreUntouched() {
        MarketFeed.Row row = new MarketFeed.Row("&aid", "&6&lGolden §bApple &r", "&cMAT", 1, 1, 1, 1, 1, 0,
                null, null, null);
        JsonObject item = items(MarketFeed.json("x", T, 30, List.of(row))).get(0);
        assertEquals("Golden Apple", item.get("name").getAsString());
        assertEquals("&aid", item.get("id").getAsString());
        assertEquals("&cMAT", item.get("material").getAsString());
    }

    // ---- the long ranges ----------------------------------------------------------------

    @Test
    void bothLongRangesFollowHistoryWithFourDecimalPrices() {
        List<Snapshot> history = List.of(snap(T, 2.4, 4200));
        List<Snapshot> h7 = List.of(snap(T - 2 * MarketFeed.HOUR_MS, 2.3100, 4020),
                snap(T - MarketFeed.HOUR_MS, 2.123456, 4100));
        List<Snapshot> h30 = List.of(snap(T - 6 * MarketFeed.HOUR_MS, 2.0, 3900), snap(T, 2.00005, 3950));
        String json = MarketFeed.json("Crate Market", T, 30, List.of(iron(history, h7, h30)));

        assertEquals("{\"title\":\"Crate Market\",\"generatedAt\":1790000000000,\"refreshSeconds\":30,"
                + "\"items\":[{\"id\":\"iron_ingot\",\"name\":\"Iron Ingot\",\"material\":\"IRON_INGOT\","
                + "\"price\":2.40,\"buy\":2.52,\"sell\":2.28,\"stock\":4200,\"maxStock\":10000,"
                + "\"change24h\":0.90,\"history\":[{\"t\":1790000000000,\"p\":2.40,\"s\":4200}],"
                + "\"history7d\":[{\"t\":1789992800000,\"p\":2.31,\"s\":4020},"
                + "{\"t\":1789996400000,\"p\":2.1235,\"s\":4100}],"
                + "\"history30d\":[{\"t\":1789978400000,\"p\":2,\"s\":3900},"
                + "{\"t\":1790000000000,\"p\":2.0001,\"s\":3950}]}]}", json);
    }

    @Test
    void eachLongRangeIsLeftOutOnItsOwnWhileEmpty() {
        List<Snapshot> some = List.of(snap(T, 2.5, 10));

        String only30 = MarketFeed.json("x", T, 30, List.of(iron(List.of(), List.of(), some)));
        assertFalse(only30.contains("\"history7d\""));
        assertTrue(only30.contains(",\"history\":[],\"history30d\":[{\"t\":1790000000000,\"p\":2.5,\"s\":10}]}"),
                only30);

        String only7 = MarketFeed.json("x", T, 30, List.of(iron(null, some, null)));
        assertFalse(only7.contains("\"history30d\""));
        assertTrue(only7.contains(",\"history\":[],\"history7d\":[{\"t\":1790000000000,\"p\":2.5,\"s\":10}]}"),
                only7);
    }

    @Test
    void historyIsAlwaysPresentEvenWhenEmpty() {
        JsonObject item = items(MarketFeed.json("x", T, 30, List.of(iron(null, null, null)))).get(0);
        assertTrue(item.has("history"));
        assertEquals(0, item.getAsJsonArray("history").size());
    }

    @Test
    void severalItemsKeepTheirOrderAndParseAsStrictJson() {
        List<MarketFeed.Row> rows = new ArrayList<>();
        for (String id : new String[] {"stone", "coal", "iron_ingot", "diamond"}) {
            List<Snapshot> h = List.of(snap(T - 1, 1.23456789, 1), snap(T, 9.87654321, 2));
            rows.add(new MarketFeed.Row(id, id, id.toUpperCase(Locale.ROOT), 1, 1, 1, 1, 1, 0, h, h, h));
        }
        List<JsonObject> items = items(MarketFeed.json("x", T, 30, rows));
        assertEquals(4, items.size());
        assertEquals("stone", items.get(0).get("id").getAsString());
        assertEquals("coal", items.get(1).get("id").getAsString());
        assertEquals("iron_ingot", items.get(2).get("id").getAsString());
        assertEquals("diamond", items.get(3).get("id").getAsString());
        for (JsonObject item : items) {
            assertEquals(List.of("id", "name", "material", "price", "buy", "sell", "stock", "maxStock",
                    "change24h", "history", "history7d", "history30d"), new ArrayList<>(item.keySet()));
            assertEquals(1.23, item.getAsJsonArray("history").get(0).getAsJsonObject().get("p").getAsDouble());
            assertEquals(1.2346, item.getAsJsonArray("history7d").get(0).getAsJsonObject().get("p").getAsDouble());
            assertEquals(9.8765, item.getAsJsonArray("history30d").get(1).getAsJsonObject().get("p").getAsDouble());
        }
    }

    @Test
    void aRowCopiesItsListsAndTreatsNullAsEmpty() {
        List<Snapshot> mutable = new ArrayList<>(List.of(snap(T, 1, 1)));
        MarketFeed.Row row = iron(mutable, null, null);
        mutable.add(snap(T + 1, 2, 2));
        assertEquals(1, row.history().size(), "a later change to the caller's list does not reach the row");
        assertEquals(List.of(), row.history7d());
        assertEquals(List.of(), row.history30d());
    }

    // ---- windowStart --------------------------------------------------------------------

    @Test
    void theSevenDayWindowIs168AlignedHoursEndingWithTheCurrentOne() {
        long hour = MarketFeed.H7_BUCKET_MS;
        long boundary = 497_222L * hour; // 2026-09-21 14:00 UTC, exactly on an hour

        assertEquals(boundary - 167 * hour,
                MarketFeed.windowStart(boundary, hour, MarketFeed.H7_POINTS), "now exactly on a boundary");
        assertEquals(boundary - 167 * hour,
                MarketFeed.windowStart(boundary + 1_234_567, hour, MarketFeed.H7_POINTS), "now mid-hour");
        assertEquals(boundary - 167 * hour,
                MarketFeed.windowStart(boundary + hour - 1, hour, MarketFeed.H7_POINTS), "the hour's last ms");
        assertEquals(1_789_398_000_000L, MarketFeed.windowStart(T, hour, MarketFeed.H7_POINTS));
    }

    @Test
    void theThirtyDayWindowIs120AlignedSixHoursEndingWithTheCurrentOne() {
        long six = MarketFeed.H30_BUCKET_MS;
        long boundary = 82_870L * six;

        assertEquals(boundary - 119 * six,
                MarketFeed.windowStart(boundary, six, MarketFeed.H30_POINTS), "now exactly on a boundary");
        assertEquals(boundary - 119 * six,
                MarketFeed.windowStart(boundary + 5 * MarketFeed.HOUR_MS, six, MarketFeed.H30_POINTS),
                "now mid-bucket");
        assertEquals(1_787_421_600_000L, MarketFeed.windowStart(T, six, MarketFeed.H30_POINTS));
    }

    @Test
    void everyWindowIsAlignedAndCoversExactlyNBucketsWithinTheLastNBucketsOfTime() {
        long[][] ranges = {
                {MarketFeed.H7_BUCKET_MS, MarketFeed.H7_POINTS},
                {MarketFeed.H30_BUCKET_MS, MarketFeed.H30_POINTS},
        };
        for (long[] r : ranges) {
            long bucket = r[0];
            int n = (int) r[1];
            // Step through two whole buckets in uneven strides so boundaries, the ms before
            // and after them, and odd offsets are all visited.
            for (long now = T - 2 * bucket; now <= T + 2 * bucket; now += 7_919_111L) {
                check(now, bucket, n);
                check(now - now % bucket, bucket, n);
                check(now - now % bucket - 1, bucket, n);
            }
        }
    }

    private static void check(long now, long bucket, int n) {
        long start = MarketFeed.windowStart(now, bucket, n);
        String at = "now=" + now + " bucket=" + bucket;
        assertEquals(0, Math.floorMod(start, bucket), "aligned: " + at);
        assertEquals(n, Math.floorDiv(now, bucket) - Math.floorDiv(start, bucket) + 1,
                "the newest bucket holds now and there are exactly N: " + at);
        assertTrue(now - start < (long) n * bucket, "never reaches past the last N buckets of time: " + at);
        assertTrue(now - start >= (long) (n - 1) * bucket, "never shorter than N-1 whole buckets: " + at);
    }

    @Test
    void theRangesAreSevenAndThirtyDays() {
        long day = 24 * MarketFeed.HOUR_MS;
        assertEquals(7 * day, MarketFeed.H7_POINTS * MarketFeed.H7_BUCKET_MS);
        assertEquals(30 * day, MarketFeed.H30_POINTS * MarketFeed.H30_BUCKET_MS);
    }

    @Test
    void windowStartRefusesANonPositiveBucketOrCount() {
        assertThrows(IllegalArgumentException.class, () -> MarketFeed.windowStart(T, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> MarketFeed.windowStart(T, MarketFeed.HOUR_MS, 0));
        assertEquals(T - T % MarketFeed.HOUR_MS, MarketFeed.windowStart(T, MarketFeed.HOUR_MS, 1),
                "one bucket is just the current one");
    }

    // ---- the live market (0.33) ---------------------------------------------------------

    private static MarketFeed.SimInfo ironSim(List<MarketFeed.EventMark> events) {
        return new MarketFeed.SimInfo(22.49, 11.7, "hot", 1_790_190_000_000L, events);
    }

    private static MarketFeed.Row ironWith(MarketFeed.SimInfo sim, List<Snapshot> h7, List<Snapshot> h30) {
        return new MarketFeed.Row("iron_ingot", "Iron Ingot", "IRON_INGOT", 25.12, 26.38, 23.86,
                512, 2048, 11.7, List.of(snap(T, 25.12, 512)), h7, h30, sim);
    }

    @Test
    void withoutTheSimTheFeedIsByteForByteTheOldOne() {
        List<MarketFeed.Row> rows = new ArrayList<>();
        rows.add(iron(List.of(snap(T - HALF_HOUR, 2.375, 4150), snap(T, 2.4, 4200)), null, null));
        rows.add(new MarketFeed.Row("odd\"id", "§6Gold \\ \"Bar\"\n", "GOLD_INGOT", Double.NaN,
                Double.POSITIVE_INFINITY, -0.001, 0, 64, -12.345, null, null, null, null));
        String old = MarketFeed.json("My \"Market\"", T, 45, rows);

        assertEquals(legacy("My \"Market\"", T, 45, rows), MarketFeed.json("My \"Market\"", T, 45, rows, null));
        assertEquals(old, MarketFeed.json("My \"Market\"", T, 45, rows, null));
        assertEquals(iron(null, null, null), new MarketFeed.Row("iron_ingot", "&fIron &lIngot", "IRON_INGOT", 2.4,
                2.52, 2.28, 4200, 10000, 0.9, null, null, null, null), "the 12-argument row has no sim");
        assertNull(iron(null, null, null).sim());
        assertFalse(old.contains("usual") || old.contains("live") || old.contains("news"));
    }

    @Test
    void simFieldsFollowTheLastHistoryArrayInTheDocumentedOrder() {
        List<MarketFeed.EventMark> events = List.of(
                new MarketFeed.EventMark(1_789_950_000_000L, "hot", 11.7),
                new MarketFeed.EventMark(1_789_500_000_000L, "down", -18.0));
        List<Snapshot> h30 = List.of(snap(T, 25.12, 512));
        String json = MarketFeed.json("Crate Market", T, 30, List.of(ironWith(ironSim(events), null, h30)));

        assertEquals("{\"title\":\"Crate Market\",\"generatedAt\":1790000000000,\"refreshSeconds\":30,"
                + "\"items\":[{\"id\":\"iron_ingot\",\"name\":\"Iron Ingot\",\"material\":\"IRON_INGOT\","
                + "\"price\":25.12,\"buy\":26.38,\"sell\":23.86,\"stock\":512,\"maxStock\":2048,"
                + "\"change24h\":11.70,\"history\":[{\"t\":1790000000000,\"p\":25.12,\"s\":512}],"
                + "\"history30d\":[{\"t\":1790000000000,\"p\":25.12,\"s\":512}],"
                + "\"usual\":22.49,\"moodPct\":11.70,\"status\":\"hot\",\"statusEndsAt\":1790190000000,"
                + "\"events\":[{\"t\":1789500000000,\"kind\":\"down\",\"pct\":-18.00},"
                + "{\"t\":1789950000000,\"kind\":\"hot\",\"pct\":11.70}]}]}", json);

        List<Snapshot> some = List.of(snap(T, 1, 1));
        JsonObject item = items(MarketFeed.json("x", T, 30, List.of(ironWith(ironSim(events), some, some)))).get(0);
        assertEquals(List.of("id", "name", "material", "price", "buy", "sell", "stock", "maxStock", "change24h",
                        "history", "history7d", "history30d", "usual", "moodPct", "status", "statusEndsAt", "events"),
                new ArrayList<>(item.keySet()));

        String onlyHistory = MarketFeed.json("x", T, 30, List.of(ironWith(ironSim(List.of()), null, null)));
        assertTrue(onlyHistory.contains("\"history\":[{\"t\":1790000000000,\"p\":25.12,\"s\":512}],\"usual\":22.49,"),
                "with no long ranges the sim fields follow history: " + onlyHistory);
    }

    @Test
    void statusAndEventsAreWrittenOnlyWhenThereAreSome() {
        MarketFeed.SimInfo quiet = new MarketFeed.SimInfo(4.47, -3.2, "", 1_790_190_000_000L, null);
        JsonObject q = items(MarketFeed.json("x", T, 30, List.of(ironWith(quiet, null, null)))).get(0);
        assertEquals(4.47, q.get("usual").getAsDouble());
        assertEquals(-3.2, q.get("moodPct").getAsDouble());
        assertFalse(q.has("status"), "no badge: no status…");
        assertFalse(q.has("statusEndsAt"), "…and no end time either");
        assertFalse(q.has("events"), "no markers: the key is left out, not []");

        MarketFeed.SimInfo wanted = new MarketFeed.SimInfo(150, 0, "wanted", null, List.of());
        JsonObject w = items(MarketFeed.json("x", T, 30, List.of(ironWith(wanted, null, null)))).get(0);
        assertEquals("wanted", w.get("status").getAsString());
        assertFalse(w.has("statusEndsAt"), "WANTED lasts while the item is sold out: no end time");
        assertEquals(0.0, w.get("moodPct").getAsDouble());

        MarketFeed.SimInfo nullStatus = new MarketFeed.SimInfo(1, 0, null, 5L, null);
        assertEquals("", nullStatus.status());
        assertFalse(MarketFeed.json("x", T, 30, List.of(ironWith(nullStatus, null, null))).contains("status"));
    }

    @Test
    void eventMarkersAreTheNewestTwentyOfThirtyDaysOldestFirst() {
        List<MarketFeed.EventMark> marks = new ArrayList<>();
        for (int i = 29; i >= 0; i--) { // newest first on purpose: the feed sorts
            marks.add(new MarketFeed.EventMark(T - i * 24 * MarketFeed.HOUR_MS, i % 2 == 0 ? "up" : "real", i));
        }
        marks.add(null);
        marks.add(new MarketFeed.EventMark(T - MarketFeed.EVENTS_WINDOW_MS - 1, "hot", 99)); // too old
        marks.add(new MarketFeed.EventMark(T + 1, "deal", 99)); // not yet
        marks.add(new MarketFeed.EventMark(T - 1, "wanted", 99)); // not a chart kind
        marks.add(new MarketFeed.EventMark(T - 1, "season", 99));
        marks.add(new MarketFeed.EventMark(T - 1, "HOT", 99));

        MarketFeed.SimInfo sim = new MarketFeed.SimInfo(1, 0, "", null, marks);
        JsonObject item = items(MarketFeed.json("x", T, 30, List.of(ironWith(sim, null, null)))).get(0);
        JsonArray events = item.getAsJsonArray("events");
        assertEquals(MarketFeed.EVENTS_MAX, events.size());
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < events.size(); i++) {
            JsonObject e = events.get(i).getAsJsonObject();
            assertEquals(List.of("t", "kind", "pct"), new ArrayList<>(e.keySet()));
            long t = e.get("t").getAsLong();
            assertTrue(t > previous, "oldest first");
            previous = t;
            assertTrue(MarketFeed.EVENT_KINDS.contains(e.get("kind").getAsString()));
            assertTrue(e.get("pct").getAsDouble() < 20, "only the newest 20 (i = 19 .. 0) survive");
        }
        assertEquals(T, previous, "the newest is last");
        assertEquals(Set.of("hot", "deal", "up", "down", "real"), MarketFeed.EVENT_KINDS);
    }

    @Test
    void extrasFollowItemsAndSeasonIsLeftOutWhenNone() {
        NewsFeed.NewsRow wheat = new NewsFeed.NewsRow(57, T - 1_000_000, "up", "wheat", "Wheat", 1, 22, 3.46, 4.22,
                "&fThe villagers need Wheat!", "&aWheat is going UP!", "sim", true);
        MarketFeed.Extras extras = new MarketFeed.Extras(List.of("iron_ingot"), List.of("oak_log"),
                new NewsFeed.SeasonRow("harvest_time", "Harvest Time", 1_793_509_200_000L), List.of(wheat));
        String json = MarketFeed.json("Crate Market", T, 30, List.of(), extras);
        assertEquals("{\"title\":\"Crate Market\",\"generatedAt\":1790000000000,\"refreshSeconds\":30,\"items\":[],"
                + "\"live\":true,\"hot\":[\"iron_ingot\"],\"deals\":[\"oak_log\"],"
                + "\"season\":{\"id\":\"harvest_time\",\"name\":\"Harvest Time\",\"endsAt\":1793509200000},"
                + "\"news\":[{\"id\":57,\"t\":1789999000000,\"kind\":\"up\",\"item\":\"wheat\",\"name\":\"Wheat\","
                + "\"dir\":1,\"pct\":22.00,\"before\":3.46,\"after\":4.22,"
                + "\"headline\":\"The villagers need Wheat!\",\"line\":\"Wheat is going UP!\","
                + "\"source\":\"sim\",\"active\":true}]}", json);

        String noSeason = MarketFeed.json("Crate Market", T, 30, List.of(),
                new MarketFeed.Extras(null, null, null, null));
        assertEquals("{\"title\":\"Crate Market\",\"generatedAt\":1790000000000,\"refreshSeconds\":30,\"items\":[],"
                + "\"live\":true,\"hot\":[],\"deals\":[],\"news\":[]}", noSeason);

        List<NewsFeed.NewsRow> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new NewsFeed.NewsRow(i, T - i * 20 * MarketFeed.HOUR_MS, "down", "x", "X", -1, -20, null, null,
                    "h", "l", "sim", false));
        }
        List<MarketFeed.Row> rows = List.of(ironWith(ironSim(List.of()), null, null),
                iron(List.of(snap(T, 2.4, 4200)), null, null));
        JsonObject root = strict(MarketFeed.json("x", T, 30, rows,
                new MarketFeed.Extras(List.of("a", "b"), List.of(), null, many))).getAsJsonObject();
        assertEquals(List.of("title", "generatedAt", "refreshSeconds", "items", "live", "hot", "deals", "news"),
                new ArrayList<>(root.keySet()));
        JsonArray news = root.getAsJsonArray("news");
        assertEquals(NewsFeed.MARKET_NEWS_LIMIT, news.size(), "the 5 newest");
        assertEquals(0, news.get(0).getAsJsonObject().get("id").getAsLong(), "newest first");
        assertEquals(4, news.get(4).getAsJsonObject().get("id").getAsLong());
        assertFalse(items(MarketFeed.json("x", T, 30, rows, null)).get(1).has("usual"),
                "an item the sim does not move carries no sim fields");
    }

    @Test
    void simAndExtrasCopyTheirListsAndDropNulls() {
        List<MarketFeed.EventMark> marks = new ArrayList<>();
        marks.add(new MarketFeed.EventMark(T, "up", 1));
        marks.add(null);
        MarketFeed.SimInfo sim = new MarketFeed.SimInfo(1, 1, "up", T, marks);
        marks.add(new MarketFeed.EventMark(T, "down", -1));
        assertEquals(1, sim.events().size(), "copied, nulls dropped");
        assertEquals(List.of(), new MarketFeed.SimInfo(1, 1, "", null, null).events());

        List<String> hot = new ArrayList<>(List.of("a"));
        hot.add(null);
        MarketFeed.Extras extras = new MarketFeed.Extras(hot, null, null, null);
        hot.add("b");
        assertEquals(List.of("a"), extras.hot());
        assertEquals(List.of(), extras.deals());
        assertEquals(List.of(), extras.news());
    }

    // ---- helpers ------------------------------------------------------------------------

    /** The feed parsed strictly (a lenient parse would forgive a malformed number). */
    static JsonElement strict(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = new Gson().getAdapter(JsonElement.class).read(reader);
            assertEquals(JsonToken.END_DOCUMENT, reader.peek(), "trailing content");
            return root;
        } catch (IOException e) {
            throw new AssertionError("not strict JSON: " + json, e);
        }
    }

    private static List<JsonObject> items(String json) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : strict(json).getAsJsonObject().getAsJsonArray("items")) {
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    /**
     * The market feed exactly as {@code MarketDashboardServer.refreshSnapshot} wrote it before
     * the long ranges existed — its loop body and its three helpers, copied verbatim with the
     * live-market reads replaced by the row's fields. The new builder must match it wherever
     * the long ranges are empty.
     */
    private static String legacy(String title, long now, int refreshSeconds, List<MarketFeed.Row> rows) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        sb.append("\"title\":").append(jsonString(title)).append(',');
        sb.append("\"generatedAt\":").append(now).append(',');
        sb.append("\"refreshSeconds\":").append(refreshSeconds).append(',');
        sb.append("\"items\":[");

        boolean first = true;
        for (MarketFeed.Row item : rows) {
            List<Snapshot> chrono = item.history();
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{');
            sb.append("\"id\":").append(jsonString(item.id())).append(',');
            sb.append("\"name\":").append(jsonString(stripLegacy(item.name()))).append(',');
            sb.append("\"material\":").append(jsonString(item.material())).append(',');
            sb.append("\"price\":").append(num(item.price())).append(',');
            sb.append("\"buy\":").append(num(item.buy())).append(',');
            sb.append("\"sell\":").append(num(item.sell())).append(',');
            sb.append("\"stock\":").append(item.stock()).append(',');
            sb.append("\"maxStock\":").append(item.maxStock()).append(',');
            sb.append("\"change24h\":").append(num(item.change24h())).append(',');
            sb.append("\"history\":[");
            for (int i = 0; i < chrono.size(); i++) {
                Snapshot s = chrono.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"t\":").append(s.recordedAt())
                        .append(",\"p\":").append(num(s.price()))
                        .append(",\"s\":").append(s.stock()).append('}');
            }
            sb.append(']');
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String stripLegacy(String s) {
        return s == null ? "" : s.replaceAll("(?i)[&§][0-9a-fk-or]", "").trim();
    }

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
