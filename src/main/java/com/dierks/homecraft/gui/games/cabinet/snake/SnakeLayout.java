package com.dierks.homecraft.gui.games.cabinet.snake;

/**
 * Where everything sits on the Snake screen (54 slots, spec R3.14), as plain numbers so the
 * layout rules can be tested without a server.
 *
 * <p>The 7 by 5 field is columns 1-7 of rows 0-4. Column 0 is all "turn left" and column 8 all
 * "turn right": five-tall targets. Row 5 is 46 apples, 47 best, 48 speed, 49 Back, 50 start /
 * pause, with 45 and 53 left as filler.
 */
final class SnakeLayout {

    static final int TITLE = 4;
    static final int CLASSIC = 20;
    static final int DAILY = 24;
    static final int SCORES_BELOW = 9;
    static final int RULES = 48;

    static final int APPLES = 46;
    static final int BEST = 47;
    static final int SPEED = 48;
    static final int PLAY = 50;
    static final int RUN = 52;

    private SnakeLayout() {
    }

    /** Field square ({@code x}, {@code y}) on the screen. */
    static int slot(int x, int y) {
        return y * 9 + 1 + x;
    }

    /** Row {@code y}'s "turn left" button. */
    static int left(int y) {
        return y * 9;
    }

    /** Row {@code y}'s "turn right" button. */
    static int right(int y) {
        return y * 9 + 8;
    }
}
