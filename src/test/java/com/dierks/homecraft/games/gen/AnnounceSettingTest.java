package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.fresh.announce} (EXTRAS E2): shipped on, read like every other Fresh Courses key,
 * and carried by every wither so turning it off survives the settings' other changes.
 */
class AnnounceSettingTest {

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = AnnounceSettingTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return GamesConfig.tree(c.getConfigurationSection("games.fresh"));
            }
        }
    }

    private static DailySettings parse(Map<String, Object> fresh, List<String> warns) {
        GamesConfig.Node n = new GamesConfig.Node("games.fresh", fresh, warns::add);
        return DailySettings.parse(n, DailySettings.defaults());
    }

    @Test
    void itShipsOnAndReadsOff() throws Exception {
        List<String> warns = new ArrayList<>();
        Map<String, Object> fresh = shipped();
        assertEquals(Boolean.TRUE, fresh.get("announce"), "config.yml ships games.fresh.announce: true");
        assertTrue(parse(fresh, warns).announce(), "read on");
        assertTrue(DailySettings.defaults().announce(), "the defaults say on");
        assertTrue(DailySettings.KEYS.contains("announce"), "a known key, so it is never reported as a typo");
        fresh.put("announce", false);
        assertFalse(parse(fresh, warns).announce(), "read off");
        assertEquals(List.of(), warns, "no WARN for either");
    }

    @Test
    void theWithersKeepIt() {
        DailySettings off = DailySettings.defaults().withAnnounce(false);
        assertFalse(off.withEnabled(true).withCadence(1).withWorld("games").withSlots(off.slots())
                .withBudget(off.budget()).withArchive(off.archive()).withRebuild(off.rollover(), null).announce(),
                "every wither carries announce");
        assertFalse(off.equals(DailySettings.defaults()), "settings that differ only in announce are not equal");
    }
}
