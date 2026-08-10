package com.dierks.homecraft.market;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.DailySellDao;
import com.dierks.homecraft.storage.MarketStateDao;
import com.dierks.homecraft.storage.PriceHistoryDao;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The finite, conserved commodities market. Each commodity has a real, positive
 * {@code stock} the market holds: <b>selling adds</b> to it, <b>buying subtracts</b>,
 * floored at 0. Price is a function of stock (empty ⇒ ceiling &amp; out of stock;
 * full ⇒ floor), split into a bid/ask by the configured spread. Money flows
 * through Vault; only item stock is finite. A per-player daily sell limit keeps
 * any one player from vacuuming the market.
 *
 * <p>This is the Amazon-market side only; QuickShop is untouched.
 */
public final class MarketService {

    /** Outcome of a buy/sell attempt. {@code ok=false} carries a player-facing {@code error}. */
    public record TradeResult(boolean ok, String error, int qty, double amount, double priceAfter, long stockAfter) {
        static TradeResult fail(String error) {
            return new TradeResult(false, error, 0, 0, 0, 0);
        }
    }

    /** Resolved daily allowance for a specific player (0 on an axis = unlimited). */
    private record Limits(boolean enforced, double maxMoney, long maxUnits) {
        static final Limits UNLIMITED = new Limits(false, 0, 0);
    }

    private static final long MS_PER_DAY = 86_400_000L;

    private final HomeCraftManagement plugin;
    private final MarketStateDao stateDao;
    private final DailySellDao dailyDao;
    private final PriceHistoryDao historyDao;
    private final EconomyService economy;

    private Map<String, MarketItem> catalog = new LinkedHashMap<>();
    private Map<String, MarketState> states = new LinkedHashMap<>();
    private PricingEngine engine = new PricingEngine(1.0, 0.2, 0.10);

    public MarketService(HomeCraftManagement plugin, MarketStateDao stateDao,
                         DailySellDao dailyDao, PriceHistoryDao historyDao, EconomyService economy) {
        this.plugin = plugin;
        this.stateDao = stateDao;
        this.dailyDao = dailyDao;
        this.historyDao = historyDao;
        this.economy = economy;
    }

    /**
     * (Re)load catalog + engine from config and reconcile persisted state:
     * existing items keep their stock/price; new or unseeded items are seeded to
     * their configured initial stock (with the stock-implied starting price).
     */
    public void reload() {
        PluginConfig.Market market = plugin.config().market();
        this.engine = new PricingEngine(market.elasticity(), market.inertia(), market.spread());

        Map<String, MarketItem> newCatalog = new LinkedHashMap<>();
        for (MarketItem item : market.catalog()) {
            newCatalog.put(item.id(), item);
        }
        this.catalog = newCatalog;

        Map<String, MarketState> loaded;
        try {
            loaded = stateDao.loadAll();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load market state: " + e.getMessage());
            loaded = new LinkedHashMap<>();
        }

        long now = System.currentTimeMillis();
        Map<String, MarketState> newStates = new LinkedHashMap<>();
        for (MarketItem item : catalog.values()) {
            MarketState state = loaded.get(item.id());
            if (state == null) {
                state = new MarketState(item.id(), 0, item.initialStock(), now);
                state.setCurrentPrice(engine.targetPrice(item, state.stock()));
                persist(state);
            } else if (!state.isSeeded()) {
                // Row carried forward from Phase 2 (sentinel stock = -1): seed it.
                state.setStock(item.initialStock());
                state.setCurrentPrice(engine.targetPrice(item, state.stock()));
                state.setUpdatedAt(now);
                persist(state);
            }
            newStates.put(item.id(), state);
        }
        this.states = newStates;
        plugin.getLogger().info("Market engine loaded " + catalog.size() + " commodity(ies).");
    }

    public Collection<MarketItem> catalog() {
        return catalog.values();
    }

    public MarketItem item(String id) {
        return catalog.get(id);
    }

    public MarketState state(String id) {
        return states.get(id);
    }

    /** Current mid price. */
    public double price(String id) {
        MarketState state = states.get(id);
        return state != null ? state.currentPrice() : Double.NaN;
    }

    public double buyPrice(String id) {
        return engine.buyPrice(price(id));
    }

    public double sellPrice(String id) {
        return engine.sellPrice(price(id));
    }

    // ---------------------------------------------------------------------

    public TradeResult buy(Player player, String id, int qty) {
        MarketItem item = catalog.get(id);
        if (item == null) {
            return TradeResult.fail("No market item '" + id + "'.");
        }
        if (!economy.isEnabled()) {
            return TradeResult.fail("The market is offline (no Vault economy).");
        }
        MarketState state = states.get(id);
        long stock = state.stock();
        if (stock <= 0) {
            return TradeResult.fail(item.label() + " is out of stock.");
        }

        int allowed = (int) Math.min(qty, stock);
        double unit = engine.buyPrice(state.currentPrice());
        double cost = unit * allowed;

        if (!economy.has(player, cost)) {
            return TradeResult.fail("You can't afford " + economy.format(cost) + " for " + allowed + ".");
        }
        if (!economy.withdraw(player, cost)) {
            return TradeResult.fail("Payment failed.");
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(item.material(), allowed));
        leftover.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));

        state.setStock(stock - allowed);   // BUY SUBTRACTS from market stock
        applyPrice(item, state);
        return new TradeResult(true, null, allowed, cost, state.currentPrice(), state.stock());
    }

    public TradeResult sell(Player player, String id, int qty) {
        MarketItem item = catalog.get(id);
        if (item == null) {
            return TradeResult.fail("No market item '" + id + "'.");
        }
        if (!economy.isEnabled()) {
            return TradeResult.fail("The market is offline (no Vault economy).");
        }
        MarketState state = states.get(id);

        int have = countMaterial(player, item.material());
        if (have <= 0) {
            return TradeResult.fail("You have no " + item.label() + " to sell.");
        }

        double unit = Math.max(0.0, engine.sellPrice(state.currentPrice()));
        int allowed = Math.min(qty, have);

        // Apply the daily anti-whale limit.
        Limits limits = resolveLimits(player);
        if (limits.enforced()) {
            long day = epochDay();
            try {
                if (limits.maxUnits() > 0) {
                    long remaining = limits.maxUnits() - dailyDao.unitsSold(player.getUniqueId(), day, id);
                    if (remaining <= 0) {
                        return TradeResult.fail(limitReachedMessage());
                    }
                    allowed = (int) Math.min(allowed, remaining);
                }
                if (limits.maxMoney() > 0 && unit > 0) {
                    double remaining = limits.maxMoney() - dailyDao.moneyEarned(player.getUniqueId(), day);
                    if (remaining <= 0) {
                        return TradeResult.fail(limitReachedMessage());
                    }
                    allowed = (int) Math.min(allowed, (long) Math.floor(remaining / unit));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read daily sell tally: " + e.getMessage());
            }
            if (allowed <= 0) {
                return TradeResult.fail(limitReachedMessage());
            }
        }

        if (!removeMaterial(player, item.material(), allowed)) {
            return TradeResult.fail("Could not take the items from your inventory.");
        }
        double proceeds = unit * allowed;
        if (!economy.deposit(player, proceeds)) {
            player.getInventory().addItem(new ItemStack(item.material(), allowed));
            return TradeResult.fail("Payout failed — your items were returned.");
        }

        state.setStock(state.stock() + allowed);   // SELL ADDS to market stock
        applyPrice(item, state);

        try {
            dailyDao.record(player.getUniqueId(), epochDay(), id, allowed, proceeds);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record daily sell tally: " + e.getMessage());
        }
        return new TradeResult(true, null, allowed, proceeds, state.currentPrice(), state.stock());
    }

    /** Record a price/stock snapshot for every commodity (periodic history). */
    public void snapshotHistory() {
        long now = System.currentTimeMillis();
        for (MarketItem item : catalog.values()) {
            MarketState state = states.get(item.id());
            if (state == null) {
                continue;
            }
            try {
                historyDao.record(item.id(), state.currentPrice(), state.stock(), now);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to snapshot price history for " + item.id() + ": " + e.getMessage());
            }
        }
    }

    /** Most recent price/stock snapshots for a commodity, newest first. */
    public List<PriceHistoryDao.Snapshot> recentHistory(String id, int limit) {
        try {
            return historyDao.recent(id, limit);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read price history: " + e.getMessage());
            return List.of();
        }
    }

    /** Hours (rounded up, min 1) until the UTC daily sell limit resets. */
    public long hoursUntilReset() {
        long now = System.currentTimeMillis();
        long nextMidnight = (epochDay() + 1) * MS_PER_DAY;
        return Math.max(1, (long) Math.ceil((nextMidnight - now) / 3_600_000.0));
    }

    // ---------------------------------------------------------------------

    private void applyPrice(MarketItem item, MarketState state) {
        state.setCurrentPrice(engine.nextPrice(item, state.currentPrice(), state.stock()));
        state.setUpdatedAt(System.currentTimeMillis());
        persist(state);
    }

    private void persist(MarketState state) {
        try {
            stateDao.save(state);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to persist market state for " + state.itemId() + ": " + e.getMessage());
        }
    }

    private Limits resolveLimits(Player player) {
        PluginConfig.SellLimits cfg = plugin.config().market().sellLimits();
        if (!cfg.enabled()) {
            return Limits.UNLIMITED;
        }
        if (cfg.bypassPermission() != null && !cfg.bypassPermission().isBlank()
                && player.hasPermission(cfg.bypassPermission())) {
            return Limits.UNLIMITED;
        }
        double maxMoney = cfg.maxMoneyPerDay();
        long maxUnits = cfg.maxUnitsPerDay();
        for (PluginConfig.RankLimit rank : cfg.ranks()) {
            if (player.hasPermission(rank.permission())) {
                maxMoney = moreGenerous(maxMoney, rank.maxMoneyPerDay());
                maxUnits = moreGenerous(maxUnits, rank.maxUnitsPerDay());
            }
        }
        // Both axes unlimited ⇒ nothing to enforce.
        if (maxMoney <= 0 && maxUnits <= 0) {
            return Limits.UNLIMITED;
        }
        return new Limits(true, maxMoney, maxUnits);
    }

    /** 0 means unlimited, which is the most generous; otherwise take the larger cap. */
    private static double moreGenerous(double a, double b) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        return Math.max(a, b);
    }

    private static long moreGenerous(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        return Math.max(a, b);
    }

    private String limitReachedMessage() {
        return "Daily sell limit reached — resets in ~" + hoursUntilReset() + "h.";
    }

    private long epochDay() {
        return System.currentTimeMillis() / MS_PER_DAY;
    }

    /** Count matching items across the player's 36 storage slots (by type; ignores name/enchants). */
    private int countMaterial(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Remove exactly {@code qty} of {@code material} from storage. Mirrors {@link #countMaterial}. */
    private boolean removeMaterial(Player player, Material material, int qty) {
        int remaining = qty;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int amount = stack.getAmount();
            if (amount <= remaining) {
                remaining -= amount;
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(amount - remaining);
                player.getInventory().setItem(i, stack);
                remaining = 0;
            }
        }
        return remaining == 0;
    }
}
