package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.EndReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The C1 contracts of race mode, warm-ups and the Dropper kind (EVENTS-DROPPER-SPEC §A.4.11, §B.1.7;
 * EVENTS-OWNER-DECISIONS D3, D4), pinned before anyone builds on them:
 * <ul>
 *   <li>a {@link RaceLink}'s defaults are Race Night's: not a normal run, no warm-up, and "Race Night
 *       was called off" when it ends;</li>
 *   <li>the race-mode entry points are built (WP-R1): a racer who isn't there is refused, never thrown
 *       at, and a course is held by one holder at a time;</li>
 *   <li>a run's warm-up is at most one, never timed, and a run that never warms up is as it was;</li>
 *   <li>{@link TrialKind#DROPPER} is Fresh Courses' own: never made by hand, 80 blocks a second, radius
 *       2.5, paid under "Dropper".</li>
 * </ul>
 */
class RaceModeContractTest {

    /** Race Night's link, with nothing but the required methods. */
    private static final class NightLink implements RaceLink {
        @Override
        public boolean alive() {
            return true;
        }

        @Override
        public long goTick() {
            return 100;
        }

        @Override
        public void progress(UUID racer, int reachedTargets, double toNext, long nanos) {
        }

        @Override
        public void finished(UUID racer, long raceMs, boolean counted, String voidReason) {
        }

        @Override
        public void left(UUID racer, EndReason why) {
        }
    }

    @Test
    void aRaceLinksDefaultsAreRaceNights() {
        RaceLink night = new NightLink();
        assertFalse(night.normalRun(), "a Race Night finish is the night's own, never a course run");
        assertEquals(0L, night.warmupUntil(), "no shared warm-up unless the coordinator has one");
        night.ready(UUID.randomUUID()); // a no-op, and never a throw
        assertEquals("&7Race Night was called off.", night.calledOffLine(), "what a racer reads when it ends");
    }

    @Test
    void theRaceModeEntryPointsAreBuiltAndNeverThrow() {
        TimeTrials trials = new TimeTrials(null);
        Course c = Course.create("river_run", TrialKind.BOAT, Tier.EASY);
        assertTrue(trials.race(null, c, c, null, null, new NightLink()) != null,
                "a racer who isn't here is refused, never thrown at");
        trials.regrid(null, c, null); // nobody to re-grid: nothing happens
        trials.park(null); // nobody to park: nothing happens
        Object night = new Object();
        Object party = new Object();
        assertTrue(trials.reserve("river_run", night, "&7Race Night is on this track."), "the first holder gets it");
        assertTrue(trials.reserve("RIVER_RUN", night, "&7Race Night is on this track."), "and may hold it again");
        assertFalse(trials.reserve("river_run", party, "&7A party race is on."), "a second holder is refused");
        trials.release("river_run", party); // only its own holder lets it go
        assertFalse(trials.reserve("river_run", party, null), "still held");
        trials.release("river_run", night);
        assertTrue(trials.reserve("river_run", party, null), "free once its holder let go");
        assertEquals("not built yet", TimeTrials.NOT_BUILT, "the contract's word is kept");
    }

    @Test
    void aRunGetsAtMostOneWarmUpAndItIsNeverTimed() {
        TrialRun run = new TrialRun(UUID.randomUUID(), Course.create("river_run", TrialKind.PARKOUR, Tier.EASY),
                false, 60);
        assertFalse(run.warmup || run.warmupUsed || run.warmupReady, "a new run is exactly the run it always was");
        assertTrue(run.timed(), "and times as always");
        assertTrue(run.beginWarmup(1_000 + 180 * 20), "Warm up (3:00)");
        assertTrue(run.warmup, "warming up");
        assertFalse(run.timed(), "never timed, submitted, paid or counted for the Cup");
        assertFalse(run.warmupOver(1_000), "not over at the start");
        assertTrue(run.warmupOver(1_000 + 180 * 20), "over when its time runs out");
        run.endWarmup();
        assertTrue(run.timed(), "Start timed run: the timed run counts as normal");
        assertFalse(run.beginWarmup(5_000), "a run gets one warm-up");
        assertFalse(run.warmup, "so it stays timed");

        TrialRun drop = new TrialRun(UUID.randomUUID(), Course.create("drop", TrialKind.DROPPER, Tier.EASY), false, 60);
        assertTrue(drop.beginWarmup(0), "the Dropper's practice drop has no time limit");
        assertFalse(drop.warmupOver(Long.MAX_VALUE), "it ends on its splash or bonk, not a clock");

        TrialRun going = new TrialRun(UUID.randomUUID(), Course.create("x", TrialKind.PARKOUR, Tier.EASY), false, 60);
        going.phase = TrialRun.Phase.RUNNING;
        assertFalse(going.beginWarmup(10), "no warm-up once the clock runs");
        assertFalse(going.warmupUsed, "and none is used up");
    }

    @Test
    void theDropperIsFreshCoursesOwnKind() {
        TrialKind d = TrialKind.DROPPER;
        assertEquals("dropper", d.id(), "stored as dropper");
        assertEquals("Dropper", d.label(), "read as Dropper");
        assertEquals(80, d.maxSpeed(), 1e-9, "terminal fall is 78.4 blocks a second");
        assertEquals(2.5, d.defaultRadius(), 1e-9, "a ledge mark's radius");
        assertEquals(TokenService.Source.GAMES_DROPPER, d.source(), "paid under Dropper");
        assertEquals(List.of("Step off the ledge.", "Steer through the holes.", "Land in the water to clear a level."),
                d.rules(), "§B.1.7's rules");
        assertEquals(d, TrialKind.of(" DROPPER "), "read by its word");
        assertFalse(d.handMade(), "never made by hand");
        assertEquals(List.of("parkour", "elytra", "boat"), TrialKind.handMadeIds(), "what create offers");
        assertTrue(TrialKind.ids().contains("dropper"), "a stored dropper row still reads");
        assertEquals("Droppers are made by Fresh Courses; keep one to make it permanent.",
                CourseAdmin.HAND_MADE_DROPPER, "what an admin reads for create <id> dropper");
        assertEquals(org.bukkit.Material.WATER_BUCKET, TimeTrials.icon(d), "its icon");
        for (TrialKind k : List.of(TrialKind.PARKOUR, TrialKind.ELYTRA, TrialKind.BOAT)) {
            assertTrue(k.handMade(), k + " is built by hand as before");
        }
    }
}
