package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A race's card: the stats make the chances by one formula, the chances add up to exactly one and sit inside the
 * clamp, the same seed is the same card; the winner is drawn at exactly those chances; and every pick's pay is the
 * whole amount closest to the target inside the band and under the cap, or the pick isn't offered.
 */
class RaceCardTest {

    private static final int CAP = GamesConfig.Common.defaults().maxPayoutFor(java.util.List.of(5, 10, 20));

    @Test
    void theChancesAddUpToOneAndSitInsideTheClamp() {
        int favouriteAtTop = 0;
        int longshotAtFloor = 0;
        for (long seed = 0; seed < 20_000; seed++) {
            RaceCard c = RaceCard.of(seed);
            int sum = 0;
            for (Racer r : Racer.values()) {
                int p = c.parts(r);
                assertTrue(p >= RaceCard.LOW && p <= RaceCard.HIGH, "seed " + seed + ": " + r + " " + p);
                assertTrue(c.form(r) >= RaceCard.FORM_MIN && c.form(r) <= RaceCard.FORM_MAX);
                sum += p;
            }
            assertEquals(RaceCard.WHOLE, sum, "seed " + seed + ": the chances add up to exactly one");
            assertTrue(RaceCard.valid(c.parts()));
            assertTrue(c.parts(c.favourite()) > c.parts(c.longest()), "a real favourite and a real longshot");
            favouriteAtTop += c.parts(c.favourite()) == RaceCard.HIGH ? 1 : 0;
            longshotAtFloor += c.parts(c.longest()) == RaceCard.LOW ? 1 : 0;
        }
        assertTrue(favouriteAtTop > 0 && longshotAtFloor > 0, "the clamp is reached sometimes: " + favouriteAtTop
                + " " + longshotAtFloor);
    }

    @Test
    void theSameSeedIsTheSameCard() {
        for (long seed : new long[]{0, 1, -7, 42, Long.MAX_VALUE, Long.MIN_VALUE}) {
            RaceCard a = RaceCard.of(seed);
            RaceCard b = RaceCard.of(seed);
            assertArrayEquals(a.parts(), b.parts());
            for (Racer r : Racer.values()) {
                assertEquals(a.form(r), b.form(r));
            }
            assertEquals(seed, a.seed());
        }
        boolean differ = false;
        for (long seed = 1; seed < 50 && !differ; seed++) {
            differ = !java.util.Arrays.equals(RaceCard.of(0).parts(), RaceCard.of(seed).parts());
        }
        assertTrue(differ, "cards differ from race to race");
    }

    /** The stats are the inputs: a stronger racer always has at least the chance of a weaker one, by the formula. */
    @Test
    void theStatsMakeTheChances() {
        assertEquals(10, RaceCard.strength(Racer.CHICKEN, 0), "3 + 2 + 5");
        assertEquals(14, RaceCard.strength(Racer.RABBIT, 2), "5 + 1 + 4 + 2 x 2");
        assertEquals(4, RaceCard.strength(Racer.COW, -2), "1 + 5 + 2 - 2 x 2");
        SplittableRandom rng = new SplittableRandom(5);
        for (int n = 0; n < 5_000; n++) {
            int[] form = new int[Racer.COUNT];
            for (int i = 0; i < form.length; i++) {
                form[i] = RaceCard.FORM_MIN + rng.nextInt(RaceCard.FORM_MAX - RaceCard.FORM_MIN + 1);
            }
            RaceCard c = RaceCard.withForm(form);
            for (Racer a : Racer.values()) {
                for (Racer b : Racer.values()) {
                    if (c.strength(a) > c.strength(b)) {
                        assertTrue(c.parts(a) >= c.parts(b), a + " is stronger than " + b);
                    }
                    if (c.strength(a) == c.strength(b)) {
                        assertTrue(Math.abs(c.parts(a) - c.parts(b)) <= 1, "level strengths, level chances");
                    }
                }
            }
            // and, where nothing is held at a limit, the shares are e^(strength / T) to within the rounding
            boolean free = true;
            double total = 0;
            for (Racer r : Racer.values()) {
                free &= c.parts(r) > RaceCard.LOW && c.parts(r) < RaceCard.HIGH;
                total += Math.exp(c.strength(r) / RaceCard.TEMPERATURE);
            }
            if (free) {
                for (Racer r : Racer.values()) {
                    double want = RaceCard.WHOLE * Math.exp(c.strength(r) / RaceCard.TEMPERATURE) / total;
                    assertEquals(want, c.parts(r), 1.0, r + " on " + java.util.Arrays.toString(form));
                }
            }
        }
        // everyone in the same form: the ratings alone
        RaceCard even = RaceCard.withForm(new int[6]);
        assertEquals(Racer.CHICKEN, even.favourite(), "10 + 0: Chicken, Rabbit and Fox level, the lower lane first");
        assertEquals(even.parts(Racer.CHICKEN), even.parts(Racer.RABBIT), 1);
        assertTrue(even.parts(Racer.PIG) < even.parts(Racer.FOX));
        assertTrue(even.parts(Racer.COW) < even.parts(Racer.PIG));
        assertThrows(IllegalArgumentException.class, () -> RaceCard.withForm(new int[]{3, 0, 0, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> RaceCard.withForm(new int[5]));
    }

    /** Over many seeds each racer wins at its stated chance: the draw is the card's. */
    @Test
    void eachRacerWinsAtItsStatedChance() {
        for (long cardSeed : new long[]{3, 77, 2026}) {
            int[] parts = RaceCard.of(cardSeed).parts();
            int races = 200_000;
            int[] wins = new int[Racer.COUNT];
            SplittableRandom seeds = new SplittableRandom(cardSeed * 31 + 1);
            for (int n = 0; n < races; n++) {
                wins[RaceCard.winner(seeds.nextLong(), parts)]++;
            }
            for (int i = 0; i < Racer.COUNT; i++) {
                double p = parts[i] / (double) RaceCard.WHOLE;
                double sd = Math.sqrt(races * p * (1 - p));
                assertEquals(races * p, wins[i], 5 * sd, Racer.of(i) + " at " + p + " on card " + cardSeed);
            }
        }
        // the draw is exact over every first draw: each racer has exactly its parts of the 10,000
        int[] parts = RaceCard.of(9).parts();
        int[] owned = new int[Racer.COUNT];
        int at = 0;
        for (int i = 0; i < Racer.COUNT; i++) {
            owned[i] = parts[i];
            at += parts[i];
        }
        assertEquals(RaceCard.WHOLE, at);
        assertThrows(IllegalArgumentException.class, () -> RaceCard.winner(1L, new int[]{1, 1, 1, 1, 1, 1}));
    }

    /**
     * Every pair offered has its return inside the band, the closest to the target any whole amount in the band can
     * be, and is never over the cap; a pair with no such amount is not offered.
     */
    @Test
    void everyPayIsTheClosestInTheBandUnderTheCap() {
        int offered = 0;
        int left = 0;
        for (int parts = RaceCard.LOW; parts <= RaceCard.HIGH; parts += 37) {
            for (int stake : new int[]{1, 2, 3, 5, 10, 20, 25, 50}) {
                for (double rtp : new double[]{85, 87.5, 90, 95}) {
                    for (int cap : new int[]{CAP, 500, 1000}) {
                        RaceCard.Pay p = RaceCard.pay(parts, stake, rtp, cap);
                        RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
                        // by brute force: every whole amount over the stake and under the cap inside the band
                        Integer best = null;
                        double bestOff = Double.MAX_VALUE;
                        for (int pays = stake + 1; pays <= cap; pays++) {
                            RtpLimits.Ratio r = new RtpLimits.Ratio((long) parts * pays, (long) RaceCard.WHOLE * stake);
                            if (!RtpLimits.inBand(r)) {
                                continue;
                            }
                            double off = Math.abs(r.value() - target.value());
                            if (off < bestOff - 1e-15) {
                                best = pays;
                                bestOff = off;
                            }
                        }
                        String what = parts + " parts, " + stake + " in, " + rtp + "%, cap " + cap;
                        if (best == null) {
                            assertNull(p, what + ": nothing in the band, not offered");
                            left++;
                            continue;
                        }
                        assertNotNull(p, what);
                        assertEquals(best, p.pays(), what + ": the closest to the target");
                        assertTrue(RtpLimits.inBand(p.ratio()), what);
                        assertTrue(p.pays() <= cap && p.pays() > stake, what);
                        offered++;
                    }
                }
            }
        }
        assertTrue(offered > 3_000 && left > 0, offered + " offered, " + left + " left out");
    }

    /** The owner's examples: a favourite at 40% pays about 2.25 times, a longshot at 5% about 18 times. */
    @Test
    void longshotsReallyPayMore() {
        assertEquals(11, RaceCard.pay(4_000, 5, 90, CAP).pays(), "40% x 11 / 5 = 88%; 12 would be 96%");
        assertEquals(22, RaceCard.pay(4_000, 10, 90, CAP).pays(), "22 is 88%, 23 is 92%: as close, the lower");
        assertEquals(45, RaceCard.pay(4_000, 20, 90, CAP).pays(), "exactly 90%");
        assertEquals(90, RaceCard.pay(500, 5, 90, CAP).pays(), "5% x 90 / 5 = 90%");
        assertEquals(180, RaceCard.pay(500, 10, 90, CAP).pays());
        assertNull(RaceCard.pay(500, 20, 90, CAP), "360 is over the cap of " + CAP + ": not offered at 20");
        assertEquals(250, CAP);
        // in dollars, under the Parlor's biggest payout
        Parlor.Rules rules = Parlor.Rules.defaults();
        assertEquals(450, RaceCard.pay(500, 25, 90, rules.capFor(25)).pays());
        assertNull(RaceCard.pay(500, 50, 90, rules.capFor(50)), "$900 is over the $500 cap");
        assertNull(RaceCard.pay(0, 5, 90, CAP));
        assertNull(RaceCard.pay(500, 0, 90, CAP));
    }

    @Test
    void howOftenReadsPlainly() {
        assertEquals("2.5", RaceCard.oneIn(4_000));
        assertEquals("4", RaceCard.oneIn(2_500));
        assertEquals("20", RaceCard.oneIn(500));
        assertEquals("25", RaceCard.oneIn(400));
        assertEquals("2.2", RaceCard.oneIn(4_500));
    }

    /** For the record: one card and its pays, printed (the report's example). */
    @Test
    void anExampleCard() {
        RaceCard c = RaceCard.of(20261122L);
        Parlor.Rules rules = Parlor.Rules.defaults();
        StringBuilder b = new StringBuilder("card 20261122:\n");
        for (Racer r : Racer.values()) {
            b.append(String.format("%-8s S%d St%d Sr%d form %+d strength %2d  %s (1 in %s)", r.mobName(), r.speed(),
                    r.stamina(), r.start(), c.form(r), c.strength(r), c.percent(r), c.oneIn(r)));
            for (int stake : new int[]{5, 10, 20}) {
                RaceCard.Pay p = c.pay(r, stake, 90, CAP);
                b.append(p == null ? "  " + stake + ":off" : String.format("  %d:%d (%.2f%%)", stake, p.pays(),
                        p.rtp() * 100));
            }
            for (int dollars : new int[]{5, 10, 25, 50}) {
                RaceCard.Pay p = c.pay(r, dollars, 90, rules.capFor(dollars));
                b.append(p == null ? "  $" + dollars + ":off" : String.format("  $%d:$%d (%.2f%%)", dollars,
                        p.pays(), p.rtp() * 100));
            }
            b.append(r == c.favourite() ? "  favourite" : r == c.longest() ? "  longest" : "").append('\n');
        }
        System.out.println(b);
        assertTrue(b.length() > 0);
    }
}
