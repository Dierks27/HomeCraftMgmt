package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bar's drinks (0.50.0): what a drink does, and which bottle a tap takes. */
class BarDrinksTest {

    @Test
    void everySillyThingCanComeUpAndNeverTheSameTwiceInARow() {
        Set<BarDrinks.Silly> seen = EnumSet.noneOf(BarDrinks.Silly.class);
        for (int roll = 0; roll < 100; roll++) {
            BarDrinks.Silly s = BarDrinks.pick(null, roll);
            seen.add(s);
            assertNotEquals(s, BarDrinks.pick(s, roll), "a second drink is something else");
        }
        assertEquals(EnumSet.allOf(BarDrinks.Silly.class), seen);
    }

    @Test
    void aSillyThingLastsAWhileNotLong() {
        assertEquals(20 * 20, BarDrinks.SILLY_TICKS, "20 seconds");
        assertTrue(BarDrinks.TINY > 0.3 && BarDrinks.TINY < 1, "small, not vanishing");
    }

    @Test
    void aTapTakesTheBottleItAimedAt() {
        // the back bar faces east: someone looking at it faces west, their left is south (z up)
        assertEquals(0, BarDrinks.slotAt("east", 1.0, 0.9));
        assertEquals(1, BarDrinks.slotAt("east", 1.0, 0.5));
        assertEquals(2, BarDrinks.slotAt("east", 1.0, 0.1));
        assertEquals(0, BarDrinks.slotAt("west", 0.0, 0.1));
        assertEquals(2, BarDrinks.slotAt("south", 0.95, 1.0));
        assertEquals(-1, BarDrinks.slotAt("up", 0.5, 0.5));
    }
}
