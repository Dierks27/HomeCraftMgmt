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
                "trials", "golf"), ids);
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
        clamps.put("max_payout", 0);
        clamps.put("skill_daily_cap", -3);
        clamps.put("featured_bonus", 1_000);
        clamps.put("break.raise_delay_days", 0);
        clamps.put("break.pause_days", List.of(0, 7, 30));
        clamps.put("snake.tick_java", 1);
        clamps.put("golf.max_over_par", 99);
        clamps.put("trials.fall_depth", 0);
        clamps.put("higher_lower.rtp", 99);
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
