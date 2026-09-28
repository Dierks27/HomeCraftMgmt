package com.dierks.homecraft.games;

import com.dierks.homecraft.storage.GamesDao;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Personal bests and high-score boards (spec §6.2).
 *
 * <p>A board is a string, so every game shares one table and one screen: cabinets use
 * {@link #CLASSIC} (or a difficulty, {@code easy}/{@code hard}) and {@link #daily}; courses use
 * {@link #course} and {@link #week}; golf uses {@link #golf}. Times are stored in milliseconds
 * (lower is better), everything else as whole numbers. Names are fine in game; only the website
 * feed hides them.
 *
 * <p>A database error never reaches a game: it is logged and the score simply isn't recorded
 * ({@link ScoreResult#NONE}).
 */
public final class Scores {

    /** A cabinet's main board. */
    public static final String CLASSIC = "classic";

    private final GamesService games;

    public Scores(GamesService games) {
        this.games = games;
    }

    /** Record a score now; see {@link GamesDao#submit}. */
    public ScoreResult submit(UUID player, String gameId, String board, long score, boolean lowerIsBetter) {
        try {
            return games.dao().submit(player, gameId, board, score, lowerIsBetter,
                    games.host().clock().nowMillis());
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not record a " + gameId + " score", e);
            return ScoreResult.NONE;
        }
    }

    /** The player's best on the board, or {@code null}. */
    public Long best(UUID player, String gameId, String board) {
        try {
            return games.dao().best(player, gameId, board);
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not read a " + gameId + " score", e);
            return null;
        }
    }

    /** The board's top rows, best first. */
    public List<GamesDao.ScoreRow> top(String gameId, String board, boolean lowerIsBetter, int limit) {
        try {
            return games.dao().top(gameId, board, lowerIsBetter, limit);
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not read the " + gameId + " board", e);
            return List.of();
        }
    }

    /** The board's record, or {@code null}. */
    public GamesDao.ScoreRow record(String gameId, String board, boolean lowerIsBetter) {
        List<GamesDao.ScoreRow> top = top(gameId, board, lowerIsBetter, 1);
        return top.isEmpty() ? null : top.get(0);
    }

    /** Today's daily board: {@code daily:<day>}. */
    public static String daily(long day) {
        return "daily:" + day;
    }

    /** A course's all-time board: {@code course:<id>}. */
    public static String course(String courseId) {
        return "course:" + courseId;
    }

    /** A course's board for one week: {@code week:<id>:<weekKey>}. */
    public static String week(String courseId, long weekKey) {
        return "week:" + courseId + ":" + weekKey;
    }

    /** A golf course's board (strokes): {@code golf:<id>}. */
    public static String golf(String courseId) {
        return "golf:" + courseId;
    }
}
