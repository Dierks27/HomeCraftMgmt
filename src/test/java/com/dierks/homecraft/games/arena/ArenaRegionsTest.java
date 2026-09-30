package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.DailySettings.SlotConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the arena box may stand (EVENTS-DROPPER-SPEC §B.3.2, S3): the shipped box is clear of every
 * Fresh Courses half (switched on or not), the Classics slots and the kept courses, and fits the
 * Games world; a slot moved next to it, a hand-built course within 16, a world too low, a border it
 * crosses, a spawn or safe spot too near and a world that isn't a Games world each refuse it.
 */
class ArenaRegionsTest {

    private static final Box BOX = FallingFloorsSettings.defaults().box();

    private static Regions.WorldFacts world() {
        return new Regions.WorldFacts("games", true, -64, 320, new Box(-30_000, -64, -30_000, 30_000, 319, 30_000),
                new int[]{0, 64, 0}, null);
    }

    private static String only(List<String> problems) {
        assertEquals(1, problems.size(), "one problem: " + problems);
        return problems.get(0);
    }

    @Test
    void theShippedBoxFitsTheShippedWorldAndKeepsClearOfEveryArea() {
        DailySettings shipped = DailySettings.defaults();
        assertEquals(List.of(), ArenaRegions.problems(BOX, shipped, List.of(), world()),
                "every slot (on or off), every Classics slot and the keep area are 32 or more away");
    }

    @Test
    void aSlotMovedNextToTheBoxRefusesItEvenSwitchedOff() {
        DailySettings shipped = DailySettings.defaults();
        List<SlotConfig> slots = new ArrayList<>(shipped.slots());
        SlotConfig moved = slots.get(0).withEnabled(false).withOrigin(new int[]{BOX.minX(), BOX.minY(), BOX.minZ()});
        slots.set(0, moved);
        String p = only(ArenaRegions.problems(BOX, shipped.withSlots(slots), List.of(), world()));
        assertTrue(p.contains(moved.id()) && p.contains("32 apart"), "an admin could switch it on any time: " + p);
    }

    @Test
    void aHandBuiltCourseWithin16RefusesIt() {
        Regions.Area near = new Regions.Area("games", new Box(BOX.maxX() + 10, 180, BOX.minZ(), BOX.maxX() + 12, 182,
                BOX.minZ() + 2), "river_run");
        String p = only(ArenaRegions.problems(BOX, DailySettings.defaults(), List.of(near), world()));
        assertTrue(p.contains("river_run") && p.contains("16 away"), p);
        assertEquals(List.of(), ArenaRegions.problems(BOX, DailySettings.defaults(), null, world()),
                "unreadable courses skip that check (the first-use claim still refuses anyone's blocks)");
    }

    @Test
    void theWorldsHeightsBorderSpawnSafeSpotAndListAreChecked() {
        Regions.WorldFacts low = new Regions.WorldFacts("games", true, -64, 200, null, null, null);
        assertTrue(only(ArenaRegions.problems(BOX, null, List.of(), low)).contains("needs y 176..215"),
                "a world too low for it");
        Regions.WorldFacts bordered = new Regions.WorldFacts("games", true, -64, 320, new Box(-5000, -64, -5000, 5000,
                319, 5000), null, null);
        assertTrue(only(ArenaRegions.problems(BOX, null, List.of(), bordered)).contains("world border"), "past the border");
        Regions.WorldFacts spawnInside = new Regions.WorldFacts("games", true, -64, 320, null,
                new int[]{BOX.minX() + 3, 200, BOX.minZ() + 3}, null);
        assertTrue(only(ArenaRegions.problems(BOX, null, List.of(), spawnInside)).contains("spawn is inside"),
                "the spawn inside the box");
        Regions.WorldFacts safeNear = new Regions.WorldFacts("games", true, -64, 320, null, null,
                new double[]{BOX.maxX() + 5.5, 190, BOX.minZ() + 4.5});
        String p = only(ArenaRegions.problems(BOX, null, List.of(), safeNear));
        assertTrue(p.contains("safe_spot") && p.contains("only 4 blocks"), "the safe spot 4 away: " + p);
        Regions.WorldFacts unlisted = new Regions.WorldFacts("world", false, -64, 320, null, null, null);
        assertTrue(only(ArenaRegions.problems(BOX, null, List.of(), unlisted)).contains("isn't in games.worlds"),
                "a world the games aren't played in");
    }
}
