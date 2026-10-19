package com.dierks.homecraft.arcade;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No configured row can be a bundle (HALLOWEEN-SPEC E18): a prize is kept on death, so a prize bundle
 * would be a keep-inventory bag for anything. And the cap's trim keys, as the registry names them.
 * {@code isItem()} and {@code isEdible()} need a server, so a kept material is checked by its name rule.
 */
class PrizeItemsSafeTest {

    @Test
    void bundlesFallBack() {
        assertEquals(Material.SUGAR, PrizeItems.safe(Material.BUNDLE, Material.SUGAR));
        assertEquals(Material.SUGAR, PrizeItems.safe(Material.ORANGE_BUNDLE, Material.SUGAR));
        assertEquals(Material.COMPASS, PrizeItems.safe(Material.WHITE_BUNDLE, Material.COMPASS));
        assertEquals(Material.SUGAR, PrizeItems.safe(null, Material.SUGAR));
    }

    @Test
    void theNameRule() {
        assertTrue(PrizeItems.refusedName("BUNDLE"));
        assertTrue(PrizeItems.refusedName("ORANGE_BUNDLE"));
        assertTrue(PrizeItems.refusedName("WATER_BUCKET"));
        assertTrue(PrizeItems.refusedName("ENDER_PEARL"));
        assertFalse(PrizeItems.refusedName("SUGAR"), "sugar stays");
        assertFalse(PrizeItems.refusedName("COMPASS"));
        assertFalse(PrizeItems.refusedName("HEART_OF_THE_SEA"));
    }

    @Test
    void trimKeys() {
        assertEquals(NamespacedKey.minecraft("wild"), PrizeItems.trimKey("wild"));
        assertEquals(NamespacedKey.minecraft("emerald"), PrizeItems.trimKey(" Emerald "));
        assertEquals(NamespacedKey.minecraft("wild"), PrizeItems.trimKey("minecraft:wild"));
        assertNull(PrizeItems.trimKey(null));
        assertNull(PrizeItems.trimKey(""));
        assertNull(PrizeItems.trimKey("not a key!"));
    }
}
