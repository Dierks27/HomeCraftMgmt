package com.dierks.homecraft.graves;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code graves:} section: the shipped file, an absent section, and values out of range. */
class GraveSettingsTest {

    @Test
    void theShippedSectionIsTheDefaults() throws Exception {
        YamlConfiguration c;
        try (Reader r = new InputStreamReader(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8)) {
            c = YamlConfiguration.loadConfiguration(r);
        }
        assertTrue(c.isConfigurationSection("graves"), "config.yml ships a graves: section");
        List<String> warnings = new ArrayList<>();
        assertEquals(GraveSettings.DEFAULTS, GraveSettings.read(c.getConfigurationSection("graves"), warnings::add));
        assertEquals(List.of(), warnings);
        GraveSettings d = GraveSettings.DEFAULTS;
        assertTrue(d.enabled());
        assertEquals(100, d.xpPercent());
        assertEquals(0, d.unlockAfterMinutes(), "never: owner and admins only");
        assertEquals(0, d.expireAfterHours(), "off by default");
        assertEquals(Set.of("fairyhub"), d.disabledWorlds(), "fairyhub keeps its keep_inventory gamerule");
        assertTrue(d.worldDisabled("fairyhub"));
        assertTrue(d.keepInventoryMakesGrave(), "everyone walks back for their things");
        assertTrue(d.compass());
    }

    @Test
    void anAbsentSectionIsTheDefaults() {
        assertEquals(GraveSettings.DEFAULTS, GraveSettings.read(null, s -> { }));
    }

    @Test
    void valuesOutOfRangeComeIntoRangeWithAWarning() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("graves.enabled", false);
        c.set("graves.xp_percent", 250);
        c.set("graves.unlock_after_minutes", -5);
        c.set("graves.expire_after_hours", -1);
        c.set("graves.disabled_worlds", List.of("FairyHub", " sky ", ""));
        c.set("graves.sneak_opens_menu", false);
        c.set("graves.keep_inventory_makes_grave", false);
        c.set("graves.compass", false);
        List<String> warnings = new ArrayList<>();
        GraveSettings s = GraveSettings.read(c.getConfigurationSection("graves"), warnings::add);
        assertFalse(s.enabled());
        assertEquals(100, s.xpPercent());
        assertEquals(0, s.unlockAfterMinutes());
        assertEquals(0, s.expireAfterHours());
        assertFalse(s.sneakOpensMenu());
        assertFalse(s.keepInventoryMakesGrave());
        assertFalse(s.compass());
        assertEquals(3, warnings.size());
        assertTrue(s.worldDisabled("fairyhub"), "world names compare without case");
        assertTrue(s.worldDisabled("Sky"));
        assertFalse(s.worldDisabled("lilahs_world"));
    }
}
