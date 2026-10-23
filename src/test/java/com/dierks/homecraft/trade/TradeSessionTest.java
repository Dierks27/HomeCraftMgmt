package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeSession.Cause;
import com.dierks.homecraft.trade.TradeSession.Phase;
import com.dierks.homecraft.trade.TradeSession.Ready;
import com.dierks.homecraft.trade.TradeSession.Side;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One trade's state (SHOP-V2-SPEC §4.4, §4.5, §4.8): add, take, one more, one fewer; any change clears both Ready
 * flags and starts the change lock; Ready is refused in the lock, with empty tables, with a problem or with the
 * other window shut; both Ready count down; a change or an un-Ready stops it; the countdown runs the swap exactly
 * once, whatever else happens in the same tick; and a cancel, from every cause, leaves both bags as they were.
 */
class TradeSessionTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long LOCK = 1_500;

    private static TradeSession<String> session() {
        return new TradeSession<>(A, B, LOCK, 3);
    }

    /** Both sides offer something and the lock has run out at {@code t}. */
    private static TradeSession<String> twoSided(long t) {
        TradeSession<String> s = session();
        s.put(Side.A, 0, "diamond", 3, t - 2 * LOCK);
        s.put(Side.B, 4, "emerald", 10, t - 2 * LOCK);
        return s;
    }

    @Test
    void addTakeOneMoreOneFewer() {
        TradeSession<String> s = session();
        assertTrue(s.put(Side.A, 5, "diamond", 20, 0), "the whole stack");
        assertEquals(20, s.entry(Side.A, 5).amount());
        assertTrue(s.setAmount(Side.A, 5, 21, 1), "one more");
        assertTrue(s.setAmount(Side.A, 5, 20, 2), "one fewer");
        assertFalse(s.put(Side.A, 5, "diamond", 20, 3), "the same amount again is no change");
        assertTrue(s.setAmount(Side.A, 5, 0, 4), "down to none takes it off");
        assertNull(s.entry(Side.A, 5));
        assertFalse(s.remove(Side.A, 5, 5), "not there");
        assertFalse(s.put(Side.A, 6, "dirt", 0, 6), "nothing is not an offer");
    }

    @Test
    void sixteenStacksATable() {
        TradeSession<String> s = session();
        for (int slot = 0; slot < TradeSession.TABLE; slot++) {
            assertTrue(s.put(Side.A, slot, "dirt", 1, 0));
        }
        assertFalse(s.put(Side.A, 20, "dirt", 1, 0), "the 17th stack doesn't fit the table");
        assertTrue(s.put(Side.A, 3, "dirt", 2, 0), "an amount on one already there still changes");
        assertTrue(s.put(Side.B, 20, "dirt", 1, 0), "the other table has its own 16");
    }

    @Test
    void anyChangeClearsBothReadyAndStartsTheLock() {
        long t = 10_000;
        TradeSession<String> s = twoSided(t);
        assertEquals(Ready.READY, s.tapReady(Side.A, t, false, true));
        assertTrue(s.ready(Side.A));
        int v = s.version();
        assertTrue(s.setMoney(Side.B, 50, t + 10), "money is a change");
        assertFalse(s.ready(Side.A), "both un-readied");
        assertTrue(s.version() > v, "and both windows repaint");
        assertTrue(s.locked(t + 10 + LOCK - 1), "the lock runs");
        assertEquals(Ready.LOCKED, s.tapReady(Side.A, t + 100, false, true), "Ready doesn't work in the lock");
        assertEquals(Ready.LOCKED, s.tapReady(Side.B, t + 100, false, true), "for either side");
        assertFalse(s.locked(t + 10 + LOCK));
        assertEquals(Ready.READY, s.tapReady(Side.A, t + 10 + LOCK, false, true));
    }

    @Test
    void readyIsRefusedWithEmptyTablesAProblemOrTheOtherWindowShut() {
        TradeSession<String> s = session();
        assertEquals(Ready.EMPTY, s.tapReady(Side.A, 100_000, false, true));
        s = twoSided(100_000);
        assertEquals(Ready.PROBLEM, s.tapReady(Side.A, 100_000, true, true));
        assertEquals(Ready.OTHER_GONE, s.tapReady(Side.A, 100_000, false, false));
        assertFalse(s.ready(Side.A));
    }

    @Test
    void bothReadyCountDownAndAChangeOrAnUnreadyStopsIt() {
        long t = 10_000;
        TradeSession<String> s = twoSided(t);
        s.tapReady(Side.A, t, false, true);
        assertEquals(Ready.COUNTDOWN, s.tapReady(Side.B, t, false, true));
        assertEquals(Phase.COUNTDOWN, s.phase());
        assertEquals(3, s.secondsLeft(t));
        assertEquals(2, s.secondsLeft(t + 1_500));
        assertEquals(1, s.secondsLeft(t + 2_999));
        assertEquals(0, s.secondsLeft(t + 3_000));
        assertTrue(s.put(Side.A, 1, "gold", 1, t + 1_000), "things can still change during the countdown");
        assertEquals(Phase.OPEN, s.phase(), "and a change stops it");
        assertFalse(s.ready(Side.B));

        long u = t + 10_000;
        s.tapReady(Side.A, u, false, true);
        s.tapReady(Side.B, u, false, true);
        assertEquals(Ready.UNREADY, s.tapReady(Side.A, u + 500, false, true), "a tap on Ready again un-readies");
        assertEquals(Phase.OPEN, s.phase(), "and stops the countdown");
        assertFalse(s.beginSwap(u + 5_000), "no swap from OPEN");
    }

    @Test
    void theCountdownRunsTheSwapExactlyOnce() {
        long t = 10_000;
        TradeSession<String> s = twoSided(t);
        s.tapReady(Side.A, t, false, true);
        s.tapReady(Side.B, t, false, true);
        assertFalse(s.beginSwap(t + 2_000), "not before the countdown ends");
        long end = t + 3_000;
        // two timers, a click and a close, all in the swap's tick
        assertTrue(s.beginSwap(end), "the first timer runs it");
        assertEquals(Phase.SWAPPING, s.phase(), "SWAPPING is set before anything moves");
        assertFalse(s.beginSwap(end), "[X] the second timer does nothing");
        assertFalse(s.put(Side.A, 7, "dirt", 1, end), "[X] a click changes nothing");
        assertFalse(s.setMoney(Side.B, 5, end), "nor money");
        assertEquals(Ready.CLOSED, s.tapReady(Side.A, end, false, true));
        assertFalse(s.cancel(Cause.CLOSED), "[X] a close in the same tick doesn't cancel it");
        assertEquals(Phase.SWAPPING, s.phase());
        s.done();
        assertEquals(Phase.DONE, s.phase());
        assertFalse(s.cancel(Cause.QUIT), "nor afterwards");
    }

    @Test
    void aFailedSwapIsOpenAgainWithBothUnreadied() {
        long t = 10_000;
        TradeSession<String> s = twoSided(t);
        s.tapReady(Side.A, t, false, true);
        s.tapReady(Side.B, t, false, true);
        assertTrue(s.beginSwap(t + 3_000));
        s.swapFailed(t + 3_000);
        assertEquals(Phase.OPEN, s.phase());
        assertFalse(s.ready(Side.A) || s.ready(Side.B));
        assertTrue(s.locked(t + 3_001), "a failure is a change: the lock runs");
    }

    @Test
    void aCancelFromEveryCauseLeavesBothBagsUntouched() {
        for (Cause cause : Cause.values()) {
            for (boolean counting : new boolean[]{false, true}) {
                String[] bagA = {"diamond x3", null, "dirt x64"};
                String[] bagB = {"emerald x10", "Creeper Mini"};
                String[] beforeA = Arrays.copyOf(bagA, bagA.length);
                String[] beforeB = Arrays.copyOf(bagB, bagB.length);
                long t = 10_000;
                TradeSession<String> s = session();
                s.put(Side.A, 0, bagA[0], 3, t - 5_000);
                s.put(Side.B, 1, bagB[1], 1, t - 5_000);
                s.setMoney(Side.B, 20, t - 5_000);
                if (counting) {
                    s.tapReady(Side.A, t, false, true);
                    s.tapReady(Side.B, t, false, true);
                    assertEquals(Phase.COUNTDOWN, s.phase());
                }
                assertTrue(s.cancel(cause), cause + " cancels");
                assertEquals(Phase.CANCELLED, s.phase());
                assertEquals(cause, s.cause());
                assertFalse(s.cancel(Cause.STOP), "once");
                assertFalse(s.beginSwap(t + 60_000), "and nothing swaps after it");
                assertArrayEquals(beforeA, bagA, cause + ": nothing left A's bag, nothing to give back");
                assertArrayEquals(beforeB, bagB, cause + ": nor B's");
            }
        }
    }
}
