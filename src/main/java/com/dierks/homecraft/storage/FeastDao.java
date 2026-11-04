package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.feast.FeastRules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Family Feast's markers in {@code hcm_meta} (0.38.6): the pot's block ({@code feast.pot}), and per edition the
 * fill ({@code feast.<ed>.done}), the show over the pot ({@code feast.<ed>.home_shown}) and the helpers in the order
 * of their first gift ({@code feast.<ed>.order}). No schema change: the points live on {@code game_scores} boards,
 * the per-player markers in {@code player_counters} and the reward in {@code game_rewards}.
 *
 * <p>Why only keys under {@code feast.}: {@code hcm_meta} also holds the schema version and the games secret, so
 * every method refuses any other key with {@link IllegalArgumentException} before touching the database, as
 * {@link GenMetaDao} does for {@code gen.}.
 *
 * <p>{@link #reset} wipes one edition in one transaction so the owner can try the Feast out under a test edition;
 * it refuses a year's edition once any helper was paid, so the real Feast can never pay twice. Runs on the main
 * thread against the plugin's one connection, like every DAO.
 */
public final class FeastDao {

    /** Every key this DAO may read or write starts with this. */
    public static final String PREFIX = FeastRules.META_PREFIX;

    private final Database database;

    public FeastDao(Database database) {
        this.database = database;
    }

    /** The value of {@code key}, or {@code null} when unset. */
    public String get(String key) throws SQLException {
        check(key);
        Connection c = database.connection();
        synchronized (c) {
            return get(c, key);
        }
    }

    /** Set {@code key} to {@code value}; a {@code null} value removes it. */
    public void set(String key, String value) throws SQLException {
        check(key);
        Connection c = database.connection();
        synchronized (c) {
            set(c, key, value);
        }
    }

    /** The helpers of {@code edition} in the order of their first gift. */
    public List<UUID> order(String edition) throws SQLException {
        return FeastRules.orderOf(get(FeastRules.orderKey(edition)));
    }

    /** Add {@code player} to the end of the edition's order, once. @return whether they were added now */
    public boolean appendOrder(String edition, UUID player) throws SQLException {
        String key = FeastRules.orderKey(edition);
        check(key);
        return database.transaction(c -> {
            List<UUID> order = new ArrayList<>(FeastRules.orderOf(get(c, key)));
            if (order.contains(player)) {
                return false;
            }
            order.add(player);
            set(c, key, FeastRules.orderText(order));
            return true;
        });
    }

    /** How many helpers of {@code edition} have been paid (its {@code game_rewards} rows). */
    public int rewardRows(String edition) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM game_rewards WHERE game = ? AND ref = ?")) {
                ps.setString(1, FeastRules.GAME);
                ps.setString(2, ref(edition));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /** What {@link #reset} did: refused (a year's edition already filled, paid or gave a Mini), or the rows removed. */
    public record Reset(boolean refused, int scores, int markers, int counters, int rewards) {
    }

    /**
     * Wipe {@code edition} in one transaction: its points boards (the day boards too), its {@code feast.<ed>.}
     * markers, its {@code feast:<ed>:} counters and, for an edition that isn't a year, its reward rows. Refused for a
     * year's edition once its table has filled, any reward row exists or any Turkey Mini mark is set. Tokens already
     * paid stay paid. The pot stays where it is.
     */
    public Reset reset(String edition) throws SQLException {
        if (!com.dierks.homecraft.config.FeastConfig.validEdition(edition)) {
            throw new IllegalArgumentException("not an edition: " + edition);
        }
        return database.transaction(c -> {
            int paid;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM game_rewards WHERE game = ? AND ref = ?")) {
                ps.setString(1, FeastRules.GAME);
                ps.setString(2, ref(edition));
                try (ResultSet rs = ps.executeQuery()) {
                    paid = rs.next() ? rs.getInt(1) : 0;
                }
            }
            boolean done;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, FeastRules.doneKey(edition));
                try (ResultSet rs = ps.executeQuery()) {
                    done = rs.next();
                }
            }
            boolean minis;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM player_counters WHERE counter = ? AND value > 0 LIMIT 1")) {
                ps.setString(1, FeastRules.miniKey(edition));
                try (ResultSet rs = ps.executeQuery()) {
                    minis = rs.next();
                }
            }
            if (!FeastRules.resetAllowed(edition, paid, done, minis)) {
                return new Reset(true, 0, 0, 0, 0);
            }
            String board = FeastRules.board(edition);
            int scores;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM game_scores WHERE game = ? AND (board = ? "
                    + "OR substr(board, 1, ?) = ?)")) {
                ps.setString(1, FeastRules.GAME);
                ps.setString(2, board);
                ps.setInt(3, board.length() + 1);
                ps.setString(4, board + ":");
                scores = ps.executeUpdate();
            }
            String meta = FeastRules.metaPrefix(edition);
            int markers;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM hcm_meta WHERE substr(key, 1, ?) = ?")) {
                ps.setInt(1, meta.length());
                ps.setString(2, meta);
                markers = ps.executeUpdate();
            }
            String counters = FeastRules.counterPrefix(edition);
            int counted;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM player_counters WHERE substr(counter, 1, ?) = ?")) {
                ps.setInt(1, counters.length());
                ps.setString(2, counters);
                counted = ps.executeUpdate();
            }
            int rewards = 0;
            if (!FeastRules.yearEdition(edition)) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM game_rewards WHERE game = ? AND ref = ?")) {
                    ps.setString(1, FeastRules.GAME);
                    ps.setString(2, ref(edition));
                    rewards = ps.executeUpdate();
                }
            }
            return new Reset(false, scores, markers, counted, rewards);
        });
    }

    /** The reward's ref as stored: {@code event:feast-<ed>} (the games' event ref). */
    static String ref(String edition) {
        return "event:" + FeastRules.eventId(edition);
    }

    /** Whether {@code key} is one this DAO may touch: {@code feast.} and at least one more character. */
    public static boolean allowed(String key) {
        return key != null && key.length() > PREFIX.length() && key.startsWith(PREFIX);
    }

    private static String get(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM hcm_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void set(Connection c, String key, String value) throws SQLException {
        if (value == null) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO hcm_meta(key, value) VALUES(?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static void check(String key) {
        if (!allowed(key)) {
            throw new IllegalArgumentException("the Family Feast only keeps keys under " + PREFIX + ", not " + key);
        }
    }
}
