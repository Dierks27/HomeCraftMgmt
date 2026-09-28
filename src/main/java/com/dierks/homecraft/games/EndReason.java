package com.dierks.homecraft.games;

/**
 * Why a world session ended (spec R3.7). The session's state is restored the same way for every
 * reason; the reason only decides what the game says and whether it sends the player back.
 */
public enum EndReason {
    /** The player finished the course or the round. */
    FINISH,
    /** The kit's "Leave game" item (clicked twice). */
    QUIT_ITEM,
    /** {@code /hcm leave}. */
    COMMAND,
    /** Something killed the player anyway ({@code /kill}); the death is cancelled first (R2.1). */
    DEATH,
    /** A teleport the session did not make took the player away (R2.8). */
    TELEPORT,
    /** The player left the session's world by some other route (the backstop). */
    WORLD_CHANGE,
    /** Quit or kicked. */
    DISCONNECT,
    /** The server is stopping or the plugin is reloading. */
    STOP,
    /** The game was switched off or failed; its live sessions end at once (R2.6). */
    GAME_OFF,
    /** An admin ended it ({@code /hcm games saved ...}). */
    ADMIN
}
