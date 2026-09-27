package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.MarketItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick is a pure, replayable function (spec §4, §10.1 #2) and follows its order of work.
 *
 * <p>Pins: 576 boundaries in one catch-up call give exactly what 576 single calls give with the
 * state copied between them (rows, schedule, counters, events, multipliers, what to insert and
 * update); replayed boundaries never start an event (only calendar SEASON rows), never apply a
 * real-world impulse and never broadcast; the same seed replays the same market and a copy of
 * the state taken mid-run continues exactly like the uninterrupted run; boundaries are
 * epoch-aligned and at most {@code max_catchup_hours} are replayed. And the tick's own rules: a
 * sold-out DEAL stops with a fade, events on a removed or {@code sim: false} item end at once, a
 * WANTED ends once stock arrives, one SEASON row per season and year, queued REAL impulses apply
 * once at the next live tick with one headline, the sim switched off is exactly neutral, a move
 * of more than 1% is reported as a jump, and the intro goes out once.
 *
 * <p>The save-through-{@code MarketSimDao}-and-reload case of §15 belongs with the DAO's tests.
 */
class MarketSimulatorDeterminismTest {

    private static final long H = SimMath.HOUR_MS;
    private static final long MIN = SimMath.MINUTE_MS;
    private static final SimSettings S = SimSettings.defaults();
    private static final long T = S.tickMs();
    private static final long B0 = Math.floorDiv(MarketSimulatorSoakTest.START, T) * T;
    private static final OnlineInfo ON = new OnlineInfo(1, 30 * MIN);
    /** The shipped settings with real-world prices switched on. */
    private static final SimSettings REAL_ON = realOn();

    private static SimSettings realOn() {
        SimSettings.Real r = S.real();
        SimSettings.Real on = new SimSettings.Real(true, r.provider(), r.url(), r.fetchTime(), r.gain(), r.maxPercent(),
                r.ignoreAbovePercent(), r.announceAbovePercent(), r.halfLifeHours(), r.lastsHours(), r.timeoutSeconds(),
                r.symbols());
        return new SimSettings(true, S.tickMinutes(), S.maxCatchupHours(), S.maxUpPercent(), S.maxDownPercent(),
                S.keepDays(), S.drift(), S.hot(), S.deal(), S.slotsPerItems(), S.cooldownDays(), S.popularWeight(),
                S.news(), S.announce(), S.headlines(), S.samePlural(), S.seasons(), on);
    }

    private static SortedMap<String, ItemParams> items() {
        return MarketSimulatorSoakTest.params(S);
    }

    private static Map<String, MarketSimulator.Quote> market() {
        return MarketSimulatorSoakTest.shippedMarket();
    }

    /** The shipped market with one item's stock changed and its balanced price back on the curve. */
    private static Map<String, MarketSimulator.Quote> market(String id, long stock) {
        Map<String, MarketSimulator.Quote> m = new TreeMap<>(market());
        MarketItem item = MarketSimulatorSoakTest.shippedCatalog().stream().filter(i -> i.id().equals(id))
                .findFirst().orElseThrow();
        m.put(id, new MarketSimulator.Quote(MarketSimulatorSoakTest.ENGINE.targetPrice(item, stock), stock));
        return m;
    }

    /** A working set mid-story: a HOT, a fresh UP, a DEAL and a WANTED. */
    private static List<MarketEvent> midStory() {
        return List.of(
                MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, B0 - 20 * H, 4 * H, 30 * H, 10 * H)
                        .withId(1).withAnnouncedAt(B0 - 16 * H),
                MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, B0 - H, 6 * H, 30 * H).withId(2),
                MarketEvent.story(EventKind.DEAL, Source.SIM, "iron_ingot", 0.1, B0 - 2 * H, 4 * H, 40 * H, 10 * H)
                        .withId(3),
                MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, B0 - 5 * H, B0 + 6 * 24 * H).withId(4));
    }

    private static Map<String, ItemSimState> freshState() {
        Map<String, ItemSimState> m = new HashMap<>();
        for (String id : items().keySet()) {
            m.put(id, ItemSimState.fresh(id).withDrift(id.length() % 2 == 0 ? 0.01 : -0.01, B0));
        }
        return m;
    }

    private static MarketSimulator.TickInput input(long seed, long b, boolean live, Map<String, ItemSimState> state,
                                                   List<MarketEvent> events, Schedule sched, BroadcastState bs,
                                                   List<RealImpulse> queued) {
        return input(S, market(), seed, b, live, state, events, sched, bs, queued);
    }

    private static MarketSimulator.TickInput input(SimSettings s, Map<String, MarketSimulator.Quote> market, long seed,
                                                   long b, boolean live, Map<String, ItemSimState> state,
                                                   List<MarketEvent> events, Schedule sched, BroadcastState bs,
                                                   List<RealImpulse> queued) {
        return new MarketSimulator.TickInput(b, live, s, new SimRandom(seed), items(), market, state, events, sched,
                ON, MarketSimulatorSoakTest.ZONE, MarketSimulatorSoakTest.SPREAD, queued, bs, Set.of("oak_log"), null);
    }

    /** {@link #midStory()} plus a flash on an item that is no longer in the catalog. */
    private static List<MarketEvent> withStray() {
        List<MarketEvent> out = new ArrayList<>(midStory());
        out.add(MarketEvent.shock(EventKind.UP, Source.SIM, "emerald", 0.2, B0 - 2 * H, 6 * H, 30 * H).withId(6));
        return out;
    }

    private static String key(MarketEvent e) {
        return e.kind() + "|" + e.itemId() + "|" + e.startedAt() + "|" + e.tag();
    }

    /** The latest version of every row a list of results inserts or updates. */
    private static Map<String, MarketEvent> writes(List<MarketSimulator.TickResult> results) {
        Map<String, MarketEvent> out = new LinkedHashMap<>();
        for (MarketSimulator.TickResult r : results) {
            for (MarketEvent e : r.started()) {
                out.put(key(e), e);
            }
            for (MarketEvent e : r.changed()) {
                out.put(key(e), e);
            }
        }
        return out;
    }

    private static Set<String> inserted(List<MarketSimulator.TickResult> results) {
        Set<String> out = new TreeSet<>();
        for (MarketSimulator.TickResult r : results) {
            for (MarketEvent e : r.started()) {
                out.add(key(e));
            }
        }
        return out;
    }

    private static final List<RealImpulse> IMPULSES = List.of(
            new RealImpulse("gold_ingot", "gc.f", 20724, 0.03, "gold", 1.5),
            new RealImpulse("wheat", "zw.f", 20724, -0.04, "wheat", -2.5));

    // ---- catch-up == tick by tick -------------------------------------------------------------

    @Test
    void aCatchUpRunEqualsTickByTickCallsWithTheStateCopied() {
        long seed = 77;
        long now = B0 + 576 * T + 40_000; // mid-tick: the last boundary is B0 + 576 T
        // Iron sold out (its DEAL stops during the replays), a stray event on an item that left
        // the catalog, real-world prices on with two impulses queued for the live boundary.
        Map<String, MarketSimulator.Quote> market = market("iron_ingot", 0);
        Map<String, ItemSimState> stateA = freshState();
        Schedule schedA = Schedule.firstEnable(B0 - 10 * H, new SimRandom(seed));
        BroadcastState bsA = new BroadcastState(0, 0, 0L, true);
        MarketSimulator.TickResult all = MarketSimulator.run(
                input(REAL_ON, market, seed, now, true, stateA, withStray(), schedA, bsA, IMPULSES), B0, now);
        assertEquals(576, all.ticks());
        assertEquals(0, all.skipped());
        assertEquals(B0 + 576 * T, all.boundary());
        assertTrue(all.live());

        Map<String, ItemSimState> state = freshState();
        Schedule sched = Schedule.firstEnable(B0 - 10 * H, new SimRandom(seed));
        BroadcastState bs = new BroadcastState(0, 0, 0L, true);
        List<MarketEvent> events = withStray();
        Map<String, Double> prev = null;
        List<MarketSimulator.TickResult> singles = new ArrayList<>();
        Set<String> jumped = new TreeSet<>();
        for (int k = 1; k <= 576; k++) {
            long b = B0 + k * T;
            boolean live = k == 576;
            // Copy everything, as a restart that reloaded it from the database would.
            Map<String, ItemSimState> copy = new HashMap<>(state);
            Schedule sc = sched.copy();
            BroadcastState bc = bs.copy();
            MarketSimulator.TickInput in = input(REAL_ON, market, seed, b, live, copy, new ArrayList<>(events), sc, bc,
                    live ? IMPULSES : List.of()).withPrevious(prev);
            MarketSimulator.TickResult r = MarketSimulator.tick(in);
            singles.add(r);
            state = copy;
            sched = sc;
            bs = bc;
            events = r.events();
            prev = r.multipliers();
            jumped.addAll(r.jumped());
        }
        MarketSimulator.TickResult last = singles.get(singles.size() - 1);
        assertEquals(state, stateA, "drift and the per-item rows");
        assertEquals(sched, schedA);
        assertEquals(bs, bsA);
        assertEquals(events, all.events());
        assertEquals(last.multipliers(), all.multipliers());
        assertEquals(last.status(), all.status());
        assertEquals(last.broadcast(), all.broadcast());
        assertEquals(writes(singles), writes(List.of(all)), "the same rows to insert and update");
        assertEquals(inserted(singles), inserted(List.of(all)));
        assertEquals(jumped, all.jumped());
        // The run did something worth comparing: rows stopped and ended during the replays, and
        // at the live boundary the REAL rows, a news flash and its broadcast.
        assertTrue(all.changed().stream().anyMatch(e -> MarketSimulator.STOP_SOLD_OUT.equals(e.stopReason())));
        assertTrue(all.changed().stream().anyMatch(e -> MarketSimulator.STOP_REMOVED.equals(e.stopReason())));
        assertEquals(2, all.started().stream().filter(e -> e.kind() == EventKind.REAL).count());
        assertTrue(all.started().stream().anyMatch(e -> e.kind() == EventKind.SEASON));
        assertTrue(all.broadcast().isPresent(), "the live boundary announces");
    }

    @Test
    void replayedTicksNeverStartEventsApplyImpulsesOrBroadcast() {
        long now = B0 + 576 * T;
        Map<String, ItemSimState> state = freshState();
        Schedule sched = new Schedule(B0, B0, B0, Schedule.NO_DAY, 0, null);
        Schedule before = sched.copy();
        BroadcastState bs = new BroadcastState();
        MarketSimulator.TickResult r = MarketSimulator.run(
                input(5, now, false, state, midStory(), sched, bs, IMPULSES), B0, now);
        assertEquals(576, r.ticks());
        assertFalse(r.live());
        for (MarketEvent e : r.started()) {
            assertEquals(EventKind.SEASON, e.kind(), "only calendar rows in a replay: " + e);
        }
        assertEquals(1, r.started().size(), "Harvest Time, once");
        assertTrue(r.broadcast().isEmpty());
        assertEquals(before, sched, "the planner never ran");
        assertEquals(new BroadcastState(), bs);
        for (MarketEvent e : r.events()) {
            if (e.id() != 1) {
                assertNull(e.announcedAt(), "nothing announced on a replay: " + e);
            }
        }
        // Drift still moved.
        assertNotEquals(freshState().get("iron_ingot").drift(), state.get("iron_ingot").drift());
    }

    @Test
    void theSameSeedReplaysTheSameMarketAndAnotherSeedDoesNot() {
        List<Map<String, Double>> a = liveRun(11, 7 * 288);
        List<Map<String, Double>> b = liveRun(11, 7 * 288);
        List<Map<String, Double>> c = liveRun(12, 7 * 288);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    private static List<Map<String, Double>> liveRun(long seed, int ticks) {
        Map<String, ItemSimState> state = new HashMap<>();
        Schedule sched = Schedule.firstEnable(B0, new SimRandom(seed));
        BroadcastState bs = new BroadcastState();
        List<MarketEvent> events = List.of();
        List<Map<String, Double>> out = new ArrayList<>();
        for (int k = 1; k <= ticks; k++) {
            long b = B0 + k * T;
            MarketSimulator.TickResult r = MarketSimulator.tick(input(seed, b, true, state, events, sched, bs, List.of())
                    .withOnline(MarketSimulatorSoakTest.online(b)));
            events = r.events();
            out.add(r.multipliers());
        }
        return out;
    }

    @Test
    void aCopyOfTheStateTakenMidRunContinuesExactly() {
        long seed = 21;
        int total = 3 * 288;
        int cut = 97;
        Map<String, ItemSimState> state = new HashMap<>();
        Schedule sched = Schedule.firstEnable(B0, new SimRandom(seed));
        BroadcastState bs = new BroadcastState();
        List<MarketEvent> events = List.of();
        Map<String, Double> prev = null;
        Map<String, ItemSimState> savedState = null;
        Schedule savedSched = null;
        BroadcastState savedBs = null;
        List<MarketEvent> savedEvents = null;
        Map<String, Double> savedPrev = null;
        List<MarketSimulator.TickResult> straight = new ArrayList<>();
        for (int k = 1; k <= total; k++) {
            long b = B0 + k * T;
            MarketSimulator.TickResult r = MarketSimulator.tick(input(seed, b, true, state, events, sched, bs, List.of())
                    .withOnline(MarketSimulatorSoakTest.online(b)).withPrevious(prev));
            events = r.events();
            prev = r.multipliers();
            straight.add(r);
            if (k == cut) {
                savedState = new HashMap<>(state);
                savedSched = sched.copy();
                savedBs = bs.copy();
                savedEvents = List.copyOf(events);
                savedPrev = prev;
            }
        }
        for (int k = cut + 1; k <= total; k++) {
            long b = B0 + k * T;
            MarketSimulator.TickResult r = MarketSimulator.tick(input(seed, b, true, savedState, savedEvents, savedSched,
                    savedBs, List.of()).withOnline(MarketSimulatorSoakTest.online(b)).withPrevious(savedPrev));
            savedEvents = r.events();
            savedPrev = r.multipliers();
            MarketSimulator.TickResult s = straight.get(k - 1);
            assertEquals(s.multipliers(), r.multipliers(), "tick " + k);
            assertEquals(s.started(), r.started(), "tick " + k);
            assertEquals(s.changed(), r.changed(), "tick " + k);
            assertEquals(s.broadcast(), r.broadcast(), "tick " + k);
        }
        assertEquals(state, savedState);
        assertEquals(sched, savedSched);
        assertEquals(bs, savedBs);
        assertTrue(straight.stream().flatMap(r -> r.started().stream()).anyMatch(e -> e.kind() != EventKind.SEASON),
                "events did start in the run");
    }

    @Test
    void boundariesAreEpochAlignedAndCappedAtMaxCatchup() {
        long t = 5 * MIN;
        MarketSimulator.Catchup none = MarketSimulator.boundaries(B0, B0 + t - 1, t, 48);
        assertTrue(none.isEmpty(), "nothing due until the next boundary");
        MarketSimulator.Catchup one = MarketSimulator.boundaries(B0 + 17, B0 + t + 123, t, 48);
        assertEquals(List.of(B0 + t), one.boundaries());
        MarketSimulator.Catchup day = MarketSimulator.boundaries(B0, B0 + 24 * H, t, 48);
        assertEquals(288, day.boundaries().size());
        assertEquals(0, day.skipped());
        for (long b : day.boundaries()) {
            assertEquals(0, b % t);
        }
        // Three days down: 48 h of replays plus the live boundary; the rest is skipped.
        MarketSimulator.Catchup long3 = MarketSimulator.boundaries(B0, B0 + 72 * H, t, 48);
        assertEquals(577, long3.boundaries().size());
        assertEquals(864 - 577, long3.skipped());
        assertEquals(B0 + 72 * H, long3.boundaries().get(576));
        MarketSimulator.Catchup noCatchup = MarketSimulator.boundaries(B0, B0 + 72 * H, t, 0);
        assertEquals(List.of(B0 + 72 * H), noCatchup.boundaries(), "0 hours: only the live boundary");
    }

    // ---- the tick's own rules -----------------------------------------------------------------

    private static MarketSimulator.TickResult once(long b, Map<String, MarketSimulator.Quote> market,
                                                   SortedMap<String, ItemParams> items, List<MarketEvent> events,
                                                   Map<String, ItemSimState> state, SimSettings s) {
        return MarketSimulator.tick(new MarketSimulator.TickInput(b, true, s, new SimRandom(3), items, market, state,
                events, new Schedule(b + H, b + H, b + H, Schedule.NO_DAY, 0, null), OnlineInfo.NOBODY,
                MarketSimulatorSoakTest.ZONE, 0.10, List.of(), new BroadcastState(0, 0, 0L, true), Set.of(), null));
    }

    @Test
    void aDealThatSellsOutStopsWithAnHourFade() {
        long b = B0 + T;
        MarketEvent deal = MarketEvent.story(EventKind.DEAL, Source.SIM, "iron_ingot", 0.1, B0 - 10 * H, 4 * H,
                40 * H, 10 * H).withId(9);
        Map<String, ItemSimState> state = freshState();
        MarketSimulator.TickResult r = once(b, market("iron_ingot", 0), items(), List.of(deal), state, S);
        MarketEvent stopped = r.changed().get(0);
        assertEquals(MarketSimulator.STOP_SOLD_OUT, stopped.stopReason());
        assertEquals(b, stopped.stoppedAt());
        assertEquals(b + H, stopped.endsAt());
        assertEquals(b + H, state.get("iron_ingot").featuredUntil(), "the cooldown runs from the actual end");
        assertEquals(40.0, r.status().get("iron_ingot").price(), "sold out: the ceiling");
    }

    @Test
    void eventsOnARemovedOrSimFalseItemEndAtOnce() {
        long b = B0 + T;
        MarketEvent hot = MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, B0 - 10 * H, 4 * H, 30 * H,
                10 * H).withId(5).withAnnouncedAt(B0 - 6 * H);
        SortedMap<String, ItemParams> items = new TreeMap<>(items());
        MarketItem oak = MarketSimulatorSoakTest.shippedCatalog().get(1);
        items.put("oak_log", ItemParams.of(oak, new ItemOverride(false, null, null, null), S));
        Map<String, ItemSimState> state = freshState();
        MarketSimulator.TickResult r = once(b, market(), items, List.of(hot), state, S);
        MarketEvent ended = r.changed().get(0);
        assertEquals(MarketSimulator.STOP_REMOVED, ended.stopReason());
        assertEquals(b, ended.endsAt(), "at once, no fade");
        assertEquals(1.0, r.multipliers().get("oak_log"));
        assertEquals(Badge.NONE, r.status().get("oak_log").badge());
        assertEquals(0.0, state.get("oak_log").drift(), "a sim:false item does not drift");
        // Removed from the catalog altogether: the same.
        items.remove("oak_log");
        MarketSimulator.TickResult gone = once(b, market(), items, List.of(hot), freshState(), S);
        assertEquals(MarketSimulator.STOP_REMOVED, gone.changed().get(0).stopReason());
        assertFalse(gone.multipliers().containsKey("oak_log"));
    }

    @Test
    void aWantedRowEndsOnceStockArrives() {
        long b = B0 + T;
        MarketEvent w = MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, B0 - H, B0 + 6 * 24 * H).withId(8);
        MarketSimulator.TickResult still = once(b, market(), items(), List.of(w), freshState(), S);
        assertTrue(still.changed().isEmpty());
        assertEquals(Badge.WANTED, still.status().get("diamond").badge());
        MarketSimulator.TickResult sold = once(b, market("diamond", 3), items(), List.of(w), freshState(), S);
        assertEquals(MarketSimulator.STOP_STOCKED, sold.changed().get(0).stopReason());
        assertEquals(Badge.NONE, sold.status().get("diamond").badge());
    }

    @Test
    void oneSeasonRowPerSeasonAndYear() {
        long b = B0 + T;
        MarketSimulator.TickResult first = once(b, market(), items(), List.of(), freshState(), S);
        assertEquals(1, first.started().size());
        MarketEvent row = first.started().get(0);
        assertEquals(EventKind.SEASON, row.kind());
        assertEquals("harvest_time:2026", row.tag());
        assertEquals(-4.0, row.pct());
        assertEquals("Harvest time! The farms are full of wheat.", row.headline());
        assertEquals(first.seasonStarts().get(0).endsAt(MarketSimulatorSoakTest.ZONE), row.endsAt());
        assertEquals(-0.04, first.breakdowns().get("wheat").predictable(), 1e-12, "wheat -4% on ship day");
        MarketSimulator.TickResult second = once(b + T, market(), items(), first.events(), freshState(), S);
        assertTrue(second.started().isEmpty(), "idempotent: the tag is already there");
        assertEquals(1, second.seasons().size());
    }

    @Test
    void queuedRealImpulsesApplyOnceAtTheNextLiveTickWithOneHeadline() {
        SimSettings s = REAL_ON;
        long b = B0 + T;
        Map<String, ItemSimState> state = freshState();
        Schedule sched = new Schedule(b + H, b + H, b + H, Schedule.NO_DAY, 0, null);
        MarketSimulator.TickInput in = new MarketSimulator.TickInput(b, true, s, new SimRandom(3), items(), market(),
                state, List.of(), sched, OnlineInfo.NOBODY, MarketSimulatorSoakTest.ZONE, 0.10, IMPULSES,
                new BroadcastState(0, 0, 0L, true), Set.of(), null);
        MarketSimulator.TickResult res = MarketSimulator.tick(in);
        List<MarketEvent> reals = res.started().stream().filter(e -> e.kind() == EventKind.REAL).toList();
        assertEquals(2, reals.size());
        MarketEvent wheat = reals.stream().filter(e -> e.itemId().equals("wheat")).findFirst().orElseThrow();
        MarketEvent gold = reals.stream().filter(e -> e.itemId().equals("gold_ingot")).findFirst().orElseThrow();
        assertEquals("wheat:zw.f:20724", wheat.tag());
        assertEquals(b, wheat.announceDueAt(), "the biggest real move of at least 2% is the headline");
        assertNull(gold.announceDueAt(), "1.5% is not announced");
        assertEquals(-0.04, wheat.strength(), 1e-12);
        assertEquals("In the real world, wheat prices went down today!", wheat.headline());
        assertTrue(wheat.pct() < 0.0 && wheat.priceAfter() < wheat.priceBefore());
        // Season -4% and real -4% together are held to the predictable cap.
        assertEquals(-0.045, res.breakdowns().get("wheat").predictable(), 1e-12);

        // Queued again (a retry after a failed write): nothing new.
        MarketSimulator.TickResult again = MarketSimulator.tick(in.at(b + T, true).withEvents(res.events()));
        assertTrue(again.started().stream().noneMatch(e -> e.kind() == EventKind.REAL));
        // With real_world off the queue is ignored.
        MarketSimulator.TickResult off = MarketSimulator.tick(new MarketSimulator.TickInput(b, true, S, new SimRandom(3),
                items(), market(), freshState(), List.of(), sched.copy(), OnlineInfo.NOBODY,
                MarketSimulatorSoakTest.ZONE, 0.10, IMPULSES, new BroadcastState(0, 0, 0L, true), Set.of(), null));
        assertTrue(off.started().stream().noneMatch(e -> e.kind() == EventKind.REAL));
    }

    @Test
    void theSimSwitchedOffIsExactlyNeutral() {
        Map<String, ItemSimState> state = freshState();
        Map<String, ItemSimState> before = new HashMap<>(state);
        MarketSimulator.TickResult r = once(B0 + T, market(), items(), midStory(), state, S.withEnabled(false));
        for (Map.Entry<String, Double> e : r.multipliers().entrySet()) {
            assertEquals(1.0, e.getValue(), e.getKey());
            ItemStatus st = r.status().get(e.getKey());
            assertEquals(Badge.NONE, st.badge());
            assertEquals(st.usual(), st.price(), "the price is exactly the usual price");
        }
        assertTrue(r.started().isEmpty());
        assertTrue(r.changed().isEmpty());
        assertEquals(before, state, "nothing moves while off");
    }

    @Test
    void aMoveOfMoreThanOnePercentIsAJump() {
        Map<String, Double> ones = new HashMap<>();
        for (String id : items().keySet()) {
            ones.put(id, 1.0);
        }
        Map<String, ItemSimState> state = new HashMap<>();
        for (String id : items().keySet()) {
            state.put(id, ItemSimState.fresh(id));
        }
        long b = B0 + T;
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.ADMIN, "wheat", 0.2, b, 6 * H, 30 * H);
        SimSettings noSeasons = MarketSimulatorSoakTest.driftOnly();
        MarketSimulator.TickResult r = MarketSimulator.tick(new MarketSimulator.TickInput(b, true, noSeasons,
                new SimRandom(3), items(), market(), state, List.of(up), new Schedule(), OnlineInfo.NOBODY,
                MarketSimulatorSoakTest.ZONE, 0.10, List.of(), new BroadcastState(0, 0, 0L, true), Set.of(), ones));
        assertEquals(Set.of("wheat"), r.jumped(), "a drift step is far below 1%");
        assertNotNull(r.status().get("wheat").event());
    }

    @Test
    void theIntroGoesOutOnceWhenSomeoneIsOn() {
        long evening = java.time.LocalDateTime.of(2026, 9, 28, 19, 30).atZone(MarketSimulatorSoakTest.ZONE)
                .toInstant().toEpochMilli();
        long b = Math.floorDiv(evening, T) * T;
        BroadcastState bs = new BroadcastState();
        Schedule sched = new Schedule(b + 9 * H, b + 9 * H, b + 9 * H, Schedule.NO_DAY, 0, null);
        MarketSimulator.TickInput in = new MarketSimulator.TickInput(b, true, S, new SimRandom(3), items(), market(),
                freshState(), List.of(), sched, ON, MarketSimulatorSoakTest.ZONE, 0.10, List.of(), bs, Set.of(), null);
        MarketSimulator.TickResult r = MarketSimulator.tick(in);
        assertEquals(AnnounceGate.Type.INTRO, r.broadcast().orElseThrow().type());
        assertTrue(bs.introDone());
        assertEquals(1, bs.countOn(java.time.LocalDate.of(2026, 9, 28).toEpochDay()));
        MarketSimulator.TickResult next = MarketSimulator.tick(in.at(b + 25 * MIN, true).withEvents(r.events()));
        assertTrue(next.broadcast().isEmpty() || next.broadcast().get().type() != AnnounceGate.Type.INTRO);
    }

    // ---- helpers for the service --------------------------------------------------------------

    @Test
    void endStopsOrEndsLiveEventsAndMovesTheCooldown() {
        long ts = B0;
        Map<String, ItemSimState> state = freshState();
        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026", B0 - H,
                B0 + 900 * H);
        List<MarketEvent> events = new ArrayList<>(midStory());
        events.add(season);
        List<MarketEvent> faded = MarketSimulator.end(events, "oak_log", ts, MarketSimulator.STOP_STOPPED, true, state);
        assertEquals(ts + H, faded.get(0).endsAt(), "sim stop: a 1 h fade");
        assertEquals(ts + H, state.get("oak_log").featuredUntil());
        assertEquals(events.get(1), faded.get(1), "other items untouched");

        List<MarketEvent> reset = MarketSimulator.end(events, null, ts, MarketSimulator.STOP_RESET, false, state);
        for (int i = 0; i < 4; i++) {
            assertEquals(ts, reset.get(i).endsAt(), "ended at once: " + reset.get(i).kind());
        }
        assertEquals(season, reset.get(4), "SEASON rows are never ended");
        assertTrue(MarketSimulator.busy(events, "wheat", ts));
        assertFalse(MarketSimulator.busy(reset, "wheat", ts));
        // The event limits' test: a DEAL still ramping counts, a HOT does not count as a DEAL.
        assertTrue(MarketSimulator.activeOf(events, "iron_ingot", ts, EventKind.DEAL, EventKind.DOWN));
        assertFalse(MarketSimulator.activeOf(events, "oak_log", ts, EventKind.DEAL, EventKind.DOWN));
        assertTrue(MarketSimulator.activeOf(events, "oak_log", ts, EventKind.HOT, EventKind.UP));
        assertFalse(MarketSimulator.activeOf(reset, "iron_ingot", ts, EventKind.DEAL, EventKind.DOWN));
    }

    @Test
    void enableResetsTheScheduleAndTheDrift() {
        Map<String, ItemSimState> state = freshState();
        Schedule sched = new Schedule();
        MarketSimulator.enable(B0, new SimRandom(4), sched, state);
        assertTrue(sched.nextHotAt() >= B0 + 10 * MIN && sched.nextHotAt() <= B0 + 40 * MIN);
        assertTrue(sched.nextDealAt() >= B0 + 60 * MIN && sched.nextDealAt() <= B0 + 180 * MIN);
        assertTrue(sched.nextNewsAt() >= B0 + 20 * MIN && sched.nextNewsAt() <= B0 + 60 * MIN);
        for (ItemSimState st : state.values()) {
            assertEquals(0.0, st.drift());
        }
        // sim reset <item>: that item's drift only.
        Map<String, ItemSimState> some = freshState();
        MarketSimulator.zeroDrift(some, "wheat", B0);
        assertEquals(0.0, some.get("wheat").drift());
        assertEquals(freshState().get("oak_log").drift(), some.get("oak_log").drift());
        // The planner's view of an item, for admin actions.
        ItemParams oak = items().get("oak_log");
        EventPlanner.Candidate c = MarketSimulator.candidate(oak, market().get("oak_log"), state.get("oak_log"), 1.02,
                false, true, S);
        assertEquals(2.0, c.weight(), "popular: x popular_weight");
        assertEquals(4000, c.stock());
        assertEquals(1.02, c.m0());
    }
}
