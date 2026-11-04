package com.dierks.homecraft.config;

import com.dierks.homecraft.config.FeastConfig.Feast;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code arcade.events.feast}: the bundled file (or a file without the section) is exactly the shipped settings with
 * no WARN; a bad date or a backwards window switches it off with one WARN; each number is squeezed into its range
 * with one WARN naming the key; an edition that can't name a Feast falls back to the year of {@code from}.
 */
class FeastConfigTest {

    private static final String P = FeastConfig.PATH + ".";

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = FeastConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    private static YamlConfiguration yaml(String section) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("arcade:\n  events:\n    feast:\n" + section.indent(6));
        return c;
    }

    @Test
    void theBundledFileIsTheShippedSettings() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(Feast.DEFAULTS, FeastConfig.parse(bundled(), warnings::add));
        assertEquals(List.of(), warnings, "the bundled file parses without a WARN");
    }

    @Test
    void theShippedSettings() {
        Feast d = Feast.DEFAULTS;
        assertTrue(d.enabled());
        assertEquals(LocalDate.of(2026, 11, 22), d.from(), "the Turkey Mini's earn window");
        assertEquals(LocalDate.of(2026, 11, 29), d.to());
        assertEquals("2026", d.edition());
        assertEquals(150, d.goal());
        assertEquals(40, d.dailyPointsPerPlayer());
        assertEquals(20, d.tokens());
        assertTrue(d.creativeHelpersPaid());
        assertFalse(Feast.OFF.enabled(), "off until config.yml is read");
        assertTrue(d.open(LocalDate.of(2026, 11, 22)) && d.open(LocalDate.of(2026, 11, 29)), "both ends in");
        assertFalse(d.open(LocalDate.of(2026, 11, 21)) || d.open(LocalDate.of(2026, 11, 30)), "outside: closed");
    }

    @Test
    void aFileWithoutTheSectionIsTheShippedSettings() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(Feast.DEFAULTS, FeastConfig.parse(new YamlConfiguration(), warnings::add));
        assertEquals(Feast.DEFAULTS, FeastConfig.parse(null, null));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadDateSwitchesItOffWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Feast f = FeastConfig.parse(yaml("from: \"22 Nov\""), warnings::add);
        assertFalse(f.enabled());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(P + "from") && warnings.get(0).contains("Family Feast"), warnings.get(0));
        assertEquals(FeastConfig.DEFAULT_FROM, f.from(), "the shipped date stands in");

        warnings.clear();
        Feast back = FeastConfig.parse(yaml("from: \"2026-11-29\"\nto: \"2026-11-22\""), warnings::add);
        assertFalse(back.enabled(), "backwards: off");
        assertEquals(1, warnings.size(), warnings.toString());

        warnings.clear();
        Feast bare = FeastConfig.parse(yaml("from: 2026-11-03\nto: 2026-11-05"), warnings::add);
        assertTrue(bare.enabled(), "a bare date reads too");
        assertEquals(LocalDate.of(2026, 11, 3), bare.from());
        assertEquals(List.of(), warnings);
    }

    @Test
    void numbersAreSqueezedIntoTheirRangesWithOneWarnEach() throws Exception {
        List<String> warnings = new ArrayList<>();
        Feast f = FeastConfig.parse(yaml("goal: 5\ndaily_points_per_player: -3\ntokens: 500"), warnings::add);
        assertEquals(10, f.goal());
        assertEquals(0, f.dailyPointsPerPlayer(), "0 means no limit");
        assertEquals(100, f.tokens());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(P + "goal"), warnings.get(0));

        warnings.clear();
        Feast words = FeastConfig.parse(yaml("goal: lots"), warnings::add);
        assertEquals(150, words.goal());
        assertEquals(1, warnings.size());
    }

    @Test
    void aTestEditionAndABadOne() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals("test", FeastConfig.parse(yaml("edition: \"test\""), warnings::add).edition());
        assertEquals(List.of(), warnings);

        for (String bad : List.of("\"Test Run\"", "\"\"", "\"a-b\"", "\"abcdefghijklmnopq\"")) {
            warnings.clear();
            Feast f = FeastConfig.parse(yaml("from: \"2027-11-21\"\nto: \"2027-11-28\"\nedition: " + bad),
                    warnings::add);
            assertEquals("2027", f.edition(), bad + ": the year of from");
            assertEquals(1, warnings.size(), bad + ": " + warnings);
            assertTrue(warnings.get(0).contains(P + "edition"), warnings.get(0));
        }
        warnings.clear();
        assertEquals("2027", FeastConfig.parse(yaml("from: \"2027-11-21\"\nto: \"2027-11-28\""), warnings::add)
                .edition(), "none set: the year of from, quietly");
        assertEquals(List.of(), warnings);
        assertTrue(FeastConfig.validEdition("test_2") && !FeastConfig.validEdition(null));
    }
}
