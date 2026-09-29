package com.dierks.homecraft.games.arena.rules;

import java.util.List;
import java.util.UUID;

/**
 * What happened in the arena, for {@code FallingFloors} to show and do (EVENTS-DROPPER-SPEC
 * §B.3.3). {@link ArenaRound} decides; the game only acts on these: a teleport, a bar, a chat line,
 * the reset. Records, so a whole round can be logged and compared line for line.
 */
public sealed interface RoundEvent {

    /** Why a countdown stopped. */
    enum Why {
        /** Fewer than two players are left in the gallery. */
        TOO_FEW,
        /** A restart is close: no new round starts. */
        HOLD,
        /** The floors are being rebuilt (a new week, an admin reset). */
        RESET,
        /** The game was closed. */
        CLOSED
    }

    /** The 10 s bar before a multiplayer round has started. */
    record CountdownStarted(int ticks) implements RoundEvent {
    }

    /** The bar stopped; the round didn't start. */
    record CountdownCancelled(Why why) implements RoundEvent {
    }

    /** A round begins with these players (in order): teleports follow, two a tick. */
    record RoundStarting(int round, List<UUID> players, boolean solo) implements RoundEvent {
        public RoundStarting {
            players = List.copyOf(players);
        }
    }

    /** Move this player to spawn number {@code spawn} of the round's layout (the run's own teleport). */
    record TeleportTo(UUID player, int spawn) implements RoundEvent {
    }

    /** Everyone is on a spawn: hold them in place for {@code ticks} (the 3-2-1). */
    record HoldStarted(int ticks) implements RoundEvent {
    }

    /** Go: the floors start falling. */
    record Go(int round) implements RoundEvent {
    }

    /** The round is long enough: the edges start falling in, one ring every 2 s. */
    record SuddenDeath(int round) implements RoundEvent {
    }

    /**
     * A player is out: move them to the gallery and tell them their line.
     *
     * @param place the place they share with anyone out on the same tick
     * @param of    how many started
     * @param tied  someone else fell on the same tick, so the place is shared (a leaver never ties)
     * @param won   this 1st place is a win that counts: multiplayer, not left, and contested (the
     *              same as the result's {@link Standing#winner()}), so "You won" is never said
     *              when no win is recorded
     */
    record Out(UUID player, long survivedTicks, int place, int of, boolean tied, OutReason reason, boolean solo,
               boolean won) implements RoundEvent {
    }

    /** The round is over (results to the gallery, then the reset). */
    record Ended(RoundResult result) implements RoundEvent {
    }

    /**
     * Converge the box to the week's plan and verify it, then call
     * {@link ArenaRound#resetDone(int, boolean)} with this {@code ticket}. A newer ResetNeeded
     * replaces this one: cancel its job (its answer would be ignored anyway), so two jobs never
     * write the box at once.
     *
     * @param ticket  this request's number, the one its answer must carry
     * @param attempt which try this is (1-3): the third failed verify in a row closes the game
     */
    record ResetNeeded(int ticket, int attempt) implements RoundEvent {
    }

    /** The floors are whole and verified: the gallery is a lobby again. */
    record LobbyOpen() implements RoundEvent {
    }

    /** The game closed itself (or was closed): no rounds until it is reopened. */
    record Closed(String reason) implements RoundEvent {
    }
}
