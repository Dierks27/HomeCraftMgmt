package com.dierks.homecraft.market;

/**
 * The elasticity + inertia pricing model (our own implementation — DynamicShopGUI
 * is only a conceptual reference).
 *
 * <p><b>Target</b> price is elastic in net demand:
 * <pre>target = basePrice * (1 + elasticity * demand)</pre>
 * so buying (demand up) pushes the target up and selling (demand down) pushes it
 * down, then it's clamped to the item's floor/ceiling.
 *
 * <p><b>Inertia</b> keeps prices from snapping — the current price only glides a
 * fraction of the way to the target each recalculation:
 * <pre>next = current + (target - current) * (1 - inertia)</pre>
 * Higher {@code inertia} ⇒ slower movement. Both knobs are config-driven.
 */
public final class PricingEngine {

    private final double elasticity;
    private final double inertia;

    public PricingEngine(double elasticity, double inertia) {
        this.elasticity = elasticity;
        // Keep inertia in [0,1) so the glide factor stays sane.
        this.inertia = Math.max(0.0, Math.min(0.99, inertia));
    }

    /** The elastic target price for a given net demand, clamped to bounds. */
    public double targetPrice(MarketItem item, long demand) {
        double target = item.basePrice() * (1.0 + elasticity * demand);
        return clamp(target, item.floor(), item.ceiling());
    }

    /** The next current price: glide from {@code current} toward the target, clamped. */
    public double nextPrice(MarketItem item, double current, long demand) {
        double target = targetPrice(item, demand);
        double glide = 1.0 - inertia;
        double next = current + (target - current) * glide;
        return clamp(next, item.floor(), item.ceiling());
    }

    public static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
