package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.storage.GamesDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The time trials' courses in {@code game_courses} (spec §11, R2.15): read, saved, deleted.
 *
 * <p>The row's columns (kind, name, world, enabled, rev) mirror the YAML in {@code data}, so a
 * glance at the table tells an admin what is there; the YAML is the definition, except for
 * {@code rev}, which the database bumps itself on every geometry edit and is taken from the column.
 *
 * <p><b>A layout change clears the course's times.</b> A time set on the old layout isn't a time
 * on the new one, so a geometry edit (start, a checkpoint added or removed, the finish, the fall
 * height) bumps {@code rev} and clears the course's all-time board and this week's board. The
 * admin command asks for a {@code confirm} first when there are times to lose
 * ({@link #hasTimes}). A run already under way keeps its snapshot and, finishing on a changed
 * layout, records nothing.
 *
 * <p>Course ids are shared by every world game's courses (the table's key), so a save never
 * overwrites another game's course of the same id.
 */
final class CourseStore {

    /** The game the courses belong to. */
    static final String GAME = "trials";

    private final Supplier<GamesDao> dao;
    private final Consumer<String> warn;

    /**
     * @param dao  the games database (read on every call)
     * @param warn where an unreadable course is reported
     */
    CourseStore(Supplier<GamesDao> dao, Consumer<String> warn) {
        this.dao = dao;
        this.warn = warn;
    }

    /** Every trials course that can be read, by id; each unreadable one is reported. */
    List<Course> load() throws SQLException {
        List<Course> out = new ArrayList<>();
        for (GamesDao.CourseRow row : dao.get().courses(GAME)) {
            Course c = fromRow(row);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** One trials course, or {@code null} when there is none (or it is another game's). */
    Course get(String id) throws SQLException {
        GamesDao.CourseRow row = dao.get().course(id);
        return row == null || !GAME.equals(row.game()) ? null : fromRow(row);
    }

    /** Whether any world game already has a course called {@code id}. */
    boolean taken(String id) throws SQLException {
        return dao.get().course(id) != null;
    }

    /**
     * Save {@code course}. A geometry edit stores the next {@code rev} and clears the course's
     * all-time and this week's boards.
     *
     * @return the stored course (with its {@code rev}), or {@code null} when another game owns the id
     */
    Course save(Course course, boolean geometryEdit, long weekKey, long now) throws SQLException {
        GamesDao d = dao.get();
        GamesDao.CourseRow old = d.course(course.id());
        if (old != null && !GAME.equals(old.game())) {
            return null;
        }
        int rev = old == null ? Math.max(1, course.rev()) : old.rev() + (geometryEdit ? 1 : 0);
        Course stored = course.withRev(rev);
        int saved = d.saveCourse(row(stored, old == null ? now : old.createdAt(), now), geometryEdit);
        if (saved != rev) {
            stored = stored.withRev(saved); // the database's count wins
            d.saveCourse(row(stored, old == null ? now : old.createdAt(), now), false);
        }
        if (geometryEdit) {
            d.resetScores(GAME, Scores.course(course.id()), null);
            d.resetScores(GAME, Scores.week(course.id(), weekKey), null);
        }
        return stored;
    }

    /** Delete a course and every board of it (all-time and every week's). */
    boolean delete(String id) throws SQLException {
        GamesDao d = dao.get();
        GamesDao.CourseRow old = d.course(id);
        if (old == null || !GAME.equals(old.game())) {
            return false;
        }
        boolean gone = d.deleteCourse(id);
        d.resetScores(GAME, Scores.course(id), null);
        String week = "week:" + id + ":";
        for (String board : d.boards(GAME)) {
            if (board != null && board.startsWith(week)) {
                d.resetScores(GAME, board, null);
            }
        }
        return gone;
    }

    /** Whether the course has times a layout change would clear (all-time or this week). */
    boolean hasTimes(String id, long weekKey) throws SQLException {
        GamesDao d = dao.get();
        return !d.top(GAME, Scores.course(id), true, 1).isEmpty()
                || !d.top(GAME, Scores.week(id, weekKey), true, 1).isEmpty();
    }

    /** The row for {@code c}: its columns mirror the YAML. */
    static GamesDao.CourseRow row(Course c, long createdAt, long now) {
        return new GamesDao.CourseRow(c.id(), GAME, c.kind().id(), c.name(), c.world(), c.enabled(),
                CourseCodec.encode(c), c.rev(), createdAt, now);
    }

    /** A row read back: the YAML, with the column's {@code rev}; {@code null} (reported) if unreadable. */
    private Course fromRow(GamesDao.CourseRow row) {
        CourseCodec.Decoded d = CourseCodec.decode(row.id(), row.data());
        for (String problem : d.problems()) {
            warn.accept("Time trials: course '" + row.id() + "': " + problem);
        }
        return d.course() == null ? null : d.course().withRev(row.rev());
    }
}
