package com.dierks.homecraft.market;

import org.bukkit.Material;

/**
 * A catalog entry in the finite, conserved commodities market — the admin-defined,
 * config-driven definition of a tradable commodity. This is the <em>static</em>
 * half; the moving {@code current price} + held {@code stock} live in
 * {@link MarketState}.
 *
 * <p>Price is a function of stock: at {@code stock == 0} the price is pinned to
 * {@link #ceiling()} and the item is out of stock; at {@code stock >= }{@link #fullStock()}
 * the price rests at {@link #floor()}.
 *
 * <p>These prices are the online-market side ONLY — QuickShop / player shops set
 * their own prices and are never touched here.
 *
 * <p>{@code maxDailySell} / {@code maxDailyBuy} are optional per-player, per-item,
 * per-day unit caps (0 = no per-item cap). They apply <em>on top of</em> the global
 * {@code sell_limits} / {@code buy_limits} — the tighter of the two wins — so rare
 * commodities can be protected while commons stay high-volume.
 */
public record MarketItem(String id, Material material, String displayName,
                         double floor, double ceiling, long initialStock, long fullStock,
                         long maxDailySell, long maxDailyBuy) {

    /** Player-facing label (falls back to the material name). */
    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName : material.name();
    }
}
