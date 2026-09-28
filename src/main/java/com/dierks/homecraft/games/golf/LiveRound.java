package com.dierks.homecraft.games.golf;

import java.util.UUID;

/**
 * One player's round in progress: the course as it was when they started (a snapshot, so an admin
 * edit can't change the holes under them), the score, the ball and what shows it.
 *
 * <p>The round's own rules live here, with no server in them, so they are tested against a fake
 * block grid: a putt goes the way the player looks and counts a stroke; a tick of the ball either
 * leaves it rolling, stops it, drops it in the cup (the hole is done), or — in water, lava or out
 * of bounds — puts it back on its last spot with a penalty stroke; and a ball at rest once the
 * strokes reach the limit is picked up. {@link GolfRounds} plays it on the server: the entities,
 * the chat, the screens.
 */
final class LiveRound {

    /** Where the round is. */
    enum State {
        /** A hole is being played. */
        PLAYING,
        /** A hole just ended; the next one starts in a moment. */
        BETWEEN,
        /** Every hole is done. */
        DONE
    }

    /** What a tick of the ball, or a reset, meant for the round. */
    enum Result {
        /** Still on its way. */
        ROLLING,
        /** At rest; putt again. */
        STILL,
        /** In the cup: the hole is done ({@link #last}). */
        IN_CUP,
        /** Water, lava, out of bounds or "Reset ball": back on its last spot, +1 stroke. */
        BACK,
        /** At rest (or put back) with the strokes at the limit: the hole is done ({@link #last}). */
        PICKED_UP
    }

    /** A ball still rolling after this many ticks is stopped where it is. */
    static final int MAX_ROLL_TICKS = 600;

    final UUID player;
    final GolfCourse course;
    final GolfRun run;
    final BallPhysics.Ball ball;
    /** What shows the ball ({@code null} in tests). */
    final BallView view;
    State state = State.PLAYING;
    /** The current hole as the ball sees it. */
    BallPhysics.Hole area;
    /** Where the ball was last putted from: where water, out of bounds and "Reset ball" put it back. */
    double lastX;
    double lastY;
    double lastZ;
    /** Ticks the ball has been moving since the putt. */
    int rolling;
    /** Which way the ball last went (for the head to face). */
    float yaw;
    /** Changes whenever the next hole is scheduled, so a stale timer can't start a hole twice. */
    long between;
    /** The hole that just ended. */
    GolfRun.HoleScore last;
    /** What the last tick of the ball did (water or out of bounds, for the words). */
    BallPhysics.Outcome outcome;

    LiveRound(UUID player, GolfCourse course, GolfRun run, BallView view) {
        this.player = player;
        this.course = course;
        this.run = run;
        this.view = view;
        this.ball = new BallPhysics.Ball(0, 0, 0);
    }

    /** The hole being played (or the last one, once done). */
    GolfCourse.Hole hole() {
        return course.hole(Math.min(run.hole(), run.holes() - 1) + 1);
    }

    /** Put the ball on the current hole's tee: the start of the hole, and its last spot. */
    void tee() {
        GolfCourse.Hole h = hole();
        area = h.physics();
        ball.place(h.tee().x(), h.tee().y(), h.tee().z());
        yaw = h.tee().yaw();
        rolling = 0;
        markSpot();
    }

    /** Remember where the ball is as the spot to come back to. */
    void markSpot() {
        lastX = ball.x();
        lastY = ball.y();
        lastZ = ball.z();
    }

    /** Whether the ball is at its last spot and still (nothing to reset). */
    boolean atSpot() {
        return !ball.moving() && ball.x() == lastX && ball.y() == lastY && ball.z() == lastZ;
    }

    /**
     * Putt: from where the ball is now (its new last spot), the horizontal way a player facing
     * {@code facing} (Minecraft yaw: 0 is +z, 90 is -x) looks, at the club's speed. +1 stroke.
     */
    void putt(float facing, int power) {
        double rad = Math.toRadians(facing);
        markSpot();
        run.stroke();
        rolling = 0;
        yaw = facing;
        ball.putt(-Math.sin(rad), Math.cos(rad), BallPhysics.speed(power));
    }

    /** One tick of the ball over {@code blocks}, and what it meant for the round. */
    Result roll(BallPhysics.Blocks blocks) {
        BallPhysics.Outcome o = BallPhysics.tick(ball, blocks, area);
        if (o == BallPhysics.Outcome.ROLLING && ++rolling > MAX_ROLL_TICKS) {
            ball.place(ball.x(), ball.y(), ball.z()); // half a minute is enough: it stops here
            o = BallPhysics.Outcome.STOPPED;
        }
        outcome = o;
        if (ball.speed() > BallPhysics.STOP) {
            yaw = (float) Math.toDegrees(Math.atan2(-ball.vx(), ball.vz()));
        }
        return switch (o) {
            case ROLLING -> Result.ROLLING;
            case IN_CUP -> {
                last = run.inCup();
                yield Result.IN_CUP;
            }
            case WATER, OUT -> back();
            case STOPPED -> {
                if (run.mustPickUp()) {
                    last = run.pickUp();
                    yield Result.PICKED_UP;
                }
                yield Result.STILL;
            }
        };
    }

    /** Back to the last spot, still, +1 stroke — picked up if that reaches the limit. */
    Result back() {
        run.penalty();
        ball.place(lastX, lastY, lastZ);
        rolling = 0;
        if (run.mustPickUp()) {
            last = run.pickUp();
            return Result.PICKED_UP;
        }
        return Result.BACK;
    }

    /** The scorecard as it stands. */
    GolfCard card() {
        return GolfCard.of(course, run, state != State.PLAYING);
    }
}
