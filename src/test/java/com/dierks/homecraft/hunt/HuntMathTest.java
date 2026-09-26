package com.dierks.homecraft.hunt;

import com.dierks.homecraft.mini.Loot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void theRadarBands() {
        assertEquals(HuntMath.Band.BURNING, HuntMath.band(5));
        assertEquals(HuntMath.Band.HOT, HuntMath.band(12));
        assertEquals(HuntMath.Band.HOT, HuntMath.band(31.9));
        assertEquals(HuntMath.Band.WARM, HuntMath.band(32));
        assertEquals(HuntMath.Band.COLD, HuntMath.band(64));
        assertEquals(HuntMath.Band.NONE, HuntMath.band(-1));
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
