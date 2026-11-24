package com.dierks.homecraft.games.chance.holdem;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;
import java.util.TreeSet;

/**
 * Texas Hold'em's settings: {@code games.holdem} (0.57.0). {@link #defaults()} is the single source of the shipped
 * values: the bundled config.yml block parses to exactly it without a WARN ({@code GamesConfigTest}); every key is read
 * over the defaults, a value out of range clamped with one WARN naming its full key. Never throws.
 *
 * <p>There is no {@code rtp}: the players play each other and the Arcade keeps nothing, so every chip put in comes back
 * to someone at the table.
 *
 * @param enabled       the game's own switch (it also needs {@code games.enabled})
 * @param buyIns        the tokens a player may sit down with at the tokens table (one chip is one token), smallest
 *                      first. The money table's buy-in is built from the Parlor's own chips, up to its biggest stake
 * @param smallBlind    the small blind, in chips, at every table
 * @param bigBlind      the big blind, in chips (never under the small one)
 * @param friendlyChips the free play chips a seat gets at the friendly table (and again, free, when they run out)
 * @param dailyLimit    buy-ins a player gets a day at the tables played for tokens or money (the friendly table has
 *                      none)
 * @param npcSeats      NPC players sit in at the friendly table until it has this many players (0: no NPCs)
 * @param awaySeconds   how long the table waits on a player who closed its screen before checking or folding for them
 * @param pauseSeconds  how long a hand's result stays on the table before the next hand is dealt
 */
public record HoldemSettings(boolean enabled, List<Integer> buyIns, int smallBlind, int bigBlind, int friendlyChips,
                             int dailyLimit, int npcSeats, int awaySeconds, int pauseSeconds) {

    public static final String ID = "holdem";
    /** The leaves under {@code games.holdem}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "buy_ins", "small_blind", "big_blind",
            "friendly_chips", "daily_limit", "npc_seats", "away_seconds", "pause_seconds");

    public static final int BLIND_MAX = 1000;
    public static final int BUY_IN_MAX = 100_000;
    public static final int AWAY_MIN = 3;
    public static final int AWAY_MAX = 120;
    public static final int PAUSE_MIN = 2;
    public static final int PAUSE_MAX = 30;

    public HoldemSettings {
        buyIns = List.copyOf(buyIns);
    }

    /**
     * As shipped: 20, 50 or 100 tokens at the tokens table, blinds of 1 and 2 chips, 200 play chips at the friendly
     * table, 10 buy-ins a day, NPC players filling the friendly table up to 4, 10 seconds for a player who walked
     * away, 5 seconds between hands.
     */
    public static HoldemSettings defaults() {
        return new HoldemSettings(true, List.of(20, 50, 100), 1, 2, 200, 10, 4, 10, 5);
    }

    /** Read {@code games.holdem} over {@code d}; never throws. */
    public static HoldemSettings parse(GamesConfig.Node n, HoldemSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> buyIns = List.copyOf(new TreeSet<>(n.intList("buy_ins", d.buyIns(), 1, BUY_IN_MAX)));
        int small = n.whole("small_blind", d.smallBlind(), 1, BLIND_MAX);
        int big = n.whole("big_blind", d.bigBlind(), 1, BLIND_MAX);
        if (big < small) {
            n.warn(n.key("big_blind") + " " + big + " is under the small blind (" + small + ") - using " + small);
            big = small;
        }
        int friendly = n.whole("friendly_chips", d.friendlyChips(), 10, BUY_IN_MAX);
        int daily = n.whole("daily_limit", d.dailyLimit(), 1, 1000);
        int npcs = n.whole("npc_seats", d.npcSeats(), 0, HoldemTable.MAX_SEATS);
        int away = n.whole("away_seconds", d.awaySeconds(), AWAY_MIN, AWAY_MAX);
        int pause = n.whole("pause_seconds", d.pauseSeconds(), PAUSE_MIN, PAUSE_MAX);
        return new HoldemSettings(enabled, buyIns, small, big, friendly, daily, npcs, away, pause);
    }

    /** "1/2": the blinds, as the screen says them. */
    public String blinds() {
        return smallBlind + "/" + bigBlind;
    }
}
