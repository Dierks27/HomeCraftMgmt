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

    /**
     * The most blocks a plan may place, for every generator but the Mountain Run v2
     * ({@link #maxOps(String, int)}). The largest are a golf course with its scenery (v3 under 30,000, v4
     * about 8,000-16,000; its own validator caps it at 40,000) and the v3 Mountain Run track (typically
     * 10,000-18,000; its validator caps it at 30,000); each generator's validator has its own, lower cap.
     */
    public static final int MAX_OPS = 100_000;
    /**
     * The most blocks a Mountain Run v2 plan (boat algo 4 and later) may place (MOUNTAIN-V2-SPEC §3.6,
     * §10.3 item 4): its whole mountain, about 350,000 for a medium road, terrain skin and trees included.
     * Its own validator caps it at 400,000 too.
     */
    public static final int BOAT_V4_MAX_OPS = 400_000;
    /** The boat planner version from which a plan is a Mountain Run v2 (a whole mountain, not a track). */
    public static final int BOAT_MOUNTAIN_ALGO = 4;

    private PlanCheck() {
    }

    /**
     * The most blocks a plan of {@code generator} at planner version {@code algo} may place: the Mountain
     * Run v2 (boat, algo {@value #BOAT_MOUNTAIN_ALGO} or later) {@value #BOAT_V4_MAX_OPS}, which is a whole
     * mountain; golf its own validator's cap if that is ever above {@value #MAX_OPS} (golf sets its own:
     * {@link GolfValidator#MAX_OPS}); every other generator, and an older boat plan, {@value #MAX_OPS}. Each
     * validator still holds its plans to its own, lower cap. Per plan, so a recall or a keep of an older
     * plan is held to the cap it was made under.
     */
    public static int maxOps(String generator, int algo) {
        if (Slots.BOAT.equals(generator) && algo >= BOAT_MOUNTAIN_ALGO) {
            return BOAT_V4_MAX_OPS;
        }
        return Slots.GOLF.equals(generator) ? Math.max(MAX_OPS, GolfValidator.MAX_OPS) : MAX_OPS;
    }

    /** {@link #maxOps(String, int)} for a plan of {@code def}'s generator ({@link #MAX_OPS} when either is missing). */
    public static int maxOps(Slots.Def def, Plan plan) {
        return def == null || plan == null ? MAX_OPS : maxOps(def.generator(), plan.algo());
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
        for (String bad : Palette.stateProblems(plan.palette())) {
            out.add("the palette's " + bad);
        }
        int cap = maxOps(def.generator(), plan.algo());
        if (plan.ops().size() > cap) {
            out.add("the plan places " + plan.ops().size() + " blocks, more than " + cap);
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
     * except a still source ({@link Palette#POOL_WATER}) in the plan of a slot that
     * {@link Slots.Def#mayHoldWater may hold water}: a Dropper's pools (EVENTS-DROPPER-SPEC §B.1.9)
     * and golf's ponds (Course Variety §1.2), each proven sealed by its own validator. Parkour, Sky
     * Rings and the ice boat still refuse water.
     */
    public static List<String> paletteProblems(List<String> palette, Slots.Def def) {
        List<String> out = new ArrayList<>();
        boolean wet = def != null && def.mayHoldWater();
        for (String bad : Palette.problems(palette)) {
            if (!(wet && Palette.poolWater(bad))) {
                out.add(bad);
            }
        }
        return out;
    }

    /**
     * The extra proof a plan MOVED from where it was made needs (a recall into a Classics slot, a
     * keep into its plot), or empty. A plan whose slot {@link Slots.Def#mayHoldWater may hold water}
     * is proven again where it will stand: a Dropper by its whole validator
     * ({@link DropperValidator#problems(Plan)}, which reads its mix back from its own pools, so it is
     * proven sealed and solvable there), golf by its quick check
     * ({@link GolfValidator#quickProblems}: its ponds sealed and every witness line replayed where it
     * now stands). The other generators' shared checks ({@link #problems}) are enough for a plan that
     * is only translated. Pure; run on the planner thread.
     */
    public static List<String> movedProblems(Plan plan, Slots.Def def) {
        if (plan == null || def == null || !def.mayHoldWater()) {
            return List.of();
        }
        return def.dropper() ? DropperValidator.problems(plan) : GolfValidator.quickProblems(plan);
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
        return generator(planner, plan, in, false);
    }

    /**
     * {@link #generator(Planner, Plan, PlanInput)}, told whether the plan was made again from a live
     * tag ({@code rederived}, a heal). A new Dropper plan is proven against the mix it was asked for
     * ({@code in.tierOrMix()}), so a plan whose pools name another mix is refused. A heal's plan is the
     * tag's own layout (the engine checks its hash next), and the planner may have had to find the
     * mix it was made with, so its pools name the mix it is proven against.
     */
    public static List<String> generator(Planner planner, Plan plan, PlanInput in, boolean rederived) {
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
            return rederived || in == null ? DropperValidator.problems(plan)
                    : DropperValidator.problems(plan, in.tierOrMix());
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
