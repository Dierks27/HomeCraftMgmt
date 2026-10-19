package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wide road's pieces (MOUNTAIN-V21-SPEC D1b, D8, §2.5): a forest of 3 trunks (§6.4 step F) stands in the
 * lane, a split and a snow drift widen it by 2 a side, the ice ways past a drift are at least 7 wide, a medium
 * boost strip stands on straights only and ends 40 before any arc; algo 4's pieces widen by 4 as before. The deck
 * fix (0.37.1): the wide road's split and drift taper over 4, and the chain is asked about 16 spots of a card.
 */
class PiecesV4Test {

    private static final String[] ROADS = {"easy", "medium", "hard"};

    @Test
    void aForestStandsInsideTheLaneAndADriftAndASplitWidenByTwo() {
        for (String id : ROADS) {
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, id);
            Skeleton sk = SkeletonTest.routes(tier).get(0);
            double s1 = sk.start + 200;
            for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
                double len = PiecesV4.shortest(sk, k);
                PiecesV4.Piece p = PiecesV4.make(new GenRandom(3), sk, k, s1, s1 + len);
                double want = k == PiecesV4.Kind.SPLIT || k == PiecesV4.Kind.SAND_PIT ? 2 : 0;
                assertEquals(want, p.extra(s1 + len / 2), 1e-12, tier + ": a " + k.word + " widens by " + want);
                assertEquals(want, PiecesV4.widen(k), 1e-12, tier + ": widen(" + k + ")");
                assertEquals(want > 0, k.widens(tier), tier + ": so only those ask for roomToWiden");
                assertEquals(0, p.extra(s1), 1e-12, tier + ": nothing at its ends");
            }
            PiecesV4.Piece forest = PiecesV4.make(new GenRandom(3), sk, PiecesV4.Kind.FOREST, s1,
                    s1 + PiecesV4.shortest(sk, PiecesV4.Kind.FOREST));
            assertEquals(tier.width / 2.0, forest.hi, 1e-12, tier + ": the forest is the lane, W / 2 a side");
            // §6.4 step F: 3 trunks on every wide road, so a hard forest is 32 long as medium's
            assertEquals(3, forest.trunks.size(), tier + ": a forest's trunks");
            assertEquals(2 * PiecesV4.TAPER + 16, forest.s2 - forest.s1, 1e-12, tier + ": a forest 32 long");
            for (double[] t : forest.trunks) {
                assertTrue(t[1] >= -tier.width / 2.0 && t[1] + 2 <= tier.width / 2.0,
                        tier + ": its trunks stand inside the lane");
            }
            // algo 4: the pit, the split and the forest widened by 4
            MountainTier v4 = MountainTier.of(BoatStyle.ROAD, id, MountainValidator.FIRST_ALGO);
            Skeleton old = SkeletonTest.routes(v4).get(0);
            double o1 = old.start + 200;
            for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
                double len = PiecesV4.shortest(old, k);
                PiecesV4.Piece p = PiecesV4.make(new GenRandom(3), old, k, o1, o1 + len);
                boolean widens = k == PiecesV4.Kind.SPLIT || k == PiecesV4.Kind.SAND_PIT || k == PiecesV4.Kind.FOREST;
                assertEquals(widens ? PiecesV4.WIDEN : 0, p.extra(o1 + len / 2), 1e-12,
                        v4 + " v4: a " + k.word + " as in 0.37.0");
            }
            assertEquals(v4.hard() ? 4 : 3, PiecesV4.trunks(v4), v4 + " v4: a forest's trunks as in 0.37.0");
            assertEquals(2 * PiecesV4.TAPER + 6 * (v4.hard() ? 4 : 3) - 2, PiecesV4.shortest(old, PiecesV4.Kind.FOREST),
                    1e-12, v4 + " v4: its forest's length as in 0.37.0");
        }
        assertEquals(4, PiecesV4.SEARCH_WIDEN, 1e-12, "the raster's search box is 0.37.0's");
    }

    @Test
    void theIceWaysPastADriftAreAtLeastSeven() {
        // the drift is 4 wide (3 on Hard), centred within ±0.5, in a lane of W + 4 (D1b's table: 7 / 7 / 8.5)
        double[] least = {8.5, 7, 7};
        double[] split = {9.5, 8, 7.5};
        for (int i = 0; i < ROADS.length; i++) {
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, ROADS[i]);
            Skeleton sk = SkeletonTest.routes(tier).get(0);
            double s1 = sk.start + 200;
            double half = tier.width / 2.0 + PiecesV4.widen(PiecesV4.Kind.SAND_PIT);
            double narrowest = Double.MAX_VALUE;
            for (int seed = 0; seed < 200; seed++) {
                PiecesV4.Piece p = PiecesV4.make(new GenRandom(seed), sk, PiecesV4.Kind.SAND_PIT, s1,
                        s1 + PiecesV4.shortest(sk, PiecesV4.Kind.SAND_PIT));
                narrowest = Math.min(narrowest, Math.min(half - p.hi, p.lo + half));
            }
            assertTrue(narrowest >= least[i] - 1e-9, tier + ": both ways past a drift at least " + least[i]
                    + " wide, the narrowest " + narrowest);
            assertTrue(narrowest < least[i] + 0.5, tier + ": fixture: the drift's place varies, " + narrowest);
            PiecesV4.Piece island = PiecesV4.make(new GenRandom(1), sk, PiecesV4.Kind.SPLIT, s1,
                    s1 + PiecesV4.shortest(sk, PiecesV4.Kind.SPLIT));
            double branch = Math.min(half - island.hi, island.lo + half);
            assertEquals(split[i], branch, 1e-9, tier + ": each split branch is (W + 4 - 3) / 2");
        }
    }

    @Test
    void boostsStandOnStraightsFortyBeforeAnyArc() {
        MountainTier tier = MountainTier.of(BoatStyle.ROAD, "medium");
        assertEquals(0, tier.boostMin, "D8: 0-1 boost strips on the medium road");
        assertEquals(1, tier.boostMax, "D8: 0-1 boost strips on the medium road");
        int boosts = 0;
        for (DropPlan dp : DropPlanTest.plans(tier)) {
            Skeleton sk = dp.sk;
            for (int seed = 0; seed < 4; seed++) {
                PiecesV4 pieces = PiecesV4.draw(new GenRandom(seed), sk, dp, MountainPlanner.FULL, blocked -> true);
                for (PiecesV4.Piece p : pieces.list) {
                    if (p.kind != PiecesV4.Kind.BOOST) {
                        continue;
                    }
                    boosts++;
                    for (double s = p.s1; s <= p.s2; s += 0.5) {
                        assertFalse(sk.line.elementAt(s).arc(), tier + ": the boost " + p.s1 + "-" + p.s2
                                + " is on a straight at " + s);
                    }
                    double arc = PiecesV4.nextArcStart(sk, p.s2);
                    assertTrue(arc - p.s2 >= PiecesV4.BOOST_ARC_GAP, tier + ": it ends " + (arc - p.s2)
                            + " before the next arc, at least " + PiecesV4.BOOST_ARC_GAP);
                    for (DropPlan.Drop d : dp.drops) {
                        assertFalse(p.s2 > d.s() - 30 && p.s1 < d.s(), tier + ": and never within 30 before the lip"
                                + " at " + d.s());
                    }
                }
            }
        }
        assertTrue(boosts > 0, "fixture: some boost strips placed");
        // an arc carries no boost on the wide road; algo 4's wide bends still could
        Skeleton sk = SkeletonTest.routes(tier).get(0);
        MountainTier v4 = MountainTier.of(BoatStyle.ROAD, "medium", MountainValidator.FIRST_ALGO);
        Skeleton old = SkeletonTest.routes(v4).get(0);
        int[] runoff = PiecesV4.runoffs(sk);
        int[] oldRunoff = PiecesV4.runoffs(old);
        boolean oldCarries = false;
        for (Centreline.Element e : sk.line.elements()) {
            if (e.arc()) {
                assertFalse(PiecesV4.carries(sk, e, runoff, PiecesV4.Kind.BOOST), tier + ": no boost on an arc");
            }
        }
        for (Centreline.Element e : old.line.elements()) {
            oldCarries |= e.arc() && PiecesV4.carries(old, e, oldRunoff, PiecesV4.Kind.BOOST);
        }
        assertTrue(oldCarries, v4 + " v4: a wide bend still carries a boost as in 0.37.0");
    }

    @Test
    void theWideRoadsSplitAndDriftTaperOverFour() {
        // the deck fix (0.37.1): a split and a drift widen by 2 a side over 4, algo 4's wall angle (4 over 8), so a
        // split is 16-22 long and a drift 18-22; the island and the snow begin where the lane is at its widest
        for (String id : ROADS) {
            MountainTier tier = MountainTier.of(BoatStyle.ROAD, id);
            Skeleton sk = SkeletonTest.routes(tier).get(0);
            assertEquals(4, PiecesV4.taper(PiecesV4.Kind.SPLIT, tier), 1e-12, tier + ": a split tapers over 4");
            assertEquals(4, PiecesV4.taper(PiecesV4.Kind.SAND_PIT, tier), 1e-12, tier + ": a drift tapers over 4");
            assertEquals(PiecesV4.TAPER, PiecesV4.taper(PiecesV4.Kind.FOREST, tier), 1e-12, tier + ": a forest, 8");
            assertEquals(16, PiecesV4.shortest(sk, PiecesV4.Kind.SPLIT), 1e-12, tier + ": the least split");
            assertEquals(18, PiecesV4.shortest(sk, PiecesV4.Kind.SAND_PIT), 1e-12, tier + ": the least drift");
            double[] split = {99, 0};
            double[] drift = {99, 0};
            for (int seed = 0; seed < 400; seed++) {
                double a = PiecesV4.length(new GenRandom(seed), sk, PiecesV4.Kind.SPLIT);
                double b = PiecesV4.length(new GenRandom(seed), sk, PiecesV4.Kind.SAND_PIT);
                split[0] = Math.min(split[0], a);
                split[1] = Math.max(split[1], a);
                drift[0] = Math.min(drift[0], b);
                drift[1] = Math.max(drift[1], b);
            }
            assertEquals(16, split[0], 1e-12, tier + ": a split 16-22 long");
            assertEquals(22, split[1], 1e-12, tier + ": a split 16-22 long");
            assertEquals(18, drift[0], 1e-12, tier + ": a drift 18-22 long");
            assertEquals(22, drift[1], 1e-12, tier + ": a drift 18-22 long");
            double s1 = sk.start + 200;
            PiecesV4.Piece p = PiecesV4.make(new GenRandom(1), sk, PiecesV4.Kind.SPLIT, s1, s1 + 16);
            assertEquals(1, p.extra(s1 + 2), 1e-12, tier + ": half widened 2 in");
            assertEquals(2, p.extra(s1 + 4), 1e-12, tier + ": fully widened 4 in");
            assertEquals(2, p.extra(s1 + 12), 1e-12, tier + ": and 4 before its end");
            assertEquals(s1 + 5, p.a1, 1e-12, tier + ": the island starts where the lane is at its widest");
            assertEquals(s1 + 11, p.a2, 1e-12, tier + ": the island 6 long in the least split");
            PiecesV4.Piece d = PiecesV4.make(new GenRandom(1), sk, PiecesV4.Kind.SAND_PIT, s1, s1 + 18);
            assertEquals(2, d.extra(d.a1), 1e-12, tier + ": the snow starts where the lane is at its widest");
            assertEquals(8, d.a2 - d.a1, 1e-12, tier + ": the snow 8 long in the least drift, as in 0.37.0");
        }
        // algo 4: taper 8, as in 0.37.0
        for (String id : ROADS) {
            MountainTier v4 = MountainTier.of(BoatStyle.ROAD, id, MountainValidator.FIRST_ALGO);
            Skeleton old = SkeletonTest.routes(v4).get(0);
            assertEquals(PiecesV4.TAPER, PiecesV4.taper(PiecesV4.Kind.SPLIT, v4), 1e-12, v4 + " v4: 8");
            assertEquals(PiecesV4.TAPER, PiecesV4.taper(PiecesV4.Kind.SAND_PIT, v4), 1e-12, v4 + " v4: 8");
            assertEquals(24, PiecesV4.shortest(old, PiecesV4.Kind.SPLIT), 1e-12, v4 + " v4: the least split 24");
            assertEquals(26, PiecesV4.shortest(old, PiecesV4.Kind.SAND_PIT), 1e-12, v4 + " v4: the least pit 26");
            double o1 = old.start + 200;
            PiecesV4.Piece p = PiecesV4.make(new GenRandom(1), old, PiecesV4.Kind.SPLIT, o1, o1 + 24);
            assertEquals(2, p.extra(o1 + 4), 1e-12, v4 + " v4: half of WIDEN 4, 4 in");
            assertEquals(o1 + 9, p.a1, 1e-12, v4 + " v4: the island from 9 in");
        }
    }

    @Test
    void theWideRoadAsksTheChainSixteenTimes() {
        for (String id : ROADS) {
            assertEquals(16, PiecesV4.chainTries(MountainTier.of(BoatStyle.ROAD, id)), id + ": the wide road");
            assertEquals(4, PiecesV4.chainTries(MountainTier.of(BoatStyle.ROAD, id, MountainValidator.FIRST_ALGO)),
                    id + ": algo 4, as in 0.37.0");
        }
    }
}
