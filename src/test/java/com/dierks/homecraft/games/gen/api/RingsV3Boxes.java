package com.dierks.homecraft.games.gen.api;

import java.util.Map;

/**
 * Sky Rings v3's boxes, pinned for the tests (the {@link V4Boxes} precedent): Sky Rings and Classic
 * Sky Rings at 512 x 176 x 512, the live course at 6080,128,4096 and the Classic west at 1760,128,3328,
 * half B {@link #HALF_GAP} on. The numbers are written out here, not read from {@link Slots}, on
 * purpose: the planner's goldens stand at these boxes whatever the shipped layout does (the Slots
 * change is another package's), and a golden that changes means the planner changed. The legacy
 * 128 x 320 boxes in {@link LegacyBoxes} are too small for a v3 loop and are refused by the planner.
 */
public final class RingsV3Boxes {

    /** The gap between a slot's halves in the v3 layout. */
    public static final int HALF_GAP = 576;

    /** Each rings slot's v3 box: origin {x, y, z} and size {x, y, z}. */
    private static final Map<String, int[]> BOXES = Map.of(
            "fresh_rings", new int[]{6080, 128, 4096, 512, 176, 512},
            "fresh_classic_rings", new int[]{1760, 128, 3328, 512, 176, 512});

    private RingsV3Boxes() {
    }

    /** {@code def}'s v3 half {@code which} ('A' or 'B'). */
    public static Box half(Slots.Def def, char which) {
        int[] b = BOXES.get(def.id());
        if (b == null) {
            throw new IllegalArgumentException("no v3 box for " + def.id());
        }
        int x = Character.toUpperCase(which) == 'A' ? b[0] : b[0] + b[3] + HALF_GAP;
        return Box.sized(x, b[1], b[2], b[3], b[4], b[5]);
    }
}
