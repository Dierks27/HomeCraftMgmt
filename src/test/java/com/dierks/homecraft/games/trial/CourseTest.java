package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A course's own rules, with no server.
 *
 * <p>Pinned here: a new course is closed, named from its id, and at layout 1; it can open only
 * with a start, a finish and a world listed in {@code games.worlds} (and says which is missing);
 * its targets are its checkpoints in order and then the finish; checkpoints are added at the end
 * and removed by their 1-based number, the ones after moving up; a radius is kept in range; the
 * layout's fingerprint changes with the world, start, checkpoints, finish or fall height and
 * nothing else; and its lowest point is the lowest of the start, checkpoints and finish.
 */
class CourseTest {

    @Test
    void aNewCourseIsClosedNamedFromItsIdAndAtLayoutOne() {
        Course c = Course.create("cliff_run", TrialKind.PARKOUR, Tier.HARD);
        assertFalse(c.enabled(), "closed until it's finished");
        assertEquals("Cliff Run", c.name(), "named from its id");
        assertEquals(1, c.rev(), "layout 1");
        assertFalse(c.ready(), "nothing placed: it can't be run");
    }

    @Test
    void aCourseOpensOnlyWithAStartAFinishAndAGamesWorld() {
        Course c = Course.create("cliff_run", TrialKind.PARKOUR, Tier.HARD);
        assertEquals(2, c.problems(List.of("games")).size(), "no start, no finish");
        Course placed = c.withWorld("games").withStart(new Course.Spot(0, 64, 0, 0, 0))
                .withFinish(new Course.Mark(10, 64, 0, 2));
        assertEquals(List.of(), placed.problems(List.of("Games")), "complete, in a Games world (any case)");
        assertTrue(placed.ready(), "and it can be run");
        List<String> elsewhere = placed.withWorld("world").problems(List.of("games"));
        assertEquals(1, elsewhere.size(), "built outside a Games world");
        assertTrue(elsewhere.get(0).contains("games.worlds"), "says where it must be");
    }

    @Test
    void theTargetsAreTheCheckpointsInOrderThenTheFinish() {
        Course.Mark a = new Course.Mark(1, 0, 0, 1);
        Course.Mark b = new Course.Mark(2, 0, 0, 1);
        Course.Mark f = new Course.Mark(3, 0, 0, 1);
        Course c = Course.create("x1", TrialKind.BOAT, Tier.EASY).plusCheckpoint(a).plusCheckpoint(b).withFinish(f);
        assertEquals(List.of(a, b, f), c.targets(), "checkpoints first, the finish last");
        assertEquals(List.of(b), c.minusCheckpoint(1).checkpoints(), "removing number 1 moves number 2 up");
        assertEquals(c, c.minusCheckpoint(5), "there is no number 5: unchanged");
        assertEquals(List.of(a, b), Course.create("x1", TrialKind.BOAT, Tier.EASY).plusCheckpoint(a).plusCheckpoint(b)
                .targets(), "no finish yet: just the checkpoints");
    }

    @Test
    void aRadiusIsKeptInRange() {
        assertEquals(Course.MIN_RADIUS, Course.radius(0), 1e-9, "too small: the smallest");
        assertEquals(Course.MAX_RADIUS, Course.radius(100), 1e-9, "too big: the biggest");
        assertEquals(2.5, Course.radius(2.5), 1e-9, "in range: as given");
        assertEquals(Course.MIN_RADIUS, Course.radius(Double.NaN), 1e-9, "not a number: the smallest");
    }

    @Test
    void aSphereHoldsWhatIsWithinItsRadius() {
        Course.Mark m = new Course.Mark(0, 0, 0, 2);
        assertTrue(m.contains(new Point(0, 2, 0)), "on the surface counts");
        assertFalse(m.contains(new Point(0, 2.01, 0)), "just outside doesn't");
    }

    private static Course placed() {
        return Course.create("cliff_run", TrialKind.PARKOUR, Tier.HARD).withWorld("games")
                .withStart(new Course.Spot(0, 64, 0, 90, 0)).plusCheckpoint(new Course.Mark(10, 70, 0, 1.5))
                .withFinish(new Course.Mark(20, 66, 0, 2));
    }

    @Test
    void theLayoutsFingerprintChangesWithTheLayoutAndNothingElse() {
        Course c = placed();
        int layout = c.layoutHash();
        assertEquals(layout, placed().layoutHash(), "the same layout, made again: the same fingerprint");
        assertEquals(layout, c.withName("Cliffs").withTier(Tier.EASY).withEnabled(true).withPinned(true)
                .withMinSeconds(20).withRev(7).layoutHash(),
                "name, tier, open, pin, shortest time and rev aren't layout");
        assertEquals(layout, c.withWorld("GAMES").layoutHash(), "a world name in another case is the same world");
        assertNotEquals(layout, c.withWorld("games2").layoutHash(), "another world");
        assertNotEquals(layout, c.withStart(new Course.Spot(0, 64, 1, 90, 0)).layoutHash(), "a moved start");
        assertNotEquals(layout, c.plusCheckpoint(new Course.Mark(15, 70, 0, 1.5)).layoutHash(), "one more checkpoint");
        assertNotEquals(layout, c.withFinish(new Course.Mark(20, 66, 0, 3)).layoutHash(), "a bigger finish");
        assertNotEquals(layout, c.withFallY(50.0).layoutHash(), "a fall height");
    }

    @Test
    void theLowestPointIsTheLowestOfTheStartCheckpointsAndFinish() {
        assertNull(Course.create("x1", TrialKind.PARKOUR, Tier.EASY).lowestY(), "nothing placed: no lowest point");
        assertEquals(64, placed().lowestY(), 1e-9, "the start (64) under a checkpoint (70) and the finish (66)");
        assertEquals(60, placed().plusCheckpoint(new Course.Mark(15, 60, 0, 1.5)).lowestY(), 1e-9,
                "a lower checkpoint");
        assertEquals(66, Course.create("x1", TrialKind.PARKOUR, Tier.EASY).withFinish(new Course.Mark(0, 66, 0, 1))
                .lowestY(), 1e-9, "only a finish so far");
    }
}
