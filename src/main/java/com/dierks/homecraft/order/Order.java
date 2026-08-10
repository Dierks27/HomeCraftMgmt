package com.dierks.homecraft.order;

import java.util.UUID;

/**
 * A placed Amazon order: goods already paid for and pulled from market stock,
 * awaiting real-time delivery. {@code deliverAt} is an absolute epoch-ms
 * timestamp, so deliveries are restart-safe.
 */
public record Order(long id, UUID player, String itemId, int qty,
                    double itemCost, double shippingCost, String tier,
                    long placedAt, long deliverAt, Status status) {

    public enum Status {
        /** Paid, stock consumed, timer running. */
        IN_TRANSIT,
        /** Timer elapsed — a package waiting to be collected at the PC. */
        READY,
        /** Collected by the player. */
        COLLECTED
    }
}
