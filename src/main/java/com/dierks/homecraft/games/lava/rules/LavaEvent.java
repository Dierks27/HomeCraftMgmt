package com.dierks.homecraft.games.lava.rules;

import java.util.List;
import java.util.UUID;

/**
 * What a round of The Floor Is Lava tells the room, in the order it happened ({@link LavaRound}). The rules know
 * nothing of blocks or players' feet: the room turns each of these into lava, stars, sounds and teleports.
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

    /** These stars are out now, to be put somewhere new (a safe phase began). */
    record StarsOut(int set, List<Integer> ids) implements LavaEvent {
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
     * @param have what is collected now (Little: of {@code goal}; Group: the shared count)
     * @param goal Little's full count; 0 where there is no goal
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
     * Challenge: a player touched the lava (or left) and is out.
     *
     * @param place    their place (2 of 4...); players out at the same moment share it
     * @param survived the rounds they lasted
     * @param left     they left the room (nobody to move to the gallery)
     */
    record Out(UUID player, int place, int survived, boolean left) implements LavaEvent {
    }

    /** The round is over. */
    record Ended(LavaResult result) implements LavaEvent {
    }
}
