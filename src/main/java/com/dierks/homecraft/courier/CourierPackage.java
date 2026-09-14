package com.dierks.homecraft.courier;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The thing you actually carry.
 *
 * <p>Without it the walk is an empty errand: you accept a number, travel, and click a menu. The
 * package is what makes it a delivery — you are given a crate, it is in your hands the whole way,
 * and you hand it over.
 *
 * <p><b>It is worth nothing and must stay worth nothing.</b> Anything that survives its job is a
 * faucet you can mint on demand by taking jobs, in an economy whose whole premise is that
 * material enters only when somebody mines it. So it cannot be placed, stacked, sold, listed or
 * carried into another job — and it is destroyed when the delivery ends, by whatever route it
 * ends.
 *
 * <h2>Kept strictly apart from Minis</h2>
 * A package is a player head. So is a Mini. Confusing the two would be very hard to trace, so
 * neither can ever match the other's test: a Mini is identified by the {@code MINI_*} keys in its
 * PDC ({@code MiniService.identify}), a package by {@link Keys#COURIER_PACKAGE} and nothing else.
 * A package is never minted, never assigned a serial, never appears in the Museum, is never
 * counted in circulation, appraised, or auctionable — because every one of those paths asks for a
 * Mini reference, and a package has none.
 */
public final class CourierPackage {

    private CourierPackage() {
    }

    /**
     * Build the crate for a job.
     *
     * <p>The texture varies by band — a parcel for a local hop, a crate for a long haul — so the
     * item reads as the size of the trip. A blank texture is a plain head, which is what an
     * unconfigured server gets and is perfectly serviceable.
     */
    public static ItemStack create(HomeCraftManagement plugin, CourierJob job) {
        String texture = plugin.config().skinNamed("courier_package." + job.band().configKey());
        ItemStack item = Heads.base(texture);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(plugin.config().courier().packageName()));
        meta.lore(List.of(
                Text.of("&7" + job.band().display() + " delivery"),
                Text.of("&7For the recipient at &fx " + job.wayX() + ", z " + job.wayZ()),
                Text.of("&8—"),
                Text.of("&8Hand this to them in person."),
                Text.of("&8Worth nothing to anyone else.")));
        // One per slot. A stack of crates would look like something you could collect, and the
        // hand-over only ever takes one.
        try {
            meta.setMaxStackSize(1);
        } catch (Throwable ignored) {
            // An older API without per-item stack sizes still gets an unsellable, unwearable
            // package; it just stacks. Nothing downstream depends on this.
        }
        unwearable(meta);
        meta.getPersistentDataContainer().set(Keys.COURIER_PACKAGE, PersistentDataType.LONG,
                job.id());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Stop the crate being worn.
     *
     * <p>A package is a player head, and a player head is a helmet — so without this you can put
     * the delivery on your head, which looks absurd and means the thing you are meant to be
     * carrying is not in your hands.
     *
     * <p>Done through the item's own {@code equippable} component rather than by catching equip
     * events, because there are a lot of ways to put a hat on: clicking the armour slot,
     * shift-clicking, the hotbar swap key, right-clicking in the air, a dispenser firing it at
     * you, or an armour stand. Moving the component's slot off the head disables all of them at
     * once, in the item itself, wherever it ends up. The listener still guards the armour slot as
     * a second line, since {@code PlayerArmorChangeEvent} cannot be cancelled and an older API
     * would simply skip this.
     */
    private static void unwearable(ItemMeta meta) {
        try {
            org.bukkit.inventory.meta.components.EquippableComponent equippable =
                    meta.getEquippable();
            equippable.setSlot(org.bukkit.inventory.EquipmentSlot.HAND);
            equippable.setEquipOnInteract(false);
            equippable.setDispensable(false);
            equippable.setSwappable(false);
            meta.setEquippable(equippable);
        } catch (Throwable ignored) {
            // No equippable component on this API version — the listener carries it instead.
        }
    }

    /** The job this item belongs to, or null if it is not a package at all. */
    public static Long jobIdOf(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(Keys.COURIER_PACKAGE, PersistentDataType.LONG);
    }

    public static boolean is(ItemStack item) {
        return jobIdOf(item) != null;
    }

    /** True if this is the package for exactly this job. */
    public static boolean isFor(ItemStack item, long jobId) {
        Long owner = jobIdOf(item);
        return owner != null && owner == jobId;
    }

    /** True if the player is holding this job's package in their main hand. */
    public static boolean inHand(Player player, long jobId) {
        return isFor(player.getInventory().getItemInMainHand(), jobId);
    }

    /** True if this job's package is anywhere in the player's inventory. */
    public static boolean carried(Player player, long jobId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isFor(item, jobId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Give the package to a player, dropping it at their feet if there is no room.
     *
     * <p>Dropped rather than refused: a full inventory should not cost somebody the job they just
     * accepted, and the crate is worthless to anybody who picks it up.
     */
    public static void give(HomeCraftManagement plugin, Player player, CourierJob job) {
        ItemStack item = create(plugin, job);
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /**
     * Destroy every copy of a job's package the player is carrying.
     *
     * <p>Called on every route a delivery can end by — handed over, abandoned, expired, the job
     * gone entirely. Returns how many were found, which is how the caller knows whether the
     * crate actually came back.
     */
    public static int destroy(Player player, long jobId) {
        return destroyMatching(player.getInventory(), id -> id == jobId)
                + destroyMatching(player.getEnderChest(), id -> id == jobId);
    }

    /**
     * Destroy any package whose job is not the one the player is currently running.
     *
     * <p>The backstop. A package only exists while its delivery does, so an orphan means
     * something went wrong — a crash between the payout and the cleanup, most likely. Rather
     * than trust that every path remembered to tidy up, anything that does not belong to the
     * live job is removed on sight.
     *
     * @param liveJob the job the player is running now, or null if they have none
     */
    public static int destroyOrphans(Player player, Long liveJob) {
        java.util.function.LongPredicate stale =
                id -> liveJob == null || id != liveJob;
        return destroyMatching(player.getInventory(), stale)
                + destroyMatching(player.getEnderChest(), stale);
    }

    private static int destroyMatching(Inventory inventory, java.util.function.LongPredicate match) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            Long owner = jobIdOf(contents[slot]);
            if (owner != null && match.test(owner)) {
                removed += contents[slot].getAmount();
                inventory.setItem(slot, null);
            }
        }
        return removed;
    }
}
