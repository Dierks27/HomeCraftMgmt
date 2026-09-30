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

    /** The chestplate slot of a player's inventory (36 boots, 37 leggings, 38 chestplate, 39 helmet). */
    public static final int CHESTPLATE = 38;

    /**
     * A player's inventory as a kit write sees it, over any item type: the server's
     * {@link PlayerInventory} ({@link #slots(PlayerInventory)}), or plain strings in a test. The kit
     * rules ({@link #put(Slots, int, Object)}, {@link #clear(Slots, int)}) are the same either way.
     */
    public interface Slots<I> {
        I get(int slot);

        void set(int slot, I item);

        /** The first empty storage slot, or {@code -1}. */
        int firstEmpty();

        /** Whether {@code item} is nothing there (null or air). */
        boolean empty(I item);

        /** Whether {@code item} (not empty) is a game's kit item, never the player's own. */
        boolean kit(I item);
    }

    /**
     * Put a kit item in {@code slot} without ever overwriting the player's own things (the Clubhouse
     * review, #3): a non-kit item there (an auction win or a Mini delivered mid-session) moves to an
     * empty storage slot first, to be banked at the session's end. With no empty slot the kit item
     * isn't placed and their item stays (Leave game is also {@code /hcm leave}).
     *
     * <p>EVERY kit item a game hands out goes through here, and every kit slot a game empties goes
     * through {@link #clear}: an auction or a Mini can land in any empty slot at any moment of a
     * session, whatever game it is (final gate #16; KitSlotsTest holds the games to it).
     *
     * @return whether the kit item is in {@code slot} now
     */
    public static boolean put(PlayerInventory inv, int slot, ItemStack kit) {
        return put(slots(inv), slot, kit);
    }

    /** {@link #put(PlayerInventory, int, ItemStack)} over any {@link Slots}. */
    public static <I> boolean put(Slots<I> inv, int slot, I kit) {
        I there = inv.get(slot);
        boolean own = !inv.empty(there) && !inv.kit(there);
        int to = moveTo(own, own ? inv.firstEmpty() : -1);
        if (to == NO_ROOM) {
            return false;
        }
        if (to >= 0) {
            inv.set(to, there);
        }
        inv.set(slot, kit);
        return true;
    }

    /**
     * Take a kit item out of {@code slot} (the offer's second button, a warm-up's Ready) — and ONLY a
     * kit item: a thing of the player's own that landed there (an auction win, a Mini) stays, to be
     * banked at the session's end (final gate #16). Never {@code setItem(slot, null)} in a session.
     *
     * @return whether a kit item was taken out
     */
    public static boolean clear(PlayerInventory inv, int slot) {
        return clear(slots(inv), slot);
    }

    /** {@link #clear(PlayerInventory, int)} over any {@link Slots}. */
    public static <I> boolean clear(Slots<I> inv, int slot) {
        I there = inv.get(slot);
        if (inv.empty(there) || !inv.kit(there)) {
            return false;
        }
        inv.set(slot, null);
        return true;
    }

    /** The server's player inventory as {@link Slots}. */
    public static Slots<ItemStack> slots(PlayerInventory inv) {
        return new Slots<>() {
            @Override
            public ItemStack get(int slot) {
                return inv.getItem(slot);
            }

            @Override
            public void set(int slot, ItemStack item) {
                inv.setItem(slot, item);
            }

            @Override
            public int firstEmpty() {
                return inv.firstEmpty();
            }

            @Override
            public boolean empty(ItemStack item) {
                return item == null || item.getType().isAir();
            }

            @Override
            public boolean kit(ItemStack item) {
                return isKit(item);
            }
        };
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
