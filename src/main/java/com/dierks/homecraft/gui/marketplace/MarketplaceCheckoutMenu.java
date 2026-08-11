package com.dierks.homecraft.gui.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.marketplace.PalletService;
import com.dierks.homecraft.storage.PalletDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Marketplace checkout: pick a shipping tier for the chosen listing; buying
 * withdraws price + shipping, ships the item to the buyer's Mailbox, and pays the
 * seller minus commission.
 */
public final class MarketplaceCheckoutMenu extends Menu {

    private static final int[] TIER_SLOTS = {11, 12, 13, 14, 15};

    private final Player player;
    private final PalletDao.Listing listing;
    private final Runnable onBack;

    public MarketplaceCheckoutMenu(HomeCraftManagement plugin, Player player,
                                   PalletDao.Listing listing, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.listing = listing;
        this.onBack = onBack;
        init(27, Text.of("&6Checkout"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        ItemStack item = Items.fromBase64(listing.itemB64());
        set(4, item != null ? item.clone() : Menus.icon(Material.BARRIER, "&cUnreadable"), null);

        double price = listing.price();
        List<PluginConfig.ShippingTier> tiers = plugin.config().shipping().tiers();
        for (int i = 0; i < tiers.size() && i < TIER_SLOTS.length; i++) {
            PluginConfig.ShippingTier tier = tiers.get(i);
            double shipping = plugin.orderService().shippingCost(price, tier);
            double total = price + shipping;
            set(TIER_SLOTS[i], Menus.icon(Material.MINECART, "&e" + tier.label(),
                    "&7Arrives in ~&f" + hours(tier.realHours()),
                    "&7Item: &f" + plugin.economy().format(price),
                    "&7Shipping: &f" + plugin.economy().format(shipping),
                    "&7Total: &6" + plugin.economy().format(total),
                    "&8—",
                    "&aClick to buy → ships to your Mailbox"), e -> buy(tier));
        }

        set(18, Menus.balance(plugin, player), null);
        set(22, Menus.icon(Material.ARROW, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private void buy(PluginConfig.ShippingTier tier) {
        PalletService.Result r = plugin.pallets().buy(player, listing.id(), tier);
        if (r.ok()) {
            player.sendMessage(Text.of("&aBought! Ships to your Mailbox — arrives in ~"
                    + Menus.duration(r.deliverAt() - System.currentTimeMillis()) + "."));
        } else {
            player.sendMessage(Text.of("&c" + r.error()));
        }
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private String hours(double h) {
        return (h == Math.floor(h) ? Long.toString((long) h) : String.valueOf(h)) + "h";
    }
}
