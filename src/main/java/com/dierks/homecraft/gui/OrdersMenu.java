package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.order.Order;
import com.dierks.homecraft.order.OrderService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "My Orders" — the player's active deliveries. In-transit orders show a live
 * countdown; ready orders show "click to collect" and hand over the goods.
 */
public final class OrdersMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;

    public OrdersMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&bMy Orders"));
    }

    @Override
    protected void build() {
        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        OrderService orders = plugin.orderService();
        List<Order> list = orders.ordersFor(player);
        if (list.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No active orders",
                    "&8Order something from the Amazon Store."), null);
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < PAGE_SIZE && i < list.size(); i++) {
            Order order = list.get(i);
            MarketItem item = plugin.market().item(order.itemId());
            Material icon = item != null ? item.material() : Material.BARRIER;
            String label = item != null ? item.label() : order.itemId();

            if (order.status() == Order.Status.READY) {
                set(i, Menus.icon(icon, "&a" + label + " &7x" + order.qty(),
                        "&7Tier: &f" + order.tier(),
                        "&aReady to collect!",
                        "&8—",
                        "&eClick to collect"), e -> {
                    OrderService.CollectResult r = orders.collect(player, order.id());
                    player.sendMessage(r.ok()
                            ? Text.of("&aCollected &f" + order.qty() + " " + label + "&a.")
                            : Text.of("&c" + r.error()));
                    refresh();
                });
            } else {
                set(i, Menus.icon(icon, "&f" + label + " &7x" + order.qty(),
                        "&7Tier: &f" + order.tier(),
                        "&7Arrives in &f" + Menus.duration(order.deliverAt() - now),
                        "&8In transit…"), null);
            }
        }

        set(49, Menus.icon(Material.ARROW, "&eBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }
}
