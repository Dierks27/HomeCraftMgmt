package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.arcade.looks.LookRules.Placement;
import com.dierks.homecraft.storage.LookDao;
import org.junit.jupiter.api.Test;

import static com.dierks.homecraft.arcade.looks.LookFixtures.DAY;
import static com.dierks.homecraft.arcade.looks.LookFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a buyer sees (SHOP-V2-SPEC §3.5, red team O19): a first look buy closes the counter so its moment shows on a
 * Bedrock screen, a renewal reopens it; a bought key replaces a cosmetic on the head, never real armour.
 */
class BuyFeedbackTest {

    @Test
    void aFirstLookBuyClosesTheCounter() {
        long now = 50 * DAY;
        assertTrue(LookRules.firstBuy(null, now), "never had it");
        assertTrue(LookRules.firstBuy(row("cap_lime", LookDao.HAT, now - 1, 1), now), "ended: a first buy again");
        assertFalse(LookRules.firstBuy(row("cap_lime", LookDao.HAT, now + DAY, 1), now),
                "a renewal: the counter reopens");
    }

    @Test
    void aCosmeticHelmetIsReplacedRealArmourNever() {
        assertEquals(Placement.HEAD, LookRules.placement(true, false, false, false, false, false), "an empty head");
        assertEquals(Placement.HEAD_REPLACING_KEY, LookRules.placement(false, true, false, false, false, false),
                "another key: deleted, Wear it makes it again");
        assertEquals(Placement.HEAD_SWAPPING, LookRules.placement(false, false, true, false, true, false),
                "a collector cap moves into the bag");
        assertEquals(Placement.NOWHERE, LookRules.placement(false, false, true, false, false, false),
                "no room: the cap stays on, the key is in My Looks");
        assertEquals(Placement.BAG, LookRules.placement(false, false, false, false, true, false),
                "real armour is never taken off by a buy");
        assertEquals(Placement.NOWHERE, LookRules.placement(false, false, false, false, false, false),
                "and with a full bag: My Looks, never the ground");
        assertEquals(Placement.HEAD_SWAPPING, LookRules.placement(false, false, false, false, true, true),
                "a present swaps real armour into the bag, as a collector present does");
        assertEquals(Placement.BAG, LookRules.placement(false, false, false, true, true, true),
                "a helmet with Curse of Binding never comes off");
        assertEquals("It's in My Looks - tap Wear it.", LookRules.where(Placement.NOWHERE));
        assertEquals("It's on your head!", LookRules.where(Placement.HEAD));
    }
}
