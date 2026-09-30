package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ore Slots's engine (spec §5.1, §5.3, R1.1, R1.4, R1.9, R1.23).
 *
 * <p>Pinned here: the shipped table solves Stone to 1,479 (×100 weights) and gives back just under
 * 90 of every 100 tokens at every stake; the §5.1 rule at rtp 85, 90 and 95 (the largest exact
 * value at or below the target, else the smallest above 85, checked against an independent
 * brute-force enumeration and against {@link RtpLimits#pick}); the payout cap binds every payout
 * and the maths uses the capped values; a table that can't reach the band leaves no stake; the
 * draw is a pure function of the seed with reel frequencies matching the weights; and seeded
 * soaks sized by R1.23 agree with the exact value.
 */
class SlotsEngineTest {

    /** A soak's seed: a constant, never tuned. */
    private static final long SOAK_SEED = 0x5107_5EEDL;

    private static final Map<String, Integer> REELS = map("coal", 10, "copper", 8, "iron", 6, "gold", 4,
            "diamond", 2, "wild", 2);
    private static final Map<String, Integer> PAYS = map("two", 2, "coal", 4, "copper", 6, "iron", 10,
            "gold", 20, "diamond", 40, "wild", 50);
    private static final List<Integer> STAKES = List.of(1, 2, 5);

    private static SlotsEngine shipped(double rtp) {
        return SlotsEngine.solve(REELS, PAYS, STAKES, 250, rtp);
    }

    // ---- an independent reading of the rules, straight from the spec's words ------------------

    /** The tokens three symbols pay: the best of every line they make, each capped. */
    private static long oraclePay(Symbol[] t, Map<String, Integer> pays, int stake, int cap) {
        long best = 0;
        boolean allWild = t[0] == Symbol.WILD && t[1] == Symbol.WILD && t[2] == Symbol.WILD;
        if (allWild) {
            best = Math.max(best, Math.min((long) stake * pays.getOrDefault("wild", 0), cap));
        }
        boolean pair = false;
        for (Symbol ore : List.of(Symbol.COAL, Symbol.COPPER, Symbol.IRON, Symbol.GOLD, Symbol.DIAMOND)) {
            int n = 0;
            for (Symbol s : t) {
                if (s == ore || s == Symbol.WILD) {
                    n++;
                }
            }
            if (n == 3) {
                best = Math.max(best, Math.min((long) stake * pays.getOrDefault(ore.key(), 0), cap));
            }
            pair |= n >= 2;
        }
        if (pair) {
            best = Math.max(best, Math.min((long) stake * pays.getOrDefault("two", 0), cap));
        }
        return best;
    }

    /** The exact return {num, den} for these (unscaled) reels, a scaled Stone, a stake and a cap. */
    private static BigInteger[] oracleRtp(Map<String, Integer> reels, Map<String, Integer> pays, long stone,
                                          int stake, int cap) {
        Map<Symbol, Long> w = new EnumMap<>(Symbol.class);
        long total = 0;
        for (Symbol s : Symbol.values()) {
            long v = s == Symbol.STONE ? stone : (long) reels.getOrDefault(s.key(), 0) * SlotsEngine.SCALE;
            w.put(s, v);
            total += v;
        }
        BigInteger num = BigInteger.ZERO;
        for (Symbol a : Symbol.values()) {
            for (Symbol b : Symbol.values()) {
                for (Symbol c : Symbol.values()) {
                    long pay = oraclePay(new Symbol[]{a, b, c}, pays, stake, cap);
                    num = num.add(BigInteger.valueOf(w.get(a)).multiply(BigInteger.valueOf(w.get(b)))
                            .multiply(BigInteger.valueOf(w.get(c))).multiply(BigInteger.valueOf(pay)));
                }
            }
        }
        return new BigInteger[]{num, BigInteger.valueOf(total).pow(3).multiply(BigInteger.valueOf(stake))};
    }

    private static int cmp(BigInteger[] f, double percent) {
        RtpLimits.Ratio r = RtpLimits.Ratio.percent(percent);
        return f[0].multiply(BigInteger.valueOf(r.den())).compareTo(BigInteger.valueOf(r.num()).multiply(f[1]));
    }

    private static RtpLimits.Ratio ratio(BigInteger[] f) {
        BigInteger g = f[0].gcd(f[1]);
        return new RtpLimits.Ratio(f[0].divide(g).longValueExact(), f[1].divide(g).longValueExact());
    }

    // ---- the solve ----------------------------------------------------------------------------

    @Test
    void theShippedTableSolvesStoneTo1479AndGivesBackJustUnder90AtEveryStake() {
        SlotsEngine e = shipped(90);
        assertEquals(STAKES, e.stakes(), "every shipped stake fits the band");
        assertTrue(e.dropped().isEmpty(), "nothing dropped: " + e.dropped());
        for (StakeOdds o : e.odds().values()) {
            assertEquals(1479, o.stone(), "stake " + o.stake() + ": the spec's checked Stone at ×100");
            assertEquals(4679, o.total(), "stake " + o.stake() + ": 3,200 of ores and Wild plus Stone");
            BigInteger[] exact = oracleRtp(REELS, PAYS, 1479, o.stake(), 250);
            assertEquals(0, o.rtpNum().multiply(exact[1]).compareTo(exact[0].multiply(o.rtpDen())),
                    "stake " + o.stake() + ": the engine's exact return is the brute-force enumeration's");
            assertEquals(0.89966, o.rtp(), 1e-5, "the spec's checked 89.97%");
            assertEquals(89, RtpLimits.wholePercent(o.rtp()), "players read about 89, never the target 90");
            assertEquals(89.9, RtpLimits.tenthPercent(o.rtp()), "admins and the website read 89.9");
            assertFalse(o.aboveTarget(), "90 is reachable from below: no INFO line");
            assertFalse(o.capped(), "with max_payout 250 the cap never binds (5 × 50 is exactly 250)");
            assertEquals(1.87, o.sd(), 0.01, "the spec's per-play spread of about 1.87 × the stake");
            assertEquals(3, o.hitOneIn(), "a paying spin about 1 in 2.8, shown rounded as 1 in 3");
        }
        assertTrue(e.sameAtEveryStake(), "the cap binds nowhere, so one paytable stands for every stake");
    }

    @Test
    void theSolveRuleHoldsAtRtp85And90And95ForEveryShippedStake() {
        Map<Double, Integer> expectedStone = Map.of(85.0, 1617, 90.0, 1479, 95.0, 1351);
        for (double target : List.of(85.0, 90.0, 95.0)) {
            SlotsEngine e = shipped(target);
            assertEquals(STAKES, e.stakes(), "rtp " + target + ": every stake is kept");
            for (StakeOdds o : e.odds().values()) {
                String why = "rtp " + target + " stake " + o.stake();
                BigInteger[] at = oracleRtp(REELS, PAYS, o.stone(), o.stake(), 250);
                BigInteger[] heavier = oracleRtp(REELS, PAYS, o.stone() + 1L, o.stake(), 250);
                BigInteger[] lighter = oracleRtp(REELS, PAYS, o.stone() - 1L, o.stake(), 250);
                assertTrue(cmp(at, 85) >= 0 && cmp(at, 95) <= 0, why + ": inside the band");
                assertEquals((int) expectedStone.get(target), o.stone(), why + ": the solved Stone");
                if (o.aboveTarget()) {
                    assertTrue(cmp(at, target) > 0, why + ": above the target");
                    assertTrue(cmp(heavier, 85) < 0, why + ": one more Stone would fall under 85, so the "
                            + "largest value at or below the target is outside the band");
                } else {
                    assertTrue(cmp(at, target) <= 0, why + ": at or below the target");
                    assertTrue(cmp(lighter, target) > 0, why + ": one Stone lighter is above the target, so "
                            + "this is the LARGEST value at or below it");
                }
                RtpLimits.Pick pick = RtpLimits.pick(RtpLimits.Ratio.percent(target),
                        new RtpLimits.Ratio[]{ratio(heavier), ratio(at), ratio(lighter)});
                assertNotNull(pick, why);
                assertEquals(1, pick.index(), why + ": RtpLimits' own rule picks the same Stone");
                assertEquals(pick.aboveTarget(), o.aboveTarget(), why + ": and agrees on the INFO line");
            }
        }
        assertTrue(shipped(85).odds(1).aboveTarget(), "85 falls between two Stones (84.97% / 85.001%): the "
                + "value just above 85 is taken, with one INFO line");
    }

    @Test
    void aValueExactlyEqualToTheTargetCounts() {
        // Coal alone (weight 4 -> 400) paying "two the same" 1x: the return is 3p^2 - 2p^3 with
        // p = 400 / (400 + Stone). Stone 100 gives p = 4/5 and a return of exactly 112/125 = 89.6%.
        SlotsEngine e = SlotsEngine.solve(map("coal", 4), map("two", 1), List.of(1), 1000, 89.6);
        StakeOdds o = e.odds(1);
        assertNotNull(o, "the table can reach the band");
        assertEquals(100, o.stone(), "the value equal to the target is 'at or below' it and is taken");
        assertEquals(0, o.rtpNum().multiply(BigInteger.valueOf(125)).compareTo(o.rtpDen().multiply(BigInteger.valueOf(112))),
                "exactly 112/125");
        assertFalse(o.aboveTarget(), "no INFO line: the target itself was reachable");
        assertTrue(cmp(oracleRtp(map("coal", 4), map("two", 1), 99, 1, 1000), 89.6) > 0,
                "one Stone lighter is above the target, so 100 is the smallest Stone that gets there");
    }

    @Test
    void aTableThatPaysNothingLeavesNoStake() {
        SlotsEngine none = SlotsEngine.solve(REELS, map("two", 0, "coal", 0, "copper", 0, "iron", 0, "gold", 0,
                "diamond", 0, "wild", 0), STAKES, 250, 90);
        assertFalse(none.playable(), "nothing pays: nothing reaches 85");
        assertEquals(STAKES, none.dropped().stream().map(SlotsEngine.Dropped::stake).toList(),
                "every stake is dropped");
        assertEquals("0.0%", none.dropped().get(0).nearest(), "the WARN can say how far off it is");

        SlotsEngine empty = SlotsEngine.solve(map("coal", 0, "wild", 0), PAYS, STAKES, 250, 90);
        assertFalse(empty.playable(), "every reel weight 0: only Stone could land");

        SlotsEngine stingy = SlotsEngine.solve(map("coal", 1, "copper", 1, "iron", 1, "gold", 1, "diamond", 1),
                map("two", 0, "coal", 1, "copper", 1, "iron", 1, "gold", 1, "diamond", 1), STAKES, 250, 90);
        assertFalse(stingy.playable(), "only three the same at 1×: even with no Stone it gives back 1 in 25");
        assertTrue(stingy.dropped().get(0).nearest().endsWith("%"), stingy.dropped().toString());
    }

    @Test
    void aStakeTheCapStarvesIsDroppedAndTheOthersKept() {
        // max_payout 1 is floored at the largest stake (5): at 5 tokens in, every line pays 5 back.
        SlotsEngine e = SlotsEngine.solve(REELS, PAYS, STAKES, 5, 90);
        assertEquals(List.of(1, 2), e.stakes(), "5 in can never give back 85 when every line returns just the 5");
        assertEquals(1, e.dropped().size());
        assertEquals(5, e.dropped().get(0).stake());
        assertTrue(e.dropped().get(0).under() < RtpLimits.MIN, "its best is under the band: " + e.dropped());
    }

    // ---- the payout cap -----------------------------------------------------------------------

    @Test
    void everyPayoutRespectsTheCap() {
        for (int cap : List.of(250, 100, 30, 10, 5)) {
            SlotsEngine e = SlotsEngine.solve(REELS, PAYS, STAKES, cap, 90);
            for (int stake : e.stakes()) {
                for (Symbol a : Symbol.values()) {
                    for (Symbol b : Symbol.values()) {
                        for (Symbol c : Symbol.values()) {
                            int pay = e.payout(stake, a, b, c);
                            assertTrue(pay <= cap, "cap " + cap + " stake " + stake + " " + a + b + c + " pays " + pay);
                            assertEquals(oraclePay(new Symbol[]{a, b, c}, PAYS, stake, cap), pay,
                                    "cap " + cap + " stake " + stake + " " + a + " " + b + " " + c);
                        }
                    }
                }
                SplittableRandom seeds = new SplittableRandom(SOAK_SEED + cap);
                for (int i = 0; i < 20_000; i++) {
                    assertTrue(e.decide(seeds.nextLong(), stake).payout() <= cap, "cap " + cap + ": a real spin");
                }
            }
        }
    }

    @Test
    void maxPayoutLowersTheRtpMathsCorrectly() {
        SlotsEngine open = SlotsEngine.solve(REELS, PAYS, STAKES, 1_000_000, 90);
        SlotsEngine capped = SlotsEngine.solve(REELS, PAYS, STAKES, 100, 90);
        StakeOdds big = capped.odds(5);
        assertTrue(big.capped(), "at 5 in, gold (100), diamond (200) and wild (250) all hit a cap of 100");
        BigInteger[] exact = oracleRtp(REELS, PAYS, big.stone(), 5, 100);
        assertEquals(0, big.rtpNum().multiply(exact[1]).compareTo(exact[0].multiply(big.rtpDen())),
                "the capped return is the enumeration with capped payouts");
        BigInteger[] uncapped = oracleRtp(REELS, PAYS, big.stone(), 5, 1_000_000);
        assertTrue(exact[0].multiply(uncapped[1]).compareTo(uncapped[0].multiply(exact[1])) < 0,
                "the same reels give back less once the cap binds");
        assertTrue(big.stone() < open.odds(5).stone(), "so less Stone is needed to reach the target");
        assertEquals(open.odds(1).stone(), capped.odds(1).stone(), "1 in never reaches the cap: same Stone");
        assertEquals(100, big.line(Line.THREE_WILDS).payout(), "wild pays the cap, not 250");
        assertEquals(Line.THREE_WILDS, capped.line(5, Symbol.WILD, Symbol.WILD, Symbol.WILD),
                "a tie at the cap goes to the higher line");
        assertFalse(capped.sameAtEveryStake(), "stakes now pay different multiples");
        List<FeedWriter.PayRow> rows = capped.feedRows();
        assertTrue(rows.stream().allMatch(r -> r.stake() != null), "so every website row carries its stake");
        assertTrue(rows.stream().anyMatch(r -> r.stake() == 5 && r.combo().equals("3 wild") && r.pays() == 100),
                "and pays in tokens: " + rows);
    }

    // ---- lines ----------------------------------------------------------------------------------

    @Test
    void theLinesReadTheWayTheSpecSays() {
        SlotsEngine e = shipped(90);
        assertEquals(Line.THREE_WILDS, e.line(1, Symbol.WILD, Symbol.WILD, Symbol.WILD), "three wilds");
        assertEquals(50, e.payout(1, Symbol.WILD, Symbol.WILD, Symbol.WILD));
        assertEquals(Line.THREE_DIAMONDS, e.line(1, Symbol.WILD, Symbol.DIAMOND, Symbol.WILD), "a Wild stands in");
        assertEquals(Line.THREE_COAL, e.line(1, Symbol.COAL, Symbol.WILD, Symbol.COAL), "three coal beats two the same");
        assertEquals(Line.TWO_THE_SAME, e.line(1, Symbol.WILD, Symbol.WILD, Symbol.STONE), "two Wilds with a Stone");
        assertEquals(Line.TWO_THE_SAME, e.line(1, Symbol.IRON, Symbol.STONE, Symbol.IRON), "any two positions");
        assertEquals(Line.TWO_THE_SAME, e.line(1, Symbol.GOLD, Symbol.WILD, Symbol.STONE), "a Wild counting as a match");
        assertNull(e.line(1, Symbol.STONE, Symbol.STONE, Symbol.STONE), "Stone never pays, not even three");
        assertNull(e.line(1, Symbol.WILD, Symbol.STONE, Symbol.STONE), "one Wild alone is no pair");
        assertNull(e.line(1, Symbol.COAL, Symbol.COPPER, Symbol.IRON), "three different ores pay nothing");
        assertEquals(10, e.payout(5, Symbol.COAL, Symbol.STONE, Symbol.COAL), "two the same pays 2 × 5 in");
    }

    @Test
    void theLineChancesAddUpToTheHitRateAndMatchTheEnumeration() {
        SlotsEngine e = shipped(90);
        StakeOdds o = e.odds(5);
        double sum = 0;
        for (LineOdds l : o.lines()) {
            sum += l.chance();
            assertEquals(FeedWriter.oneIn(l.chance()), l.oneIn(), "the screen's 1 in N is the website's");
        }
        assertEquals(o.hitChance(), sum, 1e-12, "each paying spin is exactly one line");
        assertEquals(7, o.lines().size(), "every line can come up");
        double t = o.total();
        double wild = 200 / t;
        assertEquals(wild * wild * wild, o.line(Line.THREE_WILDS).chance(), 1e-15, "three wilds: (200/4679)³");
        assertEquals(12_805, o.line(Line.THREE_WILDS).oneIn(), "about 1 in 13,000 (the spec's check)");
        assertEquals(1_829, o.line(Line.THREE_DIAMONDS).oneIn(), "about 1 in 1,850 at ×1, 1,829 at ×100");
        assertEquals(List.of(Line.values()), o.lines().stream().map(LineOdds::line).toList(), "top line first");
    }

    // ---- the draw -------------------------------------------------------------------------------

    @Test
    void decideIsAPureFunctionOfTheSeed() {
        SlotsEngine e = shipped(90);
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        for (int i = 0; i < 1_000; i++) {
            long seed = seeds.nextLong();
            Spin a = e.decide(seed, 5);
            assertEquals(a, e.decide(seed, 5), "the same seed and stake give the same spin");
            assertEquals(a, shipped(90).decide(seed, 5), "on any engine solved from the same settings");
            assertEquals(e.payout(5, a.reels().get(0), a.reels().get(1), a.reels().get(2)), a.payout(),
                    "the payout is the line's, never anything else");
            assertEquals(a.payout(), e.payout(a), "InstantEngine.payout reads the spin");
        }
        assertThrows(IllegalArgumentException.class, () -> e.decide(1L, 3), "a stake it doesn't take");
        Spin s = e.decide(42L, 2);
        String data = e.data(s);
        assertTrue(data.startsWith("v=1;stake=2;reels="), data);
        assertTrue(data.contains(";stone=1479;total=4679"), "the round keeps what decided it: " + data);
    }

    @Test
    void eachReelLandsAsOftenAsItsWeightSays() {
        SlotsEngine e = shipped(90);
        int n = 300_000;
        long[][] counts = new long[3][Symbol.values().length];
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED ^ 0xABCDL);
        for (int i = 0; i < n; i++) {
            Spin s = e.decide(seeds.nextLong(), 1);
            for (int r = 0; r < 3; r++) {
                counts[r][s.reels().get(r).ordinal()]++;
            }
        }
        for (int r = 0; r < 3; r++) {
            for (Symbol sym : Symbol.values()) {
                double p = e.weight(1, sym) / 4679.0;
                double sd = Math.sqrt(n * p * (1 - p));
                assertTrue(Math.abs(counts[r][sym.ordinal()] - n * p) <= 5 * sd,
                        "reel " + r + " " + sym + ": " + counts[r][sym.ordinal()] + " vs " + n * p);
            }
        }
        assertEquals(1000, e.weight(1, Symbol.COAL), "coal 10 × 100");
        assertEquals(1479, e.weight(1, Symbol.STONE), "Stone is the solved weight");
    }

    // ---- soaks (R1.23) --------------------------------------------------------------------------

    @Test
    void aSeededSoakOfTheShippedTableAgreesWithTheExactValue() {
        soak(shipped(90), 5, 0.005, 1_000_000);
    }

    @Test
    void seededSoaksAtTheClampExtremesAgreeWithTheExactValue() {
        soak(shipped(85), 5, 0.01, 200_000);
        soak(shipped(95), 1, 0.01, 200_000);
    }

    /** n plays with 4σ/√n ≤ {@code points} (as a fraction); |realised − exact| ≤ 4σ/√n and realised < MAX. */
    private static void soak(SlotsEngine e, int stake, double points, int minPlays) {
        StakeOdds o = e.odds(stake);
        double sd = o.sd();
        int n = (int) Math.max(minPlays, Math.ceil(Math.pow(4 * sd / points, 2)));
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        long back = 0;
        for (int i = 0; i < n; i++) {
            back += e.decide(seeds.nextLong(), stake).payout();
        }
        double realised = back / ((double) n * stake);
        double tolerance = 4 * sd / Math.sqrt(n);
        assertTrue(tolerance <= points + 1e-12, "n = " + n + " is big enough");
        assertTrue(Math.abs(realised - o.rtp()) <= tolerance,
                "target " + e.targetPercent() + ": realised " + realised + " vs exact " + o.rtp() + " (±" + tolerance + ")");
        assertTrue(realised < RtpLimits.MAX, "never more than 95 back over a long run: " + realised);
    }

    // ---- values ---------------------------------------------------------------------------------

    @Test
    void onlyMoreBackThanWentInIsAWin() {
        assertFalse(new Spin(5, List.of(Symbol.COAL, Symbol.COAL, Symbol.STONE), Line.TWO_THE_SAME, 5).win(),
                "your 5 back is not a win");
        assertTrue(new Spin(5, List.of(Symbol.COAL, Symbol.COAL, Symbol.STONE), Line.TWO_THE_SAME, 10).win());
        assertFalse(new Spin(5, List.of(Symbol.COAL, Symbol.COAL, Symbol.STONE), Line.TWO_THE_SAME, 10).big(),
                "×2 gets no title");
        assertTrue(new Spin(5, List.of(Symbol.GOLD, Symbol.GOLD, Symbol.GOLD), Line.THREE_GOLD, 100).big(),
                "×20 gets the private title");
        assertFalse(new Spin(1, List.of(Symbol.COAL, Symbol.COPPER, Symbol.STONE), null, 0).win());

        SlotsEngine evens = SlotsEngine.solve(REELS, map("two", 1, "coal", 4, "copper", 6, "iron", 10, "gold", 20,
                "diamond", 40, "wild", 50), STAKES, 250, 90);
        LineOdds two = evens.odds(5).line(Line.TWO_THE_SAME);
        assertEquals(5, two.payout(), "two: 1 returns exactly the tokens put in");
        assertFalse(two.win(), "and is shown as 'your 5 back', never as a win");
    }

    @Test
    void theSameSettingsMakeAnEqualEngine() {
        assertEquals(shipped(90), shipped(90), "equal inputs solve to an equal engine (settings records compare)");
        assertEquals(shipped(90).hashCode(), shipped(90).hashCode());
        assertFalse(shipped(90).equals(shipped(87)), "a different target is a different engine");
        assertFalse(shipped(90).equals(SlotsEngine.solve(REELS, PAYS, STAKES, 200, 90)), "so is a different cap");
    }

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return out;
    }
}
