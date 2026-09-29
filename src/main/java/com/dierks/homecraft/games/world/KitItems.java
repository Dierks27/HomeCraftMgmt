package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * A world game's kit: the only physical things a game ever hands out (spec §7.4, R3.8).
 *
 * <p>Every kit item carries {@link Keys#GAME_KIT} = {@code "<gameId>:<action>"} and says so in its
 * lore ("Game item — stays in the game"). The tag is what keeps the kit inside the game: the kit
 * guard refuses to let it be dropped, placed, stored, traded or sold, and any tagged item found
 * outside a session is swept. The action is what a click on it means to the game
 * ({@link Game#onKitUse}).
 */
public final class KitItems {

    /** The lore line every kit item ends with. */
    public static final String STAYS = "&8Game item — stays in the game";

    private KitItems() {
    }

    /** A kit item for {@code game} that means {@code action} when used. */
    public static ItemStack item(Game game, String action, Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            List<Component> lines = new ArrayList<>(lore.length + 1);
            for (String line : lore) {
                lines.add(Text.of(line));
            }
            lines.add(Text.of(STAYS));
            meta.lore(lines);
            meta.getPersistentDataContainer().set(Keys.GAME_KIT, PersistentDataType.STRING, game.id() + ":" + action);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Put a kit item in {@code slot} without ever overwriting the player's own things (the Clubhouse
     * review, #3): a non-kit item there (an auction win or a Mini delivered mid-session) moves to an
     * empty storage slot first, to be banked at the session's end. With no empty slot the kit item
     * isn't placed and their item stays (Leave game is also {@code /hcm leave}).
     *
     * @return whether the kit item is in {@code slot} now
     */
    public static boolean put(PlayerInventory inv, int slot, ItemStack kit) {
        ItemStack there = inv.getItem(slot);
        boolean own = there != null && !there.getType().isAir() && !isKit(there);
        int to = moveTo(own, own ? inv.firstEmpty() : -1);
        if (to == NO_ROOM) {
            return false;
        }
        if (to >= 0) {
            inv.setItem(to, there);
        }
        inv.setItem(slot, kit);
        return true;
    }

    /** {@link #moveTo}: nothing of theirs to move. */
    static final int STAYS_PUT = -2;
    /** {@link #moveTo}: theirs is there and there is nowhere to move it. */
    static final int NO_ROOM = -1;

    /**
     * Where the player's own item in a kit slot goes: {@link #STAYS_PUT} when the slot holds none of
     * theirs, the first empty storage slot, or {@link #NO_ROOM}.
     */
    static int moveTo(boolean ownThere, int firstEmpty) {
        if (!ownThere) {
            return STAYS_PUT;
        }
        return firstEmpty >= 0 ? firstEmpty : NO_ROOM;
    }

    /** Whether the item is any game's kit item. */
    public static boolean isKit(ItemStack item) {
        return tag(item) != null;
    }

    /** The kit item's action, or {@code null} if it is not a kit item. */
    public static String action(ItemStack item) {
        String tag = tag(item);
        if (tag == null) {
            return null;
        }
        int colon = tag.indexOf(':');
        return colon < 0 ? "" : tag.substring(colon + 1);
    }

    /** The game the kit item belongs to, or {@code null} if it is not a kit item. */
    public static String gameId(ItemStack item) {
        String tag = tag(item);
        if (tag == null) {
            return null;
        }
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(0, colon);
    }

    private static String tag(ItemStack item) {
        if (item == null || Keys.GAME_KIT == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(Keys.GAME_KIT, PersistentDataType.STRING);
    }
}
