package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Prizes are safe (HALLOWEEN-SPEC D9, on for everyone): kept when you die, never despawning, burning
 * or taken by a mob on the ground, and never changed by a workstation. A collector someone drops can be
 * picked back up only by them. Without this, a child's first creeper would take the cap she was given,
 * and with one of each for good it could never be bought again.
 *
 * <p>What counts is decided in {@link PrizeSafety}: a prize ({@code hcm:token_prize}) that holds
 * nothing but candy, and, on the ground, is not candy itself. The spawn, despawn and damage handlers
 * run for every item on the server, so they first read the item's read-only data view and return at
 * once without the tag; only a prize has its meta read.
 *
 * <p>Already covered elsewhere, unchanged: crafting, crafters, brewing and villager trades, and placed
 * prize heads ({@link PrizeItemListener}). The void can't be saved.
 */
public final class PrizeSafetyListener implements Listener {

    private final HomeCraftManagement plugin;

    public PrizeSafetyListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    // ---- what an item is -----------------------------------------------------------------

    /** Carries {@code hcm:token_prize}. Reads the data view, so no meta is copied: safe on the hot path. */
    static boolean prize(ItemStack item) {
        if (item == null || Keys.TOKEN_PRIZE == null || item.getType().isAir()) {
            return false;
        }
        try {
            return item.getPersistentDataContainer().has(Keys.TOKEN_PRIZE, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Halloween candy, known by its own key ({@link Keys#CANDY_ITEM}), never by an id prefix. */
    static boolean candy(ItemStack item) {
        if (item == null || Keys.CANDY_ITEM == null || item.getType().isAir()) {
            return false;
        }
        try {
            return item.getPersistentDataContainer().has(Keys.CANDY_ITEM);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The prize id from the data view, or null. */
    private static String prizeId(ItemStack item) {
        if (!prize(item)) {
            return null;
        }
        try {
            return item.getPersistentDataContainer().get(Keys.TOKEN_PRIZE, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * What the item holds, one "is it candy?" per stack: a bundle's items and a container's slots, read
     * the way {@link TokenPrizes#carries} reads them. Empty when it holds nothing. If it can't be read,
     * it counts as holding something that isn't candy, so a bag that can't be checked isn't protected.
     */
    private static List<Boolean> contents(ItemStack item) {
        List<Boolean> out = new ArrayList<>();
        if (!item.hasItemMeta()) {
            return out;
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof InventoryHolder holder) {
                for (ItemStack inside : holder.getInventory().getContents()) {
                    add(out, inside);
                }
            }
            if (meta instanceof BundleMeta bundle) {
                for (ItemStack inside : bundle.getItems()) {
                    add(out, inside);
                }
            }
        } catch (RuntimeException e) {
            out.add(false);
        }
        return out;
    }

    private static void add(List<Boolean> out, ItemStack inside) {
        if (inside != null && !inside.getType().isAir()) {
            out.add(candy(inside));
        }
    }

    /** {@link PrizeSafety#keepOnDeath} for a real item. */
    static boolean keepOnDeath(ItemStack item) {
        return prize(item) && PrizeSafety.keepOnDeath(true, contents(item));
    }

    /** {@link PrizeSafety#keepOnGround} for a real item. */
    static boolean keepOnGround(ItemStack item) {
        return prize(item) && PrizeSafety.keepOnGround(true, candy(item), contents(item));
    }

    /** Never despawns, can't be hurt, and no mob picks it up (a zombie would wear a dropped cap). */
    private static void protect(Item item) {
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
        item.setCanMobPickup(false);
    }

    // ---- death -----------------------------------------------------------------------------

    /**
     * Prizes stay with you. LOW runs after the Games' LOWEST revive, which cancels the death (so this
     * skips), and long before a grave plugin's MONITOR: a grave is built from the drops, and a kept
     * prize has left them, so it is never in a grave and never copied.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        PrizeSafety.moveIf(event.getDrops(), event.getItemsToKeep(), PrizeSafetyListener::keepOnDeath);
    }

    // ---- on the ground ---------------------------------------------------------------------

    /** After the Games' kit cancel (LOWEST), so only an item that is really spawning is touched. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        if (prize(stack) && keepOnGround(stack)) {
            protect(item);
        }
    }

    /** A dropped collector can be picked back up only by the one who dropped it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        if (PrizeSafety.ownerBound(prizeId(item.getItemStack()))) {
            item.setOwner(event.getPlayer().getUniqueId());
        }
    }

    /** An item from a chunk saved before this version has no unlimited lifetime yet: keep it now. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDespawn(ItemDespawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (prize(stack) && keepOnGround(stack)) {
            event.setCancelled(true);
            protect(event.getEntity());
        }
    }

    /**
     * Fire, lava, cactus and explosions. Cancelled here, at NORMAL, so the Mini retirement at MONITOR
     * (which ignores cancelled events) never retires anything for it. The void can't be saved.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item) || event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (prize(stack) && keepOnGround(stack)) {
            event.setCancelled(true);
        }
    }

    // ---- workstations ----------------------------------------------------------------------
    //
    // Crafting is refused already (PrizeItemListener). These are the rest of the ways to change a
    // prize: a cauldron washes the dye off, an anvil renames or repairs, a smithing table re-trims, a
    // grindstone strips, and an enchanting table would put Protection on a cap that gives no armour.

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onWash(CauldronLevelChangeEvent event) {
        if (event.getReason() != CauldronLevelChangeEvent.ChangeReason.ARMOR_WASH
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (TokenPrizes.is(player.getInventory().getItemInMainHand())
                || TokenPrizes.is(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&7That's a prize - it stays just as it is."));
            Sounds.refused(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent event) {
        if (carriesAny(event.getInventory())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSmithing(PrepareSmithingEvent event) {
        if (carriesAny(event.getInventory())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        if (carriesAny(event.getInventory())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (TokenPrizes.is(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (TokenPrizes.is(event.getItem())) {
            event.setCancelled(true);
        }
    }

    private static boolean carriesAny(Inventory inventory) {
        for (ItemStack in : inventory.getContents()) {
            if (TokenPrizes.carries(in)) {
                return true;
            }
        }
        return false;
    }
}
