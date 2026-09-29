package com.dierks.homecraft.games.trial;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's run, live (spec §11): the course as it was when the run started (the snapshot a
 * layout edit can't change), where the countdown is, the {@link Progress} once the clock runs,
 * and the few server things a run holds — its boat, the teleport it is waiting for.
 *
 * <p>A run may begin with one warm-up (owner decision D3): {@link #warmup} is set while it lasts,
 * and nothing done in it is ever recorded. The flags start false, so a run that never warms up is
 * exactly the run it always was.
 *
 * <p>Main thread only; {@link TimeTrials} owns every instance.
 */
final class TrialRun {

    /** Where a run is in its life. */
    enum Phase {
        /** Standing (or floating) at the start, frozen, counting 3-2-1. */
        COUNTDOWN,
        /** The clock is running. */
        RUNNING,
        /** Finished: waiting for the session to end. */
        DONE,
        /** Race mode: done with this race, waiting on the stand for the next one ({@link RaceRun}). */
        PARKED
    }

    final UUID player;
    final Course course;
    /** The course's {@link Course#layoutHash()} when the run started, to tell a re-made course apart. */
    final int layout;
    final boolean test;
    Phase phase = Phase.COUNTDOWN;
    /** Ticks until "Go!". */
    int countdown;
    /** Ticks since the run began, for the action bar's pace. */
    int ticks;
    /** Set when the clock starts. */
    Progress progress;
    /** Why the run won't count, once it has been seen breaking a rule; {@code null} while fine. */
    String voided;
    /** Moves are ignored until the run's own teleport lands (after someone else moved the player). */
    boolean suspended;
    /**
     * A move or a glide saw the run go wrong (a fall, a landing, getting out of the boat): it goes
     * back on the next tick, never from inside the event that saw it.
     */
    boolean backDue;
    /** The run's own teleport on its way: the destination, so it isn't taken for someone else's. */
    Location expect;
    /** The boat, on a boat course. */
    Entity boat;
    /** When the run was last sent back (server tick), so one fall or a held key is one reset. */
    long lastReset = Long.MIN_VALUE / 2;
    /** A re-seat in a boat is under way until this tick. */
    long reseatUntil;
    /** The server stalls seen while the clock ran: the speed check skips the legs they touch. */
    final List<FairPlay.Stall> stalls = new ArrayList<>();
    /** Sky Rings: the "open your wings" tip was shown (once per run, at the first fall-reset). */
    boolean wingsTip;

    // ---- the warm-up (owner decision D3; C1 contract, WP-R1 and WP-D play it) ------------------

    /**
     * The run is in its warm-up: free laps (or the Dropper's practice drop), checkpoints still guiding
     * and Back to checkpoint working, but NEVER timed for the record, submitted, paid or counted for
     * the Weekly Cup. The action bar says "Warm-up 2:14 left - not counted".
     */
    boolean warmup;
    /** The run has had its one warm-up: a run gets at most one ({@link #beginWarmup}). */
    boolean warmupUsed;
    /**
     * The server tick the warm-up ends at (then the player goes to the start and the normal 3-2-1
     * begins), or 0 for no time limit (the Dropper's practice drop ends on its first splash or bonk).
     */
    long warmupEnds;
    /** A race's shared warm-up: the racer tapped "Ready" ({@code RaceLink#ready}). */
    boolean warmupReady;

    /** Race mode (EVENTS-DROPPER-SPEC §A.4.11): the racer's side of a race, or {@code null} for a normal run. */
    RaceRun race;

    TrialRun(UUID player, Course course, boolean test, int countdown) {
        this.player = player;
        this.course = course;
        this.layout = course.layoutHash();
        this.test = test;
        this.countdown = countdown;
    }

    /** Whether the clock is running. */
    boolean running() {
        return phase == Phase.RUNNING && progress != null;
    }

    /**
     * Start the run's one warm-up, ending at server tick {@code endsAt} (0: no time limit). False,
     * and nothing changes, when the run already had one (at most one per run) or its clock is running.
     */
    boolean beginWarmup(long endsAt) {
        if (warmupUsed || phase != Phase.COUNTDOWN) {
            return false;
        }
        warmup = true;
        warmupUsed = true;
        warmupEnds = Math.max(0, endsAt);
        warmupReady = false;
        return true;
    }

    /** End the warm-up ("Start timed run", or its time ran out); the run can't have another. */
    void endWarmup() {
        warmup = false;
        warmupEnds = 0;
        warmupReady = false;
    }

    /** Whether the warm-up's time ran out at server tick {@code tick} (never for one without a limit). */
    boolean warmupOver(long tick) {
        return warmup && warmupEnds > 0 && tick >= warmupEnds;
    }

    /**
     * Whether a finish now may be recorded at all: not in a warm-up. (A test, a void and a stale
     * course are judged by {@code FairPlay.judge} as before.)
     */
    boolean timed() {
        return !warmup;
    }

    /** Milliseconds on the clock at {@code nanos}. */
    long elapsedMs(long nanos) {
        return progress == null ? 0 : Math.max(0, (nanos - progress.startNanos()) / 1_000_000L);
    }
}
