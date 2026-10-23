package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeSession.Phase;
import com.dierks.homecraft.trade.TradeSession.Ready;
import com.dierks.homecraft.trade.TradeSession.Side;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A give-away needs a second tap (SHOP-V2-SPEC §4.4, the A8 rule, red team O11): when one side gives something and
 * the other nothing, the first tap on Ready only asks "Give it away? Tap again", for five seconds; the second
 * readies. Any change resets it. A child's random taps can't give a collector away on one tap and a countdown she
 * can't read.
 */
class GiveAwayTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static TradeSession<String> giving(long t) {
        TradeSession<String> s = new TradeSession<>(A, B, 1_500, 3);
        s.put(Side.A, 0, "Pumpkin Cap 2026", 1, t - 10_000);
        return s;
    }

    @Test
    void aOneSidedReadyNeedsTheSecondTap() {
        long t = 100_000;
        TradeSession<String> s = giving(t);
        assertTrue(s.givingAway(Side.A));
        assertEquals(Ready.ARMED, s.tapReady(Side.A, t, false, true), "[X] the first tap only asks");
        assertFalse(s.ready(Side.A), "not ready yet");
        assertTrue(s.armed(Side.A, t + 4_999), "the question waits five seconds");
        assertEquals(Ready.READY, s.tapReady(Side.A, t + 2_000, false, true), "the second tap means it");
        assertTrue(s.ready(Side.A));
        assertEquals(Ready.COUNTDOWN, s.tapReady(Side.B, t + 2_100, false, true),
                "the receiver's own Ready is one tap: they give nothing away");
        assertEquals(Phase.COUNTDOWN, s.phase());
    }

    /**
     * Red-team finding BR-1: the only debounce was one tap a tick, so a child's double tap (100-150 ms, two or three
     * ticks) armed and readied in one gesture, before the yellow tile was even painted. A second tap counts only
     * {@value TradeSession#GIVE_AWAY_SETTLE_MS} ms after the first; one sooner only asks again.
     */
    @Test
    void aDoubleTapOnlyAsks() {
        long t = 100_000;
        TradeSession<String> s = giving(t);
        assertEquals(Ready.READY, s.tapReady(Side.B, t - 1_000, false, true), "the other side gives nothing: ready");
        assertEquals(Ready.ARMED, s.tapReady(Side.A, t, false, true), "the double tap's first half asks");
        assertEquals(Ready.ARMED, s.tapReady(Side.A, t + 100, false, true), "[X] its second half only asks again");
        assertEquals(Ready.ARMED, s.tapReady(Side.A, t + TradeSession.GIVE_AWAY_SETTLE_MS - 1, false, true));
        assertFalse(s.ready(Side.A), "nothing readied");
        assertEquals(Phase.OPEN, s.phase(), "no countdown");
        assertTrue(s.armed(Side.A, t + 4_999), "the question still waits its five seconds from the first tap");
        assertEquals(Ready.COUNTDOWN, s.tapReady(Side.A, t + TradeSession.GIVE_AWAY_SETTLE_MS, false, true),
                "a tap after the question showed means it");
    }

    @Test
    void theQuestionRunsOut() {
        long t = 100_000;
        TradeSession<String> s = giving(t);
        s.tapReady(Side.A, t, false, true);
        assertFalse(s.armed(Side.A, t + 5_000));
        assertEquals(Ready.ARMED, s.tapReady(Side.A, t + 5_000, false, true), "after five seconds it asks again");
    }

    @Test
    void anyChangeResetsIt() {
        long t = 100_000;
        TradeSession<String> s = giving(t);
        s.tapReady(Side.A, t, false, true);
        assertTrue(s.armed(Side.A, t + 100));
        s.put(Side.A, 1, "Diamond", 2, t + 200);
        assertFalse(s.armed(Side.A, t + 300), "[X] a change forgets the first tap");
        assertEquals(Ready.LOCKED, s.tapReady(Side.A, t + 300, false, true));
        assertEquals(Ready.ARMED, s.tapReady(Side.A, t + 2_000, false, true), "and the question comes again");
    }

    @Test
    void moneyAloneIsAGiveAwayTooAndATwoSidedTradeIsNot() {
        long t = 100_000;
        TradeSession<String> s = new TradeSession<>(A, B, 1_500, 3);
        s.setMoney(Side.B, 25, t - 10_000);
        assertEquals(Ready.ARMED, s.tapReady(Side.B, t, false, true), "giving money for nothing asks too");
        s.put(Side.A, 3, "Diamond", 1, t - 10_000 + 1);
        assertFalse(s.givingAway(Side.B), "now both give something");
        assertEquals(Ready.READY, s.tapReady(Side.B, t + 3_000, false, true), "one tap");
    }
}
