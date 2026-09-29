package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup through the real Time Trials (EVENTS-OWNER-DECISIONS §D2), with the real framework
 * and a real database: a counted run settled by {@link TimeTrials#settleCounted} sets the Cup time
 * (a warm-up never does), and the course editor's {@code delete}, {@code disable} and layout edits
 * call the week's Cup off and refund every entry, asking for {@code confirm} first.
 *
 * <p>Pinned here besides: a deleted course's Cup switch goes with it, so a new course with its id
 * starts from the default (a hand-built one off); {@code disable} asks for {@code confirm} and
 * warns of the Cup when anyone is in it; a call-off that fails is said to the admin, never reported
 * as done, and the Cup's minute check then refunds everyone; and a course tile reads the Cup once
 * for both its NAME and its lore.
 */
class WeeklyCupTrialsTest {

    /** Tuesday 29 September 2026, noon (the kit's zone): a Cup week is open. */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long WEEK = java.time.LocalDate.of(2026, 9, 28).toEpochDay();
    private static final String ID = "lava_leap";
    /** A hand-built parkour course, open. */
    private static final Course LAVA = new Course(ID, TrialKind.PARKOUR, "Lava Leap", Tier.EASY, "games",
            new Course.Spot(0, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 1.5)), new Course.Mark(20, 64, 0, 1.5),
            null, null, true, false, 1);

    private GamesBench bench;
    private GamesService games;
    private TimeTrials trials;
    private WeeklyCup cup;
    private Player ava;
    private Player ben;
    private Player admin;

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC), "trials", TimeTrialsSettings.defaults(),
                "cup", CupSettings.defaults());
        games = bench.games();
        trials = (TimeTrials) games.game("trials");
        cup = (WeeklyCup) games.game("cup");
        assertNotNull(trials, "Time Trials is built from its spec");
        assertNotNull(cup, "and the Weekly Cup");
        save(LAVA);
        cup.desk().dao().choose(ID, true); // a hand-built course runs a Cup once an admin says so
        ava = bench.player("Ava");
        ben = bench.player("Ben");
        admin = bench.player("Admin");
        bench.give(id(ava), 20);
        bench.give(id(ben), 20);
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private void save(Course c) throws SQLException {
        bench.dao().saveCourse(new GamesDao.CourseRow(c.id(), "trials", c.kind().id(), c.name(), c.world(),
                c.enabled(), CourseCodec.encode(c), c.rev(), 0, 0), false);
        trials.forget();
    }

    /** The course as Time Trials reads it back from its row. */
    private Course lava() throws SQLException {
        return trials.store().get(ID);
    }

    private void enterBoth() throws SQLException {
        assertNull(cup.desk().enter(id(ava), lava()), "Ava enters this week's Cup");
        assertNull(cup.desk().enter(id(ben), lava()), "Ben enters");
        assertEquals(15, bench.balance(id(ava)), "for 5 tokens");
    }

    /** The player's Cup time this week, or {@code null}. */
    private Long cupTime(Player p) throws SQLException {
        var e = cup.desk().dao().entry(new CupKey(ID, WEEK), id(p));
        return e == null || !e.hasTime() ? null : e.bestMs();
    }

    private void command(String... args) {
        trials.admin().handle(admin, args);
    }

    private String heard(Player p) {
        return bench.heard(id(p));
    }

    private void failCallOffs(boolean fail) throws SQLException {
        try (Statement st = bench.connection().createStatement()) {
            st.execute(fail ? "CREATE TRIGGER no_settle BEFORE INSERT ON cup_settlements BEGIN SELECT RAISE(ABORT, "
                    + "'disk full'); END" : "DROP TRIGGER no_settle");
        }
    }

    // ---- a counted run -----------------------------------------------------------------------------

    @Test
    void aSoloCountedRunSettledByTimeTrialsSetsTheCupTimeAndAWarmUpNever() throws Exception {
        enterBoth();
        bench.move(60_000);
        trials.settleCounted(ava, new TrialRun(id(ava), lava(), false, 0), 44_000,
                new FairPlay.Verdict(FairPlay.Kind.COUNTED, null));
        assertEquals(44_000L, cupTime(ava), "Time Trials' own settleCounted sets the Cup time");
        assertTrue(heard(ava).contains("New Cup time on Lava Leap: 0:44.0"), "and says so: " + heard(ava));
        assertEquals(44_000L, bench.dao().best(id(ava), TimeTrials.SPEC.id(), com.dierks.homecraft.games.Scores.course(ID)),
                "the same run is on the course's board: free play still counts as ever");
        assertNull(cupTime(ben), "Ben ran nothing");

        bench.move(60_000);
        TrialRun warm = new TrialRun(id(ava), lava(), false, 0);
        warm.warmup = true; // a run carrying the warm-up flag, however it got here
        trials.settleCounted(ava, warm, 30_000, new FairPlay.Verdict(FairPlay.Kind.COUNTED, null));
        assertEquals(44_000L, cupTime(ava), "a warm-up never sets a Cup time (D3)");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    // ---- the course editor -------------------------------------------------------------------------

    @Test
    void deletingACourseRefundsItsCupAndForgetsItsSwitch() throws Exception {
        enterBoth();
        command(ID, "delete");
        assertTrue(heard(admin).contains("This week's Cup on it (2 in) is called off and every entry refunded."),
                "the confirm prompt warns of the Cup: " + heard(admin));
        assertEquals(15, bench.balance(id(ava)), "nothing moved yet");

        command(ID, "delete", "confirm");
        assertNull(lava(), "deleted");
        assertEquals(20, bench.balance(id(ava)), "Ava's entry back");
        assertEquals(20, bench.balance(id(ben)), "Ben's entry back");
        assertTrue(heard(admin).contains("This week's Cup on Lava Leap was called off: 10 tokens went back to 2"
                + " player(s)."), heard(admin));
        assertEquals(CupPlan.Outcome.VOIDED, cup.desk().dao().settledAs(new CupKey(ID, WEEK)));
        assertNull(cup.desk().dao().chosen(ID), "the deleted course's Cup switch went with it");

        save(LAVA.withName("Lava Leap Again"));
        assertFalse(cup.desk().runsCup(lava()), "a new hand-built course with its id starts off, as every one does");
        assertEquals(0, bench.severe(), "nothing threw");
    }

    @Test
    void disablingACourseWithCupEntrantsAsksForConfirmThenRefunds() throws Exception {
        enterBoth();
        command(ID, "disable");
        assertTrue(heard(admin).contains("/hcm games course lava_leap disable confirm"), "it asks first: "
                + heard(admin));
        assertTrue(heard(admin).contains("This week's Cup on it (2 in) is called off and every entry refunded."),
                "and warns of the Cup: " + heard(admin));
        assertTrue(lava().enabled(), "the course is still open");
        assertEquals(15, bench.balance(id(ava)), "and nobody was refunded: a stray disable ends nobody's week");

        command(ID, "disable", "confirm");
        assertFalse(lava().enabled(), "closed");
        assertEquals(20, bench.balance(id(ava)), "refunded");
        assertTrue(heard(admin).contains("This week's Cup on Lava Leap was called off"), heard(admin));
        assertTrue(bench.dao().prefsLike(id(ben), com.dierks.homecraft.games.ChanceRounds.NOTICE).values().stream()
                .anyMatch(l -> l.contains("was called off because the course closed")), "Ben is told why");
    }

    @Test
    void disablingACourseNobodyEnteredNeedsNoConfirm() throws Exception {
        command(ID, "disable");
        assertFalse(lava().enabled(), "no Cup to call off: closed at once, as before");
        assertFalse(heard(admin).contains("confirm"), heard(admin));
    }

    @Test
    void aLayoutEditAsksThenRefundsTheCup() throws Exception {
        // No world yet, so the editor asks no server about the world's floor (the bench has none). The
        // Cup doesn't mind: it runs on the course's row whether or not it is open.
        save(LAVA.withWorld(""));
        enterBoth();
        command(ID, "fall", "50");
        assertTrue(heard(admin).contains("This week's Cup on it (2 in) is called off"), heard(admin));
        assertEquals(1, lava().rev(), "not changed yet");
        assertEquals(15, bench.balance(id(ben)));

        command(ID, "fall", "50", "confirm");
        assertEquals(2, lava().rev(), "a new layout");
        assertEquals(20, bench.balance(id(ben)), "refunded");
        assertTrue(bench.dao().prefsLike(id(ben), com.dierks.homecraft.games.ChanceRounds.NOTICE).values().stream()
                .anyMatch(l -> l.contains("was called off because the course changed")), "and told why");
    }

    @Test
    void aCallOffThatFailsIsSaidAndTheMinuteCheckRefundsLater() throws Exception {
        enterBoth();
        failCallOffs(true);
        command(ID, "disable", "confirm");
        assertFalse(lava().enabled(), "the course closed");
        assertTrue(heard(admin).contains("couldn't be called off right now"), "the admin is told: " + heard(admin));
        assertFalse(heard(admin).contains("was called off:"), "never that it was done: " + heard(admin));
        assertEquals(15, bench.balance(id(ava)), "nothing refunded yet");

        failCallOffs(false);
        cup.desk().tick(); // the Cup's minute task
        assertEquals(20, bench.balance(id(ava)), "the minute check found the closed course and refunded");
        assertEquals(20, bench.balance(id(ben)));
    }

    // ---- the tiles ---------------------------------------------------------------------------------

    @Test
    void aTileReadsTheCupOnceForItsNameAndLore() throws Exception {
        Course c = lava();
        CupLink.Tile t = CupLink.tile(games, ava, c);
        assertEquals(CupLink.tileSuffix(games, ava, c), t.suffix(), "the NAME's part");
        assertEquals(CupLink.tileLines(games, ava, c), t.lines(), "the lore's part");
        assertEquals(" &6· Cup: 5 tokens", t.suffix(), "the entry, in the tile's NAME for Bedrock");
        enterBoth();
        assertEquals(" &6· in the Cup, pool 20", CupLink.tile(games, ava, c).suffix(), "in, with the live pool");
        assertEquals(" &6· Cup: 5 tokens, pool 20", CupLink.tile(games, admin, c).suffix(), "or what it costs");
        assertEquals(CupRules.DEFAULT_ENTRY, cup.settings().entry());
    }
}
