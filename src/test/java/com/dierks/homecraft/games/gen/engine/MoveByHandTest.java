package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving a course by hand, as README "Moving an area by hand" says (there is no automatic move):
 * {@code clear} first, then change its origin, then switch it on. Its old halves end empty, nothing
 * warns that they weren't cleared, and the next course is built at the new spot. Moving without the
 * clear is still warned about, as in 0.35.
 */
class MoveByHandTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final int[] THERE = {16_384, 160, 16_384};

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    private void moveTo(int[] origin) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(SLOT) ? c.withOrigin(origin) : c);
        }
        host.settings = host.settings.withSlots(slots);
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

    private GenService.SlotReport report() {
        return gen.report().stream().filter(r -> r.id().equals(SLOT)).findFirst().orElseThrow();
    }

    private GenTag built() {
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(70);
        GenTag live = gen.liveTag(SLOT);
        assertNotNull(live, "the first set is up at the shipped spot");
        return live;
    }

    @Test
    void aCourseClearedFirstMovesWithoutAWarningAndIsBuiltAtItsNewSpot() throws Exception {
        GenTag first = built();
        Box oldA = Regions.half(DEF, DEF.origin(), Slots.HALF_GAP, 'A');
        Box oldB = Regions.half(DEF, DEF.origin(), Slots.HALF_GAP, 'B');
        assertTrue(host.world().count(gen.half(first)) > 0, "its blocks stand");

        gen.clear(SLOT, said::add);
        drive(30);
        assertFalse(report().claimed(), "cleared: its claim is given up");
        assertNull(host.store.meta(GenAdminKeys.claim(SLOT)), "and forgotten");
        assertEquals(0, host.world().count(oldA) + host.world().count(oldB), "both old halves are empty");

        host.logs.clear();
        moveTo(THERE);
        gen.check();
        assertEquals(0, host.logged(Level.WARNING, "not cleared"), "no false warning: they were cleared "
                + host.logs.stream().map(r -> r.getMessage()).toList());
        assertTrue(host.logged(Level.INFO, SLOT + " moved from") > 0, "the move is still said, as an INFO");

        gen.enable(SLOT, true, said::add);
        drive(120);
        GenTag next = gen.liveTag(SLOT);
        assertNotNull(next, "switched on again: a course is up");
        Box at = gen.half(next);
        assertTrue(Regions.halves(DEF, THERE, Slots.HALF_GAP).contains(at), "at the new spot: " + at.describe());
        assertTrue(host.world().count(at) > 0, "built there");
        assertEquals(Regions.claim(DEF, GenKit.WORLD, THERE, Slots.HALF_GAP), host.store.meta(GenAdminKeys.claim(SLOT)),
                "claimed there");
        assertEquals(0, host.world().count(oldA) + host.world().count(oldB), "and nothing came back at the old spot");
    }

    @Test
    void aCourseMovedWithoutAClearIsStillWarnedAbout() {
        built();
        moveTo(THERE);
        gen.check();
        assertTrue(host.logged(Level.WARNING, "The old halves were not cleared") > 0,
                "0.35's warning, when blocks may still stand at the old spot");
    }
}
