package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.SyntheticMountain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Mountain Run v2-sized build on the lean index (MOUNTAIN-V2-SPEC §3.6, §13.6): some 350,000 blocks over
 * the 1,200 chunks of a 480 x 176 x 640 half, at the test pace of {@link GenKit#fast} (64 chunks read a
 * tick; every rule the same). It converges and verifies in two passes with one write a block and sign;
 * every block stands as planned and nothing else is in the half; and a heal of that half after damage
 * writes only the damaged blocks.
 */
class BuildJobMountainTest {

    private static final Box HALF = SyntheticMountain.HALF;
    private static final DailySettings.Budget FAST = GenKit.fast(GenKit.weekly()).budget();

    private long nanos;

    private BuildJob run(BuildJob job) {
        BuildBudget b = new BuildBudget(() -> nanos += 1_000);
        for (int i = 0; i < 100_000 && !job.done() && !job.failed(); i++) {
            b.begin(FAST, false, 10);
            job.tick(b, FAST.chunkLoadsInFlight(), List.of(), 1_000L + 50L * i);
            b.end();
        }
        return job;
    }

    @Test
    void aMountainRunSizedPlanIsBuiltAndVerifiedThenAHealWritesOnlyTheDamage() {
        Plan p = SyntheticMountain.plan(41);
        assertTrue(p.ops().size() > 300_000, "a Mountain Run v2-sized plan: " + p.ops().size() + " blocks");
        ChunkedWorld w = new ChunkedWorld("games");
        w.put(HALF.minX() + 3, HALF.minY() + 1, HALF.minZ() + 3, "minecraft:stone"); // last week's leftovers
        w.put(HALF.maxX() - 3, HALF.maxY(), HALF.maxZ() - 3, "minecraft:oak_planks");

        BuildJob build = run(new BuildJob(w, HALF, p, BuildJob.Mode.CONVERGE));
        assertTrue(build.done(), "the mountain is built: " + build.error());
        assertEquals(2, build.pass(), "the converge, then a verify pass that found nothing");
        assertEquals(HALF.chunkCount(), build.chunkCount(), "over all 1,200 chunks");
        assertEquals(p.ops().size() + p.signs().size() + 2, build.writes(),
                "one write a planned block and sign, and one for each leftover");
        for (BlockOp op : p.ops()) {
            if (!GenKit.FakeWorld.canonicalOf(p.blockOf(op)).equals(w.at(op.x(), op.y(), op.z()))) {
                throw new AssertionError("the block at " + op.x() + "," + op.y() + "," + op.z() + " is "
                        + w.at(op.x(), op.y(), op.z()) + ", not " + p.blockOf(op));
            }
        }
        for (SignText s : p.signs()) {
            assertEquals(BuildJob.pad(s.lines()), w.signLines(s.x(), s.y(), s.z()), "each sign's text stands");
        }
        assertEquals(p.ops().size() + p.signs().size(), w.count(HALF), "and nothing else is in the half");
        build.release();

        BlockOp a = p.ops().get(1_000);
        BlockOp b = p.ops().get(200_000);
        w.set(a.x(), a.y(), a.z(), WorldPort.AIR); // griefed away
        w.set(b.x(), b.y(), b.z(), "minecraft:tnt"); // swapped
        w.put(HALF.minX() + 200, HALF.maxY() - 1, HALF.minZ() + 300, "minecraft:dirt"); // added
        BuildJob heal = run(new BuildJob(w, HALF, p, BuildJob.Mode.CONVERGE));
        assertTrue(heal.done(), "the heal finished: " + heal.error());
        assertEquals(3, heal.writes(), "exactly the three damaged blocks were written, of " + p.ops().size());
        assertEquals(2, heal.pass(), "found in one pass, proven in the next");
        assertEquals(p.ops().size() + p.signs().size(), w.count(HALF), "and the half is the plan again");
        heal.release();
        assertTrue(w.tickets.isEmpty(), "every chunk ticket is released");
    }
}
