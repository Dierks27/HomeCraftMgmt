package com.dierks.homecraft.web;

import com.dierks.homecraft.storage.PriceHistoryDao.Snapshot;
import com.google.gson.Gson;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
