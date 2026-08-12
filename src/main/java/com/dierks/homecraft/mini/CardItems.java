package com.dierks.homecraft.mini;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds Mini <b>Cards</b> (Phase 9, §3.5): the collectible you find/buy and take
 * to a Printer to print a graded Mini. A card carries the Mini's head icon and a
 * tooltip showing its rarity, the printable grade odds, and the filament cost. The
 * card id lives in the item PDC ({@link Keys#CARD_ID}).
 */
public final class CardItems {

    /** A sealed Card for a Mini type. */
    public ItemStack card(MiniDef def, CardSpec spec, RarityStyle style) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text("❐ " + def.name() + " Card", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Mini: ", def.name() + " (" + def.rarity().name() + ")", style.nameColor()));
        lore.add(Component.text("Prints: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(gradeOdds(spec), NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Needs: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(filamentCost(spec), NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Sealed card — print it at a Printer.", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        if (meta instanceof SkullMeta skull && def.texture() != null && !def.texture().isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", def.texture()));
                skull.setPlayerProfile(profile);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[HomeCraft] Failed to apply card texture for " + def.id() + ": " + t.getMessage());
            }
        }
        meta.getPersistentDataContainer().set(Keys.CARD_ID, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    /** @return the Mini catalog id a card item prints, or null if it isn't a card. */
    public String cardIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.CARD_ID, PersistentDataType.STRING);
    }

    /** "Gray 60% / Green 25% / Blue 10% / Purple 4% / Gold 1%". */
    public String gradeOdds(CardSpec spec) {
        double total = 0;
        for (double w : spec.gradeWeights().values()) {
            total += w;
        }
        StringBuilder sb = new StringBuilder();
        for (Grade g : Grade.values()) {
            double w = spec.gradeWeights().getOrDefault(g, 0.0);
            if (w <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            double pct = total > 0 ? w / total * 100.0 : 0;
            sb.append(g.display()).append(' ').append(trim(pct)).append('%');
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    /** "5 Pink, 3 Purple, 1 Gold". */
    public String filamentCost(CardSpec spec) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<DyeColor, Integer> e : spec.filament().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append(' ').append(pretty(e.getKey().name()));
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private String pretty(String enumName) {
        String n = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private String trim(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    private Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text(label, NamedTextColor.DARK_GRAY)
                .append(Component.text(value, valueColor))
                .decoration(TextDecoration.ITALIC, false);
    }
}
