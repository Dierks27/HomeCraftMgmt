package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The speed-cap envelope the Mountain Run's proof rests on (Course Variety §2.11, Appendix B): every
 * number pinned, because a planned layout depends on each (changing one is an ALGO bump).
 */
class BoatEnvelopeTest {

    @Test
    void theFallTicksAreTheLeastThatDropTheBlocksWithNoDrag() {
        assertEquals(7, BoatEnvelope.fallTicks(1), "0.02 x 7 x 8 = 1.12 is the first at least 1 (6 gives 0.84)");
        assertEquals(10, BoatEnvelope.fallTicks(2), "0.02 x 10 x 11 = 2.2 is the first at least 2 (9 gives 1.8)");
        assertEquals(0, BoatEnvelope.fallTicks(0), "no drop, no air time");
        for (int d = 1; d <= 6; d++) {
            int t = BoatEnvelope.fallTicks(d);
            assertTrue(0.02 * t * (t + 1) >= d - 1e-9 && 0.02 * (t - 1) * t < d,
                    d + " blocks: t(d) is the least n with 0.02 n (n + 1) >= d");
        }
    }

    @Test
    void theFlightAndZoneAreTheSpecsNumbers() {
        assertEquals(38, BoatEnvelope.flight(1), "L(1) = 3.75 x ceil(1.3 x 7 = 9.1) = 37.5, rounded up");
        assertEquals(49, BoatEnvelope.flight(2), "L(2) = 3.75 x ceil(1.3 x 10 = 13) = 48.75: exactly 13, not 14");
        assertEquals(40, BoatEnvelope.zone(1), "Z(1) = L(1) + 2");
        assertEquals(51, BoatEnvelope.zone(2), "Z(2) = L(2) + 2");
        assertEquals(2, BoatEnvelope.MAX_DROP, "below Java's 3-block boat-break threshold");
    }

    @Test
    void theSpeedCapIsTheBoatsOwnFairPlayCap() {
        assertEquals(TrialKind.BOAT.maxSpeed(), BoatEnvelope.maxBlocksPerSecond(), 1e-9,
                "3.75 blocks a tick is the 75 blocks a second FairPlay voids a run over");
        assertEquals(0.04, BoatEnvelope.GRAVITY, 0.0, "F4's gravity");
    }

    @Test
    void aBiggerDropNeverFliesLess() {
        int last = 0;
        for (int d = 1; d <= 6; d++) {
            assertTrue(BoatEnvelope.flight(d) >= last, d + " blocks: the envelope grows with the drop");
            last = BoatEnvelope.flight(d);
        }
    }
}
