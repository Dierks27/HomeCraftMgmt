package com.dierks.homecraft.market;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.MarketStateDao;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The dynamic-market engine: owns the catalog + live per-item state, prices
 * trades through the {@link PricingEngine}, moves money through Vault, and
 * persists state after every trade.
 *
 * <p>Buying withdraws money, gives the item, and raises the price; selling takes
 * the item, deposits money, and lowers the price — bounded by each item's
 * floor/ceiling. This is the Amazon-market side only; QuickShop is untouched.
 */
public final class MarketService {

    /** Outcome of a buy/sell attempt. {@code ok=false} carries a player-facing {@code error}. */
    public record TradeResult(boolean ok, String error, int qty, double amount, double priceAfter) {
        static TradeResult fail(String error) {
            return new TradeResult(false, error, 0, 0, 0);
        }
    }

    private final HomeCraftManagement plugin;
    private final MarketStateDao dao;
    private final EconomyService economy;

    private Map<String, MarketItem> catalog = new LinkedHashMap<>();
    private Map<String, MarketState> states = new LinkedHashMap<>();
    private PricingEngine engine = new PricingEngine(0.05, 0.2);

    public MarketService(HomeCraftManagement plugin, MarketStateDao dao, EconomyService economy) {
        this.plugin = plugin;
        this.dao = dao;
        this.economy = economy;
    }

    /**
     * (Re)load the catalog + engine from config and reconcile persisted state:
     * existing items keep their price/demand; newly-added items start at base price.
     */
    public void reload() {
        PluginConfig.Market market = plugin.config().market();
        this.engine = new PricingEngine(market.elasticity(), market.inertia());

        Map<String, MarketItem> newCatalog = new LinkedHashMap<>();
        for (MarketItem item : market.catalog()) {
            newCatalog.put(item.id(), item);
        }
        this.catalog = newCatalog;

        Map<String, MarketState> loaded;
        try {
            loaded = dao.loadAll();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load market state: " + e.getMessage());
            loaded = new LinkedHashMap<>();
        }

        Map<String, MarketState> newStates = new LinkedHashMap<>();
        for (MarketItem item : catalog.values()) {
            MarketState state = loaded.get(item.id());
            if (state == null) {
                state = new MarketState(item.id(), item.basePrice(), 0L, System.currentTimeMillis());
                persist(state);
            }
            newStates.put(item.id(), state);
        }
        this.states = newStates;
        plugin.getLogger().info("Market engine loaded " + catalog.size() + " item(s).");
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

    public double price(String id) {
        MarketState state = states.get(id);
        return state != null ? state.currentPrice() : Double.NaN;
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
        double unit = state.currentPrice();
        double total = unit * qty;

        if (!economy.has(player, total)) {
            return TradeResult.fail("You can't afford " + economy.format(total) + ".");
        }
        if (!economy.withdraw(player, total)) {
            return TradeResult.fail("Payment failed.");
        }

        // Give the goods; anything that doesn't fit drops at the player's feet.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(item.material(), qty));
        leftover.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));

        applyTrade(item, state, qty); // demand up -> price up
        return new TradeResult(true, null, qty, total, state.currentPrice());
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
        if (have < qty) {
            return TradeResult.fail("You only have " + have + " " + item.label() + ".");
        }

        double unit = state.currentPrice();
        double total = unit * qty;

        if (!removeMaterial(player, item.material(), qty)) {
            return TradeResult.fail("Could not take the items from your inventory.");
        }

        if (!economy.deposit(player, total)) {
            // Refund the items so nothing is lost on a failed payout.
            player.getInventory().addItem(new ItemStack(item.material(), qty));
            return TradeResult.fail("Payout failed — your items were returned.");
        }

        applyTrade(item, state, -qty); // demand down -> price down
        return new TradeResult(true, null, qty, total, state.currentPrice());
    }

    // ---------------------------------------------------------------------

    private void applyTrade(MarketItem item, MarketState state, long demandDelta) {
        state.setDemand(state.demand() + demandDelta);
        state.setCurrentPrice(engine.nextPrice(item, state.currentPrice(), state.demand()));
        state.setUpdatedAt(System.currentTimeMillis());
        persist(state);
    }

    private void persist(MarketState state) {
        try {
            dao.save(state);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to persist market state for " + state.itemId() + ": " + e.getMessage());
        }
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

