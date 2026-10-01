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
 * the ball exactly as {@link LiveRound} does ({@link Rolling}) — tick by tick until it isn't
 * rolling, stopped where it is after {@value #MAX_ROLL_TICKS} ticks, in the cup if it rests there,
 * and back on its spot with a penalty stroke after water, lava or leaving the bounds — or, on
 * Adventure Golf ({@link Rules}), after coming to rest over water; and on Adventure Golf a ball that
 * only wobbles on the spot for a second has come to rest.
 */
public final class GolfShot {

    /** A ball still rolling after this many ticks is stopped where it is. */
    public static final int MAX_ROLL_TICKS = 600;
    /**
     * On Adventure Golf, a ball that wobbles on the spot this many ticks running (a second) has come
     * to rest ({@link Rolling}).
     */
    public static final int WOBBLE_TICKS = 20;
    /** ...its height staying within this band (a sliver: the rise the physics lifts it by). */
    static final double WOBBLE_BAND = BallPhysics.SLIVER;

    private GolfShot() {
    }

    /** A horizontal direction, one block long. */
    public record Direction(double dx, double dz) {
    }

    /**
     * Blocks that say which rules their course plays by. Adventure Golf (a golf layout the planner
     * made at version 3 or later, and a course kept from one: Course Variety) has three of its own:
     * its smooth sandstone is sand; a ball that comes to rest with its centre over water has
     * fallen in — the ball's physics holds a ball up by the edge of its footprint, so without the
     * rule it could stop hanging over a pond's edge, or float on a pond held by a wall's lower
     * block; and a ball that only wobbles on the spot has come to rest ({@link Rolling}). Blocks
     * that don't say (a hand-built course's) play exactly as they always did.
     */
    public interface Rules {

        /** Whether this course plays Adventure Golf's rules. */
        boolean adventure();
    }

    /** Whether {@code blocks} play Adventure Golf's rules ({@link Rules}). */
    public static boolean adventure(BallPhysics.Blocks blocks) {
        return blocks instanceof Rules r && r.adventure();
    }

    /**
     * How a tick of the ball ends by its course's rules: as the physics says, except that on
     * Adventure Golf ({@link Rules}) a ball that has come to rest with its centre over water has
     * fallen in ({@link BallPhysics.Outcome#WATER}: back to its spot, +1). {@link #play} and the
     * live round ({@link LiveRound}) both ask this ({@link Rolling}), so a proof and a player see
     * the same putt.
     */
    public static BallPhysics.Outcome settled(BallPhysics.Blocks blocks, BallPhysics.Ball ball,
                                              BallPhysics.Outcome o) {
        return o == BallPhysics.Outcome.STOPPED && adventure(blocks) && overWater(blocks, ball)
                ? BallPhysics.Outcome.WATER : o;
    }

    /**
     * Whether a ball is over water: straight down from where it stands, under its centre, water
     * comes before anything solid (within {@value BallPhysics#SETTLE} blocks) — held up only by the
     * edge of its footprint, on a pond's edge or a wall's lower block beside one.
     */
    public static boolean overWater(BallPhysics.Blocks blocks, BallPhysics.Ball ball) {
        int x = (int) Math.floor(ball.x());
        int z = (int) Math.floor(ball.z());
        int from = (int) Math.floor(ball.y() - 1e-6);
        for (int y = from; y >= from - BallPhysics.SETTLE; y--) {
            if (blocks.surface(x, y, z).wet()) {
                return true;
            }
            if (blocks.top(x, y, z, ball.x(), ball.z()) != BallPhysics.Blocks.NONE) {
                return false;
            }
        }
        return false;
    }

    /**
     * One putt on its way, rolled by its course's rules: {@link #play} and the live round
     * ({@link LiveRound}) each keep one per putt and call {@link #tick} for every tick, so the two
     * loops are one and a proof and a player see the same putt. A tick is the physics
     * ({@link BallPhysics#tick}), then the roll cap (still rolling after {@value #MAX_ROLL_TICKS}
     * ticks, it stops where it is), then — on Adventure Golf only — the wobble below, then the pond
     * rule ({@link #settled}).
     *
     * <p><b>The wobble (the review of Course Variety; the owner's call).</b> A soft putt can wedge the
     * ball's edge against a half-block step: the physics lifts the edge onto the step (a sliver),
     * the ball drops back, and again, every sub-step, with no sideways speed at all — so it hangs a
     * little above the ground, "rolling", until the roll cap stops it there half a minute later. On
     * Adventure Golf ({@link Rules}) a ball whose sideways speed stays under
     * {@link BallPhysics#STOP} and whose height stays within a sliver ({@value #WOBBLE_BAND} of a
     * block) for {@value #WOBBLE_TICKS} ticks running (a second) has come to rest: it settles onto
     * what is under it ({@link BallPhysics#settle}), in the cup if it rests there, in the water if it
     * sank into it, and stopped otherwise — and the pond rule still applies. A rolling ball never
     * qualifies: on the ground, slower than {@link BallPhysics#STOP}, it has already stopped. On every
     * other course (hand-built, a layout of golf planner version 2) the window is never looked at and
     * the ball wobbles to the roll cap, exactly as it always did.
     */
    public static final class Rolling {

        /** Ticks rolling since the putt. */
        private int ticks;
        /** Ticks running the ball has wobbled on the spot, and the band its height stayed in. */
        private int still;
        private double low;
        private double high;

        /** A new putt: nothing rolled yet. */
        public void reset() {
            ticks = 0;
            still = 0;
        }

        /** One tick of {@code ball} on {@code blocks} in {@code hole}, and how it ended by the course's rules. */
        public BallPhysics.Outcome tick(BallPhysics.Blocks blocks, BallPhysics.Hole hole, BallPhysics.Ball ball) {
            BallPhysics.Outcome o = BallPhysics.tick(ball, blocks, hole);
            if (o == BallPhysics.Outcome.ROLLING && ++ticks > MAX_ROLL_TICKS) {
                ball.place(ball.x(), ball.y(), ball.z()); // half a minute is enough: it stops here
                o = BallPhysics.restsInCup(ball, blocks, hole) ? BallPhysics.Outcome.IN_CUP
                        : BallPhysics.Outcome.STOPPED;
            } else if (o == BallPhysics.Outcome.ROLLING && adventure(blocks) && wobbled(ball)) {
                o = rest(blocks, hole, ball);
            }
            return settled(blocks, ball, o); // Adventure Golf: stopped over water, it has fallen in
        }

        /** Whether the ball has now wobbled on the spot for {@value #WOBBLE_TICKS} ticks running. */
        private boolean wobbled(BallPhysics.Ball ball) {
            double y = ball.y();
            if (ball.speed() >= BallPhysics.STOP) {
                still = 0;
                return false;
            }
            if (still == 0 || Math.max(high, y) - Math.min(low, y) > WOBBLE_BAND) {
                still = 0; // a new window from here
                low = y;
                high = y;
            } else {
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
            return ++still >= WOBBLE_TICKS;
        }

        /** A wobbling ball at rest: down on what is under it, and where that is. */
        private static BallPhysics.Outcome rest(BallPhysics.Blocks blocks, BallPhysics.Hole hole,
                                                BallPhysics.Ball ball) {
            ball.place(ball.x(), ball.y(), ball.z());
            BallPhysics.settle(ball, blocks);
            int x = (int) Math.floor(ball.x());
            int y = (int) Math.floor(ball.y() + BallPhysics.RADIUS);
            int z = (int) Math.floor(ball.z());
            if (blocks.surface(x, y, z).wet()) {
                return BallPhysics.Outcome.WATER; // it settled into a pond: fallen in, as a ball dropping there
            }
            return BallPhysics.restsInCup(ball, blocks, hole) ? BallPhysics.Outcome.IN_CUP
                    : BallPhysics.Outcome.STOPPED;
        }
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
        Rolling rolling = new Rolling();
        int ticks = 0;
        while (true) {
            BallPhysics.Outcome o = rolling.tick(blocks, hole, ball);
            ticks++;
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
