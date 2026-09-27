package com.dierks.homecraft.market.sim;

import java.util.Objects;

/**
 * One {@code market_sim_state} row (schema v32): what the live market remembers about one item
 * between ticks. Immutable; {@link MarketSimulator} replaces the map entry each tick. A missing
 * row reads as {@link #fresh} (all zeros).
 *
 * <p>Nothing here is stock or price. The sim never writes either: this is only its own mood
 * bookkeeping.
 *
 * @param itemId        the catalog id
 * @param drift         {@code d}, the quiet wander, as a fraction; held to {@code [-0.08, 0.08]}
 *                      (NaN and infinities read as 0) so a hand-edited row can never widen it
 * @param lastNewsAt    when the item last had an UP/DOWN flash (0 = never): the 72 h news cooldown
 *                      and the "no HOT/DEAL within 24 h of news" rule run from here
 * @param featuredUntil when its last HOT/DEAL ended, or will end (0 = never): the 7-day cooldown
 *                      runs from here
 * @param lastWantedAt  when it last had a WANTED flash (0 = never)
 * @param updatedAt     the boundary that last wrote the row
 */
public record ItemSimState(String itemId, double drift, long lastNewsAt, long featuredUntil,
                           long lastWantedAt, long updatedAt) {

    public ItemSimState {
        Objects.requireNonNull(itemId, "itemId");
        drift = Double.isFinite(drift) ? SimLimits.clamp(drift, -SimLimits.DRIFT_MAX, SimLimits.DRIFT_MAX) : 0.0;
    }

    /** A row for an item the sim has not seen yet: no drift, never featured, never in the news. */
    public static ItemSimState fresh(String itemId) {
        return new ItemSimState(itemId, 0.0, 0L, 0L, 0L, 0L);
    }

    public ItemSimState withDrift(double newDrift, long at) {
        return new ItemSimState(itemId, newDrift, lastNewsAt, featuredUntil, lastWantedAt, at);
    }

    public ItemSimState withLastNewsAt(long at) {
        return new ItemSimState(itemId, drift, at, featuredUntil, lastWantedAt, updatedAt);
    }

    public ItemSimState withFeaturedUntil(long at) {
        return new ItemSimState(itemId, drift, lastNewsAt, at, lastWantedAt, updatedAt);
    }

    public ItemSimState withLastWantedAt(long at) {
        return new ItemSimState(itemId, drift, lastNewsAt, featuredUntil, at, updatedAt);
    }
}
