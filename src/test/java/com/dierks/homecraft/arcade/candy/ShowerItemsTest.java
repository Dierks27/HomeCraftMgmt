package com.dierks.homecraft.arcade.candy;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each shower drops and taps (0.38.6). The items themselves need a server; what decides is pinned here:
 * candy eats a red apple on a tap and never a golden one, the basket eats any treat, the tap looks only at the
 * shower's own materials, and nothing the basket drops can be placed or planted (pinned by name: a material's
 * {@code isBlock()} needs a server).
 */
class ShowerItemsTest {

    @Test
    void candyEatsOnlyTheRedAppleOnATap() {
        assertTrue(CandyShowerItems.edible(true, false), "a Candy Apple");
        assertFalse(CandyShowerItems.edible(true, true), "a golden apple is always edible: vanilla eats it");
        assertFalse(CandyShowerItems.edible(false, false), "not candy");
    }

    @Test
    void theBasketEatsAnyTreatTheGoldenPieIncluded() {
        assertTrue(HarvestShowerItems.edible(true, false));
        assertTrue(HarvestShowerItems.edible(true, true), "a pie is not always edible on Bedrock");
        assertFalse(HarvestShowerItems.edible(false, false), "not a treat");
    }

    @Test
    void theTapLooksAtEachShowersOwnMaterials() {
        Set<Material> harvest = EnumSet.of(Material.BREAD, Material.PUMPKIN_PIE, Material.BAKED_POTATO,
                HarvestItems.GOLDEN_MATERIAL);
        assertEquals(harvest, new HarvestShowerItems().tapMaterials());
        Set<Material> candy = new CandyShowerItems().tapMaterials();
        assertTrue(candy.contains(Material.APPLE) && candy.contains(Material.BUNDLE)
                && candy.contains(Material.ORANGE_BUNDLE), "the apple and every bundle, as 0.38.5");
        assertFalse(candy.contains(Material.GOLDEN_APPLE), "a golden apple is never eaten by a tap");
        for (Material m : candy) {
            assertTrue(m == Material.APPLE || m.name().endsWith("BUNDLE"), m + " is the apple or a bundle");
        }
        for (Material m : harvest) {
            assertFalse(candy.contains(m), m + ": the candy shower never looks at a treat");
        }
    }

    @Test
    void nothingTheBasketDropsCanBePlacedOrPlanted() {
        List<String> never = List.of("PUMPKIN", "CARVED_PUMPKIN", "CARROT", "POTATO", "WHEAT", "WHEAT_SEEDS",
                "HAY_BLOCK", "CAKE");
        for (Material m : HarvestItems.MATERIALS) {
            assertFalse(never.contains(m.name()), m + " could be placed or planted, losing its tag");
        }
        for (HarvestItems.Kind k : HarvestItems.Kind.values()) {
            assertTrue(HarvestItems.MATERIALS.contains(k.material()), k + " is listed");
        }
        assertTrue(HarvestItems.MATERIALS.contains(HarvestItems.GOLDEN_MATERIAL));
        assertEquals(List.of(Material.BREAD, Material.PUMPKIN_PIE, Material.BAKED_POTATO, Material.PUMPKIN_PIE),
                HarvestItems.MATERIALS, "cornbread, pie, potato, and the Golden Pie");
    }
}
