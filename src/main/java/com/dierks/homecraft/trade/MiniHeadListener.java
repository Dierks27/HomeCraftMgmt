package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.MiniInfoMenu;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Makes a plain Mini head placed as a block a first-class placed Mini: on
 * placement the block records the Mini's identity + exact item in its skull PDC;
 * right-clicking it opens the public info card; breaking it returns the exact
 * Mini (not a plain head). Armor-stand and Display-Case Minis are handled
 * elsewhere — this covers the "just placed the head" case.
 */
public final class MiniHeadListener implements Listener {

    private final HomeCraftManagement plugin;

    public MiniHeadListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return;
        }
        // A PC is also a PLAYER_HEAD custom block — leave it to the block system.
        if (plugin.blockService().itemType(item) != null) {
            return;
        }
        MiniService.MiniRef ref = plugin.miniService().identify(item);
        if (ref == null) {
            return;
        }
        BlockState state = event.getBlockPlaced().getState();
        if (!(state instanceof Skull skull)) {
            return;
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        var pdc = skull.getPersistentDataContainer();
        pdc.set(Keys.MINI_ID, PersistentDataType.STRING, ref.miniId());
        pdc.set(Keys.MINI_UID, PersistentDataType.STRING, ref.uid());
        pdc.set(Keys.MINI_MINT, PersistentDataType.LONG, ref.mintNumber());
        pdc.set(Keys.MINI_OWNER, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
        pdc.set(Keys.MINI_ITEM, PersistentDataType.STRING, Items.toBase64(one));
        skull.update(true, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof Skull skull)) {
            return;
        }
        MiniService.MiniRef ref = plugin.miniService().refFrom(skull.getPersistentDataContainer());
        if (ref == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        ItemStack display = Items.fromBase64(
                skull.getPersistentDataContainer().get(Keys.MINI_ITEM, PersistentDataType.STRING));
        new MiniInfoMenu(plugin, player, ref, display, null).open(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Skull skull)) {
            return;
        }
        var pdc = skull.getPersistentDataContainer();
        if (plugin.miniService().refFrom(pdc) == null) {
            return;
        }
        // Return the exact minted Mini instead of a plain head.
        event.setDropItems(false);
        ItemStack item = Items.fromBase64(pdc.get(Keys.MINI_ITEM, PersistentDataType.STRING));
        if (item != null) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().toCenterLocation(), item);
        }
    }
}
