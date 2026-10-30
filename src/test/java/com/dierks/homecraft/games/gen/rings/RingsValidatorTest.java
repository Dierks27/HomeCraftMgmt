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
 * The independent check of a Sky Rings plan (GEN-SPEC §4.2, Sky Rings v3): a real plan passes, and
 * a plan broken in one way is caught with words an admin can act on — a glide ring lifted above the
 * glide line or into a climb, a climb outside its band or not white, a rocket budget off the need,
 * two rocket legs in a row, a gap in a frame, a block in a hole, a stray block, rings too close
 * together, a lap too fast, and an edition an older planner made.
 */
class RingsValidatorTest {

    private static Plan real(String tier, long seed) throws GenFailed {
        return new RingsPlanner().plan(RingsPlannerTest.input('A', seed, tier));
    }

    private static boolean says(List<String> problems, String words) {
        return problems.stream().anyMatch(p -> p.contains(words));
    }

    private static Course course(Plan p) {
        return ((PlannedTrial) p.course()).course();
    }

    private static Plan withCourse(Plan p, Course c, List<BlockOp> ops) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), ops, p.signs(), p.keepClear(),
                new PlannedTrial(c, ((PlannedTrial) p.course()).refMs()), p.summary(), p.work());
    }

    /** The plan with ring {@code k} (0-based) and its mark moved by (dx, dy, dz): every block within R + 1 of its centre. */
    private static Plan moveRing(Plan p, int k, int dx, int dy, int dz, int radius) {
        Course c = course(p);
        List<Course.Mark> marks = new ArrayList<>(c.checkpoints());
        marks.add(c.finish());
        Course.Mark m = marks.get(k);
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            double ex = op.x() + 0.5 - m.x();
            double ey = op.y() + 0.5 - m.y();
            double ez = op.z() + 0.5 - m.z();
            boolean mine = Math.sqrt(ex * ex + ey * ey + ez * ez) < radius + 1;
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
        return withCourse(p, c.withCheckpoints(cps).withFinish(finish), ops);
    }

    /** The plan with ring {@code k}'s blocks (0-based) in {@code colour}. */
    private static Plan recolour(Plan p, int k, String colour) {
        Course c = course(p);
        List<Course.Mark> marks = new ArrayList<>(c.checkpoints());
        marks.add(c.finish());
        Course.Mark m = marks.get(k);
        List<String> palette = new ArrayList<>(p.palette());
        short s = RingsPlanner.index(palette, colour);
        List<BlockOp> ops = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            double ex = op.x() + 0.5 - m.x();
            double ey = op.y() + 0.5 - m.y();
            double ez = op.z() + 0.5 - m.z();
            boolean mine = Math.sqrt(ex * ex + ey * ey + ez * ez) < 9;
            ops.add(mine ? new BlockOp(op.x(), op.y(), op.z(), s) : op);
        }
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), palette, ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    /** The plan with the legs' kinds {@code legs}, the need made again from them and {@code rockets}. */
    private static Plan withLegs(Plan p, String legs, int rockets) {
        Course c = course(p);
        List<Integer> need = RingsPlanner.needList(RingsPlanner.need(RingsPlanner.kinds(legs)));
        return withCourse(p, c.withElytra(new Course.Elytra(rockets, need, legs)), p.ops());
    }

    private static Plan withOps(Plan p, List<BlockOp> ops) {
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    /** The 0-based ring a climb ends at, the first one. */
    private static int firstLift(Plan p) {
        return course(p).elytra().legs().indexOf('L') + 1;
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
        // ring 5 ends leg 5, a glide on every profile (the first three legs and the fourth, a glide after any level leg)
        Plan bad = moveRing(p, 4, 0, 5, 0, 8);
        List<String> problems = RingsValidator.problems(bad, "easy");
        assertTrue(says(problems, "leg 5") && (says(problems, "under the glide line") || says(problems,
                "doesn't descend")), "the leg into it no longer drops enough: " + problems);
    }

    @Test
    void aGlideLegThatNeedsARocketIsRefused() throws GenFailed {
        // a climb's leg called a glide: its ring is above the last, and no glide may need a rocket
        Plan p = real("easy", 5);
        int k = firstLift(p);
        String legs = course(p).elytra().legs();
        String asGlide = legs.substring(0, k - 1) + 'G' + legs.substring(k);
        Plan bad = recolour(withLegs(p, asGlide, 7), k, Palette.RAINBOW.get(k % Palette.RAINBOW.size()));
        List<String> problems = RingsValidator.problems(bad, "easy");
        assertTrue(says(problems, "leg " + (k + 1)) && says(problems, "doesn't descend"),
                "a glide can't climb: " + problems);
    }

    @Test
    void aLiftOutsideItsBandIsRefused() throws GenFailed {
        Plan p = real("easy", 5);
        int k = firstLift(p);
        List<Course.Mark> marks = new ArrayList<>(course(p).checkpoints());
        marks.add(course(p).finish());
        double climb = marks.get(k).y() - marks.get(k - 1).y();
        int up = (int) (RingsPlanner.Level.EASY.upMax() - climb + 5);
        List<String> problems = RingsValidator.problems(moveRing(p, k, 0, up, 0, 8), "easy");
        assertTrue(says(problems, "leg " + (k + 1) + " climbs") && says(problems, "a climb is 25 to 45"),
                "a climb over 45: " + problems);
    }

    @Test
    void aLiftRingThatIsntWhiteIsRefused() throws GenFailed {
        Plan p = real("medium", 6);
        int k = firstLift(p);
        List<String> problems = RingsValidator.problems(recolour(p, k, Palette.RAINBOW.get(0)), "medium");
        assertTrue(says(problems, "ring " + (k + 1)) && says(problems, "isn't one whole white"),
                "a climb's ring is white: " + problems);
    }

    @Test
    void aBudgetOffTheNeedIsRefused() throws GenFailed {
        Plan p = real("easy", 7);
        Course c = course(p);
        Plan nine = withCourse(p, c.withElytra(new Course.Elytra(9, c.elytra().need(), c.elytra().legs())), p.ops());
        assertTrue(says(RingsValidator.problems(nine, "easy"), "rockets 9, not the 6 needed plus 2"),
                "rockets are the need plus two: " + RingsValidator.problems(nine, "easy"));
        List<Integer> need = new ArrayList<>(c.elytra().need());
        need.set(3, need.get(3) + 1);
        Plan off = withCourse(p, c.withElytra(new Course.Elytra(8, need, c.elytra().legs())), p.ops());
        assertTrue(says(RingsValidator.problems(off, "easy"), "aren't what the legs say"), "the need is from the legs");
        Plan none = withCourse(p, c.withElytra(null), p.ops());
        assertTrue(says(RingsValidator.problems(none, "easy"), "no rocket budget"), "a budget is needed");
    }

    @Test
    void twoRocketLegsInARowAreRefused() throws GenFailed {
        Plan p = real("easy", 8);
        String legs = course(p).elytra().legs();
        int k = legs.indexOf('L');
        // the glide after the first climb called a level leg
        String bad = legs.substring(0, k + 1) + 'F' + legs.substring(k + 2);
        List<String> problems = RingsValidator.problems(withLegs(p, bad, 9), "easy");
        assertTrue(says(problems, "two rocket legs in a row"), "a climb then a level leg: " + problems);
    }

    @Test
    void aV2EditionIsRefusedPlainly() throws GenFailed {
        Plan p = real("easy", 9);
        Plan old = Plan.of(p.slot(), 2, p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
        List<String> problems = RingsValidator.problems(old, "easy");
        assertEquals(1, problems.size(), "one plain line, before any geometry: " + problems);
        assertTrue(says(problems, "made by Sky Rings v2") && says(problems, "stays in the archive"), problems.toString());
    }

    @Test
    void aLapOver60BlocksASecondIsRefused() throws GenFailed {
        Plan p = real("easy", 10);
        assertEquals(List.of(), RingsValidator.problems(p, "easy", RingsValidator.PEAK_SPEED), "under 60 a second");
        List<String> problems = RingsValidator.problems(p, "easy", 1.0);
        assertTrue(says(problems, "the autopilot's lap peaks at") && says(problems, "20 blocks a second is the most"),
                "the cap, with the lap's peak: " + problems);
    }

    @Test
    void aGapInAFrameIsRefused() throws GenFailed {
        Plan p = real("medium", 6);
        Course.Mark m = course(p).checkpoints().get(2);
        int cx = (int) Math.floor(m.x());
        int cy = (int) Math.floor(m.y());
        int cz = (int) Math.floor(m.z());
        List<BlockOp> ops = new ArrayList<>(p.ops());
        ops.removeIf(op -> op.x() == cx && op.y() == cy - 7 && op.z() == cz);
        List<String> problems = RingsValidator.problems(withOps(p, ops), "medium");
        assertTrue(says(problems, "ring 3") && says(problems, "isn't one whole"), "the broken ring: " + problems);
    }

    @Test
    void aBlockInAHoleOrAStrayBlockIsRefused() throws GenFailed {
        Plan p = real("easy", 7);
        Course.Mark m = course(p).checkpoints().get(1);
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
        Course c = course(p);
        Course.Mark a = c.checkpoints().get(3);
        Course.Mark b = c.checkpoints().get(4);
        // pull ring 5 most of the way back to ring 4
        int dx = (int) Math.round((a.x() - b.x()) * 0.6);
        int dz = (int) Math.round((a.z() - b.z()) * 0.6);
        List<String> problems = RingsValidator.problems(moveRing(p, 4, dx, 0, dz, 8), "easy");
        assertTrue(says(problems, "rings 4 and 5 are") || says(problems, "leg 5 is"), "too close: " + problems);
    }

    @Test
    void aTierItCantCheckIsSaidPlainly() throws GenFailed {
        Plan p = real("easy", 9);
        assertTrue(says(RingsValidator.problems(p, "EEE"), "isn't a Sky Rings tier"), "a golf mix");
        assertTrue(!RingsValidator.problems(p, "hard").isEmpty(), "an easy layout isn't a hard one");
    }
}
