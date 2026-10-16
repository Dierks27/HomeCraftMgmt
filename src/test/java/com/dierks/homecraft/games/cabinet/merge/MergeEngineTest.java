package com.dierks.homecraft.games.cabinet.merge;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ore Merge's rules, with no server. Levels stand for ores: 1 coal (2), 2 copper (4), 3 iron (8) …
 * 11 the dragon egg (2048).
 *
 * <p>Pinned here: a slide closes up gaps and merges equal neighbours once each, from the edge the
 * tiles move toward (the classic {@code [2,2,2,2] → [4,4]}, and no double merges); dragon eggs
 * don't merge; the four directions read the right lines; a move that changes nothing spawns
 * nothing; each real move spawns exactly one coal or copper on an empty square, about 9 in 10
 * coal; the same seed and moves give the same game; the score is the sum of merges; and the game
 * is over exactly when no move is left.
 */
class MergeEngineTest {

    @Test
    void fourOfAKindMergeIntoTwoNotOne() {
        MergeEngine.Slide s = MergeEngine.slide(new int[]{1, 1, 1, 1});
        assertArrayEquals(new int[]{2, 2, 0, 0}, s.out(), "[2,2,2,2] slides to [4,4], never [8]");
        assertEquals(8, s.gained(), "two merges of 4 each score 8");
        assertArrayEquals(new boolean[]{true, true, false, false}, s.merged(), "both new tiles are marked as merged");
    }

    @Test
    void aTileMergesOnlyOncePerMove() {
        assertArrayEquals(new int[]{2, 2, 0, 0}, MergeEngine.slide(new int[]{1, 1, 2, 0}).out(),
                "[2,2,4] gives [4,4]: the new 4 doesn't merge again with the old one");
        assertArrayEquals(new int[]{3, 2, 0, 0}, MergeEngine.slide(new int[]{2, 2, 2, 0}).out(),
                "[4,4,4] gives [8,4]: the pair nearest the edge merges first");
        assertArrayEquals(new int[]{2, 1, 0, 0}, MergeEngine.slide(new int[]{1, 1, 1, 0}).out(),
                "[2,2,2] gives [4,2]");
        int[] first = MergeEngine.slide(new int[]{2, 1, 1, 0}).out();
        assertArrayEquals(new int[]{2, 2, 0, 0}, first, "[4,2,2] gives [4,4], not [8], in one move");
        assertArrayEquals(new int[]{3, 0, 0, 0}, MergeEngine.slide(first).out(), "and [8] only on the next move");
    }

    @Test
    void gapsCloseAndSeparatedTilesStillMeet() {
        MergeEngine.Slide s = MergeEngine.slide(new int[]{1, 0, 1, 0});
        assertArrayEquals(new int[]{2, 0, 0, 0}, s.out(), "two coal with a gap between them still merge");
        MergeEngine.Slide moved = MergeEngine.slide(new int[]{0, 0, 0, 3});
        assertArrayEquals(new int[]{3, 0, 0, 0}, moved.out(), "a lone tile slides all the way");
        assertTrue(moved.changed(), "a slide with no merge is still a change");
        assertEquals(0, moved.gained(), "sliding alone scores nothing");
    }

    @Test
    void aLineThatCantMoveIsUnchanged() {
        MergeEngine.Slide s = MergeEngine.slide(new int[]{1, 2, 1, 2});
        assertFalse(s.changed(), "alternating tiles with no gap can't move");
        assertArrayEquals(new int[]{1, 2, 1, 2}, s.out(), "and stay where they are");
    }

    @Test
    void dragonEggsAreTheTopAndDontMerge() {
        MergeEngine.Slide s = MergeEngine.slide(new int[]{11, 11, 0, 0});
        assertFalse(s.changed(), "two dragon eggs side by side stay two dragon eggs");
        assertArrayEquals(new int[]{11, 0, 0, 0}, MergeEngine.slide(new int[]{10, 10, 0, 0}).out(),
                "two nether stars make the dragon egg");
        assertEquals(2048, MergeEngine.value(MergeEngine.TOP), "the dragon egg is worth 2048");
    }

    @Test
    void eachDirectionReadsItsLinesFromTheEdgeTheTilesMoveToward() {
        assertArrayEquals(new int[]{0, 1, 2, 3}, MergeEngine.cells(MergeEngine.Dir.LEFT, 0), "left: row 0 from the left");
        assertArrayEquals(new int[]{3, 2, 1, 0}, MergeEngine.cells(MergeEngine.Dir.RIGHT, 0), "right: row 0 from the right");
        assertArrayEquals(new int[]{1, 5, 9, 13}, MergeEngine.cells(MergeEngine.Dir.UP, 1), "up: column 1 from the top");
        assertArrayEquals(new int[]{14, 10, 6, 2}, MergeEngine.cells(MergeEngine.Dir.DOWN, 2), "down: column 2 from the bottom");
    }

    @Test
    void aMoveSlidesTheWholeGridAndSpawnsOneTileOnAnEmptySquare() {
        int[] start = {
                1, 1, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 2};
        MergeEngine grid = new MergeEngine(start, 5);
        assertTrue(grid.move(MergeEngine.Dir.RIGHT), "sliding right changes the grid");
        assertEquals(2, grid.level(3), "the two coal met at the right edge as copper");
        assertTrue(grid.merged(3), "and shimmer as just made");
        assertEquals(2, grid.level(15), "the copper in the corner stayed put");
        assertEquals(4, grid.score(), "one merge into copper scores 4");
        int spawned = grid.spawned();
        assertTrue(spawned >= 0 && start[spawned] == 0 && spawned != 3, "the new tile landed on a square that was empty");
        assertTrue(grid.level(spawned) == 1 || grid.level(spawned) == 2, "a new tile is coal or copper");
        assertEquals(3, tiles(grid), "two tiles were left after the merge, plus exactly one new one");
        assertEquals(1, grid.moves(), "one move counted");
    }

    @Test
    void aMoveThatChangesNothingSpawnsNothing() {
        int[] start = {
                1, 2, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0};
        MergeEngine grid = new MergeEngine(start, 5);
        assertFalse(grid.move(MergeEngine.Dir.LEFT), "everything is already against the left edge");
        assertFalse(grid.move(MergeEngine.Dir.UP), "and against the top");
        assertEquals(2, tiles(grid), "no tile appeared for a move that did nothing");
        assertEquals(0, grid.moves(), "and it wasn't counted");
    }

    @Test
    void theScoreIsTheSumOfEveryMergeAndTheBiggestTileIsTracked() {
        int[] start = {
                3, 3, 1, 1,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0};
        MergeEngine grid = new MergeEngine(start, 9);
        grid.move(MergeEngine.Dir.LEFT);
        assertEquals(16 + 4, grid.score(), "iron + iron (16) and coal + coal (4)");
        assertEquals(4, grid.biggest(), "redstone (16) is now the biggest ore");
        assertEquals("Redstone", MergeEngine.name(grid.biggest()), "and has a name");
    }

    @Test
    void newTilesAreCoalNineTimesInTenFromTheSeed() {
        int copper = 0;
        int total = 0;
        for (long seed = 0; seed < 5_000; seed++) {
            MergeEngine grid = new MergeEngine(seed);
            for (int c = 0; c < MergeEngine.CELLS; c++) {
                if (grid.level(c) > 0) {
                    total++;
                    assertTrue(grid.level(c) <= 2, "a starting tile is coal or copper");
                    if (grid.level(c) == 2) {
                        copper++;
                    }
                }
            }
        }
        assertEquals(10_000, total, "a new game starts with exactly two tiles");
        double share = copper / (double) total;
        assertTrue(share > 0.085 && share < 0.115, "about one tile in ten is copper, got " + share);
    }

    @Test
    void theSameSeedAndMovesGiveTheSameGame() {
        MergeEngine.Dir[] dirs = MergeEngine.Dir.values();
        for (long seed = 0; seed < 30; seed++) {
            MergeEngine a = new MergeEngine(seed);
            MergeEngine b = new MergeEngine(seed);
            SplittableRandom moves = new SplittableRandom(seed + 1000);
            for (int i = 0; i < 300 && !a.over(); i++) {
                MergeEngine.Dir d = dirs[moves.nextInt(4)];
                assertEquals(a.move(d), b.move(d), "the same move does the same thing (seed " + seed + ")");
            }
            assertEquals(board(a), board(b), "the same seed and moves give the same grid (seed " + seed + ")");
            assertEquals(a.score(), b.score(), "and the same score");
        }
        List<String> starts = new ArrayList<>();
        for (long seed = 0; seed < 20; seed++) {
            starts.add(board(new MergeEngine(seed)).toString());
        }
        assertTrue(starts.stream().distinct().count() > 5, "different seeds deal different games");
    }

    @Test
    void theGameIsOverExactlyWhenNoMoveIsLeft() {
        int[] stuck = {
                1, 2, 1, 2,
                2, 1, 2, 1,
                1, 2, 1, 2,
                2, 1, 2, 1};
        MergeEngine over = new MergeEngine(stuck, 1);
        assertFalse(over.canMove(), "a full grid with no equal neighbours has no move");
        assertTrue(over.over(), "so the game is over");
        for (MergeEngine.Dir d : MergeEngine.Dir.values()) {
            assertFalse(over.move(d), "no direction does anything once it is over");
        }

        int[] onePair = stuck.clone();
        onePair[1] = 1;
        assertTrue(new MergeEngine(onePair, 1).canMove(), "one equal pair side by side is still a move");
        int[] columnPair = stuck.clone();
        columnPair[4] = 1;
        assertTrue(new MergeEngine(columnPair, 1).canMove(), "and so is one equal pair one above the other");
        int[] gap = stuck.clone();
        gap[15] = 0;
        assertTrue(new MergeEngine(gap, 1).canMove(), "an empty square is always a move");

        int[] eggs = stuck.clone();
        eggs[0] = 11;
        eggs[1] = 11;
        assertFalse(new MergeEngine(eggs, 1).canMove(), "two dragon eggs side by side aren't a move: they don't merge");
    }

    @Test
    void randomPlayAlwaysEndsWithAFullGridAndNoMoveLeft() {
        MergeEngine.Dir[] dirs = MergeEngine.Dir.values();
        SplittableRandom moves = new SplittableRandom(42);
        MergeEngine grid = new MergeEngine(42);
        int guard = 0;
        while (!grid.over() && guard++ < 10_000) {
            grid.move(dirs[moves.nextInt(4)]);
        }
        assertTrue(grid.over(), "random play always reaches the end of a game");
        assertFalse(grid.canMove(), "and the end means no move is left");
        assertFalse(board(grid).contains(0), "the grid is full at the end");
        assertTrue(grid.score() > 0, "a whole game scores something");
    }

    @Test
    void valuesNamesAndMilestones() {
        assertEquals(2, MergeEngine.value(1), "coal is worth 2");
        assertEquals(256, MergeEngine.value(MergeEngine.DIAMOND), "the diamond is worth 256, the daily goal");
        assertEquals(0, MergeEngine.value(0), "an empty square is worth nothing");
        assertEquals("Diamond", MergeEngine.name(MergeEngine.levelOf(256)), "256 is a diamond");
        assertEquals("Nether star", MergeEngine.name(MergeEngine.levelOf(1024)), "1024 is a nether star");
        assertEquals(-1, MergeEngine.levelOf(300), "no ore is worth 300");

        OreMergeSettings s = OreMergeSettings.defaults();
        assertEquals(List.of(256, 512, 1024), s.milestonesFor(Scores.CLASSIC), "the milestones are tile values");
        assertEquals(List.of(), s.milestonesFor(Scores.daily(20_000)), "the daily boards have none");
        assertEquals(List.of(1, 2), CabinetGame.milestonesReached(s.milestonesFor(Scores.CLASSIC),
                MergeEngine.value(9), false), "making netherite (512) reaches bronze and silver");
        assertEquals(List.of(), CabinetGame.milestonesReached(s.milestonesFor(Scores.CLASSIC), 128, false),
                "an emerald reaches none, however high the score");
    }

    @Test
    void theShippedBlockParsesToTheDefaultsWithNoWarning() {
        List<String> warns = new ArrayList<>();
        OreMergeSettings parsed = OreMergeSettings.parse(new GamesConfig.Node("games.ore_merge", Map.of(
                "enabled", true, "milestone_reward", TokenBalance.CABINET_MILESTONE, "daily_reward",
                TokenBalance.CABINET_DAILY, "daily_cap", TokenBalance.CABINET_DAILY_CAP,
                "milestones", List.of(256, 512, 1024)), warns::add), OreMergeSettings.defaults());
        assertEquals(OreMergeSettings.defaults(), parsed, "the shipped values are the defaults");
        assertEquals(List.of(), warns, "and read quietly");
    }

    @Test
    void biggestNeverShrinks() {
        MergeEngine grid = new MergeEngine(new int[]{
                5, 5, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0}, 3);
        grid.move(MergeEngine.Dir.LEFT);
        assertEquals(6, grid.biggest(), "two lapis made gold");
        int before = grid.biggest();
        grid.move(MergeEngine.Dir.RIGHT);
        assertNotEquals(-1, grid.spawned(), "a real move spawned");
        assertTrue(grid.biggest() >= before, "the biggest ore made never goes back down");
    }

    private static int tiles(MergeEngine grid) {
        int n = 0;
        for (int c = 0; c < MergeEngine.CELLS; c++) {
            if (grid.level(c) > 0) {
                n++;
            }
        }
        return n;
    }

    private static List<Integer> board(MergeEngine grid) {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < MergeEngine.CELLS; c++) {
            out.add(grid.level(c));
        }
        return out;
    }
}
