package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.PricingEngine;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 90-day soak of the live market on the shipped catalog at shipped stock (spec §15), 5 seeds,
 * a player online 19:00-21:00 every evening and 10:00-12:00 on weekend mornings
 * (America/Chicago), starting on ship day.
 *
 * <p>Pins the HARD RULE at every item-tick: {@code M} inside {@code [0.75, 1.25]}, the displayed
 * price inside {@code [floor, ceiling]}, {@code |d| <= 0.08}, and stock (and the balanced price)
 * never changed — the sim is only ever handed a read-only view. And the §15 shape of the market:
 * mean {@code M} within 1.5% of 1, 0.55-1.0 flashes a day with at least 90% of them announced
 * live, 0.8-1.6 HOT and DEAL starts a week each running 25-55% of the time, a median 7-day
 * high/low range of 15-50% on the four tradable items (every seed, and pooled), and — with
 * events and seasons off — the drift's day-move distribution (calm items: median daily move
 * 0.5-0.9%, at least 90% of item-days within 3% of usual; lively: 1.0-1.7%, at least 60%).
 * Also the §6.4 anti-spam rules over every tick: at most one start per tick, every HOT/DEAL at
 * most 15% and every flash at most 25%, broadcasts only 07:00-21:00 with someone online, at
 * least 20 minutes apart and at most 6 a day, at most 2 flashes a day.
 *
 * <p>The ranges are the spec's; the measured values (printed when the test runs) sit well inside
 * them: mean M 0.997-1.008, 0.76-0.81 flashes a day all live, 1.17-1.32 HOT and DEAL starts a
 * week running 38-42% of the time, 7-day ranges 24-41%, drift alone 0.71%/day (95% within 3%)
 * calm and 1.35%/day (70%) lively.
 */
class MarketSimulatorSoakTest {

    static final ZoneId ZONE = ZoneId.of("America/Chicago");
    /** Ship day, noon local. */
    static final long START = LocalDateTime.of(2026, 9, 27, 12, 0).atZone(ZONE).toInstant().toEpochMilli();
    static final double SPREAD = 0.10;
    static final PricingEngine ENGINE = new PricingEngine(1.0, 0.2, SPREAD);
    static final int DAYS = 90;
    static final long[] SEEDS = {1L, 2L, 3L, 4L, 5L};
    static final List<String> TRADABLE = List.of("cobblestone", "oak_log", "wheat", "iron_ingot");

    private static final Map<Long, Soak> FULL = new LinkedHashMap<>();
    private static final Map<Long, Soak> QUIET = new LinkedHashMap<>();

    // ---- shared fixtures (the determinism test uses them too) --------------------------------

    /** The shipped {@code market.catalog}. */
    static List<MarketItem> shippedCatalog() {
        return List.of(
                new MarketItem("cobblestone", Material.COBBLESTONE, "&7Cobblestone", 0.10, 5.0, 17000, 20000, 400, 800),
                new MarketItem("oak_log", Material.OAK_LOG, "&6Oak Log", 1.0, 20.0, 4000, 8000, 160, 320),
                new MarketItem("wheat", Material.WHEAT, "&eWheat", 1.0, 12.0, 3000, 6000, 120, 240),
                new MarketItem("iron_ingot", Material.IRON_INGOT, "&fIron Ingot", 4.0, 40.0, 512, 2048, 40, 80),
                new MarketItem("gold_ingot", Material.GOLD_INGOT, "&6Gold Ingot", 20.0, 150.0, 0, 1024, 20, 40),
                new MarketItem("diamond", Material.DIAMOND, "&bDiamond", 60.0, 400.0, 0, 512, 10, 20));
    }

    static SortedMap<String, ItemParams> params(SimSettings s) {
        SortedMap<String, ItemParams> out = new TreeMap<>();
        for (MarketItem m : shippedCatalog()) {
            out.put(m.id(), ItemParams.of(m, ItemOverride.NONE, s));
        }
        return Collections.unmodifiableSortedMap(out);
    }

    /** Balanced price on the curve at the shipped stock; read-only, as the sim must see it. */
    static Map<String, MarketSimulator.Quote> shippedMarket() {
        Map<String, MarketSimulator.Quote> out = new TreeMap<>();
        for (MarketItem m : shippedCatalog()) {
            out.put(m.id(), new MarketSimulator.Quote(ENGINE.targetPrice(m, m.initialStock()), m.initialStock()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Someone online 19:00-21:00 daily and 10:00-12:00 on Saturday and Sunday (local). */
    static OnlineInfo online(long t) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(ZONE);
        LocalTime lt = z.toLocalTime();
        boolean weekend = z.getDayOfWeek() == DayOfWeek.SATURDAY || z.getDayOfWeek() == DayOfWeek.SUNDAY;
        LocalTime from = null;
        if (!lt.isBefore(LocalTime.of(19, 0)) && lt.isBefore(LocalTime.of(21, 0))) {
            from = LocalTime.of(19, 0);
        } else if (weekend && !lt.isBefore(LocalTime.of(10, 0)) && lt.isBefore(LocalTime.of(12, 0))) {
            from = LocalTime.of(10, 0);
        }
        if (from == null) {
            return OnlineInfo.NOBODY;
        }
        long since = z.toLocalDate().atTime(from).atZone(ZONE).toInstant().toEpochMilli();
        return new OnlineInfo(1, t - since);
    }

    /** The shipped settings with HOT, DEAL, news and seasons all off: drift alone. */
    static SimSettings driftOnly() {
        SimSettings d = SimSettings.defaults();
        SimSettings.News n = d.news();
        SimSettings.News newsOff = new SimSettings.News(false, n.percent(), n.minPercent(), n.halfLifeHours(),
                n.lastsHours(), n.gapHours(), n.extraHours(), n.maxGapHours(), n.maxPerDay(), n.hours(),
                n.waitForPlayers(), n.joinDelayMinutes(), n.maxHoldHours(), n.itemCooldownHours(),
                n.minStockPercent(), n.buyLimitShare(), n.wantedShare(), n.wantedEveryDays());
        SimSettings.Seasons se = d.seasons();
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), d.maxUpPercent(), d.maxDownPercent(),
                d.keepDays(), d.drift(), storyOff(d.hot()), storyOff(d.deal()), d.slotsPerItems(), d.cooldownDays(),
                d.popularWeight(), newsOff, d.announce(), d.headlines(), d.samePlural(),
                new SimSettings.Seasons(false, se.predictableMaxPercent(), se.rampDays(), se.list()), d.real());
    }

    static SimSettings.Story storyOff(SimSettings.Story k) {
        return new SimSettings.Story(false, k.percent(), k.holdHours(), k.rampHours(), k.fadeHours(), k.gapHours(),
                k.lastCallHours(), k.sellLimit(), k.minStockPercent(), k.buyLimitShare());
    }

    // ---- the soak -----------------------------------------------------------------------------

    /** What one 90-day run measured. */
    record Soak(long seed, int ticks, double minM, double maxM, double meanM, double maxAbsDrift,
                boolean priceInBand, boolean marketUnchanged, int flashes, int liveFlashes, int wanted,
                int hotStarts, int dealStarts, double hotOccupancy, double dealOccupancy, int broadcasts,
                Map<String, double[]> prices, Map<String, double[]> dailyM, List<Long> broadcastTimes,
                Map<Long, Integer> newsPerDay, int maxStartsPerTick, double maxStory, double maxNews) {

        double days() {
            return (double) DAYS;
        }

        double flashesPerDay() {
            return flashes / days();
        }

        double liveShare() {
            return flashes == 0 ? 0.0 : (double) liveFlashes / flashes;
        }

        double hotPerWeek() {
            return hotStarts / (days() / 7.0);
        }

        double dealPerWeek() {
            return dealStarts / (days() / 7.0);
        }
    }

    static Soak soak(long seed, SimSettings s) {
        SimRandom rng = new SimRandom(seed);
        SortedMap<String, ItemParams> items = params(s);
        Map<String, MarketSimulator.Quote> market = shippedMarket();
        Map<String, MarketSimulator.Quote> before = new TreeMap<>(market);
        Map<String, ItemSimState> state = new HashMap<>();
        Schedule sched = Schedule.firstEnable(START, rng);
        BroadcastState bs = new BroadcastState();
        List<MarketEvent> events = List.of();
        Map<String, Double> prev = null;

        long tick = s.tickMs();
        long first = Math.floorDiv(START, tick) * tick + tick;
        long end = START + DAYS * SimMath.DAY_MS;
        int perDay = (int) (SimMath.DAY_MS / tick);
        int n = (int) ((end - first) / tick);

        Map<String, double[]> prices = new LinkedHashMap<>();
        Map<String, double[]> dailyM = new LinkedHashMap<>();
        for (String id : items.keySet()) {
            prices.put(id, new double[n]);
            dailyM.put(id, new double[n / perDay]);
        }
        double minM = Double.MAX_VALUE;
        double maxM = -Double.MAX_VALUE;
        double sumM = 0;
        long countM = 0;
        double maxDrift = 0;
        boolean inBand = true;
        int flashes = 0;
        int live = 0;
        int wanted = 0;
        int hot = 0;
        int deal = 0;
        int hotTicks = 0;
        int dealTicks = 0;
        int broadcasts = 0;
        List<Long> broadcastTimes = new ArrayList<>();
        Map<Long, Integer> newsPerDay = new TreeMap<>();
        int maxStarts = 0;
        double maxStory = 0;
        double maxNews = 0;

        for (int k = 0; k < n; k++) {
            long b = first + k * tick;
            MarketSimulator.TickInput in = new MarketSimulator.TickInput(b, true, s, rng, items, market, state, events,
                    sched, online(b), ZONE, SPREAD, List.of(), bs, Set.of(), prev);
            MarketSimulator.TickResult r = MarketSimulator.tick(in);
            events = r.events();
            prev = r.multipliers();
            if (r.broadcast().isPresent()) {
                broadcasts++;
                broadcastTimes.add(b);
            }
            int starts = 0;
            for (MarketEvent e : r.started()) {
                if (e.kind() != EventKind.SEASON) {
                    starts++;
                }
                if (e.kind().story()) {
                    maxStory = Math.max(maxStory, Math.abs(e.strength()));
                } else if (e.kind().news()) {
                    maxNews = Math.max(maxNews, Math.abs(e.strength()));
                }
                switch (e.kind()) {
                    case UP, DOWN, WANTED -> {
                        flashes++;
                        newsPerDay.merge(Instant.ofEpochMilli(b).atZone(ZONE).toLocalDate().toEpochDay(), 1, Integer::sum);
                        if (e.announced()) {
                            live++;
                        }
                        if (e.kind() == EventKind.WANTED) {
                            wanted++;
                        }
                    }
                    case HOT -> hot++;
                    case DEAL -> deal++;
                    default -> {
                    }
                }
            }
            maxStarts = Math.max(maxStarts, starts);
            boolean anyHot = false;
            boolean anyDeal = false;
            for (MarketEvent e : events) {
                if (e.active(b)) {
                    anyHot |= e.kind() == EventKind.HOT;
                    anyDeal |= e.kind() == EventKind.DEAL;
                }
            }
            hotTicks += anyHot ? 1 : 0;
            dealTicks += anyDeal ? 1 : 0;
            for (Map.Entry<String, ItemParams> en : items.entrySet()) {
                String id = en.getKey();
                ItemParams p = en.getValue();
                double m = r.multipliers().get(id);
                minM = Math.min(minM, m);
                maxM = Math.max(maxM, m);
                sumM += m;
                countM++;
                double price = r.status().get(id).price();
                inBand &= price >= p.floor() && price <= p.ceiling();
                prices.get(id)[k] = price;
                if (k % perDay == 0 && k / perDay < dailyM.get(id).length) {
                    dailyM.get(id)[k / perDay] = m;
                }
                maxDrift = Math.max(maxDrift, Math.abs(state.get(id).drift()));
            }
        }
        return new Soak(seed, n, minM, maxM, sumM / countM, maxDrift, inBand, before.equals(market), flashes, live,
                wanted, hot, deal, (double) hotTicks / n, (double) dealTicks / n, broadcasts, prices, dailyM,
                broadcastTimes, newsPerDay, maxStarts, maxStory, maxNews);
    }

    @BeforeAll
    static void runAll() {
        for (long seed : SEEDS) {
            FULL.put(seed, soak(seed, SimSettings.defaults()));
            QUIET.put(seed, soak(seed, driftOnly()));
        }
        for (Soak r : FULL.values()) {
            System.out.printf(Locale.ROOT,
                    "soak seed %d: M %.4f..%.4f mean %.4f |d|max %.4f flashes/day %.3f live %.2f wanted %d "
                            + "hot/wk %.2f deal/wk %.2f hotOcc %.2f dealOcc %.2f broadcasts %d%n",
                    r.seed(), r.minM(), r.maxM(), r.meanM(), r.maxAbsDrift(), r.flashesPerDay(), r.liveShare(),
                    r.wanted(), r.hotPerWeek(), r.dealPerWeek(), r.hotOccupancy(), r.dealOccupancy(), r.broadcasts());
            StringBuilder sb = new StringBuilder("  7d range median:");
            for (String id : TRADABLE) {
                sb.append(String.format(Locale.ROOT, " %s %.3f", id, median(ranges(r.prices().get(id), 7))));
            }
            System.out.println(sb);
        }
        for (String id : TRADABLE) {
            System.out.printf(Locale.ROOT, "pooled 7d range median %s %.3f%n", id, pooledRangeMedian(id));
        }
        System.out.printf(Locale.ROOT, "drift only: calm median daily %.4f within3 %.3f | lively median daily %.4f within3 %.3f%n",
                median(dailyMoves(false)), within(false), median(dailyMoves(true)), within(true));
    }

    // ---- the hard rule ------------------------------------------------------------------------

    @Test
    void theMultiplierThePriceAndTheDriftStayInsideTheirHardBounds() {
        for (Map<Long, Soak> runs : List.of(FULL, QUIET)) {
            for (Soak r : runs.values()) {
                assertTrue(r.minM() >= SimLimits.MIN_MULTIPLIER, "seed " + r.seed() + " M min " + r.minM());
                assertTrue(r.maxM() <= SimLimits.MAX_MULTIPLIER, "seed " + r.seed() + " M max " + r.maxM());
                assertTrue(r.priceInBand(), "seed " + r.seed() + ": every displayed price inside [floor, ceiling]");
                assertTrue(r.maxAbsDrift() <= SimLimits.DRIFT_MAX, "seed " + r.seed() + " |d| " + r.maxAbsDrift());
            }
        }
    }

    @Test
    void stockAndTheBalancedPriceNeverChange() {
        for (Map<Long, Soak> runs : List.of(FULL, QUIET)) {
            for (Soak r : runs.values()) {
                assertTrue(r.marketUnchanged(), "seed " + r.seed() + ": the sim only reads stock and price");
            }
        }
        // And sold-out items sit at exactly their ceiling the whole time, whatever M does.
        for (Soak r : FULL.values()) {
            for (String id : List.of("gold_ingot", "diamond")) {
                double ceiling = params(SimSettings.defaults()).get(id).ceiling();
                for (double p : r.prices().get(id)) {
                    assertEquals(ceiling, p, "seed " + r.seed() + " " + id);
                }
            }
        }
    }

    @Test
    void theMeanMultiplierStaysAtOne() {
        for (Soak r : FULL.values()) {
            assertTrue(r.meanM() >= 0.985 && r.meanM() <= 1.015, "seed " + r.seed() + " mean M " + r.meanM());
        }
    }

    // ---- the pace -----------------------------------------------------------------------------

    @Test
    void newsFlashesComeAboutOnceAnEveningAndLandLive() {
        for (Soak r : FULL.values()) {
            assertTrue(r.flashesPerDay() >= 0.55 && r.flashesPerDay() <= 1.0,
                    "seed " + r.seed() + " flashes/day " + r.flashesPerDay());
            assertTrue(r.liveShare() >= 0.90, "seed " + r.seed() + " live share " + r.liveShare());
        }
    }

    @Test
    void hotAndDealStartAboutOnceAWeekAndRunAboutAThirdOfTheTime() {
        for (Soak r : FULL.values()) {
            assertTrue(r.hotPerWeek() >= 0.8 && r.hotPerWeek() <= 1.6, "seed " + r.seed() + " HOT/wk " + r.hotPerWeek());
            assertTrue(r.dealPerWeek() >= 0.8 && r.dealPerWeek() <= 1.6, "seed " + r.seed() + " DEAL/wk " + r.dealPerWeek());
            assertTrue(r.hotOccupancy() >= 0.25 && r.hotOccupancy() <= 0.55,
                    "seed " + r.seed() + " HOT occupancy " + r.hotOccupancy());
            assertTrue(r.dealOccupancy() >= 0.25 && r.dealOccupancy() <= 0.55,
                    "seed " + r.seed() + " DEAL occupancy " + r.dealOccupancy());
        }
    }

    @Test
    void theTradableItemsMoveNoticeablyOverAWeek() {
        for (Soak r : FULL.values()) {
            for (String id : TRADABLE) {
                double med = median(ranges(r.prices().get(id), 7));
                assertTrue(med >= 0.15 && med <= 0.50, "seed " + r.seed() + " " + id + " median 7-day range " + med);
            }
        }
        for (String id : TRADABLE) {
            double med = pooledRangeMedian(id);
            assertTrue(med >= 0.15 && med <= 0.50, id + " pooled median 7-day high/low range " + med);
        }
    }

    // ---- anti-spam (§6.4) ---------------------------------------------------------------------

    @Test
    void atMostOneStartPerTickAndEveryStrengthInsideItsLimit() {
        for (Soak r : FULL.values()) {
            assertTrue(r.maxStartsPerTick() <= 1, "seed " + r.seed());
            assertTrue(r.maxStory() <= SimLimits.STORY_MAX, "seed " + r.seed() + " HOT/DEAL " + r.maxStory());
            assertTrue(r.maxNews() <= SimLimits.NEWS_MAX, "seed " + r.seed() + " UP/DOWN " + r.maxNews());
            assertTrue(r.maxStory() >= 0.08, "seed " + r.seed() + ": stories do run at their configured size");
        }
    }

    @Test
    void broadcastsRespectTheHoursTheGapAndTheDailyCaps() {
        SimSettings s = SimSettings.defaults();
        for (Soak r : FULL.values()) {
            Map<Long, Integer> perDay = new TreeMap<>();
            long prevAt = Long.MIN_VALUE / 2;
            for (long at : r.broadcastTimes()) {
                ZonedDateTime z = Instant.ofEpochMilli(at).atZone(ZONE);
                assertTrue(s.news().hours().contains(z.toLocalTime()), "seed " + r.seed() + " broadcast at " + z);
                assertTrue(at - prevAt >= 20 * SimMath.MINUTE_MS, "seed " + r.seed() + " gap before " + z);
                assertTrue(online(at).anyone(), "seed " + r.seed() + " nobody online at " + z);
                perDay.merge(z.toLocalDate().toEpochDay(), 1, Integer::sum);
                prevAt = at;
            }
            for (int count : perDay.values()) {
                assertTrue(count <= 6, "seed " + r.seed() + " broadcasts in a day " + count);
            }
            for (int count : r.newsPerDay().values()) {
                assertTrue(count <= 2, "seed " + r.seed() + " flashes in a day " + count);
            }
        }
    }

    // ---- drift alone --------------------------------------------------------------------------

    @Test
    void driftAloneMovesStaplesAboutOnePercentAndLivelyItemsAboutTwoPercentADay() {
        double calm = median(dailyMoves(false));
        double lively = median(dailyMoves(true));
        assertTrue(calm >= 0.005 && calm <= 0.009, "calm median daily move " + calm);
        assertTrue(lively >= 0.010 && lively <= 0.017, "lively median daily move " + lively);
        assertTrue(within(false) >= 0.90, "calm item-days within 3% " + within(false));
        assertTrue(within(true) >= 0.60, "lively item-days within 3% " + within(true));
    }

    @Test
    void driftAloneStartsNothingAndAnnouncesOnlyTheIntro() {
        for (Soak r : QUIET.values()) {
            assertEquals(0, r.flashes() + r.hotStarts() + r.dealStarts(), "seed " + r.seed());
            assertEquals(1, r.broadcasts(), "seed " + r.seed() + ": only the one-time intro");
        }
    }

    // ---- measuring ----------------------------------------------------------------------------

    /** {@code |M(day + 1) / M(day) - 1|} for every calm (or lively) item-day of the drift-only runs. */
    private static double[] dailyMoves(boolean lively) {
        List<Double> out = new ArrayList<>();
        SortedMap<String, ItemParams> items = params(driftOnly());
        for (Soak r : QUIET.values()) {
            for (Map.Entry<String, ItemParams> en : items.entrySet()) {
                if (en.getValue().lively() != lively) {
                    continue;
                }
                double[] m = r.dailyM().get(en.getKey());
                for (int i = 1; i < m.length; i++) {
                    out.add(Math.abs(m[i] / m[i - 1] - 1.0));
                }
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Share of calm (or lively) item-days of the drift-only runs within 3% of the usual price. */
    private static double within(boolean lively) {
        int in = 0;
        int all = 0;
        SortedMap<String, ItemParams> items = params(driftOnly());
        for (Soak r : QUIET.values()) {
            for (Map.Entry<String, ItemParams> en : items.entrySet()) {
                if (en.getValue().lively() != lively) {
                    continue;
                }
                for (double m : r.dailyM().get(en.getKey())) {
                    all++;
                    if (Math.abs(m - 1.0) <= 0.03) {
                        in++;
                    }
                }
            }
        }
        return (double) in / all;
    }

    /** For every day, the price's high/low range over the 7 days from it: {@code max / min - 1}. */
    private static double[] ranges(double[] prices, int days) {
        int perDay = (int) (SimMath.DAY_MS / SimSettings.defaults().tickMs());
        int w = days * perDay;
        List<Double> out = new ArrayList<>();
        for (int start = 0; start + w <= prices.length; start += perDay) {
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (int i = start; i < start + w; i++) {
                lo = Math.min(lo, prices[i]);
                hi = Math.max(hi, prices[i]);
            }
            out.add(hi / lo - 1.0);
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double pooledRangeMedian(String id) {
        List<Double> all = new ArrayList<>();
        for (Soak r : FULL.values()) {
            for (double v : ranges(r.prices().get(id), 7)) {
                all.add(v);
            }
        }
        return median(all.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static double median(double[] xs) {
        double[] c = xs.clone();
        Arrays.sort(c);
        int n = c.length;
        return n == 0 ? Double.NaN : (n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0);
    }
}
