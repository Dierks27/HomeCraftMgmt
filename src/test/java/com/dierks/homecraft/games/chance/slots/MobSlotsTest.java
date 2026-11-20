package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.FreeSpinsEngine.Round;
import com.dierks.homecraft.games.chance.slots.MobSlotsSettings.Solved;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mob Slots, the slot machine with free spins: every number it publishes, read from its engine and checked against
 * counts made here from nothing but the reels' weights and what each triple pays.
 *
 * <p>The Creeper is the engine's Wild: it stands in for any drop, and three of them on the line pay their line and
 * give the free spins. A round gives back {@code L × (1 + p × N)} ({@code L} one spin's return, {@code p} the chance
 * of three Creepers, {@code N} the free spins), and THAT is what is published.
 *
 * <ul>
 *   <li>the bundled block reads as the shipped settings, with no WARN and no INFO;</li>
 *   <li>every shipped stake's whole round is inside the 85-95 band (compared in whole numbers) at or just under its
 *       target, and the number the screen, the odds lines and the website read is that exact value, never one spin's;</li>
 *   <li>the formula is the count: every first spin with every free spin after it, enumerated exactly on a small table
 *       (triple by triple) and on the shipped one;</li>
 *   <li>a long seeded run agrees with it within a stated tolerance;</li>
 *   <li>one spin may pay the round's cap over {@code N + 1}, so no round decided from any seed pays over its cap:
 *       tokens, dollars, a small {@code max_payout}, and reels that land the Creepers nearly every spin;</li>
 *   <li>a free spin never gives more free spins; the same seed always gives the same round; the stored round reads
 *       back as it was;</li>
 *   <li>{@code free_spins: 0} is a plain machine; a target the one-line engine can't reach lands just above it,
 *       inside the band, and says so;</li>
 *   <li>the wording never calls getting less back, or the same back, a win.</li>
 * </ul>
 */
class MobSlotsTest {

    /** A soak's seed: a constant, never tuned. */
    private static final long SOAK_SEED = 0x5B0B_C4EEL;
    private static final Symbol[] SYMBOLS = Symbol.values();

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = MobSlotsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    /** {@code games.mob_slots} read from {@code block}, every other key as shipped. */
    private static MobSlotsSettings parse(Map<String, ?> block, List<String> warns, List<String> infos) {
        Map<String, Object> games = new LinkedHashMap<>();
        games.put("mob_slots", block);
        return GamesConfig.parse((Object) games, warns::add, infos::add).settings(MobSlots.SPEC);
    }

    private static List<String> naming(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).toList();
    }

    private static BigInteger weight(SlotsEngine spin, int stake, Symbol a, Symbol b, Symbol c) {
        return BigInteger.valueOf(spin.weight(stake, a)).multiply(BigInteger.valueOf(spin.weight(stake, b)))
                .multiply(BigInteger.valueOf(spin.weight(stake, c)));
    }

    private static BigInteger ways(SlotsEngine spin, int stake) {
        long total = 0;
        for (Symbol s : SYMBOLS) {
            total += spin.weight(stake, s);
        }
        assertEquals(spin.odds(stake).total(), total, "the weights add up to the reel's total");
        return BigInteger.valueOf(total).pow(3);
    }

    /**
     * What a round gives back per token in, counted here triple by triple: every first spin, and after a first spin
     * of three Creepers every way {@code n} free spins can land ({@code n} of 0, 1 or 2 here: 343, 343² or 343³
     * rounds), each round's weight times all it pays. A free spin's own three Creepers start nothing more.
     */
    private static Frac countedRound(SlotsEngine spin, int stake, int n) {
        BigInteger ways = ways(spin, stake);
        BigInteger paid = BigInteger.ZERO;
        for (Symbol a : SYMBOLS) {
            for (Symbol b : SYMBOLS) {
                for (Symbol c : SYMBOLS) {
                    BigInteger w = weight(spin, stake, a, b, c);
                    BigInteger base = BigInteger.valueOf(spin.payout(stake, a, b, c));
                    boolean creepers = a == Symbol.WILD && b == Symbol.WILD && c == Symbol.WILD;
                    if (!creepers || n == 0) {
                        paid = paid.add(w.multiply(base).multiply(ways.pow(n))); // one round, at the full denominator
                    } else {
                        paid = paid.add(w.multiply(freeSpins(spin, stake, n, base, ways)));
                    }
                }
            }
        }
        return new Frac(paid, ways.pow(n + 1).multiply(BigInteger.valueOf(stake)));
    }

    /** The weighted sum of {@code soFar} plus what {@code n} free spins pay, over every way they can land. */
    private static BigInteger freeSpins(SlotsEngine spin, int stake, int n, BigInteger soFar, BigInteger ways) {
        if (n == 0) {
            return soFar;
        }
        BigInteger sum = BigInteger.ZERO;
        for (Symbol a : SYMBOLS) {
            for (Symbol b : SYMBOLS) {
                for (Symbol c : SYMBOLS) {
                    BigInteger w = weight(spin, stake, a, b, c);
                    if (w.signum() == 0) {
                        continue;
                    }
                    BigInteger then = soFar.add(BigInteger.valueOf(spin.payout(stake, a, b, c)));
                    sum = sum.add(w.multiply(freeSpins(spin, stake, n - 1, then, ways)));
                }
            }
        }
        return sum;
    }

    /**
     * The same count for any number of free spins, by what a spin pays rather than triple by triple: the exact share
     * of the reel's ways that pay each amount, then every first spin with every run of {@code n} such amounts.
     */
    private static Frac countedByAmounts(SlotsEngine spin, int stake, int n) {
        BigInteger ways = ways(spin, stake);
        TreeMap<Integer, BigInteger> amounts = new TreeMap<>();
        for (Symbol a : SYMBOLS) {
            for (Symbol b : SYMBOLS) {
                for (Symbol c : SYMBOLS) {
                    amounts.merge(spin.payout(stake, a, b, c), weight(spin, stake, a, b, c), BigInteger::add);
                }
            }
        }
        BigInteger wilds = weight(spin, stake, Symbol.WILD, Symbol.WILD, Symbol.WILD);
        int creeperPay = spin.payout(stake, Symbol.WILD, Symbol.WILD, Symbol.WILD);
        // every first spin that is not three Creepers
        BigInteger paid = BigInteger.ZERO;
        for (Map.Entry<Integer, BigInteger> e : amounts.entrySet()) {
            BigInteger w = e.getKey() == creeperPay ? e.getValue().subtract(wilds) : e.getValue();
            paid = paid.add(w.multiply(BigInteger.valueOf(e.getKey())).multiply(ways.pow(n)));
        }
        // three Creepers, then every run of n amounts
        paid = paid.add(wilds.multiply(runs(amounts, n, BigInteger.valueOf(creeperPay))));
        return new Frac(paid, ways.pow(n + 1).multiply(BigInteger.valueOf(stake)));
    }

    private static BigInteger runs(TreeMap<Integer, BigInteger> amounts, int n, BigInteger soFar) {
        if (n == 0) {
            return soFar;
        }
        BigInteger sum = BigInteger.ZERO;
        for (Map.Entry<Integer, BigInteger> e : amounts.entrySet()) {
            sum = sum.add(e.getValue().multiply(runs(amounts, n - 1, soFar.add(BigInteger.valueOf(e.getKey())))));
        }
        return sum;
    }

    // ---- the shipped block ------------------------------------------------------------------------

    @Test
    void theBundledBlockIsTheShippedSettings() throws Exception {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(shipped(), warns::add, infos::add);
        MobSlotsSettings s = parsed.settings(MobSlots.SPEC);
        assertEquals(MobSlots.SPEC.defaults(), s, "the bundled block reads as shipped");
        assertEquals(List.of(), naming(warns, "games.mob_slots"), "without a WARN");
        assertEquals(List.of(), naming(infos, "mob_slots"), "and no INFO");
        assertTrue(parsed.readable("mob_slots"));
        assertTrue(s.enabled());
        assertEquals("mob_slots", MobSlots.SPEC.id());
        assertEquals(List.of("enabled", "stakes", "daily_limit", "rtp", "free_spins", "reels.rotten_flesh",
                "reels.bone", "reels.string", "reels.ender_pearl", "reels.blaze_rod", "reels.creeper", "pays.two",
                "pays.rotten_flesh", "pays.bone", "pays.string", "pays.ender_pearl", "pays.blaze_rod",
                "pays.creeper"), MobSlots.SPEC.keys(), "the Ore Slots shape with the mobs' names, and free_spins");
        assertEquals(List.of(1, 2), s.stakes());
        assertEquals(List.of(1, 2), s.offered(), "every shipped stake is offered");
        assertEquals(List.of(), s.bank().dropped());
        assertEquals(5, s.freeSpins());
        assertEquals(50, s.dailyLimit());
        assertEquals(90.0, s.rtp());
        assertEquals(SlotTheme.MOB, s.theme());
        assertEquals("Creeper", s.theme().wild(), "the Creeper is this machine's Wild");
        assertEquals("Cobweb", s.theme().blank());
        assertEquals(Map.of("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 10), s.engineReels(),
                "the Creeper's weight reaches the engine as its Wild's");
        assertEquals(Map.of("two", 1, "coal", 2, "copper", 3, "iron", 4, "gold", 6, "diamond", 10, "wild", 20),
                s.enginePays());
    }

    // ---- the return -------------------------------------------------------------------------------

    @Test
    void everyShippedStakesWholeRoundIsInsideTheBandAndIsWhatIsPublished() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        Frac target = Frac.percent(90);
        for (int stake : s.stakes()) {
            FreeSpinsEngine round = s.round(stake);
            SlotsEngine spin = round.spin();
            StakeOdds o = round.odds();
            assertEquals(List.of(stake), spin.stakes(), "this stake's own engine");
            assertEquals(41, spin.cap(), "one spin may pay the shipped max_payout of 250 over six spins, rounded down");
            assertEquals(1668, o.stone(), "Cobweb solved at ×100");
            assertEquals(5668, o.total());
            assertEquals(1000, spin.weight(stake, Symbol.WILD), "the Creeper's weight of 10, at ×100");
            assertFalse(o.capped(), stake + ": the cap cuts no line (the top line is 20 × 2 = 40)");

            // p: the Creeper's weight over the reel's total, cubed
            Frac p = round.creepers();
            assertTrue(p.same(new Frac(BigInteger.valueOf(1000).pow(3), BigInteger.valueOf(5668).pow(3))));
            assertEquals(182, FeedWriter.oneIn(p.value()), "three Creepers about one spin in 182");
            assertEquals(o.line(Line.THREE_WILDS).chance(), p.value(), 1e-15, "which is the Creeper line's own chance");

            // L x (1 + p x N), from the engine's own whole numbers
            Frac line = new Frac(o.rtpNum(), o.rtpDen());
            Frac whole = line.times(new Frac(p.den().add(p.num().multiply(BigInteger.valueOf(5))), p.den()));
            Frac back = round.givesBack();
            assertTrue(back.same(whole), stake + ": the round gives back exactly L × (1 + 5p)");
            assertTrue(back.same(countedByAmounts(spin, stake, 5)), stake + ": and that is the count of every first"
                    + " spin with every five free spins after it");

            // inside the band, at or just under the target: compared in whole numbers
            assertTrue(back.compareTo(Frac.MIN) >= 0 && back.compareTo(Frac.MAX) <= 0, stake + ": inside 85-95");
            assertTrue(back.compareTo(target) <= 0, stake + ": at or under its target of 90");
            assertTrue(back.compareTo(Frac.of(899, 1000)) >= 0, stake + ": and within a tenth of a point of it");
            assertEquals(89, RtpLimits.wholePercent(back.value()), "players read about 89");
            assertEquals(89.9, RtpLimits.tenthPercent(back.value()), "the exact value is 89.97");
            // one spin on its own gives back less: it is never the number shown
            assertEquals(87.5, RtpLimits.tenthPercent(o.rtp()), "one spin: 87.57");
            assertTrue(line.compareTo(back) < 0);
            assertEquals(back.value(), s.givesBack(o), 1e-15, "what the shared screen reads is the whole round");
            assertEquals("&eGives back about 89 of every 100 tokens", SlotsCopy.giveBack(s, o));
            // one stone lighter would be over the target: this is the most it can give back at or under 90
            SlotsEngine lighter = lighter(s, stake, o.stone());
            assertTrue(SlotOdds.withFreeSpins(lighter, stake, 5).compareTo(target) > 0,
                    "the next step up is over 90: " + SlotOdds.withFreeSpins(lighter, stake, 5).value());
            assertTrue(round.most() <= 250, stake + ": a first spin and five free spins of the top line are "
                    + round.most());
        }
        assertEquals(240, s.round(2).most(), "2 in: six spins of 40, under the shipped max_payout");
        assertEquals(89.9, RtpLimits.tenthPercent(MobSlots.lowest(s)));
    }

    /** The one-line engine with one step less Cobweb than {@code stone}, found by raising the engine's target. */
    private static SlotsEngine lighter(MobSlotsSettings s, int stake, int stone) {
        for (int hundredths = 8500; hundredths <= 9500; hundredths++) {
            SlotsEngine e = SlotsEngine.solve(s.engineReels(), s.enginePays(), List.of(stake), 41,
                    hundredths / 100.0);
            if (e.odds(stake) != null && e.odds(stake).stone() == stone - 1) {
                return e;
            }
        }
        throw new AssertionError("no target solves Cobweb " + (stone - 1));
    }

    @Test
    void theFormulaIsTheCountTripleByTripleOnASmallTable() {
        // a small table with plenty of Creepers, so the free spins matter: three Creepers about one spin in 90
        Map<String, Integer> reels = SlotsTables.map("coal", 3, "copper", 2, "iron", 0, "gold", 0, "diamond", 1,
                "wild", 3);
        Map<String, Integer> pays = SlotsTables.map("two", 1, "coal", 2, "copper", 3, "iron", 0, "gold", 0,
                "diamond", 5, "wild", 8);
        for (int n : List.of(0, 1, 2)) {
            Solved solved = MobSlotsSettings.solve(reels, pays, 2, 250, 90, n);
            assertTrue(solved.offered(), n + " free spins: " + solved.nearest());
            SlotsEngine spin = solved.engine();
            Frac counted = countedRound(spin, 2, n); // 343, 343² or 343³ rounds, each one counted
            assertTrue(solved.givesBack().same(counted), n + " free spins: the published " + solved.givesBack().value()
                    + " is the counted " + counted.value());
            assertTrue(SlotOdds.withFreeSpins(spin, 2, n).same(counted));
            assertTrue(countedByAmounts(spin, 2, n).same(counted), "and the count by amounts agrees with it");
            assertTrue(counted.inBand(), n + ": " + counted.value());
            assertTrue(counted.compareTo(Frac.percent(90)) <= 0);
            assertTrue(counted.compareTo(Frac.of(895, 1000)) >= 0, "close under the target: " + counted.value());
            Frac p = SlotOdds.threeWilds(spin, 2);
            assertTrue(p.value() > 0.01, "the Creepers come often here: " + p.value());
            if (n > 0) {
                assertTrue(SlotOdds.line(spin, 2).compareTo(counted) < 0, "one spin alone gives back less");
            } else {
                assertTrue(SlotOdds.line(spin, 2).same(counted), "no free spins: the round is the spin");
            }
        }
    }

    @Test
    void aLongSeededRunAgreesWithTheExactValue() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        FreeSpinsEngine round = s.round(2);
        int rounds = 3_000_000;
        SplittableRandom seeds = new SplittableRandom(SOAK_SEED);
        double sum = 0;
        double squares = 0;
        long bonus = 0;
        long freeSpins = 0;
        for (int i = 0; i < rounds; i++) {
            Round r = round.decide(seeds.nextLong(), 2);
            double back = r.payout() / 2.0;
            sum += back;
            squares += back * back;
            if (r.bonus()) {
                bonus++;
                freeSpins += r.free().size();
            }
        }
        double mean = sum / rounds;
        double sd = Math.sqrt(squares / rounds - mean * mean);
        double tolerance = 4 * sd / Math.sqrt(rounds); // 4 standard errors of the run's own mean
        assertTrue(tolerance < 0.006, "the tolerance is about half a point: " + tolerance);
        assertEquals(round.givesBack().value(), mean, tolerance, "the whole round, free spins and all");
        assertTrue(Math.abs(round.odds().rtp() - mean) > tolerance, "and it is NOT one spin's return ("
                + round.odds().rtp() + "): the free spins are really played and paid");
        double p = round.creepers().value();
        assertEquals(p * rounds, bonus, 5 * Math.sqrt(p * rounds), "three Creepers as often as it says");
        assertEquals(5 * bonus, freeSpins, "and five free spins every time, never more");
    }

    // ---- the cap ----------------------------------------------------------------------------------

    /** Every decided round pays its spins added up, and never over {@code cap}; returns how many had free spins. */
    private static int sweep(FreeSpinsEngine round, int stake, int cap, int seeds, String what) {
        SplittableRandom random = new SplittableRandom(SOAK_SEED ^ stake);
        int bonus = 0;
        for (int i = 0; i < seeds; i++) {
            long seed = random.nextLong();
            Round r = round.decide(seed, stake);
            int sum = r.base().payout();
            assertTrue(r.base().payout() <= round.spin().cap(), what + ": a spin is never over its share");
            for (Spin free : r.free()) {
                assertTrue(free.payout() <= round.spin().cap(), what + ": nor a free spin");
                sum += free.payout();
            }
            assertEquals(sum, r.payout(), what + ": the round pays its spins added up, nothing else");
            assertEquals(r.payout(), round.payout(r));
            assertTrue(r.payout() <= cap, what + ": seed " + seed + " paid " + r.payout() + " over " + cap);
            assertEquals(FreeSpinsEngine.threeCreepers(r.base()) && round.freeSpins() > 0, r.bonus(),
                    what + ": free spins follow three Creepers on the first spin, and only that");
            assertTrue(r.free().isEmpty() || r.free().size() == round.freeSpins(), what + ": all of them, never more");
            bonus += r.bonus() ? 1 : 0;
        }
        assertTrue(round.most() <= cap, what + ": even the top line on every spin (" + round.most() + ") is within "
                + cap);
        assertTrue((round.freeSpins() + 1) * round.spin().cap() <= cap, what + ": the spins' shares are within it");
        return bonus;
    }

    @Test
    void noRoundEverPaysOverItsCapInTokens() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        for (int stake : s.stakes()) {
            // as ChanceRounds holds a token round: max_payout, never under any stake of the game
            List<Integer> stakes = new ArrayList<>(s.stakes());
            stakes.add(stake);
            int held = GamesConfig.Common.defaults().maxPayoutFor(stakes);
            assertEquals(250, held);
            assertEquals(held, MobSlotsSettings.roundCap(250, stake));
            int bonus = sweep(s.round(stake), stake, held, 400_000, "tokens, " + stake + " in");
            assertTrue(bonus > 1_500, "and the sweep met plenty of rounds with free spins: " + bonus);
        }
    }

    @Test
    void noRoundEverPaysOverItsCapInDollarsAndEveryChipStakeIsInTheBand() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        Parlor.Rules rules = Parlor.Rules.defaults();
        for (int dollars = 5; dollars <= 50; dollars += 5) { // every stake the Parlor's shipped chips make
            Solved solved = s.moneySpin(dollars, rules);
            assertTrue(solved.offered(), "$" + dollars + " lands in the band: " + solved.nearest());
            assertEquals(83, solved.spinCap(), "one spin may pay the Parlor's $500 over six spins, rounded down");
            assertEquals(83, solved.engine().cap());
            Frac back = solved.givesBack();
            assertTrue(back.inBand(), "$" + dollars + ": " + back.value());
            assertTrue(back.compareTo(Frac.percent(90)) <= 0, "$" + dollars + " is at or under the target");
            assertTrue(back.compareTo(Frac.of(899, 1000)) >= 0, "$" + dollars + " is close under it: " + back.value());
            assertFalse(solved.aboveTarget());
            assertTrue(back.same(countedByAmounts(solved.engine(), dollars, 5)), "$" + dollars + ": the published"
                    + " value is the count, with the cap's cut lines in it");
            assertEquals(back.value(), s.givesBack(solved.engine().odds(dollars)), 1e-15, "and what the screen reads");
            int held = rules.capFor(dollars); // as ChanceRounds holds a money round
            int bonus = sweep(new FreeSpinsEngine(solved.engine(), dollars, 5), dollars, held, 60_000,
                    "dollars, $" + dollars);
            assertTrue(bonus > 150, "$" + dollars + ": rounds with free spins were among them: " + bonus);
        }
        assertTrue(s.moneySpin(5, rules).engine().odds(5).capped(), "$5: the Creeper line's $100 is cut to $83");
        // a smaller biggest payout, set by command: a spin's share follows it, and what can't land in the band isn't
        // played
        Parlor.Rules small = new Parlor.Rules(true, true, 250, 50, 60, List.of(5, 10, 25), 5);
        for (int dollars = 5; dollars <= 50; dollars += 5) {
            Solved solved = s.moneySpin(dollars, small);
            assertEquals(small.capFor(dollars) / 6, solved.spinCap());
            if (solved.offered()) {
                assertTrue(solved.givesBack().inBand());
                sweep(new FreeSpinsEngine(solved.engine(), dollars, 5), dollars, small.capFor(dollars), 20_000,
                        "dollars under a $60 payout, $" + dollars);
            } else {
                assertNull(solved.givesBack());
                assertFalse(solved.engine().playable(), "a stake that is not offered has no engine to play");
            }
        }
        assertFalse(s.moneySpin(50, small).offered(), "$50 with at most $10 a spin can't give back 85");
    }

    @Test
    void reelsThatLandTheCreepersNearlyEverySpinStillHoldTheCap() {
        // nothing on the reels but Creepers and Cobweb: every paying spin is three Creepers (about one spin in 47),
        // so every paying spin starts free spins, and a free spin lands three Creepers as often
        Map<String, Integer> reels = new LinkedHashMap<>();
        Map<String, Integer> pays = new LinkedHashMap<>();
        for (String k : SlotTheme.MOB.reelKeys()) {
            reels.put(k, k.equals("creeper") ? 5 : 0);
            pays.put(k, k.equals("creeper") ? 40 : 0);
        }
        pays.put("two", 0);
        for (int n : List.of(1, 5)) {
            MobSlotsSettings s = MobSlotsSettings.of(true, List.of(1, 2), 50, 90, n, reels, pays, 250);
            for (int stake : s.offered()) {
                FreeSpinsEngine round = s.round(stake);
                assertEquals(250 / (n + 1), round.spin().cap());
                assertTrue(round.givesBack().inBand(), n + " free spins, " + stake + " in: " + round.givesBack().value());
                assertTrue(round.givesBack().same(countedByAmounts(round.spin(), stake, n)));
                int bonus = sweep(round, stake, 250, 150_000, n + " free spins, " + stake + " in");
                assertTrue(bonus > 2_000, "free spins came often: " + bonus);
            }
            assertTrue(s.offered().contains(1), n + " free spins: 1 in is offered");
        }
        // a free spin that lands three Creepers pays its line and starts nothing more
        MobSlotsSettings s = MobSlotsSettings.of(true, List.of(1), 50, 90, 5, reels, pays, 250);
        FreeSpinsEngine round = s.round(1);
        SplittableRandom random = new SplittableRandom(SOAK_SEED);
        int again = 0;
        for (int i = 0; i < 400_000 && again < 50; i++) {
            Round r = round.decide(random.nextLong(), 1);
            for (Spin free : r.free()) {
                if (FreeSpinsEngine.threeCreepers(free)) {
                    again++;
                    assertEquals(5, r.free().size(), "still five free spins, not ten");
                    assertEquals(40, free.payout(), "its own line is paid");
                }
            }
        }
        assertEquals(50, again, "free spins that landed three Creepers were met");
    }

    @Test
    void aSmallMaxPayoutStillHoldsEveryRound() {
        MobSlotsSettings d = MobSlotsSettings.defaults();
        for (int max : List.of(1, 5, 6, 12, 40, 100)) {
            MobSlotsSettings s = MobSlotsSettings.of(true, d.stakes(), 50, 90, 5, d.reels(), d.pays(), max);
            for (int stake : s.offered()) {
                int held = Math.max(max, stake); // the round is held to max_payout, or to its own stake where more
                assertTrue(s.round(stake).givesBack().inBand(), "max " + max + ": offered only inside the band");
                sweep(s.round(stake), stake, held, 30_000, "max_payout " + max + ", " + stake + " in");
            }
            for (SlotBank.Dropped drop : s.bank().dropped()) {
                assertNull(s.engine(drop.stake()));
                assertNull(s.round(drop.stake()));
            }
            assertEquals(s.stakes().size(), s.offered().size() + s.bank().dropped().size());
        }
        // under six tokens a round there is not one whole token for each of six spins: nothing can be offered
        MobSlotsSettings tiny = MobSlotsSettings.of(true, d.stakes(), 50, 90, 5, d.reels(), d.pays(), 5);
        assertEquals(List.of(), tiny.offered());
        assertFalse(tiny.engine().playable(), "so the machine is closed");
        assertEquals(0, FreeSpinsEngine.spinCap(5, 5));
        assertEquals(41, FreeSpinsEngine.spinCap(250, 5));
        assertEquals(250, FreeSpinsEngine.spinCap(250, 0), "no free spins: the spin has the whole cap");
    }

    // ---- the draw ---------------------------------------------------------------------------------

    @Test
    void theSameSeedAlwaysGivesTheSameRoundAndEachSpinIsTheEnginesOwn() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        FreeSpinsEngine round = s.round(2);
        SplittableRandom random = new SplittableRandom(SOAK_SEED);
        int bonus = 0;
        for (int i = 0; i < 60_000; i++) {
            long seed = random.nextLong();
            Round a = round.decide(seed, 2);
            assertEquals(a, round.decide(seed, 2), "decided from the seed and nothing else");
            // the seed gives every spin a seed of its own; each is the one-line engine's spin for it
            SplittableRandom seeds = new SplittableRandom(seed);
            assertEquals(round.spin().decide(seeds.nextLong(), 2), a.base());
            for (Spin free : a.free()) {
                assertEquals(round.spin().decide(seeds.nextLong(), 2), free);
            }
            if (a.bonus()) {
                bonus++;
                assertEquals(List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), a.base().reels());
                assertEquals(Line.THREE_WILDS, a.base().line(), "three Creepers pay their own line");
                assertEquals(40, a.base().payout(), "20 times the 2 in");
                assertEquals(5, a.free().size());
                assertTrue(a.win(), "the line alone is a win");
                assertEquals(40, a.backAfter(0));
                assertEquals(a.payout(), a.backAfter(5));
                assertEquals(40 + a.free().get(0).payout(), a.backAfter(1));
            } else {
                assertEquals(a.base().payout(), a.payout());
            }
        }
        assertTrue(bonus > 200, "rounds with free spins were among them: " + bonus);
        assertThrows(IllegalArgumentException.class, () -> round.decide(1L, 1), "this round is for 2 in");
        assertThrows(IllegalArgumentException.class, () -> new FreeSpinsEngine(round.spin(), 1, 5));
    }

    @Test
    void theStoredRoundReadsBackAsItWas() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        SplittableRandom random = new SplittableRandom(SOAK_SEED);
        int bonus = 0;
        for (int stake : s.stakes()) {
            FreeSpinsEngine round = s.round(stake);
            for (int i = 0; i < 40_000; i++) {
                Round r = round.decide(random.nextLong(), stake);
                String data = round.data(r);
                assertEquals(r, FreeSpinsEngine.parse(data), data);
                assertTrue(data.startsWith("v=1;stake=" + stake + ";free=" + r.free().size() + ";spin="), data);
                assertTrue(data.endsWith(";payout=" + r.payout() + ";stone=1668;total=5668"), data);
                bonus += r.bonus() ? 1 : 0;
            }
        }
        assertTrue(bonus > 200, "rounds with free spins were stored and read back too: " + bonus);
        Spin creepers = new Spin(2, List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), Line.THREE_WILDS, 40);
        Spin pair = new Spin(2, List.of(Symbol.COAL, Symbol.WILD, Symbol.STONE), Line.TWO_THE_SAME, 2);
        Spin none = new Spin(2, List.of(Symbol.STONE, Symbol.GOLD, Symbol.IRON), null, 0);
        Round r = new Round(2, creepers, List.of(pair, none, none, creepers, none), 84);
        assertEquals("v=1;stake=2;free=5;spin=wild,wild,wild:wild:40;free1=coal,wild,stone:two:2;"
                + "free2=stone,gold,iron:none:0;free3=stone,gold,iron:none:0;free4=wild,wild,wild:wild:40;"
                + "free5=stone,gold,iron:none:0;payout=84;stone=1668;total=5668", s.round(2).data(r));
        assertEquals("v=1;stake=2;free=0;spin=stone,gold,iron:none:0;payout=0;stone=1668;total=5668",
                s.round(2).data(new Round(2, none, List.of(), 0)));
        assertThrows(IllegalArgumentException.class, () -> FreeSpinsEngine.parse("v=1;stake=5;reels=coal,coal,coal"));
    }

    // ---- what an owner can set --------------------------------------------------------------------

    @Test
    void noFreeSpinsMakesItAPlainMachine() {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        MobSlotsSettings s = parse(Map.of("free_spins", 0), warns, infos);
        assertEquals(List.of(), warns);
        assertEquals(0, s.freeSpins());
        for (int stake : s.offered()) {
            FreeSpinsEngine round = s.round(stake);
            assertEquals(250, round.spin().cap(), "the one spin has the whole cap");
            assertTrue(round.givesBack().same(SlotOdds.line(round.spin(), stake)), "the round is the spin");
            assertTrue(round.givesBack().compareTo(Frac.percent(90)) <= 0 && round.givesBack().inBand());
            assertEquals(89.9, RtpLimits.tenthPercent(round.givesBack().value()));
            SplittableRandom random = new SplittableRandom(SOAK_SEED);
            for (int i = 0; i < 100_000; i++) {
                assertFalse(round.decide(random.nextLong(), stake).bonus(), "three Creepers give nothing more");
            }
        }
        assertEquals(List.of(1, 2), s.offered());
        assertEquals(List.of("Three reels of mob drops. Match them on the line.",
                "Three the same pays the most. Two the same gives yours back.",
                "A Creeper stands in for any drop. Cobweb never pays.",
                "Every spin is decided before the reels move."), SlotsCopy.rules(s), "and its rules say nothing of them");
    }

    @Test
    void everyNumberOfFreeSpinsAndEveryTargetStaysInsideTheBand() {
        MobSlotsSettings d = MobSlotsSettings.defaults();
        for (int n = 0; n <= 10; n++) {
            for (int rtp = 85; rtp <= 95; rtp++) {
                MobSlotsSettings s = MobSlotsSettings.of(true, d.stakes(), 50, rtp, n, d.reels(), d.pays(), 250);
                Frac want = Frac.percent(rtp);
                for (int stake : s.offered()) {
                    FreeSpinsEngine round = s.round(stake);
                    Frac back = round.givesBack();
                    String what = n + " free spins, target " + rtp + ", " + stake + " in: " + back.value();
                    assertTrue(back.inBand(), what);
                    assertTrue(SlotOdds.line(round.spin(), stake).inBand(), what + " (one spin too)");
                    assertTrue(round.most() <= 250, what);
                    assertEquals((n + 1) * round.spin().cap() <= 250, true, what);
                    if (back.compareTo(want) > 0) {
                        // only because one spin can't be solved under 85: the round is then the least there is
                        assertTrue(SlotOdds.line(round.spin(), stake).compareTo(Frac.of(8510, 10000)) < 0,
                                what + ": above its target only with one spin at the band's floor");
                    } else {
                        assertTrue(back.compareTo(Frac.of(rtp * 10L - 2, 1000)) >= 0, what + ": close under it");
                    }
                }
                assertEquals(d.stakes().size(), s.offered().size() + s.bank().dropped().size());
            }
        }
    }

    @Test
    void aTargetOneSpinCantReachLandsJustAboveItAndSaysSo() {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        MobSlotsSettings s = parse(Map.of("rtp", 85), warns, infos);
        assertEquals(List.of(), warns, "it is not a mistake in the file");
        assertEquals(List.of(1, 2), s.offered(), "it still plays");
        for (int stake : s.offered()) {
            FreeSpinsEngine round = s.round(stake);
            Frac back = round.givesBack();
            assertTrue(back.compareTo(Frac.percent(85)) > 0, "over the target: " + back.value());
            assertTrue(back.inBand(), "and inside the band");
            assertTrue(SlotOdds.line(round.spin(), stake).compareTo(Frac.MIN) >= 0, "one spin is at the band's floor");
            assertEquals(87.2, RtpLimits.tenthPercent(back.value()), "85 × (1 + 5p): what it really reaches");
            assertEquals("&eGives back about 87 of every 100 tokens", SlotsCopy.giveBack(s, round.odds()),
                    "and what players read is that, not the 85 it was asked for");
        }
        assertEquals(2, naming(infos, "mob_slots stake ").size(), "one INFO line a stake: " + infos);
        assertTrue(infos.get(0).startsWith("mob_slots stake 1: 87.2% (target 85 not reachable"), infos.get(0));
        assertTrue(SlotsCopy.bonusOddsDetail(s, s.bank()).get(0).endsWith("&8(target 85 not reachable, next above)"),
                SlotsCopy.bonusOddsDetail(s, s.bank()).get(0));
        // 88 and up are reached from below
        infos.clear();
        MobSlotsSettings ok = parse(Map.of("rtp", 88), warns, infos);
        assertEquals(List.of(), infos);
        assertTrue(ok.round(1).givesBack().compareTo(Frac.percent(88)) <= 0);
    }

    @Test
    void theOwnersNumbersAreKeptInsideTheirLimits() {
        List<String> warns = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        assertEquals(10, parse(Map.of("free_spins", 40), warns, infos).freeSpins());
        assertEquals(1, naming(warns, "games.mob_slots.free_spins 40 is above the highest allowed (10)").size(),
                warns.toString());
        warns.clear();
        assertEquals(0, parse(Map.of("free_spins", -3), warns, infos).freeSpins());
        assertEquals(1, naming(warns, "games.mob_slots.free_spins -3 cannot be negative").size(), warns.toString());

        // the Creeper line is never under another line: three Creepers would then pay as that other line
        warns.clear();
        Map<String, Object> pays = new LinkedHashMap<>();
        pays.put("creeper", 4);
        MobSlotsSettings s = parse(Map.of("pays", pays), warns, infos);
        assertEquals(10, s.pays().get("creeper"), "raised to the biggest other line (the blaze rods' 10)");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.mob_slots.pays.creeper 4 is under another line's 10"), warns.get(0));
        for (int stake : s.offered()) {
            assertEquals(Line.THREE_WILDS, s.engine(stake).line(stake, Symbol.WILD, Symbol.WILD, Symbol.WILD));
            assertTrue(s.round(stake).givesBack().inBand());
        }
        warns.clear();
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("creeper", 4);
        assertEquals(4, parse(Map.of("pays", plain, "free_spins", 0), warns, infos).pays().get("creeper"),
                "with no free spins the Creeper line may pay as it likes");
        assertEquals(List.of(), warns);

        // a stake whose round can't land in the band is not offered
        warns.clear();
        MobSlotsSettings big = parse(Map.of("stakes", List.of(1, 300)), warns, infos);
        assertEquals(List.of(1), big.offered(), "300 in: a spin may pay 50, a sixth of the round's 300");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.mob_slots.stakes 300 can't give back 85-95 of every 100 tokens"),
                warns.get(0));
        assertTrue(warns.get(0).endsWith("Mob Slots doesn't take 300 tokens"), warns.get(0));
    }

    // ---- the words --------------------------------------------------------------------------------

    @Test
    void theResultNeverCallsLessOrTheSameBackAWin() {
        SlotTheme t = SlotTheme.MOB;
        Spin creepers = new Spin(2, List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), Line.THREE_WILDS, 40);
        Spin pair = new Spin(2, List.of(Symbol.COAL, Symbol.WILD, Symbol.STONE), Line.TWO_THE_SAME, 2);
        Spin none = new Spin(2, List.of(Symbol.STONE, Symbol.GOLD, Symbol.IRON), null, 0);
        Spin bones = new Spin(2, List.of(Symbol.COPPER, Symbol.COPPER, Symbol.WILD), Line.THREE_COPPER, 6);

        assertEquals("&7No win this time.", SlotsCopy.bonusResult(t, new Round(2, none, List.of(), 0)));
        assertEquals("&7Your 2 back.", SlotsCopy.bonusResult(t, new Round(2, pair, List.of(), 2)),
                "the same back is never a win");
        assertEquals("&aThree bones! &6+6 tokens", SlotsCopy.bonusResult(t, new Round(2, bones, List.of(), 6)));
        Round bonus = new Round(2, creepers, List.of(pair, none, bones, none, none), 48);
        assertTrue(bonus.bonus() && bonus.win());
        assertEquals("&aThree creepers and 5 free spins! &6+48 tokens", SlotsCopy.bonusResult(t, bonus),
                "one total for the whole round");
        assertEquals("&aThree creepers! &e5 free spins!", SlotsCopy.bonusStart(t, 5));
        assertEquals("&eFree spin 1 of 5 &7· 42 tokens back so far", SlotsCopy.freeSpinLine(1, 5, bonus.backAfter(1)));
        assertEquals("&eFree spin 3 of 5 &7· 48 tokens back so far", SlotsCopy.freeSpinLine(3, 5, bonus.backAfter(3)));
        assertEquals("&e5 free spins!", SlotsCopy.bonusTitle(bonus)[0]);
        assertEquals("&6+48 tokens", SlotsCopy.bonusTitle(bonus)[1]);

        // a table where the Creeper line only gives the stake back: free spins that pay nothing are not a win
        Spin flat = new Spin(2, List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), Line.THREE_WILDS, 2);
        Round same = new Round(2, flat, List.of(none, none), 2);
        assertFalse(same.win());
        assertEquals("&7Three creepers and 2 free spins. Your 2 back.", SlotsCopy.bonusResult(t, same));
        Spin low = new Spin(2, List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), Line.THREE_WILDS, 1);
        assertEquals("&7Three creepers and 1 free spin. 1 back.",
                SlotsCopy.bonusResult(t, new Round(2, low, List.of(none), 1)), "nor is getting less back");
        Spin nothing = new Spin(2, List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), null, 0);
        assertEquals("&7Three creepers and 1 free spin. No win this time.",
                SlotsCopy.bonusResult(t, new Round(2, nothing, List.of(none), 0)));
        for (Round r : List.of(same, new Round(2, low, List.of(none), 1), new Round(2, pair, List.of(), 2))) {
            String line = SlotsCopy.bonusResult(t, r);
            assertFalse(line.contains("&a") || line.contains("+") || line.toLowerCase().contains("won"), line);
        }
        assertFalse(SlotsCopy.freeSpinLine(2, 5, 3).toLowerCase().contains("win"), "the running total is only a number");
    }

    @Test
    void theScreensWordsSayWhatTheCreepersDo() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        assertEquals(List.of("Three reels of mob drops. Match them on the line.",
                "Three the same pays the most. Two the same gives yours back.",
                "A Creeper stands in for any drop. Cobweb never pays.",
                "Three Creepers also give 5 free spins.",
                "Every spin is decided before the reels move."), SlotsCopy.rules(s));
        StakeOdds o = s.round(2).odds();
        LineOdds creepers = o.line(Line.THREE_WILDS);
        assertEquals("&fThree creepers &6×20 &7+ &a5 free spins &7· 1 in 182",
                SlotsCopy.bonusLineName(SlotTheme.MOB, creepers, 5));
        assertEquals(List.of("&7Creeper, Creeper, Creeper", "&72 tokens in → &640 &7back",
                "&7And 5 free spins at the same stake, played at once.",
                "&7A free spin pays like any spin, and gives no more.",
                "&7Comes up about 1 in 182 spins."), SlotsCopy.bonusLineLore(SlotTheme.MOB, creepers, 5));
        assertEquals("&7Three Creepers give 5 free spins about 1 spin in 182.",
                SlotsCopy.bonusHitLine(SlotTheme.MOB, 5, s.round(2).creepers()));
        LineOdds bones = o.line(Line.THREE_COPPER);
        assertEquals("&fThree bones &6×3 &7· 1 in 38", SlotsCopy.lineName(SlotTheme.MOB, bones));
        assertEquals("&7Bone, Bone, Bone &8(a Creeper counts)", SlotsCopy.lineLore(SlotTheme.MOB, bones).get(0));
        assertEquals("&6Mob Slots &7— gives back about 89 of every 100 tokens · 50 plays a day",
                SlotsCopy.oddsLine("Mob Slots", MobSlots.lowest(s), s.dailyLimit()));
        List<String> detail = SlotsCopy.bonusOddsDetail(s, s.bank());
        assertEquals(2, detail.size(), "a line for each stake");
        assertEquals("&7  2 tokens in: &f89.9% &7· one spin 87.5% · Cobweb 1668 of 5668 · pays 1 in 1.8 · 5 free"
                + " spins 1 in 182 · a round's most 240", detail.get(1), "the whole round first, one spin apart");
        assertTrue(SlotsCopy.feedRules(s).endsWith("Three Creepers on the line also give 5 free spins, played at once"
                + " at the same stake; a free spin gives no more."), SlotsCopy.feedRules(s));
        assertTrue(SlotsCopy.feedRules(s).contains("(a Creeper stands in for any drop)"), SlotsCopy.feedRules(s));
    }

    @Test
    void theWebsitesEntryIsTheWholeRoundsNumbers() {
        MobSlotsSettings s = MobSlotsSettings.defaults();
        List<Object[]> wrote = new ArrayList<>();
        MobSlots.write(new FeedWriter() {
            @Override
            public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                               Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
                wrote.add(new Object[]{id, stakes, rtpByStake, dailyLimit, paytable, rules, extra});
            }

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
        }, "mob_slots", "Mob Slots", s);
        assertEquals(1, wrote.size());
        Object[] w = wrote.get(0);
        assertEquals(List.of(1, 2), w[1]);
        @SuppressWarnings("unchecked")
        Map<Integer, Double> back = (Map<Integer, Double>) w[2];
        for (int stake : s.offered()) {
            assertEquals(s.round(stake).givesBack().value(), back.get(stake), 1e-15, "the whole round's return");
            assertTrue(back.get(stake) > s.round(stake).odds().rtp() + 0.02, "never one spin's");
        }
        @SuppressWarnings("unchecked")
        List<FeedWriter.PayRow> rows = (List<FeedWriter.PayRow>) w[4];
        assertEquals(7, rows.size(), "both stakes pay alike, so one set of lines in multiples");
        assertEquals("3 creeper", rows.get(0).combo());
        assertEquals(20, rows.get(0).pays());
        assertNull(rows.get(0).stake());
        assertTrue(rows.stream().anyMatch(r -> r.combo().equals("3 ender_pearl")), rows.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) w[6];
        assertEquals(5, extra.get("freeSpins"));
        assertEquals(Map.of("1", s.round(1).creepers().value(), "2", s.round(2).creepers().value()),
                extra.get("freeSpinsChance"));
    }
}
