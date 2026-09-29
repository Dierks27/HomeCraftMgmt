package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.fresh}'s archive keys (GEN-SPEC-KEEP §1, §3, §4, §8): the shipped block reads as
 * forever, 26 on the website, a week for a recall, the Classics slots after the six normal ones and
 * 24 plots well apart; a keep area that overlaps a Fresh Courses area (or comes within 16 blocks)
 * gets one WARN and keeping is off; a Classics slot on top of another area is switched off alone; and
 * out-of-range numbers are clamped like every key.
 */
class ArchiveSettingsTest {

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = ArchiveSettingsTest.class.getResourceAsStream("/config.yml");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration c = new YamlConfiguration();
            c.load(r);
            return GamesConfig.tree(c.getConfigurationSection("games.fresh"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String dotted, Object value) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> m = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = m.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                m.put(parts[i], next);
            }
            m = (Map<String, Object>) next;
        }
        m.put(parts[parts.length - 1], value);
    }

    private static DailySettings with(String key, Object value, List<String> warns) throws Exception {
        Map<String, Object> fresh = shipped();
        if (key != null) {
            put(fresh, key, value);
        }
        GamesConfig.Node n = new GamesConfig.Node("games.fresh", fresh, warns::add);
        return DailySettings.parse(n, DailySettings.defaults());
    }

    @Test
    void theShippedArchiveKeysReadAsTheDefaults() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings.Archive a = with(null, null, warns).archive();
        assertEquals(List.of(), warns, "no WARN");
        assertEquals(DailySettings.Archive.shipped(), a, "exactly the shipped archive settings");
        assertEquals(0, a.keepDays(), "archived forever");
        assertEquals(26, a.feedHistory(), "26 past courses a slot on the website");
        assertEquals(7, a.classicDays(), "a recall stays a week");
        assertEquals(new KeepArea(4096, 128, 5376, 24), a.keep(), "24 plots, well apart");
        assertNull(a.keepProblem(), "keeping is on");
        for (Slots.Def d : Slots.CLASSICS) {
            assertArrayEquals(d.origin(), a.classic(d.id()).origin(), d.id() + " where the code places it");
            assertTrue(a.classic(d.id()).enabled(), d.id() + " is usable");
        }
        assertTrue(DailySettings.KEYS.containsAll(List.of("archive.keep", "feed_history", "classics.days",
                "classics.slots.fresh_classic_parkour.origin", "keep.area", "keep.max_plots")), "every new key is known");
    }

    @Test
    void aKeepAreaOnTopOfAFreshCoursesAreaTurnsKeepingOff() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings.Archive a = with("keep.area", List.of(4096, 160, 4096), warns).archive();
        assertNotNull(a.keepProblem(), "an area on the parkour halves can't be used");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        assertTrue(warns.get(0).contains("games.fresh.keep.area") && warns.get(0).contains("keeping a course is off"),
                warns.get(0));
        warns.clear();
        assertNotNull(with("keep.area", List.of(4096, 128, 5060), warns).archive().keepProblem(),
                "4 blocks past Classic Sky Rings is too close (16 are needed)");
        warns.clear();
        assertNull(with("keep.area", List.of(4096, 128, 5072), warns).archive().keepProblem(), "16 past is fine");
        assertEquals(List.of(), warns, "without a WARN");
        warns.clear();
        assertNotNull(with("keep.area", "nowhere", warns).archive().keepProblem(), "junk turns keeping off too");
        assertFalse(warns.isEmpty(), "with a WARN");
    }

    @Test
    void aClassicsSlotOnAnotherAreaIsOffAloneAndNumbersAreClamped() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with("classics.slots.fresh_classic_golf.origin", List.of(4096, 160, 4096), warns);
        assertFalse(s.archive().classic("fresh_classic_golf").enabled(), "Classic Golf on the parkour is off");
        assertTrue(s.archive().classic("fresh_classic_parkour").enabled(), "the others keep working");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        warns.clear();
        assertEquals(365, with("classics.days", 1000, warns).archive().classicDays(), "at most a year");
        assertEquals(1, warns.size(), "a clamp WARNs once");
        warns.clear();
        assertEquals(100, with("keep.max_plots", 5000, warns).archive().keep().maxPlots(), "at most 100 plots");
        warns.clear();
        assertEquals(30, with("archive.keep", 30, warns).archive().keepDays(), "a retention in days");
        assertEquals(List.of(), warns, "in range: no WARN");
    }
}
