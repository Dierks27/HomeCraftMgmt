package com.dierks.homecraft.config;

import com.dierks.homecraft.config.TurkeyConfig.Turkey;
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
 * {@code arcade.events.turkey}: the bundled file (or a file without the section) is exactly the shipped settings
 * with no WARN; a bad date or a backwards window switches it off with one WARN; each number is squeezed into its
 * range, with one WARN naming the key.
 */
class TurkeyConfigTest {

    private static final String P = TurkeyConfig.PATH + ".";

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = TurkeyConfigTest.class.getResourceAsStream("/config.yml")) {
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
        c.loadFromString("arcade:\n  events:\n    turkey:\n" + section.indent(6));
        return c;
    }

    @Test
    void theBundledFileIsTheShippedSettings() throws Exception {
        List<String> warnings = new ArrayList<>();
        Turkey t = TurkeyConfig.parse(bundled(), warnings::add);
        assertEquals(Turkey.DEFAULTS, t);
        assertEquals(List.of(), warnings, "the bundled file parses without a WARN");
    }

    @Test
    void theShippedSettings() {
        Turkey d = Turkey.DEFAULTS;
        assertTrue(d.enabled());
        assertEquals(LocalDate.of(2026, 11, 22), d.from(), "the Turkey Mini's earn window");
        assertEquals(LocalDate.of(2026, 11, 29), d.to());
        assertEquals(240, d.everyMinutes(), "six runs a day");
        assertEquals(50, d.offsetMinutes());
        assertEquals(3, d.runMinutes());
        assertTrue(d.welcome());
        assertEquals(2.0, d.scale());
        assertEquals(3, d.tokens());
        assertEquals(6, d.dailyTokenCap(), "two catches a day pay");
        assertTrue(d.feather());
        assertEquals(5, d.miniChancePercent());
        assertEquals(2026, d.year());
        assertEquals(180_000L, d.runMillis());
        assertFalse(Turkey.OFF.enabled());
        assertTrue(d.open(LocalDate.of(2026, 11, 22)));
        assertTrue(d.open(LocalDate.of(2026, 11, 29)));
        assertFalse(d.open(LocalDate.of(2026, 11, 21)));
        assertFalse(d.open(LocalDate.of(2026, 11, 30)));
    }

    @Test
    void noSectionAndNoRootAreTheShippedSettings() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(Turkey.DEFAULTS, TurkeyConfig.parse(new YamlConfiguration(), warnings::add));
        assertEquals(Turkey.DEFAULTS, TurkeyConfig.parse(null, warnings::add));
        assertEquals(Turkey.DEFAULTS, TurkeyConfig.parse(yaml("enabled: true\n"), null), "a null warn is fine");
        assertEquals(List.of(), warnings);
    }

    @Test
    void theDatesQuotedOrBare() throws Exception {
        List<String> warnings = new ArrayList<>();
        Turkey t = TurkeyConfig.parse(yaml("from: \"2027-11-21\"\nto: 2027-11-28\n"), warnings::add);
        assertEquals(LocalDate.of(2027, 11, 21), t.from());
        assertEquals(LocalDate.of(2027, 11, 28), t.to(), "a bare YAML date");
        assertEquals(2027, t.year());
        assertTrue(t.enabled());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadDateSwitchesItOffWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Turkey t = TurkeyConfig.parse(yaml("from: \"22 Nov\"\n"), warnings::add);
        assertFalse(t.enabled());
        assertEquals(TurkeyConfig.DEFAULT_FROM, t.from(), "the shipped date stands in for the status");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith(P + "from '22 Nov' is not a date"), warnings.get(0));
        assertTrue(warnings.get(0).contains("Catch the Turkey is off"));

        warnings.clear();
        t = TurkeyConfig.parse(yaml("from: \"2026-11-29\"\nto: \"2026-11-22\"\n"), warnings::add);
        assertFalse(t.enabled(), "backwards");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("is after"), warnings.get(0));

        warnings.clear();
        t = TurkeyConfig.parse(yaml("from: \"2026-11-22\"\nto: \"2027-01-31\"\n"), warnings::add);
        assertTrue(t.enabled(), "a long window still runs");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("into another year"), warnings.get(0));
    }

    @Test
    void eachNumberIsSqueezedIntoItsRange() throws Exception {
        List<String> warnings = new ArrayList<>();
        Turkey t = TurkeyConfig.parse(yaml("""
                every_minutes: 5
                offset_minutes: 400
                run_minutes: 30
                scale: 9
                tokens: -1
                daily_token_cap: 99
                mini_chance_percent: 150
                """), warnings::add);
        assertEquals(30, t.everyMinutes());
        assertEquals(29, t.offsetMinutes(), "below the interval");
        assertEquals(10, t.runMinutes());
        assertEquals(3.0, t.scale());
        assertEquals(0, t.tokens());
        assertEquals(50, t.dailyTokenCap());
        assertEquals(100, t.miniChancePercent());
        assertEquals(7, warnings.size(), "one WARN each: " + warnings);
        assertTrue(warnings.get(0).startsWith(P + "every_minutes 5 is outside 30-360"), warnings.get(0));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith(P + "scale 9.0 is outside 1.0-3.0")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith(P + "mini_chance_percent 150 is outside 0-100")),
                warnings.toString());

        warnings.clear();
        t = TurkeyConfig.parse(yaml("tokens: lots\nscale: big\nwelcome: false\nfeather: false\n"), warnings::add);
        assertEquals(3, t.tokens());
        assertEquals(2.0, t.scale());
        assertFalse(t.welcome());
        assertFalse(t.feather());
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.equals(P + "tokens 'lots' is not a whole number; using 3.")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.equals(P + "scale 'big' is not a number; using 2.0.")),
                warnings.toString());
    }
}
