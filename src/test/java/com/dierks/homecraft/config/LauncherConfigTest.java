package com.dierks.homecraft.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What config.yml ships for the Firework Launcher (0.38.1, {@code arcade.launcher} and
 * {@code skins.firework_launcher}): a dropper, 3 placed a player, a Mega show's range of 32 blocks, a blank skin, and
 * the code's defaults the same. Every key reaches an existing server through the leaf backfill, with its comment.
 */
class LauncherConfigTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = LauncherConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    @Test
    void theShippedLauncherIsADropperThreeAPlayerWithinThirtyTwoBlocks() throws Exception {
        YamlConfiguration c = bundled();
        PluginConfig.Launcher d = PluginConfig.Launcher.DEFAULTS;
        assertEquals("DROPPER", c.getString("arcade.launcher.material"), "a dropper, as the owner asked");
        assertEquals(Material.DROPPER, d.block().material(), "and the code's default is the same");
        assertEquals("&6Firework Launcher", c.getString("arcade.launcher.name"));
        assertEquals(d.block().name(), c.getString("arcade.launcher.name"));
        assertEquals(3, c.getInt("arcade.launcher.per_player"), "three set up at once, admins too");
        assertEquals(d.perPlayer(), c.getInt("arcade.launcher.per_player"));
        assertEquals(32, c.getInt("arcade.launcher.range"));
        assertEquals(d.range(), c.getInt("arcade.launcher.range"));
        assertTrue(c.isSet("skins.firework_launcher"), "the skin slot ships");
        assertEquals("", c.getString("skins.firework_launcher"), "blank: the plain dropper, which Bedrock shows as is");
        assertTrue(String.join("\n", c.getComments("arcade.launcher")).contains("Mega Firework Show"),
                "with the comment the backfill carries over");
        assertTrue(c.getInlineComments("skins.firework_launcher").get(0).contains("arcade.launcher"));
    }

    @Test
    void perPlayerAndRangeAreBroughtIntoRangeWithOneWarn() {
        List<String> warnings = new ArrayList<>();
        assertEquals(3, PluginConfig.clamped("arcade.launcher.per_player", 3, 1, 10, warnings::add));
        assertEquals(List.of(), warnings, "in range: as it is, no WARN");
        assertEquals(1, PluginConfig.clamped("arcade.launcher.per_player", 0, 1, 10, warnings::add));
        assertEquals(10, PluginConfig.clamped("arcade.launcher.per_player", 50, 1, 10, warnings::add));
        assertEquals(64, PluginConfig.clamped("arcade.launcher.range", 500, 8, 64, warnings::add));
        assertEquals(8, PluginConfig.clamped("arcade.launcher.range", 2, 8, 64, warnings::add));
        assertEquals(List.of("arcade.launcher.per_player 0 is outside 1-10; using 1.",
                "arcade.launcher.per_player 50 is outside 1-10; using 10.",
                "arcade.launcher.range 500 is outside 8-64; using 64.",
                "arcade.launcher.range 2 is outside 8-64; using 8."), warnings, "one WARN each");
    }
}
