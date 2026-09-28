package com.dierks.homecraft.games.cabinet.sweeper;

import com.dierks.homecraft.config.GamesConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creeper Sweeper's settings: {@code games.creeper_sweeper} (spec §10b, R1.22).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param milestoneReward tokens for each board milestone (bronze, silver, gold), paid once ever
 * @param dailyReward tokens for meeting the daily challenge, once a day
 * @param dailyCap the most tokens this game pays a player a day
 * @param mines creepers on the board for each difficulty
 * @param milestones bronze, silver and gold clear times in seconds (at or under) for each difficulty
 */
public record CreeperSweeperSettings(boolean enabled, int milestoneReward, int dailyReward, int dailyCap,
                                     Map<String, Integer> mines, Map<String, List<Integer>> milestones) {

    /** The leaves under {@code games.creeper_sweeper}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "milestone_reward", "daily_reward",
            "daily_cap", "mines.easy", "mines.normal", "mines.hard", "milestones.easy", "milestones.normal",
            "milestones.hard");

    /** The difficulties, easiest first (each is its own board). */
    public static final List<String> LEVELS = List.of("easy", "normal", "hard");

    public CreeperSweeperSettings {
        mines = Collections.unmodifiableMap(new LinkedHashMap<>(mines));
        Map<String, List<Integer>> copy = new LinkedHashMap<>();
        milestones.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        milestones = Collections.unmodifiableMap(copy);
    }

    /** The shipped settings. */
    public static CreeperSweeperSettings defaults() {
        return new CreeperSweeperSettings(
                true,
                1,
                1,
                2,
                map("easy", 6, "normal", 8, "hard", 10),
                ladder(List.of(180, 90, 45), List.of(240, 120, 75), List.of(300, 180, 120)));
    }

    /** Read {@code games.creeper_sweeper} over {@code d}; never throws. */
    public static CreeperSweeperSettings parse(GamesConfig.Node n, CreeperSweeperSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int milestoneReward = n.whole("milestone_reward", d.milestoneReward(), 0, 100);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        Map<String, Integer> mines = n.wholeMap("mines", d.mines(), 1, 30);
        Map<String, List<Integer>> milestones = ladders(n, d);
        return new CreeperSweeperSettings(enabled, milestoneReward, dailyReward, dailyCap, mines, milestones);
    }

    /** An ordered, unmodifiable map from key, value pairs. */
    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    /** The three ladders in difficulty order. */
    private static Map<String, List<Integer>> ladder(List<Integer> easy, List<Integer> normal, List<Integer> hard) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        out.put("easy", easy);
        out.put("normal", normal);
        out.put("hard", hard);
        return out;
    }

    /** Each difficulty's three milestone times. */
    private static Map<String, List<Integer>> ladders(GamesConfig.Node n, CreeperSweeperSettings d) {
        GamesConfig.Node m = n.child("milestones");
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (String level : LEVELS) {
            out.put(level, m.ladder(level, d.milestones().get(level), 3600, true));
        }
        return out;
    }
}
