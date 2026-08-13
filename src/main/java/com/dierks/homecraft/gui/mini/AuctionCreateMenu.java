package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.trade.AuctionService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Set an auction's terms (starting bid, duration, optional Buy-It-Now) for the
 * Mini currently held in the main hand, then create it.
 */
public final class AuctionCreateMenu extends Menu {

    private final Player player;
    private final Runnable onBack;

    private double startBid = 100;
    private double buyNow = 0;
    private int durationMinutes = 1440; // 1 day

    public AuctionCreateMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(27, Text.of("&6Sell a Mini"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        MiniService.HeldMini held = plugin.miniService().getHeldMini(player);
        set(4, held == null
                ? Menus.icon(Material.BARRIER, "&cHold a Mini in your main hand")
                : held.item().clone(), null);

        Double floor = held != null && plugin.values() != null
                ? plugin.values().suggestedFloorFor(held.item()) : null;
        String floorLore = floor != null ? "&7Suggested floor: &b" + plugin.economy().format(floor)
                : "&8No value estimate";
        String floorHint = floor != null ? " (suggested floor: " + plugin.economy().format(floor) + ")" : "";

        set(10, Menus.icon(Material.GOLD_INGOT, "&eStarting bid", "&f" + plugin.economy().format(startBid),
                floorLore, "&7Click to set"), e -> plugin.chatPrompts().prompt(player,
                "Enter the starting bid" + floorHint + ":", input -> {
            startBid = Math.max(1, parse(input, startBid));
            reopen();
        }));

        set(12, Menus.icon(Material.CLOCK, "&eDuration", "&f" + durationMinutes + " min",
                "&7Click to set (minutes)"), e -> plugin.chatPrompts().prompt(player, "Enter the duration in minutes:", input -> {
            durationMinutes = Math.max(1, (int) parse(input, durationMinutes));
            reopen();
        }));

        set(14, Menus.icon(Material.DIAMOND, "&eBuy-It-Now", buyNow > 0 ? "&f" + plugin.economy().format(buyNow) : "&8off",
                "&7Click to set (0 = off)"), e -> plugin.chatPrompts().prompt(player, "Enter a Buy-It-Now price (0 = off):", input -> {
            buyNow = Math.max(0, parse(input, buyNow));
            reopen();
        }));

        set(16, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Create auction",
                "&7Auctions the Mini in your main hand"), e -> {
            AuctionService.Result r = plugin.auctions().create(player, startBid, buyNow, durationMinutes);
            if (r.ok()) {
                player.sendMessage(Text.of("&aAuction #" + r.auctionId() + " created."));
                back();
            } else {
                player.sendMessage(Text.of("&c" + r.error()));
                reopen();
            }
        });

        set(22, Menus.icon(Material.ARROW, "&cBack"), e -> back());
    }

    private double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            player.sendMessage(Text.of("&cNot a number — unchanged."));
            return fallback;
        }
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private void reopen() {
        open(player);
    }
}
