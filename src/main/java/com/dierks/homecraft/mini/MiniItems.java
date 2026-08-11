package com.dierks.homecraft.mini;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
 * Builds Mini items: textured heads styled by rarity (name colour, glint), with
 * a full provenance tooltip. A minted Mini carries a unique per-copy id in its
 * PDC (the anti-dupe tag) plus its type id and mint number; a preview icon is a
 * cosmetic-only version for the Museum GUI.
 */
public final class MiniItems {

    /** A real, minted Mini item given to a player (uniquely tagged). */
    public ItemStack minted(MiniDef def, RarityStyle style, long mintNumber, UUID uid) {
        ItemStack item = baseItem(def, style);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.lore(lore(def, style, mintNumber));
            var pdc = meta.getPersistentDataContainer();
            pdc.set(Keys.MINI_ID, PersistentDataType.STRING, def.id());
            pdc.set(Keys.MINI_UID, PersistentDataType.STRING, uid.toString());
            pdc.set(Keys.MINI_MINT, PersistentDataType.LONG, mintNumber);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * A cosmetic-only display icon for the Museum (no unique id — not a real Mini).
     * {@code priceText} is the pre-formatted price (via the economy) so buyers see
     * exactly what a mint costs before they ever click.
     */
    public ItemStack preview(MiniDef def, RarityStyle style, long minted, long circulation, String priceText) {
        ItemStack item = baseItem(def, style);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean free = def.price() <= 0;
            List<Component> lore = new ArrayList<>();
            lore.add(line("Type: ", def.category(), NamedTextColor.GRAY));
            lore.add(line("Series: ", def.series(), NamedTextColor.GRAY));
            lore.add(line("Rarity: ", def.rarity().name(), style.nameColor()));
            lore.add(line("Minted: ", minted + (def.uncapped() ? " (uncapped)" : " / " + def.cap()), NamedTextColor.GRAY));
            lore.add(line("In circulation: ", Long.toString(circulation), NamedTextColor.GRAY));
            lore.add(Component.empty());
            // Price is shown prominently in its own coloured line so it never reads as free.
            lore.add(Component.text("Price: ", NamedTextColor.GRAY)
                    .append(Component.text(free ? "Free" : priceText, NamedTextColor.GOLD))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            boolean soldOut = !def.uncapped() && minted >= def.cap();
            Component action;
            if (soldOut) {
                action = Component.text("Minted out — trade only", NamedTextColor.RED);
            } else if (free) {
                action = Component.text("Click to mint one (free)", NamedTextColor.YELLOW);
            } else {
                action = Component.text("Click to buy for " + priceText, NamedTextColor.GREEN);
            }
            lore.add(action.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** @return the Mini catalog id an item represents, or null if it isn't a Mini. */
    public String miniIdOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null
                : meta.getPersistentDataContainer().get(Keys.MINI_ID, PersistentDataType.STRING);
    }

    private ItemStack baseItem(MiniDef def, RarityStyle style) {
        Material material = def.type() == MiniType.ARMOR_STAND ? Material.ARMOR_STAND : Material.PLAYER_HEAD;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text(def.name(), style.nameColor()).decoration(TextDecoration.ITALIC, false));
        if (style.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (material == Material.PLAYER_HEAD && meta instanceof SkullMeta skull
                && def.texture() != null && !def.texture().isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", def.texture()));
                skull.setPlayerProfile(profile);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[HomeCraft] Failed to apply Mini texture for " + def.id() + ": " + t.getMessage());
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lore(MiniDef def, RarityStyle style, long mintNumber) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("Type: ", def.category(), NamedTextColor.GRAY));
        lore.add(line("Series: ", def.series(), NamedTextColor.GRAY));
        lore.add(line("Rarity: ", def.rarity().name(), style.nameColor()));
        lore.add(line("Mint #", mintNumber + (def.uncapped() ? "" : " of " + def.cap()), NamedTextColor.GRAY));
        return lore;
    }

    private Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text(label, NamedTextColor.DARK_GRAY)
                .append(Component.text(value, valueColor))
                .decoration(TextDecoration.ITALIC, false);
    }
}
