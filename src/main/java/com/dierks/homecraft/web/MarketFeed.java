package com.dierks.homecraft.web;

import com.dierks.homecraft.storage.PriceHistoryDao;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
 *
 * <p><b>The live market (0.33).</b> Nothing new is written unless the sim is active: a row's
 * {@link SimInfo} and the feed's {@link Extras} are {@code null} otherwise, and the output is
 * then byte for byte the 0.32 feed. While it is active:
 * <ul>
 *   <li>per item (only sim-enabled items), after the last history array:
 *       {@code "usual"} and {@code "moodPct"} (two decimals) always; {@code "status"} and
 *       {@code "statusEndsAt"} only while a badge shows; {@code "events"} only when there are
 *       some — at most {@value #EVENTS_MAX} {@code {"t":ms,"kind":…,"pct":…}} markers from the
 *       last 30 days, oldest first, kinds {@code hot|deal|up|down|real};</li>
 *   <li>at the top level, after {@code "items"}: {@code "live":true}, {@code "hot"} and
 *       {@code "deals"} (item ids), {@code "season"} (left out when none) and {@code "news"} — the
 *       {@value NewsFeed#MARKET_NEWS_LIMIT} newest from the last 7 days, the same shape as
 *       {@code /api/news} ({@link NewsFeed}).</li>
 * </ul>
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
    /** Per item: at most this many event markers… */
    public static final int EVENTS_MAX = 20;
    /** …from this far back. */
    public static final long EVENTS_WINDOW_MS = 30 * 24 * HOUR_MS;
    /** The marker kinds the chart knows; any other kind is not written. */
    public static final Set<String> EVENT_KINDS = Set.of("hot", "deal", "up", "down", "real");

    /**
     * One chart marker: an event on this item.
     *
     * @param t    where the move began (epoch ms)
     * @param kind {@code hot|deal|up|down|real}
     * @param pct  its signed percent (two decimals when written)
     */
    public record EventMark(long t, String kind, double pct) {
    }

    /**
     * The live market's per-item fields; {@code null} on a {@link Row} for an item the sim does
     * not move, and for every item while the sim is off.
     *
     * @param usual        the balanced price (what "Usually $X" says)
     * @param moodPct      percent against usual now ({@code 11.7} = +11.7%)
     * @param status       the badge ({@code hot|deal|up|down|wanted}); blank or {@code null} for
     *                     none — then neither it nor {@code statusEndsAt} is written
     * @param statusEndsAt when the badge goes, or {@code null} (not written)
     * @param events       the chart markers, any order; {@code null} is empty
     */
    public record SimInfo(double usual, double moodPct, String status, Long statusEndsAt, List<EventMark> events) {

        public SimInfo {
            status = status == null ? "" : status;
            events = nonNull(events);
        }
    }

    /**
     * The live market's top-level fields; {@code null} while the sim is off (nothing is written).
     * Present means live, so it always writes {@code "live":true}.
     *
     * @param hot    ids of items with a HOT badge
     * @param deals  ids of items with a DEAL badge
     * @param season the running season, or {@code null} (left out)
     * @param news   news rows, any order ({@link NewsFeed#select} keeps the newest 5 of 7 days)
     */
    public record Extras(List<String> hot, List<String> deals, NewsFeed.SeasonRow season,
                         List<NewsFeed.NewsRow> news) {

        public Extras {
            hot = nonNull(hot);
            deals = nonNull(deals);
            news = nonNull(news);
        }
    }

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
     * @param sim        the live market's fields, or {@code null} (none written)
     */
    public record Row(String id, String name, String material, double price, double buy, double sell,
                      long stock, long maxStock, double change24h,
                      List<PriceHistoryDao.Snapshot> history,
                      List<PriceHistoryDao.Snapshot> history7d,
                      List<PriceHistoryDao.Snapshot> history30d,
                      SimInfo sim) {

        public Row {
            history = history == null ? List.of() : List.copyOf(history);
            history7d = history7d == null ? List.of() : List.copyOf(history7d);
            history30d = history30d == null ? List.of() : List.copyOf(history30d);
        }

        /** A row without live-market fields — the 0.32 shape. */
        public Row(String id, String name, String material, double price, double buy, double sell,
                   long stock, long maxStock, double change24h,
                   List<PriceHistoryDao.Snapshot> history,
                   List<PriceHistoryDao.Snapshot> history7d,
                   List<PriceHistoryDao.Snapshot> history30d) {
            this(id, name, material, price, buy, sell, stock, maxStock, change24h, history, history7d, history30d,
                    null);
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
     * nothing. The same as {@link #json(String, long, int, List, Extras)} with no extras.
     */
    public static String json(String title, long generatedAt, int refreshSeconds, List<Row> rows) {
        return json(title, generatedAt, refreshSeconds, rows, null);
    }

    /**
     * The whole feed with the live market's fields: each row's {@link SimInfo} after its history
     * arrays, and {@code extras} after {@code "items"}. With every {@code sim} and {@code extras}
     * {@code null}, exactly {@link #json(String, long, int, List)}.
     */
    public static String json(String title, long generatedAt, int refreshSeconds, List<Row> rows, Extras extras) {
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
                item(sb, row, generatedAt);
            }
        }
        sb.append(']');
        if (extras != null) {
            extras(sb, extras, generatedAt);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void item(StringBuilder sb, Row row, long generatedAt) {
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
        if (row.sim() != null) {
            sim(sb, row.sim(), generatedAt);
        }
        sb.append('}');
    }

    /** {@code ,"usual":…,"moodPct":…[,"status":…[,"statusEndsAt":…]][,"events":[…]]} */
    private static void sim(StringBuilder sb, SimInfo sim, long generatedAt) {
        sb.append(",\"usual\":").append(Json.num2(sim.usual()));
        sb.append(",\"moodPct\":").append(Json.num2(sim.moodPct()));
        if (!sim.status().isBlank()) {
            sb.append(",\"status\":").append(Json.string(sim.status()));
            if (sim.statusEndsAt() != null) {
                sb.append(",\"statusEndsAt\":").append(sim.statusEndsAt().longValue());
            }
        }
        List<EventMark> marks = marks(sim.events(), generatedAt);
        if (!marks.isEmpty()) {
            sb.append(",\"events\":[");
            for (int i = 0; i < marks.size(); i++) {
                EventMark m = marks.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"t\":").append(m.t())
                        .append(",\"kind\":").append(Json.string(m.kind()))
                        .append(",\"pct\":").append(Json.num2(m.pct())).append('}');
            }
            sb.append(']');
        }
    }

    /**
     * The markers to write: a known kind, from {@code now - 30 days} up to {@code now}, oldest
     * first (ties keep their order), the newest {@value #EVENTS_MAX}.
     */
    static List<EventMark> marks(List<EventMark> events, long now) {
        long from = now - EVENTS_WINDOW_MS;
        List<EventMark> out = new ArrayList<>();
        for (EventMark m : events) {
            if (m != null && EVENT_KINDS.contains(m.kind()) && m.t() >= from && m.t() <= now) {
                out.add(m);
            }
        }
        out.sort(Comparator.comparingLong(EventMark::t));
        return out.size() <= EVENTS_MAX ? out : out.subList(out.size() - EVENTS_MAX, out.size());
    }

    /** {@code ,"live":true,"hot":[…],"deals":[…][,"season":{…}],"news":[…]} */
    private static void extras(StringBuilder sb, Extras extras, long generatedAt) {
        sb.append(",\"live\":true");
        sb.append(",\"hot\":");
        ids(sb, extras.hot());
        sb.append(",\"deals\":");
        ids(sb, extras.deals());
        if (extras.season() != null) {
            sb.append(",\"season\":");
            NewsFeed.season(sb, extras.season());
        }
        sb.append(",\"news\":");
        NewsFeed.newsArray(sb, NewsFeed.select(extras.news(), generatedAt, NewsFeed.MARKET_NEWS_LIMIT));
    }

    private static void ids(StringBuilder sb, List<String> ids) {
        sb.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.string(ids.get(i)));
        }
        sb.append(']');
    }

    /** A copy without {@code null} elements; {@code null} is empty. */
    private static <T> List<T> nonNull(List<T> in) {
        if (in == null) {
            return List.of();
        }
        List<T> out = new ArrayList<>(in.size());
        for (T t : in) {
            if (t != null) {
                out.add(t);
            }
        }
        return List.copyOf(out);
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
