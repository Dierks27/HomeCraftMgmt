package com.dierks.homecraft.config;

import com.dierks.homecraft.mini.EventMinis;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Admin Studio writes a holiday Mini's {@code event_only}, {@code earn_from} and {@code earn_to}
 * back unchanged, through a real YAML save and load, so an edit never turns it into a Mini packs
 * and crates could give.
 */
class MiniCatalogWriterTest {

    private static final MiniDef TURKEY = new MiniDef("turkey_mini_2026", "Turkey Mini 2026", "Holiday Collection",
            "HOLIDAY", Rarity.EPIC, MiniType.HEAD, "abc", 20, 0, false, List.of(), true,
            LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 29));

    @Test
    void aHolidayMiniSurvivesASaveAndLoad() throws InvalidConfigurationException {
        YamlConfiguration out = new YamlConfiguration();
        out.set("minis.series", List.of(Map.of("name", "Holiday Collection", "rarity", "EPIC",
                "entries", List.of(MiniCatalogWriter.entry(TURKEY)))));
        YamlConfiguration in = new YamlConfiguration();
        in.loadFromString(out.saveToString());

        Map<?, ?> series = in.getMapList("minis.series").get(0);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) series.get("entries")).get(0);
        List<String> warns = new ArrayList<>();
        EventMinis.Window w = EventMinis.readWindow(row, "turkey_mini_2026", warns::add);
        assertEquals(new EventMinis.Window(true, TURKEY.earnFrom(), TURKEY.earnTo()), w);
        assertEquals(List.of(), warns);
    }

    @Test
    void theBundledHolidayCollectionReadsWithNoWarn() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (java.io.InputStream in = MiniCatalogWriterTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            c.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        List<String> warns = new ArrayList<>();
        EventMinis.Window turkey = null;
        for (Map<?, ?> series : c.getMapList("minis.series")) {
            for (Object o : (List<?>) series.get("entries")) {
                Map<?, ?> row = (Map<?, ?>) o;
                EventMinis.Window w = EventMinis.readWindow(row, String.valueOf(row.get("name")), warns::add);
                if ("turkey_mini_2026".equals(row.get("id"))) {
                    turkey = w;
                }
            }
        }
        assertEquals(List.of(), warns);
        assertEquals(new EventMinis.Window(true, TURKEY.earnFrom(), TURKEY.earnTo()), turkey);
    }

    @Test
    void anOrdinaryMiniIsWrittenAsBefore() {
        MiniDef fox = new MiniDef("fox", "Fox", "Woodland Critters", "ANIMAL", Rarity.COMMON, MiniType.HEAD, "",
                -1, 0, false, List.of("woods"));
        Map<String, Object> m = MiniCatalogWriter.entry(fox);
        assertEquals(List.of("id", "name", "category", "rarity", "type", "texture", "cap", "price", "craftable",
                "tags"), new ArrayList<>(m.keySet()));
        assertFalse(m.containsKey("event_only"));
    }
}
