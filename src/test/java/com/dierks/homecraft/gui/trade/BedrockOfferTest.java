package com.dierks.homecraft.gui.trade;

import com.dierks.homecraft.trade.TradeService;
import com.dierks.homecraft.trade.TradeService.Tap;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock taps in the trade window (SHOP-V2-SPEC §4.3, red team O13): a tap arrives as a left click, so a right
 * click never comes. A tap on a bag stack puts the whole stack on, or one more when it is on the table already; a
 * tap on her ghost halves it (1 comes off). So she can give 3 of her 20 candy apples: tap (20), tap the ghost (10),
 * (5), (2), then tap the bag stack (3). Java keeps its left and right clicks; every other click type does nothing.
 */
class BedrockOfferTest {

    @Test
    void aTapOnABagStackPutsItAllOnThenOneMore() {
        assertEquals(Tap.WHOLE, TradeMenu.bagTap(ClickType.LEFT, true, false));
        assertEquals(Tap.ONE_MORE, TradeMenu.bagTap(ClickType.LEFT, true, true), "on the table already: one more");
        assertEquals(Tap.ONE_MORE, TradeMenu.bagTap(ClickType.SHIFT_LEFT, true, true));
        assertEquals(Tap.NONE, TradeMenu.bagTap(ClickType.RIGHT, true, false), "never comes from a touch screen");
    }

    @Test
    void aTapOnHerGhostHalvesIt() {
        assertEquals(Tap.HALVE, TradeMenu.ghostTap(ClickType.LEFT, true));
        assertEquals(10, TradeService.amountAfter(Tap.HALVE, 20, 20));
        assertEquals(0, TradeService.amountAfter(Tap.HALVE, 1, 20), "1 comes off the table");
    }

    @Test
    void threeOfHerTwentyCandyApples() {
        int on = TradeService.amountAfter(TradeMenu.bagTap(ClickType.LEFT, true, false), 0, 20);
        assertEquals(20, on, "tap the stack: all 20");
        int[] expected = {10, 5, 2};
        for (int want : expected) {
            on = TradeService.amountAfter(TradeMenu.ghostTap(ClickType.LEFT, true), on, 20);
            assertEquals(want, on, "tap the ghost: half back");
        }
        on = TradeService.amountAfter(TradeMenu.bagTap(ClickType.LEFT, true, true), on, 20);
        assertEquals(3, on, "tap the stack again: one more");
    }

    @Test
    void javaKeepsItsClicks() {
        assertEquals(Tap.WHOLE, TradeMenu.bagTap(ClickType.LEFT, false, true), "top it up to the whole stack");
        assertEquals(Tap.ONE_MORE, TradeMenu.bagTap(ClickType.RIGHT, false, false));
        assertEquals(Tap.TAKE_OFF, TradeMenu.ghostTap(ClickType.LEFT, false));
        assertEquals(Tap.ONE_FEWER, TradeMenu.ghostTap(ClickType.SHIFT_RIGHT, false));
        assertEquals(20, TradeService.amountAfter(Tap.WHOLE, 7, 20));
        assertEquals(20, TradeService.amountAfter(Tap.ONE_MORE, 20, 20), "never more than the slot holds");
        assertEquals(6, TradeService.amountAfter(Tap.ONE_FEWER, 7, 20));
    }

    @Test
    void everyOtherClickDoesNothing() {
        for (ClickType c : ClickType.values()) {
            boolean plain = c == ClickType.LEFT || c == ClickType.SHIFT_LEFT || c == ClickType.RIGHT
                    || c == ClickType.SHIFT_RIGHT;
            assertEquals(plain, TradeMenu.buttonTap(c), c.name());
            if (!plain) {
                assertEquals(Tap.NONE, TradeMenu.bagTap(c, false, false), c + " on a bag stack");
                assertEquals(Tap.NONE, TradeMenu.ghostTap(c, false), c + " on a ghost (MIDDLE: no creative clone)");
            }
        }
        assertTrue(true);
    }
}
