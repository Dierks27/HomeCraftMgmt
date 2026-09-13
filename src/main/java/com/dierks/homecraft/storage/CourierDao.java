package com.dierks.homecraft.storage;

import com.dierks.homecraft.courier.CourierJob;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Data access for {@code courier_jobs} — accepted delivery runs and their locked terms.
 *
 * <p>A job is written once at acceptance with everything the payout depends on, and after
 * that only its {@code state} changes. That is deliberate: the row is the contract, so a
 * restart, a config reload or a market move between accepting and arriving cannot alter what
 * the run is worth.
 */
public final class CourierDao {

    private final Database database;

    public CourierDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Write a newly accepted job and return it with its assigned id. */
    public CourierJob insert(CourierJob job) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO courier_jobs(player,type,state,band,world,accept_x,accept_y,accept_z,"
                            + "way_x,way_y,way_z,distance,cargo_material,cargo_amount,cargo_value,"
                            + "stat_snapshot,day,accepted_at,expires_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, job.player().toString());
                ps.setString(2, job.type().name());
                ps.setString(3, job.state().name());
                ps.setString(4, job.band().name());
                ps.setString(5, job.world());
                ps.setInt(6, job.acceptX());
                ps.setInt(7, job.acceptY());
                ps.setInt(8, job.acceptZ());
                ps.setInt(9, job.wayX());
                ps.setInt(10, job.wayY());
                ps.setInt(11, job.wayZ());
                ps.setInt(12, job.lockedDistance());
                ps.setString(13, job.cargoMaterial());
                ps.setInt(14, job.cargoAmount());
                ps.setDouble(15, job.lockedCargoValue());
                ps.setString(16, job.statSnapshot());
                ps.setLong(17, job.day());
                ps.setLong(18, job.acceptedAt());
                ps.setLong(19, job.expiresAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    return withId(job, id);
                }
            }
        }
    }

    /** A player's one live job, or null. Only ever one at a time, by design. */
    public CourierJob active(UUID player) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM courier_jobs WHERE player=? AND state='ACTIVE' "
                            + "ORDER BY accepted_at DESC LIMIT 1")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }
    }

    /**
     * How many of a band a player has used today. ACTIVE and DELIVERED both hold a slot;
     * EXPIRED does not, so abandoning a run gives the slot back rather than burning the day.
     */
    public int usedToday(UUID player, CourierJob.Band band, long day) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM courier_jobs WHERE player=? AND band=? AND day=? "
                            + "AND state IN ('ACTIVE','DELIVERED')")) {
                ps.setString(1, player.toString());
                ps.setString(2, band.name());
                ps.setLong(3, day);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /**
     * Move a job out of ACTIVE, but only if it is still ACTIVE. The guard is what makes
     * paying out exactly once safe: two turn-ins landing together, or a turn-in racing the
     * expiry sweep, and only one of them gets the row.
     *
     * @return true for the caller that actually made the change
     */
    public boolean finish(long id, CourierJob.State state) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE courier_jobs SET state=? WHERE id=? AND state='ACTIVE'")) {
                ps.setString(1, state.name());
                ps.setLong(2, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /** Expire every ACTIVE job past its deadline. Returns how many were closed. */
    public int expireStale(long now) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE courier_jobs SET state='EXPIRED' WHERE state='ACTIVE' AND expires_at <= ?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            }
        }
    }

    private CourierJob read(ResultSet rs) throws SQLException {
        return new CourierJob(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player")),
                CourierJob.Type.parse(rs.getString("type")),
                CourierJob.State.parse(rs.getString("state")),
                CourierJob.Band.parse(rs.getString("band")),
                rs.getString("world"),
                rs.getInt("accept_x"), rs.getInt("accept_y"), rs.getInt("accept_z"),
                rs.getInt("way_x"), rs.getInt("way_y"), rs.getInt("way_z"),
                rs.getInt("distance"),
                rs.getString("cargo_material"), rs.getInt("cargo_amount"), rs.getDouble("cargo_value"),
                rs.getString("stat_snapshot"),
                rs.getLong("day"), rs.getLong("accepted_at"), rs.getLong("expires_at"));
    }

    private CourierJob withId(CourierJob j, long id) {
        return new CourierJob(id, j.player(), j.type(), j.state(), j.band(), j.world(),
                j.acceptX(), j.acceptY(), j.acceptZ(), j.wayX(), j.wayY(), j.wayZ(),
                j.lockedDistance(), j.cargoMaterial(), j.cargoAmount(), j.lockedCargoValue(),
                j.statSnapshot(), j.day(), j.acceptedAt(), j.expiresAt());
    }
}
