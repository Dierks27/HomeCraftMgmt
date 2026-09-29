package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.boat.BoatValidator;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.games.gen.dropper.DropperValidator;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.golf.GolfValidator;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourValidator;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import com.dierks.homecraft.games.gen.rings.RingsValidator;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The engine's own look at a plan before a single block is set (GEN-SPEC §3.3 step 2).
 *
 * <p>Each generator proves its plan solvable with its own independent validator ({@link #generator},
 * run against the live inputs); this is the part every generator shares and the engine must be
 * sure of whatever a planner returns: the plan is for this slot and this half, every block and
 * sign is inside the half and on the palette, no two writes land on one block, the course's
 * points are inside the half, and the stored hash really names these blocks (so the tag written
 * at the flip names the layout that stands). A plan with a problem is a failed try; the old
 * layout stays up.
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
        for (String bad : paletteProblems(plan.palette(), def)) {
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

    /**
     * The palette entries {@code def}'s plans may not use ({@link Palette#problems}): water never,
     * except a still source ({@link Palette#POOL_WATER}) in a Dropper's plan, whose own validator
     * proves every pool sealed (EVENTS-DROPPER-SPEC §B.1.9). Every other generator still refuses water.
     */
    public static List<String> paletteProblems(List<String> palette, Slots.Def def) {
        List<String> out = new ArrayList<>();
        boolean dropper = def != null && def.dropper();
        for (String bad : Palette.problems(palette)) {
            if (!(dropper && Palette.poolWater(bad))) {
                out.add(bad);
            }
        }
        return out;
    }

    /**
     * The extra proof a plan MOVED from where it was made needs (a recall into a Classics slot, a
     * keep into its plot), or empty: a Dropper's is its whole validator again
     * ({@link DropperValidator#problems(Plan)}, which reads its mix back from its own pools), so a
     * moved dropper is proven sealed and solvable where it will stand. The other generators' shared
     * checks ({@link #problems}) are enough for a plan that is only translated.
     */
    public static List<String> movedProblems(Plan plan, Slots.Def def) {
        if (plan == null || def == null || !def.dropper()) {
            return List.of();
        }
        return DropperValidator.problems(plan);
    }

    /**
     * The generator's own independent validator (§4.x) run against the live inputs, so a layout
     * that was fine when it was made but isn't under today's settings (a lower
     * {@code trials.fall_depth}) is never built or re-opened: {@link ParkourValidator},
     * {@link RingsValidator}, {@link BoatValidator}, golf's quick check ({@link GolfValidator}
     * without the sloppy-player tree, which the planner already ran) and {@link DropperValidator}. A
     * planner that isn't one of the five (a test's) vouches for its own plans. Pure; run on the
     * planner thread.
     */
    public static List<String> generator(Planner planner, Plan plan, PlanInput in) {
        if (plan == null) {
            return List.of();
        }
        if (planner instanceof ParkourPlanner) {
            return ParkourValidator.problems(plan, in);
        }
        if (planner instanceof RingsPlanner) {
            return RingsValidator.problems(plan, in);
        }
        if (planner instanceof BoatPlanner) {
            return BoatValidator.problems(plan, in);
        }
        if (planner instanceof GolfPlanner) {
            return GolfValidator.quickProblems(plan);
        }
        if (planner instanceof DropperPlanner) {
            return DropperValidator.problems(plan); // the mix its pools name: a heal re-derives the tag's own
        }
        return List.of();
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
