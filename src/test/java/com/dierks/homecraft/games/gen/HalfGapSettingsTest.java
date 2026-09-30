package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.KeepArea;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code half_gap} and {@code keep.plot_gap} (LAYOUT-SPEC §3.3): optional keys config.yml doesn't
 * ship, read as each slot's, each Classic's and the keep area's gap. Left out, the default; a whole
 * number in range is used (rounded down to the 16-block grid with a WARN, as an origin is); anything
 * else is one WARN and that course (or keeping) is off, since no other gap is guessed for where the
 * spare half stands. Neither is ever reported as a typo.
 */
class HalfGapSettingsTest {

    private static final String EASY = "fresh_parkour_easy";
    /** Far from every shipped spot, old or new, so no other area is ever in the way. */
    private static final List<Integer> FAR = List.of(16_384, 160, 16_384);

    private static Map<String, Object> bundled(String section) throws Exception {
        try (InputStream in = HalfGapSettingsTest.class.getResourceAsStream("/config.yml");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration c = new YamlConfiguration();
            c.load(r);
            return GamesConfig.tree(c.getConfigurationSection(section));
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

    /** {@code games.fresh} as shipped with {@code keysAndValues} set over it. */
    private static DailySettings with(List<String> warns, Object... keysAndValues) throws Exception {
        Map<String, Object> fresh = bundled("games.fresh");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            put(fresh, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return DailySettings.parse(new GamesConfig.Node("games.fresh", fresh, warns::add), DailySettings.defaults());
    }

    @Test
    void leftOutEveryGapIsTheDefault() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with(warns);
        assertEquals(List.of(), warns, "the shipped block has no WARN");
        for (DailySettings.SlotConfig c : s.slots()) {
            assertEquals(Slots.HALF_GAP, c.halfGap(), c.id() + " has the default gap");
        }
        for (DailySettings.SlotConfig c : s.archive().classics()) {
            assertEquals(Slots.HALF_GAP, c.halfGap(), c.id() + " has the default gap");
        }
        assertEquals(KeepArea.DEFAULT_GAP, s.archive().keep().gap(), "and the keep area its default");
    }

    @Test
    void aWholeNumberInRangeIsTheSlotsGap() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with(warns, "slots." + EASY + ".origin", FAR, "slots." + EASY + ".half_gap", 576);
        assertEquals(List.of(), warns, "no WARN");
        assertEquals(576, s.slot(EASY).halfGap(), "576 between its halves");
        assertTrue(s.slot(EASY).enabled(), "and it is on");
        for (int ok : new int[]{Slots.MIN_HALF_GAP, 32, 1024, Slots.MAX_HALF_GAP}) {
            List<String> w = new ArrayList<>();
            assertEquals(ok, with(w, "slots." + EASY + ".origin", FAR, "slots." + EASY + ".half_gap", ok)
                    .slot(EASY).halfGap(), ok + " is taken as it is");
            assertEquals(List.of(), w, ok + ": no WARN");
        }
    }

    @Test
    void aGapOffTheGridIsRoundedDownWithOneWarn() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with(warns, "slots." + EASY + ".origin", FAR, "slots." + EASY + ".half_gap", 600);
        assertEquals(592, s.slot(EASY).halfGap(), "600 rounds down to 592");
        assertTrue(s.slot(EASY).enabled(), "the course stays on");
        assertEquals(List.of("games.fresh.slots." + EASY + ".half_gap 600 is not a multiple of 16 - using 592"), warns,
                "one WARN naming the key");
    }

    @Test
    void anythingElseIsOneWarnAndThatCourseIsOff() throws Exception {
        for (Object bad : new Object[]{16, 5000, -32, "wide", 40.5, true, List.of(576)}) {
            List<String> warns = new ArrayList<>();
            DailySettings s = with(warns, "slots." + EASY + ".origin", FAR, "slots." + EASY + ".half_gap", bad);
            assertFalse(s.slot(EASY).enabled(), bad + ": the course is off (no other gap is guessed)");
            assertEquals(1, warns.size(), bad + ": one WARN: " + warns);
            assertTrue(warns.get(0).startsWith("games.fresh.slots." + EASY + ".half_gap should be a whole number of"
                    + " blocks, 32-4096") && warns.get(0).endsWith("that course is off"), bad + ": " + warns);
            assertTrue(s.slot("fresh_parkour").enabled(), bad + ": the other courses are untouched");
        }
    }

    @Test
    void aClassicsSlotReadsItsGapFromItsSectionAndABareOriginListMeansTheDefault() throws Exception {
        String classic = "fresh_classic_parkour";
        List<String> warns = new ArrayList<>();
        DailySettings s = with(warns, "classics.slots." + classic, Map.of("origin", FAR, "half_gap", 576));
        assertEquals(List.of(), warns, "no WARN");
        assertEquals(576, s.archive().classic(classic).halfGap(), "the section's gap");
        assertTrue(s.archive().classic(classic).enabled(), "on");

        warns.clear();
        s = with(warns, "classics.slots." + classic, FAR);
        assertEquals(List.of(), warns, "a bare origin list: no WARN");
        assertEquals(Slots.HALF_GAP, s.archive().classic(classic).halfGap(), "and the default gap");

        warns.clear();
        s = with(warns, "classics.slots." + classic, Map.of("origin", FAR, "half_gap", "wide"));
        assertFalse(s.archive().classic(classic).enabled(), "a bad gap switches that Classics slot off");
        assertEquals(1, warns.size(), "with one WARN: " + warns);
        assertTrue(warns.get(0).startsWith("games.fresh.classics.slots." + classic + ".half_gap"), "naming it: " + warns);
    }

    @Test
    void theGapIsWhereHalfBStandsSoAnotherCourseOnItIsSwitchedOff() throws Exception {
        List<String> warns = new ArrayList<>();
        List<Integer> onB = List.of(FAR.get(0) + 64 + 576, 160, FAR.get(2));
        DailySettings s = with(warns, "slots." + EASY + ".origin", FAR, "slots." + EASY + ".half_gap", 576,
                "slots.fresh_parkour.origin", onB);
        assertTrue(s.slot(EASY).enabled(), "the first keeps its place");
        assertFalse(s.slot("fresh_parkour").enabled(), "the one on its half B is off");
        assertTrue(warns.stream().anyMatch(w -> w.contains("fresh_parkour is on top of " + EASY)), "said: " + warns);
    }

    @Test
    void thePlotGapIsReadLikeAnyGapAndABadOneTurnsKeepingOff() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with(warns, "keep.area", List.of(-40_000, 128, -40_000), "keep.plot_gap", 576);
        assertEquals(List.of(), warns, "no WARN");
        assertEquals(576, s.archive().keep().gap(), "576 between plots");
        assertNull(s.archive().keepProblem(), "keeping is on");
        assertEquals(new KeepArea(-40_000, 128, -40_000, 24, 576), s.archive().keep(), "the area as written");

        warns.clear();
        s = with(warns, "keep.area", List.of(-40_000, 128, -40_000), "keep.plot_gap", 20);
        assertEquals(16, s.archive().keep().gap(), "20 rounds down to 16");
        assertEquals(List.of("games.fresh.keep.plot_gap 20 is not a multiple of 16 - using 16"), warns, "one WARN");
        assertNull(s.archive().keepProblem(), "keeping stays on");

        for (Object bad : new Object[]{-16, 5000, "wide", 1.5}) {
            warns.clear();
            s = with(warns, "keep.area", List.of(-40_000, 128, -40_000), "keep.plot_gap", bad);
            assertNotNull(s.archive().keepProblem(), bad + ": keeping is off until it is fixed");
            assertEquals(1, warns.size(), bad + ": one WARN: " + warns);
            assertTrue(warns.get(0).startsWith("games.fresh.keep.plot_gap should be a whole number of blocks, 0-4096")
                    && warns.get(0).endsWith("keeping a course is off until it is fixed"), bad + ": " + warns);
        }
    }

    @Test
    void theGapsAreKnownKeysThatConfigYmlNeverShips() throws Exception {
        assertTrue(DailySettings.OPTIONAL_KEYS.contains("slots." + EASY + ".half_gap"), "every slot's");
        assertTrue(DailySettings.OPTIONAL_KEYS.contains("classics.slots.fresh_classic_golf.half_gap"), "every Classic's");
        assertTrue(DailySettings.OPTIONAL_KEYS.contains("keep.plot_gap"), "the keep area's");
        assertEquals(Slots.ALL.size() + Slots.CLASSICS.size() + 1, DailySettings.OPTIONAL_KEYS.size(), "nothing else");
        for (String k : DailySettings.OPTIONAL_KEYS) {
            assertFalse(DailySettings.KEYS.contains(k), k + " is not shipped, so the backfill never adds it");
            assertTrue(GamesConfig.KNOWN.contains("fresh." + k), k + " is a known key");
            assertFalse(GamesConfig.KEYS.contains("fresh." + k), k + " is not in config.yml's list");
        }

        Map<String, Object> games = bundled("games");
        put(games, "fresh.slots." + EASY + ".origin", FAR);
        put(games, "fresh.slots." + EASY + ".half_gap", 576);
        put(games, "fresh.keep.plot_gap", 0);
        put(games, "fresh.classics.slots.fresh_classic_golf.half_gap", 32);
        List<String> warns = new ArrayList<>();
        GamesConfig.parse(games, warns::add, null);
        assertEquals(List.of(), warns, "written by an owner, none of them is a typo");

        put(games, "fresh.slots." + EASY + ".half_gaps", 576);
        warns.clear();
        GamesConfig.parse(games, warns::add, null);
        assertEquals(List.of("games.fresh.slots." + EASY + ".half_gaps is not a games setting - ignored (a typo?)"),
                warns, "a misspelt one still is");
    }
}
