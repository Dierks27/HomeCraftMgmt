package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data access for Minis: the mint tally per type (the single source of truth for
 * caps) and per-copy provenance rows. Minted count is exact; circulation is
 * {@code minted - destroyed} (best-effort).
 *
 * <p>Mint numbers come from one place, {@link #mint}: the lowest number the escaped-copy repair
 * handed back ({@code mini_free_numbers}), else one past the highest ever issued. Taking the
 * number, bumping the tally and writing the provenance row are one transaction, so a number can
 * neither be skipped nor issued twice.
 */
public final class MiniDao {

    /**
     * Mint tally for one Mini type.
     *
     * @param escaped wild spawns of this Mini that got away before anyone caught them — nothing
     *                was minted for them (or, from before the hunt minted on pickup, their copy
     *                has since been repaired away), so they are NOT in {@code minted}
     */
    public record Counts(long minted, long destroyed, long escaped) {
        public Counts(long minted, long destroyed) {
            this(minted, destroyed, 0);
        }

        public long circulation() {
            return Math.max(0, minted - destroyed);
        }
    }

    /**
     * A retired copy that never had an owner and never changed hands — the shape a wild spawn's
     * copy took when it escaped under the old mint-on-spawn rule.
     */
    public record EscapedCopy(String uid, String miniId, long mintNumber, long mintedAt, long retiredAt) {
    }

    /** A (mini_id, mint_number) held by more than one copy. */
    public record Duplicate(String miniId, long mintNumber, int copies) {
    }

    /** Per-copy provenance row. */
    public record Individual(String uid, String miniId, long mintNumber, UUID owner, long mintedAt) {
    }

    /** How many live copies of a type one player holds (for the Museum's "who owns it"). */
    public record OwnerCount(UUID owner, long count) {
    }

    /** One logged secondary-market sale. */
    public record Sale(String uid, String miniId, double price, UUID seller, UUID buyer,
                       String venue, long soldAt) {
    }

    private final Database database;

    public MiniDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    public Counts counts(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT minted, destroyed, escaped FROM mini_counts WHERE mini_id = ?")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Counts(rs.getLong("minted"), rs.getLong("destroyed"), rs.getLong("escaped"));
                    }
                    return new Counts(0, 0, 0);
                }
            }
        }
    }

    /**
     * The {@code minted} tally of every Mini type that has a {@code mini_counts} row, in one
     * query — the website's Minis feed ("printed"). A type never minted may have no row (or a
     * row at 0 if one only escaped or was retired); callers default a missing id to 0.
     */
    public java.util.Map<String, Long> mintedCounts() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT mini_id, minted FROM mini_counts")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString("mini_id"), rs.getLong("minted"));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Mint one copy: take its number, bump the tally and write the provenance row, as one
     * transaction.
     *
     * <p>The number is the lowest one in {@code mini_free_numbers} for this Mini (numbers the
     * escaped-copy repair handed back), else one past the highest ever issued. "Highest ever
     * issued" is read as the larger of the tally and the largest number on record, so a number
     * is never reused even if an old provenance row went missing.
     *
     * @param owner  the first owner, or null
     * @param origin how the copy came to exist (WILD_SPAWN, WILD_DROP, PRINTER, ADMIN, …)
     * @return the mint number issued
     */
    public long mint(String miniId, UUID uid, UUID owner, String origin, long mintedAt) throws SQLException {
        return database.transaction(c -> {
            long number = -1;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT MIN(mint_number) FROM mini_free_numbers WHERE mini_id = ?")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long free = rs.getLong(1);
                        if (!rs.wasNull()) {
                            number = free;
                        }
                    }
                }
            }
            if (number > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM mini_free_numbers WHERE mini_id = ? AND mint_number = ?")) {
                    ps.setString(1, miniId);
                    ps.setLong(2, number);
                    ps.executeUpdate();
                }
            } else {
                long highest = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT MAX(COALESCE((SELECT minted FROM mini_counts WHERE mini_id = ?), 0), "
                                + "COALESCE((SELECT MAX(mint_number) FROM mini_individuals WHERE mini_id = ?), 0))")) {
                    ps.setString(1, miniId);
                    ps.setString(2, miniId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            highest = rs.getLong(1);
                        }
                    }
                }
                number = highest + 1;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_counts(mini_id, minted) VALUES(?, 1) "
                            + "ON CONFLICT(mini_id) DO UPDATE SET minted = minted + 1")) {
                ps.setString(1, miniId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_individuals(uid, mini_id, mint_number, owner, minted_at, origin) "
                            + "VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, uid.toString());
                ps.setString(2, miniId);
                ps.setLong(3, number);
                ps.setString(4, owner != null ? owner.toString() : null);
                ps.setLong(5, mintedAt);
                ps.setString(6, origin);
                ps.executeUpdate();
            }
            return number;
        });
    }

    /** A wild spawn got away: count it. Nothing was minted, so nothing else moves. */
    public void addEscaped(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_counts(mini_id, minted, escaped) VALUES(?, 0, 1) "
                            + "ON CONFLICT(mini_id) DO UPDATE SET escaped = escaped + 1")) {
                ps.setString(1, miniId);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Retired copies that look like a wild spawn that escaped under the old mint-on-spawn rule:
     * retired, never owned, never sold, and not marked as coming from anywhere else.
     *
     * <p>The signal is exact rather than a guess because only the natural-spawn path ever minted
     * with no owner — the Printer, wild drops and admin gives all mint straight to a player, and
     * a claimed spawn was handed an owner on pickup. A copy that was caught, sold or traded has
     * an owner or a sale row and is never matched.
     */
    public java.util.List<EscapedCopy> escapedCopies() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<EscapedCopy> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uid, mini_id, mint_number, minted_at, retired_at FROM mini_individuals i "
                            + "WHERE retired_at IS NOT NULL AND owner IS NULL "
                            + "AND (origin IS NULL OR origin = 'WILD_SPAWN') "
                            + "AND NOT EXISTS (SELECT 1 FROM mini_sales s WHERE s.uid = i.uid) "
                            + "ORDER BY mini_id, mint_number")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new EscapedCopy(rs.getString("uid"), rs.getString("mini_id"),
                                rs.getLong("mint_number"), rs.getLong("minted_at"), rs.getLong("retired_at")));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Undo escaped copies, as one transaction: delete each provenance row, take it back out of
     * {@code minted} and {@code destroyed}, count it as escaped, and hand its number to the
     * free pool so the next mint of that Mini reuses it.
     *
     * @return how many were repaired
     */
    public int repairEscaped(java.util.List<EscapedCopy> copies, long now) throws SQLException {
        return database.transaction(c -> {
            int done = 0;
            for (EscapedCopy e : copies) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM mini_individuals WHERE uid = ? AND retired_at IS NOT NULL AND owner IS NULL")) {
                    ps.setString(1, e.uid());
                    if (ps.executeUpdate() == 0) {
                        continue; // changed since the dry run — leave it alone
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE mini_counts SET minted = MAX(0, minted - 1), destroyed = MAX(0, destroyed - 1), "
                                + "escaped = escaped + 1 WHERE mini_id = ?")) {
                    ps.setString(1, e.miniId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO mini_free_numbers(mini_id, mint_number, freed_at) VALUES(?,?,?)")) {
                    ps.setString(1, e.miniId());
                    ps.setLong(2, e.mintNumber());
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }
                done++;
            }
            return done;
        });
    }

    /** Numbers waiting to be re-issued for one Mini, lowest first. */
    public java.util.List<Long> freeNumbers(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<Long> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT mint_number FROM mini_free_numbers WHERE mini_id = ? ORDER BY mint_number")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getLong(1));
                    }
                }
            }
            return out;
        }
    }

    /** Every (mini_id, mint_number) held by more than one copy. Empty on a healthy database. */
    public java.util.List<Duplicate> duplicateNumbers() throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<Duplicate> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT mini_id, mint_number, COUNT(*) AS n FROM mini_individuals "
                            + "GROUP BY mini_id, mint_number HAVING COUNT(*) > 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Duplicate(rs.getString(1), rs.getLong(2), rs.getInt(3)));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Make a duplicate mint number impossible from here on — {@code UNIQUE(mini_id, mint_number)}
     * — if the table holds none already. With duplicates present the index cannot be built;
     * they are returned for the log and the index is left off.
     */
    public java.util.List<Duplicate> ensureUniqueNumbers() throws SQLException {
        java.util.List<Duplicate> dups = duplicateNumbers();
        if (!dups.isEmpty()) {
            return dups;
        }
        Connection c = conn();
        synchronized (c) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_mini_ind_number "
                        + "ON mini_individuals (mini_id, mint_number)");
            }
        }
        return dups;
    }

    /**
     * Retire a minted copy on destruction: mark the individual retired and bump the
     * {@code destroyed} tally (lowering circulation) — but the mint_number is kept
     * forever, so the minted-against-cap slot is never freed. Idempotent: a copy
     * already retired (or unknown uid with a known copy already gone) is a no-op.
     *
     * @return true if this call actually retired a live copy (for logging/feedback).
     */
    public boolean retire(String uid, String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            if (uid != null && !uid.isBlank()) {
                // Only retire a provenance row that exists and isn't already retired.
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE mini_individuals SET retired_at = ? WHERE uid = ? AND retired_at IS NULL")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, uid);
                    if (ps.executeUpdate() == 0) {
                        return false; // unknown uid or already retired — don't double-count
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_counts(mini_id, minted, destroyed) VALUES(?, 0, 1) "
                            + "ON CONFLICT(mini_id) DO UPDATE SET destroyed = destroyed + 1")) {
                ps.setString(1, miniId);
                ps.executeUpdate();
            }
            return true;
        }
    }

    /**
     * The distinct Mini types this player currently holds a live copy of — the Museum's
     * "x of N collected". One query for the whole catalog: asking per Mini would be a
     * query per row on every menu build.
     */
    public java.util.Set<String> ownedIds(UUID owner) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.Set<String> out = new java.util.HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT mini_id FROM mini_individuals "
                            + "WHERE owner = ? AND retired_at IS NULL")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString("mini_id"));
                    }
                }
            }
            return out;
        }
    }

    /** Current holders of live (non-retired) copies of a type, most copies first. */
    public java.util.List<OwnerCount> owners(String miniId, int limit) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<OwnerCount> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT owner, COUNT(*) AS n FROM mini_individuals WHERE mini_id = ? AND retired_at IS NULL "
                            + "AND owner IS NOT NULL GROUP BY owner ORDER BY n DESC LIMIT ?")) {
                ps.setString(1, miniId);
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String owner = rs.getString("owner");
                        try {
                            out.add(new OwnerCount(UUID.fromString(owner), rs.getLong("n")));
                        } catch (IllegalArgumentException ignored) {
                            // malformed owner id — skip the row
                        }
                    }
                }
            }
            return out;
        }
    }

    /** @return the provenance row for a minted copy, if known. */
    public java.util.Optional<Individual> individual(String uid) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uid, mini_id, mint_number, owner, minted_at FROM mini_individuals WHERE uid = ?")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return java.util.Optional.empty();
                    }
                    String owner = rs.getString("owner");
                    return java.util.Optional.of(new Individual(rs.getString("uid"), rs.getString("mini_id"),
                            rs.getLong("mint_number"), owner != null ? UUID.fromString(owner) : null,
                            rs.getLong("minted_at")));
                }
            }
        }
    }

    /** Sales history for one copy, oldest first (the ownership trail). */
    public java.util.List<Sale> salesForUid(String uid) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            java.util.List<Sale> out = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uid, mini_id, price, seller, buyer, venue, sold_at FROM mini_sales "
                            + "WHERE uid = ? ORDER BY sold_at ASC")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapSale(rs));
                    }
                }
            }
            return out;
        }
    }

    /** @return the most recent sale price for a Mini type (any copy), or null if never sold. */
    public Double lastSalePrice(String miniId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT price FROM mini_sales WHERE mini_id = ? ORDER BY sold_at DESC LIMIT 1")) {
                ps.setString(1, miniId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble(1) : null;
                }
            }
        }
    }

    private Sale mapSale(ResultSet rs) throws SQLException {
        String seller = rs.getString("seller");
        String buyer = rs.getString("buyer");
        return new Sale(rs.getString("uid"), rs.getString("mini_id"), rs.getDouble("price"),
                seller != null ? UUID.fromString(seller) : null,
                buyer != null ? UUID.fromString(buyer) : null,
                rs.getString("venue"), rs.getLong("sold_at"));
    }

    /** Transfer provenance ownership of one minted copy (secondary-market sale/auction). */
    public void updateOwner(String uid, UUID newOwner) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mini_individuals SET owner = ? WHERE uid = ?")) {
                ps.setString(1, newOwner != null ? newOwner.toString() : null);
                ps.setString(2, uid);
                ps.executeUpdate();
            }
        }
    }

    /** Log a secondary-market sale (Vending Machine or Auction) for price history + provenance. */
    public void recordSale(String uid, String miniId, double price, UUID seller, UUID buyer,
                           String venue, long soldAt) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mini_sales(uid, mini_id, price, seller, buyer, venue, sold_at) "
                            + "VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, uid);
                ps.setString(2, miniId);
                ps.setDouble(3, price);
                ps.setString(4, seller != null ? seller.toString() : null);
                ps.setString(5, buyer != null ? buyer.toString() : null);
                ps.setString(6, venue);
                ps.setLong(7, soldAt);
                ps.executeUpdate();
            }
        }
    }
}
