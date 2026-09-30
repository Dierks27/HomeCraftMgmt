package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's words for the Ice Boat Mountain Run (COURSE-VARIETY-SPEC §5.2, §8), with no server.
 *
 * <p>Pinned here: a night on the Mountain Run reads "3 downhill races" (a sprint has no laps to
 * count), and the admin's status says "a downhill sprint" where it said the laps; every other track,
 * the frozen algo-2 loops and hand-built point-to-point tracks included, reads exactly as before; the
 * heads-up and join-open lines end with the hype line when there is one and are unchanged when there
 * isn't; and every line is kid-safe and drawable on Bedrock.
 */
class EventCopyTest {

    /** Fri 2 Oct 2026 19:00 UTC. */
    private static final long T = 1_790_967_600_000L;
    private static final String HYPE = "&bThis week: 5 drops down the mountain!";

    /** A hand-built loop, two laps stored (the shape Fresh Boat's loops had). */
    private static Course loop() {
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        List<Course.Mark> two = new ArrayList<>(lap);
        two.addAll(lap);
        return new Course("ice", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games", new Course.Spot(20, 64, -16, 0, 0), two,
                new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
    }

    @Test
    void aNightOnTheMountainRunIsDownhillRacesWithNoLaps() {
        Course run = MountainRuns.medium();
        assertTrue(EventCopy.downhill(run), "the Mountain Run is raced as a downhill sprint");
        assertFalse(RaceTrack.loop(run), "§5.1: it's not a loop");
        assertEquals(1, RaceTrack.laps(run, 0), "its own laps: 1");
        assertEquals("3 downhill races", EventCopy.format(3, RaceTrack.laps(run, 0), run), "§8: \"3 downhill races\"");
        assertEquals("1 downhill race", EventCopy.format(1, 1, run), "one race");
        assertEquals("5 downhill races", EventCopy.format(5, 1, true), "the family-night recommendation, 5");
        assertEquals("a downhill sprint", EventCopy.laps(1, run), "the admin's status line says so, not \"1 lap\"");
    }

    @Test
    void everyOtherTrackReadsExactlyAsBefore() {
        Course loop = loop();
        assertFalse(EventCopy.downhill(loop), "a loop isn't downhill");
        assertEquals("3 races, 2 laps", EventCopy.format(3, 2, loop), "a loop's laps");
        assertEquals(EventCopy.format(3, 2), EventCopy.format(3, 2, loop), "the same words as the old format");
        assertEquals("2 laps", EventCopy.laps(2, loop), "the admin's laps");
        assertEquals("1 lap", EventCopy.laps(1, loop), "one lap");
        Course hand = MountainRuns.medium(null); // the same downhill marks, hand-built: no claims about it
        assertFalse(EventCopy.downhill(hand), "a hand-built track is never called downhill");
        assertEquals("3 races, 1 lap", EventCopy.format(3, 1, hand), "a hand-built sprint reads as it always did");
        assertEquals("1 lap", EventCopy.laps(1, hand), "and so does its admin line");
        assertEquals("3 races, 1 lap", EventCopy.format(3, 1, (Course) null), "no track: the old words");
        assertEquals("3 races, 2 laps", EventCopy.format(3, 2, false), "downhill false: the old words");
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Course v2 = f.trial().course().withGen(f.tag());
            assertFalse(EventCopy.downhill(v2), f + ": an algo-2 loop isn't downhill");
            assertEquals("3 races, 2 laps", EventCopy.format(3, RaceTrack.laps(v2, 0), v2),
                    f + ": a live algo-2 loop still races its 2 laps until its set ends (§5.3)");
        }
    }

    @Test
    void theHeadsUpAndJoinOpenEndWithTheHypeWhenThereIsOne() {
        long join = T - 10 * 60_000L;
        String headsUp = EventCopy.headsUp(T, join, "Ice Boat", ZoneOffset.UTC);
        assertEquals("&6Race Night at 7:00 PM! &7Boat races on Ice Boat - join from 6:50 PM: &e/hcm play race", headsUp,
                "fixture: the heads-up as shipped");
        assertEquals(headsUp + " " + HYPE, EventCopy.headsUp(T, join, "Ice Boat", ZoneOffset.UTC, HYPE),
                "§5.2: the hype at the end of the heads-up");
        assertEquals(headsUp, EventCopy.headsUp(T, join, "Ice Boat", ZoneOffset.UTC, null), "no hype: unchanged");
        String open = EventCopy.joinOpen(T, "Ice Boat", ZoneOffset.UTC);
        assertEquals("&bRace Night &7on Ice Boat starts at 7:00 PM - join now: &e/hcm play race", open,
                "fixture: join-open as shipped");
        assertEquals(open + " " + HYPE, EventCopy.joinOpen(T, "Ice Boat", ZoneOffset.UTC, HYPE),
                "and at the end of join-open");
        assertEquals(open, EventCopy.joinOpen(T, "Ice Boat", ZoneOffset.UTC, " "), "a blank hype is none");
    }

    @Test
    void everyLineIsKidSafeAndDrawable() {
        Course run = MountainRuns.medium();
        List<String> lines = List.of(EventCopy.format(3, 1, run), EventCopy.format(1, 1, run), EventCopy.laps(1, run),
                EventCopy.headsUp(T, T - 600_000L, "Ice Boat", ZoneOffset.UTC, HYPE),
                EventCopy.joinOpen(T, "Ice Boat", ZoneOffset.UTC, HYPE));
        for (String line : lines) {
            assertTrue(GenCopy.copyProblems(line).isEmpty(), "kid-safe and drawable: " + GenCopy.copyProblems(line));
        }
    }
}
