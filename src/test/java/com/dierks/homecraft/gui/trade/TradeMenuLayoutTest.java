package com.dierks.homecraft.gui.trade;

import com.dierks.homecraft.trade.TradeService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade window's layout and words (SHOP-V2-SPEC §4.3): 16 + 16 table slots, none on a button, a label or the
 * divider; your table on the left of the divider and theirs on the right; the Ready tile's NAME carries READY and
 * the Mailbox count (Bedrock reads names, not lore); the picker's reasons and switch; and the Trade? screen's
 * buttons where every confirm screen has them. ({@code PlayerCopyTest} and {@code BedrockGlyphTest} scan these
 * words with every other screen's.)
 */
class TradeMenuLayoutTest {

    @Test
    void sixteenAndSixteenAndNothingOnTop() {
        assertEquals(16, TradeMenu.MINE.length);
        assertEquals(16, TradeMenu.THEIRS.length);
        Set<Integer> used = new HashSet<>();
        for (int[] group : new int[][]{TradeMenu.MINE, TradeMenu.THEIRS, TradeMenu.DIVIDERS, TradeMenu.BUTTONS}) {
            for (int slot : group) {
                assertTrue(slot >= 0 && slot < 54, slot + " is in a 54-slot window");
                assertTrue(used.add(slot), "slot " + slot + " is used twice");
            }
        }
        for (int slot : TradeMenu.MINE) {
            assertTrue(slot % 9 <= 3, slot + ": your table is left of the divider");
        }
        for (int slot : TradeMenu.THEIRS) {
            assertTrue(slot % 9 >= 5, slot + ": theirs is right of it");
        }
        for (int d : new int[]{13, 22, 31, 40}) {
            assertTrue(contains(TradeMenu.DIVIDERS, d), "the divider runs down the middle: " + d);
        }
        assertEquals(45, TradeMenu.CANCEL);
        assertEquals(49, TradeMenu.YOUR_READY);
        assertEquals(53, TradeMenu.HOW);
    }

    @Test
    void theReadyNameCarriesReadyAndTheMailbox() {
        assertEquals("&a&lREADY ✓", TradeMenu.readyName(true, false, false, 0));
        assertEquals("&a&lREADY ✓ &e(2 to Mailbox)", TradeMenu.readyName(true, false, false, 2));
        assertEquals("&cNot ready - tap when happy &e(1 to Mailbox)", TradeMenu.readyName(false, false, false, 1));
        assertEquals("&7Look again...", TradeMenu.readyName(false, true, false, 3), "the change lock");
        assertEquals("&eGive it away? Tap again", TradeMenu.readyName(false, false, true, 0),
                "a give-away's first tap");
        assertEquals("&a&lREADY ✓", TradeMenu.theirReadyName(true));
        assertEquals("&eTrading in 3...", TradeMenu.countdownName(3));
        assertEquals("&eGoing to Helper: 3 Diamond (+2 more)", TradeMenu.goingName("Helper", "3 Diamond", 2));
        assertEquals("&eGoing to Helper: Pumpkin Cap 2026", TradeMenu.goingName("Helper", "Pumpkin Cap 2026", 0));
        assertEquals("1,000", TradeMenu.format(1000));
        assertEquals(4, TradeMenu.STEPS.length, "$1, $10, $100, $1,000");
    }

    /**
     * Red-team finding DJ-6: her How book's name said "Tap here to take half back", on a book that does nothing when
     * tapped. Bedrock shows the name, so it points at her things on the table, where a tap takes half back.
     */
    @Test
    void herHowTilePointsAtTheTable() {
        assertEquals("&eTap your things to add. Tap them on the table to take half back.", TradeMenu.howName(true));
        assertFalse(TradeMenu.howName(true).contains("here"), "the book itself does nothing");
        assertEquals("&eHow to trade", TradeMenu.howName(false));
    }

    @Test
    void thePickersReasonsAreInTheName() {
        assertNull(TradeService.pickerNote(true, true, false), "green: tap to ask");
        assertEquals("not here", TradeService.pickerNote(false, false, true), "another world or a game first");
        assertEquals("trades off", TradeService.pickerNote(true, false, true));
        assertEquals("busy", TradeService.pickerNote(true, true, true));
        assertEquals("&aHelper", TradePickerMenu.tileName("Helper", null));
        assertEquals("&7Helper &8- busy", TradePickerMenu.tileName("Helper", "busy"));
        assertEquals("&aTrade requests: on", TradePickerMenu.toggleName(true));
        assertEquals("&7Trade requests: off", TradePickerMenu.toggleName(false));
        assertEquals(47, TradePickerMenu.TOGGLE, "in the bottom row, off the arrows and the way out");
        assertEquals(1, TradePickerMenu.pages(36));
        assertEquals(2, TradePickerMenu.pages(37));
    }

    @Test
    void theTradeScreenHasItsButtonsWhereEveryConfirmDoes() {
        assertEquals(11, TradeAskMenu.NO, "No thanks on the left");
        assertEquals(15, TradeAskMenu.YES, "Yes on the right");
        assertEquals(13, TradeAskMenu.ASKER);
        assertEquals("&bOwner wants to trade!", TradeAskMenu.askerName("Owner"));
        assertFalse(TradeAskMenu.askerName("Owner").contains("\uD83D"), "no glyph above U+FFFF");
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }
}
