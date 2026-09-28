package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
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
