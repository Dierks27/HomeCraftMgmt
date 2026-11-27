package com.dierks.homecraft.config;

import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.TokenBalance;
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
 *   <li>the shipped caps hold every whole-paid reward and bound a day (the 2 Oct token balance): the
 *       skill cap fits the two biggest Fresh first finishes and the day's repeatables, and stays at
 *       most 75; Race Night's 1st prize is paid in full; the Cup's top-up is twice its entry.</li>
 * </ul>
 */
class GamesConfigTest {

    /** The most the shipped skill cap may be: about an evening and a quarter of first-time play (BALANCE-SPEC §6). */
    private static final int MAX_SKILL_CAP = 75;

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
        return spec.parse().apply(new GamesConfig.Node("games." + GamesConfig.block(spec.id()),
                        Map.of("enabled", false), w -> { }),
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
                expected.add(GamesConfig.block(spec.id()) + "." + k);
            }
        }
        assertEquals(expected, GamesConfig.KEYS);
        assertEquals(new HashSet<>(GamesConfig.KEYS).size(), GamesConfig.KEYS.size(), "no key twice");
    }

    @Test
    void theCatalogListsEveryGameOnceInDisplayOrder() {
        List<String> ids = GameCatalog.SPECS.stream().map(GameSpec::id).toList();
        assertEquals(List.of("ore_slots", "twenty_one", "wheel", "higher_lower", "coin_flip", "nether_slots",
                "motherlode", "farm_slots", "deep_mine", "mob_slots", "mob_races", "creeper_sweeper",
                "ore_merge", "snake", "mini_match", "simon_says", "whack_a_zombie", "connect_four", "tic_tac_toe",
                "trials", "golf", "cup", "fresh_courses", "race_night", "falling_floors", "clubhouse",
                "hub", "floor_is_lava", "cpu", "kid_races", "sportsbook", "holdem", "bingo", "kid_bingo",
                "video_poker", "creeper_fuse"), ids);
        assertEquals(16, GameCatalog.SPECS.stream().filter(s -> s.kind() == GameKind.CHANCE).count(),
                "sixteen games of chance: the first five, the three more slot machines after them, Deep Mine and"
                        + " Mob Slots, then Mob Races; the Sports Board; then 0.57.0's Texas Hold'em, Bingo Night, Video"
                        + " Poker and Creeper Fuse, last");
        for (String reserved : GameCatalog.RESERVED) {
            assertTrue(GameCatalog.taken(reserved), reserved + " is reserved for /hcm play");
        }
        assertTrue(GameCatalog.taken("ORE_SLOTS"), "a game id can't be a course id");
        assertFalse(GameCatalog.taken("river_run"));
    }

    /**
     * Plan 11: the Arcade Room's free kid race ships on, 2 tokens a win and 10 a day, last in the {@code games:} block
     * (where the backfill appends it on an upgraded server); a value out of its range is clamped with one WARN.
     */
    @Test
    void theKidRaceShipsFreeWithASmallCappedPrizeAtTheEndOfTheBlock() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        com.dierks.homecraft.games.kidrace.KidRacesSettings k =
                parsed.settings(com.dierks.homecraft.games.kidrace.KidRaces.SPEC);
        assertEquals(com.dierks.homecraft.games.kidrace.KidRacesSettings.defaults(), k);
        assertTrue(k.enabled());
        assertEquals(2, k.prize());
        assertEquals(10, k.dailyCap());
        assertTrue(k.dailyCap() <= parsed.common().skillDailyCap(), "inside the server's skill cap");
        assertFalse(com.dierks.homecraft.games.kidrace.KidRaces.SPEC.kind().chance(), "never a game of chance");
        List<String> blocks = new ArrayList<>(bundled().getConfigurationSection("games").getKeys(false));
        assertEquals("kid_races", blocks.get(blocks.indexOf("sportsbook") - 1),
                "appended at the end of games: (0.55.0's Sports Board was appended after it, and more after that)");
        assertEquals(List.of("enabled", "prize", "daily_cap", "gather_seconds", "race_seconds"),
                com.dierks.homecraft.games.kidrace.KidRacesSettings.KEYS);
        List<String> warns = new ArrayList<>();
        com.dierks.homecraft.games.kidrace.KidRacesSettings read =
                com.dierks.homecraft.games.kidrace.KidRacesSettings.parse(new GamesConfig.Node("games.kid_races",
                        Map.of("prize", 500, "daily_cap", 7, "gather_seconds", 1), warns::add), k);
        assertEquals(com.dierks.homecraft.games.kidrace.KidRacesSettings.PRIZE_MAX, read.prize(), "clamped");
        assertEquals(7, read.dailyCap());
        assertEquals(com.dierks.homecraft.games.kidrace.KidRacesSettings.GATHER_MIN, read.gatherSeconds());
        assertEquals(2, warns.size(), "one WARN each: " + warns);
    }

    /**
     * 0.55.0: the Game Parlor's Sports Board ships on (it only shows with money play on), last in the {@code games:}
     * block (where the backfill appends it on an upgraded server, with no config revision): the big leagues, every 5
     * minutes, 24 hours ahead, 90 of every 100 back, a week to settle, Kalshi's public API. Out-of-range values are
     * clamped with one WARN, an unknown league is left out with one, and the rtp never leaves the band.
     */
    /**
     * 0.57.0: Bingo Night and the Arcade Room's Picture Bingo ship on, last in the {@code games:} block (where the
     * backfill appends them on an upgraded server, with no config revision), as their defaults; Picture Bingo is free
     * and never a game of chance, and its daily cap is inside the server's skill cap.
     */
    @Test
    void theBingosShipAtTheEndOfTheBlock() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        assertEquals(com.dierks.homecraft.games.chance.bingo.BingoSettings.defaults(),
                parsed.settings(com.dierks.homecraft.games.chance.bingo.Bingo.SPEC));
        com.dierks.homecraft.games.kidbingo.KidBingoSettings k =
                parsed.settings(com.dierks.homecraft.games.kidbingo.KidBingo.SPEC);
        assertEquals(com.dierks.homecraft.games.kidbingo.KidBingoSettings.defaults(), k);
        assertTrue(k.dailyCap() <= parsed.common().skillDailyCap(), "inside the server's skill cap");
        assertTrue(com.dierks.homecraft.games.chance.bingo.Bingo.SPEC.kind().chance());
        assertFalse(com.dierks.homecraft.games.kidbingo.KidBingo.SPEC.kind().chance(), "never a game of chance");
        List<String> blocks = new ArrayList<>(bundled().getConfigurationSection("games").getKeys(false));
        assertEquals(List.of("bingo", "kid_bingo"), blocks.subList(blocks.size() - 4, blocks.size() - 2),
                "appended at the end of games: (0.57.0's Video Poker and Creeper Fuse after them)");
        assertEquals(com.dierks.homecraft.games.chance.bingo.BingoSettings.KEYS,
                new ArrayList<>(bundled().getConfigurationSection("games.bingo").getKeys(false)), "every key shipped");
        assertEquals(com.dierks.homecraft.games.kidbingo.KidBingoSettings.KEYS,
                new ArrayList<>(bundled().getConfigurationSection("games.kid_bingo").getKeys(false)));
    }

    /**
     * 0.57.0: the Game Parlor's Video Poker and Creeper Fuse ship on, last in the {@code games:} block (where the
     * backfill appends them on an upgraded server, with no config revision): Video Poker at 1, 2 or 5 tokens with the
     * 6/5 Jacks or Better table and a Royal Flush of 100; Creeper Fuse at 10, 20 or 50 tokens giving back 94. A pay
     * table outside the band closes Video Poker with one WARN; a Creeper Fuse stake whose whole-token pay could give
     * back under 85 is left out with one.
     */
    @Test
    void videoPokerAndCreeperFuseShipAtTheEndOfTheBlock() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        com.dierks.homecraft.games.chance.poker.VideoPokerSettings vp =
                parsed.settings(com.dierks.homecraft.games.chance.poker.VideoPoker.SPEC);
        assertEquals(com.dierks.homecraft.games.chance.poker.VideoPokerSettings.defaults(), vp);
        assertEquals(List.of(1, 2, 5), vp.open());
        assertEquals(100, vp.pays().get("royal_flush"));
        assertEquals(1, vp.pays().get("jacks_or_better"));
        com.dierks.homecraft.games.chance.fuse.CreeperFuseSettings cf =
                parsed.settings(com.dierks.homecraft.games.chance.fuse.CreeperFuse.SPEC);
        assertEquals(com.dierks.homecraft.games.chance.fuse.CreeperFuseSettings.defaults(), cf);
        assertEquals(94.0, cf.rtp());
        assertEquals(2000, cf.top());
        assertEquals(List.of(150, 200, 300, 500, 1000), cf.autoChoices());
        List<String> blocks = new ArrayList<>(bundled().getConfigurationSection("games").getKeys(false));
        assertEquals(List.of("kid_bingo", "video_poker", "creeper_fuse"), blocks.subList(blocks.size() - 3,
                blocks.size()), "appended at the end of games: (after 0.57.0's Hold'em and the bingos)");
        assertTrue(com.dierks.homecraft.games.chance.poker.VideoPoker.SPEC.kind().chance());
        assertTrue(com.dierks.homecraft.games.chance.fuse.CreeperFuse.SPEC.kind().chance());
        List<String> warns = new ArrayList<>();
        com.dierks.homecraft.games.chance.poker.VideoPokerSettings nineSix =
                com.dierks.homecraft.games.chance.poker.VideoPokerSettings.parse(new GamesConfig.Node(
                        "games.video_poker", Map.of("pays", Map.of("royal_flush", 800, "full_house", 9, "flush", 6)),
                        warns::add), vp);
        assertTrue(nineSix.odds().isEmpty(), "99.5 of every 100 is past the band: closed");
        assertEquals(1, warns.size(), warns.toString());
        warns.clear();
        com.dierks.homecraft.games.chance.fuse.CreeperFuseSettings five =
                com.dierks.homecraft.games.chance.fuse.CreeperFuseSettings.parse(new GamesConfig.Node(
                        "games.creeper_fuse", Map.of("stakes", List.of(5, 10), "rtp", 99, "top", 5,
                        "auto_cash_out", List.of(1.5, 7)), warns::add), cf);
        assertEquals(95.0, five.rtp(), "held to the band's top");
        assertEquals(List.of(10), five.open(), "5 tokens left out");
        assertEquals(500, five.top());
        assertEquals(List.of(150), five.autoChoices(), "a choice past the top is dropped");
        assertEquals(2, warns.size(), "one WARN each (rtp, 5 tokens): " + warns);
    }

    @Test
    void theSportsBoardShipsAtTheEndOfTheBlock() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings s =
                parsed.settings(com.dierks.homecraft.games.chance.sportsbook.Sportsbook.SPEC);
        assertEquals(com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings.defaults(), s);
        assertTrue(s.enabled());
        assertEquals(List.of("nfl", "nba", "nhl", "mlb", "ncaaf", "ncaamb"), s.leagues());
        assertEquals(5, s.refreshMinutes());
        assertEquals(24, s.hoursAhead());
        assertEquals(90.0, s.rtp());
        assertEquals(7, s.settleDays());
        assertEquals("https://api.elections.kalshi.com/trade-api/v2", s.apiBase());
        assertTrue(s.liveScores(), "0.60.0: the live scores are read as shipped");
        assertEquals(60, s.scoreSeconds());
        assertEquals(16, s.scoreGames());
        assertEquals("sportsbook", com.dierks.homecraft.games.chance.sportsbook.Sportsbook.SPEC.id(),
                "the id the Parlor's wall board is tapped as (machine:sportsbook)");
        assertTrue(com.dierks.homecraft.games.chance.sportsbook.Sportsbook.SPEC.kind().chance(),
                "a game of chance: the play gate, Take a break and the Parlor's money rules all apply");
        List<String> blocks = new ArrayList<>(bundled().getConfigurationSection("games").getKeys(false));
        assertEquals("sportsbook", blocks.get(blocks.size() - 6),
                "appended at the end of games: (0.57.0's Hold'em, two bingos, Video Poker and Creeper Fuse after it)");
        assertEquals(List.of("enabled", "leagues", "refresh_minutes", "hours_ahead", "rtp", "settle_days", "api_base",
                "live_scores", "score_seconds", "score_games"),
                com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings.KEYS);
        List<String> warns = new ArrayList<>();
        com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings read =
                com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings.parse(new GamesConfig.Node(
                        "games.sportsbook", Map.of("leagues", List.of("NBA", "curling", "KXWNBAGAME", "nba"),
                        "refresh_minutes", 0, "rtp", 99, "api_base", "http://example.com"), warns::add), s);
        assertEquals(List.of("nba", "kxwnbagame"), read.leagues(), "known names and series tickers, once each");
        assertEquals("WNBA", read.boardLeagues().get(1).tab(), "a series ticker the board knows reads as its league");
        assertEquals(com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings.REFRESH_MIN, read.refreshMinutes());
        assertEquals(95.0, read.rtp(), "held to the band's top");
        assertEquals(s.apiBase(), read.apiBase(), "https only");
        assertEquals(4, warns.size(), "one WARN each (curling, refresh, rtp, api_base): " + warns);
    }

    /**
     * 0.57.0: Texas Hold'em ships on, last in the {@code games:} block (where the backfill appends it on an upgraded
     * server, with no config revision): 20, 50 or 100 tokens in, blinds of 1 and 2, 200 play chips at the friendly
     * table, 10 buy-ins a day, NPCs filling the friendly table to 4. No rtp: the players play each other. Out-of-range
     * values are clamped with one WARN each, and a big blind under the small one is raised to it.
     */
    @Test
    void holdemShipsAtTheEndOfTheBlock() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        com.dierks.homecraft.games.chance.holdem.HoldemSettings h =
                parsed.settings(com.dierks.homecraft.games.chance.holdem.Holdem.SPEC);
        assertEquals(com.dierks.homecraft.games.chance.holdem.HoldemSettings.defaults(), h);
        assertTrue(h.enabled());
        assertEquals(List.of(20, 50, 100), h.buyIns());
        assertEquals("1/2", h.blinds());
        assertEquals(200, h.friendlyChips());
        assertEquals(10, h.dailyLimit());
        assertEquals(4, h.npcSeats());
        assertEquals("holdem", com.dierks.homecraft.games.chance.holdem.Holdem.SPEC.id(),
                "the id the Parlor's card table is tapped as (machine:holdem)");
        assertTrue(com.dierks.homecraft.games.chance.holdem.Holdem.SPEC.kind().chance(),
                "a game of chance: the play gate, Take a break and the Parlor's money rules all apply");
        List<String> blocks = new ArrayList<>(bundled().getConfigurationSection("games").getKeys(false));
        assertEquals("holdem", blocks.get(blocks.indexOf("sportsbook") + 1),
                "appended at the end of games: (right after the Sports Board; 0.57.0's other games after it)");
        assertEquals(List.of("enabled", "buy_ins", "small_blind", "big_blind", "friendly_chips", "daily_limit",
                "npc_seats", "away_seconds", "pause_seconds"),
                com.dierks.homecraft.games.chance.holdem.HoldemSettings.KEYS);
        List<String> warns = new ArrayList<>();
        com.dierks.homecraft.games.chance.holdem.HoldemSettings read =
                com.dierks.homecraft.games.chance.holdem.HoldemSettings.parse(new GamesConfig.Node("games.holdem",
                        Map.of("small_blind", 5, "big_blind", 2, "npc_seats", 9, "away_seconds", 1,
                                "buy_ins", List.of(100, 20, 20)), warns::add), h);
        assertEquals(5, read.bigBlind(), "a big blind under the small one is raised to it");
        assertEquals(6, read.npcSeats(), "clamped to the table's six");
        assertEquals(3, read.awaySeconds());
        assertEquals(List.of(20, 100), read.buyIns(), "sorted, once each");
        assertEquals(3, warns.size(), "one WARN each (big_blind, npc_seats, away_seconds): " + warns);
    }

    @Test
    void freshCoursesKeepsItsSettingsInGamesFresh() throws Exception {
        assertEquals("fresh", GamesConfig.block("fresh_courses"), "the game fresh_courses reads games.fresh");
        assertEquals("snake", GamesConfig.block("snake"), "every other game reads the block of its id");
        assertTrue(GamesConfig.KEYS.contains("fresh.cadence"), "its keys are listed under fresh");
        assertTrue(GamesConfig.KEYS.stream().noneMatch(k -> k.startsWith("fresh_courses.") || k.startsWith("daily.")),
                "and nowhere else");
        Map<String, Object> games = shipped();
        put(games, "fresh.cadence", "daily");
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        assertEquals(List.of(), warns, "no WARN");
        assertEquals(1, parsed.settings(com.dierks.homecraft.games.gen.DailyCourses.SPEC).cadenceDays(),
                "games.fresh.cadence is the game's cadence");
        for (String stray : List.of("daily", "fresh_courses")) {
            Map<String, Object> g = shipped();
            put(g, stray + ".enabled", true);
            List<String> w = new ArrayList<>();
            GamesConfig.parse(g, w::add, null);
            assertEquals(1, warnsNaming(w, "games." + stray), "games." + stray + " is not a block: " + w);
        }
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
            put(games, GamesConfig.block(spec.id()), null);
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            assertEquals(List.of(), warns, "games." + GamesConfig.block(spec.id()) + " left out");
            assertAllShipped(parsed, "games." + GamesConfig.block(spec.id()) + " left out");
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
        clamps.put("fresh.retry_minutes", 0);
        clamps.put("fresh.budget.blocks_per_tick", 0);
        clamps.put("fresh.stars.gold.hard", 0.5);
        clamps.put("fresh.rewards.clear_daily.fresh_tiny_golf", 500);
        clamps.put("trials.warmup_seconds", 9_999);
        clamps.put("trials.party_max", 40);
        clamps.put("race_night.max_racers", 40);
        clamps.put("race_night.min_racers", 20);
        clamps.put("race_night.prizes", List.of(5, 3, TokenBalance.RACE_MAX_PRIZE_PER_NIGHT + 10));
        clamps.put("race_night.finisher_prize", TokenBalance.RACE_MAX_FINISHER_PRIZE + 1);
        clamps.put("race_night.prize_events_per_week", 9);
        clamps.put("race_night.warmup_seconds", 9_999);
        clamps.put("falling_floors.daily_reward", TokenBalance.FLOORS_MAX_REWARD + 1);
        clamps.put("falling_floors.fade_ticks", 2);
        clamps.put("falling_floors.max_players", 30);
        clamps.put("falling_floors.origin", List.of(5377, 176, 4352));
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
    void raceNightAndFallingFloorsShipOffAndReadTheirOwnBlocks() throws Exception {
        // EVENTS-DROPPER-SPEC §A.10 and §B.3.5, plus the owner's warm-up (D3)
        GamesConfig.Parsed shipped = GamesConfig.parse(shipped(), w -> { }, null);
        com.dierks.homecraft.games.event.RaceNightSettings rn =
                shipped.settings(com.dierks.homecraft.games.event.RaceNight.SPEC);
        assertFalse(rn.enabled(), "Race Night ships off");
        assertEquals(List.of("FRI 19:00"), rn.schedule(), "Fridays at 7:00 PM");
        assertTrue(rn.autoCourse(), "takes turns among the raceable tracks");
        assertEquals(TokenBalance.RACE_PRIZES, rn.prizes(), "the token balance's prizes (the judged ones were 5/3/2)");
        assertEquals(TokenBalance.RACE_FINISHER_PRIZE, rn.finisherPrize(), "and its prize for every other finisher");
        assertEquals(3, rn.prizeEventsPerWeek(), "at most 3 prize nights a week");
        assertEquals(180, rn.warmupSeconds(), "a shared 3-minute warm-up before the grid");
        assertTrue(rn.seasonOn(), "a monthly season board");
        com.dierks.homecraft.games.trial.TimeTrialsSettings trials =
                shipped.settings(com.dierks.homecraft.games.trial.TimeTrials.SPEC);
        assertEquals(180, trials.warmupSeconds(), "Warm up (3:00) before a timed run (D3)");
        assertTrue(trials.warmupsOn(), "warm-ups ship on");
        assertEquals(8, trials.partyMax(), "a party race holds up to 8 (D4)");
        com.dierks.homecraft.games.arena.FallingFloorsSettings ff =
                shipped.settings(com.dierks.homecraft.games.arena.FallingFloors.SPEC);
        assertFalse(ff.enabled(), "Falling Floors ships off");
        assertEquals(com.dierks.homecraft.games.arena.rules.RoundSettings.defaults(), ff.round(),
                "its round knobs are the pure rules' shipped ones");
        assertEquals(com.dierks.homecraft.games.arena.rules.ArenaScoring.Rewards.defaults(), ff.rewards(),
                "and its rewards");
        assertEquals("x 6688..6735, y 176..215, z 8544..8591", ff.box().describe(),
                "the box of §B.3.2 at its shipped spot (LAYOUT-SPEC §1.5)");

        for (Map.Entry<String, Object> junk : Map.<String, Object>of("race_night.season", "weekly",
                "race_night.course", "Ice Boat!", "race_night.prizes", List.of(5, 3),
                "falling_floors.solo", "sometimes", "falling_floors.milestones", List.of(60, 30, 120)).entrySet()) {
            Map<String, Object> games = shipped();
            put(games, "enabled", true);
            put(games, junk.getKey(), junk.getValue());
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            String game = junk.getKey().startsWith("race_night") ? "race_night" : "falling_floors";
            assertEquals(Set.of(game), parsed.unreadable(), junk + " closes " + game + " only: " + warns);
            assertEquals(1, warnsNaming(warns, "games." + junk.getKey()), junk + ": " + warns);
            assertTrue(parsed.enabled(), junk + ": the other games stay on");
        }
        Map<String, Object> games = shipped();
        put(games, "race_night.course", "Fresh_Boat");
        put(games, "falling_floors.origin", List.of(5376, 400, 4352));
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        assertEquals("fresh_boat", parsed.settings(com.dierks.homecraft.games.event.RaceNight.SPEC).course(),
                "a course id is read in lower case");
        assertEquals(List.of(5376, 273, 4352), parsed.settings(com.dierks.homecraft.games.arena.FallingFloors.SPEC)
                .origin(), "the box is kept inside the world's heights");
        assertEquals(1, warns.size(), "one WARN, for the height: " + warns);
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
    void theShippedCapsHoldEveryWholeRewardAndBoundADay() throws Exception {
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        assertEquals(List.of(), com.dierks.homecraft.games.RewardCeilings.problems(parsed),
                "every reward paid whole fits each cap it counts toward");
        int skill = parsed.common().skillDailyCap();
        com.dierks.homecraft.games.gen.DailySettings fresh =
                parsed.settings(com.dierks.homecraft.games.gen.DailyCourses.SPEC);
        int trials = 0;
        int golf = 0;
        for (com.dierks.homecraft.games.gen.DailySettings.SlotConfig c : fresh.slots()) {
            if (c.def().golf()) {
                golf = Math.max(golf, c.dailyClear());
            } else {
                trials = Math.max(trials, c.dailyClear());
            }
        }
        assertTrue(skill >= trials + golf, "the skill cap (" + skill + ") pays both on one evening, so a new-set "
                + "Monday never holds back a big first finish behind the other");
        // the v4 audit, ECON06: every golf course shares golf's cap, so it holds both Fresh golf first finishes
        // in one day in either order, after the first course's par and three holes-in-one (BALANCE-SPEC §4.1:
        // what a cap holds back is never Golf of the Week's 9-hole round)
        com.dierks.homecraft.games.golf.MiniGolfSettings g =
                parsed.settings(com.dierks.homecraft.games.golf.MiniGolf.SPEC);
        int golfClears = 0;
        for (com.dierks.homecraft.games.gen.DailySettings.SlotConfig c : fresh.slots()) {
            golfClears += c.def().golf() ? c.dailyClear() : 0;
        }
        assertTrue(g.dailyCap() >= golfClears + g.parReward() + 3 * g.holeInOneReward(), "golf's cap ("
                + g.dailyCap() + ") holds Tiny Golf's and Golf of the Week's first finishes (" + golfClears
                + ") after par and three holes-in-one");
        assertTrue(g.dailyCap() <= skill, "and stays under the skill cap");
        assertTrue(skill <= MAX_SKILL_CAP, "and stays a cap: at most " + MAX_SKILL_CAP + ", not " + skill);
        com.dierks.homecraft.games.event.RaceNightSettings night =
                parsed.settings(com.dierks.homecraft.games.event.RaceNight.SPEC);
        assertTrue(night.prizes().get(0) <= com.dierks.homecraft.games.event.NightRules.MAX_PRIZE_PER_NIGHT,
                "Race Night's 1st prize is paid in full under the night's bound");
        com.dierks.homecraft.games.cup.live.CupSettings cup =
                parsed.settings(com.dierks.homecraft.games.cup.live.WeeklyCup.SPEC);
        assertTrue(cup.serverTopup() >= 2 * cup.entry(),
                "the Cup's top-up is at least twice its entry: nobody in a Cup of 2 or 3 who sets a time loses");
    }

    @Test
    void aShippedDaysRepeatablesFitTheSkillCapAndASetPaysAboutAWeeksPlay() throws Exception {
        // BALANCE-SPEC §6 #5: the budget, worked out from the bundled file alone
        GamesConfig.Parsed parsed = GamesConfig.parse(bundled(), w -> { });
        int repeatables = 0;
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            if (parsed.settings(spec) instanceof com.dierks.homecraft.games.cabinet.CabinetSettings cab) {
                repeatables += cab.dailyReward();
            }
        }
        assertEquals(6 * TokenBalance.CABINET_DAILY + 2 * TokenBalance.DUEL_DAILY, repeatables, "eight cabinet daily goals");
        repeatables += parsed.common().featuredBonus();
        repeatables += parsed.settings(com.dierks.homecraft.games.trial.TimeTrials.SPEC).courseOfWeekBonus();
        repeatables += parsed.settings(com.dierks.homecraft.games.arena.FallingFloors.SPEC).dailyReward();
        assertTrue(repeatables <= parsed.common().skillDailyCap(),
                "everything that repeats daily fits the skill cap (" + parsed.common().skillDailyCap() + ")");

        com.dierks.homecraft.games.gen.DailySettings fresh =
                parsed.settings(com.dierks.homecraft.games.gen.DailyCourses.SPEC);
        int set = 0;
        int sum = 0;
        for (com.dierks.homecraft.games.gen.DailySettings.SlotConfig c : fresh.slots()) {
            int weekly = fresh.rewards().clearWeekly().get(c.id());
            if (c.enabled()) {
                set += c.dailyClear();
                sum += weekly;
            }
        }
        assertEquals(sum, set, "a weekly set pays each course's clear_weekly, once per set");
        assertTrue(set <= 7 * parsed.common().skillDailyCap(), "and a week's skill cap can hold a whole set");
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
                put(games, GamesConfig.block(spec.id()), junk);
                List<String> warns = new ArrayList<>();
                GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
                String key = "games." + GamesConfig.block(spec.id());
                assertTrue(parsed.enabled(), key + ": the other games stay on");
                assertFalse(parsed.readable(spec.id()), key + " = " + junk + " closes " + spec.id());
                assertEquals(Set.of(spec.id()), parsed.unreadable(), key + ": only that game");
                assertEquals(1, warns.size(), key + ": " + warns);
                assertTrue(warns.get(0).startsWith(key + " "), warns.toString());
            }

            Map<String, Object> games = shipped();
            put(games, GamesConfig.block(spec.id()) + ".enabled", "maybe");
            List<String> warns = new ArrayList<>();
            GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
            assertFalse(parsed.readable(spec.id()), spec.id() + ".enabled: maybe fails closed");
            assertEquals(1, warnsNaming(warns, "games." + GamesConfig.block(spec.id()) + ".enabled"), warns.toString());
            assertEquals(1, warns.size(), warns.toString());
            assertEquals(switchedOff(spec), parsed.settings(spec), spec.id() + " reads switched off");
        }
    }

    @Test
    void aBareSwitchWhereAGamesSectionBelongsIsItsEnabled() throws Exception {
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            Map<String, Object> games = shipped();
            put(games, GamesConfig.block(spec.id()), false);
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
