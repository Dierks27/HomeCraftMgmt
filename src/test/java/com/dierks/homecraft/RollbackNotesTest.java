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
 * Launcher first. The way back to 0.38.1 (0.38.2) switches off the Super, Ultra and Ultimate shows, which 0.38.1 would
 * sell as Mega shows, and names what the Remote does there.
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

    /**
     * The way back to 0.38.1 (0.38.2, R6): 0.38.1 reads {@code launchers} 4, 5 and 10 as 3, so the Super, Ultra and
     * Ultimate rows sell as Mega shows at their own prices; the note puts all three on its switch-off line, names the
     * Remote's row as skipped and the Remote as doing nothing, and says the linked sets come back. Both where the
     * releases are listed and in its own section.
     */
    @Test
    void theBiggerShowsAreSwitchedOffAndTheRemoteNamedForARollbackTo0381() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int at = readme.indexOf("### If you roll back to 0.38.1");
        assertTrue(at >= 0, "the section is there");
        String section = readme.substring(at, readme.indexOf("\n### ", at + 1)).replaceAll("\\s+", " ");
        assertTrue(section.contains("runs nothing"), "revision 24 runs nothing there");
        assertTrue(section.contains("**the Super, Ultra and Ultimate rows are Mega Firework Shows**"), section);
        assertTrue(section.contains("**one used while rolled back is spent as a Mega show**"), section);
        assertTrue(section.contains("**the `firework_remote` row is skipped**"), section);
        assertTrue(section.contains("linked sets stay in `game_prefs`"), section);
        assertTrue(section.contains("`per_player: 15` is read as 10"), section);
        // review R24-ROLLBACK-2: 0.38.1 refuses a place at 10 (placed >= per_player), so 10 placed is blocked too
        assertTrue(section.contains("a player with 10 or more placed can't place more until the upgrade"), section);
        assertTrue(section.contains("\"(12 of 10 set up)\""), "and the tap line over 10: " + section);
        assertFalse(section.contains("11-15"), section);
        int list = section.indexOf("**For a rollback longer than an evening,**");
        assertTrue(list >= 0, "the switch-off line");
        for (String id : List.of("firework_super", "firework_ultra", "firework_ultimate")) {
            assertTrue(section.substring(list).contains("`" + id + "`"), id + " is on it");
        }
        int releases = readme.indexOf("**Back to 0.38.1 from 0.38.2:**");
        assertTrue(releases >= 0, "the short note where the releases are listed");
        String note = readme.substring(releases, readme.indexOf("\n\n", releases)).replaceAll("\\s+", " ");
        for (String id : List.of("firework_super", "firework_ultra", "firework_ultimate", "firework_remote")) {
            assertTrue(note.contains("`" + id + "`"), id + ": " + note);
        }
        assertTrue(note.contains("the jar alone"), note);
        assertTrue(note.contains("Linked sets stay in the database"), note);
        assertTrue(note.contains("one with 10 or more placed can't place more"), note);
        assertFalse(note.contains("11-15"), note);
        assertTrue(readme.indexOf("**Back to 0.38.1 from 0.38.2:**") > readme.indexOf("**Back to 0.38.0 from 0.38.1:**"),
                "after the 0.38.0 note");
    }

    /**
     * Review R24-DOC-1 and R24-DOC-3: the launcher notes say a place over the limit (a "fourth" was 0.38.1's 3), and
     * the at-a-glance table keeps the Firework Remote and the launchers for good instead of saying every firework item
     * is used up.
     */
    @Test
    void theLauncherNotesSayTheLimitAndTheRemoteIsKept() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        String flat = readme.replaceAll("\\s+", " ");
        assertFalse(flat.contains("fourth place"), "no fourth place left");
        assertTrue(flat.contains("A held tap, or a held place over the limit, says its line once"), "the limit");
        int at = readme.indexOf("### Kept or rented, at a glance");
        String table = readme.substring(at, readme.indexOf("\n### ", at + 1));
        String kept = table.lines().filter(l -> l.startsWith("| Hats and caps")).findFirst().orElseThrow();
        assertTrue(kept.contains("Firework Launchers, the Firework Remote |"), kept);
        assertFalse(table.contains("Boosts, fireworks and other items are used up"), table);
        assertTrue(table.contains("Boosts and firework shows are used up"), table);
        for (String src : List.of("block/CustomBlockListener.java", "block/LauncherRules.java")) {
            String code = Files.readString(Path.of("src/main/java/com/dierks/homecraft/" + src),
                    StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            assertFalse(code.contains("fourth place") || code.contains("A fourth is refused"), src);
        }
    }

    /**
     * Review layout-migration F2: the way back to 0.38.4 (0.38.5). 0.38.4 refuses a dropper mix with an X and
     * switches the Dropper off, so the note says to set the shipped mix back to {@code EEMMH} before the jar is
     * swapped; it keeps the Classic Sky Rings at its new spot, says what happens to the bigger areas and the
     * archive, and says to put the mix back on the way up again, because revision 25 doesn't run twice.
     */
    @Test
    void theDropperMixGoesBackBeforeARollbackTo0384() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int at = readme.indexOf("**Back to 0.38.4 from 0.38.5:**");
        assertTrue(at >= 0, "the short note where the releases are listed");
        assertTrue(at < readme.indexOf("**Back to 0.38.3 from 0.38.4:**"), "above the 0.38.4 note");
        String note = readme.substring(at, readme.indexOf("\n\n", at)).replaceAll("\\s+", " ");
        YamlConfiguration c = new YamlConfiguration();
        try (var r = new InputStreamReader(RollbackNotesTest.class.getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8)) {
            c.load(r);
        }
        String mix = c.getString("games.fresh.slots.fresh_dropper.mix");
        assertTrue(note.contains("**first set `games.fresh.slots.fresh_dropper.mix` back to `EEMMH`**"), note);
        assertTrue(note.contains("switch the Dropper off"), "the reason is named: " + note);
        assertTrue(note.contains("set the mix back to `" + mix + "`"), "the way up again: " + note);
        assertTrue(note.contains("doesn't run twice"), note);
        List<Integer> classic = c.getIntegerList("games.fresh.classics.slots.fresh_classic_rings.origin");
        assertTrue(note.contains("Classic Sky Rings stays at `[" + classic.get(0) + ", " + classic.get(1) + ", "
                + classic.get(2) + "]`"), note);
        assertTrue(note.contains("leave it there"), note);
        assertTrue(note.contains("stay in the archive"), note);
    }
}
