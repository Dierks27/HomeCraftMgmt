package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakeWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Make this half equal to the plan" on a map-backed world (GEN-SPEC §0.2 R3/R4, §3.3, §6 S1/S2/S5).
 *
 * <p>Pinned here: a build converges and verifies; building again writes nothing; a crash mid-write
 * followed by a rerun gives exactly the world a clean build gives; it is never "done" before a pass
 * finds nothing to change; nothing is written outside its half; a scan only counts; verify heals
 * damage (a missing block, a stray block, a changed sign); a write next to a person waits and then
 * names them to be moved; tickets are released; a snapshot that contradicts itself is read again.
 */
class BuildJobTest {

    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final Box HALF = LegacyBoxes.half(DEF, 'A');
    private static final DailySettings.Budget BUDGET = new DailySettings.Budget(500, 5000, 4, 4, 2, 40);

    private long nanos;
    private long now = 1_000_000L;

    /** Tick {@code job} until it ends (or {@code max} ticks), 50 ms of clock a tick. */
    private BuildJob run(BuildJob job, List<Person> people, int max) {
        BuildBudget b = new BuildBudget(() -> nanos += 1_000);
        for (int i = 0; i < max && !job.done() && !job.failed(); i++) {
            b.begin(BUDGET, false, 10);
            job.tick(b, BUDGET.chunkLoadsInFlight(), people, now);
            b.end();
            now += 50;
        }
        return job;
    }

    private BuildJob build(FakeWorld w, Plan plan) {
        return run(new BuildJob(w, HALF, plan, BuildJob.Mode.CONVERGE), List.of(), 10_000);
    }

    private static Plan plan(long seed) {
        return GenKit.plan(DEF, HALF, seed, 1);
    }

    @Test
    void aBuildConvergesThenABuildOfTheSameHalfWritesNothing() {
        FakeWorld w = new FakeWorld("games");
        Plan p = plan(7);
        BuildJob first = build(w, p);
        assertTrue(first.done(), "the build finished: " + first.error());
        assertEquals(p.ops().size() + p.signs().size(), first.writes(), "one write per planned block and sign");
        assertEquals(2, first.pass(), "the converge, then a verify pass that found nothing");
        for (BlockOp op : p.ops()) {
            assertEquals(p.blockOf(op), w.at(op.x(), op.y(), op.z()), "each planned block is in the world");
        }
        SignText s = p.signs().get(0);
        assertEquals(List.of("FINISH!", "", "", ""), w.signLines(s.x(), s.y(), s.z()), "the sign's text is written");

        long before = w.writes;
        BuildJob again = build(w, p);
        assertTrue(again.done(), "the second build finished");
        assertEquals(0, again.writes(), "a half that already matches its plan gets zero writes");
        assertEquals(before, w.writes, "the world saw no write at all");
        assertEquals(1, again.pass(), "one pass that found nothing is the proof");
        again.release();
        first.release();
        assertTrue(w.tickets.isEmpty(), "every chunk ticket is released at the end");
    }

    @Test
    void aKillMidApplyAndARerunGiveTheSameWorldAsACleanBuild() {
        Plan p = plan(3);
        FakeWorld clean = new FakeWorld("games");
        clean.put(HALF.minX() + 40, HALF.minY() + 20, HALF.minZ() + 40, "minecraft:stone"); // yesterday's leftovers
        build(clean, p);

        FakeWorld crashed = new FakeWorld("games");
        crashed.put(HALF.minX() + 40, HALF.minY() + 20, HALF.minZ() + 40, "minecraft:stone");
        crashed.killAfter = 12;
        BuildJob dying = new BuildJob(crashed, HALF, p, BuildJob.Mode.CONVERGE);
        assertThrows(IllegalStateException.class, () -> run(dying, List.of(), 10_000),
                "the server dies in the middle of the writes");
        assertTrue(crashed.writes > 0 && crashed.writes < p.ops().size(), "the half is partly built");
        crashed.tickets.clear(); // a restart drops every ticket

        BuildJob rerun = build(crashed, p);
        assertTrue(rerun.done(), "the rerun finished: " + rerun.error());
        assertEquals(clean.copy(HALF), crashed.copy(HALF), "the rerun converged on exactly the clean build's world");
    }

    @Test
    void itIsNeverDoneBeforeAPassFindsNothingToChange() {
        // A world that silently refuses one block: every pass finds it again.
        Plan p = plan(1);
        BlockOp stuck = p.ops().get(4);
        FakeWorld w = new FakeWorld("games") {
            @Override
            public void set(int x, int y, int z, String state) {
                if (x == stuck.x() && y == stuck.y() && z == stuck.z()) {
                    return;
                }
                super.set(x, y, z, state);
            }
        };
        BuildJob job = build(w, p);
        assertFalse(job.done(), "a half that doesn't match is never done");
        assertTrue(job.failed(), "it fails instead");
        assertEquals(BuildJob.MAX_PASSES, job.pass(), "after the converge and three rounds of verify-and-heal");
        String at = stuck.x() + "," + stuck.y() + "," + stuck.z();
        assertTrue(job.error().contains(at), "the failure names the block: " + job.error());
    }

    @Test
    void nothingIsEverWrittenOutsideTheHalf() {
        FakeWorld w = new FakeWorld("games");
        HalfWriter writer = new HalfWriter(w, HALF);
        assertThrows(IllegalStateException.class, () -> writer.set(HALF.maxX() + 1, HALF.minY(), HALF.minZ(),
                WorldPort.AIR), "one block past the half is refused");
        assertThrows(IllegalStateException.class, () -> writer.sign(HALF.minX(), HALF.maxY() + 1, HALF.minZ(),
                List.of("HI")), "a sign above the half too");
        assertEquals(0, w.writes, "and nothing reached the world");

        Plan inside = plan(2);
        List<BlockOp> ops = new ArrayList<>(inside.ops());
        ops.add(new BlockOp(HALF.minX() - 1, HALF.minY() + 10, HALF.minZ() + 5, (short) 0));
        Plan outside = Plan.of(inside.slot(), 1, 2, HALF, inside.palette(), ops, inside.signs(), List.of(),
                inside.course(), List.of(), 0);
        assertThrows(IllegalArgumentException.class, () -> new BuildJob(w, HALF, outside, BuildJob.Mode.CONVERGE),
                "a plan with a block outside its half never starts");
        w.put(HALF.maxX() + 1, HALF.minY() + 5, HALF.minZ(), "minecraft:stone");
        build(w, inside);
        assertEquals("minecraft:stone", w.at(HALF.maxX() + 1, HALF.minY() + 5, HALF.minZ()),
                "a block just outside the half is never cleared");
    }

    @Test
    void aScanOnlyCountsAndNeverWrites() {
        FakeWorld w = new FakeWorld("games");
        w.put(HALF.minX() + 5, HALF.minY() + 1, HALF.minZ() + 9, "minecraft:stone");
        w.put(HALF.minX() + 20, HALF.maxY(), HALF.minZ() + 30, "minecraft:oak_leaves");
        BuildJob scan = run(new BuildJob(w, HALF, null, BuildJob.Mode.SCAN), List.of(), 10_000);
        assertTrue(scan.done(), "the scan finished");
        assertEquals(2, scan.found(), "it found both foreign blocks");
        assertEquals((HALF.minX() + 5) + "," + (HALF.minY() + 1) + "," + (HALF.minZ() + 9), scan.firstFound().get(0),
                "and names the first");
        assertEquals(0, scan.writes(), "a scan never writes");
        assertEquals(0, w.writes, "the world saw no write");
    }

    @Test
    void verifyHealsInjectedDamage() {
        FakeWorld w = new FakeWorld("games");
        Plan p = plan(5);
        build(w, p);
        BlockOp pad = p.ops().get(0);
        w.blocks.remove(GenKit.pos(pad.x(), pad.y(), pad.z()));
        w.put(HALF.minX() + 30, HALF.minY() + 11, HALF.minZ() + 50, "minecraft:dirt");
        SignText s = p.signs().get(0);
        w.signs.put(GenKit.pos(s.x(), s.y(), s.z()), List.of("GRIEFED", "", "", ""));

        BuildJob heal = build(w, p);
        assertTrue(heal.done(), "the heal finished");
        assertEquals(3, heal.writes(), "exactly the three damaged blocks were written");
        assertEquals(p.blockOf(pad), w.at(pad.x(), pad.y(), pad.z()), "the missing pad block is back");
        assertEquals(null, w.at(HALF.minX() + 30, HALF.minY() + 11, HALF.minZ() + 50), "the stray block is gone");
        assertEquals(List.of("FINISH!", "", "", ""), w.signLines(s.x(), s.y(), s.z()), "the sign reads right again");
    }

    @Test
    void aWriteNextToAPersonWaitsThenNamesThemToBeMoved() {
        FakeWorld w = new FakeWorld("games");
        Plan p = plan(4);
        BlockOp op = p.ops().get(0);
        Person standing = new Person(UUID.randomUUID(), "Kid", "games", op.x() + 0.5, op.y() + 1, op.z() + 0.5,
                null, null);
        BuildJob job = new BuildJob(w, HALF, p, BuildJob.Mode.CONVERGE);
        run(job, List.of(standing), 40);
        assertFalse(job.done(), "the build waits for them");
        assertTrue(job.deferring(), "a write near them is waiting");
        assertEquals(null, w.at(op.x(), op.y(), op.z()), "no block was placed into a person");
        assertTrue(job.stuckPeople().isEmpty(), "they aren't moved before 30 seconds (2 s so far)");

        run(job, List.of(standing), (int) (BuildJob.STUCK_MS / 50) + 5);
        assertEquals(java.util.Set.of(standing.id()), job.stuckPeople(), "after 30 seconds they are named to be moved");
        assertTrue(job.stuckPeople().isEmpty(), "reading the names clears them");

        run(job, List.of(), 10_000);
        assertTrue(job.done(), "once they're gone the build finishes");
        assertEquals(p.blockOf(op), w.at(op.x(), op.y(), op.z()), "and the block is placed");
    }

    @Test
    void chunksLoadAFewAtATimeAndAFailedLoadFailsTheJob() {
        FakeWorld w = new FakeWorld("games");
        w.asyncLoads = true;
        BuildJob job = new BuildJob(w, HALF, plan(9), BuildJob.Mode.CONVERGE);
        run(job, List.of(), 3);
        assertEquals(BUDGET.chunkLoadsInFlight(), w.pendingLoads.size(),
                "no more chunks are asked for than chunk_loads_in_flight");
        assertEquals(BuildJob.Phase.LOAD, job.phase(), "it waits for them");
        while (!w.pendingLoads.isEmpty()) {
            w.finishLoads();
            run(job, List.of(), 1);
        }
        run(job, List.of(), 10_000);
        assertTrue(job.done(), "every chunk loaded and the half was built");
        assertEquals(HALF.chunkCount(), w.tickets.size(), "each chunk held a ticket while it built");
        job.release();
        assertTrue(w.tickets.isEmpty(), "and let go at the end");

        FakeWorld bad = new FakeWorld("games");
        bad.failLoads.add(FakeWorld.chunk(HALF.minX() >> 4, HALF.minZ() >> 4));
        BuildJob failing = run(new BuildJob(bad, HALF, plan(9), BuildJob.Mode.CONVERGE), List.of(), 100);
        assertTrue(failing.failed(), "a chunk that can't load fails the job");
        assertEquals(0, bad.writes, "before any write");
    }

    @Test
    void aSnapshotThatCallsAPlannedBlocksSectionEmptyIsNotTrustedAgain() {
        Plan p = plan(6);
        BlockOp op = p.ops().get(0);
        FakeWorld w = new FakeWorld("games") {
            @Override
            public ChunkView snapshot(int cx, int cz) {
                ChunkView real = super.snapshot(cx, cz);
                if (real == null || distrusted) {
                    return real;
                }
                return new ChunkView() {
                    @Override
                    public boolean sectionEmpty(int y) {
                        return true; // a port that answers wrongly
                    }

                    @Override
                    public boolean air(int x, int y, int z) {
                        return real.air(x, y, z);
                    }

                    @Override
                    public String block(int x, int y, int z) {
                        return real.block(x, y, z);
                    }
                };
            }
        };
        build(w, p);
        w.put(HALF.minX() + 50, op.y(), HALF.minZ() + 50, "minecraft:stone"); // same section as the pads
        BuildJob again = build(w, p);
        assertTrue(w.distrusted, "the contradiction was noticed");
        assertTrue(again.done(), "the build finished anyway");
        assertEquals(null, w.at(HALF.minX() + 50, op.y(), HALF.minZ() + 50),
                "and the stray block the wrong answer would have hidden was cleared");
    }

    @Test
    void aBlockThatIsNotABlockStopsTheJobBeforeItStarts() {
        Plan p = plan(8);
        Plan bogus = Plan.of(p.slot(), 1, 8, HALF, List.of("minecraft:bogus_block"), p.ops().subList(0, 1),
                List.of(), List.of(), p.course(), List.of(), 0);
        FakeWorld w = new FakeWorld("games");
        assertThrows(IllegalArgumentException.class, () -> new BuildJob(w, HALF, bogus, BuildJob.Mode.CONVERGE),
                "an unknown block is refused when the job is made");
        assertEquals(0, w.writes, "no write happened");
    }
}
