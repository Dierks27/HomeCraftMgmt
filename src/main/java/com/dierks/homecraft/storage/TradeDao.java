package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The trade window's log ({@code trades}, schema v38, SHOP-V2-SPEC §4.12): one row per trade that ended. A swap
 * writes its row PENDING in its own transaction and flips it DONE once both bags are saved ({@link #finish}); an
 * admin settles one that didn't finish (DONE or VOID); a cancelled trade that had something on the table keeps
 * only its summaries. Every method joins a caller's transaction.
 */
public final class TradeDao {

    /** Written in the swap's transaction; not finished until both bags are saved. */
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    /** A PENDING trade an admin voided: its HELD Mailbox rows were deleted. */
    public static final String VOID = "VOID";
    /** Something was on the table, then the trade was called off; nothing moved. */
    public static final String CANCELLED = "CANCELLED";

    /** One row. {@code aItems} / {@code bItems} are one {@code Items.toBase64} per line. */
    public record Trade(long id, UUID a, String aName, UUID b, String bName, String world, long openedAt,
                        long closedAt, String outcome, String reason, String aItems, String bItems, String aSummary,
                        String bSummary, double aMoney, double bMoney, int mailbox) {

        /** The same row with another id (an insert's). */
        public Trade withId(long newId) {
            return new Trade(newId, a, aName, b, bName, world, openedAt, closedAt, outcome, reason, aItems, bItems,
                    aSummary, bSummary, aMoney, bMoney, mailbox);
        }
    }

    private final Database database;

    public TradeDao(Database database) {
        this.database = database;
    }

    /** Write a row (its id is ignored); returns the new id. */
    public long insert(Trade t) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO trades(a, a_name, b, b_name, world, opened_at, closed_at, outcome, reason, a_items, "
                            + "b_items, a_summary, b_summary, a_money, b_money, mailbox) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, t.a().toString());
                ps.setString(2, t.aName());
                ps.setString(3, t.b().toString());
                ps.setString(4, t.bName());
                ps.setString(5, t.world());
                ps.setLong(6, t.openedAt());
                ps.setLong(7, t.closedAt());
                ps.setString(8, t.outcome());
                ps.setString(9, t.reason() == null ? "" : t.reason());
                ps.setString(10, t.aItems() == null ? "" : t.aItems());
                ps.setString(11, t.bItems() == null ? "" : t.bItems());
                ps.setString(12, t.aSummary() == null ? "" : t.aSummary());
                ps.setString(13, t.bSummary() == null ? "" : t.bSummary());
                ps.setDouble(14, t.aMoney());
                ps.setDouble(15, t.bMoney());
                ps.setInt(16, t.mailbox());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    /**
     * Move a row from outcome {@code from} to {@code to} (PENDING to DONE or VOID; nothing ever moves a row back to
     * PENDING), only while it is still {@code from}: false when it wasn't (already settled, or no such trade). A
     * non-empty {@code reason} replaces the row's.
     */
    public boolean finish(long id, String from, String to, String reason) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE trades SET outcome=?, reason=CASE WHEN ?='' THEN reason ELSE ? END "
                            + "WHERE id=? AND outcome=?")) {
                String r = reason == null ? "" : reason;
                ps.setString(1, to);
                ps.setString(2, r);
                ps.setString(3, r);
                ps.setLong(4, id);
                ps.setString(5, from);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /**
     * Write a PENDING row's reason (where its bags are, {@code TradeStore.Saved}, or a bag save that didn't reach the
     * disk); a row that isn't PENDING is left alone.
     */
    public void pendingReason(long id, String reason) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE trades SET reason=? WHERE id=? AND outcome='" + PENDING + "'")) {
                ps.setString(1, reason == null ? "" : reason);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Write what the swap moved besides the stacks ({@code TradeStore.commit}, in its transaction): one line per Mini
     * owner, collector claim and cap that went home, so a void can put them back and an older trade's void can see a
     * later one carried them (red-team findings D4, R4FA-2).
     */
    public void moved(long id, String moved) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE trades SET moved=? WHERE id=?")) {
                ps.setString(1, moved == null ? "" : moved);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** What the swap moved besides the stacks ({@link #moved(long, String)}), or '' (none, or no such trade). */
    public String moved(long id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT moved FROM trades WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
                }
            }
        }
    }

    /**
     * A payment the swap gave back (a deposit to the payee failed and the refund went through): the row's
     * {@code a_money} or {@code b_money} becomes 0, so every later line names what really moved (red-team finding
     * MONEY-REFUND).
     */
    public void paidBack(long id, boolean a) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE trades SET " + (a ? "a_money" : "b_money") + "=0 WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    /** Add {@code n} to a row's Mailbox count (a stack {@code addItem} wouldn't take after all). */
    public void addMailbox(long id, int n) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE trades SET mailbox = mailbox + ? WHERE id=?")) {
                ps.setInt(1, n);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    public Optional<Trade> byId(long id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM trades WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    /** The last {@code n} trades, newest first; of one player (on either side) when {@code player} is set. */
    public List<Trade> recent(UUID player, int n) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Trade> out = new ArrayList<>();
            String sql = player == null ? "SELECT * FROM trades ORDER BY closed_at DESC, id DESC LIMIT ?"
                    : "SELECT * FROM trades WHERE a=? OR b=? ORDER BY closed_at DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                if (player != null) {
                    ps.setString(i++, player.toString());
                    ps.setString(i++, player.toString());
                }
                ps.setInt(i, Math.max(1, n));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    /** Every trade still PENDING (a swap the server didn't see finish), oldest first. */
    public List<Trade> pending() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Trade> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM trades WHERE outcome=? ORDER BY id ASC")) {
                ps.setString(1, PENDING);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    /**
     * A trade nothing needs its stacks for any more (it finished, or was settled): a side whose stored Base64 is past
     * {@code limit} becomes '' and its summary says so (SHOP-V2-SPEC §4.12). Until then each side is kept whole, so a
     * settle can hand back what one bag lost (red-team finding TCR4-5).
     */
    public void trim(long id, int limit) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            // every SET expression reads the row as it was, so each summary sees its side before the trim
            try (PreparedStatement ps = c.prepareStatement("UPDATE trades SET "
                    + "a_summary = CASE WHEN length(a_items) > ? THEN a_summary || ' (too big to store)' ELSE a_summary"
                    + " END, b_summary = CASE WHEN length(b_items) > ? THEN b_summary || ' (too big to store)' ELSE"
                    + " b_summary END, a_items = CASE WHEN length(a_items) > ? THEN '' ELSE a_items END, b_items ="
                    + " CASE WHEN length(b_items) > ? THEN '' ELSE b_items END WHERE id=?")) {
                for (int i = 1; i <= 4; i++) {
                    ps.setInt(i, limit);
                }
                ps.setLong(5, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Each side's balance just before the swap's money moved (red-team finding TCR4-3), in the swap's transaction;
     * null for a side Vault couldn't say.
     */
    public void before(long id, Double a, Double b) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE trades SET a_before=?, b_before=? WHERE id=?")) {
                if (a == null) {
                    ps.setNull(1, java.sql.Types.REAL);
                } else {
                    ps.setDouble(1, a);
                }
                if (b == null) {
                    ps.setNull(2, java.sql.Types.REAL);
                } else {
                    ps.setDouble(2, b);
                }
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    /** {@link #before(long, Double, Double)} as written: {@code [a, b]}, each null when unknown. */
    public Double[] before(long id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT a_before, b_before FROM trades WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Double[2];
                    }
                    double a = rs.getDouble(1);
                    Double ra = rs.wasNull() ? null : a;
                    double b = rs.getDouble(2);
                    Double rb = rs.wasNull() ? null : b;
                    return new Double[] {ra, rb};
                }
            }
        }
    }

    /** A later trade's {@code moved} lines ({@link #movedAfter}), with its outcome (PENDING or DONE). */
    public record Moved(long id, String outcome, String moved) {
    }

    /**
     * The {@code moved} lines of every later trade that still stands (PENDING or DONE, id above {@code id}), oldest
     * first: what a void of trade {@code id} must not put back, or must wait for (red-team findings R4FA-2, TCR4-1,
     * AW-3).
     */
    public List<Moved> movedAfter(long id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            List<Moved> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, outcome, moved FROM trades WHERE id>?"
                    + " AND moved<>'' AND outcome IN (?, ?) ORDER BY id ASC")) {
                ps.setLong(1, id);
                ps.setString(2, PENDING);
                ps.setString(3, DONE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Moved(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Delete rows closed before {@code before}. A PENDING row is never pruned, nor is any row after the oldest PENDING
     * one: its void reads what the later trades moved ({@link #movedAfter}), and a collector claim has nothing else to
     * say a later trade carried it (red-team finding SETTLE-PRUNE-3). Returns how many went.
     */
    public int prune(long before) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM trades WHERE closed_at < ? AND outcome <> ?"
                    + " AND NOT EXISTS (SELECT 1 FROM trades p WHERE p.outcome = ? AND p.id < trades.id)")) {
                ps.setLong(1, before);
                ps.setString(2, PENDING);
                ps.setString(3, PENDING);
                return ps.executeUpdate();
            }
        }
    }

    /**
     * Which of {@code uids} are in an active listing: a Display Case or Vending listing ({@code mini_listings},
     * {@code mini_vending_listings}) or an ACTIVE auction. A Mini that is there and on a trade table too is a copy
     * (red team D-m4).
     */
    public Set<String> listedUids(Collection<String> uids) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        if (uids == null || uids.isEmpty()) {
            return out;
        }
        Connection c = database.connection();
        synchronized (c) {
            for (String uid : uids) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM mini_listings WHERE uid=? UNION ALL "
                                + "SELECT 1 FROM mini_vending_listings WHERE uid=? UNION ALL "
                                + "SELECT 1 FROM mini_auctions WHERE uid=? AND status='ACTIVE' LIMIT 1")) {
                    ps.setString(1, uid);
                    ps.setString(2, uid);
                    ps.setString(3, uid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.add(uid);
                        }
                    }
                }
            }
        }
        return out;
    }

    private Trade map(ResultSet rs) throws SQLException {
        return new Trade(rs.getLong("id"), UUID.fromString(rs.getString("a")), rs.getString("a_name"),
                UUID.fromString(rs.getString("b")), rs.getString("b_name"), rs.getString("world"),
                rs.getLong("opened_at"), rs.getLong("closed_at"), rs.getString("outcome"), rs.getString("reason"),
                rs.getString("a_items"), rs.getString("b_items"), rs.getString("a_summary"),
                rs.getString("b_summary"), rs.getDouble("a_money"), rs.getDouble("b_money"), rs.getInt("mailbox"));
    }
}
