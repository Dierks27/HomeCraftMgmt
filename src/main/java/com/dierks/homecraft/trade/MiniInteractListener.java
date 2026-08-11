package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.MiniInfoMenu;
import com.dierks.homecraft.mini.MiniService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Right-clicking the air while holding a Mini opens its read-only info card. Using
 * air (not a block) keeps this from clashing with placing a head or opening a
 * custom-block GUI.
 */
public final class MiniInteractListener implements Listener {

    private final HomeCraftManagement plugin;

    public MiniInteractListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        MiniService.HeldMini held = plugin.miniService().getHeldMini(player);
        if (held == null) {
            return;
        }
        event.setCancelled(true);
        new MiniInfoMenu(plugin, player, held.ref(), held.item().clone(), null).open(player);
    }
}
