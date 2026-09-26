package com.dierks.homecraft.mini;

import com.dierks.homecraft.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the physical <b>Card Pack</b> item: a sealed booster you buy in the pack shop, win from
 * a crate or get with {@code /hcm give pack}, then right-click to open. Distinct from a Card (a
 * Card is a player head; a Pack is sealed paper). The pack id lives in {@link Keys#PACK_ID}; what
 * it was bought with lives in {@link Keys#PACK_PAID}, so a pack that comes up short can hand back
 * its share in the same currency.
 */
public final class PackItems {

    /** What a sealed pack was bought with. */
    public enum Currency { MONEY, TOKENS }

    /** A purchase price carried on the item; {@code null} means it was given free. */
    public record Paid(Currency currency, double amount) {

        String encode() {
            return currency.name().toLowerCase(Locale.ROOT) + ":" + amount;
        }

        static Paid decode(String s) {
            if (s == null) {
                return null;
            }
            int colon = s.indexOf(':');
            if (colon < 0) {
                return null;
            }
            try {
                Currency c = Currency.valueOf(s.substring(0, colon).toUpperCase(Locale.ROOT));
                double amount = Double.parseDouble(s.substring(colon + 1));
                return amount > 0 ? new Paid(c, amount) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** A sealed pack that was given, not bought. */
    public ItemStack pack(Pack.PackDef def) {
        return pack(def, null);
    }

    /** A sealed Card Pack item for a pack type. */
    public ItemStack pack(Pack.PackDef def, Paid paid) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        int n = def.cardCount();
        meta.displayName(Component.text("❒ " + com.dierks.homecraft.util.Text.plain(def.displayName()),
                NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Card Pack", NamedTextColor.DARK_GRAY));
        lore.add(line(n == 1 ? "Open it to get 1 Card." : "Open it to get " + n + " Cards.", NamedTextColor.GRAY));
        lore.add(line((n == 1 ? "Take the Card" : "Take a Card") + " to a Printer to make a Mini.",
                NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("Right-click to open.", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.PACK_ID, PersistentDataType.STRING, def.id());
        if (paid != null && paid.amount() > 0) {
            pdc.set(Keys.PACK_PAID, PersistentDataType.STRING, paid.encode());
        }
        item.setItemMeta(meta);
        return item;
    }

    /** @return the pack id an item opens, or null if it isn't a Card Pack. */
    public String packIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.PACK_ID, PersistentDataType.STRING);
    }

    /** @return what the pack was bought with, or null if it was given free (or predates this). */
    public Paid paidOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return Paid.decode(item.getItemMeta().getPersistentDataContainer().get(Keys.PACK_PAID,
                PersistentDataType.STRING));
    }

    private static final java.util.regex.Pattern LEGACY_COUNT =
            java.util.regex.Pattern.compile("Contains (\\d+) random Card");

    /**
     * How many Cards a pack sealed before packs held one Card promised ("Contains 3 random
     * Cards."), or 0 for any other pack. Somebody paid for those three; they still get them.
     */
    public int legacyCardCount(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) {
            return 0;
        }
        for (Component c : item.getItemMeta().lore()) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(c);
            java.util.regex.Matcher m = LEGACY_COUNT.matcher(plain);
            if (m.find()) {
                try {
                    return Math.max(0, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
