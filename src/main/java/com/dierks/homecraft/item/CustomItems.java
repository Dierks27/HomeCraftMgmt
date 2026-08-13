package com.dierks.homecraft.item;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the tagged custom-block items (Mini Workbench, PC). Identity travels
 * on the item via a PDC marker so we can recognise it on placement; the visual
 * material, name, lore, and (for the PC) head texture come from config.
 */
public final class CustomItems {

    private final PluginConfig config;

    public CustomItems(PluginConfig config) {
        this.config = config;
    }

    /** A Mini Workbench item, ready to place. */
    public ItemStack workbench() {
        PluginConfig.Workbench def = config.workbench();
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.MINI_WORKBENCH,
                config.skin(CustomBlockType.MINI_WORKBENCH));
    }

    /** A Mini Printer item, ready to place (Phase 9). */
    public ItemStack printer() {
        PluginConfig.Printer def = config.printer();
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.PRINTER,
                config.skin(CustomBlockType.PRINTER));
    }

    /** A PC item, ready to place. Applies the configured head texture when the base is a head. */
    public ItemStack pc() {
        PluginConfig.Pc def = config.pc();
        // A skins.pc value wins; otherwise fall back to the legacy crafting.pc.head_texture.
        String skin = config.skin(CustomBlockType.PC);
        String texture = skin.isBlank() ? def.headTexture() : skin;
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.PC, texture);
    }

    /** A Mini Vending Machine item, ready to place. */
    public ItemStack vendingMachine() {
        PluginConfig.BlockDef def = config.miniBlocks().vending();
        return tagged(def.material(), def.name(), List.of("&7Right-click to sell/buy a Mini."),
                CustomBlockType.MINI_VENDING_MACHINE, config.skin(CustomBlockType.MINI_VENDING_MACHINE));
    }

    /** A Mini Display Case item, ready to place. */
    public ItemStack displayCase() {
        PluginConfig.BlockDef def = config.miniBlocks().display();
        return tagged(def.material(), def.name(), List.of("&7Place, then load a Mini to show it off."),
                CustomBlockType.DISPLAY_CASE, config.skin(CustomBlockType.DISPLAY_CASE));
    }

    /** A Mini Auction House item, ready to place. */
    public ItemStack auctionHouse() {
        PluginConfig.BlockDef def = config.miniBlocks().auction();
        return tagged(def.material(), def.name(), List.of("&7Right-click to browse Mini auctions."),
                CustomBlockType.AUCTION_HOUSE, config.skin(CustomBlockType.AUCTION_HOUSE));
    }

    /** A TV screen item, ready to place (Round 3a). */
    public ItemStack tv() {
        return tagged(Material.PLAYER_HEAD, "&bTV", List.of("&7Place it, then bind a stream:",
                "&7&o/hcm tv seturl <url>", "&7Right-click to open the stream in your browser."),
                CustomBlockType.TV, config.skin(CustomBlockType.TV));
    }

    /** A Mailbox item, ready to place. */
    public ItemStack mailbox() {
        PluginConfig.BlockDef def = config.marketplace().mailbox();
        return tagged(def.material(), def.name(), List.of("&7Right-click to collect deliveries."),
                CustomBlockType.MAILBOX, config.skin(CustomBlockType.MAILBOX));
    }

    /** A Pallet (sell box) item, ready to place. */
    public ItemStack pallet() {
        PluginConfig.BlockDef def = config.marketplace().pallet();
        return tagged(def.material(), def.name(), List.of("&7Place on your land, load an item,",
                "&7set a price to sell on the Marketplace."), CustomBlockType.PALLET,
                config.skin(CustomBlockType.PALLET));
    }

    /** An Arcade machine item, ready to place. */
    public ItemStack arcade() {
        PluginConfig.BlockDef def = config.arcade().block();
        return tagged(def.material(), def.name(), List.of("&7Right-click to open the Arcade.",
                "&7Spend tokens on crates, pity & scratch tickets."),
                CustomBlockType.ARCADE, config.skin(CustomBlockType.ARCADE));
    }

    /** An Arcade machine block (crate/scratch/pity/counter). */
    public ItemStack arcadeMachine(String key, CustomBlockType type, String lore) {
        PluginConfig.BlockDef def = config.arcade().machines().get(key);
        return tagged(def.material(), def.name(), List.of(lore), type, config.skin(type));
    }

    /** Build the item for a given type from current config (used e.g. when dropping on break). */
    public ItemStack of(CustomBlockType type) {
        return switch (type) {
            case PC -> pc();
            case MINI_WORKBENCH -> workbench();
            case PRINTER -> printer();
            case MINI_VENDING_MACHINE -> vendingMachine();
            case DISPLAY_CASE -> displayCase();
            case AUCTION_HOUSE -> auctionHouse();
            case MAILBOX -> mailbox();
            case PALLET -> pallet();
            case ARCADE -> arcade();
            case CRATE_MACHINE -> arcadeMachine("crate", type, "&7Right-click to open loot crates.");
            case SCRATCH_BOOTH -> arcadeMachine("scratch", type, "&7Right-click to buy a scratch ticket.");
            case PITY_KIOSK -> arcadeMachine("pity", type, "&7Right-click: tokens → a guaranteed Rare+ Mini.");
            case TOKEN_COUNTER -> arcadeMachine("counter", type, "&7Right-click to check your tokens.");
            case TV -> tv();
        };
    }

    private ItemStack tagged(Material material, String name, List<String> loreLines,
                             CustomBlockType type, String headTexture) {
        // A configured skin renders the block as a textured player head regardless of
        // the base material (you can't paint a head texture onto a barrel/chest). A
        // blank texture leaves the base material — and appearance — untouched.
        boolean skinned = headTexture != null && !headTexture.isBlank();
        if (skinned) {
            material = Material.PLAYER_HEAD;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item; // should not happen for these materials
        }

        meta.displayName(Text.of(name));

        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                lore.add(Text.of(line));
            }
            meta.lore(lore);
        }

        meta.getPersistentDataContainer().set(Keys.CUSTOM_BLOCK_TYPE, PersistentDataType.STRING, type.name());

        if (material == Material.PLAYER_HEAD && meta instanceof SkullMeta skull
                && headTexture != null && !headTexture.isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", headTexture));
                skull.setPlayerProfile(profile);
            } catch (Throwable t) {
                // Bad texture value shouldn't stop the item from existing.
                Bukkit.getLogger().warning("[HomeCraft] Failed to apply PC head texture: " + t.getMessage());
            }
        }

        item.setItemMeta(meta);
        return item;
    }
}
