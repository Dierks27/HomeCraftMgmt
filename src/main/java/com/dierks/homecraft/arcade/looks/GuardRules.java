package com.dierks.homecraft.arcade.looks;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the bound-key guard refuses (SHOP-V2-SPEC §3.7), as plain decisions with a test table. A bound key (a rented
 * hat or cap, a Keeper) stays with its owner: it is never placed, dropped, put in any container but the owner's own
 * ender chest, put on a stand, a frame, a pot, a shelf or an allay, or put in a bundle. {@link BoundItemGuard} reads
 * the facts off the event; this decides.
 *
 * <p>The tops are told apart by what they are to the clicker, never by {@code InventoryType} (its constants need a
 * server): one of our menus, the player's own 2x2 crafting grid (the inventory screen), the player's own ender chest
 * ({@code view.getTopInventory().equals(player.getEnderChest())}), or anything else, which another player's ender
 * chest is too.
 */
public final class GuardRules {

    private GuardRules() {
    }

    /** What the open screen's top is to the player clicking. */
    public enum Top {
        /** One of our menus: {@code MenuListener} cancels every click already, so the guard never judges it. */
        MENU,
        /** The player's own inventory screen (its 2x2 crafting grid). */
        OWN_CRAFTING,
        /** The player's own ender chest. */
        OWN_ENDER_CHEST,
        /** Any other container: a chest, a hopper, a furnace, an anvil, another player's inventory or ender chest. */
        OTHER
    }

    /**
     * Whether an inventory click moves a bound key where it may not go, or copies one. In order:
     * <ol>
     *   <li>never when the top is one of our menus;</li>
     *   <li>a bound cursor placed into a top slot (PLACE_*, SWAP_WITH_CURSOR), unless the top is the own crafting
     *       grid or the own ender chest;</li>
     *   <li>a shift-click ({@code MOVE_TO_OTHER_INVENTORY}) of a bound item from the bottom, with the same exception;</li>
     *   <li>a number key ({@code NUMBER_KEY} / {@code HOTBAR_SWAP}) with a bound hotbar item into a top slot (the same
     *       exception);</li>
     *   <li>{@code SWAP_OFFHAND} with a bound off-hand item into a top slot (the same exception);</li>
     *   <li>a bound item into any bundle: a bound cursor onto a bundle, or a bundle on the cursor over a bound item,
     *       in any inventory;</li>
     *   <li>a bound item dropped from a slot or from the cursor (the drop would only be cancelled later) and a
     *       Creative middle-click copy of one ({@code CLONE_STACK}).</li>
     * </ol>
     * Everything else is allowed: moves inside the player's own inventory, onto the armour slots, the 2x2 grid, the
     * own ender chest, and collecting to the cursor. A Creative player's own inventory sends no clicks at all
     * ({@link #creativeRefused}).
     */
    public static boolean clickRefused(Top top, boolean clickedInTop, InventoryAction action, ClickType click,
                                       boolean cursorBound, boolean currentBound, boolean hotbarBound,
                                       boolean offhandBound, boolean cursorIsBundle, boolean currentIsBundle) {
        if (top == Top.MENU || action == null) {
            return false;
        }
        boolean allowedTop = top == Top.OWN_CRAFTING || top == Top.OWN_ENDER_CHEST;
        if ((cursorBound && currentIsBundle) || (cursorIsBundle && currentBound)) {
            return true; // rule 6: into a bundle, anywhere
        }
        switch (action) {
            case DROP_ALL_SLOT, DROP_ONE_SLOT, CLONE_STACK -> {
                return currentBound;
            }
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> {
                return cursorBound;
            }
            default -> {
            }
        }
        if (allowedTop) {
            return false;
        }
        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                return clickedInTop && cursorBound;
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                return !clickedInTop && currentBound;
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (!clickedInTop) {
                    return false;
                }
                return click == ClickType.SWAP_OFFHAND ? offhandBound : hotbarBound;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * A drag of a bound item: refused when any slot it covers is in the top, unless the top is the own crafting grid
     * or the own ender chest (or one of our menus, which cancels every drag itself).
     */
    public static boolean dragRefused(Top top, boolean draggedBound, boolean anyTopSlot) {
        return draggedBound && anyTopSlot && top == Top.OTHER;
    }

    /** A Creative player's own inventory is the client's: a refusal there desyncs it, so it never refuses. */
    public static boolean creativeRefused() {
        return false;
    }

    /** What holds the inventory being closed. */
    public enum Holder {
        /** A block's (a chest, a barrel, a hopper, a shulker box, a double chest...). */
        BLOCK,
        /** An entity that isn't a player (a storage or hopper minecart, a chest boat, a horse, a llama). */
        ENTITY,
        /** A player: an admin's {@code /invsee} of someone's bag. */
        PLAYER,
        /** One of our menus. */
        MENU,
        /** Anything else, or nothing. */
        OTHER
    }

    /**
     * Whether a close sweeps the top for bound keys (a path the guard missed): only a block's or a non-player
     * entity's container. Never a player's own inventory or ender chest someone else is looking at (red team D-M2:
     * EssentialsX's {@code /invsee} and {@code /ec} must change nothing), never the crafting grid, never our menus.
     */
    public static boolean closeSweeps(Holder holder, boolean enderChestType, boolean playerType, boolean craftingType) {
        if (enderChestType || playerType || craftingType) {
            return false;
        }
        return holder == Holder.BLOCK || holder == Holder.ENTITY;
    }

    /** The slots of a closed container that hold a bound key: what the close sweep deletes. */
    public static <T> List<Integer> sweptSlots(List<T> contents, Predicate<T> bound) {
        List<Integer> out = new ArrayList<>();
        if (contents == null) {
            return out;
        }
        for (int i = 0; i < contents.size(); i++) {
            T t = contents.get(i);
            if (t != null && bound.test(t)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * The bound keys inside a container (red-team finding GUARD-1): a bundle or a shulker box that holds one is
     * guarded as the key itself is (no chest, no ground, no frame), since only Creative puts a key inside one and
     * the player's own sweeps keep it there. Read with the sweep's own fail-closed walk ({@link LookRules#find}), so
     * an unreadable container finds nothing; empty for an item that is a key itself, or isn't a container (the
     * material first: one compare for everything else).
     */
    public static <T> List<LookRules.Found> keysInside(T item, Predicate<T> container, Function<T, LookRules.Key> keyOf,
                                                      BiFunction<T, Integer, Optional<List<T>>> contents) {
        if (item == null || !container.test(item)) {
            return List.of();
        }
        List<LookRules.Found> out = new ArrayList<>();
        for (LookRules.Found f : LookRules.find(LookRules.Area.BAG, 0, item, keyOf, contents)) {
            if (f.nested()) {
                out.add(f);
            }
        }
        return out;
    }

    /** The one WARN for a key the close sweep found: a path the guard missed, so it says where. */
    public static String sweptWarning(String inventory, String id, String player) {
        return "Bound key '" + id + "' was found in a closed " + inventory + " (" + player
                + " had it open) - deleted. The guard missed a way in: please report it.";
    }

    /** The entities a bound key in the hand may not be given to. */
    public enum EntityKind { ITEM_FRAME, ARMOR_STAND, ALLAY, OTHER }

    /** A bound key in the used hand on a frame (any), an armor stand or an allay is refused. */
    public static boolean entityRefused(EntityKind kind, boolean handBound) {
        return handBound && kind != EntityKind.OTHER;
    }

    /**
     * A block use with a bound key about: a decorated pot or a chiseled bookshelf with one in the used hand, or any
     * shelf ({@code *_SHELF}) while any hotbar slot or the used hand holds one (a powered shelf swaps the whole
     * hotbar, red team D-m8). The material is tested first, so any other block costs one compare.
     */
    public static boolean blockUseRefused(String material, boolean handBound, boolean anyHotbarBound) {
        if (material == null) {
            return false;
        }
        String m = material.toUpperCase(Locale.ROOT);
        if (m.equals("DECORATED_POT") || m.equals("CHISELED_BOOKSHELF")) {
            return handBound;
        }
        if (m.endsWith("_SHELF")) {
            return handBound || anyHotbarBound;
        }
        return false;
    }

    /** A placed block from a bound key (a head) is refused: a key is never placed. */
    public static boolean placeRefused(boolean handBound) {
        return handBound;
    }

    /** An item entity that is a bound key, or a preview stand's copy, never lands: it is cancelled as it spawns. */
    public static boolean spawnRefused(boolean bound, boolean preview) {
        return bound || preview;
    }

    /** A furnace never smelts a prize (the Knight's Helm is iron). */
    public static boolean smeltRefused(boolean tokenPrize) {
        return tokenPrize;
    }
}
