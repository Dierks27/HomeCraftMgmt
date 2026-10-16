package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long before a night the track is held, on a Mountain Run v2 (audit M06), pure.
 *
 * <p>Pinned here: the hold (the last call: no new solo runs or party races on the track) comes 1 minute plus
 * ⌈2.25 T_m⌉ before the start, so a run started just before it, at 0.45 of model speed, is in before solo runs
 * end at T − 1 min, on a 105-135 s track (with the shipped 2 minutes it was cut off mid-mountain); never
 * under 2 minutes nor over the house's 15; a track with no T_m (hand-built, algo 2-3) keeps the 2-minute hold
 * exactly; the end of solo runs stays at T − 1 min; and the machine fires the last call at the track's own
 * hold, an admin's early start keeping it.
 */
class TrackHoldTest {

    private static final long MIN = 60_000L;
    private static final long T = 1_800_000_000_000L; // the start

    @Test
    void theHoldIsAMinutePlusAYoungRidersRunBeforeTheStart() {
        assertEquals(2 * MIN, EventMachine.reserveMs(0), "no T_m: the 2 minutes a short run always had");
        assertEquals(EventMachine.RESERVE_MS, EventMachine.reserveMs(0), "exactly");
        assertEquals(EventMachine.RESERVE_MS, EventMachine.reserveMs(BoatHype.modelMs(MountainRuns.medium())),
                "the algo-3 run has no T_m, so it keeps it");
        assertEquals(330_000, EventMachine.reserveMs(120_000), "1 min + 2.25 x 120 s = 5:30");
        assertEquals(357_000, EventMachine.reserveMs(132_000), "1 min + 2.25 x 132 s = 5:57");
        assertEquals(2 * MIN, EventMachine.reserveMs(20_000), "never under 2 minutes (1 min + 45 s)");
        assertEquals(15 * MIN, EventMachine.reserveMs(600_000), "never over the house's 15 minutes");
        assertEquals(60_000, EventMachine.SOLO_END_MS, "solo runs still end 1 minute before");
    }

    @Test
    void aSoloRunStartedJustBeforeTheHoldAt045IsInBeforeSoloRunsEnd() {
        for (long model : new long[]{90_000, 105_000, 120_000, 132_000, 135_000}) {
            long run = Math.round(model / 0.45);
            long startedAt = T - EventMachine.reserveMs(model) - 1; // the last moment a solo run may start
            long in = startedAt + run;
            assertTrue(in <= T - EventMachine.SOLO_END_MS, "T_m " + model + ": a 0.45 rider is in "
                    + (T - EventMachine.SOLO_END_MS - in) + " ms before solo runs end");
            assertTrue(T - EventMachine.RESERVE_MS - 1 + run > T - EventMachine.SOLO_END_MS,
                    "T_m " + model + ": with the old 2-minute hold she would have been cut off mid-mountain");
            assertTrue(in <= T - EventMachine.GRID_LEAD_MS, "and a joined racer on a practice run is in before the"
                    + " grid call, even with no warm-up");
        }
    }

    @Test
    void theMachineCallsTheLastCallAtTheTracksOwnHold() {
        EventPlan plan = new EventPlan("rn-20261009-1900", "fresh_boat", T - 10 * MIN, T,
                NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8, MountainRunsV2.MODEL_MS), false, "");
        EventMachine.Timing v2 = EventMachine.Timing.of(plan, 30, MountainRunsV2.MODEL_MS);
        assertEquals(330_000, v2.reserveMs(), "the plan's timings carry the track's hold");
        EventMachine.State open = EventMachine.State.open(T - 10 * MIN);
        assertEquals(List.of(), did(EventMachine.step(open, v2, at(T - 330_001))), "not a moment before 5:30");
        EventMachine.Step last = EventMachine.step(open, v2, at(T - 330_000));
        assertEquals(List.of(EventMachine.Do.LAST_CALL), did(last), "the last call and the hold at T - 5:30");
        assertEquals(List.of(), did(EventMachine.step(last.state(), v2, at(T - 2 * MIN))), "nothing more at T - 2");
        assertEquals(List.of(EventMachine.Do.END_SOLO_RUNS), did(EventMachine.step(last.state(), v2, at(T - MIN))),
                "solo runs end at T - 1, as before");

        EventMachine.Timing twoMinutes = EventMachine.Timing.of(plan, 30);
        assertEquals(EventMachine.RESERVE_MS, twoMinutes.reserveMs(), "a plan with no T_m: 2 minutes");
        assertEquals(List.of(), did(EventMachine.step(open, twoMinutes, at(T - 2 * MIN - 1))), "as before: not at 2:00.001");
        assertEquals(List.of(EventMachine.Do.LAST_CALL), did(EventMachine.step(open, twoMinutes, at(T - 2 * MIN))),
                "but at T - 2");
        EventMachine.Timing old = new EventMachine.Timing(T - 10 * MIN, T, 30 * MIN, 3, 2, 0, 60_000, 4 * MIN, 20_000);
        assertEquals(EventMachine.RESERVE_MS, old.reserveMs(), "the 9-number timings keep the 2-minute hold");

        EventMachine.Timing moved = v2.moved(T - 20 * MIN, T - 5 * MIN);
        assertEquals(330_000, moved.reserveMs(), "an admin's early start keeps the track's hold");
        assertEquals(T - 5 * MIN, moved.startsAt(), "with the moved start");
    }

    @Test
    void theLastCallSaysTheMinutesThereReallyAre() {
        assertEquals(EventCopy.lastCall(3), EventCopy.lastCall(3, 2), "the 2-minute last call reads as before");
        assertTrue(EventCopy.lastCall(3, 6).contains("starts in 6 minutes!"), EventCopy.lastCall(3, 6));
        assertTrue(EventCopy.lastCall(0, 1).contains("starts in 1 minute!"), EventCopy.lastCall(0, 1));
        assertEquals(2, EventCopy.minutes(2 * MIN - 250), "a step a moment late still says 2");
        assertEquals(6, EventCopy.minutes(330_000), "5:30 to the nearest minute");
        assertEquals(1, EventCopy.minutes(0), "never 0");
    }

    private static EventMachine.Facts at(long now) {
        return new EventMachine.Facts(now, 3, 0, 0, -1, false);
    }

    private static List<EventMachine.Do> did(EventMachine.Step s) {
        return s.actions().stream().map(EventMachine.Action::what).toList();
    }
}
