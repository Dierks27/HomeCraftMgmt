package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The expert on a hole with water in play (Course Variety §3.9): par is the SAFE line.
 *
 * <p><b>Why.</b> {@link ExpertSearch} already never plays a putt that ends in a penalty, so its
 * line never touches water. But on a pond hole the fewest putts are often a brave line that skims
 * past the water's edge: a real putt a few degrees off it splashes, and a par set by it would ask
 * a child to thread a needle. So here a putt counts only if its two twins, aimed
 * {@value #TWIN_DEGREES} degrees either side from the same spot, also end without a penalty. Par
 * (E + 1) is then set by a line with room to miss; the brave line is still there, for a birdie.
 *
 * <p><b>How.</b> On a hole with no water in play ({@link LaneMap#hazards} is 0) this is exactly
 * {@link ExpertSearch#search}, untouched. With water, it is the same breadth-first search — every
 * {@value ExpertSearch#STEP_DEGREES} degrees at every power from each rest spot, rest spots merged
 * on quarter-block cells, the {@value ExpertSearch#BEAM} nearest the cup kept per level, the
 * witness replayed from the tee with {@link GolfShot#replay} — admitting a putt only when it and
 * both its twins stay dry and in bounds. Each twin is a putt of work, so a pond hole costs up to 3x
 * the putts of a dry one, and a hole whose every line is brave has no safe par: the planner draws it
 * again.
 *
 * <p>The validator checks the same rule on the stored line ({@link #unsafePutt}): the proof never
 * trusts the search's bookkeeping.
 */
final class SafeExpert {

    /** How far either side of a putt its twins are aimed, degrees. */
    static final float TWIN_DEGREES = 3f;
    /** Rest spots are merged on cells this many to the block (as {@link ExpertSearch} does). */
    private static final double CELLS_PER_BLOCK = 4;

    private SafeExpert() {
    }

    /**
     * The expert's line on {@code hole}: {@link ExpertSearch#search} on a dry hole; on one with
     * water in play (read from {@code lane}, a v3 {@link LaneMap}), the fewest putts whose every putt
     * has dry twins.
     *
     * @throws GenFailed when the job was cancelled
     */
    static ExpertSearch.Result search(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, int maxDepth,
                                      Work work) throws GenFailed {
        if (lane.hazards() == 0) {
            return ExpertSearch.search(blocks, hole, lane, maxDepth, work);
        }
        BallPhysics.Hole area = GolfShot.area(blocks, hole);
        BallPhysics.Ball tee = GolfShot.tee(blocks, hole);
        List<State> level = List.of(new State(tee.x(), tee.y(), tee.z(), List.of(),
                lane.pathDistance(tee.x(), tee.z())));
        Set<Long> seen = new HashSet<>();
        seen.add(key(tee.x(), tee.y(), tee.z()));
        for (int depth = 1; depth <= Math.min(maxDepth, ExpertSearch.MAX_DEPTH); depth++) {
            List<State> next = new ArrayList<>();
            for (State s : level) {
                int aim = lane.target(s.x(), s.z());
                double bearing = LaneMap.bearing(s.x(), s.z(), lane.waypointX(aim), lane.waypointZ(aim));
                for (int dir : ExpertSearch.order(bearing)) {
                    for (int power = 1; power <= BallPhysics.clubs(); power++) {
                        work.checkCancelled();
                        if (!work.spend()) {
                            return over();
                        }
                        Putt putt = new Putt(dir * ExpertSearch.STEP_DEGREES, power);
                        BallPhysics.Ball ball = new BallPhysics.Ball(s.x(), s.y(), s.z());
                        GolfShot.Result r = GolfShot.play(blocks, area, ball, putt);
                        if (r.penalty()) {
                            continue; // back on the same spot: nothing new
                        }
                        Boolean dry = twinsDry(blocks, area, s.x(), s.y(), s.z(), putt, work);
                        if (dry == null) {
                            return over();
                        }
                        if (!dry) {
                            continue; // a brave putt: a few degrees off, it splashes
                        }
                        List<Putt> line = new ArrayList<>(s.line());
                        line.add(putt);
                        if (r.inCup()) {
                            GolfShot.Replay replay = GolfShot.replay(blocks, hole, line);
                            work.charge(line.size());
                            if (replay.holed() && replay.putts() == depth && replay.strokes() == depth) {
                                return new ExpertSearch.Result(ExpertSearch.Status.FOUND, depth, List.copyOf(line));
                            }
                            continue; // the exact replay didn't hole: not a witness
                        }
                        if (depth < maxDepth && seen.add(key(r.x(), r.y(), r.z()))) {
                            next.add(new State(r.x(), r.y(), r.z(), List.copyOf(line),
                                    lane.pathDistance(r.x(), r.z())));
                        }
                    }
                }
            }
            next.sort(Comparator.comparingDouble(State::toCup)); // stable: ties keep the order found
            level = next.size() > ExpertSearch.BEAM ? next.subList(0, ExpertSearch.BEAM) : next;
            if (level.isEmpty()) {
                break;
            }
        }
        return new ExpertSearch.Result(ExpertSearch.Status.NONE, 0, List.of());
    }

    /**
     * The first putt (numbered from 1) of {@code witness}, replayed from {@code hole}'s tee, whose
     * twin at {@value #TWIN_DEGREES} degrees either side ends in a penalty; 0 when every putt's
     * twins stay dry and in bounds (or the line never gets that far). What the validator asks of a
     * hole with water in play.
     */
    static int unsafePutt(BallPhysics.Blocks blocks, GolfCourse.Hole hole, List<Putt> witness) {
        BallPhysics.Hole area = GolfShot.area(blocks, hole);
        BallPhysics.Ball ball = GolfShot.tee(blocks, hole);
        for (int i = 0; i < witness.size(); i++) {
            Putt putt = witness.get(i);
            if (Boolean.FALSE.equals(twinsDry(blocks, area, ball.x(), ball.y(), ball.z(), putt, Work.unlimited()))) {
                return i + 1;
            }
            GolfShot.Result r = GolfShot.play(blocks, area, ball, putt);
            if (r.inCup()) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Whether both twins of {@code putt} from (x, y, z) end without a penalty; {@code null} when the
     * work ran out first (each twin is a putt of work).
     */
    static Boolean twinsDry(BallPhysics.Blocks blocks, BallPhysics.Hole area, double x, double y, double z, Putt putt,
                            Work work) {
        for (float turn : new float[]{-TWIN_DEGREES, TWIN_DEGREES}) {
            if (!work.spend()) {
                return null;
            }
            Putt twin = new Putt(putt.yaw() + turn, putt.power());
            if (GolfShot.play(blocks, area, new BallPhysics.Ball(x, y, z), twin).penalty()) {
                return false;
            }
        }
        return true;
    }

    private static ExpertSearch.Result over() {
        return new ExpertSearch.Result(ExpertSearch.Status.OVER_BUDGET, 0, List.of());
    }

    /** A rest spot and the line from the tee that reached it. */
    private record State(double x, double y, double z, List<Putt> line, double toCup) {
    }

    /** A rest spot's merge cell (as {@link ExpertSearch} merges them). */
    private static long key(double x, double y, double z) {
        long qx = (long) Math.floor(x * CELLS_PER_BLOCK) & 0x1FFFFFL;
        long qy = (long) Math.floor(y * CELLS_PER_BLOCK) & 0x3FFFFL;
        long qz = (long) Math.floor(z * CELLS_PER_BLOCK) & 0x1FFFFFL;
        return (qx << 39) | (qy << 21) | qz;
    }
}
