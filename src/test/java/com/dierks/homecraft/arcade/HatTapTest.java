package com.dierks.homecraft.arcade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wearing a hat with a Bedrock tap on a block (HALLOWEEN-SPEC D2, kid-bedrock #1): Geyser sends a tap on a
 * block as a use-on-block only, so a child looking at the ground could never put her cap back on.
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
}
