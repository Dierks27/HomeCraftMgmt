package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.slots.DeepMine;
import com.dierks.homecraft.games.chance.slots.DeepMineEngine;
import com.dierks.homecraft.games.chance.slots.DeepMineEngine.Dig;
import com.dierks.homecraft.games.chance.slots.DeepMineSettings;
import com.dierks.homecraft.games.chance.slots.FreeSpinsEngine;
import com.dierks.homecraft.games.chance.slots.FreeSpinsEngine.Round;
import com.dierks.homecraft.games.chance.slots.Line;
import com.dierks.homecraft.games.chance.slots.MobSlots;
import com.dierks.homecraft.games.chance.slots.MobSlotsSettings;
import com.dierks.homecraft.games.chance.slots.SlotTheme;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.Symbol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deep Mine and Mob Slots themselves, the Game Parlor's last two slot machines, run for real over the framework's
 * own gate, rounds and database with a bank.
 *
 * <p>A round's reels come from a seed the round draws itself, so no test here can ask for a line; each follows every
 * spin's own result, so it holds whatever the reels do. Where free spins are needed, Mob Slots is given reels that
 * hold nothing but Creepers and Cobweb (three Creepers about one spin in 47) and is spun until they come.
 *
 * <ul>
 *   <li>a Deep Mine spin takes three times what is on a row, in one go, and pays the three rows' sum once, as ONE
 *       round whose stored data reads back as the spin and is the spin its seed decides;</li>
 *   <li>a Mob Slots round with free spins is ONE round with one stake and one payment, and one of the day's plays;</li>
 *   <li>the day's token limit, the day's money limit and the balance are asked about the whole amount;</li>
 *   <li>both are played for dollars in the Parlor, and refused, with nothing taken, outside it, without
 *       {@code hcm.games.money}, or (Deep Mine) when three rows would be more than the biggest stake; tokens and
 *       money never mix in one round.</li>
 * </ul>
 */
class LastSlotMachinesTest {

    private Host host;
    private GamesService games;
    private DeepMine deep;
    private MobSlots mob;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 20, 12, 0));
        host.bankOn = true;
        host.parlorKept = new HashMap<>();
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 1_000);
        host.money.put(alex.id, 10_000.0);
        build(0, null);
    }

    /** The framework with both machines: this server limit on a day's tokens (0: none), Mob Slots as given or shipped. */
    private void build(int chanceDailyTokens, MobSlotsSettings mobSettings) {
        List<Object> config = new ArrayList<>();
        if (mobSettings != null) {
            config.add("mob_slots");
            config.add(mobSettings);
        }
        host.config = GamesKit.config(GamesKit.common(true, chanceDailyTokens, 600, 6), config.toArray());
        games = GamesKit.service(host, List.of(DeepMine.SPEC, MobSlots.SPEC));
        deep = (DeepMine) games.game("deep_mine");
        mob = (MobSlots) games.game("mob_slots");
        games.parlorGuest(alex.id);
    }

    /**
     * Mob Slots with reels of nothing but Creepers and Cobweb: three Creepers (the only line there is) about one
     * spin in 47, and {@code freeSpins} free spins after them.
     */
    private static MobSlotsSettings onlyCreepers(int freeSpins, int creeperPays, int dailyLimit) {
        Map<String, Integer> reels = new LinkedHashMap<>();
        Map<String, Integer> pays = new LinkedHashMap<>();
        for (String k : SlotTheme.MOB.reelKeys()) {
            reels.put(k, k.equals("creeper") ? 5 : 0);
            pays.put(k, k.equals("creeper") ? creeperPays : 0);
        }
        pays.put("two", 0);
        return MobSlotsSettings.of(true, List.of(1), dailyLimit, 90, freeSpins, reels, pays, 250);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private int tokens() {
        return host.balanceOf(alex.id);
    }

    private double money() {
        return host.money.get(alex.id);
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** The newest round as stored. */
    private record Stored(String game, int stake, int payout, long seed, String data, int money, String state,
                          int owed) {
    }

    private Stored newest() throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement("SELECT game, stake, payout, seed, data, money,"
                + " state, owed FROM game_rounds ORDER BY id DESC LIMIT 1"); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "a round was stored");
            return new Stored(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getString(5),
                    rs.getInt(6), rs.getString(7), rs.getInt(8));
        }
    }

    private DeepMine.Played dig(int perLine, boolean dollars) {
        DeepMine.Played out = deep.spin(alex.player, deep.settings(), perLine, dollars);
        host.move(1_000); // past the click cooldown
        return out;
    }

    private MobSlots.Played spin(int stake, boolean dollars) {
        MobSlots.Played out = mob.spin(alex.player, mob.settings(), stake, dollars);
        host.move(1_000);
        return out;
    }

    // ---- they are games like any other --------------------------------------------------------------------

    @Test
    void eachIsItsOwnGameOfChanceWithItsOwnLedgerSource() {
        assertEquals("deep_mine", deep.id());
        assertEquals("Deep Mine", deep.name());
        assertEquals(GameKind.CHANCE, deep.kind());
        assertEquals(TokenService.Source.ARCADE_DEEP_MINE, deep.source());
        assertEquals("mob_slots", mob.id());
        assertEquals("Mob Slots", mob.name());
        assertEquals(GameKind.CHANCE, mob.kind());
        assertEquals(TokenService.Source.ARCADE_MOB_SLOTS, mob.source());
        for (Game g : List.of(deep, mob)) {
            assertTrue(games.enabled(g), g.id() + " is open");
            assertEquals(0, g.setPrice(), g.id() + " takes tokens, or money from the chips: it has no set price");
            assertEquals(g.name(), g.machineLabel());
            assertFalse(g.featurable(), "a game of chance is never the day's pick");
            assertEquals(50, games.gate().dailyLimit(g), g.id() + ": 50 plays a day");
            assertTrue(com.dierks.homecraft.storage.GamesDao.CHANCE_SOURCES.contains(g.source().name()),
                    g.id() + "'s tokens count against the day's limit on games of chance");
            assertEquals(5, g.rules().size(), g.id() + ": five short lines of rules");
            assertTrue(g.oddsLines().get(0).startsWith("&6" + g.name() + " &7— gives back about 89 of every 100"
                    + " tokens · 50 plays a day"), g.oddsLines().get(0));
        }
        assertEquals(4, deep.oddsLines().size(), "a line for everyone, then one for each of its three stakes");
        assertEquals(3, mob.oddsLines().size());
        assertTrue(mob.oddsLines().get(1).contains("one spin 87.5%"), "an admin sees one spin apart: "
                + mob.oddsLines().get(1));
    }

    // ---- Deep Mine ----------------------------------------------------------------------------------------

    @Test
    void aDeepMineSpinTakesThreeTimesTheRowsStakeAndPaysTheSumOnce() throws Exception {
        int spins = 0;
        int in = 0;
        int back = 0;
        int paying = 0;
        for (int perLine : List.of(1, 2, 5)) {
            for (int n = 0; n < 12; n++) {
                int before = tokens();
                DeepMine.Played played = dig(perLine, false);
                assertNotNull(played, alex.heard());
                Dig dig = played.dig();
                spins++;
                int total = 3 * perLine;
                assertEquals(3, dig.rows().size());
                assertEquals(perLine, dig.perLine());
                int sum = dig.rows().stream().mapToInt(Spin::payout).sum();
                assertEquals(sum, dig.payout(), "the spin pays its three rows added up");
                assertEquals(before - total + dig.payout(), tokens(), "three rows in, their sum back, once");
                in += total;
                back += dig.payout();
                paying += dig.payout() > 0 ? 1 : 0;

                // one round, for the whole spin
                Stored round = newest();
                assertEquals(spins, count("SELECT COUNT(*) FROM game_rounds"), "one round a spin, not one a row");
                assertEquals("deep_mine", round.game());
                assertEquals(total, round.stake(), "the round's stake is the whole spin");
                assertEquals(dig.payout(), round.payout());
                assertEquals("SETTLED", round.state());
                assertEquals(0, round.money());
                // what is stored shows the spin again, and the seed decides exactly it
                assertEquals(dig, DeepMineEngine.parse(round.data()), round.data());
                assertEquals(dig, new DeepMineEngine(played.line(), perLine).decide(round.seed(), total),
                        "the stored seed decides the same three rows");
                assertTrue(dig.payout() <= 250, "never over the max_payout");
            }
        }
        assertEquals(36, spins);
        assertEquals(12 * 3 + 12 * 6 + 12 * 15, in);
        assertEquals(spins, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_DEEP_MINE' AND delta < 0"),
                "one line in the ledger for what went in, a spin");
        assertEquals(paying, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_DEEP_MINE' AND delta > 0"),
                "and one for what came back, only when something did");
        assertEquals(back - in, count("SELECT COALESCE(SUM(delta), 0) FROM token_ledger WHERE source ="
                + " 'ARCADE_DEEP_MINE'"));
        assertEquals(in, games.breaks().tokensInToday(alex.id), "the day's tokens count the whole spins");
        assertEquals(36, host.dao.playsToday(alex.id, "deep_mine", host.clock.dayKey()), "a spin is one play");
        assertEquals(50 - 36, deep.playsLeft(alex.id, deep.settings()));
        assertEquals(10_000.0, money(), "no money moved");
        assertEquals(0, host.severe());
    }

    @Test
    void aDeepMineSpinNeedsTheWholeSpinsTokens() throws Exception {
        host.give(alex.id, -1_000 + 5); // 5 tokens left
        assertEquals(5, tokens());
        assertNull(dig(2, false), "2 a row is 6");
        assertTrue(alex.heard().contains("You need 1 more token."), alex.heard());
        assertEquals(5, tokens(), "nothing was taken");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertNotNull(dig(1, false), "1 a row is 3: " + alex.heard());
        assertNull(dig(3, false), "3 a row is not a stake it has");
        assertNull(dig(0, false));
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds"));
    }

    @Test
    void theDaysTokenLimitCountsTheWholeSpin() throws Exception {
        build(100, null); // the server's limit: 100 tokens a day into games of chance
        int played = 0;
        for (int n = 0; n < 6; n++) {
            assertNotNull(dig(5, false), "spin " + (n + 1) + ": " + alex.heard());
            played += 15;
        }
        assertEquals(90, played);
        assertEquals(90, games.breaks().tokensInToday(alex.id), "six spins of 15");
        int before = tokens();
        assertNull(dig(5, false), "a seventh would be 105");
        assertTrue(alex.heard().contains("That's your limit for today (100 tokens)."), alex.heard());
        assertEquals(before, tokens(), "nothing was taken");
        assertNull(dig(5, false));
        assertNotNull(dig(2, false), "6 more still fits: 96");
        assertNotNull(dig(1, false), "and 3 more: 99");
        assertNull(dig(1, false), "but not 102");
        assertEquals(99, games.breaks().tokensInToday(alex.id));
        assertEquals(8, count("SELECT COUNT(*) FROM game_rounds"));
    }

    @Test
    void deepMineIsPlayedForDollarsInTheParlorAndTheParlorIsAskedAboutTheWholeSpin() throws Exception {
        games.parlor().limit(Parlor.NO_LIMIT);
        assertNull(deep.rowsBlocker(alex.player, 5), "three rows of $5 can go");
        double before = money();
        DeepMine.Played played = dig(5, true);
        assertNotNull(played, alex.heard());
        assertEquals(before - 15 + played.dig().payout(), money(), 1e-9, "$15 in, the rows' sum back");
        assertEquals(1_000, tokens(), "no tokens moved");
        Stored round = newest();
        assertEquals(15, round.stake());
        assertEquals(1, round.money());
        assertEquals(0, round.owed(), "and it is paid");
        assertEquals(played.dig(), DeepMineEngine.parse(round.data()));
        assertEquals(166, played.line().cap(), "a row may pay a third of the Parlor's $500");
        assertEquals(15, games.parlor().inToday(alex.id), "the day's money counts the whole spin");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_DEEP_MINE'"),
                "the token ledger never heard of it");
        assertEquals(0, games.breaks().tokensInToday(alex.id));
        assertNotNull(dig(10, true), "$10 a row is $30: " + alex.heard());
        assertNotNull(dig(15, true), "$15 a row is $45: " + alex.heard());

        // three rows of $20 would be $60: over the biggest stake, so it is not played
        double held = money();
        assertEquals("3 rows of $20 is $60. The most is $50.", deep.rowsBlocker(alex.player, 20));
        assertNull(dig(20, true));
        assertTrue(alex.heard().contains("Playing for money is closed right now."), alex.heard());
        assertNull(dig(7, true), "$7 a row is not an amount the chips make");
        assertEquals(held, money(), 1e-9, "nothing was taken");
        assertEquals(3, count("SELECT COUNT(*) FROM game_rounds"));
    }

    @Test
    void theDaysMoneyLimitCountsTheWholeSpin() {
        assertEquals(250, games.parlor().rules().limit(), "$250 a day as shipped");
        for (int n = 0; n < 5; n++) {
            assertNotNull(dig(15, true), "spin " + (n + 1) + ": " + alex.heard()); // $45 each
        }
        assertEquals(225, games.parlor().inToday(alex.id));
        double before = money();
        assertEquals("Over your limit today", deep.rowsBlocker(alex.player, 10), "$30 more would be $255");
        assertNull(dig(10, true));
        assertTrue(alex.heard().contains("That's your money limit for today ($250)."), alex.heard());
        assertEquals(before, money(), 1e-9);
        assertNotNull(dig(5, true), "$15 more is $240: " + alex.heard());
        assertNull(dig(5, true), "and $255 is over");
        assertEquals(240, games.parlor().inToday(alex.id));
    }

    @Test
    void outsideTheParlorOrWithoutThePermissionNoMoneyMoves() throws Exception {
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertNull(dig(5, true));
        assertNull(spin(5, true));
        assertTrue(alex.heard().contains("Playing for money isn't open to you."), alex.heard());
        alex.denied.clear();

        games.parlorGuests(List.of());
        assertNull(dig(5, true));
        assertNull(spin(5, true));
        assertTrue(alex.heard().contains("Playing for money is only in the Game Parlor."), alex.heard());
        assertEquals("Closed right now", deep.rowsBlocker(alex.player, 5), "the Spin tile is unlit there");

        games.parlorGuest(alex.id);
        games.parlor().money(false);
        assertNull(dig(5, true));
        assertNull(spin(5, true));
        games.parlor().money(true);

        assertEquals(10_000.0, money(), "nothing was taken");
        assertEquals(1_000, tokens());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(0, games.parlor().inToday(alex.id));

        // tokens are still played outside the Parlor, as every game of chance's are
        games.parlorGuests(List.of());
        assertNotNull(dig(1, false), alex.heard());
        assertNotNull(spin(1, false), alex.heard());
        assertEquals(10_000.0, money(), "and move no money");
        alex.denied.add(PlayGate.PERMISSION_CHANCE);
        assertNull(dig(1, false), "the games of chance's own permission rules them too");
        assertNull(spin(1, false));
    }

    // ---- Mob Slots ----------------------------------------------------------------------------------------

    @Test
    void aMobSlotsSpinIsOneRoundThatTakesTheStakeAndPaysItsLine() throws Exception {
        int in = 0;
        int back = 0;
        for (int stake : List.of(1, 2)) {
            for (int n = 0; n < 20; n++) {
                int before = tokens();
                MobSlots.Played played = spin(stake, false);
                assertNotNull(played, alex.heard());
                Round round = played.round();
                assertEquals(before - stake + round.payout(), tokens());
                in += stake;
                back += round.payout();
                Stored stored = newest();
                assertEquals("mob_slots", stored.game());
                assertEquals(stake, stored.stake());
                assertEquals(round.payout(), stored.payout());
                assertEquals(round, FreeSpinsEngine.parse(stored.data()), stored.data());
                assertEquals(round, new FreeSpinsEngine(played.spin(), stake, 5).decide(stored.seed(), stake),
                        "the stored seed decides the same round, free spins and all");
                assertEquals(round.bonus(), FreeSpinsEngine.threeCreepers(round.base()));
                assertTrue(round.payout() <= 250);
            }
        }
        assertEquals(40, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'mob_slots' AND state = 'SETTLED'"));
        assertEquals(in, games.breaks().tokensInToday(alex.id));
        assertEquals(back - in, count("SELECT COALESCE(SUM(delta), 0) FROM token_ledger WHERE source ="
                + " 'ARCADE_MOB_SLOTS'"));
        assertEquals(40, host.dao.playsToday(alex.id, "mob_slots", host.clock.dayKey()));
        assertNull(spin(5, false), "5 tokens is not a stake it has");
        assertEquals(0, host.severe());
    }

    @Test
    void aRoundWithFreeSpinsIsOneRoundWithOneStakeAndOnePayment() throws Exception {
        build(0, onlyCreepers(5, 40, 10_000));
        assertEquals(List.of(1), mob.settings().offered(), "these reels are offered at 1 in");
        int spins = 0;
        int paying = 0;
        int bonuses = 0;
        int in = 0;
        int back = 0;
        while (bonuses < 3 && spins < 3_000) {
            int before = tokens();
            int rounds = count("SELECT COUNT(*) FROM game_rounds");
            MobSlots.Played played = spin(1, false);
            assertNotNull(played, alex.heard());
            Round round = played.round();
            spins++;
            in += 1;
            back += round.payout();
            paying += round.payout() > 0 ? 1 : 0;
            assertEquals(rounds + 1, count("SELECT COUNT(*) FROM game_rounds"), "one round, whatever it held");
            assertEquals(before - 1 + round.payout(), tokens(), "1 in, the round's total back, once");
            if (!round.bonus()) {
                assertEquals(0, round.payout(), "nothing but three Creepers pays on these reels");
                continue;
            }
            bonuses++;
            assertEquals(List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), round.base().reels());
            assertEquals(Line.THREE_WILDS, round.base().line());
            assertEquals(40, round.base().payout(), "the Creeper line itself is paid");
            assertEquals(5, round.free().size(), "and five free spins come with it");
            int free = round.free().stream().mapToInt(Spin::payout).sum();
            assertEquals(40 + free, round.payout(), "their lines are added to the same round");
            Stored stored = newest();
            assertEquals(1, stored.stake(), "the free spins cost nothing more");
            assertEquals(round.payout(), stored.payout(), "one payout for the spin and its free spins");
            assertEquals("SETTLED", stored.state());
            assertTrue(stored.data().contains(";free=5;spin=wild,wild,wild:wild:40;free1="), stored.data());
            assertEquals(round, FreeSpinsEngine.parse(stored.data()), "what is stored shows every spin again");
            assertEquals(round, new FreeSpinsEngine(played.spin(), 1, 5).decide(stored.seed(), 1));
            assertTrue(round.payout() <= 250, "never over the max_payout: " + round.payout());
        }
        assertEquals(3, bonuses, "three Creepers came three times within 3,000 spins (about once in 47)");
        assertEquals(spins, count("SELECT COUNT(*) FROM game_rounds"), "a round a spin: free spins are not rounds");
        assertEquals(spins, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_MOB_SLOTS' AND delta < 0"),
                "one stake a round in the ledger");
        assertEquals(in, -count("SELECT COALESCE(SUM(delta), 0) FROM token_ledger WHERE source = 'ARCADE_MOB_SLOTS'"
                + " AND delta < 0"), "of 1 token each: a free spin takes nothing");
        assertEquals(paying, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_MOB_SLOTS' AND delta > 0"),
                "and one payment for each round that paid, free spins and all");
        assertEquals(back, count("SELECT COALESCE(SUM(delta), 0) FROM token_ledger WHERE source = 'ARCADE_MOB_SLOTS'"
                + " AND delta > 0"));
        assertEquals(in, games.breaks().tokensInToday(alex.id), "the day's tokens count the stakes only");
        assertEquals(spins, host.dao.playsToday(alex.id, "mob_slots", host.clock.dayKey()),
                "and a round with free spins is one of the day's plays");
        assertEquals(0, host.severe());
    }

    @Test
    void theDaysPlaysCountRoundsNotFreeSpins() {
        build(0, onlyCreepers(5, 40, 4));
        for (int n = 0; n < 4; n++) {
            assertNotNull(spin(1, false), "play " + (n + 1) + ": " + alex.heard());
        }
        assertEquals(0, mob.playsLeft(alex.id, mob.settings()));
        int before = tokens();
        assertNull(spin(1, false), "four plays a day here");
        assertTrue(alex.heard().contains("That's all your plays of Mob Slots for today."), alex.heard());
        assertEquals(before, tokens());
    }

    @Test
    void mobSlotsIsPlayedForDollarsInTheParlor() throws Exception {
        games.parlor().limit(Parlor.NO_LIMIT);
        assertNotNull(mob.moneyEngine(mob.settings(), 5), "$5 is played");
        assertEquals(83, mob.moneyEngine(mob.settings(), 5).cap(), "one spin may pay the Parlor's $500 over six");
        assertNull(mob.moneyBlocker(alex.player, 5));
        int in = 0;
        for (int dollars : List.of(5, 10, 25, 50)) {
            double before = money();
            MobSlots.Played played = spin(dollars, true);
            assertNotNull(played, "$" + dollars + ": " + alex.heard());
            assertEquals(before - dollars + played.round().payout(), money(), 1e-9);
            assertTrue(played.round().payout() <= 500, "never over the Parlor's biggest payout");
            Stored stored = newest();
            assertEquals(dollars, stored.stake());
            assertEquals(1, stored.money());
            assertEquals(0, stored.owed());
            assertEquals(played.round(), FreeSpinsEngine.parse(stored.data()));
            in += dollars;
        }
        assertEquals(in, games.parlor().inToday(alex.id));
        assertEquals(1_000, tokens(), "no tokens moved");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_MOB_SLOTS'"));
        assertNull(spin(7, true), "$7 is not an amount the chips make");
        assertNull(spin(55, true), "nor is anything over the biggest stake");
        assertEquals(4, count("SELECT COUNT(*) FROM game_rounds"));
    }

    @Test
    void aMoneyRoundWithAFreeSpinIsOneRoundPaidOnceInDollars() throws Exception {
        // $5 in, one free spin, the Creeper line paying 16 times: $80 a spin, under a spin's half of the Parlor's $500
        build(0, onlyCreepers(1, 16, 10_000));
        games.parlor().limit(Parlor.NO_LIMIT);
        assertNotNull(mob.moneyEngine(mob.settings(), 5), "these reels are played for $5");
        assertEquals(250, mob.moneyEngine(mob.settings(), 5).cap());
        int spins = 0;
        Round hit = null;
        double expected = money();
        while (hit == null && spins < 3_000) {
            MobSlots.Played played = spin(5, true);
            assertNotNull(played, alex.heard());
            spins++;
            expected += played.round().payout() - 5;
            assertEquals(expected, money(), 1e-6, "spin " + spins);
            if (played.round().bonus()) {
                hit = played.round();
            }
        }
        assertNotNull(hit, "three Creepers came within 3,000 spins");
        assertEquals(80, hit.base().payout());
        assertEquals(1, hit.free().size());
        assertEquals(80 + hit.free().get(0).payout(), hit.payout());
        Stored stored = newest();
        assertEquals(5, stored.stake());
        assertEquals(hit.payout(), stored.payout(), "one payout, in dollars, for the spin and its free spin");
        assertEquals(1, stored.money());
        assertEquals(0, stored.owed());
        assertEquals(spins, count("SELECT COUNT(*) FROM game_rounds WHERE money = 1"));
        assertEquals(5 * spins, games.parlor().inToday(alex.id), "the day's money counts the stakes only");
        assertEquals(1_000, tokens(), "no tokens moved");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_MOB_SLOTS'"));
    }
}
