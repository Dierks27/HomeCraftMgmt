package com.dierks.homecraft.games;

/**
 * What sort of game a {@link Game} is. It decides which rules apply to it, not how it looks:
 * a {@link #CHANCE} game takes tokens in and runs every step of the {@link PlayGate} (limits,
 * Take a break, the click cooldown); the skill games ({@link #CABINET}, {@link #TRIAL},
 * {@link #GOLF}) are free, only pay capped rewards, and are the only ones that can be the
 * featured game of the day.
 */
public enum GameKind {
    /** Tokens in, tokens back: Ore Slots, Twenty-One, the Wheel, Higher or Lower, Coin Flip. */
    CHANCE,
    /** A menu game (Creeper Sweeper, Snake, ...). Free; scores, milestones, a daily challenge. */
    CABINET,
    /** Time trials in the Games world: parkour, elytra and boat courses. */
    TRIAL,
    /** Mini golf in the Games world. */
    GOLF;

    /** Whether this is a game of chance (tokens in, the full play gate, Take a break). */
    public boolean chance() {
        return this == CHANCE;
    }
}
