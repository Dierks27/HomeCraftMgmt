package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import com.dierks.homecraft.storage.GenMetaDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the engine keeps in the database (GEN-SPEC §0.2, §3.3 step 7, §5.5): course rows, its
 * {@code hcm_meta} keys, the boards it prunes and the seed secret. The edition leaderboards
 * ({@code gfresh:}) are pruned here, through {@code GamesDao}'s own board calls, and the star boards
 * through {@code GamesDao.pruneBoards}. Its one table is the archive of past editions
 * ({@code gen_editions}, {@code GenArchiveDao}); nothing about a build job is stored: a build can
 * always be run again (a keep in flight is, in {@code hcm_meta}, so it can be finished or cleaned).
 *
 * <p>The one write that matters is {@link #flip}: the new course row and the bookkeeping that goes
 * with it land in ONE transaction, so the database is before or after the flip, never between.
 * With the archive (GEN-SPEC-KEEP §1) that transaction also writes the edition's archive row
 * ({@link #flip(GamesDao.CourseRow, Map, GenArchiveDao.Row, long)}), so every edition that was ever
 * live is archived. Keeping a course ({@link #keep}) and clearing its plot ({@link #dropCourse}) are
 * one transaction each too. The archive and keep calls have defaults that refuse, so an older
 * store still compiles; the live store ({@link #of}) has them all.
 */
public interface GenStore {

    /**
     * What a flip with the archive stored.
     *
     * @param rev      the course row's {@code rev}
     * @param archived the edition's archive row (with its course code), or {@code null} for none
     */
    record Flipped(int rev, GenArchiveDao.Row archived) {
    }

    /** The secret the seeds are keyed with (the cabinets' daily-board secret, kept in {@code hcm_meta}). */
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

    /**
     * Remove the star boards past keeping ({@code gstars} before {@code oldestDay}, {@code gweek}
     * before {@code oldestWeek}: {@code GamesDao.pruneBoards}). @return rows removed
     */
    int pruneBoards(long oldestDay, long oldestWeek) throws SQLException;

    /** Every edition leaderboard ({@code gfresh:}) of the course games, by name. */
    default List<String> editionBoards() throws SQLException {
        return List.of();
    }

    /**
     * Remove these edition leaderboards ({@code gfresh:} only; anything else is left alone), in one
     * transaction. @return rows removed
     */
    default int dropEditionBoards(List<String> boards) throws SQLException {
        return 0;
    }

    /** Whether anyone has a score on {@code board} of {@code game}. */
    boolean hasScores(String game, String board) throws SQLException;

    // ---- the archive (GEN-SPEC-KEEP §1-§3, §8) ------------------------------------------------------

    /**
     * {@link #flip(GamesDao.CourseRow, Map)} and, in the SAME transaction, archive the edition
     * going live ({@code archive}; {@code null} for none, like a recall) and end the slot's previous
     * one at {@code now}.
     */
    default Flipped flip(GamesDao.CourseRow row, Map<String, String> meta, GenArchiveDao.Row archive, long now)
            throws SQLException {
        if (archive != null) {
            throw new SQLException("this store keeps no archive");
        }
        return new Flipped(flip(row, meta), null);
    }

    /** One archived edition with its plan, or {@code null}. */
    default GenArchiveDao.Row edition(String slot, String edition) throws SQLException {
        return null;
    }

    /** The archived edition with this course code, with its plan, or {@code null}. */
    default GenArchiveDao.Row editionByCode(String code) throws SQLException {
        return null;
    }

    /** A slot's archived editions (every slot's for {@code null}), newest first, without plans. */
    default List<GenArchiveDao.Row> editions(String slot, int offset, int limit) throws SQLException {
        return List.of();
    }

    /** How many editions a slot has archived (every slot's for {@code null}). */
    default int editionCount(String slot) throws SQLException {
        return 0;
    }

    /** The newest edition of {@code slot} live at some moment in ({@code after}, {@code at}], or {@code null}. */
    default GenArchiveDao.Row editionLive(String slot, long after, long at) throws SQLException {
        return null;
    }

    /** A slot's editions whose seed starts with these hex digits, newest first. */
    default List<GenArchiveDao.Row> editionsBySeed(String slot, String hexPrefix) throws SQLException {
        return List.of();
    }

    /** Every archived edition's board: pruning keeps them as long as the archive row. */
    default Set<String> archivedBoards() throws SQLException {
        return Set.of();
    }

    /** Remove archive rows that ended before {@code endedBefore}, except kept and {@code spared} ones. */
    default int pruneArchive(long endedBefore, Set<String> spared) throws SQLException {
        return 0;
    }

    /** What a board holds (players and counted runs). */
    default GenArchiveDao.BoardStats boardStats(String game, String board) throws SQLException {
        return new GenArchiveDao.BoardStats(0, 0);
    }

    /** A board's best rows. */
    default List<GamesDao.ScoreRow> top(String game, String board, boolean lowerIsBetter, int limit)
            throws SQLException {
        return List.of();
    }

    /** Delete a course row (a Classics slot closing) and apply {@code meta}, in one transaction. */
    default void closeCourse(String id, Map<String, String> meta) throws SQLException {
        throw new SQLException("this store can't close a course");
    }

    /**
     * Register a kept course (GEN-SPEC-KEEP §4 step 5-7) in ONE transaction: the new course row
     * (refused when the id exists), the edition's board copied onto the course's all-time board
     * unless {@code toBoard} is {@code null}, the archive row's {@code kept_as}, and {@code meta}.
     *
     * @return rows copied
     */
    default int keep(GamesDao.CourseRow row, String fromBoard, String toBoard, String slot, String edition,
                     Map<String, String> meta) throws SQLException {
        throw new SQLException("this store can't keep a course");
    }

    /**
     * Delete a kept course with every board of it and forget the edition it was kept from, and
     * apply {@code meta}, in one transaction (clear-plot).
     */
    default void dropCourse(String id, Map<String, String> meta) throws SQLException {
        throw new SQLException("this store can't drop a course");
    }

    /** The store over the plugin's database. */
    static GenStore of(Database database) {
        GamesDao games = new GamesDao(database);
        GenMetaDao meta = new GenMetaDao(database);
        GenArchiveDao archive = new GenArchiveDao(database);
        return new GenStore() {
            @Override
            public Flipped flip(GamesDao.CourseRow row, Map<String, String> updates, GenArchiveDao.Row entry, long now)
                    throws SQLException {
                return database.transaction(c -> {
                    int rev = games.saveCourse(row, true);
                    for (Map.Entry<String, String> e : updates.entrySet()) {
                        meta.set(e.getKey(), e.getValue());
                    }
                    GenArchiveDao.Row stored = entry == null ? null : archive.archive(entry, now);
                    return new Flipped(rev, stored);
                });
            }

            @Override
            public GenArchiveDao.Row edition(String slot, String edition) throws SQLException {
                return archive.get(slot, edition);
            }

            @Override
            public GenArchiveDao.Row editionByCode(String code) throws SQLException {
                return archive.byCode(code);
            }

            @Override
            public List<GenArchiveDao.Row> editions(String slot, int offset, int limit) throws SQLException {
                return archive.list(slot, offset, limit);
            }

            @Override
            public int editionCount(String slot) throws SQLException {
                return archive.count(slot);
            }

            @Override
            public GenArchiveDao.Row editionLive(String slot, long after, long at) throws SQLException {
                return archive.liveBetween(slot, after, at);
            }

            @Override
            public List<GenArchiveDao.Row> editionsBySeed(String slot, String hexPrefix) throws SQLException {
                return archive.bySeed(slot, hexPrefix);
            }

            @Override
            public Set<String> archivedBoards() throws SQLException {
                return archive.boards();
            }

            @Override
            public int pruneArchive(long endedBefore, Set<String> spared) throws SQLException {
                return archive.prune(endedBefore, spared);
            }

            @Override
            public GenArchiveDao.BoardStats boardStats(String game, String board) throws SQLException {
                return archive.stats(game, board);
            }

            @Override
            public List<GamesDao.ScoreRow> top(String game, String board, boolean lowerIsBetter, int limit)
                    throws SQLException {
                return games.top(game, board, lowerIsBetter, limit);
            }

            @Override
            public void closeCourse(String id, Map<String, String> updates) throws SQLException {
                database.transaction(c -> {
                    games.deleteCourse(id);
                    for (Map.Entry<String, String> e : updates.entrySet()) {
                        meta.set(e.getKey(), e.getValue());
                    }
                    return null;
                });
            }

            @Override
            public int keep(GamesDao.CourseRow row, String fromBoard, String toBoard, String slot, String edition,
                            Map<String, String> updates) throws SQLException {
                return database.transaction(c -> {
                    if (games.course(row.id()) != null) {
                        throw new SQLException("there's already a course called " + row.id());
                    }
                    games.saveCourse(row, false);
                    int copied = toBoard == null ? 0 : archive.copyBoard(row.game(), fromBoard, toBoard);
                    archive.setKept(slot, edition, row.id());
                    for (Map.Entry<String, String> e : updates.entrySet()) {
                        meta.set(e.getKey(), e.getValue());
                    }
                    return copied;
                });
            }

            @Override
            public void dropCourse(String id, Map<String, String> updates) throws SQLException {
                database.transaction(c -> {
                    GamesDao.CourseRow old = games.course(id);
                    if (old != null && !Regions.hasGen(old.data())) {
                        games.deleteCourse(id);
                        String game = old.game();
                        games.resetScores(game, Scores.course(id), null);
                        games.resetScores(game, Scores.golf(id), null);
                        String week = "week:" + id + ":";
                        for (String board : games.boards(game)) {
                            if (board != null && board.startsWith(week)) {
                                games.resetScores(game, board, null);
                            }
                        }
                    }
                    archive.clearKept(id);
                    for (Map.Entry<String, String> e : updates.entrySet()) {
                        meta.set(e.getKey(), e.getValue());
                    }
                    return null;
                });
            }

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
            public List<String> editionBoards() throws SQLException {
                List<String> out = new ArrayList<>();
                for (String game : List.of(Slots.GAME_TRIALS, Slots.GAME_GOLF)) {
                    for (String board : games.boards(game)) {
                        if (board.startsWith(GenBoards.DAY_PREFIX)) {
                            out.add(board);
                        }
                    }
                }
                return out;
            }

            @Override
            public int dropEditionBoards(List<String> boards) throws SQLException {
                Predicate<String> ours = b -> b != null && b.startsWith(GenBoards.DAY_PREFIX);
                return database.transaction(c -> {
                    int removed = 0;
                    for (String board : boards) {
                        if (ours.test(board)) {
                            removed += games.resetScores(Slots.GAME_TRIALS, board, null);
                            removed += games.resetScores(Slots.GAME_GOLF, board, null);
                        }
                    }
                    return removed;
                });
            }

            @Override
            public boolean hasScores(String game, String board) throws SQLException {
                return !games.top(game, board, true, 1).isEmpty();
            }
        };
    }
}
