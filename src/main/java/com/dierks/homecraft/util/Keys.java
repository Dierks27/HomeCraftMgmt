package com.dierks.homecraft.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Central registry of {@link NamespacedKey}s used for PersistentDataContainer
 * tagging (on items and on placed tile-entity blocks) and for recipe keys.
 * Initialized once from {@code onEnable}.
 */
public final class Keys {

    /** Marks an item/block as one of our custom blocks; value is a {@code CustomBlockType} name. */
    public static NamespacedKey CUSTOM_BLOCK_TYPE;

    /** NamespacedKey for the bootstrap (vanilla) Mini Workbench recipe. */
    public static NamespacedKey WORKBENCH_RECIPE;

    /** On a minted Mini item: its catalog id (String). */
    public static NamespacedKey MINI_ID;
    /** On a minted Mini item: its unique per-copy id (String UUID) — the anti-dupe tag. */
    public static NamespacedKey MINI_UID;
    /** On a minted Mini item: its mint number within the type (Long). */
    public static NamespacedKey MINI_MINT;

    private Keys() {
    }

    public static void init(Plugin plugin) {
        CUSTOM_BLOCK_TYPE = new NamespacedKey(plugin, "custom_block_type");
        WORKBENCH_RECIPE = new NamespacedKey(plugin, "workbench");
        MINI_ID = new NamespacedKey(plugin, "mini_id");
        MINI_UID = new NamespacedKey(plugin, "mini_uid");
        MINI_MINT = new NamespacedKey(plugin, "mini_mint");
    }
}
