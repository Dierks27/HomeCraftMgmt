package com.dierks.homecraft.games;

/**
 * How a multi-step game of chance finishes a round the player walked away from (spec §5.2,
 * R2.11, R3.1): Twenty-One stands, Higher or Lower takes the likelier side if no guess was made
 * and then cashes out.
 *
 * <p>It is pure and reads NO config: everything it needs was written into the round's
 * {@code data} when the round opened (the engine version, the stake and every payout parameter).
 * That is what lets the framework settle a round on quit, on shutdown, on the next join after a
 * crash, or for an offline player, even after the game was switched off or retuned — and settle
 * it exactly as it would have been settled the moment it was left. The result is never better
 * than the player's own best choice, so walking away is never a trick.
 */
@FunctionalInterface
public interface ExitSettler {

    /**
     * The tokens to pay back for an OPEN round left as it is.
     *
     * @param seed  the round's seed (every card is a pure function of the seed and its index)
     * @param stake the tokens put in so far (a Double included)
     * @param data  the round's data: every parameter and every action taken
     * @return the payout, 0 or more
     */
    int payoutOnExit(long seed, int stake, String data);
}
