package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.event.RaceTrack;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mountain Run v2's planner (MOUNTAIN-V2-SPEC §5.2, §15; BoatPlanner algo 4): pinned layouts for three
 * seeds of each style at each tier, every one proven; the same seed twice the same blocks; the same blocks
 * wherever the half is; the twelve safe layouts proven in both halves and off the shipped range; the work
 * budget and cancels; the summary; and BoatPlanner's dispatch between the mountain and the spiral.
 */
class MountainPlannerTest {

    static final Box HALF_A = new Box(6080, 96, 2880, 6559, 271, 3519);
    static final Box HALF_B = new Box(7136, 96, 2880, 7615, 271, 3519);
    static final Box ORIGIN = new Box(0, 96, 0, 479, 271, 639);
    static final Box FAR = new Box(20000, 96, -20000, 20479, 271, -19361);
    /** Three seeds of each style (BoatStyle.of): roads 2, 4, 5; slaloms 1, 3, 8. */
    static final long[] ROADS = {2, 4, 5};
    static final long[] SLALOMS = {1, 3, 8};

    private static final Map<String, MountainPlanner.Made> MADE = new ConcurrentHashMap<>();

    static PlanInput input(Box half, long seed, String tier) {
        return new PlanInput(Slots.ICE_BOAT, half, half == HALF_B ? 'B' : 'A', 20725, 0, seed, tier, 6, 0, null);
    }

    static MountainPlanner.Made made(Box half, long seed, String tier) {
        return MADE.computeIfAbsent(half.minX() + ":" + half.minZ() + ":" + seed + ":" + tier, k -> {
            try {
                return MountainPlanner.made(input(half, seed, tier));
            } catch (GenFailed e) {
                throw new AssertionError(tier + " seed " + seed + ": " + e.getMessage(), e);
            }
        });
    }

    // ---- the pinned layouts --------------------------------------------------------------------------------

    @Test
    void goldenHashesPinThreeSeedsPerStyleAndTier() {
        // A change here means the planner makes different layouts: Mountain Run v2 is unreleased (it ships
        // off), so re-pin with the change; once it is on, bump BoatPlanner.ALGO instead. Re-pinned for audit M00
        // (the HALFWAY! sign by BoatHype.halfway's checkpoint, measured from checkpoint 1): 10 of 18 moved. Then
        // once for the FX-MTN fixes: every road moved (the tier's pieces placed on bends too, MTN-R3-00; SANDY
        // BEND signs, R3-02; the chicane sign's hand, MTN00; road medium 4 and 5 also by the chicane cap, MTN01);
        // the slaloms didn't.
        String golden = """
                road easy fd007631bde5 43d2dd016bcb 8ee1381b7442
                road medium f277c9be3f41 475f73605cc8 4da8daecf8f3
                road hard 9fe15e052a31 2f304cc00fd1 643a210adc0f
                slalom easy e5437e73e554 52be95a3b5fd 0df883d71e01
                slalom medium f874b0413052 c1208132055e 2a1bdd16f106
                slalom hard fe19c45a486d e6cb568e821d 92c0cf205316
                """;
        StringBuilder got = new StringBuilder();
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                got.append(style.id()).append(' ').append(tier);
                for (long seed : style == BoatStyle.ROAD ? ROADS : SLALOMS) {
                    assertEquals(style, BoatStyle.of(seed), seed + " is a " + style + " seed");
                    got.append(' ').append(made(HALF_A, seed, tier).finished().hash().substring(0, 12));
                }
                got.append('\n');
            }
        }
        assertEquals(golden, got.toString(), "every style, tier and seed (if this changed, re-pin or bump ALGO)");
        assertEquals(4, BoatPlanner.ALGO, "the version these hashes were pinned at");
    }

    @Test
    void everyPinnedLayoutIsProvenAndInsideItsNumbers() {
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                for (long seed : style == BoatStyle.ROAD ? ROADS : SLALOMS) {
                    MountainPlanner.Made m = made(HALF_A, seed, tier);
                    Plan p = m.finished();
                    String name = style + " " + tier + " seed " + seed;
                    assertEquals(List.of(), MountainValidator.problems(p, tier), name + ": proven");
                    assertEquals(List.of(), BoatValidator.problems(p, input(HALF_A, seed, tier)),
                            name + ": and by the engine's own dispatch");
                    assertEquals(BoatPlanner.ALGO, p.algo(), name + ": algo 4");
                    assertTrue(p.ops().size() <= MountainValidator.MAX_OPS, name + ": " + p.ops().size() + " blocks");
                    Course c = ((PlannedTrial) p.course()).course();
                    assertTrue(c.checkpoints().size() <= MountainValidator.MAX_CHECKPOINTS,
                            name + ": " + c.checkpoints().size() + " checkpoints");
                    assertTrue(p.keepClear().size() <= MountainValidator.MAX_BOXES, name + ": keep-clear boxes");
                    for (Box b : p.keepClear()) {
                        assertTrue(p.half().contains(b), name + ": a keep-clear box inside the half " + b);
                    }
                    MountainTier mt = MountainTier.of(style, tier);
                    assertTrue(m.seconds >= mt.tMin && m.seconds <= mt.tMax,
                            name + ": T_m " + m.seconds + " in the tier's window");
                    assertTrue(m.cand.flow().passes(), name + ": the flow score passes: " + m.cand.flow().failed);
                    assertTrue(MountainPlanner.clearance(m.cand.sk(), m.cand.drops()),
                            name + ": no corridor within clearance of another");
                    long ref = ((PlannedTrial) p.course()).refMs();
                    assertEquals(MountainPlanner.modelMs(m.seconds) * 4 / 5, ref,
                            name + ": the stars' reference is 0.8 T_m (red-team F04)");
                    assertTrue(ref >= c.minSeconds() * 1000L + 1000, name + ": over the shortest time");
                    assertEquals(MountainPlanner.modelMs(m.seconds), ref * 5 / 4, name + ": T_m back from it exactly");
                    assertTrue(p.work() > 0 && p.work() <= MountainPlanner.BUDGET, name + ": work " + p.work());
                }
            }
        }
    }

    @Test
    void theSameSeedMakesTheSameLayout() throws GenFailed {
        Plan once = made(HALF_A, 2, "medium").finished();
        Plan again = MountainPlanner.made(input(HALF_A, 2, "medium")).finished();
        assertEquals(once.hash(), again.hash(), "the same seed, tier and half: the same blocks");
        assertEquals(once.summary(), again.summary(), "and the same summary");
        Plan other = made(HALF_A, 4, "medium").finished();
        assertNotEquals(once.hash(), other.hash(), "another seed: another layout");
    }

    @Test
    void theLayoutIsTheSameWhereverTheHalfIs() {
        for (long seed : new long[]{2, 3}) {
            Plan a = made(HALF_A, seed, "medium").finished();
            for (Box at : List.of(HALF_B, ORIGIN, FAR)) {
                Plan b = made(at, seed, "medium").finished();
                assertEquals(PlanShift.to(a, at).hash(), b.hash(), "seed " + seed + " at " + at.describe()
                        + ": the shipped half's blocks, moved");
                assertEquals(relative(a), relative(b), "seed " + seed + ": op for op");
            }
        }
    }

    private static List<String> relative(Plan p) {
        List<String> out = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            out.add((op.x() - p.half().minX()) + " " + (op.y() - p.half().minY()) + " " + (op.z() - p.half().minZ())
                    + " " + p.palette().get(op.state()));
        }
        return out;
    }

    // ---- the safe layouts ----------------------------------------------------------------------------------

    @Test
    void theTwelveSafeLayoutsAreProvenInBothHalvesAndOffRange() throws GenFailed {
        List<String> moved = new ArrayList<>();
        for (BoatStyle style : BoatStyle.values()) {
            long seed = style == BoatStyle.ROAD ? 2 : 1;
            for (String tier : List.of("easy", "medium", "hard")) {
                MountainTier mt = MountainTier.of(style, tier);
                for (boolean west : new boolean[]{true, false}) {
                    String name = mt + (west ? " west" : " east");
                    int stream = MountainPlanner.safeStream(mt, west);
                    MountainPlanner.Candidate fixed = MountainPlanner.candidate(
                            new GenRandom(MountainPlanner.SAFE_BASE + stream), mt, 0);
                    if (fixed == null || fixed.sk().frame.west != west) {
                        int next = -1;
                        for (int j = 0; j < MountainPlanner.SAFE_SEARCH && next < 0; j++) {
                            MountainPlanner.Candidate c = MountainPlanner.candidate(
                                    new GenRandom(MountainPlanner.SAFE_BASE + j), mt, 0);
                            next = c != null && c.sk().frame.west == west ? j : -1;
                        }
                        moved.add(name + ": stream " + stream + " no longer passes; the first that does is " + next);
                        continue;
                    }
                    MountainPlanner.Candidate c = MountainPlanner.safeCandidate(mt, west);
                    assertTrue(c.flow().passes() && c.flow().missing.isEmpty(), name + ": every flow gate (F06)");
                    PlanInput in = input(HALF_A, seed, tier);
                    MountainPlanner.Made m = MountainPlanner.attempt(in, mt, c, new GenRandom(seed).fork("pieces:safe"),
                            new GenRandom(seed).fork("scenery:safe"), MountainPlanner.BASIC);
                    assertNotNull(m, name + ": built and proven in half A");
                    for (Box at : List.of(HALF_B, ORIGIN, FAR)) {
                        Plan there = PlanShift.to(m.plan, at);
                        assertEquals(List.of(), MountainValidator.problems(there, tier),
                                name + ": proven at " + at.describe());
                    }
                }
            }
        }
        assertEquals(List.of(), moved, "MountainPlanner.SAFE_STREAMS is current");
    }

    @Test
    void aBudgetThatLeavesOnlyTheReserveGetsTheSafeLayout() throws GenFailed {
        PlanInput tight = new PlanInput(Slots.ICE_BOAT, HALF_A, 'A', 20725, 0, 2, "medium", 6,
                MountainPlanner.SAFE_RESERVE, null);
        MountainPlanner.Made m = MountainPlanner.made(tight);
        Plan p = m.finished();
        assertEquals(MountainPlanner.SAFE_RESERVE, p.work(), "no Stage A fitted: the safe layout's reserve");
        assertTrue(p.summary().get(0).contains("the safe road"), "the summary says so: " + p.summary().get(0));
        assertEquals(List.of(), MountainValidator.problems(p, "medium"), "and it is proven like any other");
        PlanInput small = new PlanInput(Slots.ICE_BOAT, HALF_A, 'A', 20725, 0, 1, "hard", 6,
                MountainPlanner.SAFE_RESERVE + 30, null);
        Plan q = MountainPlanner.made(small).finished();
        assertTrue(q.work() <= MountainPlanner.SAFE_RESERVE + 30, "the work stays in the budget: " + q.work());
        assertTrue(q.summary().get(0).contains("the safe slalom"), "30 candidates and no build: " + q.summary().get(0));
    }

    @Test
    void aCancelOrAWrongAreaFailsCleanly() {
        PlanInput cancelled = new PlanInput(Slots.ICE_BOAT, HALF_A, 'A', 20725, 0, 2, "medium", 6, 0, () -> true);
        assertThrows(GenFailed.class, () -> MountainPlanner.made(cancelled), "a cancelled job gives up");
        int[] calls = {0};
        PlanInput later = new PlanInput(Slots.ICE_BOAT, HALF_A, 'A', 20725, 0, 2, "medium", 6, 0,
                () -> ++calls[0] > 30);
        assertThrows(GenFailed.class, () -> MountainPlanner.made(later), "a cancel in the middle of the search too");
        Box small = new Box(6080, 96, 2880, 6079 + 448, 271, 3519);
        assertThrows(GenFailed.class, () -> MountainPlanner.made(input(small, 2, "medium")), "not 480 wide");
        Box unaligned = new Box(6088, 96, 2880, 6567, 271, 3519);
        assertThrows(GenFailed.class, () -> MountainPlanner.made(input(unaligned, 2, "medium")), "off a chunk corner");
        assertThrows(GenFailed.class, () -> MountainPlanner.made(input(HALF_A, 2, "EEE")), "a golf mix isn't a tier");
    }

    /** Extra hard roads for the pieces' floors (hard is the tier with the least room for them). */
    static final long[] HARD_PIECES = {18, 24};

    @Test
    void theRoadsGetTheirTiersPieces() {
        // audit MTN-R3-00: most of the §5.3 deck went unplaced (only the lips' straights were free, the free list
        // never shrank, pits and boosts were dealt last); now wide bends take pieces too, the deck is dealt the
        // scarcest first, a card takes the tightest stretch it fits and, failing that, its shortest length. The
        // soak (60 seeds a tier) placed 93% of the easy deck, 87% of medium's and 79% of hard's
        for (String tier : List.of("easy", "medium", "hard")) {
            List<Long> seeds = new ArrayList<>();
            for (long seed : ROADS) {
                seeds.add(seed);
            }
            if (tier.equals("hard")) {
                for (long seed : HARD_PIECES) {
                    seeds.add(seed);
                }
            }
            int placed = 0;
            int dealt = 0;
            int twoPits = 0;
            int twoSplits = 0;
            for (long seed : seeds) {
                MountainPlanner.Made m = made(HALF_A, seed, tier);
                PiecesV4 p = m.pieces;
                String name = "road " + tier + " seed " + seed + " (" + m.how + ")";
                int here = 0;
                int deck = 0;
                for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
                    assertTrue(p.count(k) <= p.dealt(k), name + ": no more " + k + " than dealt");
                    here += p.count(k);
                    deck += p.dealt(k);
                }
                assertTrue(m.finished().summary().get(2).contains("pieces " + here + " of " + deck + " (pits "
                        + p.count(PiecesV4.Kind.SAND_PIT) + "/" + p.dealt(PiecesV4.Kind.SAND_PIT) + ", "),
                        name + ": the summary says what was placed of what was dealt: "
                                + m.finished().summary().get(2));
                assertTrue(deck > 0 && here * 2 >= deck, name + ": " + here + " of a deck of " + deck);
                assertTrue(p.count(PiecesV4.Kind.SAND_PIT) >= 1, name + ": a sand pit");
                assertTrue(p.count(PiecesV4.Kind.CAVE) + p.count(PiecesV4.Kind.TUNNEL) >= 1,
                        name + ": a cave or a tunnel");
                if (!tier.equals("easy")) {
                    assertTrue(p.count(PiecesV4.Kind.FOREST) >= 1, name + ": a forest");
                    assertTrue(p.count(PiecesV4.Kind.SPLIT) >= 1, name + ": a split");
                }
                if (tier.equals("medium")) {
                    assertTrue(p.count(PiecesV4.Kind.BOOST) >= 1, name + ": a boost strip");
                }
                placed += here;
                dealt += deck;
                twoPits += p.count(PiecesV4.Kind.SAND_PIT) >= 2 ? 1 : 0;
                twoSplits += p.count(PiecesV4.Kind.SPLIT) >= 2 ? 1 : 0;
            }
            assertTrue(placed * 4 >= dealt * 3, "road " + tier + ": " + placed + " pieces of " + dealt + " dealt");
            if (tier.equals("hard")) {
                assertTrue(twoPits * 2 > seeds.size(), "hard: two sand pits or more in most: " + twoPits);
                assertTrue(twoSplits * 2 > seeds.size(), "hard: two splits in most: " + twoSplits);
            }
        }
    }

    @Test
    void aTightBendWithRunOffSandHasASandyBendSign() {
        // audit MTN-R3-02: v3 signed every run-off bend; v2 signed only hairpins and chicanes
        int bends = 0;
        for (String tier : List.of("easy", "medium", "hard")) {
            for (long seed : ROADS) {
                MountainPlanner.Made m = made(HALF_A, seed, tier);
                Skeleton sk = m.cand.sk();
                String name = "road " + tier + " seed " + seed;
                List<Double> sandy = new ArrayList<>();
                for (SignText t : m.finished().signs()) {
                    if (t.lines().equals(GenCopy.boatSandyBend())) {
                        sandy.add(along(m, t));
                    }
                }
                List<Double> want = new ArrayList<>();
                double tight = Double.NEGATIVE_INFINITY;
                for (Centreline.Element e : sk.line.elements()) {
                    boolean runoff = e.arc() && m.pieces.runoff[e.index] > 0;
                    Skeleton.Role role = sk.tag(e).role();
                    if (runoff && role != Skeleton.Role.HAIRPIN && role != Skeleton.Role.CHICANE
                            && e.s0 - tight > RasterV4.SIGN_BEFORE && e.s0 > sk.start && e.s0 < sk.finish) {
                        want.add(e.s0);
                    }
                    tight = runoff ? e.s1() : tight;
                }
                for (double w : want) {
                    assertTrue(sandy.stream().anyMatch(u -> w - u > 0 && w - u <= RasterV4.SIGN_BEFORE + 2),
                            name + ": a SANDY BEND sign before the tight bend at " + Math.round(w));
                }
                for (double u : sandy) {
                    assertTrue(want.stream().anyMatch(w -> w - u > 0 && w - u <= RasterV4.SIGN_BEFORE + 2),
                            name + ": the SANDY BEND sign at " + Math.round(u) + " stands before a tight bend");
                }
                bends += want.size();
            }
        }
        assertTrue(bends >= 6, "fixture: tight bends were read: " + bends);
    }

    @Test
    void aRefusedBuildLeavesOutOnlyThePiecesNearWhereItFailed() {
        // hard road 9046: the best-fit sand pit at 2536-2564 pushed checkpoint 58 up against a 1-block lip and the
        // proof refused the run ("no way along the track at one level from checkpoint 58 to checkpoint 59"); the
        // fewer-pieces build put the pit in the same place, so the road shipped with no pieces at all
        MountainPlanner.Made m = made(HALF_A, 9046, "hard");
        Plan p = m.finished();
        assertEquals(List.of(), MountainValidator.problems(p, "hard"), "proven");
        assertTrue(m.how.contains("build 1 (1 piece left out for the proof)"),
                "the first build, less one piece: " + m.how);
        assertEquals(1, m.leftOut, "one piece left out");
        int dealt = 0;
        for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
            dealt += m.pieces.dealt(k);
        }
        assertEquals(dealt - 1, m.pieces.list.size(), "the rest of the deck kept: " + p.summary().get(2));
        MountainTier mt = MountainTier.of(BoatStyle.ROAD, "hard");
        assertTrue(m.seconds >= mt.tMin && m.seconds <= mt.tMax, "T_m " + m.seconds + " in the window");
        // where a problem points: between the checkpoints it names, or round the blocks it names, a leg either way
        RasterV4.Spot a = m.checkpoints.get(57);
        RasterV4.Spot b = m.checkpoints.get(58);
        List<double[]> near = MountainPlanner.near(m, List.of("no way from checkpoint 58 to checkpoint 59"));
        assertEquals(1, near.size(), "one stretch");
        assertEquals(a.s() - MountainPlanner.LEAVE_OUT_REACH, near.get(0)[0], 1e-9, "a leg before the first");
        assertEquals(b.s() + MountainPlanner.LEAVE_OUT_REACH, near.get(0)[1], 1e-9, "a leg past the second");
        int x = (int) Math.floor(HALF_A.minX() + b.x());
        int z = (int) Math.floor(HALF_A.minZ() + b.z());
        List<double[]> block = MountainPlanner.near(m, List.of("a side pocket at " + x + " 150 " + z + ": wide"));
        assertEquals(b.s(), (block.get(0)[0] + block.get(0)[1]) / 2, 1.5, "round the block named");
        assertEquals(List.of(), MountainPlanner.near(m, List.of("the plan isn't a boat course")), "nowhere named");
    }

    @Test
    void theDeckIsTheTiersAndMediumAlwaysGetsACaveOrATunnel() {
        // §5.3's pieces per road tier; medium's "cave or tunnel 1-2" dealt neither now and then (audit MTN-R3-00)
        for (String tier : List.of("easy", "medium", "hard")) {
            MountainTier mt = MountainTier.of(BoatStyle.ROAD, tier);
            for (int i = 0; i < 400; i++) {
                Map<PiecesV4.Kind, Integer> d = PiecesV4.deal(new GenRandom(0xDEC4L + i), mt, MountainPlanner.FULL);
                String name = tier + " deal " + i + " " + d;
                within(d.get(PiecesV4.Kind.SAND_PIT), mt.pits, name + ": sand pits");
                within(d.get(PiecesV4.Kind.SPLIT), mt.splits, name + ": splits");
                within(d.get(PiecesV4.Kind.FOREST), mt.forests, name + ": forests");
                assertTrue(d.get(PiecesV4.Kind.BOOST) >= mt.boostMin && d.get(PiecesV4.Kind.BOOST) <= mt.boostMax,
                        name + ": boost strips");
                int roofed = d.get(PiecesV4.Kind.CAVE) + d.get(PiecesV4.Kind.TUNNEL);
                if (tier.equals("medium")) {
                    assertTrue(roofed >= 1 && roofed <= 2, name + ": a cave or a tunnel, 1-2");
                } else {
                    within(d.get(PiecesV4.Kind.CAVE), mt.caves, name + ": caves");
                    within(d.get(PiecesV4.Kind.TUNNEL), mt.tunnels, name + ": tunnels");
                }
            }
        }
    }

    private static void within(int n, int[] range, String what) {
        assertTrue(n >= range[0] && n <= range[1], what + ": " + n + ", " + range[0] + "-" + range[1]);
    }

    @Test
    void cancelsAreCheckedInsideEveryLongStageAndChangeNoBlock() throws GenFailed {
        // audit MTN04: the raster's passes, the pieces' trials, the mountain and the proof each check (and so
        // give GenService's online throttle its turn), and a check only throws or sleeps
        Set<String> from = new HashSet<>();
        int[] calls = {0};
        PlanInput watched = new PlanInput(Slots.ICE_BOAT, HALF_A, 'A', 20725, 0, 2, "medium", 6, 0, () -> {
            calls[0]++;
            StackWalker.getInstance().forEach(f -> from.add(f.getClassName().replaceAll(".*\\.", "")));
            return false;
        });
        Plan p = MountainPlanner.made(watched).finished();
        assertEquals(made(HALF_A, 2, "medium").finished().hash(), p.hash(), "the checks change no block");
        List<String> stages = List.of("RasterV4", "PiecesV4", "MountainScenery", "MountainValidator$Survey");
        for (String stage : stages) {
            assertTrue(from.contains(stage), "a check inside " + stage + " (" + calls[0] + " checks): " + from);
        }
        // and a cancel there ends the job as a cancel: never a refusal, the next candidate or the safe layout
        for (String stage : stages) {
            PlanInput stop = new PlanInput(Slots.ICE_BOAT, HALF_A, 'A', 20725, 0, 2, "medium", 6, 0,
                    () -> StackWalker.getInstance().walk(f -> f.anyMatch(x -> x.getClassName().endsWith("." + stage))));
            GenFailed e = assertThrows(GenFailed.class, () -> MountainPlanner.made(stop), "a cancel inside " + stage);
            assertEquals("cancelled", e.getMessage(), "a cancel inside " + stage + " is a cancel");
        }
    }

    @Test
    void theSummarySaysWhatWasMade() {
        Plan road = made(HALF_A, 2, "medium").finished();
        List<String> s = road.summary();
        assertTrue(s.get(0).startsWith("Ice Boat v4 medium: Winding Road, "), "style and tier: " + s.get(0));
        assertTrue(s.get(0).contains(" bands (") && s.get(0).contains("hairpins"), "its bands and links: " + s.get(0));
        assertTrue(s.get(1).startsWith("flow 0.") && s.get(1).contains("model T_m "), "the flow line: " + s.get(1));
        assertTrue(s.get(2).startsWith("drops ") && s.get(2).contains("Final Drop") && s.get(2).contains("pits "),
                "drops and pieces: " + s.get(2));
        assertTrue(s.get(3).contains("checkpoints") && s.get(3).contains("stand at the bottom"), "the course: " + s.get(3));
        assertTrue(s.get(4).startsWith("reference ") && s.get(4).contains("0.8 of T_m"), "the times: " + s.get(4));
        List<String> slalom = made(HALF_A, 1, "medium").finished().summary();
        assertTrue(slalom.get(0).contains("Slalom") && slalom.get(2).contains("gate sets"), "a slalom's: " + slalom);
    }

    // ---- the HALFWAY! sign and the "Halfway!" title (audit M00) ------------------------------------------

    /**
     * The seeds the audit found the sign and the title a checkpoint apart on (easy road 5 and 19, medium slalom
     * 26, hard road 13 and 16), the grid that moved the title (medium road 2), and the one with no sign at all
     * (medium slalom 23), besides every pinned layout; and easy slalom 20, whose checkpoint's own spot is taken
     * by another sign, so its HALFWAY! stands a few blocks along (audit MTN03: no plan of 600 went without).
     */
    static final List<Object[]> HALFWAY_SEEDS = List.of(new Object[]{"easy", 19L}, new Object[]{"medium", 23L},
            new Object[]{"medium", 26L}, new Object[]{"hard", 13L}, new Object[]{"hard", 16L},
            new Object[]{"easy", 20L});

    @Test
    void theHalfwaySignStandsByTheCheckpointEveryRacerHearsHalfwayAt() {
        List<Object[]> runs = new ArrayList<>(HALFWAY_SEEDS);
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                for (long seed : style == BoatStyle.ROAD ? ROADS : SLALOMS) {
                    runs.add(new Object[]{tier, seed});
                }
            }
        }
        for (Object[] run : runs) {
            String tier = (String) run[0];
            long seed = (Long) run[1];
            String name = tier + " " + BoatStyle.of(seed).id() + " seed " + seed;
            Plan p = made(HALF_A, seed, tier).finished();
            PlannedTrial pt = (PlannedTrial) p.course();
            Course c = pt.course().withGen(new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, BoatPlanner.ALGO, 20725, 0, seed,
                    'A', p.hash(), pt.refMs(), 2, 3, List.of(), List.of(), 0, 7));
            List<SignText> halfway = p.signs().stream().filter(t -> t.lines().equals(GenCopy.boatHalfway())).toList();
            assertEquals(1, halfway.size(), name + ": one HALFWAY! sign");
            SignText sign = halfway.get(0);
            Point at = new Point(sign.x() + 0.5, sign.y(), sign.z() + 0.5);
            int near = 0;
            List<Course.Mark> cps = c.checkpoints();
            for (int i = 1; i < cps.size(); i++) {
                if (cps.get(i).center().distance(at) < cps.get(near).center().distance(at)) {
                    near = i;
                }
            }
            int title = BoatHype.halfway(c);
            assertEquals(near, title, name + ": the sign stands by the checkpoint the \"Halfway!\" title shows at");
            assertEquals(BoatHype.HALFWAY, BoatHype.checkpointTitle(c, title), name + ": \"Halfway!\" there");
            RaceGrid.Grid grid = RaceGrid.forCourse(c, new PlanSurface(p), RaceGrid.MAX_SPOTS);
            assertTrue(grid.size() >= 2, name + ": fixture: a grid");
            for (int k = 0; k < grid.size(); k++) {
                Course raced = RaceTrack.raced(c, grid.spot(k), 0);
                assertEquals(title, BoatHype.halfway(raced), name + ": grid spot " + (k + 1) + " hears it there too");
                assertTrue(BoatHype.loud(raced, title), name + ": loud from grid spot " + (k + 1));
            }
        }
    }

    // ---- the chicane and staircase signs, the chicane cap (audit MTN00-MTN02) ------------------------------

    /** The first element of every chicane beat (its first arc), in order along. */
    static List<Centreline.Element> chicanes(Skeleton sk) {
        List<Centreline.Element> out = new ArrayList<>();
        int beat = -1;
        for (Centreline.Element e : sk.line.elements()) {
            Skeleton.Tag tag = sk.tag(e);
            if (tag.role() == Skeleton.Role.CHICANE && tag.beat() != beat) {
                out.add(e);
                beat = tag.beat();
            }
        }
        return out;
    }

    /** How far along the centreline a sign stands (its nearest point to the sign's column). */
    static double along(MountainPlanner.Made m, SignText t) {
        Centreline.Near near = m.cand.sk().line.nearest(t.x() - m.in.half().minX() + 0.5,
                t.z() - m.in.half().minZ() + 0.5);
        assertNotNull(near, "a sign beside the lane: " + t);
        return near.s();
    }

    @Test
    void theChicaneSignSaysWhichWayTheFirstBendGoes() {
        assertEquals(List.of("CHICANE", "Right, left!"), GenCopy.boatChicane(true), "a chicane that bends right first");
        assertEquals(List.of("CHICANE", "Left, right!"), GenCopy.boatChicane(false), "and left first");
        assertTrue(GenCopy.everySign().contains(GenCopy.boatChicane(true))
                && GenCopy.everySign().contains(GenCopy.boatChicane(false)), "both in the copy test's list");
        int[] hands = new int[2];
        for (String tier : List.of("medium", "hard")) {
            for (long seed : ROADS) {
                MountainPlanner.Made m = made(HALF_A, seed, tier);
                String name = "road " + tier + " seed " + seed;
                List<Centreline.Element> firsts = chicanes(m.cand.sk());
                List<SignText> signs = m.finished().signs().stream()
                        .filter(t -> t.lines().get(0).equals("CHICANE")).toList();
                assertEquals(firsts.size(), signs.size(), name + ": a sign before every chicane");
                for (Centreline.Element e : firsts) {
                    assertTrue(e.arc(), name + ": a chicane opens with its first bend");
                    // the rider's right, facing along the line: (-sin h, cos h) in (x, z), z running south; the
                    // bend's centre lies on the side it turns to
                    boolean right = (e.cx - e.x0) * -Math.sin(e.h0) + (e.cz - e.z0) * Math.cos(e.h0) > 0;
                    SignText sign = null;
                    for (SignText t : signs) {
                        double d = e.s0 - along(m, t);
                        if (d > 0 && d <= RasterV4.SIGN_BEFORE + 6) {
                            sign = t;
                        }
                    }
                    assertNotNull(sign, name + ": the sign stands just before the chicane at " + Math.round(e.s0));
                    assertEquals(GenCopy.boatChicane(right), sign.lines(), name + ": the chicane at " + Math.round(e.s0)
                            + " bends " + (right ? "right" : "left") + " first");
                    hands[right ? 1 : 0]++;
                }
            }
        }
        assertTrue(hands[0] > 0 && hands[1] > 0, "chicanes opening both ways were read: " + hands[0] + " left, "
                + hands[1] + " right");
    }

    @Test
    void aRoadHasNoMoreChicanesThanItsTierAllows() {
        // road medium 4 and 5 shipped four chicanes before the cap (the most is 2), road hard 18 four (3)
        List<Object[]> runs = new ArrayList<>();
        for (String tier : List.of("easy", "medium", "hard")) {
            for (long seed : ROADS) {
                runs.add(new Object[]{tier, seed});
            }
        }
        runs.add(new Object[]{"hard", 18L});
        for (Object[] run : runs) {
            String tier = (String) run[0];
            long seed = (Long) run[1];
            MountainPlanner.Made m = made(HALF_A, seed, tier);
            MountainTier mt = MountainTier.of(BoatStyle.ROAD, tier);
            String name = "road " + tier + " seed " + seed;
            int beats = chicanes(m.cand.sk()).size();
            long geometric = m.cand.flow().beats.stream().filter(b -> b == FlowScore.Beat.C).count();
            long signs = m.finished().signs().stream().filter(t -> t.lines().get(0).equals("CHICANE")).count();
            assertTrue(beats >= mt.chicMin && beats <= mt.chicMax, name + ": " + beats + " chicanes, "
                    + mt.chicMin + "-" + mt.chicMax);
            assertEquals(beats, geometric, name + ": the flow score counts the same chicanes");
            assertEquals(beats, signs, name + ": and the signs");
        }
    }

    /** Each staircase's sign (two or more STAIR lips on one straight run; "big" when every step is 2), in order. */
    static List<List<String>> staircases(MountainPlanner.Made m) {
        DropPlan dp = m.cand.drops();
        List<List<String>> out = new ArrayList<>();
        for (DropPlan.Run run : new DropPlan.Planner(new GenRandom(0), dp.sk).runs) {
            int n = 0;
            boolean big = true;
            for (int i = 0; i < dp.drops.size() - 1; i++) {
                DropPlan.Drop d = dp.drops.get(i);
                if (d.kind() == DropPlan.Kind.STAIR && d.s() >= run.s0() && d.s() <= run.s1()) {
                    n++;
                    big &= d.drop() >= 2;
                }
            }
            if (n >= 2) {
                out.add(GenCopy.boatCliffs(n, big));
            }
        }
        return out;
    }

    @Test
    void everyStaircaseHasItsOwnCliffsSignWithItsOwnCountAndHeight() {
        // hard road 7 (two staircases of 3) and 41 (two of 2) had theirs next to each other in the drop list:
        // one sign for both before audit MTN02; an easy road's staircase of hops said "big drops" (MTN-R3-01)
        assertEquals(List.of("THE CLIFFS!", "3 big drops!"), GenCopy.boatCliffs(3, true), "every step a 2-block drop");
        assertEquals(List.of("THE CLIFFS!", "2 drops ahead!"), GenCopy.boatCliffs(2, false), "a hop among them");
        List<Object[]> runs = new ArrayList<>(List.of(new Object[]{"hard", 7L}, new Object[]{"hard", 41L}));
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                for (long seed : style == BoatStyle.ROAD ? ROADS : SLALOMS) {
                    runs.add(new Object[]{tier, seed});
                }
            }
        }
        int two = 0;
        int[] words = new int[2];
        for (Object[] run : runs) {
            String tier = (String) run[0];
            long seed = (Long) run[1];
            MountainPlanner.Made m = made(HALF_A, seed, tier);
            String name = tier + " " + BoatStyle.of(seed).id() + " seed " + seed;
            List<SignText> cliffs = new ArrayList<>(m.finished().signs().stream()
                    .filter(t -> t.lines().get(0).equals("THE CLIFFS!")).toList());
            cliffs.sort((a, b) -> Double.compare(along(m, a), along(m, b)));
            List<List<String>> got = new ArrayList<>();
            for (SignText t : cliffs) {
                got.add(t.lines());
                words[t.lines().get(1).contains("big") ? 1 : 0]++;
            }
            List<List<String>> want = staircases(m);
            assertEquals(want, got, name + ": one THE CLIFFS! sign a staircase, with its own count and words");
            two += want.size() >= 2 ? 1 : 0;
            assertEquals(want.size(), m.cand.drops().staircases(), name + ": the staircases counted are those");
            MountainTier mt = MountainTier.of(BoatStyle.of(seed), tier);
            assertTrue(want.size() >= mt.stairsMin && want.size() <= mt.stairsMax, name + ": " + want.size()
                    + " staircases, " + mt.stairsMin + "-" + mt.stairsMax);
            assertTrue(m.finished().summary().get(2).contains(" - staircases " + want.size() + " - "),
                    name + ": and the summary says so (MTN-R3-03): " + m.finished().summary().get(2));
        }
        assertTrue(two >= 2, "fixture: courses with two staircases were read: " + two);
        assertTrue(words[0] > 0 && words[1] > 0, "fixture: staircases of hops and of big drops were read: "
                + words[0] + " and " + words[1]);
    }

    @Test
    void theFeaturesGateCountsOnlyRealStaircases() {
        // audit MTN-R3-03: two lips of any kind on a straight were a "staircase", so F-F's minimum never bit
        MountainPlanner.Made m = made(HALF_A, 2, "medium");
        Skeleton sk = m.cand.sk();
        DropPlan dp = m.cand.drops();
        assertTrue(dp.staircases() >= 1, "fixture: a staircase");
        assertTrue(FlowScore.of(sk, dp).missing.stream().noneMatch(x -> x.contains("staircases")),
                "with its staircase: " + FlowScore.of(sk, dp).missing);
        List<DropPlan.Drop> flat = new ArrayList<>();
        for (DropPlan.Drop d : dp.drops) {
            flat.add(d.kind() == DropPlan.Kind.STAIR ? new DropPlan.Drop(d.s(), d.drop(), DropPlan.Kind.FILL) : d);
        }
        DropPlan none = new DropPlan(sk, flat, dp.blue);
        assertEquals(0, none.staircases(), "the same lips, none of them a staircase's");
        assertTrue(FlowScore.of(sk, none).missing.stream().anyMatch(x -> x.contains("staircases")),
                "F-F misses the staircase: " + FlowScore.of(sk, none).missing);
    }

    // ---- BoatPlanner: algo 4 in the mountain's half, the spiral elsewhere --------------------------------

    @Test
    void theBoatPlannerMakesTheMountainInItsHalfAndTheSpiralElsewhere() throws GenFailed {
        BoatPlanner planner = new BoatPlanner();
        assertEquals(4, planner.algo(), "the planner is algo 4");
        Plan mountain = planner.plan(input(HALF_A, 2, "medium"));
        assertEquals(4, mountain.algo(), "a 480 x 176 x 640 half gets Mountain Run v2");
        assertEquals(made(HALF_A, 2, "medium").finished().hash(), mountain.hash(), "the same as MountainPlanner's");
        Box legacy = LegacyBoxes.half(Slots.ICE_BOAT, 'A');
        Plan spiral = planner.plan(new PlanInput(Slots.ICE_BOAT, legacy, 'A', 20725, 0, 31, "medium", 6, 0, null));
        assertEquals(BoatPlanner.ALGO_V3, spiral.algo(), "the 0.36 box still gets the algo-3 spiral");
        assertTrue(BoatPlanner.mountain(HALF_A) && !BoatPlanner.mountain(legacy), "by the half's size alone");
    }

    @Test
    void aTagIsMadeAgainByItsOwnAlgo() throws GenFailed {
        BoatPlanner planner = new BoatPlanner();
        Plan live = made(HALF_A, 2, "medium").finished();
        GenTag tag = new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, 4, 20725, 0, 2, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertEquals(live.hash(), planner.rederive(input(HALF_A, 0, "medium"), tag).hash(), "the stored tier");
        assertEquals(live.hash(), planner.rederive(input(HALF_A, 0, "hard"), tag).hash(), "any tier asked");
        GenTag old = new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, 2, 20725, 0, 2, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> planner.rederive(input(HALF_A, 0, "medium"), old), "an algo-2 loop isn't");
        GenTag next = new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, 5, 20725, 0, 2, 'A', live.hash(), 1, 2, 3,
                List.of(), List.of(), 0);
        assertThrows(GenFailed.class, () -> planner.rederive(input(HALF_A, 0, "medium"), next), "nor a later one");
    }

    @Test
    void theModelTimeComesBackFromTheTag() {
        Plan live = made(HALF_A, 2, "medium").finished();
        long ref = ((PlannedTrial) live.course()).refMs();
        GenTag v4 = new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, 4, 20725, 0, 2, 'A', live.hash(), ref, 2, 3,
                List.of(), List.of(), 0);
        assertEquals(MountainPlanner.modelMs(made(HALF_A, 2, "medium").seconds), BoatPlanner.modelMs(v4),
                "an algo-4 tag's T_m is its reference x 5/4, exactly");
        GenTag v3 = new GenTag(Slots.ICE_BOAT.id(), Slots.BOAT, 3, 20725, 0, 2, 'A', live.hash(), 18_100, 2, 3,
                List.of(), List.of(), 0);
        assertEquals(18_100, BoatPlanner.modelMs(v3), "an older tag's reference is its time");
        assertEquals(0, BoatPlanner.modelMs(null), "no tag, no time");
    }
}
