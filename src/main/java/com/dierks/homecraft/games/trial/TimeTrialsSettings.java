package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.config.GamesConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time Trials's settings: {@code games.trials} (spec §11, R1.22, R2.15).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param firstClear tokens for the first finish of a course, by its tier (paid once ever, not capped)
 * @param weeklyBestBonus tokens for setting the week's best time on a course
 * @param courseOfWeekBonus tokens for finishing the course of the week, once a day
 * @param dailyCap the most tokens the courses pay a player a day
 * @param fallDepth parkour: this far below the checkpoints sends you back to the last one
 * @param minSeconds a run faster than this never counts
 */
public record TimeTrialsSettings(boolean enabled, Map<String, Integer> firstClear, int weeklyBestBonus,
                                 int courseOfWeekBonus, int dailyCap, int fallDepth, int minSeconds) {

    /** The leaves under {@code games.trials}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "first_clear.easy", "first_clear.medium",
            "first_clear.hard", "first_clear.extreme", "weekly_best_bonus", "course_of_week_bonus",
            "daily_cap", "fall_depth", "min_seconds");

    public TimeTrialsSettings {
        firstClear = Collections.unmodifiableMap(new LinkedHashMap<>(firstClear));
    }

    /** The shipped settings. */
    public static TimeTrialsSettings defaults() {
        return new TimeTrialsSettings(
                true,
                map("easy", 5, "medium", 10, "hard", 20, "extreme", 40),
                5,
                2,
                4,
                6,
                5);
    }

    /** Read {@code games.trials} over {@code d}; never throws. */
    public static TimeTrialsSettings parse(GamesConfig.Node n, TimeTrialsSettings d) {
        boolean enabled = n.enabled(d.enabled());
        Map<String, Integer> firstClear = n.wholeMap("first_clear", d.firstClear(), 0, 1000);
        int weeklyBestBonus = n.whole("weekly_best_bonus", d.weeklyBestBonus(), 0, 100);
        int courseOfWeekBonus = n.whole("course_of_week_bonus", d.courseOfWeekBonus(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        int fallDepth = n.whole("fall_depth", d.fallDepth(), 1, 64);
        int minSeconds = n.whole("min_seconds", d.minSeconds(), 0, 3600);
        return new TimeTrialsSettings(enabled, firstClear, weeklyBestBonus,
                courseOfWeekBonus, dailyCap, fallDepth, minSeconds);
    }

    /** An ordered, unmodifiable map from key, value pairs. */
    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }
}
