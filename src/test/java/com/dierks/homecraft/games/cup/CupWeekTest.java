package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static com.dierks.homecraft.games.cup.CupFixtures.NEXT_WEEK;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cup week (§D2: "paid at the week's rollover, on the quests' week start at 04:00, the same time
 * as the Fresh Courses change"): 03:59 on the first day is still last week's Cup and 04:00 is the new
 * one; a week ends at the next week's 04:00, an hour longer across the autumn DST change; a run
 * counts only for the Cup week it started and finished in, and on a Fresh slot only on that week's
 * own layout (never on last week's, still live until the new one is flipped in); a Cup runs its own
 * seven days even when the owner moves the week start; and only a Fresh slot that keeps one layout
 * for each whole week can run a Cup.
 */
class CupWeekTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Edition MONDAYS = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY);

    private static long at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static long day(int y, int mo, int d) {
        return LocalDate.of(y, mo, d).toEpochDay();
    }

    /** A Fresh rings slot's own layout for the edition starting on {@code day}, {@code cadence} days long. */
    private static GenTag fresh(long day, int cadence) {
        return new GenTag("fresh_rings", "rings", 1, day, 0, 42L, 'A', "0123456789ab", 30_000, 34_000, 40_000,
                List.of(), List.of(), 0L, cadence);
    }

    @Test
    void theCupWeekTurnsAtFourInTheMorningOnTheFirstDayOfTheWeek() {
        assertEquals(day(2026, 9, 21), CupRules.week(MONDAYS, at(2026, 9, 28, 3, 59)),
                "Monday 03:59 is still last week's Cup");
        assertEquals(day(2026, 9, 28), CupRules.week(MONDAYS, at(2026, 9, 28, 4, 0)),
                "Monday 04:00 is the new week's Cup");
        assertEquals(day(2026, 9, 28), CupRules.week(MONDAYS, at(2026, 10, 5, 3, 59)),
                "the next Monday 03:59 is still this week's");
        assertEquals(day(2026, 9, 28), CupRules.week(MONDAYS, at(2026, 10, 4, 0, 0)),
                "Sunday midnight is still this week's, although the time trials' own week has turned by then");
        assertEquals(CupFixtures.CUP.week(), CupRules.week(MONDAYS, at(2026, 9, 29, 12, 0)),
                "the fixtures' week is the week of 28 September");
    }

    @Test
    void aWeekIsSettledAtTheNextWeeksFirstRollover() {
        long week = day(2026, 9, 28);
        assertEquals(at(2026, 10, 5, 4, 0), CupRules.settlesAt(MONDAYS, week), "settled Monday 5 October 04:00");
        long dst = day(2026, 10, 26);
        assertEquals(7L * 24 * 3600_000 + 3600_000, CupRules.settlesAt(MONDAYS, dst) - MONDAYS.startOf(dst),
                "the week the clocks go back (1 November) is 169 hours long");
        assertEquals(dst + 7, CupRules.week(MONDAYS, CupRules.settlesAt(MONDAYS, dst)),
                "the instant a week is settled is the first instant of the next one");
        assertEquals(dst, CupRules.week(MONDAYS, CupRules.settlesAt(MONDAYS, dst) - 1),
                "a millisecond before, it is still the old week");
    }

    @Test
    void aRunCountsForTheCupWeekItStartedAndFinishedIn() {
        CupRules.Weeks inside = CupRules.runWeeks(MONDAYS, at(2026, 9, 29, 12, 0), at(2026, 9, 29, 12, 2), null);
        assertTrue(inside.contains(day(2026, 9, 28)), "a run inside the week counts for that week");
        assertFalse(inside.contains(day(2026, 9, 21)) || inside.contains(day(2026, 10, 5)),
                "and for neither week around it");
        CupRules.Weeks across = CupRules.runWeeks(MONDAYS, at(2026, 10, 5, 3, 58), at(2026, 10, 5, 4, 1), null);
        assertFalse(across.contains(day(2026, 9, 28)) || across.contains(day(2026, 10, 5)),
                "a run across the rollover counts for neither week's Cup: the old one was settled while it ran");
        assertTrue(CupRules.runWeeks(MONDAYS, at(2026, 10, 4, 23, 58), at(2026, 10, 5, 0, 1), null)
                .contains(day(2026, 9, 28)), "across midnight Sunday is still inside the Cup week");
        assertTrue(CupRules.runWeeks(MONDAYS, at(2026, 10, 1, 3, 58), at(2026, 10, 1, 4, 1), null)
                .contains(day(2026, 9, 28)), "across a weekday's 04:00 is still inside the Cup week");
        assertTrue(CupRules.runWeeks(MONDAYS, at(2026, 9, 29, 12, 2), at(2026, 9, 29, 12, 0), null).isEmpty(),
                "a run that finished before it started is no run");
    }

    @Test
    void withTheWeekStartUnchangedARunCountsForExactlyTheWeekItStartedAndFinishedIn() {
        Edition sundays = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY);
        GenRandom rnd = new GenRandom(0xC0DEL);
        long from = at(2026, 1, 1, 0, 0);
        for (int i = 0; i < 20_000; i++) {
            Edition ed = i % 2 == 0 ? MONDAYS : sundays;
            long start = from + (long) rnd.nextInt(0, 400 * 24 * 60) * 60_000L + rnd.nextInt(0, 59_999);
            long finish = start + rnd.nextInt(1, 40 * 60_000);
            long w = CupRules.week(ed, start);
            boolean sameWeek = w == CupRules.week(ed, finish);
            CupRules.Weeks weeks = CupRules.runWeeks(ed, start, finish, null);
            String why = ed.weekStart() + " run " + start + ".." + finish;
            for (long k = w - 14; k <= w + 14; k += 7) {
                assertEquals(sameWeek && k == w, weeks.contains(k),
                        why + ": the only week start it counts for is its own, and only when it didn't cross a rollover");
            }
        }
    }

    @Test
    void aRunOnLastWeeksFreshLayoutAfterTheRolloverCountsForNeitherCup() {
        Edition weekly = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, null);
        GenTag lastWeek = fresh(day(2026, 9, 28), 7);
        GenTag thisWeek = fresh(day(2026, 10, 5), 7);
        long rollover = at(2026, 10, 5, 4, 0);

        CupRules.Weeks late = CupRules.runWeeks(weekly, rollover + 60_000, rollover + 90_000, lastWeek);
        assertFalse(late.contains(day(2026, 10, 5)),
                "04:01 on last week's layout, still live because the new one isn't built and flipped yet: not the new "
                        + "week's Cup (everyone practised that layout all last week)");
        assertFalse(late.contains(day(2026, 9, 28)), "nor last week's, which was settled at 04:00");
        CupRules.Weeks dayLater = CupRules.runWeeks(weekly, at(2026, 10, 6, 15, 0), at(2026, 10, 6, 15, 2), lastWeek);
        assertFalse(dayLater.contains(day(2026, 9, 28)) || dayLater.contains(day(2026, 10, 5)),
                "a day later, after the failed build gave up until tomorrow: still no Cup");
        assertTrue(CupRules.runWeeks(weekly, rollover + 20 * 60_000, rollover + 21 * 60_000, thisWeek)
                        .contains(day(2026, 10, 5)),
                "once the week's own layout is flipped in, its runs count: the flip is the Cup's layout going up, "
                        + "not a change that voids it");
        assertTrue(CupRules.runWeeks(weekly, at(2026, 10, 4, 20, 0), at(2026, 10, 4, 20, 2), lastWeek)
                .contains(day(2026, 9, 28)), "on Sunday night the same layout is its own week's, and counts");
        CupRules.Weeks early = CupRules.runWeeks(weekly, at(2026, 10, 4, 20, 0), at(2026, 10, 4, 20, 2), thisWeek);
        assertFalse(early.contains(day(2026, 9, 28)) || early.contains(day(2026, 10, 5)),
                "a layout made for a week that hasn't started counts for no Cup either");

        CupBook book = new CupBook();
        long enterAt = rollover + 60_000;
        assertNull(book.enter(NEXT_WEEK, p(1), 5, 100, enterAt, CupRules.week(weekly, enterAt), true, true),
                "entering the new week's Cup at 04:01 is fine");
        long old = rollover + 3 * 60_000;
        assertFalse(book.run(NEXT_WEEK.course(), p(1), 40_000, old,
                        CupRules.runWeeks(weekly, old - 40_000, old, lastWeek)),
                "a run on last week's layout, started after entering, still sets no Cup time");
        assertFalse(book.entries(NEXT_WEEK).get(0).hasTime(), "the entrant has no Cup time yet");
        long fresh = rollover + 30 * 60_000;
        assertTrue(book.run(NEXT_WEEK.course(), p(1), 45_000, fresh,
                        CupRules.runWeeks(weekly, fresh - 45_000, fresh, thisWeek)),
                "their first run on this week's layout is their Cup time");
        assertEquals(45_000, book.entries(NEXT_WEEK).get(0).bestMs(), "slower than the old-layout run, and it stands");
    }

    @Test
    void aLayoutThatLastsTwoWeeksCountsForBothOfItsCupWeeks() {
        Edition fortnightly = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 14, null);
        assertEquals(day(2026, 9, 28), fortnightly.startOfEditionOn(day(2026, 10, 7)),
                "the fortnight of 28 September covers the week of 5 October");
        GenTag layout = fresh(day(2026, 9, 28), 14);
        assertTrue(CupRules.runWeeks(fortnightly, at(2026, 9, 30, 18, 0), at(2026, 9, 30, 18, 1), layout)
                .contains(day(2026, 9, 28)), "its first week");
        assertTrue(CupRules.runWeeks(fortnightly, at(2026, 10, 7, 18, 0), at(2026, 10, 7, 18, 1), layout)
                .contains(day(2026, 10, 5)), "and its second: the layout is that week's own");
        CupRules.Weeks after = CupRules.runWeeks(fortnightly, at(2026, 10, 12, 5, 0), at(2026, 10, 12, 5, 1), layout);
        assertFalse(after.contains(day(2026, 10, 12)) || after.contains(day(2026, 10, 5)),
                "but not the week after its edition ended");
    }

    @Test
    void handBuiltAndRecalledClassicCoursesCountByTimeAlone() {
        Edition weekly = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, null);
        long a = at(2026, 10, 5, 4, 1);
        assertTrue(CupRules.runWeeks(weekly, a, a + 30_000, null).contains(day(2026, 10, 5)),
                "a hand-built course keeps its blocks across the rollover: its runs count straight away");
        GenTag classic = fresh(day(2026, 3, 2), 7)
                .withRecall(new GenTag.Recall("fresh_classic_rings", at(2026, 9, 30, 12, 0), day(2026, 9, 30)));
        assertTrue(CupRules.runWeeks(weekly, a, a + 30_000, classic).contains(day(2026, 10, 5)),
                "a course recalled into a Classics slot stays put at the rollover too, although its edition is March's");
    }

    @Test
    void movingTheWeekStartMidWeekNeitherSettlesARunningCupEarlyNorStrandsItsEntrants() {
        Edition sundays = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY);
        long tue = at(2026, 9, 29, 12, 0);

        // Sunday weeks to Monday weeks, on Tuesday 29 September
        CupKey sundayCup = new CupKey("sky_rings", CupRules.week(sundays, tue));
        assertEquals(day(2026, 9, 27), sundayCup.week(), "the Cup running on Tuesday under Sunday weeks");
        assertEquals(List.of(), CupRules.due(List.of(sundayCup), MONDAYS.day(tue)),
                "after the switch to Monday weeks it is not due: it has five days left");
        assertEquals(List.of(), CupRules.due(List.of(sundayCup), MONDAYS.day(at(2026, 10, 4, 3, 59))),
                "nor at Sunday 03:59");
        assertEquals(List.of(sundayCup), CupRules.due(List.of(sundayCup), MONDAYS.day(at(2026, 10, 4, 4, 0))),
                "it is due at its own end, Sunday 4 October 04:00, whatever the week start says now");
        assertEquals(at(2026, 10, 4, 4, 0), CupRules.settlesAt(MONDAYS, sundayCup.week()),
                "and the settle time on its screen doesn't move");
        CupRules.Weeks thursday = CupRules.runWeeks(MONDAYS, at(2026, 10, 1, 18, 0), at(2026, 10, 1, 18, 2), null);
        assertTrue(thursday.contains(sundayCup.week()), "its entrants' runs on Thursday still count for it");
        assertTrue(thursday.contains(day(2026, 9, 28)), "and for the Monday Cup that opened, for those who entered it too");
        assertFalse(CupRules.runWeeks(MONDAYS, at(2026, 10, 4, 12, 0), at(2026, 10, 4, 12, 2), null)
                .contains(sundayCup.week()), "after its own end, no more");

        // Monday weeks to Sunday weeks, on Tuesday 29 September
        CupKey mondayCup = new CupKey("sky_rings", CupRules.week(MONDAYS, tue));
        long current = CupRules.week(sundays, tue);
        assertEquals(day(2026, 9, 27), current, "the current Cup week is now the one that began on Sunday");
        assertEquals(List.of(), CupRules.due(List.of(mondayCup), sundays.day(tue)), "the Monday Cup keeps running");
        assertEquals(List.of(mondayCup), CupRules.due(List.of(mondayCup), sundays.day(at(2026, 10, 5, 4, 0))),
                "to its own end, Monday 5 October 04:00");
        CupBook book = new CupBook();
        book.enter(mondayCup, p(1), 5, 100, tue - 3_600_000, mondayCup.week(), true, true);
        long wed = at(2026, 9, 30, 18, 0);
        assertTrue(book.run(mondayCup.course(), p(1), 40_000, wed, CupRules.runWeeks(sundays, wed - 40_000, wed, null)),
                "an entrant of the Monday Cup can still set a time in it after the switch, so their entry isn't "
                        + "paid to the others for a contest they couldn't race");
        assertEquals(CupRefusal.WEEK_OVER, book.enter(mondayCup, p(2), 5, 100, wed, current, true, true),
                "new entries go to the current week's Cup, not the old key");
    }
    @Test
    void onlyFreshSlotsThatKeepALayoutAllWeekCanRunACup() {
        assertTrue(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.MONDAY)),
                "weekly, changing on the week start: one layout per Cup week");
        assertTrue(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 14, null)),
                "every other week, on the week start: each Cup week has one layout");
        assertFalse(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 1, null)),
                "daily: the layout changes six times a week, so the Cup would be voided every day");
        assertFalse(CupRules.freshEligible(new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.FRIDAY)),
                "weekly but changing on Fridays: the layout changes mid-week");
    }

    @Test
    void theWeekFollowsTheQuestsWeekStart() {
        Edition sundays = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY);
        assertEquals(day(2026, 9, 27), CupRules.week(sundays, at(2026, 9, 29, 12, 0)),
                "with weeks starting on Sunday, Tuesday is in the week of Sunday 27 September");
        assertEquals(day(2026, 9, 20), CupRules.week(sundays, at(2026, 9, 27, 3, 59)),
                "Sunday 03:59 is still the week before");
    }

    /**
     * fx2-C #12: a Cup takes no entry once no run started now could set a Cup time before it is paid:
     * in its last {@code restart_hold_minutes}, and in a restart hold whose restart leaves less than
     * that before it is paid (runs are held then, and after the rollover the week is over).
     */
    @Test
    void theCupStopsTakingEntriesWhenNoRunCouldStillSetACupTime() {
        long settles = at(2026, 10, 5, 4, 0); // Monday 04:00
        com.dierks.homecraft.games.RestartHold owner = new com.dierks.homecraft.games.RestartHold(
                List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), CHICAGO, 5);
        assertFalse(CupRules.closing(at(2026, 10, 5, 3, 54), settles, owner), "03:54 still takes entries");
        assertTrue(CupRules.closing(at(2026, 10, 5, 3, 55), settles, owner),
                "from 03:55 the 04:00 restart holds every run, and at 04:00 the Cup is paid: closed");
        assertTrue(CupRules.closing(at(2026, 10, 5, 3, 59), settles, owner), "right up to the payout");
        assertFalse(CupRules.closing(at(2026, 10, 4, 15, 56), settles, owner),
                "Sunday's 4:00 PM hold is a pause, not the end: there is a whole evening left");
        com.dierks.homecraft.games.RestartHold none = new com.dierks.homecraft.games.RestartHold(List.of(), CHICAGO, 5);
        assertTrue(CupRules.closing(at(2026, 10, 5, 3, 56), settles, none),
                "with no restarts set, the last 5 minutes are still too short to set a Cup time");
        assertFalse(CupRules.closing(at(2026, 10, 5, 3, 54), settles, null), "and before them it takes entries");
        com.dierks.homecraft.games.RestartHold early = new com.dierks.homecraft.games.RestartHold(
                List.of(LocalTime.of(3, 54)), CHICAGO, 5);
        assertTrue(CupRules.closing(at(2026, 10, 5, 3, 50), settles, early),
                "held for a 03:54 restart at 03:50: after it, the Cup's last minutes are all that is left");
        com.dierks.homecraft.games.RestartHold earlier = new com.dierks.homecraft.games.RestartHold(
                List.of(LocalTime.of(3, 30)), CHICAGO, 5);
        assertFalse(CupRules.closing(at(2026, 10, 5, 3, 27), settles, earlier),
                "held for a 03:30 restart: there is time after it to set a Cup time");
    }
}
