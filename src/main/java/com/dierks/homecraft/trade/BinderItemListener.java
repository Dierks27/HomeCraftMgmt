package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.BinderMenu;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Opens the Card Binder GUI when a player right-clicks a binder item in hand (Round 3a).
 */
public final class BinderItemListener implements Listener {

    private final HomeCraftManagement plugin;

    public BinderItemListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!plugin.binder().items().isBinder(event.getItem())) {
            return;
        }
        // Let a custom block's own handler win if they're clicking one.
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && plugin.blockService().at(event.getClickedBlock().getLocation()).isPresent()) {
            return;
        }
        event.setCancelled(true);
        new BinderMenu(plugin, event.getPlayer(), false).open(event.getPlayer());
    }
}
