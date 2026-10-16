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
 * it, each read through {@code games.cup}'s own clamps (so a setting the plugin can run: the top-up goes to
 * 200), every Cup of 2 or 3 with everyone timed pays each entrant at least their entry, through the
 * rounding down, the remainder going to 1st, and ties of every shape (which share their places' amounts);
 * and everyone in one Cup pays the same ({@link CupRules#fee}), so a Cup opened before the entry changed
 * (the 0.37 upgrade week) keeps the rule. A top-up the owner set below twice the entry is named at load
 * ({@code RewardCeilingsTest}).
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
        // every entry a setting may give, with a top-up of twice it read through games.cup's own clamps (the v4
        // audit, ECON-R3-01: at a top-up ceiling of 100, entries over 50 could never have it)
        for (int e = CupRules.MIN_ENTRY; e <= CupRules.MAX_ENTRY; e++) {
            List<String> warns = new ArrayList<>();
            CupSettings s = CupSettings.parse(new com.dierks.homecraft.config.GamesConfig.Node("games.cup",
                    java.util.Map.of("entry", e, "server_topup", 2 * e), warns::add), CupSettings.defaults());
            assertEquals(List.of(), warns, "entry " + e + " with a top-up of " + 2 * e + " is a setting games.cup takes");
            assertEquals(e, s.entry(), "as written: " + s);
            assertEquals(2 * e, s.serverTopup(), "as written: " + s);
            int entry = s.entry();
            int topup = s.serverTopup();
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
    void everyoneInOneCupPaysWhatItsFirstEntrantPaidSoTheRuleHoldsWhenTheEntryChanges() {
        // the v4 audit, ECON01: the 0.37 update moves the entry 5 -> 10 (and the top-up 10 -> 20) mid-week
        assertEquals(10, CupRules.fee(List.of(), 10), "a Cup nobody is in yet costs the setting");
        assertEquals(5, CupRules.fee(List.of(timed(2, 5, 41_000), timed(1, 5, 40_000)), 10),
                "one with entries costs what its first entrant paid, however they are read");
        assertEquals(10, CupRules.fee(List.of(timed(1, 0, 40_000)), 10), "a paid amount no fee can be is never used");

        List<CupEntry> mixed = List.of(timed(1, 5, 40_000), timed(2, 5, 41_000), timed(3, 10, 42_000));
        assertEquals(8, tokensOf(CupRules.settle(CUP, mixed, 20), 3),
                "why: had the third paid the new 10, third place would get 8 of it back");
        int fee = CupRules.fee(mixed.subList(0, 2), 10);
        for (List<CupEntry> cup : shapes(fee)) {
            CupPlan plan = CupRules.settle(CUP, cup, 20);
            assertSound(cup, 20, plan, "a Cup of " + cup.size() + " opened at " + fee + ", settled with the new top-up");
            for (int n = 1; n <= cup.size(); n++) {
                assertTrue(tokensOf(plan, n) >= fee, "entrant " + n + " gets back at least the " + fee + " they paid");
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
