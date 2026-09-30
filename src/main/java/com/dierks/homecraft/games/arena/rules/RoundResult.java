package com.dierks.homecraft.games.arena.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How a round ended (EVENTS-DROPPER-SPEC §B.3.3 step 5): everyone's place and time, sorted by
 * place (then by who started first), for the results lines, the boards and the rewards.
 *
 * <p><b>Contested.</b> A multiplayer round is contested when at least two of its players played it
 * out (fell or were still standing) rather than leaving. Only a contested round has winners: when
 * everyone else leaves, the one left is not handed a win, so a win on {@code ffwins} always means
 * somebody else was really out-lasted (or went out on the very same tick).
 *
 * @param round     the round's number since the server started
 * @param solo      a solo round ("How long can you last?")
 * @param calledOff ended before it could be played out (too few left, the game closed): no results
 * @param contested at least two players played it out (multiplayer only)
 * @param starters  how many started
 * @param ticks     play ticks from Go to the end
 * @param standings everyone who played, by place
 */
public record RoundResult(int round, boolean solo, boolean calledOff, boolean contested, int starters, long ticks,
                          List<Standing> standings) {

    public RoundResult {
        standings = List.copyOf(standings == null ? List.of() : standings);
    }

    /** A round that ended before it was played out. */
    public static RoundResult calledOff(int round, boolean solo, int starters, long ticks) {
        return new RoundResult(round, solo, true, false, starters, ticks, List.of());
    }

    /** One player's standing, or {@code null} if they didn't play it. */
    public Standing standing(UUID player) {
        for (Standing s : standings) {
            if (s.player().equals(player)) {
                return s;
            }
        }
        return null;
    }

    /** The players whose win counts (none for solo, called-off or uncontested rounds). */
    public List<UUID> winners() {
        List<UUID> out = new ArrayList<>();
        for (Standing s : standings) {
            if (s.winner()) {
                out.add(s.player());
            }
        }
        return out;
    }

    /** How long the round was played, in milliseconds. */
    public long ms() {
        return ticks * RoundSettings.MS_PER_TICK;
    }
}
