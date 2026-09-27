package com.dierks.homecraft.config;

import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.ItemOverride;
import com.dierks.homecraft.market.sim.RealSymbol;
import com.dierks.homecraft.market.sim.Season;
import com.dierks.homecraft.market.sim.SeasonCalendar;
import com.dierks.homecraft.market.sim.SimSettings;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live market's config: {@code market.sim} and the per-row catalog keys (spec §12, §15).
 *
 * <p>Pinned here:
 * <ul>
 *   <li>the shipped block parses to exactly {@link SimSettings#defaults()}, with no WARN and
 *       no per-item overrides, and it ships every key the parser reads and no other;</li>
 *   <li>a left-out key or section reads as the shipped value;</li>
 *   <li>the §2.2 code limits win over config, and every value that had to change logs exactly
 *       one WARN naming its full key;</li>
 *   <li>pairs are sorted, {@code news.hours} falls back to 07:00-21:00, a bad {@code MM-DD}
 *       drops only its season, an unknown provider or a non-https url keeps
 *       {@code real_world} off, an empty or unusable headline list falls back to the shipped
 *       one, and unknown keys and item ids are reported;</li>
 *   <li>per-row {@code sim}, {@code volatility}, {@code sim_weight} and {@code news_name}
 *       parse, clamp with a WARN, and skip reserved {@code @} ids;</li>
 *   <li>a config the parser cannot use turns the live market OFF instead of failing, and so
 *       does a {@code market.sim.enabled} it cannot read: the kill switch fails closed.</li>
 * </ul>
 *
 * <p>Needs paper-api on the test classpath for {@link YamlConfiguration}, like
 * {@code ConfigMigrationTest}; no server is started.
 */
class MarketSimConfigTest {

    /** The bundled config.yml, loaded with the throwing {@code load(Reader)} (see ConfigMigrationTest). */
    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = MarketSimConfigTest.class.getResourceAsStream("/config.yml")) {
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

    /** {@code market.sim} built from {@code simBody} (indented four spaces), over the shipped catalog ids. */
    private static YamlConfiguration sim(String simBody) {
        return yaml("market:\n  sim:\n" + simBody + CATALOG);
    }

    private static final String CATALOG = """
              catalog:
                - { id: cobblestone, material: COBBLESTONE }
                - { id: oak_log, material: OAK_LOG }
                - { id: wheat, material: WHEAT }
                - { id: iron_ingot, material: IRON_INGOT }
                - { id: gold_ingot, material: GOLD_INGOT }
                - { id: diamond, material: DIAMOND }
            """;

    private static long warnsNaming(List<String> warns, String key) {
        return warns.stream().filter(w -> w.startsWith(key + " ")).count();
    }

    private static void assertOneWarn(List<String> warns, String key) {
        assertEquals(1, warnsNaming(warns, key), "exactly one WARN naming " + key + ": " + warns);
    }

    // ---- the shipped block ----------------------------------------------------------------

    @Test
    void theShippedBlockIsExactlyTheDefaults() throws Exception {
        List<String> warns = new ArrayList<>();
        MarketSimConfig.Parsed parsed = MarketSimConfig.parse(bundled(), warns::add);
        SimSettings d = SimSettings.defaults();
        SimSettings s = parsed.settings();

        assertEquals(List.of(), warns, "the shipped config must load without a single WARN");
        // Section by section first, so a mismatch names its section …
        assertEquals(d.drift(), s.drift());
        assertEquals(d.hot(), s.hot());
        assertEquals(d.deal(), s.deal());
        assertEquals(d.news(), s.news());
        assertEquals(d.announce(), s.announce());
        assertEquals(d.headlines(), s.headlines());
        assertEquals(d.samePlural(), s.samePlural());
        assertEquals(d.seasons().list(), s.seasons().list());
        assertEquals(d.seasons(), s.seasons());
        assertEquals(d.real(), s.real());
        // … then the whole tree.
        assertEquals(d, s, "config.yml's market.sim must mirror SimSettings.defaults() key for key");
        assertTrue(parsed.enabled(), "the live market ships on");
        assertEquals(Map.of(), parsed.overrides(), "no shipped catalog row sets a per-item sim key");
        assertSame(ItemOverride.NONE, parsed.override("iron_ingot"));
    }

    /** The shipped headline lists and seasons are the ones the other classes carry. */
    @Test
    void theShippedListsAreWhole() throws Exception {
        SimSettings s = MarketSimConfig.parse(bundled(), w -> { }).settings();
        assertEquals(12, s.headlines().up().size());
        assertEquals(12, s.headlines().down().size());
        assertEquals(6, s.headlines().hot().size());
        assertEquals(6, s.headlines().deal().size());
        assertEquals(3, s.headlines().wanted().size());
        assertEquals(Headlines.SAME_PLURAL.size(), s.samePlural().size());
        assertEquals(SeasonCalendar.SHIPPED, s.seasons().list());
        assertEquals(List.of("gold_ingot", "wheat", "iron_ingot"),
                s.real().symbols().stream().map(RealSymbol::item).toList());
        assertFalse(s.real().enabled(), "real-world prices ship off");
    }

    /** The shipped block carries every key the parser reads, and only those (lists are one leaf each). */
    @Test
    void theShippedBlockHasExactlyTheKeysTheParserReads() throws Exception {
        YamlConfiguration c = bundled();
        ConfigurationSection sim = c.getConfigurationSection(MarketSimConfig.PATH);
        assertNotNull(sim, "config.yml ships market.sim");
        List<String> leaves = new ArrayList<>();
        for (String key : sim.getKeys(true)) {
            if (!sim.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        assertEquals(MarketSimConfig.KEYS, leaves, "config order, one leaf per key");
    }

    @Test
    void aLeftOutKeyOrSectionReadsAsShipped() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("    enabled: false\n"), warns::add).settings();
        assertEquals(List.of(), warns);
        assertEquals(SimSettings.defaults().withEnabled(false), s);

        SimSettings none = MarketSimConfig.parse(yaml("market:\n" + CATALOG), warns::add).settings();
        assertEquals(SimSettings.defaults(), none, "no market.sim at all is the shipped one");
        assertEquals(List.of(), warns);
    }

    // ---- the code limits -------------------------------------------------------------------

    @Test
    void theCodeLimitsWinAndEachClampNamesItsKey() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    max_up_percent: 50
                    max_down_percent: 90
                    drift:
                      max_percent: 20
                    hot:
                      percent: [10, 40]
                    deal:
                      percent: [5, 30]
                    news:
                      percent: [15, 60]
                      min_percent: 40
                    seasons:
                      predictable_max_percent: 10
                    real_world:
                      max_percent: 9
                """), warns::add).settings();

        assertEquals(25, s.maxUpPercent());
        assertEquals(25, s.maxDownPercent());
        assertEquals(1.25, s.multiplierHi(), "max_up 50 still tops out at 1.25x");
        assertEquals(0.75, s.multiplierLo(), "max_down 90 still bottoms out at 0.75x");
        assertEquals(8, s.drift().maxPercent());
        assertEquals(0.08, s.drift().maxFrac());
        assertEquals(new SimSettings.Range(10, 15), s.hot().percent());
        assertEquals(new SimSettings.Range(5, 15), s.deal().percent());
        assertEquals(0.15, s.hot().maxFrac());
        assertEquals(new SimSettings.Range(15, 25), s.news().percent());
        assertEquals(0.25, s.news().maxFrac());
        assertEquals(25, s.news().minPercent());
        assertEquals(4.5, s.seasons().predictableMaxPercent());
        assertEquals(0.045, s.seasons().predictableCap(0.10));
        assertEquals(4.5, s.real().maxPercent());

        for (String key : List.of("market.sim.max_up_percent", "market.sim.max_down_percent",
                "market.sim.drift.max_percent", "market.sim.hot.percent", "market.sim.deal.percent",
                "market.sim.news.percent", "market.sim.news.min_percent",
                "market.sim.seasons.predictable_max_percent", "market.sim.real_world.max_percent")) {
            assertOneWarn(warns, key);
        }
        assertEquals(9, warns.size(), "one WARN per clamped key, nothing else: " + warns);
        assertTrue(warns.stream().allMatch(w -> w.contains("locked in code")), warns.toString());
    }

    @Test
    void tickMinutesIsHeldToOneToSixty() {
        for (Object[] c : new Object[][]{{"0", 1}, {"-5", 1}, {"600", 60}, {"1", 1}, {"60", 60}, {"15", 15}}) {
            List<String> warns = new ArrayList<>();
            SimSettings s = MarketSimConfig.parse(sim("    tick_minutes: " + c[0] + "\n"), warns::add).settings();
            assertEquals(c[1], s.tickMinutes(), "tick_minutes " + c[0]);
            boolean moved = !String.valueOf(c[1]).equals(c[0]);
            assertEquals(moved ? 1 : 0, warnsNaming(warns, "market.sim.tick_minutes"), warns.toString());
        }
        List<String> warns = new ArrayList<>();
        assertEquals(2, MarketSimConfig.parse(sim("    tick_minutes: 2.4\n"), warns::add).settings().tickMinutes());
        assertOneWarn(warns, "market.sim.tick_minutes");
    }

    @Test
    void percentsCannotGoNegativeAndPairsAreSorted() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    max_up_percent: -10
                    drift:
                      calm_percent: -1
                    hot:
                      percent: [15, 8]
                      hold_hours: [54, 30]
                    news:
                      join_delay_minutes: [-2, 8]
                """), warns::add).settings();

        assertEquals(0, s.maxUpPercent());
        assertEquals(1.0, s.multiplierHi(), "a negative max_up means never above the balanced price");
        assertEquals(0, s.drift().calmPercent());
        assertEquals(new SimSettings.Range(8, 15), s.hot().percent());
        assertEquals(new SimSettings.Range(30, 54), s.hot().holdHours());
        assertEquals(new SimSettings.Range(0, 8), s.news().joinDelayMinutes());
        for (String key : List.of("market.sim.max_up_percent", "market.sim.drift.calm_percent",
                "market.sim.hot.percent", "market.sim.hot.hold_hours", "market.sim.news.join_delay_minutes")) {
            assertOneWarn(warns, key);
        }
    }

    @Test
    void notANumberKeepsTheShippedValue() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    tick_minutes: fast
                    keep_days: "90"
                    hot:
                      percent: [8]
                      enabled: maybe
                """), warns::add).settings();
        assertEquals(5, s.tickMinutes());
        assertEquals(90, s.keepDays(), "a quoted number is still a number");
        assertEquals(new SimSettings.Range(8, 15), s.hot().percent());
        assertTrue(s.hot().enabled());
        assertOneWarn(warns, "market.sim.tick_minutes");
        assertOneWarn(warns, "market.sim.hot.percent");
        assertOneWarn(warns, "market.sim.hot.enabled");
        assertEquals(0, warnsNaming(warns, "market.sim.keep_days"));
    }

    /**
     * The kill switch fails CLOSED. Every other key falls back to its shipped value, but the only
     * reason to edit market.sim.enabled is to turn the market off, so a typo there must not leave
     * it running. Review findings #6 / #33.
     */
    @Test
    void anUnreadableKillSwitchTurnsTheMarketOff() {
        for (String bad : List.of("0", "1", "n", "flase", "disabled", "\"maybe\"", "[false]")) {
            List<String> warns = new ArrayList<>();
            SimSettings s = MarketSimConfig.parse(sim("    enabled: " + bad + "\n"), warns::add).settings();
            assertEquals(SimSettings.defaults().withEnabled(false), s, "enabled: " + bad + " must read as off");
            assertOneWarn(warns, "market.sim.enabled");
            assertEquals(1, warns.size(), warns.toString());
            assertTrue(warns.get(0).contains("off"), "the WARN says the market is off: " + warns);
        }
        for (String off : List.of("false", "off", "no", "\"false\"", "\"OFF\"", "\" no \"")) {
            List<String> warns = new ArrayList<>();
            assertFalse(MarketSimConfig.parse(sim("    enabled: " + off + "\n"), warns::add).enabled(),
                    "enabled: " + off);
            assertEquals(List.of(), warns, "enabled: " + off + " is a plain off");
        }
        for (String on : List.of("true", "yes", "on", "\"true\"")) {
            List<String> warns = new ArrayList<>();
            assertTrue(MarketSimConfig.parse(sim("    enabled: " + on + "\n"), warns::add).enabled(),
                    "enabled: " + on);
            assertEquals(List.of(), warns);
        }
        // Left out, it is the shipped value (on); only a value that is THERE and unreadable is off.
        assertTrue(MarketSimConfig.parse(sim("    tick_minutes: 5\n"), w -> { }).enabled());
    }

    // ---- the other rules -------------------------------------------------------------------

    @Test
    void newsHoursMustBeHhMmToHhMm() {
        List<String> warns = new ArrayList<>();
        SimSettings bad = MarketSimConfig.parse(sim("    news:\n      hours: \"7am-9pm\"\n"), warns::add).settings();
        assertEquals(SimSettings.Hours.DEFAULT, bad.news().hours());
        assertOneWarn(warns, "market.sim.news.hours");

        warns.clear();
        SimSettings wrap = MarketSimConfig.parse(sim("    news:\n      hours: \"22:00-06:00\"\n"), warns::add)
                .settings();
        assertEquals(new SimSettings.Hours(LocalTime.of(22, 0), LocalTime.of(6, 0)), wrap.news().hours());
        assertTrue(wrap.news().hours().contains(LocalTime.of(2, 0)), "a window may wrap midnight");
        SimSettings allDay = MarketSimConfig.parse(sim("    news:\n      hours: \"00:00-00:00\"\n"), warns::add)
                .settings();
        assertTrue(allDay.news().hours().allDay(), "from == to is all day");
        assertEquals(List.of(), warns);
    }

    @Test
    void aBadSeasonDateDropsOnlyThatSeason() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    seasons:
                      list:
                        - { id: odd_month, name: "Odd", from: "13-01", to: "13-05", percent: { wheat: 2 } }
                        - { id: wheat_week, name: "Wheat Week", from: "06-01", to: "06-07", percent: { wheat: 2 },
                            headline: "Everyone wants wheat!" }
                """), warns::add).settings();
        List<Season> list = s.seasons().list();
        assertEquals(1, list.size(), "the good season stays: " + list);
        assertEquals("wheat_week", list.get(0).id());
        assertEquals(1, warns.stream().filter(w -> w.contains("odd_month")).count(), warns.toString());
    }

    @Test
    void anAdminsEmptySeasonListStaysEmpty() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("    seasons:\n      list: []\n"), warns::add).settings();
        assertEquals(List.of(), s.seasons().list(), "deleting every season is the admin's call");
        assertEquals(List.of(), warns);
    }

    @Test
    void seasonsAreHeldToThePredictableCap() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    seasons:
                      list:
                        - { id: big_one, name: "Big One", from: "06-01", to: "06-07", percent: { wheat: 12 } }
                """), warns::add).settings();
        assertEquals(4.5, s.seasons().list().get(0).percent().get("wheat"));
        assertEquals(1, warns.size(), warns.toString());
    }

    @Test
    void anUnknownProviderOrAPlainHttpUrlKeepsRealWorldOff() {
        List<String> warns = new ArrayList<>();
        SimSettings.Real bing = MarketSimConfig.parse(sim(
                "    real_world:\n      enabled: true\n      provider: bing\n"), warns::add).settings().real();
        assertFalse(bing.enabled());
        assertEquals("stooq", bing.provider(), "the record never carries a provider nothing can fetch");
        assertOneWarn(warns, "market.sim.real_world.provider");

        warns.clear();
        SimSettings.Real http = MarketSimConfig.parse(sim(
                "    real_world:\n      enabled: true\n      url: \"http://example.com/q?s={symbol}\"\n"),
                warns::add).settings().real();
        assertFalse(http.enabled());
        assertEquals("", http.url());
        assertOneWarn(warns, "market.sim.real_world.url");

        warns.clear();
        SimSettings.Real ok = MarketSimConfig.parse(sim("""
                    real_world:
                      enabled: true
                      provider: Yahoo
                      url: "https://example.com/q?s={symbol}"
                """), warns::add).settings().real();
        assertTrue(ok.enabled());
        assertEquals("yahoo", ok.provider());
        assertEquals("https://example.com/q?s={symbol}", ok.url());
        assertEquals(List.of(), warns);
    }

    @Test
    void realWorldSymbolRowsAreChecked() {
        List<String> warns = new ArrayList<>();
        SimSettings.Real r = MarketSimConfig.parse(sim("""
                    real_world:
                      symbols:
                        - { item: gold_ingot, stooq: "gc f!", yahoo: "GC=F", name: "gold" }
                        - { item: gold_ingot, stooq: "gc.f", yahoo: "GC=F", name: "gold again" }
                        - { item: wheat, stooq: "zw.f", yahoo: "", name: "" }
                        - { stooq: "hg.f" }
                """), warns::add).settings().real();
        assertEquals(List.of(new RealSymbol("gold_ingot", "", "GC=F", "gold"),
                new RealSymbol("wheat", "zw.f", "", "wheat")), r.symbols());
        assertEquals(4, warns.size(), "bad symbol, repeat, missing name, missing item: " + warns);
    }

    @Test
    void anUnquotedFetchTimeStillReadsAsThatTime() {
        // YAML 1.1 may read an unquoted 17:30 as the base-60 number 1050; either way it is 17:30.
        List<String> warns = new ArrayList<>();
        SimSettings.Real r = MarketSimConfig.parse(sim("    real_world:\n      fetch_time: 17:30\n"),
                warns::add).settings().real();
        assertEquals(LocalTime.of(17, 30), r.fetchTime());
        SimSettings.Real bad = MarketSimConfig.parse(sim("    real_world:\n      fetch_time: \"5pm\"\n"),
                warns::add).settings().real();
        assertEquals(LocalTime.of(17, 30), bad.fetchTime());
        assertOneWarn(warns, "market.sim.real_world.fetch_time");
    }

    @Test
    void anEmptyOrUnusableHeadlineListFallsBackToTheShippedOne() {
        List<String> warns = new ArrayList<>();
        SimSettings.Headlines h = MarketSimConfig.parse(sim("""
                    headlines:
                      up: []
                      hot:
                        - "No placeholder here!"
                      down:
                        - "So many {item} came in today!"
                        - "The war made {item} cheap!"
                      real_up: ""
                """), warns::add).settings().headlines();

        assertEquals(Headlines.UP, h.up(), "an empty list is the shipped list");
        assertEquals(Headlines.HOT, h.hot(), "a list with nothing usable is the shipped list");
        assertEquals(List.of("So many {item} came in today!"), h.down(), "the good one stays, the bad one goes");
        assertEquals(Headlines.REAL_UP, h.realUp());
        assertEquals(Headlines.DEAL, h.deal(), "a left-out list is the shipped list");
        assertTrue(warns.stream().anyMatch(w -> w.startsWith("market.sim.headlines.up ")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.startsWith("market.sim.headlines.hot")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.startsWith("market.sim.headlines.down #2")), warns.toString());
        assertTrue(warns.stream().anyMatch(w -> w.startsWith("market.sim.headlines.real_up")), warns.toString());
    }

    @Test
    void unknownKeysAndItemIdsAreReported() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    tick_minute: 5
                    deal:
                      sell_limit: true
                    seasons:
                      list:
                        - { id: gem_week, name: "Gem Week", from: "06-01", to: "06-07", percent: { emerald: 3 } }
                    real_world:
                      symbols:
                        - { item: copper_ingot, stooq: "hg.f", yahoo: "HG=F", name: "copper" }
                """), warns::add).settings();
        assertOneWarn(warns, "market.sim.tick_minute");
        assertOneWarn(warns, "market.sim.deal.sell_limit");
        assertFalse(s.deal().sellLimit(), "a HOT-only key under deal changes nothing");
        assertEquals(1, warns.stream().filter(w -> w.contains("emerald")).count(), warns.toString());
        assertEquals(1, warns.stream().filter(w -> w.contains("copper_ingot")).count(), warns.toString());
        assertEquals(4, warns.size(), warns.toString());
    }

    @Test
    void aSimThatIsNotASectionIsReadSafely() {
        List<String> warns = new ArrayList<>();
        SimSettings off = MarketSimConfig.parse(yaml("market:\n  sim: false\n" + CATALOG), warns::add).settings();
        assertEquals(SimSettings.defaults().withEnabled(false), off, "sim: false means off");
        assertEquals(1, warns.size(), "and says how to write it: " + warns);

        warns.clear();
        SimSettings junk = MarketSimConfig.parse(yaml("market:\n  sim: \"yes please\"\n" + CATALOG), warns::add)
                .settings();
        assertFalse(junk.enabled(), "a section nobody can read turns the live market off");
        assertEquals(1, warns.size(), warns.toString());

        // A quoted switch reads the way the config migration will write it (market.sim.enabled).
        for (String word : List.of("off", "no", "\"no\"", "\"off\"")) {
            warns.clear();
            assertEquals(SimSettings.defaults().withEnabled(false),
                    MarketSimConfig.parse(yaml("market:\n  sim: " + word + "\n" + CATALOG), warns::add).settings(),
                    "sim: " + word);
            assertEquals(1, warns.size(), warns.toString());
        }
        warns.clear();
        assertTrue(MarketSimConfig.parse(yaml("market:\n  sim: \"yes\"\n" + CATALOG), warns::add).enabled());
        assertEquals(1, warns.size(), warns.toString());
        assertEquals(Boolean.FALSE, MarketSimConfig.readSwitch("Off"));
        assertEquals(Boolean.TRUE, MarketSimConfig.readSwitch(true));
        assertNull(MarketSimConfig.readSwitch(0));
        assertNull(MarketSimConfig.readSwitch("yes please"));

        warns.clear();
        SimSettings drift = MarketSimConfig.parse(sim("    drift: 8\n"), warns::add).settings();
        assertEquals(SimSettings.Drift.defaults(), drift.drift());
        assertOneWarn(warns, "market.sim.drift");
    }

    @Test
    void aNamespacedSoundIsKeptAndAPlainNameIsReplaced() {
        List<String> warns = new ArrayList<>();
        SimSettings.Announce a = MarketSimConfig.parse(sim("""
                    announce:
                      sound_up: "minecraft:entity.player.levelup"
                      sound_down: BLOCK_NOTE_BLOCK_BASS
                """), warns::add).settings().announce();
        assertEquals("minecraft:entity.player.levelup", a.soundUp());
        assertEquals(SimSettings.Announce.BELL, a.soundDown());
        assertOneWarn(warns, "market.sim.announce.sound_down");
    }

    // ---- per-row catalog keys --------------------------------------------------------------

    @Test
    void perRowKeysParse() {
        List<String> warns = new ArrayList<>();
        MarketSimConfig.Parsed p = MarketSimConfig.parse(yaml("""
                market:
                  catalog:
                    - { id: diamond, material: DIAMOND, sim: false }
                    - { id: iron_ingot, material: IRON_INGOT, volatility: 0.5, sim_weight: 3,
                        news_name: "&fShiny Rocks" }
                    - { id: Oak_Log, material: OAK_LOG, sim_weight: 0 }
                    - { id: wheat, material: WHEAT }
                """), warns::add);

        assertEquals(List.of(), warns);
        assertEquals(new ItemOverride(false, null, null, null), p.override("diamond"));
        assertEquals(new ItemOverride(null, 0.5, 3.0, "Shiny Rocks"), p.override("iron_ingot"),
                "colour codes are stripped from news_name");
        assertEquals(new ItemOverride(null, null, 0.0, null), p.override("oak_log"), "ids are lower-cased");
        assertSame(ItemOverride.NONE, p.override("wheat"));
        assertEquals(List.of("diamond", "iron_ingot", "oak_log"), List.copyOf(p.overrides().keySet()),
                "only rows that set a key, in catalog order");
    }

    @Test
    void perRowKeysAreClampedWithAWarnNamingTheRow() {
        List<String> warns = new ArrayList<>();
        MarketSimConfig.Parsed p = MarketSimConfig.parse(yaml("""
                market:
                  catalog:
                    - { id: iron_ingot, material: IRON_INGOT, volatility: 3, sim_weight: -2 }
                    - { id: oak_log, material: OAK_LOG, volatility: -1, sim: maybe }
                    - { id: wheat, material: WHEAT, sim: "false",
                        news_name: "Golden Wheat Sheaves From Far Away" }
                    - { id: gold_ingot, material: GOLD_INGOT, news_name: "War Gold" }
                """), warns::add);

        assertEquals(new ItemOverride(null, 1.5, 0.0, null), p.override("iron_ingot"));
        assertEquals(new ItemOverride(null, 0.0, null, null), p.override("oak_log"));
        assertEquals(new ItemOverride(false, null, null, "Golden Wheat Sheaves Fro"), p.override("wheat"),
                "news_name is cut to 24 characters");
        assertSame(ItemOverride.NONE, p.override("gold_ingot"), "a news_name with a banned word is ignored");
        for (String key : List.of("market.catalog[iron_ingot].volatility", "market.catalog[iron_ingot].sim_weight",
                "market.catalog[oak_log].volatility", "market.catalog[oak_log].sim",
                "market.catalog[wheat].news_name", "market.catalog[gold_ingot].news_name")) {
            assertOneWarn(warns, key);
        }
        assertEquals(6, warns.size(), warns.toString());
    }

    @Test
    void reservedAndRepeatedIdsAreSkipped() {
        List<String> warns = new ArrayList<>();
        MarketSimConfig.Parsed p = MarketSimConfig.parse(yaml("""
                market:
                  catalog:
                    - { id: "@news", material: PAPER, sim: false }
                    - { id: wheat, material: WHEAT, sim_weight: 2 }
                    - { id: wheat, material: WHEAT, sim_weight: 5 }
                """), warns::add);
        assertEquals(List.of("wheat"), List.copyOf(p.overrides().keySet()));
        assertEquals(2.0, p.override("wheat").weight(), "the first row wins, as in readMarket");
        assertSame(ItemOverride.NONE, p.override("@news"));
        assertEquals(List.of(), warns, "readMarket already warns about these rows");
    }

    @Test
    void theOffValueIsNeutral() {
        assertFalse(MarketSimConfig.Parsed.OFF.enabled());
        assertEquals(SimSettings.defaults().withEnabled(false), MarketSimConfig.Parsed.OFF.settings());
        assertEquals(SimSettings.defaults(), MarketSimConfig.Parsed.DEFAULTS.settings());
        assertSame(ItemOverride.NONE, MarketSimConfig.Parsed.OFF.override(null));
        assertEquals(MarketSimConfig.Parsed.DEFAULTS, MarketSimConfig.parse((ConfigurationSection) null, null));
    }

    @Test
    void aOneSidedBandIsUsedBothWaysAndSaysSo() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    max_up_percent: 25
                    max_down_percent: 10
                """), warns::add).settings();
        assertEquals(0.90, s.multiplierLo(), 1e-9);
        assertEquals(1.10, s.multiplierHi(), 1e-9, "the narrower side sets both, so the mood can't lean one way");
        assertEquals(1, warns.stream().filter(w -> w.startsWith("market.sim: max_up_percent (25)")
                && w.contains("10%, both ways")).count(), warns.toString());
    }

    @Test
    void theDriftSpeedLockIsHeldAndSaysSo() {
        List<String> warns = new ArrayList<>();
        SimSettings s = MarketSimConfig.parse(sim("""
                    drift:
                      half_life_hours: 6
                      lively_percent: 3
                """), warns::add).settings();
        assertEquals(24, s.drift().halfLifeHours(), 1e-9, "the half-life is held to at least 24 h");
        assertOneWarn(warns, "market.sim.drift.half_life_hours");
        assertEquals(1, warns.stream().filter(w -> w.startsWith("market.sim.drift: lively_percent 3")
                && w.contains("held to")).count(), "3% at 24 h is past the speed lock: " + warns);

        List<String> quiet = new ArrayList<>();
        MarketSimConfig.parse(sim("    max_up_percent: 25\n"), quiet::add);
        assertTrue(quiet.isEmpty(), "the shipped drift and a symmetric band say nothing: " + quiet);
    }
}
