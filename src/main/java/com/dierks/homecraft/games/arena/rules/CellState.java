package com.dierks.homecraft.games.arena.rules;

/**
 * What a floor cell is during a round (EVENTS-DROPPER-SPEC §B.3.3): the floor's own glass, red
 * glass ("about to fall"), or air. A cell only ever moves forward, SOLID to RED to AIR; only the
 * reset between rounds (a converge to the week's plan) puts it back.
 */
public enum CellState {
    /** The plan's own glass: yellow on top, pink in the middle, light blue at the bottom. */
    SOLID,
    /** Red stained glass: stepped on, and gone {@code fade_ticks} later. Still holds a player up. */
    RED,
    /** Gone. */
    AIR;

    /** Whether a player can stand on it (red still holds, which is what makes it a warning). */
    public boolean holds() {
        return this != AIR;
    }
}
