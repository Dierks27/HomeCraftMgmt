package com.dierks.homecraft.config;

import com.dierks.homecraft.config.CandyConfig.Candy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code arcade.events.candy} (HALLOWEEN-SPEC §2.2, D10, D18): the bundled section is exactly the shipped
 * settings with no WARN; a quoted or a bare date is read, a bad one or a backwards window turns the shower off
 * with one WARN; a long or year-crossing window runs with one WARN; and each number is squeezed into its range,
 * with one WARN naming the key.
 */
class CandyConfigTest {

    private static final String P = CandyConfig.PATH + ".";

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = CandyConfigTest.class.getResourceAsStream("/config.yml")) {
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
        c.loadFromString("arcade:\n  events:\n    candy:\n" + section.indent(6));
        return c;
    }

    private static Candy parse(YamlConfiguration c, List<String> warnings) {
        return CandyConfig.parse(c, warnings::add);
    }

    @Test
    void theBundledSectionIsTheShippedSettings() throws Exception {
        YamlConfiguration c = bundled();
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(c, warnings);
        assertEquals(Candy.DEFAULTS, candy);
        assertEquals(List.of(), warnings, "the bundled file parses without a WARN");
        assertEquals(List.of("enabled", "from", "to", "every_minutes", "offset_minutes", "last_day_every_minutes",
                        "apples_per_shower", "golden_percent", "golden_tokens", "daily_token_cap", "goal",
                        "admins_see_all"),
                new ArrayList<>(c.getConfigurationSection(CandyConfig.PATH).getKeys(false)), "every key, in order");
        assertTrue(c.isString(P + "from") && c.isString(P + "to"), "the dates ship quoted");
        assertTrue(candy.enabled());
        assertEquals(LocalDate.of(2026, 10, 1), candy.from());
        assertEquals(LocalDate.of(2026, 10, 31), candy.to());
        assertEquals(20, candy.everyMinutes());
        assertEquals(10, candy.offsetMinutes());
        assertEquals(10, candy.lastDayEveryMinutes());
        assertEquals(6, candy.applesPerShower());
        assertEquals(4, candy.goldenPercent());
        assertEquals(5, candy.goldenTokens());
        assertEquals(10, candy.dailyTokenCap());
        assertEquals(31, candy.goal());
        assertFalse(candy.adminsSeeAll(), "admins_see_all is off by default");
        assertEquals(2026, candy.year());
    }

    @Test
    void aMissingSectionReadsAsTheShippedSettings() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Candy.DEFAULTS, parse(new YamlConfiguration(), warnings));
        assertEquals(List.of(), warnings);
        assertSame(Candy.DEFAULTS, CandyConfig.parse(null, null));
    }

    @Test
    void theWindowOpensAndClosesByLocalDate() {
        Candy c = Candy.DEFAULTS;
        assertFalse(c.open(LocalDate.of(2026, 9, 30)));
        assertTrue(c.open(LocalDate.of(2026, 10, 1)));
        assertTrue(c.open(LocalDate.of(2026, 10, 31)));
        assertFalse(c.open(LocalDate.of(2026, 11, 1)));
        assertFalse(c.withEnabled(false).open(LocalDate.of(2026, 10, 15)), "enabled: false");
        assertFalse(Candy.OFF.open(LocalDate.of(2026, 10, 15)), "nothing runs before config.yml is read");
        assertTrue(c.lastDay(LocalDate.of(2026, 10, 31)));
        assertFalse(c.lastDay(LocalDate.of(2026, 10, 30)));
        assertFalse(c.lastDay(null));
    }

    @Test
    void enabledFalseIsOffWithNoWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(yaml("enabled: false\n"), warnings);
        assertFalse(candy.enabled());
        assertEquals(List.of(), warnings);
        assertEquals(Candy.DEFAULTS.withEnabled(false), candy, "the rest as shipped");
    }

    @Test
    void aBareDateIsReadAsTheUtcDate() throws Exception {
        // what Bukkit's loader makes of `to: 2026-10-31`: a java.util.Date at UTC midnight
        YamlConfiguration c = new YamlConfiguration();
        c.set(P + "from", Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        c.set(P + "to", Date.from(Instant.parse("2026-10-31T00:00:00Z")));
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(c, warnings);
        assertEquals(LocalDate.of(2026, 10, 1), candy.from());
        assertEquals(LocalDate.of(2026, 10, 31), candy.to(), "not 30 Oct, as a Chicago reading would make it");
        assertTrue(candy.enabled());
        assertEquals(List.of(), warnings);
        // and the bare dates as written in the file, whatever the loader turns them into
        Candy written = parse(yaml("from: 2026-10-01\nto: 2026-10-31\n"), warnings);
        assertEquals(LocalDate.of(2026, 10, 1), written.from());
        assertEquals(LocalDate.of(2026, 10, 31), written.to());
        assertTrue(written.enabled());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aBadDateTurnsTheShowerOffWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(yaml("from: \"2026-13-01\"\n"), warnings);
        assertFalse(candy.enabled(), "off");
        assertEquals(1, warnings.size(), "one WARN: " + warnings);
        assertTrue(warnings.get(0).contains(P + "from") && warnings.get(0).contains("2026-13-01"), warnings.get(0));
        assertEquals(2026, candy.year(), "the shipped date stands in, so the year still reads");

        warnings.clear();
        assertFalse(parse(yaml("to: \"Halloween\"\n"), warnings).enabled());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(P + "to"), warnings.get(0));

        warnings.clear();
        assertFalse(parse(yaml("to: 31\n"), warnings).enabled(), "a number is not a date");
        assertEquals(1, warnings.size());
    }

    @Test
    void aBackwardsWindowIsOffWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(yaml("from: \"2026-11-01\"\nto: \"2026-10-31\"\n"), warnings);
        assertFalse(candy.enabled());
        assertEquals(1, warnings.size(), "one WARN: " + warnings);
        assertTrue(warnings.get(0).contains(P + "from") && warnings.get(0).contains("after"), warnings.get(0));
        assertFalse(candy.open(LocalDate.of(2026, 10, 31)));
    }

    @Test
    void aLongOrYearCrossingWindowStillRunsWithOneWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy winter = parse(yaml("from: \"2026-12-20\"\nto: \"2027-01-06\"\n"), warnings);
        assertTrue(winter.enabled(), "it still runs");
        assertTrue(winter.open(LocalDate.of(2027, 1, 2)));
        assertEquals(2026, winter.year(), "the year comes from `from`");
        assertEquals(1, warnings.size(), "one WARN: " + warnings);
        assertTrue(warnings.get(0).contains("another year") && warnings.get(0).contains("2026"), warnings.get(0));

        warnings.clear();
        Candy autumn = parse(yaml("from: \"2026-09-01\"\nto: \"2026-11-30\"\n"), warnings);
        assertTrue(autumn.enabled());
        assertEquals(1, warnings.size(), "91 days: one WARN: " + warnings);
        assertTrue(warnings.get(0).contains("91 days"), warnings.get(0));

        warnings.clear();
        assertTrue(parse(yaml("from: \"2026-10-01\"\nto: \"2026-12-01\"\n"), warnings).enabled());
        assertEquals(List.of(), warnings, "62 days is not too long");
    }

    @Test
    void theNumbersAreClampedWithAWarnEach() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(yaml("""
                every_minutes: 5
                golden_percent: 150
                apples_per_shower: 0
                golden_tokens: 50
                daily_token_cap: 99
                goal: 500
                last_day_every_minutes: 5
                """), warnings);
        assertEquals(10, candy.everyMinutes(), "every 5 -> 10");
        assertEquals(100, candy.goldenPercent(), "golden 150 -> 100");
        assertEquals(1, candy.applesPerShower(), "apples 0 -> 1");
        assertEquals(20, candy.goldenTokens());
        assertEquals(50, candy.dailyTokenCap());
        assertEquals(200, candy.goal());
        assertEquals(10, candy.lastDayEveryMinutes());
        assertEquals(9, candy.offsetMinutes(), "the shipped offset 10 is under every 10 no more: 0-9");
        assertEquals(8, warnings.size(), "one WARN a key: " + warnings);
        for (String key : List.of("every_minutes", "golden_percent", "apples_per_shower", "golden_tokens",
                "daily_token_cap", "goal", "last_day_every_minutes", "offset_minutes")) {
            assertTrue(warnings.stream().anyMatch(w -> w.startsWith(P + key + " ")), key + " is named: " + warnings);
        }
        assertTrue(candy.enabled(), "a clamp never turns the shower off");
    }

    @Test
    void theOffsetStaysUnderTheIntervalAndZeroMeansAsEvery() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(yaml("every_minutes: 20\noffset_minutes: 25\nlast_day_every_minutes: 0\n"), warnings);
        assertEquals(19, candy.offsetMinutes());
        assertEquals(0, candy.lastDayEveryMinutes(), "0 = as every_minutes, no WARN");
        assertEquals(1, warnings.size(), warnings.toString());
        warnings.clear();
        assertEquals(0, parse(yaml("last_day_every_minutes: -3\n"), warnings).lastDayEveryMinutes());
        assertEquals(1, warnings.size(), warnings.toString());
        warnings.clear();
        assertEquals(120, parse(yaml("every_minutes: 500\n"), warnings).everyMinutes());
        assertEquals(1, warnings.size(), warnings.toString());
        warnings.clear();
        assertEquals(0, parse(yaml("offset_minutes: -1\n"), warnings).offsetMinutes());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aWordWhereANumberGoesIsTheShippedValueWithAWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        Candy candy = parse(yaml("goal: lots\napples_per_shower: \"8\"\n"), warnings);
        assertEquals(31, candy.goal());
        assertEquals(8, candy.applesPerShower(), "a quoted number is still a number");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(P + "goal"), warnings.get(0));
    }

    @Test
    void adminsSeeAllIsOffUnlessSet() throws Exception {
        assertFalse(parse(yaml("goal: 31\n"), new ArrayList<>()).adminsSeeAll());
        assertTrue(parse(yaml("admins_see_all: true\n"), new ArrayList<>()).adminsSeeAll());
    }

    @Test
    void nextYearIsANewYear() throws Exception {
        Candy next = parse(yaml("from: \"2027-10-01\"\nto: \"2027-10-31\"\n"), new ArrayList<>());
        assertEquals(2027, next.year(), "a 2027 counter, items and bag");
        assertTrue(next.open(LocalDate.of(2027, 10, 31)));
        assertFalse(next.open(LocalDate.of(2026, 10, 31)));
    }
}
