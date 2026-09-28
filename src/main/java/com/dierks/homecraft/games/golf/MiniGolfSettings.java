package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Mini Golf's settings: {@code games.golf} (spec §12, R1.22, R2.16).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param parReward tokens for finishing a course at par or better, once a day
 * @param holeInOneReward tokens for a hole-in-one in a finished round, once per hole a day
 * @param firstClear tokens for the first finish of a course (once ever)
 * @param dailyCap the most tokens golf pays a player a day
 * @param maxOverPar strokes over par before a hole is picked up
 */
public record MiniGolfSettings(boolean enabled, int parReward, int holeInOneReward, int firstClear,
                               int dailyCap, int maxOverPar) {

    /** The leaves under {@code games.golf}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "par_reward", "hole_in_one_reward",
            "first_clear", "daily_cap", "max_over_par");

    /** The shipped settings. */
    public static MiniGolfSettings defaults() {
        return new MiniGolfSettings(
                true,
                2,
                1,
                5,
                4,
                3);
    }

    /** Read {@code games.golf} over {@code d}; never throws. */
    public static MiniGolfSettings parse(GamesConfig.Node n, MiniGolfSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int parReward = n.whole("par_reward", d.parReward(), 0, 100);
        int holeInOneReward = n.whole("hole_in_one_reward", d.holeInOneReward(), 0, 100);
        int firstClear = n.whole("first_clear", d.firstClear(), 0, 1000);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        int maxOverPar = n.whole("max_over_par", d.maxOverPar(), 1, 10);
        return new MiniGolfSettings(enabled, parReward, holeInOneReward, firstClear, dailyCap, maxOverPar);
    }
}
