package com.dierks.homecraft.arcade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wearing a hat with a Bedrock tap on a block (HALLOWEEN-SPEC D2, kid-bedrock #1): Geyser sends a tap on a
 * block as a use-on-block only, so a child looking at the ground could never put her cap back on. And a
 * collector taken off the head by a swap is never dropped (review R1).
 */
class HatTapTest {

    @Test
    void aBedrockTapOnAPlainBlockWearsIt() {
        assertTrue(PrizeItemListener.wearsOnBlockTap(true, false, false, false));
    }

    @Test
    void everythingElseIsAsBefore() {
        assertFalse(PrizeItemListener.wearsOnBlockTap(false, false, false, false),
                "Java keeps the right-click in the air");
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, true, false, false), "not while sneaking");
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, false, true, false),
                "a tap on a chest or a door still opens it");
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, false, false, true), "a head is still placed");
    }

    /**
     * Review R1: wearing the Pumpkin Cap, a right-click with a stack of two hats and a full bag used to drop
     * the cap on the ground, with no owner, for anyone to take. A collector coming off the head is never
     * dropped: with nowhere for it to go, the swap is refused.
     */
    @Test
    void aCollectorComingOffTheHeadIsNeverDropped() {
        assertTrue(PrizeItemListener.keepsOldOn(true, false, false),
                "a collector on the head, a full bag, a stack in the hand: refused");
        assertFalse(PrizeItemListener.keepsOldOn(true, true, false), "a free bag slot takes it");
        assertFalse(PrizeItemListener.keepsOldOn(true, false, true),
                "the last hat in the main hand: its slot takes the collector");
        assertFalse(PrizeItemListener.keepsOldOn(false, false, false),
                "any other helmet is handed back as before (to the ground when the bag is full)");
    }
}
