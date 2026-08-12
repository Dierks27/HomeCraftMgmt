package com.dierks.homecraft.web;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.storage.PriceHistoryDao;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The Market Web Dashboard (Phase 6, §3.7): a small, read-only web server embedded
 * in the plugin that publishes the live commodities market as a JSON feed and a
 * static HTML dashboard. Built on the JDK's {@link com.sun.net.httpserver} — no
 * extra dependencies.
 *
 * <p><b>Threading:</b> the HTTP handlers never touch the Bukkit API or the database.
 * A main-thread Bukkit task rebuilds a cached JSON snapshot on the configured
 * interval; the handlers only serve that pre-built string (and the static page).
 * This keeps all game-state/DB access on the main thread and off the request path.
 *
 * <p><b>Scope:</b> market data only — current price, buy/sell spread, stock, and
 * price history. No balances, no player data, no internals. No auth (public,
 * read-only), matching the §3.7 build order (the transactional shop is a later pass).
 */
public final class MarketDashboardServer {

    private final HomeCraftManagement plugin;

    private HttpServer server;
    private BukkitTask snapshotTask;
    private volatile String cachedJson = "{\"items\":[]}";
    private String indexHtml = "";

    public MarketDashboardServer(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Start the server if enabled in config. Safe to call when already stopped. */
    public void start() {
        PluginConfig.WebDashboard cfg = plugin.config().webDashboard();
        if (cfg == null || !cfg.enabled()) {
            return;
        }
        this.indexHtml = loadIndexHtml(cfg.title());
        try {
            server = HttpServer.create(new InetSocketAddress(cfg.bind(), cfg.port()), 0);
        } catch (IOException e) {
            plugin.getLogger().warning("Market dashboard could not bind " + cfg.bind() + ":" + cfg.port()
                    + " (" + e.getMessage() + ") — dashboard disabled this run.");
            server = null;
            return;
        }
        // A tiny bounded pool; requests are trivial (serve a cached string).
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hcm-dashboard");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/api/market", this::handleApi);
        server.createContext("/", this::handleRoot);
        server.start();

        // Build the first snapshot now, then refresh on the configured cadence (main thread).
        long periodTicks = Math.max(2, cfg.refreshSeconds()) * 20L;
        snapshotTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshSnapshot, 1L, periodTicks);

        plugin.getLogger().info("Market dashboard live at http://" + cfg.bind() + ":" + cfg.port()
                + " (refresh " + cfg.refreshSeconds() + "s).");
    }

    /** Stop the server and cancel the snapshot task. Safe to call when not running. */
    public void stop() {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            snapshotTask = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Restart to pick up config changes (bind/port/enabled/refresh/title). */
    public void restart() {
        stop();
        start();
    }

    // ---- request handlers (HTTP threads — no Bukkit/DB access here) -----------

    private void handleApi(HttpExchange ex) throws IOException {
        respond(ex, 200, "application/json; charset=utf-8", cachedJson);
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.equals("/index.html")) {
            respond(ex, 200, "text/html; charset=utf-8", indexHtml);
        } else {
            respond(ex, 404, "text/plain; charset=utf-8", "Not found");
        }
    }

    private void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---- snapshot builder (main thread) ---------------------------------------

    /** Rebuild the cached JSON from live market state + price history. Main thread. */
    private void refreshSnapshot() {
        PluginConfig.WebDashboard cfg = plugin.config().webDashboard();
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        sb.append("\"title\":").append(jsonString(cfg != null ? cfg.title() : "Crate Market")).append(',');
        sb.append("\"generatedAt\":").append(now).append(',');
        sb.append("\"refreshSeconds\":").append(cfg != null ? cfg.refreshSeconds() : 30).append(',');
        sb.append("\"items\":[");

        boolean first = true;
        for (MarketItem item : plugin.market().catalog()) {
            MarketState state = plugin.market().state(item.id());
            if (state == null) {
                continue;
            }
            List<PriceHistoryDao.Snapshot> history = plugin.market().recentHistory(item.id(), 96);
            // recentHistory is newest-first; chart wants oldest-first.
            List<PriceHistoryDao.Snapshot> chrono = new ArrayList<>(history);
            Collections.reverse(chrono);

            double price = plugin.market().price(item.id());
            double change = change24h(chrono, price, now);

            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{');
            sb.append("\"id\":").append(jsonString(item.id())).append(',');
            sb.append("\"name\":").append(jsonString(item.label())).append(',');
            sb.append("\"material\":").append(jsonString(item.material().name())).append(',');
            sb.append("\"price\":").append(num(price)).append(',');
            sb.append("\"buy\":").append(num(plugin.market().buyPrice(item.id()))).append(',');
            sb.append("\"sell\":").append(num(plugin.market().sellPrice(item.id()))).append(',');
            sb.append("\"stock\":").append(state.stock()).append(',');
            sb.append("\"maxStock\":").append(item.fullStock()).append(',');
            sb.append("\"change24h\":").append(num(change)).append(',');
            sb.append("\"history\":[");
            for (int i = 0; i < chrono.size(); i++) {
                PriceHistoryDao.Snapshot s = chrono.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"t\":").append(s.recordedAt())
                        .append(",\"p\":").append(num(s.price()))
                        .append(",\"s\":").append(s.stock()).append('}');
            }
            sb.append(']');
            sb.append('}');
        }
        sb.append("]}");
        cachedJson = sb.toString();
    }

    /**
     * Percent change vs. ~24h ago: the price of the oldest snapshot still within the
     * last 24h (or the earliest snapshot we have, if all are recent). 0 if no history.
     */
    private double change24h(List<PriceHistoryDao.Snapshot> chrono, double current, long now) {
        if (chrono.isEmpty()) {
            return 0;
        }
        long cutoff = now - 86_400_000L;
        double base = chrono.get(0).price(); // earliest available
        for (PriceHistoryDao.Snapshot s : chrono) {
            if (s.recordedAt() >= cutoff) {
                base = s.price();
                break;
            }
        }
        if (base <= 0) {
            return 0;
        }
        return (current - base) / base * 100.0;
    }

    // ---- helpers --------------------------------------------------------------

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private String loadIndexHtml(String title) {
        try (InputStream in = plugin.getResource("web/index.html")) {
            if (in == null) {
                return "<!doctype html><meta charset=utf-8><title>" + escapeHtml(title)
                        + "</title><p>Dashboard page missing.";
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return html.replace("{{TITLE}}", escapeHtml(title));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load dashboard HTML: " + e.getMessage());
            return "<!doctype html><meta charset=utf-8><title>Dashboard</title><p>Failed to load.";
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
