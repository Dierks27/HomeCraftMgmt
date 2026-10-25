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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README's way back to 0.37.3 (SHOP-V2-SPEC §7.9; red-team finding RB-1): 0.37.3 names and numbers every
 * {@code type: trophy} row as the Arcade Trophy, so a ladder trophy bought while rolled back is an "Arcade Trophy #N"
 * for good. Every bundled trophy row but the Arcade Trophy's own is on the "set enabled: false" list for a longer
 * rollback, with the two {@code only_if} rows, and the quirk is named. The way back to 0.38.0 (0.38.1) switches off
 * the Grand and Mega Firework Shows, which 0.38.0 would sell as three-second shows, and says to pick up every Firework
 * Launcher first.
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

    /**
     * The Firework Launcher (0.38.1): 0.38.0 reads a placed launcher's record as a retired Mini Workbench (an unknown
     * type, {@code PlacedBlockDao}), so the note says to pick every one up first, both where the releases are listed
     * and in its own section; and the Mega Firework Show, a three-second show at 75 tokens there, is switched off too.
     */
    @Test
    void everyLauncherIsPickedUpAndTheMegaShowSwitchedOffForARollbackTo0380() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int at = readme.indexOf("### If you roll back to 0.38.0");
        String section = readme.substring(at, readme.indexOf("\n### ", at + 1));
        assertTrue(section.contains("**Pick up every launcher before rolling back to 0.38.0.**"), section);
        assertTrue(section.contains("retired Mini Workbench"), "the reason is named");
        int list = section.indexOf("**For a rollback longer than an evening,**");
        assertTrue(section.substring(list).contains("`firework_mega`"), "the Mega show is on the switch-off line");
        assertTrue(section.contains("skipped with one WARN"), "and the launcher row isn't sold there");
        int releases = readme.indexOf("**Back to 0.38.0 from 0.38.1:**");
        assertTrue(releases >= 0);
        String note = readme.substring(releases, readme.indexOf("\n\n", releases));
        assertTrue(note.contains("pick up every Firework Launcher before rolling back to 0.38.0"), note);
        assertTrue(note.contains("`firework_mega`"), note);
    }

    /**
     * Review LAUNCHER-ROLLBACK-4: what is lost while rolled back to 0.38.0 is named, in the short note as in the long
     * section. A launcher left standing may be broken by anyone who can build there (0.38.0 refuses only the stock
     * blocks' breaks), a launcher placed then is a plain dropper for good, and a Grand or Mega show used then is spent
     * as a three-second show; the note no longer says "Nothing is lost."
     */
    @Test
    void whatIsLostWhileRolledBackTo0380IsNamed() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int releases = readme.indexOf("**Back to 0.38.0 from 0.38.1:**");
        String note = readme.substring(releases, readme.indexOf("\n\n", releases)).replaceAll("\\s+", " ");
        assertFalse(note.contains("Nothing is lost."), note);
        assertTrue(note.contains("anyone who can build there may break into a Mini Workbench item"), note);
        assertTrue(note.contains("**Until the upgrade, don't place a launcher** (0.38.0 places it as a plain dropper"),
                note);
        assertTrue(note.contains("**and don't use a Grand or Mega Firework Show** (one used then is spent as a"
                + " three-second show)"), note);
        assertTrue(note.contains("Nothing else is lost"), note);

        int at = readme.indexOf("### If you roll back to 0.38.0");
        String section = readme.substring(at, readme.indexOf("\n### ", at + 1)).replaceAll("\\s+", " ");
        assertTrue(section.contains("anyone who can build there (not only whoever placed it) may break it into a"
                + " Mini Workbench item, which is no longer a prize"), section);
        assertTrue(section.contains("**Don't place one until the upgrade:**"), section);
        assertTrue(section.contains("**one used while rolled back is spent as a three-second show**"), section);
        assertTrue(section.contains("one used while rolled back is spent the same way"), "the Mega show too");
    }
}
