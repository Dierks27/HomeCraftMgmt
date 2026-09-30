package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.Edition;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.NEXT_WEEK;
import static com.dierks.homecraft.games.cup.CupFixtures.OTHER_COURSE;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.race;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entering the Cup (§D2): once per course per week, refused when already in, when the Cup is off or
 * not on the course, when the key isn't the current week's, when this week's Cup was voided or
 * settled, and when the player can't pay; and how a counted run becomes the Cup time (only a strictly
 * faster one, only one that started after entering).
 */
class CupEntryRulesTest {

    @Test
    void anOpenCupWithTokensToSpareSaysGoAhead() {
        assertNull(CupRules.refusal(true, true, CUP, CUP.week(), null, false, 5, 5), "exactly the entry in tokens is enough");
        assertNull(CupRules.refusal(true, true, CUP, CUP.week(), null, false, 5, 500), "plenty of tokens");
    }

    @Test
    void enteringTwiceInAWeekIsRefused() {
        assertEquals(CupRefusal.ALREADY_IN, CupRules.refusal(true, true, CUP, CUP.week(), null, true, 5, 100),
                "an entry row for this course and week already exists");
        CupBook book = new CupBook();
        assertNull(book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true), "the first entry goes in");
        assertEquals(CupRefusal.ALREADY_IN, book.enter(CUP, p(1), 5, 95, 20, CUP.week(), true, true), "the second is refused");
        assertEquals(1, book.entries(CUP).size(), "there is still one entry");
        assertEquals(10, book.entries(CUP).get(0).enteredAt(), "and it is the first one, untouched");
        assertEquals(5, book.live(CUP, 10).tokens(), "only one entry is in the pool (no top-up alone)");
    }

    @Test
    void theOncePerWeekRuleIsPerCourseAndPerWeek() {
        CupBook book = new CupBook();
        assertNull(book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true), "this course, this week");
        assertNull(book.enter(OTHER_COURSE, p(1), 5, 95, 11, OTHER_COURSE.week(), true, true), "another course the same week is its own Cup");
        assertNull(book.enter(NEXT_WEEK, p(1), 5, 90, 12, NEXT_WEEK.week(), true, true), "the same course next week is a new Cup");
        assertNull(book.enter(CUP, p(2), 5, 100, 13, CUP.week(), true, true), "another player in the same Cup");
        assertEquals(2, book.entries(CUP).size(), "two players in this week's Cup");
        assertTrue(book.in(CUP, p(1)) && book.in(OTHER_COURSE, p(1)) && book.in(NEXT_WEEK, p(1)),
                "player 1 is in three different Cups");
    }

    @Test
    void theChecksComeInOrderAndEachHasItsOwnReason() {
        assertEquals(CupRefusal.OFF, CupRules.refusal(false, true, CUP, CUP.week(), null, true, 5, 0),
                "the Cup switched off server-wide comes first");
        assertEquals(CupRefusal.OFF, CupRules.refusal(true, true, CUP, CUP.week(), null, false, 0, 100),
                "an entry below 1 token would be a free pool: the Cup stays closed");
        assertEquals(CupRefusal.OFF, CupRules.refusal(true, true, CUP, CUP.week(), null, false, CupRules.MAX_ENTRY + 1, 1000),
                "an entry above the most a setting may give: closed");
        assertEquals(CupRefusal.NOT_ON_THIS_COURSE, CupRules.refusal(true, false, CUP, CUP.week(), null, true, 5, 0),
                "a course that doesn't run a Cup");
        assertEquals(CupRefusal.CALLED_OFF, CupRules.refusal(true, true, CUP, CUP.week(), CupPlan.Outcome.VOIDED, true, 5, 0),
                "a voided week says so, even to someone who was in");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, CUP, CUP.week(), CupPlan.Outcome.PRIZES, false, 5, 100),
                "a settled week takes no more entries");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, CUP, CUP.week(), CupPlan.Outcome.EMPTY, false, 5, 100),
                "an empty week that was closed takes no more entries");
        assertEquals(CupRefusal.ALREADY_IN, CupRules.refusal(true, true, CUP, CUP.week(), null, true, 5, 0),
                "already in comes before the balance: nothing to pay");
        assertEquals(CupRefusal.NOT_ENOUGH_TOKENS, CupRules.refusal(true, true, CUP, CUP.week(), null, false, 5, 4),
                "one token short");
    }

    @Test
    void anEntryIntoLastWeeksCupBetweenTheRolloverAndTheSettlementIsRefused() {
        Edition mondays = new Edition(ZoneId.of("America/Chicago"), LocalTime.of(4, 0), DayOfWeek.MONDAY);
        long rollover = CupRules.settlesAt(mondays, CUP.week());
        long now = rollover + 30_000;
        long current = CupRules.week(mondays, now);
        assertEquals(NEXT_WEEK.week(), current, "04:00:30 on Monday 5 October is already the new Cup week");

        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        book.enter(CUP, p(2), 5, 100, 11, CUP.week(), true, true);
        race(book, CUP, p(1), 40_000, 100_020);
        race(book, CUP, p(2), 41_000, 100_021);
        assertEquals(CupRefusal.WEEK_OVER, book.enter(CUP, p(3), 5, 100, now, current, true, true),
                "a screen built at 03:59:50 still holds last week's key; the click at 04:00:30 is refused although "
                        + "the settlement tick hasn't run yet");
        assertFalse(book.in(CUP, p(3)), "so nothing was written");
        CupPlan plan = book.settle(CUP, 10);
        assertNull(plan.lineFor(p(3)), "the settlement has no line for them: no stake taken for a week they couldn't race");
        assertEquals(10, plan.entries(), "only the two real entries are in the pool");
        assertNull(book.enter(NEXT_WEEK, p(3), 5, 100, now, current, true, true), "this week's Cup takes their entry");

        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, CUP, current, null, false, 5, 100),
                "last week's key with no settlement row yet");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, CUP, current, CupPlan.Outcome.VOIDED, false, 5, 100),
                "a past week reads as over, even one that was called off");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, NEXT_WEEK, CUP.week(), null, false, 5, 100),
                "nor can a week that hasn't started be entered");
        assertEquals(CupRefusal.WEEK_OVER, CupRules.refusal(true, true, null, CUP.week(), null, false, 5, 100),
                "no key at all is never this week's Cup");
        assertEquals(CupRefusal.NOT_ON_THIS_COURSE, CupRules.refusal(true, false, CUP, current, null, false, 5, 100),
                "a course without a Cup says so first");
    }

    @Test
    void aSettledOrVoidedCupTakesNoEntries() {
        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        book.settle(CUP, 10);
        assertEquals(CupRefusal.WEEK_OVER, book.enter(CUP, p(2), 5, 100, 20, CUP.week(), true, true),
                "an entry racing the rollover lands after the settlement and is refused");
        assertFalse(book.in(CUP, p(2)), "so it was never written");

        CupBook voided = new CupBook();
        voided.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        voided.voidCup(CUP, CupPlan.VoidReason.CHANGED);
        assertEquals(CupRefusal.CALLED_OFF, voided.enter(CUP, p(2), 5, 100, 20, CUP.week(), true, true),
                "a voided week stays closed until next week");
        assertNull(voided.enter(NEXT_WEEK, p(2), 5, 100, 30, NEXT_WEEK.week(), true, true), "next week's Cup is open");
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
        CupEntry e = CupEntry.entered(p(1), 5, 100_000);
        assertFalse(e.hasTime(), "a new entry has no Cup time");
        assertEquals(CupEntry.NO_TIME, e.bestMs(), "no time reads as NO_TIME");
        assertSame(e, e.withRun(40_000, 99_999), "a run that finished before entering doesn't count");
        CupEntry first = e.withRun(40_000, 140_000);
        assertEquals(40_000, first.bestMs(), "a run that started the moment the entry was made counts");
        assertEquals(140_000, first.bestAt(), "and remembers when it was set");
        assertSame(first, first.withRun(40_000, 150_000), "an equal time keeps the earlier one (the tie-break)");
        assertSame(first, first.withRun(41_000, 160_000), "a slower time changes nothing");
        CupEntry better = first.withRun(39_999, 170_000);
        assertEquals(39_999, better.bestMs(), "a faster time replaces it");
        assertEquals(170_000, better.bestAt(), "with its own moment");
        assertSame(better, better.withRun(0, 180_000), "a zero time is not a run");
        assertSame(better, better.withRun(-5, 180_000), "a negative time is not a run");
        assertEquals(5, better.paid(), "a run never changes what was paid");
    }

    @Test
    void aRunThatStartedBeforeEnteringDoesntCountEvenWhenItFinishesAfter() {
        CupEntry e = CupEntry.entered(p(4), 5, 100_000);
        assertSame(e, e.withRun(60_000, 130_000),
                "started at 70 s, 30 s before the entry at 100 s: a player who pays mid-run once their splits are on "
                        + "record pace doesn't get that run as their Cup time");
        assertSame(e, e.withRun(30_001, 130_000), "a millisecond before the entry is still before it");
        CupEntry fromEntry = e.withRun(30_000, 130_000);
        assertEquals(30_000, fromEntry.bestMs(),
                "a timed run that started as they entered counts (entering in the warm-up or the 3-2-1 is fine)");

        CupBook book = new CupBook();
        book.enter(CUP, p(4), 5, 100, 100_000, CUP.week(), true, true);
        assertFalse(race(book, CUP, p(4), 60_000, 130_000), "the book refuses the run that straddles the entry too");
        assertFalse(book.entries(CUP).get(0).hasTime(), "so the entrant still has no Cup time");
        assertTrue(race(book, CUP, p(4), 61_000, 200_000), "their next run, started after entering, counts");
        assertEquals(61_000, book.entries(CUP).get(0).bestMs(), "even though the one before it was faster");
    }

    @Test
    void theBookTakesRunsOnlyFromEntrantsOfAnOpenCup() {
        CupBook book = new CupBook();
        assertFalse(race(book, CUP, p(1), 40_000, 100_050), "someone who isn't in has no Cup time");
        book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        assertTrue(race(book, CUP, p(1), 40_000, 100_050), "an entrant's first counted run");
        assertFalse(race(book, CUP, p(1), 40_500, 100_060), "a slower run");
        assertTrue(race(book, CUP, p(1), 39_000, 100_070), "a faster one");
        assertFalse(race(book, OTHER_COURSE, p(1), 30_000, 100_080), "a run on another course is another Cup's");
        assertFalse(race(book, NEXT_WEEK, p(1), 30_000, 100_080), "a run for another week is another Cup's");
        assertFalse(book.run(CUP.course(), p(1), 30_000, 100_080, CupRules.Weeks.NONE),
                "a run that counts for no week (across the rollover, or on last week's layout) changes nothing");
        book.settle(CUP, 10);
        assertFalse(race(book, CUP, p(1), 20_000, 100_090), "after the settlement nothing changes");
        assertEquals(39_000, book.entries(CUP).get(0).bestMs(), "the Cup time stays the settled one");
    }

    @Test
    void aRunCountsInEveryOpenCupOfItsWeeksThatThePlayerIsIn() {
        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, CUP.week(), true, true);
        book.enter(NEXT_WEEK, p(1), 5, 100, 20, NEXT_WEEK.week(), true, true);
        book.enter(OTHER_COURSE, p(1), 5, 100, 30, OTHER_COURSE.week(), true, true);
        CupRules.Weeks both = new CupRules.Weeks(CUP.week(), NEXT_WEEK.week());
        assertTrue(book.run(CUP.course(), p(1), 40_000, 100_000, both), "a run whose range holds two Cup weeks");
        assertEquals(40_000, book.entries(CUP).get(0).bestMs(), "sets the time in the first");
        assertEquals(40_000, book.entries(NEXT_WEEK).get(0).bestMs(), "and in the second");
        assertFalse(book.entries(OTHER_COURSE).get(0).hasTime(), "but never on another course");
        book.settle(CUP, 10);
        assertTrue(book.run(CUP.course(), p(1), 39_000, 110_000, both), "once one is settled");
        assertEquals(40_000, book.entries(CUP).get(0).bestMs(), "the settled one keeps its time");
        assertEquals(39_000, book.entries(NEXT_WEEK).get(0).bestMs(), "and the open one still takes runs");
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
