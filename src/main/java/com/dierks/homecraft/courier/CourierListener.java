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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryType;
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

    // ---- the package ----------------------------------------------------------

    /**
     * A crate is cargo, not a building block.
     *
     * <p>It is a player head, so without this it can be set down as one — and a placed head is a
     * block somebody can break for a free head, which is the item faucet this whole design is
     * trying not to be.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPackagePlace(BlockPlaceEvent event) {
        if (CourierPackage.is(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Text.of("&7The crate stays with you until it is "
                    + "delivered."));
        }
    }

    /**
     * The crate is not a hat.
     *
     * <p>The item carries an {@code equippable} component that moves it off the head slot, which
     * is the real fix and covers every route at once. This is the second line: the component is
     * a newer API than the plugin's floor, and {@code PlayerArmorChangeEvent} cannot be
     * cancelled, so the armour slot is also guarded here where it still can be.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPackageEquip(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) {
            return;
        }
        // Only ever refuse putting one ON. Refusing what is already in the slot would lock a
        // crate to somebody's head forever if one ever got there — the exact opposite of the
        // point, and unrecoverable without an admin.
        if (CourierPackage.is(event.getCursor()) || CourierPackage.is(swapped(event))) {
            event.setCancelled(true);
        }
    }

    /** The item the number-key swap would put into the clicked slot, if any. */
    private org.bukkit.inventory.ItemStack swapped(InventoryClickEvent event) {
        int button = event.getHotbarButton();
        if (button < 0 || !(event.getWhoClicked() instanceof Player player)) {
            return null;
        }
        return player.getInventory().getItem(button);
    }

    /**
     * Whether the crate survives death.
     *
     * <p>Dropping it is the default, and deliberately so: the grave is the recovery path, and
     * losing it for good is a real consequence rather than a bug. {@code keep_on_death} exists
     * because this is a family server and the younger players should not lose a delivery to a
     * creeper they never saw.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.config().courier().packageKeepOnDeath()) {
            return;
        }
        java.util.List<org.bukkit.inventory.ItemStack> kept = new java.util.ArrayList<>();
        event.getDrops().removeIf(drop -> {
            if (CourierPackage.is(drop)) {
                kept.add(drop.clone());
                return true;
            }
            return false;
        });
        if (kept.isEmpty()) {
            return;
        }
        // Handed back on respawn rather than now: the inventory is being emptied around us.
        Player player = event.getEntity();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (org.bukkit.inventory.ItemStack item : kept) {
                for (org.bukkit.inventory.ItemStack leftover
                        : player.getInventory().addItem(item).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }, 20L);
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
