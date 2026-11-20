package com.dierks.homecraft.games;

import com.dierks.homecraft.games.ChanceRounds.Round;
import com.dierks.homecraft.games.GamesKit.ChanceSettings;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.46.0, the Game Parlor's money play (the owner: "play with our money instead of tokens", "Daily Limit of 250",
 * "Only in the parlor", and the limits "by commands"), run for real over the framework's own gate, rounds and
 * database with a bank that can be switched off or made to refuse:
 *
 * <ul>
 *   <li>a play for dollars takes dollars, pays dollars and moves no tokens; it is refused, with nothing taken,
 *       without the permission, outside the Parlor, with money play off, for a stake the chips can't make, over the
 *       day's limit (everyone's or the player's own), or with too little money;</li>
 *   <li>a round opened for dollars is raised and settled in dollars, and a raise the limit refuses puts the dollars
 *       back;</li>
 *   <li>a payout the bank refuses is kept on the round and paid once when the bank is back, never twice;</li>
 *   <li>a round the player walked away from is finished by the exit rule and paid in dollars;</li>
 *   <li>the rules set by command are kept and read back, and nonsense reads as shipped;</li>
 *   <li>with a Parlor there, the games of chance are refused anywhere else and listed only to someone in it.</li>
 * </ul>
 */
class ParlorMoneyTest {

    private Host host;
    private TestGame slots;
    private GamesService games;
    private Fake alex;
    private ExitSettler settler = (seed, stake, data) -> stake * 2;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.bankOn = true;
        host.parlorKept = new HashMap<>();
        build();
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
        host.give(alex.id, 100);
        host.money.put(alex.id, 300.0);
        games.parlorGuest(alex.id);
    }

    private void build() {
        slots = GamesKit.chance();
        ChanceSettings settings = new ChanceSettings(true, List.of(1, 5, 10), 30);
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "test_slots", settings);
        games = GamesKit.service(host, List.of(GamesKit.spec(slots, settings, (seed, stake, data) -> settler
                .payoutOnExit(seed, stake, data))));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private double money() {
        return host.money.get(alex.id);
    }

    private Integer play(int dollars, int pays) {
        Integer out = games.rounds().play(alex.player, slots, dollars, PlayGateTest.engine(pays), true);
        host.move(1_000); // past the click cooldown
        return out;
    }

    // ---- an instant play ----------------------------------------------------------------------------------

    @Test
    void aPlayForDollarsTakesDollarsPaysDollarsAndMovesNoTokens() throws Exception {
        assertEquals(25, play(10, 25), "the engine's outcome comes back for the screen");
        assertEquals(315.0, money(), "$10 in, $25 back");
        assertEquals(100, host.balanceOf(alex.id), "no tokens moved");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED' AND money = 1 AND stake = 10 "
                + "AND payout = 25 AND owed = 0"), "one settled money round, with nothing left owing");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'ARCADE_SLOTS'"),
                "and the token ledger never heard of it");
        assertEquals(0, games.breaks().tokensInToday(alex.id), "so it doesn't count against the day's token limit");
        assertEquals(10, games.parlor().inToday(alex.id), "it counts against the day's money limit");
    }

    @Test
    void aLossTakesTheStakeAndOwesNothing() throws Exception {
        assertEquals(0, play(25, 0));
        assertEquals(275.0, money());
        assertEquals(0, count("SELECT owed FROM game_rounds"));
    }

    @Test
    void aTokenPlayIsExactlyAsItWas() throws Exception {
        assertEquals(12, games.rounds().play(alex.player, slots, 5, PlayGateTest.engine(12)));
        assertEquals(107, host.balanceOf(alex.id), "5 tokens in, 12 back");
        assertEquals(300.0, money(), "no money moved");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE money = 0 AND owed = 0"));
    }

    @Test
    void withoutThePermissionNothingMoves() throws Exception {
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertNull(play(10, 25));
        assertEquals(300.0, money());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertTrue(alex.heard().contains("Playing for money isn't open to you."), alex.heard());
        assertFalse(games.parlor().offered(alex.player), "and the switch isn't shown");
    }

    @Test
    void outsideTheParlorNothingMoves() {
        games.parlorGuests(List.of());
        assertNull(play(10, 25));
        assertEquals(300.0, money());
        assertTrue(alex.heard().contains("Playing for money is only in the Game Parlor."), alex.heard());
    }

    @Test
    void withMoneyPlayOffOrNoBankNothingMoves() {
        games.parlor().money(false);
        assertNull(play(10, 25));
        assertTrue(alex.heard().contains("Playing for money is closed right now."), alex.heard());
        games.parlor().money(true);
        host.bankOn = false;
        assertNull(play(10, 25), "no economy connected");
        host.bankOn = true;
        assertEquals(300.0, money());
        assertEquals(25, play(10, 25), "back on: it plays");
    }

    @Test
    void aStakeTheChipsCantMakeOrOverTheBiggestIsRefused() {
        assertNull(play(7, 25), "5, 10 and 25 never add up to 7");
        assertNull(play(55, 25), "over the biggest stake (50)");
        assertNull(play(3, 25), "under the smallest chip");
        assertEquals(300.0, money(), "nothing was taken");
        assertEquals(25, play(35, 25), "25 + 10 is a stake");
    }

    @Test
    void theDaysMoneyLimitStopsTheNextDollar() {
        for (int i = 0; i < 5; i++) {
            assertEquals(0, play(50, 0), "play " + (i + 1) + " of 5 x $50");
        }
        assertEquals(250, games.parlor().inToday(alex.id));
        assertEquals(0, games.parlor().leftToday(alex.id));
        assertNull(play(5, 25), "$250 is in: not $5 more");
        assertTrue(alex.heard().contains("That's your money limit for today ($250). It resets at midnight."),
                alex.heard());
        assertEquals(50.0, money(), "the refused play took nothing");
        host.move(5 * 3_600_000L); // past midnight
        assertEquals(25, play(5, 25), "a new day");
    }

    @Test
    void theLimitCanBeRaisedOrSwitchedOffByCommand() {
        games.parlor().limit(20);
        assertNull(play(25, 0), "over $20 a day");
        games.parlor().limit(Parlor.NO_LIMIT);
        assertEquals(Integer.MAX_VALUE, games.parlor().leftToday(alex.id), "no limit at all");
        assertEquals(0, play(50, 0));
    }

    @Test
    void aPlayersOwnLimitComesBeforeEveryones() {
        assertTrue(games.parlor().limit(alex.id, 20));
        assertEquals(20, games.parlor().limit(alex.id));
        assertNull(play(25, 0), "Alex's own $20");
        assertTrue(games.parlor().limit(alex.id, Parlor.NO_LIMIT), "an admin's okay: no limit for Alex");
        assertEquals(Parlor.NO_LIMIT, games.parlor().limit(alex.id));
        assertTrue(games.parlor().limit(alex.id, null), "back to everyone's");
        assertEquals(250, games.parlor().limit(alex.id));
        assertFalse(games.parlor().ownLimit(alex.id));
    }

    @Test
    void tooLittleMoneySaysHowMuchMore() {
        host.money.put(alex.id, 12.0);
        assertNull(play(25, 0));
        assertTrue(alex.heard().contains("You need $13 more."), alex.heard());
        assertEquals(12.0, money());
    }

    @Test
    void aPayoutOverTheBiggestIsRefusedAndTakesNothing() throws Exception {
        assertNull(play(10, 501), "the Parlor's biggest payout is $500");
        assertEquals(300.0, money());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(500, play(10, 500), "at the biggest it goes ahead");
    }

    // ---- a round with moves -------------------------------------------------------------------------------

    @Test
    void aRoundForDollarsIsOpenedRaisedAndSettledInDollars() throws Exception {
        Round round = games.rounds().open(alex.player, slots, 10, "v1", true);
        assertNotNull(round);
        assertTrue(round.money());
        assertEquals(290.0, money(), "the stake is taken at open");
        host.move(1_000);
        assertNull(games.rounds().open(alex.player, slots, 10, "v1", true), "one open round a game: resume it");
        assertEquals(290.0, money(), "and nothing more is taken");
        Round resumed = games.rounds().openRound(alex.id, slots.id());
        assertTrue(resumed.money(), "a resumed round still knows it is for dollars");
        Round stepped = games.rounds().step(resumed, "v1;h");
        assertTrue(stepped.money());
        assertTrue(games.rounds().raise(alex.player, stepped, 10), "a Double puts $10 more in");
        assertEquals(280.0, money());
        assertEquals(20, count("SELECT stake FROM game_rounds"));
        assertTrue(games.rounds().close(stepped, 36, null));
        assertFalse(games.rounds().close(stepped, 36, null), "a second close is refused");
        assertEquals(316.0, money(), "paid exactly once");
        assertEquals(100, host.balanceOf(alex.id), "and no tokens moved at any step");
        assertEquals(0, count("SELECT owed FROM game_rounds"));
    }

    @Test
    void aRaiseOverTheDaysLimitPutsTheDollarsBack() throws Exception {
        games.parlor().limit(25);
        Round round = games.rounds().open(alex.player, slots, 10, "v1", true);
        assertFalse(games.rounds().raise(alex.player, round, 25), "$10 in + $25 more would pass $25");
        assertEquals(290.0, money(), "nothing more was taken");
        assertEquals(10, count("SELECT stake FROM game_rounds"));
        assertTrue(games.rounds().raise(alex.player, round, 10), "$10 more stays inside it");
        assertEquals(280.0, money());
    }

    @Test
    void aRoundLeftOpenIsFinishedByTheExitRuleAndPaidInDollars() throws Exception {
        games.rounds().open(alex.player, slots, 10, "v1", true);
        games.rounds().settleOpen(alex.id);
        assertEquals(310.0, money(), "$10 in, the exit rule's $20 back");
        assertEquals(100, host.balanceOf(alex.id));
        assertTrue(alex.heard().contains("was finished for you: $20 back."), alex.heard());
    }

    // ---- the bank is not the database ---------------------------------------------------------------------

    @Test
    void aPayoutTheBankRefusesIsKeptAndPaidOnceWhenItIsBack() throws Exception {
        host.bankRefusesGive = true;
        assertEquals(40, play(10, 40), "the play itself goes ahead: the stake was taken");
        assertEquals(290.0, money(), "not paid yet");
        assertEquals(40, count("SELECT owed FROM game_rounds"), "the round says what it owes");
        games.rounds().payOwed();
        assertEquals(290.0, money(), "still refused: still owed");
        assertEquals(40, count("SELECT owed FROM game_rounds"));
        host.bankRefusesGive = false;
        games.sweep();
        assertEquals(330.0, money(), "the sweep pays it");
        assertEquals(0, count("SELECT owed FROM game_rounds"));
        games.sweep();
        games.rounds().payOwed();
        assertEquals(330.0, money(), "and never again");
    }

    @Test
    void withNoBankAtSettlementTheRoundWaitsForIt() throws Exception {
        Round round = games.rounds().open(alex.player, slots, 10, "v1", true);
        host.bankOn = false; // the economy went away mid-hand
        assertTrue(games.rounds().close(round, 30, null), "the round still settles");
        assertEquals(30, count("SELECT owed FROM game_rounds"), "owing its $30");
        host.bankOn = true;
        games.rounds().payOwed();
        assertEquals(320.0, money());
    }

    // ---- the rules, by command ----------------------------------------------------------------------------

    @Test
    void theRulesAreKeptAndReadBack() {
        Parlor p = games.parlor();
        assertEquals(Parlor.Rules.defaults(), p.rules(), "as shipped until something is set");
        p.onlyHere(false);
        p.money(false);
        p.limit(400);
        p.maxStake(100);
        p.maxPayout(1000);
        p.chips(List.of(50, 10, 20));
        p.ticket(10);
        Parlor.Rules set = new Parlor.Rules(false, false, 400, 100, 1000, List.of(10, 20, 50), 10);
        assertEquals(set, p.rules());
        build(); // a restart: the same store
        assertEquals(set, games.parlor().rules(), "read back from where they are kept");
    }

    @Test
    void nonsenseInTheStoreReadsAsShipped() {
        host.parlorKept.put(Parlor.KEY_LIMIT, "lots");
        host.parlorKept.put(Parlor.KEY_CHIPS, "5,-1");
        host.parlorKept.put(Parlor.KEY_STAKE, "0");
        host.parlorKept.put(Parlor.KEY_MONEY, "maybe");
        build();
        assertEquals(Parlor.Rules.defaults(), games.parlor().rules());
    }

    @Test
    void chipsAddUpUntilSomethingStopsThem() {
        Parlor.Rules r = Parlor.Rules.defaults();
        assertEquals(5, Parlor.add(0, 5, r, 250, 300));
        assertEquals(40, Parlor.add(15, 25, r, 250, 300));
        assertEquals(50, Parlor.add(25, 25, r, 250, 300), "the biggest stake itself");
        assertEquals(30, Parlor.add(30, 25, r, 250, 300), "55 is over the biggest stake: it stays");
        assertEquals("the most is $50", Parlor.chipRefusal(30, 25, r, 250, 300));
        assertEquals(10, Parlor.add(10, 10, r, 15, 300), "only $15 left today");
        assertEquals("over your limit today", Parlor.chipRefusal(10, 10, r, 15, 300));
        assertEquals(10, Parlor.add(10, 10, r, 250, 19.99), "not enough money for $20");
        assertEquals("not enough money", Parlor.chipRefusal(10, 10, r, 250, 19.99));
        assertNull(Parlor.chipRefusal(10, 10, r, 250, 20));
    }

    @Test
    void aStakeIsWhatTheChipsCanMake() {
        List<Integer> chips = List.of(5, 10, 25);
        for (int ok : new int[] {5, 10, 15, 20, 25, 30, 35, 40, 45, 50}) {
            assertTrue(Parlor.makeable(ok, chips), "$" + ok);
        }
        for (int no : new int[] {0, 1, 4, 7, 12, 49}) {
            assertFalse(Parlor.makeable(no, chips), "$" + no);
        }
        assertTrue(Parlor.makeable(9, List.of(2, 7)));
        assertFalse(Parlor.makeable(5, List.of(2, 7)) && !Parlor.makeable(4, List.of(2, 7)));
        assertEquals(List.of(5, 10, 25), Parlor.chips("25, 5 10", List.of(1)), "sorted, any separator");
        assertEquals(List.of(1), Parlor.chips("5,10,25,50", List.of(1)), "four chips don't fit the screen");
    }

    // ---- only in the Parlor -------------------------------------------------------------------------------

    @Test
    void withAParlorThereTheGamesOfChanceAreRefusedAnywhereElse() {
        Fake sam = new Fake("Sam");
        host.online.put(sam.id, sam.player);
        host.give(sam.id, 100);
        assertNull(games.canStake(sam.player, slots, 5), "no Parlor yet: games of chance are where they always were");
        assertTrue(games.parlor().listed(slots, sam.player));
        slots.parlor = true; // the Clubhouse has its Parlor
        assertEquals(Parlor.ONLY_HERE, games.canStake(sam.player, slots, 5), "Sam isn't in it");
        assertEquals(Parlor.ONLY_HERE, games.canOpen(sam.player, slots), "not even the screen");
        assertFalse(games.parlor().listed(slots, sam.player), "and the Games screen and Tab don't offer it");
        assertTrue(games.parlor().listed(slots, null), "the console is offered everything");
        assertNull(games.canStake(alex.player, slots, 5), "Alex is standing in the Parlor: tokens play there as ever");
        assertTrue(games.parlor().listed(slots, alex.player));
        games.parlor().onlyHere(false);
        assertNull(games.canStake(sam.player, slots, 5), "the rule switched off by command");
        assertTrue(games.parlor().listed(slots, sam.player));
    }

    /** 0.46.1, the owner: "Scratch ticket is lottery... I would put that in the parlor as well". */
    @Test
    void theScratchTicketGoesWhereTheGamesOfChanceGo() {
        Fake sam = new Fake("Sam");
        assertTrue(games.parlor().ticketListed(sam.player), "no Parlor: the ticket is in the Arcade as ever");
        slots.parlor = true;
        assertFalse(games.parlor().ticketListed(sam.player), "kept to the Parlor with the games of chance");
        assertTrue(games.parlor().ticketListed(alex.player), "and offered to someone standing in it");
        assertTrue(games.parlor().ticketListed(null), "the console is never refused a listing");
        games.parlor().onlyHere(false);
        assertTrue(games.parlor().ticketListed(sam.player), "the rule off: everywhere again");
    }

    // ---- the Scratch Ticket for dollars (0.46.1: "switch the Scratch Ticket over to $ ... $5 entry") ------------

    @Test
    void atTheBoothTheTicketCostsFiveDollarsAndCountsAgainstTheDay() {
        assertTrue(games.parlor().ticketForMoney(alex.player), "in the Parlor, with a bank: a money ticket");
        assertEquals(5, games.parlor().ticketPrice());
        assertNull(games.parlor().ticketTake(alex.player), "taken");
        assertEquals(295.0, money());
        assertEquals(100, host.balanceOf(alex.id), "no tokens moved");
        assertEquals(5, games.parlor().inToday(alex.id), "a ticket is money put in today");
        games.parlor().ticketGive(alex.id, 150);
        assertEquals(296.5, money(), "$1.50 back");
        assertEquals(5, games.parlor().ticketsToday()[0], "the owner's status: $5 in");
        assertEquals(150, games.parlor().ticketsToday()[1], "and $1.50 given back");
        host.move(5 * 3_600_000L); // past midnight
        assertEquals(0, games.parlor().inToday(alex.id), "yesterday's tickets aren't today's");
        assertEquals(0, games.parlor().ticketsToday()[0]);
    }

    @Test
    void ticketsAndGamesShareTheDaysMoneyLimit() {
        games.parlor().limit(12);
        assertEquals(0, play(10, 0), "$10 into a game");
        assertEquals(Parlor.limitReached(12), games.parlor().ticketTake(alex.player), "$10 + $5 would pass $12");
        assertEquals(290.0, money(), "the refused ticket took nothing");
        games.parlor().limit(15);
        assertNull(games.parlor().ticketTake(alex.player));
        assertNull(play(5, 0), "and the ticket counts against the next game");
    }

    @Test
    void aMoneyTicketNeedsEverythingMoneyPlayNeeds() {
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertEquals(Parlor.MONEY_NOT_YOURS, games.parlor().ticketTake(alex.player));
        alex.denied.clear();
        host.money.put(alex.id, 3.0);
        assertEquals(Refusal.Reason.BALANCE, games.parlor().ticketTake(alex.player).reason());
        assertEquals(3.0, money());
        host.money.put(alex.id, 300.0);
        games.parlorGuests(List.of());
        assertFalse(games.parlor().ticketForMoney(alex.player), "outside the Parlor it is the token ticket");
        games.parlorGuest(alex.id);
        games.parlor().money(false);
        assertFalse(games.parlor().ticketForMoney(alex.player), "money play off: the token ticket");
        games.parlor().money(true);
        games.parlor().ticket(0);
        assertFalse(games.parlor().ticketForMoney(alex.player), "set back to tokens by command");
        games.parlor().ticket(5);
        host.bankOn = false;
        assertFalse(games.parlor().ticketForMoney(alex.player), "no economy: the token ticket");
    }

    @Test
    void aPrizeTheBankWontTakeIsKeptAndPaidOnce() {
        host.bankRefusesGive = true;
        games.parlor().ticketGive(alex.id, 1250);
        assertEquals(300.0, money(), "not paid");
        games.parlor().payOwed(alex.player);
        assertEquals(300.0, money(), "still refused: still kept");
        host.bankRefusesGive = false;
        games.sweep();
        assertEquals(312.5, money(), "the sweep pays it");
        games.sweep();
        games.parlor().payOwed(alex.player);
        assertEquals(312.5, money(), "once");
    }

    @Test
    void aMoneyPrizeIsTheSameShareOfThePrice() {
        // the shipped ticket: 10 tokens, prizes 3, 10, 25 and 60, a pot from 50
        assertEquals(150, Parlor.prizeCents(3, 10, 5), "3 of 10 tokens is $1.50 of $5");
        assertEquals(500, Parlor.prizeCents(10, 10, 5));
        assertEquals(1250, Parlor.prizeCents(25, 10, 5));
        assertEquals(3000, Parlor.prizeCents(60, 10, 5));
        assertEquals(2500, Parlor.prizeCents(50, 10, 5), "the pot's 50 tokens are $25");
        assertEquals(0, Parlor.prizeCents(0, 10, 5));
        assertEquals(166, Parlor.prizeCents(1, 3, 5), "rounded down to a whole cent");
        assertEquals("$5", Parlor.money(500));
        assertEquals("$1.50", Parlor.money(150));
        assertEquals("$12.05", Parlor.money(1205));
        assertEquals("$0", Parlor.money(0));
    }

    @Test
    void aDaysTallyIsOnlyThatDays() {
        assertEquals(15, Parlor.dayTally("20000:15:300", 20000)[0]);
        assertEquals(300, Parlor.dayTally("20000:15:300", 20000)[1]);
        assertEquals(0, Parlor.dayTally("19999:15:300", 20000)[0], "yesterday's");
        assertEquals(0, Parlor.dayTally("nonsense", 20000)[0]);
        assertEquals(0, Parlor.dayTally(null, 20000)[0]);
    }

    @Test
    void aSkillGameIsNeverKeptToTheParlor() {
        slots.parlor = true;
        TestGame snake = GamesKit.skill("test_snake", "Test Snake");
        assertTrue(games.parlor().listed(snake, alex.player));
        Fake sam = new Fake("Sam");
        assertTrue(games.parlor().listed(snake, sam.player));
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
