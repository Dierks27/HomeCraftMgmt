package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sloppy player, K (GEN-SPEC §4.3): a fixed, simple way of playing with a shaky aim, played
 * out on the real ball physics for every aim error it allows. A hole is kept only if this player
 * always finishes within par + 1 — so a small child who aims roughly at the flag never gets
 * picked up (that happens at par + 3).
 *
 * <p><b>The policy</b> at every spot the ball rests:
 * <ol>
 *   <li>Aim at the furthest waypoint of the lane's centre line that can be seen in a straight
 *       line at the ball's radius ({@link LaneMap#target}); the cup is the last waypoint.</li>
 *   <li>Try that bearing turned by {@code 0, -4, +4, -8, +8} degrees, at every power 1-5. Play each
 *       with an aim error of {@code -6, 0, +6} degrees and score it by its worst outcome: 0 in the
 *       cup; else 1 + the path distance to the cup / 9 (rounded up); a penalty adds 2.</li>
 *   <li>Putt the best-scoring one. A tie goes to the one whose worst rest is nearest the cup, then
 *       to the lower power, then to the smaller turn. (The nearest-rest rule comes first because
 *       the score counts whole putts: without it, every rest within 9 blocks ties and the lowest
 *       power wins, so the kid would tap the ball 2 blocks at a time.)</li>
 * </ol>
 *
 * <p><b>The tree.</b> The chosen putt is then followed into all three of its real outcomes — each
 * from its exact rest spot, nothing merged — and K is the worst total over the whole tree. The
 * guarantee is exact for every combination of the sampled aim errors (and for the witness line at
 * 0 degrees), not a percentile. A branch that already needs more than the limit stops at once
 * (the early cut-off); rest spots met twice are worked out once.
 */
final class KidPolicy {

    /** How far the kid turns off the bearing, in the order ties go. */
    static final int[] TURNS = {0, -4, 4, -8, 8};
    /** The aim errors every putt is played with. */
    static final int[] ERRORS = {-6, 0, 6};
    /** Path distance a putt is worth, for the score. */
    static final double PUTT_REACH = 9;

    /** How an evaluation ended. */
    enum Status {
        /** The worst case is {@link Result#worst}, within the limit. */
        WITHIN,
        /** Some branch needs more than the limit. */
        OVER_LIMIT,
        /** The work ran out first. */
        OVER_BUDGET
    }

    /**
     * What the kid tree showed.
     *
     * @param status how it ended
     * @param worst  K when {@link Status#WITHIN}; otherwise more than the limit (or 0 when the
     *               work ran out)
     */
    record Result(Status status, int worst) {

        boolean within() {
            return status == Status.WITHIN;
        }
    }

    private static final class Spent extends Exception {
        Spent() {
            super(null, null, false, false);
        }
    }

    /** One putt's three outcomes (by aim error). */
    private record Outcomes(GolfShot.Result[] results) {
    }

    private final BallPhysics.Blocks blocks;
    private final BallPhysics.Hole area;
    private final LaneMap lane;
    private final Work work;
    /** Per exact spot: the remaining strokes worked out, or a lower bound when cut off. */
    private final Map<List<Double>, int[]> memo = new HashMap<>();

    private KidPolicy(BallPhysics.Blocks blocks, BallPhysics.Hole area, LaneMap lane, Work work) {
        this.blocks = blocks;
        this.area = area;
        this.lane = lane;
        this.work = work;
    }

    /**
     * The kid's worst case on {@code hole}, up to {@code limit} strokes (par + 1).
     *
     * @throws GenFailed when the job was cancelled
     */
    static Result evaluate(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, int limit, Work work)
            throws GenFailed {
        BallPhysics.Ball tee = GolfShot.tee(blocks, hole);
        KidPolicy k = new KidPolicy(blocks, GolfShot.area(blocks, hole), lane, work);
        try {
            int worst = k.remaining(tee.x(), tee.y(), tee.z(), limit);
            return worst <= limit ? new Result(Status.WITHIN, worst) : new Result(Status.OVER_LIMIT, worst);
        } catch (Spent e) {
            return new Result(Status.OVER_BUDGET, 0);
        }
    }

    /**
     * The most strokes the kid needs from a ball resting at (x, y, z), or more than
     * {@code allowance} as soon as that is certain.
     */
    private int remaining(double x, double y, double z, int allowance) throws GenFailed, Spent {
        if (allowance <= 0) {
            return 1; // not in yet, and no strokes left
        }
        List<Double> key = List.of(x, y, z);
        int[] known = memo.get(key);
        if (known != null && (known[1] == 1 || known[0] > allowance)) {
            return known[0];
        }
        Outcomes chosen = choose(x, y, z);
        int worst = 0;
        boolean cut = false;
        for (GolfShot.Result r : chosen.results()) {
            int strokes = r.strokes();
            int total = strokes;
            if (!r.inCup()) {
                total += remaining(r.x(), r.y(), r.z(), allowance - strokes);
            }
            worst = Math.max(worst, total);
            if (worst > allowance) {
                cut = true;
                break;
            }
        }
        memo.put(key, new int[]{worst, cut ? 0 : 1});
        return worst;
    }

    /** The putt the kid makes from (x, y, z), with its three outcomes. */
    private Outcomes choose(double x, double y, double z) throws GenFailed, Spent {
        int aim = lane.target(x, z);
        double bearing = LaneMap.bearing(x, z, lane.waypointX(aim), lane.waypointZ(aim));
        Map<Long, GolfShot.Result> played = new HashMap<>();
        Outcomes best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestFar = Double.POSITIVE_INFINITY;
        for (int power = 1; power <= BallPhysics.clubs(); power++) {
            for (int turn : TURNS) {
                GolfShot.Result[] results = new GolfShot.Result[ERRORS.length];
                double score = 0;
                double far = 0;
                for (int e = 0; e < ERRORS.length; e++) {
                    int offset = turn + ERRORS[e];
                    long id = (long) offset * 16 + power;
                    GolfShot.Result r = played.get(id);
                    if (r == null) {
                        r = play(x, y, z, bearing + offset, power);
                        played.put(id, r);
                    }
                    results[e] = r;
                    score = Math.max(score, score(r));
                    far = Math.max(far, r.inCup() ? 0 : lane.pathDistance(r.x(), r.z()));
                }
                if (best == null || score < bestScore || score == bestScore && far < bestFar) {
                    bestScore = score;
                    bestFar = far;
                    best = new Outcomes(results);
                }
            }
        }
        return best;
    }

    private GolfShot.Result play(double x, double y, double z, double yaw, int power) throws GenFailed, Spent {
        work.checkCancelled();
        if (!work.spend()) {
            throw new Spent();
        }
        return GolfShot.play(blocks, area, new BallPhysics.Ball(x, y, z), new Putt((float) yaw, power));
    }

    /** 0 in the cup; else 1 + path distance / 9 rounded up, and 2 more for a penalty (from the spot). */
    static double score(GolfShot.Result r, double pathDistance) {
        if (r.inCup()) {
            return 0;
        }
        double rest = 1 + Math.ceil(pathDistance / PUTT_REACH);
        return r.penalty() ? rest + 2 : rest;
    }

    private double score(GolfShot.Result r) {
        return score(r, lane.pathDistance(r.x(), r.z()));
    }
}
