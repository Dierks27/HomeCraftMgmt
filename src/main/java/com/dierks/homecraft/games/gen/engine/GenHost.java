package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Everything {@link GenService} needs from the running server, in one place, so the engine's
 * rules — the schedule, the gate, still standing, the crash paths — run for real in tests with a
 * fake clock, fake worlds, fake players and a real in-memory database. The live host is
 * {@code DailyCourses}'; nothing here is asked off the main thread.
 */
public interface GenHost {

    /** Now (epoch ms): the plugin's clock. */
    long now();

    /** A monotonic clock for the tick budget ({@code System.nanoTime()}). */
    long nanoTime();

    Logger logger();

    /** The live {@code games.daily} settings (read on every call). */
    DailySettings settings();

    /** The scheduled restarts as configured now. */
    RestartHold restartHold();

    /** {@code clock.time_zone}. */
    ZoneId zone();

    /** {@code quests.week_starts_on}. */
    DayOfWeek weekStart();

    /** {@code games.worlds}. */
    List<String> gamesWorlds();

    /** The live {@code trials.fall_depth}. */
    int fallDepth();

    /** The world by name, or {@code null} when it isn't loaded. */
    WorldPort world(String name);

    GenStore store();

    /** Where plans are made (one low-priority thread; a test runs them in place). */
    Executor planner();

    /** Tell {@code gameId} ({@code trials} or {@code golf}) its courses changed. */
    void coursesChanged(String gameId);

    /** Everyone online. */
    List<Person> people();

    /** Whether anyone is online (the smaller build budget, and the planner's breathers). */
    boolean anyoneOnline();

    /** Paper's average tick time, in milliseconds. */
    double mspt();

    /** A chat line to a player ({@code &} codes). */
    void tell(UUID player, String line);

    /** An action-bar line to a player. */
    void actionBar(UUID player, String line);

    /** End the player's world session (their things come back; {@code EndReason.ADMIN}). */
    void endRun(UUID player);

    /** Teleport a player to (x, y, z) in {@code world}. */
    void move(UUID player, String world, double x, double y, double z);
}
