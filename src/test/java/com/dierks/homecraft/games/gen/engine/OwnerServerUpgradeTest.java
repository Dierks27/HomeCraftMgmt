package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.command.GamesCheck;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static com.dierks.homecraft.games.gen.engine.OwnerServer.BOAT;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.BOAT_A;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.BOAT_B;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.CLASSIC;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.GOLF;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.GOLF_A;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.GOLF_B;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.GOLF_V4_A;
import static com.dierks.homecraft.games.gen.engine.OwnerServer.GOLF_V4_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's own server, updated to v4 with nobody typing anything ({@link OwnerServer}: 0.36 with Golf of
 * the Week live at 7488,160,4096 with its ponds, Ice Boat off with an algo-3 preview in its half A at
 * 6080,160,5888, the Clubhouse, the other courses live), on the real engine over a real in-memory database.
 *
 * <p>Pinned: Golf of the Week is closed at the restart and replaced by a v4 course at its new spot, this
 * week's next reroll on a fresh board (so its first-finish reward isn't paid twice); its old halves are
 * emptied, the ponds drained before any wall goes; the boat's old halves are emptied, the boat stays off and
 * a preview builds at its new spot; Classic Golf's old claim is let go; nothing else changes, block for
 * block; status and {@code /hcm games check} say what happened; and a stop in the middle of emptying an old
 * area is finished at the next start. {@code RetireMutationTest} breaks the recorded-size guard and sees this
 * scenario fail.
 */
class OwnerServerUpgradeTest {

    private OwnerServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            if (server.gen != null) {
                server.gen.stop();
            }
            server.host.connection.close();
        }
    }

    @Test
    void theOwnersServerUpdatesItselfWithNothingTyped() throws Exception {
        server = new OwnerServer().at036();
        assertTrue(server.world.count(GOLF_A, true) > 0, "fixture: golf's ponds hold water");
        assertTrue(server.world.count(GOLF_A, false) > 0, "fixture: golf's course stands in its 0.36 half A");
        assertTrue(server.world.count(BOAT_A) > 0, "fixture: the boat's algo-3 preview stands in its 0.36 half A");

        server.upgrade(GenService.RECORDED, 20);
        assertEquals(List.of(), server.failures(), "the update did everything, and only that");

        GenTag golf = server.gen.liveTag(GOLF);
        assertTrue(GOLF_V4_A.equals(server.gen.half(golf)) || GOLF_V4_B.equals(server.gen.half(golf)),
                "Golf of the Week is open at its new spot: " + server.gen.half(golf));
        assertEquals(4, golf.algo(), "a v4 course");
        assertEquals(server.oldGolf.edition(), golf.edition(), "this week's: stars and the first-finish reward are"
                + " kept per week, so finishing it again pays nothing twice");
        assertEquals(1, golf.reroll(), "on a fresh board (reroll 1)");
        assertEquals(0, server.world.count(GOLF_A) + server.world.count(GOLF_B), "golf's old halves are empty");
        assertEquals(0, server.world.count(BOAT_A) + server.world.count(BOAT_B), "and so are the boat's");
        assertTrue(server.host.logged(Level.INFO, GOLF + "'s old area in games (half A x 7488..7551") > 0,
                "the console says so, with the area at its recorded size");
        assertTrue(server.host.logged(Level.INFO, "the ponds drained first") > 0, "ponds first, it says");
        assertTrue(server.host.logged(Level.INFO, BOAT + "'s old area in games (half A x 6080..6207") > 0,
                "and the boat's");
        assertEquals(0, server.host.logged(Level.WARNING, "isn't emptied"), "nothing was in any old area's way");
        assertEquals(0, server.host.logged(Level.SEVERE, ""), "no course went off: " + severe());
        for (String id : List.of(GOLF, BOAT, CLASSIC)) {
            OldAreas.Retired r = OldAreas.Retired.parse(server.host.store.meta(GenAdminKeys.retired(id)));
            assertNotNull(r, id + ": what was emptied is on record for the check");
            assertEquals(0, r.left(), id + ": nothing that isn't Fresh Courses' stood there");
        }
        assertTrue(OldAreas.Retired.parse(server.host.store.meta(GenAdminKeys.retired(GOLF))).removed() > 0,
                "golf's old course was taken away");
        assertEquals(0, OldAreas.Retired.parse(server.host.store.meta(GenAdminKeys.retired(CLASSIC))).removed(),
                "Classic Golf's old halves were empty: read, not written");
    }

    @Test
    void theBoatStaysOffAndBuildsItsPreviewAtItsNewSpot() throws Exception {
        server = new OwnerServer().at036();
        server.upgrade(GenService.RECORDED, 20);
        assertEquals(List.of(), server.failures(), "(the update itself)");
        server.host.settings = GenKit.fast(server.host.settings); // 2,400 chunks to look at: the test's clock only
        List<String> said = new ArrayList<>();
        server.gen.preview(BOAT, null, said::add);
        for (int s = 0; s < 30 * 60 && server.gen.slot(BOAT).preview == null; s++) {
            server.drive(1);
        }
        assertNotNull(server.gen.slot(BOAT).preview, "a preview of the boat stands: " + said);
        Box at = server.gen.slot(BOAT).half(server.gen.slot(BOAT).preview.half());
        assertEquals(OwnerServer.BOAT_V4_A, at, "in its new area, north at z 2880, 480 x 176 x 640");
        assertTrue(server.world.count(at) > 0, "built there");
        assertEquals(Regions.claim(Slots.ICE_BOAT, OwnerServer.W, Slots.ICE_BOAT.origin(), Slots.HALF_GAP),
                server.host.store.meta(GenAdminKeys.claim(BOAT)), "its new area is claimed");
        GenService.SlotReport boat = server.gen.report().stream().filter(r -> r.id().equals(BOAT)).findFirst()
                .orElseThrow();
        assertFalse(boat.wanted(), "and it is still off: the owner's one command switches it on");
        assertNull(boat.live(), "nothing opened");
        assertEquals(0, server.world.count(BOAT_A) + server.world.count(BOAT_B), "nothing came back at the old spot");
    }

    @Test
    void statusAndTheCheckSayWhatHappenedAlongTheWay() throws Exception {
        server = new OwnerServer().at036();
        server.startUpgrade(GenService.RECORDED);
        List<OldAreas.Area> before = server.gen.oldAreas();
        assertEquals(List.of(GOLF, BOAT, CLASSIC), before.stream().map(OldAreas.Area::slot).toList(),
                "right after the restart three old areas are recorded");
        assertTrue(before.stream().allMatch(a -> a.state() == OldAreas.State.WAITING), "each waiting: " + before);
        assertTrue(server.gen.summary().stream().anyMatch(l -> l.startsWith(GOLF + ": its old area (half A x"
                + " 7488..7551") && l.endsWith("is emptied by itself once nothing else is being built")),
                "status names golf's, at its recorded size: " + server.gen.summary());
        assertTrue(server.host.logged(Level.INFO, GOLF + "'s area changed with this version: its halves are 128 x 16"
                + " x 224 now") > 0, "the console explains the move as news, not a problem");
        assertEquals(0, server.host.logged(Level.WARNING, "was claimed at another place"),
                "no 'clear them by hand' WARN for an area this version moved");
        GamesCheck.Line waiting = GamesCheck.oldArea(new GamesCheck.OldArea(GOLF, "Golf of the Week",
                GamesCheck.OldState.valueOf(before.get(0).state().name()), before.get(0).where(), null, 0, 0, 0));
        assertEquals(GamesCheck.Status.OK, waiting.status(), "the check: an OK line while it waits");
        assertTrue(waiting.what().startsWith("Golf of the Week moved to its new area; its old area"), waiting.what());

        boolean sawRunning = false;
        for (int t = 0; t < 20 * 60 * 20 && !server.gen.oldAreas().isEmpty(); t++) {
            server.tick(t);
            for (OldAreas.Area a : server.gen.oldAreas()) {
                if (a.state() == OldAreas.State.RUNNING) {
                    sawRunning = true;
                    assertTrue(server.gen.status(a.slot()).stream().anyMatch(l -> l.contains("emptying its old area")),
                            "gen status says it is being emptied: " + server.gen.status(a.slot()));
                }
            }
        }
        assertTrue(sawRunning, "each was seen being emptied");
        assertEquals(List.of(), server.gen.oldAreas(), "then none is left");
        Map<String, OldAreas.Retired> done = server.gen.retiredAreas();
        assertEquals(List.of(GOLF, BOAT, CLASSIC), List.copyOf(done.keySet()), "and the check has each on record");
        GamesCheck.Line emptied = GamesCheck.oldArea(new GamesCheck.OldArea(GOLF, "Golf of the Week",
                GamesCheck.OldState.EMPTIED, Regions.describeClaim(done.get(GOLF).claim()), null, 100,
                done.get(GOLF).removed(), 0));
        assertEquals(GamesCheck.Status.OK, emptied.status(), "an OK line");
        assertTrue(emptied.what().contains("its old area is empty (" + done.get(GOLF).removed() + " blocks taken away)"),
                emptied.what());
    }

    @Test
    void aStopInTheMiddleOfEmptyingGolfsOldAreaIsFinishedAtTheNextStart() throws Exception {
        server = new OwnerServer().at036();
        server.startUpgrade(GenService.RECORDED);
        long water = server.world.count(GOLF_A, true);
        boolean stopped = false;
        boolean golfWasUp = false;
        for (int t = 0; t < 20 * 60 * 20 && !stopped; t++) {
            server.tick(t);
            boolean golfRetiring = server.gen.oldAreas().stream().anyMatch(a -> a.slot().equals(GOLF)
                    && a.state() == OldAreas.State.RUNNING);
            if (golfRetiring && server.world.count(GOLF_A, true) == 0 && server.world.count(GOLF_A, false) > 0) {
                golfWasUp = server.golfUp();
                server.gen.stop(); // the server dies here: the ponds are drained, the walls still stand
                stopped = true;
            }
        }
        assertTrue(stopped, "fixture: caught after golf's ponds were drained and before its walls went");
        assertTrue(water > 0, "(there was water)");
        assertEquals(OwnerServer.GOLF_036, server.host.store.meta(GenAdminKeys.old(GOLF)),
                "the old area stays on record until it is verified empty");
        assertTrue(golfWasUp, "(golf's new course was up before its old area was emptied)");

        server.gen = new GenService(server.host, OwnerServer.planners(4));
        server.gen.start();
        assertTrue(server.guarded(GOLF_A) && server.guarded(GOLF_B), "after the restart it is guarded again, at its"
                + " recorded size");
        server.gen.worldsReady();
        for (int s = 0; s < 20 * 60 && !(server.golfUp() && server.gen.oldAreas().isEmpty()); s++) {
            server.drive(1);
        }
        assertEquals(0, server.world.count(GOLF_A) + server.world.count(GOLF_B), "the rest went at the next start");
        assertNull(server.host.store.meta(GenAdminKeys.old(GOLF)), "and the record with it");
        assertTrue(server.golfUp(), "golf's new course checked and open again");
        assertFalse(server.guarded(GOLF_A), "the guard let the emptied area go");
    }

    private String severe() {
        return String.join("\n", server.host.logs.stream().filter(r -> r.getLevel() == Level.SEVERE)
                .map(r -> r.getMessage()).toList());
    }
}
