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
    /**
     * Mountain Run v2's quiet checkpoints: the clock line leaves the action bar alone until {@link #ticks}
     * reaches this, so the "Checkpoint 37/74 · 1:12.4" line can be read ({@code TimeTrials.QUIET_HOLD}).
     */
    int barHold;
    /**
     * The Ice Boat flight recorder's record of this run (MOUNTAIN-V21-SPEC D17, {@link FlightRecorder}): on a
     * Mountain Run v2, from the boat's first move with the clock running; {@code null} on any other course and
     * once the run's line has been written.
     */
    FlightRecorder.Run flight;
    /** When a landing last puffed snow ({@code System.nanoTime()}; MOUNTAIN-V21-SPEC D18): one a half second. */
    long snowAt = Long.MIN_VALUE / 2;

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
     * WP-CH: a rider sat in the back at some point in this run (latched when they board), so a rider
     * who hops out just before the line doesn't make a just-for-fun ride count (the Clubhouse review, #12).
     */
    boolean hadRider;
    /**
     * 0.38.3: the run was handed out from the Clubhouse ({@code ClubSolo}): Leave game partway goes back
     * there, not home.
     */
    boolean fromClub;
    /**
     * The server tick the warm-up ends at (then the player goes to the start and the normal 3-2-1
     * begins), or 0 for no time limit (the Dropper's practice drop ends on its first splash or bonk).
     */
    long warmupEnds;
    /** A race's shared warm-up: the racer tapped "Ready" ({@code RaceLink#ready}). */
    boolean warmupReady;

    // ---- the Dropper (EVENTS-DROPPER-SPEC §B.1.7; WP-D) ------------------------------------------

    /** A dropper run's own rules and state (its practice drop, splashes, hops, bonks), else {@code null}. */
    DropperRun drop;

    /** Race mode (EVENTS-DROPPER-SPEC §A.4.11): the racer's side of a race, or {@code null} for a normal run. */
    RaceRun race;

    /**
     * When this player's own "Go!" was ({@code System.nanoTime()}), or {@link Long#MIN_VALUE} before it. A solo
     * run's is its clock's start; a racer released after the shared Go (not in place yet, or put back on the
     * spot) starts on the shared clock, so theirs is later. Only a void's log line reads it ({@link #sinceGoMs}).
     */
    long goNanos = Long.MIN_VALUE;

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

    /**
     * Milliseconds since this player's own Go at {@code nanos} ({@link #goNanos}), for "at Go" or "3.2 s in" in a
     * void's log line: a racer released late already has time on the shared clock at their Go. The clock's time
     * when there has been no Go.
     */
    long sinceGoMs(long nanos) {
        return goNanos == Long.MIN_VALUE ? elapsedMs(nanos) : Math.max(0, (nanos - goNanos) / 1_000_000L);
    }
}
