package com.dierks.homecraft.storage;

import com.dierks.homecraft.graves.Grave;
import com.dierks.homecraft.graves.GraveItems;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@code graves} table (schema v40, HCM-GRAVES §3-4): every grave and what is still in it.
 *
 * <p><b>Every way out of a grave is one guarded write</b>, and it is made before anything is handed over: emptying
 * it ({@link #claimAll}), taking one stack ({@link #takeOne}) and taking the experience ({@link #takeXp}) each read
 * the row and change it in one {@link Database#transaction}, and only on a row that is still {@code OPEN} and still
 * holds what is being taken. The second of two clicks, two players, or a screen left open while someone else emptied
 * the grave gets nothing back from these, so nothing can come out twice. A grave whose contents are given out is
 * already empty in the database: a crash after that point can lose a stack in flight but never copy one.
 */
public final class GraveDao {

    private static final String COLUMNS = "id, owner, owner_name, world, x, y, z, created_at, xp, items, state, "
            + "closed_at, closed_by, notice";

    private final Database database;

    public GraveDao(Database database) {
        this.database = database;
    }

    /** A new OPEN grave; returns its id. */
    public long create(UUID owner, String ownerName, String world, int x, int y, int z, long createdAt, int xp,
                       List<GraveItems.Entry> items) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO graves(owner, owner_name, world, x, y, z, created_at, xp, items, state) "
                            + "VALUES(?,?,?,?,?,?,?,?,?, 'OPEN')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, owner.toString());
                ps.setString(2, ownerName == null ? "" : ownerName);
                ps.setString(3, world);
                ps.setInt(4, x);
                ps.setInt(5, y);
                ps.setInt(6, z);
                ps.setLong(7, createdAt);
                ps.setInt(8, Math.max(0, xp));
                ps.setString(9, GraveItems.encode(items));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
    }

    /** One grave, open or not, or null. */
    public Grave get(long id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return one(c, id);
        }
    }

    /** Every open grave, oldest first. */
    public List<Grave> open() throws SQLException {
        return query("SELECT " + COLUMNS + " FROM graves WHERE state = 'OPEN' ORDER BY id", null);
    }

    /** One player's open graves, oldest first. */
    public List<Grave> openFor(UUID owner) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM graves WHERE state = 'OPEN' AND owner = ? ORDER BY id",
                owner.toString());
    }

    /** One player's graves with a message waiting (expired while they were away). */
    public List<Grave> noticesFor(UUID owner) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM graves WHERE notice = 1 AND owner = ? ORDER BY id",
                owner.toString());
    }

    /** The messages about these graves have been given. */
    public void clearNotices(UUID owner) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE graves SET notice = 0 WHERE owner = ? AND notice = 1")) {
                ps.setString(1, owner.toString());
                ps.executeUpdate();
            }
        }
    }

    /**
     * Empty the grave in one guarded write and return it as it was (with everything that was in it), or null when it
     * isn't open any more: someone else got there first, so the caller gives nothing.
     *
     * @param state  how it ended ({@link Grave#LOOTED}, {@link Grave#RESTORED}, {@link Grave#EXPIRED},
     *               {@link Grave#REMOVED})
     * @param notice leave the owner a message about it at their next login
     */
    public Grave claimAll(long id, String state, String by, long now, boolean notice) throws SQLException {
        return database.transaction(c -> {
            Grave before = one(c, id);
            if (before == null || !before.open()) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE graves SET state = ?, items = '', xp = 0, closed_at = ?, closed_by = ?, notice = ? "
                            + "WHERE id = ? AND state = 'OPEN'")) {
                ps.setString(1, state);
                ps.setLong(2, now);
                ps.setString(3, by == null ? "" : by);
                ps.setInt(4, notice ? 1 : 0);
                ps.setLong(5, id);
                if (ps.executeUpdate() != 1) {
                    return null;
                }
            }
            return before;
        });
    }

    /** What {@link #takeOne} and {@link #takeXp} took, and whether the grave is now empty (and so closed). */
    public record Taken(GraveItems.Entry entry, int xp, boolean emptied) {
    }

    /**
     * Take stack {@code n} out of the grave in one guarded write, or null when it isn't there any more (taken by
     * another click, another player, or the grave was emptied). The grave closes as {@link Grave#LOOTED} when that
     * was the last of it.
     */
    public Taken takeOne(long id, int n, String by, long now) throws SQLException {
        return database.transaction(c -> {
            Grave before = one(c, id);
            if (before == null || !before.open()) {
                return null;
            }
            GraveItems.Entry found = null;
            for (GraveItems.Entry e : before.items()) {
                if (e.n() == n) {
                    found = e;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            List<GraveItems.Entry> left = GraveItems.without(before.items(), n);
            boolean emptied = left.isEmpty() && before.xp() <= 0;
            write(c, before, left, before.xp(), emptied, by, now);
            return new Taken(found, 0, emptied);
        });
    }

    /** Take the grave's experience in one guarded write, or null when there is none (any more). */
    public Taken takeXp(long id, String by, long now) throws SQLException {
        return database.transaction(c -> {
            Grave before = one(c, id);
            if (before == null || !before.open() || before.xp() <= 0) {
                return null;
            }
            boolean emptied = before.items().isEmpty();
            write(c, before, before.items(), 0, emptied, by, now);
            return new Taken(null, before.xp(), emptied);
        });
    }

    private static void write(Connection c, Grave before, List<GraveItems.Entry> items, int xp, boolean emptied,
                              String by, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE graves SET items = ?, xp = ?, state = ?, closed_at = ?, closed_by = ? "
                        + "WHERE id = ? AND state = 'OPEN'")) {
            ps.setString(1, GraveItems.encode(items));
            ps.setInt(2, xp);
            ps.setString(3, emptied ? Grave.LOOTED : Grave.OPEN);
            ps.setLong(4, emptied ? now : 0L);
            ps.setString(5, emptied ? (by == null ? "" : by) : "");
            ps.setLong(6, before.id());
            if (ps.executeUpdate() != 1) {
                // Nothing else can write between the read and this inside the transaction; a miss is a bug.
                throw new SQLException("grave " + before.id() + " changed under its own transaction");
            }
        }
    }

    private List<Grave> query(String sql, String arg) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (arg != null) {
                    ps.setString(1, arg);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Grave> out = new ArrayList<>();
                    while (rs.next()) {
                        Grave g = read(rs);
                        if (g != null) {
                            out.add(g);
                        }
                    }
                    return out;
                }
            }
        }
    }

    private static Grave one(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM graves WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** A row, or null when its owner can't be read (skipped, not fatal). */
    private static Grave read(ResultSet rs) throws SQLException {
        UUID owner;
        try {
            owner = UUID.fromString(rs.getString("owner"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        return new Grave(rs.getLong("id"), owner, rs.getString("owner_name"), rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getLong("created_at"), rs.getInt("xp"),
                GraveItems.decode(rs.getString("items")), rs.getString("state"), rs.getLong("closed_at"),
                rs.getString("closed_by"), rs.getInt("notice") != 0);
    }
}
