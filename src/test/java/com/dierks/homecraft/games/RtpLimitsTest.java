package com.dierks.homecraft.games;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RTP band locked in code (spec §5.1, R1.1, R1.2): 85-95 tokens back for every 100 put in,
 * whatever config says; the solve rule every engine uses to pick its published value; and the one
 * way the value is floored for players (whole) and for admins and the website (one decimal).
 */
class RtpLimitsTest {

    @Test
    void aTargetOutsideTheBandIsClampedWithOneWarnNamingTheKey() {
        List<String> warns = new ArrayList<>();
        assertEquals(0.90, RtpLimits.clampPercent(90, "games.wheel.rtp", warns::add), 1e-12);
        assertEquals(List.of(), warns);
        assertEquals(RtpLimits.MAX, RtpLimits.clampPercent(99, "games.wheel.rtp", warns::add));
        assertEquals(List.of("games.wheel.rtp 99 goes past the limit locked in code (95) - using 95"), warns);
        warns.clear();
        assertEquals(RtpLimits.MIN, RtpLimits.clampPercent(80, "games.wheel.rtp", warns::add));
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).startsWith("games.wheel.rtp 80 "), warns.toString());
        assertEquals(RtpLimits.DEFAULT, RtpLimits.clampPercent(Double.NaN, "k", w -> { }));
        assertEquals(0.85, RtpLimits.MIN);
        assertEquals(0.95, RtpLimits.MAX);
    }

    @Test
    void theLargestValueAtOrBelowTheTargetIsPicked() {
        RtpLimits.Pick p = RtpLimits.pick(0.90, new double[]{0.84, 0.88, 0.8999, 0.91});
        assertEquals(2, p.index());
        assertEquals(0.8999, p.rtp());
        assertFalse(p.aboveTarget());
    }

    @Test
    void underTheBandTheSmallestValueInsideItIsPicked() {
        RtpLimits.Pick p = RtpLimits.pick(0.85, new double[]{0.80, 0.84, 0.90, 0.86});
        assertEquals(3, p.index(), "0.84 is under 85, so the smallest at or above 85");
        assertTrue(p.aboveTarget(), "worth one INFO line");
        assertNull(RtpLimits.pick(0.90, new double[]{0.50, 0.97}), "nothing in the band: the stake is dropped");
        assertEquals("50.0% or 97.0%", RtpLimits.nearest(new double[]{0.50, 0.97, 0.30}));
        assertEquals("nothing", RtpLimits.nearest(new double[0]));
    }

    @Test
    void exactFractionsCountAValueEqualToTheTarget() {
        RtpLimits.Ratio target = RtpLimits.Ratio.percent(85);
        assertEquals(new RtpLimits.Ratio(17, 20), target);
        assertEquals(new RtpLimits.Ratio(7, 8), RtpLimits.Ratio.percent(87.5));
        RtpLimits.Pick p = RtpLimits.pick(target, new RtpLimits.Ratio[]{new RtpLimits.Ratio(84, 100),
                new RtpLimits.Ratio(170, 200), new RtpLimits.Ratio(9, 10)});
        assertEquals(1, p.index(), "170/200 is exactly 85%: it counts as at or below the target");
        assertFalse(p.aboveTarget());
        assertTrue(RtpLimits.inBand(new RtpLimits.Ratio(19, 20)), "95% exactly is inside");
        assertFalse(RtpLimits.inBand(new RtpLimits.Ratio(951, 1000)));
        assertNull(RtpLimits.pick(target, new RtpLimits.Ratio[]{new RtpLimits.Ratio(96, 100)}));
    }

    @Test
    void playersSeeTheComputedValueFlooredAndAdminsToOneDecimal() {
        assertEquals(89, RtpLimits.wholePercent(0.8976));
        assertEquals(89.7, RtpLimits.tenthPercent(0.8976));
        assertEquals(90, RtpLimits.wholePercent(0.90), "0.9 * 100 is not 89.999...");
        assertEquals(29, RtpLimits.wholePercent(0.29));
        assertEquals("gives back about 89 of every 100 tokens", RtpLimits.playerLine(0.8976));
        assertTrue(RtpLimits.inBand(0.85) && RtpLimits.inBand(0.95) && !RtpLimits.inBand(0.951));
    }
}
