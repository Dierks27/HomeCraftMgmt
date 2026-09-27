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
 * <p>Pins the §2.2 limits — the whole multiplier inside {@code [0.75, 1.25]}, drift at most 8%,
 * HOT/DEAL at most 15%, news at most 25%, seasons + real at most {@code min(4.5%, 0.45 x spread)},
 * volatility at most 1.5 — both in {@link SimLimits} itself and in every place a configured
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

        // A config that asks for a wilder wander than the drift bound gets the bound: a bigger
        // sigma could only pin the drift against its clamp.
        SimSettings wild = new SimSettings(true, 5, 48, 25, 25, 60, new SimSettings.Drift(8, 1.5, 30, 10, 66),
                null, null, 40, 7, 2, null, null, null, s.samePlural(), null, null);
        assertEquals(0.08, ItemParams.of(iron, new ItemOverride(null, 1.5, null, null), wild).sigma());

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
