package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.chance.mobraces.MobRaces;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.MobRacesSettings;
import com.dierks.homecraft.games.chance.mobraces.RaceScript;
import com.dierks.homecraft.games.chance.mobraces.RaceTicket;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mob Races run for real over the framework's gate, rounds and database, with a fake bank (the owner: "you literally
 * see the tiny mobs running on the track"; here, the race behind what is seen):
 *
 * <ul>
 *   <li>everyone in the room is in the same race: the first entry starts the wait, anyone in the Parlor may enter
 *       until it ends, one racer and one stake each, tokens or dollars as each chose;</li>
 *   <li>every entry is its own round, put in through the play gate; when entries close one seed is drawn and every
 *       round is settled once, before the race is shown; the result is told when the winner is home;</li>
 *   <li>a refused stake doesn't enter; a second entry is refused; nobody enters while a race runs;</li>
 *   <li>an entrant who leaves before the start gets the stake back and the race goes on without them, or doesn't
 *       run if nobody is left; Start now by everyone starts at once;</li>
 *   <li>the day's limits count the stakes, and nothing is ever paid twice.</li>
 * </ul>
 */
class MobRacesTest {

    private Host host;
    private GamesService games;
    private MobRaces races;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 20, 20, 0));
        host.bankOn = true;
        open(MobRacesSettings.defaults(), 1000);
    }

    /** The games with these Mob Races settings and this many tokens a day for games of chance; the track is there. */
    private void open(MobRacesSettings settings, int chanceDailyTokens) {
        host.config = GamesKit.config(GamesKit.common(true, chanceDailyTokens, 600, 6), "mob_races", settings);
        games = GamesKit.service(host, List.of(MobRaces.SPEC));
        races = (MobRaces) games.game("mob_races");
        races.trackSeen();
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

    /** A race seed whose winner is {@code racer}. */
    private static long seedWhere(Racer racer) {
        for (long seed = 1; seed < 10_000; seed++) {
            if (RaceScript.winner(seed) == racer.lane()) {
                return seed;
            }
        }
        throw new AssertionError("no seed for " + racer);
    }

    /** Move the clock on and let the race see it (the Clubhouse still says the track is there). */
    private void pass(long ms) {
        host.move(ms);
        races.trackSeen();
        races.tick();
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private int open() throws Exception {
        return count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN'");
    }

    private int settled() throws Exception {
        return count("SELECT COUNT(*) FROM game_rounds WHERE state = 'SETTLED'");
    }

    // ---- a whole race --------------------------------------------------------------------------------------

    @Test
    void twoPlayersOneInTokensOneInDollarsAreInTheSameRaceAndEachIsSettledOnce() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertEquals(Phase.IDLE, races.phase());
        assertEquals(-1, races.secondsLeft());
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false), "Alex: 5 tokens on the Chicken");
        assertEquals(Phase.GATHERING, races.phase(), "the first entry starts the wait");
        assertEquals(20, races.secondsLeft());
        assertEquals(195, host.balanceOf(alex.id), "the stake went in when the entry was made");
        host.move(3_000);
        assertTrue(races.enter(sam.player, Racer.PIG, 10, true), "Sam: $10 on the Pig");
        assertEquals(290.0, host.money.get(sam.id), "Sam's dollars went in");
        assertEquals(200, host.balanceOf(sam.id), "and none of Sam's tokens: tokens and money never mix");
        assertEquals(300.0, host.money.get(alex.id), "nor any of Alex's money");
        assertEquals(17, races.secondsLeft(), "the wait is the first entry's: a later one does not start it again");
        assertEquals(2, open(), "each entry is its own open round");
        assertEquals(2, races.entries().size());
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN' AND money = 1 AND stake = 10"));
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN' AND money = 0 AND stake = 5"));
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE state = 'OPEN' AND data NOT LIKE '%race=%'"),
                "nothing about the result exists while picks can still be made");
        assertNull(races.script(), "no race is written yet");

        races.seedNextRace(seedWhere(Racer.CHICKEN));
        pass(16_000);
        assertEquals(Phase.GATHERING, races.phase(), "a second to go");
        pass(1_000);
        assertEquals(Phase.RUNNING, races.phase(), "the wait is over: they're off");
        assertEquals(0, open(), "every round was settled the moment the race was drawn, before it is shown");
        assertEquals(2, settled());
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds WHERE data LIKE '%race=" + seedWhere(Racer.CHICKEN)
                + "'"), "the one race seed is in both rounds");
        assertEquals(195 + 27, host.balanceOf(alex.id), "Alex's pick is paid: 5 in, 27 back");
        assertEquals(290.0, host.money.get(sam.id), "Sam's pick is not");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE money = 0 AND stake = 5 AND payout = 27"));
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE money = 1 AND stake = 10 AND payout = 0"));
        assertNotNull(races.script());
        assertNull(races.winner(), "the room is not told who wins before the race is run");
        assertFalse(alex.heard().contains("won"), "and nor is anyone in it: " + alex.heard());
        assertFalse(sam.heard().contains("won"), sam.heard());
        assertEquals(3, races.laps());

        pass(24_000);
        assertEquals(Phase.RUNNING, races.phase(), "still running a second before the finish");
        assertTrue(races.raceTick() > 400 && races.raceTick() < 500, "tick " + races.raceTick());
        pass(1_000);
        assertEquals(Phase.RESULT, races.phase(), "the winner is home");
        assertEquals(Racer.CHICKEN, races.winner());
        assertTrue(alex.heard().contains("1 Chicken won, and you picked it! 5 tokens in → 27 tokens back."),
                alex.heard());
        assertTrue(sam.heard().contains("1 Chicken won. You picked 2 Pig. No win this time."), sam.heard());
        pass(MobRaces.RESULT_MS - 1);
        assertEquals(Phase.RESULT, races.phase(), "the result stands a few seconds");
        pass(1);
        assertEquals(Phase.IDLE, races.phase(), "and the track is free again");
        assertTrue(races.entries().isEmpty());
        assertEquals(Racer.CHICKEN, races.lastWinner());

        // nothing more moved after the start, and nothing can be paid again
        games.rounds().settleOpen(alex.id);
        games.rounds().settleOpen(sam.id);
        games.rounds().sweep();
        assertEquals(222, host.balanceOf(alex.id), "paid once");
        assertEquals(290.0, host.money.get(sam.id));
        assertEquals(300.0, host.money.get(alex.id));
        assertEquals(200, host.balanceOf(sam.id));
        assertEquals(0, open(), "nothing is left open");
        assertEquals(2, settled());
        assertEquals(0, host.severe());
    }

    @Test
    void aWinningPickInDollarsIsPaidInDollars() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 20, false));
        assertTrue(races.enter(sam.player, Racer.PIG, 10, true));
        races.seedNextRace(seedWhere(Racer.PIG));
        pass(20_000);
        assertEquals(Phase.RUNNING, races.phase());
        assertEquals(290.0 + 54, host.money.get(sam.id), "$10 in, $54 back, straight to the bank");
        assertEquals(180, host.balanceOf(alex.id), "Alex's Chicken did not win: the 20 tokens stay in");
        assertEquals(200, host.balanceOf(sam.id), "a dollar win pays no tokens");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds WHERE owed > 0"), "nothing is left owed");
        pass(25_000);
        assertTrue(sam.heard().contains("2 Pig won, and you picked it! $10 in → $54 back."), sam.heard());
        assertTrue(alex.heard().contains("You picked 1 Chicken. No win this time."), alex.heard());
        assertEquals(0, open());
    }

    @Test
    void everyoneOnTheSameRacerIsPaidAndSoIsNobodyWhenAnotherWins() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        Fake kim = player("Kim");
        assertTrue(races.enter(alex.player, Racer.FOX, 5, false));
        assertTrue(races.enter(sam.player, Racer.FOX, 10, false));
        assertTrue(races.enter(kim.player, Racer.SHEEP, 5, false));
        races.seedNextRace(seedWhere(Racer.FOX));
        pass(20_000);
        assertEquals(195 + 27, host.balanceOf(alex.id));
        assertEquals(190 + 54, host.balanceOf(sam.id));
        assertEquals(195, host.balanceOf(kim.id));
        pass(25_000);
        pass(MobRaces.RESULT_MS);
        assertEquals(Phase.IDLE, races.phase());
        // the next race: nobody's pick wins
        host.move(1_000);
        assertTrue(races.enter(alex.player, Racer.COW, 5, false));
        races.seedNextRace(seedWhere(Racer.RABBIT));
        pass(20_000);
        assertEquals(222 - 5, host.balanceOf(alex.id));
        pass(25_000);
        assertEquals(Racer.RABBIT, races.winner());
        assertTrue(alex.heard().contains("3 Rabbit won. You picked 4 Cow. No win this time."), alex.heard());
        assertEquals(0, open());
        assertEquals(4, settled());
    }

    // ---- refusals ------------------------------------------------------------------------------------------

    @Test
    void aRefusedStakeDoesNotEnter() throws Exception {
        Fake poor = new Fake("Poor");
        host.online.put(poor.id, poor.player);
        host.give(poor.id, 3);
        games.parlorGuest(poor.id);
        assertFalse(races.enter(poor.player, Racer.CHICKEN, 5, false), "3 tokens don't cover 5");
        assertTrue(poor.heard().contains("You need 2 more tokens."), poor.heard());
        assertEquals(Phase.IDLE, races.phase(), "a refused stake starts no wait");
        assertEquals(3, host.balanceOf(poor.id));

        Fake alex = player("Alex");
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 7, false), "7 is not a stake the game offers");
        assertFalse(races.enter(alex.player, null, 5, false), "no racer picked");
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 7, true), "$7 is not an amount the chips make");
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 500, true), "$500 is over the biggest stake");
        alex.denied.add(Parlor.PERMISSION_MONEY);
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 10, true), "no money play without the permission");
        assertTrue(alex.heard().contains("Playing for money isn't open to you."), alex.heard());
        alex.denied.clear();
        alex.denied.add(PlayGate.PERMISSION_CHANCE);
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 5, false), "no games of chance without the permission");
        alex.denied.clear();
        host.bankOn = false;
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 10, true), "no money play without the server's bank");
        host.bankOn = true;

        // outside the Parlor nothing can be entered, in tokens or dollars
        games.parlorGuests(List.of());
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 10, true));
        assertTrue(alex.heard().contains("Mob Races is run on the track in the Clubhouse's Game Parlor."),
                alex.heard());
        games.parlorGuest(alex.id);

        // and with no track drawn there is nothing to race on
        host.move(60_000);
        assertFalse(races.trackUp(), "the Clubhouse has not said the track is there for a minute");
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertTrue(alex.heard().contains("Mob Races has no track here yet."), alex.heard());

        assertEquals(Phase.IDLE, races.phase());
        assertEquals(200, host.balanceOf(alex.id), "nothing was taken");
        assertEquals(300.0, host.money.get(alex.id));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "and no round was written");

        races.trackSeen();
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false), "with everything in place, it goes in");
    }

    @Test
    void aSecondEntryByTheSamePlayerIsRefused() throws Exception {
        Fake alex = player("Alex");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        host.move(2_000); // past the click cooldown: it is the race that refuses, not the clock
        assertFalse(races.enter(alex.player, Racer.PIG, 5, false), "one racer and one stake each race");
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 10, true), "not in dollars either");
        assertTrue(alex.heard().contains("You're in this race already."), alex.heard());
        assertEquals(195, host.balanceOf(alex.id), "one stake in");
        assertEquals(300.0, host.money.get(alex.id));
        assertEquals(1, open());
        assertEquals(1, races.entries().size());
        assertEquals(Racer.CHICKEN, races.entry(alex.id).pick());
        assertEquals(5, races.entry(alex.id).stake());
        assertEquals(27, races.entry(alex.id).pays());
    }

    @Test
    void nobodyEntersWhileARaceRunsOrItsResultStands() throws Exception {
        Fake alex = player("Alex");
        Fake late = player("Late");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        pass(20_000);
        assertEquals(Phase.RUNNING, races.phase());
        assertFalse(races.enter(late.player, Racer.PIG, 5, false), "the race is on");
        assertFalse(races.enter(late.player, Racer.PIG, 10, true));
        assertTrue(late.heard().contains("The race is on. You can enter the next one when it is over."), late.heard());
        races.open(late.player, null); // a tap on the table during the race: a word, and nothing to pick
        assertTrue(late.heard().contains("The race is on. Watch the track!"), late.heard());
        races.startNow(late.player); // nothing to start
        pass(25_000);
        assertEquals(Phase.RESULT, races.phase());
        assertFalse(races.enter(late.player, Racer.PIG, 5, false), "nor while the result stands");
        assertEquals(200, host.balanceOf(late.id));
        assertEquals(300.0, host.money.get(late.id));
        assertEquals(1, races.entries().size());
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds"));
        pass(MobRaces.RESULT_MS);
        assertEquals(Phase.IDLE, races.phase());
        assertTrue(races.enter(late.player, Racer.PIG, 5, false), "the next race takes them");
    }

    // ---- leaving, and starting early -----------------------------------------------------------------------

    @Test
    void anEntrantWhoLeavesTheServerGetsTheStakeBackAndTheRaceGoesOnWithoutThem() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertTrue(races.enter(sam.player, Racer.CHICKEN, 10, true));
        // Alex quits: the game drops them, then the framework finishes their round by the exit rule (the order a
        // running server does it in: every open game's onQuit, then the player's open rounds)
        host.online.remove(alex.id);
        races.onQuit(alex.player);
        games.onQuit(alex.player);
        assertEquals(200, host.balanceOf(alex.id), "no race was drawn: the stake came back whole");
        assertTrue(count("SELECT COUNT(*) FROM game_prefs WHERE value LIKE '%Mob Races%tokens back%'") == 1,
                "and a line waits for their next join");
        assertEquals(1, races.entries().size(), "the race goes on with Sam");
        assertEquals(1, open());
        races.seedNextRace(seedWhere(Racer.CHICKEN));
        pass(20_000);
        assertEquals(Phase.RUNNING, races.phase());
        assertEquals(200, host.balanceOf(alex.id), "Alex's Chicken won, but Alex was out: nothing more is paid");
        assertEquals(290.0 + 54, host.money.get(sam.id), "Sam's pick is paid");
        assertEquals(0, open());
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE money = 0 AND stake = 5 AND payout = 5"),
                "Alex's round: 5 in, 5 back");
    }

    @Test
    void aRaceNobodyIsLeftInDoesNotRun() throws Exception {
        Fake alex = player("Alex");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 10, true));
        assertEquals(290.0, host.money.get(alex.id));
        host.online.remove(alex.id);
        races.onQuit(alex.player);
        games.onQuit(alex.player);
        assertEquals(300.0, host.money.get(alex.id), "the dollars came back");
        assertTrue(races.entries().isEmpty(), "and they are out of the race");
        pass(1_000);
        assertEquals(Phase.IDLE, races.phase(), "nobody is left: the track is free");
        pass(30_000);
        assertEquals(Phase.IDLE, races.phase(), "and no race is run");
        assertNull(races.script());
        assertEquals(0, open());
        assertEquals(1, count("SELECT COUNT(*) FROM game_rounds WHERE money = 1 AND payout = 10 AND data NOT LIKE"
                + " '%race=%'"), "the one round came back with no race ever drawn");

        // a player who goes offline without the quit being heard yet: the race finishes their round itself, once
        Fake sam = player("Sam");
        host.move(1_000);
        assertTrue(races.enter(sam.player, Racer.PIG, 5, false));
        host.online.remove(sam.id);
        pass(1_000);
        assertEquals(200, host.balanceOf(sam.id), "the stake came back");
        assertEquals(Phase.IDLE, races.phase());
        games.onQuit(sam.player);
        games.rounds().settleOpen(sam.id);
        assertEquals(200, host.balanceOf(sam.id), "and only once");
        assertEquals(0, open());
    }

    @Test
    void anEntrantWhoWalksOutOfTheParlorIsOutOfTheRaceWithTheStakeBack() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertTrue(races.enter(sam.player, Racer.PIG, 10, true));
        games.parlorGuests(List.of(alex.id)); // the Clubhouse's word: only Alex is in the Parlor now
        pass(1_000);
        assertEquals(300.0, host.money.get(sam.id), "Sam's dollars came back");
        assertTrue(sam.heard().contains("You left the Game Parlor before the race, so your $10 came back."),
                sam.heard());
        assertEquals(1, races.entries().size());
        assertEquals(Phase.GATHERING, races.phase(), "the race goes on with Alex");
        assertEquals(1, open());
        games.parlorGuests(List.of());
        pass(1_000);
        assertEquals(200, host.balanceOf(alex.id));
        assertTrue(alex.heard().contains("so your 5 tokens came back."), alex.heard());
        assertEquals(Phase.IDLE, races.phase(), "nobody is left in the room: no race");
        assertEquals(0, open());
        assertEquals(2, settled());
    }

    @Test
    void startNowByEveryoneStartsAtOnce() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        Fake kim = player("Kim");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertTrue(races.enter(sam.player, Racer.PIG, 5, false));
        races.startNow(kim.player); // not in the race: nothing
        races.startNow(alex.player);
        assertEquals(Phase.GATHERING, races.phase(), "Sam hasn't asked");
        assertTrue(races.entry(alex.id).startNow());
        assertEquals(2, open());
        pass(1_000);
        assertEquals(Phase.GATHERING, races.phase());
        races.startNow(alex.player); // asking twice is asking once
        assertEquals(Phase.GATHERING, races.phase());
        races.startNow(sam.player);
        assertEquals(Phase.RUNNING, races.phase(), "everyone asked: no more waiting, on that very click");
        assertEquals(0, open());
        assertEquals(2, settled());
        assertEquals(0, races.raceTick());

        // a table of one never waits once they ask; and nobody waits past the wait itself
        pass(25_000);
        pass(MobRaces.RESULT_MS);
        assertEquals(Phase.IDLE, races.phase());
        assertTrue(races.enter(kim.player, Racer.FOX, 5, false));
        races.startNow(kim.player);
        assertEquals(Phase.RUNNING, races.phase());
        pass(25_000);
        pass(MobRaces.RESULT_MS);
        host.move(1_000);
        assertTrue(races.enter(kim.player, Racer.FOX, 5, false));
        host.move(500);
        assertTrue(races.enter(alex.player, Racer.COW, 5, false));
        races.startNow(alex.player);
        pass(19_400);
        assertEquals(Phase.GATHERING, races.phase(), "Kim never asks: the wait runs");
        pass(200);
        assertEquals(Phase.RUNNING, races.phase(), "but no longer than the wait");
    }

    // ---- the day's limits ----------------------------------------------------------------------------------

    @Test
    void theDaysLimitsCountTheStakes() throws Exception {
        MobRacesSettings d = MobRacesSettings.defaults();
        // two races a day, and 30 tokens a day for all games of chance
        open(new MobRacesSettings(true, d.stakes(), 2, d.rtp(), d.gatherSeconds(), d.raceSeconds(), d.odds()), 30);
        Fake alex = player("Alex");
        assertEquals(2, races.racesLeft(alex.player));
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 20, false));
        assertEquals(20, games.breaks().tokensInToday(alex.id), "the stake counts against the day's tokens");
        assertEquals(1, races.racesLeft(alex.player));
        races.startNow(alex.player);
        pass(25_000);
        pass(MobRaces.RESULT_MS);
        host.move(1_000);
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 20, false), "20 more would pass 30 for the day");
        assertTrue(alex.heard().contains("That's your limit for today (30 tokens)."), alex.heard());
        assertEquals(Phase.IDLE, races.phase());
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 10, true), "dollars have their own day's limit");
        assertEquals(10, games.parlor().inToday(alex.id), "and the stake counts against it");
        assertEquals(20, games.breaks().tokensInToday(alex.id), "not against the day's tokens");
        assertEquals(0, races.racesLeft(alex.player));
        races.startNow(alex.player);
        pass(25_000);
        pass(MobRaces.RESULT_MS);
        host.move(1_000);
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 5, false), "two races a day, and that was two");
        assertTrue(alex.heard().contains("That's all your plays of Mob Races for today."), alex.heard());
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 5, true), "in dollars too");
        assertEquals(2, count("SELECT COUNT(*) FROM game_rounds"));

        // the Parlor's own day limit for money
        Fake sam = player("Sam");
        games.parlor().limit(15);
        assertFalse(races.enter(sam.player, Racer.PIG, 25, true), "$25 is over $15 a day");
        assertTrue(sam.heard().contains("That's your money limit for today ($15)."), sam.heard());
        assertTrue(races.enter(sam.player, Racer.PIG, 10, true));
        assertEquals(290.0, host.money.get(sam.id));
    }

    @Test
    void takingABreakKeepsAPlayerOutAndTheRestartHoldTakesNoEntry() throws Exception {
        Fake alex = player("Alex");
        assertTrue(games.breaks().pause(alex.id, 1), "Alex takes a break for a day");
        assertFalse(races.enter(alex.player, Racer.CHICKEN, 5, false));
        assertTrue(alex.heard().contains("taking a break"), alex.heard());
        assertEquals(Phase.IDLE, races.phase());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
    }

    // ---- the exit rule, over real rounds -------------------------------------------------------------------

    @Test
    void aRoundLeftOpenIsFinishedFromItsOwnData() throws Exception {
        Fake alex = player("Alex");
        Fake sam = player("Sam");
        // no race drawn: the stake comes back (a crash while entries were being taken)
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        games.rounds().settleOpen(alex.id);
        assertEquals(200, host.balanceOf(alex.id), "5 in, 5 back");
        assertTrue(alex.heard().contains("5 tokens back"), alex.heard());
        pass(1_000);
        pass(20_000);
        assertEquals(Phase.IDLE, races.phase(), "the race finds that round gone and does not run for nobody");
        assertNull(races.script());
        assertEquals(200, host.balanceOf(alex.id), "and pays nothing more");

        // a race drawn but not yet settled (a crash between the two): the round pays what that race pays its pick
        long seed = seedWhere(Racer.PIG);
        host.move(1_000);
        assertTrue(races.enter(sam.player, Racer.PIG, 10, true));
        assertTrue(races.enter(alex.player, Racer.COW, 5, false));
        ChanceRounds.Round samsRound = games.rounds().openRound(sam.id, "mob_races");
        ChanceRounds.Round alexsRound = games.rounds().openRound(alex.id, "mob_races");
        assertNotNull(games.rounds().step(samsRound, RaceTicket.read(samsRound.data()).in(seed).data()));
        assertNotNull(games.rounds().step(alexsRound, RaceTicket.read(alexsRound.data()).in(seed).data()));
        games.rounds().settleOpen(sam.id);
        games.rounds().settleOpen(alex.id);
        assertEquals(290.0 + 54, host.money.get(sam.id), "the Pig won that race: $54 back");
        assertEquals(195, host.balanceOf(alex.id), "the Cow did not: nothing back");
        assertEquals(0, open());
        // the race, finding both rounds finished elsewhere, runs for nobody and pays nothing again
        pass(20_000);
        assertEquals(Phase.IDLE, races.phase());
        assertEquals(344.0, host.money.get(sam.id));
        assertEquals(195, host.balanceOf(alex.id));
        assertEquals(0, host.severe());
    }

    @Test
    void anOldOpenEntryIsFinishedBeforeANewOneGoesIn() throws Exception {
        Fake alex = player("Alex");
        // an entry left open from before (the game was rebuilt under it: a reload)
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        open(MobRacesSettings.defaults(), 1000); // a new game object: its memory of the race is gone
        games.parlorGuest(alex.id);
        assertEquals(1, open());
        host.move(2_000);
        assertTrue(races.enter(alex.player, Racer.PIG, 10, false), "the old entry is finished first, then this one");
        assertEquals(200 - 10, host.balanceOf(alex.id), "the old 5 came back, the new 10 went in");
        assertEquals(1, open());
        assertEquals(1, settled());
    }

    @Test
    void stoppingMidRaceTellsItsEntrantsHowItCameOut() throws Exception {
        Fake alex = player("Alex");
        assertTrue(races.enter(alex.player, Racer.CHICKEN, 5, false));
        races.seedNextRace(seedWhere(Racer.CHICKEN));
        races.startNow(alex.player);
        assertEquals(Phase.RUNNING, races.phase());
        races.stop();
        assertEquals(Phase.IDLE, races.phase());
        assertTrue(alex.heard().contains("1 Chicken won, and you picked it!"), "they were paid at the start, so they"
                + " are told: " + alex.heard());
        assertEquals(222, host.balanceOf(alex.id));
    }

    @Test
    void whatItPublishesIsTheExactReturn() {
        List<String> lines = races.oddsLines();
        assertTrue(lines.get(0).contains("gives back about 90 of every 100 tokens"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("5 in") && l.contains("90.0%") && l.contains("27")),
                lines.toString());
        assertEquals(27, races.odds(5, false).pays());
        assertEquals(54, races.odds(10, true).pays(), "dollars are solved the same way");
        assertEquals(270, races.odds(50, true).pays());
        assertNull(races.odds(7, false));
        assertNull(races.odds(0, true));
        assertEquals(4, races.rules().size());
        assertEquals("Mob Races", races.name());
        assertEquals(com.dierks.homecraft.arcade.TokenService.Source.ARCADE_MOB_RACES, races.source());
        assertTrue(races.featurable() == false, "never the featured game: it is a game of chance");
    }
}
