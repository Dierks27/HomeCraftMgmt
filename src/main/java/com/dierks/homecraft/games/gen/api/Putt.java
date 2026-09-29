package com.dierks.homecraft.games.gen.api;

/**
 * One golf putt as a player makes it (GEN-SPEC §4.0, §4.3): the way they face (Minecraft yaw, 0 is
 * +z, 90 is -x) and the club, 1 ("Tap") to 5 ("Drive"). The golf planner's witness lines are lists
 * of these, stored in the row's {@code gen:} block and replayed on the real blocks at every build
 * and boot, through the same {@code GolfShot} the game plays with.
 */
public record Putt(float yaw, int power) {

    public Putt {
        if (!Float.isFinite(yaw)) {
            throw new IllegalArgumentException("a putt's yaw is a number: " + yaw);
        }
        if (power < 1 || power > 5) {
            throw new IllegalArgumentException("a putt's power is 1-5: " + power);
        }
    }
}
