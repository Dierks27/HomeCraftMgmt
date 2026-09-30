package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.DailySettings.SlotConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Clubhouse's box may stand (CLUBHOUSE-SPEC §1, §8): the shipped origin passes
 * {@link Regions#extraProblems} against the SHIPPED Fresh slots (on or off, the Classics too), the keep
 * area and the arena's box; a slot, the arena or a hand-built course moved next to it refuses it; and
 * its box is exposed to Fresh Courses exactly as the arena's is ({@link ClubhouseRegions#extras}).
 */
class ClubhouseRegionsTest {

    private static final Box BOX = ClubhouseSettings.defaults().box();
    private static final List<Regions.Extra> ARENA = List.of(new Regions.Extra(ArenaRegions.NAME,
            FallingFloorsSettings.defaults().box()));

    private static Regions.WorldFacts world() {
        return new Regions.WorldFacts("games", true, -64, 320, new Box(-30_000, -64, -30_000, 30_000, 319, 30_000),
                new int[]{0, 64, 0}, null);
    }

    private static String only(List<String> problems) {
        assertEquals(1, problems.size(), "one problem: " + problems);
        return problems.get(0);
    }

    @Test
    void theShippedOriginPassesExtraProblemsAgainstTheShippedConfig() {
        DailySettings shipped = DailySettings.defaults();
        assertEquals(List.of(6080, 160, 8544), ClubhouseSettings.defaults().origin(),
                "the shipped corner (LAYOUT-SPEC §1.5)");
        assertEquals(List.of(), ClubhouseRegions.problems(BOX, shipped, ARENA, List.of(), world()),
                "every slot (on or off), every Classics slot, the keep area and the arena are 32 or more away");
        List<SlotConfig> all = new ArrayList<>();
        for (SlotConfig c : shipped.slots()) {
            all.add(c.withEnabled(true));
        }
        for (SlotConfig c : shipped.archive().classics()) {
            all.add(c.withEnabled(true));
        }
        Map<String, String> both = Regions.extraProblems(List.of(ARENA.get(0), ClubhouseRegions.extras(
                ClubhouseSettings.defaults()).get(0)), all, shipped.archive().keep());
        assertEquals(Map.of(), both, "the arena and the Clubhouse, as Fresh Courses would check its extras: both fit");
    }

    @Test
    void itsBoxIsExposedToFreshCoursesAsTheArenasIs() {
        List<Regions.Extra> e = ClubhouseRegions.extras(ClubhouseSettings.defaults());
        assertEquals(1, e.size(), "one extra box");
        assertEquals("clubhouse", e.get(0).name(), "named for admins");
        assertEquals(BOX, e.get(0).box(), "the configured box, on or off (its blocks may stand)");
        assertEquals(List.of(), ClubhouseRegions.extras(null), "none while the settings can't be read");
        SlotConfig moved = DailySettings.defaults().slots().get(0).withOrigin(new int[]{BOX.minX(), 160, BOX.minZ()});
        String p = Regions.extraProblem(e.get(0), List.of(moved), null);
        assertTrue(p != null && p.contains("clubhouse") && p.contains(moved.id()), "a slot moved onto it is refused: " + p);
    }

    @Test
    void aSlotMovedNextToTheBoxRefusesItEvenSwitchedOff() {
        DailySettings shipped = DailySettings.defaults();
        List<SlotConfig> slots = new ArrayList<>(shipped.slots());
        SlotConfig moved = slots.get(0).withEnabled(false).withOrigin(new int[]{BOX.minX(), BOX.minY(), BOX.minZ()});
        slots.set(0, moved);
        String p = only(ClubhouseRegions.problems(BOX, shipped.withSlots(slots), ARENA, List.of(), world()));
        assertTrue(p.contains(moved.id()) && p.contains("32 apart"), "an admin could switch it on any time: " + p);
    }

    @Test
    void theArenaMovedNextToItRefusesIt() {
        List<Regions.Extra> near = List.of(new Regions.Extra(ArenaRegions.NAME, BOX.translate(0, 0, 40)));
        String p = only(ClubhouseRegions.problems(BOX, DailySettings.defaults(), near, List.of(), world()));
        assertTrue(p.contains("falling_floors") && p.contains("32 apart"), "the two extra boxes keep apart: " + p);
    }

    @Test
    void aHandBuiltCourseWithin16RefusesIt() {
        Regions.Area near = new Regions.Area("games", new Box(BOX.maxX() + 10, 165, BOX.minZ(), BOX.maxX() + 12, 167,
                BOX.minZ() + 2), "river_run");
        String p = only(ClubhouseRegions.problems(BOX, DailySettings.defaults(), ARENA, List.of(near), world()));
        assertTrue(p.contains("river_run") && p.contains("16 away"), p);
        assertEquals(List.of(), ClubhouseRegions.problems(BOX, DailySettings.defaults(), ARENA, null, world()),
                "unreadable courses skip that check (the first-use claim still refuses anyone's blocks)");
    }

    @Test
    void theWorldsHeightsBorderSpawnAndListAreChecked() {
        Regions.WorldFacts low = new Regions.WorldFacts("games", true, -64, 170, null, null, null);
        assertTrue(only(ClubhouseRegions.problems(BOX, null, List.of(), List.of(), low)).contains("needs y 160..175"),
                "a world too low for it");
        Regions.WorldFacts spawnInside = new Regions.WorldFacts("games", true, -64, 320, null,
                new int[]{BOX.minX() + 3, 165, BOX.minZ() + 3}, null);
        assertTrue(only(ClubhouseRegions.problems(BOX, null, List.of(), List.of(), spawnInside))
                .contains("spawn is inside"), "the spawn inside the box");
        Regions.WorldFacts unlisted = new Regions.WorldFacts("world", false, -64, 320, null, null, null);
        assertTrue(only(ClubhouseRegions.problems(BOX, null, List.of(), List.of(), unlisted))
                .contains("isn't in games.worlds"), "a world the games aren't played in");
        assertEquals(List.of("there is no box"), ClubhouseRegions.problems(null, null, null, null, null), "no box");
    }
}
