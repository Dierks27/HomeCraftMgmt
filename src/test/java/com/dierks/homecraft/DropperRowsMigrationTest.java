package com.dierks.homecraft;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 18 (EVENTS-DROPPER-SPEC §B.1.8): the Dropper's "Reach the bottom of a Dropper with no
 * bonks" reaches an upgraded server's achievements, by revision 17's rule: a list still exactly as
 * shipped gains it at its end; one the owner changed is theirs, kept, with one WARN and the line to
 * paste; a row already there is never added twice. Revision 17's step neither adds it nor counts it as
 * part of the list it compares with.
 */
class DropperRowsMigrationTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = DropperRowsMigrationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    /** A revision-17 file: the bundled one without the Dropper's row. */
    private static YamlConfiguration rev17() throws Exception {
        YamlConfiguration c = bundled();
        c.set("config_revision", 17);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.achievements")) {
            if (!ArcadeConfigMigration.DROPPER_ACHIEVEMENTS.contains(String.valueOf(row.get("id")))) {
                out.add(copy(row));
            }
        }
        c.set("arcade.achievements", out);
        return c;
    }

    private static Map<String, Object> copy(Map<?, ?> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(String.valueOf(k), v));
        return m;
    }

    private static List<String> ids(YamlConfiguration c) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.achievements")) {
            out.add(String.valueOf(row.get("id")));
        }
        return out;
    }

    private static List<String> warns(List<String> log) {
        return log.stream().filter(l -> l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    @Test
    void aShippedListGainsTheDroppersRowAtItsEnd() throws Exception {
        YamlConfiguration onDisk = rev17();
        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        assertEquals(ids(bundled()), ids(onDisk), "the list is now exactly what a fresh install ships");
        assertEquals("game_dropper_clean", ids(onDisk).get(ids(onDisk).size() - 1), "the new row at its end");
        assertEquals(List.of(), warns(log), "nothing was the owner's: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("game_dropper_clean")), "logged: " + log);
        assertEquals(18, onDisk.getInt("config_revision"), "stamped 18");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second pass is a no-op");
    }

    @Test
    void anEditedListIsKeptWithTheLineToPaste() throws Exception {
        YamlConfiguration onDisk = rev17();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.achievements")) {
            if (!"jackpot".equals(row.get("id"))) {
                list.add(copy(row));
            }
        }
        onDisk.set("arcade.achievements", list);
        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        assertFalse(ids(onDisk).contains("game_dropper_clean"), "their list isn't touched");
        assertTrue(warns(log).stream().anyMatch(l -> l.contains("arcade.achievements")), "the WARN names it: " + log);
        assertTrue(warns(log).stream().anyMatch(l -> l.endsWith("- { id: game_dropper_clean, group: \"Games\", type:"
                + " COUNTER, counter: dropper_clean, target: 1, reward: 20, display: \"Reach the bottom of a Dropper"
                + " with no bonks\" }")), "and gives the line to paste: " + log);
    }

    @Test
    void aRowAlreadyThereIsNotAddedTwice() throws Exception {
        YamlConfiguration onDisk = bundled();
        onDisk.set("config_revision", 17);
        HomeCraftManagement.migrateConfig(onDisk, "world");
        assertEquals(1, ids(onDisk).stream().filter("game_dropper_clean"::equals).count(), "never twice");
    }
}
