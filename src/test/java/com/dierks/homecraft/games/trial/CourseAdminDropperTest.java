package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course editor and the Dropper (EVENTS-DROPPER-SPEC §B.1.2), with no server: a dropper can't be
 * made by hand; a Fresh dropper is Fresh Courses' like any generated course (only info, tp, test and
 * feature); a KEPT dropper is an ordinary hand-built {@code trials} row, so it can be renamed,
 * re-tiered, enabled, tested, featured and deleted, but the verbs that would move its proven ledges
 * and pools (or its physics-made shortest time) are refused, and tab completion doesn't offer them.
 */
class CourseAdminDropperTest {

    /** A dropper kept as "dragon_drop": the Dropper's marks, no tag. */
    private static final Course KEPT = new Course("dragon_drop", TrialKind.DROPPER, "Dragon Drop", Tier.MEDIUM, "games",
            DropperCourses.START, DropperCourses.hand().checkpoints(), DropperCourses.POOL_3, 44.0, 4, true, false, 1);

    /** What the editor answers for {@code verb} on {@code c}: Fresh Courses' refusal, then the dropper's. */
    private static String refusal(Course c, String verb) {
        String daily = CourseAdmin.dailyRefusal(c, verb);
        return daily != null ? daily : DropperLayout.editRefusal(c, verb);
    }

    @Test
    void aDropperCantBeMadeByHand() {
        assertFalse(TrialKind.DROPPER.handMade(), "create <id> dropper is refused");
        assertEquals("Droppers are made by Fresh Courses; keep one to make it permanent.", CourseAdmin.HAND_MADE_DROPPER,
                "with the spec's words");
        assertFalse(TrialKind.handMadeIds().contains("dropper"), "and create's tab completion doesn't offer it");
    }

    @Test
    void aKeptDropperCanBeRenamedAndLookedAtButItsLayoutStays() {
        for (String verb : List.of("info", "tp", "test", "name", "tier", "enable", "disable", "feature", "delete")) {
            assertNull(refusal(KEPT, verb), verb + " works on a kept dropper");
        }
        Course renamed = KEPT.withName("Big Drop");
        assertEquals("Big Drop", renamed.name(), "renamed");
        assertEquals(KEPT.layoutHash(), renamed.layoutHash(), "a new name is no new layout: its board stays");
        assertEquals(List.of(), DropperLayout.problems(renamed), "and it is still a well-formed dropper");
        for (String verb : List.of("start", "checkpoint", "cp", "finish", "fall", "minseconds")) {
            assertEquals(DropperLayout.GEOMETRY_REFUSED, refusal(KEPT, verb), verb + " would move a proven ledge or pool");
        }
        List<String> offered = CourseAdmin.VERBS.stream().filter(v -> refusal(KEPT, v) == null).toList();
        assertTrue(offered.containsAll(List.of("name", "tier", "enable", "test", "feature")), "offered: " + offered);
        assertFalse(offered.contains("start") || offered.contains("finish"), "the geometry verbs aren't: " + offered);
    }

    @Test
    void aFreshDropperIsFreshCoursesLikeAnyGeneratedCourse() {
        GenTag tag = new GenTag("fresh_dropper", "dropper", 1, 20_724, 0, 7L, 'A', "abcabcabcabc", 17_400, 26_100,
                38_280, List.of(), List.of(), 1L);
        Course fresh = DropperCourses.hand(tag);
        assertEquals(GenCopy.MADE_BY_DAILY, refusal(fresh, "name"), "it points to /hcm games gen");
        assertEquals(GenCopy.MADE_BY_DAILY, refusal(fresh, "start"), "for geometry too");
        assertNull(refusal(fresh, "test"), "but can be tested");
    }
}
