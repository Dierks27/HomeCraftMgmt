package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Dropper's planner (EVENTS-DROPPER-SPEC §B.1.5): a row of glass shafts, one per level of the
 * mix, each proven before a block is set.
 *
 * <p><b>A level</b> ({@code fork("level:" + i)}, so redrawing level 2 never changes level 3):
 * <ol>
 *   <li>the ledge goes on a seeded wall, the layer depths and templates are drawn from the tier's
 *       table ({@link DropRules});</li>
 *   <li>the <b>witness</b> is drawn first: a walk-off with at most the tier's input changes, flown
 *       through the empty shaft, redrawn until it stays clear of the walls by the hitbox plus its
 *       clearance r, passes layer 1 in front of the ledge and can land in a pool;</li>
 *   <li>each layer's path opening is centred where the witness crosses it (the tier's size at least,
 *       and all of the witness's tube); the template fills the rest, but only outside the tube, so
 *       the witness is clear by construction. Easy lights every opening, Medium the first; TWIN adds
 *       a second opening proven by its own witness, DECOY a hole the next layer blocks;</li>
 *   <li>the pool goes where the witness lands, the whole floor on Easy;</li>
 *   <li>then the level is flown ({@link DropCheck}): the witness again, through the real blocks,
 *       and the pilots ({@link DropPilot}: every 0.3 blocks of the ledge's edge and three moments of
 *       a walking step, walking or jumping off, at each reaction delay, and the sloppy runs). A
 *       pilot that bonks on a layer widens that layer's opening by 1 (twice at most); after that
 *       the level is redrawn ({@code fork("level:i:try:t")}), up to 20 times; after that, or once
 *       the level has used its share of the work budget, it is <b>SAFE_STRAIGHT</b>:
 *       the tier's shallowest layers, every opening the tier's minimum + 2, stacked under a plain
 *       walk-off. So a course is never missing a level.</li>
 * </ol>
 * The finished plan is checked again, from its blocks alone, by the independent
 * {@link DropperValidator}.
 *
 * <p><b>Work</b> is counted in simulated ticks (witnesses and pilots), never time, so every host makes
 * the same plan. A typical plan needs under 50k; the engine's budget is {@value #WORK_BUDGET}.
 *
 * <p><b>The course kind.</b> The row is a {@code dropper} trial ({@link TrialKind#DROPPER}, added by
 * the C1 contracts, which re-pinned the golden hashes). {@link #KIND} still falls back to
 * {@link TrialKind#PARKOUR} if that constant is ever missing, so the package compiles on its own.
 *
 * <p>Pure: no Bukkit, no clock, no {@code java.util.Random}.
 */
public final class DropperPlanner implements Planner {

    /** Its version: bump it whenever what it makes for a seed changes (golden hashes pin three seeds a mix). */
    public static final int ALGO = 1;
    /** The most counted work (simulated ticks) a plan takes when the engine gives no budget. */
    public static final long WORK_BUDGET = 300_000;
    /** Redraws of a level after its first draw. */
    public static final int REDRAWS = 20;
    /** How many times one layer's opening may be widened by a failing pilot. */
    public static final int WIDENINGS = 2;
    /** Witness programs drawn for one draw of a level before it gives up on that draw. */
    public static final int PROGRAM_DRAWS = 40;
    /** Tries at a second witness for a TWIN layer before it becomes a plain plate. */
    public static final int TWIN_DRAWS = 20;
    /** Tries at a place for a DECOY hole before the layer becomes a plain plate. */
    public static final int DECOY_DRAWS = 12;
    /** The fewest solid blocks a layer keeps once its openings are cut. */
    public static final int MIN_SOLID = 12;
    /** The course's kind: {@link TrialKind#DROPPER} (PARKOUR only if that were ever missing). */
    public static final TrialKind KIND = TrialKind.of(DropperSlots.DROPPER) != null
            ? TrialKind.of(DropperSlots.DROPPER) : TrialKind.PARKOUR;
    /** The start spot looks this far down into the shaft. */
    public static final float START_PITCH = 30f;

    private static final List<DropProgram.Dir> AXES = List.of(DropProgram.Dir.N, DropProgram.Dir.E,
            DropProgram.Dir.S, DropProgram.Dir.W);

    @Override
    public String id() {
        return DropperSlots.DROPPER;
    }

    @Override
    public int algo() {
        return ALGO;
    }

    @Override
    public Plan plan(PlanInput in) throws GenFailed {
        return build(in, false).plan();
    }

    /**
     * The layout the tag names, made again from its seed. The mix in {@code in} is tried first; if
     * an admin has changed the mix since, the mixes with the tag's reference time are tried (the
     * reference time says how many levels of each tier there were). It must hash the same as the tag.
     */
    @Override
    public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
        if (tag == null) {
            return plan(in);
        }
        if (tag.algo() != ALGO) {
            throw new GenFailed("this layout was made by dropper planner v" + tag.algo() + ", this is v" + ALGO);
        }
        boolean anyHash = tag.planHash() == null || tag.planHash().isBlank();
        List<String> mixes = new ArrayList<>();
        String first = DropRules.normalise(in.tierOrMix());
        if (DropRules.mixProblem(first) == null) {
            mixes.add(first);
        }
        for (String m : allMixes()) {
            if (!mixes.contains(m) && (tag.refMs() <= 0 || DropRules.refMs(m) == tag.refMs())) {
                mixes.add(m);
            }
        }
        GenFailed last = null;
        for (String mix : mixes) {
            PlanInput again = new PlanInput(in.slot(), in.half(), in.halfId(), tag.day(), tag.reroll(), tag.seed(), mix,
                    in.fallDepth(), in.workBudget(), in.cancelled());
            Plan p;
            try {
                p = plan(again);
            } catch (GenFailed e) {
                last = e;
                continue;
            }
            if (anyHash || tag.planHash().equals(p.hash())) {
                return p;
            }
        }
        if (anyHash && last != null) {
            throw last;
        }
        throw new GenFailed("seed " + GenSeed.shortHex(tag.seed()) + " no longer makes layout " + tag.planHash());
    }

    /** Every mix, shortest first, then in E, M, H order. */
    static List<String> allMixes() {
        List<String> out = new ArrayList<>();
        List<String> layer = List.of("");
        for (int n = 1; n <= DropRules.MAX_LEVELS; n++) {
            List<String> next = new ArrayList<>();
            for (String p : layer) {
                for (char c : new char[]{'E', 'M', 'H'}) {
                    next.add(p + c);
                }
            }
            out.addAll(next);
            layer = next;
        }
        return out;
    }

    // ---- the search -----------------------------------------------------------------------------------

    /** A plan and what it took, for tests and status. */
    record Build(Plan plan, List<LevelPlan> levels, int redraws, int widenings, int safeLevels, long work) {
    }

    /** A rectangle of blocks, both corners inclusive, in world x and z. */
    record Rect(int x1, int z1, int x2, int z2) {

        boolean contains(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        boolean overlaps(Rect o) {
            return o != null && o.x1 <= x2 && o.x2 >= x1 && o.z1 <= z2 && o.z2 >= z1;
        }

        Rect grow(int n) {
            return new Rect(x1 - n, z1 - n, x2 + n, z2 + n);
        }

        int sizeX() {
            return x2 - x1 + 1;
        }

        int sizeZ() {
            return z2 - z1 + 1;
        }

        double cx() {
            return (x1 + x2 + 1) / 2.0;
        }

        double cz() {
            return (z1 + z2 + 1) / 2.0;
        }

        String size() {
            return sizeX() + "x" + sizeZ();
        }
    }

    /**
     * One level as drawn and proven.
     *
     * @param index    its place (0-based)
     * @param tier     its tier
     * @param shaft    its shaft
     * @param forward  the way the shaft is from the ledge
     * @param ledge    the ledge's blocks
     * @param rows     each layer's block row, top first
     * @param shapes   each layer's template
     * @param open     each layer's path opening
     * @param twinOpen each layer's second opening (TWIN), or nulls
     * @param decoys   each layer's decoy hole, or nulls
     * @param pool     the pool's water
     * @param witness  the witness
     * @param twin     the TWIN witness, or null
     * @param safe     whether it is SAFE_STRAIGHT
     * @param redraws  draws thrown away before it
     * @param widened  openings widened for it
     */
    record LevelPlan(int index, DropRules.Level tier, DropperGeometry.Shaft shaft, DropProgram.Dir forward, Rect ledge,
                     int[] rows, LayerKit.Shape[] shapes, Rect[] open, Rect[] twinOpen, Rect[] decoys, Rect pool,
                     DropProgram witness, DropProgram twin, boolean safe, int redraws, int widened) {

        Course.Mark ledgeMark() {
            return DropMarks.ledge(ledge.cx(), shaft.ledgeTop(), ledge.cz());
        }

        Course.Mark poolMark() {
            return DropMarks.pool(pool.cx(), DropperGeometry.surface(shaft, tier), pool.cz(), pool.sizeX());
        }
    }

    Build build(PlanInput in, boolean safeOnly) throws GenFailed {
        String mix = DropRules.normalise(in.tierOrMix());
        String bad = DropRules.mixProblem(mix);
        if (bad != null) {
            throw new GenFailed(bad);
        }
        List<DropRules.Level> tiers = DropRules.levels(mix);
        Box half = in.half();
        if (!DropperGeometry.fits(half, tiers.size())) {
            throw new GenFailed("the half " + half.describe() + " is too small for " + tiers.size()
                    + " dropper levels");
        }
        long budget = in.workBudget() > 0 ? in.workBudget() : WORK_BUDGET;
        long share = budget / tiers.size();
        int n = tiers.size();
        Canvas canvas = new Canvas(half);
        GenRandom root = new GenRandom(in.seed());
        List<LevelPlan> levels = new ArrayList<>();
        long work = 0;
        int redraws = 0;
        int widenings = 0;
        int safe = 0;
        try {
            for (int i = 0; i < n; i++) {
                DropperGeometry.Shaft shaft = DropperGeometry.shaft(half, i);
                DropRules.Level tier = tiers.get(i);
                canvas.walls(shaft, tier, i, n);
                LevelPlan done = null;
                long used = 0;
                int thrown = 0;
                for (int t = 0; !safeOnly && t <= REDRAWS && done == null && used < share; t++) {
                    in.checkCancelled();
                    GenRandom rng = root.fork(t == 0 ? "level:" + i : "level:" + i + ":try:" + t);
                    long[] w = {0};
                    done = attempt(canvas, shaft, tier, i, n, rng, false, thrown, w);
                    used += w[0];
                    if (done == null) {
                        thrown++;
                    }
                }
                if (done == null) {
                    in.checkCancelled();
                    long[] w = {0};
                    done = attempt(canvas, shaft, tier, i, n, root.fork("level:" + i + ":safe"), true, thrown, w);
                    used += w[0];
                    if (done == null) {
                        throw new GenFailed("level " + (i + 1) + " couldn't be made even as a straight drop");
                    }
                    safe++;
                }
                work += used;
                redraws += thrown;
                widenings += done.widened();
                levels.add(done);
            }
            Plan plan = toPlan(in, mix, canvas, levels, work, redraws, widenings, safe);
            List<String> problems = DropperValidator.problems(plan, mix);
            if (!problems.isEmpty()) {
                throw new GenFailed("the dropper plan failed its own check: " + problems.get(0));
            }
            return new Build(plan, List.copyOf(levels), redraws, widenings, safe, work);
        } catch (GenFailed e) {
            throw e;
        } catch (RuntimeException e) {
            // the Planner contract: nothing but GenFailed ever leaves a plan
            throw new GenFailed("the dropper planner hit a bug: " + e, e);
        }
    }

    // ---- one draw of one level ----------------------------------------------------------------------------

    /**
     * Draw level {@code i} once and prove it; {@code null} when this draw can't be made to work.
     * {@code work[0]} gets the ticks it flew.
     */
    private LevelPlan attempt(Canvas canvas, DropperGeometry.Shaft shaft, DropRules.Level tier, int i, int n,
                              GenRandom rng, boolean safe, int thrown, long[] work) {
        DropProgram.Dir fwd = rng.pick(AXES);
        int offset = safe ? (DropperGeometry.INSIDE - DropperGeometry.LEDGE) / 2 : rng.nextInt(1, 7);
        int layers = tier.layers();
        int[] depths = new int[layers];
        LayerKit.Shape[] shapes = new LayerKit.Shape[layers];
        for (int j = 0; j < layers; j++) {
            depths[j] = j == 0 ? (safe ? tier.firstMin() : rng.nextInt(tier.firstMin(), tier.firstMax()))
                    : depths[j - 1] + (safe ? tier.gapMin() : rng.nextInt(tier.gapMin(), tier.gapMax()));
        }
        for (int j = 0; j < layers; j++) {
            if (safe) {
                shapes[j] = new LayerKit.Shape(LayerKit.Template.PLATE, 0, 0);
                continue;
            }
            List<LayerKit.Template> can = new ArrayList<>();
            for (LayerKit.Template t : tier.templates()) {
                if (t == LayerKit.Template.TWIN && j != 0) {
                    continue;
                }
                if (t == LayerKit.Template.DECOY && j == layers - 1) {
                    continue;
                }
                can.add(t);
            }
            shapes[j] = LayerKit.shape(rng.pick(can), rng);
        }
        int[] rows = new int[layers];
        for (int j = 0; j < layers; j++) {
            rows[j] = shaft.ledgeTop() - depths[j] - 1;
        }
        Rect ledge = ledgeRect(shaft, fwd, offset);
        canvas.clearInside(shaft);
        for (int x = ledge.x1(); x <= ledge.x2(); x++) {
            for (int z = ledge.z1(); z <= ledge.z2(); z++) {
                canvas.put(x, shaft.ledgeRow(), z, Canvas.LEDGE);
            }
        }
        int surfaceY = DropperGeometry.surface(shaft, tier);
        double r = tier.tube();
        DropCheck.View frame = frame(shaft, tier, i, fwd, ledge, surfaceY, rows);

        // the witness, drawn first, through the empty shaft
        Path main = null;
        DropProgram witness = null;
        for (int k = 0; k < PROGRAM_DRAWS && main == null; k++) {
            DropProgram p = safe ? DropProgram.coast(fwd, 0) : drawProgram(rng, tier, fwd);
            Path path = fly(canvas.world, frame, p, r, work);
            if (path != null && path.fits(frame, tier)) {
                main = path;
                witness = p;
            }
            if (safe) {
                break;
            }
        }
        if (main == null) {
            return null;
        }
        int k0 = safe ? tier.minOpening() + 2 : tier.opening();
        Rect[] open = new Rect[layers];
        for (int j = 0; j < layers; j++) {
            open[j] = opening(shaft, main, j, k0);
        }
        if (!safe && frame.forwardCell(fwd == DropProgram.Dir.W ? open[0].x2() : open[0].x1(),
                fwd == DropProgram.Dir.N ? open[0].z2() : open[0].z1()) < DropperGeometry.LEDGE) {
            return null; // layer 1's opening must be in front of the ledge, where a child on it can see it
        }

        // a second witness for TWIN
        Path second = null;
        DropProgram twin = null;
        Rect[] twinOpen = new Rect[layers];
        if (shapes[0].template() == LayerKit.Template.TWIN) {
            for (int k = 0; k <= TWIN_DRAWS && second == null; k++) {
                // first the witness's mirror image (the same line on the other side), then fresh draws
                DropProgram q = k == 0 ? witness.mirrored(fwd) : drawProgram(rng, tier, fwd);
                Path path = fly(canvas.world, frame, q, r, work);
                if (path == null || !path.fits(frame, tier)) {
                    continue;
                }
                Rect first = opening(shaft, path, 0, k0);
                if (first.overlaps(open[0].grow(1)) || !poolFits(shaft, tier, main, path)) {
                    continue;
                }
                second = path;
                twin = q;
            }
            if (second == null) {
                shapes[0] = new LayerKit.Shape(LayerKit.Template.PLATE, 0, 0);
            } else {
                for (int j = 0; j < layers; j++) {
                    twinOpen[j] = opening(shaft, second, j, k0);
                }
            }
        }
        Rect pool = pool(shaft, tier, main, second);
        if (pool == null) {
            return null;
        }

        // decoys: a hole whose column the next layer blocks
        Rect[] decoys = new Rect[layers];
        for (int j = 0; j < layers - 1; j++) {
            if (shapes[j].template() != LayerKit.Template.DECOY) {
                continue;
            }
            for (int k = 0; k < DECOY_DRAWS && decoys[j] == null; k++) {
                int dx = rng.nextInt(shaft.x1(), shaft.x2() - k0 + 1);
                int dz = rng.nextInt(shaft.z1(), shaft.z2() - k0 + 1);
                Rect d = new Rect(dx, dz, dx + k0 - 1, dz + k0 - 1);
                if (d.overlaps(open[j].grow(2)) || d.overlaps(open[j + 1].grow(1))
                        || main.tubeTouches(rows[j], d) || main.tubeTouches(rows[j + 1], d)) {
                    continue;
                }
                decoys[j] = d;
            }
            if (decoys[j] == null) {
                shapes[j] = new LayerKit.Shape(LayerKit.Template.PLATE, 0, 0);
            }
        }

        // build it, fly it, widen what the pilots bonk on
        int[] widened = new int[layers];
        int widenTotal = 0;
        while (true) {
            if (!writeLevel(canvas, shaft, tier, i, n, ledge, rows, shapes, open, twinOpen, decoys, pool, main,
                    second)) {
                return null;
            }
            LevelPlan lp = new LevelPlan(i, tier, shaft, fwd, ledge, rows, shapes.clone(), open.clone(),
                    twinOpen.clone(), decoys.clone(), pool, witness, twin, safe, thrown, widenTotal);
            DropCheck.Read read = DropCheck.read(canvas.world, i, tier, lp.ledgeMark(), lp.poolMark());
            if (read.view() == null || read.view().layerRows().size() != layers) {
                return null;
            }
            DropCheck.View v = read.view();
            DropCheck.Flight f = DropCheck.witness(canvas.world, v, witness, r);
            work[0] += f.result().ticks();
            if (!f.result().splashed() || f.crossings().size() != layers) {
                return null;
            }
            if (second != null) {
                DropCheck.Flight g = DropCheck.witness(canvas.world, v, twin, r);
                work[0] += g.result().ticks();
                if (!g.result().splashed()) {
                    return null;
                }
            }
            List<DropCheck.Miss> misses = DropCheck.pilots(canvas.world, v, DropCheck.targets(canvas.world, v,
                    f.crossings()), true, work);
            if (misses.isEmpty()) {
                return lp;
            }
            int[] hit = misses.get(0).result().hit();
            int j = hit == null ? -1 : indexOf(rows, hit[1]);
            if (j < 0 || widened[j] >= WIDENINGS || misses.get(0).result().outcome() != DropRun.Outcome.TOUCHED) {
                return null;
            }
            Rect wider = widen(shaft, open[j], hit[0], hit[2]);
            if (wider == null) {
                return null;
            }
            open[j] = wider;
            widened[j]++;
            widenTotal++;
        }
    }

    private static int indexOf(int[] rows, int y) {
        for (int j = 0; j < rows.length; j++) {
            if (rows[j] == y) {
                return j;
            }
        }
        return -1;
    }

    /** The opening grown by one block toward block (hx, hz), or null when it can't grow that way. */
    static Rect widen(DropperGeometry.Shaft s, Rect o, int hx, int hz) {
        int x1 = o.x1();
        int x2 = o.x2();
        int z1 = o.z1();
        int z2 = o.z2();
        if (hx < x1) {
            x1--;
        } else if (hx > x2) {
            x2++;
        }
        if (hz < z1) {
            z1--;
        } else if (hz > z2) {
            z2++;
        }
        if (x1 < s.x1() || x2 > s.x2() || z1 < s.z1() || z2 > s.z2()) {
            return null;
        }
        Rect r = new Rect(x1, z1, x2, z2);
        return r.equals(o) ? null : r;
    }

    /** The ledge's blocks: 3 x 3 against the wall behind {@code fwd}, {@code offset} blocks along it. */
    static Rect ledgeRect(DropperGeometry.Shaft s, DropProgram.Dir fwd, int offset) {
        int l = DropperGeometry.LEDGE - 1;
        return switch (fwd) {
            case S -> new Rect(s.x1() + offset, s.z1(), s.x1() + offset + l, s.z1() + l);
            case N -> new Rect(s.x1() + offset, s.z2() - l, s.x1() + offset + l, s.z2());
            case E -> new Rect(s.x1(), s.z1() + offset, s.x1() + l, s.z1() + offset + l);
            case W -> new Rect(s.x2() - l, s.z1() + offset, s.x2(), s.z1() + offset + l);
            default -> throw new IllegalArgumentException("a ledge faces along an axis: " + fwd);
        };
    }

    /** What the draw knows of the level before any obstacle: enough to fly a witness. */
    private static DropCheck.View frame(DropperGeometry.Shaft s, DropRules.Level tier, int i, DropProgram.Dir fwd,
                                        Rect ledge, int surfaceY, int[] rows) {
        double ex = switch (fwd) {
            case E -> ledge.x2() + 1;
            case W -> ledge.x1();
            default -> ledge.cx();
        };
        double ez = switch (fwd) {
            case S -> ledge.z2() + 1;
            case N -> ledge.z1();
            default -> ledge.cz();
        };
        List<Integer> r = new ArrayList<>();
        for (int y : rows) {
            r.add(y);
        }
        return new DropCheck.View(i, tier, s.x1(), s.z1(), s.x2(), s.z2(), s.ledgeTop(),
                new int[]{ledge.x1(), ledge.z1(), ledge.x2(), ledge.z2()}, fwd, ex, ez, null, surfaceY, r);
    }

    /**
     * A witness program for the tier: walk off, keep walking a few ticks, then
     * <ul>
     *   <li>Easy: let go, or turn once;</li>
     *   <li>Medium: turn, then let go (or turn once more);</li>
     *   <li>Hard: up to three turns (often back the way it came, to stop over a hole), then let go.</li>
     * </ul>
     */
    static DropProgram drawProgram(GenRandom rng, DropRules.Level tier, DropProgram.Dir fwd) {
        List<DropProgram.Dir> compass = DropProgram.Dir.compass();
        int t0 = rng.nextInt(0, 10);
        List<DropProgram.Segment> segs = new ArrayList<>();
        segs.add(new DropProgram.Segment(fwd, t0));
        switch (tier) {
            case EASY -> segs.add(new DropProgram.Segment(rng.chance(0.5) ? DropProgram.Dir.NONE : rng.pick(compass),
                    0));
            case MEDIUM -> {
                segs.add(new DropProgram.Segment(rng.pick(compass), rng.nextInt(2, 14)));
                segs.add(new DropProgram.Segment(rng.chance(0.7) ? DropProgram.Dir.NONE : rng.pick(compass), 0));
            }
            case HARD -> {
                int turns = rng.nextInt(1, 3);
                DropProgram.Dir prev = fwd;
                for (int k = 0; k < turns; k++) {
                    DropProgram.Dir d = k > 0 && rng.chance(0.5) ? prev.opposite() : rng.pick(compass);
                    segs.add(new DropProgram.Segment(d, rng.nextInt(2, 12)));
                    prev = d;
                }
                segs.add(new DropProgram.Segment(DropProgram.Dir.NONE, 0));
            }
        }
        return new DropProgram(segs);
    }

    // ---- a witness's path -----------------------------------------------------------------------------

    /**
     * A witness flown through the empty shaft to the water's surface: its sub-steps, its crossing of
     * each layer, the blocks its tube (the hitbox grown by r) passes through at each layer, and where
     * it lands.
     */
    private static final class Path {
        final DropProgram program;
        final List<double[]> crossings;
        final int[] rows;
        final int surfaceY;
        /** Every sub-step's swept box grown by r: {x1, y1, z1, x2, y2, z2}. */
        final List<double[]> tube = new ArrayList<>();
        /** The blocks the tube passes through, by row, worked out once a row is asked about. */
        private final Map<Integer, Set<Long>> cells = new HashMap<>();

        Path(DropProgram program, List<double[]> points, List<double[]> crossings, int[] rows, double r,
             int surfaceY) {
            this.program = program;
            this.crossings = crossings;
            this.rows = rows;
            this.surfaceY = surfaceY;
            double h = DropSim.HALF_WIDTH + r;
            for (int k = 1; k < points.size(); k++) {
                double[] a = points.get(k - 1);
                double[] b = points.get(k);
                tube.add(new double[]{Math.min(a[0], b[0]) - h, Math.min(a[1], b[1]) - r, Math.min(a[2], b[2]) - h,
                        Math.max(a[0], b[0]) + h, Math.max(a[1], b[1]) + DropSim.HEIGHT + r,
                        Math.max(a[2], b[2]) + h});
            }
        }

        /** Whether the tube passes through block (x, row, z). */
        boolean tubeHas(int row, int x, int z) {
            return cells.computeIfAbsent(row, this::cellsAt).contains(key(x, z));
        }

        private Set<Long> cellsAt(int row) {
            Set<Long> out = new HashSet<>();
            for (double[] b : tube) {
                if (b[1] < row + 1 && b[4] > row) {
                    for (int x = (int) Math.floor(b[0]); x <= (int) Math.ceil(b[3]) - 1; x++) {
                        for (int z = (int) Math.floor(b[2]); z <= (int) Math.ceil(b[5]) - 1; z++) {
                            out.add(key(x, z));
                        }
                    }
                }
            }
            return out;
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        /** The blocks the tube passes through at {@code row}, as a rectangle, or null. */
        Rect tubeRect(int row) {
            int x1 = Integer.MAX_VALUE;
            int z1 = Integer.MAX_VALUE;
            int x2 = Integer.MIN_VALUE;
            int z2 = Integer.MIN_VALUE;
            for (double[] b : tube) {
                if (b[1] < row + 1 && b[4] > row) {
                    x1 = Math.min(x1, (int) Math.floor(b[0]));
                    z1 = Math.min(z1, (int) Math.floor(b[2]));
                    x2 = Math.max(x2, (int) Math.ceil(b[3]) - 1);
                    z2 = Math.max(z2, (int) Math.ceil(b[5]) - 1);
                }
            }
            return x1 == Integer.MAX_VALUE ? null : new Rect(x1, z1, x2, z2);
        }

        /** Whether the tube passes through any block of {@code d} at {@code row}. */
        boolean tubeTouches(int row, Rect d) {
            for (int x = d.x1(); x <= d.x2(); x++) {
                for (int z = d.z1(); z <= d.z2(); z++) {
                    if (tubeHas(row, x, z)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The landing: the tube's footprint where it reaches below the surface, {x1, z1, x2, z2}. */
        double[] landing() {
            double[] out = null;
            for (double[] b : tube) {
                if (b[1] < surfaceY) {
                    out = out == null ? new double[]{b[0], b[2], b[3], b[5]}
                            : new double[]{Math.min(out[0], b[0]), Math.min(out[1], b[2]), Math.max(out[2], b[3]),
                            Math.max(out[3], b[5])};
                }
            }
            return out;
        }

        /**
         * Whether it is a witness this level can use: clear of the walls by the hitbox plus r all the
         * way down, crossing every layer, its input changes within the tier's, and able to land.
         */
        boolean fits(DropCheck.View frame, DropRules.Level tier) {
            for (double[] b : tube) {
                if (b[0] < frame.x1() || b[3] > frame.x2() + 1 || b[2] < frame.z1() || b[5] > frame.z2() + 1) {
                    return false;
                }
            }
            if (crossings.size() != rows.length || landing() == null) {
                return false;
            }
            if (program.changes(frame.forward()) > tier.maxChanges()) {
                return false;
            }
            return tier.changesBefore() <= 0
                    || program.lastChange(frame.forward()) < crossings.get(tier.changesBefore() - 1)[2] - 1;
        }
    }

    /** Fly {@code p} from the middle exit to the water's surface; null when it doesn't get there. */
    private static Path fly(DropWorld w, DropCheck.View frame, DropProgram p, double r, long[] work) {
        DropRun.Result res = DropRun.fly(w, frame.witnessStart(), p.controller(), r, DropSim.MAX_TICKS,
                frame.surfaceY(), true);
        work[0] += res.ticks();
        if (res.outcome() != DropRun.Outcome.STOPPED) {
            return null;
        }
        int[] rows = new int[frame.layerRows().size()];
        for (int j = 0; j < rows.length; j++) {
            rows[j] = frame.layerRows().get(j);
        }
        return new Path(p, res.path(), DropCheck.crossings(res.path(), frame.layerRows()), rows, r, frame.surfaceY());
    }

    /**
     * Layer {@code j}'s opening for a witness: {@code k} x {@code k} blocks round its crossing, inside
     * the shaft, grown to hold all of its tube at that row.
     */
    private static Rect opening(DropperGeometry.Shaft s, Path p, int j, int k) {
        double[] c = p.crossings.get(j);
        int x1 = start(c[0], k);
        int z1 = start(c[1], k);
        x1 = Math.max(s.x1(), Math.min(s.x2() - k + 1, x1));
        z1 = Math.max(s.z1(), Math.min(s.z2() - k + 1, z1));
        Rect r = new Rect(x1, z1, x1 + k - 1, z1 + k - 1);
        Rect t = p.tubeRect(p.rows[j]);
        if (t != null) {
            r = new Rect(Math.max(s.x1(), Math.min(r.x1(), t.x1())), Math.max(s.z1(), Math.min(r.z1(), t.z1())),
                    Math.min(s.x2(), Math.max(r.x2(), t.x2())), Math.min(s.z2(), Math.max(r.z2(), t.z2())));
        }
        return r;
    }

    /** The first block of a k-wide run centred on {@code c}: the middle block for odd k, the nearest line for even. */
    static int start(double c, int k) {
        if (k % 2 == 1) {
            return (int) Math.floor(c) - (k - 1) / 2;
        }
        return (int) Math.round(c) - k / 2;
    }

    /** Whether one pool of the tier's size can hold both landings. */
    private static boolean poolFits(DropperGeometry.Shaft s, DropRules.Level tier, Path a, Path b) {
        return pool(s, tier, a, b) != null;
    }

    /** The pool: the tier's size, round the landing(s), inside the shaft; null when it can't hold them. */
    private static Rect pool(DropperGeometry.Shaft s, DropRules.Level tier, Path a, Path b) {
        double[] land = a.landing();
        if (b != null) {
            double[] l2 = b.landing();
            land = new double[]{Math.min(land[0], l2[0]), Math.min(land[1], l2[1]), Math.max(land[2], l2[2]),
                    Math.max(land[3], l2[3])};
        }
        int size = tier.pool();
        int x1 = place(land[0], land[2], size, s.x1(), s.x2());
        int z1 = place(land[1], land[3], size, s.z1(), s.z2());
        if (x1 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE) {
            return null;
        }
        return new Rect(x1, z1, x1 + size - 1, z1 + size - 1);
    }

    /** The first block of a run of {@code size} blocks inside [lo, hi] that holds [a, b], centred on it. */
    private static int place(double a, double b, int size, int lo, int hi) {
        int min = Math.max(lo, (int) Math.ceil(b - size - 1e-9));
        int max = Math.min(hi - size + 1, (int) Math.floor(a + 1e-9));
        if (min > max) {
            return Integer.MIN_VALUE;
        }
        int want = (int) Math.round((a + b) / 2 - size / 2.0);
        return Math.max(min, Math.min(max, want));
    }

    // ---- writing a level ------------------------------------------------------------------------------

    /**
     * Write level {@code i}'s inside: the ledge, the floor, the pool and the layers. A decoy whose
     * column the next layer doesn't block is filled back in. False when a layer would be left with
     * almost nothing solid.
     */
    private static boolean writeLevel(Canvas c, DropperGeometry.Shaft s, DropRules.Level tier, int i, int n,
                                      Rect ledge, int[] rows, LayerKit.Shape[] shapes, Rect[] open, Rect[] twinOpen,
                                      Rect[] decoys, Rect pool, Path main, Path second) {
        int layers = rows.length;
        boolean[][][] solid = new boolean[layers][][];
        boolean[][][] light = new boolean[layers][][];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int j = 0; j < layers; j++) {
                solid[j] = new boolean[DropperGeometry.INSIDE][DropperGeometry.INSIDE];
                light[j] = new boolean[DropperGeometry.INSIDE][DropperGeometry.INSIDE];
                boolean lit = j < tier.litLayers();
                Rect ring = open[j].grow(1);
                for (int a = 0; a < DropperGeometry.INSIDE; a++) {
                    for (int b = 0; b < DropperGeometry.INSIDE; b++) {
                        int x = s.x1() + a;
                        int z = s.z1() + b;
                        boolean on = shapes[j].solid(a, b);
                        if (lit && ring.contains(x, z) && !open[j].contains(x, z)) {
                            on = true;
                            light[j][a][b] = true;
                        }
                        if (j > 0 && decoys[j - 1] != null && decoys[j - 1].contains(x, z)) {
                            on = true;
                        }
                        if (open[j].contains(x, z) || (twinOpen[j] != null && twinOpen[j].contains(x, z))
                                || (decoys[j] != null && decoys[j].contains(x, z)) || main.tubeHas(rows[j], x, z)
                                || (second != null && second.tubeHas(rows[j], x, z))) {
                            on = false;
                        }
                        solid[j][a][b] = on;
                        light[j][a][b] &= on;
                    }
                }
            }
            for (int j = 0; j < layers - 1; j++) {
                Rect d = decoys[j];
                if (d == null) {
                    continue;
                }
                boolean blocked = true;
                for (int x = d.x1(); x <= d.x2() && blocked; x++) {
                    for (int z = d.z1(); z <= d.z2() && blocked; z++) {
                        blocked = solid[j + 1][x - s.x1()][z - s.z1()];
                    }
                }
                if (!blocked) {
                    decoys[j] = null;
                    shapes[j] = new LayerKit.Shape(LayerKit.Template.PLATE, 0, 0);
                    changed = true;
                }
            }
        }
        for (int j = 0; j < layers; j++) {
            int count = 0;
            for (boolean[] col : solid[j]) {
                for (boolean b : col) {
                    count += b ? 1 : 0;
                }
            }
            if (count < MIN_SOLID) {
                return false;
            }
        }
        c.clearInside(s);
        int surfaceY = DropperGeometry.surface(s, tier);
        int floor = DropperGeometry.floorRow(s, tier);
        int rim = i == n - 1 ? Canvas.RIM_LAST : Canvas.RIM;
        for (int x = ledge.x1(); x <= ledge.x2(); x++) {
            for (int z = ledge.z1(); z <= ledge.z2(); z++) {
                c.put(x, s.ledgeRow(), z, Canvas.LEDGE);
            }
        }
        for (int x = s.x1(); x <= s.x2(); x++) {
            for (int z = s.z1(); z <= s.z2(); z++) {
                c.put(x, floor, z, tier.wholeFloor() ? Canvas.LIGHT : rim);
                for (int y = surfaceY - DropperGeometry.POOL_DEPTH; y < surfaceY; y++) {
                    c.put(x, y, z, pool.contains(x, z) ? Canvas.WATER : rim);
                }
            }
        }
        for (int j = 0; j < layers; j++) {
            for (int a = 0; a < DropperGeometry.INSIDE; a++) {
                for (int b = 0; b < DropperGeometry.INSIDE; b++) {
                    if (solid[j][a][b]) {
                        c.put(s.x1() + a, rows[j], s.z1() + b, light[j][a][b] ? Canvas.LIGHT : Canvas.PLATE0 + i);
                    }
                }
            }
        }
        return true;
    }

    /**
     * The half being planned: what each block does ({@link DropWorld}, for flying) and which block it
     * is (for the plan's ops).
     */
    private static final class Canvas {
        static final int LEDGE = 0;
        static final int RIM = 1;
        static final int RIM_LAST = 2;
        static final int LIGHT = 3;
        static final int WATER = 4;
        static final int CLEAR = 5;
        static final int GLASS0 = 6;
        static final int PLATE0 = 11;
        static final List<String> BLOCKS;
        static final byte[] KINDS;

        static {
            List<String> b = new ArrayList<>(List.of(DropBlocks.LEDGE, DropBlocks.RIM, DropBlocks.RIM_LAST,
                    DropBlocks.LIGHT, DropBlocks.WATER, "minecraft:glass"));
            for (int i = 0; i < DropRules.MAX_LEVELS; i++) {
                b.add(DropBlocks.glass(i));
            }
            for (int i = 0; i < DropRules.MAX_LEVELS; i++) {
                b.add(DropBlocks.plate(i));
            }
            BLOCKS = List.copyOf(b);
            KINDS = new byte[BLOCKS.size()];
            for (int i = 0; i < KINDS.length; i++) {
                KINDS[i] = DropBlocks.kind(BLOCKS.get(i));
            }
        }

        final Box half;
        final DropWorld world;
        final byte[] ids;

        Canvas(Box half) {
            this.half = half;
            this.world = new DropWorld(half);
            this.ids = new byte[(int) half.volume()];
        }

        void put(int x, int y, int z, int block) {
            if (!half.contains(x, y, z)) {
                throw new IllegalStateException("a dropper block outside its half: " + x + "," + y + "," + z);
            }
            ids[index(x, y, z)] = (byte) (block + 1);
            world.set(x, y, z, KINDS[block]);
        }

        void clear(int x, int y, int z) {
            ids[index(x, y, z)] = 0;
            world.set(x, y, z, DropWorld.AIR);
        }

        /** Block (x, y, z)'s entry in {@link #BLOCKS}, or -1 for air. */
        int block(int x, int y, int z) {
            return ids[index(x, y, z)] - 1;
        }

        /** Empty a shaft's inside, top to bottom. */
        void clearInside(DropperGeometry.Shaft s) {
            for (int y = half.minY(); y <= half.maxY(); y++) {
                for (int x = s.x1(); x <= s.x2(); x++) {
                    for (int z = s.z1(); z <= s.z2(); z++) {
                        clear(x, y, z);
                    }
                }
            }
        }

        /**
         * A shaft's four walls, from its pool's floor to 4 above its ledge: its own colour, except the
         * columns shared with a neighbour, which are clear.
         */
        void walls(DropperGeometry.Shaft s, DropRules.Level tier, int i, int n) {
            int floor = DropperGeometry.floorRow(s, tier);
            for (int y = floor; y <= s.wallTop(); y++) {
                for (int x = s.x1() - 1; x <= s.x2() + 1; x++) {
                    for (int z = s.z1() - 1; z <= s.z2() + 1; z++) {
                        if (s.inside(x, z)) {
                            continue;
                        }
                        boolean shared = (x == s.x1() - 1 && i > 0) || (x == s.x2() + 1 && i < n - 1);
                        put(x, y, z, shared ? CLEAR : GLASS0 + i);
                    }
                }
            }
        }

        private int index(int x, int y, int z) {
            return ((y - half.minY()) * half.sizeX() + (x - half.minX())) * half.sizeZ() + (z - half.minZ());
        }
    }

    // ---- from levels to a plan -----------------------------------------------------------------------

    private Plan toPlan(PlanInput in, String mix, Canvas c, List<LevelPlan> levels, long work, int redraws,
                        int widenings, int safe) {
        Box half = in.half();
        List<String> palette = new ArrayList<>();
        int[] paletteOf = new int[Canvas.BLOCKS.size()];
        Arrays.fill(paletteOf, -1);
        List<BlockOp> ops = new ArrayList<>();
        for (int y = half.minY(); y <= half.maxY(); y++) {
            for (int x = half.minX(); x <= half.maxX(); x++) {
                for (int z = half.minZ(); z <= half.maxZ(); z++) {
                    int b = c.block(x, y, z);
                    if (b < 0) {
                        continue;
                    }
                    if (paletteOf[b] < 0) {
                        paletteOf[b] = palette.size();
                        palette.add(Canvas.BLOCKS.get(b));
                    }
                    ops.add(new BlockOp(x, y, z, (short) paletteOf[b]));
                }
            }
        }
        int n = levels.size();
        List<SignText> signs = new ArrayList<>();
        List<Box> keepClear = new ArrayList<>();
        List<Course.Mark> checkpoints = new ArrayList<>();
        Course.Mark finish = null;
        int lowestFloor = Integer.MAX_VALUE;
        for (LevelPlan lp : levels) {
            DropperGeometry.Shaft s = lp.shaft();
            signs.add(sign(lp, n));
            keepClear.add(new Box(s.x1(), DropperGeometry.floorRow(s, lp.tier()) + 1, s.z1(), s.x2(), half.maxY(),
                    s.z2()));
            lowestFloor = Math.min(lowestFloor, DropperGeometry.floorRow(s, lp.tier()));
            if (lp.index() > 0) {
                checkpoints.add(lp.ledgeMark());
            }
            if (lp.index() < n - 1) {
                checkpoints.add(lp.poolMark());
            } else {
                finish = lp.poolMark();
            }
        }
        LevelPlan first = levels.get(0);
        Course.Mark firstLedge = first.ledgeMark();
        Course.Spot start = new Course.Spot(firstLedge.x(), firstLedge.y(), firstLedge.z(),
                DropMarks.yaw(first.forward().ux(), first.forward().uz()), START_PITCH);
        Slots.Def slot = in.slot();
        long refMs = DropRules.refMs(mix);
        int minSeconds = DropRules.minSeconds(mix);
        Course course = new Course(slot.id(), KIND, slot.name(), DropRules.tier(mix), "", start, checkpoints, finish,
                (double) (lowestFloor - DropperGeometry.FALL_BELOW_FLOOR), minSeconds, true, false, 1);

        List<String> summary = new ArrayList<>();
        summary.add(slot.name() + " (" + mix + "): " + n + " level" + (n == 1 ? "" : "s") + ", reference "
                + String.format(Locale.ROOT, "%.1f", refMs / 1000.0) + "s, shortest " + minSeconds + "s");
        for (LevelPlan lp : levels) {
            StringBuilder sb = new StringBuilder("level " + (lp.index() + 1) + " (" + lp.tier().letter() + ", "
                    + DropBlocks.COLOURS.get(lp.index()) + (lp.safe() ? ", straight" : "") + "): ledge facing "
                    + lp.forward().word() + ", layers");
            for (int j = 0; j < lp.rows().length; j++) {
                sb.append(j == 0 ? " " : " / ").append(s(lp, j));
            }
            sb.append(", pool ").append(lp.pool().size());
            summary.add(sb.toString());
            summary.add(DropperValidator.WITNESS_PREFIX.formatted(lp.index() + 1) + lp.witness().normalised().encode());
            if (lp.twin() != null) {
                summary.add(DropperValidator.TWIN_PREFIX.formatted(lp.index() + 1) + lp.twin().normalised().encode());
            }
        }
        summary.add("seed " + GenSeed.shortHex(in.seed()) + ", " + work + " ticks flown, " + redraws + " redraw"
                + (redraws == 1 ? "" : "s") + ", " + widenings + " widened, " + safe + " straight");
        return Plan.of(slot.id(), ALGO, in.seed(), half, palette, ops, signs, keepClear,
                new PlannedTrial(course, refMs), summary, work);
    }

    /** One layer for the admin line: depth, template and opening. */
    private static String s(LevelPlan lp, int j) {
        int depth = lp.shaft().ledgeTop() - lp.rows()[j] - 1;
        String out = depth + " " + lp.shapes()[j].describe() + " " + lp.open()[j].size();
        if (lp.twinOpen()[j] != null) {
            out += "+" + lp.twinOpen()[j].size();
        }
        if (lp.decoys()[j] != null) {
            out += " decoy";
        }
        return out;
    }

    /** A level's sign, on the wall over its ledge, facing into the shaft. */
    static SignText sign(LevelPlan lp, int n) {
        Rect l = lp.ledge();
        DropperGeometry.Shaft s = lp.shaft();
        int y = s.ledgeTop() + DropperGeometry.SIGN_ABOVE;
        int mx = l.x1() + 1;
        int mz = l.z1() + 1;
        int[] at = switch (lp.forward()) {
            case S -> new int[]{mx, s.z1()};
            case N -> new int[]{mx, s.z2()};
            case E -> new int[]{s.x1(), mz};
            case W -> new int[]{s.x2(), mz};
            default -> throw new IllegalStateException("a ledge faces along an axis");
        };
        return new SignText(at[0], y, at[1], Palette.wallSign(facing(lp.forward())), signLines(lp.index() + 1, n));
    }

    /** The word a wall sign's {@code facing} state takes for a direction. */
    static String facing(DropProgram.Dir d) {
        return switch (d) {
            case N -> "north";
            case S -> "south";
            case E -> "east";
            case W -> "west";
            default -> throw new IllegalArgumentException("a sign faces along an axis: " + d);
        };
    }

    /** A level's sign: "LEVEL 2 of 5" / "Step off and" / "fall into the" / "WATER!". */
    public static List<String> signLines(int level, int levels) {
        return List.of("LEVEL " + level + " of " + levels, "Step off and", "fall into the", "WATER!");
    }
}
