package com.dierks.homecraft.gui.games;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a games screen keeps a double click from acting on what it just painted (spec R3.4).
 *
 * <p>Pinned here: a {@code DOUBLE_CLICK} never counts; a hold drops every click until it ends and
 * then lets them through; a later hold can't shorten one already running; after a deal the rest of
 * a double click on "Today's board" can't dig the square that appeared under it; and a Twenty-One
 * or Higher or Lower move holds the next one for {@link ClickHold#ACTION_MS}, so two guesses 50 ms
 * apart are one guess.
 */
class ClickHoldTest {

    private long now = 1_000_000L;
    private final ClickHold hold = new ClickHold(() -> now);

    /** A screen whose one button takes a move and holds the next, as Twenty-One's Hit does. */
    private int moves;

    private void click(ClickType type) {
        if (hold.passes(type)) {
            hold.hold(ClickHold.ACTION_MS);
            moves++;
        }
    }

    @Test
    void aDoubleClicksThirdEventNeverCounts() {
        assertFalse(hold.passes(ClickType.DOUBLE_CLICK), "PICKUP_ALL on a menu is never a move of its own");
        assertTrue(hold.passes(ClickType.LEFT), "a plain click passes when nothing is held");
        assertTrue(hold.passes(ClickType.RIGHT), "and so does a right click");
    }

    @Test
    void aHoldDropsClicksUntilItEnds() {
        hold.hold(ClickHold.SETTLE_MS);
        assertTrue(hold.held(), "held at once");
        now += ClickHold.SETTLE_MS - 1;
        assertFalse(hold.passes(ClickType.LEFT), "still inside it: dropped");
        now += 1;
        assertTrue(hold.passes(ClickType.LEFT), "over at the exact moment it ends");
    }

    @Test
    void aShorterHoldNeverCutsALongerOneShort() {
        hold.hold(ClickHold.ACTION_MS);
        hold.hold(10);
        now += 100;
        assertTrue(hold.held(), "the 300 ms hold still runs after a 10 ms one");
    }

    @Test
    void theRestOfADoubleClickOnTodaysBoardCantDigTheNewBoard() {
        int[] digs = {0};
        boolean[] dealt = {false};
        Runnable tap = () -> {
            if (!hold.passes(ClickType.LEFT)) {
                return;
            }
            if (!dealt[0]) {
                dealt[0] = true; // "Today's board": the board is dealt into this screen
                hold.hold(ClickHold.SETTLE_MS);
            } else {
                digs[0]++; // the same slot is now a board square
            }
        };
        tap.run();
        now += 60;
        tap.run();
        assertFalse(hold.passes(ClickType.DOUBLE_CLICK), "the third event is dropped as well");
        assertEquals(0, digs[0], "the second click of the double click digs nothing");
        now += 400;
        tap.run();
        assertEquals(1, digs[0], "a real tap a moment later digs");
    }

    @Test
    void twoGuessesFiftyMillisecondsApartAreOneGuess() {
        click(ClickType.LEFT);
        now += 50;
        click(ClickType.LEFT);
        assertEquals(1, moves, "only the first guess is applied: the second would be on a card never seen");
        now += 2;
        click(ClickType.DOUBLE_CLICK);
        assertEquals(1, moves, "the vanilla double click's PICKUP_ALL adds nothing either");
        now = now - 52 + ClickHold.ACTION_MS;
        click(ClickType.LEFT);
        assertEquals(2, moves, "a guess 300 ms after the last one counts");
    }
}
