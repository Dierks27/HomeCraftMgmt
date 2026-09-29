package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.engine.GenScheduler.Decision;
import com.dierks.homecraft.games.gen.engine.GenScheduler.Kind;
import com.dierks.homecraft.games.gen.engine.GenScheduler.Pin;
import com.dierks.homecraft.games.gen.engine.GenScheduler.SlotView;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a slot gets a new layout (GEN-SPEC §3.2, weekly addendum §1), with a fake clock in
 * America/Chicago and the owner's restarts at 04:00 and 16:00.
 *
 * <p>Pinned here: a stale edition builds and a current one doesn't; nothing starts in the 15 minutes
 * before either restart (03:45-04:00, 15:45-16:00); a failed try waits {@code retry_minutes} and
 * a day stops after {@code max_tries_per_day}; nothing before the startup delay; a pinned layout
 * that stands is restamped; a pin of another planner version is ignored; a reroll or an unverified
 * layout builds; the seed is the HMAC seed; no secret, no build; a job running two minutes before
 * a restart is abandoned.
 *
 * <p>And the cadence: weekly courses change on Monday at 04:00 and not on Tuesday; a cadence change
 * on reload keeps the current edition until the new schedule's first start (weekly to daily: the
 * next 04:00; daily to weekly: the day's own end; weekly to every 3 days: the fixed grid), or until
 * its own end when the change's moment is unknown; a reroll in that time is a new layout of the kept
 * edition; a later edition is never undone; a pin holds per edition.
 */
class GenSchedulerTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final long SECRET = 0x5eed_5eedL;
    private static final DailySettings S = GenKit.settings(SLOT);
    private static final RestartHold HOLD = new RestartHold(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)),
            GenKit.ZONE, 5);
    private static final Edition ED = new Edition(GenKit.ZONE, LocalTime.of(4, 0), DayOfWeek.MONDAY);
    private static final long DAY = 20725; // Tue 29 Sep 2026

    private static GenTag tag(long day, int reroll, long seed) {
        return new GenTag(SLOT, "parkour", 1, day, reroll, seed, 'A', "abc123abc123", 30_000, 60_000, 90_000,
                List.of(), List.of(), 0);
    }

    private static SlotView view(GenTag live) {
        return new SlotView(SLOT, true, false, live, true, "easy", "easy", 0, null, 1, 0, 0, 0, false, SECRET);
    }

    private static Decision at(SlotView v, long now) {
        return GenScheduler.decide(v, now, 0, S, HOLD, ED);
    }

    @Test
    void aStaleDayBuildsTodaysSeedAndACurrentOneDoesNot() {
        long morning = GenKit.at(2026, 9, 29, 4, 1);
        Decision d = at(view(tag(DAY - 1, 0, 1)), morning);
        assertEquals(Kind.BUILD, d.kind(), "yesterday's layout at 04:01 means build");
        assertEquals(DAY, d.day(), "for today");
        assertEquals(0, d.reroll(), "no reroll");
        assertEquals(GenSeed.seed(SECRET, DAY, SLOT, 0), d.seed(), "from the HMAC seed of the day and slot");
        assertEquals(Kind.BUILD, at(view(null), morning).kind(), "no layout at all means build too");
        assertEquals(Kind.NONE, at(view(tag(DAY, 0, 1)), morning).kind(), "today's layout means nothing to do");
        assertEquals(Kind.NONE, at(view(tag(DAY - 1, 0, 1)), GenKit.at(2026, 9, 29, 3, 30)).kind(),
                "at 03:30 it is still yesterday's course day");
        SlotView off = new SlotView(SLOT, false, false, null, true, "easy", "easy", 0, null, 1, 0, 0, 0, false, SECRET);
        assertEquals(Kind.NONE, at(off, morning).kind(), "a slot that is off never builds");
        SlotView busy = new SlotView(SLOT, true, true, null, true, "easy", "easy", 0, null, 1, 0, 0, 0, false, SECRET);
        assertEquals(Kind.WAIT, at(busy, morning).kind(), "one build at a time");
    }

    @Test
    void nothingStartsInTheFifteenMinutesBeforeEitherRestart() {
        SlotView due = view(tag(DAY - 1, 0, 1)); // e.g. a build that failed and is retried during the day
        for (int[] hm : new int[][]{{15, 45}, {15, 50}, {15, 59}}) {
            Decision d = at(due, GenKit.at(2026, 9, 29, hm[0], hm[1]));
            assertEquals(Kind.WAIT, d.kind(), hm[0] + ":" + hm[1] + " is too close to 16:00");
            assertTrue(d.reason().contains("4:00 PM"), "and says when: " + d.reason());
        }
        assertEquals(Kind.BUILD, at(due, GenKit.at(2026, 9, 29, 15, 44)).kind(), "15:44 is not");
        assertEquals(Kind.BUILD, at(due, GenKit.at(2026, 9, 29, 16, 0)).kind(), "at the restart the next one is 04:00");
        SlotView rerolled = new SlotView(SLOT, true, false, tag(DAY, 0, 1), true, "easy", "easy", 1, null, 1, 0, 0, 0,
                false, SECRET);
        assertEquals(Kind.WAIT, at(rerolled, GenKit.at(2026, 9, 30, 3, 50)).kind(),
                "03:45-04:00 too (a reroll due at 03:50)");
        assertEquals(Kind.NONE, at(view(tag(DAY, 0, 1)), GenKit.at(2026, 9, 29, 15, 50)).kind(),
                "a slot with nothing due isn't held, it just has nothing to do");
        RestartHold none = new RestartHold(List.of(), GenKit.ZONE, 5);
        assertEquals(Kind.BUILD, GenScheduler.decide(due, GenKit.at(2026, 9, 29, 15, 50), 0, S, none, ED).kind(),
                "with no restart times nothing is ever held");
    }

    @Test
    void aFailedTryWaitsRetryMinutesAndADayStopsAfterMaxTries() {
        long now = GenKit.at(2026, 9, 29, 9, 0);
        long lastTry = now - 29 * 60_000L;
        SlotView once = new SlotView(SLOT, true, false, tag(DAY - 1, 0, 1), true, "easy", "easy", 0, null, 1, DAY, 1,
                lastTry, false, SECRET);
        Decision d = at(once, now);
        assertEquals(Kind.WAIT, d.kind(), "29 minutes after a failed try is too soon");
        assertTrue(d.reason().contains("9:01 AM"), "and says when the next is: " + d.reason());
        assertEquals(Kind.BUILD, at(once, now + 60_000).kind(), "30 minutes after, it tries again");
        SlotView four = new SlotView(SLOT, true, false, tag(DAY - 1, 0, 1), true, "easy", "easy", 0, null, 1, DAY,
                S.maxTriesPerDay(), lastTry - 3_600_000L, false, SECRET);
        Decision given = at(four, now + 3_600_000L);
        assertEquals(Kind.WAIT, given.kind(), "after max_tries_per_day it gives up for the day");
        assertTrue(given.reason().contains("gave up"), given.reason());
        SlotView yesterday = new SlotView(SLOT, true, false, tag(DAY - 1, 0, 1), true, "easy", "easy", 0, null, 1,
                DAY - 1, S.maxTriesPerDay(), lastTry, false, SECRET);
        assertEquals(Kind.BUILD, at(yesterday, now).kind(), "yesterday's failed tries don't count today");
    }

    @Test
    void nothingIsBuiltBeforeTheStartupDelay() {
        long now = GenKit.at(2026, 9, 29, 4, 0) + 40_000;
        long readyAt = now + S.startupDelaySeconds() * 1000L;
        Decision early = GenScheduler.decide(view(tag(DAY - 1, 0, 1)), now, readyAt, S, HOLD, ED);
        assertEquals(Kind.WAIT, early.kind(), "the worlds are ready but the delay isn't over");
        assertEquals(Kind.BUILD, GenScheduler.decide(view(tag(DAY - 1, 0, 1)), readyAt, readyAt, S, HOLD, ED).kind(),
                "at the end of the delay it builds");
    }

    @Test
    void aPinnedLayoutThatStandsIsRestampedAndOtherwiseBuilt() {
        long now = GenKit.at(2026, 9, 30, 4, 1);
        long seed = 0x3f2a91c07d1e55b0L;
        Pin pin = new Pin(seed, 1, 0);
        SlotView pinned = new SlotView(SLOT, true, false, tag(DAY, 0, seed), true, "easy", "easy", 0, pin, 1, 0, 0, 0,
                false, SECRET);
        Decision d = at(pinned, now);
        assertEquals(Kind.RESTAMP, d.kind(), "the pinned layout stands: a new day, no blocks");
        assertEquals(DAY + 1, d.day(), "for today");
        SlotView other = new SlotView(SLOT, true, false, tag(DAY, 0, 99), true, "easy", "easy", 0, pin, 1, 0, 0, 0,
                false, SECRET);
        Decision build = at(other, now);
        assertEquals(Kind.BUILD, build.kind(), "a pin of a layout that isn't standing is built");
        assertEquals(seed, build.seed(), "from the pinned seed");
        SlotView newTier = new SlotView(SLOT, true, false, tag(DAY, 0, seed), true, "easy", "hard", 0, pin, 1, 0, 0, 0,
                false, SECRET);
        assertEquals(Kind.BUILD, at(newTier, now).kind(), "a new tier over a pin is a new layout");
        SlotView oldAlgo = new SlotView(SLOT, true, false, tag(DAY, 0, seed), true, "easy", "easy", 0, pin, 2, 0, 0, 0,
                false, SECRET);
        Decision fresh = at(oldAlgo, now);
        assertEquals(Kind.BUILD, fresh.kind(), "a pin made under another planner version is ignored");
        assertEquals(GenSeed.seed(SECRET, DAY + 1, SLOT, 0), fresh.seed(), "and the daily seed is used");
        Pin lapsed = new Pin(seed, 1, DAY);
        SlotView ended = new SlotView(SLOT, true, false, tag(DAY, 0, seed), true, "easy", "easy", 0, lapsed, 1, 0, 0, 0,
                false, SECRET);
        assertEquals(GenSeed.seed(SECRET, DAY + 1, SLOT, 0), at(ended, now).seed(), "a pin past its last day lapses");
        assertEquals(Pin.parse(pin.text()), pin, "a pin round-trips through its stored text");
        assertNull(Pin.parse("nonsense"), "and junk isn't a pin");
    }

    @Test
    void aRerollOrALayoutThatCouldNotBeVouchedForBuildsTheSameDay() {
        long now = GenKit.at(2026, 9, 29, 12, 0);
        SlotView rerolled = new SlotView(SLOT, true, false, tag(DAY, 0, 1), true, "easy", "easy", 2, null, 1, 0, 0, 0,
                false, SECRET);
        Decision d = at(rerolled, now);
        assertEquals(Kind.BUILD, d.kind(), "an admin's reroll builds today");
        assertEquals(2, d.reroll(), "as that reroll");
        assertEquals(GenSeed.seed(SECRET, DAY, SLOT, 2), d.seed(), "with that reroll's seed");
        SlotView broken = new SlotView(SLOT, true, false, tag(DAY, 0, 1), false, "easy", "easy", 0, null, 1, 0, 0, 0,
                false, SECRET);
        assertEquals(Kind.BUILD, at(broken, now).kind(), "a live layout the boot check couldn't vouch for is replaced");
        SlotView dirty = new SlotView(SLOT, true, false, tag(DAY, 0, 1), true, "easy", "easy", 0, null, 1, 0, 0, 0,
                true, SECRET);
        assertEquals(Kind.CLEAR_OLD, at(dirty, now).kind(), "with nothing due, the idle half is emptied");
    }

    @Test
    void noSecretNoBuild() {
        SlotView noSecret = new SlotView(SLOT, true, false, tag(DAY - 1, 0, 1), true, "easy", "easy", 0, null, 1, 0, 0,
                0, false, null);
        Decision d = at(noSecret, GenKit.at(2026, 9, 29, 5, 0));
        assertEquals(Kind.WAIT, d.kind(), "the old layout stays");
        assertTrue(d.reason().contains("secret"), d.reason());
    }

    // ---- editions (weekly addendum §1) -------------------------------------------------------------

    private static final DailySettings WEEKLY = GenKit.weekly(SLOT);
    private static final long MON_28_SEP = 20724;

    private static Edition every(int days) {
        return new Edition(GenKit.ZONE, LocalTime.of(4, 0), DayOfWeek.MONDAY, days, null);
    }

    private static GenTag edition(int cadence, long start, int reroll, long seed) {
        return tag(start, reroll, seed).withEdition(cadence, start, reroll);
    }

    private static SlotView view(GenTag live, long since) {
        return new SlotView(SLOT, true, false, live, true, "easy", "easy", 0, null, 1, 0, 0, 0, false, SECRET, since);
    }

    @Test
    void weeklyTheCoursesChangeOnMondayAt400AndNotOnTuesday() {
        Edition weekly = every(7);
        GenTag week = edition(7, MON_28_SEP, 0, 1);
        for (long t : new long[]{GenKit.at(2026, 9, 29, 4, 1), GenKit.at(2026, 9, 29, 12, 0),
                GenKit.at(2026, 10, 1, 9, 0), GenKit.at(2026, 10, 4, 23, 0), GenKit.at(2026, 10, 5, 3, 59)}) {
            assertEquals(Kind.NONE, GenScheduler.decide(view(week), t, 0, WEEKLY, HOLD, weekly).kind(),
                    "this week's courses stay all week (at " + t + ")");
        }
        Decision monday = GenScheduler.decide(view(week), GenKit.at(2026, 10, 5, 4, 0), 0, WEEKLY, HOLD, weekly);
        assertEquals(Kind.BUILD, monday.kind(), "Monday 04:00 builds the next week");
        assertEquals(MON_28_SEP + 7, monday.day(), "for the week of Mon 5 Oct");
        assertEquals(7, monday.cadence(), "a weekly edition");
        assertEquals(GenSeed.seed(SECRET, 7, MON_28_SEP + 7, SLOT, 0), monday.seed(),
                "from the seed of (secret, 7:39, slot)");
        Decision first = GenScheduler.decide(view(null), GenKit.at(2026, 9, 30, 12, 0), 0, WEEKLY, HOLD, weekly);
        assertEquals(MON_28_SEP, first.day(), "a first build mid-week is this week's edition, begun Monday");
        GenTag daily = tag(20725, 0, 1);
        assertEquals(Kind.NONE, GenScheduler.decide(view(daily), GenKit.at(2026, 9, 29, 12, 0), 0, WEEKLY, HOLD,
                weekly).kind(), "a daily layout is kept after a switch to weekly (until its own end)");
    }

    @Test
    void weeklyToDailyKeepsTheWeekUntilTheNext400ThenChangesDaily() {
        Edition daily = every(1);
        GenTag week = edition(7, MON_28_SEP, 0, 1);
        long since = GenKit.at(2026, 9, 30, 15, 0); // the owner reloads on Wednesday afternoon
        GenScheduler.Target kept = GenScheduler.target(week, GenKit.at(2026, 9, 30, 16, 0), daily, since);
        assertTrue(kept.kept(), "the week's layout is kept");
        assertEquals(MON_28_SEP, kept.start(), "as the week of 28 Sep");
        assertEquals(GenKit.at(2026, 10, 1, 4, 0), kept.endsAt(), "until the next 4:00 AM, the new schedule's first");
        assertEquals(Kind.NONE, GenScheduler.decide(view(week, since), GenKit.at(2026, 10, 1, 3, 59), 0, S, HOLD,
                daily).kind(), "never rebuilt mid-edition just because the setting changed");
        Decision thu = GenScheduler.decide(view(week, since), GenKit.at(2026, 10, 1, 4, 0), 0, S, HOLD, daily);
        assertEquals(Kind.BUILD, thu.kind(), "at 04:00 the daily courses begin");
        assertEquals(20727, thu.day(), "Thursday's");
        assertEquals(1, thu.cadence(), "a daily edition");
        assertEquals(GenSeed.seed(SECRET, 1, 20727, SLOT, 0), thu.seed(), "with a daily seed");
        GenScheduler.Target unknown = GenScheduler.target(week, GenKit.at(2026, 9, 30, 16, 0), daily, 0);
        assertEquals(GenKit.at(2026, 10, 5, 4, 0), unknown.endsAt(),
                "when the change's moment is unknown, the layout is kept until its own end");
    }

    @Test
    void dailyToWeeklyKeepsTodaysUntilTheNext400ThenTheWeeksSetGoesUp() {
        Edition weekly = every(7);
        GenTag wednesday = tag(20726, 0, 1);
        long since = GenKit.at(2026, 9, 30, 15, 0);
        GenScheduler.Target kept = GenScheduler.target(wednesday, GenKit.at(2026, 9, 30, 16, 0), weekly, since);
        assertTrue(kept.kept(), "Wednesday's daily layout is kept");
        assertEquals(GenKit.at(2026, 10, 1, 4, 0), kept.endsAt(), "until its own end, the next 4:00 AM");
        Decision thu = GenScheduler.decide(view(wednesday, since), GenKit.at(2026, 10, 1, 4, 0), 0, WEEKLY, HOLD,
                weekly);
        assertEquals(Kind.BUILD, thu.kind(), "then the week's set goes up");
        assertEquals(MON_28_SEP, thu.day(), "the week that began on Monday (key 7:38)");
        assertEquals(7, thu.cadence(), "weekly");
        GenTag week = edition(7, MON_28_SEP, 0, 2);
        assertEquals(Kind.NONE, GenScheduler.decide(view(week, since), GenKit.at(2026, 10, 3, 12, 0), 0, WEEKLY, HOLD,
                weekly).kind(), "and it stays until Monday");
    }

    @Test
    void weeklyToEveryThreeDaysChangesOnTheFixedThreeDayGrid() {
        Edition three = every(3);
        GenTag week = edition(7, MON_28_SEP, 0, 1);
        long since = GenKit.at(2026, 9, 30, 15, 0);
        GenScheduler.Target kept = GenScheduler.target(week, GenKit.at(2026, 9, 30, 16, 0), three, since);
        assertEquals(GenKit.at(2026, 10, 2, 4, 0), kept.endsAt(), "the next 3-day grid day is Fri 2 Oct");
        assertTrue(three.starts(20728), "which is on the grid");
        Decision fri = GenScheduler.decide(view(week, since), GenKit.at(2026, 10, 2, 4, 0), 0,
                GenKit.settings(SLOT).withCadence(3), HOLD, three);
        assertEquals(Kind.BUILD, fri.kind(), "and the courses change then");
        assertEquals(20728, fri.day(), "on that day");
        assertEquals(3, fri.cadence(), "every 3 days");
    }

    @Test
    void aRerollDuringAKeptEditionIsANewLayoutOfThatEditionAndALaterStartIsNeverUndone() {
        Edition daily = every(1);
        GenTag week = edition(7, MON_28_SEP, 0, 1);
        long since = GenKit.at(2026, 9, 30, 15, 0);
        SlotView rerolled = new SlotView(SLOT, true, false, week, true, "easy", "easy", 1, null, 1, 0, 0, 0, false,
                SECRET, since);
        Decision d = GenScheduler.decide(rerolled, GenKit.at(2026, 9, 30, 16, 0), 0, S, HOLD, daily);
        assertEquals(Kind.BUILD, d.kind(), "an admin's reroll builds at once");
        assertEquals(MON_28_SEP, d.day(), "the kept edition");
        assertEquals(7, d.cadence(), "as a weekly one");
        assertEquals(GenSeed.seed(SECRET, 7, MON_28_SEP, SLOT, 1), d.seed(), "with 7:38r1's seed");
        GenTag tomorrow = tag(20726, 0, 1); // made under a later rebuild_at, the day before it moved
        Edition fiveAm = new Edition(GenKit.ZONE, LocalTime.of(5, 0), DayOfWeek.MONDAY, 1, null);
        assertEquals(Kind.NONE, GenScheduler.decide(view(tomorrow), GenKit.at(2026, 9, 30, 4, 30), 0, S, HOLD,
                fiveAm).kind(), "a layout of a later edition is never replaced by an older one");
    }

    @Test
    void aPinHoldsForEditionsStartingOnOrBeforeItsLastDay() {
        Edition weekly = every(7);
        long seed = 0x3f2a91c07d1e55b0L;
        GenTag week = edition(7, MON_28_SEP, 0, seed);
        Pin twoWeeks = new Pin(seed, 1, MON_28_SEP + 13);
        SlotView pinned = new SlotView(SLOT, true, false, week, true, "easy", "easy", 0, twoWeeks, 1, 0, 0, 0, false,
                SECRET);
        Decision d = GenScheduler.decide(pinned, GenKit.at(2026, 10, 5, 4, 1), 0, WEEKLY, HOLD, weekly);
        assertEquals(Kind.RESTAMP, d.kind(), "the next week is restamped: new boards, no blocks");
        assertEquals(MON_28_SEP + 7, d.day(), "for the week of 5 Oct");
        assertEquals(7, d.cadence(), "as a weekly edition");
        SlotView later = new SlotView(SLOT, true, false, week.withEdition(7, MON_28_SEP + 7, 0), true, "easy", "easy",
                0, twoWeeks, 1, 0, 0, 0, false, SECRET);
        Decision lapsed = GenScheduler.decide(later, GenKit.at(2026, 10, 12, 4, 1), 0, WEEKLY, HOLD, weekly);
        assertEquals(GenSeed.seed(SECRET, 7, MON_28_SEP + 14, SLOT, 0), lapsed.seed(),
                "a week starting after its last day builds from the edition's own seed");
    }

    @Test
    void aJobTwoMinutesBeforeARestartIsAbandoned() {
        assertFalse(GenScheduler.abandon(GenKit.at(2026, 9, 29, 15, 57), HOLD), "three minutes before: keep going");
        assertTrue(GenScheduler.abandon(GenKit.at(2026, 9, 29, 15, 58), HOLD), "two minutes before: give up");
        assertTrue(GenScheduler.abandon(GenKit.at(2026, 9, 30, 3, 59), HOLD), "before 04:00 too");
        assertFalse(GenScheduler.abandon(GenKit.at(2026, 9, 30, 4, 0), HOLD), "at the restart the next is 12 hours off");
        assertFalse(GenScheduler.abandon(GenKit.at(2026, 9, 29, 15, 59), new RestartHold(List.of(), GenKit.ZONE, 5)),
                "with no restart times nothing is abandoned");
        assertFalse(GenScheduler.nearRestart(GenKit.at(2026, 9, 29, 15, 59), HOLD, 0),
                "avoid_before_restart_minutes: 0 holds nothing");
    }
}
