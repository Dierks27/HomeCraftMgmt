package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.order.OrderService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Checkout: pick a shipping tier for an order. Shows the item cost and, per tier,
 * the shipping fee, real-time delivery delay, and grand total. Clicking a tier
 * places the order (charges item + shipping, consumes stock now, delivers later).
 */
public final class CheckoutMenu extends Menu {

    private static final int[] TIER_SLOTS = {11, 13, 15};

    private final Player player;
    private final MarketItem item;
    private final int qty;
    private final Runnable onBack;

    public CheckoutMenu(HomeCraftManagement plugin, Player player, MarketItem item, int qty, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.item = item;
        this.qty = qty;
        this.onBack = onBack;
        init(27, Text.of("&6Checkout"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        MarketService market = plugin.market();
        OrderService orders = plugin.orderService();
        MarketService.Plan quote = market.quoteBuy(item.id(), qty);
        double itemTotal = quote.total();

        set(4, Menus.icon(item.material(), item.label() + " &7x" + quote.filled(),
                "&7Item cost: &f" + money(itemTotal)), null);

        List<PluginConfig.ShippingTier> tiers = plugin.config().shipping().tiers();
        for (int i = 0; i < TIER_SLOTS.length && i < tiers.size(); i++) {
            PluginConfig.ShippingTier tier = tiers.get(i);
            double shipping = orders.shippingCost(itemTotal, tier);
            String shipLabel = shipping <= 0 ? "&aFREE" : "&f" + money(shipping);
            set(TIER_SLOTS[i], Menus.icon(shippingIcon(i), "&e" + tier.label() + " Shipping",
                    "&7Arrives in &f" + Menus.duration((long) (tier.realHours() * 3_600_000L)),
                    "&7Shipping: " + shipLabel,
                    "&7Total: &f" + money(itemTotal + shipping),
                    "&8—",
                    "&eClick to order"), e -> placeOrder(tier));
        }

        set(22, Menus.icon(Material.BARRIER, "&cBack"), e -> onBack.run());
    }

    private void placeOrder(PluginConfig.ShippingTier tier) {
        OrderService.PlaceResult result = plugin.orderService().placeOrder(player, item.id(), qty, tier);
        if (!result.ok()) {
            player.sendMessage(Text.of("&c" + result.error()));
            refresh();
            return;
        }
        player.sendMessage(Text.of("&aOrder placed: &f" + result.order().qty() + " " + item.label()
                + " &7(" + tier.label() + ", " + money(result.itemCost() + result.shippingCost()) + ")."
                + " &aArrives in " + Menus.duration(result.order().deliverAt() - System.currentTimeMillis()) + "."));
        new OrdersMenu(plugin, player, () -> new StoreMenu(plugin, player).open(player)).open(player);
    }

    private Material shippingIcon(int index) {
        return switch (index) {
            case 0 -> Material.LIME_DYE;   // cheapest / slowest
            case 1 -> Material.YELLOW_DYE;
            default -> Material.ORANGE_DYE; // fastest / priciest
        };
    }

    private String money(double amount) {
        return plugin.economy().format(amount);
    }
}
