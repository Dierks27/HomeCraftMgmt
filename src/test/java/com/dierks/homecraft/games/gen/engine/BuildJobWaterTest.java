package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.FakeWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Dropper's sealed pools, built (EVENTS-DROPPER-SPEC §B.1.9): water is written last and removed
 * first, in three stages over the WHOLE half each pass, so a pool is filled only once every wall
 * round it stands and drained before its walls go.
 *
 * <p>Pinned here: on a real Easy Dropper plan, every water block is written after every solid and
 * sign of the half; replacing one dropper with another drains the old water first and fills the new
 * last; clearing the half (CLEAR_OLD) drains every pool before any wall or floor goes; a crash
 * between the stages (or in the middle of one) is just the next converge, giving exactly a clean
 * build's world; and a missing water block heals like any other block, as does water where a wall
 * should be.
 *
 * <p>And only a half with water is staged (the WP-D review's #6): a plan with no water, or a clear of a
 * half with none, is written chunk by chunk as each is read (one chunk's writes held at a time), as
 * before the Dropper; water found in a chunk stages the pass from there, and a caller that knows the
 * half may hold a Dropper's pools stages it from its first chunk, so water the chunks' edge cuts off
 * still goes before the wall that holds it; and while a person holds up a drain, no wall goes.
 */
class BuildJobWaterTest {

    private static final Slots.Def DEF = Slots.EASY_DROPPER;
    private static final Box HALF = LegacyBoxes.half(DEF, 'A');
    private static final DailySettings.Budget BUDGET = new DailySettings.Budget(500, 5000, 4, 4, 2, 40);

    /** One write, in the order the world saw it. */
    record Write(long pos, String was, String now) {
        boolean fills() {
            return BuildJob.fluid(now);
        }

        boolean drains() {
            return BuildJob.fluid(was) && !BuildJob.fluid(now);
        }
    }

    /** A world that remembers the order of its writes, and of its chunk reads among them. */
    static final class LoggingWorld extends FakeWorld {
        final List<Write> log = new ArrayList<>();
        /** "read" for a chunk snapshot, "write" for a block, in order. */
        final List<String> events = new ArrayList<>();

        LoggingWorld() {
            super("games");
        }

        @Override
        public void set(int x, int y, int z, String state) {
            long p = GenKit.pos(x, y, z);
            String was = blocks.getOrDefault(p, AIR);
            super.set(x, y, z, state);
            log.add(new Write(p, was, state));
            events.add("write");
        }

        @Override
        public ChunkView snapshot(int cx, int cz) {
            events.add("read");
            return super.snapshot(cx, cz);
        }
    }

    private long nanos;
    private long now = 1_000_000L;

    private BuildJob run(BuildJob job) {
        BuildBudget b = new BuildBudget(() -> nanos += 1_000);
        for (int i = 0; i < 20_000 && !job.done() && !job.failed(); i++) {
            b.begin(BUDGET, false, 10);
            job.tick(b, BUDGET.chunkLoadsInFlight(), List.of(), now);
            b.end();
            now += 50;
        }
        return job;
    }

    private BuildJob build(FakeWorld w, Plan plan) {
        BuildJob job = run(new BuildJob(w, HALF, plan, BuildJob.Mode.CONVERGE));
        job.release();
        return job;
    }

    private static Plan plan(int n) {
        return plan(n, "EEE");
    }

    /** Easy Dropper's slot with another mix (the slot takes any 1-5 levels): its pools move with the mix. */
    private static Plan plan(int n, String mix) {
        long day = 20_000 + n;
        try {
            return new DropperPlanner().plan(new PlanInput(DEF, HALF, 'A', day, 0,
                    GenSeed.seed(0x5EC12E7L, day, DEF.id(), 0), mix, 6, 0, null));
        } catch (GenFailed e) {
            throw new AssertionError("an Easy Dropper plans: " + e.getMessage(), e);
        }
    }

    /** A position's y (GenKit#pos packs it in the low 12 bits). */
    private static int y(long pos) {
        return (int) ((pos << 52) >> 52);
    }

    /** A position's chunk, for "per chunk" orders. */
    private static long chunk(long pos) {
        int x = (int) (pos >> 38);
        int z = (int) ((pos << 26) >> 38);
        return ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
    }

    private static long water(Plan p) {
        return p.ops().stream().filter(op -> BuildJob.fluid(p.blockOf(op))).count();
    }

    private static int lastIndex(List<Write> log, java.util.function.Predicate<Write> which) {
        int at = -1;
        for (int i = 0; i < log.size(); i++) {
            if (which.test(log.get(i))) {
                at = i;
            }
        }
        return at;
    }

    private static int firstIndex(List<Write> log, java.util.function.Predicate<Write> which) {
        for (int i = 0; i < log.size(); i++) {
            if (which.test(log.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void waterIsWrittenOnlyAfterEverySolidOfTheHalf() {
        Plan p = plan(1);
        assertTrue(water(p) >= 3 * 121 * 3, "an Easy Dropper has three whole-floor pools, 3 deep: " + water(p));
        for (BlockOp op : p.ops()) {
            String b = p.blockOf(op);
            assertTrue(!BuildJob.fluid(b) || Palette.poolWater(b), "its only fluid is still pool water: " + b);
        }
        LoggingWorld w = new LoggingWorld();
        BuildJob job = build(w, p);
        assertTrue(job.done(), "the build finished: " + job.error());
        int firstWater = firstIndex(w.log, Write::fills);
        int lastSolid = lastIndex(w.log, x -> !x.fills());
        assertTrue(firstWater > lastSolid, "the first water (write " + firstWater + ") comes after the last solid (write "
                + lastSolid + ")");
        assertEquals(water(p), w.log.stream().filter(Write::fills).count(), "each pool block written once");
        for (int i = firstWater + 1; i < w.log.size(); i++) {
            assertTrue(y(w.log.get(i).pos()) >= y(w.log.get(i - 1).pos()),
                    "the pools fill from their floors up, across the whole half");
        }
        for (BlockOp op : p.ops()) {
            assertEquals(p.blockOf(op), w.at(op.x(), op.y(), op.z()), "the half is exactly the plan");
        }
    }

    @Test
    void anotherDropperOverThisOneDrainsTheOldWaterFirstAndFillsTheNewLast() {
        LoggingWorld w = new LoggingWorld();
        build(w, plan(1));
        w.log.clear();
        Plan next = plan(2, "HHH"); // smaller, deeper pools: most of the old water must go
        BuildJob job = build(w, next);
        assertTrue(job.done(), "the new dropper is built: " + job.error());
        int lastDrain = lastIndex(w.log, Write::drains);
        int firstBody = firstIndex(w.log, x -> !x.drains() && !x.fills());
        int lastBody = lastIndex(w.log, x -> !x.drains() && !x.fills());
        int firstFill = firstIndex(w.log, Write::fills);
        assertTrue(lastDrain >= 0, "some old water had to go (the pools moved)");
        assertTrue(lastDrain < firstBody, "every unwanted water block is gone before any solid is written");
        assertTrue(firstFill < 0 || lastBody < firstFill, "and the new water comes after every solid");
        for (BlockOp op : next.ops()) {
            assertEquals(next.blockOf(op), w.at(op.x(), op.y(), op.z()), "the half is exactly the new plan");
        }
    }

    @Test
    void clearingTheHalfDrainsEveryPoolBeforeAnyWallGoes() {
        LoggingWorld w = new LoggingWorld();
        Plan p = plan(3);
        build(w, p);
        w.log.clear();
        BuildJob clear = build(w, null);
        assertTrue(clear.done(), "CLEAR_OLD finished: " + clear.error());
        assertEquals(0, w.count(HALF), "the half is empty");
        int lastDrain = lastIndex(w.log, Write::drains);
        int firstWall = firstIndex(w.log, x -> !BuildJob.fluid(x.was()));
        assertEquals(water(p), w.log.stream().filter(Write::drains).count(), "every pool block was drained");
        assertTrue(lastDrain < firstWall, "all of it before the first wall or floor went (write " + lastDrain + " < "
                + firstWall + ")");
        java.util.Map<Long, Integer> lastY = new java.util.HashMap<>();
        for (int i = 0; i <= lastDrain; i++) {
            Write x = w.log.get(i);
            Integer before = lastY.put(chunk(x.pos()), y(x.pos()));
            assertTrue(before == null || y(x.pos()) <= before, "a pool empties from the top (chunk by chunk, as read)");
        }
    }

    @Test
    void aCrashBetweenTheStagesIsJustTheNextConverge() {
        Plan p = plan(4);
        FakeWorld clean = new FakeWorld("games");
        build(clean, p);
        long solids = p.ops().size() + p.signs().size() - water(p);

        for (long killAt : new long[]{solids, solids + 7, solids - 5}) {
            FakeWorld crashed = new FakeWorld("games");
            crashed.killAfter = killAt;
            BuildJob dying = new BuildJob(crashed, HALF, p, BuildJob.Mode.CONVERGE);
            assertThrows(IllegalStateException.class, () -> run(dying), "the server dies after " + killAt + " writes");
            crashed.tickets.clear();
            BuildJob rerun = build(crashed, p);
            assertTrue(rerun.done(), "the rerun finished: " + rerun.error());
            assertEquals(clean.copy(HALF), crashed.copy(HALF), "after dying at write " + killAt
                    + ", the rerun gives exactly the clean build's world");
        }

        FakeWorld draining = new FakeWorld("games");
        build(draining, p);
        draining.killAfter = 40; // in the middle of the drain
        BuildJob dying = new BuildJob(draining, HALF, null, BuildJob.Mode.CONVERGE);
        assertThrows(IllegalStateException.class, () -> run(dying), "the server dies while a pool drains");
        draining.tickets.clear();
        assertTrue(build(draining, null).done(), "the clear goes on after the restart");
        assertEquals(0, draining.count(HALF), "and ends with an empty half");
    }

    @Test
    void aMissingWaterBlockHealsAndSoDoesWaterWhereAWallShouldBe() {
        Plan p = plan(5);
        LoggingWorld w = new LoggingWorld();
        build(w, p);
        BlockOp pool = p.ops().stream().filter(op -> BuildJob.fluid(p.blockOf(op))).findFirst().orElseThrow();
        BlockOp wall = p.ops().stream().filter(op -> p.blockOf(op).contains("glass")).findFirst().orElseThrow();
        w.blocks.remove(GenKit.pos(pool.x(), pool.y(), pool.z()));
        w.put(wall.x(), wall.y(), wall.z(), "minecraft:water[level=0]");
        w.log.clear();

        BuildJob heal = build(w, p);
        assertTrue(heal.done(), "the heal finished: " + heal.error());
        assertEquals(p.blockOf(pool), w.at(pool.x(), pool.y(), pool.z()), "the missing water is back");
        assertEquals(p.blockOf(wall), w.at(wall.x(), wall.y(), wall.z()), "the wall is back");
        assertEquals(3, w.log.size(), "the stray water drained, the wall written, the pool filled: " + w.log.size());
        assertTrue(w.log.get(0).drains() && !w.log.get(1).fills() && w.log.get(2).fills(), "in that order");
        assertFalse(BuildJob.fluid(w.at(wall.x(), wall.y(), wall.z())), "no water where the wall is");
    }

    // ---- staged only when there is water (the WP-D review's #6) ----------------------------------------

    /** How many chunks {@code b} spans. */
    private static int chunks(Box b) {
        return ((b.maxX() >> 4) - (b.minX() >> 4) + 1) * ((b.maxZ() >> 4) - (b.minZ() >> 4) + 1);
    }

    @Test
    void aHalfWithNoWaterIsWrittenChunkByChunkAsItIsRead() {
        Slots.Def parkour = Slots.DAILY_PARKOUR_EASY;
        Box half = parkour.half('A');
        Plan p = GenKit.plan(parkour, half, 3, 1);
        LoggingWorld w = new LoggingWorld();
        BuildJob job = new BuildJob(w, half, p, BuildJob.Mode.CONVERGE);
        assertFalse(job.staged(), "a plan with no water isn't staged");
        run(job);
        job.release();
        assertTrue(job.done(), "built: " + job.error());
        int firstWrite = w.events.indexOf("write");
        long readsBefore = w.events.subList(0, firstWrite).stream().filter("read"::equals).count();
        assertTrue(readsBefore < chunks(half), "its first chunk's blocks are written before the other chunks are read ("
                + readsBefore + " of " + chunks(half) + " read): nothing but one chunk's writes is held");
        assertFalse(job.staged(), "and it never was");
        for (BlockOp op : p.ops()) {
            assertEquals(p.blockOf(op), w.at(op.x(), op.y(), op.z()), "the half is exactly the plan");
        }

        BuildJob clear = new BuildJob(w, half, null, BuildJob.Mode.CONVERGE);
        run(clear);
        assertTrue(clear.done() && w.count(half) == 0, "cleared: " + clear.error());
        assertFalse(clear.staged(), "clearing a half with no water isn't staged either");
    }

    @Test
    void aPlanWithWaterIsStagedOverTheWholeHalf() {
        Plan p = plan(6);
        LoggingWorld w = new LoggingWorld();
        BuildJob job = new BuildJob(w, HALF, p, BuildJob.Mode.CONVERGE);
        assertTrue(job.staged(), "a Dropper's plan is staged from the start");
        run(job);
        job.release();
        int firstWrite = w.events.indexOf("write");
        long readsBefore = w.events.subList(0, firstWrite).stream().filter("read"::equals).count();
        assertEquals(chunks(HALF), readsBefore, "every chunk is read before the first solid is written");
    }

    @Test
    void waterFoundInAChunkStagesTheRestAndTheCallerCanSayItFromTheStart() {
        // a wall in the first chunk read, and the water it holds in the next one (a pool the chunks' edge cuts)
        Box half = HALF;
        int y = half.minY() + 20;
        int edge = (half.minX() >> 4 << 4) + 16; // the first x of the second chunk
        LoggingWorld w = new LoggingWorld();
        w.put(edge - 1, y, half.minZ() + 5, "minecraft:blue_stained_glass");
        w.put(edge, y, half.minZ() + 5, "minecraft:water[level=0]");
        BuildJob found = run(new BuildJob(w, half, null, BuildJob.Mode.CONVERGE));
        assertTrue(found.done() && found.staged(), "water found in a chunk stages the pass from there on");
        assertEquals(0, w.count(half), "and the half is cleared");

        LoggingWorld told = new LoggingWorld();
        told.put(edge - 1, y, half.minZ() + 5, "minecraft:blue_stained_glass");
        told.put(edge, y, half.minZ() + 5, "minecraft:water[level=0]");
        BuildJob hinted = new BuildJob(told, half, null, BuildJob.Mode.CONVERGE, true);
        assertTrue(hinted.staged(), "a Dropper's slot or plot, cleared: staged from its first chunk");
        run(hinted);
        int drained = lastIndex(told.log, Write::drains);
        int wall = firstIndex(told.log, x -> !BuildJob.fluid(x.was()));
        assertTrue(drained >= 0 && drained < wall, "so the water in the second chunk goes before the wall in the first");
        assertEquals(0, told.count(half), "and the half is cleared");
    }

    @Test
    void theBodyWaitsWhileAPersonHoldsUpADrain() {
        Plan p = plan(7);
        LoggingWorld w = new LoggingWorld();
        build(w, p);
        w.log.clear();
        BlockOp water = p.ops().stream().filter(op -> BuildJob.fluid(p.blockOf(op))).findFirst().orElseThrow();
        Person swimmer = new Person(java.util.UUID.randomUUID(), "Sam", "games", water.x() + 0.5, water.y(),
                water.z() + 0.5, null, null);
        BuildJob clear = new BuildJob(w, HALF, null, BuildJob.Mode.CONVERGE, true);
        BuildBudget b = new BuildBudget(() -> nanos += 1_000);
        for (int i = 0; i < 400; i++) {
            b.begin(BUDGET, false, 10);
            clear.tick(b, BUDGET.chunkLoadsInFlight(), List.of(swimmer), now);
            b.end();
            now += 50;
        }
        assertFalse(clear.done(), "the drain next to the swimmer waits");
        assertTrue(w.log.stream().allMatch(Write::drains), "and no wall or floor goes meanwhile: only drains ("
                + w.log.stream().filter(x -> !x.drains()).count() + " other writes)");
        assertTrue(w.log.size() > 0, "(the other water did drain)");
        assertTrue(BuildJob.fluid(w.at(water.x(), water.y(), water.z())), "the held-up block is still water");
        run(clear);
        assertTrue(clear.done(), "once they are gone the clear finishes: " + clear.error());
        assertEquals(0, w.count(HALF), "the half is empty");
        int lastDrain = lastIndex(w.log, Write::drains);
        int firstWall = firstIndex(w.log, x -> !BuildJob.fluid(x.was()));
        assertTrue(lastDrain < firstWall, "every drain, the held-up one too, came before the first wall");
    }

    @Test
    void onlyWaterIsAFluid() {
        assertTrue(BuildJob.fluid("minecraft:water[level=0]"), "a source");
        assertTrue(BuildJob.fluid("minecraft:water[level=7]"), "flowing water too (it must be drained first)");
        assertFalse(BuildJob.fluid("minecraft:blue_stained_glass"), "glass isn't");
        assertFalse(BuildJob.fluid(WorldPort.AIR), "air isn't");
        assertFalse(BuildJob.fluid(null), "nothing isn't");
    }
}
