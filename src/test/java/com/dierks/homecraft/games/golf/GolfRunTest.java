package com.dierks.homecraft.games.golf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A round's scoring (spec §12): strokes and penalties per hole, the pick-up rule at par +
 * {@code max_over_par}, and the scorecard's totals, par result and holes-in-one.
 */
class GolfRunTest {

    @Test
    void aHoleIsPickedUpOnceTheStrokesReachParPlusMaxOverPar() {
        GolfRun run = new GolfRun(List.of(3), 3);
        assertEquals(6, run.limit(), "par 3, three over: six");
        for (int i = 0; i < 5; i++) {
            run.stroke();
        }
        assertFalse(run.mustPickUp(), "five strokes: keep going");
        run.stroke();
        assertTrue(run.mustPickUp(), "six strokes and not in: picked up");
        GolfRun.HoleScore s = run.pickUp();
        assertEquals(6, s.strokes(), "a picked-up hole scores the limit");
        assertTrue(s.pickedUp(), "and says so");
        assertEquals("Picked up", GolfRun.holeWord(s), "its word");
        assertTrue(run.finished(), "the only hole is done");
    }

    @Test
    void aPenaltyPastTheLimitStillScoresOnlyTheLimit() {
        GolfRun run = new GolfRun(List.of(3), 3);
        for (int i = 0; i < 6; i++) {
            run.stroke();
        }
        run.penalty(); // the sixth putt went into the water
        assertEquals(7, run.strokes(), "the strokes are counted as they happen");
        assertTrue(run.mustPickUp(), "over the limit: picked up");
        assertEquals(6, run.pickUp().strokes(), "never more than par + max over par");
    }

    @Test
    void theLastAllowedStrokeCanStillGoIn() {
        GolfRun run = new GolfRun(List.of(2), 1);
        run.stroke();
        run.stroke();
        run.stroke();
        GolfRun.HoleScore s = run.inCup();
        assertEquals(3, s.strokes(), "in with the third (the limit): three");
        assertFalse(s.pickedUp(), "it went in");
    }

    @Test
    void penaltiesCountAsStrokes() {
        GolfRun run = new GolfRun(List.of(4), 3);
        run.stroke();
        run.penalty();
        run.stroke();
        assertEquals(3, run.inCup().strokes(), "two putts and one penalty: three");
    }

    @Test
    void theScorecardAddsUpAgainstPar() {
        GolfRun run = new GolfRun(List.of(3, 4, 2), 3);
        run.stroke();
        run.stroke();
        assertEquals("Birdie!", GolfRun.holeWord(run.inCup()), "2 on a par 3");
        for (int i = 0; i < 4; i++) {
            run.stroke();
        }
        assertEquals("Par", GolfRun.holeWord(run.inCup()), "4 on a par 4");
        assertEquals(6, run.total(), "six so far");
        assertEquals(-1, run.vsPar(), "one under after two holes");
        assertFalse(run.parOrBetter(), "not finished yet, so no par result");
        run.stroke();
        GolfRun.HoleScore last = run.inCup();
        assertTrue(last.holeInOne(), "1 on the last hole");
        assertEquals("Hole in one!", GolfRun.holeWord(last), "its word");
        assertTrue(run.finished(), "three of three");
        assertEquals(7, run.total(), "2 + 4 + 1");
        assertEquals(9, run.coursePar(), "3 + 4 + 2");
        assertEquals(-2, run.vsPar(), "two under");
        assertTrue(run.parOrBetter(), "under par pays the par reward");
        assertEquals(List.of(3), run.holesInOne(), "hole 3 was a hole-in-one");
        assertEquals(3, run.scores().size(), "one score per hole");
    }

    @Test
    void aRoundOverParIsNotParOrBetter() {
        GolfRun run = new GolfRun(List.of(2, 2), 2);
        for (int h = 0; h < 2; h++) {
            run.stroke();
            run.stroke();
            run.stroke();
            run.inCup();
        }
        assertEquals(2, run.vsPar(), "two over");
        assertFalse(run.parOrBetter(), "no par reward");
        assertTrue(run.holesInOne().isEmpty(), "no holes-in-one");
    }

    @Test
    void exactlyParIsParOrBetter() {
        GolfRun run = new GolfRun(List.of(2), 3);
        run.stroke();
        run.stroke();
        run.inCup();
        assertTrue(run.parOrBetter(), "par counts");
    }

    @Test
    void nothingCountsOnceTheRoundIsOver() {
        GolfRun run = new GolfRun(List.of(2), 3);
        run.stroke();
        run.inCup();
        run.stroke();
        assertEquals(0, run.strokes(), "no stroke after the last hole");
        assertFalse(run.mustPickUp(), "nothing to pick up");
        assertThrows(IllegalStateException.class, run::inCup, "no hole left to finish");
    }

    @Test
    void aRoundNeedsAHole() {
        assertThrows(IllegalArgumentException.class, () -> new GolfRun(List.of(), 3), "no holes, no round");
    }

    @Test
    void theWordsForAScore() {
        assertEquals("par", GolfRun.vsParText(0), "even");
        assertEquals("1 under par", GolfRun.vsParText(-1), "under");
        assertEquals("3 over par", GolfRun.vsParText(3), "over");
        assertEquals("E", GolfRun.vsParShort(0), "short even");
        assertEquals("-2", GolfRun.vsParShort(-2), "short under");
        assertEquals("+4", GolfRun.vsParShort(4), "short over");
        assertEquals("1 stroke", GolfRun.strokesText(1), "one");
        assertEquals("5 strokes", GolfRun.strokesText(5), "many");
        assertEquals("Eagle!", GolfRun.holeWord(new GolfRun.HoleScore(5, 3, false)), "two under");
        assertEquals("Bogey", GolfRun.holeWord(new GolfRun.HoleScore(3, 4, false)), "one over");
        assertEquals("Double bogey", GolfRun.holeWord(new GolfRun.HoleScore(3, 5, false)), "two over");
        assertEquals("3 over par", GolfRun.holeWord(new GolfRun.HoleScore(3, 6, false)), "further over");
    }
}
