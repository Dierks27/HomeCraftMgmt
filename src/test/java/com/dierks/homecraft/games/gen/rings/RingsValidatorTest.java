package com.dierks.homecraft.games.gen.rings;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The independent check of a Sky Rings plan (GEN-SPEC §4.2): a real plan passes, and a plan
 * broken in one way is caught with words an admin can act on — a ring lifted above the glide
 * line, a gap in a frame, a block in a hole, a stray block, rings too close together.
 */
class RingsValidatorTest {

    private static Plan real(String tier, long seed) throws GenFailed {
        return new RingsPlanner().plan(RingsPlannerTest.input('A', seed, tier));
    }

    private static boolean says(List<String> problems, String words) {
        return problems.stream().anyMatch(p -> p.contains(words));
    }

    /** The plan with ring {@code k} (0-based) and its mark moved by (dx, dy, dz). */
    private static Plan moveRing(Plan p, int k, int dx, int dy, int dz, int radius) {
        Course c = ((PlannedTrial) p.course()).course();
        List<Course.Mark> marks = new ArrayList<>(c.checkpoints());
        marks.add(c.finish());
        Course.Mark m = marks.get(k);
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            double ex = op.x() + 0.5 - m.x();
            double ey = op.y() + 0.5 - m.y();
            double ez = op.z() + 0.5 - m.z();
            boolean mine = Math.sqrt(ex * ex + ey * ey + ez * ez) < radius + 1 && !p.blockOf(op).equals(Palette.TOWER)
                    && !p.blockOf(op).startsWith(Palette.PILLAR);
            ops.add(mine ? new BlockOp(op.x() + dx, op.y() + dy, op.z() + dz, op.state()) : op);
        }
        Course.Mark moved = new Course.Mark(m.x() + dx, m.y() + dy, m.z() + dz, m.radius());
        List<Course.Mark> cps = new ArrayList<>(c.checkpoints());
        Course.Mark finish = c.finish();
        if (k < cps.size()) {
            cps.set(k, moved);
        } else {
            finish = moved;
        }
        Course changed = c.withCheckpoints(cps).withFinish(finish);
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), ops, p.signs(), p.keepClear(),
                new PlannedTrial(changed, ((PlannedTrial) p.course()).refMs()), p.summary(), p.work());
    }

    private static Plan withOps(Plan p, List<BlockOp> ops) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    @Test
    void aRealPlanPasses() throws GenFailed {
        assertEquals(List.of(), RingsValidator.problems(real("easy", 3), "easy"), "easy");
        assertEquals(List.of(), RingsValidator.problems(real("hard", 3), "hard"), "hard");
        assertEquals(List.of(), RingsValidator.problems(real("medium", 3),
                RingsPlannerTest.input('A', 3, "medium")), "and read from the plan's input");
    }

    @Test
    void aRingLiftedAboveTheGlideLineIsRefused() throws GenFailed {
        Plan p = real("easy", 5);
        Plan bad = moveRing(p, 4, 0, 5, 0, 6);
        List<String> problems = RingsValidator.problems(bad, "easy");
        assertTrue(says(problems, "leg 5") && (says(problems, "under the glide line") || says(problems,
                "doesn't descend")), "the leg into it no longer drops enough: " + problems);
    }

    @Test
    void aGapInAFrameIsRefused() throws GenFailed {
        Plan p = real("medium", 6);
        Course.Mark m = ((PlannedTrial) p.course()).course().checkpoints().get(2);
        int cx = (int) Math.floor(m.x());
        int cy = (int) Math.floor(m.y());
        int cz = (int) Math.floor(m.z());
        List<BlockOp> ops = new ArrayList<>(p.ops());
        ops.removeIf(op -> op.x() == cx && op.y() == cy - 5 && op.z() == cz);
        List<String> problems = RingsValidator.problems(withOps(p, ops), "medium");
        assertTrue(says(problems, "ring 3") && says(problems, "isn't one whole"), "the broken ring: " + problems);
    }

    @Test
    void aBlockInAHoleOrAStrayBlockIsRefused() throws GenFailed {
        Plan p = real("easy", 7);
        Course.Mark m = ((PlannedTrial) p.course()).course().checkpoints().get(1);
        List<BlockOp> hole = new ArrayList<>(p.ops());
        hole.add(new BlockOp((int) Math.floor(m.x()), (int) Math.floor(m.y()), (int) Math.floor(m.z()), (short) 0));
        assertTrue(says(RingsValidator.problems(withOps(p, hole), "easy"), "hole has a block"), "a block in a hole");
        List<BlockOp> stray = new ArrayList<>(p.ops());
        stray.add(new BlockOp(p.half().minX() + 3, p.half().minY() + 3, p.half().minZ() + 3, (short) 0));
        assertTrue(says(RingsValidator.problems(withOps(p, stray), "easy"), "a stray"), "a block that belongs nowhere");
    }

    @Test
    void ringsTooCloseTogetherAreRefused() throws GenFailed {
        Plan p = real("easy", 8);
        Course c = ((PlannedTrial) p.course()).course();
        Course.Mark a = c.checkpoints().get(3);
        Course.Mark b = c.checkpoints().get(4);
        // pull ring 5 most of the way back to ring 4
        int dx = (int) Math.round((a.x() - b.x()) * 0.6);
        int dz = (int) Math.round((a.z() - b.z()) * 0.6);
        List<String> problems = RingsValidator.problems(moveRing(p, 4, dx, 0, dz, 6), "easy");
        assertTrue(says(problems, "rings 4 and 5 are") || says(problems, "leg 5 is"), "too close: " + problems);
    }

    @Test
    void aTierItCantCheckIsSaidPlainly() throws GenFailed {
        Plan p = real("easy", 9);
        assertTrue(says(RingsValidator.problems(p, "EEE"), "isn't a Sky Rings tier"), "a golf mix");
        assertTrue(!RingsValidator.problems(p, "hard").isEmpty(), "an easy layout isn't a hard one");
    }
}
