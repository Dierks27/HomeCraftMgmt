package com.dierks.homecraft.arcade.looks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When a player's heads-ups may go as far as their join and our own titles go (SHOP-V2-SPEC §3.10): not in the 100
 * ticks after a join, and not while another title of ours is up (red team O20). Pure, so the join's race is pinned by
 * a test.
 *
 * <p>The join's wait runs on two clocks: a wall-clock stamp, for a minute tick that comes first, and the join's own
 * task 101 ticks on. A server catching up after a slow tick runs ticks back to back, so that task could run before
 * the stamp was due, and the heads-up then waited up to a minute for the next tick (red-team finding TT-1). The task
 * now ends its own join's wait ({@link #joinWaitOver}), since its delay is the wait. A later join's stamp stays, so
 * the task of a join before a quick rejoin never opens the new join's wait.
 */
final class NoticeGate {

    /** When each player's join heads-up may show (millis). */
    private final Map<UUID, Long> joinFrom = new HashMap<>();
    /** Until when a title of ours is on each player's screen (millis). */
    private final Map<UUID, Long> titleUntil = new HashMap<>();

    /** A join at {@code now}: its heads-ups wait {@code waitMs}. Returns the stamp, for {@link #joinWaitOver}. */
    long joined(UUID player, long now, long waitMs) {
        long from = now + waitMs;
        joinFrom.put(player, from);
        return from;
    }

    /** The join's own task ran: that join's wait is over, unless a later join set a new one. */
    void joinWaitOver(UUID player, long stamp) {
        joinFrom.remove(player, stamp);
    }

    /** An admin moved an end ({@code /hcm looks end}): what is due shows now, join or not. */
    void open(UUID player) {
        joinFrom.remove(player);
    }

    /** A title of ours is up until {@code until}. */
    void titleShown(UUID player, long until) {
        titleUntil.merge(player, until, Math::max);
    }

    /** Whether a heads-up may go at {@code now}: the join's wait is over and no title of ours is up. */
    boolean allows(UUID player, long now) {
        return now >= joinFrom.getOrDefault(player, 0L) && now >= titleUntil.getOrDefault(player, 0L);
    }

    /** The player left. */
    void drop(UUID player) {
        joinFrom.remove(player);
        titleUntil.remove(player);
    }
}
