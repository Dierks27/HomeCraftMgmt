package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GamesBench;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm play cheer} (CLUBHOUSE-SPEC §9, §10): once every 10 seconds per watcher, a kid-safe line
 * for the racers, and a racer's {@code /hcm play cheers off} is kept (and read back) per player.
 */
class CheersTest {

    private GamesBench bench;

    @AfterEach
    void tearDown() throws Exception {
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void aCheerOnceEveryTenSeconds() {
        Cheers c = new Cheers();
        UUID wes = UUID.randomUUID();
        assertTrue(c.allow(wes, 1_000), "the first cheer");
        assertFalse(c.allow(wes, 5_000), "not again for 10 s");
        assertEquals(6, c.waitSeconds(wes, 5_000), "6 s to wait");
        assertTrue(c.allow(UUID.randomUUID(), 5_000), "someone else can");
        assertTrue(c.allow(wes, 11_000), "10 s later: again");
        c.forget(wes);
        assertEquals(0, c.waitSeconds(wes, 11_001), "forgotten on a quit");
        assertEquals("&dSam cheers for you!", Cheers.line("Sam"), "the racer's line");
        assertEquals("&dSomeone cheers for you!", Cheers.line(null), "never a blank name");
    }

    @Test
    void cheersOffIsKeptAndReadBack() throws Exception {
        bench = new GamesBench(GamesBench.at(2026, 9, 29, 19, 0), List.of(Clubhouse.SPEC), "clubhouse",
                ClubhouseSettings.defaults());
        Clubhouse club = (Clubhouse) bench.games().game("clubhouse");
        WatchLiveTest.Fake sam = new WatchLiveTest.Fake("Sam", new Location(null, 0, 0, 0));
        assertTrue(club.cheersOn(sam.id), "cheers are on unless turned off");
        Clubhouse.cheersCommand(bench.games(), sam.player, new String[]{"play", "cheers", "off"});
        assertFalse(club.cheersOn(sam.id), "off: no cheers reach Sam");
        assertEquals("off", bench.dao().pref(sam.id, Cheers.PREF), "kept for next time");
        Clubhouse.cheersCommand(bench.games(), sam.player, new String[]{"play", "cheers"});
        assertTrue(sam.said.getLast().contains("off"), "and said: " + sam.said);
        Clubhouse.cheersCommand(bench.games(), sam.player, new String[]{"play", "cheers", "on"});
        assertTrue(club.cheersOn(sam.id), "on again");
    }
}
