package com.dierks.homecraft.market.sim;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The live market's tick (spec §4): one pure function from "state at the last boundary" to
 * "state at this boundary". It never touches stock or the balanced price — it only reads them
 * ({@link Quote}) — and it holds no state of its own between calls: everything it remembers is
 * in the {@link ItemSimState} rows, the {@link Schedule}, the {@link BroadcastState} and the event
 * list, which the caller persists.
 *
 * <p><b>Tick order</b> at boundary {@code b} (counter {@code c = b / 60000}):
 * <ol>
 *   <li>Expire and stop: an event on an item removed from the catalog or set {@code sim: false}
 *       ends at once, silently; a DEAL whose stock reached 0 stops ({@code sold_out}, 1 h fade);
 *       a WANTED row ends once the item has stock. Any stop or end of a HOT/DEAL moves the
 *       item's {@code featured_until} to its actual end.</li>
 *   <li>Drift: an exact OU step per sim-enabled item, {@code Z = gaussian("drift|" + id, c)}.</li>
 *   <li>Queued REAL impulses become REAL events (live ticks only).</li>
 *   <li>Season rows: the first tick a season's window has weight, one SEASON row per
 *       {@code id:year}.</li>
 *   <li>Planner ({@link EventPlanner}): at most one start (live ticks only).</li>
 *   <li>{@code M} and status for every item ({@link MoodEngine}), and the "jumped" set: items
 *       whose {@code M} moved more than 1% since the previous evaluation.</li>
 *   <li>{@link AnnounceGate}: at most one broadcast (live ticks only).</li>
 * </ol>
 *
 * <p><b>Replays</b> ({@code live = false}; boundaries at least one tick in the past when the
 * pump catches up) do expiry, drift and season rows only: no event starts, no REAL impulses,
 * no broadcasts. So downtime never generates events and a restart replays deterministically.
 * {@link #run} replays at most {@code max_catchup_hours} of boundaries and runs the newest live;
 * it is exactly the same as calling {@link #tick} once per boundary with the state carried over.
 *
 * <p><b>Mutation:</b> {@link TickInput#state()} (a mutable map), {@link TickInput#schedule()}
 * and {@link TickInput#broadcast()} are updated in place. Events are immutable: the result lists
 * the new working set ({@link TickResult#events()}), the rows to insert
 * ({@link TickResult#started()}, id 0) and the rows to update ({@link TickResult#changed()}).
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code MarketSimulatorSoakTest},
 * {@code MarketSimulatorDeterminismTest}).
 */
public final class MarketSimulator {

    /** {@code sim stop}: a 1 h fade. */
    public static final String STOP_STOPPED = "stopped";
    /** A DEAL whose stock reached 0: a 1 h fade and a log-only line. */
    public static final String STOP_SOLD_OUT = "sold_out";
    /** The item left the catalog or was set {@code sim: false}: ended at once, silently. */
    public static final String STOP_REMOVED = "removed";
    /** A WANTED item got stock. */
    public static final String STOP_STOCKED = "stocked";
    /** {@code sim reset}: ended at once. */
    public static final String STOP_RESET = "reset";
    /** {@code sim pause}: ended at once. */
    public static final String STOP_PAUSED = "paused";
    /** {@code enabled: false}: ended at once. */
    public static final String STOP_DISABLED = "disabled";

    /** Events stay in the working set this long after {@code ends_at} (ending lines, caches). */
    public static final long RETAIN_MS = SimMath.HOUR_MS;
    /** A move of {@code M} bigger than this between evaluations is a "jump" (stale-quote guard). */
    public static final double JUMP = 0.01;

    /**
     * What the sim reads of one item's market state. It never writes either value.
     *
     * @param base  the balanced price, {@code MarketState.currentPrice()}
     * @param stock the stock
     */
    public record Quote(double base, long stock) {
    }

    /**
     * Everything one tick needs.
     *
     * @param boundary  {@code b}, a multiple of the tick length
     * @param live      the newest boundary (plan and broadcast) rather than a replay
     * @param settings  {@code market.sim}
     * @param rng       the seeded counter RNG
     * @param items     the catalog's params by id (sorted; {@code enabled} is {@code sim:})
     * @param market    balanced price and stock by id (read only)
     * @param state     per-item rows by id; <b>mutable</b>, updated in place (missing rows are added)
     * @param active    the working set of events (live ones plus any that ended within the last hour)
     * @param schedule  updated in place
     * @param online    who is online (ignored on replays)
     * @param zone      {@code clock.time_zone}
     * @param spread    {@code market.spread}
     * @param queued    real-world impulses to apply at the next live tick; {@code null} = none
     * @param broadcast updated in place
     * @param popular   ids anyone traded in the last 14 local days; {@code null} = none
     * @param previous  each item's {@code M} at the previous evaluation, for the jump check;
     *                  {@code null} = unknown (nothing counts as a jump)
     */
    public record TickInput(long boundary, boolean live, SimSettings settings, SimRandom rng,
                            SortedMap<String, ItemParams> items, Map<String, Quote> market,
                            Map<String, ItemSimState> state, List<MarketEvent> active, Schedule schedule,
                            OnlineInfo online, ZoneId zone, double spread, List<RealImpulse> queued,
                            BroadcastState broadcast, Set<String> popular, Map<String, Double> previous) {

        public TickInput {
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(rng, "rng");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(schedule, "schedule");
            Objects.requireNonNull(broadcast, "broadcast");
            items = items == null ? new TreeMap<>() : items;
            market = market == null ? Map.of() : market;
            active = active == null ? List.of() : active;
            online = online == null ? OnlineInfo.NOBODY : online;
            zone = zone == null ? ZoneOffset.UTC : zone;
            queued = queued == null ? List.of() : queued;
            popular = popular == null ? Set.of() : popular;
        }

        /** The same input at another boundary. */
        public TickInput at(long b, boolean isLive) {
            return new TickInput(b, isLive, settings, rng, items, market, state, active, schedule, online, zone,
                    spread, queued, broadcast, popular, previous);
        }

        /** The same input with another working set of events. */
        public TickInput withEvents(List<MarketEvent> events) {
            return new TickInput(boundary, live, settings, rng, items, market, state, events, schedule, online, zone,
                    spread, queued, broadcast, popular, previous);
        }

        /** The same input with other previous multipliers. */
        public TickInput withPrevious(Map<String, Double> prev) {
            return new TickInput(boundary, live, settings, rng, items, market, state, active, schedule, online, zone,
                    spread, queued, broadcast, popular, prev);
        }

        /** The same input with someone else online. */
        public TickInput withOnline(OnlineInfo who) {
            return new TickInput(boundary, live, settings, rng, items, market, state, active, schedule, who, zone,
                    spread, queued, broadcast, popular, previous);
        }

        /** The same input with other queued impulses. */
        public TickInput withQueued(List<RealImpulse> impulses) {
            return new TickInput(boundary, live, settings, rng, items, market, state, active, schedule, online, zone,
                    spread, impulses, broadcast, popular, previous);
        }
    }

    /**
     * What a tick (or a catch-up run) did.
     *
     * @param boundary     the last boundary run
     * @param live         whether that boundary ran live
     * @param ticks        how many boundaries ran (0 when there was nothing to do)
     * @param skipped      boundaries older than {@code max_catchup_hours} that were skipped
     * @param multipliers  {@code M} by item at {@code boundary} (1.0 for {@code sim: false})
     * @param status       what players see, by item
     * @param breakdowns   {@code M} part by part, by item ({@code sim status})
     * @param started      new rows to insert (id 0), in start order; their final version
     * @param changed      existing rows that changed (stops, announcements, last calls, endings)
     * @param events       the new working set (started ones included; closed for over an hour dropped)
     * @param seasonStarts seasons whose SEASON row was created
     * @param seasons      seasons in effect at {@code boundary}
     * @param broadcast    the one announcement to deliver now, its event already marked sent
     * @param jumped       items whose {@code M} moved more than 1% between evaluations
     * @param newsHeld     a due news flash is waiting for a player
     */
    public record TickResult(long boundary, boolean live, int ticks, int skipped, Map<String, Double> multipliers,
                             Map<String, ItemStatus> status, Map<String, MoodEngine.Breakdown> breakdowns,
                             List<MarketEvent> started, List<MarketEvent> changed, List<MarketEvent> events,
                             List<SeasonCalendar.Active> seasonStarts, List<SeasonCalendar.Active> seasons,
                             Optional<AnnounceGate.Pending> broadcast, Set<String> jumped, boolean newsHeld) {
    }

    /**
     * {@code M}, status and breakdown for every item at one moment.
     *
     * @param seasons the seasons in effect
     */
    public record Evaluation(Map<String, Double> multipliers, Map<String, ItemStatus> status,
                             Map<String, MoodEngine.Breakdown> breakdowns, List<SeasonCalendar.Active> seasons) {
    }

    /**
     * The boundaries a pump run covers, oldest first: every {@code b = k x T} with
     * {@code lastTickAt < b <= now}, minus the oldest ones when more than
     * {@code max_catchup_hours} of replays are due. The last one is the live one.
     *
     * @param skipped how many old boundaries were dropped
     */
    public record Catchup(List<Long> boundaries, int skipped) {

        public boolean isEmpty() {
            return boundaries.isEmpty();
        }
    }

    private MarketSimulator() {
    }

    // ---- entry points --------------------------------------------------------------------

    /** Run one boundary, {@code in.boundary()}, live or as a replay per {@code in.live()}. */
    public static TickResult tick(TickInput in) {
        Run run = new Run(in);
        run.step(in.boundary(), in.live(), in.queued());
        return run.result(0);
    }

    /**
     * Run every boundary from {@code lastTickAt} (exclusive) to {@code now}: the ones at least a
     * tick old as replays (at most {@code max_catchup_hours} of them), the newest live when
     * {@code in.live()}. Queued REAL impulses apply at the live boundary only. With nothing due
     * the result has {@code ticks = 0} and empty maps.
     */
    public static TickResult run(TickInput in, long lastTickAt, long now) {
        Catchup plan = boundaries(lastTickAt, now, in.settings().tickMs(), in.settings().maxCatchupHours());
        Run run = new Run(in);
        List<Long> bs = plan.boundaries();
        for (int i = 0; i < bs.size(); i++) {
            boolean last = i == bs.size() - 1;
            boolean live = last && in.live();
            run.step(bs.get(i), live, live ? in.queued() : List.of());
        }
        return run.result(plan.skipped());
    }

    /** The boundaries a pump run covers (see {@link Catchup}). */
    public static Catchup boundaries(long lastTickAt, long now, long tickMs, int maxCatchupHours) {
        long t = Math.max(SimMath.MINUTE_MS, tickMs);
        long first = Math.floorDiv(lastTickAt, t) * t + t;
        long last = Math.floorDiv(now, t) * t;
        if (last < first) {
            return new Catchup(List.of(), 0);
        }
        long replays = (last - first) / t;
        long maxReplays = Math.max(0L, maxCatchupHours) * SimMath.HOUR_MS / t;
        long skipped = Math.max(0L, replays - maxReplays);
        long start = first + skipped * t;
        List<Long> out = new ArrayList<>((int) Math.min(Integer.MAX_VALUE - 8, (last - start) / t + 1));
        for (long b = start; b <= last; b += t) {
            out.add(b);
        }
        return new Catchup(List.copyOf(out), (int) Math.min(Integer.MAX_VALUE, skipped));
    }

    // ---- helpers for the service (admin actions, status) ---------------------------------

    /**
     * {@code M}, status and breakdown for every item at {@code t} from the given rows, without
     * advancing anything: for the snapshot after an admin action, and for status screens.
     */
    public static Evaluation evaluate(SimSettings s, SortedMap<String, ItemParams> items, Map<String, Quote> market,
                                      Map<String, ItemSimState> state, List<MarketEvent> events, ZoneId zone,
                                      double spread, long t) {
        List<SeasonCalendar.Active> seasons = seasonsAt(s, t, zone);
        return evaluate(s, items, market, state, events, spread, t, seasons);
    }

    /** The seasons in effect at {@code t} (none when seasons are off). */
    public static List<SeasonCalendar.Active> seasonsAt(SimSettings s, long t, ZoneId zone) {
        if (!s.seasons().enabled()) {
            return List.of();
        }
        return SeasonCalendar.active(s.seasons().list(), t, zone == null ? ZoneOffset.UTC : zone, s.seasons().rampDays());
    }

    /**
     * End or stop the live events of one item ({@code itemId}) or of every item ({@code null}):
     * with a 1 h fade ({@code fade}, {@code sim stop}) or at once ({@code sim reset}, pause,
     * disable). SEASON rows are never touched (their effect is the calendar's, and their tag must
     * stay unique). A HOT/DEAL's {@code featured_until} moves to its new end in {@code state}.
     *
     * @return the list with the stopped events replaced, same order
     */
    public static List<MarketEvent> end(List<MarketEvent> events, String itemId, long ts, String reason,
                                        boolean fade, Map<String, ItemSimState> state) {
        List<MarketEvent> out = new ArrayList<>(events.size());
        for (MarketEvent e : events) {
            if (e.kind() != EventKind.SEASON && e.active(ts) && (itemId == null || itemId.equals(e.itemId()))) {
                MarketEvent stopped = fade ? e.withStop(ts, reason) : e.withEnd(ts, reason);
                featured(stopped, state);
                out.add(stopped);
            } else {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * On the first enable, and on every resume or re-enable: the schedule as {@link
     * Schedule#resetForEnable} and drift 0 for every item.
     */
    public static void enable(long now, SimRandom rng, Schedule schedule, Map<String, ItemSimState> state) {
        schedule.resetForEnable(now, rng);
        for (Map.Entry<String, ItemSimState> e : state.entrySet()) {
            e.setValue(e.getValue().withDrift(0.0, now));
        }
    }

    /** {@code sim reset}: drift back to 0 for one item ({@code itemId}) or every item ({@code null}). */
    public static void zeroDrift(Map<String, ItemSimState> state, String itemId, long now) {
        for (Map.Entry<String, ItemSimState> e : state.entrySet()) {
            if (itemId == null || itemId.equals(e.getKey())) {
                e.setValue(e.getValue().withDrift(0.0, now));
            }
        }
    }

    /**
     * Book a started event against its item: a HOT/DEAL sets {@code featured_until}, an UP/DOWN
     * {@code last_news_at}, a WANTED {@code last_wanted_at}.
     */
    public static void recordStart(MarketEvent e, Map<String, ItemSimState> state) {
        if (e.itemId() == null) {
            return;
        }
        ItemSimState st = state.getOrDefault(e.itemId(), ItemSimState.fresh(e.itemId()));
        switch (e.kind()) {
            case HOT, DEAL -> state.put(e.itemId(), st.withFeaturedUntil(e.endsAt()));
            case UP, DOWN -> state.put(e.itemId(), st.withLastNewsAt(e.startedAt()));
            case WANTED -> state.put(e.itemId(), st.withLastWantedAt(e.startedAt()));
            default -> {
                // SEASON, REAL: nothing to book
            }
        }
    }

    /** True when a HOT/DEAL/UP/DOWN is active on {@code itemId} at {@code t}. */
    public static boolean busy(List<MarketEvent> events, String itemId, long t) {
        for (MarketEvent e : events) {
            if (e.kind().mood() && e.active(t) && Objects.equals(itemId, e.itemId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when an event of one of {@code kinds} is active on {@code itemId} at {@code t}, in any
     * phase (a ramping DEAL included). For the §3.4 event limits: the buy cap while a DEAL or
     * DOWN runs, the sell cap while a HOT or UP runs.
     */
    public static boolean activeOf(List<MarketEvent> events, String itemId, long t, EventKind... kinds) {
        for (MarketEvent e : events) {
            if (!e.active(t) || !Objects.equals(itemId, e.itemId())) {
                continue;
            }
            for (EventKind k : kinds) {
                if (e.kind() == k) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The planner's view of one item (also for admin-forced events). */
    public static EventPlanner.Candidate candidate(ItemParams p, Quote q, ItemSimState st, double m0, boolean busy,
                                                   boolean popular, SimSettings s) {
        ItemSimState row = st == null ? ItemSimState.fresh(p.id()) : st;
        double w = p.weight() * (popular ? s.popularWeight() : 1.0);
        double base = q == null ? p.ceiling() : q.base();
        long stock = q == null ? 0L : q.stock();
        return new EventPlanner.Candidate(p.id(), w, base, p.floor(), p.ceiling(), stock, p.fullStock(), m0, busy,
                row.featuredUntil(), row.lastNewsAt(), row.lastWantedAt());
    }

    /**
     * {@code e} with its headline: a template from the kind's list ({@code up}, {@code down},
     * {@code hot}, {@code deal}, {@code wanted}; {@code real_up}/{@code real_down} by the sign),
     * not one of the last 4 used for that list ({@code u("headline." + list, counter)}), with
     * {@code {item}}, {@code {Name}} and {@code {real}} filled in. The memory in {@code sched} is
     * updated (except for the one-line real lists). {@code line} is left to the announcer.
     */
    public static MarketEvent headline(MarketEvent e, ItemParams p, SimSettings s, Schedule sched, SimRandom rng,
                                       long counter, String realName) {
        String list = switch (e.kind()) {
            case UP -> Headlines.LIST_UP;
            case DOWN -> Headlines.LIST_DOWN;
            case HOT -> Headlines.LIST_HOT;
            case DEAL -> Headlines.LIST_DEAL;
            case WANTED -> Headlines.LIST_WANTED;
            case REAL -> e.strength() >= 0 ? Headlines.LIST_REAL_UP : Headlines.LIST_REAL_DOWN;
            default -> null;
        };
        if (list == null || p == null) {
            return e;
        }
        List<String> tpls = s.headlines().list(list);
        if (tpls.isEmpty()) {
            tpls = Headlines.shipped(list);
        }
        if (tpls.isEmpty()) {
            return e;
        }
        boolean remember = e.kind() != EventKind.REAL;
        List<Integer> recent = remember ? sched.recent(list) : List.of();
        int at = Headlines.pick(tpls.size(), recent, rng.uniform("headline." + list, counter));
        if (at < 0) {
            return e;
        }
        if (remember) {
            sched.setRecent(list, Headlines.remember(recent, at));
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("item", p.plural());
        vars.put("Name", p.name());
        vars.put("real", realName == null ? "" : realName);
        return e.withText(Headlines.render(tpls.get(at), vars), e.line());
    }

    // ---- internals -----------------------------------------------------------------------

    private static void featured(MarketEvent e, Map<String, ItemSimState> state) {
        if (!e.kind().story() || e.itemId() == null) {
            return;
        }
        ItemSimState st = state.getOrDefault(e.itemId(), ItemSimState.fresh(e.itemId()));
        state.put(e.itemId(), st.withFeaturedUntil(e.endsAt()));
    }

    private static Evaluation evaluate(SimSettings s, SortedMap<String, ItemParams> items, Map<String, Quote> market,
                                       Map<String, ItemSimState> state, List<MarketEvent> events, double spread,
                                       long t, List<SeasonCalendar.Active> seasons) {
        Map<String, List<MarketEvent>> byItem = new HashMap<>();
        for (MarketEvent e : events) {
            if (e.itemId() != null && e.active(t)) {
                byItem.computeIfAbsent(e.itemId(), k -> new ArrayList<>(2)).add(e);
            }
        }
        Map<String, Double> mult = new LinkedHashMap<>();
        Map<String, ItemStatus> status = new LinkedHashMap<>();
        Map<String, MoodEngine.Breakdown> parts = new LinkedHashMap<>();
        for (Map.Entry<String, ItemParams> en : items.entrySet()) {
            String id = en.getKey();
            ItemParams p = en.getValue();
            ItemSimState st = state.get(id);
            double drift = st == null ? 0.0 : st.drift();
            double season = 0.0;
            for (SeasonCalendar.Active a : seasons) {
                season += a.effect(id);
            }
            List<MarketEvent> mine = byItem.getOrDefault(id, List.of());
            MoodEngine.Breakdown bd = MoodEngine.breakdown(p, drift, mine, season, t, s, spread);
            Quote q = market.get(id);
            double base = q == null ? p.ceiling() : q.base();
            long stock = q == null ? 0L : q.stock();
            mult.put(id, bd.multiplier());
            status.put(id, MoodEngine.status(p, bd, mine, base, stock, t));
            parts.put(id, bd);
        }
        return new Evaluation(Collections.unmodifiableMap(mult), Collections.unmodifiableMap(status),
                Collections.unmodifiableMap(parts), seasons);
    }

    /** One event and where it came from ({@code original == null}: started in this run). */
    private static final class Tracked {
        MarketEvent now;
        final MarketEvent original;

        Tracked(MarketEvent now, MarketEvent original) {
            this.now = now;
            this.original = original;
        }
    }

    /** One call's worth of ticks, sharing the working set so a run equals tick-by-tick calls. */
    private static final class Run {
        private final TickInput in;
        private final SimSettings s;
        private final SimRandom rng;
        private final ZoneId zone;
        private final Map<String, ItemSimState> state;
        private final List<Tracked> events = new ArrayList<>();
        private final List<Tracked> retired = new ArrayList<>();
        private final List<SeasonCalendar.Active> seasonStarts = new ArrayList<>();
        private final Set<String> jumped = new TreeSet<>();
        private final Map<String, Long> driftKeys = new HashMap<>();
        private Map<String, Double> previous;
        private List<SeasonCalendar.Active> seasonsNow = List.of();
        private Evaluation last;
        private Optional<AnnounceGate.Pending> broadcast = Optional.empty();
        private boolean newsHeld;
        private long lastB;
        private boolean lastLive;
        private int ticks;

        Run(TickInput in) {
            this.in = in;
            this.s = in.settings();
            this.rng = in.rng();
            this.zone = in.zone();
            this.state = in.state();
            this.previous = in.previous();
            for (MarketEvent e : in.active()) {
                if (e != null) {
                    events.add(new Tracked(e, e));
                }
            }
            this.lastB = in.boundary();
            this.lastLive = in.live();
        }

        void step(long b, boolean live, List<RealImpulse> queued) {
            ticks++;
            lastB = b;
            lastLive = live;
            broadcast = Optional.empty();
            newsHeld = false;
            if (!s.enabled()) {
                seasonsNow = List.of();
                finishEvaluation(b);
                return;
            }
            long c = Math.floorDiv(b, SimMath.MINUTE_MS);
            ZonedDateTime local = Instant.ofEpochMilli(b).atZone(zone);
            long today = local.toLocalDate().toEpochDay();

            expireAndStop(b);
            drift(b, c);
            seasonsNow = seasonsAt(s, b, zone);
            if (live && s.real().enabled() && queued != null && !queued.isEmpty()) {
                applyReal(b, c, queued);
            }
            seasonRows(b);

            MarketEvent flash = null;
            long flashDelay = 0L;
            if (live) {
                EventPlanner.Planned planned = plan(b, today);
                newsHeld = planned.newsHeld();
                if (planned.started()) {
                    MarketEvent e = planned.event();
                    e = headline(e, in.items().get(e.itemId()), s, in.schedule(), rng, c, null);
                    events.add(new Tracked(e, null));
                    recordStart(e, state);
                    if (planned.live()) {
                        flash = e;
                        flashDelay = planned.joinDelayMs();
                    }
                }
            }

            finishEvaluation(b);

            if (live) {
                announce(b, today, flash, flashDelay);
            }
        }

        private void expireAndStop(long b) {
            for (Tracked t : events) {
                MarketEvent e = t.now;
                if (!e.active(b) || e.itemId() == null || e.kind() == EventKind.SEASON) {
                    continue;
                }
                ItemParams p = in.items().get(e.itemId());
                if (p == null || !p.enabled()) {
                    t.now = e.withEnd(b, STOP_REMOVED);
                    featured(t.now, state);
                    continue;
                }
                long stock = stock(e.itemId());
                if (e.kind() == EventKind.DEAL && !e.stopped() && stock <= 0) {
                    t.now = e.withStop(b, STOP_SOLD_OUT);
                    featured(t.now, state);
                } else if (e.kind() == EventKind.WANTED && stock > 0) {
                    t.now = e.withEnd(b, STOP_STOCKED);
                }
            }
            events.removeIf(t -> {
                if (b >= t.now.endsAt() + RETAIN_MS) {
                    retired.add(t);
                    return true;
                }
                return false;
            });
        }

        private void drift(long b, long c) {
            double dtH = s.tickMinutes() / 60.0;
            double half = s.drift().halfLifeHours();
            double bound = s.drift().maxFrac();
            for (Map.Entry<String, ItemParams> en : in.items().entrySet()) {
                String id = en.getKey();
                ItemParams p = en.getValue();
                ItemSimState st = state.get(id);
                if (st == null) {
                    st = ItemSimState.fresh(id);
                }
                double d = 0.0;
                if (p.enabled()) {
                    long key = driftKeys.computeIfAbsent(id, k -> SimRandom.key("drift|" + k));
                    d = SimMath.ouStep(st.drift(), dtH, half, p.sigma(), rng.gaussian(key, c), bound);
                }
                state.put(id, st.withDrift(d, b));
            }
        }

        private void applyReal(long b, long c, List<RealImpulse> queued) {
            SimSettings.Real r = s.real();
            double cap = r.maxFrac();
            List<RealImpulse> ok = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (RealImpulse ri : queued) {
                if (ri == null || ri.itemId() == null) {
                    continue;
                }
                ItemParams p = in.items().get(ri.itemId());
                double strength = Double.isFinite(ri.strength()) ? Math.max(-cap, Math.min(cap, ri.strength())) : 0.0;
                if (p == null || !p.enabled() || strength == 0.0) {
                    continue;
                }
                String tag = ri.tag();
                if (!seen.add(tag) || hasTag(EventKind.REAL, tag)) {
                    continue;
                }
                ok.add(ri);
            }
            RealImpulse lead = null;
            for (RealImpulse ri : ok) {
                double mag = Math.abs(ri.realChangePct());
                if (mag >= r.announceAbovePercent() && (lead == null || mag > Math.abs(lead.realChangePct()))) {
                    lead = ri;
                }
            }
            for (RealImpulse ri : ok) {
                String id = ri.itemId();
                ItemParams p = in.items().get(id);
                double strength = Math.max(-cap, Math.min(cap, ri.strength()));
                double m0 = moodOf(id, b).multiplier();
                MarketEvent e = MarketEvent.shock(EventKind.REAL, Source.REAL, id, strength, b, r.halfLifeMs(),
                        r.lastsMs()).toBuilder().tag(ri.tag()).announceDueAt(ri == lead ? b : null).build();
                Tracked t = new Tracked(e, null);
                events.add(t);
                double m1 = moodOf(id, b).multiplier();
                Quote q = in.market().get(id);
                double base = q == null ? p.ceiling() : q.base();
                long stock = q == null ? 0L : q.stock();
                double before = SimMath.displayPrice(base, stock, m0, p.floor(), p.ceiling());
                double after = SimMath.displayPrice(base, stock, m1, p.floor(), p.ceiling());
                e = e.withPrices(SimMath.pct(after, before), before, after);
                t.now = headline(e, p, s, in.schedule(), rng, c, ri.realName());
            }
        }

        private void seasonRows(long b) {
            for (SeasonCalendar.Active a : seasonsNow) {
                String tag = a.tag();
                if (hasTag(EventKind.SEASON, tag)) {
                    continue;
                }
                MarketEvent row = MarketEvent.info(EventKind.SEASON, Source.CALENDAR, null, tag, b, a.endsAt(zone))
                        .withPrices(headlinePercent(a.season()), 0.0, 0.0)
                        .withText(a.season().headline(), null);
                events.add(new Tracked(row, null));
                seasonStarts.add(a);
            }
        }

        private EventPlanner.Planned plan(long b, long today) {
            Evaluation before = evaluate(s, in.items(), in.market(), state, current(), in.spread(), b, seasonsNow);
            int activeHot = 0;
            int activeDeal = 0;
            Set<String> busy = new HashSet<>();
            for (Tracked t : events) {
                MarketEvent e = t.now;
                if (!e.active(b)) {
                    continue;
                }
                if (e.kind() == EventKind.HOT) {
                    activeHot++;
                } else if (e.kind() == EventKind.DEAL) {
                    activeDeal++;
                }
                if (e.kind().mood() && e.itemId() != null) {
                    busy.add(e.itemId());
                }
            }
            List<EventPlanner.Candidate> cands = new ArrayList<>();
            int simItems = 0;
            for (Map.Entry<String, ItemParams> en : in.items().entrySet()) {
                ItemParams p = en.getValue();
                if (!p.enabled()) {
                    continue;
                }
                simItems++;
                String id = en.getKey();
                cands.add(candidate(p, in.market().get(id), state.get(id), before.multipliers().get(id),
                        busy.contains(id), in.popular().contains(id), s));
            }
            BroadcastState bs = in.broadcast();
            boolean introPending = s.announce().intro() && !bs.introDone();
            EventPlanner.PlanInput pi = new EventPlanner.PlanInput(b, zone, s, cands, in.schedule(), activeHot,
                    activeDeal, simItems, in.online(), bs.lastAt(), bs.countOn(today), introPending);
            return EventPlanner.plan(pi, rng);
        }

        private void announce(long b, long today, MarketEvent flash, long flashDelay) {
            BroadcastState bs = in.broadcast();
            boolean introPending = s.announce().intro() && !bs.introDone();
            List<AnnounceGate.Pending> pending = new ArrayList<>(
                    AnnounceGate.collect(current(), b, s, rng, introPending));
            if (flash != null) {
                pending.add(new AnnounceGate.Pending(AnnounceGate.Type.FLASH, flash, b, b + 1, flashDelay, false));
            }
            Optional<AnnounceGate.Pending> chosen = AnnounceGate.choose(pending, b, zone, in.online(), bs.lastAt(),
                    bs.countOn(today), s, rng);
            if (chosen.isEmpty()) {
                return;
            }
            AnnounceGate.Pending p = chosen.get();
            if (p.type() == AnnounceGate.Type.INTRO) {
                bs.setIntroDone(true);
            } else {
                MarketEvent sent = AnnounceGate.markSent(p, b);
                for (Tracked t : events) {
                    if (t.now == p.event()) {
                        t.now = sent;
                        break;
                    }
                }
                p = p.withEvent(sent);
            }
            bs.record(b, today);
            broadcast = Optional.of(p);
        }

        private void finishEvaluation(long b) {
            Evaluation ev = evaluate(s, in.items(), in.market(), state, current(), in.spread(), b, seasonsNow);
            if (previous != null) {
                for (Map.Entry<String, Double> en : ev.multipliers().entrySet()) {
                    Double before = previous.get(en.getKey());
                    if (before != null && Math.abs(en.getValue() - before) > JUMP) {
                        jumped.add(en.getKey());
                    }
                }
            }
            previous = ev.multipliers();
            last = ev;
        }

        private MoodEngine.Breakdown moodOf(String id, long b) {
            ItemParams p = in.items().get(id);
            ItemSimState st = state.get(id);
            double season = 0.0;
            for (SeasonCalendar.Active a : seasonsNow) {
                season += a.effect(id);
            }
            List<MarketEvent> mine = new ArrayList<>();
            for (Tracked t : events) {
                if (id.equals(t.now.itemId()) && t.now.active(b)) {
                    mine.add(t.now);
                }
            }
            return MoodEngine.breakdown(p, st == null ? 0.0 : st.drift(), mine, season, b, s, in.spread());
        }

        private boolean hasTag(EventKind kind, String tag) {
            for (Tracked t : events) {
                if (t.now.kind() == kind && tag.equals(t.now.tag())) {
                    return true;
                }
            }
            for (Tracked t : retired) {
                if (t.now.kind() == kind && tag.equals(t.now.tag())) {
                    return true;
                }
            }
            return false;
        }

        private long stock(String id) {
            Quote q = in.market().get(id);
            return q == null ? 0L : q.stock();
        }

        private List<MarketEvent> current() {
            List<MarketEvent> out = new ArrayList<>(events.size());
            for (Tracked t : events) {
                out.add(t.now);
            }
            return out;
        }

        TickResult result(int skipped) {
            List<MarketEvent> started = new ArrayList<>();
            List<MarketEvent> changed = new ArrayList<>();
            List<Tracked> all = new ArrayList<>(events);
            all.addAll(retired);
            for (Tracked t : all) {
                if (t.original == null) {
                    started.add(t.now);
                } else if (!t.original.equals(t.now)) {
                    changed.add(t.now);
                }
            }
            started.sort((a, b) -> Long.compare(a.startedAt(), b.startedAt())); // stable: start order
            Evaluation ev = last;
            if (ev == null) {
                ev = new Evaluation(Map.of(), Map.of(), Map.of(), List.of());
            }
            return new TickResult(lastB, lastLive, ticks, skipped, ev.multipliers(), ev.status(), ev.breakdowns(),
                    List.copyOf(started), List.copyOf(changed), List.copyOf(current()), List.copyOf(seasonStarts),
                    ev.seasons(), broadcast, Collections.unmodifiableSet(new TreeSet<>(jumped)), newsHeld);
        }
    }

    /** The SEASON row's {@code pct}: its biggest entry, signed (the first one on a tie). */
    static double headlinePercent(Season season) {
        double best = 0.0;
        for (double v : season.percent().values()) {
            if (Math.abs(v) > Math.abs(best)) {
                best = v;
            }
        }
        return best;
    }
}
