package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.RtpLimits;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three more slot machines' numbers, every one of them read from the engine, never guessed.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>each machine's bundled block reads as its shipped settings, with no WARN and no INFO;</li>
 *   <li><b>Nether Slots</b>: every shipped stake solves inside the 85-95 band under the shipped
 *       {@code games.max_payout}, at or just under its target of 90, the cap binding nowhere; it pays measurably
 *       rarer and bigger than Ore Slots (a spin pays about one time in 12 against one in 3; every three-the-same
 *       line pays more; the top line 125 times against 50; two the same nothing); and every dollar stake the Parlor's
 *       shipped chips can make solves in the band too;</li>
 *   <li><b>The Motherlode and Farm Slots</b>: the lines are solved for {@code rtp} less the pot's share and land at
 *       or just over the band's floor; what players read, the lines' exact return plus the share, is inside 85-95
 *       (compared in whole numbers, never doubles) and reads "about 90"; the pot's seed is exactly what the
 *       three-Wilds line pays; and three Wilds comes up about once in 594 and once in 146 spins;</li>
 *   <li>a pot machine's settings keep their promises when the owner changes them: one price, a share that never
 *       takes the lines under 85, and a Wild line never under another line.</li>
 * </ul>
 */
class SlotMachinesTest {

    /** A soak's seed: a constant, never tuned. */
    private static final long SOAK_SEED = 0x90D5_10D5L;

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = SlotMachinesTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    private static SlotSettings parse(GameSpec<SlotSettings> spec, Map<String, ?> block, List<String> warns) {
        return spec.parse().apply(new GamesConfig.Node("games." + spec.id(), block, warns::add), spec.defaults());
    }

    private static List<String> naming(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).toList();
    }

    /** {@code a/b} compared with {@code c/d}, exactly. */
    private static int compare(BigInteger a, BigInteger b, long c, long d) {
        return a.multiply(BigInteger.valueOf(d)).compareTo(b.multiply(BigInteger.valueOf(c)));
    }

    // ---- the shipped blocks ---------------------------------------------------------------------

    @Test
    void eachMachinesBundledBlockIsItsShippedSettings() throws Exception {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(shipped(), warns::add, infos::add);
        for (GameSpec<SlotSettings> spec : List.of(NetherSlots.SPEC, Motherlode.SPEC, FarmSlots.SPEC)) {
            assertEquals(spec.defaults(), parsed.settings(spec), spec.id() + ": the bundled block reads as shipped");
            assertEquals(List.of(), naming(warns, "games." + spec.id()), spec.id() + ": without a WARN");
            assertEquals(List.of(), naming(infos, spec.id()), spec.id() + ": and no INFO");
            assertTrue(parsed.readable(spec.id()));
            assertTrue(spec.defaults().enabled(), spec.id() + " ships on (the games' own switch still rules)");
            assertTrue(spec.defaults().engine().playable());
            assertEquals(List.of(), spec.defaults().engine().dropped(), spec.id() + ": no stake is dropped");
        }
        assertEquals(SlotSettings.keys(SlotTheme.NETHER, false), NetherSlots.SPEC.keys());
        assertFalse(NetherSlots.SPEC.keys().contains("feed_percent"), "a machine with no pot has no such key");
        assertEquals("feed_percent", Motherlode.SPEC.keys().get(4), "a pot machine's comes after rtp");
        assertEquals(OreSlotsSettings.KEYS.size(), NetherSlots.SPEC.keys().size(), "the same keys as Ore Slots");
        assertEquals(OreSlotsSettings.KEYS.subList(0, 4), NetherSlots.SPEC.keys().subList(0, 4),
                "enabled, stakes, daily_limit, rtp");
    }

    // ---- Nether Slots ---------------------------------------------------------------------------

    @Test
    void netherSlotsSolvesEveryShippedStakeInsideTheBand() {
        SlotSettings s = SlotSettings.nether();
        SlotsEngine e = s.engine();
        assertEquals(List.of(1, 2), s.stakes());
        assertEquals(List.of(1, 2), e.stakes(), "every shipped stake is played");
        assertEquals(250, e.cap(), "under the shipped games.max_payout: exactly 2 in × the wild line's 125");
        assertEquals(90.0, e.targetPercent());
        assertTrue(e.sameAtEveryStake(), "the cap binds nowhere, so one paytable stands for both stakes");
        for (StakeOdds o : e.odds().values()) {
            assertTrue(compare(o.rtpNum(), o.rtpDen(), 85, 100) >= 0, o.stake() + ": never under the band");
            assertTrue(compare(o.rtpNum(), o.rtpDen(), 90, 100) <= 0, o.stake() + ": at or under its target");
            assertFalse(o.aboveTarget(), o.stake() + ": 90 is reached from below");
            assertEquals(471, o.stone(), "Netherrack solved at ×100");
            assertEquals(3871, o.total());
            assertEquals(89, RtpLimits.wholePercent(o.rtp()), "players read about 89");
            assertEquals(89.9, RtpLimits.tenthPercent(o.rtp()), "the exact value is 89.95");
            assertFalse(o.capped());
        }
        assertEquals("&eGives back about 89 of every 100 tokens", SlotsCopy.giveBack(s, e.odds(1)));
        assertEquals(0, s.feedPercent(), "no pot: nothing is taken out of the lines");
        assertEquals(s.rtp(), s.linesRtp());
    }

    @Test
    void netherSlotsPaysMeasurablyRarerAndBiggerThanOreSlots() {
        StakeOdds nether = SlotSettings.nether().engine().odds(1);
        StakeOdds ore = OreSlotsSettings.defaults().engine().odds(1);
        assertEquals(3, ore.hitOneIn(), "Ore Slots pays something about 1 spin in 3");
        assertEquals(12, nether.hitOneIn(), "Nether Slots about 1 in 12");
        assertTrue(nether.hitChance() * 4 < ore.hitChance(), "more than four times rarer: " + nether.hitChance()
                + " against " + ore.hitChance());
        assertNull(nether.line(Line.TWO_THE_SAME), "two the same pays nothing");
        assertEquals(2, ore.line(Line.TWO_THE_SAME).multiple());
        for (Line l : Line.values()) {
            if (l == Line.TWO_THE_SAME) {
                continue;
            }
            assertTrue(nether.line(l).multiple() > ore.line(l).multiple(), l + " pays more: "
                    + nether.line(l).multiple() + " against " + ore.line(l).multiple());
        }
        assertEquals(125, nether.line(Line.THREE_WILDS).multiple(), "the top line, against Ore Slots' 50");
        assertEquals(6, nether.line(Line.THREE_COAL).multiple(), "the smallest pay there is, against Ore Slots' 2");
        assertEquals(7251, nether.line(Line.THREE_WILDS).oneIn(), "three Wilds: about 1 spin in 7,251");
        // the average paying spin: what comes back, over how often anything does
        double netherPay = nether.rtp() / nether.hitChance();
        double orePay = ore.rtp() / ore.hitChance();
        assertTrue(netherPay > 4 * orePay, "a paying spin pays over four times as much on average: " + netherPay
                + " against " + orePay);
        assertTrue(nether.sd() > 2 * ore.sd(), "and the spread is wider: " + nether.sd() + " against " + ore.sd());
    }

    @Test
    void netherSlotsSolvesEveryDollarStakeTheParlorsChipsMake() {
        SlotSettings s = SlotSettings.nether();
        for (int dollars = 5; dollars <= 50; dollars += 5) {
            // as SlotMachine.moneyEngine solves it: this stake alone, under the Parlor's shipped biggest payout
            SlotsEngine e = SlotsEngine.solve(s.engineReels(), s.enginePays(), List.of(dollars), 500, s.linesRtp());
            StakeOdds o = e.odds(dollars);
            assertNotNull(o, "$" + dollars + " lands in the band");
            assertTrue(o.rtp() >= RtpLimits.MIN && o.rtp() <= RtpLimits.MAX, "$" + dollars + ": " + o.rtp());
            for (LineOdds l : o.lines()) {
                assertTrue(l.payout() <= 500, "$" + dollars + " " + l.line() + " is under the biggest payout");
            }
        }
    }

    @Test
    void aSeededSoakOfNetherSlotsAgreesWithTheExactValue() {
        SlotsEngine e = SlotSettings.nether().engine();
        StakeOdds o = e.odds(2);
        int plays = 2_000_000;
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        long back = 0;
        long hits = 0;
        for (int i = 0; i < plays; i++) {
            Spin spin = e.decide(seeds.nextLong(), 2);
            back += spin.payout();
            hits += spin.payout() > 0 ? 1 : 0;
        }
        double measured = back / (2.0 * plays);
        double tolerance = 4 * o.sd() / Math.sqrt(plays);
        assertEquals(o.rtp(), measured, tolerance, "the reels give back what the engine says, within 4 SD");
        assertEquals(o.hitChance(), hits / (double) plays, 0.001, "and pay as often as it says");
    }

    // ---- the pot machines -------------------------------------------------------------------------

    private static void potMachine(SlotSettings s, int price, int stone, int total, int seedDollars, long wildsOneIn) {
        SlotsEngine e = s.engine();
        assertTrue(s.pot());
        assertEquals(List.of(price), s.stakes(), "one set price");
        assertEquals(price, s.price());
        assertEquals(List.of(price), e.stakes());
        assertEquals(5, s.feedPercent(), "5 of every 100 into the pot");
        assertEquals(85.0, s.linesRtp(), "so the lines are solved for 90 less 5");
        assertEquals(85.0, e.targetPercent());
        StakeOdds o = e.odds(price);
        assertEquals(stone, o.stone());
        assertEquals(total, o.total());
        // the lines: at the band's floor, or the smallest step over it
        assertTrue(compare(o.rtpNum(), o.rtpDen(), 85, 100) >= 0, "the lines alone are never under 85: " + o.rtp());
        assertTrue(compare(o.rtpNum(), o.rtpDen(), 851, 1000) < 0, "and within a tenth of a point of it: " + o.rtp());
        // what players read: the lines' exact return plus the pot's share, inside the band
        BigInteger num = o.rtpNum().multiply(BigInteger.valueOf(100))
                .add(o.rtpDen().multiply(BigInteger.valueOf(s.feedPercent())));
        BigInteger den = o.rtpDen().multiply(BigInteger.valueOf(100));
        assertTrue(compare(num, den, 85, 100) >= 0, "lines + the pot's share is at least 85 of every 100");
        assertTrue(compare(num, den, 95, 100) <= 0, "and at most 95");
        assertTrue(compare(num, den, 90, 100) >= 0 && compare(num, den, 901, 1000) < 0, "it is the target, 90");
        assertEquals(o.rtp() + 0.05, s.givesBack(o), 1e-12, "the number players read is exactly that sum");
        assertTrue(s.givesBack(o) >= RtpLimits.MIN && s.givesBack(o) <= RtpLimits.MAX);
        assertEquals(90, RtpLimits.wholePercent(s.givesBack(o)));
        assertEquals("&eGives back about 90 of every 100 tokens", SlotsCopy.giveBack(s, o),
                "written for tokens like every line; the screen says it in dollars");
        // the seed is the Wild line's own pay
        LineOdds wilds = o.line(Line.THREE_WILDS);
        assertEquals(s.pays().get("wild") * price, wilds.payout(), "the wild multiple times the price");
        assertEquals(seedDollars, wilds.payout(), "the pot starts at $" + seedDollars);
        assertEquals(seedDollars, e.payout(price, Symbol.WILD, Symbol.WILD, Symbol.WILD), "three Wilds pays it");
        assertEquals(Line.THREE_WILDS, e.line(price, Symbol.WILD, Symbol.WILD, Symbol.WILD),
                "and reads as the Wild line, never another");
        assertFalse(wilds.capped());
        assertEquals(wildsOneIn, wilds.oneIn(), "how often the pot is taken");
        assertEquals(1, o.line(Line.TWO_THE_SAME).multiple(), "two the same gives the price back");
        for (LineOdds l : o.lines()) {
            assertTrue(l.multiple() <= wilds.multiple(), l.line() + " is never over the Wild line");
        }
    }

    @Test
    void theMotherlodeGivesBackNinetyWithThePotsShareAndStartsAtFiftyDollars() {
        SlotSettings s = SlotSettings.motherlode();
        potMachine(s, 5, 702, 4202, 50, 594);
        assertEquals(50, s.dailyLimit());
        assertEquals(85.0, RtpLimits.tenthPercent(s.engine().odds(5).rtp()), "the lines: 85.03");
        assertEquals(2, s.engine().odds(5).hitOneIn(), "a spin pays something about 1 time in 2 (mostly the price back)");
        // 25 cents a spin, taken about once in 594 spins: on average about $148 has been added when it goes
        assertEquals(25, 5 * s.feedPercent(), "a spin adds 25 cents");
    }

    @Test
    void farmSlotsGivesBackNinetyWithItsSmallPotAndStartsAtTenDollars() {
        SlotSettings s = SlotSettings.farm();
        potMachine(s, 1, 1686, 5786, 10, 146);
        assertEquals(100, s.dailyLimit());
        assertEquals(5, 1 * s.feedPercent(), "a spin adds 5 cents");
        assertTrue(s.engine().odds(1).line(Line.THREE_WILDS).chance()
                        > 4 * SlotSettings.motherlode().engine().odds(5).line(Line.THREE_WILDS).chance(),
                "the small pot goes about four times as often as the big one");
    }

    @Test
    void thePotMachinesSolveTheSameUnderTheParlorsBiggestPayout() {
        for (SlotSettings s : List.of(SlotSettings.motherlode(), SlotSettings.farm())) {
            // as SlotMachine.moneyEngine solves the spin's engine: the price, under the Parlor's shipped $500
            SlotsEngine money = SlotsEngine.solve(s.engineReels(), s.enginePays(), s.stakes(), 500, s.linesRtp());
            assertEquals(s.engine().odds(s.price()).stone(), money.odds(s.price()).stone(), s.theme() + ": no cap binds");
            assertEquals(s.engine().odds(s.price()).rtp(), money.odds(s.price()).rtp());
        }
    }

    @Test
    void aSeededSoakOfTheMotherlodesLinesAgreesWithTheExactValue() {
        SlotsEngine e = SlotSettings.motherlode().engine();
        StakeOdds o = e.odds(5);
        int plays = 1_000_000;
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        long back = 0;
        long pots = 0;
        for (int i = 0; i < plays; i++) {
            Spin spin = e.decide(seeds.nextLong(), 5);
            back += spin.payout();
            pots += spin.line() == Line.THREE_WILDS ? 1 : 0;
        }
        assertEquals(o.rtp(), back / (5.0 * plays), 4 * o.sd() / Math.sqrt(plays), "the lines, within 4 SD");
        double chance = o.line(Line.THREE_WILDS).chance();
        assertEquals(chance * plays, pots, 5 * Math.sqrt(chance * plays), "three Wilds as often as it says");
    }

    // ---- a pot machine's settings keep their promises --------------------------------------------

    @Test
    void aPotMachineHasOnePrice() {
        List<String> warns = new ArrayList<>();
        SlotSettings s = parse(Motherlode.SPEC, Map.of("stakes", List.of(10, 5, 25)), warns);
        assertEquals(List.of(5), s.stakes(), "the smallest is the price");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.motherlode.stakes [5, 10, 25] has more than one price"), warns.get(0));
        warns.clear();
        assertEquals(List.of(1, 5), parse(NetherSlots.SPEC, Map.of("stakes", List.of(5, 1)), warns).stakes(),
                "a machine with no pot keeps its choices");
    }

    @Test
    void thePotsShareNeverTakesTheLinesUnderEightyFive() {
        List<String> warns = new ArrayList<>();
        SlotSettings s = parse(Motherlode.SPEC, Map.of("rtp", 87), warns);
        assertEquals(2, s.feedPercent(), "87 leaves room for 2");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.motherlode.feed_percent 5 leaves the lines under the 85"),
                warns.get(0));
        StakeOdds o = s.engine().odds(5);
        assertTrue(o.rtp() >= RtpLimits.MIN, "the lines stay in the band: " + o.rtp());
        assertTrue(s.givesBack(o) >= RtpLimits.MIN && s.givesBack(o) <= RtpLimits.MAX);
        assertEquals(87, RtpLimits.wholePercent(s.givesBack(o)), "and the whole is the target");

        warns.clear();
        SlotSettings top = parse(FarmSlots.SPEC, Map.of("rtp", 95, "feed_percent", 9), warns);
        assertEquals(List.of(), warns, "95 with 9 into the pot is allowed: the lines are solved for 86");
        assertEquals(9, top.feedPercent());
        StakeOdds t = top.engine().odds(1);
        assertTrue(top.givesBack(t) <= RtpLimits.MAX && top.givesBack(t) >= RtpLimits.MIN, "" + top.givesBack(t));

        // every share and target an owner could write: the lines stay in the band, and so does the whole
        for (int rtp = 85; rtp <= 95; rtp++) {
            for (int feed = 0; feed <= 12; feed++) {
                SlotSettings any = parse(Motherlode.SPEC, Map.of("rtp", rtp, "feed_percent", feed), new ArrayList<>());
                StakeOdds o2 = any.engine().odds(5);
                assertNotNull(o2, "rtp " + rtp + " feed " + feed + " still plays");
                assertTrue(o2.rtp() >= RtpLimits.MIN && o2.rtp() <= RtpLimits.MAX, rtp + "/" + feed + " lines " + o2.rtp());
                assertTrue(any.givesBack(o2) >= RtpLimits.MIN && any.givesBack(o2) <= RtpLimits.MAX,
                        rtp + "/" + feed + " gives back " + any.givesBack(o2));
                assertTrue(any.feedPercent() <= Math.min(feed, rtp - 85), rtp + "/" + feed + ": " + any.feedPercent());
            }
        }

        warns.clear();
        assertEquals(9, parse(FarmSlots.SPEC, Map.of("rtp", 95, "feed_percent", 40), warns).feedPercent());
        assertEquals(1, naming(warns, "games.farm_slots.feed_percent ").size(), "40 is clamped to 9: " + warns);

        warns.clear();
        SlotSettings none = parse(FarmSlots.SPEC, Map.of("feed_percent", 0), warns);
        assertEquals(List.of(), warns);
        assertEquals(90.0, none.linesRtp(), "no share: the lines give back the whole target, and the pot is its seed");
    }

    @Test
    void theWildLineIsNeverUnderAnotherLine() {
        List<String> warns = new ArrayList<>();
        Map<String, Object> pays = new LinkedHashMap<>();
        pays.put("wild", 4);
        SlotSettings s = parse(Motherlode.SPEC, Map.of("pays", pays), warns);
        assertEquals(10, s.pays().get("wild"), "raised to the biggest other line (netherite's 10)");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.motherlode.pays.wild 4 is under another line's 10"), warns.get(0));
        assertEquals(Line.THREE_WILDS, s.engine().line(5, Symbol.WILD, Symbol.WILD, Symbol.WILD));
        warns.clear();
        Map<String, Object> low = new LinkedHashMap<>();
        low.put("wild", 4);
        assertEquals(4, parse(NetherSlots.SPEC, Map.of("pays", low), warns).pays().get("wild"),
                "a machine with no pot may pay its Wild line as it likes");
        assertEquals(List.of(), naming(warns, "games.nether_slots.pays.wild"));
    }

    @Test
    void aPotMachinesFeedEntryCountsThePotsShare() {
        SlotSettings s = SlotSettings.motherlode();
        List<FeedWriter.PayRow> rows = s.engine().feedRows(s.theme());
        assertEquals(7, rows.size(), "one row a line");
        assertEquals("3 wild", rows.get(0).combo());
        assertTrue(rows.stream().anyMatch(r -> r.combo().equals("3 netherite")), rows.toString());
        assertTrue(SlotsCopy.feedRules(s).endsWith("Every spin adds to the pot, and three Wilds takes it all."),
                SlotsCopy.feedRules(s));
        assertEquals("&6The Motherlode &7— gives back about 90 of every $100, the pot's share counted · $5 a"
                + " spin · 50 plays a day", SlotsCopy.potOddsLine("The Motherlode", s, s.engine()));
        List<String> detail = SlotsCopy.potOddsDetail(s, s.engine(), 21225, 5000);
        assertTrue(detail.get(0).contains("lines &f85.0% &7+ pot &f5%"), detail.toString());
        assertTrue(detail.get(0).contains("Deepslate 702 of 4202"), detail.toString());
        assertTrue(detail.get(0).contains("the pot 1 in 594"), detail.toString());
        assertEquals("&7  the pot now: &f$212.25 &7(it starts at $50)", detail.get(1));
    }
}
