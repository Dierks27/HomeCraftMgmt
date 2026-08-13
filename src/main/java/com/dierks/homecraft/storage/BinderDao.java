package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Data access for the Card Binder (Round 3a): how many of each Card a player has
 * stored in their binder, keyed by (player, card_id).
 */
public final class BinderDao {

    private final Database database;

    public BinderDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Every card a player has in their binder (card id → count), counts &gt; 0. */
    public Map<String, Integer> all(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            Map<String, Integer> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT card_id, count FROM binder_cards WHERE player=? AND count>0 ORDER BY card_id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            return out;
        }
    }

    public int count(UUID player, String cardId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count FROM binder_cards WHERE player=? AND card_id=?")) {
                ps.setString(1, player.toString());
                ps.setString(2, cardId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /** Add {@code n} of a card to the binder. */
    public void add(UUID player, String cardId, int n) throws SQLException {
        if (n <= 0) {
            return;
        }
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO binder_cards(player, card_id, count) VALUES(?,?,?) "
                            + "ON CONFLICT(player, card_id) DO UPDATE SET count = count + ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, cardId);
                ps.setInt(3, n);
                ps.setInt(4, n);
                ps.executeUpdate();
            }
        }
    }

    /** Take up to {@code n} of a card out of the binder; returns how many were actually removed. */
    public int take(UUID player, String cardId, int n) throws SQLException {
        if (n <= 0) {
            return 0;
        }
        Connection c = conn();
        synchronized (c) {
            int have = count(player, cardId);
            int take = Math.min(have, n);
            if (take <= 0) {
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE binder_cards SET count = count - ? WHERE player=? AND card_id=?")) {
                ps.setInt(1, take);
                ps.setString(2, player.toString());
                ps.setString(3, cardId);
                ps.executeUpdate();
            }
            return take;
        }
    }
}
