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

    /** On a mob: spawned artificially (spawner/breeding/egg) — excluded from Wild Drops. */
    public static NamespacedKey MOB_ARTIFICIAL;

    /** On a placed armor-stand Mini entity: the owner UUID (String). */
    public static NamespacedKey MINI_OWNER;
    /** On a placed armor-stand Mini entity: the exact minted item to return on reclaim (Base64 String). */
    public static NamespacedKey MINI_ITEM;

    /** On a hologram (text-display) entity: the owning display's row id (Long). */
    public static NamespacedKey DISPLAY_ID;

    /** On a Mini Card item: the catalog id of the Mini it prints (String). */
    public static NamespacedKey CARD_ID;
    /** On a filament item: the colour it represents (a DyeColor name, String). */
    public static NamespacedKey FILAMENT_COLOR;
    /** On a printed Mini item: its grade (a Grade name, String). */
    public static NamespacedKey MINI_GRADE;
    /** On a printed Mini item: its finish (e.g. SHINY), or absent for none (String). */
    public static NamespacedKey MINI_FINISH;
    /** On a Museum showcase copy: marks it display-only — excluded from cap/circulation (Byte). */
    public static NamespacedKey MINI_DISPLAY_ONLY;

    /** On a Card Pack item: the pack id it opens (String). */
    public static NamespacedKey PACK_ID;
    /** On a Card Binder item: marks it as a binder (Byte). */
    public static NamespacedKey BINDER_ITEM;

    private Keys() {
    }

    public static void init(Plugin plugin) {
        CUSTOM_BLOCK_TYPE = new NamespacedKey(plugin, "custom_block_type");
        WORKBENCH_RECIPE = new NamespacedKey(plugin, "workbench");
        MINI_ID = new NamespacedKey(plugin, "mini_id");
        MINI_UID = new NamespacedKey(plugin, "mini_uid");
        MINI_MINT = new NamespacedKey(plugin, "mini_mint");
        MOB_ARTIFICIAL = new NamespacedKey(plugin, "mob_artificial");
        MINI_OWNER = new NamespacedKey(plugin, "mini_owner");
        MINI_ITEM = new NamespacedKey(plugin, "mini_item");
        DISPLAY_ID = new NamespacedKey(plugin, "display_id");
        CARD_ID = new NamespacedKey(plugin, "card_id");
        FILAMENT_COLOR = new NamespacedKey(plugin, "filament_color");
        MINI_GRADE = new NamespacedKey(plugin, "mini_grade");
        MINI_FINISH = new NamespacedKey(plugin, "mini_finish");
        MINI_DISPLAY_ONLY = new NamespacedKey(plugin, "mini_display_only");
        PACK_ID = new NamespacedKey(plugin, "pack_id");
        BINDER_ITEM = new NamespacedKey(plugin, "binder_item");
    }
}
