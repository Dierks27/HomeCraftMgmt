package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Honest par (GOLF-V4-SPEC §3.4): a fixed "ordinary player" plays every hole 64 times on the real
 * ball physics, and par is what it usually takes. Pure.
 *
 * <p><b>Why not E + 1.</b> E is the fewest putts of a search on a 5-degree grid; a person aims
 * continuously, so E overestimates and E + 1 is generous: on Adventure Golf's courses a first-time
 * player beat par by about two strokes a course (the owner's −2). And a breadth-first search of
 * long holes costs seconds. This player instead plays like a person who has never golfed:
 *
 * <ol>
 *   <li><b>Aim</b> at the furthest centre-line waypoint it can see ({@link LaneMap#target}; the cup
 *       when it is in sight): bearing <i>b</i>.</li>
 *   <li><b>Club:</b> play each power 1-5 at <i>b</i> with no error, and pick the one whose rest is
 *       nearest the cup along the lane ({@link LaneMap#pathDistance}); a holed putt counts −1, a
 *       penalty +∞, and a tie goes to the lower power. (The choice from one exact spot is the same
 *       in every rollout, so it is worked out once.)</li>
 *   <li><b>Slip:</b> with chance <i>s</i> it takes the club one up or one down (50/50); when its bag
 *       has no such club (below Tap, above Drive) it keeps the one it chose.</li>
 *   <li><b>Aim error:</b> it turns a whole number of degrees <i>e</i> drawn by
 *       {@link GenRandom#weighted} from −⌊3σ⌋..⌊3σ⌋, weights exp(−e²/2σ²) ({@link StrictMath}, the
 *       same on every JVM), and plays {@code Putt((float) (b + e), club)}. A penalty puts the ball
 *       back on its spot, a stroke more.</li>
 * </ol>
 * A rollout ends in the cup or at {@value #CAP} strokes. Two players ({@link Model}): the
 * {@link Model#FIRST_TIMER} (σ {@value #FIRST_TIMER_SIGMA} degrees, the wrong club one time in four)
 * sets par everywhere but Tiny Golf, which the {@link Model#CHILD} (σ 6, 35%) sets.
 *
 * <p><b>Par from the blocks alone.</b> Rollout r draws from {@code new GenRandom(fnv1a64("golf v4
 * par")).fork("roll:" + r)} — never from the course's seed or the hole's number — so the same hole
 * gets the same par wherever it stands, and the validator works it out again from the blocks. μ is
 * the mean of the {@value #ROLLOUTS} rollouts; a hole's par is ⌊μ + ½⌋ (2-6), and then the course is
 * balanced ({@link #balance}) so its total par is within one of the summed means.
 *
 * <p><b>The witness</b> (§3.5) comes from the same play: the steady line (no error, no slip) and the
 * 64 rollouts are the candidates, and the first with the fewest strokes that holes out with no
 * penalty, rests on the lane after every putt, and — on a hole with water in play — keeps every
 * putt's twins three degrees either side dry — judged by the validator's own test of a witness
 * ({@link GolfValidatorV4#lineProblem}, a replay from the tee) — is the hole's proof that it can be
 * played; its putts are E.
 *
 * <p><b>Counted work.</b> Every simulated putt (a club tried, a putt played, a twin) spends one unit
 * of {@link Work}; the club chosen at an exact spot is remembered, so it is simulated once.
 *
 * <p><b>What a club is worth</b> (red-team F00). The same player can play with only some clubs in
 * its bag ({@link #mean(BallPhysics.Blocks, GolfCourse.Hole, LaneMap, Model, int[], Work)}): it chooses
 * among them, and a wrong club is the next one up or down when the bag has it, else the one it chose.
 * With every club that is exactly the player above; with Tap, Putt and Drive alone
 * ({@link #NO_CHIP_NO_SWING}) the extra strokes are what Chip and Swing are worth on the hole.
 */
final class OrdinaryPar {

    /** Rollouts a hole's μ is the mean of. */
    static final int ROLLOUTS = 64;
    /** A rollout stops here, holed or not. */
    static final int CAP = 12;
    /** The first-timer's aim error, degrees (calibrated: GOLF-V4-SPEC §3.4, {@code OrdinaryParTest}). */
    static final double FIRST_TIMER_SIGMA = 4.0;
    /** The first-timer's chance of the wrong club. */
    static final double FIRST_TIMER_SLIP = 0.25;
    /** The child's aim error, degrees. */
    static final double CHILD_SIGMA = 6.0;
    /** The child's chance of the wrong club. */
    static final double CHILD_SLIP = 0.35;
    /** The stream every hole's rollouts are forked from: par depends on the blocks, not the seed. */
    static final long STREAM = GenRandom.fnv1a64("golf v4 par");
    /** Every club, 1-5: the bag par is set with. */
    static final int[] ALL_CLUBS = {1, 2, 3, 4, 5};
    /** Tap, Putt and Drive: the bag the club-value test plays without Chip and Swing (GOLF-V4-SPEC §3.7). */
    static final int[] NO_CHIP_NO_SWING = {1, 2, 5};
    private static final double EPS = 1e-9;

    private OrdinaryPar() {
    }

    /** Who par is for (§3.4, owner decision D2). */
    enum Model {
        /** A first-time adult: about 4 degrees off, the wrong club one time in four. Golf of the Week. */
        FIRST_TIMER(FIRST_TIMER_SIGMA, FIRST_TIMER_SLIP),
        /** A child: about 6 degrees off, the wrong club about one time in three. Tiny Golf. */
        CHILD(CHILD_SIGMA, CHILD_SLIP);

        final double sigma;
        final double slip;
        /** The whole-degree errors it can make, -⌊3σ⌋..⌊3σ⌋. */
        final int[] errors;
        /** Their weights, exp(-e²/2σ²). */
        final double[] weights;

        Model(double sigma, double slip) {
            this.sigma = sigma;
            this.slip = slip;
            int reach = (int) Math.floor(3 * sigma + EPS);
            errors = new int[2 * reach + 1];
            weights = new double[2 * reach + 1];
            for (int i = 0; i < errors.length; i++) {
                int e = i - reach;
                errors[i] = e;
                weights[i] = StrictMath.exp(-(double) (e * e) / (2 * sigma * sigma));
            }
        }

        /** The standard deviation of its whole-degree error table (a test pins it near σ). */
        double tableSigma() {
            double sum = 0;
            double sq = 0;
            for (int i = 0; i < errors.length; i++) {
                sum += weights[i];
                sq += weights[i] * errors[i] * errors[i];
            }
            return Math.sqrt(sq / sum);
        }
    }

    /** The player who sets par for a course of {@code slotId}: the child on Tiny Golf, else the first-timer. */
    static Model model(String slotId) {
        return Slots.TINY_GOLF.id().equals(slotId) ? Model.CHILD : Model.FIRST_TIMER;
    }

    /** {@link #model(String)} for a slot's def. */
    static Model model(Slots.Def slot) {
        return model(slot == null ? null : slot.id());
    }

    /**
     * What the ordinary player showed on one hole.
     *
     * @param mean    μ: the mean of the rollouts' strokes (each capped at {@value #CAP})
     * @param strokes each rollout's strokes, in order
     * @param witness the witness line (§3.5), or empty when no candidate qualifies
     * @param chosen  per club (index 1-5), how often the player chose it (before a slip), over the rollouts
     * @param played  per club (index 1-5), how often it played it (after a slip)
     * @param holed   every candidate that holed out (the steady line first, then the rollouts in order)
     * @param teeClub the club the player chooses from the tee (a layup's is the club it lays up with)
     */
    record Measure(double mean, int[] strokes, List<Putt> witness, long[] chosen, long[] played, List<Line> holed,
                   int teeClub) {

        /** The par the hole measures alone: ⌊μ + ½⌋, not clamped. */
        int rounded() {
            return (int) Math.floor(mean + 0.5);
        }

        /** E: the witness's putts (0 when there is none). */
        int expert() {
            return witness.size();
        }
    }

    /**
     * A candidate line that holed out.
     *
     * @param putts   its putts, from the tee
     * @param strokes its strokes (a penalty is one more)
     * @param clean   no penalty, and every spot it rested at was on the lane
     */
    record Line(List<Putt> putts, int strokes, boolean clean) {
    }

    /** Thrown inside when the work runs out. */
    private static final class Spent extends Exception {
        Spent() {
            super(null, null, false, false);
        }
    }

    /** The club the player chooses from one exact spot (its index in the bag), and the bearing it aims along. */
    private record Choice(double bearing, int club) {
    }

    /** An exact rest spot (the memo's key). */
    private record Spot(double x, double y, double z) {
    }

    /** One rollout played out. */
    private record Played(int strokes, boolean clean, boolean holed, List<Putt> line) {
    }

    /** One hole being played. */
    private static final class Play {
        final BallPhysics.Blocks blocks;
        final GolfCourse.Hole hole;
        final BallPhysics.Hole area;
        final LaneMap lane;
        final Work work;
        /** The clubs in the bag, ascending (a slip takes the next one up or down in it). */
        final int[] bag;
        /** Whether the player ever takes the wrong club. */
        boolean slips = true;
        final Map<Spot, Choice> choices = new HashMap<>();
        final long[] chosen = new long[6];
        final long[] played = new long[6];

        Play(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, Work work, int[] bag) {
            this.blocks = blocks;
            this.hole = hole;
            this.area = GolfShot.area(blocks, hole);
            this.lane = lane;
            this.work = work;
            this.bag = bag;
        }

        GolfShot.Result putt(double x, double y, double z, Putt p) throws GenFailed, Spent {
            work.checkCancelled();
            if (!work.spend()) {
                throw new Spent();
            }
            return GolfShot.play(blocks, area, new BallPhysics.Ball(x, y, z), p);
        }

        boolean inBag(int club) {
            return indexOf(club) >= 0;
        }

        int indexOf(int club) {
            for (int k = 0; k < bag.length; k++) {
                if (bag[k] == club) {
                    return k;
                }
            }
            return -1;
        }

        /**
         * The player's choice from (x, y, z): the bearing to the furthest waypoint in sight, and the best
         * club of the bag (its index in {@link #bag}).
         */
        Choice choose(double x, double y, double z) throws GenFailed, Spent {
            Spot key = new Spot(x, y, z);
            Choice known = choices.get(key);
            if (known != null) {
                return known;
            }
            int aim = lane.target(x, z);
            double bearing = LaneMap.bearing(x, z, lane.waypointX(aim), lane.waypointZ(aim));
            int best = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int k = 0; k < bag.length; k++) {
                GolfShot.Result r = putt(x, y, z, new Putt((float) bearing, bag[k]));
                double score = r.inCup() ? -1 : r.penalty() ? Double.POSITIVE_INFINITY
                        : lane.pathDistance(r.x(), r.z());
                if (score < bestScore - EPS) {
                    bestScore = score;
                    best = k;
                }
            }
            Choice c = new Choice(bearing, best);
            choices.put(key, c);
            return c;
        }

        /**
         * One rollout from the tee: with {@code r} the noisy player (its slips and aim errors), without
         * it the steady line. Clean: holed with no penalty and every rest spot on the lane.
         */
        Played roll(Model model, GenRandom r) throws GenFailed, Spent {
            BallPhysics.Ball ball = GolfShot.tee(blocks, hole);
            int strokes = 0;
            boolean clean = true;
            List<Putt> line = new ArrayList<>();
            while (strokes < CAP) {
                double x = ball.x();
                double y = ball.y();
                double z = ball.z();
                Choice c = choose(x, y, z);
                int k = c.club();
                double yaw = c.bearing();
                if (r != null) {
                    int club = bag[k];
                    chosen[club]++;
                    if (slips && r.chance(model.slip)) {
                        int wrong = club + (r.nextBoolean() ? 1 : -1);
                        if (inBag(wrong)) { // the club next to it, when the bag has it (Tap has none below)
                            club = wrong;
                        }
                    }
                    played[club]++;
                    yaw += model.errors[r.weighted(model.weights)];
                    k = indexOf(club);
                }
                Putt p = new Putt((float) yaw, bag[k]);
                GolfShot.Result res = putt(x, y, z, p);
                ball.place(res.x(), res.y(), res.z());
                strokes += res.strokes();
                line.add(p);
                if (res.inCup()) {
                    return new Played(Math.min(strokes, CAP), clean, true, line);
                }
                if (res.penalty() || !GolfValidatorV3.restsOnLane(lane, res.x(), res.y(), res.z())) {
                    clean = false;
                }
            }
            return new Played(CAP, false, false, line);
        }
    }

    /** Rollout r's stream: from the fixed par stream, never from the course's seed. */
    static GenRandom stream(int r) {
        return new GenRandom(STREAM).fork("roll:" + r);
    }

    /**
     * μ alone: the {@value #ROLLOUTS} noisy rollouts' mean on {@code hole} (what the validator and a
     * re-derive work out again); {@code null} when the work ran out.
     *
     * @throws GenFailed when the job was cancelled
     */
    static Double mean(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, Model model, Work work)
            throws GenFailed {
        return mean(blocks, hole, lane, model, ALL_CLUBS, work);
    }

    /**
     * μ with only the clubs of {@code bag} (ascending powers; a slip takes the next one up or down in
     * it): what a club is worth, the extra strokes the player takes without it (GOLF-V4-SPEC §3.7's
     * club-value test). {@code null} when the work ran out.
     *
     * @throws GenFailed when the job was cancelled
     */
    static Double mean(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, Model model, int[] bag,
                       Work work) throws GenFailed {
        return mean(blocks, hole, lane, model, bag, true, work);
    }

    /**
     * {@link #mean(BallPhysics.Blocks, GolfCourse.Hole, LaneMap, Model, int[], Work)}, and without
     * {@code slips} a player who never takes the wrong club (its aim still errs): the red-team's own
     * measure of what the clubs are worth, reported beside the model's.
     */
    static Double mean(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, Model model, int[] bag,
                       boolean slips, Work work) throws GenFailed {
        Play play = new Play(blocks, hole, lane, work, bag);
        play.slips = slips;
        try {
            long total = 0;
            for (int r = 0; r < ROLLOUTS; r++) {
                total += play.roll(model, stream(r)).strokes();
            }
            return (double) total / ROLLOUTS;
        } catch (Spent e) {
            return null;
        }
    }

    /**
     * Everything the planner needs from the ordinary player on one hole: μ, the club counts, and the
     * witness (§3.5) among the steady line and the rollouts; {@code null} when the work ran out.
     *
     * @throws GenFailed when the job was cancelled
     */
    static Measure measure(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, Model model, Work work)
            throws GenFailed {
        Play play = new Play(blocks, hole, lane, work, ALL_CLUBS);
        try {
            List<Played> candidates = new ArrayList<>(ROLLOUTS + 1);
            candidates.add(play.roll(model, null));
            int[] strokes = new int[ROLLOUTS];
            long total = 0;
            for (int r = 0; r < ROLLOUTS; r++) {
                Played p = play.roll(model, stream(r));
                candidates.add(p);
                strokes[r] = p.strokes();
                total += p.strokes();
            }
            List<Putt> witness = List.of();
            List<Line> holed = new ArrayList<>();
            for (Played p : candidates) {
                if (p.holed()) {
                    holed.add(new Line(List.copyOf(p.line()), p.strokes(), p.clean()));
                }
            }
            for (Played p : candidates) {
                if (!p.holed() || !p.clean() || p.strokes() != p.line().size()
                        || !witness.isEmpty() && p.strokes() >= witness.size()) {
                    continue;
                }
                // the validator's own test of a witness (its replay from the tee, its rests, dry twins)
                work.checkCancelled();
                for (int k = 0; k < 4 * p.line().size(); k++) { // its replay, its rests and its twins
                    if (!work.spend()) {
                        throw new Spent();
                    }
                }
                if (GolfValidatorV4.lineProblem(blocks, hole, lane, p.line(), 1) == null) {
                    witness = List.copyOf(p.line());
                }
            }
            BallPhysics.Ball tee = GolfShot.tee(blocks, hole);
            int teeClub = play.bag[play.choose(tee.x(), tee.y(), tee.z()).club()];
            return new Measure((double) total / ROLLOUTS, strokes, witness, play.chosen.clone(), play.played.clone(),
                    List.copyOf(holed), teeClub);
        } catch (Spent e) {
            return null;
        }
    }

    /** A hole's own par from its μ: ⌊μ + ½⌋, clamped to 2-6. */
    static int par(double mean) {
        return par(mean, GolfCourse.MAX_PAR);
    }

    /** A hole's own par from its μ: ⌊μ + ½⌋, clamped to 2-{@code most}. */
    static int par(double mean, int most) {
        return Math.max(GolfCourse.MIN_PAR, Math.min(most, (int) Math.floor(mean + 0.5)));
    }

    /** The highest par a hole of slot {@code slotId} takes: 3 on Tiny Golf (§3.3: par 2-3), else 6. */
    static int most(String slotId) {
        return Slots.TINY_GOLF.id().equals(slotId) ? 3 : GolfCourse.MAX_PAR;
    }

    /**
     * The course's pars from its holes' μ (§3.4): each hole's own {@link #par}, then balanced so the
     * total is within one of the summed means. While the total is more than one under, the hole whose
     * μ is nearest to rounding up (the most above its par, the lower number on a tie) goes up a
     * stroke; while it is more than one over, the hole nearest to rounding down (the most below its
     * par) goes down one. Each hole moves at most once, never outside 2-{@code most} (Tiny Golf: 3).
     * Pure: the validator works it out again from the blocks. (When no hole can move the total may
     * stay more than one off; the planner then draws a hole again: {@link #off}.)
     */
    static int[] balance(double[] means, int most) {
        int n = means.length;
        int[] par = new int[n];
        double diff = 0;
        for (int i = 0; i < n; i++) {
            par[i] = par(means[i], most);
            diff += par[i] - means[i];
        }
        while (diff < -1 - EPS) {
            int pick = -1;
            for (int i = 0; i < n; i++) {
                if (par[i] < means[i] - EPS && par[i] < most
                        && (pick < 0 || means[i] - par[i] > means[pick] - par[pick] + EPS)) {
                    pick = i;
                }
            }
            if (pick < 0) {
                break;
            }
            par[pick]++;
            diff += 1;
        }
        while (diff > 1 + EPS) {
            int pick = -1;
            for (int i = 0; i < n; i++) {
                if (par[i] > means[i] + EPS && par[i] > GolfCourse.MIN_PAR
                        && (pick < 0 || par[i] - means[i] > par[pick] - means[pick] + EPS)) {
                    pick = i;
                }
            }
            if (pick < 0) {
                break;
            }
            par[pick]--;
            diff -= 1;
        }
        return par;
    }

    /** {@link #balance(double[], int)} for a course whose holes may take any par 2-6. */
    static int[] balance(double[] means) {
        return balance(means, GolfCourse.MAX_PAR);
    }

    /** The course's total par less its summed means: within one by construction ({@link #balance}). */
    static double off(int[] par, double[] means) {
        double diff = 0;
        for (int i = 0; i < par.length; i++) {
            diff += par[i] - means[i];
        }
        return diff;
    }
}
