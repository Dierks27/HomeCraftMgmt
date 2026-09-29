package com.dierks.homecraft.games.cup;

import java.util.Objects;
import java.util.UUID;

/**
 * What one entrant gets from a Cup's settlement: one line per entrant, including those who get
 * nothing, so every entrant is told how their Cup went.
 *
 * @param player who
 * @param kind   a prize, a refund, or nothing
 * @param tokens how many tokens they get (0 for {@link Kind#NONE})
 * @param place  their place by Cup time, 1-based, tied times sharing the first place of their group
 *               ("1224"); 0 in a refunded Cup or with no Cup time
 * @param tied   how many share that place (1 when alone); 0 when {@code place} is 0
 * @param bestMs their Cup time, or {@link CupEntry#NO_TIME}
 * @param paid   what they paid to enter
 */
public record CupPayout(UUID player, Kind kind, int tokens, int place, int tied, long bestMs, int paid) {

    /** What a line pays. */
    public enum Kind {
        /** A share of the pool. */
        PRIZE(CupSource.GAMES_CUP_PRIZE),
        /** The entry back. */
        REFUND(CupSource.GAMES_CUP_REFUND),
        /** Nothing: outside the paid places, or no Cup time. */
        NONE(null);

        private final CupSource source;

        Kind(CupSource source) {
            this.source = source;
        }

        /** The ledger source a payment of this kind is written under ({@code null} for {@link #NONE}). */
        public CupSource source() {
            return source;
        }
    }

    public CupPayout {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(kind, "kind");
    }

    /** Whether this line moves tokens. */
    public boolean pays() {
        return kind != Kind.NONE && tokens > 0;
    }
}
