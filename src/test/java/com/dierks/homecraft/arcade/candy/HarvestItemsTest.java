package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.Collectors;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Harvest Basket's treats as text (0.38.6): ids, names and lore from the event's year, the order they fall
 * in, and that no config row can take their ids. The items themselves need a server.
 */
class HarvestItemsTest {

    @Test
    void theIdsComeFromTheYear() {
        assertEquals("harvest_cornbread_2026", HarvestItems.Kind.CORNBREAD.id(2026));
        assertEquals("harvest_pie_2026", HarvestItems.Kind.PIE.id(2026));
        assertEquals("harvest_potato_2026", HarvestItems.Kind.POTATO.id(2026));
        assertEquals("harvest_golden_pie_2026", HarvestItems.goldenId(2026));
        assertEquals("harvest_pie_2027", HarvestItems.Kind.PIE.id(2027), "next year's are their own");
    }

    @Test
    void theNamesCarryTheYearForBedrock() {
        assertEquals("&eCornbread 2026", HarvestItems.Kind.CORNBREAD.name(2026));
        assertEquals("&6Pumpkin Pie 2026", HarvestItems.Kind.PIE.name(2026));
        assertEquals("&6Baked Potato 2026", HarvestItems.Kind.POTATO.name(2026));
        assertEquals("&6&lGolden Pie 2026", HarvestItems.goldenName(2026));
        for (HarvestItems.Kind k : HarvestItems.Kind.values()) {
            assertTrue(k.name(2026).contains("2026"), k + ": a Bedrock player sees lore only on a long press");
            assertTrue(k.name(2026).chars().allMatch(c -> c < 0x80), k + ": plain letters");
        }
        assertEquals(List.of("&7Thanksgiving 2026", "&7Eat it for a treat!", "&8Arcade prize"),
                HarvestItems.lore(2026, false));
        assertEquals(List.of("&7Thanksgiving 2026", "&7Eat it for a treat!", "&eVery rare!", "&8Arcade prize"),
                HarvestItems.lore(2026, true));
    }

    @Test
    void threeTreatsFallInTurn() {
        assertEquals(HarvestItems.Kind.CORNBREAD, HarvestItems.kindAt(0));
        assertEquals(HarvestItems.Kind.PIE, HarvestItems.kindAt(1));
        assertEquals(HarvestItems.Kind.POTATO, HarvestItems.kindAt(2), "the 3rd: golden until her first golden");
        assertEquals(HarvestItems.Kind.CORNBREAD, HarvestItems.kindAt(3), "a bigger shower goes round again");
        assertEquals(HarvestItems.Kind.CORNBREAD, HarvestItems.kindAt(-3), "never out of range");
        assertEquals(2, CandyRules.goldenIndex(3), "the 3rd of the shipped 3 is the guaranteed golden");
    }

    @Test
    void noConfigRowCanTakeTheirIds() {
        for (String id : List.of(HarvestItems.Kind.CORNBREAD.id(2026), HarvestItems.Kind.PIE.id(2026),
                HarvestItems.Kind.POTATO.id(2026), HarvestItems.goldenId(2026))) {
            assertTrue(Collectors.reserved(id), id + " is skipped as a config row");
        }
    }
}
