package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.PricingEngine;

import java.util.List;

/**
 * Composes one item's multiplier {@code M} from its parts (spec §2.1) and turns it into what
 * players see (§5.1, §7.1).
 *
 * <pre>
 * d     = drift, held to [-D, +D], D = min(drift.max_percent/100, 0.08)
 * e     = story + news: sum of strength x envelope over the item's HOT/DEAL and UP/DOWN events
 * q     = clamp(season + real, -c_pred, +c_pred),  c_pred = min(4.5%, 0.45 x spread, config)
 * raw   = 1 + d + e + q
 * M     = sim on AND item sim-enabled ? clamp(raw, Lo, Hi) : 1.0     (Lo >= 0.75, Hi <= 1.25)
 * P     = clamp(B x (S &gt; 0 ? M : 1), F, C),  B = clamp(balanced price, F, C)
 * </pre>
 *
 * <p>The multiplier is linear in zero-mean parts, so {@code E[M] = 1} needs no correction
 * (a soak test pins it). With no drift, no events and no season, {@code raw} is exactly
 * {@code 1.0} and so is {@code M}; with the sim off it is {@code 1.0} whatever the parts are.
 * Whatever the parts add up to, {@code M} never leaves {@code [0.75, 1.25]}.
 *
 * <p>Badges ({@link #status}): UP/DOWN beat HOT/DEAL, which beat WANTED. WANTED shows on any
 * sim-enabled item Crate holds none of (its price sits at the ceiling), whether or not a
 * WANTED flash has run.
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code MoodEngineTest}).
 */
public final class MoodEngine {

    private MoodEngine() {
    }

    /**
     * One item's multiplier, part by part (fractions; {@code 0.04} = 4%).
     *
     * @param drift       {@code d} as used (clamped to the drift bound)
     * @param story       the HOT/DEAL part of {@code e}
     * @param news        the UP/DOWN part of {@code e}
     * @param season      the season layer {@code s} before the predictable clamp
     * @param real        the real-world layer {@code r} before the predictable clamp
     * @param predictable {@code q = clamp(s + r, -c_pred, +c_pred)}
     * @param raw         {@code 1 + d + e + q}
     * @param multiplier  {@code M}: {@code raw} clamped to the band, or exactly 1.0 when not active
     * @param active      true when the sim is on and the item is sim-enabled
     */
    public record Breakdown(double drift, double story, double news, double season, double real,
                            double predictable, double raw, double multiplier, boolean active) {

        /** No mood at all: {@code M = 1.0}. */
        public static final Breakdown NEUTRAL = new Breakdown(0, 0, 0, 0, 0, 0, 1.0, 1.0, false);

        /** {@code e = story + news}. */
        public double events() {
            return story + news;
        }
    }

    /**
     * Compose {@code M} for one item at {@code t}.
     *
     * @param p          the item (its {@code enabled} flag is {@code sim:})
     * @param drift      the item's drift {@code d}
     * @param itemEvents the item's events (any kind; only HOT/DEAL/UP/DOWN/REAL count, and only
     *                   while active); {@code null} = none
     * @param season     the summed season effect {@link SeasonCalendar#effect} (0 when seasons are off)
     * @param t          the moment (a tick boundary)
     * @param s          settings (band, drift bound, predictable cap, {@code enabled})
     * @param spread     {@code market.spread}, for {@code c_pred}
     */
    public static Breakdown breakdown(ItemParams p, double drift, List<MarketEvent> itemEvents, double season,
                                      long t, SimSettings s, double spread) {
        SimSettings set = s == null ? SimSettings.defaults() : s;
        double bound = set.drift().maxFrac();
        double d = Double.isFinite(drift) ? Math.max(-bound, Math.min(bound, drift)) : 0.0;
        double story = 0.0;
        double news = 0.0;
        double real = 0.0;
        if (itemEvents != null) {
            for (MarketEvent e : itemEvents) {
                if (e == null || !e.active(t)) {
                    continue;
                }
                if (e.kind().story()) {
                    story += e.contribution(t);
                } else if (e.kind().news()) {
                    news += e.contribution(t);
                } else if (e.kind() == EventKind.REAL) {
                    real += e.realContribution(t);
                }
            }
        }
        double sea = Double.isFinite(season) ? season : 0.0;
        double cap = set.seasons().predictableCap(spread);
        double q = Math.max(-cap, Math.min(cap, sea + real));
        double raw = 1.0 + d + story + news + q;
        boolean active = set.enabled() && p != null && p.enabled();
        double m = active ? SimLimits.clampMultiplier(raw, set.multiplierLo(), set.multiplierHi()) : 1.0;
        return new Breakdown(d, story, news, sea, real, q, raw, m, active);
    }

    /**
     * What players see for one item at {@code t}.
     *
     * @param p          the item
     * @param b          its breakdown at {@code t} ({@code null} reads as {@link Breakdown#NEUTRAL})
     * @param itemEvents the item's events; {@code null} = none
     * @param base       the balanced price ({@code MarketState.currentPrice()})
     * @param stock      the item's stock
     * @param t          the moment
     */
    public static ItemStatus status(ItemParams p, Breakdown b, List<MarketEvent> itemEvents, double base,
                                    long stock, long t) {
        Breakdown bd = b == null ? Breakdown.NEUTRAL : b;
        double usual = PricingEngine.clamp(base, p.floor(), p.ceiling());
        double m = bd.multiplier();
        double price = SimMath.displayPrice(base, stock, m, p.floor(), p.ceiling());
        double pct = SimMath.pct(price, usual);
        if (!bd.active()) {
            return new ItemStatus(Badge.NONE, 0L, m, usual, price, pct, null, null);
        }

        MarketEvent best = null;
        Badge badge = Badge.NONE;
        MarketEvent wanted = null;
        if (itemEvents != null) {
            for (MarketEvent e : itemEvents) {
                if (e == null) {
                    continue;
                }
                if (e.kind() == EventKind.WANTED && e.active(t)) {
                    wanted = e;
                    continue;
                }
                Badge eb = e.badge(t);
                if (!eb.shown()) {
                    continue;
                }
                if (best == null || eb.precedence() > badge.precedence()
                        || (eb.precedence() == badge.precedence() && later(e, best, t))) {
                    best = e;
                    badge = eb;
                }
            }
        }
        if (best != null) {
            long endsAt = best.kind().news() ? best.badgeEndsAt() : best.endsAt();
            return new ItemStatus(badge, endsAt, m, usual, price, pct, best, best.phase(t));
        }
        if (stock <= 0) {
            return new ItemStatus(Badge.WANTED, 0L, m, usual, price, pct, wanted,
                    wanted == null ? null : wanted.phase(t));
        }
        return new ItemStatus(Badge.NONE, 0L, m, usual, price, pct, null, null);
    }

    /** Same precedence: the newer event wins, then the bigger move. */
    private static boolean later(MarketEvent a, MarketEvent b, long t) {
        if (a.startedAt() != b.startedAt()) {
            return a.startedAt() > b.startedAt();
        }
        return Math.abs(a.contribution(t)) > Math.abs(b.contribution(t));
    }
}
