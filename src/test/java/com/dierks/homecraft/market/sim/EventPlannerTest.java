package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner's rules (spec §5.3, §5.4).
 *
 * <p>Pins: slots (6 items give 1 HOT + 1 DEAL, 263 give 3 + 3) and the combined cap; the 7-day
 * rest after a HOT/DEAL (a DEAL then a HOT within 7 days is refused); busy items and the 24 h
 * news-to-feature gap; an item at its ceiling or with no stock is never HOT or UP; under 3% of
 * full stock (or 16 units) never DEAL, under 5% never DOWN; WANTED only at stock 0 and at most
 * once a week per item; no flash outside 07:00-21:00 and at most 2 a local day; a due flash is
 * held while nobody is ready and fires silently after 14 h; its join delay is fixed per
 * scheduled flash; every flash moves at least 10%; every strength stays inside its code limit
 * and 90% of the headroom; at most one start per tick (news before HOT before DEAL); the same
 * inputs plan the same thing.
 */
class EventPlannerTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final long H = SimMath.HOUR_MS;
    private static final long D = SimMath.DAY_MS;
    private static final long MIN = SimMath.MINUTE_MS;
    /** Monday 2026-09-28, 19:30 local: inside news hours. */
    private static final long EVENING = at(LocalDate.of(2026, 9, 28), 19, 30);
    private static final OnlineInfo READY = new OnlineInfo(1, 60 * MIN);

    private static long at(LocalDate day, int h, int m) {
        return LocalDateTime.of(day, LocalTime.of(h, m)).atZone(ZONE).toInstant().toEpochMilli();
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static EventPlanner.Candidate iron(long stock, double m0) {
        return new EventPlanner.Candidate("iron_ingot", 1.0, 22.49, 4.0, 40.0, stock, 2048, m0, false, 0, 0, 0);
    }

    private static EventPlanner.Candidate oak() {
        return new EventPlanner.Candidate("oak_log", 1.0, 4.47, 1.0, 20.0, 4000, 8000, 1.0, false, 0, 0, 0);
    }

    private static EventPlanner.Candidate gold() {
        return new EventPlanner.Candidate("gold_ingot", 1.0, 150.0, 20.0, 150.0, 0, 1024, 1.0, false, 0, 0, 0);
    }

    private static EventPlanner.Candidate with(EventPlanner.Candidate c, boolean busy, long featuredUntil,
                                               long lastNewsAt, long lastWantedAt) {
        return new EventPlanner.Candidate(c.id(), c.weight(), c.base(), c.floor(), c.ceiling(), c.stock(),
                c.fullStock(), c.m0(), busy, featuredUntil, lastNewsAt, lastWantedAt);
    }

    private static SimSettings.News news(boolean enabled, SimSettings.Hours hours, boolean wait, double wantedShare) {
        SimSettings.News n = SimSettings.News.defaults();
        return new SimSettings.News(enabled, n.percent(), n.minPercent(), n.halfLifeHours(), n.lastsHours(),
                n.gapHours(), n.extraHours(), n.maxGapHours(), n.maxPerDay(), hours, wait, n.joinDelayMinutes(),
                n.maxHoldHours(), n.itemCooldownHours(), n.minStockPercent(), n.buyLimitShare(), wantedShare,
                n.wantedEveryDays());
    }

    private static SimSettings settings(boolean hot, boolean deal, SimSettings.News news, int slotsPerItems) {
        SimSettings d = SimSettings.defaults();
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), d.maxUpPercent(), d.maxDownPercent(),
                d.keepDays(), d.drift(), hot ? d.hot() : MarketSimulatorSoakTest.storyOff(d.hot()),
                deal ? d.deal() : MarketSimulatorSoakTest.storyOff(d.deal()), slotsPerItems, d.cooldownDays(),
                d.popularWeight(), news, d.announce(), d.headlines(), d.samePlural(), d.seasons(), d.real());
    }

    private static final SimSettings.Hours ALL_DAY = SimSettings.Hours.parse("00:00-00:00");

    /** Only HOT can start. */
    private static final SimSettings HOT_ONLY = settings(true, false, news(false, SimSettings.Hours.DEFAULT, true, 0.15), 40);
    /** Only DEAL can start. */
    private static final SimSettings DEAL_ONLY = settings(false, true, news(false, SimSettings.Hours.DEFAULT, true, 0.15), 40);
    /** Only news can start (no WANTED). */
    private static final SimSettings NEWS_ONLY = settings(false, false, news(true, SimSettings.Hours.DEFAULT, true, 0.0), 40);

    private static Schedule due(long b) {
        return new Schedule(b, b, b, Schedule.NO_DAY, 0, null);
    }

    private static EventPlanner.PlanInput input(long b, SimSettings s, List<EventPlanner.Candidate> c, Schedule sched,
                                                int activeHot, int activeDeal, int simItems, OnlineInfo online) {
        return new EventPlanner.PlanInput(b, ZONE, s, c, sched, activeHot, activeDeal, simItems, online, 0L, 0, false);
    }

    private static EventPlanner.Planned plan(long seed, long b, SimSettings s, List<EventPlanner.Candidate> c) {
        return EventPlanner.plan(input(b, s, c, due(b), 0, 0, 6, READY), new SimRandom(seed));
    }

    /** How often each item got each kind over {@code n} seeds. */
    private static Map<String, Integer> tally(int n, long b, SimSettings s, List<EventPlanner.Candidate> c) {
        Map<String, Integer> out = new HashMap<>();
        for (long seed = 1; seed <= n; seed++) {
            MarketEvent e = plan(seed, b, s, c).event();
            if (e != null) {
                out.merge(e.itemId() + ":" + e.kind(), 1, Integer::sum);
            }
        }
        return out;
    }

    // ---- slots ------------------------------------------------------------------------------

    @Test
    void slotsGrowWithTheCatalog() {
        assertEquals(1, EventPlanner.slots(6, 40));
        assertEquals(3, EventPlanner.slots(263, 40));
        assertEquals(1, EventPlanner.slots(39, 40));
        assertEquals(2, EventPlanner.slots(40, 40));
        assertEquals(1, EventPlanner.slots(0, 40));
        assertEquals(2, EventPlanner.combinedCap(6));
        assertEquals(87, EventPlanner.combinedCap(263));
        assertEquals(2, EventPlanner.combinedCap(1));
    }

    @Test
    void aFullSlotOrTheCombinedCapBlocksAStart() {
        List<EventPlanner.Candidate> c = List.of(iron(512, 1.0), oak());
        // 6 items: one HOT slot, already taken.
        assertNull(EventPlanner.plan(input(EVENING, HOT_ONLY, c, due(EVENING), 1, 0, 6, READY), new SimRandom(1)).event());
        // 263 items: three slots, two taken, one free.
        assertNotNull(EventPlanner.plan(input(EVENING, HOT_ONLY, c, due(EVENING), 2, 0, 263, READY), new SimRandom(1)).event());
        assertNull(EventPlanner.plan(input(EVENING, HOT_ONLY, c, due(EVENING), 3, 0, 263, READY), new SimRandom(1)).event());
        // 4 items at 1 per slot: 3 slots each, but HOT + DEAL together at most max(2, 4/3) = 2.
        SimSettings tight = settings(true, true, news(false, SimSettings.Hours.DEFAULT, true, 0.0), 1);
        assertNull(EventPlanner.plan(input(EVENING, tight, c, due(EVENING), 1, 1, 4, READY), new SimRandom(1)).event());
        assertNotNull(EventPlanner.plan(input(EVENING, tight, c, due(EVENING), 1, 0, 4, READY), new SimRandom(1)).event());
    }

    @Test
    void theNextStoryIsScheduledAfterThisOneAndItsGap() {
        Schedule sched = due(EVENING);
        EventPlanner.Planned p = EventPlanner.plan(input(EVENING, HOT_ONLY, List.of(oak()), sched, 0, 0, 6, READY),
                new SimRandom(3));
        MarketEvent e = p.event();
        assertEquals(EventKind.HOT, e.kind());
        assertEquals(EVENING, e.startedAt());
        assertEquals(4 * H, e.rampMs());
        assertEquals(10 * H, e.fadeMs());
        assertTrue(e.holdMs() >= 30 * H && e.holdMs() <= 54 * H, "hold " + e.holdMs() / (double) H);
        assertEquals(EVENING + 4 * H, e.announceDueAt(), "announced at full strength");
        long gap = sched.nextHotAt() - e.endsAt();
        assertTrue(gap >= 48 * H && gap <= 120 * H, "gap " + gap / (double) H);
        assertFalse(p.live(), "HOT/DEAL are never announced as they start");

        // Nothing eligible: look again in an hour.
        Schedule empty = due(EVENING);
        EventPlanner.plan(input(EVENING, HOT_ONLY, List.of(gold()), empty, 0, 0, 6, READY), new SimRandom(3));
        assertEquals(EVENING + H, empty.nextHotAt());
        // Not due yet: nothing moves.
        Schedule later = new Schedule(EVENING + 1, EVENING + 1, EVENING + 1, Schedule.NO_DAY, 0, null);
        assertNull(EventPlanner.plan(input(EVENING, HOT_ONLY, List.of(oak()), later, 0, 0, 6, READY), new SimRandom(3)).event());
        assertEquals(EVENING + 1, later.nextHotAt());
    }

    // ---- HOT / DEAL eligibility -------------------------------------------------------------

    @Test
    void anItemRestsSevenDaysAfterAHotOrDeal() {
        // A DEAL on oak ended 6 days ago: no HOT on it yet. Seven days: fine.
        EventPlanner.Candidate resting = with(oak(), false, EVENING - 6 * D, 0, 0);
        assertNull(plan(1, EVENING, HOT_ONLY, List.of(resting)).event());
        EventPlanner.Candidate rested = with(oak(), false, EVENING - 7 * D, 0, 0);
        assertEquals(EventKind.HOT, plan(1, EVENING, HOT_ONLY, List.of(rested)).event().kind());
        assertEquals(EventKind.DEAL, plan(1, EVENING, DEAL_ONLY, List.of(rested)).event().kind());
        assertNull(plan(1, EVENING, DEAL_ONLY, List.of(resting)).event());
    }

    @Test
    void busyItemsAndRecentNewsAreLeftAlone() {
        EventPlanner.Candidate busy = with(oak(), true, 0, 0, 0);
        assertNull(plan(1, EVENING, HOT_ONLY, List.of(busy)).event());
        assertNull(plan(1, EVENING, DEAL_ONLY, List.of(busy)).event());
        assertNull(plan(1, EVENING, NEWS_ONLY, List.of(busy)).event(), "no news on a featured item");

        EventPlanner.Candidate news23h = with(oak(), false, 0, EVENING - 23 * H, 0);
        assertNull(plan(1, EVENING, HOT_ONLY, List.of(news23h)).event(), "no feature within 24 h of news");
        EventPlanner.Candidate news24h = with(oak(), false, 0, EVENING - 24 * H, 0);
        assertNotNull(plan(1, EVENING, HOT_ONLY, List.of(news24h)).event());

        EventPlanner.Candidate news71h = with(oak(), false, 0, EVENING - 71 * H, 0);
        assertNull(plan(1, EVENING, NEWS_ONLY, List.of(news71h)).event(), "72 h news cooldown per item");
        EventPlanner.Candidate news72h = with(oak(), false, 0, EVENING - 72 * H, 0);
        assertNotNull(plan(1, EVENING, NEWS_ONLY, List.of(news72h)).event());
    }

    @Test
    void anItemAtItsCeilingOrSoldOutIsNeverHotOrUp() {
        // Iron at its ceiling with stock left (M0 = 1): no room to go up.
        EventPlanner.Candidate atCeiling = new EventPlanner.Candidate("iron_ingot", 1.0, 40.0, 4.0, 40.0, 200, 2048,
                1.0, false, 0, 0, 0);
        List<EventPlanner.Candidate> c = List.of(atCeiling, gold(), oak());
        Map<String, Integer> hot = tally(2000, EVENING, HOT_ONLY, c);
        assertEquals(null, hot.get("iron_ingot:HOT"));
        assertEquals(null, hot.get("gold_ingot:HOT"));
        assertEquals(2000, hot.get("oak_log:HOT"));
        Map<String, Integer> news = tally(2000, EVENING, NEWS_ONLY, c);
        assertNull(news.get("iron_ingot:UP"));
        assertNull(news.get("gold_ingot:UP"));
        assertNull(news.get("gold_ingot:DOWN"));
        assertTrue(news.getOrDefault("iron_ingot:DOWN", 0) > 0, "it can still go down");
        assertTrue(news.getOrDefault("oak_log:UP", 0) > 0);
    }

    @Test
    void lowStockNeverGetsADealOrADown() {
        // 3% of 2048 is 61.44: 61 units is too few for a DEAL, 62 is enough.
        assertNull(plan(1, EVENING, DEAL_ONLY, List.of(iron(61, 1.0))).event());
        assertEquals(EventKind.DEAL, plan(1, EVENING, DEAL_ONLY, List.of(iron(62, 1.0))).event().kind());
        // At least 16 units whatever the percentage says.
        EventPlanner.Candidate small = new EventPlanner.Candidate("tiny", 1.0, 5.0, 1.0, 10.0, 15, 100, 1.0, false, 0, 0, 0);
        assertNull(plan(1, EVENING, DEAL_ONLY, List.of(small)).event());

        // 5% of 2048 is 102.4: at 102 every flash on iron goes UP, never DOWN.
        Map<String, Integer> news = tally(2000, EVENING, NEWS_ONLY, List.of(iron(102, 1.0)));
        assertNull(news.get("iron_ingot:DOWN"));
        assertTrue(news.getOrDefault("iron_ingot:UP", 0) > 0);
        Map<String, Integer> enough = tally(2000, EVENING, NEWS_ONLY, List.of(iron(103, 1.0)));
        assertTrue(enough.getOrDefault("iron_ingot:DOWN", 0) > 0);
    }

    // ---- news ---------------------------------------------------------------------------------

    @Test
    void wantedIsOnlyForSoldOutItemsAndAtMostWeekly() {
        SimSettings wantedAlways = settings(false, false, news(true, SimSettings.Hours.DEFAULT, true, 1.0), 40);
        MarketEvent w = plan(1, EVENING, wantedAlways, List.of(gold(), iron(512, 1.0))).event();
        assertEquals(EventKind.WANTED, w.kind());
        assertEquals("gold_ingot", w.itemId());
        assertEquals(0.0, w.strength(), "WANTED moves no price");
        assertEquals(150.0, w.priceBefore());

        EventPlanner.Candidate askedLastWeek = with(gold(), false, 0, 0, EVENING - 6 * D);
        MarketEvent fallback = plan(1, EVENING, wantedAlways, List.of(askedLastWeek, iron(512, 1.0))).event();
        assertTrue(fallback.kind().news(), "no WANTED within 7 days: an UP/DOWN instead");
        EventPlanner.Candidate askedAWeekAgo = with(gold(), false, 0, 0, EVENING - 7 * D);
        assertEquals(EventKind.WANTED, plan(1, EVENING, wantedAlways, List.of(askedAWeekAgo)).event().kind());

        // Never WANTED for an item with stock, and never at share 0.
        Map<String, Integer> t = tally(500, EVENING, wantedAlways, List.of(iron(512, 1.0)));
        assertTrue(t.keySet().stream().noneMatch(k -> k.endsWith("WANTED")));
        Map<String, Integer> none = tally(500, EVENING, NEWS_ONLY, List.of(gold(), iron(512, 1.0)));
        assertTrue(none.keySet().stream().noneMatch(k -> k.endsWith("WANTED")));
        // The shipped 15%: about one flash in seven says WANTED when a sold-out item is eligible.
        SimSettings shipped = settings(false, false, news(true, SimSettings.Hours.DEFAULT, true, 0.15), 40);
        int wanted = tally(4000, EVENING, shipped, List.of(gold(), iron(512, 1.0))).getOrDefault("gold_ingot:WANTED", 0);
        assertEquals(0.15, wanted / 4000.0, 0.02);
    }

    @Test
    void noFlashOutsideNewsHours() {
        LocalDate day = LocalDate.of(2026, 9, 28);
        for (int minute = 0; minute < 24 * 60; minute += 5) {
            long b = at(day, minute / 60, minute % 60);
            LocalTime lt = LocalTime.of(minute / 60, minute % 60);
            Schedule sched = due(b);
            EventPlanner.Planned p = EventPlanner.plan(input(b, NEWS_ONLY, List.of(oak()), sched, 0, 0, 6, READY),
                    new SimRandom(minute));
            if (lt.isBefore(LocalTime.of(7, 0)) || !lt.isBefore(LocalTime.of(21, 0))) {
                assertNull(p.event(), "no flash at " + lt);
                LocalDate opens = lt.isBefore(LocalTime.of(7, 0)) ? day : day.plusDays(1);
                long open = at(opens, 7, 0);
                assertTrue(sched.nextNewsAt() >= open && sched.nextNewsAt() <= open + 90 * MIN,
                        "rescheduled to the next opening at " + lt);
            } else {
                assertNotNull(p.event(), "a due flash fires at " + lt);
            }
        }
    }

    @Test
    void atMostTwoFlashesALocalDay() {
        long today = LocalDate.of(2026, 9, 28).toEpochDay();
        Schedule two = new Schedule(EVENING, EVENING, EVENING, today, 2, null);
        assertNull(EventPlanner.plan(input(EVENING, NEWS_ONLY, List.of(oak()), two, 0, 0, 6, READY), new SimRandom(1)).event());
        long tomorrow = at(LocalDate.of(2026, 9, 29), 7, 0);
        assertTrue(two.nextNewsAt() >= tomorrow && two.nextNewsAt() <= tomorrow + 90 * MIN);

        Schedule one = new Schedule(EVENING, EVENING, EVENING, today, 1, null);
        assertNotNull(EventPlanner.plan(input(EVENING, NEWS_ONLY, List.of(oak()), one, 0, 0, 6, READY), new SimRandom(1)).event());
        assertEquals(2, one.newsOn(today));
        long gap = one.nextNewsAt() - EVENING;
        assertTrue(gap >= 10 * H && gap <= 48 * H, "next flash 10-48 h later: " + gap / (double) H);

        // Yesterday's count does not carry over.
        Schedule yesterday = new Schedule(EVENING, EVENING, EVENING, today - 1, 2, null);
        assertNotNull(EventPlanner.plan(input(EVENING, NEWS_ONLY, List.of(oak()), yesterday, 0, 0, 6, READY),
                new SimRandom(1)).event());
    }

    @Test
    void aDueFlashIsHeldForAPlayerThenFiresSilentlyAfter14Hours() {
        SimSettings allDay = settings(false, false, news(true, ALL_DAY, true, 0.0), 40);
        long dueAt = at(LocalDate.of(2026, 9, 28), 2, 0);
        Schedule sched = due(dueAt);
        for (long b = dueAt; b < dueAt + 14 * H; b += 5 * MIN) {
            EventPlanner.Planned p = EventPlanner.plan(input(b, allDay, List.of(oak()), sched, 0, 0, 6, OnlineInfo.NOBODY),
                    new SimRandom(9));
            assertNull(p.event(), "held at +" + (b - dueAt) / MIN + " min");
            assertTrue(p.newsHeld());
            assertEquals(dueAt, sched.nextNewsAt(), "holding does not reschedule");
        }
        EventPlanner.Planned fired = EventPlanner.plan(input(dueAt + 14 * H, allDay, List.of(oak()), sched, 0, 0, 6,
                OnlineInfo.NOBODY), new SimRandom(9));
        assertNotNull(fired.event());
        assertFalse(fired.live(), "fires silently: nobody to tell");

        // wait_for_players: false fires at once, silently.
        SimSettings noWait = settings(false, false, news(true, ALL_DAY, false, 0.0), 40);
        EventPlanner.Planned now = EventPlanner.plan(input(dueAt, noWait, List.of(oak()), due(dueAt), 0, 0, 6,
                OnlineInfo.NOBODY), new SimRandom(9));
        assertNotNull(now.event());
        assertFalse(now.live());

        // A ready player with the gate open: live.
        EventPlanner.Planned live = EventPlanner.plan(input(dueAt, allDay, List.of(oak()), due(dueAt), 0, 0, 6, READY),
                new SimRandom(9));
        assertTrue(live.live());
        // The gate closed (a broadcast 10 min ago, or the intro still to come): held.
        EventPlanner.PlanInput recent = new EventPlanner.PlanInput(dueAt, ZONE, allDay, List.of(oak()), due(dueAt), 0, 0,
                6, READY, dueAt - 10 * MIN, 1, false);
        assertTrue(EventPlanner.plan(recent, new SimRandom(9)).newsHeld());
        EventPlanner.PlanInput intro = new EventPlanner.PlanInput(dueAt, ZONE, allDay, List.of(oak()), due(dueAt), 0, 0,
                6, READY, 0L, 0, true);
        assertTrue(EventPlanner.plan(intro, new SimRandom(9)).newsHeld());
    }

    @Test
    void theJoinDelayIsFixedPerScheduledFlash() {
        SimSettings allDay = settings(false, false, news(true, ALL_DAY, true, 0.0), 40);
        SimRandom rng = new SimRandom(5);
        long next = at(LocalDate.of(2026, 9, 28), 12, 0);
        long delay = EventPlanner.newsJoinDelayMs(allDay, rng, next);
        assertTrue(delay >= 2 * MIN && delay <= 8 * MIN, "delay " + delay);
        assertNotEquals(delay, EventPlanner.newsJoinDelayMs(allDay, rng, next + 10 * H), "a new flash rolls anew");
        // Whenever it is checked, the same flash needs the same session length.
        for (long b = next; b < next + 3 * H; b += 5 * MIN) {
            EventPlanner.Planned shortSession = EventPlanner.plan(input(b, allDay, List.of(oak()), due(next), 0, 0, 6,
                    new OnlineInfo(3, delay - 1)), rng);
            assertTrue(shortSession.newsHeld(), "one ms short at " + b);
            EventPlanner.Planned longEnough = EventPlanner.plan(input(b, allDay, List.of(oak()), due(next), 0, 0, 6,
                    new OnlineInfo(1, delay)), rng);
            assertTrue(longEnough.live());
            assertEquals(delay, longEnough.joinDelayMs());
        }
    }

    @Test
    void everyFlashMovesAtLeastTenPercentAndEveryStrengthStaysInItsLimits() {
        SimSettings s = SimSettings.defaults();
        List<EventPlanner.Candidate> c = new ArrayList<>(List.of(oak(), gold(), iron(512, 1.05),
                new EventPlanner.Candidate("wheat", 1.0, 3.46, 1.0, 12.0, 3000, 6000, 0.94, false, 0, 0, 0),
                new EventPlanner.Candidate("cobblestone", 2.0, 0.18, 0.10, 5.0, 17000, 20000, 1.1, false, 0, 0, 0)));
        int news = 0;
        int stories = 0;
        for (long seed = 1; seed <= 3000; seed++) {
            MarketEvent e = plan(seed, EVENING, s, c).event();
            if (e == null) {
                continue;
            }
            EventPlanner.Candidate k = c.stream().filter(x -> x.id().equals(e.itemId())).findFirst().orElseThrow();
            double room = e.strength() > 0 ? k.headroomUp(s) : k.headroomDown(s);
            if (e.kind().news()) {
                news++;
                assertTrue(Math.abs(e.strength()) >= 0.10 - 1e-12, "min move: " + e.strength());
                assertTrue(Math.abs(e.strength()) <= SimLimits.NEWS_MAX);
                assertTrue(Math.abs(e.strength()) <= 0.9 * room + 1e-12, "90% of headroom");
                assertEquals(e.priceAfter() / e.priceBefore() - 1, e.pct() / 100, 1e-12, "pct from the prices");
            } else if (e.kind().story()) {
                stories++;
                assertTrue(Math.abs(e.strength()) <= SimLimits.STORY_MAX);
                assertTrue(Math.abs(e.strength()) <= 0.9 * room + 1e-12);
            }
            assertTrue(e.priceAfter() >= k.floor() && e.priceAfter() <= k.ceiling());
        }
        assertTrue(news > 2000, "news comes first when due: " + news);
        assertEquals(0, stories, "news took the tick every time");

        // An item that cannot move 10% either way never gets a flash.
        EventPlanner.Candidate pinned = new EventPlanner.Candidate("glass", 1.0, 1.0, 0.95, 1.05, 500, 1000, 1.0,
                false, 0, 0, 0);
        assertTrue(tally(500, EVENING, NEWS_ONLY, List.of(pinned)).isEmpty());
    }

    @Test
    void flashesLeanBackTowardTheUsualPrice() {
        // M0 = 1.10: P(up) = clamp(0.5 - 0.5 x 0.10 / 0.25) = 0.30.
        Map<String, Integer> t = tally(4000, EVENING, NEWS_ONLY, List.of(iron(512, 1.10)));
        double up = t.getOrDefault("iron_ingot:UP", 0) / 4000.0;
        assertEquals(0.30, up, 0.03);
        Map<String, Integer> low = tally(4000, EVENING, NEWS_ONLY, List.of(iron(512, 0.90)));
        assertEquals(0.70, low.getOrDefault("iron_ingot:UP", 0) / 4000.0, 0.03);
    }

    // ---- one start, determinism -------------------------------------------------------------

    @Test
    void atMostOneStartPerTickNewsFirst() {
        SimSettings s = SimSettings.defaults();
        Schedule sched = due(EVENING);
        EventPlanner.Planned p = EventPlanner.plan(input(EVENING, s, List.of(oak(), iron(512, 1.0)), sched, 0, 0, 6,
                READY), new SimRandom(2));
        assertTrue(p.event().kind().news());
        assertEquals(EVENING, sched.nextHotAt(), "HOT stays due for the next tick");
        assertEquals(EVENING, sched.nextDealAt());

        // With the news not due, HOT goes first and DEAL waits.
        Schedule noNews = new Schedule(EVENING, EVENING, EVENING + H, Schedule.NO_DAY, 0, null);
        EventPlanner.Planned hot = EventPlanner.plan(input(EVENING, s, List.of(oak(), iron(512, 1.0)), noNews, 0, 0, 6,
                READY), new SimRandom(2));
        assertEquals(EventKind.HOT, hot.event().kind());
        assertEquals(EVENING, noNews.nextDealAt());
    }

    @Test
    void theSameInputsPlanTheSameThing() {
        List<EventPlanner.Candidate> c = List.of(oak(), gold(), iron(512, 1.02));
        for (long seed = 1; seed <= 200; seed++) {
            Schedule a = due(EVENING);
            Schedule b = due(EVENING);
            EventPlanner.Planned pa = EventPlanner.plan(input(EVENING, SimSettings.defaults(), c, a, 0, 0, 6, READY),
                    new SimRandom(seed));
            EventPlanner.Planned pb = EventPlanner.plan(input(EVENING, SimSettings.defaults(), new ArrayList<>(c).reversed(), b,
                    0, 0, 6, READY), new SimRandom(seed));
            assertEquals(pa, pb, "same plan whatever order the candidates come in");
            assertEquals(a, b);
        }
    }

    // ---- admin-forced -----------------------------------------------------------------------

    @Test
    void forcedEventsKeepTheLimitsAndTheHeadroom() {
        SimSettings s = SimSettings.defaults();
        SimRandom rng = new SimRandom(1);
        EventPlanner.Forced up = EventPlanner.forceNews(iron(512, 0.9), EventKind.UP, 60.0, EVENING, s, rng, 1);
        assertEquals(0.25, up.event().strength(), 1e-12, "held to 25%");
        EventPlanner.Forced room = EventPlanner.forceNews(iron(512, 1.0), EventKind.UP, 25.0, EVENING, s, rng, 1);
        assertEquals(0.225, room.event().strength(), 1e-12, "and to 90% of the headroom");
        assertEquals(Source.ADMIN, up.event().source());
        EventPlanner.Forced small = EventPlanner.forceNews(iron(512, 1.0), EventKind.DOWN, 3.0, EVENING, s, rng, 1);
        assertEquals(-0.10, small.event().strength(), 1e-12, "at least 10%");
        EventPlanner.Forced capped = EventPlanner.forceNews(iron(512, 1.2), EventKind.UP, 25.0, EVENING, s, rng, 1);
        assertFalse(capped.ok(), "only 4.5% of room left: refused");
        assertNotNull(capped.error());
        assertFalse(EventPlanner.forceNews(gold(), EventKind.UP, 20.0, EVENING, s, rng, 1).ok(), "sold out");
        assertEquals(EventKind.WANTED, EventPlanner.forceNews(gold(), EventKind.WANTED, null, EVENING, s, rng, 1).event().kind());
        assertFalse(EventPlanner.forceNews(oak(), EventKind.WANTED, null, EVENING, s, rng, 1).ok());

        EventPlanner.Forced hot = EventPlanner.forceStory(oak(), EventKind.HOT, 40.0, 20.0, EVENING, s, rng, 1);
        assertEquals(0.15, hot.event().strength(), 1e-12, "held to 15%");
        assertEquals(0L, hot.event().rampMs(), "at full strength now");
        assertEquals(20 * H, hot.event().holdMs());
        assertEquals(EVENING, hot.event().announceDueAt());
        assertFalse(EventPlanner.forceStory(with(oak(), true, 0, 0, 0), EventKind.DEAL, null, null, EVENING, s, rng, 1).ok(),
                "refused while the item has an event");
    }
}
