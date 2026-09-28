package com.dierks.homecraft.games.cabinet.connect;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Connect Four's settings: {@code games.connect_four} (spec §10b, R1.22, R3.14).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param dailyReward tokens for the day's first win against the Arcade on normal or hard
 * @param dailyCap the most tokens this game pays a player a day
 */
public record ConnectFourSettings(boolean enabled, int dailyReward, int dailyCap) {

    /** The leaves under {@code games.connect_four}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "daily_reward", "daily_cap");

    /** The shipped settings. */
    public static ConnectFourSettings defaults() {
        return new ConnectFourSettings(
                true,
                1,
                1);
    }

    /** Read {@code games.connect_four} over {@code d}; never throws. */
    public static ConnectFourSettings parse(GamesConfig.Node n, ConnectFourSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        return new ConnectFourSettings(enabled, dailyReward, dailyCap);
    }
}
