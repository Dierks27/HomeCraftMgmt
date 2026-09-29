package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The droppers in {@code games.fresh} (EVENTS-DROPPER-SPEC §B.1.2, §B.1.8; WIRING §2): their rows ship
 * off with a {@code mix} (not a tier) and their origins; their first-finish tokens are Easy Dropper
 * 2 a week and 1 a day, the Dropper 3 and 2; a mix is read in any case, and one that doesn't fit the
 * five shafts switches that dropper off alone with one WARN; the planner is registered; and the
 * new-courses line calls both "Dropper".
 */
class DropperSlotsConfigTest {

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = DropperSlotsConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return GamesConfig.tree(c.getConfigurationSection("games.fresh"));
            }
        }
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

    @Test
    void theDroppersShipOffWithTheirMixesOriginsAndTokens() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(shipped(), warns);
        assertEquals(List.of(), warns, "the shipped block has no WARN");
        DailySettings.SlotConfig easy = s.slot("fresh_dropper_easy");
        DailySettings.SlotConfig drop = s.slot("fresh_dropper");
        assertFalse(easy.enabled() || drop.enabled(), "both droppers ship off");
        assertEquals("EEE", easy.tierOrMix(), "Easy Dropper: three easy levels");
        assertEquals("EEMMH", drop.tierOrMix(), "the Dropper: five, easy to hard");
        assertArrayEquals(new int[]{5376, 160, 4096}, easy.origin(), "Easy Dropper's area");
        assertArrayEquals(new int[]{5376, 160, 4160}, drop.origin(), "the Dropper's, 48 further along z");
        assertEquals(2, s.dailyClear("fresh_dropper_easy", 7), "Easy Dropper's first finish in a week pays 2");
        assertEquals(1, s.dailyClear("fresh_dropper_easy", 1), "and 1 a day");
        assertEquals(3, s.dailyClear("fresh_dropper", 7), "the Dropper's 3");
        assertEquals(2, s.dailyClear("fresh_dropper", 1), "and 2 a day");
        assertTrue(DailySettings.KEYS.contains("slots.fresh_dropper.mix"), "a dropper's difficulty is a mix");
        assertFalse(DailySettings.KEYS.contains("slots.fresh_dropper.tier"), "not a tier");
        assertTrue(DailySettings.KEYS.contains("rewards.clear_weekly.fresh_dropper_easy"), "its weekly tokens are a key");
        assertTrue(DailySettings.KEYS.contains("classics.slots.fresh_classic_dropper.origin"),
                "Classic Dropper's place is a key");
        assertTrue(s.archive().classics().stream().anyMatch(c -> c.id().equals("fresh_classic_dropper")
                && java.util.Arrays.equals(c.origin(), new int[]{5376, 160, 4224})), "at the spec's origin");
    }

    @Test
    void aMixIsReadInAnyCaseAndOneThatDoesNotFitSwitchesThatDropperOffAlone() throws Exception {
        Map<String, Object> fresh = shipped();
        put(fresh, "enabled", true);
        put(fresh, "slots.fresh_dropper.enabled", true);
        put(fresh, "slots.fresh_dropper.mix", "eemhh");
        put(fresh, "slots.fresh_dropper_easy.enabled", true);
        put(fresh, "slots.fresh_dropper_easy.mix", "EEEEEE");
        List<String> warns = new ArrayList<>();
        DailySettings s = parse(fresh, warns);
        assertNotNull(s, "a bad dropper never closes Fresh Courses");
        assertEquals("EEMHH", s.slot("fresh_dropper").tierOrMix(), "a mix is upper-cased");
        assertTrue(s.slot("fresh_dropper").enabled(), "and the Dropper is on");
        assertFalse(s.slot("fresh_dropper_easy").enabled(), "six levels don't fit: Easy Dropper is off");
        assertEquals(1, warns.size(), "with one WARN: " + warns);
        assertTrue(warns.get(0).startsWith("games.fresh.slots.fresh_dropper_easy"), "naming it: " + warns);
        assertTrue(warns.get(0).contains("dropper mix"), "and saying what a dropper mix is: " + warns);
        assertTrue(s.slot("fresh_parkour_easy").enabled(), "the rest keep working");
    }

    @Test
    void thePlannerIsRegisteredForTheDropperGenerator() {
        Map<String, Planner> planners = DailyCourses.planners();
        assertTrue(planners.get(Slots.DROPPER) instanceof DropperPlanner, "dropper slots are made by the DropperPlanner");
        for (Slots.Def d : Slots.ALL) {
            assertNotNull(planners.get(d.generator()), d.id() + " has its planner");
        }
        assertEquals(Slots.DROPPER, new DropperPlanner().id(), "its id is the slots' generator");
    }

    @Test
    void theNewCoursesLineCallsBothDroppersDropper() {
        assertEquals("Dropper", NewCoursesNudge.shortName(Slots.EASY_DROPPER.id()), "Easy Dropper");
        assertEquals("Dropper", NewCoursesNudge.shortName(Slots.FRESH_DROPPER.id()), "the Dropper");
        assertEquals("Golf", NewCoursesNudge.shortName(Slots.TINY_GOLF.id()), "like both golf courses are Golf");
    }
}
