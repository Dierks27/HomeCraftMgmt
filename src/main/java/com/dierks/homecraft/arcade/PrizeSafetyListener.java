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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
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
 * picked back up only by them, and no hopper takes it. Without this, a child's first creeper would take
 * the cap she was given, and with one of each for good it could never be bought again.
 *
 * <p>What counts is decided in {@link PrizeSafety}: a prize ({@code hcm:token_prize}) that holds
 * nothing but candy, and, on the ground, is not candy itself. The spawn, despawn and damage handlers
 * run for every item on the server, so they first read the item's read-only data view and return at
 * once without the tag; only a prize has its meta read.
 *
 * <p>A prize bundle (the Trick-or-Treat Bag, 0.37.1) takes only candy.
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

    /**
     * The guard: a prize that is kept AND back in the drops (another plugin put it there after LOW)
     * would be copied into a grave. HIGHEST runs before a grave plugin's MONITOR, so it leaves the
     * drops here, with one warning naming the case.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeathGuard(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        int removed = PrizeSafety.dropKeptTwice(event.getDrops(), event.getItemsToKeep(),
                PrizeSafetyListener::keepOnDeath);
        if (removed > 0) {
            plugin.getLogger().warning("Prize safety: " + removed + " prize stack(s) at " + event.getEntity().getName()
                    + "'s death were both kept and back in the drops (another plugin put them there) - taken out "
                    + "of the drops, so no grave copies them.");
        }
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

    /**
     * A hopper doesn't take a dropped collector: the owner set on the drop is checked only when a
     * player walks over it, so a hopper under it would otherwise hand it to whoever opens the chest.
     * HIGH, after the Games' kit delete (LOWEST); the data view only, as this runs every hopper tick.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        if (PrizeSafety.hopperRefuses(prizeId(item.getItemStack()), item.getOwner() != null)) {
            event.setCancelled(true);
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

    // ---- the prize bundle (0.37.1) ---------------------------------------------------------

    /**
     * A prize bundle takes only candy (D15). Keyed to {@link BundleMeta}, not to the bag's id, so it guards any
     * prize bundle: cancelled when the clicked item is one and the cursor holds something that isn't candy, or
     * the cursor is one and the clicked slot holds something that isn't. Geyser sends Bedrock's bundle actions as
     * these clicks. Picking the bag up or putting it down (nothing to insert) is left alone.
     *
     * <p>In a Creative player's own inventory the "cursor" is the slot's new stack, and the server no longer
     * holds the bag while it is on her cursor, so nothing there is refused ({@link PrizeSafety#creativeSet}): a
     * candy put in her bag is a move, and a bag set holding a block is set with only its candy. Her screen is
     * sent her inventory again next tick, because her client already drew the stack it sent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBundleInsert(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent creative) {
            ItemStack cleaned = creativeCleaned(creative.getCursor());
            if (cleaned != null) {
                creative.setCursor(cleaned);
                if (event.getWhoClicked() instanceof Player player) {
                    tellOnlyCandy(player);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.updateInventory();
                        }
                    });
                }
            }
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (refusesInsert(clicked, cursor) || refusesInsert(cursor, clicked)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                tellOnlyCandy(player);
            }
        }
    }

    private static void tellOnlyCandy(Player player) {
        player.sendMessage(Text.of("&7Only candy goes in this bag."));
        Sounds.refused(player);
    }

    /** {@link PrizeSafety#refusesInsert} for real items: most clicks aren't bundles, so the type name goes first. */
    private static boolean refusesInsert(ItemStack bundle, ItemStack inserted) {
        if (bundle == null || inserted == null || inserted.getType().isAir()
                || !bundle.getType().name().endsWith("BUNDLE")) {
            return false;
        }
        return PrizeSafety.refusesInsert(prize(bundle) && bundle.getItemMeta() instanceof BundleMeta, candy(inserted));
    }

    /**
     * {@link PrizeSafety#creativeSet} for the stack a Creative player sets in a slot: a copy of a prize bundle
     * holding only its candy when it held something else, or null to set the stack as it is. One whose items
     * can't be read is set as it is: {@link #keepOnDeath} doesn't protect it, and a bag is never deleted.
     */
    private static ItemStack creativeCleaned(ItemStack set) {
        if (set == null || !set.getType().name().endsWith("BUNDLE") || !prize(set)) {
            return null;
        }
        try {
            if (!(set.getItemMeta() instanceof BundleMeta bundle)
                    || PrizeSafety.creativeSet(true, PrizeSafety.holdsOnlyCandy(contents(set)))
                            != PrizeSafety.CreativeSet.CLEAN) {
                return null;
            }
            bundle.setItems(PrizeSafety.onlyCandy(bundle.getItems(), PrizeSafetyListener::candy));
            ItemStack cleaned = set.clone();
            cleaned.setItemMeta(bundle);
            return cleaned;
        } catch (RuntimeException e) {
            return null;
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
