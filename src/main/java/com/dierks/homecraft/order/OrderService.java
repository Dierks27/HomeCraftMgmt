package com.dierks.homecraft.order;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.storage.OrderDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Amazon ordering + real-time shipping. Placing an order charges the item cost
 * (integrated market buy) plus a shipping fee and consumes market stock <b>now</b>;
 * the goods are delivered after the tier's real-time delay and collected at the
 * PC. Deliveries are restart-safe (absolute {@code deliver_at} timestamps checked
 * on a scheduler and on player login).
 */
public final class OrderService {

    public record PlaceResult(boolean ok, String error, Order order, double itemCost, double shippingCost) {
        static PlaceResult fail(String error) {
            return new PlaceResult(false, error, null, 0, 0);
        }
    }

    public record CollectResult(boolean ok, String error, Order order) {
        static CollectResult fail(String error) {
            return new CollectResult(false, error, null);
        }
    }

    private final HomeCraftManagement plugin;
    private final OrderDao dao;
    private final MarketService market;
    private final EconomyService economy;

    public OrderService(HomeCraftManagement plugin, OrderDao dao, MarketService market, EconomyService economy) {
        this.plugin = plugin;
        this.dao = dao;
        this.market = market;
        this.economy = economy;
    }

    /**
     * Base shipping for a tier: a percentage of the item total in PERCENTAGE mode,
     * or the flat fee in FLAT mode. A {@code flat}/{@code prime_flat} fee applies
     * ONLY in FLAT mode — in PERCENTAGE mode every tier uses its {@code percent}.
     */
    private double baseShipping(double itemTotal, PluginConfig.ShippingTier tier) {
        return plugin.config().shipping().mode() == PluginConfig.ShippingMode.PERCENTAGE
                ? itemTotal * tier.percent() / 100.0
                : tier.flat();
    }

    /**
     * Shipping fee for a tier, clamped so a slower tier never costs more than any
     * faster tier (a safety net against a misconfigured tier table).
     */
    public double shippingCost(double itemTotal, PluginConfig.ShippingTier tier) {
        double cost = baseShipping(itemTotal, tier);
        for (PluginConfig.ShippingTier faster : plugin.config().shipping().tiers()) {
            if (faster.realHours() < tier.realHours()) {
                cost = Math.min(cost, shippingCost(itemTotal, faster));
            }
        }
        return Math.max(0, cost);
    }

    public PlaceResult placeOrder(Player player, String itemId, int qty, PluginConfig.ShippingTier tier) {
        if (!economy.isEnabled()) {
            return PlaceResult.fail("The market is offline (no Vault economy).");
        }
        MarketItem item = market.item(itemId);
        if (item == null) {
            return PlaceResult.fail("No market item '" + itemId + "'.");
        }
        MarketService.Plan quote = market.quoteBuy(itemId, qty);
        if (quote.filled() <= 0) {
            return PlaceResult.fail(item.label() + " is out of stock.");
        }
        double itemTotal = quote.total();
        double shipping = shippingCost(itemTotal, tier);
        double total = itemTotal + shipping;

        if (!economy.has(player, total)) {
            return PlaceResult.fail("You can't afford " + economy.format(total)
                    + " (" + economy.format(itemTotal) + " + " + economy.format(shipping) + " shipping).");
        }

        // Charge the item cost + consume stock (goods delivered later, not now).
        MarketService.TradeResult purchase = market.purchaseForOrder(player, itemId, quote.filled());
        if (!purchase.ok()) {
            return PlaceResult.fail(purchase.error());
        }
        if (shipping > 0 && !economy.withdraw(player, shipping)) {
            plugin.getLogger().warning("Shipping fee withdraw failed after purchase for " + player.getName());
        }

        long now = System.currentTimeMillis();
        long deliverAt = now + (long) (tier.realHours() * 3_600_000L);
        try {
            Order order = dao.insert(new Order(0, player.getUniqueId(), itemId, purchase.qty(),
                    purchase.amount(), shipping, tier.label(), now, deliverAt, Order.Status.IN_TRANSIT));
            return new PlaceResult(true, null, order, purchase.amount(), shipping);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to persist order: " + e.getMessage());
            return PlaceResult.fail("Order could not be saved.");
        }
    }

    /** Flip any due in-transit orders to READY and notify online owners. */
    public void tick() {
        try {
            for (Order order : dao.findDue(System.currentTimeMillis())) {
                dao.updateStatus(order.id(), Order.Status.READY);
                Player online = plugin.getServer().getPlayer(order.player());
                if (online != null) {
                    MarketItem item = market.item(order.itemId());
                    String label = item != null ? item.label() : order.itemId();
                    online.sendMessage(Text.of("&aYour order of &f" + order.qty() + " " + label
                            + " &ahas arrived — collect it at your PC."));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to process due orders: " + e.getMessage());
        }
    }

    /** A player's active orders (in transit + ready), soonest first. */
    public List<Order> ordersFor(OfflinePlayer player) {
        try {
            return dao.listByPlayer(player.getUniqueId(), Order.Status.IN_TRANSIT, Order.Status.READY);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to list orders: " + e.getMessage());
            return List.of();
        }
    }

    public CollectResult collect(Player player, long orderId) {
        try {
            Order order = dao.findById(orderId).orElse(null);
            if (order == null || !order.player().equals(player.getUniqueId())) {
                return CollectResult.fail("That order isn't yours.");
            }
            if (order.status() == Order.Status.COLLECTED) {
                return CollectResult.fail("You already collected that order.");
            }
            if (order.status() != Order.Status.READY) {
                return CollectResult.fail("That order is still in transit.");
            }
            MarketItem item = market.item(order.itemId());
            if (item == null) {
                return CollectResult.fail("That item is no longer available.");
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(item.material(), order.qty()));
            leftover.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
            dao.updateStatus(order.id(), Order.Status.COLLECTED);
            return new CollectResult(true, null, order);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to collect order: " + e.getMessage());
            return CollectResult.fail("Order could not be collected.");
        }
    }
}
