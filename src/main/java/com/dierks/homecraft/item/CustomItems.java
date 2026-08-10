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
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.MINI_WORKBENCH, null);
    }

    /** A PC item, ready to place. Applies the configured head texture when the base is a head. */
    public ItemStack pc() {
        PluginConfig.Pc def = config.pc();
        return tagged(def.baseBlock(), def.displayName(), def.lore(), CustomBlockType.PC, def.headTexture());
    }

    /** Build the item for a given type from current config (used e.g. when dropping on break). */
    public ItemStack of(CustomBlockType type) {
        return type == CustomBlockType.PC ? pc() : workbench();
    }

    private ItemStack tagged(Material material, String name, List<String> loreLines,
                             CustomBlockType type, String headTexture) {
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
