package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Golf v4, golf planner version 4 (GOLF-V4-SPEC §3, §6): real hole lengths, every club with a job,
 * honest par, on 40 x 64 plots. {@link GolfPlanner} plans with it; pure.
 *
 * <p><b>The course.</b> The deal ({@link DealV4}) gives each hole a length class and a recipe
 * ({@link HoleRecipe}); hole i is drawn on plot i of the half ({@link PlotGrid#of(String, int)}: 40 x
 * 64 for Golf of the Week and Classic Golf, Tiny Golf's 20 x 40 as before), T = the half's floor + 4.
 *
 * <p><b>A hole is proven</b> where it stands:
 * <ol>
 *   <li>its blocks pass Adventure Golf's per-hole rules, unchanged ({@link GolfValidatorV3#holeProblems});
 *       on Tiny Golf there is no water in play and the path is at most {@value #TINY_PATH} blocks;</li>
 *   <li>a layup does its job: from the tee straight up the first leg, a Drive ends in it and a Swing
 *       rests on turf short of it;</li>
 *   <li>the ordinary player ({@link OrdinaryPar}: the first-timer, or the child on Tiny Golf) measures
 *       the class's par, ⌊μ + ½⌋, and its witness — the steady line or a rollout, holed with no
 *       penalty, every rest on the lane, dry twins on a pond hole — takes E putts, 1 ≤ E ≤ par;</li>
 *   <li>the sloppy player ({@link KidPolicy}, unchanged) always finishes within par + 2 (Tiny Golf: par +
 *       1), never wet, every rest spot of its tree on the lane.</li>
 * </ol>
 * A hole that fails is drawn again from its own stream, {@code fork("hole:" + i + ":try:" + t)}:
 * attempts 0-3 at full length, 4-7 from the shorter half of the ranges, 8-11 the group's next recipe
 * (shorter), and attempt 12 the class's proven fallback ({@link HoleRecipe#fallback}). Re-rolling one
 * hole never changes another's blocks.
 *
 * <p><b>Par for the course</b> ({@link OrdinaryPar#balance}): each hole's ⌊μ + ½⌋, balanced so the
 * total is within one of the summed means. A hole the balance lowers must still hold E ≤ par and the
 * kid's bound; if not, that hole is drawn again from its next attempt and the course balanced again.
 *
 * <p><b>Counted work</b>: at most {@value #ATTEMPT_BUDGET} putts an attempt and the input's budget a
 * course (default {@link GolfPlanner#COURSE_BUDGET}), {@value #FALLBACK_RESERVE} kept back for each
 * hole still to come's fallback ({@link #leastBudget}).
 *
 * <p><b>Re-deriving</b> ({@link #rederive}) draws the same holes from the stored attempts, measures μ
 * again and replays each stored witness, then balances par and checks the hash.
 */
final class GolfPlannerV4 {

    /** Golf v4: golf planner version 4. */
    static final int ALGO = 4;
    /** Most simulated putts for one attempt at one hole. */
    static final long ATTEMPT_BUDGET = 150_000L;
    /** Kept back for the fallback of this hole and each one still to come (a SAFE_X takes about 15,000). */
    static final long FALLBACK_RESERVE = 20_000L;
    /** Attempts per hole before the fallback (attempt 12). */
    static final int ATTEMPTS = 12;
    /** From this attempt, lengths come from the shorter half of their ranges. */
    static final int SHORTER_FROM = 4;
    /** From this attempt, the group's next recipe. */
    static final int SWITCH_FROM = 8;
    /** The sloppy player's bound over par on Golf of the Week (owner decision D8). */
    static final int KID_OVER = 2;
    /** ...and on Tiny Golf. */
    static final int KID_OVER_TINY = 1;
    /** Tiny Golf's holes are at most this long (path, blocks). */
    static final double TINY_PATH = 22;
    /** Rounds of re-solving a hole the balance can't lower before the plan gives up (never needed in tests). */
    private static final int BALANCE_ROUNDS = 40;

    private GolfPlannerV4() {
    }

    /** The least work budget a v4 course of {@code holes} holes can be planned in: a fallback for each. */
    static long leastBudget(int holes) {
        return FALLBACK_RESERVE * holes;
    }

    /** The sloppy player's bound over par on a course of {@code slotId}. */
    static int kidOver(String slotId) {
        return GolfPlanner.dry(slotId) ? KID_OVER_TINY : KID_OVER;
    }

    /**
     * One proven hole.
     *
     * @param attempt the attempt that drew it (12: the fallback)
     * @param mean    μ, the ordinary player's mean
     * @param kid     K (the kid's worst), or -1 when not worked out (a re-derived plan)
     * @param chosen  per club, how often the ordinary player chose it ({@code null}: re-derived)
     */
    record Solved(int attempt, HoleLayout layout, LengthClass cls, double mean, List<Putt> witness, int kid,
                  long[] chosen) {

        int expert() {
            return witness.size();
        }
    }

    // ---- planning ------------------------------------------------------------------------------------

    static Plan plan(PlanInput in) throws GenFailed {
        String mix = GolfPlanner.mix(in);
        Course c = new Course(in, mix);
        int n = mix.length();
        long cap = in.workBudget() > 0 ? in.workBudget() : GolfPlanner.COURSE_BUDGET;
        if (cap < leastBudget(n)) {
            throw new GenFailed("a golf course of " + n + " holes needs a work budget of at least " + leastBudget(n)
                    + " putts, not " + cap);
        }
        long[] used = {0};
        Solved[] holes = new Solved[n];
        for (int i = 0; i < n; i++) {
            holes[i] = c.solve(i, 0, cap, used, n - i);
        }
        for (int round = 0; ; round++) {
            int[] par = pars(holes, c.most);
            int bad = -1;
            for (int i = 0; i < n && bad < 0; i++) {
                if (holes[i].expert() > par[i] || holes[i].kid() > par[i] + c.kidOver) {
                    bad = i;
                }
            }
            double off = OrdinaryPar.off(par, means(holes));
            if (bad < 0 && Math.abs(off) > 1 + 1e-9) {
                bad = furthest(holes, par, off); // no hole could move (Tiny Golf's par 3 at most): draw one again
            }
            if (bad < 0) {
                break;
            }
            if (holes[bad].attempt() >= ATTEMPTS || round >= BALANCE_ROUNDS) {
                throw new GenFailed("hole " + (bad + 1) + " can't take the par the course balance gives it (" + par[bad]
                        + ")");
            }
            holes[bad] = c.solve(bad, holes[bad].attempt() + 1, cap, used, 1);
        }
        Plan plan = c.assemble(List.of(holes), used[0]);
        in.checkCancelled();
        List<String> problems = GolfValidator.problems(plan);
        if (!problems.isEmpty()) {
            throw new GenFailed("the golf plan failed its check: " + problems.get(0)
                    + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : ""));
        }
        return plan;
    }

    /** The course's pars from its holes' μ ({@link OrdinaryPar#balance}), at most {@code most}. */
    static int[] pars(Solved[] holes, int most) {
        return OrdinaryPar.balance(means(holes), most);
    }

    static double[] means(Solved[] holes) {
        double[] means = new double[holes.length];
        for (int i = 0; i < holes.length; i++) {
            means[i] = holes[i].mean();
        }
        return means;
    }

    /** The hole whose μ is furthest from its par the way the course is {@code off} (the lower number on a tie). */
    private static int furthest(Solved[] holes, int[] par, double off) {
        int pick = 0;
        double far = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < holes.length; i++) {
            double d = off < 0 ? holes[i].mean() - par[i] : par[i] - holes[i].mean();
            if (d > far + 1e-9) {
                far = d;
                pick = i;
            }
        }
        return pick;
    }

    /** What the next attempt may spend of {@code cap}: what isn't used, less the fallbacks of {@code kept} holes. */
    private static long spendable(long cap, long used, int kept) {
        return cap - used - FALLBACK_RESERVE * kept;
    }

    /**
     * Prove one drawn hole of class {@code cls} (or, {@code fallback}, of any par), or null — what the
     * full check will ask of it (the class docs).
     *
     * @param model   who sets par
     * @param kidOver the kid's bound over par
     * @param most    the highest par a hole of the slot takes (Tiny Golf: 3)
     * @param dry     Tiny Golf: no water in play, the path at most {@value #TINY_PATH}
     */
    static Solved solve(HoleLayout layout, LengthClass cls, OrdinaryPar.Model model, int kidOver, int most,
                        boolean dry, int attempt, boolean fallback, Work work) throws GenFailed {
        if (layout == null) {
            return null;
        }
        PlanBlocks grid = layout.grid(GolfPlanner.plotBox(layout));
        GolfCourse.Hole hole = layout.hole(GolfCourse.MIN_PAR);
        if (!GolfValidatorV3.holeProblems(grid, hole, 1).isEmpty()) {
            return null;
        }
        LaneMap lane = LaneMap.of(grid, hole, ALGO);
        if (dry && (lane.hazards() > 0 || path(grid, hole, lane) > TINY_PATH)) {
            return null;
        }
        if (layout.features().contains(Quota.Feature.LAYUP) && !layupHolds(grid, hole, work)) {
            return null;
        }
        OrdinaryPar.Measure m = OrdinaryPar.measure(grid, hole, lane, model, work);
        if (m == null || m.witness().isEmpty() || !fallback && m.rounded() != cls.par) {
            return null;
        }
        int par = OrdinaryPar.par(m.mean(), most);
        if (m.expert() > par) {
            return null;
        }
        boolean[] off = {false};
        KidPolicy.Result kid = KidPolicy.evaluate(grid, hole, lane, par + kidOver, work, r -> {
            if (!r.inCup() && (r.penalty() || !GolfValidatorV3.restsOnLane(lane, r.x(), r.y(), r.z()))) {
                off[0] = true;
            }
        });
        if (!kid.within() || off[0]) {
            return null;
        }
        return new Solved(attempt, layout, cls, m.mean(), m.witness(), kid.worst(), m.chosen());
    }

    /** The path from the tee to the cup along the lane, blocks. */
    static double path(PlanBlocks grid, GolfCourse.Hole hole, LaneMap lane) {
        BallPhysics.Ball tee = GolfShot.tee(grid, hole);
        return lane.pathDistance(tee.x(), tee.z());
    }

    /**
     * Whether a layup does its job (§3.6): from the tee, straight up the first leg (yaw 0), a Drive
     * ends in the hazard — a splash, or at rest in sand — and a Swing rests on turf short of it.
     */
    static boolean layupHolds(PlanBlocks grid, GolfCourse.Hole hole, Work work) throws GenFailed {
        BallPhysics.Hole area = GolfShot.area(grid, hole);
        BallPhysics.Ball tee = GolfShot.tee(grid, hole);
        work.checkCancelled();
        if (!work.spend() || !work.spend()) {
            return false;
        }
        GolfShot.Result drive = GolfShot.play(grid, area, new BallPhysics.Ball(tee.x(), tee.y(), tee.z()),
                new Putt(0f, BallPhysics.clubs()));
        GolfShot.Result swing = GolfShot.play(grid, area, new BallPhysics.Ball(tee.x(), tee.y(), tee.z()),
                new Putt(0f, BallPhysics.clubs() - 1));
        boolean driveIn = drive.penalty() || !drive.inCup() && sand(floor(grid, drive));
        boolean swingShort = !swing.penalty() && !swing.inCup() && floor(grid, swing) == PlanBlocks.FULL;
        return driveIn && swingShort;
    }

    private static byte floor(PlanBlocks grid, GolfShot.Result r) {
        return grid.get((int) Math.floor(r.x()), (int) Math.floor(r.y() - 1e-6), (int) Math.floor(r.z()));
    }

    private static boolean sand(byte code) {
        return code == PlanBlocks.SAND || code == PlanBlocks.SAND_SLAB;
    }

    // ---- re-deriving ---------------------------------------------------------------------------------

    static Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        String mix = GolfPlanner.mix(in);
        if (tag.attempts().size() != mix.length() || tag.witness().size() != mix.length()) {
            throw new GenFailed("the stored layout has " + tag.attempts().size() + " holes, the mix " + mix
                    + " has " + mix.length());
        }
        Course c = new Course(in, mix);
        List<Solved> holes = new ArrayList<>();
        long used = 0;
        for (int i = 0; i < mix.length(); i++) {
            in.checkCancelled();
            int t = tag.attempts().get(i);
            if (t < 0 || t > ATTEMPTS) {
                throw new GenFailed("hole " + (i + 1) + " has no attempt " + t);
            }
            List<Putt> witness = tag.witness().get(i);
            if (witness.isEmpty() || witness.size() > OrdinaryPar.CAP) {
                throw new GenFailed("hole " + (i + 1) + "'s stored line has " + witness.size() + " putts");
            }
            HoleLayout layout = c.draw(i, t);
            if (layout == null) {
                throw new GenFailed("hole " + (i + 1) + "'s stored attempt " + t + " doesn't draw (was the layout"
                        + " planned with another mix?)");
            }
            PlanBlocks grid = layout.grid(GolfPlanner.plotBox(layout));
            GolfCourse.Hole hole = layout.hole(GolfCourse.MIN_PAR);
            int expert = witness.size();
            GolfShot.Replay replay = GolfShot.replay(grid, hole, witness);
            used += witness.size();
            if (!replay.holed() || replay.putts() != expert || replay.strokes() != expert) {
                throw new GenFailed("hole " + (i + 1) + "'s stored line doesn't hole out in " + expert + " with the"
                        + " mix " + mix + " (was the layout planned with another mix, or edited?)");
            }
            Work work = new Work(Long.MAX_VALUE, in.cancelled());
            Double mean = OrdinaryPar.mean(grid, hole, LaneMap.of(grid, hole, ALGO), c.model, work);
            used += work.used();
            if (mean == null) {
                throw new GenFailed("hole " + (i + 1) + "'s par couldn't be worked out");
            }
            holes.add(new Solved(t, layout, c.classes.get(i), mean, witness, -1, null));
        }
        Plan plan = c.assemble(holes, used);
        if (!tag.planHash().isEmpty() && !tag.planHash().equals(plan.hash())) {
            throw new GenFailed("the rebuilt layout (" + plan.hash() + ") isn't the stored one (" + tag.planHash()
                    + "); was the mix changed?");
        }
        return plan;
    }

    // ---- the course ----------------------------------------------------------------------------------

    /** One v4 course being planned or re-derived: its input, mix, plots, streams and deal. */
    private static final class Course {

        private final PlanInput in;
        private final String mix;
        private final GenRandom root;
        private final int turfY;
        private final PlotGrid geometry;
        private final boolean dry;
        private final OrdinaryPar.Model model;
        private final int kidOver;
        private final int most;
        private final DealV4.Deal deal;
        private final List<LengthClass> classes;

        Course(PlanInput in, String mix) throws GenFailed {
            this.in = in;
            this.mix = mix;
            this.root = new GenRandom(in.seed());
            Box half = in.half();
            this.turfY = half.minY() + GolfPlanner.TURF_ABOVE_FLOOR;
            if (turfY + 6 > half.maxY()) {
                throw new GenFailed("the golf half is too low: " + half.describe());
            }
            this.geometry = PlotGrid.of(in.slot(), ALGO);
            for (int i = 0; i < mix.length(); i++) {
                if (!geometry.fits(half, i)) {
                    int[] need = geometry.needs(mix.length());
                    throw new GenFailed("hole " + (i + 1) + " doesn't fit the half " + half.describe() + " (Golf v4's "
                            + mix.length() + " holes need " + need[0] + " x " + need[1] + ")");
                }
            }
            this.dry = GolfPlanner.dry(in.slot());
            this.model = OrdinaryPar.model(in.slot());
            this.kidOver = kidOver(in.slot().id());
            this.most = OrdinaryPar.most(in.slot().id());
            this.deal = DealV4.deal(root, mix, dry);
            this.classes = deal.classes();
        }

        /** Hole {@code i}, attempt {@code t}, drawn ({@code null}: this draw didn't work out). */
        HoleLayout draw(int i, int t) {
            int[] p = geometry.plot(in.half(), i);
            if (t >= ATTEMPTS) {
                return HoleRecipe.fallback(classes.get(i), geometry, p[0], p[1], turfY);
            }
            HoleRecipe recipe = deal.recipe(mix, i, t >= SWITCH_FROM);
            GenRandom r = root.fork("hole:" + i + ":try:" + t);
            try {
                return recipe.draw(r, mix.charAt(i), geometry, p[0], p[1], turfY, t >= SHORTER_FROM, dry);
            } catch (Draft.Redraw e) {
                return null;
            }
        }

        /**
         * Hole {@code i} proven from attempt {@code from} on: its attempts while the budget lasts (keeping
         * back the fallbacks of {@code kept} holes, this one's included), then the fallback.
         */
        Solved solve(int i, int from, long cap, long[] used, int kept) throws GenFailed {
            for (int t = from; t < ATTEMPTS; t++) {
                in.checkCancelled();
                long left = spendable(cap, used[0], kept);
                if (left <= 0) {
                    break;
                }
                Work work = new Work(Math.min(ATTEMPT_BUDGET, left), in.cancelled());
                Solved s = GolfPlannerV4.solve(draw(i, t), classes.get(i), model, kidOver, most, dry, t, false, work);
                used[0] += work.used();
                if (s != null) {
                    return s;
                }
            }
            in.checkCancelled();
            Work work = new Work(Math.min(ATTEMPT_BUDGET, spendable(cap, used[0], kept - 1)), in.cancelled());
            Solved s = GolfPlannerV4.solve(draw(i, ATTEMPTS), classes.get(i), model, kidOver, most, dry, ATTEMPTS,
                    true, work);
            used[0] += work.used();
            if (s == null) {
                throw new GenFailed("hole " + (i + 1) + " couldn't be solved");
            }
            return s;
        }

        Plan assemble(List<Solved> holes, long work) {
            return GolfPlannerV4.assemble(in, holes, work, geometry, DealV4.targets(mix, classes, dry), deal.k(),
                    kidOver, most);
        }
    }

    /**
     * The plan for {@code holes} (in playing order): par balanced over the course, every block through
     * one palette, each plot's scenery ({@link GolfScenery}, from {@code scenery:<hole>}), every leaf at
     * vanilla's distance, a tee sign per hole saying its par and its main feature, the course Mini Golf
     * runs, what the row stores, and a summary: each hole's class, recipe, μ, E, par and K; the quota;
     * and the clubs the first-timer chose.
     */
    static Plan assemble(PlanInput in, List<Solved> holes, long work, PlotGrid geometry,
                         Map<Quota.Feature, Integer> targets, int deal, int kidOver, int most) {
        GenRandom root = new GenRandom(in.seed());
        int[] par = pars(holes.toArray(new Solved[0]), most);
        List<HoleLayout.Placed> placed = new ArrayList<>();
        List<SignText> signs = new ArrayList<>();
        List<GolfCourse.Hole> course = new ArrayList<>();
        List<Integer> attempts = new ArrayList<>();
        List<List<Putt>> witness = new ArrayList<>();
        List<Integer> expert = new ArrayList<>();
        List<Integer> kid = new ArrayList<>();
        List<Box> keepClear = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        List<HoleLayout> layouts = new ArrayList<>();
        long[] clubs = new long[6];
        boolean counted = true;
        int total = 0;
        double means = 0;
        for (int i = 0; i < holes.size(); i++) {
            Solved s = holes.get(i);
            HoleLayout l = s.layout();
            layouts.add(l);
            placed.addAll(l.blocks());
            placed.addAll(l.scenery());
            int[] p = geometry.plot(in.half(), i);
            placed.addAll(GolfScenery.trees(root.fork("scenery:" + i), l, p[0], p[1], geometry, in.half()));
            signs.add(new SignText(l.signX(), l.signY(), l.signZ(), Palette.sign(0),
                    GenCopy.golfTee(i + 1, par[i], l.teeFeature())));
            course.add(l.hole(par[i]));
            attempts.add(s.attempt());
            witness.add(s.witness());
            expert.add(s.expert());
            if (s.kid() >= 0) {
                kid.add(s.kid());
            }
            if (s.chosen() == null) {
                counted = false;
            } else {
                for (int c = 1; c <= 5; c++) {
                    clubs[c] += s.chosen()[c];
                }
            }
            keepClear.add(l.bounds());
            total += par[i];
            means += s.mean();
            String has = l.features().isEmpty() ? "" : "; " + l.features().stream().sorted()
                    .map(Quota.Feature::words).collect(Collectors.joining(", "));
            summary.add("hole " + (i + 1) + ": " + l.describe() + " - "
                    + String.format(Locale.ROOT, "mean %.2f", s.mean())
                    + ", E " + s.expert() + ", par " + par[i] + (s.kid() >= 0 ? ", K " + s.kid() : "") + " (try "
                    + (s.attempt() + 1) + ")" + has);
        }
        List<String> palette = new ArrayList<>();
        Map<String, Short> index = new HashMap<>();
        List<BlockOp> ops = new ArrayList<>();
        for (HoleLayout.Placed p : GolfPlanner.leaves(placed)) {
            Short state = index.get(p.blockData());
            if (state == null) {
                state = (short) palette.size();
                palette.add(p.blockData());
                index.put(p.blockData(), state);
            }
            ops.add(new BlockOp(p.x(), p.y(), p.z(), state));
        }
        Slots.Def slot = in.slot();
        summary.add(0, slot.name() + ": " + holes.size() + " holes, par " + total + ", " + work
                + " putts simulated (golf v4: par from the " + (kidOver == KID_OVER_TINY ? "child" : "first-timer")
                + ", " + String.format(Locale.ROOT, "%.2f", means) + " over the course; kid within par + " + kidOver
                + ")");
        if (targets != null) {
            summary.add(DealV4.line(DealV4.countLayouts(layouts), targets, deal));
        }
        if (counted) {
            summary.add(clubLine(clubs));
        }
        GolfCourse gc = new GolfCourse(slot.id(), slot.name(), "", true, 1, course);
        PlannedGolf planned = new PlannedGolf(gc, attempts, witness, expert, kid);
        return Plan.of(slot.id(), ALGO, in.seed(), in.half(), palette, ops, signs, keepClear, planned, summary, work);
    }

    /** "clubs: Tap 24%, Putt 9%, Chip 8%, Swing 11%, Drive 48%" (the first-timer's choices over its rollouts). */
    static String clubLine(long[] clubs) {
        long all = 0;
        for (int c = 1; c <= 5; c++) {
            all += clubs[c];
        }
        String[] names = {"", "Tap", "Putt", "Chip", "Swing", "Drive"};
        StringBuilder out = new StringBuilder("clubs:");
        for (int c = 1; c <= 5; c++) {
            out.append(c == 1 ? " " : ", ").append(names[c]).append(' ')
                    .append(all == 0 ? 0 : Math.round(100.0 * clubs[c] / all)).append('%');
        }
        return out.toString();
    }
}
