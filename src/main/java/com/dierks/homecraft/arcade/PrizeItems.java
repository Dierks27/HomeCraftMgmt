package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the physical things the Prize Counter hands over: boosts, the Mini Radar and Lure, the
 * Firework Show, hats and Arcade Trophies.
 *
 * <p>Every one carries {@code hcm:token_prize} (so no money surface will take it) and
 * {@code prize_kind} (so right-clicking it does the right thing). They are built from materials
 * a player cannot eat, throw, drink or place by accident — a boost that is a golden carrot gets
 * eaten, and a firework rocket flies off — except the heads, which are MEANT to be placed and are
 * tracked when they are.
 */
public final class PrizeItems {

    private PrizeItems() {
    }

    /** The item for a BOOST, RADAR, LURE, FIREWORK or HAT row; null for any other type. */
    public static ItemStack build(HomeCraftManagement plugin, Prize p) {
        return switch (p.type()) {
            case BOOST -> tagged(new ItemStack(safe(p.icon().material(), Material.SUGAR)), p,
                    "&7Right-click to use it.", pdc -> {
                        pdc.set(Keys.BOOST_EFFECT, PersistentDataType.STRING, p.effect());
                        pdc.set(Keys.BOOST_AMPLIFIER, PersistentDataType.INTEGER, p.amplifier());
                        pdc.set(Keys.BOOST_MINUTES, PersistentDataType.INTEGER, p.minutes());
                    });
            case RADAR -> tagged(new ItemStack(safe(p.icon().material(), Material.COMPASS)), p,
                    "&7Right-click while a wild Mini is out.", null);
            case LURE -> tagged(new ItemStack(safe(p.icon().material(), Material.HEART_OF_THE_SEA)), p,
                    "&7Right-click to set it.", null);
            case FIREWORK -> tagged(new ItemStack(Material.FIREWORK_STAR), p, "&7Right-click for a show!", null);
            case HAT -> tagged(Heads.base(p.texture()), p, "&7Wear it! Right-click to put it on.", null);
            default -> null;
        };
    }

    /**
     * A numbered Arcade Trophy. Its own texture when the row sets one, else the Arcade machine's
     * head — never a blank head, which renders as Steve.
     */
    public static ItemStack trophy(HomeCraftManagement plugin, Prize p, long number, String buyer) {
        String texture = p.texture() != null && !p.texture().isBlank() ? p.texture()
                : plugin.config().skin(com.dierks.homecraft.block.CustomBlockType.ARCADE);
        ItemStack item = Heads.base(texture);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of("&6&lArcade Trophy #" + number));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.of("&7Won by &f" + buyer));
        for (String line : p.description()) {
            lore.add(Text.of("&7" + line));
        }
        lore.add(Text.of("&7Put it on a shelf!"));
        lore.add(Text.of("&8Arcade prize"));
        meta.lore(lore);
        TokenPrizes.tag(meta, p.id());
        meta.getPersistentDataContainer().set(Keys.PRIZE_KIND, PersistentDataType.STRING, PrizeType.TROPHY.name());
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /** The prize kind an item was built as, or null. */
    public static PrizeType kindOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String k = item.getItemMeta().getPersistentDataContainer().get(Keys.PRIZE_KIND, PersistentDataType.STRING);
        if (k == null) {
            return null;
        }
        try {
            return PrizeType.valueOf(k);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ItemStack tagged(ItemStack item, Prize p, String use,
                                    java.util.function.Consumer<PersistentDataContainer> extra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(p.display()));
        List<Component> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add(Text.of("&7" + line));
        }
        lore.add(Text.of(use));
        lore.add(Text.of("&8Arcade prize"));
        meta.lore(lore);
        TokenPrizes.tag(meta, p.id());
        meta.getPersistentDataContainer().set(Keys.PRIZE_KIND, PersistentDataType.STRING, p.type().name());
        if (extra != null) {
            extra.accept(meta.getPersistentDataContainer());
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The row's material unless it is one a player would use by accident — then {@code fallback}.
     * Heads are allowed only for hats and trophies, which build their own.
     */
    private static Material safe(Material wanted, Material fallback) {
        if (wanted == null || !wanted.isItem() || wanted.isEdible() || wanted.isBlock()) {
            return fallback;
        }
        String n = wanted.name();
        if (n.endsWith("_BUCKET") || n.endsWith("POTION") || n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT")
                || n.endsWith("_MINECART") || n.contains("ARROW") || n.equals("ENDER_PEARL") || n.equals("ENDER_EYE")
                || n.equals("SNOWBALL") || n.equals("EGG") || n.equals("FIREWORK_ROCKET") || n.equals("BOW")
                || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("FISHING_ROD") || n.equals("EXPERIENCE_BOTTLE")) {
            return fallback;
        }
        return wanted;
    }
}
