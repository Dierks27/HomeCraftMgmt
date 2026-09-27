package com.dierks.homecraft.market;

/**
 * The live market's one door into the trade path (0.33). {@link MarketService} asks it for a
 * multiplier {@code M} per item, applied to the balanced price whenever a price is quoted, plus
 * the event-only daily caps, and tells it about every trade made at a moved price so the
 * ledger can measure what the sim actually paid out.
 *
 * <pre>price = clamp(balanced × (stock &gt; 0 ? M : 1), floor, ceiling)</pre>
 *
 * <p>(A buy charges the unit that empties the shelf at {@code max(M, 1)}, never below its
 * {@code M = 1} price: {@link OrderMath#buyMid}.)
 *
 * <p>The contract every implementation keeps:
 * <ul>
 *   <li>{@link #multiplier} is exactly {@code 1.0} whenever the sim is off or paused, or the
 *       item is not sim-enabled. {@code MarketService} also squeezes whatever comes back into
 *       the hard {@code [0.75, 1.25]} band ({@link OrderMath#clampMultiplier}), so a bug here
 *       can never take a price past it.</li>
 *   <li>Nothing here ever writes stock or the balanced price ({@link MarketState#currentPrice()}).
 *       Only player trades and the admin stock commands move those.</li>
 *   <li>Every method runs on the main thread and must be cheap: {@link #multiplier} is read on
 *       every price a sign, hologram, GUI or placeholder shows.</li>
 * </ul>
 *
 * <p>{@link #NEUTRAL} is the market with no sim at all: {@code M = 1.0}, no jumps, no event
 * caps, and no-op hooks. With it every price, total and cap is bit-for-bit 0.32's.
 */
public interface PriceMood {

    /** This item's multiplier right now. Exactly {@code 1.0} when the sim has nothing to say. */
    double multiplier(String id);

    /**
     * Goes up by one whenever this item's multiplier jumps by more than 1% between evaluations
     * (a news flash, a forced event, a stop, a reset, pause or resume). A GUI that remembers it
     * when it showed a quote can refuse the confirm after a jump. Drift and HOT/DEAL ramps
     * never bump it. {@code 0} when nothing has ever jumped.
     */
    long jumpSeq(String id);

    /**
     * Units one player may buy of this item per local day while a DEAL or DOWN is running on
     * it (any phase), or {@code 0} for no event cap. It binds bypass holders too.
     */
    long eventBuyCap(MarketItem item);

    /**
     * Units one player may sell of this item per local day while a HOT or UP is running on it,
     * or {@code 0} for no event cap. It binds bypass holders too.
     */
    long eventSellCap(MarketItem item);

    /**
     * Whether players can already see the event behind this item's event cap on one side, so a
     * refusal may name it. For {@code sell == false} (the {@link #eventBuyCap} side): true while
     * a DEAL or DOWN on the item is showing its badge right now. For {@code sell == true} (the
     * {@link #eventSellCap} side): true while a HOT or UP on the item is showing its badge.
     *
     * <p>False during a HOT/DEAL's silent ramp (it is announced only at full strength), for a
     * HOT/DEAL stopped during its ramp, and for an UP/DOWN's badge-less tail. The caps still
     * bind then; {@link MarketService} only words the refusal as the plain daily limit, so a
     * refusal can never tip anyone off to an event nobody has been told about.
     *
     * <p>The default is {@code false}: without an answer, refusals stay neutral. The live
     * market answers it from the item's events with {@code MoodEngine.capEventShown}.
     */
    default boolean eventCapShown(MarketItem item, boolean sell) {
        return false;
    }

    /**
     * A trade just went through at a multiplier other than exactly {@code 1.0}. {@code total}
     * is what was really paid or charged; {@code neutralTotal} is what the same units would
     * have come to at {@code M = 1} from the same starting stock and balanced price
     * ({@link OrderMath#neutralTotal}). Sells add {@code total - neutralTotal} to the sell
     * bonus, buys add {@code neutralTotal - total} to the buy discount; both are signed.
     *
     * <p>Called after the trade committed, so it must not undo anything. It should not throw;
     * if it does, {@code MarketService} logs it and the trade still stands.
     */
    void onTrade(String id, boolean sell, int units, double total, double neutralTotal);

    /**
     * {@link MarketService#reload()} just rebuilt the catalog: items may have come or gone,
     * or had their floor, ceiling or full stock changed.
     */
    void catalogChanged();

    /** No sim: {@code M = 1.0}, no jumps, no event caps, and hooks that do nothing. */
    PriceMood NEUTRAL = new PriceMood() {
        @Override
        public double multiplier(String id) {
            return 1.0;
        }

        @Override
        public long jumpSeq(String id) {
            return 0L;
        }

        @Override
        public long eventBuyCap(MarketItem item) {
            return 0L;
        }

        @Override
        public long eventSellCap(MarketItem item) {
            return 0L;
        }

        @Override
        public void onTrade(String id, boolean sell, int units, double total, double neutralTotal) {
        }

        @Override
        public void catalogChanged() {
        }

        @Override
        public String toString() {
            return "PriceMood.NEUTRAL";
        }
    };
}
