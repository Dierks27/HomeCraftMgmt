package com.dierks.homecraft.gui.games.cabinet.sweeper;

/**
 * Where everything sits on the Creeper Sweeper screen (54 slots), as plain numbers so the layout
 * rules can be tested without a server.
 *
 * <p>The board is exactly rows 0-4 (square {@code i} on slot {@code i}); every control is in row
 * 5, never on 45 or 53 (the page arrows' slots) or 49 (the way out).
 */
final class SweeperLayout {

    /** The choice screen: easy, normal, hard, with each one's high scores one row below. */
    static final int[] LEVELS = {19, 21, 23};
    static final int DAILY = 25;
    /** How far below a board's tile its high-score button is. */
    static final int SCORES_BELOW = 9;
    static final int TITLE = 4;
    static final int RULES = 48;

    /** The board screen's row 5. */
    static final int TOGGLE = 46;
    static final int LEFT = 47;
    static final int TIME = 48;
    static final int AGAIN = 50;
    static final int RUN = 51;

    private SweeperLayout() {
    }

    /** Board square {@code cell} on the screen. */
    static int slot(int cell) {
        return cell;
    }
}
