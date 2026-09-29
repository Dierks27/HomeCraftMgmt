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
 * Config revision 17 (EXTRAS E4): the skill games' quests and "Games" achievements reach an upgraded
 * server. A list is one value to the leaf backfill, so without this step a server that already has
 * the pools would never see them. A pool or list still exactly as shipped gains the new rows at its
 * end; one the owner changed is theirs and is left alone, with a WARN once (the revision gate) that
 * names it and gives the lines to paste; a row already there by id is never added twice.
 */
class GamesRowsMigrationTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = GamesRowsMigrationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    /** A revision-16 file: the bundled one without the revision-17 rows. */
    private static YamlConfiguration rev16() throws Exception {
        YamlConfiguration c = bundled();
        c.set("config_revision", 16);
        without(c, "arcade.quests.daily_pool", ArcadeConfigMigration.GAME_DAILY);
        without(c, "arcade.quests.weekly_pool", ArcadeConfigMigration.GAME_WEEKLY);
        without(c, "arcade.achievements", ArcadeConfigMigration.GAME_ACHIEVEMENTS);
        without(c, "arcade.achievements", ArcadeConfigMigration.EVENT_ACHIEVEMENTS);
        return c;
    }

    /** A revision-17 file: the bundled one without the revision-18 rows (Race Night's achievements). */
    private static YamlConfiguration rev17() throws Exception {
        YamlConfiguration c = bundled();
        c.set("config_revision", 17);
        without(c, "arcade.achievements", ArcadeConfigMigration.EVENT_ACHIEVEMENTS);
        return c;
    }

    private static void without(YamlConfiguration c, String path, List<String> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList(path)) {
            if (!ids.contains(String.valueOf(row.get("id")))) {
                out.add(copy(row));
            }
        }
        c.set(path, out);
    }

    private static Map<String, Object> copy(Map<?, ?> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(String.valueOf(k), v));
        return m;
    }

    private static List<String> ids(YamlConfiguration c, String path) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList(path)) {
            out.add(String.valueOf(row.get("id")));
        }
        return out;
    }

    private static List<String> warns(List<String> log) {
        return log.stream().filter(l -> l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    @Test
    void shippedPoolsAndTheShippedListGainTheNewRowsAtTheirEnd() throws Exception {
        YamlConfiguration onDisk = rev16();
        YamlConfiguration shipped = bundled();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        for (String path : List.of("arcade.quests.daily_pool", "arcade.quests.weekly_pool", "arcade.achievements")) {
            assertEquals(ids(shipped, path), ids(onDisk, path), path + " is now exactly what a fresh install ships");
            assertEquals(shipped.getMapList(path), onDisk.getMapList(path), path + ": the new rows are the bundled ones");
        }
        assertEquals(List.of(), warns(log), "nothing was the owner's, so nothing to warn about: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("cabinet_daily, course_daily")), "logged: " + log);
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"), "stamped");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second pass is a no-op");
    }

    @Test
    void anEditedPoolIsKeptAndTheWarningGivesTheLinesToAdd() throws Exception {
        YamlConfiguration onDisk = rev16();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.quests.daily_pool")) {
            Map<String, Object> m = copy(row);
            if ("fish_daily".equals(m.get("id"))) {
                m.put("reward", 9); // their own reward
            }
            daily.add(m);
        }
        onDisk.set("arcade.quests.daily_pool", daily);
        List<Map<?, ?>> before = onDisk.getMapList("arcade.quests.daily_pool");

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(before, onDisk.getMapList("arcade.quests.daily_pool"), "their pool is not touched");
        List<String> warns = warns(log);
        assertTrue(warns.stream().anyMatch(l -> l.contains("arcade.quests.daily_pool") && l.contains("changed")),
                "the WARN names their pool: " + warns);
        String cabinet = "- { id: cabinet_daily, type: FINISH_CABINET, target: 3, reward: 4, "
                + "display: \"Play 3 arcade cabinets\" }";
        String course = "- { id: course_daily, type: FINISH_COURSE, target: 1, reward: 5, "
                + "display: \"Finish a course or a round of golf\" }";
        assertTrue(warns.stream().anyMatch(l -> l.endsWith(cabinet)), "the first line to paste: " + warns);
        assertTrue(warns.stream().anyMatch(l -> l.endsWith(course)), "the second line to paste: " + warns);
        assertTrue(ids(onDisk, "arcade.quests.weekly_pool").contains("stars_weekly"),
                "the weekly pool was still ours, so it gains its rows");
        assertTrue(ids(onDisk, "arcade.achievements").contains("game_record"), "so does the achievements list");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "the WARN comes once");
    }

    @Test
    void theLinesToPasteReadBackAsTheShippedRows() throws Exception {
        YamlConfiguration shipped = bundled();
        for (String path : List.of("arcade.quests.daily_pool", "arcade.quests.weekly_pool", "arcade.achievements")) {
            for (Map<?, ?> row : shipped.getMapList(path)) {
                String line = ArcadeConfigMigration.flowRow(copy(row));
                YamlConfiguration y = new YamlConfiguration();
                y.loadFromString("rows:\n  " + line + "\n");
                assertEquals(List.of(copy(row)), y.getMapList("rows").stream().map(GamesRowsMigrationTest::copy).toList(),
                        "pasting " + line + " gives the row back");
            }
        }
    }

    @Test
    void aRowTheOwnerAlreadyAddedIsNotAddedTwice() throws Exception {
        YamlConfiguration onDisk = rev16();
        List<Map<String, Object>> weekly = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.quests.weekly_pool")) {
            weekly.add(copy(row));
        }
        for (Map<?, ?> row : bundled().getMapList("arcade.quests.weekly_pool")) {
            if ("stars_weekly".equals(row.get("id"))) {
                weekly.add(copy(row)); // added by hand after reading the release notes
            }
        }
        onDisk.set("arcade.quests.weekly_pool", weekly);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        List<String> ids = ids(onDisk, "arcade.quests.weekly_pool");
        assertEquals(1, ids.stream().filter("stars_weekly"::equals).count(), "never twice: " + ids);
        assertTrue(ids.containsAll(List.of("cabinet_weekly", "course_weekly")), "the other two are added: " + ids);
        assertEquals(List.of(), warns(log), "the rest of the pool was ours: " + log);
    }

    @Test
    void anEditedAchievementListIsKeptWithAWarning() throws Exception {
        YamlConfiguration onDisk = rev16();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.achievements")) {
            if (!"jackpot".equals(row.get("id"))) {
                list.add(copy(row)); // the owner took the chance-won one out
            }
        }
        onDisk.set("arcade.achievements", list);

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertFalse(ids(onDisk, "arcade.achievements").contains("jackpot"), "their removal stands");
        assertFalse(ids(onDisk, "arcade.achievements").contains("game_first_cabinet"), "their list isn't touched");
        List<String> warns = warns(log);
        assertTrue(warns.stream().anyMatch(l -> l.contains("arcade.achievements")), "named: " + warns);
        assertEquals(2 + ArcadeConfigMigration.GAME_ACHIEVEMENTS.size() + ArcadeConfigMigration.EVENT_ACHIEVEMENTS.size(),
                warns.stream().filter(l -> l.contains("arcade.achievements") || l.contains("- { id: game_")).count(),
                "one line naming the list per revision step, and one line per achievement to paste: " + warns);
    }

    @Test
    void revisionEighteenAddsRaceNightsAchievementsToAShippedList() throws Exception {
        YamlConfiguration onDisk = rev17();
        YamlConfiguration shipped = bundled();

        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

        assertEquals(ids(shipped, "arcade.achievements"), ids(onDisk, "arcade.achievements"),
                "a server at revision 17 gains game_race_first and game_race_win at the end of its list");
        assertEquals(List.of(), warns(log), "nothing was the owner's: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("game_race_first, game_race_win")), "logged: " + log);
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second pass is a no-op");
    }

    @Test
    void anAbsentListIsLeftToTheBackfill() throws Exception {
        YamlConfiguration onDisk = rev16();
        onDisk.set("arcade.quests.daily_pool", null);

        HomeCraftManagement.migrateConfig(onDisk, "world");
        assertFalse(onDisk.contains("arcade.quests.daily_pool"), "the migration doesn't write a list that isn't there");
        HomeCraftManagement.backfillConfig(onDisk, bundled());
        assertEquals(ids(bundled(), "arcade.quests.daily_pool"), ids(onDisk, "arcade.quests.daily_pool"),
                "the backfill brings the whole shipped pool, game quests and all");
    }
}
