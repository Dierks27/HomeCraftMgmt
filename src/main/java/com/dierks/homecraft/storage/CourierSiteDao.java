package com.dierks.homecraft.storage;

import com.dierks.homecraft.courier.DeliverySite;
import org.bukkit.block.structure.StructureRotation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Data access for {@code courier_sites} — placed delivery buildings and the fields they cover.
 *
 * <p>The ordering rule for this table is the whole safety story: a row is written <b>before</b>
 * the first block changes and deleted <b>after</b> the restore succeeds. Between those two
 * points the row is the only thing that knows how to put the field back, so nothing else may
 * remove it — not job expiry, not the player abandoning the run, not the plugin disabling.
 */
public final class CourierSiteDao {

    private final Database database;

    public CourierSiteDao(Database database) {
        this.database = database;
    }

    private Connection conn() {
        return database.connection();
    }

    /** Write the undo record. Called before the first block of the region is touched. */
    public void insert(DeliverySite site) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO courier_sites(job_id,world,origin_x,origin_y,origin_z,"
                            + "size_x,size_y,size_z,template,rotation,door_x,door_y,door_z,"
                            + "villager,state,snapshot,placed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, site.jobId());
                ps.setString(2, site.world());
                ps.setInt(3, site.originX());
                ps.setInt(4, site.originY());
                ps.setInt(5, site.originZ());
                ps.setInt(6, site.sizeX());
                ps.setInt(7, site.sizeY());
                ps.setInt(8, site.sizeZ());
                ps.setString(9, site.template());
                ps.setString(10, site.rotation().name());
                ps.setInt(11, site.doorX());
                ps.setInt(12, site.doorY());
                ps.setInt(13, site.doorZ());
                ps.setString(14, site.villager() == null ? null : site.villager().toString());
                ps.setString(15, site.state().name());
                ps.setBytes(16, site.snapshot());
                ps.setLong(17, site.placedAt());
                ps.executeUpdate();
            }
        }
    }

    /** Record the villager once it has actually spawned. */
    public void setVillager(long jobId, UUID villager) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE courier_sites SET villager=? WHERE job_id=?")) {
                ps.setString(1, villager == null ? null : villager.toString());
                ps.setLong(2, jobId);
                ps.executeUpdate();
            }
        }
    }

    public void setState(long jobId, DeliverySite.State state) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE courier_sites SET state=? WHERE job_id=?")) {
                ps.setString(1, state.name());
                ps.setLong(2, jobId);
                ps.executeUpdate();
            }
        }
    }

    /** Drop the undo record. Only ever called once the blocks are actually back. */
    public void delete(long jobId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM courier_sites WHERE job_id=?")) {
                ps.setLong(1, jobId);
                ps.executeUpdate();
            }
        }
    }

    public DeliverySite byJob(long jobId) throws SQLException {
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM courier_sites WHERE job_id=?")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }
    }

    /**
     * Every site that still has blocks in the world — {@code PLACED} or {@code RESTORE_PENDING}.
     *
     * <p>Read in full on enable. This is the set the startup sweep works through, and it is the
     * answer to the failure mode that leaves houses scattered across the map after a crash.
     */
    public List<DeliverySite> standing() throws SQLException {
        List<DeliverySite> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM courier_sites WHERE state IN ('PLACED','RESTORE_PENDING')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    /**
     * Standing sites whose job is no longer ACTIVE — the ones that owe a restore.
     *
     * <p>Joined against {@code courier_jobs} rather than tracked separately so the two tables
     * cannot disagree about whether a delivery is over. A site whose job row has vanished
     * entirely also lands here, which is right: with no job to finish, the house is litter.
     */
    public List<DeliverySite> owingRestore() throws SQLException {
        List<DeliverySite> out = new ArrayList<>();
        Connection c = conn();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.* FROM courier_sites s "
                            + "LEFT JOIN courier_jobs j ON j.id = s.job_id "
                            + "WHERE s.state IN ('PLACED','RESTORE_PENDING') "
                            + "AND (j.id IS NULL OR j.state <> 'ACTIVE')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    private DeliverySite read(ResultSet rs) throws SQLException {
        String villager = rs.getString("villager");
        StructureRotation rotation;
        try {
            rotation = StructureRotation.valueOf(
                    String.valueOf(rs.getString("rotation")).toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            rotation = StructureRotation.NONE;
        }
        UUID villagerId = null;
        if (villager != null && !villager.isBlank()) {
            try {
                villagerId = UUID.fromString(villager);
            } catch (IllegalArgumentException ignored) {
                // an unreadable id just means we fall back to scanning the region for the mob
            }
        }
        return new DeliverySite(
                rs.getLong("job_id"),
                rs.getString("world"),
                rs.getInt("origin_x"), rs.getInt("origin_y"), rs.getInt("origin_z"),
                rs.getInt("size_x"), rs.getInt("size_y"), rs.getInt("size_z"),
                rs.getString("template"), rotation,
                rs.getInt("door_x"), rs.getInt("door_y"), rs.getInt("door_z"),
                villagerId,
                DeliverySite.State.parse(rs.getString("state")),
                rs.getBytes("snapshot"),
                rs.getLong("placed_at"));
    }
}
