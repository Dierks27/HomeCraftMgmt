package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V3Fixtures;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.trial.Course;
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

/**
 * Sparse against dense (MOUNTAIN-V2-SPEC §15, "sparse vs dense parity on the v3 fixtures"): the
 * column-sparse engine of {@link MountainValidator}, run with the frozen algo-3 rules, says exactly
 * what {@link DownhillValidator}'s dense grid says, line for line, on the frozen algo-3 fixtures and
 * on hundreds of their mutations and the hand-made v3 runs' (a block taken out or put in anywhere,
 * a checkpoint moved or dropped, a time, a fall height, a sign or a box changed). So the port to
 * columns changed how the blocks are kept, never what the proof says.
 */
class MountainParityTest {

    private static void same(Plan p, String tier, String why) {
        List<String> dense = DownhillValidator.problems(p, tier);
        List<String> sparse = MountainValidator.v3(p, tier);
        assertEquals(dense, sparse, why + ": the sparse engine says what the dense check says");
    }

    @Test
    void theFrozenFixturesPassBothWays() {
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            same(f.plan(), f.tier(), f + " as made");
            assertEquals(List.of(), MountainValidator.v3(f.plan(), f.tier()), f + ": proven by the sparse engine too");
            for (String other : List.of("easy", "medium", "hard", "EEE")) {
                same(f.plan(), other, f + " judged as " + other);
            }
        }
    }

    @Test
    void everyBlockTakenOutOfAFixtureIsJudgedAlike() {
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            Plan p = f.plan();
            GenRandom r = new GenRandom(0xD15C0L ^ f.seed());
            for (int i = 0; i < 25; i++) {
                BlockOp op = p.ops().get(r.nextInt(p.ops().size()));
                same(HandRun.without(p, op.x(), op.y(), op.z()), f.tier(),
                        f + " without " + p.blockOf(op) + " at " + op.x() + " " + op.y() + " " + op.z());
            }
        }
    }

    @Test
    void everyBlockPutIntoAFixtureIsJudgedAlike() {
        List<String> kinds = List.of(Palette.TRACK, Palette.TRACK_FAST, Palette.SAND, Palette.TRACK_WALL, Palette.GLASS,
                Palette.MOSS, Palette.log("oak"), Palette.leaves("birch", 7), Palette.BLUE_GLASS, Palette.SEA_LANTERN,
                RaceStand.FLOOR, Palette.LIP_CAP, Palette.STONE, Palette.SNOW, Palette.GATE_LEFT, "minecraft:red_wool");
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            Plan p = f.plan();
            Box half = p.half();
            GenRandom r = new GenRandom(0xADD5L ^ f.seed());
            for (int i = 0; i < 25; i++) {
                int x = half.minX() + r.nextInt(half.sizeX());
                int y = half.minY() + r.nextInt(half.sizeY());
                int z = half.minZ() + r.nextInt(half.sizeZ());
                String b = kinds.get(r.nextInt(kinds.size()));
                same(HandRun.with(p, x, y, z, b), f.tier(), f + " with " + b + " at " + x + " " + y + " " + z);
            }
        }
    }

    @Test
    void movedDroppedAndRetimedCheckpointsAreJudgedAlike() {
        for (V3Fixtures.Fixture f : V3Fixtures.all()) {
            Plan p = f.plan();
            Course c = HandRun.course(p);
            for (int i = 0; i < c.checkpoints().size(); i++) {
                Course.Mark m = c.checkpoints().get(i);
                for (double[] d : new double[][]{{2, -3}, {-9, 6}}) {
                    same(HandRun.withCheckpoint(p, i, new Course.Mark(m.x() + d[0], m.y(), m.z() + d[1], m.radius())),
                            f.tier(), f + ": checkpoint " + (i + 1) + " moved by " + d[0] + ", " + d[1]);
                }
                same(HandRun.withCheckpoint(p, i, new Course.Mark(m.x(), m.y(), m.z(), m.radius() - 1)), f.tier(),
                        f + ": checkpoint " + (i + 1) + " a block narrower");
                same(HandRun.withCheckpoint(p, i, new Course.Mark(m.x(), m.y() - 1, m.z(), m.radius())), f.tier(),
                        f + ": checkpoint " + (i + 1) + " a block low");
                List<Course.Mark> fewer = new ArrayList<>(c.checkpoints());
                fewer.remove(i);
                Course g = c.withCheckpoints(fewer);
                same(HandRun.withCourse(p, g.withMinSeconds(DownhillValidator.minSeconds(g)), 60_000), f.tier(),
                        f + ": without checkpoint " + (i + 1));
            }
            same(HandRun.withCourse(p, c.withMinSeconds(c.minSeconds() + 1), HandRun.refMs(p)), f.tier(),
                    f + ": a second too slow a shortest time");
            same(HandRun.withCourse(p, c, 1), f.tier(), f + ": a reference time of 1 ms");
            same(HandRun.withCourse(p, c.withFallY(c.finish().y()), HandRun.refMs(p)), f.tier(), f + ": a fall height too high");
            Course.Spot s = c.start();
            same(HandRun.withCourse(p, c.withStart(new Course.Spot(s.x(), s.y(), s.z(), s.yaw() + 180, 0)),
                    HandRun.refMs(p)), f.tier(), f + ": a start facing back");
            Course.Mark fin = c.finish();
            same(HandRun.withCourse(p, c.withFinish(new Course.Mark(fin.x() + 4, fin.y(), fin.z() + 4, fin.radius())),
                    HandRun.refMs(p)), f.tier(), f + ": a finish moved");
            List<SignText> signs = new ArrayList<>(p.signs());
            signs.add(new SignText((int) Math.floor(s.x()), (int) s.y() + 2, (int) Math.floor(s.z()), Palette.sign(0),
                    List.of("HI")));
            same(HandRun.withSigns(p, signs), f.tier(), f + ": a sign over the start");
            same(HandRun.withBoxes(p, List.of(p.half().expand(1))), f.tier(), f + ": a box over the half");
        }
    }

    @Test
    void theHandMadeRunsAndEveryMutationTheDenseTestsMakeAreJudgedAlike() {
        for (String tier : List.of("easy", "medium", "hard")) {
            same(HandRun.of(tier).plan(), tier, tier + " hand-made");
            for (int round : new int[]{8, 12}) {
                HandRun run = HandRun.of(tier);
                run.round = round;
                same(run.plan(), tier, tier + " rounded " + round);
            }
        }
        HandRun easy = HandRun.easy();
        Plan p = easy.plan();
        same(HandRun.with(p, wx(40), 162, wz(60), "minecraft:slime_block"), "easy", "slime");
        same(HandRun.with(p, wx(40), 162, wz(60), "minecraft:water[level=0]"), "easy", "water");
        same(HandRun.with(p, wx(40), 162, wz(60), "minecraft:sand"), "easy", "falling sand");
        same(HandRun.with(p, wx(118), easy.after(1) + 3, wz(60), Palette.TRACK), "easy", "a second floor");
        same(HandRun.without(p, wx(118 + easy.h + 1), easy.after(1) + 1, wz(60)), "easy", "a wall hole");
        same(HandRun.without(p, wx(118 + easy.h + 1), easy.after(0) + 2, wz(40)), "easy", "a low zone wall");
        same(HandRun.edit(p, op -> op.x() == wx(118 + easy.h + 1) && op.z() == wz(60), Map.of()), "easy", "an open edge");
        same(HandRun.with(p, wx(60), 173, wz(22), Palette.log("birch")), "easy", "over the cap");
        same(HandRun.with(p, wx(0) - 1, 165, wz(60), Palette.MOSS), "easy", "outside the half");
        same(HandRun.with(p, wx(1), 162, wz(60), Palette.MOSS), "easy", "scenery at the edge");
        Map<int[], String> up = new HashMap<>();
        up.put(new int[]{wx(118), easy.after(1) + 1, wz(60)}, Palette.TRACK);
        same(HandRun.edit(p, op -> op.x() == wx(118) && op.y() == easy.after(1) && op.z() == wz(60), up), "easy",
                "a raised drive cell");
        List<BlockOp> doubled = new ArrayList<>(p.ops());
        doubled.add(p.ops().get(p.ops().size() / 3));
        same(Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), doubled, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work()), "easy", "two blocks in one place");
        List<BlockOp> unknown = new ArrayList<>(p.ops());
        unknown.add(new BlockOp(wx(40), 162, wz(60), (short) 999));
        same(Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), unknown, p.signs(), p.keepClear(), p.course(),
                p.summary(), p.work()), "easy", "a block with no palette entry");

        List<HandRun> variants = new ArrayList<>();
        HandRun three = HandRun.medium();
        three.drops[1] = 3;
        variants.add(three);
        HandRun twoOnEasy = HandRun.easy();
        twoOnEasy.drops[1] = 2;
        variants.add(twoOnEasy);
        HandRun corner = HandRun.easy();
        corner.plan();
        corner.extra.add(new int[]{118 + corner.h + 1, 33, 118 + corner.h + 1, 33, corner.after(0) - 2});
        variants.add(corner);
        HandRun dead = HandRun.easy();
        dead.plan();
        dead.extra.add(new int[]{107, 106, 113, 112, dead.after(1) - 1});
        variants.add(dead);
        HandRun chained = HandRun.easy();
        chained.plan();
        chained.extra.add(new int[]{108, 40, 118 - chained.h - 1, 46, chained.after(1) - 1});
        variants.add(chained);
        HandRun edge = HandRun.easy();
        edge.plan();
        edge.extra.add(new int[]{0, 60, 5, 66, edge.after(3)});
        variants.add(edge);
        HandRun narrow = HandRun.medium();
        narrow.carve.add(new int[]{100, 10 - narrow.hp, 102, 10});
        variants.add(narrow);
        HandRun noPath = HandRun.easy();
        noPath.carve.add(new int[]{100, 10 - noPath.hp, 102, 11});
        variants.add(noPath);
        HandRun islandWay = HandRun.easy();
        islandWay.carve.add(new int[]{112, 88, 113, 92});
        variants.add(islandWay);
        HandRun pocket = HandRun.easy();
        pocket.plan();
        pocket.extra.add(new int[]{15, 60, 20, 61, pocket.after(3)});
        variants.add(pocket);
        HandRun eleven = HandRun.easy();
        eleven.plan();
        eleven.extra.add(new int[]{62, 39, 66, 50, eleven.after(4)});
        variants.add(eleven);
        HandRun shortPit = HandRun.easy();
        shortPit.pitBack = 21;
        variants.add(shortPit);
        HandRun sunk = HandRun.easy();
        sunk.plan();
        sunk.extra.add(new int[]{94 - sunk.h, 78, 94 + sunk.h, 83, sunk.after(4) - 1});
        variants.add(sunk);
        HandRun bigs = HandRun.medium();
        bigs.drops[0] = 2;
        bigs.drops[2] = 2;
        variants.add(bigs);
        for (int i = 0; i < variants.size(); i++) {
            HandRun v = variants.get(i);
            same(v.plan(), v.tier.id(), "hand-made variant " + i);
        }
        HandRun medium = HandRun.medium();
        Plan m = medium.plan();
        same(HandRun.without(m, wx(96), medium.after(2) + 1, wz(118)), "medium", "no riser");
        same(HandRun.with(m, wx(80), medium.after(2) + 5, wz(118), Palette.leaves("oak", 7)), "medium",
                "leaves over a landing");
        same(HandRun.withCheckpoint(p, 2, HandRun.mark(114.5, 92.5, easy.after(1), easy.r())), "easy",
                "a checkpoint in a split");
        same(HandRun.withCheckpoint(p, 0, HandRun.mark(80.5, 14.5, easy.after(0), 15.5)), "easy",
                "a sphere reaching another ring");
        same(HandRun.withCheckpoint(p, 3, HandRun.mark(110.5, 120.5, easy.after(1), easy.r())), "easy",
                "a checkpoint on sand");
        same(HandRun.withCheckpoint(p, 4, HandRun.mark(25.5, 118.5, easy.after(2), easy.r())), "easy",
                "a checkpoint in the cave");
        same(HandRun.withCheckpoint(p, 0, HandRun.mark(64.5, 10.5, easy.after(0), easy.rPit())), "easy",
                "a leg 64 along the track");
        same(HandRun.withCheckpoint(p, 0, HandRun.mark(44.5, 10.5, easy.after(0), easy.rPit())), "easy",
                "a checkpoint on the start");
        assertFalse(MountainValidator.v3(HandRun.easy().plan(), "easy").size() > 0, "the hand-made run passes sparse");
    }
}
