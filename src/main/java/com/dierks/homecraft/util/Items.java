package com.dierks.homecraft.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * Serialize an {@link ItemStack} to/from a Base64 string so a listed or auctioned
 * Mini can be held verbatim in the datastore (preserving its exact texture, name,
 * and anti-dupe PDC) and handed back byte-for-byte — no rebuild-from-catalog, so
 * edits or deletions of the Mini type never corrupt an in-flight trade.
 */
public final class Items {

    private Items() {
    }

    public static String toBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /** @return the item, or {@code null} if the string is blank/unreadable. */
    public static ItemStack fromBase64(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (Throwable t) {
            return null;
        }
    }
}
