package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.engine.OwnerServer.LoggingWorld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Emptying a half while leaving what isn't Fresh Courses' (RETIRE's converge): the {@code leave} test keeps
 * a block no plan may use exactly where it is, counts it and names it, and the verify pass passes with it
 * standing; every block of ours goes, water first when the half may hold some; without a {@code leave} test
 * a clear is what it always was (everything goes).
 */
class BuildJobLeaveTest {

    private static final Box HALF = Box.sized(4096, 160, 4096, 32, 16, 32);
    private static final DailySettings.Budget BUDGET = new DailySettings.Budget(500, 5000, 4, 4, 2, 40);

    private long nanos;

    private BuildJob run(BuildJob job) {
        BuildBudget b = new BuildBudget(() -> nanos += 1_000);
        for (int i = 0; i < 10_000 && !job.done() && !job.failed(); i++) {
            b.begin(BUDGET, false, 10);
            job.tick(b, BUDGET.chunkLoadsInFlight(), List.of(), 1_000L + 50L * i);
            b.end();
        }
        return job;
    }

    /** A pool with its rim and floor, a wall, a sign; and two blocks that are nobody's plan's. */
    private static LoggingWorld world() {
        LoggingWorld w = new LoggingWorld("games");
        for (int x = 4100; x <= 4106; x++) {
            for (int z = 4100; z <= 4106; z++) {
                boolean inside = x > 4100 && x < 4106 && z > 4100 && z < 4106;
                w.put(x, 162, z, Palette.SEA_LANTERN);
                w.put(x, 163, z, inside ? "minecraft:water[level=0]" : "minecraft:blue_stained_glass");
            }
        }
        for (int x = 4096; x <= 4127; x++) {
            w.put(x, 164, 4120, Palette.GOLF_WALL); // across two chunks
        }
        w.put(4110, 164, 4110, Palette.sign(4));
        w.put(4111, 170, 4111, "minecraft:bedrock");
        w.put(4125, 171, 4125, "minecraft:oak_planks");
        return w;
    }

    @Test
    void whatIsntFreshCoursesStaysAndIsNamedAndTheVerifyPassesWithItStanding() {
        LoggingWorld w = world();
        long ours = w.blocks.size() - 2;
        BuildJob job = run(new BuildJob(w, HALF, null, BuildJob.Mode.CONVERGE, true, OldAreas::foreign));
        assertTrue(job.done(), "done, not failed: " + job.error());
        assertEquals(2, w.blocks.size(), "only the two foreign blocks are left");
        assertEquals("minecraft:bedrock", w.at(4111, 170, 4111), "the bedrock stands");
        assertEquals("minecraft:oak_planks", w.at(4125, 171, 4125), "and the planks");
        assertEquals(ours, job.writes(), "every block of ours was taken away, once");
        assertEquals(2, job.left(), "the verify pass counted the two it left");
        assertEquals(List.of("4111,170,4111 minecraft:bedrock", "4125,171,4125 minecraft:oak_planks"), job.leftAt(),
                "and named them");
        assertEquals(2, job.pass(), "the converge, then a verify pass that found nothing of ours: foreign blocks"
                + " never fail it");
        assertEquals(1.0, job.progress(), "and it says it is all the way");
    }

    @Test
    void theWaterIsDrainedBeforeAnyOtherBlockGoes() {
        LoggingWorld w = world();
        run(new BuildJob(w, HALF, null, BuildJob.Mode.CONVERGE, true, OldAreas::foreign));
        boolean solid = false;
        for (LoggingWorld.Write x : w.log) {
            boolean water = x.was() != null && x.was().startsWith("minecraft:water");
            assertFalse(water && solid, "no water left once a solid block went: " + x);
            solid |= !water;
        }
    }

    @Test
    void withoutALeaveTestAClearTakesEverythingAsBefore() {
        LoggingWorld w = world();
        BuildJob job = run(new BuildJob(w, HALF, null, BuildJob.Mode.CONVERGE, true));
        assertTrue(job.done(), "done");
        assertTrue(w.blocks.isEmpty(), "everything went, the bedrock too: a clear of a half Fresh Courses owns");
        assertEquals(0, job.left(), "nothing left, nothing counted");
    }

    @Test
    void progressRunsFromLoadingToTheEnd() {
        LoggingWorld w = world();
        BuildJob job = new BuildJob(w, HALF, null, BuildJob.Mode.CONVERGE, false, OldAreas::foreign);
        assertEquals(0.0, job.progress(), "nothing loaded yet");
        BuildBudget b = new BuildBudget(() -> nanos += 1_000);
        double last = 0;
        for (int i = 0; i < 1_000 && !job.done(); i++) {
            b.begin(BUDGET, false, 10);
            job.tick(b, 1, List.of(), 1_000L + 50L * i);
            b.end();
            assertTrue(job.progress() >= last, "it never goes back: " + job.progress() + " after " + last);
            last = job.progress();
        }
        assertEquals(1.0, last, "and ends at 1");
    }
}
