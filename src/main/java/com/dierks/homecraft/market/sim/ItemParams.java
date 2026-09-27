package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.market.MarketItem;

import java.util.regex.Pattern;

/**
 * Everything the live market needs to know about one catalog item, derived once per load
 * from its {@link MarketItem}, its optional {@link ItemOverride} and the {@link SimSettings}.
 *
 * <ul>
 *   <li>{@code lively}: {@code sqrt(floor x ceiling) >= drift.lively_from} ($10). Dearer
 *       items wander about twice as much as cheap staples.</li>
 *   <li>{@code sigma}: the drift's stationary sd,
 *       {@code volatility x (lively ? lively_percent : calm_percent) / 100}, with
 *       {@code volatility} held to {@code [0, 1.5]} and sigma itself never above the drift
 *       bound {@code D} (a wider sigma would only pin the drift against its clamp), nor above
 *       the drift's speed lock {@link SimLimits#driftSigmaCap} for the configured half-life
 *       (4.5% at the shipped 66 h, less for a shorter half-life), so no drift setting moves
 *       prices faster than the shipped settings at their liveliest.</li>
 *   <li>{@code weight}: {@code sim_weight} (default 1; negative, NaN or infinite reads as 0 =
 *       never picked).</li>
 *   <li>{@code name}: the singular plain label ({@code {Name}}); {@code plural}: the plural
 *       news name ({@code {item}}), from {@code news_name} (cut to 24 characters) or the
 *       plural rule. Both come from {@link Headlines#name} / {@link Headlines#plural}.</li>
 * </ul>
 *
 * <p>Plain Java: the only non-JDK type is {@link MarketItem}.
 */
public record ItemParams(String id, double floor, double ceiling, long fullStock, long maxDailySell,
                         long maxDailyBuy, boolean enabled, boolean lively, double sigma, double weight,
                         String name, String plural) {

    /** Longest {@code news_name} headlines accept. */
    public static final int NEWS_NAME_MAX = 24;

    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§][0-9a-fk-or]");

    /**
     * Derive the params for {@code item}. {@code override} may be {@code null} (no per-item
     * keys); {@code settings} may be {@code null} ({@link SimSettings#defaults()}).
     */
    public static ItemParams of(MarketItem item, ItemOverride override, SimSettings settings) {
        ItemOverride o = override == null ? ItemOverride.NONE : override;
        SimSettings s = settings == null ? SimSettings.defaults() : settings;
        SimSettings.Drift drift = s.drift();

        boolean enabled = o.sim() == null || o.sim();
        double geo = Math.sqrt(Math.max(0.0, item.floor()) * Math.max(0.0, item.ceiling()));
        boolean lively = geo >= drift.livelyFrom();
        double volatility = o.volatility() == null ? 1.0 : SimLimits.clampVolatility(o.volatility());
        double classPercent = lively ? drift.livelyPercent() : drift.calmPercent();
        double sigma = Math.min(volatility * classPercent / 100.0,
                Math.min(drift.maxFrac(), SimLimits.driftSigmaCap(drift.halfLifeHours())));

        double weight = 1.0;
        if (o.weight() != null) {
            double w = o.weight();
            weight = w > 0.0 && !Double.isInfinite(w) ? w : 0.0;
        }

        String name = Headlines.name(item.label());
        String plural = Headlines.plural(item.id(), name, newsName(o.newsName()), s.samePluralSet());
        return new ItemParams(item.id(), item.floor(), item.ceiling(), item.fullStock(),
                item.maxDailySell(), item.maxDailyBuy(), enabled, lively, sigma, weight, name, plural);
    }

    /** True when the item can move at all: {@code floor < ceiling}. */
    public boolean movable() {
        return floor < ceiling;
    }

    /**
     * "Limit N a day" while a DEAL or DOWN runs (§3.4):
     * {@code max(1, floor(share x (max_daily_buy > 0 ? max_daily_buy : ceil(4% of full_stock))))}.
     * With share 0.5, the shipped iron is 40 a day and oak 160.
     */
    public long eventBuyCap(double share) {
        double sh = SimLimits.clamp(share, 0.0, 1.0);
        long base = maxDailyBuy > 0 ? maxDailyBuy : (long) Math.ceil(0.04 * Math.max(0L, fullStock));
        // A hair of slack so 0.29 x 100 counts as the 29 it means, not 28.999999999999996.
        return Math.max(1L, (long) Math.floor(sh * base + 1e-9));
    }

    /**
     * The daily sell cap while a HOT or UP runs (§3.4):
     * {@code max_daily_sell > 0 ? max_daily_sell : ceil(2% of full_stock)} — the number normal
     * players already have, now also binding bypass holders.
     */
    public long eventSellCap() {
        return maxDailySell > 0 ? maxDailySell : (long) Math.ceil(0.02 * Math.max(0L, fullStock));
    }

    /** Colour codes stripped, trimmed, cut to 24 characters; {@code null} when nothing is left. */
    private static String newsName(String raw) {
        if (raw == null) {
            return null;
        }
        String plain = LEGACY_CODE.matcher(raw).replaceAll("").trim();
        if (plain.isEmpty()) {
            return null;
        }
        return plain.length() > NEWS_NAME_MAX ? plain.substring(0, NEWS_NAME_MAX).trim() : plain;
    }
}
