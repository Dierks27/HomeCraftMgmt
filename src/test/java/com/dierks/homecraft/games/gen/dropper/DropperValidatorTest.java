package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's proof (EVENTS-DROPPER-SPEC §B.1.6), rule by rule: a real plan passes, and one
 * hand-spoiled plan per rule fails with the reason an admin would need. Every spoiled plan starts
 * from a real one, so each test breaks exactly one thing.
 */
class DropperValidatorTest {

    /** One easy level, one medium level, and two levels, all real plans. */
    private static Plan easy() {
        return DropperFixtures.plan("E", 3);
    }

    private static Plan medium() {
        return DropperFixtures.plan("M", 3);
    }

    private static List<String> problems(Plan p, String mix) {
        return DropperValidator.problems(p, mix);
    }

    private static void assertFlags(List<String> problems, String words, String why) {
        assertTrue(problems.stream().anyMatch(s -> s.contains(words)), why + ": expected \"" + words + "\" in "
                + problems);
    }

    // ---- real plans pass -------------------------------------------------------------------------

    @Test
    void realPlansOfEveryTierPass() {
        for (String mix : List.of("E", "M", "H", "EM", "EEMMH", "HHHHH")) {
            Plan p = DropperFixtures.plan(mix, 3);
            assertEquals(List.of(), problems(p, mix), mix + ": the planner's own plan is proven");
            assertEquals(List.of(), DropperValidator.problems(p), mix + ": and so from the mix its pools name");
        }
    }

    @Test
    void theWrongMixIsFound() {
        List<String> two = problems(easy(), "EE");
        assertFlags(two, "the mix EE has 2", "a one-level plan for a two-level slot");
        List<String> hard = problems(medium(), "H");
        assertFlags(hard, "drops 40 to its water; a hard level drops 48", "a medium level isn't a hard one");
        assertFlags(hard, "a hard pool is 5", "nor is its pool");
        assertFlags(problems(easy(), "X"), "a dropper mix is", "a mix that isn't one");
        assertFlags(DropperValidator.problems(null, "E"), "no plan", "no plan at all");
    }

    // ---- rule 1: the witness ------------------------------------------------------------------------

    @Test
    void aBlockInThePathOpeningStopsTheWitness() {
        Plan p = medium();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.MEDIUM);
        DropCheck.Flight f = DropCheck.witness(DropperFixtures.world(p), v, DropperFixtures.witness(p, 0), 0);
        double[] at = f.crossings().get(0);
        Plan blocked = DropperFixtures.with(p, (int) Math.floor(at[0]), v.layerRows().get(0), (int) Math.floor(at[1]),
                DropBlocks.plate(0));
        List<String> out = problems(blocked, "M");
        assertFlags(out, "level 1's witness (", "the block the witness falls through is in its way");
        assertFlags(out, "touches the block at", "and it says which block");
    }

    @Test
    void aBlockInsideTheWitnessTubeButClearOfItsBodyIsABreach() {
        Plan p = easy();
        double r = DropRules.Level.EASY.tube();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        DropProgram witness = DropperFixtures.witness(p, 0);
        DropWorld w = DropperFixtures.world(p);
        int[] best = null;
        double bestClearance = -1;
        for (int row : v.layerRows()) {
            for (int x = v.x1(); x <= v.x2(); x++) {
                for (int z = v.z1(); z <= v.z2(); z++) {
                    if (w.get(x, row, z) != DropWorld.AIR) {
                        continue;
                    }
                    w.set(x, row, z, DropWorld.SOLID);
                    double d = clearance(w, v, witness, r);
                    w.set(x, row, z, DropWorld.AIR);
                    if (d > 0 && d < r && d > bestClearance) {
                        bestClearance = d;
                        best = new int[]{x, row, z};
                    }
                }
            }
        }
        assertNotNull(best, "some open block of a layer is inside the witness's tube but clear of its body");
        Plan breached = DropperFixtures.with(p, best[0], best[1], best[2], DropBlocks.plate(0));
        assertFlags(problems(breached, "E"), "touches the block at " + best[0] + "," + best[1] + "," + best[2],
                "a block " + bestClearance + " from the witness's body, a breach of " + (r - bestClearance)
                        + " into its clearance r = " + r);
    }

    /** How close the witness's body comes to a block: the smallest clearance at which it touches, or -1. */
    private static double clearance(DropWorld w, DropCheck.View v, DropProgram p, double r) {
        if (DropCheck.witness(w, v, p, 0).result().outcome() != DropRun.Outcome.SPLASH) {
            return -1; // the body itself hits it
        }
        if (DropCheck.witness(w, v, p, r).result().outcome() == DropRun.Outcome.SPLASH) {
            return r; // outside the tube altogether
        }
        double lo = 0;
        double hi = r;
        for (int i = 0; i < 20; i++) {
            double mid = (lo + hi) / 2;
            if (DropCheck.witness(w, v, p, mid).result().outcome() == DropRun.Outcome.SPLASH) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @Test
    void aMissingWitnessIsNoProof() {
        Plan p = DropperFixtures.withSummary(easy(), lines -> {
            lines.removeIf(l -> l.startsWith("level 1 witness"));
            return lines;
        });
        assertFlags(problems(p, "E"), "has no witness", "a plan must carry the proof of every level");
    }

    // ---- rule 5: openings and inputs ---------------------------------------------------------------

    @Test
    void tooManyInputChangesAreFound() {
        Plan p = easy();
        String text = DropperFixtures.witness(p, 0).encode();
        // the same path to the splash, then two more changes long after it: only the count differs
        String busy = text.substring(0, text.length() - 1) + "150 E1 -*";
        Plan spoiled = DropperFixtures.withSummary(p, lines -> {
            lines.replaceAll(l -> l.startsWith("level 1 witness: ") ? "level 1 witness: " + busy : l);
            return lines;
        });
        int changes = DropProgram.parse(busy).changes(DropperFixtures.view(p, 0, DropRules.Level.EASY).forward());
        assertTrue(changes > 1, "the spoiled program makes " + changes + " changes");
        List<String> out = problems(spoiled, "E");
        assertFlags(out, "changes its input " + changes + " times; at most 1", "Easy allows one change");
        assertFlags(out, "changes its input after layer 1", "and it must come before the first layer");
    }

    @Test
    void anOpeningUnderTheTiersMinimumIsFound() {
        // a block that closes part of layer 1's opening but none of the witness's tube: search a few real
        // plans (often the tube fills the whole 3 x 3 opening, and then there is no such block)
        List<String> found = null;
        for (int n = 3; n < 12 && found == null; n++) {
            Plan p = DropperFixtures.plan("M", n);
            DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.MEDIUM);
            DropCheck.Flight f = DropCheck.witness(DropperFixtures.world(p), v, DropperFixtures.witness(p, 0), 0);
            int row = v.layerRows().get(0);
            double[] at = f.crossings().get(0);
            for (int x = (int) at[0] - 2; x <= (int) at[0] + 2 && found == null; x++) {
                for (int z = (int) at[1] - 2; z <= (int) at[1] + 2 && found == null; z++) {
                    if (DropperFixtures.blockAt(p, x, row, z) != null) {
                        continue;
                    }
                    List<String> out = problems(DropperFixtures.with(p, x, row, z, DropBlocks.plate(0)), "M");
                    boolean narrowed = out.stream().anyMatch(s -> s.contains("by an opening of"));
                    boolean flew = out.stream().noneMatch(s -> s.startsWith("level 1's witness ("));
                    if (narrowed && flew) {
                        found = out;
                    }
                }
            }
        }
        assertNotNull(found, "some block narrows layer 1's opening under 3 while the witness still flies clear");
        assertFlags(found, "goes through layer 1 by an opening of 2", "the opening is measured round the witness");
        assertFlags(found, "at least 3", "against Medium's minimum");
    }

    // ---- rule 2: the pilots -------------------------------------------------------------------------

    @Test
    void aBlockOnlyTheSloppyLatePilotsHitIsFound() {
        Plan p = medium();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.MEDIUM);
        List<String> found = null;
        for (int row : v.layerRows()) {
            for (int x = v.x1(); x <= v.x2() && found == null; x++) {
                for (int z = v.z1(); z <= v.z2() && found == null; z++) {
                    if (DropperFixtures.blockAt(p, x, row, z) != null) {
                        continue;
                    }
                    List<String> out = problems(DropperFixtures.with(p, x, row, z, DropBlocks.plate(0)), "M");
                    boolean pilots = out.stream().anyMatch(s -> s.contains("a pilot ("));
                    boolean witnessFine = out.stream().noneMatch(s -> s.contains("witness"));
                    if (pilots && witnessFine) {
                        found = out;
                    }
                }
            }
            if (found != null) {
                break;
            }
        }
        assertNotNull(found, "some block leaves the witness its clearance but is in a real player's way");
        assertFlags(found, "level 1: a pilot (", "the plan fails on the pilot alone, named");
    }

    // ---- rule 3: enclosure ------------------------------------------------------------------------------

    @Test
    void aGapInTheWallsIsFound() {
        Plan p = easy();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        int gx = v.x1() - 1;
        int gy = v.ledgeTop() - 10;
        int gz = v.z1() + 5;
        Plan spoiled = DropperFixtures.without(p, op -> op.x() == gx && op.y() == gy && op.z() == gz);
        assertEquals(p.ops().size() - 1, spoiled.ops().size(), "one wall block taken out");
        assertFlags(problems(spoiled, "E"), "walls have 1 block missing", "a gap a body fits through");
    }

    @Test
    void aRoofIsFound() {
        Plan p = easy();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        List<int[]> roof = new ArrayList<>();
        for (int x = v.x1() - 1; x <= v.x2() + 1; x++) {
            for (int z = v.z1() - 1; z <= v.z2() + 1; z++) {
                roof.add(new int[]{x, v.ledgeTop() + DropperGeometry.WALL_ABOVE, z});
            }
        }
        assertFlags(problems(DropperFixtures.withAll(p, roof, DropBlocks.glass(0)), "E"), "a shaft has no roof",
                "the Games world's noon sky lights the shaft: there is no roof");
    }

    @Test
    void aJumpableLedgeIsFound() {
        Plan p = easy();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        int lift = 3;
        int row = v.ledgeTop() - 1;
        Plan lowered = DropperFixtures.without(p, op -> op.y() == row && op.x() >= v.ledge()[0]
                && op.x() <= v.ledge()[2] && op.z() >= v.ledge()[1] && op.z() <= v.ledge()[3]);
        List<int[]> raised = new ArrayList<>();
        for (int x = v.ledge()[0]; x <= v.ledge()[2]; x++) {
            for (int z = v.ledge()[1]; z <= v.ledge()[3]; z++) {
                raised.add(new int[]{x, row + lift, z});
            }
        }
        Plan high = DropperFixtures.withAll(lowered, raised, DropBlocks.LEDGE);
        Course c = DropperFixtures.course(p);
        Course.Spot s = c.start();
        high = DropperFixtures.withCourse(high, c.withStart(new Course.Spot(s.x(), s.y() + lift, s.z(), s.yaw(),
                s.pitch())));
        List<SignText> signs = new ArrayList<>();
        for (SignText t : p.signs()) {
            signs.add(new SignText(t.x(), t.y() + lift, t.z(), t.blockData(), t.lines()));
        }
        high = DropperFixtures.withSigns(high, signs);
        List<String> out = problems(high, "E");
        assertFlags(out, "4 above its ledge", "the walls end 1 above the ledge: a 1.25 jump clears them");
        assertFlags(out, "a player could get out of its shaft", "and the flood fill from the ledge leaks out");
    }

    @Test
    void aHoleInTheFloorIsFound() {
        Plan p = medium();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.MEDIUM);
        double[] box = DropMarks.poolBox(v.pool());
        int floor = v.surfaceY() - DropperGeometry.POOL_DEPTH - 1;
        int hx = (int) box[0] > v.x1() ? v.x1() : v.x2();
        Plan spoiled = DropperFixtures.without(p, op -> op.x() == hx && op.y() == floor && op.z() == v.z1());
        assertFlags(problems(spoiled, "M"), "floor has 1 hole", "the floor under the rim must be whole");
    }

    // ---- rule 4: sealed pools -----------------------------------------------------------------------

    /** A rim block right beside the level's pool, at the water's top row: {x, y, z}. */
    private static int[] besideThePool(Plan p, DropCheck.View v) {
        double[] box = DropMarks.poolBox(v.pool());
        int y = v.surfaceY() - 1;
        int zMid = (int) ((box[2] + box[5]) / 2);
        int xMid = (int) ((box[0] + box[3]) / 2);
        int[][] tries = {{(int) box[0] - 1, y, zMid}, {(int) box[3], y, zMid}, {xMid, y, (int) box[2] - 1},
                {xMid, y, (int) box[5]}};
        for (int[] t : tries) {
            if (DropBlocks.RIM_LAST.equals(DropperFixtures.blockAt(p, t[0], t[1], t[2]))
                    || DropBlocks.RIM.equals(DropperFixtures.blockAt(p, t[0], t[1], t[2]))) {
                return t;
            }
        }
        throw new AssertionError("a 7 x 7 pool in an 11 x 11 shaft has a rim on some side");
    }

    @Test
    void waterBesideAirIsFound() {
        Plan p = medium();
        int[] rim = besideThePool(p, DropperFixtures.view(p, 0, DropRules.Level.MEDIUM));
        Plan spoiled = DropperFixtures.without(p, op -> op.x() == rim[0] && op.y() == rim[1] && op.z() == rim[2]);
        assertFlags(problems(spoiled, "M"), "air beside or under it: pools must be sealed",
                "water with air beside it would flow");
    }

    @Test
    void waterOutsideAPoolBoxIsFound() {
        Plan p = medium();
        int[] rim = besideThePool(p, DropperFixtures.view(p, 0, DropRules.Level.MEDIUM));
        Plan spoiled = DropperFixtures.with(p, rim[0], rim[1], rim[2], DropBlocks.WATER);
        List<String> out = problems(spoiled, "M");
        assertFlags(out, "1 water block is outside every pool box",
                "sealed, but not where the course says the pool is");
        assertFalse(out.stream().anyMatch(s -> s.contains("must be sealed")), "it is still sealed: " + out);
    }

    @Test
    void aPoolBoxThatIsntAllWaterIsFound() {
        Plan p = medium();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.MEDIUM);
        double[] box = DropMarks.poolBox(v.pool());
        Plan spoiled = DropperFixtures.with(p, (int) box[0], v.surfaceY() - 1, (int) box[2], DropBlocks.RIM);
        assertFlags(problems(spoiled, "M"), "pool box has 1 block that isn't water",
                "a player standing there would count as a splash");
    }

    @Test
    void aPoolAtTheHalfsEdgeIsFound() {
        Plan p = easy();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        Box h = p.half();
        Plan spoiled = DropperFixtures.withHalf(p, new Box(v.x1(), h.minY(), h.minZ(), h.maxX(), h.maxY(), h.maxZ()));
        assertFlags(problems(spoiled, "E"), "level 1's pool isn't a block inside the half",
                "a pool must be at least a block inside, walls and all");
    }

    // ---- rule 6: the marks ------------------------------------------------------------------------------

    @Test
    void marksOutOfOrderAreFound() {
        Plan p = DropperFixtures.plan("EM", 3);
        Course c = DropperFixtures.course(p);
        Course swapped = c.withCheckpoints(List.of(c.checkpoints().get(1), c.checkpoints().get(0)));
        assertFlags(problems(DropperFixtures.withCourse(p, swapped), "EM"), "out of order",
                "a ledge where the first pool should be");
    }

    @Test
    void aFallLineAboveAPoolIsFound() {
        Plan p = easy();
        Course c = DropperFixtures.course(p);
        double floor = DropMarks.floorRow(DropMarks.poolOf(c, 0));
        assertFlags(problems(DropperFixtures.withCourse(p, c.withFallY(floor + 1)), "E"), "fall height",
                "a fall line over the pool floor would void a splash");
    }

    @Test
    void aMarkOffItsBlocksIsFound() {
        Plan p = easy();
        Course c = DropperFixtures.course(p);
        Course.Spot s = c.start();
        Course moved = c.withStart(new Course.Spot(s.x() + 6, s.y(), s.z() + 6, s.yaw(), s.pitch()));
        assertFlags(problems(DropperFixtures.withCourse(p, moved), "E"), "ledge mark isn't over its ledge",
                "the start must be on the lime ledge");
    }

    // ---- rule 7: inside the half, and not too big ------------------------------------------------

    @Test
    void anOpOutsideTheHalfIsFound() {
        Plan p = easy();
        Box h = p.half();
        assertFlags(problems(DropperFixtures.with(p, h.maxX() + 1, h.minY() + 5, h.minZ(), DropBlocks.plate(0)), "E"),
                "1 block is outside the half", "nothing may be built outside the half");
    }

    @Test
    void aBlockSetTwiceIsFound() {
        Plan p = easy();
        List<BlockOp> ops = new ArrayList<>(p.ops());
        ops.add(ops.get(0));
        Plan twice = DropperFixtures.rebuild(p, p.half(), p.palette(), ops, p.signs(), DropperFixtures.course(p),
                p.summary());
        assertFlags(problems(twice, "E"), "1 block is set twice", "two writes to one block");
    }

    @Test
    void moreThanTwentyThousandBlocksIsTooMany() {
        Plan p = DropperFixtures.plan("EEMMH", 3);
        Box h = p.half();
        List<int[]> filler = new ArrayList<>();
        for (int z : new int[]{h.minZ(), h.maxZ() - 1, h.maxZ()}) {
            for (int x = h.minX(); x <= h.maxX(); x++) {
                for (int y = h.minY(); y <= h.maxY(); y++) {
                    filler.add(new int[]{x, y, z});
                }
            }
        }
        Plan big = DropperFixtures.withAll(p, filler, "minecraft:glass");
        assertTrue(big.ops().size() > DropperValidator.MAX_OPS, "the spoiled plan has " + big.ops().size());
        assertFlags(problems(big, "EEMMH"), "a dropper places at most 20000", "the block budget");
    }

    // ---- rule 8: the palette ------------------------------------------------------------------------

    @Test
    void flowingWaterAndBlocksOffThePaletteAreRefused() {
        Plan p = medium();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.MEDIUM);
        double[] box = DropMarks.poolBox(v.pool());
        Plan flowing = DropperFixtures.with(p, (int) box[0] + 1, v.surfaceY() - 1, (int) box[2] + 1,
                "minecraft:water[level=1]");
        assertFlags(problems(flowing, "M"), "minecraft:water[level=1], which a dropper may not place",
                "only a still source");
        Plan lava = DropperFixtures.with(p, v.x1(), v.layerRows().get(0), v.z1(), "minecraft:lava");
        assertFlags(problems(lava, "M"), "minecraft:lava, which a dropper may not place", "no other fluid");
        Plan sand = DropperFixtures.with(p, v.x1(), v.layerRows().get(0), v.z1(), "minecraft:sand");
        assertFlags(problems(sand, "M"), "minecraft:sand", "nothing that falls");
        assertFalse(Palette.allowed(DropBlocks.WATER), "and water is still off the shared palette");
    }

    // ---- rule 9: signs ------------------------------------------------------------------------------

    @Test
    void aSignWithTheWrongWordsIsFound() {
        Plan p = easy();
        SignText s = p.signs().get(0);
        Plan wrong = DropperFixtures.withSigns(p, List.of(new SignText(s.x(), s.y(), s.z(), s.blockData(),
                List.of("LEVEL 2 of 2", "Step off and", "fall into the", "WATER!"))));
        assertFlags(problems(wrong, "E"), "no sign reading LEVEL 1 of 1", "the sign names this level");
    }

    @Test
    void aSignFacingAwayStandingOrMissingIsFound() {
        Plan p = easy();
        SignText s = p.signs().get(0);
        String away = s.blockData().contains("north") ? Palette.wallSign("south") : Palette.wallSign("north");
        Plan backwards = DropperFixtures.withSigns(p, List.of(new SignText(s.x(), s.y(), s.z(), away, s.lines())));
        assertFlags(problems(backwards, "E"), "no sign reading LEVEL 1 of 1", "a sign facing the wall can't be read");
        Plan standing = DropperFixtures.withSigns(p, List.of(new SignText(s.x(), s.y(), s.z(), Palette.sign(0),
                s.lines())));
        assertFlags(problems(standing, "E"), "isn't on a wall", "a standing sign in the shaft is in the way");
        assertFlags(problems(DropperFixtures.withSigns(p, List.of()), "E"), "one sign a level: 0 for 1",
                "every level has its sign");
        Box h = p.half();
        Plan outside = DropperFixtures.withSigns(p, List.of(s, new SignText(h.maxX() + 3, s.y(), s.z(),
                s.blockData(), s.lines())));
        assertFlags(problems(outside, "E"), "is outside the half", "a sign outside the half");
    }

    // ---- the level's shape --------------------------------------------------------------------------

    @Test
    void anObstacleTooCloseToTheWaterIsFound() {
        Plan p = easy();
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        Plan low = DropperFixtures.with(p, v.x1(), v.surfaceY() + 2, v.z1(), DropBlocks.plate(0));
        assertFlags(problems(low, "E"), "an obstacle 2 over its water; at least 6 clear",
                "the last metres over the splash are clear air");
    }
}
