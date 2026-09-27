package com.dierks.homecraft.market.sim;

/**
 * A real-world move waiting to be applied at the next tick as a {@link EventKind#REAL} event.
 *
 * @param itemId        the catalog id it nudges
 * @param symbol        the provider symbol it came from ({@code gc.f})
 * @param tradeDay      the trade date {@code D}, as an epoch day ({@code LocalDate.toEpochDay()})
 * @param strength      {@code R}: the signed fraction applied to the item (already capped)
 * @param realName      the real-world name for the headline ({@code gold})
 * @param realChangePct the real close-to-close move in whole percent ({@code +1.5})
 */
public record RealImpulse(String itemId, String symbol, long tradeDay, double strength,
                          String realName, double realChangePct) {

    /** The event's unique tag, {@code item:symbol:tradeDay}: one REAL row per symbol and day. */
    public String tag() {
        return itemId + ":" + symbol + ":" + tradeDay;
    }
}
