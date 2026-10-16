package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mountain Run v2's search (MOUNTAIN-V2-SPEC §5.2, BoatPlanner ALGO 4), on the planner thread: cheap
 * candidates first, the expensive build only for the best of them, and a safe layout that is never
 * missing.
 *
 * <p><b>Stage A</b> (milliseconds each, up to {@value #CANDIDATES}): a {@link Frame}, a {@link Skeleton},
 * the {@link DropPlan}, the {@link BoatLine} ride and the {@link FlowScore}, each from its own fork of the
 * seed ({@code frame:i}, {@code route:i}, {@code drops:i}); a candidate is kept when every flow gate holds
 * (the model time in the tier's window among them) and its corridors keep their clearance. It stops early
 * once {@value #KEPT} kept candidates score {@value #EARLY} or more, and goes on past {@value #CANDIDATES}
 * (up to {@value #CANDIDATES_MAX}) only while none is kept.
 *
 * <p><b>Stage B</b> (about a second each): the kept candidates by score (ties by number), at most
 * {@value #KEPT}: the pieces ({@code pieces:i}, each kept only while the checkpoints still fit round it),
 * the raster, the checkpoints, the mountain ({@code scenery:i}), the plan, and the proof
 * ({@link MountainValidator}); the first proven plan is the layout. A refused build is tried again without
 * the pieces nearest where its proof failed. Then fewer pieces (REDUCED), then none (BASIC), on the best
 * candidates; then {@code SAFE_ROAD} / {@code SAFE_SLALOM}: a fixed, proven skeleton per style, tier and
 * mirror ({@link #safe}).
 *
 * <p><b>Work</b> ({@link #BUDGET} units): a Stage A candidate 1, a Stage B attempt {@value #UNIT_B},
 * {@value #SAFE_RESERVE} kept for the safe layout. Cancels are checked between candidates, between a build's
 * stages, every {@value RasterV4#TICK_ROWS} rows of the raster's and the mountain's passes, and before and after
 * the proof (audit MTN04); a check only throws or sleeps (GenService's online throttle), never changes a block.
 * Everything is counted work, never time, so a seed makes the same course on every host (rule R6). Pure: no
 * Bukkit.
 */
final class MountainPlanner {

    /** The version: Mountain Run v2. */
    static final int ALGO = 4;
    /** Stage A candidates, those Stage B tries, and the score that ends Stage A early. */
    static final int CANDIDATES = 48;
    /**
     * While none of the first {@value #CANDIDATES} is kept, Stage A goes on up to this many: the hard road
     * (descent 44 or more under the checkpoint rules) keeps about one candidate in 60. Within the budget:
     * {@value #CANDIDATES_MAX} + 4 Stage B builds + the safe reserve is under {@value #BUDGET}.
     */
    static final int CANDIDATES_MAX = 360;
    static final int KEPT = 6;
    static final double EARLY = 0.75;
    /** Work units: the whole budget, a Stage B attempt, the safe layout's reserve. */
    static final long BUDGET = 1_000;
    static final long UNIT_B = 100;
    static final long SAFE_RESERVE = 200;
    /** The half a Mountain Run v2 needs, exactly (§4.1). */
    static final int SIZE_X = 480;
    static final int SIZE_Y = 176;
    static final int SIZE_Z = 640;
    /** Two corridors not next to each other along the track keep this much terrain between them (§4.2). */
    static final double CLEAR = 6;
    static final double CLEAR_STEP = 12;
    /** Richness: every piece, fewer, none. */
    static final int FULL = 0;
    static final int REDUCED = 1;
    static final int BASIC = 2;

    private MountainPlanner() {
    }

    /** A Stage A candidate: its number, route, drops and flow. */
    record Candidate(int index, Skeleton sk, DropPlan drops, FlowScore flow) {
    }

    /** A plan made, and what it was made from (the tests read them). */
    static final class Made {
        final PlanInput in;
        final MountainTier tier;
        final Candidate cand;
        final PiecesV4 pieces;
        final RasterV4 raster;
        final List<RasterV4.Spot> checkpoints;
        final Plan plan;
        final int trees;
        final double seconds;
        long work;
        String how;
        /** Pieces a failed proof's retry left out (the ones nearest where it failed), 0 for none. */
        int leftOut;

        Made(PlanInput in, MountainTier tier, Candidate cand, PiecesV4 pieces, RasterV4 raster,
             List<RasterV4.Spot> checkpoints, Plan plan, int trees, double seconds) {
            this.in = in;
            this.tier = tier;
            this.cand = cand;
            this.pieces = pieces;
            this.raster = raster;
            this.checkpoints = List.copyOf(checkpoints);
            this.plan = plan;
            this.trees = trees;
            this.seconds = seconds;
        }

        /** The plan with its admin summary and the work it took (neither is in its hash). */
        Plan finished() {
            Plan p = plan;
            return new Plan(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(),
                    p.course(), summary(), work, p.hash());
        }

        List<String> summary() {
            Skeleton sk = cand.sk();
            DropPlan dp = cand.drops();
            List<String> out = new ArrayList<>();
            int hairpins = 0;
            int elbows = 0;
            int bulbs = 0;
            for (int k = 0; k < sk.frame.bands; k++) {
                switch (sk.frame.links[k].kind()) {
                    case HAIRPIN -> hairpins++;
                    case ELBOWS -> elbows++;
                    case BULB -> bulbs++;
                }
            }
            out.add(in.slot().name() + " v" + ALGO + " " + tier.id + ": " + tier.style.title() + ", "
                    + String.format(Locale.ROOT, "%,d", Math.round(sk.finish - sk.start)) + " blocks, " + sk.frame.bands
                    + " bands (" + hairpins + " hairpins, " + elbows + " elbows, " + bulbs + " bulbs), " + how);
            out.add(cand.flow().summary() + String.format(Locale.ROOT, " - model T_m %.1f s", modelMs(seconds) / 1000.0));
            DropPlan.Drop last = dp.last();
            StringBuilder pieceLine = new StringBuilder();
            if (tier.slalom()) {
                int gates = 0;
                for (Skeleton.GateSet g : sk.gates) {
                    gates += g.gates();
                }
                pieceLine.append("gate sets ").append(sk.gates.size()).append(" (").append(gates).append(" gates)");
            } else {
                // placed of dealt, per kind (audit MTN-R3-00): a thin deck shows in the preview
                int dealt = 0;
                for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
                    dealt += pieces.dealt(k);
                }
                pieceLine.append("pieces ").append(pieces.list.size()).append(" of ").append(dealt).append(" (pits ")
                        .append(of(pieces, PiecesV4.Kind.SAND_PIT)).append(", splits ")
                        .append(of(pieces, PiecesV4.Kind.SPLIT)).append(", caves ")
                        .append(of(pieces, PiecesV4.Kind.CAVE)).append(", tunnels ")
                        .append(of(pieces, PiecesV4.Kind.TUNNEL)).append(", forests ")
                        .append(of(pieces, PiecesV4.Kind.FOREST)).append(", boosts ")
                        .append(of(pieces, PiecesV4.Kind.BOOST)).append("), blue straights ")
                        .append(dp.blue.size()).append(", run-offs ").append(pieces.runoffs());
            }
            out.add("drops " + dp.drops.size() + " (" + dp.bigs() + " big, " + dp.descent() + " down), Final Drop "
                    + (last == null ? 0 : last.drop()) + " (" + (last == null ? 0 : Math.round(sk.finish - last.s()))
                    + " before the finish) - staircases " + stairs(dp) + " - " + pieceLine + " - trees " + trees);
            out.add("grid " + RaceGrid.MAX_SPOTS + " (double) - checkpoints " + checkpoints.size()
                    + " - stand at the bottom - " + String.format(Locale.ROOT, "%,d", plan.ops().size()) + " blocks");
            Course c = ((PlannedTrial) plan.course()).course();
            long ref = ((PlannedTrial) plan.course()).refMs();
            out.add("reference " + String.format(Locale.ROOT, "%.2f", ref / 1000.0) + " s for the stars (0.8 of T_m "
                    + String.format(Locale.ROOT, "%.1f", modelMs(seconds) / 1000.0) + " s), shortest " + c.minSeconds()
                    + " s - seed " + GenSeed.shortHex(in.seed()));
            return out;
        }
    }

    /** "placed/dealt" of one kind of piece. */
    static String of(PiecesV4 pieces, PiecesV4.Kind k) {
        return pieces.count(k) + "/" + pieces.dealt(k);
    }

    /**
     * The model time T_m in milliseconds: the line model's seconds rounded up to a tenth. It times F-T
     * and Race Night's windows; the stars get {@link #starRefMs} (red-team F04).
     */
    static long modelMs(double seconds) {
        return (long) Math.ceil(seconds * 10 - 1e-9) * 100;
    }

    /**
     * The reference time the stars are set from (red-team F04): {@value #STAR_SHARE} of T_m, so a medium gold
     * needs about 0.83 of model speed and silver 0.57, never under the shortest time + 1 s. T_m rounded up
     * to 100 ms times 4/5 is a whole number of milliseconds, so {@link BoatPlanner#modelMs} gets T_m back
     * exactly from a tag (the floor never binds on a proven plan: the shortest time is the top-speed line).
     */
    static long starRefMs(double seconds, int minSeconds) {
        return Math.max(modelMs(seconds) * 4 / 5, minSeconds * 1000L + 1000);
    }

    /** The share of T_m the star reference is. */
    static final double STAR_SHARE = 0.8;

    /** How many staircases (two or more STAIR lips on one straight run, {@link DropPlan#staircases}) a drop plan has. */
    static int stairs(DropPlan dp) {
        return dp.staircases();
    }

    // ---- the search ---------------------------------------------------------------------------------------

    /** The proven Mountain Run v2 for {@code in}. */
    static Made made(PlanInput in) throws GenFailed {
        Box half = in.half();
        if (half.sizeX() != SIZE_X || half.sizeY() != SIZE_Y || half.sizeZ() != SIZE_Z) {
            throw new GenFailed("the area " + half.describe() + " isn't a Mountain Run v2 half: it is " + SIZE_X
                    + " x " + SIZE_Y + " x " + SIZE_Z);
        }
        if (Math.floorMod(half.minX(), 16) != 0 || Math.floorMod(half.minZ(), 16) != 0) {
            throw new GenFailed("the area " + half.describe() + " isn't on chunk corners");
        }
        String level = in.slot().normalise(in.tierOrMix());
        BoatStyle style = BoatStyle.of(in.seed());
        MountainTier tier = MountainTier.of(style, level);
        if (tier == null) {
            throw new GenFailed("'" + in.tierOrMix() + "' isn't an Ice Boat tier (easy, medium or hard)");
        }
        GenRandom root = new GenRandom(in.seed());
        long budget = in.workBudget() > 0 ? in.workBudget() : BUDGET;
        long work = 0;
        // Stage A
        List<Candidate> kept = new ArrayList<>();
        int good = 0;
        for (int i = 0; i < CANDIDATES_MAX; i++) {
            in.checkCancelled();
            if (work + 1 > budget - SAFE_RESERVE || (i >= CANDIDATES && !kept.isEmpty())) {
                break;
            }
            work++;
            Candidate c = candidate(root, tier, i);
            if (c == null) {
                continue;
            }
            kept.add(c);
            good += c.flow().total >= EARLY ? 1 : 0;
            if (good >= KEPT) {
                break;
            }
        }
        kept.sort((a, b) -> a.flow().total != b.flow().total ? Double.compare(b.flow().total, a.flow().total)
                : Integer.compare(a.index(), b.index()));
        // Stage B: every piece on the best, then fewer, then none
        int[][] steps = {{FULL, Math.min(KEPT, kept.size())}, {REDUCED, Math.min(2, kept.size())},
                {BASIC, Math.min(1, kept.size())}};
        int tries = 0;
        for (int[] step : steps) {
            for (int k = 0; k < step[1]; k++) {
                in.checkCancelled();
                if (work + UNIT_B > budget - SAFE_RESERVE) {
                    break;
                }
                work += UNIT_B;
                tries++;
                Candidate c = kept.get(k);
                Made m = attempt(in, tier, c, root.fork("pieces:" + c.index() + ":" + step[0]),
                        root.fork("scenery:" + c.index()), step[0]);
                if (m != null) {
                    m.work = work;
                    m.how = "candidate " + (c.index() + 1) + " (" + kept.size() + " kept), build " + tries
                            + (step[0] == FULL ? "" : step[0] == REDUCED ? " (fewer pieces)" : " (no pieces)")
                            + (m.leftOut == 0 ? "" : " (" + m.leftOut + (m.leftOut == 1 ? " piece" : " pieces")
                            + " left out for the proof)");
                    return m;
                }
            }
        }
        in.checkCancelled();
        Made safe = safe(in, tier, root);
        if (safe == null) {
            throw new GenFailed("no " + tier + " Mountain Run found for seed " + GenSeed.shortHex(in.seed()));
        }
        safe.work = work + SAFE_RESERVE;
        safe.how = "the safe " + (tier.slalom() ? "slalom" : "road") + " after " + kept.size() + " candidates and "
                + tries + " builds";
        return safe;
    }

    /** Stage A candidate {@code i}: frame, route, drops and flow; {@code null} unless every gate holds. */
    static Candidate candidate(GenRandom root, MountainTier tier, int i) {
        try {
            Frame f = Frame.draw(root.fork("frame:" + i), tier);
            if (f == null) {
                return null;
            }
            Skeleton sk = Skeleton.draw(root.fork("route:" + i), f);
            if (sk == null) {
                return null;
            }
            DropPlan dp = DropPlan.draw(root.fork("drops:" + i), sk);
            if (dp == null || !clearance(sk, dp)) {
                return null;
            }
            FlowScore fs = FlowScore.of(sk, dp);
            return fs.passes() ? new Candidate(i, sk, dp, fs) : null;
        } catch (RuntimeException e) {
            return null; // a candidate that can't come together is just a candidate dropped
        }
    }

    /**
     * §4.2's clearance: any two parts of the corridor not next to each other along the track keep
     * {@value #CLEAR} columns of terrain between them ({@value #CLEAR_STEP} where their levels differ by
     * more than 6), on centreline samples every 2 blocks.
     */
    static boolean clearance(Skeleton sk, DropPlan dp) {
        double step = 2;
        int n = (int) (sk.end / step) + 1;
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] half = new double[n];
        int[] lvl = new int[n];
        Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double s = Math.min(sk.end, i * step);
            double[] p = sk.line.at(s);
            xs[i] = p[0];
            zs[i] = p[1];
            half[i] = dp.width(s) / 2.0 + 1 + (sk.tier.road() ? PiecesV4.RUNOFF : 0);
            lvl[i] = dp.levelAbove(s);
            buckets.computeIfAbsent(key(p[0], p[1]), k -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < n; i++) {
            int bx = (int) Math.floor(xs[i] / 32);
            int bz = (int) Math.floor(zs[i] / 32);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> list = buckets.get(((long) (bx + dx) << 32) ^ ((bz + dz) & 0xFFFFFFFFL));
                    if (list == null) {
                        continue;
                    }
                    for (int j : list) {
                        if (j <= i || (j - i) * step < 60) {
                            continue;
                        }
                        double need = half[i] + half[j] + (Math.abs(lvl[i] - lvl[j]) > 6 ? CLEAR_STEP : CLEAR);
                        if (Math.hypot(xs[i] - xs[j], zs[i] - zs[j]) < need) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static long key(double x, double z) {
        return ((long) (int) Math.floor(x / 32) << 32) ^ (((int) Math.floor(z / 32)) & 0xFFFFFFFFL);
    }

    /**
     * Stage B for candidate {@code c} at {@code richness}: pieces, raster, checkpoints, blocks, mountain,
     * plan, proof; refused, it is built again (at most {@value #LEAVE_OUT_ROUNDS} times) without the pieces
     * nearest where the proof failed ({@link #near}); {@code null} when it doesn't come together or isn't proven.
     */
    static Made attempt(PlanInput in, MountainTier tier, Candidate c, GenRandom pieceStream, GenRandom scenery,
                        int richness) throws GenFailed {
        try {
            Base base = base(in, c);
            if (base == null) {
                return null;
            }
            Made m = build(in, tier, c, base, pieces(in, c, base, pieceStream, richness), scenery);
            if (m == null) {
                return null;
            }
            cancel(in);
            List<String> problems = proof(m.plan, tier.id, in);
            cancel(in);
            // a refused plan with pieces is built again without those nearest where the proof failed, so a piece
            // that upsets a checkpoint costs that piece, not the whole deck (the fewer-pieces build that follows
            // would put it in the same place)
            for (int round = 0; round < LEAVE_OUT_ROUNDS && !problems.isEmpty() && !m.pieces.list.isEmpty(); round++) {
                PiecesV4 fewer = m.pieces.without(near(m, problems));
                if (fewer.list.size() == m.pieces.list.size()) {
                    break;
                }
                Made again = build(in, tier, c, base, fewer, scenery);
                if (again == null) {
                    break;
                }
                again.leftOut = m.leftOut + m.pieces.list.size() - fewer.list.size();
                m = again;
                cancel(in);
                problems = proof(m.plan, tier.id, in);
                cancel(in);
            }
            return problems.isEmpty() ? m : null;
        } catch (Cancelled e) {
            throw new GenFailed("cancelled");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The proof every Stage B plan must pass: {@link MountainValidator}, the same the engine runs again, with
     * {@code in}'s cancel check between its steps (audit MTN04; it changes no verdict).
     */
    static List<String> proof(Plan plan, String tier, PlanInput in) {
        return MountainValidator.problems(plan, tier, () -> cancel(in));
    }

    /** Thrown inside a build when the job was cancelled. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    /** How many times a refused plan is built again with the pieces nearest the failure left out. */
    static final int LEAVE_OUT_ROUNDS = 2;
    /** Pieces within this along of where the proof failed are left out (a leg either way). */
    static final double LEAVE_OUT_REACH = 60;
    /** How the proof names a checkpoint ("checkpoint 58", counted from 1) and a block ("at 6472 121 3121"). */
    private static final Pattern NAMED_CHECKPOINT = Pattern.compile("checkpoint (\\d+)");
    private static final Pattern NAMED_BLOCK = Pattern.compile("at (-?\\d+) (-?\\d+) (-?\\d+)");

    /**
     * The stretches along the line where {@code m}'s proof failed: between the checkpoints a problem names
     * ("checkpoint 58 to checkpoint 59"), and round the blocks it names ("at x y z"), {@value #LEAVE_OUT_REACH}
     * either way; none when it names neither.
     */
    static List<double[]> near(Made m, List<String> problems) {
        Skeleton sk = m.cand.sk();
        Box half = m.in.half();
        List<double[]> out = new ArrayList<>();
        for (String p : problems) {
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            Matcher a = NAMED_CHECKPOINT.matcher(p);
            while (a.find()) {
                int i = Integer.parseInt(a.group(1)) - 1;
                if (i >= 0 && i < m.checkpoints.size()) {
                    lo = Math.min(lo, m.checkpoints.get(i).s());
                    hi = Math.max(hi, m.checkpoints.get(i).s());
                }
            }
            Matcher b = NAMED_BLOCK.matcher(p);
            while (b.find()) {
                Centreline.Near n = sk.line.nearest(Integer.parseInt(b.group(1)) - half.minX() + 0.5,
                        Integer.parseInt(b.group(3)) - half.minZ() + 0.5);
                if (n != null) {
                    lo = Math.min(lo, n.s());
                    hi = Math.max(hi, n.s());
                }
            }
            if (lo <= hi) {
                out.add(new double[]{lo - LEAVE_OUT_REACH, hi + LEAVE_OUT_REACH});
            }
        }
        return out;
    }

    /** A candidate's raster with no pieces and its checkpoint places: what every build of it starts from. */
    record Base(RasterV4 raster, List<RasterV4.Spot> spots) {
    }

    /** Candidate {@code c}'s {@link Base}, or {@code null} when it has no raster or no checkpoint chain. */
    static Base base(PlanInput in, Candidate c) {
        Skeleton sk = c.sk();
        cancel(in);
        RasterV4 raster = new RasterV4(in.half(), sk, c.drops(), PiecesV4.none(sk), () -> cancel(in));
        if (raster.problem != null) {
            return null;
        }
        cancel(in);
        List<RasterV4.Spot> spots = raster.spots(List.of());
        cancel(in);
        return raster.chain(spots) == null ? null : new Base(raster, spots);
    }

    /** Candidate {@code c}'s pieces at {@code richness}, each kept only while the base's checkpoint chain holds. */
    static PiecesV4 pieces(PlanInput in, Candidate c, Base base, GenRandom pieceStream, int richness) {
        return PiecesV4.draw(pieceStream, c.sk(), c.drops(), richness, blocked -> {
            cancel(in);
            return base.raster.chain(without(base.raster, base.spots, blocked)) != null;
        });
    }

    /**
     * The plan of candidate {@code c} from its {@link Base} with {@code pieces}: every block, sign and mark (not
     * yet proven); {@code null} when it doesn't fit. The base's raster is used as it is when there are no pieces,
     * so a base is built on that way once.
     */
    static Made build(PlanInput in, MountainTier tier, Candidate c, Base from, PiecesV4 pieces, GenRandom scenery) {
        Box half = in.half();
        Skeleton sk = c.sk();
        DropPlan dp = c.drops();
        // the cancel check between the stages and inside their passes (audit MTN04): it only throws or sleeps
        Runnable tick = () -> cancel(in);
        RasterV4 base = from.raster;
        List<RasterV4.Spot> baseSpots = from.spots;
        // the ride with the pieces' boost strips: T_m stays in the tier's window (F-T held it without them)
        List<double[]> fast = new ArrayList<>(dp.fast());
        fast.addAll(pieces.fast());
        BoatLine.Result ride = BoatLine.run(RideLine.of(sk, dp.drops, fast).segs());
        if (ride.seconds() < tier.tMin || ride.seconds() > tier.tMax) {
            return null;
        }
        cancel(in);
        RasterV4 raster = pieces.list.isEmpty() ? base : new RasterV4(half, sk, dp, pieces, tick);
        if (raster.problem != null) {
            return null;
        }
        cancel(in);
        List<RasterV4.Spot> cps = raster == base ? base.chain(baseSpots) : raster.chain(raster.spots(pieces.blocked()));
        if (cps == null || cps.size() > MountainValidator.MAX_CHECKPOINTS) {
            return null;
        }
        cancel(in);
        raster.blocks();
        raster.walls();
        cancel(in);
        raster.structures(scenery.fork("islands"));
        raster.markers(cps);
        raster.stand();
        signs(raster, cps, pieces);
        cancel(in);
        int trees = MountainScenery.draw(scenery, raster);
        cancel(in);
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = finishBlocks(raster, palette);
        cancel(in);
        if (ops.size() > MountainValidator.MAX_OPS) {
            return null;
        }
        // the course: a sprint from the pit on the summit to the gold finish in front of the stand
        int wx = half.minX();
        int wz = half.minZ();
        double[] st = sk.line.at(sk.start);
        double[] tan = sk.line.tangent(sk.start);
        Course.Spot start = new Course.Spot(wx + exact(st[0]), raster.topIce + 1, wz + exact(st[1]),
                (float) TrackRaster.yaw(tan[0], tan[1]), 0f);
        List<Course.Mark> marks = new ArrayList<>();
        for (RasterV4.Spot s : cps) {
            marks.add(mark(half, s));
        }
        Course.Mark finish = finishMark(raster);
        Slots.Def slot = in.slot();
        Course draft = new Course(slot.id(), TrialKind.BOAT, slot.name(), Tier.of(tier.id), "", start, marks, finish,
                (double) (raster.lowest() - 3), null, true, false, 1);
        int min = MountainValidator.minSeconds(draft);
        Course course = draft.withMinSeconds(min);
        if (modelMs(ride.seconds()) * 4 / 5 < min * 1000L + 1000) {
            return null; // never on a real run (the shortest time is the top-speed line); keeps T_m exact in the tag
        }
        long refMs = starRefMs(ride.seconds(), min);
        cancel(in);
        Plan plan = Plan.of(slot.id(), ALGO, in.seed(), half, palette, ops, raster.signs, keepClear(raster),
                new PlannedTrial(course, refMs), List.of(), 0);
        return new Made(in, tier, c, pieces, raster, cps, plan, trees, ride.seconds());
    }

    private static void cancel(PlanInput in) {
        if (in.cancelled().getAsBoolean()) {
            throw new Cancelled();
        }
    }

    /**
     * A half-local coordinate on a 1/4096 grid, so adding the half's corner is exact and the same layout
     * has the same hash wherever its half is (§10.2).
     */
    static double exact(double v) {
        return Math.round(v * 4096) / 4096.0;
    }

    /**
     * {@code spots} of raster {@code t} less those a blocked stretch covers, by the rule {@link RasterV4#spots}
     * keeps them by ({@link RasterV4#blocks}), so a chain the pieces' trial allows is one their own raster can lay.
     */
    static List<RasterV4.Spot> without(RasterV4 t, List<RasterV4.Spot> spots, List<double[]> blocked) {
        List<RasterV4.Spot> out = new ArrayList<>();
        for (RasterV4.Spot s : spots) {
            if (!t.blocks(blocked, s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Every leaf written with vanilla's own distance over the plan's own blocks; then the ops by (x, z, y). */
    static List<BlockOp> finishBlocks(RasterV4 t, List<String> palette) {
        Voxels v = t.vox;
        List<int[]> logs = new ArrayList<>();
        for (int x = 0; x < v.sx; x++) {
            t.row(x);
            for (int z = 0; z < v.sz; z++) {
                if (!v.any(x, z)) {
                    continue;
                }
                for (int[] b : v.column(x, z)) {
                    if (Palette.holdsLeaves(v.palette.get(b[1]))) {
                        logs.add(new int[]{x, b[0], z});
                    }
                }
            }
        }
        t.tick.run();
        Map<Long, Integer> d = Palette.leafDistances(logs, t.leafAt);
        t.tick.run();
        for (int i = 0; i < t.leafAt.size(); i++) {
            int[] l = t.leafAt.get(i);
            v.put(l[0], l[1], l[2], Palette.leaves(t.leafWood.get(i), d.get(Palette.blockKey(l[0], l[1], l[2]))));
        }
        return v.ops(t.half.minX(), t.half.minZ(), palette);
    }

    /** One keep-clear box per band: its track from the ice + 1 to the ice + 4 (2 over a flight zone's lip + 1). */
    static List<Box> keepClear(RasterV4 t) {
        Map<Integer, int[]> boxes = new HashMap<>();
        for (int x = 0; x < t.sx; x++) {
            t.row(x);
            for (int z = 0; z < t.sz; z++) {
                int i = t.idx(x, z);
                if (t.h[i] == RasterV4.NONE) {
                    continue;
                }
                int band = t.sk.tags.get(t.elAt[i]).band();
                int[] b = boxes.computeIfAbsent(band, k -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE});
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], z);
                b[2] = Math.max(b[2], x);
                b[3] = Math.max(b[3], z);
                b[4] = Math.min(b[4], t.h[i] + 1);
                int up = t.zoneLip[i] != RasterV4.NONE ? t.zoneLip[i] + 3 : t.h[i] + DownhillValidator.HEADROOM;
                b[5] = Math.max(b[5], up);
            }
        }
        List<Box> out = new ArrayList<>();
        for (int band = 0; band <= t.sk.frame.bands; band++) {
            int[] b = boxes.get(band);
            if (b == null) {
                continue;
            }
            out.add(new Box(t.half.minX() + b[0], b[4], t.half.minZ() + b[1], t.half.minX() + b[2],
                    Math.min(t.half.maxY(), b[5]), t.half.minZ() + b[3]));
        }
        return out;
    }

    /**
     * The signs (§7.1): the start's, before every lip (the Final Drop's its own, a staircase's THE CLIFFS),
     * piece, hairpin, chicane, bend with run-off sand and gate set, and HALFWAY by the checkpoint nearest half
     * way down ({@link #halfwaySign}).
     */
    static void signs(RasterV4 t, List<RasterV4.Spot> cps, PiecesV4 pieces) {
        Skeleton sk = t.sk;
        List<Double> used = new ArrayList<>();
        t.sign(sk.start - 4, sk.start + 4, sk.tier.road() ? GenCopy.boatRoadStart(sk.tier.id)
                : GenCopy.boatSlalomStart(sk.tier.id), used);
        DropPlan dp = t.dp;
        // a staircase is the STAIR lips of one straight run (audit MTN02): THE CLIFFS! at its first lip, with
        // that run's count, whatever lies next to it in the list; its other lips get their drop signs. "big
        // drops" only when every step is a 2-block drop (audit MTN-R3-01)
        List<DropPlan.Run> runs = dp.runs();
        DropPlan.Run signed = null;
        for (int i = 0; i < dp.drops.size(); i++) {
            DropPlan.Drop l = dp.drops.get(i);
            DropPlan.Run run = l.kind() == DropPlan.Kind.STAIR ? runOf(runs, l.s()) : null;
            int steps = run == null ? 0 : dp.stairsOn(run);
            List<String> lines;
            if (i == dp.drops.size() - 1) {
                lines = GenCopy.boatFinalDrop();
            } else if (steps >= 2 && !run.equals(signed)) {
                lines = GenCopy.boatCliffs(steps, allBig(dp, run));
                signed = run;
            } else {
                lines = GenCopy.boatDrop(l.drop());
            }
            t.sign(l.s() - RasterV4.SIGN_BEFORE, l.s() - RasterV4.SIGN_NEAR, lines, used);
        }
        for (PiecesV4.Piece p : pieces.list) {
            List<String> lines = switch (p.kind) {
                case SAND_PIT -> GenCopy.boatSandPit();
                case SPLIT -> GenCopy.boatSplit();
                case CAVE -> GenCopy.boatIceCave();
                case TUNNEL -> GenCopy.boatTunnel();
                case FOREST -> GenCopy.boatForest();
                case BOOST -> null;
            };
            if (lines != null) {
                t.sign(p.s1 - RasterV4.SIGN_BEFORE, p.s1 - RasterV4.SIGN_NEAR, lines, used);
            }
        }
        int beat = -1;
        double tight = Double.NEGATIVE_INFINITY; // where the last tight bend (hairpin, chicane, run-off sand) ends
        for (Centreline.Element e : sk.line.elements()) {
            Skeleton.Tag tag = sk.tag(e);
            boolean sandy = e.arc() && pieces.runoff[e.index] > 0;
            if (tag.beat() == beat) {
                tight = sandy ? e.s1() : tight;
                continue;
            }
            if (tag.role() == Skeleton.Role.HAIRPIN) {
                t.sign(e.s0 - RasterV4.SIGN_BEFORE - 4, e.s0 - RasterV4.SIGN_NEAR, GenCopy.boatHairpin(), used);
                beat = tag.beat();
            } else if (tag.role() == Skeleton.Role.CHICANE) {
                // a chicane beat opens with its first arc: the sign says which way that bends (audit MTN00)
                t.sign(e.s0 - RasterV4.SIGN_BEFORE - 4, e.s0 - RasterV4.SIGN_NEAR, GenCopy.boatChicane(e.turn > 0),
                        used);
                beat = tag.beat();
            } else if (sandy && e.s0 - tight > RasterV4.SIGN_BEFORE && e.s0 > sk.start && e.s0 < sk.finish) {
                // any other bend with run-off sand (an elbow, a bend: R under 40), as v3 signed them (audit
                // MTN-R3-02); a second one straight after the first is the same turn
                t.sign(e.s0 - RasterV4.SIGN_BEFORE, e.s0 - RasterV4.SIGN_NEAR, GenCopy.boatSandyBend(), used);
            }
            tight = sandy ? e.s1() : tight;
        }
        for (Skeleton.GateSet g : sk.gates) {
            double from = g.from() - g.spacing();
            t.sign(from - RasterV4.SIGN_BEFORE - 6, from - RasterV4.SIGN_NEAR, GenCopy.boatGates(g.gates()), used);
        }
        if (!cps.isEmpty()) {
            halfwaySign(t, cps, used);
        }
    }

    /** The straight run holding {@code s}, or {@code null}. */
    static DropPlan.Run runOf(List<DropPlan.Run> runs, double s) {
        for (DropPlan.Run run : runs) {
            if (s >= run.s0() && s <= run.s1()) {
                return run;
            }
        }
        return null;
    }

    /** Whether every STAIR lip on {@code run} is a 2-block drop (a "big drop"). */
    static boolean allBig(DropPlan dp, DropPlan.Run run) {
        for (int i = 0; i < dp.drops.size() - 1; i++) {
            DropPlan.Drop d = dp.drops.get(i);
            if (d.kind() == DropPlan.Kind.STAIR && d.s() >= run.s0() && d.s() <= run.s1() && d.drop() < 2) {
                return false;
            }
        }
        return true;
    }

    /**
     * The HALFWAY! sign (audit M00): by the checkpoint the runtime's "Halfway!" title shows at, chosen by the
     * same rule on the course's own marks ({@code BoatHype.halfway}), so the sign and the title can't disagree.
     * Where another sign crowds that spot (or there's no wall top), it moves along the lane, nearest first, but
     * never past half way to the next checkpoint either side, so the sign still stands by its own checkpoint.
     * Whether one went up.
     */
    static boolean halfwaySign(RasterV4 t, List<RasterV4.Spot> cps, List<Double> used) {
        List<Point> at = new ArrayList<>(cps.size());
        for (RasterV4.Spot s : cps) {
            at.add(mark(t.half, s).center());
        }
        int h = BoatHype.halfway(at, finishMark(t).center());
        RasterV4.Spot best = cps.get(h);
        List<String> lines = GenCopy.boatHalfway();
        if (t.sign(best.s() - 2, best.s() + 2, lines, used)) {
            return true;
        }
        double lo = h > 0 ? (cps.get(h - 1).s() + best.s()) / 2 + HALFWAY_MARGIN : t.sk.start;
        double hi = h + 1 < cps.size() ? (best.s() + cps.get(h + 1).s()) / 2 - HALFWAY_MARGIN : t.sk.finish;
        for (int d = 3; best.s() - d >= lo || best.s() + d <= hi; d++) {
            double before = best.s() - d;
            double after = best.s() + d;
            if (before >= lo && t.sign(before, before, lines, used)) {
                return true;
            }
            if (after <= hi && t.sign(after, after, lines, used)) {
                return true;
            }
        }
        return false;
    }

    /** How far short of half way to the next checkpoint a moved HALFWAY! sign stays (blocks along). */
    static final double HALFWAY_MARGIN = 3;

    /** Checkpoint {@code s}'s mark in the course: its middle on the 1/4096 grid ({@link #exact}), on the ice. */
    static Course.Mark mark(Box half, RasterV4.Spot s) {
        return new Course.Mark(half.minX() + exact(s.x()), s.ice() + 1, half.minZ() + exact(s.z()), s.r());
    }

    /** The finish line's mark in the course: in front of the stand, on the finish ice. */
    static Course.Mark finishMark(RasterV4 t) {
        double[] fp = t.sk.line.at(t.sk.finish);
        return new Course.Mark(t.half.minX() + exact(fp[0]), t.finishIce + 1, t.half.minZ() + exact(fp[1]),
                t.finishRadius());
    }

    // ---- the safe layouts ---------------------------------------------------------------------------------

    /** The safe candidates, made once per style, tier and mirror. */
    private static final Map<String, Candidate> SAFE = new ConcurrentHashMap<>();

    /** The safe layouts' fixed streams start here: stream {@code SAFE_BASE + j}, Stage A candidate 0. */
    static final long SAFE_BASE = 0x5AFE_0000L;

    /**
     * Which fixed stream each safe layout is, by style (road, slalom), tier (easy, medium, hard) and mirror
     * (its start at the west end, the east): the first stream whose candidate passes every flow gate with
     * that mirror and is proven (MountainPlannerTest holds all twelve to it, in both halves and off the
     * shipped range, and names the next good stream should a change to the generator move one).
     */
    static final int[][][] SAFE_STREAMS = {
            {{10, 0}, {22, 0}, {247, 76}},
            {{1, 2}, {1, 0}, {5, 0}}};

    /**
     * {@code SAFE_ROAD} / {@code SAFE_SLALOM} (§5.2, red-team F06): a fixed route per style, tier and mirror
     * (the mirror from the seed), an ordinary Stage A candidate that passes every flow gate (F-T, F-F with
     * the tier's minimums, F-P, F-V and the rest), built with no pieces, its mountain from the seed, proven
     * like any other; {@code null} only when even it can't be built here.
     */
    static Made safe(PlanInput in, MountainTier tier, GenRandom root) throws GenFailed {
        boolean west = root.fork("safe").nextBoolean();
        Candidate c = safeCandidate(tier, west);
        if (c == null) {
            return null;
        }
        return attempt(in, tier, c, root.fork("pieces:safe"), root.fork("scenery:safe"), BASIC);
    }

    /** The safe candidate of {@code tier} with its start at the west end or the east ({@link #SAFE_STREAMS}). */
    static Candidate safeCandidate(MountainTier tier, boolean west) {
        return SAFE.computeIfAbsent(tier.style.id() + ":" + tier.id + ":" + west, k -> {
            Candidate c = candidate(new GenRandom(SAFE_BASE + safeStream(tier, west)), tier, 0);
            return c != null && c.sk().frame.west == west ? c : findSafe(tier, west, 0);
        });
    }

    /** The table's stream for {@code tier} and the mirror. */
    static int safeStream(MountainTier tier, boolean west) {
        int t = switch (tier.id) {
            case "easy" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
        return SAFE_STREAMS[tier.slalom() ? 1 : 0][t][west ? 0 : 1];
    }

    /**
     * The first stream from {@code from} whose candidate passes every gate with the mirror asked: only
     * reached when a change to the generator has moved the table's (the test fails first, naming it).
     */
    static Candidate findSafe(MountainTier tier, boolean west, int from) {
        for (int j = from; j < from + SAFE_SEARCH; j++) {
            Candidate c = candidate(new GenRandom(SAFE_BASE + j), tier, 0);
            if (c != null && c.sk().frame.west == west) {
                return c;
            }
        }
        return null;
    }

    /** How many streams {@link #findSafe} tries. */
    static final int SAFE_SEARCH = 1_000;
}
