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

    @Test
    void aBedrockTapOnABlockOfOursNeverWearsIt() {
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, false, false, false, true),
                "the Arcade Machine is a head, not interactable: its tap opens the screen and leaves the hat alone");
        assertTrue(PrizeItemListener.wearsOnBlockTap(true, false, false, false, false),
                "any other plain block is as before");
        assertFalse(PrizeItemListener.wearsOnBlockTap(false, false, false, false, true), "Java: as before");
        // review HAT-1: a Mini's head (placed or wild) is ours too, though it is no placed block: MiniHeadListener
        // catches a wild one or opens a placed one's card, and PrizeItemListener.ours reads its skull's Mini, so a
        // child holding a Pumpkin Cap who taps a wild Mini catches it without putting the cap on
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, false, false, false, true),
                "a Mini's head, placed or wild: caught or shown, and the hat stays in the hand");
    }

    /**
     * A placed Arcade Trophy is "ours" (SHOP-V2-SPEC §5.8): its tap opens the Arcade, so a Bedrock hat tap (or a bound
     * key's tap) on it leaves the hat in the hand. Only on a player head, as for a Mini's.
     */
    @Test
    void aPlacedArcadeTrophyIsOurs() {
        assertTrue(PrizeItemListener.ours(false, false, true, false, true), "the trophy is ours");
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, false, false, false,
                PrizeItemListener.ours(false, false, true, false, true)), "a Bedrock hat tap on it wears nothing");
        assertFalse(PrizeItemListener.keyWearsOnBlockTap(false, false,
                PrizeItemListener.ours(false, false, true, false, true)), "nor a bound key's tap");
        assertFalse(PrizeItemListener.ours(false, false, false, false, true), "only a player head is asked");
        assertFalse(PrizeItemListener.ours(false, false, true, false, false), "a plain head is not ours");
        assertTrue(PrizeItemListener.ours(false, false, true, true, false), "a Mini's head is, as before");
        assertTrue(PrizeItemListener.ours(true, false, false, false, false), "a HomeCraft block is, as before");
        assertTrue(PrizeItemListener.ours(false, true, false, false, false), "a join sign is, as before");
    }

    /**
     * Review R1: wearing the Pumpkin Cap, a right-click with a stack of two hats and a full bag used to drop
     * the cap on the ground, with no owner, for anyone to take. Since 0.38 nothing coming off the head is ever
     * dropped: with nowhere for it to go, the swap is refused, whatever the helmet ({@link WearTest}).
     */
    @Test
    void nothingComingOffTheHeadIsEverDropped() {
        assertTrue(PrizeItemListener.keepsOldOn(false, false),
                "a full bag, a stack in the hand: refused");
        assertFalse(PrizeItemListener.keepsOldOn(true, false), "a free bag slot takes it");
        assertFalse(PrizeItemListener.keepsOldOn(false, true),
                "the last hat in the main hand: its slot takes the old helmet");
    }
}
