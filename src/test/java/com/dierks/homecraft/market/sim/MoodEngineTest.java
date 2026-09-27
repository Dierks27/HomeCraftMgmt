package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.MarketItem;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How one item's multiplier is composed and what players see (spec §2.1, §5.1, §7.1).
 *
 * <p>Pins: with no drift, no events and no season {@code M} is exactly {@code 1.0}; a
 * {@code sim: false} item, or the sim switched off, is exactly {@code 1.0} whatever its parts;
 * every part at its own maximum (drift 8% + HOT 15% + UP 25% + season 4.5%) still gives exactly
 * {@code 1.25}, and the mirror exactly {@code 0.75}; a config asking for 50% changes nothing, a
 * narrower one is honoured; season + real are clamped together to {@code c_pred} (4.5%, less on
 * a narrower spread) while REAL stays out of the event sum; at stock 0 the displayed price is
 * the ceiling whatever {@code M} is. Badges: UP/DOWN beat HOT/DEAL beat WANTED, a ramping
 * HOT/DEAL shows nothing, a fading one says so, WANTED follows stock.
 */
class MoodEngineTest {

    private static final long H = SimMath.HOUR_MS;
    private static final long T0 = 1_790_000_000_000L;
    private static final double SPREAD = 0.10;
    private static final SimSettings S = SimSettings.defaults();

    private static final MarketItem IRON = new MarketItem("iron_ingot", Material.IRON_INGOT, "&fIron Ingot",
            4.0, 40.0, 512, 2048, 40, 80);
    private static final ItemParams P = ItemParams.of(IRON, ItemOverride.NONE, S);
    private static final ItemParams OFF = ItemParams.of(IRON, new ItemOverride(false, null, null, null), S);

    private static MarketEvent hot(double a, long t0) {
        return MarketEvent.story(EventKind.HOT, Source.SIM, "iron_ingot", a, t0, 4 * H, 30 * H, 10 * H);
    }

    private static MarketEvent deal(double a, long t0) {
        return MarketEvent.story(EventKind.DEAL, Source.SIM, "iron_ingot", a, t0, 4 * H, 30 * H, 10 * H);
    }

    private static MarketEvent up(double a, long t0) {
        return MarketEvent.shock(EventKind.UP, Source.SIM, "iron_ingot", a, t0, 6 * H, 30 * H);
    }

    private static MarketEvent down(double a, long t0) {
        return MarketEvent.shock(EventKind.DOWN, Source.SIM, "iron_ingot", a, t0, 6 * H, 30 * H);
    }

    private static MarketEvent real(double r, long t0) {
        return MarketEvent.shock(EventKind.REAL, Source.REAL, "iron_ingot", r, t0, 24 * H, 96 * H);
    }

    private static SimSettings band(double maxUp, double maxDown) {
        SimSettings d = SimSettings.defaults();
        return new SimSettings(true, d.tickMinutes(), d.maxCatchupHours(), maxUp, maxDown, d.keepDays(), d.drift(),
                d.hot(), d.deal(), d.slotsPerItems(), d.cooldownDays(), d.popularWeight(), d.news(), d.announce(),
                d.headlines(), d.samePlural(), d.seasons(), d.real());
    }

    // ---- composing M ------------------------------------------------------------------------

    @Test
    void nothingGoingOnIsExactlyOne() {
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.0, List.of(), 0.0, T0, S, SPREAD);
        assertEquals(1.0, b.multiplier());
        assertEquals(1.0, b.raw());
        assertTrue(b.active());
        assertEquals(1.0, MoodEngine.breakdown(P, 0.0, null, 0.0, T0, S, SPREAD).multiplier());
        // Events that are over, or not started yet, add nothing.
        List<MarketEvent> gone = List.of(hot(0.12, T0 - 60 * H), up(0.2, T0 + H));
        assertEquals(1.0, MoodEngine.breakdown(P, 0.0, gone, 0.0, T0, S, SPREAD).multiplier());
    }

    @Test
    void aSimFalseItemOrTheSimSwitchedOffIsExactlyOne() {
        List<MarketEvent> busy = List.of(hot(0.15, T0 - 10 * H), real(0.04, T0));
        MoodEngine.Breakdown off = MoodEngine.breakdown(OFF, 0.08, busy, 0.045, T0, S, SPREAD);
        assertEquals(1.0, off.multiplier());
        assertFalse(off.active());
        MoodEngine.Breakdown disabled = MoodEngine.breakdown(P, -0.08, busy, -0.045, T0, S.withEnabled(false), SPREAD);
        assertEquals(1.0, disabled.multiplier());
        assertFalse(disabled.active());
        assertEquals(0.08, off.drift(), "the parts are still reported, for status screens");
    }

    @Test
    void thePartsAddUpLinearly() {
        MarketEvent h = hot(0.12, T0 - 10 * H);
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.02, List.of(h), -0.01, T0, S, SPREAD);
        assertEquals(0.02, b.drift());
        assertEquals(0.12, b.story(), 1e-15);
        assertEquals(0.0, b.news());
        assertEquals(-0.01, b.predictable(), 1e-15);
        assertEquals(1.0 + 0.02 + 0.12 - 0.01, b.raw(), 1e-15);
        assertEquals(b.raw(), b.multiplier(), "inside the band nothing is clamped");
        assertEquals(0.12, b.events(), 1e-15);
    }

    @Test
    void everyPartAtItsMaximumIsStillExactlyTheHardBand() {
        List<MarketEvent> up = List.of(hot(0.15, T0 - 10 * H), up(0.25, T0));
        MoodEngine.Breakdown hi = MoodEngine.breakdown(P, 0.08, up, 0.045, T0, S, SPREAD);
        assertEquals(1.0 + 0.08 + 0.15 + 0.25 + 0.045, hi.raw(), 1e-12);
        assertEquals(1.25, hi.multiplier());

        List<MarketEvent> down = List.of(deal(0.15, T0 - 10 * H), down(0.25, T0));
        MoodEngine.Breakdown lo = MoodEngine.breakdown(P, -0.08, down, -0.045, T0, S, SPREAD);
        assertEquals(0.75, lo.multiplier());

        // Out-of-range inputs are held too: a drift of 50% counts as the 8% bound.
        MoodEngine.Breakdown wild = MoodEngine.breakdown(P, 0.5, List.of(), 0.0, T0, S, SPREAD);
        assertEquals(0.08, wild.drift());
        assertEquals(1.08, wild.multiplier(), 1e-15);
        assertEquals(1.0, MoodEngine.breakdown(P, Double.NaN, List.of(), Double.NaN, T0, S, SPREAD).multiplier());
    }

    /**
     * Config can narrow the band, never widen it, and narrowing is always symmetric: the band is
     * {@code [1 - b, 1 + b]} with {@code b} the narrower of max_up_percent and max_down_percent,
     * so narrowing one side narrows both and the mood can never lean one way.
     */
    @Test
    void configCanNarrowTheBandButNeverWidenIt() {
        List<MarketEvent> up = List.of(hot(0.15, T0 - 10 * H), up(0.25, T0));
        List<MarketEvent> down = List.of(deal(0.15, T0 - 10 * H), down(0.25, T0));
        SimSettings wide = band(50, 90);
        assertEquals(1.25, MoodEngine.breakdown(P, 0.08, up, 0.045, T0, wide, SPREAD).multiplier());
        assertEquals(0.75, MoodEngine.breakdown(P, -0.08, down, -0.045, T0, wide, SPREAD).multiplier());
        SimSettings narrow = band(10, 5);
        assertEquals(1.05, MoodEngine.breakdown(P, 0.08, up, 0.045, T0, narrow, SPREAD).multiplier(), 1e-15,
                "max_down 5 narrows the top too");
        assertEquals(0.95, MoodEngine.breakdown(P, -0.08, down, -0.045, T0, narrow, SPREAD).multiplier(), 1e-15);
        SimSettings mirrored = band(5, 10);
        assertEquals(1.05, MoodEngine.breakdown(P, 0.08, up, 0.045, T0, mirrored, SPREAD).multiplier(), 1e-15);
        assertEquals(0.95, MoodEngine.breakdown(P, -0.08, down, -0.045, T0, mirrored, SPREAD).multiplier(), 1e-15,
                "max_up 5 narrows the bottom too");
        // One side at 0 is no mood at all, never a one-way one.
        for (SimSettings oneSided : List.of(band(25, 0), band(0, 25))) {
            assertEquals(1.0, MoodEngine.breakdown(P, 0.08, up, 0.045, T0, oneSided, SPREAD).multiplier());
            assertEquals(1.0, MoodEngine.breakdown(P, -0.08, down, -0.045, T0, oneSided, SPREAD).multiplier());
        }
        // A wide side never widens the narrow one past the hard band either.
        SimSettings half = band(90, 25);
        assertEquals(1.25, MoodEngine.breakdown(P, 0.08, up, 0.045, T0, half, SPREAD).multiplier());
        assertEquals(0.75, MoodEngine.breakdown(P, -0.08, down, -0.045, T0, half, SPREAD).multiplier());
    }

    @Test
    void seasonAndRealAreClampedTogetherAndRealIsNotAnEvent() {
        MarketEvent r = real(0.04, T0);
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.0, List.of(r), 0.04, T0, S, SPREAD);
        assertEquals(0.04, b.real(), 1e-15);
        assertEquals(0.04, b.season(), 1e-15);
        assertEquals(0.045, b.predictable(), 1e-15, "c_pred = min(4.5%, 0.45 x 10%)");
        assertEquals(0.0, b.events(), "REAL belongs to q, never to e");
        assertEquals(1.045, b.multiplier(), 1e-15);

        MoodEngine.Breakdown mirror = MoodEngine.breakdown(P, 0.0, List.of(real(-0.04, T0)), -0.04, T0, S, SPREAD);
        assertEquals(-0.045, mirror.predictable(), 1e-15);

        // A narrower spread narrows the cap: 0.45 x 5% = 2.25%.
        assertEquals(0.0225, MoodEngine.breakdown(P, 0.0, List.of(r), 0.04, T0, S, 0.05).predictable(), 1e-15);
        // No spread, no predictable layer at all.
        assertEquals(0.0, MoodEngine.breakdown(P, 0.0, List.of(r), 0.04, T0, S, 0.0).predictable());
        // A hand-made 10% season is still held to the cap.
        assertEquals(0.045, MoodEngine.breakdown(P, 0.0, List.of(), 0.10, T0, S, SPREAD).predictable(), 1e-15);
        // REAL decays: half its size a half-life later (plus the tail offset of k(tau)).
        double k24 = SimMath.shock(T0, T0 + 24 * H, 24, 96);
        assertEquals(0.04 * k24, MoodEngine.breakdown(P, 0.0, List.of(r), 0.0, T0 + 24 * H, S, SPREAD).real(), 1e-15);
    }

    // ---- what players see -------------------------------------------------------------------

    @Test
    void anEmptyItemSitsAtItsCeilingWhateverTheMultiplier() {
        for (double drift : new double[]{-0.08, 0.0, 0.08}) {
            MoodEngine.Breakdown b = MoodEngine.breakdown(P, drift, List.of(), drift < 0 ? -0.045 : 0.045, T0, S, SPREAD);
            ItemStatus st = MoodEngine.status(P, b, List.of(), 40.0, 0, T0);
            assertEquals(40.0, st.price(), "price at stock 0 is the ceiling");
            assertEquals(40.0, st.usual());
            assertEquals(0.0, st.pct());
            assertEquals(Badge.WANTED, st.badge(), "Crate has none: WANTED");
            assertEquals(b.multiplier(), st.multiplier());
        }
        // One unit in stock and the multiplier applies again.
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, -0.05, List.of(), 0.0, T0, S, SPREAD);
        ItemStatus st = MoodEngine.status(P, b, List.of(), 39.0, 1, T0);
        assertEquals(39.0 * 0.95, st.price(), 1e-12);
        assertEquals(Badge.NONE, st.badge());
    }

    @Test
    void thePriceIsTheBalancedPriceTimesMInsideTheBand() {
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.0, List.of(hot(0.15, T0 - 10 * H)), 0.0, T0, S, SPREAD);
        ItemStatus st = MoodEngine.status(P, b, List.of(hot(0.15, T0 - 10 * H)), 22.49, 512, T0);
        assertEquals(22.49 * 1.15, st.price(), 1e-12);
        assertEquals(15.0, st.pct(), 1e-9);
        assertEquals(22.49, st.usual());
        // Near the ceiling the band wins: 38 x 1.15 would be 43.70.
        ItemStatus capped = MoodEngine.status(P, b, List.of(), 38.0, 5, T0);
        assertEquals(40.0, capped.price());
        // A balanced price outside the band is read as the band's edge.
        assertEquals(4.0, MoodEngine.status(P, MoodEngine.Breakdown.NEUTRAL, List.of(), 1.0, 2000, T0).usual());
    }

    @Test
    void badgesFollowTheirPrecedence() {
        long t = T0 + 10 * H;
        MarketEvent h = hot(0.12, T0);
        MarketEvent u = up(0.22, t - H);
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.0, List.of(h, u), 0.0, t, S, SPREAD);
        ItemStatus both = MoodEngine.status(P, b, List.of(h, u), 22.49, 512, t);
        assertEquals(Badge.UP, both.badge(), "UP/DOWN beat HOT/DEAL");
        assertSame(u, both.event());
        assertEquals(u.badgeEndsAt(), both.endsAt());
        assertEquals(Phase.BADGE, both.phase());

        ItemStatus onlyHot = MoodEngine.status(P, b, List.of(h), 22.49, 512, t);
        assertEquals(Badge.HOT, onlyHot.badge());
        assertEquals(h.endsAt(), onlyHot.endsAt());
        assertEquals(Phase.FULL, onlyHot.phase());
        assertFalse(onlyHot.fading());

        // HOT/DEAL beat WANTED even at stock 0 (a DEAL that just sold out fades with its badge).
        MarketEvent soldOut = deal(0.12, T0).withStop(t, MarketSimulator.STOP_SOLD_OUT);
        ItemStatus dealAtZero = MoodEngine.status(P, b, List.of(soldOut), 40.0, 0, t + 30 * SimMath.MINUTE_MS);
        assertEquals(Badge.DEAL, dealAtZero.badge());
        assertTrue(dealAtZero.fading());
    }

    @Test
    void aRampingStoryIsSilentAndAFadingOneSaysSo() {
        MarketEvent h = hot(0.12, T0);
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.0, List.of(h), 0.0, T0 + 2 * H, S, SPREAD);
        ItemStatus ramp = MoodEngine.status(P, b, List.of(h), 22.49, 512, T0 + 2 * H);
        assertEquals(Badge.NONE, ramp.badge(), "no badge while ramping");
        assertTrue(ramp.pct() > 0.0, "but the price is already rising");
        assertNull(ramp.event());

        long fading = h.holdEndsAt() + 2 * H;
        ItemStatus f = MoodEngine.status(P, MoodEngine.breakdown(P, 0.0, List.of(h), 0.0, fading, S, SPREAD),
                List.of(h), 22.49, 512, fading);
        assertEquals(Badge.HOT, f.badge());
        assertTrue(f.fading());

        // A flash below 5% has lost its badge: the TAIL.
        MarketEvent u = up(0.22, T0);
        long tail = u.badgeEndsAt() + H;
        ItemStatus t = MoodEngine.status(P, MoodEngine.breakdown(P, 0.0, List.of(u), 0.0, tail, S, SPREAD),
                List.of(u), 22.49, 512, tail);
        assertEquals(Badge.NONE, t.badge());
    }

    /**
     * Review #3: an event-cap refusal may name the event only once players can see it. The HOT's
     * cap binds from its first (silent) ramp tick, but the sell side reads as shown only from full
     * strength; the DEAL likewise on the buy side; an UP/DOWN only while its badge lasts. Each
     * side only answers for its own kinds.
     */
    @Test
    void anEventCapIsShownOnlyOnceItsEventsBadgeIs() {
        MarketEvent h = hot(0.12, T0);
        assertFalse(MoodEngine.capEventShown(List.of(h), true, T0 - H), "not started");
        assertFalse(MoodEngine.capEventShown(List.of(h), true, T0), "the silent ramp begins");
        assertFalse(MoodEngine.capEventShown(List.of(h), true, T0 + 3 * H), "still ramping");
        assertTrue(h.active(T0 + 3 * H), "(the cap binds all the same: the event is active)");
        assertTrue(MoodEngine.capEventShown(List.of(h), true, T0 + 4 * H), "full strength: announced");
        assertTrue(MoodEngine.capEventShown(List.of(h), true, h.holdEndsAt() + H), "fading still shows it");
        assertFalse(MoodEngine.capEventShown(List.of(h), true, h.endsAt()), "over");
        assertFalse(MoodEngine.capEventShown(List.of(h), false, T0 + 10 * H), "a HOT says nothing about the buy side");

        MarketEvent stoppedInRamp = hot(0.12, T0).withStop(T0 + 2 * H, "admin");
        assertTrue(stoppedInRamp.active(T0 + 2 * H + 10 * SimMath.MINUTE_MS));
        assertFalse(MoodEngine.capEventShown(List.of(stoppedInRamp), true, T0 + 2 * H + 10 * SimMath.MINUTE_MS),
                "stopped before it was ever announced: never shown");

        MarketEvent d = deal(0.12, T0);
        assertFalse(MoodEngine.capEventShown(List.of(d), false, T0 + H), "a ramping DEAL is silent");
        assertTrue(MoodEngine.capEventShown(List.of(d), false, T0 + 5 * H));
        assertFalse(MoodEngine.capEventShown(List.of(d), true, T0 + 5 * H), "a DEAL says nothing about the sell side");

        MarketEvent u = up(0.22, T0);
        assertTrue(MoodEngine.capEventShown(List.of(u), true, T0), "an UP is news the tick it fires");
        assertFalse(MoodEngine.capEventShown(List.of(u), true, u.badgeEndsAt() + H), "the badge-less tail");
        assertTrue(u.active(u.badgeEndsAt() + H), "(the tail still caps)");
        MarketEvent dn = down(0.22, T0);
        assertTrue(MoodEngine.capEventShown(List.of(dn), false, T0 + H));
        assertFalse(MoodEngine.capEventShown(List.of(dn), true, T0 + H));

        // A shown UP next to a ramping HOT: the sell side is shown (the UP's badge is up), and
        // naming "the price is up" gives nothing about the HOT away.
        assertTrue(MoodEngine.capEventShown(List.of(h, u), true, T0 + H));
        assertFalse(MoodEngine.capEventShown(null, true, T0));
        assertFalse(MoodEngine.capEventShown(List.of(), false, T0));
        assertFalse(MoodEngine.capEventShown(List.of(real(0.03, T0)), true, T0), "REAL never caps");
    }

    @Test
    void wantedFollowsStockAndNothingShowsWhenTheSimIsOff() {
        MarketEvent w = MarketEvent.info(EventKind.WANTED, Source.SIM, "iron_ingot", null, T0, T0 + 7 * 24 * H);
        MoodEngine.Breakdown b = MoodEngine.breakdown(P, 0.0, List.of(w), 0.0, T0 + H, S, SPREAD);
        ItemStatus zero = MoodEngine.status(P, b, List.of(w), 40.0, 0, T0 + H);
        assertEquals(Badge.WANTED, zero.badge());
        assertSame(w, zero.event(), "the live WANTED row, for its headline");
        assertEquals(0L, zero.endsAt());
        ItemStatus stocked = MoodEngine.status(P, b, List.of(w), 39.0, 3, T0 + H);
        assertEquals(Badge.NONE, stocked.badge(), "WANTED only while Crate has none");

        MoodEngine.Breakdown off = MoodEngine.breakdown(P, 0.0, List.of(), 0.0, T0, S.withEnabled(false), SPREAD);
        assertEquals(Badge.NONE, MoodEngine.status(P, off, List.of(), 40.0, 0, T0).badge());
        MoodEngine.Breakdown simFalse = MoodEngine.breakdown(OFF, 0.0, List.of(hot(0.1, T0 - 10 * H)), 0.0, T0, S, SPREAD);
        ItemStatus none = MoodEngine.status(OFF, simFalse, List.of(hot(0.1, T0 - 10 * H)), 22.49, 512, T0);
        assertEquals(Badge.NONE, none.badge());
        assertEquals(22.49, none.price(), "exactly the usual price");
        assertEquals(ItemStatus.plain(22.49).price(), none.price());
    }
}
