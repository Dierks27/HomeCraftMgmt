package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.cup.live.CupSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.assertSound;
import static com.dierks.homecraft.games.cup.CupFixtures.timed;
import static com.dierks.homecraft.games.cup.CupFixtures.timedAt;
import static com.dierks.homecraft.games.cup.CupFixtures.tokensOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup's family rule (BALANCE-SPEC §3.2, the 2 Oct token balance): with the server's top-up
 * at twice the entry, nobody who sets a Cup time in a Cup of 2 or 3 gets back less than they paid in.
 * A family of three can enter every Cup and the youngest never pays into a pool the adults win.
 *
 * <p>Pinned here: the shipped numbers keep the rule (a top-up at least twice the entry), and the exact
 * payouts of the spec's example (10 in, 20 on top); and for every entry 1 to 100 with a top-up of twice
 * it, every Cup of 2 or 3 with everyone timed pays each entrant at least their entry, through the
 * rounding down, the remainder going to 1st, and ties of every shape (which share their places' amounts).
 */
class CupFamilyRuleTest {

    @Test
    void theShippedTopUpIsAtLeastTwiceTheEntryAndAShippedCupOfTwoOrThreeLosesNobody() {
        CupSettings shipped = CupSettings.defaults();
        assertEquals(TokenBalance.CUP_ENTRY, shipped.entry(), "the token balance's entry");
        assertTrue(shipped.serverTopup() >= 2 * shipped.entry(), "the top-up is at least twice the entry: the family rule");
        int e = shipped.entry();
        int t = shipped.serverTopup();
        for (List<CupEntry> cup : shapes(e)) {
            CupPlan plan = CupRules.settle(CUP, cup, t);
            assertSound(cup, t, plan, "a shipped Cup of " + cup.size());
            for (int n = 1; n <= cup.size(); n++) {
                assertTrue(tokensOf(plan, n) >= e, "entrant " + n + " of " + cup.size() + " gets back what they paid");
            }
        }
    }

    @Test
    void theSpecsExampleCupsPayToTheToken() {
        // BALANCE-SPEC §3.2's example at 10 in and 20 on top
        List<CupEntry> two = List.of(timed(1, 10, 40_000), timed(2, 10, 41_000));
        CupPlan p2 = CupRules.settle(CUP, two, 20);
        assertSound(two, 20, p2, "two in");
        assertEquals(40, p2.pool(), "10 + 10 + the top-up of 20");
        assertEquals(28, tokensOf(p2, 1), "70% of 40");
        assertEquals(12, tokensOf(p2, 2), "30% of 40: more than the 10 paid in");

        List<CupEntry> three = List.of(timed(1, 10, 40_000), timed(2, 10, 41_000), timed(3, 10, 42_000));
        CupPlan p3 = CupRules.settle(CUP, three, 20);
        assertSound(three, 20, p3, "three in");
        assertEquals(50, p3.pool(), "30 + the top-up of 20");
        assertEquals(25, tokensOf(p3, 1), "50% of 50");
        assertEquals(15, tokensOf(p3, 2), "30% of 50");
        assertEquals(10, tokensOf(p3, 3), "20% of 50: third gets the entry back");
    }

    @Test
    void withATopUpOfTwiceTheEntryNobodyTimedInACupOfTwoOrThreeLoses() {
        for (int entry = 1; entry <= 100; entry++) {
            int topup = 2 * entry;
            for (List<CupEntry> cup : shapes(entry)) {
                CupPlan plan = CupRules.settle(CUP, cup, topup);
                String why = cup.size() + " in at " + entry + " (top-up " + topup + "), times " + times(cup);
                assertSound(cup, topup, plan, why);
                for (int n = 1; n <= cup.size(); n++) {
                    assertTrue(tokensOf(plan, n) >= entry,
                            why + ": entrant " + n + " gets " + tokensOf(plan, n) + ", less than the " + entry + " paid");
                }
            }
        }
    }

    @Test
    void aFourthEntrantCanStillLoseTheirEntryAsBefore() {
        List<CupEntry> four = List.of(timed(1, 10, 40_000), timed(2, 10, 41_000), timed(3, 10, 42_000),
                timed(4, 10, 43_000));
        CupPlan plan = CupRules.settle(CUP, four, 20);
        assertSound(four, 20, plan, "four in");
        assertEquals(0, Math.max(0, tokensOf(plan, 4)), "only the first three places are paid: the rule is for 2 or 3");
    }

    /** Every Cup of 2 or 3 entrants, all timed, paying {@code entry}: distinct times and every tie. */
    private static List<List<CupEntry>> shapes(int entry) {
        List<List<CupEntry>> out = new ArrayList<>();
        out.add(List.of(timed(1, entry, 40_000), timed(2, entry, 41_000)));
        out.add(List.of(timedAt(1, entry, 40_000, 1001), timedAt(2, entry, 40_000, 1002)));
        out.add(List.of(timed(1, entry, 40_000), timed(2, entry, 41_000), timed(3, entry, 42_000)));
        out.add(List.of(timedAt(1, entry, 40_000, 1001), timedAt(2, entry, 40_000, 1002), timed(3, entry, 42_000)));
        out.add(List.of(timed(1, entry, 40_000), timedAt(2, entry, 41_000, 1002), timedAt(3, entry, 41_000, 1003)));
        out.add(List.of(timedAt(1, entry, 40_000, 1001), timedAt(2, entry, 40_000, 1002),
                timedAt(3, entry, 40_000, 1003)));
        return out;
    }

    private static List<Long> times(List<CupEntry> cup) {
        List<Long> out = new ArrayList<>();
        for (CupEntry e : cup) {
            out.add(e.bestMs());
        }
        return out;
    }
}
