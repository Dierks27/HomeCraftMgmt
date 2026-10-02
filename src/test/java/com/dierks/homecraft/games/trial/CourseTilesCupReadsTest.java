package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.cup.live.CupSettings;
import com.dierks.homecraft.games.cup.live.WeeklyCup;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx2-C #5, the screen's side: the Games screen builds a tile for every open course on every click,
 * and each tile shows its Weekly Cup. One screen build of Time Trials' tiles ({@link TimeTrials#tiles},
 * and a page of the course list, both through {@link TimeTrials#faces}) reads the Cup through one
 * shared read-through, so the viewer's hidden-Cup choice and token balance are read once for the
 * whole screen, not once a course. {@code CupTileReadsTest} pins the read-through itself; this pins
 * that a screen shares one.
 *
 * <p>The real framework, Time Trials and Weekly Cup on a real SQLite, with every statement the
 * framework prepares written down. A tile's item needs a server, so the screen's faces are read (the
 * tiles are exactly those, made into items).
 */
class CourseTilesCupReadsTest {

    /** Tuesday 29 September 2026, noon (the kit's zone): a Cup week is open. */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final int COURSES = 4;

    /** Every statement the DAOs prepared, while {@link #counting}. */
    private final List<String> asked = new ArrayList<>();
    private boolean counting;
    private GamesBench bench;
    private TimeTrials trials;
    private WeeklyCup cup;
    private Player ava;

    /** The connection the framework uses: the real one, with each prepared statement written down. */
    private Connection counted(Connection c) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, m, args) -> {
                    if (counting && (m.getName().equals("prepareStatement") || m.getName().equals("createStatement"))) {
                        asked.add(args == null || args.length == 0 ? "(statement)" : String.valueOf(args[0]));
                    }
                    try {
                        return m.invoke(c, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @BeforeEach
    void setUp() throws Exception {
        bench = new GamesBench(T0, this::counted, List.of(TimeTrials.SPEC, WeeklyCup.SPEC), "trials",
                TimeTrialsSettings.defaults(), "cup", new CupSettings(true, 5, 10)); // 0.36's 5 to enter
        GamesService games = bench.games();
        trials = (TimeTrials) games.game("trials");
        cup = (WeeklyCup) games.game("cup");
        assertNotNull(trials, "Time Trials is built from its spec");
        assertNotNull(cup, "and the Weekly Cup");
        assertTrue(games.enabled(cup), "the Weekly Cup is open");
        for (int i = 0; i < COURSES; i++) {
            Course c = new Course("course_" + i, TrialKind.PARKOUR, "Course " + i, Tier.EASY, "games",
                    new Course.Spot(0, 64, 0, 0, 0), List.of(new Course.Mark(10, 64, 0, 1.5)),
                    new Course.Mark(20, 64, 0, 1.5), null, null, true, false, 1);
            bench.dao().saveCourse(new GamesDao.CourseRow(c.id(), "trials", c.kind().id(), c.name(), c.world(),
                    c.enabled(), CourseCodec.encode(c), c.rev(), 0, 0), false);
            cup.desk().dao().choose(c.id(), true); // a hand-built course runs a Cup once an admin says so
        }
        trials.forget();
        ava = bench.player("Ava");
        bench.give(ava.getUniqueId(), 20);
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    /** The statements asked of {@code table}. */
    private List<String> reads(String table) {
        return asked.stream().filter(sql -> sql.contains(table)).toList();
    }

    @Test
    void aScreenOfCupCoursesReadsTheViewersHiddenCupChoiceAndBalanceOnce() {
        counting = true;
        List<TimeTrials.Face> faces = trials.faces(ava);
        counting = false;
        assertEquals(COURSES, faces.size(), "a tile for every open course");
        for (TimeTrials.Face f : faces) {
            assertTrue(f.name().contains("Cup: 5 tokens"), "every tile shows its Cup, in its NAME for Bedrock: "
                    + f.name());
        }
        assertEquals(1, reads("game_prefs").size(), "whether Ava hid the Cup is the same on every tile of one"
                + " screen: read once, not once a course: " + reads("game_prefs"));
        assertEquals(1, reads("arcade_tokens").size(), "and so is her balance: read once, not once a course: "
                + reads("arcade_tokens"));
    }
}
