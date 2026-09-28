package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course editor's typed values, with no server.
 *
 * <p>Pinned here: a radius left out is the kind's own, a typed one is kept in range and junk is
 * refused; numbers and whole numbers read or say they can't; and the editor prints numbers without
 * a pointless ".0".
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
}
