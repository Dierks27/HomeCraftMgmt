package com.dierks.homecraft.config;

import com.dierks.homecraft.hunt.HuntMath;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code arcade.radar}, the Mini Radar toned down: what config.yml ships (a reading every 6 seconds for a minute,
 * no Burning! band, Hot under 32, Warm under 64, once per Mini), the code's defaults the same, each key with a
 * one-line comment, the seconds brought into range and every other bad value read as the shipped one with one WARN.
 * The old radar is still there when configured.
 */
class RadarConfigTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = RadarConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    private static ConfigurationSection section(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return c.getConfigurationSection("radar");
    }

    @Test
    void theShippedRadarReadsEverySixSecondsForAMinuteColdWarmHotOncePerMini() throws Exception {
        YamlConfiguration c = bundled();
        List<String> warnings = new ArrayList<>();
        PluginConfig.Radar read = PluginConfig.readRadar(c.getConfigurationSection("arcade.radar"), warnings::add);
        assertEquals(List.of(), warnings, "the shipped section reads without a WARN");
        assertEquals(PluginConfig.Radar.DEFAULTS, read, "and is the code's defaults");
        assertEquals(6, c.getInt("arcade.radar.interval_seconds"));
        assertEquals(60, c.getInt("arcade.radar.lasts_seconds"));
        assertEquals(0, c.getInt("arcade.radar.burning_under"), "no Burning! band");
        assertEquals(32, c.getInt("arcade.radar.hot_under"));
        assertEquals(64, c.getInt("arcade.radar.warm_under"));
        assertTrue(c.getBoolean("arcade.radar.one_per_hunt"));
        assertEquals(new PluginConfig.Radar(6, 60, new HuntMath.Bands(0, 32, 64), true), PluginConfig.Radar.DEFAULTS);
    }

    @Test
    void everyKeyHasItsOneLineCommentAndTheSectionItsHeader() throws Exception {
        YamlConfiguration c = bundled();
        String header = String.join(" ", c.getComments("arcade.radar"));
        assertTrue(header.contains("Mini Radar") && header.contains("never says which way"), header);
        for (String key : List.of("interval_seconds", "lasts_seconds", "burning_under", "hot_under", "warm_under",
                "one_per_hunt")) {
            List<String> inline = c.getInlineComments("arcade.radar." + key);
            assertEquals(1, inline.size(), key + " has one inline comment");
            assertFalse(inline.get(0).isBlank(), key);
        }
        assertTrue(c.getInlineComments("arcade.radar.interval_seconds").get(0).contains("(2-30)"));
        assertTrue(c.getInlineComments("arcade.radar.lasts_seconds").get(0).contains("0 = until the Mini is caught"));
        assertTrue(c.getInlineComments("arcade.radar.burning_under").get(0).contains("0 = no Burning band"));
    }

    @Test
    void anAbsentSectionOrKeyIsTheShippedRadar() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(PluginConfig.Radar.DEFAULTS, PluginConfig.readRadar(null, warnings::add));
        PluginConfig.Radar partial = PluginConfig.readRadar(section("radar:\n  interval_seconds: 10\n"),
                warnings::add);
        assertEquals(new PluginConfig.Radar(10, 60, HuntMath.Bands.SHIPPED, true), partial);
        assertEquals(List.of(), warnings);
    }

    @Test
    void theOldRadarIsStillThereWhenConfigured() throws Exception {
        List<String> warnings = new ArrayList<>();
        PluginConfig.Radar old = PluginConfig.readRadar(section("""
                radar:
                  interval_seconds: 2
                  lasts_seconds: 0
                  burning_under: 12
                  hot_under: 32
                  warm_under: 64
                  one_per_hunt: false
                """), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(new PluginConfig.Radar(2, 0, new HuntMath.Bands(12, 32, 64), false), old);
    }

    @Test
    void numbersOutsideTheirRangeAreBroughtInWithOneWarnEach() throws Exception {
        List<String> warnings = new ArrayList<>();
        PluginConfig.Radar r = PluginConfig.readRadar(section("""
                radar:
                  interval_seconds: 1
                  lasts_seconds: 9999
                  burning_under: -3
                  hot_under: 40
                  warm_under: 500
                """), warnings::add);
        assertEquals(new PluginConfig.Radar(2, 600, new HuntMath.Bands(0, 40, 64), true), r,
                "the seconds are brought into range; a band out of range is its shipped value");
        assertEquals(List.of("arcade.radar.interval_seconds 1 is outside 2-30; using 2.",
                "arcade.radar.lasts_seconds 9999 is outside 0-600; using 600.",
                "arcade.radar.burning_under -3 is outside 0-64; using 0.",
                "arcade.radar.warm_under 500 is outside 2-256; using 64."), warnings);

        warnings.clear();
        assertEquals(30, PluginConfig.readRadar(section("radar:\n  interval_seconds: 45\n"), warnings::add)
                .intervalSeconds());
        assertEquals(List.of("arcade.radar.interval_seconds 45 is outside 2-30; using 30."), warnings);
    }

    @Test
    void oneBandOutOfRangeIsOneWarnAndKeepsTheOwnersOtherBands() throws Exception {
        List<String> warnings = new ArrayList<>();
        PluginConfig.Radar r = PluginConfig.readRadar(section("""
                radar:
                  burning_under: 12
                  hot_under: 200
                  warm_under: 64
                """), warnings::add);
        assertEquals(new HuntMath.Bands(12, 32, 64), r.bands(),
                "Hot is its shipped 32; the owner's Burning! under 12 and Warm under 64 stay");
        assertEquals(List.of("arcade.radar.hot_under 200 is outside 1-128; using 32."), warnings,
                "exactly one WARN, and the value it names is the one used");

        warnings.clear();
        assertEquals(new HuntMath.Bands(0, 32, 64), PluginConfig.readRadar(section("radar:\n  hot_under: 0\n"),
                warnings::add).bands());
        assertEquals(List.of("arcade.radar.hot_under 0 is outside 1-128; using 32."), warnings);
    }

    @Test
    void theHeaderSaysWhatTheCodeDoes() throws Exception {
        String header = String.join(" ", bundled().getComments("arcade.radar")).replaceAll("\\s+", " ");
        assertTrue(header.contains("(Cold, Warm or Hot; Burning! too when burning_under is above 0)"), header);
        assertFalse(header.contains("Cold, Warm, Hot, Burning!"), "the shipped radar never reads Burning!");
        assertTrue(header.contains("A seconds value outside its range is brought into it, a band outside its range "
                + "is read as its shipped value"), header);
        assertTrue(header.contains("all three are read as the shipped ones with one WARN"), header);
    }

    @Test
    void aValueThatIsntAWholeNumberOrTrueOrFalseIsTheShippedOneWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        PluginConfig.Radar r = PluginConfig.readRadar(section("""
                radar:
                  interval_seconds: often
                  lasts_seconds: 4.5
                  hot_under: true
                  one_per_hunt: maybe
                """), warnings::add);
        assertEquals(PluginConfig.Radar.DEFAULTS, r);
        assertEquals(List.of("arcade.radar.interval_seconds 'often' isn't a whole number; using 6.",
                "arcade.radar.lasts_seconds '4.5' isn't a whole number; using 60.",
                "arcade.radar.hot_under 'true' isn't a whole number; using 32.",
                "arcade.radar.one_per_hunt 'maybe' isn't true or false; using true."), warnings);

        warnings.clear();
        assertEquals(20, PluginConfig.readRadar(section("radar:\n  lasts_seconds: \"20\"\n"), warnings::add)
                .lastsSeconds(), "a quoted whole number reads");
        assertEquals(List.of(), warnings);
    }

    @Test
    void bandsOutOfOrderAreTheShippedBandsWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        PluginConfig.Radar r = PluginConfig.readRadar(section("""
                radar:
                  burning_under: 40
                  hot_under: 32
                  interval_seconds: 8
                """), warnings::add);
        assertEquals(HuntMath.Bands.SHIPPED, r.bands(), "Hot no wider than Burning!: the shipped bands");
        assertEquals(8, r.intervalSeconds(), "the rest of the section still reads");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("arcade.radar bands burning_under 40, hot_under 32, warm_under 64 are "
                + "out of order"), warnings.get(0));
        assertTrue(warnings.get(0).endsWith("using 0, 32, 64."), warnings.get(0));

        warnings.clear();
        assertEquals(HuntMath.Bands.SHIPPED, PluginConfig.readRadar(section("radar:\n  hot_under: 64\n"),
                warnings::add).bands(), "Warm no wider than Hot");
        assertEquals(1, warnings.size());

        warnings.clear();
        assertEquals(new HuntMath.Bands(0, 10, 20), PluginConfig.readRadar(
                section("radar:\n  hot_under: 10\n  warm_under: 20\n"), warnings::add).bands(),
                "no Burning band (0) needs no order with Hot");
        assertEquals(List.of(), warnings);
    }

    @Test
    void theLoaderReadsTheSectionThroughIt() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/config/PluginConfig.java"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        assertTrue(src.contains("this.radar = readRadar(c.getConfigurationSection(\"arcade.radar\"), log::warning);"),
                "load() reads arcade.radar on every start and reload");
    }
}
