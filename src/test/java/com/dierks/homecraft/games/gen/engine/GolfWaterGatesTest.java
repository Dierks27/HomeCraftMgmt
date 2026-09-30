package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf's ponds go through every water gate a Dropper's pools do (Course Variety §1.2, one predicate:
 * {@code Slots.Def.mayHoldWater}), on the real engine over a real in-memory database: a golf slot
 * whose origin moves keeps its old region guarded and its admin is told to drain its ponds first; a
 * kept golf course's plot keeps its water in; and a golf course recalled into Classic Golf or kept
 * into a plot is proven again where it will stand, on the planner thread, before a block is set.
 * The ice boat stays dry: none of this is asked of it.
 */
class GolfWaterGatesTest {

    private static final String W = GenKit.WORLD;
    private static final String TINY = "fresh_tiny_golf";
    private static final String CLASSIC = "fresh_classic_golf";

    private Host host;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        if (host != null) {
            host.connection.close();
        }
    }

    private static Map<String, Planner> fakes() {
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
        return planners;
    }

    private void boot(Map<String, Planner> planners) {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        gen.start();
    }

    private void moveTo(String id, int[] origin) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(id) ? c.withOrigin(origin) : c);
        }
        host.settings = host.settings.withSlots(slots);
    }

    private boolean guarded(Box b) {
        return gen.inArea(W, b.minX(), b.minY(), b.minZ()) && gen.inArea(W, b.maxX(), b.maxY(), b.maxZ());
    }

    @Test
    void aMovedGolfSlotsOldRegionStaysGuardedAndItsAdminIsToldToDrainItsPonds() throws Exception {
        Slots.Def golf = Slots.DAILY_GOLF;
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, golf.id(), Slots.ICE_BOAT.id());
        int[] here = golf.origin();
        host.store.meta(GenAdminKeys.claim(golf.id()), Regions.claim(golf, W, here, Slots.HALF_GAP));
        boot(fakes());
        Box oldA = Regions.half(golf, here, Slots.HALF_GAP, 'A');
        assertTrue(guarded(oldA), "claimed: guarded, as before");

        moveTo(golf.id(), new int[]{here[0], here[1], here[2] + 2048});
        gen.check();
        assertTrue(guarded(oldA) && guarded(Regions.half(golf, here, Slots.HALF_GAP, 'B')),
                "moved away: the old halves (maybe holding ponds) stay guarded");
        assertEquals(Regions.claim(golf, W, here, Slots.HALF_GAP), host.store.meta(GenAdminKeys.wet(golf.id())),
                "remembered as a wet region, as a Dropper's is");
        assertTrue(host.logged(Level.WARNING, "Its ponds may still be there") > 0
                && host.logged(Level.WARNING, "drain first - move it back and use /hcm games gen clear " + golf.id()
                + " (it empties the ponds before anything else)") > 0, "the admin is told to drain its ponds first");
        assertTrue(gen.summary().stream().anyMatch(l -> l.startsWith(golf.id() + ": its old area")
                && l.contains("drain first")), "and the status says so: " + gen.summary());

        Slots.Def boat = Slots.ICE_BOAT;
        int[] boatHere = boat.origin();
        host.store.meta(GenAdminKeys.claim(boat.id()), Regions.claim(boat, W, boatHere, Slots.HALF_GAP));
        boot(fakes());
        moveTo(boat.id(), new int[]{boatHere[0], boatHere[1], boatHere[2] + 2048});
        gen.check();
        Box boatOld = Regions.half(boat, boatHere, Slots.HALF_GAP, 'A');
        assertFalse(gen.inArea(W, boatOld.minX(), boatOld.minY(), boatOld.minZ()),
                "the ice boat stays dry: its old region isn't kept guarded");
        assertNull(host.store.meta(GenAdminKeys.wet(boat.id())), "nor remembered");
    }

    @Test
    void aKeptGolfCoursesPlotKeepsItsWaterIn() throws Exception {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, TINY);
        KeepArea area = new KeepArea(8192, 128, 8192, 4);
        Box golfPlot = area.plot(1);
        Box classicPlot = area.plot(2);
        Box boatPlot = area.plot(3);
        host.store.meta(GenAdminKeys.plot(1), new KeptPlot(1, "my_links", W, golfPlot, TINY, "d20725").text());
        host.store.meta(GenAdminKeys.plot(2), new KeptPlot(2, "old_links", W, classicPlot, CLASSIC, "d20725").text());
        host.store.meta(GenAdminKeys.plot(3), new KeptPlot(3, "my_boat", W, boatPlot, "fresh_boat", "d20725").text());
        boot(fakes());
        GenRegionGuard.Area wet = gen.wetArea();
        assertTrue(wet.in(W, golfPlot.minX() + 3, golfPlot.minY() + 3, golfPlot.minZ() + 3),
                "a kept golf course's plot keeps its ponds in");
        assertTrue(wet.in(W, classicPlot.minX() + 3, classicPlot.minY() + 3, classicPlot.minZ() + 3),
                "and so does one kept from Classic Golf");
        assertFalse(gen.inArea(W, golfPlot.minX() + 3, golfPlot.minY() + 3, golfPlot.minZ() + 3),
                "but nothing else there is refused: the keep area is hand-built ground");
        assertFalse(wet.in(W, boatPlot.minX() + 3, boatPlot.minY() + 3, boatPlot.minZ() + 3),
                "a kept ice boat has no water to keep in");
    }

    // ---- a recall and a keep, proven where they will stand (the real golf planner) ----------------------

    private static long on(int n) {
        LocalDate d = LocalDate.of(2026, 9, 29).plusDays(n - 1);
        return GenKit.at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 4, 0) + 40_000;
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

    private String heard() {
        return String.join("\n", said);
    }

    /** Two days of Tiny Golf with the real planner: TINY-1 is in the archive and no longer up. */
    private void twoDays() {
        host = new Host(on(1), TINY);
        Map<String, Planner> planners = fakes();
        planners.put(Slots.GOLF, new GolfPlanner());
        host.now = on(1);
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(70);
        drive(15);
        GenTag first = gen.liveTag(TINY);
        assertNotNull(first, "day 1's Tiny Golf is up");
        host.now = on(2);
        drive(35);
        assertNotNull(gen.liveTag(TINY), "and day 2's");
        assertFalse(first.editionKey().equals(gen.liveTag(TINY).editionKey()), "a new one: TINY-1 is archived");
    }

    @Test
    void aRecalledGolfCourseIsProvenOnThePlannerThreadBeforeABlockIsSet() {
        twoDays();
        host.holdPlans = true;
        gen.recall(null, null, GenArgs.which("TINY-1"), GenArgs.DAYS_DEFAULT, true, said::add);
        drive(20);
        assertFalse(host.plannerQueue.isEmpty(), "the moved golf course's check waits on the planner thread: " + heard());
        Box a = Slots.CLASSIC_GOLF.half('A');
        Box b = Slots.CLASSIC_GOLF.half('B');
        assertEquals(0, host.world().count(a) + host.world().count(b), "and not a block is set meanwhile");
        assertNull(gen.liveTag(CLASSIC), "nothing is up");

        host.holdPlans = false;
        host.runPlans();
        drive(60);
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "once it answers, the recall is built: " + heard());
        assertTrue(host.world().count(Slots.CLASSIC_GOLF.half(c.half())) > 0, "block for block");
    }

    @Test
    void aKeptGolfCourseIsProvenOnThePlannerThreadBeforeABlockIsSet() throws Exception {
        twoDays();
        host.holdPlans = true;
        gen.keep(TINY, GenArgs.which("TINY-1"), "my_links", null, false, true, said::add);
        Box plot = host.settings.archive().keep().plot(1);
        for (int s = 0; s < 120 && host.plannerQueue.isEmpty(); s++) {
            drive(1);
        }
        assertFalse(host.plannerQueue.isEmpty(), "the kept golf course's check waits on the planner thread: " + heard());
        assertEquals(0, host.world().count(plot), "and not a block is set in its plot meanwhile");
        assertNull(host.dao.course("my_links"), "nothing is registered");

        host.holdPlans = false;
        host.runPlans();
        for (int s = 0; s < 120 && host.dao.course("my_links") == null; s++) {
            drive(1);
        }
        assertNotNull(host.dao.course("my_links"), "once it answers, the keep is built and registered: " + heard());
        assertTrue(host.world().count(plot) > 0, "in its plot");
    }
}
