package com.dierks.homecraft.storage;

import com.dierks.homecraft.HomeCraftManagement;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite {@link Connection} and runs a tiny forward-only
 * migration framework so later phases (orders, minis, auctions) can extend the
 * schema without breaking existing installs.
 *
 * <p>SQLite is file-based (no server). At friends-scale, main-thread access is
 * fine; all DAO calls funnel through this one connection and synchronize on it.
 */
public final class Database {

    /**
     * Ordered DDL migrations. Index i (1-based) is schema version i. To evolve
     * the schema, APPEND a new statement block — never edit an existing one.
     */
    private static final String[] MIGRATIONS = {
            // v1 — placed custom-block registry (Phase 1).
            """
            CREATE TABLE IF NOT EXISTS placed_blocks (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                world      TEXT    NOT NULL,
                x          INTEGER NOT NULL,
                y          INTEGER NOT NULL,
                z          INTEGER NOT NULL,
                type       TEXT    NOT NULL,
                owner      TEXT    NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            );
            CREATE INDEX IF NOT EXISTS idx_placed_owner ON placed_blocks (owner);
            CREATE INDEX IF NOT EXISTS idx_placed_type  ON placed_blocks (type);
            """
    };

    private final HomeCraftManagement plugin;
    private Connection connection;

    public Database(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public Connection connection() {
        return connection;
    }

    public void connect() throws SQLException {
        try {
            // Explicit load: the plugin classloader won't always honour the
            // ServiceLoader auto-registration of the shaded driver.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Bundled SQLite JDBC driver not found on the classpath", e);
        }

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new SQLException("Could not create data folder: " + dir);
        }
        File dbFile = new File(dir, "homecraft.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        synchronized (connection) {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS hcm_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            }
            int current = schemaVersion();
            for (int v = current + 1; v <= MIGRATIONS.length; v++) {
                plugin.getLogger().info("Applying database migration v" + v + "…");
                try (Statement st = connection.createStatement()) {
                    for (String stmt : MIGRATIONS[v - 1].split(";")) {
                        if (!stmt.isBlank()) {
                            st.execute(stmt);
                        }
                    }
                }
                setSchemaVersion(v);
            }
        }
    }

    private int schemaVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM hcm_meta WHERE key = 'schema_version'")) {
            if (rs.next()) {
                try {
                    return Integer.parseInt(rs.getString(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }

    private void setSchemaVersion(int v) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO hcm_meta(key, value) VALUES('schema_version', ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, Integer.toString(v));
            ps.executeUpdate();
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing database: " + e.getMessage());
            }
            connection = null;
        }
    }
}
