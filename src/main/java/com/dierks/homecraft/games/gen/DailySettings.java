package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.Regions;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Daily Courses' settings: {@code games.daily} (GEN-SPEC §2.3).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml block
 * parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each key
 * over the defaults the way every game does: an out-of-range number is clamped with one WARN naming
 * its full key, and junk ({@code rollover: noon}, a word where a number belongs) closes Daily
 * Courses alone. A bad SLOT is different: an origin off the 16-block grid is rounded down, and a
 * slot whose tier, mix or region can't be used is switched off on its own with one WARN, so the
 * other courses still open ({@link Regions#validate}).
 *
 * @param enabled                   the game's own switch (ships off)
 * @param world                     the world the courses are built in; {@code ""} = the first of
 *                                  {@code games.worlds}
 * @param rollover                  when a new course day starts ({@code clock.time_zone})
 * @param startupDelaySeconds       after the server is up, before the first build
 * @param avoidBeforeRestartMinutes no build starts this close to one of {@code games.restart_times}
 * @param retryMinutes              a failed build is tried again after this
 * @param maxTriesPerDay            builds tried per slot per course day
 * @param clearWaitMinutes          how long someone still on a layout the next build needs gets
 * @param keepDays                  day boards and star rows older than this are pruned
 * @param worldRules                no mobs, fire, random ticks or weather; always noon
 * @param safeSpot                  where people in a building area are moved ({x, y, z}), or
 *                                  {@code null} for the world's spawn
 * @param dailyCap                  the most star-goal tokens a player earns a day
 * @param starGoals                 the weekly Star Chart goals, smallest first
 * @param starGoalReward            tokens per goal reached, once a week each
 * @param budget                    how much building a tick may do
 * @param stars                     the star times, as factors of a course's reference time
 * @param slots                     every slot's settings, in {@link Slots#ALL} order
 */
public record DailySettings(boolean enabled, String world, LocalTime rollover, int startupDelaySeconds,
                            int avoidBeforeRestartMinutes, int retryMinutes, int maxTriesPerDay, int clearWaitMinutes,
                            int keepDays, boolean worldRules, double[] safeSpot, int dailyCap, List<Integer> starGoals,
                            int starGoalReward, Budget budget, Stars stars, List<SlotConfig> slots) {

    /** The tiers a star factor is set for. */
    public static final List<String> TIERS = Slots.TIERS;

    /** The leaves under {@code games.daily}, in config order. */
    public static final List<String> KEYS = keys();

    public DailySettings {
        world = world == null ? "" : world.trim();
        rollover = rollover == null ? Edition.DEFAULT_ROLLOVER : rollover;
        safeSpot = safeSpot == null ? null : safeSpot.clone();
        starGoals = List.copyOf(starGoals == null ? List.of() : starGoals);
        slots = List.copyOf(slots == null ? List.of() : slots);
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
     * One slot's settings ({@code games.daily.slots.<id>}).
     *
     * @param id         the slot
     * @param enabled    its switch (an admin's {@code /hcm games gen on|off} wins over it)
     * @param tierOrMix  a tier ({@code easy}) or, for golf, a mix ({@code EEEMMMMHH})
     * @param origin     half A's min corner {x, y, z}, on the 16-block grid
     * @param dailyClear tokens for the first counted finish of a course day
     */
    public record SlotConfig(String id, boolean enabled, String tierOrMix, int[] origin, int dailyClear) {

        public SlotConfig {
            origin = origin == null ? new int[3] : origin.clone();
            if (origin.length != 3) {
                throw new IllegalArgumentException("an origin is x, y, z");
            }
        }

        /** The origin, {x, y, z} (a copy). */
        @Override
        public int[] origin() {
            return origin.clone();
        }

        /** The slot's fixed definition. */
        public Slots.Def def() {
            return Slots.of(id);
        }

        public SlotConfig withEnabled(boolean on) {
            return new SlotConfig(id, on, tierOrMix, origin, dailyClear);
        }

        public SlotConfig withOrigin(int[] o) {
            return new SlotConfig(id, enabled, tierOrMix, o, dailyClear);
        }

        public SlotConfig withTierOrMix(String t) {
            return new SlotConfig(id, enabled, t, origin, dailyClear);
        }

        /** The shipped settings of a slot. */
        public static SlotConfig shipped(Slots.Def d) {
            return new SlotConfig(d.id(), d.enabled(), d.tierOrMix(), d.origin(), d.dailyClear());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SlotConfig s && id.equals(s.id) && enabled == s.enabled
                    && tierOrMix.equals(s.tierOrMix) && Arrays.equals(origin, s.origin) && dailyClear == s.dailyClear;
        }

        @Override
        public int hashCode() {
            return (id.hashCode() * 31 + Arrays.hashCode(origin)) * 31 + tierOrMix.hashCode() + dailyClear
                    + (enabled ? 1 : 0);
        }

        @Override
        public String toString() {
            return "SlotConfig[" + id + ", " + (enabled ? "on" : "off") + ", " + tierOrMix + ", "
                    + Arrays.toString(origin) + ", " + dailyClear + "]";
        }
    }

    /** The shipped settings. */
    public static DailySettings defaults() {
        List<SlotConfig> slots = new ArrayList<>();
        for (Slots.Def d : Slots.ALL) {
            slots.add(SlotConfig.shipped(d));
        }
        return new DailySettings(false, "", Edition.DEFAULT_ROLLOVER, 60, 15, 30, 4, 20, 35, true, null, 2,
                List.of(10, 25), 1, new Budget(500, 5000, 4, 4, 2, 40),
                new Stars(tiers(2.0, 1.5, 1.25), tiers(3.0, 2.2, 1.8)), slots);
    }

    /** Read {@code games.daily} over {@code d}; never throws. */
    public static DailySettings parse(GamesConfig.Node n, DailySettings d) {
        boolean enabled = n.enabled(d.enabled());
        String world = n.text("world", d.world());
        LocalTime rollover = rollover(n, d.rollover());
        int startupDelay = n.whole("startup_delay_seconds", d.startupDelaySeconds(), 0, 3600);
        int avoid = n.whole("avoid_before_restart_minutes", d.avoidBeforeRestartMinutes(), 0, 120);
        int retry = n.whole("retry_minutes", d.retryMinutes(), 1, 1440);
        int maxTries = n.whole("max_tries_per_day", d.maxTriesPerDay(), 1, 48);
        int clearWait = n.whole("clear_wait_minutes", d.clearWaitMinutes(), 1, 120);
        int keepDays = n.whole("keep_days", d.keepDays(), 7, 3650);
        boolean worldRules = n.bool("world_rules", d.worldRules());
        double[] safeSpot = safeSpot(n, d.safeSpot());
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 100);
        List<Integer> goals = n.has("star_goals")
                ? List.copyOf(new TreeSet<>(n.intList("star_goals", d.starGoals(), 1, 1000))) : d.starGoals();
        int goalReward = n.whole("star_goal_reward", d.starGoalReward(), 0, 100);

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
            slots.add(slot(s, def, shipped == null ? SlotConfig.shipped(def) : shipped));
        }
        slots = Regions.validate(slots, n::warn, s.path());
        if (enabled) {
            GamesConfig.Common common = n.common();
            String note = Regions.rolloverNote(rollover, common == null ? List.of() : common.restartTimes());
            if (note != null) {
                n.info(n.key("rollover") + ": " + note);
            }
        }
        return new DailySettings(enabled, world, rollover, startupDelay, avoid, retry, maxTries, clearWait, keepDays,
                worldRules, safeSpot, dailyCap, goals, goalReward, budget, stars, slots);
    }

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

    /** The {@code daily_clear} tokens of a slot (0 for an unknown one). */
    public int dailyClear(String id) {
        SlotConfig c = slot(id);
        return c == null ? 0 : c.dailyClear();
    }

    @Override
    public double[] safeSpot() {
        return safeSpot == null ? null : safeSpot.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DailySettings x && enabled == x.enabled && world.equals(x.world)
                && rollover.equals(x.rollover) && startupDelaySeconds == x.startupDelaySeconds
                && avoidBeforeRestartMinutes == x.avoidBeforeRestartMinutes && retryMinutes == x.retryMinutes
                && maxTriesPerDay == x.maxTriesPerDay && clearWaitMinutes == x.clearWaitMinutes
                && keepDays == x.keepDays && worldRules == x.worldRules && Arrays.equals(safeSpot, x.safeSpot)
                && dailyCap == x.dailyCap && starGoals.equals(x.starGoals) && starGoalReward == x.starGoalReward
                && budget.equals(x.budget) && stars.equals(x.stars) && slots.equals(x.slots);
    }

    @Override
    public int hashCode() {
        return ((world.hashCode() * 31 + rollover.hashCode()) * 31 + slots.hashCode()) * 31
                + Arrays.hashCode(safeSpot) + (enabled ? 1 : 0);
    }

    @Override
    public String toString() {
        return "DailySettings[" + (enabled ? "on" : "off") + ", world '" + world + "', rollover " + rollover
                + ", slots " + slots + "]";
    }

    // ---- reading ------------------------------------------------------------------------------

    /** {@code rollover}: "HH:mm"; anything else is junk (every day hangs off it). */
    private static LocalTime rollover(GamesConfig.Node n, LocalTime d) {
        Object raw = n.raw("rollover");
        if (raw == null) {
            return d;
        }
        LocalTime t = RestartHold.parseTime(raw instanceof String ? raw : null);
        if (t == null) {
            n.invalid(n.key("rollover") + " should be a 24-hour time in quotes like \"04:00\", not \"" + raw + "\"");
            return d;
        }
        return t;
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
     * {@code slots.<id>}: its switch, tier (or {@code mix} for golf), origin and daily-clear tokens.
     * Anything here that can't be used switches this slot off with one WARN; it never closes the
     * rest of Daily Courses.
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
        String which = def.golf() ? "mix" : "tier";
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
        int dailyClear = d.dailyClear();
        Object dc = n.raw("daily_clear");
        if (dc != null) {
            if (!(dc instanceof Number num) || num.doubleValue() != Math.rint(num.doubleValue())) {
                n.warn(n.key("daily_clear") + " should be a whole number, not \"" + dc + "\" - that course is off");
                return d.withEnabled(false);
            }
            dailyClear = (int) n.clamp("daily_clear", num.doubleValue(), 0, 100, false);
        }
        return new SlotConfig(def.id(), enabled, tierOrMix, origin, dailyClear);
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
        List<String> out = new ArrayList<>(List.of("enabled", "world", "rollover", "startup_delay_seconds",
                "avoid_before_restart_minutes", "retry_minutes", "max_tries_per_day", "clear_wait_minutes",
                "keep_days", "world_rules", "safe_spot", "daily_cap", "star_goals", "star_goal_reward",
                "budget.blocks_per_tick", "budget.blocks_per_tick_idle", "budget.max_ms_per_tick",
                "budget.snapshots_per_tick", "budget.chunk_loads_in_flight", "budget.pause_above_mspt"));
        for (String kind : List.of("gold", "silver")) {
            for (String tier : TIERS) {
                out.add("stars." + kind + "." + tier);
            }
        }
        for (Slots.Def d : Slots.ALL) {
            String p = "slots." + d.id() + ".";
            out.add(p + "enabled");
            out.add(p + (d.golf() ? "mix" : "tier"));
            out.add(p + "origin");
            out.add(p + "daily_clear");
        }
        return List.copyOf(out);
    }
}
