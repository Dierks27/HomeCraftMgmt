package com.dierks.homecraft.games.chance.wheel;

/**
 * One spin of the Wheel, as decided (spec §5.2, §5.5): where it landed and what that pays. It is
 * settled in the database before the screen shows a single frame; the animation only travels to
 * {@link #space}.
 *
 * @param stake  the tokens put in
 * @param space  the space it landed on (0-23, ring order), drawn uniformly from the seed
 * @param prize  the tokens back (already capped)
 * @param result how it reads: nothing, your tokens back, or a win
 * @param data   what the round stores: the engine version, the space, its base and the prize
 */
public record WheelSpin(int stake, int space, int prize, WheelOdds.Result result, String data) {
}
