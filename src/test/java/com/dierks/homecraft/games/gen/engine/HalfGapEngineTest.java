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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A slot's own gap between its halves on the real engine (LAYOUT-SPEC §3.2): the next course is built
 * into half B at the configured gap, both halves (and nothing between them) are guarded, the claim
 * names the gap, and a changed gap is a moved region, handled exactly as a changed origin is, so the
 * old spare half is never taken for the new one unchecked.
 */
class HalfGapEngineTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final String W = GenKit.WORLD;
    private static final int[] FAR = {16_384, 160, 16_384};
    private static final int GAP = 576;

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
        place(FAR, GAP);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        host.connection.close();
    }

    private void place(int[] origin, int gap) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.id().equals(SLOT) ? c.withOrigin(origin).withHalfGap(gap) : c);
        }
        host.settings = host.settings.withSlots(slots);
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
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

    private boolean guarded(Box b) {
        return gen.inArea(W, b.minX(), b.minY(), b.minZ()) && gen.inArea(W, b.maxX(), b.maxY(), b.maxZ());
    }

    private GenService.SlotReport report() {
        return gen.report().stream().filter(r -> r.id().equals(SLOT)).findFirst().orElseThrow();
    }

    @Test
    void theCourseIsBuiltAndGuardedAtItsGapAndTheClaimNamesIt() throws Exception {
        Box a = Regions.half(DEF, FAR, GAP, 'A');
        Box b = Regions.half(DEF, FAR, GAP, 'B');
        boot();
        drive(70);
        GenTag day1 = gen.liveTag(SLOT);
        assertNotNull(day1, "the first set is up");
        assertEquals(a, gen.half(day1), "in half A, at the origin");
        assertTrue(host.world().count(a) > 0, "its blocks are there");
        assertEquals(Regions.claim(DEF, W, FAR, GAP), host.store.meta(GenAdminKeys.claim(SLOT)),
                "the claim names the gap");
        assertTrue(host.store.meta(GenAdminKeys.claim(SLOT)).endsWith("," + GAP), "as an 8th field");
        assertTrue(guarded(a) && guarded(b), "both halves are guarded");
        int midX = a.maxX() + GAP / 2;
        assertFalse(gen.inArea(W, midX, a.minY(), a.minZ()), "the air between them is nobody's");
        Box legacyB = Regions.half(DEF, FAR, Slots.LEGACY_HALF_GAP, 'B');
        assertFalse(gen.inArea(W, legacyB.minX(), legacyB.minY(), legacyB.minZ()),
                "and where half B would stand at 0.35's gap is not guarded");

        host.now = GenKit.at(2026, 9, 30, 4, 0) + 40_000;
        drive(90);
        GenTag day2 = gen.liveTag(SLOT);
        assertEquals('B', day2.half(), "the next set goes into half B");
        assertEquals(b, gen.half(day2), "576 blocks along x from half A");
        assertTrue(host.world().count(b) > 0, "built there");
    }

    @Test
    void aChangedGapIsAMovedRegionLikeAChangedOrigin() throws Exception {
        boot();
        drive(70);
        assertTrue(report().claimed(), "claimed at the wide gap");
        String claim = host.store.meta(GenAdminKeys.claim(SLOT));

        place(FAR, Slots.LEGACY_HALF_GAP);
        gen.check();
        assertTrue(host.logged(Level.WARNING, SLOT + " moved from") > 0, "the gap change is said as a move: "
                + host.logs.stream().map(r -> r.getMessage()).toList());
        assertFalse(report().claimed(), "the old claim doesn't cover the new shape");
        assertEquals(claim, host.store.meta(GenAdminKeys.claim(SLOT)), "and it isn't rewritten by itself");
        assertTrue(host.logged(Level.WARNING, SLOT + " was claimed at another place") > 0,
                "the admin reads where it was claimed");
    }

    @Test
    void a035ClaimAtTheSameOriginIsNotAClaimOfTheWideShape() throws Exception {
        host.store.meta(GenAdminKeys.claim(SLOT), Regions.claim(DEF, W, FAR, Slots.LEGACY_HALF_GAP));
        boot();
        assertFalse(report().claimed(), "7 fields mean 0.35's gap: not this slot's 576-gap region");
        assertTrue(host.logged(Level.WARNING, SLOT + " was claimed at another place") > 0,
                "said, so its old spare half isn't taken for the new one");
        Box oldB = Regions.half(DEF, FAR, Slots.LEGACY_HALF_GAP, 'B');
        assertTrue(host.logged(Level.WARNING, oldB.describe()) > 0, "naming the old half B where it stood");
    }
}
