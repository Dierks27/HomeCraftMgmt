package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenMetaDao;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * What the engine keeps in the database (GEN-SPEC §0.2, §3.3 step 7, §5.5): course rows, its
 * {@code hcm_meta} keys, the boards it prunes and the seed secret. There is no table of its own and
 * nothing about a job is stored: a build can always be run again.
 *
 * <p>The one write that matters is {@link #flip}: the new course row and the bookkeeping that goes
 * with it land in ONE transaction, so the database is before or after the flip, never between.
 */
public interface GenStore {

    /** The daily-board secret the seeds are keyed with (made once, kept in {@code hcm_meta}). */
    long secret() throws SQLException;

    /** One course row, or {@code null}. */
    GamesDao.CourseRow course(String id) throws SQLException;

    /** Every course row of a game (for the hand-built check). */
    List<GamesDao.CourseRow> courses(String game) throws SQLException;

    /**
     * Save {@code row} as a layout change (its {@code rev} goes up by one) and apply {@code meta}
     * ({@code gen.} keys; a {@code null} value removes one), all in one transaction.
     *
     * @return the stored {@code rev}
     */
    int flip(GamesDao.CourseRow row, Map<String, String> meta) throws SQLException;

    /** A {@code gen.} key, or {@code null}. */
    String meta(String key) throws SQLException;

    /** Set a {@code gen.} key ({@code null} removes it). */
    void meta(String key, String value) throws SQLException;

    /** Every {@code gen.} key under {@code prefix}. */
    Map<String, String> metaLike(String prefix) throws SQLException;

    /** Remove the Daily Courses boards past keeping. @return rows removed */
    int pruneBoards(long oldestDay, long oldestWeek) throws SQLException;

    /** Whether anyone has a score on {@code board} of {@code game}. */
    boolean hasScores(String game, String board) throws SQLException;

    /** The store over the plugin's database. */
    static GenStore of(Database database) {
        GamesDao games = new GamesDao(database);
        GenMetaDao meta = new GenMetaDao(database);
        return new GenStore() {
            @Override
            public long secret() throws SQLException {
                return games.secret();
            }

            @Override
            public GamesDao.CourseRow course(String id) throws SQLException {
                return games.course(id);
            }

            @Override
            public List<GamesDao.CourseRow> courses(String game) throws SQLException {
                return games.courses(game);
            }

            @Override
            public int flip(GamesDao.CourseRow row, Map<String, String> updates) throws SQLException {
                return database.transaction(c -> {
                    int rev = games.saveCourse(row, true);
                    for (Map.Entry<String, String> e : updates.entrySet()) {
                        meta.set(e.getKey(), e.getValue());
                    }
                    return rev;
                });
            }

            @Override
            public String meta(String key) throws SQLException {
                return meta.get(key);
            }

            @Override
            public void meta(String key, String value) throws SQLException {
                meta.set(key, value);
            }

            @Override
            public Map<String, String> metaLike(String prefix) throws SQLException {
                return meta.like(prefix);
            }

            @Override
            public int pruneBoards(long oldestDay, long oldestWeek) throws SQLException {
                return games.pruneBoards(oldestDay, oldestWeek);
            }

            @Override
            public boolean hasScores(String game, String board) throws SQLException {
                return !games.top(game, board, true, 1).isEmpty();
            }
        };
    }
}
