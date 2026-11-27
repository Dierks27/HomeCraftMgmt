package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Sports Board's picks (schema v42, {@code sports_picks}): one row per pick on a real game, from the moment the
 * money is taken until its market is decided.
 *
 * <p>The money itself is the server's bank, not this database, so the order is the Parlor's: the stake is taken,
 * then the row is written (and the stake put back if the write fails). Settling is the other way round: the row is
 * moved out of {@code OPEN} by one guarded {@code UPDATE ... WHERE status = 'OPEN'} first, and only the call that moved
 * it pays (the Parlor's {@code giveCents}, which keeps what the bank refuses as owed). So a pick is paid once, even if
 * two checks race or the server stops between the two steps.
 *
 * <p>Runs on the main thread against the plugin's one connection, like every DAO; {@code now} is always the caller's.
 */
public final class SportsPicksDao {

    public static final String OPEN = "OPEN";
    public static final String WON = "WON";
    public static final String LOST = "LOST";
    /** Called off at Kalshi: the stake back. */
    public static final String VOID = "VOID";
    /** Still undecided {@code settle_days} after its game: the stake back. */
    public static final String LAPSED = "LAPSED";

    private static final String COLUMNS = "id, player, ticker, side, stake, price, payout, placed_at, status, "
            + "settled_at, paid, day, starts_at, league, game, pick, final_score";

    /**
     * One pick.
     *
     * @param id        its row
     * @param player    who made it
     * @param ticker    the market
     * @param side      {@code yes} or {@code no} on it
     * @param stake     whole dollars put in
     * @param price     the side's chance when it was made, in tenths of a cent (locked)
     * @param payout    the cents a win gives back (locked)
     * @param placedAt  when it was made
     * @param status    {@link #OPEN}, {@link #WON}, {@link #LOST}, {@link #VOID} or {@link #LAPSED}
     * @param settledAt when it was settled (0 while open)
     * @param paid      the cents given back (a win's payout, a refund's stake)
     * @param day       the local day it was made
     * @param startsAt  when its game starts (picks close then)
     * @param league    the league's config name
     * @param game      the game as the screen showed it ("San Antonio vs Sacramento")
     * @param pick      who was picked ("San Antonio")
     * @param finalScore 0.60.0 (v43): the real game's final score ("Final: CGY 6  PIT 3"), or {@code null} for none
     */
    public record Pick(long id, UUID player, String ticker, String side, int stake, int price, long payout,
                       long placedAt, String status, long settledAt, long paid, long day, long startsAt,
                       String league, String game, String pick, String finalScore) {

        /** A pick with no final score. */
        public Pick(long id, UUID player, String ticker, String side, int stake, int price, long payout,
                    long placedAt, String status, long settledAt, long paid, long day, long startsAt,
                    String league, String game, String pick) {
            this(id, player, ticker, side, stake, price, payout, placedAt, status, settledAt, paid, day, startsAt,
                    league, game, pick, null);
        }

        public boolean open() {
            return OPEN.equals(status);
        }
    }

    /**
     * 0.60.0: how everyone's settled picks went, all together: {picks settled, won, lost, dollars put in, cents
     * given back}.
     */
    public record Totals(long picks, long won, long lost, long in, long back) {
    }

    private final Database database;

    public SportsPicksDao(Database database) {
        this.database = database;
    }

    /** Write a new OPEN pick (the stake has already been taken). @return it, with its id */
    public Pick add(UUID player, String ticker, String side, int stake, int price, long payout, long now, long day,
                    long startsAt, String league, String game, String pick) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sports_picks(player, ticker, side, stake, price, payout, placed_at, status, day, "
                            + "starts_at, league, game, pick) VALUES(?,?,?,?,?,?,?,'OPEN',?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, player.toString());
                ps.setString(2, ticker);
                ps.setString(3, side);
                ps.setInt(4, stake);
                ps.setInt(5, price);
                ps.setLong(6, payout);
                ps.setLong(7, now);
                ps.setLong(8, day);
                ps.setLong(9, startsAt);
                ps.setString(10, league);
                ps.setString(11, game);
                ps.setString(12, pick);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("no id for the new pick");
                    }
                    id = keys.getLong(1);
                }
                return new Pick(id, player, ticker, side, stake, price, payout, now, OPEN, 0, 0, day, startsAt,
                        league, game, pick);
            }
        }
    }

    /**
     * Move an OPEN pick to {@code status}, saying it gives back {@code paid} cents: the one step that decides who pays
     * it. @return true only for the call that moved it (pay then); false when it was already settled
     */
    public boolean settle(long id, String status, long paid, long now) throws SQLException {
        return settle(id, status, paid, now, null);
    }

    /** {@link #settle(long, String, long, long)}, keeping the game's final score with it ({@code null}: none known). */
    public boolean settle(long id, String status, long paid, long now, String finalScore) throws SQLException {
        if (OPEN.equals(status)) {
            throw new IllegalArgumentException("a pick is settled to WON, LOST, VOID or LAPSED");
        }
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sports_picks SET status = ?, paid = ?, settled_at = ?, final_score = ? WHERE id = ?"
                            + " AND status = 'OPEN'")) {
                ps.setString(1, status);
                ps.setLong(2, Math.max(0, paid));
                ps.setLong(3, now);
                ps.setString(4, finalScore);
                ps.setLong(5, id);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /**
     * 0.60.0: a game's final score came in: kept on every settled pick on its markets ({@code event}'s, whose tickers
     * are {@code event-...}) that has none yet. @return how many picks took it
     */
    public int finalScore(String event, String finalScore) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sports_picks SET final_score = ? WHERE final_score IS NULL AND status <> 'OPEN'"
                            + " AND substr(ticker, 1, ?) = ?")) {
                ps.setString(1, finalScore);
                ps.setInt(2, event.length() + 1);
                ps.setString(3, event + "-");
                return ps.executeUpdate();
            }
        }
    }

    /** 0.60.0: every pick a player made, newest first: the open ones, then the settled ones, latest settled first. */
    public List<Pick> history(UUID player) throws SQLException {
        return mine(player, Integer.MAX_VALUE);
    }

    /** 0.60.0: everyone's settled picks together ({@link Totals}). */
    public Totals everyone() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*),"
                    + " COALESCE(SUM(CASE WHEN status = 'WON' THEN 1 ELSE 0 END), 0),"
                    + " COALESCE(SUM(CASE WHEN status = 'LOST' THEN 1 ELSE 0 END), 0),"
                    + " COALESCE(SUM(stake), 0), COALESCE(SUM(paid), 0)"
                    + " FROM sports_picks WHERE status <> 'OPEN'");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5)) : new Totals(0, 0, 0, 0, 0);
            }
        }
    }

    /** One pick, or {@code null}. */
    public Pick pick(long id) throws SQLException {
        List<Pick> l = query("SELECT " + COLUMNS + " FROM sports_picks WHERE id = ?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Every OPEN pick, oldest first. */
    public List<Pick> open() throws SQLException {
        return query("SELECT " + COLUMNS + " FROM sports_picks WHERE status = 'OPEN' ORDER BY id");
    }

    /** The OPEN picks on one market, oldest first. */
    public List<Pick> openOn(String ticker) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM sports_picks WHERE status = 'OPEN' AND ticker = ? ORDER BY id",
                ticker);
    }

    /** The markets that have OPEN picks. */
    public List<String> openTickers() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT ticker FROM sports_picks WHERE status = 'OPEN' ORDER BY ticker");
                 ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    /** A player's picks, newest first: every open one, then up to {@code settled} settled ones. */
    public List<Pick> mine(UUID player, int settled) throws SQLException {
        List<Pick> out = new ArrayList<>(query("SELECT " + COLUMNS + " FROM sports_picks WHERE player = ? "
                + "AND status = 'OPEN' ORDER BY id DESC", player.toString()));
        out.addAll(query("SELECT " + COLUMNS + " FROM sports_picks WHERE player = ? AND status <> 'OPEN' "
                + "ORDER BY settled_at DESC, id DESC LIMIT ?", player.toString(), Math.max(0, settled)));
        return out;
    }

    /** Dollars the player put into picks on this local day (whatever became of them): the Parlor's day limit. */
    public int inToday(UUID player, long day) throws SQLException {
        return (int) one("SELECT COALESCE(SUM(stake), 0) FROM sports_picks WHERE player = ? AND day = ?",
                player.toString(), day);
    }

    /**
     * Everyone's picks today: {dollars put in on {@code day}, cents given back since {@code since} (wins and
     * refunds), OPEN picks now}.
     */
    public long[] today(long day, long since) throws SQLException {
        return new long[] {
                one("SELECT COALESCE(SUM(stake), 0) FROM sports_picks WHERE day = ?", day),
                one("SELECT COALESCE(SUM(paid), 0) FROM sports_picks WHERE status <> 'OPEN' AND settled_at >= ?",
                        since),
                one("SELECT COUNT(*) FROM sports_picks WHERE status = 'OPEN'")};
    }

    private long one(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }
    }

    private List<Pick> query(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Pick> out = new ArrayList<>();
                    while (rs.next()) {
                        UUID p;
                        try {
                            p = UUID.fromString(rs.getString("player"));
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        out.add(new Pick(rs.getLong("id"), p, rs.getString("ticker"), rs.getString("side"),
                                rs.getInt("stake"), rs.getInt("price"), rs.getLong("payout"), rs.getLong("placed_at"),
                                rs.getString("status"), rs.getLong("settled_at"), rs.getLong("paid"),
                                rs.getLong("day"), rs.getLong("starts_at"), rs.getString("league"),
                                rs.getString("game"), rs.getString("pick"), rs.getString("final_score")));
                    }
                    return out;
                }
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (a instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, a == null ? null : String.valueOf(a));
            }
        }
    }
}
