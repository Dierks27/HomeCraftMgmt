package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code market_events} row and everything derived from it.
 *
 * <p>Pins: a row can never carry a bigger move than the code allows (HOT/DEAL 15%, UP/DOWN
 * 25%, REAL 4.5%) or the wrong sign for its kind; the phase machine of §5.4 (RAMP is silent,
 * FULL/FADING badged, a stop in the ramp stays silent, UP/DOWN badged while at least 5%,
 * then TAIL, info rows FIRED); the price contribution only counts HOT/DEAL/UP/DOWN — REAL is
 * kept apart for the capped predictable layer; stops fade over an hour, ends are immediate,
 * and neither can extend or restart a row; the builder and the {@code with*} copies change
 * exactly one thing.
 */
class MarketEventTest {

    private static final long H = SimMath.HOUR_MS;
    private static final long T0 = 1_790_000_000_000L;

    private static MarketEvent hot(double strength) {
        return MarketEvent.story(EventKind.HOT, Source.SIM, "oak_log", strength, T0, 4 * H, 30 * H, 10 * H);
    }

    @Test
    void strengthIsHeldToTheCodeLimitsWithTheKindsSign() {
        assertEquals(0.15, hot(0.40).strength(), "HOT at most 15%");
        assertEquals(0.12, hot(-0.12).strength(), "HOT is always up");
        assertEquals(-0.15, MarketEvent.story(EventKind.DEAL, Source.ADMIN, "x", 0.3, T0, 0, H, H).strength(),
                "DEAL is always down, at most 15%");
        assertEquals(0.25, MarketEvent.shock(EventKind.UP, Source.SIM, "x", 0.6, T0, 6 * H, 30 * H).strength());
        assertEquals(-0.25, MarketEvent.shock(EventKind.DOWN, Source.SIM, "x", 0.6, T0, 6 * H, 30 * H).strength());
        assertEquals(-0.045, MarketEvent.shock(EventKind.REAL, Source.REAL, "x", -0.2, T0, 24 * H, 96 * H).strength());
        assertEquals(0.03, MarketEvent.shock(EventKind.REAL, Source.REAL, "x", 0.03, T0, 24 * H, 96 * H).strength());
        assertEquals(0.0, hot(Double.NaN).strength());

        // Whatever the envelope, the contribution never passes the limit.
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "x", 9, T0, 6 * H, 30 * H);
        assertEquals(0.25, up.contribution(T0));
    }

    @Test
    void factoriesFillTheScheduleColumns() {
        MarketEvent e = hot(0.12);
        assertEquals(0L, e.id(), "not inserted yet");
        assertEquals(T0 + 44 * H, e.endsAt());
        assertEquals(T0 + 34 * H, e.holdEndsAt());
        assertEquals(T0 + 4 * H, e.announceDueAt(), "announced at full strength, not at the start");
        assertEquals(e.endsAt(), e.naturalEndsAt());
        assertNull(e.stoppedAt());
        assertFalse(e.announced());

        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.22, T0, 6 * H, 30 * H);
        assertEquals(T0 + 30 * H, up.endsAt());
        assertEquals(T0, up.announceDueAt(), "news is announced in the tick it fires");

        MarketEvent wanted = MarketEvent.info(EventKind.WANTED, Source.SIM, "diamond", null, T0, T0);
        assertEquals(Phase.OVER, wanted.phase(T0), "an instant flash");
        assertEquals(0.0, wanted.contribution(T0));
        MarketEvent season = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, "harvest_time:2026",
                T0, T0 + 30 * 24 * H);
        assertEquals(Phase.FIRED, season.phase(T0 + H));
        assertEquals(Badge.NONE, season.badge(T0 + H));
        assertEquals(0.0, season.contribution(T0 + H), "a season row carries no price effect");

        assertThrows(IllegalArgumentException.class,
                () -> MarketEvent.story(EventKind.UP, Source.SIM, "x", 0.1, T0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MarketEvent.shock(EventKind.HOT, Source.SIM, "x", 0.1, T0, H, H));
        assertThrows(IllegalArgumentException.class,
                () -> MarketEvent.info(EventKind.DEAL, Source.SIM, "x", null, T0, T0));
        assertThrows(NullPointerException.class, () -> MarketEvent.builder(null, Source.SIM).build());
    }

    @Test
    void aHotWalksRampFullFadingOver() {
        MarketEvent e = hot(0.12);
        assertEquals(Phase.PENDING, e.phase(T0 - 1));
        assertEquals(Phase.RAMP, e.phase(T0));
        assertEquals(Badge.NONE, e.badge(T0 + 2 * H), "the ramp is silent: no badge");
        assertTrue(e.active(T0 + 2 * H), "...but limits already apply");
        assertEquals(0.06, e.contribution(T0 + 2 * H), 1e-12, "half way up at mid-ramp");

        assertEquals(Phase.FULL, e.phase(T0 + 4 * H));
        assertEquals(Badge.HOT, e.badge(T0 + 4 * H));
        assertEquals(0.12, e.contribution(T0 + 20 * H), 1e-15);

        assertEquals(Phase.FADING, e.phase(T0 + 34 * H));
        assertEquals(Badge.HOT, e.badge(T0 + 40 * H), "badge stays while cooling off");
        assertEquals(0.06, e.contribution(T0 + 39 * H), 1e-12);

        assertEquals(Phase.OVER, e.phase(T0 + 44 * H));
        assertFalse(e.active(T0 + 44 * H));
        assertEquals(0.0, e.contribution(T0 + 44 * H));
        assertEquals(Badge.NONE, e.badge(T0 + 44 * H));

        MarketEvent deal = MarketEvent.story(EventKind.DEAL, Source.SIM, "iron_ingot", 0.1, T0, 0, 30 * H, 10 * H);
        assertEquals(Phase.FULL, deal.phase(T0), "ramp 0 (admin): full at once");
        assertEquals(Badge.DEAL, deal.badge(T0));
        assertEquals(-0.1, deal.contribution(T0));
    }

    @Test
    void aNewsFlashIsBadgedThenTailsThenEnds() {
        MarketEvent up = MarketEvent.shock(EventKind.UP, Source.SIM, "wheat", 0.22, T0, 6 * H, 30 * H);
        assertEquals(0.22, up.contribution(T0), "the whole jump in the tick it fires");
        assertEquals(Phase.BADGE, up.phase(T0));
        assertEquals(Badge.UP, up.badge(T0));
        assertEquals(Phase.BADGE, up.phase(T0 + 11 * H));
        assertEquals(Phase.TAIL, up.phase(T0 + 13 * H), "under 5%: no badge");
        assertEquals(Badge.NONE, up.badge(T0 + 13 * H));
        assertTrue(up.active(T0 + 29 * H), "limits still apply in the tail");
        assertEquals(Phase.OVER, up.phase(T0 + 30 * H));

        MarketEvent down = MarketEvent.shock(EventKind.DOWN, Source.ADMIN, "iron_ingot", 0.18, T0, 6 * H, 30 * H);
        assertEquals(-0.18, down.contribution(T0));
        assertEquals(Badge.DOWN, down.badge(T0 + H));
    }

    @Test
    void realMovesAreKeptOutOfTheEventSum() {
        MarketEvent real = MarketEvent.shock(EventKind.REAL, Source.REAL, "gold_ingot", 0.03, T0, 24 * H, 96 * H);
        assertEquals(0.0, real.contribution(T0), "REAL belongs to the capped predictable layer");
        assertEquals(0.03, real.realContribution(T0));
        assertEquals(0.03 * 0.2, real.realContribution(T0 + 48 * H), 1e-12);
        assertEquals(0.0, real.realContribution(T0 + 96 * H));
        assertEquals(Phase.FIRED, real.phase(T0 + H));
        assertEquals(Badge.NONE, real.badge(T0 + H));
        assertEquals(0.0, hot(0.1).realContribution(T0 + 10 * H));
    }

    @Test
    void stopsFadeForAnHourAndEndsAreImmediate() {
        MarketEvent e = hot(0.12);
        long ts = T0 + 10 * H;
        MarketEvent stopped = e.withStop(ts, "stopped");
        assertEquals(Long.valueOf(ts), stopped.stoppedAt());
        assertEquals("stopped", stopped.stopReason());
        assertEquals(ts + H, stopped.endsAt());
        assertEquals(Phase.FADING, stopped.phase(ts), "a stop after the ramp cools off, badged");
        assertEquals(0.12, stopped.contribution(ts), 1e-15, "no jump at the stop");
        assertEquals(0.06, stopped.contribution(ts + H / 2), 1e-12);
        assertEquals(Phase.OVER, stopped.phase(ts + H));

        MarketEvent ended = e.withEnd(ts, "reset");
        assertEquals(ts, ended.endsAt());
        assertEquals(0.0, ended.contribution(ts), "gone at once");
        assertEquals(Phase.OVER, ended.phase(ts));

        MarketEvent early = e.withStop(T0 + H, "stopped");
        assertEquals(Phase.RAMP, early.phase(T0 + H + 1), "stopped before it was ever announced: stays silent");
        assertEquals(Badge.NONE, early.badge(T0 + H + 1));
    }

    @Test
    void aStopCanNeverExtendOrRestartARow() {
        MarketEvent e = hot(0.12);
        assertSame(e, e.withStop(e.endsAt(), "stopped"), "already over: nothing to stop");
        assertSame(e, e.withEnd(e.endsAt() + H, "reset"));

        long ts = T0 + 10 * H;
        MarketEvent stopped = e.withStop(ts, "stopped");
        MarketEvent again = stopped.withStop(ts + H / 2, "sold_out");
        assertEquals(stopped.endsAt(), again.endsAt(), "a later stop cannot push the end out");
        assertEquals("stopped", again.stopReason(), "the first stop's reason is kept");
        assertEquals(Long.valueOf(ts), again.stoppedAt());

        MarketEvent reset = stopped.withEnd(ts + H / 4, "reset");
        assertEquals(ts + H / 4, reset.endsAt(), "an end during the stop fade cuts it short");
        assertEquals(Long.valueOf(ts), reset.stoppedAt(), "...and keeps when the fade began");
        assertEquals(0.0, reset.contribution(ts + H / 4));

        // In the natural fade, a stop never lasts past the natural end.
        MarketEvent late = e.withStop(e.endsAt() - H / 4, "stopped");
        assertEquals(e.endsAt(), late.endsAt());

        // Before it starts, a stop means it never runs.
        MarketEvent never = e.withEnd(T0 - H, "disabled");
        assertEquals(T0, never.endsAt());
        assertFalse(never.active(T0));
        assertEquals(0.0, never.contribution(T0 + 10 * H));
    }

    @Test
    void copiesChangeExactlyOneThing() {
        MarketEvent e = hot(0.12);
        assertEquals(e, e.toBuilder().build(), "a builder round-trip is the same row");
        MarketEvent saved = e.withId(57);
        assertEquals(57L, saved.id());
        assertEquals(e, saved.withId(0));

        MarketEvent announced = e.withAnnouncedAt(T0 + 4 * H);
        assertTrue(announced.announced());
        assertEquals(e, announced.withAnnouncedAt(null));
        assertEquals(Long.valueOf(T0 + 31 * H), e.withLastCallAt(T0 + 31 * H).lastCallAt());
        assertEquals(Long.valueOf(T0 + 44 * H), e.withEndLineAt(T0 + 44 * H).endLineAt());

        MarketEvent text = e.withText("&fEverybody wants Oak Logs this week!", "&6Oak Log: ...");
        assertEquals("&fEverybody wants Oak Logs this week!", text.headline());
        assertEquals("&6Oak Log: ...", text.line());
        MarketEvent priced = e.withPrices(12.0, 4.47, 5.01);
        assertEquals(12.0, priced.pct());
        assertEquals(4.47, priced.priceBefore());
        assertEquals(5.01, priced.priceAfter());
        assertEquals(e, priced.withPrices(0, 0, 0));

        MarketEvent built = MarketEvent.builder(EventKind.REAL, Source.REAL).itemId("wheat")
                .tag("wheat:zw.f:20724").strength(0.02).startedAt(T0).halfLifeMs(24 * H).lastsMs(96 * H)
                .endsAt(T0 - 5).build();
        assertEquals(T0, built.endsAt(), "ends_at is never before started_at");
        assertEquals("wheat:zw.f:20724", built.tag());
    }

    @Test
    void kindsSourcesAndBadgesParseAndRank() {
        assertEquals(EventKind.HOT, EventKind.parse(" hot "));
        assertEquals(EventKind.REAL, EventKind.parse("REAL"));
        assertNull(EventKind.parse("boom"));
        assertNull(EventKind.parse(null));
        assertEquals("deal", EventKind.DEAL.id());
        assertTrue(EventKind.HOT.story() && EventKind.DEAL.story() && !EventKind.UP.story());
        assertTrue(EventKind.UP.mood() && EventKind.DOWN.mood() && EventKind.HOT.mood());
        assertFalse(EventKind.REAL.mood() || EventKind.SEASON.mood() || EventKind.WANTED.mood());
        assertEquals(1, EventKind.UP.sign());
        assertEquals(-1, EventKind.DEAL.sign());
        assertEquals(0, EventKind.REAL.sign());

        assertEquals(Source.CALENDAR, Source.parse("calendar"));
        assertEquals("admin", Source.ADMIN.id());
        assertNull(Source.parse("?"));

        assertTrue(Badge.UP.precedence() > Badge.HOT.precedence());
        assertTrue(Badge.DEAL.precedence() > Badge.WANTED.precedence());
        assertTrue(Badge.WANTED.precedence() > Badge.NONE.precedence());
        Badge[] sorted = {Badge.HOT, Badge.UP, Badge.DEAL, Badge.DOWN, Badge.WANTED, Badge.NONE};
        for (int i = 1; i < sorted.length; i++) {
            assertTrue(sorted[i - 1].sortRank() < sorted[i].sortRank(), sorted[i - 1] + " before " + sorted[i]);
        }
        assertEquals(Badge.WANTED, Badge.of(EventKind.WANTED));
        assertEquals(Badge.NONE, Badge.of(EventKind.SEASON));
        assertEquals(Badge.NONE, Badge.of(null));
        assertEquals("", Badge.NONE.id());
        assertEquals("hot", Badge.HOT.id());
        assertFalse(Badge.NONE.shown());
        assertTrue(Phase.FULL.live());
        assertFalse(Phase.OVER.live() || Phase.PENDING.live());

        RealImpulse imp = new RealImpulse("gold_ingot", "gc.f", 20724L, 0.03, "gold", 1.5);
        assertEquals("gold_ingot:gc.f:20724", imp.tag());
    }
}
