package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.TokenBalance;

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
 * @param warmupSeconds the warm-up a run may start with (owner decision D3): "Warm up (3:00)" or
 *                      "Go straight to the timed run"; 0 turns warm-ups off (and the Dropper's
 *                      practice drop with them)
 * @param partyMax the most racers in a party race (D4, "Race with friends"), 2 to
 *                 {@link PartyLobby.Kind#limit()}
 * @param riderRunsCount WP-CH ride along: whether a boat run with a rider in the back counts as normal
 *                       (a passenger doesn't change a boat's speed); false makes it just for fun, and
 *                       Race Night refuses riders
 */
public record TimeTrialsSettings(boolean enabled, Map<String, Integer> firstClear, int weeklyBestBonus,
                                 int courseOfWeekBonus, int dailyCap, int fallDepth, int minSeconds,
                                 int warmupSeconds, int partyMax, boolean riderRunsCount) {

    /** The leaves under {@code games.trials}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "first_clear.easy", "first_clear.medium",
            "first_clear.hard", "first_clear.extreme", "weekly_best_bonus", "course_of_week_bonus",
            "daily_cap", "fall_depth", "min_seconds", "warmup_seconds", "party_max", "rider_runs_count");

    /** The shipped warm-up: 3 minutes. */
    public static final int WARMUP_SECONDS = 180;
    /** The shipped party size: 8 racers. */
    public static final int PARTY_MAX = 8;

    public TimeTrialsSettings {
        firstClear = Collections.unmodifiableMap(new LinkedHashMap<>(firstClear));
        warmupSeconds = Math.max(0, Math.min(600, warmupSeconds));
        partyMax = Math.max(PartyLobby.MIN_PLAYERS, Math.min(PartyLobby.Kind.RACE.limit(), partyMax));
    }

    /** The settings before ride along: runs with a rider count. */
    public TimeTrialsSettings(boolean enabled, Map<String, Integer> firstClear, int weeklyBestBonus,
                              int courseOfWeekBonus, int dailyCap, int fallDepth, int minSeconds,
                              int warmupSeconds, int partyMax) {
        this(enabled, firstClear, weeklyBestBonus, courseOfWeekBonus, dailyCap, fallDepth, minSeconds, warmupSeconds,
                partyMax, true);
    }

    /** The settings before warm-ups and party races: those at their shipped values. */
    public TimeTrialsSettings(boolean enabled, Map<String, Integer> firstClear, int weeklyBestBonus,
                              int courseOfWeekBonus, int dailyCap, int fallDepth, int minSeconds) {
        this(enabled, firstClear, weeklyBestBonus, courseOfWeekBonus, dailyCap, fallDepth, minSeconds,
                WARMUP_SECONDS, PARTY_MAX);
    }

    /** The shipped settings. */
    public static TimeTrialsSettings defaults() {
        return new TimeTrialsSettings(
                true,
                map("easy", TokenBalance.TRIALS_FIRST_CLEAR_EASY,
                        "medium", TokenBalance.TRIALS_FIRST_CLEAR_MEDIUM,
                        "hard", TokenBalance.TRIALS_FIRST_CLEAR_HARD,
                        "extreme", TokenBalance.TRIALS_FIRST_CLEAR_EXTREME),
                TokenBalance.TRIALS_WEEKLY_BEST,
                TokenBalance.TRIALS_COURSE_OF_WEEK,
                TokenBalance.TRIALS_DAILY_CAP,
                6,
                5,
                WARMUP_SECONDS,
                PARTY_MAX,
                true);
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
        int warmupSeconds = n.whole("warmup_seconds", d.warmupSeconds(), 0, 600);
        int partyMax = n.whole("party_max", d.partyMax(), PartyLobby.MIN_PLAYERS, PartyLobby.Kind.RACE.limit());
        boolean riderRunsCount = n.bool("rider_runs_count", d.riderRunsCount());
        return new TimeTrialsSettings(enabled, firstClear, weeklyBestBonus,
                courseOfWeekBonus, dailyCap, fallDepth, minSeconds, warmupSeconds, partyMax, riderRunsCount);
    }

    /** Whether a run may start with a warm-up (and a dropper with a practice drop): {@code warmup_seconds > 0}. */
    public boolean warmupsOn() {
        return warmupSeconds > 0;
    }

    /** The first-clear reward for a tier ({@code easy}...), 0 for an unknown one. */
    public int firstClearFor(String tier) {
        Integer v = tier == null ? null : firstClear.get(tier);
        return v == null ? 0 : Math.max(0, v);
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
