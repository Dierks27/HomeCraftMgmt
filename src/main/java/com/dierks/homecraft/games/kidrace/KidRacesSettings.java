package com.dierks.homecraft.games.kidrace;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;

import java.util.List;

/**
 * The Arcade Room's kid race's settings: {@code games.kid_races}. Nothing here is ever put in: a race is free to
 * enter, and the only tokens are the small prize when your racer wins, under the game's daily cap (and the server's
 * skill cap, as every skill game's reward is).
 *
 * @param enabled       the game's own switch
 * @param prize         the tokens a win pays the picker of the racer that won (0 = none)
 * @param dailyCap      the most a player is paid in prizes a day
 * @param gatherSeconds how long the track waits for more entries after the first
 * @param raceSeconds   how long the winner takes to reach the finish
 */
public record KidRacesSettings(boolean enabled, int prize, int dailyCap, int gatherSeconds, int raceSeconds) {

    public static final String ID = "kid_races";
    /** The leaves under {@code games.kid_races}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "prize", "daily_cap", "gather_seconds",
            "race_seconds");

    /** The limits of the two clocks (the Parlor's), in seconds, and of the prize and the cap. */
    public static final int GATHER_MIN = MobRacesSettings.GATHER_MIN;
    public static final int GATHER_MAX = MobRacesSettings.GATHER_MAX;
    public static final int RACE_MIN = MobRacesSettings.RACE_MIN;
    public static final int RACE_MAX = MobRacesSettings.RACE_MAX;
    public static final int PRIZE_MAX = 20;
    public static final int CAP_MAX = 100;

    /** The shipped settings: on, 2 tokens a win, 10 a day, a 15-second wait and a 20-second race. */
    public static KidRacesSettings defaults() {
        return new KidRacesSettings(true, 2, 10, 15, 20);
    }

    /** Read {@code games.kid_races} over {@code d}; never throws. */
    public static KidRacesSettings parse(GamesConfig.Node n, KidRacesSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int prize = n.whole("prize", d.prize(), 0, PRIZE_MAX);
        int cap = n.whole("daily_cap", d.dailyCap(), 0, CAP_MAX);
        int gather = n.whole("gather_seconds", d.gatherSeconds(), GATHER_MIN, GATHER_MAX);
        int race = n.whole("race_seconds", d.raceSeconds(), RACE_MIN, RACE_MAX);
        return new KidRacesSettings(enabled, prize, cap, gather, race);
    }

    /** How long the winner takes to reach the finish, in ticks. */
    public int raceTicks() {
        return raceSeconds * 20;
    }

    /** How many laps a race is: the Parlor's pace. */
    public int laps() {
        return MobRacesSettings.laps(raceSeconds);
    }
}
