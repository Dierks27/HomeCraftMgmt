package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data access for {@code mini_auctions} — timed Mini auctions with one escrowed top bid. */
public final class MiniAuctionDao {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";

    public record Auction(long id, String uid, String miniId, long mintNumber, UUID seller,
                          double startBid, double currentBid, UUID currentBidder, double buyNow,
                          String itemB64, long endAt, String status, long createdAt) {

        public boolean hasBid() {
            return currentBidder != null;
        }
    }

    private final Database database;

    public MiniAuctionDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public long create(String uid, String miniId, long mintNumber, UUID seller, double startBid,
                       double buyNow, String itemB64, long endAt, long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_auctions(uid,mini_id,mint_number,seller,start_bid,current_bid,"
                            + "current_bidder,buy_now,item_b64,end_at,status,created_at) "
                            + "VALUES(?,?,?,?,?,0,NULL,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uid);
                ps.setString(2, miniId);
                ps.setLong(3, mintNumber);
                ps.setString(4, seller.toString());
                ps.setDouble(5, startBid);
                ps.setDouble(6, buyNow);
                ps.setString(7, itemB64);
                ps.setLong(8, endAt);
                ps.setString(9, ACTIVE);
                ps.setLong(10, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }
    }

    public Optional<Auction> byId(long id) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM mini_auctions WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public List<Auction> active() throws SQLException {
        return query("SELECT * FROM mini_auctions WHERE status = '" + ACTIVE + "' ORDER BY end_at ASC");
    }

    public List<Auction> dueBy(long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Auction> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM mini_auctions WHERE status = ? AND end_at <= ?")) {
                ps.setString(1, ACTIVE);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        }
    }

    public void updateBid(long id, double bid, UUID bidder) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mini_auctions SET current_bid = ?, current_bidder = ? WHERE id = ?")) {
                ps.setDouble(1, bid);
                ps.setString(2, bidder != null ? bidder.toString() : null);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        }
    }

    public void extendEnd(long id, long endAt) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE mini_auctions SET end_at = ? WHERE id = ?")) {
                ps.setLong(1, endAt);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    public void setStatus(long id, String status) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE mini_auctions SET status = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    private List<Auction> query(String sql) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            List<Auction> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            return out;
        }
    }

    private Auction map(ResultSet rs) throws SQLException {
        String bidder = rs.getString("current_bidder");
        return new Auction(
                rs.getLong("id"), rs.getString("uid"), rs.getString("mini_id"), rs.getLong("mint_number"),
                UUID.fromString(rs.getString("seller")),
                rs.getDouble("start_bid"), rs.getDouble("current_bid"),
                bidder != null ? UUID.fromString(bidder) : null,
                rs.getDouble("buy_now"), rs.getString("item_b64"),
                rs.getLong("end_at"), rs.getString("status"), rs.getLong("created_at"));
    }
}
