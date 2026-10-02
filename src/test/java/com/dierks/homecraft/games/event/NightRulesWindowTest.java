package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's windows scale with a Mountain Run v2 (red-team F03, MOUNTAIN-V2-SPEC D8), pure.
 *
 * <p>Pinned here: on a track of model time T_m the finish window is max(config, ⌈1.25 T_m⌉) and the longest
 * race max(config, ⌈3 T_m / 60⌉) minutes, never over 15; a party race's window is max(120, ⌈1.25 T_m⌉); a
 * track with no T_m (the algo-3 run, a hand-built track) keeps config's values exactly; config itself is
 * never lowered; the night stores the effective values (they come back from its row); its longest night
 * grows with them (the restart fit); and, the point of it all, a young racer at 0.45 of model speed behind a
 * leader at 0.85 still finishes inside the window on a 105, 120 or 135 s track, where config's 60 s window
 * would have sent her to the stand mid-run.
 */
class NightRulesWindowTest {

    private static final RaceNightSettings SHIPPED = RaceNightSettings.defaults();

    @Test
    void theWindowsGrowWithTheTracksModelTime() {
        assertEquals(60, SHIPPED.finishWindowSeconds(), "fixture: the shipped finish window");
        assertEquals(4, SHIPPED.maxRaceMinutes(), "fixture: the shipped longest race");
        assertEquals(150, NightRules.finishWindow(60, 120_000), "1.25 x 120 s");
        assertEquals(132, NightRules.finishWindow(60, 105_000), "1.25 x 105 s = 131.25, rounded up");
        assertEquals(169, NightRules.finishWindow(60, 135_000), "1.25 x 135 s = 168.75, rounded up");
        assertEquals(200, NightRules.finishWindow(200, 120_000), "config wins when it is longer: never lowered");
        assertEquals(6, NightRules.maxRace(4, 120_000), "3 x 120 s = 6 minutes");
        assertEquals(7, NightRules.maxRace(4, 135_000), "3 x 135 s = 6.75, rounded up");
        assertEquals(10, NightRules.maxRace(10, 120_000), "config wins when it is longer");
        assertEquals(15, NightRules.maxRace(4, 600_000), "never over 15 minutes (3 x 10 min would be 30)");
        assertEquals(15, NightRules.MAX_RACE_MINUTES, "NightRules' cap");
        assertEquals(150, NightRules.partyWindow(120_000), "a party race: max(120, 1.25 x 120 s)");
        assertEquals(120, NightRules.partyWindow(60_000), "a short track keeps the party race's 120 s");
    }

    @Test
    void aTrackWithNoModelTimeKeepsConfigExactly() {
        for (int cfg : new int[]{10, 60, 600}) {
            assertEquals(cfg, NightRules.finishWindow(cfg, 0), "no T_m: the configured window, " + cfg);
        }
        for (int cfg : new int[]{1, 4, 15}) {
            assertEquals(cfg, NightRules.maxRace(cfg, 0), "no T_m: the configured longest race, " + cfg);
        }
        assertEquals(120, NightRules.partyWindow(0), "and the party race's 120 s");
        assertEquals(NightRules.of(SHIPPED, 3, 0, false, 8), NightRules.of(SHIPPED, 3, 0, false, 8, 0),
                "the old of() is the new one with no T_m");
        assertEquals(0, BoatHype.modelMs(MountainRuns.medium()), "fixture: the algo-3 run has none");
    }

    @Test
    void aNightOnAV2TrackStoresItsEffectiveWindows() {
        long model = BoatHype.modelMs(MountainRunsV2.road());
        NightRules r = NightRules.of(SHIPPED, 3, 0, false, 8, model);
        assertEquals(150, r.finishWindowSeconds(), "stored with the night: the track's finish window");
        assertEquals(6, r.maxRaceMinutes(), "and its longest race");
        NightRules back = NightRules.decode(r.encode(), NightRules.of(SHIPPED, 3, 0, false, 8));
        assertEquals(r, back, "read back from the night's row after a restart, not from config");
        assertTrue(r.encode().contains("finish_window=150\n") && r.encode().contains("max_race=6\n"),
                "under the keys a night always kept them: " + r.encode());
        assertEquals(SHIPPED.finishWindowSeconds(), NightRules.of(SHIPPED, 3, 0, false, 8).finishWindowSeconds(),
                "config is untouched");
    }

    @Test
    void theLongestNightGrowsWithTheTrackForTheRestartFit() {
        NightRules cfg = NightRules.of(SHIPPED, 3, 0, false, 8);
        NightRules v2 = NightRules.of(SHIPPED, 3, 0, false, 8, 120_000);
        assertEquals(180_000L + 3 * (4 * 60_000L + 20_000L) + 120_000L, cfg.worstMillis(), "fixture: 18 minutes");
        assertEquals(180_000L + 3 * (6 * 60_000L + 20_000L) + 120_000L, v2.worstMillis(),
                "3 races of up to 6 minutes: 24 minutes, what the restart fit and the rebuild guard must see");
    }

    @Test
    void aRacerAt045BehindALeaderAt085StillFinishesOnA105To135SecondTrack() {
        NightRules config = NightRules.of(SHIPPED, 3, 0, false, 8);
        for (long model : new long[]{105_000, 120_000, 135_000}) {
            NightRules r = NightRules.of(SHIPPED, 3, 0, false, 8, model);
            long leader = Math.round(model / 0.85);
            long kid = Math.round(model / 0.45);
            assertEquals(List.of(), ends(r, leader, kid - 1, false), "T_m " + model + ": the race is still on just"
                    + " before the 0.45 racer finishes (" + kid + " ms after Go, the leader in at " + leader + ")");
            List<EventMachine.Action> done = ends(r, leader, kid, true);
            assertEquals(1, done.size(), "and ends once she is in: " + done);
            assertEquals("everyone is in", done.get(0).why(), "everyone finished: nobody was sent to the stand");
            List<EventMachine.Action> cut = ends(config, leader, kid - 1, false);
            assertEquals(1, cut.size(), "T_m " + model + ": config's own 60 s window would have ended her race");
            assertEquals("the finish window closed", cut.get(0).why(), "mid-run (red-team F03)");
        }
    }

    /**
     * Race 1's END_RACE actions of a night under {@code r}, stepped {@code at} ms after its Go, the leader in at
     * {@code leader} ms and the slow racer still on the track unless {@code kidIn}.
     */
    private static List<EventMachine.Action> ends(NightRules r, long leader, long at, boolean kidIn) {
        long go = 1_800_000_000_000L;
        EventPlan plan = new EventPlan("rn-20261002-1900", "fresh_boat", go - 600_000, go - 60_000, r, false, "");
        EventMachine.Timing t = EventMachine.Timing.of(plan, 0);
        EventMachine.State racing = new EventMachine.State(EventMachine.Phase.RACING, 1, go, go, 0, 0);
        EventMachine.Step step = EventMachine.step(racing, t, new EventMachine.Facts(go + at, 2, 2, kidIn ? 0 : 1,
                go + leader, true));
        return step.actions().stream().filter(a -> a.what() == EventMachine.Do.END_RACE).toList();
    }

    @Test
    void theV2FixtureIsATwoMinuteRun() {
        Course c = MountainRunsV2.road();
        assertEquals(MountainRunsV2.MODEL_MS, BoatHype.modelMs(c), "fixture: T_m 120 s");
    }
}
