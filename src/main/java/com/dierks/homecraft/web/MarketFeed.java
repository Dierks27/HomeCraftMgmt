package com.dierks.homecraft.web;

import com.dierks.homecraft.storage.PriceHistoryDao;

import java.util.List;

/**
 * The {@code /api/market} JSON, built from plain rows so the exact shape is unit-tested
 * without a server. {@code MarketDashboardServer} gathers the rows on the main thread and
 * caches the string this returns; the HTTP handler only serves it.
 *
 * <p><b>The contract with the website.</b> Every field the feed has always carried keeps its
 * name, its order and its formatting byte for byte — {@code price}/{@code buy}/{@code sell}/
 * {@code change24h} and each history {@code p} to two decimals, {@code change24h} a percent,
 * {@code history} the last 96 half-hour snapshots oldest first — because the site already
 * parses them. The long ranges are appended at the end of each item:
 * <ul>
 *   <li>{@code history7d} — one point per epoch-aligned hour over the last 7 days (up to
 *       {@value #H7_POINTS}), oldest first;</li>
 *   <li>{@code history30d} — one point per epoch-aligned 6 hours over the last 30 days (up
 *       to {@value #H30_POINTS}), oldest first.</li>
 * </ul>
 * Their points are {@code {"t":ms,"p":price,"s":stock}} like {@code history}'s, with
 * {@code p} to four decimals (trailing zeros dropped) to keep a few hundred points per item
 * small. An empty long array is left out entirely — the site keeps its 7D / 30D ranges off
 * until the key appears — whereas {@code history} is always present, even as {@code []}.
 */
public final class MarketFeed {

    /** One hour in milliseconds. */
    public static final long HOUR_MS = 3_600_000L;
    /** {@code history7d}: one point per hour… */
    public static final long H7_BUCKET_MS = HOUR_MS;
    /** …for 168 hours (7 days). */
    public static final int H7_POINTS = 168;
    /** {@code history30d}: one point per 6 hours… */
    public static final long H30_BUCKET_MS = 6 * HOUR_MS;
    /** …for 120 six-hour buckets (30 days). */
    public static final int H30_POINTS = 120;

    /**
     * One market item as the feed shows it. Every history list is oldest first; a
     * {@code null} list is treated as empty.
     *
     * @param id         item key ({@code iron_ingot})
     * @param name       display label; legacy colour codes are stripped when written
     * @param material   Bukkit material name ({@code IRON_INGOT})
     * @param price      market price now
     * @param buy        what a player pays
     * @param sell       what a player is paid
     * @param stock      on the shelf now ({@code 0} = sold out)
     * @param maxStock   a full shelf
     * @param change24h  percent change over the last 24 hours ({@code 0.9} = +0.9 %)
     * @param history    the recent half-hour snapshots (up to 96)
     * @param history7d  hourly points over the last 7 days (omitted from the JSON when empty)
     * @param history30d 6-hourly points over the last 30 days (omitted from the JSON when empty)
     */
    public record Row(String id, String name, String material, double price, double buy, double sell,
                      long stock, long maxStock, double change24h,
                      List<PriceHistoryDao.Snapshot> history,
                      List<PriceHistoryDao.Snapshot> history7d,
                      List<PriceHistoryDao.Snapshot> history30d) {

        public Row {
            history = history == null ? List.of() : List.copyOf(history);
            history7d = history7d == null ? List.of() : List.copyOf(history7d);
            history30d = history30d == null ? List.of() : List.copyOf(history30d);
        }
    }

    private MarketFeed() {
    }

    /**
     * Start of a long-history window: the first millisecond of the oldest of the last
     * {@code buckets} epoch-aligned buckets of {@code bucketMs}, the newest being the one that
     * holds {@code now}. So the window {@code [start, now]} spans exactly {@code buckets}
     * buckets and at most {@code buckets × bucketMs} milliseconds, and one snapshot per
     * bucket from it is at most {@code buckets} points.
     *
     * <p>Epoch alignment ({@code floorDiv}, then back to milliseconds) is what makes the
     * buckets stable from one refresh to the next, and it matches the database's
     * {@code recorded_at / bucketMs} grouping for every timestamp after 1970.
     *
     * @throws IllegalArgumentException if {@code bucketMs} or {@code buckets} is not positive
     */
    public static long windowStart(long now, long bucketMs, int buckets) {
        if (bucketMs <= 0 || buckets <= 0) {
            throw new IllegalArgumentException("bucketMs and buckets must be positive: "
                    + bucketMs + ", " + buckets);
        }
        return (Math.floorDiv(now, bucketMs) - (buckets - 1)) * bucketMs;
    }

    /**
     * The whole feed as compact JSON (no whitespace), items in the order given:
     * <pre>{"title":…,"generatedAt":…,"refreshSeconds":…,"items":[{"id":…,"name":…,"material":…,
     * "price":…,"buy":…,"sell":…,"stock":…,"maxStock":…,"change24h":…,"history":[…]
     * [,"history7d":[…]][,"history30d":[…]]},…]}</pre>
     * A {@code null} title writes {@code ""}; {@code null} rows (or a {@code null} row) write
     * nothing.
     */
    public static String json(String title, long generatedAt, int refreshSeconds, List<Row> rows) {
        int count = rows == null ? 0 : rows.size();
        StringBuilder sb = new StringBuilder(Math.max(4096, 256 + count * 1024));
        sb.append('{');
        sb.append("\"title\":").append(Json.string(title)).append(',');
        sb.append("\"generatedAt\":").append(generatedAt).append(',');
        sb.append("\"refreshSeconds\":").append(refreshSeconds).append(',');
        sb.append("\"items\":[");
        boolean first = true;
        if (rows != null) {
            for (Row row : rows) {
                if (row == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                item(sb, row);
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void item(StringBuilder sb, Row row) {
        sb.append('{');
        sb.append("\"id\":").append(Json.string(row.id())).append(',');
        sb.append("\"name\":").append(Json.string(Json.plain(row.name()))).append(',');
        sb.append("\"material\":").append(Json.string(row.material())).append(',');
        sb.append("\"price\":").append(Json.num2(row.price())).append(',');
        sb.append("\"buy\":").append(Json.num2(row.buy())).append(',');
        sb.append("\"sell\":").append(Json.num2(row.sell())).append(',');
        sb.append("\"stock\":").append(row.stock()).append(',');
        sb.append("\"maxStock\":").append(row.maxStock()).append(',');
        sb.append("\"change24h\":").append(Json.num2(row.change24h())).append(',');
        sb.append("\"history\":");
        points(sb, row.history(), false);
        if (!row.history7d().isEmpty()) {
            sb.append(",\"history7d\":");
            points(sb, row.history7d(), true);
        }
        if (!row.history30d().isEmpty()) {
            sb.append(",\"history30d\":");
            points(sb, row.history30d(), true);
        }
        sb.append('}');
    }

    /** {@code [{"t":ms,"p":price,"s":stock},…]} — price to 2 decimals, or to 4 for the long ranges. */
    private static void points(StringBuilder sb, List<PriceHistoryDao.Snapshot> points, boolean fourDecimals) {
        sb.append('[');
        for (int i = 0; i < points.size(); i++) {
            PriceHistoryDao.Snapshot s = points.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"t\":").append(s.recordedAt())
                    .append(",\"p\":").append(fourDecimals ? Json.num4(s.price()) : Json.num2(s.price()))
                    .append(",\"s\":").append(s.stock()).append('}');
        }
        sb.append(']');
    }
}
