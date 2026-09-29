package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.GameMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework the Clubhouse stands on (the Clubhouse review, #3, #5, #6, #7), walked through the real
 * world-session state machine on a fake server:
 * <ul>
 *   <li><b>passTo</b>: only an ACTIVE session is handed over, in place (nothing restored, saved or
 *       cleared); from then on its end and its void go to the new game; a racer handed out of the
 *       Clubhouse and back, then a quit or a crash, gets their things back ONCE;</li>
 *   <li><b>the session's own game mode</b> (a watcher's SPECTATOR): kept for that session only (what
 *       the game-mode guard re-asserts after Multiverse re-applies the world's mode), gone with it,
 *       and every way out puts the player's own mode back: Leave, a quit, the games off, the restart
 *       hold, a crash boot;</li>
 *   <li><b>exits with nothing to put back</b> (the row is gone) never leave a watcher in
 *       spectator mode;</li>
 *   <li><b>a watcher comes down to a floor</b> (the session's start, the Clubhouse's arrival spot)
 *       before a restore, never restored in mid-air where they flew; but never a teleport while the
 *       server is stopping;</li>
 *   <li><b>a mid-session hand-over keeps what arrived</b>: {@link SessionCore#bankExtras} banks every
 *       non-kit item in the carry (a Mini, an auction win) and it comes home once.</li>
 * </ul>
 */
class ClubhouseSessionTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    /** The Clubhouse's arrival spot: the session's start. */
    private static final Place CLUBHOUSE = Place.of("games", 5390, 161, 4460);
    /** Where a watcher flies, over a course. */
    private static final Place FLYING = Place.of("games", 40, 95, 40);

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
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    /** Into the Clubhouse: a Clubhouse session, ACTIVE. */
    private void visit() {
        assertNull(core.enter(wes, "clubhouse", "clubhouse", CLUBHOUSE, club), "into the Clubhouse");
        server.step();
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "in the Clubhouse");
        assertEquals("ADVENTURE", wes.gameMode, "the games' mode");
    }

    /** Watch live: the session's own mode is SPECTATOR, and they fly over the course. */
    private void watch() {
        assertTrue(core.mode(wes, "SPECTATOR"), "the session's own mode");
        wes.gameMode = "SPECTATOR"; // what the port does next
        wes.place = FLYING;
    }

    /** The row is gone under a live session (deleted by hand, or by an admin tool that doesn't look). */
    private void dropRow() throws Exception {
        assertTrue(dao.deleteState(wes.id, dao.loadState(wes.id).sessionId()), "the row is gone");
    }

    private long diamonds() {
        return wes.items().stream().filter("diamond x3"::equals).count();
    }

    // ---- passTo ---------------------------------------------------------------------------------------

    @Test
    void passToHandsOnlyAnActiveSessionOverInPlaceAndItsEndAndVoidGoToTheNewGame() throws Exception {
        assertNull(core.enter(wes, "clubhouse", "clubhouse", CLUBHOUSE, club), "entering");
        assertFalse(core.passTo(wes, "trials", "loop", trials), "an entry on its way can't be handed over");
        server.step();
        server.arriveAll();
        String sid = dao.loadState(wes.id).sessionId();
        int applies = wes.applies;
        assertTrue(core.passTo(wes, "trials", "loop", trials), "an ACTIVE one is");
        assertEquals("trials", core.session(wes.id).gameId(), "the race holds it now");
        assertEquals("loop", core.session(wes.id).ref(), "on its course");
        assertEquals(sid, core.session(wes.id).id(), "the same session");
        assertEquals(sid, dao.loadState(wes.id).sessionId(), "the same row: nothing saved again");
        assertEquals(applies, wes.applies, "nothing restored");
        assertEquals(CLUBHOUSE, wes.place, "nobody moved");
        assertFalse(core.passTo(wes, "", "x", trials), "never to no game");
        assertFalse(core.passTo(wes, "trials", "x", null), "never without its hooks");

        core.teleported(wes, CLUBHOUSE, Place.of("games", 5392, 161, 4460), "UNKNOWN");
        server.step();
        assertEquals(1, trials.voided, "the void goes to the game holding it");
        assertEquals(0, club.voided, "not the one it came from");
        core.leave(wes, EndReason.FINISH);
        assertEquals(List.of(EndReason.FINISH), trials.ended, "and so does the end");
        assertTrue(club.ended.isEmpty(), "never both");
        server.arriveAll();
        assertEquals(HOME, wes.place, "home");
        assertEquals(1, diamonds(), "their things once");
        assertFalse(core.passTo(wes, "clubhouse", "clubhouse", club), "no session: nothing to hand over");
    }

    @Test
    void aRacerHandedOutOfTheClubhouseAndBackThenACrashGetsTheirThingsBackOnce() throws Exception {
        visit();
        assertTrue(core.passTo(wes, "trials", "loop", trials), "Start: to the grid, in the same session");
        assertTrue(core.passTo(wes, "clubhouse", "clubhouse", club), "the finish: back in the Clubhouse");
        // The server dies here.
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(wes);
        after.arriveAll();
        assertEquals(HOME, wes.place, "home after the crash");
        assertEquals(1, diamonds(), "their things once, whichever game held the session");
        assertEquals(1, wes.applies, "applied once");
        assertEquals("SURVIVAL", wes.gameMode, "their own mode");
        assertNull(dao.loadState(wes.id), "finished");
    }

    // ---- the session's own game mode --------------------------------------------------------------

    @Test
    void theSessionsOwnModeIsKeptForThatSessionOnlyAndANewOneStartsInAdventure() {
        assertFalse(core.mode(wes, "SPECTATOR"), "no session: nothing to keep");
        visit();
        assertNull(core.mode(wes.id), "a new session: the games' ADVENTURE");
        watch();
        assertEquals("SPECTATOR", core.mode(wes.id), "watching");
        // Multiverse re-applies the world's mode: the guard cancels it and re-asserts the session's own.
        assertEquals(GameMode.SPECTATOR, BukkitPort.intended(core.mode(wes.id)), "SPECTATOR, not ADVENTURE, while watching");
        assertTrue(core.mode(wes, "ADVENTURE"), "back from watching");
        assertNull(core.mode(wes.id), "the usual again");
        assertEquals(GameMode.ADVENTURE, BukkitPort.intended(core.mode(wes.id)), "and the guard keeps ADVENTURE");
        watch();
        core.leave(wes, EndReason.QUIT_ITEM);
        server.arriveAll();
        assertNull(core.mode(wes.id), "gone with the session");
        visit();
        assertNull(core.mode(wes.id), "the next session starts in ADVENTURE, never the old one's SPECTATOR");
        assertEquals(GameMode.ADVENTURE, BukkitPort.intended(null), "none: ADVENTURE");
        assertEquals(GameMode.ADVENTURE, BukkitPort.intended("NOT_A_MODE"), "one we can't read: ADVENTURE");
    }

    @Test
    void theGuardLetsOnlyAWatcherFlyAndKeepsEveryoneElseGrounded() {
        assertTrue(KitGuardListener.groundsFlight(true, true, GameMode.ADVENTURE), "a player in a game can't take off");
        assertFalse(KitGuardListener.groundsFlight(true, true, GameMode.SPECTATOR), "a watcher flies: that is how they watch");
        assertFalse(KitGuardListener.groundsFlight(true, false, GameMode.SURVIVAL), "nobody outside a game is touched");
        assertFalse(KitGuardListener.groundsFlight(false, true, GameMode.ADVENTURE), "landing is never stopped");
    }

    @Test
    void onlyTheOwnerAndTheirOneRiderMayGetIntoAGameBoat() {
        UUID dad = UUID.randomUUID();
        UUID kid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try {
            assertTrue(WorldEntities.mayEnter(dad, dad), "the owner");
            assertFalse(WorldEntities.mayEnter(dad, kid), "nobody else, before a ride");
            WorldEntities.passenger(dad, kid);
            assertTrue(WorldEntities.mayEnter(dad, kid), "their rider");
            assertFalse(WorldEntities.mayEnter(dad, other), "and nobody else");
            assertFalse(WorldEntities.mayEnter(kid, dad), "a rider's own boat isn't the driver's");
            assertFalse(WorldEntities.mayEnter(dad, null), "no one");
            WorldEntities.passenger(dad, null);
            assertFalse(WorldEntities.mayEnter(dad, kid), "the ride over: not any more");
        } finally {
            WorldEntities.passenger(dad, null);
        }
    }

    // ---- every way out puts a watcher's own mode back --------------------------------------------------

    @Test
    void leaveWhileWatchingLandsOnTheClubhouseFloorThenPutsTheirOwnModeBack() throws Exception {
        visit();
        watch();
        core.leave(wes, EndReason.COMMAND); // /hcm leave (spectator mode can't use items)
        assertEquals(List.of(CLUBHOUSE), server.syncTeleports, "down to the Clubhouse's floor first, not restored in mid-air");
        assertEquals("SURVIVAL", wes.gameMode, "their own mode back");
        server.arriveAll();
        assertEquals(HOME, wes.place, "then home");
        assertEquals(1, diamonds(), "their things once");
    }

    @Test
    void aQuitWhileWatchingRestoresOnTheFloorNotWhereTheyFlew() throws Exception {
        visit();
        watch();
        core.quit(wes);
        assertEquals(CLUBHOUSE, wes.place, "brought down before the restore in place (#7)");
        assertEquals("SURVIVAL", wes.gameMode, "their own mode");
        assertEquals(List.of(EndReason.DISCONNECT), club.ended, "the Clubhouse hears it");
        core.joined(wes);
        server.arriveAll();
        assertEquals(HOME, wes.place, "home at the next join");
        assertEquals(1, diamonds(), "their things once");
    }

    @Test
    void theGamesOffOrTheRestartHoldWhileWatchingPutTheirOwnModeBack() throws Exception {
        visit();
        watch();
        core.leave(wes, EndReason.GAME_OFF);
        assertEquals("SURVIVAL", wes.gameMode, "the games off: their own mode");
        assertEquals(HOME, wes.place, "home, synchronously");
        visit();
        watch();
        core.leave(wes, EndReason.FINISH); // the restart hold sends everyone home (Clubhouse.sendHome)
        server.arriveAll();
        assertEquals("SURVIVAL", wes.gameMode, "the restart hold: their own mode");
        assertEquals(1, diamonds(), "their things once");
    }

    @Test
    void aCrashWhileWatchingEndsInTheirOwnModeWithTheirThingsAtTheNextJoin() throws Exception {
        visit();
        watch();
        FakeServer after = new FakeServer();
        SessionCore<FakeServer.Body, String> rebooted = new SessionCore<>(dao, after, log);
        rebooted.joined(wes); // still where they flew, in spectator mode, as the crash left them
        after.arriveAll();
        assertEquals("SURVIVAL", wes.gameMode, "the saved state's mode, put back first");
        assertEquals(HOME, wes.place, "home");
        assertEquals(1, diamonds(), "their things once");
    }

    @Test
    void aStopWhileTheServerIsStoppingNeverTeleportsAWatcher() throws Exception {
        visit();
        watch();
        server.stopping = true;
        core.stop();
        assertTrue(server.syncTeleports.isEmpty() && server.trips.isEmpty(), "no teleport while stopping");
        assertEquals("SURVIVAL", wes.gameMode, "their own mode, in place");
    }

    // ---- exits with nothing to put back (#6) ----------------------------------------------------------

    @Test
    void aWatcherWhoseRowAnAdminDiscardedIsNotLeftInSpectatorMode() throws Exception {
        visit();
        watch();
        dropRow();
        core.leave(wes, EndReason.COMMAND);
        assertEquals("ADVENTURE", wes.gameMode, "nothing to put back, but never left in spectator mode");
        assertNull(core.session(wes.id), "the session is over");
    }

    @Test
    void anExitWithNothingToPutBackLeavesAnyoneElsesModeAlone() throws Exception {
        visit();
        wes.gameMode = "ADVENTURE";
        dropRow();
        core.leave(wes, EndReason.COMMAND);
        assertEquals("ADVENTURE", wes.gameMode, "not a watcher: as before");
        visit();
        watch();
        wes.gameMode = "CREATIVE"; // something else already changed it
        dropRow();
        core.leave(wes, EndReason.COMMAND);
        assertEquals("CREATIVE", wes.gameMode, "only the session's own mode is taken away, never another");
    }

    // ---- a hand-over keeps what arrived (#3) ---------------------------------------------------------

    @Test
    void aHandOverBanksWhatArrivedMidSessionAndItComesHomeOnce() throws Exception {
        assertFalse(core.bankExtras(wes), "no session: nothing banked, nothing changed");
        visit();
        wes.slots[3] = "Mini #42"; // an auction win delivered while they waited in the Clubhouse
        wes.slots[5] = "kit:clubhouse:results";
        assertTrue(core.bankExtras(wes), "banked for the hand-over");
        assertTrue(wes.items().isEmpty(), "the inventory is empty for the race's kit (the Clubhouse's kit is gone)");
        assertEquals(List.of("Mini #42"), server.decode(dao.loadState(wes.id).carry()),
                "the Mini is in the carry, written before anything else happens");
        assertTrue(core.passTo(wes, "trials", "loop", trials), "to the grid");
        wes.slots[0] = "kit:trials:checkpoint"; // the race's own kit, in its own slots
        core.leave(wes, EndReason.FINISH);
        server.arriveAll();
        assertEquals(HOME, wes.place, "home");
        assertEquals(1, wes.items().stream().filter("Mini #42"::equals).count(), "the Mini comes home once");
        assertEquals(1, diamonds(), "their own things once");
        assertFalse(wes.holdsKit(), "no kit comes home");
        assertNull(dao.loadState(wes.id), "finished");
    }

    @Test
    void aKitSlotHoldingThePlayersOwnThingMovesItAsideNeverOverwritesIt() {
        assertEquals(KitItems.STAYS_PUT, KitItems.moveTo(false, 7), "an empty slot or an old kit item: just put it there");
        assertEquals(7, KitItems.moveTo(true, 7), "their own thing moves to the first empty storage slot");
        assertEquals(KitItems.NO_ROOM, KitItems.moveTo(true, -1), "no room: the kit item waits, their thing stays");
    }
}
