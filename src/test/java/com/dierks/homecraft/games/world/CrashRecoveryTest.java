package com.dierks.homecraft.games.world;

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
 * A hard crash (power lost, the process killed) in the middle of a Clubhouse visit, walked through the
 * real state machine on a fake server whose players come back as their data file last had them (final
 * gate, #17 and #19):
 * <ul>
 *   <li><b>write, then save</b>: whatever moves a player's things into the carry mid-session (a hand-over
 *       to a race, a death another plugin let happen) saves the player at once, like every restore does.
 *       Otherwise their file still holds the item next to the "cleared" mark, and the crash-join banks
 *       it a second time: two copies of one numbered Mini;</li>
 *   <li><b>never stuck in spectator mode</b>: a watcher's SPECTATOR is the session's own, and after a
 *       crash there is no session left to take it back. When the recovery then fails in place (the
 *       world isn't there yet, the snapshot doesn't read, the database is down) they are put in
 *       adventure mode, unless spectator was their own mode before the game.</li>
 * </ul>
 */
class CrashRecoveryTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    /** The Clubhouse's arrival spot: the session's start. */
    private static final Place CLUBHOUSE = Place.of("games", 5390, 161, 4460);
    /** Where a watcher flies, over a course. */
    private static final Place FLYING = Place.of("games", 40, 95, 40);
    private static final String MINI = "Mini #42 (uid 7f3a)";

    private Connection conn;
    private GamesDao dao;
    private FakeServer server;
    private SessionCore<FakeServer.Body, String> core;
    private final Logger log = quiet();
    private final FakeServer.Game club = new FakeServer.Game("clubhouse");
    private final FakeServer.Game trials = new FakeServer.Game("trials");
    private FakeServer.Body wes;

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
        wes = new FakeServer.Body("Wes", HOME);
        wes.slots[0] = "diamond x3";
        wes.gameMode = "SURVIVAL";
        wes.autosave(); // their data file as they walked up
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private void visit() {
        assertNull(core.enter(wes, "clubhouse", "clubhouse", CLUBHOUSE, club), "into the Clubhouse");
        server.step();
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "in the Clubhouse");
    }

    private void watch() {
        assertTrue(core.mode(wes, "SPECTATOR"), "Watch live: the session's own mode");
        wes.gameMode = "SPECTATOR";
        wes.place = FLYING;
    }

    /** The server dies; the next boot finds the player as their file had them. */
    private SessionCore<FakeServer.Body, String> crash(FakeServer after) {
        wes.crash();
        return new SessionCore<>(dao, after, log);
    }

    private long count(String item) {
        return wes.items().stream().filter(item::equals).count();
    }

    // ---- #17: write, then save ----------------------------------------------------------------------

    @Test
    void aHandOversBankIsSavedAtOnceSoAHardCrashNeverDoublesWhatArrived() throws Exception {
        visit();
        wes.slots[3] = MINI; // an auction win delivered while they wait for Race Night
        wes.autosave(); // Paper's player autosave: the Mini and the "cleared" mark on disk together
        assertTrue(core.bankExtras(wes), "race 1: the hand-over banks the Mini (ClubRaces.seat -> handOut)");
        assertTrue(core.passTo(wes, "trials", "loop", trials), "to the grid");
        assertEquals(List.of(MINI), server.decode(dao.loadState(wes.id).carry()), "the Mini is in the carry");

        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals(HOME, wes.place, "home after the crash");
        assertEquals(1, count(MINI), "the numbered Mini comes home ONCE: the bank was saved with the player");
        assertEquals(1, count("diamond x3"), "and their own things once");
        assertNull(dao.loadState(wes.id), "finished");
    }

    @Test
    void aDeathAnotherPluginLetHappenBanksItsStashAndSavesOnceTheInventoryIsGone() throws Exception {
        visit();
        wes.slots[3] = MINI;
        wes.autosave(); // on disk: the Mini, the "cleared" mark
        assertTrue(core.dying(wes, List.of(MINI)), "a session player's death: what would drop is kept aside");
        core.died(wes, false); // another plugin let the death happen: the stash goes to the carry
        wes.slots = new String[FakeServer.SLOTS]; // the server then empties the inventory (nothing drops)
        wes.dead = true;
        server.step();
        assertEquals(List.of(MINI), server.decode(dao.loadState(wes.id).carry()), "the Mini is in the carry");

        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        wes.dead = false; // back on their feet at the next join
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals(HOME, wes.place, "home after the crash");
        assertEquals(1, count(MINI), "the Mini once: the empty inventory was saved after the death");
        assertNull(dao.loadState(wes.id), "finished");
    }

    // ---- #19: never stuck in spectator mode -----------------------------------------------------------

    @Test
    void aWatcherWhoseCrashRecoveryFailsBecauseTheWorldIsntThereIsNotLeftInSpectatorMode() throws Exception {
        visit();
        watch();
        wes.autosave(); // watching for more than five minutes: SPECTATOR is in their file
        FakeServer after = new FakeServer();
        after.worlds.remove("games"); // Multiverse hasn't loaded the Games world yet
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        assertEquals("SPECTATOR", wes.gameMode, "(the crash left them in the session's spectator mode)");
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals("ADVENTURE", wes.gameMode, "the recovery failed in place, but never in spectator mode");
        assertEquals(FLYING, wes.place, "they stay where they are (fail in place)");
        assertNotNull(dao.loadState(wes.id), "the row is kept for an admin");
        assertTrue(wes.messages.contains(SessionCore.SAFE_WITH_ADMIN), "and they are told their things are safe");
    }

    @Test
    void aWatcherWhoseSnapshotNoLongerReadsIsNotLeftInSpectatorMode() throws Exception {
        visit();
        watch();
        wes.autosave();
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, "JUNK".getBytes()); // an upgrade the old item blob no longer reads under
            ps.executeUpdate();
        }
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals("ADVENTURE", wes.gameMode, "no flying through bases until an admin helps");
        assertEquals(FLYING, wes.place, "left where they are");
    }

    @Test
    void aWatcherWhoseSavedStateCantBeReadAtAllIsNotLeftInSpectatorMode() throws Exception {
        visit();
        watch();
        wes.autosave();
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state RENAME TO game_saved_state_away"); // the database fails
        }
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        rebooted.joined(wes);
        assertEquals("ADVENTURE", wes.gameMode, "in a Games world with a row nobody can read: not in spectator mode");
        assertEquals(FLYING, wes.place, "left where they are");
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state_away RENAME TO game_saved_state");
        }
        assertNotNull(dao.loadState(wes.id), "and nothing was lost");
    }

    @Test
    void aFailedRecoveryLeavesAPlayersOwnSpectatorModeAlone() throws Exception {
        wes.gameMode = "SPECTATOR"; // an admin, spectating, who stepped into the Clubhouse
        visit();
        assertEquals("SPECTATOR", dao.loadState(wes.id).gameMode(), "(their own mode is in the row)");
        FakeServer after = new FakeServer();
        after.worlds.remove("games");
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        wes.gameMode = "SPECTATOR";
        rebooted.joined(wes);
        assertEquals("SPECTATOR", wes.gameMode, "spectator was their own before the game: left alone");
    }

    @Test
    void aWatcherWhoseRecoveryWorksGetsTheirOwnModeBackAsBefore() throws Exception {
        visit();
        watch();
        wes.autosave();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals("SURVIVAL", wes.gameMode, "the saved state's own mode");
        assertEquals(HOME, wes.place, "home");
        assertEquals(1, count("diamond x3"), "their things once");
        assertNull(dao.loadState(wes.id), "finished");
    }
}
