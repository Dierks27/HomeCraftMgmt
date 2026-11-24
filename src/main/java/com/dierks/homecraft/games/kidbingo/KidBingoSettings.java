package com.dierks.homecraft.games.kidbingo;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * Picture Bingo's settings (0.57.0): {@code games.kid_bingo}. Nothing here is ever put in: a round is free, and the
 * only tokens are the small prize when your card gets three in a row, under the game's daily cap (and the server's
 * skill cap, as every skill game's reward is).
 *
 * @param enabled       the game's own switch
 * @param prize         the tokens three in a row pays (0 = none)
 * @param dailyCap      the most a player is paid in prizes a day
 * @param gatherSeconds how long the round waits for more players after the first
 * @param callSeconds   seconds between pictures
 */
public record KidBingoSettings(boolean enabled, int prize, int dailyCap, int gatherSeconds, int callSeconds) {

    public static final String ID = "kid_bingo";
    /** The leaves under {@code games.kid_bingo}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "prize", "daily_cap", "gather_seconds",
            "call_seconds");

    public static final int PRIZE_MAX = 20;
    public static final int CAP_MAX = 100;
    public static final int GATHER_MIN = 5;
    public static final int GATHER_MAX = 120;
    public static final int CALL_MIN = 2;
    public static final int CALL_MAX = 10;

    /** The shipped settings: on, 2 tokens a win, 10 a day, a 10-second wait and a picture every 3 seconds. */
    public static KidBingoSettings defaults() {
        return new KidBingoSettings(true, 2, 10, 10, 3);
    }

    /** Read {@code games.kid_bingo} over {@code d}; never throws. */
    public static KidBingoSettings parse(GamesConfig.Node n, KidBingoSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int prize = n.whole("prize", d.prize(), 0, PRIZE_MAX);
        int cap = n.whole("daily_cap", d.dailyCap(), 0, CAP_MAX);
        int gather = n.whole("gather_seconds", d.gatherSeconds(), GATHER_MIN, GATHER_MAX);
        int call = n.whole("call_seconds", d.callSeconds(), CALL_MIN, CALL_MAX);
        return new KidBingoSettings(enabled, prize, cap, gather, call);
    }

    /** Seconds between pictures, in milliseconds. */
    public long callMs() {
        return callSeconds * 1000L;
    }
}
