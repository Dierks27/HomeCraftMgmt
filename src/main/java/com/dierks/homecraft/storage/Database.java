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
            """,
            // v2 — dynamic market state: per-item current price + net demand (Phase 2).
            """
            CREATE TABLE IF NOT EXISTS market_state (
                item_id       TEXT    PRIMARY KEY,
                current_price REAL    NOT NULL,
                demand        INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            );
            """,
            // v3 — finite-stock revision (Phase 2.5): replace the abstract signed
            // 'demand' with real held 'stock'. Existing rows get stock = -1 (an
            // "unseeded" sentinel) so the service seeds them from config on load.
            """
            ALTER TABLE market_state ADD COLUMN stock INTEGER NOT NULL DEFAULT -1;
            ALTER TABLE market_state DROP COLUMN demand;
            """,
            // v4 — per-player daily sell tallies (anti-whale limit). day = UTC epoch-day.
            """
            CREATE TABLE IF NOT EXISTS market_daily_sells (
                player_uuid TEXT    NOT NULL,
                day         INTEGER NOT NULL,
                item_id     TEXT    NOT NULL,
                units       INTEGER NOT NULL,
                money       REAL    NOT NULL,
                PRIMARY KEY (player_uuid, day, item_id)
            );
            CREATE INDEX IF NOT EXISTS idx_daily_sells_day ON market_daily_sells (player_uuid, day);
            """,
            // v5 — periodic price/stock snapshots (feeds the Phase 5 dashboard charts).
            """
            CREATE TABLE IF NOT EXISTS market_price_history (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id   TEXT    NOT NULL,
                price     REAL    NOT NULL,
                stock     INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_price_history_item ON market_price_history (item_id, recorded_at);
            """,
            // v6 — Amazon orders + real-time shipping (Phase 3). deliver_at is an
            // absolute epoch-ms timestamp so deliveries survive restarts.
            """
            CREATE TABLE IF NOT EXISTS market_orders (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid   TEXT    NOT NULL,
                item_id       TEXT    NOT NULL,
                qty           INTEGER NOT NULL,
                item_cost     REAL    NOT NULL,
                shipping_cost REAL    NOT NULL,
                tier          TEXT    NOT NULL,
                placed_at     INTEGER NOT NULL,
                deliver_at    INTEGER NOT NULL,
                status        TEXT    NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_orders_player ON market_orders (player_uuid, status);
            CREATE INDEX IF NOT EXISTS idx_orders_status ON market_orders (status, deliver_at);
            """,
            // v7 — Minis collectibles (Phase 4): per-type mint tallies + per-copy
            // provenance. mint_counts.minted is the single source of truth for caps;
            // circulation = minted - destroyed.
            """
            CREATE TABLE IF NOT EXISTS mini_counts (
                mini_id   TEXT    PRIMARY KEY,
                minted    INTEGER NOT NULL DEFAULT 0,
                destroyed INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS mini_individuals (
                uid         TEXT    PRIMARY KEY,
                mini_id     TEXT    NOT NULL,
                mint_number INTEGER NOT NULL,
                owner       TEXT,
                minted_at   INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_mini_ind_type ON mini_individuals (mini_id);
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
