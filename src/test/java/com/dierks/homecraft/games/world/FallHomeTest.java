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
        assertTrue(KitGuardListener.sparesFall(core.recovering(dan.id), EntityDamageEvent.DamageCause.FALL),
                "out of any session, but on the way home: hitting the shaft floor meanwhile costs nothing");
        assertFalse(KitGuardListener.sparesFall(core.recovering(dan.id), EntityDamageEvent.DamageCause.LAVA),
                "only the fall");
        server.arriveAll();
        assertFalse(KitGuardListener.sparesFall(core.recovering(dan.id), EntityDamageEvent.DamageCause.FALL),
                "home: a fall is theirs again");
        assertEquals(HOME, dan.place, "sent home at the join");
        assertEquals(0f, dan.fall, "and they land with no fall");
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
