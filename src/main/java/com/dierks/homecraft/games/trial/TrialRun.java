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
        DONE
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

    /** Milliseconds on the clock at {@code nanos}. */
    long elapsedMs(long nanos) {
        return progress == null ? 0 : Math.max(0, (nanos - progress.startNanos()) / 1_000_000L);
    }
}
