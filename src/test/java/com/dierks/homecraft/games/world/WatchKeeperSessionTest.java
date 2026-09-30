package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.clubhouse.WatchArea;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Watch-live keeper through the real world-session state machine (the Clubhouse review's blocker,
 * #1). A watcher flies round a course inside their own Clubhouse session, and the session decides what
 * every teleport means: the session's own (armed) ones are OURS; anyone else's short hop "voids" (the
 * game hears of it), and a long one ENDS the session where they float. So the keeper:
 * <ul>
 *   <li>never redirects a move (a changed destination is an unarmed PLUGIN teleport: someone else's), it
 *       cancels it ({@link WatchArea.Keep}), which moves nobody and fires no teleport at all;</li>
 *   <li>puts a watcher who is outside back with the session's own teleport, which is OURS;</li>
 *   <li>on a void (a short foreign hop, or the fall rescue) puts them back in the AREA, never at the
 *       Clubhouse's arrival spot, so a watcher is never dropped into the Clubhouse in spectator mode.</li>
 * </ul>
 * Pinned too, as the reason: what the old redirect did (a far one ended the session in mid-air).
 */
class WatchKeeperSessionTest {

    private static final Place HOME = new Place("world", 10.5, 64, 10.5, 45f, 5f);
    /** The Clubhouse's arrival spot (the session's start). */
    private static final Place CLUBHOUSE = Place.of("games", 5390, 161, 4460);
    /** The course being raced: its watch area, and the first view above its start. */
    private static final WatchArea AREA = new WatchArea(new Box(0, -64, 0, 63, 100, 63), 10, 70, 10, 0f);
    private static final Place VIEW = Place.of("games", 10, 70, 10);

    private Connection conn;
    private FakeServer server;
    private SessionCore<FakeServer.Body, String> core;
    private FakeServer.Body wes;
    /** The Clubhouse's hooks: a void of a watcher puts them back in the area (Clubhouse.onVoid). */
    private Watcher club;

    /** The Clubhouse's side, for one watcher: {@code voided} is its onVoid while they watch. */
    private final class Watcher implements SessionCore.Hooks<FakeServer.Body> {
        final List<EndReason> ended = new ArrayList<>();
        int voided;

        @Override
        public void ready(FakeServer.Body p) {
            p.slots[8] = "kit:clubhouse:leave";
        }

        @Override
        public void ended(FakeServer.Body p, EndReason reason) {
            ended.add(reason);
        }

        @Override
        public void voided(FakeServer.Body p) {
            voided++;
            putBack(p);
        }
    }

    /** WatchLive.putBack: the session's own teleport to the nearest point inside, when outside. */
    private void putBack(FakeServer.Body p) {
        double[] in = AREA.putBack(p.place.x(), p.place.y(), p.place.z());
        if (in != null) {
            Place to = Place.of("games", in[0], in[1], in[2]);
            Place from = p.place;
            assertTrue(core.teleport(p, to), "the session's own teleport, inside its world");
            core.teleported(p, from, to, "PLUGIN"); // the server's event for it: armed, so OURS
        }
    }

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        GamesDao dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
        server = new FakeServer();
        core = new SessionCore<>(dao, server, log);
        wes = new FakeServer.Body("Wes", HOME);
        wes.slots[0] = "diamond x3";
        wes.gameMode = "SURVIVAL";
        club = new Watcher();
        assertNull(core.enter(wes, "clubhouse", "clubhouse", CLUBHOUSE, club), "into the Clubhouse");
        server.step();
        server.arriveAll();
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "in the Clubhouse");
        // Watch live: the session's own teleport to the course, then spectator mode (the session's own)
        assertTrue(core.teleport(wes, VIEW), "to the course's view point");
        core.teleported(wes, CLUBHOUSE, VIEW, "PLUGIN");
        wes.gameMode = "SPECTATOR";
        server.step();
        assertEquals(0, club.voided, "the trip out is ours");
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void aRedirectedMoveWouldHaveBeenSomeoneElsesTeleportThatIsWhyTheKeeperCancels() {
        // What the old keeper did (e.setTo): the server turns it into an UNARMED PLUGIN teleport.
        Place edge = Place.of("games", 63.7, 70, 30);
        Place farOut = Place.of("games", 63.7 + 40, 70, 30); // from where they had drifted to
        assertEquals(SessionCore.Move.END, SessionCore.classify(false, false, "PLUGIN", farOut, edge, "games"),
                "a redirect of more than 16 blocks is someone else's teleport: it ENDS the session");
        assertEquals(SessionCore.Move.KEEP, SessionCore.classify(false, false, "PLUGIN", Place.of("games", 66, 70, 30),
                edge, "games"), "a short one only voids (which used to send them to the Clubhouse in spectator mode)");

        // The keeper now: a move out from inside is cancelled; no teleport, so the session sees nothing.
        assertEquals(WatchArea.Keep.CANCEL, AREA.keep(62, 70, 30, 70, 70, 30), "cancelled");
        assertEquals(WatchArea.Keep.LET, AREA.keep(62, 70, 30, 40, 70, 30), "a move inside goes ahead");
        assertEquals(WatchArea.Keep.PULL, AREA.keep(90, 70, 30, 91, 70, 30),
                "outside already: cancelled, and put back by the session's own teleport");
        server.steps(5);
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "still watching, in their session");
        assertEquals(0, club.voided, "nothing foreign happened");
    }

    @Test
    void theKeepersPutBackIsTheSessionsOwnTeleportSoItNeverVoidsOrEnds() {
        wes.place = Place.of("games", 150, 70, 30); // far outside (the area moved under them)
        putBack(wes);
        assertTrue(AREA.contains(wes.place.x(), wes.place.y(), wes.place.z()), "inside again: " + wes.place);
        server.steps(3);
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "86 blocks, and the session goes on: it is ours");
        assertEquals(0, club.voided, "and nothing is voided");
        assertEquals("SPECTATOR", wes.gameMode, "still watching");
        assertTrue(club.ended.isEmpty(), "their session never ended");
    }

    @Test
    void aShortForeignHopPutsAWatcherBackInTheAreaNotTheClubhouse() {
        Place before = wes.place;
        Place out = Place.of("games", before.x() - 15, before.y(), before.z()); // someone else's 15-block hop
        wes.place = out;
        core.teleported(wes, before, out, "PLUGIN");
        server.step();
        assertEquals(1, club.voided, "a short foreign hop: the Clubhouse hears of it (onVoid)");
        assertTrue(AREA.contains(wes.place.x(), wes.place.y(), wes.place.z()), "back in the area: " + wes.place);
        assertNotEquals(CLUBHOUSE, wes.place, "never at the Clubhouse's arrival spot while still watching");
        server.steps(3);
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "the session goes on");
        assertEquals(1, club.voided, "the put-back itself is ours: no second void");
    }

    @Test
    void aFallIntoTheVoidComesBackToTheLastSafePointInTheAreaNeverTheClubhouse() {
        wes.place = Place.of("games", 10, -70, 10);
        core.moved(wes, wes.place, -64);
        server.step();
        assertEquals(VIEW, wes.place, "the rescue goes to the last safe point: the view point, the session's own"
                + " last teleport (the Clubhouse's arrival spot only if there was none)");
        server.step();
        assertEquals(1, club.voided, "then the Clubhouse hears of it");
        assertTrue(AREA.contains(wes.place.x(), wes.place.y(), wes.place.z()), "and they are in the area");
        assertEquals(Session.Phase.ACTIVE, core.phase(wes.id), "still in their session");
    }

    @Test
    void anAreaReachingBelowTheWorldsFloorIsHeldAboveIt() {
        WatchArea deep = new WatchArea(new Box(0, -90, 0, 40, 40, 40), 10, 20, 10, 0f).within(-64, 320);
        assertEquals(-64, deep.box().minY(), "the world's floor");
        double[] back = deep.putBack(10, -80, 10);
        assertEquals(-64, back[1], 1e-9, "feet on the floor, never in the void");
        assertEquals(WatchArea.Keep.CANCEL, deep.keep(10, -60, 10, 10, -65, 10), "a move below it is cancelled");
    }
}
