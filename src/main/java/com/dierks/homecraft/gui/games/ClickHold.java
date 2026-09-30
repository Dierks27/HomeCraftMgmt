package com.dierks.homecraft.gui.games;

import org.bukkit.event.inventory.ClickType;

import java.util.function.LongSupplier;

/**
 * Keeps the rest of a double click from landing on a screen that just changed under the cursor
 * (spec R3.4). Pure — the clock is passed in — so the timing is tested without a server.
 *
 * <p>A vanilla client sends a double click as two plain clicks and then a {@link
 * ClickType#DOUBLE_CLICK}, all on the same slot. A games screen repaints in place, so without this
 * the second and third click act on whatever the first one put there: a board cell where "Today's
 * board" was a moment ago, the confirm of a "click again to quit" that was only just armed, a
 * second card in Twenty-One. So a {@code DOUBLE_CLICK} never counts, and a screen can hold clicks
 * for a moment: {@link #SETTLE_MS} after a choice screen turns into a board or a confirm is armed,
 * {@link #ACTION_MS} after each move in a game of chance. A person reading the new screen takes
 * longer than either; a double click's second half comes well inside them.
 */
public final class ClickHold {

    /** After a board is dealt into the same screen, or a "click again" is armed. */
    public static final long SETTLE_MS = 250;
    /** After each move in a game of chance (a Hit, a guess): the next one waits this long. */
    public static final long ACTION_MS = 300;

    private final LongSupplier clock;
    private long until = Long.MIN_VALUE;

    /** @param clock milliseconds, only ever compared with itself (a monotonic clock is best) */
    public ClickHold(LongSupplier clock) {
        this.clock = clock;
    }

    /** Hold every click for {@code ms} from now (an earlier hold that ends later still wins). */
    public void hold(long ms) {
        until = Math.max(until, clock.getAsLong() + Math.max(0, ms));
    }

    /** Whether a click now falls inside a hold. */
    public boolean held() {
        return clock.getAsLong() < until;
    }

    /** Whether a click of this kind, arriving now, should run: never a DOUBLE_CLICK, never inside a hold. */
    public boolean passes(ClickType click) {
        return click != ClickType.DOUBLE_CLICK && !held();
    }
}
