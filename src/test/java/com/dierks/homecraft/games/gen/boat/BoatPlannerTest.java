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
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.LiveProof;
import com.dierks.homecraft.games.gen.engine.PlanCheck;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Laps;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run planner, Ice Boat algo 3 (Course Variety §2.9, §9): a thousand seeds per tier
 * each make a downhill sprint that {@link DownhillValidator} proves (V1-V13: containment, the deck
 * graph, the widths, the checkpoints as cuts in order, the grid of 12, the stand, the scenery), that
 * the engine's own checks accept, under the size cap and quickly; the safe spiral is proven for
 * every orientation, tier and half; a layout comes back from its tag, and three seeds a tier are
 * pinned.
 */
class BoatPlannerTest {

    private static final BoatPlanner PLANNER = new BoatPlanner();
    private static final long SECRET = 0x5EC12E7L;
    private static final Slots.Def SLOT = Slots.ICE_BOAT;

    static PlanInput input(char half, long seed, String tier) {
        return new PlanInput(SLOT, SLOT.half(half), half, 20725, 0, seed, tier, 6, 0, null);
    }

    static Course course(Plan p) {
        return ((PlannedTrial) p.course()).course();
    }

    // ---- the soak -----------------------------------------------------------------------------------

    @Test
    void aThousandSeedsPerTierMakeProvenMountainRuns() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            soak(level, 1_000, false);
        }
    }

    /** §9: 1,000 seeds x 3 tiers x both halves (the long soak: {@code gradle test -Pslow}). */
    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void aThousandSeedsPerTierInBothHalvesMakeProvenMountainRuns() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            soak(level, 1_000, true);
        }
    }

    private static void soak(BoatPlanner.Level level, int n, boolean bothHalves) {
        Set<String> hashes = ConcurrentHashMap.newKeySet();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Long> millis = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger byThird = new AtomicInteger();
        AtomicInteger plans = new AtomicInteger();
        IntStream.range(0, n * (bothHalves ? 2 : 1)).parallel().forEach(i -> {
            int day = bothHalves ? i / 2 : i;
            char half = bothHalves ? (i % 2 == 0 ? 'A' : 'B') : (day % 2 == 0 ? 'A' : 'B');
            long seed = GenSeed.seed(SECRET, 20_000 + day, SLOT.id(), 0);
            try {
                long t0 = System.nanoTime();
                BoatPlanner.Made m = BoatPlanner.made(input(half, seed, level.id()));
                Plan p = m.finished(m.work, BoatPlanner.TRIES);
                millis.add((System.nanoTime() - t0) / 1_000_000);
                plans.incrementAndGet();
                hashes.add(p.hash());
                if (p.work() <= 3) {
                    byThird.incrementAndGet();
                }
                String why = problem(p, level);
                if (why == null) {
                    why = signProblem(m);
                }
                if (why != null) {
                    failures.add("day " + day + " half " + half + ": " + why);
                }
            } catch (GenFailed | RuntimeException e) {
                failures.add("day " + day + " half " + half + ": " + e);
            }
        });
        assertTrue(failures.isEmpty(), level + ": " + failures.size() + " bad days, first "
                + failures.subList(0, Math.min(5, failures.size())));
        int total = n * (bothHalves ? 2 : 1);
        assertTrue(hashes.size() >= total * 0.95, level + ": at least 95% of layouts differ, got " + hashes.size());
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        long p99 = sorted.get((int) Math.floor(sorted.size() * 0.99) - 1);
        assertTrue(p99 <= 10_000, level + ": the 99th percentile plan takes at most 10 s, took " + p99 + " ms");
        // a monitored rate, not a gate (§9): kept loose so a slow machine never fails it
        assertTrue(byThird.get() >= plans.get() * 0.90, level + ": nearly every plan is proven by its third try, "
                + byThird.get() + " of " + plans.get());
    }

    /** Everything the soak asks of one plan; null when fine. */
    static String problem(Plan p, BoatPlanner.Level level) {
        List<String> proof = BoatValidator.problems(p, level.id());
        if (!proof.isEmpty()) {
            return "the proof: " + proof;
        }
        List<String> engine = PlanCheck.problems(p, SLOT, p.half());
        if (!engine.isEmpty()) {
            return "the engine's checks: " + engine;
        }
        if (p.ops().size() > DownhillValidator.MAX_OPS) {
            return p.ops().size() + " blocks";
        }
        if (p.algo() != BoatPlanner.ALGO) {
            return "algo " + p.algo();
        }
        Course c = course(p);
        if (Laps.loop(c) || Laps.natural(c) != 1) {
            return "not a one-lap sprint";
        }
        PlanSurface surface = new PlanSurface(p);
        RaceGrid.Grid g = RaceGrid.plan(RaceGrid.path(c), c.start().y(), surface, RaceGrid.MAX_SPOTS);
        if (g.size() != RaceGrid.MAX_SPOTS || g.mode() != RaceGrid.Mode.DOUBLE) {
            return "the grid seats " + g.size() + " " + g.mode();
        }
        if (!RaceStand.standable(surface, RaceStand.spot(p.half(), c.start().y()))) {
            return "the stand can't be stood on";
        }
        List<String> live = LiveProof.structure(c, (x, y, z) -> surface.at(x, y, z) == RaceGrid.Cell.SOLID);
        if (!live.isEmpty()) {
            return "the boot check: " + live;
        }
        int drops = drops(p);
        if (drops < level.minDrops() || drops > level.maxDrops()) {
            return drops + " drops";
        }
        return null;
    }

    /**
     * Every sign a plan's own pieces ask for (§8), null when all are up: each drop's and each piece's
     * (but a boost strip's) just before it, and the Final Drop's words only where the finish follows
     * within 70 blocks (§2.5's 43-70), since "Then the gold finish line!" must be true.
     */
    static String signProblem(BoatPlanner.Made m) {
        List<SignText> signs = m.plan.signs();
        for (TrackPieces.Piece q : m.pieces.list) {
            List<String> want = switch (q.kind) {
                case SAND_PIT -> GenCopy.boatSandPit();
                case SPLIT -> GenCopy.boatSplit();
                case CAVE -> GenCopy.boatIceCave();
                case FOREST -> GenCopy.boatForest();
                case BOOST -> null;
            };
            if (want != null && !signedBefore(m, signs, want, q.s1)) {
                return "no " + want.get(0) + " sign before the " + q.kind.word + " at s " + Math.round(q.s1) + " (leg "
                        + q.leg + ", " + Math.round(q.s1 - m.path.straight(q.leg).s0) + " into its straight)";
            }
        }
        TrackProfile profile = m.profile;
        for (int i = 0; i < profile.lips.size(); i++) {
            TrackProfile.Lip l = profile.lips.get(i);
            boolean last = i == profile.lips.size() - 1;
            List<String> want = last && profile.finish - l.s() <= 70 ? GenCopy.boatFinalDrop() : GenCopy.boatDrop(l.drop());
            if (!signedBefore(m, signs, want, l.s())) {
                return "no " + want.get(0) + " sign before the drop at s " + Math.round(l.s()) + " (leg " + l.leg() + ")";
            }
        }
        long finals = signs.stream().filter(s -> s.lines().equals(GenCopy.boatFinalDrop())).count();
        boolean inFront = profile.last() != null && profile.finish - profile.last().s() <= 70;
        if (finals != (inFront ? 1 : 0)) {
            return finals + " FINAL DROP! signs, but the finish is " + Math.round(profile.finish - profile.last().s())
                    + " after the last drop (the words promise the gold finish line next: at most 70)";
        }
        return null;
    }

    /** Whether a sign saying {@code lines} stands on a wall beside the track 3-10 blocks before {@code s}. */
    private static boolean signedBefore(BoatPlanner.Made m, List<SignText> signs, List<String> lines, double s) {
        Box half = m.plan.half();
        double reach = m.level.width() / 2.0 + BoatPlanner.MAX_EXTRA + 2.5;
        for (SignText sign : signs) {
            if (!sign.lines().equals(lines)) {
                continue;
            }
            double x = sign.x() - half.minX() + 0.5;
            double z = sign.z() - half.minZ() + 0.5;
            for (double u = s - BoatPlanner.SIGN_BEFORE; u <= s - BoatPlanner.SIGN_NEAR + 1e-9; u += 0.5) {
                double[] c = m.path.at(u);
                if (Math.hypot(c[0] - x, c[1] - z) <= reach) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many drops a plan's course has: the legs whose next target is lower (Race Night's hype count). */
    static int drops(Plan p) {
        Course c = course(p);
        int n = 0;
        double y = c.start().y();
        for (Course.Mark m : c.targets()) {
            if (m.y() < y - 1e-9) {
                n++;
            }
            y = m.y();
        }
        return n;
    }

    // ---- one layout ---------------------------------------------------------------------------------

    @Test
    void theSameSeedAlwaysMakesTheSameLayout() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Plan a = PLANNER.plan(input('A', 5, level.id()));
            Plan b = PLANNER.plan(input('A', 5, level.id()));
            assertEquals(a.hash(), b.hash(), level + ": the same seed hashes the same");
            assertEquals(a.ops(), b.ops(), level + ": block for block");
            assertEquals(a.signs(), b.signs(), level + ": sign for sign");
            assertNotEquals(a.hash(), PLANNER.plan(input('A', 6, level.id())).hash(), level + ": another seed differs");
        }
    }

    @Test
    void goldenHashesPinThreeSeedsPerTier() throws GenFailed {
        // A change here means the planner makes different layouts: bump BoatPlanner.ALGO. Algo 3 is the
        // Mountain Run (Course Variety §2) without BoatSim's V14 (the §11 schedule valve); V14 comes as
        // algo 4 after the owner's boat test strip (Gate 0), and re-pins these.
        String golden = """
                easy ad32a53c18a5 e9dc62ce8f83 d1c4fce032ad
                medium 0f32482b7197 7a96ea1288f6 d9e3833052c0
                hard 8724d626bd2c 43e06d7b4237 b0fbd41ccfc5
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
        assertEquals(3, BoatPlanner.ALGO, "the version these hashes were pinned at");
    }

    @Test
    void theCourseIsADownhillSprintFromThePitToAGoldFinishUnderTheStand() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Plan p = PLANNER.plan(input('B', 21, level.id()));
            Course c = course(p);
            Box half = SLOT.half('B');
            int h0 = half.minY();
            assertEquals(TrialKind.BOAT, c.kind(), "a boat course");
            assertEquals(SLOT.id(), c.id(), "the slot's id");
            assertEquals(Tier.of(level.id()), c.tier(), "its tier");
            assertEquals(h0 + 9, c.start().y(), 1e-9, "the start is on the top ice, H0 + 8 (the highest the stand allows)");
            assertEquals(1, Laps.natural(c), "the checkpoints are stored once: a sprint");
            assertFalse(Laps.loop(c), "the finish is far from the start: never a loop");
            assertTrue(c.finish().y() < c.start().y() - 3, "the finish is well below the start: it's downhill");
            int lowest = Integer.MAX_VALUE;
            for (BlockOp op : p.ops()) {
                String b = p.blockOf(op);
                if (b.equals(Palette.TRACK) || b.equals(Palette.TRACK_FAST) || b.equals(Palette.SAND)) {
                    lowest = Math.min(lowest, op.y());
                }
            }
            assertEquals(lowest - 3, c.fallY(), 1e-9, "the fall height is 3 under the lowest ice");
            assertEquals(c.finish().y() - 1, lowest, 1e-9, "and the finish is on the lowest ice");
            assertEquals(DownhillValidator.minSeconds(c), (int) c.minSeconds(), "the shortest time is its legs' bound");
            long ref = ((PlannedTrial) p.course()).refMs();
            assertTrue(ref >= c.minSeconds() * 1000L + 1000, "the reference time is over the shortest time and a second");
            assertEquals(level.width() / 2.0 + 1.5, c.finish().radius(), 1e-9, "the finish spans the lane");
            for (Course.Mark m : c.checkpoints()) {
                double r = m.radius();
                assertTrue(Math.abs(r - (level.width() / 2.0 + 0.5)) < 1e-9
                                || Math.abs(r - (level.pitWidth() / 2.0 + 0.5)) < 1e-9
                                || Math.abs(r - (level.width() / 2.0 + BoatPlanner.ARC_SPOT)) < 1e-9,
                        level + ": a checkpoint spans its lane (half the width and a half; a bend's a block more): " + r);
            }
            assertTrue(c.checkpoints().size() >= 5 && c.checkpoints().size() <= Course.MAX_CHECKPOINTS,
                    level + ": checkpoints all the way down, at most 64: " + c.checkpoints().size());
            boolean sign = false;
            boolean stand = false;
            for (SignText s : p.signs()) {
                sign |= s.lines().equals(GenCopy.boatRun());
                stand |= s.lines().equals(RaceStand.SIGN);
                assertTrue(s.y() <= h0 + 12 || s.lines().equals(RaceStand.SIGN), "signs stay under the stand's floor");
            }
            assertTrue(sign, level + ": the start sign says it's a downhill race");
            assertTrue(stand, level + ": and the stand says where to watch from");
            assertTrue(p.summary().get(0).contains("Mountain Run"), "the summary names it: " + p.summary());
            assertEquals(4, p.summary().size(), "four summary lines");
        }
    }

    @Test
    void theEasyTrackIsPackedIceAndOnlyHardIsAllBlue() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Plan p = PLANNER.plan(input('A', 0xC0FFEEL, level.id()));
            Map<String, Integer> ice = new HashMap<>();
            for (BlockOp op : p.ops()) {
                String b = p.blockOf(op);
                if (b.equals(Palette.TRACK) || b.equals(Palette.TRACK_FAST)) {
                    ice.merge(b, 1, Integer::sum);
                }
            }
            if (level == BoatPlanner.Level.HARD) {
                assertFalse(ice.containsKey(Palette.TRACK), "hard races on blue ice only: " + ice);
            } else if (level == BoatPlanner.Level.EASY) {
                assertFalse(ice.containsKey(Palette.TRACK_FAST), "easy is packed ice only: " + ice);
            } else {
                assertTrue(ice.getOrDefault(Palette.TRACK, 0) > ice.getOrDefault(Palette.TRACK_FAST, 0),
                        "medium is packed ice with at most a couple of blue boost strips: " + ice);
            }
        }
    }

    @Test
    void theViewingStandIsRailedAboveEverythingAndFarFromTheIce() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (long seed : new long[]{1, 21, 0xC0FFEEL}) {
                Plan p = PLANNER.plan(input('B', seed, level.id()));
                Box half = p.half();
                Course c = course(p);
                int cx = RaceStand.centreX(half);
                int cz = RaceStand.centreZ(half);
                int floorY = RaceStand.floorY(c.start().y());
                assertEquals(half.minY() + 13, floorY, "the platform is 5 over the start, its rail's top the half's top");
                Map<String, String> at = new HashMap<>();
                double nearestIce = Double.MAX_VALUE;
                for (BlockOp op : p.ops()) {
                    String b = p.blockOf(op);
                    at.put(op.x() + " " + op.y() + " " + op.z(), b);
                    if (b.equals(Palette.TRACK) || b.equals(Palette.TRACK_FAST) || b.equals(Palette.SAND)) {
                        int dx = Math.max(0, Math.abs(op.x() - cx) - 3);
                        int dz = Math.max(0, Math.abs(op.z() - cz) - 3);
                        nearestIce = Math.min(nearestIce, Math.hypot(dx, dz));
                    }
                    if (op.y() >= floorY) {
                        assertTrue(RaceStand.onPlatform(op.x(), op.z(), cx, cz),
                                "only the stand stands at the stand's heights (the scenery cap): " + op);
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
                assertTrue(nearestIce >= 12, level + " seed " + seed + ": at least 12 from the track, got " + nearestIce);
                for (Box k : p.keepClear()) {
                    assertTrue(k.maxY() < floorY, "every keep-clear box is under the platform");
                    assertTrue(half.contains(k), "and inside the half");
                }
                assertTrue(p.keepClear().size() <= DownhillValidator.MAX_BOXES, "at most 64 boxes");
                Point spot = RaceStand.spot(half, c.start().y());
                assertEquals(new Point(cx + 0.5, floorY + 1, cz + 0.5), spot, "races park finishers in its middle");
                assertTrue(RaceStand.standable(new PlanSurface(p), spot), "and they can stand there");
            }
        }
    }

    // ---- the safe spiral ---------------------------------------------------------------------------------

    @Test
    void theSafeSpiralIsProvenForEveryOrientationTierAndHalf() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (char half : new char[]{'A', 'B'}) {
                for (int side = 0; side < 4; side++) {
                    for (int dir = -1; dir <= 1; dir += 2) {
                        BoatPlanner.Made m = BoatPlanner.safe(input(half, 7, level.id()), level, side, dir,
                                new GenRandom(side * 31L + dir));
                        String where = level + " half " + half + ", leg 0 on side " + side + ", turning " + dir;
                        assertNotNull(m, where + ": the safe spiral always comes together");
                        assertEquals(null, problem(m.finished(BoatPlanner.TRIES + 1, BoatPlanner.TRIES), level),
                                where + ": and is proven");
                        int[] want = switch (level) {
                            case EASY -> new int[]{1, 1, 1, 1};
                            case MEDIUM -> new int[]{1, 1, 1, 1, 1};
                            case HARD -> new int[]{1, 1, 2, 1};
                        };
                        int[] got = new int[m.profile.lips.size()];
                        for (int i = 0; i < got.length; i++) {
                            got[i] = m.profile.lips.get(i).drop();
                        }
                        assertEquals(java.util.Arrays.toString(want), java.util.Arrays.toString(got),
                                where + ": the tier's fewest drops");
                        assertTrue(m.pieces.list.isEmpty() && m.pieces.runoffs() == 0, where + ": no pieces, no sand on the bends");
                    }
                }
            }
        }
    }

    @Test
    void theSafeSpiralsTrackDependsOnlyOnItsOrientationAndItsSceneryOnTheSeed() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            BoatPlanner.Made a = BoatPlanner.safe(input('A', 1, level.id()), level, 2, 1, new GenRandom(1));
            BoatPlanner.Made b = BoatPlanner.safe(input('A', 99, level.id()), level, 2, 1, new GenRandom(2));
            assertEquals(track(a.plan), track(b.plan), level + ": the same drive cells for the same orientation");
            assertEquals(course(a.plan).targets(), course(b.plan).targets(), level + ": and the same course");
            assertNotEquals(a.plan.hash(), b.plan.hash(), level + ": the trees differ with the seed");
        }
    }

    private static Set<String> track(Plan p) {
        Set<String> out = new HashSet<>();
        for (BlockOp op : p.ops()) {
            String b = p.blockOf(op);
            if (b.equals(Palette.TRACK) || b.equals(Palette.TRACK_FAST) || b.equals(Palette.SAND)) {
                out.add(op.x() + " " + op.y() + " " + op.z() + " " + b);
            }
        }
        return out;
    }

    @Test
    void aSeedWhoseTriesAllFailGetsTheSafeSpiral() throws GenFailed {
        // a work budget that leaves nothing for a try but the safe spiral's share
        PlanInput tight = new PlanInput(SLOT, SLOT.half('A'), 'A', 20725, 0, 77, "medium", 6,
                BoatPlanner.SAFE_RESERVE, null);
        Plan p = PLANNER.plan(tight);
        assertEquals(BoatPlanner.TRIES + 1, p.work(), "no try fitted the budget: the safe spiral");
        assertEquals(null, problem(p, BoatPlanner.Level.MEDIUM), "and it is proven like any other");
        assertTrue(p.summary().get(0).contains("safe spiral"), "the summary says so: " + p.summary());
    }

    // ---- tags, tiers, cancels ---------------------------------------------------------------------------

    @Test
    void theBootCheckMakesTheLiveLayoutAgainFromItsTag() throws GenFailed {
        Plan live = PLANNER.plan(input('A', 31, "medium"));
        GenTag tag = new GenTag(SLOT.id(), Slots.BOAT, BoatPlanner.ALGO, 20725, 0, 31, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertEquals(live.hash(), PLANNER.rederive(input('A', 0, "hard"), tag).hash(), "from the tag, any tier asked");
        assertEquals(live.hash(), PLANNER.rederive(input('A', 0, "medium"), tag).hash(), "the stored tier first");
        assertTrue(RaceStand.has(tag), "a Mountain Run layout has its viewing stand (algo 3)");
        GenTag loop = new GenTag(SLOT.id(), Slots.BOAT, 2, 20725, 0, 31, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertTrue(RaceStand.has(loop), "and so has an algo-2 loop, at the same fixed spot");
        assertThrows(GenFailed.class, () -> PLANNER.rederive(input('A', 0, "medium"), loop),
                "an algo-2 loop isn't made again by the Mountain Run's planner (it heals by a scan)");
        GenTag next = new GenTag(SLOT.id(), Slots.BOAT, BoatPlanner.ALGO + 1, 20725, 0, 31, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> PLANNER.rederive(input('A', 0, "medium"), next), "nor a later version's");
    }

    @Test
    void aWrongTierOrACancelFailsCleanly() {
        assertThrows(GenFailed.class, () -> PLANNER.plan(input('A', 1, "EEE")), "a golf mix isn't a boat tier");
        PlanInput cancelled = new PlanInput(SLOT, SLOT.half('A'), 'A', 1, 0, 1, "medium", 6, 0, () -> true);
        assertThrows(GenFailed.class, () -> PLANNER.plan(cancelled), "a cancelled job gives up");
        PlanInput small = new PlanInput(SLOT, new Box(0, 0, 0, 99, 15, 99), 'A', 1, 0, 1, "medium", 6, 0, null);
        assertThrows(GenFailed.class, () -> PLANNER.plan(small), "an area smaller than 128 x 16 x 128 is refused");
        assertEquals(Slots.BOAT, PLANNER.id(), "its id");
        assertEquals(BoatPlanner.ALGO, PLANNER.algo(), "its version");
    }

    // ---- the tiers' contents ----------------------------------------------------------------------------

    @Test
    void eachTierHasItsDropsAndOverManySeedsItsPieces() throws GenFailed {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            Map<String, Integer> seen = new HashMap<>();
            for (int day = 0; day < 60; day++) {
                long seed = GenSeed.seed(SECRET, 40_000 + day, SLOT.id(), 0);
                PlanInput in = input('A', seed, level.id());
                BoatPlanner.Made m = BoatPlanner.attempt(in, level, new GenRandom(seed), 0, BoatPlanner.Richness.FULL);
                if (m == null) {
                    continue;
                }
                int bigs = 0;
                int fall = 0;
                for (TrackProfile.Lip l : m.profile.lips) {
                    bigs += l.drop() == 2 ? 1 : 0;
                    fall += l.drop();
                    assertTrue(l.drop() >= 1 && l.drop() <= level.proof().maxDrop(), level + ": drops of 1 or 2 (easy 1)");
                }
                assertTrue(bigs <= level.proof().bigDrops(), level + ": at most the tier's 2s");
                assertTrue(fall <= level.proof().descent(), level + ": at most the tier's fall");
                seen.merge("drops", 1, Integer::sum);
                seen.merge("bigs", bigs, Integer::sum);
                for (TrackPieces.Kind k : TrackPieces.Kind.values()) {
                    seen.merge(k.name(), m.pieces.count(k), Integer::sum);
                }
                seen.merge("runoffs", m.pieces.runoffs(), Integer::sum);
                seen.merge("kerbs", m.pieces.kerbs(), Integer::sum);
                seen.merge("trees", m.trees, Integer::sum);
            }
            assertTrue(seen.getOrDefault("drops", 0) >= 50, level + ": most first tries come together: " + seen);
            assertTrue(seen.getOrDefault("runoffs", 0) > 0, level + ": sandy bends: " + seen);
            assertTrue(seen.getOrDefault("trees", 0) > 0, level + ": trees: " + seen);
            assertTrue(seen.getOrDefault("SPLIT", 0) > 0, level + ": pick-a-path splits: " + seen);
            switch (level) {
                case EASY -> {
                    assertEquals(0, seen.getOrDefault("bigs", 0), "easy drops are all 1s");
                    assertEquals(0, seen.getOrDefault("FOREST", 0) + seen.getOrDefault("BOOST", 0),
                            "easy has no forest and no boost strips");
                    assertEquals(0, seen.getOrDefault("kerbs", 0), "nor kerbs");
                }
                case MEDIUM -> {
                    assertTrue(seen.getOrDefault("BOOST", 0) > 0 && seen.getOrDefault("FOREST", 0) > 0,
                            "medium has boost strips and forests: " + seen);
                    assertTrue(seen.getOrDefault("bigs", 0) > 0, "and some Big Drops: " + seen);
                }
                case HARD -> {
                    assertTrue(seen.getOrDefault("CAVE", 0) > 0 && seen.getOrDefault("SAND_PIT", 0) > 0
                            && seen.getOrDefault("FOREST", 0) > 0, "hard has caves, sand pits and forests: " + seen);
                    assertEquals(0, seen.getOrDefault("BOOST", 0), "and no boost strips (it's all blue)");
                    assertTrue(seen.getOrDefault("bigs", 0) > 0, "and Big Drops: " + seen);
                }
            }
        }
    }

    @Test
    void theFinalDropSignStandsOnlyWhereTheGoldFinishLineComesNext() throws GenFailed {
        // Review B1: the Final Drop is the last drop the landing strips let in, which on easy (two legs
        // on) and hard (a lap on) is far from the finish. "FINAL DROP! / Then the gold / finish line!"
        // there would be untrue, so such a drop gets its own HOP! or BIG DROP! sign.
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int inFront = 0;
            for (int day = 0; day < 40; day++) {
                long seed = GenSeed.seed(SECRET, 20_000 + day, SLOT.id(), 0);
                BoatPlanner.Made m = BoatPlanner.made(input(day % 2 == 0 ? 'A' : 'B', seed, level.id()));
                TrackProfile.Lip last = m.profile.last();
                double after = m.profile.finish - last.s();
                long finals = m.plan.signs().stream().filter(s -> s.lines().equals(GenCopy.boatFinalDrop())).count();
                assertEquals(after <= 70 ? 1 : 0, finals, level + " day " + day + ": the finish is " + Math.round(after)
                        + " after the Final Drop, so " + (after <= 70 ? "one FINAL DROP! sign" : "no FINAL DROP! sign"));
                if (after <= 70) {
                    inFront++;
                    assertTrue(after >= BoatEnvelope.zone(last.drop()) + 3, level + ": and it is Z(d) + 3 before the finish");
                } else {
                    long own = m.plan.signs().stream().filter(s -> s.lines().equals(GenCopy.boatDrop(last.drop()))).count();
                    assertTrue(own >= 1, level + " day " + day + ": the far Final Drop has its own drop sign");
                }
            }
            if (level == BoatPlanner.Level.MEDIUM) {
                assertTrue(inFront >= 8, "medium's Final Drop is in front of the stand where its sixth leg holds one: "
                        + inFront + " of 40");
            }
        }
    }

    @Test
    void everyDropAndEveryPieceHasItsSignJustBeforeIt() throws GenFailed {
        // Review B2: a piece starting a few blocks into its straight had its whole sign window on the
        // bend before it, and bends had no sign spots, so a quarter of splits went unsigned.
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            List<String> bad = new ArrayList<>();
            for (int day = 0; day < 100; day++) {
                long seed = GenSeed.seed(SECRET, 20_000 + day, SLOT.id(), 0);
                BoatPlanner.Made m = BoatPlanner.made(input(day % 2 == 0 ? 'A' : 'B', seed, level.id()));
                String why = signProblem(m);
                if (why != null) {
                    bad.add("day " + day + ": " + why);
                }
            }
            assertTrue(bad.isEmpty(), level + ": every drop and piece is signed 3-10 blocks before it (§8); "
                    + bad.size() + " days not, first " + bad.subList(0, Math.min(5, bad.size())));
        }
    }

    @Test
    void aBasicTryHasOnlyTheDropsTheBendsAndTheScenery() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int made = 0;
            for (int day = 0; day < 10; day++) {
                long seed = GenSeed.seed(SECRET, 50_000 + day, SLOT.id(), 0);
                BoatPlanner.Made m = BoatPlanner.attempt(input('A', seed, level.id()), level, new GenRandom(seed), 17,
                        BoatPlanner.Richness.BASIC);
                if (m == null) {
                    continue;
                }
                made++;
                assertTrue(m.pieces.list.isEmpty(), level + ": no pieces on a basic try");
                assertEquals(List.of(), DownhillValidator.problems(m.plan, level.id()), level + ": and it's proven");
            }
            assertTrue(made >= 8, level + ": basic tries come together: " + made);
        }
    }
}
