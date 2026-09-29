package com.dierks.homecraft.games.arena.rules;

/** Why a player's round ended before the round did. */
public enum OutReason {
    /** Fell below the bottom floor ({@code out_y}), or into the void. */
    FELL,
    /** Pressed "Leave game", typed {@code /hcm leave}, or disconnected: out, and nothing is earned. */
    LEFT
}
