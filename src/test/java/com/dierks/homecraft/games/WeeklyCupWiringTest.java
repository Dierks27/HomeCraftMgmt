package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.FairPlay;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup wired into the games framework, against a real database (EVENTS-OWNER-DECISIONS
 * §D2): the real {@link WeeklyCup} game built from its spec, reading its {@code games.cup} settings
 * and the real course rows, ticking on the framework's scheduler inside its guard.
 *
 * <p>Pinned here: a winner who was offline at the rollover is told at their next join, once; a
 * restart after a missed rollover settles it on its first tick, and a restart after that pays
 * nothing more; the Cup is a skill contest, so Take a break's pause never stops an entry; only a
 * counted, timed run reaches the Cup through Time Trials' hook (never a warm-up, a test or a voided
 * run); {@code /hcm play cup off} hides the tile's Cup words while the player still hears how their
 * Cup went; and the admin's {@code status}, {@code settle ... confirm}, {@code void ... confirm} and
 * course switch do what they say, asking for {@code confirm} before they move a token.
 */
class WeeklyCupWiringTest {

    private static final long W = LocalDate.of(2026, 9, 28).toEpochDay();
    private static final long ROLLOVER = GamesKit.at(2026, 10, 5, 4, 0);

    private Host host;
    private GamesService games;
    private WeeklyCup cup;
    private Fake alex;
    private Fake sam;
    private Fake admin;
    private Course lava;

    @BeforeEach
    void setUp() throws Exception {
        host = new Host(GamesKit.at(2026, 9, 29, 12, 0)); // a Tuesday noon, in the week of 28 September
        // the wiring at 0.36's 5 to enter and 10 on top; the shipped 10/20 is pinned in CupSettingsTest
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "cup", new CupSettings(true, 5, 10));
        // Not start(): the Cup's minute task goes through the Bukkit scheduler, which a test has none of.
        // Its tick is the desk's, called by hand below exactly as the task calls it.
        games = GamesKit.service(host, List.of(WeeklyCup.SPEC));
        cup = (WeeklyCup) games.game("cup");
        assertNotNull(cup, "the Weekly Cup is built from its spec");
        lava = new Course("lava_leap", TrialKind.PARKOUR, "Lava Leap", Tier.EASY, "games",
                new Course.Spot(0, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 1.5)),
                new Course.Mark(20, 64, 0, 1.5), null, null, true, false, 1);
        save(lava);
        cup.desk().dao().choose("lava_leap", true);
        alex = new Fake("Alex");
        sam = new Fake("Sam");
        admin = new Fake("Admin");
        host.give(alex.id, 20);
        host.give(sam.id, 20);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private void save(Course c) throws Exception {
        host.dao.saveCourse(new GamesDao.CourseRow(c.id(), "trials", c.kind().id(), c.name(), c.world(), c.enabled(),
                CourseCodec.encode(c), c.rev(), 0, 0), false);
    }

    private static FairPlay.Verdict counted() {
        return new FairPlay.Verdict(FairPlay.Kind.COUNTED, null);
    }

    /** A finish through Time Trials' hook: {@code ms} long, ending a moment after now. */
    private void finish(Fake p, long ms, FairPlay.Verdict verdict, boolean warmup) {
        host.move(ms + 1_000);
        CupLink.finished(games, p.player, lava, ms, verdict, warmup);
    }

    private void enterBoth() throws Exception {
        assertNull(cup.desk().enter(alex.id, lava), "alex enters");
        assertNull(cup.desk().enter(sam.id, lava), "sam enters");
        finish(alex, 40_000, counted(), false);
        finish(sam, 41_000, counted(), false);
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String said(Fake p) {
        return p.heard();
    }

    // ---- the game -----------------------------------------------------------------------------

    @Test
    void theCupIsASkillGameWithNoTileOfItsOwn() {
        assertFalse(cup.kind().chance(), "no chance anywhere: Take a break's chance rules don't apply");
        assertEquals(List.of(), cup.tiles(alex.player), "it lives on the course screens and tiles");
        assertFalse(cup.featurable(), "a week-long contest is never today's pick");
        assertTrue(games.enabled(cup), "open while the games are: " + games.closedReason(cup));
        assertEquals(new CupSettings(true, 5, 10), cup.settings(), "games.cup as this test sets it: on, 5 to enter, top-up 10");
    }

    @Test
    void aPausedPlayerCanStillEnterTheCup() throws Exception {
        assertTrue(games.breaks().pause(alex.id, 7), "alex took a week's break from games of chance");
        assertNull(games.canOpen(alex.player, cup), "the Cup's gate has no chance steps: the pause doesn't stop it");
        assertNull(cup.desk().enter(alex.id, lava), "so alex can enter");
        assertEquals(15, host.balanceOf(alex.id), "for 5 tokens");
    }

    // ---- settling and telling -------------------------------------------------------------------

    @Test
    void anOfflineWinnerIsToldAtTheirNextJoinOnce() throws Exception {
        enterBoth();
        host.time.now = ROLLOVER;
        cup.desk().tick(); // the Cup's minute task
        assertEquals(15 + 14, host.balanceOf(alex.id), "1st: 70% of 20, paid while alex was offline");
        assertEquals(15 + 6, host.balanceOf(sam.id), "2nd: 30%");
        assertFalse(said(alex).contains("Weekly Cup"), "nothing said yet: alex isn't on");

        host.online.put(alex.id, alex.player);
        games.onJoin(alex.player);
        host.runTasks(); // the join's short wait
        assertTrue(said(alex).contains("Weekly Cup on Lava Leap: you came 1st with 0:40.0 - 14 tokens."),
                "told at the next join: " + said(alex));
        int lines = alex.said.size();
        games.onJoin(alex.player);
        host.runTasks();
        assertEquals(lines, alex.said.size(), "and only once");
        assertFalse(games.failed(cup), "the Cup never failed");
    }

    @Test
    void aRestartSettlesTheRolloverItMissedAndTheNextRestartPaysNothingMore() throws Exception {
        enterBoth();
        host.time.now = ROLLOVER + 3L * 3_600_000; // the server was down across the rollover

        WeeklyCup restarted = (WeeklyCup) GamesKit.service(host, List.of(WeeklyCup.SPEC)).game("cup");
        restarted.desk().tick(); // its first tick, a second after the start
        assertEquals(15 + 14, host.balanceOf(alex.id), "settled on the next boot");
        assertEquals(1, count("SELECT COUNT(*) FROM cup_settlements"), "one settlement");

        WeeklyCup again = (WeeklyCup) GamesKit.service(host, List.of(WeeklyCup.SPEC)).game("cup");
        again.desk().tick();
        assertEquals(15 + 14, host.balanceOf(alex.id), "the next boot pays nothing more");
        assertEquals(2, count("SELECT COUNT(*) FROM token_ledger WHERE source = 'GAMES_CUP_PRIZE'"), "two prizes, ever");
    }

    // ---- Time Trials' hook ----------------------------------------------------------------------

    @Test
    void onlyACountedTimedRunSetsACupTime() throws Exception {
        assertNull(cup.desk().enter(alex.id, lava));
        CupKey key = new CupKey("lava_leap", W);
        finish(alex, 30_000, counted(), true);
        assertNull(time(key), "a warm-up lap never sets a Cup time");
        finish(alex, 30_000, new FairPlay.Verdict(FairPlay.Kind.TEST, null), false);
        assertNull(time(key), "nor does a test run");
        finish(alex, 30_000, new FairPlay.Verdict(FairPlay.Kind.VOID, "flying"), false);
        assertNull(time(key), "nor a run that broke a rule");
        finish(alex, 30_000, new FairPlay.Verdict(FairPlay.Kind.STALE, "the course changed"), false);
        assertNull(time(key), "nor a run on a layout that's gone");
        finish(alex, 45_000, counted(), false);
        assertEquals(45_000L, time(key), "a counted, timed run does");
        assertTrue(said(alex).contains("New Cup time on Lava Leap: 0:45.0"), "and says so: " + said(alex));
        finish(sam, 20_000, counted(), false);
        assertNull(cup.desk().dao().entry(key, sam.id), "a player who isn't in has no Cup time");
    }

    private Long time(CupKey key) throws Exception {
        var e = cup.desk().dao().entry(key, alex.id);
        return e == null || !e.hasTime() ? null : e.bestMs();
    }

    // ---- /hcm play cup off --------------------------------------------------------------------

    @Test
    void hidingTheCupHidesItsWordsButNotTheResult() throws Exception {
        assertEquals(" &6· Cup: 5 tokens", CupLink.tileSuffix(games, alex.player, lava),
                "the tile's NAME carries the entry");
        assertTrue(CupLink.tileLines(games, alex.player, lava)
                .contains("&6Enter this week's Cup: 5 tokens. Best time wins the pool."), "and its lore the prompt");
        cup.show(alex.id, false);
        assertTrue(cup.hidden(alex.id), "/hcm play cup off is kept");
        assertEquals("", CupLink.tileSuffix(games, alex.player, lava), "the tile says nothing of the Cup");
        assertEquals(List.of(), CupLink.tileLines(games, alex.player, lava), "nor its lore");
        assertNull(cup.desk().enter(alex.id, lava), "hiding the words never stops an entry");
        assertNull(cup.desk().enter(sam.id, lava));
        finish(alex, 40_000, counted(), false);
        host.time.now = ROLLOVER;
        cup.desk().tick();
        host.online.put(alex.id, alex.player);
        games.onJoin(alex.player);
        host.runTasks();
        assertTrue(said(alex).contains("Weekly Cup on Lava Leap"), "a player in a Cup still hears how it went");
        cup.show(alex.id, true);
        assertFalse(cup.hidden(alex.id), "/hcm play cup on");
    }

    // ---- the admin commands ---------------------------------------------------------------------

    @Test
    void statusListsTheCupsAndOneCoursesStandings() throws Exception {
        enterBoth();
        cup.admin().handle(admin.player, new String[]{"status"});
        assertTrue(said(admin).contains("lava_leap (Lava Leap) - on - Cup pool: 20 tokens · 2 in"), said(admin));
        admin.said.clear();
        cup.admin().handle(admin.player, new String[]{"status", "lava_leap"});
        String out = said(admin);
        assertTrue(out.contains("1. Alex 0:40.0") || out.contains("1. " + alex.id.toString().substring(0, 8)),
                "the fastest first: " + out);
        assertTrue(out.indexOf("0:40.0") < out.indexOf("0:41.0"), "then the next: " + out);
    }

    @Test
    void voidAsksForConfirmThenRefundsEveryone() throws Exception {
        enterBoth();
        cup.admin().handle(admin.player, new String[]{"void", "lava_leap"});
        assertTrue(said(admin).contains("confirm"), "it asks first: " + said(admin));
        assertEquals(15, host.balanceOf(alex.id), "and moves nothing yet");
        cup.admin().handle(admin.player, new String[]{"void", "lava_leap", "confirm"});
        assertTrue(said(admin).contains("Called off the Cup on Lava Leap: 10 tokens back to 2 player(s)."),
                said(admin));
        assertEquals(20, host.balanceOf(alex.id), "refunded");
        assertEquals(CupPlan.Outcome.VOIDED, cup.desk().dao().settledAs(new CupKey("lava_leap", W)));
    }

    @Test
    void settleAsksForConfirmThenPaysTheRunningCupOnce() throws Exception {
        enterBoth();
        cup.admin().handle(admin.player, new String[]{"settle", "lava_leap"});
        assertTrue(said(admin).contains("paid out") && said(admin).contains("confirm"),
                "it shows what it would pay and asks first: " + said(admin));
        assertEquals(15, host.balanceOf(alex.id), "nothing moved yet");
        cup.admin().handle(admin.player, new String[]{"settle", "lava_leap", "confirm"});
        assertEquals(15 + 14, host.balanceOf(alex.id), "paid");
        admin.said.clear();
        cup.admin().handle(admin.player, new String[]{"settle", "lava_leap", "confirm"});
        assertTrue(said(admin).contains("Nobody is in a running Cup"), "a second settle finds nothing: " + said(admin));
        assertEquals(15 + 14, host.balanceOf(alex.id), "paid once");
    }

    @Test
    void switchingACoursesCupOffWithEntrantsCallsItOffAfterAConfirm() throws Exception {
        enterBoth();
        cup.admin().handle(admin.player, new String[]{"off", "lava_leap"});
        assertTrue(said(admin).contains("confirm"), said(admin));
        assertTrue(cup.desk().runsCup(lava), "still on until confirmed");
        cup.admin().handle(admin.player, new String[]{"off", "lava_leap", "confirm"});
        assertFalse(cup.desk().runsCup(lava), "off");
        assertEquals(20, host.balanceOf(sam.id), "this week's Cup was refunded");
        cup.admin().handle(admin.player, new String[]{"default", "lava_leap"});
        assertFalse(cup.desk().runsCup(lava), "a hand-built course's default is off");
        cup.admin().handle(admin.player, new String[]{"on", "nope"});
        assertTrue(said(admin).contains("No time-trial course called 'nope'"), said(admin));
        assertEquals(List.of("status", "settle"), cup.admin().tab(admin.player, new String[]{"s"}), "tab completion");
    }

    @Test
    void aSwitchOffWhoseCallOffFailsSaysSoAndChangesNothing() throws Exception {
        enterBoth();
        try (var st = host.connection.createStatement()) { // the disk refuses the call-off's settlement row
            st.execute("CREATE TRIGGER no_settle BEFORE INSERT ON cup_settlements BEGIN SELECT RAISE(ABORT, 'disk full');"
                    + " END");
        }
        cup.admin().handle(admin.player, new String[]{"off", "lava_leap", "confirm"});
        assertTrue(said(admin).contains("couldn't be called off"), "the admin is told it failed: " + said(admin));
        assertFalse(said(admin).contains("runs no Weekly Cup"), "and never that it was done: " + said(admin));
        assertTrue(cup.desk().runsCup(lava), "the switch wasn't saved: the Cup its entrants paid into stays in sight");
        assertEquals(15, host.balanceOf(alex.id), "nothing was refunded");
        try (var st = host.connection.createStatement()) {
            st.execute("DROP TRIGGER no_settle");
        }
        admin.said.clear();
        cup.admin().handle(admin.player, new String[]{"off", "lava_leap", "confirm"});
        assertTrue(said(admin).contains("runs no Weekly Cup. This week's was called off: 10 tokens back to 2 player(s)."),
                "tried again, it is done and says so: " + said(admin));
        assertFalse(cup.desk().runsCup(lava), "off");
        assertEquals(20, host.balanceOf(alex.id), "refunded");
    }

    @Test
    void aFinishOnLastWeeksFreshLayoutSaysWhyItSetNoCupTime() throws Exception {
        long w = W - 7;
        GenTag old = new GenTag("fresh_rings", "gen", 1, w, 0, 42L, 'A', "old", 30_000, 34_000, 40_000, List.of(),
                List.of(), 0L, 7, null);
        Course rings = new Course("fresh_rings", TrialKind.ELYTRA, "Sky Rings", Tier.MEDIUM, "games",
                new Course.Spot(0, 90, 0, 0, 0), List.of(new Course.Mark(40, 90, 0, 4)), new Course.Mark(80, 90, 0, 4),
                null, null, true, false, 1, old);
        host.time.now = GamesKit.at(2026, 9, 28, 4, 30); // Monday 04:30: last week's Sky Rings still stands
        // A Cup entered while it stood (an older build took such entries; the Cup now waits for this week's).
        assertNull(cup.desk().dao().enter(new CupKey("fresh_rings", W), alex.id, 5, "Sky Rings", host.time.now, W,
                com.dierks.homecraft.games.cup.live.CupDesk.layout(rings).encode()));
        host.move(41_000);
        CupLink.finished(games, alex.player, rings, 40_000, counted(), false);
        assertTrue(said(alex).contains("That run was on last week's course, so it doesn't set a Cup time."),
                "the finish says why it set no Cup time: " + said(alex));
        assertFalse(cup.desk().dao().entry(new CupKey("fresh_rings", W), alex.id).hasTime(), "and it set none");
    }

    @Test
    void anUnreadableCupBlockTakesNoEntriesButStillSettlesTheCupsRunning() throws Exception {
        enterBoth();
        host.config = new GamesConfig.Parsed(host.config.common(), host.config.settings(), Set.of("cup"));
        assertTrue(games.enabled(cup), "junk in games.cup doesn't close the Cup: " + games.closedReason(cup));
        assertNull(games.closedReason(cup), "so its minute task (which checks exactly this) keeps running");
        assertFalse(cup.settings().enabled(), "but it takes no new entries");
        host.give(admin.id, 20);
        assertEquals(com.dierks.homecraft.games.cup.CupRefusal.OFF, cup.desk().enter(admin.id, lava), "refused");
        assertTrue(cup.statusLines().get(0).contains("games.cup can't be read"), "/hcm games status says why: "
                + cup.statusLines());
        finish(alex, 39_000, counted(), false);
        assertEquals(39_000L, time(new CupKey("lava_leap", W)), "an entrant's counted run still sets a Cup time");
        host.time.now = ROLLOVER;
        cup.desk().tick();
        assertEquals(15 + 14, host.balanceOf(alex.id), "and the Cup is paid at its rollover on the shipped numbers");
        assertEquals(15 + 6, host.balanceOf(sam.id), "2nd: 30%");
    }

    @Test
    void theStatusLineSaysWhenTheCupsArePaid() {
        List<String> lines = cup.statusLines();
        assertTrue(lines.get(0).startsWith("entries open: 5 tokens, top-up 10"), lines.toString());
        assertEquals("0 running this week, paid Mon 5 Oct 4:00 AM", lines.get(1),
                "the week's end: the quests' week start at the Fresh Courses' 04:00");
    }
}
