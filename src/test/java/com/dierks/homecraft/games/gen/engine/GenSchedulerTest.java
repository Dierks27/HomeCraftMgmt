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
 * When a slot gets a new layout (GEN-SPEC §3.2), with a fake clock in America/Chicago and the
 * owner's restarts at 04:00 and 16:00.
 *
 * <p>Pinned here: a stale day builds and a current one doesn't; nothing starts in the 15 minutes
 * before either restart (03:45-04:00, 15:45-16:00); a failed try waits {@code retry_minutes} and
 * a day stops after {@code max_tries_per_day}; nothing before the startup delay; a pinned layout
 * that stands is restamped; a pin of another planner version is ignored; a reroll or an unverified
 * layout builds; the seed is the HMAC seed; no secret, no build; a job running two minutes before
 * a restart is abandoned.
 */
class GenSchedulerTest {

    private static final String SLOT = "daily_parkour_easy";
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
