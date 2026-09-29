package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.FeedWriter;
import com.dierks.homecraft.games.gen.api.GenBoards;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the screens read from a game's own published entries: the give-back the guide shows and
 * the boards the high-score list shows — the same numbers the game plays with and the website
 * publishes. A daily course's board changes every day, so it isn't among the listed boards; the
 * week's Star Chart is, in stars, higher is better (GEN-SPEC §5.2).
 */
class ScreensTest {

    @Test
    void theGuideShowsTheLowestComputedGiveBack() {
        Screens.Published p = new Screens.Published();
        Map<Integer, Double> rtp = new LinkedHashMap<>();
        rtp.put(1, 0.9);
        rtp.put(2, 0.895);
        rtp.put(5, 0.8976);
        p.chance("ore_slots", "Ore Slots", List.of(1, 2, 5), rtp, 50, List.of(), null, Map.of());
        assertEquals(0.895, p.lowestGiveBack(), 1e-12,
                "the headline is the lowest stake's computed value: no stake gives back less");
    }

    @Test
    void aGameThatPublishesNoOddsHasNoGiveBack() {
        Screens.Published p = new Screens.Published();
        assertTrue(Double.isNaN(p.lowestGiveBack()), "nothing published: nothing shown, never a made-up number");
        Map<Integer, Double> junk = new HashMap<>();
        junk.put(1, null);
        junk.put(2, Double.NaN);
        junk.put(3, 0.0);
        p.chance("x", "X", List.of(1, 2, 3), junk, null, List.of(), null, Map.of());
        assertTrue(Double.isNaN(p.lowestGiveBack()), "missing, NaN and zero values are not odds");
    }

    @Test
    void boardsComeWithHowTheyRead() {
        Screens.Published p = new Screens.Published();
        p.cabinet("creeper_sweeper", "Creeper Sweeper", "normal", "ms", true, 83_000L, null);
        p.cabinet("x", "X", " ", "points", false, null, null);
        p.course("river", "River Run", "boat", "medium", null, null, null);
        p.golf("meadow", "Meadow Links", 9, 27, null, null, null);

        assertEquals(List.of(
                new Screens.Published.Board("normal", "Creeper Sweeper", "ms", true),
                new Screens.Published.Board("course:river", "River Run", "ms", true),
                new Screens.Published.Board("golf:meadow", "Meadow Links", "strokes", true)), p.boards(),
                "a cabinet keeps its board and unit, a course is a time board, golf is strokes; a blank board is skipped");
        assertEquals("ms", p.cabinetUnit(), "the cabinet's unit, for its other boards too");
    }

    @Test
    void worldGamesHaveNoCabinetUnit() {
        Screens.Published p = new Screens.Published();
        p.course("river", "River Run", "boat", "medium", null, null, null);
        assertNull(p.cabinetUnit(), "a course list is not a cabinet");
        FeedWriter w = p;
        assertEquals(false, w.showNames(), "the screens never ask the feed for names; they read them in game");
    }

    @Test
    void aDailyCourseIsNotListedButTheStarChartIs() {
        Screens.Published p = new Screens.Published();
        p.course("fresh_parkour_easy", "Easy Parkour", "parkour", "easy", 40_000L, 1L, null,
                new FeedWriter.Daily("2026-09-29", 1L, 45_000L, 70_000L));
        p.golf("fresh_tiny_golf", "Tiny Golf", 3, 8, 8, 1L, null, new FeedWriter.Daily("2026-09-29", 1L, null, null));
        p.course("river", "River Run", "boat", "medium", null, null, null, null);
        p.starChart("2026-09-28", 14L, null);
        p.starChart("not a week", 3L, null);
        assertEquals(List.of(
                new Screens.Published.Board("course:river", "River Run", "ms", true),
                new Screens.Published.Board(GenBoards.week(20_724), "Star Chart", "stars", false)), p.boards(),
                "a hand-built course still lists; a daily one doesn't; the chart is the week's board; junk is skipped");
        assertNull(p.cabinetUnit(), "the chart is not a cabinet board");
    }
}
