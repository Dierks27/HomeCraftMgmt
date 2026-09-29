package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.ArenaRound;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm games floors status|reset|claim [confirm]|tp} (EVENTS-DROPPER-SPEC §B.3.5): status says
 * what the arena is doing (or why it isn't running); reset rebuilds the floors, or waits for a round
 * going; claim says whether the box is claimed and, confirmed, claims it; the words complete.
 */
class ArenaAdminTest {

    private final FakeArenaHost host = new FakeArenaHost(new FakeWorldPort(FakeArenaHost.WORLD));

    private ArenaService running() {
        ArenaService s = new ArenaService(host);
        s.start();
        for (int i = 0; i < 400 && s.round().phase() != ArenaRound.Phase.LOBBY; i++) {
            s.tick();
        }
        assertEquals(ArenaRound.Phase.LOBBY, s.round().phase(), "(built)");
        return s;
    }

    @Test
    void statusSaysWhatTheArenaIsDoingOrWhyItIsntRunning() {
        List<String> off = ArenaAdmin.lines(null, "status", new String[]{"status"}, "games.falling_floors.enabled is false");
        assertEquals(List.of("&6Falling Floors", "&7Not running: games.falling_floors.enabled is false"), off,
                "switched off");
        List<String> on = ArenaAdmin.lines(running(), "status", new String[0], "");
        assertEquals("&6Falling Floors", on.get(0), "a heading");
        assertTrue(String.join("\n", on).contains("lobby: 0/12 here"), "the round's state: " + on);
    }

    @Test
    void resetRebuildsTheFloorsOrWaitsForTheRoundGoing() {
        ArenaService s = running();
        assertEquals(List.of("&aThe floors are being put back and checked."),
                ArenaAdmin.lines(s, "reset", new String[]{"reset"}, ""), "from the lobby: at once");
        assertEquals(ArenaRound.Phase.RESET, s.round().phase(), "and it is");
        assertTrue(ArenaAdmin.lines(null, "reset", new String[]{"reset"}, "games.enabled is false").get(0)
                .contains("isn't running"), "not running: says so");
    }

    @Test
    void claimSaysWhetherTheBoxIsClaimedAndConfirmClaimsIt() {
        ArenaService s = running();
        List<String> said = ArenaAdmin.lines(s, "claim", new String[]{"claim"}, "");
        assertTrue(said.get(0).contains("is claimed"), "claimed at its first build: " + said);
        host.claim = null;
        said = ArenaAdmin.lines(s, "claim", new String[]{"claim"}, "");
        assertTrue(said.get(0).contains("isn't claimed yet") && said.get(1).contains("claim confirm"), said.toString());
        said = ArenaAdmin.lines(s, "claim", new String[]{"claim", "confirm"}, "");
        assertEquals("&aClaimed.", said.get(0), said.toString());
        assertEquals(s.claimText(), host.claim, "recorded");
    }

    @Test
    void anythingElseIsTheHelpAndTheWordsComplete() {
        assertEquals(ArenaAdmin.HELP, ArenaAdmin.lines(null, "what", new String[]{"what"}, ""), "the usage line");
        ArenaAdmin admin = new ArenaAdmin(null);
        assertEquals("floors", admin.name(), "/hcm games floors");
        assertEquals(List.of("status", "reset", "claim", "tp"), admin.tab(null, new String[]{""}), "every word");
        assertEquals(List.of("claim"), admin.tab(null, new String[]{"cl"}), "by its start");
        assertEquals(List.of("confirm"), admin.tab(null, new String[]{"claim", "c"}), "claim confirm");
        assertEquals(List.of(), admin.tab(null, new String[]{"reset", "c"}), "reset takes nothing more");
        assertTrue(admin.help().get(0).contains("status|reset|claim [confirm]|tp"), admin.help().toString());
    }
}
