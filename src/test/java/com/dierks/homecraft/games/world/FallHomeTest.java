package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fall built up in a game never comes home (final gate, #18). In a session fall damage is cancelled,
 * but the fall distance still builds (a Dropper level is a drop of 24 blocks or more) and the server
 * keeps it through a teleport. So a player who leaves, is sent home or disconnects in mid-air would
 * land at home, out of the session, with that whole fall: death, with their real things. Every way a
 * snapshot goes on, and every trip home, ends with no fall and no speed.
 */
class FallHomeTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    private static final Place START = Place.of("games", 100, 70, 100);

    private Connection conn;
    private FakeServer server;
    private GamesDao dao;
    private SessionCore<FakeServer.Body, String> core;
    private final Logger log = quiet();
    private final FakeServer.Game trials = new FakeServer.Game("trials");
    private FakeServer.Body dan;

    private static Logger quiet() {
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        return l;
    }

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
        server = new FakeServer();
        core = new SessionCore<>(dao, server, log);
        dan = new FakeServer.Body("Dan", HOME);
        dan.slots[0] = "netherite sword";
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** Into a Dropper run, then 30 blocks down a drop. */
    private void midDrop() {
        assertNull(core.enter(dan, "trials", "shaft", START, trials), "a still, safe player can start");
        server.step();
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(dan.id), "playing");
        dan.place = Place.of("games", 100, 40, 100);
        dan.fall = 30f;
        dan.speed = 3.5;
    }

    @Test
    void leavingMidDropLandsHomeWithNoFall() throws Exception {
        midDrop();
        core.leave(dan, EndReason.QUIT_ITEM); // Leave game, clicked twice on the way down
        dan.fall += 12f; // the trip home is async: they go on falling in the shaft meanwhile
        server.arriveAll();
        assertEquals(HOME, dan.place, "home");
        assertEquals(0f, dan.fall, "no fall lands with them: their first step at home would have been 39 damage");
        assertEquals(0.0, dan.speed, "and no speed");
        assertNull(dao.loadState(dan.id), "finished");
    }

    @Test
    void theGamesOffOrARestartMidDropLandHomeWithNoFall() {
        midDrop();
        core.leave(dan, EndReason.GAME_OFF); // the same tick, with a synchronous teleport
        assertEquals(HOME, dan.place, "home at once");
        assertEquals(0f, dan.fall, "no fall lands with them");
    }

    @Test
    void aDisconnectMidDropSavesNoFallAndTheNextJoinLandsHomeWithNone() {
        midDrop();
        core.quit(dan); // restored in place, in mid-air, and saved
        assertEquals(0f, dan.savedFall(), "the fall isn't written into their data file with their things");
        dan.fall = 17f; // they load in over the shaft on the next join and fall a little
        core.joined(dan);
        dan.fall += 8f; // on the loading screen while the trip home loads
        assertTrue(SessionRecoveryListener.sparesFall(core, dan, EntityDamageEvent.DamageCause.FALL),
                "out of any session, but on the way home: hitting the shaft floor meanwhile costs nothing");
        assertFalse(SessionRecoveryListener.sparesFall(core, dan, EntityDamageEvent.DamageCause.LAVA),
                "only the fall");
        server.arriveAll();
        assertFalse(SessionRecoveryListener.sparesFall(core, dan, EntityDamageEvent.DamageCause.FALL),
                "home: a fall is theirs again");
        assertEquals(HOME, dan.place, "sent home at the join");
        assertEquals(0f, dan.fall, "and they land with no fall");
    }

    /**
     * The round-2 audit's G1 #3: the fall a trip home spares is spared by the recovery listener, which
     * listens with the games on, off or failed. The games' own guard stops at "games off and nobody in a
     * session", which is just how a server is after a crash when the owner switched the games off.
     */
    @Test
    void theFallATripHomeSparesIsSparedWhateverTheGamesSwitchSays() throws Exception {
        java.lang.reflect.Method guard = SessionRecoveryListener.class.getMethod("onFall", EntityDamageEvent.class);
        org.bukkit.event.EventHandler handler = guard.getAnnotation(org.bukkit.event.EventHandler.class);
        assertTrue(handler != null && !handler.ignoreCancelled(),
                "the recovery listener, registered once at enable and never gated by the games' switch, spares it");
        assertEquals(org.bukkit.event.EventPriority.LOWEST, handler.priority(), "before anything else hears the fall");
        assertFalse(java.util.Arrays.stream(KitGuardListener.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals("sparesFall")),
                "and not in the games' guard, which a games-off server with nobody playing never asks");

        midDrop();
        core.quit(dan);
        dan.fall = 17f;
        core.joined(dan); // the games are off: nothing in the core depends on the switch
        assertTrue(core.recovering(dan.id), "(on the way home)");
        assertTrue(SessionRecoveryListener.sparesFall(core, dan, EntityDamageEvent.DamageCause.FALL),
                "the fall on the way home is spared");
    }

    /**
     * The round-2 audit's G1 #1, the backstop: a player a game let go in a Games world with their things
     * not home yet (the trip home failed, and so did our teleport down to a floor) never takes a fall there;
     * at home, or with everything handed over, a fall is theirs again.
     */
    @Test
    void aPlayerLetGoInAGamesWorldBeforeTheirThingsAreHomeHasTheirFallSpared() throws Exception {
        midDrop();
        core.leave(dan, EndReason.QUIT_ITEM); // restored in the shaft, RETURN written, the trip home starts
        server.syncTeleportsWork = false; // another plugin refuses every teleport of ours for now
        server.fail(0);
        assertTrue(dan.messages.contains(SessionCore.NOT_HOME), "(the trip home failed)");
        assertNull(core.phase(dan.id), "(out of the session)");
        assertFalse(core.recovering(dan.id), "(and no trip is under way)");
        assertEquals(Place.of("games", 100, 40, 100), dan.place, "(our teleport down didn't happen either)");
        assertTrue(SessionRecoveryListener.sparesFall(core, dan, EntityDamageEvent.DamageCause.FALL),
                "their things aren't home yet, and they are in a Games world: the fall to the shaft's floor is spared");

        FakeServer.Body eve = new FakeServer.Body("Eve", Place.of("games", 0, 90, 0));
        assertFalse(SessionRecoveryListener.sparesFall(core, eve, EntityDamageEvent.DamageCause.FALL),
                "someone in the Games world with no game of theirs to finish takes their own falls");

        server.syncTeleportsWork = true;
        core.leave(dan, EndReason.COMMAND); // /hcm leave
        server.arriveAll();
        assertEquals(HOME, dan.place, "home");
        assertNull(dao.loadState(dan.id), "(finished)");
        assertFalse(SessionRecoveryListener.sparesFall(core, dan, EntityDamageEvent.DamageCause.FALL),
                "home with everything: a fall is theirs again");
    }

    @Test
    void aCrashMidDropLandsHomeWithNoFall() {
        midDrop();
        dan.autosave(); // their file: in mid-air, 30 blocks down
        dan.crash();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(dan);
        dan.fall += 6f;
        after.arriveAll();
        assertEquals(HOME, dan.place, "home after the crash");
        assertEquals(0f, dan.fall, "with no fall");
    }
}
