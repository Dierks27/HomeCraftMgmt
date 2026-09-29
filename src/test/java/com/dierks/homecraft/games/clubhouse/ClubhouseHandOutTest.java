package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing a Clubhouse visitor out to a race, a round of golf or a ride (final gate, #16). What arrived
 * while they waited (an auction win, a Mini) is banked in the session's carry FIRST, because the next
 * game's kit goes into its own slots. If it can't be banked (the database is failing), nobody is handed
 * out: they stay in the Clubhouse with their things, and the race says it couldn't take them.
 */
class ClubhouseHandOutTest {

    private final List<String> steps = new ArrayList<>();

    private boolean handOut(boolean banks, boolean passes) {
        return Clubhouse.handOut(() -> {
            steps.add("bank");
            return banks;
        }, () -> {
            steps.add("pass");
            return passes;
        }, () -> steps.add("kit back"), () -> steps.add("gone"));
    }

    @Test
    void whatArrivedIsBankedBeforeTheSessionIsHandedOver() {
        assertTrue(handOut(true, true), "handed out");
        assertEquals(List.of("bank", "pass", "gone"), steps, "banked first, then handed over, then out of the Clubhouse");
    }

    @Test
    void aVisitorWhoseThingsCantBeBankedIsNeverHandedOut() {
        assertFalse(handOut(false, true), "not handed out: the next game's kit would go over their things");
        assertEquals(List.of("bank"), steps, "the session stays the Clubhouse's, and they stay in it");
    }

    @Test
    void aHandOverThatFailsAfterTheBankGivesTheClubhousesKitBack() {
        assertFalse(handOut(true, false), "not handed out");
        assertEquals(List.of("bank", "pass", "kit back"), steps,
                "the bank took the Clubhouse's kit with the rest: it is given back, and they stay");
    }
}
