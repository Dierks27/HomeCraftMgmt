package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for {@code courier_debt} — what a player owes for crates that did not come back.
 *
 * <p>One row per player, and only while they owe something: a settled account is a deleted
 * row, not a row holding zero. That keeps "do they owe anything" a single primary-key lookup
 * on a table that stays empty on a server where nobody loses a crate, which is most of them.
 *
 * <p><b>Debt never goes below zero.</b> It is a consequence, not a currency — there is no such
 * thing as credit with the courier office, and letting the number go negative would turn
 * paying off a debt into a way to bank against a future one.
 */
public final class CourierDebtDao {

    private final Database database;

    public CourierDebtDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** What this player owes, or 0. */
    public double owed(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT amount FROM courier_debt WHERE player=?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Math.max(0, rs.getDouble(1)) : 0;
                }
            }
        }
    }

    /**
     * Add to what a player owes, creating the row if this is their first.
     *
     * @return the new total
     */
    public double add(UUID player, double amount, long now) throws SQLException {
        if (amount <= 0) {
            return owed(player);
        }
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO courier_debt(player, amount, since) VALUES(?,?,?) "
                            + "ON CONFLICT(player) DO UPDATE SET amount = amount + excluded.amount")) {
                ps.setString(1, player.toString());
                ps.setDouble(2, amount);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return owed(player);
        }
    }

    /**
     * Take an amount off what a player owes, clearing the row once nothing is left.
     *
     * <p>Clamped at zero on purpose — see the class note. Paying more than you owe settles the
     * account and no more; the caller is expected to have charged only what {@link #owed} said.
     *
     * @return what is still outstanding afterwards
     */
    public double subtract(UUID player, double amount) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            double remaining = Math.max(0, owed(player) - Math.max(0, amount));
            if (remaining <= 0.0001) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM courier_debt WHERE player=?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE courier_debt SET amount=? WHERE player=?")) {
                ps.setDouble(1, remaining);
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }
            return remaining;
        }
    }
}
