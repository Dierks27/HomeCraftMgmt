package com.dierks.homecraft.gui;

import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Placeholder "Amazon" storefront opened by right-clicking a placed PC.
 * Phase 1 stub — the real dynamic market + ordering lands in Phase 3. This just
 * proves the PC → GUI gate works end to end.
 */
public final class AmazonHolder implements InventoryHolder {

    private final Inventory inventory;

    public AmazonHolder() {
        this.inventory = Bukkit.createInventory(this, 27, Text.of("&9Amazon"));
        decorate();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(Player player) {
        player.openInventory(new AmazonHolder().getInventory());
    }

    private void decorate() {
        ItemStack filler = named(Material.BLUE_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(13, named(Material.CHEST_MINECART, "&bAmazon — Coming Soon", List.of(
                "&7The online market opens in &fPhase 3&7.",
                "&7Browse dynamic prices, add to cart,",
                "&7choose 1/2/3-day shipping, and collect",
                "&7your packages right here at your PC.")));
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            if (lore != null) {
                meta.lore(lore.stream().map(Text::of).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
