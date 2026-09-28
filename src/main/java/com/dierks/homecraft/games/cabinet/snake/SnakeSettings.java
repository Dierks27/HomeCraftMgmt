package com.dierks.homecraft.games.cabinet.snake;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Snake's settings: {@code games.snake} (spec §10b, R1.22, R3.14).
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
 * @param tickJava ticks between moves for Java players (never below 3)
 * @param tickBedrock ticks between moves for Bedrock players
 * @param milestones bronze, silver and gold: apples in one run
 */
public record SnakeSettings(boolean enabled, int milestoneReward, int dailyReward, int dailyCap, int tickJava,
                            int tickBedrock, List<Integer> milestones) {

    /** The leaves under {@code games.snake}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "milestone_reward", "daily_reward",
            "daily_cap", "tick_java", "tick_bedrock", "milestones");

    public SnakeSettings {
        milestones = List.copyOf(milestones);
    }

    /** The shipped settings. */
    public static SnakeSettings defaults() {
        return new SnakeSettings(
                true,
                1,
                1,
                2,
                6,
                10,
                List.of(10, 20, 30));
    }

    /** Read {@code games.snake} over {@code d}; never throws. */
    public static SnakeSettings parse(GamesConfig.Node n, SnakeSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int milestoneReward = n.whole("milestone_reward", d.milestoneReward(), 0, 100);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        int tickJava = n.whole("tick_java", d.tickJava(), 3, 40);
        int tickBedrock = n.whole("tick_bedrock", d.tickBedrock(), 3, 40);
        List<Integer> milestones = n.ladder("milestones", d.milestones(), 34, false);
        return new SnakeSettings(enabled, milestoneReward, dailyReward,
                dailyCap, tickJava, tickBedrock, milestones);
    }
}
