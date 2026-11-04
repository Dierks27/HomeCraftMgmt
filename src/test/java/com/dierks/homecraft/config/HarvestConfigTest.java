package com.dierks.homecraft.config;

import com.dierks.homecraft.config.CandyConfig.Candy;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code arcade.events.harvest} (0.38.6), read by the candy parser with the basket's own path, shipped settings
 * and name: the bundled section is exactly {@link CandyConfig#HARVEST_DEFAULTS} with no WARN, a missing date reads
 * as November's (never October's), and every WARN names the basket's key and the basket.
 */
class HarvestConfigTest {

    private static final String P = CandyConfig.HARVEST_PATH + ".";
    private static final String LABEL = "the Harvest Basket";

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = HarvestConfigTest.class.getResourceAsStream("/config.yml")) {
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
        c.loadFromString("arcade:\n  events:\n    harvest:\n" + section.indent(6));
        return c;
    }

    private static Candy parse(YamlConfiguration c, List<String> warnings) {
        return CandyConfig.parse(c, CandyConfig.HARVEST_PATH, CandyConfig.HARVEST_DEFAULTS, LABEL, warnings::add);
    }

    @Test
    void theShippedSettings() {
        Candy h = CandyConfig.HARVEST_DEFAULTS;
        assertTrue(h.enabled());
        assertEquals(LocalDate.of(2026, 11, 1), h.from(), "open on the day the update goes in");
        assertEquals(LocalDate.of(2026, 11, 29), h.to());
        assertEquals(60, h.everyMinutes());
        assertEquals(40, h.offsetMinutes(), "never on the candy's :10");
        assertEquals(30, h.lastDayEveryMinutes());
        assertEquals(3, h.applesPerShower(), "cornbread, pie, potato");
        assertEquals(4, h.goldenPercent());
        assertEquals(3, h.goldenTokens());
        assertEquals(6, h.dailyTokenCap(), "at most 6 a day for an adult");
        assertEquals(26, h.goal(), "26 treats for the Pilgrim Hat");
        assertFalse(h.adminsSeeAll());
        assertEquals(2026, h.year());
    }

    @Test
    void theBundledSectionIsTheShippedSettings() throws Exception {
        YamlConfiguration c = bundled();
        List<String> warnings = new ArrayList<>();
        assertEquals(CandyConfig.HARVEST_DEFAULTS, parse(c, warnings));
        assertEquals(List.of(), warnings, "the bundled file parses without a WARN");
        assertEquals(List.of("enabled", "from", "to", "every_minutes", "offset_minutes", "last_day_every_minutes",
                        "apples_per_shower", "golden_percent", "golden_tokens", "daily_token_cap", "goal",
                        "admins_see_all"),
                new ArrayList<>(c.getConfigurationSection(CandyConfig.HARVEST_PATH).getKeys(false)),
                "every key, in the candy section's order");
        assertTrue(c.isString(P + "from") && c.isString(P + "to"), "the dates ship quoted");
    }

    @Test
    void aMissingSectionOrKeyReadsAsNovember() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(CandyConfig.HARVEST_DEFAULTS, parse(new YamlConfiguration(), warnings));
        assertSame(CandyConfig.HARVEST_DEFAULTS, CandyConfig.parse(null, CandyConfig.HARVEST_PATH,
                CandyConfig.HARVEST_DEFAULTS, LABEL, null));
        Candy noFrom = parse(yaml("to: \"2026-11-29\"\n"), warnings);
        assertEquals(LocalDate.of(2026, 11, 1), noFrom.from(), "not the candy's 1 Oct");
        assertTrue(noFrom.enabled());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadDateWarnNamesTheBasket() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy h = parse(yaml("from: \"2026-11-31\"\n"), warnings);
        assertFalse(h.enabled(), "off");
        assertEquals(1, warnings.size(), "one WARN: " + warnings);
        String w = warnings.get(0);
        assertTrue(w.contains("arcade.events.harvest.from") && w.contains("2026-11-31"), w);
        assertTrue(w.contains("The Harvest Basket is off"), w);
        assertTrue(w.contains("\"2026-11-29\""), "the example is the basket's own last day: " + w);
        assertFalse(w.contains("candy") || w.contains("Candy"), w);
        assertEquals(LocalDate.of(2026, 11, 1), h.from(), "the shipped date stands in");
        assertEquals(2026, h.year());
    }

    @Test
    void aBackwardsWindowAndAClampNameTheBasketsKeys() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy h = parse(yaml("from: \"2026-11-29\"\nto: \"2026-11-01\"\n"), warnings);
        assertFalse(h.enabled());
        assertEquals(List.of("arcade.events.harvest.from (2026-11-29) is after arcade.events.harvest.to (2026-11-01)"
                + " - the Harvest Basket is off."), warnings);
        warnings.clear();
        Candy capped = parse(yaml("daily_token_cap: 90\n"), warnings);
        assertEquals(50, capped.dailyTokenCap());
        assertEquals(List.of("arcade.events.harvest.daily_token_cap 90 is outside 0-50; using 50."), warnings);
    }

    @Test
    void theCandySectionIsReadAsBefore() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("arcade:\n  events:\n    harvest:\n      goal: 5\n");
        List<String> warnings = new ArrayList<>();
        assertEquals(Candy.DEFAULTS, CandyConfig.parse(c, warnings::add), "a harvest section never changes candy");
        assertEquals(5, parse(c, warnings).goal());
        assertEquals(List.of(), warnings);
    }
}
