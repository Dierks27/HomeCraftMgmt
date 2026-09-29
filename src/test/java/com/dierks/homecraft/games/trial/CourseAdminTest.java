package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
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
 *
 * <p>And Daily Courses (GEN-SPEC §2.4, §5.5): a course it made allows only info, tp, test and
 * feature here, everything else pointing to {@code /hcm games gen}; and no point of a hand-built
 * course may go inside a Daily Courses half or within 16 blocks of one.
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

    // ---- Daily Courses -------------------------------------------------------------------------

    /** The engine as the editor sees it: Easy Parkour's half A in world "games" is kept. */
    static GeneratedCourses keeping(Box half) {
        return new GeneratedCourses() {
            @Override
            public boolean live(String courseId, GenTag tag) {
                return true;
            }

            @Override
            public boolean standing(GenTag tag) {
                return tag != null;
            }

            @Override
            public String closedLine(String courseId) {
                return "";
            }

            @Override
            public long nextChangeAt() {
                return -1;
            }

            @Override
            public boolean inArea(String world, int x, int y, int z) {
                return "games".equals(world) && half.contains(x, y, z);
            }
        };
    }

    private static Course daily() {
        return new Course("daily_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY, "games",
                new Course.Spot(4100.5, 170, 4100.5, 0, 0), List.of(), new Course.Mark(4150.5, 170, 4150.5, 3),
                167.0, 17, true, false, 3, new GenTag("daily_parkour_easy", "parkour", 1, 20_725, 0, 1L, 'A',
                "abcabcabcabc", 22_500, 45_000, 70_000, List.of(), List.of(), 1L));
    }

    @Test
    void aDailyCourseAllowsOnlyLookingTryingAndFeaturing() {
        Course c = daily();
        for (String verb : List.of("info", "tp", "test", "feature", "INFO")) {
            assertNull(CourseAdmin.dailyRefusal(c, verb), verb + " is allowed on a daily course");
        }
        for (String verb : List.of("start", "checkpoint", "cp", "finish", "fall", "tier", "name", "minseconds",
                "enable", "disable", "delete", "anything")) {
            assertEquals(GenCopy.MADE_BY_DAILY, CourseAdmin.dailyRefusal(c, verb),
                    verb + " would change what Daily Courses rebuilds: it points to /hcm games gen");
        }
        Course slotWithoutTag = c.withGen(null);
        assertEquals(GenCopy.MADE_BY_DAILY, CourseAdmin.dailyRefusal(slotWithoutTag, "start"),
                "a row with a slot's id is Daily Courses' even when its tag was lost");
        Course handBuilt = new Course("cliffs", TrialKind.PARKOUR, "Cliffs", Tier.EASY, "games", null, List.of(),
                null, null, null, false, false, 1);
        for (String verb : CourseAdmin.VERBS) {
            assertNull(CourseAdmin.dailyRefusal(handBuilt, verb), "a hand-built course is untouched: " + verb);
        }
        assertTrue(CourseAdmin.VERBS.containsAll(CourseAdmin.DAILY_VERBS), "the daily verbs are real verbs");
    }

    @Test
    void aPointInsideADailyAreaOrWithinSixteenBlocksOfOneIsRefused() {
        Box half = Slots.DAILY_PARKOUR_EASY.half('A'); // x 4096-4159, y 160-207, z 4096-4159
        GeneratedCourses g = keeping(half);
        assertEquals(GenCopy.EDITOR_REFUSED, CourseAdmin.areaRefusal(g, "games", 4100.5, 170, 4100.5),
                "inside the half");
        assertEquals(GenCopy.EDITOR_REFUSED, CourseAdmin.areaRefusal(g, "games", 4080.2, 170, 4100), "16 west of it");
        assertEquals(GenCopy.EDITOR_REFUSED, CourseAdmin.areaRefusal(g, "games", 4175.9, 223.5, 4175.0),
                "16 past its far corner on every axis");
        assertEquals(GenCopy.EDITOR_REFUSED, CourseAdmin.areaRefusal(g, "games", 4100, 144, 4100), "16 below it");
        assertNull(CourseAdmin.areaRefusal(g, "games", 4079.9, 170, 4100), "17 west: fine");
        assertNull(CourseAdmin.areaRefusal(g, "games", 4100, 224, 4100), "17 above: fine");
        assertNull(CourseAdmin.areaRefusal(g, "games", 4176, 170, 4100), "17 east: fine");
        assertNull(CourseAdmin.areaRefusal(g, "world", 4100, 170, 4100), "another world: fine");
        assertNull(CourseAdmin.areaRefusal(GeneratedCourses.NONE, "games", 4100, 170, 4100),
                "no engine, no areas");
    }
}
