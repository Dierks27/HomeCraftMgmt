package com.dierks.homecraft.order;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Nudges the order pipeline when a player logs in, so any deliveries that came
 * due while the server ran (or while they were offline) flip to READY and notify
 * them promptly — complementing the periodic scheduler tick.
 */
public final class OrderDeliveryListener implements Listener {

    private final OrderService orders;

    public OrderDeliveryListener(OrderService orders) {
        this.orders = orders;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        orders.tick();
    }
}
