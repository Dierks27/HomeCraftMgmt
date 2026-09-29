package com.dierks.homecraft.games.cup;

import java.util.Objects;
import java.util.UUID;

/**
 * One token movement a Cup makes, as the ledger records it: an entry is {@code -paid} under
 * {@link CupSource#GAMES_CUP_ENTRY}; a prize or a refund is {@code +tokens} under its source.
 * {@link CupRules#ledger} lists them, and their sum is the proof the server keeps nothing: it is
 * exactly the top-up for a paid Cup and exactly 0 for a refunded one.
 *
 * @param player whose balance moves
 * @param source the ledger source
 * @param delta  the change to their balance (negative for an entry)
 */
public record CupLedgerRow(UUID player, CupSource source, int delta) {

    public CupLedgerRow {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
    }
}
