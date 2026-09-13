package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Asks one question: <b>has HomeCraft recorded anything at these coordinates?</b>
 *
 * <p>Five tables track things that live at a block position — custom blocks (the PC, Workbench,
 * Printer, Vending Machine, Pallet), placed Minis, natural Mini spawns, Pallet listings, Vending
 * listings and in-game displays — and every one of them shares the same {@code (world, x, y, z)}
 * shape. So this is a single UNION rather than a region query bolted onto five DAOs.
 *
 * <p>It exists for the Courier. A delivery building pasted over somebody's placed Mini would put
 * a <b>numbered, capped, uniquely-minted collectible</b> behind a wall for the length of the job,
 * and any failure in that window loses a copy that cannot be re-minted. The block snapshot would
 * dutifully restore the head; it would not restore the Mini's place in the world, and it cannot
 * restore what the owner lost access to. Rejecting the spot costs a reroll.
 */
public final class TrackedGroundDao {

    /**
     * Each table paired with the label reported when it is the one that matched.
     *
     * <p>Order matters only for which name comes back first; every entry is a refusal.
     */
    private static final String[][] SOURCES = {
            {"placed_blocks", "a placed HomeCraft block"},
            {"placed_minis", "a placed Mini"},
            {"mini_spawns", "a wild Mini waiting to be claimed"},
            {"pallet_listings", "a Pallet listing"},
            {"mini_vending_listings", "a Vending Machine listing"},
            {"displays", "an in-game display"},
    };

    private final Database database;

    public TrackedGroundDao(Database database) {
        this.database = database;
    }

    /**
     * The first tracked thing inside this box, described for a log line — or null if the ground
     * is clear.
     *
     * <p>Bounds are inclusive. Returns a description rather than a boolean because "the Courier
     * keeps refusing to build here" is otherwise a very hard thing for an admin to diagnose.
     */
    public String firstIn(String world, int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            for (String[] source : SOURCES) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM " + source[0] + " WHERE world=? "
                                + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? "
                                + "LIMIT 1")) {
                    ps.setString(1, world);
                    ps.setInt(2, minX);
                    ps.setInt(3, maxX);
                    ps.setInt(4, minY);
                    ps.setInt(5, maxY);
                    ps.setInt(6, minZ);
                    ps.setInt(7, maxZ);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return source[1];
                        }
                    }
                } catch (SQLException e) {
                    // A table this build does not have yet is not a reason to refuse the
                    // waypoint — the remaining sources still answer.
                    if (!String.valueOf(e.getMessage()).contains("no such table")) {
                        throw e;
                    }
                }
            }
        }
        return null;
    }
}
