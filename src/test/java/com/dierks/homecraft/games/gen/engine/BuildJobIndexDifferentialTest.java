package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lean index changes nothing a build does (MOUNTAIN-V2-SPEC §13.6): {@link BuildJob} and the object-index
 * job it replaced ({@link BuildJobReference}, frozen) are run in lockstep on two copies of one world over
 * hundreds of random scenarios, and after every tick they must agree on everything a caller can see
 * (phase, pass, stage, staging, progress, writes waiting, people in the way, differences found and named,
 * blocks left and named, the error), and the worlds must have seen the same writes in the same order.
 *
 * <p>The scenarios: halves over negative and positive chunks, a few sections high; plans with water,
 * signs, two blocks at one spot (the same block or another) and signs on a block's spot; worlds with
 * stray blocks, water, damaged blocks and wrong sign text; clears, scans, a {@code leave} test and a half
 * that may hold water; people standing in the way; tiny budgets; slow chunk loads; a world that refuses a
 * write (a failure naming the first five); a snapshot that lies about empty sections; a crash mid-write;
 * and plans the job must refuse, with the same exception.
 */
class BuildJobIndexDifferentialTest {

    private static final String[] SOLIDS = {"minecraft:white_concrete", "minecraft:packed_ice", "minecraft:moss_block",
            "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"};
    private static final String WATER = "minecraft:water[level=0]";
    private static final String[] JUNK = {"minecraft:stone", "minecraft:dirt", "minecraft:bedrock", WATER,
            "minecraft:white_concrete", "minecraft:oak_planks"};

    /** A world that remembers every write and sign, in order, and may refuse writes at some spots. */
    static final class Recording extends GenKit.FakeWorld {
        final List<String> log = new ArrayList<>();
        final Set<Long> refuse;
        final boolean lies;

        Recording(Set<Long> refuse, boolean lies) {
            super("games");
            this.refuse = refuse;
            this.lies = lies;
        }

        @Override
        public void set(int x, int y, int z, String state) {
            log.add("set " + x + "," + y + "," + z + " " + state);
            if (refuse.contains(GenKit.pos(x, y, z))) {
                return;
            }
            super.set(x, y, z, state);
        }

        @Override
        public void sign(int x, int y, int z, List<String> lines) {
            log.add("sign " + x + "," + y + "," + z + " " + lines);
            super.sign(x, y, z, lines);
        }

        @Override
        public ChunkView snapshot(int cx, int cz) {
            ChunkView real = super.snapshot(cx, cz);
            if (!lies || real == null || distrusted) {
                return real;
            }
            return new ChunkView() {
                @Override
                public boolean sectionEmpty(int y) {
                    return true; // a port that answers wrongly until it is told not to be trusted
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
    }

    /** One scenario: a half, a plan (or none), a world, people and the job's settings. */
    private record Scenario(Box half, Plan plan, BuildJob.Mode mode, boolean wet, Predicate<String> leave,
                            Map<Long, String> world, Map<Long, List<String>> signs, Set<Long> refuse, boolean lies,
                            boolean async, long killAfter, List<long[]> people, DailySettings.Budget budget) {
    }

    @Test
    void theLeanJobAndTheObjectIndexJobDoExactlyTheSameInEveryScenario() {
        SplittableRandom rnd = new SplittableRandom(20261002L);
        int failed = 0;
        int deferred = 0;
        int staged = 0;
        for (int i = 0; i < 400; i++) {
            Scenario sc = scenario(rnd, i);
            Outcome o = runBoth(sc, "scenario " + i);
            failed += o.failed ? 1 : 0;
            deferred += o.deferred ? 1 : 0;
            staged += o.staged ? 1 : 0;
        }
        assertTrue(failed >= 20, "the scenarios include failures (refused writes, crashes): " + failed);
        assertTrue(deferred >= 20, "and writes waiting for people: " + deferred);
        assertTrue(staged >= 40, "and water staged over the whole half: " + staged);
    }

    @Test
    void aPlanTheJobMustRefuseIsRefusedWithTheSameException() {
        Box half = Box.sized(-40, 60, -8, 40, 32, 24);
        List<String> palette = List.of("minecraft:white_concrete", "minecraft:stone");
        Plan good = plan(half, palette, List.of(op(-30, 70, 0, 0), op(-29, 70, 0, 1)), List.of());
        List<Plan> bad = List.of(
                plan(half, palette, List.of(op(-30, 70, 0, 0), op(5, 70, 0, 0), op(-41, 70, 0, 0)), List.of()),
                plan(half, palette, List.of(op(-30, 70, 0, 2), op(5, 70, 0, 0)), List.of()),
                plan(half, List.of("minecraft:bogus", "minecraft:stone"), List.of(op(5, 70, 0, 0)), List.of()),
                plan(half, palette, good.ops(), List.of(new SignText(-30, 99, 0, Palette.sign(0), List.of("A")))),
                plan(half, palette, List.of(op(-30, 59, 0, 0)), List.of(new SignText(-30, 70, 0, "minecraft:bogus",
                        List.of("A")))),
                plan(half, palette, good.ops(), List.of(new SignText(-30, 70, 0, "minecraft:bogus", List.of("A")),
                        new SignText(-30, 99, 0, Palette.sign(0), List.of("B")))));
        for (int i = 0; i < bad.size(); i++) {
            Plan p = bad.get(i);
            Throwable lean = thrown(() -> new BuildJob(new GenKit.FakeWorld("games"), half, p, BuildJob.Mode.CONVERGE));
            Throwable old = thrown(() -> new BuildJobReference(new GenKit.FakeWorld("games"), half, p,
                    BuildJobReference.Mode.CONVERGE));
            assertTrue(old != null, "bad plan " + i + " is refused by the object index");
            assertTrue(lean != null, "and by the lean one (" + i + ")");
            assertEquals(old.getClass(), lean.getClass(), "with the same kind of exception (" + i + ")");
            assertEquals(old.getMessage(), lean.getMessage(), "naming the same block (" + i + ")");
        }
        assertEquals(null, thrown(() -> new BuildJob(new GenKit.FakeWorld("games"), half, good,
                BuildJob.Mode.CONVERGE)), "while a good plan is taken");
    }

    @Test
    void theRealSlotsSmallPlansBuildTheSameToo() {
        // The engine's own little plans (GenKit's), in a shipped half, from empty and from yesterday's.
        Box half = LegacyBoxes.half(Slots.DAILY_PARKOUR_EASY, 'A');
        for (long seed = 1; seed <= 12; seed++) {
            Plan today = GenKit.plan(Slots.DAILY_PARKOUR_EASY, half, seed, 1);
            Plan yesterday = GenKit.plan(Slots.DAILY_PARKOUR_EASY, half, seed + 100, 1);
            Map<Long, String> world = new HashMap<>();
            for (BlockOp o : yesterday.ops()) {
                world.put(GenKit.pos(o.x(), o.y(), o.z()), GenKit.FakeWorld.canonicalOf(yesterday.blockOf(o)));
            }
            runBoth(new Scenario(half, today, BuildJob.Mode.CONVERGE, false, null, world, Map.of(), Set.of(), false,
                    false, -1, List.of(), new DailySettings.Budget(500, 5000, 4, 4, 2, 40)), "seed " + seed);
        }
    }

    // ---- running both ---------------------------------------------------------------------------------

    private record Outcome(boolean failed, boolean deferred, boolean staged) {
    }

    private static Outcome runBoth(Scenario sc, String what) {
        Recording wl = world(sc);
        Recording wr = world(sc);
        BuildJob lean = new BuildJob(wl, sc.half(), sc.plan(), sc.mode(), sc.wet(), sc.leave());
        BuildJobReference old = new BuildJobReference(wr, sc.half(), sc.plan(),
                BuildJobReference.Mode.valueOf(sc.mode().name()), sc.wet(), sc.leave());
        BuildBudget bl = new BuildBudget(new Clock());
        BuildBudget br = new BuildBudget(new Clock());
        long now = 1_000_000L;
        boolean deferred = false;
        boolean staged = false;
        for (int t = 0; t < 20_000 && !(lean.done() || lean.failed()) ; t++) {
            List<Person> people = people(sc, t);
            String at = what + ", tick " + t;
            bl.begin(sc.budget(), !people.isEmpty(), 10);
            br.begin(sc.budget(), !people.isEmpty(), 10);
            RuntimeException el = null;
            RuntimeException er = null;
            try {
                lean.tick(bl, sc.budget().chunkLoadsInFlight(), people, now);
            } catch (RuntimeException e) {
                el = e;
            }
            try {
                old.tick(br, sc.budget().chunkLoadsInFlight(), people, now);
            } catch (RuntimeException e) {
                er = e;
            }
            bl.end();
            br.end();
            assertEquals(er == null ? null : er.getClass(), el == null ? null : el.getClass(), at + ": the same crash");
            assertEquals(er == null ? null : er.getMessage(), el == null ? null : el.getMessage(), at + ": its words");
            assertEquals(wr.log, wl.log, at + ": the same writes in the same order");
            if (el != null) {
                return new Outcome(true, deferred, staged);
            }
            same(lean, old, at);
            deferred |= lean.deferring();
            staged |= lean.staged();
            if (sc.async()) {
                wl.finishLoads();
                wr.finishLoads();
            }
            now += 50;
        }
        assertTrue(lean.done() || lean.failed(), what + ": it ended");
        assertEquals(wr.blocks, wl.blocks, what + ": the same world");
        assertEquals(wr.signs, wl.signs, what + ": the same signs");
        lean.release();
        old.release();
        assertEquals(wr.tickets, wl.tickets, what + ": the same tickets held");
        return new Outcome(lean.failed(), deferred, staged);
    }

    private static void same(BuildJob lean, BuildJobReference old, String at) {
        assertEquals(old.phase().name(), lean.phase().name(), at + ": phase");
        assertEquals(old.pass(), lean.pass(), at + ": pass");
        assertEquals(old.stage().name(), lean.stage().name(), at + ": write stage");
        assertEquals(old.staged(), lean.staged(), at + ": staged");
        assertEquals(old.progress(), lean.progress(), at + ": progress");
        assertEquals(old.waiting(), lean.waiting(), at + ": writes waiting");
        assertEquals(old.deferring(), lean.deferring(), at + ": waiting for a person");
        assertEquals(old.stuckPeople(), lean.stuckPeople(), at + ": people to move");
        assertEquals(old.writes(), lean.writes(), at + ": writes");
        assertEquals(old.found(), lean.found(), at + ": differences found");
        assertEquals(old.firstFound(), lean.firstFound(), at + ": the first named (plan order)");
        assertEquals(old.left(), lean.left(), at + ": blocks left");
        assertEquals(old.leftAt(), lean.leftAt(), at + ": and named");
        assertEquals(old.error(), lean.error(), at + ": the error");
        assertEquals(old.loadedCount(), lean.loadedCount(), at + ": chunks loaded");
        assertEquals(old.chunkCount(), lean.chunkCount(), at + ": chunks");
    }

    private static Recording world(Scenario sc) {
        Recording w = new Recording(sc.refuse(), sc.lies());
        w.blocks.putAll(sc.world());
        w.signs.putAll(sc.signs());
        w.asyncLoads = sc.async();
        w.killAfter = sc.killAfter();
        return w;
    }

    private static List<Person> people(Scenario sc, int tick) {
        List<Person> out = new ArrayList<>();
        for (long[] p : sc.people()) { // {x, y, z, from tick, to tick}
            if (tick >= p[3] && tick < p[4]) {
                out.add(new Person(new UUID(p[0], p[2]), "Kid", "games", p[0] + 0.5, p[1] + 1, p[2] + 0.5, null, null));
            }
        }
        return out;
    }

    /** A clock that moves a microsecond each time it is read: the budget's time never runs out. */
    private static final class Clock implements java.util.function.LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos += 1_000;
        }
    }

    // ---- making scenarios --------------------------------------------------------------------------------

    private static Scenario scenario(SplittableRandom rnd, int i) {
        int sx = 8 + rnd.nextInt(40);
        int sy = 8 + rnd.nextInt(40);
        int sz = 8 + rnd.nextInt(40);
        Box half = Box.sized(rnd.nextInt(-64, 64), rnd.nextInt(-64, 200), rnd.nextInt(-64, 64), sx, sy, sz);
        boolean water = rnd.nextInt(3) == 0;
        List<String> palette = new ArrayList<>(List.of(SOLIDS));
        if (water) {
            palette.add(WATER);
        }
        List<BlockOp> ops = new ArrayList<>();
        int n = rnd.nextInt(5) == 0 ? 0 : rnd.nextInt(1, 500);
        for (int k = 0; k < n; k++) {
            int x = rnd.nextInt(half.minX(), half.maxX() + 1);
            int y = rnd.nextInt(half.minY(), half.maxY() + 1);
            int z = rnd.nextInt(half.minZ(), half.maxZ() + 1);
            int len = rnd.nextInt(4) == 0 ? rnd.nextInt(1, 6) : 1; // a short column now and then
            int state = rnd.nextInt(palette.size());
            for (int d = 0; d < len && y + d <= half.maxY(); d++) {
                ops.add(op(x, y + d, z, state));
            }
        }
        if (!ops.isEmpty() && rnd.nextInt(3) == 0) { // two blocks at one spot: the same, or another
            for (int k = 0; k < 1 + rnd.nextInt(4); k++) {
                BlockOp o = ops.get(rnd.nextInt(ops.size()));
                ops.add(rnd.nextInt(ops.size() + 1), op(o.x(), o.y(), o.z(), rnd.nextBoolean() ? o.state()
                        : rnd.nextInt(palette.size())));
            }
        }
        List<SignText> signs = new ArrayList<>();
        for (int k = 0; k < (rnd.nextInt(3) == 0 ? rnd.nextInt(1, 4) : 0); k++) {
            if (!ops.isEmpty() && rnd.nextInt(4) == 0) {
                BlockOp o = ops.get(rnd.nextInt(ops.size())); // a sign on a block's spot
                signs.add(new SignText(o.x(), o.y(), o.z(), Palette.sign(rnd.nextInt(16)), List.of("ON", "A BLOCK")));
            } else {
                signs.add(new SignText(rnd.nextInt(half.minX(), half.maxX() + 1), rnd.nextInt(half.minY(),
                        half.maxY() + 1), rnd.nextInt(half.minZ(), half.maxZ() + 1), Palette.sign(rnd.nextInt(16)),
                        List.of("SIGN " + k)));
            }
        }
        boolean none = rnd.nextInt(8) == 0;
        Plan plan = none ? null : plan(half, palette, ops, signs);
        BuildJob.Mode mode = rnd.nextInt(10) == 0 ? BuildJob.Mode.SCAN : BuildJob.Mode.CONVERGE;
        Map<Long, String> world = new HashMap<>();
        Map<Long, List<String>> worldSigns = new HashMap<>();
        Box around = Box.of(half.minX() - 2, half.minY() - 2, half.minZ() - 2, half.maxX() + 2, half.maxY() + 2,
                half.maxZ() + 2);
        for (int k = 0; k < rnd.nextInt(200); k++) { // strays, inside and just outside
            world.put(GenKit.pos(rnd.nextInt(around.minX(), around.maxX() + 1), rnd.nextInt(around.minY(),
                    around.maxY() + 1), rnd.nextInt(around.minZ(), around.maxZ() + 1)), JUNK[rnd.nextInt(JUNK.length)]);
        }
        if (plan != null) {
            for (BlockOp o : ops) {
                int r = rnd.nextInt(4);
                if (r == 0) { // already right
                    world.put(GenKit.pos(o.x(), o.y(), o.z()), GenKit.FakeWorld.canonicalOf(palette.get(o.state())));
                } else if (r == 1) { // wrong, or water where a block should be
                    world.put(GenKit.pos(o.x(), o.y(), o.z()), JUNK[rnd.nextInt(JUNK.length)]);
                }
            }
            for (SignText s : signs) {
                if (rnd.nextBoolean()) {
                    world.put(GenKit.pos(s.x(), s.y(), s.z()), GenKit.FakeWorld.canonicalOf(s.blockData()));
                    worldSigns.put(GenKit.pos(s.x(), s.y(), s.z()), rnd.nextBoolean() ? BuildJob.pad(s.lines())
                            : List.of("GRIEFED", "", "", ""));
                }
            }
        }
        Set<Long> refuse = new java.util.HashSet<>();
        if (plan != null && !ops.isEmpty() && rnd.nextInt(8) == 0) {
            for (int k = 0; k < 1 + rnd.nextInt(8); k++) { // a world that silently refuses some writes
                BlockOp o = ops.get(rnd.nextInt(ops.size()));
                refuse.add(GenKit.pos(o.x(), o.y(), o.z()));
            }
        }
        List<long[]> people = new ArrayList<>();
        if (plan != null && !ops.isEmpty() && rnd.nextInt(4) == 0) {
            for (int k = 0; k < 1 + rnd.nextInt(3); k++) {
                BlockOp o = ops.get(rnd.nextInt(ops.size()));
                long from = rnd.nextInt(5);
                long to = rnd.nextInt(8) == 0 ? from + 700 : from + rnd.nextInt(1, 40); // now and then, past STUCK_MS
                people.add(new long[]{o.x(), o.y(), o.z(), from, to});
            }
        }
        DailySettings.Budget budget = new DailySettings.Budget(1 + rnd.nextInt(60), 1 + rnd.nextInt(200), 1_000,
                1 + rnd.nextInt(3), 1 + rnd.nextInt(3), 40);
        long kill = rnd.nextInt(25) == 0 ? rnd.nextInt(1, 40) : -1;
        return new Scenario(half, plan, mode, rnd.nextInt(4) == 0, rnd.nextInt(5) == 0 ? OldAreas::foreign : null,
                world, worldSigns, refuse, rnd.nextInt(12) == 0, rnd.nextInt(5) == 0, kill, people, budget);
    }

    private static Plan plan(Box half, List<String> palette, List<BlockOp> ops, List<SignText> signs) {
        Plan like = GenKit.plan(Slots.DAILY_PARKOUR_EASY, LegacyBoxes.half(Slots.DAILY_PARKOUR_EASY, 'A'), 1, 1);
        return Plan.of(like.slot(), 1, 1, half, palette, ops, signs, List.of(), like.course(), List.of(), 0);
    }

    private static BlockOp op(int x, int y, int z, int state) {
        return new BlockOp(x, y, z, (short) state);
    }

    private static Throwable thrown(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
