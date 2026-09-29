package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.TokenService.Source;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupSource;
import com.dierks.homecraft.games.cup.CupText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup's tables (schema v36) against a real SQLite (EVENTS-OWNER-DECISIONS §D2).
 *
 * <p>Pinned here: an entry is ONE transaction (the {@code GAMES_CUP_ENTRY} debit and its row land
 * together or not at all; a player who can't pay, a second entry the same week, a week that is over
 * and a Cup already called off write nothing); a Cup time is set only by a strictly faster counted
 * run that started after the entry, in the Cup's own weeks, and never after the Cup is settled; a
 * settlement pays the table exactly once, in one transaction with its row and every entrant's
 * queued line, straight to the balance and outside the skill caps, and the ledger nets exactly the
 * top-up; a void refunds everyone in full and closes the week; and a crash in the middle of a
 * settlement leaves nothing behind, so the next boot settles it whole, once.
 */
class CupDaoTest {

    /** The week of Monday 2026-09-28 (a local epoch day). */
    private static final long WEEK = 20724L;
    private static final CupKey CUP = new CupKey("sky_rings", WEEK);
    private static final long NOW = 1_790_000_000_000L;
    private static final String NAME = "Sky Rings";

    private Connection conn;
    private Database db;
    private CupDao cup;
    private TokenDao tokens;
    private GamesDao games;
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final UUID carol = new UUID(0, 3);
    private final UUID dave = new UUID(0, 4);

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        cup = new CupDao(db);
        tokens = new TokenDao(db);
        games = new GamesDao(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void give(UUID p, int n) throws SQLException {
        tokens.change(p, n, "ADMIN", "seed", 1);
    }

    private int balance(UUID p) throws SQLException {
        return tokens.get(p).tokens();
    }

    private int count(String sql) throws SQLException {
        return count(conn, sql);
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String one(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** The sum of every Weekly Cup ledger line: what the Cups gave out, less what they took in. */
    private static int cupNet(Connection c) throws SQLException {
        return count(c, "SELECT COALESCE(SUM(delta), 0) FROM token_ledger WHERE source IN ('GAMES_CUP_ENTRY', "
                + "'GAMES_CUP_PRIZE', 'GAMES_CUP_REFUND')");
    }

    private CupRefusal enter(UUID p) throws SQLException {
        return cup.enter(CUP, p, 5, NAME, NOW, WEEK, "0;0;0;0;h:elytra:1");
    }

    /** A counted run of {@code ms} that finished at {@code at}, counting for the Cup's week. */
    private int run(UUID p, long ms, long at) throws SQLException {
        return cup.run(CUP.course(), p, ms, at, CupRules.Weeks.of(WEEK));
    }

    private static CupDao.Words words() {
        return (plan, line) -> "&6" + CupText.result(plan, line, NAME);
    }

    // ---- entering -----------------------------------------------------------------------------

    @Test
    void anEntryIsTheDebitAndItsRowInOneTransaction() throws Exception {
        give(alice, 20);
        assertNull(enter(alice), "a player with the tokens is in");
        assertEquals(15, balance(alice), "the entry is a plain token spend of 5");
        assertEquals("GAMES_CUP_ENTRY", one("SELECT source FROM token_ledger WHERE delta = -5"),
                "the entry is written under its own ledger source");
        for (CupSource src : CupSource.values()) {
            assertEquals(src.name(), Source.valueOf(src.name()).name(), "every Cup source is a C1 TokenService source");
        }
        assertEquals("Weekly Cup entry: Sky Rings", one("SELECT detail FROM token_ledger WHERE delta = -5"),
                "the ledger says what it was for, without colour codes");
        CupEntry e = cup.entry(CUP, alice);
        assertNotNull(e, "the entry row is there");
        assertEquals(5, e.paid(), "the row records what was paid");
        assertEquals(NOW, e.enteredAt(), "and when");
        assertFalse(e.hasTime(), "a new entry has no Cup time yet");
        assertEquals("0;0;0;0;h:elytra:1", cup.layout(CUP), "the first entry remembers the layout the Cup is raced on");
        assertEquals(List.of(CUP), cup.openKeys(), "the Cup is running");
    }

    @Test
    void enteringTwiceInAWeekIsRefusedAndPaysOnce() throws Exception {
        give(alice, 50);
        assertNull(enter(alice));
        assertEquals(CupRefusal.ALREADY_IN, enter(alice), "once per course per week");
        assertEquals(45, balance(alice), "the second try took nothing");
        assertEquals(1, count("SELECT COUNT(*) FROM cup_entries"), "one row");
        assertEquals(1, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_ENTRY'"), "one debit");
        assertNull(cup.enter(new CupKey("lava_leap", WEEK), alice, 5, "Lava Leap", NOW, WEEK, null),
                "another course's Cup the same week is another entry");
        CupKey next = new CupKey(CUP.course(), WEEK + 7);
        assertNull(cup.enter(next, alice, 5, NAME, NOW + 7 * 86_400_000L, WEEK + 7, null),
                "the same course next week is another entry");
        assertEquals(35, balance(alice), "three entries in all");
    }

    @Test
    void aPlayerWhoCantPayWritesNothing() throws Exception {
        give(alice, 3);
        assertEquals(CupRefusal.NOT_ENOUGH_TOKENS, enter(alice), "3 tokens don't pay a 5-token entry");
        assertEquals(3, balance(alice), "nothing was taken");
        assertEquals(0, count("SELECT COUNT(*) FROM cup_entries"), "no row");
        assertEquals(1, count("SELECT COUNT(*) FROM token_ledger"), "only the seed line");
        assertNull(cup.layout(CUP), "no layout is remembered for a Cup nobody got into");
    }

    @Test
    void aWeekThatIsOverOrAlreadyEndedTakesNoEntries() throws Exception {
        give(alice, 50);
        give(bob, 50);
        assertEquals(CupRefusal.WEEK_OVER, cup.enter(CUP, alice, 5, NAME, NOW, WEEK + 7, null),
                "a screen built before the rollover and clicked after it can't enter last week's Cup");
        assertNull(enter(alice));
        assertNotNull(cup.voidCup(CUP, CupPlan.VoidReason.CHANGED, NAME, NOW, words()));
        assertEquals(CupRefusal.CALLED_OFF, enter(bob), "a Cup called off takes no more entries this week");
        CupKey other = new CupKey("lava_leap", WEEK);
        assertNull(cup.enter(other, alice, 5, "Lava Leap", NOW, WEEK, null));
        assertNotNull(cup.settle(other, 10, "Lava Leap", NOW, words()));
        assertEquals(CupRefusal.WEEK_OVER, cup.enter(other, bob, 5, "Lava Leap", NOW, WEEK, null),
                "a Cup settled early takes no more entries");
        assertEquals(50, balance(bob), "bob paid for nothing");
        assertEquals(CupRefusal.OFF, cup.enter(new CupKey("x", WEEK), bob, 0, "X", NOW, WEEK, null),
                "an entry under 1 token is no Cup at all");
    }

    @Test
    void whenTheEntryRowCantBeWrittenTheDebitIsRolledBack() throws Exception {
        give(alice, 20);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER no_entries BEFORE INSERT ON cup_entries BEGIN SELECT RAISE(ABORT, 'disk full'); END");
        }
        assertThrows(SQLException.class, () -> enter(alice), "the row failed, so the entry failed");
        assertEquals(20, balance(alice), "the debit went with it: one transaction");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_ENTRY'"),
                "no ledger line for an entry that didn't happen");
        assertNull(cup.layout(CUP), "nor a remembered layout");
    }

    @Test
    void theEntrySourcesAreNotTakeABreaksChanceSources() {
        for (String s : List.of("GAMES_CUP_ENTRY", "GAMES_CUP_PRIZE", "GAMES_CUP_REFUND")) {
            assertFalse(GamesDao.CHANCE_SOURCES.contains(s),
                    s + " is a skill contest's, so it never counts toward the chance limit (Take a break)");
        }
    }

    @Test
    void aCupEntryIsNotCountedAsTokensPutIntoGamesOfChance() throws Exception {
        give(alice, 20);
        assertNull(enter(alice));
        assertEquals(0, games.chanceTokensToday(alice, 0), "Take a break's personal limit never sees a Cup entry");
    }

    // ---- Cup times ------------------------------------------------------------------------------

    @Test
    void aCupTimeIsTheBestCountedRunStartedAfterEntering() throws Exception {
        give(alice, 20);
        assertNull(enter(alice));
        assertEquals(0, run(alice, 40_000, NOW + 30_000), "a run that started before the entry doesn't count");
        assertEquals(1, run(alice, 40_000, NOW + 60_000), "a run that started after it does");
        CupEntry e = cup.entry(CUP, alice);
        assertEquals(40_000, e.bestMs(), "it is the Cup time");
        assertEquals(NOW + 60_000, e.bestAt(), "set when it finished");
        assertEquals(0, run(alice, 41_000, NOW + 200_000), "a slower run changes nothing");
        assertEquals(0, run(alice, 40_000, NOW + 300_000), "an equal run keeps the earlier time (the tie-break)");
        assertEquals(NOW + 60_000, cup.entry(CUP, alice).bestAt(), "still the first time set");
        assertEquals(1, run(alice, 39_000, NOW + 400_000), "a faster run replaces it");
        assertEquals(39_000, cup.entry(CUP, alice).bestMs());
        assertEquals(0, cup.run(CUP.course(), alice, 30_000, NOW + 500_000, CupRules.Weeks.of(WEEK + 7)),
                "a run that counts for another week sets nothing here");
        assertEquals(0, cup.run(CUP.course(), alice, 30_000, NOW + 500_000, CupRules.Weeks.NONE),
                "a run that counts for no week sets nothing");
        assertEquals(0, cup.run("lava_leap", alice, 30_000, NOW + 500_000, CupRules.Weeks.of(WEEK)),
                "a run on another course sets nothing");
        assertEquals(0, run(bob, 30_000, NOW + 500_000), "a player who isn't in has no Cup time to set");
    }

    @Test
    void aSettledCupTakesNoMoreTimes() throws Exception {
        give(alice, 20);
        give(bob, 20);
        assertNull(enter(alice));
        assertNull(enter(bob));
        run(alice, 40_000, NOW + 60_000);
        run(bob, 41_000, NOW + 60_000);
        assertNotNull(cup.settle(CUP, 10, NAME, NOW + 100_000, words()));
        assertEquals(0, run(bob, 30_000, NOW + 200_000), "the Cup is over: a later run can't change who won");
        assertEquals(41_000, cup.entry(CUP, bob).bestMs());
    }

    // ---- settling -------------------------------------------------------------------------------

    private void field(UUID... players) throws SQLException {
        long ms = 40_000;
        for (UUID p : players) {
            give(p, 20);
            assertNull(enter(p));
            run(p, ms, NOW + 100_000 + ms);
            ms += 1_000;
        }
    }

    @Test
    void threeOrMoreShareThePoolFiftyThirtyTwentyWithTheRemainderToFirst() throws Exception {
        field(alice, bob, carol);
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertNotNull(s, "settled");
        assertEquals(CupPlan.Outcome.PRIZES, s.plan().outcome());
        assertEquals(25, s.plan().pool(), "3 entries of 5 plus the top-up of 10");
        // 50% of 25 = 12.5 -> 12, 30% = 7.5 -> 7, 20% = 5; the 1 left over goes to 1st.
        assertEquals(15 + 13, balance(alice), "1st: 13");
        assertEquals(15 + 7, balance(bob), "2nd: 7");
        assertEquals(15 + 5, balance(carol), "3rd: 5");
        assertEquals(10, cupNet(conn), "the ledger nets exactly the top-up: the server keeps nothing");
        assertEquals("Weekly Cup: 1st on Sky Rings", one("SELECT detail FROM token_ledger WHERE delta = 13"));
        assertEquals(3, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_PRIZE'"),
                "each prize under GAMES_CUP_PRIZE");
        CupDao.Settlement row = cup.settlement(CUP);
        assertNotNull(row, "the settlement row");
        assertEquals(CupPlan.Outcome.PRIZES, row.outcome());
        assertEquals(25, row.pool());
        assertEquals(s.plan().json(), row.payouts(), "the plan is kept as it was paid");
        assertEquals(List.of(), cup.openKeys(), "nothing is waiting to be settled");
        assertNull(cup.layout(CUP), "the running Cup's layout is forgotten");
    }

    @Test
    void twoEntrantsShareSeventyThirty() throws Exception {
        field(alice, bob);
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertEquals(20, s.plan().pool(), "10 in, plus the top-up of 10 with 2 entrants");
        assertEquals(15 + 14, balance(alice), "70% of 20");
        assertEquals(15 + 6, balance(bob), "30% of 20");
        assertEquals(10, cupNet(conn), "the ledger nets exactly the top-up");
    }

    @Test
    void tenEntrantsPayTheTopThree() throws Exception {
        UUID[] ten = new UUID[10];
        for (int i = 0; i < 10; i++) {
            ten[i] = new UUID(1, i + 1);
        }
        field(ten);
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertEquals(60, s.plan().pool(), "50 in, plus 10");
        assertEquals(15 + 30, balance(ten[0]), "1st: 50% of 60");
        assertEquals(15 + 18, balance(ten[1]), "2nd: 30% of 60");
        assertEquals(15 + 12, balance(ten[2]), "3rd: 20% of 60");
        for (int i = 3; i < 10; i++) {
            assertEquals(15, balance(ten[i]), "outside the top three: their entry went into the pool");
        }
        assertEquals(10, cupNet(conn), "the ledger nets exactly the top-up");
        assertEquals(3, s.plan().payouts().size(), "three payments");
        assertEquals(10, s.notices().size(), "and everyone gets a line");
    }

    @Test
    void tiesShareTheirPlacesWithTheRemainderToTheEarliestTime() throws Exception {
        for (UUID p : List.of(alice, bob, carol)) {
            give(p, 20);
            assertNull(enter(p));
        }
        run(bob, 40_000, NOW + 100_000);   // bob set 40.000 first
        run(alice, 40_000, NOW + 200_000); // alice tied it later
        run(carol, 45_000, NOW + 300_000);
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        // Pool 25: 1st 13 (12 + the remainder 1), 2nd 7 -> the tie shares 20: 10 each, nothing left over.
        assertEquals(15 + 10, balance(bob), "tied 1st shares 1st and 2nd's amounts");
        assertEquals(15 + 10, balance(alice), "tied 1st");
        assertEquals(15 + 5, balance(carol), "3rd");
        assertEquals("Weekly Cup: tied 1st on Sky Rings", one("SELECT detail FROM token_ledger WHERE player = '"
                + alice + "' AND source = 'GAMES_CUP_PRIZE'"), "the ledger says the place was shared");
        assertEquals(10, cupNet(conn));
        assertTrue(s.plan().lineFor(bob).tied() == 2, "a shared place says how many share it");
    }

    @Test
    void aLoneEntrantGetsTheirEntryBackWithNoTopUp() throws Exception {
        field(alice);
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertEquals(CupPlan.Outcome.REFUND_ALONE, s.plan().outcome());
        assertEquals(20, balance(alice), "the 5 came back and nothing was added");
        assertEquals("GAMES_CUP_REFUND", one("SELECT source FROM token_ledger WHERE delta = 5"), "a refund");
        assertEquals(0, cupNet(conn), "a refunded Cup nets 0: no top-up, nothing kept");
        assertTrue(s.notices().get(0).line().contains("Nobody else entered"), "the player is told why");
    }

    @Test
    void fewerThanTwoCupTimesRefundsEveryone() throws Exception {
        give(alice, 20);
        give(bob, 20);
        assertNull(enter(alice));
        assertNull(enter(bob));
        run(alice, 40_000, NOW + 100_000);
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertEquals(CupPlan.Outcome.REFUND_NO_CONTEST, s.plan().outcome());
        assertEquals(20, balance(alice), "refunded");
        assertEquals(20, balance(bob), "refunded");
        assertEquals(0, cupNet(conn), "no contest, no top-up");
    }

    /** The sum of one source's Weekly Cup ledger lines. */
    private int sum(String source) throws SQLException {
        return count("SELECT COALESCE(SUM(delta), 0) FROM token_ledger WHERE source = '" + source + "'");
    }

    @Test
    void anEntryWithNoCupTimeStaysInThePoolAndTheLedgerBalancesToTheTopUp() throws Exception {
        // Entries + prizes + refunds = the top-up, which is paid only when 2 or more set a Cup time.
        field(alice, bob, carol);
        give(dave, 20);
        assertNull(enter(dave), "dave enters but never sets a Cup time");
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertEquals(CupPlan.Outcome.PRIZES, s.plan().outcome(), "3 Cup times: prizes");
        assertEquals(30, s.plan().pool(), "4 entries of 5 plus the top-up of 10: dave's entry stays in the pool");
        assertEquals(15 + 15, balance(alice), "1st: 50% of 30");
        assertEquals(15 + 9, balance(bob), "2nd: 30% of 30");
        assertEquals(15 + 6, balance(carol), "3rd: 20% of 30");
        assertEquals(15, balance(dave), "no Cup time, no share (the forfeit rule)");
        assertEquals(-20, sum("GAMES_CUP_ENTRY"), "four entries taken");
        assertEquals(30, sum("GAMES_CUP_PRIZE"), "the whole pool paid out");
        assertEquals(0, sum("GAMES_CUP_REFUND"), "nothing refunded");
        assertEquals(10, sum("GAMES_CUP_ENTRY") + sum("GAMES_CUP_PRIZE") + sum("GAMES_CUP_REFUND"),
                "entries + prizes + refunds = the top-up: the server keeps nothing and adds only the top-up");
    }

    @Test
    void theTopUpIsPaidOnlyWhenTwoOrMoreSetACupTime() throws Exception {
        CupKey one = new CupKey("one_time", WEEK);
        CupKey two = new CupKey("two_times", WEEK);
        for (UUID p : List.of(alice, bob, carol, dave)) {
            give(p, 20);
        }
        assertNull(cup.enter(one, alice, 5, NAME, NOW, WEEK, null), "alice enters the first Cup");
        assertNull(cup.enter(one, bob, 5, NAME, NOW, WEEK, null), "and bob");
        assertEquals(1, cup.run(one.course(), alice, 40_000, NOW + 140_000, CupRules.Weeks.of(WEEK)),
                "only alice sets a Cup time");
        assertNotNull(cup.settle(one, 10, NAME, NOW + 1_000_000, words()), "settled");
        assertEquals(0, cupNet(conn), "one Cup time: every entry back, no top-up");
        assertEquals(0, sum("GAMES_CUP_PRIZE"), "and no prize");
        assertEquals(10, sum("GAMES_CUP_REFUND"), "both entries refunded");

        assertNull(cup.enter(two, carol, 5, NAME, NOW, WEEK, null), "carol enters the second Cup");
        assertNull(cup.enter(two, dave, 5, NAME, NOW, WEEK, null), "and dave");
        cup.run(two.course(), carol, 40_000, NOW + 140_000, CupRules.Weeks.of(WEEK));
        cup.run(two.course(), dave, 41_000, NOW + 141_000, CupRules.Weeks.of(WEEK));
        assertNotNull(cup.settle(two, 10, NAME, NOW + 1_000_000, words()), "settled");
        assertEquals(10, cupNet(conn), "two Cup times: over both Cups the ledger nets exactly one top-up");
        assertEquals(20 - 5 + 14, balance(carol), "70% of 20");
        assertEquals(20 - 5 + 6, balance(dave), "30% of 20");
    }

    @Test
    void aCupIsSettledExactlyOnce() throws Exception {
        field(alice, bob, carol);
        assertNotNull(cup.settle(CUP, 10, NAME, NOW + 1_000_000, words()));
        int a = balance(alice);
        assertNull(cup.settle(CUP, 10, NAME, NOW + 2_000_000, words()), "a second settle finds the row and pays nothing");
        assertNull(cup.voidCup(CUP, CupPlan.VoidReason.DELETED, NAME, NOW + 2_000_000, words()),
                "nor can a settled Cup be called off and refunded");
        assertEquals(a, balance(alice), "paid once");
        assertEquals(1, count("SELECT COUNT(*) FROM cup_settlements"), "one row");
        assertEquals(3, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_PRIZE'"), "three prizes, once");
        try (Statement st = conn.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO cup_settlements(course, week, settled_at, "
                    + "outcome, pool, payouts) VALUES('sky_rings', " + WEEK + ", 1, 'PRIZES', 0, '')"),
                    "the primary key refuses a second settlement of the same Cup, whatever the code does");
        }
    }

    @Test
    void cupPrizesAreNotUnderTheSkillCaps() throws Exception {
        for (UUID p : List.of(alice, bob, carol)) {
            give(p, 200);
            assertNull(cup.enter(CUP, p, 100, NAME, NOW, WEEK, null));
        }
        run(alice, 40_000, NOW + 100_000);
        run(bob, 41_000, NOW + 100_000);
        run(carol, 42_000, NOW + 100_000);
        cup.settle(CUP, 100, NAME, NOW + 1_000_000, words());
        assertEquals(100 + 200, balance(alice), "1st gets 50% of 400 = 200 in full: far over any daily cap");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rewards"), "no skill reward row: it never went through the caps");
        assertEquals(0, games.rewardsToday(alice, 0), "and the day's capped rewards didn't move");
    }

    @Test
    void everyEntrantsLineIsQueuedInTheSettlementsTransaction() throws Exception {
        field(alice, bob, carol, dave);
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE cup_entries SET best_ms = NULL, best_at = 0 WHERE player = '" + dave + "'");
        }
        CupDao.Settled s = cup.settle(CUP, 10, NAME, NOW + 1_000_000, words());
        assertEquals(4, s.notices().size(), "one line for each entrant");
        Map<String, String> waiting = games.prefsLike(alice, ChanceRounds.NOTICE);
        assertEquals(1, waiting.size(), "alice's line waits for her next join");
        String line = waiting.values().iterator().next();
        assertTrue(line.contains("you came 1st with 0:40.0 - 15 tokens."), "her place and prize: " + line);
        String daves = games.prefsLike(dave, ChanceRounds.NOTICE).values().iterator().next();
        assertTrue(daves.contains("you didn't set a Cup time"), "an entrant with no time is told plainly: " + daves);
        CupDao.Notice n = s.notices().get(0);
        cup.read(n);
        assertTrue(games.prefsLike(n.player(), ChanceRounds.NOTICE).isEmpty(), "a line said at once is forgotten");
    }

    // ---- calling a Cup off ------------------------------------------------------------------------

    @Test
    void aVoidRefundsEveryEntryInFullAndClosesTheWeek() throws Exception {
        field(alice, bob, carol);
        CupDao.Settled s = cup.voidCup(CUP, CupPlan.VoidReason.DELETED, NAME, NOW + 100_000, words());
        assertNotNull(s);
        assertEquals(CupPlan.Outcome.VOIDED, s.plan().outcome());
        for (UUID p : List.of(alice, bob, carol)) {
            assertEquals(20, balance(p), "every entry back in full");
        }
        assertEquals(3, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_REFUND'"), "as refunds");
        assertEquals(0, cupNet(conn), "a voided Cup nets 0: no top-up");
        assertEquals(CupPlan.Outcome.VOIDED, cup.settledAs(CUP), "the week is closed as called off");
        String line = games.prefsLike(bob, ChanceRounds.NOTICE).values().iterator().next();
        assertTrue(line.contains("was called off because the course was removed"), "the player is told why: " + line);
        assertNull(cup.settle(CUP, 10, NAME, NOW + 2_000_000, words()), "a voided Cup is never settled too");
    }

    @Test
    void aCupNobodyEnteredIsNotCalledOff() throws Exception {
        assertNull(cup.voidCup(CUP, CupPlan.VoidReason.CHANGED, NAME, NOW, words()), "nothing to give back");
        assertEquals(0, count("SELECT COUNT(*) FROM cup_settlements"), "no row: the Cup stays open on the new layout");
        give(alice, 10);
        assertNull(enter(alice), "so it can still be entered");
    }

    // ---- a crash in the middle of a settlement ---------------------------------------------------

    @Test
    void aSettlementThatFailsHalfwayLeavesNothingAndIsPaidWholeOnTheNextTry() throws Exception {
        field(alice, bob, carol);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER second_prize_fails BEFORE INSERT ON token_ledger "
                    + "WHEN NEW.source = 'GAMES_CUP_PRIZE' AND (SELECT COUNT(*) FROM token_ledger WHERE source = "
                    + "'GAMES_CUP_PRIZE') >= 1 BEGIN SELECT RAISE(ABORT, 'crash'); END");
        }
        assertThrows(SQLException.class, () -> cup.settle(CUP, 10, NAME, NOW + 1_000_000, words()),
                "the second prize failed");
        assertEquals(0, count("SELECT COUNT(*) FROM cup_settlements"), "no settlement row");
        assertEquals(0, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_PRIZE'"),
                "the first prize went back too");
        assertEquals(15, balance(alice), "nobody was paid");
        assertTrue(games.prefsLike(alice, ChanceRounds.NOTICE).isEmpty(), "and nobody was told anything");
        assertEquals(List.of(CUP), cup.openKeys(), "the Cup is still waiting to be settled");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER second_prize_fails");
        }
        assertNotNull(cup.settle(CUP, 10, NAME, NOW + 2_000_000, words()), "the next try settles it");
        assertEquals(15 + 13, balance(alice), "whole");
        assertEquals(10, cupNet(conn), "and the ledger still nets the top-up");
    }

    /**
     * A real crash: the server dies (the process is killed) in the middle of paying the prizes, with
     * the settlement's transaction open. What is on disk at that instant (the database file and its
     * rollback journal) is copied, and the "next boot" opens that copy.
     */
    @Test
    void aCrashMidSettlementIsSettledOnceOnTheNextBoot(@TempDir Path dir) throws Exception {
        conn.close();
        Path file = dir.resolve("homecraft.db");
        Path image = dir.resolve("after-crash.db");
        Connection live = DriverManager.getConnection("jdbc:sqlite:" + file);
        Database liveDb = Database.open(live, Logger.getAnonymousLogger());
        CupDao liveCup = new CupDao(liveDb);
        TokenDao liveTokens = new TokenDao(liveDb);
        long ms = 40_000;
        for (UUID p : List.of(alice, bob, carol)) {
            liveTokens.change(p, 20, "ADMIN", "seed", 1);
            assertNull(liveCup.enter(CUP, p, 5, NAME, NOW, WEEK, null));
            liveCup.run(CUP.course(), p, ms, NOW + 100_000 + ms, CupRules.Weeks.of(WEEK));
            ms += 1_000;
        }
        // The power goes the moment the first prize's ledger line is written.
        Function.create(live, "power_cut", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                try {
                    Files.copy(file, image, StandardCopyOption.REPLACE_EXISTING);
                    Path journal = dir.resolve("homecraft.db-journal");
                    if (Files.exists(journal)) {
                        Files.copy(journal, dir.resolve("after-crash.db-journal"), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException e) {
                    throw new SQLException(e);
                }
                throw new SQLException("the power went");
            }
        });
        try (Statement st = live.createStatement()) {
            st.execute("CREATE TEMP TRIGGER crash AFTER INSERT ON token_ledger WHEN NEW.source = 'GAMES_CUP_PRIZE' "
                    + "BEGIN SELECT power_cut(); END");
        }
        assertThrows(SQLException.class, () -> liveCup.settle(CUP, 10, NAME, NOW + 1_000_000, words()),
                "the server died paying out");
        live.close();

        // The next boot, on what was on disk.
        Connection boot = DriverManager.getConnection("jdbc:sqlite:" + image);
        try {
            Database bootDb = Database.open(boot, Logger.getAnonymousLogger());
            CupDao bootCup = new CupDao(bootDb);
            TokenDao bootTokens = new TokenDao(bootDb);
            assertEquals(0, count(boot, "SELECT COUNT(*) FROM cup_settlements"), "nothing of the half settlement survived");
            assertEquals(0, count(boot, "SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_PRIZE'"),
                    "no prize was kept");
            assertEquals(15, bootTokens.get(alice).tokens(), "every entrant is where the entry left them");
            assertEquals(List.of(CUP), CupRules.due(bootCup.openKeys(), WEEK + 7), "so the boot finds it due");
            assertNotNull(bootCup.settle(CUP, 10, NAME, NOW + 5_000_000, words()), "and settles it");
            assertNull(bootCup.settle(CUP, 10, NAME, NOW + 6_000_000, words()), "once");
            assertEquals(15 + 13, bootTokens.get(alice).tokens(), "1st paid once");
            assertEquals(15 + 7, bootTokens.get(bob).tokens(), "2nd paid once");
            assertEquals(15 + 5, bootTokens.get(carol).tokens(), "3rd paid once");
            assertEquals(10, cupNet(boot), "the ledger nets exactly the top-up");
        } finally {
            boot.close();
        }
        // And a boot after that finds nothing to do.
        Connection again = DriverManager.getConnection("jdbc:sqlite:" + image);
        try {
            CupDao againCup = new CupDao(Database.open(again, Logger.getAnonymousLogger()));
            assertEquals(List.of(), againCup.openKeys(), "a settled Cup is never due again");
            assertNull(againCup.settle(CUP, 10, NAME, NOW + 9_000_000, words()), "nor paid again");
            assertEquals(3, count(again, "SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_PRIZE'"),
                    "three prizes in all, ever");
        } finally {
            again.close();
        }
    }

    @Test
    void aCrashJustAfterTheCommitKeepsThePaymentsAndTheLines(@TempDir Path dir) throws Exception {
        conn.close();
        Path file = dir.resolve("homecraft.db");
        Connection live = DriverManager.getConnection("jdbc:sqlite:" + file);
        Database liveDb = Database.open(live, Logger.getAnonymousLogger());
        CupDao liveCup = new CupDao(liveDb);
        TokenDao liveTokens = new TokenDao(liveDb);
        for (UUID p : List.of(alice, bob)) {
            liveTokens.change(p, 20, "ADMIN", "seed", 1);
            assertNull(liveCup.enter(CUP, p, 5, NAME, NOW, WEEK, null));
        }
        liveCup.run(CUP.course(), alice, 40_000, NOW + 100_000, CupRules.Weeks.of(WEEK));
        liveCup.run(CUP.course(), bob, 41_000, NOW + 100_000, CupRules.Weeks.of(WEEK));
        assertNotNull(liveCup.settle(CUP, 10, NAME, NOW + 1_000_000, words()));
        live.close(); // killed before anyone online was told

        Connection boot = DriverManager.getConnection("jdbc:sqlite:" + file);
        try {
            Database bootDb = Database.open(boot, Logger.getAnonymousLogger());
            CupDao bootCup = new CupDao(bootDb);
            GamesDao bootGames = new GamesDao(bootDb);
            assertEquals(List.of(), bootCup.openKeys(), "the Cup was settled");
            assertNull(bootCup.settle(CUP, 10, NAME, NOW + 2_000_000, words()), "and isn't paid again");
            assertEquals(15 + 14, new TokenDao(bootDb).get(alice).tokens(), "alice kept her prize");
            assertEquals(1, bootGames.prefsLike(alice, ChanceRounds.NOTICE).size(),
                    "and her line still waits for her next join");
            assertEquals(1, bootGames.prefsLike(bob, ChanceRounds.NOTICE).size(), "as does bob's");
        } finally {
            boot.close();
        }
    }

    // ---- hcm_meta ---------------------------------------------------------------------------------

    @Test
    void theAdminsCourseSwitchIsKeptAndForgotten() throws Exception {
        assertNull(cup.chosen("lava_leap"), "no switch: the default");
        cup.choose("lava_leap", true);
        cup.choose("sky_rings", false);
        assertEquals(Boolean.TRUE, cup.chosen("lava_leap"));
        assertEquals(Boolean.FALSE, cup.chosen("sky_rings"));
        assertEquals(Map.of("lava_leap", true, "sky_rings", false), cup.chosen(), "every switch, by course");
        cup.choose("lava_leap", null);
        assertNull(cup.chosen("lava_leap"), "default forgets it");
        assertEquals("off", one("SELECT value FROM hcm_meta WHERE key = 'cup.course.sky_rings'"),
                "kept in hcm_meta under the Cup's own prefix");
    }

    @Test
    void theCupNeverTouchesAnotherFeaturesMetaKeys() throws Exception {
        String version = one("SELECT value FROM hcm_meta WHERE key = 'schema_version'");
        assertThrows(IllegalArgumentException.class, () -> CupDao.check("schema_version"),
                "a key outside cup. is refused");
        assertThrows(IllegalArgumentException.class, () -> CupDao.check("cup."), "and so is the bare prefix");
        assertEquals(version, one("SELECT value FROM hcm_meta WHERE key = 'schema_version'"), "untouched");
        assertEquals("cup.layout.sky_rings." + WEEK, CupDao.layoutKey(CUP), "a running Cup's layout key");
        assertEquals("cup.course.sky_rings", CupDao.courseKey("sky_rings"), "a course's switch key");
    }
}
