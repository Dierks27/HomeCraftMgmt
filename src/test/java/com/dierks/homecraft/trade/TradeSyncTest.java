package com.dierks.homecraft.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The 10-tick sync (SHOP-V2-SPEC §4.4): a slot that holds fewer than its entry keeps the entry on the table, lowered,
 * and says how many are on it now (red-team finding TW-SYNC-1); a slot that is empty or holds something else takes
 * the entry off and says so.
 */
class TradeSyncTest {

    @Test
    void aLoweredStackStaysOnTheTableAndSaysHowMany() {
        TradeService.Synced r = TradeService.synced("Dirt", 64, true, 32);
        assertEquals(32, r.amount(), "still on the table, lowered");
        assertEquals("&7Your Dirt changed - 32 on the table now.", r.line(), "[X] not \"it's off the table\"");
    }

    @Test
    void aGoneStackComesOff() {
        TradeService.Synced gone = TradeService.synced("Dirt", 64, false, 0);
        assertEquals(0, gone.amount());
        assertEquals("&7Your Dirt changed - it's off the table.", gone.line());
        assertEquals(0, TradeService.synced("Dirt", 64, false, 12).amount(), "something else in the slot");
    }

    @Test
    void aSlotThatStillHoldsItIsLeftAlone() {
        assertNull(TradeService.synced("Dirt", 64, true, 64));
        assertNull(TradeService.synced("Dirt", 32, true, 64), "more than offered: the entry stands");
    }
}
