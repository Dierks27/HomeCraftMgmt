package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.Collectors;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shower's items as text (HALLOWEEN-SPEC D13, D15): ids, names and lore from the event's year. The items
 * themselves need a server, so they are checked live (§6 Step 2); what goes on them is pinned here.
 */
class CandyItemsTest {

    @Test
    void theIdsComeFromTheYear() {
        assertEquals("candy_apple_2026", CandyItems.appleId(2026));
        assertEquals("candy_golden_2026", CandyItems.goldenId(2026));
        assertEquals("bag_trick_or_treat_2026", CandyItems.bagId(2026));
        assertEquals("candy_apple_2027", CandyItems.appleId(2027), "next year's candy is its own");
        assertEquals("bag_trick_or_treat_2027", CandyItems.bagId(2027), "and next year's bag a second collectible");
    }

    @Test
    void noConfigRowCanTakeTheirIds() {
        for (String id : List.of(CandyItems.appleId(2026), CandyItems.goldenId(2026), CandyItems.bagId(2026),
                CandyItems.bagId(2031))) {
            assertTrue(Collectors.reserved(id), id + " is skipped as a config row");
        }
    }

    @Test
    void theNamesAndLore() {
        assertEquals("&cCandy Apple", CandyItems.APPLE_NAME);
        assertEquals("&6Golden Candy Apple", CandyItems.GOLDEN_NAME);
        assertEquals("&6Trick-or-Treat Bag 2026", CandyItems.bagName(2026), "the year in the name: Bedrock");
        assertEquals(List.of("&7Halloween 2026", "&7Eat it for a treat!", "&8Arcade prize"),
                CandyItems.candyLore(2026, false));
        assertEquals(List.of("&7Halloween 2026", "&7Eat it for a treat!", "&eVery rare!", "&8Arcade prize"),
                CandyItems.candyLore(2026, true));
        assertEquals(List.of("&7You found 31 candy apples!", "&6Halloween 2026 collector", "&aYours for good!",
                "&7Right-click: trick or treat!", "&8Arcade prize"), CandyItems.bagLore(2026, 31));
        assertEquals("&7You found 40 candy apples!", CandyItems.bagLore(2026, 40).get(0), "the goal as set");
    }

    @Test
    void theRedApplesFoodIsAnApples() {
        assertEquals(4, CandyItems.NUTRITION);
        assertEquals(2.4f, CandyItems.SATURATION);
    }
}
