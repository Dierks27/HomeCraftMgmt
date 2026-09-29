package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.assertSound;
import static com.dierks.homecraft.games.cup.CupFixtures.field;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.timed;
import static com.dierks.homecraft.games.cup.CupFixtures.tokens;
import static com.dierks.homecraft.games.cup.CupFixtures.tokensOf;
import static com.dierks.homecraft.games.cup.CupFixtures.untimed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup's payout table (§D2): 1 entrant is refunded with no top-up, 2 share 70/30, 3 or more
 * share 50/30/20, the server's top-up comes only with 2 or more, every amount is rounded down and
 * the remainder goes to 1st; and the plan for 1, 2, 3 and 10 entrants, token for token.
 */
class CupPayoutTableTest {

    @Test
    void theTableIsSeventyThirtyForTwoAndFiftyThirtyTwentyForThreeOrMore() {
        assertArrayEquals(new int[0], CupRules.shares(0), "nobody raced: no places");
        assertArrayEquals(new int[0], CupRules.shares(1), "one Cup time is no contest: no places");
        assertArrayEquals(new int[]{70, 30}, CupRules.shares(2), "two Cup times share 70/30");
        for (int n = 3; n <= 50; n++) {
            assertArrayEquals(new int[]{50, 30, 20}, CupRules.shares(n), n + " Cup times share 50/30/20");
        }
        int[] a = CupRules.shares(3);
        a[0] = 99;
        assertArrayEquals(new int[]{50, 30, 20}, CupRules.shares(3), "the table can't be changed through a returned copy");
    }

    @Test
    void anAmountIsRoundedDownAndTheRemainderGoesToFirst() {
        assertArrayEquals(new int[]{18, 10, 7}, CupRules.split(35, new int[]{50, 30, 20}),
                "17.5, 10.5 and 7 round down to 17, 10 and 7; the 1 left over goes to 1st");
        assertArrayEquals(new int[]{13, 7, 5}, CupRules.split(25, new int[]{50, 30, 20}),
                "12.5, 7.5 and 5 round down to 12, 7 and 5; the 1 left over goes to 1st");
        assertArrayEquals(new int[]{70, 29}, CupRules.split(99, new int[]{70, 30}),
                "69.3 and 29.7 round down to 69 and 29; the 1 left over goes to 1st, never to 2nd");
        assertArrayEquals(new int[]{1, 0}, CupRules.split(1, new int[]{70, 30}), "a 1-token pool is all 1st's");
        assertArrayEquals(new int[]{0, 0, 0}, CupRules.split(0, new int[]{50, 30, 20}), "an empty pool pays nothing");
        assertArrayEquals(new int[]{30, 18, 12}, CupRules.split(60, new int[]{50, 30, 20}), "60 splits exactly");
        assertThrows(IllegalArgumentException.class, () -> CupRules.split(-1, new int[]{70, 30}),
                "a negative pool is a bug, not a Cup");
    }

    @Test
    void theSplitAlwaysAddsUpToThePoolAndNeverPaysALaterPlaceMore() {
        for (int[] table : List.of(new int[]{70, 30}, new int[]{50, 30, 20})) {
            for (int pool = 0; pool <= 5000; pool++) {
                int[] s = CupRules.split(pool, table);
                int sum = 0;
                for (int v : s) {
                    sum += v;
                    assertTrue(v >= 0, "no place is ever negative (pool " + pool + ")");
                }
                assertEquals(pool, sum, "every token of a " + pool + "-token pool is paid, none kept");
                for (int i = 1; i < s.length; i++) {
                    assertTrue(s[i - 1] >= s[i], "a better place never gets less (pool " + pool + ")");
                }
                assertTrue(s[0] - (int) ((long) pool * table[0] / 100) < table.length,
                        "the remainder 1st gets is less than the number of places (pool " + pool + ")");
            }
        }
    }

    @Test
    void oneEntrantGetsTheirEntryBackAndTheServerAddsNothing() {
        List<CupEntry> one = List.of(timed(1, 5, 41_000));
        CupPlan plan = CupRules.settle(CUP, one, 10);
        assertEquals(CupPlan.Outcome.REFUND_ALONE, plan.outcome(), "a lone entrant raced nobody");
        assertEquals(0, plan.topup(), "the top-up comes only with 2 or more entrants");
        assertEquals(5, plan.pool(), "the pool is just their entry");
        assertEquals(List.of(5), tokens(plan), "their 5 tokens come back");
        assertEquals(CupPayout.Kind.REFUND, plan.lines().get(0).kind(), "it is written as a refund, not a prize");
        assertEquals(0, plan.lines().get(0).place(), "a refund has no place");
        assertSound(one, 10, plan, "one entrant");

        List<CupEntry> noTime = List.of(untimed(1, 5));
        CupPlan plan2 = CupRules.settle(CUP, noTime, 10);
        assertEquals(CupPlan.Outcome.REFUND_ALONE, plan2.outcome(), "alone without a time is still alone");
        assertEquals(List.of(5), tokens(plan2), "their 5 tokens come back");
    }

    @Test
    void twoEntrantsShareSeventyThirtyOfTheEntriesPlusTheTopUp() {
        List<CupEntry> two = field(2);
        CupPlan plan = CupRules.settle(CUP, two, 10);
        assertEquals(CupPlan.Outcome.PRIZES, plan.outcome(), "two Cup times are a contest");
        assertEquals(10, plan.entries(), "two entries of 5");
        assertEquals(10, plan.topup(), "the top-up comes with 2 entrants");
        assertEquals(20, plan.pool(), "entries plus the top-up");
        assertEquals(List.of(14, 6), tokens(plan), "70% of 20 is 14 and 30% is 6");
        assertEquals(1, plan.lines().get(0).place(), "the faster time is 1st");
        assertEquals(p(1), plan.lines().get(0).player(), "player 1 has the faster time");
        assertEquals(2, plan.lines().get(1).place(), "the slower time is 2nd");
        assertSound(two, 10, plan, "two entrants");

        CupPlan odd = CupRules.settle(CUP, List.of(timed(1, 5, 50_000), timed(2, 6, 40_000)), 10);
        assertEquals(21, odd.pool(), "5 + 6 + 10");
        assertEquals(15, tokensOf(odd, 2), "70% of 21 is 14.7: 14, plus the 1 left over, to 1st");
        assertEquals(6, tokensOf(odd, 1), "30% of 21 is 6.3: 6");
    }

    @Test
    void threeEntrantsShareFiftyThirtyTwentyWithTheRemainderToFirst() {
        List<CupEntry> three = field(3);
        CupPlan plan = CupRules.settle(CUP, three, 10);
        assertEquals(25, plan.pool(), "15 in entries and the top-up of 10");
        assertEquals(List.of(13, 7, 5), tokens(plan), "12.5, 7.5 and 5 round down to 12, 7 and 5, and 1st gets the 1 left");
        assertEquals(List.of(1, 2, 3), List.of(plan.lines().get(0).place(), plan.lines().get(1).place(),
                plan.lines().get(2).place()), "places follow Cup time");
        assertSound(three, 10, plan, "three entrants");
    }

    @Test
    void tenEntrantsPayTheTopThreeAndTellTheRestTheirPlace() {
        List<CupEntry> ten = field(10);
        CupPlan plan = CupRules.settle(CUP, ten, 10);
        assertEquals(60, plan.pool(), "50 in entries and the top-up of 10");
        assertEquals(List.of(30, 18, 12, 0, 0, 0, 0, 0, 0, 0), tokens(plan), "50/30/20 of 60, nothing below 3rd");
        for (int i = 0; i < 10; i++) {
            CupPayout l = plan.lines().get(i);
            assertEquals(i + 1, l.place(), "line " + i + " is place " + (i + 1));
            assertEquals(p(i + 1), l.player(), "player " + (i + 1) + " has the " + (i + 1) + "th time");
            assertEquals(i < 3 ? CupPayout.Kind.PRIZE : CupPayout.Kind.NONE, l.kind(),
                    "only the top three are paid");
        }
        assertEquals(3, plan.payouts().size(), "three ledger payments");
        assertSound(ten, 10, plan, "ten entrants");

        CupPlan odd = CupRules.settle(CUP, ten, 7);
        assertEquals(57, odd.pool(), "50 in entries and a top-up of 7");
        assertEquals(List.of(29, 17, 11, 0, 0, 0, 0, 0, 0, 0), tokens(odd),
                "28.5, 17.1 and 11.4 round down to 28, 17 and 11; 1st gets the 1 left");
        assertSound(ten, 7, odd, "ten entrants, odd pool");
    }

    @Test
    void theTopUpIsAddedOnlyWithTwoOrMoreAndNeverBelowZero() {
        assertEquals(0, CupRules.settle(CUP, field(1), 10).topup(), "no top-up for a lone entrant");
        assertEquals(10, CupRules.settle(CUP, field(2), 10).topup(), "the top-up with 2");
        assertEquals(10, CupRules.settle(CUP, field(3), 10).topup(), "the top-up with 3");
        assertEquals(0, CupRules.settle(CUP, field(3), -4).topup(), "a negative top-up setting reads as 0");
        assertEquals(15, CupRules.settle(CUP, field(3), -4).pool(), "and the pool is just the entries");
        CupPlan none = CupRules.settle(CUP, field(3), 0);
        assertEquals(List.of(8, 4, 3), tokens(none), "no top-up: 50/30/20 of 15 is 7.5, 4.5 and 3, remainder to 1st");
    }

    @Test
    void nobodyEnteredIsAnEmptyCupThatPaysNothing() {
        CupPlan plan = CupRules.settle(CUP, List.of(), 10);
        assertEquals(CupPlan.Outcome.EMPTY, plan.outcome(), "no entries");
        assertEquals(0, plan.pool(), "no pool");
        assertEquals(0, plan.topup(), "no top-up without entrants");
        assertEquals(List.of(), plan.lines(), "nobody to tell");
        assertNull(plan.reason(), "only a voided Cup has a reason");
        assertSound(List.of(), 10, plan, "an empty Cup");
    }

    @Test
    void thePoolIsWhatEachEntrantReallyPaidWhenTheEntryChangedMidWeek() {
        List<CupEntry> mixed = List.of(timed(1, 5, 40_000), timed(2, 3, 41_000), timed(3, 8, 42_000));
        CupPlan plan = CupRules.settle(CUP, mixed, 10);
        assertEquals(16, plan.entries(), "5 + 3 + 8 were really paid");
        assertEquals(26, plan.pool(), "and the top-up");
        assertEquals(List.of(14, 7, 5), tokens(plan), "13, 7.8 and 5.2 round down to 13, 7 and 5; 1st gets the 1 left");
        assertSound(mixed, 10, plan, "entries of different sizes");
    }

    @Test
    void entrantsWithoutACupTimeGetNothingWhileTwoOrMoreRaced() {
        List<CupEntry> entries = List.of(timed(1, 5, 40_000), untimed(2, 5), timed(3, 5, 39_000));
        CupPlan plan = CupRules.settle(CUP, entries, 10);
        assertEquals(CupPlan.Outcome.PRIZES, plan.outcome(), "two Cup times are a contest");
        assertEquals(25, plan.pool(), "every paid entry is in the pool the screen showed");
        assertEquals(18, tokensOf(plan, 3), "two times share 70/30 of 25: 17.5 down to 17, plus the 1 left");
        assertEquals(7, tokensOf(plan, 1), "30% of 25 is 7.5, down to 7");
        assertEquals(0, tokensOf(plan, 2), "no Cup time, no place");
        CupPayout none = plan.lineFor(p(2));
        assertEquals(0, none.place(), "no Cup time has no place");
        assertEquals(CupPayout.Kind.NONE, none.kind(), "and no payment");
        assertEquals(p(2), plan.lines().get(2).player(), "entrants without a time come after the placed ones");
        assertSound(entries, 10, plan, "an entrant without a time");
    }

    @Test
    void fewerThanTwoCupTimesGiveEveryEntryBackWithoutTheTopUp() {
        List<CupEntry> oneTime = List.of(untimed(1, 5), timed(2, 5, 40_000), untimed(3, 4));
        CupPlan plan = CupRules.settle(CUP, oneTime, 10);
        assertEquals(CupPlan.Outcome.REFUND_NO_CONTEST, plan.outcome(), "one Cup time raced nobody");
        assertEquals(0, plan.topup(), "no contest, no top-up");
        assertEquals(List.of(5, 5, 4), tokens(plan), "everyone gets back exactly what they paid, in entry order");
        for (CupPayout l : plan.lines()) {
            assertEquals(CupPayout.Kind.REFUND, l.kind(), "every line is a refund");
        }
        assertSound(oneTime, 10, plan, "one Cup time");

        List<CupEntry> noTimes = List.of(untimed(1, 5), untimed(2, 5));
        CupPlan plan2 = CupRules.settle(CUP, noTimes, 10);
        assertEquals(CupPlan.Outcome.REFUND_NO_CONTEST, plan2.outcome(), "no Cup times at all");
        assertEquals(List.of(5, 5), tokens(plan2), "both entries come back");
        assertSound(noTimes, 10, plan2, "no Cup times");
    }

    @Test
    void thePlanNeverDependsOnTheOrderTheEntriesAreReadIn() {
        List<CupEntry> entries = new ArrayList<>(field(7));
        entries.add(untimed(8, 5));
        entries.add(untimed(9, 2));
        CupPlan first = CupRules.settle(CUP, entries, 10);
        for (int seed = 0; seed < 20; seed++) {
            List<CupEntry> shuffled = new ArrayList<>(entries);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            assertEquals(first, CupRules.settle(CUP, shuffled, 10), "shuffle " + seed + " gives the same plan");
        }
    }

    @Test
    void aPlayerWithTwoEntriesInOneCupIsRefusedAsABug() {
        List<CupEntry> twice = List.of(timed(1, 5, 40_000), timed(1, 5, 41_000));
        assertThrows(IllegalArgumentException.class, () -> CupRules.settle(CUP, twice, 10),
                "the entry table's key forbids a second entry, so two are a bug to stop on, not to pay");
    }
}
