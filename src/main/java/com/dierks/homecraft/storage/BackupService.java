package com.dierks.homecraft.storage;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SQLite backups (§11 #6): a file copy before any schema migration, a scheduled
 * backup through SQLite's online backup API (safe while the server is running),
 * pruning to {@code backups.keep}, and {@code /hcm backup now}. Every backup is
 * logged with its size and path. Files live in
 * {@code plugins/HomeCraftManagement/backups/hcm-<timestamp>[-label].db}.
 */
public final class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HomeCraftManagement plugin;
    private final Database database;
    private BukkitTask task;

    public BackupService(HomeCraftManagement plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public File backupDir() {
        File dir = new File(plugin.getDataFolder(), "backups");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create the backups folder: " + dir);
        }
        return dir;
    }

    /** (Re)arm the scheduled backup at {@code backups.interval_hours}. */
    public void start() {
        stop();
        PluginConfig.Backups cfg = plugin.config().backups();
        if (cfg == null || !cfg.enabled() || cfg.intervalHours() <= 0) {
            return;
        }
        long period = (long) (cfg.intervalHours() * 3600.0 * 20.0);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> backupNow("scheduled"),
                period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Copy the database file before a schema migration (plain file copy — the
     * connection has not written anything yet, so the file is consistent).
     */
    public static File preMigrationCopy(HomeCraftManagement plugin, File dbFile) {
        if (dbFile == null || !dbFile.exists()) {
            return null;
        }
        File dir = new File(plugin.getDataFolder(), "backups");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create the backups folder: " + dir);
            return null;
        }
        File out = new File(dir, "hcm-" + LocalDateTime.now().format(STAMP) + "-pre-migration.db");
        try {
            Files.copy(dbFile.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Database backup (pre-migration): " + out.getAbsolutePath()
                    + " (" + human(out.length()) + ")");
            return out;
        } catch (IOException e) {
            plugin.getLogger().warning("Pre-migration backup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Take a backup now through SQLite's online backup API ({@code backup to <file>}
     * in the bundled driver), then prune the oldest beyond {@code backups.keep}.
     *
     * @return the written file, or null on failure.
     */
    public File backupNow(String label) {
        Connection c = database.connection();
        if (c == null) {
            plugin.getLogger().warning("Backup skipped: database not connected.");
            return null;
        }
        String suffix = label == null || label.isBlank() ? "" : "-" + label.replaceAll("[^A-Za-z0-9_-]", "_");
        File out = new File(backupDir(), "hcm-" + LocalDateTime.now().format(STAMP) + suffix + ".db");
        synchronized (c) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("backup to '" + out.getAbsolutePath().replace("'", "''") + "'");
            } catch (SQLException e) {
                plugin.getLogger().warning("Database backup failed: " + e.getMessage());
                return null;
            }
        }
        plugin.getLogger().info("Database backup (" + (label == null ? "manual" : label) + "): "
                + out.getAbsolutePath() + " (" + human(out.length()) + ")");
        prune();
        return out;
    }

    /** Delete the oldest backups beyond {@code backups.keep} (pre-migration copies included). */
    public int prune() {
        PluginConfig.Backups cfg = plugin.config().backups();
        int keep = cfg == null ? 14 : cfg.keep();
        if (keep <= 0) {
            return 0;
        }
        File[] files = backupDir().listFiles((d, n) -> n.startsWith("hcm-") && n.endsWith(".db"));
        if (files == null || files.length <= keep) {
            return 0;
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        int removed = 0;
        for (int i = 0; i < sorted.size() - keep; i++) {
            File f = sorted.get(i);
            if (f.delete()) {
                removed++;
                plugin.getLogger().info("Pruned old backup: " + f.getName());
            }
        }
        return removed;
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
