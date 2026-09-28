package com.dierks.homecraft.muffler;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What config.yml ships for the Sound Muffler. Every key here reaches an existing server through
 * the leaf backfill, so the shipped values are what every server starts with.
 */
class MufflerConfigTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = MufflerConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    @Test
    void theShippedRangesAndVolumeAreOnesAPlayerCanActuallyPick() throws Exception {
        YamlConfiguration c = bundled();
        assertTrue(c.getBoolean("sound_muffler.enabled"));
        int radius = c.getInt("sound_muffler.default_radius");
        int max = c.getInt("sound_muffler.max_radius");
        assertTrue(radius >= Muffler.MIN_RADIUS && radius <= max, "default " + radius + " within 1.." + max);
        assertTrue(max <= Muffler.HARD_MAX_RADIUS, "max_radius " + max);
        assertTrue(Muffler.RANGE_STEPS.contains(radius), "the default range is one the Range button stops on");
        assertTrue(Muffler.QUIET_STEPS.contains(c.getInt("sound_muffler.default_quiet_percent")),
                "the default Quieter volume is one the button offers");
        assertDoesNotThrow(() -> Material.valueOf(c.getString("sound_muffler.block.material")));
    }

    @Test
    void theRecipeAndSkinSlotShip() throws Exception {
        YamlConfiguration c = bundled();
        assertEquals(List.of("WWW", "WNW", "WWW"), c.getStringList("recipes.sound_muffler.shape"));
        assertEquals("#wool", c.getString("recipes.sound_muffler.ingredients.W"));
        assertEquals("NOTE_BLOCK", c.getString("recipes.sound_muffler.ingredients.N"));
        assertTrue(c.isSet("skins.sound_muffler"), "the skin slot ships (blank = the plain block)");
    }
}
