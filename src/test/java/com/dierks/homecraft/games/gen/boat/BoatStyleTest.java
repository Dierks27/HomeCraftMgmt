package com.dierks.homecraft.games.gen.boat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mountain Run v2's style from the seed (MOUNTAIN-V2-SPEC §5.1): half the seeds a Winding Road, half a
 * Slalom, the same answer everywhere that asks (the planner, the proof, the copy).
 */
class BoatStyleTest {

    @Test
    void halfTheSeedsAreSlaloms() {
        int slalom = 0;
        int n = 100_000;
        for (long seed = 0; seed < n; seed++) {
            slalom += BoatStyle.of(seed * 0x9E3779B97F4A7C15L + 7) == BoatStyle.SLALOM ? 1 : 0;
        }
        double share = slalom / (double) n;
        assertTrue(Math.abs(share - 0.5) <= 0.01, "50 +- 1% of 100k seeds are slaloms: " + share);
        int small = 0;
        for (long seed = 0; seed < 10_000; seed++) {
            small += BoatStyle.of(seed) == BoatStyle.SLALOM ? 1 : 0;
        }
        assertTrue(Math.abs(small / 10_000.0 - 0.5) <= 0.02, "and so are small consecutive seeds: " + small);
    }

    @Test
    void theStyleIsAPureFunctionOfTheSeed() {
        // pinned: a change here moves every layout's style (an ALGO bump)
        assertEquals(BoatStyle.SLALOM, BoatStyle.of(1), "seed 1");
        assertEquals(BoatStyle.ROAD, BoatStyle.of(2), "seed 2");
        assertEquals(BoatStyle.SLALOM, BoatStyle.of(3), "seed 3");
        assertEquals(BoatStyle.ROAD, BoatStyle.of(4), "seed 4");
        assertEquals(0x5EED57E1E00DL, BoatStyle.SALT, "the salt the style is mixed with");
        for (long seed = -50; seed < 50; seed++) {
            assertEquals(BoatStyle.of(seed), BoatStyle.of(seed), seed + ": the same every time");
        }
    }

    @Test
    void theProofReadsTheSameStyle() {
        for (long seed = 0; seed < 2_000; seed++) {
            long s = seed * 0x2545F4914F6CDD1DL;
            assertEquals(BoatStyle.of(s) == BoatStyle.SLALOM, MountainValidator.slalom(s),
                    s + ": MountainValidator.slalom delegates to BoatStyle.of");
        }
    }

    @Test
    void theStyleWordsAreReadAndNamed() {
        assertEquals(BoatStyle.ROAD, BoatStyle.byWord("road"), "road");
        assertEquals(BoatStyle.SLALOM, BoatStyle.byWord("SLALOM"), "any case");
        assertEquals(BoatStyle.ROAD, BoatStyle.byWord(" Road "), "trimmed");
        assertNull(BoatStyle.byWord("random"), "random is no style: the seed picks");
        assertNull(BoatStyle.byWord(null), "nothing is no style");
        assertNull(BoatStyle.byWord("downhill"), "an unknown word is no style");
        assertEquals("road", BoatStyle.ROAD.id(), "the road's id");
        assertEquals("slalom", BoatStyle.SLALOM.id(), "the slalom's id");
        assertEquals("Winding Road", BoatStyle.ROAD.title(), "the road's name");
        assertEquals("Slalom", BoatStyle.SLALOM.title(), "the slalom's name");
    }
}
