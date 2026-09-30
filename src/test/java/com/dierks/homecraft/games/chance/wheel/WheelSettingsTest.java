package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Wheel's odds (spec §5.5, R1.7, R1.1): the per-stake scale {@code k} solved exactly from the
 * breakpoints, the checked returns at rtp 85, 90 and 95 for every shipped stake, the rule against
 * losses dressed as wins (base 1 is exactly the tokens back, anything above it at least one token
 * more), the payout cap, and what is refused or dropped with one WARN each.
 *
 * <p>The solve is checked against an independent brute force: every breakpoint of every base,
 * each ring added up again with its own floor, and the R1.1 rule applied by hand.
 */
class WheelSettingsTest {

    private static final List<Double> SHIPPED = WheelSettings.defaults().segments();

    private static WheelSettings withRtp(double rtp) {
        return WheelSettings.parse(new GamesConfig.Node("games.wheel", Map.of("rtp", rtp), w -> { }),
                WheelSettings.defaults());
    }

    private static List<Double> ring(int zeros, int ones, double... bigs) {
        List<Double> out = new ArrayList<>();
        for (double b : bigs) {
            out.add(b);
        }
        for (int i = 0; i < ones; i++) {
            out.add(1.0);
        }
        for (int i = 0; i < zeros; i++) {
            out.add(0.0);
        }
        assertEquals(24, out.size(), "a test ring has 24 spaces");
        return out;
    }

    // ---- the checked values -----------------------------------------------------------------

    @Test
    void theShippedWheelGivesBackTheCheckedValuesAtEveryStake() {
        WheelSettings s = WheelSettings.defaults();
        assertEquals(List.of(5, 10, 20), s.open(), "every shipped stake is solvable");
        assertEquals(new RtpLimits.Ratio(105, 120).value(), s.odds(5).rtp(), 1e-12, "stake 5 gives back 87.5%");
        assertEquals(new RtpLimits.Ratio(215, 240).value(), s.odds(10).rtp(), 1e-12, "stake 10 gives back 89.58%");
        assertEquals(0.90, s.odds(20).rtp(), 1e-12, "stake 20 gives back exactly 90%");
        assertEquals(List.of(44, 35, 17, 10, 0), s.odds(10).distinct(),
                "the prizes at stake 10 are the spec's 0, 10, 17, 35, 44");
        assertEquals(87.5, RtpLimits.tenthPercent(s.odds(5).rtp()), "admins read 87.5");
        assertEquals(89.5, RtpLimits.tenthPercent(s.odds(10).rtp()), "admins read 89.5 (floored, never rounded up)");
        assertEquals(89, RtpLimits.wholePercent(s.odds(10).rtp()), "players read 89 at stake 10");
    }

    @Test
    void theSolveRuleHoldsAtRtp85And95ForEveryShippedStake() {
        WheelSettings at85 = withRtp(85);
        assertEquals(new RtpLimits.Ratio(102, 120), at85.odds(5).ratio(), "stake 5 reaches 85.0% exactly");
        assertEquals(new RtpLimits.Ratio(205, 240), at85.odds(10).ratio(),
                "stake 10 can't reach 85% exactly; nothing at or under is in the band, so 85.42% (the smallest above)");
        assertEquals(new RtpLimits.Ratio(408, 480), at85.odds(20).ratio(), "stake 20 reaches 85.0% exactly");

        WheelSettings at95 = withRtp(95);
        assertEquals(new RtpLimits.Ratio(114, 120), at95.odds(5).ratio(), "stake 5 reaches 95.0% exactly");
        assertEquals(new RtpLimits.Ratio(225, 240), at95.odds(10).ratio(), "stake 10: 93.75%, the largest under 95");
        assertEquals(new RtpLimits.Ratio(455, 480), at95.odds(20).ratio(), "stake 20: 94.79%, the largest under 95");

        List<String> infos = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        WheelSettings.solve("games.wheel", List.of(5, 10, 20), 85, SHIPPED, 250, warns::add, infos::add);
        assertEquals(List.of(), warns, "every stake is in the band at 85");
        assertEquals(List.of("wheel stake 10: 85.4% (target 85 not reachable in whole tokens)"), infos,
                "one INFO for the stake that had to land above its target");
    }

    @Test
    void theSolveMatchesABruteForceOverEveryBreakpoint() {
        List<List<Double>> rings = List.of(SHIPPED,
                ring(10, 6, 2.5, 2.5, 3, 1.5, 7, 12, 1.25, 4),
                ring(20, 0, 3, 3, 3, 30),
                ring(12, 9, 1.1, 1.1, 50));
        for (List<Double> segments : rings) {
            for (int cap : new int[]{250, 40}) {
                for (int stake : new int[]{1, 2, 5, 10, 20, 37}) {
                    for (double rtp : new double[]{85, 87.5, 90, 93, 95}) {
                        String what = segments + " stake " + stake + " rtp " + rtp + " cap " + cap;
                        WheelMath.Solve s = WheelMath.solve(stake, segments, Math.max(cap, stake),
                                RtpLimits.Ratio.percent(rtp));
                        Long expected = bruteForce(stake, segments, Math.max(cap, stake), RtpLimits.Ratio.percent(rtp));
                        if (expected == null) {
                            assertNull(s.odds(), what + ": nothing reachable is in the band, so the stake is dropped");
                            continue;
                        }
                        assertNotNull(s.odds(), what + ": the brute force found a value in the band");
                        assertEquals(expected.longValue(), s.odds().back(),
                                what + ": the solve picks what the R1.1 rule picks from every reachable value");
                        long sum = 0;
                        for (int p : s.odds().prizes()) {
                            sum += p;
                        }
                        assertEquals(sum, s.odds().back(), what + ": the published return is the prizes added up");
                        assertTrue(RtpLimits.inBand(s.odds().ratio()), what + ": inside [85, 95]");
                    }
                }
            }
        }
    }

    /**
     * Every total the ring can reach, found the slow way (each base's every breakpoint, each ring
     * floored again from scratch), then the R1.1 rule by hand. {@code null} = nothing in the band.
     */
    private static Long bruteForce(int in, List<Double> segments, int cap, RtpLimits.Ratio target) {
        List<long[]> bases = new ArrayList<>();
        for (double v : segments) {
            BigDecimal d = BigDecimal.valueOf(v);
            BigInteger p = d.unscaledValue();
            BigInteger q = BigInteger.ONE;
            if (d.scale() > 0) {
                q = BigInteger.TEN.pow(d.scale());
            } else if (d.scale() < 0) {
                p = p.multiply(BigInteger.TEN.pow(-d.scale()));
            }
            bases.add(new long[]{p.longValueExact(), q.longValueExact()});
        }
        TreeSet<Long> totals = new TreeSet<>();
        totals.add(total(in, bases, cap, null, 0));
        for (long[] b : bases) {
            if (b[0] <= b[1]) {
                continue;
            }
            for (long j = in + 1; j <= Math.min(cap, 24L * in + 2); j++) {
                totals.add(total(in, bases, cap, b, j));
            }
        }
        long den = 24L * in;
        Long below = null;
        Long above = null;
        for (long t : totals) {
            RtpLimits.Ratio r = new RtpLimits.Ratio(t, den);
            if (r.compareTo(target) <= 0) {
                below = t;
            }
            if (above == null && r.compareTo(RtpLimits.MIN_RATIO) >= 0) {
                above = t;
            }
        }
        if (below != null && RtpLimits.inBand(new RtpLimits.Ratio(below, den))) {
            return below;
        }
        if (above != null && RtpLimits.inBand(new RtpLimits.Ratio(above, den))) {
            return above;
        }
        return null;
    }

    /** The ring's total at {@code k = j / (in · base)} (or just above 0 when {@code base} is null). */
    private static long total(int in, List<long[]> bases, int cap, long[] base, long j) {
        long t = 0;
        for (long[] b : bases) {
            if (b[0] == 0) {
                continue;
            }
            if (b[0] == b[1]) {
                t += in;
                continue;
            }
            long floor = 0;
            if (base != null) {
                // in · (p/q) · (j · qb / (in · pb)) = p · j · qb / (q · pb)
                BigInteger num = BigInteger.valueOf(b[0]).multiply(BigInteger.valueOf(j)).multiply(BigInteger.valueOf(base[1]));
                BigInteger den = BigInteger.valueOf(b[1]).multiply(BigInteger.valueOf(base[0]));
                floor = num.divide(den).longValueExact();
            }
            t += Math.min(cap, Math.max(in + 1L, floor));
        }
        return t;
    }

    // ---- no losses dressed as wins ----------------------------------------------------------

    @Test
    void baseOneIsExactlyTheTokensBackAndNeverAWin() {
        for (double rtp : new double[]{85, 90, 95}) {
            WheelSettings s = withRtp(rtp);
            for (WheelOdds o : s.odds()) {
                for (int space = 0; space < 24; space++) {
                    if (s.segments().get(space) == 1.0) {
                        assertEquals(o.stake(), o.prize(space), "base 1 gives back exactly the tokens put in");
                        assertEquals(WheelOdds.Result.BACK, o.result(o.prize(space)), "and reads as tokens back");
                        assertEquals("&fYour " + o.stake() + " back", o.label(o.prize(space)),
                                "a neutral label, never a win");
                    }
                }
            }
        }
    }

    @Test
    void everyPrizeAboveOneBaseIsAtLeastOneTokenMoreThanWasPutIn() {
        List<List<Double>> rings = List.of(SHIPPED, ring(4, 4, 1.01, 1.01, 1.01, 1.01, 1.01, 1.01, 1.01, 1.01,
                1.01, 1.01, 1.01, 1.01, 1.01, 1.01, 1.01, 1.01), ring(21, 0, 1.2, 1.2, 25));
        for (List<Double> segments : rings) {
            for (int stake : new int[]{1, 3, 5, 10, 20, 50}) {
                for (double rtp : new double[]{85, 90, 95}) {
                    WheelMath.Solve s = WheelMath.solve(stake, segments, 250, RtpLimits.Ratio.percent(rtp));
                    if (s.odds() == null) {
                        continue;
                    }
                    for (int space = 0; space < 24; space++) {
                        double base = segments.get(space);
                        int prize = s.odds().prize(space);
                        if (base > 1) {
                            assertTrue(prize >= stake + 1, segments + " stake " + stake + ": a space above base 1 "
                                    + "pays at least one token more than was put in, not " + prize);
                            assertEquals(WheelOdds.Result.WIN, s.odds().result(prize), "and it is a win");
                        } else if (base == 0) {
                            assertEquals(0, prize, "base 0 pays nothing");
                        }
                    }
                }
            }
        }
    }

    @Test
    void maxPayoutCapsEveryPrizeAndTheMathsUsesTheCappedValues() {
        List<Double> segments = ring(20, 0, 2, 2, 2, 40);
        WheelMath.Solve free = WheelMath.solve(10, segments, 10_000, RtpLimits.Ratio.percent(90));
        assertNotNull(free.odds());
        assertTrue(free.odds().top() > 120, "uncapped, the base-40 space pays over 120: " + free.odds().prizes());

        WheelMath.Solve capped = WheelMath.solve(10, segments, 120, RtpLimits.Ratio.percent(90));
        assertNotNull(capped.odds(), "the other spaces can still carry 90%");
        assertTrue(capped.odds().top() <= 120, "no prize passes max_payout: " + capped.odds().prizes());
        long sum = capped.odds().prizes().stream().mapToLong(Integer::longValue).sum();
        assertEquals(sum, capped.odds().back(), "the return is worked out from the capped prizes");
        assertTrue(RtpLimits.inBand(capped.odds().ratio()), "and it is still inside the band");
    }

    // ---- what is refused, dropped or closed -------------------------------------------------

    @Test
    void aStakeThatCantReachTheBandIsDroppedWithOneWarnNamingIt() {
        // 18 spaces give the tokens back and 3 pay at least one more: at 1 token in that is
        // already 24 of 24 back (100%), at 10 tokens in it starts at 88.75%.
        List<Double> segments = ring(3, 18, 2, 2, 2);
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<WheelOdds> odds = WheelSettings.solve("games.wheel", List.of(1, 10), 90, segments, 250,
                warns::add, infos::add);
        assertEquals(List.of(10), odds.stream().map(WheelOdds::stake).toList(), "stake 1 is dropped, 10 stays");
        assertEquals(1, warns.size(), "one WARN for the dropped stake: " + warns);
        assertTrue(warns.get(0).startsWith("games.wheel.segments "), "it names the key: " + warns);
        assertTrue(warns.get(0).contains("stake 1 ") && warns.get(0).contains("100.0%"),
                "it names the stake and the nearest value: " + warns);
        assertEquals(List.of(), infos, "nothing else to say");
    }

    @Test
    void anImpossibleWheelClosesWithOneWarn() {
        List<String> warns = new ArrayList<>();
        List<Double> zeros = Collections.nCopies(24, 0.0);
        WheelSettings s = WheelSettings.parse(new GamesConfig.Node("games.wheel", Map.of("segments", zeros), warns::add),
                WheelSettings.defaults());
        assertTrue(s.odds().isEmpty(), "no stake can give anything back");
        assertTrue(s.open().isEmpty(), "so there is nothing to pick");
        assertNull(s.lowest(), "and no headline number");
        assertEquals(1, warns.size(), "one WARN, not one per stake: " + warns);
        assertTrue(warns.get(0).startsWith("games.wheel ") && warns.get(0).contains("closed until it is fixed"),
                warns.toString());
        assertEquals(List.of(), Wheel.oddsLines(s), "a closed wheel has no odds line");
    }

    @Test
    void aCapThatHoldsAStakeDownDropsItWithOneWarnSayingSo() {
        // max_payout floored at the largest stake (20): stakes 10 and 20 can no longer reach 85.
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<WheelOdds> odds = WheelSettings.solve("games.wheel", List.of(5, 10, 20), 90, SHIPPED, 20,
                warns::add, infos::add);
        assertEquals(List.of(5), odds.stream().map(WheelOdds::stake).toList(), "only stake 5 still fits");
        assertEquals(2, warns.size(), "one WARN per dropped stake: " + warns);
        assertTrue(warns.get(0).startsWith("games.wheel.stakes 10 is off: max_payout 20"),
                "it names the stake and says the cap holds it back, not the spaces: " + warns);
        assertEquals(List.of(), infos);
        for (int p : odds.get(0).prizes()) {
            assertTrue(p <= 20, "no prize passes the cap");
        }
    }

    @Test
    void aBaseBetweenZeroAndOneOrTheWrongLengthIsRefusedWithOneWarn() {
        List<Double> half = new ArrayList<>(SHIPPED);
        half.set(3, 0.5);
        List<String> warns = new ArrayList<>();
        GamesConfig.Node n = new GamesConfig.Node("games.wheel", Map.of("segments", half), warns::add);
        WheelSettings s = WheelSettings.parse(n, WheelSettings.defaults());
        assertTrue(n.invalid(), "a space of 0.5 would be a loss dressed as a prize: the wheel is closed");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.wheel.segments "), warns.toString());
        assertEquals(SHIPPED, s.segments(), "the shipped ring is kept meanwhile");

        warns.clear();
        GamesConfig.Node shortRing = new GamesConfig.Node("games.wheel", Map.of("segments", SHIPPED.subList(0, 23)),
                warns::add);
        WheelSettings.parse(shortRing, WheelSettings.defaults());
        assertTrue(shortRing.invalid(), "23 spaces is not a wheel");
        assertEquals(1, warns.size(), warns.toString());
    }

    @Test
    void eightOrTwelveBasesAreAPatternRepeatedAroundTheRing() {
        for (List<Double> pattern : List.of(List.of(0.0, 1.0, 0.0, 2.0, 0.0, 0.0, 1.0, 4.0),
                List.of(5.0, 0.0, 1.0, 0.0, 2.0, 0.0, 0.0, 1.0, 0.0, 4.0, 0.0, 1.0))) {
            List<String> warns = new ArrayList<>();
            GamesConfig.Node n = new GamesConfig.Node("games.wheel", Map.of("segments", pattern), warns::add);
            WheelSettings s = WheelSettings.parse(n, WheelSettings.defaults());
            assertEquals(List.of(), warns, pattern.size() + " bases are fine");
            assertFalse(n.invalid());
            assertEquals(24, s.segments().size(), "the ring always has 24 spaces");
            for (int i = 0; i < 24; i++) {
                assertEquals(pattern.get(i % pattern.size()), s.segments().get(i), "space " + i + " repeats the pattern");
            }
        }
        for (int bad : new int[]{6, 10, 23, 25}) {
            List<String> warns = new ArrayList<>();
            GamesConfig.Node n = new GamesConfig.Node("games.wheel",
                    Map.of("segments", Collections.nCopies(bad, 1.0)), warns::add);
            WheelSettings.parse(n, WheelSettings.defaults());
            assertTrue(n.invalid(), bad + " bases don't make a ring");
            assertEquals(1, warns.size(), "one WARN: " + warns);
            assertTrue(warns.get(0).startsWith("games.wheel.segments has " + bad + " spaces"), warns.toString());
        }
    }

    @Test
    void theShippedBlockReadsAsTheDefaultsWithoutAWord() {
        List<String> warns = new ArrayList<>();
        GamesConfig.Node n = new GamesConfig.Node("games.wheel", Map.of("enabled", true, "stakes", List.of(5, 10, 20),
                "daily_limit", 30, "rtp", 90, "segments", SHIPPED), warns::add);
        assertEquals(WheelSettings.defaults(), WheelSettings.parse(n, WheelSettings.defaults()),
                "the solved odds are part of the settings, and the shipped block solves to the defaults");
        assertEquals(List.of(), warns);
        assertFalse(n.invalid());
    }

    @Test
    void theShippedRingNeverPutsThreeZerosInARow() {
        int pairs = 0;
        for (int i = 0; i < 24; i++) {
            boolean z0 = SHIPPED.get(i) == 0;
            boolean z1 = SHIPPED.get((i + 1) % 24) == 0;
            boolean z2 = SHIPPED.get((i + 2) % 24) == 0;
            assertFalse(z0 && z1 && z2, "no three zeros in a row from space " + i);
            if (z0 && z1) {
                pairs++;
            }
        }
        assertEquals(2, pairs, "13 zeros on a ring of 24 need at least two zero pairs, and there are only two");
        assertEquals(13, Collections.frequency(SHIPPED, 0.0));
        assertEquals(5, Collections.frequency(SHIPPED, 1.0));
    }
}
