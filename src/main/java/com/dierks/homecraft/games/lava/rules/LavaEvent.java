package com.dierks.homecraft.games.lava.rules;

import java.util.List;
import java.util.UUID;

/**
 * What a round of The Floor Is Lava tells the room, in the order it happened ({@link LavaRound}). The rules know
 * nothing of blocks or players' feet: the room turns each of these into lava, stars, furniture moving, sounds and
 * teleports.
 */
public sealed interface LavaEvent {

    /** The round is on (the gathering is over, or Little began): these players are in it. */
    record Started(LavaMode mode, List<UUID> players) implements LavaEvent {
        public Started {
            players = List.copyOf(players);
        }
    }

    /**
     * A phase began.
     *
     * @param round the round it belongs to, from 1
     * @param level the lava's height from now on, in blocks over the floor (0: none)
     * @param ticks how long the phase lasts
     */
    record PhaseChanged(LavaRound.Phase phase, int round, int level, int ticks) implements LavaEvent {
    }

    /**
     * 0.57.0: the furniture is about to move (the floor is safe): the pieces that will move glow and rumble for
     * {@code ticks}, then {@link Shift}.
     *
     * @param seed the new layout's seed
     */
    record ShiftComing(int round, long seed, int ticks) implements LavaEvent {
    }

    /** 0.57.0: the furniture moves now, to {@link ShiftComing}'s layout. */
    record Shift(int round, long seed) implements LavaEvent {
    }

    /**
     * 0.57.0: this round's surprise, told as the stars come out (its warning is the room's: a wobble, a glow on one
     * side, a quicker rumble, a sparkle, a bouncy cushion).
     *
     * @param pick a number the room uses to choose where (which piece, which side), the same for the same round
     */
    record SurpriseComing(LavaRound.Surprise surprise, int round, int pick) implements LavaEvent {
    }

    /**
     * These stars are out now, to be put somewhere new.
     *
     * @param lava 0.57.0: stars that came out during the lava, up on the furniture
     */
    record StarsOut(int set, List<Integer> ids, boolean lava) implements LavaEvent {
        public StarsOut {
            ids = List.copyOf(ids);
        }
    }

    /** Every star is put away (the lava came, or the round ended). */
    record StarsIn() implements LavaEvent {
    }

    /**
     * A star was collected.
     *
     * @param have what is collected now (Little: of {@code goal}; Group: the shared count; Challenge: the player's
     *             own this round)
     * @param goal Little's full count; Group: this round's goal; Challenge: the stars each needs this round
     */
    record StarTaken(UUID player, int id, int have, int goal) implements LavaEvent {
    }

    /**
     * A player touched the lava and is put back on the nearest safe spot (Little, Group; a Challenge watcher too).
     *
     * @param costStar the touch cost the group a star
     * @param have     the count after it
     */
    record PutBack(UUID player, boolean costStar, int have) implements LavaEvent {
    }

    /**
     * Challenge: a player touched the lava (or left, or ran out of stars) and is out.
     *
     * @param place    their place (2 of 4...); players out at the same moment share it
     * @param survived the rounds they lasted
     * @param left     they left the room (nobody to move to the gallery)
     * @param starless 0.57.0: they didn't collect the round's stars in time
     */
    record Out(UUID player, int place, int survived, boolean left, boolean starless) implements LavaEvent {
    }

    /**
     * 0.57.0, Group: the round's star goal was reached or missed (a missed goal costs a heart).
     *
     * @param got    the stars this round, less its touches
     * @param hearts the hearts left
     */
    record Goal(int round, int got, int goal, boolean made, int hearts) implements LavaEvent {
    }

    /** The round is over. */
    record Ended(LavaResult result) implements LavaEvent {
    }
}
