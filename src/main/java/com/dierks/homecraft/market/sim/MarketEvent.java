package com.dierks.homecraft.market.sim;

import java.util.Objects;

/**
 * One {@code market_events} row (schema v32, spec §11.1): a HOT, DEAL, UP, DOWN, WANTED,
 * SEASON or REAL event. Immutable; every change is a copy ({@code with*}, {@link #toBuilder()}).
 *
 * <p><b>Nothing here is stored that can be derived.</b> The phase, the badge and the price
 * effect are functions of the row and a moment in time ({@link #phase}, {@link #badge},
 * {@link #contribution}), so a restart reads exactly what was running.
 *
 * <p><b>Strength is normalised on the way in.</b> HOT and UP are always positive, DEAL and DOWN
 * always negative, whatever sign they were given; HOT/DEAL are held to at most 15%, UP/DOWN to
 * 25%, REAL to 4.5% either way; NaN reads as 0. A row can never describe a bigger move than the
 * code allows. Negative durations read as 0 and {@code ends_at} is never before
 * {@code started_at}.
 *
 * @param id            database id; 0 until inserted
 * @param itemId        the catalog id; {@code null} for a SEASON
 * @param tag           {@code null} except SEASON ({@code id:year}) and REAL ({@code item:symbol:day})
 * @param strength      signed fraction ({@code R} for REAL; unused for WANTED/SEASON)
 * @param rampMs        HOT/DEAL silent rise
 * @param holdMs        HOT/DEAL time at full strength
 * @param fadeMs        HOT/DEAL cool-down
 * @param halfLifeMs    UP/DOWN/REAL decay half-life
 * @param lastsMs       UP/DOWN/REAL life
 * @param endsAt        when the row closes (natural end, or 1 h after a stop)
 * @param stoppedAt     when it was stopped early, or {@code null}
 * @param stopReason    {@code stopped}, {@code sold_out}, {@code reset}, {@code paused}, … or {@code null}
 * @param pct           the announced % (signed)
 * @param priceBefore   the price the announcement quotes before
 * @param priceAfter    the price the announcement quotes after
 * @param headline      rendered with {@code &} codes
 * @param line          rendered with {@code &} codes
 * @param announceDueAt when the announcement becomes due (HOT/DEAL: end of ramp)
 * @param announcedAt   when it was broadcast, or {@code null}
 * @param lastCallAt    when "Last call!" went out, or {@code null}
 * @param endLineAt     when the ending line went out, or {@code null}
 */
public record MarketEvent(long id, EventKind kind, Source source, String itemId, String tag,
                          double strength, long startedAt, long rampMs, long holdMs, long fadeMs,
                          long halfLifeMs, long lastsMs, long endsAt, Long stoppedAt, String stopReason,
                          double pct, double priceBefore, double priceAfter, String headline, String line,
                          Long announceDueAt, Long announcedAt, Long lastCallAt, Long endLineAt) {

    public MarketEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        strength = normalise(kind, strength);
        rampMs = Math.max(0L, rampMs);
        holdMs = Math.max(0L, holdMs);
        fadeMs = Math.max(0L, fadeMs);
        halfLifeMs = Math.max(0L, halfLifeMs);
        lastsMs = Math.max(0L, lastsMs);
        endsAt = Math.max(startedAt, endsAt);
    }

    // ---- factories ----------------------------------------------------------------------

    /**
     * A new HOT or DEAL: {@code ends_at = t0 + ramp + hold + fade}, due to be announced when
     * the ramp is done.
     */
    public static MarketEvent story(EventKind kind, Source source, String itemId, double strength,
                                    long startedAt, long rampMs, long holdMs, long fadeMs) {
        if (kind == null || !kind.story()) {
            throw new IllegalArgumentException("not a story kind: " + kind);
        }
        long r = Math.max(0L, rampMs);
        long h = Math.max(0L, holdMs);
        long f = Math.max(0L, fadeMs);
        return builder(kind, source).itemId(itemId).strength(strength).startedAt(startedAt)
                .rampMs(r).holdMs(h).fadeMs(f).endsAt(startedAt + r + h + f)
                .announceDueAt(startedAt + r).build();
    }

    /**
     * A new UP, DOWN or REAL: full size at {@code t0}, decaying with {@code halfLifeMs}, gone
     * at {@code t0 + lastsMs}; due to be announced at once.
     */
    public static MarketEvent shock(EventKind kind, Source source, String itemId, double strength,
                                    long startedAt, long halfLifeMs, long lastsMs) {
        if (kind == null || !kind.shock()) {
            throw new IllegalArgumentException("not a shock kind: " + kind);
        }
        long l = Math.max(0L, lastsMs);
        return builder(kind, source).itemId(itemId).strength(strength).startedAt(startedAt)
                .halfLifeMs(halfLifeMs).lastsMs(l).endsAt(startedAt + l)
                .announceDueAt(startedAt).build();
    }

    /** A new WANTED or SEASON row: no price effect, due to be announced at once. */
    public static MarketEvent info(EventKind kind, Source source, String itemId, String tag,
                                   long startedAt, long endsAt) {
        if (kind != EventKind.WANTED && kind != EventKind.SEASON) {
            throw new IllegalArgumentException("not an info kind: " + kind);
        }
        return builder(kind, source).itemId(itemId).tag(tag).startedAt(startedAt).endsAt(endsAt)
                .announceDueAt(startedAt).build();
    }

    public static Builder builder(EventKind kind, Source source) {
        return new Builder(kind, source);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    // ---- time ---------------------------------------------------------------------------

    /** HOT/DEAL {@code t1}: the end of the hold, when the fade starts. */
    public long holdEndsAt() {
        return startedAt + rampMs + holdMs;
    }

    /** When the row would close with no stop. */
    public long naturalEndsAt() {
        if (kind.story()) {
            return holdEndsAt() + fadeMs;
        }
        if (kind.shock()) {
            return startedAt + lastsMs;
        }
        return endsAt;
    }

    /** Started and not yet closed: {@code started_at <= t < ends_at}. Limits apply while active. */
    public boolean active(long t) {
        return t >= startedAt && t < endsAt;
    }

    public boolean stopped() {
        return stoppedAt != null;
    }

    public boolean announced() {
        return announcedAt != null;
    }

    /**
     * When it became news: a HOT/DEAL at the end of its silent ramp ({@code started_at + ramp}),
     * anything else when it started.
     */
    public long newsTime() {
        return kind.story() ? startedAt + rampMs : startedAt;
    }

    /** The envelope in {@code [0, 1]} ({@link SimMath#envelope}). */
    public double envelope(long t) {
        return SimMath.envelope(this, t);
    }

    /**
     * This event's part of the event sum {@code e} at {@code t}: {@code strength * envelope}
     * for HOT, DEAL, UP and DOWN; 0 for every other kind. REAL is NOT included — it belongs to
     * the predictable layer, see {@link #realContribution}.
     */
    public double contribution(long t) {
        return kind.mood() ? strength * envelope(t) : 0.0;
    }

    /** A REAL event's part of the real-world layer {@code r} at {@code t}; 0 for every other kind. */
    public double realContribution(long t) {
        return kind == EventKind.REAL ? strength * envelope(t) : 0.0;
    }

    /** Where the event is at {@code t} (see {@link Phase}). */
    public Phase phase(long t) {
        if (t < startedAt) {
            return Phase.PENDING;
        }
        if (t >= endsAt) {
            return Phase.OVER;
        }
        switch (kind) {
            case HOT, DEAL -> {
                long rampEnd = startedAt + rampMs;
                if (stoppedAt != null && t >= stoppedAt) {
                    // Stopped before it was ever at full strength: it was never announced, so it
                    // stays silent until it is over.
                    return stoppedAt < rampEnd ? Phase.RAMP : Phase.FADING;
                }
                if (t < rampEnd) {
                    return Phase.RAMP;
                }
                return t < holdEndsAt() ? Phase.FULL : Phase.FADING;
            }
            case UP, DOWN -> {
                return Math.abs(contribution(t)) >= SimMath.NEWS_BADGE_MIN ? Phase.BADGE : Phase.TAIL;
            }
            default -> {
                return Phase.FIRED;
            }
        }
    }

    /**
     * The badge this event gives its item at {@code t}: HOT/DEAL while FULL or FADING, UP/DOWN
     * while BADGE, otherwise {@link Badge#NONE}. WANTED rows give none — the WANTED badge
     * follows the item's stock, not a row.
     */
    public Badge badge(long t) {
        Phase p = phase(t);
        return switch (p) {
            case FULL, FADING, BADGE -> Badge.of(kind);
            default -> Badge.NONE;
        };
    }

    /** When the badge goes ({@link SimMath#badgeEndsAt}). */
    public long badgeEndsAt() {
        return SimMath.badgeEndsAt(this);
    }

    // ---- changes ------------------------------------------------------------------------

    /**
     * Stop at {@code ts} with the 1 h fade ({@code sim stop}, a sold-out DEAL). A no-op once
     * the row is closed. A second stop keeps the first stop's time and reason and can only
     * bring {@code ends_at} closer.
     */
    public MarketEvent withStop(long ts, String reason) {
        return stopAt(ts, reason, SimMath.STOP_FADE_MS);
    }

    /**
     * End at {@code ts} with no fade ({@code sim reset}, pause, disable, an item removed or set
     * {@code sim: false}). Same rules as {@link #withStop}.
     */
    public MarketEvent withEnd(long ts, String reason) {
        return stopAt(ts, reason, 0L);
    }

    private MarketEvent stopAt(long ts, String reason, long fadeMs) {
        if (ts >= endsAt) {
            return this;
        }
        long stop = stoppedAt != null ? stoppedAt : ts;
        String why = stoppedAt != null && stopReason != null ? stopReason : reason;
        long end = Math.max(startedAt, Math.min(endsAt, ts + fadeMs));
        return toBuilder().stoppedAt(stop).stopReason(why).endsAt(end).build();
    }

    public MarketEvent withId(long newId) {
        return toBuilder().id(newId).build();
    }

    public MarketEvent withAnnouncedAt(Long at) {
        return toBuilder().announcedAt(at).build();
    }

    public MarketEvent withLastCallAt(Long at) {
        return toBuilder().lastCallAt(at).build();
    }

    public MarketEvent withEndLineAt(Long at) {
        return toBuilder().endLineAt(at).build();
    }

    public MarketEvent withText(String newHeadline, String newLine) {
        return toBuilder().headline(newHeadline).line(newLine).build();
    }

    public MarketEvent withPrices(double newPct, double before, double after) {
        return toBuilder().pct(newPct).priceBefore(before).priceAfter(after).build();
    }

    // ---- helpers ------------------------------------------------------------------------

    private static double normalise(EventKind kind, double s) {
        if (Double.isNaN(s)) {
            return 0.0;
        }
        return switch (kind) {
            case HOT, DEAL -> kind.sign() * SimLimits.clampStory(Math.abs(s));
            case UP, DOWN -> kind.sign() * SimLimits.clampNews(Math.abs(s));
            case REAL -> SimLimits.clamp(s, -SimLimits.PREDICTABLE_MAX, SimLimits.PREDICTABLE_MAX);
            default -> s;
        };
    }

    /** A mutable builder for a {@link MarketEvent}; {@link #build()} applies the record's rules. */
    public static final class Builder {
        private long id;
        private EventKind kind;
        private Source source;
        private String itemId;
        private String tag;
        private double strength;
        private long startedAt;
        private long rampMs;
        private long holdMs;
        private long fadeMs;
        private long halfLifeMs;
        private long lastsMs;
        private long endsAt;
        private Long stoppedAt;
        private String stopReason;
        private double pct;
        private double priceBefore;
        private double priceAfter;
        private String headline;
        private String line;
        private Long announceDueAt;
        private Long announcedAt;
        private Long lastCallAt;
        private Long endLineAt;

        private Builder(EventKind kind, Source source) {
            this.kind = kind;
            this.source = source;
        }

        private Builder(MarketEvent e) {
            this.id = e.id;
            this.kind = e.kind;
            this.source = e.source;
            this.itemId = e.itemId;
            this.tag = e.tag;
            this.strength = e.strength;
            this.startedAt = e.startedAt;
            this.rampMs = e.rampMs;
            this.holdMs = e.holdMs;
            this.fadeMs = e.fadeMs;
            this.halfLifeMs = e.halfLifeMs;
            this.lastsMs = e.lastsMs;
            this.endsAt = e.endsAt;
            this.stoppedAt = e.stoppedAt;
            this.stopReason = e.stopReason;
            this.pct = e.pct;
            this.priceBefore = e.priceBefore;
            this.priceAfter = e.priceAfter;
            this.headline = e.headline;
            this.line = e.line;
            this.announceDueAt = e.announceDueAt;
            this.announcedAt = e.announcedAt;
            this.lastCallAt = e.lastCallAt;
            this.endLineAt = e.endLineAt;
        }

        public Builder id(long v) {
            this.id = v;
            return this;
        }

        public Builder kind(EventKind v) {
            this.kind = v;
            return this;
        }

        public Builder source(Source v) {
            this.source = v;
            return this;
        }

        public Builder itemId(String v) {
            this.itemId = v;
            return this;
        }

        public Builder tag(String v) {
            this.tag = v;
            return this;
        }

        public Builder strength(double v) {
            this.strength = v;
            return this;
        }

        public Builder startedAt(long v) {
            this.startedAt = v;
            return this;
        }

        public Builder rampMs(long v) {
            this.rampMs = v;
            return this;
        }

        public Builder holdMs(long v) {
            this.holdMs = v;
            return this;
        }

        public Builder fadeMs(long v) {
            this.fadeMs = v;
            return this;
        }

        public Builder halfLifeMs(long v) {
            this.halfLifeMs = v;
            return this;
        }

        public Builder lastsMs(long v) {
            this.lastsMs = v;
            return this;
        }

        public Builder endsAt(long v) {
            this.endsAt = v;
            return this;
        }

        public Builder stoppedAt(Long v) {
            this.stoppedAt = v;
            return this;
        }

        public Builder stopReason(String v) {
            this.stopReason = v;
            return this;
        }

        public Builder pct(double v) {
            this.pct = v;
            return this;
        }

        public Builder priceBefore(double v) {
            this.priceBefore = v;
            return this;
        }

        public Builder priceAfter(double v) {
            this.priceAfter = v;
            return this;
        }

        public Builder headline(String v) {
            this.headline = v;
            return this;
        }

        public Builder line(String v) {
            this.line = v;
            return this;
        }

        public Builder announceDueAt(Long v) {
            this.announceDueAt = v;
            return this;
        }

        public Builder announcedAt(Long v) {
            this.announcedAt = v;
            return this;
        }

        public Builder lastCallAt(Long v) {
            this.lastCallAt = v;
            return this;
        }

        public Builder endLineAt(Long v) {
            this.endLineAt = v;
            return this;
        }

        public MarketEvent build() {
            return new MarketEvent(id, kind, source, itemId, tag, strength, startedAt, rampMs, holdMs,
                    fadeMs, halfLifeMs, lastsMs, endsAt, stoppedAt, stopReason, pct, priceBefore,
                    priceAfter, headline, line, announceDueAt, announcedAt, lastCallAt, endLineAt);
        }
    }
}
