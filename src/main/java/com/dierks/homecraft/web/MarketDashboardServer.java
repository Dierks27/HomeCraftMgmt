package com.dierks.homecraft.web;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.MarketSimConfig;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.ItemStatus;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.MarketSimService;
import com.dierks.homecraft.market.sim.Season;
import com.dierks.homecraft.mini.MiniDef;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The Market Web Dashboard (Phase 6, §3.7): a small, read-only web server embedded
 * in the plugin that publishes the live commodities market, the Minis catalog and the Arcade as
 * JSON feeds, plus a static HTML dashboard. Built on the JDK's
 * {@link com.sun.net.httpserver} — no extra dependencies.
 *
 * <p><b>Endpoints:</b> {@code /api/market} (every commodity with its 48-hour, 7-day and
 * 30-day price history — see {@link MarketFeed}), {@code /api/news} (the live market's news,
 * its running events and season — see {@link NewsFeed}), {@code /api/minis} (the Minis catalog
 * with printed counts — see {@link MinisFeed}), {@code /api/arcade} (the open games with their
 * published odds or records, the featured game, the Scratch Ticket's pot, the Prize Counter,
 * token Card Packs and achievements — see {@link ArcadeFeed}) and {@code /} (the dashboard page,
 * which fetches {@code /api/market} and {@code /api/news} from the browser). The LilahCraft
 * website reads the feeds from its own server.
 *
 * <p><b>The live market (0.33, spec §14).</b> While {@link MarketSimService#active()} the
 * snapshot also carries the sim's side: per sim-enabled item its usual price, mood percent,
 * badge and chart markers, and at the top level the HOT/DEAL ids, the season and the newest
 * news; {@code /api/news} carries the full news list. It is read once per refresh from the
 * sim's immutable snapshot (see {@code liveView}). While the sim is missing, off or paused nothing
 * new is written: {@code /api/market} is byte for byte the 0.32 feed and {@code /api/news} is
 * {@code {"generatedAt":…,"live":false,"active":[],"news":[]}}. News rows, active rows and
 * markers are made only through {@link NewsFeed}'s factories, which drop a HOT or DEAL still in
 * its silent ramp, so the site can never show an event before players are told about it.
 *
 * <p><b>Threading:</b> the HTTP handlers never touch the Bukkit API or the database.
 * A main-thread Bukkit task rebuilds every feed on the configured interval; the handlers
 * only serve those pre-built payloads (and the static page). This keeps all
 * game-state/DB access on the main thread and off the request path. The 7- and 30-day
 * histories are the heavy part, so they are re-read from the database only after a new
 * snapshot has been recorded, and at most every ten minutes.
 *
 * <p><b>Scope:</b> market data, Minis catalog counts and the Arcade's public side only —
 * prices, buy/sell spread, stock, price history, market news, how many of each Mini were
 * printed, and each game's odds and records (a score or a time and a date). No balances, no
 * owners, no UUIDs, no winners, no per-player limits, no internals (no drift, no seed, no
 * schedule, nothing about events players have not been told about). The one switch that adds a
 * name is {@code web.dashboard.arcade_show_names} (shipped false): only then do the Arcade
 * feed's records carry who set them.
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
    private volatile FeedPayload newsFeed = new FeedPayload(NewsFeed.json(0L, false, null, null, null));
    private volatile FeedPayload minisFeed = new FeedPayload("{\"minis\":[]}");
    private volatile FeedPayload arcadeFeed =
            new FeedPayload(new ArcadeFeed(false).json(0L, null, null, null, null, null));
    private volatile FeedAuth auth = new FeedAuth("", false);
    private String indexHtml = "";

    // The 7- and 30-day histories, by commodity id — main thread only (refreshSnapshot).
    private long longHistoryVersion = -1;
    private long longHistoryBuiltAt;
    private Map<String, List<PriceHistoryDao.Snapshot>> history7d = Map.of();
    private Map<String, List<PriceHistoryDao.Snapshot>> history30d = Map.of();
    // Reading the live market failed on the last refresh (logged once per run of failures).
    private boolean liveFailing;
    // Reading today's featured game failed on the last refresh (likewise).
    private boolean featuredFailing;

    /**
     * The live market's side of {@code /api/market} and {@code /api/news} for one refresh
     * ({@link #liveView}). Only built while the sim is active.
     *
     * @param items  per sim-enabled item id, its {@code /api/market} fields
     * @param extras {@code /api/market}'s top-level fields
     * @param season the running season, or {@code null}
     * @param active {@code /api/news}' running HOT/DEAL/UP/DOWN, soonest-ending first
     * @param news   the news of the last 7 days, newest first (both feeds select from it)
     */
    private record LiveView(Map<String, MarketFeed.SimInfo> items, MarketFeed.Extras extras,
                            NewsFeed.SeasonRow season, List<NewsFeed.ActiveRow> active,
                            List<NewsFeed.NewsRow> news) {
    }

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
        if (!FeedAuth.isPlainAscii(cfg.feedToken())) {
            // Names the key, never the value.
            plugin.getLogger().warning("web.dashboard.feed_token has characters other than plain ASCII letters,"
                    + " digits and punctuation. The dashboard page cannot send such a token; use something like"
                    + " the output of `openssl rand -hex 32`.");
        }
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
        // One virtual thread per request. The JDK server reads a request's headers on this
        // executor with no time limit, so with a small fixed pool a couple of connections that
        // send half a request and stop (the port faces the internet now) would starve every
        // feed; a stalled virtual thread costs next to nothing and holds nobody else up. (That
        // needs Java 24+, where a virtual thread blocked inside the JDK's synchronized request
        // reader gives its carrier back — JEP 491; this plugin is built for Java 25.)
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("hcm-dashboard-", 0).factory());
        server.setExecutor(executor);
        // Every /api feed goes through serveFeed, so each one gets the same token gate. The
        // supplier reads the field per request, i.e. always the latest snapshot.
        server.createContext("/api/market", ex -> serveFeed(ex, () -> marketFeed));
        server.createContext("/api/news", ex -> serveFeed(ex, () -> newsFeed));
        server.createContext("/api/minis", ex -> serveFeed(ex, () -> minisFeed));
        server.createContext("/api/arcade", ex -> serveFeed(ex, () -> arcadeFeed));
        server.createContext("/", this::handleRoot);
        server.start();

        // Build the first snapshot now, then refresh on the configured cadence (main thread).
        long periodTicks = Math.max(2, cfg.refreshSeconds()) * 20L;
        snapshotTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshSnapshot, 1L, periodTicks);

        plugin.getLogger().info("Market dashboard live at http://" + cfg.bind() + ":" + cfg.port()
                + " (refresh " + cfg.refreshSeconds() + "s; feeds /api/market, /api/news, /api/minis, /api/arcade). "
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
        // HttpServer.stop leaves the executor it was handed running; shut it down so an
        // /hcm reload (which restarts the server) leaves nothing of the old one behind.
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
        String authorization = FeedAuth.fromWire(request.getFirst("Authorization"));
        if (!auth.allows(authorization, remoteAddress, FeedAuth.looksForwarded(request))) {
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
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            // The headers a GET would get, and no body. Handing the JDK a length for a HEAD logs a
            // warning per request (a way for anyone to fill the server log) and fails the write,
            // so the length goes in a header of our own.
            ex.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        // A length of 0 would mean "chunked" to the JDK server; -1 is "no body".
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // ---- snapshot builder (main thread) ---------------------------------------

    /**
     * Rebuild every feed from live state (main thread). Each is built on its own, so one
     * failing leaves the others fresh and itself serving its last good payload.
     *
     * <p>The live market's side is read once, first, and shared by {@code /api/market} and
     * {@code /api/news}. If reading it fails, both go out as if the sim were off —
     * {@code /api/market} in its 0.32 shape (its prices come from {@link MarketService#price},
     * so they stay right) and {@code /api/news} with {@code "live":false} — rather than freeze
     * the market's prices on an old payload.
     */
    private void refreshSnapshot() {
        PluginConfig.WebDashboard cfg = plugin.config().webDashboard();
        long now = System.currentTimeMillis();
        LiveView live = null;
        try {
            live = liveView(now);
            if (liveFailing) {
                liveFailing = false;
                plugin.getLogger().info("Market dashboard: the live market's feed fields are back.");
            }
        } catch (RuntimeException e) {
            if (!liveFailing) {
                liveFailing = true;
                plugin.getLogger().warning("Market dashboard: could not read the live market (" + e
                        + ") — the feeds go out without its fields until it can be read again.");
            }
        }
        try {
            marketFeed = new FeedPayload(marketJson(cfg, now, live));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market dashboard: could not rebuild /api/market (" + e
                    + ") — still serving the previous snapshot.");
        }
        try {
            newsFeed = new FeedPayload(newsJson(now, live));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market dashboard: could not rebuild /api/news (" + e
                    + ") — still serving the previous snapshot.");
        }
        try {
            minisFeed = new FeedPayload(minisJson(now));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market dashboard: could not rebuild /api/minis (" + e
                    + ") — still serving the previous snapshot.");
        }
        try {
            arcadeFeed = new FeedPayload(arcadeJson(now));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market dashboard: could not rebuild /api/arcade (" + e
                    + ") — still serving the previous snapshot.");
        }
    }

    /**
     * The {@code /api/market} JSON from live market state + price history. Main thread. With
     * {@code live == null} no row gets live-market fields and no extras are written, so the
     * output is byte for byte the 0.32 feed.
     */
    private String marketJson(PluginConfig.WebDashboard cfg, long now, LiveView live) {
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
                    history7d.get(item.id()), history30d.get(item.id()),
                    live == null ? null : live.items().get(item.id())));
        }
        return MarketFeed.json(cfg != null ? cfg.title() : "Crate Market", now,
                cfg != null ? cfg.refreshSeconds() : 30, rows, live == null ? null : live.extras());
    }

    /**
     * The {@code /api/news} JSON (spec §14.2). Main thread. With {@code live == null} (the sim
     * missing, off or paused): {@code {"generatedAt":…,"live":false,"active":[],"news":[]}}.
     */
    private static String newsJson(long now, LiveView live) {
        if (live == null) {
            return NewsFeed.json(now, false, null, null, null);
        }
        return NewsFeed.json(now, true, live.season(), live.active(), live.news());
    }

    /**
     * The live market's side of {@code /api/market} and {@code /api/news} (spec §14), read once
     * from the sim's immutable snapshot; {@code null} while the sim is missing (it failed to
     * construct), off or paused. Main thread.
     *
     * <p>Every news row, active row and chart marker is made by {@link NewsFeed#newsRow},
     * {@link NewsFeed#activeRow} or {@link NewsFeed#mark}, which return {@code null} for a HOT or
     * DEAL still in its silent ramp (or stopped during it); those are skipped, so nothing reaches
     * the site before players are told (spec §5, §10.1). Per-item fields go only to sim-enabled
     * items (not {@code sim: false} in the catalog).
     */
    private LiveView liveView(long now) {
        MarketSimService sim = plugin.marketSim();
        if (sim == null || !sim.active()) {
            return null;
        }
        MarketService market = plugin.market();
        NewsFeed.SeasonRow season = NewsFeed.seasonRow(sim.season().orElse(null), plugin.clock().zone());

        List<NewsFeed.NewsRow> news = new ArrayList<>();
        for (MarketEvent e : sim.recentNews(NewsFeed.NEWS_LIMIT, now - NewsFeed.NEWS_WINDOW_MS)) {
            NewsFeed.NewsRow row = NewsFeed.newsRow(e, newsName(sim, e), now);
            if (row != null) {
                news.add(row);
            }
        }

        List<NewsFeed.ActiveRow> active = new ArrayList<>();
        for (MarketEvent e : sim.activeEvents()) {
            NewsFeed.ActiveRow row = NewsFeed.activeRow(e, sim.displayName(e.itemId()), activePct(sim, e, now), now);
            if (row != null) {
                active.add(row);
            }
        }

        Map<String, MarketFeed.SimInfo> items = new HashMap<>();
        MarketSimConfig.Parsed parsed = plugin.config().marketSim();
        long markersFrom = now - MarketFeed.EVENTS_WINDOW_MS;
        for (MarketItem item : market.catalog()) {
            if (Boolean.FALSE.equals(parsed.override(item.id()).sim())) {
                continue; // sim: false — the sim never moves it, so the feed says nothing new about it
            }
            ItemStatus status = sim.status(item.id());
            if (status == null) {
                continue;
            }
            items.put(item.id(), simInfo(status, sim.markers(item.id(), markersFrom, MarketFeed.EVENTS_MAX), now));
        }

        MarketFeed.Extras extras = new MarketFeed.Extras(sim.activeIds(Badge.HOT), sim.activeIds(Badge.DEAL),
                season, news);
        return new LiveView(Map.copyOf(items), extras, season, List.copyOf(active), List.copyOf(news));
    }

    /**
     * One item's {@code /api/market} live fields: {@code usual} and {@code moodPct} always;
     * {@code status} only while a badge shows, and {@code statusEndsAt} only when that badge has
     * an end still ahead (a WANTED has none); the markers players may know about.
     */
    private static MarketFeed.SimInfo simInfo(ItemStatus status, List<MarketEvent> markers, long now) {
        List<MarketFeed.EventMark> marks = new ArrayList<>();
        for (MarketEvent e : markers) {
            MarketFeed.EventMark mark = NewsFeed.mark(e, now);
            if (mark != null) {
                marks.add(mark);
            }
        }
        boolean shown = status.shown();
        Long endsAt = shown && status.endsAt() > now ? Long.valueOf(status.endsAt()) : null;
        return new MarketFeed.SimInfo(status.usual(), status.pct(), shown ? status.badge().id() : "", endsAt, marks);
    }

    /**
     * The percent an {@code active} row publishes: the item's percent against usual now
     * ({@link ItemStatus#pct}, what {@code moodPct} says), or the event's own contribution for an
     * item the market no longer knows. Never against the event's direction — a HOT that drift
     * has pulled below usual reads 0, not a minus — the same rule the tiles and placeholders use.
     */
    private static double activePct(MarketSimService sim, MarketEvent e, long now) {
        ItemStatus status = sim.status(e.itemId());
        double pct = status != null ? status.pct() : e.contribution(now) * 100.0;
        if (!Double.isFinite(pct)) {
            return 0.0;
        }
        return e.kind().sign() * pct < 0 ? 0.0 : pct;
    }

    /**
     * The {@code name} a news row carries: the season's name for a SEASON (its tag is
     * {@code id:year}; a season no longer in the config reads as its id in words), otherwise the
     * item's plain label ({@link MarketSimService#displayName}).
     */
    private String newsName(MarketSimService sim, MarketEvent e) {
        if (e.kind() != EventKind.SEASON) {
            return sim.displayName(e.itemId());
        }
        String tag = e.tag() == null ? "" : e.tag();
        int colon = tag.indexOf(':');
        String id = colon < 0 ? tag : tag.substring(0, colon);
        for (Season season : plugin.config().marketSim().settings().seasons().list()) {
            if (season != null && season.id() != null && season.id().equalsIgnoreCase(id)) {
                return season.name();
            }
        }
        return id.isBlank() ? "Season" : Headlines.name(id.toUpperCase(Locale.ROOT));
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
            next7d.put(item.id(), market.sampledHistory(item.id(), from7d, now, MarketFeed.H7_BUCKET_MS));
            next30d.put(item.id(), market.sampledHistory(item.id(), from30d, now, MarketFeed.H30_BUCKET_MS));
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
     * The {@code /api/arcade} JSON (games spec §10). Main thread.
     *
     * <p>Every open game writes its own entries through {@link Game#feed}, in the framework's
     * guard: a game whose {@code feed} throws is switched off like any other failing game
     * ({@link GamesService#fail}) and whatever it wrote before throwing is dropped, never the
     * feed. With the games off (or the service missing) there are no game entries and no
     * featured game. The Arcade's own side — the Scratch Ticket (its entry and pot), the Prize
     * Counter, token packs and achievements — is published while {@code arcade.enabled}, games or
     * not. Record holders' names only with {@code web.dashboard.arcade_show_names} (read here on
     * every refresh, so {@code /hcm reload} applies it).
     */
    private String arcadeJson(long now) {
        GamesService games = plugin.games();
        ArcadeFeed feed = new ArcadeFeed(plugin.getConfig().getBoolean("web.dashboard.arcade_show_names", false),
                games == null ? 0 : games.config().common().feedTop(), games == null ? null : topBoards(games));
        ArcadeFeed.Featured featured = writeGames(feed);
        PluginConfig.Arcade arcade = plugin.config().arcade();
        if (arcade == null || !arcade.enabled()) {
            return feed.json(now, featured, null, null, null, null);
        }
        ArcadeFeed.Scratch scratch = plugin.arcade() == null ? null
                : ArcadeFeed.scratch(arcade.lotto(), plugin.arcade().pot());

        List<ArcadeFeed.PrizeRow> prizes = List.of();
        if (plugin.prizes() != null) {
            List<PluginConfig.Prize> visible = new ArrayList<>();
            for (PluginConfig.PrizeTab tab : PluginConfig.PrizeTab.values()) {
                for (PluginConfig.Prize p : plugin.prizes().visible(tab)) {
                    // The +1 Home is only ever offered through the homes service.
                    if (p.type() != PluginConfig.PrizeType.HOME_SLOT || plugin.homes() != null) {
                        visible.add(p);
                    }
                }
            }
            prizes = ArcadeFeed.prizes(visible);
        }

        List<ArcadeFeed.PackRow> packs = List.of();
        MiniService minis = plugin.miniService();
        if (plugin.packs() != null) {
            packs = ArcadeFeed.packs(plugin.packs().packs(), id -> {
                MiniDef def = minis == null ? null : minis.def(id);
                return def == null ? null : def.rarity();
            });
        }

        List<ArcadeFeed.AchievementRow> achievements = plugin.achievements() == null ? List.of()
                : ArcadeFeed.achievements(plugin.achievements().all());
        return feed.json(now, featured, scratch, prizes, packs, achievements);
    }

    /**
     * Where the feed's {@code top} lists are read (EXTRAS E3): the games' own boards, and a record
     * holder's name, which the feed asks for only while {@code arcade_show_names} is on.
     */
    private static ArcadeFeed.Boards topBoards(GamesService games) {
        return new ArcadeFeed.Boards() {
            @Override
            public List<com.dierks.homecraft.storage.GamesDao.ScoreRow> top(String game, String board,
                                                                           boolean lowerIsBetter, int limit) {
                return games.scores().top(game, board, lowerIsBetter, limit);
            }

            @Override
            public String name(java.util.UUID player) {
                return org.bukkit.Bukkit.getOfflinePlayer(player).getName();
            }
        };
    }

    /**
     * Have every open game write its {@code games[]} entries into {@code feed}, and return
     * today's featured game ({@code null} with the games off or none picked). Main thread.
     */
    private ArcadeFeed.Featured writeGames(ArcadeFeed feed) {
        GamesService games = plugin.games();
        if (games == null || !games.config().enabled()) {
            return null;
        }
        for (Game game : games.games()) {
            if (!games.enabled(game)) {
                continue;
            }
            int mark = feed.size();
            if (!games.guard(game, () -> {
                game.feed(feed);
                return Boolean.TRUE;
            }, Boolean.FALSE)) {
                feed.truncate(mark);
            }
        }
        try {
            String today = games.featured().today();
            long until = games.featured().until();
            if (featuredFailing) {
                featuredFailing = false;
                plugin.getLogger().info("Market dashboard: /api/arcade's featured game is back.");
            }
            return today == null || today.isBlank() ? null : new ArcadeFeed.Featured(today, until);
        } catch (RuntimeException e) {
            if (!featuredFailing) {
                featuredFailing = true;
                plugin.getLogger().warning("Market dashboard: could not read today's featured game (" + e
                        + ") — /api/arcade goes out without it until it can be read again.");
            }
            return null;
        }
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
