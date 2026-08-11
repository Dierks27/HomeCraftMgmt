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
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.storage.OrderDao;
import com.dierks.homecraft.storage.PlacedBlockDao;
import com.dierks.homecraft.storage.PriceHistoryDao;
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
    private BukkitTask historyTask;
    private BukkitTask deliveryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        getServer().getPluginManager().registerEvents(
                new CustomBlockListener(this, config, blockService, items, protection), this);
        getServer().getPluginManager().registerEvents(new WorkbenchListener(this, recipeManager), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new OrderDeliveryListener(orderService), this);
        getServer().getPluginManager().registerEvents(chatPrompts, this);

        PluginCommand hcm = getCommand("hcm");
        if (hcm != null) {
            HcmCommand executor = new HcmCommand(this);
            hcm.setExecutor(executor);
            hcm.setTabCompleter(executor);
        }

        getLogger().info("HomeCraft Management enabled.");
    }

    @Override
    public void onDisable() {
        if (historyTask != null) {
            historyTask.cancel();
            historyTask = null;
        }
        if (deliveryTask != null) {
            deliveryTask.cancel();
            deliveryTask = null;
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
        backfillConfig();
        config.load();
        recipeManager.registerRecipes();
        market.reload();
        scheduleHistorySnapshots();
        miniService.reload();
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
        deliveryTask = getServer().getScheduler().runTaskTimer(this, orderService::tick, 20L * 10L, 20L * 20L);
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
}
