package com.dierks.homecraft.storage;

import com.dierks.homecraft.market.sim.BroadcastState;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.ItemSimState;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.MarketSimulator;
import com.dierks.homecraft.market.sim.RealQuotes;
import com.dierks.homecraft.market.sim.Schedule;
import com.dierks.homecraft.market.sim.Source;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Data access for the live market's six tables (schema v32, spec §11.1): {@code market_sim_state},
 * {@code market_events}, {@code market_sim_meta}, {@code market_sim_ledger},
 * {@code market_news_seen} and {@code market_real_quotes}.
 *
 * <p><b>The sim's own bookkeeping only.</b> Nothing here reads or writes {@code market_state}:
 * the sim never changes stock or the balanced price, and this DAO cannot either. The one query
 * that looks outside these tables, {@link #tradedSince}, only reads the daily trade tallies.
 *
 * <p><b>One transaction per tick.</b> Everything the tick writes takes the {@link Connection}
 * handed to it by {@link Database#transaction}: {@link #saveMeta(Connection, Map)},
 * {@link #saveStates}, {@link #insertEvent}, {@link #updateEvent}, {@link #addLedger},
 * {@link #upsertQuotes} and {@link #markApplied}. They run no transaction of their own, so a
 * failed tick rolls every one of them back together, {@code last_tick_at} included. The other
 * writes (seen, mute, prune) are small and run on their own.
 *
 * <p>{@code day} columns are local epoch days ({@code GameClock.dayKey()}), except
 * {@code market_real_quotes.trade_day}, which is the exchange's trade date as an epoch day.
 */
public final class MarketSimDao {

    /** One {@code market_sim_ledger} row: what the sim paid out or saved on one local day, for one item. */
    public record LedgerRow(long day, String itemId, double sellBonus, double buyDiscount, long unitsSold,
                            long unitsBought) {
    }

    /**
     * One {@code market_news_seen} row.
     *
     * @param lastEventId the newest event this player has been shown (catch-up shows ids above it)
     * @param muted       {@code /hcm market news off}
     * @param seenAt      when they were last brought up to date: their last catch-up or the last
     *                    live broadcast they heard (0 = never)
     */
    public record Seen(UUID player, long lastEventId, boolean muted, long seenAt) {
    }

    /**
     * One {@code market_real_quotes} row.
     *
     * @param tradeDay the trade date as an epoch day
     * @param applied  its move has become a REAL event (set in that tick's transaction); it is
     *                 never applied twice
     */
    public record StoredQuote(String symbol, long tradeDay, double close, long fetchedAt, boolean applied) {

        /** The row as the parser's {@link RealQuotes.DailyClose}, for {@link RealQuotes#latestMove}. */
        public RealQuotes.DailyClose dailyClose() {
            return new RealQuotes.DailyClose(LocalDate.ofEpochDay(tradeDay), close);
        }
    }

    /** What one {@link #prune} deleted. */
    public record Pruned(int events, int ledger, int quotes) {
    }

    /** Ledger rows are kept this many local days (§11.1). */
    public static final int LEDGER_KEEP_DAYS = 400;
    /** Cached real-world closes are kept this many days (§11.1). */
    public static final int QUOTE_KEEP_DAYS = 30;

    private static final String EVENT_COLUMNS = "kind, source, item_id, tag, strength, started_at, ramp_ms, "
            + "hold_ms, fade_ms, half_life_ms, lasts_ms, ends_at, stopped_at, stop_reason, pct, price_before, "
            + "price_after, headline, line, announce_due_at, announced_at, last_call_at, end_line_at";

    private final Database database;

    public MarketSimDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    // ---- meta ---------------------------------------------------------------------------------

    /** Every {@code market_sim_meta} row, by key (sorted, mutable). */
    public Map<String, String> loadMeta() throws SQLException {
        Map<String, String> out = new TreeMap<>();
        Connection c = conn();
        synchronized (c) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT key, value FROM market_sim_meta")) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    /**
     * Write meta rows inside the caller's transaction. Each entry replaces that key's value; a
     * {@code null} value deletes the key. Keys not in {@code entries} are left alone.
     */
    public void saveMeta(Connection c, Map<String, String> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        synchronized (c) {
            try (PreparedStatement up = c.prepareStatement(
                    "INSERT INTO market_sim_meta(key, value) VALUES(?,?) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value");
                 PreparedStatement del = c.prepareStatement("DELETE FROM market_sim_meta WHERE key = ?")) {
                for (Map.Entry<String, String> en : entries.entrySet()) {
                    if (en.getKey() == null) {
                        continue;
                    }
                    if (en.getValue() == null) {
                        del.setString(1, en.getKey());
                        del.executeUpdate();
                    } else {
                        up.setString(1, en.getKey());
                        up.setString(2, en.getValue());
                        up.executeUpdate();
                    }
                }
            }
        }
    }

    /** {@link #saveMeta(Connection, Map)} as its own transaction (pause, resume, admin counters). */
    public void saveMeta(Map<String, String> entries) throws SQLException {
        database.transaction(c -> {
            saveMeta(c, entries);
            return null;
        });
    }

    // ---- per-item state -----------------------------------------------------------------------

    /** Every {@code market_sim_state} row, by item id (sorted, mutable). Items with no row are simply absent. */
    public Map<String, ItemSimState> loadStates() throws SQLException {
        Map<String, ItemSimState> out = new TreeMap<>();
        Connection c = conn();
        synchronized (c) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT item_id, drift, last_news_at, featured_until, "
                         + "last_wanted_at, updated_at FROM market_sim_state")) {
                while (rs.next()) {
                    ItemSimState s = new ItemSimState(rs.getString(1), rs.getDouble(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6));
                    out.put(s.itemId(), s);
                }
            }
        }
        return out;
    }

    /** Write (insert or replace) one row per state inside the caller's transaction. */
    public void saveStates(Connection c, Collection<ItemSimState> states) throws SQLException {
        if (states == null || states.isEmpty()) {
            return;
        }
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_sim_state(item_id, drift, last_news_at, featured_until, last_wanted_at, "
                            + "updated_at) VALUES(?,?,?,?,?,?) "
                            + "ON CONFLICT(item_id) DO UPDATE SET drift = excluded.drift, "
                            + "last_news_at = excluded.last_news_at, featured_until = excluded.featured_until, "
                            + "last_wanted_at = excluded.last_wanted_at, updated_at = excluded.updated_at")) {
                for (ItemSimState s : states) {
                    if (s == null) {
                        continue;
                    }
                    ps.setString(1, s.itemId());
                    ps.setDouble(2, s.drift());
                    ps.setLong(3, s.lastNewsAt());
                    ps.setLong(4, s.featuredUntil());
                    ps.setLong(5, s.lastWantedAt());
                    ps.setLong(6, s.updatedAt());
                    ps.executeUpdate();
                }
            }
        }
    }

    // ---- events -------------------------------------------------------------------------------

    /**
     * The simulator's working set at {@code now}: every event with
     * {@code ends_at > now - }{@link MarketSimulator#RETAIN_MS} (live ones, plus any that closed
     * within the last hour for their ending line), oldest id first. With the SEASON rows of
     * {@link #loadSeasons} this is the set {@link MarketSimulator} keeps between ticks (it keeps a
     * SEASON row while the calendar still runs its season), so a restart continues where it
     * stopped.
     *
     * <p>Rows whose {@code kind} or {@code source} this version does not know are skipped.
     */
    public List<MarketEvent> loadLive(long now) throws SQLException {
        long from = now < Long.MIN_VALUE + MarketSimulator.RETAIN_MS ? Long.MIN_VALUE : now - MarketSimulator.RETAIN_MS;
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + EVENT_COLUMNS + " FROM market_events WHERE ends_at > ? ORDER BY id")) {
                ps.setLong(1, from);
                return events(ps);
            }
        }
    }

    /**
     * Every SEASON row started at or after {@code since}, oldest id first, whatever its
     * {@code ends_at}. A restart adds the ones whose season the calendar still runs to the
     * working set: an admin may have moved a season's end later than the row's stored
     * {@code ends_at}, and without the row the simulator would try to make (and announce) a
     * second one for the same {@code id:year}.
     */
    public List<MarketEvent> loadSeasons(long since) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + EVENT_COLUMNS + " FROM market_events WHERE kind = ? AND started_at >= ? ORDER BY id")) {
                ps.setString(1, EventKind.SEASON.name());
                ps.setLong(2, since);
                return events(ps);
            }
        }
    }

    /**
     * The stored row of {@code kind} with {@code tag} (SEASON and REAL rows are unique by it), or
     * empty. What a working-set row that lost the unique-tag race is swapped for.
     */
    public Optional<MarketEvent> findByTag(EventKind kind, String tag) throws SQLException {
        if (kind == null || tag == null) {
            return Optional.empty();
        }
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + EVENT_COLUMNS + " FROM market_events WHERE kind = ? AND tag = ? ORDER BY id LIMIT 1")) {
                ps.setString(1, kind.name());
                ps.setString(2, tag);
                List<MarketEvent> rows = events(ps);
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
            }
        }
    }

    /**
     * Up to {@code limit} events started at or after {@code since}, newest first (then highest id
     * first), every kind. The rows are as stored: the caller decides what may be shown — a
     * HOT/DEAL still in its silent ramp must not be (§5.1).
     */
    public List<MarketEvent> loadRecentNews(long since, int limit) throws SQLException {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + EVENT_COLUMNS + " FROM market_events WHERE started_at >= ? "
                            + "ORDER BY started_at DESC, id DESC LIMIT ?")) {
                ps.setLong(1, since);
                ps.setInt(2, limit);
                return events(ps);
            }
        }
    }

    /** The highest event id ever written (0 when none): where a new player's catch-up mark starts. */
    public long maxEventId() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM market_events")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * Insert {@code e} as a new row inside the caller's transaction ({@code e.id()} is ignored).
     *
     * <p>A SEASON or REAL row whose {@code (kind, tag)} is already stored is not written again:
     * the partial unique index makes those idempotent, and the tick must not fail over one.
     *
     * @return the new row's id, or 0 when a row with the same {@code (kind, tag)} already exists
     */
    public long insertEvent(Connection c, MarketEvent e) throws SQLException {
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_events(" + EVENT_COLUMNS + ") "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(kind, tag) WHERE tag IS NOT NULL DO NOTHING",
                    Statement.RETURN_GENERATED_KEYS)) {
                bindEvent(ps, e);
                if (ps.executeUpdate() == 0) {
                    return 0L;
                }
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        }
    }

    /**
     * Rewrite row {@code e.id()} with every other column of {@code e} (stops, announcements, last
     * calls, ending lines) inside the caller's transaction.
     *
     * @return whether a row with that id existed
     */
    public boolean updateEvent(Connection c, MarketEvent e) throws SQLException {
        if (e.id() <= 0) {
            return false;
        }
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE market_events SET kind=?, source=?, item_id=?, tag=?, strength=?, started_at=?, "
                            + "ramp_ms=?, hold_ms=?, fade_ms=?, half_life_ms=?, lasts_ms=?, ends_at=?, "
                            + "stopped_at=?, stop_reason=?, pct=?, price_before=?, price_after=?, headline=?, "
                            + "line=?, announce_due_at=?, announced_at=?, last_call_at=?, end_line_at=? "
                            + "WHERE id=?")) {
                bindEvent(ps, e);
                ps.setLong(24, e.id());
                return ps.executeUpdate() > 0;
            }
        }
    }

    private static void bindEvent(PreparedStatement ps, MarketEvent e) throws SQLException {
        ps.setString(1, e.kind().name());
        ps.setString(2, e.source().name());
        ps.setString(3, e.itemId());
        ps.setString(4, e.tag());
        ps.setDouble(5, e.strength());
        ps.setLong(6, e.startedAt());
        ps.setLong(7, e.rampMs());
        ps.setLong(8, e.holdMs());
        ps.setLong(9, e.fadeMs());
        ps.setLong(10, e.halfLifeMs());
        ps.setLong(11, e.lastsMs());
        ps.setLong(12, e.endsAt());
        setLong(ps, 13, e.stoppedAt());
        ps.setString(14, e.stopReason());
        ps.setDouble(15, finite(e.pct()));
        ps.setDouble(16, finite(e.priceBefore()));
        ps.setDouble(17, finite(e.priceAfter()));
        ps.setString(18, e.headline());
        ps.setString(19, e.line());
        setLong(ps, 20, e.announceDueAt());
        setLong(ps, 21, e.announcedAt());
        setLong(ps, 22, e.lastCallAt());
        setLong(ps, 23, e.endLineAt());
    }

    private static List<MarketEvent> events(PreparedStatement ps) throws SQLException {
        List<MarketEvent> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                EventKind kind = EventKind.parse(rs.getString("kind"));
                Source source = Source.parse(rs.getString("source"));
                if (kind == null || source == null) {
                    continue; // written by a newer version: leave it alone
                }
                out.add(MarketEvent.builder(kind, source)
                        .id(rs.getLong("id"))
                        .itemId(rs.getString("item_id"))
                        .tag(rs.getString("tag"))
                        .strength(rs.getDouble("strength"))
                        .startedAt(rs.getLong("started_at"))
                        .rampMs(rs.getLong("ramp_ms"))
                        .holdMs(rs.getLong("hold_ms"))
                        .fadeMs(rs.getLong("fade_ms"))
                        .halfLifeMs(rs.getLong("half_life_ms"))
                        .lastsMs(rs.getLong("lasts_ms"))
                        .endsAt(rs.getLong("ends_at"))
                        .stoppedAt(nullableLong(rs, "stopped_at"))
                        .stopReason(rs.getString("stop_reason"))
                        .pct(rs.getDouble("pct"))
                        .priceBefore(rs.getDouble("price_before"))
                        .priceAfter(rs.getDouble("price_after"))
                        .headline(rs.getString("headline"))
                        .line(rs.getString("line"))
                        .announceDueAt(nullableLong(rs, "announce_due_at"))
                        .announcedAt(nullableLong(rs, "announced_at"))
                        .lastCallAt(nullableLong(rs, "last_call_at"))
                        .endLineAt(nullableLong(rs, "end_line_at"))
                        .build());
            }
        }
        return out;
    }

    // ---- ledger -------------------------------------------------------------------------------

    /**
     * Add to one local day's ledger row for an item inside the caller's transaction, creating it
     * if needed. Every amount accumulates; the money amounts are signed.
     *
     * @param bonus    {@code total - neutral} over sells at a moved price
     * @param discount {@code neutral - total} over buys at a moved price
     */
    public void addLedger(Connection c, long day, String itemId, double bonus, double discount, long sold,
                          long bought) throws SQLException {
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_sim_ledger(day, item_id, sell_bonus, buy_discount, units_sold, units_bought) "
                            + "VALUES(?,?,?,?,?,?) "
                            + "ON CONFLICT(day, item_id) DO UPDATE SET "
                            + "sell_bonus = sell_bonus + excluded.sell_bonus, "
                            + "buy_discount = buy_discount + excluded.buy_discount, "
                            + "units_sold = units_sold + excluded.units_sold, "
                            + "units_bought = units_bought + excluded.units_bought")) {
                ps.setLong(1, day);
                ps.setString(2, itemId);
                ps.setDouble(3, finite(bonus));
                ps.setDouble(4, finite(discount));
                ps.setLong(5, sold);
                ps.setLong(6, bought);
                ps.executeUpdate();
            }
        }
    }

    /** Every ledger row from local day {@code day} on, oldest day first, then by item id. */
    public List<LedgerRow> ledgerSince(long day) throws SQLException {
        List<LedgerRow> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT day, item_id, sell_bonus, buy_discount, units_sold, units_bought FROM market_sim_ledger "
                            + "WHERE day >= ? ORDER BY day, item_id")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new LedgerRow(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getLong(5), rs.getLong(6)));
                    }
                }
            }
        }
        return out;
    }

    // ---- seen and mute ------------------------------------------------------------------------

    /** The player's catch-up mark and mute, or empty when they have no row yet. */
    public Optional<Seen> seen(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_event_id, muted, seen_at FROM market_news_seen WHERE player = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Seen(player, rs.getLong(1), rs.getInt(2) != 0, rs.getLong(3)));
                }
            }
        }
    }

    /**
     * Record a catch-up: the player has now seen everything up to {@code lastEventId}, at
     * {@code at}. Creates the row (not muted) if needed. The mark only ever moves forward, so a
     * late or repeated write can never bring old news back.
     */
    public void setSeen(UUID player, long lastEventId, long at) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_news_seen(player, last_event_id, muted, seen_at) VALUES(?,?,0,?) "
                            + "ON CONFLICT(player) DO UPDATE SET "
                            + "last_event_id = MAX(last_event_id, excluded.last_event_id), seen_at = excluded.seen_at")) {
                ps.setString(1, player.toString());
                ps.setLong(2, Math.max(0L, lastEventId));
                ps.setLong(3, at);
                ps.executeUpdate();
            }
        }
    }

    /**
     * A live broadcast reached these players at {@code at}: move each one's mark up to
     * {@code lastEventId} and their {@code seen_at} up to {@code at} (neither ever back), leaving
     * the mute alone. Players with no row get one. One transaction for the lot.
     *
     * <p>{@code seen_at} is when the player was last brought up to date — their last catch-up or
     * the last broadcast they heard. The catch-up uses it to find a HOT/DEAL that became news
     * after that although its id (given when its silent ramp began) is below the mark.
     */
    public void advanceSeen(Collection<UUID> players, long lastEventId, long at) throws SQLException {
        if (players == null || players.isEmpty()) {
            return;
        }
        database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_news_seen(player, last_event_id, muted, seen_at) VALUES(?,?,0,?) "
                            + "ON CONFLICT(player) DO UPDATE SET "
                            + "last_event_id = MAX(last_event_id, excluded.last_event_id), "
                            + "seen_at = MAX(seen_at, excluded.seen_at)")) {
                for (UUID p : players) {
                    if (p == null) {
                        continue;
                    }
                    ps.setString(1, p.toString());
                    ps.setLong(2, Math.max(0L, lastEventId));
                    ps.setLong(3, Math.max(0L, at));
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /**
     * {@code /hcm market news on|off}. A player with no row gets one whose mark is the current
     * newest event, so unmuting later does not replay old news.
     */
    public void setMuted(UUID player, boolean muted) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_news_seen(player, last_event_id, muted, seen_at) "
                            + "VALUES(?, (SELECT COALESCE(MAX(id), 0) FROM market_events), ?, 0) "
                            + "ON CONFLICT(player) DO UPDATE SET muted = excluded.muted")) {
                ps.setString(1, player.toString());
                ps.setInt(2, muted ? 1 : 0);
                ps.executeUpdate();
            }
        }
    }

    // ---- real-world quotes --------------------------------------------------------------------

    /**
     * Cache fetched closes for {@code symbol} inside the caller's transaction. A trade day already
     * stored gets the new close and fetch time but keeps its {@code applied} flag. Closes that are
     * not finite and positive are skipped.
     *
     * @return how many rows were written
     */
    public int upsertQuotes(Connection c, String symbol, List<RealQuotes.DailyClose> closes, long fetchedAt)
            throws SQLException {
        if (symbol == null || closes == null || closes.isEmpty()) {
            return 0;
        }
        int n = 0;
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO market_real_quotes(symbol, trade_day, close, fetched_at, applied) VALUES(?,?,?,?,0) "
                            + "ON CONFLICT(symbol, trade_day) DO UPDATE SET close = excluded.close, "
                            + "fetched_at = excluded.fetched_at")) {
                for (RealQuotes.DailyClose d : closes) {
                    if (d == null || d.day() == null || !Double.isFinite(d.close()) || d.close() <= 0) {
                        continue;
                    }
                    ps.setString(1, symbol);
                    ps.setLong(2, d.day().toEpochDay());
                    ps.setDouble(3, d.close());
                    ps.setLong(4, fetchedAt);
                    n += ps.executeUpdate();
                }
            }
        }
        return n;
    }

    /**
     * Mark {@code (symbol, tradeDay)} applied inside the caller's transaction. Exactly one call
     * per stored quote returns {@code true}. The tick calls it (through
     * {@link #markApplied(Connection, MarketEvent)}) in the transaction that writes the REAL row.
     *
     * @return {@code true} when this call flipped it; {@code false} when it was already applied
     *         or no such quote is stored
     */
    public boolean markApplied(Connection c, String symbol, long tradeDay) throws SQLException {
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE market_real_quotes SET applied = 1 WHERE symbol = ? AND trade_day = ? AND applied = 0")) {
                ps.setString(1, symbol);
                ps.setLong(2, tradeDay);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /**
     * Mark the quote a REAL row came from applied, inside the tick's transaction that writes the
     * row ({@code tag = item:symbol:tradeDay}, {@link com.dierks.homecraft.market.sim.RealImpulse#tag}).
     * So a fetched move counts as used only once it really is a REAL event: a restart or pause
     * between the fetch and the next tick leaves it unapplied, to be queued again.
     *
     * @return {@code true} when this call flipped it; {@code false} for any other row, a tag that
     *         does not parse, or a quote already applied or not stored
     */
    public boolean markApplied(Connection c, MarketEvent real) throws SQLException {
        if (real == null || real.kind() != EventKind.REAL || real.tag() == null) {
            return false;
        }
        String tag = real.tag();
        int last = tag.lastIndexOf(':');
        int mid = last <= 0 ? -1 : tag.lastIndexOf(':', last - 1);
        if (mid < 0) {
            return false;
        }
        long day;
        try {
            day = Long.parseLong(tag.substring(last + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        String symbol = tag.substring(mid + 1, last);
        return !symbol.isEmpty() && markApplied(c, symbol, day);
    }

    /** The cached closes for {@code symbol} from trade day {@code sinceDay} on, oldest first. */
    public List<StoredQuote> quotes(String symbol, long sinceDay) throws SQLException {
        List<StoredQuote> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT symbol, trade_day, close, fetched_at, applied FROM market_real_quotes "
                            + "WHERE symbol = ? AND trade_day >= ? ORDER BY trade_day")) {
                ps.setString(1, symbol);
                ps.setLong(2, sinceDay);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new StoredQuote(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getLong(4),
                                rs.getInt(5) != 0));
                    }
                }
            }
        }
        return out;
    }

    // ---- popular and prune --------------------------------------------------------------------

    /**
     * Item ids anyone bought or sold on local day {@code day} or later (the planner's "popular"
     * set): the UNION of {@code market_daily_sells} and {@code market_daily_buys}, sorted. Read
     * only.
     */
    public Set<String> tradedSince(long day) throws SQLException {
        Set<String> out = new TreeSet<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id FROM market_daily_sells WHERE day >= ? AND units > 0 "
                            + "UNION SELECT item_id FROM market_daily_buys WHERE day >= ? AND units > 0")) {
                ps.setLong(1, day);
                ps.setLong(2, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Daily housekeeping, one transaction: events that closed before {@code eventCutoff}
     * ({@code ends_at < eventCutoff}; a row that has not closed by then stays however long ago it
     * started, so an active event is never pruned), ledger rows before local day
     * {@code ledgerDay}, and cached closes before trade day {@code quoteDay}.
     */
    public Pruned prune(long eventCutoff, long ledgerDay, long quoteDay) throws SQLException {
        return database.transaction(c -> {
            int events;
            int ledger;
            int quotes;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM market_events WHERE ends_at < ?")) {
                ps.setLong(1, eventCutoff);
                events = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM market_sim_ledger WHERE day < ?")) {
                ps.setLong(1, ledgerDay);
                ledger = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM market_real_quotes WHERE trade_day < ?")) {
                ps.setLong(1, quoteDay);
                quotes = ps.executeUpdate();
            }
            return new Pruned(events, ledger, quotes);
        });
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setLong(i, v);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** SQLite stores NaN as NULL, which a NOT NULL column refuses: a broken amount is stored as 0. */
    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    // ---- meta keys ----------------------------------------------------------------------------

    /**
     * The {@code market_sim_meta} keys (§11.1) and the codec for the two mutable planner objects
     * persisted there, {@link Schedule} and {@link BroadcastState}. Pure: no SQL.
     *
     * <p>Numbers are stored as decimal strings, flags as {@code 1}/{@code 0}, the seed as
     * unsigned hex, and "no day yet" ({@link Schedule#NO_DAY}) as an empty string. A missing or
     * unreadable value reads as the default, so a hand-edited row cannot stop the sim loading.
     */
    public static final class Meta {

        public static final String SEED = "seed";
        public static final String LAST_TICK_AT = "last_tick_at";
        public static final String WAS_ENABLED = "was_enabled";
        public static final String PAUSED = "paused";
        public static final String INTRO_DONE = "intro_done";
        public static final String MANUAL_COUNTER = "manual_counter";
        public static final String NEXT_HOT_AT = "next_hot_at";
        public static final String NEXT_DEAL_AT = "next_deal_at";
        public static final String NEXT_NEWS_AT = "next_news_at";
        public static final String NEWS_DAY = "news_day";
        public static final String NEWS_TODAY = "news_today";
        public static final String BROADCAST_DAY = "broadcast_day";
        public static final String BROADCASTS_TODAY = "broadcasts_today";
        public static final String LAST_BROADCAST_AT = "last_broadcast_at";
        /** Followed by a headline list name: {@code recent_up}, {@code recent_down}, … */
        public static final String RECENT_PREFIX = "recent_";
        public static final String REAL_LAST_FETCH_DAY = "real_last_fetch_day";
        public static final String REAL_ATTEMPTS_TODAY = "real_attempts_today";
        public static final String REAL_LAST_ATTEMPT_AT = "real_last_attempt_at";
        public static final String REAL_LAST_ERROR = "real_last_error";

        private Meta() {
        }

        /**
         * The schedule's rows: the three {@code next_*_at}, {@code news_day}, {@code news_today}
         * and one {@code recent_<list>} per headline list (an empty value for a list with no
         * memory, so a cleared memory overwrites the old one).
         */
        public static Map<String, String> of(Schedule s) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put(NEXT_HOT_AT, Long.toString(s.nextHotAt()));
            out.put(NEXT_DEAL_AT, Long.toString(s.nextDealAt()));
            out.put(NEXT_NEWS_AT, Long.toString(s.nextNewsAt()));
            out.put(NEWS_DAY, day(s.newsDay()));
            out.put(NEWS_TODAY, Integer.toString(s.newsToday()));
            for (String list : Headlines.LISTS) {
                out.put(RECENT_PREFIX + list, "");
            }
            s.recentLists().forEach((list, recent) -> out.put(RECENT_PREFIX + list, Headlines.formatRecent(recent)));
            return out;
        }

        /** The broadcast counters' rows: {@code broadcast_day}, {@code broadcasts_today}, {@code last_broadcast_at}, {@code intro_done}. */
        public static Map<String, String> of(BroadcastState b) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put(BROADCAST_DAY, day(b.day()));
            out.put(BROADCASTS_TODAY, Integer.toString(b.count()));
            out.put(LAST_BROADCAST_AT, Long.toString(b.lastAt()));
            out.put(INTRO_DONE, flag(b.introDone()));
            return out;
        }

        /** The schedule read back from {@link #of(Schedule)}'s rows. Missing next times read as 0 (due now). */
        public static Schedule schedule(Map<String, String> meta) {
            Map<String, List<Integer>> recent = new TreeMap<>();
            for (Map.Entry<String, String> en : meta.entrySet()) {
                String key = en.getKey();
                if (key != null && key.startsWith(RECENT_PREFIX) && key.length() > RECENT_PREFIX.length()) {
                    List<Integer> r = Headlines.parseRecent(en.getValue());
                    if (!r.isEmpty()) {
                        recent.put(key.substring(RECENT_PREFIX.length()), r);
                    }
                }
            }
            return new Schedule(longOf(meta, NEXT_HOT_AT, 0L), longOf(meta, NEXT_DEAL_AT, 0L),
                    longOf(meta, NEXT_NEWS_AT, 0L), longOf(meta, NEWS_DAY, Schedule.NO_DAY),
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0L, longOf(meta, NEWS_TODAY, 0L))), recent);
        }

        /** The broadcast counters read back from {@link #of(BroadcastState)}'s rows. */
        public static BroadcastState broadcast(Map<String, String> meta) {
            return new BroadcastState(longOf(meta, BROADCAST_DAY, Schedule.NO_DAY),
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0L, longOf(meta, BROADCASTS_TODAY, 0L))),
                    longOf(meta, LAST_BROADCAST_AT, 0L), flagOf(meta, INTRO_DONE));
        }

        /** The seed as stored: unsigned hex. Never log or print it. */
        public static String seedHex(long seed) {
            return Long.toHexString(seed);
        }

        /** The stored seed, or empty when there is none yet (or it is unreadable). */
        public static OptionalLong seed(Map<String, String> meta) {
            String v = meta.get(SEED);
            if (v == null || v.isBlank()) {
                return OptionalLong.empty();
            }
            try {
                return OptionalLong.of(Long.parseUnsignedLong(v.trim().toLowerCase(Locale.ROOT), 16));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }

        /** A decimal value, or {@code def} when missing, blank or not a number. */
        public static long longOf(Map<String, String> meta, String key, long def) {
            String v = meta.get(key);
            if (v == null || v.isBlank()) {
                return def;
            }
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        /** A flag: {@code 1} or {@code true} (any case) is on; anything else, or missing, is off. */
        public static boolean flagOf(Map<String, String> meta, String key) {
            String v = meta.get(key);
            if (v == null) {
                return false;
            }
            String t = v.trim();
            return t.equals("1") || t.equalsIgnoreCase("true");
        }

        /** How a flag is stored. */
        public static String flag(boolean on) {
            return on ? "1" : "0";
        }

        private static String day(long day) {
            return day == Schedule.NO_DAY ? "" : Long.toString(day);
        }
    }
}
