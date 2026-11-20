package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.DeepMineEngine.Dig;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deep Mine, the slot machine with three rows that all count: every number it publishes, read from its engine and
 * checked against a count made here from nothing but the reels' weights and what each triple pays.
 *
 * <ul>
 *   <li>the bundled block reads as the shipped settings, with no WARN and no INFO;</li>
 *   <li>every shipped stake's whole spin gives back, exactly, what one row gives back, that is inside the 85-95 band
 *       (compared in whole numbers), and is the number the screen, the odds lines and the website all read;</li>
 *   <li>a row may pay a third of what the spin may pay, so no spin decided from any seed pays over its cap: tokens at
 *       every shipped stake, dollars at every amount the Parlor's chips make, and a server with a small
 *       {@code max_payout};</li>
 *   <li>the same seed always gives the same spin, each row is the one-line engine's own spin, and the stored round
 *       reads back as the spin it was;</li>
 *   <li>the chance at least one row pays is exactly {@code 1 - (1 - p)³};</li>
 *   <li>a long seeded run agrees with the exact values;</li>
 *   <li>the wording never calls getting less back, or the same back, a win.</li>
 * </ul>
 */
class DeepMineTest {

    /** A soak's seed: a constant, never tuned. */
    private static final long SOAK_SEED = 0xDEE9_3113L;
    private static final Symbol[] SYMBOLS = Symbol.values();

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = DeepMineTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    private static DeepMineSettings parse(Map<String, ?> block, List<String> warns) {
        return DeepMine.SPEC.parse().apply(new GamesConfig.Node("games.deep_mine", block, warns::add),
                DeepMine.SPEC.defaults());
    }

    private static List<String> naming(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).toList();
    }

    /**
     * What one row gives back per token on it, counted here from scratch: every one of the 343 triples, its weight
     * (the three reels' weights multiplied) times what it pays, over the stake times all the ways the reels can land.
     */
    private static Frac countedReturn(SlotsEngine line, int stake) {
        BigInteger paid = BigInteger.ZERO;
        for (Symbol a : SYMBOLS) {
            for (Symbol b : SYMBOLS) {
                for (Symbol c : SYMBOLS) {
                    paid = paid.add(weight(line, stake, a, b, c)
                            .multiply(BigInteger.valueOf(line.payout(stake, a, b, c))));
                }
            }
        }
        return new Frac(paid, ways(line, stake).multiply(BigInteger.valueOf(stake)));
    }

    /** The chance one row pays anything, counted the same way. */
    private static Frac countedHit(SlotsEngine line, int stake) {
        BigInteger hits = BigInteger.ZERO;
        for (Symbol a : SYMBOLS) {
            for (Symbol b : SYMBOLS) {
                for (Symbol c : SYMBOLS) {
                    if (line.payout(stake, a, b, c) > 0) {
                        hits = hits.add(weight(line, stake, a, b, c));
                    }
                }
            }
        }
        return new Frac(hits, ways(line, stake));
    }

    private static BigInteger weight(SlotsEngine line, int stake, Symbol a, Symbol b, Symbol c) {
        return BigInteger.valueOf(line.weight(stake, a)).multiply(BigInteger.valueOf(line.weight(stake, b)))
                .multiply(BigInteger.valueOf(line.weight(stake, c)));
    }

    private static BigInteger ways(SlotsEngine line, int stake) {
        long total = 0;
        for (Symbol s : SYMBOLS) {
            total += line.weight(stake, s);
        }
        assertEquals(line.odds(stake).total(), total, "the weights add up to the reel's total");
        return BigInteger.valueOf(total).pow(3);
    }

    // ---- the shipped block ------------------------------------------------------------------------

    @Test
    void theBundledBlockIsTheShippedSettings() throws Exception {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(shipped(), warns::add, infos::add);
        DeepMineSettings s = parsed.settings(DeepMine.SPEC);
        assertEquals(DeepMine.SPEC.defaults(), s, "the bundled block reads as shipped");
        assertEquals(List.of(), naming(warns, "games.deep_mine"), "without a WARN");
        assertEquals(List.of(), naming(infos, "deep_mine"), "and no INFO");
        assertTrue(parsed.readable("deep_mine"));
        assertTrue(s.enabled(), "it ships on (the games' own switch still rules)");
        assertEquals("deep_mine", DeepMine.SPEC.id());
        assertEquals(OreSlotsSettings.KEYS, DeepMine.SPEC.keys(), "the Ore Slots shape, key for key");
        assertEquals(List.of(1, 2, 5), s.stakes(), "what goes on each row");
        assertEquals(List.of(1, 2, 5), s.offered(), "every shipped stake is offered");
        assertEquals(List.of(), s.bank().dropped());
        assertEquals(50, s.dailyLimit());
        assertEquals(90.0, s.rtp());
        assertEquals(Map.of("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 4), s.reels());
        assertEquals(Map.of("two", 1, "coal", 3, "copper", 4, "iron", 6, "gold", 10, "diamond", 15, "wild", 16),
                s.pays());
        assertEquals(SlotTheme.DEEP, s.theme());
        assertEquals("Tuff", s.theme().blank());
        assertTrue(s.engine().playable());
        assertEquals(s.bank().engine(1), s.engine(), "the table's own engine is its smallest stake's");
        assertNull(s.engine(3), "3 a row is not a stake it has");
    }

    // ---- the return -------------------------------------------------------------------------------

    @Test
    void everyShippedStakeGivesBackExactlyWhatOneRowDoesInsideTheBand() {
        DeepMineSettings s = DeepMineSettings.defaults();
        for (int perLine : s.stakes()) {
            DeepMineEngine spin = s.spin(perLine);
            SlotsEngine line = spin.line();
            StakeOdds o = spin.odds();
            assertEquals(3 * perLine, spin.total(), "a spin is three rows");
            assertEquals(List.of(perLine), line.stakes(), "this stake's own engine");
            assertEquals(83, line.cap(), "a row may pay a third of the shipped max_payout of 250, rounded down");
            assertEquals(656, o.stone(), "Tuff solved at ×100");
            assertEquals(4056, o.total());
            assertFalse(o.capped(), perLine + ": the cap cuts no line (the top line is 16 × 5 = 80)");
            assertFalse(o.aboveTarget());

            // the published return is exactly the count made here
            Frac back = spin.givesBack();
            assertTrue(back.same(countedReturn(line, perLine)), perLine + ": " + back + " is the counted value");
            assertTrue(back.same(new Frac(o.rtpNum(), o.rtpDen())), "and the engine's own fraction");
            // three rows, each with the same on it: three times as much comes back for three times as much in
            Frac whole = new Frac(back.num().multiply(BigInteger.valueOf(3)), back.den().multiply(BigInteger.valueOf(3)));
            assertTrue(whole.same(back), "so the whole spin gives back exactly what a row does");
            // inside the band, compared in whole numbers
            assertTrue(back.compareTo(Frac.MIN) >= 0, perLine + ": never under 85");
            assertTrue(back.compareTo(Frac.MAX) <= 0, perLine + ": never over 95");
            assertTrue(back.compareTo(Frac.percent(90)) <= 0, perLine + ": at or under its target of 90");
            assertTrue(back.compareTo(Frac.of(899, 1000)) >= 0, perLine + ": and within a tenth of a point of it");
            assertTrue(back.inBand());
            assertEquals(o.rtp(), back.value(), 1e-15);
            assertEquals(89, RtpLimits.wholePercent(back.value()), "players read about 89");
            assertEquals(89.9, RtpLimits.tenthPercent(back.value()), "the exact value is 89.95");
            assertEquals(back.value(), s.givesBack(o), 1e-15, "what the screen reads is that value");
            assertEquals("&eGives back about 89 of every 100 tokens", SlotsCopy.giveBack(s, o));
            assertTrue(spin.most() <= 250, perLine + ": three rows of the top line are " + spin.most());
        }
        assertEquals(240, s.spin(5).most(), "5 a row: 3 × 80, under the shipped max_payout");
        assertEquals(89.9, RtpLimits.tenthPercent(DeepMine.lowest(s)));
        // three the same on a row always gives back at least the whole spin
        for (LineOdds l : s.spin(2).odds().lines()) {
            if (l.line() != Line.TWO_THE_SAME) {
                assertTrue(l.payout() >= 6, l.line() + " pays " + l.payout() + " on a row of 2: the spin was 6");
            }
        }
        assertEquals(2, s.spin(2).odds().line(Line.TWO_THE_SAME).payout(), "two the same gives that row's 2 back");
    }

    @Test
    void theChanceAtLeastOneRowPaysIsExact() {
        DeepMineSettings s = DeepMineSettings.defaults();
        for (int perLine : s.stakes()) {
            DeepMineEngine spin = s.spin(perLine);
            Frac p = countedHit(spin.line(), perLine);
            assertTrue(SlotOdds.pays(spin.line(), perLine).same(p), "one row's chance is the counted one");
            assertEquals(spin.odds().hitChance(), p.value(), 1e-15, "and the engine's own");
            // 1 - (1 - p)^3, worked out here in whole numbers
            BigInteger miss = p.den().subtract(p.num());
            Frac any = new Frac(p.den().pow(3).subtract(miss.pow(3)), p.den().pow(3));
            assertTrue(spin.anyRowPays().same(any), perLine + ": exactly 1 - (1 - p)^3");
            assertEquals(1 - Math.pow(1 - p.value(), 3), spin.anyRowPays().value(), 1e-12);
            assertEquals(0.5676, p.value(), 0.0001, "a row pays something about 57 times in 100");
            assertEquals(0.9191, spin.anyRowPays().value(), 0.0001, "at least one of three about 92 times in 100");
            assertEquals("&7At least one row pays about 92 spins in 100.", SlotsCopy.deepHitLine(spin.anyRowPays()));
        }
        assertTrue(SlotOdds.atLeastOnce(Frac.of(1, 2), 3).same(Frac.of(7, 8)), "1 - (1/2)^3");
        assertTrue(SlotOdds.atLeastOnce(Frac.of(0, 5), 3).same(Frac.ZERO));
        assertTrue(SlotOdds.atLeastOnce(Frac.of(5, 5), 3).same(Frac.of(1, 1)));
    }

    // ---- the cap ----------------------------------------------------------------------------------

    /** Every row is the line's own spin, the payout is their sum, and nothing is over {@code cap}. */
    private static void sweep(DeepMineEngine spin, int cap, int seeds, String what) {
        SplittableRandom random = new SplittableRandom(SOAK_SEED ^ spin.total());
        int most = 0;
        for (int i = 0; i < seeds; i++) {
            long seed = random.nextLong();
            Dig dig = spin.decide(seed, spin.total());
            int sum = 0;
            for (Spin row : dig.rows()) {
                assertTrue(row.payout() <= spin.line().cap(), what + ": a row is never over its third");
                sum += row.payout();
            }
            assertEquals(sum, dig.payout(), what + ": the spin pays its rows added up, nothing else");
            assertEquals(dig.payout(), spin.payout(dig));
            assertTrue(dig.payout() <= cap, what + ": seed " + seed + " paid " + dig.payout() + " over " + cap);
            most = Math.max(most, dig.payout());
        }
        assertTrue(spin.most() <= cap, what + ": even three top lines (" + spin.most() + ") are within " + cap);
        assertTrue(most <= spin.most());
        assertTrue(3 * spin.line().cap() <= cap, what + ": three rows' caps are within the spin's");
    }

    @Test
    void noSpinEverPaysOverItsCapInTokens() {
        DeepMineSettings s = DeepMineSettings.defaults();
        int max = GamesConfig.Common.defaults().maxPayout();
        for (int perLine : s.stakes()) {
            // as ChanceRounds holds a token round: max_payout, never under any stake of the game or the spin itself
            List<Integer> stakes = new ArrayList<>(s.stakes());
            stakes.add(DeepMineEngine.total(perLine));
            int held = GamesConfig.Common.defaults().maxPayoutFor(stakes);
            assertEquals(250, held);
            assertEquals(held, DeepMineSettings.roundCap(max, perLine));
            sweep(s.spin(perLine), held, 200_000, "tokens, " + perLine + " a row");
        }
    }

    @Test
    void noSpinEverPaysOverItsCapInDollars() {
        DeepMineSettings s = DeepMineSettings.defaults();
        Parlor.Rules rules = Parlor.Rules.defaults();
        assertEquals(500, rules.maxPayout());
        for (int perLine : List.of(5, 10, 15)) { // every amount on a row whose three rows the Parlor's chips make
            SlotsEngine line = s.moneyLine(perLine, rules);
            StakeOdds o = line.odds(perLine);
            assertNotNull(o, "$" + perLine + " a row lands in the band");
            assertEquals(166, line.cap(), "a row may pay a third of the Parlor's $500, rounded down");
            assertTrue(new Frac(o.rtpNum(), o.rtpDen()).inBand(), "$" + perLine + ": " + o.rtp());
            assertTrue(new Frac(o.rtpNum(), o.rtpDen()).same(countedReturn(line, perLine)));
            int held = rules.capFor(DeepMineEngine.total(perLine)); // as ChanceRounds holds a money round
            sweep(new DeepMineEngine(line, perLine), held, 100_000, "dollars, $" + perLine + " a row");
        }
        assertFalse(s.moneyLine(5, rules).odds(5).capped(), "$5 a row: the top line is $80");
        assertFalse(s.moneyLine(10, rules).odds(10).capped(), "$10 a row: $160, under a row's $166");
        assertTrue(s.moneyLine(15, rules).odds(15).capped(), "$15 a row: $240 is cut to a row's $166");
        // a smaller biggest payout, set by command: a row's share follows it
        Parlor.Rules small = new Parlor.Rules(true, true, 250, 50, 100, List.of(5, 10, 25), 5);
        SlotsEngine line = s.moneyLine(5, small);
        assertEquals(33, line.cap());
        if (line.odds(5) != null) {
            sweep(new DeepMineEngine(line, 5), small.capFor(15), 100_000, "dollars under a $100 payout");
        }
    }

    @Test
    void aSmallMaxPayoutStillHoldsEverySpin() {
        DeepMineSettings d = DeepMineSettings.defaults();
        for (int max : List.of(1, 3, 10, 40, 100)) {
            DeepMineSettings s = DeepMineSettings.of(true, d.stakes(), 50, 90, d.reels(), d.pays(), max);
            for (int perLine : s.offered()) {
                // the round is held to max_payout, or to the spin itself where that is more
                int held = Math.max(max, DeepMineEngine.total(perLine));
                assertTrue(s.bank().engine(perLine).cap() >= perLine, "a row's most is never under the row itself");
                assertTrue(new Frac(s.bank().odds(perLine).rtpNum(), s.bank().odds(perLine).rtpDen()).inBand(),
                        "max " + max + ", " + perLine + " a row: offered only inside the band");
                sweep(s.spin(perLine), held, 20_000, "max_payout " + max + ", " + perLine + " a row");
            }
            for (SlotBank.Dropped drop : s.bank().dropped()) {
                assertNull(s.engine(drop.stake()), "a dropped stake has no engine");
                assertNull(s.spin(drop.stake()));
            }
            assertEquals(s.stakes().size(), s.offered().size() + s.bank().dropped().size());
        }
    }

    @Test
    void aStakeThatCantLandInTheBandIsDroppedWithAWarn() {
        List<String> warns = new ArrayList<>();
        // 400 a row: the spin may pay 1,200 (never under itself), a row 400, so every line pays the row back and no more
        DeepMineSettings s = parse(Map.of("stakes", List.of(1, 400)), warns);
        assertEquals(List.of(1, 400), s.stakes(), "as configured");
        assertEquals(List.of(1), s.offered(), "only what lands in the band is offered");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.deep_mine.stakes 400 can't give back 85-95 of every 100 tokens"),
                warns.get(0));
        assertTrue(warns.get(0).endsWith("Deep Mine doesn't take 400 tokens"), warns.get(0));
        assertTrue(s.engine().playable());

        warns.clear();
        Map<String, Object> pays = new LinkedHashMap<>();
        for (String k : SlotTheme.DEEP.payKeys()) {
            pays.put(k, 0);
        }
        DeepMineSettings none = parse(Map.of("pays", pays), warns);
        assertFalse(none.engine().playable(), "nothing pays: it is closed");
        assertEquals(List.of(), none.offered());
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("can't give back 85-95 of every 100 tokens at any stake (no line pays"
                + " anything)"), warns.get(0));
        assertEquals(List.of(), SlotsCopy.deepOddsDetail(none, none.bank()).stream()
                .filter(l -> !l.startsWith("&c")).toList(), "only the dropped stakes are listed");
        assertEquals("&6Deep Mine &7— closed", SlotsCopy.oddsLine("Deep Mine", DeepMine.lowest(none), 50));
    }

    // ---- the draw ---------------------------------------------------------------------------------

    @Test
    void theSameSeedAlwaysGivesTheSameSpinAndEachRowIsTheLinesOwn() {
        DeepMineEngine spin = DeepMineSettings.defaults().spin(2);
        SplittableRandom random = new SplittableRandom(SOAK_SEED);
        int differ = 0;
        for (int i = 0; i < 5_000; i++) {
            long seed = random.nextLong();
            Dig a = spin.decide(seed, 6);
            assertEquals(a, spin.decide(seed, 6), "decided from the seed and nothing else");
            assertEquals(3, a.rows().size());
            assertEquals(2, a.perLine());
            assertEquals(6, a.stake());
            // the seed gives each row a seed of its own, top row first; the row is the one-line engine's spin for it
            SplittableRandom rows = new SplittableRandom(seed);
            for (int r = 0; r < 3; r++) {
                assertEquals(spin.line().decide(rows.nextLong(), 2), a.rows().get(r), "row " + r);
            }
            differ += a.equals(spin.decide(seed + 1, 6)) ? 0 : 1;
        }
        assertTrue(differ > 4_900, "another seed is another spin: " + differ);
        assertThrows(IllegalArgumentException.class, () -> spin.decide(1L, 2), "a spin is three rows: 6, never 2");
        assertThrows(IllegalArgumentException.class, () -> new DeepMineEngine(spin.line(), 5),
                "an engine solved for 2 a row draws no row of 5");
    }

    @Test
    void theStoredRoundReadsBackAsTheSpinItWas() {
        DeepMineSettings s = DeepMineSettings.defaults();
        SplittableRandom random = new SplittableRandom(SOAK_SEED);
        for (int perLine : s.stakes()) {
            DeepMineEngine spin = s.spin(perLine);
            for (int i = 0; i < 3_000; i++) {
                Dig dig = spin.decide(random.nextLong(), spin.total());
                String data = spin.data(dig);
                assertEquals(dig, DeepMineEngine.parse(data), data);
                assertTrue(data.startsWith("v=1;lines=3;stake=" + perLine + ";in=" + spin.total() + ";row1="), data);
                assertTrue(data.endsWith(";payout=" + dig.payout() + ";stone=656;total=4056"),
                        "what it paid, and the reels it was drawn from: " + data);
            }
        }
        Dig one = new Dig(2, List.of(new Spin(2, List.of(Symbol.COAL, Symbol.WILD, Symbol.STONE), Line.TWO_THE_SAME, 2),
                new Spin(2, List.of(Symbol.STONE, Symbol.GOLD, Symbol.IRON), null, 0),
                new Spin(2, List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), Line.THREE_WILDS, 32)), 34);
        assertEquals("v=1;lines=3;stake=2;in=6;row1=coal,wild,stone:two:2;row2=stone,gold,iron:none:0;"
                + "row3=wild,wild,wild:wild:32;payout=34;stone=656;total=4056", s.spin(2).data(one));
        assertThrows(IllegalArgumentException.class, () -> DeepMineEngine.parse("v=1;stake=5;reels=coal,coal,coal"),
                "an Ore Slots round is not a Deep Mine round");
    }

    @Test
    void aLongSeededRunAgreesWithTheExactValues() {
        DeepMineEngine spin = DeepMineSettings.defaults().spin(2);
        StakeOdds o = spin.odds();
        int spins = 600_000;
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        long back = 0;
        long any = 0;
        long rows = 0;
        for (int i = 0; i < spins; i++) {
            Dig dig = spin.decide(seeds.nextLong(), 6);
            back += dig.payout();
            any += dig.paidRows() > 0 ? 1 : 0;
            rows += dig.paidRows();
        }
        // three rows drawn apart: the spread of the whole spin's return is one row's over the square root of three
        double tolerance = 4 * o.sd() / Math.sqrt(3.0 * spins);
        assertEquals(spin.givesBack().value(), back / (6.0 * spins), tolerance, "the return, within 4 SD ("
                + tolerance + ")");
        assertTrue(tolerance < 0.005, "which is under half a point");
        assertEquals(spin.anyRowPays().value(), any / (double) spins, 0.002, "at least one row pays as often as it"
                + " says, within 0.2 of a point");
        assertEquals(o.hitChance(), rows / (3.0 * spins), 0.002, "and each row as often as a line does");
    }

    // ---- the words --------------------------------------------------------------------------------

    private static Spin row(int perLine, Line line, int payout, Symbol... reels) {
        return new Spin(perLine, List.of(reels), line, payout);
    }

    @Test
    void theResultNeverCallsLessOrTheSameBackAWin() {
        SlotTheme t = SlotTheme.DEEP;
        Spin none = row(2, null, 0, Symbol.STONE, Symbol.COAL, Symbol.IRON);
        Spin pair = row(2, Line.TWO_THE_SAME, 2, Symbol.COAL, Symbol.COAL, Symbol.IRON);
        Spin coal = row(2, Line.THREE_COAL, 6, Symbol.COAL, Symbol.WILD, Symbol.COAL);
        Spin gold = row(2, Line.THREE_GOLD, 20, Symbol.GOLD, Symbol.GOLD, Symbol.GOLD);

        Dig nothing = new Dig(2, List.of(none, none, none), 0);
        assertEquals("&7No win this time.", SlotsCopy.deepResult(t, nothing));
        assertEquals(List.of(), SlotsCopy.deepRows(t, nothing));

        Dig less = new Dig(2, List.of(pair, none, pair), 4);
        assertFalse(less.win());
        assertEquals("&74 back.", SlotsCopy.deepResult(t, less), "4 back for 6 in is never a win");
        assertEquals(List.of("&7Top row: Two the same → &62", "&7Bottom row: Two the same → &62"),
                SlotsCopy.deepRows(t, less), "each row that paid, and what it gave back");
        assertEquals(2, less.paidRows());

        Dig same = new Dig(2, List.of(none, coal, none), 6);
        assertFalse(same.win(), "three coal on one row gives the whole spin back: not a win");
        assertEquals("&7Your 6 back.", SlotsCopy.deepResult(t, same));
        assertEquals(List.of("&7Middle row: Three coal → &66"), SlotsCopy.deepRows(t, same));

        Dig oneRow = new Dig(2, List.of(none, none, gold), 20);
        assertTrue(oneRow.win());
        assertEquals("&aThree gold! &6+20 tokens", SlotsCopy.deepResult(t, oneRow));

        Dig more = new Dig(2, List.of(pair, coal, gold), 28);
        assertEquals("&a3 rows paid! &6+28 tokens", SlotsCopy.deepResult(t, more), "one spin, more than one line");
        assertEquals(List.of("&7Top row: Two the same → &62", "&7Middle row: Three coal → &66",
                "&7Bottom row: Three gold → &620"), SlotsCopy.deepRows(t, more));
        assertEquals("&8Top row: nothing", SlotsCopy.deepRow(t, 0, none));

        for (Dig dig : List.of(nothing, less, same)) {
            String all = SlotsCopy.deepResult(t, dig) + " " + String.join(" ", SlotsCopy.deepRows(t, dig));
            assertFalse(all.toLowerCase().contains("won") || all.contains("+") || all.contains("&a"),
                    "nothing here reads as a win: " + all);
        }
        for (Dig dig : List.of(less, same, oneRow, more)) {
            for (String line : SlotsCopy.deepRows(t, dig)) {
                assertFalse(line.toLowerCase().contains("win") || line.toLowerCase().contains("won"),
                        "a row only ever says what it gave back: " + line);
            }
        }
    }

    @Test
    void theScreensWordsSayThreeRowsAndWhatTheSpinCosts() {
        DeepMineSettings s = DeepMineSettings.defaults();
        assertEquals(List.of("Three rows of deepslate ores. Every row counts.",
                "You put the same on each row, so a spin is three times that.",
                "On a row, three the same pays the most. Two the same gives that row's back.",
                "A Wild stands in for any ore. Tuff never pays.",
                "Every spin is decided before the reels move."), SlotsCopy.deepRules(s));
        assertEquals("&a&lSpin &7- &63 lines × 2 = 6 tokens in", SlotsCopy.deepSpin(2, false));
        assertEquals("&a&lSpin &7- &63 lines × 1 = 3 tokens in", SlotsCopy.deepSpin(1, false));
        assertEquals("&a&lSpin &7- &63 lines × $5 = $15 in", SlotsCopy.deepSpin(5, true));
        assertEquals("2 a row · 6 tokens a spin", SlotsCopy.deepStake(2, false));
        assertEquals("$10 a row · $30 a spin", SlotsCopy.deepStake(10, true));
        assertEquals("3 rows of $20 is $60. The most is $50.", SlotsCopy.deepOverMost(20, 50));
        LineOdds gold = s.spin(5).odds().line(Line.THREE_GOLD);
        assertEquals("&fThree gold &6×10 &7· 1 in 149", SlotsCopy.lineName(SlotTheme.DEEP, gold));
        assertEquals(List.of("&7Gold, Gold, Gold &8(a Wild counts)", "&75 tokens on a row → &650 &7back",
                "&7Comes up on a row about 1 in 149 spins."), SlotsCopy.deepLineLore(SlotTheme.DEEP, gold));
        LineOdds two = s.spin(5).odds().line(Line.TWO_THE_SAME);
        assertEquals("&75 tokens on a row → that row's 5 back", SlotsCopy.deepLineLore(SlotTheme.DEEP, two).get(1),
                "a row's own stake back is never written as a win");
        assertEquals("&6Deep Mine &7— gives back about 89 of every 100 tokens · 50 plays a day",
                SlotsCopy.oddsLine("Deep Mine", DeepMine.lowest(s), s.dailyLimit()));
        List<String> detail = SlotsCopy.deepOddsDetail(s, s.bank());
        assertEquals(3, detail.size(), "a line for each stake");
        assertEquals("&7  6 tokens in (2 a row): &f89.9% &7· Tuff 656 of 4056 · a row pays 1 in 1.8 · any row 1 in"
                + " 1.09 · a spin's most 96", detail.get(1));
    }

    @Test
    void theWebsitesEntryIsTheSameNumbers() {
        DeepMineSettings s = DeepMineSettings.defaults();
        List<Object[]> wrote = new ArrayList<>();
        DeepMine.write(new Listening() {
            @Override
            public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                               Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
                wrote.add(new Object[]{id, name, stakes, rtpByStake, dailyLimit, paytable, rules, extra});
            }
        }, "deep_mine", "Deep Mine", s);
        assertEquals(1, wrote.size());
        Object[] w = wrote.get(0);
        assertEquals("deep_mine", w[0]);
        assertEquals(List.of(3, 6, 15), w[2], "the stakes are what a whole spin costs");
        @SuppressWarnings("unchecked")
        Map<Integer, Double> back = (Map<Integer, Double>) w[3];
        for (int perLine : s.offered()) {
            assertEquals(s.spin(perLine).givesBack().value(), back.get(3 * perLine), 1e-15, "the exact return");
        }
        assertEquals(50, w[4]);
        @SuppressWarnings("unchecked")
        List<FeedWriter.PayRow> rows = (List<FeedWriter.PayRow>) w[5];
        assertEquals(21, rows.size(), "seven lines for each of three stakes");
        FeedWriter.PayRow top = rows.stream().filter(r -> r.stake() == 15 && r.combo().equals("3 wild")).findFirst()
                .orElseThrow();
        assertEquals(80, top.pays(), "what ONE row pays at that spin's stake, in tokens");
        assertEquals(s.spin(5).odds().line(Line.THREE_WILDS).chance(), top.chance());
        assertTrue(((String) w[6]).startsWith("Three rows of three reels, and every row is a line"), (String) w[6]);
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) w[7];
        assertEquals(3, extra.get("lines"));
        assertEquals(List.of(1, 2, 5), extra.get("perLine"));
        assertEquals(Map.of("3", s.spin(1).anyRowPays().value(), "6", s.spin(2).anyRowPays().value(), "15",
                s.spin(5).anyRowPays().value()), extra.get("anyRowPays"));
    }

    /** A feed writer that hears only what a test overrides. */
    private abstract static class Listening implements FeedWriter {

        @Override
        public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                            String holder) {
        }

        @Override
        public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                           String holder) {
        }

        @Override
        public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                         String holder) {
        }

        @Override
        public boolean showNames() {
            return false;
        }
    }

    @Test
    void anotherSpinIsNeverTheSameObjectByChance() {
        // the settings compare by what they were solved from, so a reload with the same numbers changes nothing
        assertEquals(DeepMineSettings.defaults(), DeepMineSettings.defaults());
        DeepMineSettings other = DeepMineSettings.of(true, List.of(1, 2, 5), 50, 91,
                DeepMineSettings.defaults().reels(), DeepMineSettings.defaults().pays(), 250);
        assertNotEquals(DeepMineSettings.defaults(), other);
        assertTrue(other.spin(1).givesBack().compareTo(DeepMineSettings.defaults().spin(1).givesBack()) > 0,
                "a higher target gives back more");
        assertTrue(other.spin(1).givesBack().compareTo(Frac.percent(91)) <= 0);
    }
}
