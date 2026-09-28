package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run's way round a course, with no server.
 *
 * <p>Pinned here: checkpoints count only in order and none can be skipped; one fast move clears
 * every checkpoint it really passes through, in order, and the finish after them; the finish
 * can't count before the last checkpoint even when the move goes through it; each target's time
 * is interpolated to where along the move it was touched; the run's own teleport ({@code jump})
 * starts the next move from there, so nothing between the two places counts; and nothing is
 * reached after the finish.
 */
class ProgressTest {

    /** A straight course along +x: start at 0, checkpoints at 10 and 20, the finish at 30, all radius 1. */
    private static final Course LINE = new Course("line", TrialKind.PARKOUR, "Line", Tier.EASY, "games",
            new Course.Spot(0, 0, 0, 0, 0),
            List.of(new Course.Mark(10, 0, 0, 1), new Course.Mark(20, 0, 0, 1)),
            new Course.Mark(30, 0, 0, 1), null, null, true, false, 1);

    private static Point x(double x) {
        return new Point(x, 0, 0);
    }

    @Test
    void checkpointsCountOneAtATimeInOrder() {
        Progress p = new Progress(LINE, x(0), 0);
        assertEquals(-1, p.lastCheckpoint(), "nothing reached yet: back means the start");
        assertEquals(List.of(), p.move(x(5), 100), "halfway to the first checkpoint");
        List<Progress.Reached> r = p.move(x(12), 200);
        assertEquals(1, r.size(), "the first checkpoint");
        assertEquals(new Progress.Reached(0, false, 157), r.get(0), "touched at x = 9: 4/7 of the move, at 157 ns");
        assertEquals(0, p.lastCheckpoint(), "back now means checkpoint 1");
        assertEquals(1, p.move(x(21), 300).size(), "the second checkpoint");
        assertEquals(1, p.lastCheckpoint(), "back now means checkpoint 2");
        assertFalse(p.finished(), "not at the finish yet");
    }

    @Test
    void oneFastMoveClearsEveryCheckpointItPassesThroughAndTheFinish() {
        Progress p = new Progress(LINE, x(0), 0);
        List<Progress.Reached> r = p.move(x(35), 3500);
        assertEquals(3, r.size(), "both checkpoints and the finish, from one 35-block move");
        assertEquals(new Progress.Reached(0, false, 900), r.get(0), "checkpoint 1 at x = 9");
        assertEquals(new Progress.Reached(1, false, 1900), r.get(1), "checkpoint 2 at x = 19");
        assertEquals(new Progress.Reached(2, true, 2900), r.get(2), "then the finish at x = 29");
        assertTrue(p.finished(), "finished");
        assertArrayEquals(new long[]{900, 1900, 2900}, p.times(), "each reached at its own point along the move");
    }

    @Test
    void aCheckpointCanNotBeSkipped() {
        Course bend = LINE.withCheckpoints(List.of(new Course.Mark(10, 5, 0, 1), new Course.Mark(20, 0, 0, 1)));
        Progress p = new Progress(bend, x(0), 0);
        assertEquals(List.of(), p.move(x(21), 100), "passing through checkpoint 2 without checkpoint 1 counts nothing");
        assertEquals(List.of(), p.move(x(35), 200), "and so the finish doesn't count either");
        assertFalse(p.finished(), "not finished: checkpoint 1 was never reached");
        List<Progress.Reached> r = p.move(new Point(10, 5, 0), 300);
        assertEquals(0, r.get(0).index(), "going back for checkpoint 1 counts it");
        assertEquals(List.of(), p.move(x(35), 400).stream().filter(Progress.Reached::finish).toList(),
                "and the path from it that misses checkpoint 2 still doesn't finish");
    }

    @Test
    void theFinishCanNotCountBeforeTheLastCheckpoint() {
        Course loop = new Course("loop", TrialKind.PARKOUR, "Loop", Tier.EASY, "games",
                new Course.Spot(0, 0, 0, 0, 0), List.of(new Course.Mark(10, 0, 0, 1)),
                new Course.Mark(2, 0, 0, 1), null, null, true, false, 1);
        Progress p = new Progress(loop, x(0), 0);
        assertEquals(List.of(), p.move(x(5), 100).stream().filter(Progress.Reached::finish).toList(),
                "walking through the finish on the way out is not finishing");
        assertEquals(1, p.move(x(10), 200).size(), "the checkpoint");
        List<Progress.Reached> back = p.move(x(0), 300);
        assertTrue(back.get(0).finish(), "now the finish counts, on the way back");
    }

    @Test
    void theFinishTimeIsInterpolatedAlongTheLastMove() {
        Course sprint = new Course("sprint", TrialKind.PARKOUR, "Sprint", Tier.EASY, "games",
                new Course.Spot(0, 0, 0, 0, 0), List.of(), new Course.Mark(30, 0, 0, 1), null, null, true, false, 1);
        Progress p = new Progress(sprint, x(0), 0);
        p.move(x(25), 1000);
        List<Progress.Reached> r = p.move(x(35), 2000);
        assertEquals(new Progress.Reached(0, true, 1400), r.get(0),
                "the move from 25 to 35 entered the finish at 29: 40% of the way, so 1400 ns, not 2000");
    }

    @Test
    void theRunsOwnTeleportStartsTheNextMoveFromThere() {
        Progress p = new Progress(LINE, x(0), 0);
        p.move(x(12), 100);
        p.jump(x(10), 200);
        assertEquals(List.of(), p.move(x(10.5), 300),
                "from checkpoint 1 a small step reaches nothing: the jump isn't a move through anything");
        Progress q = new Progress(LINE, x(0), 0);
        q.jump(x(25), 100);
        assertEquals(List.of(), q.move(x(26), 200),
                "a jump past checkpoints never counts them, and without them nothing after does");
    }

    @Test
    void nothingIsReachedAfterTheFinish() {
        Progress p = new Progress(LINE, x(0), 0);
        p.move(x(35), 100);
        assertEquals(List.of(), p.move(x(0), 200), "finished: the way back counts nothing");
        assertEquals(3, p.reachedTargets(), "two checkpoints and the finish");
        assertEquals(2, p.reachedCheckpoints(), "both checkpoints");
    }
}
