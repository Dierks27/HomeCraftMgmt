package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golf planner (GEN-SPEC §4.3, Course Variety §3), over 300 seeds of Golf of the Week
 * ({@code EEEMMMMHH}) and Tiny Golf ({@code EEE}): every course passes the full independent check
 * (Adventure Golf's rules: every hole's blocks sound, its witness dropping in E and safe on a pond
 * hole, par 2-4, the sloppy player within par + 1, every rest spot on the lane, the scenery clear of
 * the holes); the sloppy player is never wet; Easy holes are always dry, and Tiny Golf is dry whatever
 * its mix; the variety quota is met; every tier gets a par it likes unless the proven fallback stood
 * in; and the work stays inside the budget ({@code FreshBench}: the 99th percentile course under 60%
 * of it). The fallback hole, with scenery, is valid in every plot of every golf half and far beyond;
 * a stored layout is re-derived from its attempts and lines, without searching, to the same hash; the
 * plan for a seed is pinned by golden hashes; re-rolling one hole never changes another; and a
 * cancelled job stops. A 2,000-seed soak runs under {@code -Phcm.slow}.
 */
class GolfPlannerTest {

    private static final int SEEDS = 300;
    private static final long SECRET = 0x5EC12E7L;

    /** One planned course and what it took. */
    private record Run(Slots.Def slot, int n, Plan plan, long millis) {
    }

    private static final List<Run> RUNS = new ArrayList<>();

    private static long seed(Slots.Def slot, int n) {
        return GenSeed.seed(SECRET, 20725 + n, slot.id(), 0);
    }

    private static PlanInput input(Slots.Def slot, int n) {
        return new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', 20725 + n, 0, seed(slot, n), slot.tierOrMix(), 8, 0,
                null);
    }

    private static Run run(Slots.Def slot, int n) {
        long t0 = System.nanoTime();
        try {
            Plan plan = new GolfPlanner().plan(input(slot, n));
            return new Run(slot, n, plan, (System.nanoTime() - t0) / 1_000_000);
        } catch (GenFailed e) {
            throw new AssertionError(slot.id() + " seed " + n + " failed: " + e.getMessage(), e);
        }
    }

    /** Plan every course once, in parallel (each plan is single-threaded and pure). */
    @BeforeAll
    static void planAll() {
        List<Run> daily = IntStream.range(0, SEEDS).parallel().mapToObj(n -> run(Slots.DAILY_GOLF, n)).toList();
        List<Run> tiny = IntStream.range(0, SEEDS).parallel().mapToObj(n -> run(Slots.TINY_GOLF, n)).toList();
        RUNS.addAll(daily);
        RUNS.addAll(tiny);
    }

    private static PlannedGolf golf(Plan p) {
        return (PlannedGolf) p.course();
    }

    @Test
    void everyCoursePassesTheFullCheckWithParTwoToFourAndTheKidWithinParPlusOne() {
        // plan() already refused any plan the full check doesn't pass; check again from outside:
        // the quick check on every course, the full one (with the kid tree) on every tenth
        List<String> bad = RUNS.parallelStream().flatMap(r -> (r.n() % 10 == 0 ? GolfValidator.problems(r.plan())
                : GolfValidator.quickProblems(r.plan())).stream()
                .map(p -> r.slot().id() + " seed " + r.n() + ": " + p)).toList();
        assertEquals(List.of(), bad, "the independent check never fires on the planner's own plans");
        for (Run r : RUNS) {
            PlannedGolf g = golf(r.plan());
            String what = r.slot().id() + " seed " + r.n();
            assertEquals(r.slot().tierOrMix().length(), g.course().holes().size(), what + ": never missing a hole");
            for (int i = 0; i < g.course().holes().size(); i++) {
                int par = g.course().holes().get(i).par();
                assertTrue(par >= 2 && par <= 4, what + " hole " + (i + 1) + ": par 2-4, not " + par);
                assertEquals(par, g.expert().get(i) + 1, what + " hole " + (i + 1) + ": par is E + 1");
                assertTrue(g.kid().get(i) <= par + 1, what + " hole " + (i + 1) + ": the kid within par + 1");
            }
        }
    }

    @Test
    void everyTierGetsAParItLikesUnlessTheFallbackStoodIn() {
        int holes = 0;
        int fallbacks = 0;
        Map<String, Integer> templates = new TreeMap<>();
        for (Run r : RUNS) {
            PlannedGolf g = golf(r.plan());
            String mix = r.slot().tierOrMix();
            for (int i = 0; i < mix.length(); i++) {
                holes++;
                int par = g.course().holes().get(i).par();
                int attempt = g.attempts().get(i);
                assertTrue(attempt >= 0 && attempt <= GolfPlanner.ATTEMPTS, "attempts 0-12: " + attempt);
                String line = r.plan().summary().get(i + 1);
                templates.merge(line.split(" ")[2], 1, Integer::sum);
                if (attempt == GolfPlanner.ATTEMPTS) {
                    fallbacks++;
                    assertTrue(line.contains("SAFE_STRAIGHT"), "attempt 12 is the fallback: " + line);
                    continue;
                }
                boolean easy = mix.charAt(i) == 'E';
                assertTrue(easy ? par == 2 || par == 3 : par == 3 || par == 4,
                        r.slot().id() + " seed " + r.n() + " hole " + (i + 1) + ": tier " + mix.charAt(i)
                                + " took par " + par);
            }
        }
        assertTrue(fallbacks * 50 < holes, "the fallback is rare: " + fallbacks + " of " + holes);
        for (HoleTemplate t : HoleTemplate.values()) {
            if (t != HoleTemplate.SAFE_STRAIGHT) {
                assertTrue(templates.getOrDefault(t.name(), 0) * 40 > SEEDS, t + " is used: " + templates);
            }
        }
    }

    /** The {@code FreshBench} numbers (§3.11): work, time and blocks per course, p50/p95/p99. */
    @Test
    void freshBenchTheP99CourseIsWithinSixtyPercentOfTheBudget() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            long[] work = RUNS.stream().filter(r -> r.slot() == slot).mapToLong(r -> r.plan().work()).sorted()
                    .toArray();
            long[] ms = RUNS.stream().filter(r -> r.slot() == slot).mapToLong(Run::millis).sorted().toArray();
            long[] ops = RUNS.stream().filter(r -> r.slot() == slot).mapToLong(r -> r.plan().ops().size()).sorted()
                    .toArray();
            long p99Work = work[(int) (work.length * 0.99)];
            long p95Ms = ms[(int) (ms.length * 0.95)];
            long p99Ops = ops[(int) (ops.length * 0.99)];
            System.out.println("FreshBench " + slot.id() + ": work p50 " + work[work.length / 2] + " p95 "
                    + work[(int) (work.length * 0.95)] + " p99 " + p99Work + " max " + work[work.length - 1]
                    + " putts (budget " + GolfPlanner.COURSE_BUDGET + "); time p50 " + ms[ms.length / 2] + " ms, p95 "
                    + p95Ms + " ms, p99 " + ms[(int) (ms.length * 0.99)] + " ms, max " + ms[ms.length - 1] + " ms ("
                    + Runtime.getRuntime().availableProcessors() + " threads); blocks p50 " + ops[ops.length / 2]
                    + " p99 " + p99Ops + " max " + ops[ops.length - 1]);
            assertTrue(p99Work <= GolfPlanner.COURSE_BUDGET * 6 / 10, slot.id() + ": p99 work " + p99Work
                    + " is within 60% of the course budget of " + GolfPlanner.COURSE_BUDGET);
            assertTrue(work[work.length - 1] <= GolfPlanner.COURSE_BUDGET,
                    slot.id() + ": no course goes past its budget, fallbacks included");
            assertTrue(p95Ms < 30_000, slot.id() + ": p95 plan time " + p95Ms + " ms, far under the 120 s kill");
            assertTrue(p99Ops <= 30_000, slot.id() + ": p99 blocks " + p99Ops + ", scenery included, within 30,000"
                    + " (the check's cap is " + GolfValidator.MAX_OPS + ")");
        }
    }

    @Test
    void theVarietyQuotaIsMetAndTheSummarySaysWhatEachCourseHas() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            List<Run> runs = RUNS.stream().filter(r -> r.slot() == slot).toList();
            long dealt = runs.stream().filter(r -> Quota.deal(new GenRandom(r.plan().seed()), slot.tierOrMix(),
                    GolfPlanner.dry(slot)).met() == Quota.COUNTED.size()).count();
            long ended = 0;
            for (Run r : runs) {
                List<String> summary = r.plan().summary();
                String line = summary.get(summary.size() - 1);
                assertTrue(line.startsWith("quota: water "), slot.id() + " seed " + r.n() + ": the last line is the"
                        + " quota's: " + line);
                boolean all = true;
                for (String part : line.substring("quota: ".length(), line.indexOf(" (deal")).split(", ")) {
                    String[] got = part.substring(part.lastIndexOf(' ') + 1).split("/");
                    all &= Integer.parseInt(got[0]) >= Integer.parseInt(got[1]);
                }
                ended += all ? 1 : 0;
            }
            System.out.println(slot.id() + ": the quota's deal met by " + dealt + " of " + runs.size()
                    + " courses; after fallbacks, " + ended);
            assertTrue(dealt * 100 >= runs.size() * 95L, slot.id() + ": the quota is met by at least 95% of deals: "
                    + dealt + " of " + runs.size());
            assertTrue(ended * 100 >= runs.size() * 90L, slot.id() + ": and a fallback rarely breaks it: " + ended
                    + " of " + runs.size());
        }
    }

    @Test
    void theSloppyPlayerIsNeverWetAndEveryRestSpotIsOnTheLane() {
        List<String> bad = RUNS.parallelStream().filter(r -> r.n() % 10 == 3).flatMap(r -> {
            List<String> out = new ArrayList<>();
            PlannedGolf g = golf(r.plan());
            PlanBlocks grid = PlanBlocks.of(r.plan().half(), r.plan().palette(), r.plan().ops());
            for (int i = 0; i < g.course().holes().size(); i++) {
                GolfCourse.Hole h = g.course().holes().get(i);
                LaneMap lane = LaneMap.of(grid, h, GolfPlanner.ALGO);
                String what = r.slot().id() + " seed " + r.n() + " hole " + (i + 1);
                int[] wet = {0};
                int[] off = {0};
                try {
                    KidPolicy.Result k = KidPolicy.evaluate(grid, h, lane, h.par() + 1, Work.unlimited(), res -> {
                        wet[0] += res.penalty() ? 1 : 0;
                        off[0] += !res.inCup() && !GolfValidatorV3.restsOnLane(lane, res.x(), res.y(), res.z()) ? 1 : 0;
                    });
                    if (!k.within()) {
                        out.add(what + ": the kid needs " + k.worst());
                    }
                } catch (GenFailed never) {
                    out.add(what + ": cancelled");
                }
                if (wet[0] > 0 || off[0] > 0) {
                    out.add(what + ": " + wet[0] + " splashes or outs and " + off[0] + " rests off the lane in the kid's"
                            + " tree");
                }
                BallPhysics.Hole area = GolfShot.area(grid, h);
                BallPhysics.Ball ball = GolfShot.tee(grid, h);
                for (Putt p : g.witness().get(i)) {
                    GolfShot.Result res = GolfShot.play(grid, area, ball, p);
                    if (!res.inCup() && !GolfValidatorV3.restsOnLane(lane, res.x(), res.y(), res.z())) {
                        out.add(what + ": the witness rests off the lane");
                    }
                }
            }
            return out.stream();
        }).toList();
        assertEquals(List.of(), bad, "never wet (G-T3), and every rest spot over a lane cell (rule 12)");
    }

    @Test
    void aPuttIntoAPlannedPondCostsAStrokeAndTheBallComesBack() {
        int ponds = 0;
        for (Run r : RUNS.subList(0, 40)) {
            PlannedGolf g = golf(r.plan());
            PlanBlocks grid = PlanBlocks.of(r.plan().half(), r.plan().palette(), r.plan().ops());
            for (int i = 0; i < g.course().holes().size(); i++) {
                GolfCourse.Hole h = g.course().holes().get(i);
                if (LaneMap.of(grid, h, GolfPlanner.ALGO).hazards() == 0) {
                    continue;
                }
                BallPhysics.Hole area = GolfShot.area(grid, h);
                List<double[]> spots = new ArrayList<>(); // the tee, and where the par line rests
                BallPhysics.Ball walk = GolfShot.tee(grid, h);
                spots.add(new double[]{walk.x(), walk.y(), walk.z()});
                for (Putt p : g.witness().get(i)) {
                    GolfShot.Result res = GolfShot.play(grid, area, walk, p);
                    spots.add(new double[]{res.x(), res.y(), res.z()});
                }
                GolfShot.Result splash = null;
                double[] from = null;
                for (double[] at : spots) {
                    for (int yaw = 0; yaw < 360 && splash == null; yaw += 5) {
                        for (int power = 1; power <= BallPhysics.clubs() && splash == null; power++) {
                            GolfShot.Result res = GolfShot.play(grid, area, new BallPhysics.Ball(at[0], at[1], at[2]),
                                    new Putt(yaw, power));
                            splash = res.outcome() == BallPhysics.Outcome.WATER ? res : null;
                            from = at;
                        }
                    }
                }
                String what = r.slot().id() + " seed " + r.n() + " hole " + (i + 1);
                assertNotNull(splash, what + ": some putt finds the pond");
                assertEquals(2, splash.strokes(), what + ": a splash is the putt and a stroke more");
                assertEquals(from[0], splash.x(), 1e-9, what + ": and the ball comes back to where it was putted from");
                assertEquals(from[2], splash.z(), 1e-9, "...");
                ponds++;
            }
        }
        assertTrue(ponds > 20, "enough pond holes were tried: " + ponds);
    }

    @Test
    void easyHolesAreAlwaysDryAndTinyGolfIsDryWhateverItsMix() throws GenFailed {
        for (Run r : RUNS) {
            PlannedGolf g = golf(r.plan());
            PlanBlocks grid = PlanBlocks.of(r.plan().half(), r.plan().palette(), r.plan().ops());
            String mix = r.slot().tierOrMix();
            for (int i = 0; i < mix.length(); i++) {
                if (mix.charAt(i) == 'E' || r.slot() == Slots.TINY_GOLF) {
                    assertEquals(0, LaneMap.of(grid, g.course().holes().get(i), GolfPlanner.ALGO).hazards(),
                            r.slot().id() + " seed " + r.n() + " hole " + (i + 1) + ": no water in play on Easy");
                }
            }
        }
        for (String mix : List.of("MMM", "HHH", "EMH", "MHM")) {
            for (long seed = 0; seed < 12; seed++) {
                Plan p = new GolfPlanner().plan(GolfKit.input(Slots.TINY_GOLF, seed, mix, 0));
                PlanBlocks grid = PlanBlocks.of(p.half(), p.palette(), p.ops());
                for (GolfCourse.Hole h : golf(p).course().holes()) {
                    assertEquals(0, LaneMap.of(grid, h, GolfPlanner.ALGO).hazards(), "Tiny Golf at " + mix + " seed "
                            + seed + ": the four-year-old's course has no water in play, whatever an admin sets");
                }
                assertEquals(List.of(), GolfValidator.quickProblems(p), "and it is sound");
            }
        }
    }

    @Test
    void everyPlotHasItsSceneryAndEveryTeeSignSaysItsFeature() {
        int plots = 0;
        int trees = 0;
        int bare = 0;
        for (Run r : RUNS) {
            Plan p = r.plan();
            PlannedGolf g = golf(p);
            for (int i = 0; i < g.course().holes().size(); i++) {
                int[] at = GolfPlanner.plot(p.half(), i);
                Box b = p.keepClear().get(i);
                long here = p.ops().stream().filter(op -> p.blockOf(op).endsWith("_log[axis=y]")
                        && op.y() == b.minY() + 3 && op.x() >= at[0] && op.x() < at[0] + HoleTemplate.PLOT_X
                        && op.z() >= at[1] && op.z() < at[1] + HoleTemplate.PLOT_Z
                        && !b.contains(op.x(), op.y(), op.z())).count();
                plots++;
                trees += (int) here;
                bare += here == 0 ? 1 : 0;
                assertTrue(here <= GolfScenery.MOST, "at most " + GolfScenery.MOST + " decoration trees a plot: " + here);
                int n = i + 1;
                GolfCourse.Hole h = g.course().holes().get(i);
                assertTrue(p.signs().stream().map(SignText::lines).anyMatch(l -> GenCopy.golfTeeFeature(l, n, h.par())
                        != null), r.slot().id() + " seed " + r.n() + " hole " + n + " has its tee sign");
            }
        }
        System.out.println("scenery: " + trees + " decoration trees on " + plots + " plots, " + bare + " bare");
        assertTrue(trees >= plots * GolfScenery.LEAST, "2-5 trees a plot on average: " + trees + " on " + plots);
        assertTrue(bare * 100 <= plots, "a plot with no room for a tree is rare: " + bare + " of " + plots);
    }

    @Test
    void coursesDifferFromDayToDay() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            Set<String> hashes = new HashSet<>();
            RUNS.stream().filter(r -> r.slot() == slot).forEach(r -> hashes.add(r.plan().hash()));
            assertEquals(SEEDS, hashes.size(), slot.id() + ": every seed made its own layout");
        }
    }

    @Test
    void theSameInputMakesTheSamePlan() throws GenFailed {
        for (int n = 0; n < 3; n++) {
            Plan again = new GolfPlanner().plan(input(Slots.DAILY_GOLF, n));
            Plan first = RUNS.get(n).plan();
            assertEquals(first.hash(), again.hash(), "seed " + n + ": the same layout");
            assertEquals(first.work(), again.work(), "seed " + n + ": after the same counted work");
            assertEquals(first.summary(), again.summary(), "seed " + n + ": said the same way");
            assertEquals(first.ops(), again.ops(), "seed " + n + ": block for block, in order");
        }
    }

    @Test
    void goldenPlansArePinned() throws GenFailed {
        // A change here means the planner makes different courses for the same seed: bump ALGO.
        assertEquals(3, GolfPlanner.ALGO, "the version these hashes belong to");
        Map<Long, String> daily = Map.of(1L, "cf984e43e170", 20725L, "15c15b307eb2",
                0x3f2a91c07d1e55b0L, "e4eb31cf3859");
        for (Map.Entry<Long, String> e : new TreeMap<>(daily).entrySet()) {
            Plan p = new GolfPlanner().plan(GolfKit.input(Slots.DAILY_GOLF, e.getKey()));
            assertEquals(e.getValue(), p.hash(), "Daily Golf seed " + Long.toHexString(e.getKey()));
        }
        Map<Long, String> tiny = Map.of(1L, "a61b98fd5199", 20725L, "44b393ccf0a4",
                0x3f2a91c07d1e55b0L, "dac9542bf147");
        for (Map.Entry<Long, String> e : new TreeMap<>(tiny).entrySet()) {
            Plan p = new GolfPlanner().plan(GolfKit.input(Slots.TINY_GOLF, e.getKey()));
            assertEquals(e.getValue(), p.hash(), "Tiny Golf seed " + Long.toHexString(e.getKey()));
        }
    }

    @Test
    void theFallbackHoleIsValidInEveryPlotAndFarBeyond() throws GenFailed {
        List<Box> halves = new ArrayList<>();
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            halves.add(LegacyBoxes.half(slot, 'A'));
            halves.add(LegacyBoxes.half(slot, 'B'));
        }
        for (int[] origin : new int[][]{{0, 64, 0}, {16, -48, -16}, {-4096, 200, 8192}, {65_536, 100, -65_536},
                {1_048_576, 160, 1_048_576}, {-28_999_936, -48, 28_999_808}, {28_999_808, 280, -28_999_936}}) {
            halves.add(Slots.DAILY_GOLF.half(origin[0], origin[1], origin[2], 'A'));
        }
        int checked = 0;
        for (Box half : halves) {
            int plots = half.sizeZ() >= 128 ? 9 : 3;
            for (int i = 0; i < plots; i++) {
                int[] p = GolfPlanner.plot(half, i);
                HoleLayout l = HoleTemplate.SAFE_STRAIGHT.draw(new GenRandom(i), 'S', p[0], p[1],
                        half.minY() + GolfPlanner.TURF_ABOVE_FLOOR);
                Work spent = Work.unlimited();
                GolfPlanner.Solved s = GolfPlanner.solve(l, 'S', GolfPlanner.ATTEMPTS, spent);
                String what = "the fallback at " + p[0] + "," + p[1];
                assertNotNull(s, what + " is solved, the kid within par + 1");
                assertTrue(spent.used() * 2 <= GolfPlanner.FALLBACK_RESERVE, what + " takes " + spent.used()
                        + " putts, well inside the " + GolfPlanner.FALLBACK_RESERVE + " kept back for it");
                assertEquals(List.of(), GolfValidator.holeProblems(GolfKit.grid(l), l.hole(s.par()), 1),
                        what + " is sound");
                assertTrue(s.par() <= 3 && s.kid() <= s.par() + 1, what + ": par " + s.par() + ", K " + s.kid());
                checked++;
            }
        }
        assertEquals(2 * 9 + 2 * 3 + 7 * 9, checked, "every plot of both golf halves at both origins, and 7 far ones");
        for (Box half : halves) {
            int plots = half.sizeZ() >= 128 ? 9 : 3;
            Slots.Def slot = plots == 9 ? Slots.DAILY_GOLF : Slots.TINY_GOLF;
            List<GolfPlanner.Solved> safe = new ArrayList<>();
            for (int i = 0; i < plots; i++) {
                int[] p = GolfPlanner.plot(half, i);
                safe.add(GolfPlanner.solve(HoleTemplate.SAFE_STRAIGHT.draw(new GenRandom(i), 'S', p[0], p[1],
                        half.minY() + GolfPlanner.TURF_ABOVE_FLOOR), 'S', GolfPlanner.ATTEMPTS, Work.unlimited()));
            }
            PlanInput in = new PlanInput(slot, half, 'A', 20725, 0, 1, slot.tierOrMix(), 8, 0, null);
            Plan all = GolfPlanner.assemble(in, safe, 0);
            assertTrue(all.ops().stream().anyMatch(op -> Palette.isLeaves(all.blockOf(op))), "with its scenery");
            assertEquals(List.of(), GolfValidator.problems(all), "a course of fallbacks with their scenery is sound in "
                    + half.describe());
        }
        long least = GolfPlanner.leastBudget(9);
        Plan spent = new GolfPlanner().plan(GolfKit.input(Slots.DAILY_GOLF, 5, "EEEMMMMHH", least));
        PlannedGolf g = golf(spent);
        assertEquals(9, g.attempts().size(), "with no work to spare the course is still never missing a hole");
        assertEquals(12, g.attempts().get(0), "the first is the fallback: only the fallbacks' work was there");
        assertTrue(spent.work() <= least, "and it keeps to its budget: " + spent.work() + " of " + least);
        assertEquals(List.of(), GolfValidator.problems(spent), "and that course passes the full check");
        GenFailed tooLittle = assertThrows(GenFailed.class, () -> new GolfPlanner().plan(GolfKit.input(Slots.DAILY_GOLF,
                5, "EEEMMMMHH", least - 1)), "below a fallback for every hole there is no plan");
        assertTrue(tooLittle.getMessage().contains(Long.toString(least)), "and it says what the least is: "
                + tooLittle.getMessage());
    }

    @Test
    void aStoredLayoutIsRederivedWithoutSearchingToTheSameHash() throws GenFailed {
        GolfPlanner planner = new GolfPlanner();
        for (int k = 0; k < 12; k++) {
            Run r = RUNS.get(k * 50 % RUNS.size());
            PlannedGolf g = golf(r.plan());
            GenTag tag = tag(r.plan(), g.attempts(), g.witness(), GolfPlanner.ALGO);
            Plan again = planner.rederive(input(r.slot(), r.n()), tag);
            String what = r.slot().id() + " seed " + r.n();
            assertEquals(r.plan().hash(), again.hash(), what + ": the same layout");
            assertEquals(Set.copyOf(blocks(r.plan())), Set.copyOf(blocks(again)), what + ": block for block");
            assertEquals(r.plan().signs(), again.signs(), what + ": the same signs");
            assertEquals(g.course(), golf(again).course(), what + ": the same holes and pars");
            long putts = g.witness().stream().mapToLong(List::size).sum();
            assertEquals(putts, again.work(), what + ": no search, only the stored lines replayed");
            assertEquals(List.of(), golf(again).kid(), what + ": the kid tree isn't run again");
        }
        Run r = RUNS.get(0);
        PlannedGolf g = golf(r.plan());
        assertThrows(GenFailed.class, () -> planner.rederive(input(r.slot(), r.n()),
                tag(r.plan(), g.attempts(), g.witness(), GolfPlanner.ALGO + 1)), "another version can't rebuild it");
        PlanInput otherMix = new PlanInput(r.slot(), LegacyBoxes.half(r.slot(), 'A'), 'A', 1, 0, r.plan().seed(),
                "EEEMMMMHM", 8, 0, null);
        GenFailed mixed = assertThrows(GenFailed.class, () -> planner.rederive(otherMix, tag(r.plan(), g.attempts(),
                g.witness(), GolfPlanner.ALGO)), "a changed mix doesn't rebuild the stored layout");
        assertTrue(mixed.getMessage().contains("mix"), "and the admin reads that the mix may be why: "
                + mixed.getMessage());
        List<List<Putt>> wrong = new ArrayList<>(g.witness());
        wrong.set(0, List.of(new Putt(180, 1)));
        GenFailed missed = assertThrows(GenFailed.class, () -> planner.rederive(input(r.slot(), r.n()),
                tag(r.plan(), g.attempts(), wrong, GolfPlanner.ALGO)), "a stored line that doesn't drop");
        assertTrue(missed.getMessage().contains("hole 1"), "names the hole: " + missed.getMessage());
        List<Integer> bent = new ArrayList<>(g.attempts());
        bent.set(0, 13);
        assertThrows(GenFailed.class, () -> planner.rederive(input(r.slot(), r.n()), tag(r.plan(), bent,
                g.witness(), GolfPlanner.ALGO)), "there is no attempt 13");
        assertThrows(GenFailed.class, () -> planner.rederive(input(r.slot(), r.n()), null), "no tag, nothing");
    }

    @Test
    void aTightBudgetIsACapThatKeepsBackOnlyTheFallbacks() throws GenFailed {
        GolfPlanner planner = new GolfPlanner();
        Plan free = planner.plan(GolfKit.input(Slots.DAILY_GOLF, 3, "EEEMMMMHH", 0));
        long least = GolfPlanner.leastBudget(9);
        for (long budget : new long[]{free.work() + least, free.work() + least + 50_000, 3 * (free.work() + least)}) {
            assertTrue(free.work() + least <= budget, "seed 3 takes " + free.work() + " putts, so " + budget
                    + " is room for all of it and every fallback");
            assertEquals(free.hash(), planner.plan(GolfKit.input(Slots.DAILY_GOLF, 3, "EEEMMMMHH", budget)).hash(),
                    "budget " + budget + " makes the course a free budget makes: only a fallback's worth is kept"
                            + " back a hole, so the first holes are never starved into fallbacks");
        }
        for (long budget : new long[]{least, least + 500, least + free.work() / 3, least + free.work() / 2}) {
            Plan p = planner.plan(GolfKit.input(Slots.DAILY_GOLF, 3, "EEEMMMMHH", budget));
            String what = "budget " + budget + " (" + golf(p).attempts() + ")";
            assertTrue(p.work() <= budget, what + ": the plan keeps to its budget, fallbacks and all: " + p.work());
            assertEquals(9, golf(p).attempts().size(), what + ": never missing a hole");
            assertEquals(List.of(), GolfValidator.problems(p), what + ": and a sound course");
        }
    }

    @Test
    void rerollingOneHoleNeverChangesAnother() throws GenFailed {
        // The quota's deal reads the whole mix, so a changed mix may deal differently; on a seed where
        // both mixes take the same deal, changing hole 9's tier leaves holes 1-8 exactly as they were.
        long seed = 77;
        while (Quota.deal(new GenRandom(seed), "EEEMMMMHH", false).k()
                != Quota.deal(new GenRandom(seed), "EEEMMMMHM", false).k()) {
            seed++;
        }
        PlanInput a = GolfKit.input(Slots.DAILY_GOLF, seed, "EEEMMMMHH", 0);
        PlanInput b = GolfKit.input(Slots.DAILY_GOLF, seed, "EEEMMMMHM", 0);
        PlannedGolf ga = golf(new GolfPlanner().plan(a));
        PlannedGolf gb = golf(new GolfPlanner().plan(b));
        assertEquals(ga.course().holes().subList(0, 8), gb.course().holes().subList(0, 8),
                "changing hole 9 leaves holes 1-8 exactly as they were");
        assertEquals(ga.witness().subList(0, 8), gb.witness().subList(0, 8), "lines and all");
        assertFalse(ga.course().holes().get(8).equals(gb.course().holes().get(8)), "while hole 9 is new");
    }

    @Test
    void plotsRunInSnakeOrderInsideTheHalf() {
        Box half = LegacyBoxes.half(Slots.DAILY_GOLF, 'A');
        List<Box> plots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int[] p = GolfPlanner.plot(half, i);
            Box plot = Box.sized(p[0], half.minY(), p[1], HoleTemplate.PLOT_X, half.sizeY(), HoleTemplate.PLOT_Z);
            assertTrue(half.contains(plot), "plot " + i + " is inside the half");
            for (Box other : plots) {
                assertTrue(other.gap(plot) >= 1, "plots don't touch");
            }
            plots.add(plot);
        }
        assertEquals(half.minX() + 44, GolfPlanner.plot(half, 2)[0], "hole 3 ends the first row");
        assertEquals(GolfPlanner.plot(half, 2)[0], GolfPlanner.plot(half, 3)[0], "hole 4 starts the next row "
                + "above it: snake order");
        Box tiny = LegacyBoxes.half(Slots.TINY_GOLF, 'A');
        assertTrue(tiny.contains(Box.sized(GolfPlanner.plot(tiny, 2)[0], tiny.minY(), GolfPlanner.plot(tiny, 2)[1],
                20, 16, 40)), "Tiny Golf's three plots fit its half");
    }

    @Test
    void badInputsAndACancelledJobFail() {
        GolfPlanner planner = new GolfPlanner();
        assertEquals(Slots.GOLF, planner.id(), "the golf generator");
        assertThrows(GenFailed.class, () -> planner.plan(new PlanInput(Slots.DAILY_PARKOUR_EASY,
                LegacyBoxes.half(Slots.DAILY_PARKOUR_EASY, 'A'), 'A', 1, 0, 1, "easy", 8, 0, null)), "not a golf slot");
        assertThrows(GenFailed.class, () -> planner.plan(GolfKit.input(Slots.DAILY_GOLF, 1, "EEX", 0)),
                "not a mix");
        assertThrows(GenFailed.class, () -> planner.plan(GolfKit.input(Slots.TINY_GOLF, 1, "EEEE", 0)),
                "more holes than Tiny Golf has plots");
        AtomicBoolean stop = new AtomicBoolean(true);
        GenFailed cancelled = assertThrows(GenFailed.class, () -> planner.plan(new PlanInput(Slots.DAILY_GOLF,
                LegacyBoxes.half(Slots.DAILY_GOLF, 'A'), 'A', 1, 0, 1, "EEEMMMMHH", 8, 0, stop::get)),
                "a cancelled job stops");
        assertEquals("cancelled", cancelled.getMessage(), "and says so");
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void soak2000Seeds() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            List<String> bad = IntStream.range(SEEDS, SEEDS + 2000).parallel().mapToObj(n -> run(slot, n))
                    .flatMap(r -> GolfValidator.quickProblems(r.plan()).stream().map(p -> "seed " + r.n() + ": " + p))
                    .toList();
            assertEquals(List.of(), bad, slot.id() + ": 2,000 more seeds, every course valid");
        }
    }

    private static GenTag tag(Plan p, List<Integer> attempts, List<List<Putt>> witness, int algo) {
        return new GenTag(p.slot(), Slots.GOLF, algo, 20725, 0, p.seed(), 'A', p.hash(), 0, 0, 0, attempts,
                witness, 0);
    }

    private static List<String> blocks(Plan p) {
        List<String> out = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            out.add(op.x() + " " + op.y() + " " + op.z() + " " + p.blockOf(op));
        }
        return out;
    }
}
