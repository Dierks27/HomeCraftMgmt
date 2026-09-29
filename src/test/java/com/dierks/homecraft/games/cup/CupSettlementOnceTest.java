package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.NEXT_WEEK;
import static com.dierks.homecraft.games.cup.CupFixtures.OTHER_COURSE;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.tokens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Once-only settlement across a crash (§D2: "the settlement pays out and marks the cup settled exactly
 * once, and is crash-safe (settled on the next boot if it was missed)"): the Cups due are every
 * unsettled one of a past week, oldest first; settling one takes it off the list; a second settlement
 * pays nothing; and a missed rollover is caught at the next boot however many weeks later.
 */
class CupSettlementOnceTest {

    @Test
    void aRolloverTheServerMissedIsSettledAtTheNextBootExactlyOnce() {
        CupBook book = new CupBook();
        book.enter(CUP, p(1), 5, 100, 10, true, true);
        book.enter(CUP, p(2), 5, 100, 11, true, true);
        book.run(CUP, p(1), 40_000, 20);
        book.run(CUP, p(2), 41_000, 21);
        assertEquals(List.of(), book.due(CUP.week()), "while the week runs nothing is due");

        // the server is down over the rollover (or crashed before the settlement committed): it boots
        // in the next week and settles what it missed
        long boot = CUP.week() + 7;
        assertEquals(List.of(CUP), book.due(boot), "at boot the missed Cup is due");
        CupPlan plan = book.settle(CUP, 10);
        assertEquals(List.of(14, 6), tokens(plan), "and it pays as it would have at the rollover");
        assertEquals(List.of(), book.due(boot), "once settled it is never due again");
        assertNull(book.settle(CUP, 10), "a second settlement (a second boot, a retried task) pays nothing");
        assertEquals(1, book.settlements().size(), "one settlement row");
    }

    @Test
    void severalMissedWeeksAreAllDueOldestFirst() {
        CupBook book = new CupBook();
        book.enter(NEXT_WEEK, p(1), 5, 100, 50, true, true);
        book.enter(CUP, p(1), 5, 100, 10, true, true);
        book.enter(OTHER_COURSE, p(2), 5, 100, 11, true, true);
        assertEquals(List.of(OTHER_COURSE, CUP, NEXT_WEEK), book.due(NEXT_WEEK.week() + 7),
                "two weeks late: last week's two Cups (by course) and then this one");
        assertEquals(List.of(OTHER_COURSE, CUP), book.due(NEXT_WEEK.week()),
                "a week late: only the Cups of weeks before the current one");
    }

    @Test
    void aCupNobodyEnteredIsNeverDue() {
        CupBook book = new CupBook();
        assertEquals(List.of(), book.due(CUP.week() + 700), "no entries, nothing to settle");
    }

    @Test
    void dueSkipsDuplicatesAndTheCurrentWeek() {
        assertEquals(List.of(CUP), CupRules.due(List.of(CUP, CUP, NEXT_WEEK), NEXT_WEEK.week()),
                "a Cup listed twice is due once; this week's is not due yet");
        assertEquals(List.of(), CupRules.due(List.of(NEXT_WEEK), NEXT_WEEK.week()), "the running week is never due");
    }

    @Test
    void settlingTheSameEntriesAgainGivesTheSamePlan() {
        CupBook a = new CupBook();
        CupBook b = new CupBook();
        for (CupBook book : List.of(a, b)) {
            for (int i = 1; i <= 5; i++) {
                book.enter(CUP, p(i), 5, 100, i, true, true);
                book.run(CUP, p(i), 40_000 + (i % 3) * 100, 100 + i);
            }
        }
        assertEquals(a.settle(CUP, 10), b.settle(CUP, 10),
                "a settlement retried after a crash rolled it back works out exactly the same payments");
    }
}
