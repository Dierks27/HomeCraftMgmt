package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenArgs;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh Courses keeps its slots and its keep area apart from the Falling Floors arena (the WP-F
 * review's #7: {@code Regions.extraProblems} had no caller, so only the arena refused itself). The
 * rule is C1's extra-box rule ({@link Regions#extraProblem}) asked from Fresh Courses' side.
 *
 * <p>Pinned here: the shipped arena box is clear of every shipped slot, Classics slot and the keep
 * area; a slot moved on top of it, or within {@value Regions#APART} blocks, is refused with the
 * arena named (on or off, since its blocks may stand), and so is keeping while the keep area crowds
 * it; and on the real engine such a slot is off at the next re-check with that reason (logged once),
 * and no course is kept while the keep area crowds it.
 */
class ArenaApartTest {

    private static final Regions.Extra ARENA = DailyCourses.arenaExtras(FallingFloorsSettings.defaults()).get(0);

    private Host host;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, "fresh_parkour_easy");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void theArenaIsTheFallingFloorsBoxByItsName() {
        assertEquals(ArenaRegions.NAME, ARENA.name(), "named as the arena's own check names it");
        assertEquals(FallingFloorsSettings.defaults().box(), ARENA.box(), "its configured box");
        assertEquals(List.of(), DailyCourses.arenaExtras(null), "no settings, no box");
    }

    @Test
    void theShippedPlacesAreClearOfTheArena() {
        DailySettings d = DailySettings.defaults();
        List<DailySettings.SlotConfig> all = new ArrayList<>(d.slots());
        all.addAll(d.archive().classics());
        for (DailySettings.SlotConfig c : all) {
            assertNull(Regions.extrasProblem(c.def(), c.origin(), List.of(ARENA)), c.id() + " keeps clear of the arena");
        }
        assertNull(Regions.keepExtrasProblem(d.archive().keep(), List.of(ARENA)), "and so does the keep area");
    }

    @Test
    void aSlotOrTheKeepAreaCrowdingTheArenaIsRefused() {
        Box a = ARENA.box();
        Slots.Def def = Slots.FRESH_DROPPER;
        String onTop = Regions.extrasProblem(def, new int[]{a.minX(), a.minY(), a.minZ()}, List.of(ARENA));
        assertNotNull(onTop, "a slot on top of the arena is refused");
        assertTrue(onTop.contains(ArenaRegions.NAME) && onTop.contains("on top of"), onTop);
        int[] near = {a.maxX() + 16, a.minY(), a.minZ()}; // 15 blocks clear of it
        String close = Regions.extrasProblem(def, near, List.of(ARENA));
        assertNotNull(close, "a slot within " + Regions.APART + " blocks is refused");
        assertTrue(close.contains("only 15 blocks from"), close);
        assertNull(Regions.extrasProblem(def, new int[]{a.maxX() + 48, a.minY(), a.minZ()}, List.of(ARENA)),
                "47 blocks away is fine");
        assertNull(Regions.extrasProblem(def, near, List.of()), "no arena, nothing to keep clear of");

        KeepArea crowding = new KeepArea(a.maxX() + 1, a.minY(), a.minZ(), 4);
        String keep = Regions.keepExtrasProblem(crowding, List.of(ARENA));
        assertNotNull(keep, "a keep area next to the arena is refused");
        assertTrue(keep.contains("kept courses' area"), keep);
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

    @Test
    void theEngineTurnsOffASlotCrowdingTheArenaAndWontKeepNextToIt() {
        Slots.Def def = Slots.DAILY_PARKOUR_EASY;
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(85);
        assertNotNull(gen.liveTag(def.id()), "day 1's Easy Parkour is built (EASY-1), no arena anywhere near");
        host.now = GenKit.at(2026, 9, 30, 4, 0) + 40_000;
        drive(35);

        int[] o = def.origin();
        host.extras = List.of(new Regions.Extra(ArenaRegions.NAME, Box.sized(o[0], o[1], o[2] - 40, 48, 40, 16)));
        host.now += GenService.VET_EVERY_MS;
        drive(1); // the regular re-check
        GenService.SlotReport r = gen.report().stream().filter(x -> x.id().equals(def.id())).findFirst().orElseThrow();
        assertNotNull(r.problem(), "the slot next to the arena is off");
        assertTrue(r.problem().contains(ArenaRegions.NAME), "and says why: " + r.problem());
        assertTrue(host.logged(java.util.logging.Level.SEVERE, ArenaRegions.NAME) > 0, "logged once, as SEVERE");

        KeepArea keep = host.settings.archive().keep();
        Box k = keep.area();
        host.extras = List.of(new Regions.Extra(ArenaRegions.NAME, Box.sized(k.maxX() + 5, k.minY(), k.minZ(), 48, 40,
                48)));
        said.clear();
        gen.keep(def.id(), GenArgs.which("EASY-1"), "near_the_floors", null, false, true, said::add);
        String heard = String.join("\n", said);
        assertTrue(heard.contains("Keeping is off") && heard.contains(ArenaRegions.NAME),
                "no course is kept while the keep area crowds the arena: " + heard);
        assertFalse(gen.keeper().hasWork(), "and nothing is queued");
    }
}
