package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.fresh} (GEN-SPEC §2.3, §2.4, weekly addendum §1 and §4): the bundled block is exactly
 * the defaults with no WARN and ships exactly {@link DailySettings#KEYS}; an out-of-range value is
 * clamped with one WARN naming its key; junk closes Fresh Courses alone; a bad slot is switched off
 * alone and the rest keep working; the rebuild_at INFO line comes only when it is on and away from
 * a restart.
 *
 * <p>The cadence keys: {@code cadence} takes weekly, daily or 1-28 (quoted too) and anything else is
 * one WARN and weekly; {@code rebuild_at} and {@code rebuild_day} the same, never closing anything;
 * both reward ends and both goal ends are read, and each slot's first-finish tokens follow the
 * cadence.
 */
class DailySettingsTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = DailySettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return c;
            }
        }
    }

    /** The shipped {@code games.fresh} block as plain maps (a fresh copy). */
    private static Map<String, Object> shipped() throws Exception {
        return GamesConfig.tree(bundled().getConfigurationSection("games.fresh"));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String dotted, Object value) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> m = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = m.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                m.put(parts[i], next);
            }
            m = (Map<String, Object>) next;
        }
        m.put(parts[parts.length - 1], value);
    }

    private static DailySettings parse(Map<String, Object> fresh, List<String> warns) {
        GamesConfig.Node n = new GamesConfig.Node("games.fresh", fresh, warns::add);
        DailySettings s = DailySettings.parse(n, DailySettings.defaults());
        return n.invalid() ? null : s;
    }

    private static DailySettings with(String key, Object value, List<String> warns) throws Exception {
        Map<String, Object> fresh = shipped();
        put(fresh, key, value);
        return parse(fresh, warns);
    }

    @Test
    void theBundledBlockIsExactlyTheDefaultsWithNoWarn() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(shipped(), warns);
        assertEquals(List.of(), warns, "the shipped block loads without a WARN");
        assertEquals(DailySettings.defaults(), s, "and reads as the defaults");
        assertFalse(s.enabled(), "Fresh Courses ships off");
        assertEquals("", s.world(), "in the first of games.worlds");
        assertEquals(7, s.cadenceDays(), "weekly by default");
        assertEquals("weekly", s.cadenceName(), "and says so");
        assertEquals(LocalTime.of(4, 0), s.rollover(), "a new set starts at 04:00");
        assertNull(s.rebuildDay(), "on the quests' week start");
        assertNull(s.safeSpot(), "people are moved to the world's spawn");
        assertEquals(List.of(6, 12), s.starGoals(), "6 and 12 stars a week");
        assertEquals(List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2)), s.starGoalList(),
                "paying 1 and 2 tokens");
        assertEquals(2.0, s.stars().gold("easy"), 1e-9, "gold on easy is twice the expert time");
        assertEquals(1.8, s.stars().silver("hard"), 1e-9, "silver on hard is 1.8 times");
        for (Slots.Def d : Slots.ALL) {
            DailySettings.SlotConfig c = s.slot(d.id());
            assertEquals(DailySettings.SlotConfig.shipped(d), c, d.id() + " ships as its definition says");
            assertEquals(d.weeklyClear(), s.rewards().clearWeekly().get(d.id()), d.id() + ": the weekly table");
            assertEquals(d.dailyClear(), s.rewards().clearDaily().get(d.id()), d.id() + ": the daily table");
        }
        assertEquals(5, s.dailyClear("fresh_parkour_hard"), "hard parkour's first finish in a week pays 5");
        assertFalse(s.slot("fresh_boat").enabled(), "the ice boat ships off");
    }

    @Test
    void theBundledLeavesAreExactlyTheKeysInOrder() throws Exception {
        ConfigurationSection fresh = bundled().getConfigurationSection("games.fresh");
        assertNotNull(fresh, "the block is games.fresh");
        assertNull(bundled().getConfigurationSection("games.daily"), "games.daily is gone (it never shipped)");
        List<String> leaves = new ArrayList<>();
        for (String k : fresh.getKeys(true)) {
            if (!fresh.isConfigurationSection(k)) {
                leaves.add(k);
            }
        }
        assertEquals(DailySettings.KEYS, leaves, "config.yml ships exactly the keys the parser reads");
        assertEquals(List.of("enabled", "world", "cadence", "rebuild_at", "rebuild_day"), DailySettings.KEYS.subList(0,
                5), "the switch, the world and the schedule come first");
        assertTrue(DailySettings.KEYS.contains("slots.fresh_golf.mix"), "golf slots take a mix");
        assertFalse(DailySettings.KEYS.contains("slots.fresh_golf.tier"), "not a tier");
        assertTrue(DailySettings.KEYS.contains("rewards.clear_weekly.fresh_golf")
                && DailySettings.KEYS.contains("rewards.clear_daily.fresh_golf"), "both reward tables");
        assertTrue(DailySettings.KEYS.containsAll(List.of("star_goals.weekly", "star_goals.weekly_tokens",
                "star_goals.daily", "star_goals.daily_tokens")), "both goal ends");
        assertFalse(DailySettings.KEYS.contains("slots.fresh_golf.daily_clear"), "the tokens moved to rewards");
    }

    @Test
    void theCadenceTakesWeeklyDailyOrADayCountAndAnythingElseIsOneWarnAndWeekly() throws Exception {
        Object[][] fine = {{"weekly", 7}, {"daily", 1}, {"Daily", 1}, {" WEEKLY ", 7}, {3, 3}, {"3", 3}, {2, 2},
                {1, 1}, {14, 14}, {28, 28}, {"28", 28}, {7.0, 7}};
        for (Object[] f : fine) {
            List<String> warns = new ArrayList<>();
            DailySettings s = with("cadence", f[0], warns);
            assertEquals(List.of(), warns, "cadence " + f[0] + " is fine");
            assertEquals(f[1], s.cadenceDays(), "cadence " + f[0] + " is " + f[1] + " days");
        }
        for (Object junk : new Object[]{"fortnightly", 0, 29, 30, -7, 7.5, "3 days", "", true, List.of(7)}) {
            List<String> warns = new ArrayList<>();
            DailySettings s = with("cadence", junk, warns);
            assertNotNull(s, "cadence " + junk + " never closes Fresh Courses");
            assertEquals(7, s.cadenceDays(), "cadence " + junk + " falls back to weekly");
            assertEquals(1, warns.size(), "with one WARN: " + warns);
            assertTrue(warns.get(0).startsWith("games.fresh.cadence ") && warns.get(0).endsWith("using weekly"),
                    "naming the key and what it does: " + warns);
        }
    }

    @Test
    void rebuildAtAndRebuildDayAreReadAndJunkIsOneWarnAndTheDefault() throws Exception {
        List<String> warns = new ArrayList<>();
        assertEquals(LocalTime.of(5, 30), with("rebuild_at", "05:30", warns).rollover(), "a rebuild_at of 05:30");
        assertEquals(DayOfWeek.THURSDAY, with("rebuild_day", "thursday", warns).rebuildDay(), "Thursday");
        assertEquals(DayOfWeek.SUNDAY, with("rebuild_day", " Sun ", warns).rebuildDay(), "three letters, any case");
        assertNull(with("rebuild_day", "", warns).rebuildDay(), "\"\" is the quests' week start");
        assertEquals(List.of(), warns, "all fine");
        for (Object[] junk : new Object[][]{{"rebuild_at", "noon"}, {"rebuild_at", 240}, {"rebuild_at", "25:00"},
                {"rebuild_day", "someday"}, {"rebuild_day", 3}, {"rebuild_day", "th"}}) {
            List<String> w = new ArrayList<>();
            DailySettings s = with((String) junk[0], junk[1], w);
            assertNotNull(s, junk[0] + " = " + junk[1] + " never closes Fresh Courses");
            assertEquals(1, w.size(), junk[0] + ": one WARN: " + w);
            assertTrue(w.get(0).startsWith("games.fresh." + junk[0] + " "), "naming the key: " + w);
            assertEquals(LocalTime.of(4, 0), s.rollover(), "rebuild_at falls back to 04:00");
            assertNull(s.rebuildDay(), "rebuild_day falls back to the quests' week start");
        }
        Edition ed = with("rebuild_day", "thursday", new ArrayList<>()).edition(java.time.ZoneOffset.UTC,
                DayOfWeek.MONDAY);
        assertEquals(DayOfWeek.THURSDAY, ed.rebuildDay(), "the edition rules take the rebuild day");
        assertEquals(7, ed.cadenceDays(), "and the cadence");
        assertEquals(DayOfWeek.SATURDAY, DailySettings.defaults().edition(null, DayOfWeek.SATURDAY).rebuildDay(),
                "an empty rebuild day is the quests' week start");
    }

    @Test
    void eachCoursesFirstFinishFollowsTheCadenceBetweenTheTwoTables() throws Exception {
        assertEquals(3, with("cadence", "daily", new ArrayList<>()).dailyClear("fresh_parkour_hard"), "daily: 3");
        assertEquals(4, with("cadence", 3, new ArrayList<>()).dailyClear("fresh_parkour_hard"), "every 3 days: 4");
        assertEquals(5, with("cadence", 14, new ArrayList<>()).dailyClear("fresh_parkour_hard"), "every 14 days: 5");
        Map<String, Object> fresh = shipped();
        put(fresh, "cadence", 2);
        put(fresh, "rewards.clear_daily.fresh_golf", 4);
        put(fresh, "rewards.clear_weekly.fresh_golf", 10);
        DailySettings s = parse(fresh, new ArrayList<>());
        assertEquals(5, s.dailyClear("fresh_golf"), "the owner's own tables: round(4 + 6 * 1/6)");
        assertEquals(10, s.withCadence(7).dailyClear("fresh_golf"), "and switching the cadence needs no retuning");
        assertEquals(4, s.withCadence(1).dailyClear("fresh_golf"), "either way");
        assertEquals(10, s.dailyClear("fresh_golf", 7), "a finish on a weekly layout kept after the switch to 2 days "
                + "pays by its own edition");
        assertEquals(4, s.dailyClear("fresh_golf", 1), "a daily one likewise");
        assertEquals(5, s.dailyClear("fresh_golf", 2), "the configured cadence is the same as dailyClear(id)");
        assertEquals(0, s.dailyClear("river_run", 7), "a course that isn't a slot pays nothing here");
        assertEquals(List.of(10, 25), with("cadence", "daily", new ArrayList<>()).starGoals(), "daily goals");
        assertEquals(List.of(new DailyStars.Goal(9, 1), new DailyStars.Goal(21, 1)),
                with("cadence", 3, new ArrayList<>()).starGoalList(), "every 3 days: in between, daily tokens");
        assertEquals(List.of(new DailyStars.Goal(4, 2)), DailySettings.defaults().starGoals(6),
                "never above 80% of what the week can give");
    }

    @Test
    void theStarGoalsAreReadWithTheirTokens() throws Exception {
        Map<String, Object> fresh = shipped();
        put(fresh, "star_goals.weekly", List.of(12, 5, 5, 20));
        put(fresh, "star_goals.weekly_tokens", List.of(2, 1, 1, 3));
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(fresh, warns);
        assertEquals(List.of(), warns, "all fine");
        assertEquals(List.of(new DailyStars.Goal(5, 1), new DailyStars.Goal(12, 2), new DailyStars.Goal(20, 3)),
                s.goals().weekly(), "smallest first, each once, each with its own tokens");
        put(fresh, "star_goals.weekly_tokens", List.of(2));
        List<String> w2 = new ArrayList<>();
        DailySettings s2 = parse(fresh, w2);
        assertEquals(1, w2.size(), "a token list of another length is one WARN: " + w2);
        assertTrue(w2.get(0).startsWith("games.fresh.star_goals.weekly_tokens "), w2.toString());
        assertEquals(List.of(new DailyStars.Goal(5, 2), new DailyStars.Goal(12, 2), new DailyStars.Goal(20, 2)),
                s2.goals().weekly(), "the last amount is used for the rest");
    }

    @Test
    void anOutOfRangeValueIsClampedWithOneWarnNamingItsKey() throws Exception {
        Map<String, Object> clamps = new LinkedHashMap<>();
        clamps.put("retry_minutes", 0);
        clamps.put("keep_days", 1);
        clamps.put("budget.blocks_per_tick", 0);
        clamps.put("budget.pause_above_mspt", 5);
        clamps.put("stars.gold.hard", 0.5);
        clamps.put("stars.silver.easy", 1.5);
        clamps.put("rewards.clear_weekly.fresh_tiny_golf", 500);
        clamps.put("rewards.clear_daily.fresh_golf", -1);
        clamps.put("star_goals.weekly_tokens", List.of(1, 200));
        clamps.put("slots.fresh_parkour_easy.origin", List.of(4100, 160, 4096));
        for (Map.Entry<String, Object> c : clamps.entrySet()) {
            List<String> warns = new ArrayList<>();
            DailySettings s = with(c.getKey(), c.getValue(), warns);
            String key = "games.fresh." + c.getKey();
            assertNotNull(s, key + ": a clamp is not junk");
            assertEquals(1, warns.size(), key + ": " + warns);
            assertTrue(warns.get(0).startsWith(key + " "), "the WARN names " + key + ": " + warns);
        }
        DailySettings s = with("stars.silver.easy", 1.5, new ArrayList<>());
        assertEquals(2.0, s.stars().silver("easy"), 1e-9, "silver is never faster than gold");
        assertArrayEquals(new int[]{4096, 160, 4096}, with("slots.fresh_parkour_easy.origin",
                List.of(4100, 160, 4111), new ArrayList<>()).slot("fresh_parkour_easy").origin(),
                "an origin off the grid is rounded down");
    }

    @Test
    void junkClosesFreshCoursesAlone() throws Exception {
        for (Object[] junk : new Object[][]{{"safe_spot", "by the tree"}, {"star_goals.weekly", "lots"},
                {"retry_minutes", "soon"}, {"budget", 12}, {"world_rules", "maybe"},
                {"rewards.clear_weekly.fresh_golf", "lots"}}) {
            List<String> warns = new ArrayList<>();
            assertNull(with((String) junk[0], junk[1], warns), junk[0] + " = " + junk[1] + " closes Fresh Courses");
            assertEquals(1, warns.size(), junk[0] + ": " + warns);
            assertTrue(warns.get(0).startsWith("games.fresh." + junk[0]), "the WARN names it: " + warns);
        }
    }

    @Test
    void aBadSlotIsSwitchedOffAloneAndTheRestKeepWorking() throws Exception {
        Map<String, Object> fresh = shipped();
        put(fresh, "enabled", true);
        put(fresh, "slots.fresh_golf.mix", "EEEZ");
        put(fresh, "slots.fresh_tiny_golf.origin", "somewhere");
        put(fresh, "slots.fresh_rings", 12);
        put(fresh, "slots.fresh_parkour_hard.enabled", "maybe");
        put(fresh, "slots.fresh_parkour.tier", "extreme");
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(fresh, warns);
        assertNotNull(s, "a bad slot never closes Fresh Courses");
        assertEquals(5, warns.size(), "one WARN each: " + warns);
        for (String off : List.of("fresh_golf", "fresh_tiny_golf", "fresh_rings", "fresh_parkour_hard",
                "fresh_parkour")) {
            assertFalse(s.slot(off).enabled(), off + " is off");
            assertTrue(warns.stream().anyMatch(w -> w.startsWith("games.fresh.slots." + off + " ")
                    || w.startsWith("games.fresh.slots." + off + ".")), off + " is named");
        }
        assertTrue(s.slot("fresh_parkour_easy").enabled(), "easy parkour still works");
        assertTrue(s.enabled(), "and Fresh Courses is on");

        Map<String, Object> bare = shipped();
        put(bare, "slots.fresh_golf", false);
        List<String> w2 = new ArrayList<>();
        DailySettings s2 = parse(bare, w2);
        assertFalse(s2.slot("fresh_golf").enabled(), "a bare false reads as the slot's switch");
        assertEquals(1, w2.size(), "with a WARN to write enabled: false instead: " + w2);
    }

    @Test
    void theSafeSpotIsRead() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with("safe_spot", "10 70.5 -5", warns);
        assertEquals(List.of(), warns, "all fine");
        assertArrayEquals(new double[]{10, 70.5, -5}, s.safeSpot(), 1e-9, "x y z");
    }

    @Test
    void theRebuildNoteIsOneInfoLineOnlyWhenOnAndAwayFromARestart() throws Exception {
        Map<String, Object> games = GamesConfig.tree(bundled().getConfigurationSection("games"));
        put(games, "enabled", true);
        put(games, "fresh.enabled", true);
        put(games, "fresh.rebuild_at", "06:00");
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, infos::add);
        assertEquals(List.of(), warns, "no WARN");
        assertTrue(parsed.readable("fresh_courses"), "the fresh block is fresh_courses' settings");
        assertEquals(LocalTime.of(6, 0), parsed.settings(DailyCourses.SPEC).rollover(), "read from games.fresh");
        assertEquals(1, infos.stream().filter(i -> i.startsWith("games.fresh.rebuild_at")).count(),
                "one INFO: " + infos);
        assertTrue(infos.stream().anyMatch(i -> i.contains("players aren't interrupted")), infos.toString());

        put(games, "fresh.rebuild_at", "04:00");
        List<String> none = new ArrayList<>();
        GamesConfig.parse(games, warns::add, none::add);
        assertTrue(none.stream().noneMatch(i -> i.startsWith("games.fresh")), "at the restart there is nothing to say");
        put(games, "fresh.enabled", false);
        put(games, "fresh.rebuild_at", "06:00");
        List<String> off = new ArrayList<>();
        GamesConfig.parse(games, warns::add, off::add);
        assertTrue(off.stream().noneMatch(i -> i.startsWith("games.fresh")), "and nothing while it is off");
    }
}
