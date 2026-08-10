package com.dierks.homecraft.market;

import org.bukkit.Material;

/**
 * A catalog entry in the dynamic (Amazon) market — the admin-defined,
 * config-driven definition of a tradable item. This is the <em>static</em> half;
 * the moving {@code current price} + {@code demand} live in {@link MarketState}.
 *
 * <p>These prices are the online-market side ONLY — QuickShop / player shops set
 * their own prices and are never touched here.
 */
public record MarketItem(String id, Material material, String displayName,
                         double basePrice, double floor, double ceiling) {

    /** Player-facing label (falls back to the material name). */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : material.name();
    }
}
