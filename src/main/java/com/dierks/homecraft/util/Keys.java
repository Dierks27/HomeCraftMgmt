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

    /** On a Mailbox item / placed Mailbox tile: its colour variant (a MailboxVariant name, String). */
    public static NamespacedKey MAILBOX_VARIANT;
    /** On the auto-placed upper head of a two-tall Vending Machine: marks it a companion (Byte). */
    public static NamespacedKey VENDING_UPPER;

    /** On a minted Mini item: the render revision it was last drawn with (Integer). */
    public static NamespacedKey MINI_RENDER;
    /** On an effect entity (hologram / item display) we spawned: marks it ours + disposable (Byte). */
    public static NamespacedKey EFFECT_ENTITY;
    /** On a naturally spawned wild-Mini head block: marks it claimable (Byte). */
    public static NamespacedKey WILD_SPAWN;

    /** On a Display Case item / placed tile: its pedestal style (a DisplayCaseVariant name, String). */
    public static NamespacedKey DISPLAY_VARIANT;
    /** On a shop display entity (vending upper head, glow shell, hologram): the anchoring block "world:x:y:z" (String). */
    public static NamespacedKey SHOP_ANCHOR;
    /** On a per-player peek hologram entity: the viewer's UUID (String). */
    public static NamespacedKey PEEK_VIEWER;

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
        MAILBOX_VARIANT = new NamespacedKey(plugin, "mailbox_variant");
        VENDING_UPPER = new NamespacedKey(plugin, "vending_upper");
        MINI_RENDER = new NamespacedKey(plugin, "mini_render");
        EFFECT_ENTITY = new NamespacedKey(plugin, "effect_entity");
        WILD_SPAWN = new NamespacedKey(plugin, "wild_spawn");
        DISPLAY_VARIANT = new NamespacedKey(plugin, "display_variant");
        SHOP_ANCHOR = new NamespacedKey(plugin, "shop_anchor");
        PEEK_VIEWER = new NamespacedKey(plugin, "peek_viewer");
    }
}
