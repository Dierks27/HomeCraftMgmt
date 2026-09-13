package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.courier.HandoverMenu;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Everything the delivery site needs the world to respect.
 *
 * <p>Three jobs, and each closes a hole the rest of the module cannot close by itself: the
 * recipient villager is a fixture rather than a mob to trade with or kill, the building is not
 * a pile of free blocks, and a restore that could not run when the delivery ended gets its
 * chance the moment its chunk comes back.
 */
public final class CourierListener implements Listener {

    private final HomeCraftManagement plugin;

    public CourierListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- the recipient --------------------------------------------------------

    /**
     * Right-clicking the recipient opens the hand-over, and never a trade window.
     *
     * <p>Cancelled at {@code HIGHEST} and unconditionally for a tagged villager: a courier
     * villager with a vanilla trade menu would be an emerald pipeline that no part of the
     * economy design accounts for, and it would reopen every delivery.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        Long jobId = jobIdOf(event.getRightClicked());
        if (jobId == null) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        CourierJob job = plugin.courier().active(player);
        if (job == null || job.id() != jobId) {
            // Somebody else's delivery. Say so in character rather than with an error.
            player.sendMessage(Text.of("&7They glance at you, then past you. "
                    + "&oThey are waiting on somebody else's delivery."));
            return;
        }
        new HandoverMenu(plugin, player).open(player);
    }

    /** The recipient cannot be killed, including by a creative-mode player. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (jobIdOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    private Long jobIdOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(Keys.COURIER_JOB, PersistentDataType.LONG);
    }

    // ---- the building ---------------------------------------------------------

    /**
     * A delivery building is scenery, not salvage.
     *
     * <p>Two separate problems, one guard. Mining it hands out free blocks that the restore
     * then deletes — a small faucet, but a faucet — and every block changed inside the region
     * is one the snapshot no longer describes, so the field would come back wrong.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (guard(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (guard(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean guard(Player player, Block block) {
        BuildingService buildings = plugin.courier().buildings();
        DeliverySite site = buildings.siteContaining(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (site == null) {
            return false;
        }
        if (player.hasPermission("hcm.admin")) {
            return false; // an admin clearing up by hand is a deliberate act
        }
        player.sendMessage(Text.of("&7That belongs to the delivery. It will be gone shortly."));
        return true;
    }

    // ---- deferred restores ----------------------------------------------------

    /**
     * A restore that could not run when the delivery ended runs now.
     *
     * <p>This is the common case, not the rare one: a player who gives up on a run walks home,
     * and the chunk the building sits in unloads behind them long before the job expires.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.courier().buildings().onChunkLoad(event.getChunk());
    }

    // ---- the live-job cache ---------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.courier().onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.courier().onQuit(event.getPlayer());
    }
}
