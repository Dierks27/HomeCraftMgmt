package com.dierks.homecraft.market.sim;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The planner's schedule and counters: when the next HOT, DEAL and news flash may start, how
 * many flashes the current local day has had, and which headline templates were used last.
 * Mutable; {@link EventPlanner#plan} and {@link MarketSimulator} update it in place, and the
 * service persists it in {@code market_sim_meta} in the tick's transaction:
 *
 * <ul>
 *   <li>{@code next_hot_at}, {@code next_deal_at}, {@code next_news_at} (epoch ms)</li>
 *   <li>{@code news_day} (local epoch day, {@code GameClock.dayKey()}), {@code news_today}</li>
 *   <li>{@code recent_up}, {@code recent_down}, {@code recent_hot}, {@code recent_deal},
 *       {@code recent_wanted} ({@link Headlines#formatRecent} / {@link Headlines#parseRecent})</li>
 * </ul>
 *
 * <p>Status screens may show the next <em>times</em>, never an item or a direction: those are
 * rolled only when the event fires.
 */
public final class Schedule {

    /** {@link #newsDay()} before any flash has been counted. */
    public static final long NO_DAY = Long.MIN_VALUE;

    private long nextHotAt;
    private long nextDealAt;
    private long nextNewsAt;
    private long newsDay = NO_DAY;
    private int newsToday;
    private final Map<String, List<Integer>> recent = new TreeMap<>();

    /** An empty schedule: everything due at once. Use {@link #firstEnable} for a real start. */
    public Schedule() {
    }

    /** A schedule read back from meta. {@code recent} may be {@code null}. */
    public Schedule(long nextHotAt, long nextDealAt, long nextNewsAt, long newsDay, int newsToday,
                    Map<String, List<Integer>> recent) {
        this.nextHotAt = nextHotAt;
        this.nextDealAt = nextDealAt;
        this.nextNewsAt = nextNewsAt;
        this.newsDay = newsDay;
        this.newsToday = Math.max(0, newsToday);
        if (recent != null) {
            recent.forEach(this::setRecent);
        }
    }

    /**
     * The schedule on the first ever enable, and on every resume or re-enable (§5.4): the first
     * HOT 10-40 min out, the first DEAL 60-180 min out, the first flash 20-60 min out. The
     * news-day counter and the headline memory are kept.
     */
    public void resetForEnable(long now, SimRandom rng) {
        long c = Math.floorDiv(now, SimMath.MINUTE_MS);
        nextHotAt = now + minutes(rng.between(10, 40, "enable.hot", c));
        nextDealAt = now + minutes(rng.between(60, 180, "enable.deal", c));
        nextNewsAt = now + minutes(rng.between(20, 60, "enable.news", c));
    }

    /** A new schedule as on the first ever enable ({@link #resetForEnable}). */
    public static Schedule firstEnable(long now, SimRandom rng) {
        Schedule s = new Schedule();
        s.resetForEnable(now, rng);
        return s;
    }

    private static long minutes(double m) {
        return Math.round(m * SimMath.MINUTE_MS);
    }

    // ---- news counter ---------------------------------------------------------------------

    /** How many flashes local day {@code day} has had (0 when the counter is for another day). */
    public int newsOn(long day) {
        return newsDay == day ? newsToday : 0;
    }

    /** Count one flash on local day {@code day}, starting a fresh count on a new day. */
    public void countNews(long day) {
        if (newsDay != day) {
            newsDay = day;
            newsToday = 0;
        }
        newsToday++;
    }

    // ---- headline memory ------------------------------------------------------------------

    /** The last template indices used for {@code list}, oldest first; empty when none. */
    public List<Integer> recent(String list) {
        List<Integer> r = recent.get(list);
        return r == null ? List.of() : r;
    }

    public void setRecent(String list, List<Integer> indices) {
        if (list == null) {
            return;
        }
        if (indices == null || indices.isEmpty()) {
            recent.remove(list);
        } else {
            recent.put(list, List.copyOf(indices));
        }
    }

    /** Every remembered list, by list name (sorted). */
    public Map<String, List<Integer>> recentLists() {
        return Collections.unmodifiableMap(new TreeMap<>(recent));
    }

    // ---- plain accessors ------------------------------------------------------------------

    public long nextHotAt() {
        return nextHotAt;
    }

    public void setNextHotAt(long at) {
        this.nextHotAt = at;
    }

    public long nextDealAt() {
        return nextDealAt;
    }

    public void setNextDealAt(long at) {
        this.nextDealAt = at;
    }

    /** {@link #nextHotAt} for HOT, {@link #nextDealAt} for DEAL. */
    public long nextAt(EventKind kind) {
        return kind == EventKind.HOT ? nextHotAt : nextDealAt;
    }

    /** Set {@link #nextHotAt} for HOT, {@link #nextDealAt} for DEAL; ignored for other kinds. */
    public void setNextAt(EventKind kind, long at) {
        if (kind == EventKind.HOT) {
            nextHotAt = at;
        } else if (kind == EventKind.DEAL) {
            nextDealAt = at;
        }
    }

    public long nextNewsAt() {
        return nextNewsAt;
    }

    public void setNextNewsAt(long at) {
        this.nextNewsAt = at;
    }

    public long newsDay() {
        return newsDay;
    }

    public int newsToday() {
        return newsToday;
    }

    /** A deep copy (the headline memory lists are immutable already). */
    public Schedule copy() {
        return new Schedule(nextHotAt, nextDealAt, nextNewsAt, newsDay, newsToday, recent);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Schedule s)) {
            return false;
        }
        return nextHotAt == s.nextHotAt && nextDealAt == s.nextDealAt && nextNewsAt == s.nextNewsAt
                && newsDay == s.newsDay && newsToday == s.newsToday && recent.equals(s.recent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nextHotAt, nextDealAt, nextNewsAt, newsDay, newsToday, recent);
    }

    @Override
    public String toString() {
        return "Schedule[hot=" + nextHotAt + ", deal=" + nextDealAt + ", news=" + nextNewsAt
                + ", newsDay=" + newsDay + ", newsToday=" + newsToday + ", recent=" + recent + "]";
    }
}
