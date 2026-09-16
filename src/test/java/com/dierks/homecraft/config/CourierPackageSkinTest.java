package com.dierks.homecraft.config;

import com.dierks.homecraft.courier.CourierJob;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crate a courier is handed must wear the crate texture.
 *
 * <p>config.yml shipped three head textures under {@code skins.courier_package} — a parcel, a
 * box, a crate, one per distance band — and the skin loader read every other named slot
 * ({@code pallet_used}, {@code vending_upper}, {@code mailbox.*}) and not that one. So
 * {@code skinNamed("courier_package.local")} answered "", a blank value builds a plain player
 * head, and a plain player head is Steve. Every delivery in the plugin's life was made carrying
 * a severed Steve head in hand.
 *
 * <p>These pin both halves shut: the section is present and textured in the bundled file, and
 * the loader actually reads it. No server needed — {@link YamlConfiguration} is plain Java.
 */
class CourierPackageSkinTest {

    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = CourierPackageSkinTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.load(reader);
                return defaults;
            }
        }
    }

    /** Every band ships a texture, and it is a real minecraft-heads value rather than a note. */
    @Test
    void theBundledConfigTexturesEveryBand() throws Exception {
        YamlConfiguration config = bundled();
        for (CourierJob.Band band : CourierJob.Band.values()) {
            String path = "skins.courier_package." + band.configKey();
            String value = config.getString(path, "");
            assertNotNull(value, path + " is missing from the bundled config.yml");
            assertFalse(value.isBlank(),
                    path + " is blank, which hands the courier a default Steve head");
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            assertTrue(json.contains("textures.minecraft.net/texture/"),
                    path + " does not decode to a head texture URL: " + json);
        }
    }

    /** The loader reads the section — the step that was missing. */
    @Test
    void theLoaderReadsEveryBandFromTheBundledConfig() throws Exception {
        Map<String, String> named =
                PluginConfig.readCourierPackageSkins(bundled().getConfigurationSection("skins"));

        assertEquals(CourierJob.Band.values().length, named.size(),
                "expected one crate texture per band, got " + named.keySet());
        for (CourierJob.Band band : CourierJob.Band.values()) {
            String key = "courier_package." + band.configKey();
            assertEquals(bundled().getString("skins." + key), named.get(key),
                    key + " did not come through the loader");
        }
    }

    /** Bands are distinct: a local parcel should not be the same item as a long-haul crate. */
    @Test
    void eachBandGetsItsOwnCrate() throws Exception {
        Map<String, String> named =
                PluginConfig.readCourierPackageSkins(bundled().getConfigurationSection("skins"));
        assertEquals(named.values().size(), named.values().stream().distinct().count(),
                "two bands share a texture: " + named);
    }

    /** A single string in place of the section textures every band with it. */
    @Test
    void aLegacySingleValueTexturesEveryBand() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("skins:\n  courier_package: \"abc123\"\n");

        Map<String, String> named =
                PluginConfig.readCourierPackageSkins(config.getConfigurationSection("skins"));

        assertEquals(CourierJob.Band.values().length, named.size());
        for (CourierJob.Band band : CourierJob.Band.values()) {
            assertEquals("abc123", named.get("courier_package." + band.configKey()));
        }
    }

    /** No section, no skins block, an empty value: none of them may blow up on load. */
    @Test
    void anUnconfiguredServerLoadsCleanly() throws Exception {
        assertTrue(PluginConfig.readCourierPackageSkins(null).isEmpty());

        YamlConfiguration blank = new YamlConfiguration();
        blank.loadFromString("skins:\n  courier_package:\n    local: \"\"\n");
        assertTrue(PluginConfig.readCourierPackageSkins(blank.getConfigurationSection("skins"))
                .isEmpty(), "a blank value is not a texture");
    }
}
