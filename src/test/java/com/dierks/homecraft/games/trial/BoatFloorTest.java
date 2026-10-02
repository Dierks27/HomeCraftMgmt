package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;
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
 * leg speed is the fastest leg from sphere entry to sphere entry over its time (audit M01: a big sphere's
 * radius doesn't overstate it), legs not reached and legs a server stall overlaps are not counted, and a run
 * with no leg reads 0.
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
    void theTopLegSpeedIsTheFastestLegFromSphereEntryToSphereEntryOverItsTime() {
        Course c = MountainRunsV2.straight(3, MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000));
        long s = 1_000_000_000L;
        // checkpoints of radius 4 every 10 blocks, the finish (radius 5) 10 on: legs of 6 (from the start point to
        // the first sphere's edge), 10, 10 and 9 blocks, in 1 s, 0.25 s, 0.5 s and 2 s
        long[] times = {2 * s, 2 * s + s / 4, 2 * s + 3 * s / 4, 4 * s + 3 * s / 4};
        assertEquals(40.0, FairPlay.topLegSpeed(c, s, times, 4), 1e-9, "10 blocks edge to edge in 0.25 s: 40 b/s");
        assertEquals(6.0, FairPlay.topLegSpeed(c, s, times, 1), 1e-9,
                "only the legs reached count: the start point to the first sphere's edge, 6 in 1 s");
        assertEquals(0.0, FairPlay.topLegSpeed(c, s, times, 0), "no leg reached: 0");
        assertEquals(0.0, FairPlay.topLegSpeed(c, s, new long[]{s, s, s, s}, 4), "no leg that took time: 0");
        assertEquals(" Top leg speed 40 b/s.", TimeTrials.topLegLine(39.6), "the test summary's readout, rounded");
    }

    @Test
    void aLegAServerStallOverlapsIsNotCounted() {
        Course c = MountainRunsV2.straight(3, MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000));
        long s = 1_000_000_000L;
        // the third leg comes in a burst after a stall: 10 blocks "in" 1 ms
        long[] times = {2 * s, 2 * s + s / 2, 2 * s + s / 2 + s / 1000, 4 * s};
        assertEquals(10_000.0, FairPlay.topLegSpeed(c, s, times, 4), 1e-6,
                "fixture: with no stall seen, the burst reads 10,000 b/s");
        // a stall from just after checkpoint 2 was reached to half way through the burst (it doesn't touch leg 2)
        List<FairPlay.Stall> stalls = List.of(new FairPlay.Stall(2 * s + s / 2 + 100, 2 * s + s / 2 + s / 2000));
        assertEquals(20.0, FairPlay.topLegSpeed(c, s, times, 4, stalls), 1e-9,
                "the leg the stall overlaps (as the speed check skips it) isn't counted: the next fastest, 10 in 0.5 s");
        assertEquals(FairPlay.topLegSpeed(c, s, times, 4), FairPlay.topLegSpeed(c, s, times, 4, List.of()), 1e-12,
                "no stalls: the same as before");
    }

    @Test
    void aLegIntoABigSphereIsMeasuredToItsEdgeNotItsMiddle() {
        GenTag tag = MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000);
        Course.Spot start = new Course.Spot(6100.5, MountainRunsV2.TOP, 2900.5, -90f, 0f);
        List<Course.Mark> cps = List.of(new Course.Mark(6110.5, MountainRunsV2.TOP, 2900.5, 4),
                new Course.Mark(6150.5, MountainRunsV2.TOP, 2900.5, 8.5));
        Course c = new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "games", start, cps,
                new Course.Mark(6200.5, MountainRunsV2.TOP, 2900.5, 5), null, 10, true, false, 1, tag);
        long s = 1_000_000_000L;
        long[] times = {2 * s, 3 * s, 5 * s};
        // a 4 to an 8.5 checkpoint 40 apart: the boat enters the first 4 before its middle and the second 8.5
        // before its middle, so it rides 40 + 4 - 8.5 = 35.5 blocks in that second, not 40 (a Slalom's wide gates)
        assertEquals(35.5, FairPlay.topLegSpeed(c, s, times, 2), 1e-9, "35.5 blocks in 1 s, not 40");
        assertTrue(FairPlay.topLegSpeed(c, s, times, 3) <= 40, "the R1 check (40 b/s or less) isn't failed by a radius");
    }
}
