package com.dierks.homecraft.market;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.DailyBuyDao;
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
 * <p><b>Live market (0.33).</b> {@link MarketState#currentPrice()} is the <em>balanced</em>
 * price: only trades and the admin stock commands move it. A {@link PriceMood} supplies a
 * multiplier {@code M} (held inside [0.75, 1.25]) applied whenever a price is quoted, so every
 * price a player sees, pays or is paid is {@code clamp(balanced × M, floor, ceiling)}, with
 * {@code M = 1} for an empty item and {@code max(M, 1)} for the unit a buy empties the shelf
 * with (so a round trip through the empty shelf can never profit). Orders are priced by
 * {@link OrderMath} with {@code M} frozen for the order. With {@link PriceMood#NEUTRAL} (no sim, or the sim off) every price, total and
 * cap is bit-for-bit 0.32's.
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

    /**
     * Resolved daily allowance for a specific player. {@code subject} is true when the
     * player is under the limit system at all (enabled and not bypassed) — even if both
     * global axes are 0/unlimited, because a per-item cap can still apply. 0 on an axis
     * means unlimited on that axis.
     */
    private record Limits(boolean subject, double maxMoney, long maxUnits) {
        static final Limits UNLIMITED = new Limits(false, 0, 0);
    }

    private static final long MS_PER_DAY = 86_400_000L;

    private final HomeCraftManagement plugin;
    private final MarketStateDao stateDao;
    private final DailySellDao dailyDao;
    private final DailyBuyDao buyDao;
    private final PriceHistoryDao historyDao;
    private final EconomyService economy;

    private Map<String, MarketItem> catalog = new LinkedHashMap<>();
    private Map<String, MarketState> states = new LinkedHashMap<>();
    private PricingEngine engine = new PricingEngine(1.0, 0.2, 0.10);
    /** The live market's multiplier and event caps; {@link PriceMood#NEUTRAL} until one is set. */
    private PriceMood mood = PriceMood.NEUTRAL;
    /** Bumped once per {@link #snapshotHistory()} run; main thread only. */
    private long historyVersion;

    public MarketService(HomeCraftManagement plugin, MarketStateDao stateDao,
                         DailySellDao dailyDao, DailyBuyDao buyDao, PriceHistoryDao historyDao,
                         EconomyService economy) {
        this.plugin = plugin;
        this.stateDao = stateDao;
        this.dailyDao = dailyDao;
        this.buyDao = buyDao;
        this.historyDao = historyDao;
        this.economy = economy;
    }

    /**
     * Plug in the live market (or {@code null} to take it out again, which is the same as
     * {@link PriceMood#NEUTRAL}). Takes effect on the next price read.
     */
    public void setMood(PriceMood mood) {
        this.mood = mood == null ? PriceMood.NEUTRAL : mood;
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
        int rebounded = 0;
        Map<String, MarketState> newStates = new LinkedHashMap<>();
        for (MarketItem item : catalog.values()) {
            MarketState state = loaded.get(item.id());
            if (state == null) {
                state = new MarketState(item.id(), 0, seedStock(item), now);
                state.setCurrentPrice(engine.targetPrice(item, state.stock()));
                persist(state);
            } else if (!state.isSeeded()) {
                // Row carried forward from Phase 2 (sentinel stock = -1): seed it.
                state.setStock(seedStock(item));
                state.setCurrentPrice(engine.targetPrice(item, state.stock()));
                state.setUpdatedAt(now);
                persist(state);
            } else if (rebound(item, state)) {
                // The admin moved floor/ceiling under a price cached from the OLD config.
                // Snap it back inside the new band and write it through — otherwise inertia
                // would glide from an illegal price forever (a $400 cache under a $96
                // ceiling never converges, it just decays toward it while showing 4x).
                state.setUpdatedAt(now);
                persist(state);
                rebounded++;
            }
            newStates.put(item.id(), state);
        }
        this.states = newStates;
        plugin.getLogger().info("Market engine loaded " + catalog.size() + " commodity(ies)."
                + (rebounded > 0 ? " Clamped " + rebounded + " price(s) back inside the configured floor/ceiling." : ""));
        logDepartments();
        try {
            mood.catalogChanged();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("The live market could not take the new catalog: " + e);
        }
    }

    /**
     * Log how the catalog splits across the department tabs the Store and Market browse by.
     * A department showing 0, or Misc swallowing a large share, means the classifier needs a
     * rule (or the admin an override) — with a few hundred commodities that is otherwise
     * invisible until someone opens a tab and finds it empty.
     */
    private void logDepartments() {
        if (catalog.isEmpty()) {
            return;
        }
        com.dierks.homecraft.marketplace.Categorizer cat =
                new com.dierks.homecraft.marketplace.Categorizer(plugin);
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String dept : plugin.config().marketplace().departments()) {
            counts.put(dept, 0);
        }
        for (MarketItem item : catalog.values()) {
            counts.merge(cat.department(item.material()), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Market departments:");
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        plugin.getLogger().info(sb.toString());

        // Mirrors Departments.MAX_TABS: the tab row is nine slots and "All" takes the first,
        // so a ninth department still sells but never gets a tab. Say so at load rather than
        // leaving an admin to notice their new department is unreachable.
        java.util.List<String> configured = plugin.config().marketplace().departments();
        if (configured.size() > 8) {
            plugin.getLogger().warning("marketplace.departments lists " + configured.size()
                    + " departments; the Store/Market tab row fits 8 beside \"All\". "
                    + String.join(", ", configured.subList(8, configured.size()))
                    + " will have no tab — their items still sell and still show under \"All\". "
                    + "Merge or drop a department to bring them back.");
        }
    }

    /** Starting stock for a fresh/unseeded item, held strictly below {@code full_stock}. */
    private static long seedStock(MarketItem item) {
        return Math.min(item.initialStock(), maxStock(item));
    }

    /**
     * Force a state's cached mid price inside its item's current floor/ceiling.
     * A non-finite price (corrupt row) is rebuilt from the stock curve instead.
     *
     * @return true if the price actually moved (caller should persist).
     */
    private boolean rebound(MarketItem item, MarketState state) {
        double current = state.currentPrice();
        double fixed = Double.isFinite(current)
                ? PricingEngine.clamp(current, item.floor(), item.ceiling())
                : engine.targetPrice(item, state.stock());
        if (fixed == current) {
            return false;
        }
        state.setCurrentPrice(fixed);
        return true;
    }

    /**
     * Write one catalog row to config.yml and bring the live catalog and state back into
     * sync. Mirrors {@code MiniService.saveCatalog}: the config write, the PluginConfig
     * re-parse, and the service rebuild are all three required, in that order.
     *
     * @return false when config.yml could not be read or written — NOTHING changed, and the
     *         caller must not report success
     */
    public boolean saveCatalogRow(MarketDraft draft) {
        if (!new com.dierks.homecraft.config.MarketCatalogWriter(plugin).upsert(draft)) {
            return false;
        }
        plugin.config().load();
        reload();
        return true;
    }

    /** Remove one catalog row from config.yml and rebuild. Same false semantics. */
    public boolean removeCatalogRow(String id) {
        if (!new com.dierks.homecraft.config.MarketCatalogWriter(plugin).remove(id)) {
            return false;
        }
        plugin.config().load();
        reload();
        return true;
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

    /**
     * Current mid price: the balanced price times the live market's multiplier, always inside
     * the configured floor/ceiling ({@code clamp(balanced × M, floor, ceiling)}, with {@code M = 1}
     * for an empty item). The stored value is clamped on reload, but we clamp on read too so a
     * stale cache can never be displayed (or charged) outside the band the admin configured.
     * With no live market this is exactly {@link #usualPrice}.
     */
    public double price(String id) {
        MarketState state = states.get(id);
        if (state == null) {
            return Double.NaN;
        }
        MarketItem item = catalog.get(id);
        if (item == null) {
            return state.currentPrice();
        }
        double m = state.stock() > 0 ? mood.multiplier(id) : 1.0;
        return OrderMath.mid(item, state.currentPrice(), state.stock(), m);
    }

    /**
     * The balanced price, before the live market's multiplier: what the item "usually" costs
     * at its current stock ({@code clamp(currentPrice, floor, ceiling)}). NaN for an unknown id.
     */
    public double usualPrice(String id) {
        MarketState state = states.get(id);
        if (state == null) {
            return Double.NaN;
        }
        MarketItem item = catalog.get(id);
        return item == null ? state.currentPrice()
                : PricingEngine.clamp(state.currentPrice(), item.floor(), item.ceiling());
    }

    /**
     * Changes whenever this item's price jumps (a news flash, a forced event, pause/resume).
     * A GUI that remembers it when it showed a quote refuses the confirm if it moved, so nobody
     * trades on a stale price. Always 0 with no live market.
     */
    public long quoteEpoch(String id) {
        return mood.jumpSeq(id);
    }

    /**
     * Ask price — clamped to the band, so a player never pays above the ceiling. It is the ask
     * of the next unit a buy would charge ({@link OrderMath#buyMid}): the ask of {@link #price}
     * everywhere, except that the last unit on the shelf is never discounted by the live market.
     * With no live market it is exactly the ask of {@link #price}.
     */
    public double buyPrice(String id) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return OrderMath.ask(engine, item, price(id));
        }
        double m = state.stock() > 0 ? mood.multiplier(id) : 1.0;
        return OrderMath.ask(engine, item, OrderMath.buyMid(item, state.currentPrice(), state.stock(), m));
    }

    /** Bid price — clamped to the band, so the market never pays below the floor. */
    public double sellPrice(String id) {
        return OrderMath.bid(engine, catalog.get(id), price(id));
    }

    /**
     * Whether a refusal at the live market's event cap may name the event (the sale, or the
     * price being up): only once players can see it ({@link PriceMood#eventCapShown}). While it
     * is still unannounced — a HOT/DEAL's silent ramp — the cap binds all the same, but the
     * refusal is the player's own daily-limit message when their limits refuse too (word for
     * word what they would get with nothing running), else the plain daily-limit wording at
     * the event cap. A mood that throws is read as "not shown".
     */
    private boolean eventCapShown(MarketItem item, boolean sell) {
        try {
            return mood.eventCapShown(item, sell);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("The live market could not say whether an event on " + item.id()
                    + " is showing: " + e);
            return false;
        }
    }

    /**
     * The multiplier an order on this item runs at, read once and frozen for the whole order:
     * the mood's {@code M}, held inside the hard [0.75, 1.25] band whatever the mood says.
     */
    private double multiplier(String id) {
        return OrderMath.clampMultiplier(mood.multiplier(id));
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
        if (!plugin.sandbox().check(player, "market buy " + id)) {
            return TradeResult.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
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

        // While a DEAL or DOWN runs on this item, the live market caps how many units anyone may
        // buy today — bypass holders included. The tally exists for everyone: record() below is
        // unconditional. The refusal names the sale only once players can see it; during a DEAL's
        // silent ramp it reads exactly as the plain daily limit (see eventCapShown).
        long eventCap = Math.max(0L, mood.eventBuyCap(item));
        long eventLeft = Long.MAX_VALUE;
        long eventDone = 0;
        if (eventCap > 0) {
            try {
                eventDone = buyDao.unitsBought(player.getUniqueId(), epochDay(), id);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read daily buy tally: " + e.getMessage());
            }
            eventLeft = eventCap - eventDone;
            if (eventLeft <= 0 && eventCapShown(item, false)) {
                return TradeResult.fail("&eSale limit: you can buy &f" + eventCap + " " + item.label()
                        + " &ea day while it's on sale. &7Resets in ~" + hoursUntilReset() + "h.");
            }
        }

        // Resolve the daily anti-drain allowance (money spent + units) for this player,
        // combining the global buy cap with this item's optional per-item buy cap.
        Limits limits = resolveBuyLimits(player);
        boolean enforced = limits.subject();
        double remainingMoney = 0;
        long remainingUnits = 0;
        long unitCap = 0;
        if (enforced) {
            long day = epochDay();
            try {
                unitCap = tighter(limits.maxUnits(), item.maxDailyBuy());
                if (unitCap > 0) {
                    long bought = buyDao.unitsBought(player.getUniqueId(), day, id);
                    remainingUnits = unitCap - bought;
                    if (remainingUnits <= 0) {
                        return TradeResult.fail(unitsCappedMessage(true, bought, unitCap, item));
                    }
                }
                if (limits.maxMoney() > 0) {
                    remainingMoney = limits.maxMoney() - buyDao.moneySpent(player.getUniqueId(), day);
                    if (remainingMoney <= 0) {
                        return TradeResult.fail(moneyCappedMessage(true));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read daily buy tally: " + e.getMessage());
                enforced = false;
            }
        }
        if (eventCap > 0 && eventLeft <= 0) {
            // An event nobody can see yet, and the player's own limits let them through.
            return TradeResult.fail(unitsCappedMessage(true, eventDone, eventCap, item));
        }

        // Integrate the price across the order: each unit costs a little more as stock
        // drops, so the total is the area under the rising price curve — stopping at
        // whatever the daily buy limit allows. The live market's multiplier is frozen here
        // for the whole order.
        double m = multiplier(id);
        double startBase = state.currentPrice();
        long startStock = state.stock();
        OrderMath.Plan plan = OrderMath.buy(engine, item, startBase, startStock, qty,
                orderLimits(enforced, limits.maxMoney(), remainingMoney, unitCap, remainingUnits,
                        eventCap, eventLeft), m);
        if (plan.filled() <= 0) {
            return TradeResult.fail(enforced ? moneyCappedMessage(true) : item.label() + " is out of stock.");
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

        commit(item, state, plan);   // BUY SUBTRACTS from market stock; price ends where the order ended

        try {
            buyDao.record(player.getUniqueId(), epochDay(), id, plan.filled(), plan.total());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record daily buy tally: " + e.getMessage());
        }
        recordMood(item, false, startBase, startStock, plan, m);
        return new TradeResult(true, null, plan.filled(), plan.total(), price(id), state.stock());
    }

    public TradeResult sell(Player player, String id, int qty) {
        if (!plugin.sandbox().check(player, "market sell " + id)) {
            return TradeResult.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
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
            // Holding only Arcade prizes made of this material is a different answer from holding none.
            return TradeResult.fail(holdsPrizeOf(player, item.material())
                    ? com.dierks.homecraft.util.TokenPrizes.REFUSAL : "You have no " + item.label() + " to sell.");
        }

        // While a HOT or UP runs on this item, the live market caps how many units anyone may
        // sell today — bypass holders included. The tally exists for everyone: record() below is
        // unconditional. The refusal names the rise only once players can see it; during a HOT's
        // silent ramp it reads exactly as the plain daily limit (see executeBuy).
        long eventCap = Math.max(0L, mood.eventSellCap(item));
        long eventLeft = Long.MAX_VALUE;
        long eventDone = 0;
        if (eventCap > 0) {
            try {
                eventDone = dailyDao.unitsSold(player.getUniqueId(), epochDay(), id);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read daily sell tally: " + e.getMessage());
            }
            eventLeft = eventCap - eventDone;
            if (eventLeft <= 0 && eventCapShown(item, true)) {
                return TradeResult.fail("&eCrate buys up to &f" + eventCap + " " + item.label()
                        + " &ea day while the price is up. &7Resets in ~" + hoursUntilReset() + "h.");
            }
        }

        // Resolve the daily anti-whale allowance (money + units) for this player,
        // combining the global sell cap with this item's optional per-item sell cap.
        Limits limits = resolveSellLimits(player);
        boolean enforced = limits.subject();
        double remainingMoney = 0;
        long remainingUnits = 0;
        long unitCap = 0;
        if (enforced) {
            long day = epochDay();
            try {
                unitCap = tighter(limits.maxUnits(), item.maxDailySell());
                if (unitCap > 0) {
                    long sold = dailyDao.unitsSold(player.getUniqueId(), day, id);
                    remainingUnits = unitCap - sold;
                    if (remainingUnits <= 0) {
                        return TradeResult.fail(unitsCappedMessage(false, sold, unitCap, item));
                    }
                }
                if (limits.maxMoney() > 0) {
                    remainingMoney = limits.maxMoney() - dailyDao.moneyEarned(player.getUniqueId(), day);
                    if (remainingMoney <= 0) {
                        return TradeResult.fail(moneyCappedMessage(false));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read daily sell tally: " + e.getMessage());
                enforced = false;
            }
        }
        if (eventCap > 0 && eventLeft <= 0) {
            // An event nobody can see yet, and the player's own limits let them through.
            return TradeResult.fail(unitsCappedMessage(false, eventDone, eventCap, item));
        }

        // Integrate the price across the order (earn a little less per unit as
        // stock rises), stopping at whatever the daily limit allows. The live market's
        // multiplier is frozen here for the whole order.
        double m = multiplier(id);
        double startBase = state.currentPrice();
        long startStock = state.stock();
        OrderMath.Plan plan = OrderMath.sell(engine, item, startBase, startStock, Math.min(qty, have),
                orderLimits(enforced, limits.maxMoney(), remainingMoney, unitCap, remainingUnits,
                        eventCap, eventLeft), m);
        if (plan.filled() <= 0) {
            return TradeResult.fail(enforced ? moneyCappedMessage(false)
                    : "You have no " + item.label() + " to sell.");
        }

        if (!removeMaterial(player, item.material(), plan.filled())) {
            return TradeResult.fail("Could not take the items from your inventory.");
        }
        if (!economy.deposit(player, plan.total())) {
            player.getInventory().addItem(new ItemStack(item.material(), plan.filled()));
            return TradeResult.fail("Payout failed — your items were returned.");
        }

        commit(item, state, plan);   // SELL ADDS to market stock

        try {
            dailyDao.record(player.getUniqueId(), epochDay(), id, plan.filled(), plan.total());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record daily sell tally: " + e.getMessage());
        }
        recordMood(item, true, startBase, startStock, plan, m);
        if (plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_sale");
            plugin.achievements().checkBalance(player);
        }
        if (plugin.quests() != null) {
            plugin.quests().record(player,
                    com.dierks.homecraft.config.PluginConfig.QuestType.SELL_MARKET, (long) plan.total());
        }
        return new TradeResult(true, null, plan.filled(), plan.total(), price(id), state.stock());
    }

    /**
     * A previewed order: how many units, the integrated total, the displayed price it leaves
     * ({@code endPrice}, the live market's multiplier included), the stock it leaves, and the
     * balanced price it leaves ({@code endBase}). With no live market {@code endBase == endPrice}.
     */
    public record Plan(int filled, double total, double endPrice, long endStock, double endBase) {

        /** The 0.32 shape: with no live market the balanced and displayed end prices are one. */
        public Plan(int filled, double total, double endPrice, long endStock) {
            this(filled, total, endPrice, endStock, endPrice);
        }

        static Plan from(OrderMath.Plan p) {
            return new Plan(p.filled(), p.total(), p.endPrice(), p.endStock(), p.endBase());
        }
    }

    /**
     * Preview the cost of buying up to {@code qty} without mutating anything (for GUIs). It runs
     * the same {@link OrderMath} as the trade, at the same multiplier, so within a tick the quote
     * is what the trade charges (daily limits aside).
     */
    public Plan quoteBuy(String id, int qty) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return new Plan(0, 0, Double.NaN, 0);
        }
        return Plan.from(OrderMath.buy(engine, item, state.currentPrice(), state.stock(), qty,
                OrderMath.Limits.NONE, multiplier(id)));
    }

    /** Preview the proceeds of selling up to {@code qty} (ignoring daily limits) for GUIs. */
    public Plan quoteSell(String id, int qty) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return new Plan(0, 0, Double.NaN, 0);
        }
        return Plan.from(OrderMath.sell(engine, item, state.currentPrice(), state.stock(), qty,
                OrderMath.Limits.NONE, multiplier(id)));
    }

    /**
     * The allowance an order runs under: the player's daily limits (when {@code enforced}) combined
     * with the live market's event cap (which binds bypass holders too). The money cap stays a
     * non-bypass-only rule. With no event cap this is exactly the 0.32 allowance.
     */
    private static OrderMath.Limits orderLimits(boolean enforced, double maxMoney, double remainingMoney,
                                                long unitCap, long remainingUnits,
                                                long eventCap, long eventLeft) {
        boolean limited = enforced || eventCap > 0;
        long maxUnits = enforced ? tighter(unitCap, eventCap) : eventCap;
        long leftUnits = Math.min(enforced && unitCap > 0 ? remainingUnits : Long.MAX_VALUE,
                eventCap > 0 ? eventLeft : Long.MAX_VALUE);
        return new OrderMath.Limits(limited, enforced ? maxMoney : 0, remainingMoney, maxUnits, leftUnits);
    }

    /**
     * Apply a plan's resulting stock + balanced price to the state and persist (price re-clamped).
     * The balanced price is {@code endBase}: the live market's multiplier is never written back.
     */
    private void commit(MarketItem item, MarketState state, OrderMath.Plan plan) {
        state.setStock(plan.endStock());
        state.setCurrentPrice(PricingEngine.clamp(plan.endBase(), item.floor(), item.ceiling()));
        state.setUpdatedAt(System.currentTimeMillis());
        persist(state);
    }

    /**
     * Tell the live market's ledger about a trade made at a moved price: what it came to, and what
     * the same units would have come to at {@code M = 1}. A trade at exactly {@code M = 1} is not
     * reported (it is 0.32's trade, and the ledger would record no difference). Runs after the
     * money and goods moved, so a failure here is logged and the trade stands.
     */
    private void recordMood(MarketItem item, boolean sell, double startBase, long startStock,
                            OrderMath.Plan plan, double m) {
        if (m == 1.0 || plan.filled() <= 0) {
            return;
        }
        try {
            double neutral = OrderMath.neutralTotal(engine, item, sell, startBase, startStock, plan.filled());
            mood.onTrade(item.id(), sell, plan.filled(), plan.total(), neutral);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("The live market ledger missed a " + (sell ? "sale" : "purchase")
                    + " of " + item.id() + ": " + e);
        }
    }

    // ---------------------------------------------------------------------
    //  Admin stock management — apply a new economy design to a live database
    // ---------------------------------------------------------------------

    /**
     * Outcome of an admin stock write. {@code price} is the displayed price afterwards
     * ({@link MarketService#price(String)}); {@code capped} = the request was clamped below full_stock.
     */
    public record StockResult(boolean ok, String error, long stock, double price, boolean capped) {
        static StockResult fail(String error) {
            return new StockResult(false, error, 0, 0, false);
        }
    }

    /**
     * The highest stock an item may actually hold: one unit below {@code full_stock}.
     *
     * <p>{@code full_stock} is the <em>denominator of the price curve</em>, not a target to
     * reach — the market must always keep room for players to sell into, or an item silently
     * becomes sell-only-at-floor with no headroom at all.
     */
    public static long maxStock(MarketItem item) {
        return Math.max(0L, item.fullStock() - 1);
    }

    /**
     * Reset one commodity to its configured {@code initial_stock} <em>and</em> recompute its
     * price from the curve at that stock level.
     *
     * <p>Resetting stock alone is not enough: the inertia system would keep the old cached
     * price and only glide toward the new curve, so a redesigned item would show a stale
     * price for hours. A reset means <b>stock → curve price → both written through</b>.
     */
    public StockResult resetStock(String id) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return StockResult.fail("No market item '" + id + "'.");
        }
        long stock = Math.min(item.initialStock(), maxStock(item));
        state.setStock(stock);
        state.setCurrentPrice(engine.targetPrice(item, stock));   // snap, don't glide
        state.setUpdatedAt(System.currentTimeMillis());
        persist(state);
        return new StockResult(true, null, state.stock(), price(id),
                stock < item.initialStock());
    }

    /** Reset every commodity to its configured initial stock + curve price. @return items reset. */
    public int resetAllStock() {
        int count = 0;
        for (MarketItem item : catalog.values()) {
            if (resetStock(item.id()).ok()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Manually set one commodity's stock, snapping its price to the curve for that level.
     * Refuses to seat stock at or above {@code full_stock} — the request is capped at
     * {@code full_stock - 1} and reported back as {@code capped}.
     */
    public StockResult setStock(String id, long amount) {
        MarketItem item = catalog.get(id);
        MarketState state = states.get(id);
        if (item == null || state == null) {
            return StockResult.fail("No market item '" + id + "'.");
        }
        if (amount < 0) {
            return StockResult.fail("Stock cannot be negative.");
        }
        long cap = maxStock(item);
        long stock = Math.min(amount, cap);
        state.setStock(stock);
        state.setCurrentPrice(engine.targetPrice(item, stock));
        state.setUpdatedAt(System.currentTimeMillis());
        persist(state);
        return new StockResult(true, null, state.stock(), price(id), stock < amount);
    }

    /**
     * Record a price/stock snapshot for every commodity (periodic history; the displayed price,
     * so the charts and the 24h trend show what players saw), then prune
     * snapshots older than {@code market.price_history.keep_days} (0 keeps everything), at most
     * {@link PriceHistoryDao#PRUNE_BATCH} rows per run so a first prune of a big old table
     * can't stall the tick. Bumps {@link #historyVersion()}.
     */
    public void snapshotHistory() {
        long now = System.currentTimeMillis();
        for (MarketItem item : catalog.values()) {
            MarketState state = states.get(item.id());
            if (state == null) {
                continue;
            }
            try {
                historyDao.record(item.id(), price(item.id()), state.stock(), now);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to snapshot price history for " + item.id() + ": " + e.getMessage());
            }
        }
        int keepDays = plugin.config().market().priceHistoryKeepDays();
        if (keepDays > 0) {
            try {
                historyDao.pruneBefore(PriceHistoryDao.keepCutoff(now, keepDays), PriceHistoryDao.PRUNE_BATCH);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to prune old price history: " + e.getMessage());
            }
        }
        historyVersion++;
    }

    /**
     * Goes up by one every {@link #snapshotHistory()} run, so a reader that caches something
     * built from the history (the dashboard's 7- and 30-day charts) can tell when it may be
     * stale. Main thread only.
     */
    public long historyVersion() {
        return historyVersion;
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
     * A commodity's history thinned to one point per epoch-aligned {@code bucketMs} bucket (the
     * latest snapshot in each) from {@code fromInclusive} to {@code toInclusive}, oldest first.
     * See {@link PriceHistoryDao#sampled}.
     */
    public List<PriceHistoryDao.Snapshot> sampledHistory(String id, long fromInclusive, long toInclusive,
                                                         long bucketMs) {
        try {
            return historyDao.sampled(id, fromInclusive, toInclusive, bucketMs);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read sampled price history: " + e.getMessage());
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

    /** Hours (rounded up, min 1) until the daily limits reset at local midnight. */
    public long hoursUntilReset() {
        return Math.max(1, (long) Math.ceil(plugin.clock().msUntilNextDay() / 3_600_000.0));
    }

    // ---------------------------------------------------------------------

    private void persist(MarketState state) {
        try {
            stateDao.save(state);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to persist market state for " + state.itemId() + ": " + e.getMessage());
        }
    }

    private Limits resolveSellLimits(Player player) {
        PluginConfig.SellLimits cfg = plugin.config().market().sellLimits();
        return resolveLimits(player, cfg.enabled(), cfg.bypassPermission(),
                cfg.maxMoneyPerDay(), cfg.maxUnitsPerDay(), cfg.ranks());
    }

    private Limits resolveBuyLimits(Player player) {
        PluginConfig.BuyLimits cfg = plugin.config().market().buyLimits();
        return resolveLimits(player, cfg.enabled(), cfg.bypassPermission(),
                cfg.maxMoneyPerDay(), cfg.maxUnitsPerDay(), cfg.ranks());
    }

    /**
     * Resolve a player's daily allowance from a limits config. Returns {@code subject=false}
     * only when the limit system is disabled or the player holds the bypass permission;
     * otherwise {@code subject=true} even if both global axes are unlimited, so an
     * item's per-item cap can still be applied by the caller. The most generous rank wins.
     */
    private Limits resolveLimits(Player player, boolean enabled, String bypass,
                                double baseMoney, long baseUnits, List<PluginConfig.RankLimit> ranks) {
        if (!enabled) {
            return Limits.UNLIMITED;
        }
        if (bypass != null && !bypass.isBlank() && player.hasPermission(bypass)) {
            return Limits.UNLIMITED;
        }
        double maxMoney = baseMoney;
        long maxUnits = baseUnits;
        for (PluginConfig.RankLimit rank : ranks) {
            if (player.hasPermission(rank.permission())) {
                maxMoney = moreGenerous(maxMoney, rank.maxMoneyPerDay());
                maxUnits = moreGenerous(maxUnits, rank.maxUnitsPerDay());
            }
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

    /** The stricter of two unit caps (0 = unlimited); 0 only when both are unlimited. */
    private static long tighter(long a, long b) {
        if (a <= 0) {
            return Math.max(b, 0);
        }
        if (b <= 0) {
            return a;
        }
        return Math.min(a, b);
    }

    /** "You've bought/sold N/CAP <Item> today (daily limit) — resets in ~Xh." */
    private String unitsCappedMessage(boolean buy, long done, long cap, MarketItem item) {
        return "You've " + (buy ? "bought" : "sold") + " " + done + "/" + cap + " " + item.label()
                + " &7today (daily limit) — resets in ~" + hoursUntilReset() + "h.";
    }

    /** The money-axis daily cap message (spending for buys, earning for sells). */
    private String moneyCappedMessage(boolean buy) {
        return "Daily " + (buy ? "spending" : "earning") + " limit reached — resets in ~"
                + hoursUntilReset() + "h.";
    }

    /**
     * The day the daily buy/sell limits count against: a LOCAL day ({@code clock.time_zone}), the
     * same one the streak and quests use. It was a UTC day, which rolled over at 7 PM Central —
     * mid-evening, while the family plays.
     */
    private long epochDay() {
        return plugin.clock().dayKey();
    }

    /**
     * Count matching items across the player's 36 storage slots (by type; ignores name/enchants),
     * leaving out Arcade prizes: a Speed Boost made of sugar is not sugar the market can buy.
     */
    private int countMaterial(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material && !com.dierks.homecraft.util.TokenPrizes.carries(stack)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static boolean holdsPrizeOf(Player player, Material material) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material && com.dierks.homecraft.util.TokenPrizes.carries(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Remove exactly {@code qty} of {@code material} from storage. Mirrors {@link #countMaterial}. */
    private boolean removeMaterial(Player player, Material material, int qty) {
        int remaining = qty;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material || com.dierks.homecraft.util.TokenPrizes.carries(stack)) {
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
