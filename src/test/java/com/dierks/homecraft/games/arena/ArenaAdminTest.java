package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.ArenaRound;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * F review #2: {@code tp} never drops an admin into a gallery that isn't there (y 207 over
     * nothing, out of any session): only once a verify has passed, or when the walk is there anyway.
     */
    @Test
    void tpIsRefusedUntilTheGalleryIsBuiltAndChecked() {
        assertTrue(ArenaAdmin.tpRefusal(null, true, "games.enabled is false", () -> true).contains("isn't running"),
                "not running: says so");
        host.secret = null; // the week's floors can't be made yet
        ArenaService unplanned = new ArenaService(host);
        unplanned.start();
        assertTrue(ArenaAdmin.tpRefusal(unplanned, true, "", () -> true).contains("isn't built yet"), "no floors yet");
        host.secret = 12345L;

        ArenaService s = new ArenaService(host);
        s.start(); // planned; the boot verify hasn't run
        assertFalse(s.verified(), "(not verified)");
        assertEquals(ArenaAdmin.TP_NOT_BUILT, ArenaAdmin.tpRefusal(s, true, "", () -> false),
                "nothing to stand on: refused, with a line saying why");
        assertNull(ArenaAdmin.tpRefusal(s, true, "", () -> true),
                "unless the walk under the spot is there anyway (a closed arena to go and look at)");
        assertTrue(ArenaAdmin.tpRefusal(s, false, "", () -> true).contains("isn't built yet"), "no world loaded");

        assertNull(ArenaAdmin.tpRefusal(running(), true, "", () -> {
            throw new AssertionError("a verified gallery needs no look at the world");
        }), "verified: in you go");
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
