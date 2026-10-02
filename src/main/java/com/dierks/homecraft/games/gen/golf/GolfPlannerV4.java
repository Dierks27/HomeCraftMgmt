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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Golf v4, golf planner version 4 (GOLF-V4-SPEC §3, §6): real hole lengths, every club with a job,
 * honest par, on 40 x 64 plots. {@link GolfPlanner} plans with it; pure.
 *
 * <p><b>The course.</b> The deal ({@link DealV4}) gives each hole a length class and a recipe
 * ({@link HoleRecipe}), a Swing layup, a Chip layup and a guarded par 3 pinned to holes of every
 * course of 7 or more; hole i is drawn on plot i of the half ({@link PlotGrid#of(String, int)}: 40 x
 * 64 for Golf of the Week and Classic Golf, Tiny Golf's 20 x 40 as before), T = the half's floor + 4.
 *
 * <p><b>A hole is proven</b> where it stands:
 * <ol>
 *   <li>its blocks pass Adventure Golf's per-hole rules, unchanged ({@link GolfValidatorV3#holeProblems});
 *       on Tiny Golf there is no water in play and the path is at most {@value #TINY_PATH} blocks;</li>
 *   <li>a layup (and a guarded par 3) does its job along the line the first-timer really aims from the
 *       tee, across the corner ({@link #layupHolds}): the longer clubs go in, the layup club stays dry,
 *       and the first-timer's own choice from the tee is the layup club;</li>
 *   <li>the ordinary player ({@link OrdinaryPar}: the first-timer, or the child on Tiny Golf) measures
 *       the class's par, ⌊μ + ½⌋ — the fallback's too — and its witness — the steady line or a
 *       rollout, holed with no penalty, every rest on the lane, dry twins on a pond hole — takes E
 *       putts, 1 ≤ E ≤ par;</li>
 *   <li>the sloppy player ({@link KidPolicy}, unchanged) always finishes within par + 2 (Tiny Golf: par +
 *       1), never wet, every rest spot of its tree on the lane.</li>
 * </ol>
 * A hole that fails is drawn again from its own stream, {@code fork("hole:" + i + ":try:" + t)}:
 * attempts 0-3 at full length, 4-7 from the shorter half of the ranges, 8-11 the group's next recipe
 * (shorter; a pinned hole keeps its own), and attempt 12 the class's proven fallback
 * ({@link HoleRecipe#fallback}). Re-rolling one hole never changes another's blocks.
 *
 * <p><b>Settling the course</b> ({@link #settle}). Par is each hole's ⌊μ + ½⌋, balanced so the total
 * is within one of the summed means ({@link OrdinaryPar#balance}): a hole moves at most a stroke, never
 * outside 2-6 (Tiny Golf 2-3), so as every hole measures its class's par, every par is its class's or a
 * stroke off it, and the summary says which (red-team F01). A hole the balance lowers must still hold
 * E ≤ par and the kid's bound, a course no par can balance draws its furthest hole again, and on the
 * first-timer's courses of 7 holes or more the club gate holds: Putt, Chip and Swing each at least
 * {@value #GATE_PERCENT}% of its chosen clubs, shown on the summary's {@code clubs:} line (red-team F00);
 * each of these draws a hole again from its next attempt, and the course is settled again.
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
        boolean[] again = new boolean[n];
        int gateRounds = 0;
        for (int round = 0; ; round++) {
            Settle why = settle(holes, c.most, c.kidOver, c.gate && gateRounds < GATE_ROUNDS);
            if (why == null) {
                break;
            }
            if (why.hole() < 0 || round >= SETTLE_ROUNDS) {
                if (why.gate()) {
                    break; // the gate can't be met by drawing again: the course stands, its clubs line says so
                }
                throw new GenFailed("the course can't be balanced: " + why.words());
            }
            gateRounds += why.gate() ? 1 : 0;
            int bad = why.hole();
            holes[bad] = c.solve(bad, holes[bad].attempt() + 1, cap, used, 1);
            again[bad] = true;
        }
        Plan plan = c.assemble(List.of(holes), used[0], again);
        in.checkCancelled();
        List<String> problems = GolfValidator.problems(plan);
        if (!problems.isEmpty()) {
            throw new GenFailed("the golf plan failed its check: " + problems.get(0)
                    + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : ""));
        }
        return plan;
    }

    /**
     * Why a course isn't settled yet, and the hole to draw again ({@code -1}: none can be).
     *
     * @param gate whether it is the club gate (the course stands if no hole can be drawn again for it)
     */
    record Settle(int hole, String words, boolean gate) {
    }

    /**
     * What a course of solved holes still needs (null: nothing), in order:
     * <ol>
     *   <li>a hole the balance gave a par it can't take (its witness over it, or the kid over its bound)
     *       is drawn again;</li>
     *   <li>a course still more than a stroke from its holes' summed μ once balanced (no hole could move:
     *       a Tiny Golf par 3 can't go up) draws again the hole furthest that way that isn't its
     *       fallback;</li>
     *   <li>with {@code gate}, the club gate (red-team F00): Putt, Chip and Swing are each at least
     *       {@value #GATE_PERCENT}% of the first-timer's chosen clubs, and if one isn't, the hole that
     *       chose it least (not a fallback) is drawn again.</li>
     * </ol>
     * Pure, so a test can settle made-up holes.
     */
    static Settle settle(Solved[] holes, int most, int kidOver, boolean gate) {
        int n = holes.length;
        int[] par = pars(holes, most);
        for (int i = 0; i < n; i++) {
            if (holes[i].expert() > par[i] || holes[i].kid() > par[i] + kidOver) {
                return new Settle(holes[i].attempt() >= ATTEMPTS ? -1 : i, "hole " + (i + 1) + " can't take the par the"
                        + " course balance gives it (" + par[i] + ")", false);
            }
        }
        double off = OrdinaryPar.off(par, means(holes));
        if (Math.abs(off) > 1 + 1e-9) {
            return new Settle(furthest(holes, par, off), String.format(Locale.ROOT, "its par is %+.2f from its holes'"
                    + " means", off), false);
        }
        if (gate) {
            long[] clubs = clubs(holes);
            for (int club : GATED) {
                if (!gated(clubs, club)) {
                    int pick = -1;
                    for (int i = 0; i < n; i++) {
                        if (holes[i].attempt() < ATTEMPTS && holes[i].chosen() != null
                                && (pick < 0
                                || share(holes[i].chosen(), club) < share(holes[pick].chosen(), club) - 1e-12)) {
                            pick = i;
                        }
                    }
                    return new Settle(pick, CLUB_NAMES[club] + " is chosen for under " + GATE_PERCENT + "% of the"
                            + " first-timer's shots", true);
                }
            }
        }
        return null;
    }

    /** The clubs the gate holds to {@value #GATE_PERCENT}% each: Putt, Chip and Swing. */
    static final int[] GATED = {2, 3, 4};
    /** The least share, percent, each of {@link #GATED} has of the first-timer's chosen clubs on a course. */
    static final int GATE_PERCENT = 4;
    /** Holes drawn again for the club gate before the course stands as it is. */
    static final int GATE_ROUNDS = 9;
    /** Rounds of drawing a hole again (the balance and the gate) before the plan gives up. */
    static final int SETTLE_ROUNDS = 40;
    static final String[] CLUB_NAMES = {"", "Tap", "Putt", "Chip", "Swing", "Drive"};

    /** The first-timer's chosen clubs over a course's holes (index 1-5). */
    static long[] clubs(Solved[] holes) {
        long[] out = new long[6];
        for (Solved s : holes) {
            if (s.chosen() != null) {
                for (int c = 1; c <= 5; c++) {
                    out[c] += s.chosen()[c];
                }
            }
        }
        return out;
    }

    /** Whether {@code club} is at least {@value #GATE_PERCENT}% of {@code clubs} (exactly, in whole counts). */
    static boolean gated(long[] clubs, int club) {
        long all = 0;
        for (int c = 1; c <= 5; c++) {
            all += clubs[c];
        }
        return clubs[club] * 100 >= all * GATE_PERCENT;
    }

    private static double share(long[] chosen, int club) {
        long all = 0;
        for (int c = 1; c <= 5; c++) {
            all += chosen[c];
        }
        return all == 0 ? 0 : (double) chosen[club] / all;
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

    /**
     * The hole whose μ is furthest from its par the way the course is {@code off} (the lower number on
     * a tie), not a fallback; -1 when every hole is.
     */
    private static int furthest(Solved[] holes, int[] par, double off) {
        int pick = -1;
        double far = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < holes.length; i++) {
            double d = off < 0 ? holes[i].mean() - par[i] : par[i] - holes[i].mean();
            if (holes[i].attempt() < ATTEMPTS && d > far + 1e-9) {
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
     * Prove one drawn hole of class {@code cls}, its fallback too, or null — what the full check will
     * ask of it (the class docs). Every hole measures its class's par, so the course balance leaves
     * every par within a stroke of its class's (red-team F01).
     *
     * @param model   who sets par
     * @param kidOver the kid's bound over par
     * @param most    the highest par a hole of the slot takes (Tiny Golf: 3)
     * @param dry     Tiny Golf: no water in play, the path at most {@value #TINY_PATH}
     */
    static Solved solve(HoleLayout layout, LengthClass cls, OrdinaryPar.Model model, int kidOver, int most,
                        boolean dry, int attempt, Work work) throws GenFailed {
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
        if (!layupHolds(layout, grid, hole, lane, work)) {
            return null;
        }
        OrdinaryPar.Measure m = OrdinaryPar.measure(grid, hole, lane, model, work);
        if (m == null || m.witness().isEmpty() || m.rounded() != cls.par) {
            return null;
        }
        int layup = layupClub(layout.features());
        if (layup != 0 && m.teeClub() != layup) {
            return null; // the first-timer doesn't lay up from the tee
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
     * The club a hole's tee shot lays up with ({@link #layupHolds}): Swing on a layup and a guarded par
     * 3, Chip on a chip layup; 0 on every other hole.
     */
    static int layupClub(Set<Quota.Feature> features) {
        if (features.contains(Quota.Feature.CHIP_LAYUP)) {
            return 3;
        }
        return features.contains(Quota.Feature.LAYUP) || features.contains(Quota.Feature.GUARDED) ? 4 : 0;
    }

    /**
     * Whether a layup does its job (GOLF-V4-SPEC §3.6, red-team F00), checked along the line the
     * first-timer really aims from the tee — at the furthest waypoint it sees ({@link LaneMap#target}),
     * across the corner — not along the leg: there every club longer than the layup club
     * ({@link #layupClub}) ends in the water, and the layup club, there and
     * {@value SafeExpert#TWIN_DEGREES} degrees either side, rests dry on the lane. (The planner then
     * checks the first-timer's own choice from the tee is the layup club: {@link OrdinaryPar.Measure#teeClub}.)
     * Every other hole holds.
     */
    static boolean layupHolds(HoleLayout layout, PlanBlocks grid, GolfCourse.Hole hole, LaneMap lane, Work work)
            throws GenFailed {
        int club = layupClub(layout.features());
        if (club == 0) {
            return true;
        }
        BallPhysics.Hole area = GolfShot.area(grid, hole);
        BallPhysics.Ball tee = GolfShot.tee(grid, hole);
        int aim = lane.target(tee.x(), tee.z());
        double b = LaneMap.bearing(tee.x(), tee.z(), lane.waypointX(aim), lane.waypointZ(aim));
        for (int c = club + 1; c <= BallPhysics.clubs(); c++) {
            work.checkCancelled();
            if (!work.spend()) {
                return false;
            }
            GolfShot.Result r = GolfShot.play(grid, area, new BallPhysics.Ball(tee.x(), tee.y(), tee.z()),
                    new Putt((float) b, c));
            if (!r.penalty()) {
                return false;
            }
        }
        for (float twin : new float[]{0, -SafeExpert.TWIN_DEGREES, SafeExpert.TWIN_DEGREES}) {
            work.checkCancelled();
            if (!work.spend()) {
                return false;
            }
            GolfShot.Result r = GolfShot.play(grid, area, new BallPhysics.Ball(tee.x(), tee.y(), tee.z()),
                    new Putt((float) (b + twin), club));
            if (r.penalty() || r.inCup() || !GolfValidatorV3.restsOnLane(lane, r.x(), r.y(), r.z())) {
                return false;
            }
        }
        return true;
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
        /** Whether the club gate holds ({@link #settle}): the first-timer's courses of 7 holes or more. */
        private final boolean gate;
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
            this.gate = model == OrdinaryPar.Model.FIRST_TIMER && mix.length() >= DealV4.TABLE_FULL;
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
                Solved s = GolfPlannerV4.solve(draw(i, t), classes.get(i), model, kidOver, most, dry, t, work);
                used[0] += work.used();
                if (s != null) {
                    return s;
                }
            }
            in.checkCancelled();
            Work work = new Work(Math.min(ATTEMPT_BUDGET, spendable(cap, used[0], kept - 1)), in.cancelled());
            Solved s = GolfPlannerV4.solve(draw(i, ATTEMPTS), classes.get(i), model, kidOver, most, dry, ATTEMPTS,
                    work);
            used[0] += work.used();
            if (s == null) {
                throw new GenFailed("hole " + (i + 1) + " couldn't be solved");
            }
            return s;
        }

        Plan assemble(List<Solved> holes, long work) {
            return assemble(holes, work, new boolean[holes.size()]);
        }

        /** The plan, {@code again} marking the holes drawn again to settle the course ({@link #settle}). */
        Plan assemble(List<Solved> holes, long work, boolean[] again) {
            return GolfPlannerV4.assemble(in, holes, work, geometry, DealV4.targets(mix, classes, dry), deal.k(),
                    kidOver, most, gate, again);
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
                         Map<Quota.Feature, Integer> targets, int deal, int kidOver, int most, boolean gate,
                         boolean[] again) {
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
        int moved = 0;
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
            if (par[i] != s.cls().par) {
                moved++;
            }
            summary.add("hole " + (i + 1) + ": " + l.describe() + " - "
                    + String.format(Locale.ROOT, "mean %.2f", s.mean())
                    + ", E " + s.expert() + ", par " + par[i] + balanced(par[i], s.cls())
                    + (s.kid() >= 0 ? ", K " + s.kid() : "") + " (try " + (s.attempt() + 1)
                    + (again[i] ? ", drawn again to settle the course" : "") + ")" + has);
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
                + ", " + String.format(Locale.ROOT, "%.2f", means) + " over the course; "
                + (moved == 0 ? "every hole its class's par" : moved + " hole" + (moved == 1 ? "" : "s")
                + " a stroke off its class's par to balance the course") + "; kid within par + " + kidOver + ")");
        if (targets != null) {
            summary.add(DealV4.line(DealV4.countLayouts(layouts), targets, deal));
        }
        if (counted) {
            summary.add(clubLine(clubs, gate));
        }
        GolfCourse gc = new GolfCourse(slot.id(), slot.name(), "", true, 1, course);
        PlannedGolf planned = new PlannedGolf(gc, attempts, witness, expert, kid);
        return Plan.of(slot.id(), ALGO, in.seed(), in.half(), palette, ops, signs, keepClear, planned, summary, work);
    }

    /**
     * " (its class's 4, a stroke up to balance the course)" for a hole whose par the course balance
     * moved off its length class's par (never more than a stroke, never outside 2-6), else "".
     */
    static String balanced(int par, LengthClass cls) {
        return par == cls.par ? "" : " (its class's " + cls.par + ", a stroke " + (par > cls.par ? "up" : "down")
                + " to balance the course)";
    }

    /**
     * "clubs: Tap 24%, Putt 9%, Chip 8%, Swing 11%, Drive 48%" (the first-timer's choices over its
     * rollouts), and with {@code gate} whether the club gate ({@link #settle}) is met: "; Putt, Chip,
     * Swing 4%+ each: met".
     */
    static String clubLine(long[] clubs, boolean gate) {
        long all = 0;
        for (int c = 1; c <= 5; c++) {
            all += clubs[c];
        }
        StringBuilder out = new StringBuilder("clubs:");
        for (int c = 1; c <= 5; c++) {
            out.append(c == 1 ? " " : ", ").append(CLUB_NAMES[c]).append(' ')
                    .append(all == 0 ? 0 : Math.round(100.0 * clubs[c] / all)).append('%');
        }
        if (gate) {
            boolean met = true;
            for (int club : GATED) {
                met &= gated(clubs, club);
            }
            out.append("; Putt, Chip, Swing ").append(GATE_PERCENT).append("%+ each: ").append(met ? "met" : "MISSED");
        }
        return out.toString();
    }
}
