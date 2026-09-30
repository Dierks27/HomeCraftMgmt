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
    private final GamesService games;
    private final Map<UUID, GamesKit.Fake> players = new LinkedHashMap<>();

    /**
     * The framework at local time {@code now} (epoch ms, {@link #at}), with these games built from
     * their specs and {@code idThenSettings} as each game's settings (games open, Games world
     * {@code games}, economy world {@code world}).
     */
    public GamesBench(long now, List<GameSpec<?>> specs, Object... idThenSettings) {
        this(now, c -> c, specs, idThenSettings);
    }

    /**
     * {@link #GamesBench(long, List, Object...)} with the framework's database reached through
     * {@code wrap} (the real connection in, what the DAOs use out): a test that counts the statements
     * something asks. {@link #connection()} stays the real one.
     */
    public GamesBench(long now, java.util.function.UnaryOperator<Connection> wrap, List<GameSpec<?>> specs,
                      Object... idThenSettings) {
        host = new GamesKit.Host(now, wrap);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), idThenSettings);
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

    /** Run the framework's scheduled tasks that are due (all of them). */
    public void runTasks() {
        host.runTasks();
    }

    /** The framework's one-minute sweep, now (fx2-C #7). */
    public void sweep() {
        games.sweep();
    }

    /** SEVERE lines logged so far (a game that threw). */
    public long severe() {
        return host.severe();
    }

    public void close() throws SQLException {
        host.connection.close();
    }
}
