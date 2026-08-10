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

    private Keys() {
    }

    public static void init(Plugin plugin) {
        CUSTOM_BLOCK_TYPE = new NamespacedKey(plugin, "custom_block_type");
        WORKBENCH_RECIPE = new NamespacedKey(plugin, "workbench");
    }
}
