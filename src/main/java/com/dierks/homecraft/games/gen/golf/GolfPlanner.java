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
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The planner for Daily Golf and Tiny Golf (GEN-SPEC §4.3): a course of proven holes from a seed,
 * as pure data.
 *
 * <p><b>Plots.</b> A golf half is a grid of plots, each {@value HoleTemplate#PLOT_X} x
 * {@value HoleTemplate#PLOT_Z}, {@value #GAP_X} blocks apart in X and {@value #GAP_Z} in Z, three to
 * a row: Daily Golf's 64 x 128 half holds 3 x 3, Tiny Golf's 64 x 48 half one row of 3. Hole i
 * takes row i / 3, and runs along the row in snake order (the engine teleports players from tee
 * to tee anyway). The turf's top is T = the half's floor + {@value #TURF_ABOVE_FLOOR}.
 *
 * <p><b>Holes.</b> The mix ({@code EEEMMMMHH}) gives each hole a tier. Each tier's templates are
 * dealt in a seeded order, so a course doesn't repeat a shape while its tier has others. Every hole
 * is then drawn and proven:
 * <ol>
 *   <li>the expert search ({@link ExpertSearch}) finds E, the fewest putts that hole out, and a
 *       witness line that replays exactly; par is E + 1 (clamped to 2-6);</li>
 *   <li>the tier must like the par: Easy 2-3, Medium and Hard 3-4;</li>
 *   <li>the sloppy player ({@link KidPolicy}) must always finish within par + 1.</li>
 * </ol>
 * A hole that fails is drawn again from its own stream, {@code fork("hole:" + i + ":try:" + t)}:
 * attempts 0-5 use the hole's template, 6-11 the next one of its tier, and attempt 12 is
 * {@link HoleTemplate#SAFE_STRAIGHT}, which a test proves always passes. So a course is never
 * missing a hole, and re-rolling one hole never changes another.
 *
 * <p><b>Checked before it leaves.</b> A finished plan is put through the full, independent
 * {@link GolfValidator} (the sloppy player included) here on the planner thread; a plan with any
 * problem is a failed try. The main thread's pre-build check then only needs
 * {@link GolfValidator#quickProblems}.
 *
 * <p><b>Counted work.</b> Every simulated putt counts: at most {@value #ATTEMPT_BUDGET} an attempt
 * and {@link PlanInput#workBudget()} (default {@value #COURSE_BUDGET}) a course, a cap the whole
 * plan keeps to, fallbacks included. Only a fallback's cost ({@value #FALLBACK_RESERVE}) is kept
 * back for this hole and each one still to come, so a tight budget gives up the last holes'
 * attempts, never the first ones'; below {@link #leastBudget} there is no plan. Counting, not
 * timing, makes a seed give the same course on a slow host and in CI. The job's {@code cancelled}
 * check is asked every few hundred putts, so a reload stops a plan within milliseconds.
 *
 * <p><b>Re-deriving.</b> The row's {@code gen:} block stores each hole's winning attempt number and
 * witness line. {@link #rederive} draws the same holes from those without searching, replays each
 * witness on the drawn blocks, and checks the result hashes as the stored plan did; that is how the
 * boot check rebuilds the live layout. The mix must be the one the layout was planned with.
 */
public final class GolfPlanner implements Planner {

    /**
     * Its version; bumped whenever what it makes for a seed changes (the golden tests pin it). 2: the
     * work kept back for fallbacks is a fallback's cost, so a course near its budget plans differently.
     */
    public static final int ALGO = 2;
    /** Most simulated putts for one attempt at one hole. */
    public static final long ATTEMPT_BUDGET = 100_000L;
    /** Most simulated putts for a course when the input doesn't say. */
    public static final long COURSE_BUDGET = 2_500_000L;
    /** Attempts per hole before the fallback. */
    public static final int ATTEMPTS = 12;
    /** Attempts on a hole's own template before the next one of its tier. */
    public static final int SWITCH_AFTER = 6;
    /**
     * Kept back for the fallback of this hole and of each one still to come: a proven
     * {@link HoleTemplate#SAFE_STRAIGHT} takes a few hundred putts in any plot (a test pins it
     * under half of this).
     */
    static final long FALLBACK_RESERVE = 1_000L;
    /** Blocks between plots. */
    public static final int GAP_X = 2;
    public static final int GAP_Z = 4;
    /** Plots in a row. */
    public static final int COLUMNS = 3;
    /** T, the turf's top, above the half's floor. */
    public static final int TURF_ABOVE_FLOOR = 4;

    @Override
    public String id() {
        return Slots.GOLF;
    }

    @Override
    public int algo() {
        return ALGO;
    }

    @Override
    public Plan plan(PlanInput in) throws GenFailed {
        try {
            return planChecked(in);
        } catch (GenFailed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenFailed("the golf planner hit a bug: " + e, e);
        }
    }

    @Override
    public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        try {
            return rederiveChecked(in, tag);
        } catch (GenFailed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenFailed("the golf planner hit a bug: " + e, e);
        }
    }

    // ---- planning ------------------------------------------------------------------------------------

    /**
     * One proven hole.
     *
     * @param attempt the attempt that drew it (12 is the fallback)
     * @param expert  E
     * @param kid     K, or -1 when it wasn't worked out (a re-derived plan)
     */
    record Solved(int attempt, HoleLayout layout, int expert, int par, int kid, List<Putt> witness) {
    }

    private Plan planChecked(PlanInput in) throws GenFailed {
        String mix = mix(in);
        Course c = new Course(in, mix);
        int n = mix.length();
        long cap = in.workBudget() > 0 ? in.workBudget() : COURSE_BUDGET;
        if (cap < leastBudget(n)) {
            throw new GenFailed("a golf course of " + n + " holes needs a work budget of at least " + leastBudget(n)
                    + " putts, not " + cap);
        }
        long used = 0;
        List<Solved> holes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            char tier = mix.charAt(i);
            Solved solved = null;
            for (int t = 0; t < ATTEMPTS && solved == null; t++) {
                in.checkCancelled();
                long left = spendable(cap, used, n - i); // this hole's fallback is kept back too
                if (left <= 0) {
                    break; // spent: on to the fallback, which was kept back for
                }
                Work work = new Work(Math.min(ATTEMPT_BUDGET, left), in.cancelled());
                solved = solve(c.draw(i, t), tier, t, work);
                used += work.used();
            }
            if (solved == null) {
                in.checkCancelled();
                Work work = new Work(Math.min(ATTEMPT_BUDGET, spendable(cap, used, n - i - 1)), in.cancelled());
                solved = solve(c.draw(i, ATTEMPTS), 'S', ATTEMPTS, work);
                used += work.used();
                if (solved == null) {
                    throw new GenFailed("hole " + (i + 1) + " couldn't be solved");
                }
            }
            holes.add(solved);
        }
        Plan plan = c.assemble(holes, used);
        in.checkCancelled();
        List<String> problems = GolfValidator.problems(plan);
        if (!problems.isEmpty()) {
            throw new GenFailed("the golf plan failed its check: " + problems.get(0)
                    + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : ""));
        }
        return plan;
    }

    /**
     * What the next search may spend of a course's {@code cap}: what isn't {@code used} yet, less the
     * fallbacks of {@code kept} holes and the few putts a witness's replay may run over a spent
     * {@link Work}. Keeping to it, a plan never uses more than its cap.
     */
    private static long spendable(long cap, long used, int kept) {
        return cap - used - FALLBACK_RESERVE * kept - ExpertSearch.MAX_DEPTH;
    }

    /** The least work budget a course of {@code holes} holes can be planned in: a fallback for each. */
    public static long leastBudget(int holes) {
        return FALLBACK_RESERVE * holes + ExpertSearch.MAX_DEPTH;
    }

    /** Prove one drawn hole for its tier ('S' for the fallback: any par the search finds), or null. */
    static Solved solve(HoleLayout layout, char tier, int attempt, Work work) throws GenFailed {
        PlanBlocks grid = layout.grid(plotBox(layout));
        GolfCourse.Hole hole = layout.hole(GolfCourse.MIN_PAR);
        LaneMap lane = LaneMap.of(grid, hole);
        int[] range = expertRange(tier);
        ExpertSearch.Result es = ExpertSearch.search(grid, hole, lane, range[1], work);
        if (!es.found() || es.strokes() < range[0]) {
            return null;
        }
        int par = par(es.strokes());
        KidPolicy.Result kid = KidPolicy.evaluate(grid, hole, lane, par + 1, work);
        if (!kid.within()) {
            return null;
        }
        return new Solved(attempt, layout, es.strokes(), par, kid.worst(), es.witness());
    }

    /** Par for an expert line of {@code expert} putts: one more, 2-6. */
    public static int par(int expert) {
        return Math.max(GolfCourse.MIN_PAR, Math.min(GolfCourse.MAX_PAR, expert + 1));
    }

    /**
     * The expert strokes a tier accepts, {min, max}: Easy par 2-3, Medium and Hard par 3-4; the
     * fallback ('S') anything the search can find.
     */
    static int[] expertRange(char tier) {
        return switch (Character.toUpperCase(tier)) {
            case 'E' -> new int[]{1, 2};
            case 'M', 'H' -> new int[]{2, 3};
            default -> new int[]{1, ExpertSearch.MAX_DEPTH};
        };
    }

    /** A box round a hole's plot, tall enough for its cup, walls and flag. */
    static Box plotBox(HoleLayout layout) {
        Box b = layout.bounds();
        return new Box(b.minX() - 1, layout.turfY() - 4, b.minZ() - 1, b.maxX() + 1, layout.turfY() + 6,
                b.maxZ() + 1);
    }

    // ---- re-deriving ---------------------------------------------------------------------------------

    private Plan rederiveChecked(PlanInput in, GenTag tag) throws GenFailed {
        if (tag == null) {
            throw new GenFailed("there is no stored layout to rebuild");
        }
        if (!Slots.GOLF.equals(tag.generator())) {
            throw new GenFailed("the stored layout wasn't made by the golf planner");
        }
        if (tag.algo() != ALGO) {
            throw new GenFailed("the stored layout was made by golf planner version " + tag.algo() + ", this is "
                    + ALGO);
        }
        String mix = mix(in);
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
            if (witness.isEmpty() || witness.size() > ExpertSearch.MAX_DEPTH) {
                throw new GenFailed("hole " + (i + 1) + "'s stored line has " + witness.size() + " putts");
            }
            HoleLayout layout = c.draw(i, t);
            int expert = witness.size();
            int par = par(expert);
            GolfShot.Replay replay = GolfShot.replay(layout.grid(plotBox(layout)), layout.hole(par), witness);
            used += witness.size();
            if (!replay.holed() || replay.putts() != expert || replay.strokes() != expert) {
                throw new GenFailed("hole " + (i + 1) + "'s stored line doesn't hole out in " + expert + " with the"
                        + " mix " + mix + " (was the layout planned with another mix, or edited?)");
            }
            holes.add(new Solved(t, layout, expert, par, -1, witness));
        }
        Plan plan = c.assemble(holes, used);
        if (!tag.planHash().isEmpty() && !tag.planHash().equals(plan.hash())) {
            throw new GenFailed("the rebuilt layout (" + plan.hash() + ") isn't the stored one (" + tag.planHash()
                    + "); was the mix changed?");
        }
        return plan;
    }

    // ---- shared ------------------------------------------------------------------------------------

    /** The mix to plan: the input's, or the slot's own when it gives none. */
    static String mix(PlanInput in) throws GenFailed {
        Slots.Def slot = in.slot();
        if (!slot.golf()) {
            throw new GenFailed(slot.id() + " isn't a golf slot");
        }
        String mix = slot.normalise(in.tierOrMix());
        if (mix.isEmpty()) {
            mix = slot.tierOrMix();
        }
        String problem = slot.tierProblem(mix);
        if (problem != null) {
            throw new GenFailed(problem);
        }
        return mix;
    }

    /** Where hole {@code i}'s plot starts in {@code half}: {x, z}. */
    public static int[] plot(Box half, int i) {
        int row = i / COLUMNS;
        int col = row % 2 == 0 ? i % COLUMNS : COLUMNS - 1 - i % COLUMNS;
        return new int[]{half.minX() + col * (HoleTemplate.PLOT_X + GAP_X),
                half.minZ() + row * (HoleTemplate.PLOT_Z + GAP_Z)};
    }

    /** One course being planned or re-derived: its input, mix, streams and template order. */
    private static final class Course {

        private final PlanInput in;
        private final String mix;
        private final GenRandom root;
        private final int turfY;
        private final Map<Character, List<HoleTemplate>> order = new HashMap<>();

        Course(PlanInput in, String mix) throws GenFailed {
            this.in = in;
            this.mix = mix;
            this.root = new GenRandom(in.seed());
            Box half = in.half();
            this.turfY = half.minY() + TURF_ABOVE_FLOOR;
            if (turfY + 6 > half.maxY()) {
                throw new GenFailed("the golf half is too low: " + half.describe());
            }
            for (int i = 0; i < mix.length(); i++) {
                int[] p = plot(half, i);
                if (p[0] + HoleTemplate.PLOT_X - 1 > half.maxX() || p[1] + HoleTemplate.PLOT_Z - 1 > half.maxZ()) {
                    throw new GenFailed("hole " + (i + 1) + " doesn't fit the half " + half.describe());
                }
            }
            for (char tier : new char[]{'E', 'M', 'H'}) {
                List<HoleTemplate> list = new ArrayList<>(HoleTemplate.forTier(tier));
                GenRandom r = root.fork("order:" + tier);
                for (int k = list.size() - 1; k > 0; k--) {
                    int j = r.nextInt(k + 1);
                    HoleTemplate tmp = list.get(k);
                    list.set(k, list.get(j));
                    list.set(j, tmp);
                }
                order.put(tier, list);
            }
        }

        /** Hole {@code i}'s template for attempt {@code t}. */
        HoleTemplate template(int i, int t) {
            if (t >= ATTEMPTS) {
                return HoleTemplate.SAFE_STRAIGHT;
            }
            char tier = mix.charAt(i);
            int k = 0;
            for (int j = 0; j < i; j++) {
                if (mix.charAt(j) == tier) {
                    k++;
                }
            }
            List<HoleTemplate> list = order.get(tier);
            return list.get((k + (t < SWITCH_AFTER ? 0 : 1)) % list.size());
        }

        /** Hole {@code i}, attempt {@code t}, drawn. */
        HoleLayout draw(int i, int t) {
            int[] p = plot(in.half(), i);
            GenRandom r = root.fork("hole:" + i + ":try:" + t);
            return template(i, t).draw(r, mix.charAt(i), p[0], p[1], turfY);
        }

        Plan assemble(List<Solved> holes, long work) {
            return GolfPlanner.assemble(in, holes, work);
        }
    }

    /**
     * The plan for {@code holes} (in playing order): every block through one palette, a tee sign
     * per hole, the course Mini Golf runs (world and rev are the engine's to set), and what the
     * row stores.
     */
    static Plan assemble(PlanInput in, List<Solved> holes, long work) {
        List<String> palette = new ArrayList<>();
        Map<String, Short> index = new HashMap<>();
        List<BlockOp> ops = new ArrayList<>();
        List<SignText> signs = new ArrayList<>();
        List<GolfCourse.Hole> course = new ArrayList<>();
        List<Integer> attempts = new ArrayList<>();
        List<List<Putt>> witness = new ArrayList<>();
        List<Integer> expert = new ArrayList<>();
        List<Integer> kid = new ArrayList<>();
        List<Box> keepClear = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        int par = 0;
        for (int i = 0; i < holes.size(); i++) {
            Solved s = holes.get(i);
            HoleLayout l = s.layout();
            for (HoleLayout.Placed p : l.blocks()) {
                Short state = index.get(p.blockData());
                if (state == null) {
                    state = (short) palette.size();
                    palette.add(p.blockData());
                    index.put(p.blockData(), state);
                }
                ops.add(new BlockOp(p.x(), p.y(), p.z(), state));
            }
            signs.add(new SignText(l.signX(), l.signY(), l.signZ(), Palette.sign(0),
                    GenCopy.golfTee(i + 1, s.par())));
            course.add(l.hole(s.par()));
            attempts.add(s.attempt());
            witness.add(s.witness());
            expert.add(s.expert());
            if (s.kid() >= 0) {
                kid.add(s.kid());
            }
            keepClear.add(l.bounds());
            par += s.par();
            summary.add("hole " + (i + 1) + ": " + l.describe() + " - E " + s.expert() + ", par " + s.par()
                    + (s.kid() >= 0 ? ", K " + s.kid() : "") + " (try " + (s.attempt() + 1) + ")");
        }
        Slots.Def slot = in.slot();
        summary.add(0, slot.name() + ": " + holes.size() + " holes, par " + par + ", " + work
                + " putts simulated");
        GolfCourse gc = new GolfCourse(slot.id(), slot.name(), "", true, 1, course);
        PlannedGolf planned = new PlannedGolf(gc, attempts, witness, expert, kid);
        return Plan.of(slot.id(), ALGO, in.seed(), in.half(), palette, ops, signs, keepClear, planned, summary,
                work);
    }
}
