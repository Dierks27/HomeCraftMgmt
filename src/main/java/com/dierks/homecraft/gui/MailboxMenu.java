package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.marketplace.DeliveryService;
import com.dierks.homecraft.order.Order;
import com.dierks.homecraft.order.OrderService;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mailbox: every delivery a player is owed in one place — PC/Crate store
 * orders AND Marketplace purchases — with in-transit countdowns and ready-to-
 * collect items. Opened from a placed Mailbox block or the PC ("virtual inbox"),
 * so a player is never stuck without a physical Mailbox. Items wait safely here
 * until collected; nothing drops.
 */
public final class MailboxMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;
    private int page;

    public MailboxMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&eMailbox"));
    }

    @Override
    protected void build() {
        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        List<java.util.function.IntConsumer> renders = new ArrayList<>();
        collectEntries(renders);

        int pages = Math.max(1, (int) Math.ceil(renders.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        if (renders.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No deliveries",
                    "&8Store orders & Marketplace buys arrive here."), null);
        }
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= renders.size()) {
                set(i, null, null);
                continue;
            }
            renders.get(idx).accept(i);
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(49, Menus.icon(Material.ARROW, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        if ((page + 1) * PAGE_SIZE < renders.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    /** Build a render action per delivery (store orders first, then Marketplace). */
    private void collectEntries(List<java.util.function.IntConsumer> out) {
        long now = System.currentTimeMillis();
        OrderService orders = plugin.orderService();
        for (Order order : orders.ordersFor(player)) {
            MarketItem item = plugin.market().item(order.itemId());
            Material icon = item != null ? item.material() : Material.BARRIER;
            String label = item != null ? item.label() : order.itemId();
            out.add(s -> {
                if (order.status() == Order.Status.READY) {
                    set(s, Menus.icon(icon, "&a" + label + " &7x" + order.qty(),
                            "&7Store order · " + order.tier(), "&aReady!", "&eClick to collect"), e -> {
                        OrderService.CollectResult r = orders.collect(player, order.id());
                        player.sendMessage(r.ok() ? Text.of("&aCollected &f" + order.qty() + " " + label + "&a.")
                                : Text.of("&c" + r.error()));
                        refresh();
                    });
                } else {
                    set(s, Menus.icon(icon, "&f" + label + " &7x" + order.qty(),
                            "&7Store order · " + order.tier(),
                            "&7Arrives in &f" + Menus.duration(order.deliverAt() - now), "&8In transit…"), null);
                }
            });
        }

        DeliveryService deliveries = plugin.deliveries();
        for (DeliveryDao.Delivery d : deliveries.activeFor(player)) {
            ItemStack stored = Items.fromBase64(d.itemB64());
            out.add(s -> {
                boolean ready = d.status().equals(DeliveryDao.READY);
                ItemStack disp = stored != null ? stored.clone() : Menus.icon(Material.BARRIER, "&c" + d.label());
                ItemMeta meta = disp.getItemMeta();
                if (meta != null) {
                    List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                            ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(Text.of("&8—"));
                    lore.add(Text.of("&7Marketplace delivery"));
                    lore.add(ready ? Text.of("&aReady — click to collect")
                            : Text.of("&7Arrives in &f" + Menus.duration(d.deliverAt() - now)));
                    meta.lore(lore);
                    disp.setItemMeta(meta);
                }
                set(s, disp, ready ? e -> {
                    DeliveryService.Result r = deliveries.collect(player, d.id());
                    player.sendMessage(r.ok() ? Text.of("&aCollected &f" + d.label() + "&a.")
                            : Text.of("&c" + r.error()));
                    refresh();
                } : null);
            });
        }
    }
}
