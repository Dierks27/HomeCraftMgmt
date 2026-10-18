package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeService.CapPlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a collector cap goes (HALLOWEEN-SPEC D8, kid-bedrock #6): a present goes on the head, swapping
 * an old helmet into the bag, unless that helmet has Curse of Binding; a buy never takes a helmet off.
 */
class CapPlacementTest {

    @Test
    void aPresent() {
        assertEquals(CapPlacement.HEAD, PrizeService.capPlacement(true, false, PrizeService.GIFT));
        assertEquals(CapPlacement.SWAP, PrizeService.capPlacement(false, false, PrizeService.GIFT));
        assertEquals(CapPlacement.BAG, PrizeService.capPlacement(false, true, PrizeService.GIFT));
    }

    @Test
    void aBuy() {
        assertEquals(CapPlacement.HEAD, PrizeService.capPlacement(true, false, PrizeService.BUY));
        assertEquals(CapPlacement.BAG, PrizeService.capPlacement(false, false, PrizeService.BUY));
        assertEquals(CapPlacement.BAG, PrizeService.capPlacement(false, true, PrizeService.BUY));
    }
}
