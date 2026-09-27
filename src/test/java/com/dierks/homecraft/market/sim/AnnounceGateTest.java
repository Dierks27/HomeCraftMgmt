package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which announcement goes out, and when (spec §6.1).
 *
 * <p>Pins the gate — inside 07:00-21:00, at least 20 minutes since the last broadcast, fewer
 * than 6 today, someone online for the announcement's join delay — and that an admin-forced one
 * skips all of it. The priority order INTRO, flash, HOT/DEAL start, SEASON, REAL, LAST CALL,
 * ENDING (a higher one still waiting on its join delay does not block a ready lower one). And
 * every window: a HOT/DEAL start only while FULL, LAST CALL in {@code [t1 - 3 h, t1 - 30 min]}
 * once, ENDING within 30 minutes of the end, SEASON for 7 days, REAL for 12 hours; a flash that
 * fired silently is never announced later.
 */
class AnnounceGateTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final long H = SimMath.HOUR_MS;
    private static final long MIN = SimMath.MINUTE_MS;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final SimSettings S = SimSettings.defaults();
    private static final SimRandom RNG = new SimRandom(11);
    private static final OnlineInfo ON = new OnlineInfo(2, 30 * MIN);

    private static long at(int h, int m) {
        return LocalDateTime.of(DAY, LocalTime.of(h, m)).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static final long NOON = at(12, 0);

    private static MarketEvent hot(long t0) {
        return MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, t0, 4 * H, 30 * H, 10 * H);
    }

    private static AnnounceGate.Pending pending(AnnounceGate.Type type, MarketEvent e) {
        return new AnnounceGate.Pending(type, e, Long.MIN_VALUE, Long.MAX_VALUE, 0L, false);
    }

    private static List<AnnounceGate.Type> types(List<AnnounceGate.Pending> ps) {
        List<AnnounceGate.Type> out = new ArrayList<>();
        for (AnnounceGate.Pending p : ps) {
            out.add(p.type());
        }
        return out;
    }

    private static Optional<AnnounceGate.Pending> choose(List<AnnounceGate.Pending> ps, long now, long lastAt, int today) {
        return AnnounceGate.choose(ps, now, ZONE, ON, lastAt, today, S, RNG);
    }

    // ---- the gate ---------------------------------------------------------------------------

    @Test
    void theGateNeedsNewsHoursATwentyMinuteGapAndRoomInTheDay() {
        assertTrue(AnnounceGate.gateOpen(NOON, ZONE, 0L, 0, S));
        assertFalse(AnnounceGate.gateOpen(at(6, 59), ZONE, 0L, 0, S), "before 07:00");
        assertTrue(AnnounceGate.gateOpen(at(7, 0), ZONE, 0L, 0, S));
        assertTrue(AnnounceGate.gateOpen(at(20, 59), ZONE, 0L, 0, S));
        assertFalse(AnnounceGate.gateOpen(at(21, 0), ZONE, 0L, 0, S), "21:00 is closed");
        assertFalse(AnnounceGate.gateOpen(NOON, ZONE, NOON - 20 * MIN + 1, 0, S), "under 20 minutes");
        assertTrue(AnnounceGate.gateOpen(NOON, ZONE, NOON - 20 * MIN, 0, S));
        assertTrue(AnnounceGate.gateOpen(NOON, ZONE, 0L, 5, S));
        assertFalse(AnnounceGate.gateOpen(NOON, ZONE, 0L, 6, S), "6 a day");

        List<AnnounceGate.Pending> story = List.of(pending(AnnounceGate.Type.STORY, hot(NOON - 10 * H)));
        assertTrue(choose(story, NOON, 0L, 0).isPresent());
        assertTrue(choose(story, NOON, NOON - 5 * MIN, 1).isEmpty());
        assertTrue(choose(story, NOON, 0L, 6).isEmpty());
        assertTrue(choose(story, at(22, 0), 0L, 0).isEmpty());
        assertTrue(AnnounceGate.choose(story, NOON, ZONE, OnlineInfo.NOBODY, 0L, 0, S, RNG).isEmpty(), "nobody on");
    }

    @Test
    void aForcedAnnouncementSkipsEveryGate() {
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.ADMIN, "wheat", 0.2, at(3, 0), 6 * H, 30 * H);
        AnnounceGate.Pending forced = AnnounceGate.forced(up);
        assertTrue(forced.forced());
        assertEquals(AnnounceGate.Type.FLASH, forced.type());
        Optional<AnnounceGate.Pending> got = AnnounceGate.choose(
                List.of(pending(AnnounceGate.Type.INTRO, null), forced), at(3, 0), ZONE, OnlineInfo.NOBODY,
                at(3, 0) - MIN, 6, S, RNG);
        assertSame(forced, got.orElseThrow(), "at 03:00, a minute after the last, the 7th today, nobody on");
        assertEquals(AnnounceGate.Type.STORY, AnnounceGate.forced(hot(NOON)).type());
    }

    @Test
    void theJoinDelayIsFixedPerEventAndMustBeMet() {
        MarketEvent h = hot(NOON - 10 * H);
        long delay = AnnounceGate.joinDelayMs(S, RNG, "HOT", "oak_log", h.startedAt());
        assertEquals(delay, AnnounceGate.joinDelayMs(S, RNG, "HOT", "oak_log", h.startedAt()));
        assertTrue(delay >= 2 * MIN && delay <= 8 * MIN, "delay " + delay);
        List<AnnounceGate.Pending> ps = AnnounceGate.collect(List.of(h), NOON, S, RNG, false);
        assertEquals(delay, ps.get(0).joinDelayMs());
        assertTrue(AnnounceGate.choose(ps, NOON, ZONE, new OnlineInfo(4, delay - 1), 0L, 0, S, RNG).isEmpty());
        assertTrue(AnnounceGate.choose(ps, NOON, ZONE, new OnlineInfo(1, delay), 0L, 0, S, RNG).isPresent());
        // A negative delay on a hand-made pending is worked out from the same rule.
        AnnounceGate.Pending rule = new AnnounceGate.Pending(AnnounceGate.Type.STORY, h, 0L, Long.MAX_VALUE, -1L, false);
        assertTrue(AnnounceGate.choose(List.of(rule), NOON, ZONE, new OnlineInfo(1, delay - 1), 0L, 0, S, RNG).isEmpty());
        assertTrue(AnnounceGate.choose(List.of(rule), NOON, ZONE, new OnlineInfo(1, delay), 0L, 0, S, RNG).isPresent());
    }

    // ---- priority ---------------------------------------------------------------------------

    @Test
    void thePriorityOrderIsIntroFlashStorySeasonRealLastCallEnding() {
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOON, 6 * H, 30 * H);
        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026", NOON, NOON + 30 * 24 * H);
        MarketEvent real = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", 0.03, NOON, 24 * H, 96 * H);
        List<AnnounceGate.Pending> all = new ArrayList<>(List.of(
                pending(AnnounceGate.Type.ENDING, hot(NOON - 60 * H)),
                pending(AnnounceGate.Type.LAST_CALL, hot(NOON - 30 * H)),
                pending(AnnounceGate.Type.REAL, real),
                pending(AnnounceGate.Type.SEASON, season),
                pending(AnnounceGate.Type.STORY, hot(NOON - 10 * H)),
                pending(AnnounceGate.Type.FLASH, up),
                pending(AnnounceGate.Type.INTRO, null)));
        List<AnnounceGate.Type> order = new ArrayList<>();
        while (!all.isEmpty()) {
            AnnounceGate.Pending p = choose(all, NOON, 0L, 0).orElseThrow();
            order.add(p.type());
            all.remove(p);
        }
        assertEquals(List.of(AnnounceGate.Type.INTRO, AnnounceGate.Type.FLASH, AnnounceGate.Type.STORY,
                AnnounceGate.Type.SEASON, AnnounceGate.Type.REAL, AnnounceGate.Type.LAST_CALL,
                AnnounceGate.Type.ENDING), order);
    }

    @Test
    void aWaitingHigherOneDoesNotBlockAReadyLowerOne() {
        AnnounceGate.Pending story = new AnnounceGate.Pending(AnnounceGate.Type.STORY, hot(NOON - 10 * H), 0L,
                Long.MAX_VALUE, 8 * MIN, false);
        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "x:2026", NOON, NOON + 99 * H);
        AnnounceGate.Pending seasonP = new AnnounceGate.Pending(AnnounceGate.Type.SEASON, season, 0L, Long.MAX_VALUE,
                2 * MIN, false);
        Optional<AnnounceGate.Pending> got = AnnounceGate.choose(List.of(story, seasonP), NOON, ZONE,
                new OnlineInfo(1, 5 * MIN), 0L, 0, S, RNG);
        assertSame(seasonP, got.orElseThrow());
        // Of two starts, the one due first goes first.
        AnnounceGate.Pending older = new AnnounceGate.Pending(AnnounceGate.Type.STORY, hot(NOON - 20 * H),
                NOON - 16 * H, Long.MAX_VALUE, 0L, false);
        AnnounceGate.Pending newer = new AnnounceGate.Pending(AnnounceGate.Type.STORY, hot(NOON - 10 * H),
                NOON - 6 * H, Long.MAX_VALUE, 0L, false);
        assertSame(older, choose(List.of(newer, older), NOON, 0L, 0).orElseThrow());
    }

    // ---- windows ----------------------------------------------------------------------------

    @Test
    void aHotOrDealStartIsAnnouncedOnlyWhileAtFullStrength() {
        long t0 = at(8, 0);
        MarketEvent h = hot(t0);
        long full = t0 + 4 * H;
        long t1 = h.holdEndsAt();
        assertTrue(AnnounceGate.collect(List.of(h), full - 1, S, RNG, false).isEmpty(), "silent while ramping");
        assertEquals(List.of(AnnounceGate.Type.STORY), types(AnnounceGate.collect(List.of(h), full, S, RNG, false)));
        assertEquals(List.of(AnnounceGate.Type.STORY), types(AnnounceGate.collect(List.of(h), t1 - 1, S, RNG, false)));
        assertTrue(AnnounceGate.collect(List.of(h), t1, S, RNG, false).isEmpty(), "never once it fades");
        assertTrue(AnnounceGate.collect(List.of(h.withStop(full + H, MarketSimulator.STOP_STOPPED)), full + 2 * H, S,
                RNG, false).isEmpty(), "a stopped story is not announced");
        MarketEvent announced = h.withAnnouncedAt(full);
        assertTrue(AnnounceGate.collect(List.of(announced), full + H, S, RNG, false).isEmpty(), "once");
    }

    @Test
    void lastCallRunsFromThreeHoursToHalfAnHourBeforeTheFade() {
        MarketEvent h = hot(at(8, 0)).withAnnouncedAt(at(12, 0));
        long t1 = h.holdEndsAt();
        assertTrue(AnnounceGate.collect(List.of(h), t1 - 3 * H - 1, S, RNG, false).isEmpty());
        assertEquals(List.of(AnnounceGate.Type.LAST_CALL), types(AnnounceGate.collect(List.of(h), t1 - 3 * H, S, RNG, false)));
        assertEquals(List.of(AnnounceGate.Type.LAST_CALL), types(AnnounceGate.collect(List.of(h), t1 - 30 * MIN, S, RNG, false)));
        assertTrue(AnnounceGate.collect(List.of(h), t1 - 30 * MIN + 1, S, RNG, false).isEmpty());
        assertTrue(AnnounceGate.collect(List.of(h.withLastCallAt(t1 - 2 * H)), t1 - H, S, RNG, false).isEmpty(), "once");
        assertEquals(List.of(AnnounceGate.Type.STORY), types(AnnounceGate.collect(List.of(hot(at(8, 0))), t1 - H, S,
                RNG, false)), "a story not yet announced still waits for its start, never a last call");
        assertTrue(AnnounceGate.collect(List.of(h.withStop(t1 - 4 * H, MarketSimulator.STOP_STOPPED)), t1 - 2 * H, S,
                RNG, false).stream().noneMatch(p -> p.type() == AnnounceGate.Type.LAST_CALL), "not after a stop");

        SimSettings.Story k = S.hot();
        SimSettings.Story never = new SimSettings.Story(true, k.percent(), k.holdHours(), k.rampHours(), k.fadeHours(),
                k.gapHours(), 0, k.sellLimit(), k.minStockPercent(), k.buyLimitShare());
        SimSettings noLastCall = new SimSettings(true, S.tickMinutes(), S.maxCatchupHours(), S.maxUpPercent(),
                S.maxDownPercent(), S.keepDays(), S.drift(), never, S.deal(), S.slotsPerItems(), S.cooldownDays(),
                S.popularWeight(), S.news(), S.announce(), S.headlines(), S.samePlural(), S.seasons(), S.real());
        assertTrue(AnnounceGate.collect(List.of(h), t1 - H, noLastCall, RNG, false).isEmpty(), "last_call_hours: 0");
    }

    @Test
    void theEndingLineHasHalfAnHour() {
        MarketEvent h = hot(at(8, 0)).withAnnouncedAt(at(12, 0));
        long end = h.endsAt();
        assertTrue(AnnounceGate.collect(List.of(h), end - 1, S, RNG, false).stream()
                .noneMatch(p -> p.type() == AnnounceGate.Type.ENDING));
        assertEquals(List.of(AnnounceGate.Type.ENDING), types(AnnounceGate.collect(List.of(h), end, S, RNG, false)));
        assertEquals(List.of(AnnounceGate.Type.ENDING), types(AnnounceGate.collect(List.of(h), end + 29 * MIN, S, RNG, false)));
        assertTrue(AnnounceGate.collect(List.of(h), end + 30 * MIN, S, RNG, false).isEmpty());
        assertTrue(AnnounceGate.collect(List.of(h.withEndLineAt(end)), end + 5 * MIN, S, RNG, false).isEmpty(), "once");

        // An admin stop still gets its line; a silent end does not.
        long ts = at(20, 0);
        MarketEvent stopped = h.withStop(ts, MarketSimulator.STOP_STOPPED);
        assertEquals(List.of(AnnounceGate.Type.ENDING), types(AnnounceGate.collect(List.of(stopped), stopped.endsAt(), S, RNG, false)));
        for (String silent : List.of(MarketSimulator.STOP_REMOVED, MarketSimulator.STOP_RESET, MarketSimulator.STOP_PAUSED,
                MarketSimulator.STOP_DISABLED, MarketSimulator.STOP_SOLD_OUT)) {
            MarketEvent ended = h.withEnd(ts, silent);
            assertTrue(AnnounceGate.collect(List.of(ended), ended.endsAt(), S, RNG, false).isEmpty(), silent);
        }
        SimSettings.Announce a = S.announce();
        SimSettings.Announce quiet = new SimSettings.Announce(a.chat(), a.title(), a.actionBar(), a.particles(),
                a.minGapMinutes(), a.maxPerDay(), false, a.intro(), a.soundUp(), a.soundDown(), a.soundStory(),
                a.soundOther(), a.volume(), a.pitchUp(), a.pitchDown(), a.catchUp(), a.catchUpLines(), a.catchUpHours());
        SimSettings noEndings = new SimSettings(true, S.tickMinutes(), S.maxCatchupHours(), S.maxUpPercent(),
                S.maxDownPercent(), S.keepDays(), S.drift(), S.hot(), S.deal(), S.slotsPerItems(), S.cooldownDays(),
                S.popularWeight(), S.news(), quiet, S.headlines(), S.samePlural(), S.seasons(), S.real());
        assertTrue(AnnounceGate.collect(List.of(h), end, noEndings, RNG, false).isEmpty(), "announce.endings: false");
    }

    @Test
    void aSeasonWaitsUpToSevenDaysAndARealMoveTwelveHours() {
        long s0 = at(0, 5);
        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026", s0,
                s0 + 40 * 24 * H);
        assertEquals(List.of(AnnounceGate.Type.SEASON), types(AnnounceGate.collect(List.of(season), s0, S, RNG, false)));
        assertEquals(1, AnnounceGate.collect(List.of(season), s0 + 7 * 24 * H - 1, S, RNG, false).size());
        assertTrue(AnnounceGate.collect(List.of(season), s0 + 7 * 24 * H, S, RNG, false).isEmpty());
        assertTrue(AnnounceGate.collect(List.of(season.withAnnouncedAt(s0 + H)), s0 + 2 * H, S, RNG, false).isEmpty());

        MarketEvent real = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", 0.03, s0, 24 * H, 96 * H);
        assertEquals(List.of(AnnounceGate.Type.REAL), types(AnnounceGate.collect(List.of(real), s0 + 12 * H - 1, S, RNG, false)));
        assertTrue(AnnounceGate.collect(List.of(real), s0 + 12 * H, S, RNG, false).isEmpty());
        MarketEvent notTheHeadline = real.toBuilder().announceDueAt(null).build();
        assertTrue(AnnounceGate.collect(List.of(notTheHeadline), s0 + H, S, RNG, false).isEmpty(),
                "only one real headline per fetch");
    }

    @Test
    void aSilentFlashStaysSilentAndTheIntroWaitsItsTurn() {
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, NOON, 6 * H, 30 * H);
        MarketEvent wanted = MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, NOON, NOON + 7 * 24 * H);
        assertTrue(AnnounceGate.collect(List.of(up, wanted), NOON + 5 * MIN, S, RNG, false).isEmpty());

        List<AnnounceGate.Pending> intro = AnnounceGate.collect(List.of(), NOON, S, RNG, true);
        assertEquals(List.of(AnnounceGate.Type.INTRO), types(intro));
        assertNull(intro.get(0).event());
        assertTrue(AnnounceGate.collect(List.of(), NOON, S, RNG, false).isEmpty(), "once ever");
    }

    @Test
    void sendingMarksTheRightColumn() {
        MarketEvent h = hot(NOON - 10 * H);
        assertEquals(NOON, AnnounceGate.markSent(pending(AnnounceGate.Type.STORY, h), NOON).announcedAt());
        assertEquals(NOON, AnnounceGate.markSent(pending(AnnounceGate.Type.LAST_CALL, h), NOON).lastCallAt());
        assertNull(AnnounceGate.markSent(pending(AnnounceGate.Type.LAST_CALL, h), NOON).announcedAt());
        assertEquals(NOON, AnnounceGate.markSent(pending(AnnounceGate.Type.ENDING, h), NOON).endLineAt());
        assertNull(AnnounceGate.markSent(pending(AnnounceGate.Type.INTRO, null), NOON));
    }

    // ---- seasons that no longer apply (review) --------------------------------------------

    private static SimSettings withSeasons(SimSettings.Seasons seasons) {
        return new SimSettings(true, S.tickMinutes(), S.maxCatchupHours(), S.maxUpPercent(), S.maxDownPercent(),
                S.keepDays(), S.drift(), S.hot(), S.deal(), S.slotsPerItems(), S.cooldownDays(), S.popularWeight(),
                S.news(), S.announce(), S.headlines(), S.samePlural(), seasons, S.real());
    }

    @Test
    void aSeasonIsNotAnnouncedOnceItsEffectIsGone() {
        long s0 = at(0, 5);
        MarketEvent harvest = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026", s0,
                s0 + 40 * 24 * H);
        long noon = s0 + 12 * H;
        SimSettings.Seasons on = S.seasons();
        assertEquals(1, AnnounceGate.collect(List.of(harvest), noon, S, RNG, false, 0.10).size(), "the shipped case");

        // seasons.enabled: false after /hcm reload: the row still waits, but it is no longer news.
        SimSettings off = withSeasons(new SimSettings.Seasons(false, on.predictableMaxPercent(), on.rampDays(), on.list()));
        assertTrue(AnnounceGate.collect(List.of(harvest), noon, off, RNG, false, 0.10).isEmpty());
        assertTrue(AnnounceGate.collect(List.of(harvest), noon, off, RNG, false).isEmpty());

        // The season was taken out of the list.
        List<Season> without = new ArrayList<>(on.list());
        without.removeIf(x -> x.id().equals("harvest_time"));
        SimSettings removed = withSeasons(new SimSettings.Seasons(true, on.predictableMaxPercent(), on.rampDays(), without));
        assertTrue(AnnounceGate.collect(List.of(harvest), noon, removed, RNG, false, 0.10).isEmpty());

        // market.spread 0: c_pred = 0, no season moves anything, so none is announced.
        assertTrue(AnnounceGate.collect(List.of(harvest), noon, S, RNG, false, 0.0).isEmpty());
        assertEquals(1, AnnounceGate.collect(List.of(harvest), noon, S, RNG, false, 0.05).size(),
                "a narrower cap still leaves wheat -2.25%");
    }

    // ---- the catch-up's rule (review) ------------------------------------------------------

    @Test
    void aHotThatBecameNewsAfterTheMarkMovedPastItIsStillCaughtUp() {
        // HOT id 1 starts its silent ramp at 08:00 and becomes news at 12:00. At 10:00 the player
        // hears flash id 2: their mark is 2 and they were brought up to date at 10:00.
        MarketEvent hot = hot(at(8, 0)).withId(1);
        MarketEvent flash = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, at(10, 0), 6 * H, 30 * H)
                .withId(2);
        long heardAt = at(10, 0) + 13_000;
        assertEquals(at(12, 0), hot.newsTime());
        assertTrue(AnnounceGate.unseen(hot, 2, heardAt), "became news after they were last told anything");
        assertFalse(AnnounceGate.unseen(flash, 2, heardAt), "they heard this one");

        // They heard the HOT itself go out (seen_at moves to the broadcast): not again.
        assertFalse(AnnounceGate.unseen(hot, 2, at(12, 0) + 13_000));
        // A catch-up after it became news listed it and moved seen_at: not again either.
        assertFalse(AnnounceGate.unseen(hot, 2, at(13, 0)));
        // Above the mark: always new.
        assertTrue(AnnounceGate.unseen(hot.withId(3), 2, at(13, 0)));
        // Only a HOT/DEAL gets its id before it is news; a flash below the mark stays seen.
        assertFalse(AnnounceGate.unseen(flash, 2, at(9, 0)));
        // seen_at unknown (0): the id rule alone.
        assertFalse(AnnounceGate.unseen(hot, 2, 0L));
        assertFalse(AnnounceGate.unseen(null, 0, 0L));
    }
}
