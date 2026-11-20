package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.TestGame;
import com.dierks.homecraft.games.chance.slots.FarmSlots;
import com.dierks.homecraft.games.chance.slots.Line;
import com.dierks.homecraft.games.chance.slots.Motherlode;
import com.dierks.homecraft.games.chance.slots.PotSlots;
import com.dierks.homecraft.games.chance.slots.PotSlots.PotSpin;
import com.dierks.homecraft.games.chance.slots.SlotSettings;
import com.dierks.homecraft.games.chance.slots.SlotTheme;
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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pot machines (the owner: "can we make a slot machine ... where you can have a building pot", "Lets make
 * it the small-pot machine"): The Motherlode and Farm Slots themselves, run for real over the framework's own gate,
 * rounds and database with a bank that can be made to refuse.
 *
 * <p>A spin's reels come from a seed the round draws itself, so no test here can ask for three Wilds. Where one is
 * needed, the machine is given reels that hold nothing but Wilds and the blank symbol (so about one spin in 12 is
 * three Wilds and nothing else ever pays) and is spun until it comes; and every test follows each spin's own result,
 * so it holds whatever the reels do.
 *
 * <ul>
 *   <li>a spin takes the price in dollars, pays its line in dollars and adds its share to the pot, by the cent;</li>
 *   <li>the Parlor's pot cap stops the pot growing, and no cap lets it grow;</li>
 *   <li>three Wilds pays the seed through the round and the added part on top, exactly once; the server and the
 *       console are told; the pot reads as its seed again;</li>
 *   <li>a bank that refuses keeps the added part owed to the player, and it is paid later, once;</li>
 *   <li>without {@code hcm.games.money}, outside the Parlor or with money play off nobody spins, and nothing moves;</li>
 *   <li>it never takes tokens, and its price needn't be one the chips can make (while a chip stake still must be);</li>
 *   <li>the day's money limit, the machine's plays a day and the balance all count its spins;</li>
 *   <li>the pot and the cap are kept and read back.</li>
 * </ul>
 */
class PotMachinesTest {

    private static final String LODE = "motherlode";

    private Host host;
    private GamesService games;
    private Motherlode lode;
    private FarmSlots farm;
    private TestGame chips;
    private Fake alex;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 20, 12, 0));
        host.bankOn = true;
        host.parlorKept = new HashMap<>();
        build(null);
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 100);
        host.money.put(alex.id, 10_000.0);
        games.parlorGuest(alex.id);
    }

    /** The framework with both pot machines (The Motherlode on {@code lodeSettings}, or as shipped) and a chips game. */
    private void build(SlotSettings lodeSettings) {
        chips = GamesKit.chance();
        ChanceSettings settings = new ChanceSettings(true, List.of(1, 5, 10), 30);
        List<Object> config = new ArrayList<>(List.of("test_slots", settings));
        if (lodeSettings != null) {
            config.add(LODE);
            config.add(lodeSettings);
        }
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), config.toArray());
        games = GamesKit.service(host, List.of(Motherlode.SPEC, FarmSlots.SPEC, GamesKit.spec(chips, settings, null)));
        lode = (Motherlode) games.game(LODE);
        farm = (FarmSlots) games.game("farm_slots");
        if (alex != null) {
            games.parlorGuest(alex.id);
        }
    }

    /**
     * The Motherlode with reels of nothing but Wilds and Deepslate: three Wilds about one spin in 12, and no other
     * line ever. Still $5 a spin, 5 of every 100 into the pot, the pot starting at $50.
     */
    private static SlotSettings onlyWilds(int dailyLimit) {
        Map<String, Integer> reels = new LinkedHashMap<>();
        Map<String, Integer> pays = new LinkedHashMap<>();
        for (String k : SlotTheme.MOTHERLODE.reelKeys()) {
            reels.put(k, k.equals("wild") ? 5 : 0);
            pays.put(k, k.equals("wild") ? 10 : 0);
        }
        pays.put("two", 0);
        return SlotSettings.of(SlotTheme.MOTHERLODE, true, true, List.of(5), dailyLimit, 90, 5, reels, pays, 250);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private double money() {
        return host.money.get(alex.id);
    }

    private Pots pots() {
        return games.parlor().pots();
    }

    private PotSpin spin(PotSlots machine) {
        PotSpin out = machine.spin(alex.player);
        host.move(1_000); // past the click cooldown
        return out;
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    // ---- a spin ------------------------------------------------------------------------------------------

    @Test
    void aSpinTakesThePricePaysItsLineAndFeedsThePotByTheCent() throws Exception {
        games.parlor().limit(Parlor.NO_LIMIT);
        assertEquals(5, lode.setPrice(), "$5 a spin");
        assertEquals(25, lode.feedCents(lode.settings()), "5 of every 100: 25 cents a spin");
        assertEquals(5000, lode.seedCents(lode.settings()), "the pot starts at $50");
        assertEquals(5000, lode.potCents(), "and holds just that before anyone spins");
        double expected = money();
        long added = 0;
        for (int n = 1; n <= 40; n++) {
            PotSpin r = spin(lode);
            assertNotNull(r, "spin " + n + " was played: " + alex.heard());
            assertEquals(5, r.spin().stake());
            expected += r.spin().payout() - 5;
            if (r.tookPot()) {
                assertEquals(5000 + added + 25, r.potCents(), "the whole pot, this spin's own share in it");
                expected += (added + 25) / 100.0;
                added = 0;
            } else {
                assertEquals(0, r.potCents());
                added += 25;
            }
            assertEquals(added, pots().added(LODE), "spin " + n + ": what the spins have added");
            assertEquals(5000 + added, lode.potCents(), "spin " + n + ": the pot everyone sees");
            assertEquals(expected, money(), 1e-6, "spin " + n + ": $5 in, the line back, by the cent");
            assertEquals(5 * n, games.parlor().inToday(alex.id), "spin " + n + " counts against the day's money");
        }
        assertEquals(40, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'motherlode' AND money = 1 AND stake = 5"
                + " AND state = 'SETTLED' AND owed = 0"), "forty settled money rounds, nothing left owing");
        assertEquals(100, host.balanceOf(alex.id), "no tokens moved");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source IN ('ARCADE_MOTHERLODE',"
                + " 'ARCADE_FARM_SLOTS')"), "and the token ledger never heard of it");
        assertEquals(0, games.breaks().tokensInToday(alex.id), "nor the day's token limit");
        assertEquals(Long.toString(added), host.parlorKept.getOrDefault(Pots.KEY + LODE, "0"),
                "the pot is kept, in cents, under the Parlor's own key");
        assertEquals(0, host.severe());
    }

    @Test
    void farmSlotsIsADollarASpinWithItsOwnPot() {
        assertEquals(1, farm.setPrice());
        assertEquals(5, farm.feedCents(farm.settings()), "5 cents a spin");
        assertEquals(1000, farm.seedCents(farm.settings()), "its pot starts at $10");
        assertFalse(Parlor.makeable(1, games.parlor().rules().chips()), "$1 is not a stake the chips can make");
        long added = 0;
        double expected = money();
        for (int n = 1; n <= 20; n++) {
            PotSpin r = spin(farm);
            assertNotNull(r, "a set price is not the chips' to make: " + alex.heard());
            expected += r.spin().payout() - 1 + (r.tookPot() ? (added + 5) / 100.0 : 0);
            added = r.tookPot() ? 0 : added + 5;
            assertEquals(added, pots().added("farm_slots"));
            assertEquals(expected, money(), 1e-6);
        }
        assertEquals(0, pots().added(LODE), "each machine has its own pot");
        assertEquals(5000, lode.potCents());
        assertEquals(20, games.parlor().inToday(alex.id));
        assertEquals("Farm Slots · $" + (1000 + added) / 100, farm.machineLabel(),
                "its label in the room: the pot in whole dollars");
    }

    // ---- the cap -----------------------------------------------------------------------------------------

    @Test
    void theCapStopsThePotGrowing() {
        assertEquals(1000, games.parlor().rules().potCap(), "$1,000 as shipped");
        games.parlor().potCap(51);
        for (int n = 1; n <= 4; n++) {
            assertEquals(25, pots().feed(LODE, 25, 5000), "under the cap: added");
        }
        assertEquals(100, pots().added(LODE), "$50 and $1.00: the pot reads $51, the cap");
        assertEquals(0, pots().feed(LODE, 25, 5000), "at the cap a spin's share simply isn't added");
        assertEquals(100, pots().added(LODE));
        games.parlor().potCap(52);
        assertEquals(100, pots().feed(LODE, 175, 5000), "only what fits under the cap");
        assertEquals(200, pots().added(LODE));
        games.parlor().potCap(40);
        assertEquals(0, pots().feed(LODE, 25, 5000), "a cap under the pot adds nothing");
        assertEquals(200, pots().added(LODE), "and takes nothing away: it stays until it is taken");
        games.parlor().potCap(0);
        assertEquals(25, pots().feed(LODE, 25, 5000), "no cap: it grows");
        assertEquals(1_000_000, pots().feed(LODE, 1_000_000, 5000));
        assertEquals(0, pots().feed(LODE, 0, 5000), "nothing to add");
        assertEquals(0, pots().feed(LODE, -25, 5000));
        assertEquals("1000225", host.parlorKept.get(Pots.KEY + LODE));
    }

    @Test
    void realSpinsStopAtTheCapToo() {
        games.parlor().potCap(50); // the seed itself
        for (int n = 1; n <= 12; n++) {
            assertNotNull(spin(lode), alex.heard());
            assertEquals(0, pots().added(LODE), "spin " + n + ": the pot is at its cap, so nothing is added");
        }
        assertEquals(5000, lode.potCents());
        games.parlor().potCap(0);
        PotSpin r = spin(lode);
        assertEquals(r.tookPot() ? 0 : 25, pots().added(LODE), "the cap lifted: it grows again");
    }

    // ---- three Wilds -------------------------------------------------------------------------------------

    @Test
    void threeWildsPaysTheSeedThroughTheRoundAndTheAddedPartOnTopExactlyOnce() throws Exception {
        build(onlyWilds(10_000));
        games.parlor().limit(Parlor.NO_LIMIT);
        assertEquals(5000, lode.seedCents(lode.settings()), "still a $50 seed");
        assertEquals(16_200, pots().feed(LODE, 16_200, 5000), "earlier spins have added $162");
        assertEquals("The Motherlode · $212", lode.machineLabel());
        double expected = money();
        long added = 16_200;
        PotSpin hit = null;
        int spins = 0;
        while (hit == null && spins < 5_000) {
            PotSpin r = spin(lode);
            spins++;
            assertNotNull(r, alex.heard());
            expected -= 5;
            if (!r.tookPot()) {
                assertEquals(0, r.spin().payout(), "nothing but three Wilds pays on these reels");
                added += 25;
                assertEquals(added, pots().added(LODE));
                assertEquals(expected, money(), 1e-6);
                assertEquals(List.of(), host.shouted, "nobody is told about a spin that isn't the pot");
                continue;
            }
            hit = r;
        }
        assertNotNull(hit, "three Wilds came within 5,000 spins (it comes about once in 12)");
        long onTop = added + 25; // this spin's own share went in before the pot was taken
        assertEquals(List.of(Symbol.WILD, Symbol.WILD, Symbol.WILD), hit.spin().reels());
        assertEquals(Line.THREE_WILDS, hit.spin().line());
        assertEquals(50, hit.spin().payout(), "the round itself pays the line: the $50 seed");
        assertEquals(5000 + onTop, hit.potCents(), "the pot the player took: the seed and all that was added");
        assertEquals(expected + 50 + onTop / 100.0, money(), 1e-6, "paid: the seed through the round, the rest on top");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'motherlode' AND payout = 50 AND owed = 0"),
                "one round paid $50, the line's own pay, and owes nothing");
        assertEquals(spins - 1, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'motherlode' AND payout = 0"));

        // a new pot starts at the seed
        assertEquals(0, pots().added(LODE));
        assertEquals(5000, lode.potCents());
        assertEquals("The Motherlode · $50", lode.machineLabel());
        assertEquals("0", host.parlorKept.get(Pots.KEY + LODE), "and is kept that way");

        // the whole server is told, once (when the reels have stopped, never before); so is the console
        assertEquals(List.of(), host.shouted, "not while the reels still turn");
        host.runTasks();
        assertEquals(1, host.shouted.size(), host.shouted.toString());
        String told = host.shouted.get(0);
        assertTrue(told.contains("Alex") && told.contains("The Motherlode")
                && told.contains(Parlor.money(5000 + onTop)), told);
        assertTrue(told.contains("took the pot"), told);
        assertFalse(told.toLowerCase().contains("jackpot"), told);
        assertEquals(1, host.logs.stream().filter(l -> l.getLevel() == Level.INFO
                && l.getMessage().contains("Alex took The Motherlode's pot: " + Parlor.money(5000 + onTop))).count(),
                "one console line");

        assertEquals(onTop, pots().takenToday(), "the owner's status counts what was paid on top of the rounds");

        // never twice: there is nothing left to take, whoever asks
        double after = money();
        assertEquals(0, pots().take(alex.player, lode, 5000), "the added part is gone");
        assertEquals(after, money(), 1e-9, "and nothing more is paid");
        games.sweep();
        assertEquals(after, money(), 1e-9, "the sweep finds nothing owing");
        assertEquals(0, games.parlor().owed(alex.id));
        assertEquals(onTop, pots().takenToday(), "and nothing more is counted");
        assertEquals(100, host.balanceOf(alex.id), "no tokens moved");
        assertEquals(0, host.severe());
        host.move(13 * 3_600_000L); // past midnight
        assertEquals(0, pots().takenToday(), "yesterday's pots aren't today's");

        // the next spin feeds the new pot from its seed
        PotSpin next = spin(lode);
        assertEquals(next.tookPot() ? 0 : 25, pots().added(LODE));
    }

    @Test
    void aBankThatRefusesKeepsThePotOwedAndPaysItLaterOnce() throws Exception {
        build(onlyWilds(10_000));
        games.parlor().limit(Parlor.NO_LIMIT);
        pots().feed(LODE, 7_500, 5000);
        host.bankRefusesGive = true; // it takes the price, but won't pay anything out
        double start = money();
        long added = 7_500;
        int spins = 0;
        PotSpin hit = null;
        while (hit == null && spins < 5_000) {
            PotSpin r = spin(lode);
            spins++;
            assertNotNull(r, alex.heard());
            if (r.tookPot()) {
                hit = r;
            } else {
                added += 25;
            }
        }
        assertNotNull(hit, "three Wilds came");
        long onTop = added + 25;
        assertEquals(start - 5.0 * spins, money(), 1e-6, "the bank gave nothing: only the prices went");
        assertEquals(5000 + onTop, hit.potCents(), "the pot is the player's all the same");
        assertEquals(0, pots().added(LODE), "it left the pot (so nobody else can take it)");
        assertEquals(onTop, games.parlor().owed(alex.id), "and the added part is kept, owed to the player");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE game = 'motherlode' AND owed = 50"),
                "the seed is owed on its round, as any refused payout is");
        host.runTasks();
        assertEquals(1, host.shouted.size(), "the server is still told");
        host.canSchedule = false;
        pots().feed(LODE, 100, 5000);
        pots().take(alex.player, lode, 5000);
        assertEquals(2, host.shouted.size(), "with nothing to schedule on (the server stopping) it is said at once");
        host.canSchedule = true;
        onTop += 100;

        games.sweep();
        assertEquals(start - 5.0 * spins, money(), 1e-6, "still refused: still owed");
        assertEquals(onTop, games.parlor().owed(alex.id));

        host.bankRefusesGive = false;
        games.sweep();
        assertEquals(start - 5.0 * spins + 50 + onTop / 100.0, money(), 1e-6, "the bank is back: both parts are paid");
        assertEquals(0, games.parlor().owed(alex.id));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE owed > 0"));
        games.sweep();
        games.parlor().payOwed(alex.player);
        games.rounds().payOwed();
        assertEquals(start - 5.0 * spins + 50 + onTop / 100.0, money(), 1e-6, "once");
        assertEquals(0, pots().added(LODE), "and the pot never got it back");
    }

    // ---- who may spin ------------------------------------------------------------------------------------

    @Test
    void withoutThePermissionOutsideTheParlorOrWithMoneyOffNobodySpins() throws Exception {
        assertNull(lode.blocker(alex.player), "in the Parlor, with the permission: Spin is lit");
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertNull(spin(lode));
        assertTrue(alex.heard().contains("Playing for money isn't open to you."), alex.heard());
        assertEquals("Playing for money isn't open to you", lode.blocker(alex.player), "the Spin tile's reason");
        alex.denied.clear();

        games.parlorGuests(List.of());
        assertNull(spin(lode));
        assertTrue(alex.heard().contains("Playing for money is only in the Game Parlor."), alex.heard());
        assertEquals("Only in the Game Parlor", lode.blocker(alex.player));
        assertEquals("Only in the Game Parlor", farm.blocker(alex.player));
        games.parlorGuest(alex.id);

        games.parlor().money(false);
        assertNull(spin(lode));
        assertEquals("Playing for money is closed right now", lode.blocker(alex.player));
        games.parlor().money(true);
        host.bankOn = false;
        assertNull(spin(farm), "no economy connected");
        assertEquals("Playing for money is closed right now", farm.blocker(alex.player));
        host.bankOn = true;

        alex.denied.add(PlayGate.PERMISSION_CHANCE);
        assertNull(spin(lode), "the games of chance's own permission still rules");
        alex.denied.clear();

        chips.parlor = true; // the Clubhouse has its Parlor, so the games of chance are kept to it
        games.parlorGuests(List.of());
        assertNull(spin(lode));
        assertTrue(alex.heard().contains("That game is played in the Clubhouse's Game Parlor."), alex.heard());
        chips.parlor = false;
        games.parlorGuest(alex.id);

        assertEquals(10_000.0, money(), "nothing was taken");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(0, pots().added(LODE), "and a spin that wasn't played feeds no pot");
        assertEquals(0, pots().added("farm_slots"));
        assertEquals(0, games.parlor().inToday(alex.id));
        assertNotNull(spin(lode), "and with everything back, it spins");
    }

    @Test
    void itNeverTakesTokens() throws Exception {
        assertEquals(Parlor.MONEY_ONLY, games.canStake(alex.player, lode, 5), "the gate refuses tokens outright");
        assertEquals(Parlor.MONEY_ONLY, games.gate().check(alex.player, farm, 1, false));
        assertNull(games.rounds().play(alex.player, lode, 5, lode.settings().engine(), false), "a token spin");
        assertTrue(alex.heard().contains("That machine is played for money, in the Game Parlor."), alex.heard());
        assertEquals(100, host.balanceOf(alex.id), "no token left the player");
        assertEquals(10_000.0, money());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source LIKE 'ARCADE_%'"));
        assertEquals(0, pots().added(LODE));
        games.parlorGuests(List.of());
        assertEquals(Parlor.MONEY_ONLY, games.canStake(alex.player, lode, 5), "outside the Parlor too: never tokens");

        // a machine left with no price at all is closed, and is still never a game that takes tokens
        SlotSettings shipped = SlotSettings.motherlode();
        build(SlotSettings.of(SlotTheme.MOTHERLODE, true, true, List.of(), 50, 90, 5, shipped.reels(), shipped.pays(),
                250));
        assertFalse(games.enabled(lode), "no price: closed");
        assertEquals(5, lode.setPrice(), "it reads as its shipped price, never as no price");
        assertEquals(Refusal.CLOSED, games.canStake(alex.player, lode, 5));
        assertNull(lode.spin(alex.player));
        assertEquals(100, host.balanceOf(alex.id));
        assertEquals(10_000.0, money());
    }

    @Test
    void aSetPriceIsNotTheChipsToMakeButAChipStakeStillIs() {
        games.parlor().chips(List.of(10)); // $5 and $1 can no longer be built from chips
        assertFalse(Parlor.stakeFits(5, games.parlor().rules()));
        assertNotNull(spin(lode), "The Motherlode's $5 is its own price: " + alex.heard());
        assertNotNull(spin(farm), "and Farm Slots' $1: " + alex.heard());
        assertNull(games.rounds().play(alex.player, chips, 5, PlayGateTest.engine(0), true),
                "a chips game is still refused a stake the chips can't make");
        assertTrue(alex.heard().contains("Playing for money is closed right now."), alex.heard());
        host.move(1_000);
        assertNull(games.rounds().play(alex.player, lode, 10, PlayGateTest.engine(0), true),
                "and a pot machine takes its own price, never another amount");
        assertEquals(Parlor.MONEY_CLOSED, games.gate().check(alex.player, lode, 10, true));
        assertNull(games.gate().check(alex.player, lode, 5, true));
        assertEquals(6, games.parlor().inToday(alex.id), "only the two real spins counted");
    }

    // ---- the limits --------------------------------------------------------------------------------------

    @Test
    void theDaysMoneyLimitCountsTheSpins() {
        games.parlor().limit(12);
        assertNotNull(spin(lode), alex.heard());
        assertNotNull(spin(lode), alex.heard());
        assertEquals(10, games.parlor().inToday(alex.id));
        double before = money();
        long potBefore = pots().added(LODE);
        assertNull(spin(lode), "$10 + $5 would pass $12");
        assertTrue(alex.heard().contains("That's your money limit for today ($12)."), alex.heard());
        assertEquals("Over your limit today", lode.blocker(alex.player), "the Spin tile's reason");
        assertEquals(before, money(), 1e-9, "the refused spin took nothing");
        assertEquals(potBefore, pots().added(LODE), "and fed nothing");
        assertNotNull(spin(farm), "$1 still fits under it");
        assertNotNull(spin(farm));
        assertNull(spin(farm), "$12 is $12, whichever machine");
        assertTrue(games.parlor().limit(alex.id, 20), "the player's own limit, raised by an admin");
        assertNotNull(spin(lode), alex.heard());
        host.move(13 * 3_600_000L); // past midnight
        assertEquals(0, games.parlor().inToday(alex.id), "a new day");
        assertNotNull(spin(lode));
    }

    @Test
    void theMachinesOwnPlaysADayAndTheBalanceCountToo() {
        build(onlyWilds(3));
        games.parlor().limit(Parlor.NO_LIMIT);
        for (int n = 1; n <= 3; n++) {
            assertNotNull(spin(lode), "play " + n + ": " + alex.heard());
        }
        assertNull(spin(lode), "three plays a day");
        assertTrue(alex.heard().contains("That's all your plays of The Motherlode for today."), alex.heard());
        host.money.put(alex.id, 0.5);
        assertNull(spin(farm), "50 cents is not a dollar");
        assertEquals("Not enough money", farm.blocker(alex.player));
        assertEquals(0.5, money());
    }

    // ---- kept ---------------------------------------------------------------------------------------------

    @Test
    void thePotAndTheCapAreKeptAndReadBack() {
        pots().feed(LODE, 1_234, 5000);
        pots().feed("farm_slots", 55, 1000);
        games.parlor().potCap(750);
        build(null); // a restart: a new framework over the same database
        assertEquals(1_234, pots().added(LODE), "the pot survives a restart");
        assertEquals(55, pots().added("farm_slots"));
        assertEquals(6234, lode.potCents());
        assertEquals(1055, farm.potCents());
        assertEquals(750, games.parlor().rules().potCap());
        games.parlor().potCap(0);
        build(null);
        assertEquals(0, games.parlor().rules().potCap(), "no cap is kept as no cap");
        host.parlorKept.put(Parlor.KEY_POT_CAP, "plenty");
        host.parlorKept.put(Pots.KEY + LODE, "-40");
        build(null);
        assertEquals(1000, games.parlor().rules().potCap(), "nonsense reads as shipped");
        assertEquals(0, pots().added(LODE), "and a pot under nothing is nothing");
        host.parlorKept = null;
        build(null);
        assertEquals(25, pots().feed(LODE, 25, 5000), "with nothing to keep it in, it is kept in memory");
        assertEquals(25, pots().added(LODE));
    }

    @Test
    void aPotThatCannotBeReadOrSetBackIsNeverGuessedAtAndNeverPaid() {
        boolean[] down = {false};
        Map<String, String> kept = new HashMap<>() {
            @Override
            public String get(Object key) {
                if (down[0] && String.valueOf(key).startsWith(Pots.KEY)) {
                    throw new IllegalStateException("the database is down");
                }
                return super.get(key);
            }

            @Override
            public String put(String key, String value) {
                if (down[0] && key.startsWith(Pots.KEY)) {
                    throw new IllegalStateException("the database is down");
                }
                return super.put(key, value);
            }
        };
        kept.put(Pots.KEY + LODE, "20000");
        host.parlorKept = kept;
        build(null); // a restart: nothing is remembered yet
        double before = money();

        down[0] = true;
        assertEquals(0, pots().added(LODE), "a pot that can't be read shows as its starting amount");
        assertEquals(0, pots().feed(LODE, 25, 5000), "and nothing is written over it");
        assertEquals(0, pots().take(alex.player, lode, 5000), "nor is it taken on a guess");
        assertEquals(before, money(), 1e-9);
        down[0] = false;
        assertEquals("20000", kept.get(Pots.KEY + LODE), "the real pot was never touched");
        assertEquals(20_000, pots().added(LODE), "and reads again once it can be");

        down[0] = true; // now it is known, but can't be set back to nothing
        assertEquals(0, pots().take(alex.player, lode, 5000), "a pot that can't be set back is not paid out");
        assertEquals(before, money(), 1e-9, "so it can never be paid twice");
        assertEquals(0, games.parlor().owed(alex.id));
        assertEquals(20_000, pots().added(LODE), "it stays in the pot");
        assertEquals(3, host.severe(), "each time the console says so, loudly (the failed write, and what it meant)");

        down[0] = false;
        assertEquals(20_000, pots().take(alex.player, lode, 5000), "with the database back it is taken");
        assertEquals(before + 200.0, money(), 1e-9, "once");
        assertEquals("0", kept.get(Pots.KEY + LODE));
        assertEquals(0, pots().take(alex.player, lode, 5000));
        assertEquals(before + 200.0, money(), 1e-9);

        kept.put(Pots.KEY + "farm_slots", "plenty");
        assertEquals(0, pots().added("farm_slots"), "an amount that is not a number starts again from nothing added");
        assertEquals(5, pots().feed("farm_slots", 5, 1000));
    }

    // ---- what it publishes ---------------------------------------------------------------------------------

    @Test
    void itsOddsAndItsFeedEntryCountThePotsShareAndShowThePot() {
        pots().feed(LODE, 16_225, 5000);
        List<String> odds = lode.oddsLines();
        assertEquals("&6The Motherlode &7— gives back about 90 of every 100 dollars, the pot's share counted · $5 a"
                + " spin · 50 plays a day", odds.get(0));
        assertTrue(odds.get(2).contains("the pot now: &f$212.25 &7(it starts at $50)"), odds.toString());
        assertEquals("The Motherlode · $212", lode.machineLabel());
        assertEquals("The Motherlode", lode.name(), "its name stays its name");
        assertEquals(5, lode.rules().size());

        Map<String, Object> seen = new LinkedHashMap<>();
        lode.feed(new FeedWriter() {
            @Override
            public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                               Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
                seen.put("id", id);
                seen.put("stakes", stakes);
                seen.put("rtp", rtpByStake.get(5));
                seen.put("limit", dailyLimit);
                seen.put("rows", paytable.size());
                seen.putAll(extra);
            }

            @Override
            public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                                String holder) {
                throw new AssertionError("a game of chance");
            }

            @Override
            public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                               String holder) {
                throw new AssertionError("a game of chance");
            }

            @Override
            public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                             String holder) {
                throw new AssertionError("a game of chance");
            }
        });
        assertEquals(LODE, seen.get("id"));
        assertEquals(List.of(5), seen.get("stakes"));
        SlotSettings s = lode.settings();
        assertEquals(s.givesBack(s.engine().odds(5)), (Double) seen.get("rtp"), 1e-12,
                "the website's number is the lines and the pot's share");
        assertTrue((Double) seen.get("rtp") >= RtpLimits.MIN && (Double) seen.get("rtp") <= RtpLimits.MAX);
        assertEquals(50, seen.get("limit"));
        assertEquals(7, seen.get("rows"));
        assertEquals(true, seen.get("money"));
        assertEquals(5, seen.get("feedPercent"));
        assertEquals(50.0, seen.get("potSeed"));
        assertEquals(212.25, seen.get("pot"));
    }
}
