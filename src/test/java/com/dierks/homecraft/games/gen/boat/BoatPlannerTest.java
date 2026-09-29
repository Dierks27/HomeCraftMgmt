package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ice Boat planner (GEN-SPEC §4.4, §8.3): a thousand seeds per tier each make a loop that is
 * closed and simple, bends no tighter than the tier allows, keeps its walls out of the lane, and
 * has its checkpoints in loop order, two laps, at most 64 of them; and the validator passes it.
 */
class BoatPlannerTest {

    private static final BoatPlanner PLANNER = new BoatPlanner();
    private static final long SECRET = 0x5EC12E7L;
    private static final Slots.Def SLOT = Slots.ICE_BOAT;

    static PlanInput input(char half, long seed, String tier) {
        return new PlanInput(SLOT, SLOT.half(half), half, 20725, 0, seed, tier, 6, 0, null);
    }

    /** The loop a plan was drawn from: the same streams the planner used, the last one it drew. */
    static BoatPlanner.Loop loopOf(Plan p) {
        Box half = p.half();
        int t = (int) p.work() - 1;
        double flatten = t < 10 ? 1 : Math.pow(0.7, t - 9);
        return BoatPlanner.loop(new GenRandom(p.seed()).fork("loop:" + t), half.minX() + half.sizeX() / 2.0,
                half.minZ() + half.sizeZ() / 2.0, flatten);
    }

    @Test
    void aThousandSeedsPerTierMakeGoodLoops() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            soak(level);
        }
    }

    private static void soak(BoatPlanner.Level level) {
        int n = 1_000;
        Set<String> hashes = ConcurrentHashMap.newKeySet();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, n).parallel().forEach(day -> {
            long seed = GenSeed.seed(SECRET, 20_000 + day, SLOT.id(), 0);
            try {
                Plan p = PLANNER.plan(input('A', seed, level.id()));
                hashes.add(p.hash());
                List<String> problems = BoatValidator.problems(p, level.id());
                if (!problems.isEmpty()) {
                    failures.add("day " + day + ": " + problems);
                }
                String why = loopProblem(p, level);
                if (why != null) {
                    failures.add("day " + day + ": " + why);
                }
            } catch (GenFailed e) {
                failures.add("day " + day + ": " + e.getMessage());
            }
        });
        assertTrue(failures.isEmpty(), level + ": " + failures.size() + " bad days, first "
                + failures.subList(0, Math.min(5, failures.size())));
        assertTrue(hashes.size() >= n * 0.95, level + ": at least 95% of days differ, got " + hashes.size());
    }

    /** Everything the spec asks of the loop itself, checked on the exact centreline; null when fine. */
    private static String loopProblem(Plan p, BoatPlanner.Level level) {
        BoatPlanner.Loop loop = loopOf(p);
        int n = loop.size();
        // closed: the last point joins the first a step away
        double gap = Math.hypot(loop.xs()[0] - loop.xs()[n - 1], loop.zs()[0] - loop.zs()[n - 1]);
        if (Math.abs(gap - loop.step()) > 0.05) {
            return "the loop doesn't close (" + gap + ")";
        }
        // simple: seen from its middle, the angle only ever grows, once round
        double turned = 0;
        for (int i = 0; i < n; i++) {
            double a0 = Math.atan2(loop.zs()[i] - loop.cz(), loop.xs()[i] - loop.cx());
            double a1 = Math.atan2(loop.zs()[(i + 1) % n] - loop.cz(), loop.xs()[(i + 1) % n] - loop.cx());
            double step = Math.atan2(Math.sin(a1 - a0), Math.cos(a1 - a0));
            if (step <= 0) {
                return "the loop turns back on itself";
            }
            turned += step;
        }
        if (Math.abs(turned - 2 * Math.PI) > 1e-6) {
            return "the loop goes round " + turned + " radians, not once";
        }
        // curvature: three points 2 apart are on a circle of at least the tier's radius
        if (BoatPlanner.minRadius(loop) < level.minRadius() - 1e-9) {
            return "a bend of " + BoatPlanner.minRadius(loop);
        }
        // width: no wall block's middle within half the width less half a block of the centreline
        double lane = level.width() / 2.0 - 0.5;
        int iceY = p.half().minY() + BoatPlanner.ICE_ABOVE_FLOOR;
        for (BlockOp op : p.ops()) {
            if (!p.blockOf(op).equals(Palette.TRACK_WALL) || op.y() != iceY) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (int i = 0; i < n; i += 2) {
                double dx = loop.xs()[i] - (op.x() + 0.5);
                double dz = loop.zs()[i] - (op.z() + 0.5);
                if (Math.abs(dx) < 8 && Math.abs(dz) < 8) {
                    best = Math.min(best, BoatPlanner.segment(loop.xs()[i], loop.zs()[i], loop.xs()[(i + 2) % n],
                            loop.zs()[(i + 2) % n], op.x() + 0.5, op.z() + 0.5));
                }
            }
            if (best < lane - 1e-9) {
                return "a wall at " + op.x() + " " + op.z() + " is " + best + " from the centreline";
            }
        }
        // checkpoints: two identical laps, in loop order, at most 64
        Course c = ((PlannedTrial) p.course()).course();
        List<Course.Mark> cps = c.checkpoints();
        if (cps.size() > 64 || cps.size() % 2 != 0) {
            return cps.size() + " checkpoints";
        }
        List<Course.Mark> lap = cps.subList(0, cps.size() / 2);
        if (!lap.equals(cps.subList(cps.size() / 2, cps.size()))) {
            return "the laps differ";
        }
        double previous = Math.atan2(c.finish().z() - loop.cz(), c.finish().x() - loop.cx());
        double round = 0;
        for (Course.Mark m : lap) {
            double a = Math.atan2(m.z() - loop.cz(), m.x() - loop.cx());
            double step = Math.atan2(Math.sin(a - previous), Math.cos(a - previous));
            if (step <= 0) {
                return "checkpoints out of order";
            }
            round += step;
            previous = a;
        }
        if (round >= 2 * Math.PI) {
            return "the checkpoints go round more than once a lap";
        }
        return null;
    }

    @Test
    void theSameSeedAlwaysMakesTheSameLayout() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Plan a = PLANNER.plan(input('A', 5, level.id()));
            Plan b = PLANNER.plan(input('A', 5, level.id()));
            assertEquals(a.hash(), b.hash(), level + ": the same seed hashes the same");
            assertEquals(a.ops(), b.ops(), level + ": block for block");
            assertTrue(!a.hash().equals(PLANNER.plan(input('A', 6, level.id())).hash()), level + ": another differs");
        }
    }

    @Test
    void goldenHashesPinThreeSeedsPerTier() throws GenFailed {
        // A change here means the planner makes different layouts: bump BoatPlanner.ALGO. Algo 2 added
        // the viewing stand (EVENTS-DROPPER-SPEC §A.4.2), so every layout changed once.
        String golden = """
                easy 17716620da48 60f766a48aae be493b43c067
                medium e32014d62d1c f556e10cb2e4 c84f65320221
                hard de0d07f16cf7 524fc2c7990c b3ce7c41112a
                """;
        long[] seeds = {1L, 0xC0FFEEL, 0x5EED5EEDL};
        StringBuilder made = new StringBuilder();
        for (String tier : List.of("easy", "medium", "hard")) {
            made.append(tier);
            for (long seed : seeds) {
                made.append(' ').append(PLANNER.plan(input('A', seed, tier)).hash());
            }
            made.append('\n');
        }
        assertEquals(golden, made.toString(), "every tier's three seeds (if this changed, bump ALGO)");
        assertEquals(2, BoatPlanner.ALGO, "the version these hashes were pinned at");
    }

    @Test
    void theCourseIsTwoLapsOfAWalledIceLoop() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Plan p = PLANNER.plan(input('B', 21, level.id()));
            Course c = ((PlannedTrial) p.course()).course();
            assertEquals(TrialKind.BOAT, c.kind(), "a boat course");
            assertEquals(SLOT.id(), c.id(), "the slot's id");
            assertEquals(Tier.of(level.id()), c.tier(), "its tier");
            Box half = SLOT.half('B');
            int iceY = half.minY() + BoatPlanner.ICE_ABOVE_FLOOR;
            for (BlockOp op : p.ops()) {
                assertTrue(half.contains(op.x(), op.y(), op.z()), "inside half B");
                String b = p.blockOf(op);
                if (op.y() == iceY && !b.equals(Palette.TRACK_WALL)) {
                    assertEquals(level.ice(), b, level + ": the track is " + Palette.id(level.ice()));
                }
            }
            assertEquals(iceY - 3, c.fallY(), 1e-9, "the fall height is 3 under the ice");
            for (Course.Mark m : c.checkpoints()) {
                assertEquals(level.width() / 2.0 + 0.5, m.radius(), 1e-9, "a checkpoint spans the track");
            }
            BoatPlanner.Loop loop = loopOf(p);
            assertEquals(Math.round(2 * loop.length() / 30 * 1000), ((PlannedTrial) p.course()).refMs(),
                    "reference: two laps at 30 blocks a second");
            assertEquals((int) Math.floor(2 * loop.length() / 60), (int) c.minSeconds(), "shortest: two laps at 60");
            assertEquals(4, Math.hypot(c.start().x() - c.finish().x(), c.start().z() - c.finish().z()), 0.2,
                    "the start is 4 before the line");
            assertTrue(p.ops().stream().anyMatch(op -> p.blockOf(op).startsWith(Palette.ARROW)), "arrows in the wall");
            assertEquals(2, p.signs().size(), "a start sign and the viewing stand's sign");
            assertEquals(GenCopy.boatStart(BoatPlanner.LAPS), p.signs().get(0).lines(), "saying two laps");
            assertEquals(RaceStand.SIGN, p.signs().get(1).lines(), "and where to watch from");
            assertTrue(p.ops().size() < 6_000, "about 3,300 blocks: " + p.ops().size());
        }
    }

    @Test
    void theViewingStandIsRailedAboveTheKeepClearSpaceAndFarFromTheLane() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (long seed : new long[]{1, 21, 0xC0FFEEL}) {
                Plan p = PLANNER.plan(input('B', seed, level.id()));
                Box half = p.half();
                Course c = ((PlannedTrial) p.course()).course();
                int cx = RaceStand.centreX(half);
                int cz = RaceStand.centreZ(half);
                int floorY = RaceStand.floorY(c.start().y());
                int iceY = half.minY() + BoatPlanner.ICE_ABOVE_FLOOR;
                assertEquals(iceY + 5, floorY, "the platform is 5 above the ice, its top 5 above the race line");
                java.util.Map<String, String> at = new java.util.HashMap<>();
                double nearestIce = Double.MAX_VALUE;
                for (BlockOp op : p.ops()) {
                    at.put(op.x() + " " + op.y() + " " + op.z(), p.blockOf(op));
                    if (op.y() == iceY && p.blockOf(op).equals(level.ice())) {
                        int dx = Math.max(0, Math.abs(op.x() - cx) - 3);
                        int dz = Math.max(0, Math.abs(op.z() - cz) - 3);
                        nearestIce = Math.min(nearestIce, Math.hypot(dx, dz));
                    }
                }
                for (int x = cx - 3; x <= cx + 3; x++) {
                    for (int z = cz - 3; z <= cz + 3; z++) {
                        assertEquals(RaceStand.FLOOR, at.get(x + " " + floorY + " " + z), "a whole 7 x 7 platform");
                        boolean edge = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3;
                        for (int h = 1; h <= 2; h++) {
                            assertEquals(edge ? RaceStand.RAIL_BLOCK : null, at.get(x + " " + (floorY + h) + " " + z),
                                    edge ? "a two-high glass rail round the edge" : "headroom over the inner 5 x 5");
                        }
                    }
                }
                assertTrue(nearestIce >= 12, level + " seed " + seed + ": at least 12 from the lane, got " + nearestIce);
                for (Box k : p.keepClear()) {
                    assertTrue(k.maxY() < floorY, "above the track's keep-clear space (ice + 1 to + 4)");
                }
                assertTrue(half.contains(cx, floorY + 2, cz), "the rail's top is inside the half");
                Point spot = RaceStand.spot(half, c.start().y());
                assertEquals(floorY + 1, spot.y(), 1e-9, "players stand on the platform");
                assertEquals(new Point(cx + 0.5, floorY + 1, cz + 0.5), spot, "in the middle of it, where races park them");
            }
        }
    }

    @Test
    void aCircleAlwaysPassesSoACourseIsNeverMissing() {
        Box half = SLOT.half('A');
        BoatPlanner.Loop circle = BoatPlanner.loop(new GenRandom(1), half.minX() + 64, half.minZ() + 64, 0);
        assertEquals(BoatPlanner.BASE_RADIUS, BoatPlanner.minRadius(circle), 0.01, "a flat loop is a circle of 40");
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            assertTrue(BoatPlanner.minRadius(circle) >= level.minRadius(), level + ": a circle bends gently enough");
        }
    }

    @Test
    void theBootCheckMakesTheLiveLayoutAgainFromItsTag() throws GenFailed {
        Plan live = PLANNER.plan(input('A', 31, "medium"));
        GenTag tag = new GenTag(SLOT.id(), Slots.BOAT, BoatPlanner.ALGO, 20725, 0, 31, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertEquals(live.hash(), PLANNER.rederive(input('A', 0, "hard"), tag).hash(), "from the tag, any tier");
        GenTag older = new GenTag(SLOT.id(), Slots.BOAT, BoatPlanner.ALGO + 1, 20725, 0, 31, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> PLANNER.rederive(input('A', 0, "medium"), older), "another version");
    }

    @Test
    void aWrongTierOrACancelFailsCleanly() {
        assertThrows(GenFailed.class, () -> PLANNER.plan(input('A', 1, "EEE")), "a golf mix isn't a boat tier");
        PlanInput cancelled = new PlanInput(SLOT, SLOT.half('A'), 'A', 1, 0, 1, "medium", 6, 0, () -> true);
        assertThrows(GenFailed.class, () -> PLANNER.plan(cancelled), "a cancelled job gives up");
        assertEquals(Slots.BOAT, PLANNER.id(), "its id");
    }
}
