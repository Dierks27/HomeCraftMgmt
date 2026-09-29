package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.engine.FreshBench;
import com.dierks.homecraft.games.world.SessionBench;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey 3: a solo Fresh time trial with a warm-up, a counted Weekly Cup run, then an admin's
 * {@code regenerate} while the runner is still on the old layout (the real Fresh Courses engine over the
 * framework's own database and clock, the real Time Trials finish, the Weekly Cup's minute and the world
 * sessions; {@code TimeTrials.begin} and the warm-up's switch to the countdown are mirrored).
 *
 * <p>Pinned: a warm-up lap counts for nothing; a counted run goes on its set's board, pays the set's first
 * finish once (keyed by the set, so a reroll pays no second one) and sets a Cup time; the reroll builds
 * the new layout in the other half and flips it, but the old half stands until the runner on it is done,
 * so her run there still counts on the old board; the Cup is called off because the course changed and
 * refunds everyone, sets no more times and takes no new entry; the new layout starts on a fresh board.
 */
class CrossFeatureJourneyFreshTrialTest {

    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final String SLOT = "fresh_parkour_easy";
    private static final Box A = FreshBench.half(SLOT, 'A');
    private static final Box B = FreshBench.half(SLOT, 'B');

    private JourneyBench j;
    private FreshBench fresh;
    private Player ava;
    private Player ben;
    private Player admin;
    private final List<Throwable> later = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        j = new JourneyBench(T0, List.of(TimeTrials.SPEC, WeeklyCup.SPEC), "trials", TimeTrialsSettings.defaults(),
                "cup", CupSettings.defaults());
        ava = j.player("Ava", "SURVIVAL", "red");
        ben = j.player("Ben", "SURVIVAL", "blue");
        admin = j.bench.player("Admin");
        fresh = new FreshBench(j.bench, () -> FreshBench.people(j.rail, j.players.keySet()),
                (p, c, again) -> p.sendMessage(com.dierks.homecraft.util.Text.of("&cno tests here")), SLOT);
        assertTrue(fresh.driveUntil(() -> fresh.liveTag(SLOT) != null && j.trials.openCourse(SLOT) != null, 120),
                "the week's set goes up: " + fresh.logged());
        assertEquals('A', fresh.liveTag(SLOT).half(), "in half A");
    }

    @AfterEach
    void tearDown() throws Exception {
        j.close();
    }

    private static UUID id(Player p) {
        return p.getUniqueId();
    }

    private Course course() {
        return j.trials.course(SLOT);
    }

    /** Where a run on {@code c} starts, on the rail. */
    private static SessionBench.Spot start(Course c) {
        return JourneyBench.spot("games", c.start());
    }

    /** {@code TimeTrials.begin}, mirrored: the trials session at the course's start, in, saved, cleared. */
    private void begin(Player p, Course c) {
        assertNull(j.rail.enterNow(id(p), j.trials, c.id(), start(c), q -> {
        }), p.getName() + " goes to the course's start");
        j.rail.putKit(id(p), 0, "kit:trials:checkpoint");
        j.rail.putKit(id(p), 8, "kit:trials:leave");
    }

    /** A counted run's clock on {@code c}, from its start now (the 3-2-1 is over). */
    private TrialRun counted(Player p, Course c) {
        TrialRun run = new TrialRun(id(p), c, false, 0);
        run.progress = new Progress(c, c.start().point(), j.race.nanos);
        run.phase = TrialRun.Phase.RUNNING;
        j.trials.replaceRun(run);
        return run;
    }

    /**
     * The line crossed ({@code TimeTrials.finish} through the bench's {@code cross}). A solo finish ends by
     * scheduling its result screen on the server ({@code GamesService.later}, which needs a plugin): that
     * one throw, after everything is recorded, is expected on a bench; anything else fails the test.
     */
    private void cross(Player p, long ms) {
        try {
            j.race.cross(id(p), ms);
        } catch (NullPointerException e) {
            boolean scheduling = Arrays.stream(e.getStackTrace()).anyMatch(f -> f.getClassName().endsWith("GamesService")
                    && f.getMethodName().equals("later"));
            assertTrue(scheduling, "only the finish's scheduled result screen may need a server: " + e);
            later.add(e);
        }
    }

    /** {@code TimeTrials.finish}'s scheduled leave (copied, never the bench's tasks): home with their things. */
    private void home(Player p) {
        j.rail.leave(id(p), EndReason.FINISH);
        j.rail.arriveAll();
        assertNull(j.homeProblem(id(p)), p.getName() + " is home as they were: " + j.homeProblem(id(p)));
    }

    private Long best(Player p, GenTag tag) throws Exception {
        return j.bench.dao().best(id(p), TimeTrials.SPEC.id(), GenBoards.day(tag));
    }

    private long boardRows(GenTag tag) throws Exception {
        try (PreparedStatement ps = j.bench.connection().prepareStatement(
                "SELECT COUNT(*) FROM game_scores WHERE board = ?")) {
            ps.setString(1, GenBoards.day(tag));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private List<String> refs(Player p, String kind) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = j.bench.connection().prepareStatement(
                "SELECT ref FROM game_rewards WHERE player = ? AND kind = ?")) {
            ps.setString(1, id(p).toString());
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void aFreshRunWithAWarmUpCountsOnceAndARegenerateLetsTheRunnerFinishOnTheOldLayout() throws Exception {
        Course a = course();
        GenTag tagA = a.gen();
        assertNotNull(tagA, "a Fresh course");
        assertTrue(j.cup.desk().runsCup(a), "a Fresh parkour slot runs a Cup by default");

        // 1. Ava and Ben enter this week's Cup on the slot
        assertNull(j.cup.desk().enter(id(ava), a), "Ava enters the Cup");
        assertNull(j.cup.desk().enter(id(ben), a), "Ben enters the Cup");
        assertEquals(15, j.bench.balance(id(ava)), "5 each");
        assertEquals(15, j.bench.balance(id(ben)), "5 each");
        long week = j.cup.desk().week();

        // 2. Ava's run with a warm-up: a lap through the finish
        begin(ava, a);
        TrialRun warm = new TrialRun(id(ava), a, false, TimeTrials.COUNTDOWN_TICKS + 1);
        assertTrue(Warmup.begin(warm, j.race.tick, 180, a.start().point(), j.race.nanos), "Ava chose a warm-up");
        j.trials.replaceRun(warm);
        fresh.drive(40);
        cross(ava, 38_000);
        assertNull(best(ava, tagA), "a warm-up lap never reaches the set's board");
        assertNull(j.cupTime(id(ava), SLOT), "nor the Cup");
        assertTrue(refs(ava, "DAILY_CLEAR").isEmpty(), "nor the set's first finish");
        assertEquals(0, j.told.courses(id(ava)), "nor the quests");
        assertTrue(j.bench.heard(id(ava)).contains("Warm-up lap"), j.bench.heard(id(ava)));

        // 3. The 3-2-1 after the warm-up (mirrored: a new counted run on the same snapshot); 42.0 s
        counted(ava, warm.course);
        fresh.drive(43);
        cross(ava, 42_000);
        assertEquals(42_000L, best(ava, tagA), "run 1 is on the set's board");
        assertEquals(42_000L, j.cupTime(id(ava), SLOT), "and is Ava's Cup time");
        assertEquals(1, j.told.courses(id(ava)), "FINISH_COURSE once");
        assertEquals(List.of(SkillRewards.freshClearRef(SLOT, tagA.edition())), refs(ava, "DAILY_CLEAR"),
                "the set's first finish, once, keyed by the set");
        home(ava);

        // 4. Run 2 on the same layout: Ava stands in half A
        begin(ava, a);
        counted(ava, a);
        assertTrue(A.contains((int) Math.floor(j.rail.place(id(ava)).x()), (int) Math.floor(j.rail.place(id(ava)).y()),
                (int) Math.floor(j.rail.place(id(ava)).z())), "Ava stands in half A: " + j.rail.place(id(ava)));
        assertTrue(FreshBench.people(j.rail, j.players.keySet()).stream().anyMatch(p -> p.id().equals(id(ava))
                && "trials".equals(p.sessionGame()) && SLOT.equals(p.sessionRef())), "the engine sees her on the slot");
        long blocksA = fresh.blocks(A);

        // 5. The admin regenerates the slot
        List<String> said = new ArrayList<>();
        Player listening = new AdminEar(admin, said).player;
        fresh.admin().handle(listening, new String[]{"regenerate", SLOT});
        assertTrue(String.join("\n", said).contains("confirm"), "regenerate asks for confirm first: " + said);
        said.clear();
        fresh.admin().handle(listening, new String[]{"regenerate", SLOT, "confirm"});
        assertTrue(String.join("\n", said).contains("anyone on the old one finishes there"), "the reply: " + said);
        assertTrue(fresh.driveUntil(() -> fresh.liveTag(SLOT).reroll() == 1, 300), "the new layout is up: "
                + fresh.logged());
        GenTag tagB = fresh.liveTag(SLOT);
        assertEquals('B', tagB.half(), "built in half B");
        assertEquals(1, tagB.reroll(), "reroll 1");
        assertEquals(tagA.edition(), tagB.edition(), "the same set");
        assertNotEquals(tagA.editionKey(), tagB.editionKey(), "but its own layout key");
        assertNotEquals(GenBoards.day(tagA), GenBoards.day(tagB), "and its own board");
        fresh.drive(180);
        assertTrue(fresh.engine().standing(tagA), "the old layout stands while Ava is on it");
        assertEquals(blocksA, fresh.blocks(A), "half A is untouched while she is listed");

        // 6. The Cup's minute: the course changed, so the week's Cup is called off and refunded
        int avaBefore = j.bench.balance(id(ava));
        int benBefore = j.bench.balance(id(ben));
        j.cup.desk().tick();
        assertEquals(CupPlan.Outcome.VOIDED, j.cup.desk().dao().settledAs(new CupKey(SLOT, week)), "called off");
        assertEquals(avaBefore + 5, j.bench.balance(id(ava)), "Ava's 5 back");
        assertEquals(benBefore + 5, j.bench.balance(id(ben)), "Ben's 5 back");
        for (Player p : List.of(ava, ben)) {
            assertTrue(j.bench.dao().prefsLike(id(p), ChanceRounds.NOTICE).values().stream()
                    .anyMatch(l -> l.contains("called off because the course changed")), p.getName() + " is told why");
        }

        // 7. Run 2 on the old layout, after the flip: it counts, on the old board
        cross(ava, 41_000);
        assertEquals(41_000L, best(ava, tagA), "run 2 counted on the old layout's board");
        assertEquals(0, boardRows(tagB), "nothing on the new layout's board");
        assertEquals(1, refs(ava, "DAILY_CLEAR").size(), "still one first finish of the set (none for the reroll)");
        assertEquals(2, j.told.courses(id(ava)), "FINISH_COURSE twice now");
        assertEquals(42_000L, j.cupTime(id(ava), SLOT), "a called-off Cup takes no time");
        home(ava);
        assertFalse(FreshBench.people(j.rail, j.players.keySet()).stream().anyMatch(p -> p.id().equals(id(ava))),
                "home: the engine no longer sees her");
        assertTrue(fresh.driveUntil(() -> !fresh.engine().standing(tagA), 300), "then the old half is cleared: "
                + fresh.logged());

        // 8. The new layout: a fresh board
        Course b = course();
        assertEquals(tagB.editionKey(), b.gen().editionKey(), "Time Trials reads the new layout");
        begin(ava, b);
        counted(ava, b);
        fresh.drive(45);
        cross(ava, 44_000);
        assertEquals(44_000L, best(ava, tagB), "run 3 is on the new layout's board");
        assertEquals(1, boardRows(tagB), "its only row: a fresh board");
        assertEquals(1, refs(ava, "DAILY_CLEAR").size(), "still one first finish of the set");
        assertEquals(3, j.told.courses(id(ava)), "FINISH_COURSE three times");
        assertEquals(42_000L, j.cupTime(id(ava), SLOT), "no Cup time from run 3");
        home(ava);

        // 9. Ben tries the Cup again
        assertNotNull(j.cup.desk().enter(id(ben), b), "refused: this week's Cup on it is settled");
        assertTrue(refs(ben, "DAILY_CLEAR").isEmpty(), "Ben never finished");
        assertEquals(20, j.bench.balance(id(ben)), "and paid nothing more");
        assertEquals(3, later.size(), "each counted solo finish got as far as scheduling its result screen");
        assertEquals(0, j.bench.severe(), "nothing threw: " + j.bench.severeLines());
        assertEquals(0, fresh.severe(), "the engine logged nothing severe: " + fresh.logged());
    }

    /** An admin whose replies are kept (the GamesBench player with its chat lines collected here too). */
    private static final class AdminEar {
        final Player player;

        AdminEar(Player base, List<String> said) {
            player = (Player) java.lang.reflect.Proxy.newProxyInstance(AdminEar.class.getClassLoader(),
                    new Class<?>[]{Player.class}, (proxy, m, a) -> {
                        if (m.getName().equals("sendMessage") && a != null && a.length == 1) {
                            said.add(a[0] instanceof net.kyori.adventure.text.Component c
                                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                    .serialize(c) : String.valueOf(a[0]));
                        }
                        try {
                            return m.invoke(base, a);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
