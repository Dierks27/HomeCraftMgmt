package com.dierks.homecraft.arcade.feast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Clubhouse's Feast Pot (0.42.0), fed from the saved home inventory: what the menu shows, which slot gives the
 * item, and the order of the two writes (the saved inventory first, then the points; anything but points puts the
 * item back), so an item is never lost or counted twice.
 */
class HomePantryTest {

    private static HomePantry.Slot s(String type, int n) {
        return new HomePantry.Slot(type, n, true);
    }

    /** A saved inventory: one row of slots, as amounts per slot, that the fake writes change. */
    private static final class Saved {
        final List<HomePantry.Slot> slots;
        boolean changedUnder;
        boolean putBackFails;
        int writes;

        Saved(HomePantry.Slot... slots) {
            this.slots = new ArrayList<>(Arrays.asList(slots));
        }

        boolean without(int slot) {
            if (changedUnder) {
                return false;
            }
            writes++;
            HomePantry.Slot was = slots.get(slot);
            slots.set(slot, was.amount() <= 1 ? null : new HomePantry.Slot(was.type(), was.amount() - 1, was.takes()));
            last = slot;
            lastWas = was;
            return true;
        }

        int last = -1;
        HomePantry.Slot lastWas;

        boolean putBack() {
            if (putBackFails) {
                return false;
            }
            writes++;
            slots.set(last, lastWas);
            return true;
        }

        int count(String type) {
            int n = 0;
            for (HomePantry.Slot x : slots) {
                if (x != null && x.type().equals(type)) {
                    n += x.amount();
                }
            }
            return n;
        }
    }

    @Test
    void theMenuShowsEachFoodOnceWithItsCountMostPointsFirst() {
        List<HomePantry.Food> foods = HomePantry.foods(Arrays.asList(s("BREAD", 5), null, s("PUMPKIN_PIE", 2),
                s("BREAD", 3), s("DIAMOND", 4), new HomePantry.Slot("CAKE", 1, false), s("COOKED_BEEF", 1)));
        assertEquals(List.of(new HomePantry.Food("PUMPKIN_PIE", 2, 3), new HomePantry.Food("COOKED_BEEF", 1, 2),
                new HomePantry.Food("BREAD", 8, 1)), foods, "pie (3), beef (2), bread (1, 8 of it); never a diamond,"
                + " never food the pot refuses (a prize cake)");
        assertTrue(HomePantry.foods(List.of()).isEmpty(), "nothing at home: the empty bowl");
    }

    @Test
    void oneComesFromTheSmallestStack() {
        List<HomePantry.Slot> slots = Arrays.asList(s("BREAD", 64), s("BREAD", 3), s("BREAD", 3), null);
        assertEquals(1, HomePantry.pick(slots, "BREAD"), "the first of the smallest: a full stack stays whole");
        assertEquals(-1, HomePantry.pick(slots, "CAKE"));
        assertEquals(-1, HomePantry.pick(List.of(new HomePantry.Slot("BREAD", 3, false)), "BREAD"),
                "never one the pot refuses");
    }

    @Test
    void aTapTakesOneFromHomeAndCountsIt() {
        Saved home = new Saved(s("BREAD", 2), s("PUMPKIN_PIE", 1));
        int[] given = {0};
        HomePantry.Fed fed = HomePantry.feed(home.slots, "PUMPKIN_PIE", home::without, () -> {
            given[0]++;
            return new FeastCore.Gift(FeastCore.Outcome.TAKEN, 1, 3);
        }, home::putBack);
        assertEquals(HomePantry.Fed.TAKEN, fed);
        assertEquals(0, home.count("PUMPKIN_PIE"), "the pie left home");
        assertEquals(2, home.count("BREAD"), "nothing else did");
        assertEquals(1, given[0], "counted once");
        assertEquals(1, home.writes);
    }

    @Test
    void refusedPointsPutTheItemBack() {
        for (FeastCore.Outcome o : new FeastCore.Outcome[]{FeastCore.Outcome.FULL, FeastCore.Outcome.CLOSED,
                FeastCore.Outcome.FAILED}) {
            Saved home = new Saved(s("BREAD", 2));
            HomePantry.Fed fed = HomePantry.feed(home.slots, "BREAD", home::without,
                    () -> new FeastCore.Gift(o, 0, 0), home::putBack);
            assertEquals(o.name(), fed.name(), "the outcome as it was");
            assertEquals(2, home.count("BREAD"), o + ": the bread is home again");
        }
        Saved home = new Saved(s("BREAD", 1));
        assertEquals(HomePantry.Fed.FAILED, HomePantry.feed(home.slots, "BREAD", home::without, () -> null,
                home::putBack));
        assertEquals(1, home.count("BREAD"), "a gift that threw: the bread is home again");
    }

    @Test
    void aChangeUnderTheTapDoesNothing() {
        Saved home = new Saved(s("BREAD", 2));
        home.changedUnder = true; // the session ended, or another tap won
        boolean[] asked = {false};
        HomePantry.Fed fed = HomePantry.feed(home.slots, "BREAD", home::without, () -> {
            asked[0] = true;
            return new FeastCore.Gift(FeastCore.Outcome.TAKEN, 1, 1);
        }, home::putBack);
        assertEquals(HomePantry.Fed.CHANGED, fed);
        assertFalse(asked[0], "no points without the item");
        assertEquals(2, home.count("BREAD"));
        assertEquals(HomePantry.Fed.GONE, HomePantry.feed(home.slots, "CAKE", home::without,
                () -> new FeastCore.Gift(FeastCore.Outcome.TAKEN, 1, 6), home::putBack), "none at home");
    }

    @Test
    void aPutBackThatFailsIsReportedNeverHidden() {
        Saved home = new Saved(s("BREAD", 2));
        home.putBackFails = true;
        assertEquals(HomePantry.Fed.LOST, HomePantry.feed(home.slots, "BREAD", home::without,
                () -> new FeastCore.Gift(FeastCore.Outcome.FAILED, 0, 0), home::putBack), "logged SEVERE for an admin");
    }
}
