package com.dierks.homecraft;

import com.dierks.homecraft.config.CandyConfig;
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
 * Config revision 23 ({@link GrandShowMigration}: the Grand Firework Show and a gentler Candy Apple shower, the owner's
 * requests of 25 Oct), against a verbatim copy of 0.38.0's bundled config.yml.
 *
 * <p>Pinned here: the owner's 0.38.0 file ends with exactly the bundled counter (the Grand Firework Show right after the
 * Firework Show) and the new candy settings, with one INFO each and no WARN; a second start and a re-run change
 * nothing; an owner-edited candy value is kept with one WARN while the others move, the INFO saying only what moved
 * (and one more WARN when the last day would now be the quieter one); an owner-deleted Firework Show
 * still gets the Grand one, at the end; an owner's own {@code firework_grand} row is kept with a WARN and the line to
 * paste, and the shipped row already there isn't doubled; an absent list or key is left to the backfill; the frozen
 * values are what 0.37.2 and 0.38.0 shipped and what this one ships; and a 0.38.0 jar reading a revision-23 file says
 * so once and runs nothing.
 */
class GrandShowMigrationTest {

    private static final String PRIZES = GrandShowMigration.PRIZES;
    private static final String CANDY = CandyConfig.PATH;

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

    /** The one revision-23 step on its own (a revision-22 file). */
    private static List<String> apply(FileConfiguration c) {
        List<String> log = new ArrayList<>();
        GrandShowMigration.apply(c, log);
        return log;
    }

    private static void assertCandy(FileConfiguration c, int every, int lastDay, int apples, String why) {
        assertEquals(every, c.getInt(CANDY + ".every_minutes"), why + ": every_minutes");
        assertEquals(lastDay, c.getInt(CANDY + ".last_day_every_minutes"), why + ": last_day_every_minutes");
        assertEquals(apples, c.getInt(CANDY + ".apples_per_shower"), why + ": apples_per_shower");
    }

    // ---- the file that shipped ------------------------------------------------------------------------

    @Test
    void theOwners0380FileEndsWithTheBundledCounterAndTheGentlerCandy() {
        YamlConfiguration c = LayoutFixtures.v0380();
        assertEquals(22, c.getInt("config_revision"), "fixture: 0.38.0 is revision 22");
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: no WARN");
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertTrue(ArcadeConfigMigration.sameRows(rows(c), rows(bundled)), "the counter is the bundled one: " + ids(c));
        assertEquals(ids(c).indexOf("firework_show") + 1, ids(c).indexOf("firework_grand"), "right after the show");
        assertCandy(c, 60, 30, 3, "0.38.0");
        assertEquals(bundled.getConfigurationSection(CANDY).getValues(false),
                c.getConfigurationSection(CANDY).getValues(false), "the whole candy section is the bundled one");
        assertEquals(23, c.getInt("config_revision"));
        assertEquals(List.of(
                "Config migration: Prize Counter - added the Grand Firework Show (firework_grand: 15 seconds of"
                        + " fireworks with a big finale, 40 tokens) after the Firework Show.",
                "Config migration: arcade.events.candy - every_minutes 20 → 60, last_day_every_minutes 10 → 30,"
                        + " apples_per_shower 6 → 3 (a gentler Candy Apple shower: a shower an hour, every half hour on"
                        + " the last day, 3 candy apples each).",
                "Config migration: config_revision 22 → 23."), log, "one INFO each, and the revision");
        List<String> warnings = new ArrayList<>();
        assertEquals(CandyConfig.Candy.DEFAULTS, CandyConfig.parse(LayoutFixtures.reread(c), warnings::add),
                "the shower reads the shipped settings");
        assertEquals(List.of(), warnings);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(c, "world"), "a second start runs nothing");
    }

    @Test
    void aReRunChangesNothing() {
        YamlConfiguration c = LayoutFixtures.v0380();
        apply(c);
        String once = c.saveToString();
        assertEquals(List.of(), apply(c), "the second run logs nothing");
        assertEquals(once, c.saveToString(), "and changes nothing");
        assertEquals(1, ids(c).stream().filter("firework_grand"::equals).count(), "one Grand Firework Show");

        YamlConfiguration at23 = LayoutFixtures.v0380();
        at23.set("config_revision", 23);
        String before = at23.saveToString();
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(at23, "world"), "no step runs from 23");
        assertEquals(before, at23.saveToString());
    }

    @Test
    void the0372FileGetsBothRevisionsAndEndsAtTheBundledCounter() {
        YamlConfiguration c = LayoutFixtures.v0372();
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: " + log);
        assertTrue(ArcadeConfigMigration.sameRows(rows(c), rows(LayoutFixtures.bundled())), "the bundled counter");
        assertCandy(c, 60, 30, 3, "0.37.2");
        assertEquals("Config migration: config_revision 21 → 23.", log.get(log.size() - 1));
    }

    // ---- the owner's own values -------------------------------------------------------------------------

    @Test
    void anOwnerEditedCandyValueIsKeptWithOneWarnAndTheOthersMove() {
        YamlConfiguration c = LayoutFixtures.v0380();
        c.set(CANDY + ".every_minutes", 45);
        List<String> log = apply(c);
        assertCandy(c, 45, 30, 3, "their every_minutes stays, the other two move");
        String kept = HomeCraftManagement.WARN + "Config migration: kept arcade.events.candy.every_minutes = 45 because"
                + " you have changed it (the new default is 60).";
        assertEquals(List.of(kept), warns(log), "one WARN naming it");
        assertTrue(log.contains("Config migration: arcade.events.candy - last_day_every_minutes 10 → 30,"
                + " apples_per_shower 6 → 3 (a gentler Candy Apple shower: every half hour on the last day, 3 candy"
                + " apples each)."), "the INFO names only what moved, and says only what that means: " + log);
        assertTrue(log.stream().noneMatch(l -> l.contains("a shower an hour")), "never the hour their 45 isn't");
        String once = c.saveToString();
        assertEquals(List.of(kept), apply(c), "a second run moves nothing more, and only says the WARN again");
        assertEquals(once, c.saveToString(), "the file is as the first run left it");

        YamlConfiguration apples = LayoutFixtures.v0380();
        apples.set(CANDY + ".apples_per_shower", 12);
        List<String> theirApples = apply(apples);
        assertCandy(apples, 60, 30, 12, "their apples stay");
        assertTrue(theirApples.contains("Config migration: arcade.events.candy - every_minutes 20 → 60,"
                + " last_day_every_minutes 10 → 30 (a gentler Candy Apple shower: a shower an hour, every half hour on"
                + " the last day)."), "no \"3 candy apples\" when theirs are 12: " + theirApples);
        assertEquals(1, warns(theirApples).size(), theirApples.toString());

        YamlConfiguration all = LayoutFixtures.v0380();
        all.set(CANDY + ".every_minutes", 30);
        all.set(CANDY + ".last_day_every_minutes", 0);
        all.set(CANDY + ".apples_per_shower", 12);
        List<String> mine = apply(all);
        assertCandy(all, 30, 0, 12, "all three are theirs");
        assertEquals(3, warns(mine).size(), "a WARN each: " + mine);
        assertTrue(mine.stream().noneMatch(l -> l.startsWith("Config migration: arcade.events.candy")), "no INFO");
    }

    /**
     * Review R23-CANDY-INFO: an owner's own every_minutes under 30, kept, while the last day's 10 moves to 30, would
     * make the last day the one with fewer showers; one more WARN says so and what to set. Never when the two are equal,
     * when every_minutes is above 30, or when the last day didn't move.
     */
    @Test
    void aLastDayThatWouldNowBeQuieterThanTheOthersIsWarned() {
        YamlConfiguration c = LayoutFixtures.v0380();
        c.set(CANDY + ".every_minutes", 15);
        List<String> log = apply(c);
        assertCandy(c, 15, 30, 3, "theirs stays, the last day moves as the rule says");
        assertEquals(List.of(
                HomeCraftManagement.WARN + "Config migration: kept arcade.events.candy.every_minutes = 15 because you"
                        + " have changed it (the new default is 60).",
                HomeCraftManagement.WARN + "Config migration: arcade.events.candy.last_day_every_minutes is now 30,"
                        + " more than your every_minutes = 15, so the last day gets fewer showers than the others: set"
                        + " it to 15 or less (0 = as every_minutes) for more."), warns(log), "the kept one, then this");
        assertTrue(log.contains("Config migration: arcade.events.candy - last_day_every_minutes 10 → 30,"
                + " apples_per_shower 6 → 3 (a gentler Candy Apple shower: every half hour on the last day, 3 candy"
                + " apples each)."), log.toString());
        assertEquals(1, warns(apply(c)).size(), "a re-run moves nothing, so only the kept WARN is said again");

        for (int every : new int[]{30, 45, 120}) {
            YamlConfiguration x = LayoutFixtures.v0380();
            x.set(CANDY + ".every_minutes", every);
            assertEquals(1, warns(apply(x)).size(), every + ": the last day is as busy or busier, only the kept WARN");
        }
        YamlConfiguration theirLastDay = LayoutFixtures.v0380();
        theirLastDay.set(CANDY + ".every_minutes", 15);
        theirLastDay.set(CANDY + ".last_day_every_minutes", 5);
        assertEquals(2, warns(apply(theirLastDay)).size(), "both theirs: a kept WARN each, nothing about the last day");
        assertCandy(theirLastDay, 15, 5, 3, "both theirs stay");
    }

    @Test
    void anAbsentCandyKeyOrSectionIsLeftToTheBackfill() {
        YamlConfiguration c = LayoutFixtures.v0380();
        c.set(CANDY + ".apples_per_shower", null);
        List<String> log = apply(c);
        assertFalse(c.contains(CANDY + ".apples_per_shower"), "not written here");
        assertTrue(log.stream().anyMatch(l -> l.contains("every_minutes 20 → 60, last_day_every_minutes 10 → 30 (")),
                "the other two move: " + log);
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        assertEquals(3, c.getInt(CANDY + ".apples_per_shower"), "the backfill writes the new default");

        YamlConfiguration none = LayoutFixtures.v0380();
        none.set("arcade.events", null);
        assertTrue(apply(none).stream().noneMatch(l -> l.contains("candy")), "no section: nothing to say");
        HomeCraftManagement.backfillConfig(none, LayoutFixtures.bundled());
        assertCandy(none, 60, 30, 3, "the backfill's section");
    }

    @Test
    void anOwnerDeletedFireworkShowStillGetsTheGrandOneAtTheEnd() {
        YamlConfiguration c = LayoutFixtures.v0380();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        list.removeIf(o -> o instanceof Map<?, ?> m && "firework_show".equals(m.get("id")));
        c.set(PRIZES, list);
        List<String> log = apply(c);
        assertEquals(List.of(), warns(log), "no WARN");
        assertFalse(ids(c).contains("firework_show"), "the deleted row is not put back");
        assertEquals("firework_grand", ids(c).get(ids(c).size() - 1), "the Grand one is appended");
        assertEquals(list.size() + 1, ids(c).size());
        assertTrue(ShopMigration.same(rowOf(c, "firework_grand"), GrandShowMigration.GRAND), "as shipped");
        assertTrue(log.get(0).endsWith("40 tokens) at the end of the list."), log.get(0));
        assertEquals(List.of(), apply(c), "and only once");
    }

    @Test
    void anOwnersOwnFireworkGrandIsKeptWithOneWarnAndThePasteLine() {
        YamlConfiguration c = LayoutFixtures.v0380();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("id", "firework_grand");
        mine.put("display", "&dMy Big Show");
        mine.put("cost_tokens", 25);
        mine.put("type", "firework");
        list.add(mine);
        c.set(PRIZES, list);
        List<String> log = apply(c);
        assertEquals(25, rowOf(c, "firework_grand").get("cost_tokens"), "their own row stays");
        assertEquals(1, ids(c).stream().filter("firework_grand"::equals).count(), "and is not doubled");
        List<String> w = warns(log);
        assertEquals(2, w.size(), "one WARN and the shipped line: " + w);
        assertTrue(w.get(0).contains("arcade.prizes → firework_grand"), w.get(0));
        assertEquals(HomeCraftManagement.WARN + "  - { id: firework_grand, tab: COSMETICS, display: \"&6Grand Firework"
                + " Show\", description: [\"A long firework show\", \"with a big finale!\"], cost_tokens: 40, icon: {"
                + " texture: \"\", material: FIREWORK_ROCKET }, type: firework, seconds: 15 }", w.get(1));
        assertTrue(log.stream().noneMatch(l -> l.startsWith("Config migration: Prize Counter")), "nothing added");
    }

    @Test
    void theShippedRowAlreadyThereIsNotDoubled() {
        YamlConfiguration c = LayoutFixtures.v0380();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        list.add(ShopMigration.copy(GrandShowMigration.GRAND)); // pasted by hand from the release notes
        c.set(PRIZES, list);
        List<String> log = apply(c);
        assertEquals(1, ids(c).stream().filter("firework_grand"::equals).count());
        assertEquals(List.of(), warns(log), "theirs is the shipped row: no WARN");
        assertTrue(log.stream().noneMatch(l -> l.startsWith("Config migration: Prize Counter")), log.toString());
    }

    @Test
    void anAbsentListIsLeftToTheBackfill() {
        YamlConfiguration c = LayoutFixtures.v0380();
        c.set(PRIZES, null);
        assertTrue(apply(c).stream().noneMatch(l -> l.contains("Prize Counter")));
        assertFalse(c.contains(PRIZES));
    }

    // ---- the frozen tables -------------------------------------------------------------------------------

    @Test
    void theOldValuesAreWhat0372And0380ShippedAndTheNewOnesAreThisVersions() throws Exception {
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (GrandShowMigration.Step s : GrandShowMigration.CANDY) {
            for (YamlConfiguration old : List.of(LayoutFixtures.v0372(), LayoutFixtures.v0380())) {
                assertEquals(s.old(), old.get(s.path()), s.path() + " in revision " + old.getInt("config_revision"));
            }
            assertEquals(s.now(), bundled.get(s.path()), s.path() + " is the bundled value");
        }
        assertEquals(3, GrandShowMigration.CANDY.size());
        CandyConfig.Candy d = CandyConfig.Candy.DEFAULTS;
        assertEquals(List.of(60, 30, 3), List.of(d.everyMinutes(), d.lastDayEveryMinutes(), d.applesPerShower()),
                "the code's defaults match");

        assertNull(rowOf(LayoutFixtures.v0380(), "firework_grand"), "the row is new");
        assertTrue(ShopMigration.same(rowOf(bundled, "firework_grand"), GrandShowMigration.GRAND), "the bundled row");
        assertTrue(ShopMigration.same(rowOf(bundled, "firework_show"), rowOf(LayoutFixtures.v0380(), "firework_show")),
                "the Firework Show itself is unchanged");
        String text = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        assertTrue(text.contains("\n    " + ArcadeConfigMigration.flowRow(GrandShowMigration.GRAND) + "\n"),
                "the paste line is the bundled file's own line: " + ArcadeConfigMigration.flowRow(GrandShowMigration.GRAND));
        assertTrue(text.contains("\n  #   firework   a harmless firework show; seconds 3-30, default 3.\n"),
                "the type comment");
        for (Map<?, ?> crate : bundled.getMapList("arcade.crates.arcade_crate.rewards")) {
            assertFalse(String.valueOf(crate.get("prize")).contains("firework_grand"), "never in the crate");
        }
    }

    @Test
    void theTablesAreLiteralsNeverTheBundledFile() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/GrandShowMigration.java"),
                StandardCharsets.UTF_8);
        assertFalse(src.contains("bundled("), "GrandShowMigration never reads the bundled config.yml");
        assertFalse(src.contains("getResource"), "nor any other file");
        assertFalse(src.contains("Candy.DEFAULTS"), "nor the code's candy defaults");
    }

    // ---- rolled back --------------------------------------------------------------------------------------

    /**
     * 0.38.0 reading a revision-23 file (the jar rolled back under it): its gate is 22, so the start logs the one WARN
     * and runs nothing (0.38.0's {@code newerConfigWarning} is this one with {@code CONFIG_REVISION} 22).
     */
    @Test
    void a0380JarReadingRevision23SaysSoAndRunsNothing() {
        assertEquals("This config was written by a newer HomeCraft (revision 23); rows this version doesn't know are"
                + " skipped.", HomeCraftManagement.newerConfigWarning(23, ShopMigration.REVISION), "0.38.0's WARN");
        assertNull(HomeCraftManagement.newerConfigWarning(22, ShopMigration.REVISION), "its own file says nothing");
        assertNull(HomeCraftManagement.newerConfigWarning(23), "this build's own revision says nothing");
        assertEquals(HomeCraftManagement.newerConfigWarning(24, 23), HomeCraftManagement.newerConfigWarning(24),
                "and a newer one's does");
        YamlConfiguration rolledBack = LayoutFixtures.bundled();
        rolledBack.set("config_revision", 24);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(rolledBack, "world"), "no step runs");
        assertEquals(24, rolledBack.getInt("config_revision"), "and the revision is never lowered");
    }
}
