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

    /** The live {@code games.fresh} settings (read on every call). */
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

    /**
     * Whether {@code /hcm play} already opens something called {@code id} — a game, one of its
     * other names, a course — so a kept course may not take it (GEN-SPEC-KEEP §4 step 1).
     */
    default boolean playIdTaken(String id) {
        return false;
    }

    /**
     * The named boxes that share Fresh Courses' world but are nobody's slot (EVENTS-DROPPER-SPEC
     * §B.3.2: the Falling Floors arena; CLUBHOUSE-SPEC §1: the Clubhouse's room; each as configured
     * now, {@code DailyCourses.extraBoxes}). A slot's region, or the keep area, within
     * {@value Regions#APART} blocks of one is refused ({@link Regions#extraProblem}), as the arena
     * and the Clubhouse refuse themselves near them: none is ever built into another.
     */
    default List<Regions.Extra> extras() {
        return List.of();
    }

    /**
     * Whether Race Night is switched on ({@code games.race_night.enabled}): while it is, a random Ice Boat
     * week is the Winding Road (MOUNTAIN-V2-SPEC §5.1, red-team F05; {@link StyleSeed#want}). Off in a host
     * that doesn't say.
     */
    default boolean raceNightOn() {
        return false;
    }

    /**
     * When this server process started, on {@link #now}'s clock (ENG-R3-00). An old area marked emptied at or after
     * it was emptied in this process: an engine started again in it ({@code /hcm reload}, a switch) waits for the
     * world to be saved, as the engine that emptied it did, and never lets it go on a look at chunks still in
     * memory. A host that can't tell says {@link Long#MAX_VALUE}: every mark is an earlier run's.
     */
    default long bootedAt() {
        return Long.MAX_VALUE;
    }

    /** A player's name for admins (history, records); the start of their id when unknown. */
    default String playerName(UUID player) {
        return player == null ? "someone" : player.toString().substring(0, 8);
    }
}
