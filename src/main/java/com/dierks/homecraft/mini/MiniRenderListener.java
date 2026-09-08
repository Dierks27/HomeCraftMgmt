package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Migrates Minis minted under an older layout (the retired five-grade ladder) the
 * moment they are seen: on join (inventory + ender chest) and whenever a container
 * is opened. Re-rendering is idempotent and only touches items whose PDC says they
 * are stale, so the cost is a cheap scan.
 */
public final class MiniRenderListener implements Listener {

    private final HomeCraftManagement plugin;

    public MiniRenderListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        refresh(p.getInventory());
        refresh(p.getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof com.dierks.homecraft.gui.Menu) {
            return; // our own GUIs render fresh icons every time
        }
        refresh(event.getInventory());
    }

    private void refresh(Inventory inv) {
        if (inv == null) {
            return;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && plugin.miniService().refreshLegacy(it)) {
                inv.setItem(i, it);
            }
        }
    }
}
