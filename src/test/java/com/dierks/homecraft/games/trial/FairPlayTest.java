package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fair-play rules, with no server.
 *
 * <p>Pinned here: a clean run counts; a run seen flying (or with a changed mode or an effect)
 * doesn't, and says why; a run quicker than the course's shortest time doesn't, but exactly that
 * time does; a leg covered faster than the kind allows doesn't — counting only the distance that
 * must be travelled between the two spheres, max(0, d − r_prev − r_cur), so overlapping spheres
 * never trip it — and the first leg is measured from the start with its one-block allowance; a
 * run on a course whose layout changed records nothing; a test run is never counted but says
 * whether it would have been; the parkour fall height sits fall_depth under the lower of the last
 * and next checkpoint, a course's own fall_y wins for any kind, and elytra and boat courses have
 * no default; and a landing is fine near the start, in a checkpoint or at the finish only.
 */
class FairPlayTest {

    private static final Course PARKOUR = new Course("steps", TrialKind.PARKOUR, "Steps", Tier.EASY, "games",
            new Course.Spot(0, 64, 0, 0, 0),
            List.of(new Course.Mark(10, 70, 0, 1.5), new Course.Mark(20, 60, 0, 1.5)),
            new Course.Mark(30, 80, 0, 2), null, null, true, false, 1);

    // ---- the verdict --------------------------------------------------------------------------

    @Test
    void aCleanRunCounts() {
        FairPlay.Verdict v = FairPlay.judge(false, null, false, 30_000, 5, -1);
        assertTrue(v.counts(), "nothing wrong: recorded and rewarded");
        assertNull(v.reason(), "no reason to give");
    }

    @Test
    void aRunSeenBreakingARuleDoesNotCountAndSaysWhy() {
        FairPlay.Verdict v = FairPlay.judge(false, FairPlay.FLYING, false, 30_000, 5, -1);
        assertEquals(FairPlay.Kind.VOID, v.kind(), "flying voids the run");
        assertEquals(FairPlay.FLYING, v.reason(), "and the player reads why");
    }

    @Test
    void aRunQuickerThanTheShortestTimeDoesNotCountButExactlyItDoes() {
        assertEquals(FairPlay.TOO_QUICK, FairPlay.judge(false, null, false, 4_999, 5, -1).reason(),
                "4.999 s on a 5 s course is too quick");
        assertTrue(FairPlay.judge(false, null, false, 5_000, 5, -1).counts(), "exactly 5 s is believable");
        assertTrue(FairPlay.judge(false, null, false, 10, 0, -1).counts(), "a shortest time of 0 checks nothing");
    }

    @Test
    void aLegTooFastForTheKindDoesNotCount() {
        assertEquals(FairPlay.TOO_FAST, FairPlay.judge(false, null, false, 30_000, 5, 1).reason(),
                "a leg flagged too fast voids the run");
    }

    @Test
    void aRunOnAChangedLayoutRecordsNothing() {
        FairPlay.Verdict v = FairPlay.judge(false, FairPlay.FLYING, true, 30_000, 5, -1);
        assertEquals(FairPlay.Kind.STALE, v.kind(), "the course changed: its time belongs to a layout that's gone");
        assertFalse(v.counts(), "never recorded");
        assertEquals(FairPlay.CHANGED, v.reason(), "and it says so");
    }

    @Test
    void aTestRunIsNeverCountedButSaysWhetherItWouldHaveBeen() {
        FairPlay.Verdict fine = FairPlay.judge(true, null, false, 30_000, 5, -1);
        assertEquals(FairPlay.Kind.TEST, fine.kind(), "a test is a test");
        assertFalse(fine.counts(), "a test never records");
        assertNull(fine.reason(), "it would have counted");
        assertEquals(FairPlay.TOO_QUICK, FairPlay.judge(true, null, false, 1_000, 5, -1).reason(),
                "and a test too quick says it wouldn't have");
        assertEquals(FairPlay.Kind.TEST, FairPlay.judge(true, null, true, 30_000, 5, -1).kind(),
                "a test on a changed layout is still just a test");
    }

    // ---- speed ----------------------------------------------------------------------------------

    @Test
    void theSpeedCheckCountsOnlyTheDistanceThatMustBeTravelled() {
        assertFalse(FairPlay.tooFast(20, 3, 3, 1, 14), "20 blocks between two radius-3 spheres is 14 to cover: 14 b/s is fine");
        assertTrue(FairPlay.tooFast(20, 3, 3, 0.99, 14), "the same 14 blocks a hair quicker is too fast");
        assertFalse(FairPlay.tooFast(5, 3, 3, 0, 14), "overlapping spheres: nothing to travel, never too fast");
        assertTrue(FairPlay.tooFast(10, 1, 1, 0, 80), "blocks to cover in no time at all is too fast");
    }

    @Test
    void aRunsLegsAreEachCheckedAgainstTheKindsTopSpeed() {
        long s = 1_000_000_000L;
        long[] fine = {2 * s, 4 * s, 7 * s};
        assertEquals(-1, FairPlay.tooFast(PARKOUR, 0, fine, 3), "about 5 b/s everywhere: believable");
        long[] teleport = {2 * s, 2 * s + 100_000_000L, 7 * s};
        assertEquals(1, FairPlay.tooFast(PARKOUR, 0, teleport, 3),
                "checkpoint 1 to 2 (about 11 blocks to cover) in a tenth of a second is flagged");
        long[] quickStart = {s / 2, 4 * s, 7 * s};
        assertEquals(0, FairPlay.tooFast(PARKOUR, 0, quickStart, 3),
                "the first leg is from the start: 11.7 - 1 - 1.5 = 9.2 blocks in half a second is too fast");
        assertEquals(-1, FairPlay.tooFast(PARKOUR, 0, teleport, 1), "only the legs reached are checked");
    }

    @Test
    void eachKindHasItsOwnTopSpeed() {
        assertEquals(14, TrialKind.PARKOUR.maxSpeed(), "parkour: 14 blocks a second");
        assertEquals(80, TrialKind.ELYTRA.maxSpeed(), "elytra: 80 blocks a second");
        assertEquals(75, TrialKind.BOAT.maxSpeed(), "boat: 75 blocks a second");
    }

    // ---- falls and landings -------------------------------------------------------------------

    @Test
    void aParkourFallIsCountedUnderTheLowerOfTheLastAndNextCheckpoint() {
        assertEquals(58, FairPlay.fallY(PARKOUR, -1, 6), 1e-9, "from the start (64) to checkpoint 1 (70): 64 - 6");
        assertEquals(54, FairPlay.fallY(PARKOUR, 0, 6), 1e-9, "checkpoint 1 (70) to 2 (60): 60 - 6");
        assertEquals(54, FairPlay.fallY(PARKOUR, 1, 6), 1e-9, "checkpoint 2 (60) to the finish (80): 60 - 6");
        Course flat = PARKOUR.withCheckpoints(List.of());
        assertEquals(58, FairPlay.fallY(flat, -1, 6), 1e-9, "no checkpoints: the start (64) and the finish (80)");
    }

    @Test
    void aCoursesOwnFallHeightWinsForAnyKind() {
        assertEquals(40, FairPlay.fallY(PARKOUR.withFallY(40.0), 1, 6), 1e-9, "parkour with fall_y 40");
        Course boat = new Course("river", TrialKind.BOAT, "River", Tier.EASY, "games", PARKOUR.start(), List.of(),
                PARKOUR.finish(), 50.0, null, true, false, 1);
        assertEquals(50, FairPlay.fallY(boat, -1, 6), 1e-9, "a boat course with its own fall_y uses it");
        assertTrue(Double.isNaN(FairPlay.fallY(boat.withFallY(null), -1, 6)),
                "a boat course has no default fall height: leaving the boat is its reset");
        Course elytra = new Course("sky", TrialKind.ELYTRA, "Sky", Tier.EASY, "games", PARKOUR.start(), List.of(),
                PARKOUR.finish(), null, null, true, false, 1);
        assertTrue(Double.isNaN(FairPlay.fallY(elytra, -1, 6)), "nor does an elytra course: landing is its reset");
    }

    @Test
    void aLandingIsFineOnlyNearTheStartInACheckpointOrAtTheFinish() {
        assertTrue(FairPlay.safeLanding(PARKOUR, new Point(1, 64, 1)), "near the start");
        assertTrue(FairPlay.safeLanding(PARKOUR, new Point(10, 71, 0)), "inside checkpoint 1");
        assertTrue(FairPlay.safeLanding(PARKOUR, new Point(31, 80, 0)), "inside the finish");
        assertFalse(FairPlay.safeLanding(PARKOUR, new Point(15, 60, 0)), "in the middle of nowhere");
    }

    @Test
    void theShortestTimeIsTheCoursesOwnOrTheServers() {
        assertEquals(5, PARKOUR.minSecondsOr(5), "no course value: the server's min_seconds");
        assertEquals(40, PARKOUR.withMinSeconds(40).minSecondsOr(5), "the course's own wins");
        assertTrue(FairPlay.tooQuick(39_999, PARKOUR.withMinSeconds(40).minSecondsOr(5)), "and is what's checked");
    }
}
