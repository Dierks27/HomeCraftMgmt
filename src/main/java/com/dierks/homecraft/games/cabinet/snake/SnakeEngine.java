package com.dierks.homecraft.games.cabinet.snake;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.SplittableRandom;

/**
 * One Snake run (spec §10b, R3.14): the whole game, with no Bukkit in it.
 *
 * <p>The field is 7 wide and 5 tall. The snake is steered with <b>relative</b> turns — "turn
 * left", "turn right", from the snake's own point of view — because a chest screen has no
 * keyboard: two tall buttons either side of the field are easier to hit on a phone than four
 * arrows. A turn is queued and taken on the next move; only ONE turn is queued per move, so a
 * double tap can't spin the snake back into its own neck.
 *
 * <p>Each {@link #step()} moves the head one square. Eating an apple grows the snake by one;
 * running into a wall (there is no wrap-around) or into itself ends the run. Moving into the
 * square the tail is just leaving is fine, as in every Snake. The score is apples eaten.
 *
 * <p><b>Apples.</b> Where apples appear comes from the run's seed, drawn as a fixed sequence of
 * squares: the next apple goes on the next square of the sequence that is free. So everyone
 * playing the daily board (the day's seed) is offered the same apples in the same order, give or
 * take a square their own snake happened to be lying on.
 *
 * <p>The caller owns time: it calls {@link #step()} every {@link #interval} ticks, which starts at
 * the configured tick and gets one tick shorter every {@value #APPLES_PER_SPEEDUP} apples, never
 * below {@value #MIN_TICKS}.
 */
public final class SnakeEngine {

    public static final int COLS = 7;
    public static final int ROWS = 5;
    public static final int CELLS = COLS * ROWS;
    public static final int START_LENGTH = 3;
    /** Apples that fill the field completely: the best possible run. */
    public static final int MAX_APPLES = CELLS - START_LENGTH;
    /** The fastest the snake ever moves: one square every 3 ticks. */
    public static final int MIN_TICKS = 3;
    public static final int APPLES_PER_SPEEDUP = 5;
    /** Draws of the apple sequence before falling back to the next free square in order. */
    private static final int APPLE_DRAWS = 64;

    /** Which way the head points. */
    public enum Heading {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);

        final int dx;
        final int dy;

        Heading(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        /** The heading after turning left, from the snake's point of view. */
        public Heading left() {
            return values()[(ordinal() + 3) % 4];
        }

        /** The heading after turning right, from the snake's point of view. */
        public Heading right() {
            return values()[(ordinal() + 1) % 4];
        }
    }

    /** What a square of the field shows. */
    public enum Look { EMPTY, BODY, HEAD, APPLE, CRASH }

    /** Where a run is. */
    public enum State {
        LIVE,
        /** Hit a wall or itself. */
        CRASHED,
        /** Filled the whole field: nowhere left for an apple. */
        FULL;

        public boolean over() {
            return this != LIVE;
        }
    }

    /** Head first. */
    private final Deque<Integer> body = new ArrayDeque<>();
    private final boolean[] occupied = new boolean[CELLS];
    private final SplittableRandom rng;
    private Heading heading = Heading.RIGHT;
    /** -1 turn left, +1 turn right, 0 none. */
    private int queued;
    private int apple = -1;
    private int apples;
    private int moves;
    private State state = State.LIVE;

    /** A new run: a snake of three in the middle row, heading right, and its first apple. */
    public SnakeEngine(long seed) {
        this.rng = new SplittableRandom(seed);
        int y = ROWS / 2;
        for (int x = START_LENGTH - 1; x >= 0; x--) {
            int c = cell(x, y);
            body.addLast(c);
            occupied[c] = true;
        }
        apple = nextApple();
    }

    // ---- play ---------------------------------------------------------------------------------

    /** Queue a left turn for the next move; false if a turn is already queued or the run is over. */
    public boolean turnLeft() {
        return queue(-1);
    }

    /** Queue a right turn for the next move; false if a turn is already queued or the run is over. */
    public boolean turnRight() {
        return queue(1);
    }

    private boolean queue(int turn) {
        if (state.over() || queued != 0) {
            return false;
        }
        queued = turn;
        return true;
    }

    /** Move one square (taking the queued turn first) and return where the run is. */
    public State step() {
        if (state.over()) {
            return state;
        }
        if (queued != 0) {
            heading = queued < 0 ? heading.left() : heading.right();
            queued = 0;
        }
        int head = body.peekFirst();
        int x = head % COLS + heading.dx;
        int y = head / COLS + heading.dy;
        if (x < 0 || x >= COLS || y < 0 || y >= ROWS) {
            state = State.CRASHED;
            return state;
        }
        int next = cell(x, y);
        boolean eats = next == apple;
        int tail = body.peekLast();
        if (occupied[next] && next != tail) {
            state = State.CRASHED;
            return state;
        }
        moves++;
        if (!eats) {
            body.removeLast();
            occupied[tail] = false;
        }
        body.addFirst(next);
        occupied[next] = true;
        if (eats) {
            apples++;
            apple = nextApple();
            if (apple < 0) {
                state = State.FULL;
            }
        }
        return state;
    }

    // ---- the public view ----------------------------------------------------------------------

    public State state() {
        return state;
    }

    public Heading heading() {
        return heading;
    }

    /** The heading the snake will take on its next move (the queued turn applied). */
    public Heading nextHeading() {
        return queued == 0 ? heading : queued < 0 ? heading.left() : heading.right();
    }

    public int apples() {
        return apples;
    }

    public int length() {
        return body.size();
    }

    public int moves() {
        return moves;
    }

    /** The square the apple is on (row by row), or -1 once the field is full. */
    public int apple() {
        return apple;
    }

    /** The head's square. */
    public int head() {
        return body.peekFirst();
    }

    /** What square ({@code x}, {@code y}) shows. */
    public Look look(int x, int y) {
        int c = cell(x, y);
        if (c == body.peekFirst()) {
            return state == State.CRASHED ? Look.CRASH : Look.HEAD;
        }
        if (occupied[c]) {
            return Look.BODY;
        }
        return c == apple ? Look.APPLE : Look.EMPTY;
    }

    /**
     * Ticks between moves after {@code apples} apples: {@code base}, one tick less every
     * {@value #APPLES_PER_SPEEDUP} apples, never below {@value #MIN_TICKS}.
     */
    public static int interval(int base, int apples) {
        return Math.max(MIN_TICKS, base - Math.max(0, apples) / APPLES_PER_SPEEDUP);
    }

    /** The speed to show: 1 at the start, one more for every speed-up actually taken. */
    public static int speedLevel(int base, int apples) {
        return 1 + Math.max(0, base - interval(base, apples));
    }

    /** Square ({@code x}, {@code y}), row by row. */
    public static int cell(int x, int y) {
        return y * COLS + x;
    }

    /** The next square of the apple sequence that is free, or -1 when none is. */
    private int nextApple() {
        int draw = 0;
        for (int i = 0; i < APPLE_DRAWS; i++) {
            draw = rng.nextInt(CELLS);
            if (!occupied[draw]) {
                return draw;
            }
        }
        for (int i = 1; i <= CELLS; i++) {
            int c = (draw + i) % CELLS;
            if (!occupied[c]) {
                return c;
            }
        }
        return -1;
    }

    /** Test hook: move the apple (to steer a scripted run). */
    void appleAt(int cell) {
        apple = cell;
    }
}
