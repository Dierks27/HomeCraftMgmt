package com.dierks.homecraft.market.sim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether an event starts at a live tick, and which (spec §5.3, §5.4). At most one
 * start per tick, checked in order news flash, HOT, DEAL. Pure: every roll is
 * {@code u(stream, b / 60000)} from the counter-based {@link SimRandom}, so a tick replayed with
 * the same inputs plans exactly the same thing.
 *
 * <p><b>Shared eligibility</b> (every kind): the item is sim-enabled (the caller only passes
 * those), {@code sim_weight > 0}, {@code floor < ceiling}, and no HOT/DEAL/UP/DOWN is active on
 * it ({@link Candidate#busy}). <b>Pick weight</b>: {@code sim_weight x (popular ? popular_weight
 * : 1)}, over ids sorted ascending.
 *
 * <p><b>Headroom</b>: {@code up = min(Hi, C/B) - M0}, {@code down = M0 - max(Lo, F/B)}. An event
 * is only created if {@code 0.9 x headroom} reaches its minimum, and its strength is capped at
 * {@code 0.9 x headroom}, so every announced % is real.
 *
 * <p><b>HOT/DEAL</b>: slots {@code clamp(1 + floor(N_sim / slots_per_items), 1, 3)} of each,
 * both together at most {@code max(2, floor(N_sim / 3))}; the item needs stock, 7 days' rest
 * after its last HOT/DEAL, 24 h since its last news, and (DEAL) at least
 * {@code max(16, min_stock_percent% of full_stock)} in stock. It ramps silently and is
 * announced at full strength.
 *
 * <p><b>News flash</b>: only inside news hours and under {@code news.max_per_day}; a due flash
 * waits ({@code wait_for_players}) until the gate is open and someone has been online for its
 * join delay (fixed per scheduled flash), for at most {@code max_hold_hours}, then fires
 * silently. WANTED (sold-out items, once a week each) takes {@code wanted_share} of flashes;
 * otherwise up to 5 items are tried for an UP or DOWN, leaning back toward the usual price.
 *
 * <p>Plain Java, no Bukkit: unit-tested without a server ({@code EventPlannerTest}).
 */
public final class EventPlanner {

    /** HOT/DEAL need this long since the item's last news flash. */
    public static final long NEWS_TO_FEATURE_MS = 24 * SimMath.HOUR_MS;
    /** After a failed look for a candidate, look again this much later. */
    public static final long RETRY_MS = SimMath.HOUR_MS;
    /** A due flash that fell outside news hours or over the daily cap waits for the opening plus up to this. */
    public static final long OPENING_SPREAD_MS = 90 * SimMath.MINUTE_MS;
    /** News items tried per flash. */
    public static final int NEWS_ATTEMPTS = 5;
    /** The share of headroom an event may use. */
    public static final double HEADROOM_SHARE = 0.9;
    /** A DEAL needs at least this many units in stock, whatever {@code min_stock_percent} says. */
    public static final long DEAL_MIN_UNITS = 16;
    /** An admin-forced event smaller than this, after the clamps, is refused. */
    public static final double FORCED_MIN = 0.05;
    /** Admin-forced news sizes are held to this range (percent). */
    public static final double FORCED_NEWS_MIN_PERCENT = 10.0;

    /**
     * One sim-enabled item as the planner sees it at the boundary.
     *
     * @param weight        {@code sim_weight x (popular ? popular_weight : 1)}
     * @param base          the balanced price (clamped to the band here)
     * @param m0            the item's current multiplier without any new event
     * @param busy          a HOT/DEAL/UP/DOWN is active on it
     * @param featuredUntil when its last HOT/DEAL ended (0 = never)
     * @param lastNewsAt    when it last had an UP/DOWN (0 = never)
     * @param lastWantedAt  when it last had a WANTED (0 = never)
     */
    public record Candidate(String id, double weight, double base, double floor, double ceiling, long stock,
                            long fullStock, double m0, boolean busy, long featuredUntil, long lastNewsAt,
                            long lastWantedAt) {

        public Candidate {
            Objects.requireNonNull(id, "id");
        }

        /** Shared eligibility: weight above 0, a real band, nothing active. */
        public boolean shared() {
            return weight > 0.0 && Double.isFinite(weight) && floor < ceiling && !busy;
        }

        /** {@code B = clamp(base, F, C)}. */
        public double balanced() {
            return Math.max(floor, Math.min(ceiling, base));
        }

        /** {@code min(Hi, C/B) - M0}. */
        public double headroomUp(SimSettings s) {
            return SimMath.headroomUp(balanced(), floor, ceiling, m0, s.multiplierHi());
        }

        /** {@code M0 - max(Lo, F/B)}. */
        public double headroomDown(SimSettings s) {
            return SimMath.headroomDown(balanced(), floor, ceiling, m0, s.multiplierLo());
        }

        /** The displayed price at multiplier {@code m} (clamped to the band first). */
        public double priceAt(double m, SimSettings s) {
            double mm = SimLimits.clampMultiplier(m, s.multiplierLo(), s.multiplierHi());
            return SimMath.displayPrice(base, stock, mm, floor, ceiling);
        }
    }

    /**
     * Everything one planning step needs.
     *
     * @param b               the boundary (epoch ms)
     * @param zone            {@code clock.time_zone}
     * @param s               settings
     * @param c               the sim-enabled items (any order; sorted by id here)
     * @param sched           the schedule; updated in place
     * @param activeHot       HOT events active at {@code b}
     * @param activeDeal      DEAL events active at {@code b}
     * @param simItems        {@code N_sim}: how many items are sim-enabled
     * @param online          who is online
     * @param lastBroadcastAt when the last market broadcast went out (0 = never)
     * @param broadcastsToday broadcasts so far on {@code b}'s local day
     * @param introPending    the one-time intro has yet to go out: a flash is not "ready" until it
     *                        has, so the two never collide in one tick
     */
    public record PlanInput(long b, ZoneId zone, SimSettings s, List<Candidate> c, Schedule sched, int activeHot,
                            int activeDeal, int simItems, OnlineInfo online, long lastBroadcastAt,
                            int broadcastsToday, boolean introPending) {

        public PlanInput {
            Objects.requireNonNull(s, "s");
            Objects.requireNonNull(sched, "sched");
            zone = zone == null ? ZoneOffset.UTC : zone;
            c = c == null ? List.of() : c;
            online = online == null ? OnlineInfo.NOBODY : online;
        }
    }

    /**
     * What a planning step decided.
     *
     * @param event       the event that starts now ({@code headline}/{@code line} not yet set),
     *                    or {@code null}
     * @param live        a news flash that goes out now (announce it in this tick); false for a
     *                    silent flash and for HOT/DEAL (announced later, at full strength)
     * @param joinDelayMs the flash's join delay (0 when there is no flash)
     * @param newsHeld    a due flash is being held for a player ("held: waiting for a player")
     */
    public record Planned(MarketEvent event, boolean live, long joinDelayMs, boolean newsHeld) {

        static final Planned NOTHING = new Planned(null, false, 0L, false);

        public boolean started() {
            return event != null;
        }
    }

    /**
     * An admin-forced event, or why it was refused.
     *
     * @param event the event ({@code null} when refused)
     * @param error the refusal, plain words ({@code null} when created)
     */
    public record Forced(MarketEvent event, String error) {

        static Forced refused(String why) {
            return new Forced(null, why);
        }

        public boolean ok() {
            return event != null;
        }
    }

    private EventPlanner() {
    }

    // ---- slots ---------------------------------------------------------------------------

    /** HOT (and DEAL) slots: {@code clamp(1 + floor(N_sim / slots_per_items), 1, 3)}. */
    public static int slots(int simItems, int slotsPerItems) {
        int per = Math.max(1, slotsPerItems);
        long n = 1L + Math.max(0, simItems) / per;
        return (int) Math.max(1L, Math.min(3L, n));
    }

    /** HOT and DEAL together: at most {@code max(2, floor(N_sim / 3))}. */
    public static int combinedCap(int simItems) {
        return Math.max(2, Math.max(0, simItems) / 3);
    }

    // ---- the plan ------------------------------------------------------------------------

    /**
     * One planning step at {@code in.b()}: a news flash first, then HOT, then DEAL, stopping at
     * the first start. Updates {@code in.sched()} in place.
     */
    public static Planned plan(PlanInput in, SimRandom rng) {
        long b = in.b();
        long c = Math.floorDiv(b, SimMath.MINUTE_MS);
        List<Candidate> cands = new ArrayList<>(in.c());
        cands.sort(Comparator.comparing(Candidate::id));
        ZonedDateTime local = Instant.ofEpochMilli(b).atZone(in.zone());

        boolean held = false;
        Planned news = planNews(in, rng, cands, local, c);
        if (news.started()) {
            return news;
        }
        held = news.newsHeld();
        for (EventKind kind : new EventKind[]{EventKind.HOT, EventKind.DEAL}) {
            MarketEvent e = planStory(kind, in, rng, cands, c);
            if (e != null) {
                return new Planned(e, false, 0L, held);
            }
        }
        return held ? new Planned(null, false, 0L, true) : Planned.NOTHING;
    }

    private static Planned planNews(PlanInput in, SimRandom rng, List<Candidate> cands, ZonedDateTime local, long c) {
        SimSettings s = in.s();
        SimSettings.News news = s.news();
        Schedule sched = in.sched();
        long b = in.b();
        if (!news.enabled() || b < sched.nextNewsAt()) {
            return Planned.NOTHING;
        }
        LocalDate today = local.toLocalDate();
        if (!news.hours().contains(local.toLocalTime())) {
            sched.setNextNewsAt(nextOpening(local, news.hours()) + spread(rng, c));
            return Planned.NOTHING;
        }
        if (sched.newsOn(today.toEpochDay()) >= news.maxPerDay()) {
            sched.setNextNewsAt(opening(today.plusDays(1), news.hours(), in.zone()) + spread(rng, c));
            return Planned.NOTHING;
        }
        long delay = newsJoinDelayMs(s, rng, sched.nextNewsAt());
        boolean ready = !in.introPending() && in.online().ready(delay)
                && AnnounceGate.gateOpen(b, in.zone(), in.lastBroadcastAt(), in.broadcastsToday(), s);
        boolean expired = b - sched.nextNewsAt() >= Math.round(news.maxHoldHours() * SimMath.HOUR_MS);
        if (news.waitForPlayers() && !ready && !expired) {
            return new Planned(null, false, 0L, true);
        }

        MarketEvent e = wanted(in, rng, cands, c);
        if (e == null) {
            e = upOrDown(in, rng, cands, c);
        }
        if (e == null) {
            sched.setNextNewsAt(b + RETRY_MS);
            return Planned.NOTHING;
        }
        sched.countNews(today.toEpochDay());
        double gapH = Math.min(news.maxGapHours(),
                news.gapHours() - news.extraHours() * Math.log(1.0 - rng.uniform("news.gap", c)));
        sched.setNextNewsAt(b + Math.round(gapH * SimMath.HOUR_MS));
        return new Planned(e, ready, delay, false);
    }

    /**
     * A flash's join delay, fixed per scheduled flash:
     * {@code lerp(join_delay_minutes, u("news.join", next_news_at / 60000))} minutes.
     */
    public static long newsJoinDelayMs(SimSettings s, SimRandom rng, long nextNewsAt) {
        double u = rng.uniform("news.join", Math.floorDiv(nextNewsAt, SimMath.MINUTE_MS));
        return Math.round(s.news().joinDelayMinutes().lerp(u) * SimMath.MINUTE_MS);
    }

    private static MarketEvent wanted(PlanInput in, SimRandom rng, List<Candidate> cands, long c) {
        SimSettings.News news = in.s().news();
        long every = news.wantedEveryDays() * SimMath.DAY_MS;
        List<Candidate> pool = new ArrayList<>();
        for (Candidate k : cands) {
            if (k.shared() && k.stock() == 0 && in.b() - k.lastWantedAt() >= every) {
                pool.add(k);
            }
        }
        if (pool.isEmpty() || !(rng.uniform("news.kind", c) < news.wantedShare())) {
            return null;
        }
        int at = SimRandom.pickWeighted(weights(pool), rng.uniform("news.wanted", c));
        if (at < 0) {
            return null;
        }
        Candidate k = pool.get(at);
        long ends = in.b() + Math.max(SimMath.DAY_MS, every);
        double p = k.priceAt(k.m0(), in.s());
        return MarketEvent.info(EventKind.WANTED, Source.SIM, k.id(), null, in.b(), ends).withPrices(0.0, p, p);
    }

    private static MarketEvent upOrDown(PlanInput in, SimRandom rng, List<Candidate> cands, long c) {
        SimSettings s = in.s();
        SimSettings.News news = s.news();
        long b = in.b();
        long cooldown = Math.round(news.itemCooldownHours() * SimMath.HOUR_MS);
        double minMove = news.minMoveFrac();
        List<Candidate> pool = new ArrayList<>();
        for (Candidate k : cands) {
            if (k.shared() && k.stock() > 0 && b - k.lastNewsAt() >= cooldown) {
                pool.add(k);
            }
        }
        for (int attempt = 0; attempt < NEWS_ATTEMPTS && !pool.isEmpty(); attempt++) {
            int at = SimRandom.pickWeighted(weights(pool), rng.uniform("news.item." + attempt, c));
            if (at < 0) {
                return null;
            }
            Candidate k = pool.remove(at);
            boolean up = rng.uniform("news.dir." + attempt, c) < SimMath.pUp(k.m0() - 1.0);
            double j = news.size(rng.uniform("news.size." + attempt, c));
            double hu = HEADROOM_SHARE * k.headroomUp(s);
            double hd = HEADROOM_SHARE * k.headroomDown(s);
            boolean lowStock = k.stock() < news.minStockPercent() / 100.0 * k.fullStock();
            if (up && hu < minMove) {
                up = false;
            }
            if (!up && (hd < minMove || lowStock)) {
                if (hu >= minMove) {
                    up = true;
                } else {
                    continue;
                }
            }
            double a = Math.min(j, up ? hu : hd);
            if (a < minMove || !(a > 0.0)) {
                continue;
            }
            EventKind kind = up ? EventKind.UP : EventKind.DOWN;
            MarketEvent e = MarketEvent.shock(kind, Source.SIM, k.id(), kind.sign() * a, b,
                    news.halfLifeMs(), news.lastsMs());
            return priced(e, k, s);
        }
        return null;
    }

    private static MarketEvent planStory(EventKind kind, PlanInput in, SimRandom rng, List<Candidate> cands, long c) {
        SimSettings s = in.s();
        SimSettings.Story k = s.story(kind);
        Schedule sched = in.sched();
        long b = in.b();
        if (k == null || !k.enabled() || b < sched.nextAt(kind)) {
            return null;
        }
        int slots = slots(in.simItems(), s.slotsPerItems());
        int activeK = kind == EventKind.HOT ? in.activeHot() : in.activeDeal();
        if (activeK >= slots || in.activeHot() + in.activeDeal() >= combinedCap(in.simItems())) {
            return null;
        }
        long rest = s.cooldownDays() * SimMath.DAY_MS;
        List<Candidate> pool = new ArrayList<>();
        for (Candidate x : cands) {
            if (storyEligible(kind, x, b, rest, k, s)) {
                pool.add(x);
            }
        }
        String p = kind.id();
        if (pool.isEmpty()) {
            sched.setNextAt(kind, b + RETRY_MS);
            return null;
        }
        int at = SimRandom.pickWeighted(weights(pool), rng.uniform(p + ".item", c));
        if (at < 0) {
            sched.setNextAt(kind, b + RETRY_MS);
            return null;
        }
        Candidate x = pool.get(at);
        double room = HEADROOM_SHARE * (kind == EventKind.HOT ? x.headroomUp(s) : x.headroomDown(s));
        double a = Math.min(k.strength(rng.uniform(p + ".size", c)), room);
        long hold = Math.round(k.holdHours().lerp(rng.uniform(p + ".hold", c)) * SimMath.HOUR_MS);
        MarketEvent e = MarketEvent.story(kind, Source.SIM, x.id(), kind.sign() * a, b, k.rampMs(), hold, k.fadeMs());
        long gap = Math.round(k.gapHours().lerp(rng.uniform(p + ".gap", c)) * SimMath.HOUR_MS);
        sched.setNextAt(kind, b + (k.rampMs() + hold + k.fadeMs() + gap) / slots(in.simItems(), s.slotsPerItems()));
        return priced(e, x, s);
    }

    /** HOT/DEAL candidate rules (§5.4). */
    static boolean storyEligible(EventKind kind, Candidate x, long b, long restMs, SimSettings.Story k, SimSettings s) {
        if (!x.shared() || x.stock() <= 0) {
            return false;
        }
        if (b < x.featuredUntil() + restMs || b - x.lastNewsAt() < NEWS_TO_FEATURE_MS) {
            return false;
        }
        double room = HEADROOM_SHARE * (kind == EventKind.HOT ? x.headroomUp(s) : x.headroomDown(s));
        if (!(room > 0.0) || room < k.minFrac()) {
            return false;
        }
        if (kind == EventKind.DEAL) {
            double need = Math.max(DEAL_MIN_UNITS, k.minStockPercent() / 100.0 * x.fullStock());
            return x.stock() >= need;
        }
        return true;
    }

    /** {@code price_before = P(M0)}, {@code price_after = P(M0 + strength)}, pct from those. */
    private static MarketEvent priced(MarketEvent e, Candidate k, SimSettings s) {
        double before = k.priceAt(k.m0(), s);
        double after = k.priceAt(k.m0() + e.strength(), s);
        return e.withPrices(SimMath.pct(after, before), before, after);
    }

    private static double[] weights(List<Candidate> pool) {
        double[] w = new double[pool.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = pool.get(i).weight();
        }
        return w;
    }

    private static long spread(SimRandom rng, long c) {
        return Math.round(rng.uniform("news.open", c) * OPENING_SPREAD_MS);
    }

    /** The next time the news hours open after {@code local} (which is outside them). */
    static long nextOpening(ZonedDateTime local, SimSettings.Hours hours) {
        ZonedDateTime at = local.toLocalDate().atTime(hours.from()).atZone(local.getZone());
        if (!at.isAfter(local)) {
            at = local.toLocalDate().plusDays(1).atTime(hours.from()).atZone(local.getZone());
        }
        return at.toInstant().toEpochMilli();
    }

    /** The news hours' opening on local day {@code day}. */
    static long opening(LocalDate day, SimSettings.Hours hours, ZoneId zone) {
        return day.atTime(hours.from()).atZone(zone).toInstant().toEpochMilli();
    }

    // ---- admin-forced events -------------------------------------------------------------

    /**
     * {@code /hcm market news <item> <up|down|wanted> [percent]}: a flash now, skipping the
     * schedule, cooldowns, hours, hold and daily caps. The size is {@code percent} held to
     * 10-25 (or a roll in {@code news.percent}), still capped at 90% of the headroom; refused
     * when that leaves less than 5%. WANTED needs the item to be sold out. Rolls use streams
     * {@code admin.*} at {@code counter} ({@code manual_counter}).
     */
    public static Forced forceNews(Candidate k, EventKind kind, Double percent, long now, SimSettings s,
                                   SimRandom rng, long counter) {
        if (kind == EventKind.WANTED) {
            if (k.stock() > 0) {
                return Forced.refused("Crate already has some " + k.id() + " - WANTED is for sold-out items.");
            }
            double p = k.priceAt(k.m0(), s);
            long every = Math.max(SimMath.DAY_MS, s.news().wantedEveryDays() * SimMath.DAY_MS);
            return new Forced(MarketEvent.info(EventKind.WANTED, Source.ADMIN, k.id(), null, now, now + every)
                    .withPrices(0.0, p, p), null);
        }
        if (kind != EventKind.UP && kind != EventKind.DOWN) {
            return Forced.refused("Not a news kind: " + kind);
        }
        if (!(k.floor() < k.ceiling())) {
            return Forced.refused(k.id() + " has a fixed price (floor = ceiling).");
        }
        if (k.stock() <= 0) {
            return Forced.refused(k.id() + " is sold out, so its price sits at the ceiling.");
        }
        double j;
        if (percent != null && Double.isFinite(percent)) {
            j = SimLimits.clampNews(Math.max(FORCED_NEWS_MIN_PERCENT, percent) / 100.0);
        } else {
            j = s.news().size(rng.uniform("admin.news.size", counter));
        }
        double room = HEADROOM_SHARE * (kind == EventKind.UP ? k.headroomUp(s) : k.headroomDown(s));
        double a = Math.min(j, room);
        if (!(a >= FORCED_MIN)) {
            return Forced.refused(String.format(java.util.Locale.ROOT,
                    "%s can only go %s %.1f%% right now (at least 5%% needed).",
                    k.id(), kind == EventKind.UP ? "up" : "down", Math.max(0.0, a) * 100.0));
        }
        MarketEvent e = MarketEvent.shock(kind, Source.ADMIN, k.id(), kind.sign() * a, now,
                s.news().halfLifeMs(), s.news().lastsMs());
        return new Forced(priced(e, k, s), null);
    }

    /**
     * {@code /hcm market sim hot|deal <item> [percent] [hours]}: a HOT or DEAL at full strength
     * now (no ramp, announced at once). {@code percent} is held to 0-15 (default: a roll in
     * {@code percent}); {@code hours} is the hold (default: a roll in {@code hold_hours}). Still
     * capped at 90% of the headroom and refused below 5%, and refused when the item already has
     * an event or is sold out.
     */
    public static Forced forceStory(Candidate k, EventKind kind, Double percent, Double hours, long now,
                                    SimSettings s, SimRandom rng, long counter) {
        if (kind == null || !kind.story()) {
            return Forced.refused("Not HOT or DEAL: " + kind);
        }
        if (k.busy()) {
            return Forced.refused(k.id() + " already has an event running.");
        }
        if (!(k.floor() < k.ceiling())) {
            return Forced.refused(k.id() + " has a fixed price (floor = ceiling).");
        }
        if (k.stock() <= 0) {
            return Forced.refused(k.id() + " is sold out, so its price sits at the ceiling.");
        }
        SimSettings.Story st = s.story(kind);
        double a0 = percent != null && Double.isFinite(percent)
                ? SimLimits.clampStory(percent / 100.0)
                : st.strength(rng.uniform("admin.story.size", counter));
        double room = HEADROOM_SHARE * (kind == EventKind.HOT ? k.headroomUp(s) : k.headroomDown(s));
        double a = Math.min(a0, room);
        if (!(a >= FORCED_MIN)) {
            return Forced.refused(String.format(java.util.Locale.ROOT,
                    "%s can only go %s %.1f%% right now (at least 5%% needed).",
                    k.id(), kind == EventKind.HOT ? "up" : "down", Math.max(0.0, a) * 100.0));
        }
        double holdH = hours != null && Double.isFinite(hours) && hours > 0
                ? hours : st.holdHours().lerp(rng.uniform("admin.story.hold", counter));
        MarketEvent e = MarketEvent.story(kind, Source.ADMIN, k.id(), kind.sign() * a, now, 0L,
                Math.round(holdH * SimMath.HOUR_MS), st.fadeMs());
        return new Forced(priced(e, k, s), null);
    }
}
