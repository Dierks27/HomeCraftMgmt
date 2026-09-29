package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daily Courses' admin overrides and bookkeeping in {@code hcm_meta} (GEN-SPEC §5.5): a slot
 * switched on or off, its tier or golf mix, a pinned seed, today's reroll count, the region it has
 * claimed, and the difficulty its live layout was made with.
 *
 * <p>Why {@code hcm_meta} and not a table of its own: there is no schema migration for Daily
 * Courses (v34 is the last one, and a v35 would clash with any other branch), and these are a
 * handful of small values an admin can read with one query. Why only keys under {@code gen.}:
 * {@code hcm_meta} also holds the schema version and the games secret, and a typo here must never
 * be able to overwrite either — every method refuses any other key with
 * {@link IllegalArgumentException}, before touching the database.
 *
 * <p>Runs on the main thread against the plugin's one connection, like every DAO. A write made
 * inside {@link Database#transaction} joins it, so a flip can store the new course row and its
 * bookkeeping as one unit.
 */
public final class GenMetaDao {

    /** Every key this DAO may read or write starts with this. */
    public static final String PREFIX = "gen.";

    private final Database database;

    public GenMetaDao(Database database) {
        this.database = database;
    }

    /** The value of {@code key}, or {@code null} when unset. */
    public String get(String key) throws SQLException {
        check(key);
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    /** Set {@code key} to {@code value}; a {@code null} value removes it. */
    public void set(String key, String value) throws SQLException {
        check(key);
        if (value == null) {
            delete(key);
            return;
        }
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO hcm_meta(key, value) VALUES(?, ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        }
    }

    /** Remove {@code key}. */
    public void delete(String key) throws SQLException {
        check(key);
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        }
    }

    /** Every key starting with {@code prefix} ({@code gen.} or a longer prefix under it), by key. */
    public Map<String, String> like(String prefix) throws SQLException {
        checkPrefix(prefix);
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key, value FROM hcm_meta WHERE substr(key, 1, ?) = ? ORDER BY key")) {
                ps.setInt(1, prefix.length());
                ps.setString(2, prefix);
                Map<String, String> out = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
                return out;
            }
        }
    }

    /** Remove every key starting with {@code prefix} ({@code gen.} or longer). @return keys removed */
    public int deleteLike(String prefix) throws SQLException {
        checkPrefix(prefix);
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM hcm_meta WHERE substr(key, 1, ?) = ?")) {
                ps.setInt(1, prefix.length());
                ps.setString(2, prefix);
                return ps.executeUpdate();
            }
        }
    }

    /** Whether {@code key} is one this DAO may touch: {@code gen.} and at least one more character. */
    public static boolean allowed(String key) {
        return key != null && key.length() > PREFIX.length() && key.startsWith(PREFIX);
    }

    private static void checkPrefix(String prefix) {
        if (prefix == null || !prefix.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Daily Courses only keeps keys under " + PREFIX + ", not " + prefix);
        }
    }

    private static void check(String key) {
        if (!allowed(key)) {
            throw new IllegalArgumentException("Daily Courses only keeps keys under " + PREFIX + ", not " + key);
        }
    }
}
