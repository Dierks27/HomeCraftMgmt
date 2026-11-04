package com.dierks.homecraft.arcade.turkey;

import java.util.UUID;

/**
 * What {@link TurkeyRuns} needs from the server: turkeys in the world and the marks in the database. The
 * service is the real one; {@code TurkeyRunsTest} uses a fake, so the runs are tested without a server.
 */
public interface TurkeyHost {

    /** A turkey for this player in front of them, tagged with the run: its entity id, or null when no spot fits. */
    UUID spawn(UUID player, String runId);

    /** The turkey goes ("waddles home" with a poof). Nothing happens when it is already gone. */
    void remove(UUID turkey);

    /** Whether the player's claim for this run is already written (true when it can't be read: no turkey then). */
    boolean claimed(UUID player, String runId);

    /** Write the player's claim for this run: true only the first time, so nothing is ever paid twice. */
    boolean claim(UUID player, String runId);

    /** Whether the player had their welcome turkey today (true when it can't be read). */
    boolean welcomeGiven(UUID player);

    /** Write today's welcome mark, once the welcome turkey is out: true only the first time. */
    boolean markWelcome(UUID player);

    /** The catch: the firework, the title, the feather, the tokens. {@code test} gives the moment only. */
    void caught(UUID player, UUID turkey, String runId, boolean test);

    /** A soft cluck for a tap that catches nothing (already caught, or someone else's welcome turkey). */
    void cluck(UUID player, UUID turkey);
}
