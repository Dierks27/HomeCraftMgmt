package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.Game;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The Games screen's plain logic: how tiles sort into tabs and pages, and what a player on a
 * break (or without the permission) sees in place of the games of chance (spec §8.1, R1.16).
 */
class GamesMenuTest {

    private static GamesMenu.Tile tile(Game.Tab tab, int rank, int order) {
        return new GamesMenu.Tile(tab, rank, order, null, null);
    }

    @Test
    void theAllTabSortsByTabThenCatalogOrderThenEachGamesOwnOrder() {
        GamesMenu.Tile sweeper = tile(Game.Tab.CABINETS, 5, 0);
        GamesMenu.Tile scratch = tile(Game.Tab.LUCK, GamesMenu.LINK_RANK, 0);
        GamesMenu.Tile slots = tile(Game.Tab.LUCK, 0, 0);
        GamesMenu.Tile courseB = tile(Game.Tab.COURSES, 13, 2);
        GamesMenu.Tile courseA = tile(Game.Tab.COURSES, 13, 1);
        GamesMenu.Tile merge = tile(Game.Tab.CABINETS, 6, 0);
        GamesMenu.Tile golf = tile(Game.Tab.GOLF, 14, 0);
        List<GamesMenu.Tile> in = List.of(golf, sweeper, scratch, slots, courseB, courseA, merge);

        assertEquals(List.of(slots, scratch, sweeper, merge, courseA, courseB, golf), GamesMenu.arrange(in, null),
                "All shows Luck, Cabinets, Courses, Golf in that order; inside a tab the catalog order, "
                        + "the Arcade's own links (Scratch Ticket, crates) last, then each game's tile order");
    }

    @Test
    void aTabShowsOnlyItsOwnTilesAndTheInputIsLeftAlone() {
        List<GamesMenu.Tile> in = new ArrayList<>(List.of(tile(Game.Tab.CABINETS, 6, 0), tile(Game.Tab.LUCK, 0, 0),
                tile(Game.Tab.CABINETS, 5, 0)));
        in.add(null);
        List<GamesMenu.Tile> before = new ArrayList<>(in);

        List<GamesMenu.Tile> cabinets = GamesMenu.arrange(in, Game.Tab.CABINETS);

        assertEquals(2, cabinets.size(), "only the two cabinet tiles belong on the Cabinets tab");
        assertEquals(5, cabinets.get(0).rank(), "catalog order inside the tab");
        assertEquals(before, in, "arranging must not reorder the caller's list");
        assertEquals(0, GamesMenu.arrange(in, Game.Tab.GOLF).size(), "an empty tab is empty, not an error");
    }

    @Test
    void pagesHoldThirtySixTilesAndThereIsAlwaysAtLeastOne() {
        assertEquals(1, GamesMenu.pages(0), "an empty screen is still one page");
        assertEquals(1, GamesMenu.pages(36), "rows 1-4 hold exactly 36");
        assertEquals(2, GamesMenu.pages(37), "the 37th tile starts page two");
        assertEquals(3, GamesMenu.pages(73), "73 tiles need three pages");
    }

    @Test
    void aPageOutOfRangeIsClampedAndSlicedToWhatExists() {
        List<Integer> forty = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            forty.add(i);
        }
        assertEquals(1, GamesMenu.clampPage(5, 40), "past the end goes to the last page");
        assertEquals(0, GamesMenu.clampPage(-3, 40), "before the start goes to the first page");
        assertEquals(36, GamesMenu.slice(forty, 0).size(), "the first page is full");
        assertEquals(List.of(36, 37, 38, 39), GamesMenu.slice(forty, 1), "the second page has the last four");
        assertEquals(List.of(36, 37, 38, 39), GamesMenu.slice(forty, 9), "a stale page number still shows tiles");
        assertEquals(List.of(), GamesMenu.slice(List.of(), 0), "nothing to show is an empty page");
    }

    @Test
    void theLuckTabFollowsThePermissionThenTheBreak() {
        long now = 1_000_000L;
        assertSame(GamesMenu.Luck.HIDDEN, GamesMenu.luck(false, true, now + 5, now),
                "without hcm.games.chance there is nothing at all, paused or not");
        assertSame(GamesMenu.Luck.HIDDEN, GamesMenu.luck(false, false, 0, now),
                "the permission is checked before the break is even read");
        assertSame(GamesMenu.Luck.CLOSED, GamesMenu.luck(true, false, 0, now),
                "a break that can't be read closes games of chance (fails closed)");
        assertSame(GamesMenu.Luck.PAUSED, GamesMenu.luck(true, true, now + 1, now),
                "a pause that hasn't ended replaces the games of chance with one tile");
        assertSame(GamesMenu.Luck.OPEN, GamesMenu.luck(true, true, now, now),
                "a pause is over at the exact moment it ends");
        assertSame(GamesMenu.Luck.OPEN, GamesMenu.luck(true, true, 0, now), "no pause, no break: open");
    }

    @Test
    void eachTabIsCountedAndEmptyTabsCountZero() {
        Map<Game.Tab, Integer> counts = GamesMenu.counts(List.of(tile(Game.Tab.LUCK, 0, 0),
                tile(Game.Tab.LUCK, 1, 0), tile(Game.Tab.GOLF, 14, 0)));
        assertEquals(2, counts.get(Game.Tab.LUCK), "two Luck tiles");
        assertEquals(1, counts.get(Game.Tab.GOLF), "one Golf tile");
        assertEquals(0, counts.get(Game.Tab.CABINETS), "a tab with nothing reads 0, never null");
        assertEquals(0, counts.get(Game.Tab.COURSES), "a tab with nothing reads 0, never null");
    }
}
