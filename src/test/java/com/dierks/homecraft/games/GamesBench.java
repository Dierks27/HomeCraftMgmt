package com.dierks.homecraft.games;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link GamesKit}'s real framework for tests OUTSIDE this package (the integration tests in
 * {@code games.trial} that drive race mode, Race Night, party races and the Cup together): a
 * {@link GamesService} over a real in-memory database with a clock the test moves, and fake players
 * who remember what they were told. Everything the kit keeps package-private stays behind this.
 */
public final class GamesBench {

    private final GamesKit.Host host;
    private final List<GameSpec<?>> specs;
    private GamesService games;
    private final Map<UUID, GamesKit.Fake> players = new LinkedHashMap<>();

    /**
     * The framework at local time {@code now} (epoch ms, {@link #at}), with these games built from
     * their specs and {@code idThenSettings} as each game's settings (games open, Games world
     * {@code games}, economy world {@code world}).
     */
    public GamesBench(long now, List<GameSpec<?>> specs, Object... idThenSettings) {
        host = new GamesKit.Host(now);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), idThenSettings);
        this.specs = List.copyOf(specs);
        games = GamesKit.service(host, specs);
    }

    /** Epoch millis of a local date-time in the kit's zone. */
    public static long at(int year, int month, int day, int hour, int minute) {
        return GamesKit.at(year, month, day, hour, minute);
    }

    public GamesService games() {
        return games;
    }

    public Database db() {
        return host.db;
    }

    public GamesDao dao() {
        return host.dao;
    }

    public Connection connection() {
        return host.connection;
    }

    /** Now (epoch ms, the framework's clock). */
    public long now() {
        return host.time.now;
    }

    /** Move the framework's clock on. */
    public void move(long millis) {
        host.move(millis);
    }

    /** A player online in the economy world, in adventure mode. */
    public Player player(String name) {
        GamesKit.Fake f = new GamesKit.Fake(name);
        f.mode = org.bukkit.GameMode.ADVENTURE;
        players.put(f.id, f);
        host.online.put(f.id, f.player);
        return f.player;
    }

    /** Everything said to the player, colour codes stripped, one line each. */
    public String heard(UUID player) {
        GamesKit.Fake f = players.get(player);
        return f == null ? "" : f.heard();
    }

    public void give(UUID player, int tokens) {
        host.give(player, tokens);
    }

    public int balance(UUID player) {
        return host.balanceOf(player);
    }

    /**
     * Scheduled restarts at {@code times} (the kit's zone), each held {@code holdMinutes} before
     * (the restart hold: no new world games, warm-ups end). An empty list: none.
     */
    public void restarts(List<java.time.LocalTime> times, int holdMinutes) {
        com.dierks.homecraft.config.GamesConfig.Parsed c = host.config;
        host.config = new com.dierks.homecraft.config.GamesConfig.Parsed(c.common().withRestarts(times, holdMinutes),
                c.settings(), c.unreadable());
    }

    /**
     * {@code games.enabled} switched (the whole module on or off, as a config edit before a reload):
     * the framework reads it on every call, so the next {@code games().reload()} acts on it.
     */
    public void enabled(boolean on) {
        com.dierks.homecraft.config.GamesConfig.Parsed c = host.config;
        host.config = new com.dierks.homecraft.config.GamesConfig.Parsed(c.common().withEnabled(on), c.settings(),
                c.unreadable());
    }

    /**
     * A boot after a crash: a new framework (every game built again from its spec) over the same host,
     * database, clock and players. The old one is dropped with nothing run (no stop).
     */
    public GamesService reboot() {
        games = GamesKit.service(host, specs);
        return games;
    }

    /** Take a player offline (the host no longer finds them), or bring them back. */
    public void online(UUID player, boolean on) {
        GamesKit.Fake f = players.get(player);
        if (f == null) {
            return;
        }
        f.online = on;
        if (on) {
            host.online.put(player, f.player);
        } else {
            host.online.remove(player);
        }
    }

    /** Set a player's game mode (what {@code getGameMode} answers). */
    public void mode(UUID player, org.bukkit.GameMode mode) {
        GamesKit.Fake f = players.get(player);
        if (f != null) {
            f.mode = mode;
        }
    }

    /** Put a player in a world (what {@code getWorld} answers). */
    public void world(UUID player, String world) {
        GamesKit.Fake f = players.get(player);
        if (f != null) {
            f.world = GamesKit.world(world);
        }
    }

    /** The framework's scheduled tasks waiting (not run yet). */
    public int pendingTasks() {
        return host.tasks.size();
    }

    /** Every line the framework logged at SEVERE, for a failure message. */
    public String severeLines() {
        StringBuilder out = new StringBuilder();
        for (java.util.logging.LogRecord r : host.logs) {
            if (r.getLevel() == java.util.logging.Level.SEVERE) {
                out.append(r.getMessage()).append(r.getThrown() == null ? "" : " " + r.getThrown()).append('\n');
            }
        }
        return out.toString();
    }

    /** Run the framework's scheduled tasks that are due (all of them). */
    public void runTasks() {
        host.runTasks();
    }

    /** SEVERE lines logged so far (a game that threw). */
    public long severe() {
        return host.severe();
    }

    public void close() throws SQLException {
        host.connection.close();
    }
}
