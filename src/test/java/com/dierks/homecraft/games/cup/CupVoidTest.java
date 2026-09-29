package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.assertSound;
import static com.dierks.homecraft.games.cup.CupFixtures.field;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.race;
import static com.dierks.homecraft.games.cup.CupFixtures.timed;
import static com.dierks.homecraft.games.cup.CupFixtures.tokens;
import static com.dierks.homecraft.games.cup.CupFixtures.untimed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A voided Cup (§D2: the course was deleted, changed layout or closed mid-week): every entry back in
 * full whatever the times, no top-up, the reason told to each player, and the week closed for good.
 */
class CupVoidTest {

    @Test
    void aVoidedCupGivesEveryEntryBackInFullWhateverTheTimes() {
        List<CupEntry> e = List.of(timed(1, 5, 40_000), untimed(2, 3), timed(3, 8, 39_000));
        CupPlan plan = CupRules.voided(CUP, e, CupPlan.VoidReason.DELETED);
        assertEquals(CupPlan.Outcome.VOIDED, plan.outcome(), "voided");
        assertEquals(CupPlan.VoidReason.DELETED, plan.reason(), "and why");
        assertEquals(List.of(5, 3, 8), tokens(plan), "each gets back exactly what they paid, in entry order");
        assertEquals(0, plan.topup(), "a voided Cup has no top-up, even with a contest in it");
        assertEquals(16, plan.pool(), "the pool is just the entries");
        for (CupPayout l : plan.lines()) {
            assertEquals(CupPayout.Kind.REFUND, l.kind(), "every line is a refund");
            assertEquals(0, l.place(), "nobody is placed in a voided Cup");
        }
        assertSound(e, 10, plan, "a voided Cup");
    }

    @Test
    void everyReasonVoidsTheSameWay() {
        for (CupPlan.VoidReason r : CupPlan.VoidReason.values()) {
            CupPlan plan = CupRules.voided(CUP, field(10), r);
            assertEquals(List.of(5, 5, 5, 5, 5, 5, 5, 5, 5, 5), tokens(plan), r + ": ten entries back");
            assertEquals(r, plan.reason(), r + " is kept for the message");
            assertSound(field(10), 10, plan, "voided because " + r);
        }
        assertThrows(IllegalArgumentException.class, () -> CupRules.voided(CUP, field(2), null),
                "the player is told why, so a void needs a reason");
    }

    @Test
    void aCupNobodyEnteredIsNotVoidedAndStaysOpen() {
        CupPlan plan = CupRules.voided(CUP, List.of(), CupPlan.VoidReason.CLOSED);
        assertEquals(List.of(), plan.lines(), "nobody to refund");
        assertEquals(0, plan.pool(), "nothing moves");
        assertSound(List.of(), 10, plan, "an empty voided plan is still sound");

        CupBook book = new CupBook();
        assertNull(book.voidCup(CUP, CupPlan.VoidReason.CHANGED), "a course edited before anyone entered voids nothing");
        assertNull(book.settlement(CUP), "so the week isn't closed");
        assertNull(book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true), "and entries on the new layout go in");
    }

    @Test
    void aVoidedCupIsNeverSettledOrVoidedAgain() {
        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        book.enter(CUP, p(2), 5, 100, 11, CUP.week(), true, true);
        race(book, CUP, p(1), 40_000, 100_020);
        race(book, CUP, p(2), 41_000, 100_021);
        CupPlan plan = book.voidCup(CUP, CupPlan.VoidReason.CHANGED);
        assertEquals(List.of(5, 5), tokens(plan), "both entries back");
        assertNull(book.voidCup(CUP, CupPlan.VoidReason.DELETED), "voiding twice refunds nothing twice");
        assertNull(book.settle(CUP, 10), "the rollover finds it settled and pays nothing");
        assertEquals(List.of(), book.due(CUP.week() + 7), "it is never due");
        assertEquals(plan, book.settlement(CUP), "the void is the week's settlement");
    }

    @Test
    void aSettledCupCanNoLongerBeVoided() {
        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        CupPlan settled = book.settle(CUP, 10);
        assertNull(book.voidCup(CUP, CupPlan.VoidReason.DELETED), "a course deleted after the rollover can't refund a paid week");
        assertEquals(settled, book.settlement(CUP), "the settlement stands");
    }

    @Test
    void thePlayerIsToldWhyTheirEntryCameBack() {
        CupPlan plan = CupRules.voided(CUP, List.of(untimed(1, 5)), CupPlan.VoidReason.DELETED);
        assertEquals("The Weekly Cup on Sky Rings was called off because the course was removed. Your 5 tokens came back.",
                CupText.result(plan, plan.lines().get(0), "Sky Rings"), "deleted");
        CupPlan changed = CupRules.voided(CUP, List.of(untimed(1, 5)), CupPlan.VoidReason.CHANGED);
        assertEquals("The Weekly Cup on Sky Rings was called off because the course changed. Your 5 tokens came back.",
                CupText.result(changed, changed.lines().get(0), "Sky Rings"), "changed");
        CupPlan closed = CupRules.voided(CUP, List.of(untimed(1, 1)), CupPlan.VoidReason.CLOSED);
        assertEquals("The Weekly Cup on Sky Rings was called off because the course closed. Your 1 token came back.",
                CupText.result(closed, closed.lines().get(0), "Sky Rings"), "closed, singular");
    }
}
