package com.dierks.homecraft.config;

import com.dierks.homecraft.arcade.candy.CandyRules;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reads {@code arcade.events.feast}, the Family Feast's settings (0.38.6), the way {@link CandyConfig} reads the
 * candy shower's: it never throws, and gives one WARN naming the key for each problem.
 *
 * <p><b>Dates.</b> {@code from} and {@code to} are local dates ({@code clock.time_zone}), both included, quoted or
 * bare. A date that can't be read, or {@code from} after {@code to}, switches the Feast off with one WARN.
 *
 * <p><b>The edition</b> names everything the Feast keeps (its points, markers and reward), so the owner can try it
 * out under {@code "test"} and then put the real one back untouched. It must be 1-16 of {@code a-z 0-9 _}; anything
 * else (or none) falls back to the year of {@code from}, with one WARN when it was set but wrong.
 *
 * <p><b>Numbers.</b> Each is squeezed into its range, with one WARN when it had to change. A missing key reads as the
 * shipped value, so a config.yml without the section (or with the bundled one) parses to {@link Feast#DEFAULTS}
 * without a single WARN. Pinned by {@code FeastConfigTest}.
 */
public final class FeastConfig {

    /** Where the section lives in config.yml. */
    public static final String PATH = "arcade.events.feast";

    /** The shipped window: Thanksgiving week 2026, the same days the Turkey Mini 2026 can be earned. */
    public static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 11, 22);
    public static final LocalDate DEFAULT_TO = LocalDate.of(2026, 11, 29);
    /** A window longer than this still runs, with a WARN. */
    static final int LONG_WINDOW_DAYS = 62;
    /** What an edition may be written with. */
    static final Pattern EDITION = Pattern.compile("[a-z0-9_]{1,16}");

    /** The ranges: the goal, a player's points a day (0 = no limit), and the helpers' tokens. */
    static final int MIN_GOAL = 10;
    static final int MAX_GOAL = 2000;
    static final int MAX_DAILY = 2000;
    static final int MAX_TOKENS = 100;

    private FeastConfig() {
    }

    /**
     * The Family Feast's settings.
     *
     * @param enabled             the switch (off too when a date can't be read)
     * @param from                the first day, local
     * @param to                  the last day, local
     * @param edition             the name its points, markers and reward are kept under
     * @param goal                the food points that fill the tables
     * @param dailyPointsPerPlayer the most points one player adds in a local day, 0 for no limit
     * @param tokens              each helper's tokens when the tables are full, once
     * @param creativeHelpersPaid whether a helper who only ever gave in Creative is paid too
     */
    public record Feast(boolean enabled, LocalDate from, LocalDate to, String edition, int goal,
                        int dailyPointsPerPlayer, int tokens, boolean creativeHelpersPaid) {

        /** The shipped settings. */
        public static final Feast DEFAULTS = new Feast(true, DEFAULT_FROM, DEFAULT_TO,
                String.valueOf(DEFAULT_FROM.getYear()), 150, 40, 20, true);
        /** Until config.yml has been read: the shipped settings, switched off. */
        public static final Feast OFF = DEFAULTS.withEnabled(false);

        /** The same settings, switched on or off. */
        public Feast withEnabled(boolean on) {
            return new Feast(on, from, to, edition, goal, dailyPointsPerPlayer, tokens, creativeHelpersPaid);
        }

        /** Switched on, and {@code today} inside the window. The caller adds {@code arcade.enabled}. */
        public boolean open(LocalDate today) {
            return enabled && CandyRules.open(today, from, to);
        }
    }

    /** The section's settings from {@code root} (the whole config.yml), its WARNs to {@code warn} (may be null). */
    public static Feast parse(ConfigurationSection root, Consumer<String> warn) {
        Consumer<String> w = warn == null ? s -> {
        } : warn;
        if (root == null) {
            return Feast.DEFAULTS;
        }
        Feast d = Feast.DEFAULTS;
        boolean enabled = root.getBoolean(PATH + ".enabled", d.enabled());

        LocalDate from = date(root, "from", DEFAULT_FROM, w);
        LocalDate to = date(root, "to", DEFAULT_TO, w);
        if (from == null || to == null) {
            // the WARN is said; the shipped date stands in so the edition and the status still read
            enabled = false;
            from = from == null ? DEFAULT_FROM : from;
            to = to == null ? DEFAULT_TO : to;
        } else if (from.isAfter(to)) {
            w.accept(PATH + ".from (" + from + ") is after " + PATH + ".to (" + to + ") - the Family Feast is off.");
            enabled = false;
        } else if (CandyRules.days(from, to) > LONG_WINDOW_DAYS) {
            w.accept(PATH + " runs from " + from + " to " + to + " (" + CandyRules.days(from, to) + " days) - it "
                    + "still runs.");
        }

        String edition = edition(root, from, w);
        int goal = number(root, "goal", d.goal(), MIN_GOAL, MAX_GOAL, w);
        int daily = number(root, "daily_points_per_player", d.dailyPointsPerPlayer(), 0, MAX_DAILY, w);
        int tokens = number(root, "tokens", d.tokens(), 0, MAX_TOKENS, w);
        boolean creative = root.getBoolean(PATH + ".creative_helpers_paid", d.creativeHelpersPaid());
        return new Feast(enabled, from, to, edition, goal, daily, tokens, creative);
    }

    /** Whether {@code edition} may name a Feast: 1-16 of {@code a-z 0-9 _}. */
    public static boolean validEdition(String edition) {
        return edition != null && EDITION.matcher(edition).matches();
    }

    /** The edition as written, or the year of {@code from} (one WARN when it was set but can't be used). */
    static String edition(ConfigurationSection root, LocalDate from, Consumer<String> warn) {
        String fallback = String.valueOf(from.getYear());
        Object raw = root.get(PATH + ".edition");
        if (raw == null) {
            return fallback;
        }
        String e = String.valueOf(raw).trim();
        if (validEdition(e)) {
            return e;
        }
        warn.accept(PATH + ".edition '" + raw + "' can only use a-z, 0-9 and _ (1-16 of them); using \"" + fallback
                + "\".");
        return fallback;
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
                + "quotes). The Family Feast is off.");
        return null;
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
