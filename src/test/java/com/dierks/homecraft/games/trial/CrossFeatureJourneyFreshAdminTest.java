package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.FreshBench;
import com.dierks.homecraft.games.gen.engine.GenAdminKeys;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.gui.games.daily.FreshAdmin;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 7, the Fresh admin tools end to end: preview next week's course from the course screen's tools,
 * test-run it (it records nothing anywhere), choose it; at the change the next set goes up with the chosen
 * seed (its blocks already right) on a clean board, last week's Cup settles once and the new week's Cup
 * starts from nobody (the real engine, {@link FreshAdmin}, {@code GenAdmin}, Time Trials' finish, the Weekly
 * Cup and the world sessions; {@code TimeTrials.testPreview}/{@code begin} are mirrored as the tester).
 */
class CrossFeatureJourneyFreshAdminTest {

    /** Tuesday 29 September 2026, 04:00:40: just after the week's rollover. */
    private static final long T0 = GamesBench.at(2026, 9, 29, 4, 0) + 40_000;
    private static final String SLOT = "fresh_parkour_easy";
    private static final long MON_28_SEP = 20724;
    private static final long MON_5_OCT = MON_28_SEP + 7;

    private JourneyBench j;
    private FreshBench fresh;
    private Player ava;
    private Player ben;
    private Player admin;
    /** The preview the admin's test run is on. */
    private Course tested;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC), "trials", TimeTrialsSettings.defaults(),
                "cup", CupSettings.defaults());
        ava = j.player("Ava", "SURVIVAL", "red");
        ben = j.player("Ben", "SURVIVAL", "blue");
        admin = j.player("Admin", "SURVIVAL", "red");
        fresh = new FreshBench(j.bench, () -> FreshBench.people(j.rail, j.players.keySet()), this::testRun, SLOT);
        assertTrue(fresh.driveUntil(() -> fresh.liveTag(SLOT) != null && j.trials.openCourse(SLOT) != null, 120),
                "this week's set goes up: " + fresh.logged());
        assertEquals('A', fresh.liveTag(SLOT).half(), "in half A");
        assertEquals(MON_28_SEP, fresh.liveTag(SLOT).day(), "this week's");
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    /** {@code TimeTrials.testPreview}/{@code startTest}/{@code begin}, mirrored: a test run on the preview. */
    private void testRun(Player p, Course preview, Runnable again) {
        tested = preview;
        assertNull(j.rail.enterNow(id(p), j.trials, preview.id(), JourneyBench.spot("games", preview.start()), q -> {
        }), "the admin goes to the preview's start");
        TrialRun run = new TrialRun(id(p), preview, true, 0);
        run.progress = new Progress(preview, preview.start().point(), j.race.nanos);
        run.phase = TrialRun.Phase.RUNNING;
        j.trials.replaceRun(run);
    }

    /** A counted run on the live course, from its start. */
    private void countedRun(Player p) {
        Course c = j.trials.course(SLOT);
        assertNull(j.rail.enterNow(id(p), j.trials, c.id(), JourneyBench.spot("games", c.start()), q -> {
        }), p.getName() + " goes to the start");
        TrialRun run = new TrialRun(id(p), c, false, 0);
        run.progress = new Progress(c, c.start().point(), j.race.nanos);
        run.phase = TrialRun.Phase.RUNNING;
        j.trials.replaceRun(run);
    }

    /** {@code TimeTrials.finish}; only its scheduled result screen (GamesService.later) may need a server. */
    private void cross(Player p, long ms) {
        try {
            j.race.cross(id(p), ms);
        } catch (NullPointerException e) {
            assertTrue(Arrays.stream(e.getStackTrace()).anyMatch(f -> f.getClassName().endsWith("GamesService")
                    && f.getMethodName().equals("later")), "only the finish's scheduled result screen needs a server: " + e);
        }
        j.rail.leave(id(p), EndReason.FINISH);
        j.rail.arriveAll();
        assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
    }

    /** The admin's screen: its tools now, and a click on {@code kind}. Whether it asked "Sure?". */
    private boolean click(FreshAdmin.Kind kind) {
        GenOps.Tools t = fresh.engine().tools(SLOT);
        List<FreshAdmin.Tool> tools = FreshAdmin.tools(Slots.of(SLOT), t);
        FreshAdmin.Tool tool = tools.stream().filter(x -> x.kind() == kind).findFirst().orElse(null);
        assertNotNull(tool, kind + " is one of the tools: " + tools.stream().map(FreshAdmin.Tool::kind).toList());
        boolean[] asked = {false};
        FreshAdmin.click(tool, x -> asked[0] = true, words -> fresh.admin().handle(ear(), words));
        if (asked[0]) {
            FreshAdmin.yes(tool, words -> fresh.admin().handle(ear(), words));
        }
        return asked[0];
    }

    private Player ear() {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> {
                    if (m.getName().equals("sendMessage") && a != null && a.length == 1) {
                        said.add(a[0] instanceof net.kyori.adventure.text.Component c
                                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(c) : String.valueOf(a[0]));
                    }
                    try {
                        return m.invoke(admin, a);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private long count(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = j.bench.connection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    @Test
    void aPreviewTestedChosenAndFlippedRecordsNothingForTheTestAndStartsTheNextWeekClean() throws Exception {
        Course live = j.trials.course(SLOT);
        GenTag week = live.gen();

        // 1. Ava enters this week's Cup and runs 50.0
        assertNull(j.cup.desk().enter(id(ava), live), "Ava enters the Cup");
        countedRun(ava);
        fresh.drive(51);
        cross(ava, 50_000);
        assertEquals(50_000L, j.cupTime(id(ava), SLOT), "Ava's Cup time");

        // 2. The course screen's admin tools: preview next week's course
        assertTrue(FreshAdmin.shown(true, SLOT), "an admin sees the tools on a Fresh course");
        assertFalse(click(FreshAdmin.Kind.PREVIEW_NEXT), "a preview needs no Sure?");
        assertTrue(fresh.driveUntil(() -> fresh.engine().tools(SLOT).preview(), 60), "the preview stands: " + said);
        String heard = String.join("\n", said);
        assertTrue(heard.contains("for Mon 5 Oct-Sun 11 Oct"), "for next week's set: " + heard);
        assertTrue(heard.contains("ready in half B"), "in half B: " + heard);
        long previewSeed = fresh.engine().tools(SLOT).previewSeed();

        // 3. Test it: the admin's run records nothing
        assertFalse(click(FreshAdmin.Kind.TEST), "a test needs no Sure?");
        assertNotNull(tested, "the tester started a test run on the preview");
        GenTag previewTag = tested.gen();
        assertEquals('B', previewTag.half(), "the preview stands in half B");
        assertEquals(MON_5_OCT, previewTag.day(), "made as next week's set");
        fresh.drive(31);
        cross(admin, 30_000);
        assertTrue(j.bench.heard(id(admin)).contains("Test run - nothing was recorded."), j.bench.heard(id(admin)));
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores WHERE board = ?", GenBoards.day(previewTag)),
                "nothing on the preview's board (the next set's board)");
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores WHERE board = ?", GenBoards.stars(previewTag)),
                "nor on its stars board");
        assertEquals(0, j.rows("game_rewards", "player", id(admin)), "the admin was paid nothing");
        assertEquals(0, j.rows("cup_entries", "player", id(admin)), "and has no Cup entry");
        assertEquals(0, j.told.courses(id(admin)), "and no quest step");
        assertFalse(fresh.engine().live(SLOT, previewTag), "the preview is never live");
        assertEquals(week, fresh.liveTag(SLOT), "this week's course is unchanged");
        assertEquals(50_000L, j.cupTime(id(ava), SLOT), "Ava's Cup time is unchanged");

        // 4. Choose it (a confirm tool)
        said.clear();
        assertTrue(click(FreshAdmin.Kind.CHOOSE), "choose asks Sure? first");
        assertTrue(String.join("\n", said).contains("is this preview"), "chosen: " + said);
        assertNotNull(fresh.meta(GenAdminKeys.choose(SLOT)), "the one-set pin is stored");
        assertTrue(String.join("\n", fresh.engine().status(SLOT)).contains("next set: chosen seed"),
                "the status says so: " + fresh.engine().status(SLOT));

        // 5. Monday 5 October, 04:00:30: last week's Cup rolls over; the flip uses the chosen seed
        long monday = GamesBench.at(2026, 10, 5, 4, 0) + 30_000;
        j.bench.move(monday - j.bench.now());
        int avaBefore = j.bench.balance(id(ava));
        j.cup.desk().tick();
        CupKey lastWeek = new CupKey(SLOT, MON_28_SEP);
        assertSame(CupPlan.Outcome.REFUND_ALONE, j.cup.desk().dao().settledAs(lastWeek), "Ava alone: her entry back");
        assertEquals(avaBefore + 5, j.bench.balance(id(ava)), "her 5 back, once");
        j.cup.desk().tick();
        assertEquals(avaBefore + 5, j.bench.balance(id(ava)), "settled once");
        CupRefusal early = j.cup.desk().enter(id(ben), j.trials.course(SLOT));
        assertEquals(CupRefusal.NOT_UP_YET, early, "the Cup starts when the week's course is up");
        assertEquals("The Cup starts when this week's course is up.", early.message(5), "and says so");
        long writes = fresh.writes();
        assertTrue(fresh.driveUntil(() -> fresh.liveTag(SLOT).day() == MON_5_OCT, 300), "the change: "
                + fresh.logged());
        GenTag next = fresh.liveTag(SLOT);
        assertEquals(previewSeed, next.seed(), "the chosen seed is up");
        assertEquals('B', next.half(), "in half B, where the preview stood");
        assertEquals(0, next.reroll(), "the set's own build");
        assertEquals(writes, fresh.writes(), "its blocks were already right: nothing written");
        assertEquals(GenBoards.day(previewTag), GenBoards.day(next), "the test run was on this very board");
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores WHERE board = ?", GenBoards.day(next)),
                "and it starts empty");

        // 6. Ben enters the new week's Cup and runs the chosen course at 47.0
        Course chosen = j.trials.course(SLOT);
        assertEquals(next.editionKey(), chosen.gen().editionKey(), "Time Trials reads the new set");
        assertNull(j.cup.desk().enter(id(ben), chosen), "Ben enters the new week's Cup");
        countedRun(ben);
        fresh.drive(48);
        cross(ben, 47_000);
        CupKey thisWeek = new CupKey(SLOT, MON_5_OCT);
        CupRules.LivePool pool = CupRules.livePool(j.cup.desk().dao().entries(thisWeek), 10);
        assertEquals(1, pool.in(), "one entrant");
        assertEquals(5, pool.tokens(), "a pool of one entry");
        assertNull(j.cup.desk().dao().entry(thisWeek, id(ava)), "Ava's old time didn't carry over");
        assertEquals(47_000L, j.cup.desk().dao().entry(thisWeek, id(ben)).bestMs(), "Ben's Cup time");
        assertEquals(1, count("SELECT COUNT(*) FROM game_rewards WHERE player = ? AND kind = 'DAILY_CLEAR'",
                id(ben).toString()), "Ben's first finish of the set, once");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rewards WHERE player = ? AND ref = ?", id(admin).toString(),
                SkillRewards.freshClearRef(SLOT, next.edition())), "the admin's first finish of the set is still unpaid");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
        assertEquals(0, fresh.severe(), "the engine logged nothing severe: " + fresh.logged());
    }
}
