package com.dierks.homecraft.games;

import java.util.UUID;

/**
 * One pending invite to a two-player game (Coin Flip, Connect Four or Tic-Tac-Toe against a
 * friend; spec R1.8, R3.8). Answered with {@code /hcm play accept|deny}; expiry, a quit or a
 * world change cancels it.
 *
 * @param id        unique for this server run
 * @param from      who asked
 * @param to        who is asked
 * @param gameId    the game
 * @param summary   what is on offer, as the invitee reads it ("Coin Flip for 10 tokens each")
 * @param sentAt    when it was sent (epoch ms)
 * @param expiresAt when it lapses (epoch ms)
 */
public record Invite(long id, UUID from, UUID to, String gameId, String summary, long sentAt, long expiresAt) {

    /** Whether it has lapsed at {@code now}. */
    public boolean expired(long now) {
        return now >= expiresAt;
    }
}
