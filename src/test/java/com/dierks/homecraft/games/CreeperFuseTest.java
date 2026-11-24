package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.fuse.CreeperFuse;
import com.dierks.homecraft.games.chance.fuse.CreeperFuse.Phase;
import com.dierks.homecraft.games.chance.fuse.CreeperFuseSettings;
import com.dierks.homecraft.games.chance.fuse.FuseMath;
import com.dierks.homecraft.games.chance.fuse.FuseTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.57.0: Creeper Fuse run for real over the framework's gate, rounds and database, with a fake bank:
 *
 * <ul>
 *   <li>everyone in the same fuse: the first put-in starts the wait, then it is lit and where it goes off is written
 *       into every place's round before the multiple moves;</li>
 *   <li>a cash-out pays the stake times the multiple then (whole tokens, rounded down) once; anyone still in when it
 *       goes off gets nothing; an auto cash-out pays exactly its multiple even when the climb jumps past it; a fuse
 *       that goes off at once pays nobody; one that burns out pays everyone still in at the top;</li>
 *   <li>crash safety: a restart mid-fuse gives back the stake of every place still in, pays nothing again to one that
 *       cashed out, and nothing is ever paid twice; stopping the game does the same; a player who leaves mid-fuse
 *       is cashed out at the multiple it had reached;</li>
 *   <li>dollars in the Game Parlor pay dollars; tokens and money never mix; the day's limits count the stakes.</li>
 * </ul>
 */
class CreeperFuseTest {

    private static final int TOP = 2000;

    private Host host;
    private GamesService games;
    private CreeperFuse fuse;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 24, 20, 0));
        host.bankOn = true;
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 6), "creeper_fuse",
                CreeperFuseSettings.defaults());
        games = GamesKit.service(host, List.of(CreeperFuse.SPEC));
        games.start();
        fuse = (CreeperFuse) games.game("creeper_fuse");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** A player in the Parlor with 200 tokens and $300. */
    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 200);
        host.money.put(f.id, 300.0);
        games.parlorGuest(f.id);
        return f;
    }

    /** A fuse seed whose fuse goes off at a multiple (hundredths) {@code where} says yes to. */
    private static long seedWhere(IntPredicate where) {
        for (long seed = 1; seed < 100_000; seed++) {
            if (where.test(FuseMath.boomAt(seed, CreeperFuseSettings.defaults().ratio(), TOP))) {
                return seed;
            }
        }
        throw new AssertionError("no such seed");
    }

    private void pass(long ms) {
        host.move(ms);
        fuse.tick();
    }

    /** Wait out the put-in window: the fuse is lit. */
    private void light() {
        pass(10_000);
        assertEquals(Phase.LIT, fuse.phase());
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private int open() throws Exception {
        return count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'");
    }

    private String dataOf(Fake f) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(
                "SELECT data FROM game_rounds WHERE player = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, f.id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- a whole fuse --------------------------------------------------------------------------------------

    @Test
    void twoPlayersOneCashesOutOneRidesItAndTheBalancesAddUp() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        long seed = seedWhere(b -> b > 260 && b < 400);
        int boom = FuseMath.boomAt(seed, CreeperFuseSettings.defaults().ratio(), TOP);
        fuse.seedNextFuse(seed);
        assertTrue(fuse.enter(alex.player, 10, false, 0), "Alex puts in 10 tokens");
        assertEquals(Phase.GATHERING, fuse.phase(), "the first put-in starts the wait");
        assertEquals(10, fuse.secondsLeft());
        host.move(2_000);
        assertTrue(fuse.enter(sam.player, 20, false, 0), "Sam puts in 20 tokens");
        assertFalse(fuse.enter(sam.player, 20, false, 0), "once each");
        assertEquals(190, host.balanceOf(alex.id));
        assertEquals(180, host.balanceOf(sam.id));
        assertEquals(2, open(), "each place is its own open round");
        assertFalse(fuse.cashOut(alex.player), "nothing to cash out before it is lit");
        pass(8_000);
        assertEquals(Phase.LIT, fuse.phase(), "lit when the wait is over");
        assertTrue(dataOf(alex).contains(";boom=" + boom), "where it goes off is in every round before it moves: "
                + dataOf(alex));
        assertTrue(dataOf(sam).contains(";fuse=" + seed + ";boom=" + boom));
        Fake late = player("Late");
        assertFalse(fuse.enter(late.player, 10, false, 0), "no put-in once it is lit");
        assertEquals(200, host.balanceOf(late.id));
        pass(6_000); // 2.00x
        assertEquals(200, fuse.multiple());
        assertTrue(fuse.cashOut(alex.player), "Alex cashes out at 2.00x");
        assertFalse(fuse.cashOut(alex.player), "once");
        assertEquals(190 + 20, host.balanceOf(alex.id), "10 in, 20 back");
        assertEquals(200, fuse.entry(alex.id).cashedAt());
        assertTrue(alex.heard().contains("10 tokens in → 20 tokens back"), alex.heard());
        while (fuse.phase() == Phase.LIT) {
            pass(50);
        }
        assertEquals(Phase.OVER, fuse.phase());
        assertEquals(boom, fuse.lastEnd(), "it went off where it was drawn to");
        assertFalse(fuse.burnedOut());
        assertTrue(fuse.entry(sam.id).caught());
        assertEquals(180, host.balanceOf(sam.id), "Sam was still in: nothing back");
        assertTrue(sam.heard().contains("went off at " + FuseMath.times(boom)), sam.heard());
        assertEquals(0, open(), "every round settled");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'"));
        assertTrue(dataOf(alex).endsWith(";out=200"));
        assertTrue(dataOf(sam).endsWith(";out=0"));
        pass(CreeperFuse.OVER_MS);
        assertEquals(Phase.IDLE, fuse.phase(), "the next fuse is ready");
        assertTrue(fuse.entries().isEmpty());
        assertEquals(0, host.severe());
    }

    @Test
    void aFuseThatGoesOffAtOncePaysNobody() throws Exception {
        Fake alex = player("Alex");
        fuse.seedNextFuse(seedWhere(b -> b == FuseMath.ONE));
        assertTrue(fuse.enter(alex.player, 10, false, 150));
        pass(10_000);
        assertEquals(Phase.OVER, fuse.phase(), "it went off the moment it was lit");
        assertEquals(FuseMath.ONE, fuse.lastEnd());
        assertEquals(190, host.balanceOf(alex.id));
        assertEquals(0, open());
    }

    @Test
    void anAutoCashOutPaysExactlyItsMultipleEvenWhenTheClimbJumpsPastIt() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        fuse.seedNextFuse(seedWhere(b -> b > 400 && b < 1000));
        assertTrue(fuse.enter(alex.player, 10, false, 150), "auto cash-out at 1.50x");
        assertTrue(fuse.enter(sam.player, 20, false, 0));
        assertTrue(fuse.setAuto(sam.player, 300), "Sam picks 3.00x while waiting");
        assertTrue(dataOf(sam).contains("auto=300"), "written to the round first: " + dataOf(sam));
        light();
        pass(5_000); // 1.78x: past 1.50x in one step
        assertTrue(fuse.multiple() > 150);
        assertEquals(150, fuse.entry(alex.id).cashedAt(), "paid at 1.50x exactly, not where the climb got to");
        assertEquals(190 + 15, host.balanceOf(alex.id));
        pass(10_000); // 6.35x: past 3.00x
        assertEquals(300, fuse.entry(sam.id).cashedAt());
        assertEquals(180 + 60, host.balanceOf(sam.id));
        assertFalse(fuse.setAuto(sam.player, 500), "nothing to change once cashed out");
    }

    @Test
    void aFuseThatBurnsOutPaysEveryoneStillInAtTheirTop() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        fuse.seedNextFuse(seedWhere(b -> b > TOP));
        assertTrue(fuse.enter(alex.player, 10, false, 0));
        assertTrue(fuse.enter(sam.player, 50, false, 0));
        light();
        pass(15_000); // 5.66x: past Sam's own top, where 50 tokens reach the 250-token cap
        assertEquals(501, fuse.entry(sam.id).cashedAt(), "50 tokens cash out by themselves at 5.01x");
        assertEquals(150 + 250, host.balanceOf(sam.id));
        while (fuse.phase() == Phase.LIT) {
            pass(500);
        }
        assertTrue(fuse.burnedOut(), "it climbed to the top and burned out");
        assertEquals(TOP, fuse.entry(alex.id).cashedAt());
        assertEquals(190 + 200, host.balanceOf(alex.id), "10 tokens at 20.00x");
        assertEquals(0, open());
    }

    // ---- crash safety --------------------------------------------------------------------------------------

    @Test
    void aRestartMidFuseGivesBackThoseStillInAndNeverPaysTwice() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        fuse.seedNextFuse(seedWhere(b -> b > 300));
        assertTrue(fuse.enter(alex.player, 10, false, 0));
        assertTrue(fuse.enter(sam.player, 20, true, 0), "Sam's $20");
        assertEquals(280.0, host.money.get(sam.id));
        light();
        pass(6_000);
        assertTrue(fuse.cashOut(alex.player));
        assertEquals(210, host.balanceOf(alex.id));
        assertEquals(1, open(), "Sam is still in");
        // the server dies here: nothing in memory survives, everyone is offline at the next start
        host.online.clear();
        GamesService after = GamesKit.service(host, List.of(CreeperFuse.SPEC));
        after.start();
        assertEquals(0, open(), "the start-up finished it");
        assertEquals(300.0, host.money.get(sam.id), "Sam's $20 came back whole (never cashed out)");
        assertEquals(210, host.balanceOf(alex.id), "Alex's cash-out was paid when it was made, and not again");
        // the old fuse, still ticking in this test, finds the round gone and pays nothing more
        while (fuse.phase() == Phase.LIT) {
            pass(200);
        }
        assertEquals(300.0, host.money.get(sam.id));
        GamesService again = GamesKit.service(host, List.of(CreeperFuse.SPEC));
        again.start();
        assertEquals(300.0, host.money.get(sam.id), "a second start finds nothing to pay");
        assertEquals(210, host.balanceOf(alex.id));
    }

    @Test
    void stoppingTheGameGivesBackEveryPlaceStillInOnce() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        fuse.seedNextFuse(seedWhere(b -> b > 300));
        assertTrue(fuse.enter(alex.player, 10, false, 0));
        assertTrue(fuse.enter(sam.player, 20, false, 0));
        light();
        pass(6_000);
        assertTrue(fuse.cashOut(alex.player));
        fuse.stop();
        assertEquals(200, host.balanceOf(sam.id), "the fuse was put out: Sam's 20 back");
        assertTrue(sam.heard().contains("put out"), sam.heard());
        assertEquals(210, host.balanceOf(alex.id));
        assertEquals(Phase.IDLE, fuse.phase());
        assertEquals(0, open());
        assertTrue(dataOf(sam).endsWith(";out=100"), "a cash-out at 1.00x: exactly the stake");
        games.rounds().settleOpen(sam.id);
        assertEquals(200, host.balanceOf(sam.id), "nothing more");
    }

    @Test
    void aPlayerWhoLeavesIsCashedOutWhileItBurnsAndGetsTheStakeBackBefore() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertTrue(fuse.enter(alex.player, 10, false, 0));
        assertTrue(fuse.enter(sam.player, 20, false, 0));
        host.online.remove(alex.id);
        games.onQuit(alex.player);
        assertEquals(200, host.balanceOf(alex.id), "left before it was lit: the stake back");
        assertNull(fuse.entry(alex.id));
        fuse.seedNextFuse(seedWhere(b -> b > 300));
        light();
        pass(6_000);
        host.online.remove(sam.id);
        games.onQuit(sam.player);
        assertEquals(180 + 40, host.balanceOf(sam.id), "left at 2.00x: cashed out there");
        assertEquals(0, open());
    }

    @Test
    void anOldOpenPlaceIsGivenBackBeforeANewOneGoesIn() throws Exception {
        Fake alex = player("Alex");
        ChanceRounds.Round old = games.rounds().open(alex.player, fuse, 10,
                FuseTicket.entry(10, 250, TOP, 0).data(), false);
        assertNotNull(old);
        assertEquals(190, host.balanceOf(alex.id));
        host.move(1_000);
        assertTrue(fuse.enter(alex.player, 20, false, 0));
        assertEquals(200 - 20, host.balanceOf(alex.id), "the old 10 came back, the new 20 went in");
        assertEquals(1, open());
    }

    // ---- money and limits ----------------------------------------------------------------------------------

    @Test
    void dollarsPayDollarsAndTheDaysLimitsCountTheStakes() throws Exception {
        Fake sam = player("Sam");
        Fake alex = player("Alex");
        fuse.seedNextFuse(seedWhere(b -> b > 250));
        assertNull(fuse.odds(5, true), "$5 is off: whole dollars rounded down could give back under 85");
        assertNotNull(fuse.odds(10, true));
        assertTrue(fuse.enter(sam.player, 10, true, 200), "$10, auto at 2.00x");
        assertTrue(fuse.enter(alex.player, 10, false, 0));
        assertEquals(290.0, host.money.get(sam.id));
        assertEquals(200, host.balanceOf(sam.id), "no tokens of Sam's");
        assertEquals(10, games.breaks().tokensInToday(alex.id), "the stake counts toward today's tokens");
        assertEquals(10, games.parlor().inToday(sam.id), "and the dollars toward today's money");
        light();
        pass(7_000);
        assertEquals(310.0, host.money.get(sam.id), "$10 in, $20 back");
        assertEquals(200, host.balanceOf(sam.id));
    }
}
