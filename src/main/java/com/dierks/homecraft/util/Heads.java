package com.dierks.homecraft.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Build a {@code PLAYER_HEAD} from a Base64 texture value (minecraft-heads "Value").
 *
 * <p><b>Two-pass rule.</b> On the live 26.2 API, setting a skull's player profile on
 * the same {@link ItemMeta} that already carries a display name, lore and PDC tags
 * can drop everything but the name once the meta is applied. Every head builder in
 * the plugin therefore goes through {@link #base}: the profile is applied and
 * committed with its own {@code setItemMeta} <em>first</em>, and name / lore / PDC
 * are written in a second pass on a freshly fetched meta. This is the ordering
 * {@code MiniItems} has always used and is the one that renders correctly in-game.
 */
public final class Heads {

    private Heads() {
    }

    /**
     * A bare player head with {@code value} applied and committed (pass one). Blank
     * value = a plain head. Decorate the result on a fresh {@code getItemMeta()}.
     */
    public static ItemStack base(String value) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (value == null || value.isBlank()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", value));
                skull.setPlayerProfile(profile);
                item.setItemMeta(skull);
            } catch (Throwable ignored) {
                // Malformed texture — fall back to a plain head rather than crash.
            }
        }
        return item;
    }

    /** A player head textured from {@code value} (blank = plain head), with name + lore. */
    public static ItemStack textured(String value, String name, List<String> lore) {
        ItemStack item = base(value);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (name != null) {
            meta.displayName(Text.of(name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(Text.of(line));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }
}
