package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.FreshBench;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's "a preview before switching it on" (CV final gate) end to end, with Ice Boat off as it
 * ships and never built: the admin tools' Preview and Try on the switched-off course build its preview
 * in the spare half and run it, and the run records and pays nothing anywhere; the course stays closed
 * to players and nothing goes live; and the tools offer no Use it now or Use it next week while it is
 * off (the real engine, {@link FreshAdmin}, {@code GenAdmin}, Time Trials' finish, the Weekly Cup and the
 * world sessions; {@code TimeTrials.testPreview}/{@code begin} are mirrored as the tester, as in
 * {@link CrossFeatureJourneyFreshAdminTest}).
 */
class CrossFeatureJourneyFreshOffPreviewTest {

    /** Tuesday 29 September 2026, 04:00:40: just after the week's rollover. */
    private static final long T0 = GamesBench.at(2026, 9, 29, 4, 0) + 40_000;
    private static final String SLOT = "fresh_boat";
    private static final long MON_28_SEP = 20724;

    private JourneyBench j;
    private FreshBench fresh;
    private Player ava;
    private Player admin;
    /** The preview the admin's test run is on. */
    private Course tested;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC), "trials", TimeTrialsSettings.defaults(),
                "cup", CupSettings.defaults());
        ava = j.player("Ava", "SURVIVAL", "red");
        admin = j.player("Admin", "SURVIVAL", "red");
        fresh = new FreshBench(j.bench, () -> FreshBench.people(j.rail, j.players.keySet()), this::testRun); // all off
        fresh.drive(70);
        assertNull(fresh.liveTag(SLOT), "fixture: Ice Boat is off and nothing was built");
        assertNull(j.trials.openCourse(SLOT), "fixture: nobody can open it");
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

    private List<FreshAdmin.Tool> tools() {
        return FreshAdmin.tools(Slots.of(SLOT), fresh.engine().tools(SLOT));
    }

    private List<FreshAdmin.Kind> kinds() {
        return tools().stream().map(FreshAdmin.Tool::kind).toList();
    }

    /** The admin's screen: a click on {@code kind} (none of these asks "Sure?"). */
    private void click(FreshAdmin.Kind kind) {
        FreshAdmin.Tool tool = tools().stream().filter(x -> x.kind() == kind).findFirst().orElse(null);
        assertNotNull(tool, kind + " is one of the tools: " + kinds());
        FreshAdmin.click(tool, x -> {
            throw new AssertionError(kind + " asked Sure? first");
        }, words -> fresh.admin().handle(ear(), words));
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
    void aPreviewBuiltAndTriedWhileTheCourseIsOffRecordsAndPaysNothingAndOpensNothing() throws Exception {
        // 1. The tools on the course that is off: build one to try, but nothing to use it for
        GenOps.Tools t = fresh.engine().tools(SLOT);
        assertFalse(t.on(), "the tools see Ice Boat off");
        assertTrue(kinds().containsAll(List.of(FreshAdmin.Kind.PREVIEW, FreshAdmin.Kind.PREVIEW_NEXT)),
                "Preview and Preview next are offered: " + kinds());

        // 2. Preview: built in the spare half, the course still off
        click(FreshAdmin.Kind.PREVIEW);
        assertTrue(fresh.driveUntil(() -> fresh.engine().tools(SLOT).preview(), 60), "the preview stands: " + said);
        String heard = String.join("\n", said);
        assertTrue(heard.contains("ready in half A") && heard.contains("Ice Boat stays off"),
                "ready in half A, and still off: " + heard);
        assertEquals(List.of(FreshAdmin.Kind.REGENERATE, FreshAdmin.Kind.PREVIEW, FreshAdmin.Kind.PREVIEW_NEXT,
                FreshAdmin.Kind.TEST), kinds(), "Try it is offered; Use it now and Use it next week aren't, while off");

        // 3. Try it: the admin's run records and pays nothing
        click(FreshAdmin.Kind.TEST);
        assertNotNull(tested, "the tester started a test run on the preview: " + said);
        GenTag previewTag = tested.gen();
        assertEquals('A', previewTag.half(), "the preview stands in half A");
        assertEquals(MON_28_SEP, previewTag.day(), "made as this week's set");
        fresh.drive(31);
        cross(admin, 30_000);
        assertTrue(j.bench.heard(id(admin)).contains("Test run - nothing was recorded."), j.bench.heard(id(admin)));
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores WHERE board = ?", GenBoards.day(previewTag)),
                "nothing on the preview's board");
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores WHERE board = ?", GenBoards.stars(previewTag)),
                "nor on its stars board");
        assertEquals(0, count("SELECT COUNT(*) FROM game_scores"), "nor on any board");
        assertEquals(0, j.rows("game_rewards", "player", id(admin)), "the admin was paid nothing");
        assertEquals(0, j.rows("cup_entries", "player", id(admin)), "and has no Cup entry");
        assertEquals(0, j.told.courses(id(admin)), "and no quest step");

        // 4. Still closed to players, nothing live
        assertFalse(fresh.engine().live(SLOT, previewTag), "the preview is never live");
        assertNull(fresh.liveTag(SLOT), "nothing went live");
        assertNull(j.trials.openCourse(SLOT), "Ava (or anyone) still can't open Ice Boat");
        assertEquals(0, j.rows("game_rewards", "player", id(ava)), "and nobody else was paid anything");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
        assertEquals(0, fresh.severe(), "the engine logged nothing severe: " + fresh.logged());
    }
}
