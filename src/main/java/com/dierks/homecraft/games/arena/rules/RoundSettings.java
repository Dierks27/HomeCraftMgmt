package com.dierks.homecraft.games.arena.rules;

/**
 * The round knobs of Falling Floors (EVENTS-DROPPER-SPEC §B.3.3, §B.3.5), clamped so no config value
 * can make a round that never ends or a floor that vanishes before a child can react.
 *
 * <p>Only what the pure rules need lives here; {@code FallingFloorsSettings} (WP-F) reads the
 * {@code games.falling_floors} block and builds one of these. The fixed timings the spec gives in
 * words ("a 10 s countdown bar", "3 s held in place", "one ring every 2 s") are constants, not
 * config: they are part of how the game feels, and every player should get the same.
 *
 * <p>Every time is in server ticks (20 a second), because the rules run once a tick and a lagging
 * server should stretch a round for everyone alike rather than cut it short.
 *
 * @param fadeTicks    how long a stepped-on cell stays red before it is air ({@code fade_ticks}, 6-20)
 * @param minPlayers   how many ready players start the countdown at once ({@code min_players}, 2 up to
 *                     {@code maxPlayers})
 * @param maxPlayers   the most players in the arena at once ({@code max_players}, 2-16)
 * @param solo         whether a lone player may start a solo round ({@code solo})
 * @param roundSeconds when sudden death starts ({@code round_seconds}, 30-900)
 */
public record RoundSettings(int fadeTicks, int minPlayers, int maxPlayers, boolean solo, int roundSeconds) {

    public static final int DEFAULT_FADE_TICKS = 10;
    public static final int MIN_FADE_TICKS = 6;
    public static final int MAX_FADE_TICKS = 20;
    public static final int DEFAULT_MIN_PLAYERS = 2;
    public static final int DEFAULT_MAX_PLAYERS = 12;
    /**
     * The most a config may allow. At 16 players the worst tick is 16 x 4 cells x 2 writes = 128,
     * exactly the FloorWriter's per-tick cap, so a full arena never queues a write behind a fall.
     */
    public static final int MAX_PLAYERS_LIMIT = 16;
    public static final int DEFAULT_ROUND_SECONDS = 180;
    public static final int MIN_ROUND_SECONDS = 30;
    public static final int MAX_ROUND_SECONDS = 900;

    /** Ticks in a second. */
    public static final int TICKS_PER_SECOND = 20;
    /** Milliseconds in a tick: survival times are ticks x 50, so lag never shortens anyone's time. */
    public static final long MS_PER_TICK = 50L;
    /** The countdown bar before a multiplayer round: 10 s. */
    public static final int COUNTDOWN_TICKS = 10 * TICKS_PER_SECOND;
    /** A round starts on its own this long after the second player arrives: 20 s. */
    public static final int AUTO_START_TICKS = 20 * TICKS_PER_SECOND;
    /** Held in place on the spawns before Go: 3 s. */
    public static final int HOLD_TICKS = 3 * TICKS_PER_SECOND;
    /** Players teleported to the spawns each tick (a staggered start never spikes a tick). */
    public static final int TELEPORTS_PER_TICK = 2;
    /** In sudden death one more ring of every floor falls this often: 2 s. */
    public static final int RING_TICKS = 2 * TICKS_PER_SECOND;
    /** A reset that fails its verify this many times in a row closes the game. */
    public static final int RESET_ATTEMPTS = 3;

    public RoundSettings {
        fadeTicks = clamp(fadeTicks, MIN_FADE_TICKS, MAX_FADE_TICKS);
        maxPlayers = clamp(maxPlayers, 2, MAX_PLAYERS_LIMIT);
        minPlayers = clamp(minPlayers, 2, maxPlayers);
        roundSeconds = clamp(roundSeconds, MIN_ROUND_SECONDS, MAX_ROUND_SECONDS);
    }

    /** The shipped values (§B.3.5): fade 10, 2 to 12 players, solo on, sudden death after 180 s. */
    public static RoundSettings defaults() {
        return new RoundSettings(DEFAULT_FADE_TICKS, DEFAULT_MIN_PLAYERS, DEFAULT_MAX_PLAYERS, true,
                DEFAULT_ROUND_SECONDS);
    }

    /** The play tick at which sudden death starts. */
    public long roundTicks() {
        return (long) roundSeconds * TICKS_PER_SECOND;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
