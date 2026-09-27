package com.dierks.homecraft.market.sim;

import java.util.Locale;

/**
 * One {@code market.sim.real_world.symbols} row: which real commodity nudges which item.
 *
 * @param item  the catalog id it nudges (lower-case)
 * @param stooq the Stooq symbol ({@code gc.f}); blank = off for that provider
 * @param yahoo the Yahoo symbol ({@code GC=F}); blank = off for that provider
 * @param name  the real-world name headlines use ({@code gold})
 */
public record RealSymbol(String item, String stooq, String yahoo, String name) {

    public RealSymbol {
        item = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
        stooq = stooq == null ? "" : stooq.trim();
        yahoo = yahoo == null ? "" : yahoo.trim();
        name = name == null ? "" : name.trim();
    }

    /** The symbol for {@code provider} ({@code stooq} or {@code yahoo}); empty when unset or unknown. */
    public String symbol(String provider) {
        if (provider == null) {
            return "";
        }
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "stooq" -> stooq;
            case "yahoo" -> yahoo;
            default -> "";
        };
    }
}
