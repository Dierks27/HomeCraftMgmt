package com.dierks.homecraft.config;

import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code games:} section (spec §13, R3.1). Iterates {@link GameCatalog#SPECS}, so it covers
 * every game — including ones built after it was written — and no game owner edits it.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>the bundled block parses to exactly the common defaults and each spec's defaults, with
 *       zero WARNs, and ships exactly {@link GamesConfig#KEYS} in order;</li>
 *   <li>a left-out key or section reads as shipped;</li>
 *   <li>an out-of-range number is clamped with exactly one WARN naming its full key, and the code
 *       limits win (the cooldown floor, the daily-token ceiling, the RTP band);</li>
 *   <li>junk fails closed: a {@code games} that isn't a section turns the games OFF, junk in a
 *       common key turns them OFF, junk in a game's block closes that game only — never an
 *       exception;</li>
 *   <li>a bare switch where a game's section belongs reads as its {@code enabled};</li>
 *   <li>the restart hold ships the owner's schedule (04:00 and 16:00, 5 minutes); a restart time
 *       that isn't one is dropped with one WARN and the rest still hold, {@code []} is off;</li>
 *   <li>the shipped skill cap stays within a quarter of an active day's tokens (DESIGN §3.9).</li>
 * </ul>
 */
class GamesConfigTest {

    /** DESIGN §3.9: tokens earned on an active day. */
    private static final int ACTIVE_DAY_TOKENS = 28;

    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = GamesConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError("test fixture is not valid YAML", e);
        }
        return c;
    }

    /** The bundled {@code games} section as plain nested maps (a fresh copy each time). */
    private static Map<String, Object> shipped() throws Exception {
        return GamesConfig.tree(bundled().getConfigurationSection("games"));
    }

    /** A deep copy of plain nested maps, so one test's edit can't leak into the next. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), e.getValue() instanceof Map<?, ?> m ? copy((Map<String, Object>) m) : e.getValue());
        }
        return out;
    }

    /** Set (or with {@code null} remove) a dotted leaf in plain nested maps. */
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
        if (value == null) {
            m.remove(parts[parts.length - 1]);
        } else {
            m.put(parts[parts.length - 1], value);
        }
    }

    private static long warnsNaming(List<String> warns, String key) {
        return warns.stream().filter(w -> w.startsWith(key + " ") || w.startsWith(key + ".")).count();
    }

    /** The game's shipped settings with {@code enabled: false}, read through its own parser. */
    private static <S> S switchedOff(GameSpec<S> spec) {
        return spec.parse().apply(new GamesConfig.Node("games." + spec.id(), Map.of("enabled", false), w -> { }),
                spec.defaults());
    }

    private static void assertAllShipped(GamesConfig.Parsed parsed, String why) {
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            assertEquals(spec.defaults(), parsed.settings(spec), spec.id() + " should read as shipped (" + why + ")");
            assertTrue(parsed.readable(spec.id()), spec.id() + " should be readable (" + why + ")");
        }
    }

    // ---- the shipped block ------------------------------------------------------------------

    @Test
    void theShippedBlockIsExactlyEachSpecsDefaults() throws Exception {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), warns::add, infos::add);

        assertEquals(List.of(), warns, "the shipped games block must load without a single WARN");
        assertEquals(GamesConfig.Common.defaults(), parsed.common(), "the common keys read as shipped");
        assertFalse(parsed.enabled(), "the games ship OFF");
        assertEquals(Set.of(), parsed.unreadable(), "every shipped game block is readable");
        assertAllShipped(parsed, "the bundled block");
    }

    @Test
    void theShippedLeavesAreExactlyTheKeysInOrder() throws Exception {
        ConfigurationSection games = bundled().getConfigurationSection("games");
        assertNotNull(games, "config.yml ships a games: section");
        List<String> leaves = new ArrayList<>();
        for (String key : games.getKeys(true)) {
            if (!games.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        assertEquals(GamesConfig.KEYS, leaves, "config.yml's games: block must ship exactly the keys the parser reads");
    }

    @Test
    void theKeysAreTheCommonKeysThenEverySpecsOwn() {
        List<String> expected = new ArrayList<>(GamesConfig.COMMON);
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            assertFalse(spec.keys().isEmpty(), spec.id() + " ships at least its enabled key");
            assertEquals("enabled", spec.keys().get(0), spec.id() + ": enabled comes first");
            for (String k : spec.keys()) {
                expected.add(spec.id() + "." + k);
            }
        }
        assertEquals(expected, GamesConfig.KEYS);
        assertEquals(new HashSet<>(GamesConfig.KEYS).size(), GamesConfig.KEYS.size(), "no key twice");
    }

    @Test
    void theCatalogListsEveryGameOnceInDisplayOrder() {
        List<String> ids = GameCatalog.SPECS.stream().map(GameSpec::id).toList();
        assertEquals(List.of("ore_slots", "twenty_one", "wheel", "higher_lower", "coin_flip", "creeper_sweeper",
                "ore_merge", "snake", "mini_match", "simon_says", "whack_a_zombie", "connect_four", "tic_tac_toe",
                "trials", "golf", "daily"), ids);
        assertEquals(5, GameCatalog.SPECS.stream().filter(s -> s.kind() == GameKind.CHANCE).count(),
                "five games of chance");
        for (String reserved : GameCatalog.RESERVED) {
            assertTrue(GameCatalog.taken(reserved), reserved + " is reserved for /hcm play");
        }
        assertTrue(GameCatalog.taken("ORE_SLOTS"), "a game id can't be a course id");
        assertFalse(GameCatalog.taken("river_run"));
    }

    // ---- left out ---------------------------------------------------------------------------

    @Test
    void aMissingSectionReadsAsShipped() throws Exception {
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed none = GamesConfig.parse(yaml("store:\n  name: Crate\n"), warns::add);
        assertSame(GamesConfig.Parsed.DEFAULTS, none, "no games: section at all is the shipped section");
        assertEquals(List.of(), warns);

        for (GameSpec<?> spec : GameCatalog.SPECS) {
            Map<String, Object> games = shipped();
            put(games, spec.id(), null);
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            assertEquals(List.of(), warns, "games." + spec.id() + " left out");
            assertAllShipped(parsed, "games." + spec.id() + " left out");
        }
        Map<String, Object> games = shipped();
        put(games, "break", null);
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        assertEquals(List.of(), warns);
        assertEquals(GamesConfig.Common.defaults(), parsed.common(), "games.break left out");
    }

    @Test
    void everyLeftOutKeyReadsAsShipped() throws Exception {
        Map<String, Object> base = shipped();
        for (String key : GamesConfig.KEYS) {
            Map<String, Object> games = copy(base);
            put(games, key, null);
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            assertEquals(List.of(), warns, "games." + key + " left out");
            assertEquals(GamesConfig.Common.defaults(), parsed.common(), "games." + key + " left out");
            assertAllShipped(parsed, "games." + key + " left out");
        }
    }

    @Test
    void settingsFallBackToTheSpecsDefaults() {
        GamesConfig.Parsed empty = new GamesConfig.Parsed(null, null, null);
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            assertEquals(spec.defaults(), empty.settings(spec), spec.id());
            assertTrue(empty.readable(spec.id()));
        }
        assertFalse(empty.enabled(), "a Parsed with no common keys is off");
    }

    // ---- clamps -----------------------------------------------------------------------------

    @Test
    void aClampedValueGivesExactlyOneWarnNamingItsFullKey() throws Exception {
        Map<String, Object> clamps = new LinkedHashMap<>();
        clamps.put("click_cooldown_ms", 100);
        clamps.put("chance_daily_tokens", 20_000);
        // Clamped from above: a max_payout of 0 (clamped up to 1) caps chance games' payouts so hard
        // that a stake drops out of the RTP band, and that drop has its own WARN (spec §5.1).
        clamps.put("max_payout", 2_000_000);
        clamps.put("skill_daily_cap", -3);
        clamps.put("featured_bonus", 1_000);
        clamps.put("restart_times", List.of("04:00", "noon"));
        clamps.put("restart_hold_minutes", 0);
        clamps.put("break.raise_delay_days", 0);
        clamps.put("break.pause_days", List.of(0, 7, 30));
        clamps.put("snake.tick_java", 1);
        clamps.put("golf.max_over_par", 99);
        clamps.put("trials.fall_depth", 0);
        clamps.put("higher_lower.rtp", 99);
        clamps.put("daily.retry_minutes", 0);
        clamps.put("daily.budget.blocks_per_tick", 0);
        clamps.put("daily.stars.gold.hard", 0.5);
        clamps.put("daily.slots.tiny_golf.daily_clear", 500);
        for (Map.Entry<String, Object> c : clamps.entrySet()) {
            Map<String, Object> games = shipped();
            put(games, "enabled", true);
            put(games, c.getKey(), c.getValue());
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            String key = "games." + c.getKey();
            assertEquals(1, warns.size(), key + ": " + warns);
            assertTrue(warns.get(0).startsWith(key + " "), "the WARN names " + key + ": " + warns);
            assertTrue(parsed.enabled(), key + ": a clamp is not junk, the games stay on");
            assertEquals(Set.of(), parsed.unreadable(), key + ": a clamp closes nothing");
        }
    }

    @Test
    void theCodeLimitsWin() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "click_cooldown_ms", 1);
        put(games, "chance_daily_tokens", 1_000_000);
        put(games, "break.raise_delay_days", -5);
        GamesConfig.Parsed parsed = GamesConfig.parse(games, w -> { }, null);
        assertEquals(250, parsed.common().clickCooldownMs(), "the cooldown floor is locked in code");
        assertEquals(GamesConfig.MAX_CHANCE_DAILY_TOKENS, parsed.common().chanceDailyTokens());
        assertEquals(1, parsed.common().breakRaiseDelayDays(), "a raise always waits at least a day");

        List<String> warns = new ArrayList<>();
        GamesConfig.Node n = new GamesConfig.Node("games.wheel", Map.of("rtp", 80), warns::add);
        assertEquals(85.0, n.rtp("rtp", 90), "the RTP band is locked in code");
        assertEquals(1, warnsNaming(warns, "games.wheel.rtp"), warns.toString());
    }

    @Test
    void theShippedSkillCapIsAtMostAQuarterOfAnActiveDay() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        assertTrue(parsed.common().skillDailyCap() * 4 <= ACTIVE_DAY_TOKENS,
                "skill games may add at most 25% of an active day's " + ACTIVE_DAY_TOKENS + " tokens, not "
                        + parsed.common().skillDailyCap());
    }

    @Test
    void aGameOfChanceIsNeverTheFeaturedGame() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "featured", "ore_slots");
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        assertEquals("auto", parsed.common().featured());
        assertEquals(1, warnsNaming(warns, "games.featured"), warns.toString());

        put(games, "featured", "River_Run");
        assertEquals("river_run", GamesConfig.parse(games, w -> { }, null).common().featured(),
                "a course id is kept (lower-cased)");
    }

    // ---- the restart hold ---------------------------------------------------------------------

    @Test
    void theShippedRestartHoldIsTheOwnersSchedule() throws Exception {
        GamesConfig.Common common = GamesConfig.parse(bundled(), w -> { }).common();
        assertEquals(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), common.restartTimes(),
                "the host restarts at 04:00 and 16:00, so that is what ships");
        assertEquals(5, common.restartHoldMinutes(), "held for the last five minutes before each");
    }

    @Test
    void aRestartTimeThatIsNotOneIsDroppedWithOneWarnAndTheRestStillHold() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "enabled", true);
        put(games, "restart_times", java.util.Arrays.asList("16:00", "noon", 960, "25:00", "4:00", "04:00", null));
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        assertEquals(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), parsed.common().restartTimes(),
                "the good times, sorted; \"4:00\" and \"04:00\" are the same restart, kept once");
        assertEquals(4, warns.size(), "one WARN per dropped entry, none for the duplicate: " + warns);
        assertEquals(4, warnsNaming(warns, "games.restart_times"), "each names the key: " + warns);
        assertTrue(warns.stream().anyMatch(w -> w.contains("960") && w.contains("quotes")),
                "a number (YAML's reading of an unquoted 16:00) says to write the time in quotes: " + warns);
        assertTrue(parsed.enabled(), "a dropped time is not junk: the games stay on");
        assertEquals(Set.of(), parsed.unreadable(), "and nothing closes");
    }

    @Test
    void anEmptyListTurnsTheHoldOffAndASingleTimeIsAListOfOne() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "restart_times", List.of());
        List<String> warns = new ArrayList<>();
        assertEquals(List.of(), GamesConfig.parse(games, warns::add, null).common().restartTimes(),
                "restart_times: [] means no restart hold");
        put(games, "restart_times", "16:30");
        assertEquals(List.of(LocalTime.of(16, 30)), GamesConfig.parse(games, warns::add, null).common().restartTimes(),
                "a single time is a list of one");
        assertEquals(List.of(), warns, "neither is worth a WARN");
    }

    @Test
    void theHoldIsKeptBetweenOneMinuteAndAnHour() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "restart_hold_minutes", 90);
        List<String> warns = new ArrayList<>();
        assertEquals(60, GamesConfig.parse(games, warns::add, null).common().restartHoldMinutes(), "at most an hour");
        put(games, "restart_hold_minutes", 0);
        assertEquals(1, GamesConfig.parse(games, warns::add, null).common().restartHoldMinutes(),
                "at least a minute: a hold of 0 would never hold");
        assertEquals(2, warnsNaming(warns, "games.restart_hold_minutes"), warns.toString());
    }

    @Test
    void anUnquotedTimeInTheFileIsReportedNotGuessed() {
        // YAML 1.1 reads an unquoted 16:00 as the base-60 number 960; 04:00 (leading zero) stays text.
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(yaml("games:\n  restart_times: [04:00, 16:00]\n"), warns::add);
        assertEquals(List.of(LocalTime.of(4, 0)), parsed.common().restartTimes(),
                "04:00 reads as a time; 960 is never guessed back into one");
        assertEquals(1, warns.size(), "one WARN, for the unquoted 16:00: " + warns);
        assertEquals("games.restart_times 960 is not a time - dropped (YAML reads an unquoted 16:00 as 960: "
                + "write it in quotes, \"16:00\")", warns.get(0), "it names the key and says how to write the time");
        assertEquals("write each time in quotes, like \"16:00\"", GamesConfig.unquoted(7),
                "a number that can't be a time of day gets the plain advice");
    }

    @Test
    void midnightWrittenAs2400PointsTo0000QuotedOrNot() {
        // Unquoted, YAML reads 24:00 as 1440; quoted, it is text but not a 24-hour time.
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed unquoted = GamesConfig.parse(yaml("games:\n  restart_times: [24:00]\n"), warns::add);
        assertEquals(List.of(), unquoted.common().restartTimes(), "1440 is never guessed back into midnight");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        assertTrue(warns.get(0).contains("\"00:00\""),
                "the owner is told midnight is \"00:00\", not only to add quotes that won't help: " + warns);

        warns.clear();
        GamesConfig.Parsed quoted = GamesConfig.parse(yaml("games:\n  restart_times: [\"24:00\"]\n"), warns::add);
        assertEquals(List.of(), quoted.common().restartTimes(), "\"24:00\" is not a time of day either");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        assertTrue(warns.get(0).startsWith("games.restart_times \"24:00\""), "it names the key and the entry: " + warns);
        assertTrue(warns.get(0).contains("\"00:00\""), "and says midnight is \"00:00\": " + warns);

        warns.clear();
        assertEquals(List.of(LocalTime.MIDNIGHT),
                GamesConfig.parse(yaml("games:\n  restart_times: [\"00:00\"]\n"), warns::add).common().restartTimes(),
                "which is read as midnight");
        assertEquals(List.of(), warns, "without a WARN");
    }

    // ---- junk -------------------------------------------------------------------------------

    @Test
    void aGamesValueThatIsNotASectionTurnsTheGamesOff() {
        for (String junk : List.of("games: 5\n", "games: [1, 2]\n", "games: maybe\n")) {
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(yaml(junk), warns::add);
            assertSame(GamesConfig.Parsed.OFF, parsed, junk);
            assertEquals(1, warns.size(), junk + " -> " + warns);
            assertTrue(warns.get(0).startsWith("games "), warns.toString());
        }
        List<String> warns = new ArrayList<>();
        assertTrue(GamesConfig.parse(yaml("games: true\n"), warns::add).enabled(), "a bare games: true is on");
        assertEquals(1, warns.size(), "and says to write games.enabled: " + warns);
    }

    @Test
    void junkInACommonKeyTurnsTheGamesOff() throws Exception {
        Map<String, Object> junk = new LinkedHashMap<>();
        junk.put("enabled", "maybe");
        junk.put("click_cooldown_ms", "fast");
        junk.put("worlds", Map.of("a", 1));
        junk.put("restart_times", Map.of("at", "04:00"));
        junk.put("restart_hold_minutes", "soon");
        junk.put("break.daily_choices", List.of("ten"));
        junk.put("break", 7);
        for (Map.Entry<String, Object> j : junk.entrySet()) {
            Map<String, Object> games = shipped();
            put(games, "enabled", true);
            put(games, j.getKey(), j.getValue());
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            String key = "games." + j.getKey();
            assertFalse(parsed.enabled(), key + " = " + j.getValue() + " turns the games off");
            assertEquals(1, warns.size(), key + ": " + warns);
            assertTrue(warns.get(0).startsWith(key + " ") || warns.get(0).startsWith(key + "."), warns.toString());
            assertTrue(warns.get(0).contains("off until it is fixed"), warns.toString());
        }
    }

    @Test
    void junkInAGamesBlockClosesThatGameOnly() throws Exception {
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            for (Object junk : List.of(List.of(1), 12, "maybe")) {
                Map<String, Object> games = shipped();
                put(games, "enabled", true);
                put(games, spec.id(), junk);
                List<String> warns = new ArrayList<>();
                GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
                String key = "games." + spec.id();
                assertTrue(parsed.enabled(), key + ": the other games stay on");
                assertFalse(parsed.readable(spec.id()), key + " = " + junk + " closes " + spec.id());
                assertEquals(Set.of(spec.id()), parsed.unreadable(), key + ": only that game");
                assertEquals(1, warns.size(), key + ": " + warns);
                assertTrue(warns.get(0).startsWith(key + " "), warns.toString());
            }

            Map<String, Object> games = shipped();
            put(games, spec.id() + ".enabled", "maybe");
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            assertFalse(parsed.readable(spec.id()), spec.id() + ".enabled: maybe fails closed");
            assertEquals(1, warnsNaming(warns, "games." + spec.id() + ".enabled"), warns.toString());
            assertEquals(1, warns.size(), warns.toString());
            assertEquals(switchedOff(spec), parsed.settings(spec), spec.id() + " reads switched off");
        }
    }

    @Test
    void aBareSwitchWhereAGamesSectionBelongsIsItsEnabled() throws Exception {
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            Map<String, Object> games = shipped();
            put(games, spec.id(), false);
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            assertTrue(parsed.readable(spec.id()), spec.id() + ": false is a readable switch");
            assertEquals(switchedOff(spec), parsed.settings(spec), spec.id() + ": false");
            assertEquals(1, warns.size(), "one WARN to write " + spec.id() + ".enabled instead: " + warns);
        }
    }

    @Test
    void anUnknownKeyIsReportedAndIgnored() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "ore_slots.reels.emerald", 3);
        put(games, "snake.speed", 2);
        put(games, "colour", "red");
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        assertEquals(3, warns.size(), warns.toString());
        assertEquals(1, warnsNaming(warns, "games.ore_slots.reels.emerald"), warns.toString());
        assertEquals(1, warnsNaming(warns, "games.snake.speed"), warns.toString());
        assertEquals(1, warnsNaming(warns, "games.colour"), warns.toString());
        assertAllShipped(parsed, "unknown keys are ignored");
    }

    // ---- the reader -------------------------------------------------------------------------

    @Test
    void theNodeReadsListsLaddersAndMaps() {
        List<String> warns = new ArrayList<>();
        GamesConfig.Node n = new GamesConfig.Node("games.x", Map.of(
                "three", List.of(1, 2, 3), "short", List.of(1, 2), "one", 5, "falling", List.of(30, 24, 20),
                "flat", List.of(5, 5, 6), "map", Map.of("a", 99), "names", "games"), warns::add);

        assertEquals(List.of(1, 2, 3), n.intList("three", List.of(9), 1, 10, 3));
        assertEquals(List.of(5), n.intList("one", List.of(9), 1, 10), "a single number is a list of one");
        assertEquals(List.of(), warns);
        assertFalse(n.invalid());

        assertEquals(List.of(30, 24, 20), n.ladder("falling", List.of(9, 8, 7), 100, true));
        assertEquals(List.of(), warns, "falling is right when lower is better");

        assertEquals(Map.of("a", 10, "b", 2), n.wholeMap("map", Map.of("a", 1, "b", 2), 0, 10));
        assertEquals(1, warnsNaming(warns, "games.x.map.a"), "a map entry clamps by its own key: " + warns);
        assertEquals(List.of("games"), n.stringList("names", List.of()));
        assertFalse(n.invalid(), "clamps are not junk");

        warns.clear();
        assertEquals(List.of(9), n.intList("short", List.of(9), 1, 10, 3), "the wrong length is junk");
        assertTrue(n.invalid());
        assertEquals(1, warnsNaming(warns, "games.x.short"), warns.toString());
        assertTrue(warns.get(0).endsWith("games.x is off until it is fixed"), warns.toString());

        warns.clear();
        assertEquals(List.of(1, 2, 3), n.ladder("flat", List.of(1, 2, 3), 100, false), "not strictly rising");
        assertEquals(1, warnsNaming(warns, "games.x.flat"), warns.toString());
    }
}
