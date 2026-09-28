package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Today's pick among the courses, with no server.
 *
 * <p>Pinned here: a course is today's pick by its own id, and every course is when the game
 * itself ({@code /hcm games feature trials}) is — pinning the game used to pay no course at all.
 */
class TimeTrialsTest {

    @Test
    void aCourseIsTodaysPickByItsOwnIdOrWhenTheWholeGameIs() {
        assertTrue(TimeTrials.featured(Set.of("cliffs")::contains, "cliffs"), "the course itself is pinned");
        assertFalse(TimeTrials.featured(Set.of("cliffs")::contains, "river_run"), "another course is not");
        assertTrue(TimeTrials.featured(Set.of("trials")::contains, "river_run"),
                "the game pinned: every course is today's pick");
        assertFalse(TimeTrials.featured(Set.of("test_snake")::contains, "river_run"), "another game pinned: no course");
    }
}
