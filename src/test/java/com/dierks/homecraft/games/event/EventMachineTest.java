package com.dierks.homecraft.games.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's timeline ({@link EventMachine}, EVENTS-DROPPER-SPEC §A.2, §A.4, §A.9; owner decision
 * D3), stepped with a fake clock and no server.
 *
 * <p>Pinned here: the heads-up, the window, the last call with the reserve and the solo-run end at
 * their times; too few racers call the night off at the grid call; seats are retried until just
 * before Go, and too few seated at Go call it off; race, break, race, SETTLING; each way a race
 * ends (everyone in, the finish window, the time limit); the shared warm-up ends at its window or
 * when everyone is ready; fewer than 2 left after a race ends the night early; a reload or stop
 * while running settles on the races done; and a boot resumes an OPEN night only when its start is
 * at least 2 minutes away.
 */
class EventMachineTest {

    private static final long MIN = 60_000L;
    private static final long T = 1_000_000_000_000L; // the start

    private static EventMachine.Timing timing(long warmupMs) {
        return new EventMachine.Timing(T - 10 * MIN, T, 30 * MIN, 3, 2, warmupMs, 60_000, 4 * MIN, 20_000);
    }

    private static EventMachine.Facts facts(long now, int joined, int seated, int racing, long firstFinishAt,
                                            boolean allReady) {
        return new EventMachine.Facts(now, joined, seated, racing, firstFinishAt, allReady);
    }

    private static List<EventMachine.Do> did(EventMachine.Step s) {
        return s.actions().stream().map(EventMachine.Action::what).toList();
    }

    @Test
    void theHeadsUpTheWindowTheLastCallAndTheSoloEndHappenOnTime() {
        EventMachine.Timing t = timing(0);
        EventMachine.State s = EventMachine.State.scheduled();
        EventMachine.Step early = EventMachine.step(s, t, facts(T - 40 * MIN, 0, 0, 0, -1, false));
        assertTrue(early.actions().isEmpty(), "40 minutes out: nothing yet");
        EventMachine.Step heads = EventMachine.step(s, t, facts(T - 30 * MIN, 0, 0, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.HEADS_UP), did(heads), "T - 30: the heads-up");
        EventMachine.Step again = EventMachine.step(heads.state(), t, facts(T - 29 * MIN, 0, 0, 0, -1, false));
        assertTrue(again.actions().isEmpty(), "said once");
        EventMachine.Step open = EventMachine.step(again.state(), t, facts(T - 10 * MIN, 0, 0, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.OPEN), did(open), "T - 10: the window opens");
        assertEquals(EventMachine.Phase.OPEN, open.state().phase(), "OPEN");
        EventMachine.Step last = EventMachine.step(open.state(), t, facts(T - 2 * MIN, 3, 0, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.LAST_CALL), did(last), "T - 2: the last call, and the track is reserved");
        EventMachine.Step solo = EventMachine.step(last.state(), t, facts(T - MIN, 3, 0, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.END_SOLO_RUNS), did(solo), "T - 1: solo runs still on the track end");
        EventMachine.Step seat = EventMachine.step(solo.state(), t, facts(T - 15_000, 3, 0, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.SEAT), did(seat), "T - 15 s: racers are taken to the track");
        assertEquals(EventMachine.Phase.GRID, seat.state().phase(), "onto the grid (no warm-up)");
        assertEquals(T, seat.state().goAt(), "Go is the announced start");
    }

    @Test
    void openThenTooFewThenCalledOff() {
        EventMachine.Step s = EventMachine.step(EventMachine.State.open(T - 10 * MIN), timing(0),
                facts(T - 15_000, 1, 0, 0, -1, false));
        assertEquals(EventMachine.Phase.CALLED_OFF, s.state().phase(), "one racer at the grid call: called off");
        assertEquals(EventMachine.Do.CALL_OFF, s.actions().get(s.actions().size() - 1).what(), "with a CALL_OFF");
        assertTrue(s.actions().get(s.actions().size() - 1).why().startsWith("too few"), "saying why");
    }

    @Test
    void gridRetriesUntilJustBeforeGoThenTooFewSeatedCallsItOff() {
        EventMachine.Timing t = timing(0);
        EventMachine.State grid = new EventMachine.State(EventMachine.Phase.GRID, 1, T - 15_000, T, 0, 7);
        assertEquals(List.of(EventMachine.Do.RETRY_SEATS), did(EventMachine.step(grid, t,
                facts(T - 10_000, 3, 1, 0, -1, false))), "10 s to Go: the unseated are tried again");
        assertTrue(EventMachine.step(grid, t, facts(T - 2_000, 3, 1, 0, -1, false)).actions().isEmpty(),
                "2 s to Go: no more retries");
        EventMachine.Step go = EventMachine.step(grid, t, facts(T, 3, 1, 0, -1, false));
        assertEquals(EventMachine.Phase.CALLED_OFF, go.state().phase(),
                "only 1 seated at Go (min 2): called off; the unseated were DNS");
        EventMachine.Step ok = EventMachine.step(grid, t, facts(T, 3, 2, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.GO), did(ok), "2 seated: Go");
        assertEquals(EventMachine.Phase.RACING, ok.state().phase(), "RACING");
    }

    @Test
    void raceBreakRaceThenSettlingThenDone() {
        EventMachine.Timing t = timing(0);
        EventMachine.State s = new EventMachine.State(EventMachine.Phase.RACING, 1, T, T, 0, 7);
        for (int race = 1; race <= 3; race++) {
            long now = s.goAt() + 50_000;
            EventMachine.Step end = EventMachine.step(s, t, facts(now, 3, 3, 0, now - 1000, false));
            assertEquals(EventMachine.Do.END_RACE, end.actions().get(0).what(), "race " + race + " ends: everyone is in");
            assertEquals(race, end.actions().get(0).race(), "the right race");
            if (race == 3) {
                assertEquals(EventMachine.Do.SETTLE, end.actions().get(1).what(), "after the last race: settle");
                assertEquals(EventMachine.Phase.SETTLING, end.state().phase(), "SETTLING");
                assertTrue(EventMachine.step(end.state(), t, facts(now + 1000, 3, 3, 0, -1, false)).actions()
                        .isEmpty(), "SETTLING waits for the runner's pay loop");
                EventMachine.State done = end.state().ended(EventMachine.Phase.DONE, now + 2000);
                assertTrue(done.phase().over(), "DONE is over");
                assertEquals("DONE", done.phase().stored(), "and stored as DONE");
                return;
            }
            assertEquals(EventMachine.Phase.BREAK, end.state().phase(), "a break");
            assertTrue(EventMachine.step(end.state(), t, facts(now + 10_000, 3, 3, 3, -1, false)).actions().isEmpty(),
                    "10 s into the 20 s break: nothing");
            EventMachine.Step grid = EventMachine.step(end.state(), t, facts(now + 20_000, 3, 3, 3, -1, false));
            assertEquals(List.of(EventMachine.Do.GRID), did(grid), "after the break: the next grid");
            assertEquals(race + 1, grid.actions().get(0).race(), "for the next race");
            assertEquals(now + 20_000 + EventMachine.COUNTDOWN_MS, grid.state().goAt(), "a 5-second countdown");
            EventMachine.Step go = EventMachine.step(grid.state(), t, facts(grid.state().goAt(), 3, 3, 3, -1, false));
            assertEquals(List.of(EventMachine.Do.GO), did(go), "Go");
            s = go.state();
        }
    }

    @Test
    void aRaceEndsAtTheFinishWindowOrTheTimeLimit() {
        EventMachine.Timing t = timing(0);
        EventMachine.State racing = new EventMachine.State(EventMachine.Phase.RACING, 1, T, T, 0, 7);
        assertTrue(EventMachine.step(racing, t, facts(T + 100_000, 3, 3, 2, T + 50_000, false)).actions().isEmpty(),
                "50 s after the first finisher, two still going: not yet");
        EventMachine.Step window = EventMachine.step(racing, t, facts(T + 110_000, 3, 3, 2, T + 50_000, false));
        assertEquals(EventMachine.Do.END_RACE, window.actions().get(0).what(), "60 s after the first finisher: over");
        EventMachine.Step limit = EventMachine.step(racing, t, facts(T + 4 * MIN, 3, 3, 3, -1, false));
        assertEquals(EventMachine.Do.END_RACE, limit.actions().get(0).what(), "4 minutes and nobody in: over");
    }

    @Test
    void theWarmUpEndsAtItsWindowOrWhenEveryoneIsReady() {
        EventMachine.Timing t = timing(3 * MIN);
        EventMachine.Step seat = EventMachine.step(EventMachine.State.open(T - 10 * MIN), t,
                facts(T - 15_000, 3, 0, 0, -1, false));
        assertEquals(EventMachine.Phase.WARMUP, seat.state().phase(), "with warm_up_seconds, racers start on free laps");
        long ends = seat.state().warmupEnds();
        assertEquals(T - 15_000 + 3 * MIN, ends, "for 3 minutes");
        assertEquals(List.of(EventMachine.Do.RETRY_SEATS), did(EventMachine.step(seat.state(), t,
                facts(T, 3, 2, 0, -1, false))), "during it, the unseated are tried again");
        assertEquals(List.of(EventMachine.Do.RETRY_SEATS), did(EventMachine.step(seat.state(), t,
                facts(T + 30_000, 3, 3, 0, -1, false))), "not everyone ready: the grid waits");
        EventMachine.Step ready = EventMachine.step(seat.state(), t, facts(T + 30_000, 3, 3, 0, -1, true));
        assertEquals(List.of(EventMachine.Do.GRID), did(ready), "everyone ready: to the grid at once");
        assertEquals(1, ready.actions().get(0).race(), "for race 1");
        EventMachine.Step window = EventMachine.step(seat.state(), t, facts(ends, 3, 3, 0, -1, false));
        assertEquals(List.of(EventMachine.Do.GRID), did(window), "or when the window runs out");
        assertEquals(EventMachine.Phase.GRID, window.state().phase(), "GRID");
    }

    @Test
    void fewerThanTwoLeftAfterARaceEndsTheNightEarly() {
        EventMachine.State brk = new EventMachine.State(EventMachine.Phase.BREAK, 1, T + MIN, T, 0, 7);
        EventMachine.Step s = EventMachine.step(brk, timing(0), facts(T + MIN + 20_000, 1, 1, 0, -1, false));
        assertEquals(EventMachine.Do.SETTLE, s.actions().get(0).what(), "one racer left: settle on what was raced");
        assertEquals(EventMachine.Phase.SETTLING, s.state().phase(), "SETTLING");
    }

    @Test
    void aReloadOrStopWhileRunningSettlesOnTheRacesDone() {
        assertEquals(EventMachine.Boot.CALL_OFF_SETTLE, EventMachine.stop(EventMachine.Phase.BREAK, 1),
                "a stop after race 1: called off, race 1's points stand");
        assertEquals(EventMachine.Boot.CALL_OFF_SETTLE, EventMachine.stop(EventMachine.Phase.RACING, 2),
                "mid race 3: races 1 and 2 stand (race 3 is lost)");
        assertEquals(EventMachine.Boot.CALL_OFF, EventMachine.stop(EventMachine.Phase.RACING, 0),
                "mid race 1: nothing to settle");
        assertEquals(EventMachine.Boot.FINISH_PAYING, EventMachine.stop(EventMachine.Phase.SETTLING, 3),
                "while paying: finish paying");
        assertEquals(EventMachine.Boot.NOTHING, EventMachine.stop(EventMachine.Phase.DONE, 3), "over: nothing");
        assertEquals(EventMachine.Boot.CALL_OFF_SETTLE, EventMachine.boot("RUNNING", T, 1, T + 10 * MIN),
                "a crash mid-night: the boot does the same");
        assertEquals(EventMachine.Boot.FINISH_PAYING, EventMachine.boot("SETTLING", T, 3, T + 10 * MIN),
                "a crash while paying: the pay loop finishes");
    }

    @Test
    void bootResumesAnOpenNightOnlyWhenItsStartIsAtLeastTwoMinutesAway() {
        assertEquals(EventMachine.Boot.RESUME, EventMachine.boot("OPEN", T, 0, T - 5 * MIN),
                "5 minutes to go: it resumes with its sign-ups");
        assertEquals(EventMachine.Boot.RESUME, EventMachine.boot("OPEN", T, 0, T - 2 * MIN), "exactly 2: resumes");
        assertEquals(EventMachine.Boot.CALL_OFF, EventMachine.boot("OPEN", T, 0, T - MIN),
                "1 minute to go: called off (a queued notice to entrants)");
        assertEquals(EventMachine.Boot.NOTHING, EventMachine.boot("DONE", T, 3, T), "a done night stays done");
        assertEquals(EventMachine.Boot.NOTHING, EventMachine.boot(null, T, 0, T), "no row: nothing");
    }

    @Test
    void storedStatesAreTheSpecsFive() {
        for (EventMachine.Phase p : EventMachine.Phase.values()) {
            assertTrue(List.of("OPEN", "RUNNING", "SETTLING", "DONE", "CALLED_OFF").contains(p.stored()),
                    p + " is stored as one of the five states");
        }
        assertTrue(EventMachine.Phase.BREAK.running(), "a break is part of the running night");
        assertFalse(EventMachine.Phase.OPEN.running(), "the join window isn't");
    }
}
