package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupLayout;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.CupDao;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup's desk against a real SQLite, a movable clock and a fake server
 * (EVENTS-OWNER-DECISIONS §D2).
 *
 * <p>Pinned here: which courses run a Cup (a hand-built one only once an admin says so; Fresh
 * parkour, Sky Rings, Ice Boat and Dropper courses by default, and only while Fresh Courses change
 * once a week); the live pool; the Cup is paid at the quests' week start at 04:00 and not a minute
 * before, once, and a rollover the server missed is settled at the next start, oldest first; a
 * winner online is told at once and anyone offline at their next join; a run counts only inside its
 * Cup's own week and, on a Fresh slot, only on that week's own layout; the watch calls a Cup off
 * (every entry back, with why) when its course is deleted, closed or re-made, never for a rename, a
 * heal or the scheduled flip into the week's own layout, and never on a read that failed; an
 * admin's call-off is at once; with entries closed ({@code games.cup.enabled: false}) the Cups
 * already paid into still finish.
 */
class CupDeskTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /** Fresh Courses weekly from Monday, the quests' week from Monday, turning at 04:00. */
    private static final Edition WEEKLY = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7,
            DayOfWeek.MONDAY);
    /** The Cup week of Monday 28 September 2026. */
    private static final long W = LocalDate.of(2026, 9, 28).toEpochDay();
    private static final long TUESDAY_NOON = at(2026, 9, 29, 12, 0);
    /** The week's end: Monday 5 October, 04:00. */
    private static final long ROLLOVER = at(2026, 10, 5, 4, 0);

    private Connection conn;
    private CupDao dao;
    private TokenDao tokens;
    private GamesDao games;
    private FakeHost host;
    private CupDesk desk;
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final UUID carol = new UUID(0, 3);
    private Course lava;

    private static long at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    /** The server as the desk sees it: a clock, a schedule, the course rows, who is online. */
    private static final class FakeHost implements CupDesk.Host {
        long now = TUESDAY_NOON;
        Edition edition = WEEKLY;
        /** The Cup's mechanism at 0.36's 5 to enter and 10 on top (the shipped 10/20 is CupSettingsTest's). */
        CupSettings settings = new CupSettings(true, 5, 10);
        final Map<String, Course> courses = new HashMap<>();
        final Map<String, Boolean> wanted = new HashMap<>();
        final Set<UUID> online = new HashSet<>();
        final List<String> told = new ArrayList<>();
        boolean readFails;
        final Logger logger = Logger.getAnonymousLogger();
        final List<LogRecord> logs = new ArrayList<>();

        FakeHost() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logs.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        void put(Course c) {
            courses.put(c.id(), c);
        }

        long warnings() {
            return logs.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public Edition edition() {
            return edition;
        }

        @Override
        public CupSettings settings() {
            return settings;
        }

        @Override
        public Course course(String id) throws SQLException {
            if (readFails) {
                throw new SQLException("disk I/O error");
            }
            return courses.get(id);
        }

        @Override
        public Boolean slotWanted(String id) {
            return wanted.get(id);
        }

        @Override
        public boolean tellNow(UUID player, String line) {
            if (!online.contains(player)) {
                return false;
            }
            told.add(player + " " + line);
            return true;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        /** The owner's restarts: 04:00 and 4:00 PM, held 5 minutes. */
        com.dierks.homecraft.games.RestartHold hold = new com.dierks.homecraft.games.RestartHold(
                List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), CHICAGO, 5);

        @Override
        public com.dierks.homecraft.games.RestartHold restartHold() {
            return hold;
        }
    }

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new CupDao(db);
        tokens = new TokenDao(db);
        games = new GamesDao(db);
        host = new FakeHost();
        desk = new CupDesk(dao, host);
        lava = handBuilt("lava_leap", "Lava Leap");
        host.put(lava);
        for (UUID p : List.of(alice, bob, carol)) {
            tokens.change(p, 20, "ADMIN", "seed", 1);
        }
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private static Course handBuilt(String id, String name) {
        return new Course(id, TrialKind.PARKOUR, name, Tier.EASY, "games", new Course.Spot(0, 64, 0, 0, 0),
                List.of(new Course.Mark(10, 64, 0, 1.5)), new Course.Mark(20, 64, 0, 1.5), null, null, true, false, 1);
    }

    private static GenTag tag(String slot, long day, int reroll, String planHash, GenTag.Recall recall) {
        return new GenTag(slot, "gen", 1, day, reroll, 42L, 'A', planHash, 30_000, 34_000, 40_000, List.of(),
                List.of(), 0L, 7, recall);
    }

    /** Fresh Sky Rings: the edition starting on {@code day}. */
    private static Course rings(long day, int reroll, String planHash) {
        return fresh("fresh_rings", TrialKind.ELYTRA, "Sky Rings", tag("fresh_rings", day, reroll, planHash, null));
    }

    private static Course fresh(String id, TrialKind kind, String name, GenTag tag) {
        return new Course(id, kind, name, Tier.MEDIUM, "games", new Course.Spot(0, 90, 0, 0, 0),
                List.of(new Course.Mark(40, 90, 0, 4)), new Course.Mark(80, 90, 0, 4), null, null, true, false, 1, tag);
    }

    private int balance(UUID p) throws SQLException {
        return tokens.get(p).tokens();
    }

    private int count(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private void switchOn(Course c) throws SQLException {
        dao.choose(c.id(), true);
    }

    /** A counted, timed run of {@code ms} on {@code c}, started a moment after now. */
    private boolean race(UUID p, Course c, long ms) throws SQLException {
        host.now += ms + 1_000;
        return desk.counted(p, c, ms, host.now);
    }

    /** alice, bob and carol enter {@code c}'s Cup this week and set 40, 41 and 42 seconds. */
    private void threeRace(Course c) throws SQLException {
        for (UUID p : List.of(alice, bob, carol)) {
            assertNull(desk.enter(p, c), p + " enters");
        }
        assertTrue(race(alice, c, 40_000));
        assertTrue(race(bob, c, 41_000));
        assertTrue(race(carol, c, 42_000));
    }

    private String queued(UUID p) throws SQLException {
        Map<String, String> waiting = games.prefsLike(p, ChanceRounds.NOTICE);
        return waiting.isEmpty() ? null : String.join("\n", waiting.values());
    }

    // ---- which courses run a Cup ----------------------------------------------------------------

    @Test
    void aHandBuiltCourseRunsACupOnlyOnceAnAdminSwitchesItOn() throws Exception {
        assertFalse(desk.runsCup(lava), "hand-built courses start off");
        CupDesk.View v = desk.view(lava, alice);
        assertEquals(CupRefusal.NOT_ON_THIS_COURSE, v.refusal(), "so nobody can enter");
        assertFalse(v.shown(), "and the screens show no Cup");
        assertEquals(CupRefusal.NOT_ON_THIS_COURSE, desk.enter(alice, lava), "entering is refused");
        assertEquals(20, balance(alice), "and takes nothing");

        switchOn(lava);
        assertTrue(desk.runsCup(lava), "an admin switched it on");
        assertNull(desk.enter(alice, lava), "now alice can enter");
        assertEquals(15, balance(alice), "for 5 tokens");
        v = desk.view(lava, alice);
        assertTrue(v.in(), "she is in");
        assertEquals(CupRefusal.ALREADY_IN, desk.enter(alice, lava), "once a week");
        assertEquals(15, balance(alice), "the second try took nothing");
    }

    @Test
    void freshParkourRingsBoatAndDropperRunACupByDefaultWhileFreshCoursesChangeWeekly() throws Exception {
        assertTrue(desk.runsCup(rings(W, 0, "a")), "Sky Rings");
        assertTrue(desk.runsCup(fresh("fresh_parkour", TrialKind.PARKOUR, "Parkour",
                tag("fresh_parkour", W, 0, "b", null))), "Fresh parkour");
        assertTrue(desk.runsCup(fresh("fresh_boat", TrialKind.BOAT, "Ice Boat",
                tag("fresh_boat", W, 0, "c", null))), "Ice Boat");
        assertTrue(desk.runsCup(fresh("fresh_dropper", TrialKind.DROPPER, "Dropper",
                tag("fresh_dropper", W, 0, "d", null))), "the Dropper");
        Course classic = fresh("fresh_classic_rings", TrialKind.ELYTRA, "Classic Rings",
                tag("fresh_rings", W - 70, 0, "e", new GenTag.Recall("fresh_classic_rings", W - 3, W - 3)));
        assertFalse(desk.runsCup(classic), "a recalled Classic starts off (its window may end mid-week)");
        switchOn(classic);
        assertTrue(desk.runsCup(classic), "an admin may switch it on");

        host.edition = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 1, DayOfWeek.MONDAY);
        Course daily = rings(W + 1, 0, "f");
        assertFalse(desk.runsCup(daily), "daily Fresh Courses would change layout mid-week: no Cup");
        switchOn(daily);
        assertFalse(desk.runsCup(daily), "and an admin can't switch it on either");
        host.edition = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7, DayOfWeek.TUESDAY);
        assertFalse(desk.runsCup(rings(W + 1, 0, "g")), "a weekly set that starts on another day than the week");
    }

    @Test
    void thePoolIsShownLiveWithTheTopUpOnceTwoAreIn() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        CupDesk.View one = desk.view(lava, null);
        assertEquals("Cup pool: 5 tokens · 1 in", CupText.poolLine(one.pool().tokens(), one.pool().in()),
                "one entrant: no top-up (a lone entrant is refunded)");
        assertNull(desk.enter(bob, lava));
        CupDesk.View two = desk.view(lava, carol);
        assertEquals("Cup pool: 20 tokens · 2 in", CupText.poolLine(two.pool().tokens(), two.pool().in()),
                "two entrants: 10 in plus the server's 10");
        assertNull(two.refusal(), "carol may still enter");
        assertEquals(ROLLOVER, two.endsAt(), "it is paid at next Monday's 04:00");
        assertTrue(two.shown(), "shown on the screens");
    }

    // ---- settling -------------------------------------------------------------------------------

    @Test
    void theCupIsPaidAtTheRolloverOnceAndNotAMinuteBefore() throws Exception {
        switchOn(lava);
        host.online.add(alice);
        threeRace(lava);
        host.now = ROLLOVER - 60_000;
        assertEquals(List.of(), desk.tick(), "Monday 03:59 is still this week's Cup");
        assertEquals(15, balance(alice), "nothing paid yet");

        host.now = ROLLOVER;
        List<CupDesk.Closed> closed = desk.tick();
        assertEquals(1, closed.size(), "04:00: the Cup is settled");
        CupPlan plan = closed.get(0).plan();
        assertEquals(CupPlan.Outcome.PRIZES, plan.outcome());
        assertEquals(25, plan.pool(), "15 in, plus 10");
        assertEquals(15 + 13, balance(alice), "1st: 13");
        assertEquals(15 + 7, balance(bob), "2nd: 7");
        assertEquals(15 + 5, balance(carol), "3rd: 5");
        assertEquals(List.of(alice + " &6Weekly Cup on Lava Leap: you came 1st with 0:40.0 - 13 tokens."), host.told,
                "alice is online: she is told at once");
        assertNull(queued(alice), "so nothing waits for her");
        assertEquals("&6Weekly Cup on Lava Leap: you came 2nd with 0:41.0 - 7 tokens.", queued(bob),
                "bob is offline: his line waits for his next join");

        int ledger = count("SELECT COUNT(*) FROM token_ledger");
        host.now += 60_000;
        assertEquals(List.of(), desk.tick(), "a minute later there is nothing left to settle");
        host.now += 7L * 86_400_000;
        assertEquals(List.of(), desk.tick(), "nor a week later");
        assertEquals(ledger, count("SELECT COUNT(*) FROM token_ledger"), "paid exactly once");
    }

    @Test
    void aCupOpenedBeforeTheEntryChangedKeepsItsFeeSoNobodyTimedInACupOfThreeLoses() throws Exception {
        // the v4 audit, ECON01: the upgrade week. Two enter at 0.36's 5; the restart brings 0.37's 10 and 20.
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertNull(desk.enter(bob, lava));
        host.settings = new CupSettings(true, 10, 20);
        CupDesk.View v = desk.view(lava, carol);
        assertEquals(5, v.fee(), "this week's Cup still costs what its first entrant paid");
        assertEquals(5, desk.fee(lava.id()), "which is what the entry's line says (WeeklyCup.enter)");
        assertNull(desk.enter(carol, lava), "carol enters");
        assertEquals(15, balance(carol), "for 5, like the others");
        assertTrue(race(alice, lava, 40_000));
        assertTrue(race(bob, lava, 41_000));
        assertTrue(race(carol, lava, 42_000));
        host.now = ROLLOVER;
        CupPlan plan = desk.tick().get(0).plan();
        assertEquals(35, plan.pool(), "15 in, plus the new 20");
        assertEquals(15 + 7, balance(carol), "third gets 7 of the 5 she paid: nobody timed loses");

        assertEquals(15 + 18, balance(alice), "1st: 18 (half of 35, and the odd token)");
        assertEquals(10, desk.view(lava, alice).fee(), "the next week's Cup costs the new entry");
        assertNull(desk.enter(alice, lava));
        assertEquals(15 + 18 - 10, balance(alice), "and its first entrant pays it");
    }

    @Test
    void theEntrysOwnTransactionChargesTheCupsFeeToo() throws Exception {
        // a screen built before the first entry landed shows the setting; the transaction reads the Cup again
        switchOn(lava);
        CupKey key = desk.key(lava.id());
        assertNull(dao.enter(key, alice, 5, "Lava Leap", host.now, W, null), "alice opens the Cup at 5");
        assertNull(dao.enter(key, bob, 10, "Lava Leap", host.now + 1, W, null), "bob's screen said 10");
        assertEquals(15, balance(bob), "but he is charged the Cup's 5");
        assertEquals(List.of(5, 5), dao.entries(key).stream().map(CupEntry::paid).toList(), "one fee a Cup");
    }

    @Test
    void aLoneEntrantIsRefundedAtTheRolloverAndToldWhy() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertTrue(race(alice, lava, 40_000));
        host.now = ROLLOVER;
        CupPlan plan = desk.tick().get(0).plan();
        assertEquals(CupPlan.Outcome.REFUND_ALONE, plan.outcome(), "one entrant: refunded");
        assertEquals(20, balance(alice), "in full, with no top-up");
        assertEquals("&eNobody else entered the Weekly Cup on Lava Leap, so your 5 tokens came back.", queued(alice),
                "and told why");
    }

    /**
     * WP-ADM checklist fix: after an admin's early {@code /hcm games cup settle}, the course screen
     * used to lose its Cup item. It stays, saying the week's Cup is already paid out (WEEK_OVER's short
     * form, in the NAME for Bedrock); the tile and the website leave it out, since nothing is running;
     * a Cup called off stays hidden as before.
     */
    @Test
    void aCupPaidOutEarlyStillShowsOnTheCourseScreenSayingSo() throws Exception {
        switchOn(lava);
        threeRace(lava);
        assertTrue(desk.view(lava, alice).shown(), "running: shown");
        CupDesk.Closed closed = desk.settle(desk.key(lava.id())); // the admin's early settle
        assertNotNull(closed, "settled");
        assertEquals(CupPlan.Outcome.PRIZES, closed.plan().outcome(), "paid out mid-week");

        CupDesk.View v = desk.view(lava, alice);
        assertEquals(CupRefusal.WEEK_OVER, v.refusal(), "nobody can enter a Cup already paid out");
        assertTrue(v.settledEarly(), "this week's Cup was settled early");
        assertTrue(v.shown(), "and the course screen still shows its item");
        assertEquals("&7Weekly Cup &8- &7already paid out this week", CupWords.buttonName(v),
                "saying so in its NAME (Bedrock)");
        assertEquals(List.of("&7This week's Cup on this course is already paid out. It's back next week.",
                "&eClick to see the Cup"), CupWords.buttonLore(v, "Mon 5 Oct 4:00 AM"),
                "its lore doesn't promise a payout at the week's end");
        assertTrue(desk.view(lava, null).shown(), "for someone who wasn't in it too");
        assertEquals("", CupWords.tileSuffix(v), "the tile's NAME leaves out a Cup that is over");
        assertEquals(List.of(), CupWords.tileLines(v), "and so does its lore");

        Course cliffs = handBuilt("cliffs", "Cliffs");
        host.put(cliffs);
        switchOn(cliffs);
        assertNull(desk.enter(bob, cliffs));
        assertNotNull(desk.voidNow(cliffs.id(), CupPlan.VoidReason.CLOSED, "Cliffs"), "a Cup called off");
        CupDesk.View off = desk.view(cliffs, bob);
        assertEquals(CupRefusal.CALLED_OFF, off.refusal(), "called off, not paid out");
        assertFalse(off.settledEarly(), "a Cup called off isn't one settled early");
        assertFalse(off.shown(), "and stays hidden, as before");
    }

    @Test
    void anEntryBetweenTheRolloverAndTheSettlementTickGoesIntoTheNewWeek() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertNull(desk.enter(bob, lava));
        assertTrue(race(alice, lava, 40_000));
        assertTrue(race(bob, lava, 41_000));
        host.now = ROLLOVER + 30_000; // Monday 04:00:30: last week's Cup is over but its tick hasn't run
        CupDesk.View v = desk.view(lava, alice);
        assertEquals(new CupKey("lava_leap", W + 7), v.key(), "the screen shows the new week's Cup");
        assertNull(v.refusal(), "which alice may enter: last week's is over, not running");
        assertNull(desk.enter(alice, lava), "her entry goes into the new week");
        assertEquals(CupPlan.Outcome.PRIZES, desk.tick().get(0).plan().outcome(), "last week's is then paid");
        assertEquals(15 + 14 - 5, balance(alice), "on last week's two entries: 70% of 20, less the new entry");
        assertEquals(1, dao.entries(new CupKey("lava_leap", W + 7)).size(), "her new entry stands");
        assertNull(dao.settledAs(new CupKey("lava_leap", W + 7)), "and the new week's Cup runs");
    }

    @Test
    void rolloversMissedWhileTheServerWasDownAreSettledAtTheNextStartOldestFirst() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertNull(desk.enter(bob, lava));
        assertTrue(race(alice, lava, 40_000));
        assertTrue(race(bob, lava, 41_000));
        host.now = TUESDAY_NOON + 7L * 86_400_000; // the next week (nobody ticked: the server was down)
        assertNull(desk.enter(alice, lava), "next week's Cup is another entry");
        assertNull(desk.enter(carol, lava));
        host.now = at(2026, 10, 19, 9, 0); // two rollovers later

        CupDesk boot = new CupDesk(new CupDao(Database.open(conn, Logger.getAnonymousLogger())), host);
        List<CupDesk.Closed> closed = boot.tick();
        assertEquals(List.of(new CupKey("lava_leap", W), new CupKey("lava_leap", W + 7)),
                closed.stream().map(CupDesk.Closed::key).toList(), "both, oldest first");
        assertEquals(CupPlan.Outcome.PRIZES, closed.get(0).plan().outcome(), "the first week was a contest");
        assertEquals(CupPlan.Outcome.REFUND_NO_CONTEST, closed.get(1).plan().outcome(),
                "nobody set a time in the second: every entry back");
        assertEquals(20 - 5 + 14 - 5 + 5, balance(alice), "alice won the first (14) and got the second entry back");
        assertEquals(20 - 5 + 6, balance(bob), "bob came 2nd (6)");
        assertEquals(20, balance(carol), "carol got her entry back");
        assertEquals(List.of(), new CupDesk(dao, host).tick(), "and the boot after that finds nothing to do");
        assertEquals(10, count("SELECT SUM(delta) FROM token_ledger WHERE source LIKE 'GAMES_CUP_%'"),
                "every Cup token accounted for: the ledger nets the one top-up");
    }

    // ---- Cup times --------------------------------------------------------------------------------

    @Test
    void aRunCountsOnlyInsideTheCupsOwnWeek() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertFalse(desk.counted(alice, lava, 40_000, host.now + 10_000), "a run that started before the entry");
        assertTrue(race(alice, lava, 40_000), "a run after it");
        host.now = ROLLOVER + 10_000;
        assertFalse(desk.counted(alice, lava, 30_000, host.now),
                "a run that started before the rollover and finished after it counts for neither week");
        assertEquals(40_000, dao.entry(new CupKey("lava_leap", W), alice).bestMs(), "the Cup time is unchanged");
    }

    @Test
    void aFreshCourseStillOnLastWeeksLayoutTakesNoEntryUntilThisWeeksIsUp() throws Exception {
        host.now = at(2026, 9, 28, 4, 30); // Monday 04:30: the new week, but last week's layout still stands
        Course lastWeeks = rings(W - 7, 0, "old");
        host.put(lastWeeks);
        CupDesk.View v = desk.view(lastWeeks, alice);
        assertEquals(CupRefusal.NOT_UP_YET, v.refusal(), "no run on last week's layout can set a Cup time this week");
        assertTrue(v.shown(), "the Cup still shows, saying when it starts");
        assertEquals("&7Weekly Cup &8- &7starts when this week's course is up", CupWords.buttonName(v),
                "the course screen's item NAME says why, for Bedrock");
        assertEquals(" &7· Cup not open yet", CupWords.tileSuffix(v), "and the tile's NAME");
        assertEquals("The Cup starts when this week's course is up.", v.refusal().message(5), "in plain words");
        assertEquals(CupRefusal.NOT_UP_YET, desk.enter(alice, lastWeeks), "so the entry is refused");
        assertEquals(20, balance(alice), "and takes nothing");
        assertEquals(0, count("SELECT COUNT(*) FROM cup_entries"), "no entry row");

        Course thisWeeks = rings(W, 0, "new");
        host.put(thisWeeks); // the scheduled build flips the week's own layout in
        assertNull(desk.view(thisWeeks, alice).refusal(), "this week's course is up: the Cup takes entries");
        assertNull(desk.enter(alice, thisWeeks), "alice enters");
        assertEquals(15, balance(alice), "for 5 tokens");
        assertEquals(CupDesk.layout(thisWeeks).encode(), dao.layout(new CupKey("fresh_rings", W)),
                "the Cup remembers the week's own layout from its first entry");
        assertTrue(race(alice, thisWeeks, 40_000), "and a run on it counts");
    }

    @Test
    void aRunOnLastWeeksLayoutSaysWhyItSetNoCupTime() throws Exception {
        host.now = at(2026, 9, 28, 4, 30);
        Course lastWeeks = rings(W - 7, 0, "old");
        host.put(lastWeeks);
        // A Cup entered while last week's layout still stood (an older build took such entries).
        assertNull(dao.enter(new CupKey("fresh_rings", W), alice, 5, "Sky Rings", host.now, W,
                CupDesk.layout(lastWeeks).encode()));
        assertFalse(race(alice, lastWeeks, 40_000), "a run on last week's layout sets no Cup time");
        assertTrue(desk.onLastWeeksLayout(alice, lastWeeks), "so the finish says why");
        assertFalse(desk.onLastWeeksLayout(bob, lastWeeks), "but only to someone in the Cup");
        Course thisWeeks = rings(W, 0, "new");
        assertFalse(desk.onLastWeeksLayout(alice, thisWeeks), "never on this week's own layout");
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertFalse(desk.onLastWeeksLayout(alice, lava), "nor on a hand-built course");
    }

    @Test
    void aMovedWeekStartDoesntLetAPlayerEnterTheSameCourseTwice() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava), "Tuesday: alice enters the week of Monday 28 September");
        host.edition = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.SUNDAY, 7, DayOfWeek.MONDAY);
        host.now = at(2026, 9, 30, 12, 0); // Wednesday, after the owner moved the week start to Sunday
        assertEquals(W - 1, desk.week(), "the new week began on Sunday 27 September");

        CupDesk.View v = desk.view(lava, alice);
        assertEquals(CupRefusal.ALREADY_IN, v.refusal(), "her Cup of the old week start still runs its seven days");
        assertEquals(CupRefusal.ALREADY_IN, desk.enter(alice, lava), "so the new week's Cup refuses her");
        assertEquals(CupRefusal.ALREADY_IN, dao.enter(new CupKey("lava_leap", W - 1), alice, 5, "Lava Leap", host.now,
                W - 1, null, CupRules.liveWeeks(host.edition, host.now)), "and so does the entry's own transaction");
        assertEquals(15, balance(alice), "she paid once");
        assertEquals(" &6· Cup pool 0", CupWords.tileSuffix(v), "her tile doesn't invite her to pay again");

        assertNull(desk.enter(bob, lava), "bob, in no Cup on it, enters the new week's");
        assertTrue(race(alice, lava, 40_000), "alice's run counts in the Cup she is in");
        assertEquals(1, count("SELECT COUNT(*) FROM cup_entries WHERE best_ms = 40000"), "and only there");

        host.courses.remove("lava_leap");
        List<CupDesk.Closed> closed = desk.tick();
        assertEquals(2, closed.size(), "a deleted course calls off both running Cups on it: " + closed);
        assertEquals(20, balance(alice), "every entry back");
        assertEquals(20, balance(bob), "every entry back");
    }

    @Test
    void onAFreshSlotOnlyTheWeeksOwnLayoutCountsAndItsFlipIsNotAVoid() throws Exception {
        host.now = at(2026, 9, 28, 4, 30); // Monday 04:30: the new week, but last week's layout still stands
        Course lastWeeks = rings(W - 7, 0, "old");
        host.put(lastWeeks);
        // Cups entered while last week's layout still stood (an older build took such entries; the
        // desk now waits for this week's course): the watch still treats the flip as the Cup going up.
        for (UUID p : List.of(alice, bob)) {
            assertNull(dao.enter(new CupKey("fresh_rings", W), p, 5, "Sky Rings", host.now, W,
                    CupDesk.layout(lastWeeks).encode()));
        }
        assertFalse(race(alice, lastWeeks, 40_000), "a run on last week's layout sets no time in this week's Cup");
        assertEquals(List.of(), desk.tick(), "nothing to settle or call off");

        Course thisWeeks = rings(W, 0, "new");
        host.put(thisWeeks); // the scheduled build flips the week's own layout in
        assertEquals(List.of(), desk.tick(), "the flip is the Cup's layout going up, not a change");
        assertEquals(CupDesk.layout(thisWeeks).encode(), dao.layout(new CupKey("fresh_rings", W)),
                "the Cup now remembers the week's own layout");
        assertTrue(race(alice, thisWeeks, 40_000), "a run on it counts");
        assertEquals(15, balance(alice), "nobody was refunded");

        host.put(rings(W, 1, "reroll")); // an admin rerolls it mid-week
        List<CupDesk.Closed> closed = desk.tick();
        assertEquals(1, closed.size(), "a new layout mid-week calls the Cup off");
        assertEquals(CupPlan.VoidReason.CHANGED, closed.get(0).plan().reason());
        assertEquals(20, balance(alice), "every entry back");
        assertEquals(20, balance(bob), "every entry back");
        assertTrue(queued(bob).contains("was called off because the course changed"), "and told why: " + queued(bob));
        assertEquals(CupRefusal.CALLED_OFF, desk.enter(carol, rings(W, 1, "reroll")), "closed for the week");
    }

    // ---- calling off --------------------------------------------------------------------------------

    @Test
    void aDeletedCourseCallsItsCupOffAndRefundsEveryone() throws Exception {
        switchOn(lava);
        threeRace(lava);
        host.courses.remove("lava_leap");
        List<CupDesk.Closed> closed = desk.tick();
        assertEquals(CupPlan.VoidReason.DELETED, closed.get(0).plan().reason());
        for (UUID p : List.of(alice, bob, carol)) {
            assertEquals(20, balance(p), "every entry back in full");
            assertEquals("&eThe Weekly Cup on Lava Leap was called off because the course was removed. Your 5 tokens"
                    + " came back.", queued(p), "and told why, the course named from its id now its row is gone");
        }
        assertEquals(0, count("SELECT SUM(delta) FROM token_ledger WHERE source LIKE 'GAMES_CUP_%'"),
                "a called-off Cup nets 0");
        host.now = ROLLOVER;
        assertEquals(List.of(), desk.tick(), "and is never settled as well");
    }

    @Test
    void aClosedCourseCallsItsCupOff() throws Exception {
        switchOn(lava);
        threeRace(lava);
        host.put(lava.withEnabled(false));
        assertEquals(CupPlan.VoidReason.CLOSED, desk.tick().get(0).plan().reason(), "closed mid-week");
        assertEquals(20, balance(carol), "refunded");
    }

    @Test
    void aLayoutEditCallsItsCupOffButARenameDoesNot() throws Exception {
        switchOn(lava);
        threeRace(lava);
        host.put(lava.withName("Lava Leap II").withTier(Tier.HARD));
        assertEquals(List.of(), desk.tick(), "a new name or tier is not a new layout");
        host.put(lava.withFinish(new Course.Mark(25, 64, 0, 1.5)));
        List<CupDesk.Closed> closed = desk.tick();
        assertEquals(CupPlan.VoidReason.CHANGED, closed.get(0).plan().reason(), "a moved finish is");
        assertEquals(20, balance(alice), "refunded");
        assertEquals(CupRefusal.CALLED_OFF, desk.enter(alice, lava), "and the week is closed on that course");
    }

    @Test
    void aFreshSlotSwitchedOffOrClearedCallsItsCupOffButAnEngineThatIsOffDoesNot() throws Exception {
        Course r = rings(W, 0, "p");
        host.put(r);
        assertNull(desk.enter(alice, r));
        assertNull(desk.enter(bob, r));
        host.wanted.put("fresh_rings", null);
        assertEquals(List.of(), desk.tick(), "Fresh Courses not running (a reload): leave the Cup alone");
        host.wanted.put("fresh_rings", true);
        assertEquals(List.of(), desk.tick(), "the slot is on");
        host.wanted.put("fresh_rings", false);
        assertEquals(CupPlan.VoidReason.CLOSED, desk.tick().get(0).plan().reason(), "an admin switched the slot off");
        assertEquals(20, balance(alice), "refunded");

        Course boat = fresh("fresh_boat", TrialKind.BOAT, "Ice Boat", tag("fresh_boat", W, 0, "q", null));
        host.put(boat);
        assertNull(desk.enter(carol, boat));
        host.courses.remove("fresh_boat");
        assertEquals(CupPlan.VoidReason.CLOSED, desk.tick().get(0).plan().reason(),
                "a Fresh slot's row going means the slot closed");
    }

    @Test
    void aCourseThatCantBeReadIsLeftAloneAndSaidOnce() throws Exception {
        switchOn(lava);
        threeRace(lava);
        host.readFails = true;
        assertEquals(List.of(), desk.tick(), "never void on a guess");
        assertEquals(List.of(), desk.tick());
        assertEquals(15, balance(alice), "nobody refunded");
        assertEquals(1, host.warnings(), "one WARN, not one a minute");
        host.readFails = false;
        host.now = ROLLOVER;
        assertEquals(CupPlan.Outcome.PRIZES, desk.tick().get(0).plan().outcome(), "it is settled as usual");
    }

    @Test
    void anAdminsCallOffIsAtOnceAndACupNobodyEnteredStaysOpen() throws Exception {
        switchOn(lava);
        threeRace(lava);
        CupDesk.Closed c = desk.voidNow("lava_leap", CupPlan.VoidReason.STOPPED, "Lava Leap");
        assertNotNull(c, "called off");
        assertEquals(15, c.plan().paidOut(), "15 tokens back");
        assertEquals(3, c.plan().payouts().size(), "to 3 players");
        assertTrue(queued(bob).contains("The Weekly Cup on Lava Leap was called off because an admin stopped it."),
                queued(bob));
        Course cliffs = handBuilt("cliffs", "Cliffs");
        host.put(cliffs);
        switchOn(cliffs);
        assertNull(desk.voidNow("cliffs", CupPlan.VoidReason.CHANGED, "Cliffs"), "nobody in: nothing to call off");
        assertNull(desk.enter(alice, cliffs), "so its Cup stays open");
    }

    @Test
    void withEntriesClosedTheCupsAlreadyPaidIntoStillFinish() throws Exception {
        switchOn(lava);
        assertNull(desk.enter(alice, lava));
        assertNull(desk.enter(bob, lava));
        assertTrue(race(alice, lava, 40_000));
        assertTrue(race(bob, lava, 41_000));
        host.settings = new CupSettings(false, 5, 10);
        assertEquals(CupRefusal.OFF, desk.enter(carol, lava), "games.cup.enabled: false - no new entries");
        assertEquals(20, balance(carol));
        assertTrue(desk.view(lava, alice).shown(), "alice still sees the Cup she is in");
        Course cliffs = handBuilt("cliffs", "Cliffs");
        switchOn(cliffs);
        assertFalse(desk.view(cliffs, carol).shown(), "a Cup nobody entered is hidden while entries are closed");
        host.now = ROLLOVER;
        assertEquals(CupPlan.Outcome.PRIZES, desk.tick().get(0).plan().outcome(), "the running Cup is paid out");
        assertEquals(15 + 14, balance(alice), "70% of 20");
    }

    @Test
    void theHiddenSwitchReadsOnlyOff() {
        assertTrue(CupDesk.hidden("off"), "/hcm play cup off");
        assertTrue(CupDesk.hidden("OFF"), "any case");
        assertFalse(CupDesk.hidden(null), "unset: shown");
        assertFalse(CupDesk.hidden("on"), "on: shown");
    }

    @Test
    void aLayoutIsItsGeometryNotItsNameAndAFreshLayoutIsItsEdition() {
        CupLayout base = CupDesk.layout(lava);
        assertTrue(base.same(CupDesk.layout(lava.withName("Other").withTier(Tier.HARD).withPinned(true))),
                "name, tier and pinning aren't layout");
        assertFalse(base.same(CupDesk.layout(lava.withFinish(new Course.Mark(21, 64, 0, 1.5)))), "the finish is");
        assertFalse(base.same(CupDesk.layout(lava.withFallY(50.0))), "and the fall height");
        Course r = rings(W, 0, "p");
        assertTrue(CupDesk.layout(r).same(CupDesk.layout(r.withName("Sky Rings!"))), "a heal of the same layout");
        assertFalse(CupDesk.layout(r).same(CupDesk.layout(rings(W, 1, "p"))), "a reroll");
        assertFalse(CupDesk.layout(r).same(CupDesk.layout(rings(W, 0, "q"))), "a promoted other layout");
    }

    /**
     * fx2-C #12: at 03:56 on the Monday the Cup is paid (the 04:00 restart is holding every run), an
     * entry could never set a Cup time: it is refused, nothing is paid, and the screen says why.
     */
    @Test
    void anEntryInTheLastRestartHoldBeforeThePayoutIsRefusedAndNothingIsPaid() throws Exception {
        switchOn(lava);
        host.now = ROLLOVER - 4 * 60_000L; // Monday 03:56
        int before = tokens.get(alice).tokens();
        assertEquals(CupRefusal.CLOSING, desk.enter(alice, lava), "no run could set a Cup time before 04:00");
        assertEquals(before, tokens.get(alice).tokens(), "nothing was taken");
        assertEquals(0, dao.entries(desk.key(lava.id())).size(), "and no entry was written");
        CupDesk.View v = desk.view(lava, alice);
        assertEquals(CupRefusal.CLOSING, v.refusal(), "the screen shows why");
        assertTrue(CupWords.enterName(v).contains("nearly over"), "in plain words: " + CupWords.enterName(v));
        host.now = ROLLOVER - 6 * 60_000L; // 03:54
        assertNull(desk.enter(alice, lava), "a minute before the hold, entries are still taken");
    }
}
