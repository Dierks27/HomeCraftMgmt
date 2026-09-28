package com.dierks.homecraft.games.cabinet.sweeper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.SplittableRandom;

/**
 * One Creeper Sweeper board (spec §10b): the whole game, with no Bukkit in it.
 *
 * <p>The board is 9 wide and 5 tall, so its 45 squares map one to one onto a chest screen's top
 * five rows (square {@code i} is slot {@code i}). The screen only draws {@link #look} and
 * {@link #number} and forwards taps to {@link #dig} and {@link #toggleFlag}; where the creepers
 * are never leaves this class until the board is over.
 *
 * <p><b>Why the first dig is always safe.</b> A game that can end on the first tap is a coin toss,
 * not a puzzle. A Classic board places its creepers only when the first square is dug, keeping
 * that square AND its neighbours clear, so the first dig always opens an area to reason from. The
 * daily board has to be the same for everyone, so it can't wait for anyone's first tap: it is laid
 * out from the day's seed with a safe opening already dug, and every player starts from that.
 *
 * <p>On a Classic board the clock starts on the player's first dig and stops on the dig that ends
 * the board. The daily board arrives already dug open, so its clock starts when it is dealt:
 * otherwise a player could flag every creeper they can work out from the opening for free and
 * then clear the board in a second. The caller passes the time in, so the engine stays testable.
 */
public final class SweeperEngine {

    public static final int COLS = 9;
    public static final int ROWS = 5;
    public static final int CELLS = COLS * ROWS;

    /** Where a board is. */
    public enum State {
        /** A Classic board nothing has been dug on yet (a daily board starts LIVE). */
        READY,
        LIVE,
        /** Every safe square dug. */
        WON,
        /** A creeper dug: "Boom!". */
        LOST;

        public boolean over() {
            return this == WON || this == LOST;
        }
    }

    /** What one square shows. */
    public enum Look {
        /** Not dug. Every hidden square looks exactly the same. */
        HIDDEN,
        FLAG,
        /** Dug: see {@link #number}. */
        OPEN,
        /** After a Boom: a creeper nobody flagged. */
        CREEPER,
        /** After a Boom: the creeper that was dug. */
        BOOM,
        /** After a Boom: a flag with no creeper under it. */
        WRONG_FLAG
    }

    private final int mines;
    private final SplittableRandom rng;
    private final boolean[] mine = new boolean[CELLS];
    private final boolean[] open = new boolean[CELLS];
    private final boolean[] flag = new boolean[CELLS];
    private boolean placed;
    private int opened;
    private int flags;
    private State state = State.READY;
    private long startedAt = -1;
    private long endedAt = -1;
    private int boom = -1;

    private SweeperEngine(int mines, long seed) {
        this.mines = Math.max(1, Math.min(mines, CELLS - 9));
        this.rng = new SplittableRandom(seed);
    }

    /** A Classic board: its creepers are placed on the first dig, away from it. */
    public static SweeperEngine classic(int mines, long seed) {
        return new SweeperEngine(mines, seed);
    }

    /**
     * The daily board: laid out from {@code seed} alone, with a safe opening already dug, so
     * every player gets exactly the same board. An opening that would already clear the whole
     * board is skipped for the next one the seed gives. It is LIVE from {@code dealtAt}: the
     * clock runs from the deal, not from the first dig.
     */
    public static SweeperEngine daily(int mines, long seed, long dealtAt) {
        SweeperEngine board = new SweeperEngine(mines, seed);
        for (int attempt = 0; attempt < 100; attempt++) {
            board.reset();
            int start = board.rng.nextInt(CELLS);
            board.place(start);
            board.reveal(start);
            if (board.opened < CELLS - board.mines) {
                break;
            }
        }
        board.state = State.LIVE;
        board.startedAt = dealtAt;
        return board;
    }

    private void reset() {
        Arrays.fill(mine, false);
        Arrays.fill(open, false);
        opened = 0;
        placed = false;
    }

    // ---- play ---------------------------------------------------------------------------------

    /**
     * Dig {@code cell} at time {@code now} (ms). Does nothing (false) on a flagged or already dug
     * square, or once the board is over. On a Classic board the first dig starts the clock and
     * places the creepers away from it.
     */
    public boolean dig(int cell, long now) {
        if (state.over() || !inside(cell) || flag[cell] || open[cell]) {
            return false;
        }
        if (!placed) {
            place(cell);
        }
        if (state == State.READY) {
            state = State.LIVE;
            startedAt = now;
        }
        if (mine[cell]) {
            state = State.LOST;
            boom = cell;
            endedAt = now;
            return true;
        }
        reveal(cell);
        if (opened == CELLS - mines) {
            state = State.WON;
            endedAt = now;
        }
        return true;
    }

    /**
     * Put a flag on a hidden square, or take it off. No more flags than creepers, and never on a
     * dug square or a finished board (false when nothing changed).
     */
    public boolean toggleFlag(int cell) {
        if (state.over() || !inside(cell) || open[cell]) {
            return false;
        }
        if (flag[cell]) {
            flag[cell] = false;
            flags--;
            return true;
        }
        if (flags >= mines) {
            return false;
        }
        flag[cell] = true;
        flags++;
        return true;
    }

    // ---- the public view ----------------------------------------------------------------------

    public State state() {
        return state;
    }

    public int mines() {
        return mines;
    }

    public int flags() {
        return flags;
    }

    /** Creepers not yet flagged (what the screen counts down). */
    public int creepersLeft() {
        return mines - flags;
    }

    /** What square {@code cell} shows now. */
    public Look look(int cell) {
        if (open[cell]) {
            return Look.OPEN;
        }
        if (state == State.LOST) {
            if (cell == boom) {
                return Look.BOOM;
            }
            if (mine[cell]) {
                return flag[cell] ? Look.FLAG : Look.CREEPER;
            }
            return flag[cell] ? Look.WRONG_FLAG : Look.HIDDEN;
        }
        if (state == State.WON && mine[cell]) {
            return Look.FLAG;
        }
        return flag[cell] ? Look.FLAG : Look.HIDDEN;
    }

    /** How many creepers touch a DUG square (0-8); -1 for any square not dug, so nothing leaks. */
    public int number(int cell) {
        return open[cell] ? around(cell) : -1;
    }

    /**
     * The time on the clock in ms: 0 before a Classic board's first dig, running while live
     * (a daily board from its deal), frozen when the board ends. A cleared board's score.
     */
    public long timeMs(long now) {
        if (startedAt < 0) {
            return 0;
        }
        return Math.max(0, (endedAt >= 0 ? endedAt : now) - startedAt);
    }

    /** "1:23.4": minutes, seconds and tenths, cut down (never rounded up past a milestone). */
    public static String clock(long ms) {
        long tenths = Math.max(0, ms) / 100;
        long minutes = tenths / 600;
        long seconds = (tenths / 10) % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds + "." + (tenths % 10);
    }

    // ---- internals ----------------------------------------------------------------------------

    /** Lay the creepers anywhere but {@code safe} and its neighbours (a partial shuffle). */
    private void place(int safe) {
        int[] spots = new int[CELLS];
        int n = 0;
        for (int c = 0; c < CELLS; c++) {
            if (!touching(c, safe)) {
                spots[n++] = c;
            }
        }
        int count = Math.min(mines, n);
        for (int i = 0; i < count; i++) {
            int j = i + rng.nextInt(n - i);
            int t = spots[i];
            spots[i] = spots[j];
            spots[j] = t;
            mine[spots[i]] = true;
        }
        placed = true;
    }

    /** Dig {@code start}; a square with no creeper around it opens its neighbours too (flood fill). */
    private void reveal(int start) {
        Deque<Integer> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            int c = todo.poll();
            if (open[c] || flag[c] || mine[c]) {
                continue;
            }
            open[c] = true;
            opened++;
            if (around(c) != 0) {
                continue;
            }
            for (int d : neighbours(c)) {
                if (!open[d]) {
                    todo.add(d);
                }
            }
        }
    }

    private int around(int cell) {
        int n = 0;
        for (int d : neighbours(cell)) {
            if (mine[d]) {
                n++;
            }
        }
        return n;
    }

    /** The up to eight squares touching {@code cell}. */
    static int[] neighbours(int cell) {
        int x = cell % COLS;
        int y = cell / COLS;
        int[] out = new int[8];
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if ((dx != 0 || dy != 0) && nx >= 0 && nx < COLS && ny >= 0 && ny < ROWS) {
                    out[n++] = ny * COLS + nx;
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** Whether {@code a} is {@code b} or one of its neighbours. */
    static boolean touching(int a, int b) {
        return Math.abs(a % COLS - b % COLS) <= 1 && Math.abs(a / COLS - b / COLS) <= 1;
    }

    private static boolean inside(int cell) {
        return cell >= 0 && cell < CELLS;
    }

    /** Test view: whether a creeper is under {@code cell}. */
    boolean mineAt(int cell) {
        return mine[cell];
    }

    /** Test view: whether the creepers have been laid yet. */
    boolean placed() {
        return placed;
    }

    /** Test hook: a board with creepers exactly where {@code at} says (the first dig then places nothing). */
    static SweeperEngine fixed(long seed, int... at) {
        SweeperEngine board = new SweeperEngine(at.length, seed);
        for (int c : at) {
            board.mine[c] = true;
        }
        board.placed = true;
        return board;
    }
}
