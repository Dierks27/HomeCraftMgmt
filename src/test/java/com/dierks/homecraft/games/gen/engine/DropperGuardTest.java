package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Dropper's pools never outlive the flow guard (the WP-D review's #3), on the real engine over a
 * real in-memory database.
 *
 * <p>Pinned here: a Dropper slot whose origin moves keeps its old region guarded (every change
 * refused, nothing flowing out), remembered in {@code gen.<slot>.wet} before a claim at the new place
 * can overwrite the old one, across a restart, until the slot is claimed there again (so a clear
 * drains it); its admin is told to drain first, and the status says so; any other slot that moves is
 * left as before. A kept Dropper's plot, and a plot job the server stopped halfway, keep their water
 * in (flow only: the keep area is otherwise hand-built ground), and a kept parkour course's plot is
 * none of the guard's business.
 */
class DropperGuardTest {

    private static final String SLOT = "fresh_dropper";
    private static final Slots.Def DEF = Slots.FRESH_DROPPER;
    private static final String W = GenKit.WORLD;

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT, "fresh_parkour_easy");
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        gen.start();
    }

    /** {@code id}'s origin in config is now {@code origin}. */
    private void moveTo(String id, int[] origin) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(id) ? c.withOrigin(origin) : c);
        }
        host.settings = host.settings.withSlots(slots);
    }

    /** Fresh Courses claimed {@code def}'s region at {@code origin} (what a scan or a claim leaves). */
    private void claimAt(Slots.Def def, int[] origin) throws Exception {
        host.store.meta(GenAdminKeys.claim(def.id()), Regions.claim(def, W, origin));
    }

    private boolean guarded(Box b) {
        return gen.inArea(W, b.minX(), b.minY(), b.minZ()) && gen.inArea(W, b.maxX(), b.maxY(), b.maxZ());
    }

    private static int[] away(int[] o, int dz) {
        return new int[]{o[0], o[1], o[2] + dz};
    }

    @Test
    void aMovedDroppersOldRegionStaysGuardedUntilItIsClaimedThereAgain() throws Exception {
        int[] here = DEF.origin();
        int[] there = away(here, 2048);
        Box oldA = Regions.half(DEF, here, 'A');
        Box oldB = Regions.half(DEF, here, 'B');
        claimAt(DEF, here);
        boot();
        assertTrue(guarded(oldA) && guarded(oldB), "claimed: both halves guarded, as before");
        assertNull(host.store.meta(GenAdminKeys.wet(SLOT)), "nothing old to remember yet");

        moveTo(SLOT, there);
        gen.check();
        assertTrue(guarded(oldA) && guarded(oldB), "moved away: the old halves (maybe full of water) stay guarded");
        assertEquals(Regions.claim(DEF, W, here), host.store.meta(GenAdminKeys.wet(SLOT)),
                "remembered before a claim at the new place can overwrite the old one");
        assertTrue(host.logged(Level.WARNING, "drain first - move it back and use /hcm games gen clear " + SLOT) > 0,
                "and the admin is told to drain first");
        assertTrue(gen.summary().stream().anyMatch(l -> l.startsWith(SLOT + ": its old area") && l.contains("drain first")),
                "the status says so too: " + gen.summary());

        claimAt(DEF, there); // the new place is claimed: the claim key names it now
        gen.check();
        assertTrue(guarded(oldA) && guarded(oldB), "the old halves are still guarded");
        assertTrue(guarded(Regions.half(DEF, there, 'A')), "and so is the new region");
        boot();
        assertTrue(guarded(oldA) && guarded(oldB), "across a restart too");

        moveTo(SLOT, here); // moved back, while the claim names the other place
        gen.check();
        assertTrue(guarded(oldA), "back at the old place, still guarded");
        assertTrue(guarded(Regions.half(DEF, there, 'B')), "and now the place it left is remembered as well");
        assertEquals(List.of(Regions.claim(DEF, W, here), Regions.claim(DEF, W, there)),
                Regions.wetClaims(host.store.meta(GenAdminKeys.wet(SLOT))), "both old regions");

        claimAt(DEF, here); // a claim confirm cleared it (drains first) and claimed it
        gen.check();
        assertEquals(List.of(Regions.claim(DEF, W, there)), Regions.wetClaims(host.store.meta(GenAdminKeys.wet(SLOT))),
                "claimed here again: the claim guards it now, and a clear drains it");
        assertTrue(guarded(oldA), "(guarded as the claimed region)");
    }

    @Test
    void anyOtherSlotThatMovesIsLeftAsBefore() throws Exception {
        Slots.Def parkour = Slots.DAILY_PARKOUR_EASY;
        int[] here = parkour.origin();
        claimAt(parkour, here);
        boot();
        moveTo(parkour.id(), away(here, 2048));
        gen.check();
        Box old = Regions.half(parkour, here, 'A');
        assertFalse(gen.inArea(W, old.minX(), old.minY(), old.minZ()), "a parkour's old region isn't guarded (no water)");
        assertNull(host.store.meta(GenAdminKeys.wet(parkour.id())), "nor remembered");
        assertTrue(host.logged(Level.WARNING, "clear them by hand") > 0, "its copy is the old one");
    }

    @Test
    void aKeptDroppersPlotKeepsItsWaterInAndNothingElseOfItIsGuarded() throws Exception {
        KeepArea area = new KeepArea(8192, 128, 8192, 4);
        Box dropperPlot = area.plot(1);
        Box parkourPlot = area.plot(2);
        host.store.meta(GenAdminKeys.plot(1), new KeptPlot(1, "my_drop", W, dropperPlot, SLOT, "d20725").text());
        host.store.meta(GenAdminKeys.plot(2), new KeptPlot(2, "my_steps", W, parkourPlot, "fresh_parkour_easy",
                "d20725").text());
        boot();
        GenRegionGuard.Area wet = gen.wetArea();
        assertTrue(wet.covers(W) && !wet.covers("world"), "the Games world only");
        assertTrue(wet.in(W, dropperPlot.minX() + 3, dropperPlot.minY() + 3, dropperPlot.minZ() + 3),
                "a kept Dropper's plot keeps its water in");
        assertFalse(gen.inArea(W, dropperPlot.minX() + 3, dropperPlot.minY() + 3, dropperPlot.minZ() + 3),
                "but nothing else there is refused: the keep area is hand-built ground");
        assertFalse(wet.in(W, parkourPlot.minX() + 3, parkourPlot.minY() + 3, parkourPlot.minZ() + 3),
                "a kept parkour course has no water to keep in");
        assertTrue(gen.guardArea().covers(W) && !gen.guardArea().covers("world"), "the full guard: the Games world only");

        Box half = Box.sized(8192, 128, 9000, 64, 64, 16);
        host.store.meta(GenAdminKeys.KEEP_PENDING, "keep|3|" + W + "|" + KeptPlot.boxText(half) + "|" + SLOT
                + "|d20726|drop_two|0|0|Two");
        gen.check();
        assertTrue(wet.in(W, half.minX(), half.minY(), half.minZ()), "a Dropper keep the server stopped halfway too");
        gen.stop();
        assertFalse(wet.covers(W), "stopped: the engine guards nothing (Time Trials' own pool guard still does)");
    }

    @Test
    void aPendingPlotJobIsWetWhenItMayHoldADroppersWater() {
        Box b = Box.sized(100, 64, 100, 64, 64, 16);
        String box = KeptPlot.boxText(b);
        Object[] keep = KeepService.wetPending("keep|1|games|" + box + "|" + SLOT + "|d1|x|0|0|");
        assertEquals("games", keep[0], "a Dropper keep: its world");
        assertEquals(b, keep[1], "and its plot");
        assertEquals(b, KeepService.wetPending("keep|1|games|" + box + "|fresh_classic_dropper|d1|x|0|0|")[1],
                "a Classic Dropper's too");
        assertEquals(b, KeepService.wetPending("clear|1|games|" + box)[1], "any clearing (its course isn't recorded)");
        assertNull(KeepService.wetPending("keep|1|games|" + box + "|fresh_parkour_easy|d1|x|0|0|"),
                "a parkour keep holds no water");
        assertNull(KeepService.wetPending(null), "nothing pending");
        assertNull(KeepService.wetPending("keep|1|games|nonsense|" + SLOT), "an unreadable record");
        assertTrue(KeepService.dropper(SLOT) && KeepService.dropper("fresh_dropper_easy"), "the two Dropper slots");
        assertFalse(KeepService.dropper("fresh_golf") || KeepService.dropper(null), "and no other");
    }
}
