package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.twentyone.TableRound;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneSettings;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable.Phase;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable.Seat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.47.0, the Game Parlor's Twenty-One table, run for real over the framework's gate, rounds and database (the owner:
 * "play as a group around the table", "unlimited time unless the other players at the table hit a 'Hurry Up' Button
 * ... Then they have like 30 seconds"):
 *
 * <ul>
 *   <li>up to four sit; the cards come when every seat is ready; a table of one never waits;</li>
 *   <li>every seat's stake goes in through the play gate as its own round, and every hand is paid once, tokens or
 *       dollars as that seat chose;</li>
 *   <li>nobody is on a clock until someone taps Hurry Up; then a seat that isn't ready sits out, and a seat that
 *       hasn't played stands, after 30 seconds;</li>
 *   <li>a player who leaves mid-hand is finished by the exit rule and the table goes on without them.</li>
 * </ul>
 */
class TwentyOneTableTest {

    private Host host;
    private GamesService games;
    private TwentyOne game;
    private TwentyOneTable table;
    private final List<Fake> players = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.bankOn = true;
        TwentyOneSettings settings = TwentyOneSettings.defaults();
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "twenty_one", settings);
        games = GamesKit.service(host, List.of(TwentyOne.SPEC));
        game = (TwentyOne) games.game("twenty_one");
        table = game.table();
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 200);
        host.money.put(f.id, 300.0);
        games.parlorGuest(f.id);
        players.add(f);
        return f;
    }

    private Seat sit(Fake f) {
        return table.sit(f.player, () -> { });
    }

    /** A shoe whose first deal to {@code seats} seats leaves every seat a move (no two-card 21 anywhere). */
    private static long plainShoe(int seats) {
        List<com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Terms> terms = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            terms.add(TwentyOneSettings.defaults().odds(5).terms());
        }
        for (long seed = 1; seed < 10_000; seed++) {
            TableRound r = new TableRound(new com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Shoe(seed, 0),
                    terms);
            boolean plain = !r.over();
            for (int i = 0; i < seats; i++) {
                plain &= r.hand(i).playing();
            }
            if (plain) {
                return seed;
            }
        }
        throw new AssertionError("no plain deal in 10,000 shoes");
    }

    /** Play the round out: every seat stands when its turn comes. */
    private void everyoneStands() {
        for (int guard = 0; guard < 10 && table.phase() == Phase.PLAYING; guard++) {
            Seat turn = table.turn();
            if (turn == null) {
                break;
            }
            for (Fake f : players) {
                if (f.id.equals(turn.id())) {
                    table.stand(f.player);
                }
            }
        }
    }

    @Test
    void aTableOfOneDealsAsSoonAsTheyAreReady() throws Exception {
        Fake alex = player("Alex");
        Seat seat = sit(alex);
        assertNotNull(seat);
        assertEquals(5, seat.stake(), "the smallest stake to start with");
        assertEquals(Phase.OPEN, table.phase());
        table.seedNextShoe(plainShoe(1));
        table.ready(alex.player, true);
        assertNotNull(table.round(), "the cards are out");
        assertEquals(195, host.balanceOf(alex.id), "the stake went in");
        everyoneStands();
        assertEquals(Phase.OPEN, table.phase(), "the round is over: the table takes stakes again");
        assertTrue(table.round().over());
        TableRound.Hand hand = table.round().hand(seat.hand());
        assertEquals(195 + hand.payout(), host.balanceOf(alex.id), "and the hand was paid once");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"), "no round is left open");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED' AND payout = " + hand.payout()));
    }

    @Test
    void theCardsComeWhenEverySeatIsReady() {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        table.ready(alex.player, true);
        assertNull(table.round(), "Sam isn't ready: no cards yet");
        assertEquals(200, host.balanceOf(alex.id), "and no stake has gone in");
        assertTrue(table.waitingOn(table.seat(sam.id)));
        assertFalse(table.waitingOn(table.seat(alex.id)));
        table.ready(alex.player, false);
        assertFalse(table.seat(alex.id).ready(), "ready can be taken back");
        table.ready(alex.player, true);
        table.stake(sam.id, 10, false);
        table.seedNextShoe(plainShoe(2));
        table.ready(sam.player, true);
        assertNotNull(table.round());
        assertEquals(2, table.round().seats(), "one hand each, from one shoe");
        assertEquals(195, host.balanceOf(alex.id));
        assertEquals(190, host.balanceOf(sam.id), "each seat its own stake");
        assertEquals(0, table.seat(alex.id).hand());
        assertEquals(1, table.seat(sam.id).hand(), "in the order they sat");
    }

    @Test
    void theSeatsPlayInTurnAndOneSeatCantPlayForAnother() {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        // deal until both have a move (a two-card 21 would skip a turn)
        for (int n = 0; n < 50; n++) {
            table.ready(alex.player, true);
            table.ready(sam.player, true);
            if (table.phase() == Phase.PLAYING && table.turn() != null && table.turn().id().equals(alex.id)
                    && table.round().hand(1).playing()) {
                break;
            }
            everyoneStands();
            host.move(1_000);
        }
        assertEquals(alex.id, table.turn().id());
        int before = table.round().hand(0).cards().size();
        table.hit(sam.player);
        table.stand(sam.player);
        assertEquals(alex.id, table.turn().id(), "Sam's clicks do nothing on Alex's turn");
        assertEquals(before, table.round().hand(0).cards().size());
        table.stand(alex.player);
        assertEquals(sam.id, table.turn().id(), "then Sam");
        table.stand(sam.player);
        assertEquals(Phase.OPEN, table.phase());
        assertTrue(table.round().over(), "the Arcade played once for both");
    }

    @Test
    void nobodyIsOnAClockUntilSomeoneAsks() {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        table.ready(alex.player, true);
        host.move(20 * 60_000L); // twenty minutes: Sam is choosing
        table.tick();
        assertNull(table.round(), "no cards: nobody asked the table to hurry");
        assertEquals(-1, table.hurrySeconds());
        assertFalse(table.canHurry(table.seat(sam.id)), "the seat being waited on can't hurry anyone");
        assertTrue(table.canHurry(table.seat(alex.id)));
        table.hurry(alex.player);
        assertEquals(30, table.hurrySeconds());
        assertEquals("Alex", table.hurryBy());
        host.move(29_000);
        table.tick();
        assertNull(table.round(), "29 seconds: still waiting");
        host.move(2_000);
        table.tick();
        assertNotNull(table.round(), "30 seconds are up: the cards come");
        assertEquals(1, table.round().seats(), "and Sam sits this one out");
        assertEquals(-1, table.seat(sam.id).hand());
        assertEquals(200, host.balanceOf(sam.id), "nothing of Sam's went in");
        assertEquals(-1, table.hurrySeconds(), "the clock is cleared once the table moves");
    }

    @Test
    void aSlowPlayerStandsThirtySecondsAfterHurryUp() {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        for (int n = 0; n < 50; n++) {
            table.ready(alex.player, true);
            table.ready(sam.player, true);
            if (table.phase() == Phase.PLAYING && table.turn() != null && table.turn().id().equals(alex.id)
                    && table.round().hand(1).playing()) {
                break;
            }
            everyoneStands();
            host.move(1_000);
        }
        assertEquals(alex.id, table.turn().id());
        host.move(8 * 60_000L);
        table.tick();
        assertEquals(alex.id, table.turn().id(), "eight minutes on: still Alex's move, and nothing swept the hand");
        assertFalse(table.canHurry(table.seat(alex.id)));
        table.hurry(sam.player);
        host.move(31_000);
        table.tick();
        assertEquals(sam.id, table.turn().id(), "Alex stood: it is Sam's move");
        assertTrue(table.round().hand(0).stood());
        assertEquals(-1, table.hurrySeconds(), "Sam is not on a clock: nobody asked");
        table.stand(sam.player);
        assertTrue(table.round().over());
    }

    @Test
    void fourSitAndTheFifthIsTurnedAway() {
        for (String name : List.of("A", "B", "C", "D")) {
            assertNotNull(sit(player(name)));
        }
        assertNull(table.sit(player("E").player, () -> { }), "the table seats four");
        assertEquals(4, table.seats().size());
        table.leave(players.get(0).id);
        assertEquals(3, table.seats().size(), "getting up between rounds frees the seat");
        assertNotNull(sit(players.get(4)));
    }

    @Test
    void eachSeatPlaysForTokensOrForMoneyAsItChose() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        table.stake(sam.id, 25, true);
        assertNotNull(table.odds(table.seat(sam.id)), "a $25 hand is solved like any other");
        table.seedNextShoe(plainShoe(2));
        table.ready(alex.player, true);
        table.ready(sam.player, true);
        assertNotNull(table.round());
        assertEquals(275.0, host.money.get(sam.id), "Sam's $25 came out of the bank");
        assertEquals(200, host.balanceOf(sam.id), "and no tokens of Sam's moved");
        assertEquals(195, host.balanceOf(alex.id), "Alex plays for tokens at the same table");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE money = 1 AND stake = 25"));
        everyoneStands();
        TableRound.Hand sams = table.round().hand(table.seat(sam.id).hand());
        assertEquals(275.0 + sams.payout(), host.money.get(sam.id), "paid in dollars, once");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE owed > 0"));
    }

    @Test
    void aSeatWhoseStakeIsRefusedSitsOutAndTheOthersPlay() {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        table.ready(alex.player, true);
        table.ready(sam.player, true);
        everyoneStands();
        host.move(1_000);
        // Sam's tokens are gone before the next deal
        host.give(sam.id, -host.balanceOf(sam.id));
        table.ready(sam.player, true);
        assertFalse(table.seat(sam.id).ready(), "the gate says no at Ready, and says why");
        assertTrue(sam.heard().contains("more token"), sam.heard());
        table.ready(alex.player, true);
        assertEquals(Phase.OPEN, table.phase(), "Sam isn't ready, so nothing is dealt yet");
        table.hurry(alex.player);
        host.move(31_000);
        table.tick();
        assertEquals(1, table.round().seats(), "Alex plays; Sam watches");
    }

    @Test
    void aPlayerWhoLeavesTheServerMidHandIsFinishedAndTheTableGoesOn() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        sit(alex);
        sit(sam);
        for (int n = 0; n < 50; n++) {
            table.ready(alex.player, true);
            table.ready(sam.player, true);
            if (table.phase() == Phase.PLAYING && table.turn() != null && table.turn().id().equals(alex.id)
                    && table.round().hand(1).playing()) {
                break;
            }
            everyoneStands();
            host.move(1_000);
        }
        int before = count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'");
        // Alex quits: the framework finishes their open round by the exit rule (they stand)
        host.online.remove(alex.id);
        table.leave(alex.id);
        games.rounds().settleOpen(alex.id);
        assertEquals(before + 1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'"),
                "Alex's hand was settled by its own data");
        host.move(1_500);
        table.tick();
        assertEquals(sam.id, table.turn().id(), "the table moved on to Sam");
        table.stand(sam.player);
        assertEquals(Phase.OPEN, table.phase());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"));
        assertNull(table.seat(alex.id), "and Alex's seat is free");
        assertEquals(before + 2, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'"),
                "nothing was paid twice");
    }

    @Test
    void closingTheScreenMidHandKeepsTheSeatAndTheHand() {
        Fake alex = player("Alex");
        sit(alex);
        for (int n = 0; n < 50; n++) {
            table.ready(alex.player, true);
            if (table.phase() == Phase.PLAYING) {
                break;
            }
            host.move(1_000);
        }
        assertEquals(Phase.PLAYING, table.phase());
        table.leave(alex.id);
        assertTrue(table.seat(alex.id).away(), "the hand waits for them");
        Seat back = sit(alex);
        assertFalse(back.away());
        assertEquals(alex.id, table.turn().id(), "and is still theirs to play");
        table.stand(alex.player);
        assertEquals(Phase.OPEN, table.phase());
    }

    @Test
    void aTableEveryoneWalkedAwayFromIsClearedForTheNextPlayer() throws Exception {
        Fake alex = player("Alex");
        sit(alex);
        for (int n = 0; n < 50; n++) {
            table.ready(alex.player, true);
            if (table.phase() == Phase.PLAYING) {
                break;
            }
            host.move(1_000);
        }
        table.leave(alex.id); // away, cards out, nobody at the table
        Fake sam = player("Sam");
        assertNotNull(sit(sam));
        assertEquals(Phase.OPEN, table.phase(), "the table starts clean");
        assertEquals(1, table.seats().size());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'"), "Alex's hand stood and was paid");
        assertTrue(alex.heard().contains("finished for you"), alex.heard());
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
