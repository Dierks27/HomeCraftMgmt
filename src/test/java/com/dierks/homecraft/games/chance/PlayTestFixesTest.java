package com.dierks.homecraft.games.chance;

import com.dierks.homecraft.games.chance.slots.SlotMachineTestAccess;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTableTestAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.50.2: the casino play-test's findings, pinned. */
class PlayTestFixesTest {

    @Test
    void aTokenSpinIsUnlitPastTheDaysLimitAndTheBalance() {
        assertEquals("No spins left today", SlotMachineTestAccess.tokenBlocker(5, 100, 0, 0, -1));
        assertEquals("Over your limit today", SlotMachineTestAccess.tokenBlocker(5, 100, 10, 100, 100));
        assertEquals("Need 2 more tokens", SlotMachineTestAccess.tokenBlocker(5, 3, 10, 0, -1));
        assertNull(SlotMachineTestAccess.tokenBlocker(5, 100, 10, 95, 100), "exactly the limit is fine");
    }

    @Test
    void aPlayerAwayFromTheTableIsToldHowTheirHandCameOut() {
        String back = TwentyOneTableTestAccess.awayLine(false, 5, 5);
        assertTrue(back.contains("Your 5 tokens back."), back);
        assertTrue(TwentyOneTableTestAccess.awayLine(true, 10, 20).contains("$10 in → $20 back"));
        assertTrue(TwentyOneTableTestAccess.awayLine(false, 5, 0).contains("No win this time."));
    }
}
