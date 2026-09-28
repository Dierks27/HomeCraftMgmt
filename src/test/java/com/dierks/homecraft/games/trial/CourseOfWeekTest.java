package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The course of the week, with no server.
 *
 * <p>Pinned here: the pick is the same all week and doesn't depend on the order the courses were
 * read in; over the weeks every open course gets its turn; a pinned course wins while it is open
 * and is ignored when it isn't; and with no open course there is none.
 */
class CourseOfWeekTest {

    private static final List<String> OPEN = List.of("cliffs", "river_run", "sky_high", "tower");

    @Test
    void thePickIsStableForTheWeekWhateverTheOrder() {
        String pick = CourseOfWeek.pick(OPEN, 20_000, null);
        assertEquals(pick, CourseOfWeek.pick(OPEN, 20_000, null), "the same week key, the same course");
        assertEquals(pick, CourseOfWeek.pick(List.of("tower", "sky_high", "river_run", "cliffs"), 20_000, null),
                "the order the courses were read in doesn't matter");
    }

    @Test
    void everyOpenCourseGetsItsTurn() {
        Set<String> seen = new HashSet<>();
        for (long week = 20_000; week < 20_000 + 7 * 60; week += 7) {
            seen.add(CourseOfWeek.pick(OPEN, week, null));
        }
        assertEquals(Set.copyOf(OPEN), seen, "sixty weeks reach every course");
    }

    @Test
    void aPinnedCourseWinsWhileItIsOpen() {
        for (long week = 20_000; week < 20_070; week += 7) {
            assertEquals("tower", CourseOfWeek.pick(OPEN, week, "tower"), "pinned: every week");
        }
        String unpinned = CourseOfWeek.pick(OPEN, 20_000, null);
        assertEquals(unpinned, CourseOfWeek.pick(OPEN, 20_000, "closed_one"), "a pinned course that isn't open is ignored");
    }

    @Test
    void noOpenCourseMeansNone() {
        assertNull(CourseOfWeek.pick(List.of(), 20_000, "tower"), "nothing open, nothing picked");
        assertNull(CourseOfWeek.pick(null, 20_000, null), "nothing at all");
    }
}
