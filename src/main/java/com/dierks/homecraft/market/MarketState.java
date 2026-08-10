package com.dierks.homecraft.market;

/**
 * The moving, persisted state of a market commodity: its current (mid) price and
 * the real {@code stock} the market holds. Mutable and cached in memory; written
 * through to SQLite on every change so it survives restarts.
 *
 * <p>{@code stock} is real, positive, held inventory — selling to the market
 * <b>adds</b> to it, buying <b>subtracts</b>, and it is floored at 0. A negative
 * value is a persistence sentinel meaning "not yet seeded" (see {@link #isSeeded()}),
 * used to migrate Phase 2 rows forward to a config-defined starting stock.
 */
public final class MarketState {

    private final String itemId;
    private double currentPrice;
    private long stock;
    private long updatedAt;

    public MarketState(String itemId, double currentPrice, long stock, long updatedAt) {
        this.itemId = itemId;
        this.currentPrice = currentPrice;
        this.stock = stock;
        this.updatedAt = updatedAt;
    }

    public String itemId() {
        return itemId;
    }

    public double currentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public long stock() {
        return stock;
    }

    /** Set stock, flooring at 0 (the market never holds negative inventory). */
    public void setStock(long stock) {
        this.stock = Math.max(0L, stock);
    }

    /** @return false if this row still carries the "unseeded" sentinel (stock &lt; 0). */
    public boolean isSeeded() {
        return stock >= 0L;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
