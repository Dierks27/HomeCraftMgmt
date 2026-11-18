package com.dierks.homecraft.config;

import com.dierks.homecraft.arcade.candy.CandyRules;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/**
 * Reads {@code arcade.events.turkey}, Catch the Turkey's settings (0.38.6), the way {@link CandyConfig} reads the
 * candy shower's: it never throws, and gives one WARN naming the key for each problem.
 *
 * <p><b>Dates.</b> {@code from} and {@code to} are local dates ({@code clock.time_zone}), both included, quoted or
 * bare. A date that can't be read, or {@code from} after {@code to}, switches the turkeys off with one WARN. The
 * event's year (the claims, the feather's name, the Turkey Mini) comes from {@code from}.
 *
 * <p><b>Numbers.</b> Each is squeezed into its range, with one WARN when it had to change. A missing key reads as
 * the shipped value, so a config.yml without the section (or with the bundled one) parses to
 * {@link Turkey#DEFAULTS} without a single WARN. Pinned by {@code TurkeyConfigTest}.
 */
public final class TurkeyConfig {

    /** Where the section lives in config.yml. */
    public static final String PATH = "arcade.events.turkey";

    /**
     * The shipped window: the week of Thanksgiving 2026, the same days the Turkey Mini 2026 can be earned, so a
     * catch inside the window is always inside the Mini's.
     */
    public static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 11, 22);
    public static final LocalDate DEFAULT_TO = LocalDate.of(2026, 11, 29);
    /** A window longer than this still runs, with a WARN. */
    static final int LONG_WINDOW_DAYS = 62;

    /** The ranges: minutes between runs, a run's length, the turkey's size, tokens and the Mini's chance. */
    static final int MIN_EVERY = 30;
    static final int MAX_EVERY = 360;
    static final int MIN_RUN = 1;
    static final int MAX_RUN = 10;
    static final double MIN_SCALE = 1.0;
    static final double MAX_SCALE = 3.0;

    private TurkeyConfig() {
    }

    /**
     * Catch the Turkey's settings.
     *
     * @param enabled           the switch (off too when a date can't be read)
     * @param from              the first day, local; the event's year is its year
     * @param to                the last day, local
     * @param everyMinutes      a run every this many minutes, counted from local midnight plus the offset
     * @param offsetMinutes     the day's first run, in minutes after midnight (below {@code everyMinutes})
     * @param runMinutes        how long each run's turkeys stay
     * @param welcome           a turkey of your own 90 seconds after your first join of the day
     * @param scale             how big the turkey is (1.0 is a plain chicken)
     * @param tokens            tokens for each catch (once a run)
     * @param dailyTokenCap     the most tokens a player gets from turkeys in a local day
     * @param feather           each catch also gives a Turkey Feather keepsake
     * @param miniChancePercent the chance in 100 that a catch also gives a Turkey Mini (at most one extra a season)
     * @param randomTimes       0.44.0: each {@code everyMinutes} window of the day has its run at a random minute
     *                          (the same for everyone, new every day), instead of at {@code offsetMinutes} past it
     * @param quietFromHour     0.44.0: no run from this local hour ...
     * @param quietToHour       ... to this one (the same hour: no quiet hours); the runs share the rest of the day
     * @param catchUp           0.44.0: a player who has played {@code catchUpMinutes} today without catching a turkey
     *                          gets one of their own, once a day (the owner: "so everyone has a clear opportunity")
     * @param catchUpMinutes    how long they play first (1-60)
     * @param catchUpLastDays   only on the event's last this many days (the owner: "the closer we get to the end of
     *                          the event ... within the last 3 days or so"); 0 = every day
     * @param stampede          0.44.0: turkeys let loose around the Hub at each run's start for everyone there to
     *                          catch, as many as they can (0: none)
     * @param stampedeMinutes   how long the stampede lasts (1-30)
     * @param stampedeRadius    how far from the Hub's arrival spot they are let loose (16-200)
     */
    public record Turkey(boolean enabled, LocalDate from, LocalDate to, int everyMinutes, int offsetMinutes,
                         int runMinutes, boolean welcome, double scale, int tokens, int dailyTokenCap,
                         boolean feather, int miniChancePercent, boolean randomTimes, int quietFromHour,
                         int quietToHour, boolean catchUp, int catchUpMinutes, int catchUpLastDays, int stampede,
                         int stampedeMinutes, int stampedeRadius) {

        /**
         * The shipped settings: six runs a day at random times, never from 2 to 8 in the morning (the owner: "we
         * aren't playing in the early morning"), three minutes each, and (0.44.0, the owner: "a special event so not
         * every single time someone logs in") no welcome turkey, but on the last three days a catch-up turkey for
         * anyone who has played ten minutes that day without catching one.
         */
        public static final Turkey DEFAULTS = new Turkey(true, DEFAULT_FROM, DEFAULT_TO, 240, 50, 3, false, 2.0, 3, 6,
                true, 5, true, 2, 8, true, 10, 3, 100, 10, 80);
        /** Until config.yml has been read: the shipped settings, switched off. */
        public static final Turkey OFF = DEFAULTS.withEnabled(false);

        /** The same settings, switched on or off. */
        public Turkey withEnabled(boolean on) {
            return new Turkey(on, from, to, everyMinutes, offsetMinutes, runMinutes, welcome, scale, tokens,
                    dailyTokenCap, feather, miniChancePercent, randomTimes, quietFromHour, quietToHour, catchUp,
                    catchUpMinutes, catchUpLastDays, stampede, stampedeMinutes, stampedeRadius);
        }

        /** The event's year, from {@code from}: the claim keys, the feather's name and the Mini's id. */
        public int year() {
            return from == null ? DEFAULT_FROM.getYear() : from.getYear();
        }

        /** Switched on, and {@code today} inside the window. The caller adds {@code arcade.enabled}. */
        public boolean open(LocalDate today) {
            return enabled && CandyRules.open(today, from, to);
        }

        /** Whether the catch-up turkey is on {@code today}: switched on, and one of the event's last days. */
        public boolean catchUpOn(LocalDate today) {
            return catchUp && com.dierks.homecraft.arcade.turkey.TurkeyRules.lastDays(today, to, catchUpLastDays);
        }

        /** No run at local minute {@code minuteOfDay} (the quiet hours). */
        public boolean quiet(int minuteOfDay) {
            return com.dierks.homecraft.arcade.turkey.TurkeyRules.quiet(minuteOfDay, quietFromHour, quietToHour);
        }

        /** A run's length in milliseconds. */
        public long runMillis() {
            return runMinutes * 60_000L;
        }
    }

    /** The section's settings from {@code root} (the whole config.yml), its WARNs to {@code warn} (may be null). */
    public static Turkey parse(ConfigurationSection root, Consumer<String> warn) {
        Consumer<String> w = warn == null ? s -> {
        } : warn;
        if (root == null) {
            return Turkey.DEFAULTS;
        }
        Turkey d = Turkey.DEFAULTS;
        boolean enabled = root.getBoolean(PATH + ".enabled", d.enabled());

        LocalDate from = date(root, "from", DEFAULT_FROM, w);
        LocalDate to = date(root, "to", DEFAULT_TO, w);
        if (from == null || to == null) {
            // the WARN is said; the shipped date stands in so the year and the status still read
            enabled = false;
            from = from == null ? DEFAULT_FROM : from;
            to = to == null ? DEFAULT_TO : to;
        } else if (from.isAfter(to)) {
            w.accept(PATH + ".from (" + from + ") is after " + PATH + ".to (" + to + ") - Catch the Turkey is off.");
            enabled = false;
        } else if (from.getYear() != to.getYear() || CandyRules.days(from, to) > LONG_WINDOW_DAYS) {
            w.accept(PATH + " runs from " + from + " to " + to + " (" + CandyRules.days(from, to) + " days"
                    + (from.getYear() != to.getYear() ? ", into another year" : "") + ") - it still runs, and its "
                    + "year is " + from.getYear() + " (from " + PATH + ".from).");
        }

        int every = number(root, "every_minutes", d.everyMinutes(), MIN_EVERY, MAX_EVERY, w);
        int offset = number(root, "offset_minutes", d.offsetMinutes(), 0, every - 1, w);
        int run = number(root, "run_minutes", d.runMinutes(), MIN_RUN, MAX_RUN, w);
        boolean welcome = root.getBoolean(PATH + ".welcome", d.welcome());
        double scale = scale(root, d.scale(), w);
        int tokens = number(root, "tokens", d.tokens(), 0, 20, w);
        int cap = number(root, "daily_token_cap", d.dailyTokenCap(), 0, 50, w);
        boolean feather = root.getBoolean(PATH + ".feather", d.feather());
        int mini = number(root, "mini_chance_percent", d.miniChancePercent(), 0, 100, w);
        boolean random = root.getBoolean(PATH + ".random_times", d.randomTimes());
        int quietFrom = number(root, "quiet_from_hour", d.quietFromHour(), 0, 23, w);
        int quietTo = number(root, "quiet_to_hour", d.quietToHour(), 0, 23, w);
        boolean catchUp = root.getBoolean(PATH + ".catch_up", d.catchUp());
        int catchUpMinutes = number(root, "catch_up_minutes", d.catchUpMinutes(), 1, 60, w);
        int catchUpLastDays = number(root, "catch_up_last_days", d.catchUpLastDays(), 0, 31, w);
        int stampede = number(root, "hub_stampede", d.stampede(), 0, 200, w);
        int stampedeMinutes = number(root, "hub_stampede_minutes", d.stampedeMinutes(), 1, 30, w);
        int stampedeRadius = number(root, "hub_stampede_radius", d.stampedeRadius(), 16, 200, w);
        return new Turkey(enabled, from, to, every, offset, run, welcome, scale, tokens, cap, feather, mini, random,
                quietFrom, quietTo, catchUp, catchUpMinutes, catchUpLastDays, stampede, stampedeMinutes,
                stampedeRadius);
    }

    /** A date key, quoted or bare; missing reads as {@code def}. Null, with one WARN, for anything else. */
    static LocalDate date(ConfigurationSection root, String key, LocalDate def, Consumer<String> warn) {
        Object raw = root.get(PATH + "." + key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof java.util.Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (raw instanceof String s) {
            try {
                return LocalDate.parse(s.trim());
            } catch (DateTimeParseException e) {
                // fall through to the warning
            }
        }
        warn.accept(PATH + "." + key + " '" + raw + "' is not a date - write it as \"" + def + "\" (with the "
                + "quotes). Catch the Turkey is off.");
        return null;
    }

    /** The turkey's size, squeezed into 1.0-3.0, with one WARN when it had to change or isn't a number. */
    private static double scale(ConfigurationSection root, double def, Consumer<String> warn) {
        Object raw = root.get(PATH + ".scale");
        double v = def;
        if (raw instanceof Number num) {
            v = num.doubleValue();
        } else if (raw != null) {
            try {
                v = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                warn.accept(PATH + ".scale '" + raw + "' is not a number; using " + def + ".");
                return def;
            }
        }
        if (Double.isNaN(v)) {
            warn.accept(PATH + ".scale is not a number; using " + def + ".");
            return def;
        }
        double clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, v));
        if (clamped != v) {
            warn.accept(PATH + ".scale " + v + " is outside " + MIN_SCALE + "-" + MAX_SCALE + "; using " + clamped + ".");
        }
        return clamped;
    }

    /** A whole number squeezed into {@code [min, max]}, with one WARN when it had to change. */
    private static int number(ConfigurationSection root, String key, int def, int min, int max, Consumer<String> warn) {
        Object raw = root.get(PATH + "." + key);
        int n = def;
        if (raw instanceof Number num) {
            n = num.intValue();
        } else if (raw != null) {
            try {
                n = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                warn.accept(PATH + "." + key + " '" + raw + "' is not a whole number; using " + def + ".");
                n = def;
            }
        }
        int clamped = Math.max(min, Math.min(max, n));
        if (clamped != n) {
            warn.accept(PATH + "." + key + " " + n + " is outside " + min + "-" + max + "; using " + clamped + ".");
        }
        return clamped;
    }
}
