package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.assertSound;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.timed;
import static com.dierks.homecraft.games.cup.CupFixtures.timedAt;
import static com.dierks.homecraft.games.cup.CupFixtures.tokens;
import static com.dierks.homecraft.games.cup.CupFixtures.tokensOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ties at every place (§D2: "ties share their places' amounts equally, with the remainder to the
 * earliest time"): the tied times pool the amounts of every place they cover, split them evenly, and
 * whoever set the time first gets what doesn't divide. Places read "1224", and a tie below the paid
 * places shares nothing.
 */
class CupTiesTest {

    @Test
    void twoEntrantsTiedShareTheWholePoolEvenly() {
        List<CupEntry> tied = List.of(timed(1, 5, 40_000), timed(2, 5, 40_000));
        CupPlan plan = CupRules.settle(CUP, tied, 10);
        assertEquals(List.of(10, 10), tokens(plan), "70% + 30% of 20, shared by two");
        assertEquals(1, plan.lines().get(0).place(), "both are 1st");
        assertEquals(1, plan.lines().get(1).place(), "both are 1st");
        assertEquals(2, plan.lines().get(1).tied(), "the place is shared by two");
        assertSound(tied, 10, plan, "a two-way tie of two");

        List<CupEntry> odd = List.of(timedAt(1, 5, 40_000, 3_000), timedAt(2, 6, 40_000, 2_000));
        CupPlan plan2 = CupRules.settle(CUP, odd, 10);
        assertEquals(11, tokensOf(plan2, 2), "21 shared by two is 10 each; player 2 set the time first and gets the 1 left");
        assertEquals(10, tokensOf(plan2, 1), "player 1 set the same time later");
        assertSound(odd, 10, plan2, "a two-way tie of two, odd pool");
    }

    @Test
    void aTieForFirstSharesFirstAndSecondsAmounts() {
        List<CupEntry> e = List.of(timed(1, 5, 40_000), timed(2, 5, 40_000), timed(3, 5, 41_000), timed(4, 5, 42_000));
        CupPlan plan = CupRules.settle(CUP, e, 15);
        assertEquals(35, plan.pool(), "20 in entries and a top-up of 15");
        assertEquals(List.of(14, 14, 7, 0), tokens(plan), "18 + 10 shared by two; 3rd keeps its 7");
        assertEquals(List.of(1, 1, 3, 4), places(plan), "places read 1, 1, 3, 4");
        assertSound(e, 15, plan, "a tie for 1st");

        List<CupEntry> odd = List.of(timedAt(1, 5, 40_000, 5_000), timedAt(2, 5, 40_000, 4_000), timed(3, 6, 41_000));
        CupPlan plan2 = CupRules.settle(CUP, odd, 10);
        assertEquals(26, plan2.pool(), "16 in entries and the top-up");
        assertEquals(11, tokensOf(plan2, 2), "14 + 7 = 21 shared by two: player 2 set it first and gets the 1 left");
        assertEquals(10, tokensOf(plan2, 1), "player 1 gets the even share");
        assertEquals(5, tokensOf(plan2, 3), "3rd keeps its 5");
        assertSound(odd, 10, plan2, "a tie for 1st, odd");
    }

    @Test
    void aThreeWayTieForFirstSharesTheWholePool() {
        List<CupEntry> e = List.of(timedAt(1, 5, 40_000, 9_000), timedAt(2, 5, 40_000, 7_000),
                timedAt(3, 5, 40_000, 8_000), timed(4, 5, 45_000), timed(5, 5, 46_000));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals(35, plan.pool(), "25 in entries and the top-up");
        assertEquals(13, tokensOf(plan, 2), "35 shared by three is 11 each; the earliest time gets the 2 left");
        assertEquals(11, tokensOf(plan, 3), "an even share");
        assertEquals(11, tokensOf(plan, 1), "an even share");
        assertEquals(0, tokensOf(plan, 4), "4th is below the paid places");
        assertEquals(List.of(1, 1, 1, 4, 5), places(plan), "places read 1, 1, 1, 4, 5");
        assertEquals(p(2), plan.lines().get(0).player(), "the earliest of the tie is listed first");
        assertSound(e, 10, plan, "a three-way tie for 1st");
    }

    @Test
    void aTieForSecondSharesSecondAndThirdsAmounts() {
        List<CupEntry> e = List.of(timed(1, 5, 39_000), timedAt(2, 5, 40_000, 6_000), timedAt(3, 5, 40_000, 5_000),
                timed(4, 5, 41_000));
        CupPlan plan = CupRules.settle(CUP, e, 15);
        assertEquals(35, plan.pool(), "20 and 15");
        assertEquals(18, tokensOf(plan, 1), "1st keeps its 18");
        assertEquals(9, tokensOf(plan, 3), "10 + 7 = 17 shared by two: player 3 set it first and gets the 1 left");
        assertEquals(8, tokensOf(plan, 2), "the even share");
        assertEquals(0, tokensOf(plan, 4), "4th is paid nothing");
        assertEquals(List.of(1, 2, 2, 4), places(plan), "places read 1, 2, 2, 4");
        assertSound(e, 15, plan, "a tie for 2nd");
    }

    @Test
    void aTieForSecondThatRunsPastThirdSharesOnlyThePaidPlaces() {
        List<CupEntry> e = new ArrayList<>(List.of(timed(1, 5, 39_000), timed(2, 5, 40_000), timed(3, 5, 40_000),
                timed(4, 5, 40_000)));
        for (int i = 5; i <= 10; i++) {
            e.add(timed(i, 5, 50_000 + i));
        }
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals(60, plan.pool(), "ten entries and the top-up");
        assertEquals(30, tokensOf(plan, 1), "1st keeps its 30");
        for (int n = 2; n <= 4; n++) {
            assertEquals(10, tokensOf(plan, n), "18 + 12 + nothing for 4th, shared by three: 10 each");
        }
        assertSound(e, 10, plan, "a three-way tie for 2nd");
    }

    @Test
    void aTieForThirdSharesThirdsAmountWithThePlaceBelowIt() {
        List<CupEntry> e = List.of(timed(1, 5, 38_000), timed(2, 5, 39_000), timedAt(3, 5, 40_000, 9_000),
                timedAt(4, 5, 40_000, 8_000));
        CupPlan plan = CupRules.settle(CUP, e, 15);
        assertEquals(List.of(18, 10, 4, 3), tokens(plan), "3rd's 7 and 4th's nothing shared by two, the 1 left to the earlier");
        assertEquals(p(4), plan.lines().get(2).player(), "player 4 set the time first");
        assertEquals(List.of(1, 2, 3, 3), places(plan), "places read 1, 2, 3, 3");
        assertEquals(2, plan.lines().get(3).tied(), "the place is shared by two");
        assertSound(e, 15, plan, "a tie for 3rd");

        List<CupEntry> even = List.of(timed(1, 5, 38_000), timed(2, 5, 39_000), timed(3, 5, 40_000), timed(4, 5, 40_000));
        assertEquals(List.of(15, 9, 3, 3), tokens(CupRules.settle(CUP, even, 10)), "3rd's 6 shared by two");
    }

    @Test
    void aTieBelowThePaidPlacesSharesNothing() {
        List<CupEntry> e = new ArrayList<>(List.of(timed(1, 5, 38_000), timed(2, 5, 39_000), timed(3, 5, 40_000)));
        e.add(timed(4, 5, 41_000));
        e.add(timed(5, 5, 41_000));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals(List.of(18, 10, 7, 0, 0), tokens(plan), "4th and 5th tied below the paid places");
        CupPayout fourth = plan.lineFor(p(5));
        assertEquals(4, fourth.place(), "a tie for 4th is 4th");
        assertEquals(2, fourth.tied(), "shared by two");
        assertEquals(CupPayout.Kind.NONE, fourth.kind(), "and pays nothing");
        assertSound(e, 10, plan, "a tie for 4th");
    }

    @Test
    void tenEntrantsAllTiedShareThePoolEvenly() {
        List<CupEntry> e = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            e.add(timedAt(i, 5, 40_000, 2_000 - i));
        }
        CupPlan plan = CupRules.settle(CUP, e, 13);
        assertEquals(63, plan.pool(), "50 and a top-up of 13");
        assertEquals(9, tokensOf(plan, 10), "63 by ten is 6 each; player 10 set it first and gets the 3 left");
        for (int n = 1; n <= 9; n++) {
            assertEquals(6, tokensOf(plan, n), "an even share for player " + n);
            assertEquals(1, plan.lineFor(p(n)).place(), "everyone is 1st");
            assertEquals(10, plan.lineFor(p(n)).tied(), "shared by ten");
        }
        assertSound(e, 13, plan, "a ten-way tie");
    }

    @Test
    void theEarliestTimeIsWhenItWasSetNotWhenThePlayerEntered() {
        List<CupEntry> e = List.of(
                new CupEntry(p(1), 5, 100, 40_000, 9_000),
                new CupEntry(p(2), 5, 900, 40_000, 1_000),
                timed(3, 5, 41_000));
        CupPlan plan = CupRules.settle(CUP, e, 11);
        assertEquals(26, plan.pool(), "15 and 11");
        assertEquals(11, tokensOf(plan, 2), "player 2 entered later but set the time first: the 1 left is theirs");
        assertEquals(10, tokensOf(plan, 1), "player 1 entered first but set the time later");
        assertSound(e, 11, plan, "the earliest time");
    }

    @Test
    void timesSetAtTheSameMomentFallBackToEntryOrderThenThePlayerId() {
        List<CupEntry> byEntry = List.of(new CupEntry(p(1), 5, 500, 40_000, 1_000),
                new CupEntry(p(2), 6, 400, 40_000, 1_000));
        assertEquals(11, tokensOf(CupRules.settle(CUP, byEntry, 10), 2), "same moment: whoever entered first");
        List<CupEntry> byId = List.of(new CupEntry(p(2), 5, 400, 40_000, 1_000),
                new CupEntry(p(1), 6, 400, 40_000, 1_000));
        assertEquals(11, tokensOf(CupRules.settle(CUP, byId, 10), 1), "same moment and entry: the lower player id, so it never depends on row order");
    }

    @Test
    void aTieInsideAPairOfTiesKeepsEveryGroupApart() {
        List<CupEntry> e = List.of(timedAt(1, 5, 40_000, 2), timedAt(2, 5, 40_000, 1),
                timedAt(3, 5, 41_000, 2), timedAt(4, 5, 41_000, 1));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals(30, plan.pool(), "20 and 10");
        assertEquals(List.of(12, 12, 3, 3), tokens(plan), "15 + 9 by two for 1st; 6 by two for 3rd");
        assertEquals(List.of(1, 1, 3, 3), places(plan), "places read 1, 1, 3, 3");
        assertSound(e, 10, plan, "two pairs of ties");
    }

    private static List<Integer> places(CupPlan plan) {
        List<Integer> out = new ArrayList<>();
        for (CupPayout l : plan.lines()) {
            out.add(l.place());
        }
        return out;
    }
}
