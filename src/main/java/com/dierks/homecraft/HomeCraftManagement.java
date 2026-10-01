package com.dierks.homecraft;

import com.dierks.homecraft.block.CustomBlockListener;
import com.dierks.homecraft.block.CustomBlockService;
import com.dierks.homecraft.command.HcmCommand;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.crafting.RecipeManager;
import com.dierks.homecraft.crafting.WorkbenchListener;
import com.dierks.homecraft.games.VoidWorld;
import com.dierks.homecraft.games.gen.LayoutGuard;
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
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
     * 5 = the department set the Store/Market tabs sort into — Materials added, Weapons and
     * Armor folded into Combat. 6 = the Common rarity icon, which shipped as a light-grey pane
     * and rendered as an empty slot. 7 = wild Mini spawns — rarer, much further out, a find
     * window measured in minutes — plus the grade stars, for a file an editor has flattened.
     * 11 = the courier crate's head textures, which shipped blank and so handed every courier
     * a default Steve head to carry. 12 = the four quests that pushed Mini output (print, packs,
     * selling) leave the shipped pool. 13 = the wild hunt retune: closer, longer, one at a time.
     * 14 = the token economy: no money in the Arcade, Prize Counter tabs, quest pools, and the
     * achievements list. 15 = packs hold one Card and roll by rarity odds; the shipped prices
     * drop to $100 / $300. 16 = the Second/Third Home rows become one "+1 Home" row. 17 = the
     * skill games' quests and "Games" achievements join a pool and a list the owner hasn't edited.
     * 18 = the events batch's "Games" achievements (the Dropper's and Race Night's) join that list
     * the same way. 19 = the Games places move far apart, out of sight of each other: a file from an
     * older version is marked, and once the database is open {@link LayoutGuard} keeps 0.35's spots and
     * shapes on a server that built anything there and moves the untouched ones on one that didn't.
     */
    static final int CONFIG_REVISION = 19;

    /**
     * Prefix on a migration log line that should be logged as a WARNING rather than INFO: a step
     * that found an admin's own value where it expected ours, and left it alone. The admin needs
     * to see that line — it names a key they may want to change by hand.
     */
    static final String WARN = "WARN ";

    /**
     * Every quest row revision 12 retires, with each (type, target, reward) it has ever shipped
     * with. A row still holding one of those is ours and goes; a row the admin has retuned is
     * theirs and stays, with a warning naming it.
     */
    private static final java.util.Map<String, java.util.List<Object[]>> PUSH_QUESTS = pushQuests();

    private static java.util.Map<String, java.util.List<Object[]>> pushQuests() {
        java.util.Map<String, java.util.List<Object[]>> m = new java.util.LinkedHashMap<>();
        m.put("print_daily", java.util.List.<Object[]>of(new Object[] {"PRINT_MINI", 1, 2}));
        m.put("sell_daily", java.util.List.of(new Object[] {"SELL_MARKET", 300, 1},
                new Object[] {"SELL_MARKET", 500, 2}));
        m.put("pack_weekly", java.util.List.<Object[]>of(new Object[] {"OPEN_PACK", 3, 6}));
        m.put("sell_weekly", java.util.List.of(new Object[] {"SELL_MARKET", 2000, 5},
                new Object[] {"SELL_MARKET", 5000, 8}));
        return java.util.Collections.unmodifiableMap(m);
    }

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

    /**
     * The courier crate head textures, mirroring {@code skins.courier_package} in the bundled
     * config.yml — a parcel, a box and a crate, one per distance band.
     *
     * <p>Held here because the crate shipped BLANK for three releases and a blank skin is a
     * default player head: every delivery was made carrying Steve's severed head, which is a
     * strange thing to hand a child. The textures arrived in the bundled file afterwards, but
     * the backfill only adds keys that are MISSING — an upgraded server already has these three
     * keys, holding "", so it would have kept the Steve head forever. Revision 11 fills them in.
     * A texture an admin has chosen is left alone; only an empty value is replaced. Keep in sync
     * with the bundled config.yml — a test pins the two together.
     */
    static final java.util.Map<String, String> COURIER_PACKAGE_SKINS = courierPackageSkins();

    private static java.util.Map<String, String> courierPackageSkins() {
        java.util.Map<String, String> skins = new java.util.LinkedHashMap<>();
        skins.put("local", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjY4MjRjM2ZkMTZkNTljNDM5MzdiNTNmZTE0N2ViYWNiZjgxNzgyZjVmMjVkOTAzNTBlYWJhODYxNGU1ZDU3YiJ9fX0=");
        skins.put("regional", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODcyZjUwZjlkMzYyMzMyMWE1NjQ5Y2E5NWU5ZTUzYTgyNDBjYzgxY2ZhZWI0MWEyODc2NjdkMWNjNWQ1MTM5NiJ9fX0=");
        skins.put("long_haul", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzhjMTA3ZTA3YTJiNGQ0OWRiNWY5YTk4MmExY2VmOTJiM2MyOWVkZjMyOTdmYjQ3MTQyYjU1NzZjZmQ4OTYwYiJ9fX0=");
        return java.util.Collections.unmodifiableMap(skins);
    }

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
    private com.dierks.homecraft.hunt.HuntService hunt;
    private com.dierks.homecraft.trade.StandService stands;
    private com.dierks.homecraft.marketplace.DeliveryService deliveries;
    private com.dierks.homecraft.marketplace.PalletService pallets;
    private com.dierks.homecraft.web.MarketDashboardServer dashboard;
    private com.dierks.homecraft.integration.HcmPlaceholders placeholders;
    private com.dierks.homecraft.display.DisplayService displayService;
    private com.dierks.homecraft.arcade.TokenService tokens;
    private com.dierks.homecraft.arcade.PrizeService prizes;
    private com.dierks.homecraft.arcade.homes.HomeService homes;
    private com.dierks.homecraft.arcade.TrailService trails;
    private com.dierks.homecraft.arcade.RadarService radar;
    private com.dierks.homecraft.arcade.ArcadeService arcade;
    private com.dierks.homecraft.arcade.AchievementService achievements;
    private com.dierks.homecraft.arcade.QuestService quests;
    private com.dierks.homecraft.courier.CourierService courier;
    /** Sound Mufflers: placed blocks that hush chosen sounds near them. Null only if they failed to start. */
    private com.dierks.homecraft.muffler.SoundMufflerService soundMufflers;
    /** The Games (0.35). Null until it is built, or if it failed to start; callers null-check. */
    private com.dierks.homecraft.games.GamesService games;
    /**
     * Take a break (0.35): players' own limits and pauses on games of chance, the Scratch Ticket,
     * Crates and token Card Packs included. Built on its own, outside the Games' error isolation.
     */
    private com.dierks.homecraft.games.Breaks breaks;
    /** The live market (0.33): the price multiplier, its events and ticks. Null only if it failed to build. */
    private com.dierks.homecraft.market.sim.MarketSimService marketSim;
    /** Market news delivery: broadcasts, the join catch-up, the per-player mute. Null only if it failed to build. */
    private com.dierks.homecraft.market.sim.MarketNewsService marketNews;
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
        // The Games layout (LayoutGuard): decided once the database can say what was built, and before
        // anything reads the Games settings. The Games only start once it has finished, so nothing is
        // ever built at spots it hasn't decided.
        LayoutGuard.Outcome layout = layoutGuard();
        if (layout != null && layout.changed()) {
            config.load(); // the Games settings as the layout check wrote them
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
        // The live market (0.33): a bounded multiplier on the balanced price. It never touches
        // stock; with market.sim.enabled false it answers exactly 1.0 and the market is 0.32's.
        // Built right after the catalog, plugged in as the market's mood, started (ticks, replay)
        // once the displays are up.
        try {
            com.dierks.homecraft.storage.MarketSimDao simDao = new com.dierks.homecraft.storage.MarketSimDao(database);
            this.marketNews = new com.dierks.homecraft.market.sim.MarketNewsService(this, simDao);
            this.marketSim = new com.dierks.homecraft.market.sim.MarketSimService(this, simDao, marketNews);
            this.market.setMood(marketSim);
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not set up the live market - prices stay at their usual level.", e);
            this.marketSim = null;
            this.marketNews = null;
            this.market.setMood(null);
        }
        scheduleHistorySnapshots();

        // Crate ordering + shipping (Phase 3).
        this.orderService = new OrderService(this, new OrderDao(database), market, economy);
        scheduleDeliveries();

        // Minis collectibles (Phase 4).
        this.miniService = new MiniService(this, new MiniDao(database), economy);
        this.miniService.reload();
        this.miniService.ensureUniqueNumbers(); // UNIQUE(mini_id, mint_number) when the data allows it

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
        this.hunt = new com.dierks.homecraft.hunt.HuntService(this,
                new com.dierks.homecraft.storage.WildSpawnDao(database),
                new com.dierks.homecraft.storage.MiniSpawnDao(database));
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

        // The Arcade (Phase 8): tokens (balances, streak, playtime, the ledger) and the games
        // that spend them (loot crates, the Prize Counter, pity, lotto).
        this.tokens = new com.dierks.homecraft.arcade.TokenService(
                this, new com.dierks.homecraft.storage.TokenDao(database));
        // Take a break covers every game of chance, the Scratch Ticket and Crates too, whatever
        // games.enabled says — so it is its own small service, built right after the tokens and
        // outside the Games' try/catch. It fails closed: limits it can't read close the games.
        this.breaks = new com.dierks.homecraft.games.Breaks(this, new com.dierks.homecraft.storage.GamesDao(database));
        this.arcade = new com.dierks.homecraft.arcade.ArcadeService(this);
        com.dierks.homecraft.storage.PrizeDao prizeDao = new com.dierks.homecraft.storage.PrizeDao(database);
        this.prizes = new com.dierks.homecraft.arcade.PrizeService(this, prizeDao);
        this.trails = new com.dierks.homecraft.arcade.TrailService(this, prizeDao);
        this.radar = new com.dierks.homecraft.arcade.RadarService(this);
        this.achievements = new com.dierks.homecraft.arcade.AchievementService(
                this, new com.dierks.homecraft.storage.AchievementDao(database));
        // Daily/weekly quests (Phase 11) — repeatable token objectives; no listener,
        // progress is recorded from the existing market/printer/crate/pack hooks.
        this.quests = new com.dierks.homecraft.arcade.QuestService(
                this, new com.dierks.homecraft.storage.QuestDao(database));

        // Courier — paid delivery runs, and the building at the far end of one. The
        // BuildingService owns every block it places and is the only thing that removes them,
        // so it is constructed with the service that decides when a delivery is over.
        this.courier = new com.dierks.homecraft.courier.CourierService(
                this, new com.dierks.homecraft.storage.CourierDao(database),
                new com.dierks.homecraft.storage.CourierDebtDao(database),
                new com.dierks.homecraft.courier.BuildingService(
                        this, new com.dierks.homecraft.storage.CourierSiteDao(database),
                        new com.dierks.homecraft.storage.TrackedGroundDao(database)));

        // Sound Mufflers — a block that hushes the sounds you pick near it. The hushing itself goes
        // through ProtocolLib (a soft dependency); without it mufflers still place and save.
        try {
            this.soundMufflers = new com.dierks.homecraft.muffler.SoundMufflerService(
                    this, new com.dierks.homecraft.storage.SoundMufflerDao(database));
            this.soundMufflers.start();
            getServer().getPluginManager().registerEvents(
                    new com.dierks.homecraft.muffler.MufflerBlockListener(soundMufflers), this);
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not start the Sound Mufflers.", e);
            if (this.soundMufflers != null) {
                this.soundMufflers.stop();
            }
            this.soundMufflers = null;
        }

        // The Games (0.35): games of chance, arcade cabinets, time trials and mini golf, all
        // behind games.enabled. Built even while that is false (it still finishes rounds a crash
        // left open); a failure here leaves the games out and the rest of the plugin as it was.
        // Not at all while the layout check couldn't finish (its SEVERE says why).
        if (layout == null) {
            getLogger().severe("The Games are off this start, because the Games layout check could not finish"
                    + " (above). Fix that and restart.");
        } else {
            try {
                this.games = new com.dierks.homecraft.games.GamesService(this, breaks);
                this.games.screens(new com.dierks.homecraft.gui.games.Screens(this));
                this.games.progress(new com.dierks.homecraft.arcade.GamesProgress(this)); // quests and achievements
                this.games.start();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not start the Games - they are off.", e);
                if (this.games != null) {
                    try {
                        this.games.stop();
                    } catch (RuntimeException ignored) {
                        // already failing; the SEVERE above says why
                    }
                }
                this.games = null;
            }
        }
        // Registered whatever happened above: the join/quit hooks do nothing without the service,
        // and a player's saved things must come back even if the Games never started.
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.games.GamesListener(this), this);
        getServer().getPluginManager().registerEvents(
                new com.dierks.homecraft.games.world.SessionRecoveryListener(this), this);
        // [Arcade] join signs: the hub's door into any game or course (it does nothing while the
        // Games are off or failed to start).
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.games.JoinSigns(this), this);

        getServer().getPluginManager().registerEvents(
                new com.dierks.homecraft.courier.CourierListener(this), this);
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
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.hunt.HuntListener(this), this);
        getServer().getPluginManager().registerEvents(trails, this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.arcade.PrizeItemListener(this), this);
        // The +1 Home perk. LuckPerms and Essentials load before us (softdepend), so both the
        // tiers and the API are there to read now.
        this.homes = new com.dierks.homecraft.arcade.homes.HomeService(this);
        homes.reload();
        getServer().getPluginManager().registerEvents(homes, this);
        getServer().getPluginManager().registerEvents(
                new com.dierks.homecraft.arcade.QuestListener(this, placedNatural), this);
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getPluginManager().registerEvents(shops, this);
        getServer().getPluginManager().registerEvents(new com.dierks.homecraft.mini.MiniRenderListener(this), this);
        // Market news: join sessions (announcements wait for someone who has been on a while) and
        // the "While you were away" catch-up.
        getServer().getPluginManager().registerEvents(
                new com.dierks.homecraft.market.sim.MarketNewsListener(this), this);

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
        startMarketSim(); // load, replay the missed ticks, arm the pump (§11.2)
        this.tokens.start(); // the five-minute streak + playtime tick
        this.arcade.start();
        this.radar.start(); // the Mini Radar's two-second ping
        this.quests.start(); // poll statistic-backed quests (fish, distance, kills…)
        this.courier.start(); // sweep abandoned delivery runs

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
            hunt.rebuild();
            hunt.start();
            placedMinis.rebuild();
            placedMinis.start();
            shops.rebuild();
            for (com.dierks.homecraft.storage.PlacedBlock pb : blockService.findByType(com.dierks.homecraft.block.CustomBlockType.PALLET)) {
                org.bukkit.World w = getServer().getWorld(pb.world());
                if (w != null && w.isChunkLoaded(pb.x() >> 4, pb.z() >> 4)) {
                    pallets.refreshSkin(new org.bukkit.Location(w, pb.x(), pb.y(), pb.z()));
                }
            }
            if (games != null) {
                try {
                    games.worldsReady(); // players whose last world game is still sending them home
                } catch (RuntimeException e) {
                    getLogger().log(java.util.logging.Level.SEVERE, "The Games could not finish starting up.", e);
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

    /**
     * The built-in void world for the Games ({@link VoidWorld}):
     * {@code /mv create sky normal -g HomeCraftManagement}. Every world that names this plugin as its
     * generator gets it; the id after a {@code :} is ignored.
     *
     * <p>Bukkit only asks a plugin that is already enabled ({@code WorldCreator.getGeneratorForName}
     * and the server's own lookup both refuse one that isn't, and the world then loads with vanilla
     * terrain). Multiverse loads its worlds while it enables, at every start, so plugin.yml loads this
     * plugin before Multiverse-Core ({@code loadbefore}); otherwise {@code sky} would get vanilla
     * ground, biomes and mobs in every chunk made after a restart ({@code PluginLoadOrderTest}). A world
     * named in bukkit.yml instead is made before any plugin that isn't {@code load: STARTUP} is enabled,
     * so the void world is made with Multiverse, never bukkit.yml.
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new VoidWorld.Generator();
    }

    /** The void world's biomes ({@code minecraft:the_void}), for a world that names this plugin's biomes. */
    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull String worldName, @Nullable String id) {
        return VoidWorld.biomes();
    }

    @Override
    public void onDisable() {
        // Paper skips a disabled plugin's listeners, so MenuListener never sees the close events
        // that follow a shutdown. A menu holding a player's items (the Card trade-in tray) must
        // hand them back now, while the player's inventory is still going to be saved. With
        // Multiverse-Inventories, which disables (and saves) before this plugin, the recovery
        // listener has closed them already, at its PluginDisableEvent: a menu closes once.
        com.dierks.homecraft.gui.Menu.closeAll(getServer().getOnlinePlayers(), getLogger());
        if (games != null) {
            // Synchronously, while the tokens and the database are still here: no task can run now,
            // so world sessions restore in place and OPEN rounds wait for the next start.
            try {
                games.stop();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not stop the Games cleanly.", e);
            }
            games = null;
        }
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
        if (hunt != null) {
            hunt.stop();
            hunt = null;
        }
        if (effects != null) {
            effects.stop(); // removes every hologram/display/light we own
            effects = null;
        }
        if (tokens != null) {
            tokens.stop();
            tokens = null;
        }
        breaks = null;
        if (radar != null) {
            radar.stop();
            radar = null;
        }
        if (arcade != null) {
            arcade.stop();
            arcade = null;
        }
        if (quests != null) {
            quests.stop();
            quests = null;
        }
        if (courier != null) {
            courier.stop();
            courier = null;
        }
        if (soundMufflers != null) {
            soundMufflers.stop(); // unhooks ProtocolLib before the database goes
            soundMufflers = null;
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
        if (marketSim != null) {
            // Before the database closes: cancel the pump and any real-price fetch, then write the
            // per-item rows and the ledger buffer in one transaction.
            try {
                marketSim.stop();
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.WARNING, "Could not stop the live market cleanly.", e);
            }
            if (market != null) {
                market.setMood(null);
            }
            marketSim = null;
        }
        marketNews = null;
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
        // A config.yml from an older version put back by hand comes marked again: the layout check
        // gives it this server's decision before the Games read it. If it can't, the running Games keep
        // the settings they had.
        com.dierks.homecraft.config.GamesConfig.Parsed gamesBefore = config.games();
        LayoutGuard.Outcome layout = layoutGuard();
        config.load();
        if (layout == null) {
            config.keepGames(gamesBefore);
        }
        recipeManager.registerRecipes();
        market.reload(); // also tells the live market the catalog changed
        if (marketSim != null) {
            try {
                marketSim.reload(); // new settings; on/off; events of removed items end
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "The live market could not reload.", e);
            }
        }
        scheduleHistorySnapshots();
        miniService.reload();
        if (wildDrops != null) {
            wildDrops.invalidate();
        }
        if (effects != null) {
            effects.start(); // re-arm at the new cadence; the registry is kept
        }
        if (hunt != null) {
            hunt.start(); // re-arm the spawn roll and the hunt tick under the new config
        }
        if (backups != null) {
            backups.start();
        }
        if (shops != null) {
            shops.start();
        }
        if (dashboard != null) {
            dashboard.restart(); // pick up bind/port/enabled/refresh/title/feed-token changes
        }
        if (displayService != null) {
            displayService.start(); // re-arm the refresh timer at the new cadence
        }
        if (arcade != null) {
            tokens.start(); // re-arm the streak + playtime tick under any new config
            arcade.start();
            quests.start(); // re-arm the quest stat poll under any new config
        }
        if (courier != null) {
            courier.start(); // re-arm the courier expiry sweep under any new config
        }
        if (soundMufflers != null) {
            soundMufflers.reload(); // on/off and the range limits
        }
        if (games != null) {
            try {
                games.reload(); // games that closed stop (sessions home, rounds finished), new ones start
            } catch (RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "The Games could not reload.", e);
            }
        }
        if (homes != null) {
            homes.reload(); // re-read Essentials' sethome-multiple tiers
            homes.refreshAll();
        }
    }

    /**
     * Run the Games layout check ({@link LayoutGuard}) against config.yml on disk and the database,
     * logging what it did. Reads the file again when it was rewritten.
     *
     * @return what it did, or {@code null} when it couldn't finish (logged as SEVERE): the Games must
     *         not use the file then
     */
    private LayoutGuard.Outcome layoutGuard() {
        java.io.File file = configFile();
        LayoutGuard.ConfigFile onDisk = new LayoutGuard.ConfigFile() {
            @Override
            public org.bukkit.configuration.file.FileConfiguration load() {
                return loadOnDisk(file);
            }

            @Override
            public boolean save(org.bukkit.configuration.file.FileConfiguration c) {
                snapshotConfig(file);
                return saveTo(c, file, "layout-checked");
            }
        };
        LayoutGuard.Outcome out;
        try {
            out = LayoutGuard.run(LayoutGuard.Store.of(database), onDisk, System.currentTimeMillis());
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "The Games layout check could not finish: "
                    + e.getMessage() + ". It runs again at the next start or /hcm reload.", e);
            return null;
        }
        for (String line : out.log()) {
            if (line.startsWith(WARN)) {
                getLogger().warning(line.substring(WARN.length()));
            } else {
                getLogger().info(line);
            }
        }
        if (out.changed()) {
            reloadConfig();
        }
        return out;
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
            if (line.startsWith(WARN)) {
                getLogger().warning(line.substring(WARN.length()));
            } else {
                getLogger().info(line);
            }
        }
        reloadConfig();
        return true;
    }

    /** The {@code id} of every row in a quest list, for deciding whether it is still ours. */
    private static java.util.Set<String> questIds(org.bukkit.configuration.file.FileConfiguration c,
                                                  String path) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (java.util.Map<?, ?> row : mapList(c, path)) {
            Object id = row.get("id");
            if (id != null) {
                ids.add(String.valueOf(id).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return ids;
    }

    /** The rev-9 pool. Revision 12 then drops print_daily and sell_daily from it again. */
    private static java.util.List<java.util.Map<String, Object>> defaultDailyQuests() {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(questRow("fish_daily", "CATCH_FISH", 8, 3, "Catch 8 fish"));
        rows.add(questRow("walk_daily", "TRAVEL_ON_FOOT", 800, 3, "Travel 800 blocks on foot"));
        rows.add(questRow("print_daily", "PRINT_MINI", 1, 2, "Print a Mini at a Printer"));
        rows.add(questRow("sell_daily", "SELL_MARKET", 300, 1, "Sell $300 to the Market"));
        return rows;
    }

    private static java.util.List<java.util.Map<String, Object>> defaultWeeklyQuests() {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(questRow("hostiles_weekly", "KILL_HOSTILES", 120, 12, "Defeat 120 hostile mobs"));
        rows.add(questRow("breed_weekly", "BREED_ANIMALS", 12, 8, "Breed 12 animals"));
        rows.add(questRow("trade_weekly", "TRADE_VILLAGER", 15, 7, "Trade with villagers 15 times"));
        rows.add(questRow("pack_weekly", "OPEN_PACK", 3, 6, "Open 3 Card Packs"));
        rows.add(questRow("sell_weekly", "SELL_MARKET", 2000, 5, "Sell $2,000 to the Market"));
        return rows;
    }

    private static java.util.Map<String, Object> questRow(String id, String type, int target,
                                                          int reward, String display) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("type", type);
        row.put("target", target);
        row.put("reward", reward);
        row.put("display", display);
        return row;
    }

    /**
     * The Prize Counter rows a pre-revision-8 config is seeded with. Written out here rather
     * than backfilled from the bundled file because a list of maps is one value, not a tree of
     * leaves: the backfill would either miss it or flatten an admin's own rows.
     */
    private static java.util.List<java.util.Map<String, Object>> defaultPrizeRows() {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(prizeRow("filament_bundle", "&fFilament Bundle", 6, "filament", "amount", 8));
        rows.add(prizeRow("display_case", "&bDisplay Case", 25, "block", "block", "display_case"));
        rows.add(prizeRow("starter_pack", "&dStarter Pack", 30, "pack", "pack", "starter"));
        return rows;
    }

    private static java.util.Map<String, Object> prizeRow(String id, String display, int cost,
                                                          String type, String extraKey, Object extraValue) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("display", display);
        row.put("cost_tokens", cost);
        row.put("type", type);
        row.put(extraKey, extraValue);
        return row;
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
            // Merge first, then add: the tab row holds eight departments and no more, so
            // Materials only fits once Weapons and Armor have become one.
            if (mergeDepartments(c, java.util.List.of("Weapons", "Armor"), "Combat")) {
                log.add("Config migration: Weapons + Armor → Combat. The Store/Market tab row "
                        + "is nine slots and \"All\" takes the first, so eight departments is the "
                        + "ceiling and Materials needed the room.");
            }
            if (addDepartment(c, "Materials", "Blocks")) {
                log.add("Config migration: added the Materials department (ingots, gems and "
                        + "crafting stock) — it is a Store/Market tab, so an existing "
                        + "departments list has to gain it or those items fall into Misc.");
            }
        }
        if (from < 6) {
            if (replaceShippedDefault(c, "minis.rarity_styles.COMMON.pane", "LIGHT_GRAY", "WHITE")) {
                log.add("Config migration: the COMMON rarity icon is WHITE instead of LIGHT_GRAY — "
                        + "a light-grey pane is the colour of the inventory slot behind it, so every "
                        + "Common Museum header rendered as an empty tile.");
            }
        }
        if (from < 7) {
            // Two Minis in three hours, both inside 100 blocks, found in seconds. The chance
            // that produced that is the admin's own and is left alone; what moves here are the
            // numbers we shipped, which were all pulling the same way.
            if (replaceShippedInt(c, "minis.loot.natural.interval_ticks", 12000, 24000)) {
                log.add("Config migration: natural spawns roll every 20 minutes instead of 10.");
            }
            if (replaceShippedInt(c, "minis.loot.natural.despawn_minutes", 10, 3)) {
                log.add("Config migration: a wild Mini now slips away after 3 minutes, not 10 — "
                        + "and the spawn hint says so, because a timed hunt nobody is told the "
                        + "length of is just a Mini that vanishes.");
            }
            boolean near = replaceShippedInt(c, "minis.loot.natural.min_distance", 24, 96);
            near |= replaceShippedInt(c, "minis.loot.natural.max_distance", 48, 128);
            if (near) {
                log.add("Config migration: wild Minis land 96–128 blocks out instead of 24–48. "
                        + "Keep max_distance inside your view-distance (10 chunks = 160 blocks by "
                        + "default) — a spot is only usable in a loaded chunk.");
            }
            if (replaceShippedDefault(c, "minis.announce.hint_radius_text",
                    "within 100 blocks of a player", "within %blocks% blocks of a player")) {
                log.add("Config migration: the spawn hint reads its distance from "
                        + "minis.loot.natural.max_distance. It had been claiming 100 blocks while "
                        + "the spawn band was 24–48, and a hint that is wrong about the only fact "
                        + "it carries is worse than no hint.");
            }
            for (String grade : repairGradeSymbols(c)) {
                log.add("Config migration: minis.grades." + grade + ".symbol was \"?\" — the stars "
                        + "had been flattened by an editor saving config.yml as ANSI instead of "
                        + "UTF-8, so every Mini showed as \"Creeper ?\". Restored.");
            }
        }
        if (from < 8) {
            // The token economy had two sinks: a crate at 1 token and the 25-token pity
            // exchange. Against ~16 tokens earned in an active evening, a 1-token pull is
            // not a sink at all, so balances could only grow — which is exactly what the
            // live server showed. Both halves of the fix land here.
            if (replaceShippedInt(c, "arcade.crates.starter.cost_tokens", 1, 5)) {
                log.add("Config migration: the Starter Crate costs 5 tokens, not 1. A pull "
                        + "priced below a single evening's earnings cannot drain anything.");
            }
            if (!has(c, "arcade.prizes")) {
                c.set("arcade.prizes", defaultPrizeRows());
                log.add("Config migration: added the Prize Counter (arcade.prizes) — fixed-price "
                        + "token purchases with a known outcome, so tokens have a floor value and "
                        + "not only an expected one. Edit or remove the rows freely.");
            }
        }
        if (from < 9) {
            if (!has(c, "arcade.quests.week_starts")) {
                c.set("arcade.quests.week_starts", "MONDAY");
                log.add("Config migration: weekly quests roll over on MONDAY. They had been "
                        + "keyed as epochDay / 7, which is a real seven-day window but starts on "
                        + "a THURSDAY — epoch day 0 was 1 January 1970, and that was a Thursday. "
                        + "This week's weekly progress resets once as the key changes shape.");
            }
            // Only swap a quest pool that is still the one we shipped. An admin who has
            // written their own objectives has made a decision, and two sell quests are a
            // smaller problem than overwriting it.
            if (questIds(c, "arcade.quests.daily").equals(java.util.Set.of("sell_daily", "crate_daily", "print_daily"))
                    && questIds(c, "arcade.quests.weekly").equals(java.util.Set.of("sell_weekly", "crate_weekly", "pack_weekly"))) {
                c.set("arcade.quests.daily", defaultDailyQuests());
                c.set("arcade.quests.weekly", defaultWeeklyQuests());
                log.add("Config migration: the quest pool is mostly verbs now. Selling to the "
                        + "market was 40% of the token reward on offer and the biggest single "
                        + "line in both periods; it is 13% and the smallest. The new objectives "
                        + "(fish, distance on foot, hostiles, breeding, villager trades) are read "
                        + "from vanilla statistics, so they need no new listener.");
            }
        }
        if (from < 10) {
            // The screen stopped buying, so a title promising "instant buy/sell" now describes
            // something that does not exist. This is exactly what the blind backfill cannot do:
            // it only ADDS missing keys, so an existing config.yml would have kept the old
            // sentence forever. An admin who wrote their own title keeps it.
            if (replaceShippedDefault(c, "menus.market_title",
                    "&1Market — instant buy/sell", "&1Sell to Crate")) {
                log.add("Config migration: the market screen is called Sell to Crate and only "
                        + "sells. Buying there was instant and free of shipping, so no player "
                        + "would ever pick a shipping tier — which made the tiers, the Locker "
                        + "and in-transit orders content that existed and was never used. "
                        + "Selling needs no shipping because you are the one delivering.");
            }
        }
        if (from < 11) {
            // The crate a courier carries. It shipped blank for three releases, and a blank
            // head texture is a DEFAULT player head — so the parcel handed out at the job
            // board was Steve's face, held in hand for the length of every delivery. The
            // textures reached the bundled config afterwards, but these keys already exist on
            // an upgraded server (holding ""), and the backfill only adds keys that are
            // missing — so nothing would ever have replaced them.
            java.util.List<String> crates = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> band : COURIER_PACKAGE_SKINS.entrySet()) {
                if (replaceShippedDefault(c, "skins.courier_package." + band.getKey(), "",
                        band.getValue())) {
                    crates.add(band.getKey());
                }
            }
            if (!crates.isEmpty()) {
                log.add("Config migration: the courier crate now looks like a parcel instead of a "
                        + "default player head (" + String.join(", ", crates) + "). A blank skin is "
                        + "Steve's head, and the package is carried in hand for the whole delivery.");
            }
        }
        if (from < 12) {
            // Every token sink ended in Minis content, and so did two of the quests paying
            // them: print a Mini a day, open three packs a week. Selling to the market was
            // the other push. The quest system is replaced wholesale later; this stops the
            // pressure now. Only rows still holding a value we shipped go.
            for (String path : java.util.List.of("arcade.quests.daily", "arcade.quests.weekly")) {
                log.addAll(retireShippedQuests(c, path));
            }
        }
        if (from < 13) {
            // The September retune moved wild Minis to 96–128 blocks with a 3-minute window:
            // about four times the area in under a third of the time, and nobody has found one
            // since. The hunt is now a shared event (one at a time, named, hinted, beamed), so
            // it comes in closer and runs longer. The cadence is left alone.
            String n = "minis.loot.natural.";
            retune(c, n + "min_distance", 96, 48, log);
            retune(c, n + "max_distance", 128, 96, log);
            retune(c, n + "despawn_minutes", 3, 5, log);
            retune(c, n + "max_live", 2, 1, log);
            retune(c, n + "player_cooldown_minutes", 120, 90, log);
        }
        if (from < 14) {
            // The token economy: the Arcade stops taking or paying dollars, the Prize Counter
            // gets tabs and real prizes, quests are drawn per player from a pool, and the
            // achievements become a list. See ArcadeConfigMigration.
            ArcadeConfigMigration.apply(c, log);
        }
        if (from < 15) {
            // Packs hold one Card and roll by rarity odds. See PackConfigMigration.
            PackConfigMigration.apply(c, log);
        }
        if (from < 16) {
            // Second/Third Home become "+1 Home", which adds to what a player has. See
            // HomeSlotMigration.
            HomeSlotMigration.apply(c, log);
        }
        if (from < 17) {
            // The skill games' quests and achievements. See ArcadeConfigMigration#gamesRows.
            ArcadeConfigMigration.gamesRows(c, log);
        }
        if (from < 18) {
            // The events batch's "Games" achievements. See ArcadeConfigMigration#eventRows.
            ArcadeConfigMigration.eventRows(c, log);
        }
        if (from < 19) {
            // The Games places move far apart. Whether this server keeps 0.35's spots depends on what
            // it built, which only the database knows, and it isn't open yet: mark the file, and
            // LayoutGuard decides right after database.connect(), before the Games read anything.
            LayoutGuard.markPending(c);
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

        // Not revision-gated: an owner can write a bare `market.sim: false` at any time.
        liveMarketSwitch(c, log);
        // Nor this: a bare `games: false` or `games.ore_slots: false`.
        gamesSwitch(c, log);

        return log;
    }

    /**
     * A bare {@code market.sim: false} (or {@code off}, {@code no}, {@code true}, or junk) where the
     * live market's section belongs becomes {@code market.sim.enabled: <that value>}. Without this
     * the backfill that follows sees no {@code market.sim.enabled} on disk, replaces the scalar with
     * the whole shipped section, {@code enabled: true} included, and a market the owner switched off
     * would run. With it, the backfill fills in the rest of the section around the owner's switch.
     *
     * <p>A value that does not read as a switch ({@code sim: "yes please"}, {@code sim: 0}) is
     * written as {@code false} with a {@link #WARN}: an off switch nobody can read keeps the market
     * off, as {@code MarketSimConfig} reads it. The key keeps its place and its comments.
     */
    static void liveMarketSwitch(org.bukkit.configuration.file.FileConfiguration c,
                                 java.util.List<String> log) {
        String path = com.dierks.homecraft.config.MarketSimConfig.PATH;
        Object sim = c.get(path, null);
        if (sim == null || sim instanceof org.bukkit.configuration.ConfigurationSection) {
            return;
        }
        Boolean read = com.dierks.homecraft.config.MarketSimConfig.readSwitch(sim);
        boolean enabled = Boolean.TRUE.equals(read);
        java.util.List<String> above = commentsOf(c, path);
        java.util.List<String> inline;
        try {
            inline = c.getInlineComments(path);
        } catch (Throwable ignored) {
            inline = java.util.List.of();
        }
        // createSection replaces the scalar in place (same map slot, so the same spot in the file).
        c.createSection(path).set("enabled", enabled);
        try {
            c.setComments(path, above);
            c.setInlineComments(path, inline);
        } catch (Throwable ignored) {
            // Comment API unavailable on this server: the switch still stands.
        }
        if (read == null) {
            log.add(WARN + "Config migration: " + path + " was \"" + sim + "\", which is not true or false - "
                    + "wrote " + path + ".enabled: false, so the live market is off until you set it to true.");
        } else {
            log.add("Config migration: " + path + ": " + sim + " is now " + path + ".enabled: " + enabled
                    + " (the rest of the section is filled in with the shipped settings"
                    + (enabled ? ")." : "; the live market stays off)."));
        }
    }

    /**
     * A bare switch where a Games section belongs becomes that section's {@code enabled}:
     * {@code games: false} becomes {@code games.enabled: false}, and {@code games.<id>: false}
     * becomes {@code games.<id>.enabled: false} for every game in the catalog. Without it the
     * backfill that follows would replace the scalar with the whole shipped section — and a game
     * the owner switched off would come back on. A value that is not a switch is written as
     * {@code false} with a {@link #WARN} (an off switch nobody can read stays off, as
     * {@code GamesConfig} reads it). The key keeps its place and its comments.
     */
    static void gamesSwitch(org.bukkit.configuration.file.FileConfiguration c, java.util.List<String> log) {
        String root = com.dierks.homecraft.config.GamesConfig.PATH;
        Object games = c.get(root, null);
        if (games == null) {
            return;
        }
        if (!(games instanceof org.bukkit.configuration.ConfigurationSection)) {
            switchToSection(c, root, games, "the Games module", log);
            return;
        }
        for (com.dierks.homecraft.games.GameSpec<?> spec : com.dierks.homecraft.games.GameCatalog.SPECS) {
            String path = root + "." + com.dierks.homecraft.config.GamesConfig.block(spec.id());
            Object v = c.get(path, null);
            if (v != null && !(v instanceof org.bukkit.configuration.ConfigurationSection)) {
                switchToSection(c, path, v, spec.id(), log);
            }
        }
        // fx2-C #9: a bare Fresh course switch (games.fresh.slots.<id>: false), which DailySettings
        // reads as that course's enabled, is the same trap one level down.
        String slots = root + "." + com.dierks.homecraft.config.GamesConfig.block(
                com.dierks.homecraft.games.gen.DailyCourses.SPEC.id()) + ".slots";
        if (c.get(slots, null) instanceof org.bukkit.configuration.ConfigurationSection) {
            for (com.dierks.homecraft.games.gen.api.Slots.Def def : com.dierks.homecraft.games.gen.api.Slots.ALL) {
                String path = slots + "." + def.id();
                Object v = c.get(path, null);
                if (v != null && !(v instanceof org.bukkit.configuration.ConfigurationSection)) {
                    switchToSection(c, path, v, "the " + def.name() + " course", log);
                }
            }
        }
    }

    /** Replace the scalar at {@code path} with a section holding only {@code enabled}, keeping its comments. */
    private static void switchToSection(org.bukkit.configuration.file.FileConfiguration c, String path, Object value,
                                        String what, java.util.List<String> log) {
        Boolean read = com.dierks.homecraft.config.GamesConfig.readSwitch(value);
        boolean enabled = Boolean.TRUE.equals(read);
        java.util.List<String> above = commentsOf(c, path);
        java.util.List<String> inline;
        try {
            inline = c.getInlineComments(path);
        } catch (Throwable ignored) {
            inline = java.util.List.of();
        }
        c.createSection(path).set("enabled", enabled);
        try {
            c.setComments(path, above);
            c.setInlineComments(path, inline);
        } catch (Throwable ignored) {
            // Comment API unavailable on this server: the switch still stands.
        }
        if (read == null) {
            log.add(WARN + "Config migration: " + path + " was \"" + value + "\", which is not true or false - "
                    + "wrote " + path + ".enabled: false, so " + what + " stays off until you set it to true.");
        } else {
            log.add("Config migration: " + path + ": " + value + " is now " + path + ".enabled: " + enabled
                    + " (the rest of the section is filled in with the shipped settings).");
        }
    }

    /**
     * One numeric retune: move {@code path} from {@code shipped} to {@code corrected} if it still
     * holds what we shipped, logging the change; if the admin has set their own number, keep it
     * and log a {@link #WARN} line naming the key. An absent key is left to the backfill.
     */
    static void retune(org.bukkit.configuration.file.FileConfiguration c, String path, int shipped,
                       int corrected, java.util.List<String> log) {
        Object current = c.get(path, null);
        if (current == null) {
            return;
        }
        if (replaceShippedInt(c, path, shipped, corrected)) {
            log.add("Config migration: " + path + " " + shipped + " → " + corrected + ".");
        } else if (!(current instanceof Number num) || num.intValue() != corrected) {
            log.add(WARN + "Config migration: kept " + path + " = " + current + " because you have changed it "
                    + "(the new default is " + corrected + ").");
        }
    }

    /**
     * Revision 12: drop the quest rows in {@link #PUSH_QUESTS} from one quest list, where they
     * still hold a (type, target, reward) this plugin shipped. A row with the same id but the
     * admin's own numbers is kept, with a {@link #WARN} line naming it. The display text is not
     * compared: rewording a label does not change what the quest makes a player do.
     *
     * @return the log lines — one per row removed or kept
     */
    static java.util.List<String> retireShippedQuests(org.bukkit.configuration.file.FileConfiguration c,
                                                      String path) {
        java.util.List<String> log = new java.util.ArrayList<>();
        if (!(c.get(path, null) instanceof java.util.List<?> raw)) {
            return log;
        }
        java.util.List<Object> kept = new java.util.ArrayList<>();
        boolean changed = false;
        for (Object o : raw) {
            if (!(o instanceof java.util.Map<?, ?> row)) {
                kept.add(o);
                continue;
            }
            String id = String.valueOf(row.get("id")).toLowerCase(java.util.Locale.ROOT);
            java.util.List<Object[]> shipped = PUSH_QUESTS.get(id);
            if (shipped == null) {
                kept.add(o);
                continue;
            }
            if (matchesShippedQuest(row, shipped)) {
                changed = true;
                log.add("Config migration: removed the " + id + " quest from " + path + " — it paid "
                        + "tokens for printing Minis, opening packs or selling, which pushed Mini "
                        + "output instead of play.");
            } else {
                kept.add(o);
                log.add(WARN + "Config migration: kept " + path + " → " + id + " because you have "
                        + "changed it. Remove it by hand if you want the quest pressure gone.");
            }
        }
        if (changed) {
            c.set(path, kept);
        }
        return log;
    }

    private static boolean matchesShippedQuest(java.util.Map<?, ?> row, java.util.List<Object[]> shipped) {
        String type = String.valueOf(row.get("type")).trim().toUpperCase(java.util.Locale.ROOT);
        for (Object[] v : shipped) {
            if (v[0].equals(type) && row.get("target") instanceof Number t && t.longValue() == ((Integer) v[1])
                    && row.get("reward") instanceof Number r && r.intValue() == (Integer) v[2]) {
                return true;
            }
        }
        return false;
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
     * Replace a config value, but only while it is still the one this plugin shipped.
     *
     * <p>Used when a default turns out to be wrong rather than merely old. An admin who has chosen
     * their own value has chosen it deliberately and keeps it; everyone still carrying ours gets
     * the correction. Comparison is case-insensitive because these are hand-typed names.
     *
     * @return false when the key is absent, or holds something the admin picked
     */
    static boolean replaceShippedDefault(org.bukkit.configuration.file.FileConfiguration c,
                                         String path, String shipped, String corrected) {
        Object current = c.get(path, null);
        if (current == null || !shipped.equalsIgnoreCase(String.valueOf(current).trim())) {
            return false;
        }
        c.set(path, corrected);
        return true;
    }

    /**
     * {@link #replaceShippedDefault} for a number.
     *
     * <p>Separate because the String version writes a String: {@code getInt} on a value that
     * is {@code "24000"} rather than {@code 24000} returns the default instead, so a migration
     * that looked like it worked would silently leave the old behaviour running.
     *
     * @return false when the key is absent, is not a number, or holds something the admin picked
     */
    static boolean replaceShippedInt(org.bukkit.configuration.file.FileConfiguration c,
                                     String path, int shipped, int corrected) {
        if (!(c.get(path, null) instanceof Number n) || n.intValue() != shipped) {
            return false;
        }
        c.set(path, corrected);
        return true;
    }

    /**
     * Put the grade stars back after an editor has flattened them.
     *
     * <p>☆ and ★ are UTF-8; save config.yml from an editor set to ANSI/Windows-1252 and every
     * character it cannot represent is written out as the byte {@code '?'}. The file still
     * parses, nothing errors, and every Mini in the game renders as "Creeper ?" with no way to
     * see why from in-game. It is a plausible thing for anyone to do once while editing a lore
     * line, and a miserable thing to diagnose.
     *
     * <p>Only a symbol that is question marks and nothing else is touched. Nobody chooses that
     * deliberately, so there is no admin preference to overwrite — and a symbol merely different
     * from ours is left alone exactly as {@link #replaceShippedDefault} would leave it.
     *
     * @return the grades repaired, for the migration log
     */
    static java.util.List<String> repairGradeSymbols(org.bukkit.configuration.file.FileConfiguration c) {
        java.util.List<String> fixed = new java.util.ArrayList<>();
        for (com.dierks.homecraft.mini.Grade g : com.dierks.homecraft.mini.Grade.values()) {
            for (String key : java.util.List.of(g.name(), g.name().toLowerCase(java.util.Locale.ROOT))) {
                String path = "minis.grades." + key + ".symbol";
                Object current = c.get(path, null);
                if (current != null && com.dierks.homecraft.mini.Grade.isMangledSymbol(String.valueOf(current))) {
                    // g.symbol() is the built-in: Grade already refuses to serve a mangled
                    // override, so this reads the default rather than the wreckage.
                    c.set(path, g.symbol());
                    fixed.add(key);
                }
            }
        }
        return fixed;
    }

    /**
     * Revision 5: fold several departments into one, standing where the first of them stood.
     *
     * <p>The Store/Market tab row is nine slots and "All" takes the first, so eight
     * departments is a hard ceiling — Weapons and Armor become Combat to make room for
     * Materials. A department beyond the ceiling is not lost (its items still sell, and
     * still show under "All"), but it gets no tab, which is worse than one honest merge.
     *
     * <p>Admin overrides naming a folded department are retargeted with it: an override
     * pointing at a department that is no longer in the list falls through to the catch-all,
     * which would quietly move exactly the items the admin took the trouble to place by hand.
     *
     * @return false when the file names none of them, or has no list to edit
     */
    static boolean mergeDepartments(org.bukkit.configuration.file.FileConfiguration c,
                                    java.util.List<String> from, String into) {
        if (!(c.get("marketplace.departments", null) instanceof java.util.List<?> raw) || raw.isEmpty()) {
            return false; // absent — the backfill supplies the shipped list, Combat included
        }
        java.util.List<String> departments = new java.util.ArrayList<>();
        for (Object o : raw) {
            departments.add(String.valueOf(o));
        }
        // Already there? Then the folded names simply drop out, rather than a second copy
        // of the merged department appearing further up the list.
        boolean placed = containsIgnoreCase(departments, into);
        java.util.List<String> merged = new java.util.ArrayList<>();
        boolean changed = false;
        for (String d : departments) {
            if (containsIgnoreCase(from, d)) {
                changed = true;
                if (!placed) {
                    merged.add(into);
                    placed = true;
                }
                continue;
            }
            merged.add(d);
        }
        if (!changed) {
            return false;
        }
        c.set("marketplace.departments", merged);
        retargetOverrides(c, from, into);
        return true;
    }

    /** Point {@code marketplace.category_overrides} entries at the merged department. */
    private static void retargetOverrides(org.bukkit.configuration.file.FileConfiguration c,
                                          java.util.List<String> from, String into) {
        if (!(c.get("marketplace.category_overrides", null)
                instanceof org.bukkit.configuration.ConfigurationSection sec)) {
            return;
        }
        java.util.List<String> stale = new java.util.ArrayList<>();
        for (String key : sec.getKeys(false)) {
            Object value = sec.get(key, null);
            if (value != null && containsIgnoreCase(from, String.valueOf(value))) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            sec.set(key, into); // collected first — do not edit the section mid-iteration
        }
    }

    private static boolean containsIgnoreCase(java.util.List<String> haystack, String needle) {
        for (String s : haystack) {
            if (s.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
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

        java.util.List<String> kept = new java.util.ArrayList<>();
        java.util.List<String> added = backfillConfig(onDisk, defaults, kept);
        for (String path : kept) { // fx2-C #9
            getLogger().warning("Config backfill: " + path + " holds a single value where a section of settings "
                    + "belongs - left as you wrote it; the settings under it use their shipped values.");
        }
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
        return backfillConfig(current, defaults, new java.util.ArrayList<>());
    }

    /**
     * {@link #backfillConfig(org.bukkit.configuration.file.FileConfiguration,
     * org.bukkit.configuration.file.FileConfiguration)}, adding to {@code kept} every path where the
     * owner has a single value and the bundled file a section (fx2-C #9). That value is never
     * replaced by the shipped section: writing the leaves under it would swap the owner's
     * {@code false} for the shipped {@code enabled: true}. The switches the plugin reads that way
     * ({@code games}, {@code games.<block>}, {@code games.fresh.slots.<id>}, {@code market.sim}) are
     * rewritten as sections by the migration first; anything else is left for the owner.
     */
    static java.util.List<String> backfillConfig(
            org.bukkit.configuration.file.FileConfiguration current,
            org.bukkit.configuration.file.FileConfiguration defaults, java.util.List<String> kept) {
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
            String scalar = scalarAncestor(current, key);
            if (scalar != null) {
                if (!kept.contains(scalar)) {
                    kept.add(scalar);
                }
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

    /**
     * {@code /hcm config reset <section> [confirm]}: without confirm, list what would change;
     * with it, snapshot config.yml, put the section back to the bundled defaults (comments and
     * all), save and reload. Only {@link ConfigReset#ALLOWED} sections: catalogs are the admin's.
     *
     * @return the lines to show, as '&amp;'-coded strings
     */
    public java.util.List<String> resetConfigSection(String section, boolean confirm) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String path = ConfigReset.normalise(section);
        if (!ConfigReset.allowed(path)) {
            out.add("&c" + (path.isEmpty() ? "Name a section." : "'" + path + "' can't be reset.")
                    + " &7Allowed: arcade (or arcade.<part>), games (or games.<part>), packs, minis.loot.natural,"
                    + " minis.effects, clock.");
            return out;
        }
        org.bukkit.configuration.file.YamlConfiguration bundled = ArcadeConfigMigration.bundled();
        java.io.File file = configFile();
        org.bukkit.configuration.file.YamlConfiguration onDisk = loadOnDisk(file);
        if (bundled == null || onDisk == null) {
            out.add("&cCouldn't read " + (bundled == null ? "the bundled defaults" : "config.yml") + " — nothing changed.");
            return out;
        }
        if (!bundled.contains(path)) {
            out.add("&c'" + path + "' isn't in the bundled config, so there's nothing to reset it to.");
            return out;
        }
        // Where the Games places stand (every origin and gap, the keep area, the Games worlds) stays as it is:
        // putting the bundled values back would move what is built there, and places move by hand only.
        java.util.List<String> keep = ConfigReset.kept(onDisk, path);
        ConfigReset.Plan plan = ConfigReset.plan(onDisk, bundled, path, keep);
        if (plan.none()) {
            out.add("&a" + path + " already matches the defaults" + (plan.kept().isEmpty() ? ""
                    : ", apart from where the Games places stand") + ". Nothing to do.");
            keptLines(plan, out);
            return out;
        }
        out.add((confirm ? "&6Resetting " : "&6Dry run — resetting ") + "&f" + path + "&6 would change "
                + plan.changed().size() + ", add " + plan.added().size() + " and remove " + plan.removed().size()
                + " key(s):");
        int shown = 0;
        for (java.util.Map.Entry<String, Object[]> e : plan.changed().entrySet()) {
            if (shown++ < 40) {
                out.add("&e~ &f" + e.getKey() + "&7: " + ConfigReset.brief(e.getValue()[0]) + " &7→ &a"
                        + ConfigReset.brief(e.getValue()[1]));
            }
        }
        for (java.util.Map.Entry<String, Object> e : plan.added().entrySet()) {
            if (shown++ < 40) {
                out.add("&a+ &f" + e.getKey() + "&7: " + ConfigReset.brief(e.getValue()));
            }
        }
        for (java.util.Map.Entry<String, Object> e : plan.removed().entrySet()) {
            if (shown++ < 40) {
                out.add("&c- &f" + e.getKey() + "&7: " + ConfigReset.brief(e.getValue()));
            }
        }
        if (shown > 40) {
            out.add("&7…and " + (shown - 40) + " more (all of them are in the server log).");
        }
        keptLines(plan, out);
        if (!confirm) {
            out.add("&7Nothing has changed. Run &f/hcm config reset " + path + " confirm&7 to do it.");
            return out;
        }
        getLogger().info("Config reset of " + path + ": " + plan.changed().keySet() + " changed, "
                + plan.added().keySet() + " added, " + plan.removed().keySet() + " removed"
                + (plan.kept().isEmpty() ? "" : ", " + plan.kept().keySet() + " kept (where the Games places stand)")
                + ".");
        configSnapshotTaken = false;
        snapshotConfig(file);
        ConfigReset.apply(onDisk, bundled, path, keep);
        if (!saveTo(onDisk, file, "reset")) {
            out.add("&cCouldn't write config.yml — nothing changed. See the server log.");
            return out;
        }
        reloadAll();
        out.add("&aDone. A copy of the old config.yml is in backups/. Reloaded live — no restart needed.");
        if (ConfigReset.touchesQuests(path) && quests != null) {
            int cleared = quests.redrawCurrent();
            out.add("&aQuest pools changed: everyone draws today's and this week's quests again (" + cleared
                    + " draw rows cleared; progress kept).");
        }
        return out;
    }

    /** What a reset leaves as it is: where the Games places stand ({@link ConfigReset#kept}), for the dry run and the reset. */
    private static void keptLines(ConfigReset.Plan plan, java.util.List<String> out) {
        if (plan.kept().isEmpty()) {
            return;
        }
        out.add("&7Kept as they are, since they say where the Games places stand (a reset would move what is built"
                + " there; README \"Moving an area by hand\" moves one):");
        int shown = 0;
        for (java.util.Map.Entry<String, Object> e : plan.kept().entrySet()) {
            if (shown++ < 20) {
                out.add("&7= &f" + e.getKey() + "&7: " + (e.getValue() == null ? "not set" : ConfigReset.brief(e.getValue())));
            }
        }
        if (shown > 20) {
            out.add("&7…and " + (shown - 20) + " more.");
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

    /**
     * The nearest enclosing path of {@code key} that holds a single value (not a section) in
     * {@code c}, or {@code null}: the owner wrote a value where the bundled file has a section.
     */
    private static String scalarAncestor(org.bukkit.configuration.ConfigurationSection c, String key) {
        char sep = c.getRoot() == null ? '.' : c.getRoot().options().pathSeparator();
        for (int i = key.indexOf(sep); i > 0; i = key.indexOf(sep, i + 1)) {
            String path = key.substring(0, i);
            Object v = c.get(path, null);
            if (v == null) {
                return null; // missing here: nothing further down can be the owner's
            }
            if (!(v instanceof org.bukkit.configuration.ConfigurationSection)) {
                return path;
            }
        }
        return null;
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

    /**
     * Start the live market after the displays: load its rows, replay the ticks missed while the
     * server was off, publish, arm the pump. A failure leaves every price at its usual level.
     */
    private void startMarketSim() {
        if (marketSim == null) {
            return;
        }
        try {
            marketSim.start();
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "The live market could not start - prices stay at their usual level.", e);
            try {
                marketSim.stop(); // neutral from here on (M = 1.0); /hcm reload tries again
            } catch (RuntimeException ignored) {
                // it never got going
            }
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

    /** The wild hunt: naturally spawned wild Minis (the NATURAL_SPAWN trigger). */
    public com.dierks.homecraft.hunt.HuntService hunt() {
        return hunt;
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

    /** Arcade token balances, the login streak, playtime and the ledger. */
    public com.dierks.homecraft.arcade.TokenService tokens() {
        return tokens;
    }

    /** The +1 Home perk: Essentials home tiers granted through LuckPerms. */
    public com.dierks.homecraft.arcade.homes.HomeService homes() {
        return homes;
    }

    /** The Prize Counter: fixed-price token purchases, limits, and giving prizes. */
    public com.dierks.homecraft.arcade.PrizeService prizes() {
        return prizes;
    }

    /** Particle trails bought with tokens. */
    public com.dierks.homecraft.arcade.TrailService trails() {
        return trails;
    }

    /** The Mini Radar (hunt gear). */
    public com.dierks.homecraft.arcade.RadarService radar() {
        return radar;
    }

    /** Which day and week it is where the players live ({@code clock.time_zone}). */
    public com.dierks.homecraft.util.GameClock clock() {
        return config.clock();
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

    /** Sound Mufflers, or null if they failed to start. */
    public com.dierks.homecraft.muffler.SoundMufflerService soundMufflers() {
        return soundMufflers;
    }

    /** The Games framework, or null if it failed to start. */
    public com.dierks.homecraft.games.GamesService games() {
        return games;
    }

    /**
     * Take a break (limits and pauses on games of chance), or null before enable / after disable.
     * Callers that are about to take tokens for a game of chance refuse when it is null.
     */
    public com.dierks.homecraft.games.Breaks breaks() {
        return breaks;
    }

    public com.dierks.homecraft.courier.CourierService courier() {
        return courier;
    }

    /**
     * The live market (0.33): multipliers, badges, events, admin controls. {@code null} only if it
     * could not be built; callers null-check, and check {@code active()} before showing anything.
     */
    public com.dierks.homecraft.market.sim.MarketSimService marketSim() {
        return marketSim;
    }

    /** Market news delivery (broadcasts, catch-up, mute). {@code null} only if it could not be built. */
    public com.dierks.homecraft.market.sim.MarketNewsService marketNews() {
        return marketNews;
    }

    /** The SQLite datastore (the live market writes each tick through one of its transactions). */
    public Database database() {
        return database;
    }
}
