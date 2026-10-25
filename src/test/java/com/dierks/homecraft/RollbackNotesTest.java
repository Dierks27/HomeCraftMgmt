package com.dierks.homecraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README's way back to 0.37.3 (SHOP-V2-SPEC §7.9; red-team finding RB-1): 0.37.3 names and numbers every
 * {@code type: trophy} row as the Arcade Trophy, so a ladder trophy bought while rolled back is an "Arcade Trophy #N"
 * for good. Every bundled trophy row but the Arcade Trophy's own is on the "set enabled: false" list for a longer
 * rollback, with the two {@code only_if} rows, and the quirk is named. The way back to 0.38.0 (0.38.1) switches off
 * the Grand Firework Show, which 0.38.0 would sell as a three-second show.
 */
class RollbackNotesTest {

    private static String rollbackSection() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int at = readme.indexOf("### If you roll back to 0.37.3");
        assertTrue(at >= 0, "the section is there");
        int end = readme.indexOf("\n---", at);
        return readme.substring(at, end < 0 ? readme.length() : end);
    }

    private static List<String> bundledTrophies() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (var r = new InputStreamReader(RollbackNotesTest.class.getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8)) {
            c.load(r);
        }
        List<String> out = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.prizes")) {
            if ("trophy".equals(String.valueOf(row.get("type"))) && !"arcade_trophy".equals(row.get("id"))) {
                out.add(String.valueOf(row.get("id")));
            }
        }
        return out;
    }

    @Test
    void everyTrophyRowThatWouldBeMisnamedIsSwitchedOffForALongRollback() throws Exception {
        String section = rollbackSection();
        int list = section.indexOf("**For a rollback longer than an evening,**");
        assertTrue(list >= 0, "the switch-off list");
        String switchOff = section.substring(list, section.indexOf("\n\n", list));
        List<String> trophies = bundledTrophies();
        assertTrue(trophies.size() >= 6, "the ladder and the Monthly: " + trophies);
        for (String id : trophies) {
            assertTrue(switchOff.contains("`" + id + "`"), "[X] " + id + " is on the list");
        }
        for (String id : List.of("make_it_day", "sunny_day")) {
            assertTrue(switchOff.contains("`" + id + "`"), id + " is on the list");
        }
        assertTrue(section.contains("\"Arcade Trophy #N\""), "[X] the quirk is named");
    }

    /**
     * The way back to 0.38.0 (0.38.1): 0.38.0 doesn't read a firework row's {@code seconds}, so it sells the Grand
     * Firework Show as a three-second show; the note says so and puts {@code firework_grand} on its switch-off line.
     */
    @Test
    void theGrandFireworkShowIsSwitchedOffForALongRollbackTo0380() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int at = readme.indexOf("### If you roll back to 0.38.0");
        assertTrue(at >= 0, "the section is there");
        String section = readme.substring(at, readme.indexOf("\n### ", at + 1));
        assertTrue(section.contains("runs nothing"), "revision 23 runs nothing there");
        assertTrue(section.contains("three seconds"), "the quirk is named: " + section);
        int list = section.indexOf("**For a rollback longer than an evening,**");
        assertTrue(list >= 0, "the switch-off line");
        assertTrue(section.substring(list).contains("`firework_grand`"), "with the row on it");
    }
}
