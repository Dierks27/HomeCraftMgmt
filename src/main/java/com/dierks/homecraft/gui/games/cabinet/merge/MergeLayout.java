package com.dierks.homecraft.gui.games.cabinet.merge;

import com.dierks.homecraft.games.cabinet.merge.MergeEngine;

/**
 * Where everything sits on the Ore Merge screen (54 slots), as plain numbers so the layout rules
 * can be tested without a server.
 *
 * <p>The 4 by 4 grid is rows 1-4, columns 2-5; row 0 shows the score, the board and the biggest
 * ore. The four slide buttons are in row 5 either side of Back (◀ ▲ Back ▼ ▶), never on 45 or 53
 * (the page arrows' slots) or 49 (the way out).
 */
final class MergeLayout {

    static final int TITLE = 4;
    static final int CLASSIC = 20;
    static final int DAILY = 24;
    static final int SCORES_BELOW = 9;
    static final int RULES = 48;

    static final int SCORE = 2;
    /** The board being played; "Play again" once the game is over. */
    static final int RUN = 4;
    static final int BIGGEST = 6;
    static final int END = 46;

    private MergeLayout() {
    }

    /** Grid square {@code cell} (row by row) on the screen. */
    static int slot(int cell) {
        return (cell / MergeEngine.SIZE + 1) * 9 + 2 + cell % MergeEngine.SIZE;
    }

    /** The slide button for {@code dir}. */
    static int arrow(MergeEngine.Dir dir) {
        return switch (dir) {
            case LEFT -> 47;
            case UP -> 48;
            case DOWN -> 50;
            case RIGHT -> 51;
        };
    }
}
