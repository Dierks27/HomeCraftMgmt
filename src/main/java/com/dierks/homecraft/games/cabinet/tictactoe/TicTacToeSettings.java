package com.dierks.homecraft.games.cabinet.tictactoe;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.cabinet.CabinetSettings;

import java.util.List;

/**
 * Tic-Tac-Toe's settings: {@code games.tic_tac_toe} (spec §10b, R1.22).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param dailyReward tokens for the day's first win on easy (or draw on hard) against the Arcade
 * @param dailyCap the most tokens this game pays a player a day
 */
public record TicTacToeSettings(boolean enabled, int dailyReward, int dailyCap) implements CabinetSettings {

    /** The leaves under {@code games.tic_tac_toe}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "daily_reward", "daily_cap");

    /** The shipped settings. */
    public static TicTacToeSettings defaults() {
        return new TicTacToeSettings(
                true,
                TokenBalance.DUEL_DAILY,
                TokenBalance.DUEL_DAILY_CAP);
    }

    /** Read {@code games.tic_tac_toe} over {@code d}; never throws. */
    public static TicTacToeSettings parse(GamesConfig.Node n, TicTacToeSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int dailyReward = n.whole("daily_reward", d.dailyReward(), 0, 100);
        int dailyCap = n.whole("daily_cap", d.dailyCap(), 0, 1000);
        return new TicTacToeSettings(enabled, dailyReward, dailyCap);
    }
}
