package com.dierks.homecraft.market;

/**
 * Scarcity pricing for the finite-stock market. Price is a function of the real
 * stock the market holds — <b>not</b> an abstract demand counter.
 *
 * <p><b>Target</b> price from stock is a <b>geometric</b> (log-linear) interpolation
 * between floor and ceiling, so that an equal <i>percentage</i> change in stock
 * moves price by a comparable <i>percentage</i> for <em>any</em> item — a cheap
 * bulk staple and an expensive ore behave alike, instead of the cheap one
 * whipsawing while the dear one barely budges:
 * <pre>frac   = clamp(stock / fullStock, 0, 1)      // 1 = full, 0 = empty
 * t      = (1 - frac)^elasticity               // 0 at full, 1 at empty
 * target = floor * (ceiling / floor)^t         // floor at full, ceiling at empty</pre>
 * It is monotonic: near-full stock sits near the floor, near-empty near the
 * ceiling, and zero stock is exactly the ceiling. {@code elasticity} shapes the
 * curve (1 = pure geometric).
 *
 * <p><b>Inertia</b> keeps prices gliding rather than snapping — the current price
 * only moves a fraction of the way to the target each step:
 * <pre>next = current + (target - current) * (1 - inertia)</pre>
 * Bulk orders integrate this step <i>per unit</i> across the order (see
 * {@code MarketService}) so a large order's cost is the area under the rising
 * price, and the final displayed price is where the order actually ended.
 *
 * <p><b>Spread</b> splits that mid price into an ask (what you pay to buy) sitting
 * slightly above, and a bid (what you're paid to sell) slightly below.
 */
public final class PricingEngine {

    private static final double MIN_POSITIVE = 1e-6;

    private final double elasticity;
    private final double inertia;
    private final double spread;

    public PricingEngine(double elasticity, double inertia, double spread) {
        this.elasticity = Math.max(0.0001, elasticity);
        this.inertia = Math.max(0.0, Math.min(0.99, inertia));
        this.spread = Math.max(0.0, spread);
    }

    /** The stock-implied target (mid) price — geometric floor↔ceiling by stock. */
    public double targetPrice(MarketItem item, long stock) {
        long full = Math.max(1L, item.fullStock());
        double frac = clamp((double) stock / full, 0.0, 1.0);
        double t = Math.pow(1.0 - frac, elasticity); // 0 at full, 1 at empty
        // Geometric interpolation needs a positive floor; guard degenerate configs.
        double floor = Math.max(MIN_POSITIVE, item.floor());
        double ceiling = Math.max(floor, item.ceiling());
        double target = floor * Math.pow(ceiling / floor, t);
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
