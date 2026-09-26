package com.dierks.homecraft.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The {@code hcm:token_prize} tag, and the one sentence every money surface says about it.
 *
 * <p>Tokens buy fun; dollars buy things you keep. So nothing bought or won with tokens can be
 * turned into dollars: the house market, the Marketplace and its Pallets, Vending Machines, the
 * Auction House and Courier cargo all refuse a tagged item. Cards, packs, filament and HomeCraft
 * blocks are deliberately left untagged — they are Mini-making materials, not token prizes.
 */
public final class TokenPrizes {

    /** What a player is told when they try to sell one. */
    public static final String REFUSAL = "Arcade prizes can't be sold.";

    private TokenPrizes() {
    }

    /** True if the item was bought or won with tokens. */
    public static boolean is(ItemStack item) {
        if (item == null || !item.hasItemMeta() || Keys.TOKEN_PRIZE == null) {
            return false;
        }
        try {
            return item.getItemMeta().getPersistentDataContainer().has(Keys.TOKEN_PRIZE, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return false;
        }
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
