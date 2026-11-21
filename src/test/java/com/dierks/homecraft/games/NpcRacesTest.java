package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;
import com.dierks.homecraft.games.chance.mobraces.RaceScript;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuRoster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC players in Mob Races: an NPC enters only a race a real player is in, with a pretend stake and no round; a race
 * with only NPCs in it never runs; and an NPC's entry changes no real entry's odds or pay.
 */
class NpcRacesTest {

    private Host host;
    private GamesService games;
    private MobRaces races;

    private void open() {
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "mob_races", MobRacesSettings.defaults());
        games = GamesKit.service(host, List.of(MobRaces.SPEC));
        races = (MobRaces) games.game("mob_races");
        races.trackSeen();
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 200);
        games.parlorGuest(f.id);
        return f;
    }

    private void pass(long ms) {
        host.move(ms);
        races.trackSeen();
        races.tick();
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static long seedWhere(Racer racer) {
        for (long seed = 1; seed < 10_000; seed++) {
            if (RaceScript.winner(seed) == racer.lane()) {
                return seed;
            }
        }
        throw new AssertionError("no seed for " + racer);
    }

    private static String label(CpuPlayer c) {
        return CpuRoster.label(c.name(), c.level());
    }

    @Test
    void noNpcEntersARaceWithNoRealPlayerAndARaceOfNpcsAloneNeverRuns() throws Exception {
        open();
        CpuPlayer pip = CpuRoster.ALL.get(0);
        assertFalse(races.enterNpc(pip.id(), label(pip), Racer.PIG, 5), "nobody real has entered: no race to join");
        assertEquals(Phase.IDLE, races.phase());

        Fake alex = player("Alex");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertTrue(races.enterNpc(pip.id(), label(pip), Racer.PIG, 5), "now an NPC may enter");
        assertFalse(races.enterNpc(pip.id(), label(pip), Racer.COW, 5), "once");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds"), "the NPC's entry has no round");
        // Alex leaves the server before the start: only the NPC is left, so there is no race
        host.online.remove(alex.id);
        pass(1_000);
        assertEquals(Phase.IDLE, races.phase(), "a race of NPCs alone never runs");
        assertTrue(races.entries().isEmpty(), "and the NPC's entry went with it");
        assertEquals(200, host.balanceOf(alex.id), "Alex's stake came back whole");
    }

    @Test
    void npcEntriesChangeNoRealEntrysPay() throws Exception {
        int[] balances = new int[2];
        for (int run = 0; run < 2; run++) {
            open();
            Fake alex = player("Alex");
            long seed = seedWhere(Racer.CHICKEN);
            assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
            if (run == 1) {
                // two NPCs pile in, one on the same racer as Alex and one on another
                assertTrue(races.enterNpc(CpuRoster.ALL.get(0).id(), label(CpuRoster.ALL.get(0)), Racer.CHICKEN, 5));
                assertTrue(races.enterNpc(CpuRoster.ALL.get(4).id(), label(CpuRoster.ALL.get(4)), Racer.PIG,
                        races.settings().open().get(races.settings().open().size() - 1)));
                assertFalse(races.enterNpc(CpuRoster.ALL.get(5).id(), label(CpuRoster.ALL.get(5)), Racer.FOX, 5),
                        "at most two NPCs");
                assertEquals(2, races.npcCount());
            }
            races.seedNextRace(seed);
            races.startNow(alex.player);
            assertEquals(Phase.RUNNING, races.phase(), "Alex asked to start: NPCs never hold the start up");
            balances[run] = host.balanceOf(alex.id);
            assertEquals(1, count("SELECT COUNT(*) FROM game_rounds"), "one round: Alex's");
            assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'"));
            for (CpuPlayer c : CpuRoster.ALL) {
                assertEquals(0, host.balanceOf(c.id()), "an NPC has no tokens and never gets any");
                assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE player = '" + c.id() + "'"));
            }
            host.connection.close();
        }
        assertEquals(balances[0], balances[1], "Alex's pay is the same with NPCs in the race or not");
        assertTrue(balances[0] > 195, "and Alex's pick won");
    }
}
