package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.V4Boxes;
import com.dierks.homecraft.games.golf.GolfCourse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf v4's planner (GOLF-V4-SPEC §3, §6, §7), over 300 seeds of Golf of the Week ({@code EEEMMMMHH},
 * at its pinned 128 x 16 x 224 {@link V4Boxes} box) and of Tiny Golf ({@code EEE}): every course passes
 * the full independent check; each hole's class is the length deal's and it measures its class's
 * par, and the course balance never leaves a par outside its class's ±1 or 2-6 (red-team F01); the
 * course's par is within a stroke of what the first-timer takes; the length and v4 quotas are met,
 * a Swing layup, a Chip layup and a guarded par 3 on every course; every club has a job (red-team
 * F00: median Swing at least 8%, Chip 6%, Drive at most 55%; on every course the gate's Putt, Chip
 * and Swing at least 4% each; and the first-timer with only Tap, Putt and Drive takes at least 1.5
 * strokes a course more than with all five, where on Adventure Golf's courses it doesn't); the kid
 * is within par + 2 (Tiny Golf: par + 1) and never wet;
 * Tiny Golf is dry, short (path at most 22) and par 2-3; planning is well inside the budget and the
 * time (p50 about 4 s, p99 under 20 s, on one planner thread); a stored layout re-derives to the
 * same hash; goldens are pinned; re-rolling one hole changes no other hole's blocks; a cancelled job
 * stops. A 2,000-seed soak runs under {@code -Phcm.slow}.
 */
class GolfPlannerV4Test {

    private static final int SEEDS = 300;
    private static final long SECRET = 0x5EC12E7L;
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();

    /** One planned course and the CPU time it took (one planner thread). */
    private record Run(Slots.Def slot, int n, Plan plan, long millis) {
    }

    private static final List<Run> RUNS = new ArrayList<>();

    private static long seed(Slots.Def slot, int n) {
        return GenSeed.seed(SECRET, 20725 + n, slot.id(), 0);
    }

    static PlanInput input(Slots.Def slot, int n) {
        return input(slot, seed(slot, n), slot.tierOrMix(), 0);
    }

    static PlanInput input(Slots.Def slot, long seed, String mix, long budget) {
        return new PlanInput(slot, V4Boxes.half(slot, 'A'), 'A', 20725, 0, seed, mix, 8, budget, null);
    }

    private static Run run(Slots.Def slot, int n) {
        long t0 = CPU.getCurrentThreadCpuTime();
        try {
            Plan plan = new GolfPlanner().plan(input(slot, n));
            return new Run(slot, n, plan, (CPU.getCurrentThreadCpuTime() - t0) / 1_000_000);
        } catch (GenFailed e) {
            throw new AssertionError(slot.id() + " seed " + n + " failed: " + e.getMessage(), e);
        }
    }

    /** Plan every course once, in parallel (each plan is single-threaded and pure). */
    @BeforeAll
    static void planAll() {
        RUNS.addAll(IntStream.range(0, SEEDS).parallel().mapToObj(n -> run(Slots.DAILY_GOLF, n)).toList());
        RUNS.addAll(IntStream.range(0, SEEDS).parallel().mapToObj(n -> run(Slots.TINY_GOLF, n)).toList());
    }

    private static List<Run> runs(Slots.Def slot) {
        return RUNS.stream().filter(r -> r.slot() == slot).toList();
    }

    private static PlannedGolf golf(Plan p) {
        return (PlannedGolf) p.course();
    }

    /** The planner's per-hole summary lines (after the header). */
    private static List<String> holeLines(Plan p) {
        return p.summary().stream().filter(l -> l.startsWith("hole ")).toList();
    }

    /** A hole line's mean ("mean 3.42"). */
    private static double mean(String line) {
        int at = line.indexOf(" - mean ") + " - mean ".length();
        return Double.parseDouble(line.substring(at, line.indexOf(',', at)));
    }

    /** A hole line's class letter ("hole 3: M M_SAND ..."). */
    private static char cls(String line) {
        return line.charAt(line.indexOf(": ") + 2);
    }

    private static int kidOver(Slots.Def slot) {
        return slot == Slots.TINY_GOLF ? 1 : 2;
    }

    @Test
    void everyCoursePassesTheFullCheckAndEveryHoleItsBounds() {
        List<String> bad = RUNS.parallelStream().flatMap(r -> (r.n() % 10 == 0 ? GolfValidator.problems(r.plan())
                : GolfValidator.quickProblems(r.plan())).stream().map(p -> r.slot().id() + " seed " + r.n() + ": " + p))
                .toList();
        assertEquals(List.of(), bad, "the independent check never fires on the planner's own plans");
        for (Run r : RUNS) {
            PlannedGolf g = golf(r.plan());
            String what = r.slot().id() + " seed " + r.n();
            assertEquals(GolfPlanner.ALGO, r.plan().algo(), what + ": a Golf v4 plan");
            assertEquals(4, GolfPlanner.ALGO, "the version these courses belong to");
            assertEquals(r.slot().tierOrMix().length(), g.course().holes().size(), what + ": never missing a hole");
            for (int i = 0; i < g.course().holes().size(); i++) {
                int par = g.course().holes().get(i).par();
                int e = g.expert().get(i);
                assertTrue(par >= 2 && par <= (r.slot() == Slots.TINY_GOLF ? 3 : 6), what + " hole " + (i + 1)
                        + ": par " + par);
                assertTrue(e >= 1 && e <= par, what + " hole " + (i + 1) + ": 1 <= E <= par (E " + e + ", par " + par + ")");
                assertTrue(g.kid().get(i) <= par + kidOver(r.slot()), what + " hole " + (i + 1) + ": the kid within par + "
                        + kidOver(r.slot()));
            }
        }
    }

    @Test
    void eachHoleTakesTheLengthDealsClassAndMeasuresItsPar() {
        int holes = 0;
        int exact = 0;
        int fallbacks = 0;
        Map<String, Integer> mixes = new TreeMap<>();
        Map<String, Integer> recipes = new TreeMap<>();
        for (Run r : RUNS) {
            String mix = r.slot().tierOrMix();
            boolean tiny = r.slot() == Slots.TINY_GOLF;
            List<LengthClass> want = DealV4.classes(new GenRandom(r.plan().seed()), mix, tiny);
            List<String> lines = holeLines(r.plan());
            PlannedGolf g = golf(r.plan());
            int[] count = new int[4];
            for (int i = 0; i < mix.length(); i++) {
                String what = r.slot().id() + " seed " + r.n() + " hole " + (i + 1) + " (" + lines.get(i) + ")";
                LengthClass c = want.get(i);
                count[c.ordinal()]++;
                assertEquals(c.letter(), cls(lines.get(i)), what + ": the deal's class");
                int par = g.course().holes().get(i).par();
                holes++;
                recipes.merge(lines.get(i).split(" ")[3], 1, Integer::sum);
                if (g.attempts().get(i) == GolfPlannerV4.ATTEMPTS) {
                    fallbacks++;
                    assertTrue(lines.get(i).contains("SAFE_" + c.letter()), what + ": attempt 12 is its class's fallback");
                }
                assertEquals(c.par, (int) Math.floor(mean(lines.get(i)) + 0.5), what + ": it measures its class's par");
                assertClassPar(par, c, tiny ? 3 : 6, what);
                assertEquals(par != c.par, lines.get(i).contains("its class's " + c.par + ", a stroke "
                        + (par > c.par ? "up" : "down") + " to balance the course"), what + ": says when the balance moved it");
                exact += par == c.par ? 1 : 0;
            }
            mixes.merge("S" + count[0] + " M" + count[1] + " L" + count[2] + " X" + count[3], 1, Integer::sum);
            if (!tiny) {
                assertTrue(Arrays.equals(count, new int[]{2, 3, 3, 1}) || Arrays.equals(count, new int[]{2, 3, 2, 2}),
                        "Golf of the Week seed " + r.n() + " is S2 M3 L3 X1 or S2 M3 L2 X2: " + Arrays.toString(count));
            } else {
                assertTrue(count[2] == 0 && count[3] == 0 && count[0] >= 1 && count[1] >= 1,
                        "Tiny Golf seed " + r.n() + " is S, S, M or S, M, M: " + Arrays.toString(count));
            }
        }
        System.out.println("Golf v4 class mixes " + mixes + "; recipes " + recipes + "; " + fallbacks + " fallbacks of "
                + holes + " holes; par = the class's par on " + exact);
        assertTrue(exact * 100 >= holes * 80L, "the balance moves few holes: " + exact + " of " + holes
                + " take their class's par exactly");
        assertTrue(fallbacks * 100 <= holes, "a fallback stands in for at most 1% of holes: " + fallbacks);
        long xx = mixes.getOrDefault("S2 M3 L2 X2", 0);
        assertTrue(xx >= SEEDS / 6 && xx <= SEEDS / 2, "Hard deals two X holes about one time in three: " + xx);
    }

    /** Red-team F01: a hole's par is its class's, or a stroke off it by the course balance, and 2-{@code most}. */
    private static void assertClassPar(int par, LengthClass c, int most, String what) {
        assertTrue(par >= Math.max(GolfCourse.MIN_PAR, c.par - 1) && par <= Math.min(most, c.par + 1), what + ": par "
                + par + " is its class's " + c.par + " or a stroke off it, and 2-" + most + " (never par 1)");
    }

    @Test
    void noHoleIsEverPar1OrMoreThanAStrokeFromItsClassUnderTheBalanceOnEveryPath() throws GenFailed {
        int moved = 0;
        int again = 0;
        for (Run r : RUNS) {
            boolean tiny = r.slot() == Slots.TINY_GOLF;
            List<LengthClass> classes = DealV4.classes(new GenRandom(r.plan().seed()), r.slot().tierOrMix(), tiny);
            List<String> lines = holeLines(r.plan());
            int changed = 0;
            for (int i = 0; i < classes.size(); i++) {
                int par = golf(r.plan()).course().holes().get(i).par();
                assertClassPar(par, classes.get(i), tiny ? 3 : 6, r.slot().id() + " seed " + r.n() + " hole " + (i + 1));
                changed += par != classes.get(i).par ? 1 : 0;
                again += lines.get(i).contains("drawn again to settle the course") ? 1 : 0;
            }
            moved += changed;
            String header = r.plan().summary().get(0);
            assertTrue(changed == 0 ? header.contains("every hole its class's par") : header.contains(changed + " hole"
                    + (changed == 1 ? "" : "s") + " a stroke off its class's par"), "the summary says how many the"
                    + " balance moved: " + header);
        }
        System.out.println("Golf v4 course balance: " + moved + " holes a stroke off their class's par, " + again
                + " holes drawn again to settle a course, over " + RUNS.size() + " courses");
        // the fallback path: a budget of only the fallbacks plans most holes as their fallback
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            boolean tiny = slot == Slots.TINY_GOLF;
            for (int n = 0; n < 6; n++) {
                PlanInput in = input(slot, seed(slot, n), slot.tierOrMix(), GolfPlannerV4.leastBudget(slot.tierOrMix()
                        .length()));
                Plan p = new GolfPlanner().plan(in);
                List<LengthClass> classes = DealV4.classes(new GenRandom(p.seed()), slot.tierOrMix(), tiny);
                for (int i = 0; i < classes.size(); i++) {
                    assertClassPar(golf(p).course().holes().get(i).par(), classes.get(i), tiny ? 3 : 6, slot.id()
                            + " seed " + n + " on the least budget, hole " + (i + 1));
                }
            }
        }
    }

    /**
     * Red-team F01: settling a course (made-up holes): a Tiny Golf course of three M holes each at 3.45
     * can't be balanced by moving a par (3 is Tiny Golf's most), so a hole is drawn again rather than
     * any par going to 4; a hole the balance lowers below its witness is drawn again; a course whose
     * every hole is its fallback can't be settled; the club gate draws again the hole that chose the
     * missing club least, and only when it applies.
     */
    @Test
    void settlingACourseDrawsAHoleAgainAndNeverMovesAParOutOfItsClass() {
        List<Putt> two = List.of(new Putt(0, 3), new Putt(0, 1));
        long[] clubs = {0, 10, 10, 10, 10, 10};
        GolfPlannerV4.Solved[] tiny = new GolfPlannerV4.Solved[3];
        for (int i = 0; i < 3; i++) {
            tiny[i] = new GolfPlannerV4.Solved(0, null, LengthClass.M, 3.45, two, 3, clubs);
        }
        assertArrayEquals(new int[]{3, 3, 3}, GolfPlannerV4.pars(tiny, 3), "Tiny Golf's par 3 can't go up");
        GolfPlannerV4.Settle s = GolfPlannerV4.settle(tiny, 3, 1, false);
        assertNotNull(s, "a course 1.35 under its means isn't settled");
        assertEquals(0, s.hole(), "the hole furthest under (the first on a tie) is drawn again: " + s.words());
        assertFalse(s.gate(), "for the balance, not the gate");
        GolfPlannerV4.Solved[] fallbacks = new GolfPlannerV4.Solved[3];
        for (int i = 0; i < 3; i++) {
            fallbacks[i] = new GolfPlannerV4.Solved(GolfPlannerV4.ATTEMPTS, null, LengthClass.M, 3.45, two, 3, clubs);
        }
        assertEquals(-1, GolfPlannerV4.settle(fallbacks, 3, 1, false).hole(), "every hole its fallback: nothing to draw");
        // 2.50, 3.60, 4.70 round to 3, 4, 5 = 12, the means 10.80: the balance lowers the M hole to 2...
        GolfPlannerV4.Solved[] over = {
                new GolfPlannerV4.Solved(0, null, LengthClass.M, 2.50, List.of(new Putt(0, 5), new Putt(0, 2),
                        new Putt(0, 1)), 3, clubs),
                new GolfPlannerV4.Solved(0, null, LengthClass.L, 3.60, two, 4, clubs),
                new GolfPlannerV4.Solved(0, null, LengthClass.X, 4.70, two, 5, clubs)};
        assertArrayEquals(new int[]{2, 4, 5}, GolfPlannerV4.pars(over, 6), "the hole nearest rounding down goes a stroke"
                + " under its class's 3");
        assertEquals(0, GolfPlannerV4.settle(over, 6, 2, false).hole(), "...where its 3-putt line is over par: drawn again");
        // the gate: no Chip on the course
        long[] noChip = {0, 10, 10, 0, 10, 10};
        long[] someChip = {0, 10, 10, 1, 10, 10};
        GolfPlannerV4.Solved[] gated = {
                new GolfPlannerV4.Solved(0, null, LengthClass.M, 3.0, two, 3, someChip),
                new GolfPlannerV4.Solved(0, null, LengthClass.M, 3.0, two, 3, noChip),
                new GolfPlannerV4.Solved(0, null, LengthClass.M, 3.0, two, 3, someChip)};
        GolfPlannerV4.Settle g = GolfPlannerV4.settle(gated, 6, 2, true);
        assertNotNull(g, "Chip under 4% of the shots: not settled");
        assertTrue(g.gate() && g.hole() == 1, "the gate draws again the hole that chose Chip least: " + g);
        assertNull(GolfPlannerV4.settle(gated, 6, 2, false), "the gate holds only where it applies");
        GolfPlannerV4.Solved plenty = new GolfPlannerV4.Solved(0, null, LengthClass.M, 3.0, two, 3,
                new long[]{0, 10, 10, 2, 10, 10});
        assertNull(GolfPlannerV4.settle(new GolfPlannerV4.Solved[]{plenty, plenty, plenty}, 6, 2, true),
                "Chip at 6 of 126 shots (4.8%): met");
    }

    @Test
    void theCoursesParIsWithinAStrokeOfWhatTheOrdinaryPlayerTakes() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            double sum = 0;
            for (Run r : runs(slot)) {
                double means = 0;
                for (String line : holeLines(r.plan())) {
                    means += mean(line);
                }
                int par = golf(r.plan()).course().holes().stream().mapToInt(GolfCourse.Hole::par).sum();
                // (the summary prints each mean to two places: nine of them can be 0.045 off)
                assertTrue(Math.abs(par - means) <= 1.05, slot.id() + " seed " + r.n() + ": par " + par
                        + " is within a stroke of the ordinary player's " + means);
                sum += means - par;
            }
            double avg = sum / SEEDS;
            System.out.println(slot.id() + ": the ordinary player's mean over par, per course " + avg);
            // Golf of the Week's first-timer lands about half a stroke over (the spec's prototype: +0.64; its
            // greens' run-out leaves a missed putt further to come back); Tiny Golf's three holes and its par
            // of at most 3 leave the balance little to move, so the child lands a little over (within a stroke)
            double most = slot == Slots.TINY_GOLF ? 1.0 : 0.75;
            assertTrue(Math.abs(avg) <= most, slot.id() + ": on average the player who sets par lands on it: " + avg);
        }
        int[] pars = runs(Slots.DAILY_GOLF).stream().mapToInt(r -> golf(r.plan()).course().holes().stream()
                .mapToInt(GolfCourse.Hole::par).sum()).sorted().toArray();
        assertTrue(pars[0] >= 28 && pars[pars.length - 1] <= 33, "Golf of the Week's par is about 30-31: "
                + pars[0] + "-" + pars[pars.length - 1]);
        int[] tiny = runs(Slots.TINY_GOLF).stream().mapToInt(r -> golf(r.plan()).course().holes().stream()
                .mapToInt(GolfCourse.Hole::par).sum()).sorted().toArray();
        assertTrue(tiny[0] >= 6 && tiny[tiny.length - 1] <= 9, "Tiny Golf's three holes are par 6-9");
    }

    @Test
    void theLengthAndVarietyQuotasAreMet() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            List<Run> runs = runs(slot);
            boolean tiny = slot == Slots.TINY_GOLF;
            long dealt = runs.stream().filter(r -> DealV4.deal(new GenRandom(r.plan().seed()), slot.tierOrMix(), tiny)
                    .met() == DealV4.COUNTED.size()).count();
            long ended = 0;
            for (Run r : runs) {
                String line = r.plan().summary().stream().filter(l -> l.startsWith("quota: ")).findFirst().orElse("");
                assertTrue(line.startsWith("quota: water "), slot.id() + " seed " + r.n() + ": the summary has the quota");
                boolean all = true;
                for (String part : line.substring("quota: ".length(), line.indexOf(" (deal")).split(", ")) {
                    String[] got = part.substring(part.lastIndexOf(' ') + 1).split("/");
                    all &= Integer.parseInt(got[0]) >= Integer.parseInt(got[1]);
                }
                ended += all ? 1 : 0;
                if (!tiny) {
                    assertTrue(line.contains("layup") && line.contains("three legs") && line.contains("two legs"),
                            "Golf v4's quota counts layups and legs: " + line);
                }
            }
            System.out.println(slot.id() + ": the v4 quota's deal met by " + dealt + " of " + runs.size()
                    + "; after fallbacks and second recipes, " + ended);
            assertEquals(runs.size(), dealt, slot.id() + ": every deal meets the quota");
            assertTrue(ended * 100 >= runs.size() * 95L, slot.id() + ": and a later attempt rarely breaks it: " + ended);
            if (!tiny) {
                long layups = runs.stream().filter(r -> {
                    String line = r.plan().summary().stream().filter(l -> l.startsWith("quota: ")).findFirst().orElse("");
                    return line.contains("layup 1/1") || line.contains("layup 2/1");
                }).count();
                for (Quota.Feature f : DealV4.PINNED) {
                    long with = runs.stream().filter(r -> holeLines(r.plan()).stream().anyMatch(l -> l.contains(f.words())))
                            .count();
                    assertTrue(with * 100 >= runs.size() * 99L, "Golf of the Week: a " + f.words() + " on (nearly) every"
                            + " course: " + with + " of " + runs.size());
                }
                assertTrue(layups > 0, "the summary counts layups");
            }
        }
        Map<Quota.Feature, Integer> t = DealV4.table(9);
        assertEquals(1, t.get(Quota.Feature.LAYUP), "every 9-hole course has a Swing layup");
        assertEquals(1, t.get(Quota.Feature.CHIP_LAYUP), "a Chip layup (red-team F00: layups 2)");
        assertEquals(1, t.get(Quota.Feature.GUARDED), "and a guarded par 3");
        assertEquals(1, t.get(Quota.Feature.THREE_LEGS), "a hole of three legs");
        assertEquals(3, t.get(Quota.Feature.TWO_LEGS), "and three of two (G3)");
        assertEquals(3, t.get(Quota.Feature.HEIGHT), "plus Adventure Golf's height 3");
    }

    @Test
    void everyClubHasAJobForTheFirstTimer() {
        String[] names = {"Tap", "Putt", "Chip", "Swing", "Drive"};
        List<int[]> shares = new ArrayList<>();
        for (Run r : runs(Slots.DAILY_GOLF)) {
            String line = r.plan().summary().stream().filter(l -> l.startsWith("clubs: ")).findFirst().orElse("");
            assertTrue(line.startsWith("clubs: Tap "), "seed " + r.n() + ": the summary says the clubs: " + line);
            int[] s = new int[5];
            String[] parts = line.substring("clubs: ".length(), line.indexOf(';')).split(", ");
            for (int c = 0; c < 5; c++) {
                assertTrue(parts[c].startsWith(names[c] + " "), "in club order: " + line);
                s[c] = Integer.parseInt(parts[c].substring(names[c].length() + 1, parts[c].length() - 1));
            }
            assertTrue(line.endsWith("; Putt, Chip, Swing 4%+ each: met"), "seed " + r.n() + ": the course meets the club"
                    + " gate, and says so: " + line);
            for (int c = 1; c <= 3; c++) {
                assertTrue(s[c] >= 4, "seed " + r.n() + ": " + names[c] + " has a job on this course: " + line);
            }
            shares.add(s);
        }
        int[] median = new int[5];
        for (int c = 0; c < 5; c++) {
            int cc = c;
            int[] v = shares.stream().mapToInt(s -> s[cc]).sorted().toArray();
            median[c] = v[v.length / 2];
        }
        System.out.println("Golf v4 club shares, median course: Tap " + median[0] + "%, Putt " + median[1] + "%, Chip "
                + median[2] + "%, Swing " + median[3] + "%, Drive " + median[4] + "%");
        for (int c = 0; c < 5; c++) {
            assertTrue(median[c] >= 4, names[c] + " has a job: chosen for " + median[c] + "% of the median course's shots");
        }
        assertTrue(median[3] >= 8, "Swing has a real job: " + median[3] + "% (at least 8)");
        assertTrue(median[2] >= 6, "and Chip: " + median[2] + "% (at least 6)");
        assertTrue(median[4] <= 55, "Drive doesn't do it all: " + median[4] + "% (at most 55)");
        for (Run r : runs(Slots.TINY_GOLF)) {
            String line = r.plan().summary().stream().filter(l -> l.startsWith("clubs: ")).findFirst().orElse("");
            assertFalse(line.contains("each:"), "Tiny Golf (the child's par) has no club gate: " + line);
        }
    }

    /**
     * Red-team F00: what Chip and Swing are worth. The first-timer limited to Tap, Putt and Drive (its
     * aim and slips as ever: a wrong club is the next one up or down in its bag, or the one it chose)
     * takes at least 1.5 strokes a course more than with all five, on average over the 300 courses; so
     * does a player who never takes the wrong club (the red-team's measure, on 60). On Adventure Golf's
     * courses — the ones the owner found needed only two clubs — the same player loses under 1.5.
     */
    @Test
    void chipAndSwingAreWorthAtLeastAStrokeAndAHalfACourse() {
        double[] v4 = worth(runs(Slots.DAILY_GOLF).stream().map(Run::plan).toList());
        List<Plan> v3 = IntStream.range(0, 40).parallel().mapToObj(i -> {
            Slots.Def slot = Slots.DAILY_GOLF;
            PlanInput in = new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', 20725 + i, 0,
                    GenSeed.seed(SECRET, 20725 + i, slot.id(), 0), slot.tierOrMix(), 8, 0, null);
            try {
                return GolfPlanner.v3().plan(in);
            } catch (GenFailed e) {
                throw new AssertionError(e.getMessage(), e);
            }
        }).toList();
        double[] old = worth(v3);
        System.out.println(String.format(Locale.ROOT, "Golf v4: the first-timer without Chip and Swing takes %+.2f strokes"
                + " a course (min %+.2f; with a steady hand %+.2f); on Adventure Golf %+.2f (steady %+.2f)", v4[0], v4[1],
                v4[2], old[0], old[2]));
        assertTrue(v4[0] >= 1.5, "without Chip and Swing the first-timer loses at least 1.5 strokes a course: " + v4[0]);
        assertTrue(v4[2] >= 1.5, "and so does a steady hand: " + v4[2]);
        assertTrue(old[0] < 1.5, "while on Adventure Golf's courses it doesn't (the owner's two clubs): " + old[0]);
    }

    /**
     * {mean, min, steady mean} of Σ (μ with Tap, Putt and Drive − μ with all five) a course: the mean and
     * min over {@code plans}, the steady hand's over the first 60 of them (it costs as much again).
     */
    private static double[] worth(List<Plan> plans) {
        double[][] each = IntStream.range(0, plans.size()).parallel().mapToObj(k -> {
            Plan p = plans.get(k);
            PlannedGolf g = golf(p);
            PlanBlocks grid = PlanBlocks.of(p.half(), p.palette(), p.ops());
            double d = 0;
            double steady = 0;
            for (GolfCourse.Hole h : g.course().holes()) {
                LaneMap lane = LaneMap.of(grid, h, p.algo());
                try {
                    OrdinaryPar.Model m = OrdinaryPar.Model.FIRST_TIMER;
                    d += OrdinaryPar.mean(grid, h, lane, m, OrdinaryPar.NO_CHIP_NO_SWING, Work.unlimited())
                            - OrdinaryPar.mean(grid, h, lane, m, Work.unlimited());
                    if (k < 60) {
                        steady += OrdinaryPar.mean(grid, h, lane, m, OrdinaryPar.NO_CHIP_NO_SWING, false,
                                Work.unlimited()) - OrdinaryPar.mean(grid, h, lane, m, OrdinaryPar.ALL_CLUBS, false,
                                Work.unlimited());
                    }
                } catch (GenFailed never) {
                    throw new AssertionError(never);
                }
            }
            return new double[]{d, steady};
        }).toArray(double[][]::new);
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double steady = 0;
        for (double[] e : each) {
            sum += e[0];
            min = Math.min(min, e[0]);
            steady += e[1];
        }
        return new double[]{sum / each.length, min, steady / Math.min(60, each.length)};
    }

    @Test
    void theKidIsWithinItsBoundNeverWetAndAlwaysOnTheLane() {
        List<String> bad = RUNS.parallelStream().filter(r -> r.n() % 10 == 3).flatMap(r -> {
            List<String> out = new ArrayList<>();
            PlannedGolf g = golf(r.plan());
            PlanBlocks grid = PlanBlocks.of(r.plan().half(), r.plan().palette(), r.plan().ops());
            for (int i = 0; i < g.course().holes().size(); i++) {
                GolfCourse.Hole h = g.course().holes().get(i);
                LaneMap lane = LaneMap.of(grid, h, GolfPlanner.ALGO);
                for (String p : GolfValidatorV4.kidProblems(grid, h, lane, h.par() + kidOver(r.slot()), i + 1)) {
                    out.add(r.slot().id() + " seed " + r.n() + ": " + p);
                }
            }
            return out.stream();
        }).toList();
        assertEquals(List.of(), bad, "the kid is within par + 2 (Tiny Golf par + 1), never wet, always on the lane");
    }

    @Test
    void tinyGolfIsDryShortAndKidEasy() {
        for (Run r : runs(Slots.TINY_GOLF)) {
            PlannedGolf g = golf(r.plan());
            PlanBlocks grid = PlanBlocks.of(r.plan().half(), r.plan().palette(), r.plan().ops());
            for (int i = 0; i < g.course().holes().size(); i++) {
                GolfCourse.Hole h = g.course().holes().get(i);
                LaneMap lane = LaneMap.of(grid, h, GolfPlanner.ALGO);
                String what = "Tiny Golf seed " + r.n() + " hole " + (i + 1);
                assertEquals(0, lane.hazards(), what + ": no water in play");
                assertTrue(GolfPlannerV4.path(grid, h, lane) <= GolfPlannerV4.TINY_PATH + 1e-9, what + ": at most 22 long");
                assertTrue(h.par() == 2 || h.par() == 3, what + ": par 2-3");
            }
            for (BlockOp op : r.plan().ops()) {
                String b = r.plan().blockOf(op);
                if (Palette.poolWater(b)) {
                    assertTrue(op.x() < r.plan().half().minX() || insideNoHole(r.plan(), op), "Tiny Golf's only water is a"
                            + " pond to look at, outside every hole");
                }
            }
        }
        assertThrows(GenFailed.class, () -> new GolfPlanner().plan(input(Slots.TINY_GOLF, 1, "EEEE", 0)),
                "more holes than Tiny Golf has plots");
        for (String mix : List.of("MMM", "HHH", "EMH")) {
            for (int n = 0; n < 4; n++) {
                Plan p = plan(input(Slots.TINY_GOLF, seed(Slots.TINY_GOLF, n), mix, 0));
                PlanBlocks grid = PlanBlocks.of(p.half(), p.palette(), p.ops());
                for (GolfCourse.Hole h : golf(p).course().holes()) {
                    assertEquals(0, LaneMap.of(grid, h, GolfPlanner.ALGO).hazards(), "Tiny Golf at " + mix + " stays dry");
                    assertTrue(h.par() <= 3, "and par 3 at most");
                }
            }
        }
    }

    private static boolean insideNoHole(Plan p, BlockOp op) {
        for (GolfCourse.Hole h : golf(p).course().holes()) {
            Box b = Box.of(h.corner1().x(), h.corner1().y(), h.corner1().z(), h.corner2().x(), h.corner2().y(),
                    h.corner2().z());
            if (b.contains(op.x(), b.minY(), op.z())) {
                return false;
            }
        }
        return true;
    }

    private static Plan plan(PlanInput in) {
        try {
            return new GolfPlanner().plan(in);
        } catch (GenFailed e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    /** The {@code FreshBench} numbers (§6.2, §6.3): work, CPU time and blocks per course, p50/p99. */
    @Test
    void freshBenchPlanningIsWellInsideItsBudgetAndTime() {
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            long[] work = runs(slot).stream().mapToLong(r -> r.plan().work()).sorted().toArray();
            long[] ms = runs(slot).stream().mapToLong(Run::millis).sorted().toArray();
            long[] ops = runs(slot).stream().mapToLong(r -> r.plan().ops().size()).sorted().toArray();
            long p99Work = work[(int) (work.length * 0.99)];
            long p50Ms = ms[ms.length / 2];
            long p99Ms = ms[(int) (ms.length * 0.99)];
            long p99Ops = ops[(int) (ops.length * 0.99)];
            System.out.println("FreshBench golf v4 " + slot.id() + ": work p50 " + work[work.length / 2] + " p99 " + p99Work
                    + " max " + work[work.length - 1] + " putts (budget " + GolfPlanner.COURSE_BUDGET + "); CPU p50 "
                    + p50Ms + " ms, p99 " + p99Ms + " ms, max " + ms[ms.length - 1] + " ms (planning and its own full"
                    + " check); blocks p50 " + ops[ops.length / 2] + " p99 " + p99Ops + " max " + ops[ops.length - 1]);
            assertTrue(p99Work <= GolfPlanner.COURSE_BUDGET / 10, slot.id() + ": p99 work " + p99Work
                    + " is a tenth of the course budget at most");
            assertTrue(p50Ms < 4_000, slot.id() + ": p50 " + p50Ms + " ms of CPU, under the spec's 4 s");
            assertTrue(p99Ms < 20_000, slot.id() + ": p99 " + p99Ms + " ms of CPU, under 20 s");
            assertTrue(p99Ops <= 30_000, slot.id() + ": p99 blocks " + p99Ops + ", scenery included, well within the"
                    + " check's cap of " + GolfValidator.MAX_OPS);
        }
    }

    @Test
    void everyPlotHasItsSceneryAndEveryTeeSignItsFeatureAndWhichWayItTurns() {
        int doglegs = 0;
        for (Run r : RUNS.subList(0, 60)) {
            Plan p = r.plan();
            PlannedGolf g = golf(p);
            PlotGrid grid = PlotGrid.of(p.slot(), p.algo());
            for (int i = 0; i < g.course().holes().size(); i++) {
                GolfCourse.Hole h = g.course().holes().get(i);
                int number = i + 1;
                SignText sign = p.signs().get(i);
                GenCopy.TeeFeature f = GenCopy.golfTeeFeature(sign.lines(), number, h.par());
                assertNotNull(f, r.slot().id() + " seed " + r.n() + " hole " + number + ": its sign says its par");
                if (f == GenCopy.TeeFeature.DOGLEG_LEFT || f == GenCopy.TeeFeature.DOGLEG_RIGHT) {
                    doglegs++;
                    boolean left = h.cup().x() > Math.floor(h.tee().x()); // facing +Z from the tee, +X is the left
                    assertEquals(left, f == GenCopy.TeeFeature.DOGLEG_LEFT, "hole " + number + "'s sign says the way it"
                            + " turns");
                }
                Box plot = grid.plotBox(p.half(), i, p.half().minY(), p.half().maxY());
                Set<Long> trunks = new HashSet<>();
                for (BlockOp op : p.ops()) {
                    if (plot.contains(op.x(), op.y(), op.z()) && Palette.holdsLeaves(p.blockOf(op))
                            && !Palette.isLeaves(p.blockOf(op)) && p.blockOf(op).contains("_log")
                            && insideNoHole(p, op)) {
                        trunks.add(Palette.blockKey(op.x(), 0, op.z()));
                    }
                }
                boolean v4 = grid == PlotGrid.V4;
                assertTrue(trunks.size() >= (v4 ? 3 : 1) && trunks.size() <= (v4 ? GolfScenery.MOST_V4 : GolfScenery.MOST),
                        r.slot().id() + " seed " + r.n() + " plot " + number + " has " + trunks.size() + " scenery trees");
            }
        }
        assertTrue(doglegs > 0, "some doglegs said which way they turn");
    }

    @Test
    void theSameInputMakesTheSamePlanAndCoursesDifferFromWeekToWeek() throws GenFailed {
        for (int n = 0; n < 3; n++) {
            Plan first = RUNS.get(n).plan();
            Plan again = new GolfPlanner().plan(input(Slots.DAILY_GOLF, n));
            assertEquals(first.hash(), again.hash(), "seed " + n + ": the same layout");
            assertEquals(first.ops(), again.ops(), "seed " + n + ": block for block, in order");
            assertEquals(first.summary(), again.summary(), "seed " + n + ": said the same way");
        }
        for (Slots.Def slot : List.of(Slots.DAILY_GOLF, Slots.TINY_GOLF)) {
            Set<String> hashes = new HashSet<>();
            runs(slot).forEach(r -> hashes.add(r.plan().hash()));
            assertEquals(SEEDS, hashes.size(), slot.id() + ": every seed made its own layout");
        }
    }

    @Test
    void goldenPlansArePinnedAtTheV4Boxes() throws GenFailed {
        // A change here means the planner makes different courses for the same seed: bump ALGO.
        assertEquals(4, GolfPlanner.ALGO, "the version these hashes belong to");
        Map<Long, String> daily = Map.of(1L, "785c5005a1c6", 20725L, "b28b1af8cd19", 0x3f2a91c07d1e55b0L,
                "6a8eed122137");
        for (Map.Entry<Long, String> e : new TreeMap<>(daily).entrySet()) {
            Plan p = new GolfPlanner().plan(input(Slots.DAILY_GOLF, e.getKey(), Slots.DAILY_GOLF.tierOrMix(), 0));
            assertEquals(e.getValue(), p.hash(), "Golf of the Week seed " + Long.toHexString(e.getKey()));
        }
        Map<Long, String> tiny = Map.of(1L, "ac825813c4cb", 20725L, "ed8d277ba3d5", 0x3f2a91c07d1e55b0L,
                "52b07820be4c");
        for (Map.Entry<Long, String> e : new TreeMap<>(tiny).entrySet()) {
            Plan p = new GolfPlanner().plan(input(Slots.TINY_GOLF, e.getKey(), Slots.TINY_GOLF.tierOrMix(), 0));
            assertEquals(e.getValue(), p.hash(), "Tiny Golf seed " + Long.toHexString(e.getKey()));
        }
    }

    @Test
    void aStoredLayoutIsRederivedToTheSameHash() throws GenFailed {
        GolfPlanner planner = new GolfPlanner();
        for (int k = 0; k < 8; k++) {
            Run r = RUNS.get(k * 75 % RUNS.size());
            PlannedGolf g = golf(r.plan());
            GenTag tag = tag(r.plan(), g.attempts(), g.witness(), GolfPlanner.ALGO);
            Plan again = planner.rederive(input(r.slot(), r.n()), tag);
            String what = r.slot().id() + " seed " + r.n();
            assertEquals(r.plan().hash(), again.hash(), what + ": the same layout");
            assertEquals(r.plan().signs(), again.signs(), what + ": the same signs");
            assertEquals(g.course(), golf(again).course(), what + ": the same holes and pars");
            long putts = g.witness().stream().mapToLong(List::size).sum();
            assertTrue(again.work() > putts && again.work() < r.plan().work(), what + ": no search, only the ordinary"
                    + " player's 64 rollouts a hole and the stored lines: " + again.work());
            assertEquals(List.of(), golf(again).kid(), what + ": the kid tree isn't run again");
            assertEquals(List.of(), GolfValidator.quickProblems(again), what + ": and it passes the quick check");
        }
        Run r = RUNS.get(0);
        PlannedGolf g = golf(r.plan());
        assertThrows(GenFailed.class, () -> planner.rederive(input(r.slot(), r.n()),
                tag(r.plan(), g.attempts(), g.witness(), GolfPlanner.ALGO_V3)), "an algo-3 layout isn't rebuilt by v4");
        assertThrows(GenFailed.class, () -> GolfPlanner.v3().rederive(input(r.slot(), r.n()),
                tag(r.plan(), g.attempts(), g.witness(), GolfPlanner.ALGO)), "nor a v4 one by the frozen planner");
        PlanInput otherMix = input(r.slot(), r.plan().seed(), "EEEMMMMHM", 0);
        assertThrows(GenFailed.class, () -> planner.rederive(otherMix, tag(r.plan(), g.attempts(), g.witness(),
                GolfPlanner.ALGO)), "a changed mix doesn't rebuild the stored layout");
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
    void rerollingOneHoleChangesNoOtherHolesBlocks() throws GenFailed {
        Run r = RUNS.get(4);
        PlannedGolf g = golf(r.plan());
        // re-roll hole 9 to its class's fallback, with the fallback's own line
        PlanInput in = input(r.slot(), r.n());
        List<LengthClass> classes = DealV4.classes(new GenRandom(r.plan().seed()), in.tierOrMix(), false);
        Box half = in.half();
        int[] p = PlotGrid.V4.plot(half, 8);
        HoleLayout safe = HoleRecipe.fallback(classes.get(8), PlotGrid.V4, p[0], p[1],
                half.minY() + GolfPlanner.TURF_ABOVE_FLOOR);
        GolfPlannerV4.Solved s = GolfPlannerV4.solve(safe, classes.get(8), OrdinaryPar.Model.FIRST_TIMER, 2, 6, false,
                GolfPlannerV4.ATTEMPTS, Work.unlimited());
        assertNotNull(s, "the fallback solves");
        List<Integer> attempts = new ArrayList<>(g.attempts());
        attempts.set(8, GolfPlannerV4.ATTEMPTS);
        List<List<Putt>> witness = new ArrayList<>(g.witness());
        witness.set(8, s.witness());
        Plan rerolled = new GolfPlanner().rederive(in, new GenTag(r.plan().slot(), Slots.GOLF, GolfPlanner.ALGO, 20725, 0,
                r.plan().seed(), 'A', "", 0, 0, 0, attempts, witness, 0));
        for (int i = 0; i < 8; i++) {
            GolfCourse.Hole a = g.course().holes().get(i);
            GolfCourse.Hole b = golf(rerolled).course().holes().get(i);
            assertEquals(a.tee(), b.tee(), "hole " + (i + 1) + "'s tee is where it was");
            assertEquals(a.cup(), b.cup(), "its cup");
            assertEquals(a.corner1(), b.corner1(), "its bounds");
            assertEquals(a.corner2(), b.corner2(), "...");
            assertTrue(Math.abs(a.par() - b.par()) <= 1, "its par is its own, give or take the course balance");
            Box area = Box.of(a.corner1().x(), a.corner1().y(), a.corner1().z(), a.corner2().x(), a.corner2().y(),
                    a.corner2().z());
            assertEquals(blocksIn(r.plan(), area), blocksIn(rerolled, area), "hole " + (i + 1) + ": block for block");
        }
        assertFalse(g.course().holes().get(8).equals(golf(rerolled).course().holes().get(8)), "while hole 9 is new");
        assertEquals(List.of(), GolfValidator.quickProblems(rerolled), "and the course is sound");
    }

    private static Set<String> blocksIn(Plan p, Box area) {
        Set<String> out = new HashSet<>();
        for (BlockOp op : p.ops()) {
            if (area.contains(op.x(), op.y(), op.z())) {
                out.add(op.x() + " " + op.y() + " " + op.z() + " " + p.blockOf(op));
            }
        }
        return out;
    }

    @Test
    void aTightBudgetIsACapThatKeepsBackOnlyTheFallbacks() throws GenFailed {
        GolfPlanner planner = new GolfPlanner();
        long least = GolfPlannerV4.leastBudget(9);
        assertEquals(180_000, least, "nine fallbacks of 20,000 (GOLF-V4-SPEC §6.2)");
        Plan free = RUNS.get(3).plan();
        PlanInput in = input(Slots.DAILY_GOLF, 3);
        Plan roomy = planner.plan(new PlanInput(in.slot(), in.half(), 'A', in.day(), 0, in.seed(), in.tierOrMix(), 8,
                free.work() + least, null));
        assertEquals(free.hash(), roomy.hash(), "a budget with room for the plan and every fallback changes nothing");
        for (long budget : new long[]{least, least + 5_000}) {
            Plan p = planner.plan(new PlanInput(in.slot(), in.half(), 'A', in.day(), 0, in.seed(), in.tierOrMix(), 8,
                    budget, null));
            assertTrue(p.work() <= budget, "budget " + budget + ": the plan keeps to it: " + p.work());
            assertEquals(9, golf(p).attempts().size(), "budget " + budget + ": never missing a hole");
            assertEquals(List.of(), GolfValidator.quickProblems(p), "budget " + budget + ": and a sound course");
        }
        GenFailed tooLittle = assertThrows(GenFailed.class, () -> planner.plan(new PlanInput(in.slot(), in.half(), 'A',
                in.day(), 0, in.seed(), in.tierOrMix(), 8, least - 1, null)), "below the least budget, no plan");
        assertTrue(tooLittle.getMessage().contains(Long.toString(least)), "and it says the least: " + tooLittle.getMessage());
    }

    @Test
    void badInputsAndACancelledJobFail() {
        GolfPlanner planner = new GolfPlanner();
        assertEquals(Slots.GOLF, planner.id(), "the golf generator");
        assertEquals(GolfPlanner.ALGO, planner.algo(), "the engine's planner is Golf v4");
        assertEquals(GolfPlanner.ALGO_V3, GolfPlanner.v3().algo(), "the frozen one is Adventure Golf");
        assertThrows(GenFailed.class, () -> planner.plan(input(Slots.DAILY_GOLF, 1, "EEX", 0)), "not a mix");
        GenFailed small = assertThrows(GenFailed.class, () -> planner.plan(new PlanInput(Slots.DAILY_GOLF,
                LegacyBoxes.half(Slots.DAILY_GOLF, 'A'), 'A', 1, 0, 1, "EEEMMMMHH", 8, 0, null)),
                "Golf v4 doesn't fit the old 64 x 128 half");
        assertTrue(small.getMessage().contains("128 x 224"), "and says what it needs: " + small.getMessage());
        AtomicBoolean stop = new AtomicBoolean(true);
        GenFailed cancelled = assertThrows(GenFailed.class, () -> planner.plan(new PlanInput(Slots.DAILY_GOLF,
                V4Boxes.half(Slots.DAILY_GOLF, 'A'), 'A', 1, 0, 1, "EEEMMMMHH", 8, 0, stop::get)), "a cancelled job stops");
        assertEquals("cancelled", cancelled.getMessage(), "and says so");
    }

    @Test
    void aShortMixPlansTheSameWay() throws GenFailed {
        for (String mix : List.of("E", "H", "EMH", "MMMMMM", "HHHHHHHHH")) {
            Plan p = new GolfPlanner().plan(input(Slots.DAILY_GOLF, 99, mix, 0));
            assertEquals(mix.length(), golf(p).course().holes().size(), mix + ": a hole per letter");
            assertEquals(List.of(), GolfValidator.quickProblems(p), mix + ": a sound course");
        }
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
        return new GenTag(p.slot(), Slots.GOLF, algo, 20725, 0, p.seed(), 'A', p.hash(), 0, 0, 0, attempts, witness, 0);
    }
}
