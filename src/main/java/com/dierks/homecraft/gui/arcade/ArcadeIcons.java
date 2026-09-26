package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The Arcade's button icons, from {@code arcade.icons.<key>}: a head texture where one is set,
 * otherwise a plain, colourful material.
 *
 * <p>Bedrock players always get the material. Geyser shows a custom-textured head in an
 * inventory as a plain head unless the proxy registers it, so a hub of textured heads would be a
 * hub of identical Steve-coloured blobs on a tablet. The fallbacks are chosen to be distinct from
 * one another so the hub reads with no textures at all.
 */
public final class ArcadeIcons {

    private ArcadeIcons() {
    }

    /** A button icon: {@code key}'s texture or material (else {@code fallback}), named and lored. */
    public static ItemStack of(HomeCraftManagement plugin, Player viewer, String key, Material fallback,
                               String name, String... lore) {
        PluginConfig.Icon ic = plugin.config().arcadeIcon(key);
        Material material = ic != null && ic.material() != null ? ic.material() : fallback;
        String texture = ic == null ? "" : ic.texture();
        ItemStack item = texture != null && !texture.isBlank() && !Bedrock.is(viewer)
                ? Heads.base(texture) : new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            if (lore.length > 0) {
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore.length);
                for (String line : lore) {
                    lines.add(Text.of(line));
                }
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** {@link #of} with the lore as a list. */
    public static ItemStack of(HomeCraftManagement plugin, Player viewer, String key, Material fallback,
                               String name, List<String> lore) {
        return of(plugin, viewer, key, fallback, name, lore.toArray(new String[0]));
    }
}
