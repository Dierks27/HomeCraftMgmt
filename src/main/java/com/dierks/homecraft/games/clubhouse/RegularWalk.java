package com.dierks.homecraft.games.clubhouse;

import java.util.List;

/**
 * One regular's walk (pure): a person's pace along a path's corners, a tick at a time, facing the way it goes. A walk
 * is one or more legs; between two legs the body is taken from the end of one to the start of the next (the Game
 * Parlor's way in: it goes into the nook downstairs and comes out at the Parlor's arrival spot).
 */
final class RegularWalk {

    /** A person's walking pace: blocks a second, and a tick. */
    static final double PACE = 3.4;
    static final double PER_TICK = PACE / 20.0;
    /** On a step up or down (a stair, a seat) a little slower. */
    static final double STEP_SLOW = 0.7;
    /** How far a body turns in a tick (degrees): a turn, not a snap. */
    static final float TURN = 40f;

    /**
     * Part of a walk on one floor.
     *
     * @param points   its corners, start to end (two at least)
     * @param upstairs on the Game Parlor's floor (drawn only for players who may go in)
     */
    record Leg(List<WalkGrid.Point> points, boolean upstairs) {
        Leg {
            points = List.copyOf(points);
            if (points.isEmpty()) {
                throw new IllegalArgumentException("a leg needs a point");
            }
        }

        double length() {
            double out = 0;
            for (int i = 1; i < points.size(); i++) {
                WalkGrid.Point a = points.get(i - 1);
                WalkGrid.Point b = points.get(i);
                out += Math.hypot(b.x() - a.x(), b.z() - a.z());
            }
            return out;
        }
    }

    private final List<Leg> legs;
    private int leg;
    private int next = 1;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private boolean jumped;

    RegularWalk(List<Leg> legs, float yaw) {
        if (legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("a walk needs a leg");
        }
        this.legs = List.copyOf(legs);
        WalkGrid.Point p = this.legs.getFirst().points().getFirst();
        x = p.x();
        y = p.y();
        z = p.z();
        this.yaw = yaw;
        turnTo(true);
    }

    List<Leg> legs() {
        return legs;
    }

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    double z() {
        return z;
    }

    float yaw() {
        return yaw;
    }

    /** On the Parlor's floor now. */
    boolean upstairs() {
        return legs.get(leg).upstairs();
    }

    /** The last step took it from one leg to the next (a jump: it vanished and came out elsewhere). */
    boolean jumped() {
        return jumped;
    }

    boolean done() {
        return leg == legs.size() - 1 && next >= legs.get(leg).points().size();
    }

    /** Where it ends. */
    WalkGrid.Point end() {
        List<WalkGrid.Point> ps = legs.getLast().points();
        return ps.getLast();
    }

    /** Where the next step would take it (read only): for "is anyone standing there". */
    WalkGrid.Point peek() {
        double[] p = ahead(PER_TICK);
        return new WalkGrid.Point(p[0], p[1], p[2]);
    }

    /** One tick's walk on. */
    void step() {
        jumped = false;
        if (done()) {
            return;
        }
        List<WalkGrid.Point> ps = legs.get(leg).points();
        if (next >= ps.size()) {
            leg++;
            next = 1;
            WalkGrid.Point p = legs.get(leg).points().getFirst();
            x = p.x();
            y = p.y();
            z = p.z();
            jumped = true;
            turnTo(true);
            return;
        }
        double[] p = ahead(PER_TICK);
        x = p[0];
        y = p[1];
        z = p[2];
        next = (int) p[3];
        turnTo(false);
    }

    /** {@code budget} blocks on from here along this leg: x, y, z and the next corner's index. */
    private double[] ahead(double budget) {
        List<WalkGrid.Point> ps = legs.get(leg).points();
        double px = x;
        double py = y;
        double pz = z;
        int n = next;
        double left = budget;
        while (n < ps.size()) {
            WalkGrid.Point to = ps.get(n);
            double flat = Math.hypot(to.x() - px, to.z() - pz);
            boolean steps = Math.abs(to.y() - py) > 0.1;
            double pace = steps ? STEP_SLOW : 1.0;
            if (flat <= left * pace + 1e-9) {
                left -= flat / pace;
                px = to.x();
                py = to.y();
                pz = to.z();
                n++;
                if (left <= 1e-9) {
                    break;
                }
                continue;
            }
            double f = left * pace / flat;
            px += (to.x() - px) * f;
            py += (to.y() - py) * f;
            pz += (to.z() - pz) * f;
            break;
        }
        return new double[]{px, py, pz, n};
    }

    /** Turn toward the way it's going: at once on a jump, else at most {@link #TURN} a tick. */
    private void turnTo(boolean snap) {
        List<WalkGrid.Point> ps = legs.get(leg).points();
        if (next >= ps.size()) {
            return;
        }
        WalkGrid.Point to = ps.get(next);
        double dx = to.x() - x;
        double dz = to.z() - z;
        if (Math.hypot(dx, dz) < 1e-6) {
            return;
        }
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float d = RegularSpots.wrap(want - yaw);
        yaw = snap || Math.abs(d) <= TURN ? RegularSpots.wrap(want) : RegularSpots.wrap(yaw + Math.signum(d) * TURN);
    }

    /** How many ticks the walk takes (for tests and the status). */
    int ticks() {
        RegularWalk copy = new RegularWalk(legs, yaw);
        int n = 0;
        while (!copy.done() && n < 100_000) {
            copy.step();
            n++;
        }
        return n;
    }
}
