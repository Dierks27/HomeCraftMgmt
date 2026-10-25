package com.dierks.homecraft.config;

import com.dierks.homecraft.arcade.candy.CandyRules;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/**
 * Reads {@code arcade.events.candy}, the Candy Apple shower's settings (HALLOWEEN-SPEC §2.2, D10, D18).
 *
 * <p><b>Dates.</b> {@code from} and {@code to} are local dates ({@code clock.time_zone}), both included. A
 * quoted {@code "2026-10-31"} is read with {@link LocalDate#parse}; a bare {@code 2026-10-31} is turned into a
 * {@link java.util.Date} at UTC midnight by the YAML loader, and is read back as that UTC date. Anything else,
 * or {@code from} after {@code to}, turns the shower off with one WARN naming the key. A window that crosses a
 * year, or is longer than {@value #LONG_WINDOW_DAYS} days, still runs, with one WARN: the event's year (the
 * counter, the item names, the bag) comes from {@code from}.
 *
 * <p><b>Numbers.</b> Each is squeezed into its range, with one WARN naming the key when it had to change. A
 * missing key reads as the shipped value, so the bundled section parses to {@link Candy#DEFAULTS} without a
 * single WARN. {@code arcade} has no unknown-key check, so there is none here either.
 *
 * <p>Never throws, and the only Bukkit type is {@link ConfigurationSection}. Pinned by {@code CandyConfigTest}.
 */
public final class CandyConfig {

    /** Where the section lives in config.yml. */
    public static final String PATH = "arcade.events.candy";

    /** The shipped window: October 2026. */
    public static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 10, 1);
    public static final LocalDate DEFAULT_TO = LocalDate.of(2026, 10, 31);
    /** A window longer than this still runs, with a WARN. */
    static final int LONG_WINDOW_DAYS = 62;

    /** The interval's range, in minutes; 0 for {@code last_day_every_minutes} means "as every_minutes". */
    static final int MIN_EVERY = 10;
    static final int MAX_EVERY = 120;

    private CandyConfig() {
    }

    /**
     * The Candy Apple shower's settings.
     *
     * @param enabled              the shower's switch (off too when a date can't be read)
     * @param from                 the first day, local; the event's year is its year
     * @param to                   the last day, local, on which showers come every {@code lastDayEveryMinutes}
     * @param everyMinutes         a shower every this many minutes, counted from local midnight plus the offset
     * @param offsetMinutes        the day's first shower, in minutes after midnight (below {@code everyMinutes})
     * @param lastDayEveryMinutes  the last day's interval (0 = as {@code everyMinutes})
     * @param applesPerShower      candy apples per player per shower
     * @param goldenPercent        the chance in 100 that an apple is a Golden Candy Apple
     * @param goldenTokens         tokens for each Golden Candy Apple picked up
     * @param dailyTokenCap        the most tokens a player gets from candy in a local day
     * @param goal                 candy apples to collect for the Trick-or-Treat Bag
     * @param adminsSeeAll         admins also see everyone's candy (only its owner can pick it up)
     */
    public record Candy(boolean enabled, LocalDate from, LocalDate to, int everyMinutes, int offsetMinutes,
                        int lastDayEveryMinutes, int applesPerShower, int goldenPercent, int goldenTokens,
                        int dailyTokenCap, int goal, boolean adminsSeeAll) {

        /**
         * The shipped settings. 0.38.1 (config revision 23) made the shower gentler: a shower an hour, every half hour
         * on the last day, 3 candy apples each (0.37.1-0.38.0: every 20 minutes, every 10 on the last day, 6 each).
         */
        public static final Candy DEFAULTS = new Candy(true, DEFAULT_FROM, DEFAULT_TO, 60, 10, 30, 3, 4, 5, 10, 31,
                false);
        /** Until config.yml has been read: the shipped settings, switched off. */
        public static final Candy OFF = DEFAULTS.withEnabled(false);

        /** The same settings, switched on or off. */
        public Candy withEnabled(boolean on) {
            return new Candy(on, from, to, everyMinutes, offsetMinutes, lastDayEveryMinutes, applesPerShower,
                    goldenPercent, goldenTokens, dailyTokenCap, goal, adminsSeeAll);
        }

        /** The event's year, from {@code from}: the counter's key, the items' names and the bag. */
        public int year() {
            return from == null ? DEFAULT_FROM.getYear() : from.getYear();
        }

        /** Switched on, and {@code today} inside the window. The caller adds {@code arcade.enabled}. */
        public boolean open(LocalDate today) {
            return enabled && CandyRules.open(today, from, to);
        }

        /** Whether {@code today} is the last day, when showers come every {@code lastDayEveryMinutes}. */
        public boolean lastDay(LocalDate today) {
            return today != null && today.equals(to);
        }
    }

    /**
     * The section's settings from {@code root} (the whole config.yml), its WARNs to {@code warn} (may be
     * {@code null}).
     */
    public static Candy parse(ConfigurationSection root, Consumer<String> warn) {
        Consumer<String> w = warn == null ? s -> {
        } : warn;
        if (root == null) {
            return Candy.DEFAULTS;
        }
        Candy d = Candy.DEFAULTS;
        boolean enabled = root.getBoolean(PATH + ".enabled", d.enabled());

        LocalDate from = date(root, "from", w);
        LocalDate to = date(root, "to", w);
        if (from == null || to == null) {
            // the WARN is said; the shipped date stands in so the year and the status still read
            enabled = false;
            from = from == null ? DEFAULT_FROM : from;
            to = to == null ? DEFAULT_TO : to;
        } else if (from.isAfter(to)) {
            w.accept(PATH + ".from (" + from + ") is after " + PATH + ".to (" + to
                    + ") - the Candy Apple shower is off.");
            enabled = false;
        } else if (from.getYear() != to.getYear() || CandyRules.days(from, to) > LONG_WINDOW_DAYS) {
            w.accept(PATH + " runs from " + from + " to " + to + " (" + CandyRules.days(from, to) + " days"
                    + (from.getYear() != to.getYear() ? ", into another year" : "") + ") - it still runs, and its "
                    + "year is " + from.getYear() + " (from " + PATH + ".from).");
        }

        int every = number(root, "every_minutes", d.everyMinutes(), MIN_EVERY, MAX_EVERY, w);
        int offset = number(root, "offset_minutes", d.offsetMinutes(), 0, every - 1, w);
        int lastDayEvery = lastDayEvery(root, d.lastDayEveryMinutes(), w);
        int apples = number(root, "apples_per_shower", d.applesPerShower(), 1, 12, w);
        int goldenPercent = number(root, "golden_percent", d.goldenPercent(), 0, 100, w);
        int goldenTokens = number(root, "golden_tokens", d.goldenTokens(), 0, 20, w);
        int cap = number(root, "daily_token_cap", d.dailyTokenCap(), 0, 50, w);
        int goal = number(root, "goal", d.goal(), 1, 200, w);
        boolean adminsSeeAll = root.getBoolean(PATH + ".admins_see_all", d.adminsSeeAll());
        return new Candy(enabled, from, to, every, offset, lastDayEvery, apples, goldenPercent, goldenTokens, cap,
                goal, adminsSeeAll);
    }

    /**
     * A date key: a quoted ISO date, or a bare YAML date (a {@link java.util.Date} at UTC midnight); missing reads
     * as the shipped date. Null, with one WARN, for anything else.
     */
    static LocalDate date(ConfigurationSection root, String key, Consumer<String> warn) {
        Object raw = root.get(PATH + "." + key);
        if (raw == null) {
            return "from".equals(key) ? DEFAULT_FROM : DEFAULT_TO;
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
        warn.accept(PATH + "." + key + " '" + raw + "' is not a date - write it as \"2026-10-31\" (with the "
                + "quotes). The Candy Apple shower is off.");
        return null;
    }

    /** A whole number squeezed into {@code [min, max]}, with one WARN when it had to change. */
    private static int number(ConfigurationSection root, String key, int def, int min, int max, Consumer<String> warn) {
        Integer v = integer(root, key, def, warn);
        int n = v == null ? def : v;
        int clamped = Math.max(min, Math.min(max, n));
        if (clamped != n) {
            warn.accept(PATH + "." + key + " " + n + " is outside " + min + "-" + max + "; using " + clamped + ".");
        }
        return clamped;
    }

    /** {@code last_day_every_minutes}: 0 (as every_minutes), or 10-120. */
    private static int lastDayEvery(ConfigurationSection root, int def, Consumer<String> warn) {
        Integer v = integer(root, "last_day_every_minutes", def, warn);
        int n = v == null ? def : v;
        int clamped = n <= 0 ? 0 : Math.max(MIN_EVERY, Math.min(MAX_EVERY, n));
        if (clamped != n) {
            warn.accept(PATH + ".last_day_every_minutes " + n + " is not 0 or " + MIN_EVERY + "-" + MAX_EVERY
                    + "; using " + clamped + ".");
        }
        return clamped;
    }

    /** The key as a whole number; missing reads as {@code def}; null, with one WARN, when it isn't a number. */
    private static Integer integer(ConfigurationSection root, String key, int def, Consumer<String> warn) {
        Object raw = root.get(PATH + "." + key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            warn.accept(PATH + "." + key + " '" + raw + "' is not a whole number; using " + def + ".");
            return null;
        }
    }
}
