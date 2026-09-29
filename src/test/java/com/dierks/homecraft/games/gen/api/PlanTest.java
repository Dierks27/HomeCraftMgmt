package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plan and its hash (GEN-SPEC §4.0): the hash names the layout — the same blocks, signs and
 * course hash the same however the planner ordered its ops or numbered its palette, and any
 * change to a block, a sign or the course's geometry changes it — while the world, the name and
 * the switches, which aren't the layout, don't.
 */
class PlanTest {

    private static final Box HALF = Slots.DAILY_PARKOUR_EASY.half('A');

    private static Course course(double finishX) {
        return new Course("daily_parkour_easy", TrialKind.PARKOUR, "Easy Parkour", Tier.EASY, "games",
                new Course.Spot(4100.5, 170, 4100.5, -90f, 0f), List.of(new Course.Mark(4110.5, 171, 4100.5, 2.2)),
                new Course.Mark(finishX, 170, 4100.5, 3.0), 167.0, 5, true, false, 1);
    }

    private static List<BlockOp> ops(int n) {
        List<BlockOp> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BlockOp(4100 + i, 169, 4100, (short) (i % 2)));
        }
        return out;
    }

    private static Plan plan(List<String> palette, List<BlockOp> ops, List<SignText> signs, PlannedCourse c) {
        return Plan.of("daily_parkour_easy", 1, 42L, HALF, palette, ops, signs, List.of(), c, List.of("12 jumps"), 99);
    }

    private static final List<String> PALETTE = List.of(Palette.START, Palette.PATH_EASY);
    private static final List<SignText> SIGNS = List.of(new SignText(4099, 170, 4099, Palette.sign(4),
            GenCopy.parkourStart("easy")));

    @Test
    void theHashIsTwelveHexDigitsAndStable() {
        Plan p = plan(PALETTE, ops(20), SIGNS, new PlannedTrial(course(4120.5), 38_000));
        assertTrue(p.hash().matches("[0-9a-f]{12}"), "twelve hex digits: " + p.hash());
        assertEquals(p.hash(), plan(PALETTE, ops(20), SIGNS, new PlannedTrial(course(4120.5), 38_000)).hash(),
                "the same plan hashes the same every time");
        assertEquals(Plan.hash(PALETTE, ops(20), SIGNS, new PlannedTrial(course(4120.5), 38_000)), p.hash(),
                "Plan.of fills in Plan.hash");
    }

    @Test
    void theHashIgnoresOrderAndPaletteNumbering() {
        PlannedTrial c = new PlannedTrial(course(4120.5), 38_000);
        String base = Plan.hash(PALETTE, ops(20), SIGNS, c);
        List<BlockOp> shuffled = new ArrayList<>(ops(20));
        Collections.shuffle(shuffled, new java.util.Random(1));
        assertEquals(base, Plan.hash(PALETTE, shuffled, SIGNS, c), "the order ops were made in doesn't matter");
        List<String> swapped = List.of(Palette.PATH_EASY, Palette.START);
        List<BlockOp> renumbered = new ArrayList<>();
        for (BlockOp op : ops(20)) {
            renumbered.add(new BlockOp(op.x(), op.y(), op.z(), (short) (1 - op.state())));
        }
        assertEquals(base, Plan.hash(swapped, renumbered, SIGNS, c), "nor how the palette is numbered");
        Course elsewhere = course(4120.5).withWorld("games_daily").withName("Other").withEnabled(false).withRev(9);
        assertEquals(base, Plan.hash(PALETTE, ops(20), SIGNS, new PlannedTrial(elsewhere, 38_000)),
                "nor the world, name, switch or rev: they aren't the layout");
    }

    @Test
    void anyChangeToTheLayoutChangesTheHash() {
        PlannedTrial c = new PlannedTrial(course(4120.5), 38_000);
        String base = Plan.hash(PALETTE, ops(20), SIGNS, c);
        assertNotEquals(base, Plan.hash(PALETTE, ops(21), SIGNS, c), "one more block");
        List<BlockOp> moved = new ArrayList<>(ops(20));
        moved.set(3, new BlockOp(4103, 170, 4100, (short) 1));
        assertNotEquals(base, Plan.hash(PALETTE, moved, SIGNS, c), "one block moved up");
        assertNotEquals(base, Plan.hash(List.of(Palette.START, Palette.PATH_HARD), ops(20), SIGNS, c),
                "one kind of block changed");
        assertNotEquals(base, Plan.hash(PALETTE, ops(20), List.of(), c), "a sign gone");
        assertNotEquals(base, Plan.hash(PALETTE, ops(20), SIGNS, new PlannedTrial(course(4121.5), 38_000)),
                "the finish moved");
        assertNotEquals(base, Plan.hash(PALETTE, ops(20), SIGNS, new PlannedTrial(course(4120.5), 39_000)),
                "the reference time changed");
    }

    @Test
    void golfCoursesHashByTheirHoles() {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(4870.5, 164, 4100.5, 0f),
                new GolfCourse.Spot(4870, 162, 4112), 3, new GolfCourse.Spot(4866, 161, 4097),
                new GolfCourse.Spot(4874, 168, 4115));
        GolfCourse one = new GolfCourse("tiny_golf", "Tiny Golf", "games", true, 1, List.of(h));
        PlannedGolf a = new PlannedGolf(one, List.of(0), List.of(List.of(new Putt(0f, 5))), List.of(2), List.of(3));
        PlannedGolf b = new PlannedGolf(one.withHole(1, h.withPar(4)), List.of(0), List.of(), List.of(3), List.of(4));
        assertNotEquals(Plan.hash(PALETTE, ops(3), List.of(), a), Plan.hash(PALETTE, ops(3), List.of(), b),
                "a hole's par is part of the layout");
        PlannedGolf sameHoles = new PlannedGolf(one, List.of(5), List.of(), List.of(), List.of());
        assertEquals(Plan.hash(PALETTE, ops(3), List.of(), a), Plan.hash(PALETTE, ops(3), List.of(), sameHoles),
                "how the planner got there (attempts, witness) isn't");
    }

    @Test
    void aPlanKeepsItsPartsAndReadsItsBlocks() {
        Plan p = plan(PALETTE, ops(4), SIGNS, new PlannedTrial(course(4120.5), 38_000));
        assertEquals(Palette.PATH_EASY, p.blockOf(p.ops().get(1)), "an op's block is its palette entry");
        assertEquals(4, p.ops().size(), "every op is kept");
        assertThrows(UnsupportedOperationException.class, () -> p.ops().add(new BlockOp(0, 0, 0, (short) 0)),
                "a plan can't be changed after it is made");
        Plan empty = new Plan("x", 1, 0, HALF, null, null, null, null, null, null, 0, null);
        assertTrue(empty.ops().isEmpty() && empty.signs().isEmpty() && empty.hash().isEmpty(),
                "missing parts read as empty");
        assertThrows(IllegalArgumentException.class, () -> new BlockOp(0, 0, 0, (short) -1),
                "a palette index is never negative");
        assertThrows(IllegalArgumentException.class, () -> new PlannedTrial(null, 0), "a trial needs its course");
    }
}
