package com.dierks.homecraft.arcade.looks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Keeper key dragged onto (or off) the head starts (or stops) its mark at once, not at the next minute's sweep
 * (SHOP-V2-SPEC §5.3a, §5.5): a click or a drag in a view that shows the head slot reads the mark again a tick later.
 * Only the player's own inventory and the Creative one show it ({@code InventoryType} names: the enum needs a
 * server); a chest's view has no armour slots.
 */
class MarkOnDragTest {

    @Test
    void onlyAViewWithTheHeadSlotReadsTheMarkAgain() {
        assertTrue(LookListener.showsHead("CRAFTING"), "her own inventory (the 2x2 grid on top)");
        assertTrue(LookListener.showsHead("CREATIVE"), "the Creative inventory");
        assertFalse(LookListener.showsHead("CHEST"), "a chest: no armour slots, nothing to read");
        assertFalse(LookListener.showsHead("ENDER_CHEST"));
        assertFalse(LookListener.showsHead("WORKBENCH"));
        assertFalse(LookListener.showsHead(null));
    }
}
