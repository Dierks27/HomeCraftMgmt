package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
import com.dierks.homecraft.gui.games.event.RaceNightMenu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night on a Mountain Run v2 (MOUNTAIN-V2-SPEC §12, red-team F05), pure: what it says and what it races.
 *
 * <p>Pinned here: a v2 night's format names its style, "3 downhill races on the Winding Road", read off the
 * track's seed so the copy always follows the real run, and the Race Night screen says the same; Race Night
 * races only the Winding Road, so a Slalom (a week an admin's seed, {@code style: slalom} or a Race Night
 * switched on mid-week made one) is refused with a line that says why, in its set's own time word ("this
 * week", "today", or none), whatever its grid (what fixes it depends on config: {@link RaceNightV2Test}); and
 * the algo-3 run and every other track read and race exactly as before.
 */
class MountainV2NightCopyTest {

    @Test
    void aV2NightsFormatNamesTheWindingRoad() {
        assertEquals("3 downhill races on the Winding Road", EventCopy.format(3, 0, MountainRunsV2.road()),
                "§12: the night's format on a v2 Winding Road");
        assertEquals("1 downhill race on the Winding Road", EventCopy.format(1, 0, MountainRunsV2.road()), "one race");
        assertEquals("3 downhill races on the Slalom", EventCopy.format(3, 0, MountainRunsV2.slalom()),
                "a Slalom says so (a party race's copy; a night refuses one)");
        assertEquals(" on the Winding Road", EventCopy.where(MountainRunsV2.road()), "the ending alone");
        assertEquals("3 downhill races", EventCopy.format(3, 0, MountainRuns.medium()), "the algo-3 run as before");
        assertEquals("", EventCopy.where(MountainRuns.medium()), "with nothing more");
        assertEquals("", EventCopy.where(null), "and nothing for no track");
        assertTrue(EventCopy.downhill(MountainRunsV2.road()), "a v2 run is a downhill sprint too: no laps");
        assertEquals("a downhill sprint", EventCopy.laps(1, MountainRunsV2.road()), "the admin's status");
    }

    @Test
    void theRaceNightScreenSaysWhereTheRacesAre() {
        RaceNightMenu.View v = new RaceNightMenu.View("Fri 7:00 PM", null, "Ice Boat", 3, 1, List.of(20, 12, 8), 5,
                true, RaceNightMenu.Join.SOON, 0, 8, "6:50 PM", false, null, null, null, null, 0, true, true,
                EventCopy.where(MountainRunsV2.road()));
        RaceNightMenu.Tile track = RaceNightMenu.tiles(v, false).get(RaceNightMenu.TRACK);
        assertEquals("&bIce Boat &7- 3 downhill races on the Winding Road", track.name(), "the track tile's NAME");
        RaceNightMenu.View old = new RaceNightMenu.View("Fri 7:00 PM", null, "Ice Boat", 3, 1, List.of(20, 12, 8), 5,
                true, RaceNightMenu.Join.SOON, 0, 8, "6:50 PM", false, null, null, null, null, 0, true, true);
        assertEquals("&bIce Boat &7- 3 downhill races", RaceNightMenu.tiles(old, false).get(RaceNightMenu.TRACK).name(),
                "a view with no style reads as before");
        assertEquals(new RaceNight.Upcoming("Ice Boat", 1, true, " on the Winding Road"),
                RaceNight.Upcoming.of(MountainRunsV2.road(), 0), "a scheduled night's track before it is made");
        assertEquals(new RaceNight.Upcoming("Ice Boat", 1, true), RaceNight.Upcoming.of(MountainRuns.medium(), 0),
                "the algo-3 run's, as before");
    }

    @Test
    void raceNightRacesOnlyTheWindingRoad() {
        Course slalom = MountainRunsV2.slalom();
        String why = RaceTrack.raceProblem(slalom, 12, 2);
        assertEquals("Ice Boat is the Slalom this week, and " + RaceTrack.SLALOM, why, "a Slalom is refused, saying why");
        assertTrue(!why.contains("/hcm"), "with no command in it: a call-off reason reaches players, and the fix"
                + " depends on config, so Race Night adds it where an admin reads it (audit M08, M11): " + why);
        assertNull(RaceTrack.raceProblem(MountainRunsV2.road(), 12, 2), "a Winding Road is raced");
        assertNull(RaceTrack.raceProblem(MountainRuns.medium(), 12, 2), "the algo-3 run as before");
        assertTrue(RaceTrack.raceProblem(MountainRunsV2.road(), 1, 2).contains("starting grid seats 1"),
                "a v2 road with too small a grid is still refused for its grid");
        assertTrue(RaceTrack.raceProblem(slalom, 1, 2).contains(RaceTrack.SLALOM),
                "a Slalom says it is a Slalom first, not that its grid is small");
        assertTrue(why.codePoints().allMatch(cp -> cp <= 0xFFFF), "nothing Bedrock can't draw");
    }

    @Test
    void theSlalomLineSaysTheSetsOwnTimeWord() {
        Course daily = MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.SLALOM_SEED, 120_000, 4, 1));
        assertEquals("Ice Boat is the Slalom today, and " + RaceTrack.SLALOM, RaceTrack.raceProblem(daily, 12, 2),
                "audit M08: a daily set's Slalom is today's, never \"this week\"");
        Course threeDay = MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.SLALOM_SEED, 120_000, 4, 3));
        assertEquals("Ice Boat is a Slalom, and " + RaceTrack.SLALOM, RaceTrack.raceProblem(threeDay, 12, 2),
                "any other cadence: no time word");
        assertEquals("Race Night races only on the Winding Road", RaceTrack.SLALOM,
                "the end every cadence shares, which callers tell the refusal by");
    }
}
