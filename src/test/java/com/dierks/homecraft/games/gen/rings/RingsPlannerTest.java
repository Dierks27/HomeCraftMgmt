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
import com.dierks.homecraft.games.gen.api.RingsV3Boxes;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Yardsticks;
import com.dierks.homecraft.games.gen.rings.RingsPlanner.LegKind;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sky Rings' planner (GEN-SPEC §4.2, §8.3, Sky Rings v3): a hundred real daily seeds per tier (two
 * thousand under {@code -Phcm.slow}) all make a loop the independent validator passes, with at most
 * one in fifty turned down by the planner's own check first; every ring sits between the floor and
 * the roof, the legs follow the profile and their kinds' bands, the rocket budget is the need plus
 * two, the autopilot's lap is a minute or two and the course sweeps round the square; the same seed
 * always makes the same layout; and planning stays well inside its budget and time.
 */
class RingsPlannerTest {

    private static final RingsPlanner PLANNER = new RingsPlanner();
    private static final long SECRET = 0x5EC12E7L;
    private static final Slots.Def SLOT = Slots.SKY_RINGS;
    /** Seeds per tier soaked on every build, and under {@code -Phcm.slow}. */
    private static final int SOAK = 100;
    private static final int SLOW_SOAK = 2_000;
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final Pattern AUTOPILOT = Pattern.compile("autopilot ([0-9.]+)s");

    static PlanInput input(char half, long seed, String tier) {
        return new PlanInput(SLOT, RingsV3Boxes.half(SLOT, half), half, 20725, 0, seed, tier, 6, 0, null);
    }

    /** One day's plan: what the planner made (or why it failed) and the CPU it took. */
    record Run(int day, RingsPlanner.Build build, String failure, long millis) {
    }

    private static final Map<String, List<Run>> RUNS = new ConcurrentHashMap<>();

    /** The first {@code n} days of {@code level}, planned once in parallel and kept for every test that wants them. */
    private static List<Run> runs(RingsPlanner.Level level, int n) {
        return RUNS.computeIfAbsent(level.id() + ":" + n, key -> IntStream.range(0, n).parallel()
                .mapToObj(day -> run(level, day)).toList());
    }

    private static Run run(RingsPlanner.Level level, int day) {
        long seed = GenSeed.seed(SECRET, 20_000 + day, SLOT.id(), 0);
        long t0 = CPU.getCurrentThreadCpuTime();
        try {
            RingsPlanner.Build b = PLANNER.build(input('A', seed, level.id()));
            return new Run(day, b, null, (CPU.getCurrentThreadCpuTime() - t0) / 1_000_000);
        } catch (GenFailed e) {
            return new Run(day, null, e.getMessage(), (CPU.getCurrentThreadCpuTime() - t0) / 1_000_000);
        }
    }

    @Test
    void aHundredEasySeedsAllMakeALoopTheValidatorPasses() {
        soak(RingsPlanner.Level.EASY, SOAK);
    }

    @Test
    void aHundredMediumSeedsAllMakeALoopTheValidatorPasses() {
        soak(RingsPlanner.Level.MEDIUM, SOAK);
    }

    @Test
    void aHundredHardSeedsAllMakeALoopTheValidatorPasses() {
        soak(RingsPlanner.Level.HARD, SOAK);
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void twoThousandEasySeedsAllMakeALoop() {
        soak(RingsPlanner.Level.EASY, SLOW_SOAK);
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void twoThousandMediumSeedsAllMakeALoop() {
        soak(RingsPlanner.Level.MEDIUM, SLOW_SOAK);
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void twoThousandHardSeedsAllMakeALoop() {
        soak(RingsPlanner.Level.HARD, SLOW_SOAK);
    }

    /**
     * Real daily seeds: every plan came out of the planner's own validator check, at most one in fifty
     * after a refusal (the planner flies a window of pilots, the validator all of them); every tenth is
     * validated again here; and on every one the rings stay between the floor + 16 and the roof, the
     * legs follow the profile's rules and their kinds' bands, the rocket budget is the need plus two,
     * the fall height is under every ring, the build is light and inside the half, and the autopilot's
     * lap is a minute or two.
     */
    private static void soak(RingsPlanner.Level level, int n) {
        List<Run> runs = runs(level, n);
        List<String> failures = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        int rejected = 0;
        Box half = RingsV3Boxes.half(SLOT, 'A');
        int roof = half.maxY() - level.radius() - RingsPlanner.EDGE;
        for (Run run : runs) {
            String day = "day " + run.day() + ": ";
            if (run.build() == null) {
                failures.add(day + run.failure());
                continue;
            }
            rejected += run.build().validatorRejections();
            Plan p = run.build().plan();
            hashes.add(p.hash());
            if (run.day() % 10 == 0) {
                List<String> problems = RingsValidator.problems(p, level.id());
                if (!problems.isEmpty()) {
                    failures.add(day + problems);
                }
            }
            Course c = ((PlannedTrial) p.course()).course();
            List<Course.Mark> rings = allRings(c);
            if (rings.size() != level.rings()) {
                failures.add(day + rings.size() + " rings");
                continue;
            }
            Course.Elytra e = c.elytra();
            if (e == null || e.legs().length() != rings.size() - 1 || e.need().size() != rings.size()) {
                failures.add(day + "the rocket budget is off: " + e);
                continue;
            }
            LegKind[] kinds = RingsPlanner.kinds(e.legs());
            if (kinds == null) {
                failures.add(day + "legs " + e.legs());
                continue;
            }
            for (String problem : RingsValidator.profileProblems(kinds, level)) {
                failures.add(day + problem);
            }
            for (int k = 1; k < e.need().size(); k++) {
                if (e.need().get(k) > e.need().get(k - 1)) {
                    failures.add(day + "the rockets needed grow along the course: " + e.need());
                }
            }
            if (e.rockets() != e.need().get(0) + RingsPlanner.MARGIN || e.rockets() != level.rockets()) {
                failures.add(day + "rockets " + e.rockets() + " for a need of " + e.need().get(0));
            }
            for (int k = 0; k < rings.size(); k++) {
                Course.Mark m = rings.get(k);
                int cy = (int) Math.floor(m.y());
                if (cy < half.minY() + RingsPlanner.FLOOR || cy > roof) {
                    failures.add(day + "ring " + (k + 1) + " at y " + cy + " is outside the floor + 16 and the roof "
                            + roof);
                }
                if (c.fallY() >= m.y() - level.radius() - 1) {
                    failures.add(day + "the fall height " + c.fallY() + " isn't under the ring at " + m.y());
                }
                if (k == 0) {
                    continue;
                }
                Course.Mark a = rings.get(k - 1);
                double d = Math.hypot(m.x() - a.x(), m.z() - a.z());
                double drop = a.y() - m.y();
                String leg = "leg " + (k + 1) + " (" + kinds[k - 1] + ") " + Math.round(d) + "/" + Math.round(drop);
                switch (kinds[k - 1]) {
                    case LIFT -> {
                        if (d < Math.max(level.legMin(), RingsPlanner.LIFT_LEG_MIN) - 1e-9
                                || d > Math.min(level.legMax(), RingsPlanner.LIFT_LEG_MAX) + 1e-9
                                || -drop < level.upMin() || -drop > level.upMax()) {
                            failures.add(day + leg + " is outside the climb band");
                        }
                    }
                    case FLAT -> {
                        if (d < Math.max(level.legMin(), RingsPlanner.FLAT_MIN) - 1e-9
                                || d > RingsPlanner.FLAT_MAX + 1e-9 || drop < 0 || drop > RingsPlanner.FLAT_DROP) {
                            failures.add(day + leg + " is outside the level band");
                        }
                    }
                    default -> {
                        if (d < level.legMin() - 1e-9 || d > level.legMax() + 1e-9 || drop < d / level.slow() - 1e-9
                                || drop > d / level.fast() + 1e-9) {
                            failures.add(day + leg + " is outside the glide band");
                        }
                    }
                }
            }
            if (p.ops().size() >= 2_000) {
                failures.add(day + p.ops().size() + " blocks");
            }
            for (BlockOp op : p.ops()) {
                if (!half.contains(op.x(), op.y(), op.z())) {
                    failures.add(day + "a block outside the half at " + op);
                    break;
                }
            }
            double lap = autopilotSeconds(p);
            if (lap < 55 || lap > 130) {
                failures.add(day + "the autopilot's lap is " + lap + "s, not a minute or two: " + p.summary());
            }
        }
        assertTrue(failures.isEmpty(), level + ": " + failures.size() + " bad days, first "
                + failures.subList(0, Math.min(5, failures.size())));
        assertTrue(rejected <= n * 0.02, level + ": the validator turned " + rejected + " plans down of " + n
                + " (at most 2%)");
        assertTrue(hashes.stream().distinct().count() >= n * 0.95, level + ": at least 95% of days differ, got "
                + hashes.stream().distinct().count());
    }

    /** The autopilot's lap from the summary's "rockets 8 (6 needed), autopilot 72.9s" line. */
    static double autopilotSeconds(Plan p) {
        for (String line : p.summary()) {
            Matcher m = AUTOPILOT.matcher(line);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        throw new AssertionError("no autopilot time in " + p.summary());
    }

    @Test
    void theCourseLoopsRoundTheHalf() {
        for (RingsPlanner.Level level : RingsPlanner.Level.values()) {
            List<String> small = new ArrayList<>();
            for (Run run : runs(level, SOAK)) {
                if (run.build() == null) {
                    continue;
                }
                Course c = ((PlannedTrial) run.build().plan().course()).course();
                double minX = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE;
                double maxZ = -Double.MAX_VALUE;
                for (Course.Mark m : allRings(c)) {
                    minX = Math.min(minX, m.x());
                    maxX = Math.max(maxX, m.x());
                    minZ = Math.min(minZ, m.z());
                    maxZ = Math.max(maxZ, m.z());
                }
                if (maxX - minX < 300 || maxZ - minZ < 300) {
                    small.add("day " + run.day() + ": " + Math.round(maxX - minX) + " x " + Math.round(maxZ - minZ));
                }
            }
            assertEquals(List.of(), small, level + ": every course's rings span at least 300 x 300 of the square");
        }
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
                {"easy", "000000000000", "000000000000", "000000000000"},
                {"medium", "000000000000", "000000000000", "000000000000"},
                {"hard", "000000000000", "000000000000", "000000000000"},
        };
        long[] seeds = {1L, 0xC0FFEEL, 0x5EED5EEDL};
        for (String[] tier : golden) {
            for (int s = 0; s < 3; s++) {
                assertEquals(tier[s + 1], PLANNER.plan(input('A', seeds[s], tier[0])).hash(),
                        tier[0] + " seed " + Long.toHexString(seeds[s]) + " (if this changed, bump ALGO)");
            }
        }
        assertEquals(3, RingsPlanner.ALGO, "the version these hashes were pinned at (3: a 512-wide loop with glide,"
                + " climb and level legs and a fixed rocket budget)");
    }

    @Test
    void halfBGetsItsOwnLayoutInsideHalfB() throws GenFailed {
        Plan p = PLANNER.plan(input('B', 4, "easy"));
        Box half = RingsV3Boxes.half(SLOT, 'B');
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
            // the rocket budget: a kind per leg after ring 1, the need from them, the stack two over the need
            Course.Elytra e = c.elytra();
            assertNotNull(e, "the course carries its rocket budget");
            assertEquals(c.checkpoints().size(), e.legs().length(), "a leg kind per checkpoint after the first");
            assertEquals(level.rings(), e.need().size(), "a need from the tower and from every ring but the last");
            assertEquals(RingsPlanner.needList(RingsPlanner.need(RingsPlanner.kinds(e.legs()))), e.need(),
                    "the need is what the legs say");
            assertEquals(e.need().get(0) + RingsPlanner.MARGIN, e.rockets(), "two rockets over the need");
            assertEquals(level.rockets(), e.rockets(), "the tier's stack");
            assertEquals(0, e.need().get(e.need().size() - 1), "the last leg is a glide: nothing needed from the last ring");
            // the rings' colours: rainbow order, white after a climb, the last one gold
            List<String> colours = new ArrayList<>();
            for (Course.Mark m : allRings(c)) {
                colours.add(colourAround(p, m, level.radius()));
            }
            int white = 0;
            for (int k = 0; k < colours.size() - 1; k++) {
                if (k > 0 && e.legs().charAt(k - 1) == 'L') {
                    assertEquals(Palette.TOWER, colours.get(k), "ring " + (k + 1) + " ends a climb: white");
                    white++;
                } else {
                    assertEquals(Palette.RAINBOW.get(k % Palette.RAINBOW.size()), colours.get(k), "ring " + (k + 1)
                            + " follows the rainbow");
                }
            }
            assertEquals(level.lifts(), white, "a white ring per climb");
            assertEquals(Palette.FINISH, colours.get(colours.size() - 1), "the finish ring is gold");
            // the reference time is 0.8 of the autopilot's lap; the shortest believable one the path at 60
            double length = 0;
            double[] at = {c.start().x(), c.start().y(), c.start().z()};
            for (Course.Mark m : allRings(c)) {
                length += Math.sqrt(Math.pow(m.x() - at[0], 2) + Math.pow(m.y() - at[1], 2) + Math.pow(m.z() - at[2], 2));
                at = new double[]{m.x(), m.y(), m.z()};
            }
            double lapMs = autopilotSeconds(p) * 1000;
            assertTrue(t.refMs() >= 0.5 * lapMs - 100 && t.refMs() <= lapMs + 100,
                    "reference time " + t.refMs() + "ms is between half and all of the autopilot's lap " + lapMs);
            assertEquals(Math.round(lapMs * RingsPlanner.REF_FACTOR), t.refMs(), 100, "and 0.8 of it");
            assertEquals((int) Math.floor(length / 60), (int) c.minSeconds(), "shortest time: the path at 60");
            double lowest = Double.MAX_VALUE;
            for (Course.Mark m : allRings(c)) {
                lowest = Math.min(lowest, m.y());
            }
            assertEquals(Math.max(RingsV3Boxes.half(SLOT, 'A').minY() + 2, lowest - level.radius() - 12), c.fallY(),
                    1e-9, "fall height: 12 under the lowest ring's frame");
            assertTrue(p.ops().size() < 2_000, "a light build: " + p.ops().size() + " blocks");
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
        Box half = RingsV3Boxes.half(SLOT, 'A');
        assertEquals(half.maxY() - RingsPlanner.TOWER_TOP, c.start().y(), 1e-9, "the platform's top is 16 under the roof");
        assertEquals(180f, c.start().yaw(), 0f, "facing north along the board");
        assertEquals(half.maxZ() - RingsPlanner.TOWER_SOUTH + 0.5, c.start().z(), 1e-9, "at the south end");
        assertTrue(c.start().x() == half.minX() + RingsPlanner.TOWER_INSET + 0.5
                || c.start().x() == half.maxX() - RingsPlanner.TOWER_INSET + 0.5, "40 in from a side: " + c.start().x());
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
        assertEquals(GenCopy.ringsHow(8), p.signs().get(1).lines(), "the rockets for the whole run");
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
                    + ": every glide at least 1.4x under the 10:1 glide line");
            assertTrue(level.fast() < level.slow(), level + ": the steepest drop is steeper than the shallowest");
            assertEquals(45, level.maxOffNormal(), 1e-9, level + " is never flown more than 45 off a ring");
            assertEquals(level.lifts() + level.flats(), level.need(), level + ": one rocket a climb and a level leg");
            assertEquals(RingsPlanner.MARGIN, level.rockets() - level.need(), level + ": two to spare");
            assertEquals(level.rings() - 1, level.glides() + level.lifts() + level.flats(), level + ": a kind per leg");
        }
        List<RingsPlanner.Level> tiers = List.of(RingsPlanner.Level.EASY, RingsPlanner.Level.MEDIUM,
                RingsPlanner.Level.HARD);
        assertEquals(List.of(20, 24, 26), tiers.stream().map(RingsPlanner.Level::rings).toList(), "rings per tier");
        assertEquals(List.of(8, 7, 6), tiers.stream().map(RingsPlanner.Level::radius).toList(), "ring radii");
        assertEquals(List.of(30, 40, 45), tiers.stream().map(RingsPlanner.Level::maxTurn).toList(), "turns");
        assertEquals(List.of(4, 5, 5), tiers.stream().map(RingsPlanner.Level::lifts).toList(), "climbs");
        assertEquals(List.of(2, 2, 4), tiers.stream().map(RingsPlanner.Level::flats).toList(), "level legs");
        assertEquals(List.of(6, 7, 9), tiers.stream().map(RingsPlanner.Level::need).toList(), "rockets needed");
        assertEquals(List.of(8, 9, 11), tiers.stream().map(RingsPlanner.Level::rockets).toList(), "rockets handed out");
    }

    @Test
    void theProfileIsTheTemplateWithTheLevelLegsDicedIn() {
        for (RingsPlanner.Level level : RingsPlanner.Level.values()) {
            for (long seed = 1; seed <= 20; seed++) {
                LegKind[] kinds = RingsPlanner.profile(level, new com.dierks.homecraft.games.gen.api.GenRandom(seed));
                assertEquals(level.rings() - 1, kinds.length, level + ": a kind per leg after ring 1");
                assertEquals(List.of(), RingsValidator.profileProblems(kinds, level), level + " seed " + seed + ": "
                        + RingsPlanner.legs(kinds));
                int[] need = RingsPlanner.need(kinds);
                assertEquals(level.need(), need[0], level + ": the need from the tower");
                assertEquals(need[0], need[1], "nothing is spent before ring 1");
            }
        }
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
    void aWrongTierACancelAnEmptyBudgetAndALegacyBoxFailCleanly() {
        assertThrows(GenFailed.class, () -> PLANNER.plan(input('A', 1, "extreme")), "no such tier");
        PlanInput cancelled = new PlanInput(SLOT, RingsV3Boxes.half(SLOT, 'A'), 'A', 1, 0, 1, "easy", 6, 0, () -> true);
        assertThrows(GenFailed.class, () -> PLANNER.plan(cancelled), "a cancelled job gives up");
        PlanInput tiny = new PlanInput(SLOT, RingsV3Boxes.half(SLOT, 'A'), 'A', 1, 0, 1, "easy", 6, 10, null);
        assertThrows(GenFailed.class, () -> PLANNER.plan(tiny), "a budget of ten runs out");
        PlanInput legacy = new PlanInput(SLOT, LegacyBoxes.half(SLOT, 'A'), 'A', 1, 0, 1, "easy", 6, 0, null);
        GenFailed small = assertThrows(GenFailed.class, () -> PLANNER.plan(legacy), "a 128 x 320 half is too small");
        assertTrue(small.getMessage().contains("too small"), "and says so: " + small.getMessage());
        assertEquals(Slots.RINGS, PLANNER.id(), "its id");
        assertEquals(RingsPlanner.ALGO, PLANNER.algo(), "its version");
    }

    @Test
    void theSummaryTellsAnAdminWhatWasMade() throws GenFailed {
        Plan p = PLANNER.plan(input('A', 2, "hard"));
        assertTrue(p.summary().get(0).contains("26 rings"), "how many rings: " + p.summary());
        assertTrue(p.summary().get(1).startsWith("legs (across/down"), "each leg's length and drop: " + p.summary());
        assertTrue(p.summary().get(2).startsWith("steepest turn"), "the steepest turn");
        assertTrue(p.summary().get(3).startsWith("rockets 11 (9 needed)"), "the rocket budget: " + p.summary().get(3));
    }

    // ---- cost ---------------------------------------------------------------------------------------

    /**
     * Planning is well inside its budget and time (the golf bench's precedent): a hundred easy seeds
     * in parallel, then ten one at a time, each timed in CPU on its own thread just after the two
     * yardsticks, so the caps scale with the machine.
     */
    @Test
    void ringsPlanningIsWellInsideItsBudgetAndTime() {
        List<Run> parallel = runs(RingsPlanner.Level.EASY, SOAK);
        for (int i = 0; i < 5; i++) {
            Yardsticks.walk(); // compiled before any timing of them counts
            Yardsticks.roll();
        }
        int alone = 10;
        long[] aloneMs = new long[alone];
        long[] walks = new long[alone];
        long[] rolls = new long[alone];
        for (int n = 0; n < alone; n++) {
            long t0 = CPU.getCurrentThreadCpuTime();
            long walked = Yardsticks.walk();
            long t1 = CPU.getCurrentThreadCpuTime();
            long rolled = Yardsticks.roll();
            long t2 = CPU.getCurrentThreadCpuTime();
            assertEquals(Yardsticks.WALK_SUM, walked, "the walk does the same work every time");
            assertEquals(Yardsticks.ROLL_SUM, rolled, "the roll does the same work every time");
            walks[n] = (t1 - t0) / 1_000;
            rolls[n] = (t2 - t1) / 1_000;
            Run r = run(RingsPlanner.Level.EASY, n);
            assertNotNull(r.build(), "seed " + n + ": " + r.failure());
            aloneMs[n] = r.millis();
        }
        java.util.Arrays.sort(aloneMs);
        java.util.Arrays.sort(walks);
        java.util.Arrays.sort(rolls);
        long[] ms = parallel.stream().mapToLong(Run::millis).sorted().toArray();
        long[] work = parallel.stream().filter(r -> r.build() != null).mapToLong(r -> r.build().work()).sorted().toArray();
        long[] ops = parallel.stream().filter(r -> r.build() != null).mapToLong(r -> r.build().plan().ops().size())
                .sorted().toArray();
        double slower = Yardsticks.slower(walks, rolls);
        long p50 = aloneMs[aloneMs.length / 2];
        long p99Ms = ms[(int) (ms.length * 0.99)];
        long p99Work = work[(int) (work.length * 0.99)];
        long p99Ops = ops[(int) (ops.length * 0.99)];
        long p50Cap = Math.round(3_000 * slower);
        long p99Cap = Math.round(15_000 * slower);
        String machine = String.format(Locale.ROOT, "; the walk took %d us (%d where the times were measured), the roll"
                + " %d us (%d), %.2fx slower; %s", walks[walks.length / 2], Yardsticks.WALK_REF_MICROS,
                rolls[rolls.length / 2], Yardsticks.ROLL_REF_MICROS, slower, Yardsticks.machine());
        System.out.println("FreshBench rings v3 easy: work p50 " + work[work.length / 2] + " p99 " + p99Work + " max "
                + work[work.length - 1] + " (budget " + RingsPlanner.WORK_BUDGET + "); CPU p50 " + p50
                + " ms one at a time, p99 " + p99Ms + " ms, max " + ms[ms.length - 1] + " ms in parallel (planning and"
                + " its own full check); blocks p50 " + ops[ops.length / 2] + " p99 " + p99Ops + machine);
        assertTrue(p99Work <= RingsPlanner.WORK_BUDGET / 10, "p99 work " + p99Work + " is a tenth of the budget at most");
        assertTrue(p50 < p50Cap, "p50 " + p50 + " ms of CPU one at a time, under 3 s"
                + (slower > 1.0 ? ", scaled to " + p50Cap + " ms" : "") + machine);
        assertTrue(p99Ms < p99Cap, "p99 " + p99Ms + " ms of CPU in parallel, under 15 s"
                + (slower > 1.0 ? ", scaled to " + p99Cap + " ms" : "") + machine);
        assertTrue(p99Ops <= 3_000, "p99 blocks " + p99Ops + ", a light build");
    }
}
