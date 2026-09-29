package com.dierks.homecraft.games.event;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watching Race Night from anywhere and the Games tile (EVENTS-DROPPER-SPEC §A.5, §A.6), with no
 * server. Pinned here: Watch toggles; watching stops 30 seconds after the results; the watcher's bar
 * names the race and the leader; and the tile's NAME carries the night's state.
 */
class WatchersTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    @Test
    void watchTogglesAndStopsThirtySecondsAfterTheResults() {
        Watchers w = new Watchers();
        assertTrue(w.toggle(A), "a tap: watching");
        assertTrue(w.watching(A), "watching");
        assertFalse(w.toggle(A), "a second tap: stopped");
        assertFalse(w.watching(A), "not watching");
        w.toggle(A);
        w.toggle(B);
        assertTrue(w.expire(1_000).isEmpty(), "nothing ends while the night lasts");
        w.nightEnded(10_000);
        assertTrue(w.expire(39_999).isEmpty(), "still watching the results");
        assertEquals(List.of(A, B), w.expire(40_000), "30 s after the results, both stop");
        assertTrue(w.ids().isEmpty(), "nobody watching");
    }

    @Test
    void aWatchersBarNamesTheRaceAndTheLeader() {
        assertEquals("&bRace Night &7· race 2 of 3 &7· leader: &fSam",
                Watchers.bar(EventMachine.Phase.RACING, 2, 3, "Sam", 5), "racing");
        assertEquals("&bRace Night &7· break after race 1 of 3 &7· leader: &fSam",
                Watchers.bar(EventMachine.Phase.BREAK, 1, 3, "Sam", 5), "a break");
        assertEquals("&bRace Night &7· 4 racers in so far", Watchers.bar(EventMachine.Phase.OPEN, 0, 3, null, 4),
                "joining");
        assertEquals("&6Race Night is over &7· winner: &fSam", Watchers.bar(EventMachine.Phase.DONE, 3, 3, "Sam", 5),
                "over");
    }

    @Test
    void theTileNameCarriesTheState() {
        long fri7 = 1_790_967_600_000L;
        assertEquals("&6Race Night &7- Fri 7:00 PM",
                EventCopy.tileName(EventMachine.Phase.SCHEDULED, fri7, 0, 8, 0, 3, ZoneOffset.UTC), "upcoming");
        assertEquals("&aRace Night &7- join now! 3/8",
                EventCopy.tileName(EventMachine.Phase.OPEN, fri7, 3, 8, 0, 3, ZoneOffset.UTC), "joining");
        assertEquals("&cRace Night &7- racing (race 2 of 3)",
                EventCopy.tileName(EventMachine.Phase.RACING, fri7, 5, 8, 2, 3, ZoneOffset.UTC), "racing");
        assertEquals("&cRace Night &7- racing (race 1 of 3)",
                EventCopy.tileName(EventMachine.Phase.GRID, fri7, 5, 8, 0, 3, ZoneOffset.UTC), "on the grid for race 1");
    }
}
