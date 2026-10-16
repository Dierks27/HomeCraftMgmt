package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.LayoutGuard;
import com.dierks.homecraft.games.gen.LayoutScenarios;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
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
 * The legacy guard on the real engine (LAYOUT-DECISIONS item 5): courses 0.35 built at its spots, with
 * its claims, are still claimed, healed and open after the update's first start, with the same
 * editions, boards and layouts, because the guard wrote 0.35's spots and gaps into config.yml. The
 * same start without the guard would take 0.35's "origin changed" path for every one of them (a
 * reroll, a new board, the old blocks left standing, a Dropper's water guarded until drained by hand):
 * that is what the guard is for, and the scenario oracle ({@link LayoutScenarios#legacyProblem}) sees
 * exactly what the engine sees.
 */
class LegacyLayoutEngineTest {

    /** A parkour course and Sky Rings, built in 0.35 and running. */
    private static final List<String> ON = List.of("fresh_parkour_easy", "fresh_rings");
    /** A Dropper 0.35 claimed and then switched off, its pools still standing (water: drain first if moved). */
    private static final Slots.Def DROPPER = Slots.FRESH_DROPPER;
    private static final String W = GenKit.WORLD;

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, ON.toArray(String[]::new));
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

    /** What 0.35 ran: every course at its 0.35 spot, 32 between its halves. */
    private DailySettings v035() {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : host.settings.slots()) {
            slots.add(c.withOrigin(LegacyBoxes.origin(c.def())).withHalfGap(Slots.LEGACY_HALF_GAP));
        }
        return host.settings.withSlots(slots);
    }

    /** What this version reads from {@code c}, with the same courses on as the host. */
    private static DailySettings from(FileConfiguration c) {
        DailySettings st = LayoutFixtures.parse(c, new ArrayList<>()).settings(DailyCourses.SPEC);
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig s : st.slots()) {
            slots.add(s.withEnabled(ON.contains(s.id())));
        }
        return st.withEnabled(true).withWorld(W).withSlots(slots).withCadence(Edition.DAILY);
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

    private GenService.SlotReport report(String id) {
        return gen.report().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    /** 0.35 builds its first sets and stops; returns each course's live tag. */
    private Map<String, GenTag> built035() throws Exception {
        host.settings = v035();
        host.store.meta(GenAdminKeys.claim(DROPPER.id()), LayoutScenarios.Install.claim035(DROPPER));
        boot();
        drive(240);
        Map<String, GenTag> tags = new HashMap<>();
        for (String id : ON) {
            GenTag t = gen.liveTag(id);
            assertNotNull(t, id + ": 0.35 built its first set");
            assertEquals(LayoutScenarios.Install.claim035(Slots.of(id)), host.store.meta(GenAdminKeys.claim(id)),
                    id + ": claimed as 0.35 claimed, 7 fields at its 0.35 spot");
            assertTrue(host.world().count(LegacyBoxes.half(Slots.of(id), t.half())) > 0, id + ": its blocks stand");
            tags.put(id, t);
        }
        gen.stop();
        gen = null;
        return tags;
    }

    private long moveWarnings() {
        return host.logs.stream().filter(r -> r.getLevel() == Level.WARNING).map(r -> r.getMessage())
                .filter(m -> m.contains("claimed at another place") || m.contains("moved from")
                        || m.contains("isn't in its claimed region") || m.contains("drain first")).count();
    }

    @Test
    void coursesBuiltIn035StayClaimedHealedAndOpenAfterTheGuard() throws Exception {
        Map<String, GenTag> before = built035();

        LayoutScenarios.Disk disk = new LayoutScenarios.Disk(LayoutScenarios.marked035());
        LayoutGuard.Outcome o = LayoutGuard.run(LayoutGuard.Store.of(host.db), disk, host.now);
        assertEquals(LayoutGuard.Decision.LEGACY, o.decision(), "0.35 built here, so its layout stays");
        FileConfiguration file = disk.load();
        assertNull(LayoutScenarios.legacyProblem(file, host.store.metaLike("gen.")),
                "the oracle: every claim still matches the region config gives it");

        host.settings = from(file);
        host.logs.clear();
        boot();
        for (String id : ON) {
            assertTrue(report(id).claimed(), id + ": still claimed at its 0.35 spot and shape");
        }
        drive(60);
        assertEquals(0, moveWarnings(), "no course is treated as moved: " + host.logs.stream()
                .map(r -> r.getMessage()).toList());
        for (String id : ON) {
            GenService.SlotReport r = report(id);
            assertEquals(before.get(id), gen.liveTag(id), id + ": the same edition and layout, no reroll");
            assertTrue(r.verified(), id + ": healed at its 0.35 spot and open");
            assertFalse(r.healFailed(), id + ": nothing failed");
            assertEquals(LayoutScenarios.Install.claim035(Slots.of(id)), host.store.meta(GenAdminKeys.claim(id)),
                    id + ": the claim is left exactly as 0.35 wrote it");
        }
        assertTrue(report(DROPPER.id()).claimed(), "the switched-off Dropper is still claimed where its pools stand");
        assertNull(host.store.meta(GenAdminKeys.old(DROPPER.id())), "so no old region is left to guard and drain");
    }

    @Test
    void withoutTheGuardTheSameStartWouldTreatEveryCourseAsMoved() throws Exception {
        built035();
        YamlConfiguration unguarded = LayoutFixtures.v035(); // backfilled, but no half_gap: the default 576
        assertTrue(LayoutScenarios.legacyProblem(unguarded, host.store.metaLike("gen.")).contains("would be moved"),
                "the oracle sees the move");

        host.settings = from(unguarded);
        host.logs.clear();
        boot();
        for (String id : ON) {
            assertFalse(report(id).claimed(), id + ": the 0.35 claim doesn't cover the wider shape");
        }
        assertFalse(report(DROPPER.id()).claimed(), "nor the Dropper's");
        assertEquals(LayoutScenarios.Install.claim035(DROPPER), host.store.meta(GenAdminKeys.old(DROPPER.id())),
                "whose old halves, pools and all, would be guarded until someone empties them (tidy)");
        assertTrue(moveWarnings() >= ON.size() + 1, "each is said as a region claimed elsewhere, the path that rerolls: "
                + host.logs.stream().map(r -> r.getMessage()).toList());
    }
}
