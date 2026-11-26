package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.MenuStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.59.0, the face lift (the owner: "the menus for the Games menu when you click on the console are a bit messy again
 * from the changes"): where the Games screen draws its tiles, and that with every shipped game open at once its tabs
 * still fit the header row and its tiles their grid.
 */
class GamesMenuLayoutTest {

    private static GamesMenu.Tile tile(Game.Tab tab, int rank) {
        return new GamesMenu.Tile(tab, rank, 0, null, null);
    }

    private static List<GamesMenu.Tile> tiles(Game.Tab tab, int n, int rankFrom) {
        List<GamesMenu.Tile> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(tile(tab, rankFrom + i));
        }
        return out;
    }

    @Test
    void onAllEachTabStartsARowOfItsOwn() {
        List<GamesMenu.Tile> in = new ArrayList<>();
        in.addAll(tiles(Game.Tab.LUCK, 1, 0));       // one crate
        in.addAll(tiles(Game.Tab.CABINETS, 8, 10));  // the eight cabinets
        in.addAll(tiles(Game.Tab.TOGETHER, 2, 30));  // the Clubhouse, the Floor Is Lava
        List<GamesMenu.Tile> arranged = GamesMenu.arrange(in, null);

        List<GamesMenu.Spot> spots = GamesMenu.place(arranged, true);

        assertEquals(new GamesMenu.Spot(0, 9), spots.get(0), "Luck's crate opens row 1");
        assertEquals(new GamesMenu.Spot(0, 18), spots.get(1), "the cabinets start row 2, not beside the crate");
        assertEquals(new GamesMenu.Spot(0, 25), spots.get(8), "and fill it");
        assertEquals(new GamesMenu.Spot(0, 27), spots.get(9), "Together starts row 3");
        assertEquals(1, GamesMenu.pages(spots));

        List<GamesMenu.Spot> packed = GamesMenu.place(GamesMenu.arrange(in, Game.Tab.CABINETS), false);
        assertEquals(9, packed.get(0).slot(), "one tab alone is packed from slot 9");
        assertEquals(16, packed.get(7).slot());
    }

    @Test
    void aTabThatFitsOnOnePageIsNeverSplitOverTwo() {
        List<GamesMenu.Tile> in = new ArrayList<>();
        in.addAll(tiles(Game.Tab.LUCK, 30, 0));      // rows 1-4, the last one with 3 left over
        in.addAll(tiles(Game.Tab.CABINETS, 8, 40));  // 8 > 6 left on page 1: page 2 instead
        List<GamesMenu.Spot> spots = GamesMenu.place(GamesMenu.arrange(in, null), true);

        assertEquals(new GamesMenu.Spot(0, 38), spots.get(29), "Luck ends on row 4");
        assertEquals(new GamesMenu.Spot(1, 9), spots.get(30), "the cabinets start page 2 rather than split");
        assertEquals(2, GamesMenu.pages(spots));
        assertEquals(1, GamesMenu.pages(List.of()), "nothing at all is still one page");
    }

    @Test
    void aTabLongerThanAPageStillPagesWithoutLosingATile() {
        List<GamesMenu.Tile> in = tiles(Game.Tab.COURSES, 80, 0);
        List<GamesMenu.Spot> spots = GamesMenu.place(GamesMenu.arrange(in, Game.Tab.COURSES), false);
        assertEquals(3, GamesMenu.pages(spots));
        Set<GamesMenu.Spot> seen = new HashSet<>(spots);
        assertEquals(80, seen.size(), "every tile has a place of its own");
    }

    /**
     * Every game in the catalog, and what it puts on the Games screen with every shipped game open: the tab of each of
     * its tiles. A new game must be placed here (the test fails until it is), so a game can't be added to the screen
     * without someone checking it still fits.
     */
    private static Map<String, List<Game.Tab>> shippedTiles() {
        Map<String, List<Game.Tab>> m = new HashMap<>();
        for (String luck : List.of("ore_slots", "twenty_one", "wheel", "higher_lower", "coin_flip", "nether_slots",
                "motherlode", "farm_slots", "deep_mine", "mob_slots", "mob_races", "sportsbook", "holdem", "bingo",
                "video_poker", "creeper_fuse")) {
            m.put(luck, List.of(Game.Tab.LUCK));
        }
        for (String cab : List.of("creeper_sweeper", "ore_merge", "snake", "mini_match", "simon_says", "whack_a_zombie",
                "connect_four", "tic_tac_toe")) {
            m.put(cab, List.of(Game.Tab.CABINETS));
        }
        m.put("trials", List.of());                                       // hand-built courses: none ship
        m.put("golf", List.of());                                         // hand-built golf: none ship
        m.put("cup", List.of());                                          // no tile of its own
        m.put("cpu", List.of());                                          // a setting, on row 5
        m.put(Slots.DAILY, List.of(Game.Tab.COURSES, Game.Tab.GOLF));     // the Fresh Courses tile, on both
        m.put("race_night", List.of(Game.Tab.TOGETHER));
        m.put("falling_floors", List.of(Game.Tab.TOGETHER));
        m.put("clubhouse", List.of(Game.Tab.TOGETHER, Game.Tab.TOGETHER)); // and Songs, while inside
        m.put("hub", List.of(Game.Tab.TOGETHER));
        m.put("floor_is_lava", List.of(Game.Tab.TOGETHER));
        m.put("kid_races", List.of(Game.Tab.LITTLE));
        m.put("kid_bingo", List.of(Game.Tab.LITTLE));
        return m;
    }

    private static List<GamesMenu.Tile> everyShippedTile() {
        Map<String, List<Game.Tab>> places = shippedTiles();
        List<GamesMenu.Tile> out = new ArrayList<>();
        int rank = 0;
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            List<Game.Tab> tabs = places.get(spec.id());
            assertTrue(tabs != null, spec.id() + " is a game in the catalog: say here which tabs its tiles go on");
            int order = 0;
            for (Game.Tab t : tabs) {
                out.add(new GamesMenu.Tile(t, rank, order++, null, null));
            }
            rank++;
        }
        for (Slots.Def d : Slots.ALL) { // each fresh course, Courses or Golf by the game that runs it
            out.add(new GamesMenu.Tile(Slots.GAME_GOLF.equals(d.game()) ? Game.Tab.GOLF : Game.Tab.COURSES, GamesMenu.priority(d.id()), 100, 0, null, null));
        }
        out.add(tile(Game.Tab.LUCK, GamesMenu.LINK_RANK)); // the Scratch Ticket
        for (int c = 0; c < 3; c++) {                       // the crates (one ships; room for three)
            out.add(new GamesMenu.Tile(Game.Tab.LUCK, GamesMenu.LINK_RANK, 1 + c, null, null));
        }
        return out;
    }

    @Test
    void withEveryShippedGameOpenTheTabsFitTheHeaderRow() {
        assertEquals(Game.Tab.values().length + 1, GamesMenu.TAB_SLOTS.length, "a slot for All and one for each tab");
        Set<Integer> row0 = new LinkedHashSet<>(List.of(GamesMenu.BALANCE, GamesMenu.INVITE));
        for (int s : GamesMenu.TAB_SLOTS) {
            assertTrue(s >= 0 && s < 9, s + " is in row 0");
            assertTrue(row0.add(s), "slot " + s + " holds one thing");
        }
        assertEquals(GamesMenu.TAB_SLOTS[0], GamesMenu.tabSlot(null));
        Set<Integer> bySwitch = new HashSet<>();
        for (Game.Tab t : Game.Tab.values()) {
            assertTrue(bySwitch.add(GamesMenu.tabSlot(t)), t + " has a slot of its own");
            assertFalse(GamesMenu.tabName(t).isBlank(), t + " has a name");
            assertTrue(MenuStyle.visible(GamesMenu.tabName(t)) <= 12, t + "'s name fits a tile and the title");
        }
        assertTrue(GamesMenu.tabShown(Game.Tab.LITTLE, 1, null), "Little ones shows once it has a tile");
        assertFalse(GamesMenu.tabShown(Game.Tab.LITTLE, 0, null), "and not before");
        assertTrue(GamesMenu.tabShown(Game.Tab.LITTLE, 0, Game.Tab.LITTLE), "unless the viewer is on it");
        assertTrue(GamesMenu.tabShown(Game.Tab.COURSES, 0, null), "the kinds of game are always there");
    }

    @Test
    void withEveryShippedGameOpenEachTabFitsOnOnePageAndAllLosesNothing() {
        List<GamesMenu.Tile> all = everyShippedTile();
        for (Game.Tab t : Game.Tab.values()) {
            List<GamesMenu.Tile> one = GamesMenu.arrange(all, t);
            List<GamesMenu.Spot> spots = GamesMenu.place(one, false);
            assertEquals(1, GamesMenu.pages(spots), t + " has " + one.size() + " tiles: more than one page");
        }
        List<GamesMenu.Tile> arranged = GamesMenu.arrange(all, null);
        List<GamesMenu.Spot> spots = GamesMenu.place(arranged, true);
        assertEquals(arranged.size(), new HashSet<>(spots).size(), "every tile on All has a place of its own");
        Map<Integer, Game.Tab> rowTab = new HashMap<>();
        Map<Game.Tab, Integer> pageOf = new HashMap<>();
        for (int i = 0; i < spots.size(); i++) {
            GamesMenu.Spot s = spots.get(i);
            assertTrue(s.slot() >= 9 && s.slot() <= 44, "tiles stay in rows 1-4: " + s);
            Game.Tab t = arranged.get(i).tab();
            Game.Tab before = rowTab.putIfAbsent(s.page() * 10 + s.slot() / 9, t);
            assertTrue(before == null || before == t, "a row on All holds one tab: " + s);
            Integer p = pageOf.putIfAbsent(t, s.page());
            assertTrue(p == null || p == s.page(), t + " is on one page of All, not split");
        }
        assertTrue(GamesMenu.pages(spots) <= 3, "All stays a few pages: " + GamesMenu.pages(spots));
    }

    @Test
    void theBottomRowFollowsTheHouseStyle() {
        assertEquals(48, GamesMenu.GUIDE, "How the games work beside the way out");
        Set<Integer> row5 = new HashSet<>(List.of(MenuStyle.PREV, MenuStyle.NEXT, MenuStyle.EXIT));
        for (int s : new int[] {GamesMenu.PICK, GamesMenu.SCORES, GamesMenu.GUIDE, GamesMenu.BREAK, GamesMenu.NPCS}) {
            assertTrue(s > 45 && s < 53, s + " is in row 5, between the arrows");
            assertTrue(row5.add(s), "slot " + s + " holds one thing");
        }
    }
}
