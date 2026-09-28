package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a finished round can't be recorded (spec §12): the course it was played on is gone, is
 * no longer golf's, has a new layout, or was closed by an admin while it was played (a player
 * still on their way to the first tee when it closed must not be recorded and paid either).
 */
class GolfRoundsTest {

    private static GolfCourse meadow() {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(10.5, 64.0, -3.5, 90f), new GolfCourse.Spot(18, 63, -3),
                3, new GolfCourse.Spot(8, 63, -6), new GolfCourse.Spot(20, 66, 0));
        return new GolfCourse("meadow", "Meadow Links", "games", true, 4, List.of(h));
    }

    private static GamesDao.CourseRow row(GolfCourse c) {
        return CourseCodec.toRow(c, 1L, 2L);
    }

    @Test
    void aRoundOnTheCourseAsItStillIsCounts() {
        GolfCourse c = meadow();
        assertFalse(GolfRounds.stale(c, row(c)), "same layout, still open: recorded");
        assertFalse(GolfRounds.stale(c, row(c.withName("Meadow").withHole(1, c.hole(1).withPar(4)))),
                "a new name or par isn't a new layout");
    }

    @Test
    void aRoundOnAClosedChangedOrDeletedCourseDoesNotCount() {
        GolfCourse c = meadow();
        assertTrue(GolfRounds.stale(c, row(c.withEnabled(false))), "closed by an admin meanwhile");
        assertTrue(GolfRounds.stale(c, row(c.withRev(5))), "a new layout");
        assertTrue(GolfRounds.stale(c, null), "deleted");
        GamesDao.CourseRow r = row(c);
        GamesDao.CourseRow trial = new GamesDao.CourseRow(r.id(), "trials", "parkour", r.name(), r.world(), true,
                r.data(), r.rev(), r.createdAt(), r.updatedAt());
        assertTrue(GolfRounds.stale(c, trial), "the id now belongs to another game's course");
    }

    @Test
    void aPlayerOnAChangedCourseIsToldWhyAndThatTheirThingsAreBack() {
        String line = GolfRounds.changedLine(meadow());
        assertTrue(line.contains("Meadow Links was changed by an admin"), "names the course: " + line);
        assertTrue(line.contains("this round can't count"), "says it won't count: " + line);
        assertTrue(line.contains("Your things are back."), "and that nothing was lost: " + line);
    }
}
