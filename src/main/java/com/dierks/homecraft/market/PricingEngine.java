package com.dierks.homecraft.market;

/**
 * Scarcity pricing for the finite-stock market. Price is a function of the real
 * stock the market holds — <b>not</b> an abstract demand counter.
 *
 * <p><b>Target</b> price from stock:
 * <pre>frac   = clamp(stock / fullStock, 0, 1)
 * target = floor + (ceiling - floor) * (1 - frac)^elasticity</pre>
 * so an empty market ({@code stock == 0}) sits at the <b>ceiling</b> and a full
 * one ({@code stock >= fullStock}) rests at the <b>floor</b>. {@code elasticity}
 * shapes the curve (1 = linear).
 *
 * <p><b>Inertia</b> keeps prices gliding rather than snapping — the current price
 * only moves a fraction of the way to the target each recalculation:
 * <pre>next = current + (target - current) * (1 - inertia)</pre>
 *
 * <p><b>Spread</b> splits that mid price into an ask (what you pay to buy) sitting
 * slightly above, and a bid (what you're paid to sell) slightly below.
 */
public final class PricingEngine {

    private final double elasticity;
    private final double inertia;
    private final double spread;

    public PricingEngine(double elasticity, double inertia, double spread) {
        this.elasticity = Math.max(0.0001, elasticity);
        this.inertia = Math.max(0.0, Math.min(0.99, inertia));
        this.spread = Math.max(0.0, spread);
    }

    /** The stock-implied target (mid) price, clamped to the item's bounds. */
    public double targetPrice(MarketItem item, long stock) {
        long full = Math.max(1L, item.fullStock());
        double frac = clamp((double) stock / full, 0.0, 1.0);
        double target = item.floor() + (item.ceiling() - item.floor()) * Math.pow(1.0 - frac, elasticity);
        return clamp(target, item.floor(), item.ceiling());
    }

    /** The next current (mid) price: glide from {@code current} toward the target, clamped. */
    public double nextPrice(MarketItem item, double current, long stock) {
        double target = targetPrice(item, stock);
        double next = current + (target - current) * (1.0 - inertia);
        return clamp(next, item.floor(), item.ceiling());
    }

    /** Ask price — what a player pays per unit to BUY (mid + half-spread). */
    public double buyPrice(double mid) {
        return mid * (1.0 + spread / 2.0);
    }

    /** Bid price — what a player is paid per unit to SELL (mid − half-spread). */
    public double sellPrice(double mid) {
        return mid * (1.0 - spread / 2.0);
    }

    public static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
