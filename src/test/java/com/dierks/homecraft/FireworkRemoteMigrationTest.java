package com.dierks.homecraft;

import com.dierks.homecraft.arcade.HolidayShows;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 24 ({@link FireworkRemoteMigration}: the Firework Remote and the bigger Mega shows, the owner's
 * requests of 25 Oct), against a verbatim copy of 0.38.1's bundled config.yml (the jar the owner has installed, so
 * revision 23 has already run on their server).
 *
 * <p>Pinned here: the owner's 0.38.1 file ends with exactly the bundled counter (the Super, Ultra and Ultimate shows
 * and the Firework Remote right after the Mega Firework Show) and 15 launchers a player, with one INFO each and no
 * WARN; a 0.38.0 file gets both revisions and ends there too; a second start and a re-run change nothing; an owner's
 * own {@code firework_super} or {@code firework_remote} is kept with a WARN and the line to paste (the rows after it
 * still follow it), and a shipped row already there isn't doubled; an owner-deleted Mega show still gets the four, at
 * the end; an absent list or key is left to the backfill; an owner's own {@code per_player} is kept with one WARN; the
 * frozen values are what 0.38.1 shipped and what this one ships, each paste line the bundled file's own; the backfill
 * writes the new holiday section with its comment, so Halloween is on from the first start; and a 0.38.1 jar reading
 * a revision-24 file says so once and runs nothing.
 */
class FireworkRemoteMigrationTest {

    private static final String PRIZES = FireworkRemoteMigration.PRIZES;
    /** The four rows' INFO on a file with the Mega show. */
    static final String ADDED_ALL = "Config migration: Prize Counter - added the Super Firework Show (firework_super: a"
            + " minute of fireworks from 4 Firework Launchers, 100 tokens) after the Mega Firework Show, the Ultra"
            + " Firework Show (firework_ultra: a minute of fireworks from 5 Firework Launchers, 125 tokens) after the"
            + " Super Firework Show, the Ultimate Firework Show (firework_ultimate: a minute and a half of fireworks from"
            + " 10 Firework Launchers, 200 tokens) after the Ultra Firework Show and the Firework Remote"
            + " (firework_remote: tap launchers to pick them for your shows, 10 tokens) after the Ultimate Firework"
            + " Show.";
    /** The per_player INFO on 0.38.1's file. */
    /** Revision 25's line for a file that still has Classic Sky Rings at its shipped 0.38 spot. */
    static final String RINGS_MOVED = "Config migration: the bigger Sky Rings areas get their new spots (Classic Sky"
            + " Rings [6080, 128, 4992] -> [1760, 128, 3328]). After the restart the old areas are emptied by themselves"
            + " (ponds first, only Fresh Courses' own blocks); /hcm games check shows it.";

    /** Revision 25's line for a file that still has the Dropper's shipped EEMMH. */
    static final String MIX_MOVED = "Config migration: the Dropper's shipped mix EEMMH becomes EEMHX (level 5 is the"
            + " Long Drop). Its halves are 64 x 144 x 16 now; after the restart its old area is emptied by itself (pools"
            + " first) and the Dropper is built again, taller, at the same spot. A mix set with the mix command still"
            + " wins over the file: /hcm games gen status shows the one in use, and /hcm games gen mix fresh_dropper"
            + " EEMHX sets the shipped one, from the next build.";

    private static final String FIFTEEN = "Config migration: arcade.launcher - per_player 3 → 15 (each player may have"
            + " 15 Firework Launchers placed at once, enough for the Ultimate show's 10).";
    /** The ids revision 24 adds, in order. */
    private static final List<String> NEW_IDS = List.of("firework_super", "firework_ultra", "firework_ultimate",
            "firework_remote");
    private static final String PER_PLAYER = FireworkRemoteMigration.STEP.path();

    private static List<String> warns(List<String> log) {
        return log.stream().filter(l -> l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    private static List<Map<String, Object>> rows(FileConfiguration c) {
        return ArcadeConfigMigration.mapRows(c.getList(PRIZES));
    }

    private static List<String> ids(FileConfiguration c) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows(c)) {
            out.add(ShopMigration.idOf(r));
        }
        return out;
    }

    private static Map<String, Object> rowOf(FileConfiguration c, String id) {
        for (Map<String, Object> r : rows(c)) {
            if (ShopMigration.idOf(r).equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** The one revision-24 step on its own (a revision-23 file). */
    private static List<String> apply(FileConfiguration c) {
        List<String> log = new ArrayList<>();
        FireworkRemoteMigration.apply(c, log);
        return log;
    }

    /** {@code c} with {@code row} added to its counter. */
    private static void addRow(FileConfiguration c, Map<String, Object> row) {
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        list.add(row);
        c.set(PRIZES, list);
    }

    // ---- the files that shipped ------------------------------------------------------------------------

    @Test
    void theOwners0381FileGetsTheFourRowsAndFifteenLaunchers() {
        YamlConfiguration c = LayoutFixtures.v0381();
        assertEquals(23, c.getInt("config_revision"), "fixture: 0.38.1 is revision 23");
        assertEquals(3, c.getInt(PER_PLAYER), "fixture: 0.38.1's three");
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: no WARN");
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertTrue(ArcadeConfigMigration.sameRows(rows(c), rows(bundled)), "the counter is the bundled one: " + ids(c));
        int mega = ids(c).indexOf("firework_mega");
        assertEquals(NEW_IDS, ids(c).subList(mega + 1, mega + 5), "the four right after the Mega show, in order");
        assertEquals(15, c.getInt(PER_PLAYER));
        assertEquals(bundled.getConfigurationSection("arcade.launcher").getValues(false),
                c.getConfigurationSection("arcade.launcher").getValues(false), "the launcher section is the bundled one");
        assertEquals(25, c.getInt("config_revision"), "on through revision 25 (Sky Rings and the Long Drop)");
        assertEquals(List.of(ADDED_ALL, FIFTEEN, RINGS_MOVED, MIX_MOVED, "Config migration: config_revision 23 → 25."),
                log, "one INFO each, revision 25's two, and the revision");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(c, "world"), "a second start runs nothing");
    }

    @Test
    void a0380FileGetsRevisions23And24AndEndsAtTheBundledCounter() {
        YamlConfiguration c = LayoutFixtures.v0380();
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: " + log);
        assertTrue(ArcadeConfigMigration.sameRows(rows(c), rows(LayoutFixtures.bundled())), "the bundled counter");
        assertTrue(log.contains(ADDED_ALL), "revision 24's rows after revision 23's: " + log);
        assertFalse(c.contains(PER_PLAYER), "no launcher section yet: the backfill writes it");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        assertEquals(15, c.getInt(PER_PLAYER), "the backfill's 15");
        assertEquals("Config migration: config_revision 22 → 25.", log.get(log.size() - 1));
    }

    @Test
    void aReRunChangesNothing() {
        YamlConfiguration c = LayoutFixtures.v0381();
        apply(c);
        String once = c.saveToString();
        assertEquals(List.of(), apply(c), "the second run logs nothing");
        assertEquals(once, c.saveToString(), "and changes nothing");
        for (String id : NEW_IDS) {
            assertEquals(1, ids(c).stream().filter(id::equals).count(), "one " + id);
        }
        YamlConfiguration at24 = LayoutFixtures.v0381();
        at24.set("config_revision", 24);
        int launchers = at24.getInt(PER_PLAYER);
        assertEquals(List.of(RINGS_MOVED, MIX_MOVED, "Config migration: config_revision 24 → 25."),
                HomeCraftManagement.migrateConfig(at24, "world"), "no revision-24 step runs from 24, only 25's");
        assertEquals(launchers, at24.getInt(PER_PLAYER), "the launchers are as they were");
        YamlConfiguration at25 = LayoutFixtures.v0381();
        at25.set("config_revision", 25);
        String before = at25.saveToString();
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(at25, "world"), "no step runs from 25");
        assertEquals(before, at25.saveToString());
    }

    // ---- the owner's own rows and values -----------------------------------------------------------------

    @Test
    void anOwnersOwnFireworkSuperIsKeptWithOneWarnAndThePasteLine() {
        YamlConfiguration c = LayoutFixtures.v0381();
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("id", "firework_super");
        mine.put("display", "&bMy Super Show");
        mine.put("cost_tokens", 90);
        mine.put("type", "firework");
        mine.put("launchers", 3);
        addRow(c, mine);
        List<String> log = apply(c);
        assertEquals(90, rowOf(c, "firework_super").get("cost_tokens"), "their own row stays");
        assertEquals(1, ids(c).stream().filter("firework_super"::equals).count(), "and is not doubled");
        assertEquals(List.of(HomeCraftManagement.WARN + "Config migration: kept your own arcade.prizes → firework_super"
                        + " row: 0.38.2 ships a row with that id. To take 0.38.2's instead, put this line in place of"
                        + " yours:",
                HomeCraftManagement.WARN + "  - { id: firework_super, tab: COSMETICS, display: \"&bSuper Firework"
                        + " Show\", description: [\"A whole minute of fireworks\", \"from 4 Firework Launchers!\"],"
                        + " cost_tokens: 100, icon: { texture: \"\", material: FIREWORK_ROCKET }, type: firework,"
                        + " seconds: 60, launchers: 4 }"), warns(log), "one WARN and the shipped line");
        int theirs = ids(c).indexOf("firework_super");
        assertEquals(List.of("firework_ultra", "firework_ultimate", "firework_remote"),
                ids(c).subList(theirs + 1, theirs + 4), "the rows after it still follow it, their own row or not");
        assertTrue(log.stream().anyMatch(l -> l.startsWith("Config migration: Prize Counter - added the Ultra Firework"
                + " Show (firework_ultra:") && !l.contains("firework_super")), "the INFO names only what was added");
        String once = c.saveToString();
        assertEquals(warns(log), warns(apply(c)), "a second run adds nothing, and only says the WARN again");
        assertEquals(once, c.saveToString());

        YamlConfiguration remote = LayoutFixtures.v0381();
        Map<String, Object> theirRemote = new LinkedHashMap<>();
        theirRemote.put("id", "firework_remote");
        theirRemote.put("display", "&eMy Remote");
        theirRemote.put("cost_tokens", 1);
        theirRemote.put("type", "remote");
        addRow(remote, theirRemote);
        List<String> w = warns(apply(remote));
        assertEquals(2, w.size(), "one WARN and the shipped line: " + w);
        assertTrue(w.get(0).contains("arcade.prizes → firework_remote"), w.get(0));
        assertEquals(HomeCraftManagement.WARN + "  - { id: firework_remote, tab: COSMETICS, display: \"&eFirework"
                + " Remote\", description: [\"Tap your Firework Launchers\", \"to pick them for your show!\"],"
                + " cost_tokens: 10, icon: { texture: \"\", material: RECOVERY_COMPASS }, type: remote }", w.get(1));
        assertEquals(1, rowOf(remote, "firework_remote").get("cost_tokens"), "their price stays");
    }

    @Test
    void anOwnerDeletedFireworkMegaStillGetsTheRowsAtTheEnd() {
        YamlConfiguration c = LayoutFixtures.v0381();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        list.removeIf(o -> o instanceof Map<?, ?> m && "firework_mega".equals(m.get("id")));
        c.set(PRIZES, list);
        List<String> log = apply(c);
        assertEquals(List.of(), warns(log), "no WARN");
        assertFalse(ids(c).contains("firework_mega"), "the deleted row is not put back");
        assertEquals(NEW_IDS, ids(c).subList(ids(c).size() - 4, ids(c).size()),
                "the Super show is appended, and the others after it");
        assertEquals(list.size() + 4, ids(c).size());
        for (FireworkRemoteMigration.Added a : FireworkRemoteMigration.ADDED) {
            assertTrue(ShopMigration.same(rowOf(c, ShopMigration.idOf(a.row())), a.row()), "as shipped");
        }
        assertTrue(log.get(0).contains("100 tokens) at the end of the list, the Ultra Firework Show"), log.get(0));
        assertTrue(log.get(0).endsWith("10 tokens) after the Ultimate Firework Show."), log.get(0));
        assertEquals(List.of(), apply(c), "and only once");
    }

    @Test
    void theShippedRowAlreadyThereIsNotDoubled() {
        YamlConfiguration c = LayoutFixtures.v0381();
        addRow(c, ShopMigration.copy(FireworkRemoteMigration.SUPER)); // pasted by hand from the release notes
        List<String> log = apply(c);
        assertEquals(1, ids(c).stream().filter("firework_super"::equals).count());
        assertEquals(List.of(), warns(log), "theirs is the shipped row: no WARN");
        assertEquals(NEW_IDS, ids(c).subList(ids(c).size() - 4, ids(c).size()), "the others go in after it");
        assertTrue(log.stream().filter(l -> l.startsWith("Config migration: Prize Counter")).allMatch(
                l -> l.startsWith("Config migration: Prize Counter - added the Ultra Firework Show")), log.toString());
    }

    @Test
    void anAbsentListIsLeftToTheBackfill() {
        YamlConfiguration c = LayoutFixtures.v0381();
        c.set(PRIZES, null);
        assertTrue(apply(c).stream().noneMatch(l -> l.contains("Prize Counter")));
        assertFalse(c.contains(PRIZES));
    }

    @Test
    void anOwnersPerPlayerIsKeptWithOneWarn() {
        YamlConfiguration c = LayoutFixtures.v0381();
        c.set(PER_PLAYER, 5);
        List<String> log = apply(c);
        assertEquals(5, c.getInt(PER_PLAYER), "their 5 stays");
        assertEquals(List.of(HomeCraftManagement.WARN + "Config migration: kept arcade.launcher.per_player = 5 because"
                + " you have changed it (the new default is 15)."), warns(log), "one WARN naming it");
        assertTrue(log.stream().noneMatch(l -> l.startsWith("Config migration: arcade.launcher")), "no INFO");

        YamlConfiguration fifteen = LayoutFixtures.v0381();
        fifteen.set(PER_PLAYER, 15);
        assertTrue(apply(fifteen).stream().noneMatch(l -> l.contains("per_player")), "already 15: silent");
        assertEquals(15, fifteen.getInt(PER_PLAYER));

        YamlConfiguration absent = LayoutFixtures.v0381();
        absent.set(PER_PLAYER, null);
        assertTrue(apply(absent).stream().noneMatch(l -> l.contains("per_player")), "absent: nothing said");
        assertFalse(absent.contains(PER_PLAYER), "and not written here");
        HomeCraftManagement.backfillConfig(absent, LayoutFixtures.bundled());
        assertEquals(15, absent.getInt(PER_PLAYER), "the backfill writes the new default");
    }

    // ---- the frozen tables -------------------------------------------------------------------------------

    @Test
    void theOldValueIsWhat0381ShippedAndTheNewOnesAreThisVersions() throws Exception {
        YamlConfiguration bundled = LayoutFixtures.bundled();
        YamlConfiguration v0381 = LayoutFixtures.v0381();
        assertEquals(FireworkRemoteMigration.STEP.old(), v0381.get(PER_PLAYER), "0.38.1 shipped 3");
        assertEquals(FireworkRemoteMigration.STEP.now(), bundled.get(PER_PLAYER), "this version ships 15");
        assertEquals(15, com.dierks.homecraft.config.PluginConfig.Launcher.DEFAULTS.perPlayer(), "and the code's");
        assertEquals(NEW_IDS, FireworkRemoteMigration.ADDED.stream().map(a -> ShopMigration.idOf(a.row())).toList(),
                "the four, in the order they go in");
        String after = FireworkRemoteMigration.AFTER;
        for (FireworkRemoteMigration.Added a : FireworkRemoteMigration.ADDED) {
            String id = ShopMigration.idOf(a.row());
            assertNull(rowOf(v0381, id), id + " is new");
            assertNull(rowOf(LayoutFixtures.v0380(), id), id + " is new");
            assertTrue(ShopMigration.same(rowOf(bundled, id), a.row()), id + ": the bundled row");
            assertEquals(after, a.after(), id + " follows the one before it");
            assertEquals(ids(bundled).indexOf(after) + 1, ids(bundled).indexOf(id), id + ": the bundled file's order");
            after = id;
        }
        for (String old : List.of("firework_show", "firework_grand", "firework_launcher", "firework_mega")) {
            assertTrue(ShopMigration.same(rowOf(bundled, old), rowOf(v0381, old)), old + " itself is unchanged");
        }
        String text = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        for (FireworkRemoteMigration.Added a : FireworkRemoteMigration.ADDED) {
            assertTrue(text.contains("\n    " + ArcadeConfigMigration.flowRow(a.row()) + "\n"),
                    "the paste line is the bundled file's own line: " + ArcadeConfigMigration.flowRow(a.row()));
        }
        for (Map<?, ?> crate : bundled.getMapList("arcade.crates.arcade_crate.rewards")) {
            for (String id : NEW_IDS) {
                assertFalse(String.valueOf(crate.get("prize")).contains(id), id + " is never in the crate");
            }
        }
    }

    @Test
    void theTablesAreLiteralsNeverTheBundledFile() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/FireworkRemoteMigration.java"),
                StandardCharsets.UTF_8);
        assertFalse(src.contains("bundled("), "FireworkRemoteMigration never reads the bundled config.yml");
        assertFalse(src.contains("getResource"), "nor any other file");
        assertFalse(src.contains("Launcher.DEFAULTS"), "nor the code's launcher defaults");
        String grand = Files.readString(Path.of("src/main/java/com/dierks/homecraft/GrandShowMigration.java"),
                StandardCharsets.UTF_8);
        assertFalse(grand.contains("FireworkRemoteMigration"), "revision 23 is left as it is");
        assertEquals(23, GrandShowMigration.REVISION);
        assertEquals(24, FireworkRemoteMigration.REVISION);
        assertEquals(HomeCraftManagement.CONFIG_REVISION, FireworkRemoteMigration.REVISION + 1,
                "the revision before the newest (25: Sky Rings' areas and the Long Drop)");
    }

    /**
     * The new {@code arcade.holiday_shows} needs no revision step: the backfill writes it, with its comment, at every
     * start, so Halloween's colours are on from the first start of 0.38.2 (and the code's {@link HolidayShows#SHIPPED}
     * would be read if it hadn't).
     */
    @Test
    void theBackfillWritesTheHolidaySection() {
        YamlConfiguration c = LayoutFixtures.v0381();
        assertFalse(c.contains("arcade.holiday_shows"), "fixture: 0.38.1 had none");
        HomeCraftManagement.migrateConfig(c, "world");
        List<String> added = HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        assertTrue(added.contains("arcade.holiday_shows.enabled") && added.contains("arcade.holiday_shows.windows"),
                "both leaves: " + added);
        YamlConfiguration after = LayoutFixtures.reread(c);
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertEquals(bundled.getComments("arcade.holiday_shows"), after.getComments("arcade.holiday_shows"),
                "with the section's comment");
        assertEquals(bundled.get("arcade.holiday_shows.windows"), after.get("arcade.holiday_shows.windows"));
        List<String> warnings = new ArrayList<>();
        HolidayShows read = HolidayShows.read(after.contains("arcade.holiday_shows"),
                after.get("arcade.holiday_shows.enabled"), after.get("arcade.holiday_shows.windows"), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(HolidayShows.SHIPPED, read, "the six windows");
        assertEquals("Halloween", read.themeOn(java.time.LocalDate.of(2026, 10, 25)).name(), "on today");
    }

    // ---- rolled back --------------------------------------------------------------------------------------

    /**
     * 0.38.1 reading a revision-24 file (the jar rolled back under it): its gate is 23, so the start logs the one WARN
     * and runs nothing.
     */
    @Test
    void a0381JarReadingRevision24SaysSoAndRunsNothing() {
        assertEquals("This config was written by a newer HomeCraft (revision 24); rows this version doesn't know are"
                + " skipped.", HomeCraftManagement.newerConfigWarning(24, GrandShowMigration.REVISION), "0.38.1's WARN");
        assertNull(HomeCraftManagement.newerConfigWarning(23, GrandShowMigration.REVISION), "its own file: nothing");
        assertNull(HomeCraftManagement.newerConfigWarning(HomeCraftManagement.CONFIG_REVISION),
                "this build's own revision says nothing");
        YamlConfiguration rolledBack = LayoutFixtures.bundled();
        rolledBack.set("config_revision", 26);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(rolledBack, "world"), "no step runs");
        assertEquals(26, rolledBack.getInt("config_revision"), "and the revision is never lowered");
    }
}
