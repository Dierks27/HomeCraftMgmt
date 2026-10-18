package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeService.CapPlacement;
import com.dierks.homecraft.gui.Menu;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a collector cap goes (HALLOWEEN-SPEC D8, kid-bedrock #6): a present goes on the head, swapping
 * an old helmet into the bag, unless that helmet has Curse of Binding; a buy never takes a helmet off. And
 * the present's moment is never hidden behind one of our menus.
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

    /**
     * Review BK-1: the present's moment must not play behind the receiver's open menu (on a Bedrock window a
     * chest screen covers the whole view). One of our menus is closed first; any other screen is left open.
     */
    @Test
    void theMomentClosesOnlyOurOwnMenu() {
        Menu ours = new Menu(null) {
            @Override
            protected void build() {
            }
        };
        assertTrue(PrizeService.hidesTheMoment(ours), "the Prize Counter, or any menu of ours: closed");
        InventoryHolder chest = () -> null;
        assertFalse(PrizeService.hidesTheMoment(chest), "a chest, or another plugin's screen: left open");
        assertFalse(PrizeService.hidesTheMoment(null), "nothing open");
    }
}
