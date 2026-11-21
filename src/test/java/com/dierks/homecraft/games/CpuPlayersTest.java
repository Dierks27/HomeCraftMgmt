package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneSettings;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable;
import com.dierks.homecraft.games.cpu.CpuChoice;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.cpu.CpuSettings;
import com.dierks.homecraft.games.cpu.NpcSkin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NPC players as the framework runs them ({@code games.cpu}): their switch, each player's setting and a parent's
 * or admin's lock (kept in {@code game_prefs}), the level rule, the owner's names and skins (kept in {@code hcm_meta}),
 * and above all that an NPC never leaves a trace in anything real: after NPCs have sat at the table and run in races,
 * no row anywhere names one (no tokens, no money, no rounds, no scores, no boards, no counts).
 */
class CpuPlayersTest {

    private Host host;
    private GamesService games;
    private CpuPlayers npcs;

    private void open(CpuSettings settings, boolean gamesOn) throws Exception {
        if (host != null) {
            host.connection.close();
        }
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.parlorKept = new HashMap<>();
        host.config = GamesKit.config(GamesKit.common(gamesOn, 1000, 600, 6), "cpu", settings,
                "twenty_one", TwentyOneSettings.defaults(), "mob_races", MobRacesSettings.defaults());
        games = GamesKit.service(host, List.of(TwentyOne.SPEC, MobRaces.SPEC, CpuPlayers.SPEC));
        npcs = CpuPlayers.of(games);
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

    @Test
    void theSwitchesNothingWhileTheGamesOrTheNpcsAreOff() throws Exception {
        open(CpuSettings.defaults(), true);
        assertNotNull(npcs, "shipped on");
        assertEquals(new CpuSettings(true, 3, 20), CpuSettings.defaults(), "on, 3 regulars, 20 seconds alone");
        open(new CpuSettings(false, 3, 20), true);
        assertNull(npcs, "games.cpu.enabled false: no NPC anywhere");
        assertNotNull(CpuPlayers.any(games), "an admin can still set things up");
        open(CpuSettings.defaults(), false);
        assertNull(npcs, "games.enabled false: nothing happens");
    }

    @Test
    void aPlayersOwnSettingIsKeptAndALockCantBeLiftedByThem() throws Exception {
        open(CpuSettings.defaults(), true);
        Fake lilah = player("Lilah");
        UUID id = lilah.id;
        assertEquals(CpuChoice.DEFAULT, npcs.choice(id), "on, each at its own level, to start");
        assertTrue(npcs.setOwn(id, new CpuChoice(true, CpuChoice.Pref.MATCHED)));
        npcs.onQuit(lilah.player); // forget, so the next read is from the database
        assertEquals(new CpuChoice(true, CpuChoice.Pref.MATCHED), npcs.choice(id), "kept in game_prefs");

        assertTrue(npcs.setLock(id, new CpuChoice(true, CpuChoice.Pref.EASY)), "the owner sets Lilah to easy");
        assertEquals(CpuChoice.Pref.EASY, npcs.choice(id).level());
        assertFalse(npcs.setOwn(id, new CpuChoice(true, CpuChoice.Pref.ANY)), "Lilah can't change it");
        assertFalse(npcs.setOwn(id, new CpuChoice(false, CpuChoice.Pref.EASY)), "nor turn NPCs off herself");
        npcs.onQuit(lilah.player);
        assertEquals(CpuChoice.Pref.EASY, npcs.choice(id).level(), "the lock is kept");
        assertTrue(npcs.prefs(id).locked());

        assertTrue(npcs.setLock(id, null), "unlocked");
        assertEquals(CpuChoice.Pref.MATCHED, npcs.choice(id).level(), "her own setting is back");
        assertTrue(npcs.setFor(id, new CpuChoice(false, CpuChoice.Pref.ANY)), "set for her, not locked");
        assertFalse(npcs.wants(id), "NPCs off for her");
        assertTrue(npcs.setOwn(id, new CpuChoice(true, CpuChoice.Pref.ANY)), "and she may change that one");
        assertFalse(npcs.wants(CpuRoster.ALL.get(0).id()), "an NPC never counts as a real player who wants NPCs");
    }

    @Test
    void theLevelInAGameIsEasyWhenAnyoneWantsEasyAndMatchedFollowsTheirRecord() throws Exception {
        open(CpuSettings.defaults(), true);
        Fake jeff = player("Jeff");
        Fake lilah = player("Lilah");
        CpuPlayer ozzie = CpuRoster.byKey("ozzie");
        assertEquals(CpuLevel.HARD, npcs.level(ozzie, List.of(jeff.id), "twenty_one"), "any: its own");
        npcs.setLock(lilah.id, new CpuChoice(true, CpuChoice.Pref.EASY));
        assertEquals(CpuLevel.EASY, npcs.level(ozzie, List.of(jeff.id, lilah.id), "twenty_one"), "with Lilah: easy");
        assertEquals(CpuLevel.EASY, npcs.levelAgainst(ozzie, lilah.id, "connect_four"));

        npcs.setOwn(jeff.id, new CpuChoice(true, CpuChoice.Pref.MATCHED));
        assertEquals(CpuLevel.MEDIUM, npcs.level(ozzie, List.of(jeff.id), "twenty_one"),
                "matched in a game of chance: no skill to match, medium");
        assertEquals(CpuLevel.MEDIUM, npcs.levelAgainst(ozzie, jeff.id, "connect_four"), "matched starts at medium");
        npcs.recordGame(jeff.id, "connect_four", CpuLevel.MEDIUM, true, false);
        assertEquals(CpuLevel.HARD, npcs.levelAgainst(ozzie, jeff.id, "connect_four"), "Jeff won: one up");
        npcs.recordGame(jeff.id, "connect_four", CpuLevel.HARD, false, true);
        npcs.recordGame(jeff.id, "connect_four", CpuLevel.MEDIUM, false, true);
        assertEquals(CpuLevel.EASY, npcs.levelAgainst(ozzie, jeff.id, "connect_four"), "two losses: down to easy");
        assertEquals(CpuLevel.MEDIUM, npcs.levelAgainst(ozzie, jeff.id, "tic_tac_toe"), "each game its own record");
    }

    @Test
    void theOwnersNamesAndSkinsAreKeptAndTheMarkerStays() throws Exception {
        open(CpuSettings.defaults(), true);
        CpuPlayer pip = CpuRoster.byKey("pip");
        assertEquals("Grandpa Pip", npcs.rename(pip, "&6Grandpa Pip ⚙"));
        assertEquals("&fGrandpa Pip &7⚙ &e★", npcs.label(pip), "renamed: the gear and the star stay");
        npcs.reskin(pip, NpcSkin.parse("default:steve"));
        npcs.reskin(CpuRoster.byKey("wren"), NpcSkin.parse("player:Someone"));
        assertEquals("Grandpa Pip", host.parlorKept.get("gen.npc.pip.name"), "kept under gen.npc. in hcm_meta");
        assertEquals("default:steve:wide", host.parlorKept.get("gen.npc.pip.skin"));
        host.parlorKept.put("gen.npc.mabel.skin", "texture:not a texture");
        npcs.start(); // a restart reads them back
        assertEquals("Grandpa Pip", npcs.name(pip));
        assertEquals(new NpcSkin.BuiltIn("steve", false), npcs.skin(pip));
        assertEquals(new NpcSkin.Account("Someone"), npcs.skin(CpuRoster.byKey("wren")));
        assertEquals(CpuRoster.byKey("mabel").skin(), npcs.skin(CpuRoster.byKey("mabel")),
                "a kept skin that can't be read: its own built-in skin, never a broken one");
        assertTrue(host.logs.stream().anyMatch(r -> r.getMessage().contains("Mabel (NPC)")),
                "and the console says why, once");
        assertNull(npcs.rename(pip, "⚙ ★"), "nothing fit to show: refused");
        assertEquals("Pip", npcs.rename(pip, null), "its own name back");
        assertEquals("", host.parlorKept.get("gen.npc.pip.name"));
        npcs.reskin(pip, null);
        assertEquals(pip.skin(), npcs.skin(pip), "its own skin back");
    }

    /** Every value in every table of the database that names {@code id}. */
    private List<String> rowsNaming(UUID id) throws Exception {
        List<String> out = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        try (Statement st = host.connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String t : tables) {
            try (Statement st = host.connection.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + t
                    + "\"")) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        Object v = rs.getObject(c);
                        if (v != null && v.toString().contains(id.toString())) {
                            out.add(t + "." + md.getColumnName(c));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void npcsLeaveNoTraceInAnythingReal() throws Exception {
        open(CpuSettings.defaults(), true);
        Fake alex = player("Alex");
        TwentyOneTable table = ((TwentyOne) games.game("twenty_one")).table();
        table.sit(alex.player, () -> { });
        MobRaces races = (MobRaces) games.game("mob_races");
        races.trackSeen();
        // three rounds at the table with the NPCs, and a race they may enter
        for (int round = 0; round < 3; round++) {
            for (int s = 0; s < 25 && table.phase() == TwentyOneTable.Phase.OPEN; s++) {
                host.move(1_000);
                npcs.second();
                if (table.npcCount() > 0) {
                    table.ready(alex.player, true);
                }
            }
            for (int s = 0; s < 30 && table.phase() == TwentyOneTable.Phase.PLAYING; s++) {
                if (table.turn() != null && table.turn().id().equals(alex.id)) {
                    table.stand(alex.player);
                }
                host.move(1_000);
                npcs.second();
            }
        }
        assertTrue(table.npcCount() > 0, "an NPC sat in");
        for (int s = 0; s < 30 && table.phase() == TwentyOneTable.Phase.PLAYING; s++) {
            if (table.turn() != null && table.turn().id().equals(alex.id)) {
                table.stand(alex.player);
            }
            host.move(1_000);
            npcs.second();
        }
        table.leave(alex.id); // up from the table, over to the track
        host.move(1_000);
        races.trackSeen();
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false), alex.heard() + " | " + races.phase());
        for (int s = 0; s < 15; s++) {
            host.move(1_000);
            races.trackSeen();
            npcs.second();
            races.tick();
        }
        for (CpuPlayer c : CpuRoster.ALL) {
            assertEquals(List.of(), rowsNaming(c.id()), c.name() + ": no row anywhere names an NPC");
            assertEquals(0, host.balanceOf(c.id()));
            assertFalse(host.money.containsKey(c.id()), "and no money");
        }
        assertFalse(rowsNaming(alex.id).isEmpty(), "(the scan does find a real player's rows)");
    }
}
