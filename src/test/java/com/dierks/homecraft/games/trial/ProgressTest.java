package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;
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
 * starts the next move from there, so nothing between the two places counts; nothing is
 * reached after the finish; and on a parkour course Fresh Courses made a checkpoint counts with the
 * feet up on its pad, never while falling past its side or under it, while a hand-built parkour
 * course (a mark set in the air above its pad included) and the other kinds keep the whole sphere.
 *
 * <p>0.37.3, on a real Easy layout's pads: a target counts only with the feet over its pad's column, so a
 * jump that falls short beside the pad, one that hits its side and slides down, one stopped flat against its
 * side from just beside it (the checkpoint and the finish: the column has no hair along a move), feet on the
 * edge of the pad before, and anything under the pad never count, while a landing anywhere on the pad (its
 * corners, and a Bedrock player's rounded stand or landing on an edge, a hair off only where the move ends at
 * the top's height) and a jump over it still do, at the time the sphere gives.
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

    /** Fresh Courses' tag: the course was generated, so its marks sit on their pads' tops. */
    private static final GenTag FRESH = new GenTag("fresh_parkour_easy", "parkour", 1, 20_725, 0, 1L, 'A',
            "abcabcabcabc", 1, 2, 3, List.of(), List.of(), 0);

    /**
     * A Fresh Courses parkour pad's checkpoint at (10, 64, 0) on its top, sized for a 3x3 pad (r 2.6), then the
     * finish.
     */
    private static final Course PAD = new Course("pad", TrialKind.PARKOUR, "Pad", Tier.EASY, "games",
            new Course.Spot(20, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 2.6)),
            new Course.Mark(0, 64, 0, 3), null, null, true, false, 1, FRESH);

    private static Course as(TrialKind kind) {
        return new Course("pad", kind, "Pad", Tier.EASY, "games", PAD.start(), PAD.checkpoints(), PAD.finish(),
                null, null, true, false, 1, FRESH);
    }

    @Test
    void aParkourCheckpointFallenPastOrUnderDoesNotCount() {
        Progress p = new Progress(PAD, new Point(13.5, 63.6, 0), 0);
        assertEquals(List.of(), p.move(new Point(11.5, 62.6, 0), 100),
                "a jump that fell short, in the gap beside the pad and under its top: nothing");
        assertEquals(List.of(), p.move(new Point(10, 61.8, 0), 200), "falling on under the pad: still nothing");
        assertEquals(-1, p.lastCheckpoint(), "so a fall from here goes back to the start, not onto the pad");
    }

    @Test
    void aParkourCheckpointClimbedOntoCountsOnceTheFeetAreUp() {
        Progress p = new Progress(PAD, new Point(12.8, 63.0, 0), 0);
        assertEquals(List.of(new Progress.Reached(0, false, 500)), p.move(new Point(11.6, 64.2, 0), 1200),
                "the jump up counts where the feet rise over the pad's top less half a block (5/12), not at 297 ns");
    }

    @Test
    void aParkourCheckpointLandedOnCountsAsBefore() {
        Progress p = new Progress(PAD, new Point(13.5, 65.25, 0), 0);
        assertEquals(List.of(new Progress.Reached(0, false, 434)), p.move(new Point(11.2, 64.0, 0), 1000),
                "coming down onto the pad from above: the same time as the whole sphere gives");
    }

    @Test
    void aHandBuiltParkourCourseKeepsTheWholeSphere() {
        Progress p = new Progress(PAD.withGen(null), new Point(13.5, 63.6, 0), 0);
        assertEquals(1, p.move(new Point(11.5, 62.6, 0), 100).size(),
                "no gen tag (built by hand, or kept): the same move under the mark counts, as it always did");
        assertEquals(0, p.lastCheckpoint(), "checkpoint 1");
    }

    @Test
    void aHandBuiltMarkSetInTheAirAboveItsPadIsStillReachedByWalkingOntoThePad() {
        // set while flying a block above a pad whose top is y 64, with the default parkour radius
        Course air = new Course("air", TrialKind.PARKOUR, "Air", Tier.EASY, "games", new Course.Spot(14, 64, 0, 0, 0),
                List.of(new Course.Mark(10, 65, 0, TrialKind.PARKOUR.defaultRadius())), new Course.Mark(0, 64, 0, 1.5),
                null, null, true, false, 1);
        Progress p = new Progress(air, new Point(14, 64, 0), 0);
        List<Progress.Reached> r = p.move(new Point(10, 64, 0), 1000);
        assertEquals(1, r.size(), "walking onto the pad, the feet a block under the mark: it counts");
        assertEquals(0, p.lastCheckpoint(), "checkpoint 1");
    }

    @Test
    void ringsBoatMarksAndPoolsKeepTheWholeSphere() {
        for (TrialKind kind : List.of(TrialKind.ELYTRA, TrialKind.BOAT, TrialKind.DROPPER)) {
            Progress p = new Progress(as(kind), new Point(13.5, 63.6, 0), 0);
            List<Progress.Reached> r = p.move(new Point(11.5, 62.6, 0), 100);
            assertEquals(1, r.size(), kind + ": the same move under the mark still counts");
            assertEquals(0, p.lastCheckpoint(), kind + ": checkpoint 1");
        }
    }

    // ---- 0.37.3: over the pad's column, on a real Easy layout ----------------------------------------

    /** A real Easy checkpoint: pad x 4138-4140, z 4153-4155, top 170; the pad before it is x 4143-4145. */
    private static final Course.Mark EASY_CP = new Course.Mark(4139.5, 170, 4154.5, 2.6);
    /** That layout's finish: pad x 4120-4124, z 4122-4126, top 170; the pad before it is z 4128-4130. */
    private static final Course.Mark EASY_FINISH = new Course.Mark(4122.5, 170, 4124.5, 3.0);
    /** The layout as Fresh Courses runs it: Easy falls back at 166 (its lowest pad, 169, less 3). */
    private static final Course EASY = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY,
            "games", new Course.Spot(4147.5, 170, 4154.5, 90, 0), List.of(EASY_CP), EASY_FINISH, 166.0, null, true,
            false, 1, FRESH);

    /** A standing jump off the front edge of the pad before (feet at x 4142.70) that falls short beside the pad. */
    private static final List<Point> FELL_SHORT = List.of(new Point(4142.113, 170.797, 4155.1),
            new Point(4141.985, 170.495, 4155.1), new Point(4141.850, 170.121, 4155.1),
            new Point(4141.706, 169.676, 4155.1), new Point(4141.556, 169.162, 4155.1),
            new Point(4141.300, 167.931, 4155.1), new Point(4141.187, 164.693, 4155.1));
    private static final Point TAKE_OFF = new Point(4142.70, 170.0, 4155.1);

    /** Each point a tick (50 ms) after the last: everything reached on the way. */
    private static List<Progress.Reached> feed(Progress p, List<Point> ticks) {
        List<Progress.Reached> all = new java.util.ArrayList<>();
        long at = 0;
        for (Point t : ticks) {
            at += 50;
            all.addAll(p.move(t, at));
        }
        return all;
    }

    @Test
    void aJumpThatFallsShortBesideThePadDoesNotCount() {
        Progress p = new Progress(EASY, TAKE_OFF, 0);
        assertEquals(List.of(), feed(p, FELL_SHORT),
                "0.37.1 counted at the third tick (2.43 from the mark, the box 0.55 clear of the pad): nothing now");
        assertEquals(-1, p.lastCheckpoint(), "so the fall at 166 goes back to the start, not onto the missed pad");
    }

    @Test
    void aJumpThatHitsThePadsSideAndSlidesDownDoesNotCount() {
        Progress p = new Progress(EASY, new Point(4142.70, 170.0, 4154.5), 0);
        assertEquals(List.of(), feed(p, List.of(new Point(4141.941, 170.797, 4154.5),
                new Point(4141.721, 170.495, 4154.5), new Point(4141.502, 170.121, 4154.5),
                new Point(4141.300, 169.676, 4154.5), new Point(4141.300, 169.162, 4154.5),
                new Point(4141.300, 168.580, 4154.5))),
                "0.37.1 counted at the first tick (2.57 from the mark); pinned against the face at 0.3 past the edge, "
                        + "it is over the pad's column only under its top: nothing");
        assertEquals(-1, p.lastCheckpoint(), "not reached");
    }

    @Test
    void aJumpStoppedFlatAgainstThePadsSideFromJustBesideItDoesNotCount() {
        // Java moves down before across: the tick before, the box was 0.03 clear of the face and the feet 0.12 over
        // the top; the player fell past the edge and was stopped flat against the face 0.28 under it
        Progress p = new Progress(EASY, new Point(4141.33, 170.12, 4154.5), 0);
        assertEquals(List.of(), p.move(new Point(4141.30, 169.72, 4154.5), 50),
                "the line between crosses 0.02 past the edge at y 169.987, which a 0.02 band counted: nothing");
        assertEquals(List.of(), p.move(new Point(4141.30, 169.16, 4154.5), 100), "sliding on down: nothing");
        assertEquals(-1, p.lastCheckpoint(), "so the fall at 166 goes back to the start, not onto the missed pad");
    }

    @Test
    void aJumpStoppedFlatAgainstTheFinishPadsSideDoesNotFinish() {
        Progress p = new Progress(EASY.withCheckpoints(List.of()), new Point(4122.62, 170.12, 4127.33), 0);
        assertEquals(List.of(), p.move(new Point(4122.62, 169.72, 4127.30), 50),
                "the same move against the finish pad's side, which a 0.02 band finished: nothing");
        assertFalse(p.finished(), "not finished: the run never landed on the finish pad");
    }

    @Test
    void standingOnTheFrontEdgeOfThePadBeforeDoesNotCount() {
        // another Easy layout: the checkpoint one step down past a 1-block gap from the pad before (x 4110-4112, top 171)
        Course.Mark cp = new Course.Mark(4115.5, 170, 4102.5, 2.6);
        Course c = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY, "games",
                new Course.Spot(4105.5, 171, 4102.5, 270, 0), List.of(cp), EASY_FINISH, 166.0, null, true, false, 1,
                FRESH);
        Point edge = new Point(4113.3, 171, 4102.5);
        Progress p = new Progress(c, edge, 0);
        assertEquals(List.of(), p.move(edge, 50),
                "feet 0.3 over the pad before's edge are 2.42 from the mark, which 0.37.1 counted: nothing now");
        assertEquals(List.of(), p.move(new Point(4113.3, 171, 4103.8), 100), "nor along that edge");
        assertEquals(-1, p.lastCheckpoint(), "not reached before the jump");
    }

    @Test
    void underThePadNothingCounts() {
        Progress p = new Progress(EASY, new Point(4139.5, 169.0, 4154.5), 0);
        assertEquals(List.of(), p.move(new Point(4139.5, 160, 4154.5), 50), "straight down under the pad's middle");
        assertEquals(List.of(), p.move(new Point(4141.0, 169.9, 4154.5), 100), "nor up to a tenth under its top");
        assertEquals(-1, p.lastCheckpoint(), "not reached");
    }

    @Test
    void landingOnACornerCounts() {
        Progress a = new Progress(EASY, new Point(4142.0, 171.2, 4157.0), 0);
        assertEquals(1, a.move(new Point(4141.3, 170, 4156.3), 50).size(),
                "0.3 past both edges of the far corner, 2.55 from the mark: counted");
        Progress b = new Progress(EASY, new Point(4137.0, 171.2, 4152.0), 0);
        assertEquals(1, b.move(new Point(4137.7, 170, 4152.7), 50).size(), "and the near corner");
        assertEquals(0, b.lastCheckpoint(), "checkpoint 1");
    }

    @Test
    void aBedrockStandOnAnEdgeOrCornerCounts() {
        // a Bedrock position is built from the client's floats: a few float steps past where Java would put it
        for (Point stand : List.of(new Point(4141.3004, 169.99999, 4154.5),
                new Point(4141.3004, 169.99999, 4156.3004))) {
            Progress p = new Progress(EASY, stand, 0);
            assertEquals(1, p.move(stand, 50).size(), "standing still at " + stand + ": counted");
        }
    }

    @Test
    void aBedrockLandingOnThePadsVeryEdgeCounts() {
        // a Bedrock position: a float step past where Java would put the feet, and a hair under the top
        Point from = new Point(4142.0, 171.0, 4154.5);
        Point edge = new Point(4141.3004, 169.99999, 4154.5);
        Progress p = new Progress(EASY, from, 0);
        long at = Math.round(1000 * Geometry.firstHitAbove(from, edge, EASY_CP.center(), 2.6, 0,
                170 - Progress.FEET_BELOW));
        assertEquals(List.of(new Progress.Reached(0, false, at)), p.move(edge, 1000),
                "where the move ended stands on the pad within the hair: counted, at the time the sphere gives");
    }

    @Test
    void aHairPastTheEdgeCountsOnlyStandingAtTheTopsHeight() {
        Progress p = new Progress(EASY, new Point(4141.6, 170.6, 4154.5), 0);
        assertEquals(List.of(), p.move(new Point(4141.3004, 170.3, 4154.5), 50),
                "a hair past the edge in the air beside the pad (a jump falling past it): nothing");
        assertEquals(List.of(), p.move(new Point(4141.3004, 169.998, 4154.5), 100), "nor two hairs under its top");
        assertEquals(-1, p.lastCheckpoint(), "not reached");
    }

    @Test
    void aJumpThatPassesOverThePadCounts() {
        Point from = new Point(4142.0, 171.0, 4154.5);
        Point to = new Point(4137.5, 171.2, 4154.5);
        Progress p = new Progress(EASY, from, 0);
        List<Progress.Reached> r = p.move(to, 1000);
        long at = Math.round(1000 * Geometry.firstHitAbove(from, to, EASY_CP.center(), 2.6, 0, 170 - Progress.FEET_BELOW));
        assertEquals(List.of(new Progress.Reached(0, false, at)), r,
                "over the pad and on: counted, at the time the sphere gives (" + at + " ns)");
    }

    @Test
    void theFinishCountsOnlyOverItsPad() {
        Progress p = new Progress(EASY.withCheckpoints(List.of()), new Point(4122.62, 170.797, 4127.583), 0);
        assertEquals(List.of(), p.move(new Point(4122.62, 170.495, 4127.455), 100),
                "0.37.1 finished here (2.999 from the mark), with the box 0.155 clear of the pad: not now");
        assertFalse(p.finished(), "not finished");
        assertEquals(List.of(new Progress.Reached(0, true, 100)), p.move(new Point(4122.62, 170.121, 4127.2), 200),
                "over the pad's edge: finished, timed from the start of that move (the sphere was entered before it)");
        assertTrue(p.finished(), "finished");
    }

    @Test
    void whenTheSphereWasEnteredAMoveEarlierTheTimeIsTheStartOfTheMoveOverThePad() {
        Progress p = new Progress(EASY, new Point(4142.6, 171.0, 4154.5), 0);
        assertEquals(List.of(), p.move(new Point(4141.9, 170.6, 4154.5), 50), "in the sphere, beside the pad");
        assertEquals(List.of(new Progress.Reached(0, false, 50)), p.move(new Point(4141.0, 170.0, 4154.5), 100),
                "onto the pad: counted at the start of the move that got over it");
    }

    @Test
    void aHandBuiltOrKeptCourseKeepsTheWholeSphere() {
        Progress p = new Progress(EASY.withGen(null), TAKE_OFF, 0);
        List<Progress.Reached> r = feed(p, FELL_SHORT);
        assertEquals(1, r.size(), "no gen tag (built by hand, or kept): the same jump beside the pad counts, as before");
        assertEquals(0, p.lastCheckpoint(), "checkpoint 1");
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
