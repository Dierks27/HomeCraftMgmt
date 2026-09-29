package com.dierks.homecraft.games.cup;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's entry in one Weekly Cup: what they paid, when, and their Cup time so far (§D2: "their
 * best counted time that week is their Cup time").
 *
 * <p><b>Why the entry keeps what it paid.</b> {@code games.cup.entry} can change mid-week. A refund
 * gives back exactly what this player paid, and the pool is the sum of what was really paid, so the
 * server never keeps or invents a token when the setting moves.
 *
 * <p><b>Why the time a best was set.</b> Ties share their places' amounts, and the remainder goes to
 * the earliest time (§D2), the same rule the boards use ("a tie ranks whoever got there first"). Two
 * equal times can only be told apart by when each was set, so {@link #withRun} keeps the earlier one
 * on a tie.
 *
 * @param player    who entered
 * @param paid      the tokens they paid to enter (0 or more)
 * @param enteredAt when they entered (epoch ms)
 * @param bestMs    their Cup time in ms, or {@link #NO_TIME} before their first counted finish
 * @param bestAt    when that time was set (epoch ms), 0 with no time
 */
public record CupEntry(UUID player, int paid, long enteredAt, long bestMs, long bestAt) {

    /** No counted finish yet (a database NULL reads as this). */
    public static final long NO_TIME = -1L;

    public CupEntry {
        Objects.requireNonNull(player, "player");
        if (paid < 0) {
            throw new IllegalArgumentException("an entry pays 0 or more tokens: " + paid);
        }
        if (bestMs <= 0) {
            bestMs = NO_TIME;
            bestAt = 0L;
        }
    }

    /** A new entry: paid {@code paid} at {@code at}, no time yet. */
    public static CupEntry entered(UUID player, int paid, long at) {
        return new CupEntry(player, paid, at, NO_TIME, 0L);
    }

    /** Whether this entrant has set a Cup time. */
    public boolean hasTime() {
        return bestMs > 0;
    }

    /**
     * This entry after a counted run of {@code ms} that finished at {@code at}. Only a strictly
     * faster time replaces the Cup time, so a tie keeps the earlier one. A run that started
     * ({@code at - ms}) before the entry doesn't count, even when it finishes after it: a player can't
     * look at their week first and enter afterwards with a time already in hand, nor start a free run,
     * watch their splits, and pay the entry only once it is on record pace. Entering during the
     * warm-up or the 3-2-1 is fine: the timed run starts after it.
     *
     * <p>Warm-up laps, voided runs and runs for another week never reach here (the caller only passes
     * counted runs of this Cup's week, {@link CupRules#runWeeks}).
     */
    public CupEntry withRun(long ms, long at) {
        if (ms <= 0 || at - ms < enteredAt) {
            return this;
        }
        if (hasTime() && ms >= bestMs) {
            return this;
        }
        return new CupEntry(player, paid, enteredAt, ms, at);
    }
}
