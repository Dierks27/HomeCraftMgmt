package com.dierks.homecraft.market;

/**
 * How a buy or sell order is priced, unit by unit. Pulled out of {@link MarketService} for the
 * live market (0.33) so it can be tested without a server, and so the quote a GUI shows and
 * the trade that executes run the very same code.
 *
 * <p>Two prices are in play. The <b>balanced</b> price {@code b} is {@link MarketState#currentPrice()}:
 * it follows the stock curve, and only trades (and the admin stock commands) move it. The
 * <b>mid</b> a player actually trades at is the balanced price times the live market's
 * multiplier {@code M} ({@link PriceMood#multiplier}), held inside the band:
 * <pre>b     = clamp(startBase, floor, ceiling);  s = startStock
 * for each unit:
 *     m_s   = sell ? (s &gt; 0 ? M : 1)                        // an empty item sits at its ceiling
 *                  : (s &gt; 1 ? M : max(M, 1))               // the unit that empties the shelf: no discount
 *     mid   = clamp(b × m_s, floor, ceiling)
 *     unit  = buy ? ask(mid) : max(0, bid(mid))
 *     (stop if this unit would pass the daily money cap)
 *     s     = s ∓ 1
 *     b     = engine.nextPrice(item, b, s)                   // the 0.32 glide, untouched by M
 * endBase  = b
 * endPrice = clamp(b × (s &gt; 0 ? M : 1), floor, ceiling)      // what the market shows next</pre>
 *
 * <ul>
 *   <li>{@code M} is frozen for the whole order and squeezed into the hard {@code [0.75, 1.25]}
 *       band first ({@link #clampMultiplier}).</li>
 *   <li><b>The empty-shelf boundary is never a discount.</b> The first unit sold into an empty
 *       item is paid at {@code M = 1}, from its ceiling, so the unit bought back out of it (the
 *       one that empties the shelf, bought at stock 1) is charged at {@code max(M, 1)}, never
 *       less than at {@code M = 1}. Otherwise, with {@code M} below about 0.9, selling one into
 *       an empty item and buying it straight back made money every time and returned stock and
 *       the balanced price to where they started: a pump with no end for anyone without a daily
 *       cap. Now any round trip across the boundary costs at least what it cost in 0.32,
 *       whatever {@code M} is, even if it changes between the two trades.</li>
 *   <li>{@code M} never feeds the balanced price: {@code endBase} and {@code endStock} are the
 *       same whatever {@code M} is, for the same number of units.</li>
 *   <li>At {@code M = 1.0} every result is bit-for-bit 0.32's {@code simulateBuy/simulateSell}:
 *       {@code b × 1.0 == b} exactly, and clamping a price already inside the band returns it
 *       unchanged. {@code OrderMathTest} pins this against a verbatim copy of the 0.32 code.</li>
 * </ul>
 *
 * <p>Plain Java, no Bukkit beyond the {@link MarketItem} record: unit-tested without a server
 * ({@code OrderMathTest}).
 */
public final class OrderMath {

    /** The lowest multiplier any price is ever quoted at. Mirrors {@code SimLimits.MIN_MULTIPLIER}. */
    public static final double MIN_MULTIPLIER = 0.75;
    /** The highest multiplier any price is ever quoted at. Mirrors {@code SimLimits.MAX_MULTIPLIER}. */
    public static final double MAX_MULTIPLIER = 1.25;

    /**
     * The daily allowance an order runs under. When {@code limited}, a positive {@code maxMoney}
     * stops the order before the unit that would take the total past {@code remainingMoney}, and
     * a positive {@code maxUnits} stops it after {@code remainingUnits} units. {@code 0} on an
     * axis means that axis has no cap.
     */
    public record Limits(boolean limited, double maxMoney, double remainingMoney, long maxUnits, long remainingUnits) {
        /** No daily limits at all: quotes, and players who bypass the caps. */
        public static final Limits NONE = new Limits(false, 0, 0, 0, 0);
    }

    /**
     * A priced order: how many units filled, the integrated {@code total}, the displayed mid
     * price once it is done ({@code endPrice}), the stock it leaves, and the balanced price it
     * leaves ({@code endBase}, the value a trade writes back to {@link MarketState}).
     */
    public record Plan(int filled, double total, double endPrice, long endStock, double endBase) {
    }

    private OrderMath() {
    }

    /**
     * Hold a multiplier inside the hard {@code [0.75, 1.25]} band. NaN or an infinity (a broken
     * mood) gives exactly {@code 1.0}; {@code 1.0} comes back exactly {@code 1.0}.
     */
    public static double clampMultiplier(double m) {
        if (!Double.isFinite(m)) {
            return 1.0;
        }
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, m));
    }

    /**
     * The mid price at a balanced price and stock: {@code clamp(base × (stock > 0 ? M : 1), floor,
     * ceiling)}, with {@code M} held inside the hard band. An empty item is priced at its
     * balanced price, which is its ceiling once the curve has caught up.
     */
    public static double mid(MarketItem item, double base, long stock, double m) {
        return midAt(item, base, stock, clampMultiplier(m));
    }

    /**
     * The mid the next unit <em>bought</em> at this stock is priced at: {@link #mid}, except
     * that the unit that empties the shelf (bought at stock 1) is priced at {@code max(M, 1)},
     * never below its {@code M = 1} price (see the class notes). The unit sold into the empty
     * shelf is paid at {@code M = 1}, so no round trip across the boundary can profit. Equal to
     * {@link #mid} at any other stock, and at stock 1 whenever {@code M >= 1}.
     */
    public static double buyMid(MarketItem item, double base, long stock, double m) {
        return buyMidAt(item, base, stock, clampMultiplier(m));
    }

    /**
     * The spread-adjusted ask, held inside the item's band. floor/ceiling are a hard contract on
     * every price a player ever sees or pays: the spread widens the mid within the band, it
     * never pushes a quote outside it. A null item (not in the catalog) is not clamped.
     */
    public static double ask(PricingEngine engine, MarketItem item, double mid) {
        double price = engine.buyPrice(mid);
        return item == null ? price : PricingEngine.clamp(price, item.floor(), item.ceiling());
    }

    /** The spread-adjusted bid, held inside the item's band. Mirrors {@link #ask}. */
    public static double bid(PricingEngine engine, MarketItem item, double mid) {
        double price = engine.sellPrice(mid);
        return item == null ? price : PricingEngine.clamp(price, item.floor(), item.ceiling());
    }

    /**
     * Price buying up to {@code want} units: each unit costs a little more as stock drops, so
     * the total is the area under the rising price, stopping at the stock on hand and at
     * whatever {@code limits} allow. The unit that empties the shelf is charged at
     * {@code max(M, 1)} ({@link #buyMid}).
     *
     * @param startBase  the balanced price before the order ({@link MarketState#currentPrice()})
     * @param startStock the stock before the order
     * @param m          the multiplier, frozen for the whole order ({@code 1.0} = no live market)
     */
    public static Plan buy(PricingEngine engine, MarketItem item, double startBase, long startStock,
                           int want, Limits limits, double m) {
        Limits lim = limits == null ? Limits.NONE : limits;
        double mult = clampMultiplier(m);
        long stock = startStock;
        double base = PricingEngine.clamp(startBase, item.floor(), item.ceiling());
        double total = 0;
        int filled = 0;
        int cap = (int) Math.max(0, Math.min(want, stock));
        if (lim.limited() && lim.maxUnits() > 0) {
            cap = (int) Math.min(cap, lim.remainingUnits());
        }
        for (; filled < cap; filled++) {
            double unit = ask(engine, item, buyMidAt(item, base, stock, mult));
            if (lim.limited() && lim.maxMoney() > 0 && total + unit > lim.remainingMoney()) {
                break; // this unit would exceed the daily spend cap
            }
            total += unit;
            stock -= 1;
            base = engine.nextPrice(item, base, stock);
        }
        return new Plan(filled, total, midAt(item, base, stock, mult), stock, base);
    }

    /**
     * Price selling up to {@code want} units: each unit earns a little less as stock rises,
     * stopping at whatever {@code limits} allow. The first unit sold into an empty item is paid
     * at {@code M = 1}, from its ceiling.
     *
     * @param startBase  the balanced price before the order ({@link MarketState#currentPrice()})
     * @param startStock the stock before the order
     * @param m          the multiplier, frozen for the whole order ({@code 1.0} = no live market)
     */
    public static Plan sell(PricingEngine engine, MarketItem item, double startBase, long startStock,
                            int want, Limits limits, double m) {
        Limits lim = limits == null ? Limits.NONE : limits;
        double mult = clampMultiplier(m);
        long stock = startStock;
        double base = PricingEngine.clamp(startBase, item.floor(), item.ceiling());
        double total = 0;
        int filled = 0;
        int cap = Math.max(0, want);
        if (lim.limited() && lim.maxUnits() > 0) {
            cap = (int) Math.min(cap, lim.remainingUnits());
        }
        for (; filled < cap; filled++) {
            double unit = Math.max(0.0, bid(engine, item, midAt(item, base, stock, mult)));
            if (lim.limited() && lim.maxMoney() > 0 && total + unit > lim.remainingMoney()) {
                break; // this unit would exceed the daily earning cap
            }
            total += unit;
            stock += 1;
            base = engine.nextPrice(item, base, stock);
        }
        return new Plan(filled, total, midAt(item, base, stock, mult), stock, base);
    }

    /**
     * What {@code units} units would have come to at {@code M = 1} from the same start, with no
     * limits: the yardstick the ledger measures a moved trade against ({@link PriceMood#onTrade}).
     */
    public static double neutralTotal(PricingEngine engine, MarketItem item, boolean sell,
                                      double startBase, long startStock, int units) {
        return sell
                ? sell(engine, item, startBase, startStock, units, Limits.NONE, 1.0).total()
                : buy(engine, item, startBase, startStock, units, Limits.NONE, 1.0).total();
    }

    /** {@link #mid} with {@code mult} already inside the hard band. */
    private static double midAt(MarketItem item, double base, long stock, double mult) {
        double m = stock > 0 ? mult : 1.0;
        return PricingEngine.clamp(base * m, item.floor(), item.ceiling());
    }

    /**
     * {@link #buyMid} with {@code mult} already inside the hard band. At {@code mult == 1.0}
     * this is {@code midAt(..., 1.0)} exactly ({@code max(1.0, 1.0) == 1.0}), so 0.32 is kept.
     */
    private static double buyMidAt(MarketItem item, double base, long stock, double mult) {
        return midAt(item, base, stock, stock > 1 ? mult : Math.max(mult, 1.0));
    }
}
