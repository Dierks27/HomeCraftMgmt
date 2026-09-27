package com.dierks.homecraft.market.sim;

/**
 * The optional per-item live-market keys of one {@code market.catalog} row, as written.
 * Every field is {@code null} when the row leaves it out. {@link ItemParams#of} applies the
 * defaults and the code limits.
 *
 * @param sim        {@code sim: false} = this item never moves on its own
 * @param volatility x its drift size (0 = no drift, at most 1.5)
 * @param weight     {@code sim_weight}: how often it is picked for HOT/DEAL/news (0 = never)
 * @param newsName   {@code news_name}: the plural name headlines use (at most 24 characters)
 */
public record ItemOverride(Boolean sim, Double volatility, Double weight, String newsName) {

    /** A row with none of the keys. */
    public static final ItemOverride NONE = new ItemOverride(null, null, null, null);
}
