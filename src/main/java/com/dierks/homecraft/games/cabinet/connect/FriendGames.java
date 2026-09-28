package com.dierks.homecraft.games.cabinet.connect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is playing whom in a two-player cabinet (Connect Four, Tic-Tac-Toe), and who may be asked
 * (spec §10b, R3.8). Pure — players are UUIDs — so the invite and start rules are tested without
 * a server; the game asks Bukkit the questions (online? allowed here? takes invites?) and passes
 * the answers in.
 *
 * <p>A player is in at most one game of a kind at a time and has at most one invite of their own
 * out. Whether the other player may be asked never depends on anything the inviter is told: the
 * picker just leaves them out.
 *
 * @param <M> the game's own match type
 */
public final class FriendGames<M> {

    private final Map<UUID, M> matches = new HashMap<>();
    private final Map<UUID, UUID> partner = new HashMap<>();
    /** Who each inviter is waiting on. */
    private final Map<UUID, UUID> waiting = new HashMap<>();

    /**
     * Whether {@code self} may invite {@code other} right now.
     *
     * @param takesInvites whether {@code other} takes this game's invites (their own setting)
     * @param hasPending   whether {@code other} already has an invite waiting
     * @param allowed      whether {@code other} may play this game here (permission, world, ...)
     */
    public boolean canInvite(UUID self, UUID other, boolean takesInvites, boolean hasPending, boolean allowed) {
        return self != null && other != null && !self.equals(other)
                && takesInvites && !hasPending && allowed
                && !busy(self) && !busy(other) && !waiting.containsKey(self);
    }

    /** {@code inviter} sent an invite to {@code invitee}: they wait for the answer. */
    public void invited(UUID inviter, UUID invitee) {
        waiting.put(inviter, invitee);
    }

    /** Who {@code inviter} is waiting on, or {@code null}. */
    public UUID waitingOn(UUID inviter) {
        return waiting.get(inviter);
    }

    /** The invite from {@code inviter} was answered, ran out or was called off. */
    public void answered(UUID inviter) {
        waiting.remove(inviter);
    }

    /**
     * The invite was accepted: start {@code match} between the two, re-checking at this moment.
     *
     * @param bothHere    both online (and allowed) right now
     * @return false (nothing started) if either is gone, already playing, or it's one player twice
     */
    public boolean start(UUID a, UUID b, M match, boolean bothHere) {
        waiting.remove(a);
        if (!bothHere || a == null || b == null || a.equals(b) || busy(a) || busy(b)) {
            return false;
        }
        matches.put(a, match);
        matches.put(b, match);
        partner.put(a, b);
        partner.put(b, a);
        return true;
    }

    /** Whether the player is in a friend game. */
    public boolean busy(UUID player) {
        return player != null && matches.containsKey(player);
    }

    /** The player's friend game, or {@code null}. */
    public M of(UUID player) {
        return player == null ? null : matches.get(player);
    }

    /** The player's opponent, or {@code null}. */
    public UUID opponent(UUID player) {
        return player == null ? null : partner.get(player);
    }

    /**
     * End the player's friend game for both players.
     *
     * @return the game that ended, or {@code null} if they weren't in one
     */
    public M end(UUID player) {
        M match = matches.remove(player);
        if (match == null) {
            return null;
        }
        UUID other = partner.remove(player);
        if (other != null) {
            partner.remove(other);
            matches.remove(other, match);
        }
        return match;
    }

    /** The player left: end their game and forget any invite of theirs. */
    public M quit(UUID player) {
        waiting.remove(player);
        return end(player);
    }

    /** Every live friend game, once each. */
    public Collection<M> all() {
        List<M> out = new ArrayList<>();
        for (M m : matches.values()) {
            if (!out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    /** Forget everything (the game stopped). */
    public void clear() {
        matches.clear();
        partner.clear();
        waiting.clear();
    }
}
