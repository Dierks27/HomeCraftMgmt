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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *       adventure mode, unless spectator was their own mode before the game;</li>
 *   <li><b>never dropped</b> (#19, second pass): the crash left them where they were flying, and out of
 *       any session nothing spares a fall in a Games world. They are brought down to a floor (the
 *       spawn of the world they are in) by our own trip first, still a spectator on the way, and only
 *       then put in adventure mode. A trip that fails leaves them a spectator, who can't fall.</li>
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
        if (!after.worldExists(wes.place.world())) {
            wes.place = after.mainSpawn(); // Paper: a player whose world isn't loaded joins at the main spawn
        }
        return new SessionCore<>(dao, after, log);
    }

    /** A crashed watcher, back in the Games world where their file had them: in the air, a spectator. */
    private SessionCore<FakeServer.Body, String> crashWatching(FakeServer after) {
        visit();
        watch();
        wes.autosave(); // watching for more than five minutes: SPECTATOR and the spot they flew to are in their file
        SessionCore<FakeServer.Body, String> rebooted = crash(after);
        assertEquals(FLYING, wes.place, "(the crash left them where they were flying)");
        assertEquals("SPECTATOR", wes.gameMode, "(in the session's spectator mode)");
        return rebooted;
    }

    /** The recovery failed in place: they come down to the floor first, and only there leave spectator mode. */
    private void broughtDown(FakeServer after, SessionCore<FakeServer.Body, String> rebooted, String why) {
        assertEquals("SPECTATOR", wes.gameMode, why + ": not dropped where they were flying (a spectator can't fall)");
        assertEquals(FLYING, wes.place, why + ": (the trip down is on its way)");
        assertTrue(rebooted.recovering(wes.id), why + ": on the way down nothing else starts, and a fall is spared");
        after.arriveAll();
        Place floor = after.spawn("games");
        assertEquals(floor, wes.place, why + ": brought down to a floor, the Games world's spawn, never left at y 95");
        assertEquals("ADVENTURE", wes.gameMode, why + ": then out of spectator mode, not flying through bases");
        assertEquals(floor, wes.modeResetAt, why + ": the mode changed only once they were on the floor");
        assertFalse(rebooted.recovering(wes.id), why + ": nothing left on the way");
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
        assertEquals(after.mainSpawn(), wes.place, "(and the server put them on the main world's spawn)");
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals("ADVENTURE", wes.gameMode, "the recovery failed in place, but never in spectator mode");
        assertEquals(after.mainSpawn(), wes.place, "on a floor: the spawn of the world the server put them in");
        assertEquals(after.mainSpawn(), wes.modeResetAt, "out of spectator mode only there");
        assertNotNull(dao.loadState(wes.id), "the row is kept for an admin");
        assertTrue(wes.messages.contains(SessionCore.SAFE_WITH_ADMIN), "and they are told their things are safe");
    }

    @Test
    void aWatcherWhoseSnapshotNoLongerReadsIsBroughtDownThenOutOfSpectatorMode() throws Exception {
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crashWatching(after);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, "JUNK".getBytes()); // an upgrade the old item blob no longer reads under
            ps.executeUpdate();
        }
        rebooted.joined(wes);
        broughtDown(after, rebooted, "the snapshot doesn't read");
        assertNotNull(dao.loadState(wes.id), "the row is kept for an admin");
        assertTrue(wes.messages.contains(SessionCore.SAFE_WITH_ADMIN), "and they are told their things are safe");
    }

    @Test
    void aWatcherWhoseSavedStateCantBeReadAtAllIsBroughtDownThenOutOfSpectatorMode() throws Exception {
        visit();
        watch();
        wes.autosave();
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state RENAME TO game_saved_state_away"); // the database fails
        }
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crash(after); // at boot: the rows aren't known
        rebooted.joined(wes);
        broughtDown(after, rebooted, "a Games world, a body a game cleared, and a row nobody can read");
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state_away RENAME TO game_saved_state");
        }
        assertNotNull(dao.loadState(wes.id), "and nothing was lost");
    }

    @Test
    void aWatcherWhoseRestoreInTheirSessionWorldCantReadTheRowAgainIsBroughtDown() throws Exception {
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crashWatching(after);
        after.beforePrepare = () -> { // the first read worked; the database fails before the restore's own
            after.beforePrepare = null;
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE game_saved_state RENAME TO game_saved_state_away");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        rebooted.joined(wes);
        broughtDown(after, rebooted, "the restore in place couldn't read the row again");
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state_away RENAME TO game_saved_state");
        }
        assertNotNull(dao.loadState(wes.id), "and nothing was lost");
    }

    @Test
    void aWatcherWhoseRestoreInTheirSessionWorldReadsAnUnreadableSnapshotIsBroughtDown() throws Exception {
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crashWatching(after);
        after.beforePrepare = () -> { // the first decode worked; the row changed before the restore's own
            after.beforePrepare = null;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
                ps.setBytes(1, "JUNK".getBytes());
                ps.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        rebooted.joined(wes);
        broughtDown(after, rebooted, "the restore in place read a snapshot that doesn't decode");
        assertNotNull(dao.loadState(wes.id), "the row is kept for an admin");
    }

    @Test
    void aWatcherWhoseSnapshotWontGoOnIsBroughtDown() throws Exception {
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crashWatching(after);
        after.applyFails = true; // it throws before it gets as far as their mode
        rebooted.joined(wes);
        broughtDown(after, rebooted, "putting it back failed");
        assertEquals(0, wes.applies, "(nothing went on)");
        assertNotNull(dao.loadState(wes.id), "the row is kept for an admin");
        assertTrue(wes.messages.contains(SessionCore.SAFE_WITH_ADMIN), "and they are told their things are safe");
    }

    @Test
    void aTripDownThatFailsLeavesThemASpectatorWhoCantFallAndTheNextJoinTriesAgain() throws Exception {
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = crashWatching(after);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, "JUNK".getBytes());
            ps.executeUpdate();
        }
        rebooted.joined(wes);
        assertEquals(1, after.trips.size(), "(a trip down is on its way)");
        after.fail(0); // another plugin refused the teleport
        assertEquals("SPECTATOR", wes.gameMode, "never put in adventure mode in mid-air: flying beats falling");
        assertEquals(FLYING, wes.place, "(still where they were)");
        assertNull(wes.modeResetAt, "their mode was never touched");
        assertFalse(rebooted.recovering(wes.id), "nothing is left on the way, so the next try can start");
        rebooted.joined(wes); // they rejoin
        broughtDown(after, rebooted, "the next join");
    }

    @Test
    void anAdminLookingRoundTheCoursesInTheirOwnSpectatorModeIsLeftAloneWhileTheDatabaseFails() throws Exception {
        FakeServer.Body ada = new FakeServer.Body("Ada", FLYING);
        ada.gameMode = "SPECTATOR"; // her own, over a course; no game ever cleared her
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state RENAME TO game_saved_state_away"); // down at boot
        }
        SessionCore<FakeServer.Body, String> booted = new SessionCore<>(dao, server, log);
        assertTrue(booted.hasRow(ada.id), "(with the rows unknown, every join asks, and a failed read says maybe)");
        booted.joined(ada);
        assertEquals(0, server.started, "nobody moves her");
        assertEquals(FLYING, ada.place, "she stays where she is");
        assertEquals("SPECTATOR", ada.gameMode, "in her own spectator mode: no game's mark in her data");
        assertNull(ada.modeResetAt, "never touched");
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE game_saved_state_away RENAME TO game_saved_state");
        }
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
