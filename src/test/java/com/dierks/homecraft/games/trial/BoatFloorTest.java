package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mountain Run v2's per-leg boat floor and the test run's top leg speed (MOUNTAIN-V2-SPEC §12, §14 R1), pure.
 *
 * <p>Pinned here: on a v2 run a boat goes back once it is 4 under the lower of its leg's two marks (the start
 * before the first checkpoint, the finish after the last), so an escape is caught within a few blocks instead
 * of ~70, and a run on the track (which never goes under its next mark: one drop at most per leg) never is;
 * the floor is never under the course's own fall height; the algo-3 run, a hand-built boat track and any
 * course without a fall height keep exactly what they had (their fall height, or no floor at all). The top
 * leg speed is the fastest straight-line leg over its time, legs not reached are not counted, and a run with
 * no leg reads 0.
 */
class BoatFloorTest {

    @Test
    void aV2LegsFloorIs4UnderTheLowerOfItsTwoMarks() {
        Course c = MountainRunsV2.road();
        List<Course.Mark> cps = c.checkpoints();
        assertEquals(Math.min(c.start().y(), cps.get(0).y()) - 4, FairPlay.boatFloor(c, -1),
                "before the first checkpoint: the start and checkpoint 1");
        assertEquals(Math.min(cps.get(1).y(), cps.get(2).y()) - 4, FairPlay.boatFloor(c, 1), "a flat leg");
        assertEquals(cps.get(3).y() - 4, FairPlay.boatFloor(c, 2), "a Hop's leg: under the lower, landing, mark");
        assertEquals(cps.get(2).y() - 1, cps.get(3).y(), "fixture: checkpoint 4 is a Hop lower");
        int last = cps.size() - 1;
        assertEquals(c.finish().y() - 4, FairPlay.boatFloor(c, last), "after the last checkpoint: the finish");
        assertEquals(cps.get(last).y() - 2, c.finish().y(), "fixture: the Final Drop is 2");
    }

    @Test
    void aBoatOnTheTrackIsNeverUnderItsLegsFloor() {
        Course c = MountainRunsV2.road();
        List<Course.Mark> targets = c.targets();
        double prev = c.start().y();
        for (int last = -1; last < c.checkpoints().size(); last++) {
            double floor = FairPlay.boatFloor(c, last);
            double next = targets.get(last + 1).y();
            double lowestIce = Math.min(prev, next); // one drop at most per leg: the ice never goes under the next mark
            assertTrue(lowestIce - floor >= 4 - 1e-9, "leg after " + last + ": a boat on the ice is 4 over its floor");
            assertTrue(lowestIce - floor <= 4 + 1e-9, "and one 4 under it has left the track: " + last);
            prev = next;
        }
    }

    @Test
    void theV2FloorIsNeverUnderTheCoursesOwnFallHeight() {
        Course c = MountainRunsV2.road();
        Course high = c.withFallY(c.start().y() - 1);
        assertEquals(c.start().y() - 1, FairPlay.boatFloor(high, -1), "an own fall height above the leg's floor wins");
        Course none = c.withFallY(null);
        assertEquals(Math.min(c.start().y(), c.checkpoints().get(0).y()) - 4, FairPlay.boatFloor(none, -1),
                "with none, the leg's floor alone");
    }

    @Test
    void everyOtherBoatCourseKeepsItsFallHeightOrNone() {
        Course v3 = MountainRuns.medium();
        assertEquals(v3.fallY() == null ? Double.NaN : v3.fallY(), FairPlay.boatFloor(v3, 3),
                "the algo-3 run: its own fall height, as before");
        Course hand = MountainRunsV2.of(null);
        assertEquals((double) hand.fallY(), FairPlay.boatFloor(hand, 10), "a hand-built track: its fall height");
        assertTrue(Double.isNaN(FairPlay.boatFloor(hand.withFallY(null), 10)), "and none when it has none");
        assertTrue(Double.isNaN(FairPlay.boatFloor(v3.withFallY(null), 0)), "the spiral without one: none");
    }

    @Test
    void theTopLegSpeedIsTheFastestStraightLineLegOverItsTime() {
        Course c = MountainRunsV2.straight(3, MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000));
        long s = 1_000_000_000L;
        // legs of 10 blocks each: 1 s, 0.25 s, 0.5 s, then the finish leg in 2 s
        long[] times = {2 * s, 2 * s + s / 4, 2 * s + 3 * s / 4, 4 * s + 3 * s / 4};
        assertEquals(40.0, FairPlay.topLegSpeed(c, s, times, 4), 1e-9, "10 blocks in 0.25 s: 40 b/s");
        assertEquals(10.0, FairPlay.topLegSpeed(c, s, times, 1), 1e-9, "only the legs reached count");
        assertEquals(0.0, FairPlay.topLegSpeed(c, s, times, 0), "no leg reached: 0");
        assertEquals(0.0, FairPlay.topLegSpeed(c, s, new long[]{s, s, s, s}, 4), "no leg that took time: 0");
        assertEquals(" Top leg speed 40 b/s.", TimeTrials.topLegLine(39.6), "the test summary's readout, rounded");
    }
}
