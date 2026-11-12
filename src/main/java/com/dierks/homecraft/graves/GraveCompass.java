package com.dierks.homecraft.graves;

import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.function.LongPredicate;

/**
 * The grave compass: given on respawn after a death that made a grave, and by {@code /hcm graves compass}. A lodestone
 * compass with no lodestone, pointing at the grave's block (in another world it spins, and its text says which world).
 * Tagged with the grave's id ({@link Keys#GRAVE_COMPASS}).
 *
 * <p>It is only a pointer, so it is never worth anything: it never goes in a grave (a death deletes it, and the next
 * grave gets a new one), it can't be crafted with, and once its grave is closed it crumbles wherever a player sees
 * it (their own bag every refresh, any container when it's opened). Dying over and over makes no stack of compasses.
 */
public final class GraveCompass {

    private GraveCompass() {
    }

    /** A compass pointing at {@code g}, or null when its world isn't loaded. */
    public static ItemStack of(Grave g) {
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            return null;
        }
        ItemStack item = new ItemStack(Material.COMPASS);
        if (!(item.getItemMeta() instanceof CompassMeta meta)) {
            return null;
        }
        meta.setLodestone(new Location(w, g.x(), g.y(), g.z()));
        meta.setLodestoneTracked(false); // no lodestone block needed, and none is looked for
        meta.displayName(Text.of("&6Compass to " + g.ownerName() + "'s grave").decoration(
                net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(Text.of("&7Points to the grave at"), Text.of("&f" + GraveService.where(g)),
                Text.of("&8It crumbles once the things are back.")));
        meta.getPersistentDataContainer().set(Keys.GRAVE_COMPASS, PersistentDataType.LONG, g.id());
        item.setItemMeta(meta);
        return item;
    }

    /** The grave id on a grave compass, or -1 when the item isn't one. Reads the data view: cheap. */
    public static long graveId(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || Keys.GRAVE_COMPASS == null) {
            return -1;
        }
        try {
            Long id = item.getPersistentDataContainer().get(Keys.GRAVE_COMPASS, PersistentDataType.LONG);
            return id == null ? -1 : id;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static boolean is(ItemStack item) {
        return graveId(item) >= 0;
    }

    /** Take every grave compass out of {@code inv} whose grave {@code remove} says goes; returns how many went. */
    public static int removeFrom(Inventory inv, LongPredicate remove) {
        int removed = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            long id = graveId(contents[i]);
            if (id >= 0 && remove.test(id)) {
                inv.setItem(i, null);
                removed++;
            }
        }
        return removed;
    }
}
