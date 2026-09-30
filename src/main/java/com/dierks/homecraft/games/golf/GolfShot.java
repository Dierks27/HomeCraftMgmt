package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.Putt;

import java.util.List;

/**
 * One putt played out, by the round's own rules, with no server (GEN-SPEC §4.0, §4.3).
 *
 * <p>Daily Golf proves every hole before it is built: the planner searches putts on a model of
 * the blocks, the build replays the chosen line on the real blocks, and a player then plays the
 * hole. All three must mean the same putt, so they share this: {@link #direction} turns a yaw
 * into a direction with {@link StrictMath} (the same bits on every JVM), and {@link #play} rolls
 * the ball exactly as {@link LiveRound} does — tick by tick until it isn't rolling, stopped where
 * it is after {@value #MAX_ROLL_TICKS} ticks, in the cup if it rests there, and back on its spot
 * with a penalty stroke after water, lava or leaving the bounds.
 */
public final class GolfShot {

    /** A ball still rolling after this many ticks is stopped where it is. */
    public static final int MAX_ROLL_TICKS = 600;

    private GolfShot() {
    }

    /** A horizontal direction, one block long. */
    public record Direction(double dx, double dz) {
    }

    /**
     * How one putt ended.
     *
     * @param outcome {@code IN_CUP}, {@code STOPPED}, or {@code WATER}/{@code OUT} (the ball is
     *                back on the spot it was putted from)
     * @param x       where the ball is now
     * @param y       ...
     * @param z       ...
     * @param ticks   how many ticks it took
     */
    public record Result(BallPhysics.Outcome outcome, double x, double y, double z, int ticks) {

        public boolean inCup() {
            return outcome == BallPhysics.Outcome.IN_CUP;
        }

        /** Water, lava or out of bounds: back to the spot, one stroke more. */
        public boolean penalty() {
            return outcome == BallPhysics.Outcome.WATER || outcome == BallPhysics.Outcome.OUT;
        }

        /** The strokes it cost: the putt, and the penalty if there was one. */
        public int strokes() {
            return penalty() ? 2 : 1;
        }
    }

    /**
     * A replayed line.
     *
     * @param holed   whether the ball dropped
     * @param putts   the putts taken until it did (all of them when it didn't)
     * @param strokes those putts plus any penalty strokes
     */
    public record Replay(boolean holed, int putts, int strokes) {
    }

    /** The way a player facing {@code yaw} (Minecraft: 0 is +z, 90 is -x) sends the ball. */
    public static Direction direction(float yaw) {
        double rad = StrictMath.toRadians(yaw);
        return new Direction(-StrictMath.sin(rad), StrictMath.cos(rad));
    }

    /** The hole as the ball sees it, its cup as high as the cup block in {@code blocks} really is. */
    public static BallPhysics.Hole area(BallPhysics.Blocks blocks, GolfCourse.Hole hole) {
        return hole.physics(blocks);
    }

    /** A ball on {@code hole}'s tee, settled onto what is under it, as a round tees up. */
    public static BallPhysics.Ball tee(BallPhysics.Blocks blocks, GolfCourse.Hole hole) {
        BallPhysics.Ball ball = new BallPhysics.Ball(hole.tee().x(), hole.tee().y(), hole.tee().z());
        BallPhysics.settle(ball, blocks);
        return ball;
    }

    /**
     * Putt {@code ball} from where it rests and roll it out on {@code blocks}, as {@link LiveRound}
     * does. After water, lava or out of bounds it is put back where it was putted from.
     */
    public static Result play(BallPhysics.Blocks blocks, BallPhysics.Hole hole, BallPhysics.Ball ball, Putt putt) {
        double sx = ball.x();
        double sy = ball.y();
        double sz = ball.z();
        Direction d = direction(putt.yaw());
        ball.putt(d.dx(), d.dz(), BallPhysics.speed(putt.power()));
        int rolling = 0;
        int ticks = 0;
        while (true) {
            BallPhysics.Outcome o = BallPhysics.tick(ball, blocks, hole);
            ticks++;
            if (o == BallPhysics.Outcome.ROLLING && ++rolling > MAX_ROLL_TICKS) {
                ball.place(ball.x(), ball.y(), ball.z());
                o = BallPhysics.restsInCup(ball, blocks, hole) ? BallPhysics.Outcome.IN_CUP
                        : BallPhysics.Outcome.STOPPED;
            }
            switch (o) {
                case ROLLING -> {
                    continue;
                }
                case WATER, OUT -> ball.place(sx, sy, sz);
                default -> {
                    // IN_CUP or STOPPED: the ball stays where it is
                }
            }
            return new Result(o, ball.x(), ball.y(), ball.z(), ticks);
        }
    }

    /** Play {@code putts} from {@code hole}'s tee on {@code blocks}, stopping when the ball drops. */
    public static Replay replay(BallPhysics.Blocks blocks, GolfCourse.Hole hole, List<Putt> putts) {
        BallPhysics.Hole area = area(blocks, hole);
        BallPhysics.Ball ball = tee(blocks, hole);
        int strokes = 0;
        for (int i = 0; i < putts.size(); i++) {
            Result r = play(blocks, area, ball, putts.get(i));
            strokes += r.strokes();
            if (r.inCup()) {
                return new Replay(true, i + 1, strokes);
            }
        }
        return new Replay(false, putts.size(), strokes);
    }
}
