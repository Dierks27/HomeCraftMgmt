package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.SyntheticMountain;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.storage.GenArchiveDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No plan-sized work on the main thread (MOUNTAIN-V2-SPEC F11, V4-DECISIONS red-team F11): a 400,000-block
 * Mountain Run (PlanCheck's cap) goes through the real engine (claimed, planned, checked, built, verified,
 * flipped and archived; previewed and promoted; verified again at a boot), and the admin reads it back
 * ({@code history}) and asks to keep it, and no single main-thread slice (a tick, a check, a command) takes
 * more than 50 ms of the main thread's CPU. Plan-sized work (the checks, the archive row, the build index,
 * reading a row back) runs on the planner thread, played here between the ticks, outside the slices.
 *
 * <p>The budget is the shipped one (4 chunks read and at most 5,000 blocks written a tick), so a slice is
 * the engine's own work and not the test's pace; the world keeps its blocks by chunk, as a real one does.
 * What a running server loaded at its start (the game catalogue, whose first use solves Twenty-One's
 * tables; the seed's HMAC provider; YAML) is loaded first, and a small course is on beside it, so a slice is
 * never the JVM's first look at a class nor the engine's first flip.
 */
class MainThreadSliceTest {

    private static final String BOAT = Slots.ICE_BOAT.id();
    /** A small course on beside it (a server always has some): its flip is not the engine's first. */
    private static final String SMALL = Slots.DAILY_PARKOUR_EASY.id();
    /** The most main-thread CPU any one slice may take. */
    private static final long SLICE_NANOS = 50_000_000L;
    private static final int OPS = PlanCheck.BOAT_V4_MAX_OPS;

    /** The 400,000-block mountain, made once (it takes a second). */
    private static Plan mountain;

    private Host host;
    private GenService gen;
    private Map<String, Planner> planners;
    private final List<String> said = new ArrayList<>();
    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private long worst;
    private String worstWhat = "";
    private int slices;
    /** The slices over 10 ms, with what the engine was doing: the failure names them. */
    private final List<String> long10 = new ArrayList<>();

    /** A boat planner whose every plan is the mountain, moved into the half asked for. */
    static final class MountainPlanner implements Planner {
        int plans;

        @Override
        public String id() {
            return Slots.BOAT;
        }

        @Override
        public int algo() {
            return PlanCheck.BOAT_MOUNTAIN_ALGO;
        }

        @Override
        public Plan plan(PlanInput in) {
            plans++;
            return PlanShift.to(base(), in.half());
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) {
            return PlanShift.to(base(), in.half());
        }

        static synchronized Plan base() {
            if (mountain == null) {
                mountain = SyntheticMountain.plan(41, OPS);
            }
            return mountain;
        }
    }

    @org.junit.jupiter.api.BeforeAll
    static void warmUp() throws Exception {
        Class.forName("com.dierks.homecraft.games.GameCatalog", true, MainThreadSliceTest.class.getClassLoader());
        com.dierks.homecraft.games.gen.api.GenSeed.seed(1L, 7, 20_725L, BOAT, 0);
        com.dierks.homecraft.games.trial.Course c = ((com.dierks.homecraft.games.gen.api.PlannedTrial)
                MountainPlanner.base().course()).course();
        com.dierks.homecraft.games.trial.CourseCodec.decode(c.id(), com.dierks.homecraft.games.trial.CourseCodec
                .encode(c));
    }

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, BOAT, SMALL);
        host.worlds.put(GenKit.WORLD, new ChunkedWorld(GenKit.WORLD, 2 * OPS + 100_000)); // both halves built
        host.settings = GenKit.weekly(BOAT, SMALL); // the shipped budget; a small course is built too, as on a server
        host.holdPlans = true; // the planner thread's work is played between the ticks, outside the slices
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, new FakePlanner(Slots.PARKOUR));
        planners.put(Slots.RINGS, new FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, new FakePlanner(Slots.GOLF));
        planners.put(Slots.BOAT, new MountainPlanner());
        planners.put(Slots.DROPPER, new FakePlanner(Slots.DROPPER));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** One main-thread slice, timed by the thread's own CPU (other work on the box can't inflate it). */
    private void slice(String what, Runnable r) {
        boolean cpu = mx.isCurrentThreadCpuTimeSupported();
        long t0 = cpu ? mx.getCurrentThreadCpuTime() : System.nanoTime();
        String before = gen == null ? "" : gen.jobKind() + "";
        r.run();
        long took = (cpu ? mx.getCurrentThreadCpuTime() : System.nanoTime()) - t0;
        slices++;
        if (took > 10_000_000L && long10.size() < 40) {
            long10.add(what + " #" + slices + " " + took / 1_000_000 + " ms (job " + before + " -> " + gen.jobKind()
                    + ")");
        }
        if (took > worst) {
            worst = took;
            worstWhat = what;
        }
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        slice("start", gen::start);
        slice("worlds ready", gen::worldsReady);
    }

    /** Ticks (a check every twenty) until {@code done}, the planner thread's work played after each. */
    private void until(BooleanSupplier done, int maxTicks, String what) {
        for (int t = 1; t <= maxTicks && !done.getAsBoolean(); t++) {
            slice("a tick", gen::tick);
            host.now += 50;
            if (t % 20 == 0) {
                slice("a check", gen::check);
            }
            host.runPlans();
        }
        assertTrue(done.getAsBoolean(), what + " within " + maxTicks + " ticks: " + String.join(" | ", said)
                + " " + gen.status(BOAT));
    }

    @Test
    void aFourHundredThousandBlockMountainNeverTakesFiftyMillisecondsOfTheMainThreadAtOnce() throws Exception {
        boot();
        until(() -> gen.liveTag(BOAT) != null && gen.live(BOAT, gen.liveTag(BOAT)), 40_000, "the mountain is built");
        GenTag first = gen.liveTag(BOAT);
        Box a = Slots.ICE_BOAT.half(first.half());
        assertEquals(OPS + mountain.signs().size(), host.world().count(a), "all 400,000 blocks and its signs stand");
        GenArchiveDao.Row row = host.store.edition(BOAT, first.editionKey());
        assertNotNull(row, "it is archived");
        assertTrue(row.plan().length < 600_000, "in a compact row: " + row.plan().length + " bytes");

        said.clear();
        slice("preview", () -> gen.preview(BOAT, null, said::add));
        until(() -> String.join(" ", said).contains("is ready"), 40_000, "the preview is built");
        said.clear();
        slice("promote", () -> gen.promote(BOAT, true, said::add));
        until(() -> gen.liveTag(BOAT) != null && gen.liveTag(BOAT).half() != first.half()
                && gen.live(BOAT, gen.liveTag(BOAT)), 40_000, "the preview is promoted");
        GenTag promoted = gen.liveTag(BOAT);
        assertNotEquals(first.half(), promoted.half(), "into the other half");

        boot(); // a restart: the boot check verifies the live mountain again
        until(() -> gen.live(BOAT, promoted), 40_000, "the boot check opens it again");

        GenArchiveDao.Row archived = host.store.edition(BOAT, promoted.editionKey());
        said.clear();
        slice("history", () -> gen.historyOf(BOAT, GenArgs.which(archived.code()), said::add));
        until(() -> String.join(" ", said).contains("Plan:"), 400, "history answers");
        assertTrue(String.join(" ", said).contains("Plan: " + OPS + " blocks, hash " + promoted.planHash()),
                "with the plan read back off the main thread: " + said);
        said.clear();
        slice("keep", () -> gen.keep(BOAT, GenArgs.which(archived.code()), "big_boat", null, false, true,
                said::add));
        until(() -> String.join(" ", said).contains("too big to keep"), 400, "keep answers");

        System.out.println("MainThreadSliceTest: " + slices + " main-thread slices for a " + OPS
                + "-block mountain; the longest " + worst / 1_000_000.0 + " ms (" + worstWhat + "); over 10 ms: " + long10);
        assertTrue(worst <= SLICE_NANOS, "no main-thread slice over 50 ms: the longest was " + worst / 1_000_000.0
                + " ms (" + worstWhat + "); over 10 ms: " + long10);
    }
}
