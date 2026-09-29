package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.AchievementDef;
import com.dierks.homecraft.config.PluginConfig.AchievementType;
import com.dierks.homecraft.config.PluginConfig.Quest;
import com.dierks.homecraft.config.PluginConfig.QuestPeriod;
import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.web.ArcadeFeed;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped game quests and "Games" achievements (EXTRAS E4, and the Dropper's clean drop of
 * EVENTS-DROPPER-SPEC §B.1.8): exactly the rows the specs name,
 * each achievement a counter that unlocks at its target with its reward, "every cabinet" pinned to
 * the catalog's cabinets, nothing tied to a game of chance, the chance-won achievements left as they
 * were, kid-safe words; and a game quest is dealt only while its games are open.
 */
class GameAchievementsTest {

    static YamlConfiguration bundled() throws Exception {
        try (InputStream in = GameAchievementsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    /** The shipped achievement rows of the "Games" group. */
    static List<Map<String, Object>> gamesRows() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> row : bundled().getMapList("arcade.achievements")) {
            if ("Games".equals(row.get("group"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                row.forEach((k, v) -> m.put(String.valueOf(k), v));
                out.add(m);
            }
        }
        return out;
    }

    /** A shipped row as the config reads it (COUNTER rows only, which is all the Games group is). */
    private static AchievementDef def(Map<String, Object> row) {
        return new AchievementDef(String.valueOf(row.get("id")), String.valueOf(row.get("group")),
                AchievementType.valueOf(String.valueOf(row.get("type"))), String.valueOf(row.get("counter")),
                ((Number) row.get("target")).doubleValue(), true, ((Number) row.get("reward")).intValue(),
                String.valueOf(row.get("display")));
    }

    private static int cabinets() {
        int n = 0;
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            n += spec.kind() == GameKind.CABINET ? 1 : 0;
        }
        return n;
    }

    @Test
    void theGamesGroupShipsExactlyTheTwelveAchievementsWithTheirRewards() throws Exception {
        Map<String, Object[]> expected = new LinkedHashMap<>();
        expected.put("game_first_cabinet", new Object[]{"Finish an arcade cabinet game", 10, "cabinet_finishes", 1});
        expected.put("game_gold", new Object[]{"Earn a gold medal in a cabinet", 20, "cabinet_golds", 1});
        expected.put("game_all_cabinets", new Object[]{"Finish every arcade cabinet game", 30, "cabinets", cabinets()});
        expected.put("game_first_course", new Object[]{"Finish a course", 15, "course_finishes", 1});
        expected.put("game_hole_in_one", new Object[]{"Get a hole-in-one", 25, "holes_in_one", 1});
        expected.put("game_under_par", new Object[]{"Finish a golf course under par", 25, "golf_under_par", 1});
        expected.put("game_fresh_all", new Object[]{"Finish every Fresh Course in one set", 40, "fresh_sets", 1});
        expected.put("game_star_chart", new Object[]{"Reach the top Star Chart goal in a week", 30, "star_chart_tops", 1});
        expected.put("game_record", new Object[]{"Set a course record", 30, "course_records", 1});
        expected.put("game_dropper_clean", new Object[]{"Reach the bottom of a Dropper with no bonks", 20,
                "dropper_clean", 1}); // EVENTS-DROPPER-SPEC §B.1.8, config revision 18
        expected.put("game_race_first", new Object[]{"Race at Race Night", 10, "race_nights", 1});
        expected.put("game_race_win", new Object[]{"Win a Race Night", 30, "race_night_wins", 1});
        List<Map<String, Object>> rows = gamesRows();
        assertEquals(new ArrayList<>(expected.keySet()), rows.stream().map(r -> String.valueOf(r.get("id"))).toList(),
                "the Games group, in the spec's order");
        for (Map<String, Object> row : rows) {
            Object[] e = expected.get(String.valueOf(row.get("id")));
            assertEquals(e[0], row.get("display"), row.get("id") + ": its words");
            assertEquals(e[1], row.get("reward"), row.get("id") + ": its reward");
            assertEquals("COUNTER", row.get("type"), row.get("id") + ": a counter, so a finish in the Games world "
                    + "(where no tokens are paid) unlocks back home");
            assertEquals(e[2], row.get("counter"), row.get("id") + ": the counter it reads");
            assertEquals(e[3], row.get("target"), row.get("id") + ": its target");
        }
        assertEquals(8, cabinets(), "every cabinet is the catalog's 8; a new cabinet needs game_all_cabinets retargeted");
    }

    @Test
    void eachAchievementUnlocksAtItsTargetAndNotBefore() throws Exception {
        List<AchievementDef> defs = new ArrayList<>();
        for (Map<String, Object> row : gamesRows()) {
            defs.add(def(row));
        }
        for (AchievementDef d : defs) {
            long target = (long) d.target();
            assertFalse(AchievementService.counterUnlocks(defs, d.key(), target - 1).contains(d.id()),
                    d.id() + " is still locked one short of its target");
            assertTrue(AchievementService.counterUnlocks(defs, d.key(), target).contains(d.id()),
                    d.id() + " unlocks at " + target + " " + d.key());
            assertTrue(d.reward() > 0, d.id() + " pays tokens when it unlocks");
        }
        assertEquals(List.of(), AchievementService.counterUnlocks(defs, "cabinets", cabinets() - 1),
                "one cabinet short of every cabinet unlocks nothing");
        assertEquals(List.of("game_all_cabinets"), AchievementService.counterUnlocks(defs, "cabinets", cabinets()),
                "every cabinet unlocks it");
        assertEquals(List.of(), AchievementService.counterUnlocks(defs, "jackpots", 1000),
                "no Games achievement reads a game of chance's counter");
    }

    @Test
    void nothingNewIsTiedToAGameOfChanceAndTheOldChanceAchievementsStayAsTheyWere() throws Exception {
        Set<String> chanceCounters = Set.of("jackpots", "crates");
        for (Map<String, Object> row : gamesRows()) {
            AchievementDef d = def(row);
            assertFalse(ArcadeFeed.chanceWin(d), d.id() + " isn't won by a game of chance");
            assertFalse(chanceCounters.contains(d.key()), d.id() + " doesn't count crates or jackpots");
        }
        Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
        for (Map<?, ?> row : bundled().getMapList("arcade.achievements")) {
            byId.put(String.valueOf(row.get("id")), row);
        }
        assertEquals(25, byId.get("jackpot").get("reward"), "jackpot is left as it was (the owner may remove it)");
        assertEquals(5, byId.get("first_crate").get("reward"), "first_crate is left as it was");
    }

    @Test
    void theGameQuestsShipInThePoolsWithTheSpecsNumbers() throws Exception {
        YamlConfiguration c = bundled();
        Map<String, Object[]> daily = new LinkedHashMap<>();
        daily.put("cabinet_daily", new Object[]{"FINISH_CABINET", 3, 4, "Play 3 arcade cabinets"});
        daily.put("course_daily", new Object[]{"FINISH_COURSE", 1, 5, "Finish a course or a round of golf"});
        Map<String, Object[]> weekly = new LinkedHashMap<>();
        weekly.put("cabinet_weekly", new Object[]{"FINISH_CABINET", 15, 20, "Play 15 arcade cabinets"});
        weekly.put("course_weekly", new Object[]{"FINISH_COURSE", 5, 20, "Finish 5 courses or golf rounds"});
        weekly.put("stars_weekly", new Object[]{"EARN_STARS", 6, 20, "Earn 6 Fresh Courses stars"});
        for (Map.Entry<String, Map<String, Object[]>> pool : Map.of("arcade.quests.daily_pool", daily,
                "arcade.quests.weekly_pool", weekly).entrySet()) {
            List<Map<?, ?>> rows = c.getMapList(pool.getKey());
            List<String> ids = rows.stream().map(r -> String.valueOf(r.get("id"))).toList();
            List<String> newIds = new ArrayList<>(pool.getValue().keySet());
            assertEquals(newIds, ids.subList(ids.size() - newIds.size(), ids.size()),
                    pool.getKey() + ": the game quests are appended after the rows it already had");
            for (Map<?, ?> r : rows) {
                Object[] e = pool.getValue().get(String.valueOf(r.get("id")));
                if (e == null) {
                    continue;
                }
                assertEquals(e[0], r.get("type"), r.get("id") + ": type");
                assertEquals(e[1], r.get("target"), r.get("id") + ": target");
                assertEquals(e[2], r.get("reward"), r.get("id") + ": reward");
                assertEquals(e[3], r.get("display"), r.get("id") + ": words");
            }
        }
    }

    @Test
    void aGameQuestIsDealtOnlyWhileItsGamesAreOpenAndStillNoTwoOfOneType() {
        List<Quest> pool = List.of(
                new Quest("fish_daily", QuestPeriod.DAILY, QuestType.CATCH_FISH, 8, 5, "Catch 8 fish"),
                new Quest("walk_daily", QuestPeriod.DAILY, QuestType.TRAVEL_ON_FOOT, 1000, 4, "Walk"),
                new Quest("cabinet_daily", QuestPeriod.DAILY, QuestType.FINISH_CABINET, 3, 4, "Play 3 arcade cabinets"),
                new Quest("course_daily", QuestPeriod.DAILY, QuestType.FINISH_COURSE, 1, 5, "Finish a course"),
                new Quest("cabinet_again", QuestPeriod.DAILY, QuestType.FINISH_CABINET, 5, 5, "Play 5 arcade cabinets"));
        for (int seed = 0; seed < 50; seed++) {
            List<Quest> closed = QuestService.draw(pool, 5, new Random(seed), q -> !q.type().game());
            assertTrue(closed.stream().noneMatch(q -> q.type().game()), "games closed: no game quest dealt: " + closed);
            assertEquals(2, closed.size(), "the two ordinary quests are still dealt");

            List<Quest> open = QuestService.draw(pool, 5, new Random(seed), q -> true);
            Set<QuestType> types = EnumSet.noneOf(QuestType.class);
            for (Quest q : open) {
                assertTrue(types.add(q.type()), "no two quests in one draw share a type: " + open);
            }
            assertEquals(4, open.size(), "games open: one of each type is dealt: " + open);
        }
        assertEquals(QuestService.draw(pool, 3, new Random(9)), QuestService.draw(pool, 3, new Random(9), q -> true),
                "the old draw is the filtered one with nothing filtered");
    }

    @Test
    void theWordsAreKidSafe() throws Exception {
        List<String> words = new ArrayList<>();
        for (Map<String, Object> row : gamesRows()) {
            words.add(String.valueOf(row.get("display")));
        }
        YamlConfiguration c = bundled();
        for (String pool : List.of("arcade.quests.daily_pool", "arcade.quests.weekly_pool")) {
            for (Map<?, ?> r : c.getMapList(pool)) {
                if (QuestType.valueOf(String.valueOf(r.get("type"))).game()) {
                    words.add(String.valueOf(r.get("display")));
                }
            }
        }
        assertEquals(12 + 5, words.size(), "every new line is checked (the Dropper's and Race Night's too)");
        for (String w : words) {
            assertEquals(List.of(), GenCopy.copyProblems(w), "no banned word and nothing Bedrock can't draw: " + w);
            String lower = " " + w.toLowerCase(Locale.ROOT) + " ";
            for (String banned : List.of(" bet ", "wager", "gamble", "casino", "lucky", "almost", "so close", " sink")) {
                assertFalse(lower.contains(banned), w + " says " + banned.trim());
            }
        }
    }
}
