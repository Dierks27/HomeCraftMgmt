package com.dierks.homecraft.games;

/**
 * What one submitted score did to its board (spec §6.2): was it a personal best, what was the
 * old one, did it take the server record, and where the player now stands. Enough for the
 * finish line ("New best! 1:02.3 (was 1:05.0) — 2nd on the board") without a second query.
 *
 * @param personalBest whether it beat the player's previous best (or was their first)
 * @param previous     the player's previous best, or {@code null} for a first score
 * @param record       whether it is now the board's record (a tie keeps the earlier holder)
 * @param rank         the player's place on the board after this score, 1-based (0 = not ranked)
 */
public record ScoreResult(boolean personalBest, Long previous, boolean record, int rank) {

    /** Nothing was recorded. */
    public static final ScoreResult NONE = new ScoreResult(false, null, false, 0);
}
