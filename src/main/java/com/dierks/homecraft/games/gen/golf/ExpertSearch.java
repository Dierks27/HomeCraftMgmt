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
 * The expert: the fewest putts that hole out, E, found by breadth-first search on the real ball
 * physics (GEN-SPEC §4.3). Par is E + 1.
 *
 * <p><b>How.</b> From the settled tee every one of 72 directions (every 5 degrees) at every power
 * 1-5 is played with {@link GolfShot#play} — exactly the putt a player makes, on exactly the rules
 * of a round. Each rest spot is a state for the next stroke. States are merged on 0.25-block cells
 * for the search only (the state keeps its exact spot, and the line that got there), and each
 * level keeps at most {@value #BEAM} states, nearest the cup along the lane. The first putt that
 * drops at level d gives E = d; since every shorter level was tried first, no line of the searched
 * putts is shorter.
 *
 * <p><b>The witness.</b> A line that dropped is replayed from the tee, putt by putt, with
 * {@link GolfShot#replay}: only if it holes in exactly E strokes is it kept. That replay — not
 * the search's bookkeeping — is the proof, and the build replays the same line on the real blocks.
 *
 * <p><b>Cost.</b> Counted, never timed ({@link Work}): at most 360 putts a state, so at most
 * 360 + 64 x 360 x 2 for three levels. Directions are tried nearest the lane's way first, so a
 * short line is usually found after a handful of putts.
 */
final class ExpertSearch {

    /** Directions tried from every state: every {@value #STEP_DEGREES} degrees. */
    static final int DIRECTIONS = 72;
    static final int STEP_DEGREES = 5;
    /** States kept per level. */
    static final int BEAM = 64;
    /** The deepest level searched: a hole needing more than this is rejected. */
    static final int MAX_DEPTH = 3;
    /** Rest spots are merged on cells this many to the block. */
    private static final double CELLS_PER_BLOCK = 4;

    private ExpertSearch() {
    }

    /** How a search ended. */
    enum Status {
        /** A line holed out: {@link Result#strokes} and {@link Result#witness}. */
        FOUND,
        /** No line within the depth searched. */
        NONE,
        /** The work ran out first. */
        OVER_BUDGET
    }

    /**
     * What the search found.
     *
     * @param status  how it ended
     * @param strokes E, when found
     * @param witness the line, replayed from the tee: it holes in exactly E strokes
     */
    record Result(Status status, int strokes, List<Putt> witness) {

        boolean found() {
            return status == Status.FOUND;
        }
    }

    /** A rest spot and the line from the tee that reached it. */
    private record State(double x, double y, double z, List<Putt> line, double toCup) {
    }

    /**
     * Search {@code hole} on {@code blocks} up to {@code maxDepth} strokes.
     *
     * @param lane the hole's lane read from the same blocks (for "nearest the cup" and the aim)
     * @param work what the search may spend; also where a cancelled job is noticed
     * @throws GenFailed when the job was cancelled
     */
    static Result search(BallPhysics.Blocks blocks, GolfCourse.Hole hole, LaneMap lane, int maxDepth, Work work)
            throws GenFailed {
        BallPhysics.Hole area = GolfShot.area(blocks, hole);
        BallPhysics.Ball tee = GolfShot.tee(blocks, hole);
        List<State> level = List.of(new State(tee.x(), tee.y(), tee.z(), List.of(),
                lane.pathDistance(tee.x(), tee.z())));
        Set<Long> seen = new HashSet<>();
        seen.add(key(tee.x(), tee.y(), tee.z()));
        for (int depth = 1; depth <= Math.min(maxDepth, MAX_DEPTH); depth++) {
            List<State> next = new ArrayList<>();
            for (State s : level) {
                int aim = lane.target(s.x(), s.z());
                double bearing = LaneMap.bearing(s.x(), s.z(), lane.waypointX(aim), lane.waypointZ(aim));
                for (int dir : order(bearing)) {
                    for (int power = 1; power <= BallPhysics.clubs(); power++) {
                        work.checkCancelled();
                        if (!work.spend()) {
                            return new Result(Status.OVER_BUDGET, 0, List.of());
                        }
                        Putt putt = new Putt(dir * STEP_DEGREES, power);
                        BallPhysics.Ball ball = new BallPhysics.Ball(s.x(), s.y(), s.z());
                        GolfShot.Result r = GolfShot.play(blocks, area, ball, putt);
                        if (r.penalty()) {
                            continue; // back on the same spot: nothing new
                        }
                        List<Putt> line = new ArrayList<>(s.line());
                        line.add(putt);
                        if (r.inCup()) {
                            GolfShot.Replay replay = GolfShot.replay(blocks, hole, line);
                            work.charge(line.size());
                            if (replay.holed() && replay.putts() == depth && replay.strokes() == depth) {
                                return new Result(Status.FOUND, depth, List.copyOf(line));
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
            level = next.size() > BEAM ? next.subList(0, BEAM) : next;
            if (level.isEmpty()) {
                break;
            }
        }
        return new Result(Status.NONE, 0, List.of());
    }

    /**
     * The 72 direction numbers (yaw = n x 5), nearest {@code bearing} first; ties go to the lower
     * number.
     */
    static int[] order(double bearing) {
        Integer[] dirs = new Integer[DIRECTIONS];
        double[] off = new double[DIRECTIONS];
        for (int i = 0; i < DIRECTIONS; i++) {
            dirs[i] = i;
            off[i] = angleBetween(i * (double) STEP_DEGREES, bearing);
        }
        java.util.Arrays.sort(dirs, (a, b) -> off[a] != off[b] ? Double.compare(off[a], off[b])
                : Integer.compare(a, b));
        int[] out = new int[DIRECTIONS];
        for (int i = 0; i < DIRECTIONS; i++) {
            out[i] = dirs[i];
        }
        return out;
    }

    /** The angle between two yaws, 0-180 degrees. */
    static double angleBetween(double a, double b) {
        double d = Math.abs((a - b) % 360.0);
        return d > 180 ? 360 - d : d;
    }

    /** A rest spot's merge cell. */
    private static long key(double x, double y, double z) {
        long qx = (long) Math.floor(x * CELLS_PER_BLOCK) & 0x1FFFFFL;
        long qy = (long) Math.floor(y * CELLS_PER_BLOCK) & 0x3FFFFL;
        long qz = (long) Math.floor(z * CELLS_PER_BLOCK) & 0x1FFFFFL;
        return (qx << 39) | (qy << 21) | qz;
    }
}
