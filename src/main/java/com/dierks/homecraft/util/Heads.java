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

/** Build a {@code PLAYER_HEAD} from a Base64 texture value (minecraft-heads "Value"). */
public final class Heads {

    private Heads() {
    }

    /** A player head textured from {@code value} (blank = plain head), with name + lore. */
    public static ItemStack textured(String value, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
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
        if (value != null && !value.isBlank() && meta instanceof SkullMeta skull) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", value));
                skull.setPlayerProfile(profile);
            } catch (Throwable ignored) {
                // Malformed texture — fall back to a plain head rather than crash.
            }
        }
        item.setItemMeta(meta);
        return item;
    }
}
