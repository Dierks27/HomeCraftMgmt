package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.CapLook;
import com.dierks.homecraft.config.PluginConfig.KeeperLook;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemArmorTrim;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Builds the physical things the Prize Counter hands over: boosts, the Mini Radar and Lure, the
 * Firework Shows (a Mega Firework Show glints: 0.38.1), hats and caps, and the trophies (the Arcade Trophy, the ladder,
 * the Trophy of the Month).
 *
 * <p>Every one carries {@code hcm:token_prize} (so no money surface will take it) and
 * {@code prize_kind} (so right-clicking it does the right thing). They are built from materials
 * a player cannot eat, throw, drink or place by accident — a boost that is a golden carrot gets
 * eaten, and a firework rocket flies off — except the heads, which are MEANT to be placed and are
 * tracked when they are.
 *
 * <p>A collector cap is not a head: a dyed (and trimmed) leather helmet built from data components only,
 * so it looks the same on a Bedrock screen ({@link #collectorCap}). From 0.38 a hat row can name its
 * {@code material} too: the game's own Creeper or Dragon head, or a dyed leather cap ({@link #hatLook}).
 *
 * <p>A rented hat or cap (0.38) is a key bound to its owner, never placed ({@link #lookKey}): the right to wear it is
 * in the database, and the key only opens it. A worn Keeper's key is one too ({@link #keeperItem}), with no end: a
 * trimmed helmet built from data components, the Legend Crown with the golden helmet's look for Java on top.
 */
public final class PrizeItems {

    /** Trim keys already warned about, so a menu redrawn every second warns once. */
    private static final Set<String> WARNED_TRIMS = new HashSet<>();
    /**
     * Whether the Legend Crown carries its two Java-only parts (the golden helmet's equipment asset and item model).
     * The Keeper self-test switches them off when either didn't take on this server, so the Crown is the plain
     * gold-dyed look on both editions rather than a half-built one ({@link KeeperSelfTest}).
     */
    private static volatile boolean crownJavaParts = true;
    /** "November 2026": the Trophy of the Month's name, the local month. */
    private static final java.time.format.DateTimeFormatter MONTH =
            java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

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
            case FIREWORK -> MegaShow.isMega(p) ? glinting(tagged(new ItemStack(Material.FIREWORK_STAR), p,
                    "&7Use it near your Firework Launchers!", null))
                    : tagged(new ItemStack(Material.FIREWORK_STAR), p, "&7Right-click for a show!", null);
            case HAT -> p.collector() != null && p.collector().look() != null ? collectorCap(plugin, p, null)
                    : tagged(Heads.base(p.texture()), p, "&7Wear it! Right-click to put it on.", null);
            default -> null;
        };
    }

    /**
     * A hat row's look with no tag, for tiles (a menu never shows a tagged item): the Creeper or Dragon head, or
     * the dyed cap, named and with lore (legacy {@code &} codes; either may be null). Null for a row with no
     * {@code material} (a textured head: the caller draws that one).
     */
    public static ItemStack hatLook(Prize p, String name, List<String> lore) {
        Material m = p == null ? null : p.extra().hatMaterial();
        if (m == null) {
            return null;
        }
        if (m == Material.LEATHER_HELMET) {
            return capLook(capOf(p), name, lore);
        }
        ItemStack head = new ItemStack(m);
        ItemMeta meta = head.getItemMeta();
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
            head.setItemMeta(meta);
        }
        return head;
    }

    /** A cap row's look: its {@code color}'s leather dye (white when it names none), no trim. */
    static CapLook capOf(Prize p) {
        return new CapLook(capRgb(p.color()), null, null);
    }

    /** The leather dye of {@code color} as RGB ({@code PINK} is 0xF38BAA); white for none. */
    static int capRgb(org.bukkit.DyeColor color) {
        return color == null ? 0xFFFFFF : color.getColor().asRGB();
    }

    // ---- rented looks (SHOP-V2-SPEC §3.6) --------------------------------------------------------

    /**
     * The key to a rented hat or cap: the look, named with its last day ("Creeper Hat - until 18 Jan"), and bound to
     * its owner. The right is in the database ({@code cosmetics_owned}); this only opens it, so a lost or copied key
     * is harmless and My Looks makes it again free. It carries {@code hcm:token_prize} (every money surface refuses
     * it), {@code prize_kind} HAT, {@code hcm:bound_to} (the owner's UUID) and {@code look_end} (the end its name was
     * written for: the sweep rewrites the name in place when a renewal moves it). The base is the row's
     * {@code material} (the game's own Creeper or Dragon head, or the dyed cap), else its textured head. Null when it
     * can't be made.
     */
    public static ItemStack lookKey(HomeCraftManagement plugin, Prize p, UUID owner, LocalDate lastDay, long expiresAt) {
        if (p == null || owner == null || lastDay == null) {
            return null;
        }
        Material m = p.extra().hatMaterial();
        ItemStack key = m != null ? new ItemStack(m) : Heads.base(p.texture());
        return stampKey(key, p, owner, lastDay, expiresAt) ? key : null;
    }

    /**
     * Write a key's name, lore and data for the run until {@code lastDay}, in place: how a key is made, and how the
     * sweep refreshes one after a renewal (its binding untouched). A cap's look goes on again after the meta, as a
     * collector cap's does, so the meta's round trip never brings its armour back. False when the item has no meta.
     */
    public static boolean stampKey(ItemStack key, Prize p, UUID owner, LocalDate lastDay, long expiresAt) {
        if (!stampMeta(key, p, owner, lastDay, expiresAt)) {
            return false;
        }
        if (p.extra().hatMaterial() == Material.LEATHER_HELMET && key.getType() == Material.LEATHER_HELMET) {
            applyLook(key, capOf(p), null);
        }
        // One key a slot: two heads of one look would stack into a single slot the sweep counts as one key.
        key.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        return true;
    }

    private static boolean stampMeta(ItemStack key, Prize p, UUID owner, LocalDate lastDay, long expiresAt) {
        ItemMeta meta = key == null ? null : key.getItemMeta();
        if (meta == null) {
            return false;
        }
        String until = com.dierks.homecraft.arcade.looks.LookRules.until(lastDay);
        meta.displayName(Text.of(lookKeyName(p, until)));
        List<Component> lore = new ArrayList<>();
        for (String line : lookKeyLore(p, until)) {
            lore.add(Text.of(line));
        }
        meta.lore(lore);
        TokenPrizes.tag(meta, p.id());
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.PRIZE_KIND, PersistentDataType.STRING, PrizeType.HAT.name());
        pdc.set(Keys.BOUND_TO, PersistentDataType.STRING, owner.toString());
        pdc.set(Keys.LOOK_END, PersistentDataType.LONG, expiresAt);
        key.setItemMeta(meta);
        return true;
    }

    /** A key's name: the facts in the NAME, because Bedrock shows lore only on a long press (D32). */
    public static String lookKeyName(Prize p, String until) {
        return p.display() + " &7- until " + until;
    }

    /** A key's lore: the row's lines, its last day, that it is only its owner's, and how to wear it. */
    public static List<String> lookKeyLore(Prize p, String until) {
        List<String> out = new ArrayList<>();
        for (String line : p.description()) {
            out.add("&7" + line);
        }
        out.add("&7Yours until " + until);
        out.add("&7Only you can wear it.");
        out.add("&8Can't be dropped, traded or put away.");
        out.add("&7Wear it! Right-click to put it on.");
        out.add("&8Arcade prize");
        return out;
    }

    // ---- Keepers (SHOP-V2-SPEC §5.2, §5.4) ------------------------------------------------------------

    /**
     * A worn Keeper's key, in the form the config picks ({@code arcade.keepers.looks}): its look (§5.2), its name, its
     * lore (the Keeper lines, and for the Crown its "No. N" when {@code number} is above 0), {@code hcm:token_prize},
     * {@code prize_kind} HAT, {@code hcm:bound_to} = the owner and {@code look_variant}. No end: a Keeper is kept for
     * good. Null for a row that isn't a worn Keeper, or an item that can't be made.
     */
    public static ItemStack keeperItem(HomeCraftManagement plugin, Prize p, UUID owner, long number) {
        if (p == null) {
            return null;
        }
        return keeperItem(p, owner, number, Keepers.variant(plugin.config().arcade().keepers(), p.id()),
                plugin.getLogger());
    }

    /** {@link #keeperItem(HomeCraftManagement, Prize, UUID, long)} in one form. */
    static ItemStack keeperItem(Prize p, UUID owner, long number, Keepers.Variant v, Logger log) {
        KeeperLook look = p == null || !p.keeper() ? null : Keepers.look(p.id(), v);
        if (look == null || owner == null) {
            return null;
        }
        ItemStack item = new ItemStack(look.base());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.displayName(Text.of(p.display()));
        List<Component> lore = new ArrayList<>();
        for (String line : keeperLore(p, number, true)) {
            lore.add(Text.of(line));
        }
        meta.lore(lore);
        TokenPrizes.tag(meta, p.id());
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.PRIZE_KIND, PersistentDataType.STRING, PrizeType.HAT.name());
        pdc.set(Keys.BOUND_TO, PersistentDataType.STRING, owner.toString());
        pdc.set(Keys.LOOK_VARIANT, PersistentDataType.STRING, v.key());
        item.setItemMeta(meta);
        // The components go on last, after the meta, as a collector cap's do, so nothing round-trips through ItemMeta.
        applyKeeperLook(item, look, crownJavaParts, log);
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        return item;
    }

    /**
     * A worn Keeper's look with no tag, for menus and the Keepers shelf (a menu never shows a tagged item), in one form;
     * {@code name} and {@code lore} are legacy {@code &} codes (either may be null). Null for a trail.
     */
    public static ItemStack keeperLook(Prize p, Keepers.Variant v, String name, List<String> lore) {
        KeeperLook look = p == null || !p.keeper() ? null : Keepers.look(p.id(), v);
        if (look == null) {
            return null;
        }
        ItemStack item = new ItemStack(look.base());
        if (name != null || lore != null) {
            ItemMeta meta = item.getItemMeta();
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
                item.setItemMeta(meta);
            }
        }
        applyKeeperLook(item, look, crownJavaParts, null);
        return item;
    }

    /**
     * Rebuild a Keeper key's look in place in another form (§5.2's look switch, a sweep's REFRESH): the components and
     * {@code look_variant} change; its name, lore, number and binding are kept. False when the item can't be changed.
     */
    public static boolean restyleKeeper(ItemStack key, Prize p, Keepers.Variant v, Logger log) {
        KeeperLook look = p == null || !p.keeper() ? null : Keepers.look(p.id(), v);
        ItemMeta meta = key == null || look == null || key.getType() != look.base() ? null : key.getItemMeta();
        if (meta == null) {
            return false;
        }
        meta.getPersistentDataContainer().set(Keys.LOOK_VARIANT, PersistentDataType.STRING, v.key());
        key.setItemMeta(meta);
        applyKeeperLook(key, look, crownJavaParts, log);
        return true;
    }

    /**
     * A Keeper's lore, in order: the row's lines, the three Keeper lines (§5.1), the Crown's "No. N" ({@code number}
     * above 0), and on the item itself how to wear it.
     */
    public static List<String> keeperLore(Prize p, long number, boolean item) {
        List<String> out = new ArrayList<>();
        for (String line : p.description()) {
            out.add("&7" + line);
        }
        out.addAll(keeperLines());
        if (number > 0) {
            out.add("&7No. " + number);
        }
        if (item) {
            out.add("&7Wear it! Right-click to put it on.");
            out.add("&8Arcade prize");
        }
        return out;
    }

    /** The three lines every Keeper's tile and item add (§5.1). */
    public static List<String> keeperLines() {
        return List.of("&6Keeper &7- yours for good", "&7Only one each. Only you can wear it.",
                "&8Can't be given, traded or dropped.");
    }

    /** Switch the Legend Crown's Java-only parts on or off (the Keeper self-test). */
    static void crownJavaParts(boolean on) {
        crownJavaParts = on;
    }

    static boolean crownJavaParts() {
        return crownJavaParts;
    }

    /**
     * A Keeper's data components (§5.2): the dye on leather (none on iron), the trim when it resolves, unbreakable, an
     * explicit EMPTY attribute list (no armour, the Knight's Helm included), the glint as the look says, the Crown's
     * golden helmet asset and model for Java when {@code javaParts} (else the item's own, reset), and a tooltip that
     * hides all of it. Set whole every time, so a rebuild in the other form leaves nothing of the first behind.
     */
    static void applyKeeperLook(ItemStack item, KeeperLook look, boolean javaParts, Logger log) {
        if (look.rgb() >= 0 && item.getType() == Material.LEATHER_HELMET) {
            item.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(look.rgb() & 0xFFFFFF)));
        }
        ArmorTrim trim = trim(look.trimPattern(), look.trimMaterial(), "Keeper", log);
        if (trim != null) {
            item.setData(DataComponentTypes.TRIM, ItemArmorTrim.itemArmorTrim(trim));
        } else {
            item.unsetData(DataComponentTypes.TRIM);
        }
        item.setData(DataComponentTypes.UNBREAKABLE);
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, look.glint());
        if (javaParts && look.javaAsset() != null) {
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(org.bukkit.inventory.EquipmentSlot.HEAD)
                    .assetId(Key.key(look.javaAsset())).damageOnHurt(false).dispensable(false).swappable(true).build());
        } else {
            item.resetData(DataComponentTypes.EQUIPPABLE);
        }
        if (javaParts && look.javaModel() != null) {
            item.setData(DataComponentTypes.ITEM_MODEL, Key.key(look.javaModel()));
        } else {
            item.resetData(DataComponentTypes.ITEM_MODEL);
        }
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.DYED_COLOR, DataComponentTypes.TRIM,
                        DataComponentTypes.UNBREAKABLE, DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
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
        out.add(Collectors.collectorLine(p.collector()));
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
        return trim(look.trimPattern(), look.trimMaterial(), "Collector cap", log);
    }

    /** {@link #trim(CapLook, Logger)} on the two keys (a Keeper's look has its own); {@code what} names it in the WARN. */
    private static ArmorTrim trim(String trimPattern, String trimMaterial, String what, Logger log) {
        if (trimPattern == null || trimMaterial == null) {
            return null;
        }
        NamespacedKey patternKey = trimKey(trimPattern);
        NamespacedKey materialKey = trimKey(trimMaterial);
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
        String key = trimPattern + "/" + trimMaterial;
        if (WARNED_TRIMS.add(key)) {
            (log != null ? log : Logger.getLogger("HomeCraftManagement")).warning(what + " trim '" + key
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
     * A numbered trophy, named from its row ({@link #trophyName}). Its own texture when the row sets one, else
     * the Arcade machine's head — never a blank head, which renders as Steve.
     */
    public static ItemStack trophy(HomeCraftManagement plugin, Prize p, long number, String buyer) {
        String texture = p.texture() != null && !p.texture().isBlank() ? p.texture()
                : plugin.config().skin(com.dierks.homecraft.block.CustomBlockType.ARCADE);
        ItemStack item = Heads.base(texture);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(trophyName(p, number, plugin.clock().today())));
        List<Component> lore = new ArrayList<>();
        for (String line : trophyLore(p, buyer)) {
            lore.add(Text.of(line));
        }
        meta.lore(lore);
        TokenPrizes.tag(meta, p.id());
        meta.getPersistentDataContainer().set(Keys.PRIZE_KIND, PersistentDataType.STRING, PrizeType.TROPHY.name());
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * A trophy's name (P1): {@code &6&l<the row's plain name> #N} ("Bronze Trophy #3", "Arcade Trophy #12"), and
     * for a row limited to one a month (the Trophy of the Month) the local month's ("November 2026 Trophy #4").
     */
    static String trophyName(Prize p, long number, java.time.LocalDate today) {
        if (p.limit() != null && p.limit().per() == com.dierks.homecraft.config.PluginConfig.LimitPer.MONTH
                && today != null) {
            return "&6&l" + MONTH.format(today) + " Trophy #" + number;
        }
        return "&6&l" + Text.plain(p.display()) + " #" + number;
    }

    /**
     * A trophy's lore: who bought it (a buy, never "won"), the row's lines, and where it goes. The Arcade Trophy also
     * says it opens the Arcade (§5.8, {@link ArcadeTrophies}); the ladder's and the Monthly don't.
     */
    static List<String> trophyLore(Prize p, String buyer) {
        List<String> out = new ArrayList<>();
        out.add("&7Bought by &f" + buyer);
        for (String line : p.description()) {
            out.add("&7" + line);
        }
        out.add("&7Put it on a shelf!");
        if (ArcadeTrophies.ID.equalsIgnoreCase(p.id())) {
            out.add("&7Place it and tap it to open the Arcade!");
        }
        out.add("&8Arcade prize");
        return out;
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

    /** {@code item} with the enchanted glint (0.38.1: a Mega Firework Show reads as special in the bag). */
    private static ItemStack glinting(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
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
