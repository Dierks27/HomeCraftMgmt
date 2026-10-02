package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.command.GamesCheck;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.engine.OwnerServer.LoggingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RETIRE, the one move mechanism (V4-DECISIONS), on the real engine over a real in-memory database: an old
 * area a slot left behind is emptied of Fresh Courses' own blocks only, at the sizes its claim recorded,
 * water first, after the area's safety check, and forgotten once verified empty; a crash is finished at the
 * next start ({@code OwnerServerUpgradeTest}). Pinned here:
 * <ul>
 *   <li>both halves of a 0.35-shaped claim (gap 32, the old size) are emptied, ponds first, and nothing is
 *       written outside them ({@link #bothHalves}, which {@code RetireMutationTest} breaks);</li>
 *   <li>a block that isn't Fresh Courses' stays where it is and is named, in the console and the check;</li>
 *   <li>something within 16 blocks of an old half holds it: nothing is written, it stays guarded and listed,
 *       one WARN names what is in the way; once that is gone it is emptied;</li>
 *   <li>an owner's own move (origin only) is guarded but emptied only when an admin says so
 *       ({@code tidy confirm}); the verb lists it first;</li>
 *   <li>a slot resized in place has its old area emptied before its new one is scanned and built; a build
 *       next to an old area that can't be emptied waits and says why;</li>
 *   <li>a live row whose course stands in an old area is never healed or opened there.</li>
 * </ul>
 */
class RetireTest {

    private static final String W = GenKit.WORLD;
    private static final String GOLF = Slots.DAILY_GOLF.id();
    private static final String BOAT = Slots.ICE_BOAT.id();
    private static final String DROPPER = Slots.FRESH_DROPPER.id();

    private Host host;
    private LoggingWorld world;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        if (host != null) {
            host.connection.close();
        }
    }

    private void host(String... on) {
        host = new Host(GenKit.at(2026, 10, 2, 19, 0), on);
        world = new LoggingWorld(W);
        host.worlds.put(W, world);
        host.settings = GenKit.weekly(on);
    }

    private void boot(GenService.OldHalves geometry) {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, OwnerServer.planners(4), geometry);
        gen.start();
        gen.worldsReady();
    }

    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    /** Until every old area is emptied (F09: still recorded, and guarded, until the world is saved). */
    private void driveUntilNoOldArea(int minutes) {
        for (int s = 0; s < minutes * 60 && !allEmptied(); s++) {
            drive(1);
        }
    }

    private boolean allEmptied() {
        return gen.oldAreas().stream().allMatch(a -> a.state() == OldAreas.State.EMPTIED);
    }

    /** Two saves of the Games world (Paper's autosave, or /save-all): what was emptied is on disk now. */
    private void saved() {
        gen.worldSaved(W);
        gen.worldSaved(W);
    }

    /** A pond (water over blue concrete, rimmed with moss) and a wall line at the corner of {@code half}. */
    private static void pondAndWalls(GenKit.FakeWorld w, Box half) {
        int y = half.minY() + 3;
        for (int x = half.minX() + 2; x <= half.minX() + 8; x++) {
            for (int z = half.minZ() + 2; z <= half.minZ() + 8; z++) {
                boolean water = x > half.minX() + 2 && x < half.minX() + 8 && z > half.minZ() + 2 && z < half.minZ() + 8;
                w.put(x, y - 1, z, "minecraft:blue_concrete");
                w.put(x, y, z, water ? "minecraft:water[level=0]" : Palette.MOSS);
            }
        }
        for (int x = half.minX(); x < half.maxX(); x++) {
            w.put(x, y, half.maxZ() - 1, Palette.GOLF_WALL);
            w.put(x, y - 1, half.maxZ() - 1, Palette.TURF_DARK);
        }
    }

    // ---- the recorded sizes (the mutation proof's second scenario) ---------------------------------------

    /** A 0.35-shaped golf claim: 7 fields, so its halves stand 32 apart, at 0.35's 64 x 16 x 128. */
    static final String GOLF_035 = "games,4864,160,4096,64,16,128";
    static final Box GOLF_035_A = Box.sized(4864, 160, 4096, 64, 16, 128);
    static final Box GOLF_035_B = Box.sized(4960, 160, 4096, 64, 16, 128);

    /**
     * Golf's 0.35 area, both halves holding a course with a pond, emptied with {@code geometry} for where an old
     * claim stands: what went wrong, or nothing. Golf itself is off (an old area is emptied whatever the slot).
     */
    static List<String> bothHalves(GenService.OldHalves geometry) throws Exception {
        RetireTest t = new RetireTest();
        try {
            t.host();
            t.host.store.meta(GenAdminKeys.claim(GOLF), GOLF_035);
            pondAndWalls(t.world, GOLF_035_A);
            pondAndWalls(t.world, GOLF_035_B);
            Map<Long, String> elsewhere = new java.util.HashMap<>(t.world.blocks);
            elsewhere.keySet().removeIf(p -> GenKit.FakeWorld.inside(GOLF_035_A, p)
                    || GenKit.FakeWorld.inside(GOLF_035_B, p));
            t.world.put(GOLF_035_B.maxX() + 1, 165, GOLF_035_B.minZ(), "minecraft:oak_planks"); // the owner's, outside
            t.boot(geometry);
            List<String> out = new ArrayList<>();
            if (!(t.guarded(GOLF_035_A) && t.guarded(GOLF_035_B))) {
                out.add("the old halves weren't guarded where the claim recorded them");
            }
            t.driveUntilNoOldArea(20);
            if (t.world.count(GOLF_035_A) + t.world.count(GOLF_035_B) != 0) {
                out.add("the old halves still hold " + (t.world.count(GOLF_035_A) + t.world.count(GOLF_035_B))
                        + " blocks");
            }
            for (OwnerServer.LoggingWorld.Write w : t.world.log) {
                if (!GOLF_035_A.contains(w.x(), w.y(), w.z()) && !GOLF_035_B.contains(w.x(), w.y(), w.z())) {
                    out.add("a block was written outside the recorded halves: " + w);
                    break;
                }
            }
            for (Box half : List.of(GOLF_035_A, GOLF_035_B)) {
                boolean solidGone = false;
                for (OwnerServer.LoggingWorld.Write w : t.world.log) {
                    if (half.contains(w.x(), w.y(), w.z()) && w.was() != null) {
                        boolean water = w.was().startsWith("minecraft:water");
                        if (water && solidGone) {
                            out.add("a pond in " + half.describe() + " was drained after a wall went");
                            break;
                        }
                        solidGone |= !water;
                    }
                }
            }
            if (!"minecraft:oak_planks".equals(t.world.at(GOLF_035_B.maxX() + 1, 165, GOLF_035_B.minZ()))) {
                out.add("the owner's block next to the old area went");
            }
            if (t.host.store.meta(GenAdminKeys.claim(GOLF)) != null) {
                out.add("the old claim still holds the claim key");
            }
            if (!Regions.oldEmptied(t.host.store.meta(GenAdminKeys.old(GOLF))).containsKey(GOLF_035)) {
                out.add("the old claim isn't marked emptied (and kept) until the world is saved");
            }
            t.saved();
            if (t.host.store.meta(GenAdminKeys.old(GOLF)) != null) {
                out.add("the old claim is still on record after the world was saved");
            }
            return out;
        } finally {
            t.tearDown();
        }
    }

    private boolean guarded(Box b) {
        return gen.inArea(W, b.minX(), b.minY(), b.minZ()) && gen.inArea(W, b.maxX(), b.maxY(), b.maxZ());
    }

    @Test
    void bothHalvesOfA035ShapedClaimAreEmptiedAtTheSizesItRecordedPondsFirst() throws Exception {
        assertEquals(List.of(), bothHalves(GenService.RECORDED), "emptied where and as big as 0.35 built them,"
                + " water first, nothing else touched");
    }

    // ---- foreign blocks -------------------------------------------------------------------------------

    @Test
    void aBlockThatIsntFreshCoursesStaysWhereItIsAndIsNamed() throws Exception {
        host();
        host.store.meta(GenAdminKeys.claim(BOAT), OwnerServer.BOAT_036);
        for (int x = 6090; x < 6150; x++) {
            world.put(x, 165, 5900, Palette.TRACK);
            world.put(x, 166, 5899, Palette.TRACK_WALL);
        }
        world.put(6100, 166, 5900, Palette.sign(0));
        world.put(6120, 170, 5950, "minecraft:bedrock"); // a WorldEdit paste: not a block any plan may use
        world.put(6800, 170, 5950, "minecraft:chest[facing=north,type=single,waterlogged=false]");
        boot(GenService.RECORDED);
        driveUntilNoOldArea(20);
        saved();
        assertEquals(List.of(), gen.oldAreas(), "the old area is done with");
        assertEquals(2, world.count(OwnerServer.BOAT_A) + world.count(OwnerServer.BOAT_B),
                "only the two foreign blocks are left");
        assertEquals("minecraft:bedrock", world.at(6120, 170, 5950), "the paste stands");
        assertTrue(world.at(6800, 170, 5950).startsWith("minecraft:chest"), "and so does the chest");
        OldAreas.Retired r = OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(BOAT)));
        assertNotNull(r, "on record");
        assertEquals(2, r.left(), "two left");
        assertTrue(r.firstLeft().contains("6120,170,5950 minecraft:bedrock"), "named: " + r.firstLeft());
        assertEquals(121, r.removed(), "the boat's 60 + 60 track and wall blocks and its sign were taken away");
        assertTrue(host.logged(Level.WARNING, "2 blocks that aren't Fresh Courses' were left there as they are"
                + " (first at") > 0, "a WARN names them");
        GamesCheck.Line line = GamesCheck.oldArea(new GamesCheck.OldArea(BOAT, "Ice Boat", GamesCheck.OldState.EMPTIED,
                Regions.describeClaim(r.claim()), r.firstLeft().get(0), 100, r.removed(), r.left()));
        assertEquals(GamesCheck.Status.WARN, line.status(), "the check warns");
        assertTrue(line.what().contains("2 blocks that aren't Fresh Courses' were left there (first at"), line.what());
        assertTrue(line.fix().contains("remove them by hand"), "and says they are the owner's to remove: " + line.fix());
        assertFalse(guarded(OwnerServer.BOAT_A), "nothing guards that area any more");
    }

    // ---- F09: emptied, then known to be on disk --------------------------------------------------------------

    /** The boat's 0.36 area with a track and a sign in half A, its claim as 0.36 left it. */
    private void boatAt036() throws SQLException {
        host();
        host.store.meta(GenAdminKeys.claim(BOAT), OwnerServer.BOAT_036);
        for (int x = 6090; x < 6150; x++) {
            world.put(x, 165, 5900, Palette.TRACK);
            world.put(x, 166, 5899, Palette.TRACK_WALL);
        }
        world.put(6100, 166, 5900, Palette.sign(0));
    }

    @Test
    void anEmptiedAreaStaysRecordedAndGuardedUntilTheWorldHasBeenSavedTwice() throws Exception {
        boatAt036();
        boot(GenService.RECORDED);
        driveUntilNoOldArea(20);
        assertEquals(0, world.count(OwnerServer.BOAT_A), "emptied");
        String stored = host.store.meta(GenAdminKeys.old(BOAT));
        assertTrue(Regions.oldEmptied(stored).containsKey(OwnerServer.BOAT_036), "marked emptied@T, kept: " + stored);
        assertEquals(List.of(OwnerServer.BOAT_036), Regions.oldClaims(stored), "the claim reads as before");
        assertTrue(guarded(OwnerServer.BOAT_A) && guarded(OwnerServer.BOAT_B), "still guarded");
        assertEquals(OldAreas.State.EMPTIED, gen.oldAreas().get(0).state(), "listed as emptied");
        assertTrue(OldAreas.line(gen.oldAreas().get(0)).contains("is empty; it stays guarded until the world has been"
                + " saved"), "status says what it waits for: " + OldAreas.line(gen.oldAreas().get(0)));
        assertNotNull(OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(BOAT))),
                "the check's record of it is written at once (it shows for two weeks)");

        gen.worldSaved(W);
        assertNotNull(host.store.meta(GenAdminKeys.old(BOAT)), "one save may still be writing: kept");
        gen.worldSaved("some_other_world");
        gen.worldSaved("some_other_world");
        assertNotNull(host.store.meta(GenAdminKeys.old(BOAT)), "another world's saves say nothing of this one");
        gen.worldSaved(W);
        assertNull(host.store.meta(GenAdminKeys.old(BOAT)), "the second save of its world: let go");
        assertEquals(List.of(), gen.oldAreas(), "nothing listed");
        assertFalse(guarded(OwnerServer.BOAT_A), "nor guarded");
        assertEquals(1, host.logged(Level.INFO, BOAT + "'s old area in games (half A x 6080..6207, y 160..175, z"
                + " 5888..6015; half B x 6784..6911, y 160..175, z 5888..6015) has been saved empty: it is let go."),
                "said once: " + host.logs.stream().map(l -> l.getMessage()).toList());
    }

    @Test
    void aHardStopBeforeTheSaveBringsTheOldBlocksBackAndTheNextStartEmptiesThemAgain() throws Exception {
        boatAt036();
        Map<Long, String> before = new java.util.HashMap<>(world.blocks);
        boot(GenService.RECORDED);
        driveUntilNoOldArea(20);
        assertEquals(0, world.count(OwnerServer.BOAT_A), "emptied in memory");
        long first = OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(BOAT))).at();

        // kill -9 before any save: the chunks on disk still hold the old track, the database says emptied@T
        gen.stop();
        gen = null;
        world.blocks.clear();
        world.blocks.putAll(before);
        assertEquals(121, world.count(OwnerServer.BOAT_A), "the old track and its sign are back after the restart");
        host.now += 60_000;
        boot(GenService.RECORDED);
        assertTrue(guarded(OwnerServer.BOAT_A), "the record still guards it at the next start");
        driveUntilNoOldArea(20);
        assertEquals(0, world.count(OwnerServer.BOAT_A), "the next start emptied it again");
        assertEquals(1, host.logged(Level.WARNING, BOAT + "'s old area in games (half A x 6080..6207") , "with a WARN");
        assertTrue(host.logged(Level.WARNING, "had 121 blocks of its own back at this start (the world wasn't saved"
                + " after it was emptied): emptied again") == 1, "saying what happened: "
                + host.logs.stream().map(l -> l.getMessage()).toList());
        OldAreas.Retired r = OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(BOAT)));
        assertTrue(r.at() > first && r.removed() == 121, "the check's record is the latest emptying: " + r);
        long marked = Regions.oldEmptied(host.store.meta(GenAdminKeys.old(BOAT))).get(OwnerServer.BOAT_036);
        assertTrue(marked > first, "marked emptied anew, kept until it is known on disk");

        // a clean start next: it is looked at once more, nothing is written, and it is let go
        int writes = world.log.size();
        boot(GenService.RECORDED);
        driveUntilNoOldArea(20);
        for (int s = 0; s < 120 && host.store.meta(GenAdminKeys.old(BOAT)) != null; s++) {
            drive(1);
        }
        assertNull(host.store.meta(GenAdminKeys.old(BOAT)), "found still empty at a later start: let go");
        assertEquals(writes, world.log.size(), "without writing a block");
        assertEquals(1, host.logged(Level.INFO, "was still empty at this start, so it was saved that way: it is let"
                + " go"), "said once");
        assertEquals(121, OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(BOAT))).removed(),
                "and the check still says what was taken away (for two weeks)");
    }

    @Test
    void anAreaEmptiedBeforeItsSlotGrewInPlaceIsLookedAtAgainOnlyOutsideTheNewCourse() throws Exception {
        golfInPlace();
        Map<Long, String> before = new java.util.HashMap<>(world.blocks);
        boot(GenService.RECORDED);
        for (int s = 0; s < 20 * 60 && !(gen.liveTag(GOLF) != null && gen.live(GOLF, gen.liveTag(GOLF))
                && allEmptied()); s++) {
            drive(1);
        }
        Box newA = Box.sized(7488, 160, 4096, 128, 16, 224);
        Box newB = Box.sized(8192, 160, 4096, 128, 16, 224);
        assertEquals(newA, gen.half(gen.liveTag(GOLF)), "golf is up at its own spot, grown, in half A (which holds"
                + " its old half A)");

        // kill -9 before any save: the old pond is back in the old half A, inside the live course's half A
        gen.stop();
        gen = null;
        for (Map.Entry<Long, String> e : before.entrySet()) {
            if (GenKit.FakeWorld.inside(OwnerServer.GOLF_A, e.getKey())) {
                world.blocks.put(e.getKey(), e.getValue());
            }
        }
        int from = world.log.size();
        host.now += 60_000;
        boot(GenService.RECORDED);
        for (int s = 0; s < 20 * 60 && !(host.store.meta(GenAdminKeys.old(GOLF)) == null
                && gen.live(GOLF, gen.liveTag(GOLF))); s++) {
            drive(1);
        }
        assertNull(host.store.meta(GenAdminKeys.old(GOLF)), "the old area was looked at again (its half B, outside"
                + " the course) and let go");
        assertTrue(gen.live(GOLF, gen.liveTag(GOLF)), "the course is open: its own check took the pond out of its"
                + " half");
        assertEquals(0, world.count(OwnerServer.GOLF_B), "nothing in the old half B");
        assertEquals(0, host.logged(Level.WARNING, "old area (half A x 7488..7551"), "never held by its own course: "
                + host.logs.stream().map(l -> l.getMessage()).toList());
        for (OwnerServer.LoggingWorld.Write w : world.log.subList(from, world.log.size())) {
            assertTrue(newA.contains(w.x(), w.y(), w.z()) || newB.contains(w.x(), w.y(), w.z()),
                    "every write was in the course's own halves (its check, its spare emptied): " + w);
        }
    }

    // ---- something in the way -------------------------------------------------------------------------------

    @Test
    void somethingNextToAnOldAreaHoldsItAndNothingThereIsWrittenUntilItIsGone() throws Exception {
        host();
        host.store.meta(GenAdminKeys.claim(BOAT), OwnerServer.BOAT_036);
        for (int x = 6090; x < 6150; x++) {
            world.put(x, 165, 5900, Palette.TRACK);
        }
        Box arena = Box.sized(6080, 176, 6024, 48, 40, 48); // 8 blocks past the old half A
        host.extras = List.of(new Regions.Extra("falling_floors", arena));
        boot(GenService.RECORDED);
        drive(5 * 60);
        assertEquals(60, world.count(OwnerServer.BOAT_A), "nothing was written: the arena is 8 blocks off");
        assertTrue(world.log.isEmpty(), "not one block anywhere");
        List<OldAreas.Area> areas = gen.oldAreas();
        assertEquals(1, areas.size(), "it is listed");
        assertEquals(OldAreas.State.HELD, areas.get(0).state(), "held");
        assertTrue(areas.get(0).detail().startsWith("falling floors is only 8 blocks from its old half A"),
                "with what is in the way: " + areas.get(0).detail());
        assertTrue(guarded(OwnerServer.BOAT_A), "it stays guarded");
        assertEquals(1, host.logged(Level.WARNING, "fresh_boat's old area (half A x 6080..6207"),
                "one WARN, said once: " + host.logs.stream().map(l -> l.getMessage()).toList());
        assertTrue(gen.summary().stream().anyMatch(l -> l.startsWith(BOAT + ": its old area") && l.contains("can't be"
                + " emptied: falling floors")), "status says so: " + gen.summary());
        GamesCheck.Line line = GamesCheck.oldArea(new GamesCheck.OldArea(BOAT, "Ice Boat", GamesCheck.OldState.HELD,
                areas.get(0).where(), areas.get(0).detail(), 0, 0, 0));
        assertEquals(GamesCheck.Status.WARN, line.status(), "the check warns");
        assertTrue(line.fix().endsWith("/hcm games gen tidy fresh_boat confirm"), "with the one fix: " + line.fix());

        host.extras = List.of(); // the arena moved away
        drive((int) (GenService.VET_EVERY_MS / 1000) + 60);
        assertEquals(0, world.count(OwnerServer.BOAT_A), "looked at again a few minutes later, and emptied");
        saved();
        assertEquals(List.of(), gen.oldAreas(), "and forgotten once the world is saved");
    }

    // ---- an owner's own move -------------------------------------------------------------------------

    @Test
    void anOwnersOwnMoveIsGuardedButEmptiedOnlyWhenAnAdminSaysSo() throws Exception {
        host();
        Slots.Def def = Slots.FRESH_DROPPER;
        int[] here = def.origin();
        int[] there = {here[0], here[1], here[2] + 2048};
        String claim = Regions.claim(def, W, here, Slots.HALF_GAP);
        host.store.meta(GenAdminKeys.claim(DROPPER), claim);
        Box oldA = Regions.half(def, here, Slots.HALF_GAP, 'A');
        for (int x = oldA.minX() + 2; x < oldA.minX() + 10; x++) {
            world.put(x, oldA.minY() + 1, oldA.minZ() + 5, "minecraft:water[level=0]");
            world.put(x, oldA.minY(), oldA.minZ() + 5, Palette.SEA_LANTERN);
        }
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(DROPPER) ? c.withOrigin(there) : c);
        }
        host.settings = host.settings.withSlots(slots); // the owner moved it (same size: not this version's doing)
        boot(GenService.RECORDED);
        drive(10 * 60);
        assertEquals(16, world.count(oldA), "never emptied by itself");
        assertEquals(claim, host.store.meta(GenAdminKeys.old(DROPPER)), "recorded");
        assertEquals(OldAreas.State.MANUAL, gen.oldAreas().get(0).state(), "waiting for an admin");
        assertTrue(guarded(oldA), "and guarded: its pools may flow out");

        gen.tidy(DROPPER, false, said::add);
        assertTrue(said.stream().anyMatch(l -> l.contains("is still guarded - empty it with /hcm games gen tidy "
                + DROPPER + " confirm")), "tidy lists it: " + said);
        assertTrue(said.stream().anyMatch(l -> l.contains("Add &econfirm")), "and asks for confirm: " + said);
        assertEquals(16, world.count(oldA), "nothing done yet");

        said.clear();
        gen.tidy(DROPPER, true, said::add);
        assertTrue(said.stream().anyMatch(l -> l.contains("Emptying Dropper's old area")), "queued: " + said);
        driveUntilNoOldArea(10);
        assertEquals(0, world.count(oldA), "emptied, water and all");
        assertTrue(said.stream().anyMatch(l -> l.startsWith("&aDropper's old area is empty")),
                "the admin who asked is told: " + said);
        said.clear();
        gen.tidy(DROPPER, false, said::add);
        assertTrue(said.get(0).contains("has no old area to empty. The last one (half A x 7488..7551"),
                "and asked again, it says what was done: " + said);
    }

    // ---- resized in place ---------------------------------------------------------------------------------

    /** Golf's 0.36 claim at the owner's own spot, which revision 21 kept: it grows in place. */
    private void golfInPlace() throws SQLException {
        host(GOLF);
        int[] own = {7488, 160, 4096};
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(GOLF) ? c.withOrigin(own) : c);
        }
        host.settings = host.settings.withSlots(slots);
        host.store.meta(GenAdminKeys.claim(GOLF), OwnerServer.GOLF_036);
        pondAndWalls(world, OwnerServer.GOLF_A);
    }

    @Test
    void aSlotResizedInPlaceHasItsOldAreaEmptiedBeforeItsNewOneIsScannedAndBuilt() throws Exception {
        golfInPlace();
        long old = world.count(OwnerServer.GOLF_A);
        boot(GenService.RECORDED);
        for (int s = 0; s < 20 * 60 && !(gen.liveTag(GOLF) != null && gen.live(GOLF, gen.liveTag(GOLF))); s++) {
            drive(1);
        }
        GenTag golf = gen.liveTag(GOLF);
        assertNotNull(golf, "golf is up: " + gen.summary());
        assertEquals(Box.sized(7488, 160, 4096, 128, 16, 224), gen.slot(GOLF).half('A'), "at the owner's spot, 128 x"
                + " 16 x 224 now");
        int lastRemoval = -1;
        int firstPlacement = Integer.MAX_VALUE;
        for (int i = 0; i < world.log.size(); i++) {
            OwnerServer.LoggingWorld.Write w = world.log.get(i);
            if (w.was() != null && WorldPort.AIR.equals(w.now())) {
                lastRemoval = Math.max(lastRemoval, i);
            } else if (!WorldPort.AIR.equals(w.now())) {
                firstPlacement = Math.min(firstPlacement, i);
            }
        }
        assertTrue(old > 0 && lastRemoval >= 0 && lastRemoval < firstPlacement, "every old block went before the first"
                + " new one was placed (emptied first, then scanned, claimed and built)");
        assertEquals(Regions.claim(Slots.DAILY_GOLF, W, new int[]{7488, 160, 4096}, Slots.HALF_GAP),
                host.store.meta(GenAdminKeys.claim(GOLF)), "claimed at its new size");
        assertTrue(Regions.oldEmptied(host.store.meta(GenAdminKeys.old(GOLF))).containsKey(OwnerServer.GOLF_036),
                "the old claim is marked emptied, and kept until the world is saved");
        saved();
        assertNull(host.store.meta(GenAdminKeys.old(GOLF)), "then forgotten");
        assertEquals(0, host.logged(Level.SEVERE, "Region has"), "never mistaken for foreign blocks");
    }

    @Test
    void aBuildNextToAnOldAreaThatCantBeEmptiedWaitsAndSaysWhy() throws Exception {
        golfInPlace();
        Box arena = Box.sized(8128, 176, 4230, 16, 16, 16); // 6 from the old half B, 48 from the new half B
        host.extras = List.of(new Regions.Extra("falling_floors", arena));
        boot(GenService.RECORDED);
        drive(5 * 60);
        assertNull(gen.liveTag(GOLF), "nothing built");
        assertTrue(world.log.isEmpty(), "and nothing written");
        assertTrue(gen.summary().stream().anyMatch(l -> l.startsWith(GOLF + ": waiting - its old area (half A x"
                + " 7488..7551") && l.contains("is next to it and is emptied first, but it can't be now: falling"
                + " floors")), "status says why it waits: " + gen.summary());
        assertFalse(gen.report().stream().anyMatch(r -> r.id().equals(GOLF) && r.problem() != null),
                "a wait, not a problem that switches it off");

        host.extras = List.of();
        for (int s = 0; s < 20 * 60 && gen.liveTag(GOLF) == null; s++) {
            drive(1);
        }
        assertNotNull(gen.liveTag(GOLF), "once the arena is gone: emptied, then built");
        OldAreas.Retired r = OldAreas.Retired.parse(host.store.meta(GenAdminKeys.retired(GOLF)));
        assertTrue(r != null && r.removed() > 0 && r.claim().equals(OwnerServer.GOLF_036), "its old area went first: "
                + r);
    }

    // ---- a live row in an old area -------------------------------------------------------------------

    @Test
    void aLiveRowWhoseCourseStandsInAnOldAreaIsNeverHealedOrOpenedThere() throws Exception {
        host(GOLF);
        // a start that was stopped between claiming golf's new area and its flip: the claim names the new area,
        // the old claim is on record, and the row is still the 0.36 course in the old half A
        Plan plan = OwnerServer.PondGolf.plan(Slots.DAILY_GOLF, OwnerServer.GOLF_A, 7L, 3);
        for (BlockOp op : plan.ops()) {
            world.put(op.x(), op.y(), op.z(), plan.palette().get(op.state()));
        }
        for (SignText st : plan.signs()) {
            world.put(st.x(), st.y(), st.z(), st.blockData());
        }
        GenScheduler.Target t = GenScheduler.target(null, host.now, host.settings.edition(GenKit.ZONE,
                java.time.DayOfWeek.MONDAY), 0);
        GenTag old = GenService.tagFor(Slots.DAILY_GOLF, Slots.GOLF, plan, t.start(), 0, 7L, 'A',
                Slots.DAILY_GOLF.tierOrMix(), host.settings.stars(), host.now, t.cadence());
        host.store.flip(GenService.row(Slots.DAILY_GOLF, W, plan.course(), old, null, host.now), Map.of());
        host.store.meta(GenAdminKeys.claim(GOLF), Regions.claim(Slots.DAILY_GOLF, W, Slots.DAILY_GOLF.origin(),
                Slots.HALF_GAP));
        host.store.meta(GenAdminKeys.old(GOLF), OwnerServer.GOLF_036);
        boot(GenService.RECORDED);
        boolean opened = false;
        for (int s = 0; s < 20 * 60 && !(gen.liveTag(GOLF) != null && gen.liveTag(GOLF).algo() == 4
                && allEmptied()); s++) {
            drive(1);
            opened |= gen.liveTag(GOLF) != null && gen.liveTag(GOLF).sameLayout(old) && gen.live(GOLF, old);
        }
        assertFalse(opened, "the old course was never opened, though its blocks stood and its structure would pass");
        assertTrue(host.logged(Level.WARNING, GOLF + "'s live course isn't in its claimed region") > 0,
                "it is treated as outside its claim: " + host.logs.stream().map(l -> l.getMessage()).toList());
        assertEquals(4, gen.liveTag(GOLF).algo(), "a new course was built at the new area");
        assertEquals(0, world.count(OwnerServer.GOLF_A), "and the old area emptied");
    }

    // ---- the verb, plainly ----------------------------------------------------------------------------

    @Test
    void tidyWithNothingToEmptySaysSo() throws Exception {
        host();
        boot(GenService.RECORDED);
        gen.tidy(GOLF, true, said::add);
        assertEquals(List.of("&7Golf of the Week has no old area to empty."), said, "nothing to do, said plainly");
        said.clear();
        gen.tidy("fresh_classic_golf", false, said::add);
        assertEquals(List.of("&7Classic Golf has no old area to empty."), said, "a Classics slot is taken too");
    }

    @Test
    void anOldAreaWaitsForTheStartupDelayLikeABuild() throws Exception {
        host();
        host.store.meta(GenAdminKeys.claim(BOAT), OwnerServer.BOAT_036);
        world.put(6100, 165, 5900, Palette.TRACK);
        boot(GenService.RECORDED);
        drive(host.settings.startupDelaySeconds() - 5);
        assertEquals(1, world.count(OwnerServer.BOAT_A), "nothing is emptied while the server is still starting");
        drive(60);
        assertEquals(0, world.count(OwnerServer.BOAT_A), "then it is");
    }
}
