package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The courses in {@code game_courses}, against a real SQLite.
 *
 * <p>Pinned here: a course saved comes back as it was, the table's columns mirroring it; a layout
 * edit bumps the layout version and clears the course's all-time and this week's boards (other
 * weeks and other courses are left alone), while any other edit keeps both; "has times" is what
 * the admin command asks before a layout edit; deleting a course takes every board of it; another
 * world game's course of the same id is never overwritten or read as ours; and a row that can't be
 * read is skipped and reported, never thrown.
 */
class CourseStoreTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long WEEK = 20_720;

    private Connection conn;
    private GamesDao dao;
    private CourseStore store;
    private final List<String> warnings = new ArrayList<>();
    private final UUID alice = UUID.randomUUID();

    private static final Course RIVER = new Course("river_run", TrialKind.BOAT, "River Run", Tier.MEDIUM, "games",
            new Course.Spot(0, 63, 0, 90, 0), List.of(new Course.Mark(20, 63, 0, 3)), new Course.Mark(40, 63, 0, 4),
            null, 20, true, false, 1);

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        dao = new GamesDao(Database.open(conn, Logger.getAnonymousLogger()));
        store = new CourseStore(() -> dao, warnings::add);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private void time(String board, long ms) throws Exception {
        dao.submit(alice, CourseStore.GAME, board, ms, true, NOW);
    }

    @Test
    void aSavedCourseComesBackAsItWasWithItsColumnsMirroringIt() throws Exception {
        Course saved = store.save(RIVER, false, WEEK, NOW);
        assertEquals(RIVER, saved, "a new course keeps layout 1");
        assertEquals(RIVER, store.get("river_run"), "read back exactly");
        assertEquals(List.of(RIVER), store.load(), "and listed");
        GamesDao.CourseRow row = dao.course("river_run");
        assertEquals(List.of("trials", "boat", "River Run", "games", "true"),
                List.of(row.game(), row.kind(), row.name(), row.world(), String.valueOf(row.enabled())),
                "the columns say what the YAML says");
    }

    @Test
    void aLayoutEditBumpsTheVersionAndClearsThisWeeksAndAllTimeBoards() throws Exception {
        store.save(RIVER, false, WEEK, NOW);
        time(Scores.course("river_run"), 60_000);
        time(Scores.week("river_run", WEEK), 60_000);
        time(Scores.week("river_run", WEEK - 7), 61_000);
        time(Scores.course("cliffs"), 30_000);
        assertTrue(store.hasTimes("river_run", WEEK), "there are times to lose");
        Course moved = store.save(RIVER.withFinish(new Course.Mark(50, 63, 0, 4)), true, WEEK, NOW + 1);
        assertEquals(2, moved.rev(), "the layout version goes up");
        assertEquals(2, store.get("river_run").rev(), "and is stored");
        assertNull(dao.best(alice, CourseStore.GAME, Scores.course("river_run")), "the all-time board is cleared");
        assertNull(dao.best(alice, CourseStore.GAME, Scores.week("river_run", WEEK)), "this week's board is cleared");
        assertEquals(61_000L, dao.best(alice, CourseStore.GAME, Scores.week("river_run", WEEK - 7)),
                "last week's board is history and stays");
        assertEquals(30_000L, dao.best(alice, CourseStore.GAME, Scores.course("cliffs")), "another course is untouched");
        assertFalse(store.hasTimes("river_run", WEEK), "nothing left to lose");
    }

    @Test
    void anyOtherEditKeepsTheVersionAndTheTimes() throws Exception {
        store.save(RIVER, false, WEEK, NOW);
        time(Scores.course("river_run"), 60_000);
        Course renamed = store.save(RIVER.withName("The River").withTier(Tier.HARD), false, WEEK, NOW + 1);
        assertEquals(1, renamed.rev(), "a name or tier isn't a new layout");
        assertEquals("The River", store.get("river_run").name(), "the edit is stored");
        assertEquals(60_000L, dao.best(alice, CourseStore.GAME, Scores.course("river_run")), "and the times stay");
    }

    @Test
    void deletingACourseTakesEveryBoardOfIt() throws Exception {
        store.save(RIVER, false, WEEK, NOW);
        time(Scores.course("river_run"), 60_000);
        time(Scores.week("river_run", WEEK), 60_000);
        time(Scores.week("river_run", WEEK - 7), 61_000);
        time(Scores.course("river_run_2"), 30_000);
        assertTrue(store.delete("river_run"), "deleted");
        assertNull(store.get("river_run"), "gone");
        assertNull(dao.best(alice, CourseStore.GAME, Scores.week("river_run", WEEK - 7)), "every week's board goes too");
        assertNull(dao.best(alice, CourseStore.GAME, Scores.course("river_run")), "and the all-time board");
        assertEquals(30_000L, dao.best(alice, CourseStore.GAME, Scores.course("river_run_2")),
                "a course whose id merely starts the same is untouched");
        assertFalse(store.delete("river_run"), "nothing left to delete");
    }

    @Test
    void anotherGamesCourseOfTheSameIdIsNeverOverwrittenOrReadAsOurs() throws Exception {
        dao.saveCourse(new GamesDao.CourseRow("river_run", "golf", "golf", "River Links", "games", true, "holes: []",
                1, NOW, NOW));
        assertTrue(store.taken("river_run"), "the id is taken, by golf");
        assertNull(store.get("river_run"), "golf's course isn't a time trial");
        assertNull(store.save(RIVER, false, WEEK, NOW), "and saving over it is refused");
        assertEquals("golf", dao.course("river_run").game(), "golf's course is still there");
        assertFalse(store.delete("river_run"), "nor can it be deleted from here");
    }

    @Test
    void aRowThatCanNotBeReadIsSkippedAndReported() throws Exception {
        store.save(RIVER, false, WEEK, NOW);
        dao.saveCourse(new GamesDao.CourseRow("broken", "trials", "parkour", "Broken", "games", true, "kind: [oops",
                1, NOW, NOW));
        assertEquals(List.of(RIVER), store.load(), "the readable course is listed, the broken one isn't");
        assertEquals(1, warnings.size(), "the broken one is reported once");
        assertTrue(warnings.get(0).contains("broken"), "by name");
    }
}
