package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.world.KitItems;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Time Trials' kits never go over what arrived mid-run (final gate, #16). An auction win or a Mini is
 * delivered with {@code addItem} into the first empty slot whatever the player is doing: during a
 * Dropper run that is slot 1 (the kits use only 0 and 8 once the offer is gone), and on an elytra course
 * it is the rocket slot once the three rockets are used. A kit change and a refill at a ring move it
 * aside; they never delete it.
 */
class TrialKitSlotsTest {

    private static final String MINI = "Mini #42 (uid 7f3a)";

    /** Items are strings; a kit item starts with {@code "kit:"}. */
    private static final class Inv implements KitItems.Slots<String> {
        final String[] slots = new String[41];

        @Override
        public String get(int slot) {
            return slots[slot];
        }

        @Override
        public void set(int slot, String item) {
            slots[slot] = item;
        }

        @Override
        public int firstEmpty() {
            for (int i = 0; i < 36; i++) {
                if (slots[i] == null) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean empty(String item) {
            return item == null;
        }

        @Override
        public boolean kit(String item) {
            return item.startsWith("kit:");
        }

        long count(String item) {
            return Arrays.stream(slots).filter(item::equals).count();
        }
    }

    private static void dropperKit(Inv inv, DropperRun.Kit kit) {
        DropperHooks.kit(inv, kit, action -> "kit:trials:" + action);
    }

    @Test
    void aMiniThatLandsInSlotOneDuringThePracticeDropSurvivesTheNextKit() {
        Inv inv = new Inv();
        dropperKit(inv, DropperRun.Kit.OFFER);
        assertEquals("kit:trials:" + DropperRun.STRAIGHT_ACTION, inv.slots[1], "the offer's second button");
        dropperKit(inv, DropperRun.Kit.PRACTICE); // Practice drop
        assertNull(inv.slots[1], "the offer's button is gone: slot 1 is the first empty slot now");
        inv.slots[inv.firstEmpty()] = MINI; // an auction ends while they fall
        assertEquals(MINI, inv.slots[1], "(it landed in slot 1)");
        dropperKit(inv, DropperRun.Kit.DROP); // a splash, a bonk or a skip ends the practice drop
        assertEquals(1, inv.count(MINI), "the Mini is still there, to be banked at the session's end");
        assertEquals("kit:trials:" + DropperRun.BACK_ACTION, inv.slots[0], "and the timed run's kit is in");
        assertEquals("kit:trials:" + DropperHooks.LEAVE_ACTION, inv.slots[8], "with Leave game");
    }

    @Test
    void aNewRunsOfferMovesTheMiniAsideInsteadOfOverwritingIt() {
        Inv inv = new Inv();
        dropperKit(inv, DropperRun.Kit.DROP);
        inv.slots[inv.firstEmpty()] = MINI; // slot 1, during the timed run
        dropperKit(inv, DropperRun.Kit.DROP); // the next run: TimeTrials.ready gives DROP again
        dropperKit(inv, DropperRun.Kit.OFFER); // then DropperRun.start offers the practice drop in slots 0 and 1
        assertEquals("kit:trials:" + DropperRun.STRAIGHT_ACTION, inv.slots[1], "the offer's button in its slot");
        assertEquals(1, inv.count(MINI), "and the Mini moved aside, once");
    }

    @Test
    void aMiniInTheEmptyRocketSlotMovesAsideAtTheNextRing() {
        Inv inv = new Inv();
        inv.slots[0] = "kit:trials:checkpoint";
        inv.slots[8] = "kit:trials:leave";
        // three boosts used the rockets up (adventure mode spends one each), and a Mini landed there
        inv.slots[TimeTrials.ROCKET_SLOT] = MINI;
        TimeTrials.refill(inv, 41, it -> it.startsWith("kit:trials:firework"), it -> it, () -> "kit:trials:firework x3");
        assertEquals("kit:trials:firework x3", inv.slots[TimeTrials.ROCKET_SLOT], "three rockets again");
        assertEquals(1, inv.count(MINI), "and the Mini is kept, one slot along");
    }

    @Test
    void rocketsLeftAnywhereAreToppedUpWhereTheyAre() {
        Inv inv = new Inv();
        inv.slots[TimeTrials.ROCKET_SLOT] = MINI;
        inv.slots[5] = "kit:trials:firework x1"; // the player moved the stack
        TimeTrials.refill(inv, 41, it -> it.startsWith("kit:trials:firework"), it -> "kit:trials:firework x3",
                () -> "kit:trials:firework x3 (new)");
        assertEquals("kit:trials:firework x3", inv.slots[5], "topped up in place");
        assertEquals(MINI, inv.slots[TimeTrials.ROCKET_SLOT], "the Mini untouched");
        assertEquals(0, inv.count("kit:trials:firework x3 (new)"), "no second stack");
    }
}
