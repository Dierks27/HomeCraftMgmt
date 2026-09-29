package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The independent check of an Ice Boat plan (GEN-SPEC §4.4): a real plan passes, and one broken
 * in one way is caught — a sharp corner, a gap in the wall, a pinch in the track, checkpoints out
 * of order.
 */
class BoatValidatorTest {

    private static final Slots.Def SLOT = Slots.ICE_BOAT;

    private static boolean says(List<String> problems, String words) {
        return problems.stream().anyMatch(p -> p.contains(words));
    }

    private static Plan real(String tier, long seed) throws GenFailed {
        return new BoatPlanner().plan(BoatPlannerTest.input('A', seed, tier));
    }

    private static Plan withOps(Plan p, List<BlockOp> ops) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    @Test
    void aRealPlanPasses() throws GenFailed {
        assertEquals(List.of(), BoatValidator.problems(real("medium", 2), "medium"), "medium");
        assertEquals(List.of(), BoatValidator.problems(real("easy", 2), BoatPlannerTest.input('A', 2, "easy")),
                "and read from the plan's input");
    }

    @Test
    void aSquareLoopWithSharpCornersIsRefused() {
        Box half = SLOT.half('A');
        double cx = half.minX() + 64;
        double cz = half.minZ() + 64;
        // a square of side 80 round the middle, sampled every half block
        List<double[]> pts = new ArrayList<>();
        double[][] corners = {{cx + 40, cz - 40}, {cx + 40, cz + 40}, {cx - 40, cz + 40}, {cx - 40, cz - 40}};
        for (int side = 0; side < 4; side++) {
            double[] a = corners[side];
            double[] b = corners[(side + 1) % 4];
            for (int i = 0; i < 160; i++) {
                pts.add(new double[]{a[0] + (b[0] - a[0]) * i / 160.0, a[1] + (b[1] - a[1]) * i / 160.0});
            }
        }
        double[] xs = new double[pts.size()];
        double[] zs = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
        }
        BoatPlanner.Loop square = new BoatPlanner.Loop(cx, cz, xs, zs, 0.5, 320);
        Plan p = new BoatPlanner().toPlan(BoatPlannerTest.input('A', 1, "medium"), BoatPlanner.Level.MEDIUM, square, 1);
        List<String> problems = BoatValidator.problems(p, "medium");
        assertTrue(says(problems, "the track bends"), "a square's corners are too sharp for a boat: " + problems);
    }

    @Test
    void aGapInTheWallIsRefused() throws GenFailed {
        Plan p = real("medium", 3);
        int iceY = p.half().minY() + BoatPlanner.ICE_ABOVE_FLOOR;
        java.util.Set<Long> ice = new java.util.HashSet<>();
        for (BlockOp op : p.ops()) {
            if (op.y() == iceY && !p.blockOf(op).equals(Palette.TRACK_WALL)) {
                ice.add(((long) op.x() << 32) ^ (op.z() & 0xFFFFFFFFL));
            }
        }
        // a wall block right beside the ice (not just touching it corner to corner)
        BlockOp gone = p.ops().stream().filter(op -> op.y() == iceY && p.blockOf(op).equals(Palette.TRACK_WALL)
                && ice.contains(((long) (op.x() + 1) << 32) ^ (op.z() & 0xFFFFFFFFL))).findFirst().orElseThrow();
        List<BlockOp> ops = new ArrayList<>(p.ops());
        ops.removeIf(op -> op.x() == gone.x() && op.z() == gone.z());
        List<String> problems = BoatValidator.problems(withOps(p, ops), "medium");
        assertTrue(says(problems, "has no wall beside it"), "the ice next to the gap is open: " + problems);
    }

    @Test
    void aPinchInTheTrackIsRefused() throws GenFailed {
        Plan p = real("hard", 4);
        int iceY = p.half().minY() + BoatPlanner.ICE_ABOVE_FLOOR;
        // wall off the ice across one stretch: a column of wall right across the lane
        Course.Mark m = ((PlannedTrial) p.course()).course().checkpoints().get(2);
        int mx = (int) Math.floor(m.x());
        int mz = (int) Math.floor(m.z());
        List<BlockOp> ops = new ArrayList<>();
        short wall = (short) p.palette().indexOf(Palette.TRACK_WALL);
        for (BlockOp op : p.ops()) {
            boolean pinch = op.y() == iceY && Math.abs(op.x() - mx) <= 0 && Math.abs(op.z() - mz) <= 0;
            ops.add(pinch ? new BlockOp(op.x(), op.y(), op.z(), wall) : op);
            if (pinch) {
                ops.add(new BlockOp(op.x(), iceY + 1, op.z(), wall));
            }
        }
        List<String> problems = BoatValidator.problems(withOps(p, ops), "hard");
        assertTrue(!problems.isEmpty(), "a post in the middle of the lane is caught: " + problems);
    }

    @Test
    void checkpointsOutOfOrderAreRefused() throws GenFailed {
        Plan p = real("easy", 5);
        Course c = ((PlannedTrial) p.course()).course();
        List<Course.Mark> cps = new ArrayList<>(c.checkpoints());
        int lap = cps.size() / 2;
        Course.Mark a = cps.get(1);
        cps.set(1, cps.get(2));
        cps.set(2, a);
        cps.set(lap + 1, cps.get(1));
        cps.set(lap + 2, cps.get(2));
        Plan bad = Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(),
                new PlannedTrial(c.withCheckpoints(cps), ((PlannedTrial) p.course()).refMs()), p.summary(), p.work());
        assertTrue(says(BoatValidator.problems(bad, "easy"), "aren't in loop order"), "swapped checkpoints");
    }

    @Test
    void anAlgoOneLayoutWithNoStandStillPassesOnRecallAndKeep() throws GenFailed {
        Plan two = real("medium", 7);
        int floorY = two.half().minY() + BoatPlanner.ICE_ABOVE_FLOOR + 5;
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : two.ops()) {
            if (op.y() < floorY) {
                ops.add(op); // a layout from before the stand: the track only
            }
        }
        List<com.dierks.homecraft.games.gen.api.SignText> signs = new ArrayList<>();
        for (com.dierks.homecraft.games.gen.api.SignText sign : two.signs()) {
            if (sign.y() != floorY + 1) {
                signs.add(sign); // and no "watch from here" sign
            }
        }
        Plan one = Plan.of(two.slot(), 1, two.seed(), two.half(), two.palette(), ops, signs, two.keepClear(),
                two.course(), two.summary(), two.work());
        assertEquals(List.of(), BoatValidator.problems(one, "medium"), "an algo 1 layout never had a stand: none is asked for");
        assertTrue(says(BoatValidator.problems(Plan.of(two.slot(), 2, two.seed(), two.half(), two.palette(), ops, signs,
                        two.keepClear(), two.course(), two.summary(), two.work()), "medium"), "platform has a hole"),
                "the same blocks as algo 2 must have it");
        char other = SLOT.half('A').equals(one.half()) ? 'B' : 'A';
        Box to = SLOT.half(other);
        Plan moved = com.dierks.homecraft.games.gen.api.PlanShift.to(one, to); // a recall, or a kept layout, moved
        assertEquals(List.of(), com.dierks.homecraft.games.gen.engine.PlanCheck.problems(moved, SLOT, to),
                "the structural check passes it in the other half");
        assertEquals(List.of(), BoatValidator.problems(moved, "medium"), "and so does the boat check, still with no stand");
        assertEquals(1, moved.algo(), "it stays an algo 1 layout (racers go home at the line)");
    }

    /** The plan with every stand block for which {@code drop} is true taken out, and {@code extra} added. */
    private static Plan standEdited(Plan p, java.util.function.Predicate<BlockOp> drop, List<BlockOp> extra) {
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            if (!drop.test(op)) {
                ops.add(op);
            }
        }
        ops.addAll(extra);
        return withOps(p, ops);
    }

    @Test
    void theViewingStandIsCheckedRailHeadroomAndClearanceFromTheLane() throws GenFailed {
        Plan p = real("medium", 7);
        Box half = p.half();
        int cx = com.dierks.homecraft.games.trial.RaceStand.centreX(half);
        int cz = com.dierks.homecraft.games.trial.RaceStand.centreZ(half);
        int floorY = half.minY() + BoatPlanner.ICE_ABOVE_FLOOR + 5;
        assertEquals(List.of(), BoatValidator.problems(p, "medium"), "the planner's stand passes");

        Plan gap = standEdited(p, op -> op.x() == cx + 3 && op.z() == cz && op.y() == floorY + 2, List.of());
        assertTrue(says(BoatValidator.problems(gap, "medium"), "rail has a gap"), "a missing rail block");

        Plan hole = standEdited(p, op -> op.x() == cx && op.z() == cz && op.y() == floorY, List.of());
        assertTrue(says(BoatValidator.problems(hole, "medium"), "platform has a hole"), "a missing floor block");

        short rail = (short) p.palette().indexOf(com.dierks.homecraft.games.trial.RaceStand.RAIL_BLOCK);
        Plan roofed = standEdited(p, op -> false, List.of(new BlockOp(cx, floorY + 1, cz + 1, rail)));
        assertTrue(says(BoatValidator.problems(roofed, "medium"), "no headroom"), "a block over the inner 5 x 5");

        Plan lid = standEdited(p, op -> false, List.of(new BlockOp(cx, floorY + 4, cz, rail)));
        assertTrue(says(BoatValidator.problems(lid, "medium"), "a stray"), "a lid over the stand");

        Plan none = standEdited(p, op -> op.y() >= floorY, List.of());
        assertTrue(says(BoatValidator.problems(none, "medium"), "platform has a hole"),
                "an algo 2 layout must have its stand");
    }

    @Test
    void aStandTooCloseToTheLaneIsRefused() throws GenFailed {
        Plan p = real("easy", 8);
        Course c = ((PlannedTrial) p.course()).course();
        // move the whole stand next to the track: its middle 10 blocks inside the finish line
        Box half = p.half();
        int cx = com.dierks.homecraft.games.trial.RaceStand.centreX(half);
        int cz = com.dierks.homecraft.games.trial.RaceStand.centreZ(half);
        int floorY = half.minY() + BoatPlanner.ICE_ABOVE_FLOOR + 5;
        int tx = (int) Math.floor(c.finish().x());
        int dx = tx - cx - (tx > cx ? 10 : -10);
        List<BlockOp> moved = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            if (op.y() >= floorY) {
                moved.add(new BlockOp(op.x() + dx, op.y(), op.z(), op.state()));
            }
        }
        List<String> problems = BoatValidator.standProblems(p, lowOf(p), stand(p, moved), cx + dx, cz, floorY);
        assertTrue(says(problems, "blocks from the ice"), "a stand a boat could reach is refused: " + problems);
    }

    /** The ice layer as the validator reads it. */
    private static byte[][] lowOf(Plan p) {
        Box half = p.half();
        int iceY = half.minY() + BoatPlanner.ICE_ABOVE_FLOOR;
        byte[][] low = new byte[half.sizeX()][half.sizeZ()];
        for (BlockOp op : p.ops()) {
            if (op.y() == iceY) {
                low[op.x() - half.minX()][op.z() - half.minZ()] = p.blockOf(op).equals(Palette.TRACK_WALL)
                        ? BoatPlanner.WALL : BoatPlanner.ICE;
            }
        }
        return low;
    }

    /** The stand's blocks, keyed as the validator keys them. */
    private static java.util.Map<Long, String> stand(Plan p, List<BlockOp> ops) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (BlockOp op : ops) {
            out.put(((long) (op.y() & 0xFFF) << 52) ^ ((long) (op.x() & 0x3FFFFFF) << 26) ^ (op.z() & 0x3FFFFFFL),
                    Palette.id(p.blockOf(op)));
        }
        return out;
    }

    @Test
    void aTierItCantCheckIsSaidPlainly() throws GenFailed {
        Plan p = real("easy", 6);
        assertTrue(says(BoatValidator.problems(p, "EEE"), "isn't an Ice Boat tier"), "a golf mix");
    }
}
