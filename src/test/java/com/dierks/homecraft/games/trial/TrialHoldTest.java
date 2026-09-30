package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which moves Time Trials' 3-2-1 holds (final gate, #14): a step on foot during the countdown. The hold
 * itself is the framework's ({@code WorldSessions.hold}, armed as the session's own, so it is never a
 * void on the Go tick: HoldSessionTest), and Time Trials never changes a move's {@code to} itself.
 */
class TrialHoldTest {

    private static final UUID SAM = UUID.nameUUIDFromBytes("Sam".getBytes());

    private static Course boat() {
        Course p = DropperCourses.asParkour();
        return new Course("river", TrialKind.BOAT, "River", Tier.MEDIUM, "games", p.start(), p.checkpoints(),
                p.finish(), 44.0, 4, true, false, 1);
    }

    @Test
    void aStepOnFootDuringTheCountdownIsHeld() {
        TrialRun run = new TrialRun(SAM, DropperCourses.asParkour(), false, 60);
        assertTrue(TimeTrials.holds(run, true), "3-2-1: a step is held");
        assertFalse(TimeTrials.holds(run, false), "looking around goes ahead");
        run.phase = TrialRun.Phase.RUNNING;
        assertFalse(TimeTrials.holds(run, true), "after Go they run");
        assertFalse(TimeTrials.holds(null, true), "no run: nothing to hold");
    }

    @Test
    void aBoatIsHeldByItsSpeedNotByTheMove() {
        TrialRun run = new TrialRun(SAM, boat(), false, 60);
        assertFalse(TimeTrials.holds(run, true), "a boat on the grid: its speed is held, never the rider's move");
    }
}
