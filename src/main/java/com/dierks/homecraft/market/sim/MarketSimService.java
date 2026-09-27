package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.MarketLabels;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.market.OrderMath;
import com.dierks.homecraft.market.PriceMood;
import com.dierks.homecraft.market.PricingEngine;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.MarketSimDao;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The live market's Bukkit side (spec §4, §11.2-11.3, §13.2): it owns the sim's working state,
 * runs the pure {@link MarketSimulator} on a pump, writes each tick in one transaction, and
 * publishes an immutable snapshot that every price read, GUI, display, placeholder and feed is
 * served from. It is the {@link PriceMood} {@link MarketService} asks for the multiplier.
 *
 * <p><b>It only moves data.</b> Every number comes from the pure engine ({@link MarketSimulator},
 * {@link MoodEngine}, {@link EventPlanner}, {@link AnnounceGate}); this class loads rows, hands
 * them in, writes back what comes out, and delivers the one broadcast a tick may choose. It never
 * writes stock or the balanced price ({@code MarketState}): it only reads them.
 *
 * <p><b>Hard limits.</b> The multiplier it serves is the engine's, already inside
 * {@code [0.75, 1.25]} (and {@link MarketService} squeezes it into that band again). While the sim
 * is off ({@code enabled: false}), paused, or not started, every read here is neutral:
 * {@link #multiplier} is exactly {@code 1.0}, there are no jumps and no event caps, and the lists
 * are empty, so the market is 0.32's.
 *
 * <p><b>The pump</b> ({@code runTaskTimer}, first run after 10 s, then every 20 s, main thread)
 * runs every tick boundary {@code last_tick_at < b <= now}: older ones as replays (drift, expiry
 * and season rows only), the newest live. Each run is written in ONE {@link Database#transaction}:
 * the per-item rows, event inserts and updates, the meta (schedule, broadcast counters,
 * {@code last_tick_at}) and the buffered ledger. If that transaction fails the in-memory state
 * carries on, the persisted {@code last_tick_at} stays where it was, and the next tick retries the
 * writes; a restart then replays deterministically.
 *
 * <p><b>Threading.</b> Everything here runs on the main thread except the real-world HTTP fetch
 * ({@link RealWorldFetcher}), whose results come back through {@code runTask}. The snapshot is
 * {@code volatile} and immutable; the reads ({@link #multiplier}, {@link #status}, …) never touch
 * the database and never throw.
 */
public final class MarketSimService implements PriceMood {

    /** First pump 10 s after start. */
    static final long PUMP_DELAY_TICKS = 200L;
    /** Then every 20 s. */
    static final long PUMP_PERIOD_TICKS = 400L;
    /** The news cache holds this much history (catch-up, charts, the news list). */
    static final long NEWS_CACHE_MS = 30 * SimMath.DAY_MS;
    /** … and at most this many rows of it. */
    static final int NEWS_CACHE_MAX = 300;
    /** "Popular": anyone traded it in this many local days. */
    static final int POPULAR_DAYS = 14;
    /** The popular set is re-read this often. */
    static final long POPULAR_REFRESH_MS = SimMath.HOUR_MS;
    /** Under this share of sim-enabled items able to move both ways, load and reload warn. */
    static final double BOTH_WAYS_WARN = 0.30;
    /** Headroom above this (both ways, at {@code M = 1}) counts as "can move both ways". */
    static final double BOTH_WAYS_MIN = 0.01;
    /** {@code sim preview} looks at most this many days ahead. */
    static final int PREVIEW_MAX_DAYS = 14;
    /** {@code sim preview} plots one point per this many ms. */
    static final long PREVIEW_STEP_MS = 6 * SimMath.HOUR_MS;
    /** {@code sim status}'s money line covers this many days. */
    static final int STATUS_MONEY_DAYS = 7;
    /** A restart or reload reads the SEASON rows started this far back (a season lasts at most a year). */
    static final long SEASON_ROWS_MS = 400 * SimMath.DAY_MS;

    private static final String SPARK = "▁▂▃▄▅▆▇█";

    /**
     * An admin action's outcome.
     *
     * @param ok      it happened
     * @param message what to tell the admin ({@code &}-coded)
     */
    public record Result(boolean ok, String message) {
    }

    /** What every read is served from: immutable, replaced whole after each tick or admin action. */
    private record Snapshot(boolean active, long at, SortedMap<String, ItemParams> params,
                            Map<String, Double> multipliers, Map<String, ItemStatus> status,
                            Map<String, MoodEngine.Breakdown> parts, List<MarketEvent> events,
                            List<SeasonCalendar.Active> seasons, List<MarketEvent> news, List<MarketEvent> recent) {

        static final Snapshot NEUTRAL = new Snapshot(false, 0L, Collections.emptySortedMap(), Map.of(), Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** One ledger bucket's key: a local day and an item. */
    private record LedgerKey(long day, String itemId) {
    }

    /** One ledger bucket: what the sim paid out and saved on one local day for one item. */
    private static final class LedgerSum {
        double bonus;
        double discount;
        long sold;
        long bought;

        LedgerSum add(double b, double d, long s, long u) {
            bonus += b;
            discount += d;
            sold += s;
            bought += u;
            return this;
        }

        static LedgerSum plus(LedgerSum a, LedgerSum b) {
            return new LedgerSum().add(a.bonus + b.bonus, a.discount + b.discount, a.sold + b.sold,
                    a.bought + b.bought);
        }
    }

    private final HomeCraftManagement plugin;
    private final MarketSimDao dao;
    private final MarketNewsService news;
    private final RealWorldFetcher realWorld;

    private SimSettings settings;
    private SortedMap<String, ItemParams> params;
    private PricingEngine engine;
    private SimRandom rng;
    /** Per-item rows; the simulator updates them in place. */
    private final Map<String, ItemSimState> state = new TreeMap<>();
    /** The working set of events (live ones plus any that closed within the last hour). */
    private List<MarketEvent> events = new ArrayList<>();
    private Schedule schedule = new Schedule();
    private BroadcastState broadcast = new BroadcastState();
    private long lastTickAt;
    private long manualCounter;
    private boolean paused;
    private boolean started;
    private boolean startAttempted;
    private boolean newsHeld;
    private BukkitTask pump;
    /** Each item's {@code M} at the previous evaluation (the jump check); {@code null} = none. */
    private Map<String, Double> previous;
    private final Map<String, Long> jumpSeq = new ConcurrentHashMap<>();
    private Set<String> popular = Set.of();
    private long popularAt;
    private long pruneDay = Long.MIN_VALUE;
    /** Real-world impulses waiting for the next live tick. */
    private final List<RealImpulse> queued = new ArrayList<>();
    /** Stored events of the last 30 days by id (the working set's newer versions win). */
    private final TreeMap<Long, MarketEvent> newsCache = new TreeMap<>();
    /** Trades at a moved price since the last flush; written in the tick transaction. */
    private Map<LedgerKey, LedgerSum> ledger = new LinkedHashMap<>();
    /** Row updates a failed transaction did not write yet, by id. */
    private final Map<Long, MarketEvent> pendingUpdates = new LinkedHashMap<>();
    /** New rows no longer in the working set that a failed transaction did not write yet. */
    private final List<MarketEvent> pendingInserts = new ArrayList<>();
    private volatile Snapshot snapshot = Snapshot.NEUTRAL;

    public MarketSimService(HomeCraftManagement plugin, MarketSimDao dao, MarketNewsService news) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dao = Objects.requireNonNull(dao, "dao");
        this.news = news;
        this.realWorld = new RealWorldFetcher(plugin, this, dao);
        this.settings = plugin.config().marketSim().settings();
        this.params = buildParams(settings);
        this.engine = buildEngine();
        if (news != null) {
            news.attach(this);
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------

    /**
     * Load everything and start (§11.2): the seed (created on the very first start and only ever
     * stored in the database), the per-item rows, the working set of events and 30 days of news,
     * the popular set; then replay the missed boundaries, commit once, publish, and arm the pump.
     * While {@code enabled: false} or paused it only makes sure no event is left running.
     */
    public void start() {
        startAttempted = true;
        cancelPump();
        long now = System.currentTimeMillis();
        settings = plugin.config().marketSim().settings();
        params = buildParams(settings);
        engine = buildEngine();
        Map<String, String> meta;
        try {
            meta = dao.loadMeta();
            OptionalLong stored = MarketSimDao.Meta.seed(meta);
            long seed;
            if (stored.isPresent()) {
                seed = stored.getAsLong();
            } else {
                seed = new SecureRandom().nextLong();
                dao.saveMeta(Map.of(MarketSimDao.Meta.SEED, MarketSimDao.Meta.seedHex(seed)));
            }
            rng = new SimRandom(seed);
            state.clear();
            state.putAll(dao.loadStates());
            boolean firstEver = !meta.containsKey(MarketSimDao.Meta.LAST_TICK_AT);
            long t = settings.tickMs();
            lastTickAt = MarketSimDao.Meta.longOf(meta, MarketSimDao.Meta.LAST_TICK_AT, floor(now, t));
            events = new ArrayList<>(dao.loadLive(Math.min(lastTickAt, now)));
            addSeasonRows(dao.loadSeasons(now - SEASON_ROWS_MS));
            newsCache.clear();
            for (MarketEvent e : dao.loadRecentNews(now - NEWS_CACHE_MS, NEWS_CACHE_MAX)) {
                if (e.id() > 0) {
                    newsCache.put(e.id(), e);
                }
            }
            popular = Set.copyOf(dao.tradedSince(plugin.clock().dayKey() - (POPULAR_DAYS - 1)));
            popularAt = now;
            paused = MarketSimDao.Meta.flagOf(meta, MarketSimDao.Meta.PAUSED);
            manualCounter = Math.max(0L, MarketSimDao.Meta.longOf(meta, MarketSimDao.Meta.MANUAL_COUNTER, 0L));
            schedule = MarketSimDao.Meta.schedule(meta);
            broadcast = MarketSimDao.Meta.broadcast(meta);
            realWorld.load(meta);
            clockCheck(now);
            boolean wasEnabled = !firstEver && MarketSimDao.Meta.flagOf(meta, MarketSimDao.Meta.WAS_ENABLED);
            seedRows();
            pendingInserts.clear();
            pendingUpdates.clear();
            queued.clear();
            started = true;
            if (news != null) {
                news.prime();
            }
            if (!running()) {
                endAll(now, paused ? MarketSimulator.STOP_PAUSED : MarketSimulator.STOP_DISABLED);
                previous = null;
                commit();
                publishNeutral();
                plugin.getLogger().info("Live market: " + (paused ? "paused (/hcm market sim resume)"
                        : "off (market.sim.enabled: false)") + " - every price is its usual price.");
                return;
            }
            previous = null;
            if (firstEver || !wasEnabled) {
                // First start ever, or back on after being off: a fresh schedule, no drift, and no
                // replay across the time it was off.
                MarketSimulator.enable(now, rng, schedule, state);
                lastTickAt = floor(now, t);
            }
            endUnlisted(now); // items removed or set sim: false while the server was off
            requeueReal(now); // real-world moves fetched but not applied before the stop
        } catch (SQLException e) {
            started = false;
            snapshot = Snapshot.NEUTRAL;
            plugin.getLogger().severe("Live market: could not load its data (" + e.getMessage()
                    + ") - it stays off, and every price is its usual price, until /hcm reload or a restart.");
            return;
        }
        advance(now, true);
        armPump();
        warnBothWays();
        plugin.getLogger().info("Live market: running for " + simItems() + " item(s), tick " + settings.tickMinutes()
                + "m, multiplier " + fmt2(settings.multiplierLo()) + "-" + fmt2(settings.multiplierHi()) + ".");
    }

    /**
     * {@code /hcm reload} (§11.3), after {@link MarketService#reload()}: re-read the settings,
     * rebuild every item's params (drift re-clamped to the new bound), seed rows for new items,
     * end the events of removed or {@code sim: false} items silently, and keep drift, events and
     * the schedule. Going from on to off ends every event ({@code disabled}); from off to on
     * starts afresh (drift 0, a new schedule, no replay across the off period).
     */
    public void reload() {
        SimSettings next = plugin.config().marketSim().settings();
        if (!started) {
            settings = next;
            params = buildParams(next);
            engine = buildEngine();
            if (startAttempted) {
                start(); // a start that could not load gets another try
            }
            return;
        }
        long now = System.currentTimeMillis();
        boolean wasOn = running();
        settings = next;
        params = buildParams(next);
        engine = buildEngine();
        seedRows();
        double bound = next.drift().maxFrac();
        state.replaceAll((id, st) -> Math.abs(st.drift()) > bound
                ? st.withDrift(Math.max(-bound, Math.min(bound, st.drift())), st.updatedAt()) : st);
        if (!next.real().enabled()) {
            realWorld.cancel();
            queued.clear();
        }
        boolean on = running();
        if (wasOn && !on) {
            goOff(now, MarketSimulator.STOP_DISABLED);
            plugin.getLogger().info("Live market: turned off - every price is back to its usual price.");
        } else if (!wasOn && on) {
            goOn(now);
            plugin.getLogger().info("Live market: turned on.");
        } else if (on) {
            endUnlisted(now);
            reloadSeasonRows(now);
            requeueReal(now);
            commit();
            republish(now, true);
        } else {
            commit();
        }
        if (on) {
            warnBothWays();
        }
    }

    /**
     * {@code onDisable}, before the database closes: cancel the pump and any real-world fetch, then
     * write the per-item rows, any unwritten events, the meta and the ledger buffer in one
     * transaction. Events are not ended: they carry on after the restart.
     */
    public void stop() {
        cancelPump();
        realWorld.cancel();
        if (started) {
            commit();
        }
        started = false;
        snapshot = Snapshot.NEUTRAL;
    }

    /** True while the live market runs: started, {@code enabled: true} and not paused. */
    public boolean active() {
        return started && settings.enabled() && !paused;
    }

    private boolean running() {
        return started && settings.enabled() && !paused;
    }

    // ---- PriceMood --------------------------------------------------------------------------

    @Override
    public double multiplier(String id) {
        Snapshot s = snapshot;
        if (!s.active() || id == null) {
            return 1.0;
        }
        Double m = s.multipliers().get(id);
        return m == null ? 1.0 : m;
    }

    @Override
    public long jumpSeq(String id) {
        if (id == null) {
            return 0L;
        }
        Long v = jumpSeq.get(id);
        return v == null ? 0L : v;
    }

    /**
     * While a DEAL (any phase, its ramp included) or a DOWN runs on the item:
     * {@code max(1, floor(buy_limit_share x (max_daily_buy or ceil(4% of full_stock))))}, the
     * deal's share for a DEAL and the news share for a DOWN (the tighter when both run). 0
     * otherwise, and always 0 while the sim is off.
     */
    @Override
    public long eventBuyCap(MarketItem item) {
        Snapshot s = snapshot;
        if (!s.active() || item == null) {
            return 0L;
        }
        ItemParams p = s.params().get(item.id());
        if (p == null || !p.enabled()) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long cap = 0L;
        for (MarketEvent e : s.events()) {
            if (!item.id().equals(e.itemId()) || !e.active(now)) {
                continue;
            }
            if (e.kind() == EventKind.DEAL) {
                cap = tighter(cap, p.eventBuyCap(settings.deal().buyLimitShare()));
            } else if (e.kind() == EventKind.DOWN) {
                cap = tighter(cap, p.eventBuyCap(settings.news().buyLimitShare()));
            }
        }
        return cap;
    }

    /**
     * While a HOT or UP runs on the item (and {@code hot.sell_limit: true}): the item's daily sell
     * cap, {@code max_daily_sell} or {@code ceil(2% of full_stock)}. 0 otherwise.
     */
    @Override
    public long eventSellCap(MarketItem item) {
        Snapshot s = snapshot;
        if (!s.active() || item == null || !settings.hot().sellLimit()) {
            return 0L;
        }
        ItemParams p = s.params().get(item.id());
        if (p == null || !p.enabled()) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        for (MarketEvent e : s.events()) {
            if (item.id().equals(e.itemId()) && e.active(now)
                    && (e.kind() == EventKind.HOT || e.kind() == EventKind.UP)) {
                return Math.max(1L, p.eventSellCap());
            }
        }
        return 0L;
    }

    /**
     * Whether the event behind this side's cap is one players can see right now: a DEAL or DOWN
     * showing its badge ({@code sell == false}), a HOT or UP showing its badge
     * ({@code sell == true}). False during a HOT/DEAL's silent ramp (and for one stopped in it)
     * and in an UP/DOWN's badge-less tail, so a refusal never tips anyone off; always false
     * while the sim is off.
     */
    @Override
    public boolean eventCapShown(MarketItem item, boolean sell) {
        Snapshot s = snapshot;
        if (!s.active() || item == null) {
            return false;
        }
        ItemParams p = s.params().get(item.id());
        if (p == null || !p.enabled()) {
            return false;
        }
        List<MarketEvent> mine = new ArrayList<>(2);
        for (MarketEvent e : s.events()) {
            if (item.id().equals(e.itemId())) {
                mine.add(e);
            }
        }
        return MoodEngine.capEventShown(mine, sell, System.currentTimeMillis());
    }

    /**
     * §3.3: buffer what a trade at a moved price paid out ({@code total - neutral} on a sell) or
     * saved ({@code neutral - total} on a buy), per local day and item; flushed in the next tick's
     * transaction.
     */
    @Override
    public void onTrade(String id, boolean sell, int units, double total, double neutralTotal) {
        if (id == null || units <= 0 || !Double.isFinite(total) || !Double.isFinite(neutralTotal)) {
            return;
        }
        LedgerSum sum = ledger.computeIfAbsent(new LedgerKey(plugin.clock().dayKey(), id), k -> new LedgerSum());
        if (sell) {
            sum.add(total - neutralTotal, 0.0, units, 0L);
        } else {
            sum.add(0.0, neutralTotal - total, 0L, units);
        }
    }

    /**
     * The catalog was rebuilt ({@link MarketService#reload()}, also after a GUI catalog edit): new
     * params, rows for new items, removed or {@code sim: false} items' events ended silently.
     */
    @Override
    public void catalogChanged() {
        params = buildParams(settings);
        engine = buildEngine();
        if (!started) {
            return;
        }
        seedRows();
        if (!running()) {
            publishNeutral();
            return;
        }
        long now = System.currentTimeMillis();
        if (endUnlisted(now) > 0) {
            commit();
        }
        republish(now, true);
    }

    // ---- reads (main thread, from the snapshot) ---------------------------------------------

    /**
     * What players see for {@code id} right now: its badge, the displayed price and how far that
     * is from the usual price. {@code null} for an id the market does not know; badge
     * {@link Badge#NONE} (and the usual price) while quiet or while the sim is off. The price and
     * usual price are read live, so they match {@link MarketService#price} after a trade; the
     * badge follows the snapshot (WANTED follows the stock).
     */
    public ItemStatus status(String id) {
        if (id == null) {
            return null;
        }
        MarketService market = plugin.market();
        MarketItem item = market == null ? null : market.item(id);
        if (item == null) {
            return null;
        }
        MarketState st = market.state(id);
        double usual = market.usualPrice(id);
        if (!Double.isFinite(usual)) {
            usual = item.ceiling();
        }
        Snapshot s = snapshot;
        ItemStatus base = s.active() ? s.status().get(id) : null;
        if (base == null) {
            return ItemStatus.plain(usual);
        }
        long stock = st == null ? 0L : st.stock();
        double price = market.price(id);
        if (!Double.isFinite(price)) {
            price = usual;
        }
        double pct = SimMath.pct(price, usual);
        long now = System.currentTimeMillis();
        Badge badge = base.badge();
        MarketEvent event = base.event();
        Phase phase = base.phase();
        long endsAt = base.endsAt();
        if (badge != Badge.WANTED && badge.shown() && event != null) {
            Badge nowBadge = event.badge(now);
            if (!nowBadge.shown()) {
                badge = Badge.NONE;
                event = null;
                phase = null;
                endsAt = 0L;
            } else {
                phase = event.phase(now);
            }
        }
        ItemParams p = s.params().get(id);
        if (badge == Badge.WANTED && stock > 0) {
            badge = Badge.NONE;
            event = null;
            phase = null;
            endsAt = 0L;
        } else if (badge == Badge.NONE && stock <= 0 && p != null && p.enabled()) {
            badge = Badge.WANTED;
            endsAt = 0L;
        }
        return new ItemStatus(badge, endsAt, base.multiplier(), usual, price, pct, event, phase);
    }

    /**
     * Announce-worthy news, newest first (by when it became news: a HOT/DEAL at the end of its
     * silent ramp), at most {@code limit}, from {@code sinceMs} on. Never a HOT/DEAL still ramping
     * (or stopped during its ramp), never a real-world move too small to be a headline. Empty
     * while the sim is off.
     */
    public List<MarketEvent> recentNews(int limit, long sinceMs) {
        Snapshot s = snapshot;
        if (!s.active() || limit <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<MarketEvent> out = new ArrayList<>();
        for (MarketEvent e : s.news()) {
            long t = newsTime(e);
            if (t > now || !visible(e, now)) {
                continue;
            }
            if (t < sinceMs) {
                break; // sorted newest first
            }
            out.add(e);
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** Live HOT/DEAL/UP/DOWN events players know about, soonest-ending first. Empty while off. */
    public List<MarketEvent> activeEvents() {
        Snapshot s = snapshot;
        if (!s.active()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<MarketEvent> out = new ArrayList<>();
        for (MarketEvent e : s.events()) {
            if (e.kind().mood() && e.active(now) && visible(e, now)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(MarketEvent::endsAt).thenComparingLong(MarketEvent::id));
        return List.copyOf(out);
    }

    /** Item ids showing any of {@code badges} right now, sorted by display name. Empty while off. */
    public List<String> activeIds(Badge... badges) {
        Snapshot s = snapshot;
        if (!s.active() || badges == null || badges.length == 0) {
            return List.of();
        }
        EnumSet<Badge> want = EnumSet.noneOf(Badge.class);
        for (Badge b : badges) {
            if (b != null && b.shown()) {
                want.add(b);
            }
        }
        if (want.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : s.params().keySet()) {
            ItemStatus st = status(id);
            if (st != null && want.contains(st.badge())) {
                out.add(id);
            }
        }
        out.sort(Comparator.comparing((String id) -> displayName(id), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(out);
    }

    /** The strongest season in effect (largest {@code |percent| x weight}), if any. Empty while off. */
    public Optional<SeasonCalendar.Active> season() {
        Snapshot s = snapshot;
        if (!s.active()) {
            return Optional.empty();
        }
        SeasonCalendar.Active best = null;
        double bestSize = -1.0;
        for (SeasonCalendar.Active a : s.seasons()) {
            double size = 0.0;
            for (Double v : a.season().percent().values()) {
                if (v != null && Double.isFinite(v)) {
                    size = Math.max(size, Math.abs(v));
                }
            }
            size *= a.weight();
            if (size > bestSize) {
                best = a;
                bestSize = size;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Chart markers for {@code id}: its HOT/DEAL/UP/DOWN/REAL events started at or after
     * {@code sinceMs} that players know about, the newest {@code limit} of them, oldest first.
     */
    public List<MarketEvent> markers(String id, long sinceMs, int limit) {
        Snapshot s = snapshot;
        if (!s.active() || id == null || limit <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<MarketEvent> out = new ArrayList<>();
        for (MarketEvent e : s.recent()) {
            if (!id.equals(e.itemId()) || e.startedAt() < sinceMs || !visible(e, now)) {
                continue;
            }
            if (e.kind().mood() || e.kind() == EventKind.REAL) {
                out.add(e);
            }
        }
        if (out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }
        return List.copyOf(out);
    }

    /** The plain singular label headlines use ({@code Iron Ingot}); the id for an unknown item. */
    public String displayName(String id) {
        if (id == null) {
            return "";
        }
        ItemParams p = params.get(id);
        if (p != null && p.name() != null && !p.name().isBlank()) {
            return p.name();
        }
        MarketService market = plugin.market();
        MarketItem item = market == null ? null : market.item(id);
        return item != null ? Headlines.name(item.label()) : id;
    }

    /**
     * The word {@link MarketLabels#summary} needs for {@code e}: the item's name (UP, DOWN, HOT,
     * DEAL), its plural news name (WANTED), the season's name (SEASON) or the real-world name
     * (REAL).
     */
    public String eventName(MarketEvent e) {
        if (e == null) {
            return "";
        }
        return switch (e.kind()) {
            case SEASON -> {
                Season s = seasonOf(e);
                yield s != null ? s.name() : SeasonCalendar.nameOf(seasonId(e));
            }
            case REAL -> {
                String name = realName(e);
                yield name.isBlank() ? displayName(e.itemId()) : name;
            }
            case WANTED -> plural(e.itemId());
            default -> displayName(e.itemId());
        };
    }

    /** {@code e} in one line ({@code &a▲ &fWheat went up 22%}). */
    public String summary(MarketEvent e) {
        return MarketLabels.summary(e, eventName(e));
    }

    /** The settings in use. */
    public SimSettings settings() {
        return settings;
    }

    /** The real-world price feed ({@code /hcm market sim real ...}). */
    public RealWorldFetcher realWorld() {
        return realWorld;
    }

    // ---- admin ------------------------------------------------------------------------------

    /**
     * {@code /hcm market news <item> <up|down|wanted> [percent] [quiet]}: a flash now, skipping the
     * schedule, cooldowns, hours, the hold and the daily caps, but not the size limits or the
     * headroom ({@link EventPlanner#forceNews}). Broadcast at once past the gate; {@code quiet}
     * leaves out chat, title, action bar, sound and particles. Logged with the sender.
     */
    public Result forceNews(CommandSender sender, String id, EventKind kind, Double pct, boolean quiet) {
        if (!active()) {
            return new Result(false, offMessage());
        }
        if (kind != EventKind.UP && kind != EventKind.DOWN && kind != EventKind.WANTED) {
            return new Result(false, "&cPick up, down or wanted.");
        }
        ItemParams p = simItem(id);
        if (p == null) {
            return new Result(false, unknownItem(id));
        }
        if (!p.enabled()) {
            return new Result(false, "&c" + p.id() + " has sim: false - the live market leaves it alone.");
        }
        long now = System.currentTimeMillis();
        long counter = ++manualCounter;
        EventPlanner.Forced f = EventPlanner.forceNews(candidateNow(p, now), kind, pct, now, settings, rng, counter);
        if (!f.ok()) {
            return new Result(false, "&c" + f.error());
        }
        MarketEvent e = MarketSimulator.headline(f.event(), p, settings, schedule, rng, counter, null);
        e = e.withText(e.headline(), renderLine(e));
        if (!quiet) {
            e = e.withAnnouncedAt(now);
        }
        MarketEvent saved = launch(e, quiet, now);
        String who = sender == null ? "?" : sender.getName();
        plugin.getLogger().info("[Market] " + who + " forced " + kind.name() + " on " + p.id()
                + (kind == EventKind.WANTED ? "" : " (" + MarketLabels.signedWhole(MarketLabels.pct(saved)) + ")")
                + (quiet ? " quietly" : ""));
        String msg = kind == EventKind.WANTED
                ? "&aWanted flash sent: &fCrate is looking for " + plural(p.id()) + "."
                : "&aNews flash sent: &f" + p.name() + " " + kind.name() + " "
                + MarketLabels.signedWhole(MarketLabels.pct(saved)) + " &7(" + money(saved.priceBefore())
                + " → " + money(saved.priceAfter()) + ")";
        return new Result(true, msg + (quiet ? " &8(quiet: no chat, title or sound)" : ""));
    }

    /**
     * {@code /hcm market sim hot|deal <item> [percent] [hours]}: a HOT or DEAL at full strength now
     * (no ramp), announced at once. Refused when the item already has an event, is sold out, or
     * the headroom leaves less than 5% ({@link EventPlanner#forceStory}).
     */
    public Result startStory(CommandSender sender, String id, EventKind kind, Double pct, Double hours) {
        if (!active()) {
            return new Result(false, offMessage());
        }
        if (kind == null || !kind.story()) {
            return new Result(false, "&cPick hot or deal.");
        }
        ItemParams p = simItem(id);
        if (p == null) {
            return new Result(false, unknownItem(id));
        }
        if (!p.enabled()) {
            return new Result(false, "&c" + p.id() + " has sim: false - the live market leaves it alone.");
        }
        long now = System.currentTimeMillis();
        long counter = ++manualCounter;
        EventPlanner.Forced f = EventPlanner.forceStory(candidateNow(p, now), kind, pct, hours, now, settings, rng,
                counter);
        if (!f.ok()) {
            return new Result(false, "&c" + f.error());
        }
        MarketEvent e = MarketSimulator.headline(f.event(), p, settings, schedule, rng, counter, null);
        e = e.withText(e.headline(), renderLine(e)).withAnnouncedAt(now);
        MarketEvent saved = launch(e, false, now);
        String who = sender == null ? "?" : sender.getName();
        String size = MarketLabels.signedWhole(MarketLabels.pct(saved));
        plugin.getLogger().info("[Market] " + who + " started " + kind.name() + " on " + p.id() + " (" + size
                + ", " + MarketLabels.endsIn(saved.endsAt() - now) + ")");
        return new Result(true, "&a" + p.name() + " is " + (kind == EventKind.HOT ? "HOT" : "on sale") + " now: &f"
                + size + " &7for " + Headlines.left(saved.endsAt() - now) + ".");
    }

    /**
     * {@code /hcm market sim stop <item|all>}: stop the item's (or every) live event with a 1 h
     * fade. {@code null} or {@code "all"} means every item.
     *
     * @return how many events were stopped (0 for an unknown item)
     */
    public int stop(String idOrAll) {
        if (!active()) {
            return 0;
        }
        String id = target(idOrAll);
        if (id != null && !params.containsKey(id)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<MarketEvent> before = events;
        events = new ArrayList<>(MarketSimulator.end(before, id, now, MarketSimulator.STOP_STOPPED, true, state));
        int n = queueChanged(before, events);
        if (n > 0) {
            commit();
            republish(now, true);
        }
        return n;
    }

    /**
     * {@code /hcm market sim reset <item|all>}: drift back to 0 and the item's (or every) live
     * event ended at once. {@code null} or {@code "all"} means every item.
     *
     * @return how many items were reset (0 for an unknown item, or while the sim is off)
     */
    public int reset(String idOrAll) {
        if (!active()) {
            return 0;
        }
        String id = target(idOrAll);
        if (id != null && !params.containsKey(id)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<MarketEvent> before = events;
        events = new ArrayList<>(MarketSimulator.end(before, id, now, MarketSimulator.STOP_RESET, false, state));
        queueChanged(before, events);
        MarketSimulator.zeroDrift(state, id, now);
        commit();
        republish(now, true);
        return id == null ? params.size() : 1;
    }

    /**
     * {@code /hcm market sim pause}: the same as {@code enabled: false} (every event ended, every
     * price back to its usual price), remembered across restarts until {@link #resume()}.
     */
    public void pause() {
        if (paused) {
            return;
        }
        boolean wasOn = running();
        paused = true;
        if (!started) {
            return;
        }
        long now = System.currentTimeMillis();
        if (wasOn) {
            goOff(now, MarketSimulator.STOP_PAUSED);
        } else {
            commit();
        }
        plugin.getLogger().info("Live market: paused - every price is back to its usual price.");
    }

    /** {@code /hcm market sim resume}: the same as turning it back on (drift 0, a fresh schedule). */
    public void resume() {
        if (!paused) {
            return;
        }
        paused = false;
        if (!started) {
            return;
        }
        long now = System.currentTimeMillis();
        if (running()) {
            goOn(now);
            plugin.getLogger().info("Live market: resumed.");
        } else {
            commit();
        }
    }

    /** True while {@code /hcm market sim pause} holds. */
    public boolean paused() {
        return paused;
    }

    // ---- admin screens ----------------------------------------------------------------------

    /**
     * {@code /hcm market sim status [item]}. Never shows the seed, and never which item or
     * direction a future event will take: only when the next one may come.
     */
    public List<String> statusLines(String idOrNull) {
        long now = System.currentTimeMillis();
        String id = target(idOrNull);
        if (id != null) {
            return itemLines(id, now);
        }
        List<String> out = new ArrayList<>();
        String mode = active() ? "&aON" : paused ? "&ePAUSED" : !settings.enabled() ? "&cOFF" : "&cNOT RUNNING";
        out.add("&6Crate Market sim: " + mode + " &7· tick " + settings.tickMinutes() + "m · last "
                + (lastTickAt > 0 && started ? Headlines.ago(Math.max(0L, now - lastTickAt)) : "never")
                + " · seed " + (rng != null ? "set" : "not set") + " · multiplier " + fmt2(settings.multiplierLo())
                + "-" + fmt2(settings.multiplierHi()));
        SimSettings.News n = settings.news();
        if (!n.enabled()) {
            out.add("&7News: off");
        } else {
            out.add("&7News: " + schedule.newsOn(plugin.clock().dayKey()) + "/" + n.maxPerDay() + " today · next due "
                    + clock(schedule.nextNewsAt(), now) + (newsHeld && active() ? " (held: waiting for a player)" : "")
                    + " · last flash " + lastFlash(now));
        }
        out.add("&7HOT: " + storyPart(EventKind.HOT, now) + " · DEAL: " + storyPart(EventKind.DEAL, now));
        String seasons;
        if (!settings.seasons().enabled()) {
            seasons = "off";
        } else {
            List<String> parts = new ArrayList<>();
            for (SeasonCalendar.Active a : MarketSimulator.seasonsAt(settings, now, zone())) {
                String fx = MarketLabels.seasonEffects(effective(a.season()), x -> x);
                parts.add(a.season().name() + (fx.isEmpty() ? "" : " (" + fx + ")"));
            }
            seasons = parts.isEmpty() ? "none" : String.join(", ", parts);
        }
        out.add("&7Season: " + seasons + " · Real world: "
                + (settings.real().enabled() ? "on (" + realWorld.summary() + ")" : "off"));
        double[] money = ledgerTotals(STATUS_MONEY_DAYS);
        out.add("&7Sim money, last " + STATUS_MONEY_DAYS + " days: sells " + signedMoney(money[0]) + " · buys saved "
                + signedMoney(money[1]));
        int sim = 0;
        int both = 0;
        int soldOut = 0;
        int fixed = 0;
        int off = 0;
        Map<String, MarketSimulator.Quote> quotes = quotes();
        for (ItemParams p : params.values()) {
            if (!p.enabled()) {
                off++;
                continue;
            }
            sim++;
            MarketSimulator.Quote q = quotes.get(p.id());
            if (!p.movable()) {
                fixed++;
            } else if (q == null || q.stock() <= 0) {
                soldOut++;
            } else if (bothWays(p, q)) {
                both++;
            }
        }
        out.add("&7Items: " + sim + " sim-enabled — " + both + " can move both ways, " + soldOut
                + " sold out (at ceiling), " + fixed + " fixed-price" + (off > 0 ? " · " + off + " with sim: false" : ""));
        Set<String> perms = new LinkedHashSet<>();
        PluginConfig.Market m = plugin.config().market();
        if (m.sellLimits() != null && m.sellLimits().bypassPermission() != null) {
            perms.add(m.sellLimits().bypassPermission());
        }
        if (m.buyLimits() != null && m.buyLimits().bypassPermission() != null) {
            perms.add(m.buyLimits().bypassPermission());
        }
        for (Player pl : plugin.getServer().getOnlinePlayers()) {
            for (String perm : perms) {
                if (!perm.isBlank() && pl.hasPermission(perm)) {
                    out.add("&c! " + pl.getName() + " holds " + perm
                            + " - HOT/DEAL/NEWS limits still apply; drift and seasons do not.");
                    break;
                }
            }
        }
        return out;
    }

    /**
     * {@code /hcm market sim preview <item> [days]}: one possible future of the displayed price
     * (drift from a throwaway random source, the events already running, the calendar; no new
     * events), as a sparkline of 6-hour points with the low and high. Changes nothing.
     */
    public List<String> preview(String id, int days) {
        ItemParams p = simItem(id);
        if (p == null) {
            return List.of(unknownItem(id));
        }
        int d = Math.max(1, Math.min(PREVIEW_MAX_DAYS, days <= 0 ? 7 : days));
        long now = System.currentTimeMillis();
        MarketSimulator.Quote q = quotes().get(p.id());
        if (q == null) {
            return List.of("&cNo market state for " + p.id() + " yet.");
        }
        boolean on = active() && p.enabled();
        if (!on) {
            return List.of("&7" + p.id() + ": the live market " + (p.enabled() ? "is off" : "leaves it alone (sim: false)")
                    + ", so its price stays at the usual " + money(PricingEngine.clamp(q.base(), p.floor(), p.ceiling()))
                    + ".");
        }
        List<MarketEvent> mine = new ArrayList<>();
        for (MarketEvent e : events) {
            if (p.id().equals(e.itemId())) {
                mine.add(e);
            }
        }
        ItemSimState st = state.get(p.id());
        double drift = st == null ? 0.0 : st.drift();
        long t = settings.tickMs();
        double dtH = settings.tickMinutes() / 60.0;
        SplittableRandom random = new SplittableRandom(); // throwaway: never the sim's seed or streams
        List<Double> points = new ArrayList<>();
        points.add(previewPrice(p, q, drift, mine, now));
        long end = now + d * SimMath.DAY_MS;
        for (long b = floor(now, t) + t; b <= end; b += t) {
            drift = SimMath.ouStep(drift, dtH, settings.drift().halfLifeHours(), p.sigma(), random.nextGaussian(),
                    settings.drift().maxFrac());
            if (b - now >= points.size() * PREVIEW_STEP_MS) {
                points.add(previewPrice(p, q, drift, mine, b));
            }
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double v : points) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        StringBuilder spark = new StringBuilder();
        for (double v : points) {
            int i = hi - lo < 1e-9 ? 3 : (int) Math.round((v - lo) / (hi - lo) * (SPARK.length() - 1));
            spark.append(SPARK.charAt(Math.max(0, Math.min(SPARK.length() - 1, i))));
        }
        List<String> out = new ArrayList<>();
        out.add("&6Preview: &f" + p.name() + " &7(" + p.id() + "), next " + d + " day" + (d == 1 ? "" : "s")
                + " - one possible future; nothing changes");
        out.add("&e" + spark);
        out.add("&7now " + money(points.get(0)) + " · low " + money(lo) + " · high " + money(hi)
                + " &8(6-hour points: drift, running events and seasons; no new events)");
        if (q.stock() <= 0) {
            out.add("&8Sold out: the price stays at its ceiling until someone sells some.");
        }
        return out;
    }

    /**
     * {@code /hcm market sim audit [days]}: the ledger per item over the last {@code days} local
     * days (today included), plus anything not flushed yet: the sell bonus, the buy discount, the
     * units, and what the sim paid out in total.
     */
    public List<String> audit(int days) {
        int d = Math.max(1, Math.min(MarketSimDao.LEDGER_KEEP_DAYS, days <= 0 ? 7 : days));
        Map<String, LedgerSum> byItem;
        try {
            byItem = ledgerByItem(d);
        } catch (SQLException e) {
            return List.of("&cCould not read the sim ledger: " + e.getMessage());
        }
        List<String> out = new ArrayList<>();
        out.add("&6Sim money, last " + d + " day" + (d == 1 ? "" : "s") + ":");
        double bonus = 0.0;
        double discount = 0.0;
        if (byItem.isEmpty()) {
            out.add("&7 No trades at a moved price yet.");
        }
        for (Map.Entry<String, LedgerSum> en : byItem.entrySet()) {
            LedgerSum s = en.getValue();
            bonus += s.bonus;
            discount += s.discount;
            out.add("&f " + en.getKey() + " &7sells " + signedMoney(s.bonus) + " (" + s.sold + " sold) · buys saved "
                    + signedMoney(s.discount) + " (" + s.bought + " bought)");
        }
        out.add("&7Total: sells " + signedMoney(bonus) + " · buys saved " + signedMoney(discount)
                + " &8· &fnet paid out by the sim: " + signedMoney(bonus + discount));
        out.add("&8Counts only trades made while the price was moved (multiplier not exactly 1).");
        return out;
    }

    // ---- for MarketNewsService and RealWorldFetcher (package) -------------------------------

    /** The "Right now" line (§6.3): every HOT, UP, DEAL and DOWN with its time left, then the season. */
    String rightNowLine(long now) {
        List<String> parts = new ArrayList<>();
        for (Badge b : new Badge[] {Badge.HOT, Badge.UP, Badge.DEAL, Badge.DOWN}) {
            for (String id : activeIds(b)) {
                ItemStatus st = status(id);
                if (st == null || st.badge() != b) {
                    continue;
                }
                String left = st.endsAt() > now ? Headlines.left(st.endsAt() - now) : "";
                parts.add(MarketLabels.rightNowPart(b, displayName(id), left));
            }
        }
        season().ifPresent(a -> {
            long ends = a.endsAt(zone());
            parts.add(MarketLabels.rightNowSeason(a.season().name(), ends > now ? Headlines.left(ends - now) : ""));
        });
        return MarketLabels.rightNow(parts);
    }

    /** Real-world impulses for the next live tick (main thread). */
    void queueReal(List<RealImpulse> impulses) {
        if (impulses == null || impulses.isEmpty() || !running()) {
            return;
        }
        for (RealImpulse ri : impulses) {
            if (ri != null && queued.stream().noneMatch(x -> x.tag().equals(ri.tag()))) {
                queued.add(ri);
            }
        }
    }

    /** How many real-world impulses wait for the next live tick. */
    int queuedReal() {
        return queued.size();
    }

    /** The zone local days and news hours run in ({@code clock.time_zone}). */
    ZoneId zone() {
        return plugin.clock().zone();
    }

    /** A price as the economy writes it, uncoloured; {@code ""} for none. */
    String money(double v) {
        return Double.isFinite(v) && v > 0 ? plugin.economy().format(v) : "";
    }

    /** What Crate pays for {@code id} at mid price {@code mid} (the bid, clamped to the band). */
    double bid(String id, double mid) {
        MarketItem item = id == null ? null : plugin.market().item(id);
        return item == null || !(mid > 0) ? mid : OrderMath.bid(engine, item, mid);
    }

    /** What Crate charges for {@code id} at mid price {@code mid} (the ask, clamped to the band). */
    double ask(String id, double mid) {
        MarketItem item = id == null ? null : plugin.market().item(id);
        return item == null || !(mid > 0) ? mid : OrderMath.ask(engine, item, mid);
    }

    /** "Limit N a day" for a DEAL or DOWN on {@code id}; 0 for any other kind or an unknown item. */
    long buyLimit(String id, EventKind kind) {
        ItemParams p = id == null ? null : params.get(id);
        if (p == null) {
            return 0L;
        }
        if (kind == EventKind.DEAL) {
            return p.eventBuyCap(settings.deal().buyLimitShare());
        }
        return kind == EventKind.DOWN ? p.eventBuyCap(settings.news().buyLimitShare()) : 0L;
    }

    /** The plural news name headlines use for {@code id} ({@code Iron Ingots}). */
    String plural(String id) {
        ItemParams p = id == null ? null : params.get(id);
        if (p != null && p.plural() != null && !p.plural().isBlank()) {
            return p.plural();
        }
        return displayName(id);
    }

    /** The configured season a SEASON row belongs to, or {@code null}. */
    Season seasonOf(MarketEvent e) {
        String sid = seasonId(e);
        for (Season s : settings.seasons().list()) {
            if (s.id().equalsIgnoreCase(sid)) {
                return s;
            }
        }
        return null;
    }

    /** A SEASON row's last day (the row ends at local midnight after it). */
    LocalDate lastDay(MarketEvent e) {
        return Instant.ofEpochMilli(Math.max(e.startedAt(), e.endsAt() - 1)).atZone(zone()).toLocalDate();
    }

    /**
     * A SEASON row's effects in words ({@code Wheat -4%}), each as it really applies: held to the
     * predictable cap {@code min(4.5%, 45% of market.spread)} ({@link SeasonCalendar#effective}).
     */
    String seasonEffects(MarketEvent e) {
        Season s = seasonOf(e);
        return s == null ? "" : MarketLabels.seasonEffects(effective(s), this::displayName);
    }

    /** {@code s}'s effects held to today's predictable cap. */
    private Map<String, Double> effective(Season s) {
        return SeasonCalendar.effective(s, settings.seasons().predictableCap(spread()));
    }

    /** When {@code e} became news: a HOT/DEAL at the end of its ramp, anything else when it started. */
    static long newsTime(MarketEvent e) {
        return e.newsTime();
    }

    /** Players may know about {@code e}: never a HOT/DEAL in (or stopped during) its silent ramp. */
    static boolean visible(MarketEvent e, long now) {
        if (e == null || now < e.startedAt()) {
            return false;
        }
        if (e.kind().story()) {
            long full = e.startedAt() + e.rampMs();
            return (e.stoppedAt() == null || e.stoppedAt() >= full) && now >= full;
        }
        return true;
    }

    // ---- the tick -----------------------------------------------------------------------------

    private void pump() {
        if (!running()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            clockCheck(now);
            if (floor(now, settings.tickMs()) > lastTickAt) {
                advance(now, true);
                housekeeping(now);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Live market: a tick failed; the next one tries again.", e);
        }
    }

    /**
     * Run every due boundary (§4): the older ones as replays, the newest live when {@code live};
     * then write the lot in one transaction, publish the snapshot, bump the jump counters and
     * deliver the tick's broadcast.
     */
    private void advance(long now, boolean live) {
        long before = lastTickAt;
        OnlineInfo online = live && news != null ? news.online() : OnlineInfo.NOBODY;
        List<RealImpulse> impulses = live ? List.copyOf(queued) : List.of();
        MarketSimulator.TickInput in = new MarketSimulator.TickInput(before, live, settings, rng, params, quotes(),
                state, List.copyOf(events), schedule, online, zone(), spread(), impulses, broadcast, popular,
                previous);
        MarketSimulator.TickResult r = MarketSimulator.run(in, before, now);
        if (r.ticks() == 0) {
            commit(); // nothing was due (a first start): still write the rows and the meta once
            republish(now, false);
            return;
        }
        if (r.live()) {
            queued.clear(); // used up by the live tick, applied or not
        }
        lastTickAt = r.boundary();
        newsHeld = r.newsHeld();
        if (r.skipped() > 0) {
            plugin.getLogger().info("Live market: skipped " + r.skipped() + " tick(s) older than max_catchup_hours ("
                    + settings.maxCatchupHours() + "h) while the server was off.");
        }

        // New rows get their stored `line` before they are written.
        IdentityHashMap<MarketEvent, MarketEvent> lined = new IdentityHashMap<>();
        List<MarketEvent> all = new ArrayList<>(r.started());
        all.addAll(r.changed());
        all.addAll(r.events());
        for (MarketEvent e : all) {
            if (e.id() == 0 && e.line() == null && !lined.containsKey(e)) {
                String line = renderLine(e);
                if (line != null && !line.isEmpty()) {
                    lined.put(e, e.withText(e.headline(), line));
                }
            }
        }
        AnnounceGate.Pending chosen = r.broadcast().orElse(null);
        if (chosen != null && chosen.type() == AnnounceGate.Type.STORY && chosen.event() != null) {
            // A HOT/DEAL quotes its % as of its announcement: its stored line says the same.
            MarketEvent e = lined.getOrDefault(chosen.event(), chosen.event());
            String line = renderLine(e);
            if (line != null && !line.isEmpty()) {
                lined.put(chosen.event(), e.withText(e.headline(), line));
            }
        }
        List<MarketEvent> working = swap(r.events(), lined);
        List<MarketEvent> started = swap(r.started(), lined);
        List<MarketEvent> changed = swap(r.changed(), lined);
        AnnounceGate.Pending cast = r.broadcast().orElse(null);
        if (cast != null && cast.event() != null && lined.containsKey(cast.event())) {
            cast = cast.withEvent(lined.get(cast.event()));
        }
        events = new ArrayList<>(working);

        Set<MarketEvent> inWorking = Collections.newSetFromMap(new IdentityHashMap<>());
        inWorking.addAll(working);
        for (MarketEvent e : changed) {
            if (e.id() > 0) {
                pendingUpdates.put(e.id(), e);
            }
        }
        List<MarketEvent> fresh = new ArrayList<>(started);
        fresh.addAll(changed);
        for (MarketEvent e : fresh) {
            if (e.id() == 0 && !inWorking.contains(e) && !containsSame(pendingInserts, e)) {
                pendingInserts.add(e); // started and already retired within this run
            }
        }
        for (MarketEvent e : changed) {
            if (e.kind() == EventKind.DEAL && MarketSimulator.STOP_SOLD_OUT.equals(e.stopReason())
                    && e.stoppedAt() != null && e.stoppedAt() > before) {
                plugin.getLogger().info(MarketLabels.plain(MarketLabels.soldOut(displayName(e.itemId()))));
            }
        }

        Map<MarketEvent, Long> ids = commit();
        if (cast != null && cast.event() != null) {
            Long id = ids.get(cast.event());
            if (id != null && id > 0) {
                cast = cast.withEvent(cast.event().withId(id));
            }
        }
        previous = r.multipliers();
        publish(r.boundary(), r.multipliers(), r.status(), r.breakdowns(), r.seasons());
        for (String id : r.jumped()) {
            bump(id);
        }
        if (cast != null && news != null) {
            try {
                news.broadcast(cast, false);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Live market: could not deliver an announcement.", ex);
            }
        }
    }

    /**
     * Write everything that is waiting in one transaction: the per-item rows, new events (the
     * working set's id-0 rows and retired ones not written yet), changed events, the meta and the
     * ledger buffer. On success the new ids go into the working set; on failure nothing is lost
     * from memory and the next call retries.
     *
     * @return each inserted event (as it was before it got its id) to its new id; empty on failure
     */
    private Map<MarketEvent, Long> commit() {
        Database db = plugin.database();
        if (db == null) {
            return Map.of();
        }
        List<MarketEvent> inserts = new ArrayList<>(pendingInserts);
        for (MarketEvent e : events) {
            if (e.id() == 0) {
                inserts.add(e);
            }
        }
        Map<Long, MarketEvent> updates = new LinkedHashMap<>(pendingUpdates);
        for (MarketEvent e : events) {
            if (e.id() > 0 && updates.containsKey(e.id())) {
                updates.put(e.id(), e);
            }
        }
        Map<LedgerKey, LedgerSum> flushing = ledger;
        ledger = new LinkedHashMap<>();
        Map<String, String> meta = metaRows();
        List<ItemSimState> rows = new ArrayList<>(state.values());
        IdentityHashMap<MarketEvent, Long> ids = new IdentityHashMap<>();
        try {
            db.transaction(c -> {
                dao.saveStates(c, rows);
                for (MarketEvent e : inserts) {
                    long id = dao.insertEvent(c, e);
                    // 0: a SEASON/REAL row with that tag is already stored; never try it again.
                    ids.put(e, id > 0 ? id : -1L);
                    if (e.kind() == EventKind.REAL) {
                        dao.markApplied(c, e); // the fetched move is used up only now, with its row
                    }
                }
                for (MarketEvent e : updates.values()) {
                    dao.updateEvent(c, e);
                }
                dao.saveMeta(c, meta);
                for (Map.Entry<LedgerKey, LedgerSum> en : flushing.entrySet()) {
                    LedgerSum s = en.getValue();
                    dao.addLedger(c, en.getKey().day(), en.getKey().itemId(), s.bonus, s.discount, s.sold, s.bought);
                }
                return null;
            });
        } catch (SQLException | RuntimeException ex) {
            for (Map.Entry<LedgerKey, LedgerSum> en : flushing.entrySet()) {
                ledger.merge(en.getKey(), en.getValue(), LedgerSum::plus);
            }
            for (MarketEvent e : updates.values()) {
                pendingUpdates.put(e.id(), e);
            }
            plugin.getLogger().severe("Live market: could not save (" + ex.getMessage()
                    + "); it carries on in memory and tries again at the next tick.");
            return Map.of();
        }
        pendingUpdates.clear();
        pendingInserts.clear();
        for (int i = 0; i < events.size(); i++) {
            Long id = ids.get(events.get(i));
            if (id != null) {
                events.set(i, id > 0 ? events.get(i).withId(id) : stored(events.get(i)));
            }
        }
        for (MarketEvent e : inserts) {
            Long id = ids.get(e);
            if (id != null && id > 0) {
                newsCache.put(id, e.withId(id));
            }
        }
        for (MarketEvent e : updates.values()) {
            newsCache.put(e.id(), e);
        }
        return ids;
    }

    /**
     * A working-set row whose insert hit the unique {@code (kind, tag)} index: the row already
     * stored with that tag takes its place, so it is not shown or announced a second time and
     * the next tick sees the tag. Id {@code -1} (never written again) if that cannot be read.
     */
    private MarketEvent stored(MarketEvent lost) {
        try {
            Optional<MarketEvent> row = dao.findByTag(lost.kind(), lost.tag());
            if (row.isPresent()) {
                newsCache.put(row.get().id(), row.get());
                return row.get();
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Live market: could not read the stored " + lost.kind() + " " + lost.tag()
                    + ": " + ex.getMessage());
        }
        return lost.withId(-1L);
    }

    /**
     * SEASON rows the working set lacks (by id), e.g. one whose season an admin extended past the
     * row's stored end: the simulator keeps them while the calendar runs their season, so it
     * never makes a second row for the same {@code id:year}.
     */
    private void addSeasonRows(List<MarketEvent> rows) {
        Set<Long> have = new HashSet<>();
        for (MarketEvent e : events) {
            have.add(e.id());
        }
        for (MarketEvent e : rows) {
            if (e.kind() == EventKind.SEASON && e.id() > 0 && have.add(e.id())) {
                events.add(e);
            }
        }
    }

    /** {@link #addSeasonRows} from the database (a reload may have moved a season's dates). */
    private void reloadSeasonRows(long now) {
        try {
            addSeasonRows(dao.loadSeasons(now - SEASON_ROWS_MS));
        } catch (SQLException e) {
            plugin.getLogger().warning("Live market: could not read the season rows: " + e.getMessage());
        }
    }

    /** Queue again the real-world moves fetched earlier that no REAL event has used yet. */
    private void requeueReal(long now) {
        if (running() && settings.real().enabled()) {
            queueReal(realWorld.unapplied(now));
        }
    }

    /**
     * The host clock went back (a bad clock at boot, a restored snapshot, a big NTP correction):
     * the stored last tick is more than a tick ahead of now, and waiting for the clock to catch
     * up would freeze the market with no word. Carry on from now instead, without replaying, and
     * say so once. A broadcast "in the future" would also hold every announcement back.
     */
    private void clockCheck(long now) {
        long t = settings.tickMs();
        if (MarketSimulator.clockWentBack(lastTickAt, now, t)) {
            plugin.getLogger().warning("Live market: the clock is " + approx(lastTickAt - now)
                    + " behind the last tick (was it moved back?) - carrying on from now.");
            lastTickAt = floor(now, t);
            previous = null;
        }
        if (broadcast.lastAt() > now) {
            broadcast = new BroadcastState(broadcast.day(), broadcast.count(), now, broadcast.introDone());
        }
    }

    private Map<String, String> metaRows() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(MarketSimDao.Meta.LAST_TICK_AT, Long.toString(lastTickAt));
        meta.put(MarketSimDao.Meta.WAS_ENABLED, MarketSimDao.Meta.flag(settings.enabled()));
        meta.put(MarketSimDao.Meta.PAUSED, MarketSimDao.Meta.flag(paused));
        meta.put(MarketSimDao.Meta.MANUAL_COUNTER, Long.toString(manualCounter));
        meta.putAll(MarketSimDao.Meta.of(schedule));
        meta.putAll(MarketSimDao.Meta.of(broadcast));
        meta.putAll(realWorld.meta());
        return meta;
    }

    /** Daily prune (first tick of a local day), the hourly popular set, the real-world due check. */
    private void housekeeping(long now) {
        long today = plugin.clock().dayKey();
        if (today != pruneDay) {
            pruneDay = today;
            try {
                long quoteDay = LocalDate.now(zone()).toEpochDay() - MarketSimDao.QUOTE_KEEP_DAYS;
                MarketSimDao.Pruned p = dao.prune(now - settings.keepDays() * SimMath.DAY_MS,
                        today - MarketSimDao.LEDGER_KEEP_DAYS, quoteDay);
                if (p.events() + p.ledger() + p.quotes() > 0) {
                    plugin.getLogger().fine("Live market: pruned " + p.events() + " old event(s), " + p.ledger()
                            + " ledger row(s), " + p.quotes() + " cached quote(s).");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Live market: could not prune old rows: " + e.getMessage());
            }
        }
        if (now - popularAt >= POPULAR_REFRESH_MS) {
            popularAt = now;
            try {
                popular = Set.copyOf(dao.tradedSince(today - (POPULAR_DAYS - 1)));
            } catch (SQLException e) {
                plugin.getLogger().warning("Live market: could not read which items are popular: " + e.getMessage());
            }
        }
        realWorld.maybeFetch(now);
    }

    // ---- going off and on -------------------------------------------------------------------

    /** {@code enabled: false} or pause: every event ends at once, {@code M = 1}, every jump bumped. */
    private void goOff(long now, String reason) {
        cancelPump();
        realWorld.cancel();
        queued.clear();
        endAll(now, reason);
        previous = null;
        commit();
        publishNeutral();
        bumpAll();
    }

    /** Re-enable or resume: drift 0, the schedule as on a first enable, no replay across the gap. */
    private void goOn(long now) {
        MarketSimulator.enable(now, rng, schedule, state);
        lastTickAt = floor(now, settings.tickMs());
        previous = null;
        reloadSeasonRows(now);
        requeueReal(now);
        commit();
        republish(now, false);
        bumpAll();
        armPump();
    }

    private void endAll(long now, String reason) {
        List<MarketEvent> before = events;
        events = new ArrayList<>(MarketSimulator.end(before, null, now, reason, false, state));
        queueChanged(before, events);
    }

    /** End (at once, silently) the live events of items no longer in the catalog or set {@code sim: false}. */
    private int endUnlisted(long now) {
        Set<String> gone = new TreeSet<>();
        for (MarketEvent e : events) {
            if (e.kind() == EventKind.SEASON || e.itemId() == null || !e.active(now)) {
                continue;
            }
            ItemParams p = params.get(e.itemId());
            if (p == null || !p.enabled()) {
                gone.add(e.itemId());
            }
        }
        for (String id : gone) {
            List<MarketEvent> before = events;
            events = new ArrayList<>(MarketSimulator.end(before, id, now, MarketSimulator.STOP_REMOVED, false, state));
            queueChanged(before, events);
        }
        return gone.size();
    }

    /** Queue the rows {@code after} changed (same order as {@code before}) for the next commit. */
    private int queueChanged(List<MarketEvent> before, List<MarketEvent> after) {
        int n = 0;
        for (int i = 0; i < after.size() && i < before.size(); i++) {
            MarketEvent a = after.get(i);
            if (a != before.get(i)) {
                n++;
                if (a.id() > 0) {
                    pendingUpdates.put(a.id(), a);
                }
            }
        }
        return n;
    }

    /** Add a forced event, write it, publish, and announce it past the gate. */
    private MarketEvent launch(MarketEvent e, boolean quiet, long now) {
        MarketSimulator.recordStart(e, state);
        events.add(e);
        if (!quiet) {
            broadcast.record(now, plugin.clock().dayKeyAt(now));
        }
        Map<MarketEvent, Long> ids = commit();
        Long id = ids.get(e);
        MarketEvent saved = id != null && id > 0 ? e.withId(id) : e;
        republish(now, true);
        if (news != null) {
            try {
                news.broadcast(AnnounceGate.forced(saved), quiet);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Live market: could not deliver an announcement.", ex);
            }
        }
        return saved;
    }

    // ---- snapshot ---------------------------------------------------------------------------

    /** Evaluate at {@code t} and publish; with {@code jumps}, bump every item whose {@code M} moved over 1%. */
    private void republish(long t, boolean jumps) {
        if (!running()) {
            publishNeutral();
            return;
        }
        MarketSimulator.Evaluation ev = MarketSimulator.evaluate(settings, params, quotes(), state, events, zone(),
                spread(), t);
        if (jumps) {
            Snapshot s = snapshot;
            for (Map.Entry<String, Double> en : ev.multipliers().entrySet()) {
                Double was = s.active() ? s.multipliers().get(en.getKey()) : null;
                double before = was == null ? 1.0 : was;
                if (Math.abs(en.getValue() - before) > MarketSimulator.JUMP) {
                    bump(en.getKey());
                }
            }
        }
        previous = ev.multipliers();
        publish(t, ev.multipliers(), ev.status(), ev.breakdowns(), ev.seasons());
    }

    private void publish(long at, Map<String, Double> mult, Map<String, ItemStatus> status,
                         Map<String, MoodEngine.Breakdown> parts, List<SeasonCalendar.Active> seasons) {
        for (MarketEvent e : events) {
            if (e.id() > 0) {
                newsCache.put(e.id(), e);
            }
        }
        long cutoff = at - NEWS_CACHE_MS;
        newsCache.values().removeIf(e -> newsTime(e) < cutoff && !e.active(at));
        while (newsCache.size() > NEWS_CACHE_MAX) {
            newsCache.pollFirstEntry();
        }
        List<MarketEvent> recent = new ArrayList<>(newsCache.values());
        for (MarketEvent e : events) {
            if (e.id() <= 0) {
                recent.add(e);
            }
        }
        recent.sort(Comparator.comparingLong(MarketEvent::startedAt).thenComparingLong(MarketEvent::id));
        List<MarketEvent> worthy = new ArrayList<>();
        for (MarketEvent e : recent) {
            if (worthy(e)) {
                worthy.add(e);
            }
        }
        worthy.sort(Comparator.comparingLong(MarketSimService::newsTime).thenComparingLong(MarketEvent::id).reversed());
        snapshot = new Snapshot(true, at, params, Map.copyOf(mult), Map.copyOf(status), Map.copyOf(parts),
                List.copyOf(events), List.copyOf(seasons), List.copyOf(worthy), List.copyOf(recent));
    }

    private void publishNeutral() {
        snapshot = Snapshot.NEUTRAL;
    }

    /** News players are told about: every flash, WANTED and season; a HOT/DEAL not stopped in its ramp; a REAL headline. */
    private static boolean worthy(MarketEvent e) {
        return switch (e.kind()) {
            case UP, DOWN, WANTED, SEASON -> true;
            case HOT, DEAL -> e.stoppedAt() == null || e.stoppedAt() >= e.startedAt() + e.rampMs();
            case REAL -> e.announceDueAt() != null && !MarketLabels.plain(e.headline()).isEmpty();
        };
    }

    private void bump(String id) {
        jumpSeq.merge(id, 1L, Long::sum);
    }

    private void bumpAll() {
        for (String id : params.keySet()) {
            bump(id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private SortedMap<String, ItemParams> buildParams(SimSettings s) {
        TreeMap<String, ItemParams> out = new TreeMap<>();
        MarketService market = plugin.market();
        if (market == null) {
            return Collections.unmodifiableSortedMap(out);
        }
        var parsed = plugin.config().marketSim();
        for (MarketItem item : market.catalog()) {
            out.put(item.id(), ItemParams.of(item, parsed.override(item.id()), s));
        }
        return Collections.unmodifiableSortedMap(out);
    }

    private PricingEngine buildEngine() {
        PluginConfig.Market m = plugin.config().market();
        return new PricingEngine(m.elasticity(), m.inertia(), m.spread());
    }

    private void seedRows() {
        for (String id : params.keySet()) {
            state.putIfAbsent(id, ItemSimState.fresh(id));
        }
    }

    private Map<String, MarketSimulator.Quote> quotes() {
        Map<String, MarketSimulator.Quote> out = new HashMap<>();
        MarketService market = plugin.market();
        if (market == null) {
            return out;
        }
        for (String id : params.keySet()) {
            MarketState st = market.state(id);
            if (st != null) {
                out.put(id, new MarketSimulator.Quote(st.currentPrice(), st.stock()));
            }
        }
        return out;
    }

    private double spread() {
        return plugin.config().market().spread();
    }

    private int simItems() {
        int n = 0;
        for (ItemParams p : params.values()) {
            if (p.enabled()) {
                n++;
            }
        }
        return n;
    }

    private EventPlanner.Candidate candidateNow(ItemParams p, long now) {
        Map<String, MarketSimulator.Quote> quotes = quotes();
        MarketSimulator.Evaluation ev = MarketSimulator.evaluate(settings, params, quotes, state, events, zone(),
                spread(), now);
        Double m0 = ev.multipliers().get(p.id());
        double m = m0 == null ? 1.0 : m0;
        MoodEngine.Breakdown bd = ev.breakdowns().get(p.id());
        return MarketSimulator.candidate(p, quotes.get(p.id()), state.get(p.id()), m, bd == null ? m : bd.raw(),
                MarketSimulator.busy(events, p.id(), now), popular.contains(p.id()), settings);
    }

    /** The stored {@code line} for a new row (§6.2, &-coded; feeds strip the codes). */
    private String renderLine(MarketEvent e) {
        String id = e.itemId();
        String name = displayName(id);
        return switch (e.kind()) {
            case UP -> MarketLabels.newsLine(e, name, money(bid(id, e.priceBefore())), money(bid(id, e.priceAfter())), 0L);
            case DOWN -> MarketLabels.newsLine(e, name, money(ask(id, e.priceBefore())), money(ask(id, e.priceAfter())),
                    buyLimit(id, EventKind.DOWN));
            case HOT, DEAL -> {
                long from = e.announcedAt() != null ? e.announcedAt()
                        : e.announceDueAt() != null ? e.announceDueAt() : e.startedAt();
                yield MarketLabels.storyLine(e, name, Headlines.left(Math.max(0L, e.endsAt() - from)),
                        buyLimit(id, e.kind()));
            }
            case WANTED -> MarketLabels.wantedLine();
            case SEASON -> MarketLabels.seasonLine(seasonEffects(e), MarketLabels.untilDate(lastDay(e)));
            case REAL -> MarketLabels.realLine(e, name);
        };
    }

    private String realName(MarketEvent e) {
        String symbol = null;
        if (e.tag() != null) {
            String[] parts = e.tag().split(":");
            if (parts.length >= 3) {
                symbol = parts[1];
            }
        }
        String fallback = "";
        for (RealSymbol r : settings.real().symbols()) {
            if (!r.item().equalsIgnoreCase(e.itemId() == null ? "" : e.itemId())) {
                continue;
            }
            if (symbol != null && (symbol.equalsIgnoreCase(r.stooq()) || symbol.equalsIgnoreCase(r.yahoo()))) {
                return r.name();
            }
            if (fallback.isEmpty()) {
                fallback = r.name();
            }
        }
        return fallback;
    }

    private static String seasonId(MarketEvent e) {
        String tag = e.tag() == null ? "" : e.tag();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(0, colon);
    }

    /** {@code null} for "every item" ({@code null}, blank or {@code all}); else the lower-cased id. */
    private static String target(String idOrAll) {
        if (idOrAll == null || idOrAll.isBlank() || "all".equalsIgnoreCase(idOrAll.trim())) {
            return null;
        }
        return idOrAll.trim().toLowerCase(Locale.ROOT);
    }

    private ItemParams simItem(String id) {
        String key = target(id);
        return key == null ? null : params.get(key);
    }

    private static String unknownItem(String id) {
        return "&cNo market item '" + (id == null ? "" : id) + "'.";
    }

    private String offMessage() {
        return paused ? "&eThe live market is paused (/hcm market sim resume)."
                : "&eThe live market is off (market.sim.enabled: false).";
    }

    private boolean bothWays(ItemParams p, MarketSimulator.Quote q) {
        if (!p.movable() || q == null || q.stock() <= 0) {
            return false;
        }
        double b = PricingEngine.clamp(q.base(), p.floor(), p.ceiling());
        return SimMath.headroomUp(b, p.floor(), p.ceiling(), 1.0, settings.multiplierHi()) > BOTH_WAYS_MIN
                && SimMath.headroomDown(b, p.floor(), p.ceiling(), 1.0, settings.multiplierLo()) > BOTH_WAYS_MIN;
    }

    private void warnBothWays() {
        Map<String, MarketSimulator.Quote> quotes = quotes();
        int sim = 0;
        int both = 0;
        for (ItemParams p : params.values()) {
            if (!p.enabled()) {
                continue;
            }
            sim++;
            if (bothWays(p, quotes.get(p.id()))) {
                both++;
            }
        }
        if (sim > 0 && both < BOTH_WAYS_WARN * sim) {
            plugin.getLogger().warning("Live market: only " + both + " of " + sim + " sim-enabled item(s) can move "
                    + "both ways right now (the rest are sold out, at a price limit or fixed-price), so HOT, DEAL "
                    + "and news flashes have little to work with.");
        }
    }

    private List<String> itemLines(String id, long now) {
        ItemParams p = params.get(id);
        if (p == null) {
            return List.of(unknownItem(id));
        }
        MarketService market = plugin.market();
        MarketSimulator.Quote q = quotes().get(id);
        long stock = q == null ? 0L : q.stock();
        double usual = market.usualPrice(id);
        double price = market.price(id);
        ItemSimState st = state.getOrDefault(id, ItemSimState.fresh(id));
        List<MarketEvent> mine = new ArrayList<>();
        for (MarketEvent e : events) {
            if (id.equals(e.itemId()) && e.active(now)) {
                mine.add(e);
            }
        }
        double season = 0.0;
        for (SeasonCalendar.Active a : MarketSimulator.seasonsAt(settings, now, zone())) {
            season += a.effect(id);
        }
        MoodEngine.Breakdown bd = MoodEngine.breakdown(p, st.drift(), mine, season, now, settings, spread());
        double m = active() ? bd.multiplier() : 1.0;
        List<String> out = new ArrayList<>();
        out.add("&f" + id + " &7· stock " + stock + "/" + p.fullStock() + " · usual " + money(usual) + " · now "
                + money(price) + " (" + MarketLabels.moodPct(SimMath.pct(price, usual)) + ")");
        if (!p.enabled()) {
            out.add("&7  sim: false - this item never moves on its own (x1.000)");
            return out;
        }
        MarketEvent best = null;
        for (MarketEvent e : mine) {
            if (e.kind().mood() && (best == null || e.startedAt() > best.startedAt())) {
                best = e;
            }
        }
        String event = best == null ? "none"
                : best.kind().name() + " " + frac(best.contribution(now)) + " (" + best.phase(now).name() + ", "
                + MarketLabels.endsIn(best.endsAt() - now) + " left)";
        out.add("&7  drift " + frac(bd.drift()) + " (" + (p.lively() ? "lively " : "calm ")
                + String.format(Locale.ROOT, "%.1f%%", p.sigma() * 100.0) + ") · event " + event + " · season "
                + frac(bd.season()) + " · real " + frac(bd.real()) + " &f→ x" + String.format(Locale.ROOT, "%.3f", m));
        double b = Double.isFinite(usual) ? usual : p.ceiling();
        double up = SimMath.headroomUp(b, p.floor(), p.ceiling(), m, settings.multiplierHi());
        double down = SimMath.headroomDown(b, p.floor(), p.ceiling(), m, settings.multiplierLo());
        long rest = st.featuredUntil() > 0 ? st.featuredUntil() + settings.cooldownDays() * SimMath.DAY_MS : 0L;
        String rests = rest > now
                ? MarketLabels.untilDate(Instant.ofEpochMilli(rest).atZone(zone()).toLocalDate()) : "-";
        out.add("&7  headroom up " + String.format(Locale.ROOT, "%.1f%%", up * 100.0) + " / down "
                + String.format(Locale.ROOT, "%.1f%%", down * 100.0) + " · rests until " + rests + " · last news "
                + (st.lastNewsAt() > 0 ? Headlines.ago(Math.max(0L, now - st.lastNewsAt())) : "never")
                + (popular.contains(id) ? " · popular" : ""));
        return out;
    }

    private double previewPrice(ItemParams p, MarketSimulator.Quote q, double drift, List<MarketEvent> mine, long t) {
        double season = 0.0;
        for (SeasonCalendar.Active a : MarketSimulator.seasonsAt(settings, t, zone())) {
            season += a.effect(p.id());
        }
        MoodEngine.Breakdown bd = MoodEngine.breakdown(p, drift, mine, season, t, settings, spread());
        return SimMath.displayPrice(q.base(), q.stock(), bd.multiplier(), p.floor(), p.ceiling());
    }

    private String storyPart(EventKind kind, long now) {
        SimSettings.Story k = settings.story(kind);
        if (k == null || !k.enabled()) {
            return "off";
        }
        List<String> parts = new ArrayList<>();
        for (MarketEvent e : events) {
            if (e.kind() != kind || !e.active(now)) {
                continue;
            }
            parts.add(e.itemId() + " " + MarketLabels.signedWhole(e.strength() * 100.0) + " ("
                    + phaseWords(e.phase(now)) + ", " + Headlines.left(Math.max(0L, e.endsAt() - now)) + " left)");
        }
        if (!parts.isEmpty()) {
            return String.join(", ", parts);
        }
        return "none" + (active() ? " (next window in " + approx(schedule.nextAt(kind) - now) + ")" : "");
    }

    private static String phaseWords(Phase p) {
        return switch (p) {
            case RAMP -> "warming up, not announced yet";
            case FULL -> "full";
            case FADING -> "cooling off";
            default -> p.name().toLowerCase(Locale.ROOT);
        };
    }

    private String lastFlash(long now) {
        MarketEvent last = null;
        for (MarketEvent e : newsCache.values()) {
            if ((e.kind().news() || e.kind() == EventKind.WANTED) && (last == null || e.startedAt() > last.startedAt())) {
                last = e;
            }
        }
        for (MarketEvent e : events) {
            if ((e.kind().news() || e.kind() == EventKind.WANTED) && (last == null || e.startedAt() > last.startedAt())) {
                last = e;
            }
        }
        return last == null ? "never" : Headlines.ago(Math.max(0L, now - last.startedAt()));
    }

    /** {@code 19:40} today, else {@code Mon 07:40}. */
    private String clock(long at, long now) {
        if (at <= 0) {
            return "now";
        }
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone());
        ZonedDateTime n = Instant.ofEpochMilli(now).atZone(zone());
        String hm = String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
        if (t.toLocalDate().equals(n.toLocalDate())) {
            return hm + (at <= now ? " (due)" : "");
        }
        DayOfWeek dow = t.getDayOfWeek();
        return dow.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + hm;
    }

    /** {@code ~5h}, {@code ~2d}, {@code now}. */
    private static String approx(long ms) {
        if (ms <= 0) {
            return "now";
        }
        if (ms < SimMath.HOUR_MS) {
            return "~" + Math.max(1L, ms / SimMath.MINUTE_MS) + "m";
        }
        if (ms < 48 * SimMath.HOUR_MS) {
            return "~" + Math.round(ms / (double) SimMath.HOUR_MS) + "h";
        }
        return "~" + Math.round(ms / (double) SimMath.DAY_MS) + "d";
    }

    /** A fraction as a signed percent to one decimal ({@code +1.2%}); {@code 0} when it rounds to nothing. */
    private static String frac(double f) {
        double v = Double.isFinite(f) ? f * 100.0 : 0.0;
        if (Math.abs(v) < 0.05) {
            return "0";
        }
        return String.format(Locale.ROOT, "%+.1f%%", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private String signedMoney(double v) {
        if (!Double.isFinite(v) || Math.abs(v) < 0.005) {
            return plugin.economy().format(0.0);
        }
        return (v < 0 ? "-" : "+") + plugin.economy().format(Math.abs(v));
    }

    private double[] ledgerTotals(int days) {
        double[] out = new double[2];
        try {
            for (LedgerSum s : ledgerByItem(days).values()) {
                out[0] += s.bonus;
                out[1] += s.discount;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Live market: could not read the ledger: " + e.getMessage());
        }
        return out;
    }

    /** The ledger of the last {@code days} local days (today included) by item, unflushed trades included. */
    private Map<String, LedgerSum> ledgerByItem(int days) throws SQLException {
        long from = plugin.clock().dayKey() - (days - 1);
        Map<String, LedgerSum> byItem = new TreeMap<>();
        for (MarketSimDao.LedgerRow r : dao.ledgerSince(from)) {
            byItem.computeIfAbsent(r.itemId(), k -> new LedgerSum())
                    .add(r.sellBonus(), r.buyDiscount(), r.unitsSold(), r.unitsBought());
        }
        for (Map.Entry<LedgerKey, LedgerSum> en : ledger.entrySet()) {
            if (en.getKey().day() >= from) {
                LedgerSum s = en.getValue();
                byItem.computeIfAbsent(en.getKey().itemId(), k -> new LedgerSum())
                        .add(s.bonus, s.discount, s.sold, s.bought);
            }
        }
        return byItem;
    }

    private void armPump() {
        cancelPump();
        pump = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pump, PUMP_DELAY_TICKS, PUMP_PERIOD_TICKS);
    }

    private void cancelPump() {
        if (pump != null) {
            pump.cancel();
            pump = null;
        }
    }

    private static long floor(long t, long step) {
        long s = Math.max(SimMath.MINUTE_MS, step);
        return Math.floorDiv(t, s) * s;
    }

    /** The stricter of two caps where 0 means none. */
    private static long tighter(long a, long b) {
        if (a <= 0) {
            return Math.max(0L, b);
        }
        if (b <= 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static List<MarketEvent> swap(List<MarketEvent> in, IdentityHashMap<MarketEvent, MarketEvent> map) {
        if (map.isEmpty()) {
            return in;
        }
        List<MarketEvent> out = new ArrayList<>(in.size());
        for (MarketEvent e : in) {
            MarketEvent r = map.get(e);
            out.add(r != null ? r : e);
        }
        return out;
    }

    private static boolean containsSame(List<MarketEvent> list, MarketEvent e) {
        for (MarketEvent x : list) {
            if (x == e) {
                return true;
            }
        }
        return false;
    }
}
