package com.dierks.homecraft.market;

/**
 * The moving, persisted state of a market item: its current price and a net
 * demand counter (units bought minus units sold). Mutable and cached in memory;
 * written through to SQLite on every change so it survives restarts.
 */
public final class MarketState {

    private final String itemId;
    private double currentPrice;
    private long demand;
    private long updatedAt;

    public MarketState(String itemId, double currentPrice, long demand, long updatedAt) {
        this.itemId = itemId;
        this.currentPrice = currentPrice;
        this.demand = demand;
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

    public long demand() {
        return demand;
    }

    public void setDemand(long demand) {
        this.demand = demand;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
