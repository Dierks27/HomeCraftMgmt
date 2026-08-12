package com.dierks.homecraft.integration;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.display.Trend;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.order.Order;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.MiniDao;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The {@code hcm} PlaceholderAPI expansion (§9.5) — the single data feed that
 * powers TAB, holograms, and sign boards. Read-only: it exposes live market
 * price/stock/trend, Mini mint/circulation counts, and the requesting player's
 * next delivery. Unknown commodities/Minis resolve to {@code N/A} (never an error).
 *
 * <p>This class is only loaded and registered when PlaceholderAPI is installed
 * (see {@link HomeCraftManagement#onEnable()}), so referencing PAPI types here is
 * safe on servers without it.
 *
 * <p>Placeholders:
 * <ul>
 *   <li>{@code %hcm_price_<item>%} — current price (formatted)</li>
 *   <li>{@code %hcm_stock_<item>%} / {@code %hcm_maxstock_<item>%}</li>
 *   <li>{@code %hcm_trend_<item>%} — ▲/▼/▬ + 24h percent</li>
 *   <li>{@code %hcm_mini_minted_<id>%} / {@code %hcm_mini_circulation_<id>%}</li>
 *   <li>{@code %hcm_order_status%} — the player's next delivery + ETA</li>
 * </ul>
 */
public final class HcmPlaceholders extends PlaceholderExpansion {

    private final HomeCraftManagement plugin;

    public HcmPlaceholders(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "hcm";
    }

    @Override
    public String getAuthor() {
        return "Dierks27";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // keep registered across PAPI reloads
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }
        String p = params.toLowerCase(java.util.Locale.ROOT);

        if (p.equals("order_status")) {
            return orderStatus(player);
        }
        if (p.startsWith("price_")) {
            return priceOf(p.substring("price_".length()));
        }
        if (p.startsWith("maxstock_")) {
            MarketItem item = plugin.market().item(p.substring("maxstock_".length()));
            return item != null ? Long.toString(item.fullStock()) : "N/A";
        }
        if (p.startsWith("stock_")) {
            MarketState state = plugin.market().state(p.substring("stock_".length()));
            return state != null ? Long.toString(state.stock()) : "N/A";
        }
        if (p.startsWith("trend_")) {
            String id = p.substring("trend_".length());
            if (plugin.market().item(id) == null) {
                return "N/A";
            }
            return Trend.label(plugin.market().change24h(id));
        }
        if (p.startsWith("mini_minted_")) {
            return miniCount(p.substring("mini_minted_".length()), false);
        }
        if (p.startsWith("mini_circulation_")) {
            return miniCount(p.substring("mini_circulation_".length()), true);
        }
        return null; // not ours — let PAPI leave the token as-is
    }

    private String priceOf(String id) {
        MarketItem item = plugin.market().item(id);
        if (item == null) {
            return "N/A";
        }
        return plugin.economy().format(plugin.market().price(id));
    }

    private String miniCount(String id, boolean circulation) {
        MiniService minis = plugin.miniService();
        if (minis == null || !minis.idExists(id)) {
            return "N/A";
        }
        MiniDao.Counts c = minis.counts(id);
        long value = circulation ? Math.max(0, c.minted() - c.destroyed()) : c.minted();
        return Long.toString(value);
    }

    private String orderStatus(OfflinePlayer player) {
        if (!(player instanceof Player online)) {
            return "N/A";
        }
        long now = System.currentTimeMillis();
        String ready = null;                 // a ready-to-collect delivery wins outright
        String soonest = null;               // otherwise the soonest in-transit one
        long soonestEta = Long.MAX_VALUE;

        // Crate store orders (delivered to the Locker).
        for (Order order : plugin.orderService().ordersFor(online)) {
            MarketItem item = plugin.market().item(order.itemId());
            String desc = order.qty() + "x " + strip(item != null ? item.label() : order.itemId());
            if (order.status() == Order.Status.READY) {
                if (ready == null) {
                    ready = "Ready: " + desc;
                }
            } else {
                long eta = order.deliverAt() - now;
                if (eta < soonestEta) {
                    soonestEta = eta;
                    soonest = desc + " in " + Menus.duration(eta);
                }
            }
        }

        // Marketplace / Mailbox deliveries.
        if (plugin.deliveries() != null) {
            for (DeliveryDao.Delivery d : plugin.deliveries().activeFor(online)) {
                String desc = strip(d.label());
                if (d.status().equals(DeliveryDao.READY)) {
                    if (ready == null) {
                        ready = "Ready: " + desc;
                    }
                } else {
                    long eta = d.deliverAt() - now;
                    if (eta < soonestEta) {
                        soonestEta = eta;
                        soonest = desc + " in " + Menus.duration(eta);
                    }
                }
            }
        }

        if (ready != null) {
            return ready;
        }
        return soonest != null ? soonest : "No pending orders";
    }

    /** Drop &-colour codes so placeholder output is plain text for any surface. */
    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("(?i)&[0-9a-fk-or]", "");
    }
}
