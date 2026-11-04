package com.dierks.homecraft.arcade.feast;

import com.dierks.homecraft.arcade.feast.FeastRules.Refusal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Family Feast's rules (0.38.6): the food and its points, what the pot refuses (never Creative, never a basket
 * treat), one item a tap and the stack on a sneak under the day's points, items taken rounded up, the plates, the
 * Thankful line, each moment's gate, and every name kept under the edition.
 */
class FeastRulesTest {

    private static boolean takes(String material, boolean harvest, boolean prize, boolean candy, boolean kit,
                                 boolean named) {
        return FeastRules.refusal(material, harvest, prize, candy, kit, named, true) == Refusal.NONE;
    }

    @Test
    void thePointsTable() {
        Map<Integer, List<String>> table = Map.of(
                1, List.of("BREAD", "BAKED_POTATO", "COOKIE", "APPLE", "CARROT", "COOKED_COD", "COOKED_SALMON",
                        "MELON_SLICE"),
                2, List.of("COOKED_CHICKEN", "COOKED_BEEF", "COOKED_PORKCHOP", "COOKED_MUTTON", "COOKED_RABBIT",
                        "MUSHROOM_STEW", "BEETROOT_SOUP", "GOLDEN_CARROT"),
                3, List.of("PUMPKIN_PIE", "RABBIT_STEW"),
                6, List.of("CAKE"));
        int n = 0;
        for (Map.Entry<Integer, List<String>> e : table.entrySet()) {
            for (String m : e.getValue()) {
                assertEquals(e.getKey(), FeastRules.points(m), m);
                n++;
            }
        }
        assertEquals(n, FeastRules.POINTS.size(), "nothing else is food for the Feast");
        for (String not : List.of("BEEF", "GOLDEN_APPLE", "PUMPKIN", "WHEAT", "POTATO", "DIAMOND", "ROTTEN_FLESH")) {
            assertEquals(0, FeastRules.points(not), not + " is not");
        }
        assertEquals(0, FeastRules.points(null));
    }

    @Test
    void basketTreatsAreWelcomeAndEveryOtherSpecialThingIsRefused() {
        assertTrue(takes("BREAD", false, false, false, false, false), "plain bread");
        assertTrue(takes("PUMPKIN_PIE", true, true, false, false, true), "a basket pie: a prize with a name, welcome");
        assertTrue(takes("BREAD", true, true, false, false, true), "her cornbread");
        assertEquals(Refusal.CANDY, FeastRules.refusal("APPLE", false, true, true, false, true, true), "a candy apple");
        assertEquals(Refusal.KIT, FeastRules.refusal("BREAD", false, false, false, true, false, true), "a kit's bread");
        assertEquals(Refusal.PRIZE, FeastRules.refusal("CAKE", false, true, false, false, false, true), "a prize");
        assertEquals(Refusal.NAMED, FeastRules.refusal("COOKED_BEEF", false, false, false, false, true, true),
                "a named steak");
        assertEquals(Refusal.NOT_FOOD, FeastRules.refusal("DIAMOND", false, false, false, false, false, true));
        assertEquals(Refusal.NOT_FOOD, FeastRules.refusal("GOLDEN_APPLE", true, true, false, false, true, true),
                "a treat that isn't food for the Feast is still refused");
        assertEquals(Refusal.CLOSED, FeastRules.refusal("BREAD", false, false, false, false, false, false),
                "closed: nothing goes in");
        // there is no Creative argument at all: Creative is never a refusal
    }

    @Test
    void oneItemATapAndTheStackOnASneakUnderTheDaysPoints() {
        assertEquals(1, FeastRules.want(12, 1, false, 40), "a tap: one");
        assertEquals(12, FeastRules.want(12, 1, true, 40), "a sneak: the stack");
        assertEquals(3, FeastRules.want(16, 3, true, 10), "30 used of 40: 10 left, a pie is 3, so 3 pies");
        assertEquals(1, FeastRules.want(16, 3, false, 10), "a tap still one");
        assertEquals(0, FeastRules.want(1, 3, false, 2), "2 left and a pie is 3: full for today, nothing taken");
        assertEquals(0, FeastRules.want(5, 1, true, 0), "nothing left");
        assertEquals(64, FeastRules.want(64, 1, true, -1), "no limit");
        assertEquals(0, FeastRules.want(0, 1, false, 40), "an empty hand");
    }

    @Test
    void itemsTakenRoundUp() {
        assertEquals(3, FeastRules.taken(9, 3));
        assertEquals(3, FeastRules.taken(7, 3), "7 points of pies: 3 pies, never points without food");
        assertEquals(1, FeastRules.taken(1, 6));
        assertEquals(0, FeastRules.taken(0, 3));
    }

    @Test
    void thePlates() {
        assertEquals(0, FeastRules.plates(0, 150, 20));
        assertEquals(0, FeastRules.plates(7, 150, 20), "under a twentieth");
        assertEquals(1, FeastRules.plates(8, 160, 20), "a twentieth of 160 is 8");
        assertEquals(10, FeastRules.plates(75, 150, 20), "half");
        assertEquals(19, FeastRules.plates(149, 150, 20));
        assertEquals(20, FeastRules.plates(150, 150, 20), "the goal");
        assertEquals(20, FeastRules.plates(400, 150, 20), "over the goal");
        assertTrue(FeastRules.filled(150, 150) && !FeastRules.filled(149, 150));
        for (int i = 0; i < 20; i++) {
            assertTrue(FeastRules.points(FeastRules.kindAt(i)) > 0, "plate " + i + " shows food");
        }
        assertEquals("&6Family Feast &f137 / 150", FeastRules.line(137, 150));
        assertEquals("&6Family Feast &f150 / 150", FeastRules.line(171, 150), "never past the goal");
        assertEquals("&a" + "|".repeat(10) + "&8" + "|".repeat(10), FeastRules.bar(75, 150));
        assertTrue(FeastRules.pitch(0, 150) < FeastRules.pitch(75, 150) && FeastRules.pitch(150, 150) == 2.0f,
                "the chime rises");
    }

    @Test
    void theThankfulLine() {
        assertEquals("Lily", FeastRules.thankful(List.of("Lily")));
        assertEquals("Dad and Lily", FeastRules.thankful(List.of("Dad", "Lily")));
        assertEquals("Dad, Sam, Lee and Lily", FeastRules.thankful(List.of("Dad", "Sam", "Lee", "Lily")));
        assertEquals("&6Thankful for: &fDad, Sam, Lee and Lily&6!",
                FeastRules.thankfulLine(List.of("Dad", "Sam", "Lee", "Lily")));
        assertTrue(FeastRules.thankfulLine(List.of("A", "B")).chars().allMatch(c -> c < 128), "plain ASCII");
    }

    @Test
    void theOrderOfFirstGifts() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertEquals(List.of(b, a, c), FeastRules.ordered(List.of(b, a), List.of(a, b, c)),
                "the order first, then any helper it missed");
        assertEquals(List.of(a), FeastRules.ordered(List.of(c, a), List.of(a)), "only helpers");
        String text = FeastRules.orderText(List.of(a, b));
        assertEquals(List.of(a, b), FeastRules.orderOf(text));
        assertEquals(List.of(a), FeastRules.orderOf(a + ",junk," + a), "junk skipped, each once");
        assertEquals(List.of(), FeastRules.orderOf(null));
    }

    @Test
    void eachCeremonyGate() {
        assertTrue(FeastRules.roomCeremonyTurn(true, true, false, false, true));
        assertFalse(FeastRules.roomCeremonyTurn(false, true, false, false, true), "the room closed");
        assertFalse(FeastRules.roomCeremonyTurn(true, false, false, false, true), "no unseen helper inside");
        assertFalse(FeastRules.roomCeremonyTurn(true, true, true, false, true), "the restart hold");
        assertFalse(FeastRules.roomCeremonyTurn(true, true, false, true, true), "a photo waits");
        assertFalse(FeastRules.roomCeremonyTurn(true, true, false, false, false), "a minute between two");

        assertTrue(FeastRules.personalMomentDue(true, true, false, true));
        assertFalse(FeastRules.personalMomentDue(false, true, false, true), "not full");
        assertFalse(FeastRules.personalMomentDue(true, false, false, true), "not a helper");
        assertFalse(FeastRules.personalMomentDue(true, true, true, true), "seen");
        assertFalse(FeastRules.personalMomentDue(true, true, false, false), "not home: later");

        assertTrue(FeastRules.miniDue(true, true, false, true, true));
        assertFalse(FeastRules.miniDue(true, true, true, true, true), "had it");
        assertFalse(FeastRules.miniDue(true, true, false, false, true), "not home");
        assertFalse(FeastRules.miniDue(true, true, false, true, false), "a test edition has none");
        assertFalse(FeastRules.miniDue(false, true, false, true, true), "not full");

        assertTrue(FeastRules.paid(true, 0), "Creative helpers paid (shipped)");
        assertFalse(FeastRules.paid(false, 0), "the knob off: a Creative-only helper isn't");
        assertTrue(FeastRules.paid(false, 1), "one gift outside Creative is enough");
    }

    @Test
    void everyNameIsUnderTheEdition() {
        assertEquals("feast:2026", FeastRules.board("2026"));
        assertEquals("feast:test:d20780", FeastRules.dayBoard("test", 20780));
        assertEquals("feast:test:seen", FeastRules.seenKey("test"));
        assertEquals("feast:test:room_seen", FeastRules.roomSeenKey("test"));
        assertEquals("feast:test:survival", FeastRules.survivalKey("test"));
        assertEquals("feast:test:mini", FeastRules.miniKey("test"));
        assertEquals("feast-test", FeastRules.eventId("test"));
        assertEquals("feast.test.done", FeastRules.doneKey("test"));
        assertEquals("feast.test.home_shown", FeastRules.homeShownKey("test"));
        assertEquals("feast.test.order", FeastRules.orderKey("test"));
        assertTrue(FeastRules.dayBoard("2026", 1).startsWith(FeastRules.board("2026") + ":"));
    }

    @Test
    void aYearsEditionHasATurkeyMiniAndCantBeResetOncePaid() {
        assertEquals("turkey_mini_2026", FeastRules.miniId("2026"));
        assertNull(FeastRules.miniId("test"), "a test never mints a real Mini");
        assertNull(FeastRules.miniId("20261"));
        assertTrue(FeastRules.resetAllowed("2026", 0, false, false), "before the table filled");
        assertFalse(FeastRules.resetAllowed("2026", 1, true, true), "never twice for the real one");
        assertFalse(FeastRules.resetAllowed("2026", 0, true, false), "filled with tokens: 0: no row, still refused");
        assertFalse(FeastRules.resetAllowed("2026", 0, false, true), "a Turkey Mini given: refused");
        assertTrue(FeastRules.resetAllowed("test", 4, true, true), "a test, always");
    }

    @Test
    void thePotsSpot() {
        FeastRules.Spot s = new FeastRules.Spot("world", 10, 64, -3);
        assertEquals("world,10,64,-3", s.text());
        assertEquals(s, FeastRules.Spot.of(s.text()));
        assertTrue(s.is("WORLD", 10, 64, -3));
        assertFalse(s.is("world", 10, 65, -3));
        assertNull(FeastRules.Spot.of("world,1,2"));
        assertNull(FeastRules.Spot.of("world,a,2,3"));
        assertNull(FeastRules.Spot.of(null));
    }

    @Test
    void aBedrockTapIsAlwaysOneItemEvenCrouched() {
        assertTrue(FeastRules.wholeStack(true, false), "a Java sneak-tap: the stack");
        assertFalse(FeastRules.wholeStack(true, true), "crouch stays on by itself on Bedrock: one item");
        assertFalse(FeastRules.wholeStack(false, false));
        assertEquals(1, FeastRules.want(13, 3, FeastRules.wholeStack(true, true), 40), "13 pies held, one goes in");
    }
}
