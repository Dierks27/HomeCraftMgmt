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

    // ---- plan 13: the wing's 16 new rows ----------------------------------------------------------------------------

    /**
     * Shipped, the grown box (64 deep) overlaps nothing and is 32 or more from every shipped site: every Fresh Courses
     * slot and Classic (on or off), the kept courses' area, the arena and The Floor Is Lava's playroom. So it may
     * reach its whole box, and every other area keeps clear of all of it.
     */
    @Test
    void shippedTheGrownBoxClearsEveryShippedSite() {
        DailySettings shipped = DailySettings.defaults();
        ClubhouseSettings club = ClubhouseSettings.defaults();
        List<Regions.Extra> others = new ArrayList<>(ARENA);
        others.addAll(com.dierks.homecraft.games.lava.LavaRegions.extras(
                com.dierks.homecraft.games.lava.LavaSettings.defaults()));
        assertEquals(2, others.size(), "the arena and the playroom");
        assertEquals(List.of(), ClubhouseRegions.problems(club.box(), shipped, others, List.of(), world()),
                "the whole grown box: nothing within 32 of it");
        assertEquals(List.of(), ClubhouseRegions.problems(club.wingBox(), shipped, others, List.of(), world()),
                "its new rows on their own, as the room asks before it claims them");
        for (Regions.Extra o : others) {
            assertTrue(o.box().gap(club.box()) >= Regions.APART, o.name() + " is " + o.box().gap(club.box()) + " away");
        }
        assertEquals(club.box(), ClubhouseRegions.reach(club, shipped, others), "it may reach its whole box");
        assertEquals(List.of(new Regions.Extra(ClubhouseRegions.NAME, club.box())),
                ClubhouseRegions.extras(club, shipped, others), "and the others keep clear of all of it");
        assertEquals(64, club.box().sizeZ());
    }

    /**
     * Where the 16 new rows would crowd another area (a playroom set just south of the room, a slot moved near), the
     * Clubhouse reaches only its old box: that area keeps clear of the old box alone, so the grown wing never closes it,
     * and the room (which asks the same and more before it claims the rows) keeps its old size.
     */
    @Test
    void whereTheNewRowsWouldCrowdAnotherAreaItReachesOnlyItsOldBox() {
        DailySettings shipped = DailySettings.defaults();
        ClubhouseSettings club = ClubhouseSettings.defaults();
        Box wing = club.wingBox();
        // a playroom 40 blocks south of the old box: clear of it, but 24 from the grown box
        Box lava = Box.sized(club.box().minX(), club.box().minY(), club.oldBox().maxZ() + 41, 32, 16, 32);
        assertTrue(lava.gap(club.oldBox()) >= Regions.APART && lava.gap(club.box()) < Regions.APART, "the setup");
        List<Regions.Extra> others = List.of(new Regions.Extra("floor_is_lava", lava));
        assertTrue(only(ClubhouseRegions.problems(wing, shipped, others, null, null)).contains("floor_is_lava"));
        assertEquals(club.oldBox(), ClubhouseRegions.reach(club, shipped, others), "its old box only");
        Regions.Extra seen = ClubhouseRegions.extras(club, shipped, others).get(0);
        assertEquals(club.oldBox(), seen.box());
        assertEquals(null, Regions.extraApartProblem(new Regions.Extra("floor_is_lava", lava), List.of(seen)),
                "so the playroom isn't closed by rows the Clubhouse will never build");
        // a Fresh Courses slot (switched off, even) moved near the new rows: the same
        SlotConfig near = shipped.slots().get(0).withEnabled(false).withOrigin(new int[]{wing.minX(), wing.minY(),
                wing.maxZ() + 20});
        List<SlotConfig> slots = new ArrayList<>(shipped.slots());
        slots.set(0, near);
        DailySettings moved = shipped.withSlots(slots);
        assertEquals(List.of(), ClubhouseRegions.problems(club.oldBox(), moved, ARENA, null, null),
                "the setup: the slot keeps clear of the old box");
        assertTrue(only(ClubhouseRegions.problems(wing, moved, ARENA, null, null)).contains(near.id()),
                "but not of the new rows");
        assertEquals(club.oldBox(), ClubhouseRegions.reach(club, moved, ARENA), "a slot near the new rows");
        assertEquals(club.box(), ClubhouseRegions.reach(club, shipped, ARENA), "shipped: the whole box");
        assertEquals(null, ClubhouseRegions.reach(null, shipped, ARENA), "no settings, no box");
        assertEquals(List.of(), ClubhouseRegions.extras(null, shipped, ARENA));
    }
}
