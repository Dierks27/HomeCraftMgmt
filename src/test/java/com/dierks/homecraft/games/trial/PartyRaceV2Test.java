package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.util.Text;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A party race on a Mountain Run v2 (MOUNTAIN-V2-SPEC §12, red-team F03, F05), pure.
 *
 * <p>Pinned here: its finish window is max(120 s, ⌈1.25 T_m⌉), 150 s on a two-minute run, so a slow young
 * rider still gets down behind a fast one, and the race ends exactly then; every other course keeps its
 * 120 s; and a party race on a Slalom (Race Night races only the Winding Road, friends may race either) gets a
 * kind heads-up that the gates are narrow, while a Winding Road, the spiral and a hand-built track get none.
 */
class PartyRaceV2Test {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);

    private final AtomicLong tick = new AtomicLong(1_000);

    @Test
    void aV2PartyRaceWindowGrowsWithTheRun() {
        assertEquals(150, PartyRace.finishWindowSeconds(MountainRunsV2.road()), "1.25 x 120 s");
        assertEquals(PartyRace.FINISH_WINDOW_SECONDS, PartyRace.finishWindowSeconds(MountainRuns.medium()),
                "the spiral keeps 120 s");
        assertEquals(PartyRace.FINISH_WINDOW_SECONDS, PartyRace.finishWindowSeconds(MountainRunsV2.of(null)),
                "and a hand-built track");
        assertEquals(120, PartyRace.FINISH_WINDOW_SECONDS, "the party race's own window, as before");
    }

    @Test
    void aV2PartyRaceEndsAtItsOwnWindow() {
        Course run = MountainRunsV2.road();
        PartyRace r = new PartyRace(7, run, List.of(new PartyRace.Racer(SAM, "Sam"), new PartyRace.Racer(AVA, "Ava")),
                0, tick::get, line -> { });
        r.seated(SAM, new Course.Spot(6200.5, 170, 2896.5, -90f, 0f));
        r.seated(AVA, new Course.Spot(6200.5, 170, 2892.5, -90f, 0f));
        assertTrue(r.seatingDone(), "fixture: both seated");
        long g = r.goTick();
        r.tick(g);
        tick.set(g + 2_820);
        r.finished(AVA, 141_000, true, null); // the leader, at 0.85 of model speed
        assertEquals(PartyRace.Step.NONE, r.tick(g + 2_820 + 120 * 20L), "120 s after the first finish: still on");
        assertEquals(PartyRace.Step.NONE, r.tick(g + 2_820 + 150 * 20L - 1), "until its 150 s window closes");
        assertEquals(PartyRace.Step.END, r.tick(g + 2_820 + 150 * 20L), "then it ends");
    }

    @Test
    void aSlalomPartyRaceGetsAKindHeadsUp() {
        String note = PartyRaces.slalomNote(MountainRunsV2.slalom());
        assertEquals("Heads up: Ice Boat is the Slalom this week. The gates are narrow and boats bump, so give each"
                + " other room!", Text.plain(note), "red-team F05: party races warn");
        assertNull(PartyRaces.slalomNote(MountainRunsV2.road()), "the Winding Road needs none");
        assertNull(PartyRaces.slalomNote(MountainRuns.medium()), "nor the spiral");
        assertNull(PartyRaces.slalomNote(MountainRunsV2.of(null)), "nor a hand-built track");
        List<String> words = new ArrayList<>(List.of(note.toLowerCase().split("[^a-z]+")));
        assertTrue(words.stream().noneMatch(w -> w.equals("almost") || w.equals("lose") || w.equals("fail")),
                "kid-safe words: " + words);
    }
}
