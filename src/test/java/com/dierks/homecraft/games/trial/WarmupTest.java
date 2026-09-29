package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.util.Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warm-ups before a counted run (owner decision D3): starting a course offers "Warm up (3:00)" or
 * "Go straight to the timed run"; a warm-up lap is never timed, recorded, paid or counted for the
 * Weekly Cup (its finish never reaches the normal finish, where all of that happens); "Start timed
 * run" or the clock ends it, and the run that follows is a normal timed run from the 3-2-1; going
 * straight is the run as it always was; and a run gets at most one warm-up.
 */
class WarmupTest {

    private static final Course COURSE = LapsTest.straight();

    private static TrialRun newRun() {
        return new TrialRun(UUID.randomUUID(), COURSE, false, TimeTrials.COUNTDOWN_TICKS + 1);
    }

    private static TimeTrialsSettings withWarmup(int seconds) {
        TimeTrialsSettings d = TimeTrialsSettings.defaults();
        return new TimeTrialsSettings(d.enabled(), Map.copyOf(d.firstClear()), d.weeklyBestBonus(),
                d.courseOfWeekBonus(), d.dailyCap(), d.fallDepth(), d.minSeconds(), seconds, d.partyMax());
    }

    @Test
    void theChoiceIsOfferedWhileWarmUpsAreOnInItemNames() {
        TimeTrialsSettings shipped = TimeTrialsSettings.defaults();
        assertEquals(180, shipped.warmupSeconds(), "games.trials.warmup_seconds ships at 180");
        assertTrue(Warmup.offered(shipped, false, TrialKind.PARKOUR), "a parkour course offers the choice");
        assertTrue(Warmup.offered(shipped, false, TrialKind.BOAT), "so does a boat course");
        assertTrue(Warmup.offered(shipped, false, TrialKind.ELYTRA), "and an elytra course");
        assertFalse(Warmup.offered(withWarmup(0), false, TrialKind.PARKOUR), "0 turns warm-ups off: straight in");
        assertFalse(Warmup.offered(shipped, true, TrialKind.PARKOUR), "an admin's test run never warms up");
        assertFalse(Warmup.offered(shipped, false, TrialKind.DROPPER), "the Dropper has its own practice drop");
        assertFalse(Warmup.offered(null, false, TrialKind.PARKOUR), "no settings, no choice");

        assertEquals("Warm up (3:00)", Warmup.choice(180), "the first button's words");
        assertEquals("Warm up (1:30)", Warmup.choice(90), "its length follows the setting");
        assertEquals("Go straight to the timed run", Warmup.STRAIGHT, "the second button's words");
        assertEquals("Start timed run - ends the warm-up", Text.plain(Warmup.TIMED_NAME),
                "the kit item's NAME says what it does (Bedrock reads names)");
        assertEquals("Warm-up 2:14 left - not counted", Text.plain(Warmup.bar(134)), "the action bar");
        assertEquals("Warm-up 0:05 left - not counted", Text.plain(Warmup.bar(5)), "seconds padded");
    }

    @Test
    void aWarmUpLapIsNeverTimedRecordedPaidOrCounted() {
        TrialRun run = newRun();
        assertTrue(Warmup.begin(run, 1_000, 180, COURSE.start().point(), 7_000), "Warm up (3:00)");
        assertTrue(run.warmup, "warming up");
        assertEquals(TrialRun.Phase.RUNNING, run.phase, "free laps from the start at once");
        assertEquals(1_000 + 180 * 20, run.warmupEnds, "three minutes of ticks");
        assertFalse(run.timed(), "nothing in it is timed for the record");
        assertEquals(RaceRun.Route.WARMUP_LAP, RaceRun.route(run),
                "its finish is a warm-up lap: the normal finish (boards, rewards, the Cup, stars) is never reached");

        run.voided = null;
        Warmup.lapDone(run, 9_000);
        assertEquals(0, run.progress.reachedTargets(), "round again: a fresh lap");
        assertEquals(9_000, run.progress.startNanos(), "from now");
        assertEquals(COURSE.start().point(), run.progress.last(), "from the start");
        assertTrue(run.suspended, "nothing counts until the player is back at the start");
        assertTrue(1_010 - run.lastReset >= TimeTrials.SUSPEND_TICKS, "so the next tick sends them there");
        assertTrue(run.warmup, "still warming up");
        assertEquals(RaceRun.Route.WARMUP_LAP, RaceRun.route(run), "every lap of it");

        TrialRun racing = newRun();
        racing.race = new RaceRun(new TrialFakes.Link(), COURSE, COURSE.start(), null, true);
        racing.beginWarmup(5_000);
        assertEquals(RaceRun.Route.WARMUP_LAP, RaceRun.route(racing),
                "a race's shared warm-up lap is never reported to the race either");
    }

    @Test
    void theTimedRunAfterAWarmUpCountsNormally() {
        TrialRun run = newRun();
        Warmup.begin(run, 1_000, 180, COURSE.start().point(), 7_000);
        assertFalse(run.warmupOver(1_000 + 180 * 20 - 1), "not over a tick early");
        assertTrue(run.warmupOver(1_000 + 180 * 20), "over at 0:00");
        run.backDue = true;
        Warmup.toCountdown(run);
        assertFalse(run.warmup, "the warm-up is over");
        assertTrue(run.timed(), "the run is timed");
        assertEquals(TrialRun.Phase.COUNTDOWN, run.phase, "back to the start for the normal 3-2-1");
        assertEquals(TimeTrials.COUNTDOWN_TICKS + 1, run.countdown, "the full countdown");
        assertNull(run.progress, "the clock starts at Go, not before");
        assertFalse(run.backDue || run.suspended, "nothing left over from the warm-up");
        assertTrue(run.stalls.isEmpty(), "no stalls carried into the timed run");
        assertEquals(RaceRun.Route.SOLO, RaceRun.route(run), "its finish is the normal finish: judged, recorded, paid");
    }

    @Test
    void goingStraightIsTheRunAsItAlwaysWas() {
        TrialRun run = newRun();
        assertFalse(run.warmup || run.warmupUsed, "no warm-up chosen");
        assertEquals(TrialRun.Phase.COUNTDOWN, run.phase, "the 3-2-1 at the start");
        assertTrue(run.timed(), "timed");
        assertEquals(RaceRun.Route.SOLO, RaceRun.route(run), "and counted as ever");
        assertFalse(Warmup.begin(newRun(), 1_000, 0, COURSE.start().point(), 0), "0 seconds: no warm-up at all");
    }

    @Test
    void aRunGetsAtMostOneWarmUp() {
        TrialRun run = newRun();
        assertTrue(Warmup.begin(run, 1_000, 180, COURSE.start().point(), 0), "the first");
        Warmup.toCountdown(run);
        assertFalse(Warmup.begin(run, 5_000, 180, COURSE.start().point(), 0), "a second one is refused");
        assertFalse(run.warmup, "so the run stays timed");
        TrialRun going = newRun();
        going.phase = TrialRun.Phase.RUNNING;
        assertFalse(Warmup.begin(going, 1, 180, COURSE.start().point(), 0), "and none once the clock runs");
        assertFalse(going.warmupUsed, "which uses nothing up");
    }

    @Test
    void theClockReadsInMinutesAndSeconds() {
        assertEquals("3:00", Warmup.clock(180), "three minutes");
        assertEquals("0:45", Warmup.clock(45), "under a minute");
        assertEquals("0:00", Warmup.clock(-3), "never negative");
        assertEquals(3, Warmup.secondsLeft(100, 141), "41 ticks left read as 3 seconds (rounded up)");
        assertEquals(0, Warmup.secondsLeft(200, 141), "none once past");
        assertEquals(1_000 + 3_600, Warmup.endsAt(1_000, 180), "180 seconds of ticks");
    }

    // ---- the fixes (EV fix stage, R1 review) --------------------------------------------------------

    @Test
    void aWarmUpChoiceHoldsOnlyForTheCourseItWasMadeOn() {
        TimeTrialsSettings on = withWarmup(180);
        TrialRun here = newRun();
        assertTrue(Warmups.wants(COURSE.id(), here, on), "chosen on this course: it warms up");
        assertTrue(Warmups.wants(COURSE.id().toUpperCase(java.util.Locale.ROOT), here, on), "whatever the case");
        assertFalse(Warmups.wants("another", here, on), "a choice left over from another course never carries over");
        assertFalse(Warmups.wants(null, here, on), "no choice: no warm-up");
        TrialRun drop = new TrialRun(UUID.randomUUID(), new Course("drop", TrialKind.DROPPER, "Drop", Tier.EASY, "games",
                COURSE.start(), List.of(), COURSE.finish(), null, 30, true, false, 1), false, 0);
        assertFalse(Warmups.wants("drop", drop, on), "never on a Dropper (its practice drop plays that part)");
        TrialRun test = new TrialRun(UUID.randomUUID(), COURSE, true, 0);
        assertFalse(Warmups.wants(COURSE.id(), test, on), "never on an admin's test run");
        assertFalse(Warmups.wants(COURSE.id(), here, withWarmup(0)), "never with warm-ups switched off");
    }

    @Test
    void aSoloWarmUpEndsWhenItsTimeIsUpOrARestartIsDueSoon() {
        TrialRun run = newRun();
        assertTrue(Warmup.begin(run, 1_000, 180, COURSE.start().point(), 5_000), "warming up");
        assertFalse(Warmup.over(run, 1_100, false), "time left, no restart: warming up");
        assertTrue(Warmup.over(run, 1_100, true), "the restart hold: over now, so the counted run still happens");
        assertTrue(Warmup.over(run, Warmup.endsAt(1_000, 180), false), "or when its time is up");
        assertTrue(Warmup.endedForRestart("4:00 PM").contains("4:00 PM"), "and the player hears why");
        Warmup.toCountdown(run);
        assertFalse(Warmup.over(run, 1_100, true), "a run no longer warming up has nothing to end");
    }
}
