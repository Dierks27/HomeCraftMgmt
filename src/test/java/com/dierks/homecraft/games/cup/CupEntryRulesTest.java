package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.NEXT_WEEK;
import static com.dierks.homecraft.games.cup.CupFixtures.OTHER_COURSE;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entering the Cup (§D2): once per course per week, refused when already in, when the Cup is off or
 * not on the course, when this week's Cup was voided or settled, and when the player can't pay; and
 * how a counted run becomes the Cup time (only a strictly faster one, only after entering).
 */
class CupEntryRulesTest {

    @Test
    void anOpenCupWithTokensToSpareSaysGoAhead() {
        assertNull(CupRules.refusal(true, true, null, false, 5, 5), "exactly the entry in tokens is enough");
        assertNull(CupRules.refusal(true, true, null, false, 5, 500), "plenty of tokens");
    }

    @Test
    void enteringTwiceInAWeekIsRefused() {
        assertEquals(CupRefusal.ALREADY_IN, CupRules.refusal(true, true, null, true, 5, 100),
                "an entry row for this course and week already exists");
        CupBook book = new CupBook();
        assertNull(book.enter(CUP, p(1), 5, 100, 10, true, true), "the first entry goes in");
        assertEquals(CupRefusal.ALREADY_IN, book.enter(CUP, p(1), 5, 95, 20, true, true), "the second is refused");
        assertEquals(1, book.entries(CUP).size(), "there is still one entry");
        assertEquals(10, book.entries(CUP).get(0).enteredAt(), "and it is the first one, untouched");
        assertEquals(5, book.live(CUP, 10).tokens(), "only one entry is in the pool (no top-up alone)");
    }

    @Test
    void theOncePerWeekRuleIsPerCourseAndPerWeek() {
        CupBook book = new CupBook();
        assertNull(book.enter(CUP, p(1), 5, 100, 10, true, true), "this course, this week");
        assertNull(book.enter(OTHER_COURSE, p(1), 5, 95, 11, true, true), "another course the same week is its own Cup");
        assertNull(book.enter(NEXT_WEEK, p(1), 5, 90, 12, true, true), "the same course next week is a new Cup");
        assertNull(book.enter(CUP, p(2), 5, 100, 13, true, true), "another player in the same Cup");
        assertEquals(2, book.entries(CUP).size(), "two players in this week's Cup");
        assertTrue(book.in(CUP, p(1)) && book.in(OTHER_COURSE, p(1)) && book.in(NEXT_WEEK, p(1)),
                "player 1 is in three different Cups");
    }

    @Test
    void theChecksComeInOrderAndEachHasItsOwnReason() {
        assertEquals(CupRefusal.OFF, CupRules.refusal(false, true, null, true, 5, 0),
                "the Cup switched off server-wide comes first");
        assertEquals(CupRefusal.OFF, CupRules.refusal(true, true, null, false, 0, 100),
                "an entry below 1 token would be a free pool: the Cup stays closed");
        assertEquals(CupRefusal.OFF, CupRules.refusal(true, true, null, false, CupRules.MAX_ENTRY + 1, 1000),
                "an entry above the most a setting may give: closed");
        assertEquals(CupRefusal.NOT_ON_THIS_COURSE, CupRules.refusal(true, false, null, true, 5, 0),
                "a course that doesn't run a Cup");
        assertEquals(CupRefusal.CALLED_OFF, CupRules.refusal(true, true, CupPlan.Outcome.VOIDED, true, 5, 0),
                "a voided week says so, even to someone who was in");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, CupPlan.Outcome.PRIZES, false, 5, 100),
                "a settled week takes no more entries");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, CupPlan.Outcome.EMPTY, false, 5, 100),
                "an empty week that was closed takes no more entries");
        assertEquals(CupRefusal.ALREADY_IN, CupRules.refusal(true, true, null, true, 5, 0),
                "already in comes before the balance: nothing to pay");
        assertEquals(CupRefusal.NOT_ENOUGH_TOKENS, CupRules.refusal(true, true, null, false, 5, 4),
                "one token short");
    }

    @Test
    void aSettledOrVoidedCupTakesNoEntries() {
        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, true, true);
        book.settle(CUP, 10);
        assertEquals(CupRefusal.WEEK_OVER, book.enter(CUP, p(2), 5, 100, 20, true, true),
                "an entry racing the rollover lands after the settlement and is refused");
        assertFalse(book.in(CUP, p(2)), "so it was never written");

        CupBook voided = new CupBook();
        voided.enter(CUP, p(1), 5, 100, 10, true, true);
        voided.voidCup(CUP, CupPlan.VoidReason.CHANGED);
        assertEquals(CupRefusal.CALLED_OFF, voided.enter(CUP, p(2), 5, 100, 20, true, true),
                "a voided week stays closed until next week");
        assertNull(voided.enter(NEXT_WEEK, p(2), 5, 100, 30, true, true), "next week's Cup is open");
    }

    @Test
    void theRefusalLinesAreKindAndPlain() {
        assertEquals("You're already in this week's Cup on this course.", CupRefusal.ALREADY_IN.message(5),
                "the already-in line");
        assertEquals("You need 5 tokens to enter the Cup.", CupRefusal.NOT_ENOUGH_TOKENS.message(5),
                "the balance line names the entry");
        assertEquals("You need 1 token to enter the Cup.", CupRefusal.NOT_ENOUGH_TOKENS.message(1), "singular");
        for (CupRefusal r : CupRefusal.values()) {
            String m = r.message(5);
            assertFalse(m.isBlank(), r + " says something");
            assertFalse(m.contains("&") || m.contains("§"), r + " has no colour codes: the caller colours it");
            assertFalse(m.toLowerCase().contains("bet") || m.toLowerCase().contains("wager"),
                    r + " never calls the Cup a bet");
        }
    }

    @Test
    void onlyAStrictlyFasterCountedRunAfterEnteringBecomesTheCupTime() {
        CupEntry e = CupEntry.entered(p(1), 5, 1_000);
        assertFalse(e.hasTime(), "a new entry has no Cup time");
        assertEquals(CupEntry.NO_TIME, e.bestMs(), "no time reads as NO_TIME");
        assertSame(e, e.withRun(40_000, 999), "a run that finished before entering doesn't count");
        CupEntry first = e.withRun(40_000, 1_000);
        assertEquals(40_000, first.bestMs(), "a run finished as the entry was made counts");
        assertEquals(1_000, first.bestAt(), "and remembers when it was set");
        assertSame(first, first.withRun(40_000, 2_000), "an equal time keeps the earlier one (the tie-break)");
        assertSame(first, first.withRun(41_000, 3_000), "a slower time changes nothing");
        CupEntry better = first.withRun(39_999, 4_000);
        assertEquals(39_999, better.bestMs(), "a faster time replaces it");
        assertEquals(4_000, better.bestAt(), "with its own moment");
        assertSame(better, better.withRun(0, 5_000), "a zero time is not a run");
        assertSame(better, better.withRun(-5, 5_000), "a negative time is not a run");
        assertEquals(5, better.paid(), "a run never changes what was paid");
    }

    @Test
    void theBookTakesRunsOnlyFromEntrantsOfAnOpenCup() {
        CupBook book = new CupBook();
        assertFalse(book.run(CUP, p(1), 40_000, 50), "someone who isn't in has no Cup time");
        book.enter(CUP, p(1), 5, 100, 10, true, true);
        assertTrue(book.run(CUP, p(1), 40_000, 50), "an entrant's first counted run");
        assertFalse(book.run(CUP, p(1), 40_500, 60), "a slower run");
        assertTrue(book.run(CUP, p(1), 39_000, 70), "a faster one");
        assertFalse(book.run(OTHER_COURSE, p(1), 30_000, 80), "a run on another course is another Cup's");
        book.settle(CUP, 10);
        assertFalse(book.run(CUP, p(1), 20_000, 90), "after the settlement nothing changes");
        assertEquals(39_000, book.entries(CUP).get(0).bestMs(), "the Cup time stays the settled one");
    }

    @Test
    void anEntryIsAPlayerAndZeroOrMoreTokens() {
        assertThrows(IllegalArgumentException.class, () -> CupEntry.entered(p(1), -1, 0), "a negative entry");
        assertThrows(NullPointerException.class, () -> CupEntry.entered(null, 5, 0), "an entry needs a player");
        assertThrows(IllegalArgumentException.class, () -> new CupKey(" ", 1), "a Cup needs a course");
        assertEquals("cup:sky_rings:20724", CUP.ref(), "the Cup's ref");
        CupEntry nulled = new CupEntry(p(1), 5, 0, 0, 1234);
        assertEquals(0, nulled.bestAt(), "no time means no moment either");
        assertEquals(List.of(), new CupBook().entries(CUP), "an unknown Cup has no entries");
    }
}
