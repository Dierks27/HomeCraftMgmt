package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
 * {@code games.daily} (GEN-SPEC §2.3, §2.4): the bundled block is exactly the defaults with no WARN
 * and ships exactly {@link DailySettings#KEYS}; an out-of-range value is clamped with one WARN naming
 * its key; junk closes Daily Courses alone; a bad slot is switched off alone and the rest keep
 * working; the rollover INFO line comes only when it is on and away from a restart.
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

    /** The shipped {@code games.daily} block as plain maps (a fresh copy). */
    private static Map<String, Object> shipped() throws Exception {
        return GamesConfig.tree(bundled().getConfigurationSection("games.daily"));
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

    private static DailySettings parse(Map<String, Object> daily, List<String> warns) {
        GamesConfig.Node n = new GamesConfig.Node("games.daily", daily, warns::add);
        DailySettings s = DailySettings.parse(n, DailySettings.defaults());
        return n.invalid() ? null : s;
    }

    @Test
    void theBundledBlockIsExactlyTheDefaultsWithNoWarn() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(shipped(), warns);
        assertEquals(List.of(), warns, "the shipped block loads without a WARN");
        assertEquals(DailySettings.defaults(), s, "and reads as the defaults");
        assertFalse(s.enabled(), "Daily Courses ships off");
        assertEquals("", s.world(), "in the first of games.worlds");
        assertEquals(LocalTime.of(4, 0), s.rollover(), "the day starts at 04:00");
        assertNull(s.safeSpot(), "people are moved to the world's spawn");
        assertEquals(List.of(10, 25), s.starGoals(), "10 and 25 stars a week");
        assertEquals(2.0, s.stars().gold("easy"), 1e-9, "gold on easy is twice the expert time");
        assertEquals(1.8, s.stars().silver("hard"), 1e-9, "silver on hard is 1.8 times");
        for (Slots.Def d : Slots.ALL) {
            DailySettings.SlotConfig c = s.slot(d.id());
            assertEquals(DailySettings.SlotConfig.shipped(d), c, d.id() + " ships as its definition says");
        }
        assertEquals(3, s.dailyClear("daily_parkour_hard"), "hard parkour's first finish a day pays 3");
        assertFalse(s.slot("ice_boat").enabled(), "the ice boat ships off");
    }

    @Test
    void theBundledLeavesAreExactlyTheKeysInOrder() throws Exception {
        ConfigurationSection daily = bundled().getConfigurationSection("games.daily");
        List<String> leaves = new ArrayList<>();
        for (String k : daily.getKeys(true)) {
            if (!daily.isConfigurationSection(k)) {
                leaves.add(k);
            }
        }
        assertEquals(DailySettings.KEYS, leaves, "config.yml ships exactly the keys the parser reads");
        assertEquals("enabled", DailySettings.KEYS.get(0), "enabled comes first");
        assertTrue(DailySettings.KEYS.contains("slots.daily_golf.mix"), "golf slots take a mix");
        assertFalse(DailySettings.KEYS.contains("slots.daily_golf.tier"), "not a tier");
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
        clamps.put("slots.tiny_golf.daily_clear", 500);
        clamps.put("slots.daily_parkour_easy.origin", List.of(4100, 160, 4096));
        for (Map.Entry<String, Object> c : clamps.entrySet()) {
            Map<String, Object> daily = shipped();
            put(daily, c.getKey(), c.getValue());
            List<String> warns = new ArrayList<>();
            DailySettings s = parse(daily, warns);
            String key = "games.daily." + c.getKey();
            assertNotNull(s, key + ": a clamp is not junk");
            assertEquals(1, warns.size(), key + ": " + warns);
            assertTrue(warns.get(0).startsWith(key + " "), "the WARN names " + key + ": " + warns);
        }
        Map<String, Object> daily = shipped();
        put(daily, "stars.silver.easy", 1.5);
        DailySettings s = parse(daily, new ArrayList<>());
        assertEquals(2.0, s.stars().silver("easy"), 1e-9, "silver is never faster than gold");
        put(daily, "slots.daily_parkour_easy.origin", List.of(4100, 160, 4111));
        assertArrayEquals(new int[]{4096, 160, 4096}, parse(daily, new ArrayList<>()).slot("daily_parkour_easy")
                .origin(), "an origin off the grid is rounded down");
    }

    @Test
    void junkClosesDailyCoursesAlone() throws Exception {
        for (Object[] junk : new Object[][]{{"rollover", "noon"}, {"rollover", 240}, {"safe_spot", "by the tree"},
                {"star_goals", "lots"}, {"retry_minutes", "soon"}, {"budget", 12}, {"world_rules", "maybe"}}) {
            Map<String, Object> daily = shipped();
            put(daily, (String) junk[0], junk[1]);
            List<String> warns = new ArrayList<>();
            assertNull(parse(daily, warns), junk[0] + " = " + junk[1] + " closes Daily Courses");
            assertEquals(1, warns.size(), junk[0] + ": " + warns);
            assertTrue(warns.get(0).startsWith("games.daily." + junk[0]), "the WARN names it: " + warns);
        }
    }

    @Test
    void aBadSlotIsSwitchedOffAloneAndTheRestKeepWorking() throws Exception {
        Map<String, Object> daily = shipped();
        put(daily, "enabled", true);
        put(daily, "slots.daily_golf.mix", "EEEZ");
        put(daily, "slots.tiny_golf.origin", "somewhere");
        put(daily, "slots.sky_rings", 12);
        put(daily, "slots.daily_parkour_hard.enabled", "maybe");
        put(daily, "slots.daily_parkour_medium.tier", "extreme");
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(daily, warns);
        assertNotNull(s, "a bad slot never closes Daily Courses");
        assertEquals(5, warns.size(), "one WARN each: " + warns);
        for (String off : List.of("daily_golf", "tiny_golf", "sky_rings", "daily_parkour_hard",
                "daily_parkour_medium")) {
            assertFalse(s.slot(off).enabled(), off + " is off");
            assertTrue(warns.stream().anyMatch(w -> w.startsWith("games.daily.slots." + off)), off + " is named");
        }
        assertTrue(s.slot("daily_parkour_easy").enabled(), "easy parkour still works");
        assertTrue(s.enabled(), "and Daily Courses is on");

        Map<String, Object> bare = shipped();
        put(bare, "slots.daily_golf", false);
        List<String> w2 = new ArrayList<>();
        DailySettings s2 = parse(bare, w2);
        assertFalse(s2.slot("daily_golf").enabled(), "a bare false reads as the slot's switch");
        assertEquals(1, w2.size(), "with a WARN to write enabled: false instead: " + w2);
    }

    @Test
    void theSafeSpotStarGoalsAndRolloverAreRead() throws Exception {
        Map<String, Object> daily = shipped();
        put(daily, "safe_spot", "10 70.5 -5");
        put(daily, "star_goals", List.of(25, 10, 10, 40));
        put(daily, "rollover", "05:30");
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(daily, warns);
        assertEquals(List.of(), warns, "all fine");
        assertArrayEquals(new double[]{10, 70.5, -5}, s.safeSpot(), 1e-9, "x y z");
        assertEquals(List.of(10, 25, 40), s.starGoals(), "goals smallest first, each once");
        assertEquals(LocalTime.of(5, 30), s.rollover(), "a rollover of 05:30");
    }

    @Test
    void theRolloverNoteIsOneInfoLineOnlyWhenOnAndAwayFromARestart() throws Exception {
        Map<String, Object> games = GamesConfig.tree(bundled().getConfigurationSection("games"));
        put(games, "enabled", true);
        put(games, "daily.enabled", true);
        put(games, "daily.rollover", "06:00");
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.parse(games, warns::add, infos::add);
        assertEquals(List.of(), warns, "no WARN");
        assertEquals(1, infos.stream().filter(i -> i.startsWith("games.daily.rollover")).count(), "one INFO: " + infos);
        assertTrue(infos.stream().anyMatch(i -> i.contains("players aren't interrupted")), infos.toString());

        put(games, "daily.rollover", "04:00");
        List<String> none = new ArrayList<>();
        GamesConfig.parse(games, warns::add, none::add);
        assertTrue(none.stream().noneMatch(i -> i.startsWith("games.daily")), "at the restart there is nothing to say");
        put(games, "daily.enabled", false);
        put(games, "daily.rollover", "06:00");
        List<String> off = new ArrayList<>();
        GamesConfig.parse(games, warns::add, off::add);
        assertTrue(off.stream().noneMatch(i -> i.startsWith("games.daily")), "and nothing while it is off");
    }
}
