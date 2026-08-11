package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.MiniInfoMenu;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Places, protects, and reclaims posed armor-stand Minis. Placing an ARMOR_STAND
 * Mini spawns its configured stand (not a plain one); the entity is owner-only
 * (protected from all other damage/manipulation); the owner reclaims it by
 * hitting it; anyone can right-click it to open its info card.
 */
public final class ArmorStandListener implements Listener {

    private final HomeCraftManagement plugin;
    private final StandService stands;

    public ArmorStandListener(HomeCraftManagement plugin, StandService stands) {
        this.plugin = plugin;
        this.stands = stands;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || held.getType() != Material.ARMOR_STAND) {
            return;
        }
        MiniService.MiniRef ref = plugin.miniService().identify(held);
        if (ref == null) {
            return; // a plain armor stand, not a Mini
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        // If the clicked block is one of our custom blocks, let its GUI handle the
        // click instead of placing a stand against it.
        if (plugin.blockService().at(clicked.getLocation()).isPresent()) {
            return;
        }
        Player player = event.getPlayer();
        Location loc = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);

        // Never let the vanilla stand spawn — we place our configured one.
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);

        if (plugin.config().respectTownPerms() && !plugin.protection().canBuild(player, loc)) {
            player.sendMessage(Text.of("&cYou can't place that here."));
            return;
        }

        ItemStack one = held.clone();
        one.setAmount(1);
        stands.spawn(loc, player.getLocation().getYaw(), ref, player.getUniqueId(), one);
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        player.sendMessage(Text.of("&aPlaced your Mini."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!stands.isMiniStand(event.getEntity())) {
            return;
        }
        ArmorStand stand = (ArmorStand) event.getEntity();
        // Owner (or admin) hitting it reclaims it; everything else is blocked.
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player player) {
            event.setCancelled(true);
            boolean owner = player.getUniqueId().equals(stands.ownerOf(stand)) || player.hasPermission("hcm.admin");
            if (owner) {
                stands.reclaim(stand, player);
                player.sendMessage(Text.of("&aReclaimed your Mini."));
            } else {
                player.sendMessage(Text.of("&cThis Mini isn't yours."));
            }
            return;
        }
        event.setCancelled(true); // environmental / mob damage — protected
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (stands.isMiniStand(event.getRightClicked())) {
            event.setCancelled(true); // no swapping a Mini's equipment
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !stands.isMiniStand(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        MiniService.MiniRef ref = stands.refOf(event.getRightClicked());
        if (ref != null) {
            new MiniInfoMenu(plugin, event.getPlayer(), ref,
                    stands.storedItem(event.getRightClicked()), null).open(event.getPlayer());
        }
    }
}
