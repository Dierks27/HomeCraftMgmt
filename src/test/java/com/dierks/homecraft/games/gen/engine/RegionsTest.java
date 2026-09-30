package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.DailySettings.SlotConfig;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where generated courses may stand (GEN-SPEC §2.2, §2.4): the 16-block grid, the world's reach and
 * heights, every half 32 from every other, the border, the spawn and safe spot 16 outside, no
 * hand-built course within 16, and a hand-built row with a slot's id never taken over.
 */
class RegionsTest {

    private static List<SlotConfig> shipped() {
        return new ArrayList<>(DailySettings.defaults().slots());
    }

    private static Regions.WorldFacts world() {
        return new Regions.WorldFacts("games", true, -64, 320, new Box(-30_000, -64, -30_000, 30_000, 319, 30_000),
                new int[]{0, 64, 0}, null);
    }

    @Test
    void theShippedLayoutPassesEveryCheckAndEveryHalfIs576Apart() {
        List<String> warns = new ArrayList<>();
        List<SlotConfig> out = Regions.validate(shipped(), warns::add, "games.fresh.slots");
        assertEquals(List.of(), warns, "the shipped slots need no WARN");
        assertEquals(shipped(), out, "and come out unchanged");
        List<Box> halves = new ArrayList<>();
        for (Slots.Def d : Slots.ALL) {
            halves.addAll(Regions.halves(d, d.origin(), Slots.HALF_GAP));
            assertEquals(0, d.originX() % 16, d.id() + " x is on the grid");
            assertEquals(0, d.originZ() % 16, d.id() + " z is on the grid");
            assertTrue(Regions.worldProblems(d, d.origin(), Slots.HALF_GAP, world()).isEmpty(),
                    d.id() + " fits the Games world");
        }
        for (int i = 0; i < halves.size(); i++) {
            for (int j = i + 1; j < halves.size(); j++) {
                assertTrue(halves.get(i).gap(halves.get(j)) >= 576, halves.get(i).describe() + " and "
                        + halves.get(j).describe() + " are at least 576 apart (out of sight of each other)");
            }
        }
        Slots.Def rings = Slots.SKY_RINGS;
        assertEquals("x 6080..6207, y 128..303, z 4096..4415", rings.half('A').describe(), "the spec's table, half A");
        assertEquals(6784, rings.half('B').minX(), "and half B, 576 past half A");
    }

    // ---- named extra boxes (EVENTS-DROPPER-SPEC §B.3.2) ---------------------------------------------

    /** Every shipped slot and Classics slot, switched on. */
    private static List<SlotConfig> everySlotOn() {
        List<SlotConfig> out = new ArrayList<>();
        for (SlotConfig c : DailySettings.defaults().slots()) {
            out.add(c.withEnabled(true));
        }
        for (SlotConfig c : DailySettings.defaults().archive().classics()) {
            out.add(c.withEnabled(true));
        }
        return out;
    }

    @Test
    void theShippedArenaBoxKeepsClearOfEveryAreaAndTheKeepPlots() {
        Box arena = com.dierks.homecraft.games.arena.FallingFloorsSettings.defaults().box();
        Regions.Extra extra = new Regions.Extra("falling_floors", arena);
        assertNull(Regions.extraProblem(extra, everySlotOn(), DailySettings.defaults().archive().keep()),
                "the shipped arena is 32 from every half (all switched on) and from the kept courses");
        assertTrue(Regions.worldProblems(Slots.DAILY_PARKOUR_EASY, Slots.DAILY_PARKOUR_EASY.origin(),
                Slots.HALF_GAP, world()).isEmpty(),
                "(the world itself is fine)");
    }

    @Test
    void anExtraBoxMustBe32FromEveryHalfAndTheKeepArea() {
        Slots.Def def = Slots.DAILY_PARKOUR_EASY;
        List<SlotConfig> one = List.of(new SlotConfig(def.id(), true, "easy", def.origin(), 2));
        Box b = def.half('B');
        Box at32 = new Box(b.maxX() + 33, b.minY(), b.minZ(), b.maxX() + 40, b.minY() + 5, b.minZ() + 5);
        Box at31 = at32.translate(-1, 0, 0);
        assertEquals(32, at32.gap(b), "(32 blocks between them)");
        assertNull(Regions.extraProblem(new Regions.Extra("arena", at32), one, null), "32 apart is fine");
        String near = Regions.extraProblem(new Regions.Extra("arena", at31), one, null);
        assertNotNull(near, "31 apart is too close");
        assertTrue(near.contains("only 31 blocks from " + def.id() + "'s half B"), near);
        assertTrue(near.contains("32 apart"), "it says how far: " + near);
        assertNull(Regions.extraProblem(new Regions.Extra("arena", at31), List.of(one.get(0).withEnabled(false)), null),
                "a switched-off slot is no neighbour");
        assertTrue(Regions.extraProblem(new Regions.Extra("arena", b), one, null).contains("on top of"),
                "inside a half is on top of it");

        KeepArea keep = new KeepArea(100_000, 128, 100_000, 6);
        Box k = keep.area();
        Box nearKeep = new Box(k.minX(), k.minY(), k.maxZ() + 32, k.minX() + 8, k.minY() + 8, k.maxZ() + 40);
        assertEquals(31, nearKeep.gap(k), "(31 blocks from the plots)");
        String kept = Regions.extraProblem(new Regions.Extra("arena", nearKeep), List.of(), keep);
        assertNotNull(kept, "31 from the kept courses is too close");
        assertTrue(kept.contains("kept courses"), kept);
        assertNull(Regions.extraProblem(new Regions.Extra("arena", nearKeep.translate(0, 0, 1)), List.of(), keep),
                "32 is fine");

        assertTrue(Regions.extraProblem(new Regions.Extra("arena", new Box(0, 300, 0, 10, 330, 10)), List.of(), null)
                .contains("needs y"), "the world's heights are checked too");
        assertTrue(Regions.extraProblem(new Regions.Extra("arena", new Box(29_000_000, 0, 0, 29_000_010, 5, 5)),
                List.of(), null).contains("reaches past"), "and its reach");
    }

    @Test
    void extraBoxesKeepApartFromEachOtherAndReportByName() {
        Box first = new Box(200_000, 100, 200_000, 200_047, 139, 200_047);
        Box close = first.translate(48 + 20, 0, 0);
        Box far = first.translate(48 + 32, 0, 0);
        java.util.Map<String, String> problems = Regions.extraProblems(List.of(new Regions.Extra("one", first),
                new Regions.Extra("two", close), new Regions.Extra("three", far.translate(200, 0, 0))), List.of(), null);
        assertEquals(java.util.Set.of("two"), problems.keySet(), "only the one too close to another: " + problems);
        assertTrue(problems.get("two").contains("only 20 blocks from one"), problems.toString());
        assertTrue(Regions.extraProblems(List.of(new Regions.Extra("one", first), new Regions.Extra("two", far)),
                List.of(), null).isEmpty(), "32 apart is fine");
    }

    @Test
    void anExtraBoxMustBe16FromEveryHandBuiltCourse() {
        Box arena = new Box(1000, 100, 1000, 1047, 139, 1047);
        Regions.Extra extra = new Regions.Extra("falling_floors", arena);
        Regions.Area at16 = new Regions.Area("games", new Box(1064, 100, 1000, 1066, 102, 1002), "river_run");
        Regions.Area at15 = new Regions.Area("games", new Box(1063, 100, 1000, 1065, 102, 1002), "river_run");
        assertEquals(16, at16.box().gap(arena), "(16 blocks between them)");
        assertNull(Regions.extraHandBuiltProblem(extra, "games", List.of(at16)), "16 away is fine");
        String near = Regions.extraHandBuiltProblem(extra, "games", List.of(at15));
        assertNotNull(near, "15 away is too close");
        assertTrue(near.contains("river_run") && near.contains("only 15 blocks from falling_floors"), near);
        assertNull(Regions.extraHandBuiltProblem(extra, "world", List.of(at15)), "another world's course is no neighbour");
        assertTrue(Regions.extraHandBuiltProblem(extra, "GAMES", List.of(new Regions.Area("games", arena, "inside")))
                .contains("inside"), "a course inside the box, in any case of the world's name");
    }

    @Test
    void anOriginOffTheGridIsRoundedDownWithOneWarn() {
        List<SlotConfig> slots = shipped();
        slots.set(0, slots.get(0).withOrigin(new int[]{4100, 160, 4111}));
        List<String> warns = new ArrayList<>();
        List<SlotConfig> out = Regions.validate(slots, warns::add, "games.fresh.slots");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.fresh.slots.fresh_parkour_easy.origin "), warns.get(0));
        assertArrayEquals(new int[]{4096, 160, 4096}, out.get(0).origin(), "rounded down to the grid");
        assertTrue(out.get(0).enabled(), "and still on");
    }

    @Test
    void aSlotOutOfReachOrHeightOrWithABadTierIsSwitchedOffAlone() {
        List<SlotConfig> slots = shipped();
        slots.set(0, slots.get(0).withOrigin(new int[]{29_000_000, 160, 4096}));
        slots.set(1, slots.get(1).withOrigin(new int[]{4352, 300, 4096}));
        slots.set(2, slots.get(2).withTierOrMix("extreme"));
        slots.set(4, slots.get(4).withTierOrMix("EEEEEEEEEE"));
        slots.set(5, slots.get(5).withTierOrMix("EEX"));
        List<String> warns = new ArrayList<>();
        List<SlotConfig> out = Regions.validate(slots, warns::add, "games.fresh.slots");
        assertEquals(5, warns.size(), "one WARN per bad slot: " + warns);
        for (int i : new int[]{0, 1, 2, 4, 5}) {
            assertFalse(out.get(i).enabled(), out.get(i).id() + " is off");
            assertTrue(warns.stream().anyMatch(w -> w.startsWith("games.fresh.slots." + out.get(i).id() + " ")),
                    "its WARN names it");
        }
        assertTrue(out.get(3).enabled(), "fresh_rings is untouched");
        assertTrue(warns.stream().anyMatch(w -> w.contains("29000000")), "the reach is named");
        assertTrue(warns.stream().anyMatch(w -> w.contains("-56..312")), "the height is named");
    }

    @Test
    void aSlotTooCloseToAnotherIsSwitchedOffAndTheFirstKeepsItsPlace() {
        List<SlotConfig> slots = shipped();
        slots.set(0, slots.get(0).withOrigin(LegacyBoxes.origin(Slots.DAILY_PARKOUR_EASY))
                .withHalfGap(LegacyBoxes.HALF_GAP)); // easy at its 0.35 spot, half B at x 4192..4255
        slots.set(1, slots.get(1).withOrigin(new int[]{4240, 160, 4096})); // onto easy's half B
        List<String> warns = new ArrayList<>();
        List<SlotConfig> out = Regions.validate(slots, warns::add, "games.fresh.slots");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("fresh_parkour_easy"), "naming the one it meets: " + warns.get(0));
        assertTrue(out.get(0).enabled(), "the earlier slot stays on");
        assertFalse(out.get(1).enabled(), "the later one is off");
        assertNotNull(Regions.apartProblem(slots.get(1), slots), "the pre-build check agrees");
        assertNull(Regions.apartProblem(shipped().get(0), shipped()), "and passes the shipped layout");
    }

    @Test
    void theWorldsFloorCeilingBorderSpawnAndSafeSpotAreChecked() {
        Slots.Def d = Slots.DAILY_PARKOUR_EASY;
        int[] o = LegacyBoxes.origin(d); // fixed coordinates below: 0.35's spot
        int g = LegacyBoxes.HALF_GAP;
        Regions.WorldFacts low = new Regions.WorldFacts("games", true, 0, 200, null, new int[]{0, 64, 0}, null);
        assertTrue(Regions.worldProblems(d, o, g, low).get(0).contains("160..207"),
                "a 200-high world has no room");
        Regions.WorldFacts unlisted = new Regions.WorldFacts("lobby", false, -64, 320, null, new int[]{0, 64, 0},
                null);
        assertTrue(Regions.worldProblems(d, o, g, unlisted).get(0).contains("games.worlds"),
                "the world must be listed");
        Regions.WorldFacts small = new Regions.WorldFacts("games", true, -64, 320, new Box(-1000, -64, -1000, 999, 319,
                999), new int[]{0, 64, 0}, null);
        assertTrue(Regions.worldProblems(d, o, g, small).get(0).contains("border"),
                "a small border is too small");
        Regions.WorldFacts spawnInside = new Regions.WorldFacts("games", true, -64, 320, null,
                new int[]{4100, 170, 4100}, null);
        assertTrue(Regions.worldProblems(d, o, g, spawnInside).get(0).contains("spawn is inside half A"),
                "a spawn inside a half");
        Regions.WorldFacts spawnNear = new Regions.WorldFacts("games", true, -64, 320, null,
                new int[]{4096 - 10, 170, 4100}, null);
        assertTrue(Regions.worldProblems(d, o, g, spawnNear).get(0).contains("only 9 blocks"),
                "a spawn 9 away");
        Regions.WorldFacts safe = new Regions.WorldFacts("games", true, -64, 320, null, new int[]{0, 64, 0},
                new double[]{4180.5, 170, 4100.5});
        assertTrue(Regions.worldProblems(d, o, g, safe).get(0).contains("safe_spot"),
                "a safe spot between the halves");
        Regions.WorldFacts farSpawn = new Regions.WorldFacts("games", true, -64, 320, null,
                new int[]{4096 - 17, 170, 4100}, null);
        assertTrue(Regions.worldProblems(d, o, g, farSpawn).isEmpty(), "16 blocks between is fine");
    }

    @Test
    void aHandBuiltCourseWithin16OfAHalfIsFoundAndOurOwnRowsAreNot() {
        Slots.Def d = Slots.DAILY_PARKOUR_EASY;
        int[] o = LegacyBoxes.origin(d); // the fixed coordinates below are round 0.35's spot
        int g = LegacyBoxes.HALF_GAP;
        Course near = new Course("river_run", TrialKind.PARKOUR, "River Run", Tier.EASY, "games",
                new Course.Spot(0, 70, 0, 0f, 0f), List.of(new Course.Mark(4096 - 14, 170, 4100, 1.5)), null, null,
                null, false, false, 1);
        Course far = near.withCheckpoints(List.of(new Course.Mark(4096 - 20, 170, 4100, 1.5))).withName("Far");
        GolfCourse golf = new GolfCourse("meadow", "Meadow", "games", true, 1, List.of(new GolfCourse.Hole(
                new GolfCourse.Tee(4165.5, 165, 4100.5, 0f), new GolfCourse.Spot(4170, 164, 4100), 3,
                new GolfCourse.Spot(4162, 163, 4095), new GolfCourse.Spot(4175, 168, 4110))));
        GenTag tag = new GenTag(d.id(), "parkour", 1, 20725, 0, 1, 'A', "abc", 1, 2, 3, List.of(), List.of(), 0);
        Course ours = near.withGen(tag);
        List<GamesDao.CourseRow> rows = List.of(
                new GamesDao.CourseRow("river_run", "trials", "parkour", "River Run", "games", true,
                        CourseCodec.encode(near), 1, 0, 0),
                new GamesDao.CourseRow("far", "trials", "parkour", "Far", "games", true, CourseCodec.encode(far), 1, 0,
                        0),
                com.dierks.homecraft.games.golf.CourseCodec.toRow(golf, 0, 0),
                new GamesDao.CourseRow(d.id(), "trials", "parkour", d.name(), "games", true, CourseCodec.encode(ours), 1,
                        0, 0));
        List<Regions.Area> areas = Regions.handBuilt(rows);
        assertTrue(areas.stream().noneMatch(a -> a.courseId().equals(d.id())), "our own row is not hand-built");
        String problem = Regions.handBuiltProblem(d, o, g, "games", areas);
        assertNotNull(problem, "a checkpoint 14 from half A (13 blocks between, radius 1.5) is too close");
        assertTrue(problem.contains("river_run"), problem);
        List<Regions.Area> onlyFar = Regions.handBuilt(List.of(rows.get(1)));
        assertNull(Regions.handBuiltProblem(d, o, g, "games", onlyFar), "20 away is fine");
        List<Regions.Area> onlyGolf = Regions.handBuilt(List.of(rows.get(2)));
        assertTrue(Regions.handBuiltProblem(d, o, g, "games", onlyGolf).contains("meadow"),
                "a golf hole's bounds between the halves are found");
        assertNull(Regions.handBuiltProblem(d, o, g, "other_world", areas),
                "another world is never in the way");
    }

    @Test
    void aHandBuiltRowWithASlotsIdIsNeverTakenOver() {
        Slots.Def d = Slots.DAILY_PARKOUR_EASY;
        Course hand = new Course(d.id(), TrialKind.PARKOUR, "Mine", Tier.EASY, "games", null, List.of(), null, null,
                null, false, false, 1);
        GamesDao.CourseRow row = new GamesDao.CourseRow(d.id(), "trials", "parkour", "Mine", "games", false,
                CourseCodec.encode(hand), 1, 0, 0);
        assertEquals("course fresh_parkour_easy exists and wasn't made by Fresh Courses", Regions.takenByHand(d, row),
                "a hand-built row is refused");
        GenTag tag = new GenTag(d.id(), "parkour", 1, 20725, 0, 1, 'A', "abc", 1, 2, 3, List.of(), List.of(), 0);
        GamesDao.CourseRow gen = new GamesDao.CourseRow(d.id(), "trials", "parkour", "Mine", "games", false,
                CourseCodec.encode(hand.withGen(tag)), 1, 0, 0);
        assertNull(Regions.takenByHand(d, gen), "our own row is ours");
        GamesDao.CourseRow golfGame = new GamesDao.CourseRow(d.id(), "golf", "golf", "Mine", "games", false,
                CourseCodec.encode(hand.withGen(tag)), 1, 0, 0);
        assertNotNull(Regions.takenByHand(d, golfGame), "a row of the wrong game is not ours");
        assertNull(Regions.takenByHand(d, null), "no row at all is fine");
        assertTrue(Regions.hasGen(CourseCodec.encode(hand.withGen(tag))), "a gen: block is seen");
        assertTrue(Regions.hasGen("name: x\ngen: broken\n"), "even an unreadable one (it is still ours)");
        assertFalse(Regions.hasGen(CourseCodec.encode(hand)), "and none on a hand-built row");
    }

    @Test
    void aClaimNamesItsWorldAndOriginAndTheRolloverNoteComesOnlyAwayFromARestart() {
        Slots.Def d = Slots.TINY_GOLF;
        String claim = Regions.claim(d, "Games", LegacyBoxes.origin(d), LegacyBoxes.HALF_GAP);
        assertEquals("games,5120,160,4096,64,16,48", claim, "world (lower case), origin and one half's size");
        assertArrayEquals(LegacyBoxes.origin(d), Regions.claimOrigin(claim), "the origin reads back");
        assertNull(Regions.claimOrigin("garbage"), "junk isn't a claim");
        List<LocalTime> restarts = List.of(LocalTime.of(4, 0), LocalTime.of(16, 0));
        assertNull(Regions.rolloverNote(LocalTime.of(4, 0), restarts), "a rollover at a restart needs no note");
        assertNull(Regions.rolloverNote(LocalTime.of(4, 10), restarts), "nor 10 minutes after one");
        assertTrue(Regions.rolloverNote(LocalTime.of(3, 0), restarts).contains("while the server is running"),
                "one an hour before a restart gets the note");
        assertNull(Regions.rolloverNote(LocalTime.of(3, 0), List.of()), "no restarts, no note");
    }
}
