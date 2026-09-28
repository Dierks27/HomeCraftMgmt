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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world sessions when something fails underneath them: a row write the database refuses (a
 * SQLite trigger stands in for a full disk), a snapshot or a carry that no longer reads, a capture
 * that throws. These started as the F3 review's probes, each showing a way to lose or duplicate a
 * player's things; each now pins the fix.
 *
 * <p>Pinned: a failed RETURN write never sends the player home and nothing applies that snapshot
 * again, in this run or after a restart; a failed DONE write hands nothing over, and the retry
 * hands it over once; an unreadable snapshot or carry fails in place instead of pulling the player
 * into the Games world on every join; an admin restore banks what the player gathered since the
 * failure, and the admin hears what really happened; a capture that throws sends the player back
 * as they were instead of leaving them stuck ENTERING.
 */
class SessionCoreFailureTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    private static final Place START = Place.of("games", 100, 70, 100);

    private Connection conn;
    private GamesDao dao;
    private FakeServer server;
    private SessionCore<FakeServer.Body, String> core;
    private final Logger log = quietLogger();
    private final FakeServer.Game trials = new FakeServer.Game("trials");
    private FakeServer.Body alice;
    private boolean captureThrows;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
        server = new FakeServer();
        server.beforeCapture = () -> {
            if (captureThrows) {
                // ItemStack#serializeAsBytes throws for a stack over 99 (ItemStack.CODEC's count range)
                throw new IllegalStateException("Value must be within range [1;99]: 128");
            }
        };
        core = new SessionCore<>(dao, server, log);
        alice = new FakeServer.Body("Alice", HOME);
        alice.slots[0] = "diamond x3";
        alice.slots[1] = "bread x5";
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private static Logger quietLogger() {
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        return l;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private void play() {
        assertNull(core.enter(alice, "trials", "r", START, trials), "a still, safe player can start");
        server.step();
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(alice.id), "playing");
    }

    private SavedState row() throws Exception {
        return dao.loadState(alice.id);
    }

    private long count(String item) {
        return alice.items().stream().filter(item::equals).count();
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

    private void setBlob(String column, byte[] blob) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET " + column + " = ?")) {
            ps.setBytes(1, blob);
            ps.executeUpdate();
        }
    }

    private void rejoin(SessionCore<FakeServer.Body, String> c) {
        c.quit(alice);
        alice.online = true;
        c.joined(alice);
    }

    // ---- entering -----------------------------------------------------------------------------------

    @Test
    void aCaptureThatThrowsSendsThemBackAsTheyWereInsteadOfLeavingThemStuckEntering() throws Exception {
        captureThrows = true;
        assertNull(core.enter(alice, "trials", "r", START, trials), "the entry starts");
        server.step();
        server.arrive(0); // at the start: the capture throws
        assertNull(core.phase(alice.id), "the entry is cleared, not stuck ENTERING");
        assertNull(row(), "nothing was saved");
        assertEquals(List.of("diamond x3", "bread x5"), alice.items(), "and nothing about her changed");
        assertEquals(0, trials.ready, "the game never started");
        assertTrue(alice.messages.contains("&c" + SessionCore.CANT_START), "told it couldn't start");
        server.arriveAll();
        assertEquals(HOME, alice.place, "sent back where she was");

        captureThrows = false;
        play(); // and she can start again at once
    }

    // ---- a RETURN write that fails ------------------------------------------------------------------

    @Test
    void aFailedReturnWriteKeepsThemWhereTheyAreAndNothingAppliesItAgain() throws Exception {
        play();
        refuse(SavedState.RETURN);
        core.leave(alice, EndReason.COMMAND);
        server.arriveAll();
        assertEquals(1, alice.applies, "the snapshot went on");
        assertTrue(alice.items().contains("diamond x3"), "so her things are back on her");
        assertEquals(START, alice.place, "but she isn't sent home while the row still says ACTIVE");
        assertEquals(SavedState.ACTIVE, row().phase(), "the write failed");
        assertTrue(alice.messages.contains(SessionCore.BACK_STAY), "told her things are back and to /hcm leave in a moment");
        assertNull(core.session(alice.id), "the game is over");
        assertFalse(core.home(alice.id), "not home");
        assertEquals(SessionCore.STILL_SENDING, core.enter(alice, "trials", "r", START, trials), "no new game meanwhile");
        assertTrue(core.adminRestore(alice).contains("put back already"), "an admin can't apply it again either");

        allow(SavedState.RETURN);
        alice.slots[1] = null; // she eats the bread and finds netherite meanwhile
        alice.slots[7] = "netherite ingot";
        rejoin(core);
        server.arriveAll();
        assertEquals(1, alice.applies, "the next join never applies it a second time");
        assertTrue(alice.items().contains("netherite ingot"), "what she gained is kept");
        assertEquals(0, count("bread x5"), "and the old snapshot isn't put back over it");
        assertEquals(HOME, alice.place, "the join wrote RETURN and sent her home");
        assertNull(row(), "finished");
    }

    @Test
    void aFailedReturnWriteSurvivesARestartThroughThePlayersOwnData() throws Exception {
        play();
        refuse(SavedState.RETURN);
        core.leave(alice, EndReason.COMMAND);
        assertEquals(SessionCore.APPLIED + row().sessionId(), alice.mark, "her own data says the snapshot is on her");
        allow(SavedState.RETURN);
        alice.slots[7] = "netherite ingot";

        FakeServer after = new FakeServer(); // a restart forgets everything in memory
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(alice);
        after.arriveAll();
        assertEquals(1, alice.applies, "not applied again after the restart either");
        assertEquals(1, count("diamond x3"), "nothing doubled (a crash-join bank would have)");
        assertTrue(alice.items().contains("netherite ingot"), "nothing lost");
        assertEquals(HOME, alice.place, "home");
        assertNull(row(), "finished");
        assertNull(alice.mark, "and the mark is cleared once it's done");
    }

    @Test
    void leaveRetriesAFailedReturnWriteAndKeepsThemWhileItStillFails() throws Exception {
        play();
        refuse(SavedState.RETURN);
        core.leave(alice, EndReason.QUIT_ITEM);
        alice.messages.clear();
        core.leave(alice, EndReason.COMMAND); // /hcm leave while the disk is still full
        assertEquals(START, alice.place, "still not sent home");
        assertTrue(alice.messages.contains(SessionCore.BACK_STAY), "told again");
        assertEquals(SessionCore.ADMIN_WRITE, core.adminReturn(alice), "an admin hears what really happened");

        allow(SavedState.RETURN);
        core.leave(alice, EndReason.COMMAND);
        server.arriveAll();
        assertEquals(HOME, alice.place, "home once RETURN is written");
        assertEquals(1, alice.applies, "applied once in all");
        assertNull(row(), "finished");
    }

    @Test
    void aReturnRowIsNeverAppliedAgainWhateverPathTouchesIt() throws Exception {
        play();
        core.quit(alice);
        alice.online = true;
        core.joined(alice);
        core.respawned(alice);
        core.worldsReady(List.of(alice));
        core.leave(alice, EndReason.COMMAND);
        core.adminRestore(alice);
        server.steps(10);
        server.arriveAll();
        assertEquals(1, alice.applies, "join, respawn, worlds-up, /hcm leave and an admin: applied once");
        assertNull(row(), "finished");
    }

    // ---- a DONE write that fails --------------------------------------------------------------------

    @Test
    void aFailedDoneWriteHandsNothingOverAndTheRetryHandsItOverOnce() throws Exception {
        play();
        alice.slots[20] = "Mini #42"; // delivered mid-game
        refuse(SavedState.DONE);
        core.leave(alice, EndReason.COMMAND);
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertEquals(0, count("Mini #42"), "nothing handed over while DONE can't be written");
        assertEquals(SavedState.RETURN, row().phase(), "the row still holds it");
        assertEquals(List.of("Mini #42"), server.decode(row().carry()), "all of it");
        assertTrue(alice.messages.contains(SessionCore.NOT_DONE), "told to /hcm leave to try again");

        allow(SavedState.DONE);
        rejoin(core);
        server.arriveAll();
        assertEquals(1, count("Mini #42"), "handed over exactly once");
        assertEquals(1, count("diamond x3"), "her own things once");
        assertNull(row(), "finished");
    }

    @Test
    void aFailedCarryWriteForWhatDoesntFitHandsNothingOver() throws Exception {
        for (int i = 2; i < FakeServer.STORAGE - 1; i++) {
            alice.slots[i] = "cobble " + i; // room for one
        }
        play();
        alice.slots[20] = "Mini #42";
        alice.slots[21] = "Mini #43";
        core.leave(alice, EndReason.COMMAND);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_carry BEFORE UPDATE OF carry ON game_saved_state "
                    + "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");
        }
        server.arriveAll();
        assertEquals(0, count("Mini #42") + count("Mini #43"), "keeping the rest failed: none of it is given");
        assertEquals(List.of("Mini #42", "Mini #43"), server.decode(row().carry()), "the row still holds both");
        assertTrue(alice.dropped.isEmpty(), "and nothing is on the ground");
    }

    // ---- what can't be read -------------------------------------------------------------------------

    @Test
    void anUnreadableSnapshotFailsInPlaceOnEveryJoinInsteadOfPullingThemIn() throws Exception {
        play();
        setBlob("items", "JUNK".getBytes());
        core.leave(alice, EndReason.COMMAND);
        assertEquals(SavedState.ACTIVE, row().phase(), "kept for an admin");
        alice.place = HOME; // she walks home and gathers things
        alice.slots[3] = "oak log x64";
        int trips = server.started;
        for (int login = 0; login < 3; login++) {
            rejoin(core);
            server.arriveAll();
            assertEquals(HOME, alice.place, "login " + login + ": left where she is");
        }
        assertEquals(trips, server.started, "not one teleport into the Games world");
        assertTrue(alice.items().contains("oak log x64"), "what she gathered is untouched");
        assertEquals(4, alice.messages.stream().filter(SessionCore.SAFE_WITH_ADMIN::equals).count(),
                "told her things are safe when the game ended and again on every join");
        assertEquals(SessionCore.ADMIN_UNREADABLE, core.adminRestore(alice), "and an admin hears the real reason");
        assertEquals(trips, server.started, "still no teleport");
    }

    @Test
    void anAdminRestoreBanksWhatTheyGatheredSinceAFailureBeforeItOverwrites() throws Exception {
        play();
        byte[] good = row().items();
        setBlob("items", "JUNK".getBytes());
        core.leave(alice, EndReason.COMMAND); // failed: left as she is, the row ACTIVE
        alice.place = HOME;
        alice.slots[3] = "oak log x64"; // gathered afterwards
        setBlob("items", good); // an admin fixed the blob
        String said = core.adminRestore(alice);
        assertTrue(said.startsWith("&a") && said.contains("games"), "the admin hears she is taken to the session world: " + said);
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertEquals(1, count("diamond x3"), "her saved things are back");
        assertEquals(1, count("oak log x64"), "and what she gathered since is kept, not overwritten");
        assertNull(row(), "finished");
    }

    @Test
    void leaveAfterAFailureAlsoBanksWhatTheyHoldFirst() throws Exception {
        play();
        byte[] good = row().items();
        setBlob("items", "JUNK".getBytes());
        core.leave(alice, EndReason.COMMAND);
        alice.slots[3] = "oak log x64"; // still in the Games world
        setBlob("items", good);
        core.leave(alice, EndReason.COMMAND); // /hcm leave tries again
        server.arriveAll();
        assertEquals(1, count("diamond x3"), "restored");
        assertEquals(1, count("oak log x64"), "and what she held is handed back at home");
        assertNull(row(), "finished");
    }

    @Test
    void anUnreadableCarryFailsInPlaceAndWaitsForAnAdmin() throws Exception {
        play();
        core.quit(alice); // restored in place, RETURN
        setBlob("carry", "JUNK".getBytes());
        alice.place = HOME;
        int trips = server.started;
        rejoin(core);
        rejoin(core);
        core.leave(alice, EndReason.COMMAND);
        assertEquals(trips, server.started, "no trip home that would fail again on every join");
        assertEquals(SavedState.RETURN, row().phase(), "the row is kept");
        assertTrue(alice.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told an admin will help");
        assertEquals(SessionCore.ADMIN_CARRY, core.adminReturn(alice), "and the admin hears why");
        assertEquals("&aDiscarded their saved things.", core.adminDiscard(alice.id, "Alice", "Admin"), "which they can clear");
        assertTrue(core.home(alice.id), "then she is free");
    }

    @Test
    void anAdminIsToldWhenTheSessionWorldIsGone() throws Exception {
        play();
        alice.dead = true;
        core.quit(alice); // a dead player is never restored: ACTIVE
        alice.dead = false;
        alice.online = true;
        alice.place = HOME;
        server.worlds.remove("games");
        String said = core.adminRestore(alice);
        assertTrue(said.startsWith("&c") && said.contains("gone"), "the admin hears the world is gone: " + said);
        assertEquals(SavedState.ACTIVE, row().phase(), "the row is kept");
        assertEquals(HOME, alice.place, "and she isn't moved");
    }

    // ---- the detour ---------------------------------------------------------------------------------

    @Test
    void aStopWhileInTheWorldChangeDetourLosesNothing() throws Exception {
        play();
        alice.slots[5] = "Mini #7";
        alice.place = Place.of("nether", 0, 64, 0);
        core.worldChanged(alice);
        server.stopping = true;
        core.stop();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.worldsReady(List.of(alice));
        after.arriveAll();
        assertEquals(HOME, alice.place, "home after the restart");
        assertEquals(1, count("Mini #7"), "the Mini once");
        assertEquals(1, count("diamond x3"), "her things once");
        assertNull(row(), "finished");
    }

    @Test
    void aQuitWhileInTheWorldChangeDetourLosesNothing() throws Exception {
        play();
        alice.slots[5] = "Mini #7";
        alice.place = Place.of("nether", 0, 64, 0);
        core.worldChanged(alice);
        rejoin(core);
        server.steps(3);
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertEquals(1, count("Mini #7"), "the Mini once");
        assertEquals(1, count("diamond x3"), "her things once");
        assertNull(row(), "finished");
    }

    @Test
    void anUnreadableSnapshotFoundInTheDetourLeavesThemWhereTheyWent() throws Exception {
        play();
        alice.slots[5] = "Mini #7";
        setBlob("items", "JUNK".getBytes());
        Place nether = Place.of("nether", 0, 64, 0);
        alice.place = nether;
        core.worldChanged(alice);
        server.steps(2);
        assertTrue(server.trips.isEmpty(), "not taken back into the Games world for a restore that can't happen");
        assertEquals(nether, alice.place, "left where she went");
        assertEquals(List.of("Mini #7"), server.decode(row().carry()), "what arrived mid-game is safe in the row");
        assertTrue(alice.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told an admin will help");
    }
}
