package com.dierks.homecraft.market.sim;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.MarketLabels;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.MarketSimDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The optional real-world layer's network side (spec §9.2): once a trading day it fetches the
 * configured commodity closes, caches them, and queues one small REAL impulse per symbol for the
 * next live tick. Everything it can move is capped by the pure code ({@link RealQuotes#impulse}:
 * at most ±4.5%, and seasons plus real stay under the predictable cap); only a number comes from
 * outside, never text.
 *
 * <p><b>Schedule.</b> Monday to Friday, once {@code fetch_time} (local) has passed and today has not
 * been fetched yet; a failed fetch is retried every 60 minutes, at most 3 times a day. Symbols are
 * fetched one after another, 2 s apart.
 *
 * <p><b>Threading.</b> One {@link HttpClient} (connect 10 s, request {@code timeout_seconds}),
 * {@code User-Agent: HomeCraftManagement/<version>}, https only, the body read through
 * {@code ofInputStream} and cut off at 256 KB. The requests and the (pure) parsing run off the
 * main thread; the results come back with {@code runTask}, guarded by {@code plugin.isEnabled()},
 * and every database write and state change happens there. {@link #cancel()} (disable, pause,
 * {@code real_world.enabled: false}) cancels whatever is in flight and drops late results.
 *
 * <p><b>Failure.</b> Any error keeps the cached quotes and lets existing impulses fade. One WARN
 * per symbol per local day, FINE after that. {@code real status} shows the last fetch and error.
 */
public final class RealWorldFetcher {

    /** The largest reply read. */
    static final int MAX_BODY_BYTES = 256 * 1024;
    /** Symbols are fetched this far apart. */
    static final long SPACING_MS = 2_000L;
    /** Fetch attempts per local day. */
    static final int MAX_ATTEMPTS = 3;
    /** A failed fetch is retried this much later. */
    static final long RETRY_MS = SimMath.HOUR_MS;
    /** The newest close must be at most this many days old. */
    static final int MAX_AGE_DAYS = 4;
    /** Connection timeout. */
    static final int CONNECT_SECONDS = 10;
    /** {@code real test} prints this many of the newest closes. */
    static final int ECHO_CLOSES = 5;

    /** One symbol to fetch. {@code itemId} is {@code null} for a symbol no row maps. */
    private record Job(String symbol, String itemId, String name, URI uri, String provider) {
    }

    /** One symbol's outcome: its closes, or why there are none. */
    private record Fetched(Job job, List<RealQuotes.DailyClose> closes, String error) {
    }

    private final HomeCraftManagement plugin;
    private final MarketSimService sim;
    private final MarketSimDao dao;
    private final Function<URI, CompletableFuture<String>> fetch;
    private final String userAgent;
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpClient http;

    // main thread only
    private boolean busy;
    private long lastFetchDay = Schedule.NO_DAY;
    private long attemptDay = Schedule.NO_DAY;
    private int attempts;
    private long lastAttemptAt;
    private String lastError = "";
    private final Map<String, Long> warned = new HashMap<>();

    RealWorldFetcher(HomeCraftManagement plugin, MarketSimService sim, MarketSimDao dao) {
        this(plugin, sim, dao, null);
    }

    /**
     * @param fetch the HTTP GET to use ({@code null} = the real one); tests inject their own so no
     *              network is needed
     */
    RealWorldFetcher(HomeCraftManagement plugin, MarketSimService sim, MarketSimDao dao,
                     Function<URI, CompletableFuture<String>> fetch) {
        this.plugin = plugin;
        this.sim = sim;
        this.dao = dao;
        this.fetch = fetch != null ? fetch : this::httpGet;
        this.userAgent = "HomeCraftManagement/" + version(plugin);
    }

    // ---- state (meta) -------------------------------------------------------------------------

    /** Read the fetch bookkeeping back from {@code market_sim_meta}. */
    void load(Map<String, String> meta) {
        lastFetchDay = MarketSimDao.Meta.longOf(meta, MarketSimDao.Meta.REAL_LAST_FETCH_DAY, Schedule.NO_DAY);
        lastAttemptAt = MarketSimDao.Meta.longOf(meta, MarketSimDao.Meta.REAL_LAST_ATTEMPT_AT, 0L);
        attempts = (int) Math.max(0L, Math.min(MAX_ATTEMPTS,
                MarketSimDao.Meta.longOf(meta, MarketSimDao.Meta.REAL_ATTEMPTS_TODAY, 0L)));
        attemptDay = lastAttemptAt > 0 ? dayOf(lastAttemptAt) : Schedule.NO_DAY;
        String err = meta.get(MarketSimDao.Meta.REAL_LAST_ERROR);
        lastError = err == null ? "" : err;
    }

    /** The fetch bookkeeping as {@code market_sim_meta} rows. */
    Map<String, String> meta() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(MarketSimDao.Meta.REAL_LAST_FETCH_DAY, lastFetchDay == Schedule.NO_DAY ? "" : Long.toString(lastFetchDay));
        out.put(MarketSimDao.Meta.REAL_ATTEMPTS_TODAY, Integer.toString(attempts));
        out.put(MarketSimDao.Meta.REAL_LAST_ATTEMPT_AT, Long.toString(lastAttemptAt));
        out.put(MarketSimDao.Meta.REAL_LAST_ERROR, lastError);
        return out;
    }

    /** For {@code sim status}: {@code stooq, last fetch Sep 26} (plus the last error, if any). */
    String summary() {
        String provider = sim.settings().real().provider();
        String when = lastFetchDay == Schedule.NO_DAY ? "never fetched"
                : "last fetch " + MarketLabels.untilDate(LocalDate.ofEpochDay(lastFetchDay));
        return provider + ", " + when + (lastError.isBlank() ? "" : ", last error: " + lastError);
    }

    // ---- scheduling ---------------------------------------------------------------------------

    /**
     * The tick's due check: on a weekday after {@code fetch_time}, when today has not been
     * fetched, fewer than 3 tries have been made today and the last one was over an hour ago.
     */
    void maybeFetch(long now) {
        SimSettings.Real r = sim.settings().real();
        if (!r.enabled() || busy || !sim.active() || !plugin.isEnabled()) {
            return;
        }
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(sim.zone());
        DayOfWeek dow = local.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return;
        }
        long today = local.toLocalDate().toEpochDay();
        if (today == lastFetchDay || local.toLocalTime().isBefore(r.fetchTime())) {
            return;
        }
        if (attemptsOn(today) >= MAX_ATTEMPTS || (lastAttemptAt > 0 && now - lastAttemptAt < RETRY_MS)) {
            return;
        }
        fetchAll(true, null);
    }

    // ---- commands ---------------------------------------------------------------------------

    /**
     * {@code /hcm market sim real fetch} (and the daily fetch, {@code echo == null}): fetch every
     * configured symbol now. With {@code apply} the closes are cached and each new trade day's move
     * is queued for the next tick (once per symbol and trade day, however often this runs);
     * without it nothing is written and {@code echo} only hears what would happen.
     */
    public void fetchAll(boolean apply, CommandSender echo) {
        SimSettings.Real r = sim.settings().real();
        if (!r.enabled()) {
            tell(echo, "&eReal prices are off (market.sim.real_world.enabled: false). "
                    + "Try a symbol with /hcm market sim real test <symbol>.");
            return;
        }
        if (apply && !sim.active()) {
            tell(echo, "&eThe live market is off or paused, so nothing would apply.");
            return;
        }
        if (busy) {
            tell(echo, "&eA real-price fetch is already running.");
            return;
        }
        String tpl = template(r);
        if (tpl == null) {
            tell(echo, "&cReal prices: no usable https URL for provider '" + r.provider() + "'.");
            return;
        }
        long now = System.currentTimeMillis();
        LocalDate today = Instant.ofEpochMilli(now).atZone(sim.zone()).toLocalDate();
        List<Job> jobs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (RealSymbol row : r.symbols()) {
            String symbol = row.symbol(r.provider());
            if (symbol.isBlank()) {
                continue;
            }
            if (!RealQuotes.validSymbol(symbol)) {
                skipped.add(symbol + " (not a valid symbol)");
                continue;
            }
            if (plugin.market().item(row.item()) == null) {
                skipped.add(symbol + " (no market item '" + row.item() + "')");
                continue;
            }
            try {
                jobs.add(new Job(symbol, row.item(), row.name().isBlank() ? row.item() : row.name(),
                        URI.create(RealQuotes.url(tpl, symbol, today)), r.provider()));
            } catch (IllegalArgumentException e) {
                skipped.add(symbol + " (bad URL)");
            }
        }
        for (String s : skipped) {
            tell(echo, "&7Skipping " + s + ".");
        }
        if (jobs.isEmpty()) {
            tell(echo, "&eReal prices: no symbols to fetch for provider '" + r.provider() + "'.");
            return;
        }
        if (apply) {
            long day = today.toEpochDay();
            if (attemptDay != day) {
                attemptDay = day;
                attempts = 0;
            }
            attempts++;
            lastAttemptAt = now;
            saveMeta();
        }
        busy = true;
        int gen = generation.get();
        tell(echo, "&7Fetching " + jobs.size() + " real price" + (jobs.size() == 1 ? "" : "s") + " from "
                + r.provider() + "…");
        run(jobs, gen).whenComplete((results, err) -> onMain(gen, () -> finish(results, apply, echo, today)));
    }

    /**
     * {@code /hcm market sim real test <symbol>}: fetch and parse one symbol now and print its
     * newest closes and what would apply. Writes nothing, and works while real prices are off.
     */
    public void test(String symbol, CommandSender echo) {
        SimSettings.Real r = sim.settings().real();
        String sym = symbol == null ? "" : symbol.trim();
        if (!RealQuotes.validSymbol(sym)) {
            tell(echo, "&cNot a symbol: '" + sym + "' (letters, digits and . = ^ _ -, at most 16).");
            return;
        }
        String tpl = template(r);
        if (tpl == null) {
            tell(echo, "&cReal prices: no usable https URL for provider '" + r.provider() + "'.");
            return;
        }
        RealSymbol row = null;
        for (RealSymbol s : r.symbols()) {
            if (sym.equalsIgnoreCase(s.stooq()) || sym.equalsIgnoreCase(s.yahoo())) {
                row = s;
                break;
            }
        }
        LocalDate today = LocalDate.now(sim.zone());
        Job job;
        try {
            job = new Job(sym, row == null ? null : row.item(), row == null || row.name().isBlank() ? sym : row.name(),
                    URI.create(RealQuotes.url(tpl, sym, today)), r.provider());
        } catch (IllegalArgumentException e) {
            tell(echo, "&cReal prices: the URL for '" + sym + "' is not valid.");
            return;
        }
        tell(echo, "&7Fetching " + sym + " from " + r.provider() + "… &8(test: nothing is saved)");
        int gen = generation.get();
        run(List.of(job), gen).whenComplete((results, err) -> onMain(gen, () -> report(results, echo, today)));
    }

    /** Cancel whatever is in flight; late results are dropped. */
    public void cancel() {
        generation.incrementAndGet();
        for (CompletableFuture<?> f : inFlight) {
            f.cancel(true);
        }
        inFlight.clear();
        busy = false;
    }

    /** {@code /hcm market sim real status}. */
    public List<String> statusLines() {
        SimSettings.Real r = sim.settings().real();
        long now = System.currentTimeMillis();
        long today = dayOf(now);
        List<String> out = new ArrayList<>();
        out.add("&6Real prices: " + (r.enabled() ? "&aon" : "&coff") + " &7· provider " + r.provider() + " · "
                + (r.url().isBlank() ? "default URL" : "custom URL") + " · fetch " + r.fetchTime() + " Mon-Fri"
                + (busy ? " · fetching now" : ""));
        out.add("&7Last fetch: " + (lastFetchDay == Schedule.NO_DAY ? "never"
                : MarketLabels.untilDate(LocalDate.ofEpochDay(lastFetchDay))) + " · tries today "
                + attemptsOn(today) + "/" + MAX_ATTEMPTS + " · last try "
                + (lastAttemptAt > 0 ? Headlines.ago(Math.max(0L, now - lastAttemptAt)) : "never"));
        out.add("&7Last error: " + (lastError.isBlank() ? "none" : lastError));
        List<String> rows = new ArrayList<>();
        for (RealSymbol s : r.symbols()) {
            String symbol = s.symbol(r.provider());
            if (!symbol.isBlank()) {
                rows.add(symbol + " → " + s.item() + " (" + s.name() + ")");
            }
        }
        out.add("&7Symbols: " + (rows.isEmpty() ? "none" : String.join(", ", rows)));
        out.add("&7Waiting for the next tick: " + sim.queuedReal() + " · gain " + fmt(r.gain()) + " · at most "
                + fmt(r.maxPercent()) + "% · ignored above " + fmt(r.ignoreAbovePercent()) + "% · news from "
                + fmt(r.announceAbovePercent()) + "%");
        return out;
    }

    // ---- the fetch ----------------------------------------------------------------------------

    /** Fetch the jobs one after another, 2 s apart; every job gets a result (closes or an error). */
    private CompletableFuture<List<Fetched>> run(List<Job> jobs, int gen) {
        CompletableFuture<List<Fetched>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            long wait = i == 0 ? 0L : SPACING_MS;
            chain = chain.thenCompose(done -> {
                if (gen != generation.get()) {
                    return CompletableFuture.completedFuture(done);
                }
                CompletableFuture<String> body = CompletableFuture
                        .supplyAsync(job::uri, CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS))
                        .thenCompose(this::get);
                return body.handle((text, err) -> {
                    done.add(parse(job, text, err));
                    return done;
                });
            });
        }
        CompletableFuture<List<Fetched>> tracked = chain;
        inFlight.add(tracked);
        tracked.whenComplete((x, y) -> inFlight.remove(tracked));
        return tracked;
    }

    private CompletableFuture<String> get(URI uri) {
        CompletableFuture<String> f;
        try {
            f = fetch.apply(uri);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (f == null) {
            return CompletableFuture.failedFuture(new IOException("no reply"));
        }
        inFlight.add(f);
        f.whenComplete((x, y) -> inFlight.remove(f));
        // The request timeout covers the headers; this also bounds a reply that trickles in.
        long limit = Math.max(1, sim.settings().real().timeoutSeconds()) + (long) CONNECT_SECONDS;
        return f.orTimeout(limit, TimeUnit.SECONDS);
    }

    /** The real HTTP GET: https only (enforced by the URL check), capped at 256 KB. */
    private CompletableFuture<String> httpGet(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return CompletableFuture.failedFuture(new IOException("not https"));
        }
        int timeout = Math.max(1, sim.settings().real().timeoutSeconds());
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("User-Agent", userAgent)
                .header("Accept", "text/csv, application/json;q=0.9, */*;q=0.5")
                .GET()
                .build();
        return client().sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApply(RealWorldFetcher::read);
    }

    /** The body as text, refused past 256 KB or on a non-2xx status. */
    static String read(HttpResponse<InputStream> resp) {
        try (InputStream in = resp.body()) {
            if (resp.statusCode() / 100 != 2) {
                throw new CompletionException(new IOException("HTTP " + resp.statusCode()));
            }
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new CompletionException(new IOException("reply over 256 KB"));
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                c = http;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
                            .followRedirects(HttpClient.Redirect.NORMAL) // never https -> http
                            .build();
                    http = c;
                }
            }
        }
        return c;
    }

    /** Parse one reply off the main thread (pure). */
    private static Fetched parse(Job job, String body, Throwable err) {
        if (err != null) {
            return new Fetched(job, List.of(), describe(err));
        }
        List<RealQuotes.DailyClose> closes = "yahoo".equals(job.provider())
                ? RealQuotes.parseYahoo(body) : RealQuotes.parseStooq(body);
        if (closes.isEmpty()) {
            return new Fetched(job, List.of(), "no prices in the reply");
        }
        return new Fetched(job, closes, null);
    }

    static String describe(Throwable err) {
        Throwable t = err;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
            return "timed out";
        }
        if (t instanceof CancellationException) {
            return "cancelled";
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    // ---- back on the main thread ------------------------------------------------------------

    private void onMain(int gen, Runnable work) {
        if (!plugin.isEnabled() || gen != generation.get()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled() && gen == generation.get()) {
                    work.run();
                }
            });
        } catch (RuntimeException ignored) {
            // the plugin was disabled between the check and the call: nothing to hand back to
        }
    }

    /** Cache the closes, queue the new moves, remember how it went (main thread). */
    private void finish(List<Fetched> results, boolean apply, CommandSender echo, LocalDate today) {
        busy = false;
        List<Fetched> list = results == null ? List.of() : results;
        SimSettings.Real r = sim.settings().real();
        long now = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<RealImpulse> impulses = new ArrayList<>();
        for (Fetched f : list) {
            if (f.error() != null) {
                errors.add(f.job().symbol() + ": " + f.error());
                warnOnce(f.job().symbol(), f.error(), today.toEpochDay());
                lines.add("&c" + f.job().symbol() + ": " + f.error());
            }
        }
        if (apply) {
            Database db = plugin.database();
            try {
                if (db == null) {
                    throw new SQLException("no database");
                }
                db.transaction(c -> {
                    for (Fetched f : list) {
                        if (f.error() != null) {
                            continue;
                        }
                        String symbol = f.job().symbol();
                        dao.upsertQuotes(c, symbol, f.closes(), now);
                        List<RealQuotes.DailyClose> closes = new ArrayList<>();
                        for (MarketSimDao.StoredQuote q : dao.quotes(symbol,
                                today.minusDays(RealQuotes.LOOKBACK_DAYS * 2L).toEpochDay())) {
                            closes.add(q.dailyClose());
                        }
                        Optional<RealQuotes.Move> move = RealQuotes.latestMove(closes, today, MAX_AGE_DAYS);
                        if (move.isEmpty()) {
                            lines.add("&7" + symbol + ": no fresh day-over-day move.");
                            continue;
                        }
                        double q = move.get().change();
                        double strength = RealQuotes.impulse(q, r.gain(), r.maxFrac(), r.ignoreAboveFrac());
                        if (Double.isNaN(strength)) {
                            lines.add("&7" + symbol + ": " + pct(q) + " looks like bad data (over "
                                    + fmt(r.ignoreAbovePercent()) + "%) - skipped.");
                            continue;
                        }
                        long day = move.get().day().toEpochDay();
                        if (dao.markApplied(c, symbol, day)) {
                            impulses.add(new RealImpulse(f.job().itemId(), symbol, day, strength, f.job().name(),
                                    q * 100.0));
                            lines.add("&a" + symbol + " → " + f.job().itemId() + ": " + pct(q) + " on "
                                    + move.get().day() + " → " + pct(strength) + " at the next tick.");
                        } else {
                            lines.add("&7" + symbol + ": " + move.get().day() + " was already applied.");
                        }
                    }
                    return null;
                });
            } catch (SQLException | RuntimeException ex) {
                impulses.clear();
                errors.add("database: " + ex.getMessage());
                lines.add("&cReal prices: could not save the closes: " + ex.getMessage());
                plugin.getLogger().warning("Real prices: could not save the closes: " + ex.getMessage());
            }
            sim.queueReal(impulses);
            if (errors.isEmpty() && !list.isEmpty()) {
                lastFetchDay = today.toEpochDay();
                lastError = "";
            } else {
                lastError = String.join("; ", errors);
            }
            saveMeta();
        } else {
            for (Fetched f : list) {
                if (f.error() == null) {
                    lines.addAll(wouldApply(f, r, today));
                }
            }
        }
        for (String line : lines) {
            tell(echo, line);
        }
    }

    /** {@code real test}'s report (main thread). */
    private void report(List<Fetched> results, CommandSender echo, LocalDate today) {
        SimSettings.Real r = sim.settings().real();
        for (Fetched f : results == null ? List.<Fetched>of() : results) {
            if (f.error() != null) {
                tell(echo, "&c" + f.job().symbol() + ": " + f.error());
                continue;
            }
            List<RealQuotes.DailyClose> closes = f.closes();
            tell(echo, "&6" + f.job().symbol() + " &7(" + closes.size() + " close" + (closes.size() == 1 ? "" : "s")
                    + "), newest:");
            for (int i = Math.max(0, closes.size() - ECHO_CLOSES); i < closes.size(); i++) {
                RealQuotes.DailyClose d = closes.get(i);
                tell(echo, "&7  " + d.day() + "  &f" + String.format(Locale.ROOT, "%.4f", d.close()));
            }
            for (String line : wouldApply(f, r, today)) {
                tell(echo, line);
            }
            tell(echo, "&8Nothing was saved.");
        }
    }

    /** What {@code f} would do if applied now (nothing is written). */
    private List<String> wouldApply(Fetched f, SimSettings.Real r, LocalDate today) {
        List<String> out = new ArrayList<>();
        String symbol = f.job().symbol();
        List<RealQuotes.DailyClose> closes = new ArrayList<>(f.closes());
        if (f.job().itemId() != null) {
            try {
                Map<LocalDate, RealQuotes.DailyClose> byDay = new TreeMap<>();
                for (MarketSimDao.StoredQuote q : dao.quotes(symbol,
                        today.minusDays(RealQuotes.LOOKBACK_DAYS * 2L).toEpochDay())) {
                    byDay.put(q.dailyClose().day(), q.dailyClose());
                }
                for (RealQuotes.DailyClose d : f.closes()) {
                    byDay.put(d.day(), d);
                }
                closes = new ArrayList<>(byDay.values());
            } catch (SQLException e) {
                // the fetched closes alone will do
            }
        }
        Optional<RealQuotes.Move> move = RealQuotes.latestMove(closes, today, MAX_AGE_DAYS);
        if (move.isEmpty()) {
            out.add("&7" + symbol + ": no fresh day-over-day move (the newest close must be at most "
                    + MAX_AGE_DAYS + " days old).");
            return out;
        }
        double q = move.get().change();
        double strength = RealQuotes.impulse(q, r.gain(), r.maxFrac(), r.ignoreAboveFrac());
        out.add("&7Latest move: " + move.get().prevDay() + " → " + move.get().day() + ": &f" + pct(q));
        if (Double.isNaN(strength)) {
            out.add("&7Would skip it: over " + fmt(r.ignoreAbovePercent()) + "% is treated as bad data.");
        } else if (f.job().itemId() == null) {
            out.add("&7Would move " + pct(strength) + " - but no real_world.symbols row maps " + symbol + " to an item.");
        } else {
            out.add("&7Would move " + f.job().itemId() + " by " + pct(strength) + " (gain " + fmt(r.gain())
                    + ", at most " + fmt(r.maxPercent()) + "%)"
                    + (Math.abs(q) * 100.0 >= r.announceAbovePercent() ? ", with a news line." : "."));
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void warnOnce(String symbol, String error, long day) {
        Long last = warned.get(symbol);
        String msg = "Real prices: " + symbol + " failed (" + error + ") - the market carries on without it";
        if (last == null || last != day) {
            warned.put(symbol, day);
            plugin.getLogger().warning(msg);
        } else {
            plugin.getLogger().fine(msg);
        }
    }

    private void saveMeta() {
        try {
            dao.saveMeta(meta());
        } catch (SQLException e) {
            plugin.getLogger().warning("Real prices: could not save the fetch state: " + e.getMessage());
        }
    }

    private int attemptsOn(long day) {
        return attemptDay == day ? attempts : 0;
    }

    private long dayOf(long ms) {
        return Instant.ofEpochMilli(ms).atZone(sim.zone()).toLocalDate().toEpochDay();
    }

    /** The URL template in use: the configured one, or the provider's default; {@code null} if not https. */
    private static String template(SimSettings.Real r) {
        String tpl = r.url() == null || r.url().isBlank() ? RealQuotes.defaultUrl(r.provider()) : r.url();
        return tpl != null && RealQuotes.validUrl(tpl) ? tpl : null;
    }

    private void tell(CommandSender to, String line) {
        if (to == null || line == null || line.isEmpty()) {
            return;
        }
        if (to instanceof Player p && !p.isOnline()) {
            return;
        }
        try {
            to.sendMessage(Text.of(line));
        } catch (RuntimeException ignored) {
            // the sender went away
        }
    }

    private static String pct(double frac) {
        return String.format(Locale.ROOT, "%+.2f%%", frac * 100.0);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String version(HomeCraftManagement plugin) {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (RuntimeException e) {
            return "dev";
        }
    }
}
