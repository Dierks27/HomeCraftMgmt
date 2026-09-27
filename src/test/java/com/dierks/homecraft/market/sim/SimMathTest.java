package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.MarketItem;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live market's formulas (spec §2.3, §2.5, §5.2, §5.3).
 *
 * <p>Pins: the drift is an exact OU step (z = 0 is pure decay; stationary sd is sigma; the
 * daily move is about 1% for staples and 2% for lively items at 5-minute ticks; it never
 * passes 8%). The HOT/DEAL envelope hits 0, 0.5, 1, 0.5, 0 at the spec's points and a stop
 * fades it to 0 within the hour. The news shock is exactly 1 the instant it fires — the
 * anti-front-running guarantee — and decays to the spec's table. Headroom never goes past the
 * band or the ceiling/floor, and an empty item's displayed price is its ceiling whatever the
 * multiplier. {@link ItemParams} derives the §2.5 calm/lively classes for the shipped catalog.
 */
class SimMathTest {

    private static final long H = SimMath.HOUR_MS;
    private static final long T0 = 1_790_000_000_000L;
    private static final double TICK_H = 5.0 / 60.0;

    @Test
    void smoothIsTheSmoothstep() {
        assertEquals(0.0, SimMath.smooth(0));
        assertEquals(0.5, SimMath.smooth(0.5));
        assertEquals(1.0, SimMath.smooth(1));
        assertEquals(0.0, SimMath.smooth(-3), "clamped below");
        assertEquals(1.0, SimMath.smooth(7), "clamped above");
        assertEquals(0.0, SimMath.smooth(Double.NaN));
        assertEquals(0.15625, SimMath.smooth(0.25), 1e-15);
        assertEquals(12.0, SimMath.lerp(10, 20, 0.2), 1e-12);
    }

    // ---- drift ----------------------------------------------------------------------------

    @Test
    void ouDecayMatchesTheSpec() {
        assertEquals(0.999125, SimMath.ouDecay(TICK_H, 66), 1e-6, "5-minute tick, 66 h half-life");
        assertEquals(0.5, SimMath.ouDecay(66, 66), 1e-15);
        assertEquals(0.0, SimMath.ouDecay(TICK_H, 0), "no half-life forgets at once");
        assertEquals(1.0, SimMath.ouDecay(0, 66));
        assertEquals(0.667, SimMath.dailyMoveSd(1.0, 66), 0.001, "daily move sd is 0.667 sigma");
        assertEquals(0.0100, SimMath.dailyMoveSd(0.015, 66), 0.0001, "calm: 1% a day");
        assertEquals(0.0200, SimMath.dailyMoveSd(0.030, 66), 0.0001, "lively: 2% a day");
    }

    @Test
    void ouStepWithZeroNoiseIsPureDecay() {
        double a = SimMath.ouDecay(TICK_H, 66);
        assertEquals(0.05 * a, SimMath.ouStep(0.05, TICK_H, 66, 0.03, 0.0, 0.08));
        assertEquals(-0.07 * a, SimMath.ouStep(-0.07, TICK_H, 66, 0.015, 0.0, 0.08));
        assertEquals(0.0, SimMath.ouStep(Double.NaN, TICK_H, 66, 0.03, Double.NaN, 0.08));
    }

    @Test
    void ouStepNeverPassesTheDriftBound() {
        // A config asking for 50% and a huge draw still stops at 8%.
        assertEquals(0.08, SimMath.ouStep(0.079, TICK_H, 66, 0.5, 40, 0.5));
        assertEquals(-0.08, SimMath.ouStep(-0.079, TICK_H, 66, 0.5, -40, 0.5));
        assertEquals(0.03, SimMath.ouStep(0.0, TICK_H, 66, 0.5, 40, 0.03), "a narrower bound is honoured");
        assertEquals(0.0, SimMath.ouStep(0.05, TICK_H, 66, 0.5, 40, -1), "a negative bound is no drift");
    }

    @Test
    void theStationarySdIsSigma() {
        // The step is exact for any dt, so one half-life per step gives near-independent
        // samples and a tight estimate from 200k steps.
        for (double sigma : new double[]{0.015, 0.03}) {
            SimRandom r = new SimRandom(42L);
            double d = 0;
            double sum = 0;
            double sq = 0;
            int n = 200_000;
            for (int i = 0; i < n; i++) {
                d = SimMath.ouStep(d, 66, 66, sigma, r.gaussian("drift|stationary", i), 0.08);
                sum += d;
                sq += d * d;
            }
            double mean = sum / n;
            double sd = Math.sqrt(sq / n - mean * mean);
            assertEquals(sigma, sd, 0.03 * sigma, "sigma " + sigma);
            assertTrue(Math.abs(mean) < 0.05 * sigma, "mean drift ~0: " + mean);
        }
    }

    @Test
    void theDailyMoveAtFiveMinuteTicksIsOneAndTwoPercent() {
        assertDailyMove(0.015, 0.0100, 0.0005, "calm");
        assertDailyMove(0.030, 0.0200, 0.0010, "lively");
    }

    private static void assertDailyMove(double sigma, double expected, double tol, String what) {
        SimRandom r = new SimRandom(7L);
        long key = SimRandom.key("drift|" + what);
        int perDay = 288;
        int days = 10_000;
        double d = 0;
        long c = 0;
        for (int i = 0; i < 44 * perDay; i++) { // burn-in: 44 days, 16 half-lives
            d = SimMath.ouStep(d, TICK_H, 66, sigma, r.gaussian(key, c++), 0.08);
        }
        double prev = d;
        double sum = 0;
        double sq = 0;
        double maxAbs = 0;
        for (int day = 0; day < days; day++) {
            for (int i = 0; i < perDay; i++) {
                d = SimMath.ouStep(d, TICK_H, 66, sigma, r.gaussian(key, c++), 0.08);
                maxAbs = Math.max(maxAbs, Math.abs(d));
            }
            double move = d - prev;
            sum += move;
            sq += move * move;
            prev = d;
        }
        double mean = sum / days;
        double sd = Math.sqrt(sq / days - mean * mean);
        assertEquals(expected, sd, tol, what + " daily move sd " + sd);
        assertTrue(maxAbs <= 0.08, what + " never past 8%: " + maxAbs);
    }

    // ---- envelopes ------------------------------------------------------------------------

    @Test
    void theStoryEnvelopeRampsHoldsAndFades() {
        long ramp = 4 * H;
        long hold = 30 * H;
        long fade = 10 * H;
        long t1 = T0 + ramp + hold;
        assertEquals(0.0, SimMath.storyEnv(T0, ramp, hold, fade, T0 - 1));
        assertEquals(0.0, SimMath.storyEnv(T0, ramp, hold, fade, T0), "0 at t0: the rise is silent");
        assertEquals(0.5, SimMath.storyEnv(T0, ramp, hold, fade, T0 + 2 * H), "0.5 at mid-ramp");
        assertEquals(1.0, SimMath.storyEnv(T0, ramp, hold, fade, T0 + ramp), "full at the end of the ramp");
        assertEquals(1.0, SimMath.storyEnv(T0, ramp, hold, fade, t1 - 1), "1 through the hold");
        assertEquals(1.0, SimMath.storyEnv(T0, ramp, hold, fade, t1));
        assertEquals(0.5, SimMath.storyEnv(T0, ramp, hold, fade, t1 + 5 * H), "0.5 at mid-fade");
        assertEquals(0.0, SimMath.storyEnv(T0, ramp, hold, fade, t1 + fade), "0 at ends_at");
        assertEquals(1.0, SimMath.storyEnv(T0, 0, hold, fade, T0), "ramp 0 is full at once");
        assertEquals(0.0, SimMath.storyEnv(T0, 0, hold, 0, T0 + hold), "fade 0 ends at once");

        MarketEvent hot = MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, T0, ramp, hold, fade);
        assertEquals(0.5, SimMath.storyEnv(hot, T0 + 2 * H));
        assertEquals(t1 + fade, hot.endsAt());
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.2, T0, 6 * H, 30 * H);
        assertEquals(0.0, SimMath.storyEnv(up, T0), "storyEnv is for HOT/DEAL only");
    }

    @Test
    void aStopFadesToZeroWithinTheHour() {
        MarketEvent hot = MarketEvent.story(EventKind.DEAL, Source.SIM, "iron_ingot", -0.1, T0, 4 * H, 30 * H, 10 * H);
        long ts = T0 + 10 * H;
        MarketEvent stopped = hot.withStop(ts, "stopped");
        assertEquals(ts + H, stopped.endsAt());
        assertEquals(1.0, SimMath.envelope(stopped, ts), "no jump at the stop");
        assertEquals(0.5, SimMath.envelope(stopped, ts + H / 2), 1e-12);
        assertEquals(0.0, SimMath.envelope(stopped, ts + H));
        assertEquals(1.0, SimMath.stopFade(ts, ts - 1));
        assertEquals(0.5, SimMath.stopFade(ts, ts + H / 2), 1e-12);
        assertEquals(0.0, SimMath.stopFade(ts, ts + 2 * H));

        // Stopped during the ramp: it fades from where it had got to, never up.
        MarketEvent early = hot.withStop(T0 + 2 * H, "stopped");
        assertEquals(0.5, SimMath.envelope(early, T0 + 2 * H));
        assertTrue(SimMath.envelope(early, T0 + 2 * H + 1) <= 0.5);
        assertEquals(0.0, SimMath.envelope(early, T0 + 3 * H));

        // Stopped in the natural fade: never stronger and never longer than unstopped.
        long t1 = hot.holdEndsAt();
        MarketEvent late = hot.withStop(t1 + 9 * H + 30 * 60_000L, "stopped");
        assertEquals(hot.endsAt(), late.endsAt(), "a stop never extends the row");
        for (long t = t1 + 9 * H; t < hot.endsAt() + H; t += 60_000L) {
            assertTrue(SimMath.envelope(late, t) <= SimMath.envelope(hot, t) + 1e-15);
        }
    }

    @Test
    void theNewsShockIsInstantThenDecays() {
        assertEquals(1.0, SimMath.shock(T0, T0, 6, 30), "instant: full size the moment it fires");
        assertEquals(0.4839, SimMath.shock(T0, T0 + 6 * H, 6, 30), 1e-4);
        assertEquals(0.2258, SimMath.shock(T0, T0 + 12 * H, 6, 30), 1e-4);
        assertEquals(0.0323, SimMath.shock(T0, T0 + 24 * H, 6, 30), 1e-4);
        assertEquals(0.0, SimMath.shock(T0, T0 + 30 * H, 6, 30), "gone at 30 h");
        assertEquals(0.0, SimMath.shock(T0, T0 - 1, 6, 30));
        assertTrue(SimMath.shock(T0, T0 + 30 * H - 1, 6, 30) >= 0.0);

        assertEquals(1.0, SimMath.shock(T0, T0, 24, 96));
        assertEquals(0.4667, SimMath.shock(T0, T0 + 24 * H, 24, 96), 1e-4, "REAL at 24 h");
        assertEquals(0.2, SimMath.shock(T0, T0 + 48 * H, 24, 96), 1e-12, "REAL at 48 h");
        assertEquals(0.0, SimMath.shock(T0, T0 + 96 * H, 24, 96));
        assertEquals(0.0, SimMath.shock(T0, T0, 0, 30), "no half-life, no shock");

        double prev = 2;
        for (long t = T0; t <= T0 + 30 * H; t += 5 * 60_000L) {
            double k = SimMath.shock(T0, t, 6, 30);
            assertTrue(k <= prev && k >= 0, "monotone");
            prev = k;
        }
    }

    @Test
    void theNewsBadgeEndsAboutTwelveHoursAfterATwentyTwoPercentFlash() {
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.22, T0, 6 * H, 30 * H);
        double hours = (up.badgeEndsAt() - T0) / (double) H;
        assertEquals(11.95, hours, 0.01);
        assertTrue(Math.abs(up.contribution(up.badgeEndsAt() - 60_000L)) >= SimMath.NEWS_BADGE_MIN);
        assertTrue(Math.abs(up.contribution(up.badgeEndsAt() + 60_000L)) < SimMath.NEWS_BADGE_MIN);

        MarketEvent tiny = MarketEvent.shock(EventKind.DOWN, Source.SIM, "wheat", -0.04, T0, 6 * H, 30 * H);
        assertEquals(T0, tiny.badgeEndsAt(), "under 5% never shows a badge");
        MarketEvent hot = MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", 0.12, T0, 4 * H, 30 * H, 10 * H);
        assertEquals(hot.endsAt(), hot.badgeEndsAt(), "HOT keeps its badge through the fade");
    }

    // ---- headroom, direction, price -------------------------------------------------------

    @Test
    void headroomStopsAtTheBandAndTheCeilingAndFloor() {
        // Iron at its shipped balanced price: the band binds both ways.
        assertEquals(0.25, SimMath.headroomUp(22.49, 4, 40, 1.0, 1.25), 1e-12);
        assertEquals(0.25, SimMath.headroomDown(22.49, 4, 40, 1.0, 0.75), 1e-12);
        assertEquals(0.25, SimMath.headroomUp(22.49, 4, 40, 1.0, 9.0), 1e-12, "a wider band is held at 1.25");
        assertEquals(0.25, SimMath.headroomDown(22.49, 4, 40, 1.0, 0.1), 1e-12, "a wider band is held at 0.75");
        assertEquals(0.20, SimMath.headroomUp(22.49, 4, 40, 1.05, 1.25), 1e-12, "the current mood counts");

        // Near the ceiling the ceiling binds; at it there is no room at all.
        assertEquals(40.0 / 36.0 - 1.0, SimMath.headroomUp(36, 4, 40, 1.0, 1.25), 1e-12);
        assertEquals(0.0, SimMath.headroomUp(40, 4, 40, 1.0, 1.25));
        assertEquals(0.0, SimMath.headroomDown(4, 4, 40, 1.0, 0.75), "at the floor there is no way down");
        assertEquals(0.0, SimMath.headroomUp(22.49, 4, 40, 1.3, 1.25), "never negative");
        assertEquals(0.0, SimMath.headroomUp(0, 4, 40, 1.0, 1.25), "no balanced price, no room");
    }

    @Test
    void pUpLeansBackTowardTheBalancedPrice() {
        assertEquals(0.5, SimMath.pUp(0.0));
        assertEquals(0.4, SimMath.pUp(0.05), 1e-12);
        assertEquals(0.6, SimMath.pUp(-0.05), 1e-12);
        assertEquals(0.2, SimMath.pUp(0.25), 1e-12);
        assertEquals(0.2, SimMath.pUp(1.0), "clamped at 0.2");
        assertEquals(0.8, SimMath.pUp(-1.0), "clamped at 0.8");
        assertEquals(0.5, SimMath.pUp(Double.NaN));
    }

    @Test
    void theDisplayedPriceIsClampedAndAnEmptyItemSitsAtItsCeiling() {
        assertEquals(22.49, SimMath.displayPrice(22.49, 512, 1.0, 4, 40), "M = 1 is the balanced price, bit for bit");
        assertEquals(22.49 * 1.2, SimMath.displayPrice(22.49, 512, 1.2, 4, 40), 1e-12);
        assertEquals(22.49 * 1.25, SimMath.displayPrice(22.49, 512, 3.0, 4, 40), 1e-12, "M held at 1.25");
        assertEquals(22.49 * 0.75, SimMath.displayPrice(22.49, 512, 0.1, 4, 40), 1e-12, "M held at 0.75");
        assertEquals(40.0, SimMath.displayPrice(36, 512, 1.25, 4, 40), "never above the ceiling");
        assertEquals(4.0, SimMath.displayPrice(4.5, 512, 0.75, 4, 40), "never below the floor");
        for (double m : new double[]{0.75, 0.9, 1.0, 1.1, 1.25}) {
            assertEquals(150.0, SimMath.displayPrice(150, 0, m, 20, 150), "stock 0 is the ceiling at M = " + m);
        }
        assertEquals(10.0, SimMath.pct(11, 10), 1e-12);
        assertEquals(-25.0, SimMath.pct(7.5, 10), 1e-12);
        assertEquals(0.0, SimMath.pct(5, 0));
    }

    // ---- item params (§2.5) ---------------------------------------------------------------

    @Test
    void theShippedCatalogSplitsIntoCalmStaplesAndLivelyOres() {
        SimSettings s = SimSettings.defaults();
        assertCalm(ItemParams.of(item("cobblestone", Material.COBBLESTONE, "&7Cobblestone", 0.10, 5.0, 20000, 400, 800), null, s));
        assertCalm(ItemParams.of(item("oak_log", Material.OAK_LOG, "&6Oak Log", 1, 20, 8000, 160, 320), null, s));
        assertCalm(ItemParams.of(item("wheat", Material.WHEAT, "&eWheat", 1, 12, 6000, 120, 240), null, s));
        assertLively(ItemParams.of(item("iron_ingot", Material.IRON_INGOT, "&fIron Ingot", 4, 40, 2048, 40, 80), null, s));
        assertLively(ItemParams.of(item("gold_ingot", Material.GOLD_INGOT, "&6Gold Ingot", 20, 150, 1024, 20, 40), null, s));
        assertLively(ItemParams.of(item("diamond", Material.DIAMOND, "&bDiamond", 60, 400, 512, 10, 20), null, s));
    }

    private static void assertCalm(ItemParams p) {
        assertFalse(p.lively(), p.id());
        assertEquals(0.015, p.sigma(), 1e-15, p.id());
    }

    private static void assertLively(ItemParams p) {
        assertTrue(p.lively(), p.id());
        assertEquals(0.03, p.sigma(), 1e-15, p.id());
    }

    @Test
    void itemNamesLimitsAndPlurals() {
        SimSettings s = SimSettings.defaults();
        ItemParams cobble = ItemParams.of(item("cobblestone", Material.COBBLESTONE, "&7Cobblestone", 0.10, 5.0, 20000, 400, 800), null, s);
        assertEquals("Cobblestone", cobble.name(), "colour codes stripped");
        assertEquals("Cobblestone", cobble.plural(), "same_plural keeps it");
        assertTrue(cobble.movable());

        ItemParams iron = ItemParams.of(item("iron_ingot", Material.IRON_INGOT, null, 4, 40, 2048, 40, 80), null, s);
        assertEquals("Iron Ingot", iron.name(), "a raw material label is title-cased");
        assertEquals("Iron Ingots", iron.plural());
        assertEquals(40, iron.eventBuyCap(0.5), "iron: 40 a day on a DEAL");
        assertEquals(40, iron.eventSellCap());

        ItemParams oak = ItemParams.of(item("oak_log", Material.OAK_LOG, "&6Oak Log", 1, 20, 8000, 160, 320), null, s);
        assertEquals(160, oak.eventBuyCap(0.5), "oak: 160 a day on a DEAL");
        assertEquals("Oak Logs", oak.plural());

        ItemParams uncapped = ItemParams.of(item("stone", Material.STONE, "Stone", 1, 5, 1000, 0, 0), null, s);
        assertEquals(20, uncapped.eventBuyCap(0.5), "no max_daily_buy: half of ceil(4% of full_stock)");
        assertEquals(20, uncapped.eventSellCap(), "no max_daily_sell: ceil(2% of full_stock)");
        assertEquals(29, ItemParams.of(item("x", Material.STONE, "X", 1, 5, 1000, 0, 100), null, s).eventBuyCap(0.29),
                "no floating-point off-by-one");
        assertEquals(1, uncapped.eventBuyCap(0.0), "never below one a day");

        ItemParams fixed = ItemParams.of(item("glass", Material.GLASS, "Glass", 5, 5, 100, 0, 0), null, s);
        assertFalse(fixed.movable(), "floor == ceiling cannot move");
    }

    private static MarketItem item(String id, Material m, String label, double f, double c, long full,
                                   long maxSell, long maxBuy) {
        return new MarketItem(id, m, label, f, c, full / 2, full, maxSell, maxBuy);
    }
}
