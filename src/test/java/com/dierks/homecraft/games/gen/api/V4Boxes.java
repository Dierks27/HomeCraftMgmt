package com.dierks.homecraft.games.gen.api;

import java.util.Map;

/**
 * Golf v4's boxes, pinned for the tests (GOLF-V4-SPEC §4.3, §8 G1): Golf of the Week and Classic
 * Golf at 128 x 16 x 224 in Col G (x 8768), half B at x + 128 + 576; Tiny Golf at its 0.36 size
 * and spot (64 x 16 x 48 at 7488,160,5504, half B 576 on). The numbers are written out here, not
 * read from {@link Slots}, on purpose: the planner's goldens stand at these boxes whatever the
 * shipped layout does (the Slots change is another package's), and a golden that changes means the
 * planner changed.
 */
public final class V4Boxes {

    /** The gap between a slot's halves in the v4 layout. */
    public static final int HALF_GAP = 576;

    /** Each golf slot's v4 box: origin {x, y, z} and size {x, y, z}. */
    private static final Map<String, int[]> BOXES = Map.of(
            "fresh_golf", new int[]{8768, 160, 4096, 128, 16, 224},
            "fresh_classic_golf", new int[]{8768, 160, 4896, 128, 16, 224},
            "fresh_tiny_golf", new int[]{7488, 160, 5504, 64, 16, 48});

    private V4Boxes() {
    }

    /** {@code def}'s v4 half {@code which} ('A' or 'B'). */
    public static Box half(Slots.Def def, char which) {
        int[] b = BOXES.get(def.id());
        if (b == null) {
            throw new IllegalArgumentException("no v4 box for " + def.id());
        }
        int x = Character.toUpperCase(which) == 'A' ? b[0] : b[0] + b[3] + HALF_GAP;
        return Box.sized(x, b[1], b[2], b[3], b[4], b[5]);
    }
}
