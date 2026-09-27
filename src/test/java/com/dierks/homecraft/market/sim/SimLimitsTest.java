package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.MarketItem;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's hard rule, in code: config can make the live market calmer, never wilder.
 *
 * <p>Pins the §2.2 limits — the whole multiplier inside {@code [0.75, 1.25]} and narrowed only
 * symmetrically, drift at most 8% with a half-life of at least 24 h and never faster than the
 * shipped settings at their liveliest, HOT/DEAL at most 15%, news at most 25%, seasons + real at
 * most {@code min(4.5%, 0.45 x spread)}, volatility at most 1.5 — both in {@link SimLimits}
 * itself and in every place a configured
 * number enters: the {@link SimSettings} records squeeze their values on construction, and
 * {@link ItemParams#of} squeezes the per-item volatility. Also pins that
 * {@link SimSettings#defaults()} is the shipped §12 section.
 *
 * <p>No server needed: the only Bukkit type is {@link Material}, a plain enum.
 */
class SimLimitsTest {

    @Test
    void theMultiplierBandCanOnlyNarrow() {
        assertEquals(1.25, SimLimits.multiplierHi(0.50), "max_up_percent 50 is still 1.25");
        assertEquals(0.75, SimLimits.multiplierLo(0.90), "max_down_percent 90 is still 0.75");
        assertEquals(1.25, SimLimits.multiplierHi(0.25));
        assertEquals(0.75, SimLimits.multiplierLo(0.25));
        assertEquals(1.10, SimLimits.multiplierHi(0.10), 1e-15, "narrower is fine");
        assertEquals(0.90, SimLimits.multiplierLo(0.10), 1e-15);
        assertEquals(1.0, SimLimits.multiplierHi(-0.2), "negative never goes below the balanced price");
        assertEquals(1.0, SimLimits.multiplierLo(-0.2));
        assertEquals(1.25, SimLimits.multiplierHi(Double.POSITIVE_INFINITY));
        assertEquals(0.75, SimLimits.multiplierLo(Double.POSITIVE_INFINITY));
        assertEquals(1.25, SimLimits.multiplierHi(Double.NaN));
        assertEquals(0.75, SimLimits.multiplierLo(Double.NaN));
    }

    /**
     * Narrowing is always symmetric: the band is {@code [1 - b, 1 + b]} with {@code b} the
     * narrower of max_up_percent and max_down_percent. Narrowing one side only (max_down_percent:
     * 0) used to clip every dip while keeping every rise, a mood that leaned about +2.3% on
     * average; now it narrows both sides.
     */
    @Test
    void narrowingOneSideOfTheBandNarrowsBoth() {
        Object[][] cases = {
                // max_up, max_down, band, lo, hi
                {25.0, 25.0, 25.0, 0.75, 1.25},
                {25.0, 0.0, 0.0, 1.0, 1.0},
                {0.0, 25.0, 0.0, 1.0, 1.0},
                {25.0, 10.0, 10.0, 0.90, 1.10},
                {10.0, 25.0, 10.0, 0.90, 1.10},
                {5.0, 10.0, 5.0, 0.95, 1.05},
                {50.0, 90.0, 25.0, 0.75, 1.25},    // both past the lock: the hard band
                {90.0, 20.0, 20.0, 0.80, 1.20},    // one past the lock never widens the other
                {-10.0, 25.0, 0.0, 1.0, 1.0},      // negative reads as 0
                {Double.NaN, 25.0, 0.0, 1.0, 1.0}, // NaN reads as 0
        };
        for (Object[] c : cases) {
            SimSettings s = band((double) c[0], (double) c[1]);
            String at = "max_up " + c[0] + ", max_down " + c[1];
            assertEquals((double) c[2], s.bandPercent(), 1e-12, at);
            assertEquals((double) c[3], s.multiplierLo(), 1e-12, at);
            assertEquals((double) c[4], s.multiplierHi(), 1e-12, at);
            assertEquals(1.0 - s.multiplierLo(), s.multiplierHi() - 1.0, 1e-12, at + ": symmetric");
            assertTrue(s.multiplierLo() >= SimLimits.MIN_MULTIPLIER && s.multiplierHi() <= SimLimits.MAX_MULTIPLIER, at);
        }
        assertEquals(25.0, SimSettings.defaults().bandPercent(), "shipped: 25 both ways");
    }

    private static SimSettings band(double maxUp, double maxDown) {
        SimSettings d = SimSettings.defaults();
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), maxUp, maxDown, d.keepDays(), d.drift(),
                d.hot(), d.deal(), d.slotsPerItems(), d.cooldownDays(), d.popularWeight(), d.news(), d.announce(),
                d.headlines(), d.samePlural(), d.seasons(), d.real());
    }

    /**
     * The drift's speed is locked: {@code half_life_hours} is at least 24 (a shorter one redrew
     * the drift within the day and brought back same-day scalping), and the drift size is held
     * so the drift never moves prices faster than the shipped settings at their liveliest
     * (4.5% at 66 h): {@code sigma <= 0.045 x sqrt(min(h, 66) / 66)}.
     */
    @Test
    void theDriftsSpeedIsLocked() {
        assertEquals(24.0, SimLimits.clampDriftHalfLife(0.0), "0 would redraw the drift every tick");
        assertEquals(24.0, SimLimits.clampDriftHalfLife(6.0));
        assertEquals(24.0, SimLimits.clampDriftHalfLife(-3.0));
        assertEquals(24.0, SimLimits.clampDriftHalfLife(Double.NaN));
        assertEquals(24.0, SimLimits.clampDriftHalfLife(24.0));
        assertEquals(66.0, SimLimits.clampDriftHalfLife(66.0), "the shipped value passes");
        assertEquals(500.0, SimLimits.clampDriftHalfLife(500.0), "slower is calmer, always allowed");
        assertEquals(Double.POSITIVE_INFINITY, SimLimits.clampDriftHalfLife(Double.POSITIVE_INFINITY));

        assertEquals(0.045, SimLimits.driftSigmaCap(66.0), "exactly 4.5% at the shipped half-life");
        assertEquals(0.045, SimLimits.driftSigmaCap(1000.0));
        assertEquals(0.045, SimLimits.driftSigmaCap(Double.POSITIVE_INFINITY));
        assertEquals(0.045 * Math.sqrt(24.0 / 66.0), SimLimits.driftSigmaCap(24.0), 1e-15);
        assertEquals(SimLimits.driftSigmaCap(24.0), SimLimits.driftSigmaCap(1.0), "below the floor reads as the floor");
        assertEquals(SimLimits.driftSigmaCap(24.0), SimLimits.driftSigmaCap(Double.NaN));

        // The Drift record holds the half-life to its floor, structurally.
        assertEquals(24.0, new SimSettings.Drift(8, 1.5, 3, 10, 0).halfLifeHours());
        assertEquals(24.0, new SimSettings.Drift(8, 1.5, 3, 10, 6).halfLifeHours());
        assertEquals(24.0, new SimSettings.Drift(8, 1.5, 3, 10, Double.NaN).halfLifeHours());
        assertEquals(66.0, SimSettings.Drift.defaults().halfLifeHours());

        // Whatever the knobs say, the drift's short-run speed (sd per square-root hour,
        // sigma x sqrt(2 ln2 / h)) never passes the shipped liveliest: lively 3% x volatility 1.5
        // at 66 h. Nor does its day-to-day move (about 3%) or its 5-minute tick move (about 0.19%).
        MarketItem iron = new MarketItem("iron_ingot", Material.IRON_INGOT, "&fIron Ingot", 4.0, 40.0, 512, 2048, 40, 80);
        MarketItem oak = new MarketItem("oak_log", Material.OAK_LOG, "&6Oak Log", 1.0, 20.0, 4000, 8000, 160, 320);
        double ln2 = Math.log(2);
        double shippedSpeed = 0.045 * Math.sqrt(2 * ln2 / 66.0);
        double shippedDay = 0.045 * Math.sqrt(2 * (1 - Math.pow(2, -24.0 / 66.0)));
        for (double h : new double[] {0, 1, 6, 12, 23.9, 24, 30, 48, 65.9, 66, 100, 1000}) {
            for (double pct : new double[] {0, 1.5, 3, 4.5, 8, 30, 1e6}) {
                for (Double vol : new Double[] {null, 0.5, 1.5, 9.0}) {
                    SimSettings s = drift(pct, pct, h);
                    for (MarketItem item : List.of(iron, oak)) {
                        double sigma = ItemParams.of(item, new ItemOverride(null, vol, null, null), s).sigma();
                        double hl = s.drift().halfLifeHours();
                        String at = item.id() + " percent " + pct + " volatility " + vol + " half-life " + h;
                        assertTrue(hl >= 24.0, at);
                        assertTrue(sigma <= SimLimits.DRIFT_SIGMA_MAX, at + ": sigma " + sigma);
                        assertTrue(sigma * Math.sqrt(2 * ln2 / hl) <= shippedSpeed * (1 + 1e-12), at + ": speed");
                        double day = sigma * Math.sqrt(2 * (1 - Math.pow(2, -24.0 / hl)));
                        assertTrue(day <= shippedDay * (1 + 1e-12), at + ": day move " + day);
                        double a = SimMath.ouDecay(5.0 / 60.0, hl);
                        assertTrue(sigma * Math.sqrt(1 - a * a) <= 0.0019, at + ": 5-minute tick move");
                    }
                }
            }
        }

        // The shipped settings are untouched by the lock, volatility 1.5 included.
        SimSettings shipped = SimSettings.defaults();
        assertEquals(0.03, ItemParams.of(iron, ItemOverride.NONE, shipped).sigma());
        assertEquals(0.015, ItemParams.of(oak, ItemOverride.NONE, shipped).sigma());
        assertEquals(0.045, ItemParams.of(iron, new ItemOverride(null, 1.5, null, null), shipped).sigma());
        // A shorter half-life slows the drift size to keep the speed: 24 h holds lively to 2.7%.
        assertEquals(0.045 * Math.sqrt(24.0 / 66.0), ItemParams.of(iron, ItemOverride.NONE, drift(1.5, 3.0, 6)).sigma(), 1e-15);
    }

    private static SimSettings drift(double calm, double lively, double halfLife) {
        SimSettings d = SimSettings.defaults();
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), d.maxUpPercent(), d.maxDownPercent(),
                d.keepDays(), new SimSettings.Drift(8, calm, lively, 10, halfLife), d.hot(), d.deal(), d.slotsPerItems(),
                d.cooldownDays(), d.popularWeight(), d.news(), d.announce(), d.headlines(), d.samePlural(), d.seasons(),
                d.real());
    }

    @Test
    void aRawMultiplierIsAlwaysClampedIntoTheHardBand() {
        assertEquals(1.25, SimLimits.clampMultiplier(1.6));
        assertEquals(0.75, SimLimits.clampMultiplier(0.2));
        assertEquals(1.0, SimLimits.clampMultiplier(Double.NaN), "NaN means no mood at all");
        assertEquals(1.0, SimLimits.clampMultiplier(1.0), "1.0 passes through bit for bit");
        assertEquals(1.25, SimLimits.clampMultiplier(3.0, 0.1, 9.0), "a band wider than the hard one is squeezed");
        assertEquals(0.75, SimLimits.clampMultiplier(0.0, 0.1, 9.0));
        assertEquals(1.1, SimLimits.clampMultiplier(1.2, 0.9, 1.1), "a narrower band is honoured");
        assertEquals(0.9, SimLimits.clampMultiplier(0.8, 0.9, 1.1));
    }

    @Test
    void driftStoryAndNewsSizesAreCapped() {
        assertEquals(0.08, SimLimits.clampDriftMax(0.20), "drift 20% is 8%");
        assertEquals(0.05, SimLimits.clampDriftMax(0.05));
        assertEquals(0.0, SimLimits.clampDriftMax(-1));

        // hot [10, 40] -> [10, 15]
        assertEquals(0.10, SimLimits.clampStory(0.10));
        assertEquals(0.15, SimLimits.clampStory(0.40));
        // news [15, 60] -> [15, 25]
        assertEquals(0.15, SimLimits.clampNews(0.15));
        assertEquals(0.25, SimLimits.clampNews(0.60));
        assertEquals(0.0, SimLimits.clampNews(Double.NaN));

        assertEquals(1.5, SimLimits.clampVolatility(9));
        assertEquals(0.0, SimLimits.clampVolatility(-1));
        assertEquals(1, SimLimits.clampTickMinutes(0));
        assertEquals(60, SimLimits.clampTickMinutes(600));
    }

    @Test
    void thePredictableLayersStayUnderHalfTheSpreadAndUnderFourAndAHalfPercent() {
        assertEquals(0.045, SimLimits.predictableCap(0.045, 0.10), "the shipped numbers");
        assertEquals(0.0, SimLimits.predictableCap(0.045, 0.0), "no spread, no predictable move");
        assertEquals(0.045, SimLimits.predictableCap(0.10, 0.10), "config above 45% of the spread is cut");
        assertEquals(0.045, SimLimits.predictableCap(0.10, 0.30), "a wide spread still stops at 4.5%");
        assertEquals(0.02, SimLimits.predictableCap(0.02, 0.10), "calmer is fine");
        assertEquals(0.018, SimLimits.predictableCap(0.045, 0.04), 1e-15, "a narrow spread narrows it");
        assertEquals(0.0, SimLimits.predictableCap(-0.1, 0.10));
        assertEquals(0.0, SimLimits.predictableCap(0.045, -0.10));

        // The point of the cap: the best predictable swing is cheaper than a round trip.
        double c = SimLimits.predictableCap(0.045, 0.10);
        assertTrue((1 + c) / (1 - c) < (1 + 0.05) / (1 - 0.05));
    }

    @Test
    void settingsRecordsSqueezeWildConfigIntoTheLimits() {
        SimSettings d = SimSettings.defaults();
        SimSettings wild = new SimSettings(true, 0, -5, 50, 90, 0,
                new SimSettings.Drift(20, -1, 3, 10, 66),
                new SimSettings.Story(true, new SimSettings.Range(10, 40), new SimSettings.Range(30, 54),
                        4, 10, new SimSettings.Range(48, 120), 3, true, 0, 0),
                new SimSettings.Story(true, new SimSettings.Range(40, 10), new SimSettings.Range(30, 54),
                        4, 10, new SimSettings.Range(48, 120), 3, false, 300, 7),
                0, -1, 2.0,
                new SimSettings.News(true, new SimSettings.Range(15, 60), 90, 6, 30, 10, 14, 48, 2,
                        SimSettings.Hours.DEFAULT, true, new SimSettings.Range(2, 8), 14, 72, 5, 0.5, 3, 7),
                d.announce(), d.headlines(), d.samePlural(),
                new SimSettings.Seasons(true, 10, 2, List.of()),
                new SimSettings.Real(true, "STOOQ", null, null, 2, 10, 8, 2, 24, 96, 0, null));

        assertEquals(1, wild.tickMinutes());
        assertEquals(1.25, wild.multiplierHi(), "max_up 50 -> 1.25");
        assertEquals(0.75, wild.multiplierLo(), "max_down 90 -> 0.75");
        assertEquals(0.08, wild.drift().maxFrac(), "drift 20 -> 8%");
        assertEquals(0.0, wild.drift().calmPercent());
        assertEquals(new SimSettings.Range(10, 15), wild.hot().percent(), "hot [10,40] -> [10,15]");
        assertEquals(new SimSettings.Range(10, 15), wild.deal().percent(), "pairs are sorted first");
        assertEquals(0.15, wild.hot().maxFrac());
        assertEquals(0.15, wild.hot().strength(1.0));
        assertEquals(100.0, wild.deal().minStockPercent());
        assertEquals(1.0, wild.deal().buyLimitShare());
        assertEquals(new SimSettings.Range(15, 25), wild.news().percent(), "news [15,60] -> [15,25]");
        assertEquals(0.25, wild.news().maxFrac());
        assertEquals(0.25, wild.news().minMoveFrac());
        assertEquals(1.0, wild.news().wantedShare());
        assertEquals(4.5, wild.seasons().predictableMaxPercent());
        assertEquals(0.045, wild.seasons().predictableCap(0.30));
        assertEquals(4.5, wild.real().maxPercent());
        assertEquals("stooq", wild.real().provider());
        assertEquals(LocalTime.of(17, 30), wild.real().fetchTime());
        assertEquals(1, wild.slotsPerItems(), "never a division by zero");
        assertEquals(0, wild.cooldownDays());
    }

    @Test
    void theShippedSettingsAreTheSpecsSection12() {
        SimSettings s = SimSettings.defaults();
        assertTrue(s.enabled());
        assertEquals(5, s.tickMinutes());
        assertEquals(300_000L, s.tickMs());
        assertEquals(48, s.maxCatchupHours());
        assertEquals(1.25, s.multiplierHi());
        assertEquals(0.75, s.multiplierLo());
        assertEquals(60, s.keepDays());

        assertEquals(new SimSettings.Drift(8, 1.5, 3.0, 10.0, 66), s.drift());
        assertEquals(0.08, s.drift().maxFrac());

        SimSettings.Story hot = s.hot();
        assertTrue(hot.enabled() && hot.sellLimit());
        assertEquals(new SimSettings.Range(8, 15), hot.percent());
        assertEquals(new SimSettings.Range(30, 54), hot.holdHours());
        assertEquals(4 * 3_600_000L, hot.rampMs());
        assertEquals(10 * 3_600_000L, hot.fadeMs());
        assertEquals(new SimSettings.Range(48, 120), hot.gapHours());
        assertEquals(3 * 3_600_000L, hot.lastCallMs());
        SimSettings.Story deal = s.deal();
        assertFalse(deal.sellLimit());
        assertEquals(3.0, deal.minStockPercent());
        assertEquals(0.5, deal.buyLimitShare());
        assertEquals(s.hot(), s.story(EventKind.HOT));
        assertEquals(s.deal(), s.story(EventKind.DEAL));
        assertNull(s.story(EventKind.UP));

        assertEquals(40, s.slotsPerItems());
        assertEquals(7, s.cooldownDays());
        assertEquals(2.0, s.popularWeight());

        SimSettings.News n = s.news();
        assertEquals(new SimSettings.Range(15, 25), n.percent());
        assertEquals(0.10, n.minMoveFrac());
        assertEquals(6 * 3_600_000L, n.halfLifeMs());
        assertEquals(30 * 3_600_000L, n.lastsMs());
        assertEquals(10.0, n.gapHours());
        assertEquals(14.0, n.extraHours());
        assertEquals(48.0, n.maxGapHours());
        assertEquals(2, n.maxPerDay());
        assertEquals("07:00-21:00", n.hours().toString());
        assertTrue(n.waitForPlayers());
        assertEquals(new SimSettings.Range(2, 8), n.joinDelayMinutes());
        assertEquals(14.0, n.maxHoldHours());
        assertEquals(72.0, n.itemCooldownHours());
        assertEquals(5.0, n.minStockPercent());
        assertEquals(0.5, n.buyLimitShare());
        assertEquals(0.15, n.wantedShare());
        assertEquals(7, n.wantedEveryDays());

        SimSettings.Announce a = s.announce();
        assertEquals(20, a.minGapMinutes());
        assertEquals(6, a.maxPerDay());
        for (String key : List.of(a.soundUp(), a.soundDown(), a.soundStory(), a.soundOther())) {
            assertTrue(key.startsWith("minecraft:block.note_block."), key + " must be namespaced");
        }
        assertEquals(0.8, a.volume());
        assertEquals(1.4, a.pitchUp());
        assertEquals(0.8, a.pitchDown());
        assertEquals(3, a.catchUpLines());
        assertEquals(48, a.catchUpHours());

        SimSettings.Headlines h = s.headlines();
        assertEquals(12, h.up().size());
        assertEquals(12, h.down().size());
        assertEquals(6, h.hot().size());
        assertEquals(6, h.deal().size());
        assertEquals(3, h.wanted().size());
        assertEquals(List.of("In the real world, {real} prices went up today!"), h.list("real_up"));
        assertEquals(h.wanted(), h.list("WANTED"));
        assertEquals(List.of(), h.list("nope"));

        assertEquals(41, s.samePlural().size());
        assertTrue(s.samePluralSet().contains("wheat") && s.samePluralSet().contains("glass"));
        assertEquals("cobblestone", s.samePlural().get(0));

        assertTrue(s.seasons().enabled());
        assertEquals(0.045, s.seasons().predictableCap(0.10));
        assertEquals(2, s.seasons().rampDays());
        assertEquals(7, s.seasons().list().size());
        assertEquals("harvest_time", s.seasons().list().get(5).id());

        SimSettings.Real r = s.real();
        assertFalse(r.enabled(), "real-world prices ship off");
        assertEquals("stooq", r.provider());
        assertEquals("", r.url());
        assertEquals(LocalTime.of(17, 30), r.fetchTime());
        assertEquals(0.04, r.maxFrac());
        assertEquals(0.08, r.ignoreAboveFrac());
        assertEquals(0.02, r.announceAboveFrac());
        assertEquals(24 * 3_600_000L, r.halfLifeMs());
        assertEquals(96 * 3_600_000L, r.lastsMs());
        assertEquals(List.of(new RealSymbol("gold_ingot", "gc.f", "GC=F", "gold"),
                new RealSymbol("wheat", "zw.f", "ZW=F", "wheat"),
                new RealSymbol("iron_ingot", "hg.f", "HG=F", "metal")), r.symbols());
        assertEquals("GC=F", r.symbols().get(0).symbol("yahoo"));
        assertEquals("", r.symbols().get(0).symbol("bloomberg"));

        assertEquals(s, SimSettings.defaults(), "defaults are a value, equal every time");
        assertFalse(s.withEnabled(false).enabled());
        assertEquals(s, s.withEnabled(false).withEnabled(true));
    }

    @Test
    void newsHoursParseAndWrap() {
        SimSettings.Hours h = SimSettings.Hours.parse("07:00-21:00");
        assertEquals(SimSettings.Hours.DEFAULT, h);
        assertTrue(h.contains(LocalTime.of(7, 0)), "from is inclusive");
        assertTrue(h.contains(LocalTime.of(20, 59)));
        assertFalse(h.contains(LocalTime.of(21, 0)), "to is exclusive");
        assertFalse(h.contains(LocalTime.of(3, 0)));

        SimSettings.Hours night = SimSettings.Hours.parse(" 22:00 - 2:30 ");
        assertTrue(night.contains(LocalTime.of(23, 0)));
        assertTrue(night.contains(LocalTime.of(1, 0)));
        assertFalse(night.contains(LocalTime.of(12, 0)));

        SimSettings.Hours all = SimSettings.Hours.parse("08:00-08:00");
        assertTrue(all.allDay() && all.contains(LocalTime.of(3, 0)), "from == to is all day");

        for (String bad : new String[]{null, "", "7-21", "07:00", "25:00-26:00", "07:60-21:00", "a:b-c:d"}) {
            assertNull(SimSettings.Hours.parse(bad), "'" + bad + "'");
        }
    }

    @Test
    void itemVolatilityIsClampedAndTheOverridesApply() {
        SimSettings s = SimSettings.defaults();
        MarketItem iron = new MarketItem("iron_ingot", Material.IRON_INGOT, "&fIron Ingot",
                4.0, 40.0, 512, 2048, 40, 80);

        assertEquals(0.03 * 1.5, ItemParams.of(iron, new ItemOverride(null, 9.0, null, null), s).sigma(), 1e-15,
                "volatility 9 is 1.5");
        assertEquals(0.0, ItemParams.of(iron, new ItemOverride(null, -2.0, null, null), s).sigma(),
                "negative volatility is no drift");
        assertEquals(0.0, ItemParams.of(iron, new ItemOverride(null, Double.NaN, null, null), s).sigma());

        // A config that asks for a wilder wander gets the drift's speed lock (4.5% at 66 h), and
        // never more than the drift bound: a bigger sigma could only pin the drift against its clamp.
        SimSettings wild = new SimSettings(true, 5, 48, 25, 25, 60, new SimSettings.Drift(8, 1.5, 30, 10, 66),
                null, null, 40, 7, 2, null, null, null, s.samePlural(), null, null);
        assertEquals(0.045, ItemParams.of(iron, new ItemOverride(null, 1.5, null, null), wild).sigma());
        SimSettings tightBound = new SimSettings(true, 5, 48, 25, 25, 60, new SimSettings.Drift(2, 1.5, 30, 10, 66),
                null, null, 40, 7, 2, null, null, null, s.samePlural(), null, null);
        assertEquals(0.02, ItemParams.of(iron, new ItemOverride(null, 1.5, null, null), tightBound).sigma(),
                "a drift bound under the speed lock wins");

        assertFalse(ItemParams.of(iron, new ItemOverride(false, null, null, null), s).enabled(), "sim: false");
        assertTrue(ItemParams.of(iron, ItemOverride.NONE, s).enabled());
        assertTrue(ItemParams.of(iron, null, null).enabled(), "no override and no settings means the defaults");
        assertEquals(1.0, ItemParams.of(iron, ItemOverride.NONE, s).weight());
        assertEquals(0.0, ItemParams.of(iron, new ItemOverride(null, null, -3.0, null), s).weight());
        assertEquals(0.0, ItemParams.of(iron, new ItemOverride(null, null, Double.POSITIVE_INFINITY, null), s).weight());
        assertEquals(2.5, ItemParams.of(iron, new ItemOverride(null, null, 2.5, null), s).weight());

        ItemParams named = ItemParams.of(iron, new ItemOverride(null, null, null,
                "&bThe Shiniest Iron Bars In The Land"), s);
        assertEquals("The Shiniest Iron Bars I", named.plural(), "news_name is cut to 24 characters");
        assertEquals(24, named.plural().length());
        assertEquals("Iron Ingot", named.name(), "{Name} still comes from the label");
    }
}
