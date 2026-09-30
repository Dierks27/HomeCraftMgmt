package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole Race Night run start to finish with no server (EVENTS-DROPPER-SPEC §A.2-§A.4, §A.9;
 * EVENTS-RECONCILED 1-2; owner decision D3): a fake clock, fake racers and a fake Time Trials behind
 * {@link NightPorts}, and a real database. Pinned here: three racers race three times and are paid
 * 5, 3 and 1 (the podium rule), once each; the grid for a later race puts the fewest points in
 * front; a racer who isn't free is tried again until just before Go and is then DNS for that race;
 * a disconnect scores 0 and is pulled back in for the next race; Leave game is leaving for good; too
 * few racers calls the night off; a stop while racing settles on the races done; the fourth prize
 * night of a week is just for fun; the shared warm-up ends when everyone is ready; and a prize
 * that can't be paid now is owed and paid once later.
 */
class NightRunnerTest {

    private static final long MIN = 60_000L;
    /** The start: Fri 2 Oct 2026 19:00 UTC. */
    private static final long T = 1_790_967_600_000L;
    private static final String ID = "rn-20261002-1900";
    private static final String SEASON = "rnseason:2026-10";

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    private Connection conn;
    private GamesDao games;
    private EventDao dao;
    private Ports ports;
    private Payer payer;
    private NightRunner runner;

    /** The fake server: the clock, who is online and free, and a Time Trials that seats whoever is free. */
    private static final class Ports implements NightPorts {
        long now = T - 11 * MIN;
        long tick = 1_000;
        final Set<UUID> online = new HashSet<>();
        final Set<UUID> busy = new HashSet<>();
        final Map<UUID, List<String>> told = new HashMap<>();
        final Map<UUID, Course.Spot> seatedAt = new HashMap<>();
        final Map<UUID, Course.Spot> regridded = new HashMap<>();
        final List<UUID> parked = new ArrayList<>();
        final List<UUID> home = new ArrayList<>();
        final Map<UUID, String> homeLines = new HashMap<>();
        /** How often each racer's freedom was asked (a seat attempt asks it). */
        final Map<UUID, Integer> asked = new HashMap<>();
        boolean restartHeld;
        final Map<UUID, String> titles = new HashMap<>();
        final Map<UUID, Boolean> progressed = new LinkedHashMap<>();
        final List<String> announced = new ArrayList<>();
        final Map<UUID, Long> season = new HashMap<>();
        boolean reserved;
        RaceLink link;

        @Override
        public long now() {
            return now;
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public boolean online(UUID player) {
            return online.contains(player);
        }

        @Override
        public boolean free(UUID player) {
            asked.merge(player, 1, Integer::sum);
            return online.contains(player) && !busy.contains(player) && !seatedAt.containsKey(player);
        }

        @Override
        public boolean restartHeld() {
            return restartHeld;
        }

        @Override
        public String name(UUID player) {
            return names.get(player);
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
            this.link = link;
            seatedAt.put(racer, grid);
            return null;
        }

        @Override
        public void regrid(UUID racer, Course raced, Course.Spot grid) {
            regridded.put(racer, grid);
        }

        @Override
        public void park(UUID racer) {
            parked.add(racer);
        }

        @Override
        public void home(UUID racer, EndReason why, String line) {
            home.add(racer);
            homeLines.put(racer, line);
            seatedAt.remove(racer);
            if (link != null) {
                link.left(racer, why); // Time Trials ends the session, which tells the link
            }
        }

        @Override
        public boolean reserve(String courseId, Object holder, String line) {
            reserved = true;
            return true;
        }

        @Override
        public void release(String courseId, Object holder) {
            reserved = false;
        }

        @Override
        public void endSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void warnSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void tell(UUID player, String line, boolean queueIfOffline) {
            told.computeIfAbsent(player, k -> new ArrayList<>()).add(line);
        }

        @Override
        public void title(UUID player, String big, String small) {
            titles.put(player, big + " | " + small);
        }

        @Override
        public void bar(UUID player, String line, float progress, boolean lastLap) {
        }

        @Override
        public void watchers(String line) {
        }

        @Override
        public void announce(Announcer.Line line, String text, Collection<UUID> racers) {
            announced.add(line.name());
        }

        @Override
        public void changed() {
        }

        @Override
        public long points(UUID player, String board) {
            return season.getOrDefault(player, 0L);
        }

        @Override
        public void progress(UUID player, boolean won) {
            progressed.put(player, won);
        }

        @Override
        public void log(String line, boolean warn) {
        }

        /** Whether the player was told something containing {@code words}. */
        boolean heard(UUID player, String words) {
            for (String l : told.getOrDefault(player, List.of())) {
                if (l.contains(words)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<UUID, String> names = Map.of(A, "Ava", B, "Ben", C, "Cal", D, "Dee");

    /** The rewards: pays whoever can earn now, each ref once. */
    private static final class Payer implements PayLoop.Payer {
        final Set<UUID> canEarn = new HashSet<>();
        final Map<String, Integer> paid = new LinkedHashMap<>();

        @Override
        public int pay(UUID player, String ref, int tokens, String detail) {
            if (!canEarn.contains(player)) {
                return -1;
            }
            if (paid.containsKey(player + ref)) {
                return 0;
            }
            paid.put(player + ref, tokens);
            return tokens;
        }

        @Override
        public boolean paid(UUID player, String ref) {
            return paid.containsKey(player + ref);
        }

        int to(UUID player) {
            return paid.getOrDefault(player + "event:" + ID, 0);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        games = new GamesDao(db);
        dao = new EventDao(db, games);
        ports = new Ports();
        payer = new Payer();
        for (UUID u : List.of(A, B, C, D)) {
            ports.online.add(u);
            payer.canEarn.add(u);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private static Course loop() {
        List<Course.Mark> lap = List.of(new Course.Mark(20, 64, 20, 4), new Course.Mark(-20, 64, 20, 4),
                new Course.Mark(-20, 64, -20, 4), new Course.Mark(16, 64, -20, 4));
        List<Course.Mark> two = new ArrayList<>(lap);
        two.addAll(lap);
        return new Course("ice", TrialKind.BOAT, "Ice Loop", Tier.EASY, "games",
                new Course.Spot(20, 64, -16, 0, 0), two, new Course.Mark(20, 64, -14, 4), null, 30, true, false, 1);
    }

    private static List<Course.Spot> grid() {
        List<Course.Spot> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            out.add(new Course.Spot(20 + (i % 2 == 0 ? -1.5 : 1.5), 64, -20 - 4 * (i / 2), 0, 0));
        }
        return out;
    }

    private static NightRules rules(int warmupSeconds) {
        return new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5, 4, 3, 2), 2, 1, List.of(5, 3, 2), 1, false,
                warmupSeconds, 60, 4, 20);
    }

    private NightRunner night(NightRules rules) {
        EventPlan plan = new EventPlan(ID, "ice", T - 10 * MIN, T, rules, false, "");
        NightRunner r = new NightRunner(plan, new NightRunner.Track(loop(), "Ice Loop", grid(), new Point(0, 70, 0)),
                dao, ports, new PayLoop(dao, payer, () -> ports.now, Logger.getAnonymousLogger()), ZoneOffset.UTC,
                SEASON, 30, EventMachine.State.scheduled());
        r.prizeWeek(() -> 2920L, 3);
        return r;
    }

    /** Run the server tick by tick (50 ms each) until {@code at}. */
    private void runUntil(long at) {
        while (ports.now < at) {
            ports.now += 50;
            ports.tick++;
            runner.tick();
        }
    }

    private void open() {
        runner = night(rules(0));
        runUntil(T - 10 * MIN + 1_000);
        assertEquals(EventMachine.Phase.OPEN, runner.phase(), "the window is open at T - 10");
    }

    private void join(UUID... who) {
        for (UUID u : who) {
            assertNull(runner.join(u, names.get(u)), names.get(u) + " joins");
        }
    }

    /** Race the current race with finishes in this order, a second apart; then wait out the break. */
    private void race(UUID... order) {
        runUntil(ports.now + 40_000);
        assertEquals(EventMachine.Phase.RACING, runner.phase(), "racing");
        long ms = 40_000;
        for (UUID u : order) {
            runner.finished(u, ms, true, null);
            ms += 1_000;
            runUntil(ports.now + 1_000);
        }
    }

    // ---- the whole night ----------------------------------------------------------------------------

    @Test
    void threeRacersRaceThreeTimesAndArePaidFiveThreeAndOneOnce() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        assertEquals(3, ports.seatedAt.size(), "every free racer is on the grid by Go");
        assertEquals(EventMachine.Phase.RACING, runner.phase(), "race 1 is off");
        assertEquals(3, runner.started(), "3 started race 1");
        assertTrue(ports.titles.get(A).contains("Prizes: 5, 3, 2 tokens"), "a prize night says so at Go: "
                + ports.titles.get(A));
        race(A, B, C); // 10, 8, 6
        assertEquals(EventMachine.Phase.BREAK, runner.phase(), "everyone in: the break");
        assertTrue(ports.parked.containsAll(List.of(A, B, C)), "each finisher waits on the stand");
        runUntil(ports.now + 21_000);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "after the break, race 2's grid");
        runUntil(ports.now + 500);
        assertEquals(grid().get(0), ports.regridded.get(C), "the fewest points tonight (Cal, 6) start at the front");
        assertEquals(grid().get(2), ports.regridded.get(A), "the most (Ava, 10) at the back");
        race(A, B, C); // 20, 16, 12
        runUntil(ports.now + 21_000);
        race(B, A, C); // 28, 26, 18
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.DONE, runner.phase(), "three races: settled and done");

        assertEquals(5, payer.to(A), "1st: 5 tokens");
        assertEquals(3, payer.to(B), "2nd: 3 tokens (3 racers started)");
        assertEquals(1, payer.to(C), "3rd needs 4 racers, so Cal gets the finisher's 1");
        assertEquals(3, payer.paid.size(), "one payment each, under the night's ref");
        EventDao.EventRow row = dao.event(ID);
        assertEquals(EventDao.DONE, row.state(), "stored DONE");
        assertTrue(row.prized(), "it held one of the week's prize slots");
        assertEquals(3, row.racesDone(), "three races stored");
        for (EventDao.EntryRow e : dao.entries(ID)) {
            assertNotNull(e.paidAt(), e.name() + "'s prize is recorded as paid");
        }
        assertEquals(28L, games.best(A, EventDao.GAME, SEASON), "the season board has Ava's 28 points");
        assertEquals(18L, games.best(C, EventDao.GAME, SEASON), "and Cal's 18");
        assertEquals(28L, games.best(A, EventDao.GAME, EventCopy.nightBoard(ID)), "the night's board too");
        assertTrue(ports.home.containsAll(List.of(A, B, C)), "everyone goes home with their things");
        assertEquals(Boolean.TRUE, ports.progressed.get(A), "Ava won the night (achievements)");
        assertEquals(Boolean.FALSE, ports.progressed.get(C), "Cal raced it");
        assertFalse(ports.reserved, "the track is let go");

        // a second settle (a crash before DONE) never pays twice
        assertEquals(0, new PayLoop(dao, payer, () -> ports.now, Logger.getAnonymousLogger()).payNight(ID),
                "nothing is left to pay");
        assertEquals(3, payer.paid.size(), "and nobody was paid again");
    }

    @Test
    void aRacerWhoIsNotFreeIsTriedAgainThenIsDnsAndMakesTheNextRace() throws Exception {
        open();
        join(A, B, C);
        ports.busy.add(C); // in another game
        runUntil(T - 5_000);
        assertTrue(ports.heard(C, "Stand still, or use Leave game, to join"), "Cal is asked to get free");
        runUntil(T + 250);
        assertFalse(ports.seatedAt.containsKey(C), "never seated for race 1");
        assertTrue(ports.heard(C, "you'll be in the next one"), "DNS for race 1, told why");
        assertEquals(2, runner.started(), "2 started race 1");
        ports.busy.remove(C);
        race(A, B);
        runUntil(ports.now + 21_000);
        runUntil(ports.now + 500);
        assertTrue(ports.seatedAt.containsKey(C), "free again before race 2's grid: Cal is in");
        List<EventDao.RaceRow> rows = dao.races(ID);
        assertTrue(rows.stream().anyMatch(r -> r.player().equals(C) && r.race() == 1 && "DNS".equals(r.result())),
                "race 1 stored Cal as DNS: " + rows);
    }

    @Test
    void aDisconnectScoresNothingAndIsPulledBackInForTheNextRace() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        runUntil(ports.now + 10_000);
        ports.online.remove(C);
        ports.seatedAt.remove(C);
        runner.left(C, EndReason.DISCONNECT);
        race(A, B);
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.BREAK, runner.phase(), "the two left in are in: the break");
        assertTrue(dao.races(ID).stream().anyMatch(r -> r.player().equals(C) && "LEFT".equals(r.result())
                && r.points() == 0), "Cal's race 1 is LEFT with 0 points");
        assertTrue(runner.in(C), "a disconnect isn't leaving: Cal is still on the list");
        ports.online.add(C);
        runUntil(ports.now + 21_000);
        runUntil(ports.now + 500);
        assertTrue(ports.seatedAt.containsKey(C), "back and free: pulled in for race 2");
    }

    @Test
    void leaveGameIsLeavingForGood() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        ports.seatedAt.remove(B);
        runner.left(B, EndReason.QUIT_ITEM);
        assertFalse(runner.in(B), "Leave game takes Ben off the night");
        assertEquals(EventDao.LEFT, dao.entries(ID).stream().filter(e -> e.player().equals(B)).findFirst().orElseThrow()
                .status(), "stored LEFT");
        race(A, C);
        runUntil(ports.now + 21_000);
        runUntil(ports.now + 500);
        assertFalse(ports.seatedAt.containsKey(B), "never re-gridded, though free");
        assertNull(ports.regridded.get(B), "never re-gridded");
        assertEquals("You left tonight's Race Night - see you next time!", runner.joinProblem(B),
                "and can't join again once the racing began");
    }

    @Test
    void tooFewRacersCallsTheNightOff() throws Exception {
        open();
        join(A);
        runUntil(T);
        assertEquals(EventMachine.Phase.CALLED_OFF, runner.phase(), "one racer: called off");
        assertEquals(EventDao.CALLED_OFF, dao.event(ID).state(), "stored CALLED_OFF");
        assertTrue(ports.heard(A, "needs 2 racers"), "Ava is told why");
        assertTrue(payer.paid.isEmpty(), "nothing paid");
    }

    @Test
    void aStopWhileRacingSettlesOnTheRacesDone() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        race(A, B, C);
        assertEquals(EventMachine.Phase.BREAK, runner.phase(), "in the break after race 1");
        runner.stopNow("&7Race Night was called off - the server is restarting.", "the server restarted");
        assertEquals(EventMachine.Phase.CALLED_OFF, runner.phase(), "called off at once");
        EventDao.EventRow row = dao.event(ID);
        assertEquals(EventDao.CALLED_OFF, row.state(), "stored CALLED_OFF");
        assertEquals(1, row.racesDone(), "on 1 race");
        assertEquals(5, payer.to(A), "race 1's points stand: Ava 1st, 5 tokens");
        assertEquals(3, payer.to(B), "Ben 2nd, 3");
        assertEquals(1, payer.to(C), "Cal finished a race: 1");
        assertTrue(ports.home.containsAll(List.of(A, B, C)), "everyone home with their things");
    }

    /**
     * fx2-C #13: a night made before the week's rollover (a long heads-up, or an admin's start in 600)
     * that goes after it claims its prize night in the week race 1 goes in, as the screen shows it:
     * a full last week doesn't make it just for fun, and it doesn't use up last week's slots.
     */
    @Test
    void thePrizeNightIsClaimedInTheWeekRace1GoesIn() throws Exception {
        for (int i = 1; i <= 3; i++) { // last week used all three of its prize nights
            String other = "rn-2026092" + i + "-1900";
            dao.open(new EventDao.EventRow(other, "ice", T - 5 * 86_400_000L, T - 5 * 86_400_000L, EventDao.DONE, "",
                    3, false, "", "", T, T, ""));
            assertTrue(dao.claimPrizeSlot(other, "2919", 3), "last week's prize night " + i);
        }
        long[] week = {2919}; // the night is made on the last evening of last week
        EventPlan plan = new EventPlan(ID, "ice", T - 10 * MIN, T, rules(0), false, "");
        runner = new NightRunner(plan, new NightRunner.Track(loop(), "Ice Loop", grid(), new Point(0, 70, 0)), dao,
                ports, new PayLoop(dao, payer, () -> ports.now, Logger.getAnonymousLogger()), ZoneOffset.UTC, SEASON,
                30, EventMachine.State.scheduled());
        runner.prizeWeek(() -> week[0], 3);
        runUntil(T - 10 * MIN + 1_000);
        join(A, B, C);
        week[0] = 2920; // the week turns over before race 1's Go
        runUntil(T + 250);
        assertTrue(runner.prizeNight(), "the new week has all its prize nights: this is one");
        assertTrue(ports.titles.get(A).contains("Prizes: 5, 3, 2 tokens"), "and Go says so: " + ports.titles.get(A));
        assertEquals(1, dao.prizedIn("2920"), "it uses the new week's slot");
        assertEquals(3, dao.prizedIn("2919"), "never last week's");
    }

    @Test
    void theFourthPrizeNightOfAWeekIsJustForFun() throws Exception {
        for (int i = 1; i <= 3; i++) {
            String other = "rn-2026092" + i + "-1900";
            dao.open(new EventDao.EventRow(other, "ice", T - 5 * 86_400_000L, T - 5 * 86_400_000L, EventDao.DONE, "",
                    3, false, "", "", T, T, ""));
            assertTrue(dao.claimPrizeSlot(other, "2920", 3), "prize night " + i + " of the week");
        }
        open();
        join(A, B, C);
        runUntil(T + 250);
        assertFalse(runner.prizeNight(), "no slot left: just for fun");
        assertTrue(ports.titles.get(A).contains(RacePrizes.JUST_FOR_FUN), "the Go subtitle says so: " + ports.titles.get(A));
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.DONE, runner.phase(), "done");
        assertTrue(payer.paid.isEmpty(), "a just-for-fun night pays no tokens");
        assertEquals(30L, games.best(A, EventDao.GAME, SEASON), "but its points still go on the season board");
    }

    @Test
    void theWarmUpEndsWhenEveryoneIsReady() throws Exception {
        runner = night(rules(180));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B);
        runUntil(T - 14_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "T - 15 s: into the shared warm-up");
        assertTrue(runner.warmupUntil() > ports.tick, "the warm-up has an end tick for Time Trials");
        runner.ready(A);
        runUntil(ports.now + 2_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "one ready is not everyone");
        runner.ready(B);
        runUntil(ports.now + 500);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "everyone ready: to the grid");
        assertEquals(0L, runner.warmupUntil(), "the warm-up is over");
        runUntil(T - 1_000);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "but race 1 never goes before the advertised start");
        runUntil(T + 250);
        assertEquals(EventMachine.Phase.RACING, runner.phase(), "then the countdown and Go");
    }

    @Test
    void aPrizeThatCantBePaidNowIsOwedAndPaidOnceLater() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        payer.canEarn.remove(C); // somewhere tokens can't be earned when the night settles
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 1_000);
        assertEquals(0, payer.to(C), "not paid yet");
        List<EventDao.EntryRow> owed = dao.owed(C);
        assertEquals(1, owed.size(), "Cal's prize is owed");
        PayLoop loop = new PayLoop(dao, payer, () -> ports.now, Logger.getAnonymousLogger());
        payer.canEarn.add(C);
        assertEquals(0, loop.payOwed(C), "paid at Cal's next join");
        assertEquals(1, payer.to(C), "the finisher's 1 token");
        assertEquals(0, loop.payOwed(C), "a second join pays nothing");
        assertEquals(1, payer.paid.values().stream().filter(v -> v == 1).count(), "once");
    }

    /**
     * fx2-C #6: a racer who is online but can't be paid where they are when the night settles (watching
     * live in spectator from the Clubhouse, in creative) reads that their prize is waiting, as an
     * offline one does at their next join; a racer who was paid reads nothing of the kind.
     */
    @Test
    void anOnlineRacerWhosePrizeIsOwedIsToldItIsWaiting() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        payer.canEarn.remove(A); // Ava is watching the others live when the night settles
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.DONE, runner.phase(), "settled");
        assertEquals(1, dao.owed(A).size(), "Ava's prize is owed");
        assertTrue(ports.heard(A, "Your Race Night prize is waiting"), "and she is told so: " + ports.told.get(A));
        assertFalse(ports.heard(B, "Your Race Night prize is waiting"), "Ben was paid: nothing is waiting for him");
    }

    @Test
    void aLateJoinerIsSeatedBeforeGoAndAFullNightSaysSo() throws Exception {
        runner = night(new NightRules(1, 0, 2, 3, List.of(10, 8, 6), 2, 1, List.of(5, 3, 2), 1, false, 0, 60, 4, 20));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B);
        runUntil(T - 10_000);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "on the grid");
        join(C);
        runUntil(T - 5_000);
        assertTrue(ports.seatedAt.containsKey(C), "a late joiner still makes race 1");
        assertEquals("Race Night is full (3). Tap Watch to see it.", runner.joinProblem(D), "3 of 3: full");
    }

    // ---- the fixes (EV fix stage, R2 review) --------------------------------------------------------

    @Test
    void everyoneIsOnTheWayHomeWithTheNightsOwnLineBeforeItsEndIsHeard() throws Exception {
        open();
        join(A, B, C);
        List<UUID> homeAtEnd = new ArrayList<>();
        runner.onEnd(r -> homeAtEnd.addAll(ports.home)); // RaceNight's onEnd forgets the night: nothing ticks it after
        runUntil(T + 250);
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 21_000);
        race(A, B, C);
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.DONE, runner.phase(), "done");
        assertTrue(homeAtEnd.containsAll(List.of(A, B, C)), "everyone was sent home before the end was heard: " + homeAtEnd);
        assertEquals(NightRunner.DONE_LINE, ports.homeLines.get(A), "reading the night's own goodbye");
        assertEquals(NightRunner.DONE_LINE, runner.calledOffLine(), "and race mode's line for a night that ended is the same");
    }

    @Test
    void anAdminCancelSendsEveryoneHomeWithoutAnotherTick() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        List<UUID> homeAtEnd = new ArrayList<>();
        runner.onEnd(r -> homeAtEnd.addAll(ports.home));
        runner.callOff("&7Race Night was called off by an admin.", "admin cancel"); // EventAdmin's cancel: no drain after
        assertTrue(homeAtEnd.containsAll(List.of(A, B, C)), "everyone on the way home at once: " + homeAtEnd);
        assertEquals("&7Race Night was called off.", ports.homeLines.get(A), "with the called-off line");
    }

    @Test
    void oneReadyRacerNeverEndsTheWarmUpWhileAJoinedRacerIsntAtTheTrackYet() throws Exception {
        runner = night(rules(180));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B, C);
        ports.busy.add(C); // still in another game
        runUntil(T - 14_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "the shared warm-up");
        runner.ready(A);
        runner.ready(B);
        runUntil(ports.now + 3_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "Cal joined but isn't here: the warm-up waits for Cal");
        ports.busy.remove(C);
        runUntil(ports.now + 1_500);
        assertTrue(ports.seatedAt.containsKey(C), "free: Cal is taken to the track");
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "and Cal gets to warm up too");
        runner.ready(C);
        runUntil(ports.now + 500);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "everyone joined is ready: to the grid");
        runUntil(T - 1_000);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "race 1 still waits for the advertised start");
    }

    @Test
    void aRestartDueSoonEndsTheWarmUpAtOnce() throws Exception {
        runner = night(rules(180));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B);
        runUntil(T - 14_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "the shared warm-up");
        ports.restartHeld = true;
        runUntil(ports.now + 500);
        assertEquals(EventMachine.Phase.GRID, runner.phase(), "the restart hold: straight to the grid");
    }

    @Test
    void aNightWhereNobodyFinishedPaysNothingAndNobodyWins() throws Exception {
        open();
        join(A, B, C);
        while (!runner.phase().over() && ports.now < T + 20 * MIN) {
            runUntil(ports.now + 1_000); // nobody crosses the line: each race runs out of time
        }
        assertEquals(EventMachine.Phase.DONE, runner.phase(), "three races, all run out");
        assertTrue(payer.paid.isEmpty(), "still-racing points alone win no prize: " + payer.paid);
        for (UUID u : List.of(A, B, C)) {
            assertEquals(Boolean.FALSE, ports.progressed.get(u), names.get(u) + " raced, but nobody won");
            assertTrue(ports.heard(u, "nobody finished a race tonight"), names.get(u) + " hears there's no winner");
        }
    }

    @Test
    void aVoidedFinishNeverStartsTheFinishWindow() throws Exception {
        open();
        join(A, B);
        runUntil(T + 250);
        runUntil(ports.now + 40_000);
        runner.finished(A, 40_000, false, "flying");
        runUntil(ports.now + 65_000);
        assertEquals(EventMachine.Phase.RACING, runner.phase(), "a void isn't a finish: Ben races on past the window");
        runner.finished(B, 110_000, true, null);
        runUntil(ports.now + 1_000);
        assertEquals(EventMachine.Phase.BREAK, runner.phase(), "everyone in: the break");
    }

    @Test
    void aLeaveBeforeTheRacingCancelsASeatStillQueued() throws Exception {
        runner = night(rules(180));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B);
        runUntil(T - 14_000);
        assertEquals(EventMachine.Phase.WARMUP, runner.phase(), "the shared warm-up");
        join(C); // a late joiner: queued to be seated
        runner.leave(C); // and changes their mind before the queue gets to them
        runUntil(ports.now + 2_000);
        assertFalse(ports.seatedAt.containsKey(C), "never taken to the track");
        assertFalse(runner.in(C), "and off the list");
    }

    @Test
    void aRacerBackAfterLeavingTheWarmUpLeavesForGoodWithLeaveGame() throws Exception {
        runner = night(rules(180));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B, C);
        runUntil(T - 14_000);
        runUntil(ports.now + 1_000);
        runner.leave(C); // at the track: out, and sent home
        runUntil(ports.now + 100);
        assertTrue(ports.home.contains(C), "Cal went home");
        assertNull(runner.join(C, "Cal"), "the racing hasn't begun: Cal may join again");
        runUntil(ports.now + 1_000);
        assertTrue(ports.seatedAt.containsKey(C), "and is back at the track");
        runner.left(C, EndReason.QUIT_ITEM); // Leave game this time
        assertFalse(runner.in(C), "Leave game is leaving for good, even after being sent home once");
    }

    @Test
    void aRacerWhoIsntFreeIsTriedAgainOnceASecond() throws Exception {
        open();
        join(A, B, C);
        ports.busy.add(C);
        runUntil(T - 14_000);
        int before = ports.asked.getOrDefault(C, 0);
        runUntil(ports.now + 10_000);
        int tries = ports.asked.getOrDefault(C, 0) - before;
        assertTrue(tries >= 9 && tries <= 11, "about once a second, not every 5 ticks: " + tries + " in 10 s");
    }

    @Test
    void aNightCalledOffInRaceOneGivesItsPrizeSlotBack() throws Exception {
        open();
        join(A, B, C);
        runUntil(T + 250);
        assertTrue(dao.event(ID).prized(), "race 1's Go claimed one of the week's prize nights");
        runner.callOff("&7Race Night was called off by an admin.", "admin cancel");
        assertFalse(dao.event(ID).prized(), "nothing was raced: the slot is given back");
        assertEquals(0, dao.prizedIn("2920"), "so the week still has all its prize nights");
    }

    @Test
    void anAdminGoStoresTheMovedStart() throws Exception {
        runner = night(rules(0));
        runner.restore(List.of(new EventDao.EntryRow(ID, A, "Ava", T - 11 * MIN, EventDao.IN, 0, null, 0, null),
                new EventDao.EntryRow(ID, B, "Ben", T - 11 * MIN, EventDao.IN, 0, null, 0, null)));
        assertEquals(EventMachine.Phase.SCHEDULED, runner.phase(), "resumed before its window: scheduled");
        assertNull(runner.goNow(), "an admin's go");
        assertEquals(ports.now + EventMachine.GRID_LEAD_MS, dao.event(ID).startsAt(),
                "the row has the moved start, so a boot never resumes a night that already started");
        assertEquals(runner.startsAt(), dao.event(ID).startsAt(), "the same start the night runs to");
    }

    @Test
    void theLiveNightAndARecoveredOneAgreeAndAWarmUpAloneIsNoRace() throws Exception {
        runner = night(rules(180));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B, C, D);
        runUntil(T - 14_000);
        runUntil(ports.now + 2_000);
        ports.seatedAt.remove(D);
        runner.left(D, EndReason.QUIT_ITEM); // at the track for the warm-up only, then Leave game
        for (UUID u : List.of(A, B, C)) {
            runner.ready(u);
        }
        runUntil(T + 250);
        assertEquals(EventMachine.Phase.RACING, runner.phase(), "race 1 is off");
        race(A, B, C);
        List<EventDao.RaceRow> rows = dao.races(ID);
        assertEquals("DNS", rows.stream().filter(r -> r.player().equals(D)).findFirst().orElseThrow().result(),
                "Dee never started race 1: DNS, not LEFT");
        assertEquals(3, runner.started(), "three started race 1, as the stored rows say");
        runner.stopNow("&7Race Night was called off - the server is restarting.", "the server restarted");
        List<EventDao.Placed> recovered = StoredNight.placed(dao.races(ID), dao.entries(ID), rules(180), true);
        List<NightStandings.Ranked> live = runner.standings();
        assertEquals(live.size(), recovered.size(), "the same racers have a place live and recovered");
        for (int i = 0; i < live.size(); i++) {
            assertEquals(live.get(i).player(), recovered.get(i).player(), "the same order");
            assertEquals(live.get(i).place(), recovered.get(i).place(), "the same place");
        }
        assertTrue(live.stream().noneMatch(r -> r.player().equals(D)), "a warm-up alone gets no place");
        assertFalse(ports.progressed.containsKey(D), "and no Race Night achievement");
    }

    @Test
    void theLiveFinishLineSaysThePlaceByTimeAndTheLastRaceMakesNoPromise() throws Exception {
        runner = night(new NightRules(1, 0, 2, 8, List.of(10, 8, 6), 2, 1, List.of(5, 3, 2), 1, false, 0, 60, 4, 20));
        runUntil(T - 10 * MIN + 1_000);
        join(A, B, C);
        ports.busy.add(C);
        runUntil(T + 250);
        assertTrue(ports.heard(C, "You weren't free when the last race began."), "one race: no 'next one' promised");
        assertFalse(ports.heard(C, "next one"), "never a promise that can't be kept");
        runUntil(ports.now + 40_000);
        runner.finished(A, 45_200, true, null);
        runner.finished(B, 45_100, true, null); // told later, but faster (the interpolated crossing)
        assertTrue(ports.heard(B, "You came 1st!"), "Ben's place is by time, as it is stored: " + ports.told.get(B));
    }
}
