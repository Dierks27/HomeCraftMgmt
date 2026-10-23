package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeItemListener.OldHelmet;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Putting a hat on (SHOP-V2-SPEC §3.7): nothing ever goes on the ground; a bound old helmet is deleted (My Looks
 * makes it again); anything else goes into the bag, and with no room the swap is refused; a bound key's tap on a
 * plain block wears it on both editions, and a key is never placed.
 */
class WearTest {

    @Test
    void wearNeverDropsAnything() {
        for (boolean bound : new boolean[] {false, true}) {
            for (boolean room : new boolean[] {false, true}) {
                for (boolean frees : new boolean[] {false, true}) {
                    OldHelmet goes = PrizeItemListener.oldHelmet(bound, room, frees);
                    assertNotEquals(OldHelmet.NONE, goes);
                    // NONE is "nothing was on the head"; the only outcomes are delete, the bag, or refuse.
                    assertTrue(goes == OldHelmet.DELETE || goes == OldHelmet.BAG || goes == OldHelmet.REFUSE);
                }
            }
        }
    }

    @Test
    void aBoundOldHelmetIsDeleted() {
        assertEquals(OldHelmet.DELETE, PrizeItemListener.oldHelmet(true, false, false),
                "a key is never moved, so it never needs room");
        assertEquals(OldHelmet.DELETE, PrizeItemListener.oldHelmet(true, true, true));
    }

    @Test
    void aFullBagRefusesTheSwap() {
        assertEquals(OldHelmet.REFUSE, PrizeItemListener.oldHelmet(false, false, false),
                "any other helmet with nowhere to go: refused (it used to be dropped)");
        assertEquals(OldHelmet.BAG, PrizeItemListener.oldHelmet(false, true, false));
        assertEquals(OldHelmet.BAG, PrizeItemListener.oldHelmet(false, false, true),
                "the last hat in the main hand frees its own slot");
        assertTrue(PrizeItemListener.keepsOldOn(false, false));
        assertFalse(PrizeItemListener.keepsOldOn(true, false));
    }

    @Test
    void theBoundKeyTapWearsOnBothEditionsAndNeverPlaces() {
        assertTrue(PrizeItemListener.keyWearsOnBlockTap(false, false, false), "a plain block: worn, Java or Bedrock");
        assertFalse(PrizeItemListener.keyWearsOnBlockTap(true, false, false), "sneaking passes the tap through");
        assertFalse(PrizeItemListener.keyWearsOnBlockTap(false, true, false), "a chest still opens");
        assertFalse(PrizeItemListener.keyWearsOnBlockTap(false, false, true), "never on one of ours");
        // Bedrock's rule for an unbound hat is called with head && !bound: a bound mob head is never "a head to
        // place", and an old textured hat still is.
        assertFalse(PrizeItemListener.wearsOnBlockTap(true, false, false, true && !false, false),
                "a grandfathered player-head hat on Bedrock is still placed");
        assertTrue(PrizeItemListener.wearsOnBlockTap(true, false, false, true && !true, false));
        assertTrue(PrizeItemListener.isHead(Material.CREEPER_HEAD));
        assertTrue(PrizeItemListener.isHead(Material.PLAYER_HEAD));
        assertFalse(PrizeItemListener.isHead(Material.LEATHER_HELMET), "a cap is no head");
        assertFalse(PrizeItemListener.isHead(Material.CREEPER_WALL_HEAD), "a wall head is a block, never in a hand");
    }
}
