package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sloppy player (GEN-SPEC §4.3): a fixed policy played out over every combination of its aim
 * errors (-6, 0, +6 degrees). On known straights its worst case is pinned; a limit it can't meet
 * stops the tree early (for far less work than the whole tree); the same hole always gives the
 * same answer after the same work; and its score is the spec's.
 */
class KidPolicyTest {

    private static KidPolicy.Result kid(HoleLayout l, int limit, Work work) throws GenFailed {
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        return KidPolicy.evaluate(g, h, LaneMap.of(g, h), limit, work);
    }

    @Test
    void aKnownHolesWorstCaseIsPinned() throws GenFailed {
        KidPolicy.Result thirteen = kid(GolfKit.straight(13, 0), 9, Work.unlimited());
        assertEquals(KidPolicy.Status.WITHIN, thirteen.status(), "a 13-block straight is finished");
        assertEquals(2, thirteen.worst(), "within 2 strokes whatever the aim errors (par 2 + 1 would allow 3)");
        KidPolicy.Result fifteen = kid(GolfKit.straight(15, 0), 9, Work.unlimited());
        assertEquals(3, fifteen.worst(), "a 15-block straight takes the kid at most 3 (par 3)");
        KidPolicy.Result safe = kid(GolfKit.draw(HoleTemplate.SAFE_STRAIGHT, 'S', 0), 9, Work.unlimited());
        assertEquals(2, safe.worst(), "the fallback hole: at most 2");
    }

    @Test
    void aLimitItCantMeetStopsTheTreeEarly() throws GenFailed {
        Work full = Work.unlimited();
        KidPolicy.Result all = kid(GolfKit.straight(15, 0), 3, full);
        assertEquals(KidPolicy.Status.WITHIN, all.status(), "3 is enough for the 15-block straight");
        Work cut = Work.unlimited();
        KidPolicy.Result over = kid(GolfKit.straight(15, 0), 2, cut);
        assertEquals(KidPolicy.Status.OVER_LIMIT, over.status(), "2 isn't");
        assertTrue(over.worst() > 2, "it says by how much it went over: " + over.worst());
        assertTrue(cut.used() < full.used() / 2, "and stops long before the whole tree: " + cut.used() + " of "
                + full.used() + " putts");
        Work spent = new Work(100, null);
        assertEquals(KidPolicy.Status.OVER_BUDGET, kid(GolfKit.straight(15, 0), 9, spent).status(),
                "out of work is its own answer, not a pass");
    }

    @Test
    void theSameHoleGivesTheSameAnswerAfterTheSameWork() throws GenFailed {
        for (long seed = 0; seed < 4; seed++) {
            HoleLayout l = GolfKit.draw(HoleTemplate.DOGLEG, 'M', seed);
            Work a = Work.unlimited();
            Work b = Work.unlimited();
            assertEquals(kid(l, 6, a), kid(l, 6, b), l.describe() + ": the same worst case");
            assertEquals(a.used(), b.used(), l.describe() + ": after the same work");
        }
    }

    @Test
    void itScoresAPuttByItsOutcome() {
        GolfShot.Result in = new GolfShot.Result(BallPhysics.Outcome.IN_CUP, 0, 0, 0, 10);
        GolfShot.Result near = new GolfShot.Result(BallPhysics.Outcome.STOPPED, 0, 0, 0, 10);
        GolfShot.Result wet = new GolfShot.Result(BallPhysics.Outcome.WATER, 0, 0, 0, 10);
        assertEquals(0, KidPolicy.score(in, 5), "in the cup scores 0");
        assertEquals(2, KidPolicy.score(near, 0.5), "a rest within a putt's reach: 1 + 1");
        assertEquals(2, KidPolicy.score(near, 9), "9 blocks is still one putt's reach");
        assertEquals(3, KidPolicy.score(near, 9.5), "past it: 1 + 2");
        assertEquals(4, KidPolicy.score(wet, 3), "a penalty adds 2");
    }
}
