package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Race Night's rows (EVENTS-DROPPER-SPEC §A.9, migration v36): the nights ({@code game_events}),
 * who joined each and what they are owed ({@code game_event_entries}), and every racer's result in
 * every race ({@code game_event_races}); plus Race Night's admin settings in {@code hcm_meta} under
 * {@code race.} (grids, stands, skipped nights, the pause).
 *
 * <p><b>Crash safety.</b> Each race is stored in ONE transaction ({@link #storeRace}): its result
 * rows, the points they add to each racer's night and to the season board, and the night's
 * {@code races_done}. The result rows go in first and a racer's points are added only when their
 * row was new, so storing the same race twice (a crash after the commit, before the runner moved
 * on) adds nothing. A prize is paid once: the payment carries the ref {@code event:<id>}, which
 * the rewards table refuses twice, and {@code paid_at} is only a record of it, so a crash between
 * the payment and {@code paid_at} is healed by the next pass ({@code PayLoop}).
 *
 * <p>Runs on the main thread against the plugin's one connection, like every DAO.
 */
public final class EventDao {

    /** The game every Race Night board and reward is kept under. */
    public static final String GAME = "race_night";
    /** Every {@code hcm_meta} key this DAO may touch starts with this. */
    public static final String META = "race.";

    /** A night's stored states. */
    public static final String OPEN = "OPEN";
    public static final String RUNNING = "RUNNING";
    public static final String SETTLING = "SETTLING";
    public static final String DONE = "DONE";
    public static final String CALLED_OFF = "CALLED_OFF";

    /** An entry's statuses. */
    public static final String IN = "IN";
    public static final String LEFT = "LEFT";

    private final Database database;
    private final GamesDao games;

    public EventDao(Database database, GamesDao games) {
        this.database = database;
        this.games = games;
    }

    /** One night's row. */
    public record EventRow(String id, String course, long joinAt, long startsAt, String state, String settings,
                           int racesDone, boolean prized, String week, String madeBy, long createdAt, Long endedAt,
                           String note) {
    }

    /** One racer's row for a night. */
    public record EntryRow(String eventId, UUID player, String name, long joinedAt, String status, int points,
                           Integer place, int prize, Long paidAt) {
    }

    /** One racer's result in one race. */
    public record RaceRow(String eventId, int race, UUID player, Integer place, Long ms, int targets, int points,
                          String result) {
    }

    /** One racer's final line of a night, as settling stores it. */
    public record Placed(UUID player, int place, int points, int prize) {
    }

    // ---- nights -------------------------------------------------------------------------------

    /** Write a night's row when its window opens; false (nothing written) when it already has one. */
    public boolean open(EventRow row) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO game_events(id, course, join_at, "
                    + "starts_at, state, settings, races_done, prized, week, made_by, created_at, ended_at, note) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, row.id());
                ps.setString(2, row.course());
                ps.setLong(3, row.joinAt());
                ps.setLong(4, row.startsAt());
                ps.setString(5, row.state());
                ps.setString(6, row.settings());
                ps.setInt(7, row.racesDone());
                ps.setInt(8, row.prized() ? 1 : 0);
                ps.setString(9, row.week() == null ? "" : row.week());
                ps.setString(10, row.madeBy() == null ? "" : row.madeBy());
                ps.setLong(11, row.createdAt());
                if (row.endedAt() == null) {
                    ps.setNull(12, Types.INTEGER);
                } else {
                    ps.setLong(12, row.endedAt());
                }
                ps.setString(13, row.note() == null ? "" : row.note());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** A night by id, or {@code null}. */
    public EventRow event(String id) throws SQLException {
        List<EventRow> rows = events("SELECT * FROM game_events WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Every night not over yet (OPEN, RUNNING, SETTLING), soonest start first. */
    public List<EventRow> live() throws SQLException {
        return events("SELECT * FROM game_events WHERE state IN ('OPEN', 'RUNNING', 'SETTLING') "
                + "ORDER BY starts_at, id");
    }

    /** The last {@code n} nights that ended (DONE or CALLED_OFF), newest first. */
    public List<EventRow> recent(int n) throws SQLException {
        return events("SELECT * FROM game_events WHERE state IN ('DONE', 'CALLED_OFF') "
                + "ORDER BY COALESCE(ended_at, starts_at) DESC, id DESC LIMIT ?", Math.max(1, n));
    }

    /** Whether a night with this id was ever written. */
    public boolean exists(String id) throws SQLException {
        return count("SELECT COUNT(*) FROM game_events WHERE id = ?", id) > 0;
    }

    /** Move a night to {@code state}; {@code endedAt} when it ended, else {@code null}. */
    public void setState(String id, String state, String note, Long endedAt) throws SQLException {
        database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE game_events SET state = ?, note = CASE WHEN ? = '' "
                    + "THEN note ELSE ? END, ended_at = COALESCE(?, ended_at) WHERE id = ?")) {
                String n = note == null ? "" : note;
                ps.setString(1, state);
                ps.setString(2, n);
                ps.setString(3, n);
                if (endedAt == null) {
                    ps.setNull(4, Types.INTEGER);
                } else {
                    ps.setLong(4, endedAt);
                }
                ps.setString(5, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The night's track (an auto night picks it when its window opens). */
    public void setCourse(String id, String course) throws SQLException {
        update("UPDATE game_events SET course = ? WHERE id = ?", course, id);
    }

    /** The night's start moved (an admin's {@code go}). */
    public void setStart(String id, long joinAt, long startsAt) throws SQLException {
        update("UPDATE game_events SET join_at = ?, starts_at = ? WHERE id = ?", joinAt, startsAt, id);
    }

    /**
     * Claim one of the week's prize-night slots for the night (§A.3), in one transaction: a night that
     * already holds one keeps it; otherwise it gets one when fewer than {@code perWeek} nights of
     * {@code week} hold one.
     *
     * @return whether the night holds a slot now
     */
    public boolean claimPrizeSlot(String id, String week, int perWeek) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT prized FROM game_events WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    if (rs.getInt(1) == 1) {
                        return true;
                    }
                }
            }
            int used;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM game_events WHERE week = ? AND prized = 1")) {
                ps.setString(1, week);
                try (ResultSet rs = ps.executeQuery()) {
                    used = rs.next() ? rs.getInt(1) : 0;
                }
            }
            boolean room = used < Math.max(0, perWeek);
            try (PreparedStatement ps = c.prepareStatement("UPDATE game_events SET week = ?, prized = ? WHERE id = ?")) {
                ps.setString(1, week);
                ps.setInt(2, room ? 1 : 0);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            return room;
        });
    }

    /** How many nights of {@code week} hold a prize slot. */
    public int prizedIn(String week) throws SQLException {
        return count("SELECT COUNT(*) FROM game_events WHERE week = ? AND prized = 1", week);
    }

    // ---- entries ------------------------------------------------------------------------------

    /**
     * The player joins (or, before the racing starts, joins again): their row is IN. Their name is
     * kept for the results. {@code false} when the night doesn't exist.
     */
    public boolean join(String eventId, UUID player, String name, long now) throws SQLException {
        return database.transaction(c -> {
            if (!exists(c, eventId)) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO game_event_entries(event_id, player, name, "
                    + "joined_at, status) VALUES(?,?,?,?,?) ON CONFLICT(event_id, player) DO UPDATE SET "
                    + "status = excluded.status, name = excluded.name")) {
                ps.setString(1, eventId);
                ps.setString(2, player.toString());
                ps.setString(3, name == null ? "" : name);
                ps.setLong(4, now);
                ps.setString(5, IN);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Set an entry's status (IN, LEFT). */
    public void setStatus(String eventId, UUID player, String status) throws SQLException {
        update("UPDATE game_event_entries SET status = ? WHERE event_id = ? AND player = ?", status, eventId,
                player.toString());
    }

    /** Remove an entry (left the list before the racing began: it never happened). */
    public void unjoin(String eventId, UUID player) throws SQLException {
        update("DELETE FROM game_event_entries WHERE event_id = ? AND player = ?", eventId, player.toString());
    }

    /** A night's entries in the order they joined. */
    public List<EntryRow> entries(String eventId) throws SQLException {
        return entryRows("SELECT * FROM game_event_entries WHERE event_id = ? ORDER BY joined_at, rowid", eventId);
    }

    /** Prizes the player is owed and hasn't been paid, oldest first. */
    public List<EntryRow> owed(UUID player) throws SQLException {
        return entryRows("SELECT e.* FROM game_event_entries e JOIN game_events g ON g.id = e.event_id "
                + "WHERE e.player = ? AND e.prize > 0 AND e.paid_at IS NULL AND g.state IN ('SETTLING', 'DONE', "
                + "'CALLED_OFF') ORDER BY e.joined_at", player.toString());
    }

    /** A night's unpaid prizes. */
    public List<EntryRow> unpaid(String eventId) throws SQLException {
        return entryRows("SELECT * FROM game_event_entries WHERE event_id = ? AND prize > 0 AND paid_at IS NULL "
                + "ORDER BY joined_at, rowid", eventId);
    }

    /** Record that a prize was paid (the payment itself is once-only by its ref). */
    public void markPaid(String eventId, UUID player, long now) throws SQLException {
        update("UPDATE game_event_entries SET paid_at = ? WHERE event_id = ? AND player = ? AND paid_at IS NULL", now,
                eventId, player.toString());
    }

    // ---- races --------------------------------------------------------------------------------

    /**
     * Store one race in ONE transaction: the result rows, each racer's points added to their night and
     * (when {@code seasonBoard} is set) to the season board, and the night's {@code races_done}. A racer
     * whose row for this race is already stored adds nothing, so storing a race twice is harmless.
     *
     * @param seasonBoard {@code rnseason:2026-10}, or {@code null} with the season off
     * @return how many result rows were new
     */
    public int storeRace(String eventId, int race, List<RaceRow> rows, String seasonBoard, long now)
            throws SQLException {
        return database.transaction(c -> {
            int added = 0;
            for (RaceRow r : rows) {
                boolean fresh;
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO game_event_races(event_id, race, "
                        + "player, place, ms, targets, points, result) VALUES(?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, eventId);
                    ps.setInt(2, race);
                    ps.setString(3, r.player().toString());
                    if (r.place() == null) {
                        ps.setNull(4, Types.INTEGER);
                    } else {
                        ps.setInt(4, r.place());
                    }
                    if (r.ms() == null) {
                        ps.setNull(5, Types.INTEGER);
                    } else {
                        ps.setLong(5, r.ms());
                    }
                    ps.setInt(6, r.targets());
                    ps.setInt(7, Math.max(0, r.points()));
                    ps.setString(8, r.result());
                    fresh = ps.executeUpdate() == 1;
                }
                if (!fresh) {
                    continue;
                }
                added++;
                if (r.points() > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE game_event_entries SET points = points + ? "
                            + "WHERE event_id = ? AND player = ?")) {
                        ps.setInt(1, r.points());
                        ps.setString(2, eventId);
                        ps.setString(3, r.player().toString());
                        ps.executeUpdate();
                    }
                    if (seasonBoard != null && !seasonBoard.isBlank()) {
                        games.addPoints(r.player(), GAME, seasonBoard, r.points(), now);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_events SET races_done = MAX(races_done, ?) WHERE id = ?")) {
                ps.setInt(1, race);
                ps.setString(2, eventId);
                ps.executeUpdate();
            }
            return added;
        });
    }

    /** Every stored race row of a night, race by race, best place first. */
    public List<RaceRow> races(String eventId) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM game_event_races WHERE event_id = ? "
                    + "ORDER BY race, place IS NULL, place, points DESC, player")) {
                ps.setString(1, eventId);
                List<RaceRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID p = uuid(rs.getString("player"));
                        if (p == null) {
                            continue;
                        }
                        int place = rs.getInt("place");
                        Integer placeOrNull = rs.wasNull() ? null : place;
                        long ms = rs.getLong("ms");
                        Long msOrNull = rs.wasNull() ? null : ms;
                        out.add(new RaceRow(eventId, rs.getInt("race"), p, placeOrNull, msOrNull, rs.getInt("targets"),
                                rs.getInt("points"), rs.getString("result")));
                    }
                }
                return out;
            }
        }
    }

    /**
     * Settle a night in ONE transaction: each racer's place and prize (a prize already paid is never
     * changed), the night's board ({@code nightBoard}, each racer's points, written once), and the
     * state SETTLING. Running it again changes nothing that was paid.
     */
    public void settle(String eventId, List<Placed> placed, String nightBoard, long now) throws SQLException {
        database.transaction(c -> {
            for (Placed p : placed) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE game_event_entries SET place = ?, "
                        + "prize = CASE WHEN paid_at IS NULL THEN ? ELSE prize END WHERE event_id = ? AND player = ?")) {
                    ps.setInt(1, p.place());
                    ps.setInt(2, Math.max(0, p.prize()));
                    ps.setString(3, eventId);
                    ps.setString(4, p.player().toString());
                    ps.executeUpdate();
                }
                if (nightBoard != null && p.points() > 0 && games.best(p.player(), GAME, nightBoard) == null) {
                    games.addPoints(p.player(), GAME, nightBoard, p.points(), now);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE game_events SET state = ? WHERE id = ? "
                    + "AND state NOT IN ('DONE', 'CALLED_OFF')")) {
                ps.setString(1, SETTLING);
                ps.setString(2, eventId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- hcm_meta: race.* ---------------------------------------------------------------------

    /** A Race Night setting ({@code race.grid.<course>}, {@code race.stand.<course>}, {@code race.skip.<id>}, {@code race.paused}). */
    public String meta(String key) throws SQLException {
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

    /** Set a Race Night setting; {@code null} removes it. */
    public void setMeta(String key, String value) throws SQLException {
        check(key);
        if (value == null) {
            update("DELETE FROM hcm_meta WHERE key = ?", key);
            return;
        }
        update("INSERT INTO hcm_meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, value);
    }

    /** Every Race Night setting under {@code prefix} ({@code race.skip.}), by key. */
    public Map<String, String> metaLike(String prefix) throws SQLException {
        check(prefix);
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

    private static void check(String key) {
        if (key == null || key.length() <= META.length() || !key.startsWith(META)) {
            throw new IllegalArgumentException("Race Night only keeps keys under " + META + ", not " + key);
        }
    }

    // ---- internals ----------------------------------------------------------------------------

    private static boolean exists(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM game_events WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private List<EventRow> events(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                List<EventRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long ended = rs.getLong("ended_at");
                        Long endedAt = rs.wasNull() ? null : ended;
                        out.add(new EventRow(rs.getString("id"), rs.getString("course"), rs.getLong("join_at"),
                                rs.getLong("starts_at"), rs.getString("state"), rs.getString("settings"),
                                rs.getInt("races_done"), rs.getInt("prized") == 1, rs.getString("week"),
                                rs.getString("made_by"), rs.getLong("created_at"), endedAt, rs.getString("note")));
                    }
                }
                return out;
            }
        }
    }

    private List<EntryRow> entryRows(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                List<EntryRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID p = uuid(rs.getString("player"));
                        if (p == null) {
                            continue;
                        }
                        int place = rs.getInt("place");
                        Integer placeOrNull = rs.wasNull() ? null : place;
                        long paid = rs.getLong("paid_at");
                        Long paidAt = rs.wasNull() ? null : paid;
                        out.add(new EntryRow(rs.getString("event_id"), p, rs.getString("name"), rs.getLong("joined_at"),
                                rs.getString("status"), rs.getInt("points"), placeOrNull, rs.getInt("prize"), paidAt));
                    }
                }
                return out;
            }
        }
    }

    private void update(String sql, Object... args) throws SQLException {
        database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private int count(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (a instanceof Long n) {
                ps.setLong(i + 1, n);
            } else {
                ps.setString(i + 1, a == null ? null : a.toString());
            }
        }
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
