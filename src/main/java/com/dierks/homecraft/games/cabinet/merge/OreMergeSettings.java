package com.dierks.homecraft.games.cabinet.merge;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Ore Merge's settings: {@code games.ore_merge} (spec §10b, R1.22).
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
 * @param milestones bronze, silver and gold: the biggest tile made (256 diamond, 512 netherite, 1024 nether star)
 */
public record OreMergeSettings(boolean enabled, int milestoneReward, int dailyReward, int dailyCap,
                               List<Integer> milestones) {

    /** The leaves under {@code games.ore_merge}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "milestone_reward", "daily_reward",
            "daily_cap", "milestones");

    public OreMergeSettings {
        milestones = List.copyOf(milestones);
    }

    /** The shipped settings. */
    public static OreMergeSettings defaults() {
        return new OreMergeSettings(
                true,
                1,
                1,
                2,
                List.of(256, 512, 1024));
    }

    /** Read {@code games.ore_merge} over {@code d}; never throws. */
    public static OreMergeSettings parse(GamesConfig.Node n, OreMergeSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int milestoneReward = n.whole("milestone_reward", d.milestoneReward(), 0, 100);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        List<Integer> milestones = n.ladder("milestones", d.milestones(), 2048, false);
        return new OreMergeSettings(enabled, milestoneReward, dailyReward, dailyCap, milestones);
    }
}
