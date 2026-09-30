package com.dierks.homecraft.games.cabinet.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The Arcade's Connect Four player (spec §10b): a negamax search with alpha-beta pruning over
 * {@link ConnectFourBoard}, at three strengths.
 *
 * <ul>
 *   <li><b>Easy</b> looks two moves ahead with a little noise on top, and one move in four it just
 *       drops a piece anywhere — so it sometimes leaves a four open for a child to find.</li>
 *   <li><b>Normal</b> looks four moves ahead.</li>
 *   <li><b>Hard</b> looks seven moves ahead with a centre-weighted evaluation: it takes any win
 *       it can see, blocks any it must, and builds threats in the middle where most lines run.</li>
 * </ul>
 *
 * <p>Moves that score the same are picked between with the game's own random generator (a fresh
 * seed per game), so the Arcade doesn't open every game identically; with the same seed a game
 * replays exactly. Columns are searched centre first, which is what makes the pruning cut deep
 * enough for seven moves to take milliseconds on the main thread.
 */
public final class ConnectFourAI {

    /** How strong the Arcade plays: how many moves it looks ahead. */
    public enum Level {
        EASY(2),
        NORMAL(4),
        HARD(7);

        public final int depth;

        Level(int depth) {
            this.depth = depth;
        }
    }

    /** A win: bigger than any evaluation, minus the moves it takes (sooner is better). */
    static final int WIN = 1_000_000;
    private static final int INF = WIN + 1_000;
    /** A line of 1, 2 or 3 of one player's pieces with the rest empty. */
    private static final int[] LINE = {0, 1, 5, 40};
    /** A piece in the middle column: it sits in the most lines. */
    private static final int CENTRE = 3;
    /** Easy's noise, less than one open three is worth. */
    private static final int EASY_NOISE = 12;

    private ConnectFourAI() {
    }

    /**
     * The column the Arcade plays for the player whose turn it is.
     *
     * @return a playable column, or -1 if there is none (the game is over)
     */
    public static int choose(ConnectFourBoard board, Level level, SplittableRandom rng) {
        List<Integer> legal = new ArrayList<>();
        for (int col : order(board.cols())) {
            if (board.canPlay(col)) {
                legal.add(col);
            }
        }
        if (legal.isEmpty()) {
            return -1;
        }
        if (level == Level.EASY && rng.nextInt(4) == 0) {
            return legal.get(rng.nextInt(legal.size()));
        }
        int me = board.turn();
        int best = Integer.MIN_VALUE;
        List<Integer> ties = new ArrayList<>();
        for (int col : legal) {
            int score;
            if (board.winsWith(col, me)) {
                score = WIN - 1;
            } else if (level == Level.EASY) {
                board.play(col);
                score = -negamax(board, level.depth - 1, -INF, INF, 1) + rng.nextInt(EASY_NOISE);
                board.undo();
            } else {
                // A window just below the best so far: anything that could tie or beat it comes
                // back exact, anything worse fails low cheaply.
                int alpha = best == Integer.MIN_VALUE ? -INF : best - 1;
                board.play(col);
                score = -negamax(board, level.depth - 1, -INF, -alpha, 1);
                board.undo();
            }
            if (score > best) {
                best = score;
                ties.clear();
                ties.add(col);
            } else if (score == best) {
                ties.add(col);
            }
        }
        return ties.size() == 1 ? ties.get(0) : ties.get(rng.nextInt(ties.size()));
    }

    /**
     * The value of the position for the player to move, looking {@code depth} moves ahead
     * (fail-hard alpha-beta). {@code ply} is how many moves from the root this is, so a win found
     * sooner scores higher and a loss later scores less badly.
     */
    static int negamax(ConnectFourBoard board, int depth, int alpha, int beta, int ply) {
        if (board.full()) {
            return 0;
        }
        int me = board.turn();
        int cols = board.cols();
        int[] order = order(cols);
        for (int col : order) {
            if (board.winsWith(col, me)) {
                return WIN - (ply + 1);
            }
        }
        if (depth <= 0) {
            return evaluate(board);
        }
        boolean any = false;
        for (int col : order) {
            if (!board.room(col)) {
                continue;
            }
            any = true;
            board.play(col);
            int score = -negamax(board, depth - 1, -beta, -alpha, ply + 1);
            board.undo();
            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return any ? alpha : 0;
    }

    /**
     * How good the position looks for the player to move, without looking ahead: every line of
     * four that only one player has pieces in counts for that player (more pieces, much more),
     * plus a little for each piece in the middle column.
     */
    static int evaluate(ConnectFourBoard board) {
        int me = board.turn();
        long mine = board.bits(me);
        long theirs = board.bits(1 - me);
        int score = 0;
        for (long line : board.lines()) {
            int m = Long.bitCount(line & mine);
            int o = Long.bitCount(line & theirs);
            if (o == 0) {
                score += LINE[Math.min(m, 3)];
            } else if (m == 0) {
                score -= LINE[Math.min(o, 3)];
            }
        }
        long centre = board.column(board.cols() / 2);
        score += CENTRE * (Long.bitCount(mine & centre) - Long.bitCount(theirs & centre));
        return score;
    }

    /** Each board width's column order, worked out once (the search asks at every node). */
    private static final int[][] ORDERS = new int[64][];

    /** Columns from the middle out: 3, 2, 4, 1, 5, 0, 6 on seven. Don't change the array. */
    static int[] order(int cols) {
        int[] known = ORDERS[cols];
        if (known != null) {
            return known;
        }
        int[] out = new int[cols];
        int mid = cols / 2;
        int n = 0;
        out[n++] = mid;
        for (int d = 1; n < cols; d++) {
            if (mid - d >= 0) {
                out[n++] = mid - d;
            }
            if (n < cols && mid + d < cols) {
                out[n++] = mid + d;
            }
        }
        ORDERS[cols] = out;
        return out;
    }
}
