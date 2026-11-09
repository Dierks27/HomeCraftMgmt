package com.dierks.homecraft.games.golf;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which Bedrock players near a golf ball get its armour stand sent to them, pass by pass (0.38.7, the
 * Bedrock ball nobody could see). Paper sends a shown entity once: showing it again to a player who
 * already has it sends nothing. If their client lost that one spawn (it came while they were still
 * landing from a long teleport), the ball stayed gone for the whole round. So each Bedrock player in
 * range is shown the stand on their first pass, then sent it afresh (hidden and shown again) on the next
 * {@value #PASSES} - 1 passes, then left alone. One who goes out of range starts again when they come
 * back, and a new hole or a new stand starts everyone again ({@link #clear}).
 *
 * <p>Pure: the ball's view asks it each pass; tested without a server.
 */
final class BedrockSends {

    /** What to do for one player on one pass. */
    enum Send {
        /** Show the stand (their first pass). */
        SHOW,
        /** Hide it and show it again: a fresh spawn for a client that may have lost the first. */
        RESEND,
        /** They have had it enough times. */
        NONE
    }

    /** Passes in a row in which a player in range is sent the stand (the first a show, the rest afresh). */
    static final int PASSES = 5;

    private final Map<UUID, Integer> sent = new HashMap<>();

    /** This pass for {@code player}, who is in range now; it counts. */
    Send pass(UUID player) {
        int n = sent.getOrDefault(player, 0);
        if (n >= PASSES) {
            return Send.NONE;
        }
        sent.put(player, n + 1);
        return n == 0 ? Send.SHOW : Send.RESEND;
    }

    /** Only {@code near} are in range now: anyone else starts again when they come back. */
    void retain(Set<UUID> near) {
        sent.keySet().retainAll(near);
    }

    /** A new hole or a new stand: everyone starts again. */
    void clear() {
        sent.clear();
    }
}
