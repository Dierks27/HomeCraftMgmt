package com.dierks.homecraft.games.arena.rules;

import java.util.UUID;

/**
 * One player's place in a finished round (EVENTS-DROPPER-SPEC §B.3.3 step 5).
 *
 * <p>Places count like a race's: players out on the same tick share the place, and the next
 * place skips ("1, 2, 2, 4"). A player's place is fixed the tick they go out: 1 + how many are
 * still in after that tick.
 *
 * @param player        who
 * @param place         1 = last standing (or out on the round's last tick together)
 * @param survivedTicks play ticks from Go to when they went out (or the round ended)
 * @param reason        how they went out; {@code null} for the one still standing at the end
 * @param tied          whether someone else fell on the same tick (the place is shared; a leaver never ties)
 * @param winner        a multiplayer win that counts: 1st, not left, in a contested round
 */
public record Standing(UUID player, int place, long survivedTicks, OutReason reason, boolean tied, boolean winner) {

    public Standing {
        if (player == null) {
            throw new IllegalArgumentException("a standing names its player");
        }
        if (place < 1 || survivedTicks < 0) {
            throw new IllegalArgumentException("place " + place + " and time " + survivedTicks + " are not negative");
        }
    }

    /** How long they lasted, in milliseconds (ticks x 50). */
    public long survivalMs() {
        return survivedTicks * RoundSettings.MS_PER_TICK;
    }

    /** Whether they were still standing when the round ended. */
    public boolean stillStanding() {
        return reason == null;
    }

    /** Whether they left rather than fell (leaving earns nothing). */
    public boolean left() {
        return reason == OutReason.LEFT;
    }
}
