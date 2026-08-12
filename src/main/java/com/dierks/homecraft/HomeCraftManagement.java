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
    private ChatPromptService chatPrompts;
    private HeadLibraryService headLibrary;
    private VendingService vending;
    private AuctionService auctions;
    private WildDropService wildDrops;
    private com.dierks.homecraft.trade.StandService stands;
    private com.dierks.homecraft.marketplace.DeliveryService deliveries;
    private com.dierks.homecraft.marketplace.PalletService pallets;
    private com.dierks.homecraft.web.MarketDashboardServer dashboard;
    private com.dierks.homecraft.integration.HcmPlaceholders placeholders;
    private com.dierks.homecraft.display.DisplayService displayService;
    private com.dierks.homecraft.arcade.ArcadeService arcade;
    private BukkitTask historyTask;
    private BukkitTask deliveryTask;
    private BukkitTask auctionTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        backfillConfig();
        Keys.init(this);

        this.config = new PluginConfig(this);
        this.config.load();

        this.database = new Database(this);
        try {
            database.connect();
        } catch (SQLException e) {
            getLogger().severe("Could not initialise the database — disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PlacedBlockDao placedBlockDao = new PlacedBlockDao(database);
        this.protection = new ProtectionService(this);
        this.items = new CustomItems(config);
        this.blockService = new CustomBlockService(this, placedBlockDao);
        this.recipeManager = new RecipeManager(this, config, items);
        this.recipeManager.registerRecipes();

        // Finite-stock market engine (Phase 2.5).
        this.economy = new EconomyService(this);
        this.market = new MarketService(this, new MarketStateDao(database),
                new DailySellDao(database), new PriceHistoryDao(database), economy);
        this.market.reload();
        scheduleHistorySnapshots();

        // Crate ordering + shipping (Phase 3).
        this.orderService = new OrderService(this, new OrderDao(database), market, economy);
        scheduleDeliveries();

        // Minis collectibles (Phase 4).
        this.miniService = new MiniService(this, new MiniDao(database), economy);
        this.miniService.reload();

        // Admin Studio chat-input bridge + web head-library (Phase 4b).
        this.chatPrompts = new ChatPromptService(this);
        this.headLibrary = new HeadLibraryService(this);

        // Minis trading + drops (Phase 4c).
        this.vending = new VendingService(this, new MiniListingDao(database),
                new com.dierks.homecraft.storage.MiniVendingDao(database), economy);
        MiniInboxDao inbox = new MiniInboxDao(database);
        this.auctions = new AuctionService(this, new MiniAuctionDao(database), inbox, economy);
        this.wildDrops = new WildDropService(this);
        this.stands = new com.dierks.homecraft.trade.StandService(this);
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

        getServer().getPluginManager().registerEvents(
                new CustomBlockListener(this, config, blockService, items, protection), this);
        getServer().getPluginManager().registerEvents(new WorkbenchListener(this, recipeManager), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new OrderDeliveryListener(orderService), this);
        getServer().getPluginManager().registerEvents(chatPrompts, this);
        getServer().getPluginManager().registerEvents(new InboxListener(this), this);
        getServer().getPluginManager().registerEvents(new WildDropListener(this, wildDrops, placedNatural), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.MiniInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.MiniHeadListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.trade.ArmorStandListener(this, stands), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.display.DisplayListener(this), this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.arcade.ArcadeListener(this), this);

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
        migrateConfig();
        backfillConfig();
        config.load();
        recipeManager.registerRecipes();
        market.reload();
        scheduleHistorySnapshots();
        miniService.reload();
        if (wildDrops != null) {
            wildDrops.invalidate();
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
     */
    /**
     * Targeted, one-time config upgrades that a blind key-backfill can't do (it only
     * ADDS missing keys, never rewrites changed ones). Runs before {@link #backfillConfig()}.
     *
     * <p>Phase 6: the legacy shipping scheme had exactly three whole-hour tiers
     * (one_day/two_day/three_day at 24/48/72h). The new scheme adds an {@code express}
     * sub-hour tier and retimes the rest. When the on-disk config predates {@code express},
     * we replace the whole {@code shipping.tiers} map with the new four-tier scheme
     * (mode is preserved; admins who already added {@code express} are left untouched).
     */
    private void migrateConfig() {
        org.bukkit.configuration.file.FileConfiguration c = getConfig();
        boolean changed = false;

        if (c.contains("shipping.tiers") && !c.contains("shipping.tiers.express")) {
            c.set("shipping.tiers", null);
            c.set("shipping.tiers.express.real_minutes", 5);
            c.set("shipping.tiers.express.percent", 20);
            c.set("shipping.tiers.one_day.real_hours", 1);
            c.set("shipping.tiers.one_day.percent", 10);
            c.set("shipping.tiers.two_day.real_hours", 3);
            c.set("shipping.tiers.two_day.percent", 5);
            c.set("shipping.tiers.three_day.real_hours", 8);
            c.set("shipping.tiers.three_day.percent", 0);
            if (!c.contains("shipping.locker.enabled")) {
                c.set("shipping.locker.enabled", true);
            }
            changed = true;
            getLogger().info("Config migration: upgraded shipping to the Express tier scheme "
                    + "(express 5m/20% · one_day 1h/10% · two_day 3h/5% · three_day 8h/free).");
        }

        if (changed) {
            saveConfig();
        }
    }

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

        org.bukkit.configuration.file.FileConfiguration current = getConfig();
        java.util.List<String> added = new java.util.ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            // Skip section nodes themselves; their leaves are handled individually so
            // a partially-customised section keeps the admin's values and only gains
            // whatever leaves are missing.
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                try {
                    current.setComments(key, defaults.getComments(key));
                    current.setInlineComments(key, defaults.getInlineComments(key));
                } catch (Throwable ignored) {
                    // Comment API unavailable on this server — values still merge fine.
                }
                added.add(key);
            }
        }

        if (!added.isEmpty()) {
            saveConfig();
            getLogger().info("Config backfill: added " + added.size()
                    + " missing key(s) from defaults: " + String.join(", ", added));
        }
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
}
