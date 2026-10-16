package com.dierks.homecraft.games.cabinet.whack;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.cabinet.CabinetSettings;

import java.util.List;

/**
 * Whack-a-Zombie's settings: {@code games.whack_a_zombie} (spec §10b, R1.22).
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
 * @param seconds how long a round lasts
 * @param milestones bronze, silver and gold: points in one round
 */
public record WhackAZombieSettings(boolean enabled, int milestoneReward, int dailyReward, int dailyCap,
                                   int seconds, List<Integer> milestones) implements CabinetSettings {

    /** The leaves under {@code games.whack_a_zombie}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "milestone_reward", "daily_reward",
            "daily_cap", "seconds", "milestones");

    public WhackAZombieSettings {
        milestones = List.copyOf(milestones);
    }

    /** Bronze, silver and gold on the one {@code classic} board; the daily board has none. */
    @Override
    public List<Integer> milestonesFor(String board) {
        return Scores.CLASSIC.equals(board) ? milestones : List.of();
    }

    /** The shipped settings. */
    public static WhackAZombieSettings defaults() {
        return new WhackAZombieSettings(
                true,
                TokenBalance.CABINET_MILESTONE,
                TokenBalance.CABINET_DAILY,
                TokenBalance.CABINET_DAILY_CAP,
                30,
                List.of(15, 25, 35));
    }

    /** Read {@code games.whack_a_zombie} over {@code d}; never throws. */
    public static WhackAZombieSettings parse(GamesConfig.Node n, WhackAZombieSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int milestoneReward = n.whole("milestone_reward", d.milestoneReward(), 0, 100);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        int seconds = n.whole("seconds", d.seconds(), 10, 120);
        List<Integer> milestones = n.ladder("milestones", d.milestones(), 1000, false);
        return new WhackAZombieSettings(enabled, milestoneReward, dailyReward, dailyCap, seconds, milestones);
    }
}
