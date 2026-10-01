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
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every path into and out of a world game, walked through a fake server against the real saved-
 * state table (spec §7.2, §7.5, §7.6, §14 "Saved state and sessions"). These are the paths that
 * can lose or duplicate a player's things, so each test checks the two things that matter: what
 * the player holds at the end, and what the row says.
 *
 * <p>Pinned: enter saves before it changes anything and clears only after; quit restores in place
 * and the next join sends them home; a crash-join restores once, overwrite-only, and only in the
 * session world; a RETURN row is never applied again; death is cancelled and leaves normally, and
 * a player who is dead anyway keeps an ACTIVE row until the respawn; racing entries are refused
 * and a late callback from an old session can't touch a new one; things that arrived mid-game come
 * back (what doesn't fit waits in the row for {@code /hcm leave}, never on the ground); a crash-join
 * banks what arrived after the clear; stop while stopping never teleports; a reload that closes
 * games sends them home at once; foreign teleports (into another Games world the carry waits) and
 * world changes; the void; an unreadable snapshot is kept for an admin; DONE rows are pruned after
 * a week; the kit never survives any path. When a write fails or a blob can't be read, see
 * {@link SessionCoreFailureTest}.
 */
class SessionCoreTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    private static final Place START = Place.of("games", 100, 70, 100);

    private Connection conn;
    private GamesDao dao;
    private FakeServer server;
    private SessionCore<FakeServer.Body, String> core;
    private final Logger log = quietLogger();
    private final FakeServer.Game trials = new FakeServer.Game("trials");
    private FakeServer.Body alice;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
        server = new FakeServer();
        core = new SessionCore<>(dao, server, log);
        alice = new FakeServer.Body("Alice", HOME);
        alice.slots[0] = "diamond x3";
        alice.slots[1] = "bread x5";
        alice.slots[39] = "iron helmet";
        alice.slots[40] = "shield";
        alice.effects.add(new SavedStateCodec.Effect("minecraft:speed", 0, 600, false, true, true));
        alice.allowFlight = true;
        alice.flying = true;
        alice.gameMode = "CREATIVE";
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

    private String[] original() {
        String[] s = new String[FakeServer.SLOTS];
        s[0] = "diamond x3";
        s[1] = "bread x5";
        s[39] = "iron helmet";
        s[40] = "shield";
        return s;
    }

    /** Enter and arrive: Alice is in the game. */
    private void play() {
        assertNull(core.enter(alice, "trials", "river_run", START, trials), "a still, safe player can start");
        server.step(); // the entry leaves a tick later
        assertEquals(1, server.trips.size(), "one teleport to the start");
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(alice.id), "arrived, saved, cleared: playing");
    }

    private SavedState row() throws Exception {
        return dao.loadState(alice.id);
    }

    private int doneRows() throws Exception {
        return dao.statesInPhase(SavedState.DONE).size();
    }

    private void assertHomeWithEverything(String why) throws Exception {
        assertHomeWithEverything(why, core);
    }

    private void assertHomeWithEverything(String why, SessionCore<FakeServer.Body, String> c) throws Exception {
        assertEquals(HOME, alice.place, why + ": back where they started");
        assertArrayEquals(original(), Arrays.copyOf(alice.slots, FakeServer.SLOTS), why + ": exactly their own things, once");
        assertEquals("CREATIVE", alice.gameMode, why + ": their own game mode");
        assertTrue(alice.allowFlight && alice.flying, why + ": flying again, as they were");
        assertEquals(12, alice.level, why + ": their XP");
        assertEquals(1, alice.effects.size(), why + ": their effects");
        assertFalse(alice.holdsKit(), why + ": no kit item survives");
        assertNull(row(), why + ": no live row is left");
        assertNull(c.session(alice.id), why + ": no session is left");
    }

    // ---- entering -----------------------------------------------------------------------------------

    @Test
    void enteringSavesTheStateBeforeItClearsAnything() throws Exception {
        play();
        SavedState s = row();
        assertNotNull(s, "the row is written on arrival");
        assertEquals(SavedState.ACTIVE, s.phase(), "a fresh session is ACTIVE");
        assertEquals("games", s.sessionWorld(), "taken in the world of the start (Multiverse-Inventories' group)");
        assertEquals(HOME, SavedStateCodec.from(s), "the return point is where they stood before the teleport");
        assertArrayEquals(original(), FakeServer.slots(s.items()), "captured BEFORE the clear: their real things");
        assertEquals("CREATIVE", s.gameMode(), "and their own game mode");
        assertEquals("ADVENTURE", alice.gameMode, "then ADVENTURE");
        assertEquals(List.of("kit:trials:checkpoint", "kit:trials:leave", "kit:trials:elytra"), alice.items(),
                "then emptied, and only the kit given");
        assertFalse(alice.allowFlight, "no flight in a game");
        assertEquals(20.0, alice.health, "full health");
        assertEquals(0, alice.level, "no XP");
        assertTrue(alice.effects.isEmpty(), "no effects");
        assertEquals(1, trials.ready, "the game starts once");
        assertEquals(Session.Phase.ACTIVE, core.session(alice.id).phase(), "the session is live");
        assertEquals("river_run", core.session(alice.id).ref(), "for its course");
    }

    @Test
    void somethingOnTheCursorOrInTheGridIsRefusedBeforeTheInventoryCloses() throws Exception {
        for (int i = 2; i < FakeServer.STORAGE; i++) {
            alice.slots[i] = "cobble " + i; // full: closing would have to drop what is held
        }
        alice.cursor = "gold ingot";
        alice.grid[2] = "stick x2";
        assertNull(core.enter(alice, "trials", "", START, trials), "the entry starts");
        server.step();
        assertTrue(server.trips.isEmpty(), "but never leaves");
        assertNull(core.session(alice.id), "the entry is cleared");
        assertNull(row(), "nothing saved");
        assertEquals("gold ingot", alice.cursor, "the cursor is left as it was");
        assertEquals("stick x2", alice.grid[2], "and the grid");
        assertTrue(alice.dropped.isEmpty(), "nothing was dropped (a full inventory can't take them back)");
        assertTrue(alice.messages.contains("&c" + SessionCore.HANDS), "told to put them down first");

        alice.slots[2] = "gold ingot"; // put down
        alice.slots[3] = "stick x2";
        alice.cursor = null;
        alice.grid[2] = null;
        play();
        List<String> saved = Arrays.stream(FakeServer.slots(row().items())).filter(s -> s != null).toList();
        assertTrue(saved.contains("gold ingot") && saved.contains("stick x2"), "then it all goes into what is saved");
    }

    @Test
    void anEntryWithSomethingStillInHandIsRefusedWithNothingChanged() throws Exception {
        alice.stickyCursor = true;
        alice.cursor = "gold ingot";
        assertNull(core.enter(alice, "trials", "", START, trials), "the entry starts");
        server.step();
        assertTrue(server.trips.isEmpty(), "but never leaves: the cursor didn't go home");
        assertNull(core.session(alice.id), "the entry is cleared");
        assertNull(row(), "nothing saved");
        assertEquals("gold ingot", alice.cursor, "nothing touched");
        assertTrue(alice.messages.contains("&c" + SessionCore.HANDS), "and told why");
    }

    @Test
    void anUnsafePlayerIsRefusedBeforeAnythingHappens() throws Exception {
        alice.standing = new SessionCore.Standing(false, false, false, false, false, false, 7f, false, 0, false,
                false, Long.MAX_VALUE);
        assertEquals(SessionCore.SAFE, core.enter(alice, "trials", "", START, trials), "falling: stand still first");
        assertNull(core.session(alice.id), "no entry");
        server.step();
        assertTrue(server.trips.isEmpty(), "no teleport");
    }

    @Test
    void aSecondEntryIsRefusedInEveryPhaseAndWhileARowIsLive() throws Exception {
        assertNull(core.enter(alice, "trials", "", START, trials), "the first entry starts");
        assertEquals(SessionCore.IN_SESSION, core.enter(alice, "golf", "", START, trials),
                "a double click while the first is on its way (ENTERING) is refused");
        server.step();
        assertEquals(SessionCore.IN_SESSION, core.enter(alice, "golf", "", START, trials), "still refused mid-teleport");
        server.arriveAll();
        assertEquals(SessionCore.IN_SESSION, core.enter(alice, "golf", "", START, trials), "refused while playing");
        core.quit(alice); // restored in place, RETURN
        alice.online = true;
        assertEquals(SessionCore.STILL_SENDING, core.enter(alice, "golf", "", START, trials),
                "refused while a RETURN row is still sending them back");
        assertEquals(1, server.started, "only the first entry ever travelled");
    }

    @Test
    void aRowThatAppearsDuringTheTripCallsTheEntryOffWithNothingChanged() throws Exception {
        assertNull(core.enter(alice, "trials", "", START, trials), "the entry starts");
        server.step();
        SavedState other = new SavedState(alice.id, "golf", "", SavedState.ACTIVE, "someone-else", "games",
                FakeServer.blob(new String[FakeServer.SLOTS]), null, 0, 0, 0, 20, 20, 5, 0, 0, 300, "SURVIVAL",
                false, false, 0.2f, 0.1f, 0, "", "world", 0, 64, 0, 0, 0, server.now, null);
        assertTrue(dao.saveState(other), "another row lands while Alice travels");
        server.arrive(0);
        assertNull(core.session(alice.id), "the entry is called off");
        assertEquals("someone-else", row().sessionId(), "the other row is untouched");
        assertEquals("CREATIVE", alice.gameMode, "nothing about Alice changed");
        assertArrayEquals(original(), Arrays.copyOf(alice.slots, FakeServer.SLOTS), "not even her inventory");
        assertEquals(1, server.trips.size(), "she is sent back where she was");
        server.arriveAll();
        assertEquals(HOME, alice.place, "and is home");
    }

    @Test
    void thePlainInsertRefusesARowThatRacedInAtTheLastMoment() throws Exception {
        assertNull(core.enter(alice, "trials", "", START, trials), "the entry starts");
        server.step();
        server.beforeCapture = () -> {
            try {
                dao.saveState(new SavedState(alice.id, "golf", "", SavedState.ACTIVE, "raced", "games", null, null,
                        0, 0, 0, 20, 20, 5, 0, 0, 300, "SURVIVAL", false, false, 0.2f, 0.1f, 0, "", "world", 0, 64,
                        0, 0, 0, server.now, null));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        server.arriveAll();
        assertEquals("raced", row().sessionId(), "the INSERT is refused, never an upsert over the live row");
        assertNull(core.session(alice.id), "the entry is called off");
        assertEquals("CREATIVE", alice.gameMode, "before anything about Alice changed");
        assertEquals(0, trials.ready, "the game never started");
    }

    @Test
    void anEntryCalledOffOnTheWayChangesNothingAndSendsThemBack() throws Exception {
        assertNull(core.enter(alice, "trials", "", START, trials), "the entry starts");
        server.step();
        core.leave(alice, EndReason.COMMAND); // /hcm leave while travelling
        assertEquals(Session.Phase.ENTERING, core.phase(alice.id), "kept until the trip resolves, so nothing races it");
        server.arrive(0);
        assertNull(core.session(alice.id), "never started");
        assertNull(row(), "never saved");
        assertEquals(1, server.trips.size(), "sent back");
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertEquals("CREATIVE", alice.gameMode, "unchanged");
    }

    @Test
    void aQuitWhileTravellingInLeavesNothingBehind() throws Exception {
        assertNull(core.enter(alice, "trials", "", START, trials), "the entry starts");
        server.step();
        alice.online = false;
        core.quit(alice);
        server.arriveAll();
        assertNull(row(), "nothing was saved");
        assertNull(core.session(alice.id), "and nothing is in memory");
    }

    // ---- quit, join, crash ----------------------------------------------------------------------------

    @Test
    void enterThenQuitThenJoinBringsEverythingBack() throws Exception {
        play();
        core.quit(alice); // PlayerQuitEvent, LOWEST
        assertEquals(SavedState.RETURN, row().phase(), "restored in place and marked RETURN in the same tick");
        assertArrayEquals(original(), Arrays.copyOf(alice.slots, FakeServer.SLOTS),
                "their own things are on them as they are saved on quit (the kit is gone)");
        assertEquals(START.world(), alice.place.world(), "no teleport while quitting");
        assertTrue(server.trips.isEmpty() && server.syncTeleports.isEmpty(), "not one");
        assertEquals(List.of(EndReason.DISCONNECT), trials.ended, "the game hears it ended");
        assertTrue(alice.saves > 0, "the restored state is written to disk at once");

        alice.online = true;
        core.joined(alice); // one tick after the join
        assertEquals(1, alice.applies, "a RETURN row is never applied again");
        assertEquals(1, server.trips.size(), "only sent home");
        server.arriveAll();
        assertHomeWithEverything("after the next join");
        assertEquals(1, doneRows(), "the row is DONE, kept a week for support");
    }

    @Test
    void aCrashJoinRestoresOnceOnlyInTheSessionWorldThenSendsThemHome() throws Exception {
        play();
        // The server dies. The disk had the game state; a spawn plugin puts her in the hub on join.
        FakeServer after = new FakeServer();
        after.now = server.now + 60_000;
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        alice.place = Place.of("world", 0, 64, 0);
        assertTrue(rebooted.hasRow(alice.id), "the recovery set is loaded at enable");

        rebooted.joined(alice);
        assertEquals(0, alice.applies, "never applied outside the session world");
        assertEquals(1, after.trips.size(), "first a trip to the session world");
        assertEquals("games", after.trips.get(0).to().world(), "the snapshot's own world");
        after.arrive(0);
        assertEquals(List.of("games"), alice.appliedIn, "applied once, in the session world");
        assertEquals(SavedState.RETURN, row().phase(), "RETURN in the same tick");
        after.arriveAll();
        assertHomeWithEverything("after a crash-join", rebooted);
        assertTrue(alice.messages.contains(SessionCore.BACK_AFTER_RESTART), "told why their things just came back");

        rebooted.joined(alice);
        assertEquals(1, alice.applies, "a second join finds nothing to do");
        assertTrue(after.trips.isEmpty(), "and goes nowhere");
    }

    @Test
    void aCrashJoinIsOverwriteOnlySoOldThingsOnDiskCantDuplicate() throws Exception {
        play();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        // The disk was saved before the clear (a crash between the clear and its save): her own
        // things AND an extra copy of the diamonds, and so not the clear's mark either.
        alice.slots = original();
        alice.slots[5] = "diamond x3";
        alice.cursor = "emerald";
        alice.mark = null;
        rebooted.joined(alice);
        after.arriveAll(); // she was still in the games world: restore, then home
        assertArrayEquals(original(), Arrays.copyOf(alice.slots, FakeServer.SLOTS),
                "overwritten, not merged: the stale copy is gone and nothing is doubled");
        assertNull(alice.cursor, "what was in hand predates the game and is not kept");
        assertNull(row(), "finished");
    }

    @Test
    void theClearIsSavedAtOnceSoACrashJoinBanksWhatArrivedDuringTheGame() throws Exception {
        play();
        String sid = row().sessionId();
        assertEquals(SessionCore.CLEARED + sid, alice.mark, "her own data says it was cleared for this session");
        assertEquals(1, alice.saves, "and it was saved right after the clear: the file no longer holds her things");
        alice.slots[20] = "Mini #42"; // delivered mid-game; the autosave caught it, then the server died

        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(alice);
        after.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertEquals(1, alice.items().stream().filter("Mini #42"::equals).count(),
                "what arrived after the clear is banked and handed over, not thrown away");
        assertEquals(1, alice.items().stream().filter("diamond x3"::equals).count(), "her own things once");
        assertEquals(1, alice.applies, "applied once");
        assertNull(row(), "finished");
    }

    @Test
    void aStopWhileTheServerIsStoppingRestoresInPlaceAndNeverTeleports() throws Exception {
        play();
        server.stopping = true;
        core.stop();
        assertEquals(SavedState.RETURN, row().phase(), "restored and marked RETURN");
        assertTrue(server.trips.isEmpty() && server.syncTeleports.isEmpty(), "no teleport during shutdown");
        assertEquals(List.of(EndReason.STOP), trials.ended, "the game hears it");
        assertNull(core.session(alice.id), "memory is cleared");

        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.worldsReady(List.of(alice)); // she stayed online through a /reload
        assertEquals(1, after.trips.size(), "the worlds-up pass sends her home");
        after.arriveAll();
        assertHomeWithEverything("after a stop and the next start", rebooted);
        assertEquals(1, alice.applies, "applied once, at the stop");
    }

    @Test
    void aReloadThatClosesGamesSendsPlayersHomeAtOnce() throws Exception {
        play();
        core.stop();
        assertEquals(List.of(HOME), server.syncTeleports, "a synchronous teleport home");
        assertHomeWithEverything("after a reload closed the games");
    }

    // ---- Multiverse-Inventories disables first (plugin.yml loads this plugin before Multiverse-Core) ----

    @Test
    void whenMultiverseInventoriesDisablesAtAStopEveryPlayersOwnThingsAreBackBeforeItSavesThem() throws Exception {
        play();
        assertTrue(alice.holdsKit(), "the fixture: playing, with the kit");
        server.stopping = true;
        assertFalse(core.disabling("Vault"), "another plugin going ends nothing");
        assertEquals(Session.Phase.ACTIVE, core.phase(alice.id), "still playing");

        assertTrue(core.disabling("Multiverse-Inventories"), "Multiverse-Inventories saves everyone's things as it"
                + " disables, and since this plugin loads before Multiverse-Core it now disables first");
        assertArrayEquals(original(), Arrays.copyOf(alice.slots, FakeServer.SLOTS), "so her own things are on her"
                + " when it saves them, never the kit");
        assertFalse(alice.holdsKit(), "no kit item left for it to save");
        assertEquals(SavedState.RETURN, row().phase(), "restored in place and marked RETURN");
        assertTrue(server.trips.isEmpty() && server.syncTeleports.isEmpty(), "no teleport while the server stops");
        assertNull(core.session(alice.id), "the session is over: this plugin's own disable finds nothing left");
        assertEquals(List.of(EndReason.STOP), trials.ended, "the game hears it once");
        core.stop(); // this plugin's own disable, later in the same shutdown
        assertEquals(1, alice.applies, "applied once");
    }

    @Test
    void whenMultiverseInventoriesIsDisabledWithoutAStopPlayersGoHomeWhileItStillSwapsTheirThings() throws Exception {
        play();
        assertTrue(core.disabling("multiverse-inventories"), "matched ignoring case");
        assertEquals(List.of(HOME), server.syncTeleports, "a synchronous teleport home, at once");
        assertHomeWithEverything("after Multiverse-Inventories was disabled by a /reload");
    }

    @Test
    void aGameSwitchedOffSendsItsPlayersHomeSynchronously() throws Exception {
        play();
        core.leave(alice, EndReason.GAME_OFF);
        assertEquals(List.of(HOME), server.syncTeleports, "sync, so a stopped game's tasks don't matter");
        assertHomeWithEverything("after the game was switched off");
        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("taking a break")), "told why");
    }

    // ---- leaving normally -----------------------------------------------------------------------------

    @Test
    void aNormalLeaveRestoresMarksReturnThenGoesHomeAndFinishes() throws Exception {
        play();
        core.leave(alice, EndReason.QUIT_ITEM);
        assertEquals(SavedState.RETURN, row().phase(), "RETURN before the trip home");
        assertEquals(1, alice.applies, "applied once");
        assertEquals(Session.Phase.LEAVING, core.phase(alice.id), "still guarded on the way");
        core.leave(alice, EndReason.COMMAND);
        assertEquals(1, server.trips.size(), "a second leave while leaving does nothing");
        assertFalse(core.home(alice.id), "not home yet: a Play again button waits");
        server.arriveAll();
        assertHomeWithEverything("after the Leave item");
        assertTrue(core.home(alice.id), "home now");
        assertEquals(List.of(EndReason.QUIT_ITEM), trials.ended, "the game hears it once");
    }

    @Test
    void thingsThatArriveMidGameAreBankedAndHandedOverAtHome() throws Exception {
        play();
        alice.slots[20] = "Mini #42"; // the auction timer delivered it mid-game
        alice.cursor = "binder card";
        core.leave(alice, EndReason.FINISH);
        assertFalse(alice.items().contains("Mini #42"), "not mixed into the restore in the Games world");
        assertEquals(List.of("binder card", "Mini #42"), server.decode(row().carry()),
                "banked in the row BEFORE the overwrite: a crash now loses nothing");
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertTrue(alice.items().contains("Mini #42") && alice.items().contains("binder card"),
                "handed over at home: it survives the leave");
        assertEquals(1, alice.items().stream().filter("diamond x3"::equals).count(), "and nothing is doubled");
        assertNull(row(), "finished");
    }

    @Test
    void aCrashBetweenTheBankAndHomeStillLosesNothing() throws Exception {
        play();
        alice.slots[20] = "Mini #42";
        core.leave(alice, EndReason.FINISH); // banked, restored, RETURN, trip pending... and the server dies
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(alice);
        after.arriveAll();
        assertEquals(1, alice.items().stream().filter("Mini #42"::equals).count(), "the Mini comes back once");
        assertEquals(1, alice.items().stream().filter("diamond x3"::equals).count(), "the diamonds once");
        assertNull(row(), "finished");
    }

    @Test
    void whatDoesntFitAtHomeWaitsInTheRowForRoomAndLeaveHandsItOver() throws Exception {
        for (int i = 2; i < FakeServer.STORAGE - 1; i++) {
            alice.slots[i] = "cobble " + i; // one slot left
        }
        play();
        alice.slots[20] = "Mini #42";
        alice.slots[21] = "Mini #43";
        core.leave(alice, EndReason.FINISH);
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertTrue(alice.dropped.isEmpty(), "nothing is dropped on the ground");
        assertTrue(alice.items().contains("Mini #42"), "what fits is handed over");
        assertEquals(List.of("Mini #43"), server.decode(row().carry()), "the rest waits in the row");
        assertEquals(SavedState.RETURN, row().phase(), "which stays RETURN");
        assertTrue(alice.messages.contains(SessionCore.FULL), "and they are told to make room");

        alice.slots[5] = null; // made room
        int trips = server.started;
        core.leave(alice, EndReason.COMMAND); // /hcm leave
        assertEquals(trips, server.started, "already home: no second trip");
        assertEquals(1, alice.items().stream().filter("Mini #43"::equals).count(), "handed over once");
        assertEquals(1, alice.items().stream().filter("Mini #42"::equals).count(), "and the first one still once");
        assertNull(row(), "finished");
    }

    @Test
    void leftoversAreNeverDroppedInAGamesWorldTheyWaitForRoom() throws Exception {
        alice.place = Place.of("games", 5, 64, 5); // entered from the Games hub
        for (int i = 2; i < FakeServer.STORAGE; i++) {
            alice.slots[i] = "cobble " + i;
        }
        play();
        alice.slots[20] = "Mini #42";
        core.leave(alice, EndReason.FINISH);
        server.arriveAll();
        assertTrue(alice.dropped.isEmpty(), "nothing dropped in a Games world");
        assertEquals(SavedState.RETURN, row().phase(), "the row waits");
        assertEquals(List.of("Mini #42"), server.decode(row().carry()), "with what didn't fit");
        assertTrue(alice.messages.contains(SessionCore.FULL), "and they are told to make room");

        alice.slots[5] = null; // made room
        core.leave(alice, EndReason.COMMAND); // /hcm leave
        server.arriveAll();
        assertTrue(alice.items().contains("Mini #42"), "handed over");
        assertNull(row(), "finished");
    }

    @Test
    void aFailedTripHomeKeepsReturnAndLeaveTriesAgain() throws Exception {
        play();
        core.leave(alice, EndReason.COMMAND);
        server.fail(0);
        assertEquals(SavedState.RETURN, row().phase(), "kept RETURN");
        assertTrue(alice.messages.contains(SessionCore.NOT_HOME), "told how to try again");
        assertNull(core.session(alice.id), "free to move, but can't start another game");
        assertEquals(SessionCore.STILL_SENDING, core.enter(alice, "trials", "", START, trials), "refused meanwhile");
        core.leave(alice, EndReason.COMMAND);
        server.arriveAll();
        assertHomeWithEverything("after /hcm leave tried again");
        assertEquals(1, alice.applies, "never applied twice");
    }

    @Test
    void aReturnPointInAWorldThatIsGoneSendsThemToSpawn() throws Exception {
        alice.place = Place.of("old_world", 1, 2, 3);
        server.worlds.add("old_world");
        play();
        server.worlds.remove("old_world");
        core.leave(alice, EndReason.COMMAND);
        server.arriveAll();
        assertEquals(server.mainSpawn(), alice.place, "the main world's spawn instead");
        assertNull(row(), "finished");
    }

    @Test
    void leaveWithNoGameSaysSo() {
        core.leave(alice, EndReason.COMMAND);
        assertTrue(alice.messages.contains(SessionCore.NOT_IN_GAME), "a plain answer");
    }

    // ---- death --------------------------------------------------------------------------------------

    @Test
    void deathIsCancelledThenTheGameEndsNormally() throws Exception {
        play();
        assertTrue(core.dying(alice, List.of()), "a session player's death is cancelled");
        core.died(alice, true);
        assertEquals(Session.Phase.ACTIVE, core.phase(alice.id), "nothing happens in the event itself");
        server.step();
        assertEquals(SavedState.RETURN, row().phase(), "next tick: the normal leave");
        server.arriveAll();
        assertHomeWithEverything("after a cancelled death");
        assertEquals(List.of(EndReason.DEATH), trials.ended, "the game hears DEATH");
    }

    @Test
    void quitWhileDeadKeepsTheRowActiveUntilTheRespawn() throws Exception {
        play();
        alice.dead = true;
        core.quit(alice);
        assertEquals(SavedState.ACTIVE, row().phase(), "a dead player is never restored");
        assertEquals(0, alice.applies, "not applied");
        assertFalse(alice.holdsKit(), "but the kit is gone");

        alice.online = true; // rejoins on the death screen
        core.joined(alice);
        assertEquals(0, alice.applies, "still dead: still waiting");
        assertTrue(server.trips.isEmpty(), "and going nowhere");

        alice.dead = false; // respawns in the Games world
        core.respawned(alice);
        server.steps((int) SessionCore.RESPAWN_DELAY);
        assertEquals(1, alice.applies, "restored after the respawn");
        server.arriveAll();
        assertHomeWithEverything("after quitting on the death screen");
    }

    @Test
    void aDeathAnotherPluginLetThroughStillLosesNothing() throws Exception {
        play();
        alice.slots[20] = "Mini #42";
        assertTrue(core.dying(alice, List.of("Mini #42")), "cancelled at LOWEST, drops kept aside");
        alice.dead = true; // someone un-cancelled it: she dies, the inventory is wiped
        alice.slots = new String[FakeServer.SLOTS];
        core.died(alice, false);
        assertEquals(List.of("Mini #42"), server.decode(row().carry()), "what would have dropped is banked");
        server.step();
        assertNull(core.session(alice.id), "the session ends");
        assertEquals(SavedState.ACTIVE, row().phase(), "the row waits for the respawn");

        alice.dead = false;
        alice.place = Place.of("world", 0, 64, 0); // her bed is in the overworld
        core.respawned(alice);
        server.steps((int) SessionCore.RESPAWN_DELAY);
        assertEquals("games", server.trips.get(0).to().world(), "back to the session world first");
        server.arrive(0);
        assertEquals(List.of("games"), alice.appliedIn, "applied there");
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertTrue(alice.items().contains("Mini #42") && alice.items().contains("diamond x3"), "everything");
        assertNull(row(), "finished");
    }

    // ---- races --------------------------------------------------------------------------------------

    @Test
    void aLateCallbackFromAnOldSessionCantTouchANewOne() throws Exception {
        play();
        String first = row().sessionId();
        core.leave(alice, EndReason.COMMAND); // the trip home is pending...
        FakeServer.Trip oldTrip = server.trips.remove(0); // (held back: it will come in very late)
        core.quit(alice); // ...she quits
        alice.online = true;
        core.joined(alice); // rejoins: RETURN goes home again
        server.arrive(0);
        assertNull(row(), "that finished the first session");

        play(); // and a new one starts
        String second = row().sessionId();
        assertFalse(first.equals(second), "a new session id");
        server.trips.add(oldTrip);
        server.arrive(server.trips.size() - 1); // the old callback finally fires
        assertEquals(SavedState.ACTIVE, row().phase(), "the new row is untouched");
        assertEquals(second, row().sessionId(), "still the new session");
        assertEquals(Session.Phase.ACTIVE, core.phase(alice.id), "and she is still playing");
    }

    // ---- teleports, worlds, the void ------------------------------------------------------------------

    @Test
    void teleportsAreRecognisedAsOursOnlyByAnExactMatch() {
        Place target = Place.of("games", 10, 64, 10);
        assertTrue(SessionCore.matches(target, 100, 300, "PLUGIN", Place.of("games", 10.005, 64, 9.995)),
                "within 0.01 on every axis, cause PLUGIN, armed 200 ticks ago: ours");
        assertFalse(SessionCore.matches(target, 100, 301, "PLUGIN", target), "armed too long ago");
        assertFalse(SessionCore.matches(target, 100, 101, "COMMAND", target), "another cause");
        assertFalse(SessionCore.matches(target, 100, 101, "PLUGIN", Place.of("games", 10.02, 64, 10)), "0.02 off");
        assertFalse(SessionCore.matches(target, 100, 101, "PLUGIN", Place.of("world", 10, 64, 10)), "another world");
    }

    @Test
    void foreignTeleportsEndTheGameOnlyWhenTheyLeaveOrGoFar() {
        Place from = Place.of("games", 0, 64, 0);
        assertEquals(SessionCore.Move.OURS, SessionCore.classify(true, false, "PLUGIN", from, Place.of("world", 0, 0, 0), "games"),
                "ours is ours, even to another world");
        assertEquals(SessionCore.Move.OURS, SessionCore.classify(false, true, "DISMOUNT", from, Place.of("games", 1, 64, 0), "games"),
                "our own dismount");
        assertEquals(SessionCore.Move.END, SessionCore.classify(false, false, "COMMAND", from, Place.of("world", 0, 64, 0), "games"),
                "another world ends it");
        assertEquals(SessionCore.Move.END, SessionCore.classify(false, false, "DISMOUNT", from, Place.of("nether", 0, 64, 0), "games"),
                "another world ends it, whatever the cause");
        assertEquals(SessionCore.Move.END, SessionCore.classify(false, false, "PLUGIN", from, Place.of("games", 17, 64, 0), "games"),
                "more than 16 blocks ends it");
        assertEquals(SessionCore.Move.KEEP, SessionCore.classify(false, false, "PLUGIN", from, Place.of("games", 16, 64, 0), "games"),
                "16 blocks keeps it");
        for (String cause : List.of("DISMOUNT", "EXIT_BED", "UNKNOWN")) {
            assertEquals(SessionCore.Move.KEEP, SessionCore.classify(false, false, cause, from, Place.of("games", 90, 64, 0), "games"),
                    cause + " in the same world keeps it");
        }
    }

    @Test
    void aTeleportOutEndsTheGameWhereTheyStandThenHandsOverTheCarry() throws Exception {
        play();
        alice.slots[20] = "Mini #42";
        core.teleported(alice, START, Place.of("world", 500, 64, 500), "COMMAND"); // /spawn, at MONITOR
        assertEquals(SavedState.RETURN, row().phase(), "restored in place before they go");
        assertTrue(server.trips.isEmpty() && server.syncTeleports.isEmpty(), "we don't teleport them: they're going somewhere");
        assertEquals(List.of(EndReason.TELEPORT), trials.ended, "the game hears TELEPORT");
        alice.place = Place.of("world", 500, 64, 500); // the teleport happens
        server.step();
        assertTrue(alice.items().contains("Mini #42"), "a tick later the carry is handed over where they are");
        assertNull(row(), "and the row is finished");
    }

    @Test
    void aForeignTeleportIntoAnotherGamesWorldKeepsTheCarryForTheTripHome() throws Exception {
        server.worlds.add("games2");
        server.gamesWorlds.add("games2");
        play();
        alice.slots[20] = "Mini #42";
        Place there = Place.of("games2", 0, 64, 0);
        core.teleported(alice, START, there, "COMMAND");
        alice.place = there;
        server.step();
        assertFalse(alice.items().contains("Mini #42"), "not handed over inside a Games world");
        assertEquals(List.of("Mini #42"), server.decode(row().carry()), "it waits in the row");
        assertEquals(SavedState.RETURN, row().phase(), "which stays RETURN");
        assertTrue(alice.messages.contains(SessionCore.KEPT), "told how to get it");
        assertTrue(alice.items().contains("diamond x3"), "her own things are back");

        core.leave(alice, EndReason.COMMAND); // /hcm leave
        server.arriveAll();
        assertEquals(HOME, alice.place, "taken home");
        assertEquals(1, alice.items().stream().filter("Mini #42"::equals).count(), "and handed it there, once");
        assertNull(row(), "finished");
    }

    @Test
    void aFarTeleportInsideTheGamesWorldWithAFullInventoryLosesNothing() throws Exception {
        for (int i = 2; i < FakeServer.STORAGE; i++) {
            alice.slots[i] = "cobble " + i;
        }
        play();
        alice.slots[20] = "Mini #42";
        Place far = Place.of("games", 500, 64, 500);
        core.teleported(alice, START, far, "COMMAND");
        alice.place = far;
        server.step();
        assertEquals(SavedState.RETURN, row().phase(), "the carry waits");
        core.leave(alice, EndReason.COMMAND);
        server.arriveAll();
        assertEquals(HOME, alice.place, "sent home");
        assertTrue(alice.dropped.isEmpty(), "nothing on the ground: her inventory is full");
        assertEquals(List.of("Mini #42"), server.decode(row().carry()), "the Mini waits for room");
        alice.slots[7] = null;
        core.leave(alice, EndReason.COMMAND);
        assertTrue(alice.items().contains("Mini #42"), "then it is handed over");
        assertNull(row(), "finished");
    }

    @Test
    void aSmallForeignMoveKeepsTheGameAndTheGameHearsIt() throws Exception {
        play();
        core.teleported(alice, START, Place.of("games", 104, 70, 100), "UNKNOWN");
        assertEquals(Session.Phase.ACTIVE, core.phase(alice.id), "the session goes on");
        server.step();
        assertEquals(1, trials.voided, "the game can void the run");
    }

    @Test
    void aGamesOwnTeleportIsRecognisedAndBecomesTheSafePoint() throws Exception {
        play();
        Place checkpoint = Place.of("games", 150, 80, 150);
        assertTrue(core.teleport(alice, checkpoint), "a game's teleport inside the session world");
        assertEquals(checkpoint, alice.place, "same tick when the chunk is loaded");
        core.teleported(alice, START, checkpoint, "PLUGIN");
        server.step();
        assertEquals(0, trials.voided, "ours: nothing to void");
        assertFalse(core.teleport(alice, Place.of("world", 0, 64, 0)), "never to another world");

        alice.place = Place.of("games", 150, -70, 150);
        core.moved(alice, alice.place, -64);
        server.step();
        assertEquals(checkpoint, alice.place, "a fall into the void goes back to the last safe point (next tick)");
        server.step();
        assertEquals(1, trials.voided, "then the game hears of it");
        core.moved(alice, Place.of("games", 150, -70, 150), -64);
        server.steps(2);
        assertEquals(1, trials.voided, "one rescue per second, not one per move packet");
    }

    @Test
    void theWorldChangeBackstopBanksAtOnceThenRestoresInTheSessionWorld() throws Exception {
        play();
        alice.slots[5] = "Mini #7";
        alice.place = Place.of("nether", 0, 64, 0); // a portal the guard missed; LOWEST, before the swap
        core.worldChanged(alice);
        assertFalse(alice.holdsKit(), "the kit is gone before Multiverse-Inventories saves anything");
        assertTrue(alice.items().isEmpty(), "and the extras are out of the inventory");
        assertEquals(List.of("Mini #7"), server.decode(row().carry()), "banked in the row");
        assertEquals(0, alice.applies, "not applied in the wrong world");
        server.step();
        assertEquals(START, server.trips.get(0).to(), "back to the session start first");
        server.arrive(0);
        assertEquals(List.of("games"), alice.appliedIn, "applied in the session world");
        server.arriveAll();
        assertEquals(HOME, alice.place, "then home");
        assertTrue(alice.items().contains("Mini #7") && alice.items().contains("diamond x3"), "with everything");
        assertEquals(List.of(EndReason.WORLD_CHANGE), trials.ended, "the game hears WORLD_CHANGE");
        assertNull(row(), "finished");
    }

    // ---- when a restore can't happen --------------------------------------------------------------------

    @Test
    void anUnreadableSnapshotIsKeptForAnAdminWithTheirStateAsItIs() throws Exception {
        play();
        byte[] good = row().items();
        alice.slots[20] = "Mini #42";
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, "JUNK".getBytes());
            ps.executeUpdate();
        }
        core.leave(alice, EndReason.COMMAND);
        assertEquals(0, alice.applies, "nothing applied");
        assertEquals("ADVENTURE", alice.gameMode, "the player is left as they are");
        assertFalse(alice.holdsKit(), "except the kit, which never leaves a game");
        assertEquals(SavedState.ACTIVE, row().phase(), "the row is kept exactly");
        assertEquals(List.of("Mini #42"), server.decode(row().carry()), "what arrived mid-game is safe in it too");
        assertTrue(alice.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told an admin will help");
        assertNull(core.session(alice.id), "the session is over");

        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, good);
            ps.executeUpdate();
        }
        assertEquals(SessionCore.ADMIN_BACK, core.adminRestore(alice),
                "an admin restores: she is still in the session world, so it happens at once and the admin hears so");
        server.arriveAll();
        assertEquals(HOME, alice.place, "home");
        assertTrue(alice.items().contains("Mini #42") && alice.items().contains("diamond x3"), "with everything");
        assertNull(row(), "finished");
    }

    @Test
    void aRestoreWhoseWorldIsGoneKeepsTheRow() throws Exception {
        play();
        FakeServer after = new FakeServer(); // the server dies mid-game
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        after.worlds.remove("games");
        alice.place = Place.of("world", 0, 64, 0);
        rebooted.joined(alice);
        assertEquals(SavedState.ACTIVE, row().phase(), "kept");
        assertTrue(alice.messages.contains(SessionCore.SAFE_WITH_ADMIN), "told an admin will help");
        assertTrue(after.trips.isEmpty(), "and not moved");
    }

    // ---- admin ----------------------------------------------------------------------------------------

    @Test
    void adminsCanOnlyReturnARowWhoseThingsAreBackAndCanDiscardOne() throws Exception {
        play();
        assertTrue(core.adminReturn(alice).contains("Ended their game"), "a live game is ended properly");
        assertEquals(SavedState.RETURN, row().phase(), "which restores first");
        core.quit(alice);
        alice.online = true;
        assertTrue(core.adminRestore(alice).contains("put back already"), "a RETURN row is never applied again");
        assertEquals("&aSending them back now.", core.adminReturn(alice), "return sends them home");
        server.arriveAll();
        assertNull(row(), "finished");

        play();
        alice.dead = true;
        core.quit(alice); // dead: never restored, the row stays ACTIVE
        alice.dead = false;
        alice.online = true;
        assertTrue(core.adminReturn(alice).contains("haven't been put back"), "return refuses an ACTIVE row: it would lose it");
        assertEquals("&aDiscarded their saved things.", core.adminDiscard(alice.id, "Alice", "Admin"), "discard deletes it");
        assertNull(row(), "gone");
        assertFalse(core.hasRow(alice.id), "and the recovery set knows");
        assertNull(core.enter(alice, "trials", "", START, trials), "so she can play again");
    }

    // ---- housekeeping -------------------------------------------------------------------------------

    @Test
    void doneRowsArePrunedAfterAWeekAtStart() throws Exception {
        long week = SessionCore.KEEP_DONE_MS;
        for (String[] r : new String[][]{{"old", String.valueOf(server.now - week - 1)}, {"new", String.valueOf(server.now - week + 60_000)}}) {
            SavedState s = new SavedState(java.util.UUID.randomUUID(), "trials", "", SavedState.ACTIVE, r[0], "games",
                    null, null, 0, 0, 0, 20, 20, 5, 0, 0, 300, "SURVIVAL", false, false, 0.2f, 0.1f, 0, "", "world",
                    0, 64, 0, 0, 0, 1L, null);
            assertTrue(dao.saveState(s), "seeded");
            assertTrue(dao.finishState(s.player(), r[0], Long.parseLong(r[1])), "finished at the given time");
        }
        new SessionCore<>(dao, server, log);
        List<SavedState> left = dao.statesInPhase(SavedState.DONE);
        assertEquals(1, left.size(), "the week-old one is pruned");
        assertEquals("new", left.get(0).sessionId(), "the recent one is kept for support");
    }

    @Test
    void aStrayKitItemIsSweptOnEveryJoin() {
        alice.slots[3] = "kit:golf:club";
        alice.ender.add("kit:trials:rocket");
        core.joined(alice);
        assertFalse(alice.holdsKit(), "inventory and ender chest, with no row at all");
    }

    @Test
    void theQuietLoggerSeesTheFailuresOnce() throws Exception {
        List<LogRecord> severe = new java.util.ArrayList<>();
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.SEVERE) {
                    severe.add(r);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        play();
        try (PreparedStatement ps = conn.prepareStatement("UPDATE game_saved_state SET items = ?")) {
            ps.setBytes(1, "JUNK".getBytes());
            ps.executeUpdate();
        }
        core.leave(alice, EndReason.COMMAND);
        core.joined(alice);
        core.joined(alice);
        assertEquals(1, severe.size(), "a failed restore is logged SEVERE once per session, not on every join");
        assertTrue(severe.get(0).getMessage().contains(row().sessionId()), "with the session id");
        assertTrue(severe.get(0).getMessage().contains("Alice"), "and the player");
    }
}
