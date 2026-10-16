package com.dierks.homecraft.games.cabinet.match;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.cabinet.CabinetSettings;

import java.util.List;

/**
 * Mini Match's settings: {@code games.mini_match} (spec §10b, R1.22).
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
 * @param milestones bronze, silver and gold: finish in at most this many flips (at least
 *                   {@link MatchEngine#PAIRS}, a perfect game)
 */
public record MiniMatchSettings(boolean enabled, int milestoneReward, int dailyReward, int dailyCap,
                                List<Integer> milestones) implements CabinetSettings {

    /** The leaves under {@code games.mini_match}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "milestone_reward", "daily_reward",
            "daily_cap", "milestones");

    public MiniMatchSettings {
        milestones = List.copyOf(milestones);
    }

    /** Bronze, silver and gold on the one {@code classic} board; the daily board has none. */
    @Override
    public List<Integer> milestonesFor(String board) {
        return Scores.CLASSIC.equals(board) ? milestones : List.of();
    }

    /** The shipped settings. */
    public static MiniMatchSettings defaults() {
        return new MiniMatchSettings(
                true,
                TokenBalance.CABINET_MILESTONE,
                TokenBalance.CABINET_DAILY,
                TokenBalance.CABINET_DAILY_CAP,
                List.of(30, 24, 20));
    }

    /** Read {@code games.mini_match} over {@code d}; never throws. */
    public static MiniMatchSettings parse(GamesConfig.Node n, MiniMatchSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int milestoneReward = n.whole("milestone_reward", d.milestoneReward(), 0, 100);
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        // A perfect game takes one flip per pair, so no milestone may ask for fewer.
        List<Integer> milestones = n.ladder("milestones", d.milestones(), MatchEngine.PAIRS, 200, true);
        return new MiniMatchSettings(enabled, milestoneReward, dailyReward, dailyCap, milestones);
    }
}
