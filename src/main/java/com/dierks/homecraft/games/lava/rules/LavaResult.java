package com.dierks.homecraft.games.lava.rules;

import java.util.List;
import java.util.UUID;

/**
 * How a round of The Floor Is Lava ended.
 *
 * @param mode      the way it was played
 * @param calledOff everyone left before it was over: nothing is recorded
 * @param players   who was in the room at the end (Little, Group: they share the result)
 * @param stars     Little: the stars collected, which is every one of them; Group: the shared count; Challenge: 0
 * @param rounds    how many times the lava came
 * @param standings Challenge: everyone who started, best first (the winner, or the winners, at place 1)
 * @param solo      Challenge: one player alone ("how many rounds can you last?")
 */
public record LavaResult(LavaMode mode, boolean calledOff, List<UUID> players, int stars, int rounds,
                         List<Standing> standings, boolean solo) {

    /**
     * One Challenge player's result.
     *
     * @param place    1 for the last one standing (shared by players who went out at the same moment)
     * @param survived the rounds they lasted
     */
    public record Standing(UUID player, int place, int survived) {
    }

    public LavaResult {
        players = List.copyOf(players == null ? List.of() : players);
        standings = List.copyOf(standings == null ? List.of() : standings);
    }

    /** The Challenge standing of {@code player}, or {@code null}. */
    public Standing standing(UUID player) {
        for (Standing s : standings) {
            if (s.player().equals(player)) {
                return s;
            }
        }
        return null;
    }

    /** Challenge: the winner or winners (place 1); empty for the other modes. */
    public List<UUID> winners() {
        return standings.stream().filter(s -> s.place() == 1).map(Standing::player).toList();
    }
}
