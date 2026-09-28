package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course editor's typed values, with no server.
 *
 * <p>Pinned here: a radius left out is the kind's own, a typed one is kept in range and junk is
 * refused; numbers and whole numbers read or say they can't; the editor prints numbers without a
 * pointless ".0"; the words after the verb never throw, even with {@code confirm} and no verb
 * (a throw there switched the whole game off); and a fall height must sit under the lowest point
 * of the course and not under the world's floor, whichever edit would break that.
 */
class CourseAdminTest {

    @Test
    void aRadiusLeftOutIsTheKindsOwnAndATypedOneIsKeptInRange() {
        assertEquals(TrialKind.ELYTRA.defaultRadius(), CourseAdmin.radius(null, TrialKind.ELYTRA.defaultRadius()), 1e-9,
                "none typed: the elytra default");
        assertEquals(2.5, CourseAdmin.radius("2.5", 3), 1e-9, "as typed");
        assertEquals(Course.MAX_RADIUS, CourseAdmin.radius("99", 3), 1e-9, "too big: the biggest");
        assertTrue(Double.isNaN(CourseAdmin.radius("big", 3)), "junk is refused, not guessed");
    }

    @Test
    void numbersReadOrSayTheyCanNot() {
        assertEquals(60.5, CourseAdmin.number("60.5"), 1e-9, "a y level");
        assertNull(CourseAdmin.number("sixty"), "not a number");
        assertNull(CourseAdmin.number("NaN"), "not a real number");
        assertEquals(3, CourseAdmin.whole(" 3 "), "a checkpoint number");
        assertEquals(-1, CourseAdmin.whole("third"), "not a whole number");
    }

    @Test
    void numbersPrintWithoutAPointlessPointZero() {
        assertEquals("3", CourseAdmin.fmt(3.0), "a whole number");
        assertEquals("2.5", CourseAdmin.fmt(2.5), "a half");
        assertEquals("-12.3", CourseAdmin.fmt(-12.34), "one decimal is enough for an admin");
    }

    @Test
    void theEditorsWordsAreTheOnesItOffers() {
        assertTrue(CourseAdmin.VERBS.containsAll(java.util.List.of("start", "checkpoint", "finish", "tier", "name",
                "fall", "minseconds", "enable", "disable", "info", "tp", "test", "feature", "delete")),
                "every command in the spec is offered");
    }

    @Test
    void theWordsAfterTheVerbNeverThrowEvenWithConfirmAndNoVerb() {
        assertEquals(List.of(), CourseAdmin.rest(new String[] {"cliffs", "confirm"}, true),
                "'cliffs confirm' (delete left out): nothing, not subList(2, 1)");
        assertEquals(List.of(), CourseAdmin.rest(new String[] {"cliffs"}, false), "just the id");
        assertEquals(List.of(), CourseAdmin.rest(new String[] {"cliffs", "delete", "confirm"}, true),
                "delete confirm: no words after the verb");
        assertEquals(List.of("60"), CourseAdmin.rest(new String[] {"cliffs", "fall", "60", "confirm"}, true),
                "the confirm on the end is not a word");
        assertEquals(List.of("Big", "Hill"), CourseAdmin.rest(new String[] {"cliffs", "name", "Big", "Hill"}, false),
                "every word after the verb");
    }

    private static final Course STEPS = Course.create("steps", TrialKind.PARKOUR, Tier.EASY).withWorld("games")
            .withStart(new Course.Spot(0, 64, 0, 0, 0)).plusCheckpoint(new Course.Mark(10, 60, 0, 1.5))
            .withFinish(new Course.Mark(20, 70, 0, 2));

    @Test
    void aFallHeightMustBeUnderTheLowestPointOfTheCourse() {
        assertNull(CourseAdmin.fallProblem(STEPS, -64), "no fall height: nothing to check");
        assertNull(CourseAdmin.fallProblem(STEPS.withFallY(59.5), -64), "under the lowest checkpoint (60)");
        assertTrue(CourseAdmin.fallProblem(STEPS.withFallY(60.0), -64).contains("lowest point"),
                "at the lowest point: every run would go straight back");
        assertTrue(CourseAdmin.fallProblem(STEPS.withFallY(65.0), -64).contains("y 60"),
                "above it, and it says where the lowest point is");
        assertTrue(CourseAdmin.fallProblem(STEPS.withFallY(55.0).plusCheckpoint(new Course.Mark(15, 54, 0, 1.5)), -64)
                .contains("lowest point"), "a checkpoint placed under an existing fall height is refused too");
        assertNull(CourseAdmin.fallProblem(Course.create("x1", TrialKind.PARKOUR, Tier.EASY).withFallY(100.0), null),
                "nothing placed yet: the points placed later are checked against it");
    }

    @Test
    void aFallHeightMayNotBeUnderTheWorldsFloor() {
        assertTrue(CourseAdmin.fallProblem(STEPS.withFallY(-70.0), -64).contains("bottom of the world"),
                "no run ever falls under the floor");
        assertNull(CourseAdmin.fallProblem(STEPS.withFallY(-64.0), -64), "at the floor is fine");
        assertNull(CourseAdmin.fallProblem(STEPS.withFallY(-70.0), null),
                "a world not loaded: only the course is checked");
    }
}
