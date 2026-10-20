package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a hand-built parkour point stands on something (0.37.1). A checkpoint or the finish is a sphere around
 * where the builder's feet were, so one set while flying hangs in the air above its pad: a player on the pad is
 * that much further from it, and with the default radius (1.5) one more than 1.5 above the pad can only be
 * reached with a jump. {@code /hcm games course <id> checkpoint add} and {@code finish} warn when the builder
 * isn't standing on a block, and {@code /hcm games check} lists the points of each hand-built parkour course that
 * aren't on one. Fresh Courses' own marks always sit on their pad's top (their proof needs a solid block under
 * each), and only those get {@link Progress}'s floor. Pure: the world comes in as {@link Solid}.
 */
public final class Footing {

    /** A player's half-width: feet at x stand on any block under x - 0.3 to x + 0.3 (and the same for z). */
    static final double HALF_WIDTH = 0.3;

    /** Whether block (x, y, z) is solid. */
    @FunctionalInterface
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    private Footing() {
    }

    /**
     * Whether feet at (x, y, z) are on a block: something solid under the player's width, within
     * {@link Progress#FEET_BELOW} under the feet (from a hair under them, so a slab's own block counts, down to
     * half a block, that included).
     */
    public static boolean onGround(double x, double y, double z, Solid solid) {
        int top = (int) Math.floor(y - 0.01);
        int low = (int) Math.floor(y - Progress.FEET_BELOW - 1e-6);
        for (int bx = (int) Math.floor(x - HALF_WIDTH); bx <= (int) Math.floor(x + HALF_WIDTH); bx++) {
            for (int bz = (int) Math.floor(z - HALF_WIDTH); bz <= (int) Math.floor(z + HALF_WIDTH); bz++) {
                for (int by = top; by >= low; by--) {
                    if (solid.at(bx, by, bz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The points of a parkour course that aren't on a block ({@link #onGround}), in order: "checkpoint 2", "the
     * finish". Empty for every other kind: rings are flown through, a boat mark is on water, a pool is water.
     */
    public static List<String> offGround(Course c, Solid solid) {
        List<String> out = new ArrayList<>();
        if (c == null || c.kind() != TrialKind.PARKOUR) {
            return out;
        }
        for (int i = 0; i < c.checkpoints().size(); i++) {
            Course.Mark m = c.checkpoints().get(i);
            if (!onGround(m.x(), m.y(), m.z(), solid)) {
                out.add("checkpoint " + (i + 1));
            }
        }
        Course.Mark f = c.finish();
        if (f != null && !onGround(f.x(), f.y(), f.z(), solid)) {
            out.add("the finish");
        }
        return out;
    }
}
