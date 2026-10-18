package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import org.junit.jupiter.api.Test;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flight recorder's wiring into Time Trials (MOUNTAIN-V21-SPEC D17, D18), on the games framework with a real
 * database and no server (the repo has no harness that fires {@code VehicleMoveEvent} at {@code boatMoved}, so
 * the per-move hook's gate is pinned by {@link FlightRecorderTest#onlyAMountainRunOfAlgoFourOrLaterIsRecorded}).
 *
 * <p>Pinned here: {@code /hcm games boat flights} reads Time Trials' recorder, in plain words when nothing was
 * recorded and when Time Trials isn't there; a run's record ends once, as one INFO line naming the edition and
 * the course and never the player; and the line is then among the command's last runs. A solo warm-up's flights
 * are a line of their own, so the timed run's line holds the timed run's only (the early audit's X4). The
 * finish of a Mountain Run says its best jump in one quiet line under the time, and nothing when it has none;
 * a landing's puff of snow is 6 particles off a hop and 14 off a Big Drop, at most one a run every half a
 * second, never while the run is put back or moved or once it is over, and a puff that fails is said once.
 */
class TimeTrialsFlightsTest {

    @Test
    void theCommandReadsTimeTrialsRecorderAndARunsLineIsWrittenOnce() throws Exception {
        GamesBench bench = new GamesBench(GamesBench.at(2026, 10, 19, 20, 0), List.of(TimeTrials.SPEC), "trials",
                TimeTrialsSettings.defaults());
        Logger log = Logger.getLogger("HomeCraftManagement");
        List<LogRecord> said = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                said.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(h);
        try {
            List<String> none = TimeTrials.flightLines(bench.games());
            assertTrue(none.get(1).contains("Nothing recorded since the restart yet"), "plain words: " + none);

            TimeTrials t = (TimeTrials) bench.games().game(TimeTrials.SPEC.id());
            TrialRun run = new TrialRun(UUID.randomUUID(), MountainRunsV2.road(), true, 0);
            run.flight = new FlightRecorder().start(false, run.course);
            t.flightsEnd(run, true);
            assertNull(run.flight, "the record is closed");
            t.flightsEnd(run, false); // a session ending after the finish: nothing more
            List<LogRecord> lines = said.stream().filter(r -> r.getMessage().startsWith("Ice Boat flights")).toList();
            assertEquals(1, lines.size(), "one line a run: " + lines.stream().map(LogRecord::getMessage).toList());
            assertEquals(Level.INFO, lines.get(0).getLevel());
            assertEquals("Ice Boat flights (Java, road medium): 0 drops; 2-block: 0; 1-block: 0",
                    lines.get(0).getMessage(), "the edition and the course, never the player's name");
            assertTrue(TimeTrials.flightLines(bench.games()).contains("&f" + lines.get(0).getMessage()),
                    "the command shows it: " + TimeTrials.flightLines(bench.games()));

            TrialRun other = new TrialRun(UUID.randomUUID(), MountainRuns.medium(), false, 0);
            t.flightsEnd(other, false);
            assertEquals(1, said.stream().filter(r -> r.getMessage().startsWith("Ice Boat flights")).count(),
                    "a run with no record writes nothing");
        } finally {
            log.removeHandler(h);
            bench.close();
        }
        assertTrue(TimeTrials.flightLines(null).get(0).contains("Time Trials isn't running"));
    }

    /** What the plugin's logger says while {@code body} runs. */
    private static List<LogRecord> said(ThrowingRunnable body) throws Exception {
        Logger log = Logger.getLogger("HomeCraftManagement");
        List<LogRecord> said = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                said.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(h);
        try {
            body.run();
        } finally {
            log.removeHandler(h);
        }
        return said;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static List<String> flightLines(List<LogRecord> said) {
        return said.stream().map(LogRecord::getMessage).filter(m -> m.startsWith("Ice Boat flights")).toList();
    }

    /** A run on {@code c} with its clock running since {@code nanos}. */
    private static TrialRun riding(Course c, boolean test, long nanos) {
        TrialRun run = new TrialRun(UUID.randomUUID(), c, test, 0);
        run.phase = TrialRun.Phase.RUNNING;
        run.progress = new Progress(c, c.start().point(), nanos);
        return run;
    }

    @Test
    void aSoloWarmUpsFlightsAreALineOfTheirOwn() throws Exception {
        GamesBench bench = new GamesBench(GamesBench.at(2026, 10, 19, 20, 0), List.of(TimeTrials.SPEC), "trials",
                TimeTrialsSettings.defaults());
        try {
            TimeTrials t = (TimeTrials) bench.games().game(TimeTrials.SPEC.id());
            Course road = MountainRunsV2.road();
            TrialRun run = new TrialRun(UUID.randomUUID(), road, false, TimeTrials.COUNTDOWN_TICKS + 1);
            assertTrue(Warmup.begin(run, 1_000, 180, road.start().point(), 0L), "fixture: warming up");
            List<LogRecord> said = said(() -> {
                run.flight = new FlightRecorder().start(false, run.course); // its first move (TimeTrials.flight)
                FlightRecorderTest.Ride warm = new FlightRecorderTest.Ride(run.flight).flat(30, 2.0);
                warm.drop(2, 2.0, true);
                warm.flat(20, 1.2);
                t.warmups().over(run); // Warmups.end: the warm-up is over, back to the start for the 3-2-1
                assertNull(run.flight, "the warm-up's record is closed");
                assertFalse(run.warmup, "and the run waits for its 3-2-1");

                run.flight = new FlightRecorder().start(false, run.course); // the timed run's first move
                FlightRecorderTest.Ride timed = new FlightRecorderTest.Ride(run.flight).flat(30, 2.0);
                timed.drop(1, 2.0, true);
                timed.flat(10, 1.0);
                t.flightsEnd(run, true);
            });
            List<String> lines = flightLines(said);
            assertEquals(2, lines.size(), "the warm-up's line, then the timed run's: " + lines);
            assertTrue(lines.get(0).startsWith("Ice Boat flights (Java, road medium, not finished): 1 drop; "
                    + "2-block: 1,"), "the warm-up's own drop: " + lines.get(0));
            assertTrue(lines.get(1).startsWith("Ice Boat flights (Java, road medium): 1 drop; 2-block: 0; 1-block: 1,"),
                    "the timed run's line counts the timed run's one drop: " + lines.get(1));
        } finally {
            bench.close();
        }
    }

    @Test
    void theFinishSaysTheBestJumpQuietlyOnAMountainRunWithOne() throws Exception {
        FlightRecorder r = new FlightRecorder();
        FlightRecorder.Run run = r.start(false, "road", "medium");
        FlightRecorderTest.Ride ride = new FlightRecorderTest.Ride(run).flat(30, 2.0);
        double big = ride.drop(2, 2.0, true);
        ride.flat(10, 2.0);
        ride.drop(1, 2.0, true);
        ride.flat(10, 1.0);
        FlightRecorder.Summary s = r.close(run, true);
        assertEquals(big, s.best(), 1e-9, "the longest flight of either d");
        assertEquals("&7Best jump: &f" + String.format(java.util.Locale.ROOT, "%.1f", big) + " blocks",
                TimeTrials.bestJump(s), "rounded to a tenth");
        assertEquals("&7Best jump: &f13.4 blocks", TimeTrials.bestJump(s));
        assertNull(TimeTrials.bestJump(r.close(r.start(false, "road", "medium"), true)), "no flight, no line");
        assertNull(TimeTrials.bestJump(null), "no record (any other course), no line");

        // through the finish: the line under the time, on a Mountain Run v2 only
        GamesBench bench = new GamesBench(GamesBench.at(2026, 10, 19, 20, 0), List.of(TimeTrials.SPEC), "trials",
                TimeTrialsSettings.defaults());
        try {
            TimeTrials t = (TimeTrials) bench.games().game(TimeTrials.SPEC.id());
            assertEquals(List.of("Best jump: 13.4 blocks"), finishLines(t, MountainRunsV2.road(), true),
                    "a Mountain Run v2 with a jump: its best, under the time");
            assertEquals(List.of(), finishLines(t, MountainRunsV2.road(), false), "with none: no line");
            assertEquals(List.of(), finishLines(t, MountainRuns.medium(), true), "0.36's Mountain Run: no line");
        } finally {
            bench.close();
        }
    }

    /** A solo test run on {@code c} through Time Trials' finish (a 2-block drop on the way when {@code jump}). */
    private static List<String> finishLines(TimeTrials t, Course c, boolean jump) throws Exception {
        List<String> said = new ArrayList<>();
        UUID id = UUID.randomUUID();
        Player p = TrialFakes.player(id, "rider", said);
        long start = 7_000_000_000L;
        TrialRun run = riding(c, true, start);
        if (FlightRecorder.records(c)) {
            run.flight = new FlightRecorder().start(false, c);
            FlightRecorderTest.Ride ride = new FlightRecorderTest.Ride(run.flight).flat(30, 2.0);
            if (jump) {
                ride.drop(2, 2.0, true);
            }
            ride.flat(20, 1.2);
        }
        try {
            t.finish(p, run, start + 75_000_000_000L);
        } catch (NullPointerException noServer) {
            // the trip home is the last thing a finish does, on the server's scheduler: there is none in a test
            assertEquals("later", noServer.getStackTrace()[0].getMethodName(), "only the trip home: " + noServer);
        }
        assertTrue(said.get(0).startsWith(c.name() + ": "), "the time first: " + said);
        assertNull(run.flight, "the record closed at the finish");
        return said.stream().filter(l -> l.contains("jump")).toList();
    }

    @Test
    void aLandingPuffsSnowSizedByItsDropAtMostEveryHalfSecond() {
        assertEquals(6, TimeTrials.snowPuff(1), "a hop: about 6");
        assertEquals(14, TimeTrials.snowPuff(2), "a Big Drop: about 14");

        long now = 90_000_000_000L;
        TrialRun run = riding(MountainRunsV2.road(), false, 0);
        assertTrue(TimeTrials.snowDue(run, now), "a Mountain Run v2 riding: the first landing puffs");
        run.snowAt = now;
        assertFalse(TimeTrials.snowDue(run, now + TimeTrials.SNOW_EVERY - 1), "not within half a second");
        assertTrue(TimeTrials.snowDue(run, now + TimeTrials.SNOW_EVERY), "and again after it");
        run.snowAt = Long.MIN_VALUE / 2;

        run.suspended = true; // moved by someone, or its own reset on the way
        assertFalse(TimeTrials.snowDue(run, now), "none while the run is put back or moved");
        run.suspended = false;
        run.expect = new org.bukkit.Location(null, 0, 0, 0);
        assertFalse(TimeTrials.snowDue(run, now), "none while its own teleport is on the way");
        run.expect = null;
        run.backDue = true;
        assertFalse(TimeTrials.snowDue(run, now), "none with a reset due");
        run.backDue = false;
        run.phase = TrialRun.Phase.DONE;
        assertFalse(TimeTrials.snowDue(run, now), "none after the finish");
        run.phase = TrialRun.Phase.RUNNING;
        assertTrue(TimeTrials.snowDue(run, now), "fixture: riding again");

        assertFalse(TimeTrials.snowDue(riding(MountainRuns.medium(), false, 0), now), "not 0.36's Mountain Run");
        assertFalse(TimeTrials.snowDue(riding(MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.ROAD_SEED,
                MountainRunsV2.MODEL_MS, 3, 7)), false, 0), now), "not the algo-3 spiral");
        assertFalse(TimeTrials.snowDue(null, now));
    }

    @Test
    void aPuffThatFailsIsSaidOnceAndTheRunGoesOn() throws Exception {
        GamesBench bench = new GamesBench(GamesBench.at(2026, 10, 19, 20, 0), List.of(TimeTrials.SPEC), "trials",
                TimeTrialsSettings.defaults());
        try {
            TimeTrials t = (TimeTrials) bench.games().game(TimeTrials.SPEC.id());
            TrialRun a = riding(MountainRunsV2.road(), false, 0);
            TrialRun b = riding(MountainRunsV2.road(), false, 0);
            // no server in a test: finding the world throws, as a broken one would
            List<LogRecord> said = said(() -> {
                t.landingSnow(a, 6210.5, 168, 2900.5, 2);
                t.landingSnow(b, 6210.5, 168, 2900.5, 1);
                t.landingSnow(a, 6230.5, 167, 2900.5, 1); // within half a second: not even tried
            });
            List<LogRecord> warnings = said.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
            assertEquals(1, warnings.size(), "said once: " + said.stream().map(LogRecord::getMessage).toList());
            assertTrue(warnings.get(0).getMessage().startsWith("Ice Boat landing snow failed"),
                    warnings.get(0).getMessage());
            assertTrue(a.running() && b.running(), "the runs go on");
        } finally {
            bench.close();
        }
    }
}
