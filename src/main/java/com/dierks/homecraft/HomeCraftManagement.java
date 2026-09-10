package com.dierks.homecraft;

import com.dierks.homecraft.block.CustomBlockListener;
import com.dierks.homecraft.block.CustomBlockService;
import com.dierks.homecraft.command.HcmCommand;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.crafting.RecipeManager;
import com.dierks.homecraft.crafting.WorkbenchListener;
import com.dierks.homecraft.gui.MenuListener;
import com.dierks.homecraft.input.ChatPromptService;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.integration.HeadLibraryService;
import com.dierks.homecraft.integration.ProtectionService;
import com.dierks.homecraft.item.CustomItems;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.order.OrderDeliveryListener;
import com.dierks.homecraft.order.OrderService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DailySellDao;
import com.dierks.homecraft.storage.MarketStateDao;
import com.dierks.homecraft.storage.MiniAuctionDao;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.storage.MiniInboxDao;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.storage.OrderDao;
import com.dierks.homecraft.storage.PlacedBlockDao;
import com.dierks.homecraft.storage.PlacedNaturalDao;
import com.dierks.homecraft.storage.PriceHistoryDao;
import com.dierks.homecraft.trade.AuctionService;
import com.dierks.homecraft.trade.InboxListener;
import com.dierks.homecraft.trade.VendingService;
import com.dierks.homecraft.trade.WildDropListener;
import com.dierks.homecraft.trade.WildDropService;
import com.dierks.homecraft.util.Keys;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;

/**
 * HomeCraft Management.
 *
 * <p>Project skeleton + SQLite datastore, the Mini Workbench and PC custom blocks,
 * the finite-stock commodities market, and the PC-gated Amazon Store with
 * real-time shipping. Minis (Phase 4) and the web dashboard (Phase 5) are stubs
 * where they connect.
 */
public final class HomeCraftManagement extends JavaPlugin {

    /**
     * The config schema revision this build ships. {@code migrateConfig} runs every
     * revision step an on-disk file is below, in order, and stamps this value in.
     * Kept in sync with {@code config_revision} in the bundled config.yml — the two are
     * pinned together by a test, because a fresh install whose file says less than this
     * would migrate itself on its very first boot.
     *
     * <p>3 = the pass-3 economy rebalance. 4 = the two per-item daily caps pass 3 missed.
     * 5 = the Materials department the Store/Market tabs sort into.
     */
    static final int CONFIG_REVISION = 5;

    /**
     * Per-item daily caps (~2% sell / ~4% buy of {@code full_stock}), mirroring the
     * {@code market.catalog} the plugin ships. Single source of truth on purpose: pass 3
     * kept its own list inline and it drifted — it carried four of these six, so every
     * server that upgraded through it was left with cobblestone and diamond uncapped,
     * contradicting the shipped defaults. Revision 4 repairs that. Add a row here and to
     * the bundled config.yml together.
     */
    private static final java.util.Map<String, int[]> DAILY_CAPS = java.util.Map.of(
            "cobblestone", new int[] {400, 800},
            "oak_log", new int[] {160, 320},
            "wheat", new int[] {120, 240},
            "iron_ingot", new int[] {40, 80},
            "gold_ingot", new int[] {20, 40},
            "diamond", new int[] {10, 20});

    private PluginConfig config;
    private Database database;
    private CustomItems items;
    private CustomBlockService blockService;
    private RecipeManager recipeManager;
    private ProtectionService protection;
    private EconomyService economy;
    private MarketService market;
    private OrderService orderService;
    private MiniService miniService;
    private com.dierks.homecraft.mini.CardService cardService;
    private com.dierks.homecraft.mini.PrinterService printerService;
    private com.dierks.homecraft.mini.PackService packService;
    private com.dierks.homecraft.mini.MiniValue miniValue;
    private com.dierks.homecraft.mini.BinderService binderService;
    private ChatPromptService chatPrompts;
    private com.dierks.homecraft.gui.BrowseState browseState;
    private HeadLibraryService headLibrary;
    private VendingService vending;
    private AuctionService auctions;
    private WildDropService wildDrops;
    private com.dierks.homecraft.mini.AnnounceService announce;
    private com.dierks.homecraft.integration.EconomySandbox sandbox;
    private com.dierks.homecraft.storage.BackupService backups;
    private com.dierks.homecraft.trade.ShopDisplayService shops;
    private com.dierks.homecraft.trade.PlacedMiniService placedMinis;
    private com.dierks.homecraft.effects.MiniEffectsService effects;
    private com.dierks.homecraft.trade.NaturalSpawnService naturalSpawns;
    private com.dierks.homecraft.trade.StandService stands;
    private com.dierks.homecraft.marketplace.DeliveryService deliveries;
    private com.dierks.homecraft.marketplace.PalletService pallets;
    private com.dierks.homecraft.web.MarketDashboardServer dashboard;
    private com.dierks.homecraft.integration.HcmPlaceholders placeholders;
    private com.dierks.homecraft.display.DisplayService displayService;
    private com.dierks.homecraft.arcade.ArcadeService arcade;
    private com.dierks.homecraft.arcade.AchievementService achievements;
    private com.dierks.homecraft.arcade.QuestService quests;
    /** Set once a pre-migration copy of config.yml is taken this start / {@code /hcm reload}. */
    private boolean configSnapshotTaken;
    private BukkitTask historyTask;
    private BukkitTask deliveryTask;
    private BukkitTask auctionTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (migrateConfig()) {
            backfillConfig();
        }
        Keys.init(this);

        this.config = new PluginConfig(this);
        this.config.load();
        this.sandbox = new com.dierks.homecraft.integration.EconomySandbox(this); // §11 #1 — needed before any service

        this.database = new Database(this);
        try {
            database.connect();
        } catch (SQLException e) {
            getLogger().severe("Could not initialise the database — disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.backups = new com.dierks.homecraft.storage.BackupService(this, database); // §11 #6
        this.backups.start();
        PlacedBlockDao placedBlockDao = new PlacedBlockDao(database);
        this.protection = new ProtectionService(this);
        this.items = new CustomItems(config);
        this.blockService = new CustomBlockService(this, placedBlockDao);
        this.recipeManager = new RecipeManager(this, config, items);
        this.recipeManager.registerRecipes();

        // Finite-stock market engine (Phase 2.5).
        this.economy = new EconomyService(this);
        this.market = new MarketService(this, new MarketStateDao(database),
                new DailySellDao(database), new com.dierks.homecraft.storage.DailyBuyDao(database),
                new PriceHistoryDao(database), economy);
        this.market.reload();
        scheduleHistorySnapshots();

        // Crate ordering + shipping (Phase 3).
        this.orderService = new OrderService(this, new OrderDao(database), market, economy);
        scheduleDeliveries();

        // Minis collectibles (Phase 4).
        this.miniService = new MiniService(this, new MiniDao(database), economy);
        this.miniService.reload();

        // Card & Printer collectible economy (Phase 9): Cards are the acquisition
        // token; the Printer is the single mint source (graded, cap-aware).
        com.dierks.homecraft.storage.CardDao cardDao = new com.dierks.homecraft.storage.CardDao(database);
        this.cardService = new com.dierks.homecraft.mini.CardService(this, cardDao);
        this.printerService = new com.dierks.homecraft.mini.PrinterService(this, cardDao);
        this.packService = new com.dierks.homecraft.mini.PackService(this);
        this.miniValue = new com.dierks.homecraft.mini.MiniValue(this);
        this.binderService = new com.dierks.homecraft.mini.BinderService(
                this, new com.dierks.homecraft.storage.BinderDao(database));

        // Admin Studio chat-input bridge + web head-library (Phase 4b).
        this.chatPrompts = new ChatPromptService(this);
        this.browseState = new com.dierks.homecraft.gui.BrowseState(this);
        this.headLibrary = new HeadLibraryService(this);

        // Minis trading + drops (Phase 4c).
        this.vending = new VendingService(this, new MiniListingDao(database),
                new com.dierks.homecraft.storage.MiniVendingDao(database), economy);
        MiniInboxDao inbox = new MiniInboxDao(database);
        this.auctions = new AuctionService(this, new MiniAuctionDao(database), inbox, economy);
        this.wildDrops = new WildDropService(this);
        this.stands = new com.dierks.homecraft.trade.StandService(this);
        // Mini presentation (Phase 12): announcements, world effects, natural spawns.
        this.announce = new com.dierks.homecraft.mini.AnnounceService(this);
        this.effects = new com.dierks.homecraft.effects.MiniEffectsService(this);
        this.naturalSpawns = new com.dierks.homecraft.trade.NaturalSpawnService(
                this, new com.dierks.homecraft.storage.MiniSpawnDao(database));
        this.shops = new com.dierks.homecraft.trade.ShopDisplayService(this);
        this.placedMinis = new com.dierks.homecraft.trade.PlacedMiniService(
                this, new com.dierks.homecraft.storage.PlacedMiniDao(database));
        PlacedNaturalDao placedNatural = new PlacedNaturalDao(database);
        scheduleAuctionClose();

        // Crate Marketplace + Mailbox deliveries (Phase 5).
        this.deliveries = new com.dierks.homecraft.marketplace.DeliveryService(
                this, new com.dierks.homecraft.storage.DeliveryDao(database));
        this.pallets = new com.dierks.homecraft.marketplace.PalletService(
                this, new com.dierks.homecraft.storage.PalletDao(database), economy, deliveries,
                new com.dierks.homecraft.marketplace.Categorizer(this));

        // In-Game Economy Displays (Phase 7) — sign boards, holograms, map-TVs.
        this.displayService = new com.dierks.homecraft.display.DisplayService(
                this, new com.dierks.homecraft.storage.DisplayDao(database));

        // The Arcade (Phase 8) — tokens, loot crates, pity, lotto.
        this.arcade = new com.dierks.homecraft.arcade.ArcadeService(
                this, new com.dierks.homecraft.storage.TokenDao(database));
        this.achievements = new com.dierks.homecraft.arcade.AchievementService(
                this, new com.dierks.homecraft.storage.AchievementDao(database));
        // Daily/weekly quests (Phase 11) — repeatable token objectives; no listener,
        // progress is recorded from the existing market/printer/crate/pack hooks.
        this.quests = new com.dierks.homecraft.arcade.QuestService(
                this, new com.dierks.homecraft.storage.QuestDao(database));

        getServer().getPluginManager().registerEvents(
                new CustomBlockListener(this, config, blockService, items, protection), this);
        getServer().getPluginManager().registerEvents(new WorkbenchListener(this, recipeManager), this);
        getServer().getPluginManager().registerEvents(
                new com.dierks.homecraft.crafting.RecipeBookListener(recipeManager), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(browseState, this);
        getServer().getPluginManager().registerEvents(new OrderDeliveryListener(orderService), this);
        getServer().getPluginManager().registerEvents(chatPrompts, this);
        getServer().getPluginManager().registerEvents(new InboxListener(this), this);
        getServer().getPluginManager().registerEvents(new WildDropListener(this, wildDrops, placedNatural), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.MiniInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.PackItemListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.BinderItemListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.MiniHeadListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.ArmorStandListener(this, stands), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.mini.MiniDestructionListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.display.DisplayListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.arcade.ArcadeListener(this), this);
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getPluginManager().registerEvents(shops, this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.mini.MiniRenderListener(this), this);

        PluginCommand hcm = getCommand("hcm");
        if (hcm != null) {
            HcmCommand executor = new HcmCommand(this);
            hcm.setExecutor(executor);
            hcm.setTabCompleter(executor);
        }

        // Market Web Dashboard (Phase 6) — embedded read-only web server.
        this.dashboard = new com.dierks.homecraft.web.MarketDashboardServer(this);
        this.dashboard.start();

        // Start the economy-display refresh timer (renders signs + spawns holograms).
        this.displayService.start();
        this.arcade.start();

        // Item-builder self-test on the live API (Card lore/PDC + Legendary glint).
        com.dierks.homecraft.mini.ItemSelfTest.run(this);

        // Once the worlds are up: rebuild placed-Mini effects, natural spawns, placed
        // heads and shop displays from the datastore (nothing leaks after a crash),
        // put pedestal skins back on Display Cases, and re-skin Pallets.
        getServer().getScheduler().runTask(this, () -> {
            int cases = blockService.reskinDisplayCases();
            if (cases > 0) {
                getLogger().info("Display Cases: re-applied the pedestal skin on " + cases + " case(s).");
            }
            effects.rebuild();
            naturalSpawns.rebuild();
            naturalSpawns.start();
            placedMinis.rebuild();
            placedMinis.start();
            shops.rebuild();
            for (com.dierks.homecraft.storage.PlacedBlock pb : blockService.findByType(com.dierks.homecraft.block.CustomBlockType.PALLET)) {
                org.bukkit.World w = getServer().getWorld(pb.world());
                if (w != null && w.isChunkLoaded(pb.x() >> 4, pb.z() >> 4)) {
                    pallets.refreshSkin(new org.bukkit.Location(w, pb.x(), pb.y(), pb.z()));
                }
            }
        });

        // In-Game Economy Displays (Phase 7): the PlaceholderAPI 'hcm' expansion —
        // only loaded/registered when PlaceholderAPI is installed.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                this.placeholders = new com.dierks.homecraft.integration.HcmPlaceholders(this);
                if (this.placeholders.register()) {
                    getLogger().info("Registered PlaceholderAPI expansion 'hcm'.");
                }
            } catch (Throwable t) {
                getLogger().warning("Could not register the PlaceholderAPI expansion: " + t.getMessage());
                this.placeholders = null;
            }
        }

        getLogger().info("HomeCraft Management enabled.");
    }

    @Override
    public void onDisable() {
        if (shops != null) {
            shops.stop();
            shops = null;
        }
        if (placedMinis != null) {
            placedMinis.stop();
            placedMinis = null;
        }
        if (backups != null) {
            backups.stop();
            backups = null;
        }
        if (naturalSpawns != null) {
            naturalSpawns.stop();
            naturalSpawns = null;
        }
        if (effects != null) {
            effects.stop(); // removes every hologram/display/light we own
            effects = null;
        }
        if (arcade != null) {
            arcade.stop();
            arcade = null;
        }
        if (displayService != null) {
            displayService.stop();
            displayService = null;
        }
        if (placeholders != null) {
            try {
                placeholders.unregister();
            } catch (Throwable ignored) {
                // PAPI may already be gone; nothing to clean up.
            }
            placeholders = null;
        }
        if (dashboard != null) {
            dashboard.stop();
            dashboard = null;
        }
        if (historyTask != null) {
            historyTask.cancel();
            historyTask = null;
        }
        if (deliveryTask != null) {
            deliveryTask.cancel();
            deliveryTask = null;
        }
        if (auctionTask != null) {
            auctionTask.cancel();
            auctionTask = null;
        }
        if (recipeManager != null) {
            recipeManager.unregisterRecipes();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("HomeCraft Management disabled.");
    }

    /** Reload config.yml, re-register data-driven recipes, and reload the market catalog live. */
    public void reloadAll() {
        reloadConfig();
        configSnapshotTaken = false;
        if (migrateConfig()) {
            backfillConfig();
        }
        config.load();
        recipeManager.registerRecipes();
        market.reload();
        scheduleHistorySnapshots();
        miniService.reload();
        if (wildDrops != null) {
            wildDrops.invalidate();
        }
        if (effects != null) {
            effects.start(); // re-arm at the new cadence; the registry is kept
        }
        if (naturalSpawns != null) {
            naturalSpawns.start(); // re-arm the spawn/expiry timers under the new config
        }
        if (backups != null) {
            backups.start();
        }
        if (shops != null) {
            shops.start();
        }
        if (dashboard != null) {
            dashboard.restart(); // pick up bind/port/enabled/refresh/title changes
        }
        if (displayService != null) {
            displayService.start(); // re-arm the refresh timer at the new cadence
        }
        if (arcade != null) {
            arcade.start(); // re-arm the playtime task under any new config
        }
    }

    /**
     * Targeted, one-time config upgrades that a blind key-backfill can't do (it only
     * ADDS missing keys, never rewrites changed ones). Runs before {@link #backfillConfig()}.
     *
     * <p>Phase 6: the legacy shipping scheme had exactly three whole-hour tiers
     * (one_day/two_day/three_day at 24/48/72h). The new scheme adds an {@code express}
     * sub-hour tier and retimes the rest. When the on-disk config predates {@code express},
     * we replace the whole {@code shipping.tiers} map with the new four-tier scheme
     * (mode is preserved; admins who already added {@code express} are left untouched).
     *
     * <p><b>This reads the FILE, never {@link #getConfig()}.</b> {@code reloadConfig()}
     * attaches the jar's bundled config.yml to {@code getConfig()} as DEFAULTS, and
     * {@code ConfigurationSection.contains(path)} answers out of those defaults — so every
     * "is this key on disk?" question asked of {@code getConfig()} came back {@code true}
     * for anything the jar ships, and this method could never fire (0.21.0 shipped
     * {@code worlds:}/{@code backups:}/{@code shops:} that no server ever received). It
     * therefore loads {@code plugins/HomeCraftManagement/config.yml} into a defaults-free
     * {@link org.bukkit.configuration.file.YamlConfiguration}, decides and writes against
     * that, saves it, and only then calls {@code reloadConfig()} so the live view catches up.
     *
     * @return false only when the file could not be read or the upgrade could not be
     *         persisted — the caller must then skip {@link #backfillConfig()} too, because
     *         backfilling an unmigrated file writes leaves (e.g. {@code shipping.tiers.express.*})
     *         that make the migration's own guard see the new shape and never fire again.
     */
    private boolean migrateConfig() {
        java.io.File file = configFile();
        org.bukkit.configuration.file.YamlConfiguration onDisk = loadOnDisk(file);
        if (onDisk == null) {
            return false;
        }
        String mainWorld = getServer().getWorlds().isEmpty()
                ? "world" : getServer().getWorlds().get(0).getName();

        java.util.List<String> log = migrateConfig(onDisk, mainWorld);
        if (log.isEmpty()) {
            return true;
        }
        // These upgrades rewrite whole sections (shipping.tiers is replaced outright), and
        // because the defaults bug kept them dormant they may all fire at once on the first
        // start after this build. Keep the admin a copy of what they had, next to the
        // database's own pre-migration snapshots.
        snapshotConfig(file);
        if (!saveTo(onDisk, file, "migrated")) {
            getLogger().severe("Skipping the config backfill as well, so config.yml is not left "
                    + "half-upgraded. Fix whatever stopped the write and restart.");
            return false;
        }
        for (String line : log) {
            getLogger().info(line);
        }
        reloadConfig();
        return true;
    }

    /**
     * The migration itself, run against a config that carries NO defaults — every check
     * below is a question about what is physically in the admin's file.
     *
     * <p>Returns one log line per upgrade applied; an empty list means nothing changed and
     * the caller need not write the file. Package-private so it can be unit-tested with a
     * plain {@link org.bukkit.configuration.file.YamlConfiguration} and no server.
     *
     * @param c         the on-disk config.yml, loaded WITHOUT defaults attached
     * @param mainWorld the server's main world, seeded into {@code worlds.economy_enabled}
     */
    static java.util.List<String> migrateConfig(
            org.bukkit.configuration.file.FileConfiguration c, String mainWorld) {
        java.util.List<String> log = new java.util.ArrayList<>();

        if (has(c, "shipping.tiers") && !has(c, "shipping.tiers.express")) {
            c.set("shipping.tiers", null);
            c.set("shipping.tiers.express.real_minutes", 5);
            c.set("shipping.tiers.express.percent", 20);
            c.set("shipping.tiers.one_day.real_hours", 1);
            c.set("shipping.tiers.one_day.percent", 10);
            c.set("shipping.tiers.two_day.real_hours", 3);
            c.set("shipping.tiers.two_day.percent", 5);
            c.set("shipping.tiers.three_day.real_hours", 8);
            c.set("shipping.tiers.three_day.percent", 0);
            if (!has(c, "shipping.locker.enabled")) {
                c.set("shipping.locker.enabled", true);
            }
            log.add("Config migration: upgraded shipping to the Express tier scheme "
                    + "(express 5m/20% · one_day 1h/10% · two_day 3h/5% · three_day 8h/free).");
        }

        // Skins & recipes rework: two-state Pallet, two-tall Vending Machine, Mailbox
        // colour variants, and the PC recipe moving into the shared `recipes:` section.
        // Each step only fires when the on-disk file still has the old shape, so an
        // admin's custom values are carried over rather than dropped.
        if (has(c, "skins.pallet") && !has(c, "skins.pallet_empty")) {
            c.set("skins.pallet_empty", str(c, "skins.pallet"));
            c.set("skins.pallet", null);
            log.add("Config migration: skins.pallet → skins.pallet_empty (pallet_used added from defaults).");
        }
        if (has(c, "skins.vending") && !has(c, "skins.vending_lower")) {
            c.set("skins.vending_lower", str(c, "skins.vending"));
            c.set("skins.vending", null);
            log.add("Config migration: skins.vending → skins.vending_lower (vending_upper added from defaults).");
        }
        if (has(c, "skins.mailbox") && !hasSection(c, "skins.mailbox")) {
            String legacy = str(c, "skins.mailbox");
            c.set("skins.mailbox", null);
            if (!legacy.isBlank()) {
                c.set("skins.mailbox.wood", legacy);
            }
            log.add("Config migration: skins.mailbox is now a per-variant map (old value kept as wood).");
        }
        if (has(c, "crafting.pc.recipe") && !has(c, "recipes.pc")) {
            c.set("crafting.pc.recipe", null);
            log.add("Config migration: the PC recipe now lives under recipes.pc (added from defaults) "
                    + "alongside every other craftable block; the old crafting.pc.recipe was dropped.");
        }
        // Economy world sandbox: default to the server's main world on first run, written
        // into the file so the admin can see (and extend) it.
        if (!has(c, "worlds.economy_enabled")) {
            c.set("worlds.economy_enabled", java.util.List.of(mainWorld));
            log.add("Config migration: worlds.economy_enabled = [" + mainWorld + "] (the economy runs only there).");
        }

        // Display Case skins became a per-style map (plain / royal).
        if (has(c, "skins.display_case") && !hasSection(c, "skins.display_case")) {
            String legacy = str(c, "skins.display_case");
            c.set("skins.display_case", null);
            if (!legacy.isBlank()) {
                c.set("skins.display_case.plain", legacy);
            }
            log.add("Config migration: skins.display_case is now a per-style map (old value kept as plain).");
        }

        // Revision steps, applied once to a live file and tracked by config_revision. Each is
        // gated on the revision the file arrived with, so a server already past one is not
        // dragged back through it — that matters because the pass-3 block below rewrites
        // whole sections and would undo anything the admin has tuned since.
        int from = revision(c);
        if (from < 3) {
            applyEconomyRebalance(c);
            log.add("Config migration: economy rebalance applied (daily caps, $5k limits, 8% commission, "
                    + "token-only crates, pity 25, packs, printer fees).");
        }
        if (from < 4) {
            java.util.List<String> filled = fillMissingDailyCaps(c);
            if (!filled.isEmpty()) {
                log.add("Config migration: per-item daily caps filled in for " + String.join(", ", filled)
                        + " — pass 3 capped only four of the six catalog rows.");
            }
        }
        if (from < 5) {
            if (addDepartment(c, "Materials", "Blocks")) {
                log.add("Config migration: added the Materials department (ingots, gems and "
                        + "crafting stock) — it is a Store/Market tab, so an existing "
                        + "departments list has to gain it or those items fall into Misc.");
            }
        }
        if (from < CONFIG_REVISION) {
            c.set("config_revision", CONFIG_REVISION);
            log.add("Config migration: config_revision " + from + " → " + CONFIG_REVISION + ".");
        }

        if (has(c, "skins.pc") && has(c, "crafting.pc.head_texture")) {
            String skin = str(c, "skins.pc");
            String legacy = str(c, "crafting.pc.head_texture");
            if (!skin.isBlank() && legacy.isBlank()) {
                c.set("crafting.pc.head_texture", skin);
                log.add("Config migration: crafting.pc.head_texture seeded from skins.pc.");
            }
        }

        return log;
    }

    /**
     * The pass-3 economy numbers, written onto the live config so an upgraded server
     * gets them too (the blind backfill only adds missing keys). Every value here is
     * mirrored by the bundled config.yml defaults, so a key that is absent from the
     * admin's file is simply left to {@link #backfillConfig()}, which runs next.
     */
    private static void applyEconomyRebalance(org.bukkit.configuration.file.FileConfiguration c) {
        // Per-item daily caps (~2% sell / ~4% buy of full_stock), from DAILY_CAPS.
        java.util.List<java.util.Map<?, ?>> catalog = mapList(c, "market.catalog");
        java.util.List<java.util.Map<String, Object>> rewritten = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> row : catalog) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> e : row.entrySet()) {
                m.put(String.valueOf(e.getKey()), e.getValue());
            }
            int[] cap = DAILY_CAPS.get(String.valueOf(m.get("id")));
            if (cap != null) {
                m.put("max_daily_sell", cap[0]);
                m.put("max_daily_buy", cap[1]);
            }
            rewritten.add(m);
        }
        if (!rewritten.isEmpty()) {
            c.set("market.catalog", rewritten);
        }
        c.set("market.sell_limits.max_money_per_day", 5000);
        c.set("market.buy_limits.max_money_per_day", 5000);
        for (String side : new String[] {"sell_limits", "buy_limits"}) {
            java.util.List<java.util.Map<?, ?>> ranks = mapList(c, "market." + side + ".ranks");
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            for (java.util.Map<?, ?> row : ranks) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> e : row.entrySet()) {
                    m.put(String.valueOf(e.getKey()), e.getValue());
                }
                if ("hcm.market.limit.vip".equals(String.valueOf(m.get("permission")))) {
                    m.put("max_money_per_day", 12500);
                }
                out.add(m);
            }
            if (!out.isEmpty()) {
                c.set("market." + side + ".ranks", out);
            }
        }
        c.set("marketplace.fee.commission_percent", 8.0);

        // Tokens never become money: the starter crate is rewritten to card/filament/pack/mini.
        c.set("arcade.crates.starter.rewards", java.util.List.of(
                map("type", "card", "tag", "starter", "weight", 40),
                map("type", "filament", "amount", 3, "weight", 30),
                map("type", "pack", "pack", "starter", "weight", 20),
                map("type", "mini", "tag", "starter", "weight", 10)));
        c.set("arcade.crates.starter.paid_odds", java.util.List.of(map("cost_money", 750, "floor", "RARE")));
        c.set("arcade.pity.tokens", 25);

        // Packs: starter 250 (no Legendary), premium 750.
        c.set("packs", java.util.List.of(
                map("id", "starter", "display", "Starter Pack", "price", 250.0, "count", 3,
                        "pool", java.util.List.of(map("card", "piggy_mini", "weight", 50), map("card", "chick_mini", "weight", 50))),
                map("id", "premium", "display", "Premium Pack", "price", 750.0, "count", 3,
                        "pool", java.util.List.of(map("card", "piggy_mini", "weight", 45), map("card", "chick_mini", "weight", 45),
                                map("card", "golden_idol", "weight", 1)))));

        // Printer: public printers charge money, private ones consume filament.
        c.set("printer.fee", 0);
        c.set("printer.public_fee", 150);
    }

    /**
     * Revision 4: give every catalog row the daily caps {@link #DAILY_CAPS} says it should
     * have, but only where the row does not already carry one. Pass 3's inline caps list
     * held four of the six shipped rows, so cobblestone and diamond came out of it uncapped
     * on every upgraded server — diamond most importantly, where the shipped comment is
     * "rare — tight cap protects supply ... prevents hoarding".
     *
     * <p>Deliberately narrow: it fills gaps and changes nothing else, so bumping the
     * revision does not re-run {@link #applyEconomyRebalance} over an admin's tuned file.
     * A cap the admin has set themselves — including a deliberate 0 — is left alone.
     *
     * @return the {@code <id>.<key>} paths filled in, empty when there was nothing to do
     */
    private static java.util.List<String> fillMissingDailyCaps(
            org.bukkit.configuration.file.FileConfiguration c) {
        java.util.List<java.util.Map<?, ?>> catalog = mapList(c, "market.catalog");
        if (catalog.isEmpty()) {
            return java.util.List.of(); // no catalog on disk — the backfill supplies the shipped one
        }
        java.util.List<String> filled = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> rewritten = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> row : catalog) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> e : row.entrySet()) {
                m.put(String.valueOf(e.getKey()), e.getValue());
            }
            String id = String.valueOf(m.get("id"));
            int[] cap = DAILY_CAPS.get(id);
            if (cap != null) {
                if (!(m.get("max_daily_sell") instanceof Number)) {
                    m.put("max_daily_sell", cap[0]);
                    filled.add(id + ".max_daily_sell");
                }
                if (!(m.get("max_daily_buy") instanceof Number)) {
                    m.put("max_daily_buy", cap[1]);
                    filled.add(id + ".max_daily_buy");
                }
            }
            rewritten.add(m);
        }
        if (!filled.isEmpty()) {
            c.set("market.catalog", rewritten);
        }
        return filled;
    }

    /**
     * Revision 5: insert a department into {@code marketplace.departments} if the admin's
     * list lacks it. The blind backfill cannot do this — it only adds keys that are
     * missing entirely, and every server already has a departments list — so a department
     * added to the shipped defaults would never reach an existing file, and everything the
     * classifier routed to it would land in Misc instead.
     *
     * <p>Inserted after {@code after} so the tab order stays sensible, and never at the
     * end: the LAST entry is the catch-all the classifier falls back to.
     *
     * @return false when the list already has it, or has no list to edit
     */
    private static boolean addDepartment(org.bukkit.configuration.file.FileConfiguration c,
                                         String department, String after) {
        if (!(c.get("marketplace.departments", null) instanceof java.util.List<?> raw) || raw.isEmpty()) {
            return false; // absent — the backfill supplies the shipped list, Materials included
        }
        java.util.List<String> departments = new java.util.ArrayList<>();
        for (Object o : raw) {
            departments.add(String.valueOf(o));
        }
        for (String d : departments) {
            if (d.equalsIgnoreCase(department)) {
                return false;
            }
        }
        int at = departments.size() - 1; // before the catch-all
        for (int i = 0; i < departments.size(); i++) {
            if (departments.get(i).equalsIgnoreCase(after)) {
                at = i + 1;
                break;
            }
        }
        departments.add(Math.min(at, departments.size()), department);
        c.set("marketplace.departments", departments);
        return true;
    }

    private static java.util.Map<String, Object> map(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /**
     * Deep-merge any keys present in the bundled default config.yml but missing from
     * the admin's on-disk config.yml, then save. Bukkit's {@code saveDefaultConfig()}
     * only writes the file when it's absent — it never adds newly-introduced keys to
     * an existing file — so a server that upgrades across a version which adds a
     * section (e.g. {@code minis:}) would otherwise silently run without it.
     *
     * <p>Only missing keys are added; every existing value the admin has edited is
     * preserved untouched. Nested sections are reconstructed leaf-by-leaf and lists
     * are copied whole. Added keys carry their default comments across where the API
     * supports it. What was added is logged.
     *
     * <p>Like {@link #migrateConfig()} this compares against the FILE, not
     * {@link #getConfig()}: the jar's defaults are attached to {@code getConfig()}, so
     * {@code contains()} there is true for every key the jar ships and the merge below
     * had nothing left to add — it was a no-op for the entire life of the plugin.
     */
    private void backfillConfig() {
        java.io.InputStream in = getResource("config.yml");
        if (in == null) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration defaults;
        try (java.io.Reader reader =
                     new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException e) {
            getLogger().warning("Could not read bundled default config for backfill: " + e.getMessage());
            return;
        }

        java.io.File file = configFile();
        org.bukkit.configuration.file.YamlConfiguration onDisk = loadOnDisk(file);
        if (onDisk == null) {
            return;
        }

        java.util.List<String> added = backfillConfig(onDisk, defaults);
        if (added.isEmpty()) {
            return;
        }
        // The bigger of the two rewrites, and the one that has never run: it can add
        // hundreds of keys and reflows the whole file through Bukkit's YAML emitter.
        snapshotConfig(file);
        if (!saveTo(onDisk, file, "backfilled")) {
            return;
        }
        getLogger().info("Config backfill: added " + added.size()
                + " missing key(s) from defaults: " + String.join(", ", added));
        reloadConfig();
    }

    /**
     * Copy every leaf the bundled resource ships that is missing from {@code current},
     * carrying its comments across, and return the keys added in file order.
     * Package-private so it can be unit-tested without a server.
     *
     * <p>This is a LEAF merge, not a full deep merge: a default that is an empty map
     * (there are a handful, e.g. {@code marketplace.category_overrides}) is a section with no
     * leaves and is deliberately never created, since an absent section and an empty one mean
     * the same thing to every reader. Do not "fix" that — it would stamp empty blocks into
     * every admin's file.
     *
     * @param current  the on-disk config.yml, loaded WITHOUT defaults attached
     * @param defaults the bundled config.yml resource
     */
    static java.util.List<String> backfillConfig(
            org.bukkit.configuration.file.FileConfiguration current,
            org.bukkit.configuration.file.FileConfiguration defaults) {
        java.util.List<String> added = new java.util.ArrayList<>();
        java.util.Set<String> touchedSections = new java.util.LinkedHashSet<>();
        for (String key : defaults.getKeys(true)) {
            // Skip section nodes themselves; their leaves are handled individually so
            // a partially-customised section keeps the admin's values and only gains
            // whatever leaves are missing.
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (has(current, key)) {
                continue;
            }
            if ("config_revision".equals(key)) {
                // The plugin's own bookkeeping, not a setting: it is the gate that says the
                // rebalance has been applied, so only the migration that actually applies it
                // may write it. Backfilling it blind could satisfy the gate for work never done.
                continue;
            }
            Object value = defaults.get(key);
            if (value == null) {
                continue; // a bare `key:` in the defaults — set(key, null) would only delete
            }
            current.set(key, value);
            copyComments(defaults, current, key);
            added.add(key);
            ancestorsOf(key, touchedSections);
        }

        // A section that exists only because we just wrote leaves into it has no header
        // comment of its own — give it the explanatory block the bundled file ships with,
        // so a freshly-backfilled `worlds:` / `backups:` / `shops:` reads like the rest of
        // the file. A section the admin already commented is never overwritten.
        for (String section : touchedSections) {
            if (hasSection(current, section) && commentsOf(current, section).isEmpty()) {
                copyComments(defaults, current, section);
            }
        }
        return added;
    }

    /**
     * Collect every parent path of {@code key} ("a.b.c" → "a", "a.b") into {@code out}.
     * Assumes the default '.' path separator, as every literal path in this class does.
     */
    private static void ancestorsOf(String key, java.util.Set<String> out) {
        int dot = key.indexOf('.');
        while (dot >= 0) {
            out.add(key.substring(0, dot));
            dot = key.indexOf('.', dot + 1);
        }
    }

    /** Carry a key's block + inline comments from the bundled defaults onto the live file. */
    private static void copyComments(org.bukkit.configuration.ConfigurationSection from,
                                     org.bukkit.configuration.ConfigurationSection to, String key) {
        try {
            to.setComments(key, from.getComments(key));
            to.setInlineComments(key, from.getInlineComments(key));
        } catch (Throwable ignored) {
            // Comment API unavailable on this server — values still merge fine.
        }
    }

    /** A key's block comments, or an empty list on a server that predates the comment API. */
    private static java.util.List<String> commentsOf(
            org.bukkit.configuration.ConfigurationSection c, String key) {
        try {
            return c.getComments(key);
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
    }

    /** The admin's own config.yml inside the plugin's data folder. */
    private java.io.File configFile() {
        return new java.io.File(getDataFolder(), "config.yml");
    }

    /**
     * The admin's config.yml as a defaults-free view, or {@code null} when it cannot be
     * parsed. A file with a YAML error must NOT come back as an empty config: migration
     * and backfill would then believe every key is missing and rewrite the whole thing on
     * top of a typo the admin could otherwise just correct.
     * {@link org.bukkit.configuration.file.YamlConfiguration#loadConfiguration(java.io.File)}
     * does exactly that — it swallows the parse error and hands back an empty config — so
     * this uses the throwing form and refuses to touch a file it could not read.
     */
    private org.bukkit.configuration.file.YamlConfiguration loadOnDisk(java.io.File file) {
        org.bukkit.configuration.file.YamlConfiguration onDisk =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            onDisk.load(file);
        } catch (java.io.FileNotFoundException e) {
            // No file yet (saveDefaultConfig() could not write one) — an empty view is right.
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be read (" + e.getMessage()
                    + ") — leaving it exactly as it is. Fix the YAML and restart; "
                    + "no keys were migrated or backfilled.");
            return null;
        }
        return onDisk;
    }

    /**
     * Copy config.yml into {@code backups/} before the first pass that rewrites it — at most
     * one copy per start or {@code /hcm reload}, so migrate and backfill together leave a
     * single snapshot of what the admin had.
     */
    private void snapshotConfig(java.io.File file) {
        if (configSnapshotTaken || !file.isFile()) {
            return;
        }
        java.io.File dir = new java.io.File(getDataFolder(), "backups");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            getLogger().warning("Could not create the backups folder: " + dir);
            return;
        }
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        java.io.File out = new java.io.File(dir, "config-" + stamp + "-pre-migration.yml");
        try {
            java.nio.file.Files.copy(file.toPath(), out.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            configSnapshotTaken = true;
            getLogger().info("Config backup (pre-migration): " + out.getAbsolutePath());
        } catch (java.io.IOException e) {
            getLogger().warning("Could not back up config.yml before migrating: " + e.getMessage());
        }
    }

    /** Write {@code c} to {@code file}; false (with a warning logged) if the write fails. */
    private boolean saveTo(org.bukkit.configuration.file.FileConfiguration c, java.io.File file, String what) {
        try {
            c.save(file);
            return true;
        } catch (java.io.IOException e) {
            getLogger().warning("Could not write the " + what + " config.yml: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write values into the admin's config.yml ON DISK, then refresh the live view.
     *
     * <p>Use this for anything the plugin persists at runtime, never
     * {@code getConfig().set(...)} followed by {@code saveConfig()}. {@code saveConfig()}
     * serialises the tree loaded at the last startup or {@code /hcm reload} and writes that
     * snapshot over config.yml — so it silently discards every hand edit made to the file
     * since. An admin who adds {@code market.catalog} rows while the server is up and then
     * touches anything in Admin Studio loses the lot, with nothing in the log to say so.
     *
     * <p>Reading the file first means a save only ever changes the keys asked for. A
     * {@code null} value removes its key, exactly as {@code set} does.
     *
     * @param values paths to write, applied in iteration order
     * @return false when the file could not be read or written — nothing was changed
     */
    public boolean writeConfig(java.util.Map<String, Object> values) {
        java.io.File file = configFile();
        org.bukkit.configuration.file.YamlConfiguration onDisk = loadOnDisk(file);
        if (onDisk == null) {
            return false;
        }
        for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
            onDisk.set(entry.getKey(), entry.getValue());
        }
        if (!saveTo(onDisk, file, "updated")) {
            return false;
        }
        reloadConfig();
        return true;
    }

    /**
     * The {@code market.catalog} rows exactly as they sit in config.yml, defaults-free:
     * each a MUTABLE copy, in file order, including rows {@link PluginConfig} rejects and
     * keys it does not model.
     *
     * <p>{@code null} — not an empty list — when config.yml could not be read. A caller
     * must abort on null rather than write a catalog over a file it failed to parse.
     */
    public java.util.List<java.util.Map<String, Object>> readCatalogRows() {
        org.bukkit.configuration.file.YamlConfiguration onDisk = loadOnDisk(configFile());
        if (onDisk == null) {
            return null;
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> row : mapList(onDisk, "market.catalog")) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> e : row.entrySet()) {
                m.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.add(m);
        }
        return out;
    }

    /** Single-key form of {@link #writeConfig(java.util.Map)}. */
    public boolean writeConfig(String path, Object value) {
        java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
        one.put(path, value);
        return writeConfig(one);
    }

    /**
     * {@code contains()} that can never be answered by attached defaults — the only honest
     * way to ask "is this key physically in the file?". {@code get(path, null)} skips the
     * default lookup that the single-argument {@code get(path)} performs, and with it
     * {@code contains(path)}, {@code isConfigurationSection(path)}, {@code getMapList(path)}
     * and {@code getInt(path)}, all of which route through it. (The two-argument getters —
     * {@code getString(path, def)}, {@code getInt(path, def)} — already ignore defaults.)
     *
     * <p>Only the ANSWER is guaranteed defaults-free: the final segment resolves as
     * {@code map.get(key)} falling back to the {@code null} we pass. The intermediate path
     * walk still goes through the single-argument {@code getConfigurationSection()}, which
     * MATERIALISES a real empty section for a segment that exists only in the defaults. That
     * side effect is why the callers must pass a defaults-free view rather than
     * {@code getConfig()} — this helper alone would not prevent it.
     */
    private static boolean has(org.bukkit.configuration.ConfigurationSection c, String path) {
        return c.get(path, null) != null;
    }

    /** {@code isConfigurationSection()} that can never be answered by attached defaults. */
    private static boolean hasSection(org.bukkit.configuration.ConfigurationSection c, String path) {
        return c.get(path, null) instanceof org.bukkit.configuration.ConfigurationSection;
    }

    /** {@code getString()} that never falls back to attached defaults; "" when absent. */
    private static String str(org.bukkit.configuration.ConfigurationSection c, String path) {
        Object v = c.get(path, null);
        return v == null ? "" : String.valueOf(v);
    }

    /** {@code getMapList()} that never falls back to attached defaults. */
    private static java.util.List<java.util.Map<?, ?>> mapList(
            org.bukkit.configuration.ConfigurationSection c, String path) {
        java.util.List<java.util.Map<?, ?>> out = new java.util.ArrayList<>();
        if (c.get(path, null) instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * {@code config_revision} exactly as written on disk — 0 when the key is absent, and
     * never the jar's value. (Reads the same defaults-free view as every other decision
     * here; the old {@code getInt("config_revision", 0)} happened to be defaults-immune,
     * but it was the odd one out and there is no reason to keep two rules.)
     */
    private static int revision(org.bukkit.configuration.file.FileConfiguration c) {
        return c.get("config_revision", null) instanceof Number n ? n.intValue() : 0;
    }

    /** (Re)schedule the periodic price-history snapshot task at the configured cadence. */
    private void scheduleHistorySnapshots() {
        if (historyTask != null) {
            historyTask.cancel();
            historyTask = null;
        }
        int minutes = Math.max(1, config.market().priceHistoryIntervalMinutes());
        long periodTicks = minutes * 60L * 20L;
        // First snapshot ~30s after (re)load, then every configured interval.
        historyTask = getServer().getScheduler().runTaskTimer(this, market::snapshotHistory, 20L * 30L, periodTicks);
    }

    /** Poll for due Amazon deliveries (flips in-transit orders to ready) every 20s. */
    private void scheduleDeliveries() {
        if (deliveryTask != null) {
            deliveryTask.cancel();
        }
        deliveryTask = getServer().getScheduler().runTaskTimer(this, () -> {
            orderService.tick();
            if (deliveries != null) {
                deliveries.tick();
            }
        }, 20L * 10L, 20L * 20L);
    }

    /** Close expired Mini auctions every 10s (durable — end times survive restarts). */
    private void scheduleAuctionClose() {
        if (auctionTask != null) {
            auctionTask.cancel();
        }
        auctionTask = getServer().getScheduler().runTaskTimer(this, auctions::closeDue, 20L * 10L, 20L * 10L);
    }

    public PluginConfig config() {
        return config;
    }

    public CustomItems items() {
        return items;
    }

    public CustomBlockService blockService() {
        return blockService;
    }

    public RecipeManager recipeManager() {
        return recipeManager;
    }

    public ProtectionService protection() {
        return protection;
    }

    public EconomyService economy() {
        return economy;
    }

    public MarketService market() {
        return market;
    }

    public OrderService orderService() {
        return orderService;
    }

    public MiniService miniService() {
        return miniService;
    }

    public com.dierks.homecraft.mini.CardService cards() {
        return cardService;
    }

    public com.dierks.homecraft.mini.PrinterService printers() {
        return printerService;
    }

    public com.dierks.homecraft.mini.PackService packs() {
        return packService;
    }

    public com.dierks.homecraft.mini.MiniValue values() {
        return miniValue;
    }

    public com.dierks.homecraft.mini.BinderService binder() {
        return binderService;
    }

    /** Per-player, per-session browsing state for the Store, Market and Museum menus. */
    public com.dierks.homecraft.gui.BrowseState browseState() {
        return browseState;
    }

    public ChatPromptService chatPrompts() {
        return chatPrompts;
    }

    public HeadLibraryService heads() {
        return headLibrary;
    }

    public VendingService vending() {
        return vending;
    }

    public AuctionService auctions() {
        return auctions;
    }

    /** The per-world economy sandbox (§11 #1). */
    public com.dierks.homecraft.integration.EconomySandbox sandbox() {
        return sandbox;
    }

    /** SQLite backups (§11 #6). */
    public com.dierks.homecraft.storage.BackupService backups() {
        return backups;
    }

    /** Shop displays: the vending upper half, glow, hologram and peek. */
    public com.dierks.homecraft.trade.ShopDisplayService shops() {
        return shops;
    }

    /** Mini heads placed as plain blocks (registry, look-at hologram, guaranteed drop). */
    public com.dierks.homecraft.trade.PlacedMiniService placedMinis() {
        return placedMinis;
    }

    /** Server-wide Mini announcements (found broadcasts, spawn hints). */
    public com.dierks.homecraft.mini.AnnounceService announce() {
        return announce;
    }

    /** World effects for placed Minis (holograms, particles, light, rotation). */
    public com.dierks.homecraft.effects.MiniEffectsService effects() {
        return effects;
    }

    /** Naturally spawned wild Minis (the NATURAL_SPAWN trigger). */
    public com.dierks.homecraft.trade.NaturalSpawnService naturalSpawns() {
        return naturalSpawns;
    }

    public WildDropService wildDrops() {
        return wildDrops;
    }

    public com.dierks.homecraft.trade.StandService stands() {
        return stands;
    }

    public com.dierks.homecraft.marketplace.DeliveryService deliveries() {
        return deliveries;
    }

    public com.dierks.homecraft.marketplace.PalletService pallets() {
        return pallets;
    }

    public com.dierks.homecraft.display.DisplayService displayService() {
        return displayService;
    }

    public com.dierks.homecraft.arcade.ArcadeService arcade() {
        return arcade;
    }

    public com.dierks.homecraft.arcade.AchievementService achievements() {
        return achievements;
    }

    public com.dierks.homecraft.arcade.QuestService quests() {
        return quests;
    }
}
