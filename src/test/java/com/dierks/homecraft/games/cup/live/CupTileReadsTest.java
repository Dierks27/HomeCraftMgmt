package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.CupDao;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx2-C #5: the Games screen builds a tile for every open course on every click, and each tile asks
 * about the Weekly Cup. One viewer's tiles in one screen build go through {@link CupDesk.Reads}: a
 * course that runs no Cup costs one read (whether it runs one), the viewer's balance is read once for
 * the whole screen, and what a tile shows is exactly what the full read would have shown. The
 * queries are counted on a real SQLite.
 */
class CupTileReadsTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Edition WEEKLY = new Edition(CHICAGO, LocalTime.of(4, 0), DayOfWeek.MONDAY, 7,
            DayOfWeek.MONDAY);
    private static final long TUESDAY_NOON = LocalDateTime.of(2026, 9, 29, 12, 0).atZone(CHICAGO).toInstant()
            .toEpochMilli();

    private Connection raw;
    /** Every statement the DAOs prepared, while {@link #counting}. */
    private final List<String> asked = new ArrayList<>();
    private boolean counting;
    private CupDao dao;
    private TokenDao tokens;
    private CupDesk desk;
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final Map<String, Course> courses = new HashMap<>();

    private final CupDesk.Host host = new CupDesk.Host() {
        @Override
        public long now() {
            return TUESDAY_NOON;
        }

        @Override
        public Edition edition() {
            return WEEKLY;
        }

        @Override
        public CupSettings settings() {
            return CupSettings.defaults();
        }

        @Override
        public Course course(String id) {
            return courses.get(id);
        }

        @Override
        public Boolean slotWanted(String id) {
            return true;
        }

        @Override
        public boolean tellNow(UUID player, String line) {
            return false;
        }

        @Override
        public Logger logger() {
            return Logger.getAnonymousLogger();
        }
    };

    /** The connection the DAOs use: the real one, with each prepared statement written down. */
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
    void open() throws Exception {
        raw = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(counted(raw), Logger.getAnonymousLogger());
        dao = new CupDao(db);
        tokens = new TokenDao(db);
        desk = new CupDesk(dao, host);
        tokens.change(alice, 20, "ADMIN", "seed", 1);
        tokens.change(bob, 20, "ADMIN", "seed", 1);
    }

    @AfterEach
    void close() throws Exception {
        raw.close();
    }

    private Course course(String id, String name) {
        Course c = new Course(id, TrialKind.PARKOUR, name, Tier.EASY, "games", new Course.Spot(0, 64, 0, 0, 0),
                List.of(new Course.Mark(10, 64, 0, 1.5)), new Course.Mark(20, 64, 0, 1.5), null, null, true, false, 1);
        courses.put(id, c);
        return c;
    }

    private long balanceReads() {
        return asked.stream().filter(sql -> sql.contains("arcade_tokens")).count();
    }

    @Test
    void aCourseThatRunsNoCupCostsOneReadOnItsTile() throws Exception {
        Course lava = course("lava_leap", "Lava Leap"); // hand-built: no Cup until an admin says so
        CupDesk.Reads reads = desk.reads(alice);
        counting = true;
        assertNull(reads.shown(lava), "a course with no Cup shows none on its tile");
        assertEquals(1, asked.size(), "and asking costs one read, whether it runs a Cup, not the entries,"
                + " the settlement and the balance thrown away after: " + asked);
    }

    @Test
    void aScreenOfCupTilesReadsTheViewersBalanceOnce() throws Exception {
        List<Course> cups = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Course c = course("course_" + i, "Course " + i);
            dao.choose(c.id(), true);
            cups.add(c);
        }
        CupDesk.Reads reads = desk.reads(alice);
        counting = true;
        for (Course c : cups) {
            assertNotNull(reads.shown(c), "an open Cup shows on its tile");
        }
        assertEquals(1, balanceReads(), "the viewer's tokens are the same on every tile of one screen: read once,"
                + " not once a course");
        assertTrue(asked.size() <= 4 * 4 + 1, "at most whether it runs, its entries, its settlement and the"
                + " viewer's other Cup on it, a course, and the balance once: " + asked.size());
    }

    @Test
    void whatATileShowsIsWhatTheFullReadShows() throws Exception {
        Course on = course("sky_steps", "Sky Steps");
        dao.choose(on.id(), true);
        Course off = course("lava_leap", "Lava Leap");
        Course called = course("cliffs", "Cliffs");
        dao.choose(called.id(), false);
        assertEquals(null, dao.enter(desk.key(on.id()), bob, 5, on.name(), TUESDAY_NOON, desk.week(),
                CupDesk.layout(on).encode(), com.dierks.homecraft.games.cup.CupRules.liveWeeks(WEEKLY, TUESDAY_NOON)),
                "Bob enters");
        tokens.change(alice, -18, "ADMIN", "spent", 2); // 2 left: not enough to enter
        CupDesk.Reads reads = desk.reads(alice);
        for (Course c : List.of(on, off, called)) {
            CupDesk.View full = desk.view(c, alice);
            assertEquals(full.shown() ? full : null, reads.shown(c), "the tile of " + c.id() + " is the full read's,"
                    + " pool, entry and refusal alike");
        }
        assertEquals(com.dierks.homecraft.games.cup.CupRefusal.NOT_ENOUGH_TOKENS, reads.shown(on).refusal(),
                "the one balance read still says she can't pay");
        CupDesk.Reads bobs = desk.reads(bob);
        assertTrue(bobs.shown(on).in(), "Bob's tile says he is in");
    }
}
