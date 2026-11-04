package com.dierks.homecraft.hunt;

import com.dierks.homecraft.mini.Loot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntMathTest {

    @Test
    void theCompassMatchesTheGame() {
        // North is -Z, east is +X.
        assertEquals("north", HuntMath.direction(0, 0, 0, -50));
        assertEquals("south", HuntMath.direction(0, 0, 0, 50));
        assertEquals("east", HuntMath.direction(0, 0, 50, 0));
        assertEquals("west", HuntMath.direction(0, 0, -50, 0));
        assertEquals("northeast", HuntMath.direction(0, 0, 40, -40));
        assertEquals("southwest", HuntMath.direction(10, 10, -30, 50));
        assertEquals("northwest", HuntMath.direction(0, 0, -40, -40));
        assertEquals("southeast", HuntMath.direction(0, 0, 40, 40));
        assertEquals("north", HuntMath.direction(0, 0, 10, -60), "10° off north is still north");
    }

    @Test
    void hintsComeDueAsTheTimeRunsDown() {
        List<Loot.Hint> hints = Loot.Hint.defaults();
        long start = 1_000_000;
        long end = start + 300_000; // five minutes
        assertEquals(1, HuntMath.stagesDue(hints, start, end, start), "the opening line is due at once");
        assertEquals(1, HuntMath.stagesDue(hints, start, end, start + 89_000));
        assertEquals(2, HuntMath.stagesDue(hints, start, end, start + 90_000), "30%");
        assertEquals(3, HuntMath.stagesDue(hints, start, end, start + 180_000), "60%");
        assertEquals(4, HuntMath.stagesDue(hints, start, end, start + 255_000), "85% — the beam");
        assertEquals(4, HuntMath.stagesDue(hints, start, end, end + 60_000));
        assertEquals(0, HuntMath.stagesDue(List.of(), start, end, end));
    }

    @Test
    void theShippedRadarBandsHaveNoBurningSoHotIsTheClosest() {
        HuntMath.Bands shipped = HuntMath.Bands.SHIPPED;
        assertEquals(new HuntMath.Bands(0, 32, 64), shipped);
        assertEquals(HuntMath.Band.HOT, HuntMath.band(0, shipped), "standing on it is Hot, never Burning!");
        assertEquals(HuntMath.Band.HOT, HuntMath.band(5, shipped));
        assertEquals(HuntMath.Band.HOT, HuntMath.band(31.9, shipped));
        assertEquals(HuntMath.Band.WARM, HuntMath.band(32, shipped));
        assertEquals(HuntMath.Band.WARM, HuntMath.band(63.9, shipped));
        assertEquals(HuntMath.Band.COLD, HuntMath.band(64, shipped));
        assertEquals(HuntMath.Band.COLD, HuntMath.band(96, shipped), "where a wild Mini lands at the furthest");
        assertEquals(HuntMath.Band.NONE, HuntMath.band(-1, shipped));
    }

    @Test
    void theOldBandsAreStillThereWhenConfigured() {
        HuntMath.Bands old = new HuntMath.Bands(12, 32, 64);
        assertEquals(HuntMath.Band.BURNING, HuntMath.band(5, old));
        assertEquals(HuntMath.Band.BURNING, HuntMath.band(11.9, old));
        assertEquals(HuntMath.Band.HOT, HuntMath.band(12, old));
        assertEquals(HuntMath.Band.HOT, HuntMath.band(31.9, old));
        assertEquals(HuntMath.Band.WARM, HuntMath.band(32, old));
        assertEquals(HuntMath.Band.COLD, HuntMath.band(64, old));
        assertEquals(HuntMath.Band.NONE, HuntMath.band(-1, old));
    }

    @Test
    void aChargeReadsForItsSecondsThenIsSpent() {
        long locked = 1_000_000;
        assertTrue(HuntMath.charged(locked, 60, locked), "reads the moment it locks on");
        assertTrue(HuntMath.charged(locked, 60, locked + 59_999));
        assertFalse(HuntMath.charged(locked, 60, locked + 60_000), "spent at the minute");
        assertFalse(HuntMath.charged(locked, 60, locked + 300_000));
        assertTrue(HuntMath.charged(locked, 0, locked + 3_600_000), "0 = until the Mini is caught or gets away");
    }

    @Test
    void readingsComeDueEveryIntervalWithHalfASecondOfSlack() {
        long last = 1_000_000;
        assertFalse(HuntMath.readingDue(last, 6, last));
        assertFalse(HuntMath.readingDue(last, 6, last + 5_000), "not after five seconds");
        assertFalse(HuntMath.readingDue(last, 6, last + 5_499));
        assertTrue(HuntMath.readingDue(last, 6, last + 5_500), "a once-a-second check a hair early still reads");
        assertTrue(HuntMath.readingDue(last, 6, last + 6_000));
        assertFalse(HuntMath.readingDue(last, 2, last + 1_000), "the old two seconds");
        assertTrue(HuntMath.readingDue(last, 2, last + 2_000));
    }

    @Test
    void biomesReadAsWordsAndTheArticleAgrees() {
        assertEquals("Dark Forest", HuntMath.prettyBiome("minecraft:dark_forest"));
        assertEquals("wild", HuntMath.prettyBiome(null));
        String text = "Hint: the {rarity} Mini is in a {biome} biome.";
        assertEquals("Hint: the Rare Mini is in an Old Growth Birch Forest biome.",
                HuntMath.fill(text, "Rare", "Kaden", 5, "Old Growth Birch Forest", "north"));
        assertEquals("Hint: the Rare Mini is in a Plains biome.",
                HuntMath.fill(text, "Rare", "Kaden", 5, "Plains", "north"));
        assertEquals("A Rare Mini appeared near Kaden! You have 5 minutes to find it.",
                HuntMath.fill(Loot.Hint.defaults().get(0).text(), "Rare", "Kaden", 5, "Plains", "north"));
    }

    @Test
    void noShippedHintGivesAwayCoordinatesOrDistance() {
        for (Loot.Hint h : Loot.Hint.defaults()) {
            String t = h.text().toLowerCase();
            assertEquals(false, t.contains("block") || t.matches(".*\\d.*"), "hint gives away too much: " + h.text());
        }
    }
}
