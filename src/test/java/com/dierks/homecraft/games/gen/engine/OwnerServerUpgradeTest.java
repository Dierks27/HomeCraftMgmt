package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.command.GamesCheck;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
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
        assertEquals(0, server.host.logged(Level.WARNING, "isn't in its claimed region"), "the move this version makes"
                + " is news, not a WARN (ENG-R3-01)");
        assertEquals(1, server.host.logged(Level.INFO, GOLF + "'s course from before this version stays closed in its"
                + " old area; a new one is built in its new area once that is checked."), "said once, as an INFO");
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
        for (String id : List.of(GOLF, BOAT, CLASSIC)) {
            String old = server.host.store.meta(GenAdminKeys.old(id));
            assertTrue(old != null && old.contains("|emptied@"), id + ": kept, marked emptied, until it is on disk: "
                    + old);
        }
        assertTrue(server.guarded(GOLF_A) && server.guarded(BOAT_A), "and still guarded");
        server.saved();
        for (String id : List.of(GOLF, BOAT, CLASSIC)) {
            assertNull(server.host.store.meta(GenAdminKeys.old(id)), id + ": let go once the world was saved");
        }
        assertFalse(server.guarded(GOLF_A) || server.guarded(BOAT_A), "and no longer guarded");
        assertEquals(List.of(), server.failures(), "everything else as it was");
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
        GenService.SlotReport moving = server.gen.report().stream().filter(r -> r.id().equals(GOLF)).findFirst()
                .orElseThrow();
        assertTrue(moving.moving() && moving.lastError() == null, "ENG-R3-01: the check reads golf as moving to its new"
                + " area (an OK line), not as a course that failed its check: " + moving);
        GamesCheck.Line waiting = GamesCheck.oldArea(new GamesCheck.OldArea(GOLF, "Golf of the Week",
                GamesCheck.OldState.valueOf(before.get(0).state().name()), before.get(0).where(), null, 0, 0, 0));
        assertEquals(GamesCheck.Status.OK, waiting.status(), "the check: an OK line while it waits");
        assertTrue(waiting.what().startsWith("Golf of the Week moved to its new area; its old area"), waiting.what());

        boolean sawRunning = false;
        for (int t = 0; t < 20 * 60 * 20 && !server.allEmptied(); t++) {
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
        assertFalse(server.gen.report().stream().filter(r -> r.id().equals(GOLF)).findFirst().orElseThrow().moving(),
                "golf is claimed at its new area now: no longer moving");
        assertTrue(server.gen.summary().stream().anyMatch(l -> l.startsWith(GOLF + ": its old area (half A x"
                + " 7488..7551") && l.contains("is empty; it stays guarded until the world has been saved")),
                "status says each waits for the world to be saved: " + server.gen.summary());
        server.saved();
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
        for (int s = 0; s < 20 * 60 && !(server.golfUp() && server.allEmptied()); s++) {
            server.drive(1);
        }
        assertEquals(0, server.world.count(GOLF_A) + server.world.count(GOLF_B), "the rest went at the next start");
        assertTrue(server.host.store.meta(GenAdminKeys.old(GOLF)).contains("|emptied@"), "the record marked emptied");
        assertTrue(server.golfUp(), "golf's new course checked and open again");
        server.saved();
        assertNull(server.host.store.meta(GenAdminKeys.old(GOLF)), "and gone once the world was saved");
        assertFalse(server.guarded(GOLF_A), "the guard let the emptied area go");
    }

    // ---- F10: config.yml couldn't be saved at revision 21 ----------------------------------------------------

    @Test
    void aConfigTheUpdateCouldntSaveKeepsTheMovedAreasWhereTheyWereBuiltAndDoesNothingThere() throws Exception {
        server = new OwnerServer().at036();
        String why = "config.yml couldn't be saved at this update (it is still at config revision 19, and revision 21"
                + " moves this area), so it stays where it was built, closed, and nothing is built or emptied there"
                + " until the file can be written";
        server.startHeld(why);
        server.drive(10 * 60);
        assertEquals(List.of(), server.writesSinceUpgrade(), "not one block written: no claim, build, reroll or"
                + " emptying for the held areas (and nothing to heal anywhere else)");
        assertEquals(OwnerServer.GOLF_036, server.host.store.meta(GenAdminKeys.claim(GOLF)),
                "golf's claim is 0.36's: nothing was claimed at 7488 at the new size");
        assertEquals(OwnerServer.BOAT_036, server.host.store.meta(GenAdminKeys.claim(BOAT)), "nor for the boat");
        assertEquals(OwnerServer.CLASSIC_036, server.host.store.meta(GenAdminKeys.claim(CLASSIC)), "nor Classic Golf");
        GenTag oldGolf = server.gen.liveTag(GOLF);
        assertFalse(oldGolf != null && server.gen.live(GOLF, oldGolf), "golf's 0.36 course stays closed");
        GenService.SlotReport golf = server.gen.report().stream().filter(r -> r.id().equals(GOLF)).findFirst()
                .orElseThrow();
        assertEquals(why, golf.problem(), "and says why");
        assertEquals(1, server.host.logged(Level.SEVERE, GOLF + " is off: " + why), "once, as a SEVERE");
        assertTrue(server.guarded(GOLF_A) && server.guarded(GOLF_B), "its 0.36 area stays guarded at its own size");
        List<OldAreas.Area> held = server.gen.oldAreas();
        assertTrue(held.stream().allMatch(a -> a.state() == OldAreas.State.HELD && why.equals(a.detail())),
                "each old area waits for the file, saying so: " + held);
        assertEquals(0, server.host.logged(Level.INFO, "area changed with this version"), "no move is announced");
        List<String> said = new ArrayList<>();
        server.gen.tidy(GOLF, true, said::add);
        assertFalse(said.stream().anyMatch(l -> l.contains("Emptying")), "tidy empties nothing there either: " + said);
        for (String id : OwnerServer.OTHERS) {
            assertTrue(server.gen.live(id, server.gen.liveTag(id)), id + " opens as usual");
        }

        // the next start reads a saved revision 21: the update goes as it always would
        server.gen.stop();
        server.upgrade(GenService.RECORDED, 20);
        assertEquals(List.of(), server.failures(), "moved once, straight to the new spots");
        assertEquals(1, server.gen.liveTag(GOLF).reroll(), "golf rerolled once, not twice");
    }

    @Test
    void aHoldLiftedByAReloadMovesTheAreasAsARestartWouldWithNoMoveWarning() throws Exception {
        // ENG08: config.yml is saved at last and /hcm reload reads revision 21 into the same running engine
        server = new OwnerServer().at036();
        String why = "config.yml couldn't be saved at this update (it is still at config revision 19, and revision 21"
                + " moves this area), so it stays where it was built, closed, and nothing is built or emptied there"
                + " until the file can be written";
        server.startHeld(why);
        server.drive(2 * 60);
        assertEquals(List.of(), server.writesSinceUpgrade(), "(held: nothing written)");
        List<String> on = new ArrayList<>(OwnerServer.OTHERS);
        on.add(GOLF);
        server.host.settings = GenKit.weekly(on.toArray(String[]::new)); // the reload: revision 21's spots
        for (int s = 0; s < 20 * 60 && !(server.golfUp() && server.allEmptied()); s++) {
            server.drive(1);
        }
        assertTrue(server.golfUp(), "golf's v4 course is up at its new spot: " + server.gen.summary());
        assertTrue(GOLF_V4_A.equals(server.gen.half(server.gen.liveTag(GOLF)))
                || GOLF_V4_B.equals(server.gen.half(server.gen.liveTag(GOLF))), "in the new column");
        assertEquals(1, server.gen.liveTag(GOLF).reroll(), "this week's next reroll, as after a restart");
        assertEquals(0, server.world.count(GOLF_A) + server.world.count(GOLF_B), "its old area emptied by itself");
        assertEquals(0, server.host.logged(Level.WARNING, "moved from"), "no 'moved ... not cleared' WARN, with"
                + " boxes at the wrong size and advice that can't be followed: " + warnings());
        assertEquals(0, server.host.logged(Level.INFO, GOLF + " moved from"), "nor any move line: the hold is no move");
        assertEquals(1, server.host.logged(Level.INFO, GOLF + "'s area changed with this version: its halves are 128"
                + " x 16 x 224 now"), "the resize line says it, once, as at a restart");
        assertTrue(server.host.logged(Level.INFO, GOLF + "'s old area in games (half A x 7488..7551") > 0,
                "and RETIRE, at the area's recorded size");
    }

    @Test
    void aClassicRecallThatEndedAroundTheUpdateNeverClearsTheNewClassicAreaNobodyClaimed() throws Exception {
        // ENG01: a golf course recalled into 0.36's Classic Golf, its recall over before the first v4 start; v4's
        // Classic Golf halves (x 8768..8895 / 9472..9599, z 4896..5119), never claimed or scanned, hold someone's own
        server = new OwnerServer().at036();
        Plan plan = OwnerServer.PondGolf.plan(Slots.DAILY_GOLF, OwnerServer.CLASSIC_A, 0x77L, 3);
        for (BlockOp op : plan.ops()) {
            server.world.put(op.x(), op.y(), op.z(), plan.palette().get(op.state()));
        }
        GenTag og = server.oldGolf;
        long from = server.host.now - 6L * 86_400_000L;
        GenTag tag = new GenTag(og.slot(), og.generator(), plan.algo(), og.day(), og.reroll(), og.seed(), 'A',
                plan.hash(), og.refMs(), og.goldMs(), og.silverMs(), og.attempts(), og.witness(), from, og.cadence(),
                new GenTag.Recall(CLASSIC, from, og.day()));
        server.host.store.flip(GenService.row(Slots.CLASSIC_GOLF, OwnerServer.W, plan.course(), tag, null,
                server.host.now, "Classic: Golf of the Week"), Map.of());
        server.host.store.meta(GenAdminKeys.recall(CLASSIC), new ClassicWant(og.slot(), og.editionKey(), from,
                server.host.now - 3_600_000L, false).text()); // over an hour before the restart
        server.world.put(8800, 165, 5000, "minecraft:diamond_block"); // v4's Classic Golf half A
        server.world.put(9500, 165, 5000, "minecraft:diamond_block"); // and half B
        Box newA = Box.sized(8768, 160, 4896, 128, 16, 224);
        Box newB = Box.sized(9472, 160, 4896, 128, 16, 224);

        server.upgrade(GenService.RECORDED, 20);
        assertEquals("minecraft:diamond_block", server.world.at(8800, 165, 5000), "the block in half A stands");
        assertEquals("minecraft:diamond_block", server.world.at(9500, 165, 5000), "and the one in half B");
        assertTrue(server.writesSinceUpgrade().stream().noneMatch(w -> newA.contains(w.x(), w.y(), w.z())
                || newB.contains(w.x(), w.y(), w.z())), "not one write where Fresh Courses holds nothing");
        assertEquals(1, server.host.logged(Level.INFO, CLASSIC + " is closed (its recall is over). Its area isn't"
                + " claimed, so nothing is cleared there"), "the close says why nothing is cleared: " + infos());
        assertEquals(0, server.host.logged(Level.INFO, CLASSIC + "'s halves are empty"), "no clearing ran");
        assertNull(server.host.store.meta(GenAdminKeys.claim(CLASSIC)), "and nothing was claimed there");
        assertEquals(0, server.world.count(OwnerServer.CLASSIC_A) + server.world.count(OwnerServer.CLASSIC_B),
                "the closed course stood in the old area, which was emptied by itself");
    }

    @Test
    void anUnrecallOfAClassicThatIsntClaimedClearsNothing() throws Exception {
        // ENG01's safety net, and the admin's path to closeClassic: an unrecall before the recall's own scan
        server = new OwnerServer().at036();
        server.startUpgrade(GenService.RECORDED);
        server.world.put(8800, 165, 5000, "minecraft:diamond_block");
        SlotState s = server.gen.slot(CLASSIC);
        assertFalse(s.claimed, "fixture: v4's Classic Golf area is nobody's yet");
        s.bothDirty = true; // whatever set it, the clearing must refuse a region it doesn't hold
        server.drive(20 * 60);
        assertEquals("minecraft:diamond_block", server.world.at(8800, 165, 5000), "never cleared unscanned");
        assertFalse(s.bothDirty, "and it isn't tried again");
        assertEquals(1, server.host.logged(Level.INFO, CLASSIC + "'s halves aren't claimed, so they aren't cleared"),
                "said once: " + infos());
    }

    // ---- ENG03: back over an area emptied while they were away -------------------------------------------

    @Test
    void someoneWhoLoggedOutOnGolfsOldCourseIsMovedToSafetyWhenTheyJoinAfterItWasEmptied() throws Exception {
        server = new OwnerServer().at036();
        server.upgrade(GenService.RECORDED, 20);
        assertEquals(0, server.world.count(GOLF_A), "(golf's old half A was emptied while they were away)");
        server.saved(); // and its record let go (F09): the check's record of it still says where it was
        assertNull(server.host.store.meta(GenAdminKeys.old(GOLF)), "(let go)");

        Person owner = standing("Owner", GOLF_A.minX() + 20.5, 161, GOLF_A.minZ() + 40.5, null);
        assertTrue(server.gen.joined(owner), "over nothing where the course was: moved before they fall");
        assertTrue(server.host.moved.contains(owner.id()), "to the safe spot");
        assertTrue(server.host.told.contains(com.dierks.homecraft.games.gen.api.GenCopy.MOVED), "and told why");

        Person golfer = standing("Golfer", GOLF_A.minX() + 20.5, 161, GOLF_A.minZ() + 40.5, "golf");
        assertFalse(server.gen.joined(golfer), "someone in a game is the game's to look after");
        Person far = standing("Walker", 100.5, 161, 100.5, null);
        assertFalse(server.gen.joined(far), "over nothing far from every area Fresh Courses empties: not ours");
        Box easy = server.gen.half(server.gen.liveTag("fresh_parkour_easy"));
        long pad = server.world.blocks.keySet().stream().filter(p -> GenKit.FakeWorld.inside(easy, p)).findFirst()
                .orElseThrow();
        int px = (int) (pad >> 38);
        int pz = (int) ((pad << 26) >> 38);
        int py = (int) ((pad << 52) >> 52);
        Person onCourse = standing("Runner", px + 0.5, py + 1, pz + 0.5, null);
        assertFalse(server.gen.joined(onCourse), "someone standing on a course stays where they are");
        assertEquals(List.of(owner.id()), server.host.moved, "only the one over nothing was moved");
    }

    /** A player at (x, y, z) in the Games world, in a session of {@code game} or none, their chunk loaded. */
    private Person standing(String name, double x, double y, double z, String game) {
        server.world.load((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, ok -> { });
        return new Person(java.util.UUID.randomUUID(), name, OwnerServer.W, x, y, z, game,
                game == null ? null : GOLF);
    }

    private String warnings() {
        return String.join("\n", server.host.logs.stream().filter(r -> r.getLevel() == Level.WARNING)
                .map(r -> r.getMessage()).toList());
    }

    private String infos() {
        return String.join("\n", server.host.logs.stream().filter(r -> r.getLevel() == Level.INFO)
                .map(r -> r.getMessage()).toList());
    }

    private String severe() {
        return String.join("\n", server.host.logs.stream().filter(r -> r.getLevel() == Level.SEVERE)
                .map(r -> r.getMessage()).toList());
    }
}
