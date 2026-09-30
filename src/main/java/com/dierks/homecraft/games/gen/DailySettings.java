package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import com.dierks.homecraft.games.gen.engine.Regions;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fresh Courses' settings: {@code games.fresh} (GEN-SPEC §2.3, weekly addendum §1 and §4).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block
 * parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each key
 * over the defaults the way every game does: an out-of-range number is clamped with one WARN naming
 * its full key, and junk (a word where a number belongs) closes Fresh Courses alone. A bad SLOT is
 * different: an origin off the 16-block grid is rounded down, and a slot whose tier, mix or region
 * can't be used is switched off on its own with one WARN, so the other courses still open
 * ({@link Regions#validate}).
 *
 * <p><b>The cadence keys never close anything.</b> {@code cadence}, {@code rebuild_at} and
 * {@code rebuild_day} decide when courses change, so a typo in one gets one WARN and the shipped
 * meaning (weekly, 04:00, the quests' week start) rather than switching every course off.
 *
 * <p><b>Rewards scale with the cadence.</b> The owner keeps two ends — what a daily cadence pays
 * and what a weekly one pays — for each course's first finish and for the Star Chart's goals, and
 * the amounts for the configured cadence are worked out from them ({@link DailyStars#scaled}), so
 * switching the cadence needs no retuning. {@link SlotConfig#dailyClear()} is already the amount for
 * the configured cadence.
 *
 * @param enabled                   the game's own switch (ships off)
 * @param world                     the world the courses are built in; {@code ""} = the first of
 *                                  {@code games.worlds}
 * @param cadenceDays               how many days an edition lasts: 7 (weekly, shipped), 1 (daily), or
 *                                  any whole number of days from 1 to 28
 * @param rollover                  when a new edition starts on its day ({@code rebuild_at},
 *                                  {@code clock.time_zone})
 * @param rebuildDay                the weekday editions are anchored on ({@code rebuild_day}), or
 *                                  {@code null} for the quests' week start
 * @param startupDelaySeconds       after the server is up, before the first build
 * @param avoidBeforeRestartMinutes no build starts this close to one of {@code games.restart_times}
 * @param retryMinutes              a failed build is tried again after this
 * @param maxTriesPerDay            builds tried per slot per course day
 * @param clearWaitMinutes          how long someone still on a layout the next build needs gets
 * @param keepDays                  edition boards and star rows older than this are pruned (the
 *                                  last 8 editions of each course are always kept)
 * @param worldRules                no mobs, fire, random ticks or weather; always noon
 * @param safeSpot                  where people in a building area are moved ({x, y, z}), or
 *                                  {@code null} for the world's spawn
 * @param dailyCap                  the most star-goal tokens a player earns a day
 * @param goals                     the weekly Star Chart goals at both ends of the cadence
 * @param budget                    how much building a tick may do
 * @param stars                     the star times, as factors of a course's reference time
 * @param rewards                   the first-finish tokens at both ends of the cadence
 * @param slots                     every slot's settings, in {@link Slots#ALL} order
 * @param archive                   the archive, the Classics slots and the keep area (GEN-SPEC-KEEP)
 * @param announce                  one chat line tells each player once when a new set is up
 *                                  ({@code announce}, EXTRAS E2: {@link NewCoursesNudge})
 */
public record DailySettings(boolean enabled, String world, int cadenceDays, LocalTime rollover, DayOfWeek rebuildDay,
                            int startupDelaySeconds, int avoidBeforeRestartMinutes, int retryMinutes,
                            int maxTriesPerDay, int clearWaitMinutes, int keepDays, boolean worldRules,
                            double[] safeSpot, int dailyCap, Goals goals, Budget budget, Stars stars, Rewards rewards,
                            List<SlotConfig> slots, Archive archive, boolean announce) {

    /** The tiers a star factor is set for. */
    public static final List<String> TIERS = Slots.TIERS;

    /** The leaves under {@code games.fresh}, in config order. */
    public static final List<String> KEYS = keys();
    /**
     * Leaves read under {@code games.fresh} that config.yml doesn't ship (so the backfill never adds
     * them): each slot's and Classics slot's {@code half_gap} and {@code keep.plot_gap}. Only an owner
     * (or a config migration keeping an owner's spot as it was) writes them; they are known keys, so
     * never reported as a typo.
     */
    public static final List<String> OPTIONAL_KEYS = optionalKeys();

    public DailySettings {
        world = world == null ? "" : world.trim();
        cadenceDays = Edition.clampCadence(cadenceDays);
        rollover = rollover == null ? Edition.DEFAULT_ROLLOVER : rollover;
        safeSpot = safeSpot == null ? null : safeSpot.clone();
        goals = goals == null ? Goals.shipped() : goals;
        rewards = rewards == null ? Rewards.shipped() : rewards;
        slots = List.copyOf(slots == null ? List.of() : slots);
        archive = archive == null ? Archive.shipped() : archive;
    }

    /** Without {@code announce} (the shape before EXTRAS E2): it is on, as shipped. */
    public DailySettings(boolean enabled, String world, int cadenceDays, LocalTime rollover, DayOfWeek rebuildDay,
                         int startupDelaySeconds, int avoidBeforeRestartMinutes, int retryMinutes, int maxTriesPerDay,
                         int clearWaitMinutes, int keepDays, boolean worldRules, double[] safeSpot, int dailyCap,
                         Goals goals, Budget budget, Stars stars, Rewards rewards, List<SlotConfig> slots,
                         Archive archive) {
        this(enabled, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds, avoidBeforeRestartMinutes,
                retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules, safeSpot, dailyCap, goals, budget,
                stars, rewards, slots, archive, true);
    }

    /** Without the archive's settings (the shape before GEN-SPEC-KEEP): they are the shipped ones. */
    public DailySettings(boolean enabled, String world, int cadenceDays, LocalTime rollover, DayOfWeek rebuildDay,
                         int startupDelaySeconds, int avoidBeforeRestartMinutes, int retryMinutes, int maxTriesPerDay,
                         int clearWaitMinutes, int keepDays, boolean worldRules, double[] safeSpot, int dailyCap,
                         Goals goals, Budget budget, Stars stars, Rewards rewards, List<SlotConfig> slots) {
        this(enabled, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds, avoidBeforeRestartMinutes,
                retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules, safeSpot, dailyCap, goals, budget,
                stars, rewards, slots, null);
    }

    /**
     * The archive of every edition, the Classics slots and the keep area (GEN-SPEC-KEEP §1, §3, §4,
     * §8): {@code archive.keep}, {@code feed_history}, {@code classics.*} and {@code keep.*}.
     *
     * @param keepDays    days an edition stays archived after it ended; 0 = forever (shipped)
     * @param feedHistory past courses per slot in the website's {@code freshHistory}
     * @param classicDays how long a recalled course stays up when no time is given
     * @param classics    each Classics slot's region (its origin); one whose area can't be used is off
     * @param keep        the keep area and its plots
     * @param keepProblem why the keep area can't be used (keep is refused), or {@code null}
     */
    public record Archive(int keepDays, int feedHistory, int classicDays, List<SlotConfig> classics, KeepArea keep,
                          String keepProblem) {

        public Archive {
            classics = List.copyOf(classics == null ? List.of() : classics);
        }

        /**
         * The shipped keep area's first plot corner ({@code keep.area}, LAYOUT-SPEC §1.5): west of the
         * courses, 24 plots from x 1760, z 7296, {@link KeepArea#DEFAULT_GAP} apart, so no kept course
         * can see another, or any course.
         */
        public static final List<Integer> KEEP_AREA = List.of(1760, 128, 7296);

        /** The shipped settings: forever, 26 on the website, a week, the shipped regions and 24 plots. */
        public static Archive shipped() {
            List<SlotConfig> c = new ArrayList<>();
            for (Slots.Def d : Slots.CLASSICS) {
                c.add(SlotConfig.shipped(d));
            }
            return new Archive(0, 26, 7, c, new KeepArea(KEEP_AREA.get(0), KEEP_AREA.get(1), KEEP_AREA.get(2), 24), null);
        }

        /** A Classics slot's settings, or {@code null}. */
        public SlotConfig classic(String id) {
            Slots.Def def = Slots.classic(id);
            if (def == null) {
                return null;
            }
            for (SlotConfig c : classics) {
                if (c.id().equals(def.id())) {
                    return c;
                }
            }
            return null;
        }

        public Archive withKeep(KeepArea k, String problem) {
            return new Archive(keepDays, feedHistory, classicDays, classics, k, problem);
        }

        public Archive withClassics(List<SlotConfig> c) {
            return new Archive(keepDays, feedHistory, classicDays, c, keep, keepProblem);
        }

        public Archive withKeepDays(int days) {
            return new Archive(days, feedHistory, classicDays, classics, keep, keepProblem);
        }
    }

    /**
     * How much building one tick may do (§3.3): blocks while anyone is online and while nobody
     * is, milliseconds, chunk snapshots, chunk loads waiting at once, and the average tick time
     * above which building pauses (it resumes a quarter lower).
     */
    public record Budget(int blocksPerTick, int blocksPerTickIdle, int maxMsPerTick, int snapshotsPerTick,
                         int chunkLoadsInFlight, int pauseAboveMspt) {

        /** The average tick time building resumes below: a quarter under the pause line (30 for 40). */
        public double resumeBelowMspt() {
            return pauseAboveMspt * 0.75;
        }
    }

    /**
     * The star times (§5.2): gold (3 stars) and silver (2 stars) as factors of a trial's reference
     * time, per tier. Silver is never faster than gold.
     *
     * @param gold   {@code easy}, {@code medium}, {@code hard} → factor
     * @param silver the same
     */
    public record Stars(Map<String, Double> gold, Map<String, Double> silver) {

        public Stars {
            gold = Collections.unmodifiableMap(new LinkedHashMap<>(gold));
            silver = Collections.unmodifiableMap(new LinkedHashMap<>(silver));
        }

        /** The gold factor for {@code tier} (medium's for an unknown one). */
        public double gold(String tier) {
            return factor(gold, tier);
        }

        /** The silver factor for {@code tier} (medium's for an unknown one). */
        public double silver(String tier) {
            return factor(silver, tier);
        }

        private static double factor(Map<String, Double> m, String tier) {
            Double f = tier == null ? null : m.get(tier.toLowerCase(Locale.ROOT));
            if (f == null) {
                f = m.get("medium");
            }
            return f == null ? 1.0 : f;
        }
    }

    /**
     * The first finish of each course in an edition ({@code rewards.clear_weekly} and
     * {@code rewards.clear_daily}, by slot id): the two ends a cadence's amount is worked out
     * from ({@link #clear}).
     *
     * @param clearWeekly tokens when the cadence is weekly or longer
     * @param clearDaily  tokens when the cadence is daily
     */
    public record Rewards(Map<String, Integer> clearWeekly, Map<String, Integer> clearDaily) {

        public Rewards {
            clearWeekly = Collections.unmodifiableMap(new LinkedHashMap<>(clearWeekly));
            clearDaily = Collections.unmodifiableMap(new LinkedHashMap<>(clearDaily));
        }

        /**
         * The shipped tables (the addendum's: Easy 2/1, Parkour 3/2, Hard 5/3, Sky Rings 3/2, Golf 3/2, Tiny 2/1;
         * and EVENTS-DROPPER-SPEC §B.1.8's Easy Dropper 2/1, Dropper 3/2).
         */
        public static Rewards shipped() {
            Map<String, Integer> weekly = new LinkedHashMap<>();
            Map<String, Integer> daily = new LinkedHashMap<>();
            for (Slots.Def d : Slots.ALL) {
                weekly.put(d.id(), d.weeklyClear());
                daily.put(d.id(), d.dailyClear());
            }
            return new Rewards(weekly, daily);
        }

        /** What the first finish of {@code slotId} in an edition pays at an N-day cadence (0 for an unknown slot). */
        public int clear(String slotId, int cadence) {
            Slots.Def d = Slots.of(slotId);
            if (d == null) {
                return 0;
            }
            return DailyStars.scaled(cadence, clearDaily.getOrDefault(d.id(), d.dailyClear()),
                    clearWeekly.getOrDefault(d.id(), d.weeklyClear()));
        }
    }

    /**
     * The weekly Star Chart goals at both ends of the cadence ({@code star_goals.*}): the stars
     * and what each pays, smallest first.
     *
     * @param weekly the goals when the cadence is weekly or longer (shipped 6★ +1, 12★ +2)
     * @param daily  the goals when the cadence is daily (shipped 10★ +1, 25★ +1)
     */
    public record Goals(List<DailyStars.Goal> weekly, List<DailyStars.Goal> daily) {

        public Goals {
            weekly = List.copyOf(weekly == null ? List.of() : weekly);
            daily = List.copyOf(daily == null ? List.of() : daily);
        }

        public static Goals shipped() {
            return new Goals(List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2)),
                    List.of(new DailyStars.Goal(10, 1), new DailyStars.Goal(25, 1)));
        }

        /** The goals for an N-day cadence, before the week's ceiling ({@link DailyStars#goals}). */
        public List<DailyStars.Goal> forCadence(int cadence) {
            return DailyStars.goals(cadence, daily, weekly);
        }
    }

    /**
     * One slot's settings ({@code games.fresh.slots.<id>}).
     *
     * @param id         the slot
     * @param enabled    its switch (an admin's {@code /hcm games gen on|off} wins over it)
     * @param tierOrMix  a tier ({@code easy}) or, for golf and the droppers, a mix ({@code EEEMMMMHH}, {@code EEMMH})
     * @param origin     half A's min corner {x, y, z}, on the 16-block grid
     * @param dailyClear tokens for the first counted finish of an edition, at the configured cadence
     *                   (worked out from {@link Rewards})
     * @param halfGap    the blocks between its half A and half B along +X ({@code half_gap}, optional;
     *                   {@link Slots#HALF_GAP} when config names none). Where half B stands, so it is
     *                   part of the claim: changing it moves the spare half like changing the origin.
     */
    public record SlotConfig(String id, boolean enabled, String tierOrMix, int[] origin, int dailyClear,
                             int halfGap) {

        public SlotConfig {
            origin = origin == null ? new int[3] : origin.clone();
            if (origin.length != 3) {
                throw new IllegalArgumentException("an origin is x, y, z");
            }
            if (halfGap < 0) {
                throw new IllegalArgumentException("a half gap is 0 or more blocks: " + halfGap);
            }
        }

        /** A slot at the default gap ({@link Slots#HALF_GAP}). */
        public SlotConfig(String id, boolean enabled, String tierOrMix, int[] origin, int dailyClear) {
            this(id, enabled, tierOrMix, origin, dailyClear, Slots.HALF_GAP);
        }

        /** The origin, {x, y, z} (a copy). */
        @Override
        public int[] origin() {
            return origin.clone();
        }

        /** The slot's (or Classics slot's) fixed definition. */
        public Slots.Def def() {
            return Slots.any(id);
        }

        public SlotConfig withEnabled(boolean on) {
            return new SlotConfig(id, on, tierOrMix, origin, dailyClear, halfGap);
        }

        public SlotConfig withOrigin(int[] o) {
            return new SlotConfig(id, enabled, tierOrMix, o, dailyClear, halfGap);
        }

        public SlotConfig withTierOrMix(String t) {
            return new SlotConfig(id, enabled, t, origin, dailyClear, halfGap);
        }

        public SlotConfig withDailyClear(int tokens) {
            return new SlotConfig(id, enabled, tierOrMix, origin, tokens, halfGap);
        }

        public SlotConfig withHalfGap(int gap) {
            return new SlotConfig(id, enabled, tierOrMix, origin, dailyClear, gap);
        }

        /** The shipped settings of a slot (at the shipped, weekly, cadence). */
        public static SlotConfig shipped(Slots.Def d) {
            return new SlotConfig(d.id(), d.enabled(), d.tierOrMix(), d.origin(),
                    DailyStars.scaled(Edition.DEFAULT_CADENCE, d.dailyClear(), d.weeklyClear()));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SlotConfig s && id.equals(s.id) && enabled == s.enabled
                    && tierOrMix.equals(s.tierOrMix) && Arrays.equals(origin, s.origin) && dailyClear == s.dailyClear
                    && halfGap == s.halfGap;
        }

        @Override
        public int hashCode() {
            return ((id.hashCode() * 31 + Arrays.hashCode(origin)) * 31 + tierOrMix.hashCode() + dailyClear
                    + (enabled ? 1 : 0)) * 31 + halfGap;
        }

        @Override
        public String toString() {
            return "SlotConfig[" + id + ", " + (enabled ? "on" : "off") + ", " + tierOrMix + ", "
                    + Arrays.toString(origin) + ", " + dailyClear + ", gap " + halfGap + "]";
        }
    }

    /** The shipped settings. */
    public static DailySettings defaults() {
        List<SlotConfig> slots = new ArrayList<>();
        for (Slots.Def d : Slots.ALL) {
            slots.add(SlotConfig.shipped(d));
        }
        return new DailySettings(false, "", Edition.DEFAULT_CADENCE, Edition.DEFAULT_ROLLOVER, null, 60, 15, 30, 4,
                20, 35, true, null, 2, Goals.shipped(), new Budget(500, 5000, 4, 4, 2, 40),
                new Stars(tiers(2.0, 1.5, 1.25), tiers(3.0, 2.2, 1.8)), Rewards.shipped(), slots, Archive.shipped(),
                true);
    }

    /** Read {@code games.fresh} over {@code d}; never throws. */
    public static DailySettings parse(GamesConfig.Node n, DailySettings d) {
        boolean enabled = n.enabled(d.enabled());
        String world = n.text("world", d.world());
        int cadence = cadence(n, d.cadenceDays());
        LocalTime rebuildAt = rebuildAt(n, d.rollover());
        DayOfWeek rebuildDay = rebuildDay(n, d.rebuildDay());
        int startupDelay = n.whole("startup_delay_seconds", d.startupDelaySeconds(), 0, 3600);
        int avoid = n.whole("avoid_before_restart_minutes", d.avoidBeforeRestartMinutes(), 0, 120);
        int retry = n.whole("retry_minutes", d.retryMinutes(), 1, 1440);
        int maxTries = n.whole("max_tries_per_day", d.maxTriesPerDay(), 1, 48);
        int clearWait = n.whole("clear_wait_minutes", d.clearWaitMinutes(), 1, 120);
        int keepDays = n.whole("keep_days", d.keepDays(), 7, 3650);
        boolean worldRules = n.bool("world_rules", d.worldRules());
        double[] safeSpot = safeSpot(n, d.safeSpot());
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 100);
        boolean announce = n.bool("announce", d.announce());

        GamesConfig.Node r = n.child("rewards");
        Rewards rewards = new Rewards(r.wholeMap("clear_weekly", d.rewards().clearWeekly(), 0, 100),
                r.wholeMap("clear_daily", d.rewards().clearDaily(), 0, 100));

        GamesConfig.Node g = n.child("star_goals");
        Goals goals = new Goals(goals(g, "weekly", d.goals().weekly()), goals(g, "daily", d.goals().daily()));

        GamesConfig.Node b = n.child("budget");
        Budget db = d.budget();
        Budget budget = new Budget(b.whole("blocks_per_tick", db.blocksPerTick(), 1, 100_000),
                b.whole("blocks_per_tick_idle", db.blocksPerTickIdle(), 1, 1_000_000),
                b.whole("max_ms_per_tick", db.maxMsPerTick(), 1, 50),
                b.whole("snapshots_per_tick", db.snapshotsPerTick(), 1, 64),
                b.whole("chunk_loads_in_flight", db.chunkLoadsInFlight(), 1, 64),
                b.whole("pause_above_mspt", db.pauseAboveMspt(), 20, 1000));

        Stars stars = stars(n.child("stars"), d.stars());

        List<SlotConfig> slots = new ArrayList<>();
        GamesConfig.Node s = n.child("slots");
        for (Slots.Def def : Slots.ALL) {
            SlotConfig shipped = d.slot(def.id());
            slots.add(slot(s, def, shipped == null ? SlotConfig.shipped(def) : shipped)
                    .withDailyClear(rewards.clear(def.id(), cadence)));
        }
        slots = Regions.validate(slots, n::warn, s.path());
        Archive archive = archive(n, d.archive(), slots);
        if (enabled) {
            GamesConfig.Common common = n.common();
            String note = Regions.rolloverNote(rebuildAt, common == null ? List.of() : common.restartTimes());
            if (note != null) {
                n.info(n.key("rebuild_at") + ": " + note);
            }
        }
        return new DailySettings(enabled, world, cadence, rebuildAt, rebuildDay, startupDelay, avoid, retry, maxTries,
                clearWait, keepDays, worldRules, safeSpot, dailyCap, goals, budget, stars, rewards, slots, archive,
                announce);
    }

    /**
     * {@code archive.keep}, {@code feed_history}, {@code classics.*} and {@code keep.*}. A Classics
     * slot's origin is checked like a slot's (on the grid, in range, 32 from every other half); one
     * that can't be used is off on its own. A keep area that overlaps a Fresh Courses area or comes
     * within 16 blocks of one gets one WARN and keep is refused until it is moved.
     */
    private static Archive archive(GamesConfig.Node n, Archive d, List<SlotConfig> slots) {
        int keep = n.child("archive").whole("keep", d.keepDays(), 0, 36_500);
        int feed = n.whole("feed_history", d.feedHistory(), 0, 520);
        GamesConfig.Node cn = n.child("classics");
        int days = cn.whole("days", d.classicDays(), 1, 365);
        GamesConfig.Node cs = cn.child("slots");
        List<SlotConfig> both = new ArrayList<>(slots);
        for (Slots.Def def : Slots.CLASSICS) {
            SlotConfig shipped = d.classic(def.id());
            SlotConfig c = shipped == null ? SlotConfig.shipped(def) : shipped;
            Object raw = cs.raw(def.id());
            if (raw != null) {
                Object o = raw instanceof Map<?, ?> ? cs.child(def.id()).raw("origin") : raw;
                int[] read = o == null ? c.origin() : origin(o);
                if (read == null) {
                    cs.warn(cs.key(def.id()) + ".origin should be three whole numbers [x, y, z] - that Classics slot"
                            + " is off");
                    c = c.withEnabled(false);
                } else {
                    c = c.withOrigin(read);
                }
                if (raw instanceof Map<?, ?> && c.enabled()) {
                    Integer gap = halfGap(cs.child(def.id()), c.halfGap());
                    c = gap == null ? c.withEnabled(false) : c.withHalfGap(gap);
                }
            }
            both.add(c);
        }
        both = Regions.validate(both, cs::warn, cs.path());
        List<SlotConfig> classics = new ArrayList<>(both.subList(slots.size(), both.size()));

        GamesConfig.Node kn = n.child("keep");
        KeepArea dk = d.keep();
        int plots = kn.whole("max_plots", dk.maxPlots(), 1, 100);
        int[] at = {dk.x(), dk.y(), dk.z()};
        Object ra = kn.raw("area");
        String problem = null;
        if (ra != null) {
            int[] read = origin(ra);
            if (read == null) {
                problem = "keep.area should be three whole numbers [x, y, z]";
            } else {
                at = read;
            }
        }
        int gap = dk.gap();
        Object rg = kn.raw("plot_gap");
        String gapProblem = null;
        if (rg != null) {
            Integer read = wholeBlocks(rg);
            if (read == null || read < 0 || read > KeepArea.MAX_GAP) {
                // where every plot stands: no other gap is guessed, so keeping waits until it is fixed
                gapProblem = "keep.plot_gap should be a whole number of blocks, 0-" + KeepArea.MAX_GAP + ", not " + rg;
                kn.warn(kn.key("plot_gap") + " should be a whole number of blocks, 0-" + KeepArea.MAX_GAP + ", not "
                        + rg + " - keeping a course is off until it is fixed");
            } else {
                gap = Math.floorDiv(read, Slots.GAP_GRID) * Slots.GAP_GRID;
                if (gap != read) {
                    kn.warn(kn.key("plot_gap") + " " + read + " is not a multiple of " + Slots.GAP_GRID + " - using "
                            + gap);
                }
            }
        }
        KeepArea area = new KeepArea(at[0], at[1], at[2], plots, gap);
        if (problem == null) {
            List<Box> halves = new ArrayList<>();
            for (SlotConfig c : both) {
                Slots.Def def = c.def();
                if (def != null) {
                    halves.addAll(Regions.halves(def, c.origin(), c.halfGap()));
                }
            }
            problem = area.problem(halves);
        }
        if (problem != null) {
            kn.warn(kn.key("area") + " " + area.describe() + ": " + problem + " - keeping a course is off until it"
                    + " is moved");
        } else {
            problem = gapProblem;
        }
        return new Archive(keep, feed, days, classics, area, problem);
    }

    // ---- what the engine and the screens ask ----------------------------------------------------

    /** One slot's settings, or {@code null} for an unknown id. */
    public SlotConfig slot(String id) {
        Slots.Def def = Slots.of(id);
        if (def == null) {
            return null;
        }
        for (SlotConfig c : slots) {
            if (c.id().equals(def.id())) {
                return c;
            }
        }
        return null;
    }

    /** What the first counted finish of {@code id} in an edition pays at the configured cadence (0: unknown id). */
    public int dailyClear(String id) {
        SlotConfig c = slot(id);
        return c == null ? 0 : c.dailyClear();
    }

    /**
     * What the first counted finish of {@code id} pays in an edition of {@code cadence} days (the
     * run's own {@code tag.cadence()}: a layout kept over a cadence change pays by its own edition),
     * from {@code rewards} (0: unknown id).
     */
    public int dailyClear(String id, int cadence) {
        return slot(id) == null ? 0 : rewards.clear(id, cadence);
    }

    /** The edition rules these settings make, in {@code zone} with the quests' {@code weekStart}. */
    public Edition edition(ZoneId zone, DayOfWeek weekStart) {
        return new Edition(zone, rollover, weekStart, cadenceDays, rebuildDay);
    }

    /** The Star Chart goals at the configured cadence, before the week's ceiling. */
    public List<DailyStars.Goal> starGoalList() {
        return goals.forCadence(cadenceDays);
    }

    /** The stars of each goal at the configured cadence, smallest first, before the week's ceiling. */
    public List<Integer> starGoals() {
        return DailyStars.stars(starGoalList());
    }

    /** The goals for a week that can give at most {@code weekMax} stars ({@link DailyStars#clamp}). */
    public List<DailyStars.Goal> starGoals(int weekMax) {
        return DailyStars.clamp(starGoalList(), weekMax);
    }

    /** The cadence as status shows it: "weekly", "daily", "every 3 days". */
    public String cadenceName() {
        return GenCopy.cadenceName(cadenceDays);
    }

    @Override
    public double[] safeSpot() {
        return safeSpot == null ? null : safeSpot.clone();
    }

    // ---- withers (tests and admins) ---------------------------------------------------------------

    public DailySettings withEnabled(boolean on) {
        return new DailySettings(on, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, budget, stars, rewards, slots, archive, announce);
    }

    public DailySettings withWorld(String w) {
        return new DailySettings(enabled, w, cadenceDays, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, budget, stars, rewards, slots, archive, announce);
    }

    /** Another cadence; each slot's first-finish amount follows it. */
    public DailySettings withCadence(int days) {
        int n = Edition.clampCadence(days);
        List<SlotConfig> out = new ArrayList<>();
        for (SlotConfig c : slots) {
            out.add(c.withDailyClear(rewards.clear(c.id(), n)));
        }
        return new DailySettings(enabled, world, n, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, budget, stars, rewards, out, archive, announce);
    }

    /** Another {@code rebuild_at} and {@code rebuild_day} ({@code null}: the quests' week start). */
    public DailySettings withRebuild(LocalTime at, DayOfWeek day) {
        return new DailySettings(enabled, world, cadenceDays, at, day, startupDelaySeconds, avoidBeforeRestartMinutes,
                retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules, safeSpot, dailyCap, goals,
                budget, stars, rewards, slots, archive, announce);
    }

    public DailySettings withBudget(Budget b) {
        return new DailySettings(enabled, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, b, stars, rewards, slots, archive, announce);
    }

    public DailySettings withSlots(List<SlotConfig> s) {
        return new DailySettings(enabled, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, budget, stars, rewards, s, archive, announce);
    }

    /** Other archive, Classics and keep settings. */
    public DailySettings withArchive(Archive a) {
        return new DailySettings(enabled, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, budget, stars, rewards, slots, a, announce);
    }

    /** The new-courses line on or off. */
    public DailySettings withAnnounce(boolean on) {
        return new DailySettings(enabled, world, cadenceDays, rollover, rebuildDay, startupDelaySeconds,
                avoidBeforeRestartMinutes, retryMinutes, maxTriesPerDay, clearWaitMinutes, keepDays, worldRules,
                safeSpot, dailyCap, goals, budget, stars, rewards, slots, archive, on);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DailySettings x && enabled == x.enabled && world.equals(x.world)
                && cadenceDays == x.cadenceDays && rollover.equals(x.rollover)
                && Objects.equals(rebuildDay, x.rebuildDay) && startupDelaySeconds == x.startupDelaySeconds
                && avoidBeforeRestartMinutes == x.avoidBeforeRestartMinutes && retryMinutes == x.retryMinutes
                && maxTriesPerDay == x.maxTriesPerDay && clearWaitMinutes == x.clearWaitMinutes
                && keepDays == x.keepDays && worldRules == x.worldRules && Arrays.equals(safeSpot, x.safeSpot)
                && dailyCap == x.dailyCap && goals.equals(x.goals) && budget.equals(x.budget)
                && stars.equals(x.stars) && rewards.equals(x.rewards) && slots.equals(x.slots)
                && archive.equals(x.archive) && announce == x.announce;
    }

    @Override
    public int hashCode() {
        return (((world.hashCode() * 31 + rollover.hashCode()) * 31 + slots.hashCode()) * 31
                + Arrays.hashCode(safeSpot)) * 31 + cadenceDays + (enabled ? 1 : 0);
    }

    @Override
    public String toString() {
        return "DailySettings[" + (enabled ? "on" : "off") + ", world '" + world + "', " + cadenceName()
                + " at " + rollover + (rebuildDay == null ? "" : " on " + rebuildDay) + ", slots " + slots + "]";
    }

    // ---- reading ------------------------------------------------------------------------------

    /**
     * {@code cadence}: {@code weekly} (7), {@code daily} (1), or a whole number of days from 1 to
     * {@value Edition#MAX_CADENCE} (quoted or not). Anything else gets one WARN and is weekly.
     */
    static int cadence(GamesConfig.Node n, int d) {
        Object raw = n.raw("cadence");
        if (raw == null) {
            return d;
        }
        Integer days = null;
        if (raw instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue())
                && Math.abs(num.doubleValue()) <= Edition.MAX_CADENCE) {
            days = num.intValue();
        } else if (raw instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("weekly")) {
                days = Edition.WEEKLY;
            } else if (t.equals("daily")) {
                days = Edition.DAILY;
            } else if (t.matches("\\d{1,2}")) {
                days = Integer.parseInt(t);
            }
        }
        if (days == null || days < Edition.DAILY || days > Edition.MAX_CADENCE) {
            n.warn(n.key("cadence") + " should be weekly, daily or a whole number of days from 1 to "
                    + Edition.MAX_CADENCE + ", not \"" + raw + "\" - using weekly");
            return Edition.WEEKLY;
        }
        return days;
    }

    /** {@code rebuild_at}: "HH:mm" in quotes; anything else gets one WARN and is 04:00. */
    static LocalTime rebuildAt(GamesConfig.Node n, LocalTime d) {
        Object raw = n.raw("rebuild_at");
        if (raw == null) {
            return d;
        }
        LocalTime t = RestartHold.parseTime(raw instanceof String ? raw : null);
        if (t == null) {
            n.warn(n.key("rebuild_at") + " should be a 24-hour time in quotes like \"04:00\", not \"" + raw
                    + "\" - using 04:00");
            return Edition.DEFAULT_ROLLOVER;
        }
        return t;
    }

    /**
     * {@code rebuild_day}: {@code ""} for the quests' week start, or a day of the week ({@code monday}
     * or {@code mon}, any case). Anything else gets one WARN and is the quests' week start.
     */
    static DayOfWeek rebuildDay(GamesConfig.Node n, DayOfWeek d) {
        Object raw = n.raw("rebuild_day");
        if (raw == null) {
            return d;
        }
        if (raw instanceof String s) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) {
                return null;
            }
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().equals(t) || (t.length() == 3 && day.name().startsWith(t))) {
                    return day;
                }
            }
        }
        n.warn(n.key("rebuild_day") + " should be a day of the week like \"monday\", or \"\" for the quests' week"
                + " start, not \"" + raw + "\" - using the quests' week start");
        return null;
    }

    /**
     * {@code star_goals.<end>} and {@code star_goals.<end>_tokens}: the stars, and what each pays in
     * the same order. A token list of another length gets one WARN: missing amounts repeat the last
     * one, extra ones are dropped. Junk in either list closes Fresh Courses, like any list.
     */
    private static List<DailyStars.Goal> goals(GamesConfig.Node g, String end, List<DailyStars.Goal> d) {
        List<Integer> stars = g.intList(end, DailyStars.stars(d), 1, 1000);
        List<Integer> dTokens = new ArrayList<>();
        for (DailyStars.Goal goal : d) {
            dTokens.add(goal.tokens());
        }
        if (!g.has(end) && !g.has(end + "_tokens")) {
            return d;
        }
        List<Integer> tokens = g.has(end + "_tokens") ? g.intList(end + "_tokens", dTokens, 0, 100) : dTokens;
        if (tokens.size() != stars.size()) {
            g.warn(g.key(end + "_tokens") + " " + tokens + " should list one amount for each of " + stars
                    + " - the last amount is used for the rest");
        }
        List<DailyStars.Goal> out = new ArrayList<>();
        for (int i = 0; i < stars.size(); i++) {
            int t = tokens.isEmpty() ? 1 : tokens.get(Math.min(i, tokens.size() - 1));
            out.add(new DailyStars.Goal(stars.get(i), t));
        }
        return DailyStars.tidy(out);
    }

    /** {@code safe_spot}: {@code ""} for the world's spawn, or "x y z"; anything else is junk. */
    private static double[] safeSpot(GamesConfig.Node n, double[] d) {
        String raw = n.text("safe_spot", null);
        if (raw == null) {
            return d;
        }
        if (raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split("[\\s,]+");
        if (parts.length == 3) {
            try {
                double[] out = new double[3];
                for (int i = 0; i < 3; i++) {
                    out[i] = Double.parseDouble(parts[i]);
                    if (!Double.isFinite(out[i])) {
                        throw new NumberFormatException(parts[i]);
                    }
                }
                return out;
            } catch (NumberFormatException e) {
                // junk: below
            }
        }
        n.invalid(n.key("safe_spot") + " should be \"x y z\" or \"\" for the world's spawn, not \"" + raw + "\"");
        return d;
    }

    private static Stars stars(GamesConfig.Node n, Stars d) {
        GamesConfig.Node g = n.child("gold");
        GamesConfig.Node s = n.child("silver");
        Map<String, Double> gold = new LinkedHashMap<>();
        Map<String, Double> silver = new LinkedHashMap<>();
        for (String tier : TIERS) {
            double gf = g.num(tier, d.gold(tier), 1.0, 10.0);
            double sf = s.num(tier, d.silver(tier), 1.0, 20.0);
            if (sf < gf) {
                s.warn(s.key(tier) + " " + sf + " is faster than the gold time (" + gf + ") - using " + gf);
                sf = gf;
            }
            gold.put(tier, gf);
            silver.put(tier, sf);
        }
        return new Stars(gold, silver);
    }

    /**
     * {@code slots.<id>}: its switch, tier (or {@code mix} for golf and the droppers) and origin. Anything here that
     * can't be used switches this slot off with one WARN; it never closes the rest of Fresh
     * Courses. (Its first-finish tokens are in {@code rewards}.)
     */
    private static SlotConfig slot(GamesConfig.Node slots, Slots.Def def, SlotConfig d) {
        Object raw = slots.raw(def.id());
        String key = slots.key(def.id());
        if (raw == null) {
            return d;
        }
        if (!(raw instanceof Map<?, ?>)) {
            Boolean on = GamesConfig.readSwitch(raw);
            if (on == null) {
                slots.warn(key + " should be a section of settings, not \"" + raw + "\" - that course is off");
                return d.withEnabled(false);
            }
            slots.warn(key + " should be a section of settings - reading it as " + key + ".enabled: " + on
                    + " and the rest as shipped");
            return d.withEnabled(on);
        }
        GamesConfig.Node n = slots.child(def.id());
        boolean enabled = d.enabled();
        Object en = n.raw("enabled");
        if (en != null) {
            Boolean on = GamesConfig.readSwitch(en);
            if (on == null) {
                n.warn(n.key("enabled") + " should be true or false, not \"" + en + "\" - that course is off");
                return d.withEnabled(false);
            }
            enabled = on;
        }
        String which = def.mixed() ? "mix" : "tier";
        String tierOrMix = d.tierOrMix();
        Object t = n.raw(which);
        if (t != null) {
            if (t instanceof Map<?, ?> || t instanceof List<?>) {
                n.warn(n.key(which) + " should be a single value - that course is off");
                return d.withEnabled(false);
            }
            tierOrMix = def.normalise(String.valueOf(t));
        }
        int[] origin = d.origin();
        Object o = n.raw("origin");
        if (o != null) {
            int[] read = origin(o);
            if (read == null) {
                n.warn(n.key("origin") + " should be three whole numbers [x, y, z], not " + o
                        + " - that course is off");
                return d.withEnabled(false);
            }
            origin = read;
        }
        Integer gap = halfGap(n, d.halfGap());
        if (gap == null) {
            return d.withEnabled(false);
        }
        return new SlotConfig(def.id(), enabled, tierOrMix, origin, d.dailyClear(), gap);
    }

    /**
     * {@code half_gap} under {@code n} (optional): the blocks between the slot's halves, a whole
     * number {@value Slots#MIN_HALF_GAP}..{@value Slots#MAX_HALF_GAP}; one off the 16-block grid is
     * rounded down with a WARN, as an origin is. {@code d} when unset; {@code null} (one WARN, that
     * course is off) when it can't be used: the gap decides where the spare half stands, so no other
     * gap is guessed in its place.
     */
    private static Integer halfGap(GamesConfig.Node n, int d) {
        Object raw = n.raw("half_gap");
        if (raw == null) {
            return d;
        }
        Integer gap = wholeBlocks(raw);
        if (gap == null || gap < Slots.MIN_HALF_GAP || gap > Slots.MAX_HALF_GAP) {
            n.warn(n.key("half_gap") + " should be a whole number of blocks, " + Slots.MIN_HALF_GAP + "-"
                    + Slots.MAX_HALF_GAP + ", not " + raw + " - that course is off");
            return null;
        }
        int aligned = Math.floorDiv(gap, Slots.GAP_GRID) * Slots.GAP_GRID;
        if (aligned != gap) {
            n.warn(n.key("half_gap") + " " + gap + " is not a multiple of " + Slots.GAP_GRID + " - using " + aligned);
        }
        return aligned;
    }

    /** A whole number (an integral Number, not a boolean or text), or {@code null}. */
    private static Integer wholeBlocks(Object raw) {
        if (!(raw instanceof Number num) || num.doubleValue() != Math.rint(num.doubleValue())
                || Math.abs(num.doubleValue()) > Integer.MAX_VALUE / 2.0) {
            return null;
        }
        return num.intValue();
    }

    /** Three whole numbers, or {@code null}. */
    private static int[] origin(Object raw) {
        if (!(raw instanceof List<?> l) || l.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number num) || num.doubleValue() != Math.rint(num.doubleValue())
                    || Math.abs(num.doubleValue()) > Integer.MAX_VALUE / 2.0) {
                return null;
            }
            out[i] = num.intValue();
        }
        return out;
    }

    private static Map<String, Double> tiers(double easy, double medium, double hard) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("easy", easy);
        m.put("medium", medium);
        m.put("hard", hard);
        return m;
    }

    private static List<String> keys() {
        List<String> out = new ArrayList<>(List.of("enabled", "world", "cadence", "rebuild_at", "rebuild_day",
                "startup_delay_seconds", "avoid_before_restart_minutes", "retry_minutes", "max_tries_per_day",
                "clear_wait_minutes", "keep_days", "world_rules", "safe_spot", "daily_cap", "announce"));
        for (String table : List.of("clear_weekly", "clear_daily")) {
            for (Slots.Def d : Slots.ALL) {
                out.add("rewards." + table + "." + d.id());
            }
        }
        out.addAll(List.of("star_goals.weekly", "star_goals.weekly_tokens", "star_goals.daily",
                "star_goals.daily_tokens", "budget.blocks_per_tick", "budget.blocks_per_tick_idle",
                "budget.max_ms_per_tick", "budget.snapshots_per_tick", "budget.chunk_loads_in_flight",
                "budget.pause_above_mspt"));
        for (String kind : List.of("gold", "silver")) {
            for (String tier : TIERS) {
                out.add("stars." + kind + "." + tier);
            }
        }
        for (Slots.Def d : Slots.ALL) {
            String p = "slots." + d.id() + ".";
            out.add(p + "enabled");
            out.add(p + (d.mixed() ? "mix" : "tier"));
            out.add(p + "origin");
        }
        out.addAll(List.of("archive.keep", "feed_history", "classics.days"));
        for (Slots.Def d : Slots.CLASSICS) {
            out.add("classics.slots." + d.id() + ".origin");
        }
        out.addAll(List.of("keep.area", "keep.max_plots"));
        return List.copyOf(out);
    }

    private static List<String> optionalKeys() {
        List<String> out = new ArrayList<>();
        for (Slots.Def d : Slots.ALL) {
            out.add("slots." + d.id() + ".half_gap");
        }
        for (Slots.Def d : Slots.CLASSICS) {
            out.add("classics.slots." + d.id() + ".half_gap");
        }
        out.add("keep.plot_gap");
        return List.copyOf(out);
    }
}
