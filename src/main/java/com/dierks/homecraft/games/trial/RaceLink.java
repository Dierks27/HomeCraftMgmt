package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;

import java.util.UUID;

/**
 * The coordinator's side of a race run (EVENTS-DROPPER-SPEC §A.4.11; EVENTS-OWNER-DECISIONS D3, D4).
 *
 * <p>A race is a group of ordinary Time Trials runs in "race mode": each racer is seated by
 * {@code TimeTrials.race}, held until one shared release tick, and reports back here instead of
 * finishing like a solo run. Two coordinators implement it, so there is one race engine:
 * <ul>
 *   <li><b>Race Night</b> (WP-R2): the night's races. A finish is the night's own result and never a
 *       course run ({@link #normalRun()} false): no course board, weekly best, first clear, Fresh
 *       Courses clear or stars, because a grid start's time isn't comparable with a solo one.</li>
 *   <li><b>A party race</b> (WP-R1, D4): friends racing any time-trial course together. Each racer's
 *       finish is ALSO a normal counted run ({@link #normalRun()} true): it goes on the course's
 *       boards and earns the normal rewards and a Weekly Cup time exactly once, under every fair-play
 *       rule. There are no party prizes and no fees.</li>
 * </ul>
 * TimeTrials calls every method on the main thread, inside the games' guard, and never holds a
 * reference once the run ends. Nothing here may throw: a coordinator that fails is switched off by
 * the framework, and its runs end at their next tick through {@link #alive()}.
 *
 * <p>Ticks are server ticks ({@code Bukkit.getCurrentTick()}); nanos are {@code System.nanoTime()},
 * the clock {@link Progress} times crossings on.
 */
public interface RaceLink {

    /**
     * Whether the race still runs. False (the night was called off, {@code race_night} or the party
     * closed, the coordinator failed): TimeTrials ends the run at its next tick with
     * {@link #calledOffLine()}, and the racer goes home with their things.
     */
    boolean alive();

    /**
     * The shared release tick of the current race: every boat and runner is held still until it,
     * so every racer starts on the same tick. A racer seated after it (a slow chunk) starts at once,
     * on the shared clock.
     */
    long goTick();

    /**
     * Where a racer is, every 5 ticks while racing: how many targets (checkpoints, then the finish)
     * they have reached, the distance to the next one, and when they reached the last one. Live
     * positions only; the final order is by finish time.
     */
    void progress(UUID racer, int reachedTargets, double toNext, long nanos);

    /**
     * A racer crossed the line.
     *
     * @param raceMs     the time from the shared Go, from {@code Progress}'s interpolated crossing
     * @param counted    whether it counts (false: voided by a fair-play rule)
     * @param voidReason why it didn't count ("flying", ...), or {@code null} when it did
     */
    void finished(UUID racer, long raceMs, boolean counted, String voidReason);

    /** A racer's session ended before they finished (left, quit, kicked, the server stopping). */
    void left(UUID racer, EndReason why);

    /**
     * Whether a finish is also a normal counted course run (a party race, D4): TimeTrials then runs
     * its ordinary finish for it exactly once (boards, rewards, the Weekly Cup), on top of
     * {@link #finished}. Race Night: false.
     */
    default boolean normalRun() {
        return false;
    }

    /**
     * The server tick the shared warm-up ends (owner decision D3), or 0 for none: until then a racer
     * seated by {@code TimeTrials.race} runs free laps from the course's start, never timed, and
     * the action bar says "Warm-up 2:14 left - not counted". The coordinator re-grids everyone when
     * the window ends or everyone is {@link #ready}.
     */
    default long warmupUntil() {
        return 0L;
    }

    /** A racer tapped "Ready" during the shared warm-up (D3). */
    default void ready(UUID racer) {
    }

    /** What a racer reads when the race is called off under them ({@link #alive()} false). */
    default String calledOffLine() {
        return "&7Race Night was called off.";
    }
}
