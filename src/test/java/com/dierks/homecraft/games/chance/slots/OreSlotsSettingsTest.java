package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ore Slots's settings and everything read from them (spec §5.1, §5.3, §10, R1.2, R1.19-R1.21).
 *
 * <p>Pinned here: the solve runs inside the SPEC's parse, so a table that can't reach the band
 * closes the game with ONE WARN at load, a stake the payout cap starves is dropped with one WARN
 * naming it, and a stake that lands just above its target logs one INFO line; and the screen's
 * give-back line and paytable, the {@code /hcm arcade odds} lines and the website's entry all come
 * from the one engine in the settings record — change {@code rtp} to 87 and they all move together.
 */
class OreSlotsSettingsTest {

    /** The bundled {@code games} section as plain maps (a fresh copy each call). */
    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = OreSlotsSettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String dotted, Object value) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> m = root;
        for (int i = 0; i < parts.length - 1; i++) {
            m = (Map<String, Object>) m.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        }
        m.put(parts[parts.length - 1], value);
    }

    private static OreSlotsSettings parse(Map<String, ?> block, List<String> warns) {
        return OreSlotsSettings.parse(new GamesConfig.Node("games.ore_slots", block, warns::add),
                OreSlotsSettings.defaults());
    }

    private static List<String> naming(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).toList();
    }

    // ---- the parse and its solve ----------------------------------------------------------------

    @Test
    void theShippedBlockIsTheDefaultsWithTheSolvedEngine() throws Exception {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(shipped(), warns::add, infos::add);
        OreSlotsSettings s = parsed.settings(OreSlots.SPEC);
        assertEquals(OreSlotsSettings.defaults(), s, "the bundled block reads as shipped");
        assertEquals(List.of(), naming(warns, "games.ore_slots"), "without a WARN");
        assertEquals(List.of(), naming(infos, "ore_slots"), "and 90 is reachable from below: no INFO either");
        assertEquals(List.of(1, 2, 5), s.engine().stakes());
        assertEquals(1479, s.engine().odds(5).stone(), "Stone solved at ×100");
        assertEquals(250, s.engine().cap(), "max_payout 250: exactly 5 in × the wild line's 50");
        assertTrue(parsed.readable("ore_slots"));
    }

    @Test
    void anImpossibleTableClosesTheGameWithOneWarn() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> zero = new LinkedHashMap<>();
        for (String k : List.of("two", "coal", "copper", "iron", "gold", "diamond", "wild")) {
            zero.put(k, 0);
        }
        GamesConfig.Node n = new GamesConfig.Node("games.ore_slots", Map.of("pays", zero), warns::add);
        OreSlotsSettings s = OreSlotsSettings.parse(n, OreSlotsSettings.defaults());
        assertFalse(s.engine().playable(), "no stake is left");
        assertEquals(1, warns.size(), "ONE WARN for the whole game, not one per stake: " + warns);
        assertTrue(warns.get(0).startsWith("games.ore_slots can't give back 85-95"), warns.get(0));
        assertTrue(warns.get(0).contains("no line pays anything"), "it says why: " + warns.get(0));
        assertTrue(warns.get(0).endsWith("games.ore_slots is off until it is fixed"), warns.get(0));
        assertTrue(n.invalid(), "the framework reads the game as closed until it is fixed");

        warns.clear();
        Map<String, Object> none = new LinkedHashMap<>();
        for (String k : List.of("coal", "copper", "iron", "gold", "diamond", "wild")) {
            none.put(k, 0);
        }
        assertFalse(parse(Map.of("reels", none), warns).engine().playable(), "only Stone could land");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("every reel weight is 0"), warns.get(0));
    }

    @Test
    void aStakeTheCapStarvesIsDroppedWithOneWarnNamingIt() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "enabled", true);
        put(games, "max_payout", 0);
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(games, warns::add, null);
        OreSlotsSettings s = parsed.settings(OreSlots.SPEC);
        List<String> mine = naming(warns, "games.ore_slots");
        assertEquals(1, mine.size(), "one WARN for the one dropped stake: " + warns);
        assertTrue(mine.get(0).startsWith("games.ore_slots.stakes 5 can't give back 85-95"), mine.get(0));
        assertTrue(mine.get(0).contains("max_payout 5"), "it names the cap that did it: " + mine.get(0));
        assertEquals(List.of(1, 2), s.engine().stakes(), "1 and 2 still play");
        assertTrue(parsed.readable("ore_slots"), "a dropped stake is not junk: the game stays open");
    }

    @Test
    void aStakeJustAboveItsTargetLogsOneInfoLine() throws Exception {
        Map<String, Object> games = shipped();
        put(games, "ore_slots.rtp", 85);
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        OreSlotsSettings s = GamesConfig.parse(games, warns::add, infos::add).settings(OreSlots.SPEC);
        assertEquals(List.of(), naming(warns, "games.ore_slots"), "85 is a legal target: no WARN");
        List<String> mine = naming(infos, "ore_slots stake ");
        assertEquals(3, mine.size(), "one INFO per stake: " + infos);
        assertEquals("ore_slots stake 1: 85.0% (target 85 not reachable with whole weights)", mine.get(0));
        for (StakeOdds o : s.engine().odds().values()) {
            assertTrue(o.rtp() >= RtpLimits.MIN, "never under the band: " + o.rtp());
        }
    }

    @Test
    void outOfRangeValuesAreClampedWithOneWarnEach() {
        List<String> warns = new ArrayList<>();
        OreSlotsSettings s = parse(Map.of("reels", Map.of("coal", 50_000), "rtp", 99,
                "stakes", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)), warns);
        assertEquals(3, warns.size(), warns.toString());
        assertEquals(1, naming(warns, "games.ore_slots.reels.coal ").size(), "the weight names its full key");
        assertEquals(1, naming(warns, "games.ore_slots.rtp ").size(), "the band is locked in code");
        assertEquals(1, naming(warns, "games.ore_slots.stakes ").size(), "the screen shows at most 7 stakes");
        assertEquals(OreSlotsSettings.MAX_WEIGHT, s.reels().get("coal"));
        assertEquals(95.0, s.rtp());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), s.stakes());
    }

    // ---- one engine, every reader ---------------------------------------------------------------

    /** A FeedWriter that keeps the one chance entry it is given. */
    private static final class Recorder implements FeedWriter {
        List<Integer> stakes;
        Map<Integer, Double> rtp;
        Integer dailyLimit;
        List<PayRow> rows;
        int entries;

        @Override
        public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                           Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
            assertEquals("ore_slots", id);
            assertEquals("Ore Slots", name);
            this.stakes = stakes;
            this.rtp = rtpByStake;
            this.dailyLimit = dailyLimit;
            this.rows = paytable;
            entries++;
        }

        @Override
        public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                            String holder) {
            throw new AssertionError("Ore Slots is a game of chance");
        }

        @Override
        public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                           String holder) {
            throw new AssertionError("Ore Slots is a game of chance");
        }

        @Override
        public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                         String holder) {
            throw new AssertionError("Ore Slots is a game of chance");
        }
    }

    @Test
    void changingRtpTo87MovesTheScreenTheOddsLinesAndTheFeedTogether() {
        OreSlotsSettings at90 = parse(Map.of(), new ArrayList<>());
        OreSlotsSettings at87 = parse(Map.of("rtp", 87), new ArrayList<>());
        for (OreSlotsSettings s : List.of(at90, at87)) {
            SlotsEngine e = s.engine();
            int floor = RtpLimits.wholePercent(e.odds(1).rtp());
            assertEquals("&eGives back about " + floor + " of every 100 tokens", SlotsCopy.giveBack(e.odds(1)),
                    "the screen's line is the engine's computed value, floored");
            assertEquals("&6Ore Slots &7— gives back about " + RtpLimits.wholePercent(e.lowestRtp())
                    + " of every 100 tokens · 50 plays a day", SlotsCopy.oddsLine("Ore Slots", e, s.dailyLimit()),
                    "/hcm arcade odds reads the same engine");
            assertTrue(SlotsCopy.oddsDetail(e).get(0).contains(RtpLimits.tenthPercent(e.odds(1).rtp()) + "%"),
                    "admins get the same value to one decimal: " + SlotsCopy.oddsDetail(e));
            Recorder feed = new Recorder();
            OreSlots.write(feed, "ore_slots", "Ore Slots", s);
            assertEquals(1, feed.entries, "one entry");
            assertEquals(e.stakes(), feed.stakes);
            assertEquals(e.rtpByStake(), feed.rtp, "the website's numbers are the engine's");
            assertEquals(50, feed.dailyLimit);
            assertEquals(7, feed.rows.size(), "one row per line, no stake: every stake pays the same");
            for (FeedWriter.PayRow row : feed.rows) {
                LineOdds l = e.odds(1).lines().stream().filter(x -> x.line().combo().equals(row.combo()))
                        .findFirst().orElseThrow();
                assertEquals(l.chance(), row.chance(), row.combo() + ": the chance is the engine's");
                assertEquals(l.multiple(), row.pays(), row.combo() + ": pays is the multiple");
                assertNull(row.stake(), row.combo());
            }
        }
        assertEquals(89, RtpLimits.wholePercent(at90.engine().odds(1).rtp()));
        assertEquals(86, RtpLimits.wholePercent(at87.engine().odds(1).rtp()),
                "rtp 87 solves to just under 87 and players read 86, never the target");
        assertNotEquals(SlotsCopy.giveBack(at90.engine().odds(1)), SlotsCopy.giveBack(at87.engine().odds(1)));
        assertNotEquals(at90.engine().feedRows(), at87.engine().feedRows(), "the website's chances move too");
    }

    @Test
    void thePaytableTextComesFromTheEngine() {
        SlotsEngine e = OreSlotsSettings.defaults().engine();
        StakeOdds o = e.odds(5);
        LineOdds diamonds = o.line(Line.THREE_DIAMONDS);
        assertEquals("&fThree diamonds &6×40 &7· 1 in " + SlotsCopy.grouped(FeedWriter.oneIn(diamonds.chance())),
                SlotsCopy.lineName(diamonds), "symbol, ×N and 1 in N, all in the NAME for Bedrock");
        assertEquals("&fThree diamonds &6×40 &7· 1 in 1,829", SlotsCopy.lineName(diamonds));
        assertEquals("&fTwo the same &6×2 &7· 1 in 3", SlotsCopy.lineName(o.line(Line.TWO_THE_SAME)),
                "including two the same");
        assertTrue(SlotsCopy.lineLore(diamonds).contains("&75 tokens in → &6200 &7back"), SlotsCopy.lineLore(diamonds).toString());
        assertEquals("&7A spin pays something about 1 in 3 times.", SlotsCopy.hitLine(o));
        assertEquals("1-5 tokens", SlotsCopy.stakeRange(e.stakes()), "the tile's key fact");

        SlotsEngine capped = SlotsEngine.solve(OreSlotsSettings.defaults().reels(), OreSlotsSettings.defaults().pays(),
                List.of(1, 2, 5), 100, 90);
        assertEquals("&fThree wilds &6100 tokens &7· 1 in "
                        + SlotsCopy.grouped(capped.odds(5).line(Line.THREE_WILDS).oneIn()),
                SlotsCopy.lineName(capped.odds(5).line(Line.THREE_WILDS)), "a capped line shows its tokens");
    }

    @Test
    void theResultCopyNeverDressesALossAsAWin() {
        Spin won = new Spin(5, List.of(Symbol.DIAMOND, Symbol.WILD, Symbol.DIAMOND), Line.THREE_DIAMONDS, 200);
        Spin back = new Spin(5, List.of(Symbol.COAL, Symbol.COAL, Symbol.STONE), Line.TWO_THE_SAME, 5);
        Spin none = new Spin(5, List.of(Symbol.COAL, Symbol.IRON, Symbol.STONE), null, 0);
        assertEquals("&aThree diamonds! &6+200 tokens", SlotsCopy.result(won));
        assertEquals("&7Your 5 back.", SlotsCopy.result(back), "the same tokens back is neutral");
        assertEquals("&7No win this time.", SlotsCopy.result(none));
        String[] title = SlotsCopy.title(won);
        assertEquals("&eThree diamonds!", title[0], "a private title, never BIG WIN");
        assertEquals("&6+200 tokens", title[1]);
        for (String line : List.of(SlotsCopy.result(back), SlotsCopy.result(none))) {
            String lower = line.toLowerCase();
            assertFalse(lower.contains("win!") || lower.contains("won") || lower.contains("+"), line);
            assertFalse(lower.contains("try again") || lower.contains("one more") || lower.contains("almost"),
                    "no nudge after a loss: " + line);
        }
    }
}
