package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.golf.GolfNow;
import com.dierks.homecraft.games.trial.TrialsNow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Now playing, the Clubhouse's south wall (0.38.3; the owner: "Maybe some stats or something? Have to make it a
 * reason to be there"): who is in which game right now and how far along, a reason to hang out and cheer.
 *
 * <pre>
 *   Now playing
 *   Sam - Sky Rings - ring 3 - 0:41
 *   Lee - Tiny Golf - hole 4
 *   Ana - Snake
 *   Max - in the Clubhouse
 *   Cheer them on: /hcm play cheer
 * </pre>
 * The games first (courses, golf, Race Night, Falling Floors), then the cabinets, then whoever is in the Clubhouse;
 * at most {@value #MAX_ROWS} rows ("and 3 more" when there are more). Names, the game and how far only: nobody's own
 * numbers. Read from the games' live state at most once a second ({@link ClubBoards}); nothing here reads the
 * database.
 *
 * <p>Pure: rows in, the board's text out.
 */
public final class NowBoard {

    /** Where a row sorts: in a game, at a cabinet, in the Clubhouse. */
    public static final int GAME = 0;
    public static final int CABINET = 1;
    public static final int CLUBHOUSE = 2;
    /** Rows under the title (with the cheer line, the board's 9 lines). */
    static final int MAX_ROWS = ClubBoard.MAX_ROWS - 1;

    private NowBoard() {
    }

    /**
     * One player now.
     *
     * @param name  their name
     * @param what  the game or course ("Sky Rings", "Snake", "in the Clubhouse")
     * @param where how far, or {@code null} ("checkpoint 3", "hole 4", "warming up")
     * @param ms    the run's clock, or 0 for none
     * @param order {@link #GAME}, {@link #CABINET} or {@link #CLUBHOUSE}
     */
    public record Playing(String name, String what, String where, long ms, int order) {
    }

    /**
     * Someone in another game, at a cabinet, or in the Clubhouse.
     *
     * @param what  the game's name, or where they are ("in the Clubhouse", "watching live")
     * @param order {@link #GAME}, {@link #CABINET} or {@link #CLUBHOUSE}
     */
    public record Other(UUID player, String what, int order) {
    }

    /**
     * Everyone's row, once each: a run on a course (where it is, its clock), a round of golf (its hole), then the
     * others by {@code order} (another game before a cabinet before the Clubhouse: someone playing Snake in the
     * Clubhouse is at Snake). Names through {@code names}.
     */
    public static List<Playing> rows(List<TrialsNow.Run> runs, List<GolfNow.Round> rounds, List<Other> others,
                                     Function<UUID, String> names) {
        Map<UUID, Playing> by = new LinkedHashMap<>();
        for (TrialsNow.Run r : runs) {
            by.putIfAbsent(r.player(), new Playing(names.apply(r.player()), r.course(), runWhere(r.starting(),
                    r.warmup(), r.racing(), r.done(), r.reached(), r.checkpoints(), r.rings()), r.done() ? 0 : r.ms(),
                    GAME));
        }
        for (GolfNow.Round r : rounds) {
            by.putIfAbsent(r.player(), new Playing(names.apply(r.player()), r.course(),
                    r.done() ? ClubBoardText.FINISHED : ClubBoardText.hole(r.hole()), 0, GAME));
        }
        List<Other> sorted = new ArrayList<>(others);
        sorted.sort(Comparator.comparingInt(Other::order));
        for (Other o : sorted) {
            by.putIfAbsent(o.player(), new Playing(names.apply(o.player()), o.what(), null, 0, o.order()));
        }
        return new ArrayList<>(by.values());
    }

    /** The board: its title, the rows, and the cheer line while anyone is in a game. */
    public static String text(List<Playing> rows) {
        List<Playing> sorted = new ArrayList<>(rows == null ? List.of() : rows);
        sorted.sort(Comparator.comparingInt(Playing::order)
                .thenComparing(p -> p.name() == null ? "" : p.name().toLowerCase(Locale.ROOT)));
        StringBuilder b = new StringBuilder(ClubBoardText.NOW_TITLE);
        if (sorted.isEmpty()) {
            return b.append('\n').append(ClubBoardText.NOBODY).toString();
        }
        int shown = sorted.size() <= MAX_ROWS ? sorted.size() : MAX_ROWS - 1;
        for (int i = 0; i < shown; i++) {
            b.append('\n').append(row(sorted.get(i)));
        }
        if (shown < sorted.size()) {
            b.append('\n').append(ClubBoardText.more(sorted.size() - shown));
        }
        if (sorted.get(0).order() == GAME) {
            b.append('\n').append(ClubBoardText.CHEER);
        }
        return b.toString();
    }

    /** One row: "Sam - Sky Rings - ring 3 - 0:41". */
    static String row(Playing p) {
        return ClubBoardText.playing(p.name(), p.what(), p.where(), p.ms());
    }

    /**
     * How far a run is: "starting" (the 3-2-1), "warming up", "racing" (and its checkpoint), "checkpoint 3" (only on
     * a course with checkpoints, once one is reached), "finished", or nothing.
     */
    public static String runWhere(boolean starting, boolean warmup, boolean racing, boolean done, int reached,
                                  int checkpoints) {
        return runWhere(starting, warmup, racing, done, reached, checkpoints, false);
    }

    /** {@link #runWhere}, saying "ring 3" instead of "checkpoint 3" on an elytra course ({@code rings}). */
    public static String runWhere(boolean starting, boolean warmup, boolean racing, boolean done, int reached,
                                  int checkpoints, boolean rings) {
        if (done) {
            return ClubBoardText.FINISHED;
        }
        if (starting) {
            return ClubBoardText.STARTING;
        }
        if (warmup) {
            return ClubBoardText.WARMING_UP;
        }
        int at = Math.min(reached, checkpoints);
        String cp = checkpoints > 0 && reached > 0 ? rings ? ClubBoardText.ring(at) : ClubBoardText.checkpoint(at) : null;
        if (racing) {
            return cp == null ? ClubBoardText.RACING : ClubBoardText.RACING + ", " + cp;
        }
        return cp;
    }
}
