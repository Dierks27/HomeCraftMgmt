package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf courses (spec §12, §3.5): the YAML kept in {@code game_courses.data} round-trips exactly
 * (unset parts stay unset), junk is refused rather than read as an empty course, a course is
 * playable only when enabled with every hole's tee, cup and bounds set (tee and cup inside the
 * bounds) in a Games world, ids follow the one rule, and edits number the holes as expected.
 */
class GolfCourseTest {

    private static final List<String> WORLDS = List.of("games");

    private static GolfCourse.Hole complete(int par) {
        return new GolfCourse.Hole(new GolfCourse.Tee(10.5, 64.0, -3.25, 91.5f), new GolfCourse.Spot(18, 63, -3), par,
                new GolfCourse.Spot(8, 63, -6), new GolfCourse.Spot(20, 66, 0));
    }

    private static GolfCourse meadow() {
        return new GolfCourse("meadow", "Meadow Links", "games", true, 4, List.of(complete(3), complete(4)));
    }

    // ---- the YAML ---------------------------------------------------------------------------------------

    @Test
    void theHolesRoundTripThroughYaml() {
        List<GolfCourse.Hole> holes = List.of(complete(3),
                new GolfCourse.Hole(new GolfCourse.Tee(-1.125, 70.0, 2.0, -45f), null, 5, new GolfCourse.Spot(1, 2, 3), null),
                new GolfCourse.Hole(null, null, 2, null, null));
        String yaml = CourseCodec.write(holes);
        assertEquals(holes, CourseCodec.read(yaml), "every hole comes back exactly, unset parts unset:\n" + yaml);
        assertTrue(yaml.contains("holes:"), "readable YAML with a holes list:\n" + yaml);
    }

    @Test
    void aCourseRoundTripsThroughItsRow() {
        GolfCourse c = meadow();
        GamesDao.CourseRow row = CourseCodec.toRow(c, 1_000L, 2_000L);
        assertEquals("golf", row.game(), "a golf row");
        assertEquals("meadow", row.id(), "its id");
        assertEquals("Meadow Links", row.name(), "its name in the name column");
        assertEquals("games", row.world(), "its world in the world column");
        assertEquals(4, row.rev(), "its rev in the rev column");
        assertEquals(1_000L, row.createdAt(), "made then");
        assertEquals(2_000L, row.updatedAt(), "edited now");
        assertEquals(c, CourseCodec.fromRow(row), "and back again");
    }

    @Test
    void blankDataIsACourseWithNoHoles() {
        assertTrue(CourseCodec.read("").isEmpty(), "nothing yet");
        assertTrue(CourseCodec.read("format: 1\n").isEmpty(), "no holes key");
    }

    @Test
    void junkIsRefusedNotReadAsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.read("holes: [unclosed"), "not YAML");
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.read("holes: 12\n"), "holes is not a list");
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.read("holes:\n- 3\n"), "a hole is not a map");
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.read("holes:\n- tee: {x: 1, y: 2, z: 3, yaw: 0}\n"),
                "a hole without a par");
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.read("holes:\n- par: 3\n  cup: {x: 1.5, y: 2, z: 3}\n"),
                "a cup must be a block");
        assertThrows(IllegalArgumentException.class, () -> CourseCodec.read("format: 9\nholes: []\n"),
                "a format this version doesn't know");
    }

    // ---- what makes a course playable ---------------------------------------------------------------------

    @Test
    void aCompleteEnabledCourseInAGamesWorldIsPlayable() {
        GolfCourse c = meadow();
        assertTrue(c.problems(WORLDS).isEmpty(), "nothing missing: " + c.problems(WORLDS));
        assertTrue(c.playable(WORLDS), "enabled and ready");
        assertFalse(c.withEnabled(false).playable(WORLDS), "not while disabled");
        assertTrue(c.problems(List.of("GAMES")).isEmpty(), "world names match whatever the case");
    }

    @Test
    void everyMissingPieceIsNamed() {
        GolfCourse none = new GolfCourse("x", "X", "games", true, 1, List.of());
        assertEquals(List.of("it has no holes yet"), none.problems(WORLDS), "a course needs a hole");
        GolfCourse bare = none.addHole(new GolfCourse.Hole(new GolfCourse.Tee(0, 64, 0, 0), null, 3, null, null));
        assertEquals(List.of("hole 1 has no cup", "hole 1 needs both bound corners"), bare.problems(WORLDS),
                "the cup and the bounds");
        GolfCourse elsewhere = new GolfCourse("x", "X", "world", true, 1, List.of(complete(3)));
        assertEquals(List.of("its world world is not in games.worlds"), elsewhere.problems(WORLDS),
                "only the Games worlds");
        GolfCourse badPar = meadow().withHole(2, complete(7));
        assertEquals(List.of("hole 2's par must be 2-6"), badPar.problems(WORLDS), "par 2-6");
    }

    @Test
    void theTeeAndTheCupMustBeInsideTheBounds() {
        GolfCourse.Hole h = complete(3);
        GolfCourse teeOut = meadow().withHole(1, h.withTee(new GolfCourse.Tee(30.0, 64, -3, 0)));
        assertEquals(List.of("hole 1's tee is outside its bounds"), teeOut.problems(WORLDS), "a tee outside");
        GolfCourse cupOut = meadow().withHole(1, h.withCup(new GolfCourse.Spot(21, 63, -3)));
        assertEquals(List.of("hole 1's cup is outside its bounds"), cupOut.problems(WORLDS), "a cup outside");
        GolfCourse edge = meadow().withHole(1, h.withCup(new GolfCourse.Spot(20, 63, 0)));
        assertTrue(edge.problems(WORLDS).isEmpty(), "the corner blocks themselves are inside");
    }

    @Test
    void theBoundsCornersMayBeGivenInAnyOrder() {
        GolfCourse.Hole a = complete(3);
        GolfCourse.Hole b = new GolfCourse.Hole(a.tee(), a.cup(), 3, a.corner2(), a.corner1());
        assertEquals(a.physics(), b.physics(), "the same box either way round");
        BallPhysics.Hole h = a.physics();
        assertEquals(18.5, h.cupX(), 0.0, "the cup's top centre, x");
        assertEquals(64.0, h.cupY(), 0.0, "the top of the cup block");
        assertEquals(-2.5, h.cupZ(), 0.0, "the cup's top centre, z");
    }

    // ---- ids and edits --------------------------------------------------------------------------------------

    @Test
    void courseIdsFollowOneRule() {
        assertNull(GolfCourse.idProblem("meadow"), "plain");
        assertNull(GolfCourse.idProblem("golf_2"), "digits and underscores");
        assertNotNull(GolfCourse.idProblem("Meadow"), "upper case is refused as written");
        assertNull(GolfCourse.idProblem(GolfCourse.normalise(" Meadow ")), "but typed ids are lower-cased first");
        assertNotNull(GolfCourse.idProblem("2holes"), "a letter first");
        assertNotNull(GolfCourse.idProblem("a-b"), "no dashes");
        assertNotNull(GolfCourse.idProblem("a".repeat(33)), "up to 32");
        assertNotNull(GolfCourse.idProblem(null), "none");
    }

    @Test
    void editsNumberTheHolesInOrder() {
        GolfCourse c = GolfCourse.create("m", "M", "games");
        assertFalse(c.enabled(), "a new course starts disabled");
        assertEquals(1, c.rev(), "at rev 1");
        c = c.addHole(complete(2)).addHole(complete(3)).addHole(complete(4));
        assertEquals(List.of(2, 3, 4), c.pars(), "added at the end");
        assertEquals(9, c.par(), "par adds up");
        c = c.removeHole(2);
        assertEquals(List.of(2, 4), c.pars(), "the holes after a removed one move up");
        c = c.withHole(1, c.hole(1).withPar(5));
        assertEquals(5, c.hole(1).par(), "par changed");
        assertNull(c.hole(3), "no hole 3 any more");
        assertNull(c.hole(0), "holes count from 1");
        GolfCourse.Hole h = c.hole(2).withCorner(1, new GolfCourse.Spot(0, 0, 0));
        assertEquals(new GolfCourse.Spot(0, 0, 0), h.corner1(), "corner 1 set");
        assertEquals(complete(4).corner2(), h.corner2(), "corner 2 untouched");
    }
}
