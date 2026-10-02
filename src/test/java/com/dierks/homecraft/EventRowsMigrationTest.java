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
 * Config revision 18: the events batch's "Games" achievements (the Dropper's clean drop, Race Night's
 * two and Falling Floors' whole minute) reach every server in ONE step, and revision 17 still adds
 * exactly what it added on the final-round build (the owner may have run it).
 *
 * <p>Pinned here, each ending with every Games row exactly once: a fresh install; an upgrade from
 * revision 16 (0.34); an upgrade from revision 17; a revision-17 list that already has one of the
 * new rows; and an owner-edited list, which is never clobbered (one WARN with the lines to paste).
 */
class EventRowsMigrationTest {

    /** Revision 17's "Games" achievements exactly as the final-round build (e45140a) shipped them. */
    private static final List<String> REVISION_17 = List.of("game_first_cabinet", "game_gold", "game_all_cabinets",
            "game_first_course", "game_hole_in_one", "game_under_par", "game_fresh_all", "game_star_chart",
            "game_record");
    /** What revision 18 adds, in the order it ships. */
    private static final List<String> REVISION_18 = List.of("game_dropper_clean", "game_race_first", "game_race_win",
            "game_floors_minute");

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = EventRowsMigrationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    /** A revision-16 (0.34) file: the bundled one without revision 17's rows or revision 18's. */
    private static YamlConfiguration rev16() throws Exception {
        YamlConfiguration c = bundled();
        c.set("config_revision", 16);
        without(c, "arcade.quests.daily_pool", ArcadeConfigMigration.GAME_DAILY);
        without(c, "arcade.quests.weekly_pool", ArcadeConfigMigration.GAME_WEEKLY);
        without(c, "arcade.achievements", REVISION_17);
        without(c, "arcade.achievements", REVISION_18);
        return c;
    }

    /** A revision-17 file: the bundled one without revision 18's rows. */
    private static YamlConfiguration rev17() throws Exception {
        YamlConfiguration c = bundled();
        c.set("config_revision", 17);
        without(c, "arcade.achievements", REVISION_18);
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

    /** Every Games row (revision 17's and 18's) is in the list exactly once. */
    private static void everyRowOnce(List<String> ids, String when) {
        for (String id : concat(REVISION_17, REVISION_18)) {
            assertEquals(1, ids.stream().filter(id::equals).count(), when + ": " + id + " exactly once in " + ids);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    void theTwoRevisionsAddWhatTheyShouldAndNothingElse() {
        assertEquals(REVISION_17, ArcadeConfigMigration.GAME_ACHIEVEMENTS,
                "revision 17 adds exactly what the final-round build's did (an owner may have run it)");
        assertEquals(REVISION_18, ArcadeConfigMigration.EVENT_ACHIEVEMENTS,
                "ONE revision 18 adds the whole events batch: the Dropper's, Race Night's and Falling Floors'");
        assertEquals(21, HomeCraftManagement.CONFIG_REVISION,
                "the newest is 21 now (19 the Games layout, LayoutGuardTest; 21 the v4 areas, GamesAreaMigrationTest):"
                        + " neither adds an achievement row");
    }

    @Test
    void aFreshInstallShipsEveryRowOnceAndNeedsNoMigration() throws Exception {
        YamlConfiguration fresh = bundled();
        assertEquals(HomeCraftManagement.CONFIG_REVISION, fresh.getInt("config_revision"),
                "the bundled config says it is the newest revision");
        everyRowOnce(ids(fresh), "a fresh install");
        assertEquals(REVISION_18, ids(fresh).subList(ids(fresh).size() - REVISION_18.size(), ids(fresh).size()),
                "the events batch's rows ship last, in their order");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(fresh, "world"), "nothing to migrate");
        everyRowOnce(ids(fresh), "and after a start");
    }

    @Test
    void anUpgradeFromRevisionSixteenGainsBothRevisionsOnce() throws Exception {
        YamlConfiguration onDisk = rev16();
        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        assertEquals(ids(bundled()), ids(onDisk), "exactly what a fresh install ships, in the same order");
        everyRowOnce(ids(onDisk), "0.34 (revision 16) upgraded");
        assertEquals(List.of(), warns(log), "nothing was the owner's: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains(String.join(", ", REVISION_17))), "17's rows logged: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains(String.join(", ", REVISION_18))), "18's rows logged: " + log);
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"),
                "stamped the newest revision");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second start is a no-op");
        everyRowOnce(ids(onDisk), "after a second start");
    }

    @Test
    void revisionSeventeensStepAloneStillAddsOnlyItsOwnRows() throws Exception {
        YamlConfiguration onDisk = rev16();
        List<String> before = ids(onDisk);
        List<String> log = new ArrayList<>();
        ArcadeConfigMigration.gamesRows(onDisk, log);
        assertEquals(concat(before, REVISION_17), ids(onDisk),
                "revision 17 appends its nine rows and none of revision 18's, as it always did");
        assertEquals(List.of(), warns(log), "the list it compares with leaves out the later rows: " + log);
    }

    @Test
    void anUpgradeFromRevisionSeventeenGainsTheEventRowsAtItsEndOnce() throws Exception {
        YamlConfiguration onDisk = rev17();
        List<String> before = ids(onDisk);
        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        assertEquals(concat(before, REVISION_18), ids(onDisk), "the four new rows at the end, in their order");
        assertEquals(ids(bundled()), ids(onDisk), "exactly what a fresh install ships");
        everyRowOnce(ids(onDisk), "revision 17 upgraded");
        assertEquals(List.of(), warns(log), "nothing was the owner's: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("added the \"Games\" achievements to arcade.achievements ("
                + String.join(", ", REVISION_18) + ")")), "the INFO line reads plainly too: " + log);
        assertEquals(HomeCraftManagement.CONFIG_REVISION, onDisk.getInt("config_revision"),
                "stamped the newest revision");
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), "a second start is a no-op");
    }

    @Test
    void aRowAlreadyThereIsNeverAddedTwice() throws Exception {
        YamlConfiguration onDisk = rev17();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<?, ?> row : onDisk.getMapList("arcade.achievements")) {
            list.add(copy(row));
        }
        for (Map<?, ?> row : bundled().getMapList("arcade.achievements")) {
            if ("game_race_win".equals(row.get("id"))) {
                list.add(copy(row)); // pasted by hand from the release notes
            }
        }
        onDisk.set("arcade.achievements", list);
        List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");
        everyRowOnce(ids(onDisk), "a list with one of the rows already");
        assertEquals(List.of(), warns(log), "the rest of the list was ours: " + log);

        YamlConfiguration twice = bundled();
        twice.set("config_revision", 17); // a revision-17 stamp on a list that has everything
        HomeCraftManagement.migrateConfig(twice, "world");
        everyRowOnce(ids(twice), "a list that has them all");
    }

    @Test
    void anOwnerEditedListIsNeverClobbered() throws Exception {
        for (YamlConfiguration onDisk : List.of(rev17(), rev16())) {
            int from = onDisk.getInt("config_revision");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<?, ?> row : onDisk.getMapList("arcade.achievements")) {
                if (!"jackpot".equals(row.get("id"))) {
                    list.add(copy(row)); // the owner took one out
                }
            }
            onDisk.set("arcade.achievements", list);
            List<Map<?, ?>> theirs = onDisk.getMapList("arcade.achievements");

            List<String> log = HomeCraftManagement.migrateConfig(onDisk, "world");

            assertEquals(theirs, onDisk.getMapList("arcade.achievements"), from + ": their list is exactly as they left it");
            for (String id : REVISION_18) {
                assertFalse(ids(onDisk).contains(id), from + ": " + id + " isn't added to an edited list");
            }
            List<String> warns = warns(log);
            assertTrue(warns.stream().anyMatch(l -> l.contains("arcade.achievements") && l.contains("changed")),
                    from + ": a WARN names the list: " + warns);
            // WP-ADM: revision 18's WARN once read "so the new new "Games" achievements were not added"
            assertTrue(warns.stream().anyMatch(l -> l.contains("so the new \"Games\" achievements were not added")),
                    from + ": the WARN says what wasn't added, in plain words: " + warns);
            assertTrue(log.stream().noneMatch(l -> l.contains("new new")), from + ": never \"new new\": " + log);
            for (String id : REVISION_18) {
                assertEquals(1, warns.stream().filter(l -> l.contains("- { id: " + id + ",")).count(),
                        from + ": the line to paste for " + id + ", once: " + warns);
            }
            assertTrue(warns.stream().anyMatch(l -> l.endsWith("- { id: game_floors_minute, group: \"Games\", type:"
                    + " COUNTER, counter: floors_minutes, target: 1, reward: 15, display: \"Last a whole minute on"
                    + " Falling Floors\" }")), from + ": the line reads back as the shipped row: " + warns);
            assertEquals(List.of(), HomeCraftManagement.migrateConfig(onDisk, "world"), from + ": the WARN comes once");
        }
    }
}
