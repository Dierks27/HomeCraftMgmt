package com.dierks.homecraft.games.gen.rings;

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
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sky Rings' planner (GEN-SPEC §4.2, §8.3): two thousand real daily seeds per tier all make a
 * layout the independent validator passes first time (the glide bound, the tubes, the voxel rings
 * and every autopilot flight, from a standstill at every ring too); the fall height is under every
 * ring; the course fits its height; the same seed always makes the same layout.
 */
class RingsPlannerTest {

    private static final RingsPlanner PLANNER = new RingsPlanner();
    private static final long SECRET = 0x5EC12E7L;
    private static final Slots.Def SLOT = Slots.SKY_RINGS;

    static PlanInput input(char half, long seed, String tier) {
        return new PlanInput(SLOT, LegacyBoxes.half(SLOT, half), half, 20725, 0, seed, tier, 6, 0, null);
    }

    @Test
    void twoThousandEasySeedsAllPassTheValidator() {
        soak(RingsPlanner.Level.EASY);
    }

    @Test
    void twoThousandMediumSeedsAllPassTheValidator() {
        soak(RingsPlanner.Level.MEDIUM);
    }

    @Test
    void twoThousandHardSeedsAllPassTheValidator() {
        soak(RingsPlanner.Level.HARD);
    }

    /**
     * Two thousand days of real seeds: every plan came out of the planner's own validator check
     * without a single refusal (so the construction is right, not the filter); every tenth is
     * validated again here; and on every one the rings stay above the floor with the course's
     * whole height, the fall height is under every ring, and every ring drops at least 1 in
     * the tier's slow ratio.
     */
    private static void soak(RingsPlanner.Level level) {
        int n = 2_000;
        Set<String> hashes = ConcurrentHashMap.newKeySet();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger rejected = new AtomicInteger();
        Box half = LegacyBoxes.half(SLOT, 'A');
        IntStream.range(0, n).parallel().forEach(day -> {
            long seed = GenSeed.seed(SECRET, 20_000 + day, SLOT.id(), 0);
            try {
                RingsPlanner.Build b = PLANNER.build(input('A', seed, level.id()));
                rejected.addAndGet(b.validatorRejections());
                Plan p = b.plan();
                hashes.add(p.hash());
                if (day % 10 == 0) {
                    List<String> problems = RingsValidator.problems(p, level.id());
                    if (!problems.isEmpty()) {
                        failures.add("day " + day + ": " + problems);
                    }
                }
                Course c = ((PlannedTrial) p.course()).course();
                List<Course.Mark> rings = new ArrayList<>(c.checkpoints());
                rings.add(c.finish());
                if (rings.size() != level.rings()) {
                    failures.add("day " + day + ": " + rings.size() + " rings");
                }
                double previous = Double.MAX_VALUE;
                for (Course.Mark m : rings) {
                    if (m.y() < half.minY() + RingsPlanner.FLOOR) {
                        failures.add("day " + day + ": a ring under the floor + 16 at y " + m.y());
                    }
                    if (c.fallY() >= m.y() - level.radius() - 1) {
                        failures.add("day " + day + ": the fall height " + c.fallY() + " isn't under the ring at "
                                + m.y());
                    }
                    if (m.y() >= previous) {
                        failures.add("day " + day + ": a ring isn't lower than the one before");
                    }
                    previous = m.y();
                }
                for (BlockOp op : p.ops()) {
                    if (!half.contains(op.x(), op.y(), op.z())) {
                        failures.add("day " + day + ": a block outside the half at " + op);
                        break;
                    }
                }
            } catch (GenFailed e) {
                failures.add("day " + day + ": " + e.getMessage());
            }
        });
        assertTrue(failures.isEmpty(), level + ": " + failures.size() + " bad days, first "
                + failures.subList(0, Math.min(5, failures.size())));
        assertEquals(0, rejected.get(), level + ": the validator never had to turn a plan down");
        assertTrue(hashes.size() >= n * 0.95, level + ": at least 95% of days differ, got " + hashes.size());
    }

    // ---- the same seed, the same layout -------------------------------------------------------

    @Test
    void theSameSeedAlwaysMakesTheSameLayout() throws GenFailed {
        for (RingsPlanner.Level level : RingsPlanner.Level.values()) {
            Plan a = PLANNER.plan(input('A', 77, level.id()));
            Plan b = PLANNER.plan(input('A', 77, level.id()));
            assertEquals(a.hash(), b.hash(), level + ": the same seed hashes the same");
            assertEquals(a.ops(), b.ops(), level + ": block for block");
            assertNotEquals(a.hash(), PLANNER.plan(input('A', 78, level.id())).hash(), level + ": another seed differs");
        }
    }

    @Test
    void goldenHashesPinThreeSeedsPerTier() throws GenFailed {
        // A change here means the planner makes different layouts: bump RingsPlanner.ALGO.
        String[][] golden = {
                {"easy", "15712bc2b54e", "3e3fad90d7b6", "7c1ce1ca2ea4"},
                {"medium", "484bb189f55b", "ddf7bf273cb7", "6a3f2c9f2b91"},
                {"hard", "fd8fb8bb0278", "db2a5231db65", "512a7a0e400d"},
        };
        long[] seeds = {1L, 0xC0FFEEL, 0x5EED5EEDL};
        for (String[] tier : golden) {
            for (int s = 0; s < 3; s++) {
                assertEquals(tier[s + 1], PLANNER.plan(input('A', seeds[s], tier[0])).hash(),
                        tier[0] + " seed " + Long.toHexString(seeds[s]) + " (if this changed, bump ALGO)");
            }
        }
        assertEquals(2, RingsPlanner.ALGO, "the version these hashes were pinned at (2: flights are checked all"
                + " along each tick's move, with a clearance)");
    }

    @Test
    void halfBGetsItsOwnLayoutInsideHalfB() throws GenFailed {
        Plan p = PLANNER.plan(input('B', 4, "easy"));
        Box half = LegacyBoxes.half(SLOT, 'B');
        for (BlockOp op : p.ops()) {
            assertTrue(half.contains(op.x(), op.y(), op.z()), op + " inside half B");
        }
        assertEquals(List.of(), RingsValidator.problems(p, "easy"), "and it passes there");
    }

    // ---- the course ----------------------------------------------------------------------------

    @Test
    void theCourseIsAnElytraTrialThroughRainbowRings() throws GenFailed {
        for (RingsPlanner.Level level : RingsPlanner.Level.values()) {
            Plan p = PLANNER.plan(input('A', 12, level.id()));
            PlannedTrial t = (PlannedTrial) p.course();
            Course c = t.course();
            assertEquals(SLOT.id(), c.id(), "the course id is the slot");
            assertEquals(TrialKind.ELYTRA, c.kind(), "an elytra course");
            assertEquals(SLOT.name(), c.name(), "named for the slot");
            assertEquals(Tier.of(level.id()), c.tier(), "in its tier");
            assertNull(c.gen(), "the tag is the engine's to add");
            assertEquals(level.rings() - 1, c.checkpoints().size(), "every ring but the last is a checkpoint");
            for (Course.Mark m : c.checkpoints()) {
                assertEquals(level.radius() - 1, m.radius(), 1e-9, "a checkpoint counts inside the hole: R - 1");
            }
            assertEquals(level.radius() - 1, c.finish().radius(), 1e-9, "so does the finish");
            // the rings' colours: rainbow order, the last one gold
            List<String> colours = new ArrayList<>();
            for (Course.Mark m : allRings(c)) {
                colours.add(colourAround(p, m, level.radius()));
            }
            for (int k = 0; k < colours.size() - 1; k++) {
                assertEquals(Palette.RAINBOW.get(k % Palette.RAINBOW.size()), colours.get(k), "ring " + (k + 1)
                        + " follows the rainbow");
            }
            assertEquals(Palette.FINISH, colours.get(colours.size() - 1), "the finish ring is gold");
            // the reference time is the path at 20 b/s; the shortest believable one the path at 60
            double length = 0;
            double[] at = {c.start().x(), c.start().y(), c.start().z()};
            for (Course.Mark m : allRings(c)) {
                length += Math.sqrt(Math.pow(m.x() - at[0], 2) + Math.pow(m.y() - at[1], 2) + Math.pow(m.z() - at[2], 2));
                at = new double[]{m.x(), m.y(), m.z()};
            }
            assertEquals(Math.round(length / 20 * 1000), t.refMs(), "reference time: the path at 20 blocks a second");
            assertEquals((int) Math.floor(length / 60), (int) c.minSeconds(), "shortest time: the path at 60");
            double lowest = Double.MAX_VALUE;
            for (Course.Mark m : allRings(c)) {
                lowest = Math.min(lowest, m.y());
            }
            assertEquals(Math.max(LegacyBoxes.half(SLOT, 'A').minY() + 2, lowest - level.radius() - 12), c.fallY(),
                    1e-9, "fall height: 12 under the lowest ring's frame");
            assertTrue(p.ops().size() < 1_000, "a light build: " + p.ops().size() + " blocks");
            assertTrue(Palette.problems(p.palette()).isEmpty(), "only allowed blocks: " + p.palette());
        }
    }

    private static List<Course.Mark> allRings(Course c) {
        List<Course.Mark> out = new ArrayList<>(c.checkpoints());
        out.add(c.finish());
        return out;
    }

    private static String colourAround(Plan p, Course.Mark m, int radius) {
        int cx = (int) Math.floor(m.x());
        int cy = (int) Math.floor(m.y());
        int cz = (int) Math.floor(m.z());
        for (BlockOp op : p.ops()) {
            // the bottom of the frame, straight under the centre, whichever way the ring faces
            if (op.x() == cx && op.y() == cy - radius && op.z() == cz) {
                return p.blockOf(op);
            }
        }
        return "none";
    }

    @Test
    void theTowerIsWhereTheCourseStarts() throws GenFailed {
        Plan p = PLANNER.plan(input('A', 13, "easy"));
        Course c = ((PlannedTrial) p.course()).course();
        Box half = LegacyBoxes.half(SLOT, 'A');
        assertEquals(half.maxY() - RingsPlanner.TOWER_TOP, c.start().y(), 1e-9, "the platform's top is 16 under the roof");
        assertEquals(180f, c.start().yaw(), 0f, "facing north along the board");
        int under = 0;
        int pillar = 0;
        for (BlockOp op : p.ops()) {
            if (op.x() == (int) Math.floor(c.start().x()) && op.z() == (int) Math.floor(c.start().z())) {
                if (op.y() == (int) c.start().y() - 1) {
                    under++;
                    assertEquals(Palette.TOWER, p.blockOf(op), "the start stands on the white platform");
                } else {
                    pillar++;
                    assertTrue(p.blockOf(op).startsWith(Palette.PILLAR), "the pillar under it is quartz");
                }
            }
        }
        assertEquals(1, under, "one block under the start");
        assertEquals((int) c.start().y() - 1 - half.minY(), pillar, "the pillar runs down to the half's floor");
        assertEquals(2, p.signs().size(), "two signs");
        assertEquals(GenCopy.ringsStart(), p.signs().get(0).lines(), "how to start flying");
        assertEquals(GenCopy.ringsHow(), p.signs().get(1).lines(), "where to fly");
        for (SignText s : p.signs()) {
            assertEquals((int) c.start().y(), s.y(), "the signs stand on the platform");
        }
        Course.Mark first = c.checkpoints().get(0);
        assertEquals(c.start().y() - RingsPlanner.FIRST_BELOW, first.y(), 1.0, "ring 1 is about 8 below the platform");
    }

    @Test
    void everyTierKeepsItsGlideMarginAndItsTable() {
        for (RingsPlanner.Level level : RingsPlanner.Level.values()) {
            assertTrue(level.slow() <= RingsPlanner.GLIDE / RingsPlanner.GLIDE_MARGIN, level
                    + ": every drop at least 1.4x under the 10:1 glide line");
            assertTrue(level.fast() < level.slow(), level + ": the steepest drop is steeper than the shallowest");
        }
        assertEquals(RingsPlanner.GLIDE / 2, RingsPlanner.Level.EASY.slow(), 1e-9, "easy is 2x under the glide line");
        assertEquals(List.of(10, 12, 14), List.of(RingsPlanner.Level.EASY.rings(), RingsPlanner.Level.MEDIUM.rings(),
                RingsPlanner.Level.HARD.rings()), "rings per tier");
        assertEquals(List.of(6, 5, 4), List.of(RingsPlanner.Level.EASY.radius(), RingsPlanner.Level.MEDIUM.radius(),
                RingsPlanner.Level.HARD.radius()), "ring radii");
        assertEquals(List.of(20, 30, 45), List.of(RingsPlanner.Level.EASY.maxTurn(),
                RingsPlanner.Level.MEDIUM.maxTurn(), RingsPlanner.Level.HARD.maxTurn()), "turns");
        assertEquals(35, RingsPlanner.Level.EASY.maxOffNormal(), 1e-9, "easy is never flown more than 35 off a ring");
    }

    // ---- the boot check -------------------------------------------------------------------------

    @Test
    void theBootCheckMakesTheLiveLayoutAgainFromItsTag() throws GenFailed {
        Plan live = PLANNER.plan(input('A', 99, "medium"));
        GenTag tag = new GenTag(SLOT.id(), Slots.RINGS, RingsPlanner.ALGO, 20725, 0, 99, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertEquals(live.hash(), PLANNER.rederive(input('A', 0, "medium"), tag).hash(), "the tag's seed");
        assertEquals(live.hash(), PLANNER.rederive(input('A', 0, "easy"), tag).hash(),
                "found even after the tier was changed");
        GenTag older = new GenTag(SLOT.id(), Slots.RINGS, RingsPlanner.ALGO + 1, 20725, 0, 99, 'A', live.hash(), 1, 2,
                3, List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> PLANNER.rederive(input('A', 0, "medium"), older), "another version");
        GenTag wrong = new GenTag(SLOT.id(), Slots.RINGS, RingsPlanner.ALGO, 20725, 0, 99, 'A', "000000000000", 1, 2,
                3, List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> PLANNER.rederive(input('A', 0, "medium"), wrong), "a hash it can't make");
    }

    @Test
    void aWrongTierACancelAndAnEmptyBudgetFailCleanly() {
        assertThrows(GenFailed.class, () -> PLANNER.plan(input('A', 1, "extreme")), "no such tier");
        PlanInput cancelled = new PlanInput(SLOT, LegacyBoxes.half(SLOT, 'A'), 'A', 1, 0, 1, "easy", 6, 0, () -> true);
        assertThrows(GenFailed.class, () -> PLANNER.plan(cancelled), "a cancelled job gives up");
        PlanInput tiny = new PlanInput(SLOT, LegacyBoxes.half(SLOT, 'A'), 'A', 1, 0, 1, "easy", 6, 10, null);
        assertThrows(GenFailed.class, () -> PLANNER.plan(tiny), "a budget of ten runs out");
        assertEquals(Slots.RINGS, PLANNER.id(), "its id");
        assertEquals(RingsPlanner.ALGO, PLANNER.algo(), "its version");
    }

    @Test
    void theSummaryTellsAnAdminWhatWasMade() throws GenFailed {
        Plan p = PLANNER.plan(input('A', 2, "hard"));
        assertTrue(p.summary().get(0).contains("14 rings"), "how many rings: " + p.summary());
        assertTrue(p.summary().get(1).startsWith("legs (across/down):"), "each leg's length and drop");
        assertTrue(p.summary().get(2).startsWith("steepest turn"), "the steepest turn");
    }
}
