package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flight recorder's wiring into Time Trials (MOUNTAIN-V21-SPEC D17), on the games framework with a real
 * database and no server (the repo has no harness that fires {@code VehicleMoveEvent} at {@code boatMoved}, so
 * the per-move hook's gate is pinned by {@link FlightRecorderTest#onlyAMountainRunOfAlgoFourOrLaterIsRecorded}).
 *
 * <p>Pinned here: {@code /hcm games boat flights} reads Time Trials' recorder, in plain words when nothing was
 * recorded and when Time Trials isn't there; a run's record ends once, as one INFO line naming the edition and
 * the course and never the player; and the line is then among the command's last runs.
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
}
