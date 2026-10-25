package com.dierks.homecraft.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * The {@code hcm:token_prize} tag, and the one sentence every money surface says about it.
 *
 * <p>Tokens buy fun; dollars buy things you keep. So nothing bought or won with tokens can be
 * turned into dollars: the house market, the Marketplace and its Pallets, Vending Machines, the
 * Auction House and Courier cargo all refuse a tagged item. Cards, packs, filament and HomeCraft
 * blocks are deliberately left untagged — they are Mini-making materials, not token prizes — except
 * the Firework Launcher (0.38.1), which only tokens buy, so it is tagged wherever it is made.
 */
public final class TokenPrizes {

    /** What a player is told when they try to sell one. */
    public static final String REFUSAL = "Arcade prizes can't be sold.";

    /** How deep {@link #carries} and {@link #contents} open containers inside containers (the item itself is 0). */
    public static final int MAX_DEPTH = 4;

    private TokenPrizes() {
    }

    /**
     * True if the item was bought or won with tokens — or is a world game's kit item
     * ({@link Keys#GAME_KIT}, spec §7.4): a kit elytra or rocket is a real item, so every money
     * surface that already refuses a prize (and the craft, brew and trade blocks) refuses it too.
     */
    public static boolean is(ItemStack item) {
        return tagged(item, false);
    }

    /** {@link #is}, with {@code unreadable} the answer for an item whose tags can't be read. */
    private static boolean tagged(ItemStack item, boolean unreadable) {
        if (item == null || !item.hasItemMeta() || Keys.TOKEN_PRIZE == null) {
            return false;
        }
        try {
            var pdc = item.getItemMeta().getPersistentDataContainer();
            return pdc.has(Keys.TOKEN_PRIZE, PersistentDataType.STRING)
                    || (Keys.GAME_KIT != null && pdc.has(Keys.GAME_KIT, PersistentDataType.STRING));
        } catch (RuntimeException e) {
            return unreadable;
        }
    }

    /**
     * True if the item is a prize or holds one — a shulker box or a bundle with a prize inside
     * is a prize for every money surface, or boxing it up would be the way round the rule.
     *
     * <p><b>It fails closed</b> (0.37.3, D36): an item whose tags or contents can't be read counts as carrying a
     * prize, and so does one that holds anything deeper than {@link #MAX_DEPTH}, which is never opened. A prize six
     * bundles deep used to pass, and went onto a Pallet.
     */
    public static boolean carries(ItemStack item) {
        return carries(item, 0, i -> tagged(i, true), TokenPrizes::contents);
    }

    /**
     * The walk {@link #carries(ItemStack)} makes, over any kind of item (a test walks its own stand-ins): the item
     * is a prize, or what it holds can't be read ({@code contents} says empty), or something inside carries one.
     */
    static <T> boolean carries(T item, int depth, Predicate<T> prize,
                               BiFunction<T, Integer, Optional<List<T>>> contents) {
        if (item == null) {
            return false;
        }
        if (prize.test(item)) {
            return true;
        }
        Optional<List<T>> inside = contents.apply(item, depth);
        if (inside.isEmpty()) {
            return true; // unreadable, or past the depth limit with something in it
        }
        for (T in : inside.get()) {
            if (carries(in, depth + 1, prize, contents)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a container item holds, read once for everyone who needs it: a shulker box (any block-state item with an
     * inventory) and a bundle. An empty list for an item that holds nothing or isn't a container;
     * {@code Optional.empty()} when its contents can't be read, or when it holds something at a {@code depth} past
     * {@link #MAX_DEPTH} (nothing that deep is opened, so it counts as unread). Air and empty slots are left out.
     */
    public static Optional<List<ItemStack>> contents(ItemStack item, int depth) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.of(List.of());
        }
        List<ItemStack> out = new ArrayList<>();
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof org.bukkit.inventory.InventoryHolder holder) {
                for (ItemStack inside : holder.getInventory().getContents()) {
                    if (inside != null && !inside.getType().isAir()) {
                        out.add(inside);
                    }
                }
            }
            if (meta instanceof org.bukkit.inventory.meta.BundleMeta bundle) {
                for (ItemStack inside : bundle.getItems()) {
                    if (inside != null && !inside.getType().isAir()) {
                        out.add(inside);
                    }
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return limited(out, depth);
    }

    /** The depth rule of {@link #contents}: what was read, or empty (unread) when it is too deep to open. */
    static <T> Optional<List<T>> limited(List<T> read, int depth) {
        return depth > MAX_DEPTH && !read.isEmpty() ? Optional.empty() : Optional.of(read);
    }

    /** Tag a prize item's meta with the prize it came from (and so as unsellable). */
    public static void tag(ItemMeta meta, String prizeId) {
        meta.getPersistentDataContainer().set(Keys.TOKEN_PRIZE, PersistentDataType.STRING, prizeId);
    }

    /** The prize id an item was bought as, or null. */
    public static String prizeId(ItemStack item) {
        if (!is(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.TOKEN_PRIZE, PersistentDataType.STRING);
    }
}
