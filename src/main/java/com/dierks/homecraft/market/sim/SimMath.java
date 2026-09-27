package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.PricingEngine;

/**
 * The live market's small formulas (spec §2.3, §5.2, §5.3), in one place so the simulator,
 * the planner, the status screens and the tests all use the same numbers.
 *
 * <ul>
 *   <li><b>Drift</b> ({@link #ouStep}): an exact Ornstein–Uhlenbeck step,
 *       {@code d' = clamp(d*a + sigma*sqrt(1 - a^2)*z, -D, +D)} with
 *       {@code a = exp(-dt*ln2/halfLife)}. Mean 0, stationary sd {@code sigma}, and the clamp
 *       {@code D} never passes 8%.</li>
 *   <li><b>HOT/DEAL envelope</b> ({@link #storyEnv}): smooth ramp, flat hold, smooth fade,
 *       with {@code smooth(u) = u^2(3 - 2u)}.</li>
 *   <li><b>Shock</b> ({@link #shock}): UP/DOWN/REAL jump at full size the instant they start,
 *       then decay: {@code k(tau) = (2^(-tau/h) - 2^(-L/h)) / (1 - 2^(-L/h))}, exactly 1 at
 *       {@code tau = 0} and exactly 0 from {@code tau = L}.</li>
 *   <li><b>Stop</b> ({@link #envelope}): from the stop, the event fades out over 1 h from the
 *       value it had at the stop. It is never stronger than the unstopped envelope and never
 *       outlives the row's {@code ends_at}.</li>
 *   <li><b>Headroom</b> ({@link #headroomUp}, {@link #headroomDown}): how far the multiplier
 *       can still move before the ceiling/floor or the configured band stops it.</li>
 * </ul>
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code SimMathTest}).
 */
public final class SimMath {

    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 60 * MINUTE_MS;
    public static final long DAY_MS = 24 * HOUR_MS;
    /** A stopped event fades out over this long. */
    public static final long STOP_FADE_MS = HOUR_MS;
    /** An UP/DOWN shows its badge while its contribution is at least this (5%). */
    public static final double NEWS_BADGE_MIN = 0.05;

    private static final double LN2 = Math.log(2.0);

    private SimMath() {
    }

    // ---- basics -------------------------------------------------------------------------

    /** {@code u^2 (3 - 2u)} with {@code u} clamped to {@code [0, 1]}; NaN gives 0. */
    public static double smooth(double u) {
        double x = SimLimits.clamp(u, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    /** {@code a + (b - a) * u}. */
    public static double lerp(double a, double b, double u) {
        return a + (b - a) * u;
    }

    // ---- drift --------------------------------------------------------------------------

    /**
     * The OU decay over {@code dtH} hours: {@code exp(-dtH * ln2 / halfLifeH)}. A half-life
     * that is not positive forgets at once (0); a step that is not positive keeps all (1).
     */
    public static double ouDecay(double dtH, double halfLifeH) {
        if (!(halfLifeH > 0.0)) {
            return 0.0;
        }
        if (!(dtH > 0.0)) {
            return 1.0;
        }
        return Math.exp(-dtH * LN2 / halfLifeH);
    }

    /**
     * One exact Ornstein–Uhlenbeck step of the drift:
     * {@code clamp(d*a + sigma*sqrt(1 - a^2)*z, -D, +D)} with {@code a = ouDecay(dtH, halfLifeH)}
     * and {@code D = min(max, 0.08)}. NaN inputs read as 0; a negative sigma as 0.
     *
     * @param d         the drift now (fraction)
     * @param dtH       the step in hours ({@code tick_minutes / 60})
     * @param halfLifeH the drift's half-life in hours
     * @param sigma     the item's stationary sd (fraction)
     * @param z         a standard normal draw
     * @param max       the configured bound {@code D} (fraction; squeezed to at most 0.08)
     */
    public static double ouStep(double d, double dtH, double halfLifeH, double sigma, double z, double max) {
        double a = ouDecay(dtH, halfLifeH);
        double now = Double.isNaN(d) ? 0.0 : d;
        double s = Double.isNaN(sigma) || sigma < 0.0 ? 0.0 : sigma;
        double draw = Double.isNaN(z) ? 0.0 : z;
        double next = now * a + s * Math.sqrt(Math.max(0.0, 1.0 - a * a)) * draw;
        double bound = SimLimits.clampDriftMax(max);
        return Math.max(-bound, Math.min(bound, next));
    }

    /**
     * The sd of the drift's move over one day at stationarity:
     * {@code sigma * sqrt(2 (1 - a_24))}, about {@code 0.667 sigma} at a 66 h half-life.
     */
    public static double dailyMoveSd(double sigma, double halfLifeH) {
        return sigma * Math.sqrt(2.0 * (1.0 - ouDecay(24.0, halfLifeH)));
    }

    // ---- envelopes ----------------------------------------------------------------------

    /**
     * The HOT/DEAL shape with no stop, in {@code [0, 1]}:
     * <pre>
     * 0                        t &lt; t0
     * smooth((t - t0) / R)     t0 &lt;= t &lt; t0 + R        (R = 0: straight to 1)
     * 1                        t0 + R &lt;= t &lt; t1         (t1 = t0 + R + H)
     * smooth(1 - (t - t1) / F) t1 &lt;= t &lt; t1 + F
     * 0                        t &gt;= t1 + F (= ends_at)
     * </pre>
     * Negative durations read as 0.
     */
    public static double storyEnv(long t0, long rampMs, long holdMs, long fadeMs, long t) {
        if (t < t0) {
            return 0.0;
        }
        long r = Math.max(0L, rampMs);
        long h = Math.max(0L, holdMs);
        long f = Math.max(0L, fadeMs);
        long rampEnd = t0 + r;
        if (t < rampEnd) {
            return smooth((double) (t - t0) / r);
        }
        long t1 = rampEnd + h;
        if (t < t1) {
            return 1.0;
        }
        if (t < t1 + f) {
            return smooth(1.0 - (double) (t - t1) / f);
        }
        return 0.0;
    }

    /** A HOT/DEAL event's envelope at {@code t}, stop included; 0 for any other kind. */
    public static double storyEnv(MarketEvent e, long t) {
        return e != null && e.kind().story() ? envelope(e, t) : 0.0;
    }

    /**
     * The shock decay {@code k(tau)}, {@code tau = (t - t0)} in hours:
     * {@code (2^(-tau/h) - 2^(-L/h)) / (1 - 2^(-L/h))} for {@code 0 <= tau < L}, else 0.
     * Exactly 1 at {@code t == t0}: the jump is instant. A half-life or life that is not
     * positive gives 0.
     *
     * @param hH the half-life in hours (UP/DOWN 6, REAL 24)
     * @param lH the life in hours (UP/DOWN 30, REAL 96)
     */
    public static double shock(long t0, long t, double hH, double lH) {
        if (!(hH > 0.0) || !(lH > 0.0) || t < t0) {
            return 0.0;
        }
        double tau = (double) (t - t0) / HOUR_MS;
        if (tau >= lH) {
            return 0.0;
        }
        double c = Math.pow(2.0, -lH / hH);
        double span = 1.0 - c;
        if (!(span > 0.0)) {
            return t == t0 ? 1.0 : 0.0;
        }
        return (Math.pow(2.0, -tau / hH) - c) / span;
    }

    /**
     * The stop fade factor: 1 before {@code stoppedAt}, then
     * {@code smooth(1 - (t - stoppedAt) / 1h)}, reaching 0 an hour after the stop.
     */
    public static double stopFade(long stoppedAt, long t) {
        if (t < stoppedAt) {
            return 1.0;
        }
        return smooth(1.0 - (double) (t - stoppedAt) / STOP_FADE_MS);
    }

    /**
     * Any event's envelope at {@code t}, in {@code [0, 1]}: the story shape for HOT/DEAL, the
     * shock decay for UP/DOWN/REAL, 0 for WANTED/SEASON (their rows carry no price effect).
     * 0 before {@code started_at} and from {@code ends_at}. After a stop at {@code ts} it is
     * {@code min(unstopped(t), unstopped(ts) * stopFade(ts, t))}: the spec's 1-hour stop fade,
     * except that a stop can never make an event stronger or longer than it already was.
     */
    public static double envelope(MarketEvent e, long t) {
        if (e == null || t < e.startedAt() || t >= e.endsAt()) {
            return 0.0;
        }
        double env = unstopped(e, t);
        Long stop = e.stoppedAt();
        if (stop != null && t >= stop) {
            double atStop = unstopped(e, Math.max(stop, e.startedAt()));
            env = Math.min(env, atStop * stopFade(stop, t));
        }
        return env;
    }

    private static double unstopped(MarketEvent e, long t) {
        EventKind k = e.kind();
        if (k.story()) {
            return storyEnv(e.startedAt(), e.rampMs(), e.holdMs(), e.fadeMs(), t);
        }
        if (k.shock()) {
            return shock(e.startedAt(), t, (double) e.halfLifeMs() / HOUR_MS, (double) e.lastsMs() / HOUR_MS);
        }
        return 0.0;
    }

    /**
     * When an UP/DOWN's badge goes: the moment its contribution falls below 5%,
     * {@code t0 - h*log2(c + (1 - c)*0.05/|J|)} with {@code c = 2^(-L/h)} (about 12 h for a
     * 22% flash), never after {@code ends_at}. A flash smaller than 5% has no badge time
     * ({@code started_at}). HOT/DEAL keep their badge to {@code ends_at}; so does every other
     * kind.
     */
    public static long badgeEndsAt(MarketEvent e) {
        if (!e.kind().news()) {
            return e.endsAt();
        }
        double j = Math.abs(e.strength());
        double h = (double) e.halfLifeMs() / HOUR_MS;
        double l = (double) e.lastsMs() / HOUR_MS;
        if (!(j > NEWS_BADGE_MIN) || !(h > 0.0) || !(l > 0.0)) {
            return e.startedAt();
        }
        double c = Math.pow(2.0, -l / h);
        double hours = -h * Math.log(c + (1.0 - c) * NEWS_BADGE_MIN / j) / LN2;
        long at = e.startedAt() + Math.round(hours * HOUR_MS);
        return Math.max(e.startedAt(), Math.min(at, e.endsAt()));
    }

    // ---- headroom, direction, price -----------------------------------------------------

    /**
     * How far the multiplier can still rise: {@code min(hi, C/B) - m0}, with {@code hi} held
     * to at most 1.25; never negative. A balanced price that is not positive gives 0.
     */
    public static double headroomUp(double b, double f, double c, double m0, double hi) {
        if (!(b > 0.0) || Double.isNaN(c) || Double.isNaN(m0)) {
            return 0.0;
        }
        double top = Math.min(Double.isNaN(hi) ? SimLimits.MAX_MULTIPLIER : Math.min(hi, SimLimits.MAX_MULTIPLIER), c / b);
        return Math.max(0.0, top - m0);
    }

    /**
     * How far the multiplier can still fall: {@code m0 - max(lo, F/B)}, with {@code lo} held
     * to at least 0.75; never negative. A balanced price that is not positive gives 0.
     */
    public static double headroomDown(double b, double f, double c, double m0, double lo) {
        if (!(b > 0.0) || Double.isNaN(f) || Double.isNaN(m0)) {
            return 0.0;
        }
        double bottom = Math.max(Double.isNaN(lo) ? SimLimits.MIN_MULTIPLIER : Math.max(lo, SimLimits.MIN_MULTIPLIER), f / b);
        return Math.max(0.0, m0 - bottom);
    }

    /**
     * The chance a news flash goes UP given {@code x = M0 - 1}:
     * {@code clamp(0.5 - 0.5 * x / 0.25, 0.2, 0.8)}. A price already up leans down and vice
     * versa, so flashes pull toward the balanced price. NaN gives 0.5.
     */
    public static double pUp(double x) {
        if (Double.isNaN(x)) {
            return 0.5;
        }
        return SimLimits.clamp(0.5 - 0.5 * x / 0.25, 0.2, 0.8);
    }

    /**
     * The displayed mid {@code P = clamp(B * m(S), F, C)} with {@code B = clamp(base, F, C)},
     * {@code m(S) = M} when stock is above 0 and exactly 1 at stock 0 (an empty item sits at
     * its ceiling). {@code M} is held to {@code [0.75, 1.25]}; with {@code M = 1} this is
     * exactly {@code clamp(base, F, C)}.
     */
    public static double displayPrice(double base, long stock, double m, double f, double c) {
        double mult = stock > 0 ? SimLimits.clampMultiplier(m) : 1.0;
        double b = PricingEngine.clamp(base, f, c);
        return PricingEngine.clamp(b * mult, f, c);
    }

    /** {@code (price / usual - 1) * 100}, the % a player sees; 0 when {@code usual} is not positive. */
    public static double pct(double price, double usual) {
        if (!(usual > 0.0) || Double.isNaN(price)) {
            return 0.0;
        }
        return (price / usual - 1.0) * 100.0;
    }
}
