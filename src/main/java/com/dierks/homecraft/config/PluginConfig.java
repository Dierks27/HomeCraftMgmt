package com.dierks.homecraft.config;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.mini.RarityStyle;
import com.dierks.homecraft.mini.StandData;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Typed view over {@code config.yml}. Phase 1 fully wires the {@code crafting}
 * section (Workbench + PC definitions and their reloadable, empty-by-default
 * recipes). The {@code market}/{@code shipping}/{@code minis} sections are left
 * as raw config for later phases.
 *
 * <p>{@link #load()} is safe to call repeatedly — it re-reads the current
 * {@link FileConfiguration}, so {@code /hcm reload} just re-invokes it.
 */
public final class PluginConfig {

    /** SHAPED = 3x3 grid via shape+symbols; SHAPELESS = unordered ingredient list. */
    public enum RecipeType {SHAPED, SHAPELESS}

    /** One shapeless ingredient: a material and how many are required. */
    public record Ingredient(Material material, int amount) {
    }

    /** A vanilla-style shaped recipe (used for the bootstrap Workbench recipe). */
    public record Shaped(List<String> shape, Map<Character, Material> ingredients) {
        public boolean isEmpty() {
            return shape == null || shape.isEmpty();
        }
    }

    /** The PC's Workbench recipe: shaped or shapeless, empty until an admin fills it. */
    public record PcRecipe(RecipeType type,
                           List<String> shape,
                           Map<Character, Material> ingredients,
                           List<Ingredient> shapeless) {
        public boolean isEmpty() {
            return type == RecipeType.SHAPED
                    ? (shape == null || shape.isEmpty())
                    : (shapeless == null || shapeless.isEmpty());
        }
    }

    public record Workbench(Material baseBlock, String displayName, List<String> lore, Shaped recipe) {
    }

    public record Pc(Material baseBlock, String displayName, List<String> lore, String headTexture, PcRecipe recipe) {
    }

    /** A daily sell allowance (0 = unlimited on that axis). */
    public record RankLimit(String permission, double maxMoneyPerDay, long maxUnitsPerDay) {
    }

    /** Anti-whale daily sell limits, with an optional per-permission override table. */
    public record SellLimits(boolean enabled, double maxMoneyPerDay, long maxUnitsPerDay,
                             String bypassPermission, List<RankLimit> ranks) {
    }

    /** The finite-stock market tuning + catalog (Phase 2.5). */
    public record Market(double elasticity, double inertia, double spread,
                         List<MarketItem> catalog, SellLimits sellLimits,
                         int priceHistoryIntervalMinutes) {
    }

    /** How shipping is priced. */
    public enum ShippingMode {PERCENTAGE, FLAT}

    /**
     * One shipping tier. The real-time delivery delay is {@code realHours} +
     * {@code realMinutes} combined (either may be zero — {@code real_minutes}
     * alone lets a tier deliver in under an hour). {@code primeFlat} forces a
     * flat fee regardless of order size (Prime-style).
     */
    public record ShippingTier(String id, String label, double realHours, double realMinutes,
                               double percent, double flat, boolean primeFlat) {

        /** Combined real-time delivery delay in milliseconds (hours + minutes). */
        public long deliveryMillis() {
            return (long) (realHours * 3_600_000L + realMinutes * 60_000L);
        }
    }

    /** Crate shipping config (Phase 3/6). Tiers ordered fastest → slowest. */
    public record Shipping(ShippingMode mode, List<ShippingTier> tiers) {
    }

    /** The Market Web Dashboard's embedded-server settings (Phase 6, §3.7). */
    public record WebDashboard(boolean enabled, String bind, int port, int refreshSeconds, String title) {
    }

    /** In-game economy displays refresh cadence (Phase 7, §3.8). */
    public record Displays(int refreshSeconds) {
    }

    /** Online store branding shown in-game (name + display URL). */
    public record Store(String name, String displayUrl) {
    }

    /**
     * Config-driven GUI titles so no section label is hard-coded (§2.2). The store
     * title supports {@code {store}} / {@code {url}} tokens, filled from
     * {@link Store}. Edit these live via the Admin Studio or {@code /hcm reload}.
     */
    public record MenuTitles(String admin, String museum, String market, String storeFormat) {
    }

    /** The Minis catalog + rarity styling (Phase 4). */
    public record Minis(String pricingMode, Map<Rarity, RarityStyle> rarityStyles,
                        List<String> categories, List<MiniDef> catalog) {
        public RarityStyle style(Rarity rarity) {
            return rarityStyles.getOrDefault(rarity, DEFAULT_RARITY_STYLES.get(rarity));
        }
    }

    /** Appearance (base material + display name) of a placeable custom block. */
    public record BlockDef(Material material, String name) {
    }

    /** The Mini trading blocks' appearance (Phase 4c). */
    public record MiniBlocks(BlockDef vending, BlockDef display, BlockDef auction) {
    }

    /** The Crate Marketplace config (Phase 5): blocks, fees, departments, ban list. */
    public record Marketplace(BlockDef mailbox, BlockDef pallet, double commissionPercent,
                              double dailyStorageFee, List<String> departments,
                              Map<String, String> categoryOverrides, Set<Material> banList,
                              boolean requireProtectedLand) {

        /** The department an admin override assigns to a material, or null. */
        public String override(Material material) {
            return categoryOverrides.get(material.name());
        }

        public boolean isBanned(Material material) {
            return banList.contains(material);
        }

        public String defaultDepartment() {
            return departments.isEmpty() ? "Misc" : departments.get(departments.size() - 1);
        }
    }

    /** Built-in rarity palette + smart defaults; overridable under {@code minis.rarity_styles}. */
    private static final Map<Rarity, RarityStyle> DEFAULT_RARITY_STYLES = new EnumMap<>(Rarity.class);

    static {
        DEFAULT_RARITY_STYLES.put(Rarity.LEGENDARY,
                new RarityStyle(Material.YELLOW_STAINED_GLASS_PANE, NamedTextColor.GOLD, true, 5, 50000));
        DEFAULT_RARITY_STYLES.put(Rarity.EPIC,
                new RarityStyle(Material.PURPLE_STAINED_GLASS_PANE, NamedTextColor.LIGHT_PURPLE, true, 20, 15000));
        DEFAULT_RARITY_STYLES.put(Rarity.RARE,
                new RarityStyle(Material.BLUE_STAINED_GLASS_PANE, NamedTextColor.AQUA, false, 100, 4000));
        DEFAULT_RARITY_STYLES.put(Rarity.UNCOMMON,
                new RarityStyle(Material.GREEN_STAINED_GLASS_PANE, NamedTextColor.GREEN, false, 500, 800));
        DEFAULT_RARITY_STYLES.put(Rarity.COMMON,
                new RarityStyle(Material.LIGHT_GRAY_STAINED_GLASS_PANE, NamedTextColor.GRAY, false, -1, 150));
    }

    private final HomeCraftManagement plugin;
    private final Logger log;

    private boolean respectTownPerms = true;
    private Workbench workbench;
    private Pc pc;
    private Market market;
    private Shipping shipping;
    private Store store;
    private MenuTitles menuTitles;
    private Minis minis;
    private MiniBlocks miniBlocks;
    private Loot.MiniLoot miniLoot;
    private Map<String, StandData> miniStands;
    private Marketplace marketplace;
    private Map<com.dierks.homecraft.block.CustomBlockType, String> skins;
    private WebDashboard webDashboard;
    private Displays displays;

    public PluginConfig(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public boolean respectTownPerms() {
        return respectTownPerms;
    }

    public Workbench workbench() {
        return workbench;
    }

    public Pc pc() {
        return pc;
    }

    public Shipping shipping() {
        return shipping;
    }

    public Store store() {
        return store;
    }

    public MenuTitles menuTitles() {
        return menuTitles;
    }

    public Minis minis() {
        return minis;
    }

    public MiniBlocks miniBlocks() {
        return miniBlocks;
    }

    public Marketplace marketplace() {
        return marketplace;
    }

    /** The Base64 head-texture value configured for a custom block, or "" if none. */
    public String skin(com.dierks.homecraft.block.CustomBlockType type) {
        return skins.getOrDefault(type, "");
    }

    public WebDashboard webDashboard() {
        return webDashboard;
    }

    public Displays displays() {
        return displays;
    }

    public Loot.MiniLoot miniLoot() {
        return miniLoot;
    }

    /** Posed armor-stand configurations keyed by Mini id (Phase 4d). */
    public Map<String, StandData> miniStands() {
        return miniStands;
    }

    public Market market() {
        return market;
    }

    /** (Re)parse config.yml into the typed views above. */
    public void load() {
        FileConfiguration c = plugin.getConfig();

        this.respectTownPerms = c.getBoolean("crafting.respect_town_perms", true);

        // ---- Workbench ----
        Material wbBase = material(c.getString("crafting.workbench.base_block"), Material.CRAFTER, "crafting.workbench.base_block");
        String wbName = c.getString("crafting.workbench.display_name", "&6Mini Workbench");
        List<String> wbLore = c.getStringList("crafting.workbench.lore");
        Shaped wbRecipe = readShaped(c, "crafting.workbench.recipe");
        this.workbench = new Workbench(wbBase, wbName, wbLore, wbRecipe);

        // ---- PC ----
        Material pcBase = material(c.getString("crafting.pc.base_block"), Material.PLAYER_HEAD, "crafting.pc.base_block");
        String pcName = c.getString("crafting.pc.display_name", "&bPersonal Computer");
        List<String> pcLore = c.getStringList("crafting.pc.lore");
        String texture = c.getString("crafting.pc.head_texture", "");
        PcRecipe pcRecipe = readPcRecipe(c, "crafting.pc.recipe");
        this.pc = new Pc(pcBase, pcName, pcLore, texture, pcRecipe);

        // ---- Market (Phase 2.5 — finite stock) ----
        this.market = readMarket(c);

        // ---- Shipping (Phase 3) ----
        this.shipping = readShipping(c);

        // ---- Store branding (Phase 3) ----
        this.store = new Store(
                c.getString("store.name", "Crate"),
                c.getString("store.display_url", "www.Crate.com"));

        // ---- Menu titles (Phase 4b — config-driven section labels, §2.2) ----
        this.menuTitles = new MenuTitles(
                c.getString("menus.admin_title", "&4Admin Studio"),
                c.getString("menus.museum_title", "&5Mini Museum &8&l·&r &7Collectibles"),
                c.getString("menus.market_title", "&1Market — instant buy/sell"),
                c.getString("menus.store_title", "&6Welcome to {store} &8· &7{url}"));

        // ---- Minis (Phase 4) ----
        this.minis = readMinis(c);

        // ---- Mini trading blocks (Phase 4c) ----
        this.miniBlocks = new MiniBlocks(
                blockDef(c, "minis.blocks.vending_machine", Material.BARREL, "&dMini Vending Machine"),
                blockDef(c, "minis.blocks.display_case", Material.PLAYER_HEAD, "&bMini Display Case"),
                blockDef(c, "minis.blocks.auction_house", Material.LECTERN, "&6Mini Auction House"));

        // ---- Wild Drops loot (Phase 4c Part C) ----
        this.miniLoot = readMiniLoot(c);

        // ---- Posed armor-stand Minis (Phase 4d) ----
        this.miniStands = new LinkedHashMap<>();
        for (Map<?, ?> row : c.getMapList("minis.stands")) {
            String id = str(row.get("id"), null);
            if (id != null && !id.isBlank()) {
                this.miniStands.put(id, StandData.fromConfig(row));
            }
        }

        // ---- Crate Marketplace (Phase 5) ----
        this.marketplace = readMarketplace(c);

        // ---- Block skins + Web Dashboard (Phase 6) ----
        this.skins = readSkins(c);
        this.webDashboard = readWebDashboard(c);

        // ---- In-game economy displays (Phase 7) ----
        this.displays = new Displays(Math.max(2, c.getInt("displays.refresh_seconds", 20)));
    }

    private Map<com.dierks.homecraft.block.CustomBlockType, String> readSkins(FileConfiguration c) {
        Map<com.dierks.homecraft.block.CustomBlockType, String> map =
                new EnumMap<>(com.dierks.homecraft.block.CustomBlockType.class);
        ConfigurationSection sec = c.getConfigurationSection("skins");
        if (sec == null) {
            return map;
        }
        putSkin(map, sec, "pc", com.dierks.homecraft.block.CustomBlockType.PC);
        putSkin(map, sec, "workbench", com.dierks.homecraft.block.CustomBlockType.MINI_WORKBENCH);
        putSkin(map, sec, "vending", com.dierks.homecraft.block.CustomBlockType.MINI_VENDING_MACHINE);
        putSkin(map, sec, "display_case", com.dierks.homecraft.block.CustomBlockType.DISPLAY_CASE);
        putSkin(map, sec, "auction", com.dierks.homecraft.block.CustomBlockType.AUCTION_HOUSE);
        putSkin(map, sec, "mailbox", com.dierks.homecraft.block.CustomBlockType.MAILBOX);
        putSkin(map, sec, "pallet", com.dierks.homecraft.block.CustomBlockType.PALLET);
        return map;
    }

    private void putSkin(Map<com.dierks.homecraft.block.CustomBlockType, String> map,
                         ConfigurationSection sec, String key,
                         com.dierks.homecraft.block.CustomBlockType type) {
        String v = sec.getString(key, "");
        if (v != null && !v.isBlank()) {
            map.put(type, v.trim());
        }
    }

    private WebDashboard readWebDashboard(FileConfiguration c) {
        return new WebDashboard(
                c.getBoolean("web.dashboard.enabled", true),
                c.getString("web.dashboard.bind", "0.0.0.0"),
                c.getInt("web.dashboard.port", 8080),
                Math.max(2, c.getInt("web.dashboard.refresh_seconds", 30)),
                c.getString("web.dashboard.title", "Crate Market"));
    }

    private Marketplace readMarketplace(FileConfiguration c) {
        BlockDef mailbox = blockDef(c, "marketplace.mailbox_block", Material.BARREL, "&eMailbox");
        BlockDef pallet = blockDef(c, "marketplace.pallet_block", Material.CHEST, "&6Pallet");
        double commission = c.getDouble("marketplace.fee.commission_percent", 5.0);
        double storageFee = c.getDouble("marketplace.fee.daily_storage_fee", 0.0);

        List<String> departments = c.getStringList("marketplace.departments");
        if (departments.isEmpty()) {
            departments = List.of("Blocks", "Food", "Tools", "Weapons", "Armor", "Redstone", "Collectibles", "Misc");
        }

        Map<String, String> overrides = new LinkedHashMap<>();
        ConfigurationSection ov = c.getConfigurationSection("marketplace.category_overrides");
        if (ov != null) {
            for (String key : ov.getKeys(false)) {
                overrides.put(key.trim().toUpperCase(), ov.getString(key));
            }
        }

        Set<Material> banned = new HashSet<>();
        List<String> banStrings = c.getStringList("marketplace.ban_list");
        if (banStrings.isEmpty()) {
            banStrings = List.of("BEDROCK", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
                    "COMMAND_BLOCK_MINECART", "BARRIER", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
                    "LIGHT", "DEBUG_STICK", "KNOWLEDGE_BOOK", "BEDROCK");
        }
        for (String s : banStrings) {
            Material m = Material.matchMaterial(s.trim().toUpperCase());
            if (m != null) {
                banned.add(m);
            }
        }
        // Spawn eggs are always banned (can't be listed).
        for (Material m : Material.values()) {
            if (m.name().endsWith("_SPAWN_EGG")) {
                banned.add(m);
            }
        }

        boolean requireLand = c.getBoolean("marketplace.require_protected_land", true);
        return new Marketplace(mailbox, pallet, Math.max(0, commission), Math.max(0, storageFee),
                departments, overrides, banned, requireLand);
    }

    private Loot.MiniLoot readMiniLoot(FileConfiguration c) {
        List<Loot.LootList> lists = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("minis.loot.lists")) {
            String id = str(row.get("id"), null);
            if (id == null || id.isBlank()) {
                continue;
            }
            List<Loot.LootEntry> entries = new ArrayList<>();
            if (row.get("entries") instanceof List<?> es) {
                for (Object o : es) {
                    if (o instanceof Map<?, ?> em) {
                        String mini = str(em.get("mini"), null);
                        if (mini == null || mini.isBlank()) {
                            continue;
                        }
                        entries.add(new Loot.LootEntry(mini, Math.max(0.0001, number(em.get("weight"), 1))));
                    }
                }
            }
            lists.add(new Loot.LootList(id, entries));
        }
        List<Loot.LootSource> sources = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("minis.loot.sources")) {
            String listId = str(row.get("list"), null);
            if (listId == null || listId.isBlank()) {
                continue;
            }
            Loot.Trigger t = Loot.Trigger.parse(str(row.get("trigger"), "BLOCK_BREAK"));
            String match = str(row.get("match"), "*");
            double chance = row.get("chance_percent") != null
                    ? number(row.get("chance_percent"), 0)
                    : number(row.get("chance"), 0);
            sources.add(new Loot.LootSource(t, match, listId, Math.max(0, chance)));
        }
        return new Loot.MiniLoot(lists, sources);
    }

    private BlockDef blockDef(FileConfiguration c, String path, Material fallback, String defaultName) {
        Material m = material(c.getString(path + ".material"), fallback, path + ".material");
        String name = c.getString(path + ".name", defaultName);
        return new BlockDef(m, name);
    }

    private Minis readMinis(FileConfiguration c) {
        String pricingMode = c.getString("minis.pricing_mode", "ESCALATING");

        Map<Rarity, RarityStyle> styles = new EnumMap<>(DEFAULT_RARITY_STYLES);
        ConfigurationSection stylesSec = c.getConfigurationSection("minis.rarity_styles");
        if (stylesSec != null) {
            for (String key : stylesSec.getKeys(false)) {
                Rarity rarity;
                try {
                    rarity = Rarity.valueOf(key.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warning("Unknown rarity '" + key + "' in minis.rarity_styles; skipping.");
                    continue;
                }
                ConfigurationSection s = stylesSec.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                RarityStyle d = styles.get(rarity);
                styles.put(rarity, new RarityStyle(
                        paneMaterial(s.getString("pane"), d.pane()),
                        colorOf(s.getString("name_color"), d.nameColor()),
                        s.getBoolean("glint", d.glint()),
                        s.getLong("default_cap", d.defaultCap()),
                        s.getDouble("default_price", d.defaultPrice())));
            }
        }

        List<String> categories = c.getStringList("minis.categories");
        if (categories.isEmpty()) {
            categories = List.of("ANIMAL", "FOOD", "LETTER", "SYMBOL", "CHARACTER", "VEHICLE", "HOLIDAY", "MISC");
        }

        List<MiniDef> catalog = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> seriesRow : c.getMapList("minis.series")) {
            String seriesName = str(seriesRow.get("name"), "Series");
            Rarity seriesRarity = parseRarity(seriesRow.get("rarity"), Rarity.COMMON);
            if (!(seriesRow.get("entries") instanceof List<?> entries)) {
                continue;
            }
            for (Object entryObj : entries) {
                if (!(entryObj instanceof Map<?, ?> e)) {
                    continue;
                }
                String name = str(e.get("name"), null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                Rarity rarity = parseRarity(e.get("rarity"), seriesRarity);
                RarityStyle style = styles.get(rarity);
                MiniType type = parseMiniType(e.get("type"));
                String category = str(e.get("category"), "MISC");
                String texture = str(e.get("texture"), "");
                long cap = e.get("cap") != null ? (long) number(e.get("cap"), style.defaultCap()) : style.defaultCap();
                double price = e.get("price") != null ? number(e.get("price"), style.defaultPrice()) : style.defaultPrice();
                boolean craftable = e.get("craftable") instanceof Boolean b
                        ? b : Boolean.parseBoolean(String.valueOf(e.get("craftable")));
                // Canonicalize the id one way (slug) whether it's provided or derived
                // from the name, so a messy config id like "1-up" becomes "1_up" and
                // matches the item PDC stamp + getHeldMini lookup everywhere.
                String id = slug(str(e.get("id"), name));
                if (id.isBlank() || !seen.add(id)) {
                    log.warning("Skipping Mini with empty/duplicate id '" + id + "'.");
                    continue;
                }
                catalog.add(new MiniDef(id, name, seriesName, category, rarity, type, texture, cap, price, craftable));
            }
        }
        return new Minis(pricingMode, styles, categories, catalog);
    }

    private Material paneMaterial(String color, Material fallback) {
        if (color == null || color.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(color.trim().toUpperCase() + "_STAINED_GLASS_PANE");
        return m != null ? m : fallback;
    }

    private NamedTextColor colorOf(String name, NamedTextColor fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        NamedTextColor c = NamedTextColor.NAMES.value(name.trim().toLowerCase());
        return c != null ? c : fallback;
    }

    private Rarity parseRarity(Object value, Rarity fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Rarity.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private MiniType parseMiniType(Object value) {
        if (value == null) {
            return MiniType.HEAD;
        }
        try {
            return MiniType.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MiniType.HEAD;
        }
    }

    private String str(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }

    private String slug(String name) {
        // One canonical slug across the whole plugin (see MiniIds.slug).
        return com.dierks.homecraft.mini.MiniIds.slug(name);
    }

    private Shipping readShipping(FileConfiguration c) {
        ShippingMode mode;
        try {
            mode = ShippingMode.valueOf(c.getString("shipping.mode", "PERCENTAGE").toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warning("Invalid shipping.mode; defaulting to PERCENTAGE.");
            mode = ShippingMode.PERCENTAGE;
        }
        // Fully config-driven: read every tier defined under shipping.tiers, in any
        // number, then order them fastest → slowest by their real delivery time.
        List<ShippingTier> tiers = new ArrayList<>();
        ConfigurationSection sec = c.getConfigurationSection("shipping.tiers");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                tiers.add(readTier(c, key));
            }
        }
        if (tiers.isEmpty()) {
            // Safety net if the whole tiers map is missing: the four-tier default scheme.
            tiers.add(new ShippingTier("express", "Express", 0, 5, 20, 0, false));
            tiers.add(new ShippingTier("one_day", "One Day", 1, 0, 10, 0, false));
            tiers.add(new ShippingTier("two_day", "Two Day", 3, 0, 5, 0, false));
            tiers.add(new ShippingTier("three_day", "Three Day", 8, 0, 0, 0, false));
        }
        tiers.sort(java.util.Comparator.comparingLong(ShippingTier::deliveryMillis));
        return new Shipping(mode, tiers);
    }

    private ShippingTier readTier(FileConfiguration c, String key) {
        String base = "shipping.tiers." + key;
        double hours = c.getDouble(base + ".real_hours", 0);
        double minutes = c.getDouble(base + ".real_minutes", 0);
        double percent = c.getDouble(base + ".percent", 0);
        double flat = c.getDouble(base + ".flat", 0);
        boolean prime = c.getBoolean(base + ".prime_flat", false);
        String label = c.getString(base + ".label", prettifyKey(key));
        return new ShippingTier(key, label, Math.max(0, hours), Math.max(0, minutes),
                Math.max(0, percent), Math.max(0, flat), prime);
    }

    /** "one_day" / "two-day" → "One Day"; used as a tier's display label when none is set. */
    private String prettifyKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.replace('-', '_').split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    private Market readMarket(FileConfiguration c) {
        double elasticity = c.getDouble("market.elasticity", 1.0);
        double inertia = c.getDouble("market.inertia", 0.2);
        double spread = c.getDouble("market.spread", 0.10);
        long defaultFullStock = c.getLong("market.default_full_stock", 1024);
        int historyMinutes = c.getInt("market.price_history.interval_minutes", 30);

        List<MarketItem> catalog = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> row : c.getMapList("market.catalog")) {
            Object idObj = row.get("id");
            Object matObj = row.get("material");
            if (idObj == null || matObj == null) {
                log.warning("Skipping market.catalog entry missing 'id' or 'material'.");
                continue;
            }
            String id = String.valueOf(idObj).trim().toLowerCase();
            if (id.isEmpty() || !seen.add(id)) {
                log.warning("Skipping market.catalog entry with empty/duplicate id '" + id + "'.");
                continue;
            }
            Material material = material(String.valueOf(matObj), null, "market.catalog[" + id + "].material");
            if (material == null) {
                continue;
            }
            String displayName = row.get("display_name") != null ? String.valueOf(row.get("display_name")) : null;

            double floor = number(row.get("floor"), 0.0);
            double ceiling = number(row.get("ceiling"), Math.max(floor, 1.0) * 100.0);
            if (floor < 0) {
                floor = 0;
            }
            if (ceiling < floor) {
                log.warning("market.catalog[" + id + "] ceiling < floor; raising ceiling to floor.");
                ceiling = floor;
            }
            long initialStock = Math.max(0L, (long) number(row.get("initial_stock"), 0));
            long fullStock = (long) number(row.get("full_stock"), defaultFullStock);
            if (fullStock < 1) {
                fullStock = 1; // avoid divide-by-zero in the pricing curve
            }

            catalog.add(new MarketItem(id, material, displayName, floor, ceiling, initialStock, fullStock));
        }
        return new Market(elasticity, inertia, spread, catalog, readSellLimits(c), Math.max(1, historyMinutes));
    }

    private SellLimits readSellLimits(FileConfiguration c) {
        boolean enabled = c.getBoolean("market.sell_limits.enabled", true);
        double maxMoney = c.getDouble("market.sell_limits.max_money_per_day", 0);
        long maxUnits = c.getLong("market.sell_limits.max_units_per_day", 0);
        String bypass = c.getString("market.sell_limits.bypass_permission", "hcm.market.limit.bypass");

        List<RankLimit> ranks = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("market.sell_limits.ranks")) {
            Object perm = row.get("permission");
            if (perm == null || String.valueOf(perm).isBlank()) {
                continue;
            }
            ranks.add(new RankLimit(
                    String.valueOf(perm),
                    number(row.get("max_money_per_day"), 0),
                    (long) number(row.get("max_units_per_day"), 0)));
        }
        return new SellLimits(enabled, maxMoney, maxUnits, bypass, ranks);
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private Shaped readShaped(FileConfiguration c, String path) {
        List<String> shape = c.getStringList(path + ".shape");
        Map<Character, Material> ing = readSymbolMap(c, path + ".ingredients");
        return new Shaped(shape, ing);
    }

    private PcRecipe readPcRecipe(FileConfiguration c, String path) {
        RecipeType type;
        try {
            type = RecipeType.valueOf(c.getString(path + ".type", "SHAPED").toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warning("Invalid recipe type at " + path + ".type; defaulting to SHAPED.");
            type = RecipeType.SHAPED;
        }
        List<String> shape = c.getStringList(path + ".shape");
        Map<Character, Material> ing = readSymbolMap(c, path + ".ingredients");

        List<Ingredient> shapeless = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList(path + ".ingredients")) {
            // Only relevant when ingredients is a LIST (shapeless); symbol maps are handled above.
            Object mat = row.get("material");
            if (mat == null) {
                continue;
            }
            Material m = material(String.valueOf(mat), null, path + ".ingredients");
            if (m == null) {
                continue;
            }
            int amount = row.get("amount") instanceof Number n ? n.intValue() : 1;
            shapeless.add(new Ingredient(m, Math.max(1, amount)));
        }
        return new PcRecipe(type, shape, ing, shapeless);
    }

    /** Reads an {@code ingredients} section shaped like {@code A: IRON_INGOT} into a char->Material map. */
    private Map<Character, Material> readSymbolMap(FileConfiguration c, String path) {
        Map<Character, Material> out = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection(path);
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            if (key.isEmpty()) {
                continue;
            }
            Material m = material(sec.getString(key), null, path + "." + key);
            if (m != null) {
                out.put(key.charAt(0), m);
            }
        }
        return out;
    }

    private Material material(String name, Material fallback, String where) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name.trim().toUpperCase());
        if (m == null) {
            log.warning("Unknown material '" + name + "' at " + where + (fallback != null ? "; using " + fallback : ""));
            return fallback;
        }
        return m;
    }
}
