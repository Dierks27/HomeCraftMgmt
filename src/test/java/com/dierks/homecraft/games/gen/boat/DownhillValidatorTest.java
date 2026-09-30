package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.RaceStand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dierks.homecraft.games.gen.boat.HandRun.wx;
import static com.dierks.homecraft.games.gen.boat.HandRun.wz;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run's proof (Course Variety §2.10, §4.1, §9): hand-made runs of every tier pass,
 * and every mutation the spec lists (and a few more, one per rule) is caught, each by the rule
 * meant to catch it. The runs come from {@link HandRun}; a mutation either builds a variant of one
 * (a bay, a carved obstacle, other drops) or edits single blocks, signs or marks of a built plan.
 */
class DownhillValidatorTest {

    private static boolean says(List<String> problems, String part) {
        for (String p : problems) {
            if (p.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void caught(Plan p, String tier, String part, String why) {
        List<String> problems = DownhillValidator.problems(p, tier);
        assertTrue(says(problems, part), why + ": expected '" + part + "' in " + problems);
    }

    private static void passes(Plan p, String tier, String why) {
        assertEquals(List.of(), DownhillValidator.problems(p, tier), why);
    }

    // ---- the hand-made runs ---------------------------------------------------------------------------

    @Test
    void aHandMadeMountainRunOfEveryTierPasses() {
        for (String tier : List.of("easy", "medium", "hard")) {
            HandRun run = HandRun.of(tier);
            Plan p = run.plan();
            passes(p, tier, tier + ": the hand-made run is proven");
            assertTrue(p.ops().size() > 5_000 && p.ops().size() <= DownhillValidator.MAX_OPS,
                    tier + ": a real-sized plan (" + p.ops().size() + " blocks) under the cap");
            Course c = HandRun.course(p);
            assertEquals(8, c.checkpoints().size(), tier + ": two checkpoints round each of the four drops");
            RaceGrid.Grid g = RaceGrid.plan(RaceGrid.path(c), c.start().y(), new PlanSurface(p), 12);
            assertEquals(12, g.size(), tier + ": the live grid seats twelve on the plan's own blocks");
            assertEquals(RaceGrid.Mode.DOUBLE, g.mode(), tier + ": in rows of two");
        }
    }

    @Test
    void theHandMadeRunsHaveThePiecesTheRulesAreAbout() {
        HandRun run = HandRun.medium();
        Plan p = run.plan();
        assertEquals(List.of(168, 167, 165, 164, 163), List.of(run.after(0), run.after(1), run.after(2), run.after(3),
                run.after(4)), "medium: drops of 1, 2, 1 and 1 from H0 + 8");
        boolean sand = false;
        boolean roof = false;
        boolean leaves = false;
        for (BlockOp op : p.ops()) {
            String b = Palette.id(p.blockOf(op));
            sand |= b.equals(Palette.SAND);
            roof |= b.equals(Palette.BLUE_GLASS);
            leaves |= Palette.isLeaves(b);
        }
        assertTrue(sand && roof && leaves, "sand (kerb, paddock), a cave roof and real leaves");
        assertEquals(HandRun.NONE, run.level(118, 92), "the split's island is no track");
        assertEquals(run.after(1), run.level(114, 92), "its west branch is");
        assertEquals(run.after(1), run.level(121, 92), "and its east");
    }

    @Test
    void roundedCornersAsAPlannerDrawsThemPassToo() {
        for (int round : new int[]{8, 12}) {
            for (String tier : List.of("easy", "medium", "hard")) {
                HandRun run = HandRun.of(tier);
                run.round = round;
                passes(run.plan(), tier, tier + ", corners of radius " + round
                        + ": arcs drawn on whole blocks leave no false gap, pocket or corner step");
            }
        }
    }

    @Test
    void aPlanOfTheWrongTierOrShapeIsRefusedPlainly() {
        Plan hard = HandRun.hard().plan();
        caught(hard, "easy", "blue ice on an easy track", "hard's blue ice judged as easy");
        caught(hard, "easy", "drops 2 blocks", "and its 2-block drops");
        caught(hard, "EEE", "isn't an Ice Boat tier", "a golf mix");
        assertFalse(DownhillValidator.problems(null, "easy").isEmpty(), "no plan is never a pass");
    }

    // ---- V1, W5: the palette --------------------------------------------------------------------------

    @Test
    void slimeAndWaterAreRefusedAnywhere() {
        Plan p = HandRun.easy().plan();
        caught(HandRun.with(p, wx(40), 162, wz(60), "minecraft:slime_block"), "easy", "slime",
                "W5: slime lifts a boat, even far from the track");
        caught(HandRun.with(p, wx(40), 162, wz(60), "minecraft:water[level=0]"), "easy", "stays dry",
                "W5: the Mountain Run has no water");
    }

    @Test
    void blocksThatArentTheMountainRunsAreRefused() {
        Plan p = HandRun.easy().plan();
        caught(HandRun.with(p, wx(40), 162, wz(60), "minecraft:red_wool"), "easy", "isn't an Ice Boat block",
                "a golf flag is a Fresh Courses block, not a boat one");
        caught(HandRun.with(p, wx(40), 162, wz(60), "minecraft:sand"), "easy", "isn't a Fresh Courses block",
                "sand falls");
        passes(HandRun.with(p, wx(40), 162, wz(60), Palette.TRACK_WALL), "easy",
                "wood away from the track is scenery (a cliff face), and allowed");
    }

    @Test
    void aLeafWithTheWrongDistanceOrThatWouldDecayIsRefused() {
        Plan p = HandRun.easy().plan();
        BlockOp leaf = null;
        for (BlockOp op : p.ops()) {
            if (Palette.isLeaves(p.blockOf(op))) {
                leaf = op;
                break;
            }
        }
        BlockOp l = leaf;
        String said = p.blockOf(l);
        int d = Integer.parseInt(Palette.states(said).get("distance"));
        Map<int[], String> wrong = new HashMap<>();
        wrong.put(new int[]{l.x(), l.y(), l.z()}, Palette.leaves("oak", d < 7 ? d + 1 : d - 1));
        caught(HandRun.edit(p, op -> false, wrong), "easy", "wrong distance",
                "a leaf the game would change churns the verify pass");
        Map<int[], String> decays = new HashMap<>();
        decays.put(new int[]{l.x(), l.y(), l.z()}, "minecraft:oak_leaves[distance=" + d
                + ",persistent=false,waterlogged=false]");
        caught(HandRun.edit(p, op -> false, decays), "easy", "would decay", "a leaf that isn't persistent");
    }

    @Test
    void aStrayBlockIsRefused() {
        Plan p = HandRun.easy().plan();
        caught(HandRun.with(p, wx(40), 162, wz(60), RaceStand.FLOOR), "easy", "a stray minecraft:white_concrete",
                "white concrete is the stand's alone");
        caught(HandRun.with(p, wx(40), 162, wz(60), Palette.GLASS), "easy", "a stray minecraft:glass",
                "glass away from any wall");
    }

    // ---- V2: floors -----------------------------------------------------------------------------------

    @Test
    void stackedFloorsAreRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int ice = run.after(1);
        caught(HandRun.with(p, wx(118), ice + 3, wz(60), Palette.TRACK), "easy", "two floors in one column",
                "ice 3 over the track is a second floor");
        caught(HandRun.with(p, wx(118), ice + 5, wz(60), Palette.TRACK), "easy", "two floors in one column",
                "and so is ice 5 over it");
    }

    // ---- V3: downhill only ------------------------------------------------------------------------------

    @Test
    void aRaisedDriveCellCantBeReached() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int ice = run.after(1);
        Map<int[], String> up = new HashMap<>();
        up.put(new int[]{wx(118), ice + 1, wz(60)}, Palette.TRACK);
        Plan raised = HandRun.edit(p, op -> op.x() == wx(118) && op.y() == ice && op.z() == wz(60), up);
        caught(raised, "easy", "can't be reached from the start", "a boat can't climb onto a raised block");
    }

    @Test
    void aThreeBlockLipIsRefused() {
        HandRun run = HandRun.medium();
        run.drops[1] = 3;
        caught(run.plan(), "medium", "drops 3 blocks", "V3, V8: a drop of 3 would break a boat");
    }

    @Test
    void twoBlockDropsAreForMediumAndHardOnly() {
        HandRun run = HandRun.easy();
        run.drops[1] = 2;
        caught(run.plan(), "easy", "easy drops at most 1", "easy's drops are all 1 block");
    }

    @Test
    void aCornerStepThatIsntSquareIsRefused() {
        HandRun run = HandRun.easy();
        run.plan();
        // one block beside the first landing, two under the lip it touches corner to corner
        run.extra.add(new int[]{118 + run.h + 1, 33, 118 + run.h + 1, 33, run.after(0) - 2});
        caught(run.plan(), "easy", "a corner step", "V3: a diagonal step with a third height between");
    }

    @Test
    void aDeadEndDeckIsRefused() {
        HandRun run = HandRun.easy();
        run.plan();
        run.extra.add(new int[]{107, 106, 113, 112, run.after(1) - 1});
        caught(run.plan(), "easy", "is a dead end", "a sunken bay a boat can drop into but never leave");
    }

    // ---- V4: containment --------------------------------------------------------------------------------

    @Test
    void aMissingWallBlockIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        caught(HandRun.without(p, wx(118 + run.h + 1), run.after(1) + 1, wz(60)), "easy", "isn't solid",
                "W1: a hole in the wall beside the track");
    }

    @Test
    void aFlightZoneWallOneTooLowIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int x = wx(118 + run.h + 1);
        int topY = run.after(0) + 2;
        assertTrue(p.ops().stream().anyMatch(op -> op.x() == x && op.y() == topY && op.z() == wz(40)),
                "the landing's wall reaches 2 over the lip's surface");
        caught(HandRun.without(p, x, topY, wz(40)), "easy", "too low beside the landing",
                "W2: a boat flying off the drop could clear a wall only 2 over the landing");
        assertFalse(says(DownhillValidator.problems(HandRun.without(p, x, topY, wz(40)), "easy"), "isn't solid"),
                "the lowered wall still stands 2 over its own track: only W2 catches it");
    }

    @Test
    void aMissingRiserIsRefused() {
        HandRun run = HandRun.medium();
        Plan p = run.plan();
        int riser = run.after(2) + 1;
        assertTrue(p.ops().stream().anyMatch(op -> op.x() == wx(96) && op.y() == riser && op.z() == wz(118)),
                "the 2-block drop has its riser");
        caught(HandRun.without(p, wx(96), riser, wz(118)), "medium", "no riser under the drop",
                "W3: a slot under the high ice a boat could wedge into");
    }

    @Test
    void anOpenEdgeIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int x = wx(118 + run.h + 1);
        caught(HandRun.edit(p, op -> op.x() == x && op.z() == wz(60), Map.of()), "easy", "an open edge",
                "W4: a whole wall column gone");
    }

    @Test
    void trackAtTheAreasEdgeIsRefused() {
        HandRun run = HandRun.easy();
        run.plan();
        run.extra.add(new int[]{0, 60, 5, 66, run.after(3)});
        caught(run.plan(), "easy", "runs to the edge of the area", "no wall can stand outside the half");
    }

    // ---- V5: widths ---------------------------------------------------------------------------------------

    @Test
    void sandAcrossTheWholeWidthIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int ice = run.after(1);
        Map<int[], String> sand = new HashMap<>();
        for (int x = 107; x <= 112; x++) {
            for (int z = 118 - run.h; z <= 118 + run.h; z++) {
                sand.put(new int[]{wx(x), ice, wz(z)}, Palette.SAND);
            }
        }
        caught(HandRun.edit(p, op -> false, sand), "easy", "the sand can't be avoided",
                "V5b: sand is never forced");
        passes(p, "easy", "while the 2-wide kerb leaves an ice line");
    }

    @Test
    void aPassageNarrowedToThreeOnMediumIsRefused() {
        HandRun run = HandRun.medium();
        run.carve.add(new int[]{100, 10 - run.hp, 102, 10});
        caught(run.plan(), "medium", "a gap of 3.0 between walls", "V5d: medium's narrowest passage is 4");
    }

    @Test
    void noPathPWideIsRefused() {
        HandRun run = HandRun.easy();
        run.carve.add(new int[]{100, 10 - run.hp, 102, 11});
        List<String> problems = DownhillValidator.problems(run.plan(), "easy");
        assertTrue(says(problems, "no path 5 wide runs from the start to the finish"),
                "V5a: a 3-wide passage on easy has no middle 2 from both walls: " + problems);
        assertTrue(says(problems, "a gap of 3.0"), "V5d says where: " + problems);
    }

    @Test
    void aNarrowWayRoundTheIslandIsRefused() {
        HandRun run = HandRun.easy();
        run.carve.add(new int[]{112, 88, 113, 92});
        caught(run.plan(), "easy", "a way round the island", "V5d: both branches of a split are wide");
    }

    @Test
    void aDeepSidePocketIsRefused() {
        HandRun run = HandRun.easy();
        run.plan();
        run.extra.add(new int[]{15, 60, 20, 61, run.after(3)});
        caught(run.plan(), "easy", "a side pocket", "V5c: every block of track is within 3 of its middle");
    }

    // ---- V6: checkpoints ------------------------------------------------------------------------------------

    @Test
    void aCheckpointInASplitOrAFlightZoneIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int ice = run.after(1);
        caught(HandRun.withCheckpoint(p, 2, HandRun.mark(114.5, 92.5, ice, run.r())), "easy", "is in a split",
                "a checkpoint in one branch is no cut, and not a single lane");
        caught(HandRun.withCheckpoint(p, 2, HandRun.mark(118.5, 42.5, ice, run.r())), "easy", "is in a flight zone",
                "a boat may still be flying there");
    }

    @Test
    void aCheckpointThatIsntACutIsRefused() {
        Plan p = HandRun.easy().plan();
        Course.Mark cp = HandRun.course(p).checkpoints().get(0);
        caught(HandRun.withCheckpoint(p, 0, new Course.Mark(cp.x(), cp.y(), cp.z(), 3)), "easy",
                "doesn't span the track", "a sphere of 3 across a 9-wide lane can be gone round");
    }

    @Test
    void aSphereReachingAnotherRingIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        List<String> problems = DownhillValidator.problems(
                HandRun.withCheckpoint(p, 0, HandRun.mark(80.5, 14.5, run.after(0), 15.5)), "easy");
        assertTrue(says(problems, "reaches another part of the track"),
                "the inner ring 16 away is within the sphere's radius and a block: " + problems);
        assertEquals(1, problems.size(), "and that is all that is wrong with it: " + problems);
    }

    @Test
    void checkpointsOnSandUnderARoofOrTooCloseToADropAreRefused() {
        HandRun easy = HandRun.easy();
        Plan p = easy.plan();
        caught(HandRun.withCheckpoint(p, 3, HandRun.mark(110.5, 118.5, easy.after(1), easy.r())), "easy",
                "is on sand", "a reset lands on ice");
        caught(HandRun.withCheckpoint(p, 4, HandRun.mark(25.5, 118.5, easy.after(2), easy.r())), "easy",
                "under a roof or trees", "not in the cave");
        HandRun hard = HandRun.hard();
        Plan q = hard.plan();
        caught(HandRun.withCheckpoint(q, 1, HandRun.mark(118.5, 30.5, hard.after(0), hard.r())), "hard",
                "from the drop", "3 before any drop's edge");
    }

    @Test
    void checkpointsOutOfOrderOverlappingOrAroundTheStartAreRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        Course c = HandRun.course(p);
        List<Course.Mark> swapped = new ArrayList<>(c.checkpoints());
        swapped.set(0, c.checkpoints().get(1));
        swapped.set(1, c.checkpoints().get(0));
        Course s = c.withCheckpoints(swapped);
        caught(HandRun.withCourse(p, s.withMinSeconds(DownhillValidator.minSeconds(s)), 60_000), "easy",
                "comes before", "checkpoints are met in the order stored");
        caught(HandRun.withCheckpoint(p, 0, HandRun.mark(118.5, 20.5, run.after(0), run.rPit())), "easy", "overlap",
                "two spheres on the same stretch");
        caught(HandRun.withCheckpoint(p, 0, HandRun.mark(44.5, 10.5, run.after(0), run.rPit())), "easy",
                "the start is inside checkpoint 1", "a checkpoint on the start is met before the race");
        caught(HandRun.withCheckpoint(p, 0, HandRun.mark(80.5, 10.5, run.after(0), 2)), "easy", "is under 3.0",
                "B-T5 needs radii of 3");
        caught(HandRun.withCheckpoint(p, 0, HandRun.mark(80.5, 10.5, run.after(0) + 1, run.rPit())), "easy",
                "isn't on the track's surface", "a checkpoint in the air");
    }

    @Test
    void twoDropsInOneLegOrALegOver60AreRefused() {
        Plan p = HandRun.easy().plan();
        Course c = HandRun.course(p);
        List<Course.Mark> fewer = new ArrayList<>(c.checkpoints());
        fewer.remove(3);
        fewer.remove(2);
        Course f = c.withCheckpoints(fewer);
        caught(HandRun.withCourse(p, f.withMinSeconds(DownhillValidator.minSeconds(f)), 60_000), "easy",
                "2 drops between checkpoint 2 and checkpoint 3", "a reset never skips back over two drops");
        List<Course.Mark> first = new ArrayList<>(c.checkpoints());
        first.remove(0);
        Course g = c.withCheckpoints(first);
        caught(HandRun.withCourse(p, g.withMinSeconds(DownhillValidator.minSeconds(g)), 60_000), "easy",
                "at most 60.0", "no leg longer than 60");
    }

    @Test
    void aSprintStoresItsCheckpointsOnce() {
        Plan p = HandRun.easy().plan();
        Course c = HandRun.course(p);
        List<Course.Mark> twice = new ArrayList<>(c.checkpoints());
        twice.addAll(c.checkpoints());
        Course t = c.withCheckpoints(twice);
        caught(HandRun.withCourse(p, t.withMinSeconds(DownhillValidator.minSeconds(t)), 60_000), "easy",
                "stored for 2 laps", "one lap, never two");
    }

    // ---- V7: headroom -----------------------------------------------------------------------------------

    @Test
    void aCanopyInTheHeadroomIsRefused() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        caught(HandRun.with(p, wx(118), run.after(1) + 3, wz(60), Palette.leaves("oak", 7)), "easy",
                "something in the headroom", "four blocks of air over the ice");
    }

    @Test
    void aCaveRoofIsExactlyFiveOverTheIce() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int roof = run.after(2) + 5;
        Map<int[], String> low = new HashMap<>();
        low.put(new int[]{wx(25), roof - 1, wz(118)}, Palette.BLUE_GLASS);
        caught(HandRun.edit(p, op -> op.x() == wx(25) && op.y() == roof && op.z() == wz(118), low), "easy",
                "something in the headroom", "a roof at 4 bumps heads");
        Map<int[], String> high = new HashMap<>();
        high.put(new int[]{wx(25), roof + 1, wz(118)}, Palette.BLUE_GLASS);
        caught(HandRun.edit(p, op -> op.x() == wx(25) && op.y() == roof && op.z() == wz(118), high), "easy",
                "isn't 5 over the ice", "a roof is exactly 5 up");
        caught(HandRun.with(p, wx(60), run.after(2) + 6, wz(118), Palette.GLASS), "easy", "a block over the track",
                "only roofs, trees and moss over the track");
    }

    @Test
    void nothingHangsOverALanding() {
        HandRun run = HandRun.medium();
        Plan p = run.plan();
        caught(HandRun.with(p, wx(80), run.after(2) + 5, wz(118), Palette.leaves("oak", 7)), "medium",
                "something over the landing", "up to 2 over the top of a 2-block drop stays clear");
    }

    @Test
    void sceneryKeepsItsDistance() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        int wall = 118 + run.h + 1;
        caught(HandRun.with(p, wx(wall), run.after(1) + 4, wz(110), Palette.MOSS), "easy", "too close to the track",
                "scenery beside the track is 5 over its ice");
        caught(HandRun.with(p, wx(1), 162, wz(60), Palette.MOSS), "easy", "of the area's edge",
                "scenery keeps 2 inside the half");
    }

    // ---- V8: falls ------------------------------------------------------------------------------------------

    @Test
    void theTiersDropsAndFallAreKept() {
        HandRun medium = HandRun.medium();
        medium.drops[0] = 2;
        medium.drops[2] = 2;
        caught(medium.plan(), "medium", "3 drops of 2 blocks; medium has at most 2", "medium has two big drops at most");
        HandRun easy = HandRun.easy();
        Plan p = easy.plan();
        Course c = HandRun.course(p);
        caught(HandRun.withCourse(p, c.withFallY((double) easy.after(4)), HandRun.refMs(p)), "easy",
                "isn't under the lowest ice", "the fall height is under every block of ice");
    }

    // ---- V9: the stand and the scenery cap -------------------------------------------------------------

    @Test
    void aTreeOverTheSceneryCapIsRefused() {
        Plan p = HandRun.easy().plan();
        caught(HandRun.with(p, wx(60), 173, wz(22), Palette.log("birch")), "easy", "at or over the stand's floor",
                "only the stand stands at its heights");
    }

    @Test
    void theStandElevenFromTheIceIsRefusedAndTwelvePasses() {
        HandRun twelve = HandRun.easy();
        twelve.plan();
        twelve.extra.add(new int[]{62, 39, 66, 49, twelve.after(4)});
        passes(twelve.plan(), "easy", "a bay 12 from the stand's platform is out of reach");
        HandRun eleven = HandRun.easy();
        eleven.plan();
        eleven.extra.add(new int[]{62, 39, 66, 50, eleven.after(4)});
        caught(eleven.plan(), "easy", "blocks from the ice; at least 12", "11 is within a boat's reach");
    }

    @Test
    void theStandMustBeWhole() {
        Plan p = HandRun.easy().plan();
        int cx = RaceStand.centreX(p.half());
        int cz = RaceStand.centreZ(p.half());
        caught(HandRun.without(p, cx + 3, 175, cz), "easy", "rail has a gap", "the loop's own stand rule");
        caught(HandRun.withBoxes(p, List.of(new Box(wx(20), 170, wz(8), wx(30), 174, wz(12)))), "easy",
                "keep-clear space", "every keep-clear box under the stand");
    }

    // ---- V10: the grid ---------------------------------------------------------------------------------------

    @Test
    void aGridShortOfTwelveIsRefused() {
        HandRun ok = HandRun.easy();
        ok.pitBack = 20;
        passes(ok.plan(), "easy", "a pit 21.5 behind the start still seats 6 rows of two");
        HandRun shorter = HandRun.easy();
        shorter.pitBack = 21;
        caught(shorter.plan(), "easy", "the starting grid seats 10", "one row fewer: Race Night's 12 don't fit");
    }

    // ---- V11: the finish -------------------------------------------------------------------------------------

    @Test
    void theFinishIsFarEnoughPastTheFinalDropAndNearTheStand() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        Course c = HandRun.course(p);
        int low = run.after(4);
        Course near = c.withFinish(HandRun.mark(80.5, 34.5, low, run.h + 2.0));
        caught(HandRun.withCourse(p, near.withMinSeconds(DownhillValidator.minSeconds(near)), 60_000), "easy",
                "from the Final Drop", "the finish is past the Final Drop's flight zone");
        Course far = c.withFinish(HandRun.mark(94.5, 44.5, low, run.h + 2.0));
        caught(HandRun.withCourse(p, far.withMinSeconds(DownhillValidator.minSeconds(far)), 60_000), "easy",
                "from the viewing stand", "finishers watch from the stand, 30 at most");
        Course early = c.withFinish(HandRun.mark(94.5, 66.5, low, run.h + 2.0));
        caught(HandRun.withCourse(p, early.withMinSeconds(DownhillValidator.minSeconds(early)), 60_000), "easy",
                "the run-out is at least 14.0 of ice", "14 blocks of ice before the paddock");
    }

    @Test
    void theRunOutIsFlatAndEndsInSand() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        Map<int[], String> ice = new HashMap<>();
        for (BlockOp op : p.ops()) {
            if (Palette.id(p.blockOf(op)).equals(Palette.SAND) && op.z() >= wz(78) && op.z() <= wz(83)) {
                ice.put(new int[]{op.x(), op.y(), op.z()}, Palette.TRACK);
            }
        }
        caught(HandRun.edit(p, op -> false, ice), "easy", "no sand paddock", "the run-out ends in sand");
        HandRun sunk = HandRun.easy();
        sunk.plan();
        sunk.extra.add(new int[]{94 - sunk.h, 78, 94 + sunk.h, 83, sunk.after(4) - 1});
        caught(sunk.plan(), "easy", "isn't on the lowest track", "the finish is on the lowest deck");
    }

    // ---- V12: times -----------------------------------------------------------------------------------------

    @Test
    void theShortestTimeIsTheSummedLegBoundExactly() {
        Plan p = HandRun.easy().plan();
        Course c = HandRun.course(p);
        int min = DownhillValidator.minSeconds(c);
        assertEquals(min, c.minSeconds(), "the hand-made run stores the formula's value");
        caught(HandRun.withCourse(p, c.withMinSeconds(min + 1), HandRun.refMs(p)), "easy", "its legs say",
                "one second too high could void a clean run");
        caught(HandRun.withCourse(p, c.withMinSeconds(min - 1), HandRun.refMs(p)), "easy", "its legs say",
                "and one too low isn't the formula either");
        caught(HandRun.withCourse(p, c, min * 1000L + 999), "easy", "under the shortest time and a second",
                "the reference time is at least a second over the shortest");
    }

    @Test
    void theShortestTimeFormulaIsFairPlaysLegBound() {
        Course.Spot start = new Course.Spot(0.5, 10, 0.5, 0f, 0f);
        List<Course.Mark> cps = List.of(new Course.Mark(0.5, 10, 100.5, 4), new Course.Mark(0.5, 8, 200.5, 4));
        Course c = new Course("x", com.dierks.homecraft.games.trial.TrialKind.BOAT, "x", null, "", start, cps,
                new Course.Mark(0.5, 8, 300.5, 5), null, null, true, false, 1);
        // legs: 100 - 1 - 4 = 95; sqrt(100^2 + 2^2) - 8 = 92.02; 100 - 9 = 91: 278.02 / 75 = 3.7
        assertEquals(3, DownhillValidator.minSeconds(c), "the summed leg bound over 75 blocks a second, rounded down");
    }

    // ---- V13: sizes and boxes ---------------------------------------------------------------------------------

    @Test
    void tooManyBlocksOrBoxesAreRefused() {
        Plan p = HandRun.easy().plan();
        Map<int[], String> fill = new HashMap<>();
        for (int x = 2; x <= 125; x++) {
            for (int z = 2; z <= 125; z++) {
                fill.put(new int[]{wx(x), 160, wz(z)}, Palette.MOSS);
                fill.put(new int[]{wx(x), 161, wz(z)}, Palette.MOSS);
            }
        }
        caught(HandRun.edit(p, op -> false, fill), "easy", "blocks is more than 30000", "the plan cap");
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i <= DownhillValidator.MAX_BOXES; i++) {
            boxes.add(new Box(wx(20), 169, wz(8), wx(21), 170, wz(9)));
        }
        caught(HandRun.withBoxes(p, boxes), "easy", "keep-clear boxes is more than 64", "the box cap");
        caught(HandRun.withBoxes(p, List.of(new Box(wx(-3), 169, wz(8), wx(2), 170, wz(9)))), "easy",
                "reaches outside the half", "every box inside the half");
        caught(HandRun.with(p, wx(0) - 1, 165, wz(60), Palette.MOSS), "easy", "outside the half",
                "every block inside the half");
    }

    // ---- signs ------------------------------------------------------------------------------------------------

    @Test
    void signsStandOnSomethingAndNeverOverTheTrack() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        List<SignText> over = new ArrayList<>(p.signs());
        over.add(new SignText(wx(80), run.after(0) + 3, wz(10), Palette.sign(0), List.of("HI")));
        caught(HandRun.withSigns(p, over), "easy", "a sign over the track", "a sign over the ice");
        List<SignText> floating = new ArrayList<>(p.signs());
        floating.add(new SignText(wx(40), 165, wz(60), Palette.sign(0), List.of("HI")));
        caught(HandRun.withSigns(p, floating), "easy", "nothing to stand or hang on", "a sign in the air");
    }

    @Test
    void theStartIsOnTheTrack() {
        HandRun run = HandRun.easy();
        Plan p = run.plan();
        Course c = HandRun.course(p);
        Course.Spot s = c.start();
        Course up = c.withStart(new Course.Spot(s.x(), s.y() + 1, s.z(), s.yaw(), s.pitch()));
        caught(HandRun.withCourse(p, up, HandRun.refMs(p)), "easy", "the start", "a start in the air");
    }

    @Test
    void theStartFacesDownTheTrack() {
        Plan p = HandRun.easy().plan();
        Course c = HandRun.course(p);
        Course.Spot s = c.start();
        Course back = c.withStart(new Course.Spot(s.x(), s.y(), s.z(), 90f, 0f));
        caught(HandRun.withCourse(p, back.withMinSeconds(DownhillValidator.minSeconds(back)), HandRun.refMs(p)),
                "easy", "the start doesn't face down the track", "a grid lined up the wrong way");
    }

    @Test
    void theCheckNeverThrows() {
        Plan p = HandRun.easy().plan();
        Course c = HandRun.course(p);
        Course nowhere = c.withFinish(new Course.Mark(1e9, 1e9, -1e9, 5));
        assertFalse(DownhillValidator.problems(HandRun.withCourse(p, nowhere, 60_000), "easy").isEmpty(),
                "a finish far outside the half is refused, never thrown");
    }
}
