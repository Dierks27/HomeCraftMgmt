package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session that ends where the player is lets them go on a floor, never in mid-air (the round-2
 * audit's G1 #1, the rest of final gate #18/#19's class). Inside a session nothing hurts them; out of
 * it, a Sky Rings flyer 60 blocks up or a Dropper player halfway down a drop would fall with their real
 * things on. So every way a live session is let go where the player stands (RETURN can't be written, the
 * trip home fails, the row can't be read, the snapshot can't be read or won't go on) first puts them on
 * the session's last safe spot, by the session's own teleport, which lands with no fall. A watcher the
 * crash left flying is brought down to a floor before the restore changes their mode there, so a trip
 * home that then fails leaves them on that floor, not at y 95 or inside a hill.
 *
 * <p>And whatever such an exit banks into the carry is saved with the player at once (the round-2 audit's
 * G1 #4, hardening): otherwise their data file still holds it beside the "cleared" mark, and a hard crash
 * banks it a second time (two copies of one numbered Mini).
 */
class NeverLetGoInMidAirTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    /** Sky Rings' start platform: the session's start and first safe spot. */
    private static final Place START = Place.of("games", 100, 70, 100);
    /** Between two rings, 60 blocks up. */
    private static final Place RINGS = Place.of("games", 140, 130, 100);
    /** The Clubhouse's arrival spot. */
    private static final Place CLUBHOUSE = Place.of("games", 5390, 161, 4460);
    /** Where a watcher flies, over a course. */
    private static final Place FLYING = Place.of("games", 40, 95, 40);
    private static final String MINI = "Mini #42 (uid 7f3a)";

    private Connection conn;
    private GamesDao dao;
    private FakeServer server;
    private SessionCore<FakeServer.Body, String> core;
    private final Logger log = quiet();
    private final FakeServer.Game rings = new FakeServer.Game("trials");
    private final FakeServer.Game club = new FakeServer.Game("clubhouse");
    private FakeServer.Body ben;

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
        ben = new FakeServer.Body("Ben", HOME);
        ben.slots[0] = "netherite sword";
        ben.slots[1] = "diamond x3";
        ben.autosave(); // his data file as he walked up
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** Into Sky Rings, then gliding between rings 60 blocks up. */
    private void flying() {
        assertNull(core.enter(ben, "trials", "sky_rings", START, rings), "a still, safe player can start");
        server.step();
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(ben.id), "(playing)");
        ben.place = RINGS;
        ben.fall = 20f;
        ben.speed = 2.5;
    }

    /** From now on the database refuses to move the row to {@code phase} (as a full disk would). */
    private void refuse(String phase) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_" + phase + " BEFORE UPDATE OF phase ON game_saved_state WHEN NEW.phase = '"
                    + phase + "' BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");
        }
    }

    private void allow(String phase) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER no_" + phase);
        }
    }

    private void table(String from, String to) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + from + " RENAME TO " + to);
        }
    }

    private long count(FakeServer.Body p, String item) {
        return p.items().stream().filter(item::equals).count();
    }

    /** Let go on the course's start platform with no fall: never left where they were flying. */
    private void onTheFloor(String why) {
        assertNull(core.phase(ben.id), why + ": (out of the session)");
        assertEquals(START, ben.place, why + ": put down on the session's last safe spot, not left 60 blocks up");
        assertEquals(0f, ben.fall, why + ": landing with no fall");
        assertEquals(0.0, ben.speed, why + ": and no speed");
        assertTrue(server.syncTeleports.contains(START), why + ": by the session's own teleport");
    }

    // ---- a live session let go where the player is ------------------------------------------------------

    @Test
    void aLeaveWhoseReturnCantBeWrittenPutsThemDownOnTheCourseFirst() throws Exception {
        flying();
        refuse(SavedState.RETURN);
        core.leave(ben, EndReason.QUIT_ITEM); // Leave game, double-clicked between two rings
        assertTrue(ben.messages.contains(SessionCore.BACK_STAY), "their things are back; RETURN isn't written yet");
        assertEquals("SURVIVAL", ben.gameMode, "(in their own mode, which can fall)");
        assertEquals(1, count(ben, "netherite sword"), "(with everything they own)");
        onTheFloor("RETURN refused");
        assertEquals(0, server.trips.size(), "and not sent home until RETURN is written");

        allow(SavedState.RETURN);
        core.leave(ben, EndReason.COMMAND); // /hcm leave writes it and goes on
        server.arriveAll();
        assertEquals(HOME, ben.place, "home");
        assertNull(dao.loadState(ben.id), "finished");
        assertEquals(1, count(ben, "netherite sword"), "their things once");
    }

    @Test
    void theGamesSwitchedOffMidAirWhileReturnCantBeWrittenPutThemDownToo() throws Exception {
        flying();
        refuse(SavedState.RETURN);
        core.leave(ben, EndReason.GAME_OFF); // the game switched off (or an admin, or /hcm leave)
        assertTrue(ben.messages.contains(SessionCore.BACK_STAY), "(RETURN isn't written)");
        onTheFloor("the games off");
    }

    @Test
    void aTripHomeThatFailsPutsThemDownOnTheCourseNotWhereTheyWere() throws Exception {
        flying();
        core.leave(ben, EndReason.QUIT_ITEM); // restored where they glide, RETURN written, the trip home starts
        assertEquals(1, server.trips.size(), "(the trip home is on its way)");
        ben.fall += 10f; // falling while it loads: still in the session, so it costs nothing
        server.fail(0); // another plugin refused the teleport
        assertTrue(ben.messages.contains(SessionCore.NOT_HOME), "they are told to try /hcm leave");
        onTheFloor("the trip home failed");

        core.leave(ben, EndReason.COMMAND);
        server.arriveAll();
        assertEquals(HOME, ben.place, "/hcm leave takes them home");
        assertNull(dao.loadState(ben.id), "finished");
    }

    @Test
    void anEndThatCantReadTheRowPutsThemDownFirst() throws Exception {
        flying();
        table("game_saved_state", "game_saved_state_away"); // the database fails at the end
        core.leave(ben, EndReason.ADMIN);
        assertTrue(ben.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told their things are safe");
        onTheFloor("the row can't be read");
        table("game_saved_state_away", "game_saved_state");
        assertNotNull(dao.loadState(ben.id), "the row is kept for an admin");
    }

    @Test
    void aSnapshotThatCantBeReadPutsThemDownFirst() throws Exception {
        flying();
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, "JUNK".getBytes());
            ps.executeUpdate();
        }
        core.leave(ben, EndReason.QUIT_ITEM);
        assertTrue(ben.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told their things are safe");
        onTheFloor("the snapshot can't be read");
    }

    @Test
    void aSnapshotThatWontGoOnPutsThemDownFirst() {
        flying();
        server.applyFails = true;
        core.leave(ben, EndReason.QUIT_ITEM);
        assertTrue(ben.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told their things are safe");
        onTheFloor("the snapshot won't go on");
    }

    @Test
    void aDisconnectMidAirIsStillRestoredInPlaceWithNoTeleport() throws Exception {
        flying();
        refuse(SavedState.RETURN);
        core.quit(ben); // they are leaving the server: no teleport, the next join finishes it
        assertEquals(RINGS, ben.place, "a quit never teleports");
        assertTrue(server.syncTeleports.isEmpty(), "(no teleport at all)");
    }

    // ---- a watcher the crash left flying -----------------------------------------------------------------

    @Test
    void aCrashedWatcherIsBroughtDownBeforeTheirOwnModeGoesOnSoAFailedTripHomeLeavesThemOnAFloor() {
        FakeServer.Body wes = new FakeServer.Body("Wes", HOME);
        wes.slots[0] = "diamond x3";
        wes.autosave();
        assertNull(core.enter(wes, "clubhouse", "clubhouse", CLUBHOUSE, club), "into the Clubhouse");
        server.step();
        server.arriveAll();
        assertTrue(core.mode(wes, "SPECTATOR"), "Watch live: the session's own mode");
        wes.gameMode = "SPECTATOR";
        wes.place = FLYING;
        wes.autosave(); // watching over a course: SPECTATOR and the spot are in their file
        wes.crash();

        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(wes);
        assertEquals(0, wes.applies, "nothing goes on up there: their own mode would drop them, or leave them in a"
                + " hill they flew through");
        assertEquals("SPECTATOR", wes.gameMode, "a spectator still, who can't fall, on the way down");
        assertEquals(1, after.trips.size(), "brought down first");
        after.arrive(0); // at the Games world's spawn
        Place floor = after.spawn("games");
        assertEquals(List.of(floor), wes.appliedAt, "their own mode and their things went on at the floor");
        assertEquals("SURVIVAL", wes.gameMode, "(their own mode)");
        assertEquals(1, after.trips.size(), "(the trip home is on its way)");
        after.fail(0); // another plugin refused it
        assertTrue(wes.messages.contains(SessionCore.NOT_HOME), "told to try /hcm leave");
        assertEquals(floor, wes.place, "let go on the floor, not at y 95 over a course");

        rebooted.leave(wes, EndReason.COMMAND);
        after.arriveAll();
        assertEquals(HOME, wes.place, "/hcm leave takes them home");
        assertEquals(1, count(wes, "diamond x3"), "their things once");
    }

    // ---- G1 #4: an exit that banks saves the player ------------------------------------------------------

    @Test
    void aSnapshotThatWontGoOnSavesWhatItBankedSoAHardCrashNeverDoublesIt() throws Exception {
        flying();
        ben.slots[3] = MINI; // an auction win delivered mid-game
        ben.autosave(); // Paper's autosave: the Mini beside the "cleared" mark
        server.applyFails = true;
        core.leave(ben, EndReason.QUIT_ITEM); // the Mini is banked, then the snapshot won't go on
        assertEquals(List.of(MINI), server.decode(dao.loadState(ben.id).carry()), "(the Mini is in the carry)");

        ben.crash();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(ben);
        after.arriveAll();
        assertEquals(HOME, ben.place, "home after the crash");
        assertEquals(1, count(ben, MINI), "the numbered Mini comes home once: the bank was saved with the player");
        assertEquals(1, count(ben, "netherite sword"), "and their own things once");
        assertNull(dao.loadState(ben.id), "finished");
    }

    @Test
    void aCrashJoinWhoseSnapshotWontGoOnSavesWhatItBankedSoASecondCrashNeverDoublesIt() throws Exception {
        flying();
        ben.slots[3] = MINI;
        ben.autosave(); // on disk: the Mini, the "cleared" mark
        ben.crash();
        FakeServer after = new FakeServer();
        after.applyFails = true;
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(ben); // their data says it was saved after the clear: the Mini is banked, then the apply fails
        assertEquals(List.of(MINI), server.decode(dao.loadState(ben.id).carry()), "(the Mini is in the carry)");

        ben.crash(); // and the server dies again before its next autosave
        FakeServer again = new FakeServer();
        SessionCore<FakeServer.Body, String> third = new SessionCore<>(dao, again, log);
        third.joined(ben);
        again.arriveAll();
        assertEquals(HOME, ben.place, "home in the end");
        assertEquals(1, count(ben, MINI), "the Mini once");
        assertNull(dao.loadState(ben.id), "finished");
    }

    @Test
    void aWorldChangeWhoseTripBackFailsSavesWhatItBankedSoAHardCrashNeverDoublesIt() throws Exception {
        flying();
        ben.slots[3] = MINI;
        ben.autosave();
        ben.place = Place.of("nether", 0, 64, 0); // out of the session world some way no teleport event showed
        core.worldChanged(ben); // the backstop banks the Mini, then goes back to restore
        server.step();
        assertEquals(1, server.trips.size(), "(the trip back to the session world)");
        server.fail(0);
        assertTrue(ben.messages.contains(SessionCore.NOT_RESTORED), "(told it couldn't be put back yet)");

        ben.crash();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(ben);
        after.arriveAll();
        assertEquals(HOME, ben.place, "home after the crash");
        assertEquals(1, count(ben, MINI), "the Mini once");
        assertNull(dao.loadState(ben.id), "finished");
    }
}
