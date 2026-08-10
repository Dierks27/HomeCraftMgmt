package com.dierks.homecraft;

import com.dierks.homecraft.block.CustomBlockListener;
import com.dierks.homecraft.block.CustomBlockService;
import com.dierks.homecraft.command.HcmCommand;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.crafting.RecipeManager;
import com.dierks.homecraft.crafting.WorkbenchListener;
import com.dierks.homecraft.gui.AmazonListener;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.integration.ProtectionService;
import com.dierks.homecraft.item.CustomItems;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DailySellDao;
import com.dierks.homecraft.storage.MarketStateDao;
import com.dierks.homecraft.storage.PlacedBlockDao;
import com.dierks.homecraft.storage.PriceHistoryDao;
import com.dierks.homecraft.util.Keys;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;

/**
 * HomeCraft Management — Phase 1.
 *
 * <p>Delivers the project skeleton, an extensible SQLite datastore, the Mini
 * Workbench (placeable custom block → crafting GUI), and the PC (config-crafted
 * at the bench → placeholder Amazon GUI). The market, shipping, and Mini modules
 * are intentionally left as stubs where they connect.
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
    private BukkitTask historyTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        getServer().getPluginManager().registerEvents(
                new CustomBlockListener(this, config, blockService, items, protection), this);
        getServer().getPluginManager().registerEvents(new WorkbenchListener(this, recipeManager), this);
        getServer().getPluginManager().registerEvents(new AmazonListener(), this);

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
        config.load();
        recipeManager.registerRecipes();
        market.reload();
        scheduleHistorySnapshots();
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
}
