package com.dierks.homecraft.web;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.PriceHistoryDao;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The Market Web Dashboard (Phase 6, §3.7): a small, read-only web server embedded
 * in the plugin that publishes the live commodities market and the Minis catalog as
 * JSON feeds, plus a static HTML dashboard. Built on the JDK's
 * {@link com.sun.net.httpserver} — no extra dependencies.
 *
 * <p><b>Endpoints:</b> {@code /api/market} (every commodity with its 48-hour, 7-day and
 * 30-day price history — see {@link MarketFeed}), {@code /api/minis} (the Minis catalog
 * with printed counts — see {@link MinisFeed}) and {@code /} (the dashboard page, which
 * fetches {@code /api/market} from the browser). The LilahCraft website reads both feeds
 * from its own server.
 *
 * <p><b>Threading:</b> the HTTP handlers never touch the Bukkit API or the database.
 * A main-thread Bukkit task rebuilds both feeds on the configured interval; the handlers
 * only serve those pre-built payloads (and the static page). This keeps all
 * game-state/DB access on the main thread and off the request path. The 7- and 30-day
 * histories are the heavy part, so they are re-read from the database only after a new
 * snapshot has been recorded, and at most every ten minutes.
 *
 * <p><b>Scope:</b> market data and Minis catalog counts only — prices, buy/sell
 * spread, stock, price history, and how many of each Mini were printed. No balances,
 * no owners, no UUIDs, no player data of any kind, no internals.
 *
 * <p><b>Auth:</b> the page is public; the feeds ({@code /api/*}) all pass one gate,
 * {@link FeedAuth}. With {@code web.dashboard.feed_token} blank they are open, as they
 * always were; once it is set they need {@code Authorization: Bearer <token>} (401
 * otherwise) — the dashboard page asks for it once — except requests straight from this PC
 * or the LAN if {@code web.dashboard.lan_skips_token} is turned on (it ships off, because a
 * Playit agent or a tunnel delivers internet traffic from 127.0.0.1 or a LAN address). The
 * token is never logged or echoed.
 * Feeds are gzipped for clients that accept it.
 */
public final class MarketDashboardServer {

    /** The 7- and 30-day histories are rebuilt from the database at most this often. */
    private static final long LONG_HISTORY_MIN_AGE_MS = 10 * 60_000L;

    private static final String JSON = "application/json; charset=utf-8";
    private static final String UNAUTHORIZED = "{\"error\":\"unauthorized\"}";

    private final HomeCraftManagement plugin;

    private HttpServer server;
    private ExecutorService executor;
    private BukkitTask snapshotTask;
    private volatile FeedPayload marketFeed = new FeedPayload("{\"items\":[]}");
    private volatile FeedPayload minisFeed = new FeedPayload("{\"minis\":[]}");
    private volatile FeedAuth auth = new FeedAuth("", false);
    private String indexHtml = "";

    // The 7- and 30-day histories, by commodity id — main thread only (refreshSnapshot).
    private long longHistoryVersion = -1;
    private long longHistoryBuiltAt;
    private Map<String, List<PriceHistoryDao.Snapshot>> history7d = Map.of();
    private Map<String, List<PriceHistoryDao.Snapshot>> history30d = Map.of();

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
        FeedAuth gate = new FeedAuth(cfg.feedToken(), cfg.lanSkipsToken());
        this.auth = gate;
        // A reload may have changed the catalog: rebuild the long histories on the first refresh.
        this.longHistoryVersion = -1;
        this.longHistoryBuiltAt = 0L;
        this.history7d = Map.of();
        this.history30d = Map.of();
        try {
            server = HttpServer.create(new InetSocketAddress(cfg.bind(), cfg.port()), 0);
        } catch (IOException e) {
            plugin.getLogger().warning("Market dashboard could not bind " + cfg.bind() + ":" + cfg.port()
                    + " (" + e.getMessage() + ") — dashboard disabled this run.");
            server = null;
            return;
        }
        // A tiny bounded pool; requests are trivial (serve a cached payload).
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hcm-dashboard");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        // Every /api feed goes through serveFeed, so each one gets the same token gate. The
        // supplier reads the field per request, i.e. always the latest snapshot.
        server.createContext("/api/market", ex -> serveFeed(ex, () -> marketFeed));
        server.createContext("/api/minis", ex -> serveFeed(ex, () -> minisFeed));
        server.createContext("/", this::handleRoot);
        server.start();

        // Build the first snapshot now, then refresh on the configured cadence (main thread).
        long periodTicks = Math.max(2, cfg.refreshSeconds()) * 20L;
        snapshotTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshSnapshot, 1L, periodTicks);

        plugin.getLogger().info("Market dashboard live at http://" + cfg.bind() + ":" + cfg.port()
                + " (refresh " + cfg.refreshSeconds() + "s; feeds /api/market, /api/minis). "
                + tokenState(gate));
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
        // HttpServer.stop leaves the executor it was handed running; without this every
        // /hcm reload (which restarts the server) would leave two idle threads behind.
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /** Restart to pick up config changes (bind/port/enabled/refresh/title/feed token). */
    public void restart() {
        stop();
        start();
    }

    /** The feed gate for the startup log line — whether it is on, never the token itself. */
    private static String tokenState(FeedAuth gate) {
        if (!gate.enabled()) {
            return "Feed token: off.";
        }
        if (gate.lanSkipsToken()) {
            return "Feed token: on; requests from this PC or the LAN skip it (web.dashboard.lan_skips_token)"
                    + " — turn that off if a Playit agent or a tunnel delivers internet traffic from 127.0.0.1"
                    + " or a LAN address.";
        }
        return "Feed token: on (the dashboard page asks for it once).";
    }

    // ---- request handlers (HTTP threads — no Bukkit/DB access here) -----------

    /**
     * Serve one website feed. Every {@code /api/*} context comes through here: 401 unless
     * {@link FeedAuth} lets the request in, otherwise the cached payload, gzipped when the
     * client accepts it. Nothing about the request's token is logged or echoed.
     */
    private void serveFeed(HttpExchange ex, Supplier<FeedPayload> feed) throws IOException {
        Headers request = ex.getRequestHeaders();
        InetSocketAddress remote = ex.getRemoteAddress();
        InetAddress remoteAddress = remote == null ? null : remote.getAddress();
        if (!auth.allows(request.getFirst("Authorization"), remoteAddress, FeedAuth.looksForwarded(request))) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            respond(ex, 401, JSON, UNAUTHORIZED);
            return;
        }
        FeedPayload payload = feed.get();
        boolean gzip = FeedPayload.acceptsGzip(request.get("Accept-Encoding"));
        Headers response = ex.getResponseHeaders();
        response.set("Vary", "Accept-Encoding");
        if (gzip) {
            response.set("Content-Encoding", "gzip");
        }
        respond(ex, 200, JSON, gzip ? payload.gzipped() : payload.raw());
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
        respond(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        // A length of 0 would mean "chunked" to the JDK server; -1 is "no body".
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // ---- snapshot builder (main thread) ---------------------------------------

    /**
     * Rebuild both feeds from live state (main thread). Each is built on its own, so one
     * failing leaves the other fresh and itself serving its last good payload.
     */
    private void refreshSnapshot() {
        PluginConfig.WebDashboard cfg = plugin.config().webDashboard();
        long now = System.currentTimeMillis();
        try {
            marketFeed = new FeedPayload(marketJson(cfg, now));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market dashboard: could not rebuild /api/market (" + e
                    + ") — still serving the previous snapshot.");
        }
        try {
            minisFeed = new FeedPayload(minisJson(now));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market dashboard: could not rebuild /api/minis (" + e
                    + ") — still serving the previous snapshot.");
        }
    }

    /** The {@code /api/market} JSON from live market state + price history. Main thread. */
    private String marketJson(PluginConfig.WebDashboard cfg, long now) {
        MarketService market = plugin.market();
        refreshLongHistory(market, now);
        List<MarketFeed.Row> rows = new ArrayList<>();
        for (MarketItem item : market.catalog()) {
            MarketState state = market.state(item.id());
            if (state == null) {
                continue;
            }
            List<PriceHistoryDao.Snapshot> history = market.recentHistory(item.id(), 96);
            // recentHistory is newest-first; chart wants oldest-first.
            List<PriceHistoryDao.Snapshot> chrono = new ArrayList<>(history);
            Collections.reverse(chrono);

            double price = market.price(item.id());
            rows.add(new MarketFeed.Row(item.id(), item.label(), item.material().name(), price,
                    market.buyPrice(item.id()), market.sellPrice(item.id()),
                    state.stock(), item.fullStock(), change24h(chrono, price, now), chrono,
                    history7d.get(item.id()), history30d.get(item.id())));
        }
        return MarketFeed.json(cfg != null ? cfg.title() : "Crate Market", now,
                cfg != null ? cfg.refreshSeconds() : 30, rows);
    }

    /**
     * Re-read the 7-day (hourly) and 30-day (6-hourly) histories from the database when a
     * new snapshot has been recorded since the last read and that read is at least
     * {@link #LONG_HISTORY_MIN_AGE_MS} old (or there has been none yet). Snapshots come
     * every {@code market.price_history.interval_minutes}, so in practice this is one read
     * per snapshot, not one per refresh. Main thread.
     */
    private void refreshLongHistory(MarketService market, long now) {
        long version = market.historyVersion();
        if (version == longHistoryVersion) {
            return;
        }
        long age = now - longHistoryBuiltAt;
        boolean neverBuilt = longHistoryVersion < 0;
        // A negative age means the clock went back: treat the cache as stale rather than wait it out.
        if (!neverBuilt && age >= 0 && age < LONG_HISTORY_MIN_AGE_MS) {
            return;
        }
        long from7d = MarketFeed.windowStart(now, MarketFeed.H7_BUCKET_MS, MarketFeed.H7_POINTS);
        long from30d = MarketFeed.windowStart(now, MarketFeed.H30_BUCKET_MS, MarketFeed.H30_POINTS);
        Map<String, List<PriceHistoryDao.Snapshot>> next7d = new HashMap<>();
        Map<String, List<PriceHistoryDao.Snapshot>> next30d = new HashMap<>();
        for (MarketItem item : market.catalog()) {
            next7d.put(item.id(), market.sampledHistory(item.id(), from7d, MarketFeed.H7_BUCKET_MS));
            next30d.put(item.id(), market.sampledHistory(item.id(), from30d, MarketFeed.H30_BUCKET_MS));
        }
        history7d = next7d;
        history30d = next30d;
        longHistoryVersion = version;
        longHistoryBuiltAt = now;
    }

    /** The {@code /api/minis} JSON: the catalog and its printed counts, nothing else. Main thread. */
    private String minisJson(long now) {
        MiniService minis = plugin.miniService();
        if (minis == null) {
            return MinisFeed.json(now, null, null);
        }
        return MinisFeed.json(now, minis.catalog(), minis.printedCounts());
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
