package com.dierks.homecraft.games.gen.parkour;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Progress;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily Parkour's planner (GEN-SPEC §4.1, §8.3): ten thousand real daily seeds per tier all make
 * a layout the independent validator passes first time; the same seed always makes the same
 * layout (golden hashes pin three per tier); easy never needs a sprint; the course is what Time
 * Trials runs; and the boot check can make a live layout again from its tag.
 */
class ParkourPlannerTest {

    private static final ParkourPlanner PLANNER = new ParkourPlanner();
    private static final long SECRET = 0x5EC12E7L;
    private static final List<Slots.Def> SLOTS = List.of(Slots.DAILY_PARKOUR_EASY, Slots.DAILY_PARKOUR_MEDIUM,
            Slots.DAILY_PARKOUR_HARD);

    static PlanInput input(Slots.Def slot, char half, long seed, String tier, int fallDepth) {
        return new PlanInput(slot, LegacyBoxes.half(slot, half), half, 20725, 0, seed, tier, fallDepth, 0, null);
    }

    static PlanInput input(Slots.Def slot, long seed) {
        return input(slot, 'A', seed, slot.tierOrMix(), 6);
    }

    // ---- the soak ------------------------------------------------------------------------------

    @Test
    void tenThousandEasySeedsAllPassTheValidator() {
        soak(Slots.DAILY_PARKOUR_EASY);
    }

    @Test
    void tenThousandMediumSeedsAllPassTheValidator() {
        soak(Slots.DAILY_PARKOUR_MEDIUM);
    }

    @Test
    void tenThousandHardSeedsAllPassTheValidator() {
        soak(Slots.DAILY_PARKOUR_HARD);
    }

    /**
     * Ten thousand days of real seeds (the HMAC the engine uses) for one slot: every plan passes
     * the validator without the planner ever having had to throw one away, stays inside its half
     * with the 3-block margin, has its checkpoints every N jumps, and nearly every day is different.
     */
    private static void soak(Slots.Def slot) {
        JumpRules.Level level = JumpRules.Level.of(slot.tierOrMix());
        int n = 10_000;
        Set<String> hashes = ConcurrentHashMap.newKeySet();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger rejected = new AtomicInteger();
        Box half = LegacyBoxes.half(slot, 'A');
        IntStream.range(0, n).parallel().forEach(day -> {
            long seed = GenSeed.seed(SECRET, 20_000 + day, slot.id(), 0);
            try {
                ParkourPlanner.Build b = PLANNER.build(input(slot, seed));
                rejected.addAndGet(b.validatorRejections());
                Plan p = b.plan();
                hashes.add(p.hash());
                List<String> problems = ParkourValidator.problems(p, level.id(), 6);
                if (!problems.isEmpty()) {
                    failures.add("day " + day + ": " + problems);
                }
                for (BlockOp op : p.ops()) {
                    if (op.x() < half.minX() + 3 || op.x() > half.maxX() - 3 || op.z() < half.minZ() + 3
                            || op.z() > half.maxZ() - 3 || !half.contains(op.x(), op.y(), op.z())) {
                        failures.add("day " + day + ": a block at " + op + " is within 3 of the half's side");
                        break;
                    }
                }
                Course c = ((PlannedTrial) p.course()).course();
                List<int[]> jumps = ParkourValidator.jumps(p);
                if (c.checkpoints().size() != level.checkpoints() || jumps.size() != level.jumps()) {
                    failures.add("day " + day + ": " + c.checkpoints().size() + " checkpoints, " + jumps.size()
                            + " jumps");
                }
                if (level == JumpRules.Level.EASY) {
                    for (int[] j : jumps) {
                        if (JumpSim.walkReach(j[0]) - JumpRules.gap(j[1], j[2]) < level.margin() - 1e-9) {
                            failures.add("day " + day + ": an easy jump " + j[0] + "/" + j[1] + "," + j[2]
                                    + " needs more than a walk");
                        }
                    }
                }
            } catch (GenFailed e) {
                failures.add("day " + day + ": " + e.getMessage());
            }
        });
        assertTrue(failures.isEmpty(), slot.id() + ": " + failures.size() + " bad days, first "
                + failures.subList(0, Math.min(5, failures.size())));
        assertEquals(0, rejected.get(), slot.id() + ": the validator never had to turn a plan down");
        assertTrue(hashes.size() >= n * 0.95, slot.id() + ": at least 95% of days differ, got " + hashes.size());
    }

    // ---- the same seed, the same layout -------------------------------------------------------

    @Test
    void theSameSeedAlwaysMakesTheSameLayout() throws GenFailed {
        for (Slots.Def slot : SLOTS) {
            Plan a = PLANNER.plan(input(slot, 42));
            Plan b = PLANNER.plan(input(slot, 42));
            assertEquals(a.hash(), b.hash(), slot.id() + ": the same seed hashes the same");
            assertEquals(a.ops(), b.ops(), slot.id() + ": block for block");
            assertEquals(a.course(), b.course(), slot.id() + ": and the same course");
            assertTrue(!a.hash().equals(PLANNER.plan(input(slot, 43)).hash()), slot.id() + ": another seed differs");
        }
    }

    @Test
    void goldenHashesPinThreeSeedsPerTier() throws GenFailed {
        // A change here means the planner makes different layouts: bump ParkourPlanner.ALGO.
        String[][] golden = {
                {"easy", "e69a705f00d6", "1a6684208d50", "6e70a086523d"},
                {"medium", "00e4dc588604", "c0e8597fffad", "e7179450eab7"},
                {"hard", "cccf12fa9329", "a3f54cb78eac", "c8d61be9a34d"},
        };
        long[] seeds = {1L, 0xC0FFEEL, 0x5EED5EEDL};
        for (int t = 0; t < 3; t++) {
            Slots.Def slot = SLOTS.get(t);
            for (int s = 0; s < 3; s++) {
                assertEquals(golden[t][s + 1], PLANNER.plan(input(slot, seeds[s])).hash(),
                        golden[t][0] + " seed " + Long.toHexString(seeds[s]) + " (if this changed, bump ALGO)");
            }
        }
        assertEquals(2, ParkourPlanner.ALGO, "the version these hashes were pinned at (2: checkpoints cover where"
                + " feet stand, and Easy turns are out of sprint reach)");
    }

    @Test
    void halfBGetsItsOwnLayoutInsideHalfB() throws GenFailed {
        for (Slots.Def slot : SLOTS) {
            Plan p = PLANNER.plan(input(slot, 'B', 7, slot.tierOrMix(), 6));
            Box half = LegacyBoxes.half(slot, 'B');
            assertEquals(half, p.half(), slot.id() + ": the plan is for half B");
            for (BlockOp op : p.ops()) {
                assertTrue(half.contains(op.x(), op.y(), op.z()), slot.id() + ": " + op + " inside half B");
            }
            for (SignText s : p.signs()) {
                assertTrue(half.contains(s.x(), s.y(), s.z()), slot.id() + ": sign inside half B");
            }
            assertTrue(ParkourValidator.problems(p, slot.tierOrMix(), 6).isEmpty(), slot.id() + ": valid in B");
        }
    }

    // ---- the course ----------------------------------------------------------------------------

    @Test
    void theCourseIsATimeTrialThePlayersCanRun() throws GenFailed {
        for (Slots.Def slot : SLOTS) {
            JumpRules.Level level = JumpRules.Level.of(slot.tierOrMix());
            Plan p = PLANNER.plan(input(slot, 99));
            PlannedTrial t = (PlannedTrial) p.course();
            Course c = t.course();
            assertEquals(slot.id(), c.id(), "the course id is the slot");
            assertEquals(slot.id(), p.slot(), "and so is the plan's");
            assertEquals(TrialKind.PARKOUR, c.kind(), "a parkour course");
            assertEquals(slot.name(), c.name(), "named for the slot");
            assertEquals(Tier.of(level.id()), c.tier(), "in its tier");
            assertNull(c.gen(), "the tag is the engine's to add at the flip");
            assertEquals(ParkourPlanner.ALGO, p.algo(), "stamped with the planner's version");
            assertTrue(c.ready() || c.world().isEmpty(), "a start and a finish (the world comes at the flip)");
            assertNotNull(c.start(), "a start");
            assertNotNull(c.finish(), "a finish");
            for (Course.Mark m : c.checkpoints()) {
                assertEquals(ParkourPlanner.CHECKPOINT_RADIUS, m.radius(), 0.0, "checkpoints are 2.6");
            }
            assertEquals(ParkourPlanner.FINISH_RADIUS, c.finish().radius(), 0.0, "the finish is 3.0");
            assertEquals(Math.max(5, (int) Math.floor(0.4 * t.refMs() / 1000.0)), (int) c.minSeconds(),
                    "shortest time: 40% of the reference, at least 5 s");
            assertTrue(t.refMs() > level.jumps() * 400L, "the reference counts 0.4 s a jump and the running");
            double lowest = Double.MAX_VALUE;
            for (BlockOp op : p.ops()) {
                lowest = Math.min(lowest, op.y() + 1);
            }
            if (level == JumpRules.Level.EASY) {
                assertEquals(lowest - 3, c.fallY(), 1e-9, "easy falls back 3 under its lowest pad");
            } else {
                assertNull(c.fallY(), level + " falls back by trials.fall_depth per leg");
            }
            assertTrue(Palette.problems(p.palette()).isEmpty(), "only allowed blocks: " + p.palette());
            assertTrue(p.ops().size() <= ParkourValidator.MAX_OPS, "a small build");
        }
    }

    @Test
    void theSignsSayHowToPlayAndWhereItEnds() throws GenFailed {
        for (Slots.Def slot : SLOTS) {
            Plan p = PLANNER.plan(input(slot, 5));
            assertEquals(2, p.signs().size(), "a start sign and a finish sign");
            assertEquals(GenCopy.parkourStart(slot.tierOrMix()), p.signs().get(0).lines(), "the start sign");
            assertEquals(GenCopy.finish(), p.signs().get(1).lines(), "the finish sign");
            for (SignText s : p.signs()) {
                assertTrue(s.blockData().startsWith(Palette.SIGN + "[rotation="), "a standing oak sign");
            }
        }
    }

    @Test
    void theStartPadIsGreenWithAnArrowAndTheFinishIsGold() throws GenFailed {
        Plan p = PLANNER.plan(input(Slots.DAILY_PARKOUR_EASY, 11));
        Course c = ((PlannedTrial) p.course()).course();
        int sx = (int) Math.floor(c.start().x());
        int sz = (int) Math.floor(c.start().z());
        int sy = (int) c.start().y() - 1;
        String under = null;
        int lime = 0;
        int gold = 0;
        int blue = 0;
        for (BlockOp op : p.ops()) {
            String b = p.blockOf(op);
            if (op.x() == sx && op.y() == sy && op.z() == sz) {
                under = b;
            }
            lime += b.equals(Palette.START) ? 1 : 0;
            gold += b.equals(Palette.FINISH) ? 1 : 0;
            blue += b.startsWith(Palette.CHECKPOINT) ? 1 : 0;
        }
        assertNotNull(under, "the start spot stands on a block");
        assertTrue(under.startsWith(Palette.ARROW), "an arrow in the middle of the start pad: " + under);
        assertEquals(24, lime, "a 5x5 green start pad (and its arrow)");
        assertEquals(25, gold, "a 5x5 gold finish pad");
        assertTrue(blue >= 3 * 8, "three light-blue 3x3 checkpoint pads (an arrow on a turning one)");
    }

    @Test
    void theArrowPointsTheWayTheFirstJumpGoes() {
        assertEquals(Palette.arrow("west"), ParkourPlanner.arrowToward(0), "east is the block facing west");
        assertEquals(Palette.arrow("north"), ParkourPlanner.arrowToward(2), "south faces north");
        assertEquals(Palette.arrow("east"), ParkourPlanner.arrowToward(4), "west faces east");
        assertEquals(Palette.arrow("south"), ParkourPlanner.arrowToward(6), "north faces south");
        assertThrows(IllegalArgumentException.class, () -> ParkourPlanner.arrowToward(1), "no diagonal arrows");
    }

    @Test
    void mediumAndHardShapedForAShallowFallDepthStillNeverResetAPad() throws GenFailed {
        for (int depth = 1; depth <= 6; depth++) {
            for (Slots.Def slot : List.of(Slots.DAILY_PARKOUR_MEDIUM, Slots.DAILY_PARKOUR_HARD)) {
                for (long seed = 0; seed < 20; seed++) {
                    Plan p = PLANNER.plan(input(slot, 'A', seed, slot.tierOrMix(), depth));
                    assertEquals(List.of(), ParkourValidator.problems(p, slot.tierOrMix(), depth),
                            slot.id() + " at fall_depth " + depth + " seed " + seed);
                }
            }
        }
    }

    @Test
    void everyFallDepthOfSixOrMoreMakesTheSameLayout() throws GenFailed {
        String six = PLANNER.plan(input(Slots.DAILY_PARKOUR_HARD, 'A', 3, "hard", 6)).hash();
        assertEquals(six, PLANNER.plan(input(Slots.DAILY_PARKOUR_HARD, 'A', 3, "hard", 12)).hash(),
                "a deeper fall only lowers the floor: the same layout");
        assertEquals(six, PLANNER.plan(input(Slots.DAILY_PARKOUR_HARD, 'A', 3, "hard", 64)).hash(), "at 64 too");
    }

    /**
     * fix2-D (D0): what a chosen course keeps is the design depth, so it must be exactly what shapes
     * the layout. Two settings with the same design depth make the same plan from the same seed, on
     * every tier: easy never reads the setting (0), medium and hard read it up to 6. And medium and
     * hard below 6 really are other courses, so dropping a pick there is not for nothing.
     */
    @Test
    void theDesignDepthIsAllOfFallDepthThatShapesALayout() throws GenFailed {
        assertEquals(0, ParkourPlanner.designDepth("easy", 3), "easy falls back at a fixed height");
        assertEquals(0, ParkourPlanner.designDepth("rings", 3), "not a parkour tier");
        assertEquals(4, ParkourPlanner.designDepth("medium", 4), "below 6: the setting");
        assertEquals(6, ParkourPlanner.designDepth("hard", 12), "past 6: 6");
        assertEquals(1, ParkourPlanner.designDepth("HARD", 0), "as the settings allow it, whatever the case");
        int[] depths = {1, 2, 4, 5, 6, 7, 9, 64};
        for (Slots.Def slot : SLOTS) {
            String tier = slot.tierOrMix();
            int reshaped = 0;
            for (long seed = 0; seed < 30; seed++) {
                Map<Integer, String> byDesign = new HashMap<>();
                Set<String> hashes = new HashSet<>();
                for (int depth : depths) {
                    String hash = PLANNER.plan(input(slot, 'A', seed, tier, depth)).hash();
                    hashes.add(hash);
                    String same = byDesign.putIfAbsent(ParkourPlanner.designDepth(tier, depth), hash);
                    assertTrue(same == null || same.equals(hash), tier + " seed " + seed + " at fall_depth " + depth
                            + ": the same design depth must make the same layout");
                }
                if (hashes.size() > 1) {
                    reshaped++;
                }
            }
            if (tier.equals("easy")) {
                assertEquals(0, reshaped, "no fall_depth changes an easy layout");
            } else {
                assertTrue(reshaped > 0, tier + ": a shallower fall_depth makes other layouts (so a pick is dropped"
                        + " for a reason)");
            }
        }
    }

    @Test
    void everyCheckpointCountsWhereverFeetCanStandOnItsPad() throws GenFailed {
        for (Slots.Def slot : SLOTS) {
            for (int day = 0; day < 100; day++) {
                Plan p = PLANNER.plan(input(slot, GenSeed.seed(SECRET, 20_000 + day, slot.id(), 0)));
                for (Course.Mark m : ((PlannedTrial) p.course()).course().checkpoints()) {
                    // a 3 x 3 pad under the mark; feet stand up to a half-width past its edges
                    for (double dx = -1.79; dx <= 1.791; dx += 0.179) {
                        for (double dz = -1.79; dz <= 1.791; dz += 0.179) {
                            Progress run = new Progress(new Course(slot.id(), TrialKind.PARKOUR, slot.name(),
                                    Tier.EASY, "", null, List.of(m), new Course.Mark(0, -100, 0, 1), null, null, true,
                                    false, 1), new Point(m.x() + 9, m.y() + 3, m.z()), 0);
                            run.move(new Point(m.x() + dx, m.y(), m.z() + dz), 1);
                            assertEquals(1, run.reachedCheckpoints(), slot.id() + " day " + day + ": landing at "
                                    + dx + "," + dz + " from the middle of the checkpoint counts it");
                        }
                    }
                }
            }
        }
    }

    // ---- the boot check -------------------------------------------------------------------------

    private static GenTag tag(Plan p, int algo) {
        return new GenTag(p.slot(), Slots.PARKOUR, algo, 20725, 0, p.seed(), 'A', p.hash(), 20_000, 40_000, 60_000,
                List.of(), List.of(), 0);
    }

    @Test
    void theBootCheckMakesTheLiveLayoutAgainFromItsTag() throws GenFailed {
        for (Slots.Def slot : SLOTS) {
            Plan live = PLANNER.plan(input(slot, 314));
            Plan again = PLANNER.rederive(input(slot, 0), tag(live, ParkourPlanner.ALGO));
            assertEquals(live.hash(), again.hash(), slot.id() + ": the tag's seed, not the input's");
            assertEquals(live.ops(), again.ops(), slot.id() + ": block for block");
        }
    }

    @Test
    void theBootCheckFindsALayoutMadeUnderAnotherTierOrFallDepth() throws GenFailed {
        Plan shallow = PLANNER.plan(input(Slots.DAILY_PARKOUR_MEDIUM, 'A', 8, "medium", 3));
        Plan again = PLANNER.rederive(input(Slots.DAILY_PARKOUR_MEDIUM, 'A', 0, "medium", 6),
                tag(shallow, ParkourPlanner.ALGO));
        assertEquals(shallow.hash(), again.hash(), "made at fall_depth 3, found with the setting now at 6");
        Plan hard = PLANNER.plan(input(Slots.DAILY_PARKOUR_MEDIUM, 'A', 9, "hard", 6));
        Plan found = PLANNER.rederive(input(Slots.DAILY_PARKOUR_MEDIUM, 'A', 0, "easy", 6),
                tag(hard, ParkourPlanner.ALGO));
        assertEquals(hard.hash(), found.hash(), "made hard, found after an admin set the slot to easy");
    }

    @Test
    void theBootCheckRefusesALayoutTheLiveFallDepthNoLongerFits() throws GenFailed {
        int refused = 0;
        for (int day = 0; day < 40; day++) {
            Box hardA = LegacyBoxes.half(Slots.DAILY_PARKOUR_HARD, 'A');
            PlanInput at6 = new PlanInput(Slots.DAILY_PARKOUR_HARD, hardA, 'A', 20725, 0,
                    GenSeed.seed(0x5EC12E7L, 20_000 + day, Slots.DAILY_PARKOUR_HARD.id(), 0), "hard", 6, 0, null);
            Plan live = PLANNER.plan(at6);
            PlanInput at1 = new PlanInput(Slots.DAILY_PARKOUR_HARD, hardA, 'A', 20725, 0, at6.seed(), "hard", 1, 0,
                    null);
            if (ParkourValidator.problems(live, "hard", 1).isEmpty()) {
                assertEquals(live.hash(), PLANNER.rederive(at1, tag(live, ParkourPlanner.ALGO)).hash(),
                        "day " + day + ": a layout that still fits fall_depth 1 is found as before");
                continue;
            }
            refused++;
            GenFailed e = assertThrows(GenFailed.class, () -> PLANNER.rederive(at1, tag(live, ParkourPlanner.ALGO)),
                    "day " + day + ": made at fall_depth 6, it isn't re-opened at 1");
            assertTrue(e.getMessage().contains("fall_depth 1"), "and says why: " + e.getMessage());
        }
        assertTrue(refused > 0, "some Hard layouts don't fit fall_depth 1 (the case this guards)");
    }

    @Test
    void theBootCheckRefusesALayoutItCantMakeAgain() throws GenFailed {
        Plan live = PLANNER.plan(input(Slots.DAILY_PARKOUR_EASY, 21));
        GenFailed older = assertThrows(GenFailed.class,
                () -> PLANNER.rederive(input(Slots.DAILY_PARKOUR_EASY, 21), tag(live, ParkourPlanner.ALGO + 1)),
                "another version's layout isn't re-derived");
        assertTrue(older.getMessage().contains("v"), "and says which version: " + older.getMessage());
        GenTag wrong = new GenTag(live.slot(), Slots.PARKOUR, ParkourPlanner.ALGO, 20725, 0, live.seed(), 'A',
                "000000000000", 1, 2, 3, List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> PLANNER.rederive(input(Slots.DAILY_PARKOUR_EASY, 21), wrong),
                "a hash the seed no longer makes is refused");
        assertEquals(live.hash(), PLANNER.rederive(input(Slots.DAILY_PARKOUR_EASY, 21), null).hash(),
                "no tag: a plain plan");
    }

    // ---- refusals --------------------------------------------------------------------------------

    @Test
    void aWrongTierACancelAndAnEmptyBudgetFailCleanly() {
        assertThrows(GenFailed.class, () -> PLANNER.plan(input(Slots.DAILY_PARKOUR_EASY, 'A', 1, "EEE", 6)),
                "a golf mix isn't a parkour tier");
        PlanInput cancelled = new PlanInput(Slots.DAILY_PARKOUR_EASY, LegacyBoxes.half(Slots.DAILY_PARKOUR_EASY, 'A'),
                'A', 1, 0, 1, "easy", 6, 0, () -> true);
        GenFailed c = assertThrows(GenFailed.class, () -> PLANNER.plan(cancelled), "a cancelled job gives up");
        assertEquals("cancelled", c.getMessage(), "and says so");
        PlanInput tiny = new PlanInput(Slots.DAILY_PARKOUR_HARD, LegacyBoxes.half(Slots.DAILY_PARKOUR_HARD, 'A'), 'A', 1,
                0, 1, "hard", 6, 1, null);
        assertThrows(GenFailed.class, () -> PLANNER.plan(tiny), "a budget of one try runs out");
        assertTrue(ParkourPlanner.WORK_BUDGET >= 100_000, "the budget the engine should give covers every start");
    }

    @Test
    void itIsThePlannerForParkour() {
        assertEquals(Slots.PARKOUR, PLANNER.id(), "its id");
        assertEquals(ParkourPlanner.ALGO, PLANNER.algo(), "its version");
    }

    @Test
    void theSummaryTellsAnAdminWhatWasMade() throws GenFailed {
        Plan p = PLANNER.plan(input(Slots.DAILY_PARKOUR_HARD, 2));
        assertTrue(p.summary().get(0).contains("28 jumps"), "how many jumps: " + p.summary());
        assertTrue(p.summary().get(1).startsWith("jumps (height/gap):"), "each jump's height and gap");
        Set<String> all = new HashSet<>(p.summary());
        assertTrue(all.stream().noneMatch(l -> l.contains(Long.toHexString(SECRET))), "never the secret");
        assertTrue(p.work() > 0, "the work it took");
    }
}
