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
        return executeBuy(player, id, qty, true);
    }

    /** Buy for an Amazon order: charge + consume stock now, but deliver the goods later. */
    public TradeResult purchaseForOrder(Player player, String id, int qty) {
        return executeBuy(player, id, qty, false);
    }

    private TradeResult executeBuy(Player player, String id, int qty, boolean deliverNow) {
        MarketItem item = catalog.get(id);
        if (item == null) {
            return TradeResult.fail("No market item '" + id + "'.");
        }
        if (!economy.isEnabled()) {
            return TradeResult.fail("The market is offline (no Vault economy).");
        }
        MarketState state = states.get(id);
        if (state.stock() <= 0) {
            return TradeResult.fail(item.label() + " is out of stock.");
        }

        // Integrate the price across the order: each unit costs a little more as
        // stock drops, so the total is the area under the rising price curve.
        Plan plan = simulateBuy(item, state.currentPrice(), state.stock(), qty);
        if (plan.filled() <= 0) {
            return TradeResult.fail(item.label() + " is out of stock.");
        }
        if (!economy.has(player, plan.total())) {
            return TradeResult.fail("You can't afford " + economy.format(plan.total())
                    + " for " + plan.filled() + " " + item.label() + ".");
        }
        if (!economy.withdraw(player, plan.total())) {
            return TradeResult.fail("Payment failed.");
        }

        if (deliverNow) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(item.material(), plan.filled()));
            leftover.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }

        commit(state, plan);   // BUY SUBTRACTS from market stock; price ends where the order ended
        return new TradeResult(true, null, plan.filled(), plan.total(), state.currentPrice(), state.stock());
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

        // Resolve the daily anti-whale allowance (money + units) for this player.
        Limits limits = resolveLimits(player);
        boolean enforced = limits.enforced();
        double remainingMoney = 0;
        long remainingUnits = 0;
        if (enforced) {
            long day = epochDay();
            try {
                if (limits.maxUnits() > 0) {
                    remainingUnits = limits.maxUnits() - dailyDao.unitsSold(player.getUniqueId(), day, id);
                    if (remainingUnits <= 0) {
                        return TradeResult.fail(limitReachedMessage());
                    }
                }
                if (limits.maxMoney() > 0) {
                    remainingMoney = limits.maxMoney() - dailyDao.moneyEarned(player.getUniqueId(), day);
                    if (remainingMoney <= 0) {
                        return TradeResult.fail(limitReachedMessage());
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read daily sell tally: " + e.getMessage());
                enforced = false;
            }
        }

        // Integrate the price across the order (earn a little less per unit as
        // stock rises), stopping at whatever the daily limit allows.
        Plan plan = simulateSell(item, state.currentPrice(), state.stock(),
                Math.min(qty, have), enforced, limits.maxMoney(), remainingMoney, limits.maxUnits(), remainingUnits);
        if (plan.filled() <= 0) {
            return TradeResult.fail(enforced ? limitReachedMessage()
                    : "You have no " + item.label() + " to sell.");
        }

        if (!removeMaterial(player, item.material(), plan.filled())) {
            return TradeResult.fail("Could not take the items from your inventory.");
        }
        if (!economy.deposit(player, plan.total())) {
            player.getInventory().addItem(new ItemStack(item.material(), plan.filled()));
            return TradeResult.fail("Payout failed — your items were returned.");
        }

        commit(state, plan);   // SELL ADDS to market stock

        try {
            dailyDao.record(player.getUniqueId(), epochDay(), id, plan.filled(), plan.total());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record daily sell tally: " + e.getMessage());
        }
        if (plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_sale");
            plugin.achievements().checkBalance(player);
        }
        if (plugin.quests() != null) {
            plugin.quests().record(player,
                    com.dierks.homecraft.config.PluginConfig.QuestType.SELL_MARKET, (long) plan.total());
        }
        return new TradeResult(true, null, plan.filled(), plan.total(), state.currentPrice(), state.stock());
    }

    /** A previewed/executed order: how many units, the integrated total, and the resulting price/stock. */
    public record Plan(int filled, double total, double endPrice, long endStock) {
    }

    /** Preview the cost of buying up to {@code qty} without mutating anything (for GUIs). */
    public Plan quoteBuy(String id, int qty) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return new Plan(0, 0, Double.NaN, 0);
        }
        return simulateBuy(item, state.currentPrice(), state.stock(), qty);
    }

    /** Preview the proceeds of selling up to {@code qty} (ignoring daily limits) for GUIs. */
    public Plan quoteSell(String id, int qty) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return new Plan(0, 0, Double.NaN, 0);
        }
        return simulateSell(item, state.currentPrice(), state.stock(), qty, false, 0, 0, 0, 0);
    }

    private Plan simulateBuy(MarketItem item, double startPrice, long startStock, int want) {
        long stock = startStock;
        double price = startPrice;
        double total = 0;
        int filled = 0;
        int cap = (int) Math.max(0, Math.min(want, stock));
        for (; filled < cap; filled++) {
            total += engine.buyPrice(price);
            stock -= 1;
            price = engine.nextPrice(item, price, stock);
        }
        return new Plan(filled, total, price, stock);
    }

    private Plan simulateSell(MarketItem item, double startPrice, long startStock, int want,
                             boolean limited, double maxMoney, double remainingMoney,
                             long maxUnits, long remainingUnits) {
        long stock = startStock;
        double price = startPrice;
        double total = 0;
        int filled = 0;
        int cap = Math.max(0, want);
        if (limited && maxUnits > 0) {
            cap = (int) Math.min(cap, remainingUnits);
        }
        for (; filled < cap; filled++) {
            double unit = Math.max(0.0, engine.sellPrice(price));
            if (limited && maxMoney > 0 && total + unit > remainingMoney) {
                break; // this unit would exceed the daily earning cap
            }
            total += unit;
            stock += 1;
            price = engine.nextPrice(item, price, stock);
        }
        return new Plan(filled, total, price, stock);
    }

    /** Apply a plan's resulting stock + price to the state and persist. */
    private void commit(MarketState state, Plan plan) {
        state.setStock(plan.endStock());
        state.setCurrentPrice(plan.endPrice());
        state.setUpdatedAt(System.currentTimeMillis());
        persist(state);
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

    /**
     * Percent change of the current price vs. ~24h ago — the basis of every display's
     * trend arrow. Compares to the oldest snapshot still within the last 24h (or the
     * earliest snapshot we have, if all are recent). Returns 0 with no history.
     */
    public double change24h(String id) {
        List<PriceHistoryDao.Snapshot> history = recentHistory(id, 96); // newest-first
        if (history.isEmpty()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - MS_PER_DAY;
        // history is newest→oldest; the last element still ≥ cutoff is the ~24h-ago base.
        double base = history.get(history.size() - 1).price(); // earliest available
        for (PriceHistoryDao.Snapshot s : history) {
            if (s.recordedAt() >= cutoff) {
                base = s.price();
            }
        }
        if (base <= 0) {
            return 0;
        }
        return (price(id) - base) / base * 100.0;
    }

    /** Hours (rounded up, min 1) until the UTC daily sell limit resets. */
    public long hoursUntilReset() {
        long now = System.currentTimeMillis();
        long nextMidnight = (epochDay() + 1) * MS_PER_DAY;
        return Math.max(1, (long) Math.ceil((nextMidnight - now) / 3_600_000.0));
    }

    // ---------------------------------------------------------------------

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
