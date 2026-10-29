package com.dierks.homecraft.gui.games;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Play?" screen (0.38.4): its buttons where every confirm screen and the Trade? screen have them (Not now on
 * the left, Yes on the right, the asker in the middle), and the asker's tile NAME carries who and what, since
 * Bedrock shows lore only on a long press.
 */
class InviteAskMenuLayoutTest {

    @Test
    void theButtonsAreWhereEveryConfirmScreenHasThem() {
        assertEquals(11, InviteAskMenu.NO, "Not now on the left");
        assertEquals(13, InviteAskMenu.ASKER, "the asker in the middle");
        assertEquals(15, InviteAskMenu.YES, "Yes on the right");
    }

    @Test
    void theAskersTileSaysWhoAndWhatInItsName() {
        String name = InviteAskMenu.askerName("Mum", "Party race");
        assertEquals("&bMum invites you: &fParty race", name);
        assertTrue(name.contains("Mum") && name.contains("Party race"), "the asker and the invite's name");
        assertFalse(name.contains("\uD83D"), "no glyph above U+FFFF");
        assertEquals("&bDad invites you: &fRace challenge", InviteAskMenu.askerName("Dad", "Race challenge"));
    }
}
