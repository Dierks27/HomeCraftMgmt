package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * What the Dropper tests share: real plans from the planner (cached, since a plan is immutable and
 * takes a few milliseconds), ways to spoil one on purpose for the validator's tests, and a single
 * hand-built shaft for the physics tests, where the blocks must be exactly where a test puts them.
 */
final class DropperFixtures {

    /** The server secret the tests' seeds come from (any fixed number: the engine's is per server). */
    static final long SECRET = 0x5EC12E7L;
    /** The course day the tests plan for. */
    static final long DAY = 20_000;

    private static final DropperPlanner PLANNER = new DropperPlanner();
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<Plan, Map<Long, String>> BLOCKS = Collections.synchronizedMap(new IdentityHashMap<>());

    private DropperFixtures() {
    }

    /** The slot a mix is planned into in the tests: Easy Dropper for EEE, the Dropper otherwise. */
    static Slots.Def slotFor(String mix) {
        return "EEE".equals(mix) ? DropperSlots.EASY : DropperSlots.DROPPER_SLOT;
    }

    /** Test seed {@code n} for {@code slot}, made the way the engine makes one. */
    static long seed(Slots.Def slot, int n) {
        return GenSeed.seed(SECRET, DAY + n, slot.id(), 0);
    }

    /** The planner's input for {@code mix} and seed {@code n} in half {@code h}. */
    static PlanInput input(String mix, int n, char h) {
        Slots.Def slot = slotFor(mix);
        return new PlanInput(slot, slot.half(h), h, DAY + n, 0, seed(slot, n), mix, 6, 0, null);
    }

    /** The plan for {@code mix} and seed {@code n} in half A (cached). */
    static Plan plan(String mix, int n) {
        return PLANS.computeIfAbsent(mix + "/" + n, k -> {
            try {
                return PLANNER.plan(input(mix, n, 'A'));
            } catch (GenFailed e) {
                throw new AssertionError("the fixture plan " + k + " should plan: " + e.getMessage(), e);
            }
        });
    }

    /** The trial course of a plan. */
    static Course course(Plan p) {
        return ((PlannedTrial) p.course()).course();
    }

    /** The blocks of a plan, as the physics sees them. */
    static DropWorld world(Plan p) {
        return DropWorld.of(p);
    }

    /** Level {@code i} of a plan as its blocks say it is. */
    static DropCheck.View view(Plan p, int i, DropRules.Level tier) {
        Course c = course(p);
        DropCheck.Read r = DropCheck.read(world(p), i, tier, DropMarks.ledgeOf(c, i), DropMarks.poolOf(c, i));
        if (r.view() == null) {
            throw new AssertionError("level " + (i + 1) + " should read: " + r.problem());
        }
        return r.view();
    }

    /** Level {@code i}'s witness program, from the plan's summary. */
    static DropProgram witness(Plan p, int i) {
        return DropperValidator.programs(p, DropperValidator.WITNESS_PREFIX, i + 1).get(i);
    }

    // ---- spoiling a plan ------------------------------------------------------------------------

    /** The same plan (its hash worked out again) with other parts. */
    static Plan rebuild(Plan p, Box half, List<String> palette, List<BlockOp> ops, List<SignText> signs,
                        Course course, List<String> summary) {
        long ref = ((PlannedTrial) p.course()).refMs();
        return Plan.of(p.slot(), p.algo(), p.seed(), half, palette, ops, signs, p.keepClear(),
                new PlannedTrial(course, ref), summary, p.work());
    }

    /** The plan with every op {@code drop} matches left out. */
    static Plan without(Plan p, Predicate<BlockOp> drop) {
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            if (!drop.test(op)) {
                ops.add(op);
            }
        }
        return rebuild(p, p.half(), p.palette(), ops, p.signs(), course(p), p.summary());
    }

    /** The plan with block (x, y, z) set to {@code blockData} (added to the palette if new). */
    static Plan with(Plan p, int x, int y, int z, String blockData) {
        return withAll(p, List.of(new int[]{x, y, z}), blockData);
    }

    /** The plan with every block of {@code at} set to {@code blockData}, replacing what was there. */
    static Plan withAll(Plan p, List<int[]> at, String blockData) {
        List<String> palette = new ArrayList<>(p.palette());
        int idx = palette.indexOf(blockData);
        if (idx < 0) {
            idx = palette.size();
            palette.add(blockData);
        }
        Set<Long> replaced = new HashSet<>();
        for (int[] b : at) {
            replaced.add(key(b[0], b[1], b[2]));
        }
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            if (!replaced.contains(key(op.x(), op.y(), op.z()))) {
                ops.add(op);
            }
        }
        for (int[] b : at) {
            ops.add(new BlockOp(b[0], b[1], b[2], (short) idx));
        }
        return rebuild(p, p.half(), palette, ops, p.signs(), course(p), p.summary());
    }

    /** The plan with its course replaced. */
    static Plan withCourse(Plan p, Course c) {
        return rebuild(p, p.half(), p.palette(), p.ops(), p.signs(), c, p.summary());
    }

    /** The plan with its summary lines changed. */
    static Plan withSummary(Plan p, UnaryOperator<List<String>> edit) {
        return rebuild(p, p.half(), p.palette(), p.ops(), p.signs(), course(p),
                edit.apply(new ArrayList<>(p.summary())));
    }

    /** The plan with its signs replaced. */
    static Plan withSigns(Plan p, List<SignText> signs) {
        return rebuild(p, p.half(), p.palette(), p.ops(), signs, course(p), p.summary());
    }

    /** The plan claiming a different half (its blocks stay put). */
    static Plan withHalf(Plan p, Box half) {
        return rebuild(p, half, p.palette(), p.ops(), p.signs(), course(p), p.summary());
    }

    /** What block (x, y, z) of a plan is: its block-data text, or null for air. */
    static String blockAt(Plan p, int x, int y, int z) {
        return BLOCKS.computeIfAbsent(p, plan -> {
            Map<Long, String> m = new HashMap<>();
            for (BlockOp op : plan.ops()) {
                m.put(key(op.x(), op.y(), op.z()), plan.blockOf(op));
            }
            return m;
        }).get(key(x, y, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ---- one hand-built shaft ---------------------------------------------------------------------

    /**
     * One Easy shaft built by hand in a 16 x 64 x 16 half at the origin: glass walls from the floor
     * to 4 above the ledge, a 3 x 3 lime ledge against the north wall facing south, the whole floor
     * water 3 deep over a floor. Plates go wherever a test puts them.
     */
    static final class Shaft {
        final Box half = Box.sized(0, 0, 0, 16, 64, 16);
        final DropperGeometry.Shaft s = DropperGeometry.shaft(half, 0);
        final DropRules.Level tier = DropRules.Level.EASY;
        final DropWorld w = new DropWorld(half);
        /** The ledge's blocks: x from {@code ledgeX} for 3, z from the north wall for 3. */
        final int ledgeX = s.x1() + 4;
        final int surface = DropperGeometry.surface(s, tier);

        Shaft() {
            int floor = DropperGeometry.floorRow(s, tier);
            for (int y = floor; y <= s.wallTop(); y++) {
                for (int x = s.x1() - 1; x <= s.x2() + 1; x++) {
                    for (int z = s.z1() - 1; z <= s.z2() + 1; z++) {
                        if (!s.inside(x, z)) {
                            w.set(x, y, z, DropWorld.WALL);
                        }
                    }
                }
            }
            for (int x = ledgeX; x < ledgeX + DropperGeometry.LEDGE; x++) {
                for (int z = s.z1(); z < s.z1() + DropperGeometry.LEDGE; z++) {
                    w.set(x, s.ledgeRow(), z, DropWorld.LEDGE);
                }
            }
            for (int x = s.x1(); x <= s.x2(); x++) {
                for (int z = s.z1(); z <= s.z2(); z++) {
                    w.set(x, floor, z, DropWorld.SOLID);
                    for (int y = surface - DropperGeometry.POOL_DEPTH; y < surface; y++) {
                        w.set(x, y, z, DropWorld.WATER);
                    }
                }
            }
        }

        /** A plate at {@code row}, solid wall to wall except the open square x1-x2, z1-z2. */
        Shaft plate(int row, int ox1, int oz1, int ox2, int oz2) {
            for (int x = s.x1(); x <= s.x2(); x++) {
                for (int z = s.z1(); z <= s.z2(); z++) {
                    boolean open = x >= ox1 && x <= ox2 && z >= oz1 && z <= oz2;
                    w.set(x, row, z, open ? DropWorld.AIR : DropWorld.SOLID);
                }
            }
            return this;
        }

        /** The ledge's mark. */
        Course.Mark ledgeMark() {
            return DropMarks.ledge(ledgeX + 1.5, s.ledgeTop(), s.z1() + 1.5);
        }

        /** The pool's mark: the whole floor. */
        Course.Mark poolMark() {
            return DropMarks.pool(s.x1() + DropperGeometry.INSIDE / 2.0, surface, s.z1() + DropperGeometry.INSIDE / 2.0,
                    DropperGeometry.INSIDE);
        }

        /** The level as its blocks say it is. */
        DropCheck.View view() {
            DropCheck.Read r = DropCheck.read(w, 0, tier, ledgeMark(), poolMark());
            if (r.view() == null) {
                throw new AssertionError("the hand-built shaft should read: " + r.problem());
            }
            return r.view();
        }
    }
}
