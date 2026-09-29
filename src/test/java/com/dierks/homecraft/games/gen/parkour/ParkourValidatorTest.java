package com.dierks.homecraft.games.gen.parkour;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The independent check of a parkour plan (GEN-SPEC §4.1): a real plan passes it, and one
 * hand-made bad plan per rule is caught — a flat 4-gap, a 2-block climb, a head-hitter, a skip,
 * a pad under the fall floor, a block outside the half — each with words an admin can act on.
 */
class ParkourValidatorTest {

    private static final Slots.Def HARD = Slots.DAILY_PARKOUR_HARD;
    private static final Slots.Def EASY = Slots.DAILY_PARKOUR_EASY;
    private static final Slots.Def MEDIUM = Slots.DAILY_PARKOUR_MEDIUM;

    /** A hand-made pad: its first corner, size, top and block. */
    private record P(int x1, int z1, int sx, int sz, int top, String block) {
    }

    /**
     * A plan of these pads in this order: the start on the first, a checkpoint on each pad in
     * {@code checkpoints}, the finish on the last.
     */
    private static Plan layout(Slots.Def slot, String tier, Double fallY, List<P> pads, int... checkpoints) {
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = new ArrayList<>();
        for (P p : pads) {
            for (int x = p.x1(); x < p.x1() + p.sx(); x++) {
                for (int z = p.z1(); z < p.z1() + p.sz(); z++) {
                    ops.add(new BlockOp(x, p.top() - 1, z, ParkourPlanner.index(palette, p.block())));
                }
            }
        }
        P s = pads.get(0);
        P f = pads.get(pads.size() - 1);
        List<Course.Mark> cps = new ArrayList<>();
        for (int k : checkpoints) {
            P c = pads.get(k);
            cps.add(new Course.Mark(c.x1() + c.sx() / 2.0, c.top(), c.z1() + c.sz() / 2.0,
                    ParkourPlanner.CHECKPOINT_RADIUS));
        }
        P first = pads.get(1);
        Course.Spot start = new Course.Spot(s.x1() + s.sx() / 2.0, s.top(), s.z1() + s.sz() / 2.0,
                ParkourPlanner.yawToward(s.x1() + s.sx() / 2.0, s.z1() + s.sz() / 2.0, first.x1() + first.sx() / 2.0,
                        first.z1() + first.sz() / 2.0), 0f);
        Course c = new Course(slot.id(), TrialKind.PARKOUR, slot.name(), Tier.of(tier), "", start, cps,
                new Course.Mark(f.x1() + f.sx() / 2.0, f.top(), f.z1() + f.sz() / 2.0, 3.0), fallY, 5, true, false, 1);
        return Plan.of(slot.id(), 1, 1, slot.half('A'), palette, ops, List.of(), List.of(),
                new PlannedTrial(c, 20_000), List.of(), 0);
    }

    private static boolean says(List<String> problems, String words) {
        for (String p : problems) {
            if (p.contains(words)) {
                return true;
            }
        }
        return false;
    }

    private static Plan real(Slots.Def slot, long seed) throws GenFailed {
        return new ParkourPlanner().plan(ParkourPlannerTest.input(slot, seed));
    }

    /** The plan with one more block, or one changed. */
    private static Plan with(Plan p, int x, int y, int z, String block) {
        List<String> palette = new ArrayList<>(p.palette());
        List<BlockOp> ops = new ArrayList<>();
        boolean replaced = false;
        for (BlockOp op : p.ops()) {
            if (op.x() == x && op.y() == y && op.z() == z) {
                ops.add(new BlockOp(x, y, z, ParkourPlanner.index(palette, block)));
                replaced = true;
            } else {
                ops.add(op);
            }
        }
        if (!replaced) {
            ops.add(new BlockOp(x, y, z, ParkourPlanner.index(palette, block)));
        }
        return Plan.of(p.slot(), p.algo(), p.seed(), p.half(), palette, ops, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work());
    }

    @Test
    void aRealPlanOfEveryTierPasses() throws GenFailed {
        assertEquals(List.of(), ParkourValidator.problems(real(EASY, 4), "easy", 6), "easy");
        assertEquals(List.of(), ParkourValidator.problems(real(MEDIUM, 4), "medium", 6), "medium");
        assertEquals(List.of(), ParkourValidator.problems(real(HARD, 4), "hard", 6), "hard");
        assertEquals(List.of(), ParkourValidator.problems(real(HARD, 4),
                ParkourPlannerTest.input(HARD, 4)), "and read from the plan's input");
    }

    @Test
    void aFlatFourGapIsRefusedEvenOnHard() {
        int x = HARD.half('A').minX() + 5;
        int z = HARD.half('A').minZ() + 5;
        List<P> pads = List.of(new P(x, z, 5, 5, 180, Palette.START),
                new P(x + 5 + 4, z + 2, 1, 1, 180, Palette.PATH_HARD),
                new P(x + 5 + 4 + 1 + 2, z + 1, 5, 5, 180, Palette.FINISH));
        List<String> problems = ParkourValidator.problems(layout(HARD, "hard", null, pads), "hard", 6);
        assertTrue(says(problems, "jump 1: 0 over 4.00 isn't allowed in hard"), "the 4-gap is named: " + problems);
    }

    @Test
    void aTwoBlockClimbIsRefused() {
        int x = EASY.half('A').minX() + 5;
        int z = EASY.half('A').minZ() + 5;
        List<P> pads = List.of(new P(x, z, 5, 5, 180, Palette.START),
                new P(x + 6, z + 1, 3, 3, 182, Palette.PATH_EASY),
                new P(x + 11, z, 5, 5, 182, Palette.FINISH));
        List<String> problems = ParkourValidator.problems(layout(EASY, "easy", 177.0, pads), "easy", 6);
        assertTrue(says(problems, "a height step of +2 isn't allowed in easy"), "the +2 is named: " + problems);
    }

    @Test
    void aSprintGapIsRefusedOnEasy() {
        int x = EASY.half('A').minX() + 5;
        int z = EASY.half('A').minZ() + 5;
        List<P> pads = List.of(new P(x, z, 5, 5, 180, Palette.START),
                new P(x + 5 + 3, z + 1, 3, 3, 180, Palette.PATH_EASY),
                new P(x + 5 + 3 + 3 + 2, z, 5, 5, 180, Palette.FINISH));
        List<String> problems = ParkourValidator.problems(layout(EASY, "easy", 177.0, pads), "easy", 6);
        assertTrue(says(problems, "0 over 3.00 isn't allowed in easy"), "a 3-gap needs a sprint: " + problems);
    }

    @Test
    void aHeadHitterIsRefused() throws GenFailed {
        Plan p = real(EASY, 6);
        Course c = ((PlannedTrial) p.course()).course();
        Course.Mark cp = c.checkpoints().get(1);
        // a block two over the middle of a checkpoint pad: a jump there bumps its head
        Plan bad = with(p, (int) Math.floor(cp.x()), (int) cp.y() + 2, (int) Math.floor(cp.z()), Palette.PATH_EASY);
        List<String> problems = ParkourValidator.problems(bad, "easy", 6);
        assertTrue(says(problems, "is in the headroom over pad"), "the head-hitter is named: " + problems);
    }

    @Test
    void aSkipIsRefused() {
        int x = EASY.half('A').minX() + 5;
        int z = EASY.half('A').minZ() + 5;
        // start, then two pads one gap apart: the start and the second pad are only 5 apart
        List<P> pads = List.of(new P(x, z, 5, 5, 180, Palette.START),
                new P(x + 6, z + 1, 3, 3, 180, Palette.PATH_EASY),
                new P(x + 10, z + 1, 3, 3, 180, Palette.PATH_EASY),
                new P(x + 15, z, 5, 5, 180, Palette.FINISH));
        List<String> problems = ParkourValidator.problems(layout(EASY, "easy", 177.0, pads), "easy", 6);
        assertTrue(says(problems, "pads 0 and 2 are 5.00 apart: a player could skip"), "the skip: " + problems);
    }

    @Test
    void aPadUnderTheFallFloorIsRefused() {
        int x = MEDIUM.half('A').minX() + 5;
        int z = MEDIUM.half('A').minZ() + 5;
        // fall_depth 6 under a leg from 180 to 180 is 174: a pad at 175 is 1 over it
        List<P> pads = List.of(new P(x, z, 5, 5, 180, Palette.START),
                new P(x + 7, z + 1, 2, 2, 179, Palette.PATH_MEDIUM),
                new P(x + 12, z + 1, 2, 2, 178, Palette.PATH_MEDIUM),
                new P(x + 17, z + 1, 2, 2, 175, Palette.PATH_MEDIUM),
                new P(x + 21, z + 1, 3, 3, 180, Palette.CHECKPOINT),
                new P(x + 26, z, 5, 5, 180, Palette.FINISH));
        List<String> problems = ParkourValidator.problems(layout(MEDIUM, "medium", null, pads, 4), "medium", 6);
        assertTrue(says(problems, "pad 3 (top 175) is within 2 of the fall height 174.00"), "named: " + problems);
        List<String> deeper = ParkourValidator.problems(layout(MEDIUM, "medium", null, pads, 4), "medium", 8);
        assertFalse(says(deeper, "fall height"), "a deeper fall_depth leaves it room: " + deeper);
    }

    @Test
    void aBlockOutsideTheHalfIsRefused() throws GenFailed {
        Plan p = real(HARD, 8);
        Box half = p.half();
        Plan bad = with(p, half.maxX() + 1, 180, half.minZ() + 10, Palette.PATH_HARD);
        List<String> problems = ParkourValidator.problems(bad, "hard", 6);
        assertTrue(says(problems, "is outside the half"), "the stray block is named: " + problems);
    }

    @Test
    void aWrongColourIsRefused() throws GenFailed {
        Plan p = real(EASY, 9);
        Course.Mark f = ((PlannedTrial) p.course()).course().finish();
        Plan bad = with(p, (int) Math.floor(f.x()), (int) f.y() - 1, (int) Math.floor(f.z()), Palette.PATH_EASY);
        List<String> problems = ParkourValidator.problems(bad, "easy", 6);
        assertTrue(says(problems, "(finish) has minecraft:white_concrete"), "gold means finish: " + problems);
    }

    @Test
    void aBlockOffThePaletteIsRefused() throws GenFailed {
        Plan p = real(EASY, 10);
        Course.Spot s = ((PlannedTrial) p.course()).course().start();
        Plan bad = with(p, (int) Math.floor(s.x()) + 1, (int) s.y() - 1, (int) Math.floor(s.z()), "minecraft:sand");
        List<String> problems = ParkourValidator.problems(bad, "easy", 6);
        assertTrue(says(problems, "'minecraft:sand' isn't a Daily Courses block"), "sand falls: " + problems);
    }

    @Test
    void tooManyBlocksAreRefused() {
        Slots.Def slot = HARD;
        Box half = slot.half('A');
        List<BlockOp> ops = new ArrayList<>();
        for (int y = half.minY(); ops.size() <= ParkourValidator.MAX_OPS; y += 2) {
            for (int x = half.minX(); x <= half.maxX(); x++) {
                for (int z = half.minZ(); z <= half.maxZ(); z++) {
                    ops.add(new BlockOp(x, y, z, (short) 0));
                }
            }
        }
        Course c = new Course(slot.id(), TrialKind.PARKOUR, "Hard Parkour", Tier.HARD, "",
                new Course.Spot(half.minX() + 0.5, half.minY() + 1, half.minZ() + 0.5, 0, 0), List.of(),
                new Course.Mark(half.maxX() + 0.5, half.minY() + 1, half.maxZ() + 0.5, 3), null, 5, true, false, 1);
        Plan big = Plan.of(slot.id(), 1, 1, half, List.of(Palette.PATH_HARD), ops, List.of(), List.of(),
                new PlannedTrial(c, 20_000), List.of(), 0);
        assertTrue(says(ParkourValidator.problems(big, "hard", 6), "is more than 40000"), "a cap on the build");
    }

    @Test
    void theAirOverAPadMustReachTheSky() {
        Box half = EASY.half('A');
        int x = half.minX() + 10;
        int y = half.minY() + 20;
        int z = half.minZ() + 10;
        Map<Long, String> blocks = new HashMap<>();
        // a 1x1 pad in a sealed box of blocks: floor, four walls two high, roof
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                blocks.put(ParkourValidator.key(x + dx, y - 1, z + dz), "b");
                blocks.put(ParkourValidator.key(x + dx, y + 2, z + dz), "b");
                if (dx != 0 || dz != 0) {
                    blocks.put(ParkourValidator.key(x + dx, y, z + dz), "b");
                    blocks.put(ParkourValidator.key(x + dx, y + 1, z + dz), "b");
                }
            }
        }
        ParkourValidator.Pad pad = new ParkourValidator.Pad(x, z, x, z, y);
        ParkourValidator.Pad roof = new ParkourValidator.Pad(x - 1, z - 1, x + 1, z + 1, y + 3);
        assertFalse(ParkourValidator.openToSky(pad, List.of(pad, roof), blocks, half), "a sealed pocket is caught");
        blocks.remove(ParkourValidator.key(x + 1, y + 1, z));
        assertTrue(ParkourValidator.openToSky(pad, List.of(pad, roof), blocks, half), "one gap in the wall frees it");
        assertTrue(ParkourValidator.openToSky(pad, List.of(pad), blocks, half), "nothing overhead: open sky");
    }

    @Test
    void aSignStandingOnNothingIsRefused() throws GenFailed {
        Plan p = real(EASY, 12);
        List<SignText> signs = new ArrayList<>(p.signs());
        SignText s = signs.get(0);
        signs.set(0, new SignText(s.x(), s.y() + 3, s.z(), s.blockData(), GenCopy.parkourStart("easy")));
        Plan bad = new Plan(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), signs, p.keepClear(),
                p.course(), p.summary(), p.work(), p.hash());
        assertTrue(says(ParkourValidator.problems(bad, "easy", 6), "stands on nothing"), "signs stand on pads");
    }

    @Test
    void aTierOrCourseItCantCheckIsSaidPlainly() throws GenFailed {
        Plan p = real(EASY, 13);
        assertTrue(says(ParkourValidator.problems(p, "EEE", 6), "isn't a parkour tier"), "a golf mix");
        assertFalse(ParkourValidator.problems(p, "hard", 6).isEmpty(), "an easy layout isn't a hard one");
    }

    @Test
    void aCheckpointThatMissesTheCornersFeetCanStandOnIsRefused() throws GenFailed {
        Plan p = real(EASY, 4);
        Course c = ((PlannedTrial) p.course()).course();
        List<Course.Mark> small = new ArrayList<>();
        for (Course.Mark m : c.checkpoints()) {
            small.add(new Course.Mark(m.x(), m.y(), m.z(), 2.2)); // covers the block corners (2.12), not the feet
        }
        Course narrow = new Course(c.id(), c.kind(), c.name(), c.tier(), c.world(), c.start(), small, c.finish(),
                c.fallY(), c.minSeconds(), c.enabled(), c.pinned(), c.rev());
        Plan bad = Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), p.ops(), p.signs(), p.keepClear(),
                new PlannedTrial(narrow, ((PlannedTrial) p.course()).refMs()), p.summary(), p.work());
        assertTrue(says(ParkourValidator.problems(bad, "easy", 6), "doesn't cover its pad"),
                "a child who lands on a corner 2.4 from the middle and hops on would never be counted");
        assertEquals(List.of(), ParkourValidator.problems(p, "easy", 6), "the planner's own 2.6 covers it");
    }

    @Test
    void anEasyTurnWhosePadsASprintCouldJoinIsRefused() {
        // start, a pad, a checkpoint the path turns on, a pad 4.24 from the first one a step down, the finish
        List<P> pads = List.of(new P(4100, 4110, 5, 5, 172, Palette.START), new P(4107, 4111, 3, 3, 172,
                Palette.PATH_EASY), new P(4112, 4112, 3, 3, 172, Palette.CHECKPOINT), new P(4113, 4117, 3, 3, 171,
                Palette.PATH_EASY), new P(4112, 4122, 5, 5, 171, Palette.FINISH));
        List<String> problems = ParkourValidator.problems(layout(EASY, "easy", 160.0, pads, 2), "easy", 6);
        assertTrue(says(problems, "pads 1 and 3 are 4.24 apart: a player could skip"),
                "walking can't cross 4.24 a step down, but a sprint can, missing the checkpoint: " + problems);
    }
}
