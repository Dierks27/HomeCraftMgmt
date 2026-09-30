package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
 *
 * <p>Also pinned (review fixes): a gap between trial ticks over 250 ms is a server stall, and a
 * leg its window touches isn't speed-checked — the reviewer's honest 7.2 s run through a 1.7 s
 * stall counts — while a leg away from any stall still is, with a tick of slack; a changed walk
 * speed, a movement attribute off a player's own base or any modifier but vanilla sprinting voids
 * a run; and a course deleted and made again at the same layout number is still a changed course.
 *
 * <p>And Daily Courses' "still standing" rule (GEN-SPEC §3.4): a finish on the previous layout of
 * a daily course counts while that layout stands, even though the row has moved on to the next
 * one; once its half starts being cleared the finish is stale; with nothing vouching for it (the
 * daily game off) the old rule decides; and a hand-built course keeps the old rule exactly.
 *
 * <p>And the Dropper (EVENTS-DROPPER-SPEC §B.1.7): the legs that end at a ledge are the game's own
 * hops and are never speed-checked, while its falls still are and every other kind is checked as
 * before; its shortest time is 90% of its walk-off falls; its fall height is its own; and a drop in
 * progress at the weekly flip counts while its half stands.
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

    // ---- server stalls ----------------------------------------------------------------------------

    private static final long TICK = 50_000_000L;
    private static final long SECOND = 1_000_000_000L;

    /** The reviewer's probe: checkpoints every 6 blocks (radius 1.5), the finish at 42. */
    private static final Course PROBE = new Course("probe", TrialKind.PARKOUR, "Probe", Tier.EASY, "games",
            new Course.Spot(0, 64, 0, 0, 0),
            List.of(new Course.Mark(6, 64, 0, 1.5), new Course.Mark(12, 64, 0, 1.5), new Course.Mark(18, 64, 0, 1.5)),
            new Course.Mark(42, 64, 0, 1.5), null, null, true, false, 3);

    /** What the probe run left: its progress, the stalls its ticks saw, and when it started and finished. */
    private record LagRun(Progress progress, List<FairPlay.Stall> stalls, long start, long finish) {
    }

    /**
     * The reviewer's LagProbe: a player sprinting at 5.6 b/s (far under parkour's 14) along
     * {@link #PROBE}, and the server standing still from 1.5 s to 3.2 s into the run. The moves the
     * client sent meanwhile queue up and are handled 20 µs apart once it's back; the trial tick
     * that sees the stall runs just before them (the tightest case for the stall's window).
     */
    private static LagRun lagRun() {
        long t0 = SECOND;
        long stallFrom = t0 + 1_500_000_000L;
        long stallTo = t0 + 3_200_000_000L;
        Progress p = new Progress(PROBE, new Point(0, 64, 0), t0);
        List<FairPlay.Stall> stalls = new ArrayList<>();
        long lastTick = t0;
        long processed = stallTo;
        for (int i = 1; i <= 400; i++) {
            long real = t0 + i * TICK;
            long seen;
            long tick = -1;
            if (real > stallFrom && real <= stallTo) {
                if (processed == stallTo) {
                    tick = stallTo; // the server is back: its first tick, then the queue
                }
                processed += 20_000;
                seen = processed;
            } else {
                tick = real;
                seen = real;
            }
            if (tick >= 0) {
                FairPlay.Stall st = FairPlay.stall(lastTick, tick);
                if (st != null) {
                    stalls.add(st);
                }
                lastTick = tick;
            }
            for (Progress.Reached r : p.move(new Point(5.6 * (i * TICK) / 1e9, 64, 0), seen)) {
                if (r.finish()) {
                    return new LagRun(p, stalls, t0, r.nanos());
                }
            }
        }
        throw new AssertionError("the probe run never finished");
    }

    @Test
    void aGapBetweenTicksOverAQuarterSecondIsAStallThatRunsOnPastIt() {
        long t = 10 * SECOND;
        assertNull(FairPlay.stall(0, t), "no tick before this one: nothing to compare");
        assertNull(FairPlay.stall(t, t + TICK), "an ordinary tick");
        assertNull(FairPlay.stall(t, t + FairPlay.STALL_NANOS), "exactly a quarter second is still no stall");
        assertEquals(new FairPlay.Stall(t, t + 1_700_000_000L + FairPlay.STALL_NANOS),
                FairPlay.stall(t, t + 1_700_000_000L),
                "a 1.7 s stall, running on past the tick after it, when the queued moves are handled");
        FairPlay.Stall st = new FairPlay.Stall(2 * SECOND, 3 * SECOND);
        assertTrue(st.overlaps(SECOND, 2 * SECOND), "a leg ending as it starts touches it");
        assertTrue(st.overlaps(2_500_000_000L, 4 * SECOND), "a leg starting inside it touches it");
        assertFalse(st.overlaps(3 * SECOND + 1, 4 * SECOND), "a leg after it doesn't");
    }

    @Test
    void anHonestRunThroughAServerStallCounts() {
        LagRun run = lagRun();
        Progress p = run.progress();
        assertEquals(1, run.stalls().size(), "the ticks saw the one stall");
        assertTrue(FairPlay.tooFast(PROBE, run.start(), p.times(), p.reachedTargets()) >= 0,
                "without the stall's window a leg looks impossibly fast: the false void the review found");
        int fast = FairPlay.tooFast(PROBE, run.start(), p.times(), p.reachedTargets(), run.stalls());
        assertEquals(-1, fast, "the legs the stall touched aren't checked; the rest are believable");
        long ms = (run.finish() - run.start()) / 1_000_000L;
        assertTrue(ms >= 7_200 && ms < 7_300, "an honest 7.2 s run, lag only ever lengthens a time: " + ms);
        assertTrue(FairPlay.judge(false, null, false, ms, 5, fast).counts(), "so it counts");
    }

    @Test
    void aLegAwayFromAnyStallIsStillChecked() {
        long[] teleport = {2 * SECOND, 2 * SECOND + 100_000_000L, 7 * SECOND};
        List<FairPlay.Stall> later = List.of(new FairPlay.Stall(5 * SECOND, 6 * SECOND));
        assertEquals(1, FairPlay.tooFast(PARKOUR, 0, teleport, 3, later),
                "a stall on the last leg doesn't excuse a teleport on the second");
        List<FairPlay.Stall> there = List.of(new FairPlay.Stall(2 * SECOND + 50_000_000L, 2 * SECOND + 400_000_000L));
        assertEquals(-1, FairPlay.tooFast(PARKOUR, 0, teleport, 3, there),
                "a leg a stall touched isn't checked: its times are when the server caught up");
    }

    @Test
    void everyLegHasATickOfSlack() {
        Course straight = new Course("dash", TrialKind.PARKOUR, "Dash", Tier.EASY, "games",
                new Course.Spot(0, 64, 0, 0, 0), List.of(), new Course.Mark(16, 64, 0, 1), null, null, true, false, 1);
        assertEquals(-1, FairPlay.tooFast(straight, 0, new long[] {980_000_000L}, 1, List.of()),
                "14 blocks to cover in 0.98 s is 14.3 b/s by the clock, but within a tick of slack of 14");
        assertEquals(0, FairPlay.tooFast(straight, 0, new long[] {900_000_000L}, 1, List.of()),
                "in 0.9 s it is too fast even with the slack");
    }

    // ---- walk speed and movement attributes ---------------------------------------------------

    private static List<FairPlay.Stat> own(String key, double base, String... modifiers) {
        List<FairPlay.Stat> out = new ArrayList<>();
        for (var e : FairPlay.PLAYER_BASES.entrySet()) {
            out.add(e.getKey().equals(key) ? new FairPlay.Stat(key, base, List.of(modifiers))
                    : new FairPlay.Stat(e.getKey(), e.getValue(), List.of()));
        }
        return out;
    }

    @Test
    void aPlayersOwnMovementIsFineSprintingIncluded() {
        assertNull(FairPlay.movement(0.2f, own(FairPlay.MOVEMENT_SPEED, 0.1f)),
                "the default walk speed and a movement speed of 0.1 (stored as a float)");
        assertNull(FairPlay.movement(0.2f, own(FairPlay.MOVEMENT_SPEED, 0.1, FairPlay.SPRINTING)),
                "vanilla sprinting is a modifier an honest run has");
        assertNull(FairPlay.movement(0.2f, own(FairPlay.MOVEMENT_SPEED, 0.1, FairPlay.POWDER_SNOW)),
                "so is powder snow's slow-down: a course may be built with powder snow");
        assertNull(FairPlay.movement(0.2f, List.of(new FairPlay.Stat("minecraft:scale", 2, List.of("x:y")))),
                "an attribute the run doesn't watch is none of its business");
    }

    @Test
    void aChangedWalkSpeedOrMovementAttributeVoidsARun() {
        assertEquals(FairPlay.WALK_SPEED, FairPlay.movement(0.4f, own(FairPlay.MOVEMENT_SPEED, 0.1)),
                "/speed walk: the walk speed changed");
        assertEquals(FairPlay.WALK_SPEED, FairPlay.movement(Float.NaN, own(FairPlay.MOVEMENT_SPEED, 0.1)),
                "a walk speed that isn't a number is not the default");
        assertEquals(FairPlay.MOVEMENT, FairPlay.movement(0.2f, own("minecraft:jump_strength", 0.6)),
                "a jump strength base off the player's own 0.42");
        assertEquals(FairPlay.MOVEMENT, FairPlay.movement(0.2f, own("minecraft:gravity", 0.08, "someplugin:floaty")),
                "another plugin's gravity modifier");
        assertEquals(FairPlay.MOVEMENT, FairPlay.movement(0.2f,
                own(FairPlay.MOVEMENT_SPEED, 0.1, FairPlay.SPRINTING, "someplugin:boots")),
                "a speed modifier next to sprinting");
        assertEquals(FairPlay.MOVEMENT, FairPlay.movement(0.2f, own("minecraft:step_height", 0.6, FairPlay.SPRINTING)),
                "sprinting belongs on the movement speed only");
        assertEquals(FairPlay.MOVEMENT, FairPlay.movement(0.2f, own("minecraft:safe_fall_distance", 10)),
                "a longer safe fall");
    }

    // ---- a changed course ------------------------------------------------------------------------

    @Test
    void aCourseDeletedAndMadeAgainAtTheSameLayoutNumberIsStillChanged() {
        int layout = PARKOUR.layoutHash();
        assertFalse(FairPlay.stale(PARKOUR.rev(), layout, PARKOUR), "the same course: the run counts");
        assertFalse(FairPlay.stale(PARKOUR.rev(), layout, PARKOUR.withName("Big Steps").withTier(Tier.HARD)
                .withPinned(true).withMinSeconds(9)), "a new name, tier, pin or shortest time isn't a new layout");
        assertTrue(FairPlay.stale(PARKOUR.rev(), layout, null), "deleted");
        assertTrue(FairPlay.stale(PARKOUR.rev(), layout, PARKOUR.withRev(2)), "a layout edit bumps the rev");
        Course remade = PARKOUR.withFinish(new Course.Mark(40, 80, 0, 2));
        assertEquals(PARKOUR.rev(), remade.rev(), "made again, it came round to the same layout number");
        assertTrue(FairPlay.stale(PARKOUR.rev(), layout, remade), "but it is a different layout: nothing recorded");
    }

    // ---- the still-standing rule ------------------------------------------------------------------

    private static GenTag layout(long day, char half, String hash) {
        return new GenTag("fresh_parkour_easy", "parkour", 1, day, 0, 7L, half, hash, 22_500, 45_000, 70_000,
                List.of(), List.of(), 1L);
    }

    /** Yesterday's Easy Parkour in half A, as a run started on it saw it. */
    private static final Course YESTERDAY = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour",
            Tier.EASY, "games", new Course.Spot(4100.5, 170, 4100.5, 0, 0),
            List.of(new Course.Mark(4110.5, 170, 4100.5, 2.2)), new Course.Mark(4120.5, 171, 4100.5, 3.0), 167.0, 17,
            true, false, 11, layout(20_724, 'A', "aaaaaaaaaaaa"));
    /** Today's, flipped in while the run was going: half B, the next rev. */
    private static final Course TODAY = new Course("fresh_parkour_easy", TrialKind.PARKOUR, "Easy Parkour",
            Tier.EASY, "games", new Course.Spot(4196.5, 180, 4100.5, 0, 0),
            List.of(new Course.Mark(4206.5, 180, 4100.5, 2.2)), new Course.Mark(4216.5, 181, 4100.5, 3.0), 177.0, 17,
            true, false, 12, layout(20_725, 'B', "bbbbbbbbbbbb"));

    /** What the engine vouches for: the live layout and, until its half is being cleared, the one before. */
    private static Predicate<GenTag> standing(boolean previousStands) {
        return t -> t != null && (t.sameLayout(TODAY.gen()) || (previousStands && t.sameLayout(YESTERDAY.gen())));
    }

    @Test
    void aFinishOnAStillStandingPreviousLayoutCounts() {
        int hash = YESTERDAY.layoutHash();
        assertTrue(FairPlay.stale(YESTERDAY.rev(), hash, TODAY), "by the old rule alone the row has moved on");
        assertFalse(FairPlay.stale(YESTERDAY, hash, TODAY, standing(true)),
                "but yesterday's blocks still stand, so the run on them counts");
        assertFalse(FairPlay.stale(TODAY, TODAY.layoutHash(), TODAY, standing(true)), "a run on the live layout counts");
    }

    @Test
    void onceClearingTheOldHalfHasStartedAFinishOnItIsStale() {
        assertTrue(FairPlay.stale(YESTERDAY, YESTERDAY.layoutHash(), TODAY, standing(false)),
                "CLEAR_OLD started on its half: the layout no longer stands, the finish records nothing");
        assertTrue(FairPlay.stale(YESTERDAY, YESTERDAY.layoutHash(), null, standing(false)),
                "deleted and not standing: stale");
        assertFalse(FairPlay.stale(YESTERDAY, YESTERDAY.layoutHash(), null, standing(true)),
                "the row gone but the blocks still standing: it counts");
    }

    @Test
    void withNothingVouchingTheOldRuleDecides() {
        Predicate<GenTag> none = t -> false;
        assertFalse(FairPlay.stale(TODAY, TODAY.layoutHash(), TODAY, none),
                "the daily game off mid-run: an unchanged course still counts");
        assertTrue(FairPlay.stale(YESTERDAY, YESTERDAY.layoutHash(), TODAY, none),
                "a changed one doesn't");
        assertTrue(FairPlay.stale(YESTERDAY, YESTERDAY.layoutHash(), TODAY, null), "no gate at all: nothing stands");
    }

    @Test
    void aHandBuiltCourseKeepsTheOldRuleWhateverStands() {
        int hash = PARKOUR.layoutHash();
        Predicate<GenTag> everything = t -> true;
        assertTrue(FairPlay.stale(PARKOUR, hash, PARKOUR.withRev(2), everything),
                "a hand-built layout edit is stale even if the gate says everything stands");
        assertFalse(FairPlay.stale(PARKOUR, hash, PARKOUR, everything), "unchanged: counts");
        assertTrue(FairPlay.stale(PARKOUR, hash, null, everything), "deleted: stale");
    }

    // ---- the Dropper (EVENTS-DROPPER-SPEC §B.1.7) ---------------------------------------------------

    /**
     * A clean drop of the hand-made dropper: 1.6 s to pool 1, the hop a quarter second later onto ledge 2
     * (some 30 blocks up and across: the game's own teleport), and so on down.
     */
    private static long[] dropTimes(long start) {
        long s = 1_000_000_000L;
        return new long[]{start + 16 * s / 10, start + 185 * s / 100, start + 37 * s / 10, start + 395 * s / 100,
                start + 6 * s};
    }

    @Test
    void aDroppersOwnHopsAreNeverSpeedCheckedButItsFallsAre() {
        Course drop = DropperCourses.hand();
        long start = 10_000_000_000L;
        long[] times = dropTimes(start);
        assertEquals(-1, FairPlay.tooFast(drop, start, times, 5), "a clean drop counts: its hops are its own teleports");
        Course elytra = new Course("x", TrialKind.ELYTRA, "X", Tier.EASY, "games", DropperCourses.START,
                drop.checkpoints(), drop.finish(), null, null, true, false, 1);
        assertEquals(TrialKind.ELYTRA.maxSpeed(), TrialKind.DROPPER.maxSpeed(), 1e-9, "the same top speed as elytra");
        assertEquals(1, FairPlay.tooFast(elytra, start, times, 5),
                "the same legs on an elytra course: the 30-block hop in a quarter second is too fast");
        assertTrue(FairPlay.ownTeleport(drop, 1) && FairPlay.ownTeleport(drop, 3), "the legs that end at a ledge");
        assertFalse(FairPlay.ownTeleport(drop, 0) || FairPlay.ownTeleport(drop, 2) || FairPlay.ownTeleport(drop, 4),
                "the legs that end at a pool are real falls");
        long[] quick = times.clone();
        quick[2] = times[1] + 100_000_000L; // 40 blocks down in a tenth of a second
        quick[3] = quick[2] + 250_000_000L;
        quick[4] = quick[3] + 2_000_000_000L;
        assertEquals(2, FairPlay.tooFast(drop, start, quick, 5), "a fall faster than gravity is still caught");
        for (TrialKind k : List.of(TrialKind.PARKOUR, TrialKind.ELYTRA, TrialKind.BOAT)) {
            Course other = new Course("x", k, "X", Tier.EASY, "games", DropperCourses.START, drop.checkpoints(),
                    drop.finish(), null, null, true, false, 1);
            for (int i = 0; i < 5; i++) {
                assertFalse(FairPlay.ownTeleport(other, i), k + ": no leg is skipped, as before");
            }
        }
    }

    @Test
    void aDroppersShortestTimeHoldsAndItsFallHeightIsItsOwn() {
        Course real = DropperCourses.planned("EEMMH", 3);
        int min = com.dierks.homecraft.games.gen.dropper.DropRules.minSeconds("EEMMH");
        assertEquals(Integer.valueOf(min), real.minSeconds(), "the planner puts 90% of the walk-off falls on the course");
        assertEquals(min, real.minSecondsOr(5), "and it wins over the server's min_seconds");
        assertEquals(FairPlay.TOO_QUICK, FairPlay.judge(false, null, false, min * 1000L - 1, min, -1).reason(),
                "nobody falls faster than gravity: quicker doesn't count");
        assertTrue(FairPlay.judge(false, null, false, min * 1000L, min, -1).counts(), "exactly it does");
        assertEquals(real.fallY(), FairPlay.fallY(real, 2, 6), 1e-9, "the fall height is always the course's own");
    }

    @Test
    void aDropperRunInProgressAtTheWeeklyFlipStillCounts() {
        GenTag old = new GenTag("fresh_dropper", "dropper", 1, 20_724, 0, 7L, 'A', "aaaaaaaaaaaa", 17_400, 26_100,
                38_280, List.of(), List.of(), 1L);
        GenTag flipped = new GenTag("fresh_dropper", "dropper", 1, 20_731, 0, 8L, 'B', "bbbbbbbbbbbb", 17_400, 26_100,
                38_280, List.of(), List.of(), 1L);
        Course then = DropperCourses.hand(old);
        Course now = new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.MEDIUM, "games",
                new Course.Spot(106.5, 100, 10.5, 0, 30), then.checkpoints(), then.finish(), 44.0, 4, true, false, 2,
                flipped);
        Predicate<GenTag> bothStand = t -> t != null && (t.sameLayout(flipped) || t.sameLayout(old));
        Predicate<GenTag> onlyNew = t -> t != null && t.sameLayout(flipped);
        assertFalse(FairPlay.stale(then, then.layoutHash(), now, bothStand),
                "the run keeps its snapshot: while its half stands, its finish counts on the old board");
        assertTrue(FairPlay.stale(then, then.layoutHash(), now, onlyNew), "once its half is being cleared it doesn't");
    }
}
