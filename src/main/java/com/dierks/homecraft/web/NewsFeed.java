package com.dierks.homecraft.web;

import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.SeasonCalendar;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code /api/news} JSON (spec §14.2), and the news pieces {@code /api/market} shares with it
 * (its top-level {@code news} and {@code season}, its per-item event markers). Built on the main
 * thread from the live market's snapshot and cached; the HTTP handler only serves the string.
 *
 * <pre>{"generatedAt":ms,"live":true[,"season":{"id":…,"name":…,"endsAt":ms}],
 * "active":[{"item":…,"name":…,"kind":…,"pct":…,"startedAt":ms,"endsAt":ms},…],
 * "news":[{"id":N,"t":ms,"kind":…,"item":…|null,"name":…,"dir":1|-1|0,"pct":…[,"before":…,"after":…],
 * "headline":…,"line":…,"source":…,"active":B},…]}</pre>
 * <ul>
 *   <li>{@code live:false} (the sim is off or paused) is always exactly
 *       {@code {"generatedAt":ms,"live":false,"active":[],"news":[]}}, whatever else was passed;</li>
 *   <li>{@code season} is left out when no season is running;</li>
 *   <li>{@code news}: at most {@value #NEWS_LIMIT}, from the last 7 days, newest first
 *       ({@link #select});</li>
 *   <li>{@code kind} is {@code up|down|hot|deal|wanted|season|real}, {@code source}
 *       {@code sim|admin|calendar|real};</li>
 *   <li>{@code item} is JSON {@code null} for a season; {@code before}/{@code after} only when
 *       the row has both prices;</li>
 *   <li>names, headlines and lines lose their colour codes ({@link Json#plain}); money and
 *       percents are two decimals ({@link Json#num2}).</li>
 * </ul>
 *
 * <p><b>Nothing announced early.</b> A HOT or DEAL ramps silently and is only news once it is at
 * full strength (spec §5, §10.1 #5). {@link #visible} is the one rule, and every factory here —
 * {@link #newsRow}, {@link #activeRow}, {@link #mark} — returns {@code null} for an event that
 * is not visible yet (or never will be: one stopped during its ramp), so the website cannot be
 * used to front-run a ramp. The builders skip {@code null} rows.
 *
 * <p>No Bukkit — unit-tested without a server.
 */
public final class NewsFeed {

    /** One day in milliseconds. */
    public static final long DAY_MS = 86_400_000L;
    /** {@code /api/news} and {@code /api/market}'s {@code news} reach back this far. */
    public static final long NEWS_WINDOW_MS = 7 * DAY_MS;
    /** {@code /api/news}: at most this many news rows. */
    public static final int NEWS_LIMIT = 50;
    /** {@code /api/market}'s top-level {@code news}: the newest this many. */
    public static final int MARKET_NEWS_LIMIT = 5;

    /**
     * The running season.
     *
     * @param endsAt local midnight after its last day ({@code SeasonCalendar.Active.endsAt})
     */
    public record SeasonRow(String id, String name, long endsAt) {
    }

    /**
     * One live HOT/DEAL/UP/DOWN.
     *
     * @param item  item id
     * @param name  display label (colour codes stripped when written)
     * @param kind  {@code hot}, {@code deal}, {@code up} or {@code down}
     * @param pct   signed percent (two decimals when written)
     * @param startedAt when it started
     * @param endsAt    when it closes (its limits end then)
     */
    public record ActiveRow(String item, String name, String kind, double pct, long startedAt, long endsAt) {
    }

    /**
     * One news event.
     *
     * @param id       the {@code market_events} id
     * @param t        when it became news ({@link #newsTime})
     * @param kind     {@code up|down|hot|deal|wanted|season|real}
     * @param item     item id, or {@code null} (a season) — written as JSON {@code null}
     * @param name     the item's label, or the season's name
     * @param dir      1, -1 or 0
     * @param pct      signed percent
     * @param before   the price quoted before, or {@code null}
     * @param after    the price quoted after, or {@code null}; both or neither are written
     * @param headline rendered headline ({@code &} codes are stripped when written)
     * @param line     rendered line (likewise)
     * @param source   {@code sim|admin|calendar|real}
     * @param active   still running at build time
     */
    public record NewsRow(long id, long t, String kind, String item, String name, int dir, double pct,
                          Double before, Double after, String headline, String line, String source,
                          boolean active) {
    }

    private NewsFeed() {
    }

    // ---- the feed -----------------------------------------------------------------------

    /**
     * {@code /api/news} as compact JSON. With {@code live == false} the other arguments are
     * ignored. {@code null} lists (and {@code null} rows) write nothing; the news rows go
     * through {@link #select} with {@value #NEWS_LIMIT}.
     */
    public static String json(long generatedAt, boolean live, SeasonRow season, List<ActiveRow> active,
                              List<NewsRow> news) {
        if (!live) {
            return "{\"generatedAt\":" + generatedAt + ",\"live\":false,\"active\":[],\"news\":[]}";
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        sb.append("\"generatedAt\":").append(generatedAt).append(',');
        sb.append("\"live\":true,");
        if (season != null) {
            sb.append("\"season\":");
            season(sb, season);
            sb.append(',');
        }
        sb.append("\"active\":[");
        boolean first = true;
        if (active != null) {
            for (ActiveRow row : active) {
                if (row == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                active(sb, row);
            }
        }
        sb.append("],\"news\":");
        newsArray(sb, select(news, generatedAt, NEWS_LIMIT));
        sb.append('}');
        return sb.toString();
    }

    /**
     * The rows to publish: non-{@code null}, from {@code now - 7 days} up to {@code now}, newest
     * first ({@code t} descending, then {@code id} descending), at most {@code limit}.
     */
    public static List<NewsRow> select(List<NewsRow> rows, long now, int limit) {
        if (rows == null || limit <= 0) {
            return List.of();
        }
        long from = now - NEWS_WINDOW_MS;
        List<NewsRow> out = new ArrayList<>();
        for (NewsRow r : rows) {
            if (r != null && r.t() >= from && r.t() <= now) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(NewsRow::t).thenComparingLong(NewsRow::id).reversed());
        return out.size() <= limit ? List.copyOf(out) : List.copyOf(out.subList(0, limit));
    }

    // ---- from the live market's events --------------------------------------------------

    /**
     * Whether players may know about {@code e} at {@code now}. A HOT/DEAL only from the end of
     * its ramp (when it is announced), and never if it was stopped during the ramp; every other
     * kind from its start.
     */
    public static boolean visible(MarketEvent e, long now) {
        if (e == null || now < e.startedAt()) {
            return false;
        }
        if (e.kind().story()) {
            long full = e.startedAt() + e.rampMs();
            if (e.stoppedAt() != null && e.stoppedAt() < full) {
                return false;
            }
            return now >= full;
        }
        return true;
    }

    /** When {@code e} became news: a HOT/DEAL at the end of its ramp, anything else at its start. */
    public static long newsTime(MarketEvent e) {
        return e.kind().story() ? e.startedAt() + e.rampMs() : e.startedAt();
    }

    /**
     * {@code e} as a news row, or {@code null} if it is not {@link #visible} yet (or is a REAL
     * move too small to have a headline — it still moves the chart, see {@link #mark}).
     *
     * @param name the item's label, or the season's name for a SEASON
     */
    public static NewsRow newsRow(MarketEvent e, String name, long now) {
        if (!visible(e, now)) {
            return null;
        }
        if (e.kind() == EventKind.REAL && (e.headline() == null || Json.plain(e.headline()).isEmpty())) {
            return null;
        }
        double pct = pct(e);
        int dir = e.kind().sign() != 0 ? e.kind().sign() : (int) Math.signum(pct);
        boolean prices = e.priceBefore() > 0 && e.priceAfter() > 0
                && Double.isFinite(e.priceBefore()) && Double.isFinite(e.priceAfter());
        return new NewsRow(e.id(), newsTime(e), e.kind().id(), e.itemId(), name, dir, pct,
                prices ? e.priceBefore() : null, prices ? e.priceAfter() : null,
                e.headline(), e.line(), e.source().id(), e.active(now));
    }

    /**
     * {@code e} as an {@code active} row, or {@code null} unless it is a {@link #visible} HOT,
     * DEAL, UP or DOWN still running at {@code now}.
     *
     * @param pct the percent to publish — the item's percent against usual now
     *            ({@code ItemStatus.pct}); pass {@code e.contribution(now) * 100} for the event
     *            alone
     */
    public static ActiveRow activeRow(MarketEvent e, String name, double pct, long now) {
        if (e == null || !e.kind().mood() || !visible(e, now) || !e.active(now)) {
            return null;
        }
        return new ActiveRow(e.itemId(), name, e.kind().id(), pct, e.startedAt(), e.endsAt());
    }

    /**
     * {@code e} as a chart marker for {@code /api/market}'s per-item {@code events}, or
     * {@code null} unless it is a {@link #visible} HOT, DEAL, UP, DOWN or REAL. The marker sits
     * where the move began ({@code started_at}) and carries the event's signed percent.
     */
    public static MarketFeed.EventMark mark(MarketEvent e, long now) {
        if (e == null || !(e.kind().mood() || e.kind() == EventKind.REAL) || !visible(e, now)) {
            return null;
        }
        return new MarketFeed.EventMark(e.startedAt(), e.kind().id(), pct(e));
    }

    /** The running season; {@code null} in gives {@code null} out. */
    public static SeasonRow seasonRow(SeasonCalendar.Active active, ZoneId zone) {
        if (active == null || active.season() == null) {
            return null;
        }
        return new SeasonRow(active.season().id(), active.season().name(), active.endsAt(zone));
    }

    /**
     * The signed percent an event reports: its stored {@code pct} when set, else its strength as
     * a percent; the sign is the kind's (HOT/UP +, DEAL/DOWN -), a REAL's strength sign, or the
     * stored sign for the other kinds. The same rule as {@code MarketLabels.pct}.
     */
    static double pct(MarketEvent e) {
        double p = e.pct();
        double raw = Double.isFinite(p) && p != 0.0 ? p : e.strength() * 100.0;
        if (!Double.isFinite(raw)) {
            return 0.0;
        }
        int sign = e.kind().sign();
        if (sign == 0 && e.kind() == EventKind.REAL && e.strength() != 0.0) {
            sign = e.strength() > 0 ? 1 : -1;
        }
        return sign == 0 ? raw : sign * Math.abs(raw);
    }

    // ---- writers (shared with MarketFeed) -----------------------------------------------

    static void season(StringBuilder sb, SeasonRow s) {
        sb.append("{\"id\":").append(Json.string(s.id()))
                .append(",\"name\":").append(Json.string(Json.plain(s.name())))
                .append(",\"endsAt\":").append(s.endsAt()).append('}');
    }

    /** {@code [row,…]} in the order given ({@code null} rows skipped). */
    static void newsArray(StringBuilder sb, List<NewsRow> rows) {
        sb.append('[');
        boolean first = true;
        for (NewsRow r : rows) {
            if (r == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            news(sb, r);
        }
        sb.append(']');
    }

    private static void news(StringBuilder sb, NewsRow r) {
        sb.append("{\"id\":").append(r.id());
        sb.append(",\"t\":").append(r.t());
        sb.append(",\"kind\":").append(Json.string(r.kind()));
        sb.append(",\"item\":").append(r.item() == null ? "null" : Json.string(r.item()));
        sb.append(",\"name\":").append(Json.string(Json.plain(r.name())));
        sb.append(",\"dir\":").append(Integer.signum(r.dir()));
        sb.append(",\"pct\":").append(Json.num2(r.pct()));
        if (r.before() != null && r.after() != null) {
            sb.append(",\"before\":").append(Json.num2(r.before()));
            sb.append(",\"after\":").append(Json.num2(r.after()));
        }
        sb.append(",\"headline\":").append(Json.string(Json.plain(r.headline())));
        sb.append(",\"line\":").append(Json.string(Json.plain(r.line())));
        sb.append(",\"source\":").append(Json.string(r.source()));
        sb.append(",\"active\":").append(r.active());
        sb.append('}');
    }

    private static void active(StringBuilder sb, ActiveRow r) {
        sb.append("{\"item\":").append(Json.string(r.item()));
        sb.append(",\"name\":").append(Json.string(Json.plain(r.name())));
        sb.append(",\"kind\":").append(Json.string(r.kind()));
        sb.append(",\"pct\":").append(Json.num2(r.pct()));
        sb.append(",\"startedAt\":").append(r.startedAt());
        sb.append(",\"endsAt\":").append(r.endsAt());
        sb.append('}');
    }
}
