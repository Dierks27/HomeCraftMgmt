package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.golf.GolfNow;
import com.dierks.homecraft.games.trial.TrialsNow;
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

    @Test
    void withNoRaceGoingACheerReachesEveryoneOnASoloRunOrARoundAlone() {
        UUID sam = UUID.randomUUID();
        UUID lee = UUID.randomUUID();
        UUID ana = UUID.randomUUID();
        UUID max = UUID.randomUUID();
        UUID eve = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        List<TrialsNow.Run> runs = List.of(
                new TrialsNow.Run(sam, "Sky Rings", 3, 12, 41_900, false, false, false, false), // the clock running
                new TrialsNow.Run(lee, "Sky Rings", 0, 12, 0, false, true, false, false), // warming up
                new TrialsNow.Run(ana, "Lava Leap", 0, 5, 0, true, false, false, false), // the 3-2-1: not yet
                new TrialsNow.Run(max, "Lava Leap", 5, 5, 60_000, false, false, false, true), // finished
                new TrialsNow.Run(eve, "Ice Boat", 1, 4, 9_000, false, false, true, false), // a racer: the race's
                new TrialsNow.Run(me, "Sky Rings", 1, 12, 5_000, false, false, false, false)); // the cheerer
        List<GolfNow.Round> rounds = List.of(
                new GolfNow.Round(eve, "Tiny Golf", 4, 9, false, false), // a round alone (Eve again: once)
                new GolfNow.Round(max, "Tiny Golf", 9, 9, false, true), // done
                new GolfNow.Round(ana, "Tiny Golf", 2, 9, true, false)); // a group: the group's
        assertEquals(List.of(sam, lee, eve), Cheers.solo(runs, rounds, me),
                "on a run with the clock going or warming up, or in a round alone; never the cheerer");
        assertEquals(List.of(), Cheers.solo(List.of(), List.of(), me), "nobody: " + Cheers.NOBODY);
        assertTrue(Cheers.NOBODY.contains("Nobody"), "the line says so");
    }
}
