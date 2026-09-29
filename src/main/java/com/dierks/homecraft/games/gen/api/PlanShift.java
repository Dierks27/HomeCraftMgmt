package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.List;

/**
 * An archived plan moved somewhere else (GEN-SPEC-KEEP §3, §4): into a Classics slot's half for a
 * recall, or into a plot of the keep area to keep it for good.
 *
 * <p>Everything moves by the same whole-block offset — every block, sign and keep-clear box, and
 * every point of the course (start, checkpoints, finish and the fall height; every tee, cup and
 * bound corner) — so the moved course is the same course: the same jumps, the same putts, the same
 * witness lines. Only its place and so its hash change. Pure.
 */
public final class PlanShift {

    private PlanShift() {
    }

    /** {@code plan} with its half's min corner moved to {@code to}'s min corner. */
    public static Plan to(Plan plan, Box to) {
        return by(plan, to.minX() - plan.half().minX(), to.minY() - plan.half().minY(),
                to.minZ() - plan.half().minZ());
    }

    /** {@code plan} moved by (dx, dy, dz) blocks; its hash is worked out again for the new place. */
    public static Plan by(Plan plan, int dx, int dy, int dz) {
        List<BlockOp> ops = new ArrayList<>(plan.ops().size());
        for (BlockOp op : plan.ops()) {
            ops.add(new BlockOp(op.x() + dx, op.y() + dy, op.z() + dz, op.state()));
        }
        List<SignText> signs = new ArrayList<>(plan.signs().size());
        for (SignText s : plan.signs()) {
            signs.add(new SignText(s.x() + dx, s.y() + dy, s.z() + dz, s.blockData(), s.lines()));
        }
        List<Box> keepClear = new ArrayList<>(plan.keepClear().size());
        for (Box b : plan.keepClear()) {
            keepClear.add(b.translate(dx, dy, dz));
        }
        PlannedCourse course = course(plan.course(), dx, dy, dz);
        return Plan.of(plan.slot(), plan.algo(), plan.seed(), plan.half().translate(dx, dy, dz), plan.palette(), ops,
                signs, keepClear, course, plan.summary(), plan.work());
    }

    /** The course moved by (dx, dy, dz). */
    public static PlannedCourse course(PlannedCourse pc, int dx, int dy, int dz) {
        if (pc instanceof PlannedTrial t) {
            return new PlannedTrial(trial(t.course(), dx, dy, dz), t.refMs());
        }
        PlannedGolf g = (PlannedGolf) pc;
        return new PlannedGolf(golf(g.course(), dx, dy, dz), g.attempts(), g.witness(), g.expert(), g.kid());
    }

    /** A time trial moved by (dx, dy, dz): start, checkpoints, finish and fall height. */
    public static Course trial(Course c, int dx, int dy, int dz) {
        Course.Spot start = c.start() == null ? null : new Course.Spot(c.start().x() + dx, c.start().y() + dy,
                c.start().z() + dz, c.start().yaw(), c.start().pitch());
        List<Course.Mark> cps = new ArrayList<>();
        for (Course.Mark m : c.checkpoints()) {
            cps.add(mark(m, dx, dy, dz));
        }
        Course.Mark finish = c.finish() == null ? null : mark(c.finish(), dx, dy, dz);
        Double fall = c.fallY() == null ? null : c.fallY() + dy;
        return new Course(c.id(), c.kind(), c.name(), c.tier(), c.world(), start, cps, finish, fall, c.minSeconds(),
                c.enabled(), c.pinned(), c.rev(), c.gen());
    }

    /** A golf course moved by (dx, dy, dz): every tee, cup and bound corner. */
    public static GolfCourse golf(GolfCourse c, int dx, int dy, int dz) {
        List<GolfCourse.Hole> holes = new ArrayList<>();
        for (GolfCourse.Hole h : c.holes()) {
            GolfCourse.Tee tee = h.tee() == null ? null : new GolfCourse.Tee(h.tee().x() + dx, h.tee().y() + dy,
                    h.tee().z() + dz, h.tee().yaw());
            holes.add(new GolfCourse.Hole(tee, spot(h.cup(), dx, dy, dz), h.par(), spot(h.corner1(), dx, dy, dz),
                    spot(h.corner2(), dx, dy, dz)));
        }
        return new GolfCourse(c.id(), c.name(), c.world(), c.enabled(), c.rev(), holes, c.gen());
    }

    private static Course.Mark mark(Course.Mark m, int dx, int dy, int dz) {
        return new Course.Mark(m.x() + dx, m.y() + dy, m.z() + dz, m.radius());
    }

    private static GolfCourse.Spot spot(GolfCourse.Spot s, int dx, int dy, int dz) {
        return s == null ? null : new GolfCourse.Spot(s.x() + dx, s.y() + dy, s.z() + dz);
    }
}
