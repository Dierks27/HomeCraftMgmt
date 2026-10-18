package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.CapLook;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemArmorTrim;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Builds the physical things the Prize Counter hands over: boosts, the Mini Radar and Lure, the
 * Firework Show, hats and Arcade Trophies.
 *
 * <p>Every one carries {@code hcm:token_prize} (so no money surface will take it) and
 * {@code prize_kind} (so right-clicking it does the right thing). They are built from materials
 * a player cannot eat, throw, drink or place by accident — a boost that is a golden carrot gets
 * eaten, and a firework rocket flies off — except the heads, which are MEANT to be placed and are
 * tracked when they are.
 *
 * <p>A collector cap is the one prize that is not a head: a dyed (and trimmed) leather helmet built
 * from data components only, so it looks the same on a Bedrock screen ({@link #collectorCap}).
 */
public final class PrizeItems {

    /** Trim keys already warned about, so a menu redrawn every second warns once. */
    private static final Set<String> WARNED_TRIMS = new HashSet<>();

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
            case HAT -> p.collector() != null && p.collector().look() != null ? collectorCap(plugin, p, null)
                    : tagged(Heads.base(p.texture()), p, "&7Wear it! Right-click to put it on.", null);
            default -> null;
        };
    }

    /** {@link #collectorCap(HomeCraftManagement, Prize, String, long)} with no serial number. */
    public static ItemStack collectorCap(HomeCraftManagement plugin, Prize p, String giver) {
        return collectorCap(plugin, p, giver, 0);
    }

    /**
     * A collector cap, tagged like every prize (unsellable everywhere; right-click to wear it). The
     * look is {@link #capLook}'s; {@code giver} adds "A gift from" to the lore, and is null for one
     * bought (or given back) for yourself; {@code serial} above 0 adds "No. n". Null when the row is
     * not a collector cap.
     */
    public static ItemStack collectorCap(HomeCraftManagement plugin, Prize p, String giver, long serial) {
        if (p == null || p.collector() == null || p.collector().look() == null) {
            return null;
        }
        ItemStack cap = new ItemStack(Material.LEATHER_HELMET);
        ItemMeta meta = cap.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.displayName(Text.of(p.display()));
        List<Component> lore = new ArrayList<>();
        for (String line : collectorLore(p, giver, serial)) {
            lore.add(Text.of(line));
        }
        meta.lore(lore);
        TokenPrizes.tag(meta, p.id());
        meta.getPersistentDataContainer().set(Keys.PRIZE_KIND, PersistentDataType.STRING, PrizeType.HAT.name());
        cap.setItemMeta(meta);
        // The components go on last, after the meta, so nothing of the look round-trips through ItemMeta
        // (an explicit EMPTY attribute list is what keeps the armour off).
        applyLook(cap, p.collector().look(), plugin == null ? null : plugin.getLogger());
        return cap;
    }

    /** {@link #collectorLore(Prize, String, long)} with no serial number. */
    static List<String> collectorLore(Prize p, String giver) {
        return collectorLore(p, giver, 0);
    }

    /**
     * A collector cap's lore, in order: the row's description, the year's collector line, "Yours for
     * good!", the giver for a present, its serial number (above 0), then how to wear it. The year is
     * in the name too, because a Bedrock player sees lore only on a long press.
     */
    static List<String> collectorLore(Prize p, String giver, long serial) {
        List<String> out = new ArrayList<>();
        for (String line : p.description()) {
            out.add("&7" + line);
        }
        // Every collector so far is Halloween's; a later season's collector names its own season here.
        out.add("&6Halloween " + p.collector().year() + " collector");
        out.add("&aYours for good!");
        if (giver != null && !giver.isBlank()) {
            out.add("&7A gift from &f" + giver);
        }
        if (serial > 0) {
            out.add("&7No. " + serial);
        }
        out.add("&7Wear it! Right-click to put it on.");
        out.add("&8Arcade prize");
        return out;
    }

    /**
     * A collector cap's look with no tag, for menus: a menu never shows a tagged item. {@code name}
     * and {@code lore} (legacy {@code &} codes; either may be null) are set before the look, for the
     * same reason as in {@link #collectorCap}.
     */
    public static ItemStack capLook(CapLook look, String name, List<String> lore) {
        ItemStack cap = new ItemStack(Material.LEATHER_HELMET);
        if (name != null || lore != null) {
            ItemMeta meta = cap.getItemMeta();
            if (meta != null) {
                if (name != null) {
                    meta.displayName(Text.of(name));
                }
                if (lore != null) {
                    List<Component> lines = new ArrayList<>();
                    for (String line : lore) {
                        lines.add(Text.of(line));
                    }
                    meta.lore(lines);
                }
                cap.setItemMeta(meta);
            }
        }
        if (look != null) {
            applyLook(cap, look, null);
        }
        return cap;
    }

    /** {@link #capLook(CapLook, String, List)} with no name or lore. */
    public static ItemStack capLook(CapLook look) {
        return capLook(look, null, null);
    }

    /**
     * The cap's data components (HALLOWEEN-SPEC D2): the dye; the trim, when it resolves; unbreakable
     * (a worn leather helmet would wear out); an explicit EMPTY attribute list, so no armour; and a
     * tooltip that hides all four. No glint: it would hide the dye and trim colours.
     */
    private static void applyLook(ItemStack cap, CapLook look, Logger log) {
        cap.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(look.rgb() & 0xFFFFFF)));
        ArmorTrim trim = trim(look, log);
        if (trim != null) {
            cap.setData(DataComponentTypes.TRIM, ItemArmorTrim.itemArmorTrim(trim));
        }
        cap.setData(DataComponentTypes.UNBREAKABLE);
        cap.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        cap.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.DYED_COLOR, DataComponentTypes.TRIM,
                        DataComponentTypes.UNBREAKABLE, DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
    }

    /**
     * The look's vanilla trim, looked up by key at runtime ("wild", "emerald"), or null for none. An
     * unknown key means no trim, with one warning: the dye alone is the whole look on Bedrock anyway.
     */
    private static ArmorTrim trim(CapLook look, Logger log) {
        if (look.trimPattern() == null || look.trimMaterial() == null) {
            return null;
        }
        NamespacedKey patternKey = trimKey(look.trimPattern());
        NamespacedKey materialKey = trimKey(look.trimMaterial());
        try {
            TrimPattern pattern = patternKey == null ? null
                    : RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(patternKey);
            TrimMaterial material = materialKey == null ? null
                    : RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).get(materialKey);
            if (pattern != null && material != null) {
                return new ArmorTrim(material, pattern);
            }
        } catch (RuntimeException e) {
            // fall through to the warning: no trim
        }
        String key = look.trimPattern() + "/" + look.trimMaterial();
        if (WARNED_TRIMS.add(key)) {
            (log != null ? log : Logger.getLogger("HomeCraftManagement")).warning("Collector cap trim '" + key
                    + "' is not a trim on this server — the cap is made without one (the dye is unchanged).");
        }
        return null;
    }

    /** A trim key as the registry names it: "wild" and "minecraft:wild" are the same; null if malformed. */
    static NamespacedKey trimKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT));
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
     * Heads are allowed only for hats and trophies, which build their own. A bundle is refused too: a
     * prize is kept on death, so a prize bundle would be a keep-inventory bag for anything.
     */
    static Material safe(Material wanted, Material fallback) {
        // The name rule first: it is plain Java, while isItem() and isEdible() need a server.
        if (wanted == null || refusedName(wanted.name())) {
            return fallback;
        }
        if (!wanted.isItem() || wanted.isEdible() || wanted.isBlock()) {
            return fallback;
        }
        return wanted;
    }

    /** The materials {@link #safe} refuses by name. */
    static boolean refusedName(String n) {
        return n.endsWith("_BUCKET") || n.endsWith("POTION") || n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT")
                || n.endsWith("_MINECART") || n.contains("ARROW") || n.equals("ENDER_PEARL") || n.equals("ENDER_EYE")
                || n.equals("SNOWBALL") || n.equals("EGG") || n.equals("FIREWORK_ROCKET") || n.equals("BOW")
                || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("FISHING_ROD") || n.equals("EXPERIENCE_BOTTLE")
                || n.endsWith("BUNDLE");
    }
}
