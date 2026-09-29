package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The engine's own look at a plan before a single block is set (GEN-SPEC §3.3 step 2).
 *
 * <p>Each generator proves its plan solvable with its own independent validator; this is the part
 * every generator shares and the engine must be sure of whatever a planner returns: the plan is
 * for this slot and this half, every block and sign is inside the half and on the palette, no two
 * writes land on one block, the course's points are inside the half, and the stored hash really
 * names these blocks (so the tag written at the flip names the layout that stands). A plan with a
 * problem is a failed try; the old layout stays up.
 */
public final class PlanCheck {

    /** The most blocks a plan may place (the boat, the largest, is about 3,300). */
    public static final int MAX_OPS = 100_000;

    private PlanCheck() {
    }

    /** Everything wrong with {@code plan} as a plan for {@code def}'s half {@code half}; empty = fine. */
    public static List<String> problems(Plan plan, Slots.Def def, Box half) {
        List<String> out = new ArrayList<>();
        if (plan == null) {
            out.add("the planner returned nothing");
            return out;
        }
        if (!def.id().equals(plan.slot())) {
            out.add("the plan is for " + plan.slot() + ", not " + def.id());
        }
        if (!half.equals(plan.half())) {
            out.add("the plan is for " + (plan.half() == null ? "no half" : plan.half().describe()) + ", not "
                    + half.describe());
        }
        for (String bad : Palette.problems(plan.palette())) {
            out.add("the palette has " + bad + ", which a course may not use");
        }
        if (plan.ops().size() > MAX_OPS) {
            out.add("the plan places " + plan.ops().size() + " blocks, more than " + MAX_OPS);
        }
        Set<Long> seen = new HashSet<>();
        int outside = 0;
        int twice = 0;
        for (BlockOp op : plan.ops()) {
            if (op.state() < 0 || op.state() >= plan.palette().size()) {
                out.add("a block at " + op.x() + "," + op.y() + "," + op.z() + " has no palette entry");
                break;
            }
            if (!half.contains(op.x(), op.y(), op.z())) {
                outside++;
            }
            if (!seen.add(pos(op.x(), op.y(), op.z()))) {
                twice++;
            }
        }
        for (SignText s : plan.signs()) {
            if (!half.contains(s.x(), s.y(), s.z())) {
                outside++;
            }
            if (!seen.add(pos(s.x(), s.y(), s.z()))) {
                twice++;
            }
            if (!Palette.allowed(s.blockData())) {
                out.add("a sign at " + s.x() + "," + s.y() + "," + s.z() + " is " + s.blockData());
            }
        }
        if (outside > 0) {
            out.add(outside + " block" + (outside == 1 ? " is" : "s are") + " outside the half");
        }
        if (twice > 0) {
            out.add(twice + " block" + (twice == 1 ? " is" : "s are") + " set twice");
        }
        for (Box k : plan.keepClear()) {
            if (!half.contains(k)) {
                out.add("a keep-clear box (" + k.describe() + ") reaches outside the half");
            }
        }
        out.addAll(courseProblems(plan, def, half));
        if (!plan.hash().equals(Plan.hash(plan.palette(), plan.ops(), plan.signs(), plan.course()))) {
            out.add("the plan's hash doesn't match its blocks");
        }
        return out;
    }

    private static List<String> courseProblems(Plan plan, Slots.Def def, Box half) {
        List<String> out = new ArrayList<>();
        if (def.golf()) {
            if (!(plan.course() instanceof PlannedGolf g)) {
                out.add("a golf slot's plan has no golf course");
                return out;
            }
            GolfCourse c = g.course();
            out.addAll(c.problems(null));
            int n = c.holes().size();
            if (n == 0 || n > def.plots()) {
                out.add("it has " + n + " holes; this slot fits 1-" + def.plots());
            }
            if (g.witness().size() != n || g.attempts().size() != n) {
                out.add("each hole needs its attempt and its witness line");
            }
            for (int i = 0; i < n; i++) {
                GolfCourse.Hole h = c.holes().get(i);
                if (h.tee() != null && !half.contains(h.tee().x(), h.tee().y(), h.tee().z())) {
                    out.add("hole " + (i + 1) + "'s tee is outside the half");
                }
                if (h.cup() != null && !half.contains(h.cup().x(), h.cup().y(), h.cup().z())) {
                    out.add("hole " + (i + 1) + "'s cup is outside the half");
                }
            }
            return out;
        }
        if (!(plan.course() instanceof PlannedTrial t)) {
            out.add("a trial slot's plan has no trial course");
            return out;
        }
        Course c = t.course();
        if (c.start() == null || c.finish() == null) {
            out.add("the course has no start or no finish");
            return out;
        }
        if (!half.contains(c.start().x(), c.start().y(), c.start().z())) {
            out.add("the start is outside the half");
        }
        int i = 0;
        for (Course.Mark m : c.targets()) {
            i++;
            if (!half.contains(m.x(), m.y(), m.z())) {
                out.add("checkpoint " + i + " is outside the half");
            }
        }
        if (c.checkpoints().size() > Course.MAX_CHECKPOINTS) {
            out.add("it has more than " + Course.MAX_CHECKPOINTS + " checkpoints");
        }
        if (t.refMs() <= 0) {
            out.add("it has no reference time");
        }
        return out;
    }

    private static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
