package com.dierks.homecraft.market;

import com.dierks.homecraft.market.sim.SimLimits;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link OrderMath}, the per-unit order pricing pulled out of {@code MarketService} for the
 * live market (0.33).
 *
 * <ul>
 *   <li><b>At {@code m = 1.0} it is 0.32, bit for bit.</b> {@link Oracle} is a verbatim copy of
 *       0.32's {@code MarketService.simulateBuy/simulateSell} (and the private {@code ask/bid}
 *       they call). For the shipped catalog × qty {1, 64, 600, 2304} × 1,000 seeded random start
 *       states (with random daily limits), filled, total, end price and end stock must match
 *       exactly, and the end balanced price must equal the end price.</li>
 *   <li><b>At {@code m != 1}:</b> {@code endPrice == clamp(endBase × m)}; every unit's mid stays
 *       inside [floor, ceiling]; the first unit sold into stock 0 is priced at {@code m = 1};
 *       {@code m} never changes the balanced price or the stock an order leaves; money and unit
 *       caps stop an order mid-way; totals rise with quantity; the neutral-total helper is an
 *       {@code m = 1} order.</li>
 *   <li><b>The hard band:</b> whatever multiplier comes in, orders are priced inside
 *       [0.75, 1.25], and {@link PriceMood#NEUTRAL} is exactly 1.0 with no caps.</li>
 * </ul>
 *
 * <p>No server needed: {@link MarketItem} touches only {@link Material}, a plain enum.
 */
class OrderMathTest {

    /** The shipped engine: {@code market.elasticity/inertia/spread} in config.yml. */
    private static final PricingEngine SHIPPED = new PricingEngine(1.0, 0.2, 0.10);

    /** The shipped catalog, as {@code market.catalog} in src/main/resources/config.yml has it. */
    private static final List<MarketItem> CATALOG = List.of(
            new MarketItem("cobblestone", Material.COBBLESTONE, "&7Cobblestone", 0.10, 5.0, 17000, 20000, 400, 800),
            new MarketItem("oak_log", Material.OAK_LOG, "&6Oak Log", 1.0, 20.0, 4000, 8000, 160, 320),
            new MarketItem("wheat", Material.WHEAT, "&eWheat", 1.0, 12.0, 3000, 6000, 120, 240),
            new MarketItem("iron_ingot", Material.IRON_INGOT, "&fIron Ingot", 4.0, 40.0, 512, 2048, 40, 80),
            new MarketItem("gold_ingot", Material.GOLD_INGOT, "&6Gold Ingot", 20.0, 150.0, 0, 1024, 20, 40),
            new MarketItem("diamond", Material.DIAMOND, "&bDiamond", 60.0, 400.0, 0, 512, 10, 20));

    private static final int[] QUANTITIES = {1, 64, 600, 2304};
    private static final int START_STATES = 1_000;

    private static final double[] MOODS = {0.75, 0.8, 0.9, 0.97, 1.03, 1.1, 1.2, 1.25};

    // ------------------------------------------------------------------ m = 1.0: 0.32, bit for bit

    @Test
    void atNeutralMultiplierBuysAreBitIdenticalTo032() {
        assertMatchesOracle(SHIPPED, false, 0xB0B5L, START_STATES);
    }

    @Test
    void atNeutralMultiplierSellsAreBitIdenticalTo032() {
        assertMatchesOracle(SHIPPED, true, 0x5E11L, START_STATES);
    }

    /** The same guarantee on engines an admin might configure, not only the shipped one. */
    @Test
    void atNeutralMultiplierOtherEnginesAreBitIdenticalTo032() {
        PricingEngine[] engines = {
                new PricingEngine(0.6, 0.0, 0.0),
                new PricingEngine(1.7, 0.5, 0.25),
                new PricingEngine(2.5, 0.99, 0.5),
                new PricingEngine(1.0, 0.2, 3.0),   // a spread past 2: the raw bid is negative, held at the floor
        };
        long seed = 77;
        for (PricingEngine engine : engines) {
            assertMatchesOracle(engine, false, seed++, 150);
            assertMatchesOracle(engine, true, seed++, 150);
        }
    }

    private static void assertMatchesOracle(PricingEngine engine, boolean sell, long seed, int states) {
        Oracle oracle = new Oracle(engine);
        SplittableRandom rnd = new SplittableRandom(seed);
        for (MarketItem item : CATALOG) {
            for (int qty : QUANTITIES) {
                for (int n = 0; n < states; n++) {
                    double base = randomBase(rnd, item);
                    long stock = randomStock(rnd, item, sell);
                    OrderMath.Limits lim = randomLimits(rnd);
                    Oracle.Plan want = sell
                            ? oracle.simulateSell(item, base, stock, qty, lim.limited(), lim.maxMoney(),
                                    lim.remainingMoney(), lim.maxUnits(), lim.remainingUnits())
                            : oracle.simulateBuy(item, base, stock, qty, lim.limited(), lim.maxMoney(),
                                    lim.remainingMoney(), lim.maxUnits(), lim.remainingUnits());
                    OrderMath.Plan got = sell
                            ? OrderMath.sell(engine, item, base, stock, qty, lim, 1.0)
                            : OrderMath.buy(engine, item, base, stock, qty, lim, 1.0);
                    String at = item.id() + (sell ? " sell " : " buy ") + qty + " from base=" + base
                            + " stock=" + stock + " " + lim;
                    assertEquals(want.filled(), got.filled(), "filled: " + at);
                    assertBits(want.total(), got.total(), "total: " + at);
                    assertBits(want.endPrice(), got.endPrice(), "end price: " + at);
                    assertEquals(want.endStock(), got.endStock(), "end stock: " + at);
                    assertBits(want.endPrice(), got.endBase(), "end base == 0.32 end price: " + at);
                }
            }
        }
    }

    /** Stricter than {@code ==}: the same bits, so even 0.0 and -0.0 count as different. */
    private static void assertBits(double want, double got, String message) {
        assertEquals(Double.doubleToLongBits(want), Double.doubleToLongBits(got),
                () -> message + " (want " + want + ", got " + got + ")");
    }

    /** Mostly inside the band (uniform, and log-uniform for the wide bands), sometimes outside it. */
    private static double randomBase(SplittableRandom rnd, MarketItem item) {
        double f = item.floor();
        double c = item.ceiling();
        int pick = rnd.nextInt(20);
        if (pick == 0) {
            return f * rnd.nextDouble(0.0, 1.0);             // a stale price under the floor
        }
        if (pick == 1) {
            return c * rnd.nextDouble(1.0, 4.0);             // a stale price over the ceiling
        }
        if (pick == 2) {
            return rnd.nextBoolean() ? f : c;                 // exactly on an edge
        }
        if (pick < 11) {
            return f * Math.pow(c / f, rnd.nextDouble());     // log-uniform, like the curve
        }
        return rnd.nextDouble(f, c);
    }

    /** Anywhere from empty to full, the edges often; sells may start past full_stock. */
    private static long randomStock(SplittableRandom rnd, MarketItem item, boolean sell) {
        long full = item.fullStock();
        int pick = rnd.nextInt(10);
        if (pick == 0) {
            return rnd.nextInt(3);                            // 0, 1 or 2
        }
        if (pick == 1) {
            return full - 1 - rnd.nextInt(3);
        }
        if (pick == 2 && sell) {
            return full + rnd.nextInt(3000);
        }
        return rnd.nextLong(full);
    }

    /** Half the orders unlimited; the rest with a random mix of money and unit caps. */
    private static OrderMath.Limits randomLimits(SplittableRandom rnd) {
        if (rnd.nextBoolean()) {
            return OrderMath.Limits.NONE;
        }
        boolean limited = rnd.nextInt(5) != 0;
        double maxMoney = rnd.nextInt(3) == 0 ? 0 : rnd.nextDouble(1, 20_000);
        double remainingMoney = maxMoney > 0 ? rnd.nextDouble(-1, maxMoney) : 0;
        long maxUnits = rnd.nextInt(3) == 0 ? 0 : 1 + rnd.nextInt(2500);
        long remainingUnits = maxUnits > 0 ? rnd.nextLong(-1, maxUnits + 1) : rnd.nextLong(-5, 5);
        return new OrderMath.Limits(limited, maxMoney, remainingMoney, maxUnits, remainingUnits);
    }

    // ------------------------------------------------------------------ m != 1

    @Test
    void endPriceIsTheEndBalancedPriceTimesTheMultiplier() {
        SplittableRandom rnd = new SplittableRandom(11);
        for (MarketItem item : CATALOG) {
            for (double m : MOODS) {
                for (int n = 0; n < 200; n++) {
                    boolean sell = rnd.nextBoolean();
                    double base = randomBase(rnd, item);
                    long stock = randomStock(rnd, item, sell);
                    int qty = rnd.nextInt(700);
                    OrderMath.Plan p = order(SHIPPED, item, sell, base, stock, qty, OrderMath.Limits.NONE, m);
                    double k = p.endStock() > 0 ? m : 1.0;
                    assertEquals(PricingEngine.clamp(p.endBase() * k, item.floor(), item.ceiling()), p.endPrice(),
                            item.id() + " m=" + m);
                    assertEquals(OrderMath.mid(item, p.endBase(), p.endStock(), m), p.endPrice());
                    assertTrue(p.endBase() >= item.floor() && p.endBase() <= item.ceiling(), "end base in band");
                }
            }
        }
    }

    /**
     * The sim never moves the balanced price or the stock: for the same units, an order at any
     * multiplier leaves exactly the balanced price and stock an {@code m = 1} order leaves.
     */
    @Test
    void theMultiplierNeverMovesTheBalancedPriceOrTheStock() {
        SplittableRandom rnd = new SplittableRandom(12);
        for (MarketItem item : CATALOG) {
            for (double m : MOODS) {
                for (int n = 0; n < 200; n++) {
                    boolean sell = rnd.nextBoolean();
                    double base = randomBase(rnd, item);
                    long stock = randomStock(rnd, item, sell);
                    int qty = rnd.nextInt(700);
                    OrderMath.Plan moved = order(SHIPPED, item, sell, base, stock, qty, OrderMath.Limits.NONE, m);
                    OrderMath.Plan neutral = order(SHIPPED, item, sell, base, stock, qty, OrderMath.Limits.NONE, 1.0);
                    assertEquals(neutral.filled(), moved.filled());
                    assertEquals(neutral.endStock(), moved.endStock());
                    assertBits(neutral.endBase(), moved.endBase(), item.id() + " m=" + m);
                }
            }
        }
    }

    /**
     * Walk an order one unit at a time: an n-unit order equals n one-unit orders chained from
     * each other's end state (the multiplier is frozen, so splitting an order changes nothing),
     * and every unit's mid is inside the band.
     */
    @Test
    void everyUnitsMidStaysInsideTheBandAndAnOrderIsItsUnitsChained() {
        SplittableRandom rnd = new SplittableRandom(13);
        for (MarketItem item : CATALOG) {
            for (double m : new double[] {0.75, 0.9, 1.0, 1.1, 1.25}) {
                for (int n = 0; n < 40; n++) {
                    boolean sell = rnd.nextBoolean();
                    double base = randomBase(rnd, item);
                    long stock = randomStock(rnd, item, sell);
                    int qty = 1 + rnd.nextInt(300);
                    OrderMath.Plan whole = order(SHIPPED, item, sell, base, stock, qty, OrderMath.Limits.NONE, m);

                    double b = base;
                    long s = stock;
                    double total = 0;
                    for (int u = 0; u < whole.filled(); u++) {
                        double mid = OrderMath.mid(item, PricingEngine.clamp(b, item.floor(), item.ceiling()), s, m);
                        assertTrue(mid >= item.floor() && mid <= item.ceiling(),
                                item.id() + " unit " + u + " mid " + mid + " outside the band");
                        OrderMath.Plan one = order(SHIPPED, item, sell, b, s, 1, OrderMath.Limits.NONE, m);
                        assertEquals(1, one.filled());
                        double unit = sell ? Math.max(0.0, OrderMath.bid(SHIPPED, item, mid))
                                : OrderMath.ask(SHIPPED, item, mid);
                        assertBits(unit, one.total(), item.id() + " unit " + u + " price");
                        total += one.total();
                        b = one.endBase();
                        s = one.endStock();
                    }
                    assertBits(whole.total(), total, item.id() + " chained total, m=" + m);
                    // (b is still the raw start when nothing filled, e.g. a buy from stock 0.)
                    assertBits(whole.endBase(), PricingEngine.clamp(b, item.floor(), item.ceiling()),
                            item.id() + " chained end base");
                    assertEquals(whole.endStock(), s);
                }
            }
        }
    }

    /** DESIGN §3.1: an empty item sits at its ceiling, so the first unit sold into it ignores M. */
    @Test
    void firstUnitSoldIntoAnEmptyItemIsPricedAtOne() {
        for (MarketItem item : CATALOG) {
            double base = item.ceiling();
            for (double m : MOODS) {
                OrderMath.Plan one = OrderMath.sell(SHIPPED, item, base, 0, 1, OrderMath.Limits.NONE, m);
                OrderMath.Plan atOne = OrderMath.sell(SHIPPED, item, base, 0, 1, OrderMath.Limits.NONE, 1.0);
                assertBits(atOne.total(), one.total(), item.id() + " m=" + m);
                assertBits(OrderMath.bid(SHIPPED, item, item.ceiling()), one.total(), item.id() + " paid from the ceiling");

                // The second unit is into stock 1, so it carries the multiplier.
                OrderMath.Plan two = OrderMath.sell(SHIPPED, item, base, 0, 2, OrderMath.Limits.NONE, m);
                OrderMath.Plan twoAtOne = OrderMath.sell(SHIPPED, item, base, 0, 2, OrderMath.Limits.NONE, 1.0);
                if (m > 1.0) {
                    assertTrue(two.total() > twoAtOne.total(), item.id() + " second unit lifted by m=" + m);
                } else {
                    assertTrue(two.total() < twoAtOne.total(), item.id() + " second unit lowered by m=" + m);
                }
            }
            // And an empty item's displayed price is its balanced price whatever the mood.
            assertEquals(item.ceiling(), OrderMath.mid(item, item.ceiling(), 0, 0.75));
            assertEquals(item.ceiling(), OrderMath.mid(item, item.ceiling(), 0, 1.25));
        }
    }

    /** A buy always has stock under it, so every unit, the first included, carries M. */
    @Test
    void everyBoughtUnitCarriesTheMultiplier() {
        MarketItem iron = item("iron_ingot");
        double base = 22.49;
        for (double m : MOODS) {
            OrderMath.Plan one = OrderMath.buy(SHIPPED, iron, base, 512, 1, OrderMath.Limits.NONE, m);
            assertBits(OrderMath.ask(SHIPPED, iron, PricingEngine.clamp(base * m, 4.0, 40.0)), one.total(), "m=" + m);
        }
        // The last unit on the shelf too: it is bought from stock 1.
        OrderMath.Plan last = OrderMath.buy(SHIPPED, iron, 30.0, 1, 5, OrderMath.Limits.NONE, 1.2);
        assertEquals(1, last.filled());
        assertEquals(0, last.endStock());
        assertBits(OrderMath.ask(SHIPPED, iron, PricingEngine.clamp(30.0 * 1.2, 4.0, 40.0)), last.total(),
                "last unit at 30 x 1.2");
        assertBits(last.endBase(), last.endPrice(), "an emptied item shows its balanced price");
    }

    @Test
    void theMoneyCapStopsAnOrderMidWay() {
        MarketItem oak = item("oak_log");
        for (double m : MOODS) {
            for (boolean sell : new boolean[] {false, true}) {
                double budget = 300.0;
                OrderMath.Limits lim = new OrderMath.Limits(true, 5_000, budget, 0, 0);
                OrderMath.Plan capped = order(SHIPPED, oak, sell, 4.47, 4000, 500, lim, m);
                assertTrue(capped.filled() > 0 && capped.filled() < 500, "stopped mid-order, m=" + m);
                assertTrue(capped.total() <= budget, "never past the cap");
                // The unit it stopped before really would have passed the cap.
                OrderMath.Plan next = order(SHIPPED, oak, sell, capped.endBase(), capped.endStock(), 1,
                        OrderMath.Limits.NONE, m);
                assertTrue(capped.total() + next.total() > budget, "stopped at the right unit, m=" + m);
                // …and the capped order is exactly the unlimited order's first N units.
                OrderMath.Plan prefix = order(SHIPPED, oak, sell, 4.47, 4000, capped.filled(),
                        OrderMath.Limits.NONE, m);
                assertBits(prefix.total(), capped.total(), "prefix total");
                assertBits(prefix.endPrice(), capped.endPrice(), "prefix end price");
            }
        }
        // Not limited: the money cap is ignored.
        OrderMath.Plan free = OrderMath.buy(SHIPPED, oak, 4.47, 4000, 500,
                new OrderMath.Limits(false, 5_000, 300, 0, 0), 1.1);
        assertEquals(500, free.filled());
    }

    @Test
    void theUnitCapStopsAnOrderMidWay() {
        MarketItem wheat = item("wheat");
        for (double m : MOODS) {
            for (boolean sell : new boolean[] {false, true}) {
                OrderMath.Limits lim = new OrderMath.Limits(true, 0, 0, 120, 37);
                OrderMath.Plan capped = order(SHIPPED, wheat, sell, 3.46, 3000, 500, lim, m);
                assertEquals(37, capped.filled(), "m=" + m);
                assertEquals(sell ? 3037 : 2963, capped.endStock());
                OrderMath.Plan none = order(SHIPPED, wheat, sell, 3.46, 3000, 500,
                        new OrderMath.Limits(true, 0, 0, 120, 0), m);
                assertEquals(0, none.filled(), "nothing left today");
                assertBits(PricingEngine.clamp(3.46 * m, 1.0, 12.0), none.endPrice(), "an empty order leaves the price");
            }
        }
        // A buy never takes more than the stock on hand.
        OrderMath.Plan all = OrderMath.buy(SHIPPED, wheat, 11.0, 7, 500, OrderMath.Limits.NONE, 0.8);
        assertEquals(7, all.filled());
        assertEquals(0, all.endStock());
    }

    @Test
    void totalsRiseWithQuantity() {
        for (MarketItem item : CATALOG) {
            for (double m : new double[] {0.75, 1.0, 1.25}) {
                for (boolean sell : new boolean[] {false, true}) {
                    long stock = sell ? 0 : Math.min(300, item.fullStock() / 2);
                    double base = sell ? item.ceiling() : Math.sqrt(item.floor() * item.ceiling());
                    double previous = 0;
                    for (int qty = 1; qty <= 300; qty++) {
                        OrderMath.Plan p = order(SHIPPED, item, sell, base, stock, qty, OrderMath.Limits.NONE, m);
                        if (!sell && qty > stock) {
                            assertEquals(stock, p.filled());
                            assertBits(previous, p.total(), "a buy past the stock costs no more");
                            continue;
                        }
                        assertTrue(p.total() > previous,
                                item.id() + (sell ? " sell " : " buy ") + qty + " at m=" + m);
                        previous = p.total();
                    }
                }
            }
        }
    }

    @Test
    void neutralTotalIsTheSameUnitsAtOne() {
        SplittableRandom rnd = new SplittableRandom(14);
        for (MarketItem item : CATALOG) {
            for (int n = 0; n < 200; n++) {
                boolean sell = rnd.nextBoolean();
                double base = randomBase(rnd, item);
                long stock = randomStock(rnd, item, sell);
                double m = MOODS[rnd.nextInt(MOODS.length)];
                OrderMath.Limits lim = randomLimits(rnd);
                OrderMath.Plan traded = order(SHIPPED, item, sell, base, stock, rnd.nextInt(700), lim, m);
                double neutral = OrderMath.neutralTotal(SHIPPED, item, sell, base, stock, traded.filled());
                OrderMath.Plan atOne = order(SHIPPED, item, sell, base, stock, traded.filled(),
                        OrderMath.Limits.NONE, 1.0);
                assertEquals(traded.filled(), atOne.filled(), "the neutral order fills the same units");
                assertBits(atOne.total(), neutral, item.id());
                // Sells above 1 earn a bonus and buys below 1 get a discount, never the other way.
                if (traded.filled() > 0 && m > 1.0) {
                    assertTrue(traded.total() >= neutral, item.id() + " m=" + m);
                } else if (traded.filled() > 0 && m < 1.0) {
                    assertTrue(traded.total() <= neutral, item.id() + " m=" + m);
                }
            }
        }
        // At m = 1 the ledger sees no difference at all.
        MarketItem iron = item("iron_ingot");
        OrderMath.Plan plain = OrderMath.sell(SHIPPED, iron, 22.49, 512, 40, OrderMath.Limits.NONE, 1.0);
        assertBits(plain.total(), OrderMath.neutralTotal(SHIPPED, iron, true, 22.49, 512, 40), "m=1 sell");
    }

    // ------------------------------------------------------------------ the hard band

    @Test
    void theMultiplierIsHeldInsideTheHardBand() {
        assertEquals(SimLimits.MIN_MULTIPLIER, OrderMath.MIN_MULTIPLIER, "mirrors SimLimits");
        assertEquals(SimLimits.MAX_MULTIPLIER, OrderMath.MAX_MULTIPLIER, "mirrors SimLimits");
        assertBits(1.0, OrderMath.clampMultiplier(1.0), "1.0 stays exactly 1.0");
        assertEquals(1.25, OrderMath.clampMultiplier(3.0));
        assertEquals(0.75, OrderMath.clampMultiplier(0.1));
        assertEquals(0.75, OrderMath.clampMultiplier(-2.0));
        assertEquals(1.0, OrderMath.clampMultiplier(Double.NaN));
        assertEquals(1.0, OrderMath.clampMultiplier(Double.POSITIVE_INFINITY));
        assertEquals(1.0, OrderMath.clampMultiplier(Double.NEGATIVE_INFINITY));

        // An order handed a wild multiplier is priced exactly as at the band's edge.
        MarketItem oak = item("oak_log");
        for (boolean sell : new boolean[] {false, true}) {
            OrderMath.Plan wild = order(SHIPPED, oak, sell, 4.47, 4000, 64, OrderMath.Limits.NONE, 9.0);
            OrderMath.Plan edge = order(SHIPPED, oak, sell, 4.47, 4000, 64, OrderMath.Limits.NONE, 1.25);
            assertBits(edge.total(), wild.total(), "9.0 is priced as 1.25");
            OrderMath.Plan low = order(SHIPPED, oak, sell, 4.47, 4000, 64, OrderMath.Limits.NONE, 0.0);
            OrderMath.Plan lowEdge = order(SHIPPED, oak, sell, 4.47, 4000, 64, OrderMath.Limits.NONE, 0.75);
            assertBits(lowEdge.total(), low.total(), "0.0 is priced as 0.75");
            OrderMath.Plan nan = order(SHIPPED, oak, sell, 4.47, 4000, 64, OrderMath.Limits.NONE, Double.NaN);
            OrderMath.Plan one = order(SHIPPED, oak, sell, 4.47, 4000, 64, OrderMath.Limits.NONE, 1.0);
            assertBits(one.total(), nan.total(), "NaN is priced as 1.0");
        }
        assertEquals(4.47 * 1.25, OrderMath.mid(oak, 4.47, 4000, 7.0), 1e-12);
    }

    @Test
    void askAndBidStayInsideTheBand() {
        MarketItem cobble = item("cobblestone");
        assertEquals(5.0, OrderMath.ask(SHIPPED, cobble, 4.99));          // 4.99 x 1.05 > ceiling
        assertEquals(0.10, OrderMath.bid(SHIPPED, cobble, 0.101));        // 0.101 x 0.95 < floor
        assertEquals(4.99 * 1.05, OrderMath.ask(SHIPPED, null, 4.99), 1e-12);   // no item: not clamped
        assertEquals(SHIPPED.buyPrice(1.0), OrderMath.ask(SHIPPED, cobble, 1.0));
        assertEquals(SHIPPED.sellPrice(1.0), OrderMath.bid(SHIPPED, cobble, 1.0));
        assertEquals(0.10, SHIPPED.spread());
    }

    @Test
    void theNeutralMoodIsExactlyOneWithNoCaps() {
        PriceMood mood = PriceMood.NEUTRAL;
        MarketItem iron = item("iron_ingot");
        assertBits(1.0, mood.multiplier("iron_ingot"), "multiplier");
        assertBits(1.0, mood.multiplier("no_such_item"), "multiplier of an unknown id");
        assertEquals(0L, mood.jumpSeq("iron_ingot"));
        assertEquals(0L, mood.eventBuyCap(iron));
        assertEquals(0L, mood.eventSellCap(iron));
        mood.onTrade("iron_ingot", true, 1, 1.0, 1.0);
        mood.catalogChanged();
        assertEquals(OrderMath.Limits.NONE, new OrderMath.Limits(false, 0, 0, 0, 0));
    }

    // ------------------------------------------------------------------ helpers

    private static OrderMath.Plan order(PricingEngine engine, MarketItem item, boolean sell, double base, long stock,
                                        int qty, OrderMath.Limits lim, double m) {
        return sell ? OrderMath.sell(engine, item, base, stock, qty, lim, m)
                : OrderMath.buy(engine, item, base, stock, qty, lim, m);
    }

    private static MarketItem item(String id) {
        return CATALOG.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * 0.32's order integration, copied verbatim from {@code MarketService} at ae2e909 (the
     * {@code Plan} record, {@code simulateBuy}, {@code simulateSell}, and the private
     * {@code ask}/{@code bid}). Do not edit: it is the yardstick for "bit-identical at m = 1".
     */
    private static final class Oracle {

        private final PricingEngine engine;

        Oracle(PricingEngine engine) {
            this.engine = engine;
        }

        /** A previewed/executed order: how many units, the integrated total, and the resulting price/stock. */
        record Plan(int filled, double total, double endPrice, long endStock) {
        }

        /**
         * The spread-adjusted ask, held inside the item's band. floor/ceiling are a hard
         * contract on every price a player ever sees or pays — the spread widens the mid
         * within the band, it never pushes a quote outside it.
         */
        private double ask(MarketItem item, double mid) {
            double price = engine.buyPrice(mid);
            return item == null ? price : PricingEngine.clamp(price, item.floor(), item.ceiling());
        }

        /** The spread-adjusted bid, held inside the item's band. Mirrors {@link #ask}. */
        private double bid(MarketItem item, double mid) {
            double price = engine.sellPrice(mid);
            return item == null ? price : PricingEngine.clamp(price, item.floor(), item.ceiling());
        }

        private Plan simulateBuy(MarketItem item, double startPrice, long startStock, int want,
                                boolean limited, double maxMoney, double remainingMoney,
                                long maxUnits, long remainingUnits) {
            long stock = startStock;
            double price = PricingEngine.clamp(startPrice, item.floor(), item.ceiling());
            double total = 0;
            int filled = 0;
            int cap = (int) Math.max(0, Math.min(want, stock));
            if (limited && maxUnits > 0) {
                cap = (int) Math.min(cap, remainingUnits);
            }
            for (; filled < cap; filled++) {
                double unit = ask(item, price);
                if (limited && maxMoney > 0 && total + unit > remainingMoney) {
                    break; // this unit would exceed the daily spend cap
                }
                total += unit;
                stock -= 1;
                price = engine.nextPrice(item, price, stock);
            }
            return new Plan(filled, total, price, stock);
        }

        private Plan simulateSell(MarketItem item, double startPrice, long startStock, int want,
                                 boolean limited, double maxMoney, double remainingMoney,
                                 long maxUnits, long remainingUnits) {
            long stock = startStock;
            double price = PricingEngine.clamp(startPrice, item.floor(), item.ceiling());
            double total = 0;
            int filled = 0;
            int cap = Math.max(0, want);
            if (limited && maxUnits > 0) {
                cap = (int) Math.min(cap, remainingUnits);
            }
            for (; filled < cap; filled++) {
                double unit = Math.max(0.0, bid(item, price));
                if (limited && maxMoney > 0 && total + unit > remainingMoney) {
                    break; // this unit would exceed the daily earning cap
                }
                total += unit;
                stock += 1;
                price = engine.nextPrice(item, price, stock);
            }
            return new Plan(filled, total, price, stock);
        }
    }
}
