package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases of the tick found in review, each pinned against the shipped catalog.
 *
 * <p>Pins: a news flash that came due while the server was down is held for a player again from
 * the first live tick (not fired silently at plugin enable), also when every missed boundary was
 * skipped; a real-world move whose Crate price cannot visibly move (sold out, or the predictable
 * cap already used) is never the headline; an event's quoted prices are the move that really
 * happens when a narrowed band already clips the mood, and an event that would barely move is
 * not made; a HOT/DEAL quotes its price against the usual price, as the tiles do, when it is
 * announced; a SEASON row is kept while the calendar still runs its season, so no second row is
 * made for the same {@code id:year}; the clock going back is detected.
 */
class MarketSimulatorEdgeTest {

    private static final ZoneId ZONE = MarketSimulatorSoakTest.ZONE;
    private static final double SPREAD = MarketSimulatorSoakTest.SPREAD;
    private static final long H = SimMath.HOUR_MS;
    private static final long MIN = SimMath.MINUTE_MS;
    private static final SimSettings S = SimSettings.defaults();
    private static final long T = S.tickMs();
    private static final OnlineInfo READY = new OnlineInfo(1, 60 * MIN);

    private static long at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static SortedMap<String, ItemParams> items(SimSettings s) {
        return MarketSimulatorSoakTest.params(s);
    }

    private static Map<String, MarketSimulator.Quote> market() {
        return MarketSimulatorSoakTest.shippedMarket();
    }

    private static MarketSimulator.TickInput input(SimSettings s, long seed, long b, Map<String, ItemSimState> state,
                                                   List<MarketEvent> events, Schedule sched, BroadcastState bs,
                                                   OnlineInfo online, List<RealImpulse> queued) {
        return new MarketSimulator.TickInput(b, true, s, new SimRandom(seed), items(s), market(), state, events, sched,
                online, ZONE, SPREAD, queued, bs, Set.of(), null);
    }

    private static SimSettings withCatchup(int hours) {
        SimSettings d = S;
        return new SimSettings(true, d.tickMinutes(), hours, d.maxUpPercent(), d.maxDownPercent(), d.keepDays(),
                d.drift(), d.hot(), d.deal(), d.slotsPerItems(), d.cooldownDays(), d.popularWeight(), d.news(),
                d.announce(), d.headlines(), d.samePlural(), d.seasons(), d.real());
    }

    private static SimSettings band(double percent) {
        SimSettings d = S;
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), percent, percent, d.keepDays(), d.drift(),
                d.hot(), d.deal(), d.slotsPerItems(), d.cooldownDays(), d.popularWeight(), d.news(), d.announce(),
                d.headlines(), d.samePlural(), d.seasons(), d.real());
    }

    private static SimSettings realOn() {
        SimSettings.Real r = S.real();
        SimSettings.Real on = new SimSettings.Real(true, r.provider(), r.url(), r.fetchTime(), r.gain(), r.maxPercent(),
                r.ignoreAbovePercent(), r.announceAbovePercent(), r.halfLifeHours(), r.lastsHours(), r.timeoutSeconds(),
                r.symbols());
        return new SimSettings(true, S.tickMinutes(), S.maxCatchupHours(), S.maxUpPercent(), S.maxDownPercent(),
                S.keepDays(), S.drift(), S.hot(), S.deal(), S.slotsPerItems(), S.cooldownDays(), S.popularWeight(),
                S.news(), S.announce(), S.headlines(), S.samePlural(), S.seasons(), on);
    }

    private static boolean isFlash(MarketEvent e) {
        return e.kind().news() || e.kind() == EventKind.WANTED;
    }

    // ---- a flash due during downtime --------------------------------------------------------

    @Test
    void aFlashThatCameDueWhileTheServerWasDownWaitsForAPlayerAgain() {
        long due = at(2026, 10, 3, 8, 0);          // Saturday morning
        long last = at(2026, 10, 2, 21, 5);        // the server stopped Friday night
        long now = at(2026, 10, 4, 15, 0) + 10_000; // and starts Sunday afternoon, nobody on yet
        long live = Math.floorDiv(now, T) * T;
        for (long seed = 1; seed <= 20; seed++) {
            Schedule sched = new Schedule(Long.MAX_VALUE, Long.MAX_VALUE, due, Schedule.NO_DAY, 0, null);
            BroadcastState bs = new BroadcastState(0, 0, 0L, true);
            Map<String, ItemSimState> state = new HashMap<>();
            MarketSimulator.TickResult r = MarketSimulator.run(
                    input(S, seed, last, state, List.of(), sched, bs, OnlineInfo.NOBODY, null), last, now);
            assertTrue(r.live());
            assertTrue(r.started().stream().noneMatch(MarketSimulatorEdgeTest::isFlash),
                    "seed " + seed + ": no silent flash at plugin enable: " + r.started());
            assertTrue(r.newsHeld(), "held for a player");
            assertEquals(live, sched.nextNewsAt(), "the hold starts at the first live tick");

            // The server stays up with nobody on: as with no downtime, it is still held at 21:00
            // and moves to the next morning.
            List<MarketEvent> events = new ArrayList<>(r.events());
            long b = live + T;
            long morning = at(2026, 10, 5, 7, 0);
            for (; b < morning; b += T) {
                MarketSimulator.TickResult x = MarketSimulator.tick(
                        input(S, seed, b, state, events, sched, bs, OnlineInfo.NOBODY, null));
                assertTrue(x.started().stream().noneMatch(MarketSimulatorEdgeTest::isFlash), "seed " + seed + " at " + b);
                events = new ArrayList<>(x.events());
            }
            assertTrue(sched.nextNewsAt() >= morning, "moved to the next opening");
            // Someone joins: it goes out live.
            MarketSimulator.TickResult fired = null;
            for (; b < morning + 4 * H && fired == null; b += T) {
                MarketSimulator.TickResult x = MarketSimulator.tick(input(S, seed, b, state, events, sched, bs, READY, null));
                events = new ArrayList<>(x.events());
                if (x.started().stream().anyMatch(MarketSimulatorEdgeTest::isFlash)) {
                    fired = x;
                }
            }
            assertNotNull(fired, "seed " + seed);
            assertTrue(fired.broadcast().isPresent() && fired.broadcast().get().type() == AnnounceGate.Type.FLASH,
                    "seed " + seed + ": announced when it fires");
        }
    }

    @Test
    void aFlashDueInSkippedBoundariesWaitsToo() {
        SimSettings noCatchup = withCatchup(0);
        long due = at(2026, 10, 3, 8, 0);
        long last = at(2026, 10, 2, 21, 5);
        long now = at(2026, 10, 4, 15, 0) + 10_000;
        Schedule sched = new Schedule(Long.MAX_VALUE, Long.MAX_VALUE, due, Schedule.NO_DAY, 0, null);
        MarketSimulator.TickResult r = MarketSimulator.run(input(noCatchup, 1, last, new HashMap<>(), List.of(), sched,
                new BroadcastState(0, 0, 0L, true), OnlineInfo.NOBODY, null), last, now);
        assertEquals(1, r.ticks(), "max_catchup_hours 0: only the live boundary runs");
        assertTrue(r.skipped() > 0);
        assertTrue(r.started().stream().noneMatch(MarketSimulatorEdgeTest::isFlash));
        assertTrue(r.newsHeld());
        assertEquals(Math.floorDiv(now, T) * T, sched.nextNewsAt());
    }

    @Test
    void aFlashHeldWhileTheServerRunsStillGoesSilentlyAfterItsHold() {
        // Live ticks keep the 14 h rule; a short gap with no replay does not reset it.
        long due = at(2026, 10, 3, 2, 0);
        Schedule sched = new Schedule(Long.MAX_VALUE, Long.MAX_VALUE, due, Schedule.NO_DAY, 0, null);
        Schedule copy = sched.copy();
        MarketSimulator.waitForLiveTick(copy, due - T, T);
        assertEquals(sched, copy, "not due yet: untouched");
        MarketSimulator.waitForLiveTick(copy, due, T);
        assertEquals(due + T, copy.nextNewsAt(), "due at a replayed boundary: due again at the next one");
        MarketSimulator.waitForLiveTick(copy, due + 7 * T, T);
        assertEquals(due + 8 * T, copy.nextNewsAt());
    }

    // ---- real-world headlines ---------------------------------------------------------------

    @Test
    void aRealMoveTheCratePriceCannotShowIsNeverTheHeadline() {
        SimSettings s = realOn();
        long b = at(2026, 10, 5, 17, 35); // Harvest Time: wheat already -4%
        Schedule sched = new Schedule(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Schedule.NO_DAY, 0, null);
        BroadcastState bs = new BroadcastState(0, 0, 0L, true);
        Map<String, ItemSimState> state = new HashMap<>();
        // Gold is sold out (it sits at its ceiling) and has the bigger real move.
        List<RealImpulse> q = List.of(new RealImpulse("gold_ingot", "gc.f", 20366, 0.04, "gold", 2.5),
                new RealImpulse("wheat", "zw.f", 20366, -0.04, "wheat", -2.2));
        MarketSimulator.TickResult r = MarketSimulator.tick(input(s, 7, b, state, List.of(), sched, bs, READY, q));
        MarketEvent gold = real(r, "gold_ingot");
        MarketEvent wheat = real(r, "wheat");
        assertEquals(gold.priceBefore(), gold.priceAfter(), "sold out: at the ceiling either way");
        assertNull(gold.announceDueAt(), "never announced as a move that did not happen");
        assertEquals(b, wheat.announceDueAt(), "the biggest move players can see is the headline");
        assertEquals(1L, Math.round(Math.abs(SimMath.pct(wheat.priceAfter(), wheat.priceBefore()))));
        assertTrue(r.broadcast().isEmpty() || !"gold_ingot".equals(r.broadcast().get().itemId()));

        // Next day wheat again: season + real are already at the predictable cap, so it cannot move.
        List<MarketEvent> events = new ArrayList<>(r.events());
        long b2 = b + 24 * H;
        MarketSimulator.TickResult r2 = MarketSimulator.tick(input(s, 7, b2, state, events, sched, bs, READY,
                List.of(new RealImpulse("wheat", "zw.f", 20367, -0.04, "wheat", -2.4))));
        MarketEvent again = real(r2, "wheat");
        assertEquals(again.priceBefore(), again.priceAfter(), 1e-12);
        assertNull(again.announceDueAt());
        assertTrue(r2.broadcast().isEmpty() || r2.broadcast().get().type() != AnnounceGate.Type.REAL);

        // Gold alone: a REAL row (it counts once gold is stocked again), but no headline at all.
        MarketSimulator.TickResult r3 = MarketSimulator.tick(input(s, 7, b, new HashMap<>(), List.of(),
                new Schedule(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Schedule.NO_DAY, 0, null),
                new BroadcastState(0, 0, 0L, true), READY,
                List.of(new RealImpulse("gold_ingot", "gc.f", 20366, 0.04, "gold", 2.5))));
        assertNull(real(r3, "gold_ingot").announceDueAt());
        assertTrue(r3.broadcast().isEmpty() || r3.broadcast().get().type() != AnnounceGate.Type.REAL);
    }

    private static MarketEvent real(MarketSimulator.TickResult r, String id) {
        return r.started().stream().filter(e -> e.kind() == EventKind.REAL && id.equals(e.itemId())).findFirst()
                .orElseThrow();
    }

    // ---- quoted prices are the real move ----------------------------------------------------

    @Test
    void aNarrowedBandPricesTheMoveThatReallyHappens() {
        SimSettings narrow = band(10); // M in [0.90, 1.10]
        // Iron's mood is already past the top: raw 1.15, shown as 1.10.
        EventPlanner.Candidate iron = new EventPlanner.Candidate("iron_ingot", 1.0, 22.49, 4.0, 40.0, 512, 2048,
                1.10, false, 0, 0, 0, 1.15);
        EventPlanner.Forced f = EventPlanner.forceNews(iron, EventKind.DOWN, 20.0, 0L, narrow, new SimRandom(1), 1);
        assertTrue(f.ok(), String.valueOf(f.error()));
        MarketEvent e = f.event();
        double a = -e.strength(); // 0.9 x (1.10 - 0.90) = 0.18
        assertEquals(0.18, a, 1e-9);
        double after = iron.priceAt(1.15 - a, narrow); // M = 0.97, not 1.10 - 0.18 = 0.92
        assertEquals(iron.priceAt(1.10, narrow), e.priceBefore(), 1e-12);
        assertEquals(after, e.priceAfter(), 1e-12);
        assertEquals((0.97 / 1.10 - 1.0) * 100.0, e.pct(), 1e-9, "-11.8%, the move that happens, not -16.4%");

        // A mood so far past the band that the event would barely move is refused.
        EventPlanner.Candidate swallowed = new EventPlanner.Candidate("iron_ingot", 1.0, 22.49, 4.0, 40.0, 512, 2048,
                1.10, true, 0, 0, 0, 1.26);
        assertFalse(EventPlanner.forceNews(swallowed, EventKind.DOWN, 20.0, 0L, narrow, new SimRandom(1), 1).ok());
        assertEquals(0.02, swallowed.moveBy(-0.18, narrow), 1e-9);
        assertEquals(0.18, iron.moveBy(-0.18, narrow) + 0.05, 1e-9, "0.13 of the 0.18 is real");
    }

    @Test
    void thePlannerNeverAnnouncesMoreThanTheBandLets() {
        SimSettings narrow = band(6); // M in [0.94, 1.06]: drift and seasons often reach past it
        long start = at(2026, 8, 15, 0, 0);
        int flashes = 0;
        for (long seed = 1; seed <= 6; seed++) {
            SimRandom rng = new SimRandom(seed);
            Schedule sched = Schedule.firstEnable(start, rng);
            BroadcastState bs = new BroadcastState(0, 0, 0L, true);
            Map<String, ItemSimState> state = new HashMap<>();
            List<MarketEvent> events = new ArrayList<>();
            for (long b = start; b < start + 60L * SimMath.DAY_MS; b += T) {
                MarketSimulator.TickResult r = MarketSimulator.tick(new MarketSimulator.TickInput(b, true, narrow, rng,
                        items(narrow), market(), state, events, sched, READY, ZONE, SPREAD, null, bs, Set.of(), null));
                for (MarketEvent e : r.started()) {
                    if (!e.kind().news()) {
                        continue;
                    }
                    flashes++;
                    double shown = r.status().get(e.itemId()).price();
                    assertEquals(e.priceAfter(), shown, 1e-9, "seed " + seed + ": the quoted price is the price");
                    double moved = Math.abs(SimMath.pct(shown, e.priceBefore()));
                    // A multiplier move of at least min_move from at most 1.06: at least 9.4%.
                    assertTrue(moved >= 100.0 * narrow.news().minMoveFrac() / narrow.multiplierHi() - 1e-6,
                            "seed " + seed + ": every flash really moves the price: " + e);
                }
                events = new ArrayList<>(r.events());
            }
        }
        assertTrue(flashes > 20, "flashes: " + flashes); // 39; before the fix 9 of 40 quoted a price never shown
    }

    // ---- HOT/DEAL quote the usual price -----------------------------------------------------

    @Test
    void aHotOrDealQuotesItsPriceAgainstTheUsualPrice() {
        MarketEvent hot = MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.11, 0L, 0L, 30 * H, 10 * H);
        // Usual $4.47; drift and Cozy Winter already had it at x1.07 ($4.78); the HOT takes it to $5.27.
        MarketEvent priced = EventPlanner.storyPriced(hot, 4.47, 4.78, 5.27);
        assertEquals((5.27 / 4.47 - 1.0) * 100.0, priced.pct(), 1e-9, "+18%, as the tile shows");
        assertEquals(4.47, priced.priceBefore());
        assertEquals(5.27, priced.priceAfter());
        // Under the usual price even with the HOT (a deep dip): never "+0%", its own move instead.
        MarketEvent dip = EventPlanner.storyPriced(hot, 4.47, 3.95, 4.38);
        assertEquals((4.38 / 3.95 - 1.0) * 100.0, dip.pct(), 1e-9);
        assertEquals(3.95, dip.priceBefore());
        MarketEvent deal = MarketEvent.story(EventKind.DEAL, Source.SIM, "wheat", 0.1, 0L, 0L, 30 * H, 10 * H);
        assertEquals((3.0 / 3.46 - 1.0) * 100.0, EventPlanner.storyPriced(deal, 3.46, 3.30, 3.0).pct(), 1e-9);
        assertEquals((3.55 / 3.90 - 1.0) * 100.0, EventPlanner.storyPriced(deal, 3.46, 3.90, 3.55).pct(), 1e-9,
                "a DEAL still over the usual price quotes its own cut");
    }

    @Test
    void anAnnouncedHotOrDealSaysWhatTheTileSays() {
        long start = at(2026, 12, 1, 12, 0); // Cozy Winter: oak +4%
        int n = 0;
        for (long seed = 1; seed <= 4; seed++) {
            SimRandom rng = new SimRandom(seed);
            Schedule sched = Schedule.firstEnable(start, rng);
            BroadcastState bs = new BroadcastState(0, 0, 0L, true);
            Map<String, ItemSimState> state = new HashMap<>();
            List<MarketEvent> events = new ArrayList<>();
            for (long b = start; b < start + 50L * SimMath.DAY_MS; b += T) {
                MarketSimulator.TickResult r = MarketSimulator.tick(new MarketSimulator.TickInput(b, true, S, rng,
                        items(S), market(), state, events, sched, new OnlineInfo(1, 3 * H), ZONE, SPREAD, null, bs,
                        Set.of(), null));
                Optional<AnnounceGate.Pending> cast = r.broadcast();
                if (cast.isPresent() && cast.get().type() == AnnounceGate.Type.STORY) {
                    MarketEvent e = cast.get().event();
                    ItemStatus st = r.status().get(e.itemId());
                    int sign = e.kind().sign();
                    if (Math.round(sign * st.pct()) >= 1) {
                        n++;
                        assertEquals(st.pct(), e.pct(), 1e-9, "seed " + seed + " " + e);
                        assertEquals(st.usual(), e.priceBefore(), 1e-12);
                        assertEquals(st.price(), e.priceAfter(), 1e-12);
                    } else {
                        assertTrue(sign * e.pct() > 0, "never the wrong way: " + e);
                    }
                    MarketEvent row = r.events().stream().filter(x -> x.id() == e.id() && x.startedAt() == e.startedAt()
                            && x.itemId().equals(e.itemId()) && x.kind() == e.kind()).findFirst().orElseThrow();
                    assertEquals(e.pct(), row.pct(), "the stored row carries what was announced");
                }
                events = new ArrayList<>(r.events());
            }
        }
        assertTrue(n > 5, "announced: " + n);
    }

    // ---- SEASON rows ------------------------------------------------------------------------

    @Test
    void aSeasonRowOutlivingItsStoredEndIsKeptWhileTheCalendarRunsIt() {
        // Harvest Time's row was written ending Nov 1; an admin then moved the season's end to
        // Nov 15. On Nov 3 the calendar still runs it and the row's own end is long past.
        List<Season> list = new ArrayList<>();
        for (Season x : SeasonCalendar.SHIPPED) {
            list.add(x.id().equals("harvest_time") ? new Season(x.id(), x.name(), x.from(),
                    java.time.MonthDay.of(11, 15), x.percent(), x.headline()) : x);
        }
        SimSettings.Seasons se = S.seasons();
        SimSettings s = new SimSettings(true, S.tickMinutes(), S.maxCatchupHours(), S.maxUpPercent(), S.maxDownPercent(),
                S.keepDays(), S.drift(), S.hot(), S.deal(), S.slotsPerItems(), S.cooldownDays(), S.popularWeight(),
                S.news(), S.announce(), S.headlines(), S.samePlural(),
                new SimSettings.Seasons(true, se.predictableMaxPercent(), se.rampDays(), list), S.real());
        long b = Math.floorDiv(at(2026, 11, 3, 12, 0), T) * T;
        MarketEvent stored = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026",
                at(2026, 9, 27, 12, 5), at(2026, 11, 1, 0, 0)).withAnnouncedAt(at(2026, 9, 27, 19, 30)).withId(5);
        MarketEvent over = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "back_to_school:2026",
                at(2026, 8, 15, 0, 5), at(2026, 9, 11, 0, 0)).withId(4);
        Schedule sched = new Schedule(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Schedule.NO_DAY, 0, null);
        MarketSimulator.TickResult r = MarketSimulator.tick(new MarketSimulator.TickInput(b, true, s, new SimRandom(3),
                items(s), market(), new HashMap<>(), List.of(over, stored), sched, READY, ZONE, SPREAD, null,
                new BroadcastState(0, 0, 0L, true), Set.of(), null));
        assertTrue(r.started().isEmpty(), "no second harvest_time:2026 row: " + r.started());
        assertTrue(r.events().contains(stored), "kept in the working set while the season runs");
        assertFalse(r.events().contains(over), "a season that is over leaves the working set as before");
        assertTrue(r.broadcast().isEmpty(), "and nothing is announced again");
        MarketSimulator.TickResult next = MarketSimulator.tick(new MarketSimulator.TickInput(b + T, true, s,
                new SimRandom(3), items(s), market(), new HashMap<>(), r.events(), sched, READY, ZONE, SPREAD, null,
                new BroadcastState(0, 0, 0L, true), Set.of(), null));
        assertTrue(next.started().isEmpty());
    }

    // ---- the clock going back ---------------------------------------------------------------

    @Test
    void theClockGoingBackIsNoticed() {
        long now = at(2026, 10, 5, 12, 2);
        long b = Math.floorDiv(now, T) * T;
        assertFalse(MarketSimulator.clockWentBack(b, now, T), "the normal case");
        assertFalse(MarketSimulator.clockWentBack(b - T, now, T));
        assertFalse(MarketSimulator.clockWentBack(b + T, now, T), "a small correction only delays a tick");
        assertTrue(MarketSimulator.clockWentBack(b + 2 * T, now, T));
        assertTrue(MarketSimulator.clockWentBack(b + 6 * H, now, T), "six hours ahead: carry on from now");
        // And nothing is due until the clock passes the stored tick, which is what froze it.
        assertTrue(MarketSimulator.boundaries(b + 6 * H, now, T, 48).isEmpty());
    }
}
