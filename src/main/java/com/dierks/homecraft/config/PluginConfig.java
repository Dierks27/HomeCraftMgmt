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
import java.util.Locale;
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

    /**
     * The Mini Printer (Phase 9): its placed-block appearance, the per-print money
     * {@code fee}, and the Shiny finish material ({@code shinyAmount} of
     * {@code shinyDye} filament) + optional {@code shinyFee}.
     */
    public record Printer(Material baseBlock, String displayName, List<String> lore,
                          double fee, org.bukkit.DyeColor shinyDye, int shinyAmount, double shinyFee) {
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
    public record Displays(int refreshSeconds, HologramOpts hologram, MapTvOpts maptv) {
    }

    /**
     * Map-TV options (§3.8 follow-up): the dashboard URL a right-click opens in the
     * browser, and the commodity item pinned to the screen (a floating
     * {@code ItemDisplay}). {@code showItem}/{@code itemScale}/{@code spin} mirror the
     * hologram knobs.
     */
    public record MapTvOpts(String dashboardUrl, boolean showItem, float itemScale, boolean spin) {
    }

    /**
     * Hologram appearance (Phase 8 Part A): pair the text ticker with a floating
     * {@code ItemDisplay}. {@code itemOverride} null = use the commodity's own item.
     */
    public record HologramOpts(boolean itemDisplay, boolean above, boolean spin, Material itemOverride,
                               float itemScale, float textScale) {
    }

    // ---- Arcade (Phase 8, §3.9) — all in-game currency, never real money -------

    public enum RewardType {MONEY, ITEM, MINI}

    /** One weighted reward in a crate's loot table. */
    public record CrateReward(RewardType type, double amount, Material material, int itemAmount,
                              String miniId, double weight) {
    }

    /** An optional paid-odds tier: a Vault fee that guarantees a rarity floor for the pull. */
    public record PaidTier(double costMoney, Rarity floor) {
    }

    /** A token-priced loot crate with a weighted reward table (cap-aware Mini prizes). */
    public record Crate(String id, String display, int costTokens, List<CrateReward> rewards,
                        List<PaidTier> paidTiers) {
    }

    /** One weighted payout in the lotto/scratch table. */
    public record LottoPayout(double amount, double weight) {
    }

    public record Lotto(double ticketCost, List<LottoPayout> payouts) {
    }

    /** A one-time achievement: a token payout the first time a player hits a milestone. */
    public record AchievementDef(String id, boolean enabled, int reward, String display, double threshold) {
    }

    // ---- Daily/weekly quests (Phase 11, §3.9) — repeatable token objectives ----

    /** How often a quest resets (its progress window). */
    public enum QuestPeriod {DAILY, WEEKLY}

    /** The trackable objective a quest counts toward. Each maps to one earn hook. */
    public enum QuestType {SELL_MARKET, OPEN_CRATE, PRINT_MINI, OPEN_PACK, SCRATCH}

    /**
     * A single quest: reach {@code target} of {@code type} within the {@code period}
     * to earn {@code reward} tokens (once per period). All in-game currency.
     */
    public record Quest(String id, QuestPeriod period, QuestType type, long target, int reward, String display) {
    }

    /** The whole quest config: on/off plus the daily + weekly objective lists. */
    public record Quests(boolean enabled, List<Quest> all) {
        public List<Quest> byPeriod(QuestPeriod p) {
            List<Quest> out = new ArrayList<>();
            for (Quest q : all) {
                if (q.period() == p) {
                    out.add(q);
                }
            }
            return out;
        }
    }

    /** The whole Arcade config: token sources, crates, pity exchange, lotto, blocks. */
    public record Arcade(boolean enabled, boolean streakEnabled, List<Integer> streakRewards,
                         boolean playtimeEnabled, int playtimeMinutesPerToken,
                         Map<String, Crate> crates, int pityTokens, Rarity pityRarity, Lotto lotto,
                         BlockDef block, Map<String, BlockDef> machines) {

        /** Tokens awarded on a given consecutive-day streak (last entry repeats). */
        public int streakReward(int streakDay) {
            if (streakRewards.isEmpty()) {
                return 0;
            }
            int idx = Math.min(Math.max(1, streakDay), streakRewards.size()) - 1;
            return streakRewards.get(idx);
        }
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

    /** The Minis catalog + rarity styling (Phase 4) + per-type card specs (Phase 9). */
    public record Minis(String pricingMode, Map<Rarity, RarityStyle> rarityStyles,
                        List<String> categories, List<MiniDef> catalog,
                        Map<String, com.dierks.homecraft.mini.CardSpec> cardSpecs) {
        public RarityStyle style(Rarity rarity) {
            return rarityStyles.getOrDefault(rarity, DEFAULT_RARITY_STYLES.get(rarity));
        }

        /** The card spec for a Mini — its configured one, or a rarity-derived default. */
        public com.dierks.homecraft.mini.CardSpec cardSpec(MiniDef def) {
            com.dierks.homecraft.mini.CardSpec s = cardSpecs.get(def.id());
            return s != null ? s : com.dierks.homecraft.mini.CardSpec.defaultsFor(def.rarity());
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
    private Printer printer;
    private com.dierks.homecraft.mini.Pack.Packs packs;
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
    private Arcade arcade;
    private Map<String, AchievementDef> achievements;
    private Quests quests;

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

    public Printer printer() {
        return printer;
    }

    public com.dierks.homecraft.mini.Pack.Packs packs() {
        return packs;
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

    public Arcade arcade() {
        return arcade;
    }

    /** One-time achievement definitions keyed by id (Phase 9). */
    public Map<String, AchievementDef> achievements() {
        return achievements;
    }

    /** Daily/weekly quest definitions (Phase 11). */
    public Quests quests() {
        return quests;
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

        // ---- Mini Printer (Phase 9) ----
        Material prBase = material(c.getString("printer.base_block"), Material.SMITHING_TABLE, "printer.base_block");
        String prName = c.getString("printer.display_name", "&bMini Printer");
        List<String> prLore = c.getStringList("printer.lore");
        if (prLore.isEmpty()) {
            prLore = List.of("&7Right-click with a Card to print a graded Mini.");
        }
        double prFee = Math.max(0, c.getDouble("printer.fee", 50));
        org.bukkit.DyeColor shinyDye = parseDye(c.getString("printer.shiny.filament", "MAGENTA"));
        if (shinyDye == null) {
            shinyDye = org.bukkit.DyeColor.MAGENTA;
        }
        int shinyAmt = Math.max(0, c.getInt("printer.shiny.amount", 2));
        double shinyFee = Math.max(0, c.getDouble("printer.shiny.fee", 0));
        this.printer = new Printer(prBase, prName, prLore, prFee, shinyDye, shinyAmt, shinyFee);

        // ---- Card Packs (Phase 10) ----
        this.packs = readPacks(c);

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

        // ---- In-game economy displays (Phase 7 + Phase 8 hologram opts) ----
        boolean itemDisp = c.getBoolean("displays.hologram.item_display", true);
        boolean above = !"below".equalsIgnoreCase(c.getString("displays.hologram.position", "above"));
        boolean spin = c.getBoolean("displays.hologram.spin", true);
        String holoItem = c.getString("displays.hologram.item", "");
        Material holoMat = (holoItem == null || holoItem.isBlank())
                ? null : Material.matchMaterial(holoItem.trim().toUpperCase());
        float itemScale = (float) Math.max(0.05, c.getDouble("displays.hologram.item_scale", 0.3));
        float textScale = (float) Math.max(0.1, c.getDouble("displays.hologram.text_scale", 1.0));
        String mapTvUrl = c.getString("displays.maptv.dashboard_url", "http://localhost:8080");
        boolean mapTvItem = c.getBoolean("displays.maptv.show_item", true);
        float mapTvScale = (float) Math.max(0.05, c.getDouble("displays.maptv.item_scale", 0.5));
        boolean mapTvSpin = c.getBoolean("displays.maptv.spin", true);
        this.displays = new Displays(Math.max(2, c.getInt("displays.refresh_seconds", 20)),
                new HologramOpts(itemDisp, above, spin, holoMat, itemScale, textScale),
                new MapTvOpts(mapTvUrl, mapTvItem, mapTvScale, mapTvSpin));

        // ---- Arcade (Phase 8) ----
        this.arcade = readArcade(c);

        // ---- Achievements (Phase 9) ----
        this.achievements = readAchievements(c);

        // ---- Daily/weekly quests (Phase 11) ----
        this.quests = readQuests(c);
    }

    private Quests readQuests(FileConfiguration c) {
        boolean enabled = c.getBoolean("arcade.quests.enabled", true);
        List<Quest> all = new ArrayList<>();
        readQuestList(c, "arcade.quests.daily", QuestPeriod.DAILY, all);
        readQuestList(c, "arcade.quests.weekly", QuestPeriod.WEEKLY, all);
        return new Quests(enabled, all);
    }

    private void readQuestList(FileConfiguration c, String path, QuestPeriod period, List<Quest> out) {
        for (Map<?, ?> row : c.getMapList(path)) {
            String id = str(row.get("id"), "").toLowerCase(Locale.ROOT);
            QuestType type = parseQuestType(str(row.get("type"), ""));
            long target = (long) Math.max(1, number(row.get("target"), 1));
            int reward = (int) Math.max(0, number(row.get("reward"), 1));
            String display = str(row.get("display"), id);
            if (id.isBlank() || type == null) {
                continue; // a mistyped id/type is skipped rather than crashing the load
            }
            out.add(new Quest(id, period, type, target, reward, display));
        }
    }

    private QuestType parseQuestType(String s) {
        try {
            return QuestType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, AchievementDef> readAchievements(FileConfiguration c) {
        Map<String, AchievementDef> map = new LinkedHashMap<>();
        // Built-in milestones with sensible defaults; each toggleable/tunable in config.
        putAchievement(map, c, "first_mini", 3, "First Mini Collected", 0);
        putAchievement(map, c, "first_sale", 2, "First Market Sale", 0);
        putAchievement(map, c, "first_pc", 2, "Built Your First PC", 0);
        putAchievement(map, c, "first_crate", 1, "Opened Your First Crate", 0);
        putAchievement(map, c, "first_pack", 2, "Opened Your First Pack", 0);
        putAchievement(map, c, "rich_10k", 5, "Reached $10,000", 10000);
        return map;
    }

    private void putAchievement(Map<String, AchievementDef> map, FileConfiguration c, String id,
                                int defReward, String defDisplay, double defThreshold) {
        String base = "arcade.achievements." + id;
        boolean enabled = c.getBoolean(base + ".enabled", true);
        int reward = Math.max(0, c.getInt(base + ".reward", defReward));
        String display = c.getString(base + ".display", defDisplay);
        double threshold = c.getDouble(base + ".threshold", defThreshold);
        map.put(id, new AchievementDef(id, enabled, reward, display, threshold));
    }

    private Arcade readArcade(FileConfiguration c) {
        boolean enabled = c.getBoolean("arcade.enabled", true);
        boolean streakEnabled = c.getBoolean("arcade.tokens.login_streak.enabled", true);
        // Escalating per-consecutive-day reward table (last entry repeats for day N+).
        List<Integer> streakRewards = new ArrayList<>();
        for (int v : c.getIntegerList("arcade.tokens.login_streak.rewards")) {
            streakRewards.add(Math.max(0, v));
        }
        if (streakRewards.isEmpty()) {
            streakRewards.add(Math.max(0, c.getInt("arcade.tokens.login_streak.reward_per_day", 1)));
        }
        boolean ptEnabled = c.getBoolean("arcade.tokens.playtime.enabled", true);
        int minsPerToken = Math.max(0, c.getInt("arcade.tokens.playtime.minutes_per_token", 60));

        Map<String, Crate> crates = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("arcade.crates");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                String base = "arcade.crates." + key;
                String display = c.getString(base + ".display", key);
                int cost = Math.max(0, c.getInt(base + ".cost_tokens", 1));
                List<CrateReward> rewards = new ArrayList<>();
                for (Map<?, ?> row : c.getMapList(base + ".rewards")) {
                    CrateReward r = readReward(row);
                    if (r != null) {
                        rewards.add(r);
                    }
                }
                List<PaidTier> tiers = new ArrayList<>();
                for (Map<?, ?> row : c.getMapList(base + ".paid_odds")) {
                    double costMoney = number(row.get("cost_money"), 0);
                    Rarity floor = parseRarity(str(row.get("floor"), str(row.get("boost_rarity"), "RARE")));
                    if (costMoney > 0) {
                        tiers.add(new PaidTier(costMoney, floor));
                    }
                }
                crates.put(key.toLowerCase(Locale.ROOT),
                        new Crate(key.toLowerCase(Locale.ROOT), display, cost, rewards, tiers));
            }
        }

        int pityTokens = Math.max(0, c.getInt("arcade.pity.tokens", 3));
        Rarity pityRarity = parseRarity(c.getString("arcade.pity.guarantees_rarity", "RARE"));

        double ticketCost = c.getDouble("arcade.lotto.ticket_cost_money", 250);
        List<LottoPayout> payouts = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.lotto.payouts")) {
            payouts.add(new LottoPayout(Math.max(0, number(row.get("amount"), 0)),
                    Math.max(0.0001, number(row.get("weight"), 1))));
        }

        BlockDef block = blockDef(c, "arcade.block", Material.JUKEBOX, "&5Arcade Machine");

        Map<String, BlockDef> machines = new LinkedHashMap<>();
        machines.put("crate", blockDef(c, "arcade.machines.crate", Material.CHEST, "&6Crate Machine"));
        machines.put("scratch", blockDef(c, "arcade.machines.scratch", Material.CARTOGRAPHY_TABLE, "&aScratch-Ticket Booth"));
        machines.put("pity", blockDef(c, "arcade.machines.pity", Material.ENCHANTING_TABLE, "&bPity Exchange"));
        machines.put("counter", blockDef(c, "arcade.machines.counter", Material.LECTERN, "&eToken Counter"));

        return new Arcade(enabled, streakEnabled, streakRewards, ptEnabled, minsPerToken,
                crates, pityTokens, pityRarity, new Lotto(Math.max(0, ticketCost), payouts), block, machines);
    }

    private CrateReward readReward(Map<?, ?> row) {
        String type = str(row.get("type"), "money").toLowerCase(Locale.ROOT);
        double weight = Math.max(0.0001, number(row.get("weight"), 1));
        switch (type) {
            case "money" -> {
                return new CrateReward(RewardType.MONEY, Math.max(0, number(row.get("amount"), 0)),
                        null, 0, null, weight);
            }
            case "item" -> {
                Material m = Material.matchMaterial(str(row.get("material"), "").toUpperCase(Locale.ROOT));
                if (m == null || !m.isItem()) {
                    return null;
                }
                int amt = (int) number(row.get("amount"), 1);
                return new CrateReward(RewardType.ITEM, 0, m, Math.max(1, amt), null, weight);
            }
            case "mini" -> {
                String mini = str(row.get("mini"), null);
                if (mini == null || mini.isBlank()) {
                    return null;
                }
                return new CrateReward(RewardType.MINI, 0, null, 0,
                        com.dierks.homecraft.mini.MiniIds.slug(mini), weight);
            }
            default -> {
                return null;
            }
        }
    }

    private Rarity parseRarity(String s) {
        try {
            return Rarity.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Rarity.RARE;
        }
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
        putSkin(map, sec, "printer", com.dierks.homecraft.block.CustomBlockType.PRINTER);
        putSkin(map, sec, "vending", com.dierks.homecraft.block.CustomBlockType.MINI_VENDING_MACHINE);
        putSkin(map, sec, "display_case", com.dierks.homecraft.block.CustomBlockType.DISPLAY_CASE);
        putSkin(map, sec, "auction", com.dierks.homecraft.block.CustomBlockType.AUCTION_HOUSE);
        putSkin(map, sec, "mailbox", com.dierks.homecraft.block.CustomBlockType.MAILBOX);
        putSkin(map, sec, "pallet", com.dierks.homecraft.block.CustomBlockType.PALLET);
        putSkin(map, sec, "arcade", com.dierks.homecraft.block.CustomBlockType.ARCADE);
        putSkin(map, sec, "crate_machine", com.dierks.homecraft.block.CustomBlockType.CRATE_MACHINE);
        putSkin(map, sec, "scratch_booth", com.dierks.homecraft.block.CustomBlockType.SCRATCH_BOOTH);
        putSkin(map, sec, "pity_kiosk", com.dierks.homecraft.block.CustomBlockType.PITY_KIOSK);
        putSkin(map, sec, "token_counter", com.dierks.homecraft.block.CustomBlockType.TOKEN_COUNTER);
        putSkin(map, sec, "tv", com.dierks.homecraft.block.CustomBlockType.TV);
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
        Map<String, com.dierks.homecraft.mini.CardSpec> cardSpecs = new LinkedHashMap<>();
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
                cardSpecs.put(id, readCardSpec(e.get("card"), rarity));
            }
        }
        return new Minis(pricingMode, styles, categories, catalog, cardSpecs);
    }

    /** Parse a Mini's optional {@code card:} block, or rarity-derive it if absent. */
    private com.dierks.homecraft.mini.CardSpec readCardSpec(Object raw, Rarity rarity) {
        com.dierks.homecraft.mini.CardSpec def = com.dierks.homecraft.mini.CardSpec.defaultsFor(rarity);
        if (!(raw instanceof Map<?, ?> card)) {
            return def;
        }
        long cardCap = card.get("cap") != null ? (long) number(card.get("cap"), -1) : def.cardCap();

        Map<com.dierks.homecraft.mini.Grade, Double> grades =
                new EnumMap<>(com.dierks.homecraft.mini.Grade.class);
        if (card.get("grades") instanceof Map<?, ?> gm && !gm.isEmpty()) {
            for (com.dierks.homecraft.mini.Grade g : com.dierks.homecraft.mini.Grade.values()) {
                Object w = gm.get(g.name().toLowerCase(Locale.ROOT));
                grades.put(g, Math.max(0, number(w, 0)));
            }
        } else {
            grades.putAll(def.gradeWeights());
        }

        Map<org.bukkit.DyeColor, Integer> filament = new EnumMap<>(org.bukkit.DyeColor.class);
        if (card.get("filament") instanceof Map<?, ?> fm && !fm.isEmpty()) {
            for (Map.Entry<?, ?> en : fm.entrySet()) {
                org.bukkit.DyeColor c = parseDye(String.valueOf(en.getKey()));
                if (c != null) {
                    filament.put(c, Math.max(0, (int) number(en.getValue(), 0)));
                }
            }
        } else {
            filament.putAll(def.filament());
        }
        return new com.dierks.homecraft.mini.CardSpec(cardCap, grades, filament);
    }

    private org.bukkit.DyeColor parseDye(String s) {
        try {
            return org.bukkit.DyeColor.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse the {@code packs:} list into the typed Card-Pack model (Phase 10). */
    private com.dierks.homecraft.mini.Pack.Packs readPacks(FileConfiguration c) {
        List<com.dierks.homecraft.mini.Pack.PackDef> list = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("packs")) {
            String id = str(row.get("id"), null);
            if (id == null || id.isBlank()) {
                continue;
            }
            String display = str(row.get("display"), id);
            double price = Math.max(0, number(row.get("price"), 0));
            int count = Math.max(1, (int) number(row.get("count"), 3));
            List<com.dierks.homecraft.mini.Pack.PackEntry> pool = new ArrayList<>();
            if (row.get("pool") instanceof List<?> pl) {
                for (Object o : pl) {
                    if (o instanceof Map<?, ?> em) {
                        String card = str(em.get("card"), null);
                        if (card == null || card.isBlank()) {
                            continue;
                        }
                        double weight = Math.max(0, number(em.get("weight"), 1));
                        pool.add(new com.dierks.homecraft.mini.Pack.PackEntry(card, weight));
                    }
                }
            }
            list.add(new com.dierks.homecraft.mini.Pack.PackDef(id, display, price, count, pool));
        }
        return new com.dierks.homecraft.mini.Pack.Packs(list);
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
