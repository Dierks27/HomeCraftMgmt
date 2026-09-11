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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.RecipeChoice;
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
    public record Shaped(List<String> shape, Map<Character, RecipeChoice> ingredients) {
        public boolean isEmpty() {
            return shape == null || shape.isEmpty();
        }
    }

    /**
     * One data-driven block recipe from the {@code recipes:} section: SHAPED, up to
     * 3x3, symbols mapped to a {@link RecipeChoice} (a Material, or any item of a
     * {@code #tag}). {@code key} is the config key ({@code pc}, {@code printer},
     * {@code mailbox.blue}, …) and doubles as the recipe's namespaced-key stem.
     */
    public record BlockRecipe(String key, List<String> shape, Map<Character, RecipeChoice> ingredients) {
        public boolean isEmpty() {
            return shape == null || shape.isEmpty();
        }
    }

    /**
     * The filament recipe template ({@code recipes.filament}): one SHAPED recipe per
     * DyeColor, where the {@code <dye>} ingredient becomes that colour's dye and the
     * result is {@code output} filament of that colour.
     */
    public record FilamentRecipe(boolean enabled, List<String> shape, Map<Character, String> ingredients, int output) {
        public boolean isEmpty() {
            return !enabled || shape == null || shape.isEmpty();
        }
    }

    /** The PC's craft-grid recipe: shaped or shapeless, empty until an admin fills it. */
    public record PcRecipe(RecipeType type,
                           List<String> shape,
                           Map<Character, RecipeChoice> ingredients,
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
                          double fee, org.bukkit.DyeColor shinyDye, int shinyAmount, double shinyFee,
                          double publicFee) {
    }

    /** A daily trade allowance (0 = unlimited on that axis). */
    public record RankLimit(String permission, double maxMoneyPerDay, long maxUnitsPerDay) {
    }

    /** Anti-whale daily sell limits, with an optional per-permission override table. */
    public record SellLimits(boolean enabled, double maxMoneyPerDay, long maxUnitsPerDay,
                             String bypassPermission, List<RankLimit> ranks) {
    }

    /** Anti-drain daily buy limits — the sell-side mirror, same shape and semantics. */
    public record BuyLimits(boolean enabled, double maxMoneyPerDay, long maxUnitsPerDay,
                            String bypassPermission, List<RankLimit> ranks) {
    }

    /** The finite-stock market tuning + catalog (Phase 2.5). */
    public record Market(double elasticity, double inertia, double spread,
                         List<MarketItem> catalog, SellLimits sellLimits, BuyLimits buyLimits,
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
    public record Displays(int refreshSeconds, HologramOpts hologram, TvOpts tv) {
    }

    /** TV price-panel options (§3.8): the wall-mounted {@code TextDisplay} panel size. */
    public record TvOpts(float scale) {
    }

    /**
     * Hologram appearance (Phase 8 Part A): pair the text ticker with a floating
     * {@code ItemDisplay}. {@code itemOverride} null = use the commodity's own item.
     */
    public record HologramOpts(boolean itemDisplay, boolean above, boolean spin, Material itemOverride,
                               float itemScale, float textScale) {
    }

    // ---- Arcade (Phase 8, §3.9) — all in-game currency, never real money -------

    /**
     * What a crate may pay out. Tokens never become money: {@code money} and sellable
     * {@code item} rewards are rejected at config load (§11, two-currency rule).
     */
    public enum RewardType {CARD, MINI, PACK, FILAMENT, TOKENS}

    /**
     * One weighted reward in a crate's loot table. CARD and MINI name a fixed
     * {@code miniId} <em>or</em> a {@code tag} pool (every Mini carrying that tag,
     * weighted by rarity) — both issue that Mini's <b>Card</b>. PACK gives a sealed
     * pack ({@code packId}); FILAMENT gives {@code amount} filament of {@code color}
     * (null = random); TOKENS grants {@code amount} tokens.
     */
    public record CrateReward(RewardType type, String miniId, String tag, String packId, int amount,
                              org.bukkit.DyeColor color, double weight) {
        public boolean usesTag() {
            return tag != null && !tag.isBlank();
        }
    }

    // ---- Mini presentation: world effects + announcements ------------------------

    /**
     * World effects for placed Minis of one rarity (Display Case trophies, armor-stand
     * Minis, wild spawns). Any field may be off; {@code particle}/{@code placeSound}/
     * {@code placeParticle} are registry names (null = none).
     */
    public record MiniEffect(String particle, int particleCount, int particleInterval, double particleOffset,
                             boolean hologram, String hologramColor,
                             boolean light, int lightLevel,
                             boolean rotate, double degreesPerSecond,
                             boolean fullBright, String placeSound, String placeParticle) {

        public static final MiniEffect NONE = new MiniEffect(null, 0, 0, 0, false, "white", false, 0,
                false, 0, false, null, null);
    }

    /** All rarity effects + the shared knobs (radius, cadence, Mint chime, Shiny ring, wild hologram text). */
    public record MiniEffects(Map<Rarity, MiniEffect> byRarity, double radius, int tickInterval,
                              String mintSound, String shinyParticle, String wildHologramText,
                              double caseItemScale, double caseItemHeight) {
        public MiniEffect of(Rarity rarity) {
            return byRarity.getOrDefault(rarity, MiniEffect.NONE);
        }
    }

    /** Server-wide Mini announcements (found broadcasts, natural-spawn hints) and their gates. */
    public record Announce(boolean enabled, boolean found, boolean spawnHint, boolean slippedAway,
                           Rarity minRarity, String hintRadiusText, String sound,
                           Map<Rarity, Boolean> perRarity) {
        /** Whether a broadcast about this rarity is allowed at all. */
        public boolean allows(Rarity rarity) {
            return enabled && rarity.ordinal() >= minRarity.ordinal() && perRarity.getOrDefault(rarity, true);
        }
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

    /** The per-world economy sandbox (§11 #1): where money/tokens may move, and whether to log refusals. */
    public record Worlds(List<String> economyEnabled, boolean logBlockedAttempts) {
    }

    /** SQLite backup schedule (§11 #6). */
    public record Backups(boolean enabled, double intervalHours, int keep) {
    }

    /** Shop presentation: glow outline on shop displays (per block type), the listing hologram, and peek. */
    public record Shops(boolean glowEnabled, double glowRadius, Map<String, String> glowColors,
                        boolean hologramEnabled, int hologramLines, double peekRange) {
        public String glowColor(String blockKey) {
            return glowColors.getOrDefault(blockKey, "white");
        }
    }

    /**
     * Config-driven GUI titles so no section label is hard-coded (§2.2). The store
     * title supports {@code {store}} / {@code {url}} tokens, filled from
     * {@link Store}. Edit these live via the Admin Studio or {@code /hcm reload}.
     */
    public record MenuTitles(String admin, String museum, String market, String storeFormat) {
    }

    /**
     * Where the browsing menus start before a player touches anything. Stored as raw
     * strings so config stays independent of the GUI enums; each menu parses its own with
     * a safe fallback, so a typo in config.yml degrades to the default rather than failing
     * to open a menu.
     *
     * @param storeDepartment the department tab selected first ("All", or a department name)
     * @param storeSort       NAME | PRICE_UP | PRICE_DOWN | STOCK
     * @param museumView      SERIES | RARITY | TYPE
     */
    public record MenuDefaults(String storeDepartment, String storeSort, String museumView) {
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
        // WHITE, not LIGHT_GRAY: a light-grey pane is the colour of the inventory slot behind it,
        // so a Common tile — every Common Museum header, and the Rarity button on a fresh Mini —
        // rendered as an empty slot.
        DEFAULT_RARITY_STYLES.put(Rarity.COMMON,
                new RarityStyle(Material.WHITE_STAINED_GLASS_PANE, NamedTextColor.GRAY, false, -1, 150));
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
    private MenuDefaults menuDefaults;
    private Minis minis;
    private MiniBlocks miniBlocks;
    private Loot.MiniLoot miniLoot;
    private MiniEffects miniEffects;
    private Announce announce;
    private Worlds worlds = new Worlds(List.of(), true);
    private Backups backups = new Backups(true, 24, 14);
    private Shops shops = new Shops(true, 16, Map.of(), true, 3, 6);
    private Map<String, StandData> miniStands;
    private Marketplace marketplace;
    private Map<com.dierks.homecraft.block.CustomBlockType, String> skins;
    /** Extra skin slots that aren't 1:1 with a block type (pallet_used, vending_upper, mailbox.<variant>). */
    private Map<String, String> namedSkins = new LinkedHashMap<>();
    /** Every data-driven block recipe, keyed by config key (pc, printer, …, mailbox.<variant>). */
    private Map<String, BlockRecipe> recipes = new LinkedHashMap<>();
    private FilamentRecipe filamentRecipe = new FilamentRecipe(false, List.of(), Map.of(), 3);
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

    public MenuDefaults menuDefaults() {
        return menuDefaults;
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

    /**
     * A named skin slot that isn't a block type of its own: {@code pallet_used},
     * {@code vending_upper}, or {@code mailbox.<variant>}. "" if unset.
     */
    public String skinNamed(String key) {
        return namedSkins.getOrDefault(key, "");
    }

    /** The head texture for a Display Case pedestal style ("" = none → plain base block). */
    public String displayCaseSkin(com.dierks.homecraft.block.DisplayCaseVariant variant) {
        return skinNamed("display_case." + variant.key());
    }

    /** "&b{variant} Display Case" → "&bRoyal Display Case" (a name without the token keeps its text). */
    public String displayCaseName(com.dierks.homecraft.block.DisplayCaseVariant variant) {
        String name = miniBlocks.display().name();
        if (name == null || name.isBlank()) {
            name = "&b{variant} Display Case";
        }
        return name.contains("{variant}") ? name.replace("{variant}", variant.label()) : name;
    }

    /** The head texture for a Mailbox colour variant ("" = none → plain base block). */
    public String mailboxSkin(com.dierks.homecraft.block.MailboxVariant variant) {
        return skinNamed("mailbox." + variant.key());
    }

    /**
     * The Mailbox display name for a variant: the configured name with {@code {variant}}
     * replaced by the colour label ("&e{variant} Mailbox" → "&eBlue Mailbox"). A name
     * without the token (pre-variant configs) gets the label inserted after any leading
     * colour codes, so "&eMailbox" still reads "&eBlue Mailbox".
     */
    public String mailboxName(com.dierks.homecraft.block.MailboxVariant variant) {
        String name = marketplace.mailbox().name();
        if (name == null || name.isBlank()) {
            name = "&e{variant} Mailbox";
        }
        if (name.contains("{variant}")) {
            return name.replace("{variant}", variant.label());
        }
        int i = 0;
        while (i + 1 < name.length() && name.charAt(i) == '&') {
            i += 2;
        }
        return name.substring(0, i) + variant.label() + " " + name.substring(i);
    }

    /** All data-driven block recipes (see {@code recipes:} in config.yml), keyed by config key. */
    public Map<String, BlockRecipe> recipes() {
        return recipes;
    }

    /** The per-colour filament recipe template ({@code recipes.filament}). */
    public FilamentRecipe filamentRecipe() {
        return filamentRecipe;
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

    /** World effects for placed Minis, by rarity (Phase 12). */
    public MiniEffects miniEffects() {
        return miniEffects;
    }

    /** Server-wide Mini announcements (Phase 12). */
    public Announce announce() {
        return announce;
    }

    /** The economy world sandbox (§11 #1). */
    public Worlds worlds() {
        return worlds;
    }

    /** Backup schedule (§11 #6). */
    public Backups backups() {
        return backups;
    }

    /** Shop glow / hologram / peek presentation. */
    public Shops shops() {
        return shops;
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
        // ---- Block recipes (recipes: section) — the PC's craft-grid recipe is the same
        // entry that is registered as its vanilla recipe; the legacy crafting.pc.recipe
        // is only honoured when recipes.pc is absent (pre-migration configs).
        this.recipes = readRecipes(c);
        BlockRecipe pcBlock = recipes.get("pc");
        PcRecipe pcRecipe = pcBlock != null
                ? new PcRecipe(RecipeType.SHAPED, pcBlock.shape(), pcBlock.ingredients(), List.of())
                : readPcRecipe(c, "crafting.pc.recipe");
        this.pc = new Pc(pcBase, pcName, pcLore, texture, pcRecipe);

        // ---- Mini Printer (Phase 9) ----
        Material prBase = material(c.getString("printer.base_block"), Material.SMITHING_TABLE, "printer.base_block");
        String prName = c.getString("printer.display_name", "&bMini Printer");
        List<String> prLore = c.getStringList("printer.lore");
        if (prLore.isEmpty()) {
            prLore = List.of("&7Right-click with a Card to print a graded Mini.");
        }
        double prFee = Math.max(0, c.getDouble("printer.fee", 0));
        double prPublicFee = Math.max(0, c.getDouble("printer.public_fee", 150));
        org.bukkit.DyeColor shinyDye = parseDye(c.getString("printer.shiny.filament", "MAGENTA"));
        if (shinyDye == null) {
            shinyDye = org.bukkit.DyeColor.MAGENTA;
        }
        int shinyAmt = Math.max(0, c.getInt("printer.shiny.amount", 2));
        double shinyFee = Math.max(0, c.getDouble("printer.shiny.fee", 0));
        this.printer = new Printer(prBase, prName, prLore, prFee, shinyDye, shinyAmt, shinyFee, prPublicFee);

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

        this.menuDefaults = new MenuDefaults(
                c.getString("menus.store.default_department", "All"),
                c.getString("menus.store.default_sort", "NAME"),
                c.getString("menus.museum.default_view", "SERIES"));

        // ---- Minis (Phase 4) ----
        this.minis = readMinis(c);

        // ---- Mini trading blocks (Phase 4c) ----
        this.miniBlocks = new MiniBlocks(
                blockDef(c, "minis.blocks.vending_machine", Material.BARREL, "&dMini Vending Machine"),
                blockDef(c, "minis.blocks.display_case", Material.PLAYER_HEAD, "&bMini Display Case"),
                blockDef(c, "minis.blocks.auction_house", Material.LECTERN, "&6Mini Auction House"));

        // ---- Wild Drops loot (Phase 4c Part C) ----
        this.miniLoot = readMiniLoot(c);

        // ---- Mini presentation (Phase 12): grade names/stars, world effects, announcements ----
        readGrades(c);
        this.miniEffects = readMiniEffects(c);
        this.announce = readAnnounce(c);

        // ---- Safety (Phase 13): economy world sandbox, backups, shop presentation ----
        List<String> enabledWorlds = new ArrayList<>();
        for (String w : c.getStringList("worlds.economy_enabled")) {
            if (w != null && !w.isBlank()) {
                enabledWorlds.add(w.trim());
            }
        }
        this.worlds = new Worlds(enabledWorlds, c.getBoolean("worlds.log_blocked_attempts", true));
        this.backups = new Backups(c.getBoolean("backups.enabled", true),
                Math.max(0, c.getDouble("backups.interval_hours", 24)),
                Math.max(0, c.getInt("backups.keep", 14)));
        Map<String, String> glowColors = new LinkedHashMap<>();
        ConfigurationSection gc = c.getConfigurationSection("shops.glow.color");
        if (gc != null) {
            for (String key : gc.getKeys(false)) {
                glowColors.put(key.toLowerCase(Locale.ROOT), gc.getString(key, "white"));
            }
        }
        this.shops = new Shops(c.getBoolean("shops.glow.enabled", true),
                Math.max(2, c.getDouble("shops.glow.radius", 16)), glowColors,
                c.getBoolean("shops.hologram.enabled", true),
                Math.max(0, c.getInt("shops.hologram.lines", 3)),
                Math.max(1, c.getDouble("shops.peek.range", 6)));

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
        float tvScale = (float) Math.max(0.5, Math.min(12.0, c.getDouble("displays.tv.scale", 3.0)));
        this.displays = new Displays(Math.max(2, c.getInt("displays.refresh_seconds", 20)),
                new HologramOpts(itemDisp, above, spin, holoMat, itemScale, textScale),
                new TvOpts(tvScale));

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

        int pityTokens = Math.max(0, c.getInt("arcade.pity.tokens", 25));
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
        String type = str(row.get("type"), "").toLowerCase(Locale.ROOT);
        double weight = Math.max(0.0001, number(row.get("weight"), 1));
        switch (type) {
            case "money", "item" -> {
                log.warning("Arcade crate reward of type '" + type + "' is not allowed — tokens must never "
                        + "become money or sellable items (§11). Use card, mini, pack, filament or tokens. Skipped.");
                return null;
            }
            case "card", "mini" -> {
                String mini = str(row.get("mini"), str(row.get("card"), null));
                String tag = str(row.get("tag"), null);
                boolean hasMini = mini != null && !mini.isBlank();
                boolean hasTag = tag != null && !tag.isBlank();
                if (!hasMini && !hasTag) {
                    log.warning("Arcade crate '" + type + "' reward needs a mini: id or a tag: — skipped.");
                    return null;
                }
                return new CrateReward(type.equals("mini") ? RewardType.MINI : RewardType.CARD,
                        hasMini ? com.dierks.homecraft.mini.MiniIds.slug(mini) : null,
                        hasTag ? tag.trim().toLowerCase(Locale.ROOT) : null, null, 1, null, weight);
            }
            case "pack" -> {
                String pack = str(row.get("pack"), null);
                if (pack == null || pack.isBlank()) {
                    log.warning("Arcade crate 'pack' reward needs a pack: id — skipped.");
                    return null;
                }
                return new CrateReward(RewardType.PACK, null, null, pack.trim(), 1, null, weight);
            }
            case "filament" -> {
                int amt = Math.max(1, (int) number(row.get("amount"), 1));
                org.bukkit.DyeColor color = parseDye(str(row.get("color"), null));
                return new CrateReward(RewardType.FILAMENT, null, null, null, amt, color, weight);
            }
            case "tokens" -> {
                int amt = Math.max(1, (int) number(row.get("amount"), 1));
                return new CrateReward(RewardType.TOKENS, null, null, null, amt, null, weight);
            }
            default -> {
                log.warning("Arcade crate reward has unknown type '" + type + "' — skipped.");
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
        Map<String, String> namedSkinsEarly = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection("skins");
        if (sec == null) {
            this.namedSkins = namedSkinsEarly;
            return map;
        }
        putSkin(map, sec, "pc", com.dierks.homecraft.block.CustomBlockType.PC);
        putSkin(map, sec, "workbench", com.dierks.homecraft.block.CustomBlockType.MINI_WORKBENCH);
        putSkin(map, sec, "printer", com.dierks.homecraft.block.CustomBlockType.PRINTER);
        // Two-tall Vending Machine: the lower head IS the block (legacy key: vending).
        putSkin(map, sec, sec.contains("vending_lower") ? "vending_lower" : "vending",
                com.dierks.homecraft.block.CustomBlockType.MINI_VENDING_MACHINE);
        // Display Case pedestal styles: a per-variant map (legacy single string = plain).
        ConfigurationSection dc = sec.getConfigurationSection("display_case");
        if (dc != null) {
            for (com.dierks.homecraft.block.DisplayCaseVariant v : com.dierks.homecraft.block.DisplayCaseVariant.values()) {
                String val = dc.getString(v.key(), "");
                if (val != null && !val.isBlank()) {
                    namedSkinsEarly.put("display_case." + v.key(), val.trim());
                }
            }
        } else {
            String legacy = sec.getString("display_case", "");
            if (legacy != null && !legacy.isBlank()) {
                namedSkinsEarly.put("display_case.plain", legacy.trim());
            }
        }
        String plainCase = namedSkinsEarly.get("display_case.plain");
        if (plainCase != null) {
            map.put(com.dierks.homecraft.block.CustomBlockType.DISPLAY_CASE, plainCase);
        }
        putSkin(map, sec, "auction", com.dierks.homecraft.block.CustomBlockType.AUCTION_HOUSE);
        // Two-state Pallet: the item + an empty placed Pallet wear pallet_empty (legacy key: pallet).
        putSkin(map, sec, sec.contains("pallet_empty") ? "pallet_empty" : "pallet",
                com.dierks.homecraft.block.CustomBlockType.PALLET);
        putSkin(map, sec, "arcade", com.dierks.homecraft.block.CustomBlockType.ARCADE);
        putSkin(map, sec, "crate_machine", com.dierks.homecraft.block.CustomBlockType.CRATE_MACHINE);
        putSkin(map, sec, "scratch_booth", com.dierks.homecraft.block.CustomBlockType.SCRATCH_BOOTH);
        putSkin(map, sec, "pity_kiosk", com.dierks.homecraft.block.CustomBlockType.PITY_KIOSK);
        putSkin(map, sec, "token_counter", com.dierks.homecraft.block.CustomBlockType.TOKEN_COUNTER);

        // Named slots: the Pallet's loaded state, the Vending Machine's top half, and
        // one skin per Mailbox colour. A legacy single-string `mailbox:` counts as wood.
        Map<String, String> named = new LinkedHashMap<>(namedSkinsEarly);
        putNamed(named, sec, "pallet_used");
        putNamed(named, sec, "vending_upper");
        ConfigurationSection mb = sec.getConfigurationSection("mailbox");
        if (mb != null) {
            for (com.dierks.homecraft.block.MailboxVariant v : com.dierks.homecraft.block.MailboxVariant.values()) {
                String val = mb.getString(v.key(), "");
                if (val != null && !val.isBlank()) {
                    named.put("mailbox." + v.key(), val.trim());
                }
            }
        } else {
            String legacy = sec.getString("mailbox", "");
            if (legacy != null && !legacy.isBlank()) {
                named.put("mailbox.wood", legacy.trim());
            }
        }
        // The base MAILBOX skin slot is the wood variant (used by items.of(MAILBOX)).
        String wood = named.get("mailbox.wood");
        if (wood != null) {
            map.put(com.dierks.homecraft.block.CustomBlockType.MAILBOX, wood);
        }
        this.namedSkins = named;
        return map;
    }

    private void putNamed(Map<String, String> map, ConfigurationSection sec, String key) {
        String v = sec.getString(key, "");
        if (v != null && !v.isBlank()) {
            map.put(key, v.trim());
        }
    }

    /**
     * Read the {@code recipes:} section: a flat entry ({@code pc:}) has {@code shape}
     * directly; a grouped entry ({@code mailbox:}) holds one sub-entry per variant,
     * keyed {@code mailbox.<variant>}. Entries with an empty shape are kept (so the
     * loader can report them) but never registered.
     */
    private Map<String, BlockRecipe> readRecipes(FileConfiguration c) {
        Map<String, BlockRecipe> out = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection("recipes");
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            if (key.equalsIgnoreCase("filament")) {
                this.filamentRecipe = readFilamentRecipe(entry);
                continue;
            }
            if (entry.contains("shape")) {
                out.put(key, readBlockRecipe(c, key, "recipes." + key));
            } else {
                for (String sub : entry.getKeys(false)) {
                    if (entry.getConfigurationSection(sub) != null) {
                        String full = key + "." + sub;
                        out.put(full, readBlockRecipe(c, full, "recipes." + full));
                    }
                }
            }
        }
        return out;
    }

    /** {@code recipes.filament}: shape + symbol map kept as raw names (one is the {@code <dye>} placeholder). */
    private FilamentRecipe readFilamentRecipe(ConfigurationSection sec) {
        boolean enabled = sec.getBoolean("enabled", true);
        List<String> shape = new ArrayList<>(sec.getStringList("shape"));
        Map<Character, String> ing = new LinkedHashMap<>();
        ConfigurationSection is = sec.getConfigurationSection("ingredients");
        if (is != null) {
            for (String k : is.getKeys(false)) {
                if (!k.isEmpty()) {
                    ing.put(k.charAt(0), is.getString(k, ""));
                }
            }
        }
        int output = Math.max(1, sec.getInt("output", 3));
        return new FilamentRecipe(enabled, shape, ing, output);
    }

    private BlockRecipe readBlockRecipe(FileConfiguration c, String key, String path) {
        List<String> shape = new ArrayList<>(c.getStringList(path + ".shape"));
        if (shape.size() > 3) {
            log.warning("Recipe " + path + " has more than 3 rows; extra rows ignored.");
            shape = new ArrayList<>(shape.subList(0, 3));
        }
        for (int i = 0; i < shape.size(); i++) {
            String row = shape.get(i);
            if (row.length() > 3) {
                log.warning("Recipe " + path + " row " + (i + 1) + " is wider than 3; truncated.");
                shape.set(i, row.substring(0, 3));
            }
        }
        Map<Character, RecipeChoice> ing = readSymbolMap(c, path + ".ingredients");
        // Every non-space symbol in the shape must resolve, or the recipe is disabled.
        for (String row : shape) {
            for (char sym : row.toCharArray()) {
                if (sym != ' ' && !ing.containsKey(sym)) {
                    log.warning("Recipe " + path + " uses symbol '" + sym
                            + "' with no ingredient; recipe disabled.");
                    return new BlockRecipe(key, List.of(), Map.of());
                }
            }
        }
        return new BlockRecipe(key, List.copyOf(shape), ing);
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
            departments = List.of("Blocks", "Materials", "Food", "Tools", "Combat", "Redstone", "Collectibles", "Misc");
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
            String tag = str(row.get("tag"), null);
            boolean hasList = listId != null && !listId.isBlank();
            boolean hasTag = tag != null && !tag.isBlank();
            if (!hasList && !hasTag) {
                continue;
            }
            Loot.Trigger t = Loot.Trigger.parse(str(row.get("trigger"), "BLOCK_BREAK"));
            String match = str(row.get("match"), "*");
            double chance = row.get("chance_percent") != null
                    ? number(row.get("chance_percent"), 0)
                    : number(row.get("chance"), 0);
            Map<com.dierks.homecraft.mini.Grade, Double> grades = null;
            if (row.get("grades") instanceof Map<?, ?> gm && !gm.isEmpty()) {
                grades = new EnumMap<>(com.dierks.homecraft.mini.Grade.class);
                for (Map.Entry<?, ?> en : gm.entrySet()) {
                    com.dierks.homecraft.mini.Grade g = com.dierks.homecraft.mini.Grade.parse(String.valueOf(en.getKey()));
                    grades.merge(g, Math.max(0, number(en.getValue(), 0)), Double::sum);
                }
            }
            Double shiny = row.get("shiny_percent") != null ? Math.max(0, number(row.get("shiny_percent"), 0)) : null;
            sources.add(new Loot.LootSource(t, match, hasList ? listId : null,
                    hasTag ? tag.trim().toLowerCase(Locale.ROOT) : null, Math.max(0, chance), grades, shiny));
        }

        Map<Rarity, Double> rarityWeights = Loot.MiniLoot.defaultRarityWeights();
        ConfigurationSection rw = c.getConfigurationSection("minis.loot.rarity_weights");
        if (rw != null) {
            for (String key : rw.getKeys(false)) {
                try {
                    rarityWeights.put(Rarity.valueOf(key.trim().toUpperCase(Locale.ROOT)), Math.max(0, rw.getDouble(key)));
                } catch (IllegalArgumentException ex) {
                    log.warning("Unknown rarity '" + key + "' in minis.loot.rarity_weights — ignored.");
                }
            }
        }
        double shinyPercent = Math.max(0, c.getDouble("minis.loot.shiny_percent", 5.0));
        int minDistance = Math.max(4, c.getInt("minis.loot.natural.min_distance", 96));
        Loot.Natural natural = new Loot.Natural(
                Math.max(200, c.getInt("minis.loot.natural.interval_ticks", 24000)),
                Math.max(1, c.getInt("minis.loot.natural.despawn_minutes", 3)),
                minDistance,
                // A max below the min would make every band empty and no Mini would ever land.
                Math.max(minDistance + 8, c.getInt("minis.loot.natural.max_distance", 128)),
                Math.max(0, c.getInt("minis.loot.natural.player_cooldown_minutes", 120)),
                Math.max(0, c.getInt("minis.loot.natural.max_live", 2)));
        return new Loot.MiniLoot(lists, sources, rarityWeights, shinyPercent, natural);
    }

    /** {@code tags: [a, b]} or {@code tags: "a, b"} → lower-case, de-duplicated list. */
    private List<String> readTags(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                addTag(out, o == null ? "" : String.valueOf(o));
            }
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split("[,\\s]+")) {
                addTag(out, part);
            }
        }
        return out;
    }

    private void addTag(List<String> out, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!v.isEmpty() && !out.contains(v)) {
            out.add(v);
        }
    }

    /** {@code minis.grades.<GRADE>: { name, symbol, multiplier }} → applied to the Grade enum. */
    private void readGrades(FileConfiguration c) {
        Map<com.dierks.homecraft.mini.Grade, com.dierks.homecraft.mini.Grade.Style> styles =
                new EnumMap<>(com.dierks.homecraft.mini.Grade.class);
        ConfigurationSection sec = c.getConfigurationSection("minis.grades");
        if (sec != null) {
            for (com.dierks.homecraft.mini.Grade g : com.dierks.homecraft.mini.Grade.values()) {
                ConfigurationSection gs = sec.getConfigurationSection(g.name());
                if (gs == null) {
                    gs = sec.getConfigurationSection(g.name().toLowerCase(Locale.ROOT));
                }
                if (gs == null) {
                    continue;
                }
                String symbol = gs.getString("symbol", g.symbol());
                if (com.dierks.homecraft.mini.Grade.isMangledSymbol(symbol)) {
                    // '?' is what ☆ becomes when config.yml is saved back as ANSI instead of
                    // UTF-8, so every Mini renders as "Creeper ?". Grade falls back to the
                    // built-in star; say why, because nothing in-game can show the cause.
                    log.warning("minis.grades." + g.name() + ".symbol is \"" + symbol
                            + "\" — that is a UTF-8 character your editor could not save. Re-save "
                            + "config.yml as UTF-8 (or let the config migration restore the stars). "
                            + "Using the built-in " + g.symbol() + " until then.");
                    symbol = null;
                }
                styles.put(g, new com.dierks.homecraft.mini.Grade.Style(
                        gs.getString("name", g.display()),
                        symbol,
                        gs.getDouble("multiplier", g.valueMultiplier())));
            }
        }
        com.dierks.homecraft.mini.Grade.configure(styles);
    }

    /** Built-in per-rarity effect defaults, overridable leaf-by-leaf under {@code minis.effects.<RARITY>}. */
    private MiniEffects readMiniEffects(FileConfiguration c) {
        Map<Rarity, MiniEffect> defaults = new EnumMap<>(Rarity.class);
        defaults.put(Rarity.COMMON, MiniEffect.NONE);
        defaults.put(Rarity.UNCOMMON, MiniEffect.NONE);
        defaults.put(Rarity.RARE, new MiniEffect("END_ROD", 2, 40, 0.3, true, "aqua", true, 7,
                true, 20, false, null, null));
        defaults.put(Rarity.EPIC, new MiniEffect("ENCHANT", 6, 20, 0.4, true, "light_purple", true, 7,
                true, 20, false, null, null));
        defaults.put(Rarity.LEGENDARY, new MiniEffect("SOUL_FIRE_FLAME", 3, 30, 0.4, true, "gold", true, 12,
                true, 20, true, "ITEM_TOTEM_USE", "TOTEM_OF_UNDYING"));

        Map<Rarity, MiniEffect> out = new EnumMap<>(Rarity.class);
        for (Rarity r : Rarity.values()) {
            MiniEffect d = defaults.get(r);
            ConfigurationSection sec = c.getConfigurationSection("minis.effects." + r.name());
            if (sec == null) {
                out.put(r, d);
                continue;
            }
            ConfigurationSection particle = sec.getConfigurationSection("particle");
            ConfigurationSection holo = sec.getConfigurationSection("hologram");
            ConfigurationSection light = sec.getConfigurationSection("light");
            ConfigurationSection rotate = sec.getConfigurationSection("rotate");
            String particleType = particle != null ? particle.getString("type", d.particle()) : d.particle();
            if (particleType != null && (particleType.isBlank() || particleType.equalsIgnoreCase("none"))) {
                particleType = null;
            }
            String placeSound = blankToNull(sec.getString("place_sound", d.placeSound()));
            String placeParticle = blankToNull(sec.getString("place_particle", d.placeParticle()));
            out.put(r, new MiniEffect(
                    particleType == null ? null : particleType.trim().toUpperCase(Locale.ROOT),
                    particle != null ? Math.max(0, particle.getInt("count", d.particleCount())) : d.particleCount(),
                    particle != null ? Math.max(1, particle.getInt("interval_ticks", Math.max(1, d.particleInterval())))
                            : d.particleInterval(),
                    particle != null ? Math.max(0, particle.getDouble("offset", d.particleOffset())) : d.particleOffset(),
                    holo != null ? holo.getBoolean("enabled", d.hologram()) : d.hologram(),
                    holo != null ? holo.getString("color", d.hologramColor()) : d.hologramColor(),
                    light != null ? light.getBoolean("enabled", d.light()) : d.light(),
                    light != null ? Math.max(0, Math.min(15, light.getInt("level", d.lightLevel()))) : d.lightLevel(),
                    rotate != null ? rotate.getBoolean("enabled", d.rotate()) : d.rotate(),
                    rotate != null ? Math.max(0, rotate.getDouble("degrees_per_second", d.degreesPerSecond()))
                            : d.degreesPerSecond(),
                    sec.getBoolean("full_bright", d.fullBright()),
                    placeSound == null ? null : placeSound.trim().toUpperCase(Locale.ROOT),
                    placeParticle == null ? null : placeParticle.trim().toUpperCase(Locale.ROOT)));
        }
        return new MiniEffects(out,
                Math.max(2, c.getDouble("minis.effects.radius", 16)),
                Math.max(1, c.getInt("minis.effects.tick_interval", 10)),
                c.getString("minis.effects.mint_sound", "BLOCK_AMETHYST_BLOCK_CHIME"),
                c.getString("minis.effects.shiny_particle", "GLOW"),
                c.getString("minis.effects.wild_hologram_text", "&dA wild Mini!"),
                Math.max(0.1, c.getDouble("minis.effects.case_item_scale", 0.6)),
                Math.max(0.0, c.getDouble("minis.effects.case_item_height", 0.9)));
    }

    private Announce readAnnounce(FileConfiguration c) {
        Map<Rarity, Boolean> per = new EnumMap<>(Rarity.class);
        for (Rarity r : Rarity.values()) {
            per.put(r, c.getBoolean("minis.announce.per_rarity." + r.name(), true));
        }
        Rarity min;
        try {
            min = Rarity.valueOf(c.getString("minis.announce.min_rarity", "COMMON").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            min = Rarity.COMMON;
        }
        return new Announce(
                c.getBoolean("minis.announce.enabled", true),
                c.getBoolean("minis.announce.found", true),
                c.getBoolean("minis.announce.spawn_hint", true),
                c.getBoolean("minis.announce.slipped_away", true),
                min,
                c.getString("minis.announce.hint_radius_text", "within %blocks% blocks of a player"),
                c.getString("minis.announce.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"),
                per);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || s.equalsIgnoreCase("none") ? null : s;
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
                List<String> tags = readTags(e.get("tags"));
                catalog.add(new MiniDef(id, name, seriesName, category, rarity, type, texture, cap, price, craftable, tags));
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
            // Keys are the grade names (standard/graded/mint); the retired five-grade
            // keys (gray…gold) fold onto their successors so old configs still load.
            for (com.dierks.homecraft.mini.Grade g : com.dierks.homecraft.mini.Grade.values()) {
                grades.put(g, 0.0);
            }
            for (Map.Entry<?, ?> en : gm.entrySet()) {
                com.dierks.homecraft.mini.Grade g = com.dierks.homecraft.mini.Grade.parse(String.valueOf(en.getKey()));
                grades.merge(g, Math.max(0, number(en.getValue(), 0)), Double::sum);
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
        if (s == null || s.isBlank()) {
            return null;
        }
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
            // Hard rule: stock must NEVER start at (or above) full_stock. full_stock is the
            // denominator of the price curve, not a target — the market has to keep room for
            // players to sell into, or the item lands on the floor with nowhere left to go.
            if (initialStock >= fullStock) {
                long clamped = Math.min((long) (fullStock * 0.85), fullStock - 1);
                log.warning("market.catalog[" + id + "] initial_stock (" + initialStock
                        + ") >= full_stock (" + fullStock + "); clamping to " + clamped
                        + " — the market must always have room to sell into.");
                initialStock = Math.max(0L, clamped);
            }
            // Optional per-player, per-item, per-day unit caps (0 = none). Enforced on top
            // of the global sell_limits/buy_limits — the tighter cap wins.
            long maxDailySell = Math.max(0L, (long) number(row.get("max_daily_sell"), 0));
            long maxDailyBuy = Math.max(0L, (long) number(row.get("max_daily_buy"), 0));

            catalog.add(new MarketItem(id, material, displayName, floor, ceiling, initialStock, fullStock,
                    maxDailySell, maxDailyBuy));
        }
        return new Market(elasticity, inertia, spread, catalog,
                readSellLimits(c), readBuyLimits(c), Math.max(1, historyMinutes));
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

    private BuyLimits readBuyLimits(FileConfiguration c) {
        boolean enabled = c.getBoolean("market.buy_limits.enabled", true);
        double maxMoney = c.getDouble("market.buy_limits.max_money_per_day", 0);
        long maxUnits = c.getLong("market.buy_limits.max_units_per_day", 0);
        String bypass = c.getString("market.buy_limits.bypass_permission", "hcm.market.limit.bypass");

        List<RankLimit> ranks = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("market.buy_limits.ranks")) {
            Object perm = row.get("permission");
            if (perm == null || String.valueOf(perm).isBlank()) {
                continue;
            }
            ranks.add(new RankLimit(
                    String.valueOf(perm),
                    number(row.get("max_money_per_day"), 0),
                    (long) number(row.get("max_units_per_day"), 0)));
        }
        return new BuyLimits(enabled, maxMoney, maxUnits, bypass, ranks);
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
        Map<Character, RecipeChoice> ing = readSymbolMap(c, path + ".ingredients");
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
        Map<Character, RecipeChoice> ing = readSymbolMap(c, path + ".ingredients");

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

    /**
     * Reads an {@code ingredients} section shaped like {@code A: IRON_INGOT} (or
     * {@code A: "#planks"} for any item of a tag) into a char -> {@link RecipeChoice} map.
     */
    private Map<Character, RecipeChoice> readSymbolMap(FileConfiguration c, String path) {
        Map<Character, RecipeChoice> out = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection(path);
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            if (key.isEmpty()) {
                continue;
            }
            RecipeChoice choice = ingredientChoice(sec.getString(key), path + "." + key);
            if (choice != null) {
                out.put(key.charAt(0), choice);
            }
        }
        return out;
    }

    /** A Material name → exact-material choice; {@code #tag} → any item in that tag; null if unknown. */
    private RecipeChoice ingredientChoice(String raw, String where) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.startsWith("#")) {
            String tagName = v.substring(1).trim().toLowerCase(Locale.ROOT);
            NamespacedKey key = tagName.contains(":") ? NamespacedKey.fromString(tagName)
                    : NamespacedKey.minecraft(tagName);
            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null && key != null) {
                tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
            }
            if (tag == null) {
                log.warning("Unknown item tag '" + v + "' at " + where + "; ingredient skipped.");
                return null;
            }
            List<Material> items = new ArrayList<>();
            for (Material m : tag.getValues()) {
                if (m.isItem()) {
                    items.add(m);
                }
            }
            if (items.isEmpty()) {
                log.warning("Item tag '" + v + "' at " + where + " holds no items; ingredient skipped.");
                return null;
            }
            return new RecipeChoice.MaterialChoice(items);
        }
        Material m = material(v, null, where);
        return m == null ? null : new RecipeChoice.MaterialChoice(m);
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
