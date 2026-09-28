package com.dierks.homecraft.games.cabinet.simon;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Simon Says's settings: {@code games.simon_says} (spec §10b, R1.22).
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
 * @param milestones bronze, silver and gold: the longest sequence repeated
 */
public record SimonSaysSettings(boolean enabled, int milestoneReward, int dailyReward, int dailyCap,
                                List<Integer> milestones) {

    /** The leaves under {@code games.simon_says}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "milestone_reward", "daily_reward",
            "daily_cap", "milestones");

    public SimonSaysSettings {
        milestones = List.copyOf(milestones);
    }

    /** The shipped settings. */
    public static SimonSaysSettings defaults() {
        return new SimonSaysSettings(
                true,
                1,
                1,
                2,
                List.of(5, 10, 15));
    }

    /** Read {@code games.simon_says} over {@code d}; never throws. */
    public static SimonSaysSettings parse(GamesConfig.Node n, SimonSaysSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int milestoneReward = n.whole("milestone_reward", d.milestoneReward(), 0, 100);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        List<Integer> milestones = n.ladder("milestones", d.milestones(), 100, false);
        return new SimonSaysSettings(enabled, milestoneReward, dailyReward, dailyCap, milestones);
    }
}
