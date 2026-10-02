package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F12 (the red team): what a restart costs on a server whose Ice Boat is the Mountain Run v2 (1,200 chunks a
 * half), on the bench at the shipped pace: two chunk loads in flight, each finishing a tick later (as Paper's
 * do), and four snapshots a tick. Easy Dropper and Dropper are on too.
 *
 * <p>Measured with {@link #restart}, in ticks after the worlds are ready (20 a second):
 * <ul>
 *   <li><b>Before</b> (bb977ec): Easy Dropper opened at tick 923, Dropper at 943 (both queued behind the
 *       boat's check), the boat at 920 after loading all 1,200 chunks of its live half; then its empty spare
 *       half was loaded too: 2,416 chunk loads in the first ten minutes.</li>
 *   <li><b>After</b>, the world saved since the last change: Easy Dropper at tick 23, Dropper at 43, the boat at
 *       122 after a sample of 89 chunks; its spare half never loaded; the whole live half checked later in idle
 *       time with the course open (1,289 loads in ten minutes).</li>
 *   <li><b>After</b>, no save since the last change (a crash): the droppers still open first; the boat is
 *       checked whole, closed, and its spare half emptied, as before.</li>
 * </ul>
 */
class BootCostTest {

    static final String EASY = Slots.EASY_DROPPER.id();
    static final String DROPPER = Slots.FRESH_DROPPER.id();
    static final String BOAT = Slots.ICE_BOAT.id();

    /** A world whose chunk loads finish a tick later, counting every load asked for. */
    static final class CountingWorld extends GenKit.FakeWorld {
        final Set<Long> loaded = new HashSet<>();
        long loads;

        CountingWorld(String name) {
            super(name);
            asyncLoads = true;
        }

        @Override
        public void load(int cx, int cz, Consumer<Boolean> done) {
            loads++;
            loaded.add(chunk(cx, cz));
            super.load(cx, cz, done);
        }

        boolean anyLoaded(Box b) {
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    if (loaded.contains(chunk(cx, cz))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * One restart, in ticks after the worlds are ready.
     *
     * @param easyOpen    when Easy Dropper opened
     * @param dropperOpen when Dropper opened
     * @param boatOpen    when the Ice Boat opened
     * @param boatLoads   chunk loads asked for until the Ice Boat opened
     * @param spareLoaded whether any chunk of the boat's spare half was loaded in the first ten minutes
     * @param loads       chunk loads in the first ten minutes
     * @param boatClosed  whether the boat closed again after it opened
     */
    record Boot(int easyOpen, int dropperOpen, int boatOpen, long boatLoads, boolean spareLoaded, long loads,
                boolean boatClosed) {
    }

    private Host host;
    private CountingWorld world;
    private GenService gen;
    private int ticks;
    /** The boat planner's version at the next {@link #boot} (ENG00: a release that bumps it, or a rollback). */
    private int boatAlgo = 1;

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        if (host != null) {
            host.connection.close();
        }
    }

    private void tick() {
        gen.tick();
        world.finishLoads();
        host.now += 50;
        if (++ticks % 20 == 0) {
            gen.check();
        }
    }

    private boolean open(String id) {
        GenTag t = gen.liveTag(id);
        return t != null && gen.live(id, t);
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        Map<String, Planner> planners = OwnerServer.planners(4);
        ((GenKit.FakePlanner) planners.get(Slots.BOAT)).algo = boatAlgo;
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        world.loads = 0;
        world.loaded.clear();
        ticks = 0;
    }

    /** The three courses built, their spare halves emptied: a server that has run for a while. */
    private void running() {
        host = new Host(GenKit.at(2026, 10, 5, 19, 0), EASY, DROPPER, BOAT);
        world = new CountingWorld(GenKit.WORLD);
        host.worlds.put(GenKit.WORLD, world);
        host.settings = GenKit.weekly(EASY, DROPPER, BOAT);
        boot();
        for (int t = 0; t < 20 * 60 * 30 && !(open(EASY) && open(DROPPER) && open(BOAT)); t++) {
            tick();
        }
        assertTrue(open(EASY) && open(DROPPER) && open(BOAT), "the three are up: " + gen.summary());
        for (int t = 0; t < 20 * 60 * 10; t++) {
            tick(); // the spare halves emptied, nothing left to do
        }
    }

    /** Two autosaves of the Games world, as Paper fires them. */
    private void saved() {
        gen.worldSaved(GenKit.WORLD);
        gen.worldSaved(GenKit.WORLD);
    }

    private Box live(String id) {
        return gen.half(gen.liveTag(id));
    }

    private Box spare(String id) {
        GenTag live = gen.liveTag(id);
        return gen.slot(id).half(live.half() == 'A' ? 'B' : 'A');
    }

    private Boot restart() {
        boot();
        int easy = -1;
        int dropper = -1;
        int boat = -1;
        long boatLoads = -1;
        boolean closed = false;
        for (int t = 0; t < 20 * 60 * 10; t++) {
            tick();
            if (easy < 0 && open(EASY)) {
                easy = ticks;
            }
            if (dropper < 0 && open(DROPPER)) {
                dropper = ticks;
            }
            if (boat < 0 && open(BOAT)) {
                boat = ticks;
                boatLoads = world.loads;
            }
            closed |= boat >= 0 && !open(BOAT);
        }
        return new Boot(easy, dropper, boat, boatLoads, world.anyLoaded(spare(BOAT)), world.loads, closed);
    }

    @Test
    void aRestartAfterTheWorldWasSavedOpensTheDroppersAtOnceAndTheMountainOnASample() {
        running();
        saved();
        Map<Long, String> before = world.copy(live(BOAT));
        Boot b = restart();
        assertTrue(b.easyOpen() > 0 && b.easyOpen() <= 60, "Easy Dropper opens within 3 seconds (it was 46): " + b);
        assertTrue(b.dropperOpen() > 0 && b.dropperOpen() <= 60, "Dropper too (it was 47): " + b);
        assertTrue(b.boatOpen() > 0 && b.boatOpen() <= 200, "the boat within 10 seconds (it was 46): " + b);
        assertTrue(b.boatLoads() <= 120, "after reading a sample of its half, not 1,200 chunks: " + b);
        assertFalse(b.spareLoaded(), "its spare half, known to be empty, is never loaded: " + b);
        assertFalse(b.boatClosed(), "and it stays open while its whole half is checked: " + b);
        assertFalse(gen.slot(BOAT).fullCheckDue, "the whole half was checked in the ten minutes");
        assertEquals(1, host.logged(Level.INFO, "checked " + BOAT + " in half"), "one sampled check at the start");
        assertEquals(1, host.logged(Level.INFO, "checked the whole of " + BOAT), "and the whole of it once, later");
        assertEquals(before, world.copy(live(BOAT)), "nothing written: it was right");
    }

    @Test
    void aRestartWithNoSaveSinceTheLastChangeChecksTheWholeMountainClosedAsBefore() {
        running(); // a crash: the world was never saved after the last build
        Boot b = restart();
        assertTrue(b.easyOpen() > 0 && b.easyOpen() <= 60, "the droppers still open first: " + b);
        assertTrue(b.dropperOpen() > 0 && b.dropperOpen() <= 60, "both: " + b);
        assertTrue(b.boatLoads() >= live(BOAT).chunkCount(), "the boat's whole half is read before it opens: " + b);
        assertTrue(b.spareLoaded(), "and its spare half is emptied again, as it isn't known to be: " + b);
        assertEquals(0, host.logged(Level.INFO, "by a sample"), "no sample: nothing is known to be on disk");
    }

    @Test
    void aSampleThatFindsADifferenceChecksTheWholeHalfBeforeItOpens() {
        running();
        saved();
        Box half = live(BOAT);
        // the plan's first pad is in the half's corner chunk, which every sample reads: it is gone
        String pad = world.at(half.minX() + 3, half.minY() + 10, half.minZ() + 3);
        assertTrue(pad != null, "the fixture: the first pad's corner block stands");
        world.blocks.remove(GenKit.pos(half.minX() + 3, half.minY() + 10, half.minZ() + 3));
        Boot b = restart();
        assertTrue(b.boatLoads() >= half.chunkCount(), "the whole half was checked before it opened: " + b);
        assertEquals(pad, world.at(half.minX() + 3, half.minY() + 10, half.minZ() + 3), "and the block healed");
        assertEquals(1, host.logged(Level.WARNING, BOAT + "'s check by a sample found 1 block that differ"),
                "the WARN says why it waited");
    }

    @Test
    void aDifferenceOutsideTheSampleIsHealedLaterByTheWholeHalfCheckWithTheCourseOpen() {
        running();
        saved();
        Box half = live(BOAT);
        int x = half.minX() + 2 * 16 + 5; // chunk (2, 2) from the corner: off the sample's lattice, and no sign
        int z = half.minZ() + 2 * 16 + 5;
        world.put(x, half.minY() + 20, z, "minecraft:stone");
        Boot b = restart();
        assertTrue(b.boatOpen() > 0 && b.boatOpen() <= 200, "it opened on its sample: " + b);
        assertNull(world.at(x, half.minY() + 20, z), "the whole half's check found the stray block and took it away");
        assertFalse(b.boatClosed(), "with the course open the whole time: " + b);
        assertEquals(1, host.logged(Level.SEVERE, "checked the whole of " + BOAT + " in half"),
                "and said so, as a heal is said: " + host.logs.stream().map(l -> l.getMessage()).toList());
    }

    // ---- ENG02: the whole-half check of an open course is said as one ---------------------------------

    @Test
    void theWholeHalfCheckOfACourseOpenOnItsSampleSaysItStayedOpenAndTheCheckSaysLiveNotBeingBuilt() {
        running();
        saved();
        Box half = live(BOAT);
        char h = gen.liveTag(BOAT).half();
        int x = half.minX() + 2 * 16 + 5; // off the sample's lattice: only the whole half's check finds it
        int z = half.minZ() + 2 * 16 + 5;
        world.put(x, half.minY() + 20, z, "minecraft:stone");
        boot();
        int seen = 0;
        for (int t = 0; t < 20 * 60 * 10; t++) {
            tick();
            if (t % 5 == 0 && gen.status(BOAT).stream().anyMatch(l -> l.contains("(the whole half, open)"))) {
                seen++;
                assertTrue(open(BOAT), "the course is open while its whole half is checked");
                GenService.SlotReport r = gen.report().stream().filter(s -> s.id().equals(BOAT)).findFirst()
                        .orElseThrow();
                assertFalse(r.building(), "so /hcm games check says live, not 'being built'");
                assertTrue(r.current(), "(live)");
            }
        }
        assertTrue(seen > 0, "the whole-half check was seen running");
        assertNull(world.at(x, half.minY() + 20, z), "it took the stray block away");
        assertEquals(1, host.logged(Level.SEVERE, "Fresh Courses: checked the whole of " + BOAT + " in half " + h
                + " - 1 blocks healed (something edited it after its last full check, where the sample at the start"
                + " didn't look); it stayed open."), "said as what it was: " + host.logs.stream()
                .filter(l -> l.getLevel() == Level.SEVERE).map(l -> l.getMessage()).toList());
        assertEquals(0, host.logged(Level.SEVERE, "it is open again"), "it was never closed");
    }

    // ---- ENG07: a clean whole-half check keeps the fact the sample stood on ---------------------------------

    @Test
    void aCleanWholeHalfCheckKeepsTheFactSoAQuickSecondRestartOpensOnASampleAgain() throws Exception {
        running();
        saved();
        char h = gen.liveTag(BOAT).half();
        String fact = h + "=" + GenService.planFact(gen.liveTag(BOAT).planHash());
        assertTrue(String.valueOf(host.store.meta(GenAdminKeys.onDisk(BOAT))).contains(fact), "fixture: known on disk");
        boot();
        for (int t = 0; t < 20 * 60 * 10; t++) {
            tick();
            if (t % 20 == 0) {
                assertTrue(String.valueOf(host.store.meta(GenAdminKeys.onDisk(BOAT))).contains(fact),
                        "the fact stays while the whole half is read (nothing is written): tick " + t);
            }
        }
        assertFalse(gen.slot(BOAT).fullCheckDue, "the whole half was checked in the ten minutes");
        assertEquals(1, host.logged(Level.INFO, "checked the whole of " + BOAT + " in half " + h + " - 0 blocks healed"),
                "and found right");
        long sampled = host.logged(Level.INFO, "by a sample");
        Boot b = restart(); // no WorldSaveEvent since: the fact is still true on disk
        assertTrue(b.boatOpen() > 0 && b.boatOpen() <= 200, "the boat opens on a sample again: " + b);
        assertTrue(b.boatLoads() <= 120, "after reading a sample, not 1,200 chunks: " + b);
        assertEquals(sampled + 1, host.logged(Level.INFO, "by a sample"), "one more sampled check");
    }

    // ---- ENG00: a saved big half from another planner version --------------------------------------------

    @Test
    void aSavedMountainFromAnOlderPlannerVersionKeepsItsBlocksAndGetsTheStructureCheck() throws Exception {
        running(); // the boat planner at version 1
        saved();
        anotherVersionAtTheRestart(2, "an older"); // the next release bumps it
    }

    @Test
    void aSavedMountainFromANewerPlannerVersionKeepsItsBlocksAfterARollback() throws Exception {
        boatAlgo = 2;
        running();
        saved();
        anotherVersionAtTheRestart(1, "a different"); // rolled back to the release before
    }

    /**
     * A restart with the boat's planner at {@code algo}, not the live course's: there is no plan to compare a
     * sample with, so the course gets the structure check every start gave it, and not a block changes (it was
     * emptied by a plan-less converge, and a new course built mid-week).
     */
    private void anotherVersionAtTheRestart(int algo, String version) throws Exception {
        GenTag before = gen.liveTag(BOAT);
        Map<Long, String> blocks = world.copy(live(BOAT));
        assertFalse(blocks.isEmpty(), "fixture: the mountain stands");
        assertTrue(String.valueOf(host.store.meta(GenAdminKeys.onDisk(BOAT))).contains(before.half() + "="
                + GenService.planFact(before.planHash())), "fixture: it is known on disk, so a sample was due");
        boatAlgo = algo;
        Boot b = restart();
        GenTag after = gen.liveTag(BOAT);
        assertEquals(before.editionKey(), after.editionKey(), "the same course stays live");
        assertEquals(before.reroll(), after.reroll(), "on the same board (no reroll)");
        assertEquals(before.planHash(), after.planHash(), "the same layout");
        assertTrue(open(BOAT), "and open: " + gen.summary());
        assertEquals(blocks, world.copy(live(BOAT)), "not a block of it changed");
        assertEquals(1, host.logged(Level.WARNING, BOAT + "'s course was made by " + version + " version of its"
                + " planner, so only its structure was checked; " + (algo > 1 ? "the new" : "this") + " version builds"
                + " from the next set."), "the structure check, as every start gave it, said for what it is");
        assertEquals(0, host.logged(Level.WARNING, "check by a sample found"), "never a sample: " + b);
        assertEquals(0, host.logged(Level.INFO, "by a sample"), "none");
        assertEquals(0, host.logged(Level.SEVERE, BOAT), "nothing failed: " + host.logs.stream()
                .filter(l -> l.getLevel() == Level.SEVERE).map(l -> l.getMessage()).toList());
    }

    @Test
    void aSpareHalfThatHadAPreviewBuiltInItIsEmptiedAgainAtTheNextStart() {
        running();
        saved();
        gen.preview(BOAT, null, line -> { });
        for (int t = 0; t < 20 * 60 * 5 && gen.slot(BOAT).preview == null; t++) {
            tick();
        }
        assertTrue(gen.slot(BOAT).preview != null, "the preview stands in the spare half");
        Boot b = restart(); // no save since
        assertTrue(b.spareLoaded(), "its fact went before the first block was written: the spare half is emptied: " + b);
        assertTrue(world.count(spare(BOAT)) == 0, "and it is empty again");
    }

    @Test
    void factsAreReadOnlyForTheClaimTheyWereRecordedFor() {
        String claim = "games,6080,96,2880,480,176,640,576";
        assertEquals(Map.of('A', "plan:abc", 'B', "empty"),
                GenService.diskFacts("claim=" + claim + ";A=plan:abc;B=empty", claim), "this claim's facts");
        assertEquals(Map.of(), GenService.diskFacts("claim=" + claim + ";A=plan:abc", "games,6080,160,5888,128,16,128,576"),
                "another claim's say nothing about this region");
        assertEquals(Map.of(), GenService.diskFacts(null, claim), "none stored, none known");
    }
}
