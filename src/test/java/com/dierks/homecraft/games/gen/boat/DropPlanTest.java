package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wide road's drop rules (MOUNTAIN-V21-SPEC D2-D4, §2.1, §2.2, §3.1): 66 across a Big Drop and 60 for
 * every other leg, the road's tight run-up and the Slalom's 0.37.0 one, the room a piece or a gate field leaves
 * the checkpoint before a lip, GROW budgeted at the Final Drop and at a shared checkpoint, no blue straight
 * ending in a sweeper, lips within 300 of each other on the medium and hard roads (§6.4 step S), and algo 4's
 * rules where its tables are asked for.
 */
class DropPlanTest {

    private static final Box HALF_A = new Box(6080, 96, 2880, 6559, 271, 3519);
    private static final Runnable NO_TICK = () -> {
    };

    /** The drop plans of {@code tier}'s routes ({@link SkeletonTest#routes}), each from its own stream. */
    static List<DropPlan> plans(MountainTier tier) {
        List<DropPlan> out = new ArrayList<>();
        for (Skeleton sk : SkeletonTest.routes(tier)) {
            DropPlan dp = DropPlan.draw(new GenRandom(sk.frame.bands * 31L + Math.round(sk.end)), sk);
            if (dp != null) {
                out.add(dp);
            }
        }
        return out;
    }

    @Test
    void legForIsSixtySixOnlyForBigDrops() {
        assertEquals(66.0, DropPlan.BIG_LEG, 1e-12, "D2: a Big Drop's leg may be 66");
        assertEquals(MountainValidator.MAX_LEG_BIG_DROP, DropPlan.BIG_LEG, 1e-12, "the proof's own bound");
        assertEquals(60.0, DropPlan.LEG, 1e-12, "every other leg keeps 60");
        assertEquals(66.0, DropPlan.legFor(2), 1e-12, "a 2-block drop's leg");
        assertEquals(60.0, DropPlan.legFor(1), 1e-12, "a hop's leg");
        assertEquals(60.0, DropPlan.legFor(0), 1e-12, "a leg with no drop");
        Skeleton wide = SkeletonTest.routes(MountainTier.of(BoatStyle.ROAD, "medium")).get(0);
        DropPlan.Planner p = new DropPlan.Planner(new GenRandom(0), wide);
        assertEquals(66.0, p.leg(2), 1e-12, "the wide road's planner reads it");
        assertEquals(60.0, p.leg(1), 1e-12, "and 60 for a hop");
        assertEquals(RasterV4.GROW, p.grow(), 1e-12, "and budgets the raster's grow");
        Skeleton v4 = SkeletonTest.routes(MountainTier.of(BoatStyle.ROAD, "medium", MountainValidator.FIRST_ALGO))
                .get(0);
        DropPlan.Planner q = new DropPlan.Planner(new GenRandom(0), v4);
        assertEquals(60.0, q.leg(2), 1e-12, "algo 4 keeps 60 for every leg");
        assertEquals(0.0, q.grow(), 1e-12, "and budgets no grow");
    }

    @Test
    void theRoadsRunUpIsTwoPastTheSetBackAndTheSlalomsIsAsBefore() {
        // §2.1's table: width, setBack on / off the axis, tier.runUp on / off, clearBefore(W)
        Object[][] want = {
                {BoatStyle.ROAD, "easy", 18, 9.75, 11.25, 12, 14, 21},
                {BoatStyle.ROAD, "medium", 15, 8.25, 9.75, 11, 12, 18},
                {BoatStyle.ROAD, "hard", 14, 7.75, 9.25, 10, 12, 17},
                {BoatStyle.SLALOM, "easy", 15, 8.25, 9.75, 18, 18, 18},
                {BoatStyle.SLALOM, "medium", 13, 7.25, 8.75, 16, 16, 16},
                {BoatStyle.SLALOM, "hard", 11, 6.25, 7.75, 14, 14, 14}};
        for (Object[] w : want) {
            MountainTier t = MountainTier.of((BoatStyle) w[0], (String) w[1]);
            assertEquals((int) w[2], t.width, t + ": its width");
            double on = MountainTier.setBack(t.width, true);
            double off = MountainTier.setBack(t.width, false);
            assertEquals((double) w[3], on, 1e-12, t + ": the set-back on an axis-aligned straight");
            assertEquals((double) w[4], off, 1e-12, t + ": and off it");
            assertEquals((int) w[5], t.runUp(on), t + ": the run-up on the axis");
            assertEquals((int) w[6], t.runUp(off), t + ": and off it");
            assertEquals((int) w[7], MountainTier.clearBefore(t.width), t + ": the reach a piece or gate field leaves");
            if (t.slalom()) {
                assertEquals(MountainTier.clearBefore(t.width), t.runUp(on), t + ": the Slalom keeps 0.37.0's run-up");
            }
        }
        // algo 4: every lip ran up clearBefore of its lane there, a necked 2-block drop its 7-wide neck's
        MountainTier v4 = MountainTier.of(BoatStyle.SLALOM, "medium", MountainValidator.FIRST_ALGO);
        assertEquals(16, v4.runUp(MountainTier.setBack(v4.width, true)), v4 + " v4: runUp(13) = 16");
        Skeleton sk = SkeletonTest.routes(v4).get(0);
        DropPlan.Planner p = new DropPlan.Planner(new GenRandom(0), sk);
        double s = sk.start + 200;
        assertEquals(MountainTier.clearBefore(MountainTier.NECK), p.runUp(s, 2, p.before(s, 2)),
                v4 + " v4: a 2-block drop's run-up is the neck's");
        assertEquals(16, p.runUp(s, 1, p.before(s, 1)), v4 + " v4: a hop's the lane's");
        MountainTier road = MountainTier.of(BoatStyle.ROAD, "medium", MountainValidator.FIRST_ALGO);
        assertEquals(10, road.runUp(MountainTier.setBack(road.width, true)), road + " v4: never the tight run-up");
    }

    @Test
    void aLipJustPastAPieceOrAGateFieldStillHasItsCheckpoint() {
        // D4 item 3 (red team P1): a piece's stretch and a gate field stop clearBefore(W) before a lip, room for
        // the checkpoint before it (its set-back c, grown by GROW, and the raster's margin): never the tight
        // run-up. A piece at the very tail of its stretch (place()'s pass 1) leaves the base raster a spot there.
        int pieces = 0;
        for (String id : new String[]{"easy", "medium", "hard"}) {
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, id);
            for (DropPlan dp : plans(tier)) {
                if (pieces >= 6) {
                    break;
                }
                Skeleton sk = dp.sk;
                int[] runoff = PiecesV4.runoffs(sk);
                RasterV4 base = null;
                for (double[] f : PiecesV4.stretches(sk, dp, runoff, PiecesV4.Kind.CAVE, false)) {
                    DropPlan.Drop lip = lipAfter(dp, f[1]);
                    double len = PiecesV4.shortest(sk, PiecesV4.Kind.CAVE);
                    if (lip == null || f[1] - f[0] < len + 2
                            || Math.abs(lip.s() - MountainTier.clearBefore(tier.width) - 2 - f[1]) > 1e-9) {
                        continue; // not a stretch a lip's run-up ends
                    }
                    double s1 = Math.floor(f[0] + 1 + (f[1] - f[0] - len - 2));
                    PiecesV4.Piece p = PiecesV4.make(new GenRandom(1), sk, PiecesV4.Kind.CAVE, s1, s1 + len);
                    List<double[]> blocked = new PiecesV4(List.of(p), runoff).blocked();
                    if (base == null) {
                        base = new RasterV4(HALF_A, sk, dp, PiecesV4.none(sk), NO_TICK);
                        assertNull(base.problem, tier + ": the base raster lays");
                    }
                    double c = new DropPlan.Planner(new GenRandom(0), sk).before(lip.s(), lip.drop());
                    assertTrue(hasSpot(base.spots(blocked), lip.s(), c), tier + ": a cave ending at " + (s1 + len)
                            + " leaves the checkpoint before the lip at " + lip.s() + " (within " + (c + RasterV4.GROW)
                            + ")");
                    pieces++;
                    break;
                }
            }
        }
        assertTrue(pieces >= 3, "fixture: " + pieces + " pieces at the tail of their stretch before a lip");
        // a slalom lip at the earliest place ok() allows after a gate field
        int lips = 0;
        for (String id : new String[]{"easy", "medium", "hard"}) {
            MountainTier tier = MountainTier.of(BoatStyle.SLALOM, id);
            for (Skeleton sk : SkeletonTest.routes(tier)) {
                if (lips >= 6 || sk.gates.isEmpty()) {
                    continue;
                }
                DropPlan.Planner p = new DropPlan.Planner(new GenRandom(1), sk);
                if (!p.finalDrop()) {
                    continue;
                }
                Skeleton.GateSet g = sk.gates.get(0);
                double at = Double.NaN;
                int d = 1;
                for (double s = Math.ceil(g.to() * 2) / 2; s < g.to() + 80 && Double.isNaN(at); s += 0.5) {
                    for (int dd = 2; dd >= 1 && Double.isNaN(at); dd--) {
                        if (p.ok(s, dd)) {
                            at = s;
                            d = dd;
                        }
                    }
                }
                double back = MountainTier.clearBefore(tier.width) + DropPlan.CLEAR;
                if (Double.isNaN(at) || !p.gates(at - 0.5 - back, at - 0.5)) {
                    continue; // no lip after this set, or the gate field isn't what holds it back
                }
                p.add(at, d, DropPlan.Kind.FILL);
                DropPlan hand = new DropPlan(sk, p.lips, p.blue);
                RasterV4 base = new RasterV4(HALF_A, sk, hand, PiecesV4.none(sk), NO_TICK);
                if (base.problem != null) {
                    continue;
                }
                double c = p.before(at, d);
                assertTrue(hasSpot(base.spots(List.of()), at, c), tier + ": a lip at " + at + " just past the gates"
                        + " to " + g.to() + " has its checkpoint (within " + (c + RasterV4.GROW) + ")");
                lips++;
            }
        }
        assertTrue(lips >= 3, "fixture: " + lips + " slalom lips at the earliest place past a gate field");
    }

    /** The first non-final lip at or after {@code s}, or {@code null}. */
    private static DropPlan.Drop lipAfter(DropPlan dp, double s) {
        for (int i = 0; i < dp.drops.size() - 1; i++) {
            if (dp.drops.get(i).s() >= s) {
                return dp.drops.get(i);
            }
        }
        return null;
    }

    /** Whether a lane spot stands before {@code lip}, within {@code c} + GROW of it. */
    private static boolean hasSpot(List<RasterV4.Spot> spots, double lip, double c) {
        for (RasterV4.Spot s : spots) {
            if (!s.gate() && s.s() < lip && lip - s.s() <= c + RasterV4.GROW + 1e-9) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theFinalDropAndASharedCheckpointBudgetGrow() {
        // red team P2: the raster may grow a checkpoint by GROW, standing it that much further back. finalDrop()'s
        // window is Z + 3 to 66 - c - 0.25 - GROW (D3: 54-56 at 15 wide, 54-54.5 at 18)
        for (String id : new String[]{"easy", "medium"}) { // 18 and 15 wide
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, id);
            int windows = 0;
            int bigs = 0;
            for (Skeleton sk : SkeletonTest.routes(tier)) {
                DropPlan.Planner p = new DropPlan.Planner(new GenRandom(Math.round(sk.end)), sk);
                if (!p.finalDrop()) {
                    continue;
                }
                DropPlan.Drop f = p.lips.get(0);
                double c = p.before(f.s(), f.drop());
                double to = sk.finish - f.s();
                assertTrue(to >= BoatEnvelope.zone(f.drop()) + DropPlan.CLEAR - 1e-9
                                && to <= Math.min(70, DropPlan.legFor(f.drop()) - c - DropPlan.ROUNDING - RasterV4.GROW)
                                + 1e-9, tier + ": a " + f.drop() + "-block Final Drop " + to + " before the finish");
                windows++;
                bigs += f.drop() >= 2 ? 1 : 0;
            }
            assertTrue(windows > 0, tier + ": fixture: some Final Drops placed");
            assertTrue(bigs * 4 >= windows * 3, tier + ": D3, the Final Drop a Big Drop on every tier: " + bigs + " of "
                    + windows + " (the half-block window [54, 54.5] at 18 wide always holds a lip)");
        }
        // and on whole plans (upgrade() may move it), the finish's leg keeps room for its checkpoint grown
        int big = 0;
        int bigEasy = 0;
        int finals = 0;
        for (String id : new String[]{"easy", "medium"}) {
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, id);
            for (DropPlan dp : plans(tier)) {
                DropPlan.Drop last = dp.last();
                DropPlan.Planner p = new DropPlan.Planner(new GenRandom(0), dp.sk);
                double c = p.before(last.s(), last.drop());
                double to = dp.sk.finish - last.s();
                assertTrue(to <= DropPlan.legFor(last.drop()) - c - DropPlan.ROUNDING - RasterV4.GROW + 1e-9,
                        tier + ": the Final Drop " + to + " before the finish leaves its grown checkpoint's leg in "
                                + DropPlan.legFor(last.drop()));
                assertTrue(to >= BoatEnvelope.zone(last.drop()) + DropPlan.CLEAR - 1e-9,
                        tier + ": and keeps V11's Z + 3 from it: " + to);
                finals++;
                big += last.drop() >= 2 ? 1 : 0;
                bigEasy += last.drop() >= 2 && tier.easy() ? 1 : 0;
            }
        }
        assertTrue(finals > 0, "fixture: some Final Drops");
        assertTrue(big > 0, "some of them Big Drops: " + big + " of " + finals);
        assertTrue(bigEasy > 0, "the 18-wide road's too, upgraded within 54-54.5 of the finish: " + bigEasy);
        // two Big Drops as close as ok()'s spacing lets them stand (65.5 apart at 18 wide, 64 at 15), sharing the
        // one checkpoint between them
        int pairs = 0;
        int refused = 0;
        int taken = 0;
        for (String id : new String[]{"easy", "medium"}) {
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, id);
            for (Skeleton sk : SkeletonTest.routes(tier)) {
                DropPlan.Planner p = new DropPlan.Planner(new GenRandom(1), sk);
                if (!p.finalDrop()) {
                    continue;
                }
                double[] pair = densestBigPair(p);
                if (pair == null) {
                    continue;
                }
                double s1 = pair[0];
                double s2 = pair[1];
                int i1 = indexOf(p, s1);
                double c1 = p.before(s1, 2);
                double c2 = p.before(s2, 2);
                double a = p.zoneEnd.get(i1) + DropPlan.CLEAR + DropPlan.ROUNDING;
                double b = s2 - c2;
                assertTrue(a + p.spotAt(a) + c2 + 0.75 > b, tier + ": the densest pair (" + s1 + ", " + s2
                        + ") has room for one checkpoint between them");
                double shared = Math.min(b, s1 - c1 + DropPlan.BIG_LEG - RasterV4.GROW);
                assertTrue(shared >= a - 1e-9, tier + ": which stands past the first one's zone: " + shared + " >= "
                        + a);
                double after = p.zoneEnd.get(indexOf(p, s2)) + DropPlan.CLEAR + DropPlan.ROUNDING;
                assertTrue(after - shared <= DropPlan.BIG_LEG + 1e-9, tier + ": and the second " + (after - shared));
                pairs++;
                // where the budget changes the answer (audit TQ1): chains() keeps that shared checkpoint at most
                // BIG_LEG - GROW past the one before the first lip, so a third Big Drop at the same gap, its flight
                // zone ending on the same straight, needs its own checkpoint within a grown leg of it. At 18 wide
                // (set back 9.75) it would stand 1 past that and is refused; at 15 (8.25, 64 apart) the clamp
                // doesn't bind and the same third is taken. Without the grow budget the 18-wide road takes it too.
                double s3 = s2 + (s2 - s1);
                if (p.zoneEnd(s3, 2) > p.runAt(s2).s1()) {
                    continue; // its zone would run into the bend past the straight, a refusal of another rule
                }
                if (tier.easy()) {
                    assertFalse(p.ok(s3, 2), tier + ": a third Big Drop " + (s2 - s1) + " past the pair at (" + s1
                            + ", " + s2 + ") is refused: the checkpoint the pair shares, grown, can't reach one past it");
                    refused++;
                } else {
                    assertTrue(p.ok(s3, 2), tier + ": a third Big Drop " + (s2 - s1) + " past the pair at (" + s1
                            + ", " + s2 + ") is taken: the grow budget doesn't bind at 15 wide");
                    taken++;
                }
            }
        }
        assertTrue(pairs >= 4, "fixture: " + pairs + " pairs of Big Drops sharing a checkpoint");
        assertTrue(refused >= 4 && taken >= 4, "fixture: third Big Drops read on the 18-wide road " + refused
                + " and on the 15-wide " + taken);
    }

    /**
     * The first two Big Drops {@code p} can lay one after the other on one axis-aligned straight, the second as near
     * the first as {@code ok} allows; {@code null} when no straight holds two. Both are left laid.
     */
    private static double[] densestBigPair(DropPlan.Planner p) {
        for (DropPlan.Run run : p.runs) {
            if (run.length() < 160) {
                continue;
            }
            for (double s1 = Math.ceil(run.s0() * 2) / 2; s1 < run.s1() - 100; s1 += 0.5) {
                if (!p.ok(s1, 2)) {
                    continue;
                }
                p.add(s1, 2, DropPlan.Kind.STAIR);
                for (double s2 = s1 + BoatEnvelope.zone(2) + DropPlan.LIP_GAP; s2 <= run.s1(); s2 += 0.5) {
                    if (p.ok(s2, 2)) {
                        p.add(s2, 2, DropPlan.Kind.STAIR);
                        return new double[]{s1, s2};
                    }
                }
                p.remove(indexOf(p, s1));
                break;
            }
        }
        return null;
    }

    private static int indexOf(DropPlan.Planner p, double s) {
        for (int i = 0; i < p.lips.size(); i++) {
            if (p.lips.get(i).s() == s) {
                return i;
            }
        }
        throw new AssertionError("no lip at " + s);
    }

    @Test
    void noBlueStraightEndsInASweeper() {
        int blue = 0;
        for (DropPlan dp : plans(MountainTier.of(BoatStyle.ROAD, "hard"))) {
            assertTrue(dp.blue.size() <= 2, "D8: 1-2 blue straights on the hard road, not " + dp.blue.size());
            for (double[] b : dp.blue) {
                boolean brake = false;
                for (DropPlan.Drop d : dp.drops) {
                    brake |= d.drop() == 1 && d.s() >= b[1] - 1e-9 && d.s() <= b[1] + DropPlan.BRAKE_WINDOW;
                }
                assertTrue(brake, "the blue straight " + b[0] + " to " + b[1] + " ends in a 1-block brake drop");
                blue++;
            }
        }
        assertTrue(blue > 0, "fixture: some blue straights");
    }

    @Test
    void bigBetweenSeesOnlyTwoBlockLips() {
        Skeleton sk = SkeletonTest.routes(MountainTier.of(BoatStyle.ROAD, "medium")).get(0);
        DropPlan dp = new DropPlan(sk, List.of(new DropPlan.Drop(300, 1, DropPlan.Kind.FILL),
                new DropPlan.Drop(400, 2, DropPlan.Kind.FILL), new DropPlan.Drop(500, 1, DropPlan.Kind.FINAL)),
                List.of());
        assertFalse(dp.bigBetween(250, 350), "a hop isn't a Big Drop");
        assertTrue(dp.bigBetween(350, 450), "a 2-block lip is");
        assertTrue(dp.bigBetween(399, 400), "(a, b] holds b");
        assertFalse(dp.bigBetween(400, 450), "and not a");
        assertFalse(dp.bigBetween(450, 600), "the 1-block Final Drop isn't");
        assertEquals(1, dp.lipsBetween(450, 600), "though it is a lip");
    }

    @Test
    void theWideRoadsMediumAndHardKeepLipsWithinThreeHundred() {
        // §6.4 step S: SPREAD 300 on the wide road's medium and hard tiers; easy, the slaloms and algo 4 keep 500
        for (String id : List.of("easy", "medium", "hard")) {
            MountainTier road = MountainTier.of(BoatStyle.ROAD, id);
            assertEquals(road.easy() ? 500 : 300, DropPlan.spreadFor(road), 1e-12, road + ": its spread");
            assertEquals(500, DropPlan.spreadFor(MountainTier.of(BoatStyle.SLALOM, id)), 1e-12, "the slalom keeps 500");
            assertEquals(500, DropPlan.spreadFor(MountainTier.of(BoatStyle.ROAD, id, MountainValidator.FIRST_ALGO)),
                    1e-12, road + " v4 keeps 500");
        }
        int plans = 0;
        for (String id : List.of("medium", "hard")) {
            for (DropPlan dp : plans(MountainTier.of(BoatStyle.ROAD, id))) {
                DropPlan.Planner along = new DropPlan.Planner(new GenRandom(0), dp.sk);
                double prev = dp.sk.start;
                for (int i = 0; i <= dp.drops.size(); i++) {
                    double s = i < dp.drops.size() ? dp.drops.get(i).s() : dp.sk.finish;
                    assertTrue(along.unlinked(prev, s) <= DropPlan.SPREAD_ROAD + 1e-9, "road " + id + ": "
                            + along.unlinked(prev, s) + " without a lip (links not counted) from " + prev + " to " + s);
                    prev = s;
                }
                plans++;
            }
        }
        assertTrue(plans > 0, "fixture: wide medium and hard drop plans");
    }

    @Test
    void theWideRoadHasNoNecksAndAlgoFourKeepsThem() {
        int necked = 0;
        for (DropPlan dp : plans(MountainTier.of(BoatStyle.ROAD, "medium"))) {
            assertTrue(dp.necks().isEmpty(), "no neck on the wide road");
            for (double s = Frame.PIT; s <= dp.sk.finish; s += 1) {
                assertEquals(dp.sk.width(s), dp.width(s), 1e-12, "the lane keeps the tier's width at " + s);
            }
        }
        MountainTier slalom = MountainTier.of(BoatStyle.SLALOM, "medium", MountainValidator.FIRST_ALGO);
        for (DropPlan dp : plans(slalom)) {
            for (DropPlan.Drop d : dp.drops) {
                if (d.drop() >= 2) {
                    assertEquals(MountainTier.NECK, dp.width(d.s()), 1e-12, slalom + " v4: a necked 2-block drop");
                    necked++;
                }
            }
        }
        assertTrue(necked > 0, "fixture: algo 4's 13-wide slalom still necks its 2-block drops: " + necked);
        assertNotNull(MountainTier.of(BoatStyle.ROAD, "easy", MountainValidator.FIRST_ALGO), "algo 4's tables");
    }
}
